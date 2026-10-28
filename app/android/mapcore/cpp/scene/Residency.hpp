// Residency (plan-vector D3 "Streaming", D5.2, D5.7, T8): the tiles whose geometry lives in the GPU heaps. The cover
// (scene/Cover.hpp) asks each frame for the tiles it wants, in three lanes: the visible tiles by their share of the
// screen, nearest first; their ancestors (an ancestor is resident before its children are dropped); then prefetch. The
// workers (2-3, asleep when there is nothing to do) take requests lane by lane, a request whose tile has left the cover
// is cancelled if it has not started, and
//   1. a worker decodes the tile from the store (tiles::Store: mapped packs, the expanded-tile cache, ranges) into an
//      arena of its own while it is under way,
//   2. every pass's stage-T builder runs over it (frame::Pass::build) into the geometry heaps (scene/Heaps.hpp), each
//      writing its ranges in place and the items it adds (scene/ItemTable.hpp's ItemSpec) apart: the passes of a tile
//      are shared by the workers (ENGINE.md gain 9), the longest first, and a pass may share its own work in pieces
//      (TileBuild::share: the buildings by blocks of records); an idle worker starts the next tile wanted, and once
//      none waits in its lane takes a piece or a pass of a tile under way: no dense tile is left to one worker,
//   3. the worker that finishes its last pass adds the items in the passes' order, as one thread building them in turn
//      would, and publishes it, ready, with one step the render thread sees between frames (take_published).
// The render thread references only published tiles; a tile it lets go of (release) has its heap ranges retired, freed
// two frames later after the fences. The render thread never waits on I/O, decoding or the sun.
//
// The workers wait at background priority; while a tile the frame wants in view is queued or under way they run at
// normal priority, with a hint session of their own told how long each piece of that work takes (ENGINE.md gain 8:
// WorkerHints, the platform's). Without hints, they stay at background priority (nice 10 on Android).
//
// The sun's hook (K8, plan-vector D8): the sun worker's scene is the resident tiles and their 220 m halo
// (sun_tiles: sun::halo_of over the resident tiles of a pack), as K8's Worker asks for it.
#pragma once

#include "base/arena.hpp"
#include "frame/Pass.hpp"
#include "scene/Heaps.hpp"
#include "scene/ItemTable.hpp"
#include "scene/WorkerHints.hpp"
#include "tiles/Store.hpp"
#include "tiles/Style.hpp"
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
    /// The pack it came from (the store's id), what kind of pack that is (a frame cell's windows among them: the
    /// ground's order across scenes) and its ground frame.
    std::string pack;
    tiles::PackKind kind = tiles::PackKind::render;
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

/// The workers a pass may share its own work with (TileBuild::share): the residency's, for the pass's tile.
class Sharer {
public:
    Sharer() = default;
    Sharer(const Sharer&) = delete;
    Sharer& operator=(const Sharer&) = delete;
    Sharer(Sharer&&) = delete;
    Sharer& operator=(Sharer&&) = delete;
    virtual ~Sharer() = default;

    /// How many workers wait for work now.
    [[nodiscard]] virtual unsigned idle() const = 0;
    /// Runs piece(0) to piece(pieces - 1) on this worker and the idle ones; returns once every one is done.
    virtual void share(std::size_t pieces, const std::function<void(std::size_t)>& piece) = 0;
};

/// What a pass's builder sees of a tile and writes for it (frame::Pass::build), on a worker: the passes of one tile
/// build at once, each into its own record and items.
class TileBuild {
public:
    TileBuild(const tiles::Tile& tile, const tiles::RenderHead& head, const tiles::Style& style, std::uint8_t level,
              view::Vec3 origin, Heaps& heaps, PassTile& out, std::vector<ItemSpec>& items, std::uint32_t pass,
              Sharer* sharer = nullptr) noexcept
        : tile_(tile), head_(head), style_(style), level_(level), origin_(origin), heaps_(heaps), out_(out),
          items_(items), pass_(pass), sharer_(sharer) {}

