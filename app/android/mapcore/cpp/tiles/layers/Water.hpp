// The water and quay layers (rendertiles' Schema.WATER and QUAY): S['water'] and S['water_holes'], clipped exactly at
// the tile's edges, one piece per ring and tile (a ring that leaves the tile and comes back is joined along the edge;
// the renders test each ring whole, its area in pixels over 0.2: a reader adds the signed areas of the pieces that
// share index and hole, and every piece turns the way its ring turns), and the water's edges that carry a quay wall, as
// water_edges finds them, each in the tile of its midpoint.
#pragma once

#include "tiles/Schema.hpp"

#include <array>
#include <cstdint>

namespace atlas::tiles::water {

inline constexpr std::array<ColumnSpec, 9> columns = {{
    {"index", Codec::delta},     // source order in S['water'] (or S['water_holes'] for holes)
    {"hole", Codec::boolean},    // an island: a ring of S['water_holes']
    {"ring", Codec::geom},       // the ring clipped at the tile's edges: one piece per ring and tile
    {"ring.keep", Codec::keep},  // the piece's keep codes, clip points always kept
    {"shore", Codec::boolean},   // a natural shore (S['natural_shore']): no quay wall
    {"level", Codec::centi},     // the water level below the street, metres; null: the region's WATER_Y
    {"tidal", Codec::boolean},   // its level follows the tide of the moment
    {"hole_of", Codec::nvarz},   // a hole's water ring, as its index in S['water']
    {"ground_key", Codec::varu}, // the slot in the renders' ground order
}};

inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t hole = slot_of(columns, "hole");
inline constexpr std::uint8_t ring = slot_of(columns, "ring");
inline constexpr std::uint8_t ring_keep = slot_of(columns, "ring.keep");
inline constexpr std::uint8_t shore = slot_of(columns, "shore");
inline constexpr std::uint8_t level = slot_of(columns, "level");
inline constexpr std::uint8_t tidal = slot_of(columns, "tidal");
inline constexpr std::uint8_t hole_of = slot_of(columns, "hole_of");
inline constexpr std::uint8_t ground_key = slot_of(columns, "ground_key");

} // namespace atlas::tiles::water

namespace atlas::tiles::quay {

inline constexpr std::array<ColumnSpec, 8> columns = {{
    {"index", Codec::delta},  // the edge's order in water_edges(S)
    {"hole", Codec::boolean}, // an island's edge
    {"ring", Codec::varu},    // the edge's ring: its index in S['water'] (or S['water_holes'] for an island)
    {"owner", Codec::nvarz},  // the ring the edge counts as (Edge.r) when not its own
    {"edge", Codec::geom},    // the edge: its two vertices, the water ring's
    {"flip", Codec::boolean}, // the normal into the water is -(ez, -ex)/L rather than (ez, -ex)/L
    {"level", Codec::centi},  // the level of the edge's ring; null: the region's WATER_Y
    {"tidal", Codec::boolean},
}};

inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t hole = slot_of(columns, "hole");
inline constexpr std::uint8_t ring = slot_of(columns, "ring");
inline constexpr std::uint8_t owner = slot_of(columns, "owner");
inline constexpr std::uint8_t edge = slot_of(columns, "edge");
inline constexpr std::uint8_t flip = slot_of(columns, "flip");
inline constexpr std::uint8_t level = slot_of(columns, "level");
inline constexpr std::uint8_t tidal = slot_of(columns, "tidal");

} // namespace atlas::tiles::quay
