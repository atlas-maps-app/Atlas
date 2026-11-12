// The water's glints (passes/Glints.cpp; fx/Glints.hpp): the moment's table of the filter's 256 results is the
// colour matrix as WebKit's float code applies it (A = 11 · red − 6.9 · 255 through one fused rounding, then rint;
// the glint's colour premultiplied by it), written back as sRGB; and on the host's Vulkan, the Westerkerk home by day,
// the ground drawn alone with the glints and without (no reflections or glitter, the shadows at no opacity): over the
// bare water, in full mode with the fields bound, each pixel is the table's entry at the glints' field's red where the
// water under it reads it (passes::water_map, fixed to the water; the bilinear blend of the field's bytes there, a red
// either side where the GPU's division rounds the point another way) composited at the rect's opacity over the frame
// without them, within a level (one 8-bit store); in structural mode, whose references leave out an element of a
// generator-only filter, nothing. And the map holds a ground point's place in the fields as the camera pans.
// The octaves (the far flicker): the home reads the field as it lies at every pixel; a far view reads it at the octave
// of each pixel's footprint, at most a field pixel of the coarser octave a device px, a point of the water keeping its
// place in every octave as the frame's middle crosses the fields' periods; and on the GPU the canal ring's board view
// is the two octaves' results blended, as passes::glints_read works them out.
#include "fx/ByteTables.hpp"
#include "fx/Glints.hpp"
#include "passes/Ground.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <format>
#include <optional>
#include <utility>

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

