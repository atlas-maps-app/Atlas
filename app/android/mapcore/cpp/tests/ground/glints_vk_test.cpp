// The water's glints (passes/Glints.cpp; fx/Glints.hpp): the moment's table of the filter's 256 results is the
// colour matrix as WebKit's float code applies it (A = 11 · red − 6.9 · 255 through one fused rounding, then rint;
// the glint's colour premultiplied by it), written back as sRGB; and on the host's Vulkan, the Westerkerk home by day,
// the ground drawn alone with the glints and without (no reflections or glitter, the shadows at no opacity): over the
// bare water, in full mode with the fields bound, each pixel is the table's entry at the glints' field's red where the
// water under it reads it (passes::water_map, fixed to the water; the bilinear blend of the field's bytes there, a red
// either side where the GPU's division rounds the point another way) composited at the rect's opacity over the frame
// without them, within a level (one 8-bit store); in structural mode, whose references leave out an element of a
// generator-only filter, nothing. And the map holds a ground point's place in the fields as the camera pans.
#include "fx/ByteTables.hpp"
#include "fx/Glints.hpp"
#include "passes/Ground.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <optional>

using namespace atlas;

TEST_CASE("fx: the glints' table is the colour matrix's result for each red, written back as sRGB") {
    for (const bool night : {false, true}) {
        const std::array<float, 3> gl = fx::glint_colour(night);
        const std::array<fx::Argb, 256> table = fx::glint_table(gl);
        std::size_t drawn = 0;
        for (std::uint32_t r = 0; r < 256; ++r) {
            // row 15 of the matrix: fma(11, r, 0); then fma(-6.9f, 255, t), one rounding of the exact sum; rint
            const double exact = (11.0 * r) + (static_cast<double>(-6.9F) * 255.0);
            const double a = std::clamp(std::nearbyint(static_cast<double>(static_cast<float>(exact))), 0.0, 255.0);
            fx::Argb want = 0;
            if (a > 0) {
                const auto alpha = static_cast<std::uint32_t>(a);
                std::array<std::uint32_t, 3> c{};
                for (std::size_t k = 0; k < 3; ++k) {
                    c[k] = static_cast<std::uint32_t>(std::nearbyint(static_cast<double>(gl[k] * 255.0F)));
                }
                const fx::Argb linear = fx::bytes::premultiply(fx::bytes::pack(alpha, c[0], c[1], c[2]));
                want = fx::bytes::premultiply(fx::bytes::unpremultiply_exact(linear, false));
                ++drawn;
            }
            CHECK_MESSAGE(table[r] == want, (night ? "night" : "day") << " red " << r);
        }
        // the glints show where the noise's red passes 160
        CHECK(drawn == 95);
    }
}

TEST_CASE("ground: the water's map keeps a point of the water where it reads the fields as the camera pans, turns and "
          "zooms") {
    // the Westerkerk home (amsterdam.py's main camera) on a phone of 1080 × 2400 at 2.625, the water 1.5 m down
    constexpr double density = 2.625;
    constexpr std::uint32_t width = 1080;
    constexpr std::uint32_t height = 2400;
    constexpr double water_y = -1.5;
    const view::CameraSpec home{.target = {-10.0, 0.0, -30.0},
                                .yaw = 52.0,
                                .pitch = 38.0,
                                .dist = 430.0,
                                .width = width / density,
                                .height = height / density};
    // where the water's point (x, water_y, z) reads the fields through camera `spec`'s map: device px
    const auto read_at = [&](const view::CameraSpec& spec, double x, double z) {
        const view::Camera cam(spec);
        const std::array<float, 9> m = passes::water_map(cam, water_y, density, width, height);
        const std::optional<view::Vec2> p = cam.proj({x, water_y, z});
        REQUIRE(p.has_value());
        const passes::WaterPoint q =
            passes::water_point(m, static_cast<float>(p->x * density), static_cast<float>(p->y * density));
        REQUIRE(q.on);
        return std::array<double, 2>{static_cast<double>(q.x), static_cast<double>(q.y)};
    };
    const auto mirrored = [](double v, double n) {
        double m = std::fmod(v, 2 * n);
        if (m < 0) m += 2 * n;
        return m < n ? m : (2 * n) - m;
    };
    std::size_t held = 0;
    for (const double x : {-80.0, -10.0, 60.0}) {
        for (const double z : {-120.0, -30.0, 40.0}) {
            const std::array<double, 2> q = read_at(home, x, z);
            view::CameraSpec panned = home;
            panned.target = {home.target.x + 35.0, 0.0, home.target.z - 25.0};
            view::CameraSpec turned = home;
            turned.yaw = home.yaw + 24.0;
            view::CameraSpec zoomed = home;
            zoomed.dist = home.dist * 0.7;
            zoomed.pitch = home.pitch + 6.0;
            for (const view::CameraSpec& other : {panned, turned, zoomed}) {
                const std::array<double, 2> o = read_at(other, x, z);
                // the same place in the fields (each reduced by its own whole mirror periods)
                CHECK(std::abs(mirrored(o[0], width) - mirrored(q[0], width)) < 0.05);
                CHECK(std::abs(mirrored(o[1], height) - mirrored(q[1], height)) < 0.05);
                ++held;
            }
        }
    }
    CHECK(held == 27);
    // and at the home's target the fields go by the frame's own pixels, as the renders read them there
    const view::Camera cam(home);
    const std::array<float, 9> m = passes::water_map(cam, water_y, density, width, height);
    const std::optional<view::Vec2> target = cam.proj({home.target.x, water_y, home.target.z});
    REQUIRE(target.has_value());
    const view::Vec2 t = target.value_or(view::Vec2{});
    const auto step = [&](double dx, double dy) {
        const passes::WaterPoint a =
            passes::water_point(m, static_cast<float>(t.x * density), static_cast<float>(t.y * density));
        const passes::WaterPoint b =
            passes::water_point(m, static_cast<float>((t.x * density) + dx), static_cast<float>((t.y * density) + dy));
        const auto ex = static_cast<double>(b.x - a.x);
        const auto ey = static_cast<double>(b.y - a.y);
        return std::sqrt((ex * ex) + (ey * ey));
    };
    CHECK(std::abs(step(10.0, 0.0) - 10.0) < 0.3);
    CHECK(std::abs(step(0.0, 10.0) - 10.0) < 0.6);
}

