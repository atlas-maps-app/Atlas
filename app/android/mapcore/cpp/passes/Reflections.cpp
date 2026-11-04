// The water's reflections (passes/Ground.hpp; ENGINE.md step 4, the water; render/amsterdam.py:372 reflections,
// R/Amsterdam.kt reflections): with weather, where the water is drawn, the scene mirrored in the water's plane
// (WATER_Y) inside its clip, after wgrad's rect and before its tint, in `<g filter="url(#ripple)" opacity=".92">` (.95
// at night). Each building and part within 16 m of a quay edge (the tiles' near_water flag), not hidden, with a
// height, its centroid within the radius cut and its mirrored middle 2 m or more ahead of the eye: every wall of
// 0.3 m or more facing the camera, mirrored (2 WY - y), in the light of the sample at the real wall's middle 0.2 m out
// (K8's reflection samples), darkened as the bright sky the water mirrors would show it (0.74 by day, 0.62 under
// cloud, 0.5 at night) and edged by its own .3 stroke; a gabled roof's gable on each wall across its ridge, mirrored
// as a downward triangle; within 520 m the window rows of a building that is neither a houseboat nor a part, each
// level's on every wall over 2.2 m, one path at .55; and every tree within 9 m of a quay edge, from 30 m on, five
// circles (random.Random(its id)) in `<g filter="url(#leaf)">`. Each as face() keeps it (Camera.poly at 300 px of
// margin, 0.12 px² of area at least; a window row over 0.3), sorted far to near by its building's (its tree's)
// mirrored middle, a gable 0.01 nearer and the rows 0.02. At night, after them, the streaks: each street lamp within
// the radius cut (S['nodes']'s order) and each lit road's point within it and within 12 m of a quay edge (S['roads']'s,
// every 13 m or so along each segment), where the eye sees its reflection (4.5 m up for a lamp, 5.5 for a road)
// through the water and on the picture, an ellipse of the streak gradient below it, its size, place and opacity drawn
// from one random.Random(5) in that order. The air's haze at eye level (`_hz`) is still to come, which the buildings do
// not take yet either.
//
// On the GPU each face is a record of the quay walls' layout at its element's painter slot (a tree's circle its
// centre and radius in CSS px), drawn in the renders' order in the translucent phase at the reflections' order, inside
// the water's clip: a sample takes what the group's layer would composite there, its colour at the group's opacity
// over wgrad's at the pixel, opaque, a window row's at its opacity over what lies under it; written at its slot's
// depth, so the faces of one path (a building's rows) blend once. In the ripple's layer a tree's circle carries its
// group's leaf filter region (130% of the five circles' box, in whole device px): each pixel within it takes the
// circle's samples at the pixel the leaves' field (fx's `leaf`) moves it to, where that lies within it too.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "geometry/RoofShapes.hpp"
#include "passes/Ground.hpp"
#include "passes/Team.hpp"
#include "scene/Residency.hpp"
#include "style/Light.hpp"
#include "sun/Samples.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

/// near_water(edges, [(x, z)], d) over the drawn tiles' quay edges: each tile's filed by the cells of a grid (32 m
/// squares) that its box widened by the reach (the most either test reads: a lit road's 12 m) meets, so a point reads
/// only the edges near it. A point near one of any drawn tile's edges (each in its own tile's metres) is near the
/// water. A tile kept under one fading in has none (its trees and lit roads are not the frame's).
constexpr double edge_side = 32.0;
constexpr double edge_reach = 12.0;

[[nodiscard]] std::int64_t edge_cell(double v) noexcept { return static_cast<std::int64_t>(std::floor(v / edge_side)); }

