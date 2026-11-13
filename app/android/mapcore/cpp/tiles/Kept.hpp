// The world a kept country keeps (ENGINE.md step 11): the world's packs' heads and the chunks of the tiles a view of
// the country draws from 18 km out to the globe, as the release's world-keep.json lists them for it, held in the
// world's cache out of its LRU (ExpandedCache's pins) while the country is kept, so a phone that went offline still
// draws the country out to the globe. The app reads the list, fetches what is missing and hands it over; the core says
// which keys those are: a head's is its pack's identity, a chunk's its SHA-256, which only its pack's head names.
#pragma once

#include "tiles/Chunks.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Store.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::tiles {

struct Style; // tiles/Style.hpp

/// A world pack of the keep: its kind (world_mid: a fine or a coarse pack of the mid-zoom base), its length and its
/// SHA-256 as the app's world.idx has them, and its tiles to keep.
struct KeptPack {
    PackKind kind = PackKind::world_mid;
    std::uint64_t length = 0;
    Sha256Digest identity{};
    std::vector<TileKey> tiles;
};

/// A chunk the keep lacks: its pack's place among those asked about, its tile, and its bytes in the pack.
struct KeptChunk {
    std::uint32_t pack = 0;
    TileKey tile;
    std::uint64_t offset = 0;
    std::uint64_t length = 0;
};

/// Where the keep stands.
struct Kept {
    /// The packs whose heads the cache lacks (their places among those asked about): their chunks are not known yet.
    std::vector<std::uint32_t> heads;
    /// The chunks it lacks, of the packs whose heads it holds, in the packs' order and each pack's tiles' order.
    std::vector<KeptChunk> chunks;
    /// The tiles asked for that their pack does not hold (another world's list): nothing to keep.
    std::uint32_t unknown = 0;
    /// The chunks it holds, and their stored bytes.
    std::uint32_t held = 0;
    std::uint64_t held_bytes = 0;
};

/// Pins in `cache` everything `packs` keep, in place of what was pinned before: every pack's head, and each tile's
/// chunk of the packs whose heads the cache holds (a head that does not open its pack is dropped, and counts as
/// lacking). Says what is lacking; the app fetches it, hands it over (Store::keep_head, keep_range) and asks again,
/// which pins the chunks of the heads that came. No packs: nothing is pinned. A world pack opens in `world_style` where
/// one is given, as the map's store opens it. Fails where the pins cannot be written (the cache's set_pinned).
[[nodiscard]] Result<Kept> keep_packs(ExpandedCache& cache, std::span<const KeptPack> packs,
                                      const Style* world_style = nullptr);

/// A range of the bytes of the world pack `pack` (its tiles are not read) the app fetched: the chunks lying whole in
/// it, checked and kept in `cache` (Store::keep), the pack opened from its head there. Fails where the cache holds no
/// head that opens it.
[[nodiscard]] Result<KeptChunks> keep_range(ExpandedCache& cache, const KeptPack& pack, const PackBytes& range,
                                            const Style* world_style = nullptr);

} // namespace atlas::tiles
