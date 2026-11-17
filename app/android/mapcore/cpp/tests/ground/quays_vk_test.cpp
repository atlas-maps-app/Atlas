// The quay walls on the host's Vulkan (passes/Quays.cpp): at the Westerkerk home with the sun up, the ground drawn
// alone, each pixel whose samples the twin's quay faces cover is the latest face's colour there: the twin's faces (each
// a Camera.poly polygon, a wall's and its bands' grown by their .3 strokes' half width, the port's own polygons) at the
// standard 4x sample positions, the latest painter slot winning, against the frame's pixels.
//
// And at eye level before a wall of the Keizersgracht by day, the walls' courses (closeup.py's coursing): the frame
// drawn with them and without differs only along the twin's course lines (each `M x0 y0 l dx dy` of a path, its
// stroke's width), each changed pixel between the frame without them and their joints' colour over it at the path's
// opacity; a pixel a wide line covers whole is that blend; and from above (the home's view) no wall has courses.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "geometry/Ground.hpp"
#include "passes/Ground.hpp"
#include "style/Palette.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// The standard 4x sample positions within a pixel.
constexpr std::array<std::array<double, 2>, 4> samples{
    {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};

/// A twin quay face in device px: its polygons (a bands' path has several), its half stroke, its colour, its slot.
struct Face {
    std::vector<std::vector<std::array<double, 2>>> polys;
    double hw = 0;
    std::uint32_t colour = 0;
    std::uint32_t slot = 0;
};

/// How far x lies from the boundary of the convex polygon grown by hw.
double from_boundary(const std::vector<std::array<double, 2>>& p, double hw, double x, double y) {
    double nearest = 1e30;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& a = p[i];
        const auto& b = p[(i + 1) % p.size()];
        const double ex = b[0] - a[0];
        const double ey = b[1] - a[1];
        const double qx = x - a[0];
        const double qy = y - a[1];
        const double l2 = (ex * ex) + (ey * ey);
        const double t = l2 > 0 ? std::clamp(((qx * ex) + (qy * ey)) / l2, 0.0, 1.0) : 0.0;
        nearest = std::min(nearest, pymath::hypot(qx - (ex * t), qy - (ey * t)));
    }
    return std::abs(nearest - hw);
}

/// Whether x lies in the convex polygon or within hw of it.
bool covers(const std::vector<std::array<double, 2>>& p, double hw, double x, double y) {
    double twice = 0;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& a = p[i];
        const auto& b = p[(i + 1) % p.size()];
        twice += (a[0] * b[1]) - (b[0] * a[1]);
    }
    const double sgn = twice >= 0 ? 1.0 : -1.0;
    bool inside = true;
    double nearest = 1e30;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& a = p[i];
        const auto& b = p[(i + 1) % p.size()];
        const double ex = b[0] - a[0];
        const double ey = b[1] - a[1];
        const double qx = x - a[0];
        const double qy = y - a[1];
        if (sgn * ((ex * qy) - (ey * qx)) < 0) inside = false;
        const double l2 = (ex * ex) + (ey * ey);
        const double t = l2 > 0 ? std::clamp(((qx * ex) + (qy * ey)) / l2, 0.0, 1.0) : 0.0;
        nearest = std::min(nearest, pymath::hypot(qx - (ex * t), qy - (ey * t)));
    }
    return inside || (hw > 0 && nearest <= hw);
}

} // namespace

