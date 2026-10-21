// The composite's `mat` rule and grain (K17a; shaders/shared/composite.slang, shaders/frame/composite.slang) on the
// host's Vulkan (lavapipe, the validation layer on): a probe pass writes known colours into known samples, every other
// layer of them the objects group's (writing the stencil's objects bit on the samples it covers, the others clearing
// it), in full mode at a moment with weather with the fields bound, so the twin (passes/Composite.hpp's
// composite_pixel_fx, the shader's own text as C++, with each sample's objects bit and the grain field's bytes)
// predicts every pixel: the rule where the objects group covers some of a pixel's samples, the grain everywhere. In
// every configuration, route and composite target the device has, at 8 bits a channel and at 10, by day and at night,
// with K17a's steps alone and with every step the composite draws (the haze and the vignette, integer arithmetic too).
// Every pixel off lavapipe's seam is the twin's, bit for bit; the seam's pixels, shaded twice in place where the
// composite writes the samples (the steps then applied twice), are named and counted, never absorbed.
#if !defined(__ANDROID__)

#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/FieldCache.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/frame/test_samples.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Composite.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>
#include <format>
#include <memory>
#include <string>
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

/// Whether a probe layer is the objects group's: every odd one (layer 0, covering every sample, is the ground's).
[[nodiscard]] constexpr bool objects_layer(std::uint32_t layer) noexcept { return (layer & 1U) != 0U; }

/// The probe pass: `layers` full-screen triangles, each writing its colour into the samples its coverage picks, and
/// the objects bit (set by the objects group's layers, cleared by the others) into their stencil.
class ObjectSamplesPass final : public frame::Pass {
public:
    explicit ObjectSamplesPass(std::uint32_t layers) noexcept : layers_(layers) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "object samples"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }

    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        release();
        ATLAS_GPU_LET(module, frame::make_module(c, "frame_test_samples", spirv::frame_test_samples));
        module_ = std::move(module);
        for (std::size_t i = 0; i < pipelines_.size(); ++i) {
            const bool objects = i == 1;
            gpu::GraphicsDesc desc{
                .label = {.name = objects ? "object samples" : "other samples", .configuration = c.configuration},
                .module = *module_,
                .vertex = "samples_vs",
                .fragment = "samples_fs",
                .layout = c.layout.pipeline_layout(),
                .target = c.pass.scene_target()};
            desc.stencil_test = true;
            desc.stencil = {.failOp = vk::StencilOp::eKeep,
                            .passOp = vk::StencilOp::eReplace,
                            .depthFailOp = vk::StencilOp::eKeep,
                            .compareOp = vk::CompareOp::eAlways,
                            .compareMask = 0,
                            .writeMask = shaders::OBJECTS_BIT,
                            .reference = objects ? shaders::OBJECTS_BIT : 0U};
            if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
            ATLAS_GPU_LET(pipeline, frame::make_pipeline(c, desc, frame::Overdraw::opaque));
            pipelines_[i] = std::move(pipeline);
        }
        samples_ = static_cast<std::uint32_t>(c.pass.spec().config.samples());
        levels_ = gpu::ten_bit(c.pass.spec().colour_format) ? 1023U : 255U;
        return {};
    }
    void release() noexcept override {
        for (vk::raii::Pipeline& p : pipelines_) p.clear();
        module_.clear();
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)phase;
        const vk::raii::CommandBuffer& cmd = r.frame.cmd;
        for (std::uint32_t layer = 0; layer < layers_; ++layer) {
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_[objects_layer(layer) ? 1 : 0]);
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
    std::array<vk::raii::Pipeline, 2> pipelines_{vk::raii::Pipeline{nullptr}, vk::raii::Pipeline{nullptr}};
};

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
[[nodiscard]] std::uint32_t level_at(const gpu::Packed& p, std::size_t i, std::size_t c) noexcept {
    const std::uint32_t w = p.words[i];
    switch (p.format) {
    case vk::Format::eA2B10G10R10UnormPack32: return (w >> (10 * c)) & 0x3FFU;
    case vk::Format::eA2R10G10B10UnormPack32: return (w >> (10 * (2 - c))) & 0x3FFU;
    case vk::Format::eB8G8R8A8Unorm: return (w >> (8 * (2 - c))) & 0xFFU;
    default: return (w >> (8 * c)) & 0xFFU;
    }
}

