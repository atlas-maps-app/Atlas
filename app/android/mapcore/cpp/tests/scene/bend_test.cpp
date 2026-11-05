// The bend (ENGINE.md step 5, "The bend"; its step 7): view/Bend.hpp's weights, the frame camera's drop (nothing up to
// 18 km, bit for bit), the sphere's float trigonometry and points (shaders/shared/sphere.slang, its own text here)
// against scene/WorldBend.cpp's binary64, the walk's bent boxes and horizon, and the sweep: from 1 km to 60,000 km in
// 120 steps at Amsterdam, Tokyo, the open sea and 85° S, no step moves a ground point on the screen more than the zoom
// alone does.
#include "base/hlsl.hpp"
#include "frame/FrameBlock.hpp"
#include "scene/WorldBend.hpp"
#include "view/Bend.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <format>
#include <numbers>
#include <optional>
#include <vector>

namespace atlas::hlsl {
#include "shaders/shared/sphere.slang"
} // namespace atlas::hlsl

namespace {

using namespace atlas;

constexpr double radian = std::numbers::pi / 180.0;
constexpr double r_earth = view::earth_radius;

/// The boards' frame: 411 by 914 dp at 2.625.
constexpr view::Device device{.density = 2.625, .width_px = 1079, .height_px = 2399, .quarter_turns = 0};

[[nodiscard]] view::CameraSpec spec_of(double pitch, double dist, double yaw = 0.0) {
    return {.target = {0, 0, 0}, .yaw = yaw, .pitch = pitch, .dist = dist, .width = 411.0, .height = 914.0};
}

/// The frame centred on a place, as scene/World.cpp's where no pack is.
[[nodiscard]] view::GroundFrame centred(double lat, double lon) {
    return {.lat0 = lat, .lon0 = lon, .kx = 111'320.0 * std::cos(lat * radian), .kz = 110'540.0};
}

/// The point `metres` along the ground from (lat, lon) toward bearing `bearing` (degrees from north): the great
/// circle's.
[[nodiscard]] std::array<double, 2> destination(double lat, double lon, double bearing, double metres) {
    const double d = metres / r_earth;
    const double p = lat * radian;
    const double t = bearing * radian;
    const double p2 = std::asin((std::sin(p) * std::cos(d)) + (std::cos(p) * std::sin(d) * std::cos(t)));
    const double l2 = (lon * radian) +
                      std::atan2(std::sin(t) * std::sin(d) * std::cos(p), std::cos(d) - (std::sin(p) * std::sin(p2)));
    return {p2 / radian, std::remainder(l2 / radian, 360.0)};
}

/// world.slang's normal of the bent ground at a point, in double: the dropped ground's gradient blended to the
/// sphere's by w₂.
[[nodiscard]] view::Vec3 normal_of(const scene::WorldBend& b, const view::GroundFrame& f, double lat, double lon,
                                   double shift) {
    const double x = f.x(lat, lon + shift);
    const double z = f.z(lat, lon);
    view::Vec3 n{2.0 * b.drop * (x - b.target.x), 1.0, 2.0 * b.drop * (z - b.target.z)};
    if (b.sphere > 0.0) {
        const view::Vec3 o = scene::sphere_offset(b, lat, lon);
        const view::Vec3 s{o.x / r_earth, (o.y + r_earth) / r_earth, o.z / r_earth};
        n = {n.x + ((s.x - n.x) * b.sphere), n.y + ((s.y - n.y) * b.sphere), n.z + ((s.z - n.z) * b.sphere)};
    }
    return n;
}

} // namespace

