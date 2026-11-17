// The terrain's relief tiles (ENGINE.md part 6, terrain, step 2: relief light on the plane): the store's terrain packs'
// height rasters (tiles/Heights.hpp; `terrain_country`, L12 to L14) baked to what the relief pass lights the ground
// with (fx/Relief.hpp: a texel a sample, the surface's normal and its sky-view), for the ground a frame sees.
//
// The cover is the relief's own, not the map's: the map's cover draws a tile's cells where they hold features, over a
// plate that lies everywhere, and the relief lights the ground wherever heights are. Each frame a walk of the pyramid
// from L0 takes every tile the view's frustum sees out to the cover's far plane, down to L12, to L13 within the coarse
// reach of the eye and to L14 within the fine reach (the map's levels' reaches: a sample is about a pixel at its
// level's reach), but no finer than the terrain packs hold rasters there. Each tile it ends at is lit by the raster
// the store gives for it (its own level's, else its nearest ancestor's: a source capped at a level, or one not built),
// its part of that raster's texels; where the store holds none (the open sea, a country without terrain yet) the
// ground stays flat. A raster not baked yet is wanted, and meanwhile the nearest baked raster of a coarser level over
// the tile lights it. No frame waits for a tile: one worker of the relief's own decodes and bakes each wanted raster,
// nearest first.
//
// A terrain pack is fetched by range (a remote pack: its head in the world's cache, its chunks as they are wanted):
// a wanted raster whose chunk is not on the phone is wanted of the app (wants), which fetches and keeps it
// (tiles::Store::keep), and it is baked once the cache has changed. The L9 tiles the walk's tiles lie in are the blocks
// the app fetches the heads of (blocks: a terrain pack an L9 tile), whether or not a pack is open for them.
//
// The baked rasters number at most max_tiles (264 KB of texels each at 257²), the least lately drawn going first; where
// a view asks for more, its farthest tiles stay flat.
//
// Thread-safe as the world uses it: update and frame on the render thread; the worker its own; wants, blocks and
// sources from any thread.
#pragma once

#include "base/arena.hpp"
#include "fx/Relief.hpp"
#include "scene/Cells.hpp"
#include "scene/WorldTiles.hpp"
#include "tiles/Store.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <array>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace atlas::scene {

/// The relief's levels: the terrain packs' (tiles::Store's terrain_country_first and _last).
inline constexpr std::uint8_t relief_coarsest = 12;
inline constexpr std::uint8_t relief_finest = 14;
/// The level of a terrain pack's block: a pack an L9 tile.
inline constexpr std::uint8_t relief_block = 9;

/// A raster, baked.
struct ReliefTile {
    tiles::TileKey key;       ///< the raster's own tile
    std::uint64_t serial = 0; ///< its number among every raster baked, never given twice
    fx::ReliefBake bake;
    std::vector<std::string> sources; ///< the registry ids of its samples' sources (packs/terrain-sources.json)
    /// The pack it was read from: once the store no longer holds it, the tile is let go.
    std::shared_ptr<const tiles::StorePack> pack;
    std::uint64_t drawn = 0; ///< the last frame that drew it
};

/// A part of a raster: where the part's north-west corner lies in it and how far it reaches east and south, each as a
/// share of the raster's side (0 to 1).
struct ReliefPart {
    const ReliefTile* tile = nullptr;
    float x = 0;
    float y = 0;
    float extent = 1;
};

/// A tile a frame lights: its box on the ground and the raster's part over it.
struct ReliefDraw {
    tiles::TileKey key;
    /// Its north-west corner at street level in the frame's metres, and how far it reaches east and south.
    view::Vec3 corner;
    double east = 0;
    double south = 0;
    double distance = 0; ///< the eye's to its nearest point
    /// The raster that lights it, baked: its own (the store's for it), else the nearest coarser one baked.
    ReliefPart part;
    /// Where `part` is its own: the nearest coarser raster baked, or none. The relief pass draws it while the own
    /// one's texels are on their way to the GPU.
    ReliefPart under;
    /// Whether `part` is its own raster's.
    bool own = false;
};

/// What a frame lights.
struct ReliefFrame {
    std::vector<ReliefDraw> draws; ///< nearest first
    view::GroundFrame frame;
    /// The walk's far plane, metres of depth (the cover's): the light ends before it.
    double far = 0;
    /// The rasters the frame wants that are not baked yet (those wanted of the app among them).
    std::size_t wanted = 0;
    /// The tiles of the walk left flat for the rasters they would take past max_tiles.
    std::size_t left = 0;
};

