// The living map's load (load/Amsterdam.hpp) and what it reads with (load/Real.hpp, load/PoiStyle.hpp, text/Case.hpp):
// a tag value's leading number and year as Python's regular expressions read them; a document with each kind of
// record, its values those render/amsterdam.py's own load gives for it (CPython 3.9, run on the same document); what
// the renders cannot load, loaded on as amsterdam.hpp says; a position off the Earth refused; and, where
// ATLAS_REGIONS_DIR names the renderer's data/regions, the canal ring's raw.json loaded whole. :mapcorecheck's
// AmsterdamParityTest holds every record of the regions' raw.json to the port's, field for field.
#include "load/Amsterdam.hpp"
#include "load/PoiStyle.hpp"
#include "load/Real.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "text/Case.hpp"

#include <doctest/doctest.h>

#include <bit>
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

/// Whether two doubles are the same bits.
bool same(double a, double b) { return std::bit_cast<std::uint64_t>(a) == std::bit_cast<std::uint64_t>(b); }

std::vector<std::string_view> keys_of(const load::Guessed& g) {
    std::vector<std::string_view> out;
    for (const load::Guess k : g.keys()) out.push_back(load::key_of(k));
    return out;
}

// One record of each kind, and what the renders cannot load: a node without a position (14), building:levels without
// a number (3), a road without points (33).
constexpr std::string_view document = R"({"version": 0.6, "elements": [
{"type": "way", "id": 1, "tags": {"building": "house", "start_date": " 1650-05", "building:levels": "4"},
 "geometry": [{"lat": 52.3746, "lon": 4.884}, {"lat": 52.3746, "lon": 4.88415}, {"lat": 52.37469, "lon": 4.88415},
              {"lat": 52.37469, "lon": 4.884}, {"lat": 52.3746, "lon": 4.884}]},
{"type": "relation", "id": 2, "tags": {"building:part": "yes", "height": "30", "roof:shape": "pyramidal"},
 "members": [{"type": "way", "ref": 7, "role": "inner", "geometry": [{"lat": 52.0, "lon": 4.0}]},
             {"type": "way", "ref": 8, "role": "outer", "geometry": [{"lat": 52.37462, "lon": 4.88402},
              {"lat": 52.37462, "lon": 4.88409}, {"lat": 52.37467, "lon": 4.88409}, {"lat": 52.37467, "lon": 4.88402},
              {"lat": 52.37462, "lon": 4.88402}]}]},
{"type": "way", "id": 3, "tags": {"building": "yes", "height": "6.2 m", "building:levels": "many",
 "building:colour": "#ABCDEF"},
 "geometry": [{"lat": 52.3750, "lon": 4.8850}, {"lat": 52.3750, "lon": 4.8853}, {"lat": 52.3752, "lon": 4.8853},
              {"lat": 52.3752, "lon": 4.8850}, {"lat": 52.3750, "lon": 4.8850}]},
{"type": "way", "id": 4, "tags": {"building": "no"}, "geometry": [{"lat": 52.3750, "lon": 4.8850},
 {"lat": 52.3750, "lon": 4.8853}, {"lat": 52.3752, "lon": 4.8853}, {"lat": 52.3750, "lon": 4.8850}]},
{"type": "way", "id": 5, "tags": {"building": "shed"}, "geometry": [{"lat": 52.3750, "lon": 4.8850},
 {"lat": 52.3750, "lon": 4.88501}, {"lat": 52.37501, "lon": 4.88501}, {"lat": 52.3750, "lon": 4.8850}]},
{"type": "node", "id": 10, "lat": 52.37464, "lon": 4.88407, "tags": {"shop": "bakery", "name": "Bakker"}},
{"type": "node", "id": 11, "lat": 52.3760, "lon": 4.8860, "tags": {"amenity": "cafe", "brand": "Foo"}},
{"type": "node", "id": 12, "lat": 52.3761, "lon": 4.8861, "tags": {"natural": "tree", "height": "8"}},
{"type": "node", "id": 13, "lat": 52.3762, "lon": 4.8862, "tags": {"shop": "3D_printing_materials"}},
{"type": "node", "id": 14, "tags": {"shop": "kiosk"}},
{"type": "way", "id": 20, "tags": {"natural": "water"}, "geometry": [{"lat": 52.3770, "lon": 4.8870},
 {"lat": 52.3772, "lon": 4.8870}, {"lat": 52.3772, "lon": 4.8875}, {"lat": 52.3770, "lon": 4.8875},
 {"lat": 52.3770, "lon": 4.8870}]},
{"type": "way", "id": 21, "tags": {"waterway": "canal", "name": "Prinsengracht"}, "geometry": [
 {"lat": 52.3730, "lon": 4.8830}, {"lat": 52.3740, "lon": 4.8835}, {"lat": 52.3750, "lon": 4.8838}]},
{"type": "way", "id": 30, "tags": {"highway": "residential", "name": "Westermarkt", "width": "6", "bridge": "yes",
 "layer": "1"}, "geometry": [{"lat": 52.3745, "lon": 4.8830}, {"lat": 52.3745, "lon": 4.8845}]},
{"type": "way", "id": 31, "tags": {"highway": "pedestrian"}, "geometry": [{"lat": 52.3780, "lon": 4.8800},
 {"lat": 52.3780, "lon": 4.8805}, {"lat": 52.3783, "lon": 4.8805}, {"lat": 52.3783, "lon": 4.8800},
 {"lat": 52.3780, "lon": 4.8800}]},
{"type": "way", "id": 32, "tags": {"highway": "motorway"}, "geometry": [{"lat": 52.3745, "lon": 4.8830},
 {"lat": 52.3745, "lon": 4.8845}]},
{"type": "way", "id": 33, "tags": {"highway": "footway"}, "geometry": []},
{"type": "way", "id": 40, "tags": {"railway": "tram"}, "geometry": [{"lat": 52.3746, "lon": 4.8830},
 {"lat": 52.3746, "lon": 4.8846}]},
{"type": "relation", "id": 50, "tags": {"leisure": "park", "name": "Park"}, "members": [{"type": "way", "ref": 9,
 "role": "outer", "geometry": [{"lat": 52.3790, "lon": 4.8800}, {"lat": 52.3790, "lon": 4.8810},
 {"lat": 52.3795, "lon": 4.8810}, {"lat": 52.3795, "lon": 4.8800}, {"lat": 52.3790, "lon": 4.8800}]}]}
]})";

} // namespace

