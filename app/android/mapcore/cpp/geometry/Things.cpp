#include "geometry/Things.hpp"

#include "base/pymath/PyMath.hpp"
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

/// amsterdam.py's railing: each segment of `line` cut into pieces of 2 m, each one a thing at its ends with its posts'
/// gaps; `source` numbers them in the road's order.
void railing_pieces(std::span<const P2> line, std::int64_t id, std::uint32_t road, std::uint32_t side,
                    std::uint16_t kind, TileThings& out) {
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
                                 .order = scene::order_of(railings_list, (std::uint64_t{road} << 16U) |
                                                                             (std::uint64_t{side} << 15U) | piece),
                                 .kind = kind,
                                 .flags = 0,
                                 .slots = {1, 1, 1, 1},
                                 .min_depth = 0.8F,
                                 .radius = static_cast<float>(pymath::hypot((k2 - k) / 2, 0.6) + 0.01),
                                 .tile = 0,
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
    const tl::Layer* layer = in.tile->layer(tl::LayerId::thing);
    if (layer == nullptr) return out;
    const tl::Frame& frame = in.head->frame;
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
                             .owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(t.id))});
        out.things.push_back(t);
    }
    // the bridges' railings: for s_ in (-1, 1), off = ribbon(r['pts'], r['w'] / 2 + 0.15), off[:n] or off[n:][::-1]
    if (const tl::Layer* roads = in.tile->layer(tl::LayerId::road)) {
        std::vector<P2> pts;
        std::vector<P2> off;
        for (std::uint32_t i = 0; i < roads->count; ++i) {
            if (!roads->boolean(tl::road::bridge, i).value_or(false)) continue;
            const tl::RingView r = roads->ring(tl::road::line, i);
            if (r.size() < 2) continue;
            pts.clear();
            for (std::size_t k = 0; k < r.size(); ++k) pts.push_back({frame.x(r.lon[k]), frame.z(r.lat[k])});
            const double w = roads->decimal(tl::road::w, i).value_or(0.0);
            off.clear();
            ribbon(pts, (w / 2) + 0.15, off);
            const std::size_t n = pts.size();
            const std::int64_t id = roads->integer(tl::road::id, i);
            const auto road = static_cast<std::uint32_t>(roads->integer(tl::road::index, i));
            railing_pieces(std::span<const P2>(off).first(n), id, road, 0, in.kind, out);
            std::vector<P2> right(off.begin() + static_cast<std::ptrdiff_t>(n), off.end());
            std::ranges::reverse(right);
            railing_pieces(right, id, road, 1, in.kind, out);
        }
    }
    return out;
}

} // namespace atlas::geometry
