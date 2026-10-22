// The scene cut into render tiles (ENGINE.md step 3, stage P): rendertiles' SceneToTiles for the living map, so the
// core writes from the same data the tiles the packer writes, byte for byte (tiles/TileWriter.hpp writes them). Every
// record of the scene becomes a feature in the tile and cell of its reference point (a building's centroid rounded onto
// the grid, a line's middle vertex, a ring's box centre, a node), with the values the packer derives for it:
//   - buildings and parts: the ring on OSM's grid, the loader's fields (heights, year, levels, roof, orientation, the
//     material drawn, the guessed tags, the places and their opening hours, hidden), the tags the renders read, the
//     wall's and roof's base colours as surface_colour resolves them, and the derived flags, a quay edge within 16 m
//     among them (near_water over water_edges);
//   - the quay edges water_edges finds; trees; things (lamps, furniture, places, and the doors and shopfronts on the
//     facades); roads with their ground and marks keys; tram rails; areas; canal names and place nodes;
//   - the water, each ring clipped at the edges of every tile it reaches (rendertiles' Clip, in exact integers).
// Each feature's box and top bound what the renders draw from it (rendertiles' Drawn), and each tile's cell table holds
// their union. A vertex off OSM's grid refuses the cut, as the packer's build fails: Overpass prints every coordinate
// on it. The living map is cut at L14 alone (its pack has no other level) and carries no keep codes.
//
// Where the packer's build fails on a value, the core cuts on, named here:
//   - a wall or roof colour `#…` that Java cannot read as three hex pairs (hx throws) is taken as unmapped: the clay;
//   - a tree that is also a place: the tree layer has no column for its style, which is left out;
//   - a piece of a water ring that turns against its ring (a ring that crosses itself) is left out.
#pragma once

#include "load/Scene.hpp"
#include "tiles/Sections.hpp"
#include "tiles/TileWriter.hpp"

#include <expected>
#include <span>
#include <string>
#include <vector>

namespace atlas::load {

/// Why a scene cannot be cut: what showed it, as the packer names it (`building 3 (123456): z = … is not on the grid`).
struct CutError {
    std::string what;
};

/// The living map's render tiles at L14: the scene cut in `frame`, the tiles in the order a feature first reached
/// them.
[[nodiscard]] std::expected<std::vector<tiles::TileFill>, CutError> cut_living(const Scene& scene,
                                                                               const tiles::Frame& frame);

/// The same region loaded at another tolerance of the ladder (its views' and the live map's bands'): where a
/// building's or part's attributes differ there, a variant feature carries them for that tolerance.
struct CutVariant {
    double tolerance = 0.0;
    const Scene* scene = nullptr;
};

/// A region's render tiles at L14 and L12 (SceneToTiles for a region, its scene loaded whole at tolerance 0): rings
/// with keep codes, the variants at `variants`' tolerances, and what regions.py adds to the living map's lists. L12
/// holds the ground (the areas and the land cover) and the water at the ladder's 4 m, and the labels.
[[nodiscard]] std::expected<std::vector<tiles::TileFill>, CutError>
cut_region(const Scene& scene, const tiles::Frame& frame, std::span<const CutVariant> variants);

} // namespace atlas::load
