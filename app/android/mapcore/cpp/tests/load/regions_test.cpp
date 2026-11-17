// A region's load (load/Regions.hpp): a multipolygon building's outer ways joined into its ring, and loaded whatever
// corner its ring starts at (a neighbour's first point too), a water relation and
// its island, land cover in LAND's order, a big road in a tunnel left out, the closest roof shape, Douglas-Peucker's
// simplify, a theme park's coaster tracks (load/Coasters.hpp) and Python's float() they read a layer with, and where
// ATLAS_REGIONS_DIR names the renderer's data/regions, the canal ring's raw.json loaded whole as the packer loads it,
// with nothing that cannot be there (load/Clean.hpp; ATLAS_CLEAN_DUMP writes its buildings for the fault detectors).
// No tag's number, however large, ends a load or refuses one, in any look or cut.
#include "base/pymath/PyMath.hpp"
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/Generic.hpp"
#include "load/Real.hpp"
#include "load/Regions.hpp"
#include "load/Tradition.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Json.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;

std::string read_text(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

std::expected<osm::Document, osm::ReadError> read(std::string_view s) {
    return osm::read_overpass(std::as_bytes(std::span(s.data(), s.size())));
}

load::RegionConfig amsterdam_region() {
    return {.name = "test",
            .frame = load::westerkerk_frame(),
            .bbox = {52.36, 4.87, 52.39, 4.92},
            .tradition = nullptr,
            .palette = load::amsterdam_palette()};
}

// a relation building of two outer ways, a water relation with an island, two land covers, a big road in a tunnel and
// one on the ground, a dome
constexpr std::string_view document = R"({"elements": [
{"type": "relation", "id": 1, "tags": {"building": "yes", "roof:shape": "dome", "height": "20"}, "members": [
  {"type": "way", "ref": 11, "role": "outer", "geometry": [{"lat": 52.3700, "lon": 4.8800}, {"lat": 52.3700, "lon": 4.8810},
   {"lat": 52.3706, "lon": 4.8810}]},
  {"type": "way", "ref": 12, "role": "outer", "geometry": [{"lat": 52.3700, "lon": 4.8800}, {"lat": 52.3706, "lon": 4.8800},
   {"lat": 52.3706, "lon": 4.8810}]}]},
{"type": "relation", "id": 2, "tags": {"natural": "water"}, "members": [
  {"type": "way", "ref": 21, "role": "outer", "geometry": [{"lat": 52.3800, "lon": 4.8900}, {"lat": 52.3800, "lon": 4.8950},
   {"lat": 52.3830, "lon": 4.8950}, {"lat": 52.3830, "lon": 4.8900}, {"lat": 52.3800, "lon": 4.8900}]},
  {"type": "way", "ref": 22, "role": "inner", "geometry": [{"lat": 52.3810, "lon": 4.8910}, {"lat": 52.3810, "lon": 4.8920},
   {"lat": 52.3820, "lon": 4.8920}, {"lat": 52.3820, "lon": 4.8910}, {"lat": 52.3810, "lon": 4.8910}]}]},
{"type": "way", "id": 3, "tags": {"landuse": "residential"}, "geometry": [{"lat": 52.3600, "lon": 4.8700},
 {"lat": 52.3600, "lon": 4.8800}, {"lat": 52.3650, "lon": 4.8800}, {"lat": 52.3650, "lon": 4.8700}, {"lat": 52.3600, "lon": 4.8700}]},
{"type": "way", "id": 4, "tags": {"leisure": "park"}, "geometry": [{"lat": 52.3700, "lon": 4.9000},
 {"lat": 52.3700, "lon": 4.9010}, {"lat": 52.3705, "lon": 4.9010}, {"lat": 52.3705, "lon": 4.9000}, {"lat": 52.3700, "lon": 4.9000}]},
{"type": "way", "id": 5, "tags": {"highway": "motorway", "tunnel": "yes"}, "geometry": [{"lat": 52.37, "lon": 4.87}, {"lat": 52.38, "lon": 4.88}]},
{"type": "way", "id": 6, "tags": {"highway": "primary", "layer": "1"}, "geometry": [{"lat": 52.37, "lon": 4.87}, {"lat": 52.38, "lon": 4.88}]}
]})";