[[nodiscard]] std::uint64_t edge_key(std::int64_t cx, std::int64_t cz) noexcept {
    return (static_cast<std::uint64_t>(cx) << 32U) ^ (static_cast<std::uint64_t>(cz) & 0xFFFF'FFFFU);
}

/// Whether quay edge q lies nearer than d to (x, z), as near_water tests each edge.
[[nodiscard]] bool edge_near(const geo::QuayEdge& q, double x, double z, double d) noexcept {
    const geo::P2& a = q.a;
    const geo::P2& b = q.b;
    if (pm::min(a.x, b.x) - d > x || pm::max(a.x, b.x) + d < x) return false;
    if (pm::min(a.z, b.z) - d > z || pm::max(a.z, b.z) + d < z) return false;
    const double ux = b.x - a.x;
    const double uz = b.z - a.z;
    double l2 = (ux * ux) + (uz * uz);
    if (l2 == 0) l2 = 1;
    const double t = pm::max(0.0, pm::min(1.0, (((x - a.x) * ux) + ((z - a.z) * uz)) / l2));
    return pm::hypot((a.x + (ux * t)) - x, (a.z + (uz * t)) - z) < d;
}

/// The box (x0, x1, z0, z1) grown to hold (x, z); an empty one (x1 < x0) becomes the point's.
void hold(std::array<double, 4>& box, double x, double z) noexcept {
    box = box[1] < box[0]
              ? std::array<double, 4>{x, x, z, z}
              : std::array<double, 4>{pm::min(box[0], x), pm::max(box[1], x), pm::min(box[2], z), pm::max(box[3], z)};
}

/// Drawn tile `g`'s quay edges filed by the grid's cells into `own`, with their box.
void file_edges(const geo::GroundTile& g, ReflectionsKept::Tile& own) {
    std::vector<std::pair<std::uint64_t, std::uint32_t>> filed;
    own.edge_box = {0, -1, 0, 0};
    for (std::uint32_t e = 0; e < g.quays.size(); ++e) {
        const geo::QuayEdge& q = g.quays[e];
        if (!q.drawn) continue;
        hold(own.edge_box, pm::min(q.a.x, q.b.x), pm::min(q.a.z, q.b.z));
        hold(own.edge_box, pm::max(q.a.x, q.b.x), pm::max(q.a.z, q.b.z));
        const std::int64_t x1 = edge_cell(pm::max(q.a.x, q.b.x) + edge_reach);
        const std::int64_t z1 = edge_cell(pm::max(q.a.z, q.b.z) + edge_reach);
        for (std::int64_t cx = edge_cell(pm::min(q.a.x, q.b.x) - edge_reach); cx <= x1; ++cx) {
            for (std::int64_t cz = edge_cell(pm::min(q.a.z, q.b.z) - edge_reach); cz <= z1; ++cz) {
                filed.emplace_back(edge_key(cx, cz), e);
            }
        }
    }
    std::ranges::sort(filed);
    own.edge_keys.clear();
    own.edge_first.clear();
    own.edge_list.clear();
    for (const auto& [key, e] : filed) {
        if (own.edge_keys.empty() || own.edge_keys.back() != key) {
            own.edge_keys.push_back(key);
            own.edge_first.push_back(static_cast<std::uint32_t>(own.edge_list.size()));
        }
        own.edge_list.push_back(e);
    }
    own.edge_first.push_back(static_cast<std::uint32_t>(own.edge_list.size()));
}

/// Whether one of tile `g`'s quay edges (filed in `own`) lies nearer than `d` (at most the reach) to (x, z).
[[nodiscard]] bool near_edges(const ReflectionsKept::Tile& own, const geo::GroundTile& g, double x, double z,
                              double d) {
    const std::uint64_t key = edge_key(edge_cell(x), edge_cell(z));
    const auto it = std::ranges::lower_bound(own.edge_keys, key);
    if (it == own.edge_keys.end() || *it != key) return false;
    const auto c = static_cast<std::size_t>(it - own.edge_keys.begin());
    for (std::uint32_t i = own.edge_first[c]; i < own.edge_first[c + 1]; ++i) {
        if (edge_near(g.quays[own.edge_list[i]], x, z, d)) return true;
    }
    return false;
}

/// Which of points `at` (by index, at xs and zs; `box` theirs) lie nearer than `d` to one of tile `t`'s quay edges
/// (filed in `edges`): none where their boxes lie farther apart (every edge's own test drops such a point, its box
/// within the edges' box), else each one's test.
template <typename Points>
[[nodiscard]] ReflectionsKept::Near near_points(const ReflectionsKept::Tile& edges, const geo::GroundTile& t,
                                                const std::array<double, 4>& box, double d, const Points& points) {
    ReflectionsKept::Near out{.serial = edges.serial};
    const std::array<double, 4>& e = edges.edge_box;
    if (e[1] < e[0] || box[1] < box[0]) return out;
    const double x0 = e[0] - d;
    const double x1 = e[1] + d;
    const double z0 = e[2] - d;
    const double z1 = e[3] + d;
    if (box[1] < x0 || box[0] > x1 || box[3] < z0 || box[2] > z1) return out;
    points([&](std::uint32_t i, double x, double z) {
        if (x < x0 || x > x1 || z < z0 || z > z1) return;
        if (near_edges(edges, t, x, z, d)) out.at.push_back(i);
    });
    return out;
}

/// A band of water ring `r`'s z (ReflectionsKept::WaterRing): z's place from z0 in steps, within the ring's bands;
/// monotonic in z, so a side spanning z lies in each band from its lower end's to its higher end's.
[[nodiscard]] std::uint32_t band_of(const ReflectionsKept::WaterRing& r, double z) noexcept {
    const double t = (z - r.z0) / r.step;
    if (!(t > 0)) return 0;
    const auto last = static_cast<double>(r.bands - 1);
    return t >= last ? r.bands - 1 : static_cast<std::uint32_t>(std::floor(t));
}

/// The water rings of drawn tile `g` (its water fills that are no islands, in their order) with their sides filed by
/// bands of z, into `own`.
void band_water(const geo::GroundTile& g, ReflectionsKept::Tile& own) {
    own.rings.clear();
    own.band_at.clear();
    own.sides.clear();
    own.banded = true;
    std::vector<std::uint32_t> count;
    for (std::uint32_t fi = 0; fi < g.fills.size(); ++fi) {
        const geo::Fill& fill = g.fills[fi];
        if (fill.kind != geo::FillKind::water || fill.hole || fill.count == 0) continue;
        const std::span<const geo::P2> r = std::span(g.points).subspan(fill.first, fill.count);
        double lo = r[0].z;
        double hi = r[0].z;
        for (const geo::P2& q : r) {
            lo = std::min(lo, q.z);
            hi = std::max(hi, q.z);
        }
        // about eight sides a band, at least a metre each
        const auto bands = static_cast<std::uint32_t>(std::clamp<std::size_t>(r.size() / 8, 1, 4096));
        ReflectionsKept::WaterRing ring{.fill = fi,
                                        .z0 = lo,
                                        .step = std::max(1.0, (hi - lo) / static_cast<double>(bands)),
                                        .bands = bands,
                                        .at = static_cast<std::uint32_t>(own.band_at.size())};
        // each side (i, the one before it) in every band from its lower end's to its higher end's
        count.assign(bands, 0U);
        std::size_t j = r.size() - 1;
        for (std::size_t i = 0; i < r.size(); ++i) {
            const std::uint32_t b0 = band_of(ring, std::min(r[i].z, r[j].z));
            const std::uint32_t b1 = band_of(ring, std::max(r[i].z, r[j].z));
            for (std::uint32_t k = b0; k <= b1; ++k) ++count[k];
            j = i;
        }
        const auto first = static_cast<std::uint32_t>(own.sides.size());
        std::uint32_t next = first;
        for (std::uint32_t k = 0; k < bands; ++k) {
            own.band_at.push_back(next);
            next += count[k];
        }
        own.band_at.push_back(next);
        own.sides.resize(next);
        std::ranges::fill(count, 0U);
        j = r.size() - 1;
        for (std::size_t i = 0; i < r.size(); ++i) {
            const std::uint32_t b0 = band_of(ring, std::min(r[i].z, r[j].z));
            const std::uint32_t b1 = band_of(ring, std::max(r[i].z, r[j].z));
            for (std::uint32_t k = b0; k <= b1; ++k) {
                own.sides[own.band_at[ring.at + k] + count[k]++] = static_cast<std::uint32_t>(i);
            }
            j = i;
        }
        own.rings.push_back(ring);
    }
}

/// on_water(S, x, z): (x, z) within one of the drawn tiles' water rings (S['water']: its pieces there, the islands
/// aside), each tested as real.py's pip tests a ring: the sides the horizontal ray from (x, z) crosses, counted, of
/// which only those of z's band can be (a side crosses it only where its ends lie either side of z).
[[nodiscard]] bool on_water(const GroundFrame& f, const ReflectionsKept& kept, double x, double z) {
    for (std::size_t ti = 0; ti < f.tiles.size(); ++ti) {
        if (f.tiles[ti].under) continue;
        const geo::GroundTile& g = *f.tiles[ti].ground;
        const ReflectionsKept::Tile& own = *kept.drawn[ti];
        for (const ReflectionsKept::WaterRing& ring : own.rings) {
            const geo::Fill& fill = g.fills[ring.fill];
            if (x < fill.x0 || x > fill.x1 || z < fill.z0 || z > fill.z1) continue;
            const std::span<const geo::P2> r = std::span(g.points).subspan(fill.first, fill.count);
            const std::uint32_t band = band_of(ring, z);
            bool ins = false;
            for (std::uint32_t s = own.band_at[ring.at + band]; s < own.band_at[ring.at + band + 1]; ++s) {
                const std::uint32_t i = own.sides[s];
                const geo::P2& a = r[i];
                const geo::P2& b = r[i == 0 ? r.size() - 1 : i - 1];
                if (((a.z > z) != (b.z > z)) && x < (((b.x - a.x) * (z - a.z)) / ((b.z - a.z) + 1e-12)) + a.x) {
                    ins = !ins;
                }
            }
            if (ins) return true;
        }
    }
    return false;
}

/// A colour of the style (0xRRGGBB) as the port's hx reads it.
[[nodiscard]] shaders::double3 rgb_of(std::uint32_t c) noexcept {
    return {static_cast<double>((c >> 16U) & 0xFFU), static_cast<double>((c >> 8U) & 0xFFU),
            static_cast<double>(c & 0xFFU)};
}

/// A mirrored wall's colour: tohex(mulc(light(th, base, nrm, lit), dk)), 0xRRGGBB.
[[nodiscard]] std::uint32_t mirrored(const shaders::Theme& theme, std::uint32_t base, const geo::V3& n, bool lit,
                                     double dk) {
    const shaders::double3 c = shaders::light(theme, rgb_of(base), shaders::double3{n.x, n.y, n.z}, lit);
    const hlsl::uint3 b = shaders::tohex3(shaders::double3{c.x * dk, c.y * dk, c.z * dk});
    return (b.x << 16U) | (b.y << 8U) | b.z;
}

[[nodiscard]] view::Vec3 vec(const geo::V3& v) noexcept { return {v.x, v.y, v.z}; }

/// The draws of a reflected tree's five circles from random.Random(its id), in their order: each circle's offset
/// across (uniform(-.5, .5)) and down (uniform(-.4, .4)) and its radius's share (uniform(.45, .7)).
constexpr std::size_t tree_draws = 15;

[[nodiscard]] std::uint32_t bits_of(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// A record's flags (ground.slang's REFLECTION_ flags): edged by its own .3 stroke (QUAY_STROKE's), a window row at its
/// opacity, a tree's circle, a night streak's ellipse.
constexpr std::uint32_t reflection_stroke = 1;
constexpr std::uint32_t reflection_sheer = 2;
constexpr std::uint32_t reflection_circle = 4;
constexpr std::uint32_t reflection_ellipse = 8;
constexpr std::uint32_t reflection_leaf = 16;

/// Where a mirror's faces lie for face(): its box (its footprint's, widened by the window rows' 0.04 m out from their
/// walls, between the highest and the lowest of its gables' apexes, its walls' mirrored tops and their mirrored base)
/// wholly ahead of the near plane and beyond the frame's 300 px margin and a pixel more, so no face passes face()'s
/// margin test (no face is clipped and each one's points project within its corners' box; the slack holds where a
/// face's own points round otherwise); wholly ahead (by a micrometre: no face's point is clipped, so face() takes each
/// face's own points); or neither.
enum class MirrorBox : std::uint8_t { beyond, ahead, across };

[[nodiscard]] MirrorBox mirror_box(const view::Camera& cam, const geo::MirrorHead& hd) noexcept {
    constexpr double widen = 0.05;
    constexpr double margin = 300 + 1;
    const double near = cam.near() + 1e-6;
    const double low = std::min({hd.apex_y, hd.top_y, hd.base_y});
    const double high = std::max({hd.apex_y, hd.top_y, hd.base_y});
    double min_x = 0;
    double max_x = 0;
    double min_y = 0;
    double max_y = 0;
    for (int i = 0; i < 8; ++i) {
        const view::Vec3 k = cam.cam({(i & 1) != 0 ? hd.x1 + widen : hd.x0 - widen, (i & 2) != 0 ? high : low,
                                      (i & 4) != 0 ? hd.z1 + widen : hd.z0 - widen});
        if (!(k.z >= near)) return MirrorBox::across;
        const view::Vec2 p = cam.pc(k);
        min_x = i == 0 ? p.x : std::min(min_x, p.x);
        max_x = i == 0 ? p.x : std::max(max_x, p.x);
        min_y = i == 0 ? p.y : std::min(min_y, p.y);
        max_y = i == 0 ? p.y : std::max(max_y, p.y);
    }
    return max_x < -margin || min_x > cam.width() + margin || max_y < -margin || min_y > cam.height() + margin
               ? MirrorBox::beyond
               : MirrorBox::ahead;
}

/// mirror_box of a group of mirror heads (ReflectionsKept::Group, over their boxes as mirror_box widens them): beyond
/// where the group's box lies wholly ahead and beyond the margin by a hair more, so every head's does; ahead where it
/// lies wholly ahead and within the margin by a hair, so every head's does too (each head's box projects within the
/// group's box's corners' hull); else across, and each head tests its own. The hair (a micrometre ahead, a millionth
/// of a pixel across) is far more than the projections' rounding.
[[nodiscard]] MirrorBox group_box(const view::Camera& cam, const ReflectionsKept::Group& g) noexcept {
    constexpr double margin = 300 + 1;
    constexpr double hair = 1e-6;
    const double near = (cam.near() + 1e-6) + hair;
    double min_x = 0;
    double max_x = 0;
    double min_y = 0;
    double max_y = 0;
    for (int i = 0; i < 8; ++i) {
        const view::Vec3 k =
            cam.cam({(i & 1) != 0 ? g.x1 : g.x0, (i & 2) != 0 ? g.y1 : g.y0, (i & 4) != 0 ? g.z1 : g.z0});
        if (!(k.z >= near)) return MirrorBox::across;
        const view::Vec2 p = cam.pc(k);
        min_x = i == 0 ? p.x : std::min(min_x, p.x);
        max_x = i == 0 ? p.x : std::max(max_x, p.x);
        min_y = i == 0 ? p.y : std::min(min_y, p.y);
        max_y = i == 0 ? p.y : std::max(max_y, p.y);
    }
    if (max_x < -margin - hair || min_x > cam.width() + margin + hair || max_y < -margin - hair ||
        min_y > cam.height() + margin + hair) {
        return MirrorBox::beyond;
    }
    if (min_x > -margin + hair && max_x < cam.width() + margin - hair && min_y > -margin + hair &&
        max_y < cam.height() + margin - hair) {
        return MirrorBox::ahead;
    }
    return MirrorBox::across;
}

/// face_kept of a face none of whose points lies nearer than the near plane, so Camera.poly clips none and takes its
/// points as they are: `p` their screen points (Camera.pc of each one's camera coordinates), in its order; their box
/// against the frame's margin and their shoelace area against the least, summed as face_kept sums them.
[[nodiscard]] bool face_kept_ahead(const view::Camera& cam, std::span<const view::Vec2> p, double margin, double least,
                                   bool strictly, std::array<double, 4>& box) noexcept {
    double min_x = p[0].x;
    double max_x = p[0].x;
    double min_y = p[0].y;
    double max_y = p[0].y;
    double sum = 0;
    view::Vec2 a = p[0];
    for (std::size_t i = 1; i <= p.size(); ++i) {
        const view::Vec2 r = i < p.size() ? p[i] : p[0];
        if (i < p.size()) {
            min_x = pm::min(min_x, r.x);
            max_x = pm::max(max_x, r.x);
            min_y = pm::min(min_y, r.y);
            max_y = pm::max(max_y, r.y);
        }
        sum += (a.x * r.y) - (r.x * a.y);
        a = r;
    }
    if (max_x < -margin || min_x > cam.width() + margin || max_y < -margin || min_y > cam.height() + margin) {
        return false;
    }
    const double area = std::abs(sum) / 2;
    const bool kept = strictly ? area > least : area >= least;
    if (kept) box = {min_x, min_y, max_x, max_y};
    return kept;
}

/// A streak gradient's stop as the twin carries it: its colour with its opacity in the alpha byte.
[[nodiscard]] std::uint32_t stop_argb(std::uint32_t rgb, double opacity) noexcept {
    return (static_cast<std::uint32_t>(std::lround(opacity * 255.0)) << 24U) | (rgb & 0xFF'FFFFU);
}

/// The drawn tiles' lists (each by its order, a tie in the tile's own order: ReflectionsKept::Tile's) merged by their
/// order, a tie going to the tile drawn first, onto `out`: what one stable sort by order of the lists end to end in
/// the frame's order gives.
void merge_by_order(const std::vector<const std::vector<ReflectionsKept::Ordered>*>& lists,
                    std::vector<ReflectionsKept::Ref>& out) {
    struct Next {
        std::uint64_t order;
        std::uint32_t tile;
    };
    // a heap whose top is the earliest next element: the least order, then the least tile
    const auto later = [](const Next& a, const Next& b) {
        return a.order != b.order ? a.order > b.order : a.tile > b.tile;
    };
    std::vector<std::size_t> at(lists.size(), 0);
    std::vector<Next> heap;
    heap.reserve(lists.size());
    std::size_t total = 0;
    for (std::uint32_t t = 0; t < lists.size(); ++t) {
        total += lists[t]->size();
        if (!lists[t]->empty()) heap.push_back({.order = lists[t]->front().order, .tile = t});
    }
    std::ranges::make_heap(heap, later);
    out.reserve(out.size() + total);
    while (!heap.empty()) {
        std::ranges::pop_heap(heap, later);
        const std::uint32_t t = heap.back().tile;
        const std::vector<ReflectionsKept::Ordered>& list = *lists[t];
        out.push_back({.tile = t, .index = list[at[t]].index});
        if (++at[t] < list.size()) {
            heap.back() = {.order = list[at[t]].order, .tile = t};
            std::ranges::push_heap(heap, later);
        } else {
            heap.pop_back();
        }
    }
}

/// A list of a tile's elements by their order, a tie in the tile's own order.
void sort_by_order(std::vector<ReflectionsKept::Ordered>& list) {
    std::ranges::stable_sort(list, {}, &ReflectionsKept::Ordered::order);
}

/// Which of a drawn tile's points (`points` visits each one's index and place; `box` theirs, `count` how many it
/// has) lie nearer than `d` to a quay edge of a drawn tile, into k.flags (by index): those near each drawn tile's
/// edges, in `cache` (the tile's), made as the two are first drawn together. False where none is.
template <typename Points>
bool near_flags(const GroundFrame& f, ReflectionsKept& k, std::vector<ReflectionsKept::Near>& cache,
                const std::array<double, 4>& box, double d, std::size_t count, const Points& points) {
    bool any = false;
    for (std::uint32_t tj = 0; tj < f.tiles.size(); ++tj) {
        if (f.tiles[tj].under) continue;
        const ReflectionsKept::Tile& edges = *k.drawn[tj];
        auto it = std::ranges::find(cache, edges.serial, &ReflectionsKept::Near::serial);
        if (it == cache.end()) {
            cache.push_back(near_points(edges, *f.tiles[tj].ground, box, d, points));
            it = cache.end() - 1;
        }
        if (it->at.empty()) continue;
        if (!any) k.flags.assign(count, 0U);
        any = true;
        for (const std::uint32_t i : it->at) k.flags[i] = 1U;
    }
    return any;
}

/// Which of the drawn tiles' lit roads' points lie within 12 m of a quay edge of a drawn tile (each tile's
/// road_near), made the first night frame after a lay.
void lay_roads_near(const GroundFrame& f, ReflectionsKept& k) {
    k.roads_near = true;
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        if (f.tiles[ti].under) continue;
        const geo::GroundTile& g = *f.tiles[ti].ground;
        ReflectionsKept::Tile& own = *k.drawn[ti];
        own.road_near.assign(g.lit_points.size(), 0U);
        if (g.lit_roads.empty()) continue;
        const auto points = [&](const auto& at) {
            for (const geo::LitRoad& road : g.lit_roads) {
                for (std::uint32_t i = road.first; i < road.first + road.count; ++i) {
                    at(i, g.lit_points[i].x, g.lit_points[i].z);
                }
            }
        };
        if (!near_flags(f, k, own.near_roads, own.road_box, 12.0, g.lit_points.size(), points)) continue;
        for (const geo::LitRoad& road : g.lit_roads) {
            for (std::uint32_t i = road.first; i < road.first + road.count; ++i) own.road_near[i] = k.flags[i];
        }
    }
}

/// A drawn tile's mirror heads by groups of its 16 × 16 squares (by their centres, over their box), each group's box
/// over its heads' as mirror_box widens them.
void group_mirrors(const geo::GroundTile& g, ReflectionsKept::Tile& own) {
    constexpr double widen = 0.05;
    constexpr std::size_t side = 16;
    own.groups.clear();
    own.group_of.assign(g.mirror_heads.size(), 0);
    if (g.mirror_heads.empty()) return;
    std::array<double, 4> centres{0, -1, 0, 0};
    for (const geo::MirrorHead& hd : g.mirror_heads) hold(centres, hd.centre.x, hd.centre.z);
    const auto square = [](double v, double v0, double v1) -> std::size_t {
        if (!(v1 > v0)) return 0;
        const double t = std::floor(((v - v0) / (v1 - v0)) * static_cast<double>(side));
        return t <= 0 ? 0 : std::min(side - 1, static_cast<std::size_t>(t));
    };
    std::array<std::int32_t, side * side> index{};
    index.fill(-1);
    for (std::uint32_t h = 0; h < g.mirror_heads.size(); ++h) {
        const geo::MirrorHead& hd = g.mirror_heads[h];
        const std::size_t c =
            (square(hd.centre.z, centres[2], centres[3]) * side) + square(hd.centre.x, centres[0], centres[1]);
        const double low = std::min({hd.apex_y, hd.top_y, hd.base_y});
        const double high = std::max({hd.apex_y, hd.top_y, hd.base_y});
        const ReflectionsKept::Group box{
            .x0 = hd.x0 - widen, .x1 = hd.x1 + widen, .z0 = hd.z0 - widen, .z1 = hd.z1 + widen, .y0 = low, .y1 = high};
        if (index[c] < 0) {
            index[c] = static_cast<std::int32_t>(own.groups.size());
            own.groups.push_back(box);
        } else {
            ReflectionsKept::Group& u = own.groups[static_cast<std::size_t>(index[c])];
            u = {.x0 = std::min(u.x0, box.x0),
                 .x1 = std::max(u.x1, box.x1),
                 .z0 = std::min(u.z0, box.z0),
                 .z1 = std::max(u.z1, box.z1),
                 .y0 = std::min(u.y0, box.y0),
                 .y1 = std::max(u.y1, box.y1)};
        }
        own.group_of[h] = static_cast<std::uint16_t>(index[c]);
    }
}

} // namespace

std::uint32_t leaf_anchor(std::int64_t id) noexcept {
    // a mix of the id's two halves (the crowns' tree_crown_anchor's steps), 12 bits a coordinate
    const auto bits = static_cast<std::uint64_t>(id);
    std::uint32_t h =
        (static_cast<std::uint32_t>(bits) * 0x9E37'79B1U) ^ (static_cast<std::uint32_t>(bits >> 32U) * 0x85EB'CA77U);
    h ^= h >> 15U;
    h *= 0x2C1B'3C6DU;
    h ^= h >> 12U;
    return (h & 0xFFFU) | (((h >> 16U) & 0xFFFU) << 16U);
}

void prepare_reflections(geo::GroundTile& g) {
    g.mirror_heads.clear();
    g.mirror_walls.clear();
    if (g.casters == nullptr) return;
    const sun::TileSolids& solids = g.casters->solids;
    const double wy = g.water_y;
    std::vector<geo::V2> poly;
    g.mirror_heads.reserve(g.casters->mirrors.size());
    for (std::uint32_t k = 0; k < g.casters->mirrors.size(); ++k) {
        const sun::Mirror& m = g.casters->mirrors[k];
        const std::span<const double> xs = solids.xs(m.solid);
        const std::span<const double> zs = solids.zs(m.solid);
        poly.clear();
        for (std::size_t i = 0; i < xs.size(); ++i) poly.emplace_back(xs[i], zs[i]);
        // a houseboat's walls from the water's level
        const double y0 = m.boat ? wy + 0.3 : m.min;
        const double h = m.boat ? (m.h + wy) + 0.3 : m.h;
        const double top = h - m.rh;
        const geo::V2 c = geo::centroid(poly);
        std::array<double, 4> box{0, 0, 0, 0};
        for (std::size_t i = 0; i < poly.size(); ++i) {
            const geo::V2& q = poly[i];
            box = i == 0 ? std::array<double, 4>{q.x, q.x, q.z, q.z}
                         : std::array<double, 4>{std::min(box[0], q.x), std::max(box[1], q.x), std::min(box[2], q.z),
                                                 std::max(box[3], q.z)};
        }
        geo::MirrorHead head{.mirror = k,
                             .first = static_cast<std::uint32_t>(g.mirror_walls.size()),
                             .count = 0,
                             .centre = {.x = c.x, .z = c.z},
                             .x0 = box[0],
                             .x1 = box[1],
                             .z0 = box[2],
                             .z1 = box[3],
                             .y0 = y0,
                             .top = top,
                             .depth_y = (2 * wy) - ((y0 + h) / 2),
                             .mid_y = (2 * wy) - ((y0 + top) / 2),
                             .base_y = (2 * wy) - y0,
                             .top_y = (2 * wy) - top,
                             .apex_y = (2 * wy) - h};
        const std::size_t n = poly.size();
        // a gable end mirrors as a downward triangle on a wall across the ridge (the longest edge's, turned where the
        // roof is oriented across)
        const bool gabled = m.rh != 0.0 && m.gabled && n > 0;
        double rx = 0;
        double rz = 0;
        double rl = 1;
        if (gabled) {
            const geo::V2& ra = poly[m.longest];
            const geo::V2& rb = poly[(m.longest + 1) % n];
            rx = rb.x - ra.x;
            rz = rb.z - ra.z;
            rl = pm::hypot(rx, rz);
            if (rl == 0) rl = 1;
            if (m.across) {
                const double t0 = rx;
                rx = -rz;
                rz = t0;
            }
        }
        for (std::size_t i = 0; i < n; ++i) {
            const geo::V2& a = poly[i];
            const geo::V2& bb = poly[(i + 1) % n];
            const double ex = bb.x - a.x;
            const double ez = bb.z - a.z;
            const double elen = pm::hypot(ex, ez);
            if (elen < 0.3) continue;
            const geo::V3 nrm = geo::vnorm({ez, 0.0, -ex});
            g.mirror_walls.push_back({.from = static_cast<std::uint32_t>(i),
                                      .sample = head.count++,
                                      .nx = nrm.x,
                                      .nz = nrm.z,
                                      .gable = gabled && std::abs((ex * rx) + (ez * rz)) / (elen * rl) < 0.35,
                                      .rows = elen > 2.2});
        }
        g.mirror_heads.push_back(head);
    }
}

void Ground::lay_reflections() {
    const GroundFrame& f = frame_;
    ReflectionsKept& k = reflections_kept_;
    // a tile kept under one fading in draws none (`none`); those come last, so the lists stand as they come and go
    std::size_t n = f.tiles.size();
    while (n > 0 && f.tiles[n - 1].under) --n;
    const auto ground_of = [&](std::size_t i) -> const void* { return f.tiles[i].under ? nullptr : f.tiles[i].ground; };
    bool same = k.grounds.size() == n;
    for (std::size_t i = 0; same && i < n; ++i) same = k.grounds[i] == ground_of(i);
    if (same) {
        k.drawn.resize(n);
        k.drawn.resize(f.tiles.size(), &k.none);
        return;
    }
    k.grounds.clear();
    for (std::size_t i = 0; i < n; ++i) k.grounds.push_back(ground_of(i));
    ++k.lays;
    k.roads_near = false;
    // each drawn tile's own, kept while it is drawn (its walls' colours, its trees' draws), made for one that lands;
    // one no longer drawn is kept a few lays more without its ground, which it watches, and taken up again where its
    // ground comes back
    for (const std::unique_ptr<ReflectionsKept::Tile>& own : k.tiles) own->used = false;
    k.drawn.clear();
    for (const DrawnTile& t : f.tiles) {
        if (t.under) {
            k.drawn.push_back(&k.none);
            continue;
        }
        ReflectionsKept::Tile* own = nullptr;
        for (const std::unique_ptr<ReflectionsKept::Tile>& o : k.tiles) {
            if (o->key == t.ground && !o->watched.expired()) own = o.get();
        }
        if (own == nullptr) {
            auto made = std::make_unique<ReflectionsKept::Tile>();
            const geo::GroundTile& g = *t.ground;
            made->watched = t.pass->cpu;
            made->key = t.ground;
            made->serial = ++k.serials;
            made->lit.assign(g.mirror_walls.size(), 0U);
            made->unlit.assign(g.mirror_walls.size(), 0U);
            made->made.assign(g.mirror_walls.size(), 0U);
            made->draws_at.assign(g.casters != nullptr ? g.casters->trees.x.size() : 0U, -1);
            // S['buildings'] + S['parts'] and S['trees'], each in its source order; the street lamps by S['nodes']'s
            // order and the lit roads by S['roads']'s
            if (g.casters != nullptr) {
                for (std::uint32_t h = 0; h < g.mirror_heads.size(); ++h) {
                    made->mirrors_by_order.push_back(
                        {.order = g.solid_order[g.casters->mirrors[g.mirror_heads[h].mirror].solid], .index = h});
                }
                const sun::TileTrees& tt = g.casters->trees;
                for (std::uint32_t i = 0; i < tt.x.size() && i < g.tree_order.size(); ++i) {
                    made->trees_by_order.push_back({.order = g.tree_order[i], .index = i});
                    hold(made->tree_box, tt.x[i], tt.z[i]);
                }
                group_mirrors(g, *made);
            }
            for (std::uint32_t i = 0; i < g.lamps.size(); ++i) {
                made->lamps_by_order.push_back({.order = g.lamps[i].index, .index = i});
            }
            for (std::uint32_t i = 0; i < g.lit_roads.size(); ++i) {
                made->roads_by_order.push_back({.order = g.lit_roads[i].index, .index = i});
                for (std::uint32_t p = g.lit_roads[i].first; p < g.lit_roads[i].first + g.lit_roads[i].count; ++p) {
                    hold(made->road_box, g.lit_points[p].x, g.lit_points[p].z);
                }
            }
            sort_by_order(made->mirrors_by_order);
            sort_by_order(made->trees_by_order);
            sort_by_order(made->lamps_by_order);
            sort_by_order(made->roads_by_order);
            own = made.get();
            k.tiles.push_back(std::move(made));
        }
        if (!own->ground) own->ground = t.pass->cpu;
        own->used = true;
        own->laid = k.lays;
        k.drawn.push_back(own);
    }
    std::erase_if(k.tiles, [&](const std::unique_ptr<ReflectionsKept::Tile>& o) {
        return o->watched.expired() || o->laid + 8 < k.lays;
    });
    // what each kept tile knows of tiles gone goes with them
    std::vector<std::uint64_t> serials;
    serials.reserve(k.tiles.size());
    for (const std::unique_ptr<ReflectionsKept::Tile>& o : k.tiles) {
        if (!o->used) o->ground.reset();
        serials.push_back(o->serial);
    }
    std::ranges::sort(serials);
    for (const std::unique_ptr<ReflectionsKept::Tile>& o : k.tiles) {
        const auto gone = [&](const ReflectionsKept::Near& near) {
            return !std::ranges::binary_search(serials, near.serial);
        };
        std::erase_if(o->near_trees, gone);
        std::erase_if(o->near_roads, gone);
    }
    // S['buildings'] + S['parts'], each in its source order, across the drawn tiles; S['trees'] likewise
    std::vector<const std::vector<ReflectionsKept::Ordered>*> lists(f.tiles.size());
    const auto merged = [&](std::vector<ReflectionsKept::Ordered> ReflectionsKept::Tile::* list,
                            std::vector<ReflectionsKept::Ref>& out) {
        for (std::size_t ti = 0; ti < f.tiles.size(); ++ti) lists[ti] = &(k.drawn[ti]->*list);
        out.clear();
        merge_by_order(lists, out);
    };
    merged(&ReflectionsKept::Tile::mirrors_by_order, k.mirrors);
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        if (!f.tiles[ti].under) k.drawn[ti]->places.assign(f.tiles[ti].ground->mirror_heads.size(), 0U);
    }
    for (std::uint32_t m = 0; m < k.mirrors.size(); ++m) k.drawn[k.mirrors[m].tile]->places[k.mirrors[m].index] = m;
    // the drawn tiles' quay edges, each tile's filed once
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        if (!f.tiles[ti].under && k.drawn[ti]->edge_first.empty()) file_edges(*f.tiles[ti].ground, *k.drawn[ti]);
    }
    // the trees within 9 m of a quay edge of a drawn tile: each tile's, by its order, merged across them as S['trees']
    k.near_lists.resize(f.tiles.size());
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        std::vector<ReflectionsKept::Ordered>& list = k.near_lists[ti];
        list.clear();
        lists[ti] = &list;
        if (f.tiles[ti].under) continue;
        ReflectionsKept::Tile& own = *k.drawn[ti];
        if (own.trees_by_order.empty()) continue;
        const sun::TileTrees& tt = f.tiles[ti].ground->casters->trees;
        const auto points = [&](const auto& at) {
            for (const ReflectionsKept::Ordered& o : own.trees_by_order) at(o.index, tt.x[o.index], tt.z[o.index]);
        };
        if (!near_flags(f, k, own.near_trees, own.tree_box, 9.0, tt.x.size(), points)) continue;
        for (const ReflectionsKept::Ordered& o : own.trees_by_order) {
            if (k.flags[o.index] != 0U) list.push_back(o);
        }
    }
    k.trees.clear();
    merge_by_order(lists, k.trees);
    merged(&ReflectionsKept::Tile::lamps_by_order, k.lamps);
    merged(&ReflectionsKept::Tile::roads_by_order, k.roads);
}

