// OSM's grid of 1e-7 degrees seen from a scene (rendertiles' Grid and Cells, the pack's PackTiles): a frame's metres
// back onto the grid, exactly or not at all, and the tile and cell a grid point lies in. Stage P's cut of a scene into
// render tiles (ENGINE.md step 3) places every feature with them, as the packer places it, so the core's tiles are the
// packer's.
#pragma once

#include "tiles/Chunks.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <optional>

namespace atlas::tiles {

/// The grid latitude whose frame z is exactly `z` (Grid.latOf: the nearest and its four neighbours either way, a zero
/// of the same sign), or nothing.
[[nodiscard]] std::optional<std::int32_t> grid_lat(const Frame& frame, double z) noexcept;
/// The grid longitude whose frame x is exactly `x` (Grid.lonOf), or nothing.
[[nodiscard]] std::optional<std::int32_t> grid_lon(const Frame& frame, double x) noexcept;

/// A point in the frame's metres rounded onto the grid (SceneToTiles' `nearest`): only for choosing a tile and a cell.
[[nodiscard]] GridPoint nearest_grid(const Frame& frame, double x, double z) noexcept;

/// PackTiles.tileX: the column at zoom `z` holding longitude `lon` (degrees), clamped to the world.
[[nodiscard]] std::uint32_t tile_x(double lon, std::uint8_t z) noexcept;
/// PackTiles.tileY: the row (0 at the north) at zoom `z` holding latitude `lat`, clamped to Mercator's limit.
[[nodiscard]] std::uint32_t tile_y(double lat, std::uint8_t z) noexcept;

/// Cells.tileOf: the tile at zoom `z` whose grid box holds the grid point (west edge ≤ lon < east edge, south edge ≤
/// lat < north edge; Grid.col and Grid.row).
[[nodiscard]] TileKey tile_of(std::uint8_t z, std::int32_t lat, std::int32_t lon) noexcept;

/// A tile's grid box, [south, west, north, east] (Grid.tileBox).
[[nodiscard]] std::array<std::int32_t, 4> tile_box(const TileKey& tile) noexcept;

/// Cells.cellOf: the cell of the grid point within `tile`, counted row by row from the north-west (a point on the box's
/// east or south edge goes to the last cell).
[[nodiscard]] std::uint16_t cell_of(const TileKey& tile, std::int32_t lat, std::int32_t lon) noexcept;

/// Cells.centre: the centre of a grid box [south, west, north, east], rounded down, as (lat, lon).
[[nodiscard]] GridPoint centre_of(const std::array<std::int32_t, 4>& box) noexcept;

} // namespace atlas::tiles