    [[nodiscard]] const tiles::Tile& tile() const noexcept { return tile_; }
    [[nodiscard]] const tiles::RenderHead& head() const noexcept { return head_; }
    /// The pack's style.
    [[nodiscard]] const tiles::Style& style() const noexcept { return style_; }
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
    /// How many of the residency's workers wait for work now, to take pieces of the pass's work (none outside the
    /// residency, or where its workers share nothing): worth splitting it in as many pieces and one more, at most.
    [[nodiscard]] unsigned helpers() const { return sharer_ != nullptr ? sharer_->idle() : 0; }
    /// Runs `pieces` pieces of the pass's work, piece(0) to piece(pieces - 1), on this worker and the residency's idle
    /// ones (ENGINE.md gain 9: a dense tile's buildings in blocks), and returns once every one is done; a piece writes
    /// only what it alone writes. Outside the residency (a builder's test), one after another here.
    void share(std::size_t pieces, const std::function<void(std::size_t)>& piece) const {
        if (sharer_ != nullptr && pieces > 1) {
            sharer_->share(pieces, piece);
            return;
        }
        for (std::size_t i = 0; i < pieces; ++i) piece(i);
    }

private:
    const tiles::Tile& tile_;
    const tiles::RenderHead& head_;
    const tiles::Style& style_;
    std::uint8_t level_;
    view::Vec3 origin_;
    Heaps& heaps_;
    PassTile& out_;
    std::vector<ItemSpec>& items_;
    std::uint32_t pass_;
    Sharer* sharer_;
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
    double build_ms = 0;     ///< the workers' time over every tile built (its decoding, its passes and its publication)
    std::uint64_t shared = 0; ///< passes built on a worker other than the one that decoded their tile (gain 9)
    std::uint64_t pieces = 0; ///< pieces of a pass's work (TileBuild::share) run on another worker than the pass's
    std::size_t resident = 0;
    std::size_t native_bytes = 0; ///< the resident tiles' native bytes (ResidentTile::native_bytes)
    std::size_t pending = 0;
};

/// How the workers build the tiles.
struct WorkerOptions {
    /// How many (T8: 2-3 by core count).
    unsigned count = 2;
    /// Whether an idle worker takes a pass of a tile under way and a piece of a pass's work (ENGINE.md gain 9); else
    /// each tile is built by the worker that took it, pass after pass, as before the gain (its measure).
    bool share = true;
    /// What they tell the platform (ENGINE.md gain 8), or none.
    std::shared_ptr<WorkerHints> hints;
};

/// The resident tiles.
class Residency {
public:
    /// Tiles from `store`, built by `passes`' builders into `heaps` by `workers`; `clock` gives the frame's time in
    /// seconds (the tiles' fades), from the workers' threads.
    Residency(const tiles::Store& store, Heaps& heaps, std::span<const std::unique_ptr<frame::Pass>> passes,
              WorkerOptions workers, std::function<double()> clock);
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
    /// Stops the workers for good (the world's end: every thread stops before what they share goes).
    void stop();

private:
    struct Job {
        Request request;
        std::string pack;
        std::uint64_t generation = 0;
    };
    /// What a tile's decoding needs while the tile is under way: the decoded tile views both.
    struct Scratch {
        Arena arena;
        std::vector<std::byte> raw;
        Scratch();
    };
    /// A tile under way (gain 9): taken from the queue, decoded by the worker that took it, its passes handed out to
    /// the workers longest first, each writing its own record and items; the worker that finishes its last pass
    /// publishes it.
    struct Underway {
        Job job;
        std::uint64_t key = 0;
        std::uint64_t frees = 0; // the heaps' frees as it began (failed_)
        std::shared_ptr<const tiles::StorePack> pack;
        std::unique_ptr<Scratch> scratch; // the decoded tile's bytes
        tiles::Tile tile;
        std::shared_ptr<ResidentTile> out;        // its passes' records written in place, one each
        std::vector<std::vector<ItemSpec>> items; // per pass
        std::vector<std::uint32_t> order;         // the passes in the order they are handed out (empty while decoding)
        unsigned worker = 0;                      // the worker that took it
        // Under mutex_:
        std::size_t handed = 0; // of order
        std::size_t done = 0;
        bool failed = false;
        double ms = 0; // the workers' time on it
    };
    /// A pass's work shared out in pieces (TileBuild::share), while its worker runs them and waits for the rest.
    struct Split {
        const std::function<void(std::size_t)>* piece = nullptr;
        std::size_t count = 0;
        Lane lane = Lane::visible;
        // Under mutex_:
        std::size_t handed = 0;
        std::size_t done = 0;
    };
    /// A worker's next piece of work: a piece of a pass's shared work, a tile to start, or a pass of a tile under way
    /// (none: the worker stops).
    struct Piece {
        Split* split = nullptr;
        std::size_t index = 0;
        Underway* tile = nullptr;
        std::int32_t pass = -1; ///< -1: the tile to start
    };

