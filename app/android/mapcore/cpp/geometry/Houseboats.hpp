// A houseboat in stage T (plan-vector D10 "Houseboats", K10): render/amsterdam.py's draw_houseboat, drawn instead of
// the building when the moment has weather (th['wx']): only its outline is mapped, so a generic boat at the region's
// waterline: the hull's walls from 0.15 m under the water to the deck 0.7 m above it, the deck, the cabin's walls set
// back to 84 % and 80 % of the footprint about its centroid, and the cabin's roof (88 %, 86 %). The walls are lit by
// their sun samples and drawn with their same-colour 0.3 px edge; the deck and the cabin's roof whenever the sun is up,
// from either side. Without weather the houseboat is drawn as a building (geometry/Walls.cpp). Each cabin wall over
// 1.6 m long has its row of panes (a windowed wall of WindowStyle::cabin), which the windows pass draws nearer than
// 520 m.
#pragma once

#include "geometry/Walls.hpp"

namespace atlas::geometry {

/// The boat of houseboat `bl` (its values and footprint) into `b`, flagged prim_flag::weather, after its sun samples
/// (K8's order: the hull's walls, then the cabin's) and its own painter slots (Builder::boat_slot: each cabin wall's
/// panes the slot after its wall's), its hull and deck in the region's colours (the style's).
void houseboat(Builder& b, const Building& bl, const BuildingRegion& region);

} // namespace atlas::geometry
