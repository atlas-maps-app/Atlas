// The frosted backdrops (passes/Frosted.hpp, K11b). On the CPU: the plan of a frame's frosted shapes (their place on
// the output through each pre-rotation, what is left out, regions merged and packed into the copy, coarser blocks
// where the copy is short, render passes grouped). On the host's Vulkan: the frame builder with the map's passes over
// a known picture (shaders/frosted/test_pattern.slang), its frame with frosted keep-out shapes against the same frame
// without them and against the CPU's model of the glass (the copy's blocks, fx::blur_cpu's boxes, the bilinear read,
// the saturation, the rounded rectangle's coverage, the blend): every pixel outside the shapes as it was, every pixel
// inside within a few levels of the model, in every configuration and route the device draws, at 8 and 10 bits, the
// validation layer and its synchronization validation silent.
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/Blur.hpp"
#include "gen/spirv/frosted/test_pattern.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Frosted.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <memory>
#include <string>
#include <vector>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

using namespace atlas;

namespace {

/// A frosted keep-out shape in dp, its chrome faded by `fade` (0 opaque, 127 gone).
[[nodiscard]] style::KeepOut shape(float left, float top, float right, float bottom, float radius, float blur_dp,
                                   float saturation, bool frosted = true, std::uint32_t fade = 0) {
    return {.left = left,
            .top = top,
            .right = right,
            .bottom = bottom,
            .radius = radius,
            .blur16 = static_cast<std::uint16_t>(std::lround(blur_dp * 16.0F)),
            .saturation = static_cast<std::uint8_t>(std::lround(saturation * 100.0F)),
            .flags = static_cast<std::uint8_t>((frosted ? 1U : 0U) | (fade << 1U))};
}

/// `camera` with `shapes` as its keep-outs.
[[nodiscard]] style::CameraState with(style::CameraState camera, std::span<const style::KeepOut> shapes) {
    camera.keep_out_count = static_cast<std::uint32_t>(shapes.size());
    camera.keep_out = {};
    for (std::size_t i = 0; i < shapes.size(); ++i) camera.keep_out[i] = shapes[i];
    return camera;
}

/// Whether two rectangles of the copy overlap.
[[nodiscard]] bool overlap(const passes::FrostRegion& a, const passes::FrostRegion& b) {
    return a.cx < b.cx + b.width && b.cx < a.cx + a.width && a.cy < b.cy + b.height && b.cy < a.cy + a.height;
}

/// Every region inside the copy, none over another.
void check_packed(const passes::FrostPlan& plan, vk::Extent2D copy) {
    for (std::size_t i = 0; i < plan.regions.size(); ++i) {
        const passes::FrostRegion& r = plan.regions[i];
        CHECK(r.cx + r.width <= copy.width);
        CHECK(r.cy + r.height <= copy.height);
        CHECK(r.width > 0);
        CHECK(r.height > 0);
        for (std::size_t j = i + 1; j < plan.regions.size(); ++j) CHECK_FALSE(overlap(r, plan.regions[j]));
    }
}

} // namespace

