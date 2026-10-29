#include "geometry/Doors.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"
#include "geometry/StreetWall.hpp"
#include "geometry/Trees.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <limits>
#include <unordered_map>

namespace atlas::geometry {

namespace {

namespace tl = atlas::tiles;

/// How far a door's elements reach from its point on the wall: its stoop 0.6 m out and 0.75 m to each side, its
/// plate's 2.4 m at most (a number of eleven characters) about its anchor 2.75 m up, with room to spare.
constexpr double door_reach = 6.0;

/// The most depth of a door's node on the ground at which the doors pass may draw it (passes/Doors.cpp: 75 m a metre
/// up), with the metre that height may add looking down and the metre its tiles' box test spares.
constexpr float door_depth = 77.0F;

} // namespace

TileDoors build_doors(const DoorInput& in) {
    TileDoors out;
    const tl::Layer* things = in.tile->layer(tl::LayerId::thing);
    const tl::Layer* buildings = in.tile->layer(tl::LayerId::building);
    if (things == nullptr || buildings == nullptr) return out;
    const tl::Frame& frame = in.head->frame;
    // the tile's buildings by their place in S['buildings']
    std::unordered_map<std::int64_t, std::uint32_t> building_of;
    for (std::uint32_t i = 0; i < buildings->count; ++i)
        building_of.emplace(buildings->integer(tl::building::index, i), i);
    std::vector<V2> ring;
    for (std::uint32_t i = 0; i < things->count; ++i) {
        // facade_details: an address (`num`), not a business (`biz`: a place style other than a service's)
        if (!things->boolean(tl::thing::door, i).value_or(false)) continue;
        if (const auto cat = things->string(tl::thing::style_cat, i); cat && *cat != "service") continue;
        const auto building = things->nullable_integer(tl::thing::building, i);
        const auto wall = things->nullable_integer(tl::thing::wall, i);
        if (!building || !wall) continue;
        // filed with its building (stage P files every door in its building's tile)
        const auto at = building_of.find(*building);
        if (at == building_of.end()) continue;
        const tl::RingView r = buildings->ring(tl::building::ring, at->second);
        if (*wall < 0 || static_cast<std::size_t>(*wall) >= r.size()) continue;
        ring.clear();
        for (std::size_t k = 0; k < r.size(); ++k) ring.emplace_back(frame.x(r.lon[k]), frame.z(r.lat[k]));
        Door d;
        const tl::GridPoint p = things->point(tl::thing::pos, i);
        d.x = frame.x(p.lon);
        d.z = frame.z(p.lat);
        const auto k = static_cast<std::size_t>(*wall);
        const std::optional<StreetWall> w = street_wall_at(ring[k], ring[(k + 1) % ring.size()], d.x, d.z);
        if (!w) continue;
        d.node = static_cast<std::uint32_t>(things->integer(tl::thing::index, i));
        d.id = things->integer(tl::thing::id, i);
        d.building = buildings->integer(tl::building::id, at->second);
        // street_wall's (qx, qz, ux / L, uz / L)
        d.qx = w->qx;
        d.qz = w->qz;
        d.ux = w->ux / w->length;
        d.uz = w->uz / w->length;
        d.number = things->nullable_integer(tl::thing::door_number, i);
        // its item: cb = cam.cam((cx, b['h'] / 2, cz)), depth = cb[2] - 0.03; no least depth (facade_details' items
        // join the gather's after its cut), the radius cut at the node (`far(n['x'], n['z'])`)
        const V2 c = centroid(ring);
        const double h = buildings->decimal(tl::building::h, at->second).value_or(0.0);
        const double reach = pymath::hypot(d.qx - c.x, d.qz - c.z) + pymath::fabs(h / 2) + door_reach;
        out.items.push_back(
            {.x = c.x,
             .y = h / 2,
             .z = c.z,
             .offset = -0.03,
             .cut_x = d.x,
             .cut_z = d.z,
             .order = scene::order_of(facades_list, d.node),
             .kind = in.kind,
             .flags = scene::item_flag::radius,
             .slots = {door_slots, door_slots, door_slots, door_slots},
             .min_depth = -std::numeric_limits<float>::infinity(),
             .max_depth = door_depth, // its node (the cut point) beyond the pass's reach: no rank
             .radius = static_cast<float>(reach),
             .tile = 0,
             .owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(d.id)),
             .building = scene::owner_key(scene::owner_kind_area, static_cast<std::uint64_t>(d.building))});
        out.doors.push_back(d);
    }
    return out;
}

} // namespace atlas::geometry
