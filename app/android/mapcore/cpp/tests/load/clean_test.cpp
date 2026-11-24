// The buildings' cleaning (load/Clean.hpp, the Rust library behind load/CleanAbi.h): an outline drawn less its parts
// (the Engelse kerk, which hid whole behind its 13 m² tower), an outline its parts cover whole hidden, the lower of two
// overlapping buildings giving way, a roof over a kiosk left as it is, a storey mapped into the one below raised onto
// it, a courtyard cut out; every vertex on OSM's grid, the same bits each time, and the seams stage T leaves out.
#include "geometry/Seams.hpp"
#include "load/Amsterdam.hpp"
#include "load/Clean.hpp"
#include "tests/base/require.hpp"
#include "tiles/Grid.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

namespace load = atlas::load;
namespace tl = atlas::tiles;
using atlas::geometry::V2;

const tl::Frame frame = load::westerkerk_frame();

/// A grid point's place in the frame.
V2 at(std::int32_t lat, std::int32_t lon) { return {frame.x(lon), frame.z(lat)}; }

/// A box on the grid, its corners from (lat0, lon0) to (lat1, lon1) in grid units from the Westerkerk, turned so its
/// area is positive as the loaders turn a ring.
std::vector<V2> box(std::int32_t lat0, std::int32_t lon0, std::int32_t lat1, std::int32_t lon1) {
    constexpr std::int32_t lat = 523746000;
    constexpr std::int32_t lon = 48840000;
    std::vector<V2> r = {at(lat + lat0, lon + lon0), at(lat + lat0, lon + lon1), at(lat + lat1, lon + lon1),
                         at(lat + lat1, lon + lon0)};
    if (atlas::geometry::signed_area(r) < 0) std::ranges::reverse(r);
    return r;
}

load::Building building(std::int64_t id, std::vector<V2> poly, double min, double h) {
    load::Building b;
    b.id = id;
    b.poly = std::move(poly);
    b.min = min;
    b.h = h;
    b.bb = load::box_of(b.poly);
    return b;
}

double area(const std::vector<V2>& r) { return std::fabs(atlas::geometry::signed_area(r)); }

/// Whether every vertex lies on OSM's grid, as the cut requires.
bool on_grid(const std::vector<V2>& r) {
    return std::ranges::all_of(r, [](const V2& p) { return tl::grid_lat(frame, p.z) && tl::grid_lon(frame, p.x); });
}

} // namespace

TEST_CASE("clean: an outline is drawn less its parts, the rest of it kept") {
    // the Engelse kerk: a 476 m² church, its 13 m² tower part at one end
    load::Scene s;
    s.buildings.push_back(building(57858493, box(0, 0, 1800, 4000), 0.0, 19.0));
    s.parts.push_back(building(1425764020, box(700, 0, 1050, 500), 0.0, 30.0));
    const double whole = area(s.buildings[0].poly);
    load::clean_buildings(s, frame);
    ATLAS_REQUIRE(s.buildings.size() == 1 && s.parts.size() == 1);
    const load::Building& kerk = s.buildings[0];
    CHECK(!kerk.hidden);
    CHECK(std::fabs(area(kerk.poly) - (whole - area(s.parts[0].poly))) < 1e-6);
    CHECK(kerk.poly.size() == 8); // the tower's notch in its west wall
    CHECK(on_grid(kerk.poly));
    CHECK(load::seams(kerk.poly).empty());
    CHECK(s.counts.covered == 1);
    CHECK(s.counts.overlapping_pairs == 0);
    CHECK(s.counts.uncovered_outlines == 0);
}

