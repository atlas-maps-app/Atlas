// What a pack's values make past the buildings' records (twin/building/BuildingTile.cpp's heap_words, the frame's
// window jobs): a height no building has (a double in the tile's column: 4.4e85 m, an infinity, a NaN) in a level's
// tile, held at its grid's edge; a wall of more near cells than the cells' record holds (a byte each of its wall's
// columns and rows), a shed 2.6 km long, whose long walls stage T cuts into 288 bays of 9 m, as a node mapped a degree
// off makes one: its cells left out and the wall drawn as its one cell near too, never an end of the process, each
// other wall keeping its cells; the windows of a job's walls, whose sum passes 32 bits where the tile's running
// count wraps; a building past an item's 65,535 painter slots (a relation's joined ring of thousands of walls),
// whose walls past them are drawn as one cell each; and the windows' bounds (tl::building::most_levels,
// most_window_cols, most_tile_windows), which a building's walls and their sun samples keep alike: levels no building
// has (201, 2^31 - 1, -7) as unmapped, a wall 140 km long without windows, and a tile's windows stopped at the most a
// frame draws.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "geometry/Walls.hpp"
#include "load/Amsterdam.hpp"
#include "load/Window.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tests/building/fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Payload.hpp"
#include "tiles/layers/Buildings.hpp"
#include "twin/building/BuildingShader.hpp"
#include "twin/building/BuildingTile.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <map>
#include <optional>
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