// A terrace of four houses in a row, west to east, wall to wall, and a part over the east half of the first. Each
// relation's ring starts at a corner where a neighbour's ring starts too: 2 (two outer ways) and the part 5 (two
// outer ways) at way 1's first point, 4 (two outer ways) at the first point of 3 (one closed outer way). Every ring is
// mapped clockwise, so none is turned and each keeps its first point.
constexpr std::string_view terrace = R"({"elements": [
{"type": "way", "id": 1, "tags": {"building": "house", "building:levels": "3"}, "geometry": [
  {"lat": 52.3700, "lon": 4.88015}, {"lat": 52.3700, "lon": 4.8800}, {"lat": 52.3701, "lon": 4.8800},
  {"lat": 52.3701, "lon": 4.88015}, {"lat": 52.3700, "lon": 4.88015}]},
{"type": "relation", "id": 2, "tags": {"type": "multipolygon", "building": "house"}, "members": [
  {"type": "way", "ref": 20, "role": "outer", "geometry": [{"lat": 52.3700, "lon": 4.88015},
   {"lat": 52.3701, "lon": 4.88015}, {"lat": 52.3701, "lon": 4.8803}]},
  {"type": "way", "ref": 21, "role": "outer", "geometry": [{"lat": 52.3701, "lon": 4.8803},
   {"lat": 52.3700, "lon": 4.8803}, {"lat": 52.3700, "lon": 4.88015}]}]},
{"type": "relation", "id": 3, "tags": {"type": "multipolygon", "building": "house"}, "members": [
  {"type": "way", "ref": 22, "role": "outer", "geometry": [{"lat": 52.3700, "lon": 4.88045},
   {"lat": 52.3700, "lon": 4.8803}, {"lat": 52.3701, "lon": 4.8803}, {"lat": 52.3701, "lon": 4.88045},
   {"lat": 52.3700, "lon": 4.88045}]}]},
{"type": "relation", "id": 4, "tags": {"type": "multipolygon", "building": "house"}, "members": [
  {"type": "way", "ref": 23, "role": "outer", "geometry": [{"lat": 52.3700, "lon": 4.88045},
   {"lat": 52.3701, "lon": 4.88045}, {"lat": 52.3701, "lon": 4.8806}]},
  {"type": "way", "ref": 24, "role": "outer", "geometry": [{"lat": 52.3701, "lon": 4.8806},
   {"lat": 52.3700, "lon": 4.8806}, {"lat": 52.3700, "lon": 4.88045}]}]},
{"type": "relation", "id": 5, "tags": {"type": "multipolygon", "building:part": "yes", "height": "12"}, "members": [
  {"type": "way", "ref": 25, "role": "outer", "geometry": [{"lat": 52.3700, "lon": 4.88015},
   {"lat": 52.3700, "lon": 4.880075}, {"lat": 52.3701, "lon": 4.880075}]},
  {"type": "way", "ref": 26, "role": "outer", "geometry": [{"lat": 52.3701, "lon": 4.880075},
   {"lat": 52.3701, "lon": 4.88015}, {"lat": 52.3700, "lon": 4.88015}]}]}
]})";

} // namespace

TEST_CASE("load: a region's relations, land cover, big roads and closest roof shapes") {
    const auto doc = read(document);
    ATLAS_REQUIRE(doc.has_value());
    const auto loaded = load::load_region(*doc, amsterdam_region());
    ATLAS_REQUIRE(loaded.has_value());
    const load::Scene& s = *loaded;
    ATLAS_REQUIRE(s.buildings.size() == 1);
    const load::Building& b = s.buildings[0];
    CHECK(b.relation);
    CHECK(b.poly.size() ==
          4); // two ways joined into a closed ring, its closing point dropped, turned to a positive area
    CHECK(load::xz(load::westerkerk_frame(), 52.3700, 4.8800) == b.poly.back());
    CHECK(b.roof == "pyramidal"); // a dome drawn as the closest roof the renders draw
    CHECK(b.h == 20.0);
    // the living map's rules load the relation's outer way, cleaned; regions.py's load joins it again, keeping its
    // closing point, and its first point and length differ, so the renders hold the ring twice
    ATLAS_REQUIRE(s.water.size() == 2);
    CHECK(s.water[0].size() == 4);
    CHECK(s.water[1].size() == 5);
    CHECK(s.water_holes.size() == 1);
    CHECK(s.natural_shore == std::vector<bool>{true, true});
    ATLAS_REQUIRE(s.land.size() == 2);
    CHECK(s.land[0].kind == "residential"); // draw order 0 before the park's 2
    CHECK(s.land[1].kind == "park");
    ATLAS_REQUIRE(s.big.size() == 1); // the motorway in a tunnel is not drawn
    CHECK(s.big[0].id == 6);
    CHECK(s.big[0].layer == 1.0);
}

