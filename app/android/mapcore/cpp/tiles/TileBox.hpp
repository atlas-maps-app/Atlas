// A tile's bounds on OSM's grid (rendertiles' Grid.tileBox), which every tile's header carries and the reader checks:
// [south, west, north, east], each the grid integer nearest the tile's edge, so neighbouring tiles share their edges
// exactly. A column's edge is round((x / 2^z · 360 − 180) · 1e7); a row's is Web Mercator's,
// round(toDegrees(atan(sinh(π − 2π·y / 2^z))) · 1e7), with StrictMath's atan and sinh from pymath, so the core computes
// Kotlin's bits. Rounding is Java's Math.round (the nearest integer, ties toward positive infinity), as Grid.round.
#pragma once

#include "tiles/Payload.hpp"

#include <cstdint>

namespace atlas::tiles {

/// The grid longitude of column `x`'s west edge at zoom `z` (Grid.colEdge).
[[nodiscard]] std::int32_t column_edge(std::uint8_t z, std::uint32_t x) noexcept;

/// The grid latitude of row `y`'s north edge at zoom `z` (Grid.rowEdge); y = 2^z is the last row's south edge.
[[nodiscard]] std::int32_t row_edge(std::uint8_t z, std::uint32_t y) noexcept;

/// Whether the header's box is its tile's, edge for edge.
[[nodiscard]] bool tile_box_matches(const TileHeader& header) noexcept;

/// The grid integer nearest `degrees` (Grid.round): Math.round(degrees · 1e7), its low 32 bits as Kotlin's toInt keeps.
[[nodiscard]] std::int32_t grid_round(double degrees) noexcept;

} // namespace atlas::tiles
