// The building the app asks about (ENGINE.md step 6, the user's own edits drawn at once: FillIn's question), drawn as
// the FillIn board draws it while the question is open: plain (its near cells alone: no windows, no facade detail), its
// walls in one flat colour (the board's grey before an answer, the answer's colour as the board draws it after), and
// hatched before the answer (the board's hatch: #8C857A lines 2.2 dp wide every 7 dp at 45 degrees). The ranks mark its
// items (frame/Ranks.cpp: RankOptions::asked), the buildings pass gives its walls the colour (passes/Walls.cpp) and the
// building shader hatches it (shaders/building/draw.slang).
//
// While the app asks about its roof's shape too (`roof`), the board's house stands in its place: the plain box until
// the answer, then the box under the roof picked, which no tile holds. The core builds that one building again
// (scene/House.hpp), the house's pass draws it (passes/House.hpp) and the ranks leave the tile's own out meanwhile.
#pragma once

#include <cstdint>

namespace atlas::frame {

/// The building asked about.
struct AskedBuilding {
    std::int64_t id = 0;    ///< its OSM id
    bool relation = false;  ///< a multipolygon's (else a way's)
    std::uint32_t wall = 0; ///< its walls' flat colour, 0xRRGGBB
    bool hatched = false;   ///< drawn hatched: the question still open
    /// The roof the app asks about it in (scene::house_roof's codes): 0 its own, as its tile draws it; 1 flat, the
    /// board's plain box (the roof's question open); 2 gabled; 3 hipped.
    std::uint8_t roof = 0;
    /// Its walls' colour is not asked about (it is mapped; `wall` and `hatched` say nothing): its house stands in the
    /// colour its tile gives its walls, and where no house stands in its place the building is drawn as its tile has
    /// it, not as asked.
    bool own_wall = false;

    friend constexpr bool operator==(const AskedBuilding&, const AskedBuilding&) = default;
};

} // namespace atlas::frame
