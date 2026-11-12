// The world's packs (ENGINE.md step 5, its step 1: the Netherlands' world on the Mac) as the core reads them. The index
// (world.idx, as tiles' WorldIndex writes it) is read by the core's reader (tiles/WorldIndex.hpp, add_world for a
// folder); each pack it lists is a range of its part, whose SHA-256 is the entry's, opened by the store as it opens a
// bundled pack (a range of a file); its head is the world's (profile world_base, its block's levels from fine to
// coarse), its chunks pass verify(), and every tile is found and decoded through the store and agrees with its chunk.
// Each tile's ground covers it: in every cell the land under everything (GROUND source 2) and the sea (WATER's tidal
// rings) add up to the cell's area exactly on the grid, and no ring, line or point of any layer leaves its cell's box.
// A world pack's levels are the world's (L6 to L11 a pack's, L0 to L5 the globe's), so no country's tile is in one. The
// globe (world-globe.atlas, the store's world_base) is held to the same. A world written here as the packer lays one
// out (two fine packs end to end in a part, a coarse pack in another, and the globe) holds it on CI, and a tile whose
// ground leaves a piece of a cell bare or crosses a cell's edge is caught. With ATLAS_WORLD_DIR naming the packer's
// output folder (world.idx, its parts and world-globe.atlas), its packs are checked so and each level's tiles, features
// and bytes printed, a line a level: `world level <z> ...`.
#include "base/pymath/PyMath.hpp"
#include "load/PackStyle.hpp"
#include "passes/World.hpp"
#include "scene/Cells.hpp"
#include "scene/WorldTiles.hpp"
#include "style/Theme.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tests/tiles/world_support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Kept.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Store.hpp"
#include "tiles/WorldIndex.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Labels.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Water.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <map>
#include <numbers>
#include <optional>
#include <span>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

using namespace atlas::tiles;
using atlas::test::cell_box;
using atlas::test::ColumnBytes;
using atlas::test::Ring;
using atlas::test::TileBytes;
using atlas::test::world_pack;
using atlas::test::world_tile;
namespace col = atlas::test::columns;
namespace fs = std::filesystem;

__extension__ using Int128 = __int128;

/// A folder of its own for one test, deleted when it ends.
class TempDir {
public:
    TempDir() {
        static std::atomic<int> serial{0};
        path_ = fs::temp_directory_path() / std::format("atlas-world-test-{}-{}", ::getpid(), serial++);
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
    [[nodiscard]] std::string path() const { return path_.string(); }

private:
    fs::path path_;
};

/// The file at `path`, whole (empty when it cannot be read).
std::vector<std::byte> read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> out(bytes.size());
    std::ranges::transform(bytes, out.begin(), [](char c) { return static_cast<std::byte>(c); });
    return out;
}

void write_file(const std::string& path, std::span<const std::byte> bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
}

/// `index` as WorldIndex.write lays it out (written here independently).
std::vector<std::byte> world_index_bytes(const WorldIndex& index) {
    atlas::test::Sink s;
    s.raw(atlas::tiles::as_bytes(world_index_magic));
    s.u16(world_index_version);
    s.u16(0);
    s.u64(static_cast<std::uint64_t>(index.date));
    s.u16(index.parts.size());
    for (const auto& [name, length] : index.parts) {
        s.u8(name.size());
        s.raw(atlas::tiles::as_bytes(name));
        s.u64(length);
    }
    s.u32(index.entries.size());
    for (const WorldEntry& e : index.entries) {
        s.u8(e.z);
        s.u8(e.first);
        s.u8(e.last);
        s.u8(e.part);
        s.u32(e.x);
        s.u32(e.y);
        s.u64(e.offset);
        s.u64(e.length);
        s.u32(e.tiles);
        for (const std::int32_t v : e.box) s.u32(static_cast<std::uint32_t>(v));
        s.raw(e.sha256);
    }
    return s.bytes;
}

/// Twice the signed area of `ring` (closed, its first vertex not repeated), on the grid, counter-clockwise positive,
/// summed from (`south`, `west`) so no product overflows.
Int128 twice_area(const RingView& ring, std::int32_t south, std::int32_t west) {
    Int128 sum = 0;
    const std::size_t n = ring.size();
    for (std::size_t i = 0; i < n; ++i) {
        const std::size_t j = (i + 1) % n;
        const Int128 xi = std::int64_t{ring.lon[i]} - west;
        const Int128 yi = std::int64_t{ring.lat[i]} - south;
        const Int128 xj = std::int64_t{ring.lon[j]} - west;
        const Int128 yj = std::int64_t{ring.lat[j]} - south;
        sum += (xi * yj) - (xj * yi);
    }
    return sum;
}

/// How a tile's ground covers its cells: the most any cell's land and sea differ from twice its area, and that cell's
/// twice area; the rings, lines and points found outside their cell's box; the land's and the sea's pieces.
struct Cover {
    Int128 worst = 0;
    Int128 cell_area = 0;
    std::uint64_t outside = 0;
    std::uint64_t land = 0;
    std::uint64_t sea = 0;

    /// The worst cell's difference as a share of its area.
    [[nodiscard]] double relative() const noexcept {
        return cell_area == 0 ? 0.0 : static_cast<double>(worst) / static_cast<double>(cell_area);
    }
};

/// Whether every vertex of `ring` lies in `box`.
bool inside(const RingView& ring, const std::array<std::int32_t, 4>& box) {
    for (std::size_t i = 0; i < ring.size(); ++i) {
        if (ring.lat[i] < box[0] || ring.lon[i] < box[1] || ring.lat[i] > box[2] || ring.lon[i] > box[3]) return false;
    }
    return true;
}

/// How `tile`'s ground covers its cells, and what crosses a cell's edge.
Cover cover_of(const Tile& tile) {
    Cover cover;
    const std::uint32_t cells = std::uint32_t{tile.header.cells_per_side} * tile.header.cells_per_side;
    const Layer* ground = tile.layer(LayerId::ground);
    const Layer* water = tile.layer(LayerId::water);
    for (std::uint32_t c = 0; c < cells; ++c) {
        const auto box = cell_box(tile.header, c);
        const Int128 twice = Int128{2} * (std::int64_t{box[2]} - box[0]) * (std::int64_t{box[3]} - box[1]);
        Int128 sum = 0;
        if (ground != nullptr) {
            for (auto [i, end] = ground->cell(c); i < end; ++i) {
                if (ground->integer(ground::source, i) != ground::source_land_base) continue;
                const RingView r = ground->ring(ground::ring, i);
                sum += std::max(twice_area(r, box[0], box[1]), -twice_area(r, box[0], box[1]));
                ++cover.land;
            }
        }
        if (water != nullptr) {
            for (auto [i, end] = water->cell(c); i < end; ++i) {
                if (water->boolean(water::tidal, i) != true) continue;
                const RingView r = water->ring(water::ring, i);
                sum += std::max(twice_area(r, box[0], box[1]), -twice_area(r, box[0], box[1]));
                ++cover.sea;
            }
        }
        const Int128 difference = std::max(sum - twice, twice - sum);
        if (difference > cover.worst || cover.cell_area == 0) {
            cover.worst = std::max(cover.worst, difference);
            cover.cell_area = twice;
        }
        for (const Layer& l : tile.layers) {
            for (auto [i, end] = l.cell(c); i < end; ++i) {
                bool in = true;
                switch (l.id) {
                case LayerId::ground: in = inside(l.ring(ground::ring, i), box); break;
                case LayerId::water: in = inside(l.ring(water::ring, i), box); break;
                case LayerId::road: in = inside(l.ring(road::line, i), box); break;
                case LayerId::line: in = inside(l.ring(line::line, i), box); break;
                case LayerId::label: {
                    const GridPoint p = l.point(label::pos, i);
                    in = p.lat >= box[0] && p.lon >= box[1] && p.lat <= box[2] && p.lon <= box[3];
                    break;
                }
                default: break;
                }
                if (!in) ++cover.outside;
            }
        }
    }
    return cover;
}

/// What a level of the world holds: its tiles, their features, and their chunks' bytes decompressed and stored.
struct LevelCount {
    std::uint64_t tiles = 0;
    std::uint64_t features = 0;
    std::uint64_t raw = 0;
    std::uint64_t stored = 0;
};

/// Checks the pack `id` the store opened as `kind`: its head the world's for `levels` (fine to coarse), its chunks
/// verified, every tile within `block` (or anywhere for none) found and decoded through the store, checked against its
/// chunk, and its ground covering its cells; counts each level into `counts`.
void check_pack(const Store& store, std::string_view id, PackKind kind, std::span<const std::uint8_t> levels,
                std::optional<TileKey> block, std::map<std::uint8_t, LevelCount>& counts) {
    INFO("pack " << id);
    std::shared_ptr<const StorePack> pack;
    for (const auto& p : store.packs()) {
        if (p->id == id) pack = p;
    }
    ATLAS_REQUIRE(pack != nullptr);
    CHECK(pack->kind == kind);
    const RenderHead* head = pack->pack.head();
    ATLAS_REQUIRE(head != nullptr);
    CHECK(head->profile == Profile::world_base);
    CHECK(std::ranges::equal(head->levels, levels));
    CHECK(head->never_simplified());
    auto verified = pack->pack.verify();
    ATLAS_REQUIRE(verified, describe(verified.error()));
    Zstd zstd;
    TileDecoder decoder;
    std::vector<std::byte> raw;
    for (const ChunkRef& c : pack->pack.chunks().chunks()) {
        INFO("tile " << int{c.tile.z} << "/" << c.tile.x << "/" << c.tile.y);
        CHECK(std::ranges::find(levels, c.tile.z) != levels.end());
        if (block) {
            const unsigned shift = c.tile.z - block->z;
            CHECK((c.tile.x >> shift) == block->x);
            CHECK((c.tile.y >> shift) == block->y);
        }
        const auto hits = store.find(c.tile);
        ATLAS_REQUIRE(hits.size() == 1, hits.size() << " packs hold the tile");
        CHECK(hits[0].pack == pack);
        atlas::Arena arena(1U << 20U, atlas::Growth::grows, "world");
        auto tile = store.decode(hits[0], zstd, decoder, arena, raw);
        ATLAS_REQUIRE(tile, describe(tile.error()));
        CHECK(tile->feature_count() == c.count);
        const Cover cover = cover_of(*tile);
        CHECK_MESSAGE(cover.worst == 0, "a cell's land and sea differ from its area by " << cover.relative());
        CHECK(cover.relative() <= 1e-9);
        CHECK(cover.outside == 0);
        CHECK(cover.land + cover.sea >= std::uint64_t{tile->header.cells_per_side} * tile->header.cells_per_side);
        LevelCount& n = counts[c.tile.z];
        ++n.tiles;
        n.features += c.count;
        n.raw += c.raw;
        n.stored += c.stored;
    }
}

