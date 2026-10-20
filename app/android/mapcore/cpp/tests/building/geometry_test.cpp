// Stage T of the buildings (geometry/Walls.hpp, Roofs.hpp, Houseboats.hpp): a synthetic tile with every roof shape and
// a houseboat (the tests' own writer, tests/tiles/support.hpp), and every tile of the bundled packs (ATLAS_TILES_DIR)
// at the levels their views and bands load at. Each primitive's sun sample is the one K8's sun/Samples.cpp computes for
// the same face (kind, record, edge and place, so the frame reads the bit the port's renderer asks for), the buildings'
// samples end where K8's trees' begin, every item's slots hold its primitives in each variant, and the primitives lie
// in their cells' ranges. MT/buildings/StageTParityTest.kt holds the corners to the port's own face() calls.
#include "base/arena.hpp"
#include "geometry/Houseboats.hpp"
#include "geometry/Walls.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tests/building/fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Payload.hpp"
#include "tiles/layers/Buildings.hpp"
#include "twin/building/BuildingTile.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cmath>
#include <format>
#include <map>
#include <string>
#include <vector>

namespace {

namespace tl = atlas::tiles;
namespace gm = atlas::geometry;
using atlas::test::TileBytes;
using atlas::test::building::s0;
using atlas::test::building::w0;

/// K8's synthetic tile (tests/building/fixture.hpp) and the frame it is read in.
struct Fixture {
    TileBytes t = atlas::test::building::tile();
    tl::RenderHead head;

    Fixture() { head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8}; }
};

/// The kind of sun sample a primitive reads: a cell drawn as the wall's one cell reads the wall's.
[[nodiscard]] atlas::sun::Kind sample_kind(const gm::Prim& p) {
    using K = atlas::sun::Kind;
    switch (p.kind) {
    case gm::PrimKind::cell: return (p.flags & gm::prim_flag::far) != 0 ? K::wall : K::cell;
    case gm::PrimKind::skillion_wall: return K::skillion_wall;
    case gm::PrimKind::hull_wall: return K::hull;
    case gm::PrimKind::cabin_wall: return K::cabin;
    case gm::PrimKind::top: return K::top;
    case gm::PrimKind::slope: return K::slope;
    case gm::PrimKind::gable: return K::gable;
    case gm::PrimKind::hip: return K::hip;
    case gm::PrimKind::pyramid: return K::pyramid;
    case gm::PrimKind::skillion: return K::skillion;
    case gm::PrimKind::mansard: return K::mansard;
    case gm::PrimKind::mansard_top: return K::mansard_top;
    case gm::PrimKind::deck:
    case gm::PrimKind::cabin_roof: break;
    }
    return K::crown; // no sample: never compared
}

/// What one tile's check found.
struct Checked {
    std::size_t prims = 0;
    std::size_t sampled = 0;
    std::size_t wrong = 0;
    std::string first_wrong;
};

