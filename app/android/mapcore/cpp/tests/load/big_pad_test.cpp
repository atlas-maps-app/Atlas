// Stage P files a big road by its box widened by half the width the ground draws it with and half a metre
// (load/Cut.cpp's big_pad, geometry/BigRoad.hpp's rule), over the ground's own cases (tests/ground/big_roads.hpp): a
// lanes or a width past any road's, or that cannot be read, pads as an untagged road, and one in range as drawn. Each
// case is a motorway 60 m east to west alone in a cell of a window's tile, so that the cell's box is its own: as far
// north and south of its line as the pad, to the grid's step.
#include "base/arena.hpp"
#include "load/Window.hpp"
#include "tests/base/require.hpp"
#include "tests/ground/big_roads.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Schema.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <span>
#include <string>
#include <vector>

namespace tl = atlas::tiles;

namespace {

const tl::TileKey window_tile = tl::tile_of(14, 523'746'000, 48'840'000);

/// The middle of cell `i` of the window's tile (row by row from the north-west), as grid points (degrees × 1e7).
[[nodiscard]] std::array<std::int32_t, 2> cell_middle(std::size_t i) {
    const std::array<std::int32_t, 4> b = tl::tile_box(window_tile);
    const std::int64_t side = tl::cells_per_side(window_tile.z);
    const auto row = static_cast<std::int64_t>(i) / side;
    const auto col = static_cast<std::int64_t>(i) % side;
    const std::int64_t lat = b[2] - (((2 * row) + 1) * (std::int64_t{b[2]} - b[0]) / (2 * side));
    const std::int64_t lon = b[1] + (((2 * col) + 1) * (std::int64_t{b[3]} - b[1]) / (2 * side));
    return {static_cast<std::int32_t>(lat), static_cast<std::int32_t>(lon)};
}

} // namespace

TEST_CASE("load: a big road's box is widened by half the width the ground draws it with, a value no road has as none") {
    const std::vector<atlas::test::BigRoadCase> cases = atlas::test::big_road_cases();
    REQUIRE(cases.size() <= std::size_t{tl::cells_per_side(window_tile.z)} * tl::cells_per_side(window_tile.z));
    std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [)";
    for (std::size_t i = 0; i < cases.size(); ++i) {
        const auto [lat, lon] = cell_middle(i);
        const double la = lat / 1e7;
        const double lo = lon / 1e7;
        data += std::format(R"({}{{"type": "way", "id": {}, "tags": {{"highway": "motorway"{}}}, "geometry": [)"
                            R"({{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}, )"
                            R"({{"lat": {:.7f}, "lon": {:.7f}}}]}})",
                            i == 0 ? "" : ",", 40 + i, atlas::test::overpass_tags(cases[i]), la, lo - 0.0004, la, lo,
                            la, lo + 0.0004);
    }
    data += "]}";
    const auto built = atlas::load::build_window(std::as_bytes(std::span(data.data(), data.size())), window_tile,
                                                 atlas::load::place_of(window_tile));
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "big-pad";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    {
        std::ofstream out(file, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                  static_cast<std::streamsize>(built->pack.size()));
    }
    auto pack = atlas::test::open_pack(file.string());
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const tl::RenderHead& head = *pack->head();
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "big pad test");
    const auto chunks = pack->chunks().level(window_tile.z);
    ATLAS_REQUIRE(!chunks.empty());
    auto tile = pack->decode(chunks.front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    for (std::size_t i = 0; i < cases.size(); ++i) {
        const atlas::test::BigRoadCase& c = cases[i];
        INFO("way " << 40 + i << ": " << c.what);
        const auto [lat, lon] = cell_middle(i);
        const std::uint16_t cell = tl::cell_of(window_tile, lat, lon);
        REQUIRE(cell < tile->cells.size());
        const tl::CellInfo& box = tile->cells[cell];
        CHECK(box.total == 1);
        // the line runs east to west: its box reaches the pad north and south of it, to the grid's step (widen's
        // ceiling and its one step more: 2.2 cm)
        const double pad = (c.drawn_width / 2) + 0.5;
        const double half = (static_cast<double>(box.north) - box.south) / 2 / 1e7 * head.frame.kz;
        CHECK(half >= pad);
        CHECK(half <= pad + 0.03);
    }
}
