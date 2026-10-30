// The fields and the byte tables on the GPU (fx/FieldCache.hpp, shaders/fx/mat.slang) on the host's Vulkan (lavapipe
// on this Mac and on CI, under the validation layer). FieldImages: a size's fields staged and uploaded into set 1
// through the frame's own layout (frame/FrameLayout.hpp), and the device's reads of them, through the functions the
// scene's shaders call, against the CPU's bytes: every byte of every field at every pixel (L2 (c), D16) and `mat`'s
// grey and opaque levels at a spread of pixels and colours; a second size replaces the first, which is kept until its
// frame has finished. FieldCache: the provider of set 1 in the frame (FrameBuilder, the renderer, an offscreen
// output), its fields bound for the frame's size once its worker has made them, FRAME_FIELDS in full mode alone, a
// resize's images replacing the old ones once made, and set 1 never written at rest.
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/ByteTables.hpp"
#include "fx/FieldCache.hpp"
#include "fx/Mat.hpp"
#include "gen/spirv/fx/mat.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"
#include "standin/View.hpp"
#include "tests/base/fma_inputs.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#if !defined(__ANDROID__)
#include "gpu/Offscreen.hpp"
#endif

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <memory>
#include <span>
#include <vector>

namespace {

using namespace atlas;
using fx::FieldSize;

/// Under ThreadSanitizer S1's every pixel is left to the other builds (the threads are what it checks).
#if defined(__has_feature)
#if __has_feature(thread_sanitizer)
constexpr bool thread_sanitizer = true;
#else
constexpr bool thread_sanitizer = false;
#endif
#else
constexpr bool thread_sanitizer = false;
#endif

/// A probe as mat.slang's FxProbe: an upright pixel and a colour's levels.
struct Probe {
    std::int32_t x;
    std::int32_t y;
    std::uint32_t colour;
    std::uint32_t unused;
};

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

/// The device's answers of `entry` (fields_check or mat_check) for `probes`, with the fields of `images` in set 1 as
/// frame `frame` (every earlier one finished).
std::vector<std::array<std::uint32_t, 4>> ask(const gpu::Device& device, const fx::FieldImages& images,
                                              frame::FrameLayout& layout, std::uint64_t frame, const char* entry,
                                              const std::vector<Probe>& probes) {
    std::vector<std::array<std::uint32_t, 4>> out;
    const vk::raii::Device& d = device.handle();
    layout.set_image(frame::Global::noise_rgba, images.view(0), images.sampler());
    layout.set_image(frame::Global::noise_rg, images.view(1), images.sampler());
    layout.set_image(frame::Global::noise_r, images.view(2), images.sampler());
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
    auto pipeline =
        gpu::make_compute(d, no_cache, {.name = entry, .module = "fx_mat"}, **module, entry, **pipeline_layout);
    ATLAS_REQUIRE_OR_RETURN(out, pipeline.has_value(), pipeline.error().text());

    const vk::DeviceSize in_bytes = probes.size() * sizeof(Probe);
    const vk::DeviceSize out_bytes = probes.size() * 16;
    auto in = device.memory().buffer(in_bytes, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
    auto results = device.memory().buffer(out_bytes, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::readback);
    ATLAS_REQUIRE_OR_RETURN(out, in.has_value() && results.has_value());
    std::memcpy(in->bytes().data(), probes.data(), in_bytes);
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
        layout.record_setup(cmd, gpu::Sync(device.enabled().synchronization2));
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, **pipeline);
        const auto sets = layout.sets();
        const auto offsets = layout.offsets(0);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, **pipeline_layout, 0, sets, offsets);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, **pipeline_layout, 2, **set, {});
        cmd.dispatch(static_cast<std::uint32_t>((probes.size() + 63) / 64), 1, 1);
        const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eShaderWrite,
                                        .dstAccessMask = vk::AccessFlagBits::eHostRead};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eComputeShader, vk::PipelineStageFlagBits::eHost, {}, to_host,
                            {}, {});
    });
    results->invalidate();
    out.resize(probes.size());
    std::memcpy(out.data(), results->bytes().data(), out_bytes);
    return out;
}

