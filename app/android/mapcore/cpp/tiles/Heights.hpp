// A terrain tile's height raster (ENGINE.md part 6, terrain; rendertiles' Heights.kt is the specification): the one
// feature of a terrain tile's `heights` layer (LayerId::heights), in a terrain pack (Profile::terrain). The map's
// reader skips the layer as one it does not know (it is no layer of the map's schema, so a map pack's style version
// does not move with it); this reader takes its columns from the decoded tile.
//
// Where the samples lie: a raster of side n has n × n samples over its tile, the edges included. Sample (r, c), row r
// from the north and column c from the west, lies at the pyramid's point X = (x + c / (n − 1)) / 2^z,
// Y = (y + r / (n − 1)) / 2^z: longitude 360·X − 180, latitude Web Mercator's atan(sinh(π·(1 − 2Y))). So a level's
// samples lie on one lattice over the world: a tile's edge samples are its neighbours', and every second sample of a
// level is one of the level above. Beyond each edge lie `apron` more samples of the lattice, the neighbours' own. The
// raster is stored whole: w = n + 2·apron samples a row, w rows, from the apron's north-west corner.
//
// A sample's height is step · (base + v) metres (orthometric, its source's datum): `step` a double, `base` the lowest
// sample in steps, v the sample in steps above it, 0 to `max`.
//
// The layer's columns, in the format's column layout (a varint string index, a codec byte, a varint length, the bytes):
// `side` (varu), `apron` (varu; absent 0), `step` (f64), `base` (varz; absent 0), `max` (varu), `flags` (varu; absent
// 0), `sources` (strs) and `shares` (ids): the registry ids of the samples' sources and how many of the w × w samples
// each gave; and `samples` (codec 19, the layer's own): a varint w, a varint w, then w × w zig-zag varints, each sample
// minus its prediction from its west (a), north (b) and north-west (c) neighbours by LOCO-I's median edge detector:
// min(a, b) where c ≥ max(a, b), max(a, b) where c ≤ min(a, b), else a + b − c; outside the raster a is the sample
// above (0 for the first), b is a, c is b. A column this reader does not know is skipped.
//
// What read_heights checks: one feature; a side of 2^k + 1 (k from 1 to 8) and an apron of at most 32; a finite step
// above 0; the samples' width and rows equal to n + 2·apron; every sample within 0 to `max` as it is summed, the
// lowest exactly 0 and the highest exactly `max`; no byte left over in any column; as many shares as sources, their
// sum at most w × w.
#pragma once

#include "base/arena.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Error.hpp"
#include "tiles/Payload.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string_view>

namespace atlas::tiles {

/// The codec byte of the `samples` column, the heights layer's own.
inline constexpr std::uint8_t heights_codec_med = 19;
/// The most samples beyond each edge a raster may carry, and the most steps a sample may stand above the base.
inline constexpr std::uint32_t heights_max_apron = 32;
inline constexpr std::uint32_t heights_max_steps = 1U << 28U;
/// `flags`: at least one sample has no source and stands at 0 m; at least one was filled from its surroundings.
inline constexpr std::uint32_t heights_nodata = 1;
inline constexpr std::uint32_t heights_filled = 2;

/// Whether `side` is one a raster may have: 2^k + 1, k from 1 to 8.
[[nodiscard]] constexpr bool heights_side_allowed(std::uint64_t side) noexcept {
    return side >= 3 && side <= 257 && ((side - 1) & (side - 2)) == 0;
}

/// One of a raster's sources: its id in the registry (packs/terrain-sources.json) and the samples it gave.
struct HeightSource {
    std::string_view id;
    std::uint32_t samples = 0;
};

/// A tile's raster, decoded: views into the arena it was read into and the tile's own bytes.
struct Heights {
    TileKey tile;
    std::uint16_t side = 0;  ///< samples a side over the tile, edges included
    std::uint16_t apron = 0; ///< samples beyond each edge
    double step = 0.0;       ///< metres
    std::int64_t base = 0;   ///< the lowest sample, in steps
    std::uint32_t max = 0;   ///< the highest sample, in steps above the base
    std::uint32_t flags = 0;
    std::span<const HeightSource> sources;
    std::span<const std::int32_t> samples; ///< width() × width(), from the apron's north-west corner, steps above base

    [[nodiscard]] constexpr std::uint32_t width() const noexcept { return std::uint32_t{side} + (2U * apron); }

    /// The sample at row `r`, column `c` of the tile (each −apron to side − 1 + apron), in steps.
    [[nodiscard]] constexpr std::int64_t steps(std::int32_t r, std::int32_t c) const noexcept {
        const auto row = static_cast<std::size_t>(r + apron);
        const auto col = static_cast<std::size_t>(c + apron);
        return base + samples[(row * width()) + col];
    }
    /// Its height, metres.
    [[nodiscard]] constexpr double metres(std::int32_t r, std::int32_t c) const noexcept {
        return step * static_cast<double>(steps(r, c));
    }
    /// The raster's lowest and highest heights, metres (the apron's samples among them).
    [[nodiscard]] constexpr double lowest() const noexcept { return step * static_cast<double>(base); }
    [[nodiscard]] constexpr double highest() const noexcept { return step * static_cast<double>(base + max); }
};

/// Whether `tile` holds a heights layer.
[[nodiscard]] bool has_heights(const Tile& tile) noexcept;

/// The raster of `tile` (decoded by TileDecoder), its samples in `arena`; Fault::missing (Site::layers) for a tile
/// without a heights layer, else every check above, an error naming the column (`heights.<name>`).
[[nodiscard]] Result<Heights> read_heights(const Tile& tile, Arena& arena);

/// The raster's hash, the same from the packer's reader (`packer heights-check`): the SHA-256 of its side and apron
/// (u16 each), its step's bits (u64), its base (i64) and its samples (i32 each, steps above the base), little-endian.
[[nodiscard]] Sha256Digest heights_hash(const Heights& heights) noexcept;

/// A raster's part over one of its tile's descendants (or the tile itself): the samples of the coarser lattice in the
/// finer tile's box, edges included, which is what a tile without a raster of its own level reads. `side` samples a
/// side, `side` − 1 the raster's own (side − 1) / 2^d, d the levels between them; `apron` samples of the raster lie
/// beyond each of its edges (inside the raster's tile, or in its apron).
struct HeightsCut {
    const Heights* raster = nullptr;
    TileKey tile;
    std::uint16_t side = 0;
    std::uint16_t row = 0; ///< the raster's row and column of the cut's sample (0, 0)
    std::uint16_t column = 0;

    [[nodiscard]] constexpr std::uint16_t apron() const noexcept { return raster->apron; }
    [[nodiscard]] constexpr std::int64_t steps(std::int32_t r, std::int32_t c) const noexcept {
        return raster->steps(r + row, c + column);
    }
    [[nodiscard]] constexpr double metres(std::int32_t r, std::int32_t c) const noexcept {
        return raster->metres(r + row, c + column);
    }
};

/// `raster` cut to `tile`'s box: nothing where `tile` is not `raster`'s tile or a descendant of it, or lies so deep
/// that fewer than two of the raster's samples a side fall on its box.
[[nodiscard]] std::optional<HeightsCut> cut_heights(const Heights& raster, TileKey tile) noexcept;

} // namespace atlas::tiles
