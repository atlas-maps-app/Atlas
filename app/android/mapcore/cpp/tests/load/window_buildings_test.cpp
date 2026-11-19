// What a window's build counts of its buildings, for a country's pack build to print (stagep's `windows`; the
// Architect's audit of 19 November, 7.3): the cleaning's counts of the scene its tile was cut from
// (load::BuiltWindow::counts, its footprints), and of its tile alone its buildings and the most windows stage T's walls
// can give them (load::buildings_of, load::most_windows), which stage T's own walls never pass at any level of the
// ladder.
#include "base/arena.hpp"
#include "geometry/Walls.hpp"
#include "load/Window.hpp"
#include "tests/base/require.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <format>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace {

namespace gm = atlas::geometry;
namespace load = atlas::load;
namespace tl = atlas::tiles;

/// A house of `levels` storeys, `east` by `north` degrees from its south-west corner (`lat`, `lon`).
[[nodiscard]] std::string house(int id, double lat, double lon, double east, double north, int levels) {
    return std::format(R"({{"type": "way", "id": {}, "tags": {{"building": "house", "building:levels": "{}"}},
    "geometry": [{{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}},
    {{"lat": {:.7f}, "lon": {:.7f}}}, {{"lat": {:.7f}, "lon": {:.7f}}}]}})",
                       id, levels, lat, lon, lat, lon + east, lat + north, lon + east, lat + north, lon, lat, lon);
}

} // namespace

TEST_CASE("load: a window counts its scene's buildings, and its tile's buildings and the most windows they can have") {
    // The Westerkerk's window: a terrace of three houses of 3 storeys, each about 10 m wide and 12 m deep, the middle
    // one mapped 2 m into the first, which the cleaning clips; a house of 5 storeys apart; and a house in the window's
    // margin, about 45 m north of its tile, which its scene holds and its tile does not
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::array<std::int32_t, 4> tb = tl::tile_box(k);
    constexpr double ten_m = 0.000147; // east, here
    constexpr double twelve_m = 0.000108;
    const double north = static_cast<double>(tb[2]) / 1e7;
    const double middle = static_cast<double>(tb[1] + tb[3]) / 2e7;
    const std::string elements = house(10, 52.3745, 4.8840, ten_m, twelve_m, 3) + ",\n" +
                                 house(11, 52.3745, 4.8840 + (ten_m * 0.8), ten_m, twelve_m, 3) + ",\n" +
                                 house(12, 52.3745, 4.8840 + (ten_m * 1.8), ten_m, twelve_m, 3) + ",\n" +
                                 house(13, 52.3750, 4.8850, ten_m, twelve_m, 5) + ",\n" +
                                 house(14, north + 0.0004, middle, ten_m, twelve_m, 2);
    const std::string answer =
        R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
)" + elements +
        "]}";
    const auto built = load::build_window(std::as_bytes(std::span(answer.data(), answer.size())), k, load::place_of(k));
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    // the scene's: the five houses, the terrace's overlap clipped and none left
    CHECK(built->footprints == 5);
    CHECK(built->counts.pairs_before >= 1);
    CHECK(built->counts.clipped >= 1);
    CHECK(built->counts.overlapping_pairs == 0);
    CHECK(built->counts.uncovered_outlines == 0);
    CHECK(built->counts.unsettled == 0);
    CHECK(built->counts.unmeasured == 0);
    // the tile's: the four houses in it
    const auto held = load::buildings_of(built->pack);
    ATLAS_REQUIRE(held.has_value(), held.error().what);
    CHECK(held->records == 4);
    REQUIRE(held->most_windows > 0);
    CHECK(held->most_windows < tl::building::most_tile_windows);

    // stage T's walls at every level of the ladder give the tile no more windows than that, and cut none
    auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(std::span<const std::byte>(built->pack)));
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE(pack->head() != nullptr);
    ATLAS_REQUIRE(pack->chunks().chunks().size() == 1);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(std::size_t{1} << 22U, atlas::Growth::grows, "window buildings test");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    const auto tile = pack->decode(pack->chunks().chunks().front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
    CHECK(load::most_windows(*tile, pack->head()->frame) == held->most_windows);
    for (std::uint8_t level = 0; level < tl::keep::tolerances.size(); ++level) {
        INFO("the ladder's level " << int{level});
        const gm::TileBuildings g =
            gm::build_buildings({.tile = &*tile, .head = pack->head(), .level = level, .kind = 1});
        CHECK(g.windows <= held->most_windows);
        CHECK(g.windows_cut == 0);
        if (level == 0) CHECK(g.windows > 0);
    }
}

TEST_CASE("load: a window that holds no tile has no buildings and no windows") {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string answer =
        R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": []})";
    const auto built = load::build_window(std::as_bytes(std::span(answer.data(), answer.size())), k, load::place_of(k));
    ATLAS_REQUIRE(built.has_value(), built.error().what);
    CHECK(built->footprints == 0);
    CHECK(built->counts == load::BuildingCounts{});
    const auto held = load::buildings_of(built->pack);
    ATLAS_REQUIRE(held.has_value(), held.error().what);
    CHECK(held->records == 0);
    CHECK(held->most_windows == 0);
}
