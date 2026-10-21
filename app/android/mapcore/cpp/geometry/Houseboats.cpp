// A houseboat in stage T (geometry/Houseboats.hpp).
#include "geometry/Houseboats.hpp"

#include "base/pymath/PyMath.hpp"

#include <array>
#include <cmath>
#include <vector>

namespace atlas::geometry {

namespace pm = atlas::pymath;

namespace {

/// draw_houseboat's walls(pp, y0, y1, base): per side of 0.2 m or more, the wall from y0 to y1, its corners as the port
/// lists them (never through quadAt), lit by its sample 0.2 m out from its middle.
void boat_walls(Builder& b, std::span<const V2> pp, double y0, double y1, std::uint32_t base, PrimKind kind) {
    const std::size_t m = pp.size();
    for (std::size_t i = 0; i < m; ++i) {
        const V2 a = pp[i];
        const V2 c = pp[(i + 1) % m];
        const double ex = c.x - a.x;
        const double ez = c.z - a.z;
        const double elen = hypot(ex, ez);
        if (elen < 0.2) continue;
        const V3 nrm = vnorm({ez, 0.0, -ex});
        const std::uint32_t face = b.face({.base = base,
                                           .normal = nrm,
                                           .test = {(a.x + c.x) / 2, (y0 + y1) / 2, (a.z + c.z) / 2},
                                           .lit = Lit::sun,
                                           .facing = true});
        const std::array<V3, 4> q{V3{a.x, y0, a.z}, V3{c.x, y0, c.z}, V3{c.x, y1, c.z}, V3{a.x, y1, a.z}};
        const std::uint16_t slot = Builder::slot(b.boat_slot++);
        b.edged(q, kind, static_cast<std::uint16_t>(prim_flag::weather | prim_flag::any), face, b.sample++, slot, slot,
                {.edge = static_cast<std::uint32_t>(i), .part = 0});
    }
}

/// A horizontal ring drawn whenever the sun is up and from either side (the deck, the cabin's roof).
void day_top(Builder& b, std::span<const V2> ring, double y, std::uint32_t base, PrimKind kind) {
    const V2 ce = centroid(ring);
    const std::uint32_t face =
        b.face({.base = base, .normal = {0.0, 1.0, 0.0}, .test = {ce.x, y, ce.z}, .lit = Lit::day, .facing = false});
    std::vector<V3> pts;
    pts.reserve(ring.size());
    for (const V2& p : ring) pts.emplace_back(p.x, y, p.z);
    const std::uint16_t slot = Builder::slot(b.boat_slot++);
    b.polygon(std::move(pts), ring, kind, static_cast<std::uint16_t>(prim_flag::weather | prim_flag::any), face,
              no_sample, slot, slot, std::abs(signed_area(ring)));
}

} // namespace

void houseboat(Builder& b, const Building& bl, const BuildingRegion& region) {
    const double wy = region.water_y;
    const std::span<const V2> poly = bl.poly;
    const V2 ce = centroid(poly);
    const double lo = wy - 0.15;
    const double deck = wy + 0.7;
    const double top = pm::max(deck + 1.6, (wy + 0.3) + bl.v.h);
    std::vector<V2> cab;
    std::vector<V2> roof;
    cab.reserve(poly.size());
    roof.reserve(poly.size());
    for (const V2& p : poly) {
        cab.emplace_back(ce.x + ((p.x - ce.x) * 0.84), ce.z + ((p.z - ce.z) * 0.8));
        roof.emplace_back(ce.x + ((p.x - ce.x) * 0.88), ce.z + ((p.z - ce.z) * 0.86));
    }
    boat_walls(b, poly, lo, deck, region.hull, PrimKind::hull_wall);
    day_top(b, poly, deck, region.deck, PrimKind::deck);
    boat_walls(b, cab, deck, top, bl.v.wall_rgb, PrimKind::cabin_wall);
    day_top(b, roof, top, bl.v.roof_rgb, PrimKind::cabin_roof);
}

} // namespace atlas::geometry