// The port told a relation's ring from one already loaded by its first point alone, to the decimetre (regions.py's
// load and load/Regions.cpp's relation_buildings until core-p14), so a relation whose ring started where a neighbour's
// did was never loaded: here 2, 4 and the part 5 (the engine audit, 17 Nov 2026). A relation's ring is known by its
// ways since.
TEST_CASE("load: a relation whose ring starts at a neighbour's first point is loaded") {
    const auto doc = read(terrace);
    ATLAS_REQUIRE(doc.has_value());
    const auto ids = [](const std::vector<load::Building>& list) {
        std::string out;
        for (const load::Building& record : list) out += std::format("{} ", record.id);
        return out;
    };
    // as read, before the cleaning
    const auto read_as = load::load_living(*doc, load::westerkerk_frame(), load::amsterdam_palette());
    ATLAS_REQUIRE(read_as.has_value());
    ATLAS_REQUIRE(ids(read_as->buildings) == "1 2 3 4 ", ids(read_as->buildings));
    ATLAS_REQUIRE(ids(read_as->parts) == "5 ", ids(read_as->parts));
    const std::vector<load::Building>& b = read_as->buildings;
    // the case itself: the rings do start at the same point
    CHECK(b[1].poly[0] == b[0].poly[0]);
    CHECK(read_as->parts[0].poly[0] == b[0].poly[0]);
    CHECK(b[3].poly[0] == b[2].poly[0]);
    CHECK(!b[0].relation);
    CHECK(b[1].relation);
    CHECK(b[2].relation);
    CHECK(b[3].relation);
    CHECK(read_as->parts[0].relation);
    for (const load::Building& house : b) CHECK(house.poly.size() == 4);
    CHECK(read_as->parts[0].poly.size() == 4);
    CHECK(read_as->counts.doubles == 0); // no way here is a building of its own and a relation's ring
    // as a region loads it: every house stands, the part too, and the first house is drawn less its part
    const auto loaded = load::load_region(*doc, amsterdam_region());
    ATLAS_REQUIRE(loaded.has_value());
    const load::Scene& s = *loaded;
    CHECK(ids(s.buildings) == "1 2 3 4 ");
    ATLAS_REQUIRE(ids(s.parts) == "5 ", ids(s.parts));
    CHECK(std::ranges::none_of(s.buildings, &load::Building::hidden));
    CHECK(!s.parts[0].hidden);
    CHECK(s.parts[0].parent_id == 1);
    CHECK(s.parts[0].h == 12.0);
    CHECK(s.counts.covered == 1);
    CHECK(s.counts.hidden == 0);
    CHECK(s.counts.vanished == 0);
    CHECK(s.counts.overlapping_pairs == 0);
    CHECK(s.counts.uncovered_outlines == 0);
}

TEST_CASE("load: simplify drops the corners within its tolerance of a straight wall") {
    // a 20 x 10 m ring with a corner 0.3 m off its long side
    const std::vector<load::V2> ring = {{0, 0}, {10, 0.3}, {20, 0}, {20, 10}, {10, 10}, {0, 10}};
    const auto kept = load::simplify(ring, 0.5);
    CHECK(kept.size() == 4);
    CHECK(load::simplify(ring, 0.2).size() == 5); // the corner is kept; (10, 10), on its wall, goes at any tolerance
    // fewer than six points: as it is
    const std::vector<load::V2> small = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
    CHECK(load::simplify(small, 5.0).size() == 4);
}