TEST_CASE("the plan places each frosted shape on the output through the pre-rotation, and leaves out what it cannot") {
    constexpr std::uint32_t w = 1080;
    constexpr std::uint32_t h = 2400;
    const style::CameraState board = standin::board_view(w, h, 2.625);
    const std::array shapes{
        shape(16, 40, 396, 92, 26, 20, 1.8F),               // VK bench's top pill
        shape(16, 815, 396, 867, 26, 20, 1.8F, false),      // kept clear, not frosted
        shape(500, 40, 600, 92, 26, 20, 1.8F),              // off the frame
        shape(-60, 300, 30, 344, 22, 22, 1.6F),             // partly off it
        shape(100, 500, 100.0F + 1e-3F, 544, 0, 22, 1.0F),  // too thin to cover a pixel's centre, but drawn
        shape(100, 600, 300, 644, 22, 22, 1.6F, true, 127), // its chrome faded out: no glass
    };
    const style::CameraState camera = with(board, shapes);
    const vk::Extent2D copy = passes::frost_copy_extent({.width = w, .height = h});
    passes::FrostPlan plan;
    passes::plan_frost(camera, 0, {.width = w, .height = h}, copy, plan);
    REQUIRE(plan.shapes.size() == 3);
    const passes::FrostShape& top = plan.shapes[0];
    CHECK(top.left == doctest::Approx(16 * 2.625));
    CHECK(top.top == doctest::Approx(40 * 2.625));
    CHECK(top.right == doctest::Approx(396 * 2.625));
    CHECK(top.bottom == doctest::Approx(92 * 2.625));
    CHECK(top.radius == doctest::Approx(26 * 2.625));
    CHECK(top.sigma == doctest::Approx(20 * 2.625));
    CHECK(top.saturation == doctest::Approx(1.8));
    CHECK(top.down == 4);
    CHECK(plan.shapes[1].left == doctest::Approx(-60 * 2.625));
    check_packed(plan, copy);
    // The regions stay on the frame's grid of blocks.
    for (const passes::FrostRegion& r : plan.regions) {
        CHECK(r.gx >= 0);
        CHECK(r.gy >= 0);
        CHECK((r.gx + static_cast<std::int32_t>(r.width)) * static_cast<std::int32_t>(r.down) <=
              static_cast<std::int32_t>(w));
        CHECK((r.gy + static_cast<std::int32_t>(r.height)) * static_cast<std::int32_t>(r.down) <=
              static_cast<std::int32_t>(h));
    }

    // Each quarter turn: the shape's view box turned as the frame's projection turns the view (view::turn_of).
    const std::array one{shape(10, 20, 110, 70, 12, 20, 1.0F)};
    const style::CameraState small = with(standin::board_view(400, 800, 1.0), one);
    for (std::uint32_t q = 0; q < 4; ++q) {
        const bool turned = (q & 1U) != 0;
        const vk::Extent2D out{.width = turned ? 800U : 400U, .height = turned ? 400U : 800U};
        passes::plan_frost(small, q, out, passes::frost_copy_extent(out), plan);
        REQUIRE(plan.shapes.size() == 1);
        const passes::FrostShape& s = plan.shapes[0];
        // The view's (x, y) lands on the output at: q 0 (x, y); q 1 (H - y, x); q 2 (W - x, H - y); q 3 (y, W - x),
        // where W, H are the view's size: so the box's corners as follows.
        const std::array<std::array<double, 4>, 4> want{{{10, 20, 110, 70},
                                                         {800 - 70, 10, 800 - 20, 110},
                                                         {400 - 110, 800 - 70, 400 - 10, 800 - 20},
                                                         {20, 400 - 110, 70, 400 - 10}}};
        CHECK_MESSAGE(s.left == doctest::Approx(want[q][0]), "q " << q);
        CHECK_MESSAGE(s.top == doctest::Approx(want[q][1]), "q " << q);
        CHECK_MESSAGE(s.right == doctest::Approx(want[q][2]), "q " << q);
        CHECK_MESSAGE(s.bottom == doctest::Approx(want[q][3]), "q " << q);
    }

    // Nothing frosted, nothing planned; no frame size, nothing.
    const std::array clear{shape(16, 40, 396, 92, 26, 20, 1.8F, false)};
    passes::plan_frost(with(board, clear), 0, {.width = w, .height = h}, copy, plan);
    CHECK(plan.empty());
    CHECK(plan.regions.empty());
    CHECK(plan.groups.empty());
    passes::plan_frost(camera, 0, {.width = 0, .height = 0}, copy, plan);
    CHECK(plan.empty());
}

