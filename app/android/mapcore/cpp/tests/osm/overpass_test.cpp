// Overpass's JSON read as Python's json module reads it (osm/Overpass.hpp) and Python's float() of a decimal
// (pymath::decimal_to_double): the decimal against the C library's strtod, correctly rounded on every host CI runs (and
// on Android), on random decimals of every length and scale and on the edges (halfway cases, the subnormals, the
// largest double, 800 digits); the reader on documents with each kind of element and value, its refusals named; and,
// where ATLAS_REGIONS_DIR names the renderer's data/regions, each region's raw.json read whole. The port's own loaders
// read the same files: :mapcorecheck's OverpassParityTest holds the values to theirs.
#include "base/pymath/PyMath.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <random>
#include <string>
#include <vector>

namespace {

namespace osm = atlas::osm;
using Fault = osm::ReadError::Fault;

/// The C library's strtod of `digits` × 10^`e10`, as its text.
double c_double(const std::string& digits, int e10) {
    const std::string text = digits + "e" + std::to_string(e10);
    return std::strtod(text.c_str(), nullptr);
}

/// A generator seeded with `seed`, the same draws every run.
[[nodiscard]] std::mt19937_64 seeded(std::uint64_t seed) { return std::mt19937_64(seed); }

std::span<const std::byte> bytes_of(std::string_view s) { return std::as_bytes(std::span(s.data(), s.size())); }

std::expected<osm::Document, osm::ReadError> read(std::string_view s) { return osm::read_overpass(bytes_of(s)); }

Fault fault_of(std::string_view s) {
    const auto r = read(s);
    REQUIRE_MESSAGE(!r.has_value(), "read without a fault: ", s);
    return r ? Fault::syntax : r.error().fault;
}

} // namespace

TEST_CASE("pymath: decimal_to_double is the correctly rounded double of every decimal, as Python's float()") {
    std::mt19937_64 random = seeded(20261021);
    std::size_t checked = 0;
    for (int i = 0; i < 200'000; ++i) {
        const std::size_t length = 1 + (random() % (i % 10 == 0 ? 60 : 20));
        std::string digits;
        for (std::size_t k = 0; k < length; ++k) digits += static_cast<char>('0' + (random() % 10));
        const int e10 = static_cast<int>(random() % 700) - 350 - static_cast<int>(length / 2);
        const double want = c_double(digits, e10);
        const double got = atlas::pymath::decimal_to_double(digits, e10);
        if (std::bit_cast<std::uint64_t>(want) != std::bit_cast<std::uint64_t>(got)) {
            FAIL_CHECK(digits, "e", e10, ": ", got, " for ", want);
            break;
        }
        ++checked;
    }
    // the edges: halfway between doubles, either way of it; the subnormals and their bottom; past the largest
    const std::vector<std::pair<std::string, int>> edges{
        {"9007199254740993", 0},             // 2^53 + 1: halfway, to even (down)
        {"9007199254740995", 0},             // halfway, to even (up)
        {"90071992547409930000000001", -10}, // just past halfway
        {"5", -324},                         // the least subnormal (4.9e-324)
        {"24703282292062327", -340},         // just under half the least subnormal: 0
        {"24703282292062328", -340},         // just over: the least subnormal
        {"22250738585072011", -324},         // around the least normal
        {"17976931348623157", 292},          // the largest double
        {"17976931348623159", 292},          // past it: infinity
        {"1", 400},
        {"1", -400},
        {"0", 0},
        {"000000", 50},
        {"52374012", -6},
        {"4884", -3},
    };
    for (const auto& [digits, e10] : edges) {
        CHECK_MESSAGE(std::bit_cast<std::uint64_t>(atlas::pymath::decimal_to_double(digits, e10)) ==
                          std::bit_cast<std::uint64_t>(c_double(digits, e10)),
                      digits, "e", e10);
    }
    // 800 digits and more: the ones past 780 decide only whether the value lies above the kept ones
    std::string longer = "1";
    for (int k = 0; k < 820; ++k) longer += static_cast<char>('0' + (k * 7 % 10));
    for (const int e10 : {-1000, -821, -500, -400}) {
        CHECK(std::bit_cast<std::uint64_t>(atlas::pymath::decimal_to_double(longer, e10)) ==
              std::bit_cast<std::uint64_t>(c_double(longer, e10)));
    }
    MESSAGE(checked, " random decimals equal to strtod's");
}

