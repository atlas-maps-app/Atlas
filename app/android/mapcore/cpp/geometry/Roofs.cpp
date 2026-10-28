// A building's roof in stage T (geometry/Roofs.hpp).
#include "geometry/Roofs.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/Ground.hpp"
#include "tiles/layers/Buildings.hpp"

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
    // decide): never on a roof drawn only without weather
    const bool old = (variant & prim_flag::dry) == 0 && bl.v.year != 0 && bl.v.year < 1850;
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

/// Amsterdam.tradRoof: a gabled or hipped roof in a tradition's kit (`variant` carries prim_flag::weather: the port
/// draws it with weather, the plain roof without), its eaves and verges overhanging the walls by the kit's `eaves`,
/// its gable ends facing out, its slopes from the eaves' height up to the ridge; then, after them, the kit's hoist
/// beams (where it gives the building one), each slope's tile courses on a roof of tiles or slates, and the lit ridge,
/// which the frame draws.
void kit_roof(Builder& b, const Building& bl, bool hipped, std::uint16_t variant, double eaves) {
    namespace c = tiles::building;
    const std::span<const V2> poly = bl.poly;
    const std::size_t n = poly.size();
    const double h = bl.h;
    const double rh = bl.rh;
    const double wall_top = bl.wall_top;
    // trad_ridge: along the first longest side, across it where roof:orientation says
    const std::size_t i0 = longest_first(poly);
    double ux = poly[(i0 + 1) % n].x - poly[i0].x;
    double uz = poly[(i0 + 1) % n].z - poly[i0].z;
    double lg = pm::hypot(ux, uz);
    if (lg == 0.0) lg = 1.0;
    ux /= lg;
    uz /= lg;
    if (is(bl.v.orient, "across")) {
        const double t = ux;
        ux = -uz;
        uz = t;
    }
    const V2 ce = centroid(poly);
    // hd: the farthest corner from the ridge's line (City.pyMaxOf), at least 1 m; the roof's slope over it
    double far = std::abs((-(poly[0].x - ce.x) * uz) + ((poly[0].z - ce.z) * ux));
    for (std::size_t i = 1; i < n; ++i)
        far = pm::max(far, std::abs((-(poly[i].x - ce.x) * uz) + ((poly[i].z - ce.z) * ux)));
    const double slope = rh / pm::max(1.0, far);
    // the eaves' ring (TD['eaves'], truthy), or the footprint where a side would turn over
    double e = eaves;
    std::optional<std::vector<V2>> offset = e != 0.0 ? offset_ring(poly, e) : std::nullopt;
    if (!offset) e = 0.0;
    const std::vector<V2> ring = offset ? std::move(*offset) : std::vector<V2>(poly.begin(), poly.end());
    // the extent of a ring along the ridge (City.pyMinOf and pyMaxOf of the projections)
    const auto extent = [&](std::span<const V2> pp) {
        double s0 = ((pp[0].x - ce.x) * ux) + ((pp[0].z - ce.z) * uz);
        double s1 = s0;
        for (std::size_t i = 1; i < pp.size(); ++i) {
            const double pr = ((pp[i].x - ce.x) * ux) + ((pp[i].z - ce.z) * uz);
            s0 = pm::min(s0, pr);
            s1 = pm::max(s1, pr);
        }
        return std::array<double, 2>{s0, s1};
    };
    const auto slot = [&b] {
        return std::array<std::uint16_t, 2>{Builder::slot(b.near_slot++), Builder::slot(b.far_slot++)};
    };
    const auto flags = static_cast<std::uint16_t>(variant | prim_flag::any);
    // the gable ends, in the wall's own plane and material, facing out
    struct Beam {
        std::uint32_t face;
        V3 from;
        V3 to;
    };
    std::vector<Beam> beams;
    if (!hipped) {
        const auto [s0, s1] = extent(poly);
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 bb = poly[(i + 1) % n];
            const double qa = pm::min(s1, pm::max(s0, ((a.x - ce.x) * ux) + ((a.z - ce.z) * uz)));
            const double qb = pm::min(s1, pm::max(s0, ((bb.x - ce.x) * ux) + ((bb.z - ce.z) * uz)));
            if (std::abs(qa - qb) > 0.3) continue;
            const V3 apex{ce.x + ((ux * (qa + qb)) / 2), h, ce.z + ((uz * (qa + qb)) / 2)};
            V3 nr = vnorm({bb.z - a.z, 0.0, -(bb.x - a.x)});
            if (pip(((a.x + bb.x) / 2) + (nr.x * 0.05), ((a.z + bb.z) / 2) + (nr.z * 0.05), poly)) nr = neg(nr);
            const std::array<V3, 3> tri{V3{a.x, wall_top, a.z}, V3{bb.x, wall_top, bb.z}, apex};
            const std::uint32_t face =
                b.face({.base = bl.v.wall_rgb, .normal = nr, .test = cen(tri), .lit = Lit::sun, .facing = true});
            const auto [near, far_slot] = slot();
            b.plain({tri[0], tri[1], tri[2], tri[2]}, 3, PrimKind::gable, flags, face, b.sample++, near, far_slot,
                    {.edge = static_cast<std::uint32_t>(i), .part = 0});
            if ((bl.v.flags & c::flag_kit_hoist) != 0) {
                const V3 from{apex.x, h - (rh * 0.25), apex.z};
                beams.push_back(
                    {.face = face, .from = from, .to = {apex.x + (nr.x * 0.7), from.y, apex.z + (nr.z * 0.7)}});
            }
        }
    }
    // the slopes, from the overhanging eaves up to the ridge
    const auto [s0, s1] = extent(ring);
    const double ye = wall_top - (e * slope);
    const double inset = hipped ? pm::min(rh + e, (s1 - s0) / 2) : 0.0;
    const bool tiled = is(bl.v.rmat_draw, "roof_tiles") || is(bl.v.rmat_draw, "slate");
    const double rstep = is(bl.v.rmat_draw, "roof_tiles") ? 0.33 : 0.26;
    const auto on_ridge_at = [&](const V3& p) {
        const double sv = pm::min(s1 - inset, pm::max(s0 + inset, ((p.x - ce.x) * ux) + ((p.z - ce.z) * uz)));
        return V3{ce.x + (ux * sv), h, ce.z + (uz * sv)};
    };
    struct Courses {
        std::uint32_t face;
        V3 pa;
        V3 pb;
        V3 qa;
        V3 qe;
        std::uint32_t k;
    };
    std::vector<Courses> courses;
    const std::size_t m = ring.size();
    for (std::size_t i = 0; i < m; ++i) {
        const V2 a = ring[i];
        const V2 bb = ring[(i + 1) % m];
        const V3 pa{a.x, ye, a.z};
        const V3 pb{bb.x, ye, bb.z};
        const V3 qa = on_ridge_at(pa);
        const V3 qb = on_ridge_at(pb);
        const bool end = dist3(qa, qb) < 0.3;
        if (end && !hipped) continue;
        const std::array<V3, 4> q = end ? std::array<V3, 4>{pa, pb, qa, qa} : std::array<V3, 4>{pa, pb, qb, qa};
        const std::uint8_t corners = end ? 3 : 4;
        // vnorm(vcross(q[1] - q[0], q[-1] - q[0])), turned up
        V3 nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[corners - 1], q[0])));
        if (nr.y < 0) nr = neg(nr);
        const V3 mid = cen(std::span<const V3>(q.data(), corners));
        const std::uint32_t face =
            b.face({.base = bl.v.roof_rgb, .normal = nr, .test = mid, .lit = Lit::sun, .facing = true});
        const auto [near, far_slot] = slot();
        // the eaves overhang the walls: the face leads them where it passes their top
        const auto overhang = static_cast<std::uint16_t>(flags | (e != 0.0 ? prim_flag::eaves : 0U));
        b.plain(q, corners, end ? PrimKind::hip : PrimKind::slope, overhang, face, b.sample++, near, far_slot,
                {.edge = static_cast<std::uint32_t>(i), .part = 0});
        if (tiled) {
            // the courses of tiles or slates, parallel to the eaves: sl = dist(pa, qa), k = int(sl / rstep)
            const auto k = static_cast<std::uint32_t>(kt_int(dist3(pa, qa) / rstep));
            if (k > 1) courses.push_back({.face = face, .pa = pa, .pb = pb, .qa = qa, .qe = end ? qa : qb, .k = k});
        }
    }
    // the rows after the slopes: the hoist beams, then each slope's courses; then the lit ridge
    for (const Beam& beam : beams) {
        const auto [near, far_slot] = slot();
        b.out.beams.push_back({.item = b.item,
                               .face = beam.face,
                               .from = beam.from,
                               .to = beam.to,
                               .near_slot = near,
                               .far_slot = far_slot});
    }
    const std::uint32_t darker = [&] {
        const Rgba dk = darken(rgba(bl.v.roof_rgb), 0.55);
        return (std::uint32_t{dk.r} << 16U) | (std::uint32_t{dk.g} << 8U) | std::uint32_t{dk.b};
    }();
    for (const Courses& cs : courses) {
        const auto [near, far_slot] = slot();
        b.out.courses.push_back({.item = b.item,
                                 .face = cs.face,
                                 .pa = cs.pa,
                                 .pb = cs.pb,
                                 .qa = cs.qa,
                                 .qe = cs.qe,
                                 .k = cs.k,
                                 .rstep = rstep,
                                 .rgb = darker,
                                 .near_slot = near,
                                 .far_slot = far_slot});
    }
    const V3 r0{ce.x + (ux * (s0 + inset)), h, ce.z + (uz * (s0 + inset))};
    const V3 r1{ce.x + (ux * (s1 - inset)), h, ce.z + (uz * (s1 - inset))};
    if (dist3(r0, r1) > 0.3) {
        const auto [near, far_slot] = slot();
        b.out.ridges.push_back({.item = b.item, .from = r0, .to = r1, .near_slot = near, .far_slot = far_slot});
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
        // a tradition's kit draws its own roof with weather, the plain one without (TD needs `wx`)
        const bool kit = (bl.v.flags & tiles::building::flag_kit) != 0 && (variant & prim_flag::dry) == 0;
        gable_roof(b, bl, is(bl.roof, "hipped"), kit ? static_cast<std::uint16_t>(variant | prim_flag::dry) : variant);
        if (kit) {
            kit_roof(b, bl, is(bl.roof, "hipped"), static_cast<std::uint16_t>(variant | prim_flag::weather),
                     b.kit_eaves.value_or(0.0));
        }
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
