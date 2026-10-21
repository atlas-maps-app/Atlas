// The frame builder and its composite on the host's Vulkan (plan-vector K7's acceptance: "the composite with every
// effect stubbed passes colours through bit for bit"): in every configuration, frame route and composite target the
// device has, at 8 bits a channel and at 10, with the validation layer and its synchronization validation silent. A
// probe pass writes known colours into known samples (shaders/frame/test_samples.slang: a hash of each pixel and layer
// picks each layer's colour and the samples it covers), so the twin (passes/Composite.hpp's composite_pixel, the
// shader's own text as C++) predicts every pixel the composite writes, and the frame read back must equal it in every
// channel of every pixel. Both routes are first-class (64 % of phones draw through the render pass, the device survey
// of 18 October): each has its own cases, the render pass's with both composite targets.
#if !defined(__ANDROID__)

#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "gen/spirv/frame/test_samples.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Composite.hpp"
#include "platform/host/Offscreen.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>
#include <format>
#include <memory>
#include <string>
#include <tuple>
#include <vector>

using namespace atlas;

namespace {

/// test_samples.slang's hash.
[[nodiscard]] std::uint32_t test_hash(std::uint32_t x) noexcept {
    x = x ^ (x >> 16U);
    x = x * 0x7feb352dU;
    x = x ^ (x >> 15U);
    x = x * 0x846ca68bU;
    x = x ^ (x >> 16U);
    return x;
}

/// The probe pass: `layers` full-screen triangles, each writing its colour into the samples its coverage picks.
class SamplesPass final : public frame::Pass {
public:
    explicit SamplesPass(std::uint32_t layers) noexcept : layers_(layers) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "samples"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }

    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        release();
        ATLAS_GPU_LET(module, frame::make_module(c, "frame_test_samples", spirv::frame_test_samples));
        module_ = std::move(module);
        gpu::GraphicsDesc desc{
            .label = {.name = "samples", .module = "frame_test_samples", .configuration = c.configuration},
            .module = *module_,
            .vertex = "samples_vs",
            .fragment = "samples_fs",
            .layout = c.layout.pipeline_layout(),
            .target = c.pass.scene_target()};
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(c, desc, frame::Overdraw::opaque));
        pipeline_ = std::move(pipeline);
        samples_ = static_cast<std::uint32_t>(c.pass.spec().config.samples());
        levels_ = gpu::ten_bit(c.pass.spec().colour_format) ? 1023U : 255U;
        return {};
    }
    void release() noexcept override {
        pipeline_.clear();
        module_.clear();
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)phase;
        const vk::raii::CommandBuffer& cmd = r.frame.cmd;
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
        for (std::uint32_t layer = 0; layer < layers_; ++layer) {
            layouts::frame_test_samples::SamplesPush p{};
            p.layer = {layer, levels_, samples_, 0U};
            cmd.pushConstants(
                r.layout.pipeline_layout(), frame::push_stages, 0,
                vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
            cmd.draw(3, 1, 0, 0);
            r.counters.draw("probe", 1);
        }
    }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{frame::Phase{.slot = frame::Slot::objects}};
    std::uint32_t layers_;
    std::uint32_t samples_ = 1;
    std::uint32_t levels_ = 255;
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

/// The composite configurations the device has: per configuration, each route, and the composite's targets.
std::vector<gpu::Configuration> composite_configurations(const gpu::Device& device) {
    std::vector<gpu::Configuration> out;
    const bool dynamic = device.enabled().dynamic_rendering && device.enabled().local_read;
    for (const gpu::FrameConfig c :
         {gpu::FrameConfig::a, gpu::FrameConfig::b, gpu::FrameConfig::c, gpu::FrameConfig::x1}) {
        if (!device.facts().supports(c)) continue;
        const bool one = gpu::Configuration{.config = c}.samples() == vk::SampleCountFlagBits::e1;
        for (const gpu::Route route : {gpu::Route::renderpass, gpu::Route::dynamic}) {
            if (route == gpu::Route::dynamic && !dynamic) continue;
            if (one) {
                out.push_back({.config = c,
                               .route = route,
                               .form = gpu::FrameForm::composite,
                               .target = gpu::CompositeTarget::direct});
                continue;
            }
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::composite,
                           .target = gpu::CompositeTarget::resolve});
            if (route == gpu::Route::renderpass) {
                out.push_back({.config = c,
                               .route = route,
                               .form = gpu::FrameForm::composite,
                               .target = gpu::CompositeTarget::direct});
            }
        }
    }
    return out;
}

