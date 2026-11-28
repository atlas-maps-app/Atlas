// The sun worker (plan-vector D8, K8): stage S. Per sun bucket it tests every sample of the tiles it holds, visible
// tiles nearest first, on its threads, and publishes the run's bits together once they are all complete, so a run wakes
// the frame loop once; the frame keeps a tile's old bits until then and cross-fades the swap (BitTiles). A live
// moment's bucket is its sun at 0.5° steps of azimuth and elevation (a new one about every two minutes), a named
// moment's its own sun exactly; named moments' buckets are kept per tile on disk.
//
// The scene is the resident tiles plus a 220 m halo, as the march reaches: their solids in one grid (built once while
// the tiles stay; it does not depend on the sun), each tile's samples tested only for the tiles asked. Windows are
// tested for the tiles a job flags (those within win_d · 1.35 of the camera, nearest_first): the renders draw them
// nearer than win_d.
#pragma once

#include "style/Theme.hpp"
#include "sun/BitTiles.hpp"
#include "sun/Grid.hpp"
#include "sun/Samples.hpp"
#include "sun/Shadows.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"

#include <atomic>
#include <bit>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <iterator>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <thread>
#include <vector>

namespace atlas::sun {

/// A sun bucket's width, degrees of azimuth and of elevation.
inline constexpr double bucket_degrees = 0.5;

/// The layout of a tile's samples and bits, kept with every bucket on disk: a change to the samples' order or to what
/// they are bumps it, and older files are never read. 2: a file keeps where the tile's crowns begin. 3: a frame cell's
/// level's tile has its lean walls' samples (make_tile).
inline constexpr std::uint16_t sample_format = 3;

/// A sun bucket: the sun one set of bits is for.
struct Bucket {
    Sun sun;
    std::uint64_t key = 0; ///< the sun's bits, hashed: the same sun, the same key
    bool named = false;    ///< a named moment's own sun (kept on disk); else a live sun's bucket

    friend bool operator==(const Bucket&, const Bucket&) = default;
};

/// Living.sunVector: the unit vector toward the sun at `azimuth` and `elevation` (degrees), with pymath.
[[nodiscard]] Sun sun_vector(double azimuth, double elevation) noexcept;

/// The bucket of a sun as the port's bits.
[[nodiscard]] Bucket bucket_of(const Sun& sun, bool named) noexcept;

/// The bucket of a moment's theme block: a named moment's own sun, bit for bit; a live moment's sun at its azimuth
/// and elevation rounded to 0.5° (at least 0.5° up while the sun is up); straight down while the sun is set.
[[nodiscard]] Bucket bucket_of(const style::ThemeBlock& block) noexcept;

/// A scene's tiles in order, each one's part (shared between scenes) read as `const SunTile&`.
class SceneTiles {
public:
    class Iterator {
    public:
        using iterator_concept = std::forward_iterator_tag;
        using iterator_category = std::forward_iterator_tag;
        using value_type = SunTile;
        using difference_type = std::ptrdiff_t;
        using reference = const SunTile&;
        using pointer = const SunTile*;
        Iterator() = default;
        explicit Iterator(std::span<const std::shared_ptr<const SunTile>>::iterator at) noexcept : at_(at) {}
        [[nodiscard]] reference operator*() const noexcept { return **at_; }
        [[nodiscard]] pointer operator->() const noexcept { return at_->get(); }
        Iterator& operator++() noexcept {
            ++at_;
            return *this;
        }
        Iterator operator++(int) noexcept {
            const Iterator was = *this;
            ++at_;
            return was;
        }
        [[nodiscard]] bool operator==(const Iterator&) const = default;

    private:
        std::span<const std::shared_ptr<const SunTile>>::iterator at_;
    };

    explicit SceneTiles(std::span<const std::shared_ptr<const SunTile>> parts) noexcept : parts_(parts) {}
    [[nodiscard]] std::size_t size() const noexcept { return parts_.size(); }
    [[nodiscard]] bool empty() const noexcept { return parts_.empty(); }
    [[nodiscard]] const SunTile& operator[](std::size_t t) const noexcept { return *parts_[t]; }
    [[nodiscard]] Iterator begin() const noexcept { return Iterator(parts_.begin()); }
    [[nodiscard]] Iterator end() const noexcept { return Iterator(parts_.end()); }

private:
    std::span<const std::shared_ptr<const SunTile>> parts_;
};

/// The sun's scene: the tiles the worker holds and the grid over all their solids.
class Scene {
public:
    /// The grid over every tile's solids, in the tiles' order; `scene_maxh` is the scene's tallest solid
    /// (Samples.hpp's scene_maxh). `chunks` are the tiles' chunk hashes (for the disk cache; empty: none kept).
    Scene(std::vector<SunTile> tiles, double scene_maxh, std::vector<tiles::Sha256Digest> chunks = {});
    /// The same over tiles' parts that other scenes may share (SunGlobals keeps each tile's part from one scene to
    /// the next, never a copy of it).
    Scene(std::vector<std::shared_ptr<const SunTile>> tiles, double scene_maxh,
          std::vector<tiles::Sha256Digest> chunks = {});
    /// `from` with its tiles as `tiles`: the same tiles with the same solids, some made whole that were their solids
    /// alone (a tile of the halo come resident: SunGlobals). Its grid is `from`'s, shared, and so are its chunks.
    Scene(const Scene& from, std::vector<std::shared_ptr<const SunTile>> tiles);

