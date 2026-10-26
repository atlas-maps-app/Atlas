// The sky layer on the host's Vulkan (passes/Sky.cpp): the sun's glow, its disc and the city's glow over the sky's
// gradient, the ground drawn alone at a camera with the horizon in view. By day the sun stands low before the camera:
// its disc's colour at its centre, the glow's radialGradient composited over the sky's gradient at the pixels of a line
// across it, and the gradient alone beyond the circle; at night (with weather) the city's glow over the sky's rows
// above the horizon. Each pixel against the colours recomputed from the SVG's stops, within two levels (the hardware's
// blend and its rounding).
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <string>

using namespace atlas;

namespace {

/// A colour as floats of 0 to 255, with an opacity of 0 to 1.
struct Colour {
    double r = 0;
    double g = 0;
    double b = 0;
    double a = 1;
};

[[nodiscard]] Colour of(std::uint32_t rgb, double opacity) {
    return {.r = static_cast<double>((rgb >> 16U) & 0xFFU),
            .g = static_cast<double>((rgb >> 8U) & 0xFFU),
            .b = static_cast<double>(rgb & 0xFFU),
            .a = opacity};
}

[[nodiscard]] Colour mix(const Colour& p, const Colour& q, double t) {
    return {.r = p.r + ((q.r - p.r) * t),
            .g = p.g + ((q.g - p.g) * t),
            .b = p.b + ((q.b - p.b) * t),
            .a = p.a + ((q.a - p.a) * t)};
}

/// `s` over the opaque `d`.
[[nodiscard]] Colour over(const Colour& s, const Colour& d) {
    return {.r = (s.r * s.a) + (d.r * (1 - s.a)),
            .g = (s.g * s.a) + (d.g * (1 - s.a)),
            .b = (s.b * s.a) + (d.b * (1 - s.a)),
            .a = 1};
}

/// The sky's gradient (render's #sky) at CSS row y: sky_top at y1 to sky_hor at y2, padded.
[[nodiscard]] Colour sky_at(const style::ThemeBlock& th, const passes::GroundFrame& f, double y) {
    const auto rgb = [](const style::Rgb8& c) {
        return (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | std::uint32_t{c.b};
    };
    const double t = f.sky_y2 != f.sky_y1 ? std::clamp((y - f.sky_y1) / (f.sky_y2 - f.sky_y1), 0.0, 1.0)
                                          : (y < f.sky_y1 ? 0.0 : 1.0);
    return mix(of(rgb(th.sky_top), 1), of(rgb(th.sky_horizon), 1), t);
}

/// A stop's opacity as the SVG parser folds it into the colour's alpha.
[[nodiscard]] double folded(float opacity) {
    return static_cast<double>(std::floor((255.0F * opacity) + 0.5F)) / 255.0;
}

/// The largest difference of a channel of pixel (x, y) from `want`, in levels.
[[nodiscard]] double apart(const gpu::Pixels& px, std::uint32_t x, std::uint32_t y, const Colour& want) {
    const std::size_t i = ((static_cast<std::size_t>(y) * px.width) + x) * 4;
    return std::max(
        {std::abs(px.rgba[i] - want.r), std::abs(px.rgba[i + 1] - want.g), std::abs(px.rgba[i + 2] - want.b)});
}

/// The sun's direction `elevation` degrees above the horizon before a camera at `bearing` (Camera's yaw: its forward
/// is (sin yaw, 0, -cos yaw) along the ground).
[[nodiscard]] std::array<double, 3> sun_before(double bearing, double elevation) {
    const double b = pymath::radians(bearing);
    const double e = pymath::radians(elevation);
    return {pymath::sin(b) * pymath::cos(e), pymath::sin(e), -pymath::cos(b) * pymath::cos(e)};
}

} // namespace

TEST_CASE("ground: the sky layer's sun, its glow and the city's glow lie over the sky as their gradients give them") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the sky is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    // the horizon in view, away from eye level (12° down: below 10° the renders draw the eye's sky, which the app's
    // camera never reaches), the sun 3° above the horizon before the camera
    const style::CameraState camera = test::s1_camera(52.3746, 4.8840, 52.0, 78.0, 200.0);
    const double density = camera.density;