TEST_CASE("load: num() and the year read a tag value's leading number as Python's regular expressions do") {
    CHECK(load::num("12") == 12.0);
    CHECK(load::num(" \t12.5 m") == 12.5);
    CHECK(load::num("3;4") == 3.0);
    CHECK(load::num("1,2,3") == 1.0);
    CHECK(load::num("12.") == 12.0); // a point without a digit after it ends the number
    CHECK(load::num(".5") == std::nullopt);
    CHECK(load::num("white") == std::nullopt);
    CHECK(load::num("") == std::nullopt);
    CHECK(load::num("- 3") == std::nullopt);
    const auto negative_zero = load::num("-0");
    ATLAS_REQUIRE(negative_zero.has_value());
    CHECK(same(*negative_zero, -0.0));
    CHECK(load::num(" 　"
                    "7") == 7.0);                        // Python's \s: a no-break and an ideographic space
    CHECK(load::num("١٢.٥") == 12.5);                    // Arabic-Indic digits, as float() reads them
    CHECK(load::num("\U0001D7D9") == std::nullopt);      // a digit beyond the plane, as the port's UTF-16 test
    CHECK(load::num(std::string(400, '9')) == HUGE_VAL); // float() of 400 nines
    CHECK(load::num(std::optional<std::string_view>{}) == std::nullopt);
    CHECK(load::leading_year("1650") == 1650);
    CHECK(load::leading_year("  2009-02") == 2009);
    CHECK(load::leading_year("C13") == std::nullopt);
    CHECK(load::leading_year("945 BC") == std::nullopt);
    CHECK(load::leading_year("١٩٠٠") == 1900);
}

TEST_CASE("load: capitalize() as the port's str.capitalize()") {
    CHECK(atlas::text::capitalize("3d printing materials") == "3d printing materials");
    CHECK(atlas::text::capitalize("plumbing & Heating") == "Plumbing & heating");
    CHECK(atlas::text::capitalize("") == "");
    CHECK(atlas::text::capitalize("ßtraße") == "ßtraße"); // no titlecase for ß
    CHECK(atlas::text::capitalize("ǆemal") == "ǅemal");   // ǆ titlecases to ǅ
    CHECK(atlas::text::capitalize("xİ") == "Xi̇");         // İ lowercases to i̇
    CHECK(atlas::text::capitalize("ΟΔΥΣΣΕΥΣ") ==          // ΟΔΥΣΣΕΥΣ: Οδυσσευς
          "Οδυσσευς");
}