    void work(const std::stop_token& stop, unsigned worker);
    /// The next piece for worker `worker`, taken under `lock` (none once stopped): of the best lane, a pass of a tile
    /// it took, else a piece of a pass's shared work, else the queue's next tile, else a pass of another worker's tile
    /// under way; else it waits.
    [[nodiscard]] Piece take(std::unique_lock<std::mutex>& lock, const std::stop_token& stop, unsigned worker);
    /// Decodes a tile just taken into a scratch of its own; false when it cannot be built.
    [[nodiscard]] bool start(Underway& u, tiles::Zstd& zstd, tiles::TileDecoder& decoder);
    /// Runs pass `p` of a tile under way; false when it fails.
    [[nodiscard]] bool run(Underway& u, std::uint32_t p);
    /// A pass's work in `count` pieces, on the pass's worker and the idle ones (TileBuild::share).
    void share(const Underway& u, std::size_t count, const std::function<void(std::size_t)>& piece);
    /// How many workers wait for work now.
    [[nodiscard]] unsigned idle() const;
    class Helpers; // a pass's Sharer: its tile's lane
    /// A tile whose last piece is done: its items joined and published, or what its passes made let go of when one
    /// failed, then forgotten as failed.
    void publish(Underway& u);
    /// Whether a tile the frame wants in view is queued or under way (under mutex_).
    [[nodiscard]] bool urgent_locked() const noexcept;
    /// Tells the hints whether the frame waits on the workers, when that changed (outside mutex_).
    void hint();
    [[nodiscard]] static std::uint64_t key_of(tiles::TileKey key, std::string_view pack) noexcept;

    const tiles::Store& store_;
    Heaps& heaps_;
    std::span<const std::unique_ptr<frame::Pass>> passes_;
    std::vector<std::string> stage_t_names_; // each pass's stage T as its trace section names it (gain 21)
    std::function<double()> clock_;
    std::shared_ptr<WorkerHints> hints_;
    unsigned workers_count_ = 0;
    bool share_ = true;

    mutable std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    std::vector<Job> queue_;                          // sorted: lane, then priority
    std::unordered_map<std::uint64_t, int> underway_; // keys requested, building or published but not taken
    // Keys whose build failed, with the heaps' frees before it: a tile that found no room is not built again, frame
    // after frame, until a range has gone back to the heaps.
    std::unordered_map<std::uint64_t, std::uint64_t> failed_;
    std::vector<std::unique_ptr<Underway>> building_; // the tiles under way, in the order they were taken
    std::vector<Split*> splits_;                      // the passes sharing their work, each its worker's
    std::condition_variable split_done_;              // a split's last piece done
    std::vector<std::unique_ptr<Scratch>> scratch_;   // decoding's scratch, kept for the next tiles
    std::vector<double> pass_ms_;                     // each pass's time over its runs (the order handed out)
    std::vector<std::uint64_t> pass_runs_;
    std::vector<std::shared_ptr<ResidentTile>> published_;
    std::uint64_t generation_ = 0;
    unsigned busy_ = 0;
    std::mutex hint_mutex_; // the hints' changes one at a time; taken before mutex_, never after
    bool urgent_ = false;   // urgent_locked as last told the hints (under hint_mutex_)
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
