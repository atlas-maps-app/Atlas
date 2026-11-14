// The world's tiles beyond the countries' (ENGINE.md step 5, "The world's mid-zoom base" and "the world's cover"; its
// step 2, "The world pass and its cover, flat"): the store's world_mid packs (L6 to L11) and the globe (world_base, L0
// to L5), drawn by the world pass (passes/World.hpp) under every country's tiles once the camera is more than 18 km
// from its target, where today's map said "Zoom in to see the map".
//
// The cover walks a quadtree from L0: a tile the frustum sees (the view plus 40 px, out to the world's far plane) is
// split while its nearest point is nearer than its level's start, 18.8 km·2^(11 − z) (where its tiles are about 910 dp
// wide, as L12's are at 9.4 km), down to L11. Each tile it ends at is drawn by the tile itself where the store holds
// it, else by its nearest ancestor the store holds (the open sea has no world tile; beyond the packs' blocks only the
// globe is there). A tile not resident yet is wanted, and meanwhile its nearest resident ancestor draws its cells over
// it (Cells.hpp's cells_over: ancestors by cells); the globe's L0 to L3 (0.92 MB for the planet) are kept resident from
// the first frame on, so once the globe is on the phone a zoom out never shows nothing. No frame waits for a tile: one
// worker of the world's own decodes, triangulates and places each wanted tile, nearest first, and a frame draws what is
// resident.
//
// The resident tiles hold up to 85 % of the heap's buffer as it is, past which the least lately drawn go; where the
// tiles a frame draws take more, a build's range lies beyond the buffer and it doubles, up to the heap's budget. A tile
// no piece of the heap holds whole is placed in parts, each in the largest piece free then (place): the tiles come
// and go in their own sizes, so the heap's free bytes lie in pieces between those drawn, and at the pitched views from
// 60 to 1,000 km over the Netherlands 13 to 28 MB were free in pieces of 2.2 to 5.6 MB while their farthest tiles of 5
// to 8 MB found none (ENGINE.md step 11). A tile whose build finds no room even so is built again once the pieces hold
// it, not before: the tiles the frame does not draw are let go of for it, least lately drawn first, and it waits while
// their bytes come back, the frames coming meanwhile; where their going would not make it it is held back, its place
// drawn by its nearest resident ancestor, final, until the heap's pieces change. So no tile is built again at rest:
// those views rebuilt theirs every frame, each build asking for the next.
//
// A tile's geometry (stage T, on the worker) lies in the world's own heap, one buffer of a sixth of the geometry heaps'
// budget (42.7 MB at the user's phone's 256 MB, 21 MB at 128 MB), grown up to a third of it, beside the heaps' two
// buffers: its fills, three vertex words a triangle and a word a triangle (its colour and cell), then its lines, three
// words a segment, then its lights, two words each; a part's lie as a tile's. Painter's order within a tile is the
// world's: the land under everything (GROUND source 2), the land cover by LAND's order (source 1), the water (the sea,
// then lakes, reservoirs and riverbanks), the areas over the water (source 0: the globe's ice shelves); then the
// rivers, the roads by class, the railways, the big roads and the runways. Vertices are the tile's 16-bit fractions of
// its box (at most 1/65,535 of a tile: 0.014 dp at a level's start), placed in the frame by the world pass.
//
// Beyond a camera 18 km from its target the ground bends (ENGINE.md step 5, "The bend"; scene/WorldBend.hpp): the walk
// takes each tile's box on the bent ground and, once the world is the sphere, passes over a tile wholly behind the
// horizon. Stage T cuts each triangle and segment longer than its level keeps (a chord of it sags from the bent
// ground by half a pixel at the level's start: 12 km at L11), so every level's tiles are as round as their pixels,
// and the world pass draws each tile's coarse ancestor at L3 (the floor, always resident; an L3 tile's or a coarser
// one's at its parent's level) under it, so the sub-pixel cracks where tiles of two levels meet show the ground's
// colours. An ancestor's cells over a tile are taken by their boxes (Cells.hpp's cells_overlapping), as the packer cuts
// its rows of equal latitude. The poles' caps beyond Mercator's 85.05° are two
// grids of their own, from 84° (under the tiles' last degree): the sea in the north, Antarctica's land in the south.
//
// Thread-safe as the world uses it: update and frame on the render thread; the worker its own.
#pragma once

