// The quay walls on the host's Vulkan (passes/Quays.cpp): at the Westerkerk home with the sun up, the ground drawn
// alone, each pixel whose samples the twin's quay faces cover is the latest face's colour there: the twin's faces (each
// a Camera.poly polygon, a wall's and its bands' grown by their .3 strokes' half width, the port's own polygons) at the
// standard 4x sample positions, the latest painter slot winning, against the frame's pixels.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "passes/Ground.hpp"
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

#endif
