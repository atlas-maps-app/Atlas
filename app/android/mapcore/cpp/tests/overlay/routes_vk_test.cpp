// The app's routes and areas drawn by the core (passes/Routes.hpp, shaders/overlay/route.slang), from a RouteSet in the
// frame's inputs, on every configuration and route the host's Vulkan has, looking straight down: a route's fill in its
// colour after the distance travelled and faded before it, its white casing beside the fill and nothing beyond it, its
// pieces meeting at each point; a dashed route's dashes in its colour and the casing's caps across their gaps; an
// area's fill over the frame and its line round it; and the route's chip halfway along the part of it on the view, its
// texels on whole pixels. With ATLAS_ROUTES_PPM naming a folder, each configuration's frame is written there.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Routes.hpp"
#include "routes/Routes.hpp"
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

/// A world without tiles: only its ground frame.
class Stub final : public frame::FrameScene {
public:
    explicit Stub(view::GroundFrame frame) noexcept : frame_(frame) {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return nullptr; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }
    [[nodiscard]] std::optional<view::GroundFrame> ground_frame() const noexcept override { return frame_; }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return {}; }

private:
    view::GroundFrame frame_;
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
constexpr double density = 2.0;

/// A colour, 0 to 255 a channel.
using Rgb = std::array<double, 3>;

const Rgb background{std::round(0.25 * 255.0), std::round(0.5 * 255.0), std::round(0.75 * 255.0)};

Rgb rgb(std::uint32_t argb) {
    return {static_cast<double>((argb >> 16U) & 0xFFU), static_cast<double>((argb >> 8U) & 0xFFU),
            static_cast<double>(argb & 0xFFU)};
}

/// `argb` at its own opacity over `under`.
Rgb over(std::uint32_t argb, const Rgb& under) {
    const double a = static_cast<double>(argb >> 24U) / 255.0;
    const Rgb c = rgb(argb);
    return {(c[0] * a) + (under[0] * (1.0 - a)), (c[1] * a) + (under[1] * (1.0 - a)),
            (c[2] * a) + (under[2] * (1.0 - a))};
}

/// The frame's camera as FrameBuilder::derive makes it without a ladder (the target at the frame's origin).
view::View view_of(const style::CameraState& cam) {
    const view::CameraSpec spec{.target = {},
                                .yaw = view::bearing_of(cam.bearing),
                                .pitch = view::engine_pitch(cam.pitch),
                                .dist = cam.distance,
                                .fov = view::field_of_view,
                                .width = static_cast<double>(cam.width_dp),
                                .height = static_cast<double>(cam.height_dp),
                                .cx = std::nullopt,
                                .cy = cam.principal_y,
                                .near = view::near_plane};
    return {spec, {.density = cam.density, .width_px = cam.width_px, .height_px = cam.height_px}};
}

constexpr std::uint32_t blue = 0xFF2E'5BFFU;
constexpr std::uint32_t faded = 0xFF8A'9BD9U;
constexpr std::uint32_t green = 0xFF1F'9D55U;
constexpr std::uint32_t white = 0xFFFF'FFFFU;
constexpr std::uint32_t orange = 0xFFE8'612BU;
constexpr std::uint32_t orange_fill = 0x33E8'612BU;

} // namespace

