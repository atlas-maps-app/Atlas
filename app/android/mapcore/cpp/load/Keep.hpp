// Keep codes of a ring (ENGINE.md step 3, stage P: the regions' cut; rendertiles' KeepCodes): how many of the ladder's
// tolerances (tiles/Schema.hpp's keep::tolerances) each vertex survives regions.py's `simplify` at, so a reader
// simplifies a ring at any level of the ladder from its codes alone (tiles/Simplify.hpp).
//
// `simplify` splits a closed ring at the vertex farthest from its first, then each half by Douglas-Peucker: a segment
// whose farthest inner vertex lies more than the tolerance from its chord splits there, else only its first vertex is
// kept. The split vertex does not depend on the tolerance, so the recursion is the same tree at every tolerance, cut
// where a chord's distance falls to the tolerance: a vertex survives a tolerance exactly when it is below the smallest
// chord distance on its way down the tree. Distances are the port's own arithmetic in the same order (math.dist for the
// farthest vertex, math.hypot for a chord), so a code is right to the last bit. The tree is walked with a stack of its
// own, so a long ring cannot run a phone thread's stack out.
#pragma once

#include "geometry/RoofShapes.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::load {

/// For each vertex of `pts`, the tolerance below which `simplify` keeps it: the ring's two anchors (the first vertex
/// and the one farthest from it) at every tolerance (+infinity), a vertex never split off 0.
[[nodiscard]] std::vector<double> keep_thresholds(std::span<const geometry::V2> pts);

/// The keep codes of `pts` (metres in the pack's frame): a ring of fewer than keep::min_points vertices, which
/// `simplify` never touches, keeps every vertex at every tolerance (keep::all).
[[nodiscard]] std::vector<std::uint8_t> keep_codes(std::span<const geometry::V2> pts);

} // namespace atlas::load
