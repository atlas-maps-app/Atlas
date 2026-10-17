// The chunk table (the `tiles` section), each chunk's reach (the `reach` section) and the chunks themselves
// (plan-vector D2, Appendix A; D1's container; rendertiles' Format and Reach).
//
// The table: a varint chunk count, then per chunk its zoom (one byte), column and row, stored and raw lengths and
// feature count (varints), and the 32-byte SHA-256 of its decompressed bytes. Chunks lie in the `chunks` section in the
// table's order, so each offset follows from the stored lengths before it; they are in level order (finest first) and
// along a Hilbert curve within a level. In a render pack (format 3) each chunk is one zstd frame of one tile, at most
// max_tile_bytes decompressed; in an element pack (format 2) each is raw DEFLATE of elements, which only Kotlin reads.
//
// `reach` (render packs, stored), little-endian: u32 "ATRR", u16 payload version, u16 the entry size e (at least 20; a
// reader reads the fields it knows and skips the rest), u32 the entry count (the table's), then per chunk in the
// table's order i32 south, west, north, east on the grid and i32 the top in centimetres above the street: the union of
// the tile's cells' boxes and their highest top, the bounds of everything its features draw. A feature is stored whole
// under its reference point, so a tile can reach past its own bounds: a frame asks for every chunk whose reach meets
// its view (with the margin the renders cull by), not only the tiles under it, and pads that for what the boxes leave
// out (shadows, cast beyond a feature by its top; reflections of what stands within 16 m of a quay edge).
#pragma once

#include "tiles/Checksums.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Error.hpp"
#include "tiles/Schema.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

namespace atlas::tiles {

/// The finest zoom a pack may use (render/data's PackTiles.MAX_ZOOM): columns and rows then fit 24 bits.
inline constexpr std::uint8_t max_zoom = 24;

/// The largest decompressed chunk of an element pack the core lists (it never decompresses one); a render pack's chunk
/// is a tile, at most max_tile_bytes.
inline constexpr std::uint32_t max_element_chunk_raw = 64U << 20U;

/// The first four bytes of `reach` ("ATRR", little-endian), its header's size and the size of the entries it writes.
inline constexpr std::uint32_t reach_magic = 0x5252'5441U;
inline constexpr std::size_t reach_header_bytes = 12;
inline constexpr std::size_t reach_entry_bytes = 20;

/// A box on the grid (degrees × 1e7, south and west to north and east, edges included).
struct GridBox {
    std::int32_t south = 0;
    std::int32_t west = 0;
    std::int32_t north = 0;
    std::int32_t east = 0;

    friend constexpr bool operator==(const GridBox&, const GridBox&) = default;
};

/// How far a chunk's features reach: the grid box of everything they draw (road ribbons and pavements, rotors, booms,
/// crowns, chimneys) and the highest point they draw, in centimetres above the street.
struct Reach {
    GridBox box;
    std::int32_t top_cm = 0;

    /// Whether the box meets `view` (touching counts).
    [[nodiscard]] constexpr bool meets(const GridBox& view) const noexcept {
        return box.south <= view.north && view.south <= box.north && box.west <= view.east && view.west <= box.east;
    }
    friend constexpr bool operator==(const Reach&, const Reach&) = default;
};

/// The largest decompressed `style` or `rhead` section the core accepts.
inline constexpr std::uint64_t max_section_raw = 16U << 20U;

/// A tile of the pyramid: zoom, column, row (0 at the north).
struct TileKey {
    std::uint8_t z = 0;
    std::uint32_t x = 0;
    std::uint32_t y = 0;

    constexpr TileKey() noexcept = default;
    constexpr TileKey(std::uint8_t zoom, std::uint32_t column, std::uint32_t row) noexcept
        : z(zoom), x(column), y(row) {}

