// A building's roof in stage T (geometry/Roofs.hpp).
#include "geometry/Roofs.hpp"

#include "base/pymath/PyMath.hpp"

#include <array>
#include <cmath>
#include <optional>
#include <vector>

namespace atlas::geometry {

namespace pm = atlas::pymath;

namespace {

/// A face's normal turned up where it points down (`if nr[1] < 0: nr = -nr`).
[[nodiscard]] V3 upward(const V3& nr) noexcept { return nr.y < 0 ? neg(nr) : nr; }

/// The face of three points' plane: vnorm(vcross(t1 - t0, t2 - t0)), turned up.
[[nodiscard]] V3 normal_of(const V3& t0, const V3& t1, const V3& t2) noexcept {
    return upward(vnorm(vcross(vsub(t1, t0), vsub(t2, t0))));
}

/// The area of a planar polygon in space: its plan's area over the cosine of its slope.
[[nodiscard]] double area_in_space(std::span<const V2> plan, const V3& normal) noexcept {
    const double ny = std::abs(normal.y);
    return ny > 0 ? std::abs(signed_area(plan)) / ny : 0.0;
}

/// The footprint at height h, its corners in the port's order.
[[nodiscard]] std::vector<V3> at_height(std::span<const V2> poly, double h) {
    std::vector<V3> ring;
    ring.reserve(poly.size());
    for (const V2& p : poly) ring.emplace_back(p.x, h, p.z);
    return ring;
}

/// The top drawn when the eye is above it: the ring at h, facing up (vdot((0, 1, 0), C - p) > 0 is the port's
/// cam.C[1] > h), lit by its sample 0.05 m above its centroid, whatever the sun's side (`not night and sh.lit(...)`).
void top(Builder& b, std::span<const V2> ring, double h, PrimKind kind, std::uint16_t variant, std::uint32_t sample,
         std::uint32_t base) {
    const V2 ce = centroid(ring);
    const std::uint32_t face =
        b.face({.base = base, .normal = {0.0, 1.0, 0.0}, .test = {ce.x, h, ce.z}, .lit = Lit::sun, .facing = true});
    b.polygon(at_height(ring, h), ring, kind, static_cast<std::uint16_t>(variant | prim_flag::any), face, sample,
              Builder::slot(b.near_slot), Builder::slot(b.far_slot), std::abs(signed_area(ring)));
    ++b.near_slot;
    ++b.far_slot;
}

/// Amsterdam.gableRoof without a tradition.
void gable_roof(Builder& b, const Building& bl, bool hipped, std::uint16_t variant) {
    const std::span<const V2> poly = bl.poly;
    const std::size_t n = poly.size();
    const double h = bl.h;
    const double wall_top = bl.wall_top;
    const Ridge r = ridge_of(poly, is(bl.v.orient, "across"));
    const double inset = hipped ? pm::min(bl.rh, (r.s1 - r.s0) / 2) : 0.0;
    struct Pending {
        std::array<V3, 4> p;
        std::uint8_t corners;
        PrimKind kind;
        std::uint32_t face;
        std::uint32_t sample;
        std::uint32_t edge;
        bool beam = false; ///< a hoist beam from p[0] to p[1] (no primitive), under the gable of face `face`
    };
    // an old house's gables have their hoist beam (`yr and yr < 1850`; with weather within 260 m, the frame's to
    // decide)
    const bool old = bl.v.year != 0 && bl.v.year < 1850;
    std::vector<Pending> gables;
    std::vector<Pending> slopes;
    for (std::size_t i = 0; i < n; ++i) {
        const V2 a = poly[i];
        const V2 bb = poly[(i + 1) % n];
        const V3 pa{a.x, wall_top, a.z};
        const V3 pb{bb.x, wall_top, bb.z};
        const V3 qa = on_ridge(r, inset, h, pa);
        const V3 qb = on_ridge(r, inset, h, pb);
        const std::uint32_t sample = b.sample++; // K8: one sample per edge, hip, gable or slope
        if (dist3(qa, qb) < 0.3 && hipped) {
            const std::array<V3, 3> tri{pa, pb, qa};
            const V3 nr = normal_of(tri[0], tri[1], tri[2]);
            const std::uint32_t face =
                b.face({.base = bl.v.roof_rgb, .normal = nr, .test = cen(tri), .lit = Lit::sun, .facing = true});
            slopes.push_back({.p = {pa, pb, qa, qa},
                              .corners = 3,
                              .kind = PrimKind::hip,
                              .face = face,
                              .sample = sample,
                              .edge = static_cast<std::uint32_t>(i)});
            continue;
        }
        if (dist3(qa, qb) < 0.3) {
            // the gable end: a vertical wall triangle in the wall's material
            const std::array<V3, 3> tri{pa, pb, V3{(qa.x + qb.x) / 2, h, (qa.z + qb.z) / 2}};
            const V3 nr = vnorm({bb.z - a.z, 0.0, -(bb.x - a.x)});
            const std::uint32_t face =
                b.face({.base = bl.v.wall_rgb, .normal = nr, .test = cen(tri), .lit = Lit::sun, .facing = true});
            gables.push_back({.p = {tri[0], tri[1], tri[2], tri[2]},
                              .corners = 3,
                              .kind = PrimKind::gable,
                              .face = face,
                              .sample = sample,
                              .edge = static_cast<std::uint32_t>(i)});
            if (old) {
                // the hoist beam: from (ap.x, h - rh * 0.25, ap.z) to (ap.x + nr.x * 0.7, ..., ap.z + nr.z * 0.7), into
                // the slopes as the port appends it
                const V3 from{tri[2].x, h - (bl.rh * 0.25), tri[2].z};
                const V3 to{tri[2].x + (nr.x * 0.7), h - (bl.rh * 0.25), tri[2].z + (nr.z * 0.7)};
                slopes.push_back({.p = {from, to, to, to},
                                  .corners = 0,
                                  .kind = PrimKind::gable,
                                  .face = face,
                                  .sample = 0,
                                  .edge = static_cast<std::uint32_t>(i),
                                  .beam = true});
            }
            continue;
        }
        const std::array<V3, 4> q{pa, pb, qb, qa};
        const V3 nr = upward(vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0]))));
        const std::uint32_t face =
            b.face({.base = bl.v.roof_rgb, .normal = nr, .test = cen(q), .lit = Lit::sun, .facing = true});
        slopes.push_back({.p = q,
                          .corners = 4,
                          .kind = PrimKind::slope,
                          .face = face,
                          .sample = sample,
                          .edge = static_cast<std::uint32_t>(i)});
    }
    // gable walls first, so the roof covers any hidden notch; a hoist beam at its slot among the slopes
    for (const std::vector<Pending>* list : {&gables, &slopes}) {
        for (const Pending& p : *list) {
            if (p.beam) {
                b.out.beams.push_back({.item = b.item,
                                       .face = p.face,
                                       .from = p.p[0],
                                       .to = p.p[1],
                                       .near_slot = Builder::slot(b.near_slot++),
                                       .far_slot = Builder::slot(b.far_slot++)});
                continue;
            }
            b.plain(p.p, p.corners, p.kind, static_cast<std::uint16_t>(variant | prim_flag::any), p.face, p.sample,
                    Builder::slot(b.near_slot++), Builder::slot(b.far_slot++), {.edge = p.edge, .part = 0});
        }
    }
}

