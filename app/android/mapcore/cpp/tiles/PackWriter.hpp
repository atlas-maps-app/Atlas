// The render pack's writer (ENGINE.md step 3: built tiles become local packs; rendertiles' Format3Writer.write): tiles
// the writer encoded (tiles/TileWriter.hpp), with a pack's `rhead` and `style`, become a pack in D1's container that
// Pack::open reads like a downloaded one (tiles/Directory.hpp, tiles/Chunks.hpp):
//   - the 20-byte header, the manifest (the caller's JSON: for Kotlin and the tools, the core never reads it), the
//     directory, then `tiles`, `reach`, `chunks`, `rhead` and `style`, as the packer orders them;
//   - the chunks in level order (rhead's, finest first) and along the Hilbert curve within a level (PackTiles.hilbert),
//     each one zstd frame, every chunk's SHA-256 over its tile's bytes, each section's CRC-32 over its stored bytes.
// The core vendors zstd's decompression alone, so the frames hold the tiles in raw blocks: a local pack is as large as
// its tiles (the canal ring's 27 are 4.2 MB), and reading one is a copy. Compressing them waits for zstd's compressor
// in third_party; the tiles' own bytes, the packer's, do not change with it.
#pragma once

#include "tiles/Chunks.hpp"
#include "tiles/Error.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// One tile to pack: its place, its bytes (encode_tile's), its features, and how far they reach (reach_of).
struct PackTile {
    TileKey key;
    std::vector<std::byte> raw;
    std::uint32_t count = 0;
    Reach reach;
};

/// What a render pack holds besides its tiles.
struct PackParts {
    std::span<const std::uint8_t> levels; ///< rhead's levels, finest first: the chunks' order
    std::string_view manifest;            ///< the JSON manifest, as it is
    std::span<const std::byte> head;      ///< the `rhead` section
    std::span<const std::byte> style;     ///< the `style` section's tables, before compression
};

/// The pack of `tiles` (format 3), or why it cannot be one: a tile twice, a tile of a level `rhead` lacks, a tile past
/// max_tile_bytes.
[[nodiscard]] Result<std::vector<std::byte>> write_pack(std::span<const PackTile> tiles, const PackParts& parts);

/// One zstd frame holding `raw` in raw blocks, its content size given.
[[nodiscard]] std::vector<std::byte> zstd_raw_frame(std::span<const std::byte> raw);

/// Tile (x, y)'s place along the Hilbert curve over zoom z's grid (render/data's PackTiles.hilbert).
[[nodiscard]] std::uint64_t hilbert(std::uint8_t z, std::uint32_t x, std::uint32_t y) noexcept;

} // namespace atlas::tiles
