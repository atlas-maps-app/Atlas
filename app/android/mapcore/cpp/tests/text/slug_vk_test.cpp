// The glyph shader on the host's Vulkan (lavapipe), under the validation layer (plan-vector D13, K13, T6): its coverage
// at probe samples equals the CPU's (text/SlugBands.cpp's coverage, the same arithmetic), read from the curve and band
// data as the frame's ground text binds them (storage buffers in set 2). The glyphs the frame draws are held to the CPU
// in tests/text/ground_text_vk_test.cpp.
#include "gen/layouts.hpp"
#include "gen/spirv/text/slug.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/Memory.hpp"
#include "gpu/Pipelines.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/text/text_support.hpp"
#include "text/Outlines.hpp"
#include "text/SlugBands.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <random>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

using namespace atlas;
namespace sl = layouts::text_slug;
namespace slug = text::slug;

/// Records with `record`, submits once and waits.
template <class F>
gpu::Result<void> submit(const gpu::Device& device, F&& record) {
    const vk::raii::Device& d = device.handle();
    ATLAS_VK_LET(pool,
                 d.createCommandPool(
                     {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device.queue_family()}),
                 "a command pool");
    ATLAS_VK_LET(commands,
                 d.allocateCommandBuffers(
                     {.commandPool = *pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                 "a command buffer");
    const vk::raii::CommandBuffer& cmd = commands.front();
    ATLAS_VK_CHECK(cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}), "the commands");
    std::forward<F>(record)(cmd);
    ATLAS_VK_CHECK(cmd.end(), "the commands");
    ATLAS_VK_LET(fence, d.createFence({}), "a fence");
    const vk::CommandBuffer raw = *cmd;
    ATLAS_VK_CHECK(device.queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, *fence),
                   "the submission");
    if (const vk::Result r = d.waitForFences(*fence, vk::True, UINT64_MAX); r != vk::Result::eSuccess)
        return gpu::fail(r, "the wait");
    return {};
}

/// A host-visible buffer holding `bytes` (at least 16 bytes, so an empty list still binds).
gpu::Result<gpu::Buffer> buffer_of(const gpu::Device& device, std::span<const std::byte> bytes,
                                   vk::BufferUsageFlags usage, gpu::MemoryUse use = gpu::MemoryUse::upload) {
    ATLAS_GPU_LET(b, device.memory().buffer(std::max<vk::DeviceSize>(bytes.size(), 16), usage, use));
    if (!bytes.empty()) std::memcpy(b.bytes().data(), bytes.data(), bytes.size());
    b.flush();
    return std::move(b);
}

template <class T>
std::span<const std::byte> bytes_of(const std::vector<T>& v) {
    return std::as_bytes(std::span(v));
}

/// The glyphs of the tests, in Geist 700, into one atlas.
struct Glyphs {
    slug::Atlas atlas;
    std::vector<slug::Glyph> glyphs;
    std::vector<std::vector<slug::Curve>> curves;
};

Glyphs geist_glyphs(const text::Fonts& fonts) {
    Glyphs out;
    const auto geist = fonts.instance({.face = text::Face::sans, .weight = 700});
    if (!geist) return out;
    for (const char32_t c : std::u32string(U"OAEGRS8&W@")) {
        const text::GlyphOutline o = text::outline(**geist, (*geist)->glyph_of(c));
        const auto g = out.atlas.add(o, 1000);
        if (!g) continue;
        out.glyphs.push_back(*g);
        out.curves.push_back(slug::em_curves(o, 1000));
    }
    return out;
}

/// A glyph's draw: its em-to-CSS map (size px per em, its em origin at the origin), white, slot 0.
sl::GlyphDraw draw_of(const slug::Glyph& g, float size) {
    sl::GlyphDraw s{};
    s.row0 = {size, 0.0F, 0.0F, 0.0F};
    s.row1 = {0.0F, -size, 0.0F, 0.0F};
    s.box = {g.box.x0, g.box.y0, g.box.x1, g.box.y1};
    s.bands = {g.scale_x, g.scale_y, g.offset_x, g.offset_y};
    s.location = {g.band_x, g.band_y, g.vbands - 1, g.hbands - 1};
    s.colour = {1.0F, 1.0F, 1.0F, 1.0F};
    s.info = {0, 0, 0, 0};
    return s;
}

