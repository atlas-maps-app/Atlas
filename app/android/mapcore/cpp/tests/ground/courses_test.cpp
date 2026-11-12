// The quay walls' courses at eye level (passes/Quays.cpp's course_paths and course_lines) held to a fresh run of
// render/closeup.py's coursing: four walls and cameras (a brick wall and a cut stone one seen across the water, a
// brick wall running past the camera through the near plane, and a long one seen along it from far), each path's
// stroke width and opacity as the port prints them, its lines' count, the sums of their printed numbers in tenths
// (each line `M x0 y0 l dx dy`), and its first and last lines. The rows are the port's own output: a script that
// imports render/amsterdam.py, calls CU.coursing(cam, a, b, b[0] - a[0], b[1] - a[1], el, n3, WY + 0.38, -0.2,
// course) with n3 the wall's normal toward the camera, and prints them (12 November 2026).
#include "base/pymath/PyMath.hpp"
#include "passes/Ground.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

using namespace atlas;

namespace {

/// A path of the port's: its case, its stroke, its width and opacity as printed, its lines, the sums of their printed
/// x0, y0, dx and dy in tenths, and its first and last lines in tenths.
struct Row {
    std::string_view name;
    std::uint32_t colour;
    double width;
    double opacity;
    std::size_t lines;
    std::array<std::int64_t, 4> sums;
    std::array<std::int64_t, 4> first;
    std::array<std::int64_t, 4> last;
};

constexpr std::array rows{
    // brick: 2 paths
    Row{.name = "brick",
        .colour = 0xE2D9CBU,
        .width = 0.58,
        .opacity = 0.23,
        .lines = 14,
        .sums = {-5656, 78928, 33901, -188},
        .first = {-399, 5848, 2416, -14},
        .last = {-409, 5427, 2427, -13}},
    Row{.name = "brick",
        .colour = 0xE2D9CBU,
        .width = 0.58,
        .opacity = 0.23,
        .lines = 14,
        .sums = {28245, 78740, 33619, -186},
        .first = {2018, 5833, 2396, -14},
        .last = {2017, 5415, 2406, -12}},
    // stone: 2 paths
    Row{.name = "stone",
        .colour = 0x5E5548U,
        .width = 0.42,
        .opacity = 0.26,
        .lines = 35,
        .sums = {19911, 215281, 11968, -6781},
        .first = {-382, 6519, 2400, -17},
        .last = {1845, 5584, 0, -191}},
    Row{.name = "stone",
        .colour = 0x5E5548U,
        .width = 0.42,
        .opacity = 0.26,
        .lines = 35,
        .sums = {103947, 214743, 12025, -6754},
        .first = {2018, 6502, 2380, -17},
        .last = {4249, 5571, 4, -190}},
    // beside: 7 paths
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 0.39,
        .opacity = 0.08,
        .lines = 14,
        .sums = {36288, 61851, 1036, 1713},
        .first = {2592, 4554, 74, 139},
        .last = {2592, 4282, 74, 106}},
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 0.44,
        .opacity = 0.12,
        .lines = 14,
        .sums = {37327, 63562, 1330, 2188},
        .first = {2666, 4692, 95, 177},
        .last = {2667, 4388, 95, 135}},
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 0.51,
        .opacity = 0.17,
        .lines = 14,
        .sums = {38656, 65751, 1756, 2893},
        .first = {2760, 4870, 125, 234},
        .last = {2762, 4523, 126, 179}},
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 0.59,
        .opacity = 0.24,
        .lines = 14,
        .sums = {40411, 68644, 2432, 4006},
        .first = {2886, 5104, 173, 324},
        .last = {2887, 4702, 174, 248}},
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 0.72,
        .opacity = 0.30,
        .lines = 14,
        .sums = {42843, 72651, 3589, 5912},
        .first = {3059, 5428, 256, 478},
        .last = {3061, 4950, 257, 366}},
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 0.92,
        .opacity = 0.30,
        .lines = 14,
        .sums = {46432, 78562, 5829, 9604},
        .first = {3315, 5907, 415, 777},
        .last = {3318, 5316, 418, 595}},
    Row{.name = "beside",
        .colour = 0xE2D9CBU,
        .width = 1.25,
        .opacity = 0.30,
        .lines = 14,
        .sums = {52262, 88166, 11122, 18321},
        .first = {3730, 6683, 791, 1480},
        .last = {3736, 5911, 798, 1136}},
    // far: 6 paths
    Row{.name = "far",
        .colour = 0xE2D9CBU,
        .width = 0.57,
        .opacity = 0.22,
        .lines = 28,
        .sums = {-10664, 176178, 14508, -11132},
        .first = {-378, 6767, 517, -476},
        .last = {-384, 5816, 519, -319}},
    Row{.name = "far",
        .colour = 0xE2D9CBU,
        .width = 0.48,
        .opacity = 0.15,
        .lines = 28,
        .sums = {3843, 165045, 10361, -7950},
        .first = {139, 6291, 369, -340},
        .last = {135, 5498, 371, -227}},
    Row{.name = "far",
        .colour = 0xE2D9CBU,
        .width = 0.42,
        .opacity = 0.10,
        .lines = 28,
        .sums = {14204, 157096, 7769, -5963},
        .first = {509, 5950, 277, -255},
        .last = {506, 5270, 278, -171}},
    Row{.name = "far",
        .colour = 0xE2D9CBU,
        .width = 0.37,
        .opacity = 0.07,
        .lines = 28,
        .sums = {21974, 151134, 6048, -4637},
        .first = {786, 5695, 216, -199},
        .last = {784, 5100, 216, -133}},
    Row{.name = "far",
        .colour = 0xE2D9CBU,
        .width = 0.35,
        .opacity = 0.04,
        .lines = 28,
        .sums = {28016, 146498, 4840, -3710},
        .first = {1001, 5497, 172, -159},
        .last = {1000, 4967, 173, -106}},
    Row{.name = "far",
        .colour = 0xE2D9CBU,
        .width = 0.35,
        .opacity = 0.01,
        .lines = 28,
        .sums = {32851, 142790, 3948, -3035},
        .first = {1174, 5338, 141, -130},
        .last = {1173, 4861, 141, -87}},
};

