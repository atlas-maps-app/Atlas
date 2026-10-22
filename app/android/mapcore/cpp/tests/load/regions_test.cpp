// A region's load (load/Regions.hpp): a multipolygon building's outer ways joined into its ring, a water relation and
// its island, land cover in LAND's order, a big road in a tunnel left out, the closest roof shape, Douglas-Peucker's
// simplify, and where ATLAS_REGIONS_DIR names the renderer's data/regions, the canal ring's raw.json loaded whole as
// the packer loads it (its counts are RegionsParityTest's, which holds every record to the port's Regions.load).
#include "load/Generic.hpp"
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
