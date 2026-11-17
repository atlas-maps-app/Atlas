// The buildings' cleaning (ENGINE.md step 3, stage P): a scene's buildings and parts made into what can stand there,
// by the Rust library behind load/CleanAbi.h, on OSM's grid in whole numbers, so every platform cuts the same tiles.
//   1. Courtyards: a footprint's inner rings (Building::holes) are cut out of it.
//   2. Coverage: an outline less the parts overlapping it, the rest of it kept. An outline its parts cover whole is
//      hidden: kept for its places, drawn by nothing of its own.
//   3. Stacking: a footprint standing on others it overlaps in height, over most of its area, starts at their top.
//   4. Clipping: of two drawn footprints that still overlap (over 1 m² in plan and 0.1 m in height), the one whose
//      heights lie within the other's gives way where they overlap; where neither holds the other, the one that loses
//      less volume. A part cut to nothing goes; an outline cut to nothing is hidden. What a cut leaves under 3 m², or
//      with no body wider than 0.5 m, goes. A cut is opened by a hair (3 cm): a hair on a piece is cut from it, a
//      neck of that width between two bodies cut through.
//   5. What is left over: an outline takes back the ground of its own that nothing draws once every cut is made (a
//      strip of it beside the crumb of a part), at its own height.
// What is left of each footprint is its one ring: its pieces and courtyards joined by seams, an edge run there and
// back, that draw nothing (geometry/Seams.hpp finds them again). A footprint nothing touches keeps its ring as it was.
// The validity counts come with it (Scene::counts): the pairs still overlapping, the outlines left uncovered.
#pragma once

#include "load/Scene.hpp"
#include "tiles/Sections.hpp"

namespace atlas::load {

/// Cleans the buildings and parts of `s`, loaded in `frame`, and counts what is left that cannot be there
/// (Scene::counts). A vertex off OSM's grid (which the cut refuses) leaves its footprint as it is.
void clean_buildings(Scene& s, const tiles::Frame& frame);

} // namespace atlas::load
