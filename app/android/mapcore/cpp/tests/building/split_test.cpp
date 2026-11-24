// A ring with seams split at a level (geometry/Seams.hpp's split), on rings made by hand in metres with their seams'
// kinds as a tile's marks give them: which pieces a level still draws, which edges nothing stands on, and what a tile
// whose marks do not pair is read as. The library that finds a footprint's seams is not asked: stage T never asks it.
#include "geometry/Seams.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

namespace {

namespace gm = atlas::geometry;
using gm::V2;
using Kinds = std::vector<std::uint8_t>;
using Edges = std::vector<std::uint32_t>;
constexpr std::uint8_t P = gm::seam_kind::piece;
constexpr std::uint8_t C = gm::seam_kind::court;

// two boxes of 100 m² joined by a seam between pieces (edges 1 and 6)
const std::vector<V2> two = {{0, 0},   {10, 0},  {20, 10}, {20, 0},  {30, 0},
                             {30, 10}, {20, 10}, {10, 0},  {10, 10}, {0, 10}};
const Kinds two_kinds = {0, P, 0, 0, 0, 0, P, 0, 0, 0};
// a box of 100 m² round a courtyard of 4 m², joined from the box's first corner (edges 0 and 5)
const std::vector<V2> yard = {{0, 0}, {4, 4}, {4, 6}, {6, 6}, {6, 4}, {4, 4}, {0, 0}, {10, 0}, {10, 10}, {0, 10}};
const Kinds yard_kinds = {C, 0, 0, 0, 0, C, 0, 0, 0, 0};

} // namespace

