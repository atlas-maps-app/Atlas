// The screen fields made on the GPU (fx/GpuFields.hpp, shaders/fx/fields.slang) on the host's Vulkan (lavapipe on this
// Mac and on CI, under the validation layer): the byte check passes, the fields' kept bytes among it; every field at
// S1 and at the Pixel 10 Pro's size made both ways, the GPU's bytes the CPU's and fields.inc's (the port's), and the
// images uploaded from each byte for byte the same as the shaders read them; the first start's field time both ways,
// printed; and the provider's GPU path (fx/FieldCache.hpp's make_on_gpu): a size the disk does not hold made before the
// frame and bound for it, kept on disk by the worker and mapped at the next start, a resize's made again, and a size on
// disk mapped.
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/FieldCache.hpp"
#include "fx/GpuFields.hpp"
#include "gen/spirv/fx/mat.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#if !defined(__ANDROID__)
#include "gpu/Offscreen.hpp"
#endif

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <format>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

using namespace atlas;
using fx::FieldSize;
using clock = std::chrono::steady_clock;

/// Under ThreadSanitizer the phone's size is left to the other builds (S1 holds the threads).
#if defined(__has_feature)
#if __has_feature(thread_sanitizer)
constexpr bool thread_sanitizer = true;
#else
constexpr bool thread_sanitizer = false;
#endif
#else
constexpr bool thread_sanitizer = false;
#endif

[[nodiscard]] double ms_since(clock::time_point started) {
    return std::chrono::duration<double, std::milli>(clock::now() - started).count();
}

[[nodiscard]] const fx::FieldReference& reference(std::string_view name) {
    for (const fx::FieldReference& r : fx::field_references()) {
        if (r.name == name) return r;
    }
    FAIL("fields.inc has no size " << std::string(name));
    return fx::field_references().front();
}

/// Records into a one-shot command buffer, submits it and waits.
template <class Record>
void run_once(const gpu::Device& device, Record record) {
    const vk::raii::Device& d = device.handle();
    auto pool = d.createCommandPool({.queueFamilyIndex = device.queue_family()});
    ATLAS_REQUIRE(pool.has_value(), vk::to_string(pool.error()));
    auto commands = d.allocateCommandBuffers(
        {.commandPool = **pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1});
    ATLAS_REQUIRE(commands.has_value(), vk::to_string(commands.error()));
    const vk::raii::CommandBuffer& cmd = commands->front();
    auto begun = cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit});
    ATLAS_REQUIRE(begun.has_value(), vk::to_string(begun.error()));
    record(cmd);
    auto ended = cmd.end();
    ATLAS_REQUIRE(ended.has_value(), vk::to_string(ended.error()));
    auto fence = d.createFence({});
    ATLAS_REQUIRE(fence.has_value(), vk::to_string(fence.error()));
    const vk::CommandBuffer raw = *cmd;
    auto submitted = device.queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, **fence);
    ATLAS_REQUIRE(submitted.has_value(), vk::to_string(submitted.error()));
    const vk::Result waited = d.waitForFences(**fence, vk::True, UINT64_MAX);
    ATLAS_REQUIRE(waited == vk::Result::eSuccess, vk::to_string(waited));
}

/// A probe as mat.slang's FxProbe: an upright pixel and a colour's levels.
struct Probe {
    std::int32_t x;
    std::int32_t y;
    std::uint32_t colour;
    std::uint32_t unused;
};

