// Writes the fuzz targets' seed corpora (plan-vector T10: corpora start from the bundled tiles) when ATLAS_TILES_SEEDS
// names a folder and ATLAS_TILES_DIR the bundled packs: fuzz_payload gets every real tile under 8 KB and a synthetic
// one with every codec; fuzz_container small packs of real tiles in its modes and an element pack; fuzz_cache entries
// of a real chunk, a damaged one, and an operation sequence. Each file is named by its SHA-256. The test empties those
// three folders of their old seeds first and touches nothing else under ATLAS_TILES_SEEDS: other targets' seeds (K0's
// fuzz_varint) stay. The repository keeps them in fuzz/corpus/<target>/ (about 40 KB), which CI's fuzz job starts from:
//   ATLAS_TILES_DIR=<bundled packs> ATLAS_TILES_SEEDS=<cpp>/fuzz/corpus atlas_tests -tc='tiles: the fuzz*'
// With ATLAS_TILES_WHOLE also set, the Westerkerk's whole pack joins fuzz_container's seeds (for local runs).
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/ExpandedCache.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <filesystem>
#include <fstream>

namespace {

using namespace atlas::tiles;
namespace fs = std::filesystem;
namespace col = atlas::test::columns;

void seed(const fs::path& dir, std::span<const std::byte> bytes) {
    fs::create_directories(dir);
    std::ofstream out(dir / hex(Sha256::of(bytes)).substr(0, 16), std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
}

std::vector<std::byte> with_mode(unsigned mode, std::span<const std::byte> bytes) {
    std::vector<std::byte> out{static_cast<std::byte>(mode)};
    out.insert(out.end(), bytes.begin(), bytes.end());
    return out;
}

/// A small tile with every codec the schema uses: buildings sharing a wall, a tree with a house number, an item's tags.
std::vector<std::byte> synthetic_tile() {
    atlas::test::TileBytes t;
    const std::int32_t s = 523'740'000;
    const std::int32_t w = 48'790'000;
    const std::vector<atlas::test::Ring> rings = {
        {{s, s, s + 300, s + 300, s + 150, s + 100}, {w, w + 400, w + 400, w, w - 50, w - 60}, {13, 0, 5, 13, 2, 13}},
        {{s + 300, s, s - 10, s - 20, s + 310, s + 320},
         {w + 400, w + 400, w + 900, w + 1000, w + 950, w + 70000},
         {13, 13, 1, 0, 9, 13}},
    };
    atlas::test::Sink extra;
    extra.u8(8);
    extra.varu(1);
    extra.varu(t.string("levels"));
    extra.u8(7);
    extra.varu(2);
    extra.u8(5);
    extra.f64(2.5);
    extra.u8(4);
    extra.varz(-9);
    extra.u8(0);
    atlas::test::LayerBytes b{.id = static_cast<std::uint8_t>(LayerId::building), .cells = {0, 9}};
    b.columns = {
        {"index", Codec::delta, col::delta({3, 7})},
        {"id", Codec::delta, col::delta({100, 200})},
        {"ring", Codec::geom, col::geom(rings)},
        {"ring.keep", Codec::keep, col::keep(rings)},
        {"h", Codec::centi, col::centi({12.5, 1.0 / 3.0})},
        {"year", Codec::nvarz, col::nvarz({1650, std::nullopt})},
        {"roof", Codec::str, col::str(t, {"gabled", std::nullopt})},
        {"hidden", Codec::boolean, col::boolean({false, true})},
        {"guessed", Codec::strs, col::strs(t, {std::nullopt, std::vector<std::string>{"height"}})},
        {"places", Codec::ids, col::ids({{5, 3}, {}})},
        {"flags", Codec::varu, col::varu({16, 208})},
        {"wall_rgb", Codec::varu, col::varu({0xA0522D, 0xFFFFFF})},
        {"extra", Codec::value, extra.bytes}, // a column the schema lacks: skipped
    };
    t.layers.push_back(b);
    atlas::test::LayerBytes trees{.id = static_cast<std::uint8_t>(LayerId::tree), .cells = {5, 5}};
    trees.columns = {
        {"pos", Codec::point, col::point({{s + 1, w + 1}, {s + 2, w - 2}})},
        {"tag:addr:housenumber", Codec::strn, col::strn(t, {"12", "12a"})},
    };
    t.layers.push_back(trees);
    atlas::test::LayerBytes items{.id = static_cast<std::uint8_t>(LayerId::item), .cells = {9}};
    items.columns = {
        {"pos", Codec::point, col::point({{s + 7, w + 7}})},
        {"x", Codec::centi, col::centi({-12.25})},
        {"tags", Codec::tags, col::tags(t, {{{"man_made", "tower"}, {"height", "43"}}})},
    };
    t.layers.push_back(items);
    atlas::test::LayerBytes things{.id = static_cast<std::uint8_t>(LayerId::thing), .cells = {63}};
    things.columns = {
        {"pos", Codec::point, col::point({{s - 9, w + 9}})},
        {"door_number", Codec::ndelta, col::ndelta({112})},
        {"building", Codec::nvarz, col::nvarz({7})},
    };
    t.layers.push_back(things);
    return t.encode();
}

TEST_CASE("tiles: the fuzz targets' seed corpora (written when ATLAS_TILES_SEEDS is set)") {
    const std::string out = atlas::test::environment("ATLAS_TILES_SEEDS");
    const std::string packs = atlas::test::environment("ATLAS_TILES_DIR");
    if (out.empty() || packs.empty()) {
        MESSAGE("ATLAS_TILES_SEEDS or ATLAS_TILES_DIR is not set: no seeds written");
        return;
    }
    const fs::path root(out);
    for (const char* target : {"fuzz_payload", "fuzz_container", "fuzz_cache"}) {
        std::error_code ec;
        std::vector<fs::path> old;
        for (const auto& f : fs::directory_iterator(root / target, ec)) {
            if (f.is_regular_file()) old.push_back(f.path());
        }
        for (const fs::path& f : old) fs::remove(f, ec);
    }
    Zstd zstd;
    std::vector<std::pair<std::vector<std::byte>, ChunkRef>> tiles; // every real tile, raw, and its chunk
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack((fs::path(packs) / std::format("{}.atlas", name)).string());
        ATLAS_REQUIRE(pack);
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        for (const ChunkRef& c : pack->chunks().chunks()) {
            ATLAS_REQUIRE(pack->unpack(c, zstd, raw, scratch));
            tiles.emplace_back(raw, c);
        }
    }
    std::ranges::sort(tiles, {}, [](const auto& t) { return t.first.size(); });
    auto chunk = [](const std::pair<std::vector<std::byte>, ChunkRef>& t) {
        return atlas::test::ChunkBytes{
            .tile = t.second.tile, .raw = t.first, .count = t.second.count, .reach = t.second.reach};
    };

    // fuzz_payload: every real tile under 8 KB (a dozen, from 254 bytes to under 5 KB, both profiles, L14 and L12), and
    // the synthetic one
    for (const auto& [raw, tile] : tiles) {
        if (raw.size() < 8'192) seed(root / "fuzz_payload", raw);
    }
    seed(root / "fuzz_payload", synthetic_tile());

    // fuzz_container: packs of one and of three small tiles in each mode, an element pack, the Westerkerk's whole pack
    constexpr std::array<std::uint8_t, 2> levels = {14, 12};
    std::vector<atlas::test::ChunkBytes> one = {chunk(tiles[0])};
    std::vector<atlas::test::ChunkBytes>
        three; // the two smallest L14 tiles of the canal ring and its smallest L12 tile
    for (const auto& t : tiles) {
        const bool wanted =
            (t.second.tile.z == 14 && std::ranges::count_if(three, [](const auto& c) { return c.tile.z == 14; }) < 2) ||
            (t.second.tile.z == 12 && std::ranges::none_of(three, [](const auto& c) { return c.tile.z == 12; }));
        if (wanted) three.push_back(chunk(t));
    }
    std::ranges::sort(three, {}, [](const auto& c) { return c.tile.z == 14 ? 0 : 1; }); // rhead's level order
    for (const unsigned mode : {0U, 1U, 3U, 4U, 7U})
        seed(root / "fuzz_container", with_mode(mode, atlas::test::pack_bytes(one, levels)));
    for (const unsigned mode : {0U, 7U})
        seed(root / "fuzz_container", with_mode(mode, atlas::test::pack_bytes(three, levels)));
    seed(root / "fuzz_container", with_mode(0, atlas::test::pack_bytes(one, levels, format_elements)));
    if (!atlas::test::environment("ATLAS_TILES_WHOLE").empty()) {
        std::ifstream in(fs::path(packs) / "amsterdam.atlas", std::ios::binary);
        std::vector<char> whole((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        seed(root / "fuzz_container", with_mode(0, std::as_bytes(std::span(whole))));
    }

    // fuzz_cache: entries of a real tile (good, and damaged), operation sequences, an entry checked on its own
    const CacheKey key{.kind = CacheKind::chunk, .source = Sha256::of(tiles[0].first)};
    const auto entry = ExpandedCache::encode(key, tiles[0].first);
    seed(root / "fuzz_cache", with_mode(0, entry));
    seed(root / "fuzz_cache", with_mode(2, entry));
    auto damaged = entry;
    damaged[cache_header_bytes + 5] ^= std::byte{1};
    seed(root / "fuzz_cache", with_mode(2, damaged));
    const std::vector<std::uint8_t> ops = {0x00, 40,   0x04, 60,   0x08, 80, 0x01, 0x05, 0x0C,
                                           100,  0x10, 120,  0x09, 0x03, 50, 0x06, 0x00, 200};
    seed(root / "fuzz_cache", with_mode(1, std::as_bytes(std::span(ops))));
    MESSAGE("seeds written to " << out);
}

} // namespace
