// A building's roof in stage T (plan-vector D10 "Roofs", K10): render/amsterdam.py's _draw_building by the roof's
// shape after regions.py's CLOSEST (and quadruple_saltbox as hipped), without a tradition (render tiles carry none):
//   - pyramidal: a face from each footprint edge up to the apex over the centroid;
//   - gabled and hipped: the ridge along the longest side (across it where roof:orientation says so), each eave edge
//     projected onto it, a hipped ridge pulled in by the roof's height at both ends: a slope per edge, a hip's end
//     triangle or a gable's wall triangle (in the wall's material) where an edge's projection is shorter than 0.3 m;
//     the gables first, then the slopes and hips, as the port draws them (`gab + slopes`);
//   - skillion: the footprint at its slope's heights, its normal and its centre as the port tests them;
//   - mansard: the brisis faces from the eaves to the inset top (Amsterdam.insetRing), and the top;
//   - anything else (flat, unknown, or a pitched shape without a height): the top, drawn when the eye is above it.
// Each face with the port's normal and test point, its sun sample (K8's order: every edge's, in the edges' order), and
// its painter slot after the walls'.
#pragma once

#include "geometry/Walls.hpp"

#include <cstdint>

namespace atlas::geometry {

/// The roof of `b`, flagged `variant` (prim_flag::dry for a houseboat's building).
void roofs(Builder& out, const Building& b, std::uint16_t variant);

} // namespace atlas::geometry
