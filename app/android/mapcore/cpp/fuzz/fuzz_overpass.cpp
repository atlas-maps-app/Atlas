// Fuzzes the Overpass reader (osm/Overpass.hpp) with arbitrary document bytes: it must never read past its input or
// fail an invariant, a refusal must name a byte inside the input, and every document it accepts must hold together:
// each element's views lie in its arrays, what it says it lacks is empty, every string is UTF-8, and a node's
// coordinates are numbers as float() makes them (never NaN: JSON has none).
// Seeds: fuzz/corpus/fuzz_overpass (a document with each kind of element and value, as the unit test reads).
#include "base/assert.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Bytes.hpp"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace {

void check_text(std::string_view s) {
    ATLAS_ASSERT(atlas::tiles::valid_utf8(std::as_bytes(std::span(s.data(), s.size()))), "a string that is not UTF-8");
}

void check_points(std::span<const atlas::osm::LatLon> points) {
    for (const atlas::osm::LatLon& p : points) {
        ATLAS_ASSERT(!std::isnan(p.lat) && !std::isnan(p.lon), "a coordinate JSON cannot write");
    }
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto doc = atlas::osm::read_overpass(bytes);
    if (!doc) {
        ATLAS_ASSERT(doc.error().at <= size, "a refusal past the input");
        return 0;
    }
    for (const atlas::osm::Element& e : doc->elements()) {
        check_text(doc->text(e.type));
        ATLAS_ASSERT(e.has_tags || doc->tags(e).empty(), "tags it says it lacks");
        ATLAS_ASSERT(e.has_nodes || doc->nodes(e).empty(), "nodes it says it lacks");
        ATLAS_ASSERT(e.has_geometry || doc->geometry(e).empty(), "a geometry it says it lacks");
        ATLAS_ASSERT(e.has_members || doc->members(e).empty(), "members it says it lacks");
        ATLAS_ASSERT(e.from < e.to && e.to <= size, "an element's bytes past the input");
        if (e.has_at) check_points(std::span(&e.at, 1));
        for (const atlas::osm::Tag t : doc->tags(e)) {
            check_text(t.key);
            check_text(t.value);
            ATLAS_ASSERT(doc->tags(e).find(t.key).has_value(), "a tag its list does not find");
        }
        check_points(doc->geometry(e));
        for (const atlas::osm::Member& m : doc->members(e)) {
            check_text(doc->text(m.role));
            ATLAS_ASSERT(m.has_role || doc->text(m.role).empty(), "a role it says it lacks");
            ATLAS_ASSERT(m.has_geometry || doc->geometry(m).empty(), "a member's geometry it says it lacks");
            check_points(doc->geometry(m));
        }
    }
    return 0;
}
