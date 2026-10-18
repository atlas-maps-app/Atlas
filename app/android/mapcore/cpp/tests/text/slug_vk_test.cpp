// The text shaders on the host's Vulkan (lavapipe), under the validation layer (plan-vector D13, K13, T6): the glyph
// shader's coverage at probe samples equals the CPU's (text/SlugBands.cpp's coverage, the same arithmetic); drawn,
// every glyph's pixels carry that coverage, its quad dilated far enough that nothing the CPU covers is cut off; and a
// label sprite is copied texel for texel onto whole pixels, its fade scaling it.
#include "gen/layouts.hpp"
#include "gen/spirv/text/slug.hpp"
#include "gen/spirv/text/sprite.hpp"
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
namespace sp = layouts::text_sprite;
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

/// An image the shaders read, its texels uploaded and left in SHADER_READ_ONLY_OPTIMAL.
struct Texture {
    gpu::Image image;
    vk::raii::ImageView view{nullptr};
};

gpu::Result<Texture> texture(const gpu::Device& device, vk::Format format, std::uint32_t width, std::uint32_t height,
                             std::span<const std::byte> texels) {
    Texture t;
    ATLAS_GPU_LET(
        image, device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = format,
                                      .extent = {.width = width, .height = height, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = vk::SampleCountFlagBits::e1,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = vk::ImageUsageFlagBits::eSampled | vk::ImageUsageFlagBits::eTransferDst,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     gpu::MemoryUse::gpu));
    t.image = std::move(image);
    ATLAS_GPU_LET(staging, buffer_of(device, texels, vk::BufferUsageFlagBits::eTransferSrc));
    const vk::ImageSubresourceRange range{
        .aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1};
    const vk::Image handle = t.image.handle();
    const auto uploaded = submit(device, [&](const vk::raii::CommandBuffer& cmd) {
        const vk::ImageMemoryBarrier to_copy{.srcAccessMask = {},
                                             .dstAccessMask = vk::AccessFlagBits::eTransferWrite,
                                             .oldLayout = vk::ImageLayout::eUndefined,
                                             .newLayout = vk::ImageLayout::eTransferDstOptimal,
                                             .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                                             .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                                             .image = handle,
                                             .subresourceRange = range};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTopOfPipe, vk::PipelineStageFlagBits::eTransfer, {}, {}, {},
                            to_copy);
        const vk::BufferImageCopy region{
            .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .mipLevel = 0, .layerCount = 1},
            .imageExtent = {.width = width, .height = height, .depth = 1}};
        cmd.copyBufferToImage(staging.handle(), handle, vk::ImageLayout::eTransferDstOptimal, region);
        const vk::ImageMemoryBarrier to_read{.srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                                             .dstAccessMask = vk::AccessFlagBits::eShaderRead,
                                             .oldLayout = vk::ImageLayout::eTransferDstOptimal,
                                             .newLayout = vk::ImageLayout::eShaderReadOnlyOptimal,
                                             .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                                             .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                                             .image = handle,
                                             .subresourceRange = range};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer,
                            vk::PipelineStageFlagBits::eFragmentShader | vk::PipelineStageFlagBits::eComputeShader, {},
                            {}, {}, to_read);
    });
    if (!uploaded) return std::unexpected(uploaded.error());
    ATLAS_VK_LET(
        view,
        device.handle().createImageView(
            {.image = handle, .viewType = vk::ImageViewType::e2D, .format = format, .subresourceRange = range}),
        "a texture's view");
    t.view = std::move(view);
    return std::move(t);
}

/// A colour target to draw into and read back.
struct Target {
    gpu::Image image;
    vk::raii::ImageView view{nullptr};
    vk::Format format = vk::Format::eUndefined;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
};

gpu::Result<Target> target(const gpu::Device& device, vk::Format format, std::uint32_t width, std::uint32_t height) {
    Target t{.format = format, .width = width, .height = height};
    ATLAS_GPU_LET(image, device.memory().image(
                             {.imageType = vk::ImageType::e2D,
                              .format = format,
                              .extent = {.width = width, .height = height, .depth = 1},
                              .mipLevels = 1,
                              .arrayLayers = 1,
                              .samples = vk::SampleCountFlagBits::e1,
                              .tiling = vk::ImageTiling::eOptimal,
                              .usage = vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eTransferSrc,
                              .initialLayout = vk::ImageLayout::eUndefined},
                             gpu::MemoryUse::gpu));
    t.image = std::move(image);
    ATLAS_VK_LET(
        view,
        device.handle().createImageView(
            {.image = t.image.handle(),
             .viewType = vk::ImageViewType::e2D,
             .format = format,
             .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1}}),
        "a target's view");
    t.view = std::move(view);
    return std::move(t);
}