/// A pixel's channel `c` in the output's levels, as the image holds it.
[[nodiscard]] std::uint32_t level_at(const platform::Packed& p, std::size_t i, std::size_t c) noexcept {
    const std::uint32_t w = p.words[i];
    switch (p.format) {
    case vk::Format::eA2B10G10R10UnormPack32: return (w >> (10 * c)) & 0x3FFU;
    case vk::Format::eA2R10G10B10UnormPack32: return (w >> (10 * (2 - c))) & 0x3FFU;
    case vk::Format::eB8G8R8A8Unorm: return (w >> (8 * (2 - c))) & 0xFFU;
    default: return (w >> (8 * c)) & 0xFFU;
    }
}

/// Whether pixel (x, y) of a w × h frame lies on the diagonal from its top-left corner to its bottom-right one: where
/// lavapipe, clipping the composite's full-screen triangle to the frame, joins the two triangles it draws it as.
[[nodiscard]] bool on_seam(std::uint32_t x, std::uint32_t y, std::uint32_t w, std::uint32_t h) noexcept {
    // The seam y = x·h / w meets the pixel's square [x, x + 1) × [y, y + 1).
    const std::uint64_t wide = w;
    const std::uint64_t high = h;
    const std::uint64_t above = std::uint64_t{y} * wide; // the pixel's top edge, times w
    const std::uint64_t below = above + wide;            // its bottom edge
    const std::uint64_t left = std::uint64_t{x} * high;  // the seam's height at its left edge, times w
    const std::uint64_t right = left + high;             // and at its right edge
    return above < right && below > left;
}

/// The probe's frame against the twin's: per pixel, each sample's last colour, then composite_pixel. Counts the
/// channels that differ, those on the seam apart (`on_seam_wrong`).
[[nodiscard]] std::uint32_t mismatches(const platform::Packed& out, std::uint32_t layers, std::uint32_t samples,
                                       std::uint32_t levels, const shaders::FrameLayers& stubbed, std::string& first,
                                       std::uint32_t& on_seam_wrong) {
    std::uint32_t wrong = 0;
    on_seam_wrong = 0;
    const auto n = static_cast<float>(levels);
    const std::uint32_t full = (1U << samples) - 1U;
    for (std::uint32_t y = 0; y < out.height; ++y) {
        for (std::uint32_t x = 0; x < out.width; ++x) {
            std::array<hlsl::float3, 4> colour{};
            for (std::uint32_t layer = 0; layer < layers; ++layer) {
                const std::uint32_t h = test_hash((x * 73856093U) ^ (y * 19349663U) ^ ((layer + 1U) * 83492791U));
                const hlsl::float3 c = {static_cast<float>(h % (levels + 1U)) / n,
                                        static_cast<float>((h / 7U) % (levels + 1U)) / n,
                                        static_cast<float>((h / 49U) % (levels + 1U)) / n};
                const std::uint32_t mask = layer == 0 ? full : (test_hash(h) & full);
                for (std::uint32_t s = 0; s < samples; ++s) {
                    if ((mask >> s) & 1U) colour[s] = c;
                }
            }
            const hlsl::float3 want =
                passes::composite_pixel(stubbed, std::span(colour.data(), samples),
                                        {static_cast<float>(x) + 0.5F, static_cast<float>(y) + 0.5F},
                                        {static_cast<float>(out.width), static_cast<float>(out.height)});
            const std::size_t i = static_cast<std::size_t>(y) * out.width + x;
            for (std::size_t c = 0; c < 3; ++c) {
                const std::uint32_t got = level_at(out, i, c);
                const auto expected = static_cast<std::uint32_t>(want[static_cast<int>(c)]);
                if (got == expected) continue;
                if (on_seam(x, y, out.width, out.height)) {
                    ++on_seam_wrong;
                    continue;
                }
                if (wrong == 0)
                    first = std::format("pixel ({}, {}) channel {}: {} where the twin has {}", x, y, c, got, expected);
                ++wrong;
            }
        }
    }
    return wrong;
}

std::string describe(const gpu::Configuration& c) { return gpu::name_of(c); }

} // namespace