TEST_CASE("load: the canal ring's raw.json loaded as a region, with nothing that cannot be there (ATLAS_REGIONS_DIR)") {
    // the canal ring's buildings and parts once cleaned (load/Clean.hpp), recorded 17 Nov 2026
    constexpr std::size_t buildings_after = 25953;
    constexpr std::size_t parts_after = 648;
    const std::string dir = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: the renderer's data are not loaded here");
        return;
    }
    std::ifstream in(std::filesystem::path(dir) / "grachten" / "raw.json", std::ios::binary);
    if (!in) {
        MESSAGE("the canal ring's raw.json is not on this machine");
        return;
    }
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto doc = read(text);
    ATLAS_REQUIRE(doc.has_value());
    // region.json's origin, kx as the port's configure computes it (correctly rounded: CrMath)
    const double lat0 = 52.372;
    const load::RegionConfig region{.name = "grachten",
                                    .frame = {.lat0 = lat0, .lon0 = 4.891, .kx = 0x1.097c73fb3b404p+16, .kz = 110540},
                                    .bbox = {52.36, 4.872, 52.393, 4.922},
                                    .tradition = nullptr,
                                    .palette = load::amsterdam_palette()};
    const auto t0 = std::chrono::steady_clock::now();
    const auto loaded = load::load_region(*doc, region);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    ATLAS_REQUIRE(loaded.has_value());
    MESSAGE("grachten as a region: ", loaded->buildings.size(), " buildings, ", loaded->parts.size(), " parts, ",
            loaded->land.size(), " land covers, ", loaded->water.size(), " water rings, ", loaded->water_holes.size(),
            " islands, in ", ms, " ms");
    const load::BuildingCounts& c = loaded->counts;
    MESSAGE("grachten's buildings: ", c.underground, " below the ground by location and ", c.underground_layer,
            " by layer, ", c.doubles, " doubles (", c.doubles_disagree, " disagreeing), ", c.bounded,
            " with numbers past the bounds, ", c.courtyards, " with courtyards, ", c.covered,
            " outlines drawn less their "
            "parts, ",
            c.hidden, " covered whole, ", c.clipped, " clipped, ", c.raised, " raised, ", c.vanished, " vanished, ",
            c.seamed, " seamed; ", c.overlapping_pairs, " pairs overlapping, ", c.uncovered_outlines,
            " outlines uncovered (", c.uncovered_m2, " m², slivers ", c.sliver_m2, " m²), ", c.flat_roofs,
            " pitched roofs drawn flat");
    CHECK(c.overlapping_pairs == 0);
    CHECK(c.uncovered_outlines == 0);
    CHECK(c.unsettled == 0);
    CHECK(c.unmeasured == 0);
    CHECK(c.underground == 17);       // location=underground
    CHECK(c.underground_layer == 11); // a layer below 0 alone
    CHECK(c.doubles == 11);           // 8 ways with the role outer, and the three of r20772608 without a role
    CHECK(loaded->buildings.size() == buildings_after);
    CHECK(loaded->parts.size() == parts_after);
    CHECK(loaded->land.size() == 2384);
    CHECK(loaded->water.size() == 331);
    // ATLAS_CLEAN_DUMP: what is drawn, as Overpass's JSON, for the fault detectors (an outline not drawn tagged
    // atlas:drawn=no)
    if (const std::string out = atlas::test::environment("ATLAS_CLEAN_DUMP"); !out.empty()) {
        std::ofstream f(out, std::ios::binary);
        f << R"({"version":0.6,"elements":[)";
        bool first = true;
        for (const auto* list : {&loaded->buildings, &loaded->parts}) {
            const bool part = list == &loaded->parts;
            for (const load::Building& b : *list) {
                f << (first ? "" : ",") << R"({"type":"way","id":)" << (b.relation ? 9000000000000 + b.id : b.id)
                  << R"(,"tags":{")" << (part ? "building:part" : "building") << R"(":"yes","height":")" << b.h
                  << R"(","min_height":")" << b.min << R"(","atlas:drawn":")" << (b.hidden ? "no" : "yes")
                  << R"("},"geometry":[)";
                first = false;
                for (std::size_t i = 0; i <= b.poly.size(); ++i) {
                    const load::V2 p = b.poly[i % b.poly.size()];
                    const auto lat = atlas::tiles::grid_lat(region.frame, p.z);
                    const auto lon = atlas::tiles::grid_lon(region.frame, p.x);
                    f << (i == 0 ? "" : ",") << R"({"lat":)" << (lat ? *lat : 0) / 1e7 << R"(,"lon":)"
                      << (lon ? *lon : 0) / 1e7 << "}";
                }
                f << "]}";
            }
        }
        f << "]}\n";
        MESSAGE("grachten's buildings written to ", out);
    }
}