/// A case: its camera, its wall's ends, its water's level and its course.
struct Case {
    std::string_view name;
    view::CameraSpec camera;
    std::array<double, 2> a;
    std::array<double, 2> b;
    double wy;
    double course;
};

[[nodiscard]] view::CameraSpec spec(view::Vec3 target, double yaw, double pitch, double dist) {
    return {.target = target,
            .yaw = yaw,
            .pitch = pitch,
            .dist = dist,
            .fov = 36.0,
            .width = 412,
            .height = 916,
            .cx = 206.0,
            .cy = 440.0};
}

/// A printed number in tenths ('%.1f').
[[nodiscard]] std::int64_t tenths(double v) { return std::llround(passes::printed(v, 1) * 10.0); }

} // namespace

TEST_CASE("ground: a quay wall's courses at eye level are closeup.py's coursing, path by path and line by line") {
    const std::array<Case, 4> cases{{
        {.name = "brick",
         .camera = spec({2.0, 1.2, -3.0}, 20.0, 8.0, 30.0),
         .a = {-12.3, -4.1},
         .b = {9.4, 3.2},
         .wy = -1.5,
         .course = 0.0625},
        {.name = "stone",
         .camera = spec({2.0, 1.2, -3.0}, 20.0, 8.0, 30.0),
         .a = {-12.3, -4.1},
         .b = {9.4, 3.2},
         .wy = -3.2,
         .course = 0.45},
        {.name = "beside",
         .camera = spec({0.0, 1.5, 0.0}, 0.0, 4.0, 12.0),
         .a = {1.6, -30.0},
         .b = {1.9, 25.0},
         .wy = -1.5,
         .course = 0.0625},
        {.name = "far",
         .camera = spec({0.0, 1.0, 0.0}, 75.0, 2.0, 60.0),
         .a = {-40.0, 6.0},
         .b = {38.0, -11.0},
         .wy = -2.4,
         .course = 0.0625},
    }};
    std::size_t row = 0;
    for (const Case& c : cases) {
        CAPTURE(std::string(c.name));
        const view::Camera cam(c.camera);
        // the wall's normal toward the camera
        const double ex = c.b[0] - c.a[0];
        const double ez = c.b[1] - c.a[1];
        const double elen = pymath::hypot(ex, ez);
        double nx = ez / elen;
        double nz = -ex / elen;
        const double mx = (c.a[0] + c.b[0]) / 2;
        const double mz = (c.a[1] + c.b[1]) / 2;
        if ((nx * (cam.eye().x - mx)) + (nz * (cam.eye().z - mz)) <= 0) {
            nx = -nx;
            nz = -nz;
        }
        const passes::QuayFace wall{.ax = c.a[0], .az = c.a[1], .bx = c.b[0], .bz = c.b[1]};
        std::vector<passes::QuayFace> paths;
        passes::course_paths(cam, wall, c.wy, c.course, paths);
        std::vector<passes::CourseLine> lines;
        for (const passes::QuayFace& p : paths) {
            CHECK(p.kind == passes::QuayFace::Kind::courses);
            lines.clear();
            passes::course_lines(cam, p, c.wy, nx, nz, lines);
            if (lines.empty()) continue; // `if segs`: a chunk behind the camera has no path
            REQUIRE(row < rows.size());
            const Row& want = rows[row++];
            CAPTURE(row);
            CHECK(want.name == c.name);
            CHECK(p.colour == want.colour);
            CHECK(p.width == want.width);
            CHECK(p.opacity == want.opacity);
            REQUIRE(lines.size() == want.lines);
            std::array<std::int64_t, 4> sums{};
            const auto numbers = [](const passes::CourseLine& l) {
                return std::array<std::int64_t, 4>{tenths(l.pa.x), tenths(l.pa.y), tenths(l.pb.x - l.pa.x),
                                                   tenths(l.pb.y - l.pa.y)};
            };
            for (const passes::CourseLine& l : lines) {
                const std::array<std::int64_t, 4> n = numbers(l);
                for (std::size_t k = 0; k < 4; ++k) sums[k] += n[k];
            }
            CHECK(sums == want.sums);
            CHECK(numbers(lines.front()) == want.first);
            CHECK(numbers(lines.back()) == want.last);
        }
        CHECK((row == rows.size() || rows[row].name != c.name)); // every path of the port's was met
    }
    CHECK(row == rows.size());
}

