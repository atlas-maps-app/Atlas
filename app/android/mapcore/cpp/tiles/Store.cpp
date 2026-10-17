#include "tiles/Store.hpp"

#include "base/log.hpp"
#include "tiles/Bytes.hpp"

#include <algorithm>
#include <array>
#include <utility>

namespace atlas::tiles {
namespace {

/// A remote pack's bytes the store already has (its cached head), and while it opens, a record of what it reads.
struct RemoteState {
    struct Range {
        std::uint64_t offset = 0;
        std::vector<std::byte> bytes;
    };
    std::mutex mutex;
    std::vector<Range> ranges;
    bool recording = false;
};

/// The cache entry of a remote pack's head: a varint range count, then per range its offset, length and bytes.
[[nodiscard]] std::vector<std::byte> encode_ranges(const std::vector<RemoteState::Range>& ranges) {
    std::vector<std::byte> out;
    auto varu = [&out](std::uint64_t v) {
        while (v >= 0x80U) {
            out.push_back(static_cast<std::byte>((v & 0x7FU) | 0x80U));
            v >>= 7U;
        }
        out.push_back(static_cast<std::byte>(v));
    };
    varu(ranges.size());
    for (const auto& r : ranges) {
        varu(r.offset);
        varu(r.bytes.size());
        out.insert(out.end(), r.bytes.begin(), r.bytes.end());
    }
    return out;
}

[[nodiscard]] bool decode_ranges(std::span<const std::byte> payload, std::uint64_t pack_size,
                                 std::vector<RemoteState::Range>& out) {
    Reader r(payload);
    const std::uint32_t n = r.count();
    if (!r.ok() || n > r.remaining()) return false;
    for (std::uint32_t i = 0; i < n; ++i) {
        const std::uint64_t offset = r.varu();
        const std::uint64_t length = r.varu();
        if (!r.ok() || length > r.remaining() || offset > pack_size || length > pack_size - offset) return false;
        const std::span<const std::byte> bytes = r.bytes(static_cast<std::size_t>(length));
        out.push_back({offset, std::vector<std::byte>(bytes.begin(), bytes.end())});
    }
    return r.done();
}

} // namespace

Result<Pack> Store::open_remote(const PackRef& ref) {
    auto state = std::make_shared<RemoteState>();
    const CacheKey head_key{.kind = CacheKind::pack_head, .source = ref.identity};
    bool cached = false;
    if (cache_ != nullptr) {
        if (auto entry = cache_->get(head_key)) {
            cached = decode_ranges(entry->payload, ref.length, state->ranges);
            if (!cached) {
                state->ranges.clear();
                cache_->remove(head_key);
            }
        }
    }
    // Reads come from the cached head where it covers them, else from the app's fetch (recorded while the pack opens).
    RangeSource::Fetch fetch = [state, network = ref.fetch](std::uint64_t offset,
                                                            std::span<std::byte> out) -> Result<void> {
        {
            const std::lock_guard lock(state->mutex);
            for (const auto& r : state->ranges) {
                if (offset >= r.offset && out.size() <= r.bytes.size() &&
                    offset - r.offset <= r.bytes.size() - out.size()) {
                    const auto from = r.bytes.begin() + static_cast<std::ptrdiff_t>(offset - r.offset);
                    std::copy(from, from + static_cast<std::ptrdiff_t>(out.size()), out.begin());
                    return {};
                }
            }
        }
        if (!network) return std::unexpected(error(Fault::io, Site::file, offset));
        if (auto done = network(offset, out); !done) return done;
        const std::lock_guard lock(state->mutex);
        if (state->recording) state->ranges.push_back({offset, std::vector<std::byte>(out.begin(), out.end())});
        return {};
    };
    {
        const std::lock_guard lock(state->mutex);
        state->recording = !cached;
    }
    auto pack = Pack::open(std::make_unique<RangeSource>(ref.length, std::move(fetch)));
    if (!pack && cached) {
        // a cached head that no longer opens (another pack under the same identity, or damage): fetch it afresh
        cache_->remove(head_key);
        return open_remote(ref);
    }
    if (!pack) return std::unexpected(pack.error());
    std::vector<std::byte> payload;
    {
        const std::lock_guard lock(state->mutex);
        if (state->recording) payload = encode_ranges(state->ranges);
        state->recording = false;
    }
    if (cache_ != nullptr && !cached) {
        // a head the cache cannot keep (a full disk) only costs the next start its fetch
        if (const auto kept = cache_->put(head_key, payload); !kept)
            log::warn("tiles: a remote pack's head was not cached: {}", describe(kept.error()));
    }
    return pack;
}

Result<void> Store::add(const PackRef& ref) {
    Result<Pack> pack = std::unexpected(error(Fault::missing, Site::file));
    bool remote = false;
    if (ref.fetch) {
        remote = true;
        pack = open_remote(ref);
    } else if (ref.fd >= 0) {
        auto source = MappedSource::open(ref.fd, ref.offset, ref.length);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source));
    } else if (!ref.path.empty()) {
        auto source = MappedSource::open(ref.path);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source));
    }
    if (!pack) return std::unexpected(pack.error());
    if (!pack->is_render()) return std::unexpected(error(Fault::unsupported, Site::header, pack->format()));
    auto entry = std::make_shared<StorePack>(StorePack{
        .id = ref.id, .kind = ref.kind, .identity = ref.identity, .remote = remote, .pack = std::move(*pack)});
    const std::lock_guard lock(mutex_);
    std::erase_if(packs_, [&](const auto& p) { return p->id == ref.id; });
    const auto at = std::ranges::find_if(packs_, [&](const auto& p) { return p->kind > ref.kind; });
    packs_.insert(at, std::move(entry));
    return {};
}