TEST_CASE("the plan merges the home's chips into one region and one render pass, and keeps another blur apart") {
    constexpr std::uint32_t w = 1080;
    constexpr std::uint32_t h = 2400;
    // The Main home's chrome on a 412 × 915 dp board: the weather chip and the quick needs, the control column, the
    // eight category chips, the tab bar.
    std::vector<style::KeepOut> shapes{
        shape(16, 48, 126, 92, 22, 22, 1.6F),   // weather
        shape(16, 102, 116, 146, 22, 22, 1.0F), // needs
        shape(348, 48, 396, 328, 24, 22, 1.6F), // controls
        shape(12, 837, 400, 901, 32, 24, 1.6F), // the tab bar
    };
    for (int i = 0; i < 8; ++i) {
        const float x = 12.0F + (static_cast<float>(i) * 49.5F);
        shapes.push_back(shape(x, 717, x + 41.5F, 761, 20.75F, 20, 1.0F));
    }
    const style::CameraState camera = with(standin::board_view(w, h, 2.62136), shapes);
    const vk::Extent2D copy = passes::frost_copy_extent({.width = w, .height = h});
    passes::FrostPlan plan;
    passes::plan_frost(camera, 0, {.width = w, .height = h}, copy, plan);
    REQUIRE(plan.shapes.size() == 12);
    check_packed(plan, copy);
    // The chips share a region and a render pass; the bar's blur is another; weather and needs share a region (one σ)
    // though they saturate differently (saturation is the glass's, not the copy's).
    for (std::size_t i = 5; i < 12; ++i) {
        CHECK(plan.shapes[i].region == plan.shapes[4].region);
        CHECK(plan.shapes[i].group == plan.shapes[4].group);
    }
    CHECK(plan.shapes[3].region != plan.shapes[4].region);
    CHECK(plan.shapes[0].region == plan.shapes[1].region);
    CHECK(plan.shapes[0].group == plan.shapes[1].group);
    MESSAGE("the home's 12 frosted shapes: " << plan.regions.size() << " regions, " << plan.groups.size()
                                             << " render passes, copy " << copy.width << " × " << copy.height);
    std::uint64_t texels = 0;
    for (const passes::FrostRegion& r : plan.regions) texels += std::uint64_t{r.width} * r.height;
    MESSAGE("copied texels: " << texels << " (a quarter of the frame is " << (w / 4) * (h / 4) << ")");
    CHECK(plan.regions.size() <= 5);
    CHECK(plan.groups.size() <= 5);

    // A copy too small for them all: coarser blocks until every region fits.
    const vk::Extent2D tiny{.width = 120, .height = 160};
    passes::plan_frost(camera, 0, {.width = w, .height = h}, tiny, plan);
    CHECK(plan.shapes.size() == 12);
    check_packed(plan, tiny);
    bool coarser = false;
    for (const passes::FrostShape& s : plan.shapes) coarser = coarser || s.down > 4;
    CHECK(coarser);
}

TEST_CASE("the copy's block follows Backdrop.kt's: full resolution under σ = 8 px, then 4 px, coarser past 64 px") {
    CHECK(passes::frost_block(0.0) == 1);
    CHECK(passes::frost_block(7.99) == 1);
    CHECK(passes::frost_block(8.0) == 4);
    CHECK(passes::frost_block(57.75) == 4);
    CHECK(passes::frost_block(64.0) == 4);
    CHECK(passes::frost_block(64.5) == 8);
    CHECK(passes::frost_block(672.0) == 64);
    CHECK(passes::frost_block(1e9) == 64);
}

#if !defined(__ANDROID__)

namespace {

/// The test picture in the scene subpass: a full-screen triangle of known colours.
class PatternPass final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "pattern"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        release();
        ATLAS_GPU_LET(module, frame::make_module(c, "frosted_test_pattern", spirv::frosted_test_pattern));
        module_ = std::move(module);
        gpu::GraphicsDesc desc{
            .label = {.name = "pattern", .module = "frosted_test_pattern", .configuration = c.configuration},
            .module = *module_,
            .vertex = "pattern_vs",
            .fragment = "pattern_fs",
            .layout = c.layout.pipeline_layout(),
            .target = c.pass.scene_target()};
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(c, desc, frame::Overdraw::opaque));
        pipeline_ = std::move(pipeline);
        return {};
    }
    void release() noexcept override {
        pipeline_.clear();
        module_.clear();
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)phase;
        r.frame.cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
        r.frame.cmd.draw(3, 1, 0, 0);
        r.counters.draw("pattern", 1);
    }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{frame::Phase{.slot = frame::Slot::objects}};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

