// The sun worker on the bundled render packs (plan-vector K8), when ATLAS_TILES_DIR names a folder holding them
// (Gradle's bundled tiles, or the packs CI keeps): the bundled check over the Westerkerk equals the host build's hash
// (bundled_check_expected, which the app's VK facts holds the emulator's Bionic to), and with ATLAS_SUN_EXPECT naming
// the JVM's sun-bits.txt (MT/sun/SunParityTest.kt: `sun bits <pack> <level> <sun x y z bits> <sha256>`, the boards'
// suns and levels), every scene's bits equal the JVM's, on whichever host runs this.
#include "sun/Buckets.hpp"
#include "sun/Samples.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cstdio>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

using namespace atlas::sun;
namespace tl = atlas::tiles;

/// The SHA-256 over each tile's key, sample count and bits in the pack's order, as TestSun.cpp's `bits` hashes them.
std::string bits_hash(const tl::Pack& pack, std::uint8_t level, const Sun& sun, std::uint64_t& samples) {
    const tl::RenderHead& head = *pack.head();
    const Region region = region_of(*pack.style());
    const std::uint8_t z = head.levels.front();
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "sun packs test");
    std::vector<Deck> decks;
    for (const tl::ChunkRef& c : pack.chunks().level(z)) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return "error " + tl::describe(tile.error());
        collect_decks(*tile, head, level, decks);
    }
    std::vector<SunTile> tiles;
    for (const tl::ChunkRef& c : pack.chunks().level(z)) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return "error " + tl::describe(tile.error());
        tiles.push_back(make_tile(*tile, head, region, level, decks));
    }
    const auto maxh = scene_maxh(pack, level);
    if (!maxh) return "error " + tl::describe(maxh.error());
    const Scene scene(std::move(tiles), *maxh);
    std::vector<TileJob> jobs;
    for (std::uint32_t t = 0; t < scene.tiles().size(); ++t) jobs.push_back({t, true});
    std::vector<TileBits> got(scene.tiles().size());
    std::mutex m;
    run(scene, bucket_of(sun, true), jobs, 4, [&](std::uint32_t t, TileBits bits) {
        const std::lock_guard lock(m);
        got[t] = std::move(bits);
    });
    tl::Sha256 sha;
    samples = 0;
    for (std::size_t t = 0; t < scene.tiles().size(); ++t) {
        const std::array<std::uint64_t, 4> h{scene.tiles()[t].key.packed(), got[t].count, 0, 0};
        sha.update(std::as_bytes(std::span(h)));
        sha.update(std::as_bytes(std::span(got[t].words)));
        samples += got[t].count;
    }
    return tl::hex(sha.finish());
}

/// The scene of `pack` at `level` over the tiles `keys` (every tile of its finest level when empty), with the decks of
/// the same tiles.
std::unique_ptr<Scene> scene_of(const tl::Pack& pack, std::uint8_t level, std::span<const tl::TileKey> keys) {
    const tl::RenderHead& head = *pack.head();
    const Region region = region_of(*pack.style());
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "sun halo test");
    std::vector<const tl::ChunkRef*> chunks;
    for (const tl::ChunkRef& c : pack.chunks().level(head.levels.front())) {
        if (keys.empty() || std::ranges::find(keys, c.tile) != keys.end()) chunks.push_back(&c);
    }
    std::vector<Deck> decks;
    for (const tl::ChunkRef* c : chunks) {
        arena.reset();
        auto tile = pack.decode(*c, zstd, decoder, arena, raw, scratch);
        if (!tile) return nullptr;
        collect_decks(*tile, head, level, decks);
    }
    std::vector<SunTile> tiles;
    for (const tl::ChunkRef* c : chunks) {
        arena.reset();
        auto tile = pack.decode(*c, zstd, decoder, arena, raw, scratch);
        if (!tile) return nullptr;
        tiles.push_back(make_tile(*tile, head, region, level, decks));
    }
    const auto maxh = scene_maxh(pack, level); // the scene's tallest solid: the whole pack's, whatever tiles it holds
    if (!maxh) return nullptr;
    return std::make_unique<Scene>(std::move(tiles), *maxh);
}

/// Every tile's bits of `scene` for `sun`, windows tested, by tile.
std::map<std::uint64_t, std::vector<std::uint64_t>> bits_of(const Scene& scene, const Sun& sun,
                                                            std::span<const tl::TileKey> only = {}) {
    std::vector<TileJob> jobs;
    for (std::uint32_t t = 0; t < scene.tiles().size(); ++t) {
        if (only.empty() || std::ranges::find(only, scene.tiles()[t].key) != only.end()) jobs.push_back({t, true});
    }
    std::map<std::uint64_t, std::vector<std::uint64_t>> out;
    std::mutex m;
    run(scene, bucket_of(sun, true), jobs, 4, [&](std::uint32_t t, TileBits bits) {
        const std::lock_guard lock(m);
        out[scene.tiles()[t].key.packed()] = std::move(bits.words);
    });
    return out;
}

} // namespace

