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

} // namespace atlas::geometry