TEST_CASE("load: every region's buildings, with nothing that cannot be there and what the cleaning did "
          "(ATLAS_REGIONS_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: the regions are not loaded here");
        return;
    }
    std::vector<std::filesystem::path> regions;
    for (const auto& e : std::filesystem::directory_iterator(dir)) {
        if (std::filesystem::exists(e.path() / "raw.json") && std::filesystem::exists(e.path() / "region.json")) {
            regions.push_back(e.path());
        }
    }
    std::ranges::sort(regions);
    for (const std::filesystem::path& folder : regions) {
        const std::string name = folder.filename().string();
        const std::string text = read_text(folder / "raw.json");
        const std::string json = read_text(folder / "region.json");
        const auto doc = read(text);
        const auto r = atlas::tiles::read_json(std::as_bytes(std::span(json.data(), json.size())));
        if (!doc || !r) {
            MESSAGE(name, ": does not read");
            continue;
        }
        const atlas::tiles::Value origin = r->root().find("origin").value_or(atlas::tiles::Value());
        const atlas::tiles::Value bbox = r->root().find("bbox").value_or(atlas::tiles::Value());
        if (origin.size() != 2 || bbox.size() != 4) continue;
        const double lat0 = origin.child(0).number().value_or(0);
        std::shared_ptr<const load::Tradition> tradition;
        if (const auto t = r->root().find("tradition"); t && t->string())
            tradition = load::tradition_named(*t->string());
        const load::RegionConfig region{.name = name,
                                        .frame = {.lat0 = lat0,
                                                  .lon0 = origin.child(1).number().value_or(0),
                                                  .kx = 111320 * atlas::pymath::cos(atlas::pymath::radians(lat0)),
                                                  .kz = 110540},
                                        .bbox = {bbox.child(0).number().value_or(0), bbox.child(1).number().value_or(0),
                                                 bbox.child(2).number().value_or(0),
                                                 bbox.child(3).number().value_or(0)},
                                        .tradition = tradition,
                                        .palette = load::region_palette(name)};
        const auto t0 = std::chrono::steady_clock::now();
        const auto loaded = load::load_region(*doc, region);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        ATLAS_REQUIRE(loaded.has_value(), name);
        const load::BuildingCounts& c = loaded->counts;
        const std::string line = std::format(
            "region {}: {} buildings, {} parts; pairs {} uncovered {} flat {} (by a seam {}); underground {}+{} "
            "doubles {} (disagreeing {}) bounded {} courtyards {} covered {} hidden {} clipped {} raised {} vanished "
            "{} "
            "seamed {} unsettled {} unmeasured {}; uncovered {:.1f} m², slivers {:.1f} m², dropped {:.1f} m²; {:.0f} "
            "ms",
            name, loaded->buildings.size(), loaded->parts.size(), c.overlapping_pairs, c.uncovered_outlines,
            c.flat_roofs, c.flat_by_seam, c.underground, c.underground_layer, c.doubles, c.doubles_disagree, c.bounded,
            c.courtyards, c.covered, c.hidden, c.clipped, c.raised, c.vanished, c.seamed, c.unsettled, c.unmeasured,
            c.uncovered_m2, c.sliver_m2, c.dropped_m2, ms);
        MESSAGE(line);
        (void)std::printf("%s\n", line.c_str()); // NOLINT: the lines a run's log keeps
        CHECK_MESSAGE(c.overlapping_pairs == 0, name);
        CHECK_MESSAGE(c.uncovered_outlines == 0, name);
    }
}

TEST_CASE("load: generic.py's colours: a colour word or hex form read, a guessed colour varied per building") {
    CHECK(load::colour_hex(" Light_Grey ") == "#D3D3D3");
    CHECK(load::colour_hex("#abc") == "#AABBCC");
    CHECK(load::colour_hex("#ABCDEF") == "#ABCDEF");
    CHECK(load::colour_hex("red") == "#B03A2E");
    CHECK(load::colour_hex("rood") == std::nullopt);
    CHECK(load::colour_hex(std::nullopt) == std::nullopt);
    // generic.py's own: jitter('#8E5A46', 123) and jitter('#9C988F', 130, 0.06)
    CHECK(load::jitter("#8E5A46", 123) == "#875542");
    CHECK(load::jitter("#9C988F", 130, 0.06) == "#9C988F");
}

