// The world a kept country keeps (tiles/Kept.hpp).
#include "tiles/Kept.hpp"

#include <memory>
#include <string>

namespace atlas::tiles {

namespace {

/// The pack `p` opened from its head in `cache` alone (no fetch: Store::absent), or null where the cache holds no head
/// that opens it.
[[nodiscard]] std::shared_ptr<const StorePack> opened(ExpandedCache& cache, const KeptPack& p,
                                                      const Style* world_style) {
    const CacheKey head{.kind = CacheKind::pack_head, .source = p.identity};
    if (!cache.holds(head)) return nullptr;
    Store store(&cache);
    store.set_world_style(world_style);
    const auto added =
        store.add({.id = "kept", .kind = p.kind, .length = p.length, .fetch = Store::absent(), .identity = p.identity});
    if (!added) return nullptr;
    for (std::shared_ptr<const StorePack>& pack : store.packs()) {
        if (pack->id == "kept") return std::move(pack);
    }
    return nullptr;
}

} // namespace

Result<Kept> keep_packs(ExpandedCache& cache, std::span<const KeptPack> packs, const Style* world_style) {
    Kept out;
    std::vector<CacheKey> keys;
    for (std::uint32_t i = 0; i < packs.size(); ++i) {
        const KeptPack& p = packs[i];
        keys.push_back({.kind = CacheKind::pack_head, .source = p.identity});
        const std::shared_ptr<const StorePack> pack = opened(cache, p, world_style);
        if (!pack) {
            out.heads.push_back(i);
            continue;
        }
        for (const TileKey& tile : p.tiles) {
            const ChunkRef* c = pack->pack.chunks().find(tile);
            if (c == nullptr) {
                ++out.unknown;
                continue;
            }
            const CacheKey key{.kind = CacheKind::chunk, .source = c->sha256};
            keys.push_back(key);
            if (cache.holds(key)) {
                ++out.held;
                out.held_bytes += c->stored;
            } else {
                out.chunks.push_back({.pack = i, .tile = tile, .offset = c->offset, .length = c->stored});
            }
        }
    }
    if (auto pinned = cache.set_pinned(keys); !pinned) return std::unexpected(pinned.error());
    return out;
}

Result<KeptChunks> keep_range(ExpandedCache& cache, const KeptPack& pack, const PackBytes& range,
                              const Style* world_style) {
    const std::shared_ptr<const StorePack> open = opened(cache, pack, world_style);
    if (!open) return std::unexpected(error(Fault::missing, Site::cache));
    Zstd zstd;
    return Store::keep(cache, *open, range, zstd);
}

} // namespace atlas::tiles