TEST_CASE("ground: the glints read the field as it lies at the home and by each pixel's footprint from afar") {
    constexpr double density = 2.625;
    constexpr std::uint32_t width = 1080;
    constexpr std::uint32_t height = 2400;
    constexpr double water_y = -1.5;
    const auto spec = [&](double yaw, double pitch, double dist, double x = -10.0, double z = -30.0) {
        return view::CameraSpec{.target = {x, 0.0, z},
                                .yaw = yaw,
                                .pitch = pitch,
                                .dist = dist,
                                .width = width / density,
                                .height = height / density};
    };
    // what a frame's pixels read: those as the field lies and those by octaves, the largest footprint, and the most
    // field pixels of its octave a read takes a device px (the footprint over 2^octave, the first read's)
    struct Reads {
        std::size_t lies = 0;
        std::size_t octaves = 0;
        double footprint = 0;
        double most = 0;
        float as_it_lies = 0;
    };
    const auto reads_of = [&](const view::CameraSpec& s) {
        const view::Camera cam(s);
        passes::WaterOctaves o;
        const std::array<float, 9> m = passes::water_map(cam, water_y, density, width, height, &o);
        Reads r;
        r.as_it_lies = o.as_it_lies;
        for (std::uint32_t y = 0; y < height; y += 8) {
            for (std::uint32_t x = 0; x < width; x += 8) {
                const passes::GlintsRead g = passes::glints_read(m, o, width, height, static_cast<float>(x) + 0.5F,
                                                                 static_cast<float>(y) + 0.5F);
                if (!g.first.on) continue;
                const bool lies = !g.second.on;
                r.lies += lies ? 1 : 0;
                r.octaves += lies ? 0 : 1;
                r.footprint = std::max(r.footprint, static_cast<double>(g.footprint));
                r.most = std::max(r.most, static_cast<double>(g.footprint) / std::exp2(static_cast<double>(g.octave)));
                if (!lies) CHECK((g.blend >= 0.0F && g.blend < 1.0F));
            }
        }
        return r;
    };
    // the home, its principal point at the frame's middle and where the phone's camera has it: every pixel as it lies
    for (const double cy : {height / density / 2, 440.0}) {
        view::CameraSpec home = spec(52.0, 38.0, 430.0);
        home.cy = cy;
        const Reads r = reads_of(home);
        MESSAGE(std::format("the home (cy {:.0f}): as it lies up to {:.2f} field px a px, its most {:.2f}", cy,
                            std::sqrt(static_cast<double>(r.as_it_lies)), r.footprint));
        CHECK(r.octaves == 0);
        CHECK(r.lies == std::size_t{135} * 300);
        CHECK(r.as_it_lies > 15.0F);
        CHECK(r.footprint < 3.1);
    }
    // from afar every pixel by its octaves, none taking more than two field pixels of its finer octave (so at most one
    // of the coarser), from the audit's pans' 10 and 15 km down to where the frame's middle takes four
    for (const double dist : {1800.0, 5000.0, 10000.0, 15000.0}) {
        const Reads r = reads_of(spec(22.0, 42.0, dist));
        MESSAGE(std::format(
            "{:.0f} m: {} pixels by octaves, {} as the field lies; the most {:.1f} field px a px, {:.3f} of "
            "its octave's",
            dist, r.octaves, r.lies, r.footprint, r.most));
        CHECK(r.as_it_lies == 1.0F);
        CHECK(r.lies == 0);
        CHECK(r.octaves > 40000U);
        CHECK(r.most < 2.0 + 1e-3);
    }
    // between, the field as it lies up to fewer of its pixels the farther the camera: 4 over the middle's footprint
    {
        const Reads r = reads_of(spec(52.0, 38.0, 860.0));
        CHECK(std::sqrt(static_cast<double>(r.as_it_lies)) == doctest::Approx(4.0 / 2.0).epsilon(0.05));
        CHECK(r.lies > 0U);
        CHECK(r.octaves > 0U);
        CHECK(r.most < (2.0 * 2.1));
    }
    // a point of the water keeps its place in each octave as the camera pans across the fields' periods: the octave's
    // point of the pixel that shows it, mirrored into the octave's field, from two cameras 40 km of water apart
    const auto mirrored = [](double v, double n) {
        double m = std::fmod(v, 2 * n);
        if (m < 0) m += 2 * n;
        return m < n ? m : (2 * n) - m;
    };
    const auto octave_point = [&](const view::CameraSpec& s, double x, double z, std::uint32_t octave) {
        const view::Camera cam(s);
        passes::WaterOctaves o;
        const std::array<float, 9> m = passes::water_map(cam, water_y, density, width, height, &o);
        // the octave asked for whatever the pixel's own: as_it_lies set so that the pixel's footprint is that octave's
        const std::optional<view::Vec2> p = cam.proj({x, water_y, z});
        REQUIRE(p.has_value());
        const auto px = static_cast<float>(p->x * density);
        const auto py = static_cast<float>(p->y * density);
        const passes::GlintsRead own = passes::glints_read(m, o, width, height, px, py);
        REQUIRE(own.first.on);
        const double f = static_cast<double>(own.footprint) / std::exp2(static_cast<double>(octave) + 0.5);
        o.as_it_lies = static_cast<float>(f * f);
        const passes::GlintsRead g = passes::glints_read(m, o, width, height, px, py);
        REQUIRE(g.second.on);
        REQUIRE(g.octave == octave);
        return std::array<double, 5>{static_cast<double>(g.first.x), static_cast<double>(g.first.y),
                                     static_cast<double>(g.second.x), static_cast<double>(g.second.y),
                                     static_cast<double>(o.periods[0]) + (1000.0 * o.periods[1])};
    };
    std::size_t held = 0;
    std::size_t crossed = 0;
    for (const std::uint32_t octave : {0U, 1U, 3U, 6U}) {
        for (const auto& [x, z] : {std::pair{-900.0, 400.0}, std::pair{250.0, -1300.0}, std::pair{2100.0, 800.0}}) {
            const std::array<double, 5> a = octave_point(spec(22.0, 42.0, 10000.0), x, z, octave);
            for (const auto& [tx, tz] :
                 {std::pair{1500.0, -700.0}, std::pair{-2300.0, 1900.0}, std::pair{600.0, 2500.0}}) {
                const std::array<double, 5> b = octave_point(spec(22.0, 42.0, 10000.0, tx, tz), x, z, octave);
                // a float's step at the map's points over the octave's scale, and the projection's own rounding
                const double eps = 0.6 / std::exp2(static_cast<double>(octave)) + 0.02;
                CHECK(std::abs(mirrored(a[0], width) - mirrored(b[0], width)) < eps);
                CHECK(std::abs(mirrored(a[1], height) - mirrored(b[1], height)) < eps);
                CHECK(std::abs(mirrored(a[2], width) - mirrored(b[2], width)) < eps);
                CHECK(std::abs(mirrored(a[3], height) - mirrored(b[3], height)) < eps);
                ++held;
                crossed += a[4] != b[4] ? 1 : 0;
            }
        }
    }
    CHECK(held == 36);
    // the cameras' middles lay in other periods of the fields (else the case holds nothing of the periods put back)
    CHECK(crossed >= 24U);
}

#if !defined(__ANDROID__)

#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <format>
#include <memory>
#include <string>
#include <string_view>
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
    std::size_t octaves = 0;         ///< bare pixels whose read is two octaves' blend
    std::size_t glinted_octaves = 0; ///< of the glinted, those
    std::string first;
};

} // namespace

