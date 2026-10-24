// The frame's view (plan-vector D4, K7): the engine camera's projection and its device pixels, the snap at rest as the
// SVG's `%.1f` prints (a planted set: ties on exact binary halves, values a hair either side of every print step,
// signed zeros, large and tiny values), the print precisions the composite's values are rounded to, the frame drawn and
// its blend, and ranks against the renders' stable sort. The port's own values are held to these by the JVM's parity
// tests (MT/frame/LadderParityTest.kt); here the C++ is held to its own definitions on every host.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "scene/ItemTable.hpp"
#include "view/Camera.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"
#include "view/ScreenLayers.hpp"
#include "view/Snap.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <format>
#include <random>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// A generator of a seed given (the tests' sequences are fixed, so every run checks the same values).
[[nodiscard]] std::mt19937_64 seeded(std::uint64_t seed) { return std::mt19937_64(seed); }

/// `v` as '%.<n>f' prints it (std::format's fixed precision: the exact binary value rounded, ties to even).
[[nodiscard]] std::string printed(double v, int n) { return std::format("{:.{}f}", v, n); }

/// The planted set: every step of a tenth from -3 to 3 and around 1e5, each as the double nearest it, its neighbours
/// and its halves' neighbours; exact binary ties (k/4); signed zeros; values that print as -0.0.
[[nodiscard]] std::vector<double> planted() {
    std::vector<double> out{0.0,       -0.0,  0.25,   0.75,     1.25,      -0.25, -0.75, 2.25,  0.05,
                            -0.05,     0.04,  -0.04,  0.049999, -0.049999, 0.35,  0.45,  2.675, 1e7 + 0.05,
                            123456.75, 1e-30, -1e-30, 5e-324,   0.1,       0.2,   0.3};
    for (int k = -30; k <= 30; ++k) {
        for (const double base : {0.0, 1.0e5}) {
            const double step = base + static_cast<double>(k) / 10.0;
            const double half = base + (static_cast<double>(k) + 0.5) / 10.0;
            for (const double v : {step, half}) {
                out.push_back(v);
                out.push_back(std::nextafter(v, 1e9));
                out.push_back(std::nextafter(v, -1e9));
            }
        }
    }
    return out;
}

} // namespace

TEST_CASE("the snap at rest prints as the SVG's %.1f on a planted set, in double and as the vertex stage's float") {
    std::size_t checked = 0;
    for (const double v : planted()) {
        const double s = view::snap_tenth(v);
        CHECK_MESSAGE(printed(s, 1) == printed(v, 1), std::format("{:.17g} snaps to {:.17g}", v, s));
        // The snapped value is the double nearest its print (what the SVG's parser reads back).
        CHECK(std::bit_cast<std::uint64_t>(s) == std::bit_cast<std::uint64_t>(std::stod(printed(v, 1))));
        const auto f = static_cast<float>(v);
        const float sf = view::snap_tenth(f);
        CHECK_MESSAGE(printed(static_cast<double>(sf), 1) == printed(static_cast<double>(f), 1),
                      std::format("the float {:.9g} snaps to {:.9g}", f, sf));
        ++checked;
    }
    // Random coordinates over a frame and far beyond it.
    std::mt19937_64 rng = seeded(20261018);
    std::uniform_real_distribution<double> near(-3000.0, 3000.0);
    std::uniform_real_distribution<double> far(-2.0e6, 2.0e6);
    for (int i = 0; i < 200'000; ++i) {
        const double v = (i % 5 == 0) ? far(rng) : near(rng);
        if (printed(view::snap_tenth(v), 1) != printed(v, 1)) {
            FAIL(std::format("{:.17g}", v));
            break;
        }
        const auto f = static_cast<float>(v);
        if (printed(static_cast<double>(view::snap_tenth(f)), 1) != printed(static_cast<double>(f), 1)) {
            FAIL(std::format("the float {:.9g}", f));
            break;
        }
    }
    MESSAGE(checked << " planted values and 200,000 random ones print as '%.1f' prints them");
}