void Ground::decide_reflections(const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.reflections.clear();
    f.reflection_faces.clear();
    f.reflection_circles.clear();
    f.reflection_streaks.clear();
    // drawn inside the water's clip (`if not r1: return ''`), with weather alone
    const bool night = f.night;
    // the mirrored buildings and trees, and the night's streaks after them, as the map leaves them in
    const bool mirroring = !f.out(style::camera_part::reflections);
    const bool streaks = night && !f.out(style::camera_part::water_streaks);
    if (!f.weather || no_reflections_ || only_setts_ || (!mirroring && !streaks)) {
        // none drawn while the moment or the switches leave them out: the kept tiles go
        reflections_kept_ = {};
        return;
    }
    if (!f.water) return;
    const shaders::Theme& theme = state.block.theme;
    const geo::V3 light{f.light[0], f.light[1], f.light[2]};
    const bool overcast = state.theme.has(style::theme_flag::overcast) && state.theme.sky_k != 0.0;
    lay_reflections();
    ReflectionsKept& k = reflections_kept_;
    // the moment the walls' colours are made in: they stand while it does
    std::vector<std::uint8_t> moment(sizeof(shaders::Theme) + sizeof(f.light) + 2);
    std::memcpy(moment.data(), &theme, sizeof(shaders::Theme));
    std::memcpy(moment.data() + sizeof(shaders::Theme), f.light.data(), sizeof(f.light));
    moment[moment.size() - 2] = night ? 1U : 0U;
    moment[moment.size() - 1] = overcast ? 1U : 0U;
    if (moment != k.moment) {
        k.moment = std::move(moment);
        ++k.generation;
    }
    // the elements in runs of one depth (ReflectionsKept::Run), each a place in the renders' list
    std::vector<Reflection>& elements = k.elements;
    std::vector<ReflectionsKept::Run>& runs = k.runs;
    elements.clear();
    runs.clear();
    std::uint64_t place = 0;
    const auto rank = [&](double depth, const Reflection& e) {
        runs.push_back(
            {.depth = depth, .place = place++, .first = static_cast<std::uint32_t>(elements.size()), .count = 1});
        elements.push_back(e);
    };
    // The mirrors tile by tile, in each tile's order (its heads, walls and samples lie there in turn); each element's
    // place is its mirror's in S['buildings'] + S['parts'] (ReflectionsKept::mirrors), then its own among its
    // mirror's, as the renders append them. Each tile's on whichever of the team's threads takes it, into its own
    // elements, runs and faces (ReflectionsKept::Mirrored, their firsts its own), merged in the tiles' order after:
    // the frame's lists as one thread lays them.
    const auto mirror = [&](std::uint32_t ti, ReflectionsKept::Taker& s, ReflectionsKept::Mirrored& out) {
        out.elements.clear();
        out.runs.clear();
        out.faces.clear();
        const DrawnTile& t = f.tiles[ti];
        const geo::GroundTile& g = *t.ground;
        if (t.under || g.casters == nullptr) return;
        const view::Camera& cam = *t.cam; // in the tile's own frame
        const geo::V3 eye{cam.eye().x, cam.eye().y, cam.eye().z};
        const double wy = g.water_y;
        const tiles::ReflectionsStyle& rs = g.reflection_style;
        const double dk = night ? rs.dark[2] : (overcast ? rs.dark[1] : rs.dark[0]);
        // its walls' reflection samples, from the first (the tile's bits' list, as its samples have them)
        const auto* firsts = t.sun && t.sun->now != nullptr ? t.sun->now->reflections.get() : nullptr;
        const bool sampled = firsts != nullptr && firsts->size() == g.casters->mirrors.size();
        ReflectionsKept::Tile& own = *k.drawn[ti];
        std::array<double, 4> box{};
        std::uint64_t at_place = 0;
        // each footprint point's screen points at its mirror's walls' mirrored base and tops, made as a facing wall
        // first takes it (`made_for`: the mirror they were made for, plus one)
        std::vector<view::Vec2>& base_at = s.base_at;
        std::vector<view::Vec2>& top_at = s.top_at;
        std::vector<std::uint64_t>& made_for = s.made_for;
        // its mirrors' groups against the frame first: a group beyond it or wholly within its margin says each of its
        // mirrors' boxes is, else each mirror tests its own
        s.group_box.resize(own.groups.size());
        for (std::size_t gi = 0; gi < own.groups.size(); ++gi) {
            s.group_box[gi] = static_cast<std::uint8_t>(group_box(cam, own.groups[gi]));
        }
        for (std::uint32_t h = 0; h < g.mirror_heads.size(); ++h) {
            const geo::MirrorHead& hd = g.mirror_heads[h];
            const sun::Mirror& m = g.casters->mirrors[hd.mirror];
            if (f.engine && beyond_radius(hd.centre.x, hd.centre.z, f.target_x, f.target_z, f.radius)) continue;
            const auto grouped = static_cast<MirrorBox>(s.group_box[own.group_of[h]]);
            if (grouped == MirrorBox::beyond) continue;
            const double depth = cam.cam({hd.centre.x, hd.depth_y, hd.centre.z}).z;
            if (depth < 2) continue;
            const MirrorBox where = grouped == MirrorBox::ahead ? MirrorBox::ahead : mirror_box(cam, hd);
            if (where == MirrorBox::beyond) continue;
            const bool ahead = where == MirrorBox::ahead;
            at_place = static_cast<std::uint64_t>(own.places[h]) << 32U;
            const std::uint32_t sample = sampled ? (*firsts)[hd.mirror] : 0U;
            const std::span<const double> xs = g.casters->solids.xs(m.solid);
            const std::span<const double> zs = g.casters->solids.zs(m.solid);
            const std::size_t n = xs.size();
            // its windows' rows, but where the map leaves the windows out (Atlas's guesses off among others)
            const bool rows = !m.boat && !m.part && depth < 520 && !f.out(style::camera_part::windows);
            // a guessed roof's shape while the guesses are off: the plain box's walls up to the top, no gable
            const bool plain = f.plain_roofs && m.shape_guessed;
            const double top_y = plain ? hd.apex_y : hd.top_y;
            const std::uint64_t mirror_count = ++s.mirror_count;
            if (ahead && base_at.size() < n) {
                base_at.resize(n);
                top_at.resize(n);
                made_for.resize(n, 0);
            }
            // a footprint point's screen points at the walls' mirrored base and top (a wall's quad's corners)
            const auto screen = [&](std::size_t i) {
                if (made_for[i] != mirror_count) {
                    base_at[i] = cam.pc(cam.cam({xs[i], hd.base_y, zs[i]}));
                    top_at[i] = cam.pc(cam.cam({xs[i], top_y, zs[i]}));
                    made_for[i] = mirror_count;
                }
            };
            Reflection windows{.kind = Reflection::Kind::windows,
                               .tile = ti,
                               .first = 0,
                               .count = 0,
                               .colour = night ? rs.windows.night : rs.windows.day};
            s.rows_faces.clear();
            // its walls' elements in one run (their depth its own), its gables' in the next
            const auto walls = static_cast<std::uint32_t>(out.elements.size());
            std::uint64_t walls_place = 0;
            s.gables.clear();
            std::uint64_t gables_place = 0;
            for (std::uint32_t wi = hd.first; wi < hd.first + hd.count; ++wi) {
                const geo::MirrorWall& w = g.mirror_walls[wi];
                const geo::V2 a{xs[w.from], zs[w.from]};
                const std::size_t next = (w.from + 1) % n;
                const geo::V2 bb{xs[next], zs[next]};
                const std::uint32_t at = sample + w.sample;
                const geo::V3 nrm{w.nx, 0.0, w.nz};
                const geo::V3 mid{(a.x + bb.x) / 2, hd.mid_y, (a.z + bb.z) / 2};
                if (geo::vdot(nrm, geo::vsub(eye, mid)) <= 0) continue;
                const bool toward = !night && geo::vdot(nrm, light) > 0;
                // without its samples (its tile drawn before its first bits) lit by day, never as if in the shade
                const bool lit = toward && (!sampled || sun_bit(t, at));
                if (own.made[wi] != k.generation) {
                    // its colours in the moment's light, once a moment
                    own.lit[wi] = mirrored(theme, m.wall, nrm, toward, dk);
                    own.unlit[wi] = toward ? mirrored(theme, m.wall, nrm, false, dk) : own.lit[wi];
                    own.made[wi] = k.generation;
                }
                const std::uint32_t lit_c = own.lit[wi];
                const std::uint32_t unlit_c = own.unlit[wi];
                const std::uint32_t col = lit ? lit_c : unlit_c;
                const std::array<view::Vec3, 4> q{view::Vec3{a.x, hd.base_y, a.z}, view::Vec3{bb.x, hd.base_y, bb.z},
                                                  view::Vec3{bb.x, top_y, bb.z}, view::Vec3{a.x, top_y, a.z}};
                const Reflection wall{.kind = Reflection::Kind::wall,
                                      .tile = ti,
                                      .first = 0,
                                      .count = 1,
                                      .colour = col,
                                      .lit = lit_c,
                                      .unlit = unlit_c,
                                      .sample = at,
                                      .sampled = toward && sampled};
                if (ahead) {
                    screen(w.from);
                    screen(next);
                }
                const bool kept_wall = ahead ? face_kept_ahead(cam,
                                                               std::array<view::Vec2, 4>{base_at[w.from], base_at[next],
                                                                                         top_at[next], top_at[w.from]},
                                                               300, 0.12, false, box)
                                             : face_kept(cam, q, 300, 0.12, false, s.cs, &box);
                if (kept_wall) {
                    Reflection e = wall;
                    e.first = static_cast<std::uint32_t>(out.faces.size());
                    out.faces.push_back({.q = q, .n = 4, .box = box});
                    if (out.elements.size() == walls) walls_place = at_place;
                    ++at_place;
                    out.elements.push_back(e);
                }
                if (w.gable && !plain) {
                    const std::array<view::Vec3, 3> tri{q[3], q[2], view::Vec3{mid.x, hd.apex_y, mid.z}};
                    const bool kept_gable =
                        ahead
                            ? face_kept_ahead(
                                  cam, std::array<view::Vec2, 3>{top_at[w.from], top_at[next], cam.pc(cam.cam(tri[2]))},
                                  300, 0.12, false, box)
                            : face_kept(cam, tri, 300, 0.12, false, s.cs, &box);
                    if (kept_gable) {
                        Reflection e = wall;
                        e.kind = Reflection::Kind::gable;
                        e.first = static_cast<std::uint32_t>(out.faces.size());
                        out.faces.push_back({.q = {tri[0], tri[1], tri[2], tri[2]}, .n = 3, .box = box});
                        if (s.gables.empty()) gables_place = at_place;
                        ++at_place;
                        s.gables.push_back(e);
                    }
                }
                if (rows && w.rows) {
                    const std::int64_t lv = m.levels;
                    const double fh = (hd.top - hd.y0) / static_cast<double>(std::max<std::int64_t>(1, lv));
                    for (std::int64_t r = 0; r < lv; ++r) {
                        const double ya = (hd.y0 + (static_cast<double>(r) * fh)) + (fh * 0.3);
                        const double yb = ya + (fh * 0.48);
                        const std::array<geo::V3, 4> wr =
                            geo::quad_at(a, bb, (2 * wy) - yb, (2 * wy) - ya, 0.08, 0.92, 0.04, nrm);
                        const std::array<view::Vec3, 4> wq{vec(wr[0]), vec(wr[1]), vec(wr[2]), vec(wr[3])};
                        if (face_kept(cam, wq, 300, 0.3, true, s.cs, &box))
                            s.rows_faces.push_back({.q = wq, .n = 4, .box = box});
                    }
                }
            }
            if (out.elements.size() > walls) {
                out.runs.push_back({.depth = depth,
                                    .place = walls_place,
                                    .first = walls,
                                    .count = static_cast<std::uint32_t>(out.elements.size()) - walls});
            }
            if (!s.gables.empty()) {
                out.runs.push_back({.depth = depth - 0.01,
                                    .place = gables_place,
                                    .first = static_cast<std::uint32_t>(out.elements.size()),
                                    .count = static_cast<std::uint32_t>(s.gables.size())});
                out.elements.insert(out.elements.end(), s.gables.begin(), s.gables.end());
            }
            if (!s.rows_faces.empty()) {
                windows.first = static_cast<std::uint32_t>(out.faces.size());
                windows.count = static_cast<std::uint32_t>(s.rows_faces.size());
                out.faces.insert(out.faces.end(), s.rows_faces.begin(), s.rows_faces.end());
                out.runs.push_back({.depth = depth - 0.02,
                                    .place = at_place++,
                                    .first = static_cast<std::uint32_t>(out.elements.size()),
                                    .count = 1});
                out.elements.push_back(windows);
            }
        }
    };
    if (mirroring) {
        Team& team = this->team();
        k.mirrored.resize(f.tiles.size());
        if (k.takers.size() < team.takers()) k.takers.resize(team.takers());
        // the tiles with the most mirrors first, so that the last ones taken are short
        std::vector<std::uint32_t>& order = k.order;
        order.resize(f.tiles.size());
        for (std::uint32_t ti = 0; ti < order.size(); ++ti) order[ti] = ti;
        std::ranges::stable_sort(order, [&](std::uint32_t a, std::uint32_t b) {
            return f.tiles[a].ground->mirror_heads.size() > f.tiles[b].ground->mirror_heads.size();
        });
        helped_ms_ += team.run(order.size(), [&](std::size_t i, unsigned taker) {
            mirror(order[i], k.takers[taker], k.mirrored[order[i]]);
        });
        for (ReflectionsKept::Mirrored& m : std::span(k.mirrored).first(f.tiles.size())) {
            const auto faces = static_cast<std::uint32_t>(f.reflection_faces.size());
            const auto first = static_cast<std::uint32_t>(elements.size());
            for (Reflection& e : m.elements) e.first += faces;
            for (ReflectionsKept::Run& r : m.runs) r.first += first;
            elements.insert(elements.end(), m.elements.begin(), m.elements.end());
            runs.insert(runs.end(), m.runs.begin(), m.runs.end());
            f.reflection_faces.insert(f.reflection_faces.end(), m.faces.begin(), m.faces.end());
        }
    }
    place = static_cast<std::uint64_t>(k.mirrors.size()) << 32U;
    // the trees within 9 m of a quay edge (S['trees'], in its source order across the drawn tiles), from 30 m on
    for (std::size_t ti = 0; mirroring && ti < k.trees.size(); ++ti) {
        const ReflectionsKept::Ref& tr = k.trees[ti];
        const geo::GroundTile& g = *f.tiles[tr.tile].ground;
        const sun::TileTrees& tt = g.casters->trees;
        const view::Camera& cam = *f.tiles[tr.tile].cam;
        const double x = tt.x[tr.index];
        const double z = tt.z[tr.index];
        if (f.engine && beyond_radius(x, z, f.target_x, f.target_z, f.radius)) continue;
        const view::Vec3 kz = cam.cam({x, (2 * g.water_y) - (tt.h[tr.index] * 0.68), z});
        if (kz.z < 30) continue; // from 30 m on (and so 2 m ahead)
        const view::Vec2 p = cam.pc(kz);
        const double rr = (((tt.crown[tr.index] / 2) * 0.9) * cam.focal()) / kz.z;
        // its five circles' draws from random.Random(its id), made as it is first drawn
        ReflectionsKept::Tile& own = *k.drawn[tr.tile];
        std::int32_t& first = own.draws_at[tr.index];
        if (first < 0) {
            first = static_cast<std::int32_t>(own.draws.size());
            pm::PyRandom rnd(tt.id[tr.index]);
            for (int c = 0; c < 5; ++c) {
                own.draws.push_back(rnd.uniform(-.5, .5));
                own.draws.push_back(rnd.uniform(-.4, .4));
                own.draws.push_back(rnd.uniform(.45, .7));
            }
        }
        const std::span<const double> d =
            std::span<const double>(own.draws).subspan(static_cast<std::size_t>(first), tree_draws);
        Reflection e{.kind = Reflection::Kind::tree,
                     .tile = tr.tile,
                     .first = static_cast<std::uint32_t>(f.reflection_circles.size()),
                     .count = 5,
                     .colour = night ? g.reflection_style.trees.night : g.reflection_style.trees.day,
                     .anchor = leaf_anchor(tt.id[tr.index])};
        for (std::size_t c = 0; c < 5; ++c) {
            const double cx = p.x + (d[3 * c] * rr);
            const double cy = p.y + (d[(3 * c) + 1] * rr);
            const double r = rr * d[(3 * c) + 2];
            f.reflection_circles.push_back(
                f.exact ? ReflectionCircle{.cx = printed(cx, 1), .cy = printed(cy, 1), .r = printed(r, 1)}
                        : ReflectionCircle{.cx = cx, .cy = cy, .r = r});
        }
        // the leaf filter's region (`<filter id="leaf" x="-15%" y="-15%" width="130%" height="130%">` over the group's
        // box), in filter px at the frame's filter scale (its density as a float), floored and ceiled
        std::array<double, 4> region{1e300, 1e300, -1e300, -1e300};
        for (std::uint32_t c = 0; c < e.count; ++c) {
            const ReflectionCircle& q = f.reflection_circles[e.first + c];
            region = {pm::min(region[0], q.cx - q.r), pm::min(region[1], q.cy - q.r), pm::max(region[2], q.cx + q.r),
                      pm::max(region[3], q.cy + q.r)};
        }
        const auto scale = static_cast<double>(static_cast<float>(state.camera.density));
        const double bx = region[0] + (-0.15 * (region[2] - region[0]));
        const double by = region[1] + (-0.15 * (region[3] - region[1]));
        const double bw = 1.3 * (region[2] - region[0]);
        const double bh = 1.3 * (region[3] - region[1]);
        e.region = {static_cast<std::int32_t>(std::floor(bx * scale)),
                    static_cast<std::int32_t>(std::floor(by * scale)),
                    static_cast<std::int32_t>(std::ceil((bx + bw) * scale)),
                    static_cast<std::int32_t>(std::ceil((by + bh) * scale))};
        rank(kz.z, e);
    }
    // out.sort(key=lambda x: -x[0]): far to near, stable (equal depths in their order), run by run
    std::ranges::sort(runs, [](const ReflectionsKept::Run& a, const ReflectionsKept::Run& b) {
        return a.depth > b.depth || (a.depth == b.depth && a.place < b.place);
    });
    f.reflections.reserve(elements.size());
    for (const ReflectionsKept::Run& r : runs) {
        const auto from = elements.begin() + r.first;
        f.reflections.insert(f.reflections.end(), from, from + r.count);
    }
    if (streaks) decide_streaks();
}