/// The configurations the frame draws on this device: each one the facts allow, through both routes where it can.
std::vector<gpu::Configuration> configurations(const gpu::Device& device) {
    std::vector<gpu::Configuration> out;
    const bool dynamic = device.enabled().dynamic_rendering && device.enabled().local_read;
    for (const gpu::FrameConfig c :
         {gpu::FrameConfig::a, gpu::FrameConfig::b, gpu::FrameConfig::c, gpu::FrameConfig::x1}) {
        if (!device.facts().supports(c)) continue;
        const bool one = gpu::Configuration{.config = c}.samples() == vk::SampleCountFlagBits::e1;
        for (const gpu::Route route : {gpu::Route::renderpass, gpu::Route::dynamic}) {
            if (route == gpu::Route::dynamic && !dynamic) continue;
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::composite,
                           .target = one ? gpu::CompositeTarget::direct : gpu::CompositeTarget::resolve});
        }
    }
    return out;
}

/// One frame drawn: its pixels at 8 bits, its packed words, the frosted pass's plan and counts, its GPU times.
struct Drawn {
    platform::Pixels pixels;
    platform::Packed packed;
    passes::FrostPlan plan;
    vk::Extent2D copy{};
    frame::Count frosted;
    gpu::GpuTimes gpu;
};

[[nodiscard]] gpu::Result<Drawn> draw(const gpu::Device& device, const gpu::PipelineCache& cache,
                                      const gpu::Configuration& config, vk::Extent2D extent, gpu::Depth depth,
                                      const style::CameraState& camera) {
    ATLAS_GPU_LET(output, platform::Offscreen::create(device, extent, 2, depth));
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes.push_back(std::make_unique<PatternPass>());
    ATLAS_GPU_LET(builder, frame::FrameBuilder::create(device, cache, std::move(passes),
                                                       {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = 0}));
    builder->set_inputs({.camera = camera, .theme = {}, .seconds = 0.5, .mode = frame::Mode::structural});
    ATLAS_GPU_LET(renderer, gpu::Renderer::create(device, *output, *builder, config));
    for (int f = 0; f < 3; ++f) {
        ATLAS_GPU_LET(report, renderer->draw(0));
        (void)report;
    }
    renderer->drain();
    Drawn d;
    d.gpu = renderer->frames().last_times();
    ATLAS_GPU_LET(packed, output->read_packed(output->latest()));
    d.pixels = platform::to_rgba8(packed);
    d.packed = std::move(packed);
    for (std::size_t p = 0; p < builder->passes().size(); ++p) {
        if (builder->passes()[p]->name() != "frosted") continue;
        const auto* frosted = static_cast<const passes::Frosted*>(builder->passes()[p].get()); // NOLINT
        d.plan = frosted->backdrops().plan();
        d.copy = frosted->backdrops().copy_extent();
        d.frosted = builder->counters()[p].total();
    }
    renderer.reset();
    return d;
}

/// The rounded rectangle's coverage of the pixel centred at (x, y), as glass.slang computes it.
[[nodiscard]] double coverage_at(const passes::FrostShape& s, double x, double y) {
    const double hx = (s.right - s.left) * 0.5;
    const double hy = (s.bottom - s.top) * 0.5;
    const double r = std::clamp(s.radius, 0.0, std::min(hx, hy));
    const double qx = std::abs(x - (s.left + hx)) - (hx - r);
    const double qy = std::abs(y - (s.top + hy)) - (hy - r);
    const double outside = std::sqrt((std::max(qx, 0.0) * std::max(qx, 0.0)) + (std::max(qy, 0.0) * std::max(qy, 0.0)));
    const double d = outside + std::min(std::max(qx, qy), 0.0) - r;
    return std::clamp(0.5 - d, 0.0, 1.0);
}