/// The tiles of `counts` at levels past `above`.
std::uint64_t tiles_past(const std::map<std::uint8_t, LevelCount>& counts, int above) {
    std::uint64_t sum = 0;
    for (const auto& [z, n] : counts) {
        if (std::cmp_greater(z, above)) sum += n.tiles;
    }
    return sum;
}

/// The levels from `last` down to `first`, as a head names them.
std::vector<std::uint8_t> fine_to_coarse(std::uint8_t first, std::uint8_t last) {
    std::vector<std::uint8_t> out;
    for (int z = last; std::cmp_greater_equal(z, first); --z) out.push_back(static_cast<std::uint8_t>(z));
    return out;
}

/// The world in `dir` (world.idx, its parts and world-globe.atlas) as the store reads it, checked as this file says;
/// returns each level's counts, the packs' and the globe's together.
std::map<std::uint8_t, LevelCount> check_world(const std::string& dir) {
    std::map<std::uint8_t, LevelCount> counts;
    const auto index = read_world_index(read_file(dir + "/world.idx"));
    ATLAS_REQUIRE_OR_RETURN(counts, index.has_value(), dir << "/world.idx is no world index");
    ATLAS_REQUIRE_OR_RETURN(counts, !index->entries.empty());
    Store store;
    std::vector<std::vector<std::byte>> parts;
    for (const auto& [name, length] : index->parts) {
        parts.push_back(read_file(std::format("{}/{}", dir, name)));
        CHECK_MESSAGE(parts.back().size() == length, name << " holds " << parts.back().size() << " bytes");
    }
    std::uint64_t tiles = 0;
    for (const WorldEntry& e : index->entries) {
        const std::string id = std::format("world/{}/{}/{}", e.z, e.x, e.y);
        INFO(id);
        ATLAS_REQUIRE_OR_RETURN(counts, e.part < parts.size());
        const auto& part = parts[e.part];
        ATLAS_REQUIRE_OR_RETURN(counts, e.offset + e.length <= part.size());
        CHECK(Sha256::of(std::span(part).subspan(e.offset, e.length)) == e.sha256);
        CHECK(e.first <= e.last);
        CHECK(e.first >= e.z);
        const std::string part_path = dir + "/" + index->parts[e.part].first;
        const int fd = ::open(part_path.c_str(), O_RDONLY | O_CLOEXEC); // NOLINT(cppcoreguidelines-pro-type-vararg)
        ATLAS_REQUIRE_OR_RETURN(counts, fd >= 0, part_path);
        auto added =
            store.add({.id = id, .kind = PackKind::world_mid, .fd = fd, .offset = e.offset, .length = e.length});
        (void)::close(fd); // the mapping outlives the descriptor
        ATLAS_REQUIRE_OR_RETURN(counts, added, describe(added.error()));
        tiles += e.tiles;
    }
    const std::string globe = dir + "/world-globe.atlas";
    const bool has_globe = fs::exists(globe);
    if (has_globe) {
        auto added = store.add({.id = "world-globe", .kind = PackKind::world_base, .path = globe});
        ATLAS_REQUIRE_OR_RETURN(counts, added, describe(added.error()));
        CHECK(store.packs().back()->id == "world-globe"); // looked up last
    }
    CHECK(store.packs().front()->kind == PackKind::world_mid);
    for (const WorldEntry& e : index->entries) {
        const std::string id = std::format("world/{}/{}/{}", e.z, e.x, e.y);
        const std::uint64_t before = tiles_past(counts, -1);
        check_pack(store, id, PackKind::world_mid, fine_to_coarse(e.first, e.last), TileKey{e.z, e.x, e.y}, counts);
        const std::uint64_t after = tiles_past(counts, -1);
        CHECK_MESSAGE(after - before == e.tiles, id << " holds " << after - before << " tiles, its entry " << e.tiles);
    }
    // a level's chunks over the whole world, as a frame asks for them: every tile of the level, each pack's once
    const GridBox world{.south = -900'000'000, .west = -1'800'000'000, .north = 900'000'000, .east = 1'800'000'000};
    for (const auto& [z, n] : counts) {
        if (has_globe && z <= 5) continue;
        CHECK(store.reaching(z, world).size() == n.tiles);
    }
    if (has_globe) check_pack(store, "world-globe", PackKind::world_base, fine_to_coarse(0, 5), std::nullopt, counts);
    CHECK(tiles_past(counts, 5) == tiles);
    return counts;
}

/// Decodes `tile`'s bytes and measures its cover.
Cover cover_of_bytes(TileBytes& tile) {
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = tile.encode();
    auto decoded = decoder.decode(raw, arena);
    ATLAS_REQUIRE_OR_RETURN(Cover{}, decoded, describe(decoded.error()));
    return cover_of(*decoded);
}

