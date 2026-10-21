// The render tile's writer (ENGINE.md step 3: built tiles become local packs; rendertiles' Format3Writer.encodeTile and
// codec/Columns.kt's encoders): a tile's layers, filled feature by feature in the reader's typed form
// (tiles/Payload.hpp's Column, owned, its strings interned), become one chunk's bytes, the bytes the packer writes for
// the same features:
//   - each layer in the order it was first filled, its columns in the schema's order, a column whose every value is its
//     default left out (a keep column with its geometry column, or when no ring carries codes);
//   - the string table in the order the columns use the strings, each column's values before its own name, as the
//     packer's StringTable fills it, so the bytes depend on the features alone and not on the order strings were
//     interned;
//   - the cell table from each feature's cell and each cell's box and top, which the caller works out (Format's *Boxes
//     and tops*: what the features draw, not only their vertices);
//   - the header with the grid's own box (tiles/TileBox.hpp).
// What the format cannot hold is refused with the reader's errors (tiles/Error.hpp), naming the layer and column: a
// point or double column without a value for every feature, keep codes on some rings and not others, a small ring's
// codes other than `keep::all`, features out of their cells' order, more features than a layer holds, a tile past
// max_tile_bytes, a string that is not UTF-8. tests/tiles/writer_test.cpp holds the writer to the packer: every tile of
// the bundled packs, decoded and written again, is its own bytes.
#pragma once

#include "tiles/Error.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <initializer_list>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::tiles {

/// A tile's strings, each kept once. Columns hold their ids; the order of the tile's string table is the columns' (see
/// the file's head), not the pool's.
class StringPool {
public:
    using Id = std::uint32_t;
    /// A null string.
    static constexpr Id none = 0xFFFF'FFFFU;

    StringPool() = default;
    StringPool(const StringPool&) = delete;
    StringPool& operator=(const StringPool&) = delete;
    StringPool(StringPool&&) = default;
    StringPool& operator=(StringPool&&) = default;
    ~StringPool() = default;

    /// `s`'s id, kept on first use.
    [[nodiscard]] Id intern(std::string_view s);
    [[nodiscard]] std::string_view at(Id id) const noexcept { return strings_[id]; }
    [[nodiscard]] std::size_t size() const noexcept { return strings_.size(); }

private:
    std::deque<std::string> strings_; ///< never moved, so the index's views stay valid
    std::unordered_map<std::string_view, Id> index_;
};

/// A run of a column's flat values: one feature's vertices, tag pairs, ids or strings.
struct FillSpan {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
};

/// One column of a layer being filled: a value per feature in the codec's typed form (the reader's Column, owned), its
/// per-feature arrays as long as the features set so far (the rest are the column's default).
struct ColumnFill {
    std::vector<std::int64_t> ints;   ///< u8, varu, varz, delta; nvarz and ndelta; a strn number
    std::vector<std::uint8_t> flags;  ///< presence (nvarz, ndelta, centi, f64, point, strs); boolean 0/1/2; strn kind;
                                      ///< geom: 1 a ring without keep codes, 2 one with them (0: not set, empty)
    std::vector<double> reals;        ///< centi, f64
    std::vector<StringPool::Id> refs; ///< str; a strn string
    std::vector<FillSpan> spans;      ///< geom (vertices), tags (pairs), ids, strs (strings)
    std::vector<std::int32_t> lat;    ///< geom: its vertices; point: per feature
    std::vector<std::int32_t> lon;
    std::vector<std::uint8_t> keep;      ///< geom: each vertex's code, where its rings carry them
    std::vector<std::int64_t> items;     ///< ids
    std::vector<StringPool::Id> strings; ///< tags (key, value, ...), strs
};

/// One layer of a tile to write: its features, in their cells' order and in source order within a cell, and their
/// values by the schema's slots (tiles/layers/). A value not set is its column's default (null, zero, empty); a point
/// or double column needs one for every feature. Each setter checks the slot's codec (a wrong one is a programming
/// error).
class LayerFill {
public:
    LayerFill(LayerId id, StringPool& strings);

    [[nodiscard]] LayerId id() const noexcept { return id_; }
    [[nodiscard]] const LayerSchema& schema() const noexcept { return *schema_; }
    [[nodiscard]] std::uint32_t count() const noexcept { return static_cast<std::uint32_t>(cells_.size()); }
    /// Each feature's cell (row by row from the north-west).
    [[nodiscard]] std::span<const std::uint16_t> cells() const noexcept { return cells_; }
    [[nodiscard]] const ColumnFill& column(std::uint8_t slot) const noexcept { return columns_[slot]; }

    /// A new feature in cell `cell` (at least the last feature's), and its index.
    std::uint32_t add(std::uint16_t cell);

