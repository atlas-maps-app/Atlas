// The cells' edge (shaders/building/building.slangh's building_outline, compiled as C++): a polygon filled and stroked
// in one colour, 0.3 px wide with round joins, is the polygon dilated by 0.15 px, its corners' arcs included (the
// Minkowski sum of the polygon and a disc). The outline of 16 points must lie within r · tan²(phi / (4 per)) of that
// shape's boundary at every corner angle, needles included, and a dense grid of points must fall inside the outline
// exactly where they fall inside the true shape, but for points that close to its boundary.
#include "tests/base/require.hpp"
#include "twin/building/BuildingShader.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <random>
#include <vector>

namespace {

namespace sh = atlas::shaders;
using atlas::hlsl::float2;

struct P {
    double x;
    double y;
};

/// The distance from p to segment ab.
double segment_distance(P p, P a, P b) {
    const double dx = b.x - a.x;
    const double dy = b.y - a.y;
    const double l2 = (dx * dx) + (dy * dy);
    double t = l2 > 0 ? (((p.x - a.x) * dx) + ((p.y - a.y) * dy)) / l2 : 0.0;
    t = std::clamp(t, 0.0, 1.0);
    const double qx = a.x + (dx * t) - p.x;
    const double qy = a.y + (dy * t) - p.y;
    return std::sqrt((qx * qx) + (qy * qy));
}

/// Whether p lies inside a convex polygon (either winding).
bool inside_convex(P p, const std::vector<P>& poly) {
    double sign = 0;
    for (std::size_t i = 0; i < poly.size(); ++i) {
        const P a = poly[i];
        const P b = poly[(i + 1) % poly.size()];
        const double c = ((b.x - a.x) * (p.y - a.y)) - ((b.y - a.y) * (p.x - a.x));
        if (c == 0) continue;
        if (sign == 0) sign = c;
        if ((c > 0) != (sign > 0)) return false;
    }
    return true;
}

/// The signed distance of p from the polygon dilated by r (negative inside it).
double dilated_distance(P p, const std::vector<P>& poly, double r) {
    double d = 1e300;
    for (std::size_t i = 0; i < poly.size(); ++i)
        d = std::min(d, segment_distance(p, poly[i], poly[(i + 1) % poly.size()]));
    if (poly.size() >= 3 && inside_convex(p, poly)) return -d - r;
    return d - r;
}

/// The outline of a shape of these corners, as the vertex stage makes it.
std::vector<P> outline_of(const std::vector<P>& corners, float r) {
    sh::BuildingShape s = sh::building_nothing();
    s.count = static_cast<std::uint32_t>(corners.size());
    for (std::uint32_t i = 0; i < s.count; ++i) {
        const float2 v = {static_cast<float>(corners[i].x), static_cast<float>(corners[i].y)};
        s = sh::building_with_corner(s, i, v, 1.0F);
    }
    std::vector<P> out;
    for (std::uint32_t v = 0; v < sh::BUILDING_EDGED_VERTICES; ++v) {
        const float2 o = sh::building_outline(s, v, r);
        out.push_back({static_cast<double>(o.x), static_cast<double>(o.y)});
    }
    return out;
}

/// Whether p lies inside the outline (a convex fan from its first point, as the index buffer draws it).
bool inside_outline(P p, const std::vector<P>& o) {
    for (std::size_t t = 1; t + 1 < o.size(); ++t) {
        const P a = o[0];
        const P b = o[t];
        const P c = o[t + 1];
        const double d1 = ((b.x - a.x) * (p.y - a.y)) - ((b.y - a.y) * (p.x - a.x));
        const double d2 = ((c.x - b.x) * (p.y - b.y)) - ((c.y - b.y) * (p.x - b.x));
        const double d3 = ((a.x - c.x) * (p.y - c.y)) - ((a.y - c.y) * (p.x - c.x));
        const bool neg = d1 < 0 || d2 < 0 || d3 < 0;
        const bool pos = d1 > 0 || d2 > 0 || d3 > 0;
        if (!(neg && pos)) return true;
    }
    return false;
}

struct Measured {
    double worst = 0;      ///< the outline's points' and chords' farthest distance from the true boundary, px
    std::size_t grid = 0;  ///< grid points tested
    std::size_t wrong = 0; ///< inside one shape and not the other, farther than `band` from the boundary
};

Measured measure(const std::vector<P>& corners, double r, double band) {
    Measured m;
    const std::vector<P> o = outline_of(corners, static_cast<float>(r));
    for (std::size_t i = 0; i < o.size(); ++i) {
        const P a = o[i];
        const P b = o[(i + 1) % o.size()];
        for (int k = 0; k <= 8; ++k) {
            const double t = k / 8.0;
            const P q{a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t)};
            m.worst = std::max(m.worst, std::abs(dilated_distance(q, corners, r)));
        }
    }
    double x0 = 1e300;
    double y0 = 1e300;
    double x1 = -1e300;
    double y1 = -1e300;
    for (const P& c : corners) {
        x0 = std::min(x0, c.x);
        y0 = std::min(y0, c.y);
        x1 = std::max(x1, c.x);
        y1 = std::max(y1, c.y);
    }
    const double step = 0.01;
    for (double y = y0 - (2 * r); y <= y1 + (2 * r); y += step) {
        for (double x = x0 - (2 * r); x <= x1 + (2 * r); x += step) {
            const P p{x, y};
            const double d = dilated_distance(p, corners, r);
            if (std::abs(d) <= band) continue;
            ++m.grid;
            if ((d < 0) != inside_outline(p, o)) ++m.wrong;
        }
    }
    return m;
}

} // namespace