/// Draws `count` instances of 6 vertices with `pipeline` into `t` (cleared to zero), then copies it into `readback`.
void draw_into(const vk::raii::CommandBuffer& cmd, const Target& t, const vk::raii::Pipeline& pipeline,
               const vk::raii::PipelineLayout& layout, vk::DescriptorSet set, std::span<const std::byte> push,
               std::uint32_t count, const gpu::Buffer& readback) {
    const vk::ImageSubresourceRange range{
        .aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1};
    const vk::ImageMemoryBarrier to_draw{.srcAccessMask = {},
                                         .dstAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
                                         .oldLayout = vk::ImageLayout::eUndefined,
                                         .newLayout = vk::ImageLayout::eColorAttachmentOptimal,
                                         .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                                         .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                                         .image = t.image.handle(),
                                         .subresourceRange = range};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTopOfPipe, vk::PipelineStageFlagBits::eColorAttachmentOutput, {},
                        {}, {}, to_draw);
    const vk::ClearValue clear{vk::ClearColorValue{std::array{0.0F, 0.0F, 0.0F, 0.0F}}};
    const vk::RenderingAttachmentInfo colour{.imageView = *t.view,
                                             .imageLayout = vk::ImageLayout::eColorAttachmentOptimal,
                                             .loadOp = vk::AttachmentLoadOp::eClear,
                                             .storeOp = vk::AttachmentStoreOp::eStore,
                                             .clearValue = clear};
    const vk::Rect2D area{.offset = {.x = 0, .y = 0}, .extent = {.width = t.width, .height = t.height}};
    cmd.beginRendering({.renderArea = area, .layerCount = 1, .colorAttachmentCount = 1, .pColorAttachments = &colour});
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout, 0, set, {});
    cmd.pushConstants<std::byte>(*layout, vk::ShaderStageFlagBits::eVertex, 0, push);
    const vk::Viewport viewport{.x = 0,
                                .y = 0,
                                .width = static_cast<float>(t.width),
                                .height = static_cast<float>(t.height),
                                .minDepth = 0,
                                .maxDepth = 1};
    cmd.setViewport(0, viewport);
    cmd.setScissor(0, area);
    cmd.draw(6, count, 0, 0);
    cmd.endRendering();
    const vk::ImageMemoryBarrier to_copy{.srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
                                         .dstAccessMask = vk::AccessFlagBits::eTransferRead,
                                         .oldLayout = vk::ImageLayout::eColorAttachmentOptimal,
                                         .newLayout = vk::ImageLayout::eTransferSrcOptimal,
                                         .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                                         .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                                         .image = t.image.handle(),
                                         .subresourceRange = range};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eColorAttachmentOutput, vk::PipelineStageFlagBits::eTransfer, {}, {},
                        {}, to_copy);
    const vk::BufferImageCopy region{
        .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .mipLevel = 0, .layerCount = 1},
        .imageExtent = {.width = t.width, .height = t.height, .depth = 1}};
    cmd.copyImageToBuffer(t.image.handle(), vk::ImageLayout::eTransferSrcOptimal, readback.handle(), region);
    const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                                    .dstAccessMask = vk::AccessFlagBits::eHostRead};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer, vk::PipelineStageFlagBits::eHost, {}, to_host, {}, {});
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

/// A glyph's instance: its em-to-pixel map (size px per em, turned by angle, its em origin at origin px), white.
sl::SlugGlyph instance_of(const slug::Glyph& g, float size, float angle, float shear, float ox, float oy) {
    const float cs = std::cos(angle); // NOLINT(bugprone-unsafe-functions): a test's placement, not the port's
    const float sn = std::sin(angle); // NOLINT(bugprone-unsafe-functions)
    // em (y up) to px (y down): [[size, size * shear], [0, -size]] turned by the angle
    const float m00 = size;
    const float m01 = size * shear;
    const float m10 = 0.0F;
    const float m11 = -size;
    sl::SlugGlyph s{};
    s.row0 = {(cs * m00) - (sn * m10), (cs * m01) - (sn * m11), ox, 0.0F};
    s.row1 = {(sn * m00) + (cs * m10), (sn * m01) + (cs * m11), oy, 0.0F};
    s.box = {g.box.x0, g.box.y0, g.box.x1, g.box.y1};
    s.bands = {g.scale_x, g.scale_y, g.offset_x, g.offset_y};
    s.location = {g.band_x, g.band_y, g.vbands - 1, g.hbands - 1};
    s.colour = {1.0F, 1.0F, 1.0F, 1.0F};
    s.table = {0, 0, 0, 0};
    return s;
}