    /// u8, varu (not negative), varz, delta.
    void integer(std::uint8_t slot, std::uint32_t f, std::int64_t v);
    /// nvarz, ndelta.
    void nullable_integer(std::uint8_t slot, std::uint32_t f, std::optional<std::int64_t> v);
    /// centi; f64 (never null).
    void decimal(std::uint8_t slot, std::uint32_t f, std::optional<double> v);
    /// str.
    void string(std::uint8_t slot, std::uint32_t f, std::optional<std::string_view> v);
    /// strn: a canonical decimal integer is written as a number, anything else as a string.
    void string_or_number(std::uint8_t slot, std::uint32_t f, std::optional<std::string_view> v);
    /// boolean.
    void boolean(std::uint8_t slot, std::uint32_t f, std::optional<bool> v);
    /// geom, with each vertex's keep code where its layer has a keep column for it (nothing: no codes). A layer's rings
    /// carry codes all or none.
    void ring(std::uint8_t slot, std::uint32_t f, std::span<const std::int32_t> lat, std::span<const std::int32_t> lon,
              std::optional<std::span<const std::uint8_t>> keep = std::nullopt);
    /// point.
    void point(std::uint8_t slot, std::uint32_t f, GridPoint p);
    /// tags, in their order (each key once).
    void tags(std::uint8_t slot, std::uint32_t f, std::span<const std::pair<std::string_view, std::string_view>> pairs);
    /// ids.
    void ids(std::uint8_t slot, std::uint32_t f, std::span<const std::int64_t> v);
    /// strs: null, or a list of strings.
    void string_list(std::uint8_t slot, std::uint32_t f, std::optional<std::span<const std::string_view>> v);

private:
    /// The column at `slot`, checked to be of `codec`, its per-feature arrays grown to hold feature `f`.
    ColumnFill& at(std::uint8_t slot, std::uint32_t f, std::initializer_list<Codec> codecs);

    LayerId id_;
    const LayerSchema* schema_;
    StringPool* strings_;
    std::vector<std::uint16_t> cells_;
    std::vector<ColumnFill> columns_;
};

/// A cell of a tile to write: the grid box of what its features draw and the highest point they draw, centimetres above
/// the street (meaningful where a feature lies in it).
struct CellFill {
    std::int32_t south = 0;
    std::int32_t west = 0;
    std::int32_t north = 0;
    std::int32_t east = 0;
    std::int32_t top_cm = 0;
};

/// A tile to write: its place on the grid, its cells, its strings and its layers.
class TileFill {
public:
    TileFill(std::uint8_t level, std::uint32_t x, std::uint32_t y);
    TileFill(const TileFill&) = delete;
    TileFill& operator=(const TileFill&) = delete;
    TileFill(TileFill&&) = default;
    TileFill& operator=(TileFill&&) = default;
    ~TileFill() = default;

    [[nodiscard]] std::uint8_t level() const noexcept { return level_; }
    [[nodiscard]] std::uint32_t x() const noexcept { return x_; }
    [[nodiscard]] std::uint32_t y() const noexcept { return y_; }
    [[nodiscard]] std::uint8_t cells_per_side() const noexcept { return tiles::cells_per_side(level_); }

    [[nodiscard]] StringPool& strings() noexcept { return *strings_; }
    [[nodiscard]] const StringPool& strings() const noexcept { return *strings_; }
    /// Each cell, row by row from the north-west.
    [[nodiscard]] std::span<CellFill> cells() noexcept { return cells_; }
    [[nodiscard]] std::span<const CellFill> cells() const noexcept { return cells_; }
    /// The layer `id`, added after the others the first time it is asked for (a layer of the schema). The reference
    /// stays valid as other layers are added.
    [[nodiscard]] LayerFill& layer(LayerId id);
    /// The layers, in the order they were added (the order they are written in).
    [[nodiscard]] const std::deque<LayerFill>& layers() const noexcept { return layers_; }

private:
    std::uint8_t level_;
    std::uint32_t x_;
    std::uint32_t y_;
    std::unique_ptr<StringPool> strings_; ///< the layers keep its address
    std::vector<CellFill> cells_;
    std::deque<LayerFill> layers_;
};

/// The tile's bytes (one decompressed chunk), or why the format cannot hold it.
[[nodiscard]] Result<std::vector<std::byte>> encode_tile(const TileFill& tile);

/// How far a tile to write reaches (Reach.of, the `reach` section's entry): the union of the boxes of its cells that
/// hold features and their highest top, at least 0. A tile without features reaches nowhere (Fault::missing).
[[nodiscard]] Result<Reach> reach_of(const TileFill& tile);

/// The value of `s` when it is a non-negative decimal integer written canonically (no sign, no leading zero, at most 15
/// digits), as Columns.canonicalInt has it: how a strn column tells a number from a string.
[[nodiscard]] std::optional<std::int64_t> canonical_int(std::string_view s) noexcept;

/// Whether `v` is exactly its hundredths and not -0 (Columns.isCenti): how a centi column codes it as an integer.
[[nodiscard]] bool is_centi(double v) noexcept;

} // namespace atlas::tiles