TEST_CASE("tiles: a world's index, its packs as ranges of their parts and the globe are read, and every tile covers "
          "its cells") {
    TempDir dir;
    constexpr std::array<std::uint8_t, 3> fine = {11, 10, 9};
    constexpr std::array<std::uint8_t, 3> coarse = {8, 7, 6};
    constexpr std::array<std::uint8_t, 6> globe = {5, 4, 3, 2, 1, 0};
    // the L6 blocks 32/21 (Amsterdam's) and 33/21, their L9 to L11 tiles; the L3 block 4/2, its L6 to L8 tiles
    const auto a = world_pack({world_tile(9, 262, 168), world_tile(10, 525, 336), world_tile(11, 1050, 673)}, fine);
    const auto b = world_pack({world_tile(9, 264, 170)}, fine);
    const auto c = world_pack({world_tile(6, 32, 21), world_tile(8, 131, 84)}, coarse);
    write_file(dir.path() + "/world-globe.atlas", world_pack({world_tile(0, 0, 0), world_tile(5, 16, 10)}, globe));
    // a part holds its packs end to end
    std::vector<std::byte> mid = a;
    mid.insert(mid.end(), b.begin(), b.end());
    write_file(dir.path() + "/world-mid.000", mid);
    write_file(dir.path() + "/world-coarse.000", c);
    WorldIndex index{.date = 1'761'955'200, .parts = {{"world-mid.000", mid.size()}, {"world-coarse.000", c.size()}}};
    index.entries.push_back({.z = 3,
                             .first = 6,
                             .last = 8,
                             .part = 1,
                             .x = 4,
                             .y = 2,
                             .offset = 0,
                             .length = c.size(),
                             .tiles = 2,
                             .box = {},
                             .sha256 = Sha256::of(c)});
    index.entries.push_back({.z = 6,
                             .first = 9,
                             .last = 11,
                             .part = 0,
                             .x = 32,
                             .y = 21,
                             .offset = 0,
                             .length = a.size(),
                             .tiles = 3,
                             .box = {},
                             .sha256 = Sha256::of(a)});
    index.entries.push_back({.z = 6,
                             .first = 9,
                             .last = 11,
                             .part = 0,
                             .x = 33,
                             .y = 21,
                             .offset = a.size(),
                             .length = b.size(),
                             .tiles = 1,
                             .box = {},
                             .sha256 = Sha256::of(b)});
    const auto bytes = world_index_bytes(index);
    write_file(dir.path() + "/world.idx", bytes);

    const auto read = read_world_index(bytes);
    ATLAS_REQUIRE(read.has_value());
    CHECK(read->date == index.date);
    CHECK(read->parts == index.parts);
    ATLAS_REQUIRE(read->entries.size() == 3);
    CHECK(read->entries[2].offset == a.size());
    CHECK(read->entries[2].sha256 == Sha256::of(b));

    const auto counts = check_world(dir.path());
    CHECK(counts.size() == 7);
    CHECK(counts.at(9).tiles == 2);
    CHECK(counts.at(0).tiles == 1);

    SUBCASE("an index cut short, run on, of another magic or a later version is none") {
        CHECK(!read_world_index(std::span(bytes).first(bytes.size() - 1)).has_value());
        auto longer = bytes;
        longer.push_back(std::byte{0});
        CHECK(!read_world_index(longer).has_value());
        auto magic = bytes;
        magic[0] = std::byte{'B'};
        CHECK(!read_world_index(magic).has_value());
        auto version = bytes;
        version[4] = std::byte{2};
        CHECK(!read_world_index(version).has_value());
    }
    SUBCASE("a world pack whose head names its levels from coarse to fine is refused") {
        constexpr std::array<std::uint8_t, 3> upward = {9, 10, 11};
        write_file(dir.path() + "/upward.atlas", world_pack({world_tile(9, 262, 168)}, upward));
        Store store;
        CHECK(!store.add({.id = "upward", .kind = PackKind::world_mid, .path = dir.path() + "/upward.atlas"}));
    }
    SUBCASE("add_world adds every pack of the index as a range of its part, and the globe") {
        Store store;
        const auto added = add_world(store, dir.path());
        ATLAS_REQUIRE(added, describe(added.error()));
        CHECK(added->packs == 3);
        CHECK(added->globe);
        CHECK(added->tiles == 6);
        const auto packs = store.packs();
        ATLAS_REQUIRE(packs.size() == 4);
        CHECK(packs[0]->id == "world/3/4/2");
        CHECK(packs[0]->kind == PackKind::world_mid);
        CHECK(packs[3]->id == world_globe_id);
        CHECK(packs[3]->kind == PackKind::world_base);
        CHECK(store.find(TileKey(11, 1050, 673)).size() == 1);
        CHECK(store.find(TileKey(0, 0, 0)).size() == 1);
        // a folder without an index, or a part shorter than its range, is an error
        Store none;
        CHECK(!add_world(none, dir.path() + "/nowhere"));
        write_file(dir.path() + "/world-coarse.000", std::span(c).first(c.size() / 2));
        Store short_part;
        CHECK(!add_world(short_part, dir.path()));
    }
    SUBCASE("a world pack of a country's level, or a globe of the mid-zoom base's, is refused") {
        constexpr std::array<std::uint8_t, 1> country = {12};
        constexpr std::array<std::uint8_t, 1> mid_level = {6};
        write_file(dir.path() + "/country.atlas", world_pack({world_tile(12, 2100, 1346)}, country));
        write_file(dir.path() + "/mid.atlas", world_pack({world_tile(6, 32, 21)}, mid_level));
        Store store;
        CHECK(!store.add({.id = "country", .kind = PackKind::world_mid, .path = dir.path() + "/country.atlas"}));
        CHECK(!store.add({.id = "mid", .kind = PackKind::world_base, .path = dir.path() + "/mid.atlas"}));
        CHECK(store.add({.id = "mid", .kind = PackKind::world_mid, .path = dir.path() + "/mid.atlas"}));
    }
}

TEST_CASE("tiles: a world pack the app brings by ranges opens from its head in the cache, and a chunk loads once "
          "it is kept there, checked against its SHA-256") {
    TempDir dir;
    constexpr std::array<std::uint8_t, 3> fine = {11, 10, 9};
    const auto bytes = world_pack({world_tile(9, 262, 168), world_tile(10, 525, 336), world_tile(11, 1050, 673)}, fine);
    auto cache = ExpandedCache::open(dir.path() + "/cache", std::uint64_t{1} << 22U);
    ATLAS_REQUIRE(cache);
    const PackRef ref{.id = "world/6/32/21",
                      .kind = PackKind::world_mid,
                      .length = bytes.size(),
                      .fetch = Store::absent(),
                      .identity = Sha256::of(bytes)};
    // its head as the app fetches it (WorldFetch.head): its bytes before its chunks and after them
    auto whole = Pack::open(std::make_unique<MemorySource>(std::span<const std::byte>(bytes)));
    ATLAS_REQUIRE(whole);
    const Section* section = find_section(whole->sections(), "chunks");
    ATLAS_REQUIRE(section != nullptr);
    const std::uint64_t start = section->offset;
    const std::uint64_t end = section->offset + section->stored;
    ATLAS_REQUIRE(end < bytes.size()); // rhead and style follow the chunks
    const std::span<const std::byte> all(bytes);
    const std::array<PackBytes, 2> head = {
        {{.offset = 0, .bytes = all.first(start)}, {.offset = end, .bytes = all.subspan(end)}}};
    Zstd zstd;
    std::vector<std::byte> raw;

    // nothing in the cache: the pack does not open, nothing is fetched (the app brings the bytes)
    {
        Store store(cache->get());
        const auto added = store.add(ref);
        ATLAS_REQUIRE(!added);
        CHECK(added.error().fault == Fault::missing);
    }
    // a head missing what follows the chunks, ranges past the pack, the globe's kind (L0 to L5): refused, none kept
    CHECK(!Store::keep_head(**cache, ref, std::span(head).first(1)));
    const std::array<PackBytes, 1> past = {{{.offset = end + 1, .bytes = all.subspan(end)}}};
    CHECK(!Store::keep_head(**cache, ref, past));
    PackRef globe = ref;
    globe.kind = PackKind::world_base;
    CHECK(!Store::keep_head(**cache, globe, head));
    CHECK((*cache)->entries() == 0);
    // the head kept: the pack opens from it, its tiles found, their chunks absent until kept
    ATLAS_REQUIRE(Store::keep_head(**cache, ref, head));
    CHECK((*cache)->entries() == 1);
    Store store(cache->get());
    ATLAS_REQUIRE(store.add(ref));
    const auto pack = store.packs().front();
    CHECK(pack->remote);
    for (const TileKey t : {TileKey(11, 1050, 673), TileKey(10, 525, 336), TileKey(9, 262, 168)}) {
        const auto hit = store.find(t);
        ATLAS_REQUIRE(hit.size() == 1);
        const auto absent = store.load(hit[0], zstd, raw);
        ATLAS_REQUIRE(!absent);
        CHECK(absent.error().fault == Fault::missing);
        CHECK(absent.error().site == Site::file);
    }
    // a range of the first two chunks whole and the third's first bytes: the two kept, the third neither
    const auto table = pack->pack.chunks().chunks();
    std::vector<ChunkRef> refs(table.begin(), table.end());
    std::ranges::sort(refs, {}, &ChunkRef::offset);
    ATLAS_REQUIRE(refs.size() == 3);
    const ChunkRef& third = refs[2];
    const std::uint64_t from = refs[0].offset;
    const auto two =
        Store::keep(**cache, *pack, {.offset = from, .bytes = all.subspan(from, third.offset + 1 - from)}, zstd);
    CHECK(two.kept.size() == 2);
    CHECK(two.refused.empty());
    CHECK((*cache)->entries() == 3);
    ATLAS_REQUIRE(store.load(store.find(refs[0].tile)[0], zstd, raw));
    ATLAS_REQUIRE(store.load(store.find(refs[1].tile)[0], zstd, raw));
    CHECK(!store.load(store.find(third.tile)[0], zstd, raw));
    // the third's bytes damaged: refused, not kept; whole, kept
    std::vector<std::byte> damaged(all.begin() + static_cast<std::ptrdiff_t>(third.offset),
                                   all.begin() + static_cast<std::ptrdiff_t>(third.offset + third.stored));
    damaged[damaged.size() / 2] ^= std::byte{0x40};
    const auto bad = Store::keep(**cache, *pack, {.offset = third.offset, .bytes = damaged}, zstd);
    CHECK(bad.kept.empty());
    ATLAS_REQUIRE(bad.refused.size() == 1);
    CHECK(bad.refused[0] == third.tile);
    CHECK((*cache)->entries() == 3);
    const auto good =
        Store::keep(**cache, *pack, {.offset = third.offset, .bytes = all.subspan(third.offset, third.stored)}, zstd);
    CHECK(good.kept.size() == 1);
    ATLAS_REQUIRE(store.load(store.find(third.tile)[0], zstd, raw));
    // a later start opens it from the cache alone, every chunk there
    Store again(cache->get());
    ATLAS_REQUIRE(again.add(ref));
    for (const ChunkRef& c : refs) ATLAS_REQUIRE(again.load(again.find(c.tile)[0], zstd, raw));
}

TEST_CASE("tiles: the world's cover check finds a cell's ground short of its area and a piece across its edge") {
    TileBytes whole = world_tile(9, 262, 168);
    const Cover good = cover_of_bytes(whole);
    CHECK(good.worst == 0);
    CHECK(good.outside == 0);
    CHECK(good.land == 10); // the cells of c % 3 > 0
    CHECK(good.sea == 11);  // those of c % 3 < 2

    // the sea of cell 1 (its east half) left out: that cell is short by half its area
    TileBytes bare = world_tile(9, 262, 168);
    auto& water = bare.layers[1];
    std::vector<Ring> sea;
    TileHeader header;
    header.cells_per_side = 4;
    const auto tb = bare.grid_box();
    header.south = tb[0];
    header.west = tb[1];
    header.north = tb[2];
    header.east = tb[3];
    std::vector<std::uint32_t> cells;
    for (std::uint32_t c = 0; c < 16; ++c) {
        if (c == 1 || c % 3 == 2) continue;
        const auto b = cell_box(header, c);
        const std::int32_t m = b[1] + static_cast<std::int32_t>((std::int64_t{b[3]} - b[1]) * (c % 3) / 2);
        sea.push_back({.lat = {b[0], b[0], b[2], b[2]}, .lon = {m, b[3], b[3], m}, .keep = {}});
        cells.push_back(c);
    }
    water.cells = cells;
    atlas::test::Sink indexes;
    atlas::test::Sink flags;
    for (std::size_t i = 0; i < sea.size(); ++i) {
        indexes.varz(i == 0 ? 0 : 1);
        flags.u8(2);
    }
    water.columns = {{"index", Codec::delta, indexes.bytes},
                     {"ring", Codec::geom, col::geom(sea)},
                     {"shore", Codec::boolean, flags.bytes},
                     {"tidal", Codec::boolean, flags.bytes}};
    const Cover short_of = cover_of_bytes(bare);
    const auto b1 = cell_box(header, 1);
    CHECK(short_of.worst == Int128{2} * (std::int64_t{b1[2]} - b1[0]) *
                                ((std::int64_t{b1[3]} - b1[1]) - ((std::int64_t{b1[3]} - b1[1]) / 2)));
    CHECK(short_of.relative() > 0.49);

    // the road reaching a unit past the cell's west edge
    TileBytes across = world_tile(9, 262, 168);
    for (ColumnBytes& c : across.layers[2].columns) {
        if (c.name == "line")
            c.bytes = col::geom({{.lat = {b1[0], b1[2]}, .lon = {b1[1] - 1, b1[1] + 10}, .keep = {}}});
    }
    const Cover crossing = cover_of_bytes(across);
    CHECK(crossing.worst == 0);
    CHECK(crossing.outside == 1);
}

/// A pack of one L6 world tile, its head of `profile`, its style `style`'s bytes.
std::vector<std::byte> styled_pack(std::vector<std::byte> style, Profile profile) {
    constexpr std::array<std::uint8_t, 1> levels = {6};
    TileBytes tile = world_tile(6, 32, 21);
    const std::vector<atlas::test::ChunkBytes> chunks = {atlas::test::chunk_of(tile)};
    atlas::test::PackOptions options;
    options.head = atlas::test::head_bytes(levels, profile);
    options.style = std::move(style);
    return atlas::test::pack_bytes(chunks, levels, format_render, options);
}

TEST_CASE("tiles: a world pack is drawn in the world's style where one is given, whatever its own style lacks") {
    // the world's style (the core's own, load::world_style: ENGINE.md step 5's style in one place), here the test
    // style's, and a world pack built before the style file had its shopfronts (as world-test's of 1 November, which
    // the core of 4 November refused: "style windows.shop_glass_open: missing")
    auto tables = read_style(atlas::test::style_bytes());
    ATLAS_REQUIRE(tables, describe(tables.error()));
    const auto world = Style::of(*tables);
    ATLAS_REQUIRE(world, describe(world.error()));
    REQUIRE(world->shopfronts.glass_open != 0);
    std::vector<atlas::test::values::Field> lacking = atlas::test::style_tables();
    CHECK(std::erase_if(lacking, [](const atlas::test::values::Field& t) { return t.first == "shopfronts"; }) == 1);
    const std::vector<std::byte> old_style = atlas::test::style_bytes(lacking);
    const std::vector<std::byte> old = styled_pack(old_style, Profile::world_base);

    SUBCASE("in its own style it is refused, the table it lacks its error") {
        auto pack = Pack::open(std::make_unique<MemorySource>(old));
        ATLAS_REQUIRE(!pack);
        CHECK(pack.error().fault == Fault::missing);
        CHECK(pack.error().site == Site::style);
    }
    SUBCASE("in the world's it opens, its tables its own and its values the world's") {
        auto pack = Pack::open(std::make_unique<MemorySource>(old), &*world);
        ATLAS_REQUIRE(pack, describe(pack.error()));
        ATLAS_REQUIRE(pack->style() != nullptr && pack->style_tables() != nullptr);
        CHECK(pack->style()->shopfronts.glass_open == world->shopfronts.glass_open);
        CHECK(pack->style()->windows.shop_glass_open == world->windows.shop_glass_open);
        CHECK(!pack->style_tables()->table("shopfronts"));
        CHECK(pack->verify());
    }
    SUBCASE("a pack of another profile keeps its own style, and so is refused") {
        for (const Profile profile : {Profile::living, Profile::region}) {
            CAPTURE(static_cast<int>(profile));
            auto pack = Pack::open(std::make_unique<MemorySource>(styled_pack(old_style, profile)), &*world);
            ATLAS_REQUIRE(!pack);
            CHECK(pack.error().site == Site::style);
        }
    }
    SUBCASE("a world pack's style is still read: one of another payload version is refused") {
        std::vector<std::byte> other = atlas::test::style_bytes();
        other.at(4) ^= std::byte{0x80}; // the payload version's low byte, after the magic
        auto pack = Pack::open(std::make_unique<MemorySource>(styled_pack(other, Profile::world_base)), &*world);
        ATLAS_REQUIRE(!pack);
        CHECK(pack.error().fault == Fault::bad_version);
    }
    SUBCASE("the store opens the world's packs in its world style, from a file and by ranges alike") {
        TempDir dir;
        write_file(dir.path() + "/old.atlas", old);
        Store own;
        CHECK(!own.add({.id = "old", .kind = PackKind::world_mid, .path = dir.path() + "/old.atlas"}));
        Store store;
        store.set_world_style(&*world);
        auto added = store.add({.id = "old", .kind = PackKind::world_mid, .path = dir.path() + "/old.atlas"});
        ATLAS_REQUIRE(added, describe(added.error()));
        const auto fetch = [&old](std::uint64_t offset, std::span<std::byte> out) -> Result<void> {
            if (offset > old.size() || out.size() > old.size() - offset)
                return std::unexpected(error(Fault::io, Site::file));
            std::copy_n(old.begin() + static_cast<std::ptrdiff_t>(offset), out.size(), out.begin());
            return {};
        };
        auto fetched = store.add({.id = "fetched", .kind = PackKind::world_mid, .length = old.size(), .fetch = fetch});
        ATLAS_REQUIRE(fetched, describe(fetched.error()));
        const auto packs = store.packs();
        ATLAS_REQUIRE(packs.size() == 2);
        for (const auto& p : packs) {
            CAPTURE(p->id);
            ATLAS_REQUIRE(p->pack.style() != nullptr);
            CHECK(p->pack.style()->shopfronts.glass_open == world->shopfronts.glass_open);
        }
    }
    SUBCASE("the head the app brings is kept in the world's style, as the store opens the pack, and not in its own") {
        TempDir dir;
        auto cache = ExpandedCache::open(dir.path() + "/cache", std::uint64_t{1} << 22U);
        ATLAS_REQUIRE(cache);
        const PackRef ref{.id = "world/6/32/21",
                          .kind = PackKind::world_mid,
                          .length = old.size(),
                          .fetch = Store::absent(),
                          .identity = Sha256::of(old)};
        auto whole = Pack::open(std::make_unique<MemorySource>(std::span<const std::byte>(old)), &*world);
        ATLAS_REQUIRE(whole, describe(whole.error()));
        const Section* section = find_section(whole->sections(), "chunks");
        ATLAS_REQUIRE(section != nullptr);
        const std::uint64_t end = section->offset + section->stored;
        ATLAS_REQUIRE(end < old.size()); // rhead and style follow the chunks
        const std::span<const std::byte> all(old);
        const std::array<PackBytes, 2> head = {
            {{.offset = 0, .bytes = all.first(section->offset)}, {.offset = end, .bytes = all.subspan(end)}}};
        CHECK(!Store::keep_head(**cache, ref, head));
        CHECK((*cache)->entries() == 0);
        ATLAS_REQUIRE(Store::keep_head(**cache, ref, head, &*world));
        CHECK((*cache)->entries() == 1);
        Store store(cache->get());
        store.set_world_style(&*world);
        auto added = store.add(ref);
        ATLAS_REQUIRE(added, describe(added.error()));
        const auto packs = store.packs();
        ATLAS_REQUIRE(packs.size() == 1);
        CHECK(packs.front()->remote);
        ATLAS_REQUIRE(packs.front()->pack.style() != nullptr);
        CHECK(packs.front()->pack.style()->shopfronts.glass_open == world->shopfronts.glass_open);
    }
}

TEST_CASE("tiles: the packer's world packs and globe, when ATLAS_WORLD_DIR names them") {
    const std::string dir = atlas::test::environment("ATLAS_WORLD_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the packer's world packs are not checked here");
        return;
    }
    const auto counts = check_world(dir);
    CHECK(!counts.empty());
    for (const auto& [z, n] : counts) {
        const std::string line = std::format("world level {}: {} tiles, {} features, {} bytes decompressed, {} stored",
                                             z, n.tiles, n.features, n.raw, n.stored);
        std::printf("%s\n", line.c_str()); // NOLINT: the line the entry's sizes come from
    }
}

} // namespace