    /// Zoom, column and row in one number, ordered by zoom, then column, then row.
    [[nodiscard]] constexpr std::uint64_t packed() const noexcept {
        return (std::uint64_t{z} << 48U) | (std::uint64_t{x} << 24U) | y;
    }
    [[nodiscard]] static constexpr TileKey unpack(std::uint64_t key) noexcept {
        return {static_cast<std::uint8_t>(key >> 48U), static_cast<std::uint32_t>((key >> 24U) & 0xFF'FFFFU),
                static_cast<std::uint32_t>(key & 0xFF'FFFFU)};
    }
    friend constexpr bool operator==(const TileKey&, const TileKey&) = default;
};

/// One entry of the chunk table, with its place in the pack.
struct ChunkRef {
    TileKey tile;
    std::uint64_t offset = 0; ///< from the pack's first byte
    std::uint32_t stored = 0;
    std::uint32_t raw = 0;
    std::uint32_t count = 0; ///< the features (render tiles) or elements (element packs) it holds
    Sha256Digest sha256{};
    Reach reach; ///< a render pack's (`reach`); an element pack's chunks have none
};

/// A pack's chunks, in the table's order, with a sorted index for finding a tile.
class ChunkTable {
public:
    [[nodiscard]] std::span<const ChunkRef> chunks() const noexcept { return chunks_; }

    /// The chunk of tile z/x/y, or null.
    [[nodiscard]] const ChunkRef* find(TileKey tile) const noexcept;

    /// The zooms the table holds, in its order (finest first).
    [[nodiscard]] std::span<const std::uint8_t> zooms() const noexcept { return zooms_; }

    /// The chunks of zoom `z`, in the table's order (each level's chunks lie together); empty for a zoom it lacks.
    [[nodiscard]] std::span<const ChunkRef> level(std::uint8_t z) const noexcept;

private:
    friend Result<ChunkTable> read_chunk_table(std::span<const std::byte>, const Section&, std::uint8_t,
                                               std::span<const std::uint8_t>);
    friend Result<void> read_reach(std::span<const std::byte>, ChunkTable&);

    std::vector<ChunkRef> chunks_;
    std::vector<std::uint64_t> keys_;  ///< sorted packed tiles
    std::vector<std::uint32_t> order_; ///< the chunk of each sorted key
    std::vector<std::uint8_t> zooms_;
    std::vector<std::uint32_t> level_first_; ///< where each of zooms_ begins in chunks_, then the chunk count
};

/// Reads the `tiles` section's bytes against the `chunks` section: every chunk inside it, the stored lengths filling it
/// (and in a render pack the raw lengths adding up to its raw length), every tile on its zoom's grid, each tile once,
/// the zooms in level order. `levels` are `rhead`'s for a render pack (each chunk's zoom must be one of them, in their
/// order); for an element pack, empty (the zooms must then only go from fine to coarse).
[[nodiscard]] Result<ChunkTable> read_chunk_table(std::span<const std::byte> bytes, const Section& chunks,
                                                  std::uint8_t format, std::span<const std::uint8_t> levels);

/// Reads the `reach` section's bytes into `table`'s chunks: its magic and payload version, an entry size of at least 20
/// bytes, one entry per chunk filling the section exactly, every box south to north and west to east.
[[nodiscard]] Result<void> read_reach(std::span<const std::byte> bytes, ChunkTable& table);

/// Decompresses zstd frames; one per thread, reused (its context holds about 160 KB).
class Zstd {
public:
    Zstd();
    ~Zstd();
    Zstd(const Zstd&) = delete;
    Zstd& operator=(const Zstd&) = delete;
    Zstd(Zstd&&) noexcept;
    Zstd& operator=(Zstd&&) noexcept;

    /// Decompresses `stored`, which must be exactly one frame, into `out`, which it must fill exactly.
    [[nodiscard]] Result<void> frame(std::span<const std::byte> stored, std::span<std::byte> out, Site site,
                                     std::uint64_t detail = 0) noexcept;

private:
    struct Context;
    std::unique_ptr<Context> context_;
};

/// A chunk's decompressed bytes, checked against the table's SHA-256 (a render pack's chunks only).
[[nodiscard]] Result<void> unpack_chunk(Zstd& zstd, const ChunkRef& chunk, std::span<const std::byte> stored,
                                        std::uint8_t format, std::span<std::byte> out, bool verify = true) noexcept;

} // namespace atlas::tiles
