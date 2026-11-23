#include "geometry/Things.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/BigRoad.hpp"
#include "geometry/Ribbons.hpp"
#include "geometry/Trees.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <span>

namespace atlas::geometry {

namespace {

namespace tl = atlas::tiles;

/// The reach of what each kind draws from the gather's point (x, 1, z), metres: a lamp to its cap over 4.2 m, a
/// bench's 1.8 m by 0.72 m, a basket, a bollard, a rack's three 0.7 m arcs a metre apart.
[[nodiscard]] double reach_of(ThingKind kind) noexcept {
    switch (kind) {
    case ThingKind::lamp: return 4.5;
    case ThingKind::bench: return 1.5;
    case ThingKind::basket:
    case ThingKind::bollard: return 1.0;
    case ThingKind::rack:
    case ThingKind::railing: return 1.6;
    }
    return 4.5;
}

/// A way's owner kind in scene::owner_key (AnchorItems' OwnerType.WAY).
constexpr std::uint8_t owner_way = 2;

/// The pieces railing_pieces cuts `line` into, from its segments' lengths alone: ceil(length / 2) a segment (its loop's
/// k = 0, 2, 4, ... below the length, each exact), none for one without length. Each term is a whole number, so the
/// sum is exact in any order.
[[nodiscard]] double railing_piece_count(std::span<const P2> line) noexcept {
    double pieces = 0.0;
    for (std::size_t i = 0; i + 1 < line.size(); ++i) {
        const std::array<double, 2> pa{line[i].x, line[i].z};
        const std::array<double, 2> pb{line[i + 1].x, line[i + 1].z};
        const double lg = pymath::dist(pa, pb);
        if (lg > 0.0) pieces += std::ceil(lg / 2.0);
    }
    return pieces;
}

/// amsterdam.py's railing: each segment of `line` cut into pieces of 2 m, each one a thing at its ends with its posts'
/// gaps, numbered in the road's order (the pieces of a side past its 16,383rd, 32.8 km on, share the last number); the
/// renders' (`app` false) or the app's map's, their items flagged so that a frame ranks the one set it draws.
void railing_pieces(std::span<const P2> line, std::int64_t id, std::uint32_t road, std::uint32_t side, bool app,
                    std::uint16_t kind, std::uint8_t cell, TileThings& out) {
    std::uint32_t piece = 0;
    for (std::size_t i = 0; i + 1 < line.size(); ++i) {
        const P2 a = line[i];
        const P2 b = line[i + 1];
        const std::array<double, 2> pa{a.x, a.z};
        const std::array<double, 2> pb{b.x, b.z};
        const double lg = pymath::dist(pa, pb);
        double k = 0.0;
        while (k < lg) {
            const double k2 = pymath::min(lg, k + 2.0);
            // pa = (a[0] + (b[0] - a[0]) * k / Lg, ...), pb alike at k2
            Thing t;
            t.id = id;
            t.node = road;
            t.kind = ThingKind::railing;
            t.x = a.x + ((b.x - a.x) * k / lg);
            t.z = a.z + ((b.z - a.z) * k / lg);
            t.bx = a.x + ((b.x - a.x) * k2 / lg);
            t.bz = a.z + ((b.z - a.z) * k2 / lg);
            t.gy = railing_foot;
            t.posts = static_cast<std::uint32_t>(std::max(1.0, std::trunc((k2 - k) / 0.32)));
            // its item: c = cam.cam((sx, y0 + 0.5, sz)), from 0.8 m, no radius cut
            const double sx = (t.x + t.bx) / 2;
            const double sz = (t.z + t.bz) / 2;
            out.items.push_back({.x = sx,
                                 .y = railing_foot + 0.5,
                                 .z = sz,
                                 .offset = 0.0,
                                 .cut_x = sx,
                                 .cut_z = sz,
                                 .order = scene::order_of(
                                     railings_list, (std::uint64_t{road} << 16U) | (std::uint64_t{side} << 15U) |
                                                        (app ? 1U << 14U : 0U) | std::min(piece, (1U << 14U) - 1U)),
                                 .kind = kind,
                                 .flags = app ? scene::item_flag::app : scene::item_flag::renders,
                                 .slots = {1, 1, 1, 1},
                                 .min_depth = 0.8F,
                                 .radius = static_cast<float>(pymath::hypot((k2 - k) / 2, 0.6) + 0.01),
                                 .tile = 0,
                                 .cell = cell,
                                 .owner = scene::owner_key(owner_way, static_cast<std::uint64_t>(id))});
            out.things.push_back(t);
            ++piece;
            k = k2;
        }
    }
}

} // namespace

std::optional<ThingKind> thing_kind(std::optional<std::string_view> highway, std::optional<std::string_view> amenity,
                                    std::optional<std::string_view> barrier) noexcept {
    if (highway && *highway == "street_lamp") return ThingKind::lamp;
    if (amenity) {
        if (*amenity == "bench") return ThingKind::bench;
        if (*amenity == "bicycle_parking") return ThingKind::rack;
        if (*amenity == "waste_basket") return ThingKind::basket;
    }
    if (barrier && (*barrier == "bollard" || *barrier == "block")) return ThingKind::bollard;
    return std::nullopt;
}

TileThings build_things(const ThingInput& in) {
    TileThings out;
    const tl::Frame& frame = in.head->frame;
    // the street furniture; a tile without any still has its bridges' railings
    if (const tl::Layer* layer = in.tile->layer(tl::LayerId::thing)) {
        for (std::uint32_t i = 0; i < layer->count; ++i) {
            const std::optional<ThingKind> kind =
                thing_kind(layer->string(tl::thing::tag_highway, i), layer->string(tl::thing::tag_amenity, i),
                           layer->string(tl::thing::tag_barrier, i));
            if (!kind) continue;
            Thing t;
            const tl::GridPoint p = layer->point(tl::thing::pos, i);
            t.x = frame.x(p.lon);
            t.z = frame.z(p.lat);
            t.id = layer->integer(tl::thing::id, i);
            t.node = static_cast<std::uint32_t>(layer->integer(tl::thing::index, i));
            t.gy = layer->decimal(tl::thing::gy, i).value_or(0.0);
            t.kind = *kind;
            const std::uint32_t slots = thing_slots[static_cast<std::size_t>(*kind)];
            // its item: amsterdam.py's gather, c = cam.cam((n['x'], 1, n['z'])), from 2 m, the radius cut at it
            const double up = std::abs(t.gy - 1) + reach_of(*kind);
            out.items.push_back({.x = t.x,
                                 .y = 1.0,
                                 .z = t.z,
                                 .offset = 0.0,
                                 .cut_x = t.x,
                                 .cut_z = t.z,
                                 .order = scene::order_of(nodes_list, t.node),
                                 .kind = in.kind,
                                 .flags = scene::item_flag::radius,
                                 .slots = {slots, slots, slots, slots},
                                 .min_depth = 2.0F,
                                 .radius = static_cast<float>(pymath::hypot(reach_of(*kind), up) + 0.01),
                                 .tile = 0,
                                 .cell = layer->cell_of(i),
                                 .owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(t.id))});
            out.things.push_back(t);
        }
    }
    // the bridges' railings: for s_ in (-1, 1), off = ribbon(r['pts'], r['w'] / 2 + 0.15), off[:n] or off[n:][::-1];
    // then the app's map's, at the edges of its street as wide as its way there (ground.slang's GROUND_WIDE), each side
    // twice as far from the centreline: ribbon(r['pts'], r['w'] + 0.3). Each bridge's four railings are counted from
    // their lines before any piece is made, and kept while the tile's pieces stay within most_tile_railing_pieces.
    if (const tl::Layer* roads = in.tile->layer(tl::LayerId::road)) {
        std::vector<P2> pts;
        std::array<std::vector<P2>, 2> off; // the renders' ribbon and the app's
        std::vector<P2> right;
        double pieces = 0.0; // the tile's so far
        bool full = false;
        for (std::uint32_t i = 0; i < roads->count; ++i) {
            if (!roads->boolean(tl::road::bridge, i).value_or(false)) continue;
            const tl::RingView r = roads->ring(tl::road::line, i);
            if (r.size() < 2) continue;
            if (full) {
                out.railings_cut += 4;
                continue;
            }
            pts.clear();
            for (std::size_t k = 0; k < r.size(); ++k) pts.push_back({frame.x(r.lon[k]), frame.z(r.lat[k])});
            // a width no road has (not finite, or past 0 to widest_road) reads as an absent one, as a big road's does
            // (geometry/BigRoad.hpp), never as the bound: a railing's bends part by twice its offset
            const double mapped = roads->decimal(tl::road::w, i).value_or(0.0);
            const double w = mapped >= 0.0 && mapped <= widest_road ? mapped : 0.0;
            const std::size_t n = pts.size();
            double bridge = 0.0;
            for (const bool app : {false, true}) {
                std::vector<P2>& o = off.at(app ? 1 : 0);
                o.clear();
                ribbon(pts, app ? w + 0.3 : (w / 2) + 0.15, o);
                bridge += railing_piece_count(std::span<const P2>(o).first(n)) +
                          railing_piece_count(std::span<const P2>(o).subspan(n));
            }
            if (pieces + bridge > most_tile_railing_pieces) {
                full = true;
                out.railings_cut += 4;
                continue;
            }
            pieces += bridge;
            const std::int64_t id = roads->integer(tl::road::id, i);
            const auto road = static_cast<std::uint32_t>(roads->integer(tl::road::index, i));
            const std::uint8_t cell = roads->cell_of(i);
            for (const bool app : {false, true}) {
                const std::vector<P2>& o = off.at(app ? 1 : 0);
                railing_pieces(std::span<const P2>(o).first(n), id, road, 0, app, in.kind, cell, out);
                right.assign(o.begin() + static_cast<std::ptrdiff_t>(n), o.end());
                std::ranges::reverse(right);
                railing_pieces(right, id, road, 1, app, in.kind, cell, out);
            }
        }
    }
    return out;
}

} // namespace atlas::geometry