TEST_CASE("the app's routes drawn: fill, casing, the travelled part, dashes, an area and the chip, on the street") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::Routes>();
    const passes::Routes* routes = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.clear = clear, .steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    const double lat0 = standin::origin_latitude;
    const double lon0 = standin::origin_longitude;
    const view::GroundFrame ground{
        .lat0 = lat0, .lon0 = lon0, .kx = 111320.0 * pymath::cos(pymath::radians(lat0)), .kz = 110540.0};
    Stub world(ground);
    // where a frame point (x east, z south, metres) is on the map
    const auto lat_of = [&](double z) { return lat0 - (z / ground.kz); };
    const auto lon_of = [&](double x) { return lon0 + (x / ground.kx); };
    // Straight down from 300 m, north up.
    style::CameraState camera = standin::board_view(frame_width, frame_height, density, 0.0, 0.0, 300.0);
    camera.flags = style::camera_flag::rest;
    const view::View view = view_of(camera);
    const auto at_px = [&](double x, double z) {
        const auto p = view.device_proj({x, 0.0, z});
        return p ? *p : view::Vec2{-1.0, -1.0};
    };
    const double metre_px = at_px(1.0, 0.0).x - at_px(0.0, 0.0).x; // device px a metre, everywhere straight down
    // Route A west to east through the target, a point every 10 m, 6 dp wide, 50 m of it travelled, with a chip;
    // route B 40 m north of it, dashed; an area 20 m round a point south-west of the target.
    routes::Route a{.width = 6.0F, .colour = blue, .faded = faded, .casing = white, .travelled = 50.0, .chip = 0};
    for (int k = -10; k <= 10; ++k) {
        const double x = 10.0 * k;
        a.points.push_back({lat_of(0.0), lon_of(x), x + 100.0});
    }
    routes::Route b{.points = {{lat_of(-40.0), lon_of(-100.0), 0.0}, {lat_of(-40.0), lon_of(100.0), 200.0}},
                    .width = 6.0F,
                    .colour = green,
                    .faded = green,
                    .casing = white,
                    .dashed = true};
    const routes::Area area{
        .latitude = lat_of(50.0), .longitude = lon_of(-60.0), .radius = 20.0F, .fill = orange_fill, .stroke = orange};
    const std::uint32_t yellow = 0xFF00'FFFFU; // R and G, premultiplied ABGR
    routes::Image chip{
        .width = 10, .height = 6, .pixels = std::vector<std::uint32_t>(60, yellow), .anchor_x = 5, .anchor_y = 3};
    auto set = routes::RouteSet::make({area}, {a, b}, {chip}, static_cast<float>(density), 1);
    ATLAS_REQUIRE(set.has_value(), routes::describe(set.error()));
    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    double seconds = 1.0;
    for (const gpu::Configuration& config : configs) {
        auto output =
            gpu::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        for (int f = 0; f < 2; ++f) {
            (*builder)->set_inputs({.camera = camera, .seconds = seconds, .scene = &world, .routes = set->get()});
            seconds += 0.016;
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), gpu::name_of(config) << ": " << report.error().text());
        }
        (*renderer)->drain();
        auto pixels = (*output)->read((*output)->latest());
        ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
        if (const std::string out = environment("ATLAS_ROUTES_PPM"); !out.empty()) {
            CHECK(gpu::write_ppm(*pixels, std::format("{}/routes-{}.ppm", out, gpu::name_of(config))));
        }
        const auto& rgba = pixels->rgba;
        // the pixel whose centre is nearest the street point (x, z), `down` px south of it
        const auto at = [&](double x, double z, double down = 0.0) {
            const view::Vec2 p = at_px(x, z);
            const auto px = static_cast<std::size_t>(std::floor(p.x));
            const auto py = static_cast<std::size_t>(std::floor(p.y + down));
            const std::size_t i = 4 * ((py * frame_width) + px);
            return Rgb{static_cast<double>(rgba[i]), static_cast<double>(rgba[i + 1]),
                       static_cast<double>(rgba[i + 2])};
        };
        const auto near = [](const Rgb& x, const Rgb& y, double levels) {
            return std::fabs(x[0] - y[0]) <= levels && std::fabs(x[1] - y[1]) <= levels &&
                   std::fabs(x[2] - y[2]) <= levels;
        };
        const std::string name = gpu::name_of(config);
        MESSAGE(std::format("{}: {:.3f} px a metre, {} draws", name, metre_px, routes->draws().size()));
        // A: its colour past the distance travelled, faded before it, at a point and at a meeting of two pieces
        CHECK_MESSAGE(near(at(60.0, 0.0), rgb(blue), 1.0), name);
        CHECK_MESSAGE(near(at(0.0, 0.0, 3.0), rgb(blue), 1.0), name);
        CHECK_MESSAGE(near(at(-80.0, 0.0), rgb(faded), 1.0), name);
        // its fill 3 dp either side, its casing 1.6 dp beyond, then the frame
        CHECK_MESSAGE(near(at(60.0, 0.0, 2.0 * density), rgb(blue), 1.0), name);
        CHECK_MESSAGE(near(at(60.0, 0.0, 3.8 * density), rgb(white), 1.0), name);
        CHECK_MESSAGE(near(at(60.0, 0.0, -3.8 * density), rgb(white), 1.0), name);
        CHECK_MESSAGE(near(at(60.0, 0.0, 6.0 * density), background, 1.0), name);
        // B: a dash's middle in its colour; a gap's middle the casing, whose caps reach across it
        const double period = 6.0 * 2.6 * density / metre_px; // metres
        const double dash = 6.0 * 1.3 * density / metre_px;
        CHECK_MESSAGE(near(at(-100.0 + (3.0 * period) + (dash / 2.0), -40.0), rgb(green), 1.0), name);
        CHECK_MESSAGE(near(at(-100.0 + (3.0 * period) + dash + ((period - dash) / 2.0), -40.0), rgb(white), 1.0), name);
        CHECK_MESSAGE(near(at(-100.0 + (3.0 * period) + (dash / 2.0), -40.0, 7.0 * density), background, 1.0), name);
        // the area: its fill over the frame, its line at its radius, the frame beyond
        CHECK_MESSAGE(near(at(-60.0, 50.0), over(orange_fill, background), 1.0), name);
        CHECK_MESSAGE(near(at(-60.0, 50.0, -(20.0 * metre_px)), rgb(orange), 2.0), name);
        CHECK_MESSAGE(near(at(-60.0, 50.0, -(20.0 * metre_px) - (4.0 * density)), background, 1.0), name);
        // the chip, halfway along the part of A on the view 24 dp in from its edges: at the target, its texels there
        const auto draws = routes->draws();
        ATLAS_REQUIRE(!draws.empty());
        const auto& c = draws.back();
        CHECK(c.look[3] == passes::Routes::chip);
        const view::Vec2 middle = at_px(0.0, 0.0);
        CHECK(c.box[0] == static_cast<float>(std::floor(middle.x + 0.5) - 5.0));
        CHECK(c.box[1] == static_cast<float>(std::floor(middle.y + 0.5) - 3.0));
        const std::size_t ci =
            4 * ((static_cast<std::size_t>(c.box[1] + 2.0F) * frame_width) + static_cast<std::size_t>(c.box[0] + 4.0F));
        CHECK(Rgb{static_cast<double>(rgba[ci]), static_cast<double>(rgba[ci + 1]),
                  static_cast<double>(rgba[ci + 2])} == Rgb{255.0, 255.0, 0.0});
        // drawn: the area, A's casing and fill pieces (20 each), B's two, then the chip
        CHECK(draws.size() == 1 + 40 + 2 + 1);
        renderer->reset();
    }
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif
