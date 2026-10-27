// The app's markers drawn by the core (passes/Markers.hpp, shaders/overlay/marker.slang), from a MarkerSet in the
// frame's inputs, on every configuration and route the host's Vulkan has: a marker as itself its look's texels on whole
// pixels at its place, blended over the frame as premultiplied colour, exactly; two dots that meet folded into a
// cluster of two, drawn as its bubble (the disc of its colour inside the white one, the shadow below it) with its
// count's sprite centred on it; the draws the lowest priority first; and the labels keeping clear of the markers (a
// label at a pin's place is not placed while the pin is there, and is once it is gone). With ATLAS_MARKERS_PPM naming
// a folder, each configuration's frame is written there.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "labels/Sprites.hpp"
#include "markers/Markers.hpp"
#include "passes/Labels.hpp"
#include "passes/Markers.hpp"
#include "standin/View.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <format>
#include <memory>
#include <vector>

#if !defined(__ANDROID__)
#include "gpu/Offscreen.hpp"

using namespace atlas;

namespace {

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before threads start
    return v == nullptr ? std::string() : std::string(v);
}

/// A world without tiles: the frame drawn and the labels' sprites.
class Stub final : public frame::FrameScene {
public:
    Stub(view::GroundFrame frame, frame::GlobalProvider* globals) noexcept : frame_(frame), globals_{globals} {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return nullptr; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }
    [[nodiscard]] std::optional<view::GroundFrame> ground_frame() const noexcept override { return frame_; }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return globals_; }

private:
    view::GroundFrame frame_;
    std::array<frame::GlobalProvider*, 1> globals_;
};

/// The configurations the host's device draws the map's frame in, each route (labels_vk_test's list).
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

constexpr std::array<float, 4> clear{0.25F, 0.5F, 0.75F, 1.0F};
constexpr std::uint32_t frame_width = 400;
constexpr std::uint32_t frame_height = 300;
constexpr float density = 2.0F;

/// A colour, 0 to 255 a channel.
using Rgb = std::array<double, 3>;

const Rgb background{std::round(0.25 * 255.0), std::round(0.5 * 255.0), std::round(0.75 * 255.0)};

/// An image of `width` x `height` px whose texels are premultiplied colours, opaque and translucent ones, its anchor
/// at (`ax`, `ay`).
markers::Image image(std::uint32_t width, std::uint32_t height, std::int32_t ax, std::int32_t ay, std::uint32_t seed) {
    markers::Image m{.width = width, .height = height, .anchor_x = ax, .anchor_y = ay};
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint32_t a = (x * 37 + y * 11 + seed * 5) % 3 == 0 ? 128U : 255U;
            const std::uint32_t r = (a * ((x * 7 + seed) % 256)) / 255;
            const std::uint32_t g = (a * ((y * 29) % 256)) / 255;
            const std::uint32_t b = (a * ((seed * 83) % 256)) / 255;
            m.pixels.push_back(r | (g << 8U) | (b << 16U) | (a << 24U));
        }
    }
    return m;
}

/// An opaque image of one colour (0xAABBGGRR, R in the low byte).
markers::Image solid(std::uint32_t width, std::uint32_t height, std::uint32_t abgr) {
    return {.width = width,
            .height = height,
            .pixels = std::vector<std::uint32_t>(std::size_t{width} * height, abgr),
            .anchor_x = static_cast<std::int32_t>(width / 2),
            .anchor_y = static_cast<std::int32_t>(height / 2)};
}

/// A label of `width` x `height` px at one point.
labels::LabelSprite label(std::uint32_t width, std::uint32_t height, double lat, double lon) {
    labels::LabelSprite s;
    s.id = 1;
    s.priority = 1;
    s.measures = {.half = (static_cast<float>(width) / 2.0F) + 8.0F,
                  .pad = 8.0F,
                  .text_height = static_cast<float>(height),
                  .baseline = -4.0F};
    s.points = {{lat, lon}};
    s.own = {.width = width,
             .height = height,
             .pixels = std::vector<std::uint32_t>(std::size_t{width} * height, 0xFF00'00FFU),
             .anchor_x = static_cast<std::int32_t>(width / 2),
             .anchor_y = static_cast<std::int32_t>(height / 2)};
    return s;
}

} // namespace