TEST_CASE("the frame builder draws every configuration, route and composite target, and the composite with every "
          "effect stubbed passes the samples' colours through bit for bit") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D extent{.width = 96, .height = 72};
    constexpr std::uint32_t layers = 5;
    const auto configs = composite_configurations(device);
    CHECK(!configs.empty());
    std::size_t drawn = 0;
    std::size_t renderpass = 0;
    std::size_t dynamic = 0;
    for (const gpu::Configuration& config : configs) {
        for (const gpu::Depth depth : {gpu::Depth::eight, gpu::Depth::deepest}) {
            auto output = platform::Offscreen::create(device, extent, 2, depth);
            ATLAS_REQUIRE(output.has_value(), output.error().text());
            if (depth == gpu::Depth::deepest && !gpu::ten_bit((*output)->format())) continue;
            ATLAS_REQUIRE((*output)->fill({1.0F, 0.0F, 1.0F, 1.0F}).has_value());
            std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
            passes.push_back(std::make_unique<SamplesPass>(layers));
            auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes),
                                                       {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = 0});
            ATLAS_REQUIRE(builder.has_value(), builder.error().text());
            (*builder)->set_inputs({.camera = standin::board_view(extent.width, extent.height, 1.0),
                                    .theme = {},
                                    .seconds = 0.5,
                                    .mode = frame::Mode::structural,
                                    .scene = nullptr});
            auto renderer = gpu::Renderer::create(device, **output, **builder, config);
            ATLAS_REQUIRE(renderer.has_value(), describe(config) << ": " << renderer.error().text());
            for (int f = 0; f < 3; ++f) {
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), describe(config) << ": " << report.error().text());
            }
            (*renderer)->drain();
            auto packed = (*output)->read_packed((*output)->latest());
            ATLAS_REQUIRE(packed.has_value(), packed.error().text());
            const std::uint32_t levels = gpu::ten_bit(packed->format) ? 1023U : 255U;
            const auto samples = static_cast<std::uint32_t>(config.samples());
            std::string first;
            std::uint32_t seam = 0;
            const std::uint32_t wrong =
                mismatches(*packed, layers, samples, levels, (*builder)->state().block.layers, first, seam);
            const std::string name = std::format("{} in {}", describe(config), gpu::colour_name(packed->format));
            CHECK_MESSAGE(wrong == 0,
                          name << ": " << wrong << " channels differ from the twin's; the first: " << first);
            // Where the composite writes the 1x output itself, every pixel is the twin's. Where it writes the samples
            // in place, resolved, a pixel shaded twice reads what its first fragment wrote: lavapipe clips the
            // full-screen triangle to the frame and draws the quad as two triangles, whose seam pixels get a fragment
            // each (a GPU's guard band draws the triangle whole, once per pixel). Only seam pixels with unlike samples
            // may differ.
            if (config.target == gpu::CompositeTarget::direct) {
                CHECK_MESSAGE(seam == 0, name << ": " << seam << " channels on the diagonal differ");
            } else if (seam > 0) {
                MESSAGE(name << ": " << seam << " channels of pixels on the clipped triangle's seam differ (shaded "
                             << "twice in place on lavapipe); every other pixel is the twin's");
            }
            CHECK_MESSAGE((*builder)->state().block.layers.steps.x == 0U, name << ": a step ran");
            // Nothing is written per frame: one version of each set for the frames drawn.
            CHECK((*builder)->layout().frame_versions() == 1);
            CHECK((*builder)->layout().global_versions() == 1);
            CHECK((*builder)->total().draws == layers + 1); // the probe's layers and the composite
            // A dynamic route the device's own properties forbid is forced for coverage (tests/gpu/gpu_fixture.hpp):
            // what the validation layer says of its local reads is reported, not failed on; anything else fails.
            const bool forced = config.route == gpu::Route::dynamic && device.enabled().local_read_forced;
            for (const std::string& m : session->take_messages()) {
                const bool about_local_reads = m.find("LocalRead") != std::string::npos ||
                                               m.find("local read") != std::string::npos ||
                                               m.find("dynamicRenderingLocalRead") != std::string::npos;
                if (forced && about_local_reads) {
                    MESSAGE(name << " (forced): the validation layer: " << m);
                } else {
                    CHECK_MESSAGE(false, name << ": " << m);
                }
            }
            MESSAGE(name << ": " << extent.width * extent.height << " pixels as the twin computes them, "
                         << (*builder)->pipeline_count() << " pipelines in " << (*builder)->pipelines_ms() << " ms");
            ++drawn;
            (config.route == gpu::Route::renderpass ? renderpass : dynamic) += 1;
            renderer->reset();
        }
    }
    if (device.enabled().local_read_forced) {
        const gpu::FeatureFacts& x = device.facts().features;
        MESSAGE("the dynamic route here is one this device's Vulkan 1.4 properties forbid, forced for coverage: "
                "dynamicRenderingLocalReadMultisampledAttachments "
                << std::string(x.local_read_multisampled ? "true" : "false")
                << ", dynamicRenderingLocalReadDepthStencilAttachments "
                << std::string(x.local_read_depth_stencil ? "true" : "false") << "; the facts pick "
                << std::string(gpu::name_of(device.facts().choice.route)));
        CHECK(device.facts().choice.route == gpu::Route::renderpass);
    }
    CHECK(drawn >= 2);
    CHECK(renderpass >= 2); // the render pass's own cases: both composite targets where the device has 4x
    if (device.enabled().local_read) CHECK(dynamic >= 1);
    MESSAGE(drawn << " frames: " << renderpass << " through the render pass, " << dynamic << " by dynamic rendering");
}

