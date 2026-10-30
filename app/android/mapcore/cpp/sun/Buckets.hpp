// The sun worker (plan-vector D8, K8): stage S. Per sun bucket it tests every sample of the tiles it holds, visible
// tiles nearest first, on its threads, and publishes each tile's bits as soon as they are complete; the frame keeps a
// tile's old bits until then and cross-fades the swap (BitTiles). A live moment's bucket is its sun at 0.5° steps of
// azimuth and elevation (a new one about every two minutes), a named moment's its own sun exactly; named moments'
// buckets are kept per tile on disk.
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

#include <bit>
#include <condition_variable>
#include <cstdint>
#include <functional>
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
/// they are bumps it, and older files are never read. 2: a file keeps where the tile's crowns begin.
inline constexpr std::uint16_t sample_format = 2;

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

/// The sun's scene: the tiles the worker holds and the grid over all their solids.
class Scene {
public:
    /// The grid over every tile's solids, in the tiles' order; `scene_maxh` is the scene's tallest solid
    /// (Samples.hpp's scene_maxh). `chunks` are the tiles' chunk hashes (for the disk cache; empty: none kept).
    Scene(std::vector<SunTile> tiles, double scene_maxh, std::vector<tiles::Sha256Digest> chunks = {});

    [[nodiscard]] const Grid& grid() const noexcept { return grid_; }
    [[nodiscard]] std::span<const SunTile> tiles() const noexcept { return tiles_; }
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
    std::vector<SunTile> tiles_;
    std::vector<PrismId> base_;
    std::vector<tiles::Sha256Digest> chunks_;
    Grid grid_;
};

/// The tiles a sun scene holds for `resident` tiles of `pack`: every chunk of their level whose reach comes within
/// `metres` (the march's reach) of a resident tile's reach, the resident among them, in the pack's order. The scene's
/// grid holds all their solids, so every march from a resident tile's samples finds every solid it can reach; a job
/// asks only for the resident. Keys the pack does not hold are left out.
[[nodiscard]] std::vector<tiles::TileKey> halo_of(const tiles::Pack& pack, std::span<const tiles::TileKey> resident,
                                                  double metres = reach);

/// Whether sample `i` of a tile is lit for `sun`: it faces the sun (or is tested whatever the sun) and the march from
/// it leaves every solid. `base` is where the tile's solids begin in the grid.
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

/// A bucket over `jobs`, in their order, on `threads` threads (the caller among them): pieces of up to 4,096 samples
/// taken in turn, each tile's bits handed to `done` (on whichever thread completed it) as soon as all its pieces are.
void run(const Scene& scene, const Bucket& bucket, std::span<const TileJob> jobs, unsigned threads,
         const std::function<void(std::uint32_t tile, TileBits bits)>& done);

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
inline constexpr std::uint64_t bundled_check_expected = 0x6A47'C776'C64E'D9AEULL;

/// `sun <hash> ok`, or `sun <hash> FAIL expected <hash>` (with the march check's hash when that one failed too), or
/// `sun unavailable: <why>`, for the bundled pack in `pack`.
[[nodiscard]] std::string sun_line(const tiles::Pack& pack);

/// sun_line for the pack in `length` bytes of the open file `fd` from `offset` (an APK asset's descriptor).
[[nodiscard]] std::string sun_line(int fd, std::uint64_t offset, std::uint64_t length);

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
    /// most that); it replaces a request not yet begun.
    void request(std::shared_ptr<const Scene> scene, const Bucket& bucket, std::vector<TileJob> jobs,
                 unsigned threads = 0);

    /// Waits until every request made so far is done.
    void wait_idle();

    /// Requests run so far, and tiles taken from the disk cache.
    [[nodiscard]] std::uint64_t runs() const;
    [[nodiscard]] std::uint64_t cached() const;

private:
    struct Request {
        std::shared_ptr<const Scene> scene;
        Bucket bucket;
        std::vector<TileJob> jobs;
        unsigned threads = 0;
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
    std::uint64_t runs_ = 0;
    std::uint64_t cached_ = 0;
    std::jthread thread_; ///< last: it starts once every member above is made, and stops before they go
};

} // namespace atlas::sun