TEST_CASE("decimals rounds to the SVG's precisions as Python's '%.nf' does") {
    std::mt19937_64 rng = seeded(7);
    std::uniform_real_distribution<double> u(-1000.0, 1000.0);
    for (int n = 0; n <= 3; ++n) {
        for (const double v : planted()) CHECK(printed(view::decimals(v, n), n) == printed(v, n));
        for (int i = 0; i < 50'000; ++i) {
            const double v = u(rng) / pymath::pow(10.0, static_cast<double>(i % 4));
            if (printed(view::decimals(v, n), n) != printed(v, n)) {
                FAIL(std::format("{:.17g} at {} decimals", v, n));
                break;
            }
        }
    }
    CHECK(std::signbit(view::decimals(-0.0004, 3)));
    CHECK(view::decimals(0.0625, 3) == 0.062); // an exact tie, to the even
    CHECK(view::decimals(2.5, 0) == 2.0);
    CHECK(view::decimals(3.5, 0) == 4.0);
}

TEST_CASE("the engine camera projects as render/city.py's Camera, and its device pixels are the density's") {
    // amsterdam.py's main camera (the Westerkerk home) on 412 × 916 CSS px at S1's density.
    const view::CameraSpec spec{.target = {-10, 0, -30},
                                .yaw = 52,
                                .pitch = 38,
                                .dist = 430,
                                .fov = 36,
                                .width = 412,
                                .height = 916,
                                .cx = std::nullopt,
                                .cy = 440};
    const view::View v(spec, {.density = 1080.0 / 412.0, .width_px = 1080, .height_px = 2400, .quarter_turns = 0});
    const view::Camera& c = v.camera();
    // The target projects onto the principal point; the eye stands `dist` back along the view.
    const view::Vec2 t = c.pc(c.cam(spec.target));
    CHECK(t.x == doctest::Approx(206.0).epsilon(1e-12));
    CHECK(t.y == doctest::Approx(440.0).epsilon(1e-12));
    CHECK(c.depth(spec.target) == doctest::Approx(430.0).epsilon(1e-12));
    const view::Vec3& e = c.eye();
    CHECK(std::sqrt((e.x + 10) * (e.x + 10) + e.y * e.y + (e.z + 30) * (e.z + 30)) == doctest::Approx(430.0));
    CHECK(e.y == doctest::Approx(430.0 * pymath::sin(pymath::radians(38.0))));
    // The focal length: (H / 2) / tan(18°).
    CHECK(c.focal() == doctest::Approx(458.0 / pymath::tan(pymath::radians(18.0))).epsilon(1e-14));
    // The horizon: the ground meets the eye's height there, and the ground depth goes to nothing at it.
    const double hy = c.horizon_y();
    CHECK(hy < 0); // above the frame at this pitch
    CHECK_FALSE(c.ground_depth(hy - 1.0).has_value());
    CHECK(c.ground_depth(900.0).value_or(-1.0) > 0);
    // Device pixels: CSS times the density from the top-left corner.
    const view::Vec2 d = v.device_proj(spec.target).value_or(view::Vec2(-1, -1));
    CHECK(d.x == doctest::Approx(206.0 * 1080.0 / 412.0));
    CHECK(d.y == doctest::Approx(440.0 * 1080.0 / 412.0));
    CHECK_FALSE(c.proj(e).has_value()); // the eye itself is nearer than the near plane
    // The float block the GPU reads, through the shared camera.slang: within 1e-3 CSS px of the double's projection
    // for points across the frame (L2's allowance), relative to the eye through a tile's place.
    const shaders::FrameCamera b = v.block(false, false, 1);
    const shaders::TilePlace place = v.tile_place({-400, 0, -400}, 1.0, 1.0);
    double worst = 0;
    for (int i = 0; i < 2000; ++i) {
        const int column = i % 40;
        const int row = i / 40;
        const double x = -400 + (static_cast<double>(column) * 20.0);
        const double z = -400 + (static_cast<double>(row) * 16.0);
        const view::Vec3 w{x, static_cast<double>(i % 7) * 3.0, z};
        const auto css = c.proj(w);
        // Where a vertex can matter: on the frame or within one frame of it (far off it a float's step grows).
        if (!css.has_value()) continue;
        const view::Vec2 p = *css;
        if (p.x < -412 || p.x > 824 || p.y < -916 || p.y > 1832) continue;
        const hlsl::float3 local = {static_cast<float>(w.x + 400), static_cast<float>(w.y),
                                    static_cast<float>(w.z + 400)};
        const hlsl::float3 k = shaders::camera_of(b, shaders::eye_relative(place, local));
        if (k.z < 1.0F) continue;
        const hlsl::float2 s = shaders::css_of(b, k);
        worst =
            pymath::max({worst, std::fabs(static_cast<double>(s.x) - p.x), std::fabs(static_cast<double>(s.y) - p.y)});
    }
    MESSAGE("the float path's worst error against the double camera: " << worst << " CSS px");
    CHECK(worst < 1e-3);
}

