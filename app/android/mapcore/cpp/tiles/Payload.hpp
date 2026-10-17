// A render tile, decoded (plan-vector Appendix A; rendertiles' Format.kt): one decompressed chunk becomes tables, never
// per-feature objects (T7). Each layer is a structure of arrays: per column of its schema one typed array per feature
// (and per vertex for geometry), allocated in the caller's arena, its strings viewing the chunk's own bytes.
//
// A tile's bytes (at most max_tile_bytes):
//    0  u32 "ATRT", u16 payload version, u8 level (at most max_zoom), u8 cells per side n (cells_per_side(level)),
//       u32 x, u32 y (0 at the north), each below 2^level
//   16  i32 south, west, north, east: the tile's bounds on the grid, checked to be Grid.tileBox's (TileBox.hpp)
//   32  u32 string table offset and length, u32 cell table offset and length
//   48  u16 layer count L, u16 zero
//   52  L × 16 bytes: u8 layer id, u8 zero, u16 column count, u32 feature count, u32 offset, u32 length
// Every block lies inside the tile. The string table: a varint count, then each string (varint length, UTF-8). The cell
// table: for each of the n × n cells, row by row from the north-west, the varint count of the features whose reference
// point lies in it (every layer); when not zero, the grid box of what they draw (four zig-zag varints relative to the
// tile's south and west), the highest point they draw in centimetres above the street (zig-zag), and per layer of the
// directory its count in the cell. A layer's features are sorted by cell, then by source order, so these counts give
// each cell's range in every layer. A layer: its columns, each a varint string index (its name), a codec byte, a varint
// length and its bytes.
#pragma once

#include "base/arena.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Error.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Values.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// A point on OSM's grid: latitude and longitude in 1e-7 degrees.
struct GridPoint {
    std::int32_t lat = 0;
    std::int32_t lon = 0;

    constexpr GridPoint() noexcept = default;
    constexpr GridPoint(std::int32_t latitude, std::int32_t longitude) noexcept : lat(latitude), lon(longitude) {}
    friend constexpr bool operator==(const GridPoint&, const GridPoint&) = default;
};

/// A ring (closed, its first vertex not repeated) or a line: its vertices on the grid and, when its layer carries them,
/// a keep code per vertex.
struct RingView {
    std::span<const std::int32_t> lat;
    std::span<const std::int32_t> lon;
    std::span<const std::uint8_t> keep;
    bool has_keep = false;

    [[nodiscard]] std::size_t size() const noexcept { return lat.size(); }
    [[nodiscard]] GridPoint operator[](std::size_t i) const noexcept { return {lat[i], lon[i]}; }
};

/// A nullable string that is often a number (Codec::strn).
struct StringOrNumber {
    enum class Kind : std::uint8_t { null, string, number } kind = Kind::null;
    std::string_view text;   ///< a string
    std::int64_t number = 0; ///< a number: a non-negative decimal integer, written without leading zeros
};

/// One column of a layer, decoded. An absent column holds its default for every feature. What each codec fills:
/// - u8, boolean: `flags`, the value per feature (boolean: 0 absent, 1 false, 2 true);
/// - varu, varz, delta: `ints` per feature;
/// - nvarz, ndelta: `ints` and `flags` (1 present, 0 null) per feature;
/// - centi: `reals` and `flags` per feature; f64: `reals`;
/// - str: `refs` per feature, 0 for null, else the string's index + 1;
/// - strn: `flags` (0 null, 1 string, 2 number) and `ints` (the string's index, or the number) per feature;
/// - geom: `offsets` (features + 1), `lat` and `lon` per vertex;
/// - keep: `flags`, the code of every vertex of its geometry column, and that column's `offsets`;
/// - point: `lat` and `lon` per feature;
/// - tags: `offsets` (features + 1) into `refs`, key and value string indexes in pairs;
/// - ids: `offsets` into `ints`;
/// - strs: `offsets` into `refs` (string indexes) and `flags` per feature (1 a list, 0 null);
/// - value: `refs`, each feature's root node in `nodes`.
struct Column {
    Codec codec = Codec::u8;
    bool present = false;
    std::span<const std::uint32_t> offsets;
    std::span<const std::int32_t> lat;
    std::span<const std::int32_t> lon;
    std::span<const std::int64_t> ints;
    std::span<const double> reals;
    std::span<const std::uint8_t> flags;
    std::span<const std::uint32_t> refs;
    std::span<const ValueNode> nodes;
};

/// One layer of a tile: its features' columns by schema slot, and each cell's range of features.
class Layer {
public:
    LayerId id = LayerId::none;
    const LayerSchema* schema = nullptr;
    std::uint32_t count = 0;
    std::span<const Column> columns;           ///< by the schema's slot
    std::span<const std::uint32_t> cell_first; ///< cells + 1: cell c holds features [cell_first[c], cell_first[c + 1])
    std::span<const std::string_view> strings; ///< the tile's strings

    [[nodiscard]] const Column& column(std::uint8_t slot) const noexcept { return columns[slot]; }

    /// An integer column (u8, varu, varz, delta) at feature `i`; 0 when the column is absent.
    [[nodiscard]] std::int64_t integer(std::uint8_t slot, std::size_t i) const noexcept;
    /// A nullable integer column (nvarz, ndelta).
    [[nodiscard]] std::optional<std::int64_t> nullable_integer(std::uint8_t slot, std::size_t i) const noexcept;
    /// A decimal column (centi, f64).
    [[nodiscard]] std::optional<double> decimal(std::uint8_t slot, std::size_t i) const noexcept;
    /// A string column (str).
    [[nodiscard]] std::optional<std::string_view> string(std::uint8_t slot, std::size_t i) const noexcept;
    /// A string-or-number column (strn).
    [[nodiscard]] StringOrNumber string_or_number(std::uint8_t slot, std::size_t i) const noexcept;
    /// A boolean column.
    [[nodiscard]] std::optional<bool> boolean(std::uint8_t slot, std::size_t i) const noexcept;
    /// A geometry column, with its keep codes when the layer has a keep column for it.
    [[nodiscard]] RingView ring(std::uint8_t slot, std::size_t i) const noexcept;
    /// A point column.
    [[nodiscard]] GridPoint point(std::uint8_t slot, std::size_t i) const noexcept;
    /// A tags column: the number of pairs, and each pair's key and value.
    [[nodiscard]] std::size_t tag_count(std::uint8_t slot, std::size_t i) const noexcept;
    [[nodiscard]] std::pair<std::string_view, std::string_view> tag(std::uint8_t slot, std::size_t i,
                                                                    std::size_t k) const noexcept;
    /// A tags column's value for `key`, or nothing.
    [[nodiscard]] std::optional<std::string_view> tag(std::uint8_t slot, std::size_t i,
                                                      std::string_view key) const noexcept;
    /// An ids column.
    [[nodiscard]] std::span<const std::int64_t> ids(std::uint8_t slot, std::size_t i) const noexcept;
    /// A strs column: null, or the list's string indexes (resolve them with `strings`).
    [[nodiscard]] std::optional<std::span<const std::uint32_t>> string_list(std::uint8_t slot,
                                                                            std::size_t i) const noexcept;
    /// A value column (null when absent).
    [[nodiscard]] Value value(std::uint8_t slot, std::size_t i) const noexcept;

    /// The features of cell `c`.
    [[nodiscard]] std::pair<std::uint32_t, std::uint32_t> cell(std::size_t c) const noexcept {
        return {cell_first[c], cell_first[c + 1]};
    }
};

/// The tile's header.
struct TileHeader {
    std::uint8_t level = 0;
    std::uint8_t cells_per_side = 0;
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    std::int32_t south = 0;
    std::int32_t west = 0;
    std::int32_t north = 0;
    std::int32_t east = 0;
};

/// One cell of the cell table.
struct CellInfo {
    std::uint32_t total = 0; ///< features of every layer whose reference point lies in it
    std::int32_t south = 0;  ///< the grid box of what they draw (meaningful when total > 0)
    std::int32_t west = 0;
    std::int32_t north = 0;
    std::int32_t east = 0;
    std::int32_t top_cm = 0; ///< the highest point they draw, centimetres above the street
};

/// A layer of the directory this reader does not define (counted, not decoded).
struct SkippedLayer {
    std::uint8_t id = 0;
    std::uint32_t count = 0;
};

/// A decoded tile: views into the chunk's bytes and the arena it was decoded into.
struct Tile {
    TileHeader header;
    std::span<const std::string_view> strings;
    std::span<const CellInfo> cells;
    std::span<const Layer> layers;         ///< the layers this reader defines, in the directory's order
    std::span<const SkippedLayer> skipped; ///< the others, in the directory's order

    /// The layer `id`, or null when the tile has none.
    [[nodiscard]] const Layer* layer(LayerId id) const noexcept;
    /// Features in every layer, skipped ones included (the chunk table's count).
    [[nodiscard]] std::uint64_t feature_count() const noexcept;
};

/// The reach of a decoded tile (rendertiles' Reach.of): the union of its cells' boxes and their highest top, at least
/// 0. A tile without features reaches nowhere (Fault::missing).
[[nodiscard]] Result<Reach> reach_of(const Tile& tile) noexcept;

/// Checks a tile decoded from `chunk` against its pack, as Format3Reader.decode does: the header's level, column and
/// row are the chunk table's, and the tile reaches exactly where the `reach` section says.
[[nodiscard]] Result<void> check_against(const Tile& tile, const ChunkRef& chunk) noexcept;

/// Decodes tiles. One per worker: it keeps scratch buffers between tiles, so decoding allocates only in the arena.
class TileDecoder {
public:
    /// Decodes `raw` (one decompressed chunk) into `arena`. The tile views both, so both must outlive it.
    [[nodiscard]] Result<Tile> decode(std::span<const std::byte> raw, Arena& arena);

    /// Scratch buffers the column decoders share (grown as needed, never shrunk).
    struct Scratch {
        std::vector<std::int64_t> ints;
        std::vector<std::uint32_t> refs;
        std::vector<std::uint32_t> keys;
        ValueReader values{{}, false};
    };

private:
    Scratch scratch_;
};

} // namespace atlas::tiles
