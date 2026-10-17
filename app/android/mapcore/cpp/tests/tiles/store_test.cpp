// The tile store (tiles/Store.hpp) and the expanded-tile cache (tiles/ExpandedCache.hpp): packs from files, from a
// range of a file (an APK asset), and remote ones read by ranges, online and then offline from the cache; chunks picked
// by their reach, and tiles decoded and checked against their chunks; the cache's entries, budget, recency, damage and
// leftovers; both used from several threads at once, with entries damaged meanwhile, after which the cache's index and
// its folder still agree.
#include "tests/tiles/support.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Store.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <cstdio>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <random>
#include <thread>
#include <unistd.h>

namespace {

using namespace atlas::tiles;
using atlas::test::ChunkBytes;
namespace fs = std::filesystem;

/// A folder of its own for one test, deleted when it ends.
class TempDir {
public:
    TempDir() {
        static std::atomic<int> serial{0};
        path_ = fs::temp_directory_path() / std::format("atlas-tiles-test-{}-{}", ::getpid(), serial++);
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
    [[nodiscard]] std::string path() const { return path_.string(); }

private:
    fs::path path_;
};

void write_file(const std::string& path, std::span<const std::byte> bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
}

/// The reach the packs of pack_of give tile `t`: its column's band of longitudes (1000 units a column), from the south
/// of the world to its north, `which` pack's a little wider ('b' reaches 300 units further east).
Reach reach_of_test(TileKey t, char which) {
    const auto west = static_cast<std::int32_t>(t.x) * 1000;
    return {.box = {.south = -900'000'000,
                    .west = west,
                    .north = 900'000'000,
                    .east = west + 1100 + (which == 'b' ? 300 : 0)},
            .top_cm = which == 'b' ? 2000 : 1000};
}

/// A pack of three chunks whose bytes say which pack and tile they are.
std::vector<std::byte> pack_of(char which) {
    std::vector<ChunkBytes> cs;
    const std::array<TileKey, 3> tiles = {{{14, 8414, 5384}, {14, 8415, 5384}, {12, 2103, 1346}}};
    for (const TileKey& t : tiles) {
        ChunkBytes c;
        c.tile = t;
        c.raw.assign(3000 + t.x % 7, static_cast<std::byte>(which));
        c.raw[0] = static_cast<std::byte>(t.x & 0xFFU);
        c.count = 1;
        c.reach = reach_of_test(t, which);
        cs.push_back(c);
    }
    constexpr std::array<std::uint8_t, 2> levels = {14, 12};
    return atlas::test::pack_bytes(cs, levels);
}

TEST_CASE("tiles: the store opens packs from a file and from a range of a file, and finds tiles in the plan's order") {
    TempDir dir;
    const auto a = pack_of('a');
    const auto b = pack_of('b');
    write_file(dir.file("render.atlas"), a);
    // a pack inside a larger file, as a bundled pack sits stored in the APK: 1234 bytes in, not on a page boundary
    std::vector<std::byte> apk(1234, std::byte{0x5A});
    apk.insert(apk.end(), b.begin(), b.end());
    apk.insert(apk.end(), 777, std::byte{0x5A});
    write_file(dir.file("app.apk"), apk);

    Store store;
    REQUIRE(store.add({.id = "grachten", .kind = PackKind::render, .path = dir.file("render.atlas")}));
    const int fd = ::open(dir.file("app.apk").c_str(), O_RDONLY | O_CLOEXEC);
    REQUIRE(fd >= 0);
    REQUIRE(store.add({.id = "bundled", .kind = PackKind::bundled, .fd = fd, .offset = 1234, .length = b.size()}));
    (void)::close(fd); // the mapping outlives the descriptor

    const auto packs = store.packs();
    REQUIRE(packs.size() == 2);
    CHECK(packs[0]->id == "bundled"); // added second, looked up first
    CHECK(packs[1]->id == "grachten");
    const auto hits = store.find({14, 8415, 5384});
    REQUIRE(hits.size() == 2);
    Zstd zstd;
    std::vector<std::byte> raw;
    REQUIRE(store.load(hits[0], zstd, raw));
    CHECK(raw[1] == std::byte{'b'});
    REQUIRE(store.load(hits[1], zstd, raw));
    CHECK(raw[1] == std::byte{'a'});
    CHECK(store.find({14, 1, 1}).empty());

    // the same id again replaces it; removing it leaves the other
    REQUIRE(store.add({.id = "grachten", .kind = PackKind::window, .path = dir.file("render.atlas")}));
    CHECK(store.packs().size() == 2);
    CHECK(store.packs()[1]->kind == PackKind::window);
    CHECK(store.remove("grachten"));
    CHECK(!store.remove("grachten"));
    CHECK(store.packs().size() == 1);
}

TEST_CASE("tiles: the store picks a level's chunks by their reach, pack by pack in lookup order") {
    TempDir dir;
    write_file(dir.file("a.atlas"), pack_of('a'));
    write_file(dir.file("b.atlas"), pack_of('b'));
    Store store;
    REQUIRE(store.add({.id = "a", .kind = PackKind::render, .path = dir.file("a.atlas")}));
    REQUIRE(store.add({.id = "b", .kind = PackKind::bundled, .path = dir.file("b.atlas")}));
    auto tiles = [](const std::vector<StoreHit>& hits) {
        std::vector<std::string> out;
        out.reserve(hits.size());
        for (const StoreHit& h : hits) out.push_back(std::format("{} {}", h.pack->id, h.chunk->tile.x));
        return out;
    };
    // the view at column 8415's band: both packs' chunk of 8415; 8414's reaches past its own band to 8415's in pack b
    const GridBox view{.south = 523'700'000, .west = 8'415'350, .north = 523'800'000, .east = 8'415'500};
    CHECK(tiles(store.reaching(14, view)) == std::vector<std::string>{"b 8414", "b 8415", "a 8415"});
    // touching counts: pack a's 8414 reaches to 8'415'100
    CHECK(tiles(store.reaching(14, {.south = 0, .west = 8'415'100, .north = 1, .east = 8'415'100})) ==
          std::vector<std::string>{"b 8414", "b 8415", "a 8414", "a 8415"});
    CHECK(tiles(store.reaching(12, view)).empty()); // L12's chunk lies at column 2103's band
    CHECK(tiles(store.reaching(12, {.south = 0, .west = 2'103'500, .north = 1, .east = 2'103'600})) ==
          std::vector<std::string>{"b 2103", "a 2103"});
    CHECK(store.reaching(13, view).empty());
    const auto hits = store.reaching(14, view);
    CHECK(hits[0].chunk->reach == reach_of_test({14, 8414, 5384}, 'b'));
}

TEST_CASE("tiles: the store decodes a hit's tile and checks it against its chunk's key and reach") {
    TempDir dir;
    atlas::test::TileBytes t;
    atlas::test::LayerBytes trees;
    trees.id = static_cast<std::uint8_t>(LayerId::tree);
    trees.cells = {3, 40};
    trees.columns = {
        {"pos", Codec::point, atlas::test::columns::point({{523'740'001, 48'790'001}, {523'740'002, 48'790'002}})}};
    t.layers.push_back(trees);
    const auto tb = t.grid_box();
    t.cell_boxes[40] = {{tb[0], tb[1] - 77, tb[2], tb[3]}, 900};
    const std::vector<std::byte> raw = t.encode();
    constexpr std::array<std::uint8_t, 1> levels = {14};
    std::vector<ChunkBytes> good = {{.tile = {14, 8414, 5384}, .raw = raw, .count = 2, .reach = t.reach()}};
    auto bad = good;
    bad[0].reach.top_cm = 899;
    write_file(dir.file("good.atlas"), atlas::test::pack_bytes(good, levels));
    write_file(dir.file("bad.atlas"), atlas::test::pack_bytes(bad, levels));
    Store store;
    REQUIRE(store.add({.id = "good", .kind = PackKind::render, .path = dir.file("good.atlas")}));
    REQUIRE(store.add({.id = "bad", .kind = PackKind::window, .path = dir.file("bad.atlas")}));
    const auto hits = store.find({14, 8414, 5384});
    REQUIRE(hits.size() == 2);
    Zstd zstd;
    TileDecoder decoder;
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    std::vector<std::byte> bytes;
    auto tile = store.decode(hits[0], zstd, decoder, arena, bytes);
    REQUIRE_MESSAGE(tile, describe(tile.error()));
    CHECK(tile->feature_count() == 2);
    CHECK(tile->cells[40].west == tb[1] - 77);
    auto refused = store.decode(hits[1], zstd, decoder, arena, bytes);
    REQUIRE(!refused);
    CHECK(refused.error().site == Site::reach);
    // and through the pack itself
    std::vector<std::byte> scratch;
    CHECK(hits[0].pack->pack.decode(*hits[0].chunk, zstd, decoder, arena, bytes, scratch));
    CHECK(!hits[1].pack->pack.decode(*hits[1].chunk, zstd, decoder, arena, bytes, scratch));
}

TEST_CASE("tiles: the store refuses what it cannot draw from") {
    TempDir dir;
    Store store;
    auto missing = store.add({.id = "x", .path = dir.file("none.atlas")});
    REQUIRE(!missing);
    CHECK(missing.error().fault == Fault::io);
    std::vector<ChunkBytes> cs(1);
    cs[0].tile = {14, 1, 1};
    cs[0].raw.assign(10, std::byte{1});
    constexpr std::array<std::uint8_t, 1> levels = {14};
    write_file(dir.file("elements.atlas"), atlas::test::pack_bytes(cs, levels, format_elements));
    auto elements = store.add({.id = "e", .path = dir.file("elements.atlas")});
    REQUIRE(!elements);
    CHECK(elements.error().fault == Fault::unsupported);
    CHECK(!store.add({.id = "nothing"}));
    CHECK(store.packs().empty());
}

/// A remote pack: its bytes in memory, served by ranges while the network is up, each range counted.
struct Remote {
    std::vector<std::byte> bytes;
    std::atomic<bool> online{true};
    std::atomic<int> fetches{0};

