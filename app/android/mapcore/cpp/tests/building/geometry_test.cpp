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

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <format>
#include <map>
#include <optional>
#include <span>
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
    // the windows: the tile's window group of K8's samples, wall by wall, window by window (its record, edge and place
    // on the wall), each windowed wall naming its wall's one-cell primitive, its slots within its item's; a houseboat
    // cabin's wall its row of panes, without samples, at the boat's slot after its wall's
    CHECK(g.windows == s.size() - s.windows_first);
    std::uint32_t windows = 0;
    for (const gm::WindowWall& w : g.window_walls) {
        REQUIRE(w.wall < g.edged.size());
        const gm::Prim& wall = g.edged[w.wall];
        if (w.style == gm::WindowStyle::cabin) {
            CHECK(wall.kind == gm::PrimKind::cabin_wall);
            CHECK(wall.cell == w.cell);
            CHECK(w.levels == 1);
            CHECK(w.cols >= 1);
            CHECK(w.flags == 0);
            CHECK(w.near_slot == w.far_slot);
            CHECK(w.near_slot == wall.near_slot + 1);
            CHECK(w.near_slot < g.items[wall.item].boat_slots);
            continue;
        }
        CHECK(wall.kind == gm::PrimKind::cell);
        CHECK((wall.flags & gm::prim_flag::far) != 0);
        CHECK(wall.cell == w.cell);
        const gm::BuildingItem& item = g.items[wall.item];
        // its slots: its shop glazing's, its material face's, then its windows' (or a church's own), and its
        // building's plinth's and cornice's after its walls
        const std::uint32_t taken = ((w.flags & gm::window_flag::glazing) != 0 ? 1U : 0U) +
                                    gm::material_slots(w.material) +
                                    (w.cols > 0 || w.church_windows > 0 ? gm::window_slots : 0U);
        CHECK(w.near_slot + taken <= item.near_slots);
        CHECK(w.far_slot + taken <= item.far_slots);
        // rows with its bays, its windows'; without bays only its material face's storeys
        if (w.cols > 0) {
            CHECK(w.levels > 0);
        } else {
            CHECK((w.levels > 0) == (w.material != gm::Material::none));
        }
        if (w.material != gm::Material::none) {
            CHECK(w.elen > 2.0);
            CHECK(w.fh > 0.0);
            CHECK(w.levels >= 1);
        }
        if ((w.flags & (gm::window_flag::plinth | gm::window_flag::cornice)) != 0) {
            REQUIRE(wall.item < g.item_facades.size());
            const std::uint32_t slots = g.item_facades[wall.item];
            REQUIRE(slots != gm::no_facade);
            CHECK(w.near_slot + taken <= (slots & 0xFFFFU));
            CHECK((slots & 0xFFFFU) + 2 <= item.near_slots);
            CHECK((slots >> 16U) + 2 <= item.far_slots);
        }
        const std::uint32_t count = std::uint32_t{w.levels} * w.cols;
        windows += count;
        for (std::uint32_t k = 0; k < count; ++k) {
            const std::uint32_t i = s.windows_first + w.first + k;
            const bool same = i < s.size() && s.kind[i] == atlas::sun::Kind::window &&
                              s.source[i] == static_cast<atlas::sun::Source>(item.source) &&
                              s.owner[i] == item.record && s.edge[i] == wall.edge && s.part[i] == k;
            if (!same) {
                if (c.wrong == 0) {
                    c.first_wrong = "window " + std::to_string(k) + " of record " + std::to_string(item.record) +
                                    " edge " + std::to_string(wall.edge) + " sample " + std::to_string(i);
                }
                ++c.wrong;
            }
        }
    }
    CHECK(windows == g.windows);
    // cell c's primitives are [first[c], first[c + 1]), and its windowed walls alike
    const std::size_t cells = std::size_t{g.cells_per_side} * g.cells_per_side;
    REQUIRE(g.edged_first.size() == cells + 1);
    REQUIRE(g.plain_first.size() == cells + 1);
    REQUIRE(g.window_walls_first.size() == cells + 1);
    CHECK(g.edged_first[cells] == g.edged.size());
    CHECK(g.plain_first[cells] == g.plain.size());
    CHECK(g.window_walls_first[cells] == g.window_walls.size());
    for (std::size_t k = 0; k < cells; ++k) {
        for (std::uint32_t i = g.edged_first[k]; i < g.edged_first[k + 1]; ++i) CHECK(g.edged[i].cell == k);
        for (std::uint32_t i = g.plain_first[k]; i < g.plain_first[k + 1]; ++i) CHECK(g.plain[i].cell == k);
        for (std::uint32_t i = g.window_walls_first[k]; i < g.window_walls_first[k + 1]; ++i) {
            CHECK(g.window_walls[i].cell == k);
        }
    }
    // an n-gon's triangles are its polygon's
    for (std::size_t k = 0; k < g.polygons.size(); ++k) {
        const gm::Polygon& poly = g.polygons[k];
        for (std::uint32_t i = poly.first; i < poly.first + poly.count; ++i) {
            REQUIRE(i < g.plain.size());
            CHECK(g.plain[i].polygon == k);
        }
    }
    // an n-gon's triangles' edges on its ring (prim_flag::outline << k for edge k), which its stroke follows: each edge
    // between two of the ring's points its triangles use that follow each other there (earcut drops a ring's duplicate
    // and collinear points, and an edge runs over them), and the ring's every such edge once
    for (const gm::Polygon& poly : g.polygons) {
        const std::size_t n = poly.ring.size();
        bool unique = true;
        const auto index_of = [&](const gm::V3& v) {
            std::size_t found = n;
            for (std::size_t i = 0; i < n; ++i) {
                if (poly.ring[i].x != v.x || poly.ring[i].y != v.y || poly.ring[i].z != v.z) continue;
                unique = unique && found == n;
                found = i;
            }
            return found;
        };
        std::vector<std::uint8_t> used(n, 0);
        std::vector<std::array<std::size_t, 3>> corners;
        for (std::uint32_t t = poly.first; t < poly.first + poly.count; ++t) {
            const gm::Prim& p = g.plain[t];
            const std::array<std::size_t, 3> at{index_of(p.p[0]), index_of(p.p[1]), index_of(p.p[2])};
            for (const std::size_t i : at) {
                REQUIRE(i < n);
                used[i] = 1;
            }
            corners.push_back(at);
        }
        if (!unique) continue; // a ring through one point twice: its corners' places on it are not known
        const auto next_used = [&](std::size_t i) {
            for (std::size_t k = (i + 1) % n; k != i; k = (k + 1) % n) {
                if (used[k] != 0) return k;
            }
            return i;
        };
        std::size_t marked = 0;
        for (std::size_t t = 0; t < corners.size(); ++t) {
            const std::uint16_t flags = g.plain[poly.first + t].flags;
            for (std::uint32_t k = 0; k < 3; ++k) {
                const std::size_t u = corners[t][k];
                const std::size_t v = corners[t][(k + 1) % 3];
                const bool ring = next_used(u) == v || next_used(v) == u;
                const bool outline = (flags & (std::uint32_t{gm::prim_flag::outline} << k)) != 0;
                CHECK(ring == outline);
                if (outline) ++marked;
            }
        }
        CHECK(marked == static_cast<std::size_t>(std::ranges::count(used, std::uint8_t{1})));
    }
    return c;
}

