// A park's and a zoo's planted woods (ENGINE.md step 4, "Trees and things"; render/woods.py's plant, R/Woods.kt). OSM
// maps a wood as its outline (landuse=forest, natural=wood), not the trees in it, so they are planted generically, as
// regions.py plants them for a region with a park or a zoo (R's `park`, `zoo`): one about every 8.5 m on a lattice of
// rows jittered by up to 2.6 m across and 2.2 m along, 13 to 21 m tall with crowns of 6 to 9 m, kept off everything
// mapped inside the outline (on a 2 m grid: paths by their width, buildings, water, rails and coaster tracks, and the
// smaller land in the wood: lawns, playgrounds, squares), their sizes and ids from one random stream seeded 7.
//
// The renders plant a region's woods about the view they draw: its target, out to its radius, each wood's lattice from
// its box's corner or the view's square's edge, whichever is further in. The engine plants them once for the region,
// anchored to its frame (woods_radius): about the frame's origin, out to the region's box's farthest corner, so every
// wood is planted whole on its own lattice and every view of the region and every tile has the same trees. Every value
// is the port's double, by its own arithmetic.
#pragma once

#include "load/Scene.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <vector>

namespace atlas::load {

/// The radius the engine plants a region's woods out to about its frame's origin: the farthest of its box's corners
/// (south, west, north, east) and 40 m, rounded up to a whole number of 2 m, the planting grid's cells.
[[nodiscard]] double woods_radius(const tiles::Frame& frame, const std::array<double, 4>& bbox) noexcept;

/// woods.plant(S, cx, cz, rad): the trees planted in the scene's woods within `rad` of (cx, cz), in its order.
[[nodiscard]] std::vector<WoodTree> plant_woods(const Scene& s, double cx, double cz, double rad);

} // namespace atlas::load