TEST_CASE("the frame builder refuses the one-subpass form, which A0 did not build") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto output = platform::Offscreen::create(device, {.width = 32, .height = 32}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto builder = frame::FrameBuilder::create(device, *cache, frame::make_passes());
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    const gpu::Configuration onepass{.config = gpu::FrameConfig::c,
                                     .route = gpu::Route::renderpass,
                                     .form = gpu::FrameForm::onepass,
                                     .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, onepass);
    REQUIRE_FALSE(renderer.has_value());
    CHECK(renderer.error().text().find("ONEPASS") != std::string::npos);
    CHECK_FALSE((*builder)->holds_prepared());
    (void)session->take_messages();
}

TEST_CASE("the composite's haze and vignette are the twin's, bit for bit off lavapipe's seam, at every density and "
          "camera, in every configuration, route and target") {
    // K17a (the coordinator's: A2's exact haze): the haze and fin's vignette in whole numbers (shared/composite.slang),
    // checked on, not only stubbed, over the probe's known samples, at a moment with weather and a haze, at densities
    // 1 and 2.625 and three pitches (the horizon, and so the haze's stops, at three places); the seam counted apart.
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D extent{.width = 96, .height = 72};
    constexpr std::uint32_t layers = 4;
    std::size_t drawn = 0;
    std::uint32_t seam_total = 0;
    for (const gpu::Configuration& config : composite_configurations(device)) {
        for (const gpu::Depth depth : {gpu::Depth::eight, gpu::Depth::deepest}) {
            // and once with the screen's haze and vignette off (the app's Look settings: MapFrame::set_screen)
            for (const auto& [density, pitch, screen] : {std::tuple{1.0, 46.0, true}, std::tuple{2.625, 70.0, true},
                                                         std::tuple{1.5, 30.0, true}, std::tuple{2.625, 70.0, false}}) {
                if (!screen && depth != gpu::Depth::eight) continue;
                auto output = platform::Offscreen::create(device, extent, 2, depth);
                ATLAS_REQUIRE(output.has_value(), output.error().text());
                if (depth == gpu::Depth::deepest && !gpu::ten_bit((*output)->format())) continue;
                std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
                passes.push_back(std::make_unique<SamplesPass>(layers));
                auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes),
                                                           {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = ~0U});
                if (!screen) (*builder)->set_steps(~(shaders::COMPOSITE_HAZE | shaders::COMPOSITE_VIGNETTE));
                ATLAS_REQUIRE(builder.has_value(), builder.error().text());
                style::ThemeBlock theme{};
                theme.flags = style::theme_flag::weather;
                theme.haze = {.r = 0xC8, .g = 0xCD, .b = 0xD7, .a = 0xFF};
                theme.haze_max = 0.55;
                (*builder)->set_inputs({.camera = standin::board_view(extent.width, extent.height, density, pitch),
                                        .theme = theme,
                                        .seconds = 0.5,
                                        .mode = frame::Mode::structural,
                                        .scene = nullptr});
                auto renderer = gpu::Renderer::create(device, **output, **builder, config);
                ATLAS_REQUIRE(renderer.has_value(), describe(config) << ": " << renderer.error().text());
                for (int f = 0; f < 3; ++f) {
                    auto report = (*renderer)->draw(0);
                    ATLAS_REQUIRE(report.has_value(), describe(config) << ": " << report.error().text());
                }
                (*renderer)->drain();
                auto packed = (*output)->read_packed((*output)->latest());
                ATLAS_REQUIRE(packed.has_value(), packed.error().text());
                const shaders::FrameBlock& block = (*builder)->state().block;
                const std::uint32_t steps = block.layers.steps.x;
                CHECK(((steps & shaders::COMPOSITE_HAZE) != 0U) == screen);
                CHECK(((steps & shaders::COMPOSITE_VIGNETTE) != 0U) == screen);
                const std::uint32_t levels = gpu::ten_bit(packed->format) ? 1023U : 255U;
                const auto samples = static_cast<std::uint32_t>(config.samples());
                const auto n = static_cast<float>(levels);
                const std::uint32_t full = (1U << samples) - 1U;
                const hlsl::float3 frame_box = {block.camera.frame.x, block.camera.frame.y, block.camera.frame.z};
                shaders::FrameLayers stubbed = block.layers;
                stubbed.steps.x = 0;
                std::uint32_t wrong = 0;
                std::uint32_t seam = 0;
                std::uint32_t changed = 0;
                std::string first;
                for (std::uint32_t y = 0; y < packed->height; ++y) {
                    for (std::uint32_t x = 0; x < packed->width; ++x) {
                        std::array<hlsl::float3, 4> colour{};
                        for (std::uint32_t layer = 0; layer < layers; ++layer) {
                            const std::uint32_t h =
                                test_hash((x * 73856093U) ^ (y * 19349663U) ^ ((layer + 1U) * 83492791U));
                            const hlsl::float3 c = {static_cast<float>(h % (levels + 1U)) / n,
                                                    static_cast<float>((h / 7U) % (levels + 1U)) / n,
                                                    static_cast<float>((h / 49U) % (levels + 1U)) / n};
                            const std::uint32_t mask = layer == 0 ? full : (test_hash(h) & full);
                            for (std::uint32_t s = 0; s < samples; ++s) {
                                if ((mask >> s) & 1U) colour[s] = c;
                            }
                        }
                        const hlsl::float3 want = passes::composite_pixel_fx(
                            block.layers, std::span(colour.data(), samples), {}, hlsl::uint2{x, y}, frame_box);
                        const hlsl::float3 bare = passes::composite_pixel_fx(stubbed, std::span(colour.data(), samples),
                                                                             {}, hlsl::uint2{x, y}, frame_box);
                        if (want.x != bare.x || want.y != bare.y || want.z != bare.z) ++changed;
                        const std::size_t i = (static_cast<std::size_t>(y) * packed->width) + x;
                        for (std::size_t c = 0; c < 3; ++c) {
                            const std::uint32_t got = level_at(*packed, i, c);
                            const auto expected = static_cast<std::uint32_t>(want[static_cast<int>(c)]);
                            if (got == expected) continue;
                            if (on_seam(x, y, packed->width, packed->height)) {
                                ++seam;
                                continue;
                            }
                            if (wrong == 0) {
                                first = std::format("pixel ({}, {}) channel {}: {} where the twin has {}", x, y, c, got,
                                                    expected);
                            }
                            ++wrong;
                        }
                    }
                }
                const std::string name = std::format("{} in {} at density {} and pitch {}", describe(config),
                                                     gpu::colour_name(packed->format), density, pitch);
                CHECK_MESSAGE(wrong == 0,
                              name << ": " << wrong << " channels differ from the twin's; the first: " << first);
                // The steps do something: the haze and the vignette change most pixels; off, none.
                if (screen) {
                    CHECK_MESSAGE(changed > packed->width * packed->height / 4,
                                  name << ": the steps changed " << changed);
                } else {
                    CHECK_MESSAGE(changed == 0,
                                  name << ": with the haze and the vignette off, the steps changed " << changed);
                }
                if (config.target == gpu::CompositeTarget::direct) {
                    CHECK_MESSAGE(seam == 0, name << ": " << seam << " channels on the diagonal differ");
                } else if (seam > 0) {
                    MESSAGE(name << ": " << seam << " channels of pixels on the clipped triangle's seam differ (shaded "
                                 << "twice in place on lavapipe: the haze and the vignette applied twice there); every "
                                 << "other pixel is the twin's");
                    seam_total += seam;
                }
                const bool forced = config.route == gpu::Route::dynamic && device.enabled().local_read_forced;
                for (const std::string& m : session->take_messages()) {
                    const bool about_local_reads = m.find("LocalRead") != std::string::npos ||
                                                   m.find("local read") != std::string::npos ||
                                                   m.find("dynamicRenderingLocalRead") != std::string::npos;
                    if (forced && about_local_reads) {
                        MESSAGE(name << " (forced): the validation layer: " << m);
                    } else {
                        CHECK_MESSAGE(false, name << ": " << m);
                    }
                }
                ++drawn;
                renderer->reset();
            }
        }
    }
    CHECK(drawn >= 6);
    MESSAGE(drawn << " frames with the haze and the vignette on; " << seam_total
                  << " channels on lavapipe's seam differ");
}

#endif