TEST_CASE("the frame drawn converts between frames and blends a change over 300 ms") {
    const view::GroundFrame a{.lat0 = 52.3746, .lon0 = 4.8840, .kx = 111320 * 0.6100, .kz = 110540};
    const view::GroundFrame b{.lat0 = 52.30, .lon0 = 4.95, .kx = 111320 * 0.6109, .kz = 110540};
    const view::Vec3 p{1234.5, 2.0, -876.25};
    const view::Vec3 back = view::convert(b, a, view::convert(a, b, p));
    CHECK(back.x == doctest::Approx(p.x).epsilon(1e-12));
    CHECK(back.z == doctest::Approx(p.z).epsilon(1e-12));
    view::Frames frames;
    frames.set(a, 10.0);
    CHECK(frames.weight(10.0) == 1.0); // the first frame is drawn at once
    frames.set(b, 20.0);
    CHECK(frames.weight(20.0) == 0.0);
    CHECK(frames.weight(20.15) == doctest::Approx(0.5));
    CHECK(frames.weight(20.3) == 1.0);
    // At the change a tile of frame a keeps its old place about the target; at the end it is in b's frame exactly.
    const view::Vec3 target = view::convert(a, b, {0, 0, 0});
    const view::Placement start = frames.place(a, p, target, 20.0);
    const view::Placement end = frames.place(a, p, target, 21.0);
    const view::Vec3 exact = view::convert(a, b, p);
    CHECK(end.origin.x == doctest::Approx(exact.x));
    CHECK(end.scale == doctest::Approx(b.kx / a.kx));
    CHECK(start.origin.x - target.x == doctest::Approx((exact.x - target.x) * a.kx / b.kx));
    CHECK(start.scale == doctest::Approx(1.0)); // the old frame's own metres
}

TEST_CASE("a pack without views frames the live camera in its own frame, with a region's settings") {
    // a frame cell's windows: their frame is the cell's, its origin the cell's centre, kilometres from the camera
    const view::GroundFrame cell{.lat0 = 52.3745, .lon0 = 4.7461, .kx = 111320 * 0.6101, .kz = 110540};
    const view::LiveView v{.latitude = 52.3749,
                           .longitude = 4.8839,
                           .distance = 1600,
                           .bearing = 52,
                           .pitch = 52,
                           .width_dp = 412,
                           .height_dp = 915,
                           .cy_dp = 440};
    const view::Ladder region = view::Ladder::bare(cell, false);
    CHECK(region.knots().empty());
    CHECK(region.snap(v) == nullptr);
    const view::Placed p = region.place(v);
    CHECK_FALSE(p.view().has_value());
    CHECK(p.dist() == 1600);
    const view::CameraSpec c = p.camera();
    CHECK(c.target.x == cell.x(v.latitude, v.longitude)); // about 9.4 km east of the cell's centre
    CHECK(c.target.x > 9000);
    CHECK(c.target.z == cell.z(v.latitude, v.longitude));
    CHECK(c.target.y == 0);
    CHECK(c.dist == 1600);
    CHECK(c.yaw == view::bearing_of(52));
    CHECK(c.pitch == view::engine_pitch(52));
    // regions.py's cfg_for for a view of nothing but its camera: no fin, the haze from the distance
    CHECK(p.truthy("no_fin"));
    CHECK(p.number("haze0") == 1600 * 0.85);
    CHECK(p.number("haze1") == 1600 * 2.2);
    const view::LayerSettings s = view::settings_of(p);
    CHECK(s.no_fin);
    CHECK(s.width == 412);
    CHECK(s.height == 915);
    // the living map's camera has no cfg_for
    const view::Placed living = view::Ladder::bare(cell, true).place(v);
    CHECK_FALSE(living.truthy("no_fin"));
    CHECK_FALSE(living.number("haze0").has_value());
    CHECK(living.camera().target.x == c.target.x);
}