/// The CPU's model of the glass over `bare`: each region's blocks (the linear blit's mean of the block's middle four
/// pixels), fx::blur_cpu's boxes along x (opaque) then y, their ends extended, then for each shape's pixels the
/// bilinear read of its region within it (shifted by `shift_x`, `shift_y` px: the test's check that no shift fits
/// better), CSS's saturate() on the unpremultiplied colour, the coverage and the blend over the frame. In 8-bit
/// levels, as doubles.
[[nodiscard]] std::vector<double> model(const platform::Pixels& bare, const passes::FrostPlan& plan, double shift_x = 0,
                                        double shift_y = 0) {
    const std::uint32_t w = bare.width;
    std::vector<double> out(bare.rgba.size());
    for (std::size_t i = 0; i < out.size(); ++i) out[i] = bare.rgba[i];
    std::vector<std::vector<std::uint32_t>> blurred;
    for (const passes::FrostRegion& r : plan.regions) {
        std::vector<std::uint32_t> texels(std::size_t{r.width} * r.height);
        const std::uint32_t d = r.down;
        for (std::uint32_t by = 0; by < r.height; ++by) {
            for (std::uint32_t bx = 0; bx < r.width; ++bx) {
                const std::uint32_t x0 = (static_cast<std::uint32_t>(r.gx) + bx) * d;
                const std::uint32_t y0 = (static_cast<std::uint32_t>(r.gy) + by) * d;
                const std::uint32_t xa = d == 1 ? x0 : x0 + (d / 2) - 1;
                const std::uint32_t ya = d == 1 ? y0 : y0 + (d / 2) - 1;
                const std::uint32_t xb = d == 1 ? x0 : xa + 1;
                const std::uint32_t yb = d == 1 ? y0 : ya + 1;
                std::uint32_t texel = 0xFF000000U;
                for (std::uint32_t c = 0; c < 3; ++c) {
                    auto at = [&](std::uint32_t x, std::uint32_t y) {
                        return static_cast<double>(bare.rgba[((std::size_t{y} * w + x) * 4) + c]);
                    };
                    const double mean = (at(xa, ya) + at(xb, ya) + at(xa, yb) + at(xb, yb)) / 4.0;
                    texel |= static_cast<std::uint32_t>(std::lround(mean)) << (8U * c);
                }
                texels[(std::size_t{by} * r.width) + bx] = texel;
            }
        }
        std::vector<std::uint32_t> along_x(texels.size());
        fx::blur_cpu(texels, along_x, r.width, {.x = 0, .y = 0, .width = r.width, .height = r.height, .run = r.run},
                     {.axis = fx::Axis::x, .rounding = fx::Rounding::nearest, .edge = fx::Edge::extend, .opaque = true},
                     fx::BlurFormat::rgba8);
        std::vector<std::uint32_t> both(texels.size());
        fx::blur_cpu(along_x, both, r.width, {.x = 0, .y = 0, .width = r.width, .height = r.height, .run = r.run},
                     {.axis = fx::Axis::y, .rounding = fx::Rounding::nearest, .edge = fx::Edge::extend},
                     fx::BlurFormat::rgba8);
        blurred.push_back(std::move(both));
    }
    for (std::size_t g = 0; g < plan.groups.size(); ++g) {
        for (const passes::FrostShape& s : plan.shapes) {
            if (s.group != g) continue;
            const passes::FrostRegion& r = plan.regions[s.region];
            const std::vector<std::uint32_t>& t = blurred[s.region];
            const auto x0 = static_cast<std::int64_t>(std::max(0.0, std::floor(s.left) - 1));
            const auto y0 = static_cast<std::int64_t>(std::max(0.0, std::floor(s.top) - 1));
            const auto x1 = static_cast<std::int64_t>(std::min<double>(w, std::ceil(s.right) + 1));
            const auto y1 = static_cast<std::int64_t>(std::min<double>(bare.height, std::ceil(s.bottom) + 1));
            for (std::int64_t y = y0; y < y1; ++y) {
                for (std::int64_t x = x0; x < x1; ++x) {
                    const double px = static_cast<double>(x) + 0.5;
                    const double py = static_cast<double>(y) + 0.5;
                    const double cov = coverage_at(s, px, py) * s.alpha;
                    if (cov <= 0.0) continue;
                    // The region's texels, their centres at i + 0.5, read within the region.
                    const double u = std::clamp(((px + shift_x) / r.down) - r.gx, 0.5, r.width - 0.5) - 0.5;
                    const double v = std::clamp(((py + shift_y) / r.down) - r.gy, 0.5, r.height - 0.5) - 0.5;
                    const auto i0 = static_cast<std::uint32_t>(std::floor(u));
                    const auto j0 = static_cast<std::uint32_t>(std::floor(v));
                    const std::uint32_t i1 = std::min(i0 + 1, r.width - 1);
                    const std::uint32_t j1 = std::min(j0 + 1, r.height - 1);
                    const double fu = u - i0;
                    const double fv = v - j0;
                    std::array<double, 4> read{};
                    for (std::uint32_t c = 0; c < 4; ++c) {
                        auto at = [&](std::uint32_t i, std::uint32_t j) {
                            return static_cast<double>((t[(std::size_t{j} * r.width) + i] >> (8U * c)) & 0xFFU) / 255.0;
                        };
                        read[c] = ((at(i0, j0) * (1 - fu) + at(i1, j0) * fu) * (1 - fv)) +
                                  ((at(i0, j1) * (1 - fu) + at(i1, j1) * fu) * fv);
                    }
                    const double a = read[3];
                    std::array<double, 3> col{};
                    for (std::size_t c = 0; c < 3; ++c) col[c] = a > 0 ? read[c] / a : 0.0;
                    const double grey = (0.213 * col[0]) + (0.715 * col[1]) + (0.072 * col[2]);
                    const std::size_t at = (std::size_t(y) * w + std::size_t(x)) * 4;
                    for (std::size_t c = 0; c < 3; ++c) {
                        const double sat = std::clamp(grey + ((col[c] - grey) * s.saturation), 0.0, 1.0);
                        const double glass = sat * a * cov;
                        out[at + c] = (glass * 255.0) + (out[at + c] * (1.0 - (a * cov)));
                    }
                }
            }
        }
    }
    return out;
}