/// The four images' bytes as the shaders read them once `images` uploaded what it staged as frame `frame` (every
/// earlier one finished): mat.slang's fields_check at every pixel through set 1 of `layout` (fields_vk_test's way),
/// rows from the top, four bytes a pixel an image (the canopy's one).
[[nodiscard]] std::array<std::vector<std::uint8_t>, 4> read_back(const gpu::Device& device, fx::FieldImages& images,
                                                                 frame::FrameLayout& layout, std::uint64_t frame) {
    std::array<std::vector<std::uint8_t>, 4> out;
    const gpu::Sync sync(device.enabled().synchronization2);
    run_once(device, [&](const vk::raii::CommandBuffer& cmd) { images.record_upload(cmd, sync, frame); });
    const vk::raii::Device& d = device.handle();
    layout.set_image(frame::Global::noise_rgba, images.view(0), images.sampler());
    layout.set_image(frame::Global::noise_rg, images.view(1), images.sampler());
    layout.set_image(frame::Global::noise_r, images.view(2), images.sampler());
    layout.set_image(frame::Global::noise_canopy, images.view(3), images.canopy_sampler());
    layout.set_buffer(frame::Global::byte_tables, images.tables());
    auto begun = layout.begin_frame(frame, frame - 1, 1, 1);
    ATLAS_REQUIRE_OR_RETURN(out, begun.has_value(), begun.error().text());
    const std::array set2{
        gpu::Binding{
            .slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = vk::ShaderStageFlagBits::eCompute},
        gpu::Binding{
            .slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = vk::ShaderStageFlagBits::eCompute}};
    auto set_layout = gpu::make_set_layout(d, set2);
    ATLAS_REQUIRE_OR_RETURN(out, set_layout.has_value(), set_layout.error().text());
    const std::array<vk::DescriptorSetLayout, 1> extra{**set_layout};
    auto pipeline_layout = layout.make_layout(extra);
    ATLAS_REQUIRE_OR_RETURN(out, pipeline_layout.has_value(), pipeline_layout.error().text());
    auto module = gpu::make_module(d, spirv::fx_mat);
    ATLAS_REQUIRE_OR_RETURN(out, module.has_value(), module.error().text());
    const vk::raii::PipelineCache no_cache{nullptr};
    auto pipeline = gpu::make_compute(d, no_cache, {.name = "fields_check", .module = "fx_mat"}, **module,
                                      "fields_check", **pipeline_layout);
    ATLAS_REQUIRE_OR_RETURN(out, pipeline.has_value(), pipeline.error().text());
    const FieldSize size = images.size();
    std::vector<Probe> every;
    every.reserve(static_cast<std::size_t>(size.width) * size.height);
    for (std::uint32_t y = 0; y < size.height; ++y) {
        for (std::uint32_t x = 0; x < size.width; ++x) {
            every.push_back({.x = static_cast<std::int32_t>(x), .y = static_cast<std::int32_t>(y)});
        }
    }
    const vk::DeviceSize in_bytes = every.size() * sizeof(Probe);
    const vk::DeviceSize out_bytes = every.size() * 16;
    auto in = device.memory().buffer(in_bytes, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
    auto results = device.memory().buffer(out_bytes, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::readback);
    ATLAS_REQUIRE_OR_RETURN(out, in.has_value() && results.has_value());
    std::memcpy(in->bytes().data(), every.data(), in_bytes);
    in->flush();
    const std::array<std::span<const gpu::Binding>, 1> kinds{set2};
    auto pool = gpu::make_pool(d, kinds, 1);
    ATLAS_REQUIRE_OR_RETURN(out, pool.has_value(), pool.error().text());
    auto set = gpu::allocate_set(d, *pool, *set_layout);
    ATLAS_REQUIRE_OR_RETURN(out, set.has_value(), set.error().text());
    gpu::SetWriter(**set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, in->handle())
        .buffer(1, vk::DescriptorType::eStorageBuffer, results->handle())
        .apply(d);
    run_once(device, [&](const vk::raii::CommandBuffer& cmd) {
        layout.record_setup(cmd, sync);
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, **pipeline);
        const auto sets = layout.sets();
        const auto offsets = layout.offsets(0);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, **pipeline_layout, 0, sets, offsets);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, **pipeline_layout, 2, **set, {});
        cmd.dispatch(static_cast<std::uint32_t>((every.size() + 63) / 64), 1, 1);
        const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eShaderWrite,
                                        .dstAccessMask = vk::AccessFlagBits::eHostRead};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eComputeShader, vk::PipelineStageFlagBits::eHost, {}, to_host,
                            {}, {});
    });
    results->invalidate();
    // Each probe's answer: the rgba, rg and r images' words at its pixel, then the canopy's byte.
    const auto* words = reinterpret_cast<const std::uint32_t*>(results->bytes().data());
    for (std::size_t k = 0; k < 3; ++k) {
        out.at(k).resize(every.size() * 4);
        for (std::size_t i = 0; i < every.size(); ++i) {
            const std::uint32_t w = words[(i * 4) + k];
            for (std::size_t b = 0; b < 4; ++b) out.at(k)[(i * 4) + b] = static_cast<std::uint8_t>(w >> (8U * b));
        }
    }
    out[3].resize(every.size());
    for (std::size_t i = 0; i < every.size(); ++i) out[3][i] = static_cast<std::uint8_t>(words[(i * 4) + 3]);
    return out;
}

#if !defined(__ANDROID__)
/// A world of no tiles whose one provider of set 1 is the fields'.
class FieldsScene final : public frame::FrameScene {
public:
    explicit FieldsScene(frame::GlobalProvider& fields) noexcept : globals_{&fields} {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return nullptr; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return globals_; }

private:
    std::array<frame::GlobalProvider*, 1> globals_;
};
#endif

} // namespace