/// The twelve bytes the fields hold at pixel `at` (rows from the top), as fields_check packs them.
[[nodiscard]] std::array<std::uint32_t, 4> packed_at(const fx::ScreenFields& f, std::size_t at) {
    const auto word = [](std::span<const std::uint8_t> b) {
        std::uint32_t w = 0;
        for (std::size_t i = 0; i < b.size(); ++i) w |= static_cast<std::uint32_t>(b[i]) << (8U * i);
        return w;
    };
    return {word(f.rgba().subspan(at * fx::rgba_bytes, fx::rgba_bytes)),
            word(f.rg().subspan(at * fx::rg_bytes, fx::rg_bytes)), word(f.r().subspan(at * fx::r_bytes, fx::r_bytes)),
            0U};
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

TEST_CASE("the fields and the byte tables reach the shaders byte for byte, and mat's levels are the CPU's") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto images = fx::FieldImages::create(device);
    ATLAS_REQUIRE(images.has_value(), images.error().text());
    auto layout = frame::FrameLayout::create(device);
    ATLAS_REQUIRE(layout.has_value(), layout.error().text());
    const gpu::Sync sync(device.enabled().synchronization2);

    std::vector<FieldSize> sizes{fx::size_of(97, 61, 1.7), fx::size_of(61, 97, 2.2)};
    if (!thread_sanitizer) sizes.push_back(fx::size_of(1080, 2400, 1080.0 / 412.0)); // S1, every pixel
    std::uint64_t frame = 0;
    for (const FieldSize size : sizes) {
        CAPTURE(size.width);
        CAPTURE(size.height);
        ++frame;
        const auto fields = fx::make_fields(size, {.threads = 2});
        const bool resized = (*images)->ready();
        auto staged = (*images)->stage(*fields);
        ATLAS_REQUIRE(staged.has_value(), staged.error().text());
        CHECK((*images)->staged());
        run_once(device, [&](const vk::raii::CommandBuffer& cmd) { (*images)->record_upload(cmd, sync, frame); });
        CHECK_FALSE((*images)->staged());
        CHECK((*images)->ready());
        CHECK((*images)->size() == size);
        // The staging buffer until this frame has finished, and the last size's images until the one before.
        CHECK((*images)->retired() == (resized ? 2U : 1U));

        // Every pixel's twelve bytes.
        std::vector<Probe> every;
        every.reserve(static_cast<std::size_t>(size.width) * size.height);
        for (std::uint32_t y = 0; y < size.height; ++y) {
            for (std::uint32_t x = 0; x < size.width; ++x) {
                every.push_back({.x = static_cast<std::int32_t>(x), .y = static_cast<std::int32_t>(y)});
            }
        }
        const auto got = ask(device, **images, **layout, frame, "fields_check", every);
        ATLAS_REQUIRE(got.size() == every.size());
        std::size_t differ = 0;
        for (std::size_t i = 0; i < every.size(); ++i) {
            if (got[i] != packed_at(*fields, i)) ++differ;
        }
        CHECK_MESSAGE(differ == 0, differ << " of " << every.size() << " pixels differ");

        // `mat` at a spread of pixels and colours: the grey there and the opaque table's levels.
        test::SplitMix64 rnd(size.width);
        std::vector<Probe> spread;
        spread.reserve(4096);
        for (std::uint32_t k = 0; k < 4096; ++k) {
            spread.push_back({.x = static_cast<std::int32_t>(rnd.below(size.width)),
                              .y = static_cast<std::int32_t>(rnd.below(size.height)),
                              .colour = rnd.below(1U << 24U)});
        }
        ++frame;
        const auto mat = ask(device, **images, **layout, frame, "mat_check", spread);
        ATLAS_REQUIRE(mat.size() == spread.size());
        const auto table = fx::mat_table();
        std::size_t mat_differ = 0;
        for (std::size_t i = 0; i < spread.size(); ++i) {
            const std::uint32_t g = fields->at(fx::Field::mat, static_cast<std::uint32_t>(spread[i].x),
                                               static_cast<std::uint32_t>(spread[i].y))[0];
            std::uint32_t expected = g;
            for (std::uint32_t c = 0; c < 3; ++c) {
                const std::uint32_t level = (spread[i].colour >> (8U * c)) & 0xFFU;
                expected |= static_cast<std::uint32_t>(table[(level * 256) + g]) << (8U * (c + 1));
            }
            if (mat[i][0] != expected) ++mat_differ;
        }
        CHECK_MESSAGE(mat_differ == 0, mat_differ << " of " << spread.size() << " probes differ");
        (*images)->collect(frame);
        CHECK((*images)->retired() == 0);
    }
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}

TEST_CASE("the byte tables the GPU reads are the CPU's, in their order") {
    const std::vector<std::uint8_t> t = fx::gpu_tables();
    REQUIRE(t.size() == fx::tables_bytes);
    CHECK(std::ranges::equal(std::span(t).subspan(fx::table_to_linear, 65536), fx::bytes::to_linear()));
    CHECK(std::ranges::equal(std::span(t).subspan(fx::table_to_srgb, 65536), fx::bytes::to_srgb()));
    CHECK(std::ranges::equal(std::span(t).subspan(fx::table_mat, 65536), fx::mat_table()));
}