/// Amsterdam.mansard's faces and top (its dormers are K14's).
void mansard(Builder& b, const Building& bl, std::uint16_t variant) {
    const std::span<const V2> poly = bl.poly;
    const std::size_t n = poly.size();
    const double h = bl.h;
    const double rh = bl.rh;
    const double wall_top = bl.wall_top;
    std::optional<std::vector<V2>> found;
    for (const double dl : {pm::min(1.4, rh * 0.36), pm::min(0.8, rh * 0.22)}) {
        found = inset_ring(poly, dl);
        if (found) break;
    }
    const std::vector<V2> ins = found ? std::move(*found) : std::vector<V2>(poly.begin(), poly.end());
    for (std::size_t i = 0; i < n; ++i) {
        const V2 a = poly[i];
        const V2 bb = poly[(i + 1) % n];
        const V2 ia = ins[i];
        const V2 ib = ins[(i + 1) % n];
        const double elen = dist(a, bb);
        if (elen < 0.3) continue;
        const std::array<V3, 4> q{V3{a.x, wall_top, a.z}, V3{bb.x, wall_top, bb.z}, V3{ib.x, h, ib.z},
                                  V3{ia.x, h, ia.z}};
        V3 nr = upward(vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0]))));
        if (std::abs(nr.y) < 1e-3) { // upright: face it outward
            nr = vnorm({bb.z - a.z, 0.0, -(bb.x - a.x)});
            if (pip(((a.x + bb.x) / 2) + (nr.x * 0.05), ((a.z + bb.z) / 2) + (nr.z * 0.05), poly)) nr = neg(nr);
        }
        const std::uint32_t face =
            b.face({.base = bl.v.roof_rgb, .normal = nr, .test = cen(q), .lit = Lit::sun, .facing = true});
        b.plain(q, 4, PrimKind::mansard, static_cast<std::uint16_t>(variant | prim_flag::any), face, b.sample++,
                Builder::slot(b.near_slot++), Builder::slot(b.far_slot++),
                {.edge = static_cast<std::uint32_t>(i), .part = 0});
        if (elen >= 2.4 && rh >= 2.4) ++b.sample; // K8's dormer sample (K14 draws the dormers)
    }
    top(b, ins, h, PrimKind::mansard_top, variant, b.sample++, bl.v.roof_rgb);
}

} // namespace