TEST_CASE("load: the place styles of poi_style.py") {
    const auto doc = read(R"({"elements": [
        {"type": "node", "id": 1, "tags": {"amenity": "pub", "shop": "bakery"}},
        {"type": "node", "id": 2, "tags": {"shop": "clothes", "brand": "Levi's", "brand:wikidata": "Q127962"}},
        {"type": "node", "id": 3, "tags": {"office": "yes", "brand": ""}},
        {"type": "node", "id": 4, "tags": {"healthcare": ""}},
        {"type": "node", "id": 5, "tags": {"leisure": "fitness_centre", "brand": "Gym"}}]})");
    ATLAS_REQUIRE(doc.has_value());
    const auto style = [&](std::size_t i) { return load::place_style(load::Tags(doc->tags(doc->elements()[i]))); };
    const auto pub = style(0); // amenity wins over shop
    ATLAS_REQUIRE(pub.has_value());
    CHECK(pub->kind == "Brown café");
    CHECK(pub->col == "#E07A1F");
    const auto levis = style(1);
    ATLAS_REQUIRE(levis.has_value());
    CHECK(levis->bg == "#C41230");
    CHECK(levis->fg == "#FFFFFF");
    const auto office = style(2); // an empty brand is kept, and gets no sign
    ATLAS_REQUIRE(office.has_value());
    CHECK(office->brand == std::optional<std::string_view>(""));
    CHECK(office->bg == std::nullopt);
    CHECK(!style(3).has_value()); // healthcare must not be empty
    const auto gym = style(4);    // a brand without a listed wikidata gets the neutral sign
    ATLAS_REQUIRE(gym.has_value());
    CHECK(gym->bg == "#26282C");
}

TEST_CASE("load: each kind of record, its values those amsterdam.py's load gives") {
    const auto doc = read(document);
    ATLAS_REQUIRE(doc.has_value(), osm::name_of(doc.error().fault), " at ", doc.error().at);
    const auto loaded = load::load_amsterdam(*doc, load::westerkerk_frame(), load::amsterdam_palette());
    ATLAS_REQUIRE(loaded.has_value(), load::name_of(loaded.error().fault));
    const load::Scene& s = *loaded;
    ATLAS_REQUIRE(s.buildings.size() == 2); // 4 is building=no, 5 too small
    ATLAS_REQUIRE(s.parts.size() == 1);
    CHECK(s.water.size() == 1);
    CHECK(s.roads.size() == 1); // the motorway is not drawn, the footway has no points
    CHECK(s.rails.size() == 1);
    CHECK(s.areas.size() == 2);
    CHECK(s.trees.size() == 1);
    CHECK(s.nodes.size() == 4); // 14 has no position
    CHECK(s.pois.size() == 3);
    CHECK(s.labels.size() == 2);

    const load::Building& house = s.buildings[0];
    CHECK(house.id == 1);
    CHECK(house.h == 12.0);
    CHECK(house.src == load::HeightSource::levels);
    CHECK(house.year == 1650);
    CHECK(house.roof == "gabled");
    CHECK(house.roof_h == 5.0);
    CHECK(house.orient == "along");
    CHECK(house.col == "#E8E2D4"); // random.Random(1)
    CHECK(house.rcol == "#56504C");
    CHECK(house.mat == std::nullopt);
    CHECK(house.mat_draw == "plaster");
    CHECK(house.levels == 4);
    CHECK(keys_of(house.guessed) == std::vector<std::string_view>{"roof:shape", "roof:height", "building:colour",
                                                                  "building:material", "roof:colour"});
    CHECK(house.poly.size() == 4);
    CHECK(same(house.bb.x0, 0.0));
    CHECK(same(house.bb.x1, 10.194067830736945));
    CHECK(same(house.bb.z0, -9.948600000016086));
    CHECK(same(house.bb.z1, -0.0)); // Python's max keeps the first of equal zeros
    CHECK(house.hidden);            // the part's centroid is inside it
    CHECK(house.places == std::vector<std::uint32_t>{0});

    const load::Building& plain = s.buildings[1];
    CHECK(plain.h == 6.2);
    CHECK(plain.src == load::HeightSource::height);
    CHECK(plain.col == "#ABCDEF");
    CHECK(plain.rcol == "#7E7A74");
    CHECK(!plain.mat_draw.has_value()); // a mapped colour comes with no material
    CHECK(plain.levels == 2);           // building:levels=many: from the height, where Python raises
    CHECK(keys_of(plain.guessed) == std::vector<std::string_view>{"roof:shape", "roof:colour"});
    CHECK(!plain.hidden);
    CHECK(plain.places.empty());

    const load::Building& part = s.parts[0];
    CHECK(part.id == 2);
    CHECK(part.h == 30.0);
    ATLAS_REQUIRE(part.roof_h.has_value());
    CHECK(same(*part.roof_h, 2.140754244460191));
    CHECK(part.levels == 9);
    CHECK(keys_of(part.guessed) == std::vector<std::string_view>{"roof:height"});
    CHECK(!part.col.has_value());

    const load::Node& bakery = s.nodes[0];
    CHECK(same(bakery.x, 0x1.30767bc1bd1d4p+2));
    CHECK(same(bakery.z, -0x1.1afb7e90d1940p+2));
    ATLAS_REQUIRE(bakery.style.has_value());
    CHECK(s.styles.at(*bakery.style).kind == "Bakery");
    CHECK(bakery.building == 0U);
    const load::Node& cafe = s.nodes[1];
    ATLAS_REQUIRE(cafe.style.has_value());
    CHECK(s.styles.at(*cafe.style).kind == "Café");
    CHECK(s.styles.at(*cafe.style).bg == "#26282C");
    CHECK(!cafe.building.has_value());
    CHECK(!s.nodes[2].style.has_value());
    const load::Node& printing = s.nodes[3];
    ATLAS_REQUIRE(printing.style.has_value());
    CHECK(s.styles.at(*printing.style).kind == "3d printing materials");
    CHECK(s.pois == std::vector<std::uint32_t>{0, 1, 3});

    const load::Tree& tree = s.trees[0];
    CHECK(tree.node == 2);
    CHECK(tree.h == 8.0);
    CHECK(tree.crown == 7.0);
    CHECK((!tree.guessed.height && tree.guessed.diameter_crown && tree.guessed.leaf_type));

    const load::Road& road = s.roads[0];
    CHECK(road.w == 6.0);
    CHECK(road.hw == "residential");
    CHECK(road.bridge);
    CHECK(road.layer == 1.0);
    CHECK(!road.wguess);

    CHECK(s.areas[0].kind == "pedestrian");
    CHECK(s.areas[0].poly.size() == 5); // amsterdam.py closes the ring twice and cleans off one
    CHECK(s.areas[0].poly.front() == s.areas[0].poly.back());
    CHECK(s.areas[1].kind == "park");
    CHECK(s.areas[1].poly.size() == 4);
    CHECK(s.labels[0].kind == "canal");
    CHECK(s.labels[0].name == "Prinsengracht");
    CHECK(s.labels[1].kind == "street");
    CHECK(s.labels[1].pts.size() == 2);
    CHECK(s.water[0].size() == 4);
}