#include "base/arena.hpp"
#include "gpu/Device.hpp"
#include "scene/Cells.hpp"
#include "scene/Heaps.hpp"
#include "scene/WorldBend.hpp"
#include "scene/WorldNames.hpp"
#include "tiles/Store.hpp"

#include <array>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace atlas::scene {

/// How far from its target a camera is before the world draws, metres (Kotlin's PackList.LEVELS_REACH): up to it the
/// countries' cover alone, as before the world, but where the camera's place has no country's levels (the camera's
/// flag world_under: WorldTiles::update's `under_far`), where the world lies under the cover from every distance.
inline constexpr double world_from = 18'000.0;

/// How long a world tile fades in from the first frame it can be drawn, seconds (the countries' cover's): its
/// ancestor drawn under it until the fade ends, and no frame waits for it. The globe's resident levels (L0 to L3) are
/// drawn whole from the first frame: the floor every fade lies over.
inline constexpr double world_fade_seconds = 0.18;

/// The world's finest level, and the levels per tile's side of cells.
inline constexpr std::uint8_t world_finest = 11;
inline constexpr std::uint8_t world_cells_per_side = 4;

/// The world's blocks (ENGINE.md step 5, "Packs"): a coarse pack per L3 tile holds its L6 to L8 tiles, a fine pack per
/// L6 tile its L9 to L11.
inline constexpr std::uint8_t world_coarse_block = 3;
inline constexpr std::uint8_t world_fine_block = 6;
inline constexpr std::uint8_t world_coarse_first = 6;
inline constexpr std::uint8_t world_fine_first = 9;

/// The block of a world pack by its store id (`world/<z>/<x>/<y>`, tiles::world_pack_id), or nothing for another id.
[[nodiscard]] std::optional<tiles::TileKey> world_block_of(std::string_view id) noexcept;

/// A world tile wanted whose chunk is not on the phone (ENGINE.md step 6): its remote pack's id and the chunk's range
/// in the pack, which the app fetches and keeps (tiles::Store::keep).
struct WorldWant {
    tiles::TileKey tile;
    std::string pack;
    tiles::Sha256Digest identity{}; ///< the pack's (world.idx's SHA-256 of it)
    std::uint64_t offset = 0;
    std::uint64_t length = 0;
};

/// Level `z`'s start: the camera distance from which its tiles are drawn, 18.8 km·2^(11 − z).
[[nodiscard]] double world_level_start(std::uint8_t z) noexcept;

/// The world's far plane for a camera `distance` metres from its target with its eye `height` metres up: 30 km·(D /
/// 18 km)², up to the horizon of a sphere of 6,371 km (√(2Rh + h²)).
[[nodiscard]] double world_far(double distance, double height) noexcept;

/// A tile the cover walk ends at, the tile that draws it (itself where the store holds it, else its nearest ancestor
/// the store holds), and the eye's distance to its nearest point.
struct WorldLeaf {
    tiles::TileKey key;
    tiles::TileKey holder;
    double distance = 0;
};

/// The cover's walk (the file's head): every tile it ends at, in the walk's order; `held` says whether the store holds
/// a world tile. Ends at none without a held ancestor. Each tile's box is its ground's on `bend`'s bent ground
/// (world_box), and a tile behind the horizon is passed over. Each level starts at `reach` times its start (the
/// triangle governor's 2^−bias, scene/Governor.hpp; 1 at bias 0).
[[nodiscard]] std::vector<WorldLeaf> walk_world(const Frustum& frustum, const view::GroundFrame& frame,
                                                const std::function<bool(const tiles::TileKey&)>& held,
                                                const WorldBend& bend, double reach = 1.0);

/// The longest edge stage T leaves a level-`z` tile's triangles and segments: a chord that long sags from the bent
/// ground by the square of its length over 8R, half a pixel at the level's start (3 m at L11, twice as much a level
/// coarser).
[[nodiscard]] double world_split_length(std::uint8_t z) noexcept;

/// The floor's level: each tile drawn finer has its ancestor there drawn under it (the globe's resident L3).
inline constexpr std::uint8_t world_floor = 3;

/// The latitude from which the poles' caps are drawn (under the tiles' last degree before Mercator's 85.05°).
inline constexpr double world_cap_from = 84.0;