/// Whether pixel (x, y) of a w × h frame lies on the diagonal where lavapipe joins the two triangles it clips the
/// composite's full-screen triangle into.
[[nodiscard]] bool on_seam(std::uint32_t x, std::uint32_t y, std::uint32_t w, std::uint32_t h) noexcept {
    const std::uint64_t above = std::uint64_t{y} * w;
    const std::uint64_t below = above + w;
    const std::uint64_t left = std::uint64_t{x} * h;
    const std::uint64_t right = left + h;
    return above < right && below > left;
}

/// What the frame held against the twin.
struct Outcome {
    std::uint32_t wrong = 0;       ///< channels off the seam that differ
    std::uint32_t seam = 0;        ///< channels on the seam that differ
    std::uint32_t silhouettes = 0; ///< pixels where the rule ran
    std::string first;
};

/// The frame against the twin.
[[nodiscard]] Outcome compare(const gpu::Packed& out, std::uint32_t layers, std::uint32_t samples,
                              const shaders::FrameLayers& frame_layers, const fx::ScreenFields& fields,
                              std::uint32_t opacity) {
    Outcome o;
    const std::uint32_t levels = frame_layers.steps.y;
    const auto n = static_cast<float>(levels);
    const std::uint32_t full = (1U << samples) - 1U;
    const hlsl::float3 box = {static_cast<float>(out.width), static_cast<float>(out.height), 1.0F};
    for (std::uint32_t y = 0; y < out.height; ++y) {
        for (std::uint32_t x = 0; x < out.width; ++x) {
            std::array<hlsl::float3, 4> colour{};
            std::uint32_t objects = 0;
            for (std::uint32_t layer = 0; layer < layers; ++layer) {
                const std::uint32_t h = test_hash((x * 73856093U) ^ (y * 19349663U) ^ ((layer + 1U) * 83492791U));
                const hlsl::float3 c = {static_cast<float>(h % (levels + 1U)) / n,
                                        static_cast<float>((h / 7U) % (levels + 1U)) / n,
                                        static_cast<float>((h / 49U) % (levels + 1U)) / n};
                const std::uint32_t mask = layer == 0 ? full : (test_hash(h) & full);
                for (std::uint32_t s = 0; s < samples; ++s) {
                    if (((mask >> s) & 1U) == 0U) continue;
                    colour[s] = c;
                    objects = objects_layer(layer) ? objects | (1U << s) : objects & ~(1U << s);
                }
            }
            const std::array<std::uint8_t, 2> grain = fields.at(fx::Field::grain, x, y);
            const passes::CompositeInputs inputs{
                .objects = objects, .mat = true, .grain = {grain[0], grain[1], opacity}};
            if (objects != 0U && objects != full) ++o.silhouettes;
            const hlsl::float3 want = passes::composite_pixel_fx(frame_layers, std::span(colour.data(), samples),
                                                                 inputs, hlsl::uint2{x, y}, box);
            const std::size_t i = (static_cast<std::size_t>(y) * out.width) + x;
            for (std::size_t c = 0; c < 3; ++c) {
                const std::uint32_t got = level_at(out, i, c);
                const auto expected = static_cast<std::uint32_t>(want[static_cast<int>(c)]);
                if (got == expected) continue;
                if (on_seam(x, y, out.width, out.height)) {
                    ++o.seam;
                    continue;
                }
                if (o.wrong == 0) {
                    o.first = std::format("pixel ({}, {}) channel {}: {} where the twin has {} (objects {:#x})", x, y,
                                          c, got, expected, objects);
                }
                ++o.wrong;
            }
        }
    }
    return o;
}

} // namespace

