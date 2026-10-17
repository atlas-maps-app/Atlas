// The label layer (rendertiles' Schema.LABEL): the canal name candidates in source order (each a way's middle vertex
// and the one before it, the baseline's direction, and its slot among the marks) and the place nodes with their rank.
#pragma once

#include "tiles/Schema.hpp"

#include <array>
#include <cstdint>

namespace atlas::tiles::label {

inline constexpr std::array<ColumnSpec, 13> columns = {{
    {"source", Codec::u8},   // 0 a canal name candidate (S['labels'], kind canal); 1 a place node
    {"index", Codec::delta}, // source order: in S['labels'], or in S['nodes']
    {"id", Codec::nvarz},    // a place node's id
    {"name", Codec::str},    // the name
    {"pos", Codec::point},   // a canal: the way's middle vertex (pts[n / 2]); a place: the node
    {"prev", Codec::point},  // a canal: the vertex before it (pts[n / 2 - 1]), the baseline's direction
    {"count", Codec::varu},  // a canal: the way's vertex count
    {"kind", Codec::str},    // a place: its place=* value
    {"ground_key",
     Codec::varu}, // a canal name: its slot among the marks (ground_key::marks, class marks_canal); 0: a place
    {"tag:place", Codec::str},
    {"tag:population", Codec::str},
    {"tag:capital", Codec::str},
    {"tag:admin_level", Codec::str},
}};

inline constexpr std::uint8_t source = slot_of(columns, "source");
inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t name = slot_of(columns, "name");
inline constexpr std::uint8_t pos = slot_of(columns, "pos");
inline constexpr std::uint8_t prev = slot_of(columns, "prev");
inline constexpr std::uint8_t count = slot_of(columns, "count");
inline constexpr std::uint8_t kind = slot_of(columns, "kind");
inline constexpr std::uint8_t ground_key = slot_of(columns, "ground_key");

/// The two sources of the layer.
inline constexpr std::uint8_t source_canal = 0;
inline constexpr std::uint8_t source_place = 1;

} // namespace atlas::tiles::label
