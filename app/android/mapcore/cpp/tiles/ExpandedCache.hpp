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
//
// Pinned entries (ENGINE.md step 11: the world's tiles a kept country draws offline) are kept whatever their age: the
// budget counts the others alone, and no eviction takes a pinned one. The pins are a set of keys, replaced as a whole
// (set_pinned: every kept country's heads and chunks), whether or not their entries are there yet: an entry written
// under a pinned key is pinned from its first byte. The set is kept in the file `pinned` beside the entries (their
// file names, a line each, written beside and renamed), so it holds across restarts.
#pragma once

#include "tiles/Checksums.hpp"
#include "tiles/Error.hpp"
#include "tiles/Source.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
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
    /// down to the budget. A payload larger than the budget is refused (Fault::too_big), but under a pinned key.
    [[nodiscard]] Result<void> put(const CacheKey& key, std::span<const std::byte> payload);

    /// The pinned keys from now on, replacing the set there was: their entries are never evicted and the budget does
    /// not count them; an entry no longer pinned is the others' again (the least recently used go down to the budget).
    /// Written to the cache's `pinned` file first: where that fails (Fault::io) the set stays as it was.
    [[nodiscard]] Result<void> set_pinned(std::span<const CacheKey> keys);
    /// Whether `key` is pinned (its entry there or not).
    [[nodiscard]] bool pinned(const CacheKey& key) const;
    /// How many keys are pinned, and the bytes of their entries' files that are there.
    [[nodiscard]] std::size_t pins() const;
    [[nodiscard]] std::uint64_t pinned_bytes() const;

    /// Deletes the entry of `key`, if there is one.
    void remove(const CacheKey& key);

    /// Evicts the least recently used entries until the cache holds at most `budget` bytes of entries that are not
    /// pinned (the budget stays).
    void evict_to(std::uint64_t budget);

    /// The budget from now on (the world's, which a setting raises), the least recently used evicted down to it.
    void set_budget(std::uint64_t budget);

    /// Whether the cache holds an entry of `key`: its index alone, the entry not read (a damaged one is a hit here and
    /// a miss in get).
    [[nodiscard]] bool holds(const CacheKey& key) const;

    /// How many entries were written since the cache opened: a reader that found an entry missing asks again once it
    /// changes.
    [[nodiscard]] std::uint64_t writes() const;

    /// The bytes of every entry's file, the pinned ones' too, and how many entries there are.
    [[nodiscard]] std::uint64_t bytes() const;
    [[nodiscard]] std::size_t entries() const;
    [[nodiscard]] std::uint64_t budget() const noexcept { return budget_.load(std::memory_order_relaxed); }
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
    /// Entry `it` out of the index, its bytes off the totals (the lock held); its file is the caller's to delete.
    void forget_locked(std::unordered_map<std::string, Info>::iterator it);
    /// Reads the `pinned` file (at open, before the entries are listed).
    void read_pins();

    std::string directory_;
    std::atomic<std::uint64_t> budget_;
    mutable std::mutex mutex_;
    std::unordered_map<std::string, Info> index_; ///< by file name
    std::uint64_t total_ = 0;
    std::unordered_set<std::string> pinned_; ///< by file name, their entries there or not
    std::uint64_t pinned_total_ = 0;         ///< the bytes of the pinned entries that are there
    std::uint64_t clock_ = 0;
    std::atomic<std::uint64_t> writes_{0}; ///< read without the lock (writes)
};

} // namespace atlas::tiles