void Ground::decide_streaks() {
    GroundFrame& f = frame_;
    ReflectionsKept& kept = reflections_kept_;
    const ReflectionsKept& k = kept;
    // the street lamps (S['nodes']'s order), then the lit roads (S['roads']'s), across the drawn tiles
    if (k.lamps.empty() && k.roads.empty()) return;
    // the drawn tiles' water rings by bands, made the first night each is drawn
    for (std::size_t ti = 0; ti < f.tiles.size(); ++ti) {
        if (!f.tiles[ti].under && !kept.drawn[ti]->banded) band_water(*f.tiles[ti].ground, *kept.drawn[ti]);
    }
    pm::PyRandom rnd(5);
    // streak(x, yl, z, k): where the eye's ray to the mirrored light meets the water, ahead, on the picture and on the
    // water (the tests are the port's, the dearest last: none draws from the random numbers before all have passed)
    const auto streak = [&](std::uint32_t tile, double x, double yl, double z, double k) {
        const view::Camera& cam = *f.tiles[tile].cam; // in the lamp's or the road's tile's own frame
        const view::Vec3 eye = cam.eye();
        const double wy = f.tiles[tile].ground->water_y;
        const double my = (2 * wy) - yl;
        const double dy = my - eye.y;
        if (dy >= 0) return;
        const view::Vec3 c = cam.cam({x, my, z});
        if (c.z < 2) return;
        const view::Vec2 p = cam.pc(c);
        const double s = cam.focal() / c.z;
        const bool shown = -20 < p.x && p.x < cam.width() + 20 && -40 < p.y && p.y < cam.height() + 40;
        if (!shown) return;
        const double t = (wy - eye.y) / dy;
        if (!on_water(f, kept, eye.x + ((x - eye.x) * t), eye.z + ((z - eye.z) * t))) return;
        const double j = rnd.random();
        const double cx = p.x + (rnd.uniform(-1, 1) * s);
        const double cy = p.y + ((3 + (3 * j)) * s);
        const double rx = pm::max(0.5, rnd.uniform(0.35, 0.8) * s);
        const double ry = pm::max(2.5, (4 + (6 * j)) * s);
        const double op = k * rnd.uniform(0.45, 1);
        f.reflections.push_back({.kind = Reflection::Kind::streak,
                                 .tile = tile,
                                 .first = static_cast<std::uint32_t>(f.reflection_streaks.size()),
                                 .count = 1});
        f.reflection_streaks.push_back(f.exact
                                           ? ReflectionStreak{.cx = printed(cx, 1),
                                                              .cy = printed(cy, 1),
                                                              .rx = printed(rx, 2),
                                                              .ry = printed(ry, 1),
                                                              .opacity = printed(op, 2)}
                                           : ReflectionStreak{.cx = cx, .cy = cy, .rx = rx, .ry = ry, .opacity = op});
    };
    for (const ReflectionsKept::Ref& l : k.lamps) {
        const geo::P2& at = f.tiles[l.tile].ground->lamps[l.index].at;
        if (f.engine && beyond_radius(at.x, at.z, f.target_x, f.target_z, f.radius)) continue;
        streak(l.tile, at.x, 4.5, at.z, 0.95);
    }
    if (!k.roads.empty() && !k.roads_near) lay_roads_near(f, kept);
    for (const ReflectionsKept::Ref& r : k.roads) {
        const geo::GroundTile& g = *f.tiles[r.tile].ground;
        const geo::LitRoad& road = g.lit_roads[r.index];
        const std::vector<std::uint8_t>& near = k.drawn[r.tile]->road_near;
        for (std::uint32_t i = 0; i < road.count; ++i) {
            const geo::P2& q = g.lit_points[road.first + i];
            if (f.engine && beyond_radius(q.x, q.z, f.target_x, f.target_z, f.radius)) continue;
            if (near[road.first + i] == 0U) continue; // within 12 m of a quay edge
            streak(r.tile, q.x, 5.5, q.z, 0.7);
        }
    }
}