/// The band texels as the frame's buffer holds them: each texel's two values, the first in the low half.
std::vector<std::uint32_t> packed_bands(const slug::Atlas& atlas) {
    std::vector<std::uint32_t> out;
    out.reserve(atlas.bands().size());
    for (const slug::BandTexel& t : atlas.bands()) out.push_back(std::uint32_t{t.a} | (std::uint32_t{t.b} << 16U));
    return out;
}

/// The identity coverage table.
std::vector<float> identity_table() {
    std::vector<float> t(256);
    for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<float>(i) / 255.0F;
    return t;
}

/// The probe's pipeline objects: set 0 empty (the probe reads no frame block), set 1 the glyphs' curves and bands at
/// their global bindings, set 2 the glyphs, the tables and the probes.
struct SlugPipes {
    vk::raii::DescriptorSetLayout empty{nullptr};
    vk::raii::DescriptorSetLayout globals_layout{nullptr};
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::PipelineLayout layout{nullptr};
    vk::raii::ShaderModule module{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    vk::raii::DescriptorSet globals{nullptr};
    vk::raii::DescriptorSet set{nullptr};
};

constexpr vk::ShaderStageFlags compute = vk::ShaderStageFlagBits::eCompute;
constexpr std::array<gpu::Binding, 2> global_bindings{{
    {.slot = sl::glyph_curves.binding, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
    {.slot = sl::glyph_bands.binding, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
}};
constexpr std::array<gpu::Binding, 4> slug_bindings{{
    {.slot = sl::glyph_draws.binding, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
    {.slot = sl::coverage_tables.binding, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
    {.slot = sl::probes.binding, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
    {.slot = sl::probed.binding, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
}};

gpu::Result<SlugPipes> slug_pipes(const gpu::Device& device) {
    const vk::raii::Device& d = device.handle();
    SlugPipes p;
    ATLAS_GPU_LET(empty, gpu::make_set_layout(d, {}));
    p.empty = std::move(empty);
    ATLAS_GPU_LET(globals_layout, gpu::make_set_layout(d, global_bindings));
    p.globals_layout = std::move(globals_layout);
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, slug_bindings));
    p.set_layout = std::move(set_layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{*p.empty, *p.globals_layout, *p.set_layout};
    ATLAS_GPU_LET(layout, gpu::make_layout(d, sets, 0, {}));
    p.layout = std::move(layout);
    ATLAS_GPU_LET(module, gpu::make_module(d, spirv::text_slug));
    p.module = std::move(module);
    const std::array<std::span<const gpu::Binding>, 2> kinds{global_bindings, slug_bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 1));
    p.pool = std::move(pool);
    ATLAS_GPU_LET(globals, gpu::allocate_set(d, p.pool, p.globals_layout));
    p.globals = std::move(globals);
    ATLAS_GPU_LET(set, gpu::allocate_set(d, p.pool, p.set_layout));
    p.set = std::move(set);
    return std::move(p);
}

} // namespace

TEST_CASE("the glyph shader's coverage at probe samples is the CPU's, on the host's Vulkan") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    const text::Fonts* fonts = test::bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const Glyphs g = geist_glyphs(*fonts);
    ATLAS_REQUIRE(g.glyphs.size() == 10);
    auto curves = buffer_of(device, std::as_bytes(g.atlas.curves()), vk::BufferUsageFlagBits::eStorageBuffer);
    ATLAS_REQUIRE(curves.has_value(), curves.error().text());
    auto bands = buffer_of(device, bytes_of(packed_bands(g.atlas)), vk::BufferUsageFlagBits::eStorageBuffer);
    ATLAS_REQUIRE(bands.has_value(), bands.error().text());
    std::vector<sl::GlyphDraw> instances;
    instances.reserve(g.glyphs.size());
    for (const auto& glyph : g.glyphs) instances.push_back(draw_of(glyph, 20.0F));
    // Probes: samples about each glyph at sizes from 2 to 400 px per em.
    std::mt19937 random(20261018); // NOLINT(cert-msc51-cpp): the same samples on every run
    std::vector<sl::SlugProbe> probes;
    std::vector<float> expected;
    for (std::uint32_t i = 0; i < g.glyphs.size(); ++i) {
        const slug::Glyph& glyph = g.glyphs[i];
        std::uniform_real_distribution<float> ux(glyph.box.x0 - 0.05F, glyph.box.x1 + 0.05F);
        std::uniform_real_distribution<float> uy(glyph.box.y0 - 0.05F, glyph.box.y1 + 0.05F);
        std::uniform_real_distribution<float> scale(2.0F, 400.0F);
        for (int k = 0; k < 2000; ++k) {
            const float x = ux(random);
            const float y = uy(random);
            const float ppx = scale(random);
            const float ppy = scale(random);
            probes.push_back({.sample = {x, y, ppx, ppy}, .glyph = {i, 0, 0, 0}});
            expected.push_back(slug::coverage(g.atlas, glyph, x, y, ppx, ppy));
        }
    }
    auto glyph_buffer = buffer_of(device, bytes_of(instances), vk::BufferUsageFlagBits::eStorageBuffer);
    auto table_buffer = buffer_of(device, bytes_of(identity_table()), vk::BufferUsageFlagBits::eStorageBuffer);
    auto probe_buffer = buffer_of(device, bytes_of(probes), vk::BufferUsageFlagBits::eStorageBuffer);
    auto probed = buffer_of(device, bytes_of(std::vector<float>(probes.size())),
                            vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::readback);
    ATLAS_REQUIRE(glyph_buffer && table_buffer && probe_buffer && probed);
    auto pipes = slug_pipes(device);
    ATLAS_REQUIRE(pipes.has_value(), pipes.error().text());
    gpu::SetWriter(*pipes->globals)
        .buffer(sl::glyph_curves.binding, vk::DescriptorType::eStorageBuffer, curves->handle())
        .buffer(sl::glyph_bands.binding, vk::DescriptorType::eStorageBuffer, bands->handle())
        .apply(device.handle());
    gpu::SetWriter(*pipes->set)
        .buffer(sl::glyph_draws.binding, vk::DescriptorType::eStorageBuffer, glyph_buffer->handle())
        .buffer(sl::coverage_tables.binding, vk::DescriptorType::eStorageBuffer, table_buffer->handle())
        .buffer(sl::probes.binding, vk::DescriptorType::eStorageBuffer, probe_buffer->handle())
        .buffer(sl::probed.binding, vk::DescriptorType::eStorageBuffer, probed->handle())
        .apply(device.handle());
    const vk::raii::PipelineCache no_cache{nullptr};
    auto probe = gpu::make_compute(device.handle(), no_cache, {.name = "slug probe", .module = "text_slug"},
                                   *pipes->module, "slug_probe", *pipes->layout);
    ATLAS_REQUIRE(probe.has_value(), probe.error().text());
    const auto ran = submit(device, [&](const vk::raii::CommandBuffer& cmd) {
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, **probe);
        const std::array<vk::DescriptorSet, 2> sets{*pipes->globals, *pipes->set};
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *pipes->layout, 1, sets, {});
        cmd.dispatch(static_cast<std::uint32_t>((probes.size() + 63) / 64), 1, 1);
        const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eShaderWrite,
                                        .dstAccessMask = vk::AccessFlagBits::eHostRead};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eComputeShader, vk::PipelineStageFlagBits::eHost, {}, to_host,
                            {}, {});
    });
    ATLAS_REQUIRE(ran.has_value(), ran.error().text());
    probed->invalidate();
    std::vector<float> got(probes.size());
    std::memcpy(got.data(), probed->bytes().data(), got.size() * sizeof(float));
    double worst = 0.0;
    double sum = 0.0;
    for (std::size_t i = 0; i < got.size(); ++i) {
        const double d = std::fabs(static_cast<double>(got[i]) - static_cast<double>(expected[i]));
        worst = std::max(worst, d);
        sum += d;
    }
    MESSAGE(got.size() << " probes on " << device.facts().name << ": the largest difference from the CPU " << worst
                       << ", the mean " << sum / static_cast<double>(got.size()));
    CHECK(worst < 1e-3);
    CHECK(sum / static_cast<double>(got.size()) < 1e-5);
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