TEST_CASE("the GPU's byte check holds its noises and its fields' kept bytes to the CPU's") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto made = fx::GpuFields::create(device, *cache, nullptr);
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const auto started = clock::now();
    const auto check = (*made)->check();
    ATLAS_REQUIRE(check.has_value(), check.error().text());
    CHECK_MESSAGE(check->exact, check->first);
    // The eleven noises over two blocks of 64 × 64, then the eight fields over the same blocks.
    CHECK(check->pixels == (std::size_t{11} * 2 * 64 * 64) + (fx::field_count * 2 * 64 * 64));
    CHECK(check->differ == 0);
    MESSAGE("the byte check: " << check->pixels << " pixels in " << ms_since(started) << " ms");
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}

TEST_CASE("every field at S1 and at the Pixel 10 Pro's size made on the GPU is the CPU's, byte for byte, through the "
          "images; the first start's time both ways") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    // An empty pipeline cache: the first start of all, its pipelines compiled.
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const auto first = clock::now();
    auto made = fx::GpuFields::create(device, *cache, nullptr);
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const double pipeline_ms = ms_since(first);
    const auto checked = (*made)->check();
    ATLAS_REQUIRE(checked.has_value(), checked.error().text());
    ATLAS_REQUIRE(checked->exact, checked->first);
    const double check_ms = ms_since(first) - pipeline_ms;
    auto layout = frame::FrameLayout::create(device);
    ATLAS_REQUIRE(layout.has_value(), layout.error().text());
    std::uint64_t frame = 0;
    std::vector<std::string_view> sizes{"S1"};
    if (!thread_sanitizer) sizes.emplace_back("pixel10pro");
    for (const std::string_view name : sizes) {
        CAPTURE(std::string(name));
        const fx::FieldReference& ref = reference(name);
        const FieldSize size = ref.size;
        // The worker's make (FieldCacheOptions' three threads) against the GPU's.
        auto started = clock::now();
        const std::shared_ptr<const fx::ScreenFields> cpu = fx::make_fields(size, {.threads = 3});
        const double cpu_ms = ms_since(started);
        started = clock::now();
        auto gpu = (*made)->make(size);
        ATLAS_REQUIRE(gpu.has_value(), gpu.error().text());
        const double gpu_ms = ms_since(started);
        const fx::ScreenFields& g = *gpu->fields;
        CHECK(g.size() == size);
        CHECK_FALSE(g.mapped());
        CHECK(std::ranges::equal(g.rgba(), cpu->rgba()));
        CHECK(std::ranges::equal(g.rg(), cpu->rg()));
        CHECK(std::ranges::equal(g.r(), cpu->r()));
        CHECK(std::ranges::equal(g.canopy(), cpu->canopy()));
        for (std::size_t f = 0; f < fx::field_count; ++f) {
            CAPTURE(std::string(fx::screen_fields[f].name));
            CHECK(fx::hash_of(g.bytes_of(static_cast<fx::Field>(f))) == ref.kept[f]);
        }
        // The images: uploaded from the CPU's bytes, and from the GPU's own buffer, each read as the shaders read it.
        auto from_cpu = fx::FieldImages::create(device);
        auto from_gpu = fx::FieldImages::create(device);
        ATLAS_REQUIRE(from_cpu.has_value() && from_gpu.has_value());
        auto staged = (*from_cpu)->stage(*cpu);
        ATLAS_REQUIRE(staged.has_value(), staged.error().text());
        staged = (*from_gpu)->stage(size, gpu->buffer);
        ATLAS_REQUIRE(staged.has_value(), staged.error().text());
        const auto cpu_images = read_back(device, **from_cpu, **layout, ++frame);
        const auto gpu_images = read_back(device, **from_gpu, **layout, ++frame);
        const std::array<std::span<const std::uint8_t>, 4> planes{cpu->rgba(), cpu->rg(), cpu->r(), cpu->canopy()};
        for (std::size_t i = 0; i < 4; ++i) {
            CAPTURE(i);
            CHECK(std::ranges::equal(cpu_images.at(i), planes.at(i)));
            CHECK(std::ranges::equal(gpu_images.at(i), cpu_images.at(i)));
        }
        MESSAGE(std::format("fields {}x{} at a first start: the CPU's {:.0f} ms on 3 threads; the GPU's {:.0f} ms "
                            "(its commands {:.0f} ms), after its pipeline {:.0f} ms and the byte check {:.0f} ms once",
                            size.width, size.height, cpu_ms, gpu_ms, gpu->ms, pipeline_ms, check_ms));
    }
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}