TEST_CASE("clean: an outline its parts cover whole is hidden, a part covered whole goes") {
    load::Scene s;
    s.buildings.push_back(building(1, box(0, 0, 1000, 1000), 0.0, 10.0));
    s.parts.push_back(building(2, box(0, 0, 1000, 500), 0.0, 12.0));
    s.parts.push_back(building(3, box(0, 500, 1000, 1000), 0.0, 8.0));
    s.parts.push_back(building(4, box(100, 100, 200, 400), 0.0, 6.0)); // within 2, and lower: drawn by nothing
    const std::vector<V2> mapped = s.buildings[0].poly;
    load::clean_buildings(s, frame);
    ATLAS_REQUIRE(s.buildings.size() == 1);
    CHECK(s.buildings[0].hidden);
    CHECK(s.buildings[0].poly == mapped); // kept as mapped, for its places
    ATLAS_REQUIRE(s.parts.size() == 2);
    CHECK(s.parts[0].id == 2);
    CHECK(s.parts[1].id == 3);
    CHECK(s.counts.hidden == 1);
    CHECK(s.counts.vanished == 1);
    CHECK(s.counts.uncovered_outlines == 0);
}

TEST_CASE("clean: of two overlapping buildings the lower gives way; a roof over a kiosk stays") {
    load::Scene s;
    s.buildings.push_back(building(1, box(0, 0, 1000, 1000), 0.0, 10.0));    // a house
    s.buildings.push_back(building(2, box(0, 500, 1000, 1500), 0.0, 23.0));  // a taller one mapped over it
    s.buildings.push_back(building(3, box(2000, 0, 3000, 1000), 3.0, 4.0));  // a roof on posts
    s.buildings.push_back(building(4, box(2200, 200, 2400, 400), 0.0, 3.0)); // the kiosk under it
    const std::vector<V2> tall = s.buildings[1].poly;
    const std::vector<V2> roof = s.buildings[2].poly;
    const std::vector<V2> kiosk = s.buildings[3].poly;
    load::clean_buildings(s, frame);
    ATLAS_REQUIRE(s.buildings.size() == 4);
    CHECK(s.buildings[1].poly == tall);
    CHECK(std::fabs(area(s.buildings[0].poly) - (area(tall) / 2)) < 1e-6);
    CHECK(s.buildings[2].poly == roof);
    CHECK(s.buildings[3].poly == kiosk);
    CHECK(s.counts.clipped == 1);
    CHECK(s.counts.overlapping_pairs == 0);
}

TEST_CASE("clean: a storey mapped into the one below stands on it") {
    load::Scene s;
    s.parts.push_back(building(1, box(0, 0, 1000, 1000), 0.0, 15.0));
    s.parts.push_back(building(2, box(0, 0, 1000, 1000), 12.0, 18.0));
    load::clean_buildings(s, frame);
    ATLAS_REQUIRE(s.parts.size() == 2);
    CHECK(s.parts[1].min == 15.0);
    CHECK(s.counts.raised == 1);
    CHECK(s.counts.overlapping_pairs == 0);
}

TEST_CASE("clean: a courtyard is cut out and joined by seams, the same bits each time") {
    load::Scene s;
    load::Building b = building(1, box(0, 0, 3000, 3000), 0.0, 15.0);
    b.holes.push_back(box(1000, 1000, 2000, 2000));
    s.buildings.push_back(b);
    load::Scene again = s;
    load::clean_buildings(s, frame);
    load::clean_buildings(again, frame);
    ATLAS_REQUIRE(s.buildings.size() == 1);
    const std::vector<V2>& ring = s.buildings[0].poly;
    CHECK(ring == again.buildings[0].poly);
    CHECK(ring.size() == 10);
    CHECK(s.buildings[0].holes.empty());
    CHECK(std::fabs(area(ring) - (area(box(0, 0, 3000, 3000)) - area(box(1000, 1000, 2000, 2000)))) < 1e-6);
    CHECK(on_grid(ring));
    const std::vector<std::uint8_t> seams = load::seams(ring);
    CHECK(std::ranges::count(seams, std::uint8_t{1}) == 2);
    // both are the courtyard's, and the ring turns as geometry::split reads it: an outer ring's area above 0
    CHECK(std::ranges::count(load::seam_kinds(ring), atlas::geometry::seam_kind::court) == 2);
    CHECK(atlas::geometry::signed_area(ring) > 0.0);
    CHECK(s.counts.courtyards == 1);
    CHECK(s.counts.seamed == 1);
}

