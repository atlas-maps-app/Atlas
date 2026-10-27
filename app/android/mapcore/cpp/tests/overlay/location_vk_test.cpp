// The location's puck drawn by the core (passes/Overlays.hpp, shaders/overlay/location.slang), from the camera state's
// location, on every configuration and route the host's Vulkan has: the blue dot at the fix, the white ring about it,
// the heading's cone bright on its left edge and clear on its right, the accuracy's circle on the street (each pixel
// well inside it 13 % of the blue over the frame, each well outside untouched, as the frame's own rays meet the
// street), and nothing without the location; the parts decided as the canvas decides them (no circle under 20 dp, no
// puck off the view). With ATLAS_LOCATION_PPM naming a folder, each configuration's frame is written there.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Overlays.hpp"
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
const Rgb blue{46.0, 91.0, 255.0};

/// The blue at opacity `a` over `under`.
Rgb tint_over(double a, const Rgb& under) {
    return {(blue[0] * a) + (under[0] * (1.0 - a)), (blue[1] * a) + (under[1] * (1.0 - a)),
            (blue[2] * a) + (under[2] * (1.0 - a))};
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

/// Where the ray of the pixel whose centre is (x, y) device px meets the street (frame metres), if it does.
std::optional<view::Vec3> street_at(const view::Camera& c, double x, double y) {
    const double kx = ((x / density) - c.cx()) / c.focal();
    const double ky = (c.cy() - (y / density)) / c.focal();
    const view::Vec3 ray{c.forward().x + (kx * c.right().x) + (ky * c.up().x),
                         c.forward().y + (kx * c.right().y) + (ky * c.up().y),
                         c.forward().z + (kx * c.right().z) + (ky * c.up().z)};
    if (!(ray.y < 0.0)) return std::nullopt;
    const double t = -c.eye().y / ray.y;
    return view::Vec3{c.eye().x + (t * ray.x), 0.0, c.eye().z + (t * ray.z)};
}

} // namespace

