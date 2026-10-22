// Fuzzes the living map's load (load/Amsterdam.hpp) with arbitrary documents: whatever the Overpass reader accepts, the
// load must load or refuse without failing an invariant or reading out of bounds, and every scene it makes must hold
// together: each outline has at least three points and a box around them, and each index (a place's building, a
// building's places, a tree's node, a place among the nodes) points into its list.
// Seeds: fuzz/corpus/fuzz_load (the unit test's document, with a record of each kind, and the reader's seed).
#include "base/assert.hpp"
#include "load/Amsterdam.hpp"
#include "osm/Overpass.hpp"

#include <cstddef>
#include <cstdint>
#include <span>

namespace {

void check_building(const atlas::load::Building& b, std::size_t nodes) {
    ATLAS_ASSERT(b.poly.size() >= 3, "an outline of fewer than three points");
    for (const atlas::load::V2& p : b.poly) {
        ATLAS_ASSERT(b.bb.x0 <= p.x && p.x <= b.bb.x1 && b.bb.z0 <= p.z && p.z <= b.bb.z1, "a point outside its box");
    }
    for (const std::uint32_t i : b.places) ATLAS_ASSERT(i < nodes, "a place past the nodes");
    ATLAS_ASSERT(b.guessed.keys().size() <= 8, "more guesses than keys");
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto doc = atlas::osm::read_overpass(bytes);
    if (!doc) return 0;
    const auto scene =
        atlas::load::load_amsterdam(*doc, atlas::load::westerkerk_frame(), atlas::load::amsterdam_palette());
    if (!scene) return 0;
    const std::size_t nodes = scene->nodes.size();
    for (const atlas::load::Building& b : scene->buildings) check_building(b, nodes);
    for (const atlas::load::Building& b : scene->parts) {
        check_building(b, nodes);
        ATLAS_ASSERT(b.places.empty() && !b.hidden, "a part with places or hidden");
    }
    for (const atlas::load::Node& n : scene->nodes) {
        ATLAS_ASSERT(!n.building || *n.building < scene->buildings.size(), "a place in a building past the list");
        ATLAS_ASSERT(!n.building || n.style.has_value(), "a building's node without a place style");
    }
    for (const atlas::load::Tree& t : scene->trees) ATLAS_ASSERT(t.node < nodes, "a tree past the nodes");
    for (const std::uint32_t p : scene->pois) ATLAS_ASSERT(p < nodes, "a place past the nodes");
    return 0;
}
