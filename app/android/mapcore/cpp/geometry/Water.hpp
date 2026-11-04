// The water's pieces (plan-vector D10 "Water: surface", K9): S['water'] and S['water_holes'] clipped at the tile's
// edges (rendertiles' Layer.WATER: every piece turns as its ring turns), turned as render's water_layer turns them
// (amsterdam.py:457 `wound`: every water area one way, every island the other, so with the nonzero rule overlaps stay
// water and islands stay land), and cut into fans. A fan from a ring's first point gives every point of the plane the
// ring's own winding number once the fan's triangles are counted, +1 for each triangle turning one way and -1 for the
// others, whatever the ring's shape (concave, self-touching where a tile's edge joins it, crossing itself): the frame
// counts them in the stencil (passes/Water.cpp), so the clips are the renders' nonzero unions exactly, up to the
// samples.
#pragma once

#include "geometry/Ribbons.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::geometry {

/// Turns a water piece as `wound` turns its ring on the screen (seen from above, the screen keeps the ground's turn):
/// a water area so its signed area (x east, z south) is positive, an island so it is negative. Reverses `ring` in place
/// when it turns the other way; a ring of no area stays as it is.
void orient_water(std::span<P2> ring, bool hole) noexcept;

/// The fan of a ring of `count` points whose first is vertex `first`: (first, first + i, first + i + 1) for i in 1 to
/// count - 2. Appends to `out`.
void fan(std::uint32_t first, std::uint32_t count, std::vector<std::uint32_t>& out);

/// The widest water thin_edges measures across, metres: no thin edge's water is wider.
inline constexpr double thin_most = 40.0;
/// How far the water's width halfway along a thin edge may lie from the mean of its widths at the edge's ends, metres,
/// or that share of the mean where more: an edge whose far bank bends more is halved.
inline constexpr double thin_slack = 0.25;
inline constexpr double thin_slack_share = 0.1;
/// The shortest stretch thin_edges halves an edge into, metres, and how many times it halves one at most.
inline constexpr double thin_least = 2.0;
inline constexpr int thin_halvings = 3;

/// One water piece for thin_edges: [first, first + count) of the tile's points, turned as orient_water turns it, and
/// its fill's index in the tile.
struct ThinRing {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    std::uint32_t fill = 0;
};

/// A stretch of a water piece's edge and the water's width across it, which the app's map draws at least 1.5 device
/// px wide at the share of them it covers where it is narrower on the screen (passes/Water.cpp's thin water): its
/// ends, the water's width at each along the edge's normal into the water to the other bank, its piece's fill and the
/// other bank's piece's (the frame draws the stretch only where it draws both).
struct ThinEdge {
    P2 a;
    P2 b;
    double ta = 0;
    double tb = 0;
    std::uint32_t fill = 0;
    std::uint32_t other = 0;
};

/// The thin edges of a tile's water pieces, the tile's box [lo, hi] (the pieces are cut along its sides, the water
/// going on beyond them). Each edge of a piece but those along a side: the water's width across it from a tenth, half
/// and nine tenths of the way along it, along its normal into the water (the left of its direction, x east and z
/// south, as orient_water turns the pieces) to the nearest other edge of the tile's pieces, its ends' widths those from
/// a tenth and nine tenths. Kept where all three meet an edge of the same piece within thin_most that is not along a
/// side and the one halfway lies within thin_slack (or thin_slack_share) of the mean of the two; else halved, up to
/// thin_halvings times while the halves are at least thin_least long, each half tried again. In the pieces' order, an
/// edge's stretches from its first point on.
[[nodiscard]] std::vector<ThinEdge> thin_edges(std::span<const P2> points, std::span<const ThinRing> rings, P2 lo,
                                               P2 hi);

} // namespace atlas::geometry
