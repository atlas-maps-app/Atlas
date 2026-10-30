// The sun's bits in set 1 (plan-vector D8, K8's worker; frame/Globals.hpp's first provider that no pass owns): the
// world's sun, a lane for each pack drawn: the active pack, the coarser pack around it (the canal ring around the
// Westerkerk) and the neighbours beside them (frame cells' windows, max_neighbours), each lane with its own scene, bits
// and worker. A pack keeps its lane while it is drawn, whatever its role: the neighbours' order changes as the camera
// moves, and the active pack and a neighbour swap roles as it crosses a frame cell's edge, and only the role moves (the
// active pack's lane is asked first, on the sun's threads; the others on half as many). A lane's scene is its pack's
// resident tiles at their finest zoom and their 220 m halo (Residency::sun_tiles), each made at the ladder level it is
// drawn at (a halo tile at the cover's), built on a thread of its own whenever that set changes; K8's Worker runs the
// moment's bucket (sun::bucket_of the theme block: a named moment's own sun, a live one's 0.5° bucket) over it, visible
// tiles nearest the eye first (in the lane's own pack's frame), skipping a tile whose bits for the bucket are there,
// and publishes the run's bits together once it completes (BitTiles, which cross-fades a swap over 300 ms), waking the
// frame loop once a run.
//
// A building's shadow does not stop at its frame cell's edge: a lane's scene also holds the solids of the other packs
// drawn (the active pack's and the neighbours', not the coarser pack's, which lies under the active one) within the
// march's reach of the lane's resident tiles, made in the lane's own frame (Other: a point of the grid is a place in
// every frame), at places its own pack has no tile; their bridges are among its decks. A tile's bits record what they
// were made against (sun::Scene::context: its chunk and the chunks within the march's reach of it, hashed), so a
// neighbouring cell's tile coming into the scene, or a window joining near it, makes them again; and the disk cache
// keeps them under that context.
//
// Each frame (prepare, from World::update after the cover) every tile the cover draws gets its bits: CPU-side
// (CoverTile::sun, for the twin and the passes' own choices) and on the GPU, where its texels (BitTiles::texels: per 32
// samples the new bits and the old, R32G32_UINT) are written into the frame slot's half of one host-visible buffer,
// and CoverTile::sun_base and sun_weight say where they begin (in texels from the buffer's start) and how far the swap
// has faded. The buffer is set 1's sun_bits; it changes, and set 1 with it, only when it grows (the old one kept until
// the frames that read it have finished). A tile drawn before its first bits (or a stand-in ancestor, which the scene
// does not hold) has sun_base no_sun.
#pragma once

#include "frame/Globals.hpp"
#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "sun/BitTiles.hpp"
#include "sun/Buckets.hpp"
#include "tiles/Store.hpp"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace atlas::scene {

/// The sun's choices.
struct SunOptions {
    bool enabled = true;
    /// The bucket's threads (the worker's own and the run's helpers): D8 runs a bucket on four.
    unsigned threads = 4;
    /// Where named moments' bits are kept on disk (empty: nowhere), and how many bytes at most.
    std::string cache_dir;
    std::uint64_t cache_budget = std::uint64_t{32} << 20U;
};

/// What the sun has done, for logs and tests.
struct SunStats {
    std::uint64_t scenes = 0;     ///< scenes built
    double scene_ms = 0;          ///< the last scene's build
    std::size_t tiles = 0;        ///< the last scene's tiles (the halo's among them)
    std::size_t resident = 0;     ///< of which its bits are asked for
    std::uint64_t runs = 0;       ///< buckets run
    std::uint64_t cached = 0;     ///< tiles taken from the disk cache
    std::size_t texels = 0;       ///< texels the last frame wrote
    std::uint64_t failed = 0;     ///< scenes that could not be built
    std::size_t others = 0;       ///< the last scene's tiles of other packs (their solids alone)
    std::size_t solids = 0;       ///< the last scene's solids, of which
    std::size_t other_solids = 0; ///< the other packs'
    /// The last working out of every lane's other packs' tiles (Lane::others), on the render thread: done where a
    /// lane's pack, role, level or resident tiles changed, else nothing a frame.
    double others_ms = 0;
};