TEST_CASE("ground: a quay's wall, waterline and coping move with the user's quay walls, today's palette none") {
    geometry::GroundTile g;
    g.quay_colour = style::quay_walls_own;
    g.quay_coping = 0xB8B0A2U;
    g.quay_waterline = {.day = 0x2C2A22U, .night = 0x06070AU};
    const style::PaletteBlock none{};
    for (const bool night : {false, true}) {
        CAPTURE(night);
        for (const style::PaletteBlock* today : {static_cast<const style::PaletteBlock*>(nullptr), &none}) {
            const passes::QuayColours c = passes::quay_colours(g, today, night);
            CHECK(c.wall == g.quay_colour);
            CHECK(c.waterline == (night ? g.quay_waterline.night : g.quay_waterline.day));
            CHECK(c.coping == g.quay_coping);
        }
    }
    // the sum of a colour's channels: a lighter tone's is more
    const auto sum = [](std::uint32_t c) { return ((c >> 16U) & 0xFFU) + ((c >> 8U) & 0xFFU) + (c & 0xFFU); };
    style::PaletteBlock blue{};
    blue.colours.at(style::palette_slot::quay_walls) = 0x01405C80U;
    const passes::QuayColours b = passes::quay_colours(g, &blue, false);
    // the wall takes the user's colour exactly, its coping a lighter tone of it and its waterline a darker one
    CHECK(b.wall == 0x405C80U);
    CHECK(b.coping != g.quay_coping);
    CHECK(sum(b.coping) > sum(b.wall));
    CHECK(b.waterline != g.quay_waterline.day);
    CHECK(sum(b.waterline) < sum(b.wall));
    CHECK((b.coping & 0xFFU) > ((b.coping >> 16U) & 0xFFU)); // bluer than red, as the wall
    // another region's walls (Bergen's cut stone) move with them, as a tone of the user's
    g.quay_colour = 0x7F796EU;
    const std::uint32_t stone = passes::quay_colours(g, &blue, false).wall;
    CHECK(stone != g.quay_colour);
    CHECK(stone != b.wall);
}