/// The cells whose near_items are not the items of their near cells (each once, ascending), as the frame takes them to
/// draw a cell's near cells only where one of their buildings is near.
std::size_t near_items_wrong(const gm::TileBuildings& g, const atlas::passes::BuildingTile& layout) {
    const std::size_t grid = !g.edged_first.empty() ? g.edged_first.size() - 1 : 0;
    if (layout.near_items_first.size() != grid + 1 || layout.near_items_first[grid] != layout.near_items.size()) {
        return grid + 1;
    }
    std::size_t wrong = 0;
    std::vector<std::uint16_t> want;
    for (std::size_t c = 0; c < grid; ++c) {
        want.clear();
        for (std::uint32_t i = g.edged_first[c]; i < g.edged_first[c + 1]; ++i) {
            const gm::Prim& q = g.edged[i];
            const bool near_cell = q.kind == gm::PrimKind::cell && (q.flags & gm::prim_flag::near) != 0 &&
                                   (q.flags & gm::prim_flag::far) == 0;
            if (near_cell) want.push_back(static_cast<std::uint16_t>(q.item));
        }
        std::ranges::sort(want);
        want.erase(std::ranges::unique(want).begin(), want.end());
        const std::span<const std::uint16_t> got =
            std::span(layout.near_items)
                .subspan(layout.near_items_first[c], layout.near_items_first[c + 1] - layout.near_items_first[c]);
        if (!std::ranges::equal(got, want)) ++wrong;
    }
    return wrong;
}

} // namespace

