// The tree, thing and item layers (rendertiles' Schema.TREE, THING and ITEM): S['trees'], the other nodes the renders
// draw (lamps, furniture, bollards, doors with their numbers, shops and cafés), and a region's specials (cranes, masts,
// towers, pylons, turbines).
#pragma once

#include "tiles/Schema.hpp"

#include <array>
#include <cstdint>

namespace atlas::tiles::tree {

inline constexpr std::array<ColumnSpec, 16> columns = {{
    {"index", Codec::delta},  // source order in S['trees']
    {"node", Codec::delta},   // its order in S['nodes']
    {"id", Codec::delta},     // the OSM node id
    {"pos", Codec::point},    // the trunk
    {"h", Codec::centi},      // height, metres
    {"crown", Codec::centi},  // crown diameter, metres
    {"gy", Codec::centi},     // the ground's height under it (terrain towns)
    {"guessed", Codec::strs}, // the tags the loader guessed
    {"tag:natural", Codec::str},
    {"tag:highway", Codec::str},
    {"tag:leaf_type", Codec::str},
    {"tag:leaf_cycle", Codec::str},
    {"tag:genus", Codec::str},
    {"tag:species", Codec::str},
    {"tag:taxon", Codec::str},
    {"tag:addr:housenumber", Codec::strn},
}};

inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t node = slot_of(columns, "node");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t pos = slot_of(columns, "pos");
inline constexpr std::uint8_t h = slot_of(columns, "h");
inline constexpr std::uint8_t crown = slot_of(columns, "crown");
inline constexpr std::uint8_t gy = slot_of(columns, "gy");
inline constexpr std::uint8_t tag_leaf_type = slot_of(columns, "tag:leaf_type");
inline constexpr std::uint8_t tag_leaf_cycle = slot_of(columns, "tag:leaf_cycle");
inline constexpr std::uint8_t tag_genus = slot_of(columns, "tag:genus");
inline constexpr std::uint8_t tag_species = slot_of(columns, "tag:species");
inline constexpr std::uint8_t tag_taxon = slot_of(columns, "tag:taxon");

} // namespace atlas::tiles::tree

namespace atlas::tiles::thing {

inline constexpr std::array<ColumnSpec, 20> columns = {{
    {"index", Codec::delta},        // source order in S['nodes']
    {"id", Codec::delta},           // the OSM node id
    {"pos", Codec::point},          // where it stands
    {"poi", Codec::boolean},        // in S['pois']
    {"style.cat", Codec::str},      // PoiStyle: category (null without a style)
    {"style.col", Codec::str},      // PoiStyle: colour
    {"style.kind", Codec::str},     // PoiStyle: kind
    {"style.brand", Codec::str},    // PoiStyle: brand
    {"style.bg", Codec::str},       // PoiStyle: sign background
    {"style.fg", Codec::str},       // PoiStyle: sign foreground
    {"gy", Codec::centi},           // the ground's height under it (terrain towns)
    {"door", Codec::boolean},       // it has a house number: a door where facades are drawn
    {"door_number", Codec::ndelta}, // the number on its door: the house number's leading digits
    {"building", Codec::nvarz},     // where facades are drawn: the building whose facade carries its door or shopfront,
                                    // as its index in S['buildings'] (maybe in another tile); null for none
    {"tag:natural", Codec::str},
    {"tag:highway", Codec::str},
    {"tag:amenity", Codec::str},
    {"tag:barrier", Codec::str},
    {"tag:opening_hours", Codec::str},
    {"tag:outdoor_seating", Codec::str},
}};

inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t pos = slot_of(columns, "pos");
inline constexpr std::uint8_t poi = slot_of(columns, "poi");
inline constexpr std::uint8_t door = slot_of(columns, "door");
inline constexpr std::uint8_t door_number = slot_of(columns, "door_number");
inline constexpr std::uint8_t building = slot_of(columns, "building");
inline constexpr std::uint8_t gy = slot_of(columns, "gy");
inline constexpr std::uint8_t tag_highway = slot_of(columns, "tag:highway");
inline constexpr std::uint8_t tag_amenity = slot_of(columns, "tag:amenity");
inline constexpr std::uint8_t tag_barrier = slot_of(columns, "tag:barrier");

} // namespace atlas::tiles::thing

namespace atlas::tiles::item {

inline constexpr std::array<ColumnSpec, 10> columns = {{
    {"source", Codec::u8},   // 0 S['cranes'], 1 S['masts'], 2 S['mtowers'], 3 S['towers'] (pylons), 4 S['turbines']
    {"index", Codec::delta}, // source order in its list
    {"id", Codec::nvarz},    // the OSM id
    {"pos", Codec::point}, // where it stands: the grid point itself for a mapped node, the nearest grid point otherwise
    {"x", Codec::centi},   // its x in the frame, metres, where the scene has it off the grid; null where pos is exact
    {"z", Codec::centi},   // its z likewise
    {"poly", Codec::geom}, // a crane's mapped outline (empty for nodes)
    {"h", Codec::centi},   // height, metres
    {"rotor", Codec::centi}, // a turbine's rotor diameter, metres
    {"tags", Codec::tags},   // every tag (items are few)
}};

inline constexpr std::uint8_t source = slot_of(columns, "source");
inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t pos = slot_of(columns, "pos");
inline constexpr std::uint8_t x = slot_of(columns, "x");
inline constexpr std::uint8_t z = slot_of(columns, "z");
inline constexpr std::uint8_t poly = slot_of(columns, "poly");
inline constexpr std::uint8_t h = slot_of(columns, "h");
inline constexpr std::uint8_t rotor = slot_of(columns, "rotor");
inline constexpr std::uint8_t tags = slot_of(columns, "tags");

} // namespace atlas::tiles::item