TEST_CASE("the app's markers drawn: their looks on whole pixels, a cluster's bubble and count, the labels clear") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::Markers>();
    const passes::Markers* markers = owned.get();
    passes.push_back(std::move(owned));
    auto owned_labels = std::make_unique<passes::Labels>();
    const passes::Labels* labels = owned_labels.get();
    passes.push_back(std::move(owned_labels));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.clear = clear, .steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    passes::SpriteGlobals globals(device);
    const double lat0 = standin::origin_latitude;
    const double lon0 = standin::origin_longitude;
    const view::GroundFrame ground{
        .lat0 = lat0, .lon0 = lon0, .kx = 111320.0 * pymath::cos(pymath::radians(lat0)), .kz = 110540.0};
    Stub world(ground, &globals);
    // where a frame point (x, z metres) is on the map
    const auto lat_of = [&](double z) { return lat0 - (z / ground.kz); };
    const auto lon_of = [&](double x) { return lon0 + (x / ground.kx); };
    // A pin at the view's target (its look 24 x 20 px, its place at 12, 10), priority 5; two dots 60 m west, half a
    // metre apart, which fold into a cluster of two (a bubble of #E8612B, its count in white); the count's sprite an
    // opaque yellow 6 x 8.
    const std::uint32_t colour = 0xFFE8'612BU;
    const std::uint32_t white = 0xFFFF'FFFFU;
    std::vector<markers::Marker> list{
        {.latitude = lat0, .longitude = lon0, .priority = 5, .style = markers::Style::pin, .look = 0, .look_left = 0},
        {.latitude = lat0,
         .longitude = lon_of(-60.0),
         .style = markers::Style::dot,
         .colour = colour,
         .ink = white,
         .look = 1,
         .look_left = 1},
        {.latitude = lat_of(0.5),
         .longitude = lon_of(-59.5),
         .style = markers::Style::dot,
         .colour = colour,
         .ink = white,
         .look = 1,
         .look_left = 1},
    };
    const std::uint32_t yellow = 0xFF00'FFFFU; // R and G
    auto set = markers::MarkerSet::make(list, {image(24, 20, 12, 10, 1), image(10, 10, 5, 5, 2)},
                                        {{.ink = white, .key = 2, .image = solid(6, 8, yellow)}}, density, 1);
    ATLAS_REQUIRE(set.has_value(), markers::describe(set.error()));
    // a label at the pin's place
    const auto label_set = labels::LabelSet::make({label(31, 13, lat0, lon0)}, 1, 0.0F, 0.0F);
    ATLAS_REQUIRE(label_set.has_value(), labels::describe(label_set.error()));
    style::CameraState camera =
        standin::board_view(frame_width, frame_height, static_cast<double>(density), 46.0, 18.0, 300.0);
    camera.flags = style::camera_flag::rest;
    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    double seconds = 1.0;
    float pin_x = 0.0F; // where the pin's place stands on the view
    float pin_y = 0.0F;
    for (const gpu::Configuration& config : configs) {
        auto output =
            gpu::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        for (int f = 0; f < 2; ++f) {
            (*builder)->set_inputs({.camera = camera,
                                    .seconds = seconds,
                                    .scene = &world,
                                    .labels = label_set->get(),
                                    .markers = set->get()});
            seconds += 0.016;
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), gpu::name_of(config) << ": " << report.error().text());
        }
        (*renderer)->drain();
        auto pixels = (*output)->read((*output)->latest());
        ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
        const auto& rgba = pixels->rgba;
        if (const std::string out = environment("ATLAS_MARKERS_PPM"); !out.empty()) {
            CHECK(gpu::write_ppm(*pixels, std::format("{}/markers-{}.ppm", out, gpu::name_of(config))));
        }
        const auto at = [&](std::int32_t x, std::int32_t y) {
            const std::size_t p = 4 * ((static_cast<std::size_t>(y) * frame_width) + static_cast<std::size_t>(x));
            return Rgb{static_cast<double>(rgba[p]), static_cast<double>(rgba[p + 1]),
                       static_cast<double>(rgba[p + 2])};
        };
        const auto near = [](const Rgb& a, const Rgb& b, double levels) {
            return std::fabs(a[0] - b[0]) <= levels && std::fabs(a[1] - b[1]) <= levels &&
                   std::fabs(a[2] - b[2]) <= levels;
        };
        // placed: the cluster of two (the first dot, the second folded into it) under the pin, drawn first
        const auto placed = markers->layout().placed();
        ATLAS_REQUIRE(placed.size() == 2);
        CHECK(placed[0] == 1);
        CHECK(placed[1] == 0);
        CHECK(markers->layout().clusters()[0] == 2);
        CHECK(markers->layout().clusters()[1] == 0);
        const auto draws = markers->draws();
        ATLAS_REQUIRE(draws.size() == 3); // the bubble, its count, the pin
        CHECK(draws[0].source[3] == 1U);
        CHECK(draws[1].source[3] == 0U);
        CHECK(draws[2].source[3] == 0U);
        // the pin's texels on whole pixels at its place, over the frame, exactly
        pin_x = markers->xs()[0];
        pin_y = markers->ys()[0];
        const auto px = static_cast<std::int32_t>(std::floor(static_cast<double>(pin_x) + 0.5));
        const auto py = static_cast<std::int32_t>(std::floor(static_cast<double>(pin_y) + 0.5));
        CHECK(draws[2].target[0] == static_cast<float>(px - 12));
        CHECK(draws[2].target[1] == static_cast<float>(py - 10));
        const markers::Image pin = image(24, 20, 12, 10, 1);
        std::size_t wrong = 0;
        for (std::int32_t y = 0; y < 20; ++y) {
            for (std::int32_t x = 0; x < 24; ++x) {
                const std::uint32_t t = pin.pixels[(static_cast<std::size_t>(y) * 24) + static_cast<std::size_t>(x)];
                const double a = static_cast<double>(t >> 24U) / 255.0;
                Rgb want{};
                for (std::size_t k = 0; k < 3; ++k) {
                    want.at(k) =
                        std::round(static_cast<double>((t >> (8U * k)) & 0xFFU) + (background.at(k) * (1.0 - a)));
                }
                if (!near(at(px - 12 + x, py - 10 + y), want, 1.0)) ++wrong;
            }
        }
        CHECK_MESSAGE(wrong == 0, gpu::name_of(config));
        // the cluster's bubble: its count's yellow at its centre; the disc of its colour 3 dp in; white 1 dp in; the
        // shadow darker 6 dp below the disc than 6 dp above it
        const auto bx = static_cast<std::int32_t>(draws[0].target[0]);
        const auto by = static_cast<std::int32_t>(draws[0].target[1]);
        const auto r = static_cast<double>(draws[0].target[2]);
        const auto d = static_cast<double>(density);
        CHECK(r == doctest::Approx((28.0 + (6.0 * pymath::log10(2.0))) * d / 2.0).epsilon(1e-6));
        CHECK_MESSAGE(near(at(bx, by), Rgb{255.0, 255.0, 0.0}, 1.0), gpu::name_of(config));
        const auto ring = [&](double in) { return static_cast<std::int32_t>(std::floor(r - (in * d))); };
        CHECK_MESSAGE(near(at(bx + ring(3.0), by), Rgb{232.0, 97.0, 43.0}, 1.0), gpu::name_of(config));
        CHECK_MESSAGE(near(at(bx - ring(1.0) - 1, by), Rgb{255.0, 255.0, 255.0}, 1.0), gpu::name_of(config));
        const auto below = at(bx, by + static_cast<std::int32_t>(r + (6.0 * d)));
        const auto above = at(bx, by - static_cast<std::int32_t>(r + (6.0 * d)) - 1);
        MESSAGE(std::format("{}: the pin at {}, {}; the bubble at {}, {}, radius {:.2f} px; under it {:.0f} {:.0f} "
                            "{:.0f}, over it {:.0f} {:.0f} {:.0f}",
                            gpu::name_of(config), px, py, bx, by, r, below[0], below[1], below[2], above[0], above[1],
                            above[2]));
        CHECK(below[2] < above[2] - 4.0);
        CHECK(above[2] <= background[2]);
        // the label at the pin's place keeps clear of it: not placed
        CHECK(labels->drawn().empty());
        renderer->reset();
    }
    // Without the markers, the label stands at the pin's place; and nothing of the markers is drawn.
    {
        auto output =
            gpu::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, configs.front());
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        for (int f = 0; f < 2; ++f) {
            (*builder)->set_inputs({.camera = camera,
                                    .seconds = seconds,
                                    .scene = &world,
                                    .labels = label_set->get(),
                                    .markers = nullptr});
            seconds += 0.016;
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
        }
        (*renderer)->drain();
        CHECK(markers->draws().empty());
        REQUIRE(labels->drawn().size() == 1);
        CHECK(labels->drawn()[0].stand.x == pin_x);
        CHECK(labels->drawn()[0].stand.y == pin_y);
        renderer->reset();
    }
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif
