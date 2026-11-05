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
#include "passes/World.hpp"
#include "scene/WorldTiles.hpp"
#include "style/Theme.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
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
using atlas::test::ChunkBytes;
using atlas::test::ColumnBytes;
using atlas::test::LayerBytes;
using atlas::test::Ring;
using atlas::test::TileBytes;
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

/// Cell `c`'s box of a tile with `header`: [south, west, north, east], cells counted row by row from the north-west,
/// each edge the first grid integer at or past its share of the tile (WorldGeometry.cellBoxes), so that every point of
/// a cell is the cell rendertiles' Cells.cellOf gives it.
std::array<std::int32_t, 4> cell_box(const TileHeader& header, std::uint32_t c) {
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

/// A world tile as the packer cuts one: cell `c` land west of `(c % 3) / 2` of its width and sea east of it (all sea,
/// half and half, all land), a road across the first cell's land, a place in it.
TileBytes world_tile(std::uint8_t z, std::uint32_t x, std::uint32_t y) {
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
ChunkBytes chunk_of(TileBytes& tile) {
    ChunkBytes c;
    c.tile = {tile.level, tile.x, tile.y};
    c.raw = tile.encode();
    for (const auto& l : tile.layers) c.count += static_cast<std::uint32_t>(l.cells.size());
    c.reach = tile.reach();
    return c;
}

/// A world pack of `tiles` for `levels` (fine to coarse), as WorldPack.write lays one out: its chunks a level at a
/// time, in the levels' order.
std::vector<std::byte> world_pack(std::vector<TileBytes> tiles, std::span<const std::uint8_t> levels) {
    std::ranges::stable_sort(tiles, {},
                             [&](const TileBytes& t) { return std::ranges::find(levels, t.level) - levels.begin(); });
    std::vector<ChunkBytes> chunks;
    chunks.reserve(tiles.size());
    for (TileBytes& t : tiles) chunks.push_back(chunk_of(t));
    atlas::test::PackOptions options;
    options.head = atlas::test::head_bytes(levels, Profile::world_base);
    return atlas::test::pack_bytes(chunks, levels, format_render, options);
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
        const double dx = (lon_of(b) - lon_of(a)) * 111'320.0 * std::cos(lat * std::numbers::pi / 180.0);
        const double dz = (lat_of(b) - lat_of(a)) * 110'540.0;
        return std::hypot(dx, dz);
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
    const atlas::scene::WorldGeometry cap = atlas::scene::world_cap_geometry(atlas::scene::world_palette::south_cap);
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
        if (cap.words[(3 * std::size_t{cap.triangles}) + t] == atlas::scene::world_palette::south_cap) ++cap_looks;
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
}