std::filesystem::path scratch(const std::string& name) {
    const char* dir = std::getenv("ATLAS_TEST_OUT"); // NOLINT(concurrency-mt-unsafe)
    std::filesystem::path base =
        dir != nullptr ? std::filesystem::path(dir) : std::filesystem::path(ATLAS_TEST_SCRATCH) / "frosted";
    std::error_code ec;
    std::filesystem::create_directories(base, ec);
    return base / name;
}

} // namespace

TEST_CASE("the frosted pass draws the glass inside the frosted shapes as the CPU's model of it, and leaves every other "
          "pixel as it was, in every configuration and route, at 8 and 10 bits") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D extent{.width = 360, .height = 800};
    const style::CameraState board = standin::board_view(extent.width, extent.height, 1.0);
    // A pill at the board's σ, one running past the frame's left edge, a disc without saturation, a narrow pill whose
    // small σ is copied at full resolution, a pill whose chrome is half faded out (a screen's fade: its glass at half),
    // and a shape kept clear but not frosted.
    const std::array shapes{
        shape(16, 40, 344, 92, 26, 20, 1.8F),
        shape(-30, 700, 200, 752, 26, 22, 1.6F),
        shape(300, 600, 344, 644, 22, 22, 1.0F),
        shape(40, 300, 200, 340, 8, 5, 1.8F),
        shape(40, 500, 320, 552, 26, 20, 1.8F, true, 64),
        shape(40, 400, 200, 440, 8, 20, 1.8F, false),
    };
    const style::CameraState frosted_camera = with(board, shapes);
    std::size_t drawn = 0;
    for (const gpu::Configuration& config : configurations(device)) {
        std::optional<Drawn> eight;
        for (const gpu::Depth depth : {gpu::Depth::eight, gpu::Depth::deepest}) {
            auto bare = draw(device, *cache, config, extent, depth, board);
            ATLAS_REQUIRE(bare.has_value(), gpu::name_of(config) << ": " << bare.error().text());
            if (depth == gpu::Depth::deepest && !gpu::ten_bit(bare->packed.format)) continue;
            auto frosted = draw(device, *cache, config, extent, depth, frosted_camera);
            ATLAS_REQUIRE(frosted.has_value(), gpu::name_of(config) << ": " << frosted.error().text());
            const std::string name =
                std::format("{} in {}", gpu::name_of(config), gpu::colour_name(bare->packed.format));
            for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
            // The plan: five frosted shapes, the half-faded one's glass at half.
            const passes::FrostPlan& plan = frosted->plan;
            CHECK(plan.shapes.size() == 5);
            CHECK(plan.shapes[4].alpha == doctest::Approx(63.0 / 127.0));
            CHECK(bare->plan.empty());
            CHECK(frosted->frosted.dispatches == 2);
            CHECK(frosted->frosted.draws == plan.shapes.size());
            CHECK(bare->frosted.draws == 0);
            CHECK(bare->frosted.dispatches == 0);
            // Outside the shapes' boxes (a pixel's coverage reaches a pixel past them), every word as it was.
            std::vector<bool> under(std::size_t{extent.width} * extent.height, false);
            for (const passes::FrostShape& s : plan.shapes) {
                const auto x0 = static_cast<std::int64_t>(std::max(0.0, std::floor(s.left) - 1));
                const auto y0 = static_cast<std::int64_t>(std::max(0.0, std::floor(s.top) - 1));
                const auto x1 = static_cast<std::int64_t>(std::min<double>(extent.width, std::ceil(s.right) + 1));
                const auto y1 = static_cast<std::int64_t>(std::min<double>(extent.height, std::ceil(s.bottom) + 1));
                for (std::int64_t y = y0; y < y1; ++y) {
                    for (std::int64_t x = x0; x < x1; ++x)
                        under[(std::size_t(y) * extent.width) + std::size_t(x)] = true;
                }
            }
            const std::uint32_t words = platform::words_per_pixel(bare->packed.format);
            std::size_t moved = 0;
            std::size_t changed = 0;
            for (std::size_t p = 0; p < under.size(); ++p) {
                bool same = true;
                for (std::uint32_t k = 0; k < words; ++k) {
                    same = same && bare->packed.words[(p * words) + k] == frosted->packed.words[(p * words) + k];
                }
                if (!under[p] && !same) ++moved;
                if (under[p] && !same) ++changed;
            }
            CHECK_MESSAGE(moved == 0, name << ": " << moved << " pixels outside the frosted shapes changed");
            CHECK_MESSAGE(changed > 20000, name << ": the glass changed " << changed << " pixels");
            if (depth == gpu::Depth::eight) {
                // Inside: within a few levels of the model, the edges' coverage given a little more.
                const std::vector<double> want = model(bare->pixels, plan);
                double worst_inside = 0;
                double worst_edge = 0;
                double sum = 0;
                std::size_t n = 0;
                for (std::uint32_t y = 0; y < extent.height; ++y) {
                    for (std::uint32_t x = 0; x < extent.width; ++x) {
                        double cov = 0;
                        for (const passes::FrostShape& s : plan.shapes) {
                            cov = std::max(cov, coverage_at(s, x + 0.5, y + 0.5));
                        }
                        if (cov <= 0) continue;
                        const std::size_t at = (std::size_t{y} * extent.width + x) * 4;
                        for (std::size_t c = 0; c < 3; ++c) {
                            const double diff = std::abs(frosted->pixels.rgba[at + c] - want[at + c]);
                            (cov >= 1.0 ? worst_inside : worst_edge) =
                                std::max(cov >= 1.0 ? worst_inside : worst_edge, diff);
                            sum += diff;
                            ++n;
                        }
                    }
                }
                // The copy is read where the model reads it: no shift of a pixel either way fits the frame better.
                if (drawn == 0) {
                    auto mean_abs = [&](const std::vector<double>& m) {
                        double total = 0;
                        std::size_t count = 0;
                        for (std::uint32_t y = 0; y < extent.height; ++y) {
                            for (std::uint32_t x = 0; x < extent.width; ++x) {
                                if (coverage_at(plan.shapes[0], x + 0.5, y + 0.5) < 1.0) continue;
                                const std::size_t at = (std::size_t{y} * extent.width + x) * 4;
                                for (std::size_t c = 0; c < 3; ++c)
                                    total += std::abs(frosted->pixels.rgba[at + c] - m[at + c]);
                                count += 3;
                            }
                        }
                        return total / static_cast<double>(count);
                    };
                    const double here = mean_abs(want);
                    for (const std::array<double, 2> shift :
                         {std::array<double, 2>{-1, 0}, std::array<double, 2>{1, 0}, std::array<double, 2>{0, -1},
                          std::array<double, 2>{0, 1}}) {
                        const double there = mean_abs(model(bare->pixels, plan, shift[0], shift[1]));
                        CHECK_MESSAGE(here < there, name << ": the model shifted by (" << shift[0] << ", " << shift[1]
                                                         << ") px fits better: " << there << " against " << here);
                    }
                }
                // lavapipe filters in 8-bit fixed point (its lerps truncate): about a level low on average, a few at
                // worst once the saturation stretches it; a copy read a pixel off, or blurred by other boxes, is more.
                CHECK_MESSAGE(worst_inside <= 6.0,
                              name << ": inside the shapes, " << worst_inside << " levels from the model");
                CHECK_MESSAGE(worst_edge <= 7.0,
                              name << ": at their edges, " << worst_edge << " levels from the model");
                CHECK_MESSAGE(sum / static_cast<double>(n) <= 1.5, name << ": mean " << sum / static_cast<double>(n));
                MESSAGE(name << ": the glass within " << worst_inside << " levels of the model inside the shapes ("
                             << worst_edge << " at their edges, mean " << sum / static_cast<double>(n)
                             << "); the frosted pass " << frosted->gpu.after_ns / 1e6 << " ms of GPU on "
                             << device.facts().name);
                if (drawn == 0) {
                    (void)platform::write_ppm(bare->pixels, scratch("frosted-bare.ppm").string());
                    (void)platform::write_ppm(frosted->pixels, scratch("frosted.ppm").string());
                }
                eight = std::move(*frosted);
            } else if (eight) {
                // At 10 bits the copy is 8-bit all the same: the glass within a few levels of the 8-bit frame's.
                const platform::Difference d = platform::compare(eight->pixels, frosted->pixels, 0, false);
                CHECK_MESSAGE(d.largest <= 4, name << ": against the 8-bit frame, up to " << d.largest << " levels");
            }
            ++drawn;
        }
    }
    CHECK(drawn >= 2);
}

