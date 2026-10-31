// Stage T of the buildings (geometry/Walls.hpp): the tile's records at the level, in the sun samples' order (K8's
// sun/Samples.cpp: the building layer's records, then the part layer's, each as BuildingSamples::all walks it), so a
// running count gives each face its sample; the walls here, the roofs in geometry/Roofs.cpp, the boats in
// geometry/Houseboats.cpp; then everything laid out by the tile's cells.
#include "geometry/Walls.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "geometry/ChurchWindows.hpp"
#include "geometry/Ground.hpp"
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
    case PrimKind::band: return "band";
    case PrimKind::corner_board: return "corner_board";
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
    std::uint8_t kind;
    std::uint8_t no_win;
    std::uint8_t year;
    std::uint8_t rmat_draw;
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
            tl::slot_of(c, "kind"),
            tl::slot_of(c, "no_win"),
            tl::slot_of(c, "year"),
            tl::slot_of(c, "rmat_draw")};
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
    v.kind = l.string(s.kind, i);
    v.no_win = l.boolean(s.no_win, i).value_or(false);
    v.year = l.nullable_integer(s.year, i).value_or(0); // amsterdam.py: yr = b['year'] or 0
    v.rmat_draw = l.string(s.rmat_draw, i);
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

Material material_of(const Values& v) noexcept {
    // material_face: `md == 'glass'`; `md == 'metal' and kind in ('industrial', 'hangar', 'sports', 'shed',
    // 'terminal', 'canopy', None)`; `md in ('limestone', 'stone', 'plaster') and lv >= 5 and kind in ('home', 'office',
    // 'hotel') and fh * ppm > 2.2`; `kind == 'parking' and fh * ppm > 2.0`, lv = max(1, levels); a building of the
    // third kind whose storeys are too small on the screen draws none, as the fourth asks a parking garage
    static constexpr std::array<std::string_view, 6> industrial = {"industrial", "hangar",   "sports",
                                                                   "shed",       "terminal", "canopy"};
    static constexpr std::array<std::string_view, 3> fronts = {"limestone", "stone", "plaster"};
    static constexpr std::array<std::string_view, 3> tall = {"home", "office", "hotel"};
    if (is(v.mat_draw, "glass")) return Material::glass;
    if (is(v.mat_draw, "metal") && (!v.kind.has_value() || in_set(v.kind, industrial))) return Material::metal;
    if (in_set(v.mat_draw, fronts) && std::max<std::int64_t>(1, v.levels) >= 5 && in_set(v.kind, tall)) {
        return Material::balconies;
    }
    if (is(v.kind, "parking")) return Material::parking;
    return Material::none;
}

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
    case PrimKind::deck:
    case PrimKind::band:
    case PrimKind::corner_board: break; // trad_wall's face() calls: a fill alone
    }
    return 0;
}

