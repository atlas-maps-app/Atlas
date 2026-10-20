// Residency (plan-vector D3 "Streaming", D5.2, D5.7, T8): the tiles whose geometry lives in the GPU heaps. The cover
// (scene/Cover.hpp) asks each frame for the tiles it wants, in three lanes: the visible tiles by their share of the
// screen, nearest first; their ancestors (an ancestor is resident before its children are dropped); then prefetch. The
// workers (2-3, background priority, asleep when there is nothing to do) take requests lane by lane,
// a request whose tile has left the cover is cancelled if it has not started, and a worker
//   1. decodes the tile from the store (tiles::Store: mapped packs, the expanded-tile cache, ranges) into its arena,
//   2. runs every pass's stage-T builder over it (frame::Pass::build) into the geometry heaps (scene/Heaps.hpp), each
//      writing its ranges in place, and the items they add (scene/ItemTable.hpp's ItemSpec),
//   3. publishes it, ready, with one step the render thread sees between frames (take_published).
// The render thread references only published tiles; a tile it lets go of (release) has its heap ranges retired, freed
// two frames later after the fences. The render thread never waits on I/O, decoding or the sun.
//
// The sun's hook (K8, plan-vector D8): the sun worker's scene is the resident tiles and their 220 m halo
// (sun_tiles: sun::halo_of over the resident tiles of a pack), as K8's Worker asks for it.
#pragma once

#include "frame/Pass.hpp"
#include "scene/Heaps.hpp"
#include "scene/ItemTable.hpp"
#include "tiles/Store.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::scene {

/// What one pass's stage T made of one tile, kept with the tile while it is resident.
struct PassTile {
    /// Its heap ranges (vertices, indices, instances, ...: the pass's own meaning), at most four.
    std::array<HeapRange, 4> ranges{};
    /// Its draws' elements per cell (the pass's unit: vertices, indices or instances), as offsets: cell c holds
    /// [cell_first[c], cell_first[c + 1]); empty when the pass does not draw by cell.
    std::vector<std::uint32_t> cell_first;
    /// Its own numbers (ground slots, counts), the pass's to define.
    std::array<std::uint32_t, 8> values{};
    /// The pass's own CPU-side data for the tile (the ground's rings in double, which it decides per frame as the port
    /// does), of the pass's own type (cpu_as). Made in stage T on a worker (TileBuild::keep); const once the tile is
    /// published, from then on read on the render thread (or any other) and never written; freed with the tile, when
    /// the residency lets it go (a pass that keeps it past that frame holds its own reference).
    std::shared_ptr<const void> cpu;
    /// The bytes `cpu` holds, as its builder counts them: part of the tile's native bytes, which the residency's budget
    /// sees (ResidentTile::native_bytes).
    std::size_t cpu_bytes = 0;

    /// `cpu` as the pass's type T (null when the pass kept none).
    template <class T>
    [[nodiscard]] const T* cpu_as() const noexcept {
        return static_cast<const T*>(cpu.get());
    }
};

/// A tile resident in the heaps, as the render thread sees it once published.
struct ResidentTile {
    tiles::TileKey key;
    /// Its number while resident: the item table's tile column, the cover's reference.
    std::uint32_t handle = 0;
    /// The pack it came from (the store's id) and its ground frame.
    std::string pack;
    view::GroundFrame frame;
    /// Its south-west corner at street level, in its pack frame's metres.
    view::Vec3 origin;
    /// The ladder index it was built at (its tolerance).
    std::uint8_t level = 0;
    /// Its cells: per side, and each one's box on the grid and top (tiles::CellInfo).
    std::uint8_t cells_per_side = 0;
    std::vector<tiles::CellInfo> cells;
    tiles::Reach reach;
    /// Per pass of the registry: what its builder made.
    std::vector<PassTile> passes;
    /// The items its builders added (the residency adds them to the item table as it takes the tile).
    std::vector<ItemSpec> items;
    /// Their rows in the item table, once added.
    std::vector<std::uint32_t> rows;
    /// When it was published (seconds, the frame's clock): its fade in.
    double published = 0;
    /// What it holds in native memory: itself, its cells, its items and their rows, each pass's offsets and CPU-side
    /// data (PassTile::cpu_bytes); the residency's budget counts it (ResidencyStats::native_bytes).
    std::size_t native_bytes = 0;
};