TEST_CASE("load: Python's float() of a string, as a coaster track's layer is read") {
    CHECK(load::py_float("1") == 1.0);
    CHECK(load::py_float(" -2 ") == -2.0);
    CHECK(load::py_float("+3") == 3.0);
    CHECK(load::py_float("1_000") == 1000.0);
    CHECK(load::py_float("1.5e2") == 150.0);
    CHECK(load::py_float(".5") == 0.5);
    CHECK(load::py_float("5.") == 5.0);
    CHECK(load::py_float("-Infinity") == -std::numeric_limits<double>::infinity());
    CHECK(load::py_float("inf") == std::numeric_limits<double>::infinity());
    const auto nan = load::py_float("NaN");
    ATLAS_REQUIRE(nan.has_value());
    CHECK(std::isnan(*nan));
    for (const std::string_view bad : {"", " ", ".", "1__0", "_1", "1_", "1;2", "1e", "e5", "yes", "0x10", "1.5.2"}) {
        CHECK_MESSAGE(!load::py_float(bad).has_value(), bad);
    }
}

TEST_CASE("load: a theme park's coaster tracks, as coasters.py loads them") {
    // a loop of four pieces about its ride (station, lift hill, a run at layer 2, brake run), a wooden coaster's piece
    // near its ride's outline, a monorail, a piece in a tunnel and one inside a building
    constexpr std::string_view park = R"({"elements": [
{"type": "node", "id": 1, "lat": 52.37025, "lon": 4.88025, "tags": {"attraction": "roller_coaster", "name": "Big One"}},
{"type": "way", "id": 2, "tags": {"attraction": "roller_coaster", "name": "Woodie"}, "geometry": [
  {"lat": 52.3719, "lon": 4.8819}, {"lat": 52.3719, "lon": 4.8821}, {"lat": 52.3722, "lon": 4.8821}, {"lat": 52.3719, "lon": 4.8819}]},
{"type": "way", "id": 10, "tags": {"roller_coaster": "track", "roller_coaster:track": "station"}, "geometry": [
  {"lat": 52.3700, "lon": 4.8800}, {"lat": 52.3700, "lon": 4.8805}]},
{"type": "way", "id": 11, "tags": {"roller_coaster": "track", "roller_coaster:track": "lift_hill"}, "geometry": [
  {"lat": 52.3700, "lon": 4.8805}, {"lat": 52.3705, "lon": 4.8805}]},
{"type": "way", "id": 12, "tags": {"roller_coaster": "track", "layer": "2"}, "geometry": [
  {"lat": 52.3705, "lon": 4.8805}, {"lat": 52.37052, "lon": 4.88025}, {"lat": 52.3705, "lon": 4.8800}]},
{"type": "way", "id": 13, "tags": {"roller_coaster": "track", "roller_coaster:track": "brake_run"}, "geometry": [
  {"lat": 52.3705, "lon": 4.8800}, {"lat": 52.3700, "lon": 4.8800}]},
{"type": "way", "id": 14, "tags": {"roller_coaster": "track", "layer": "1"}, "geometry": [
  {"lat": 52.3720, "lon": 4.8818}, {"lat": 52.3722, "lon": 4.8822}]},
{"type": "way", "id": 15, "tags": {"railway": "monorail", "name": "Mono"}, "geometry": [
  {"lat": 52.3740, "lon": 4.8840}, {"lat": 52.3742, "lon": 4.8845}]},
{"type": "way", "id": 16, "tags": {"roller_coaster": "track", "tunnel": "yes"}, "geometry": [
  {"lat": 52.3701, "lon": 4.8801}, {"lat": 52.3702, "lon": 4.8802}]},
{"type": "way", "id": 20, "tags": {"building": "yes"}, "geometry": [
  {"lat": 52.3730, "lon": 4.8830}, {"lat": 52.3730, "lon": 4.8835}, {"lat": 52.3735, "lon": 4.8835},
  {"lat": 52.3735, "lon": 4.8830}, {"lat": 52.3730, "lon": 4.8830}]},
{"type": "way", "id": 17, "tags": {"roller_coaster": "track"}, "geometry": [
  {"lat": 52.3731, "lon": 4.8831}, {"lat": 52.3733, "lon": 4.8833}]}
]})";
    const auto doc = read(park);
    ATLAS_REQUIRE(doc.has_value());
    load::RideProfiles rides;
    rides.heights = {{"Big One", 30.0}, {"Woodie", 20.0}};
    rides.wooden = {{"Woodie", true}};
    load::RegionConfig region = amsterdam_region();
    region.park = &rides;
    const auto loaded = load::load_region(*doc, region);
    ATLAS_REQUIRE(loaded.has_value());
    const std::vector<load::CoasterTrack>& t = loaded->coasters;
    ATLAS_REQUIRE(t.size() == 6); // the tunnel's piece and the indoor one are left out
    for (std::size_t i = 0; i < 4; ++i) {
        CHECK(t[i].ride == "Big One");
        CHECK(t[i].kind == "steel");
        CHECK(t[i].ys.size() == t[i].pts.size());
    }
    CHECK(t[0].role == "station");
    CHECK(t[1].role == "lift_hill");
    CHECK(t[2].role == "track");
    CHECK(t[3].role == "brake_run");
    // the station at 1.2 m, met by the brake run (1.2) and the lift hill (no height): 1.2 at both its ends
    CHECK(t[0].ys.front() == 1.2);
    CHECK(t[0].ys.back() == 1.2);
    // the lift hill from its low end (the station's 1.2) up to 95 % of its ride's 30 m
    CHECK(t[1].ys.front() == 1.2);
    CHECK(std::abs(t[1].ys.back() - (30.0 * 0.95)) < 1e-12);
    // the run at layer 2: 0.75 of 30 m, ramping down to where it meets the brake run (the mean of 22.5 and 1.2)
    CHECK(t[2].ys.front() == 22.5);
    CHECK(std::abs(t[2].ys[1] - 22.5) < 1e-9); // its middle, where both ramps end
    CHECK(std::abs(t[2].ys.back() - ((22.5 + 1.2) / 2)) < 1e-12);
    CHECK(std::abs(t[3].ys.front() - ((22.5 + 1.2) / 2)) < 1e-12);
    // the wooden coaster's piece at layer 1: 0.45 of its ride's 20 m
    CHECK(t[4].ride == "Woodie");
    CHECK(t[4].kind == "wood");
    CHECK(t[4].ys.front() == 9.0);
    // the monorail: a beam at 3.5 m
    CHECK(t[5].kind == "mono");
    CHECK(t[5].ride == "Mono");
    CHECK(t[5].role == "mono");
    CHECK(t[5].ys.front() == 3.5);
    // a layer Python's float() cannot read, where the renders stopped, never refuses the load: it is the number it
    // starts with ("2;3" is 2), or 0 ("inf", "high")
    const auto ys_with = [&](std::string_view layer) -> std::vector<double> {
        std::string other(park);
        other.replace(other.find(R"("layer": "2")"), 12, std::format(R"("layer": "{}")", layer));
        const auto d = read(other);
        ATLAS_REQUIRE_OR_RETURN({}, d.has_value());
        const auto l = load::load_region(*d, region);
        ATLAS_REQUIRE_OR_RETURN({}, l.has_value(), layer);
        ATLAS_REQUIRE_OR_RETURN({}, l->coasters.size() == 6, layer);
        return l->coasters[2].ys;
    };
    CHECK(ys_with("2;3") == t[2].ys);
    CHECK(ys_with("inf") == ys_with("0"));
    CHECK(ys_with("high") == ys_with("0"));
    CHECK(ys_with("0") != t[2].ys);
    // no park, no tracks
    CHECK(load::load_region(*doc, amsterdam_region())->coasters.empty());
}