#if !defined(__ANDROID__)

#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <format>
#include <memory>
#include <string>
#include <vector>

namespace {

std::array<double, 3> channels(const style::Rgb8& c) {
    return {static_cast<double>(c.r), static_cast<double>(c.g), static_cast<double>(c.b)};
}

/// Index `v` of an axis of `n` pixels mirrored into it (mat.slang's fx_mirror).
std::size_t mirror(std::int64_t v, std::size_t n) {
    const auto size = static_cast<std::int64_t>(n);
    std::int64_t m = v % (2 * size);
    if (m < 0) m += 2 * size;
    return static_cast<std::size_t>(m < size ? m : (2 * size) - 1 - m);
}

/// What the frames with and without the glints show over the bare water.
struct Count {
    std::size_t bare = 0;
    std::size_t glinted = 0;
    std::size_t wrong = 0;
    std::string first;
};

} // namespace

TEST_CASE("ground: the glints are their filter's table at the field's red, over the water at the rect's opacity") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the glints are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    const style::CameraState camera = test::home_camera();
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    for (const frame::Mode mode : {frame::Mode::full, frame::Mode::structural}) {
        const bool full = mode == frame::Mode::full;
        double opacity = 0;
        bool filtered = false;
        double wgrad_y1 = 0;
        double wgrad_y2 = 0;
        style::ThemeBlock th{};
        std::array<float, 9> map{};
        const auto quiet = [](passes::Ground& g) {
            g.draw_reflections(false);
            g.draw_glitter(false);
        };
        const auto a = test::draw_ground(
            *session, camera, day, dir, "amsterdam",
            [&](passes::Ground& g, const frame::FrameState& state) {
                const passes::GroundFrame& f = g.decided();
                opacity = f.wind_glints ? f.wind_glints_opacity : 0;
                filtered = (state.block.info.x & shaders::FRAME_FIELDS) != 0U;
                wgrad_y1 = f.wgrad_y1;
                wgrad_y2 = f.wgrad_y2;
                th = state.theme;
                if (state.view) {
                    map = passes::water_map(state.view->camera(), passes::water_level(f), state.camera.density,
                                            state.camera.width_px, state.camera.height_px);
                }
            },
            40, quiet, mode, fields, settle);
        ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its glints");
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
        CHECK(filtered == full);
        CHECK((opacity > 0) == full);
        const auto b = test::draw_ground(
            *session, camera, day, dir, "amsterdam", {}, 40,
            [&](passes::Ground& g) {
                quiet(g);
                g.draw_wind_glints(false);
            },
            mode, fields, settle);
        ATLAS_REQUIRE(b.has_value(), "the home was not drawn without its glints");
        if (!full) {
            CHECK_MESSAGE(a->pixels.rgba == b->pixels.rgba, "structural mode drew the glints");
            continue;
        }
        const std::uint32_t w = a->pixels.width;
        const std::uint32_t h = a->pixels.height;
        const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
        ATLAS_REQUIRE(latest != nullptr, "no fields");
        REQUIRE(latest->size().width == w);
        const std::vector<std::uint8_t> red = latest->bytes_of(fx::Field::glints);
        const std::array<fx::Argb, 256> table = fx::glint_table(fx::glint_colour(false));
        // the bare water of the frame without them: wgrad under the tint, a pixel's neighbours too
        const std::array<double, 3> top = channels(th.water_top);
        const std::array<double, 3> bottom = channels(th.water_bottom);
        const std::array<double, 3> water = channels(th.water);
        constexpr double tint = 0.12;
        std::vector<std::uint8_t> plain(std::size_t{w} * h, 0);
        for (std::size_t i = 0; i < plain.size(); ++i) {
            const std::size_t row = i / w;
            const double css_y = (static_cast<double>(row) + 0.5) / static_cast<double>(camera.density);
            const double t = wgrad_y2 != wgrad_y1 ? std::clamp((css_y - wgrad_y1) / (wgrad_y2 - wgrad_y1), 0.0, 1.0)
                                                  : (css_y < wgrad_y1 ? 0.0 : 1.0);
            bool bare = true;
            for (std::size_t k = 0; k < 3; ++k) {
                const double g = top[k] + ((bottom[k] - top[k]) * t);
                bare = bare && std::abs(static_cast<double>(b->pixels.rgba[(i * 4) + k]) -
                                        ((g * (1 - tint)) + (water[k] * tint))) <= 2;
            }
            plain[i] = bare ? 1 : 0;
        }
        Count c;
        for (std::size_t row = 1; row + 1 < h; ++row) {
            for (std::size_t column = 1; column + 1 < w; ++column) {
                bool around = true;
                for (std::size_t y = row - 1; y <= row + 1 && around; ++y) {
                    for (std::size_t x = column - 1; x <= column + 1; ++x) around = around && plain[(y * w) + x] != 0;
                }
                if (!around) continue;
                ++c.bare;
                const std::size_t i = (row * w) + column;
                // the field's red where the water under the pixel reads it, as fx_glints_red blends it
                const passes::WaterPoint q =
                    passes::water_point(map, static_cast<float>(column) + 0.5F, static_cast<float>(row) + 0.5F);
                if (!q.on) continue;
                const float fx0 = std::floor(q.x);
                const float fy0 = std::floor(q.y);
                const auto at = [&](std::int64_t x, std::int64_t y) {
                    return static_cast<float>(red[(static_cast<std::size_t>(mirror(y, h)) * w) + mirror(x, w)]);
                };
                const auto ix = static_cast<std::int64_t>(fx0);
                const auto iy = static_cast<std::int64_t>(fy0);
                const float tx = q.x - fx0;
                const float ty = q.y - fy0;
                const float upper = at(ix, iy) + ((at(ix + 1, iy) - at(ix, iy)) * tx);
                const float lower = at(ix, iy + 1) + ((at(ix + 1, iy + 1) - at(ix, iy + 1)) * tx);
                // fx_glints_red's uint(r + 0.5): the blend is never negative, so the floor is its truncation
                const auto r = static_cast<std::int64_t>(std::floor((upper + ((lower - upper) * ty)) + 0.5F));
                // the filter's result there, at the rect's opacity over the water: the red's, or a red either side
                bool glinted = false;
                double worst = 1e9;
                std::array<double, 3> want{};
                for (const std::int64_t red_at : {r, r - 1, r + 1}) {
                    if (red_at < 0 || red_at > 255) continue;
                    const fx::Argb f = table[static_cast<std::size_t>(red_at)];
                    const auto fa = static_cast<double>(f >> 24U);
                    glinted = glinted || (fa != 0 && red_at == r);
                    const std::array<double, 3> fc{static_cast<double>((f >> 16U) & 0xFFU),
                                                   static_cast<double>((f >> 8U) & 0xFFU),
                                                   static_cast<double>(f & 0xFFU)};
                    double worst_here = 0;
                    std::array<double, 3> here{};
                    for (std::size_t k = 0; k < 3; ++k) {
                        const auto under = static_cast<double>(b->pixels.rgba[(i * 4) + k]);
                        here[k] = (fc[k] * opacity) + (under * (1 - (fa / 255.0 * opacity)));
                        worst_here =
                            std::max(worst_here, std::abs(static_cast<double>(a->pixels.rgba[(i * 4) + k]) - here[k]));
                    }
                    if (worst_here < worst) {
                        worst = worst_here;
                        want = here;
                    }
                }
                if (!glinted) continue;
                ++c.glinted;
                if (worst <= 1) continue;
                if (c.wrong == 0) {
                    c.first = std::format("pixel {} {}: {} {} {}, expected {:.1f} {:.1f} {:.1f}", column, row,
                                          int(a->pixels.rgba[i * 4]), int(a->pixels.rgba[(i * 4) + 1]),
                                          int(a->pixels.rgba[(i * 4) + 2]), want[0], want[1], want[2]);
                }
                ++c.wrong;
            }
        }
        MESSAGE(
            std::format("the glints at the home: opacity {:.2f}; {} pixels of bare water, {} under them, {} not their "
                        "composite",
                        opacity, c.bare, c.glinted, c.wrong));
        CHECK(c.glinted > 2000U);
        CHECK_MESSAGE(c.wrong == 0, c.first);
    }
}

#endif