TEST_CASE("ranks are the renders' stable sort by depth, kept by an insertion pass as the camera turns") {
    scene::ItemTable items;
    std::mt19937_64 rng = seeded(42);
    std::uniform_real_distribution<double> u(-800.0, 800.0);
    for (std::uint32_t i = 0; i < 4000; ++i) {
        const double x = std::round(u(rng)); // whole metres: equal depths happen, and the gather order breaks them
        const double z = std::round(u(rng));
        items.add({.x = x,
                   .y = 4,
                   .z = z,
                   .offset = (i % 3 == 0) ? -0.01 * (i % 11) : 0.0,
                   .cut_x = x,
                   .cut_z = z,
                   .order = scene::order_of(i % 2, i),
                   .kind = 0,
                   .flags = 0,
                   .slots = {10, 5, 2, 1},
                   .min_depth = 2.0F,
                   .radius = 20.0F,
                   .tile = 1});
    }
    frame::Selectors selectors;
    const std::uint16_t kind = selectors.kind("building");
    selectors.set(kind, 300.0, 600.0, 1200.0);
    // the detail, in its own order (not ascending)
    const std::array<double, 3> details{600.0, 300.0, 1200.0};
    selectors.set_details(kind, details);
    frame::Ranks ranks;
    std::uint64_t moves = 0;
    std::size_t full_sorts = 0;
    for (int f = 0; f < 60; ++f) {
        const view::Camera camera({.target = {0, 0, 0},
                                   .yaw = 30.0 + f * 1.5,
                                   .pitch = 40.0 + f * 0.2,
                                   .dist = 900,
                                   .fov = 36,
                                   .width = 412,
                                   .height = 916,
                                   .cx = std::nullopt,
                                   .cy = 440});
        ranks.update(items, camera, selectors, {.ground_slots = 100});
        // The renders' order: gatherItems' list (rows in order key order), then pySortBy { -d }: a stable sort.
        std::vector<std::uint32_t> want(ranks.order().begin(), ranks.order().end());
        std::ranges::sort(want, [&](std::uint32_t a, std::uint32_t b) { return items.order()[a] < items.order()[b]; });
        std::ranges::stable_sort(
            want, [&](std::uint32_t a, std::uint32_t b) { return -ranks.depth()[a] < -ranks.depth()[b]; });
        CHECK(std::ranges::equal(want, ranks.order()));
        // Slots: the ground's first, then each item's, by its band; the rank table holds them and its detail.
        std::uint32_t at = 100;
        for (const std::uint32_t row : ranks.order()) {
            const double d = ranks.depth()[row];
            const std::size_t band = selectors.band(kind, d);
            const std::uint32_t detail = (d < 600.0 ? 1U : 0U) | (d < 300.0 ? 2U : 0U) | (d < 1200.0 ? 4U : 0U);
            CHECK(ranks.first_slot()[row] == at);
            CHECK(ranks.band()[row] == band);
            CHECK(selectors.detail(kind, d) == detail);
            CHECK(ranks.table()[row] == shaders::rank_entry(at, detail));
            CHECK(shaders::rank_slot(ranks.table()[row]) == at);
            CHECK(shaders::rank_detail(ranks.table()[row]) == detail);
            at += items.slots(row, band);
        }
        CHECK(ranks.slots() == at);
        for (std::uint32_t row = 0; row < items.rows(); ++row) {
            if (ranks.first_slot()[row] == frame::Ranks::none) CHECK(ranks.table()[row] == shaders::RANK_NONE);
        }
        moves += ranks.moves();
        full_sorts += ranks.sorted_fully() ? 1 : 0;
    }
    MESSAGE("60 frames turning 1.5° each over " << ranks.order().size() << " drawn items: " << moves
                                                << " insertion moves, " << full_sorts << " full sorts");
    // A pan keeps the order: no moves.
    const view::Camera panned({.target = {50, 0, 20},
                               .yaw = 118.5,
                               .pitch = 51.8,
                               .dist = 900,
                               .fov = 36,
                               .width = 412,
                               .height = 916,
                               .cx = std::nullopt,
                               .cy = 440});
    ranks.update(items, panned, selectors, {.ground_slots = 100});
    const view::Camera panned_more({.target = {51, 0, 21},
                                    .yaw = 118.5,
                                    .pitch = 51.8,
                                    .dist = 900,
                                    .fov = 36,
                                    .width = 412,
                                    .height = 916,
                                    .cx = std::nullopt,
                                    .cy = 440});
    const std::vector<std::uint32_t> before(ranks.order().begin(), ranks.order().end());
    ranks.update(items, panned_more, selectors, {.ground_slots = 100});
    {
        std::vector<std::uint32_t> want(ranks.order().begin(), ranks.order().end());
        std::ranges::sort(want, [&](std::uint32_t a, std::uint32_t b) { return items.order()[a] < items.order()[b]; });
        std::ranges::stable_sort(
            want, [&](std::uint32_t a, std::uint32_t b) { return -ranks.depth()[a] < -ranks.depth()[b]; });
        CHECK(std::ranges::equal(want, ranks.order()));
    }
    std::size_t common = 0;
    for (const std::uint32_t r : ranks.order()) common += std::ranges::find(before, r) != before.end() ? 1U : 0U;
    MESSAGE("a 1.4 m pan: " << ranks.moves() << " moves over " << common << " items drawn in both frames");
}