/// The identity coverage table.
std::vector<float> identity_table() {
    std::vector<float> t(256);
    for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<float>(i) / 255.0F;
    return t;
}

/// The glyph shader's pipeline objects for a session.
struct SlugPipes {
    vk::raii::DescriptorSetLayout set_layout{nullptr};
    vk::raii::PipelineLayout layout{nullptr};
    vk::raii::ShaderModule module{nullptr};
    vk::raii::DescriptorPool pool{nullptr};
    vk::raii::DescriptorSet set{nullptr};
};

constexpr vk::ShaderStageFlags drawn_and_probed =
    vk::ShaderStageFlagBits::eFragment | vk::ShaderStageFlagBits::eCompute;
constexpr std::array<gpu::Binding, 6> slug_bindings{{
    {.slot = 0,
     .type = vk::DescriptorType::eStorageBuffer,
     .stages = drawn_and_probed | vk::ShaderStageFlagBits::eVertex},
    {.slot = 1, .type = vk::DescriptorType::eSampledImage, .stages = drawn_and_probed},
    {.slot = 2, .type = vk::DescriptorType::eSampledImage, .stages = drawn_and_probed},
    {.slot = 3, .type = vk::DescriptorType::eStorageBuffer, .stages = drawn_and_probed},
    {.slot = 4, .type = vk::DescriptorType::eStorageBuffer, .stages = vk::ShaderStageFlagBits::eCompute},
    {.slot = 5, .type = vk::DescriptorType::eStorageBuffer, .stages = vk::ShaderStageFlagBits::eCompute},
}};

gpu::Result<SlugPipes> slug_pipes(const gpu::Device& device) {
    const vk::raii::Device& d = device.handle();
    SlugPipes p;
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, slug_bindings));
    p.set_layout = std::move(set_layout);
    const vk::DescriptorSetLayout raw = *p.set_layout;
    ATLAS_GPU_LET(layout,
                  gpu::make_layout(d, std::span(&raw, 1), sizeof(sl::PushConstants), vk::ShaderStageFlagBits::eVertex));
    p.layout = std::move(layout);
    ATLAS_GPU_LET(module, gpu::make_module(d, spirv::text_slug));
    p.module = std::move(module);
    const std::array<std::span<const gpu::Binding>, 1> kinds{slug_bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 1));
    p.pool = std::move(pool);
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
    auto curves = texture(device, vk::Format::eR32G32B32A32Sfloat, slug::texture_width, g.atlas.curve_rows(),
                          std::as_bytes(g.atlas.curves()));
    ATLAS_REQUIRE(curves.has_value(), curves.error().text());
    auto bands = texture(device, vk::Format::eR16G16Uint, slug::texture_width, g.atlas.band_rows(),
                         std::as_bytes(g.atlas.bands()));
    ATLAS_REQUIRE(bands.has_value(), bands.error().text());
    std::vector<sl::SlugGlyph> instances;
    instances.reserve(g.glyphs.size());
    for (const auto& glyph : g.glyphs) instances.push_back(instance_of(glyph, 20.0F, 0.0F, 0.0F, 0.0F, 0.0F));
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
    gpu::SetWriter(*pipes->set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, glyph_buffer->handle())
        .image(1, vk::DescriptorType::eSampledImage, *curves->view, vk::ImageLayout::eShaderReadOnlyOptimal)
        .image(2, vk::DescriptorType::eSampledImage, *bands->view, vk::ImageLayout::eShaderReadOnlyOptimal)
        .buffer(3, vk::DescriptorType::eStorageBuffer, table_buffer->handle())
        .buffer(4, vk::DescriptorType::eStorageBuffer, probe_buffer->handle())
        .buffer(5, vk::DescriptorType::eStorageBuffer, probed->handle())
        .apply(device.handle());
    const vk::raii::PipelineCache no_cache{nullptr};
    auto compute = gpu::make_compute(device.handle(), no_cache, {.name = "slug probe", .module = "text_slug"},
                                     *pipes->module, "slug_probe", *pipes->layout);
    ATLAS_REQUIRE(compute.has_value(), compute.error().text());
    const auto ran = submit(device, [&](const vk::raii::CommandBuffer& cmd) {
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, **compute);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *pipes->layout, 0, *pipes->set, {});
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