/// The world's sun: a provider of set 1's sun_bits.
class SunGlobals final : public frame::GlobalProvider {
public:
    /// The sun of the tiles `residency` holds from `store`, its buffers on `device`; `clock` gives the time its bits'
    /// fades run on (seconds; called from the worker's threads too), a steady clock of its own when empty.
    [[nodiscard]] static gpu::Result<std::unique_ptr<SunGlobals>> create(const gpu::Device& device,
                                                                         const tiles::Store& store,
                                                                         const Residency& residency, SunOptions options,
                                                                         std::function<double()> clock);
    ~SunGlobals() override;
    SunGlobals(const SunGlobals&) = delete;
    SunGlobals& operator=(const SunGlobals&) = delete;
    SunGlobals(SunGlobals&&) = delete;
    SunGlobals& operator=(SunGlobals&&) = delete;

    // frame::GlobalProvider
    [[nodiscard]] std::string_view name() const noexcept override { return "sun"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return binding; }
    void update(const frame::FrameState& state, frame::Globals& globals) override;

    /// The frame's sun, on the render thread after the cover's update: each lane's scene and bucket asked for where
    /// they changed, and every tile `cover` draws given its bits (CoverTile::sun, sun_base, sun_weight) in frame slot
    /// `state.slot`'s half of the buffer. `pack` is the active pack (null: none) and `level` its tiles' ladder level;
    /// `coarser` the coarser pack drawn around it (null: none) and `coarser_level` its tiles'; `neighbours` the packs
    /// drawn beside them, nearest first (null: none; max_neighbours at most), and `neighbour_levels` their tiles'.
    void prepare(const frame::FrameState& state, const std::shared_ptr<const tiles::StorePack>& pack,
                 std::uint8_t level, Cover& cover, const std::shared_ptr<const tiles::StorePack>& coarser = nullptr,
                 std::uint8_t coarser_level = 0,
                 std::span<const std::shared_ptr<const tiles::StorePack>> neighbours = {},
                 std::span<const std::uint8_t> neighbour_levels = {});

    /// Called on a worker's thread as a run's bits are published, once a run (cheap and never blocking: the frame
    /// loop's wake).
    void set_wake(const std::function<void()>& wake) {
        for (Lane& l : lanes_) l.bits.on_publish(wake);
    }
    /// Whether a tile the cover drew last frame is still cross-fading its bits.
    [[nodiscard]] bool fading() const noexcept { return fading_; }
    /// A lane's role this frame: the active pack's, the coarser pack's, a neighbour's.
    enum class LaneOf : std::uint8_t { active = 0, coarser = 1, neighbour = 2 };
    /// The bits, as the workers publish them, of the lane in role `lane` (the first neighbour's for a neighbour; the
    /// first lane's where no lane has the role).
    [[nodiscard]] const sun::BitTiles& bits(LaneOf lane = LaneOf::active) const noexcept;
    /// The buffer set 1's sun_bits reads (empty before the first texels), its halves' texels.
    [[nodiscard]] const gpu::Buffer& buffer() const noexcept { return buffer_; }
    [[nodiscard]] std::size_t capacity() const noexcept { return capacity_; }
    /// Waits until the scenes asked for are built and their buckets run (tests).
    void wait_idle();
    /// Stops the builder and the workers for good (the world's end: every thread stops before what they share goes).
    void stop();
    /// The active pack's lane's (scenes and runs: every lane's).
    [[nodiscard]] SunStats stats() const;

private:
    SunGlobals(const gpu::Device& device, const tiles::Store& store, const Residency& residency, SunOptions options,
               std::function<double()> clock);

    /// A tile of another pack drawn whose solids alone a lane's scene holds, at its ladder level (its chunk: what it
    /// holds).
    struct Other {
        std::shared_ptr<const tiles::StorePack> pack;
        tiles::TileKey key;
        std::uint8_t level = 0;
        tiles::Sha256Digest chunk{};
        friend bool operator==(const Other& a, const Other& b) noexcept {
            return a.pack->id == b.pack->id && a.key == b.key && a.level == b.level && a.chunk == b.chunk;
        }
    };

    /// A scene to build: the pack, its tiles (each at its ladder level), the other packs' tiles whose solids it holds,
    /// which of its own want bits, the bucket and the jobs' order (the camera's ground point, the visible tiles, the
    /// windows' reach).
    struct Build {
        std::shared_ptr<const tiles::StorePack> pack;
        std::vector<std::pair<tiles::TileKey, std::uint8_t>> tiles;
        std::vector<Other> others;
        std::vector<tiles::TileKey> wanted;
        std::vector<tiles::TileKey> visible;
        sun::Bucket bucket;
        double cx = 0;
        double cz = 0;
        double window_reach = 0;
        bool active = false; ///< the active pack's: its bucket runs on all the sun's threads
    };