/// Holds every primitive's sample to K8's, the buildings' sample count to where K8's trees begin, every primitive's
/// slot to its item's count, and the primitives to their cells' ranges.
Checked check_tile(const gm::TileBuildings& g, const atlas::sun::SunTile& st) {
    Checked c;
    const atlas::sun::TileSamples& s = st.samples;
    CHECK(g.samples == s.crowns_first);
    const auto check = [&](const gm::Prim& p) {
        ++c.prims;
        const gm::BuildingItem& item = g.items[p.item];
        CHECK((p.flags & (gm::prim_flag::near | gm::prim_flag::far)) != 0);
        if ((p.flags & gm::prim_flag::weather) != 0) {
            CHECK(p.near_slot < item.boat_slots);
        } else {
            if ((p.flags & gm::prim_flag::near) != 0) CHECK(p.near_slot < item.near_slots);
            if ((p.flags & gm::prim_flag::far) != 0) CHECK(p.far_slot < item.far_slots);
        }
        if (p.sample == gm::no_sample) return;
        ++c.sampled;
        const bool same = p.sample < s.size() && s.kind[p.sample] == sample_kind(p) &&
                          s.source[p.sample] == static_cast<atlas::sun::Source>(item.source) &&
                          s.owner[p.sample] == item.record && s.edge[p.sample] == p.edge && s.part[p.sample] == p.part;
        if (!same) {
            if (c.wrong == 0) {
                c.first_wrong = std::string(gm::name_of(p.kind)) + " of record " + std::to_string(item.record) +
                                " edge " + std::to_string(p.edge) + " part " + std::to_string(p.part) + " sample " +
                                std::to_string(p.sample);
            }
            ++c.wrong;
        }
    };
    for (const gm::Prim& p : g.edged) check(p);
    for (const gm::Prim& p : g.plain) check(p);
    // cell c's primitives are [first[c], first[c + 1])
    const std::size_t cells = std::size_t{g.cells_per_side} * g.cells_per_side;
    REQUIRE(g.edged_first.size() == cells + 1);
    REQUIRE(g.plain_first.size() == cells + 1);
    CHECK(g.edged_first[cells] == g.edged.size());
    CHECK(g.plain_first[cells] == g.plain.size());
    for (std::size_t k = 0; k < cells; ++k) {
        for (std::uint32_t i = g.edged_first[k]; i < g.edged_first[k + 1]; ++i) CHECK(g.edged[i].cell == k);
        for (std::uint32_t i = g.plain_first[k]; i < g.plain_first[k + 1]; ++i) CHECK(g.plain[i].cell == k);
    }
    // an n-gon's triangles are its polygon's
    for (std::size_t k = 0; k < g.polygons.size(); ++k) {
        const gm::Polygon& poly = g.polygons[k];
        for (std::uint32_t i = poly.first; i < poly.first + poly.count; ++i) {
            REQUIRE(i < g.plain.size());
            CHECK(g.plain[i].polygon == k);
        }
    }
    return c;
}

} // namespace

