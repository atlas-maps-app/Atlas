// A frame cell's levels (ENGINE.md, one pack per country, step 7: step 5's levels per cell), which the map draws beyond
// the finest level's reach (4.7 km of the eye), derived by stage P from the cell's L14 windows alone, so a level's tile
// is a function of its places' packs, the same bytes whoever derives it.
//
// L13 (coarse_level), drawn from 4.7 to 9.4 km of the eye: four places a tile, cells_per_side 4, so a place is 2 × 2 of
// its cells and each feature keeps the quarter of its place its L14 cell lies in; a place drawn at L14 and its
// neighbour's L13 cells never draw a feature twice. Every feature the frame draws beyond 4.7 km, each in its own look:
// its ring at the ladder's 1.5 m (its keep codes: the renders' 0.42 CSS px at 4.7 km), its line at 1.5 m by
// Douglas-Peucker; what the renders leave out at 4.7 km left out (a face under 0.12 px², a ground polygon under 0.2
// px²; the doors and street furniture, the quay walls, the cranes, gantries, conveyors and power lines, which they draw
// only closer; a shop's places and hours, a kit's shutters and party walls; the canal names and the variants of the
// live map's bands).
//
// L12 (far_level), drawn from 9.4 km to the far plane (30 km at most) and the first stand-in of every view: sixteen
// places a tile, cells_per_side 4, so each cell is one place whole. The same rules at twice the depth (rings at the
// ladder's 4 m, a face under 0.12 px² and a ground polygon under 0.2 px² of 9.4 km left out), and of the buildings only
// the large: a footprint of 1,000 m² or more, or 30 m high or more, each with all its parts, and an outline with a part
// that tall (a church with its tower). Every building at L13 to 30 km drew 12 to 13 M triangles at the Westerkerk from
// 10 and 20 km, against the frame's 600k, and its tiles did not fit the heaps (pinch_audit_vk_test, 30 October).
//
// No blocks: neighbours that share a wall in the densest tile differ by a median ΔE76 of 12.6 in their walls' colours
// and 9.4 in their roofs' (core-p11, the canal ring's 16 places), and a house of 60 m² is 38 px² of the phone's at
// 4.7 km, so a block's one colour would change what the renders draw; one in 17 such pairs looks alike within a just
// noticeable difference.
#pragma once

#include "load/Window.hpp"
#include "tiles/Chunks.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <vector>

namespace atlas::load {

/// The coarse level: four L14 places a tile.
inline constexpr std::uint8_t coarse_level = 13;
/// The ladder index its rings are simplified at: 1.5 m.
inline constexpr std::uint8_t coarse_code = 10;
/// The depth from the eye it is first drawn at (the finest level's reach), metres, and the focal length of the phone's
/// frame there (916 dp tall: 4.7 km is zoom 14.8 at 52° N), dp.
inline constexpr double coarse_depth = 4'700.0;
inline constexpr double frame_focal = 1'420.0;

/// The far level: sixteen places a tile, each one of its cells, drawn from twice the coarse level's depth on.
inline constexpr std::uint8_t far_level = 12;
/// The ladder index its rings are simplified at: 4 m.
inline constexpr std::uint8_t far_code = 11;
inline constexpr double far_depth = 2.0 * coarse_depth;
/// The buildings it keeps: a footprint of this many m² or more, or this height in metres or more.
inline constexpr double far_least_area = 1'000.0;
inline constexpr double far_least_height = 30.0;

/// One tile of a level built: its pack (format 3, one chunk, its level alone), and what it holds.
struct BuiltLevel {
    tiles::TileKey key;
    std::vector<std::byte> pack;
    std::uint32_t features = 0;
    std::uint64_t raw = 0;       ///< the tile's decompressed bytes
    std::uint32_t buildings = 0; ///< buildings and parts
    std::uint32_t trees = 0;
};

/// The levels' tiles of one frame cell from its windows' packs (each a window's pack as build_window writes it, all of
/// one frame cell and one version, in any order; a window without a tile adds nothing): the L13 tiles that hold
/// anything, row by row from the north-west, then the L12 tiles.
[[nodiscard]] std::expected<std::vector<BuiltLevel>, WindowError>
build_levels(std::span<const std::span<const std::byte>> windows);

} // namespace atlas::load