void roofs(Builder& b, const Building& bl, std::uint16_t variant) {
    const std::span<const V2> poly = bl.poly;
    const std::size_t n = poly.size();
    const double h = bl.h;
    const double rh = bl.rh;
    const double wall_top = bl.wall_top;
    if (is(bl.roof, "pyramidal") && rh != 0.0) {
        const V2 ce = centroid(poly);
        const V3 apex{ce.x, h, ce.z};
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 bb = poly[(i + 1) % n];
            const std::array<V3, 3> tri{V3{a.x, wall_top, a.z}, V3{bb.x, wall_top, bb.z}, apex};
            const V3 nr = normal_of(tri[0], tri[1], tri[2]);
            const std::uint32_t face =
                b.face({.base = bl.v.roof_rgb, .normal = nr, .test = cen(tri), .lit = Lit::sun, .facing = true});
            b.plain({tri[0], tri[1], tri[2], tri[2]}, 3, PrimKind::pyramid,
                    static_cast<std::uint16_t>(variant | prim_flag::any), face, b.sample++,
                    Builder::slot(b.near_slot++), Builder::slot(b.far_slot++),
                    {.edge = static_cast<std::uint32_t>(i), .part = 0});
        }
    } else if ((is(bl.roof, "gabled") || is(bl.roof, "hipped")) && rh != 0.0) {
        gable_roof(b, bl, is(bl.roof, "hipped"), variant);
    } else if (bl.skillion) {
        // s_ = rh / span; nr = vnorm((dirv[0] * s_, 1, dirv[1] * s_)); tested at (cx, h - rh / 2, cz)
        const double s = rh / bl.span;
        const V3 nr = vnorm({bl.dirv.x * s, 1.0, bl.dirv.z * s});
        const V2 ce = centroid(poly);
        const std::uint32_t face = b.face(
            {.base = bl.v.roof_rgb, .normal = nr, .test = {ce.x, h - (rh / 2), ce.z}, .lit = Lit::sun, .facing = true});
        std::vector<V3> ring;
        ring.reserve(n);
        for (const V2& p : poly) ring.emplace_back(p.x, bl.sk_top(p), p.z);
        b.polygon(std::move(ring), poly, PrimKind::skillion, static_cast<std::uint16_t>(variant | prim_flag::any), face,
                  b.sample++, Builder::slot(b.near_slot), Builder::slot(b.far_slot), area_in_space(poly, nr));
        ++b.near_slot;
        ++b.far_slot;
    } else if (is(bl.roof, "mansard") && rh != 0.0) {
        mansard(b, bl, variant);
    } else {
        top(b, poly, h, PrimKind::top, variant, b.sample++, bl.v.roof_rgb);
    }
}

} // namespace atlas::geometry
