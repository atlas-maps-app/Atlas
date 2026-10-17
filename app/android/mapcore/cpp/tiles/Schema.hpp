// The render tile's schema (payload version 1; rendertiles' Schema.kt and Format.kt): every layer's columns in the
// order a tile writes them, each a name and a codec. A column's name is the scene's own field name where there is one,
// so a decoded feature reads as the port's record. A column whose every value is its default is left out of a tile, and
// a reader takes a missing column as all defaults (POINT and F64 columns are never missing).
//
// What a reader does not know it skips, by Format's one rule: a layer whose id it does not know (its features counted
// in the cells all the same), a column whose name its schema lacks whatever its codec byte, a section or a `style`
// table. A column its schema has, coded with another codec (an unknown codec byte included), is an error.
//
// The per-layer lists live in tiles/layers/; :mapcorecheck holds them equal to Schema.kt, name by name (MT/tiles).
#pragma once

#include "tiles/Error.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::tiles {

/// Column codecs (rendertiles' Codec). Every column holds one value per feature of its layer.
enum class Codec : std::uint8_t {
    u8 = 1,      ///< one unsigned byte
    varu = 2,    ///< an unsigned varint
    varz = 3,    ///< a zig-zag varint
    delta = 4,   ///< zig-zag varint deltas from the previous feature's value (the first from 0)
    centi = 5,   ///< a nullable decimal: hundredths exactly, null, or a double's bits
    str = 6,     ///< a nullable string: 0 for null, else its index + 1
    boolean = 7, ///< a nullable boolean byte: 0 absent, 1 false, 2 true
    geom = 8,    ///< rings and lines, vertices neighbours share written once (Columns.hpp)
    keep = 9,    ///< the keep codes of the geometry column named like it without ".keep"
    point = 10,  ///< one grid point: latitudes, then longitudes, as zig-zag deltas
    tags = 11,   ///< OSM tags: a pair count, then key and value string indexes
    nvarz = 12,  ///< a nullable zig-zag varint: 0 for null, else its zig-zag + 1
    ids = 13,    ///< a list of 64-bit integers: a count, then zig-zag deltas
    strs = 14,   ///< a nullable list of strings: 0 for null, else count + 1, then string indexes
    value = 15,  ///< a typed value (Values.hpp); no layer of this payload has such a column
    f64 = 16,    ///< a double's bits, little-endian
    strn = 17,   ///< a nullable string that is often a number (house numbers)
    ndelta = 18, ///< a nullable integer as a delta from the column's previous non-null value
};

/// The largest codec id this reader knows.
inline constexpr std::uint8_t max_codec = 18;

/// One column of a layer.
struct ColumnSpec {
    std::string_view name;
    Codec codec = Codec::u8;

    constexpr ColumnSpec() noexcept = default;
    constexpr ColumnSpec(std::string_view n, Codec c) noexcept : name(n), codec(c) {}
};

/// At least the most columns a layer of this payload has (the building's 28).
inline constexpr std::size_t max_columns = 32;

/// The most features one layer of a tile may hold (Format.MAX_FEATURES).
inline constexpr std::uint32_t max_features = 1U << 20U;

/// The largest decompressed tile (Format.MAX_TILE_BYTES; the canal ring's densest is under 1 MB).
inline constexpr std::uint32_t max_tile_bytes = 4U << 20U;

/// The bytes of a tile's fixed header and of each entry of its layer directory.
inline constexpr std::size_t tile_header_bytes = 52;
inline constexpr std::size_t layer_entry_bytes = 16;

/// Keep codes (rendertiles' Keep): how many of the ladder's positive tolerances a vertex survives `simplify` at.
namespace keep {
/// The ladder, metres: 0 (no simplification), every tolerance regions.py's GENERALISE names and the live map's bands,
/// 1.5 m for L13, and 4, 16 and 25 m for the far levels. Codes 14 and 15 are spare.
inline constexpr std::array<double, 14> tolerances = {0.0, 0.15, 0.2, 0.3, 0.4, 0.5,  0.6,
                                                      0.7, 0.8,  0.9, 1.5, 4.0, 16.0, 25.0};
/// The code of a vertex that survives every tolerance (a ring's anchors, clip points, and every vertex of a small
/// ring).
inline constexpr std::uint8_t all = 13;
/// The largest code a tile may hold (14 and 15 are spare, but valid).
inline constexpr std::uint8_t max_code = 15;
/// `simplify` leaves rings of fewer points than this as they are.
inline constexpr std::size_t min_points = 6;

/// The ladder index of `tolerance` (metres), or -1 when it is not on the ladder.
[[nodiscard]] constexpr int level_of(double tolerance) noexcept {
    for (std::size_t i = 0; i < tolerances.size(); ++i) {
        if (tolerances[i] == tolerance) return static_cast<int>(i);
    }
    return -1;
}
} // namespace keep

namespace detail {
/// Not constexpr: a consteval function that reaches it fails to compile, with the reason in the diagnostic.
inline void compile_time_failure(const char* /*why*/) noexcept {}
} // namespace detail