TEST_CASE("an item's band and detail are the ranks' choice in double, which a float depth or threshold can miss") {
    // The camera looks north from 1,000 m south of the origin at street level: an item on the axis at z has depth
    // 1,000 − z. Two items lie just short of their kind's first threshold, where a float would put them past it: one
    // within a float step of 460 m (its depth rounds to 460.0f), one between win_d-like 412.3456789 m and that
    // threshold rounded to float.
    frame::Selectors selectors;
    const std::uint16_t walls = selectors.kind("walls");
    const std::uint16_t windows = selectors.kind("windows");
    selectors.set(walls, 460.0);
    selectors.set(windows, 412.3456789);
    const std::array<double, 1> wall_detail{460.0};
    const std::array<double, 1> window_detail{412.3456789};
    selectors.set_details(walls, wall_detail);
    selectors.set_details(windows, window_detail);
    scene::ItemTable items;
    auto add = [&items](double z, std::uint16_t kind) {
        return items.add({.x = 0,
                          .y = 4,
                          .z = z,
                          .offset = 0,
                          .cut_x = 0,
                          .cut_z = z,
                          .order = scene::order_of(0, static_cast<std::uint64_t>(items.rows())),
                          .kind = kind,
                          .flags = 0,
                          .slots = {7, 3, 1, 1},
                          .min_depth = 2.0F,
                          .radius = 5.0F,
                          .tile = 1});
    };
    const std::uint32_t wall = add(540.00001, walls);
    const std::uint32_t window = add(1000.0 - 412.345675, windows);
    const view::Camera camera({.target = {0, 0, 0},
                               .yaw = 0,
                               .pitch = 0,
                               .dist = 1000,
                               .fov = 36,
                               .width = 412,
                               .height = 916,
                               .cx = std::nullopt,
                               .cy = std::nullopt});
    frame::Ranks ranks;
    ranks.update(items, camera, selectors);
    for (const std::uint32_t row : {wall, window}) {
        REQUIRE(ranks.first_slot()[row] != frame::Ranks::none);
        const double depth = ranks.depth()[row];
        const std::uint16_t kind = items.kind()[row];
        CHECK(depth < selectors.thresholds(kind)[0]);
        // A float depth, or the threshold as the selector table holds it, would say band 1.
        const hlsl::float4 t = selectors.table()[kind];
        CHECK((static_cast<float>(depth) >= t.x || depth >= static_cast<double>(t.x)));
        CHECK(ranks.band()[row] == 0);
        CHECK(shaders::rank_detail(ranks.table()[row]) == 1U);
    }
    // The wall, at 460 m less a hair, is the farther: first in painter's order, its band-0 slots before the window's.
    CHECK(ranks.order().front() == wall);
    CHECK(shaders::rank_slot(ranks.table()[window]) == 7);
}

