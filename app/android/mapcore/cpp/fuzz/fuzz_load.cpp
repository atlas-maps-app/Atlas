// Fuzzes the living map's load (load/Amsterdam.hpp) and a region's (load/Regions.hpp, whole or simplified) with
// arbitrary documents: whatever the Overpass reader accepts, a load must load or refuse without failing an invariant or
// reading out of bounds, and every scene it makes must hold together (a region's: a shore flag per water ring): each
// outline has at least three points and a box around them, and each index (a place's building, a building's places, a
// tree's node, a place among the nodes) points into its list. The region takes, by the input's size, a tradition with
// every rule and detail generic.py reads (load/Generic.hpp's looks), or none, is named Paris (its generic looks' stone
// and mansards) or not, and is a theme park (its coaster tracks, load/Coasters.hpp, with no ride's profile; its woods
// are not planted here, a wood's lattice is the size of its outline); the living map Amsterdam's New looks or none
// (fuzz/scenes.hpp, which fuzz_stage_t builds the same scenes by).
// Each scene is cut into its render tiles too (load/Cut.hpp), and the region's into a window, which must cut or refuse.
// No tag's value may end the process. Seeds: fuzz/corpus/fuzz_load (the unit test's document, with a record of each
// kind, and the reader's seed), and every input that crashed it.
#include "base/assert.hpp"
#include "fuzz/scenes.hpp"
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/Regions.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Grid.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace {

void check_building(const atlas::load::Building& b, std::size_t nodes) {
    ATLAS_ASSERT(b.poly.size() >= 3, "an outline of fewer than three points");
    for (const atlas::load::V2& p : b.poly) {
        ATLAS_ASSERT(b.bb.x0 <= p.x && p.x <= b.bb.x1 && b.bb.z0 <= p.z && p.z <= b.bb.z1, "a point outside its box");
    }
    for (const std::uint32_t i : b.places) ATLAS_ASSERT(i < nodes, "a place past the nodes");
    ATLAS_ASSERT(b.guessed.keys().size() <= 10, "more guesses than keys");
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto doc = atlas::osm::read_overpass(bytes);
    if (!doc) return 0;
    // the living map's load, and a region's (in a box around the Westerkerk) on the same document
    const atlas::load::RegionConfig region = atlas::fuzz::region_of(size);
    const auto regional = atlas::load::load_region(*doc, region, atlas::fuzz::simplified_of(size));
    if (regional) {
        ATLAS_ASSERT(regional->natural_shore.size() == regional->water.size(), "a shore flag per water ring");
        for (const atlas::load::CoasterTrack& t : regional->coasters) {
            ATLAS_ASSERT(!t.pts.empty() && t.ys.size() == t.pts.size(), "a height at each of a track's points");
        }
        for (const atlas::load::Building& b : regional->buildings) check_building(b, regional->nodes.size());
        // the region's cut: its tiles at its two levels, or a refusal (a vertex off the grid, a tradition's details)
        if (const auto tiles = atlas::load::cut_region(*regional, region.frame, {})) {
            for (const atlas::tiles::TileFill& t : *tiles)
                ATLAS_ASSERT(t.level() == 14 || t.level() == 12, "a tile's level");
        }
        // a window's cut (the Westerkerk's L14 tile): that tile alone, or none, and what the tiles cannot carry yet
        // (a tradition's details) left out, never refused
        const atlas::tiles::TileKey window = atlas::fuzz::window();
        if (const auto tiles = atlas::load::cut_window(*regional, region.frame, {}, window)) {
            ATLAS_ASSERT(tiles->size() <= 1, "a window's tiles");
            for (const atlas::tiles::TileFill& t : *tiles) {
                ATLAS_ASSERT(atlas::tiles::TileKey(t.level(), t.x(), t.y()).packed() == window.packed(),
                             "a window's tile");
            }
        } else {
            ATLAS_ASSERT(!tiles.error().what.contains("cannot carry") && !tiles.error().what.contains("no column"),
                         "a window refused for what it leaves out");
        }
    }
    const auto scene = atlas::load::load_amsterdam(*doc, atlas::load::westerkerk_frame(),
                                                   atlas::load::amsterdam_palette(), atlas::fuzz::looks_of(size));
    if (!scene) return 0;
    if (const auto tiles = atlas::load::cut_living(*scene, atlas::load::westerkerk_frame())) {
        for (const atlas::tiles::TileFill& t : *tiles) ATLAS_ASSERT(t.level() == 14, "a living tile's level");
    }
    const std::size_t nodes = scene->nodes.size();
    for (const atlas::load::Building& b : scene->buildings) check_building(b, nodes);
    for (const atlas::load::Building& b : scene->parts) {
        check_building(b, nodes);
        ATLAS_ASSERT(b.places.empty() && (!b.hidden || b.underground),
                     "a part with places, or hidden above the ground");
    }
    for (const atlas::load::Node& n : scene->nodes) {
        ATLAS_ASSERT(!n.building || *n.building < scene->buildings.size(), "a place in a building past the list");
        ATLAS_ASSERT(!n.building || n.style.has_value(), "a building's node without a place style");
        ATLAS_ASSERT(!n.style || *n.style < scene->styles.size(), "a place's style past the scene's styles");
    }
    for (const atlas::load::Tree& t : scene->trees) ATLAS_ASSERT(t.node < nodes, "a tree past the nodes");
    for (const std::uint32_t p : scene->pois) ATLAS_ASSERT(p < nodes, "a place past the nodes");
    return 0;
}