/// The colours a world tile's primitives name: the world pass's palette, from the moment's theme and the world's
/// style (passes/World.cpp).
namespace world_palette {
inline constexpr std::uint8_t land = 0;    ///< the land under everything: the theme's ground (the plate's colour)
inline constexpr std::uint8_t water = 1;   ///< the sea, lakes and rivers: the theme's water
inline constexpr std::uint8_t road = 2;    ///< the roads: the theme's road
inline constexpr std::uint8_t rail = 3;    ///< the railways: the style's rails
inline constexpr std::uint8_t asphalt = 4; ///< the big roads: the style's asphalt
inline constexpr std::uint8_t runway = 5;  ///< the runways: the style's runway pavement
inline constexpr std::uint8_t ice = 6;     ///< glaciers, ice shelves and the south pole's cap: the ladder's ice
inline constexpr std::uint8_t coast = 7;   ///< the coast: the ladder's line along the land's edge (the globe's tiles)
inline constexpr std::uint8_t kinds = 8;   ///< the land cover: kinds + its LAND kind (the style's land table)
inline constexpr std::uint32_t size = 64;  ///< the palette's entries
} // namespace world_palette

/// A world tile's geometry as the heap holds it: its fills' vertex words (three a triangle: its box's fractions, u in
/// the low half and v, from the north, in the high), a word a triangle (its colour, and its cell at bit 8), then three
/// words a line's segment (its two ends, and its colour, its cell at bit 8 and its width in decimetres from bit 16),
/// then two words a city's light (its place as a vertex word, and its size, 0 the largest, with its cell at bit 8).
/// A globe's tile (L0 to L5) carries its land's edge as the last of its lines (`coast` of its `segments`), in the
/// coast's palette entry with no width (the ladder's): each edge of its land's rings that is not its cells' cut. The
/// world pass leaves them out of a frame under the ramps, where the coast has no weight.
struct WorldGeometry {
    std::vector<std::uint32_t> words;
    std::uint32_t triangles = 0;
    std::uint32_t segments = 0;
    std::uint32_t coast = 0;
    std::uint32_t lights = 0;
};

/// The places of a level-`z` world tile that are lit at night (ENGINE.md step 5, "City lights"): the globe's (L0 to
/// L5, which hold Natural Earth's by scalerank), the base's cities, and its towns from L10.
[[nodiscard]] bool world_lights(std::uint8_t z, std::string_view kind) noexcept;

/// The width the renders' terrain lines take for a road of class `hw` (regions.py's LW, the widths of
/// relief.py's lines: a motorway 12 m, a trunk 10, a primary 8, a secondary 7, a tertiary 6), metres; 8 for another big
/// road and 2 for another road, as LW's defaults.
[[nodiscard]] double terrain_line_width(std::string_view hw, bool big) noexcept;

/// Stage T of a world tile: its geometry, its land cover coloured by `style`'s LAND kinds (none: as the land), each
/// triangle and segment cut to its level's world_split_length, and its lit places' lights where the style has the
/// ladder (their sizes by its people).
[[nodiscard]] WorldGeometry build_world_geometry(const tiles::Tile& tile, const tiles::Style* style);

/// The places a world tile names (scene/WorldNames.hpp), in the tile's order, the packer's rank: its label layer's
/// place nodes of a kind the world names, each with its `name`, its people (the number its population's text starts
/// with) and whether it is a capital. After them its waters (the layer's source 2: each at its name's point, with its
/// area) and its rivers (source 3: each at the middle of its longest piece in the tile, with its length there), each
/// by the packer's rank.
[[nodiscard]] std::vector<WorldName> build_world_names(const tiles::Tile& tile);

/// Where a world tile is water, coarsely (a water's name is stood where its water shows, scene/WaterStand.hpp): a
/// bit a cell of 64 by 64 over its box, rows from the north (row r's bit c, columns from the west), set where the
/// tile's fills leave the water's colour at the cell's middle (a later fill over it, the ice's, takes it back). The
/// rivers' lines are not in it.
inline constexpr std::uint32_t water_mask_side = 64;
using WaterMask = std::array<std::uint64_t, water_mask_side>;
[[nodiscard]] WaterMask world_water_mask(const WorldGeometry& geometry) noexcept;

/// A pole's cap: a grid of 128 by 16 quads over its box, two triangles each in the tiles' winding, in palette entry
/// `palette` and cell 0.
[[nodiscard]] WorldGeometry world_cap_geometry(std::uint8_t palette);

