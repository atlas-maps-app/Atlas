// The road and line layers (rendertiles' Schema.ROAD and LINE): S['roads'] and the other lines the renders draw (tram
// tracks, rail, big roads, runways, power lines, marine works, a park's edge), stored whole in the tile of their
// reference point, so decorations measured along a line stay exact.
#pragma once

#include "tiles/Schema.hpp"

#include <array>
#include <cstdint>

namespace atlas::tiles::road {

inline constexpr std::array<ColumnSpec, 21> columns = {{
    {"index", Codec::delta},     // source order in S['roads']
    {"id", Codec::delta},        // the OSM way id
    {"line", Codec::geom},       // the centreline, whole
    {"w", Codec::centi},         // width, metres
    {"hw", Codec::str},          // highway class
    {"bridge", Codec::boolean},  // a bridge
    {"layer", Codec::centi},     // layer
    {"wguess", Codec::boolean},  // the width is the class's guess
    {"ground_key", Codec::varu}, // the slot in the renders' ground order
    {"marks_key", Codec::varu},  // the slot of the marks drawn along it (ground_key::marks, class marks_road); 0: none
    {"tag:lit", Codec::str},
    {"tag:surface", Codec::str},
    {"tag:surface:colour", Codec::str},
    {"tag:sidewalk", Codec::str},
    {"tag:sidewalk:both", Codec::str},
    {"tag:sidewalk:left", Codec::str},
    {"tag:sidewalk:right", Codec::str},
    {"tag:sidewalk:surface", Codec::str},
    {"tag:sidewalk:both:surface", Codec::str},
    {"tag:sidewalk:left:surface", Codec::str},
    {"tag:sidewalk:right:surface", Codec::str},
}};

inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t line = slot_of(columns, "line");
inline constexpr std::uint8_t w = slot_of(columns, "w");
inline constexpr std::uint8_t hw = slot_of(columns, "hw");
inline constexpr std::uint8_t bridge = slot_of(columns, "bridge");
inline constexpr std::uint8_t layer = slot_of(columns, "layer");
inline constexpr std::uint8_t ground_key = slot_of(columns, "ground_key");
inline constexpr std::uint8_t marks_key = slot_of(columns, "marks_key");

} // namespace atlas::tiles::road

namespace atlas::tiles::line {

inline constexpr std::array<ColumnSpec, 17> columns = {{
    {"source", Codec::u8},   // 0 S['rails'] (tram), 1 S['rail'], 2 S['big'], 3 S['aero'], 4 S['power'], 5 S['marine'],
                             // 6 S['park'], 7 S['gantries'], 8 S['conveyors']
    {"index", Codec::delta}, // source order in its list
    {"id", Codec::nvarz},    // the OSM id where the scene has one, or its tile carries the order across windows
    {"otype", Codec::str},   // relation for a multipolygon's ring, else null (a way): with id and part, its element
    {"part", Codec::varu},   // the ring's place among its element's rings (load/Scene.hpp's Origin)
    {"line", Codec::geom},   // the line, whole (a park's edge is its ring)
    {"kind", Codec::str},    // aeroway or man_made kind
    {"hw", Codec::str},      // a big road's class
    {"layer", Codec::centi}, // a big road's layer (0 where none is mapped): drawn 0.5 m higher per layer
    {"crane", Codec::boolean},   // a crane rail
    {"closed", Codec::boolean},  // a marine work mapped as a closed way
    {"h", Codec::centi},         // a gantry's or a conveyor's height, metres (null where none is mapped)
    {"ground_key", Codec::varu}, // the slot in the renders' ground order
    {"tag:lanes", Codec::str},
    {"tag:width", Codec::str},
    {"tag:ref", Codec::str},
    {"tag:tower:type", Codec::str},
}};

inline constexpr std::uint8_t source = slot_of(columns, "source");
inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t otype = slot_of(columns, "otype");
inline constexpr std::uint8_t part = slot_of(columns, "part");
inline constexpr std::uint8_t line = slot_of(columns, "line");
inline constexpr std::uint8_t kind = slot_of(columns, "kind");
inline constexpr std::uint8_t hw = slot_of(columns, "hw");
inline constexpr std::uint8_t layer = slot_of(columns, "layer");
inline constexpr std::uint8_t h = slot_of(columns, "h");
inline constexpr std::uint8_t ground_key = slot_of(columns, "ground_key");

} // namespace atlas::tiles::line