TEST_CASE("load: a position off the Earth refuses the document, naming its element") {
    const auto doc = read(R"({"elements": [{"type": "node", "id": 7, "lat": 91.0, "lon": 4.0, "tags": {"a": "b"}},
        {"type": "node", "id": 8, "lat": 52.0, "lon": 4.0, "tags": {"a": "b"}}]})");
    ATLAS_REQUIRE(doc.has_value());
    const auto loaded = load::load_amsterdam(*doc, load::westerkerk_frame(), load::amsterdam_palette());
    ATLAS_REQUIRE(!loaded.has_value());
    CHECK(loaded.error().fault == load::LoadError::Fault::off_earth);
    CHECK(loaded.error().element == 7);
    // a way the load does not read is never looked at
    const auto unread = read(R"({"elements": [{"type": "way", "id": 9, "tags": {"highway": "motorway"},
        "geometry": [{"lat": 99.0, "lon": 4.0}, {"lat": 52.0, "lon": 4.0}]}]})");
    ATLAS_REQUIRE(unread.has_value());
    CHECK(load::load_amsterdam(*unread, load::westerkerk_frame(), load::amsterdam_palette()).has_value());
}

TEST_CASE("load: the canal ring's raw.json loaded whole (ATLAS_REGIONS_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: the renderer's data are not loaded here");
        return;
    }
    const std::filesystem::path path = std::filesystem::path(dir) / "grachten" / "raw.json";
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        MESSAGE(path.string(), " is not on this machine");
        return;
    }
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto doc = read(text);
    ATLAS_REQUIRE(doc.has_value());
    const auto t0 = std::chrono::steady_clock::now();
    const auto loaded = load::load_amsterdam(*doc, load::westerkerk_frame(), load::amsterdam_palette());
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    ATLAS_REQUIRE(loaded.has_value());
    std::size_t places = 0;
    std::size_t hidden = 0;
    for (const load::Building& b : loaded->buildings) {
        places += b.places.size();
        hidden += b.hidden ? 1U : 0U;
    }
    MESSAGE("grachten: ", loaded->buildings.size(), " buildings, ", loaded->parts.size(), " parts, ",
            loaded->nodes.size(), " nodes, ", places, " places in buildings, ", hidden, " outlines hidden, in ", ms,
            " ms");
    CHECK(loaded->buildings.size() == 25959); // AmsterdamParityTest's count, as the port loads it
    CHECK(loaded->parts.size() == 696);
    CHECK(places > 0);
    CHECK(hidden > 0);
}