/// The plain land: one quad over its box, two triangles in the tiles' winding, in the land's palette entry and cell 0.
/// Drawn at a tile's box where the world lies under the cover (WorldTiles::update's `under_far`) and has no tile of its
/// fine packs there yet, as the plate lay there before.
[[nodiscard]] WorldGeometry world_blank_geometry();

/// A part of a world tile's geometry in the world's heap: its words laid out as a tile's (its triangles' vertices,
/// their words, its segments, its lights), so the world pass draws it as it draws a tile.
struct WorldPart {
    HeapRange range;
    std::uint32_t triangles = 0;
    std::uint32_t segments = 0;
    std::uint32_t coast = 0; ///< the last of its segments: its coast's
    std::uint32_t lights = 0;
};

/// A resident world tile.
struct WorldTile {
    tiles::TileKey key;
    std::array<std::int32_t, 4> box{}; ///< south, west, north, east (the grid's 1e-7°)
    /// Its geometry: one part where a piece of the heap holds it whole, else in parts (WorldTiles::place), each list
    /// in the tile's order across them: the first part's triangles before the second's, and so its segments and
    /// lights. The world pass draws all its parts' fills, then their lines, so its pixels are those of one part.
    std::vector<WorldPart> parts;
    std::uint32_t triangles = 0; ///< its parts' all told, as the three below
    std::uint32_t segments = 0;
    std::uint32_t coast = 0; ///< the last of its segments: its coast's
    std::uint32_t lights = 0;
    std::vector<WorldName> names; ///< the places and the waters it names, by rank (build_world_names)
    WaterMask water{};            ///< where it is water (world_water_mask)
    std::uint64_t drawn = 0;      ///< the last frame that drew it
    double shown = -1;   ///< when the frames drawing it without a break began, seconds (its fade's start); -1: none
    bool pinned = false; ///< the globe's L0 to L3: never let go of
    bool ever_drawn = false;
    std::int8_t cap = 0; ///< a pole's cap: 1 the north's, -1 the south's (its box the frame's: world_cap_box)
    bool blank = false;  ///< the plain land (world_blank_geometry): its box each draw's own (WorldDraw::box)
    /// The pack it was built from: once the store no longer holds it (a new version of the world), the tile is let go.
    std::shared_ptr<const tiles::StorePack> pack;

    /// Its parts' bytes in the heap.
    [[nodiscard]] vk::DeviceSize bytes() const noexcept {
        vk::DeviceSize n = 0;
        for (const WorldPart& p : parts) n += p.range.size;
        return n;
    }
};

/// A pole's cap's box in a frame whose camera's target lies at longitude `lon` (degrees): from world_cap_from to the
/// pole, 180° either side of the target.
[[nodiscard]] DegreeBox world_cap_box(std::int8_t cap, double lon) noexcept;

/// A tile a frame draws, its cells (bit r · 4 + c, rows from the north) and how far it has faded in (1: done; under
/// 1 the tile is drawn over its nearest resident ancestor's cells, which the frame draws first).
struct WorldDraw {
    const WorldTile* tile = nullptr;
    std::uint64_t cells = 0;
    float fade = 1.0F;
    /// The plain land's box (a blank tile's draw): the place of a tile the world has nothing fine of yet.
    DegreeBox box{};
    /// The cells whose lights it draws and whose places it names: those it draws for a tile the walk ends at, not the
    /// floor's under another tile's nor an ancestor's under one fading in (a city is lit once, and named once).
    std::uint64_t light_cells = 0;
};