void Ground::write_reflections(std::vector<std::uint32_t>& words) {
    Words& w = *words_;
    w.reflections = static_cast<std::uint32_t>(words.size());
    w.reflection_count = 0;
    // every record is reflection_words long (a face's head and corners, a circle's, a streak's): counted first, then
    // written in place
    constexpr std::size_t reflection_words = 16;
    const auto written = [&](const Reflection& e) {
        const DrawnTile& t = frame_.tiles[e.tile];
        return t.pass->ranges[1] || t.pass->ranges[0];
    };
    std::size_t records = 0;
    for (const Reflection& e : frame_.reflections) {
        if (written(e)) records += e.kind == Reflection::Kind::streak ? 1U : e.count;
    }
    const std::size_t start = words.size();
    words.resize(start + (records * reflection_words), 0U);
    std::uint32_t* out = words.data() + start;
    const auto head = [&](std::uint32_t tile, std::uint32_t slot, std::uint32_t colour, std::uint32_t flags) {
        out[0] = tile;
        out[1] = slot;
        out[2] = colour;
        out[3] = flags;
        ++w.reflection_count;
    };
    // The opaque elements (the walls, gables and trees) nearest first, then the translucent ones (the window rows, the
    // streaks) far to near: each is written at its own slot's depth, so a sample still takes its nearest opaque face
    // and over it every translucent one nearer than that, in the renders' order, as when every element was drawn far
    // to near; and a GPU that tests depth before shading spares the opaque faces hidden behind nearer ones.
    const std::size_t count = frame_.reflections.size();
    const auto translucent = [](const Reflection& e) {
        return e.kind == Reflection::Kind::windows || e.kind == Reflection::Kind::streak;
    };
    for (std::size_t n = 0; n < 2 * count; ++n) {
        const bool second = n >= count;
        const std::size_t j = second ? n - count : count - 1 - n;
        const Reflection& e = frame_.reflections[j];
        if (translucent(e) != second) continue;
        if (!written(e)) continue;
        const DrawnTile& t = frame_.tiles[e.tile];
        const geo::GroundTile& g = *t.ground;
        const std::uint32_t slot_value = slot(geo::group::reflection, static_cast<std::uint32_t>(j), 0);
        if (e.kind == Reflection::Kind::streak) {
            // its ellipse and opacity, and the gradient's three stops (colour and opacity, a byte each)
            const ReflectionStreak& k = frame_.reflection_streaks[e.first];
            const tiles::ReflectionsStyle& rs = g.reflection_style;
            head(e.tile, slot_value, 0U, reflection_ellipse);
            std::size_t at = 4;
            for (const double v : {k.cx, k.cy, k.rx, k.ry, k.opacity}) out[at++] = bits_of(static_cast<float>(v));
            for (std::size_t i = 0; i < 3; ++i) {
                const std::uint32_t argb = stop_argb(rs.streak[i], rs.streak_opacity[i]);
                // RGBA8, r in the low byte
                out[at++] =
                    ((argb >> 16U) & 0xFFU) | (argb & 0xFF00U) | ((argb & 0xFFU) << 16U) | (argb & 0xFF00'0000U);
            }
            out += reflection_words; // and four words of nothing
            continue;
        }
        if (e.kind == Reflection::Kind::tree) {
            const std::uint32_t colour = lit_mix(e.colour, e.colour, 0.0);
            const std::uint32_t leaf =
                no_leaves_ || frame_.out(style::camera_part::leaf_filters) ? 0U : reflection_leaf;
            for (std::uint32_t k = 0; k < e.count; ++k) {
                const ReflectionCircle& c = frame_.reflection_circles[e.first + k];
                // a circle of its tree's group under the leaf filter: its centre and radius, then the group's region
                // and its tree's place in the leaves' field
                head(e.tile, slot_value, colour, reflection_circle | leaf);
                out[4] = bits_of(static_cast<float>(c.cx));
                out[5] = bits_of(static_cast<float>(c.cy));
                out[6] = bits_of(static_cast<float>(c.r));
                for (std::size_t i = 0; i < 4; ++i) out[7 + i] = std::bit_cast<std::uint32_t>(e.region[i]);
                out[11] = e.anchor;
                out += reflection_words; // and four words of nothing
            }
            continue;
        }
        const std::uint32_t colour =
            e.sampled ? lit_mix(e.lit, e.unlit, sun_amount(t, e.sample)) : lit_mix(e.colour, e.colour, 0.0);
        const std::uint32_t flags = e.kind == Reflection::Kind::wall      ? reflection_stroke
                                    : e.kind == Reflection::Kind::windows ? reflection_sheer
                                                                          : 0U;
        for (std::uint32_t k = 0; k < e.count; ++k) {
            const ReflectionFace& face = frame_.reflection_faces[e.first + k];
            head(e.tile, slot_value, colour, flags);
            std::size_t at = 4;
            for (const view::Vec3& c : face.q) {
                out[at++] = bits_of(static_cast<float>(c.x - g.origin.x));
                out[at++] = bits_of(static_cast<float>(c.y));
                out[at++] = bits_of(static_cast<float>(c.z - g.origin.z));
            }
            out += reflection_words;
        }
    }
}

void Ground::record_reflections(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.reflection_count == 0 || !*pipelines_->reflection) return;
    if (drew_ripple_) {
        record_ripple(r);
        return;
    }
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const tiles::ReflectionsStyle& rs = frame_.tiles[frame_.reflections.front().tile].ground->reflection_style;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->reflection);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    pc.mode = {frame_.night ? ground_flag::night : 0U, 0U, w.reflections, samples};
    // wgrad's rows (the water under the group), the group's opacity and the window rows'
    pc.rows = {static_cast<float>(frame_.wgrad_y1), static_cast<float>(frame_.wgrad_y2),
               static_cast<float>(frame_.night ? rs.opacity[1] : rs.opacity[0]),
               static_cast<float>(rs.windows_opacity)};
    const style::ThemeBlock& th = r.state.theme;
    const auto rgba = [](const style::Rgb8& c) { return geo::Rgba{.r = c.r, .g = c.g, .b = c.b, .a = 255}.packed(); };
    pc.colour = {rgba(th.water_top), rgba(th.water_bottom), 0U, 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(face_vertices, w.reflection_count, 0, 0);
    r.counters.draw("ground reflections", std::uint64_t{w.reflection_count} * (face_vertices / 3), w.reflection_count);
}