#if !defined(__ANDROID__)
TEST_CASE("the fields' provider binds them for the frame's size once made, in full mode alone, and a resize's once "
          "they are made, the old kept until their frames finish") {
    namespace fs = std::filesystem;
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const fs::path dir = fs::path(ATLAS_TEST_SCRATCH) / "fields-provider";
    std::error_code ec;
    fs::remove_all(dir, ec);
    auto made = fx::FieldCache::create(device, {.dir = dir, .threads = 2});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    fx::FieldCache& fields = **made;
    std::atomic<int> wakes{0};
    fields.set_wake([&wakes] { wakes.fetch_add(1); });
    FieldsScene scene(fields);
    auto builder = frame::FrameBuilder::create(device, *cache, frame::make_passes());
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    const vk::Extent2D extent{.width = 96, .height = 72};
    auto output = gpu::Offscreen::create(device, extent, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    const style::CameraState wide = standin::board_view(96, 72, 2.0);
    const style::CameraState tall = standin::board_view(72, 96, 2.0);
    const FieldSize wide_size = fx::size_of(96, 72, 2.0);
    const FieldSize tall_size = fx::size_of(72, 96, 2.0);
    (*builder)->set_inputs({.camera = wide, .mode = frame::Mode::full, .scene = &scene});
    auto renderer = gpu::Renderer::create(device, **output, **builder, device.facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    auto draw = [&] {
        auto report = (*renderer)->draw(0);
        ATLAS_REQUIRE(report.has_value(), report.error().text());
    };
    auto fields_on = [&] { return ((*builder)->state().block.info.x & shaders::FRAME_FIELDS) != 0U; };
    const frame::FrameLayout& layout = (*builder)->layout();

    // The first frame asks for its size's fields: the tables are bound, the fields not yet. The worker is held while
    // the frame is drawn: made in a millisecond, the fields would otherwise be published within the frame's own update
    // whenever the frame's thread is paused there (a busy machine), and taken at once.
    fields.worker().hold(true);
    draw();
    CHECK(layout.bound_buffer(frame::Global::byte_tables) == fields.images().tables());
    CHECK(fields.wanted() == wide_size);
    CHECK_FALSE(fields_on());
    fields.worker().hold(false);
    fields.worker().wait_idle();
    CHECK(wakes.load() == 1);
    CHECK(fs::exists(fx::FieldDisk(dir).path(wide_size)));
    // The next takes them: images of their size, bound, uploaded before the passes.
    draw();
    CHECK(fields_on());
    CHECK(fields.bound_for(wide_size));
    CHECK(fields.images().size() == wide_size);
    // Structural mode stubs the effects; full mode runs them again, set 1 untouched frame after frame.
    (*builder)->set_inputs({.camera = wide, .mode = frame::Mode::structural, .scene = &scene});
    draw();
    CHECK_FALSE(fields_on());
    (*builder)->set_inputs({.camera = wide, .mode = frame::Mode::full, .scene = &scene});
    const std::uint64_t versions = layout.global_versions();
    for (int n = 0; n < 4; ++n) draw();
    CHECK(fields_on());
    CHECK(layout.global_versions() == versions);
    CHECK(fields.images().retired() == 0);

    // A resize: off until the new size's fields are made, then theirs, the old images kept until their frames finish.
    (*builder)->set_inputs({.camera = tall, .mode = frame::Mode::full, .scene = &scene});
    fields.worker().hold(true);
    draw();
    CHECK(fields.wanted() == tall_size);
    CHECK_FALSE(fields_on());
    fields.worker().hold(false);
    fields.worker().wait_idle();
    draw();
    CHECK(fields_on());
    CHECK(fields.images().size() == tall_size);
    CHECK(fields.images().retired() == 2); // the wide images and the upload's staging buffer
    for (int n = 0; n < 3; ++n) draw();
    CHECK(fields.images().retired() == 0);
    // Back to the first size: mapped from disk this time.
    (*builder)->set_inputs({.camera = wide, .mode = frame::Mode::full, .scene = &scene});
    draw();
    fields.worker().wait_idle();
    CHECK(fields.worker().last_from_disk());
    draw();
    CHECK(fields_on());
    CHECK(fields.images().size() == wide_size);
    CHECK(wakes.load() == 3);
    (*renderer)->drain();
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
    MESSAGE("set 1 made " << layout.global_versions() << " times; the fields hold " << fields.images().bytes()
                          << " bytes of GPU memory");
    renderer->reset();
}
#endif