    [[nodiscard]] const Grid& grid() const noexcept { return *grid_; }
    /// Its tiles, in order, each one's part as `const SunTile&`.
    [[nodiscard]] SceneTiles tiles() const noexcept { return SceneTiles(tiles_); }
    /// Tile `t`'s part, which other scenes may share.
    [[nodiscard]] const std::shared_ptr<const SunTile>& part(std::size_t t) const noexcept { return tiles_[t]; }
    /// Where tile `t`'s solids begin in the grid.
    [[nodiscard]] PrismId base(std::size_t t) const noexcept { return base_[t]; }
    /// Tile `t`'s chunk hash, or nothing: the disk cache's key for its bits (the scene's maker may hash into it the
    /// chunks within the march's reach of the tile, which its bits depend on: SunGlobals).
    [[nodiscard]] const tiles::Sha256Digest* chunk(std::size_t t) const noexcept {
        return t < chunks_.size() ? &chunks_[t] : nullptr;
    }
    /// What tile `t`'s bits are made against (TileBits::context): its chunk hash's first eight bytes, 0 without one.
    [[nodiscard]] std::uint64_t context(std::size_t t) const noexcept;
    /// The tile of `key`, or nothing.
    [[nodiscard]] std::optional<std::uint32_t> find(tiles::TileKey key) const noexcept;

private:
    std::vector<std::shared_ptr<const SunTile>> tiles_;
    std::vector<PrismId> base_;
    std::vector<tiles::Sha256Digest> chunks_;
    std::shared_ptr<const Grid> grid_;
};

/// The tiles a sun scene holds for `resident` tiles of `pack`: every chunk of their level whose reach comes within
/// `metres` (the march's reach) of a resident tile's reach, the resident among them, in the pack's order. The scene's
/// grid holds all their solids, so every march from a resident tile's samples finds every solid it can reach; a job
/// asks only for the resident. Keys the pack does not hold are left out.
[[nodiscard]] std::vector<tiles::TileKey> halo_of(const tiles::Pack& pack, std::span<const tiles::TileKey> resident,
                                                  double metres = reach);

/// Whether sample `i` of a tile is lit for `sun`: it faces the sun (or is tested whatever the sun) and the march from
/// it leaves every solid; one whose record the scene takes from another tile (no_march) faces it alone. `base` is
/// where the tile's solids begin in the grid.
[[nodiscard]] bool sample_lit(const Grid& grid, PrismId base, const TileSamples& s, std::uint32_t i,
                              const Sun& sun) noexcept;

/// The bits of samples [from, to) of a tile into `words`, bit i for sample i (`from` a multiple of 64; the words of
/// [from, to) are overwritten, no other word is touched).
void evaluate(const Grid& grid, PrismId base, const TileSamples& s, const Sun& sun, std::uint32_t from,
              std::uint32_t to, std::span<std::uint64_t> words) noexcept;

/// One tile of a bucket's run.
struct TileJob {
    std::uint32_t tile = 0; ///< its place in the scene's tiles
    bool windows = false;   ///< test its window group too
};

/// The jobs for every tile of `scene`, nearest the camera's ground point (cx, cz) first, the tiles `visible` marks
/// (non-zero, by their place in the scene) before the others; windows for the tiles whose box lies within
/// `window_reach` metres of it.
[[nodiscard]] std::vector<TileJob> nearest_first(const Scene& scene, const tiles::Frame& frame, double cx, double cz,
                                                 std::span<const std::uint8_t> visible, double window_reach);

/// The most samples of one tile a thread of a run evaluates at once (a piece): large enough that taking one costs
/// nothing, small enough that four threads share a tile.
inline constexpr std::uint32_t piece_samples = 4096;

/// What a run did once told to end: the pieces its threads began after they saw `wind_up`, and the pieces it evaluated
/// of tiles it never finished (their words dropped with them).
struct RunEnd {
    std::uint64_t late = 0;
    std::uint64_t dropped = 0;
};

/// A bucket over `jobs`, in their order, on `threads` threads (the caller among them): pieces of up to piece_samples
/// samples taken in turn, each tile's bits handed to `done` (on whichever thread completed it) as soon as all its
/// pieces are.
/// - Once `stop` (null: never) is set, no piece begins: the tiles not done by then are never handed on.
/// - Once `wind_up` (null: never) is set, no tile begins: the tiles begun by then are finished and handed on, the
///   others never are. So its threads begin at most P - 1 pieces more, P a tile's pieces (the rest of the tile being
///   taken), and one each (the next tile's first, taken as they are told), dropped.
RunEnd run(const Scene& scene, const Bucket& bucket, std::span<const TileJob> jobs, unsigned threads,
           const std::function<void(std::uint32_t tile, TileBits bits)>& done, const std::atomic<bool>* stop = nullptr,
           const std::atomic<bool>* wind_up = nullptr);

/// Whether `bits` holds tile `j`'s bits for the bucket `bucket` (its key) made against what `scene` holds near it
/// (Scene::context), its window group among them where `j` asks for it: a run need not make them again. `t` is the
/// frame's time, for the view.
[[nodiscard]] bool bits_there(const BitTiles& bits, const Scene& scene, const TileJob& j, std::uint64_t bucket,
                              double t);

/// Named moments' buckets kept per tile on disk (`<dir>/<key>.sun`, the key a hash of the chunk's SHA-256, the level,
/// the samples' format and the sun's bits), at most `budget` bytes, the oldest dropped first.
class DiskCache {
public:
    explicit DiskCache(std::string dir, std::uint64_t budget = std::uint64_t{32} << 20U);

