// A park's and a zoo's planted woods (ENGINE.md step 4, "Trees and things"; render/woods.py's plant, R/Woods.kt). OSM
// maps a wood as its outline (landuse=forest, natural=wood), not the trees in it, so they are planted generically, as
// regions.py plants them for a region with a park or a zoo (R's `park`, `zoo`): one about every 8.5 m on a lattice of
// rows jittered by up to 2.6 m across and 2.2 m along, 13 to 21 m tall with crowns of 6 to 9 m, kept off everything
// mapped inside the outline (on a 2 m grid: paths by their width, buildings, water, rails and coaster tracks, and the
// smaller land in the wood: lawns, playgrounds, squares), their sizes and ids from one random stream seeded 7.
//
// The renders plant a region's woods about the view they draw: its target, out to its radius, each wood's lattice from
// its box's corner or the view's square's edge, whichever is further in. The engine plants them once for the region, as
// the port loads a region whole (LiveEngine's wholeCamera): about the centre of the region's box, out to its
// half-diagonal, so every wood in it is planted whole on its own lattice and every view and every tile has the same
// trees. Every value is the port's double, by its own arithmetic.
#pragma once

#include "load/Scene.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <vector>

namespace atlas::load {

/// Where a region's woods are planted about: the centre of its box (frame metres) and its half-diagonal, as the packer
/// computes them (rendertiles' PlantedWoods: LiveEngine's wholeCamera, Kotlin's hypot).
struct WoodsAnchor {
    double cx = 0.0;
    double cz = 0.0;
    double reach = 0.0;
};

/// A zoo or a theme park whose woods a window plants (RegionConfig::zoo_woods) holds at least this many square metres
/// and is no petting zoo (zoo=petting_zoo): Artis's 14 ha, the Efteling's 70, never a city farm's corner.
inline constexpr double zoo_least_area = 50'000.0;

/// How far beyond a zoo's or a theme park's outline a window plants its woods (and all of them inside it): a window's
/// margin (load/Window.hpp's window_margin), so the window of every tile such a tree stands in holds the zoo in its
/// data and the planting has no seam at a window's edge. The renders plant a zoo's region out to its view's radius
/// (Artis's 640 m about its gate, 320 to 430 m beyond its outline): this plants about half the area of Artis's woods
/// they plant.
inline constexpr double zoo_woods_reach = 200.0;

/// woods.plant(S, cx, cz, rad): the trees planted in the scene's woods within `rad` of (cx, cz), in its order.
[[nodiscard]] std::vector<WoodTree> plant_woods(const Scene& s, double cx, double cz, double rad);

} // namespace atlas::load