TEST_CASE("buildings: stage T of a synthetic tile, every roof shape and a houseboat, held to K8's samples") {
    Fixture f;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "buildings test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw = f.t.encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    // the houseboat's hull and deck in the test's colours (a pack's come from its style)
    const gm::TileBuildings g = gm::build_buildings({.tile = &*tile,
                                                     .head = &f.head,
                                                     .level = 0,
                                                     .region = {.water_y = -1.5, .hull = 0x112233, .deck = 0x445566},
                                                     .kind = 7});
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
    // each draws a building: an outline its own, the part the flat house it stands in (its parent), keyed so
    CHECK(g.items[0].outline == 100);
    CHECK(g.items[7].id == 200);
    CHECK(g.items[7].outline == 100);
    CHECK(g.items[7].spec.building == g.items[0].spec.owner);
    CHECK(g.items[7].spec.owner != g.items[0].spec.owner);
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
        CHECK(g.faces[p.face].base == 0x112233U);
    }
}

TEST_CASE("buildings: a building's material face, as material_face's chain takes its material drawn and its kind") {
    const auto of = [](std::optional<std::string_view> mat, std::optional<std::string_view> kind, std::int64_t levels) {
        gm::Values v;
        v.mat_draw = mat;
        v.kind = kind;
        v.has_levels = true;
        v.levels = levels;
        return gm::material_of(v);
    };
    // glass, whatever its kind; metal with no kind or an industrial one
    CHECK(of("glass", "home", 3) == gm::Material::glass);
    CHECK(of("glass", "parking", 3) == gm::Material::glass);
    CHECK(of("metal", std::nullopt, 1) == gm::Material::metal);
    CHECK(of("metal", "hangar", 1) == gm::Material::metal);
    CHECK(of("metal", "home", 1) == gm::Material::none);
    // a stone, limestone or plaster front of 5 storeys or more of a home, an office or a hotel; levels 0 counts as 1
    CHECK(of("stone", "home", 5) == gm::Material::balconies);
    CHECK(of("limestone", "office", 9) == gm::Material::balconies);
    CHECK(of("plaster", "hotel", 6) == gm::Material::balconies);
    CHECK(of("plaster", "hotel", 4) == gm::Material::none);
    CHECK(of("brick", "home", 9) == gm::Material::none);
    CHECK(of("stone", "church", 9) == gm::Material::none);
    // a parking garage of any other material (metal's industrial kinds are not its)
    CHECK(of("brick", "parking", 0) == gm::Material::parking);
    CHECK(of("metal", "parking", 4) == gm::Material::parking);
    CHECK(of(std::nullopt, "parking", 2) == gm::Material::parking);
    CHECK(of(std::nullopt, std::nullopt, 2) == gm::Material::none);
    // the slots: a glass wall's fill and lines, any other one's path
    CHECK(gm::material_slots(gm::Material::glass) == 2);
    CHECK(gm::material_slots(gm::Material::parking) == 1);
    CHECK(gm::material_slots(gm::Material::none) == 0);
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
        ATLAS_REQUIRE(pack->style() != nullptr);
        const tl::Style& style = *pack->style();
        const atlas::sun::Region region = atlas::sun::region_of(style);
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
        std::size_t windows = 0;
        std::size_t walls = 0;
        std::array<std::size_t, 5> faced{}; // the facade walls by their material face (gm::Material)
        std::size_t beams = 0;              // the hoist beams under old gables
        double ms = 0;
        for (const std::uint8_t level : levels) {
            for (const tl::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
                arena.reset();
                auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
                ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
                const auto t0 = std::chrono::steady_clock::now();
                const gm::TileBuildings g = gm::build_buildings(
                    {.tile = &*tile,
                     .head = head,
                     .level = level,
                     .region = {.water_y = style.water_y, .hull = style.hull, .deck = style.deck, .paris = style.paris},
                     .kind = 0});
                ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                const atlas::sun::SunTile st = atlas::sun::make_tile(*tile, *head, region, level, {});
                const Checked c = check_tile(g, st);
                // the tile's range of the walls heap: its primitives, near cells, faces, colours and rows
                atlas::passes::BuildingTile layout;
                const std::size_t bytes = atlas::passes::heap_words(g, 0.0, 0.0, layout).size() * 16;
                CHECK(near_items_wrong(g, layout) == 0);
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
                windows += g.windows;
                walls += g.window_walls.size();
                for (const gm::WindowWall& ww : g.window_walls) ++faced[static_cast<std::size_t>(ww.material)];
                // each hoist beam names its item and its gable's face, at a slot within the item's, 0.7 m long
                for (const gm::RoofBeam& beam : g.beams) {
                    CHECK(beam.item < g.items.size());
                    CHECK(beam.face < g.faces.size());
                    if (beam.item < g.items.size()) {
                        CHECK(beam.near_slot < g.items[beam.item].near_slots);
                        CHECK(beam.far_slot < g.items[beam.item].far_slots);
                    }
                    const double dx = beam.to.x - beam.from.x;
                    const double dz = beam.to.z - beam.from.z;
                    CHECK(std::abs(std::sqrt((dx * dx) + (dz * dz)) - 0.7) < 1e-9);
                    CHECK(beam.to.y == beam.from.y);
                }
                beams += g.beams.size();
                wrong += c.wrong;
                ++tiles;
            }
        }
        MESSAGE(std::string(name), ": ", tiles, " tiles over ", levels.size(), " levels, ", prims, " primitives, ",
                sampled, " with a sun sample, ", windows, " windows on ", walls, " walls, ", wrong,
                " not K8's; stage T ", ms / static_cast<double>(tiles), " ms a tile");
        MESSAGE(std::string(name), ": material faces on ", faced[1], " glass walls, ", faced[2], " metal, ", faced[3],
                " with balconies, ", faced[4], " of parking garages; ", beams, " hoist beams under old gables");
        MESSAGE(std::string(name), ": a tile's range of the walls heap ",
                static_cast<double>(all_bytes) / static_cast<double>(tiles) / (1 << 20), " MB on average, the densest ",
                static_cast<double>(most_bytes) / (1 << 20), " MB: ", densest);
        std::string per_level;
        for (const auto& [lv, b] : level_bytes)
            per_level += std::format(" {}: {:.2f}", lv, static_cast<double>(b) / (1 << 20));
        MESSAGE(std::string(name), ": the densest tile's MB by level:", per_level);
        // the heaps' share of the buildings (D2 put a dense tile at about 1.5-2.5 MB): a tile 2 MB at most on average,
        // and the canal ring's centre (14/8414/5384, some 6,800 buildings) within 15 MB at its finest level (14.0 with
        // the facades of its walls, a plinth on nearly every one: 24 bytes a facade wall, its record and its reveals'
        // colours; 13.4 with its 40,000 windowed walls alone), 10 MB at the Main board's (level 7), which the frame's
        // test holds within the heaps' budget (scene/Heaps.hpp)
        CHECK(all_bytes <= tiles * (std::size_t{2} << 20U));
        CHECK(most_bytes <= std::size_t{15} << 20U);
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