TEST_CASE("buildings: stage T of a synthetic tile, every roof shape and a houseboat, held to K8's samples") {
    Fixture f;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "buildings test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw = f.t.encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const gm::TileBuildings g = gm::build_buildings({.tile = &*tile, .head = &f.head, .level = 0, .kind = 7});
    const atlas::sun::SunTile st = atlas::sun::make_tile(*tile, f.head, atlas::sun::Region{}, 0, {});
    const Checked c = check_tile(g, st);
    CHECK_MESSAGE(c.wrong == 0, c.first_wrong);
    std::map<gm::PrimKind, int> kinds;
    for (const gm::Prim& p : g.edged) ++kinds[p.kind];
    for (const gm::Prim& p : g.plain) {
        if ((p.flags & gm::prim_flag::polygon) == 0) ++kinds[p.kind];
    }
    for (const gm::Polygon& p : g.polygons) ++kinds[g.plain[p.first].kind];
    for (const auto& [k, n] : kinds) MESSAGE(std::string(gm::name_of(k)), " ", n);
    // 7 buildings and the part, each an item of the given kind with its sort point
    REQUIRE(g.items.size() == 8);
    CHECK(g.items[7].source == gm::Source::part);
    CHECK(g.items[7].spec.y == (12.0 + 30.0) / 2);
    CHECK(g.items[7].spec.offset == -(0.01 * 12.0));
    CHECK(g.items[0].spec.kind == 7);
    CHECK(g.items[0].spec.y == 12.0 / 2);
    // the shapes
    CHECK(kinds[gm::PrimKind::pyramid] == 4);
    CHECK(kinds[gm::PrimKind::slope] == 4); // two on the gabled roof, two on the hipped
    CHECK(kinds[gm::PrimKind::gable] == 2);
    CHECK(kinds[gm::PrimKind::hip] == 2);
    CHECK(kinds[gm::PrimKind::skillion] == 1);
    CHECK(kinds[gm::PrimKind::mansard] == 4);
    CHECK(kinds[gm::PrimKind::mansard_top] == 1);
    CHECK(kinds[gm::PrimKind::hull_wall] == 4);
    CHECK(kinds[gm::PrimKind::cabin_wall] == 4);
    CHECK(kinds[gm::PrimKind::deck] == 1);
    CHECK(kinds[gm::PrimKind::cabin_roof] == 1);
    CHECK(kinds[gm::PrimKind::skillion_wall] >= 1);
    // the flat house (12 m, 10 × 20 m): its first wall's one cell, corner for corner the port's quadAt
    const tl::Frame& fr = f.head.frame;
    const gm::V2 a{fr.x(w0), fr.z(s0)};
    const gm::V2 b{fr.x(w0 + 1470), fr.z(s0)};
    const std::array<gm::V3, 4> q = gm::quad_at(a, b, 0.0, 12.0, 0.0, 1.0);
    bool found = false;
    for (const gm::Prim& p : g.edged) {
        const gm::BuildingItem& item = g.items[p.item];
        if (item.source == gm::Source::building && item.record == 0 && p.edge == 0 &&
            (p.flags & gm::prim_flag::far) != 0) {
            CHECK(p.p == q);
            CHECK(g.faces[p.face].base == 0x8E5A46U);
            found = true;
        }
    }
    CHECK(found);
    // the houseboat's boat (with weather) and its building (without) never share a variant
    for (const gm::Prim& p : g.edged) {
        if (g.items[p.item].source != gm::Source::building || g.items[p.item].record != 6) continue;
        const bool boat = p.kind == gm::PrimKind::hull_wall || p.kind == gm::PrimKind::cabin_wall;
        CHECK(((p.flags & gm::prim_flag::weather) != 0) == boat);
        CHECK(((p.flags & gm::prim_flag::dry) != 0) == !boat);
    }
    // a hull's wall: from 0.15 m under the waterline to the deck, 0.7 m above it
    for (const gm::Prim& p : g.edged) {
        if (p.kind != gm::PrimKind::hull_wall) continue;
        CHECK(p.p[0].y == -1.5 - 0.15);
        CHECK(p.p[2].y == -1.5 + 0.7);
        CHECK(g.faces[p.face].base == gm::hull_rgb);
    }
}

