// A region's load (load/Regions.hpp): a multipolygon building's outer ways joined into its ring, a water relation and
// its island, land cover in LAND's order, a big road in a tunnel left out, the closest roof shape, Douglas-Peucker's
// simplify, a theme park's coaster tracks (load/Coasters.hpp) and Python's float() they read a layer with, and where
// ATLAS_REGIONS_DIR names the renderer's data/regions, the canal ring's raw.json loaded whole as the packer loads it
// (its counts are RegionsParityTest's, which holds every record to the port's Regions.load).
#include "load/Generic.hpp"
#include "load/Real.hpp"
#include "load/Regions.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <string>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;

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

TEST_CASE("load: the canal ring's raw.json loaded as a region, as the packer loads it (ATLAS_REGIONS_DIR)") {
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
    CHECK(loaded->buildings.size() == 25961);
    CHECK(loaded->parts.size() == 696);
    CHECK(loaded->land.size() == 2384);
    CHECK(loaded->water.size() == 331);
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
    // a layer Python's float() cannot read refuses the load, as the renders stop there
    std::string bad(park);
    bad.replace(bad.find(R"("layer": "2")"), 12, R"("layer": "2;3")");
    const auto refused = read(bad);
    ATLAS_REQUIRE(refused.has_value());
    const auto failed = load::load_region(*refused, region);
    ATLAS_REQUIRE(!failed.has_value());
    CHECK(failed.error().fault == load::LoadError::Fault::not_a_number);
    CHECK(failed.error().element == 12);
    // no park, no tracks
    CHECK(load::load_region(*doc, amsterdam_region())->coasters.empty());
}