TEST_CASE("ground: the quay walls are the twin's faces, grown by their strokes, sample for sample") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the quay walls are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    std::vector<Face> faces;
    const auto a = test::draw_ground(*session, camera, test::sunny(), dir, "amsterdam",
                                     [&](passes::Ground& g, const frame::FrameState& state) {
                                         twin::Stream s;
                                         g.emit(state, s, passes::Ground::phase_opaque);
                                         const twin::Decoded d = twin::decode(s.bytes());
                                         REQUIRE(d.error.empty());
                                         const float density = state.block.camera.frame.z;
                                         const std::uint32_t first = g.slot(geometry::group::quay, 0, 0);
                                         const auto count = static_cast<std::uint32_t>(g.decided().quays.size());
                                         for (const twin::Element& e : d.document.elements) {
                                             if (e.layer != twin::Layer::ground || e.kind != twin::Op::path) continue;
                                             if (e.slot < first || e.slot >= first + count) continue;
                                             Face f;
                                             f.slot = e.slot;
                                             f.colour = e.style.fill.argb;
                                             f.hw = e.style.stroke.kind == twin::Paint::Kind::none
                                                        ? 0.0
                                                        : static_cast<double>(e.style.stroke_width * density) / 2;
                                             std::size_t start = 0;
                                             for (const std::uint32_t end : e.subpath_ends) {
                                                 std::vector<std::array<double, 2>> p;
                                                 p.reserve(end - start);
                                                 for (std::size_t k = start; k < end; ++k) {
                                                     p.push_back({static_cast<double>(e.points[k].x * density),
                                                                  static_cast<double>(e.points[k].y * density)});
                                                 }
                                                 f.polys.push_back(std::move(p));
                                                 start = end;
                                             }
                                             faces.push_back(std::move(f));
                                         }
                                     });
    ATLAS_REQUIRE(a.has_value(), "the home was not drawn");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE(faces.size() > 50);
    std::ranges::stable_sort(faces, {}, &Face::slot);
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    // each sample's latest face (its index + 1; 0: none)
    std::vector<std::array<std::uint32_t, 4>> latest(std::size_t{w} * h, {0, 0, 0, 0});
    for (std::uint32_t fi = 0; fi < faces.size(); ++fi) {
        const Face& f = faces[fi];
        for (const auto& p : f.polys) {
            double x0 = p[0][0];
            double x1 = x0;
            double y0 = p[0][1];
            double y1 = y0;
            for (const auto& q : p) {
                x0 = std::min(x0, q[0]);
                x1 = std::max(x1, q[0]);
                y0 = std::min(y0, q[1]);
                y1 = std::max(y1, q[1]);
            }
            const double grow = f.hw + 1;
            for (auto y = static_cast<std::int64_t>(std::max(0.0, std::floor(y0 - grow)));
                 y <= static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1 + grow))); ++y) {
                for (auto x = static_cast<std::int64_t>(std::max(0.0, std::floor(x0 - grow)));
                     x <= static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1 + grow))); ++x) {
                    auto& l = latest[(static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)];
                    for (std::size_t k = 0; k < samples.size(); ++k) {
                        if (covers(p, f.hw, static_cast<double>(x) + samples[k][0],
                                   static_cast<double>(y) + samples[k][1])) {
                            l[k] = fi + 1;
                        }
                    }
                }
            }
        }
    }
    // a pixel whose four samples one face covers is that face's colour, but where a sample lies within a print step
    // (0.1 CSS px) of a face's boundary: at rest a corner the vertex stage projects in float may print a step off the
    // twin's double where its value lies within float rounding of the print's midpoint
    const double step = 0.1 * static_cast<double>(camera.density);
    const auto on_boundary = [&](std::size_t i) {
        for (const auto& k : samples) {
            const auto row = static_cast<std::uint32_t>(i / w);
            const double x = static_cast<double>(i % w) + k[0];
            const double y = static_cast<double>(row) + k[1];
            for (const Face& f : faces) {
                for (const auto& p : f.polys) {
                    if (from_boundary(p, f.hw, x, y) < step) return true;
                }
            }
        }
        return false;
    };
    std::size_t whole = 0;
    std::size_t wrong = 0;
    std::size_t boundary = 0;
    std::string first;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        const auto& l = latest[i];
        if (l[0] == 0 || l[1] != l[0] || l[2] != l[0] || l[3] != l[0]) continue;
        ++whole;
        const std::uint32_t c = faces[l[0] - 1].colour;
        const std::uint8_t* px = &a->pixels.rgba[i * 4];
        const int dr = std::abs(static_cast<int>(px[0]) - static_cast<int>((c >> 16U) & 0xFFU));
        const int dg = std::abs(static_cast<int>(px[1]) - static_cast<int>((c >> 8U) & 0xFFU));
        const int db = std::abs(static_cast<int>(px[2]) - static_cast<int>(c & 0xFFU));
        if (std::max({dr, dg, db}) > 1) {
            if (on_boundary(i)) {
                ++boundary;
                continue;
            }
            if (wrong == 0) {
                first = std::format("pixel {} {}: {} {} {}, the face's #{:06x}", i % w, i / w, int(px[0]), int(px[1]),
                                    int(px[2]), c & 0xFFFFFFU);
            }
            ++wrong;
        }
    }
    MESSAGE("home sunny: " << faces.size() << " quay faces in the twin; " << whole << " pixels wholly one face's; "
                           << boundary << " of them another's by a sample within a print step of a boundary, " << wrong
                           << " otherwise not its colour");
    CHECK(whole > 2000);
    CHECK_MESSAGE(wrong == 0, first);
    CHECK(boundary <= whole / 1000);
}