    [[nodiscard]] PackRef ref(std::string id) {
        PackRef r;
        r.id = std::move(id);
        r.kind = PackKind::world_mid;
        r.length = bytes.size();
        r.identity = Sha256::of(bytes);
        r.fetch = [this](std::uint64_t offset, std::span<std::byte> out) -> Result<void> {
            if (!online) return std::unexpected(error(Fault::io, Site::file, 101)); // ENETUNREACH-like
            ++fetches;
            std::ranges::copy(std::span(bytes).subspan(static_cast<std::size_t>(offset), out.size()), out.begin());
            return {};
        };
        return r;
    }
};

TEST_CASE("tiles: a remote pack is read by ranges, its head and chunks cached, and opens offline from them") {
    TempDir dir;
    auto cache = ExpandedCache::open(dir.file("rtiles"), 1U << 20U);
    REQUIRE(cache);
    Remote remote;
    remote.bytes = pack_of('w');
    Zstd zstd;
    std::vector<std::byte> raw;
    {
        Store store(cache->get());
        REQUIRE(store.add(remote.ref("base")));
        const int opening = remote.fetches;
        CHECK(opening >= 4); // header, directory, rhead, style, chunk table
        const auto hit = store.find({14, 8414, 5384});
        REQUIRE(hit.size() == 1);
        REQUIRE(store.load(hit[0], zstd, raw));
        CHECK(raw[1] == std::byte{'w'});
        CHECK(remote.fetches == opening + 1);
        REQUIRE(store.load(hit[0], zstd, raw)); // from the cache now
        CHECK(remote.fetches == opening + 1);
        CHECK((*cache)->entries() == 2); // the head and one chunk
    }
    remote.online = false;
    {
        Store store(cache->get());
        REQUIRE_MESSAGE(store.add(remote.ref("base")), "the cached head opens the pack offline");
        CHECK(remote.fetches > 0);
        const auto seen = store.find({14, 8414, 5384});
        REQUIRE(seen.size() == 1);
        REQUIRE(store.load(seen[0], zstd, raw));
        CHECK(raw[0] == std::byte{8414 & 0xFF});
        const auto unseen = store.find({12, 2103, 1346});
        REQUIRE(unseen.size() == 1);
        auto offline = store.load(unseen[0], zstd, raw);
        REQUIRE(!offline);
        CHECK(offline.error().fault == Fault::io);
    }
    remote.online = true;
    {
        // a damaged cached chunk is noticed, deleted and fetched again
        std::string chunk_file;
        for (const auto& e : fs::directory_iterator(dir.file("rtiles"))) {
            if (e.file_size() < 2000) continue; // the head's entry is the smaller one; the chunk's holds 3 KB
            chunk_file = e.path().string();
        }
        REQUIRE(!chunk_file.empty());
        {
            std::fstream f(chunk_file, std::ios::in | std::ios::out | std::ios::binary);
            f.seekp(cache_header_bytes + 20);
            f.put('\x01');
        }
        Store store(cache->get());
        REQUIRE(store.add(remote.ref("base")));
        const int before = remote.fetches;
        const auto hit = store.find({14, 8414, 5384});
        REQUIRE(store.load(hit[0], zstd, raw));
        CHECK(raw[1] == std::byte{'w'});
        CHECK(remote.fetches == before + 1);
    }
}

TEST_CASE("tiles: the cache keeps entries apart by every part of their key") {
    TempDir dir;
    auto cache = ExpandedCache::open(dir.path(), 1U << 20U);
    REQUIRE(cache);
    ExpandedCache& c = **cache;
    const Sha256Digest chunk = Sha256::of(as_bytes("chunk"));
    const std::array<CacheKey, 4> keys = {{{.kind = CacheKind::built, .source = chunk, .style = "s1.1-a", .format = 1},
                                           {.kind = CacheKind::built, .source = chunk, .style = "s1.1-b", .format = 1},
                                           {.kind = CacheKind::built, .source = chunk, .style = "s1.1-a", .format = 2},
                                           {.kind = CacheKind::chunk, .source = chunk}}};
    for (std::size_t i = 0; i < keys.size(); ++i) {
        const std::string payload(100 + i, static_cast<char>('a' + i));
        REQUIRE(c.put(keys[i], as_bytes(payload)));
    }
    CHECK(c.entries() == 4);
    for (std::size_t i = 0; i < keys.size(); ++i) {
        const auto entry = c.get(keys[i]);
        const auto& e = atlas::test::held(entry);
        CHECK(e.payload.size() == 100 + i);
        CHECK(e.payload[0] == static_cast<std::byte>('a' + i));
    }
    CHECK(!c.get({.kind = CacheKind::pack_head, .source = chunk}));
    const std::uint64_t before = c.bytes();
    REQUIRE(c.put(keys[0], as_bytes(std::string(10, 'z')))); // replaced: the bytes follow
    CHECK(c.bytes() == before - 100 + 10);
    CHECK(atlas::test::held(c.get(keys[0])).payload.size() == 10);
    c.remove(keys[0]);
    CHECK(!c.get(keys[0]));
    CHECK(c.entries() == 3);
}

TEST_CASE("tiles: the cache evicts the least recently used first, and remembers recency across a restart") {
    TempDir dir;
    const std::size_t entry = cache_header_bytes + 1000;
    auto key = [](int i) {
        return CacheKey{.kind = CacheKind::built,
                        .source = Sha256::of(as_bytes(std::format("tile {}", i))),
                        .style = "s",
                        .format = 1};
    };
    const std::string payload(1000, 'p');
    {
        auto cache = ExpandedCache::open(dir.path(), 3 * entry);
        REQUIRE(cache);
        for (int i = 0; i < 3; ++i) {
            REQUIRE((*cache)->put(key(i), as_bytes(payload)));
            std::this_thread::sleep_for(std::chrono::milliseconds(5)); // distinct modification times
        }
        CHECK((*cache)->get(key(0))); // 0 is now the most recent, 1 the least
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        REQUIRE((*cache)->put(key(3), as_bytes(payload)));
        CHECK((*cache)->entries() == 3);
        CHECK(!(*cache)->get(key(1)));
        CHECK((*cache)->get(key(0)));
        CHECK((*cache)->bytes() == 3 * entry);
        auto too_big = (*cache)->put(key(9), as_bytes(std::string(3 * entry, 'x')));
        REQUIRE(!too_big);
        CHECK(too_big.error().fault == Fault::too_big);
    }
    // a restart: the index from the files, the oldest (2, written before 0 was read and 3 written) evicted first
    auto cache = ExpandedCache::open(dir.path(), 2 * entry);
    REQUIRE(cache);
    CHECK((*cache)->entries() == 2);
    CHECK(!(*cache)->get(key(2)));
    CHECK((*cache)->get(key(0)));
    CHECK((*cache)->get(key(3)));
}

TEST_CASE("tiles: a damaged, cut or foreign entry is a miss, and leftovers of interrupted writes go at open") {
    TempDir dir;
    const CacheKey key{.kind = CacheKind::chunk, .source = Sha256::of(as_bytes("k"))};
    std::string path;
    {
        auto cache = ExpandedCache::open(dir.path(), 1U << 20U);
        REQUIRE(cache);
        REQUIRE((*cache)->put(key, as_bytes("a payload of some length")));
        path = std::format("{}/{}.x", dir.path(), hex(key.digest()));
        REQUIRE(fs::exists(path));
        {
            std::fstream f(path, std::ios::in | std::ios::out | std::ios::binary);
            f.seekp(cache_header_bytes + 3);
            f.put('!');
        }
        CHECK(!(*cache)->get(key));
        CHECK(!fs::exists(path)); // deleted
        CHECK((*cache)->entries() == 0);
    }
    write_file(dir.file("deadbeef.x.tmp-1-1"), as_bytes("half"));
    write_file(dir.file("notes.txt"), as_bytes("not ours"));
    const std::string cut = std::format("{}/{}.x", dir.path(), hex(Sha256::of(as_bytes("cut"))));
    write_file(cut, as_bytes("ATXC"));
    auto cache = ExpandedCache::open(dir.path(), 1U << 20U);
    REQUIRE(cache);
    CHECK(!fs::exists(dir.file("deadbeef.x.tmp-1-1")));
    CHECK(!fs::exists(cut));
    CHECK(fs::exists(dir.file("notes.txt")));
    CHECK((*cache)->entries() == 0);
}

TEST_CASE("tiles: an entry's bytes are checked: header, key, length and payload") {
    const CacheKey key{.kind = CacheKind::built, .source = Sha256::of(as_bytes("tile")), .style = "s1", .format = 3};
    const auto bytes = ExpandedCache::encode(key, as_bytes("payload"));
    const Sha256Digest digest = key.digest();
    const auto ok = ExpandedCache::check(bytes, &digest);
    REQUIRE(ok);
    CHECK(as_text(*ok) == "payload");
    const Sha256Digest other =
        CacheKey{.kind = CacheKind::built, .source = Sha256::of(as_bytes("tile")), .style = "s1", .format = 4}.digest();
    CHECK(!ExpandedCache::check(bytes, &other));
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        auto b = bytes;
        b[i] ^= std::byte{0x40};
        CHECK_MESSAGE(!ExpandedCache::check(b, &digest), "a flipped byte at " << i);
    }
    auto cut = bytes;
    cut.pop_back();
    CHECK(!ExpandedCache::check(cut, &digest));
    CHECK(!ExpandedCache::check(std::span(bytes).first(10), nullptr));
}

TEST_CASE("tiles: the store and the cache work from several threads at once") {
    TempDir dir;
    auto cache = ExpandedCache::open(dir.file("rtiles"), 12U * (cache_header_bytes + 1000));
    REQUIRE(cache);
    Remote remote;
    remote.bytes = pack_of('t');
    write_file(dir.file("render.atlas"), pack_of('r'));
    Store store(cache->get());
    REQUIRE(store.add(remote.ref("base")));
    REQUIRE(store.add({.id = "render", .kind = PackKind::render, .path = dir.file("render.atlas")}));
    std::atomic<int> failures{0};
    std::vector<std::thread> threads;
    threads.reserve(4);
    for (int t = 0; t < 4; ++t) {
        threads.emplace_back([&, t] {
            std::mt19937 g(static_cast<unsigned>(t));
            Zstd zstd;
            std::vector<std::byte> raw;
            const std::array<TileKey, 3> tiles = {{{14, 8414, 5384}, {14, 8415, 5384}, {12, 2103, 1346}}};
            for (int i = 0; i < 300; ++i) {
                const int op = static_cast<int>(g() % 10);
                if (op == 0 && t == 0) {
                    // the app lists the render pack again (or drops it) while others read
                    if (g() % 2 == 0) {
                        if (!store.add({.id = "render", .kind = PackKind::render, .path = dir.file("render.atlas")}))
                            ++failures;
                    } else {
                        (void)store.remove("render");
                    }
                    continue;
                }
                if (op == 0 && t == 1) {
                    // a damaged sector under an entry (its reserved header bytes no longer zero), found by the next get
                    const CacheKey k{.kind = CacheKind::built,
                                     .source = Sha256::of(as_bytes(std::format("{}", g() % 20))),
                                     .style = "s",
                                     .format = 1};
                    const std::string path = std::format("{}/{}.x", (*cache)->directory(), hex(k.digest()));
                    if (const int fd = ::open(path.c_str(), O_WRONLY | O_CLOEXEC); fd >= 0) {
                        const char bad = 'X';
                        (void)::pwrite(fd, &bad, 1, 60);
                        (void)::close(fd);
                    }
                    continue;
                }
                if (op < 4) {
                    const CacheKey k{.kind = CacheKind::built,
                                     .source = Sha256::of(as_bytes(std::format("{}", g() % 20))),
                                     .style = "s",
                                     .format = 1};
                    const std::string payload(1000, static_cast<char>('a' + (k.source[0] == std::byte{0} ? 0 : 1)));
                    if (g() % 2 == 0) {
                        if (!(*cache)->put(k, as_bytes(payload))) ++failures;
                    } else if (auto e = (*cache)->get(k); e && e->payload.size() != 1000) {
                        ++failures;
                    }
                    continue;
                }
                for (const StoreHit& h : store.find(tiles[g() % 3])) {
                    if (!store.load(h, zstd, raw) || raw.size() < 3000) ++failures;
                }
            }
        });
    }
    for (auto& th : threads) th.join();
    CHECK(failures == 0);
    CHECK((*cache)->bytes() <= (*cache)->budget());
    // every entry the index holds is a file in the folder, and no other file is
    std::size_t files = 0;
    std::uint64_t bytes = 0;
    for (const auto& f : fs::directory_iterator(dir.file("rtiles"))) {
        ++files;
        bytes += f.file_size();
        CHECK_MESSAGE(f.path().extension() == ".x", f.path().filename().string());
    }
    CHECK(files == (*cache)->entries());
    CHECK(bytes == (*cache)->bytes());
}

} // namespace
