// What a pack's values make past the buildings' records (twin/building/BuildingTile.cpp's heap_words): a height no
// building has (a double in the tile's column: 4.4e85 m, an infinity, a NaN) in a level's tile, held at its grid's
// edge; a wall of more near cells than the cells' record holds (a byte each of its wall's columns and rows), a shed
// 2.6 km long, whose long walls stage T cuts into 288 bays of 9 m, as a node mapped a degree off makes one: its cells
// left out and the wall drawn as its one cell near too, never an end of the process, each other wall keeping its
// cells.
#include "base/arena.hpp"
#include "geometry/Walls.hpp"
#include "tests/base/require.hpp"
#include "tests/building/fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Payload.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTile.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace gm = atlas::geometry;
namespace tl = atlas::tiles;
namespace col = atlas::test::columns;
using atlas::test::LayerBytes;
using atlas::test::Ring;
using atlas::test::TileBytes;
using atlas::test::building::rect;

namespace {

/// A shed 2.6 km east to west and 10 m deep (about 147 grid units a metre east, 90 north, here), and a house of 30 m
/// beside it whose long walls are cut into three bays.
[[nodiscard]] TileBytes long_tile() {
    TileBytes t;
    const std::vector<Ring> rings{rect(0, 0, 147 * 2600, 900), rect(0, 2700, 147 * 30, 900)};
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0};
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1})},
        {"id", tl::Codec::delta, col::delta({400, 401})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({10.0, 10.0})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({std::nullopt, std::nullopt})},
        {"levels", tl::Codec::nvarz, col::nvarz({2, 3})},
        {"roof", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
        {"wall_rgb", tl::Codec::varu, col::varu({0x9AA1A7, 0x8E5A46})},
        {"roof_rgb", tl::Codec::varu, col::varu({0x737E88, 0x56585C})},
        {"flags", tl::Codec::varu, col::varu({0, 0})},
        {"tag:building", tl::Codec::str, col::str(t, {"shed", "house"})},
        {"tag:roof:direction", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
    };
    t.layers = {b};
    return t;
}

/// A house of 10 m and one of `h` (a pack's height as a double, past what any building has) beside it.
[[nodiscard]] TileBytes tall_tile(double h) {
    TileBytes t;
    const std::vector<Ring> rings{rect(0, 0, 1470, 900), rect(0, 2700, 1470, 900)};
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0};
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1})},
        {"id", tl::Codec::delta, col::delta({500, 501})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({10.0, h})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({std::nullopt, std::nullopt})},
        {"levels", tl::Codec::nvarz, col::nvarz({3, 3})},
        {"roof", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
        {"wall_rgb", tl::Codec::varu, col::varu({0x8E5A46, 0x8E5A46})},
        {"roof_rgb", tl::Codec::varu, col::varu({0x56585C, 0x56585C})},
        {"flags", tl::Codec::varu, col::varu({0, 0})},
        {"tag:building", tl::Codec::str, col::str(t, {"house", "house"})},
        {"tag:roof:direction", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
    };
    t.layers = {b};
    return t;
}

} // namespace

TEST_CASE("buildings: a level's tile holds a height no building has at its grid's edge, its grid kept fine") {
    tl::RenderHead head;
    head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8};
    constexpr double inf = std::numeric_limits<double>::infinity();
    // loadfail's 4.4e85 m, past the grid's 2^127 steps; an infinity, which no step reaches; a NaN
    for (const double h : {4.4e85, 3e46, inf, -inf, std::numeric_limits<double>::quiet_NaN()}) {
        INFO("a height of " << h);
        atlas::Arena arena(1U << 20U, atlas::Growth::grows, "tall test");
        tl::TileDecoder decoder;
        const std::vector<std::byte> raw = tall_tile(h).encode();
        auto tile = decoder.decode(raw, arena);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        const gm::TileBuildings g =
            gm::build_buildings({.tile = &*tile, .head = &head, .level = 0, .kind = 1, .lean = true});
        REQUIRE(g.lean);
        atlas::passes::BuildingTile layout;
        (void)atlas::passes::heap_words(g, 0.0, 0.0, layout);
        CHECK(layout.lean);
        // the houses' few metres keep the grid's steps fine (4 m at most: 2^2), the height held at its edge
        for (std::size_t i = 0; i < 3; ++i) CHECK(layout.grid_steps.at(i) <= 2);
    }
}

TEST_CASE("buildings: a wall of more cells than their record holds is drawn as its one cell near too") {
    tl::RenderHead head;
    head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8};
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "long wall test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw = long_tile().encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const gm::TileBuildings g = gm::build_buildings({.tile = &*tile, .head = &head, .level = 0, .kind = 1});
    // stage T cuts the shed's long walls past the record, the house's within it
    std::size_t past = 0;
    std::size_t within = 0;
    for (const gm::Prim& p : g.edged) {
        if (p.kind != gm::PrimKind::cell || (p.flags & gm::prim_flag::near) == 0 || (p.flags & gm::prim_flag::far) != 0)
            continue;
        (p.ncol > 0xFF ? past : within) += 1;
    }
    REQUIRE(past == 2 * 288);
    REQUIRE(within > 0);
    atlas::passes::BuildingTile layout;
    const std::vector<std::array<std::uint32_t, 4>> words = atlas::passes::heap_words(g, 0.0, 0.0, layout);
    CHECK(layout.cells == within); // the house's cells alone
    // the heap's edged records are stage T's but its near cells, in order: each wall of cells left out is drawn near
    std::vector<const gm::Prim*> kept;
    for (const gm::Prim& p : g.edged) {
        const bool near_cell =
            p.kind == gm::PrimKind::cell && (p.flags & gm::prim_flag::near) != 0 && (p.flags & gm::prim_flag::far) == 0;
        if (!near_cell) kept.push_back(&p);
    }
    REQUIRE(kept.size() == layout.edged);
    const std::uint32_t pv = layout.prim_vectors();
    std::size_t near_walls = 0;
    std::size_t far_walls = 0;
    for (std::size_t i = 0; i < kept.size(); ++i) {
        const gm::Prim& p = *kept[i];
        if (p.kind != gm::PrimKind::cell || (p.flags & gm::prim_flag::near) != 0) continue; // a wall of cells
        const std::uint32_t flags = words.at(layout.prims_at + (i * pv) + 2)[3] >> 16U;
        if ((flags & atlas::shaders::BUILDING_NEAR) != 0U) {
            ++near_walls;
        } else {
            ++far_walls;
        }
    }
    CHECK(near_walls == 2); // the shed's long walls
    CHECK(far_walls == 2);  // the house's long walls, drawn by their cells up close
}