TEST_CASE("osm: Overpass's JSON is read as Python's json reads it") {
    const std::string doc = R"({
  "version": 0.6, "generator": "Overpass API 0.7.62", "osm3s": {"timestamp_osm_base": "2026-10-09T15:00:00Z"},
  "elements": [
    {"type": "node", "id": 42, "lat": 52.3740123, "lon": 4.8840000, "tags": {"natural": "tree", "height": "12"}},
    {"type": "node", "id": 43, "lat": 52, "lon": -0, "timestamp": "x", "version": 3},
    {"type": "node", "id": 44, "lat": -0.0, "lon": 1e-7, "tags": {}},
    {"type": "way", "id": 900000000001, "bounds": {"minlat": 52.0, "minlon": 4.0, "maxlat": 53.0, "maxlon": 5.0},
     "nodes": [42, 43, 44, 42],
     "geometry": [{"lat": 52.3740123, "lon": 4.884}, {"lat": 52.0, "lon": 0}, {"lat": -0.0, "lon": 1e-7},
                  {"lat": 52.3740123, "lon": 4.884}],
     "tags": {"building": "yes", "name": "Café \"De Kat\"\\n", "note": "😀 \/ \t"}},
    {"type": "relation", "id": 7, "members": [
        {"type": "way", "ref": 900000000001, "role": "outer",
         "geometry": [{"lat": 1.5, "lon": 2.5}, {"lat": 1.5, "lon": 2.75}, {"lat": 1.75, "lon": 2.5}]},
        {"type": "way", "ref": 5, "role": "inner"},
        {"type": "node", "ref": 42, "role": "label", "lat": 52.3740123, "lon": 4.884}],
     "tags": {"type": "multipolygon", "natural": "water"}},
    {"type": "area", "id": 3600000007, "tags": null},
    {"type": "node", "id": -9223372036854775808, "lat": 1E+2, "lon": 25e-1}
  ],
  "remark": null
})";
    const auto r = read(doc);
    ATLAS_REQUIRE(r.has_value(), osm::name_of(r.error().fault), " at ", r.error().at);
    const std::span<const osm::Element> e = r->elements();
    REQUIRE(e.size() == 7);
    CHECK(e[0].kind == osm::Kind::node);
    CHECK(e[0].id == 42);
    CHECK(e[0].at.lat == 52.3740123);
    CHECK(e[0].at.lon == 4.884);
    REQUIRE(e[0].tags.size() == 2);
    CHECK(e[0].tags[0].key == "natural"); // in the document's order, as a dict keeps them
    CHECK(e[0].tags[1].value == "12");
    // an integer coordinate is an int made a float: no -0; a decimal keeps its sign
    CHECK(e[1].at.lat == 52.0);
    CHECK(!std::signbit(e[1].at.lon));
    CHECK(!e[1].has_tags);
    CHECK(std::signbit(e[2].at.lat));
    CHECK(e[2].at.lon == 1e-7);
    CHECK(e[2].has_tags); // `e.get('tags')` is {}: falsy, and present
    CHECK(e[2].tags.empty());
    const osm::Element& way = e[3];
    CHECK(way.kind == osm::Kind::way);
    CHECK(way.id == 900000000001);
    CHECK(std::ranges::equal(way.nodes, std::vector<std::int64_t>{42, 43, 44, 42}));
    REQUIRE(way.geometry.size() == 4);
    CHECK(std::signbit(way.geometry[2].lat));
    CHECK(way.geometry[3].lon == 4.884);
    REQUIRE(way.tags.size() == 3);
    CHECK(way.tags[1].value == "Caf\xC3\xA9 \"De Kat\"\\n");
    CHECK(way.tags[2].value == "\xF0\x9F\x98\x80 / \t");
    const osm::Element& rel = e[4];
    CHECK(rel.kind == osm::Kind::relation);
    REQUIRE(rel.members.size() == 3);
    CHECK(rel.members[0].role == "outer");
    CHECK(rel.members[0].has_geometry);
    CHECK(rel.members[0].geometry.size() == 3);
    CHECK(rel.members[0].geometry[1].lon == 2.75);
    CHECK(!rel.members[1].has_geometry);
    CHECK(rel.members[2].kind == osm::Kind::node);
    CHECK(rel.members[2].geometry.empty());
    CHECK(e[5].kind == osm::Kind::other);
    CHECK(e[5].type == "area");
    CHECK(!e[5].has_tags); // "tags": null is None
    CHECK(e[6].id == std::numeric_limits<std::int64_t>::min());
    CHECK(e[6].at.lat == 100.0);
    CHECK(e[6].at.lon == 2.5);
}

