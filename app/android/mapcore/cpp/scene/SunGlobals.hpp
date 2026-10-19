// The sun's bits in set 1 (plan-vector D8, K8's worker; frame/Globals.hpp's first provider that no pass owns): the
// world's sun. Its scene is the active pack's resident tiles at their finest zoom and their 220 m halo
// (Residency::sun_tiles), each made at the ladder level it is drawn at (a halo tile at the cover's), built on a thread
// of its own whenever that set changes; K8's Worker runs the moment's bucket (sun::bucket_of the theme block: a named
// moment's own sun, a live one's 0.5° bucket) over it, visible tiles nearest first, skipping a tile whose bits for the
// bucket are there, and publishes each tile's bits as it completes (BitTiles, which cross-fades a swap over 300 ms).
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
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
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
    std::uint64_t scenes = 0; ///< scenes built
    double scene_ms = 0;      ///< the last scene's build
    std::size_t tiles = 0;    ///< the last scene's tiles (the halo's among them)
    std::size_t resident = 0; ///< of which its bits are asked for
    std::uint64_t runs = 0;   ///< buckets run
    std::uint64_t cached = 0; ///< tiles taken from the disk cache
    std::size_t texels = 0;   ///< texels the last frame wrote
    std::uint64_t failed = 0; ///< scenes that could not be built
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

    /// The frame's sun, on the render thread after the cover's update: the scene and the bucket asked for where they
    /// changed, and every tile `cover` draws given its bits (CoverTile::sun, sun_base, sun_weight) in frame slot
    /// `state.slot`'s half of the buffer. `pack` is the active pack (null: none), `level` the cover's ladder level.
    void prepare(const frame::FrameState& state, const std::shared_ptr<const tiles::StorePack>& pack,
                 std::uint8_t level, Cover& cover);

    /// Called on a worker's thread as each tile's bits are published (cheap and never blocking: the frame loop's wake).
    void set_wake(std::function<void()> wake) { bits_.on_publish(std::move(wake)); }
    /// Whether a tile the cover drew last frame is still cross-fading its bits.
    [[nodiscard]] bool fading() const noexcept { return fading_; }
    /// The bits, as the workers publish them.
    [[nodiscard]] const sun::BitTiles& bits() const noexcept { return bits_; }
    /// The buffer set 1's sun_bits reads (empty before the first texels), its halves' texels.
    [[nodiscard]] const gpu::Buffer& buffer() const noexcept { return buffer_; }
    [[nodiscard]] std::size_t capacity() const noexcept { return capacity_; }
    /// Waits until the scene asked for is built and its bucket run (tests).
    void wait_idle();
    [[nodiscard]] SunStats stats() const;

private:
    SunGlobals(const gpu::Device& device, const tiles::Store& store, const Residency& residency, SunOptions options,
               std::function<double()> clock);

    /// A scene to build: the pack, its tiles (each at its ladder level), which of them want bits, the bucket and the
    /// jobs' order (the camera's ground point, the visible tiles, the windows' reach).
    struct Build {
        std::shared_ptr<const tiles::StorePack> pack;
        std::vector<std::pair<tiles::TileKey, std::uint8_t>> tiles;
        std::vector<tiles::TileKey> wanted;
        std::vector<tiles::TileKey> visible;
        sun::Bucket bucket;
        double cx = 0;
        double cz = 0;
        double window_reach = 0;
    };

    void work(const std::stop_token& stop);
    [[nodiscard]] std::shared_ptr<const sun::Scene> build(const Build& b);
    [[nodiscard]] std::optional<double> maxh(const tiles::StorePack& pack, std::uint8_t level);
    void ask(const std::shared_ptr<const sun::Scene>& scene, const Build& b);
    [[nodiscard]] double now() const;

    const gpu::Device& device_;
    const tiles::Store& store_;
    const Residency& residency_;
    SunOptions options_;
    std::function<double()> clock_;
    std::chrono::steady_clock::time_point started_ = std::chrono::steady_clock::now();
    static constexpr std::array<frame::Global, 1> binding{frame::Global::sun_bits};

    sun::BitTiles bits_;
    std::unique_ptr<sun::DiskCache> cache_;
    std::unique_ptr<sun::Worker> worker_;

    // The render thread's.
    std::string pack_id_;
    std::vector<std::pair<std::uint64_t, std::uint8_t>> asked_; ///< the scene's tiles (packed key, level), sorted
    std::vector<std::uint64_t> wanted_;                         ///< its resident tiles, sorted
    std::uint8_t level_ = 0;
    std::uint64_t bucket_ = 0;
    bool have_bucket_ = false;
    std::vector<std::uint32_t> texels_;
    gpu::Buffer buffer_;
    std::size_t capacity_ = 0;                                   ///< texels per half
    std::vector<std::pair<gpu::Buffer, std::uint64_t>> retired_; ///< old buffers and the last frame that read them
    bool fading_ = false;
    std::size_t last_texels_ = 0;

    // The builder thread's request and results.
    mutable std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    std::optional<Build> next_;
    bool building_ = false;
    std::shared_ptr<const sun::Scene> scene_;
    std::shared_ptr<const tiles::StorePack> scene_pack_;
    std::vector<std::pair<std::uint64_t, double>> maxh_; ///< scene_maxh per (the pack identity's first word, level)
    SunStats stats_;
    std::jthread thread_; ///< last: it starts once every member above is made, and stops first
};

} // namespace atlas::scene
