// A houseboat in stage T (plan-vector D10 "Houseboats", K10): render/amsterdam.py's draw_houseboat, drawn instead of
// the building when the moment has weather (th['wx']): only its outline is mapped, so a generic boat at the region's
// waterline: the hull's walls from 0.15 m under the water to the deck 0.7 m above it, the deck, the cabin's walls set
// back to 84 % and 80 % of the footprint about its centroid, and the cabin's roof (88 %, 86 %). The walls are lit by
// their sun samples and drawn with their same-colour 0.3 px edge; the deck and the cabin's roof whenever the sun is up,
// from either side. Without weather the houseboat is drawn as a building (geometry/Walls.cpp). The cabin's windows are
// the facades' (K14).
#pragma once

#include "geometry/Walls.hpp"

namespace atlas::geometry {

/// The boat of houseboat `b` (its values and footprint), flagged prim_flag::weather, after its sun samples (K8's
/// order: the hull's walls, then the cabin's) and its own painter slots (Builder::boat_slot).
void houseboat(Builder& out, const Building& b, const BuildingRegion& region);

/// The hull's colour and the deck's (draw_houseboat's #23272B and #6E5E4C).
inline constexpr std::uint32_t hull_rgb = 0x23272BU;
inline constexpr std::uint32_t deck_rgb = 0x6E5E4CU;

} // namespace atlas::geometry