TEST_CASE("clean: a footprint with a corner off the grid is left as it is, its courtyards with it") {
    load::Scene s;
    load::Building b = building(1, box(0, 0, 3000, 3000), 0.0, 15.0);
    b.poly[0].x += 0.002; // a third of a grid step east of its grid point
    b.holes.push_back(box(1000, 1000, 2000, 2000));
    ATLAS_REQUIRE(!on_grid(b.poly));
    s.buildings.push_back(b);
    load::clean_buildings(s, frame);
    ATLAS_REQUIRE(s.buildings.size() == 1);
    CHECK(s.buildings[0].poly == b.poly);
    CHECK(s.buildings[0].holes == b.holes); // cleared only where the cleaning has cut them into the ring
    CHECK_FALSE(s.buildings[0].at.has_value());
    CHECK(load::seams(s.buildings[0].poly).empty());
}

TEST_CASE("clean: row houses sharing their walls are left as they are") {
    load::Scene s;
    for (std::int32_t i = 0; i < 6; ++i)
        s.buildings.push_back(building(i + 1, box(0, i * 600, 1000, (i + 1) * 600), 0.0, 12.0));
    std::vector<std::vector<V2>> mapped;
    mapped.reserve(s.buildings.size());
    for (const load::Building& b : s.buildings) mapped.push_back(b.poly);
    load::clean_buildings(s, frame);
    ATLAS_REQUIRE(s.buildings.size() == mapped.size());
    for (std::size_t i = 0; i < mapped.size(); ++i) CHECK(s.buildings[i].poly == mapped[i]);
    CHECK(s.counts == load::BuildingCounts{});
}