#if !defined(__ANDROID__)
TEST_CASE("the fields' provider makes a size the disk does not hold on the GPU before its frame, which draws with "
          "them; the worker keeps them on disk, and maps them at the next start") {
    namespace fs = std::filesystem;
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const fs::path dir = fs::path(ATLAS_TEST_SCRATCH) / "fields-gpu-provider";
    std::error_code ec;
    fs::remove_all(dir, ec);
    const FieldSize wide_size = fx::size_of(96, 72, 2.0);
    const FieldSize tall_size = fx::size_of(72, 96, 2.0);
    {
        auto made = fx::FieldCache::create(device, {.dir = dir, .threads = 2, .gpu = true, .pipelines = &*cache});
        ATLAS_REQUIRE(made.has_value(), made.error().text());
        fx::FieldCache& fields = **made;
        FieldsScene scene(fields);
        auto builder = frame::FrameBuilder::create(device, *cache, frame::make_passes());
        ATLAS_REQUIRE(builder.has_value(), builder.error().text());
        auto output = gpu::Offscreen::create(device, {.width = 96, .height = 72}, 2);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        const style::CameraState wide = standin::board_view(96, 72, 2.0);
        const style::CameraState tall = standin::board_view(72, 96, 2.0);
        (*builder)->set_inputs({.camera = wide, .mode = frame::Mode::full, .scene = &scene});
        auto renderer = gpu::Renderer::create(device, **output, **builder, device.facts().choice);
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        auto draw = [&] {
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
        };
        auto fields_on = [&] { return ((*builder)->state().block.info.x & shaders::FRAME_FIELDS) != 0U; };

        // Due for the GPU (the disk holds nothing), made before the first frame, which draws with them.
        CHECK(fields.gpu_due(wide_size));
        CHECK(fields.make_on_gpu(wide_size));
        CHECK(fields.gpu_off().empty());
        CHECK(fields.bound_for(wide_size));
        CHECK_FALSE(fields.gpu_due(wide_size));
        draw();
        CHECK(fields_on());
        CHECK(fields.images().size() == wide_size);
        // The worker has them as its latest, and keeps them on disk: the CPU's bytes.
        fields.worker().wait_idle();
        ATLAS_REQUIRE(fields.worker().latest() != nullptr);
        CHECK(fields.worker().latest()->size() == wide_size);
        const auto kept = fx::FieldDisk(dir).read(wide_size);
        ATLAS_REQUIRE(kept != nullptr, "the GPU's fields were not kept on disk");
        const auto cpu = fx::make_fields(wide_size, {.threads = 2});
        CHECK(std::ranges::equal(kept->rgba(), cpu->rgba()));
        CHECK(std::ranges::equal(kept->rg(), cpu->rg()));
        CHECK(std::ranges::equal(kept->r(), cpu->r()));
        for (int n = 0; n < 3; ++n) draw();
        CHECK(fields_on());
        CHECK(fields.images().retired() == 0);

        // A resize: the new size made on the GPU before its frame, the old images kept until their frames finish.
        (*builder)->set_inputs({.camera = tall, .mode = frame::Mode::full, .scene = &scene});
        CHECK(fields.gpu_due(tall_size));
        CHECK(fields.make_on_gpu(tall_size));
        draw();
        CHECK(fields_on());
        CHECK(fields.images().size() == tall_size);
        CHECK(fields.images().retired() == 2); // the wide images and the GPU's buffer
        // Back to the first size, on disk now: the worker maps it, as the CPU's path does, the GPU making nothing.
        (*builder)->set_inputs({.camera = wide, .mode = frame::Mode::full, .scene = &scene});
        CHECK_FALSE(fields.gpu_due(wide_size));
        draw();
        fields.worker().wait_idle();
        CHECK(fields.worker().last_from_disk());
        draw();
        CHECK(fields_on());
        CHECK(fields.images().size() == wide_size);
        (*renderer)->drain();
        renderer->reset();
    }
    // The next start: both sizes on disk, nothing due for the GPU.
    auto again = fx::FieldCache::create(device, {.dir = dir, .threads = 2, .gpu = true, .pipelines = &*cache});
    ATLAS_REQUIRE(again.has_value(), again.error().text());
    CHECK_FALSE((*again)->gpu_due(wide_size));
    CHECK_FALSE((*again)->gpu_due(tall_size));
    CHECK((*again)->make_on_gpu(tall_size)); // nothing to do
    CHECK_FALSE((*again)->bound_for(tall_size));
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
    fs::remove_all(dir, ec);
}
#endif