TEST_CASE("drawn, each glyph's pixels carry the CPU's coverage, its quad dilated far enough") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    if (!device.enabled().dynamic_rendering) {
        MESSAGE("skipped: " << device.facts().name << " has no dynamic rendering");
        return;
    }
    const text::Fonts* fonts = test::bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const Glyphs g = geist_glyphs(*fonts);
    ATLAS_REQUIRE(g.glyphs.size() == 10);
    auto curves = texture(device, vk::Format::eR32G32B32A32Sfloat, slug::texture_width, g.atlas.curve_rows(),
                          std::as_bytes(g.atlas.curves()));
    auto bands = texture(device, vk::Format::eR16G16Uint, slug::texture_width, g.atlas.band_rows(),
                         std::as_bytes(g.atlas.bands()));
    ATLAS_REQUIRE(curves && bands);
    // Ten glyphs in a row, each in its own cell of 96 px, at sizes from 6 to 44 px, turned and sheared.
    constexpr std::uint32_t cell = 96;
    const std::uint32_t width = cell * static_cast<std::uint32_t>(g.glyphs.size());
    const std::uint32_t height = cell;
    std::vector<sl::SlugGlyph> instances;
    for (std::size_t i = 0; i < g.glyphs.size(); ++i) {
        const float size = 6.0F + (4.2F * static_cast<float>(i));
        const float angle = -0.6F + (0.17F * static_cast<float>(i));
        const float shear = i % 3 == 0 ? 0.25F : 0.0F;
        instances.push_back(
            instance_of(g.glyphs[i], size, angle, shear, (static_cast<float>(i) * cell) + 30.3F, 62.6F));
    }
    auto glyph_buffer = buffer_of(device, bytes_of(instances), vk::BufferUsageFlagBits::eStorageBuffer);
    auto table_buffer = buffer_of(device, bytes_of(identity_table()), vk::BufferUsageFlagBits::eStorageBuffer);
    auto unused = buffer_of(device, {}, vk::BufferUsageFlagBits::eStorageBuffer);
    auto out = target(device, vk::Format::eR32G32B32A32Sfloat, width, height);
    auto readback = device.memory().buffer(vk::DeviceSize{width} * height * 16, vk::BufferUsageFlagBits::eTransferDst,
                                           gpu::MemoryUse::readback);
    ATLAS_REQUIRE(glyph_buffer && table_buffer && unused && out && readback);
    auto pipes = slug_pipes(device);
    ATLAS_REQUIRE(pipes.has_value(), pipes.error().text());
    gpu::SetWriter(*pipes->set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, glyph_buffer->handle())
        .image(1, vk::DescriptorType::eSampledImage, *curves->view, vk::ImageLayout::eShaderReadOnlyOptimal)
        .image(2, vk::DescriptorType::eSampledImage, *bands->view, vk::ImageLayout::eShaderReadOnlyOptimal)
        .buffer(3, vk::DescriptorType::eStorageBuffer, table_buffer->handle())
        .buffer(4, vk::DescriptorType::eStorageBuffer, unused->handle())
        .buffer(5, vk::DescriptorType::eStorageBuffer, unused->handle())
        .apply(device.handle());
    gpu::GraphicsDesc desc{.label = {.name = "slug", .module = "text_slug"},
                           .module = *pipes->module,
                           .vertex = "slug_vs",
                           .fragment = "slug_fs",
                           .layout = *pipes->layout,
                           .target = {.colour_formats = {vk::Format::eR32G32B32A32Sfloat}, .colour_count = 1}};
    desc.blends[0] = gpu::Blend::opaque();
    const vk::raii::PipelineCache no_cache{nullptr};
    auto pipeline = gpu::make_graphics(device.handle(), no_cache, desc);
    ATLAS_REQUIRE(pipeline.has_value(), pipeline.error().text());
    const sl::PushConstants push{.screen = {static_cast<float>(width), static_cast<float>(height),
                                            1.0F / static_cast<float>(width), 1.0F / static_cast<float>(height)}};
    const auto drawn = submit(device, [&](const vk::raii::CommandBuffer& cmd) {
        draw_into(cmd, *out, *pipeline, pipes->layout, *pipes->set, std::as_bytes(std::span(&push, 1)),
                  static_cast<std::uint32_t>(instances.size()), *readback);
    });
    ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    readback->invalidate();
    std::vector<float> pixels(static_cast<std::size_t>(width) * height * 4);
    std::memcpy(pixels.data(), readback->bytes().data(), pixels.size() * sizeof(float));
    // Each pixel against the CPU's coverage at its centre, by the glyph whose cell it is in.
    double worst = 0.0;
    std::size_t covered = 0;
    std::size_t missed = 0;
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::size_t i = x / cell;
            const sl::SlugGlyph& s = instances[i];
            const float a = s.row0[0];
            const float b = s.row0[1];
            const float c = s.row1[0];
            const float d = s.row1[1];
            const float det = (a * d) - (b * c);
            const float px = (static_cast<float>(x) + 0.5F) - s.row0[2];
            const float py = (static_cast<float>(y) + 0.5F) - s.row1[2];
            const float ex = ((d * px) - (b * py)) / det;
            const float ey = ((-c * px) + (a * py)) / det;
            const float ppx = std::fabs(det) / (std::fabs(d) + std::fabs(b));
            const float ppy = std::fabs(det) / (std::fabs(c) + std::fabs(a));
            const float cpu = slug::coverage(g.atlas, g.glyphs[i], ex, ey, ppx, ppy);
            const float gpu_value = pixels[((static_cast<std::size_t>(y) * width) + x) * 4 + 3];
            worst = std::max(worst, std::fabs(static_cast<double>(gpu_value) - static_cast<double>(cpu)));
            covered += cpu > 0.0F ? 1U : 0U;
            missed += cpu > 0.002F && gpu_value == 0.0F ? 1U : 0U;
        }
    }
    MESSAGE(covered << " covered pixels of " << width * height << " on " << device.facts().name
                    << ": the largest difference from the CPU " << worst << ", " << missed << " cut off");
    CHECK(covered > 2000);
    CHECK(missed == 0);
    CHECK(worst < 0.02); // the rasteriser's interpolated em coordinate and derivatives against the exact inverse
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}