/// What a frame draws of the world: its tiles coarse to fine, from the world's heap, in the frame drawn.
struct WorldFrame {
    std::vector<WorldDraw> draws;
    vk::Buffer buffer;
    vk::DeviceSize buffer_bytes = 0;
    view::GroundFrame frame;
    /// The frame's bend: the world pass places each tile on its ground.
    WorldBend bend;
    /// The world's style (its packs'), for the palette; null where the packs carry none.
    const tiles::Style* style = nullptr;
    /// The tiles the cover wants that are not resident yet, but those held back: 0 once every tile in view draws
    /// itself or is held back.
    std::size_t wanted = 0;
    /// The tiles in view held back for room: their builds found none, and the going of no tile the frame leaves out
    /// makes a piece of the heap that holds them, so their places are drawn by their nearest resident ancestors, final
    /// (a world heap too small for the view), until the heap's pieces change.
    std::size_t held = 0;
    /// Whether a tile drawn is still fading in (a later frame draws it otherwise).
    bool fading = false;
    /// Whether a wanted tile waits for the bytes let go of for it, which come back once the frames that may read them
    /// have finished: the frames keep coming until it is built (World::world_fading).
    bool settling = false;
    /// The cells in view no resident tile draws (no ancestor down to the globe's L0 holds them): 0 once the globe's
    /// coarse levels are resident, so a zoom out never shows the frame's clear colour.
    std::size_t bare = 0;
    /// Drawn up to world_from, under the cover, where no country's levels are (WorldTiles::update's `under_far`): the
    /// cover's tiles are the picture where it has any, and the world lies under them.
    bool under = false;
    /// Measurement (branch worldsums, never lands): FNV-1a sums of the frame's draws (each tile's key, cells, light
    /// cells, fade, box and its parts' counts), of the heap's words they draw (read from its mapping as the frame is
    /// listed) and of its frame and bend.
    std::uint64_t sum_draws = 0;
    std::uint64_t sum_words = 0;
    std::uint64_t sum_view = 0;
};

/// Whether `frame` draws water at (`lat`, `lon`), by the mask of the finest tile it draws there (WorldTile::water);
/// nothing where it draws no tile of the packs (off its tiles, a pole's cap, the plain land).
[[nodiscard]] std::optional<bool> world_water_at(const WorldFrame& frame, double lat, double lon) noexcept;

/// What the world holds: resident tiles, their heap bytes and triangles, builds done and failed.
struct WorldStats {
    std::size_t resident = 0;
    std::size_t parts = 0; ///< the resident tiles' parts: more than them where a tile lies in parts
    vk::DeviceSize bytes = 0;
    std::uint64_t triangles = 0;
    std::uint64_t built = 0;
    std::uint64_t failed = 0;
    double build_ms = 0; ///< the builds' time on the worker, all told
    /// The heap's budget now (the buffer's, once a doubling the device refused ended it there) and its buffer's bytes
    /// now (the budget's once it has grown to it), the bytes of the resident tiles the last frame drew and of the
    /// pinned ones (the globe's L0 to L3), and the budget's largest free piece: a tile larger than that finds no room,
    /// whatever the bytes free.
    vk::DeviceSize capacity = 0;
    vk::DeviceSize buffer = 0;
    vk::DeviceSize free = 0; ///< the heap's bytes no range holds (a range let go of among those held until freed)
    vk::DeviceSize drawn_bytes = 0;
    vk::DeviceSize pinned_bytes = 0;
    vk::DeviceSize largest_free = 0;
    /// The builds that found no room in the heap, all told, and the bytes the last of them asked for.
    std::uint64_t no_room = 0;
    std::uint32_t no_room_bytes = 0;
    /// Of the last frame: the bytes of the resident ancestors it drew for tiles not resident (their stand-ins, but the
    /// pinned), and of the tiles it wanted that found no room (the bytes their builds asked for), with the wanted
    /// tiles of no such build yet. Drawn less stand-ins plus wanted: the room the frame takes whole.
    vk::DeviceSize stand_in_bytes = 0;
    vk::DeviceSize wanted_bytes = 0;
    std::size_t wanted_unsized = 0;
    /// The stand-ins let go of for the tiles they stood in for, all told (WorldTiles::trade).
    std::uint64_t traded = 0;
};

class WorldTiles {
public:
    /// The world's tiles of `store`, in a heap on `device` of one buffer of `initial` bytes, doubled where a tile's
    /// range lies beyond it up to `budget` (each frame binds it as it is when the frame begins), each doubling within
    /// the device's budget too (HeapBudget::within_device): one the device refuses ends the budget at the buffer.
    [[nodiscard]] static gpu::Result<std::unique_ptr<WorldTiles>>
    create(const gpu::Device& device, const tiles::Store& store, vk::DeviceSize initial, vk::DeviceSize budget);
    WorldTiles(const WorldTiles&) = delete;
    WorldTiles& operator=(const WorldTiles&) = delete;
    WorldTiles(WorldTiles&&) = delete;
    WorldTiles& operator=(WorldTiles&&) = delete;
    ~WorldTiles();

    /// Called on the worker as each tile is built (the frame loop's frame request).
    void set_wake(std::function<void()> wake);