/// The slot of the column named `name` in `columns`; a name that is not there fails to compile.
[[nodiscard]] consteval std::uint8_t slot_of(std::span<const ColumnSpec> columns, std::string_view name) {
    for (std::size_t i = 0; i < columns.size(); ++i) {
        if (columns[i].name == name) return static_cast<std::uint8_t>(i);
    }
    detail::compile_time_failure("no such column");
    return 0xFF;
}

/// A layer's schema, with what the reader derives from it: each keep column's geometry column, and the order of the
/// canonical dump (the columns' names sorted, keep columns left out: their codes print with their rings).
struct LayerSchema {
    LayerId id = LayerId::none;
    std::string_view label;
    std::span<const ColumnSpec> columns;
    std::span<const std::uint8_t> dump_order;
    std::span<const std::uint8_t> keep_of;  ///< for a keep column the slot of its geometry column, else 0xFF
    std::span<const std::uint8_t> keep_for; ///< for a geometry column the slot of its keep column, else 0xFF

    /// The slot of the column named `name`, or 0xFF.
    [[nodiscard]] constexpr std::uint8_t slot(std::string_view name) const noexcept {
        for (std::size_t i = 0; i < columns.size(); ++i) {
            if (columns[i].name == name) return static_cast<std::uint8_t>(i);
        }
        return 0xFF;
    }
};

/// The schema of a layer this payload defines, or null (marking, built and dem come with K30, K20b and K28).
[[nodiscard]] const LayerSchema* schema_of(LayerId id) noexcept;

/// Every layer this payload defines, by id.
[[nodiscard]] std::span<const LayerSchema* const> schemas() noexcept;

/// Cells per side at `level`: 8 at L14 and finer, 4 at L12 and coarser (Format.cellsPerSide).
[[nodiscard]] constexpr std::uint8_t cells_per_side(std::uint8_t level) noexcept { return level >= 14 ? 8 : 4; }

namespace detail {

/// Two column lists, one after the other.
template <std::size_t A, std::size_t B>
[[nodiscard]] consteval std::array<ColumnSpec, A + B> concat(const std::array<ColumnSpec, A>& a,
                                                             const std::array<ColumnSpec, B>& b) {
    std::array<ColumnSpec, A + B> out{};
    for (std::size_t i = 0; i < A; ++i) out[i] = a[i];
    for (std::size_t i = 0; i < B; ++i) out[A + i] = b[i];
    return out;
}

/// The slots of `columns` but keep columns, sorted by name.
template <std::size_t N>
[[nodiscard]] consteval auto dump_order(const std::array<ColumnSpec, N>& columns) {
    std::array<std::uint8_t, N> order{};
    std::size_t n = 0;
    for (std::size_t i = 0; i < N; ++i) {
        if (columns[i].codec != Codec::keep) order[n++] = static_cast<std::uint8_t>(i);
    }
    std::sort(order.begin(), order.begin() + static_cast<std::ptrdiff_t>(n),
              [&](std::uint8_t a, std::uint8_t b) { return columns[a].name < columns[b].name; });
    for (std::size_t i = n; i < N; ++i) order[i] = 0xFF;
    return order;
}

/// The number of columns but keep columns.
template <std::size_t N>
[[nodiscard]] consteval std::size_t fields(const std::array<ColumnSpec, N>& columns) {
    return static_cast<std::size_t>(
        std::count_if(columns.begin(), columns.end(), [](const ColumnSpec& c) { return c.codec != Codec::keep; }));
}

/// For each keep column the slot of its geometry column (its name without ".keep"), else 0xFF.
template <std::size_t N>
[[nodiscard]] consteval auto keep_of(const std::array<ColumnSpec, N>& columns) {
    std::array<std::uint8_t, N> out{};
    for (std::size_t i = 0; i < N; ++i) {
        out[i] = 0xFF;
        if (columns[i].codec != Codec::keep) continue;
        constexpr std::string_view suffix = ".keep";
        const std::string_view base = columns[i].name.substr(0, columns[i].name.size() - suffix.size());
        for (std::size_t j = 0; j < i; ++j) {
            if (columns[j].name == base && columns[j].codec == Codec::geom) out[i] = static_cast<std::uint8_t>(j);
        }
        if (out[i] == 0xFF) compile_time_failure("a keep column needs its geometry column before it");
    }
    return out;
}

/// For each geometry column the slot of its keep column, else 0xFF.
template <std::size_t N>
[[nodiscard]] consteval auto keep_for(const std::array<ColumnSpec, N>& columns) {
    const auto of = keep_of(columns);
    std::array<std::uint8_t, N> out{};
    for (std::size_t i = 0; i < N; ++i) out[i] = 0xFF;
    for (std::size_t i = 0; i < N; ++i) {
        if (of[i] != 0xFF) out[of[i]] = static_cast<std::uint8_t>(i);
    }
    return out;
}

} // namespace detail

} // namespace atlas::tiles