TEST_CASE("the screen layers of a camera and a theme: the 17 stops, the eye-level band, the sun, fin and the rain") {
    style::ThemeBlock th{};
    th.version = style::theme_block_version;
    th.flags = style::theme_flag::weather | style::theme_flag::wet | style::theme_flag::wet_set |
               style::theme_flag::cloud_shadows;
    th.haze_max = 0.5;
    th.cloud = 0.4;
    th.cloud_shadows = 0.6;
    th.wind = 23.0;
    th.sun_vector = {0.3, 0.5, -0.8124};
    const view::CameraSpec spec{
        .target = {0, 0, 0}, .yaw = 52, .pitch = 38, .dist = 430, .width = 412, .height = 916, .cy = 440};
    const view::Camera cam(spec);
    const view::Layers l =
        view::layers_of(cam, th, {.width = 412, .height = 916, .pitch = 38, .haze0 = 380, .haze1 = 800});
    CHECK(l.weather);
    CHECK_FALSE(l.eye);
    CHECK(l.haze.count == 17);
    CHECK(l.haze.y1 == 916);
    CHECK(l.haze.offsets[16] == 1.0);
    CHECK(l.rain_angle.value_or(-1) == 18); // int(23 · 0.8)
    CHECK(l.cloud_shadows());
    CHECK(l.cloud_shadow_cut == doctest::Approx(-7 * (0.62 - 0.6 * 0.3)));
    CHECK(l.draws_fin);
    CHECK_FALSE(l.clouds.has_value()); // the horizon is above the frame: no sky layer
    CHECK(l.horizon < 4);
    // A low camera sees its horizon: the sky layer's clouds, and the sun's glow where it reaches the frame.
    const view::Camera sky(
        {.target = {0, 0, 0}, .yaw = 52, .pitch = 12, .dist = 430, .width = 412, .height = 916, .cy = 440});
    const view::Layers ls = view::layers_of(sky, th, {.width = 412, .height = 916, .pitch = 12});
    CHECK(ls.horizon > 4);
    REQUIRE(ls.clouds.has_value());
    CHECK(ls.clouds.value_or(view::Clouds{}).t == doctest::Approx(0.62 - 0.4 * 0.25));
    CHECK(ls.sky_bottom == pymath::min(916.0, ls.horizon + 2));
    // At eye level: the 25-stop band around the horizon, no fin.
    const view::Camera low(
        {.target = {0, 1.6, 0}, .yaw = 52, .pitch = 4, .dist = 60, .width = 412, .height = 916, .cy = 440});
    const view::Layers e = view::layers_of(low, th, {.width = 412, .height = 916, .pitch = 4, .ground_y = 1.6});
    CHECK(e.eye);
    CHECK(e.haze.band);
    CHECK(e.haze.count == 25);
    CHECK_FALSE(e.draws_fin);
    CHECK_FALSE(e.cloud_shadows());
    // What the composite gets: the stops as the SVG prints them.
    const shaders::FrameLayers b = view::block_of(l, th, shaders::COMPOSITE_HAZE | shaders::COMPOSITE_VIGNETTE, 255);
    CHECK(b.steps.x == (shaders::COMPOSITE_HAZE | shaders::COMPOSITE_VIGNETTE));
    CHECK(b.steps.y == 255U);
    CHECK(b.haze.w == 17.0F);
    CHECK(static_cast<double>(b.haze_alphas[0].x) == doctest::Approx(view::decimals(l.haze.alphas[0], 3)));
    CHECK(b.vignette.x == doctest::Approx(0.2F));
}