TEST_CASE("load: no tag's number ends the load, however large, in every look and cut") {
    // CI's fuzz_load, 14 and 16 Nov 2026: a `height` of 7e26 m ended the process in a tradition's look, where the
    // levels were round((h - roof_h) / floor_h) as a Long. Every number the loader reads from a tag, past any Long's
    // floors, past the largest double (infinite) and negative, on each kind of record that reads one.
    const std::string huge = "7" + std::string(26, '0');
    const std::string endless(400, '9');
    load::RideProfiles rides;
    const auto tradition = load::read_tradition(std::as_bytes(std::span(std::string_view(R"(
{"name": "n", "says": "s", "floor_h": 3.0, "levels": {"default": 2}, "walls": {"default": [["brick", "#8E5A46", 1]]},
 "roof": {"default": {"shapes": [["mansard", 1], ["gabled", 1], ["skillion", 1], ["flat", 1]], "material": "metal",
                      "colours": ["#737E88"]}}})"))));
    ATLAS_REQUIRE(tradition.has_value());
    for (const std::string& v : {huge, "-" + huge, endless, "-" + endless}) {
        std::string doc = R"({"elements": [)";
        std::int64_t id = 0;
        const auto square = [&](std::string_view tags) {
            // a 12 m square, each a little east of the last
            const double lon = 4.8800 + (0.0003 * static_cast<double>(id));
            doc += std::format(
                R"({}{{"type": "way", "id": {}, "tags": {{{}}}, "geometry": [{{"lat": 52.3700, "lon": {:.4f}}},
 {{"lat": 52.3700, "lon": {:.4f}}}, {{"lat": 52.37011, "lon": {:.4f}}}, {{"lat": 52.37011, "lon": {:.4f}}},
 {{"lat": 52.3700, "lon": {:.4f}}}]}})",
                id == 0 ? "" : ",\n", id + 1, tags, lon, lon + 0.00018, lon + 0.00018, lon, lon);
            ++id;
        };
        const auto line = [&](std::string_view tags) {
            doc += std::format(R"(,
{{"type": "way", "id": {}, "tags": {{{}}}, "geometry": [{{"lat": 52.3720, "lon": 4.8800}}, {{"lat": 52.3725, "lon": 4.8830}}]}})",
                               ++id, tags);
        };
        const auto node = [&](std::string_view tags) {
            ++id;
            doc += std::format(R"(,
{{"type": "node", "id": {}, "lat": 52.3740, "lon": {:.4f}, "tags": {{{}}}}})",
                               id, 4.8800 + (0.0002 * static_cast<double>(id)), tags);
        };
        for (const std::string_view b : {"house", "apartments", "yes", "office"}) {
            square(std::format(R"("building": "{}", "height": "{}")", b, v));
            square(std::format(R"("building": "{}", "height": "15", "roof:levels": "{}")", b, v));
            square(std::format(R"("building": "{}", "building:levels": "{}", "roof:levels": "{}")", b, v, v));
            square(std::format(R"("building": "{}", "roof:shape": "gabled", "roof:height": "{}")", b, v));
            square(std::format(R"("building": "{}", "roof:shape": "mansard", "height": "{}", "roof:levels": "{}")", b,
                               v, v));
            square(std::format(R"("building": "{}", "min_height": "{}", "height": "{}")", b, v, v));
        }
        square(std::format(R"("building:part": "yes", "height": "{}", "roof:levels": "{}")", v, v));
        line(std::format(R"("highway": "primary", "width": "{}", "lanes": "{}", "layer": "{}")", v, v, v));
        line(std::format(R"("highway": "motorway", "lanes": "{}")", v));
        line(std::format(R"("aeroway": "runway", "width": "{}")", v));
        line(std::format(R"("roller_coaster": "track", "layer": "{}")", v));
        line(std::format(R"("man_made": "gantry", "height": "{}")", v));
        node(std::format(R"("natural": "tree", "height": "{}", "diameter_crown": "{}")", v, v));
        node(std::format(R"("man_made": "mast", "height": "{}")", v));
        node(std::format(R"("man_made": "tower", "height": "{}")", v));
        node(std::format(R"("power": "tower", "height": "{}")", v));
        node(std::format(R"("power": "generator", "generator:source": "wind", "height": "{}", "rotor:diameter": "{}")",
                         v, v));
        doc += "]}";
        const auto read_doc = read(doc);
        ATLAS_REQUIRE(read_doc.has_value(), osm::name_of(read_doc.error().fault), " at ", read_doc.error().at);
        for (const std::string_view name : {"test", "paris"}) {
            for (const std::shared_ptr<const load::Tradition>& t :
                 {std::shared_ptr<const load::Tradition>(), *tradition}) {
                load::RegionConfig region = amsterdam_region();
                region.name = name;
                region.tradition = t;
                region.park = &rides;
                const auto loaded = load::load_region(*read_doc, region);
                ATLAS_REQUIRE(loaded.has_value(), v.size(), " ", name);
                CHECK(loaded->buildings.size() == 24);
                for (const load::Building& b : loaded->buildings) CHECK(b.levels >= 1);
                // a track below ground is left out; one infinitely far below is read as layer 0
                CHECK(loaded->coasters.size() == (v.starts_with("-7") ? 0U : 1U));
                CHECK(load::cut_region(*loaded, region.frame, {}).has_value());
            }
        }
        const load::Looks looks{.region = "ams", .landuse = load::LandUseFrom::areas, .keep_homes = true};
        for (const load::Looks* l : {&looks, static_cast<const load::Looks*>(nullptr)}) {
            const auto living = load::load_amsterdam(*read_doc, load::westerkerk_frame(), load::amsterdam_palette(), l);
            ATLAS_REQUIRE(living.has_value());
            CHECK(load::cut_living(*living, load::westerkerk_frame()).has_value());
        }
    }
}