TEST_CASE("the location's puck drawn: the dot, its ring, the heading's cone and the accuracy on the street") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::Overlays>();
    const passes::Overlays* overlays = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.clear = clear, .steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    const double lat0 = standin::origin_latitude;
    const double lon0 = standin::origin_longitude;
    const view::GroundFrame ground{
        .lat0 = lat0, .lon0 = lon0, .kx = 111320.0 * pymath::cos(pymath::radians(lat0)), .kz = 110540.0};
    Stub world(ground);
    // 300 m from the fix at the frame's target, 100 m of accuracy (about 150 px either way), heading east
    style::CameraState camera = standin::board_view(frame_width, frame_height, density, 46.0, 18.0, 300.0);
    camera.flags = style::camera_flag::rest | style::camera_flag::location | style::camera_flag::heading;
    camera.location = {.latitude = lat0, .longitude = lon0, .accuracy = 100.0F, .heading = 90.0F};
    const view::View view = view_of(camera);
    const auto dot = view.device_proj({0.0, 0.0, 0.0});
    ATLAS_REQUIRE(dot.has_value());
    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    for (const gpu::Configuration& config : configs) {
        auto output =
            gpu::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        const auto draw = [&](const style::CameraState& at) {
            for (int f = 0; f < 2; ++f) {
                (*builder)->set_inputs({.camera = at, .seconds = 1.0 + (0.016 * f), .scene = &world});
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE_OR_RETURN(std::vector<std::uint8_t>{}, report.has_value(),
                                        gpu::name_of(config) << ": " << report.error().text());
            }
            (*renderer)->drain();
            auto pixels = (*output)->read((*output)->latest());
            ATLAS_REQUIRE_OR_RETURN(std::vector<std::uint8_t>{}, pixels.has_value(), pixels.error().text());
            return pixels->rgba;
        };
        const auto rgba = draw(camera);
        ATLAS_REQUIRE(rgba.size() == std::size_t{frame_width} * frame_height * 4);
        if (const std::string out = environment("ATLAS_LOCATION_PPM"); !out.empty()) {
            const gpu::Pixels px{.width = frame_width, .height = frame_height, .rgba = rgba};
            CHECK(gpu::write_ppm(px, std::format("{}/location-{}.ppm", out, gpu::name_of(config))));
        }
        const auto at = [&](std::uint32_t x, std::uint32_t y) {
            const std::size_t p = 4 * ((std::size_t{y} * frame_width) + x);
            return Rgb{static_cast<double>(rgba[p]), static_cast<double>(rgba[p + 1]),
                       static_cast<double>(rgba[p + 2])};
        };
        const auto near = [](const Rgb& a, const Rgb& b, double levels) {
            return std::fabs(a[0] - b[0]) <= levels && std::fabs(a[1] - b[1]) <= levels &&
                   std::fabs(a[2] - b[2]) <= levels;
        };
        CHECK(overlays->parts(0) == (passes::Overlays::accuracy | passes::Overlays::puck));
        CHECK(overlays->location(0).dot[0] == doctest::Approx(dot->x));
        // the blue dot at the fix, the white ring 8.5 dp out
        const auto cx = static_cast<std::uint32_t>(dot->x);
        const auto cy = static_cast<std::uint32_t>(dot->y);
        CHECK_MESSAGE(near(at(cx, cy), blue, 1.0), gpu::name_of(config));
        CHECK_MESSAGE(near(at(cx - 17, cy), Rgb{255.0, 255.0, 255.0}, 1.0), gpu::name_of(config));
        // the cone: its axis toward the heading on screen; 30 dp from its pivot, 25 degrees left of the axis 40 % of
        // the blue, 25 degrees right of it none, over the accuracy's 13 %
        const auto turn = static_cast<double>(overlays->location(0).dot[3]);
        const auto cone_at = [&](double off) {
            const double a = turn + off;
            const double x = dot->x + (30.0 * density * pymath::sin(a));
            const double y = dot->y + (2.0 * density) - (30.0 * density * pymath::cos(a));
            return at(static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y));
        };
        const double deg = pymath::pi / 180.0;
        const Rgb under = tint_over(0.13, background);
        CHECK_MESSAGE(near(cone_at(-25.0 * deg), tint_over(0.4, under), 3.0), gpu::name_of(config));
        CHECK_MESSAGE(near(cone_at(25.0 * deg), under, 2.0), gpu::name_of(config));
        // the accuracy: beyond the puck's box, each pixel well inside the circle on the street 13 % of the blue over
        // the frame, each well outside it the frame's own colour
        std::size_t inside = 0;
        std::size_t outside = 0;
        std::size_t wrong = 0;
        for (std::uint32_t y = 0; y < frame_height; ++y) {
            for (std::uint32_t x = 0; x < frame_width; ++x) {
                const double px = x + 0.5;
                const double py = y + 0.5;
                if (std::fabs(px - dot->x) < 46.0 * density && std::fabs(py - dot->y) < 46.0 * density) continue;
                const auto hit = street_at(view.camera(), px, py);
                const auto next = street_at(view.camera(), px + 1.0, py + 1.0);
                Rgb want = background;
                if (hit && next) {
                    const double d = pymath::hypot(hit->x, hit->z);
                    const double step = pymath::hypot(next->x - hit->x, next->z - hit->z); // metres a pixel or so
                    if (std::fabs(d - 100.0) < 3.0 * step) continue;                       // the edge and its line
                    if (d < 100.0) {
                        want = under;
                        ++inside;
                    } else {
                        ++outside;
                    }
                } else {
                    ++outside;
                }
                if (!near(at(x, y), want, 2.0)) ++wrong;
            }
        }
        MESSAGE(std::format("{}: the dot at {:.1f}, {:.1f}; {} pixels inside the accuracy, {} outside it",
                            gpu::name_of(config), dot->x, dot->y, inside, outside));
        CHECK(inside > 10000);
        CHECK(outside > 10000);
        CHECK(wrong == 0);
        // without the location, nothing
        style::CameraState plain = camera;
        plain.flags = style::camera_flag::rest;
        plain.location = {};
        const auto bare = draw(plain);
        CHECK(overlays->parts(0) == 0);
        std::size_t touched = 0;
        for (std::size_t p = 0; p + 3 < bare.size(); p += 4) {
            const Rgb c{static_cast<double>(bare[p]), static_cast<double>(bare[p + 1]),
                        static_cast<double>(bare[p + 2])};
            if (!near(c, background, 1.0)) ++touched;
        }
        CHECK(touched == 0);
        renderer->reset();
    }
    // the parts as the canvas decides them: a fix of 5 m (8 dp at its depth) has no circle; one far off the view no
    // puck either; and the cone only with a heading
    {
        auto output =
            gpu::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, configs.front());
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        const auto parts = [&](const style::CameraState& at) {
            for (int f = 0; f < 2; ++f) {
                (*builder)->set_inputs({.camera = at, .seconds = 2.0 + (0.016 * f), .scene = &world});
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE_OR_RETURN(0U, report.has_value(), report.error().text());
            }
            (*renderer)->drain();
            return overlays->parts(0) | (overlays->parts(1) << 4U);
        };
        style::CameraState small = camera;
        small.location.accuracy = 5.0F;
        CHECK(parts(small) == (passes::Overlays::puck | (passes::Overlays::puck << 4U)));
        style::CameraState away = camera;
        away.location.latitude = lat0 - 0.01; // 1.1 km behind the target's side of the view, off it
        CHECK(parts(away) == 0U);
        style::CameraState no_heading = camera;
        no_heading.flags &= ~style::camera_flag::heading;
        no_heading.location.heading = 0.0F;
        (void)parts(no_heading);
        CHECK(overlays->location(0).flags[0] == 0U);
        renderer->reset();
    }
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif
