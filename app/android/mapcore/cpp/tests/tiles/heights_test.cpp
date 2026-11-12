// The terrain's height rasters (tiles/Heights.hpp, ENGINE.md part 6): a tile's raster read back sample for sample, the
// same from a tile the packer's writer made (tests/tiles/data/heights-17.tile, which rendertiles' HeightsCodecTest
// holds its writer to, byte for byte); every refusal the reader owes (a bad length, range, lowest or highest sample,
// side, apron or step, columns missing, twice or in another codec, shares that do not add up); a raster cut to a finer
// tile's box; and the store, which holds terrain packs apart from the map's and finds a tile's raster at its own level
// or the nearest coarser one.
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Heights.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Store.hpp"
#include "tiles/WorldIndex.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <functional>
#include <iterator>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

using namespace atlas::tiles;
using atlas::test::ChunkBytes;
using atlas::test::LayerBytes;
using atlas::test::Sink;
using atlas::test::TileBytes;
namespace fs = std::filesystem;

constexpr auto med = static_cast<Codec>(heights_codec_med);

/// A folder of its own for one test, deleted when it ends.
class TempDir {
public:
    TempDir() {
        static std::atomic<int> serial{0};
        path_ = fs::temp_directory_path() / std::format("atlas-heights-test-{}-{}", ::getpid(), serial++);
        fs::create_directories(path_);
    }
    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;
    TempDir(TempDir&&) = delete;
    TempDir& operator=(TempDir&&) = delete;
    ~TempDir() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] std::string file(std::string_view name) const { return (path_ / name).string(); }

private:
    fs::path path_;
};

void write_file(const std::string& path, std::span<const std::byte> bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
}

std::vector<std::byte> read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    const std::vector<char> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> out(bytes.size());
    std::ranges::transform(bytes, out.begin(), [](char c) { return static_cast<std::byte>(c); });
    return out;
}

/// A raster to write: its numbers and its samples in whole steps (not yet above their base), w × w.
struct Raster {
    std::uint32_t side = 17;
    std::uint32_t apron = 8;
    double step = 0.0625;
    std::vector<std::int64_t> steps;
    std::vector<std::string> sources = {"ahn4", "glo30"};
    std::vector<std::int64_t> shares = {1000, 80};
    std::uint64_t flags = heights_filled;

    [[nodiscard]] std::uint32_t width() const { return side + (2 * apron); }
    [[nodiscard]] std::int64_t base() const { return *std::ranges::min_element(steps); }
    [[nodiscard]] std::int64_t max() const { return *std::ranges::max_element(steps) - base(); }
};

/// The test's surface, in whole steps: the same in rendertiles' HeightsCodecTest, whose writer made the data file.
std::int64_t surface(std::int64_t r, std::int64_t c) {
    const std::int64_t m =
        ((3 * r * r) + (5 * c * c) + (7 * r * c)) % 97; // never negative: the form is positive definite
    return -40 + m + (2 * (r + 8)) + (c + 8);
}

Raster raster_of(std::uint32_t side, std::uint32_t apron,
                 const std::function<std::int64_t(std::int64_t, std::int64_t)>& f) {
    Raster out;
    out.side = side;
    out.apron = apron;
    const auto a = static_cast<std::int64_t>(apron);
    const auto n = static_cast<std::int64_t>(side);
    for (std::int64_t r = -a; r < n + a; ++r) {
        for (std::int64_t c = -a; c < n + a; ++c) out.steps.push_back(f(r, c));
    }
    const auto total = static_cast<std::int64_t>(out.steps.size());
    out.shares = {total - 9, 4};
    return out;
}

/// The `samples` column of `v` (w a row), as HeightsCodec.med writes it.
std::vector<std::byte> med_bytes(const std::vector<std::int64_t>& v, std::size_t w) {
    Sink s;
    s.varu(w);
    s.varu(w);
    for (std::size_t r = 0; r < w; ++r) {
        for (std::size_t c = 0; c < w; ++c) {
            const std::size_t at = (r * w) + c;
            const std::int64_t a = c > 0 ? v[at - 1] : (r > 0 ? v[at - w] : 0);
            const std::int64_t b = r > 0 ? v[at - w] : a;
            const std::int64_t d = (r > 0 && c > 0) ? v[at - w - 1] : b;
            const std::int64_t p =
                d >= std::max(a, b) ? std::min(a, b) : (d <= std::min(a, b) ? std::max(a, b) : a + b - d);
            s.varz(v[at] - p);
        }
    }
    return s.bytes;
}

/// The columns of `raster`, each by its name, for a test to change before the tile is written.
struct Columns {
    std::vector<atlas::test::ColumnBytes> list;

    atlas::test::ColumnBytes& operator[](std::string_view name) {
        return *std::ranges::find(list, name, &atlas::test::ColumnBytes::name);
    }
    void drop(std::string_view name) {
        std::erase_if(list, [&](const auto& c) { return c.name == name; });
    }
};

Columns columns_of(TileBytes& t, const Raster& raster) {
    namespace col = atlas::test::columns;
    std::vector<std::int64_t> above(raster.steps.size());
    const std::int64_t base = raster.base();
    std::ranges::transform(raster.steps, above.begin(), [&](std::int64_t s) { return s - base; });
    Sink step;
    step.f64(raster.step);
    Sink sources;
    sources.varu(raster.sources.size() + 1);
    for (const auto& s : raster.sources) sources.varu(t.string(s));
    Sink shares;
    shares.varu(raster.shares.size());
    std::int64_t previous = 0;
    for (const std::int64_t s : raster.shares) {
        shares.varz(s - previous);
        previous = s;
    }
    return Columns{{{"side", Codec::varu, col::varu({raster.side})},
                    {"apron", Codec::varu, col::varu({raster.apron})},
                    {"step", Codec::f64, step.bytes},
                    {"base", Codec::varz, col::varz({base})},
                    {"max", Codec::varu, col::varu({static_cast<std::uint64_t>(raster.max())})},
                    {"flags", Codec::varu, col::varu({raster.flags})},
                    {"sources", Codec::strs, sources.bytes},
                    {"shares", Codec::ids, shares.bytes},
                    {"samples", med, med_bytes(above, raster.width())}}};
}

/// A terrain tile of `columns`: one layer, one feature in its first cell.
TileBytes tile_of(std::uint8_t level, std::uint32_t x, std::uint32_t y) {
    TileBytes t;
    t.level = level;
    t.n = cells_per_side(level);
    t.x = x;
    t.y = y;
    return t;
}

void put(TileBytes& t, const Columns& columns, std::uint32_t features = 1) {
    LayerBytes layer{.id = static_cast<std::uint8_t>(LayerId::heights)};
    layer.cells.assign(features, 0);
    layer.columns = columns.list;
    t.layers = {layer};
}

TileBytes terrain_tile(std::uint8_t level, std::uint32_t x, std::uint32_t y, const Raster& raster) {
    TileBytes t = tile_of(level, x, y);
    put(t, columns_of(t, raster));
    return t;
}

/// A refusal of the raster's reader as outcome() words it: the fault in its column, with its detail.
std::string refusal(Fault fault, std::string_view column, std::uint64_t detail = 0) {
    return describe(column_error(fault, LayerId::heights, column, detail));
}

/// What reading `t` gives: the raster's error, described, or "ok".
std::string outcome(TileBytes& t) {
    const std::vector<std::byte> raw = t.encode();
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto tile = decoder.decode(raw, arena);
    if (!tile) return "tile: " + describe(tile.error());
    const auto heights = read_heights(*tile, arena);
    return heights ? "ok" : describe(heights.error());
}

/// A terrain pack of `tiles` for `levels` (fine to coarse), as the packer's writer lays one out: the terrain's
/// profile, a style without tables.
std::vector<std::byte> terrain_pack(std::vector<TileBytes> tiles, std::span<const std::uint8_t> levels,
                                    Profile profile = Profile::terrain) {
    std::ranges::stable_sort(tiles, {},
                             [&](const TileBytes& t) { return std::ranges::find(levels, t.level) - levels.begin(); });
    std::vector<ChunkBytes> chunks;
    for (TileBytes& t : tiles) {
        ChunkBytes c;
        c.tile = {t.level, t.x, t.y};
        c.raw = t.encode();
        c.count = 1;
        c.reach = t.reach();
        chunks.push_back(std::move(c));
    }
    atlas::test::PackOptions options;
    options.head = atlas::test::head_bytes(levels, profile);
    if (profile == Profile::terrain) {
        Sink style;
        style.u32(style_magic);
        style.u16(payload_version);
        style.varu(0);
        options.style = style.bytes;
    }
    return atlas::test::pack_bytes(chunks, levels, format_render, options);
}

} // namespace

TEST_CASE("a terrain tile's raster reads back sample for sample") {
    const Raster raster = raster_of(17, 8, surface);
    TileBytes t = terrain_tile(14, 8414, 5384, raster);
    const std::vector<std::byte> raw = t.encode();
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    // the map's reader skips the layer and counts its one feature
    CHECK(tile->layers.empty());
    ATLAS_REQUIRE(tile->skipped.size() == 1);
    CHECK(tile->feature_count() == 1);
    CHECK(has_heights(*tile));
    const auto h = read_heights(*tile, arena);
    ATLAS_REQUIRE(h, describe(h.error()));
    CHECK(h->tile == TileKey(14, 8414, 5384));
    CHECK(h->side == 17);
    CHECK(h->apron == 8);
    CHECK(h->width() == 33);
    CHECK(h->step == 0.0625);
    CHECK(h->base == raster.base());
    CHECK(h->max == raster.max());
    CHECK(h->flags == heights_filled);
    ATLAS_REQUIRE(h->sources.size() == 2);
    CHECK(h->sources[0].id == "ahn4");
    CHECK(h->sources[0].samples == 1080);
    CHECK(h->sources[1].id == "glo30");
    CHECK(h->sources[1].samples == 4);
    for (std::int32_t r = -8; r < 25; ++r) {
        for (std::int32_t c = -8; c < 25; ++c) {
            if (h->steps(r, c) != surface(r, c)) FAIL_CHECK("sample " << r << ", " << c << " is " << h->steps(r, c));
        }
    }
    CHECK(h->metres(0, 0) == 0.0625 * static_cast<double>(surface(0, 0)));
    CHECK(h->lowest() == 0.0625 * static_cast<double>(raster.base()));
    CHECK(h->highest() == 0.0625 * static_cast<double>(raster.base() + raster.max()));

    SUBCASE("a tile without the layer has no raster") {
        TileBytes plain = tile_of(14, 8414, 5384);
        const std::vector<std::byte> bytes = plain.encode();
        const auto decoded = decoder.decode(bytes, arena);
        ATLAS_REQUIRE(decoded, describe(decoded.error()));
        CHECK(!has_heights(*decoded));
        const auto none = read_heights(*decoded, arena);
        ATLAS_REQUIRE(!none);
        CHECK(none.error().fault == Fault::missing);
    }
    SUBCASE("the columns a raster may leave out stand for none; one this reader does not know is skipped") {
        TileBytes bare = tile_of(14, 8414, 5384);
        Raster flat = raster_of(17, 0, [](std::int64_t, std::int64_t) { return std::int64_t{0}; });
        Columns columns = columns_of(bare, flat);
        for (const char* name : {"apron", "base", "flags", "sources", "shares"}) columns.drop(name);
        columns.list.push_back({"skyview", Codec::u8, atlas::test::columns::u8({1, 2, 3})});
        put(bare, columns);
        const std::vector<std::byte> bytes = bare.encode();
        const auto decoded = decoder.decode(bytes, arena);
        ATLAS_REQUIRE(decoded, describe(decoded.error()));
        const auto read = read_heights(*decoded, arena);
        ATLAS_REQUIRE(read, describe(read.error()));
        CHECK(read->apron == 0);
        CHECK(read->base == 0);
        CHECK(read->max == 0);
        CHECK(read->flags == 0);
        CHECK(read->sources.empty());
        CHECK(read->samples.size() == 17U * 17U);
    }
}

TEST_CASE("the packer's writer and the core's reader agree on a tile") {
    const std::vector<std::byte> raw = read_file(std::string(ATLAS_TESTS_DIR) + "/tiles/data/heights-17.tile");
    ATLAS_REQUIRE(!raw.empty(), "tests/tiles/data/heights-17.tile is not there");
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    CHECK(tile->header.level == 14);
    CHECK(tile->header.x == 8414);
    CHECK(tile->header.y == 5384);
    const auto h = read_heights(*tile, arena);
    ATLAS_REQUIRE(h, describe(h.error()));
    const Raster raster = raster_of(17, 8, surface);
    CHECK(h->side == 17);
    CHECK(h->apron == 8);
    CHECK(h->step == 0.0625);
    CHECK(h->base == raster.base());
    CHECK(h->max == raster.max());
    CHECK(h->flags == heights_filled);
    ATLAS_REQUIRE(h->sources.size() == 2);
    CHECK(h->sources[0].id == "ahn4");
    CHECK(h->sources[0].samples == 1080);
    CHECK(h->sources[1].id == "glo30");
    CHECK(h->sources[1].samples == 4);
    for (std::int32_t r = -8; r < 25; ++r) {
        for (std::int32_t c = -8; c < 25; ++c) {
            if (h->steps(r, c) != surface(r, c)) FAIL_CHECK("sample " << r << ", " << c << " is " << h->steps(r, c));
        }
    }
    // the tile's one cell holds the raster's top, which is its reach's
    const auto reach = reach_of(*tile);
    ATLAS_REQUIRE(reach, describe(reach.error()));
    CHECK(reach->top_cm == static_cast<std::int32_t>(std::ceil(h->highest() * 100.0)));
    // the same tile from this test's own writer hashes the same
    TileBytes mine = terrain_tile(14, 8414, 5384, raster);
    const std::vector<std::byte> bytes = mine.encode();
    const auto again = decoder.decode(bytes, arena);
    ATLAS_REQUIRE(again, describe(again.error()));
    const auto h2 = read_heights(*again, arena);
    ATLAS_REQUIRE(h2, describe(h2.error()));
    CHECK(heights_hash(*h) == heights_hash(*h2));
}

TEST_CASE("the raster's reader refuses a bad length, range, lowest and highest sample") {
    const Raster raster = raster_of(17, 8, surface);
    const auto with = [&](const std::function<void(TileBytes&, Columns&)>& change, std::uint32_t features = 1) {
        TileBytes t = tile_of(14, 8414, 5384);
        Columns columns = columns_of(t, raster);
        change(t, columns);
        put(t, columns, features);
        return outcome(t);
    };
    namespace col = atlas::test::columns;
    CHECK(with([](TileBytes&, Columns&) {}) == "ok");

    SUBCASE("lengths") {
        // a width that is not the side's and apron's, in the samples or in the side
        CHECK(with([](TileBytes&, Columns& c) { c["apron"].bytes = col::varu({7}); }) ==
              refusal(Fault::bad_count, "samples", 33));
        CHECK(with([](TileBytes&, Columns& c) { c["side"].bytes = col::varu({33}); }) ==
              refusal(Fault::bad_count, "samples", 33));
        // the samples cut short, and a byte after the last
        CHECK(with([](TileBytes&, Columns& c) {
                  c["samples"].bytes.resize(c["samples"].bytes.size() - 1);
              }).starts_with(refusal(Fault::truncated, "samples")));
        CHECK(with([](TileBytes&, Columns& c) {
                  c["samples"].bytes.resize(600);
              }).starts_with(refusal(Fault::truncated, "samples")));
        CHECK(with([](TileBytes&, Columns& c) { c["samples"].bytes.push_back(std::byte{0}); }) ==
              refusal(Fault::left_over, "samples", 1));
        // a byte after a column's one number
        CHECK(with([](TileBytes&, Columns& c) { c["max"].bytes.push_back(std::byte{0}); }) ==
              refusal(Fault::left_over, "max", 1));
        CHECK(with([](TileBytes&, Columns& c) { c["step"].bytes.pop_back(); }) == refusal(Fault::truncated, "step"));
        // two rasters in a tile
        CHECK(with([](TileBytes&, Columns&) {}, 2) == refusal(Fault::bad_count, "", 2));
    }
    SUBCASE("ranges") {
        CHECK(with([](TileBytes&, Columns& c) { c["side"].bytes = col::varu({18}); }) ==
              refusal(Fault::bad_value, "side", 18));
        CHECK(with([](TileBytes&, Columns& c) { c["side"].bytes = col::varu({513}); }) ==
              refusal(Fault::bad_value, "side", 513));
        CHECK(with([](TileBytes&, Columns& c) { c["side"].bytes = col::varu({2}); }) ==
              refusal(Fault::bad_value, "side", 2));
        CHECK(with([](TileBytes&, Columns& c) { c["apron"].bytes = col::varu({33}); }) ==
              refusal(Fault::too_big, "apron", 33));
        CHECK(with([](TileBytes&, Columns& c) { c["max"].bytes = col::varu({(1U << 28U) + 1U}); }) ==
              refusal(Fault::too_big, "max", 268435457));
        for (const double bad :
             {0.0, -0.5, std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()}) {
            CHECK(with([bad](TileBytes&, Columns& c) {
                      Sink s;
                      s.f64(bad);
                      c["step"].bytes = s.bytes;
                  }) == refusal(Fault::bad_value, "step"));
        }
        // a sample below the base, and one above the highest the tile names: its place in the raster
        CHECK(with([&](TileBytes&, Columns& c) {
                  std::vector<std::int64_t> above(raster.steps.size());
                  std::ranges::transform(raster.steps, above.begin(),
                                         [&](std::int64_t s) { return s - raster.base(); });
                  above[40] = -1;
                  c["samples"].bytes = med_bytes(above, 33);
              }) == refusal(Fault::bad_value, "samples", 40));
        CHECK(with([&](TileBytes&, Columns& c) {
                  std::vector<std::int64_t> above(raster.steps.size());
                  std::ranges::transform(raster.steps, above.begin(),
                                         [&](std::int64_t s) { return s - raster.base(); });
                  above[1000] = raster.max() + 1;
                  c["samples"].bytes = med_bytes(above, 33);
              }) == refusal(Fault::bad_value, "samples", 1000));
        // a residual that would wrap the sum
        const auto wrapping = [](TileBytes&, Columns& c) {
            Sink s;
            s.varu(33);
            s.varu(33);
            for (int i = 0; i < 33 * 33; ++i) s.varz(std::numeric_limits<std::int64_t>::max());
            c["samples"].bytes = s.bytes;
        };
        CHECK(with(wrapping) == refusal(Fault::bad_value, "samples"));
    }
    SUBCASE("the lowest and the highest sample") {
        // the highest the tile names is not the samples'
        CHECK(with([&](TileBytes&, Columns& c) {
                  c["max"].bytes = col::varu({static_cast<std::uint64_t>(raster.max()) + 1});
              }) == refusal(Fault::bad_value, "max", static_cast<std::uint64_t>(raster.max())));
        CHECK(with([&](TileBytes&, Columns& c) {
                  c["max"].bytes = col::varu({static_cast<std::uint64_t>(raster.max()) - 1});
              }).starts_with(refusal(Fault::bad_value, "samples")));
        // no sample stands at the base
        CHECK(with([&](TileBytes&, Columns& c) {
                  std::vector<std::int64_t> above(raster.steps.size());
                  std::ranges::transform(raster.steps, above.begin(),
                                         [&](std::int64_t s) { return s - raster.base() + 1; });
                  c["samples"].bytes = med_bytes(above, 33);
                  c["max"].bytes = col::varu({static_cast<std::uint64_t>(raster.max()) + 1});
              }) == refusal(Fault::bad_value, "base", 1));
    }
    SUBCASE("columns missing, twice, or in another codec") {
        for (const char* name : {"side", "step", "max", "samples"}) {
            CHECK(with([name](TileBytes&, Columns& c) { c.drop(name); }) == refusal(Fault::missing, name));
        }
        CHECK(with([](TileBytes&, Columns& c) {
                  const atlas::test::ColumnBytes side = c["side"];
                  c.list.push_back(side);
              }) == refusal(Fault::duplicate, "side"));
        CHECK(with([](TileBytes&, Columns& c) { c["samples"].codec = Codec::ids; }) ==
              refusal(Fault::bad_value, "samples", 13));
        CHECK(with([](TileBytes&, Columns& c) { c["base"].codec = Codec::varu; }) ==
              refusal(Fault::bad_value, "base", 2));
    }
    SUBCASE("shares that do not add up") {
        const auto shares = [](const std::vector<std::int64_t>& v) {
            Sink s;
            s.varu(v.size());
            std::int64_t previous = 0;
            for (const std::int64_t x : v) {
                s.varz(x - previous);
                previous = x;
            }
            return s.bytes;
        };
        CHECK(with([&](TileBytes&, Columns& c) { c["shares"].bytes = shares({1080}); }) ==
              refusal(Fault::bad_count, "shares", 1));
        CHECK(with([&](TileBytes&, Columns& c) { c.drop("shares"); }) == refusal(Fault::bad_count, "shares"));
        CHECK(with([&](TileBytes&, Columns& c) { c["shares"].bytes = shares({1080, 10}); }) ==
              refusal(Fault::bad_count, "shares", 1090));
        CHECK(with([&](TileBytes&, Columns& c) { c["shares"].bytes = shares({-1, 4}); }) ==
              refusal(Fault::bad_value, "shares"));
        CHECK(with([&](TileBytes& t, Columns& c) {
                  Sink s;
                  s.varu(3);
                  s.varu(t.string("ahn4"));
                  s.varu(9999);
                  c["sources"].bytes = s.bytes;
              }) == refusal(Fault::bad_value, "sources", 9999));
    }
}

TEST_CASE("a raster is cut to a finer tile's box") {
    // an L12 raster of 257 a side, its samples their own lattice column and row over the world
    const std::uint32_t x = 2103;
    const std::uint32_t y = 1346;
    const Raster raster = raster_of(257, 8, [&](std::int64_t r, std::int64_t c) {
        return ((std::int64_t{y} * 256) + r) * 100000 + (std::int64_t{x} * 256) + c;
    });
    TileBytes t = terrain_tile(12, x, y, raster);
    const std::vector<std::byte> raw = t.encode();
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    const auto h = read_heights(*tile, arena);
    ATLAS_REQUIRE(h, describe(h.error()));

    const auto self = cut_heights(*h, TileKey(12, x, y));
    ATLAS_REQUIRE(self.has_value());
    CHECK(self->side == 257);
    CHECK(self->steps(256, 256) == h->steps(256, 256));

    // the south-east L14 tile of its sixteen: 65 samples a side, every one a sample of the L12 lattice in its box
    const TileKey fine(14, (x * 4) + 3, (y * 4) + 3);
    const auto cut = cut_heights(*h, fine);
    ATLAS_REQUIRE(cut.has_value());
    CHECK(cut->side == 65);
    CHECK(cut->row == 192);
    CHECK(cut->column == 192);
    CHECK(cut->apron() == 8);
    // sample (r, c) of the cut lies at the L14 lattice's (4r, 4c) of a 257-side raster of that tile: the same point
    for (const std::int32_t r : {-8, 0, 31, 64, 72}) {
        for (const std::int32_t c : {-8, 0, 17, 64, 72}) {
            const std::int64_t row = (std::int64_t{y} * 256) + 192 + r;
            const std::int64_t column = (std::int64_t{x} * 256) + 192 + c;
            CHECK(cut->steps(r, c) == (row * 100000) + column);
            CHECK(cut->metres(r, c) == h->step * static_cast<double>((row * 100000) + column));
        }
    }
    // a tile elsewhere, a coarser one, and one too deep for two samples a side have none
    CHECK(!cut_heights(*h, TileKey(14, (x * 4) + 4, y * 4)).has_value());
    CHECK(!cut_heights(*h, TileKey(11, x / 2, y / 2)).has_value());
    CHECK(cut_heights(*h, TileKey(20, x << 8U, y << 8U)).has_value());
    CHECK(!cut_heights(*h, TileKey(21, x << 9U, y << 9U)).has_value());
}

TEST_CASE("the store holds terrain packs apart from the map's and finds a tile's raster") {
    const TempDir dir;
    constexpr std::array<std::uint8_t, 3> levels = {14, 13, 12};
    const auto flat = [](std::int64_t level) {
        return [level](std::int64_t r, std::int64_t c) { return (level * 1000) + r + c; };
    };
    // an L9 block's rasters: its L12 tile, one L13 tile and one L14 tile under it
    std::vector<TileBytes> tiles;
    tiles.push_back(terrain_tile(12, 2103, 1346, raster_of(17, 8, flat(12))));
    tiles.push_back(terrain_tile(13, 4206, 2692, raster_of(17, 8, flat(13))));
    tiles.push_back(terrain_tile(14, 8412, 5384, raster_of(17, 8, flat(14))));
    const std::vector<std::byte> pack = terrain_pack(tiles, levels);
    // the part: bytes before the pack, as a pack after another lies in its part
    std::vector<std::byte> part(321, std::byte{0x5A});
    part.insert(part.end(), pack.begin(), pack.end());
    write_file(dir.file("nl-terrain.000"), part);

    Store store;
    const PackRef ref{.id = "terrain/nl/9/262/168",
                      .kind = PackKind::terrain_country,
                      .path = dir.file("nl-terrain.000"),
                      .offset = 321,
                      .length = pack.size()};
    const auto added = store.add(ref);
    ATLAS_REQUIRE(added, describe(added.error()));
    // the map sees none of it
    CHECK(store.packs().empty());
    CHECK(store.find(TileKey(14, 8412, 5384)).empty());
    CHECK(store.reaching(14, GridBox{.south = -900000000, .west = -1800000000, .north = 900000000, .east = 1800000000})
              .empty());
    ATLAS_REQUIRE(store.terrain_packs().size() == 1);
    CHECK(store.terrain_packs()[0]->kind == PackKind::terrain_country);
    CHECK(store.terrain_packs()[0]->pack.head()->profile == Profile::terrain);

    Zstd zstd;
    TileDecoder decoder;
    std::vector<std::byte> raw;
    const auto level_of = [&](TileKey tile) -> std::optional<std::int64_t> {
        const auto hit = store.heights(tile);
        if (!hit) return std::nullopt;
        atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
        const auto decoded = store.decode(*hit, zstd, decoder, arena, raw);
        if (!decoded) return -1;
        const auto h = read_heights(*decoded, arena);
        if (!h || h->tile != hit->chunk->tile || !cut_heights(*h, tile)) return -1;
        return h->steps(0, 0) / 1000;
    };
    // its own level; the nearest coarser one where its own is not built; none outside the block
    CHECK(level_of(TileKey(14, 8412, 5384)) == 14);
    CHECK(level_of(TileKey(14, 8413, 5384)) == 13);
    CHECK(level_of(TileKey(14, 8415, 5387)) == 12);
    CHECK(level_of(TileKey(13, 4206, 2692)) == 13);
    CHECK(level_of(TileKey(13, 4207, 2693)) == 12);
    CHECK(level_of(TileKey(12, 2103, 1346)) == 12);
    CHECK(level_of(TileKey(16, 8412 * 4, 5384 * 4)) == 14);
    CHECK(!level_of(TileKey(11, 1051, 673)).has_value());
    CHECK(!level_of(TileKey(14, 8416, 5384)).has_value());

    SUBCASE("a pack is terrain exactly when it is listed as terrain, and of its kind's levels") {
        write_file(dir.file("pack.atlas"), pack);
        Store other;
        CHECK(!other.add({.id = "a", .kind = PackKind::render, .path = dir.file("pack.atlas")}));
        CHECK(!other.add({.id = "b", .kind = PackKind::world_mid, .path = dir.file("pack.atlas")}));
        write_file(dir.file("map.atlas"), terrain_pack(tiles, levels, Profile::region));
        CHECK(!other.add({.id = "c", .kind = PackKind::terrain_country, .path = dir.file("map.atlas")}));
        constexpr std::array<std::uint8_t, 1> eleven = {11};
        write_file(dir.file("eleven.atlas"),
                   terrain_pack({terrain_tile(11, 1051, 673, raster_of(17, 8, flat(11)))}, eleven));
        const auto refused =
            other.add({.id = "d", .kind = PackKind::terrain_country, .path = dir.file("eleven.atlas")});
        ATLAS_REQUIRE(!refused);
        CHECK(refused.error().site == Site::head);
        CHECK(other.packs().empty());
        CHECK(other.terrain_packs().empty());
    }
    SUBCASE("removing it leaves no raster") {
        CHECK(store.remove("terrain/nl/9/262/168"));
        CHECK(store.terrain_packs().empty());
        CHECK(!store.heights(TileKey(14, 8412, 5384)).has_value());
        CHECK(!store.remove("terrain/nl/9/262/168"));
    }
    SUBCASE("add_terrain adds every pack of a country's index as a range of its part") {
        Sink index;
        index.raw(as_bytes(world_index_magic));
        index.u16(world_index_version);
        index.u16(0);
        index.u64(1794000000);
        index.u16(1);
        index.u8(14);
        index.raw(as_bytes("nl-terrain.000"));
        index.u64(part.size());
        index.u32(1);
        index.u8(9);
        index.u8(12);
        index.u8(14);
        index.u8(0);
        index.u32(262);
        index.u32(168);
        index.u64(321);
        index.u64(pack.size());
        index.u32(3);
        for (int i = 0; i < 4; ++i) index.u32(0);
        index.raw(Sha256::of(pack));
        write_file(dir.file("nl-terrain.idx"), index.bytes);
        Store fresh;
        const auto all = add_terrain(fresh, dir.file("nl-terrain.idx"));
        ATLAS_REQUIRE(all, describe(all.error()));
        CHECK(all->packs == 1);
        CHECK(all->tiles == 3);
        ATLAS_REQUIRE(fresh.terrain_packs().size() == 1);
        CHECK(fresh.terrain_packs()[0]->id == "terrain/nl-terrain/9/262/168");
        CHECK(fresh.heights(TileKey(14, 8413, 5384)).has_value());
        CHECK(!add_terrain(fresh, dir.file("none.idx")));
    }
}