/// What a pass's builder sees of a tile and writes for it (frame::Pass::build), on a worker.
class TileBuild {
public:
    TileBuild(const tiles::Tile& tile, const tiles::RenderHead& head, const tiles::StyleTables* style,
              std::uint8_t level, view::Vec3 origin, Heaps& heaps, PassTile& out, std::vector<ItemSpec>& items,
              std::uint32_t pass) noexcept
        : tile_(tile), head_(head), style_(style), level_(level), origin_(origin), heaps_(heaps), out_(out),
          items_(items), pass_(pass) {}

    [[nodiscard]] const tiles::Tile& tile() const noexcept { return tile_; }
    [[nodiscard]] const tiles::RenderHead& head() const noexcept { return head_; }
    [[nodiscard]] const tiles::StyleTables* style() const noexcept { return style_; }
    /// The ladder index the tile is built at (the rings' simplification and the variants').
    [[nodiscard]] std::uint8_t level() const noexcept { return level_; }
    /// The tile's south-west corner at street level in its pack frame's metres: its positions are float metres from it.
    [[nodiscard]] const view::Vec3& origin() const noexcept { return origin_; }
    /// The pass's number in the registry (its PassTile in ResidentTile::passes).
    [[nodiscard]] std::uint32_t pass() const noexcept { return pass_; }

    /// A range of `bytes` in heap `kind` (allocate every range first, then write: Heaps' writer holds a lock).
    [[nodiscard]] gpu::Result<HeapRange> allocate(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment = 16) {
        return heaps_.allocate(kind, bytes, alignment);
    }
    [[nodiscard]] Heaps& heaps() noexcept { return heaps_; }
    /// The pass's record of the tile.
    [[nodiscard]] PassTile& out() noexcept { return out_; }
    /// Adds an item the pass draws (the residency sets its tile).
    void add_item(const ItemSpec& item) { items_.push_back(item); }
    /// Keeps `data` as the pass's CPU-side data for the tile (PassTile::cpu), `bytes` of the tile's native bytes: const
    /// from here on, read once the tile is published, freed with the tile.
    template <class T>
    void keep(std::shared_ptr<const T> data, std::size_t bytes) {
        out_.cpu = std::move(data);
        out_.cpu_bytes = bytes;
    }

private:
    const tiles::Tile& tile_;
    const tiles::RenderHead& head_;
    const tiles::StyleTables* style_;
    std::uint8_t level_;
    view::Vec3 origin_;
    Heaps& heaps_;
    PassTile& out_;
    std::vector<ItemSpec>& items_;
    std::uint32_t pass_;
};

/// A request's lane, in the order the workers take them.
enum class Lane : std::uint8_t { visible = 0, ancestor = 1, prefetch = 2 };

/// One tile the cover wants: its key, the pack it comes from, its lane and its priority in the lane (higher first).
struct Request {
    tiles::TileKey key;
    std::string_view pack; ///< the store's id
    Lane lane = Lane::visible;
    double priority = 0;
    std::uint8_t level = 0; ///< the ladder index to build it at
};

/// Counters for logs and tests.
struct ResidencyStats {
    std::uint64_t requested = 0;
    std::uint64_t built = 0;
    std::uint64_t cancelled = 0;
    std::uint64_t failed = 0;
    std::uint64_t released = 0;
    std::size_t waiting = 0; ///< tiles whose build failed, not asked again until the heaps have freed a range
    double build_ms = 0;     ///< the workers' time over every tile built
    std::size_t resident = 0;
    std::size_t native_bytes = 0; ///< the resident tiles' native bytes (ResidentTile::native_bytes)
    std::size_t pending = 0;
};