TEST_CASE("scene: a world tile's geometry lies the land under everything, then the water, then its lines, each in its "
          "cell; the levels start where their tiles are as wide as L12's at 9.4 km") {
    TileBytes tile = world_tile(9, 262, 168);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = tile.encode();
    auto decoded = decoder.decode(raw, arena);
    ATLAS_REQUIRE(decoded.has_value(), describe(decoded.error()));
    const atlas::scene::WorldGeometry g = atlas::scene::build_world_geometry(*decoded, nullptr);
    REQUIRE(g.triangles > 0);
    CHECK(g.segments == 1);
    CHECK(g.words.size() == (4 * std::size_t{g.triangles}) + (3 * std::size_t{g.segments}));
    // the land's triangles (palette 0) all before the sea's (1): the painter's order
    std::size_t last_land = 0;
    std::size_t first_sea = g.triangles;
    for (std::size_t t = 0; t < g.triangles; ++t) {
        const std::uint32_t look = g.words[(3 * std::size_t{g.triangles}) + t];
        if ((look & 0xFFU) == atlas::scene::world_palette::land) last_land = t;
        if ((look & 0xFFU) == atlas::scene::world_palette::water) first_sea = std::min(first_sea, t);
        CHECK(((look >> 8U) & 0xFFU) < 16U);
    }
    CHECK(last_land < first_sea);
    CHECK(first_sea < g.triangles);
    // the secondary road in cell 1, its width not mapped: the renders' width of its class, 7 m
    const std::uint32_t look = g.words[(4 * std::size_t{g.triangles}) + 2];
    CHECK((look & 0xFFU) == atlas::scene::world_palette::road);
    CHECK(((look >> 8U) & 0xFFU) == 1U);
    CHECK((look >> 16U) == 70U);
    // the renders' terrain lines (regions.py's LW)
    CHECK(atlas::scene::terrain_line_width("motorway", false) == 12.0);
    CHECK(atlas::scene::terrain_line_width("trunk", true) == 10.0);
    CHECK(atlas::scene::terrain_line_width("primary", false) == 8.0);
    CHECK(atlas::scene::terrain_line_width("secondary", false) == 7.0);
    CHECK(atlas::scene::terrain_line_width("tertiary", false) == 6.0);
    CHECK(atlas::scene::terrain_line_width("residential", false) == 2.0);
    CHECK(atlas::scene::terrain_line_width("", true) == 8.0);
    // the levels' starts and the far plane
    CHECK(atlas::scene::world_level_start(11) == 18'800.0);
    CHECK(atlas::scene::world_level_start(6) == 18'800.0 * 32);
    CHECK(atlas::scene::world_far(18'000.0, 1'000.0) == doctest::Approx(30'000.0));
    CHECK(atlas::scene::world_far(1e6, 1e6) == doctest::Approx(std::sqrt((2.0 * 6'371'000.0 * 1e6) + 1e12)));
}

TEST_CASE("scene: a world tile's lit places are its lights in the world's style, sized by their people; a town from "
          "L10 on, a village never, and none without the ladder; a globe's tile carries its coast") {
    using namespace atlas::tiles;
    using atlas::test::LayerBytes;
    namespace col = atlas::test::columns;
    const auto places = [](std::uint8_t z, std::uint32_t x, std::uint32_t y) {
        TileBytes tile = world_tile(z, x, y);
        TileHeader header;
        header.cells_per_side = tile.n;
        const auto tb = tile.grid_box();
        header.south = tb[0];
        header.west = tb[1];
        header.north = tb[2];
        header.east = tb[3];
        const auto b = atlas::test::cell_box(header, 5);
        LayerBytes label{.id = static_cast<std::uint8_t>(LayerId::label)};
        label.cells = {5, 5, 5, 5, 5};
        const std::initializer_list<std::pair<std::int32_t, std::int32_t>> at = {{b[0] + 10, b[1] + 10},
                                                                                 {b[0] + 20, b[1] + 20},
                                                                                 {b[0] + 30, b[1] + 30},
                                                                                 {b[0] + 40, b[1] + 40},
                                                                                 {b[0] + 50, b[1] + 50}};
        label.columns = {
            {"source", Codec::u8, col::u8({1, 1, 1, 1, 1})},
            {"index", Codec::delta, col::delta({0, 1, 2, 3, 4})},
            {"name", Codec::str, col::str(tile, {"Tokyo", "Madrid", "Utrecht", "Zaandam", "Westzaan"})},
            {"pos", Codec::point, col::point(at)},
            {"prev", Codec::point, col::point(at)},
            {"count", Codec::varu, col::varu({1, 1, 1, 1, 1})},
            {"kind", Codec::str, col::str(tile, {"city", "city", "city", "town", "village"})},
            {"tag:population", Codec::str, col::str(tile, {"37000000", " 6700000 (2021)", "360000", "72000", ""})}};
        tile.layers.back() = label;
        return tile;
    };
    const atlas::tiles::Style* world = atlas::load::world_style();
    REQUIRE(world != nullptr);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto built = [&](std::uint8_t z, std::uint32_t x, std::uint32_t y,
                           const atlas::tiles::Style* style) -> atlas::scene::WorldGeometry {
        TileBytes tile = places(z, x, y);
        const auto raw = tile.encode();
        auto decoded = decoder.decode(raw, arena);
        ATLAS_REQUIRE_OR_RETURN({}, decoded.has_value(), describe(decoded.error()));
        return atlas::scene::build_world_geometry(*decoded, style);
    };
    // L9: its three cities, by their people the first size, the second (the number its tag's text starts with, as the
    // packer ranks by it) and the third; its town not yet
    const atlas::scene::WorldGeometry g9 = built(9, 262, 168, world);
    REQUIRE(g9.lights == 3);
    const std::size_t lights9 = (4 * std::size_t{g9.triangles}) + (3 * std::size_t{g9.segments});
    REQUIRE(g9.words.size() == lights9 + (2 * std::size_t{g9.lights}));
    for (std::uint32_t i = 0; i < 3; ++i) {
        const std::uint32_t what = g9.words[lights9 + (2 * std::size_t{i}) + 1];
        CHECK((what & 0xFFU) == i);
        CHECK(((what >> 8U) & 0xFFU) == 5U);
    }
    // each light's place is its node's, a vertex word within the tile's cell 5 (row 1, column 1)
    const std::uint32_t at = g9.words[lights9];
    CHECK((at & 0xFFFFU) > 16'383U);
    CHECK((at & 0xFFFFU) < 32'768U);
    CHECK((at >> 16U) > 16'383U);
    CHECK((at >> 16U) < 32'768U);
    // L10: the town too, of the third size
    const atlas::scene::WorldGeometry g10 = built(10, 525, 336, world);
    REQUIRE(g10.lights == 4);
    CHECK((g10.words[g10.words.size() - 1] & 0xFFU) == 2U);
    // the globe's levels: a city alone (its tiles hold Natural Earth's by scalerank)
    CHECK(built(5, 16, 10, world).lights == 3);
    // none without the world's style
    CHECK(built(9, 262, 168, nullptr).lights == 0);
    // a globe's tile carries its coast among its lines: each edge of its land that is not its cells' cut, here the
    // east edge of each half cell's land, a meridian (one u), in the coast's entry with no width; the base's none
    const auto coasts = [](const atlas::scene::WorldGeometry& g) {
        std::size_t n = 0;
        const std::size_t lines = 4 * std::size_t{g.triangles};
        for (std::size_t i = 0; i < g.segments; ++i) {
            const std::uint32_t what = g.words[lines + (3 * i) + 2];
            if ((what & 0xFFU) != atlas::scene::world_palette::coast) continue;
            ++n;
            CHECK((what >> 16U) == 0U);
            CHECK((g.words[lines + (3 * i)] & 0xFFFFU) == (g.words[lines + (3 * i) + 1] & 0xFFFFU));
        }
        return n;
    };
    const atlas::scene::WorldGeometry g0 = built(0, 0, 0, world);
    CHECK(coasts(g0) >= 5);
    // the coast's segments are the last of the tile's, and counted: a frame under the ramps leaves them out
    CHECK(g0.coast == coasts(g0));
    REQUIRE(g0.segments > g0.coast);
    for (std::size_t i = 0; i < g0.segments; ++i) {
        const std::uint32_t what = g0.words[(4 * std::size_t{g0.triangles}) + (3 * i) + 2];
        CHECK(((what & 0xFFU) == atlas::scene::world_palette::coast) == (i >= g0.segments - g0.coast));
    }
    CHECK(coasts(g9) == 0);
    CHECK(g9.coast == 0);
    // nor is the world's own edge a coast: land that Mercator's square cuts beyond 85° south (Antarctica's), its last
    // latitude a little off the tile's, has its three other edges for its coast
    {
        TileBytes tile = world_tile(0, 0, 0);
        TileHeader header;
        header.cells_per_side = tile.n;
        const auto tb = tile.grid_box();
        header.south = tb[0];
        header.west = tb[1];
        header.north = tb[2];
        header.east = tb[3];
        const auto b = atlas::test::cell_box(header, 13);
        const std::int32_t last = b[0] + 500;
        REQUIRE(last < -850'000'000);
        const std::vector<atlas::test::Ring> land = {
            {.lat = {last, last, b[2] - 100'000, b[2] - 100'000},
             .lon = {b[1] + 100'000, b[3] - 100'000, b[3] - 100'000, b[1] + 100'000},
             .keep = {}}};
        LayerBytes land_layer{.id = static_cast<std::uint8_t>(LayerId::ground)};
        land_layer.cells = {13};
        land_layer.columns = {{"source", Codec::u8, col::u8({ground::source_land_base})},
                              {"index", Codec::delta, col::delta({0})},
                              {"ring", Codec::geom, col::geom(land)},
                              {"kind", Codec::str, col::str(tile, {"land"})}};
        tile.layers = {land_layer};
        const auto raw = tile.encode();
        auto decoded = decoder.decode(raw, arena);
        ATLAS_REQUIRE(decoded.has_value(), describe(decoded.error()));
        const atlas::scene::WorldGeometry cut = atlas::scene::build_world_geometry(*decoded, world);
        REQUIRE(cut.coast > 0);
        const std::size_t lines = 4 * std::size_t{cut.triangles};
        for (std::size_t i = 0; i < cut.segments; ++i) {
            const std::uint32_t from = cut.words[lines + (3 * i)] >> 16U;
            const std::uint32_t to = cut.words[lines + (3 * i) + 1] >> 16U;
            // no segment along the tile's last row of the grid
            CHECK_FALSE((from >= 65'530U && to >= 65'530U));
        }
    }
    CHECK(coasts(built(0, 0, 0, nullptr)) == 0);
    CHECK(atlas::scene::world_lights(6, "city"));
    CHECK(!atlas::scene::world_lights(9, "town"));
    CHECK(atlas::scene::world_lights(11, "town"));
    CHECK(!atlas::scene::world_lights(3, "town"));
    CHECK(!atlas::scene::world_lights(11, "village"));
}

TEST_CASE("scene: a world tile's names are its places of the kinds the world names, in the packer's rank, each with "
          "its own name, its people, its capital and its place") {
    using namespace atlas::tiles;
    using atlas::test::LayerBytes;
    namespace col = atlas::test::columns;
    TileBytes tile = world_tile(9, 262, 168);
    TileHeader header;
    header.cells_per_side = tile.n;
    const auto tb = tile.grid_box();
    header.south = tb[0];
    header.west = tb[1];
    header.north = tb[2];
    header.east = tb[3];
    const auto b5 = atlas::test::cell_box(header, 5);
    const auto b6 = atlas::test::cell_box(header, 6);
    LayerBytes label{.id = static_cast<std::uint8_t>(LayerId::label)};
    // cell 5 holds the tile's first, third and fifth places by rank and a hamlet, cell 6 its second and fourth, a
    // place without a name and a canal's name
    label.cells = {5, 5, 5, 5, 6, 6, 6, 6};
    const std::initializer_list<std::pair<std::int32_t, std::int32_t>> at = {
        {b5[0] + 10, b5[1] + 10}, {b5[0] + 20, b5[1] + 20}, {b5[0] + 30, b5[1] + 30}, {b5[0] + 40, b5[1] + 40},
        {b6[0] + 10, b6[1] + 10}, {b6[0] + 20, b6[1] + 20}, {b6[0] + 30, b6[1] + 30}, {b6[0] + 40, b6[1] + 40}};
    label.columns = {
        {"source", Codec::u8, col::u8({1, 1, 1, 1, 1, 1, 1, 0})},
        {"index", Codec::delta, col::delta({0, 2, 4, 6, 1, 3, 5, 0})},
        {"name", Codec::str,
         col::str(tile, {"Nederland", "\xd0\x9c\xd0\xbe\xd1\x81\xd0\xba\xd0\xb2\xd0\xb0", "Westzaan", "Haaldersbroek",
                         "Amsterdam", "Zaandam", "", "Zaan"})},
        {"pos", Codec::point, col::point(at)},
        {"prev", Codec::point, col::point(at)},
        {"count", Codec::varu, col::varu({1, 1, 1, 1, 1, 1, 1, 4})},
        {"kind", Codec::str, col::str(tile, {"country", "city", "village", "hamlet", "city", "town", "town", ""})},
        {"tag:population", Codec::str,
         col::str(tile, {"17000000", "13000000", "", "120", " 900000 (2021)", "72000", "5000", ""})},
        {"tag:capital", Codec::str, col::str(tile, {"", "", "", "", "yes", "", "", ""})}};
    tile.layers.back() = label;
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = tile.encode();
    const auto decoded = decoder.decode(raw, arena);
    ATLAS_REQUIRE(decoded.has_value(), describe(decoded.error()));
    const std::vector<atlas::scene::WorldName> names = atlas::scene::build_world_names(*decoded);
    REQUIRE(names.size() == 5);
    using atlas::scene::NameKind;
    CHECK(names[0].text == "Nederland");
    CHECK(names[0].kind == NameKind::country);
    CHECK(names[0].people == 17'000'000.0);
    CHECK(names[0].cell == 5);
    CHECK(names[1].text == "Amsterdam");
    CHECK(names[1].kind == NameKind::city);
    CHECK(names[1].capital);
    CHECK(names[1].people == 900'000.0);
    CHECK(names[1].cell == 6);
    CHECK(names[1].lat == doctest::Approx(Frame::degrees(b6[0] + 10)));
    CHECK(names[1].lon == doctest::Approx(Frame::degrees(b6[1] + 10)));
    // a name in its own script, as its bytes
    CHECK(names[2].text == "\xd0\x9c\xd0\xbe\xd1\x81\xd0\xba\xd0\xb2\xd0\xb0");
    CHECK_FALSE(names[2].capital);
    CHECK(names[3].text == "Zaandam");
    CHECK(names[3].kind == NameKind::town);
    CHECK(names[4].text == "Westzaan");
    CHECK(names[4].kind == NameKind::village);
    CHECK(names[4].people == 0.0);
    // by rank among the labels: under the boards' (0 or more), the country, the capital, the city, the town, the
    // village
    using atlas::scene::name_priority;
    CHECK(name_priority(names[0]) < 0);
    CHECK(name_priority(names[0]) > name_priority(names[1]));
    CHECK(name_priority(names[1]) > name_priority(names[2]));
    CHECK(name_priority(names[2]) > name_priority(names[3]));
    CHECK(name_priority(names[3]) > name_priority(names[4]));
}

TEST_CASE("scene: a world tile's names hold its waters and its rivers after its places, each by the packer's rank, a "
          "water with its area and a river with its length in the tile") {
    using namespace atlas::tiles;
    using atlas::test::LayerBytes;
    namespace col = atlas::test::columns;
    TileBytes tile = world_tile(9, 262, 168);
    TileHeader header;
    header.cells_per_side = tile.n;
    const auto tb = tile.grid_box();
    header.south = tb[0];
    header.west = tb[1];
    header.north = tb[2];
    header.east = tb[3];
    const auto b5 = atlas::test::cell_box(header, 5);
    const auto b6 = atlas::test::cell_box(header, 6);
    LayerBytes label{.id = static_cast<std::uint8_t>(LayerId::label)};
    // a cell's rows as the packer writes them: its places, then its waters, then its rivers, each by its rank in the
    // tile. Cell 5: a town, the tile's second water, a water of no kind the world names, its first river; cell 6: a
    // city, the tile's first water, a water without a name, a node's bay (no area), its second river
    label.cells = {5, 5, 5, 5, 6, 6, 6, 6, 6};
    const std::initializer_list<std::pair<std::int32_t, std::int32_t>> at = {
        {b5[0] + 10, b5[1] + 10}, {b5[0] + 20, b5[1] + 20}, {b5[0] + 30, b5[1] + 30},
        {b5[0] + 40, b5[1] + 40}, {b6[0] + 10, b6[1] + 10}, {b6[0] + 20, b6[1] + 20},
        {b6[0] + 30, b6[1] + 30}, {b6[0] + 40, b6[1] + 40}, {b6[0] + 50, b6[1] + 50}};
    label.columns = {
        {"source", Codec::u8, col::u8({1, 2, 2, 3, 1, 2, 2, 2, 3})},
        {"index", Codec::delta, col::delta({1, 1, 3, 0, 0, 0, 2, 4, 1})},
        {"name", Codec::str,
         col::str(tile, {"Zaandam", "Markermeer", "Sluis", "Zaan", "Amsterdam", "IJsselmeer / Lake IJssel", "",
                         "Hoornse Hop", "Maas / Meuse"})},
        {"pos", Codec::point, col::point(at)},
        {"prev", Codec::point, col::point(at)},
        {"count", Codec::varu, col::varu({1, 593'000'000, 5'000, 12'400, 1, 1'125'000'000, 9'000'000, 0, 8'300})},
        {"kind", Codec::str, col::str(tile, {"town", "lake", "lock", "river", "city", "lake", "lake", "bay", "river"})},
        {"tag:population", Codec::str, col::str(tile, {"72000", "", "", "", "900000", "", "", "", ""})},
        {"tag:capital", Codec::str, col::str(tile, {"", "", "", "", "yes", "", "", "", ""})}};
    tile.layers.back() = label;
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = tile.encode();
    const auto decoded = decoder.decode(raw, arena);
    ATLAS_REQUIRE(decoded.has_value(), describe(decoded.error()));
    const std::vector<atlas::scene::WorldName> names = atlas::scene::build_world_names(*decoded);
    using atlas::scene::NameKind;
    REQUIRE(names.size() == 7);
    // the places by rank, as before
    CHECK(names[0].text == "Amsterdam");
    CHECK(names[1].text == "Zaandam");
    CHECK(names[1].extent == 0.0);
    // the waters by rank: each at its point with its area; the one of no kind and the nameless one are left out
    CHECK(names[2].text == "IJsselmeer");
    CHECK(names[2].kind == NameKind::lake);
    CHECK(names[2].extent == 1'125'000'000.0);
    CHECK(names[2].cell == 6);
    CHECK(names[2].lat == doctest::Approx(Frame::degrees(b6[0] + 20)));
    CHECK(names[2].lon == doctest::Approx(Frame::degrees(b6[1] + 20)));
    CHECK(names[2].people == 0.0);
    CHECK_FALSE(names[2].lit);
    CHECK(names[3].text == "Markermeer");
    CHECK(names[3].extent == 593'000'000.0);
    CHECK(names[3].cell == 5);
    CHECK(names[4].text == "Hoornse Hop");
    CHECK(names[4].kind == NameKind::bay);
    CHECK(names[4].extent == 0.0);
    // the rivers by rank: each with its length in the tile
    CHECK(names[5].text == "Zaan");
    CHECK(names[5].kind == NameKind::river);
    CHECK(names[5].extent == 12'400.0);
    // a name of several languages' is its first part
    CHECK(names[6].text == "Maas");
    CHECK(names[6].extent == 8'300.0);
    // no water is lit
    for (const atlas::scene::WorldName& n : names) CHECK((!atlas::scene::is_water(n.kind) || !n.lit));
}

TEST_CASE("scene: stage T cuts a world tile's triangles and segments to its level's length on the bent ground, each of "
          "the tile's winding, and the poles' caps are grids of it") {
    // an L5 tile: its cells 2.8° wide, its triangles' edges up to 280 km, its level's length 99 km
    TileBytes tile = world_tile(5, 16, 10);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = tile.encode();
    auto decoded = decoder.decode(raw, arena);
    ATLAS_REQUIRE(decoded.has_value(), describe(decoded.error()));
    const atlas::scene::WorldGeometry g = atlas::scene::build_world_geometry(*decoded, nullptr);
    const double limit = atlas::scene::world_split_length(5);
    CHECK(limit == doctest::Approx(std::sqrt(8.0 * 6'371'000.0 * 3.0 * 64.0)));
    const auto& h = decoded->header;
    const double south = h.south * 1e-7;
    const double west = h.west * 1e-7;
    const double north = h.north * 1e-7;
    const double east = h.east * 1e-7;
    const auto lat_of = [&](std::uint32_t w) { return north - ((north - south) * (w >> 16U) / 65'535.0); };
    const auto lon_of = [&](std::uint32_t w) { return west + ((east - west) * (w & 0xFFFFU) / 65'535.0); };
    const auto length = [&](std::uint32_t a, std::uint32_t b) {
        const double lat = (lat_of(a) + lat_of(b)) / 2.0;
        const double dx = (lon_of(b) - lon_of(a)) * 111'320.0 * atlas::pymath::cos(lat * std::numbers::pi / 180.0);
        const double dz = (lat_of(b) - lat_of(a)) * 110'540.0;
        return atlas::pymath::hypot(dx, dz);
    };
    // twice the signed area in (u, −v): positive counter-clockwise with north up
    const auto area = [](std::uint32_t a, std::uint32_t b, std::uint32_t c) {
        const auto u = [](std::uint32_t w) { return static_cast<double>(w & 0xFFFFU); };
        const auto n = [](std::uint32_t w) { return -static_cast<double>(w >> 16U); };
        return ((u(b) - u(a)) * (n(c) - n(a))) - ((u(c) - u(a)) * (n(b) - n(a)));
    };
    REQUIRE(g.triangles > 32);
    double longest = 0;
    double total = 0;
    std::size_t backwards = 0;
    for (std::size_t t = 0; t < g.triangles; ++t) {
        const std::uint32_t a = g.words[3 * t];
        const std::uint32_t b = g.words[(3 * t) + 1];
        const std::uint32_t c = g.words[(3 * t) + 2];
        longest = std::max({longest, length(a, b), length(b, c), length(c, a)});
        const double twice = area(a, b, c);
        if (twice < 0) ++backwards;
        total += twice / 2.0;
    }
    MESSAGE(std::format("{} triangles, the longest edge {:.0f} m of {:.0f}", g.triangles, longest, limit));
    CHECK(longest <= limit * 1.001);
    CHECK(longest > limit / 2.0);
    CHECK(backwards == 0);
    // the land and the sea cover the tile once: its box's area, within the fractions' rounding
    CHECK(total == doctest::Approx(65'535.0 * 65'535.0).epsilon(1e-3));
    for (std::size_t s = 0; s < g.segments; ++s) {
        const std::size_t at = (4 * std::size_t{g.triangles}) + (3 * s);
        CHECK(length(g.words[at], g.words[at + 1]) <= limit * 1.001);
    }
    // the caps: 128 by 16 quads, every triangle of the tiles' winding, in its palette entry and cell 0
    const atlas::scene::WorldGeometry cap = atlas::scene::world_cap_geometry(atlas::scene::world_palette::ice);
    CHECK(cap.triangles == 4096U);
    CHECK(cap.segments == 0U);
    REQUIRE(cap.words.size() == 4U * 4096U);
    double cap_total = 0;
    std::size_t cap_backwards = 0;
    std::size_t cap_looks = 0;
    for (std::size_t t = 0; t < cap.triangles; ++t) {
        const double twice = area(cap.words[3 * t], cap.words[(3 * t) + 1], cap.words[(3 * t) + 2]);
        if (!(twice > 0)) ++cap_backwards;
        cap_total += twice / 2.0;
        if (cap.words[(3 * std::size_t{cap.triangles}) + t] == atlas::scene::world_palette::ice) ++cap_looks;
    }
    CHECK(cap_backwards == 0);
    CHECK(cap_looks == cap.triangles);
    CHECK(cap_total == doctest::Approx(65'535.0 * 65'535.0));
    // a cap's box follows the target's longitude
    const atlas::scene::DegreeBox north_cap = atlas::scene::world_cap_box(1, 30.0);
    CHECK(north_cap.north == 90.0);
    CHECK(north_cap.south == atlas::scene::world_cap_from);
    CHECK(north_cap.west == -150.0);
    CHECK(north_cap.east == 210.0);
    CHECK(atlas::scene::world_cap_box(-1, 0.0).south == -90.0);
}

TEST_CASE("scene: a world tile's cells over a descendant are the packer's, rows of equal latitude, not Mercator's") {
    using atlas::scene::cells_overlapping;
    using atlas::scene::world_cells_per_side;
    const auto box = [](std::uint8_t z, std::uint32_t x, std::uint32_t y) {
        return atlas::tiles::tile_box(atlas::tiles::TileKey(z, x, y));
    };
    // the globe's 3/4/2, 40.98° to 66.51° N and 0° to 45° E: its rows of cells end at 60.13°, 53.75° and 47.36° N
    const std::array<std::int32_t, 4> l3 = box(3, 4, 2);
    CHECK(cells_overlapping(world_cells_per_side, l3, l3) == 0xFFFFU);
    // 6/32/20, 52.52° to 55.78° N and 0° to 5.6° E, lies under the first column's second and third rows; by its key
    // it lies under the third alone, and the drawing ended at 53.75° N (the sweep's hole of 5 November)
    CHECK(cells_overlapping(world_cells_per_side, l3, box(6, 32, 20)) == ((1U << 4U) | (1U << 8U)));
    CHECK(atlas::scene::cells_over(world_cells_per_side, 3, 32, 20) == (1U << 8U));
    // 6/32/21 (48.92° to 52.52° N) lies under the third row alone, and 6/33/20, whose east edge is the first column's,
    // under the first column alone
    CHECK(cells_overlapping(world_cells_per_side, l3, box(6, 32, 21)) == (1U << 8U));
    CHECK(cells_overlapping(world_cells_per_side, l3, box(6, 33, 20)) == ((1U << 4U) | (1U << 8U)));
    // a child: its two columns, and the rows of the other half it reaches (4/8/5's 55.78° N, over the second row)
    CHECK(cells_overlapping(world_cells_per_side, l3, box(4, 8, 4)) == 0x0033U);
    CHECK(cells_overlapping(world_cells_per_side, l3, box(4, 8, 5)) == 0x3330U);
    // and every point of a descendant lies in one of the cells over it (tiles::cell_of, the packer's Cells.cellOf):
    // 3/4/2's L6 tiles and 9/263/168's L11 (Amsterdam's, whose rows part from Mercator's by 3.7 m), at their corners,
    // 32 units in, and middles
    for (const auto& [ancestor, gap] :
         {std::pair{atlas::tiles::TileKey(3, 4, 2), 3U}, std::pair{atlas::tiles::TileKey(9, 263, 168), 2U}}) {
        const std::array<std::int32_t, 4> a = atlas::tiles::tile_box(ancestor);
        const std::uint32_t side = 1U << gap;
        for (std::uint32_t y = 0; y < side; ++y) {
            for (std::uint32_t x = 0; x < side; ++x) {
                const atlas::tiles::TileKey k(static_cast<std::uint8_t>(ancestor.z + gap), (ancestor.x << gap) + x,
                                              (ancestor.y << gap) + y);
                const std::array<std::int32_t, 4> b = atlas::tiles::tile_box(k);
                const std::uint64_t over = cells_overlapping(world_cells_per_side, a, b);
                for (const std::int32_t lat : {b[0] + 32, (b[0] / 2) + (b[2] / 2), b[2] - 32}) {
                    for (const std::int32_t lon : {b[1] + 32, (b[1] / 2) + (b[3] / 2), b[3] - 32}) {
                        CHECK(((over >> atlas::tiles::cell_of(ancestor, lat, lon)) & 1U) == 1U);
                    }
                }
            }
        }
    }
}

TEST_CASE("scene: the globe's ice, its glaciers and its ice shelves, is the ice's entry in the world's style, and the "
          "land's without it") {
    using namespace atlas::tiles;
    using atlas::test::LayerBytes;
    namespace col = atlas::test::columns;
    TileBytes tile = world_tile(3, 4, 2);
    TileHeader header;
    header.cells_per_side = tile.n;
    const auto tb = tile.grid_box();
    header.south = tb[0];
    header.west = tb[1];
    header.north = tb[2];
    header.east = tb[3];
    const auto b = atlas::test::cell_box(header, 5);
    const std::int32_t w = (b[3] - b[1]) / 4;
    const auto square = [&](std::int32_t k) {
        return atlas::test::Ring{.lat = {b[0], b[0], b[2], b[2]},
                                 .lon = {b[1] + (k * w), b[1] + ((k + 1) * w), b[1] + ((k + 1) * w), b[1] + (k * w)},
                                 .keep = {}};
    };
    // the land under everything, a glacier on it (land cover), and an ice shelf over the sea (an area)
    LayerBytes ground{.id = static_cast<std::uint8_t>(LayerId::ground)};
    ground.cells = {5, 5, 5};
    ground.columns = {
        {"source", Codec::u8, col::u8({ground::source_land_base, ground::source_land, ground::source_area})},
        {"index", Codec::delta, col::delta({0, 1, 2})},
        {"ring", Codec::geom, col::geom({square(0), square(1), square(2)})},
        {"kind", Codec::str, col::str(tile, {"land", "glacier", "ice_shelf"})}};
    tile.layers = {ground};
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = tile.encode();
    const auto decoded = decoder.decode(raw, arena);
    ATLAS_REQUIRE(decoded.has_value(), describe(decoded.error()));
    const auto entries = [&](const atlas::tiles::Style* style) {
        const atlas::scene::WorldGeometry g = atlas::scene::build_world_geometry(*decoded, style);
        std::array<std::size_t, atlas::scene::world_palette::size> count{};
        for (std::size_t t = 0; t < g.triangles; ++t) {
            ++count.at(g.words[(3 * std::size_t{g.triangles}) + t] & 0xFFU);
        }
        return count;
    };
    const atlas::tiles::Style* world = atlas::load::world_style();
    REQUIRE(world != nullptr);
    const auto with = entries(world);
    CHECK(with[atlas::scene::world_palette::land] > 0);
    // the glacier and the shelf: as many triangles each as the land's square, all the ice's
    CHECK(with[atlas::scene::world_palette::ice] == 2 * with[atlas::scene::world_palette::land]);
    // without the world's style (no ladder) they are the land's, as before
    const auto without = entries(nullptr);
    CHECK(without[atlas::scene::world_palette::ice] == 0);
    CHECK(without[atlas::scene::world_palette::land] == 3 * with[atlas::scene::world_palette::land]);
    // the ice's colour: the ladder's #E8EEF2 by day, a fifth of it at night as the land cover's
    atlas::style::ThemeBlock theme{};
    theme.ground = {.r = 10, .g = 20, .b = 30, .a = 255};
    const std::vector<std::uint32_t> day = atlas::passes::World::palette_of(theme, false, world);
    const std::vector<std::uint32_t> night = atlas::passes::World::palette_of(theme, true, world);
    CHECK(day[atlas::scene::world_palette::ice] == 0xFFF2EEE8U);
    CHECK((night[atlas::scene::world_palette::ice] & 0xFFU) == doctest::Approx(0xE8 * 0.2).epsilon(0.02));
    CHECK(((night[atlas::scene::world_palette::ice] >> 16U) & 0xFFU) == doctest::Approx(0xF2 * 0.2).epsilon(0.02));
    // and under the night's caps the day's palette has it whole
    theme.flags |= atlas::style::theme_flag::night;
    CHECK(atlas::passes::World::day_palette_of(theme, world)[atlas::scene::world_palette::ice] == 0xFFF2EEE8U);
    // a style without the ladder leaves the entry the land's
    CHECK(atlas::passes::World::palette_of(theme, false, nullptr)[atlas::scene::world_palette::ice] == 0xFF1E140AU);
}

TEST_CASE("passes: the world's palette is the theme's land, water and roads, and its land where the style has none") {
    atlas::style::ThemeBlock theme{};
    theme.ground = {.r = 10, .g = 20, .b = 30, .a = 255};
    theme.water = {.r = 40, .g = 50, .b = 60, .a = 255};
    theme.road = {.r = 70, .g = 80, .b = 90, .a = 255};
    const std::vector<std::uint32_t> p = atlas::passes::World::palette_of(theme, false, nullptr);
    REQUIRE(p.size() == atlas::scene::world_palette::size);
    CHECK(p[atlas::scene::world_palette::land] == 0xFF1E140AU);
    CHECK(p[atlas::scene::world_palette::water] == 0xFF3C3228U);
    CHECK(p[atlas::scene::world_palette::road] == 0xFF5A5046U);
    CHECK(p[atlas::scene::world_palette::rail] == 0xFF1E140AU);
    CHECK(p[atlas::scene::world_palette::kinds + 3] == 0xFF1E140AU);
    // the day's palette under the ladder's caps: the moment's own by day (the sun 5 degrees up or more); at night the
    // ladder's ground, water and roads, and the style's colours by day
    const atlas::tiles::Style* world = atlas::load::world_style();
    REQUIRE(world != nullptr);
    for (const double elevation : {5.0, 30.0, 90.0}) {
        theme.sun_elevation = elevation;
        CHECK(atlas::passes::World::day_share(theme) == 1.0);
        CHECK(atlas::passes::World::day_palette_of(theme, world) ==
              atlas::passes::World::palette_of(theme, false, world));
    }
    // between the night's start and there, between the two, so the far world does not step when the night flag flips:
    // at the flip (the sun 2 degrees under the horizon, still day) the ladder's own, half way (1.5 degrees up) the
    // mean, a channel at a time
    theme.sun_elevation = atlas::style::night_below;
    CHECK(atlas::passes::World::day_share(theme) == 0.0);
    const std::vector<std::uint32_t> flip = atlas::passes::World::day_palette_of(theme, world);
    CHECK(flip[atlas::scene::world_palette::land] == 0xFFC2CFD6U);
    CHECK(flip[atlas::scene::world_palette::water] == 0xFF90754FU);
    CHECK(flip[atlas::scene::world_palette::road] == 0xFFB4C1C9U);
    theme.sun_elevation = 1.5;
    CHECK(atlas::passes::World::day_share(theme) == doctest::Approx(0.5));
    const std::vector<std::uint32_t> half = atlas::passes::World::day_palette_of(theme, world);
    const std::vector<std::uint32_t> own = atlas::passes::World::palette_of(theme, false, world);
    for (const std::size_t entry :
         {std::size_t{atlas::scene::world_palette::land}, std::size_t{atlas::scene::world_palette::water},
          std::size_t{atlas::scene::world_palette::road}}) {
        for (unsigned shift = 0; shift < 24; shift += 8) {
            const auto a = static_cast<double>((flip[entry] >> shift) & 0xFFU);
            const auto b = static_cast<double>((own[entry] >> shift) & 0xFFU);
            CHECK(std::abs(static_cast<double>((half[entry] >> shift) & 0xFFU) - ((a + b) / 2.0)) <= 0.5);
        }
    }
    theme.flags |= atlas::style::theme_flag::night;
    CHECK(atlas::passes::World::day_share(theme) == 0.0);
    const std::vector<std::uint32_t> night = atlas::passes::World::palette_of(theme, true, world);
    const std::vector<std::uint32_t> day = atlas::passes::World::day_palette_of(theme, world);
    REQUIRE(day.size() == atlas::scene::world_palette::size);
    CHECK(day[atlas::scene::world_palette::land] == 0xFFC2CFD6U);
    CHECK(day[atlas::scene::world_palette::water] == 0xFF90754FU);
    CHECK(day[atlas::scene::world_palette::road] == 0xFFB4C1C9U);
    theme.flags = 0;
    const std::vector<std::uint32_t> by_day = atlas::passes::World::palette_of(theme, false, world);
    for (std::size_t k = atlas::scene::world_palette::rail; k < day.size(); ++k) {
        if (k != atlas::scene::world_palette::ice && k < atlas::scene::world_palette::kinds) {
            CHECK(day[k] == by_day[k]);
        }
    }
    CHECK(night[atlas::scene::world_palette::land] == 0xFF1E140AU);
    // without the ladder the day's palette is the moment's
    theme.flags |= atlas::style::theme_flag::night;
    CHECK(atlas::passes::World::day_palette_of(theme, nullptr) ==
          atlas::passes::World::palette_of(theme, true, nullptr));
}

TEST_CASE("tiles: the cache keeps its pinned entries whatever their age, out of its budget, across a restart") {
    // ENGINE.md step 11: the world's tiles a kept country draws offline are pinned in the world's cache
    TempDir dir;
    const std::size_t entry = cache_header_bytes + 1000;
    const auto key = [](int i) {
        return CacheKey{.kind = CacheKind::chunk, .source = Sha256::of(as_bytes(std::format("chunk {}", i)))};
    };
    const std::string payload(1000, 'p');
    {
        auto opened = ExpandedCache::open(dir.path() + "/cache", 2 * entry);
        ATLAS_REQUIRE(opened);
        ExpandedCache& c = **opened;
        // two pinned, one of them not there yet; the budget of two entries is the others'
        const std::array<CacheKey, 2> pins{key(0), key(1)};
        ATLAS_REQUIRE(c.set_pinned(pins));
        CHECK(c.pins() == 2);
        CHECK(c.pinned(key(0)));
        CHECK(!c.pinned(key(2)));
        CHECK(c.pinned_bytes() == 0);
        ATLAS_REQUIRE(c.put(key(0), as_bytes(payload)));
        CHECK(c.pinned_bytes() == entry);
        for (int i = 2; i < 6; ++i) ATLAS_REQUIRE(c.put(key(i), as_bytes(payload)));
        // the oldest of the others went, the pinned one stays though it is the oldest of all
        CHECK(c.holds(key(0)));
        CHECK(!c.holds(key(2)));
        CHECK(!c.holds(key(3)));
        CHECK(c.holds(key(4)));
        CHECK(c.holds(key(5)));
        CHECK(c.entries() == 3);
        CHECK(c.bytes() == 3 * entry);
        // an entry written under a pinned key is pinned from the start, whatever its size
        ATLAS_REQUIRE(c.put(key(1), as_bytes(std::string(5 * entry, 'x'))));
        CHECK(c.pinned_bytes() == entry + cache_header_bytes + (5 * entry));
        auto too_big = c.put(key(9), as_bytes(std::string(5 * entry, 'x')));
        ATLAS_REQUIRE(!too_big);
        CHECK(too_big.error().fault == Fault::too_big);
        // emptied down to nothing, the pinned ones stay
        c.evict_to(0);
        CHECK(c.entries() == 2);
        CHECK(c.holds(key(0)));
        CHECK(c.holds(key(1)));
        // a pinned entry removed: its bytes off the pinned ones', its key pinned still
        c.remove(key(1));
        CHECK(c.pinned_bytes() == entry);
        CHECK(c.pinned(key(1)));
    }
    {
        // a restart: the pins from their file, so the open's eviction leaves the pinned entry
        auto opened = ExpandedCache::open(dir.path() + "/cache", 1);
        ATLAS_REQUIRE(opened);
        ExpandedCache& c = **opened;
        CHECK(c.pins() == 2);
        CHECK(c.holds(key(0)));
        CHECK(c.pinned_bytes() == entry);
        CHECK(atlas::test::held(c.get(key(0))).payload.size() == 1000);
        // no longer pinned: the others' again, and over the budget of one byte it goes
        ATLAS_REQUIRE(c.set_pinned({}));
        CHECK(c.pins() == 0);
        CHECK(c.pinned_bytes() == 0);
        CHECK(!c.holds(key(0)));
        CHECK(c.entries() == 0);
    }
    auto opened = ExpandedCache::open(dir.path() + "/cache", 2 * entry);
    ATLAS_REQUIRE(opened);
    CHECK((*opened)->pins() == 0);
}

TEST_CASE("tiles: a kept country's world: its packs' heads and its tiles' chunks pinned, what is lacking said, a "
          "fetched range kept") {
    // ENGINE.md step 11 (tiles/Kept.hpp): the app reads world-keep.json's packs and tiles; the core says which heads
    // and chunks the cache lacks, and pins them all
    TempDir dir;
    constexpr std::array<std::uint8_t, 3> fine = {11, 10, 9};
    const auto bytes = world_pack({world_tile(9, 262, 168), world_tile(10, 525, 336), world_tile(11, 1050, 673)}, fine);
    const auto other = world_pack({world_tile(9, 263, 168), world_tile(10, 527, 336)}, fine);
    auto cache = ExpandedCache::open(dir.path() + "/cache", std::uint64_t{1} << 22U);
    ATLAS_REQUIRE(cache);
    ExpandedCache& c = **cache;
    // a pack's head as the app fetches it: its bytes before its chunks and after them
    const auto head_of = [](const std::vector<std::byte>& pack) {
        auto whole = Pack::open(std::make_unique<MemorySource>(std::span<const std::byte>(pack)));
        ATLAS_REQUIRE_OR_RETURN((std::array<PackBytes, 2>{}), whole.has_value(), "the pack opens");
        const Section* section = find_section(whole->sections(), "chunks");
        ATLAS_REQUIRE_OR_RETURN((std::array<PackBytes, 2>{}), section != nullptr, "its chunks");
        const std::span<const std::byte> all(pack);
        const std::uint64_t end = section->offset + section->stored;
        return std::array<PackBytes, 2>{
            {{.offset = 0, .bytes = all.first(section->offset)}, {.offset = end, .bytes = all.subspan(end)}}};
    };
    // the keep: two of the first pack's three tiles and one the pack does not hold; the other pack's two
    const std::array<KeptPack, 2> keep{
        KeptPack{.kind = PackKind::world_mid,
                 .length = bytes.size(),
                 .identity = Sha256::of(bytes),
                 .tiles = {TileKey(11, 1050, 673), TileKey(9, 262, 168), TileKey(11, 1, 1)}},
        KeptPack{.kind = PackKind::world_mid,
                 .length = other.size(),
                 .identity = Sha256::of(other),
                 .tiles = {TileKey(9, 263, 168), TileKey(10, 527, 336)}}};
    const auto ref_of = [](const KeptPack& p) {
        return PackRef{.id = "p", .kind = p.kind, .length = p.length, .fetch = Store::absent(), .identity = p.identity};
    };
    // nothing on the phone: both heads lacking and pinned, no chunk known yet
    auto first = keep_packs(c, keep);
    ATLAS_REQUIRE(first);
    CHECK(first->heads == std::vector<std::uint32_t>{0, 1});
    CHECK(first->chunks.empty());
    CHECK(c.pins() == 2);
    CHECK(c.pinned({.kind = CacheKind::pack_head, .source = keep[0].identity}));
    // a range before its head: refused
    CHECK(!keep_range(c, keep[0], {.offset = 0, .bytes = std::span<const std::byte>(bytes)}));
    // the first head came: its two tiles' chunks lacking, the tile it does not hold counted, the other head lacking
    ATLAS_REQUIRE(Store::keep_head(c, ref_of(keep[0]), head_of(bytes)));
    auto second = keep_packs(c, keep);
    ATLAS_REQUIRE(second);
    CHECK(second->heads == std::vector<std::uint32_t>{1});
    ATLAS_REQUIRE(second->chunks.size() == 2);
    CHECK(second->unknown == 1);
    CHECK(second->held == 0);
    CHECK(second->chunks[0].pack == 0);
    CHECK(second->chunks[0].tile == TileKey(11, 1050, 673));
    CHECK(second->chunks[1].tile == TileKey(9, 262, 168));
    CHECK(c.pins() == 4); // two heads, two chunks
    // the first chunk's bytes fetched and handed over: kept, pinned, and no longer lacking
    const KeptChunk want = second->chunks[0];
    const std::span<const std::byte> all(bytes);
    auto kept = keep_range(c, keep[0], {.offset = want.offset, .bytes = all.subspan(want.offset, want.length)});
    ATLAS_REQUIRE(kept);
    CHECK(kept->kept == std::vector<TileKey>{TileKey(11, 1050, 673)});
    CHECK(kept->refused.empty());
    auto third = keep_packs(c, keep);
    ATLAS_REQUIRE(third);
    ATLAS_REQUIRE(third->chunks.size() == 1);
    CHECK(third->chunks[0].tile == TileKey(9, 262, 168));
    CHECK(third->held == 1);
    CHECK(third->held_bytes == want.length);
    CHECK(c.pinned_bytes() > want.length);
    // the pack's whole bytes handed over: the tile not of the keep is kept in the cache too, but not pinned, so the
    // cache emptied of the others keeps the head and the keep's two chunks
    ATLAS_REQUIRE(keep_range(c, keep[0], {.offset = 0, .bytes = all}));
    CHECK(c.entries() == 4);
    c.evict_to(0);
    CHECK(c.entries() == 3);
    auto whole = keep_packs(c, keep);
    ATLAS_REQUIRE(whole);
    CHECK(whole->chunks.empty());
    CHECK(whole->held == 2);
    // offline, the map's store opens the pack and loads the kept tiles from the cache alone
    {
        Store store(&c);
        ATLAS_REQUIRE(store.add(ref_of(keep[0])));
        Zstd zstd;
        std::vector<std::byte> raw;
        ATLAS_REQUIRE(store.load(store.find(TileKey(11, 1050, 673))[0], zstd, raw));
        ATLAS_REQUIRE(store.load(store.find(TileKey(9, 262, 168))[0], zstd, raw));
        CHECK(!store.load(store.find(TileKey(10, 525, 336))[0], zstd, raw));
    }
    // the country no longer kept: nothing pinned, and the cache emptied holds nothing
    auto none = keep_packs(c, {});
    ATLAS_REQUIRE(none);
    CHECK(none->heads.empty());
    CHECK(c.pins() == 0);
    c.evict_to(0);
    CHECK(c.entries() == 0);
}