    /// One pack's sun: its bits and worker, the render thread's view of its scene, and the builder's.
    struct Lane {
        sun::BitTiles bits;
        std::unique_ptr<sun::Worker> worker;
        // The render thread's.
        std::string pack_id;
        LaneOf role = LaneOf::neighbour; ///< this frame's, where pack_id is drawn
        std::uint8_t fine = 0;
        std::vector<std::pair<std::uint64_t, std::uint8_t>> asked; ///< the scene's tiles (packed key, level), sorted
        std::vector<std::uint64_t> wanted;                         ///< its resident tiles, sorted
        std::vector<Other> others;                                 ///< the other packs' solids to hold, sorted
        std::vector<Other> asked_others;                           ///< those its scene was last asked with
        std::uint8_t level = 0;
        std::uint64_t bucket = 0;
        bool have_bucket = false;
        // The builder thread's request and results (under mutex_).
        std::optional<Build> next;
        bool building = false;
        std::shared_ptr<const sun::Scene> scene;
        // The builder thread's own.
        std::vector<std::pair<std::uint64_t, std::uint8_t>> built;
        std::vector<Other> built_others;
        std::string built_pack;
    };

    /// A lane's pack this frame, and what of it the other lanes' scenes read: its level and resident tiles.
    struct LaneInputs {
        std::shared_ptr<const tiles::StorePack> pack;
        LaneOf role = LaneOf::neighbour;
        std::uint8_t level = 0;
        std::vector<std::uint64_t> wanted;                          ///< resident tiles, sorted
        std::vector<std::pair<std::uint64_t, std::uint8_t>> levels; ///< their levels, sorted
        friend bool operator==(const LaneInputs&, const LaneInputs&) = default;
    };

    void work(const std::stop_token& stop);
    /// The lane's scene and bucket for the frame (the render thread): asked for where they changed. `eye_frame` is the
    /// frame the view's eye is in (the active pack's), from which the eye is taken into the lane's pack's own.
    void prepare_lane(Lane& lane, const frame::FrameState& state, const LaneInputs& in,
                      std::span<const CoverTile> drawn, const std::optional<view::GroundFrame>& eye_frame);
    /// The other packs' tiles lane `k`'s scene holds the solids of (Other): each finest tile of the active pack or a
    /// neighbour (not the coarser one) whose reach comes within the march's reach of a resident tile of lane `k`'s
    /// pack, at a place that pack has no tile of; none for the coarser pack's lane. Sorted by pack and tile.
    [[nodiscard]] static std::vector<Other> others_of(std::size_t k, std::span<const LaneInputs> lanes);
    [[nodiscard]] std::shared_ptr<const sun::Scene> build(const Build& b);
    [[nodiscard]] std::optional<double> maxh(const std::shared_ptr<const tiles::StorePack>& pack, std::uint8_t level);
    void ask(Lane& lane, const std::shared_ptr<const sun::Scene>& scene, const Build& b);
    [[nodiscard]] double now() const;

    const gpu::Device& device_;
    const tiles::Store& store_;
    const Residency& residency_;
    SunOptions options_;
    std::function<double()> clock_;
    std::chrono::steady_clock::time_point started_ = std::chrono::steady_clock::now();
    static constexpr std::array<frame::Global, 1> binding{frame::Global::sun_bits};

    std::unique_ptr<sun::DiskCache> cache_;
    /// A lane for each pack drawn (the active one, the coarser one, the neighbours), each kept by its pack.
    std::array<Lane, 2 + max_neighbours> lanes_;
    /// The active pack's lane (the render thread sets it; the builder asks it first, and stats() are its).
    std::atomic<std::size_t> active_lane_{0};
    /// What each lane's others (Lane::others) were last worked out from (the render thread's).
    std::array<LaneInputs, 2 + max_neighbours> others_from_;

    // The render thread's.
    std::vector<std::uint32_t> texels_;
    gpu::Buffer buffer_;
    std::size_t capacity_ = 0;                                   ///< texels per half
    std::vector<std::pair<gpu::Buffer, std::uint64_t>> retired_; ///< old buffers and the last frame that read them
    bool fading_ = false;
    std::size_t last_texels_ = 0;
    double others_ms_ = 0;

    // The builder thread's wakes (the lanes' requests and results are under mutex_).
    mutable std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    /// scene_maxh per pack (the store's own, never its identity: the APK's packs have none) and level.
    struct Tallest {
        std::weak_ptr<const tiles::StorePack> pack;
        std::uint8_t level = 0;
        double h = 0.0;
    };
    std::vector<Tallest> maxh_;
    SunStats stats_;
    std::jthread thread_; ///< last: it starts once every member above is made, and stops first
};

} // namespace atlas::scene
