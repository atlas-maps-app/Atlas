// A world's tiles and packs as the packer cuts and writes them, small enough for a test (ENGINE.md step 5):
// world_test's index, store and cover checks, and world_vk_test's world by ranges (step 6).
#pragma once

#include "tests/tiles/support.hpp"
#include "tiles/Payload.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Labels.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::test {

/// Cell `c`'s box of a tile with `header`: [south, west, north, east], cells counted row by row from the north-west,
/// each edge the first grid integer at or past its share of the tile (WorldGeometry.cellBoxes), so that every point of
/// a cell is the cell rendertiles' Cells.cellOf gives it.
inline std::array<std::int32_t, 4> cell_box(const tiles::TileHeader& header, std::uint32_t c) {
    const std::int64_t n = header.cells_per_side;
    const std::int64_t west = header.west;
    const std::int64_t width = std::int64_t{header.east} - west;
    const std::int64_t north = header.north;
    const std::int64_t height = north - header.south;
    const auto column = [&](std::int64_t k) { return static_cast<std::int32_t>(west + ((k * width) + n - 1) / n); };
    const auto row = [&](std::int64_t k) { return static_cast<std::int32_t>(north - ((k * height) + n - 1) / n); };
    const std::int64_t r = c / n;
    const std::int64_t k = c % n;
    return {row(r + 1), column(k), row(r), column(k + 1)};
}

/// A world tile as the packer cuts one: cell `c` land west of `(c % 3) / 2` of its width and sea east of it (all sea,
/// half and half, all land), a road across the first cell's land, a place in it.
inline TileBytes world_tile(std::uint8_t z, std::uint32_t x, std::uint32_t y) {
    using namespace atlas::tiles;
    namespace col = columns;
    TileBytes t;
    t.level = z;
    t.n = 4;
    t.x = x;
    t.y = y;
    TileHeader header;
    header.cells_per_side = t.n;
    const auto tb = t.grid_box();
    header.south = tb[0];
    header.west = tb[1];
    header.north = tb[2];
    header.east = tb[3];
    LayerBytes ground{.id = static_cast<std::uint8_t>(LayerId::ground)};
    LayerBytes water{.id = static_cast<std::uint8_t>(LayerId::water)};
    std::vector<Ring> land;
    std::vector<Ring> sea;
    for (std::uint32_t c = 0; c < 16; ++c) {
        const auto b = cell_box(header, c);
        t.cell_boxes[c] = {b, 0};
        const std::int32_t m = b[1] + static_cast<std::int32_t>((std::int64_t{b[3]} - b[1]) * (c % 3) / 2);
        if (m > b[1]) {
            land.push_back({.lat = {b[0], b[0], b[2], b[2]}, .lon = {b[1], m, m, b[1]}, .keep = {}});
            ground.cells.push_back(c);
        }
        if (m < b[3]) {
            sea.push_back({.lat = {b[0], b[0], b[2], b[2]}, .lon = {m, b[3], b[3], m}, .keep = {}});
            water.cells.push_back(c);
        }
    }
    atlas::test::Sink source;
    for (std::size_t i = 0; i < land.size(); ++i) source.u8(ground::source_land_base);
    atlas::test::Sink kind;
    for (std::size_t i = 0; i < land.size(); ++i) kind.varu(t.string("land") + 1U);
    atlas::test::Sink indexes;
    for (std::size_t i = 0; i < land.size(); ++i) indexes.varz(i == 0 ? 0 : 1);
    ground.columns = {{"source", Codec::u8, source.bytes},
                      {"index", Codec::delta, indexes.bytes},
                      {"ring", Codec::geom, col::geom(land)},
                      {"kind", Codec::str, kind.bytes}};
    atlas::test::Sink sea_indexes;
    atlas::test::Sink flags;
    for (std::size_t i = 0; i < sea.size(); ++i) {
        sea_indexes.varz(i == 0 ? 0 : 1);
        flags.u8(2);
    }
    water.columns = {{"index", Codec::delta, sea_indexes.bytes},
                     {"ring", Codec::geom, col::geom(sea)},
                     {"shore", Codec::boolean, flags.bytes},
                     {"tidal", Codec::boolean, flags.bytes}};
    // a road and a place in cell 1 (half land)
    const auto b1 = cell_box(header, 1);
    LayerBytes road{.id = static_cast<std::uint8_t>(LayerId::road)};
    road.cells = {1};
    road.columns = {{"index", Codec::delta, col::delta({0})},
                    {"line", Codec::geom, col::geom({{.lat = {b1[0], b1[2]}, .lon = {b1[1], b1[1] + 10}, .keep = {}}})},
                    {"hw", Codec::str, col::str(t, {"secondary"})}};
    LayerBytes label{.id = static_cast<std::uint8_t>(LayerId::label)};
    label.cells = {1};
    label.columns = {{"source", Codec::u8, col::u8({label::source_place})},
                     {"index", Codec::delta, col::delta({0})},
                     {"name", Codec::str, col::str(t, {"Westzaan"})},
                     {"pos", Codec::point, col::point({{b1[0] + 5, b1[1] + 5}})},
                     {"prev", Codec::point, col::point({{b1[0] + 5, b1[1] + 5}})},
                     {"count", Codec::varu, col::varu({1})},
                     {"kind", Codec::str, col::str(t, {"village"})}};
    t.layers = {ground, water, road, label};
    return t;
}

/// `tile` encoded as a chunk.
inline ChunkBytes chunk_of(TileBytes& tile) {
    ChunkBytes c;
    c.tile = {tile.level, tile.x, tile.y};
    c.raw = tile.encode();
    for (const auto& l : tile.layers) c.count += static_cast<std::uint32_t>(l.cells.size());
    c.reach = tile.reach();
    return c;
}

/// A world pack of `tiles` for `levels` (fine to coarse), as WorldPack.write lays one out: its chunks a level at a
/// time, in the levels' order.
inline std::vector<std::byte> world_pack(std::vector<TileBytes> tiles, std::span<const std::uint8_t> levels) {
    using namespace atlas::tiles;
    std::ranges::stable_sort(tiles, {},
                             [&](const TileBytes& t) { return std::ranges::find(levels, t.level) - levels.begin(); });
    std::vector<ChunkBytes> chunks;
    chunks.reserve(tiles.size());
    for (TileBytes& t : tiles) chunks.push_back(chunk_of(t));
    atlas::test::PackOptions options;
    options.head = atlas::test::head_bytes(levels, Profile::world_base);
    return atlas::test::pack_bytes(chunks, levels, format_render, options);
}

} // namespace atlas::test