    SUBCASE("by day: the glow and its disc") {
        style::ThemeBlock day = test::sunny();
        day.flags |= style::theme_flag::weather;
        day.sun_vector = sun_before(52.0, 3.0);
        const auto d = test::draw_ground(*session, camera, day, dir, "amsterdam");
        ATLAS_REQUIRE(d.has_value(), "the frame was not drawn");
        const passes::GroundFrame& f = d->decided;
        REQUIRE(f.glow);
        CHECK_FALSE(f.overcast);
        CHECK(f.glow_r == 170.0);
        CHECK(f.glow_y < f.horizon);
        const gpu::Pixels& px = d->pixels;
        const auto pixel_of = [&](double css) { return static_cast<std::int64_t>(std::floor(css * density)); };
        // the disc's colour at its centre
        const std::int64_t cx = pixel_of(f.glow_x);
        const std::int64_t cy = pixel_of(f.glow_y);
        const auto width = static_cast<std::int64_t>(px.width);
        const auto height = static_cast<std::int64_t>(px.height);
        REQUIRE((cx >= 0 && cy >= 0 && cx < width && cy < height));
        CHECK(apart(px, static_cast<std::uint32_t>(cx), static_cast<std::uint32_t>(cy), of(0xFFFDF4U, 1)) <= 2.0);
        // along the circle's row, past the disc: the glow over the sky's gradient, then the gradient alone
        const Colour c0 = of(0xFFF8E6U, folded(1.0F));
        const Colour c1 = of(0xFFE9C2U, folded(0.45F));
        const Colour c2 = of(0xFFE0B0U, folded(0.0F));
        std::size_t checked = 0;
        std::size_t wrong = 0;
        std::size_t glowing = 0;
        double worst = 0;
        for (std::int64_t x = 0; x < width; ++x) {
            const double ux = (static_cast<double>(x) + 0.5) / density;
            const double uy = (static_cast<double>(cy) + 0.5) / density;
            const double dist = pymath::hypot(ux - f.glow_x, uy - f.glow_y);
            if (dist < 9.0 + (2.0 / density)) continue;              // the disc and its edge
            if (std::abs(dist - f.glow_r) < 2.0 / density) continue; // the circle's own edge
            const Colour sky = sky_at(day, f, uy);
            Colour want = sky;
            if (dist < f.glow_r) {
                const double t = dist / f.glow_r;
                want = over(t <= 0.25 ? mix(c0, c1, t * 4.0) : mix(c1, c2, (t - 0.25) / 0.75), sky);
                ++glowing;
            }
            const double a = apart(px, static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(cy), want);
            worst = std::max(worst, a);
            if (a > 2.0) ++wrong;
            ++checked;
        }
        MESSAGE("the sun at (" << f.glow_x << ", " << f.glow_y << "), the horizon at row " << f.horizon << ": "
                               << checked << " pixels of its row checked, " << glowing << " under the glow, the worst "
                               << worst << " levels apart");
        CHECK(glowing > 300);
        CHECK(wrong == 0);
    }

    SUBCASE("at night: the city's glow") {
        style::ThemeBlock night = test::night();
        night.flags |= style::theme_flag::weather;
        const auto d = test::draw_ground(*session, camera, night, dir, "amsterdam");
        ATLAS_REQUIRE(d.has_value(), "the frame was not drawn");
        const passes::GroundFrame& f = d->decided;
        REQUIRE(f.city_glow);
        CHECK_FALSE(f.glow);
        const gpu::Pixels& px = d->pixels;
        const Colour g0 = of(0xFF9E5AU, folded(0.0F));
        const Colour g1 = of(0xFF9E5AU, folded(0.28F));
        // a column down the sky, to two px above the horizon
        const auto x = static_cast<std::uint32_t>(px.width / 2);
        std::size_t checked = 0;
        std::size_t wrong = 0;
        double worst = 0;
        for (std::uint32_t y = 0; y < px.height; ++y) {
            const double uy = (static_cast<double>(y) + 0.5) / density;
            if (uy > f.horizon - 2.0) break;
            const double t = f.city_y2 != f.city_y1 ? std::clamp((uy - f.city_y1) / (f.city_y2 - f.city_y1), 0.0, 1.0)
                                                    : (uy < f.city_y1 ? 0.0 : 1.0);
            const Colour want = over(mix(g0, g1, t), sky_at(night, f, uy));
            const double a = apart(px, x, y, want);
            worst = std::max(worst, a);
            if (a > 2.0) ++wrong;
            ++checked;
        }
        MESSAGE("the city's glow from row " << f.city_y1 << " to " << f.city_y2 << ": " << checked
                                            << " pixels of a column checked, the worst " << worst << " levels apart");
        CHECK(checked > 100);
        CHECK(wrong == 0);
    }
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}

#endif
