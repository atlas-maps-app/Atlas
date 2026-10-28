// Stage T of the doors and their numbers (ENGINE.md step 4, the facades; render/amsterdam.py's facade_details, its
// doors): a render tile's addresses that open onto a street wall, each a door the frame may draw. Where facades are
// drawn (the living map: regions set no_facades), stage P gives a node with a house number or a shop its building (the
// thing layer's `building`, facade_details' `b`) and that building's street wall (`wall`: a corner of its ring, as
// street_wall chooses it), and files it in its building's tile: here the building's ring, its id and its height are
// the tile's own. A door is an address that is not a business (a business's is a shopfront: step 4's next): its node's
// place in S['nodes'], its id, its position and its house number's leading digits; its building's id; the point on its
// wall and the wall's direction (geometry/StreetWall.hpp: street_wall's own arithmetic over the ring); and an item,
// sorted as the port sorts the door (its building's centroid at half its height, less 0.03), its radius cut at its
// node, its sphere about that point holding all it draws. Which doors draw, with which numbers, the frame decides at
// its camera (passes/Doors.hpp), the radius cut its own, so the frustum never changes which address draws a door.
#pragma once

#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <cstdint>
#include <optional>
#include <vector>

namespace atlas::geometry {

/// The painter slots a door's item takes: its door (filled, then outlined), its stoop, its plate and its number.
inline constexpr std::uint32_t door_slots = 5;

/// The doors' items' list in the renders' gather order (scene::order_of): the facades, after every other list.
inline constexpr std::uint32_t facades_list = 4;

/// One door: an address on a street wall, as facade_details reads it.
struct Door {
    std::uint32_t node = 0; ///< its place in S['nodes']
    std::int64_t id = 0;    ///< its OSM node (the door's colour is id % 5)
    double x = 0;           ///< the node (frame metres)
    double z = 0;
    std::int64_t building = 0; ///< its building's OSM id: its key's first part, with round(qx) and round(qz)
    double qx = 0;             ///< the point on its street wall
    double qz = 0;
    double ux = 0; ///< the wall's direction, unit
    double uz = 0;
    std::optional<std::int64_t> number; ///< its house number's leading digits (re.match(r'\d+')), if any
};

/// A tile's doors in the tile's order (S['nodes']' within each cell), and their items, one each.
struct TileDoors {
    std::vector<Door> doors;
    std::vector<scene::ItemSpec> items;
};

/// What the doors read: the tile, its pack's head (its frame), and the items' kind.
struct DoorInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    std::uint16_t kind = 0;
};

/// The tile's doors.
[[nodiscard]] TileDoors build_doors(const DoorInput& in);

} // namespace atlas::geometry