TEST_CASE("the bend's weights: nothing up to 18 km, the drop whole from 150 km and the sphere from 600 km, eased in "
          "log D") {
    CHECK(view::drop_weight(0.0) == 0.0);
    CHECK(view::drop_weight(18'000.0) == 0.0);
    CHECK(view::drop_of(18'000.0) == 0.0);
    CHECK(view::drop_weight(150'000.0) == 1.0);
    CHECK(view::drop_weight(1e9) == 1.0);
    CHECK(view::sphere_weight(150'000.0) == 0.0);
    CHECK(view::sphere_weight(600'000.0) == 1.0);
    CHECK(view::drop_of(150'000.0) == doctest::Approx(1.0 / (2.0 * r_earth)));
    // halfway in log D, halfway in weight; no kink at the knots
    CHECK(view::drop_weight(std::sqrt(18'000.0 * 150'000.0)) == doctest::Approx(0.5));
    CHECK(view::sphere_weight(300'000.0) == doctest::Approx(0.5));
    CHECK(view::drop_weight(18'018.0) < 1e-5);
    CHECK(view::sphere_weight(599'400.0) > 1.0 - 1e-5);
    double last = 0;
    for (int i = 0; i <= 1000; ++i) {
        const double d = 10'000.0 * std::pow(100.0, i / 1000.0);
        const double w = view::drop_weight(d) + view::sphere_weight(d);
        CHECK(w >= last);
        last = w;
    }
}

TEST_CASE("the bend: up to 18 km the frame's camera and every point are as before, bit for bit; beyond, the block "
          "carries the drop and the target") {
    for (const double dist : {500.0, 5'000.0, 18'000.0}) {
        const view::View v(spec_of(60.0, dist), device);
        CHECK(v.drop() == 0.0);
        const view::Vec3 p{1234.5, -3.25, -98'765.4321};
        const view::Vec3 q = v.bent(p);
        CHECK(std::bit_cast<std::uint64_t>(q.y) == std::bit_cast<std::uint64_t>(p.y));
        const shaders::FrameCamera b = v.block(false, false, 7);
        CHECK(std::bit_cast<std::uint32_t>(b.device.w) == 0U);
        CHECK(b.flags.z == 0U);
        CHECK(b.flags.w == 0U);
        // camera.slang's own bend: the point back unchanged
        const hlsl::float3 e = {1234.5F, -3.25F, -98'765.43F};
        const hlsl::float3 bent = shaders::bent(b, e);
        CHECK(std::bit_cast<std::uint32_t>(bent.y) == std::bit_cast<std::uint32_t>(e.y));
    }
    const view::View v(spec_of(60.0, 50'000.0), device);
    CHECK(v.drop() == view::drop_of(50'000.0));
    CHECK(v.drop() > 0.0);
    const shaders::FrameCamera b = v.block(false, false, 7);
    CHECK(b.device.w == static_cast<float>(v.drop()));
    const view::Vec3& eye = v.camera().eye();
    CHECK(hlsl::asfloat(b.flags.z) == static_cast<float>(0.0 - eye.x));
    CHECK(hlsl::asfloat(b.flags.w) == static_cast<float>(0.0 - eye.z));
    // a point 100 km from the target drops by w₁·|Δ|²/2R, the GPU's as the CPU's
    const view::Vec3 p{100'000.0, 0.0, 0.0};
    const double drop = view::drop_weight(50'000.0) * 1e10 / (2.0 * r_earth);
    CHECK(v.bent(p).y == doctest::Approx(-drop));
    const hlsl::float3 g = {static_cast<float>(p.x - eye.x), static_cast<float>(-eye.y), static_cast<float>(-eye.z)};
    CHECK(static_cast<double>(shaders::bent(b, g).y) == doctest::Approx(-eye.y - drop).epsilon(1e-6));
}

TEST_CASE("the sphere's float sines and cosines are within a few float ulps of binary64's") {
    double worst = 0;
    double worst_near = 0;
    for (int i = -40'000; i <= 40'000; ++i) {
        const float x = static_cast<float>(i) * 1e-4F;
        const hlsl::float2 sc = hlsl::sin_cos(x);
        const double xd = static_cast<double>(x);
        worst = std::max({worst, std::abs(static_cast<double>(sc.x) - std::sin(xd)),
                          std::abs(static_cast<double>(sc.y) - std::cos(xd))});
        if (std::abs(xd) < 0.01 && xd != 0.0) {
            worst_near = std::max(worst_near, std::abs((static_cast<double>(sc.x) - std::sin(xd)) / std::sin(xd)));
        }
    }
    MESSAGE(std::format("sin_cos: {:.3g} off at most, {:.3g} of sin x within 0.01 of 0", worst, worst_near));
    CHECK(worst < 2.5e-7);
    CHECK(worst_near < 2.5e-7);
}

TEST_CASE("the sphere's points in float are where binary64 puts them, near the target as far off") {
    for (const double lat : {0.0, 52.37, 84.9, -85.0, -89.5}) {
        const double lon = 4.89;
        scene::WorldBend b;
        b.lat = lat;
        b.lon = lon;
        b.sin_lat = std::sin(lat * radian);
        b.cos_lat = std::cos(lat * radian);
        double far_worst = 0;
        double near_worst = 0;
        for (int i = -10; i <= 10; ++i) {
            for (int j = -10; j <= 10; ++j) {
                for (const double step : {17.0, 0.01}) {
                    const double dlat = i * step / 2.0;
                    const double dlon = j * step;
                    if (std::abs(lat + dlat) > 90.0) continue;
                    const view::Vec3 o = scene::sphere_offset(b, lat + dlat, lon + dlon);
                    const hlsl::float3 f = hlsl::on_sphere(
                        static_cast<float>(dlat * radian), static_cast<float>(dlon * radian),
                        static_cast<float>(b.sin_lat), static_cast<float>(b.cos_lat), static_cast<float>(r_earth));
                    const double off = std::hypot(static_cast<double>(f.x) - o.x, static_cast<double>(f.y) - o.y,
                                                  static_cast<double>(f.z) - o.z);
                    double& worst = step > 1.0 ? far_worst : near_worst;
                    worst = std::max(worst, off);
                }
            }
        }
        MESSAGE(std::format("at {}°: {:.3g} m off at most, {:.3g} m within 0.1°", lat, far_worst, near_worst));
        CHECK(far_worst < 4.0);
        CHECK(near_worst < 0.01);
    }
}

TEST_CASE("the walk's boxes hold the ground the world pass draws, and the horizon passes over what lies behind it") {
    struct Case {
        double pitch;
        double dist;
    };
    const double lat = 52.37;
    const double lon = 4.89;
    const view::GroundFrame f = centred(lat, lon);
    for (const Case c : {Case{60.0, 50'000.0}, Case{45.0, 300'000.0}, Case{89.0, 3'000'000.0}}) {
        const view::View v(spec_of(c.pitch, c.dist), device);
        const scene::WorldBend b = scene::world_bend(v, f);
        CHECK(b.lat == doctest::Approx(lat));
        CHECK(b.lon == doctest::Approx(lon));
        for (const double size : {0.2, 2.0, 11.25}) {
            const scene::DegreeBox g{.north = lat + size, .south = lat - size, .west = lon - size, .east = lon + size};
            const scene::Box box = scene::world_box(b, f, g);
            const double shift = scene::wrap_shift(b, lon);
            int outside = 0;
            for (int i = 0; i <= 16; ++i) {
                for (int j = 0; j <= 16; ++j) {
                    const view::Vec3 p = scene::world_point(b, f, g.north + ((g.south - g.north) * i / 16.0),
                                                            g.west + ((g.east - g.west) * j / 16.0), shift);
                    if (p.x < box.x0 || p.x > box.x1 || p.y < box.y0 || p.y > box.y1 || p.z < box.z0 || p.z > box.z1) {
                        ++outside;
                    }
                }
            }
            CHECK(outside == 0);
        }
    }
    // from 20,000 km over (0°, 0°) the horizon lies 76° off: a box about 70° east in front, one about 100° behind
    const view::GroundFrame f0 = centred(0.0, 0.0);
    const view::View v(spec_of(90.0, 20'000'000.0), device);
    const scene::WorldBend b = scene::world_bend(v, f0);
    CHECK(b.sphere == 1.0);
    CHECK(scene::before_horizon(b, {.north = 2.0, .south = -2.0, .west = -2.0, .east = 2.0}));
    CHECK(scene::before_horizon(b, {.north = 2.0, .south = -2.0, .west = 68.0, .east = 72.0}));
    CHECK_FALSE(scene::before_horizon(b, {.north = 2.0, .south = -2.0, .west = 98.0, .east = 102.0}));
    CHECK_FALSE(scene::before_horizon(b, {.north = 2.0, .south = -2.0, .west = 178.0, .east = 182.0}));
    // across the antimeridian a tile is placed within 180° of the target, not a world away
    const view::GroundFrame fe = centred(10.0, 179.5);
    const view::View ve(spec_of(60.0, 50'000.0), device);
    const scene::WorldBend be = scene::world_bend(ve, fe);
    CHECK(scene::wrap_shift(be, -179.0) == 360.0);
    CHECK(scene::wrap_shift(be, 179.0) == 0.0);
    const scene::Box east = scene::world_box(be, fe, {.north = 10.5, .south = 9.5, .west = -180.0, .east = -179.0});
    CHECK(east.x0 == doctest::Approx(0.5 * fe.kx));
}

TEST_CASE("the sweep: from 1 km to 60,000 km in 120 steps no ground point moves on the screen more than the zoom alone "
          "moves it, at Amsterdam, Tokyo, the open sea and 85° S") {
    struct Place {
        const char* name;
        double lat;
        double lon;
    };
    constexpr int steps = 120;
    const double k = std::pow(60'000.0, 1.0 / steps); // the zoom's factor a step
    for (const Place place : {Place{"Amsterdam", 52.37, 4.89}, Place{"Tokyo", 35.68, 139.69},
                              Place{"the open sea", 35.0, -40.0}, Place{"85° S", -85.0, 0.0}}) {
        const view::GroundFrame f = centred(place.lat, place.lon);
        std::vector<std::array<double, 2>> points;
        for (const double metres : {300.0, 3'000.0, 30'000.0, 300'000.0, 1'000'000.0, 3'000'000.0, 6'000'000.0}) {
            for (int a = 0; a < 8; ++a) points.push_back(destination(place.lat, place.lon, 45.0 * a + 10.0, metres));
        }
        std::vector<std::optional<view::Vec2>> last(points.size());
        double worst = 0;
        double worst_at = 0;
        int compared = 0;
        for (int i = 0; i <= steps; ++i) {
            const double dist = 1'000.0 * std::pow(k, i);
            const view::View v(spec_of(90.0, dist), device);
            const view::Camera& cam = v.camera();
            const scene::WorldBend b = scene::world_bend(v, f);
            const view::Vec2 centre{cam.cx(), cam.cy()};
            for (std::size_t p = 0; p < points.size(); ++p) {
                const auto [lat, lon] = points[p];
                const double shift = scene::wrap_shift(b, lon);
                const view::Vec3 w = scene::world_point(b, f, lat, lon, shift);
                const view::Vec3 n = normal_of(b, f, lat, lon, shift);
                const view::Vec3& e = cam.eye();
                const bool facing = (n.x * (w.x - e.x)) + (n.y * (w.y - e.y)) + (n.z * (w.z - e.z)) < 0.0;
                std::optional<view::Vec2> s = facing ? cam.proj(w) : std::nullopt;
                if (s &&
                    (s->x < -100.0 || s->x > cam.width() + 100.0 || s->y < -100.0 || s->y > cam.height() + 100.0)) {
                    s.reset();
                }
                if (s && last[p]) {
                    const double moved = std::hypot(s->x - last[p]->x, s->y - last[p]->y);
                    const double r = std::hypot(last[p]->x - centre.x, last[p]->y - centre.y);
                    const double zoom = (1.0 - (1.0 / k)) * r;
                    const double ratio = moved / (zoom + 0.5);
                    ++compared;
                    if (ratio > worst) {
                        worst = ratio;
                        worst_at = dist;
                    }
                }
                last[p] = s;
            }
        }
        MESSAGE(std::format("{}: {} steps of points compared, the most a step moved one {:.3f} times the zoom's (and "
                            "half a px), at {:.0f} m",
                            place.name, compared, worst, worst_at));
        CHECK(compared > 400);
        CHECK(worst <= 1.25);
    }
}
