#include "geometry/Things.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/Trees.hpp"
#include "tiles/layers/Points.hpp"

#include <cmath>

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
    case ThingKind::rack: return 1.6;
    }
    return 4.5;
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
    return out;
}

} // namespace atlas::geometry