TEST_CASE("buildings: stage T of every bundled tile, at the levels its views load at, held to K8's samples") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not checked here");
        return;
    }
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack(dir + "/" + name + ".atlas");
        ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
        const tl::RenderHead* head = pack->head();
        ATLAS_REQUIRE(head != nullptr);
        const atlas::sun::Region region =
            pack->style() != nullptr ? atlas::sun::region_of(*pack->style()) : atlas::sun::Region{};
        // the levels the pack's views and bands load at, and the unsimplified one
        std::vector<std::uint8_t> levels{0};
        for (const tl::HeadView& v : head->views()) levels.push_back(v.tolerance);
        for (const tl::HeadBand& b : head->bands()) levels.push_back(b.tolerance);
        std::ranges::sort(levels);
        levels.erase(std::ranges::unique(levels).begin(), levels.end());
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        atlas::Arena arena(4U << 20U, atlas::Growth::grows, "buildings test");
        std::size_t prims = 0;
        std::size_t sampled = 0;
        std::size_t most_bytes = 0;
        std::size_t all_bytes = 0;
        std::string densest;
        std::map<int, std::size_t> level_bytes; // the densest tile's bytes at each level
        std::size_t wrong = 0;
        std::size_t tiles = 0;
        double ms = 0;
        for (const std::uint8_t level : levels) {
            for (const tl::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
                arena.reset();
                auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
                ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
                const auto t0 = std::chrono::steady_clock::now();
                const gm::TileBuildings g = gm::build_buildings(
                    {.tile = &*tile, .head = head, .level = level, .region = {.water_y = region.water_y}, .kind = 0});
                ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                const atlas::sun::SunTile st = atlas::sun::make_tile(*tile, *head, region, level, {});
                const Checked c = check_tile(g, st);
                // the tile's range of the walls heap: its primitives, near cells, faces, colours and rows
                atlas::passes::BuildingTile layout;
                const std::size_t bytes = atlas::passes::heap_words(g, 0.0, 0.0, layout).size() * 16;
                if (bytes > most_bytes) {
                    most_bytes = bytes;
                    densest = std::format("{}/{}/{} at level {} ({} buildings, {} edged primitives, {} near cells, {} "
                                          "plain, {} faces)",
                                          chunk.tile.z, chunk.tile.x, chunk.tile.y, int{level}, g.items.size(),
                                          layout.edged, layout.cells, layout.plain, layout.faces);
                }
                all_bytes += bytes;
                level_bytes[level] = std::max(level_bytes[level], bytes);
                CHECK_MESSAGE(c.wrong == 0, name, " level ", int{level}, ": ", c.first_wrong);
                prims += c.prims;
                sampled += c.sampled;
                wrong += c.wrong;
                ++tiles;
            }
        }
        MESSAGE(std::string(name), ": ", tiles, " tiles over ", levels.size(), " levels, ", prims, " primitives, ",
                sampled, " with a sun sample, ", wrong, " not K8's; stage T ", ms / static_cast<double>(tiles),
                " ms a tile");
        MESSAGE(std::string(name), ": a tile's range of the walls heap ",
                static_cast<double>(all_bytes) / static_cast<double>(tiles) / (1 << 20), " MB on average, the densest ",
                static_cast<double>(most_bytes) / (1 << 20), " MB: ", densest);
        std::string per_level;
        for (const auto& [lv, b] : level_bytes)
            per_level += std::format(" {}: {:.2f}", lv, static_cast<double>(b) / (1 << 20));
        MESSAGE(std::string(name), ": the densest tile's MB by level:", per_level);
        // the heaps' share of the buildings (D2 put a dense tile at about 1.5-2.5 MB): a tile 2 MB at most on average,
        // and the canal ring's centre (14/8414/5384, some 6,800 buildings) within 13 MB at its finest level, 9 MB at
        // the Main board's (level 7), which the frame's test holds within the heaps' budget (scene/Heaps.hpp)
        CHECK(all_bytes <= tiles * (std::size_t{2} << 20U));
        CHECK(most_bytes <= std::size_t{13} << 20U);
        CHECK(wrong == 0);
        CHECK(prims > 0);
    }
}

TEST_CASE("buildings: a draw list's runs join its own cells' primitives, never another list's") {
    using atlas::passes::BuildingRun;
    // a tile's lists over four cells: the edged list's cells hold [0, 10), [10, 10), [10, 30), [30, 31); the plain's
    // [0, 5), [5, 9), [9, 9), [9, 20)
    const std::vector<std::uint32_t> edged{0, 10, 10, 30, 31};
    const std::vector<std::uint32_t> plain{0, 5, 9, 9, 20};
    std::vector<BuildingRun> runs;
    atlas::passes::building_runs(0b1101, edged, runs); // cells 0, 2, 3: one run, the gap joined
    REQUIRE(runs.size() == 1);
    CHECK(runs[0].first == 0);
    CHECK(runs[0].end == 31);
    // the plain list's runs, appended after the edged list's: its own, though they lie within the edged run's numbers
    atlas::passes::building_runs(0b1001, plain, runs); // cells 0 and 3
    REQUIRE(runs.size() == 2);
    CHECK(runs[1].first == 0);
    CHECK(runs[1].end == 20);
    // and a second tile's after both
    atlas::passes::building_runs(0b0010, plain, runs); // cell 1
    REQUIRE(runs.size() == 3);
    CHECK(runs[2].first == 5);
    CHECK(runs[2].end == 9);
    // no cell visible, or only empty cells: no run
    atlas::passes::building_runs(0, edged, runs);
    atlas::passes::building_runs(0b0010, edged, runs);
    CHECK(runs.size() == 3);
}