TEST_CASE("ground: a wall shows no courses where they would be under 1.6 px, where it is short or low") {
    // 110 m off, a brick's course is 0.8 px: none; a stone's 45 cm is 5.8 px: every chunk
    const view::Camera far(spec({0.0, 1.0, 0.0}, 0.0, 3.0, 110.0));
    const passes::QuayFace wall{.ax = -10.0, .az = 0.0, .bx = 10.0, .bz = 0.0};
    std::vector<passes::QuayFace> paths;
    passes::course_paths(far, wall, -1.5, 0.0625, paths);
    CHECK(paths.empty());
    passes::course_paths(far, wall, -3.0, 0.45, paths);
    REQUIRE(paths.size() == 4U); // 20 m in chunks of 5 m
    CHECK(paths.front().chunks == 4U);
    CHECK(paths.back().chunk == 3U);
    CHECK(paths.front().opacity == 0.26); // the stone's whole opacity from 3.8 px on
    // a piece 0.2 m long has none, nor a wall whose courses would span under 0.3 m (the water 0.8 m down)
    const view::Camera near(spec({0.0, 1.0, 0.0}, 0.0, 3.0, 12.0));
    paths.clear();
    passes::course_paths(near, {.ax = 0.0, .az = 0.0, .bx = 0.2, .bz = 0.0}, -1.5, 0.0625, paths);
    CHECK(paths.empty());
    passes::course_paths(near, wall, -0.8, 0.0625, paths);
    CHECK(paths.empty());
    passes::course_paths(near, wall, -1.5, 0.0625, paths);
    CHECK(paths.size() == 4U);
}
