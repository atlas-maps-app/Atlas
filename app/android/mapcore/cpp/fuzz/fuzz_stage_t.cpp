// Fuzzes stage T, the CPU's build of each tile the residency loads (scene/Residency.cpp's passes' build), with the
// tiles fuzz_load's scenes cut into (fuzz/scenes.hpp): a region's tiles at its two levels, its window's and the living
// map's, each written into a pack in memory with its head and style as the packer writes them (load/PackHead.hpp), the
// pack opened as the store opens one, and each of its tiles decoded and built by every pass's CPU part: the ground
// (and its reflections and words), the buildings and their heap's words, the trees, things, region items, doors and
// shopfronts, the canal names, and the sun's solids and samples. A tile is built at one of its head's bands' ladder
// levels, by the input's size (size / 16), as a frame at that distance loads it.
//
// Whatever a scene holds, stage T must build it or leave it out without failing an invariant, without reading out of
// bounds and without running without bound: no tag's value, no height and no length may end the process. The pack the
// writer writes must open, and each tile in it decode. The walls and the sun's samples must count the same windows,
// each window's sample where the walls number it (windows_agree); a tile's railings take at most
// geometry::most_tile_railing_pieces. Seeds: fuzz/corpus/fuzz_stage_t (fuzz_load's seeds and crash inputs, a big road's
// lanes and widths, a bridge's railings, and every input that crashed it).
#include "base/arena.hpp"
#include "base/assert.hpp"
#include "fuzz/scenes.hpp"
#include "geometry/Doors.hpp"
#include "geometry/Ground.hpp"
#include "geometry/Items.hpp"
#include "geometry/Shopfronts.hpp"
#include "geometry/Things.hpp"
#include "geometry/Trees.hpp"
#include "geometry/Walls.hpp"
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/PackHead.hpp"
#include "load/Regions.hpp"
#include "osm/Overpass.hpp"
#include "passes/Ground.hpp"
#include "passes/Items.hpp"
#include "passes/Things.hpp"
#include "passes/Trees.hpp"
#include "sun/Samples.hpp"
#include "text/CanalNames.hpp"
#include "tiles/Container.hpp"
#include "tiles/Json.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"
#include "twin/building/BuildingTile.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace geo = atlas::geometry;
namespace tl = atlas::tiles;

namespace {

/// The pack of `cut` with the head and style `facts` give, opened, or nothing where the writer refuses a tile (one the
/// format cannot hold, as a load's cut would be refused).
std::optional<tl::Pack> pack_of(const std::vector<tl::TileFill>& cut, const atlas::load::PackFacts& facts) {
    std::vector<tl::PackTile> tiles;
    for (const tl::TileFill& t : cut) {
        auto raw = tl::encode_tile(t);
        if (!raw) return std::nullopt;
        const auto reach = tl::reach_of(t);
        if (!reach) return std::nullopt;
        std::uint32_t count = 0;
        for (const tl::LayerFill& l : t.layers()) count += l.count();
        tiles.push_back(
            {.key = tl::TileKey(t.level(), t.x(), t.y()), .raw = std::move(*raw), .count = count, .reach = *reach});
    }
    const auto sections = atlas::load::pack_sections(facts, {});
    ATLAS_ASSERT(sections.has_value(), "the fuzz target's head and style do not write");
    auto bytes = tl::write_pack(
        tiles, {.levels = facts.levels, .manifest = "{}", .head = sections->head, .style = sections->style});
    if (!bytes) return std::nullopt;
    auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(std::move(*bytes)));
    if (!pack) {
        (void)std::fprintf(stderr, "fuzz_stage_t: %s\n", tl::describe(pack.error()).c_str());
        ATLAS_UNREACHABLE("a pack the writer wrote does not open");
    }
    return std::move(*pack);
}

/// The walls and the sun's samples count the same windows: a window's sample is found by its place in the tile's
/// window group, which the walls number (geometry/Walls.cpp's number_windows) and the samples walk again
/// (sun/Samples.cpp's wall_windows). The group holds as many as the walls' windows, and each windowed wall's, from its
/// first, are its record's on its edge, in its order (a window's place on its wall as a sample holds it, at most
/// 0xFFFF). A houseboat cabin's panes have none.
void windows_agree(const geo::TileBuildings& walls, const atlas::sun::TileSamples& s) {
    ATLAS_ASSERT(s.windows_first <= s.size() && s.size() - s.windows_first == walls.windows,
                 "the walls and the sun's samples count different windows");
    for (const geo::WindowWall& w : walls.window_walls) {
        if (w.style == geo::WindowStyle::cabin) continue;
        const geo::Prim& wall = walls.edged.at(w.wall);
        const geo::BuildingItem& item = walls.items.at(wall.item);
        const std::uint32_t count = std::uint32_t{w.levels} * w.cols;
        ATLAS_ASSERT(w.first <= walls.windows && count <= walls.windows - w.first, "a wall's windows past the tile's");
        for (std::uint32_t k = 0; k < count; ++k) {
            const std::uint32_t i = s.windows_first + w.first + k;
            ATLAS_ASSERT(s.kind[i] == atlas::sun::Kind::window &&
                             s.source[i] == static_cast<atlas::sun::Source>(item.source) && s.owner[i] == item.record &&
                             s.edge[i] == wall.edge && s.part[i] == std::min<std::uint32_t>(k, 0xFFFFU),
                         "a window's sample is not the window's");
        }
    }
}

/// Every pass's CPU build of `tile` at ladder `level`, as the residency's passes build it (their kinds any rows).
void build_tile(const tl::Tile& tile, const tl::RenderHead& head, const tl::Style& style, std::uint8_t level,
                const atlas::sun::Region& sun) {
    const double ox = head.frame.x(tile.header.west);
    const double oz = head.frame.z(tile.header.south);
    const bool lean = tile.header.level < 14;

    geo::GroundTile ground = geo::build_ground(tile, head, style, level, {.x = ox, .z = oz});
    atlas::passes::prepare_reflections(ground);
    (void)geo::pack(ground);

    const geo::TileBuildings buildings = geo::build_buildings(
        {.tile = &tile,
         .head = &head,
         .level = level,
         .region = {.water_y = style.water_y,
                    .hull = style.hull,
                    .deck = style.deck,
                    .paris = style.paris,
                    .kit_eaves = style.kit ? std::optional<double>(style.kit->eaves) : std::nullopt,
                    .kit_band = style.kit ? style.kit->band : std::nullopt,
                    .kit_boards = style.kit ? style.kit->boards : std::nullopt},
         .kind = 1,
         .share = {},
         .blocks = 1,
         .lean = false});
    if (!buildings.items.empty()) {
        atlas::passes::BuildingTile layout;
        (void)atlas::passes::heap_words(buildings, ox, oz, layout);
    }

    const geo::TileTrees trees =
        geo::build_trees({.tile = &tile, .head = &head, .style = &style, .kind = 2, .lean = lean});
    if (!trees.trees.empty()) (void)atlas::passes::tree_words(trees, ox, 0.0, oz, lean);
    const geo::TileThings things = geo::build_things({.tile = &tile, .head = &head, .kind = 3});
    ATLAS_ASSERT(std::ranges::count(things.things, geo::ThingKind::railing, &geo::Thing::kind) <=
                     geo::most_tile_railing_pieces,
                 "a tile's railings past the most a tile holds");
    if (!things.things.empty()) (void)atlas::passes::thing_words(things, ox, 0.0, oz);
    const geo::TileRegionItems items = geo::build_region_items({.tile = &tile, .head = &head, .kind = 4});
    if (!items.items.empty()) (void)atlas::passes::item_words(items, ox, 0.0, oz);
    (void)geo::build_doors({.tile = &tile, .head = &head, .kind = 5});
    (void)geo::build_shopfronts({.tile = &tile, .head = &head, .board = 6, .blade = 7, .table = 8, .glow = 9});

    if (const tl::Layer* labels = tile.layer(tl::LayerId::label)) {
        std::vector<atlas::text::CanalCandidate> candidates;
        atlas::text::canal_candidates(*labels, head.frame, candidates);
    }

    std::vector<atlas::sun::Deck> decks;
    atlas::sun::collect_decks(tile, head, level, decks);
    windows_agree(buildings, atlas::sun::make_tile(tile, head, sun, level, decks).samples);
    (void)atlas::sun::tile_tallest(tile, level);
}

/// Each tile of `pack` decoded and built at one of its head's bands' levels, by the input's size; and its canal names.
void build_pack(const tl::Pack& pack, std::size_t size, bool window) {
    const tl::RenderHead& head = *pack.head();
    const tl::Style& style = *pack.style();
    const auto bands = head.bands();
    const std::uint8_t level = bands[(size / 16) % bands.size()].tolerance;
    const atlas::sun::Region sun = atlas::sun::region_of(style);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    for (const tl::ChunkRef& chunk : pack.chunks().chunks()) {
        atlas::Arena arena(std::size_t{1} << 16U, atlas::Growth::grows, "fuzz_stage_t");
        const auto tile = pack.decode(chunk, zstd, decoder, arena, raw, scratch);
        if (!tile) {
            (void)std::fprintf(stderr, "fuzz_stage_t: %s\n", tl::describe(tile.error()).c_str());
            ATLAS_UNREACHABLE("a tile the writer wrote does not decode");
        }
        build_tile(*tile, head, style, level, sun);
    }
    std::ignore = atlas::text::pack_canals(pack, window);
}

// the element pack's SHA-256 a head names: any will do
constexpr std::string_view source = "0000000000000000000000000000000000000000000000000000000000000000";

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto doc = atlas::osm::read_overpass(bytes);
    if (!doc) return 0;
    const atlas::load::RegionConfig region = atlas::fuzz::region_of(size);
    if (const auto regional = atlas::load::load_region(*doc, region, atlas::fuzz::simplified_of(size))) {
        // R as a region's packer reads it: its time zone, and the tradition it names
        const std::string r =
            region.tradition ? R"({"tz": "Europe/Amsterdam", "tradition": "fuzz"})" : R"({"tz": "Europe/Amsterdam"})";
        const auto r_value = tl::read_json(std::as_bytes(std::span(r.data(), r.size())));
        ATLAS_ASSERT(r_value.has_value(), "the fuzz target's R does not read");
        const auto facts = [&](std::span<const std::uint8_t> levels) {
            return atlas::load::PackFacts{.profile = tl::Profile::region,
                                          .region = region.name,
                                          .levels = levels,
                                          .frame = region.frame,
                                          .engine_version = "fuzz",
                                          .source = source,
                                          .r = r_value->root(),
                                          .tradition = region.tradition.get()};
        };
        static constexpr std::array<std::uint8_t, 2> region_levels = {14, 12};
        if (const auto tiles = atlas::load::cut_region(*regional, region.frame, {})) {
            if (const auto pack = pack_of(*tiles, facts(region_levels))) build_pack(*pack, size, false);
        }
        static constexpr std::array<std::uint8_t, 1> window_levels = {14};
        if (const auto tiles = atlas::load::cut_window(*regional, region.frame, {}, atlas::fuzz::window())) {
            if (const auto pack = pack_of(*tiles, facts(window_levels))) build_pack(*pack, size, true);
        }
    }
    const auto scene = atlas::load::load_amsterdam(*doc, atlas::load::westerkerk_frame(),
                                                   atlas::load::amsterdam_palette(), atlas::fuzz::looks_of(size));
    if (!scene) return 0;
    if (const auto tiles = atlas::load::cut_living(*scene, atlas::load::westerkerk_frame())) {
        static constexpr std::array<std::uint8_t, 1> living_levels = {14};
        const atlas::load::PackFacts facts{.profile = tl::Profile::living,
                                           .region = "fuzz_living",
                                           .levels = living_levels,
                                           .frame = atlas::load::westerkerk_frame(),
                                           .engine_version = "fuzz",
                                           .source = source,
                                           .r = {},
                                           .tradition = nullptr};
        if (const auto pack = pack_of(*tiles, facts)) build_pack(*pack, size, false);
    }
    return 0;
}