TEST_CASE("the frosted pass at the board's size: the home's twelve shapes, and its GPU time on this device") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D s1{.width = 1080, .height = 2400};
    std::vector<style::KeepOut> shapes{
        shape(16, 48, 126, 92, 22, 22, 1.6F),
        shape(16, 102, 116, 146, 22, 22, 1.0F),
        shape(348, 48, 396, 328, 24, 22, 1.6F),
        shape(12, 837, 400, 901, 32, 24, 1.6F),
    };
    for (int i = 0; i < 8; ++i) {
        const float x = 12.0F + (static_cast<float>(i) * 49.5F);
        shapes.push_back(shape(x, 717, x + 41.5F, 761, 20.75F, 20, 1.0F));
    }
    const style::CameraState camera = with(standin::board_view(s1.width, s1.height, 2.62136), shapes);
    const gpu::Configuration config = device.facts().choice;
    auto frosted = draw(device, *cache, config, s1, gpu::Depth::eight, camera);
    ATLAS_REQUIRE(frosted.has_value(), frosted.error().text());
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    CHECK(frosted->plan.shapes.size() == 12);
    CHECK(frosted->frosted.draws == 12);
    MESSAGE("the home's chrome at S1: " << frosted->plan.regions.size() << " regions, " << frosted->plan.groups.size()
                                        << " render passes; the frosted pass " << frosted->gpu.after_ns / 1e6
                                        << " ms of GPU on " << device.facts().name << " (the phone's: VK bench)");
    (void)platform::write_ppm(frosted->pixels, scratch("frosted-home.ppm").string());
}

#endif