TEST_CASE("buildings: a cell's edge is its polygon dilated by 0.15 px with round joins, needles included") {
    const double r = 0.15 * (1080.0 / 412.0); // S1's density: the edge's half width in device px
    // a right-angled cell, a parallelogram, a needle (a wall seen edge-on), a triangle and a pentagon (near-clipped)
    const std::vector<std::vector<P>> shapes{
        {{10, 10}, {20, 10}, {20, 14}, {10, 14}},
        {{10, 10}, {30, 12}, {31, 16}, {11, 14}},
        {{10, 10}, {40, 10.6}, {40, 11.1}, {10, 10.4}},
        {{10, 10}, {14, 25}, {9, 11}},
        {{10, 10}, {18, 9}, {22, 14}, {16, 20}, {9, 16}},
        {{10, 10}, {10.05, 10.02}, {40, 30}, {39.9, 30}}, // two needles' ends
    };
    double worst = 0;
    for (const auto& s : shapes) {
        const Measured m = measure(s, r, 0.02);
        MESSAGE(s.size() << " corners: the outline within " << m.worst << " px of the true boundary; " << m.wrong
                         << " of " << m.grid << " grid points on the wrong side, farther than 0.02 px from it");
        CHECK(m.wrong == 0);
        worst = std::max(worst, m.worst);
    }
    // r · tan²(phi / 16) at most, for a needle's corner (phi up to pi) at four points a corner: 0.016 px at S1;
    // a pentagon's three a corner, r · tan²(pi / 12)
    CHECK(worst <= r * std::tan(3.14159265358979 / 12) * std::tan(3.14159265358979 / 12) + 1e-4);
    // random convex quads, the cells of walls seen at any angle
    std::mt19937_64 rng(20261019);
    std::uniform_real_distribution<double> u(-1.0, 1.0);
    double worst_random = 0;
    std::size_t wrong = 0;
    for (int k = 0; k < 400; ++k) {
        // a parallelogram from two edges, some of them nearly parallel (needles)
        const P a{50 + (10 * u(rng)), 50 + (10 * u(rng))};
        const P e{30 * u(rng), 30 * u(rng)};
        const double squeeze = k % 4 == 0 ? 0.002 : (k % 4 == 1 ? 0.05 : 1.0);
        const P f{(-e.y * squeeze) + (5 * u(rng) * squeeze), (e.x * squeeze) + (5 * u(rng) * squeeze)};
        const std::vector<P> quad{
            a, {a.x + e.x, a.y + e.y}, {a.x + e.x + f.x, a.y + e.y + f.y}, {a.x + f.x, a.y + f.y}};
        const Measured m = measure(quad, r, 0.02);
        worst_random = std::max(worst_random, m.worst);
        wrong += m.wrong;
    }
    MESSAGE("400 random cells (a quarter needles): the outline within " << worst_random << " px of the true boundary; "
                                                                        << wrong << " grid points on the wrong side");
    CHECK(wrong == 0);
    CHECK(worst_random <= r * std::tan(3.14159265358979 / 16) * std::tan(3.14159265358979 / 16) + 1e-4);
}