void Ground::emit_reflections(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const {
    const GroundFrame& f = frame_;
    if (f.reflections.empty() || !state.view) return;
    const view::Camera& cam = state.view->camera();
    TwinPlacer pr(state, cam);
    const tiles::ReflectionsStyle& rs = f.tiles[f.reflections.front().tile].ground->reflection_style;
    const std::int32_t ripple =
        out.group({.node = {.opacity = static_cast<float>(f.night ? rs.opacity[1] : rs.opacity[0]),
                            .filter = out.filter("ripple")},
                   .parent = water});
    const std::int32_t leaf = out.filter("leaf");
    // the streaks' radialGradient (objectBoundingBox), once a frame where one is drawn
    std::int32_t streak = twin::none;
    std::vector<twin::Point> points;
    std::vector<std::uint32_t> ends;
    std::vector<twin::Subpath> subpaths;
    for (std::size_t j = 0; j < f.reflections.size(); ++j) {
        const Reflection& e = f.reflections[j];
        const DrawnTile& t = f.tiles[e.tile];
        const std::uint32_t slot_value = slot(geo::group::reflection, static_cast<std::uint32_t>(j), 0);
        twin::Style s;
        s.fill = twin::Paint::colour(0xFF00'0000U | e.colour);
        if (e.kind == Reflection::Kind::streak) {
            if (streak == twin::none) {
                const std::array<float, 3> offsets{0.0F, 0.5F, 1.0F};
                const std::array<std::uint32_t, 3> colours{stop_argb(rs.streak[0], rs.streak_opacity[0]),
                                                           stop_argb(rs.streak[1], rs.streak_opacity[1]),
                                                           stop_argb(rs.streak[2], rs.streak_opacity[2])};
                streak = out.gradient({.id = "streak",
                                       .radial = true,
                                       .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                                       .units = twin::Units::bounding_box,
                                       .offsets = offsets,
                                       .colours = colours});
            }
            const ReflectionStreak& k = f.reflection_streaks[e.first];
            out.at(slot_value, ripple);
            s.fill = twin::Paint::gradient(streak);
            out.ellipse(s, static_cast<float>(k.cx), static_cast<float>(k.cy), static_cast<float>(k.rx),
                        static_cast<float>(k.ry), {.opacity = static_cast<float>(k.opacity)});
            continue;
        }
        if (e.kind == Reflection::Kind::tree) {
            // `<g fill="…" filter="url(#leaf)">` and its five circles
            out.at(slot_value, out.group({.node = {.filter = leaf}, .parent = ripple}));
            for (std::uint32_t k = 0; k < e.count; ++k) {
                const ReflectionCircle& c = f.reflection_circles[e.first + k];
                out.circle(s, static_cast<float>(c.cx), static_cast<float>(c.cy), static_cast<float>(c.r));
            }
            continue;
        }
        points.clear();
        ends.clear();
        for (std::uint32_t k = 0; k < e.count; ++k) {
            const ReflectionFace& face = f.reflection_faces[e.first + k];
            if (pr.poly(t, std::span<const view::Vec3>(face.q).first(face.n), points) >= 3) {
                ends.push_back(static_cast<std::uint32_t>(points.size()));
            }
        }
        if (ends.empty()) continue;
        subpaths.clear();
        std::uint32_t start = 0;
        for (const std::uint32_t end : ends) {
            subpaths.push_back({.points = std::span<const twin::Point>(points).subspan(start, end - start)});
            start = end;
        }
        out.at(slot_value, ripple);
        twin::Node node;
        if (e.kind == Reflection::Kind::wall) {
            // edged by its own .3 stroke
            s.stroke = twin::Paint::colour(0xFF00'0000U | e.colour);
            s.stroke_width = 0.3F;
        } else if (e.kind == Reflection::Kind::windows) {
            node.opacity = static_cast<float>(rs.windows_opacity);
        }
        out.path(s, subpaths, node);
    }
}

} // namespace atlas::passes