TEST_CASE("clean: a ring with seams piece by piece: a courtyard's seam stays in its piece, a piece's is in none") {
    namespace gm = atlas::geometry;
    namespace kind = atlas::geometry::seam_kind;
    using Pieces = std::vector<std::vector<std::uint32_t>>;
    using Kinds = std::vector<std::uint8_t>;
    const auto turned = [](std::vector<V2> ring, std::size_t by) {
        std::ranges::rotate(ring, ring.begin() + static_cast<std::ptrdiff_t>(by));
        return ring;
    };
    // a ring's pieces at level 0, by the kinds the cut marks (load::seam_kinds)
    const auto split = [](const std::vector<V2>& ring) { return gm::split(ring, load::seam_kinds(ring), 0.0); };
    const auto edges = [](const gm::Split& s) {
        Pieces out;
        for (const gm::Piece& p : s.pieces) out.push_back(p.edges);
        return out;
    };
    // no seam: the ring itself, a wall on every edge
    const std::vector<V2> plain = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
    gm::Split s = split(plain);
    CHECK(edges(s) == Pieces{{0, 1, 2, 3}});
    CHECK(s.off.empty());
    CHECK_FALSE(s.pieces[0].courtyard);
    // a courtyard, joined from the outer ring's first corner: one piece of every edge, its seams with it
    const std::vector<V2> yard = {{0, 0}, {4, 4}, {4, 6}, {6, 6}, {6, 4}, {4, 4}, {0, 0}, {10, 0}, {10, 10}, {0, 10}};
    CHECK(load::seams(yard) == Kinds{1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
    CHECK(load::seam_kinds(yard) == Kinds{kind::court, 0, 0, 0, 0, kind::court, 0, 0, 0, 0});
    s = split(yard);
    CHECK(edges(s) == Pieces{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}});
    CHECK(s.pieces[0].courtyard);
    CHECK(s.pieces[0].drawn);
    CHECK(s.off == Kinds{1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
    // the same ring from a point of its courtyard: the same piece
    s = split(turned(yard, 3));
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.pieces[0].edges.size() == 10);
    CHECK(s.pieces[0].courtyard);
    CHECK(s.pieces[0].drawn);
    // two boxes joined by a seam: each a piece of its own four edges, a simple ring, the seam in neither
    const std::vector<V2> two = {{0, 0},   {10, 0},  {20, 10}, {20, 0},  {30, 0},
                                 {30, 10}, {20, 10}, {10, 0},  {10, 10}, {0, 10}};
    CHECK(load::seam_kinds(two) == Kinds{0, kind::piece, 0, 0, 0, 0, kind::piece, 0, 0, 0});
    s = split(two);
    CHECK(edges(s) == Pieces{{0, 7, 8, 9}, {2, 3, 4, 5}});
    for (const gm::Piece& piece : s.pieces) CHECK_FALSE(piece.courtyard);
    CHECK(s.off == Kinds{0, 1, 0, 0, 0, 0, 1, 0, 0, 0});
    std::vector<V2> second;
    for (const std::uint32_t e : s.pieces[1].edges) second.push_back(two[e]);
    CHECK(second == std::vector<V2>{{20, 10}, {20, 0}, {30, 0}, {30, 10}});
    CHECK(load::seams(second).empty());
    s = split(turned(two, 3)); // from a point of the second box
    ATLAS_REQUIRE(s.pieces.size() == 2);
    CHECK(s.pieces[0].edges.size() == 4);
    CHECK(s.pieces[1].edges.size() == 4);
    // a block round a courtyard and a house standing in the courtyard, joined to the courtyard's corner: the house a
    // piece by itself, the block a piece with its courtyard
    const std::vector<V2> island = {{0, 0},   {10, 10}, {13, 13}, {17, 13}, {17, 17}, {13, 17}, {13, 13}, {10, 10},
                                    {10, 20}, {20, 20}, {20, 10}, {10, 10}, {0, 0},   {30, 0},  {30, 30}, {0, 30}};
    CHECK(load::seam_kinds(island) ==
          Kinds{kind::court, kind::piece, 0, 0, 0, 0, kind::piece, 0, 0, 0, 0, kind::court, 0, 0, 0, 0});
    s = split(island);
    CHECK(edges(s) == Pieces{{0, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {2, 3, 4, 5}});
    ATLAS_REQUIRE(s.pieces.size() == 2);
    CHECK(s.pieces[0].courtyard);
    CHECK_FALSE(s.pieces[1].courtyard);
    CHECK(s.paired);
    // seams that do not run there and back in turn (no ring the cleaning made): the ring as it is, one piece, flat
    const std::vector<V2> crossed = {{0, 0}, {1, 0}, {2, 1},  {3, 0}, {4, 0}, {2, -1},
                                     {1, 0}, {0, 0}, {2, -3}, {4, 0}, {3, 0}, {2, 3}};
    CHECK(std::ranges::count(load::seams(crossed), std::uint8_t{1}) == 4);
    s = split(crossed);
    CHECK_FALSE(s.paired);
    ATLAS_REQUIRE(s.pieces.size() == 1);
    CHECK(s.pieces[0].edges.size() == crossed.size());
    CHECK(s.pieces[0].courtyard);
    CHECK(std::ranges::count(s.off, std::uint8_t{1}) == 4);
}

TEST_CASE("clean: the seams of a ring, and a roof over one drawn flat") {
    CHECK(load::seams(box(0, 0, 10, 10)).empty());
    const std::vector<V2> ring = {{0, 0}, {10, 0}, {10, 10}, {4, 4}, {6, 4}, {6, 6}, {4, 6}, {4, 4}, {10, 10}, {0, 10}};
    const std::vector<std::uint8_t> seams = load::seams(ring);
    ATLAS_REQUIRE(seams.size() == ring.size());
    CHECK(seams == std::vector<std::uint8_t>{0, 0, 1, 0, 0, 0, 0, 1, 0, 0});
    CHECK(atlas::geometry::drawn_pitched("gabled", 4.0, false));
    CHECK(atlas::geometry::drawn_pitched("quadruple_saltbox", 4.0, false));
    CHECK(!atlas::geometry::drawn_pitched("gabled", 4.0, true)); // over a piece with a courtyard
    CHECK(!atlas::geometry::drawn_pitched("gabled", 0.0, false));
    CHECK(!atlas::geometry::drawn_pitched("double_saltbox", 4.0, false));
}