    /// Frame `number`'s world at `seconds` (the frame's clock): its cover for `view`'s camera (in `frame`'s metres)
    /// `distance` metres from its target, on its bent ground, each level from `reach` times its start (walk_world),
    /// the tiles it draws and those it wants, the tiles built since the last frame taken in and room made. Nothing is
    /// drawn up to world_from, or without a world pack in the store.
    ///
    /// With `under_far` (metres; 0: none) the world is drawn up to world_from too, out to that far plane, under the
    /// cover (the camera's flag world_under: no country's levels at its place, so beyond its windows the cover has
    /// nothing; ENGINE.md step 3): there each tile in view is drawn by the fine packs' tile holding it (L9 to L11) once
    /// resident, meanwhile by its nearest resident ancestor of those levels, else by the plain land at its box, as the
    /// plate lay there, so a coarse level never stands in for a street's place (the globe's coast, seen from 500 m);
    /// and nothing is drawn where no tile in view has a fine one, held or resident. Render thread.
    void update(const view::View& view, const view::GroundFrame& frame, double distance, std::uint64_t number,
                double seconds, double reach = 1.0, double under_far = 0.0);

    /// The last update's frame, or null where it draws nothing.
    [[nodiscard]] const WorldFrame* frame() const noexcept { return drawing_ ? &frame_ : nullptr; }
    /// What it holds (render thread).
    [[nodiscard]] WorldStats stats() const;
    /// The tiles the last update wanted whose chunks are not on the phone, in its order (nearest first), each with its
    /// pack's range: a remote pack's chunk the cache lacks. Each is wanted again once the cache has changed (any
    /// thread).
    [[nodiscard]] std::vector<WorldWant> wants() const;
    /// The blocks the last update's cover reaches whose world pack is not open (ENGINE.md step 6: the app fetches the
    /// pack's head where its index has one), nearest first: a coarse block (L3) for its L6 to L8, a fine block (L6) for
    /// its L9 to L11 (any thread).
    [[nodiscard]] std::vector<tiles::TileKey> blocks() const;
    /// Waits until the worker has built every tile wanted so far (tests).
    void wait_idle();
    /// Ends the heap's budget at its buffer as it is, as a doubling the device refuses does (tests: Heaps::end_budget).
    void end_budget();
    /// Cuts the heap's free bytes into pieces of `piece` bytes, 16 bytes apart, for good (tests: a heap no piece of
    /// which holds a tile whole; Heaps::fragment_for_tests).
    void fragment_for_tests(vk::DeviceSize piece);

private:
    /// The worker's decoder, its arena and buffers.
    struct Scratch {
        tiles::Zstd zstd;
        tiles::TileDecoder decoder;
        std::vector<std::byte> raw;
        Arena arena{std::size_t{4} << 20U, Growth::grows, "a world tile"};
    };

    WorldTiles(const tiles::Store& store, std::unique_ptr<Heaps> heaps) noexcept;
    void run(const std::stop_token& stop);
    /// Builds `key` from the store into the heap; null where it cannot (no world tile, no room, a damaged chunk, or a
    /// chunk not on the phone, which `absent` then names). `bytes`: the heap bytes its geometry asked for.
    [[nodiscard]] std::unique_ptr<WorldTile> build(const tiles::TileKey& key, Scratch& scratch, bool& no_room,
                                                   std::uint32_t& bytes, std::optional<WorldWant>& absent);
    /// Places `g` in the heap as `t`'s parts: whole where a piece holds it, else in parts, each in the largest piece
    /// free then, as much of the tile's lists as it holds (its triangles, then its segments, then its lights, each
    /// whole), up to parts_most of part_least bytes or more. False, its parts given back, where they do not hold it.
    [[nodiscard]] bool place(const WorldGeometry& g, WorldTile& t);
    /// Gives a resident tile's parts back: once the last frame that drew it has finished.
    void let_go(const WorldTile& t);
    /// Lets go of resident tiles not drawn by frame `number`, least lately drawn first, until `need` bytes are free.
    void make_room(vk::DeviceSize need, std::uint64_t number);
    /// Room for `bytes` of a wanted tile whose build found none, whole or in parts (place): the ranges let go of
    /// already, once their frames have finished (Heaps::pieces_with), else those of the tiles frame `number` does not
    /// draw, least lately drawn first as make_room lets them go, let go of until their pieces hold it. Whether the
    /// room comes.
    enum class Piece : std::uint8_t {
        now,    ///< the pieces hold it now
        coming, ///< the ranges let go of already make room once their frames have finished
        made,   ///< tiles were let go of for it, which comes once their frames have finished
        none,   ///< the going of every tile the frame does not draw would not make it: none was let go of
    };
    [[nodiscard]] Piece make_piece(std::uint32_t bytes, std::uint64_t number);
    /// Where the going of no tile frame `number` leaves out makes room for wanted tile `key`: whether its stand-in
    /// (`stood_by`, by the wanted tiles' keys) is let go of for the tiles it stands in for. It is where the frame draws
    /// it for them alone (none of `kept`: no place of its own, none under a tile fading in, no floor), each of them
    /// found no room (`roomless`, their bytes), and the room its going makes, with that of the tiles the frame does
    /// not draw, holds them all. Its draws in this frame stand: its ranges are retired as a drawn tile's are and the
    /// tile is kept until the next update (leaving_). The frames after draw their places by its nearest resident
    /// ancestor until they are built and fade in.
    [[nodiscard]] bool trade(std::uint64_t key, const std::unordered_map<std::uint64_t, std::uint64_t>& stood_by,
                             const std::unordered_set<std::uint64_t>& kept,
                             const std::unordered_map<std::uint64_t, std::uint32_t>& roomless, std::uint64_t number);
    /// The world's style: the first world pack's. Returns whether the store's world packs changed.
    bool take_style();
    /// Lets go of the resident tiles whose packs the store no longer holds (render thread).
    void let_go_of_gone();

