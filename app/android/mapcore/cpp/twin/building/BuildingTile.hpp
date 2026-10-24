// The buildings' records in the walls heap (plan-vector D5.7, K10): the layout the pass's stage T writes, its vertex
// stage reads (shaders/building/building.slangh) and its twin reads back, for one tile at one level. One range a tile:
//   - the primitives, 4 uint4 each (corners c0-c3 as floats relative to the tile's south-west corner, each with a word:
//     the face, the sun sample, the item and flags, the slots), the edged ones (a wall as one cell, a skillion's wall,
//     a houseboat's walls) then the plain ones (roof faces, n-gons' triangles);
//   - the near cells, two words each (two a uint4): the wall's primitive in the edged list, and its place on the wall
//     with the wall's columns and rows (building_cell_prim cuts the cell from its wall);
//   - the windowed walls, a uint4 each (shaders/building/windows.slangh's BuildingWindowWall: its wall's primitive in
//     the edged list with its style and flags, its first window, its rows and bays, its slots), by cell;
//   - each face's geometry, 2 uint4 (its normal and area as floats, its test point and flags);
//   - each frame slot's colours, 2 words a face (lit, unlit, 0xBBGGRR), which the CPU's light writes per tile and
//     moment (its inputs stay on the CPU: BuildingTile::lights);
//   - the items' rows in the item table, one word each, written once the tile is taken.
// Plain data: no Vulkan, so the twin and its tests read it as the GPU does.
#pragma once

#include "geometry/Walls.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::shaders {
struct Theme;
} // namespace atlas::shaders

namespace atlas::passes {

/// An n-gon the frame fills as triangles and the twin emits whole, as the port's face() gets it: its ring's points in
/// BuildingTile::ring_points, and its triangles among the plain primitives.
struct BuildingRing {
    std::uint32_t first = 0;  ///< its first point
    std::uint32_t points = 0; ///< how many
    std::uint32_t prim = 0;   ///< its first triangle in the plain list
    std::uint32_t prims = 0;  ///< how many
};

/// What lights a face (light.slang's light(): its base colour and its normal in double, the port's), and whether by day
/// alone (building.slangh's BUILDING_FACE_DAY).
struct BuildingLight {
    std::array<double, 3> normal{};
    std::uint32_t base = 0; ///< 0xRRGGBB
    std::uint32_t flags = 0;
};

/// What the buildings' stage T keeps of a tile (PassTile::cpu): where its records lie in its range of the walls heap
/// (uint4 indexes from the range's start), its counts, each cell's primitives and near cells, its faces' light inputs,
/// its n-gons, and its items' owners.
struct BuildingTile {
    std::uint32_t edged = 0;        ///< primitives drawn with their edge, from `prims_at`
    std::uint32_t plain = 0;        ///< then the plain ones
    std::uint32_t cells = 0;        ///< near cells, from `cells_at`
    std::uint32_t window_walls = 0; ///< windowed walls, from `windows_at`
    std::uint32_t windows = 0;      ///< their windows (the tile's window group of sun samples)
    std::uint32_t faces = 0;
    std::uint32_t items = 0;
    std::uint32_t prims_at = 0;   ///< the primitives, 4 uint4 each: the edged ones, then the plain
    std::uint32_t cells_at = 0;   ///< the near cells, two words each
    std::uint32_t windows_at = 0; ///< the windowed walls, a uint4 each
    std::uint32_t faces_at = 0;   ///< each face's geometry, 2 uint4
    std::uint32_t colours_at = 0; ///< each frame slot's colours: slot s from word colours_at · 4 + s · 2 · faces
    std::uint32_t rows_at = 0;    ///< the items' rows, four a uint4
    std::uint32_t vectors = 0;    ///< the range's size in uint4
    std::uint8_t cells_per_side = 0;
    /// The tile's cell c's edged primitives: [edged_first[c], edged_first[c + 1]); its near cells, plain ones and
    /// windowed walls alike.
    std::vector<std::uint32_t> edged_first;
    std::vector<std::uint32_t> cells_first;
    std::vector<std::uint32_t> plain_first;
    std::vector<std::uint32_t> windows_first;
    /// Each windowed wall's first window among the tile's by cell (its windows: [window_at[i], window_at[i + 1])).
    std::vector<std::uint32_t> window_at;
    /// Each windowed wall's item (its wall's), so the frame's jobs take only the walls whose windows it draws.
    std::vector<std::uint16_t> window_items;
    /// Each face's light inputs (the CPU's light, per tile and moment, and the twin's).
    std::vector<BuildingLight> lights;
    /// The n-gons, in their triangles' order, and their rings' points relative to the tile's corner (x, y, z each, the
    /// floats the heap holds of the triangles' corners).
    std::vector<BuildingRing> rings;
    std::vector<float> ring_points;
    /// Each item's OSM id, and its owner: bit 0 a part (else a building), bit 1 a relation's id (else a way's).
    std::vector<std::int64_t> ids;
    std::vector<std::uint8_t> owners;
};

/// Every face's colours at the theme `theme`, lit and unlit (0xBBGGRR: light.slang's light() and tohex in binary64, the
/// port's bits), two words a face, into `out` (2 · lights.size() words).
void building_colours(const shaders::Theme& theme, std::span<const BuildingLight> lights, std::span<std::uint32_t> out);

/// The uint4 words of a tile's buildings (BuildingTile's layout, `layout` filled), from stage T's geometry: the
/// primitives' corners relative to the tile's south-west corner (`origin_x`, `origin_z`, frame metres) as floats.
[[nodiscard]] std::vector<std::array<std::uint32_t, 4>> heap_words(const geometry::TileBuildings& g, double origin_x,
                                                                   double origin_z, BuildingTile& layout);

/// A run of primitives a draw covers: [first, end) of a tile's list.
struct BuildingRun {
    std::uint32_t first = 0;
    std::uint32_t end = 0;
};

/// A tile's visible cells (bit c for cell c) as runs of the list whose cell c holds [first[c], first[c + 1]), runs
/// with few primitives between them joined, appended to `out`.
void building_runs(std::uint64_t cells, std::span<const std::uint32_t> first, std::vector<BuildingRun>& out);

} // namespace atlas::passes
