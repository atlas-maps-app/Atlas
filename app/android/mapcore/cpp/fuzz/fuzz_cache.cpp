// Fuzzes the expanded-tile cache (tiles/ExpandedCache.hpp). The input's first byte picks what:
//  0  an entry file's bytes, checked: never a read past them, and an accepted entry's payload is the bytes after its
//     header;
//  1  a sequence of puts, gets, removes and evictions on a real cache in a folder of its own, held to a model of the
//     same least-recently-used cache: every hit, miss, payload, byte count and entry count must be the model's;
//  2  bytes written as an entry's file, then the cache opened over it and asked for it: a damaged file is a miss and
//     is deleted, a good one gives its payload.
// Seeds: entries of the bundled packs' chunks and short operation sequences (fuzz/corpus/fuzz_cache).
#include "base/assert.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/ExpandedCache.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <map>
#include <span>
#include <string>
#include <system_error>
#include <unistd.h>
#include <vector>

namespace {

using namespace atlas::tiles;
namespace fs = std::filesystem;

#if !defined(ATLAS_TEST_SCRATCH)
#error "ATLAS_TEST_SCRATCH names the build folder's scratch folder (CMakeLists.txt)"
#endif

/// This process's folder under the build folder's scratch, removed when the process exits (a crash leaves it there,
/// beside the build, never in the system's temp folder).
class Folder {
public:
    Folder() : path_(fs::path(ATLAS_TEST_SCRATCH) / std::format("fuzz-cache-{}", ::getpid())) {}
    Folder(const Folder&) = delete;
    Folder& operator=(const Folder&) = delete;
    Folder(Folder&&) = delete;
    Folder& operator=(Folder&&) = delete;
    ~Folder() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] const fs::path& path() const noexcept { return path_; }

private:
    fs::path path_;
};

/// A folder for this process's caches, emptied before each input.
const fs::path& folder() {
    static const Folder owned;
    return owned.path();
}

void empty_folder() {
    std::error_code ec;
    fs::remove_all(folder(), ec);
    fs::create_directories(folder(), ec);
}

CacheKey key_of(unsigned k) {
    CacheKey key;
    key.kind = static_cast<CacheKind>(1 + (k % 3));
    key.source = Sha256::of(std::as_bytes(std::span(&k, 1)));
    key.format = k / 3;
    return key;
}

std::vector<std::byte> payload_of(unsigned k, std::size_t n) {
    std::vector<std::byte> p(n);
    for (std::size_t i = 0; i < n; ++i) p[i] = static_cast<std::byte>((std::size_t{k} * 31U) + i);
    return p;
}

/// The cache as a model: entries with their recency, evicted oldest first past the budget.
struct Model {
    struct Entry {
        std::vector<std::byte> payload;
        std::uint64_t used = 0;
    };
    std::map<unsigned, Entry> entries;
    std::uint64_t clock = 0;
    std::uint64_t total = 0;

    void evict(std::uint64_t budget, const unsigned* keep) {
        while (total > budget) {
            auto oldest = entries.end();
            for (auto it = entries.begin(); it != entries.end(); ++it) {
                if (keep != nullptr && it->first == *keep) continue;
                if (oldest == entries.end() || it->second.used < oldest->second.used) oldest = it;
            }
            if (oldest == entries.end()) return;
            total -= cache_header_bytes + oldest->second.payload.size();
            entries.erase(oldest);
        }
    }
};

void operations(std::span<const std::uint8_t> ops) {
    empty_folder();
    constexpr std::uint64_t budget = 1200;
    auto opened = ExpandedCache::open(folder().string(), budget);
    ATLAS_ASSERT(opened.has_value(), "the cache opens in an empty folder");
    ExpandedCache& cache = **opened;
    Model model;
    for (std::size_t i = 0; i < ops.size(); ++i) {
        const std::uint8_t op = ops[i];
        const unsigned k = (op >> 2U) & 7U;
        switch (op & 3U) {
        case 0: { // put
            const std::size_t n = i + 1 < ops.size() ? std::size_t{ops[++i]} * 4 : 0;
            const auto payload = payload_of(k, n);
            const bool fits = cache_header_bytes + n <= budget;
            const auto put = cache.put(key_of(k), payload);
            ATLAS_ASSERT(put.has_value() == fits, "a put fits the budget or is refused");
            if (!fits) break;
            if (const auto it = model.entries.find(k); it != model.entries.end()) {
                model.total -= cache_header_bytes + it->second.payload.size();
            }
            model.entries[k] = {.payload = payload, .used = ++model.clock};
            model.total += cache_header_bytes + n;
            model.evict(budget, &k);
            break;
        }
        case 1: { // get
            const auto got = cache.get(key_of(k));
            const auto it = model.entries.find(k);
            ATLAS_ASSERT(got.has_value() == (it != model.entries.end()), "a hit where the model holds the key");
            if (got) {
                ATLAS_ASSERT(std::ranges::equal(got->payload, it->second.payload), "the payload put");
                it->second.used = ++model.clock;
            }
            break;
        }
        case 2: // remove
            cache.remove(key_of(k));
            if (const auto it = model.entries.find(k); it != model.entries.end()) {
                model.total -= cache_header_bytes + it->second.payload.size();
                model.entries.erase(it);
            }
            break;
        default: { // evict to a smaller budget
            const std::uint64_t to = i + 1 < ops.size() ? std::uint64_t{ops[++i]} * 8 : 0;
            cache.evict_to(to);
            model.evict(to, nullptr);
        }
        }
        ATLAS_ASSERT(cache.bytes() == model.total && cache.entries() == model.entries.size(), "the model's sizes");
    }
}

void file(std::span<const std::byte> bytes) {
    empty_folder();
    const CacheKey key = key_of(5);
    const Sha256Digest digest = key.digest();
    const fs::path path = folder() / std::format("{}.x", hex(digest));
    {
        std::ofstream out(path, std::ios::binary);
        out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
    }
    const auto expected = ExpandedCache::check(bytes, &digest);
    auto opened = ExpandedCache::open(folder().string(), 1U << 20U);
    ATLAS_ASSERT(opened.has_value(), "the cache opens");
    const auto got = (*opened)->get(key);
    ATLAS_ASSERT(got.has_value() == expected.has_value(), "a hit exactly when the file checks");
    if (got) {
        ATLAS_ASSERT(std::ranges::equal(got->payload, *expected), "the file's payload");
    } else {
        ATLAS_ASSERT(!fs::exists(path), "a damaged entry is deleted");
    }
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    if (size == 0) return 0;
    const std::span<const std::byte> rest(reinterpret_cast<const std::byte*>(data) + 1, size - 1); // NOLINT
    switch (data[0] % 3) {
    case 0: {
        const Sha256Digest digest = key_of(1).digest();
        if (const auto payload = ExpandedCache::check(rest, nullptr)) {
            ATLAS_ASSERT(payload->size() + cache_header_bytes == rest.size(), "the payload follows the header");
            const auto keyed = ExpandedCache::check(rest, &digest);
            ATLAS_ASSERT(!keyed || keyed->size() == payload->size(), "the same payload under its key");
        }
        break;
    }
    case 1: operations(std::span(data + 1, size - 1)); break;
    default: file(rest);
    }
    return 0;
}