/// The resident tiles.
class Residency {
public:
    /// Tiles from `store`, built by `passes`' builders into `heaps` on `workers` threads; `clock` gives the frame's
    /// time in seconds (the tiles' fades), from the workers' threads.
    Residency(const tiles::Store& store, Heaps& heaps, std::span<const std::unique_ptr<frame::Pass>> passes,
              unsigned workers, std::function<double()> clock);
    ~Residency();
    Residency(const Residency&) = delete;
    Residency& operator=(const Residency&) = delete;
    Residency(Residency&&) = delete;
    Residency& operator=(Residency&&) = delete;

    /// The frame's requests (render thread): what is wanted now replaces what was; a request not begun whose tile is
    /// no longer wanted is cancelled. Tiles already resident or under way are not asked again.
    void want(std::span<const Request> requests);

    /// The tiles published since the last call, their items added to `items` (render thread, between frames).
    void take_published(ItemTable& items, std::vector<std::shared_ptr<const ResidentTile>>* taken = nullptr);

    /// Lets go of a resident tile: its rows leave `items`, its heap ranges are retired after frame `frame` (render
    /// thread).
    void release(tiles::TileKey key, std::string_view pack, ItemTable& items, std::uint64_t frame);

    /// The resident tile of `key` in pack `pack` (taken), or null.
    [[nodiscard]] const ResidentTile* find(tiles::TileKey key, std::string_view pack) const noexcept;
    /// Every resident tile (taken).
    [[nodiscard]] std::span<const std::shared_ptr<const ResidentTile>> resident() const noexcept { return resident_; }

    /// The sun's scene for pack `pack` (K8): the keys of the resident tiles and their 220 m halo (sun::halo_of), in the
    /// pack's order; none for a pack the store lacks.
    [[nodiscard]] std::vector<tiles::TileKey> sun_tiles(std::string_view pack) const;

    /// Called on a worker thread as each tile is published, outside the residency's lock (null: nothing): cheap and
    /// never blocking, such as the frame loop's wake (World::set_wake).
    void set_published(std::function<void()> callback);

    /// Waits until no request is queued or under way (tests).
    void wait_idle();
    [[nodiscard]] ResidencyStats stats() const;

private:
    struct Job {
        Request request;
        std::string pack;
        std::uint64_t generation = 0;
    };

    void work(const std::stop_token& stop);
    [[nodiscard]] std::shared_ptr<ResidentTile> build(const Job& job, tiles::Zstd& zstd, tiles::TileDecoder& decoder,
                                                      Arena& arena, std::vector<std::byte>& raw);
    [[nodiscard]] static std::uint64_t key_of(tiles::TileKey key, std::string_view pack) noexcept;

    const tiles::Store& store_;
    Heaps& heaps_;
    std::span<const std::unique_ptr<frame::Pass>> passes_;
    std::function<double()> clock_;

    mutable std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    std::vector<Job> queue_;                          // sorted: lane, then priority
    std::unordered_map<std::uint64_t, int> underway_; // keys requested, building or published but not taken
    // Keys whose build failed, with the heaps' frees before it: a tile that found no room is not built again, frame
    // after frame, until a range has gone back to the heaps.
    std::unordered_map<std::uint64_t, std::uint64_t> failed_;
    std::vector<std::shared_ptr<ResidentTile>> published_;
    std::uint64_t generation_ = 0;
    unsigned busy_ = 0;
    std::function<void()> published_callback_;
    ResidencyStats stats_;
    std::atomic<std::uint32_t> next_handle_{1};
    std::atomic<std::size_t> native_{0}; // the resident tiles' native bytes (the render thread changes it)

    // The render thread's.
    std::vector<std::shared_ptr<const ResidentTile>> resident_;
    std::unordered_map<std::uint64_t, std::size_t> index_; // key → resident_

    std::vector<std::jthread> workers_; // last: they start once every member above is made, and stop first
};

} // namespace atlas::scene
