// The expanded-tile cache (plan-vector D2, D3): what the core made or fetched once, kept on disk so it is made or
// fetched once: stage T's GPU-ready buffers of a tile, keyed by its chunk's SHA-256, StyleVersion and the buffer
// format; a remote pack's chunks (the world mid-zoom base's, read by HTTP ranges), keyed by their SHA-256, so a place
// seen once works offline and a monthly rebuild refetches only the chunks whose data changed; and a remote pack's head
// (its header, directory, chunk table, `rhead` and `style`). In `cacheDir/rtiles`, least recently used out first,
// within a byte budget (256 MB on the phone).
//
// An entry is one file, named by its key's SHA-256 in hex, written to a temporary name and renamed into place, so a
// reader never sees half an entry:
//    0  u32 "ATXC", u16 entry format (1), u8 kind, u8 zero
//    8  the key's SHA-256 (32 bytes)
//   40  u64 payload length, u32 CRC-32 of the payload, u32 CRC-32 of bytes 0–51 (everything before it)
//   56  eight zero bytes; the payload from 64
// An entry that fails any check (a torn write, a damaged sector, a file of another format) is deleted and is a miss.
// Recency survives restarts in the files' modification times, touched on each hit. Every change to an entry's file
// (rename into place, eviction, removal) happens under the cache's lock; reads map files outside it.
#pragma once

#include "tiles/Checksums.hpp"
#include "tiles/Error.hpp"
#include "tiles/Source.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace atlas::tiles {

/// What an entry holds.
enum class CacheKind : std::uint8_t {
    built = 1,     ///< stage T's buffers of a tile (K10 and the feature packages)
    chunk = 2,     ///< a remote pack's chunk, as stored (zstd)
    pack_head = 3, ///< a remote pack's head: the byte ranges opening it reads
};

/// An entry's key: what it was made from.
struct CacheKey {
    CacheKind kind = CacheKind::built;
    Sha256Digest source{};    ///< the chunk's SHA-256, or the remote pack's identity
    std::string_view style;   ///< StyleVersion.ID (built entries)
    std::uint32_t format = 0; ///< the buffer format's version (built entries)

    /// The SHA-256 of the key's fields, which names the entry's file.
    [[nodiscard]] Sha256Digest digest() const noexcept;
};

/// The bytes of the entry's header, before the payload.
inline constexpr std::size_t cache_header_bytes = 64;

/// The on-disk cache. Safe to use from several threads at once.
class ExpandedCache {
public:
    /// Opens (and creates) the cache in `directory`: entries are listed and checked by their headers, files of another
    /// kind and leftovers of interrupted writes are deleted, and the oldest are evicted down to `budget` bytes.
    [[nodiscard]] static Result<std::unique_ptr<ExpandedCache>> open(const std::string& directory,
                                                                     std::uint64_t budget);

    ExpandedCache(const ExpandedCache&) = delete;
    ExpandedCache& operator=(const ExpandedCache&) = delete;
    ExpandedCache(ExpandedCache&&) = delete;
    ExpandedCache& operator=(ExpandedCache&&) = delete;
    ~ExpandedCache() = default;

    /// A cache hit: the entry's file mapped, its payload checked.
    struct Entry {
        std::unique_ptr<MappedSource> file;
        std::span<const std::byte> payload;
    };

    /// The entry of `key`, or nothing (a miss, or an entry that failed its checks and was deleted).
    [[nodiscard]] std::optional<Entry> get(const CacheKey& key);

    /// Stores `payload` under `key` (replacing an entry of the same key), then evicts the least recently used entries
    /// down to the budget. A payload larger than the budget is refused (Fault::too_big).
    [[nodiscard]] Result<void> put(const CacheKey& key, std::span<const std::byte> payload);

    /// Deletes the entry of `key`, if there is one.
    void remove(const CacheKey& key);

    /// Evicts the least recently used entries until the cache holds at most `budget` bytes (the budget stays).
    void evict_to(std::uint64_t budget);

    /// The bytes of every entry's file, and how many entries there are.
    [[nodiscard]] std::uint64_t bytes() const;
    [[nodiscard]] std::size_t entries() const;
    [[nodiscard]] std::uint64_t budget() const noexcept { return budget_; }
    [[nodiscard]] const std::string& directory() const noexcept { return directory_; }

    /// Checks an entry file's bytes (header, key and payload) and returns its payload; for the fuzzer and the tests.
    [[nodiscard]] static Result<std::span<const std::byte>> check(std::span<const std::byte> file,
                                                                  const Sha256Digest* key);

    /// An entry file's bytes for `key` and `payload`.
    [[nodiscard]] static std::vector<std::byte> encode(const CacheKey& key, std::span<const std::byte> payload);

private:
    ExpandedCache(std::string directory, std::uint64_t budget) noexcept
        : directory_(std::move(directory)), budget_(budget) {}

    struct Info {
        std::uint64_t bytes = 0;
        std::uint64_t used = 0;       ///< recency: larger is more recent
        std::uint64_t generation = 0; ///< which write made the file (0: found at open)
    };

    [[nodiscard]] std::string path_of(const Sha256Digest& digest) const;
    void scan();
    /// Evicts entries, oldest first, until `budget` holds: out of the index and off the disk (the lock held, so a file
    /// another thread renames into place is never deleted by mistake).
    void evict_locked(std::uint64_t budget, const std::string* keep);

    std::string directory_;
    std::uint64_t budget_;
    mutable std::mutex mutex_;
    std::unordered_map<std::string, Info> index_; ///< by file name
    std::uint64_t total_ = 0;
    std::uint64_t clock_ = 0;
    std::uint64_t writes_ = 0;
};

} // namespace atlas::tiles