TEST_CASE("the composite's mat rule at the objects group's silhouettes and fin's grain are the twin's, bit for bit off "
          "lavapipe's seam, in every configuration, route and target") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D extent{.width = 96, .height = 72};
    constexpr std::uint32_t layers = 6;
    auto made = fx::FieldCache::create(device, {.threads = 2});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    fx::FieldCache& fields = **made;
    FieldsScene world(fields);
    const auto configs = composite_configurations(device);
    CHECK(!configs.empty());
    std::size_t drawn = 0;
    std::uint32_t seam_total = 0;
    bool first = true;
    for (const gpu::Configuration& config : configs) {
        for (const gpu::Depth depth : {gpu::Depth::eight, gpu::Depth::deepest}) {
            // K17a's steps alone in every case; at night, and with every step the composite draws, in the first.
            std::vector<std::pair<bool, std::uint32_t>> runs{
                {false, shaders::COMPOSITE_MAT | shaders::COMPOSITE_GRAIN}};
            if (first) {
                runs.emplace_back(true, shaders::COMPOSITE_MAT | shaders::COMPOSITE_GRAIN);
                runs.emplace_back(false, ~0U);
            }
            first = false;
            for (const auto& [night, steps] : runs) {
                auto output = gpu::Offscreen::create(device, extent, 2, depth);
                ATLAS_REQUIRE(output.has_value(), output.error().text());
                if (depth == gpu::Depth::deepest && !gpu::ten_bit((*output)->format())) continue;
                std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
                passes.push_back(std::make_unique<ObjectSamplesPass>(layers));
                auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes),
                                                           {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = steps});
                ATLAS_REQUIRE(builder.has_value(), builder.error().text());
                style::ThemeBlock theme{};
                theme.flags = style::theme_flag::weather | (night ? style::theme_flag::night : 0U);
                theme.haze = {.r = 0xC8, .g = 0xCD, .b = 0xD7, .a = 0xFF}; // a haze for the every-step run
                theme.haze_max = 0.55;
                (*builder)->set_inputs({.camera = standin::board_view(extent.width, extent.height, 1.0),
                                        .theme = theme,
                                        .seconds = 0.5,
                                        .mode = frame::Mode::full,
                                        .scene = &world});
                auto renderer = gpu::Renderer::create(device, **output, **builder, config);
                ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
                for (int f = 0; f < 4; ++f) {
                    auto report = (*renderer)->draw(0);
                    ATLAS_REQUIRE(report.has_value(), gpu::name_of(config) << ": " << report.error().text());
                    fields.worker().wait_idle();
                }
                (*renderer)->drain();
                const shaders::FrameBlock& block = (*builder)->state().block;
                ATLAS_REQUIRE((block.info.x & shaders::FRAME_FIELDS) != 0U, "the fields are bound");
                auto packed = (*output)->read_packed((*output)->latest());
                ATLAS_REQUIRE(packed.has_value(), packed.error().text());
                const std::shared_ptr<const fx::ScreenFields> latest = fields.worker().latest();
                ATLAS_REQUIRE(latest != nullptr);
                const auto samples = static_cast<std::uint32_t>(config.samples());
                const Outcome o = compare(*packed, layers, samples, block.layers, *latest,
                                          night ? shaders::COMPOSITE_GRAIN_NIGHT : shaders::COMPOSITE_GRAIN_DAY);
                const std::string name =
                    std::format("{} in {}{}{}", gpu::name_of(config), gpu::colour_name(packed->format),
                                night ? ", night" : "", steps == ~0U ? ", every step" : "");
                CHECK_MESSAGE(o.wrong == 0,
                              name << ": " << o.wrong << " channels differ from the twin's; the first: " << o.first);
                if (samples > 1) CHECK_MESSAGE(o.silhouettes > 0, name << ": no silhouette pixel");
                if (config.target == gpu::CompositeTarget::direct) {
                    CHECK_MESSAGE(o.seam == 0, name << ": " << o.seam << " channels on the diagonal differ");
                } else if (o.seam > 0) {
                    MESSAGE(name << ": " << o.seam
                                 << " channels of pixels on the clipped triangle's seam differ (shaded "
                                 << "twice in place on lavapipe: the rule and the grain applied twice there); every "
                                 << "other pixel is the twin's");
                    seam_total += o.seam;
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
                MESSAGE(name << ": " << extent.width * extent.height << " pixels, " << o.silhouettes
                             << " of them silhouettes, as the twin computes them");
                ++drawn;
                renderer->reset();
            }
        }
    }
    CHECK(drawn >= 2);
    MESSAGE(drawn << " frames; " << seam_total << " channels on lavapipe's seam differ in all");
}

#endif