TEST_CASE("ground: the glints are their filter's table at the field's red, over the water at the rect's opacity, the "
          "home as the field lies and the board's view by its pixels' octaves") {
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
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    struct Seen {
        const char* name;
        const char* pack;
        style::CameraState camera;
        frame::Mode mode;
    };
    const std::array<Seen, 3> seen{
        {{"the home", "amsterdam", test::home_camera(), frame::Mode::full},
         {"the home", "amsterdam", test::home_camera(), frame::Mode::structural},
         {"the board", "grachten", test::view_camera(dir, "grachten", "grachten_home"), frame::Mode::full}}};
    for (const Seen& view : seen) {
        const style::CameraState camera = view.camera;
        const frame::Mode mode = view.mode;
        const bool full = mode == frame::Mode::full;
        const bool home = std::string_view(view.name) == "the home";
        double opacity = 0;
        bool filtered = false;
        double wgrad_y1 = 0;
        double wgrad_y2 = 0;
        style::ThemeBlock th{};
        std::array<float, 9> map{};
        passes::WaterOctaves octaves;
        const auto quiet = [](passes::Ground& g) {
            g.draw_reflections(false);
            g.draw_glitter(false);
        };
        const auto a = test::draw_ground(
            *session, camera, day, dir, view.pack,
            [&](passes::Ground& g, const frame::FrameState& state) {
                const passes::GroundFrame& f = g.decided();
                opacity = f.wind_glints ? f.wind_glints_opacity : 0;
                filtered = (state.block.info.x & shaders::FRAME_FIELDS) != 0U;
                wgrad_y1 = f.wgrad_y1;
                wgrad_y2 = f.wgrad_y2;
                th = state.theme;
                if (state.view) {
                    map = passes::water_map(state.view->camera(), passes::water_level(f), state.camera.density,
                                            state.camera.width_px, state.camera.height_px, &octaves);
                }
            },
            40, quiet, mode, fields, settle);
        ATLAS_REQUIRE(a.has_value(), std::format("{} was not drawn with its glints", view.name));
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
        CHECK(filtered == full);
        CHECK((opacity > 0) == full);
        const auto b = test::draw_ground(
            *session, camera, day, dir, view.pack, {}, 40,
            [&](passes::Ground& g) {
                quiet(g);
                g.draw_wind_glints(false);
            },
            mode, fields, settle);
        ATLAS_REQUIRE(b.has_value(), std::format("{} was not drawn without its glints", view.name));
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
                // the field's red where the water under the pixel reads it, as fx_glints_red blends it: the field as
                // it lies, or its two octaves by the pixel's footprint (passes::glints_read, glints_fs's steps)
                const passes::GlintsRead read = passes::glints_read(
                    map, octaves, w, h, static_cast<float>(column) + 0.5F, static_cast<float>(row) + 0.5F);
                if (!read.first.on) continue;
                const auto red_of = [&](const passes::WaterPoint& q) {
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
                    return static_cast<std::int64_t>(std::floor((upper + ((lower - upper) * ty)) + 0.5F));
                };
                const std::int64_t r = red_of(read.first);
                const bool two = read.second.on;
                const std::int64_t r2 = two ? red_of(read.second) : r;
                c.octaves += two ? 1 : 0;
                // the filter's result there (r g b a), at the rect's opacity over the water: the red's, or a red either
                // side (each octave's), the two octaves' blended by the second's share
                const auto entry = [&](std::int64_t red_at) {
                    const fx::Argb f = table[static_cast<std::size_t>(std::clamp<std::int64_t>(red_at, 0, 255))];
                    return std::array<double, 4>{static_cast<double>((f >> 16U) & 0xFFU),
                                                 static_cast<double>((f >> 8U) & 0xFFU), static_cast<double>(f & 0xFFU),
                                                 static_cast<double>(f >> 24U)};
                };
                const bool glinted = entry(r)[3] != 0 || (two && entry(r2)[3] != 0);
                double worst = 1e9;
                std::array<double, 3> want{};
                const auto share = static_cast<double>(read.blend);
                for (const std::int64_t d1 : {0, -1, 1}) {
                    for (const std::int64_t d2 : {0, -1, 1}) {
                        if (!two && d2 != 0) continue;
                        const std::array<double, 4> e1 = entry(r + d1);
                        const std::array<double, 4> e2 = entry(r2 + d2);
                        std::array<double, 4> f{};
                        for (std::size_t k = 0; k < 4; ++k) f[k] = two ? e1[k] + ((e2[k] - e1[k]) * share) : e1[k];
                        double worst_here = 0;
                        std::array<double, 3> here{};
                        for (std::size_t k = 0; k < 3; ++k) {
                            const auto under = static_cast<double>(b->pixels.rgba[(i * 4) + k]);
                            here[k] = (f[k] * opacity) + (under * (1 - (f[3] / 255.0 * opacity)));
                            worst_here = std::max(worst_here,
                                                  std::abs(static_cast<double>(a->pixels.rgba[(i * 4) + k]) - here[k]));
                        }
                        if (worst_here < worst) {
                            worst = worst_here;
                            want = here;
                        }
                    }
                }
                if (!glinted) continue;
                ++c.glinted;
                c.glinted_octaves += two ? 1 : 0;
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
            std::format("the glints at {} ({:.0f} m): opacity {:.2f}; {} pixels of bare water, {} read by octaves "
                        "(the field as it lies up to {:.2f} of its pixels a px); {} under the glints, {} of them by "
                        "octaves, {} not their composite",
                        view.name, camera.distance, opacity, c.bare, c.octaves,
                        std::sqrt(static_cast<double>(octaves.as_it_lies)), c.glinted, c.glinted_octaves, c.wrong));
        CHECK(c.glinted > 2000U);
        CHECK_MESSAGE(c.wrong == 0, c.first);
        // the home reads the field as it lies at every pixel (its pixels are what they were); the board by octaves
        if (home) {
            CHECK(c.octaves == 0U);
        } else {
            CHECK(c.glinted_octaves > 2000U);
        }
    }
}

#endif
