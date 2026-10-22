// Fuzzes the reader of Overpass's diffs and the merge (osm/Diff.hpp) with arbitrary XML bytes: neither may read past
// its input or fail an invariant, a refusal must name a byte inside the input, and every diff it accepts must hold
// together: its date is a date, its changes come in type and id order once each, a gone element has no new form and
// every other one has. Each accepted diff is merged into a small kept answer, and every merge must give a document the
// Overpass reader reads (each new form is JSON as Overpass prints it), holding the kept data's date as its whole date,
// the diff's as its own, and the elements in type and id order.
// Seeds: fuzz/corpus/fuzz_diff (a diff with each action and element, as the unit test reads).
#include "base/assert.hpp"
#include "osm/Diff.hpp"
#include "osm/Overpass.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <utility>

namespace {

constexpr std::string_view kept = R"({
  "version": 0.6,
  "osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"},
  "elements": [
{"type": "node", "id": 1, "lat": 52.3745000, "lon": 4.8838000, "tags": {"amenity": "bench"}},
{"type": "node", "id": 4, "lat": 52.3746000, "lon": 4.8839000, "tags": {"natural": "tree"}},
{"type": "way", "id": 5, "nodes": [1, 4], "geometry": [{"lat": 52.3745000, "lon": 4.8838000},
 {"lat": 52.3746000, "lon": 4.8839000}], "tags": {"highway": "footway"}},
{"type": "relation", "id": 9, "members": [{"type": "way", "ref": 5, "role": "outer", "geometry": [
 {"lat": 52.3745000, "lon": 4.8838000}, {"lat": 52.3746000, "lon": 4.8839000}]}], "tags": {"type": "multipolygon"}}
  ]
})";

[[nodiscard]] std::pair<int, std::int64_t> key_of(atlas::osm::Kind kind, std::int64_t id) {
    return {static_cast<int>(kind), id};
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto diff = atlas::osm::read_diff(bytes);
    if (!diff) {
        ATLAS_ASSERT(diff.error().at <= size, "a refusal past the input");
        return 0;
    }
    ATLAS_ASSERT(atlas::osm::is_osm_date(diff->osm_base), "a diff without its date");
    for (std::size_t i = 0; i < diff->changes.size(); ++i) {
        const atlas::osm::Change& c = diff->changes[i];
        ATLAS_ASSERT(c.kind != atlas::osm::Kind::other, "a change of another type");
        ATLAS_ASSERT(c.gone == c.json.empty(), "a gone element with a new form, or another without one");
        if (i > 0) {
            const atlas::osm::Change& b = diff->changes[i - 1];
            ATLAS_ASSERT(key_of(b.kind, b.id) < key_of(c.kind, c.id), "changes out of order, or twice");
        }
    }
    const auto merged = atlas::osm::merge_diff(std::as_bytes(std::span(kept.data(), kept.size())), *diff);
    ATLAS_ASSERT(merged.has_value(), "a diff read that does not merge into kept data in order");
    const std::string& json = merged->json;
    const auto doc = atlas::osm::read_overpass(std::as_bytes(std::span(json.data(), json.size())));
    ATLAS_ASSERT(doc.has_value(), "a merge the Overpass reader does not read");
    ATLAS_ASSERT(doc->osm_base() == diff->osm_base, "a merge without the diff's date");
    ATLAS_ASSERT(doc->whole() == "2026-10-09T08:46:19Z", "a merge without the kept data's whole date");
    const auto elements = doc->elements();
    for (std::size_t i = 1; i < elements.size(); ++i) {
        ATLAS_ASSERT(key_of(elements[i - 1].kind, elements[i - 1].id) < key_of(elements[i].kind, elements[i].id),
                     "merged elements out of order, or twice");
    }
    return 0;
}