TEST_CASE("ground: at eye level a quay wall's courses are the twin's lines over the wall, at their paths' opacity") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the quay walls are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    // a wall of the Keizersgracht (the bundled Westerkerk's quay edge from (107.9, -16.0) to (86.6, 24.1), east of the
    // church) seen square on from 25 m, 6° down: the gaze level enough (`cam.f[1] > -0.25`), a brick's course 3.5 px.
    // From the water's side the wall fills the frame's middle; from the land's it faces away, so of the two bearings
    // square to it the test takes the one that shows its courses
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const auto toward = [&](double bearing) {
        return test::s1_camera(52.3746 - (4.05 / 110540.0), 4.8840 + (97.25 / kx), bearing, 84.0, 25.0);
    };
    /// A course line in device px, its half width, and its path's joints and opacity.
    struct Line {
        std::array<double, 2> a;
        std::array<double, 2> b;
        double hw = 0;
        std::uint32_t colour = 0;
        double opacity = 0;
    };
    std::vector<Line> lines;
    std::size_t paths = 0;
    std::size_t decided = 0;
    const auto look = [&](const style::CameraState& camera) {
        lines.clear();
        paths = 0;
        decided = 0;
        return test::draw_ground(
            *session, camera, test::sunny(), dir, "amsterdam", [&](passes::Ground& g, const frame::FrameState& state) {
                twin::Stream s;
                g.emit(state, s, passes::Ground::phase_opaque);
                const twin::Decoded d = twin::decode(s.bytes());
                REQUIRE(d.error.empty());
                const float density = state.block.camera.frame.z;
                const std::uint32_t first = g.slot(geometry::group::quay, 0, 0);
                const auto count = static_cast<std::uint32_t>(g.decided().quays.size());
                for (const passes::QuayFace& f : g.decided().quays) {
                    if (f.kind == passes::QuayFace::Kind::courses) ++decided;
                }
                for (const twin::Element& e : d.document.elements) {
                    if (e.layer != twin::Layer::ground || e.kind != twin::Op::path) continue;
                    if (e.slot < first || e.slot >= first + count) continue;
                    if (e.style.fill.kind != twin::Paint::Kind::none) continue; // a face
                    REQUIRE(e.style.stroke.kind == twin::Paint::Kind::colour);
                    ++paths;
                    std::size_t start = 0;
                    for (const std::uint32_t end : e.subpath_ends) {
                        REQUIRE(end - start == 2U);
                        lines.push_back({.a = {static_cast<double>(e.points[start].x * density),
                                               static_cast<double>(e.points[start].y * density)},
                                         .b = {static_cast<double>(e.points[start + 1].x * density),
                                               static_cast<double>(e.points[start + 1].y * density)},
                                         .hw = static_cast<double>(e.style.stroke_width * density) / 2,
                                         .colour = e.style.stroke.argb,
                                         .opacity = static_cast<double>(e.node.opacity)});
                        start = end;
                    }
                }
            });
    };
    style::CameraState camera = toward(118.0);
    auto with = look(camera);
    ATLAS_REQUIRE(with.has_value(), "the canal was not drawn with its courses");
    if (lines.empty()) {
        camera = toward(298.0);
        with = look(camera);
        ATLAS_REQUIRE(with.has_value(), "the canal was not drawn with its courses");
    }
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const auto without = test::draw_ground(*session, camera, test::sunny(), dir, "amsterdam", {}, 40,
                                           [](passes::Ground& g) { g.draw_courses(false); });
    ATLAS_REQUIRE(without.has_value(), "the canal was not drawn without its courses");
    // the frame is 7 m of the wall wide at 25 m: two of its 5 m chunks show their lines (the others' lie beside it)
    REQUIRE(decided >= 2);
    REQUIRE(paths >= 2);
    REQUIRE(lines.size() >= 20);
    const gpu::Pixels& a = with->pixels;
    const gpu::Pixels& b = without->pixels;
    const auto w = static_cast<std::int64_t>(a.width);
    const auto h = static_cast<std::int64_t>(a.height);
    // each pixel's nearest line (its index + 1) within its half width and a pixel and a half, and whether a line
    // covers the pixel whole
    std::vector<std::uint32_t> near(static_cast<std::size_t>(w * h), 0);
    std::vector<double> most(near.size(), 0.0); // the most opaque of the paths whose lines reach the pixel
    std::vector<std::uint32_t> whole(near.size(), 0);
    for (std::uint32_t li = 0; li < lines.size(); ++li) {
        const Line& l = lines[li];
        const double reach = l.hw + 1.5;
        const auto x0 =
            std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(std::min(l.a[0], l.b[0]) - reach)));
        const auto x1 =
            std::min<std::int64_t>(w - 1, static_cast<std::int64_t>(std::ceil(std::max(l.a[0], l.b[0]) + reach)));
        const auto y0 =
            std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(std::min(l.a[1], l.b[1]) - reach)));
        const auto y1 =
            std::min<std::int64_t>(h - 1, static_cast<std::int64_t>(std::ceil(std::max(l.a[1], l.b[1]) + reach)));
        const double ex = l.b[0] - l.a[0];
        const double ey = l.b[1] - l.a[1];
        const double l2 = (ex * ex) + (ey * ey);
        for (std::int64_t y = y0; y <= y1; ++y) {
            for (std::int64_t x = x0; x <= x1; ++x) {
                const double qx = (static_cast<double>(x) + 0.5) - l.a[0];
                const double qy = (static_cast<double>(y) + 0.5) - l.a[1];
                const double t = l2 > 0 ? ((qx * ex) + (qy * ey)) / l2 : 0.0;
                const double tc = std::clamp(t, 0.0, 1.0);
                const double dist = pymath::hypot(qx - (ex * tc), qy - (ey * tc));
                const auto i = static_cast<std::size_t>((y * w) + x);
                if (dist <= reach) {
                    near[i] = li + 1;
                    most[i] = std::max(most[i], l.opacity);
                }
                // whole: the pixel's box inside the stroke, away from its ends
                const double along = t * std::sqrt(l2);
                if (dist <= l.hw - 0.75 && along >= 1.0 && along <= std::sqrt(l2) - 1.0) whole[i] = li + 1;
            }
        }
    }
    std::size_t changed = 0;
    std::size_t astray = 0;
    std::size_t beyond = 0;
    std::size_t covered = 0;
    std::size_t blended = 0;
    std::string first;
    for (std::size_t i = 0; i < near.size(); ++i) {
        bool differs = false;
        for (std::size_t k = 0; k < 3; ++k) differs = differs || a.rgba[(i * 4) + k] != b.rgba[(i * 4) + k];
        if (differs) {
            ++changed;
            if (near[i] == 0) {
                if (astray == 0)
                    first = std::format("pixel {} {} changed away from every line", i % a.width, i / a.width);
                ++astray;
                continue;
            }
        }
        if (near[i] == 0) continue;
        const Line& l = lines[(whole[i] != 0 ? whole[i] : near[i]) - 1];
        // between the frame without them and the joints over it at the path's opacity (the most opaque path's where
        // two paths' lines reach the pixel); a pixel a line covers whole is its own path's blend
        bool within = true;
        bool full = true;
        for (std::size_t k = 0; k < 3; ++k) {
            const auto under = static_cast<double>(b.rgba[(i * 4) + k]);
            const auto joint = static_cast<double>((l.colour >> (16U - (8U * k))) & 0xFFU);
            const double bound = under + ((joint - under) * most[i]);
            const auto got = static_cast<double>(a.rgba[(i * 4) + k]);
            within = within && got >= std::min(under, bound) - 1.5 && got <= std::max(under, bound) + 1.5;
            full = full && std::abs(got - (under + ((joint - under) * l.opacity))) <= 1.5;
        }
        if (differs && !within) {
            if (beyond == 0) first = std::format("pixel {} {} beyond its line's blend", i % a.width, i / a.width);
            ++beyond;
        }
        if (whole[i] != 0) {
            ++covered;
            if (full) ++blended;
        }
    }
    MESSAGE(std::format(
        "the Keizersgracht at eye level, bearing {}: {} courses' paths decided, {} in the twin with {} lines; {} "
        "pixels changed, {} away from every line, {} beyond their line's blend; {} pixels a line "
        "covers whole, {} its joints at the path's opacity over the wall",
        camera.bearing, decided, paths, lines.size(), changed, astray, beyond, covered, blended));
    CHECK(changed > 2000);
    CHECK_MESSAGE(astray == 0, first);
    CHECK_MESSAGE(beyond == 0, first);
    // a covered pixel another face hides (the coping, the waterline, a nearer wall) is not the blend: most are
    CHECK(covered > 200);
    CHECK(blended >= covered * 3 / 4);
    // from above no wall has courses
    const auto home = test::draw_ground(*session, test::home_camera(), test::sunny(), dir, "amsterdam",
                                        [&](passes::Ground& g, const frame::FrameState&) {
                                            for (const passes::QuayFace& f : g.decided().quays) {
                                                CHECK(f.kind != passes::QuayFace::Kind::courses);
                                            }
                                        });
    ATLAS_REQUIRE(home.has_value(), "the home was not drawn");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}

#endif