TEST_CASE("a label sprite is copied texel for texel onto whole pixels, and its fade scales it") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    if (!device.enabled().dynamic_rendering) {
        MESSAGE("skipped: " << device.facts().name << " has no dynamic rendering");
        return;
    }
    // An atlas of 32 x 16 premultiplied texels, two sprites in it.
    constexpr std::uint32_t aw = 32;
    constexpr std::uint32_t ah = 16;
    std::vector<std::uint8_t> atlas(static_cast<std::size_t>(aw) * ah * 4);
    std::mt19937 random(7); // NOLINT(cert-msc51-cpp): the same texels on every run
    for (std::size_t p = 0; p < atlas.size(); p += 4) {
        const auto alpha = static_cast<std::uint8_t>(random() % 256);
        for (std::size_t k = 0; k < 3; ++k) atlas[p + k] = static_cast<std::uint8_t>(random() % (alpha + 1U));
        atlas[p + 3] = alpha;
    }
    auto tex = texture(device, vk::Format::eR8G8B8A8Unorm, aw, ah, std::as_bytes(std::span(atlas)));
    ATLAS_REQUIRE(tex.has_value(), tex.error().text());
    const std::vector<sp::SpriteDraw> draws{
        {.target = {5, 3, 12, 9}, .source = {2, 1, 0, 0}, .fade = {1.0F, 0, 0, 0}},
        {.target = {30, 20, 16, 10}, .source = {14, 4, 0, 0}, .fade = {0.5F, 0, 0, 0}},
    };
    auto draw_buffer = buffer_of(device, bytes_of(draws), vk::BufferUsageFlagBits::eStorageBuffer);
    constexpr std::uint32_t width = 64;
    constexpr std::uint32_t height = 40;
    auto out = target(device, vk::Format::eR8G8B8A8Unorm, width, height);
    auto readback = device.memory().buffer(vk::DeviceSize{width} * height * 4, vk::BufferUsageFlagBits::eTransferDst,
                                           gpu::MemoryUse::readback);
    ATLAS_REQUIRE(draw_buffer && out && readback);
    const vk::raii::Device& d = device.handle();
    constexpr std::array<gpu::Binding, 2> bindings{{
        {.slot = 0,
         .type = vk::DescriptorType::eStorageBuffer,
         .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment},
        {.slot = 1, .type = vk::DescriptorType::eSampledImage, .stages = vk::ShaderStageFlagBits::eFragment},
    }};
    auto set_layout = gpu::make_set_layout(d, bindings);
    ATLAS_REQUIRE(set_layout.has_value());
    const vk::DescriptorSetLayout raw = **set_layout;
    auto layout = gpu::make_layout(d, std::span(&raw, 1), sizeof(sp::PushConstants), vk::ShaderStageFlagBits::eVertex);
    auto module = gpu::make_module(d, spirv::text_sprite);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    auto pool = gpu::make_pool(d, kinds, 1);
    ATLAS_REQUIRE(layout && module && pool);
    auto set = gpu::allocate_set(d, *pool, *set_layout);
    ATLAS_REQUIRE(set.has_value());
    gpu::SetWriter(**set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, draw_buffer->handle())
        .image(1, vk::DescriptorType::eSampledImage, *tex->view, vk::ImageLayout::eShaderReadOnlyOptimal)
        .apply(d);
    gpu::GraphicsDesc desc{.label = {.name = "sprite", .module = "text_sprite"},
                           .module = **module,
                           .vertex = "sprite_vs",
                           .fragment = "sprite_fs",
                           .layout = **layout,
                           .target = {.colour_formats = {vk::Format::eR8G8B8A8Unorm}, .colour_count = 1}};
    desc.blends[0] = gpu::Blend::over();
    const vk::raii::PipelineCache no_cache{nullptr};
    auto pipeline = gpu::make_graphics(d, no_cache, desc);
    ATLAS_REQUIRE(pipeline.has_value(), pipeline.error().text());
    const sp::PushConstants push{.screen = {width, height, 1.0F / width, 1.0F / height}};
    const auto drawn = submit(device, [&](const vk::raii::CommandBuffer& cmd) {
        draw_into(cmd, *out, *pipeline, *layout, **set, std::as_bytes(std::span(&push, 1)), 2, *readback);
    });
    ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    readback->invalidate();
    std::vector<std::uint8_t> pixels(static_cast<std::size_t>(width) * height * 4);
    std::memcpy(pixels.data(), readback->bytes().data(), pixels.size());
    std::size_t exact = 0;
    std::size_t faded = 0;
    std::size_t elsewhere = 0;
    int worst_fade = 0;
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint8_t* got = &pixels[((static_cast<std::size_t>(y) * width) + x) * 4];
            const sp::SpriteDraw* hit = nullptr;
            for (const auto& s : draws) {
                if (std::cmp_greater_equal(x, s.target[0]) && std::cmp_less(x, s.target[0] + s.target[2]) &&
                    std::cmp_greater_equal(y, s.target[1]) && std::cmp_less(y, s.target[1] + s.target[3])) {
                    hit = &s;
                }
            }
            if (hit == nullptr) {
                elsewhere += (got[0] | got[1] | got[2] | got[3]) == 0 ? 0U : 1U;
                continue;
            }
            const auto sx = static_cast<std::size_t>(static_cast<std::int64_t>(x) - hit->target[0] + hit->source[0]);
            const auto sy = static_cast<std::size_t>(static_cast<std::int64_t>(y) - hit->target[1] + hit->source[1]);
            const std::uint8_t* want = &atlas[((sy * aw) + sx) * 4];
            if (hit->fade[0] == 1.0F) {
                exact += std::equal(got, got + 4, want) ? 1U : 0U;
            } else {
                ++faded;
                for (int k = 0; k < 4; ++k) {
                    const int expected = static_cast<int>(std::lround(want[k] * 0.5));
                    worst_fade = std::max(worst_fade, std::abs(static_cast<int>(got[k]) - expected));
                }
            }
        }
    }
    CHECK(exact == 12U * 9U);
    CHECK(faded == 16U * 10U);
    CHECK(worst_fade <= 1);
    CHECK(elsewhere == 0);
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
