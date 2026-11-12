// The sky layer on the host's Vulkan (passes/Sky.cpp): the sun's glow, its disc and the city's glow over the sky's
// gradient, the ground drawn alone at a camera with the horizon in view. By day the sun stands low before the camera:
// its disc's colour at its centre, the glow's radialGradient composited over the sky's gradient at the pixels of a line
// across it, and the gradient alone beyond the circle; at night (with weather) the city's glow over the sky's rows
// above the horizon. Each pixel against the colours recomputed from the SVG's stops, within two levels (the hardware's
// blend and its rounding).
//
// And the clouds with their shadows (fx/Clouds.hpp), in full mode with the fields bound, by day, under an overcast sky
// and at night, the sun behind the camera: over the sky's rows above the horizon each pixel is cloudf's result over
// cloudsh2's over the frame drawn without them (at night the city's glow over both), each from its table at the red
// the clouds' field gives its matrix there, within a level a blend; no pixel below the filters' region changes; and in
// structural mode, whose references leave out an element that is all filter, nothing is drawn.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "fx/Clouds.hpp"
#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <format>
#include <memory>
#include <string>
#include <vector>

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

TEST_CASE("ground: the sky layer's clouds and their shadows are their filters' tables at the reds of the clouds' field") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the clouds are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    // the horizon in view (12° down), the sun behind the camera: no glow lies over the clouds by day
    const style::CameraState camera = test::s1_camera(52.3746, 4.8840, 52.0, 78.0, 200.0);
    const double density = camera.density;
    style::ThemeBlock day = test::sunny();
    day.sun_vector = sun_before(232.0, 30.0);
    style::ThemeBlock grey = day;
    grey.flags |= style::theme_flag::overcast;
    grey.sky_k = 0.6;
    struct Moment {
        const char* name;
        style::ThemeBlock theme;
        bool night;
    };
    const std::array<Moment, 3> moments{
        {{"day", day, false}, {"overcast", grey, false}, {"night", test::night_weather(), true}}};
    for (const Moment& m : moments) {
        CAPTURE(std::string(m.name));
        passes::GroundFrame f;
        bool filtered = false;
        const auto with = test::draw_ground(
            *session, camera, m.theme, dir, "amsterdam",
            [&](passes::Ground& g, const frame::FrameState& state) {
                f = g.decided();
                filtered = (state.block.info.x & shaders::FRAME_FIELDS) != 0U;
            },
            40, {}, frame::Mode::full, fields, settle);
        ATLAS_REQUIRE(with.has_value(), "the frame was not drawn with its clouds");
        for (const std::string& message : session->take_messages()) CHECK_MESSAGE(false, message);
        const auto without = test::draw_ground(
            *session, camera, m.theme, dir, "amsterdam", {}, 40, [](passes::Ground& g) { g.draw_clouds(false); },
            frame::Mode::full, fields, settle);
        ATLAS_REQUIRE(without.has_value(), "the frame was not drawn without its clouds");
        REQUIRE(filtered);
        REQUIRE(f.clouds);
        CHECK_FALSE(f.glow);
        CHECK(f.city_glow == m.night);
        const gpu::Pixels& a = with->pixels;
        const gpu::Pixels& b = without->pixels;
        const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
        ATLAS_REQUIRE(latest != nullptr, "no fields");
        REQUIRE(latest->size().width == a.width);
        REQUIRE(latest->size().height == a.height);
        const std::array<fx::Argb, 256> shadow_table = fx::cloud_table(f.cloud_shadow_matrix);
        const std::array<fx::Argb, 256> cloud_table = fx::cloud_table(f.cloud_matrix);
        CHECK(f.cloud_offset == fx::cloud_offset(static_cast<float>(density)));
        // the filters' region: the rows above ceil(int(hy + 2) · density)
        CHECK(f.cloud_rows == static_cast<std::uint32_t>(std::ceil(std::trunc(f.horizon + 2.0) * density)));
        const auto level = [](fx::Argb p, std::uint32_t shift) { return static_cast<double>((p >> shift) & 0xFFU); };
        // a premultiplied sRGB pixel over an opaque colour
        const auto under = [&](fx::Argb p, const Colour& d) {
            const double keep = 1.0 - (level(p, 24) / 255.0);
            return Colour{.r = level(p, 16) + (d.r * keep), .g = level(p, 8) + (d.g * keep), .b = level(p, 0) + (d.b * keep)};
        };
        const Colour g0 = of(0xFF9E5AU, folded(0.0F));
        const Colour g1 = of(0xFF9E5AU, folded(0.28F));
        std::size_t sky = 0;
        std::size_t clouded = 0;
        std::size_t shaded = 0;
        std::size_t clear = 0;
        std::size_t wrong = 0;
        std::size_t beyond = 0;
        double worst = 0;
        std::string first;
        for (std::uint32_t y = 0; y < a.height; ++y) {
            const double uy = (static_cast<double>(y) + 0.5) / density;
            for (std::uint32_t x = 0; x < a.width; ++x) {
                const std::size_t i = ((static_cast<std::size_t>(y) * a.width) + x) * 4;
                if (y >= f.cloud_rows) {
                    // below the filters' region nothing changes
                    if (a.rgba[i] != b.rgba[i] || a.rgba[i + 1] != b.rgba[i + 1] || a.rgba[i + 2] != b.rgba[i + 2]) {
                        ++beyond;
                    }
                    continue;
                }
                if (uy > f.horizon - 2.0) continue; // the horizon's own rows, where the ground begins
                ++sky;
                const fx::CloudReds reds = fx::cloud_reds(*latest, f.cloud_offset, x, y);
                const fx::Argb shadow = shadow_table[reds.shadow];
                const fx::Argb cloud = cloud_table[reds.cloud];
                if ((cloud >> 24U) != 0U) ++clouded;
                if ((shadow >> 24U) != 0U && (cloud >> 24U) != 255U) ++shaded;
                if ((cloud >> 24U) == 0U && (shadow >> 24U) == 0U) ++clear;
                Colour want;
                double slack = 1.0;
                if (m.night) {
                    // the city's glow lies over the clouds: from the sky's gradient up
                    const double t = f.city_y2 != f.city_y1
                                         ? std::clamp((uy - f.city_y1) / (f.city_y2 - f.city_y1), 0.0, 1.0)
                                         : (uy < f.city_y1 ? 0.0 : 1.0);
                    want = over(mix(g0, g1, t), under(cloud, under(shadow, sky_at(m.theme, f, uy))));
                    slack = 2.0;
                } else {
                    const Colour bare{.r = static_cast<double>(b.rgba[i]),
                                      .g = static_cast<double>(b.rgba[i + 1]),
                                      .b = static_cast<double>(b.rgba[i + 2])};
                    want = under(cloud, under(shadow, bare));
                }
                const double d = apart(a, x, y, want);
                worst = std::max(worst, d);
                if (d <= slack) continue;
                if (wrong == 0) {
                    first = std::format("pixel {} {}: {} {} {}, expected {:.1f} {:.1f} {:.1f} (reds {} and {})", x, y,
                                        int(a.rgba[i]), int(a.rgba[i + 1]), int(a.rgba[i + 2]), want.r, want.g, want.b,
                                        reds.shadow, reds.cloud);
                }
                ++wrong;
            }
        }
        MESSAGE(std::format("the clouds, {}: horizon {:.3f}, cloudf {} {} {} {} {}, cloudsh2 {} {} {} {} {}", m.name,
                            f.horizon, f.cloud_matrix.red, f.cloud_matrix.green, f.cloud_matrix.blue, f.cloud_matrix.k,
                            f.cloud_matrix.offset, f.cloud_shadow_matrix.red, f.cloud_shadow_matrix.green,
                            f.cloud_shadow_matrix.blue, f.cloud_shadow_matrix.k, f.cloud_shadow_matrix.offset));
        MESSAGE(std::format("the clouds, {}: the region's {} rows, the offset {} rows and {}/2^24; {} pixels of sky, {} "
                            "under a cloud, {} under a shadow alone or through a cloud, {} clear; the worst {:.2f} "
                            "levels apart, {} not their composite, {} changed below the region",
                            m.name, f.cloud_rows, f.cloud_offset.rows, f.cloud_offset.above, sky, clouded, shaded,
                            clear, worst, wrong, beyond));
        CHECK(sky > 100'000U);
        CHECK(clouded > 10'000U);
        CHECK(shaded > 1'000U);
        if (std::string_view(m.name) == "day") CHECK(clear > 10'000U);
        CHECK_MESSAGE(wrong == 0, first);
        CHECK(beyond == 0);
        // structural mode draws none: the frame with them is the frame without
        const auto plain = test::draw_ground(*session, camera, m.theme, dir, "amsterdam",
                                             [&](passes::Ground& g, const frame::FrameState&) {
                                                 CHECK_FALSE(g.decided().clouds);
                                             },
                                             40, {}, frame::Mode::structural, fields, settle);
        ATLAS_REQUIRE(plain.has_value(), "the frame was not drawn in structural mode");
    }
    for (const std::string& message : session->take_messages()) CHECK_MESSAGE(false, message);
}

#endif