    const tiles::Store& store_;
    std::unique_ptr<Heaps> heaps_;
    std::unordered_map<std::uint64_t, std::unique_ptr<WorldTile>> resident_; // by packed key
    /// The stand-ins traded by the last update (trade): its frame drew them, so they are kept until the next.
    std::vector<std::unique_ptr<WorldTile>> leaving_;
    std::array<std::unique_ptr<WorldTile>, 2> caps_; // the north's and the south's, built with the heap
    std::unique_ptr<WorldTile> blank_;               // the plain land, built with the heap
    WorldFrame frame_;
    std::uint64_t number_ = 0;          ///< the last update's frame
    vk::DeviceSize stand_in_bytes_ = 0; ///< the last frame's stand-ins' bytes (WorldStats::stand_in_bytes)
    vk::DeviceSize wanted_bytes_ = 0;   ///< the bytes its wanted tiles' builds asked for (WorldStats)
    std::size_t wanted_unsized_ = 0;    ///< its wanted tiles of no build yet (WorldStats)
    bool drawing_ = false;
    std::shared_ptr<const tiles::StorePack> style_pack_;
    std::vector<std::shared_ptr<const tiles::StorePack>> seen_packs_;
    std::vector<const tiles::StorePack*> seen_world_; ///< the world packs among seen_packs_
    std::unordered_set<std::uint64_t> open_blocks_;   ///< their blocks, packed (their ids, tiles::world_pack_id)
    bool pinned_asked_ = false;

    mutable std::mutex mutex_; // queue_, building_, built_, roomless_, idle_, the stats, style_pack_'s writes
    std::condition_variable wake_worker_;
    std::condition_variable idle_cv_;
    std::vector<tiles::TileKey> queue_;
    std::uint64_t building_ = ~std::uint64_t{0};
    std::vector<std::unique_ptr<WorldTile>> built_;
    std::unordered_map<std::uint64_t, std::uint32_t> roomless_; ///< the bytes a build that found no room asked for
    /// The wanted tiles waiting for the bytes let go of for them: the heap's frees() then (render thread). Each is
    /// built again once a piece holds it, which the heap's frees having changed may bring.
    std::unordered_map<std::uint64_t, std::uint64_t> room_waits_;
    std::vector<std::uint64_t> failed_keys_;
    /// A tile built without its chunk on the phone, and the cache's writes as its build began.
    struct Absent {
        WorldWant want;
        std::uint64_t writes = 0;
    };
    std::unordered_map<std::uint64_t, Absent> absent_; ///< by packed key
    std::vector<WorldWant> wants_;
    std::vector<tiles::TileKey> blocks_;
    std::function<void()> wake_;
    bool drawing_asked_ = false; // the last update drew the world: a tile built asks for a frame
    WorldStats stats_;
    std::jthread worker_;
};

} // namespace atlas::scene
