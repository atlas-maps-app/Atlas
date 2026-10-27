// Stage T of the buildings (geometry/Walls.hpp): the tile's records at the level, in the sun samples' order (K8's
// sun/Samples.cpp: the building layer's records, then the part layer's, each as BuildingSamples::all walks it), so a
// running count gives each face its sample; the walls here, the roofs in geometry/Roofs.cpp, the boats in
// geometry/Houseboats.cpp; then everything laid out by the tile's cells.
#include "geometry/Walls.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "geometry/ChurchWindows.hpp"
#include "geometry/Houseboats.hpp"
#include "geometry/Roofs.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/layers/Buildings.hpp"

#include <mapbox/earcut.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <unordered_map>

namespace atlas::geometry {

namespace pm = atlas::pymath;
namespace tl = atlas::tiles;

std::string_view name_of(PrimKind kind) noexcept {
    switch (kind) {
    case PrimKind::cell: return "cell";
    case PrimKind::skillion_wall: return "skillion_wall";
    case PrimKind::hull_wall: return "hull_wall";
    case PrimKind::cabin_wall: return "cabin_wall";
    case PrimKind::top: return "top";
    case PrimKind::slope: return "slope";
    case PrimKind::gable: return "gable";
    case PrimKind::hip: return "hip";
    case PrimKind::pyramid: return "pyramid";
    case PrimKind::skillion: return "skillion";
    case PrimKind::mansard: return "mansard";
    case PrimKind::mansard_top: return "mansard_top";
    case PrimKind::deck: return "deck";
    case PrimKind::cabin_roof: return "cabin_roof";
    }
    return "?";
}

namespace {

/// The slots of a building's attributes in a layer (the building and part layers, or the variant layer).
struct Slots {
    std::uint8_t h;
    std::uint8_t min;
    std::uint8_t roof_h;
    std::uint8_t roof;
    std::uint8_t orient;
    std::uint8_t wall_rgb;
    std::uint8_t roof_rgb;
    std::uint8_t otype;
    std::uint8_t hidden;
    std::uint8_t flags;
    std::uint8_t tag_building;
    std::uint8_t tag_part;
    std::uint8_t tag_direction;
    std::uint8_t levels;
    std::uint8_t mat_draw;
    std::uint8_t no_win;
    std::uint8_t year;
};

template <class Columns>
consteval Slots slots_in(const Columns& c) {
    return {tl::slot_of(c, "h"),
            tl::slot_of(c, "min"),
            tl::slot_of(c, "roof_h"),
            tl::slot_of(c, "roof"),
            tl::slot_of(c, "orient"),
            tl::slot_of(c, "wall_rgb"),
            tl::slot_of(c, "roof_rgb"),
            tl::slot_of(c, "otype"),
            tl::slot_of(c, "hidden"),
            tl::slot_of(c, "flags"),
            tl::slot_of(c, "tag:building"),
            tl::slot_of(c, "tag:building:part"),
            tl::slot_of(c, "tag:roof:direction"),
            tl::slot_of(c, "levels"),
            tl::slot_of(c, "mat_draw"),
            tl::slot_of(c, "no_win"),
            tl::slot_of(c, "year")};
}

constexpr Slots record_slots = slots_in(tl::building::columns);
constexpr Slots variant_slots = slots_in(tl::variant::columns);

[[nodiscard]] Values values_of(const tl::Layer& l, const Slots& s, std::size_t i) noexcept {
    Values v;
    const std::optional<double> h = l.decimal(s.h, i);
    v.has_h = h.has_value();
    v.h = h.value_or(0.0);
    v.min = l.decimal(s.min, i).value_or(0.0);
    v.roof_h = l.decimal(s.roof_h, i).value_or(0.0);
    v.roof = l.string(s.roof, i);
    v.orient = l.string(s.orient, i);
    v.wall_rgb = static_cast<std::uint32_t>(l.integer(s.wall_rgb, i)) & 0xFF'FFFFU;
    v.roof_rgb = static_cast<std::uint32_t>(l.integer(s.roof_rgb, i)) & 0xFF'FFFFU;
    v.relation = l.string(s.otype, i).value_or(std::string_view{}) == "relation";
    v.btype = l.string(s.tag_building, i);
    v.part_tag = l.string(s.tag_part, i).has_value();
    v.direction = l.string(s.tag_direction, i);
    v.hidden = l.boolean(s.hidden, i).value_or(false);
    v.flags = static_cast<std::uint64_t>(l.integer(s.flags, i));
    const std::optional<std::int64_t> levels = l.nullable_integer(s.levels, i);
    v.has_levels = levels.has_value();
    v.levels = levels.value_or(0);
    v.mat_draw = l.string(s.mat_draw, i);
    v.no_win = l.boolean(s.no_win, i).value_or(false);
    v.year = l.nullable_integer(s.year, i).value_or(0); // amsterdam.py: yr = b['year'] or 0
    return v;
}

/// A ring at the level, in metres in the pack's frame.
void ring_at(const tl::RingView& ring, std::uint8_t level, const tl::Frame& frame, std::vector<std::uint32_t>& kept,
             std::vector<V2>& out) {
    kept.resize(ring.size());
    const tl::Simplified s = tl::simplify(ring, level, frame, kept);
    out.clear();
    for (std::size_t k = 0; k < s.count; ++k) out.emplace_back(frame.x(ring.lon[kept[k]]), frame.z(ring.lat[kept[k]]));
}

/// The cell of each record of a layer.
[[nodiscard]] std::vector<std::uint8_t> cells_of(const tl::Layer& l, std::size_t cells) {
    std::vector<std::uint8_t> out(l.count, 0);
    for (std::size_t c = 0; c < cells && c + 1 < l.cell_first.size(); ++c) {
        const auto [first, last] = l.cell(c);
        for (std::uint32_t i = first; i < last && i < l.count; ++i) out[i] = static_cast<std::uint8_t>(c);
    }
    return out;
}

constexpr std::array<std::string_view, 5> pitched = {"gabled", "pyramidal", "hipped", "skillion", "mansard"};

/// The wall materials of a Paris stone front (WIN_STYLE's tall French windows).
constexpr std::array<std::string_view, 4> paris_fronts = {"limestone", "plaster", "stone", "sandstone"};

template <std::size_t N>
[[nodiscard]] bool in_set(const std::optional<std::string_view>& v,
                          const std::array<std::string_view, N>& set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

/// One building's primitives and windowed walls as they were made, before the tile's layout by cells.
struct Made {
    std::uint8_t cell = 0;
    std::size_t edged_first = 0;
    std::size_t edged_end = 0;
    std::size_t plain_first = 0;
    std::size_t plain_end = 0;
    std::size_t windows_first = 0;
    std::size_t windows_end = 0;
};

/// A count kept within a record's 16 bits.
[[nodiscard]] std::uint16_t u16(int v) noexcept { return static_cast<std::uint16_t>(std::clamp(v, 0, 0xFFFF)); }

} // namespace

bool is(const std::optional<std::string_view>& v, std::string_view s) noexcept { return v.has_value() && *v == s; }

std::uint16_t stroke_of(PrimKind kind) noexcept {
    switch (kind) {
    case PrimKind::slope:
    case PrimKind::gable:
    case PrimKind::hip:
    case PrimKind::mansard: return prim_flag::stroke_fine; // gable_roof's and mansard's stroke-width=".25"
    case PrimKind::top:
    case PrimKind::skillion:
    case PrimKind::mansard_top: return prim_flag::stroke_wide; // the flat tops' and the skillion's ".45"
    case PrimKind::cabin_roof: return prim_flag::stroke_cabin; // draw_houseboat's #2A2C30 ".4"
    case PrimKind::cell:
    case PrimKind::skillion_wall:
    case PrimKind::hull_wall:
    case PrimKind::cabin_wall:
    case PrimKind::pyramid:
    case PrimKind::deck: break;
    }
    return 0;
}

std::uint32_t Builder::face(const Face& f) {
    out.faces.push_back(f);
    return static_cast<std::uint32_t>(out.faces.size() - 1);
}

void Builder::edged(const std::array<V3, 4>& p, PrimKind kind, std::uint16_t flags, std::uint32_t face,
                    std::uint32_t sun, std::uint16_t near, std::uint16_t far, At at) {
    out.edged.push_back({.p = p,
                         .kind = kind,
                         .cell = cell,
                         .flags = static_cast<std::uint16_t>(flags | prim_flag::edge),
                         .face = face,
                         .sample = sun,
                         .item = item,
                         .polygon = no_polygon,
                         .near_slot = near,
                         .far_slot = far,
                         .edge = static_cast<std::uint16_t>(std::min<std::uint32_t>(at.edge, 0xFFFFU)),
                         .part = static_cast<std::uint16_t>(std::min<std::uint32_t>(at.part, 0xFFFFU)),
                         .ncol = static_cast<std::uint16_t>(std::min<std::uint32_t>(at.ncol, 0xFFFFU)),
                         .nrow = static_cast<std::uint16_t>(std::min<std::uint32_t>(at.nrow, 0xFFFFU))});
}

void Builder::plain(const std::array<V3, 4>& p, std::uint8_t corners, PrimKind kind, std::uint16_t flags,
                    std::uint32_t face, std::uint32_t sun, std::uint16_t near, std::uint16_t far, At at) {
    std::array<V3, 4> q = p;
    auto f = static_cast<std::uint16_t>(flags | stroke_of(kind));
    if (corners == 3) {
        q[3] = q[2];
        f |= prim_flag::triangle;
    }
    out.plain.push_back({.p = q,
                         .kind = kind,
                         .cell = cell,
                         .flags = f,
                         .face = face,
                         .sample = sun,
                         .item = item,
                         .polygon = no_polygon,
                         .near_slot = near,
                         .far_slot = far,
                         .edge = static_cast<std::uint16_t>(std::min<std::uint32_t>(at.edge, 0xFFFFU)),
                         .part = static_cast<std::uint16_t>(std::min<std::uint32_t>(at.part, 0xFFFFU))});
}

void Builder::polygon(std::vector<V3> ring, std::span<const V2> plan, PrimKind kind, std::uint16_t flags,
                      std::uint32_t face, std::uint32_t sun, std::uint16_t near, std::uint16_t far, double area) {
    const auto index = static_cast<std::uint32_t>(out.polygons.size());
    Polygon poly{.ring = std::move(ring),
                 .area = area,
                 .face = face,
                 .first = static_cast<std::uint32_t>(out.plain.size()),
                 .count = 0,
                 .item = item,
                 .kind = kind,
                 .flags = static_cast<std::uint16_t>(flags | stroke_of(kind))};
    triangulate(plan, triangles_);
    // an edge of a triangle lies on the ring where its two corners follow each other there, but for points between
    // them that no triangle uses (earcut drops a ring's duplicate and collinear points: the edge runs over them)
    const std::size_t n = poly.ring.size();
    used_.assign(n, 0);
    for (const std::uint32_t i : triangles_) used_[i] = 1;
    const auto follows = [&](std::uint32_t u, std::uint32_t v) {
        for (std::size_t k = (u + 1) % n; k != u; k = (k + 1) % n) {
            if (k == v) return true;
            if (used_[k] != 0) return false;
        }
        return false;
    };
    const auto on_ring = [&](std::uint32_t u, std::uint32_t v) { return follows(u, v) || follows(v, u); };
    for (std::size_t t = 0; t + 2 < triangles_.size(); t += 3) {
        const std::uint32_t i = triangles_[t];
        const std::uint32_t j = triangles_[t + 1];
        const std::uint32_t k = triangles_[t + 2];
        const V3& a = poly.ring[i];
        const V3& b = poly.ring[j];
        const V3& c = poly.ring[k];
        std::uint32_t outline = 0;
        if (on_ring(i, j)) outline |= prim_flag::outline;
        if (on_ring(j, k)) outline |= std::uint32_t{prim_flag::outline} << 1U;
        if (on_ring(k, i)) outline |= std::uint32_t{prim_flag::outline} << 2U;
        out.plain.push_back({.p = {a, b, c, c},
                             .kind = kind,
                             .cell = cell,
                             .flags = static_cast<std::uint16_t>(poly.flags | prim_flag::triangle | prim_flag::polygon |
                                                                 static_cast<std::uint16_t>(outline)),
                             .face = face,
                             .sample = sun,
                             .item = item,
                             .polygon = index,
                             .near_slot = near,
                             .far_slot = far});
        ++poly.count;
    }
    out.polygons.push_back(std::move(poly));
}

std::uint16_t Builder::slot(std::uint32_t value) noexcept {
    ATLAS_ASSERT(value <= 0xFFFFU, "a building with more painter slots than an item's 16 bits hold");
    return static_cast<std::uint16_t>(value);
}

int window_cols(double elen, WindowStyle style) noexcept {
    const double bay = style == WindowStyle::paris ? 2.9 : (style == WindowStyle::modern ? 3.4 : 2.1);
    return std::max(1, kt_int(elen / bay));
}

void walls(Builder& b, const Building& bl, std::uint16_t variant) {
    // render/amsterdam.py's _draw_building: per footprint edge of 0.3 m or more, its cells, the skillion's wall, the
    // wall's shop glazing and its windows; after them the building's plinth and cornice
    const std::span<const V2> poly = bl.poly;
    const std::size_t n = poly.size();
    const double y0 = bl.y0;
    const double wall_top = bl.wall_top;
    // K8's windowed walls (sun/Samples.cpp): not a part (but a church's), not a houseboat, not no_win, with levels
    const std::uint64_t flags = bl.v.flags;
    const bool church = (flags & tl::building::flag_church) != 0;
    const bool modern = (flags & tl::building::flag_modern) != 0;
    const bool windowed = (!bl.v.part_tag || church) && !is(bl.v.btype, "houseboat") && !bl.v.no_win && bl.v.has_levels;
    const WindowStyle style = b.paris && !modern && in_set(bl.v.mat_draw, paris_fronts)
                                  ? WindowStyle::paris
                                  : (modern ? WindowStyle::modern : WindowStyle::plain);
    const auto window_flags =
        static_cast<std::uint8_t>(((flags & tl::building::flag_shop_floor) != 0 ? window_flag::shop_floor : 0U) |
                                  ((flags & tl::building::flag_warehouse) != 0 ? window_flag::warehouse : 0U) |
                                  (church ? window_flag::church : 0U));
    // its flat detail with weather (`if wx and lens is None and not part and d < 760`; a houseboat is drawn as a boat
    // with weather): a plinth where it stands on the street (y0 <= 0.5); a cornice on an old house's or under a mansard
    // (`(yr and yr < 1800) or roof == 'mansard'`, a tradition's later) where its walls stand over 5 m; a shop floor's
    // glazing on its walls over 2.4 m on the street
    const bool facades = (!bl.v.part_tag || church) && (flags & tl::building::flag_houseboat) == 0;
    const bool plinth = facades && y0 <= 0.5;
    const bool cornice =
        facades && ((bl.v.year != 0 && bl.v.year < 1800) || is(bl.roof, "mansard")) && wall_top - y0 > 5;
    const bool shop = facades && (flags & tl::building::flag_shop_floor) != 0 && y0 <= 0.5;
    const auto facade_flags =
        static_cast<std::uint8_t>((plinth ? window_flag::plinth : 0U) | (cornice ? window_flag::cornice : 0U));
    for (std::size_t i = 0; i < n; ++i) {
        const V2 a = poly[i];
        const V2 bb = poly[(i + 1) % n];
        const double ex = bb.x - a.x;
        const double ez = bb.z - a.z;
        const double elen = hypot(ex, ez);
        if (elen < 0.3) continue;
        const V3 nrm = vnorm({ez, 0.0, -ex});
        const auto edge = static_cast<std::uint32_t>(i);
        // vdot(nrm, vsub(cam.C, ((a + bb) / 2, (y0 + wall_top) / 2))) > 0: the wall faces the eye
        const std::uint32_t face = b.face({.base = bl.v.wall_rgb,
                                           .normal = nrm,
                                           .test = {(a.x + bb.x) / 2, (y0 + wall_top) / 2, (a.z + bb.z) / 2},
                                           .lit = Lit::sun,
                                           .facing = true});
        // K8's samples of the edge: the wall as one cell, then its ncol × nrow cells when they are not one
        const std::uint32_t one = b.sample++;
        const auto wall = static_cast<std::uint32_t>(b.out.edged.size()); // its one-cell primitive, in either variant
        const int ncol = std::max(1, kt_int(elen / 9));
        const int nrow = std::max(1, std::min(3, kt_int((wall_top - y0) / 7)));
        const auto cell = [&](int r, int nr, int c, int nc) {
            const double ya = y0 + (((wall_top - y0) * r) / nr);
            const double yb = y0 + (((wall_top - y0) * (r + 1)) / nr);
            const double u0 = static_cast<double>(c) / nc;
            const double u1 = static_cast<double>(c + 1) / nc;
            return quad_at(a, bb, ya, yb, u0, u1);
        };
        if (ncol == 1 && nrow == 1) {
            b.edged(cell(0, 1, 0, 1), PrimKind::cell, static_cast<std::uint16_t>(variant | prim_flag::any), face, one,
                    Builder::slot(b.near_slot++), Builder::slot(b.far_slot++), {.edge = edge, .part = 0});
        } else {
            b.edged(cell(0, 1, 0, 1), PrimKind::cell, static_cast<std::uint16_t>(variant | prim_flag::far), face, one,
                    0, Builder::slot(b.far_slot++), {.edge = edge, .part = 0});
            for (int r = 0; r < nrow; ++r) {
                for (int c = 0; c < ncol; ++c) {
                    b.edged(cell(r, nrow, c, ncol), PrimKind::cell,
                            static_cast<std::uint16_t>(variant | prim_flag::near), face, b.sample++,
                            Builder::slot(b.near_slot++), 0,
                            {.edge = edge,
                             .part = static_cast<std::uint32_t>((r * ncol) + c),
                             .ncol = static_cast<std::uint32_t>(ncol),
                             .nrow = static_cast<std::uint32_t>(nrow)});
                }
            }
        }
        if (bl.skillion) {
            const double ta = bl.sk_top(a);
            const double tb = bl.sk_top(bb);
            if (pm::max(ta, tb) > wall_top + 0.05) {
                const std::array<V3, 4> q{V3{a.x, wall_top, a.z}, V3{bb.x, wall_top, bb.z}, V3{bb.x, tb, bb.z},
                                          V3{a.x, ta, a.z}};
                b.edged(q, PrimKind::skillion_wall, static_cast<std::uint16_t>(variant | prim_flag::any), face,
                        b.sample++, Builder::slot(b.near_slot++), Builder::slot(b.far_slot++),
                        {.edge = edge, .part = 0});
            }
        }
        // its facade: its shop glazing (its own path, after its cells), then its windows (a row per level, the shop
        // floor's too, bays by its length), their slots after its cells in both variants (a view's win_d can reach
        // past the cells' 460 m); its plinth and cornice, whose slots follow all its building's walls
        const int lv = static_cast<int>(bl.v.levels); // Kotlin's Long.toInt()
        const bool windows = windowed && elen > 2.2 && lv > 0;
        const bool glazing = shop && elen > 2.4;
        // a church's own windows (`if church and d < 520 ...: church_windows(...)`, whatever its levels), at its
        // windows' slots
        std::size_t bays = 0;
        std::size_t transoms = 0;
        if (church) {
            const std::vector<ChurchWindow> arched = church_windows(a, bb, y0, wall_top, {nrm.x, nrm.z});
            bays = arched.size();
            transoms = arched.empty() ? 0 : arched.front().lead.size() - 1;
        }
        if (windows || glazing || facade_flags != 0 || bays > 0) {
            const int cols = windows ? window_cols(elen, style) : 0;
            b.out.window_walls.push_back({.wall = wall,
                                          .first = b.windows,
                                          .levels = u16(windows ? lv : 0),
                                          .cols = u16(cols),
                                          .near_slot = Builder::slot(b.near_slot),
                                          .far_slot = Builder::slot(b.far_slot),
                                          .cell = b.cell,
                                          .style = style,
                                          .flags = static_cast<std::uint8_t>(window_flags | facade_flags |
                                                                             (glazing ? window_flag::glazing : 0U)),
                                          .church_windows = u16(static_cast<int>(bays)),
                                          .church_transoms = u16(static_cast<int>(transoms))});
            const std::uint32_t taken = (glazing ? 1U : 0U) + (windows || bays > 0 ? window_slots : 0U);
            if (windows) b.windows += static_cast<std::uint32_t>(lv) * static_cast<std::uint32_t>(cols);
            b.near_slot += taken;
            b.far_slot += taken;
        }
    }
    // the plinth's path, then the cornice's, after the walls (before the roof)
    b.facade = no_facade;
    if (facade_flags != 0) {
        b.facade = std::uint32_t{Builder::slot(b.near_slot)} | (std::uint32_t{Builder::slot(b.far_slot)} << 16U);
        b.near_slot += 2;
        b.far_slot += 2;
    }
}

void triangulate(std::span<const V2> ring, std::vector<std::uint32_t>& out) {
    out.clear();
    if (ring.size() < 3) return;
    std::vector<std::vector<std::array<double, 2>>> rings(1);
    rings[0].reserve(ring.size());
    for (const V2& p : ring) rings[0].push_back({p.x, p.z});
    out = mapbox::earcut<std::uint32_t>(rings);
}

double Building::sk_top(const V2& p) const noexcept {
    // skTop(p) = h - rh * ((p[0] * dirv[0] + p[1] * dirv[1]) - p0_) / span
    return h - ((rh * (((p.x * dirv.x) + (p.z * dirv.z)) - p0)) / span);
}

TileBuildings build_buildings(const BuildingInput& in) {
    ATLAS_ASSERT(in.tile != nullptr && in.head != nullptr);
    const tl::Tile& tile = *in.tile;
    const tl::Frame& frame = in.head->frame;
    TileBuildings out;
    out.level = in.level;
    out.cells_per_side = tile.header.cells_per_side;
    const std::size_t cells = std::size_t{tile.header.cells_per_side} * tile.header.cells_per_side;
    // the variants at this level, by their base feature's layer and source order (as K8 finds them)
    std::unordered_map<std::uint64_t, std::uint32_t> variants;
    const tl::Layer* var = tile.layer(tl::LayerId::variant);
    if (var != nullptr && in.level > 0) {
        for (std::uint32_t i = 0; i < var->count; ++i) {
            if (var->integer(tl::variant::tolerance, i) != in.level) continue;
            const auto layer = static_cast<std::uint64_t>(var->integer(tl::variant::layer, i));
            const auto index = static_cast<std::uint64_t>(var->integer(tl::variant::index, i));
            variants[(layer << 56U) | index] = i;
        }
    }
    TileBuildings made; // the primitives in the samples' order, laid out by cells below
    Builder b(made);
    b.paris = in.region.paris;
    std::vector<Made> spans;
    std::vector<std::uint32_t> kept;
    // the tile's outlines, whether each is a relation's: the building a part names (its parent) is keyed by it
    std::unordered_map<std::int64_t, bool> outlines;
    if (const tl::Layer* l = tile.layer(tl::LayerId::building); l != nullptr) {
        for (std::uint32_t i = 0; i < l->count; ++i) {
            outlines[l->integer(tl::building::id, i)] =
                l->string(tl::building::otype, i).value_or(std::string_view{}) == "relation";
        }
    }
    Building bl;
    for (const auto [layer_id, source] :
         {std::pair{tl::LayerId::building, Source::building}, std::pair{tl::LayerId::part, Source::part}}) {
        const tl::Layer* l = tile.layer(layer_id);
        if (l == nullptr) continue;
        const std::vector<std::uint8_t> cell_of = cells_of(*l, cells);
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const auto index = static_cast<std::uint64_t>(l->integer(tl::building::index, i));
            const auto it = variants.find((static_cast<std::uint64_t>(layer_id) << 56U) | index);
            bl.v = it != variants.end() ? values_of(*var, variant_slots, it->second) : values_of(*l, record_slots, i);
            ring_at(l->ring(tl::building::ring, i), in.level, frame, kept, bl.poly);
            // BuildingSamples::all: an outline drawn by its parts, or one without a height, is never drawn
            if (bl.v.hidden && source == Source::building) continue;
            if (!bl.v.has_h || bl.poly.empty()) continue;
            bl.source = source;
            Made m{.cell = cell_of[i],
                   .edged_first = made.edged.size(),
                   .plain_first = made.plain.size(),
                   .windows_first = made.window_walls.size()};
            b.cell = m.cell;
            b.item = static_cast<std::uint32_t>(made.items.size());
            b.near_slot = 0;
            b.far_slot = 0;
            b.boat_slot = 0;
            const bool boat = (bl.v.flags & tl::building::flag_houseboat) != 0;
            if (boat) houseboat(b, bl, in.region); // the boat, drawn when the moment has weather
            // the building as _draw_building draws it (a houseboat without weather)
            bl.h = bl.v.h;
            bl.y0 = bl.v.min;
            bl.roof = bl.v.roof;
            if (is(bl.roof, "quadruple_saltbox")) bl.roof = "hipped"; // closest shape this renderer draws
            bl.rh = in_set(bl.roof, pitched) && bl.v.roof_h != 0.0 ? bl.v.roof_h : 0.0;
            bl.wall_top = bl.h - bl.rh;
            bl.skillion = is(bl.roof, "skillion") && bl.rh != 0.0;
            if (bl.skillion) {
                bl.dirv = roof_dir(bl.v.direction.value_or(std::string_view{}), bl.poly);
                double lo = (bl.poly[0].x * bl.dirv.x) + (bl.poly[0].z * bl.dirv.z);
                double hi = lo;
                for (std::size_t k = 1; k < bl.poly.size(); ++k) {
                    const double pr = (bl.poly[k].x * bl.dirv.x) + (bl.poly[k].z * bl.dirv.z);
                    lo = pm::min(lo, pr);
                    hi = pm::max(hi, pr);
                }
                bl.p0 = lo;
                bl.span = pm::max(0.5, hi - bl.p0);
            } else {
                bl.dirv = {};
                bl.p0 = 0.0;
                bl.span = 1.0;
            }
            const std::uint16_t variant = boat ? prim_flag::dry : 0;
            walls(b, bl, variant);
            roofs(b, bl, variant);
            // the reflection's samples (K16 draws it): one per wall of 0.3 m or more of a building near the water
            if ((bl.v.flags & tl::building::flag_near_water) != 0 && !bl.v.hidden && bl.v.h != 0.0) {
                const std::size_t n = bl.poly.size();
                for (std::size_t k = 0; k < n; ++k) {
                    const V2 a = bl.poly[k];
                    const V2 c = bl.poly[(k + 1) % n];
                    if (hypot(c.x - a.x, c.z - a.z) >= 0.3) ++b.sample;
                }
            }
            // the item: Amsterdam.gatherItems' sort point (the footprint's centroid at h / 2, a part's between its base
            // and its top, with its offset), its radius cut at the centroid
            const V2 c = centroid(bl.poly);
            const double hv = bl.v.h != 0.0 ? bl.v.h : 0.0;
            const double sy = source == Source::building ? hv / 2 : (bl.v.min + hv) / 2;
            const double offset = source == Source::building ? 0.0 : -(0.01 * bl.v.min);
            BuildingItem item{.spec = {.x = c.x,
                                       .y = sy,
                                       .z = c.z,
                                       .offset = offset,
                                       .cut_x = c.x,
                                       .cut_z = c.z,
                                       .order = scene::order_of(source == Source::building ? 0 : 1, index),
                                       .kind = in.kind,
                                       .flags = scene::item_flag::radius,
                                       .slots = {},
                                       .min_depth = 2.0F,
                                       .radius = 0.0F,
                                       .tile = 0},
                              .source = source,
                              .record = i,
                              .id = l->integer(tl::building::id, i),
                              .relation = bl.v.relation,
                              .near_slots = Builder::slot(b.near_slot),
                              .far_slots = Builder::slot(b.far_slot),
                              .boat_slots = Builder::slot(b.boat_slot)};
            // every band draws at most this many primitives: the cells' variant by its band, a boat at any distance
            const std::uint32_t near_slots = std::max<std::uint32_t>(b.near_slot, b.boat_slot);
            const std::uint32_t far_slots = std::max<std::uint32_t>(b.far_slot, b.boat_slot);
            item.spec.slots = {near_slots, far_slots, far_slots, far_slots};
            // its owner as the likeness harness keys the port's (AnchorCompiler.osmOwner: a dict with a footprint is an
            // area, by its id, whether a way's or a relation's)
            item.spec.owner = scene::owner_key(owner_area, static_cast<std::uint64_t>(item.id));
            // the building it draws: a part's outline where it names one (FillIn asks about the outline)
            item.outline = item.id;
            item.outline_relation = item.relation;
            if (source == Source::part) {
                if (const auto parent = l->nullable_integer(tl::building::parent_id, i)) {
                    item.outline = *parent;
                    const auto outline = outlines.find(*parent);
                    item.outline_relation = outline != outlines.end() && outline->second;
                }
            }
            item.spec.building = scene::owner_key(owner_area, static_cast<std::uint64_t>(item.outline));
            m.edged_end = made.edged.size();
            m.plain_end = made.plain.size();
            m.windows_end = made.window_walls.size();
            // the sphere about the sort point that holds every corner it draws
            double r2 = 0.0;
            const auto reach = [&](const Prim& p) {
                for (const V3& q : p.p) {
                    const double dx = q.x - c.x;
                    const double dy = q.y - sy;
                    const double dz = q.z - c.z;
                    r2 = std::max(r2, (dx * dx) + (dy * dy) + (dz * dz));
                }
            };
            for (std::size_t k = m.edged_first; k < m.edged_end; ++k) reach(made.edged[k]);
            for (std::size_t k = m.plain_first; k < m.plain_end; ++k) reach(made.plain[k]);
            item.spec.radius = static_cast<float>(std::sqrt(r2) * 1.0001 + 0.01);
            made.items.push_back(item);
            made.item_facades.push_back(b.facade);
            spans.push_back(m);
        }
    }
    out.samples = b.sample;
    out.windows = b.windows;
    out.faces = std::move(made.faces);
    out.items = std::move(made.items);
    out.item_facades = std::move(made.item_facades);
    // the primitives by cell: each cell's buildings, then its parts, in the layers' order (the layers are sorted so);
    // the windowed walls with them, each naming its wall's new place
    std::vector<std::size_t> order(spans.size());
    for (std::size_t k = 0; k < order.size(); ++k) order[k] = k;
    std::ranges::stable_sort(order, [&](std::size_t a, std::size_t c) { return spans[a].cell < spans[c].cell; });
    out.edged_first.assign(cells + 1, 0);
    out.plain_first.assign(cells + 1, 0);
    out.window_walls_first.assign(cells + 1, 0);
    out.edged.reserve(made.edged.size());
    out.plain.reserve(made.plain.size());
    out.window_walls.reserve(made.window_walls.size());
    std::vector<std::uint32_t> moved(made.plain.size(), 0);       // a plain primitive's new place
    std::vector<std::uint32_t> edged_moved(made.edged.size(), 0); // an edged one's
    for (const std::size_t k : order) {
        const Made& m = spans[k];
        for (std::size_t e = m.edged_first; e < m.edged_end; ++e) {
            edged_moved[e] = static_cast<std::uint32_t>(out.edged.size());
            out.edged.push_back(made.edged[e]);
        }
        for (std::size_t p = m.plain_first; p < m.plain_end; ++p) {
            moved[p] = static_cast<std::uint32_t>(out.plain.size());
            out.plain.push_back(made.plain[p]);
        }
        for (std::size_t w = m.windows_first; w < m.windows_end; ++w) {
            WindowWall ww = made.window_walls[w];
            ww.wall = edged_moved[ww.wall];
            out.window_walls.push_back(ww);
        }
        out.edged_first[std::size_t{m.cell} + 1] = static_cast<std::uint32_t>(out.edged.size());
        out.plain_first[std::size_t{m.cell} + 1] = static_cast<std::uint32_t>(out.plain.size());
        out.window_walls_first[std::size_t{m.cell} + 1] = static_cast<std::uint32_t>(out.window_walls.size());
    }
    // cells with nothing take the end of the cell before them
    for (std::size_t c = 1; c <= cells; ++c) {
        out.edged_first[c] = std::max(out.edged_first[c], out.edged_first[c - 1]);
        out.plain_first[c] = std::max(out.plain_first[c], out.plain_first[c - 1]);
        out.window_walls_first[c] = std::max(out.window_walls_first[c], out.window_walls_first[c - 1]);
    }
    out.polygons = std::move(made.polygons);
    for (Polygon& p : out.polygons) {
        if (p.count > 0) p.first = moved[p.first];
    }
    return out;
}

} // namespace atlas::geometry
