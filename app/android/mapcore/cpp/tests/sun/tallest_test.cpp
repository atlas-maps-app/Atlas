// The sun's tallest solid (sun::scene_maxh, sun/Samples.hpp), where a scene's march ends: folded from its tiles' parts
// (sun::tile_tallest, fold_tallest) as Python's max over every height in the pack's order, a first NaN's bits kept;
// and kept by chunk across a frame cell's compositions, so that a cell composed window by window decodes each window
// once for it and a level's tile joining decodes none at the windows' zoom, its tallest the same to the bit as the
// cell's scanned whole (SunGlobals keeps a cell's parts by its id while the store holds it).
#include "base/pymath/PyMath.hpp"
#include "load/Levels.hpp"
#include "load/Window.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tests/load/corner.hpp"
#include "tiles/Store.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <random>
#include <span>
#include <string>
#include <vector>

namespace {

using namespace atlas;
namespace pm = atlas::pymath;

[[nodiscard]] std::uint64_t bits(double v) noexcept { return std::bit_cast<std::uint64_t>(v); }
/// A random engine seeded with `seed` (a seed handed in, as the other tests seed theirs).
[[nodiscard]] std::mt19937_64 seeded(std::uint64_t seed) { return std::mt19937_64(seed); }

} // namespace

TEST_CASE("sun: a scene's tallest folded from its tiles' parts is Python's max over every height in the pack's order, "
          "a first NaN's bits kept") {
    // heights as a pack's records hold them (never 0: a record of no height adds none), NaNs of two payloads among them
    const std::array<double, 7> pool{12.5,
                                     45.0,
                                     -3.0,
                                     std::bit_cast<double>(std::uint64_t{0x7FF8'0000'0000'1234}),
                                     60.25,
                                     45.0,
                                     std::bit_cast<double>(std::uint64_t{0xFFF8'0000'0000'0001})};
    std::mt19937_64 random = seeded(20261104);
    int nans = 0;
    for (int round = 0; round < 4000; ++round) {
        // a scene of up to five tiles of up to four heights each, a tile's part made as tile_tallest makes it
        std::vector<std::vector<double>> tiles(1 + (random() % 5));
        for (std::vector<double>& t : tiles) {
            const std::uint64_t n = random() % 5;
            for (std::uint64_t k = 0; k < n; ++k) t.push_back(pool.at(random() % pool.size()));
        }
        std::vector<sun::Tallest> parts;
        bool any = false;
        double python = 0.0; // scene_maxh's fold record by record
        for (const std::vector<double>& t : tiles) {
            sun::Tallest part;
            for (const double h : t) {
                if (!part.any) part.first = h;
                part.any = true;
                if (!std::isnan(h)) {
                    part.most = part.numbers ? pm::max(part.most, h) : h;
                    part.numbers = true;
                }
                python = any ? pm::max(python, h) : h;
                any = true;
            }
            parts.push_back(part);
        }
        const double folded = sun::fold_tallest(parts);
        CHECK_MESSAGE(bits(folded) == bits(python), std::format("round {}: {} against {}", round, folded, python));
        nans += std::isnan(python) ? 1 : 0;
    }
    CHECK(nans > 0); // a first NaN came, and was kept with its bits
}

TEST_CASE("sun: a frame cell's tallest, composed window by window, decodes each window once and is the cell's scanned "
          "whole; a level's tile joining decodes none of the windows") {
    // Three windows of frame cell L10 526/337 joining one at a time, each with a house and a tower, the tallest
    // growing with the second, and their levels after them.
    test::CornerWindows at;
    at.corner = tiles::TileKey(14, 526U << 4U, 337U << 4U);
    const std::array<std::int32_t, 4> box = tiles::tile_box(at.corner);
    at.lat0 = static_cast<double>(box[2]) / 1e7;
    at.lon0 = static_cast<double>(box[1]) / 1e7;
    at.kx = 111320.0 * pm::cos(pm::radians(at.lat0));
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "sun-tallest";
    std::filesystem::create_directories(folder);
    const auto write = [&folder](const std::vector<std::byte>& pack, const std::string& name) {
        const std::filesystem::path file = folder / name;
        std::ofstream out(file, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(pack.data()), // NOLINT: the pack's bytes as chars
                  static_cast<std::streamsize>(pack.size()));
        return file.string();
    };
    std::int64_t id = 1;
    std::vector<std::vector<std::byte>> built;
    const auto window = [&](std::uint32_t dx, std::uint32_t dy, double tower) -> std::string {
        const tiles::TileKey k(14, at.corner.x + dx, at.corner.y + dy);
        const double east = (1490.0 * dx) + 300.0;
        const double south = (-1490.0 * dy) - 300.0;
        const std::string json =
            std::format(R"({{"version":0.6,"elements":[{},{}]}})",
                        test::made_building(id, R"("building":"yes","building:levels":"4")", east, south - 30,
                                            east + 30, south, at),
                        test::made_building(id, std::format(R"("building":"yes","height":"{}")", tower), east + 200,
                                            south - 20, east + 220, south, at));
        auto made = load::build_window(std::as_bytes(std::span(json.data(), json.size())), k, load::place_of(k));
        ATLAS_REQUIRE_OR_RETURN(std::string{}, made.has_value(), made.error().what);
        built.push_back(made->pack);
        return write(made->pack, std::format("{}-{}.atlas", k.x, k.y));
    };
    tiles::Store store;
    const auto add = [&store](const tiles::TileKey& k, const std::string& file) {
        return store.add(
            {.id = std::format("window/{}/{}/{}", k.z, k.x, k.y), .kind = tiles::PackKind::window, .path = file});
    };
    const std::array<std::uint8_t, 3> levels{0, 1, 3}; // ladder levels: the records', and two tolerances' variants
    std::array<sun::TallestByChunk, 3> kept;
    std::array<double, 3> last{};
    const auto check = [&](std::uint8_t zoom, std::uint64_t decodes) {
        const tiles::Pack& pack = store.packs()[0]->pack;
        for (std::size_t l = 0; l < levels.size(); ++l) {
            sun::TallestByChunk parts;
            std::uint64_t decoded = 0;
            const auto h = sun::scene_maxh(pack, levels.at(l), zoom, kept.at(l), parts, decoded);
            const auto whole = sun::scene_maxh(pack, levels.at(l), zoom);
            ATLAS_REQUIRE(h.has_value() && whole.has_value());
            CHECK(decoded == decodes);
            CHECK(bits(*h) == bits(*whole));
            CHECK(parts.size() == pack.chunks().level(zoom).size());
            kept.at(l) = std::move(parts);
            last.at(l) = *h;
        }
    };
    const std::array<std::pair<std::uint32_t, std::uint32_t>, 3> places{{{1, 0}, {0, 1}, {0, 0}}};
    const std::array<double, 3> towers{45.0, 61.5, 30.0};
    std::vector<double> tallest; // the records' tallest as each window joined
    for (std::size_t i = 0; i < places.size(); ++i) {
        const std::string file = window(places.at(i).first, places.at(i).second, towers.at(i));
        ATLAS_REQUIRE(!file.empty());
        ATLAS_REQUIRE(add(tiles::TileKey(14, at.corner.x + places.at(i).first, at.corner.y + places.at(i).second), file)
                          .has_value());
        check(14, 1); // the window that joined, alone
        tallest.push_back(last[0]);
    }
    CHECK(tallest[1] > tallest[0]); // the second window's tower the tallest from then on
    CHECK(tallest[2] == tallest[1]);
    // The levels join: at the windows' zoom nothing is decoded; at a level's, its tile alone, once.
    std::vector<std::span<const std::byte>> windows(built.begin(), built.end());
    const auto made = load::build_levels(windows);
    ATLAS_REQUIRE(made.has_value(), made.error().what);
    ATLAS_REQUIRE(!made->empty());
    const tiles::TileKey coarse = (*made)[0].key;
    ATLAS_REQUIRE(coarse.z == 13);
    ATLAS_REQUIRE(add(coarse, write((*made)[0].pack, "13.atlas")).has_value());
    check(14, 0);
    CHECK(bits(last[0]) == bits(tallest[2]));
    std::ranges::fill(kept, sun::TallestByChunk{});
    check(13, 1);
    check(13, 0);
}