double kit_eaves_of(const Values& v, std::optional<double> eaves) noexcept {
    // generic.py: eaves=P.get('eaves', 0) if b.get('roof') in ('gabled', 'hipped') else 0
    return eaves && (is(v.roof, "gabled") || is(v.roof, "hipped")) ? *eaves : 0.0;
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
    // render/amsterdam.py's _draw_building: per footprint edge of 0.3 m or more, its cells, a kit's details on it, the
    // skillion's wall, the wall's shop glazing and its windows; after them the building's plinth and cornice
    const std::span<const V2> poly = bl.poly;
    const std::size_t n = poly.size();
    const double y0 = bl.y0;
    const double wall_top = bl.wall_top;
    // K8's windowed walls (sun/Samples.cpp): not a part (but a church's), not a houseboat, not no_win, with levels
    const std::uint64_t flags = bl.v.flags;
    const bool church = (flags & tl::building::flag_church) != 0;
    const bool modern = (flags & tl::building::flag_modern) != 0;
    const bool windowed =
        !b.lean && (!bl.v.part_tag || church) && !is(bl.v.btype, "houseboat") && !bl.v.no_win && bl.v.has_levels;
    const WindowStyle style = b.paris && !modern && in_set(bl.v.mat_draw, paris_fronts)
                                  ? WindowStyle::paris
                                  : (modern ? WindowStyle::modern : WindowStyle::plain);
    const auto window_flags =
        static_cast<std::uint8_t>(((flags & tl::building::flag_shop_floor) != 0 ? window_flag::shop_floor : 0U) |
                                  ((flags & tl::building::flag_warehouse) != 0 ? window_flag::warehouse : 0U) |
                                  (church ? window_flag::church : 0U));
    // its flat detail with weather (`if wx and lens is None and not part and d < 760`; a houseboat is drawn as a boat
    // with weather): a plinth where it stands on the street (y0 <= 0.5); a cornice on an old house's, under a mansard
    // or where its kit gives it one (`(yr and yr < 1800) or roof == 'mansard' or (TD and TD.get('cornice'))`) where its
    // walls stand over 5 m; a shop floor's glazing on its walls over 2.4 m on the street
    const bool facades = !b.lean && (!bl.v.part_tag || church) && (flags & tl::building::flag_houseboat) == 0;
    const bool plinth = facades && y0 <= 0.5;
    const bool cornice = facades &&
                         ((bl.v.year != 0 && bl.v.year < 1800) || is(bl.roof, "mansard") ||
                          (flags & tl::building::flag_kit_cornice) != 0) &&
                         wall_top - y0 > 5;
    const bool shop = facades && (flags & tl::building::flag_shop_floor) != 0 && y0 <= 0.5;
    const auto facade_flags =
        static_cast<std::uint8_t>((plinth ? window_flag::plinth : 0U) | (cornice ? window_flag::cornice : 0U));
    // its material face (`if wx and lens is None and not part and elen > 2.0 and d < win_d * 1.35`): its storeys
    // (lv = max(1, levels)) and their height, and a metal wall's ribs' colour (tohex(mulc(base, 0.62)))
    const Material material = facades ? material_of(bl.v) : Material::none;
    const std::int64_t storeys = std::max<std::int64_t>(1, bl.v.levels);
    const double fh = (wall_top - y0) / static_cast<double>(storeys);
    const std::uint32_t ribs = [&] {
        const Rgba c = darken(rgba(bl.v.wall_rgb), 0.62);
        return (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | std::uint32_t{c.b};
    }();
    // a tradition's kit's details on each wall (trad_wall, drawn with weather, as TD needs `wx`), but a part's (a
    // church's parts have them) and a houseboat's (a boat with weather): its stucco ground floor's band where it stands
    // on the street (`y0 <= b.get('_gy', 0.0) + 0.5`: no render tile carries a terrain town's own ground), its timber
    // boards' lines and corner boards, and the shadow of its eaves under a hipped roof or along a gabled one's ridge
    // (trad_ridge, where the roof has a height); the lines in the wall's drawn colour darkened
    const bool kit = (flags & tl::building::flag_kit) != 0 && facades;
    const std::optional<tiles::KitBand> band =
        kit && (flags & tl::building::flag_kit_band) != 0 && y0 <= 0.5 ? b.kit_band : std::nullopt;
    const std::optional<tiles::KitBoards> boards =
        kit && (flags & tl::building::flag_kit_boards) != 0 ? b.kit_boards : std::nullopt;
    const double eaves = kit ? kit_eaves_of(bl.v, b.kit_eaves) : 0.0;
    const bool hipped = is(bl.roof, "hipped");
    const std::optional<V2> ridge =
        eaves != 0.0 && bl.rh != 0.0 ? std::optional<V2>(kit_ridge(poly, is(bl.v.orient, "across"))) : std::nullopt;
    const std::uint32_t lines_rgb = [&] {
        const Rgba c = darken(rgba((flags & tl::building::flag_wall_drawn) != 0 ? bl.v.wall_rgb : 0x888888U), 0.62);
        return (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | std::uint32_t{c.b};
    }();
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
        // the kit's details this wall has: its boards' lines where it takes two boards or more (`range(1, nb)`), its
        // corner boards where it is over 1 m long, the shadow of its eaves (|e · u| / max(elen, 1e-6) > 0.7 along
        // the ridge); its cells' leads under theirs
        const int nb = boards ? kt_int(elen / boards->spacing) : 0;
        const bool corners = boards && elen > 1.0;
        const bool shadow =
            eaves != 0.0 &&
            (hipped || (ridge && std::abs((ex * ridge->x) + (ez * ridge->z)) / pm::max(elen, 1e-6) > 0.7));
        const std::uint16_t detailed = band || nb > 1 || corners || shadow ? prim_flag::kit : 0;
        // K8's samples of the edge: the wall as one cell, then its ncol × nrow cells when they are not one
        const std::uint32_t one = b.sample++;
        const auto wall = static_cast<std::uint32_t>(b.out.edged.size()); // its one-cell primitive, in either variant
        const int ncol = b.lean ? 1 : std::max(1, kt_int(elen / 9));
        const int nrow = b.lean ? 1 : std::max(1, std::min(3, kt_int((wall_top - y0) / 7)));
        const auto cell = [&](int r, int nr, int c, int nc) {
            const double ya = y0 + (((wall_top - y0) * r) / nr);
            const double yb = y0 + (((wall_top - y0) * (r + 1)) / nr);
            const double u0 = static_cast<double>(c) / nc;
            const double u1 = static_cast<double>(c + 1) / nc;
            return quad_at(a, bb, ya, yb, u0, u1);
        };
        if (ncol == 1 && nrow == 1) {
            b.edged(cell(0, 1, 0, 1), PrimKind::cell, static_cast<std::uint16_t>(variant | prim_flag::any | detailed),
                    face, one, Builder::slot(b.near_slot++), Builder::slot(b.far_slot++), {.edge = edge, .part = 0});
        } else {
            b.edged(cell(0, 1, 0, 1), PrimKind::cell, static_cast<std::uint16_t>(variant | prim_flag::far | detailed),
                    face, one, 0, Builder::slot(b.far_slot++), {.edge = edge, .part = 0});
            for (int r = 0; r < nrow; ++r) {
                for (int c = 0; c < ncol; ++c) {
                    b.edged(cell(r, nrow, c, ncol), PrimKind::cell,
                            static_cast<std::uint16_t>(variant | prim_flag::near | detailed), face, b.sample++,
                            Builder::slot(b.near_slot++), 0,
                            {.edge = edge,
                             .part = static_cast<std::uint32_t>((r * ncol) + c),
                             .ncol = static_cast<std::uint32_t>(ncol),
                             .nrow = static_cast<std::uint32_t>(nrow)});
                }
            }
        }
        // trad_wall, after the cells, each detail at its slot in both variants: the band (lit by its own sample, K8's
        // next), the boards' lines, the two corner boards (lit by day as they face the sun), the eaves' shadow; the
        // band and the corner boards on the wall's plane (their faces' normal and test point the wall's)
        if (detailed != 0) {
            const V3 test{(a.x + bb.x) / 2, (y0 + wall_top) / 2, (a.z + bb.z) / 2};
            const auto drawn = static_cast<std::uint16_t>(variant | prim_flag::weather | prim_flag::any);
            KitWall kw{.item = b.item, .face = face, .a = a, .ex = ex, .ez = ez, .nrm = nrm, .y0 = y0, .top = wall_top};
            if (band) {
                // bh = min(bd['h'], (wall_top - y0) * 0.45); quad_at(a, bb, y0, y0 + bh, 0, 1, 0.02, nrm)
                const double bh = pm::min(band->h, (wall_top - y0) * 0.45);
                const std::uint32_t f = b.face({.base = band->colour,
                                                .normal = nrm,
                                                .test = test,
                                                .lit = Lit::sun,
                                                .facing = true,
                                                .kit = KitPart::band});
                b.plain(quad_at(a, bb, y0, y0 + bh, 0.0, 1.0, 0.02, nrm), 4, PrimKind::band, drawn, f, b.sample++,
                        Builder::slot(b.near_slot++), Builder::slot(b.far_slot++), {.edge = edge, .part = 0});
            }
            if (nb > 1) {
                kw.boards = static_cast<std::uint32_t>(nb);
                kw.spacing = boards->spacing;
                kw.rgb = lines_rgb;
                kw.lines_near = Builder::slot(b.near_slot++);
                kw.lines_far = Builder::slot(b.far_slot++);
            }
            if (corners) {
                // cw = 0.16 / elen; quad_at(a, bb, y0, wall_top, u0, u1, 0.03, nrm) for (0, cw) and (1 - cw, 1)
                const double cw = 0.16 / elen;
                const std::uint32_t f = b.face({.base = boards->corner,
                                                .normal = nrm,
                                                .test = test,
                                                .lit = Lit::day,
                                                .facing = true,
                                                .kit = KitPart::corner});
                const std::array<std::array<double, 2>, 2> spans{{{0.0, cw}, {1.0 - cw, 1.0}}};
                for (std::uint32_t k = 0; k < 2; ++k) {
                    b.plain(quad_at(a, bb, y0, wall_top, spans.at(k)[0], spans.at(k)[1], 0.03, nrm), 4,
                            PrimKind::corner_board, drawn, f, no_sample, Builder::slot(b.near_slot++),
                            Builder::slot(b.far_slot++), {.edge = edge, .part = k});
                }
            }
            if (shadow) {
                // quad_at(a, bb, wall_top - min(1.1, E * 1.7), wall_top, 0, 1, 0.025, nrm)
                kw.shadow = true;
                kw.shade = quad_at(a, bb, wall_top - pm::min(1.1, eaves * 1.7), wall_top, 0.0, 1.0, 0.025, nrm);
                kw.shadow_near = Builder::slot(b.near_slot++);
                kw.shadow_far = Builder::slot(b.far_slot++);
            }
            if (kw.boards > 1 || kw.shadow) b.out.kit_walls.push_back(kw);
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
        // its facade: its shop glazing (its own path, after its cells), its material face, then its windows (a row per
        // level, the shop floor's too, bays by its length), their slots after its cells in both variants (a view's
        // win_d can reach past the cells' 460 m); its plinth and cornice, whose slots follow all its building's walls
        const int lv = static_cast<int>(bl.v.levels); // Kotlin's Long.toInt()
        const bool windows = windowed && elen > 2.2 && lv > 0;
        const bool glazing = shop && elen > 2.4;
        // a church's own windows (`if church and d < 520 ...: church_windows(...)`, whatever its levels), at its
        // windows' slots
        std::size_t bays = 0;
        std::size_t transoms = 0;
        if (church && !b.lean) {
            const std::vector<ChurchWindow> arched = church_windows(a, bb, y0, wall_top, {nrm.x, nrm.z});
            bays = arched.size();
            transoms = arched.empty() ? 0 : arched.front().lead.size() - 1;
        }
        const Material faced = elen > 2.0 ? material : Material::none;
        if (windows || glazing || facade_flags != 0 || bays > 0 || faced != Material::none) {
            const int cols = windows ? window_cols(elen, style) : 0;
            // its rows: its windows', else its storeys where it has a material face (its floors and decks); a
            // building:levels past what the record holds (no building has 4095) keeps that many
            const std::int64_t rows =
                std::min<std::int64_t>(windows ? lv : (faced != Material::none ? storeys : 0), max_window_levels);
            b.out.window_walls.push_back({.wall = wall,
                                          .first = b.windows,
                                          .levels = u16(static_cast<int>(rows)),
                                          .cols = u16(cols),
                                          .near_slot = Builder::slot(b.near_slot),
                                          .far_slot = Builder::slot(b.far_slot),
                                          .cell = b.cell,
                                          .style = style,
                                          .flags = static_cast<std::uint8_t>(window_flags | facade_flags |
                                                                             (glazing ? window_flag::glazing : 0U)),
                                          .church_windows = u16(static_cast<int>(bays)),
                                          .church_transoms = u16(static_cast<int>(transoms)),
                                          .material = faced,
                                          .fh = fh,
                                          .elen = elen,
                                          .ribs = faced == Material::metal ? ribs : 0U});
            const std::uint32_t taken =
                (glazing ? 1U : 0U) + material_slots(faced) + (windows || bays > 0 ? window_slots : 0U);
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

namespace {

/// The records stage T walks, in the sun samples' order: the building layer's, then the part layer's, with each
/// record's cell.
struct Records {
    std::array<const tl::Layer*, 2> layers{};
    std::array<std::vector<std::uint8_t>, 2> cells;
    std::size_t count = 0; ///< both layers'
    /// The tile's outlines, whether each is a relation's: the building a part names (its parent) is keyed by it.
    std::unordered_map<std::int64_t, bool> outlines;
};

/// A dense tile's walk in blocks of about this many records, at most this many.
constexpr std::size_t records_a_block = 192;
constexpr std::size_t max_blocks = 8;

constexpr std::array<std::pair<tl::LayerId, Source>, 2> walked{std::pair{tl::LayerId::building, Source::building},
                                                               std::pair{tl::LayerId::part, Source::part}};

/// What the walk makes of a run of the records (ENGINE.md gain 9: a dense tile's buildings in blocks, each on a
/// worker): its primitives, faces, n-gons, items and windowed walls as the walk makes them, numbered from its first,
/// and the sun samples and windows it counts.
struct Block {
    TileBuildings made;
    std::vector<Made> spans;
    std::uint32_t samples = 0;
    std::uint32_t windows = 0;
};

/// Records [from, to) of the walk into `out`, as one walk over every record makes them.
void walk(const BuildingInput& in, const Records& records, const tl::Layer* var,
          const std::unordered_map<std::uint64_t, std::uint32_t>& variants, std::size_t from, std::size_t to,
          Block& out) {
    const tl::Frame& frame = in.head->frame;
    TileBuildings& made = out.made;
    std::vector<Made>& spans = out.spans;
    Builder b(made);
    b.lean = in.tile->header.level < 14;
    b.paris = in.region.paris;
    b.kit_eaves = in.region.kit_eaves;
    b.kit_band = in.region.kit_band;
    b.kit_boards = in.region.kit_boards;
    std::vector<std::uint32_t> kept;
    Building bl;
    std::size_t first = 0; // the walk's index of the layer's first record
    for (std::size_t w = 0; w < walked.size(); ++w) {
        const auto [layer_id, source] = walked[w];
        const tl::Layer* l = records.layers[w];
        if (l == nullptr) continue;
        const std::vector<std::uint8_t>& cell_of = records.cells[w];
        const std::size_t begin = std::max(from, first);
        const std::size_t end = std::min<std::size_t>(to, first + l->count);
        for (auto i = static_cast<std::uint32_t>(begin - std::min(begin, first)); first + i < end; ++i) {
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
            const std::size_t beams_first = made.beams.size();
            const std::size_t kits_first = made.kit_walls.size();
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
                    const auto outline = records.outlines.find(*parent);
                    item.outline_relation = outline != records.outlines.end() && outline->second;
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
            for (std::size_t k = beams_first; k < made.beams.size(); ++k) {
                Prim beam{};
                beam.p = {made.beams[k].from, made.beams[k].to, made.beams[k].to, made.beams[k].to};
                reach(beam);
            }
            // a kit's walls' lines and shadows, out from their walls (the lines 0.02 m, within the shadow's 0.025 m
            // out of the whole wall)
            for (std::size_t k = kits_first; k < made.kit_walls.size(); ++k) {
                const KitWall& kw = made.kit_walls[k];
                Prim out_of{};
                out_of.p = quad_at(kw.a, {kw.a.x + kw.ex, kw.a.z + kw.ez}, kw.y0, kw.top, 0.0, 1.0, 0.025, kw.nrm);
                reach(out_of);
            }
            item.spec.radius = static_cast<float>(std::sqrt(r2) * 1.0001 + 0.01);
            made.items.push_back(item);
            made.item_facades.push_back(b.facade);
            spans.push_back(m);
        }
        first += l->count;
    }
    out.samples = b.sample;
    out.windows = b.windows;
}

} // namespace

TileBuildings build_buildings(const BuildingInput& in) {
    ATLAS_ASSERT(in.tile != nullptr && in.head != nullptr);
    const tl::Tile& tile = *in.tile;
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
    Records records;
    for (std::size_t w = 0; w < walked.size(); ++w) {
        records.layers[w] = tile.layer(walked[w].first);
        if (records.layers[w] == nullptr) continue;
        records.cells[w] = cells_of(*records.layers[w], cells);
        records.count += records.layers[w]->count;
    }
    if (const tl::Layer* l = tile.layer(tl::LayerId::building); l != nullptr) {
        for (std::uint32_t i = 0; i < l->count; ++i) {
            records.outlines[l->integer(tl::building::id, i)] =
                l->string(tl::building::otype, i).value_or(std::string_view{}) == "relation";
        }
    }
    // The walk in blocks of records where the work is shared (a dense tile's), each on a worker, else in one; then the
    // blocks in order, each one's numbers after those of the blocks before it: the primitives, faces, n-gons, items
    // and windowed walls one walk over every record makes, in the samples' order.
    const std::size_t most = in.share ? std::clamp<std::size_t>(in.blocks, 1, max_blocks) : std::size_t{1};
    const std::size_t blocks = std::clamp<std::size_t>(records.count / records_a_block, 1, most);
    std::vector<Block> walks(blocks);
    const auto walk_block = [&](std::size_t k) {
        walk(in, records, var, variants, records.count * k / blocks, records.count * (k + 1) / blocks, walks[k]);
    };
    if (blocks > 1) {
        in.share(blocks, walk_block);
    } else {
        walk_block(0);
    }
    TileBuildings made = std::move(walks[0].made); // the primitives in the samples' order, laid out by cells below
    std::vector<Made> spans = std::move(walks[0].spans);
    std::uint32_t samples = walks[0].samples;
    std::uint32_t windows = walks[0].windows;
    if (blocks > 1) {
        std::size_t edged_count = 0;
        std::size_t plain_count = 0;
        for (const Block& k : walks) {
            edged_count += k.made.edged.size();
            plain_count += k.made.plain.size();
        }
        made.edged.reserve(edged_count);
        made.plain.reserve(plain_count);
    }
    for (std::size_t n = 1; n < blocks; ++n) {
        Block& k = walks[n];
        const auto edged = static_cast<std::uint32_t>(made.edged.size());
        const auto plain = static_cast<std::uint32_t>(made.plain.size());
        const auto faces = static_cast<std::uint32_t>(made.faces.size());
        const auto polygons = static_cast<std::uint32_t>(made.polygons.size());
        const auto items = static_cast<std::uint32_t>(made.items.size());
        const std::size_t walls = made.window_walls.size();
        const auto after = [&](Prim q) {
            q.face += faces;
            if (q.sample != no_sample) q.sample += samples;
            q.item += items;
            if (q.polygon != no_polygon) q.polygon += polygons;
            return q;
        };
        for (const Prim& q : k.made.edged) made.edged.push_back(after(q));
        for (const Prim& q : k.made.plain) made.plain.push_back(after(q));
        made.faces.insert(made.faces.end(), k.made.faces.begin(), k.made.faces.end());
        for (Polygon& g : k.made.polygons) {
            g.face += faces;
            g.first += plain;
            g.item += items;
            made.polygons.push_back(std::move(g));
        }
        made.items.insert(made.items.end(), k.made.items.begin(), k.made.items.end());
        made.item_facades.insert(made.item_facades.end(), k.made.item_facades.begin(), k.made.item_facades.end());
        for (WindowWall w : k.made.window_walls) {
            w.wall += edged;
            if (w.style != WindowStyle::cabin) w.first += windows; // a cabin's panes are none of the tile's windows
            made.window_walls.push_back(w);
        }
        // the hoist beams, a kit roof's courses and ridges and a kit's walls' lines and shadows, by their items and
        // faces
        for (RoofBeam r : k.made.beams) {
            r.item += items;
            r.face += faces;
            made.beams.push_back(r);
        }
        for (RoofCourses r : k.made.courses) {
            r.item += items;
            r.face += faces;
            made.courses.push_back(r);
        }
        for (RoofRidge r : k.made.ridges) {
            r.item += items;
            made.ridges.push_back(r);
        }
        for (KitWall w : k.made.kit_walls) {
            w.item += items;
            w.face += faces;
            made.kit_walls.push_back(w);
        }
        for (Made m : k.spans) {
            m.edged_first += edged;
            m.edged_end += edged;
            m.plain_first += plain;
            m.plain_end += plain;
            m.windows_first += walls;
            m.windows_end += walls;
            spans.push_back(m);
        }
        samples += k.samples;
        windows += k.windows;
    }
    out.samples = samples;
    out.windows = windows;
    out.faces = std::move(made.faces);
    out.items = std::move(made.items);
    out.item_facades = std::move(made.item_facades);
    out.beams = std::move(made.beams);
    out.courses = std::move(made.courses);
    out.ridges = std::move(made.ridges);
    out.kit_walls = std::move(made.kit_walls);
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
    // A level's tile (a frame cell's L13 or L12, drawn beyond the finest level's reach): its faces lit by their samples
    // where its tile has bits (the coarse level's, in its lane's sun scene: scene/SunGlobals.hpp), else by their facing
    // alone, as a deck is (the far level's; a tile drawn before its first bits), never as if in the shade.
    if (tile.header.level < 14) {
        for (Face& f : out.faces) {
            if (f.lit == Lit::sun) f.lit = Lit::level;
        }
    }
    return out;
}

} // namespace atlas::geometry