TEST_CASE("osm: what Overpass never writes and the port could not read alike is refused, named") {
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "tags": {"a": "1", "a": "2"}}]})") == Fault::duplicate);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "id": 2}]})") == Fault::duplicate);
    CHECK(fault_of(R"({"elements": [], "elements": []})") == Fault::duplicate);
    CHECK(fault_of(R"({"elements": [{"type": "way", "id": 1, "geometry": [{"lat": 1, "lon": 2}, null]}]})") ==
          Fault::unsupported);
    CHECK(fault_of(R"({"elements": [{"type": "way", "id": 1, "geometry": null}]})") == Fault::unsupported);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "tags": {"a": "\ud800"}}]})") == Fault::bad_utf8);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "tags": {"a": "\udc00x"}}]})") == Fault::bad_utf8);
    CHECK(fault_of("{\"elements\": [{\"type\": \"node\", \"id\": 1, \"tags\": {\"a\": \"\xC0\xAF\"}}]}") ==
          Fault::bad_utf8);
    CHECK(fault_of("{\"elements\": [{\"type\": \"node\", \"id\": 1, \"tags\": {\"a\": \"x\ty\"}}]}") == Fault::syntax);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "tags": {"a": 1}}]})") == Fault::bad_type);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1.5}]})") == Fault::bad_number);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 9223372036854775808}]})") == Fault::bad_number);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 01}]})") == Fault::syntax);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "lat": NaN}]})") == Fault::syntax);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "lat": .5}]})") == Fault::syntax);
    CHECK(fault_of(R"({"elements": [{"id": 1}]})") == Fault::bad_type);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1}]} x)") == Fault::syntax);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1, "x": "\u12"}]})") == Fault::syntax);
    CHECK(fault_of(R"({"elements": [{"type": "node", "id": 1)") == Fault::truncated);
    CHECK(fault_of(R"({"remark": "runtime error"})") == Fault::no_elements);
    std::string deep = R"({"elements": [], "x": )";
    for (std::size_t i = 0; i <= osm::max_depth + 1; ++i) deep += "[";
    CHECK(fault_of(deep) == Fault::too_deep);
    const std::string_view no_number = R"({"elements": [{"type": "node", "id": 1, "lat": ]})";
    const auto at = read(no_number);
    REQUIRE(!at.has_value());
    CHECK(at.error().fault == Fault::syntax);
    CHECK(at.error().at == no_number.find(']')); // the byte the fault was found at
}

TEST_CASE("osm: the renderer's regions' raw.json read whole (ATLAS_REGIONS_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: the renderer's data are not read here");
        return;
    }
    for (const char* region : {"grachten", "nottinghill"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / region / "raw.json";
        std::ifstream in(path, std::ios::binary);
        if (!in) {
            MESSAGE(path.string(), " is not on this machine");
            continue;
        }
        const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        const auto t0 = std::chrono::steady_clock::now();
        const auto r = read(text);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        ATLAS_REQUIRE(r.has_value(), region, ": ", osm::name_of(r.error().fault), " at ", r.error().at);
        std::size_t nodes = 0;
        std::size_t ways = 0;
        std::size_t relations = 0;
        std::size_t points = 0;
        for (const osm::Element& e : r->elements()) {
            nodes += e.kind == osm::Kind::node ? 1 : 0;
            ways += e.kind == osm::Kind::way ? 1 : 0;
            relations += e.kind == osm::Kind::relation ? 1 : 0;
            points += e.geometry.size();
            if (e.kind == osm::Kind::way) CHECK(e.has_geometry); // out geom
        }
        MESSAGE(std::string(region), ": ", text.size(), " bytes, ", nodes, " nodes, ", ways, " ways, ", relations,
                " relations, ", points, " way points, read in ", ms, " ms");
        CHECK(ways > 0);
    }
}