    /// The bits kept for tile `chunk` at `level` for `bucket` (with its window group when `windows` is asked), or
    /// nothing.
    [[nodiscard]] std::optional<TileBits> load(const tiles::Sha256Digest& chunk, std::uint8_t level,
                                               const Bucket& bucket, std::uint32_t count, bool windows) const;

    /// Keeps `bits` for tile `chunk` at `level`; returns whether they were written.
    bool store(const tiles::Sha256Digest& chunk, std::uint8_t level, const TileBits& bits);

private:
    [[nodiscard]] std::string path(const tiles::Sha256Digest& chunk, std::uint8_t level, const Bucket& bucket) const;
    void trim();

    std::string dir_;
    std::uint64_t budget_;
    mutable std::mutex mutex_;
};

/// The sun's check (CI's runners and the phone, where no JVM holds the core to the port): the march and hull2 over a
/// scene drawn from PyRandom(20261018) (600 prisms of 3 to 8 sides, 5,000 points, 5 suns; 300 point sets for hull2),
/// hashed with FNV-1a 64 over every answer and every hull point's bits. MT/sun/SunParityTest.kt draws the same scene
/// and computes it with the port's own Shadows and hull2; `march_check_expected` is that hash.
[[nodiscard]] std::uint64_t march_check();

/// The port's hash of the check's scene (MT/sun/SunParityTest.kt holds this value to the port's).
inline constexpr std::uint64_t march_check_expected = 0x6C22'FF27'6113'E698ULL;

/// `sun <hash> ok`, or `sun <hash> FAIL expected <hash>`.
[[nodiscard]] std::string march_check_line();

/// The Westerkerk's sunny sun, as the port's bits (Amsterdam.sunOf(THEMES['sunny']) over the living map): the sun of
/// the bundled check (MT/sun/SunParityTest.kt holds it to the port's).
inline constexpr Sun bundled_sun{.x = std::bit_cast<double>(0xBFE3'702C'8755'BB0BULL),
                                 .y = std::bit_cast<double>(0x3FDC'01A6'8C65'0CC6ULL),
                                 .z = std::bit_cast<double>(0x3FE5'36FB'D771'42E0ULL)};

/// The sun's check over the bundled Westerkerk (the app's tiles/amsterdam.atlas), for the emulator's and the phone's
/// Bionic: every sample of its one tile level at ladder level 0 (the living map's), windows included, tested for
/// bundled_sun on up to four threads, and every ground shadow, hashed with SHA-256 (its first 8 bytes) after the march
/// check's value. The host build computes the same (MT/sun/SunParityTest.kt, whose parity test holds the host's bits to
/// the port's), and `bundled_check_expected` and ci/hashes.env's ATLAS_SUN_HASH are its hash.
[[nodiscard]] tiles::Result<std::uint64_t> bundled_check(const tiles::Pack& pack);

/// The host build's hash of the bundled check.
inline constexpr std::uint64_t bundled_check_expected = 0x538B'B90B'B4F0'7E41ULL;

/// `sun <hash> ok`, or `sun <hash> FAIL expected <hash>` (with the march check's hash when that one failed too), or
/// `sun unavailable: <why>`, for the bundled pack in `pack`.
[[nodiscard]] std::string sun_line(const tiles::Pack& pack);

/// sun_line for the pack in `length` bytes of the open file `fd` from `offset` (an APK asset's descriptor).
[[nodiscard]] std::string sun_line(int fd, std::uint64_t offset, std::uint64_t length);

/// What a worker's stale runs did (a run under way as a newer request came: Worker::request), so far.
struct StaleStats {
    std::uint64_t stopped = 0; ///< stale runs stopped before their end (fewer tiles made than asked)
    /// The longest a newer request waited for the run under way to publish, milliseconds of the wall clock (every run
    /// a newer request came during, stopped or not).
    double newer_wait_ms = 0;
    std::uint64_t late = 0;     ///< pieces stale runs began after they were told (RunEnd::late)
    std::uint64_t dropped = 0;  ///< pieces stale runs evaluated of tiles they never finished (RunEnd::dropped)
    std::uint64_t left_out = 0; ///< tiles requests left out as they were taken, their bits there by then
};

/// The sun worker: a thread that runs the latest bucket asked for over the latest scene, publishing into `bits`; a
/// named moment's tiles come from the disk cache when it holds them and go into it when computed.
class Worker {
public:
    /// A worker with `threads` threads in all (its own and the run's helpers); `cache` may be null; `clock` gives the
    /// frame's time in seconds for the publications' fades, and is called from the worker's threads.
    Worker(BitTiles& bits, unsigned threads, DiskCache* cache, std::function<double()> clock);
    ~Worker();
    Worker(const Worker&) = delete;
    Worker& operator=(const Worker&) = delete;
    Worker(Worker&&) = delete;
    Worker& operator=(Worker&&) = delete;

    /// Asks for `bucket` over `scene`'s tiles in `jobs`' order, on `threads` threads (0: the worker's own count, at
    /// most that); it replaces a request not yet begun. A run under way is stale then: it begins no tile more, and
    /// publishes the tiles it made, those it had begun among them (run's `wind_up`). This request, as it is taken,
    /// leaves out the tiles whose bits are there by then (bits_there), so that none is made twice.
    void request(std::shared_ptr<const Scene> scene, const Bucket& bucket, std::vector<TileJob> jobs,
                 unsigned threads = 0);

    /// Waits until every request made so far is done.
    void wait_idle();
    /// Drops the request not yet begun and stops the run under way before its next piece, its bits never published
    /// (the sun left out: ENGINE.md's parts); a request after it runs as before.
    void cancel();

    /// Requests run so far, and tiles taken from the disk cache.
    [[nodiscard]] std::uint64_t runs() const;
    [[nodiscard]] std::uint64_t cached() const;
    /// The runs' own time so far, milliseconds of the wall clock from a request's taking to its bits' publication
    /// (the disk cache's loads among it; its helpers' time is within it), and the tiles their buckets computed.
    [[nodiscard]] double run_ms() const;
    [[nodiscard]] std::uint64_t run_tiles() const;
    /// What its stale runs did so far.
    [[nodiscard]] StaleStats stale() const;
#if defined(ATLAS_TESTS)
    /// The tests' switch: off, a run under way goes to its end whatever is asked after it, and a request leaves out
    /// nothing as it is taken (the base a stale run's stop is held to).
    void stop_stale(bool on) noexcept { stop_stale_.store(on, std::memory_order_relaxed); }
#endif

private:
    struct Request {
        std::shared_ptr<const Scene> scene;
        Bucket bucket;
        std::vector<TileJob> jobs;
        unsigned threads = 0;
        bool after_run = false; ///< asked while a run was under way: leaves out the tiles it made (bits_there)
    };

    void loop(const std::stop_token& stop);

    BitTiles& bits_;
    unsigned threads_;
    DiskCache* cache_;
    std::function<double()> clock_;
    mutable std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    std::optional<Request> next_;
    bool busy_ = false;
    std::atomic<bool> cancelled_{false}; ///< the run under way stops (cancel; cleared as a request is taken)
    std::atomic<bool> stale_{false};     ///< the run under way winds up (a newer request; cleared as one is taken)
    std::atomic<bool> stop_stale_{true}; ///< a newer request makes the run under way stale (stop_stale)
    /// When the first request after the one under way came (none: none came yet).
    std::optional<std::chrono::steady_clock::time_point> newer_;
    std::uint64_t runs_ = 0;
    std::uint64_t cached_ = 0;
    double run_ms_ = 0;
    std::uint64_t run_tiles_ = 0;
    StaleStats stale_stats_;
    std::jthread thread_; ///< last: it starts once every member above is made, and stops before they go
};

} // namespace atlas::sun

/// A scene's tiles view its scene's parts (it owns none), so an iterator outlives the view it came from.
template <>
inline constexpr bool std::ranges::enable_borrowed_range<atlas::sun::SceneTiles> = true;