TEST_CASE("split: a piece under the level's least face draws nothing there, and one of its size is drawn") {
    gm::Split s = gm::split(two, two_kinds, 0.0);
    ATLAS_REQUIRE(s.pieces.size() == 2);
    CHECK(s.paired);
    CHECK(s.pieces[0].edges == Edges{0, 7, 8, 9});
    CHECK(s.pieces[1].edges == Edges{2, 3, 4, 5});
    CHECK(s.pieces[0].drawn);
    CHECK(s.pieces[1].drawn);
    CHECK(s.off == Kinds{0, 1, 0, 0, 0, 0, 1, 0, 0, 0});
    // each is 100 m²: drawn at a least face of 100, not at one above
    s = gm::split(two, two_kinds, 100.0);
    ATLAS_REQUIRE(s.pieces.size() == 2);
    CHECK(s.pieces[0].drawn);
    CHECK(s.pieces[1].drawn);
    s = gm::split(two, two_kinds, 100.5);
    ATLAS_REQUIRE(s.pieces.size() == 2);
    CHECK_FALSE(s.pieces[0].drawn);
    CHECK_FALSE(s.pieces[1].drawn);
    CHECK(s.pieces[0].edges == Edges{0, 7, 8, 9}); // still listed, for a count
    CHECK(s.off == Kinds{1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
    // a courtyard is taken off its piece's area: 96 m²
    s = gm::split(yard, yard_kinds, 96.5);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK_FALSE(s.pieces[0].drawn);
    s = gm::split(yard, yard_kinds, 95.5);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.pieces[0].drawn);
    CHECK(s.pieces[0].courtyard);
}

TEST_CASE("split: a piece a level has turned inside out draws nothing, its neighbour as before") {
    // the second box the other way round, as a simplified ring can leave a small piece
    const std::vector<V2> turned = {{0, 0},  {10, 0},  {20, 10}, {30, 10}, {30, 0},
                                    {20, 0}, {20, 10}, {10, 0},  {10, 10}, {0, 10}};
    const gm::Split s = gm::split(turned, two_kinds, 0.0);
    ATLAS_REQUIRE(s.pieces.size() == 2);
    CHECK(s.paired);
    CHECK(s.pieces[0].edges == Edges{0, 7, 8, 9});
    CHECK(s.pieces[0].drawn);
    CHECK(s.pieces[1].edges == Edges{2, 3, 4, 5});
    CHECK_FALSE(s.pieces[1].drawn);
    CHECK(s.off == Kinds{0, 1, 1, 1, 1, 1, 1, 0, 0, 0});
    // a piece of no area at all: its two corners left on one line
    const std::vector<V2> flat = {{0, 0}, {10, 0}, {20, 10}, {30, 10}, {20, 10}, {10, 0}, {10, 10}, {0, 10}};
    const gm::Split f = gm::split(flat, Kinds{0, P, 0, 0, P, 0, 0, 0}, 0.0);
    ATLAS_REQUIRE(f.pieces.size() == 2);
    CHECK(f.pieces[0].drawn);
    CHECK_FALSE(f.pieces[1].drawn);
    CHECK(f.off == Kinds{0, 1, 1, 1, 1, 0, 0, 0});
}

TEST_CASE("split: a courtyard that no longer encloses ground is left out of its piece, which stays flat") {
    // the courtyard the outer ring's way round
    const std::vector<V2> filled = {{0, 0}, {4, 4}, {6, 4}, {6, 6}, {4, 6}, {4, 4}, {0, 0}, {10, 0}, {10, 10}, {0, 10}};
    gm::Split s = gm::split(filled, yard_kinds, 0.0);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.paired);
    CHECK(s.pieces[0].edges == Edges{6, 7, 8, 9}); // neither the courtyard's edges nor its seams
    CHECK(s.pieces[0].courtyard);                  // flat all the same
    CHECK(s.pieces[0].drawn);
    CHECK(s.off == Kinds{1, 1, 1, 1, 1, 1, 0, 0, 0, 0});
    // the same from a corner of the courtyard: the outer ring is the walk of the largest area, wherever the ring starts
    const std::vector<V2> from_yard = {{6, 6},   {4, 6},  {4, 4}, {0, 0}, {10, 0},
                                       {10, 10}, {0, 10}, {0, 0}, {4, 4}, {6, 4}};
    s = gm::split(from_yard, Kinds{0, 0, C, 0, 0, 0, 0, C, 0, 0}, 0.0);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.pieces[0].edges == Edges{3, 4, 5, 6});
    CHECK(s.pieces[0].courtyard);
    CHECK(s.off == Kinds{1, 1, 1, 0, 0, 0, 0, 1, 1, 1});
    // a courtyard left larger than its piece's outer ring: the piece has no area, and draws nothing
    const std::vector<V2> hollow = {{0, 0},   {-5, -5}, {-5, 20}, {20, 20}, {20, -5},
                                    {-5, -5}, {0, 0},   {10, 0},  {10, 10}, {0, 10}};
    s = gm::split(hollow, yard_kinds, 0.0);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK_FALSE(s.pieces[0].drawn);
    CHECK(s.off == Kinds{1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
}

TEST_CASE("split: more than two marked edges on the same two points pair inside out, what lies between has no area") {
    // the seam to the courtyard run there, back, and there again before the courtyard, then back
    const std::vector<V2> twice = {{0, 0}, {4, 4}, {0, 0}, {4, 4},  {4, 6},   {6, 6},
                                   {6, 4}, {4, 4}, {0, 0}, {10, 0}, {10, 10}, {0, 10}};
    const gm::Split s = gm::split(twice, Kinds{C, C, C, 0, 0, 0, 0, C, 0, 0, 0, 0}, 0.0);
    CHECK(s.paired);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.pieces[0].edges == Edges{2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
    CHECK(s.pieces[0].courtyard);
    CHECK(s.pieces[0].drawn);
    CHECK(s.off == Kinds{1, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
}

TEST_CASE("split: marks that do not pair give the ring as one flat piece, nothing else") {
    const auto flat_whole = [](const gm::Split& s, std::size_t n) {
        ATLAS_REQUIRE(s.pieces.size() == 1);
        CHECK_FALSE(s.paired);
        CHECK(s.pieces[0].edges.size() == n);
        CHECK(s.pieces[0].courtyard);
        CHECK(s.pieces[0].drawn);
    };
    // a seam marked there and not back
    gm::Split s = gm::split(yard, Kinds{C, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 0.0);
    flat_whole(s, yard.size());
    CHECK(s.off == Kinds{1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    // there as a courtyard's and back as a piece's
    s = gm::split(yard, Kinds{C, 0, 0, 0, 0, P, 0, 0, 0, 0}, 0.0);
    flat_whole(s, yard.size());
    CHECK(s.off == Kinds{1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
    // a mark on an edge that is no seam's
    s = gm::split(two, Kinds{0, P, 0, P, 0, 0, P, 0, 0, 0}, 0.0);
    flat_whole(s, two.size());
    // a ring without a mark is itself, a wall on every edge; so is one whose kinds are not one an edge
    s = gm::split(two, Kinds{}, 0.0);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.paired);
    CHECK_FALSE(s.pieces[0].courtyard);
    CHECK(s.off.empty());
    s = gm::split(two, Kinds{0, P, 0}, 5'000.0);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.pieces[0].drawn);
    CHECK(s.off.empty());
}