TEST_CASE("sun: a tile's bits from its 220 m halo alone are its bits in the whole pack") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    auto pack = atlas::test::open_pack((std::filesystem::path(dir) / "grachten.atlas").string());
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const auto view = pack->head()->view("grachten_home");
    ATLAS_REQUIRE(view.has_value());
    const std::uint8_t level = view->tolerance;
    const auto whole = scene_of(*pack, level, {});
    ATLAS_REQUIRE(whole != nullptr);
    const Sun sun = sun_vector(220.0, 24.0); // a low afternoon sun: long marches
    const auto all = bits_of(*whole, sun);
    // three resident tiles: the one with the most solids, the first and the last
    const auto tiles = whole->tiles();
    std::size_t densest = 0;
    for (std::size_t t = 1; t < tiles.size(); ++t) {
        if (tiles[t].solids.size() > tiles[densest].solids.size()) densest = t;
    }
    for (const std::size_t r : {densest, std::size_t{0}, tiles.size() - 1}) {
        const std::array<tl::TileKey, 1> resident{tiles[r].key};
        const std::vector<tl::TileKey> halo = halo_of(*pack, resident);
        CHECK(std::ranges::find(halo, tiles[r].key) != halo.end());
        CHECK(halo.size() < tiles.size());
        const auto part = scene_of(*pack, level, halo);
        ATLAS_REQUIRE(part != nullptr);
        const auto got = bits_of(*part, sun, resident);
        ATLAS_REQUIRE(got.size() == 1);
        MESSAGE("tile ", tiles[r].key.x, "/", tiles[r].key.y, ": ", tiles[r].samples.size(), " samples, a halo of ",
                halo.size(), " of ", tiles.size(), " tiles");
        CHECK(got.begin()->second == all.at(tiles[r].key.packed()));
    }
}

TEST_CASE("sun: the bundled packs' bits are the host build's") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    const std::string westerkerk = (std::filesystem::path(dir) / "amsterdam.atlas").string();
    auto pack = atlas::test::open_pack(westerkerk);
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    const auto t0 = std::chrono::steady_clock::now();
    const std::string line = sun_line(*pack);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    MESSAGE(line, " in ", ms, " ms");
    (void)std::printf("%s (%.0f ms)\n", line.c_str(), ms); // NOLINT: the line CI's logs keep
    CHECK(line.ends_with(" ok"));
    {
        // as the app hands it over: a range of an open file (an APK's stored asset), at an offset no page's
        const std::filesystem::path copy = std::filesystem::path(ATLAS_TEST_SCRATCH) / "sun-asset.bin";
        std::filesystem::create_directories(copy.parent_path());
        std::ifstream from(westerkerk, std::ios::binary);
        std::ostringstream bytes;
        bytes << from.rdbuf();
        const std::string body = bytes.str();
        constexpr std::size_t lead = 1234;
        {
            std::ofstream to(copy, std::ios::binary | std::ios::trunc);
            to << std::string(lead, 'x') << body << "trailing bytes of another asset";
        }
        const int fd = ::open(copy.c_str(), O_RDONLY | O_CLOEXEC); // NOLINT(cppcoreguidelines-pro-type-vararg)
        ATLAS_REQUIRE(fd >= 0);
        CHECK(sun_line(fd, lead, body.size()) == line);
        (void)::close(fd);
        std::filesystem::remove(copy);
    }
    const std::string expect = atlas::test::environment("ATLAS_SUN_EXPECT");
    if (expect.empty()) {
        MESSAGE("ATLAS_SUN_EXPECT is not set: the boards' scenes are not held to the JVM's bits here");
        return;
    }
    std::ifstream in(expect);
    ATLAS_REQUIRE(in.good(), "ATLAS_SUN_EXPECT names no readable file: " << expect);
    int checked = 0;
    for (std::string l; std::getline(in, l);) {
        if (!l.starts_with("sun bits ")) continue;
        std::istringstream words(l.substr(9));
        std::string name;
        int level = 0;
        std::string sx;
        std::string sy;
        std::string sz;
        std::string want;
        words >> name >> level >> sx >> sy >> sz >> want;
        const Sun sun{.x = std::bit_cast<double>(std::stoull(sx, nullptr, 16)),
                      .y = std::bit_cast<double>(std::stoull(sy, nullptr, 16)),
                      .z = std::bit_cast<double>(std::stoull(sz, nullptr, 16))};
        auto p = atlas::test::open_pack((std::filesystem::path(dir) / (name + ".atlas")).string());
        ATLAS_REQUIRE(p.has_value(), name << ": " << tl::describe(p.error()));
        std::uint64_t samples = 0;
        const std::string got = bits_hash(*p, static_cast<std::uint8_t>(level), sun, samples);
        MESSAGE(name, " level ", level, ": ", samples, " samples, bits ", got);
        CHECK_MESSAGE(got == want, name << " level " << level << ": " << got << ", the JVM's " << want);
        ++checked;
    }
    CHECK(checked > 0);
}