bool Store::remove(std::string_view id) {
    const std::lock_guard lock(mutex_);
    return std::erase_if(packs_, [&](const auto& p) { return p->id == id; }) > 0;
}

std::vector<std::shared_ptr<const StorePack>> Store::packs() const {
    const std::lock_guard lock(mutex_);
    return packs_;
}

std::vector<StoreHit> Store::find(TileKey tile) const {
    std::vector<StoreHit> hits;
    for (const auto& p : packs()) {
        if (const ChunkRef* c = p->pack.chunks().find(tile)) hits.push_back({p, c});
    }
    return hits;
}

std::vector<StoreHit> Store::reaching(std::uint8_t z, const GridBox& view) const {
    std::vector<StoreHit> hits;
    for (const auto& p : packs()) {
        for (const ChunkRef& c : p->pack.chunks().level(z)) {
            if (c.reach.meets(view)) hits.push_back({p, &c});
        }
    }
    return hits;
}

Result<Tile> Store::decode(const StoreHit& hit, Zstd& zstd, TileDecoder& decoder, Arena& arena,
                           std::vector<std::byte>& raw) const {
    if (auto done = load(hit, zstd, raw); !done) return std::unexpected(done.error());
    auto tile = decoder.decode(raw, arena);
    if (!tile) return tile;
    if (auto checked = check_against(*tile, *hit.chunk); !checked) return std::unexpected(checked.error());
    return tile;
}

Result<void> Store::load(const StoreHit& hit, Zstd& zstd, std::vector<std::byte>& raw) const {
    const StorePack& p = *hit.pack;
    const ChunkRef& c = *hit.chunk;
    std::vector<std::byte> scratch;
    if (!p.remote) return p.pack.unpack(c, zstd, raw, scratch);
    const CacheKey key{.kind = CacheKind::chunk, .source = c.sha256};
    if (cache_ != nullptr) {
        if (auto entry = cache_->get(key)) {
            raw.resize(c.raw);
            if (unpack_chunk(zstd, c, entry->payload, p.pack.format(), raw)) return {};
            cache_->remove(key); // a stale or damaged copy: fetch it again
        }
    }
    auto stored = p.pack.stored(c, scratch);
    if (!stored) return std::unexpected(stored.error());
    raw.resize(c.raw);
    if (auto done = unpack_chunk(zstd, c, *stored, p.pack.format(), raw); !done) return done;
    if (cache_ != nullptr) {
        if (const auto kept = cache_->put(key, *stored); !kept)
            log::warn("tiles: a fetched chunk was not cached: {}", describe(kept.error()));
    }
    return {};
}

} // namespace atlas::tiles
