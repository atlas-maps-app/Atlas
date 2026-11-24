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
// back, that draw nothing. The library finds a ring's seams again (seams, below), for stage P alone and on level 0's
// ring: the cut marks each in the tile with its kind (seam_kinds; load/Cut.cpp's keep_seams), and stage T reads the
// marks (geometry/Seams.hpp). A footprint nothing touches keeps its ring as it was.
// The validity counts come with it (Scene::counts): the pairs still overlapping, the outlines left uncovered.
#pragma once

#include "load/Scene.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::load {

/// The layers of a cut the cleaning touches: the buildings and parts and their variants, and what stage P derives from
/// their rings, the things on their walls (a door's and a shopfront's wall) and the quays they line. In these the core
/// and the port differ on purpose, the port's buildings being as mapped; every other layer of the core's cut is the
/// port's line for line, until cut 2 makes the test packs with the core. Listed here once: the core's tests
/// (tests/load/cut_test.cpp) and the port's (LoadHost.TOUCHED, through jni/TestLoad.cpp) hold the others by it.
inline constexpr std::array<std::string_view, 5> cleaned_layers = {"building", "part", "variant", "thing", "quay"};

/// Cleans the buildings and parts of `s`, loaded in `frame`, and counts what is left that cannot be there
/// (Scene::counts). A vertex off OSM's grid (which the cut refuses) leaves its footprint as it is.
void clean_buildings(Scene& s, const tiles::Frame& frame);

/// For each edge of the closed ring (from vertex i to i + 1), 1 where it is a seam (its reverse is an edge of the ring
/// too), else 0; empty where the ring has none.
[[nodiscard]] std::vector<std::uint8_t> seams(std::span<const V2> ring);

/// The same by kind (geometry/Seams.hpp's seam_kind), as the cut marks them: a seam joins a courtyard to its piece
/// where its middle lies in the footprint, and two pieces where it lies outside (bridge.rs's clear: it crosses no edge
/// and passes no vertex, so its middle is well in or out), by the ring's edges that are no seams, even-odd. A seam's
/// way there and its way back have one middle, so one kind. Empty where the ring has none.
[[nodiscard]] std::vector<std::uint8_t> seam_kinds(std::span<const V2> ring);

} // namespace atlas::load
