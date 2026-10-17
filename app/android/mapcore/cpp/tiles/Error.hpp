// Errors of the tile reader (plan-vector T3: errors are values). Every fallible step of reading a pack, a section, a
// chunk, a tile or an entry of the expanded-tile cache returns std::expected<T, TileError>. The error says what was
// wrong (the fault), where (the site, and for a column its layer and name, so L0 names the field a damaged byte lands
// in), and a number that says more. It allocates nothing; describe() formats it for logs and tests.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <string>
#include <string_view>

namespace atlas::tiles {

/// What is wrong.
enum class Fault : std::uint8_t {
    truncated,   ///< the bytes end inside a value or a block
    overlong,    ///< a varint longer than its value needs (a writer always writes the shortest)
    overflow,    ///< a varint of more than 64 bits, or a number past its field's range
    left_over,   ///< bytes after a block's last value
    bad_utf8,    ///< a string that is not UTF-8
    bad_magic,   ///< not the kind of block it should be
    bad_version, ///< a format or payload version this reader does not know
    bad_value,   ///< a value its field does not allow (a codec, a type byte, a boolean of 3, a keep code of 16)
    bad_count,   ///< numbers that must agree do not (a cell's sum, a layer's features, a section's chunks)
    duplicate,   ///< the same section, layer, column, tile or key twice
    missing,     ///< a required section, column, table or tile is not there
    damaged,     ///< a checksum does not match
    too_big,     ///< past one of the reader's limits
    unsupported, ///< valid, but not what the core reads (format 1, deflated element chunks)
    io,          ///< the operating system failed (open, map, read, write); the detail is errno
};

/// Where it is wrong.
enum class Site : std::uint8_t {
    none,
    header,      ///< the container's 20-byte header
    directory,   ///< the section directory
    section,     ///< a section as a whole (its bounds, its CRC-32, its decompression)
    chunk_table, ///< the `tiles` section
    chunk,       ///< one chunk: its bytes, its decompression, its SHA-256 (the detail is its tile, packed)
    head,        ///< the `rhead` section
    style,       ///< the `style` section
    reach,       ///< the `reach` section, or a tile against its entry there
    tile,        ///< a tile's header
    strings,     ///< a tile's string table
    cells,       ///< a tile's cell table
    layers,      ///< a tile's layer directory, or a layer's column directory
    column,      ///< one column of one layer (named in the error)
    value,       ///< a typed value
    cache,       ///< an entry of the expanded-tile cache
    file,        ///< a file or a range source
};

/// The layers of a render tile (plan-vector Appendix A; rendertiles' `Layer`). Ids are the format's; a reader skips a
/// layer it does not know.
enum class LayerId : std::uint8_t {
    none = 0,
    building = 1,
    part = 2,
    ground = 3,
    water = 4,
    quay = 5,
    road = 6,
    line = 7,
    tree = 8,
    thing = 9,
    item = 10,
    label = 11,
    marking = 12,
    built = 13,
    dem = 14,
    variant = 15,
};

/// A reader's error: fault, site, and for a column the layer and the column's name (cut to 31 bytes).
struct TileError {
    Fault fault = Fault::truncated;
    Site site = Site::none;
    LayerId layer = LayerId::none;
    std::array<char, 32> column{};
    std::uint64_t detail = 0;

    [[nodiscard]] std::string_view column_name() const noexcept;
};

/// An error at a site, with an optional detail.
[[nodiscard]] TileError error(Fault fault, Site site, std::uint64_t detail = 0) noexcept;

/// An error in a layer's column.
[[nodiscard]] TileError column_error(Fault fault, LayerId layer, std::string_view column,
                                     std::uint64_t detail = 0) noexcept;

/// The fault's, site's and layer's names, as the messages and the JNI tests write them.
[[nodiscard]] std::string_view name_of(Fault fault) noexcept;
[[nodiscard]] std::string_view name_of(Site site) noexcept;
[[nodiscard]] std::string_view name_of(LayerId layer) noexcept;

/// One line for logs and tests: "<site>[ <layer>.<column>]: <fault> (<detail>)".
[[nodiscard]] std::string describe(const TileError& e);

/// A value or a reader's error.
template <class T>
using Result = std::expected<T, TileError>;

} // namespace atlas::tiles