TEST_CASE("buildings: a job's windows are counted exactly though the tile's running count wraps") {
    // two walls of 4e9 windows each (each below 2^32, as levels times bays are), their running count wrapped
    atlas::passes::BuildingTile layout;
    constexpr std::uint64_t each = 4'000'000'000;
    layout.window_at = {0, static_cast<std::uint32_t>(each), static_cast<std::uint32_t>((2 * each) & 0xFFFF'FFFFU)};
    CHECK(layout.windows_of(0, 1) == each);
    CHECK(layout.windows_of(1, 1) == each);
    CHECK(layout.windows_of(0, 2) == 2 * each);
    // what the jobs' cap read before (the running count's difference in 32 bits): far below the frame's 2^20
    CHECK(layout.window_at[2] - layout.window_at[0] == 3'705'032'704U);
}

namespace {

/// A round building of `edges` walls about 2.6 m long, 21 m high and of 3 levels (each wall three near cells, a row a
/// storey, and its windows' five slots: eight painter slots a wall, past an item's 65,535 after 8,191 walls), as a
/// relation's joined ring makes one; and the house of long_tile beside it.
[[nodiscard]] TileBytes round_tile(std::size_t edges) {
    TileBytes t;
    constexpr double east = 147.26; // grid units a metre, here
    constexpr double north = 89.83;
    const double radius = 2.6 * static_cast<double>(edges) / atlas::pymath::tau;
    Ring ring;
    for (std::size_t i = 0; i < edges; ++i) {
        // clockwise on the map, as the loader leaves a building's ring
        const double a = atlas::pymath::pi - (atlas::pymath::tau * static_cast<double>(i) / static_cast<double>(edges));
        ring.lat.push_back(atlas::test::building::s0 +
                           static_cast<std::int32_t>(std::lround(radius * (1 + atlas::pymath::sin(a)) * north)));
        ring.lon.push_back(atlas::test::building::w0 +
                           static_cast<std::int32_t>(std::lround(radius * (1 + atlas::pymath::cos(a)) * east)));
    }
    const auto beside = static_cast<std::int32_t>(std::lround(2.2 * radius * north));
    const std::vector<Ring> rings{ring, rect(0, beside, 147 * 30, 900)};
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = {0, 0};
    b.columns = {
        {"index", tl::Codec::delta, col::delta({0, 1})},
        {"id", tl::Codec::delta, col::delta({500, 501})},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, col::centi({21.0, 10.0})},
        {"min", tl::Codec::centi, col::centi({0.0, 0.0})},
        {"roof_h", tl::Codec::centi, col::centi({std::nullopt, std::nullopt})},
        {"levels", tl::Codec::nvarz, col::nvarz({3, 3})},
        {"roof", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
        {"wall_rgb", tl::Codec::varu, col::varu({0x9AA1A7, 0x8E5A46})},
        {"roof_rgb", tl::Codec::varu, col::varu({0x737E88, 0x56585C})},
        {"flags", tl::Codec::varu, col::varu({0, 0})},
        {"tag:building", tl::Codec::str, col::str(t, {"yes", "house"})},
        {"tag:roof:direction", tl::Codec::str, col::str(t, {std::nullopt, std::nullopt})},
    };
    t.layers = {b};
    return t;
}

} // namespace

TEST_CASE("buildings: a building past an item's painter slots draws its walls past them as one cell each") {
    constexpr std::size_t edges = 9000;
    tl::RenderHead head;
    head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8};
    atlas::Arena arena(1U << 22U, atlas::Growth::grows, "painter slots test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw = round_tile(edges).encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const gm::TileBuildings g = gm::build_buildings({.tile = &*tile, .head = &head, .level = 0, .kind = 1});
    // per building: its walls of cells (a far one cell, its near cells after it) and its walls drawn as one cell
    std::array<std::size_t, 2> celled{};
    std::array<std::size_t, 2> single{};
    std::array<std::uint32_t, 2> last_cell_slot{};
    for (const gm::Prim& p : g.edged) {
        if (p.kind != gm::PrimKind::cell || p.item > 1) continue;
        const bool near = (p.flags & gm::prim_flag::near) != 0;
        const bool far = (p.flags & gm::prim_flag::far) != 0;
        if (near && far) ++single.at(p.item);
        if (far && !near) ++celled.at(p.item);
        if (near && !far) last_cell_slot.at(p.item) = std::max<std::uint32_t>(last_cell_slot.at(p.item), p.near_slot);
    }
    // the round building's walls take their cells until their slots would pass the item's last, then one cell each;
    // the house beside it keeps all its cells
    CHECK(celled[0] + single[0] == edges);
    CHECK(celled[0] > 8000);
    CHECK(single[0] > 500);
    CHECK(last_cell_slot[0] <= 0xFFFFU);
    CHECK(celled[1] == 2); // the house's long walls, of three bays
    CHECK(single[1] == 2); // its ends
    // and the heap takes them: each wall's cells a whole run (heap_words' release asserts)
    atlas::passes::BuildingTile layout;
    const std::vector<std::array<std::uint32_t, 4>> words = atlas::passes::heap_words(g, 0.0, 0.0, layout);
    CHECK(!words.empty());
    CHECK(layout.cells > 3 * 8000);
}

namespace {

static_assert(tl::building::most_levels == atlas::load::max_levels, "stage T's levels are those stage P writes");

/// `one`'s bytes `n` times: a column of `n` equal values, for a codec that writes each value apart.
[[nodiscard]] std::vector<std::byte> times(const std::vector<std::byte>& one, std::size_t n) {
    std::vector<std::byte> out;
    for (std::size_t i = 0; i < n; ++i) out.insert(out.end(), one.begin(), one.end());
    return out;
}

/// A delta column of `n` values counting up by one from `from`.
[[nodiscard]] std::vector<std::byte> counting(std::int64_t from, std::size_t n) {
    std::vector<std::byte> out = col::delta({from});
    const std::vector<std::byte> step = times(col::varz({1}), n - 1);
    out.insert(out.end(), step.begin(), step.end());
    return out;
}

/// Houses 10 m high and deep in a row, each `widths[i]` metres east to west (about 147 grid units a metre east, 90
/// north, here) with `levels[i]`.
[[nodiscard]] TileBytes row_tile(std::initializer_list<double> widths,
                                 std::initializer_list<std::optional<std::int64_t>> levels) {
    TileBytes t;
    const std::size_t n = widths.size();
    std::vector<Ring> rings;
    for (const double w : widths) {
        const auto dy = static_cast<std::int32_t>(2700 * rings.size());
        rings.push_back(rect(0, dy, static_cast<std::int32_t>(std::lround(147 * w)), 900));
    }
    LayerBytes b;
    b.id = static_cast<std::uint8_t>(tl::LayerId::building);
    b.cells = std::vector<std::uint32_t>(n, 0);
    b.columns = {
        {"index", tl::Codec::delta, counting(0, n)},
        {"id", tl::Codec::delta, counting(600, n)},
        {"ring", tl::Codec::geom, col::geom(rings)},
        {"h", tl::Codec::centi, times(col::centi({10.0}), n)},
        {"min", tl::Codec::centi, times(col::centi({0.0}), n)},
        {"roof_h", tl::Codec::centi, times(col::centi({std::nullopt}), n)},
        {"levels", tl::Codec::nvarz, col::nvarz(levels)},
        {"roof", tl::Codec::str, times(col::str(t, {std::nullopt}), n)},
        {"wall_rgb", tl::Codec::varu, times(col::varu({0x8E5A46}), n)},
        {"roof_rgb", tl::Codec::varu, times(col::varu({0x56585C}), n)},
        {"flags", tl::Codec::varu, times(col::varu({0}), n)},
        {"tag:building", tl::Codec::str, times(col::str(t, {"house"}), n)},
        {"tag:roof:direction", tl::Codec::str, times(col::str(t, {std::nullopt}), n)},
    };
    t.layers = {b};
    return t;
}

/// A tile's windows as its walls number them and as its sun samples hold them: the windowed walls (but a cabin's) in
/// the walk's order, each after those before it by its record's rows × bays, the tile's count their sum, and its window
/// group's samples, record by record, as many as the record's walls' windows. Returns each record's windowed walls.
[[nodiscard]] std::map<std::uint32_t, std::vector<gm::WindowWall>> windows_agree(const gm::TileBuildings& g,
                                                                                 const atlas::sun::SunTile& st) {
    std::vector<gm::WindowWall> walls(g.window_walls.begin(), g.window_walls.end());
    std::ranges::sort(walls, {}, &gm::WindowWall::first); // the walk's order (by cell in the tile)
    std::map<std::uint32_t, std::vector<gm::WindowWall>> by_record;
    std::map<std::uint32_t, std::uint64_t> windows_of;
    std::uint64_t n = 0;
    for (const gm::WindowWall& w : walls) {
        if (w.style == gm::WindowStyle::cabin) continue;
        CHECK(w.first == n);
        n += std::uint64_t{w.levels} * w.cols;
        const std::uint32_t record = g.edged.at(w.wall).item; // one item a building, in the layer's order
        by_record[record].push_back(w);
        windows_of[record] += std::uint64_t{w.levels} * w.cols;
    }
    CHECK(g.windows == n);
    const atlas::sun::TileSamples& s = st.samples;
    CHECK(s.size() - s.windows_first == n);
    std::map<std::uint32_t, std::uint64_t> sampled;
    std::size_t not_windows = 0; // counted, not checked a sample at a time: a million of them
    for (std::uint32_t i = s.windows_first; i < s.size(); ++i) {
        not_windows += s.kind[i] == atlas::sun::Kind::window ? 0 : 1;
        ++sampled[s.owner[i]];
    }
    CHECK(not_windows == 0);
    for (const auto& [record, count] : windows_of) {
        INFO("record " << record);
        CHECK(sampled[record] == count);
    }
    for (const auto& [record, count] : sampled) {
        INFO("record " << record);
        CHECK(windows_of[record] == count);
    }
    return by_record;
}

} // namespace

TEST_CASE("buildings: levels and bays past what a building holds draw no windows, in its walls and samples alike") {
    // a house of 3 levels and one of 200 (the most), three whose levels no building has, and a shed 140 km long whose
    // long walls' bays pass what a record holds (66,666 of 2.1 m), its ends keeping theirs
    tl::RenderHead head;
    head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8};
    atlas::Arena arena(1U << 22U, atlas::Growth::grows, "window bounds test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw =
        row_tile({30, 30, 30, 30, 30, 140'000}, {3, 200, 201, 2'147'483'647, -7, 1}).encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const gm::TileBuildings g = gm::build_buildings({.tile = &*tile, .head = &head, .level = 0, .kind = 1});
    // the walls first: a wall's windows past the record's rows or bays were counted apart from what it holds (and its
    // samples, 2^31 rows of them, would never end)
    std::uint64_t held = 0;
    for (const gm::WindowWall& w : g.window_walls) held += std::uint64_t{w.levels} * w.cols;
    ATLAS_REQUIRE(g.windows == held);
    CHECK(g.windows_cut == 0);
    // the most a pack's build reckons they can be (load::most_windows), the levels no building has taken as none
    CHECK(atlas::load::most_windows(*tile, head.frame) >= g.windows);
    for (const gm::WindowWall& w : g.window_walls) {
        const std::uint32_t record = g.edged.at(w.wall).item;
        INFO("record " << record << ", a wall of " << w.elen << " m");
        if (record == 2 || record == 3 || record == 4 || w.elen > 100'000) ATLAS_REQUIRE(w.cols == 0);
    }
    const atlas::sun::Region region;
    const atlas::sun::SunTile st = atlas::sun::make_tile(*tile, head, region, 0, {});
    const auto walls = windows_agree(g, st);
    ATLAS_REQUIRE(walls.contains(0));
    for (const gm::WindowWall& w : walls.at(0)) CHECK((w.levels == 3 && w.cols > 0));
    ATLAS_REQUIRE(walls.contains(1));
    for (const gm::WindowWall& w : walls.at(1)) CHECK((w.levels == 200 && w.cols > 0));
    // the three past what a building holds keep their walls' facades (a plinth) without windows or their samples
    ATLAS_REQUIRE(walls.contains(5));
    std::size_t ends = 0;
    for (const gm::WindowWall& w : walls.at(5)) {
        if (w.elen < 100) ends += w.cols > 0 ? 1 : 0;
    }
    CHECK(ends == 2);
}

TEST_CASE(
    "buildings: a tile's windows stop at the most a frame draws, a wall past them without, in walls and samples") {
    // a shed 11 km long of 200 levels (its long walls 5,233 bays each: 1,046,600 windows a wall, one within the most a
    // frame draws, 2^20, and two past it), and a house of 3 levels after it, whose windows fit in what is left
    tl::RenderHead head;
    head.frame = tl::Frame{.lat0 = 52.374, .lon0 = 4.884, .kx = 67'908.9, .kz = 111'318.8};
    atlas::Arena arena(1U << 22U, atlas::Growth::grows, "tile windows test");
    tl::TileDecoder decoder;
    const std::vector<std::byte> raw = row_tile({11'010, 30}, {200, 3}).encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    const gm::TileBuildings g = gm::build_buildings({.tile = &*tile, .head = &head, .level = 0, .kind = 1});
    ATLAS_REQUIRE(g.windows <= tl::building::most_tile_windows);
    CHECK(g.windows_cut == 1);
    // a pack's build, which cannot count what stage T cuts, tells that this tile can pass the most (load::most_windows)
    CHECK(atlas::load::most_windows(*tile, head.frame) > tl::building::most_tile_windows);
    std::size_t long_kept = 0;
    std::size_t long_cut = 0;
    for (const gm::WindowWall& w : g.window_walls) {
        if (w.elen < 1000) continue;
        (w.cols > 0 ? long_kept : long_cut) += 1;
    }
    ATLAS_REQUIRE(long_kept == 1);
    ATLAS_REQUIRE(long_cut == 1);
    const atlas::sun::Region region;
    const atlas::sun::SunTile st = atlas::sun::make_tile(*tile, head, region, 0, {});
    const auto walls = windows_agree(g, st);
    ATLAS_REQUIRE(walls.contains(1));
    CHECK(walls.at(1).size() == 4);
    for (const gm::WindowWall& w : walls.at(1)) CHECK((w.levels == 3 && w.cols > 0));
    for (const gm::WindowWall& w : walls.at(0)) {
        if (w.elen > 1000 && w.cols > 0) CHECK(std::uint64_t{w.levels} * w.cols == 1'046'600);
    }
}