/// What the relief holds.
struct ReliefStats {
    std::size_t resident = 0;
    std::uint64_t baked = 0;
    std::uint64_t failed = 0;
    double bake_ms = 0; ///< the worker's time for the rasters baked, all told (decode and bake)
};

class ReliefTiles {
public:
    /// The most rasters baked at a time, and so the most a frame draws.
    static constexpr std::size_t max_tiles = 64;

    /// How a frame's walk goes: the frustum's margin and far plane (the cover's), and the levels' reaches from the eye.
    struct Reaches {
        double far = 12'000.0;
        double fine = 4'700.0;
        double coarse = 12'000.0;
        double margin_px = 40.0;
    };

    explicit ReliefTiles(const tiles::Store& store);
    ReliefTiles(const ReliefTiles&) = delete;
    ReliefTiles& operator=(const ReliefTiles&) = delete;
    ReliefTiles(ReliefTiles&&) = delete;
    ReliefTiles& operator=(ReliefTiles&&) = delete;
    ~ReliefTiles();

    /// What the worker calls once it has baked a raster or found its chunk absent (the frame loop's frame request).
    void set_wake(std::function<void()> wake);

    /// The frame's relief for `view` in ground frame `frame`: frame number `number`.
    void update(const view::View& view, const view::GroundFrame& frame, const Reaches& reaches, std::uint64_t number);
    /// A frame without relief (the terrain switched off, or the camera beyond the relief's reach): nothing drawn or
    /// wanted, the baked rasters kept.
    void rest();

    [[nodiscard]] const ReliefFrame& frame() const noexcept { return frame_; }

    /// The wanted rasters whose chunks are not on the phone, nearest first.
    [[nodiscard]] std::vector<WorldWant> wants() const;
    /// The L9 tiles the last frame's tiles lie in, nearest first.
    [[nodiscard]] std::vector<tiles::TileKey> blocks() const;
    /// The registry ids of the sources of the rasters the last frame drew, sorted, comma-separated.
    [[nodiscard]] std::string sources() const;
    [[nodiscard]] ReliefStats stats() const;

    /// Waits until the worker has nothing to do (a test's).
    void wait_idle();

private:
    /// What the store gives for a tile: its raster's chunk and pack (none: no heights there).
    struct Hit {
        std::shared_ptr<const tiles::StorePack> pack;
        const tiles::ChunkRef* chunk = nullptr;
    };
    /// A wanted raster whose chunk was not on the phone, and the cache's writes when it was found so.
    struct Absent {
        WorldWant want;
        std::uint64_t writes = 0;
    };
    struct Scratch {
        tiles::Zstd zstd;
        tiles::TileDecoder decoder;
        std::vector<std::byte> raw;
        Arena arena{std::size_t{1} << 20U, Growth::grows, "a relief tile"};
    };

    void run(const std::stop_token& stop);
    /// Bakes raster `key`; null where it cannot (no such raster, a damaged chunk, or a chunk not on the phone, which
    /// `absent` then names).
    [[nodiscard]] std::unique_ptr<ReliefTile> build(const tiles::TileKey& key, Scratch& scratch,
                                                    std::optional<WorldWant>& absent) const;
    [[nodiscard]] const Hit& hit_of(const tiles::TileKey& key);
    /// Takes the store's terrain packs: true where they changed (what was read from a pack gone is let go).
    bool take_packs();
    [[nodiscard]] const ReliefTile* resident(const tiles::TileKey& key) const noexcept;

    const tiles::Store& store_;
    ReliefFrame frame_;
    std::vector<std::shared_ptr<const tiles::StorePack>> packs_; ///< the terrain packs seen
    std::unordered_map<std::uint64_t, Hit> hits_;                ///< the store's answers while the packs stay
    std::unordered_map<std::uint64_t, std::unique_ptr<ReliefTile>> resident_;
    std::uint64_t serial_ = 0;

    mutable std::mutex mutex_;
    std::condition_variable wake_worker_;
    std::condition_variable idle_cv_;
    std::vector<tiles::TileKey> queue_;                ///< the rasters to bake, nearest first
    std::uint64_t building_ = ~std::uint64_t{0};       ///< the one the worker bakes now
    std::vector<std::unique_ptr<ReliefTile>> built_;   ///< baked, for the next update to take
    std::unordered_map<std::uint64_t, Absent> absent_; ///< wanted of the app
    std::vector<std::uint64_t> failed_;                ///< not tried again while the packs stay
    std::vector<WorldWant> wants_;
    std::vector<tiles::TileKey> blocks_;
    std::string sources_;
    ReliefStats stats_;
    std::function<void()> wake_;
    std::jthread worker_; // last: made once the rest is, stopped and joined before the rest goes
};

} // namespace atlas::scene
