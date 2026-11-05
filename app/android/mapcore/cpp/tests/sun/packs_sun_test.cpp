// The sun worker on the bundled render packs (plan-vector K8), when ATLAS_TILES_DIR names a folder holding them
// (Gradle's bundled tiles, or the packs CI keeps): the bundled check over the Westerkerk equals the host build's hash
// (bundled_check_expected, which the app's VK facts holds the emulator's Bionic to), and with ATLAS_SUN_EXPECT naming
// the JVM's sun-bits.txt (MT/sun/SunParityTest.kt: `sun bits <pack> <level> <sun x y z bits> <sha256>`, the boards'
// suns and levels), every scene's bits equal the JVM's, on whichever host runs this. A scene made as the sun's builder
// makes it (each tile decoded once, its halo's tiles their solids alone, one made whole as it comes resident) gives
// its tiles the bits a scene of every tile whole gives them.
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
#include <optional>
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

TEST_CASE(
    "sun: a scene as the sun's builder makes it, each tile decoded once and its halo's tiles their solids alone, "
    "gives a resident tile its bits; a halo tile made whole in it then gets the bits it had with every tile whole") {
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
    const tl::RenderHead& head = *pack->head();
    const Region region = region_of(*pack->style());
    const auto whole = scene_of(*pack, level, {});
    ATLAS_REQUIRE(whole != nullptr);
    // the resident tile: the one with the most solids, and its halo
    const auto all_tiles = whole->tiles();
    std::size_t densest = 0;
    for (std::size_t t = 1; t < all_tiles.size(); ++t) {
        if (all_tiles[t].solids.size() > all_tiles[densest].solids.size()) densest = t;
    }
    const tl::TileKey resident = all_tiles[densest].key;
    const std::array<tl::TileKey, 1> wanted{resident};
    const std::vector<tl::TileKey> halo = halo_of(*pack, wanted);
    ATLAS_REQUIRE(halo.size() > 1);
    // the scene of the halo as it was built, every tile made whole (make_tile, each decoded twice)
    const auto every = scene_of(*pack, level, halo);
    ATLAS_REQUIRE(every != nullptr);
    // as the builder makes it: each tile decoded once, its decks taken and the rest of it started (start_tile) or its
    // solids alone (solids_tile), and the resident tile's quay walls once every deck is known (finish_tile)
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "sun builder test");
    std::vector<Deck> decks;
    std::vector<SunTile> made;
    std::optional<TileStart> start;
    std::size_t at = 0;
    for (std::size_t i = 0; i < halo.size(); ++i) {
        const tl::ChunkRef* c = pack->chunks().find(halo[i]);
        ATLAS_REQUIRE(c != nullptr);
        arena.reset();
        auto tile = pack->decode(*c, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        collect_decks(*tile, head, level, decks);
        if (halo[i] == resident) {
            start = start_tile(*tile, head, region, level);
            made.emplace_back();
            at = i;
        } else {
            made.push_back(solids_tile(*tile, head.frame, level));
        }
    }
    ATLAS_REQUIRE(start.has_value());
    made[at] = finish_tile(std::move(*start), region, decks);
    const auto maxh = scene_maxh(*pack, level);
    ATLAS_REQUIRE(maxh.has_value());
    const auto built = std::make_shared<const Scene>(std::move(made), *maxh);
    ATLAS_REQUIRE(built->tiles().size() == every->tiles().size());
    for (std::size_t i = 0; i < halo.size(); ++i) {
        const SunTile& t = built->tiles()[i];
        const SunTile& e = every->tiles()[i];
        CHECK(t.key == e.key);
        CHECK(t.whole == (i == at));
        CHECK(t.solids.size() == e.solids.size()); // the grid holds the same solids
        if (i == at) CHECK(t.samples.size() == e.samples.size());
    }
    const Sun sun = sun_vector(220.0, 24.0); // a low afternoon sun: long marches
    const auto before = bits_of(*every, sun);
    const auto got = bits_of(*built, sun, wanted);
    ATLAS_REQUIRE(got.size() == 1);
    CHECK(got.at(resident.packed()) == before.at(resident.packed()));
    // A tile of the halo comes resident: made whole in the scene as built (its grid shared), its bits are those it
    // had with every tile whole, and the resident tile's stay.
    std::size_t next = halo.size();
    for (std::size_t i = 0; i < halo.size(); ++i) {
        if (i != at && (next == halo.size() || every->tiles()[i].samples.size() > every->tiles()[next].samples.size()))
            next = i;
    }
    ATLAS_REQUIRE(next < halo.size());
    ATLAS_REQUIRE(every->tiles()[next].samples.size() > 0);
    std::vector<SunTile> again(built->tiles().begin(), built->tiles().end());
    {
        const tl::ChunkRef* c = pack->chunks().find(halo[next]);
        arena.reset();
        auto tile = pack->decode(*c, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        again[next] = make_tile(*tile, head, region, level, decks);
    }
    const Scene whole_again(*built, std::move(again));
    CHECK(&whole_again.grid() == &built->grid());
    const std::array<tl::TileKey, 2> both{resident, halo[next]};
    const auto both_bits = bits_of(whole_again, sun, both);
    ATLAS_REQUIRE(both_bits.size() == 2);
    CHECK(both_bits.at(resident.packed()) == before.at(resident.packed()));
    CHECK(both_bits.at(halo[next].packed()) == before.at(halo[next].packed()));
    MESSAGE("tile ", resident.x, "/", resident.y, " and ", halo[next].x, "/", halo[next].y, ": a halo of ", halo.size(),
            " tiles, ", decks.size(), " decks");
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
