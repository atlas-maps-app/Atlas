// What the tile reader's tests share: the environment, real packs, and a writer of synthetic tiles that follows
// rendertiles' Format3Writer byte for byte (the same encodings, written independently here), so each codec and check of
// the reader is tested on bytes made to the specification.
#pragma once

#include "tiles/Bytes.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileBox.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <source_location>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::test {

/// The value of an optional the test requires to hold one. Without one the test case fails at the caller's line and
/// the caller reads a value-initialised T in its place (a helper cannot end the test case), so nothing reads an empty
/// optional; a test that goes on into the value's parts (a list's children) requires them first.
template <class T>
[[nodiscard]] const T& held(const std::optional<T>& o, std::source_location where = std::source_location::current()) {
    if (o.has_value()) return *o;
    DOCTEST_ADD_FAIL_AT(where.file_name(), static_cast<int>(where.line()), "the optional holds no value");
    static const T none{};
    return none;
}

/// An environment variable, or "".
[[nodiscard]] inline std::string environment(const char* name) {
    const char* value = std::getenv(name); // NOLINT(concurrency-mt-unsafe): tests read it before starting threads
    return value != nullptr ? std::string(value) : std::string();
}

/// Opens the pack file at `path`, mapped.
[[nodiscard]] inline tiles::Result<tiles::Pack> open_pack(const std::string& path) {
    auto source = tiles::MappedSource::open(path);
    if (!source) return std::unexpected(source.error());
    return tiles::Pack::open(std::move(*source));
}

/// A little-endian sink with LEB128 varints, as rendertiles' LeSink.
class Sink {
public:
    void u8(std::uint64_t v) { bytes.push_back(static_cast<std::byte>(v)); }
    void u16(std::uint64_t v) { le(v, 2); }
    void u32(std::uint64_t v) { le(v, 4); }
    void u64(std::uint64_t v) { le(v, 8); }
    void f64(double v) { u64(std::bit_cast<std::uint64_t>(v)); }
    void u32be(std::uint64_t v) {
        for (int k = 3; k >= 0; --k) u8(v >> (8U * static_cast<unsigned>(k)));
    }
    void u64be(std::uint64_t v) {
        u32be(v >> 32U);
        u32be(v & 0xFFFF'FFFFU);
    }
    void varu(std::uint64_t v) {
        while (v >= 0x80U) {
            u8((v & 0x7FU) | 0x80U);
            v >>= 7U;
        }
        u8(v);
    }
    void varz(std::int64_t v) { varu((static_cast<std::uint64_t>(v) << 1U) ^ static_cast<std::uint64_t>(v >> 63)); }
    void utf8(std::string_view s) {
        varu(s.size());
        raw(tiles::as_bytes(s));
    }
    void raw(std::span<const std::byte> b) { bytes.insert(bytes.end(), b.begin(), b.end()); }
    void block(const Sink& b) {
        varu(b.bytes.size());
        raw(b.bytes);
    }
    void put_u32(std::size_t at, std::uint64_t v) {
        for (std::size_t k = 0; k < 4; ++k) bytes[at + k] = static_cast<std::byte>(v >> (8U * k));
    }
    [[nodiscard]] std::size_t size() const noexcept { return bytes.size(); }

    std::vector<std::byte> bytes;

private:
    void le(std::uint64_t v, unsigned n) {
        for (unsigned k = 0; k < n; ++k) u8(v >> (8U * k));
    }
};

/// A column to write: its name, codec and encoded bytes.
struct ColumnBytes {
    std::string name;
    tiles::Codec codec;
    std::vector<std::byte> bytes;
};

/// A layer to write: its id, features and columns, and each feature's cell.
struct LayerBytes {
    std::uint8_t id = 0;
    std::vector<std::uint32_t> cells; ///< per feature, sorted
    std::vector<ColumnBytes> columns;
    std::uint16_t column_count_override = 0xFFFF;
};

/// A tile to write, as Format3Writer.encodeTile lays it out.
struct TileBytes {
    std::uint8_t level = 14;
    std::uint8_t n = 8;
    std::uint32_t x = 8414;
    std::uint32_t y = 5384;
    std::optional<std::array<std::int32_t, 4>> box; ///< the tile's own (Grid.tileBox) unless set
    std::vector<std::string> strings;
    std::vector<LayerBytes> layers;
    /// Per cell: its box (relative values are computed) and top; unset cells get the tile's box.
    std::map<std::uint32_t, std::pair<std::array<std::int32_t, 4>, std::int32_t>> cell_boxes;

    /// The string's index in the table, added when new.
    std::uint32_t string(std::string_view s) {
        for (std::size_t i = 0; i < strings.size(); ++i) {
            if (strings[i] == s) return static_cast<std::uint32_t>(i);
        }
        strings.emplace_back(s);
        return static_cast<std::uint32_t>(strings.size() - 1);
    }

    /// The tile's grid box: [south, west, north, east].
    [[nodiscard]] std::array<std::int32_t, 4> grid_box() const {
        return box.value_or(std::array<std::int32_t, 4>{tiles::row_edge(level, y + 1), tiles::column_edge(level, x),
                                                        tiles::row_edge(level, y), tiles::column_edge(level, x + 1)});
    }

    /// The features of every layer in cell `c`.
    [[nodiscard]] std::uint64_t in_cell(std::uint32_t c) const {
        std::uint64_t total = 0;
        for (const auto& l : layers) total += static_cast<std::uint64_t>(std::ranges::count(l.cells, c));
        return total;
    }

    /// The box and top cell `c` is written with.
    [[nodiscard]] std::pair<std::array<std::int32_t, 4>, std::int32_t> cell_box(std::uint32_t c) const {
        const auto it = cell_boxes.find(c);
        return it != cell_boxes.end() ? it->second : std::pair{grid_box(), 0};
    }

    /// The tile's reach as the writer computes it (rendertiles' Reach.of): its cells' boxes joined, their highest top
    /// (at least 0).
    [[nodiscard]] tiles::Reach reach() const {
        tiles::Reach r;
        bool any = false;
        for (std::uint32_t c = 0; c < std::uint32_t{n} * n; ++c) {
            if (in_cell(c) == 0) continue;
            const auto [b, top] = cell_box(c);
            if (!any || b[0] < r.box.south) r.box.south = b[0];
            if (!any || b[1] < r.box.west) r.box.west = b[1];
            if (!any || b[2] > r.box.north) r.box.north = b[2];
            if (!any || b[3] > r.box.east) r.box.east = b[3];
            r.top_cm = std::max(r.top_cm, top);
            any = true;
        }
        return r;
    }

    [[nodiscard]] std::vector<std::byte> encode() {
        const std::array<std::int32_t, 4> tb = grid_box();
        // names first, so the table holds them before the cells are written
        for (const auto& l : layers) {
            for (const auto& c : l.columns) (void)string(c.name);
        }
        Sink strings_block;
        strings_block.varu(strings.size());
        for (const auto& s : strings) strings_block.utf8(s);
        std::vector<Sink> layer_blocks;
        for (const auto& l : layers) {
            Sink b;
            for (const auto& c : l.columns) {
                b.varu(string(c.name));
                b.u8(static_cast<std::uint8_t>(c.codec));
                b.varu(c.bytes.size());
                b.raw(c.bytes);
            }
            layer_blocks.push_back(std::move(b));
        }
        Sink cells;
        const std::uint32_t cell_count = std::uint32_t{n} * n;
        for (std::uint32_t c = 0; c < cell_count; ++c) {
            const std::uint64_t total = in_cell(c);
            cells.varu(total);
            if (total == 0) continue;
            const auto [b, top] = cell_box(c);
            cells.varz(std::int64_t{b[0]} - tb[0]);
            cells.varz(std::int64_t{b[1]} - tb[1]);
            cells.varz(std::int64_t{b[2]} - tb[0]);
            cells.varz(std::int64_t{b[3]} - tb[1]);
            cells.varz(top);
            for (const auto& l : layers) cells.varu(static_cast<std::uint64_t>(std::ranges::count(l.cells, c)));
        }
        Sink out;
        out.u32(tiles::tile_magic);
        out.u16(tiles::payload_version);
        out.u8(level);
        out.u8(n);
        out.u32(x);
        out.u32(y);
        for (const std::int32_t v : tb) out.u32(static_cast<std::uint32_t>(v));
        const std::size_t strings_at = tiles::tile_header_bytes + (layers.size() * tiles::layer_entry_bytes);
        const std::size_t cells_at = strings_at + strings_block.size();
        out.u32(strings_at);
        out.u32(strings_block.size());
        out.u32(cells_at);
        out.u32(cells.size());
        out.u16(layers.size());
        out.u16(0);
        std::size_t at = cells_at + cells.size();
        for (std::size_t i = 0; i < layers.size(); ++i) {
            const auto& l = layers[i];
            out.u8(l.id);
            out.u8(0);
            out.u16(l.column_count_override != 0xFFFF ? l.column_count_override : l.columns.size());
            out.u32(l.cells.size());
            out.u32(at);
            out.u32(layer_blocks[i].size());
            at += layer_blocks[i].size();
        }
        out.raw(strings_block.bytes);
        out.raw(cells.bytes);
        for (const auto& b : layer_blocks) out.raw(b.bytes);
        return out.bytes;
    }
};

/// One ring or line to encode: its vertices and, when its layer carries them, its keep codes.
struct Ring {
    std::vector<std::int32_t> lat;
    std::vector<std::int32_t> lon;
    std::vector<std::uint8_t> keep;
};

/// The column encoders, as rendertiles' Columns.encode writes each codec (written here independently).
namespace columns {

inline std::vector<std::byte> u8(std::initializer_list<unsigned> v) {
    Sink s;
    for (const unsigned x : v) s.u8(x);
    return s.bytes;
}

inline std::vector<std::byte> varu(std::initializer_list<std::uint64_t> v) {
    Sink s;
    for (const std::uint64_t x : v) s.varu(x);
    return s.bytes;
}

inline std::vector<std::byte> varz(std::initializer_list<std::int64_t> v) {
    Sink s;
    for (const std::int64_t x : v) s.varz(x);
    return s.bytes;
}

inline std::vector<std::byte> delta(std::initializer_list<std::int64_t> v) {
    Sink s;
    std::int64_t previous = 0;
    for (const std::int64_t x : v) {
        s.varz(tiles::wrap_sub(x, previous));
        previous = x;
    }
    return s.bytes;
}

/// CENTI: hundredths when exact, null, or a double's bits.
inline std::vector<std::byte> centi(std::initializer_list<std::optional<double>> v) {
    Sink s;
    for (const auto& x : v) {
        if (!x) {
            s.varu(1);
            continue;
        }
        const double d = *x;
        const bool small = std::isfinite(d) && std::fabs(d) <= 9e13;
        const auto n = small ? static_cast<std::int64_t>(std::llround(d * 100)) : 0;
        if (small && static_cast<double>(n) / 100.0 == d && (d != 0.0 || !std::signbit(d))) {
            s.varu(((static_cast<std::uint64_t>(n) << 1U) ^ static_cast<std::uint64_t>(n >> 63)) << 2U);
        } else {
            s.varu(2);
            s.f64(d);
        }
    }
    return s.bytes;
}

inline std::vector<std::byte> str(TileBytes& t, std::initializer_list<std::optional<std::string_view>> v) {
    Sink s;
    for (const auto& x : v) s.varu(x ? t.string(*x) + 1U : 0U);
    return s.bytes;
}

inline std::vector<std::byte> boolean(std::initializer_list<std::optional<bool>> v) {
    Sink s;
    for (const auto& x : v) s.u8(!x ? 0 : (*x ? 2 : 1));
    return s.bytes;
}

inline std::vector<std::byte> nvarz(std::initializer_list<std::optional<std::int64_t>> v) {
    Sink s;
    for (const auto& x : v) {
        if (!x) {
            s.varu(0);
        } else {
            s.varu(((static_cast<std::uint64_t>(*x) << 1U) ^ static_cast<std::uint64_t>(*x >> 63)) + 1U);
        }
    }
    return s.bytes;
}

inline std::vector<std::byte> ndelta(std::initializer_list<std::optional<std::int64_t>> v) {
    Sink s;
    std::int64_t previous = 0;
    for (const auto& x : v) {
        if (!x) {
            s.varu(0);
            continue;
        }
        const std::int64_t d = *x - previous;
        previous = *x;
        s.varu(((static_cast<std::uint64_t>(d) << 1U) ^ static_cast<std::uint64_t>(d >> 63)) + 1U);
    }
    return s.bytes;
}

/// STRN: a canonical decimal integer as a delta from the column's previous number, else a string of the table.
inline std::vector<std::byte> strn(TileBytes& t, std::initializer_list<std::optional<std::string_view>> v) {
    Sink s;
    std::int64_t previous = 0;
    for (const auto& x : v) {
        if (!x) {
            s.varu(0);
            continue;
        }
        const bool number = !x->empty() && x->size() <= 15 && (x->size() <= 1 || (*x)[0] != '0') &&
                            std::ranges::all_of(*x, [](char c) { return c >= '0' && c <= '9'; });
        if (number) {
            const std::int64_t n = std::stoll(std::string(*x));
            const std::int64_t d = n - previous;
            previous = n;
            s.varu((2 * ((static_cast<std::uint64_t>(d) << 1U) ^ static_cast<std::uint64_t>(d >> 63))) + 2);
        } else {
            s.varu((2 * std::uint64_t{t.string(*x)}) + 1);
        }
    }
    return s.bytes;
}

inline std::vector<std::byte> point(std::initializer_list<std::pair<std::int32_t, std::int32_t>> v) {
    Sink lat;
    Sink lon;
    std::int64_t a = 0;
    std::int64_t o = 0;
    for (const auto& [la, lo] : v) {
        lat.varz(la - a);
        lon.varz(lo - o);
        a = la;
        o = lo;
    }
    Sink s;
    s.block(lat);
    s.block(lon);
    return s.bytes;
}

inline std::vector<std::byte> tags(TileBytes& t,
                                   std::initializer_list<std::vector<std::pair<std::string, std::string>>> v) {
    Sink s;
    for (const auto& m : v) {
        s.varu(m.size());
        for (const auto& [k, x] : m) {
            s.varu(t.string(k));
            s.varu(t.string(x));
        }
    }
    return s.bytes;
}

inline std::vector<std::byte> ids(std::initializer_list<std::vector<std::int64_t>> v) {
    Sink s;
    for (const auto& list : v) {
        s.varu(list.size());
        std::int64_t previous = 0;
        for (const std::int64_t x : list) {
            s.varz(x - previous);
            previous = x;
        }
    }
    return s.bytes;
}

inline std::vector<std::byte> strs(TileBytes& t, std::initializer_list<std::optional<std::vector<std::string>>> v) {
    Sink s;
    for (const auto& list : v) {
        if (!list) {
            s.varu(0);
            continue;
        }
        s.varu(list->size() + 1);
        for (const auto& x : *list) s.varu(t.string(x));
    }
    return s.bytes;
}

/// GEOM: Geometry.encode, vertices neighbours share written once.
inline std::vector<std::byte> geom(const std::vector<Ring>& rings) {
    Sink counts;
    Sink kinds;
    Sink jumps;
    std::array<Sink, 4> planes;
    Sink escapes;
    Sink first_refs;
    Sink next_refs;
    std::map<std::uint64_t, std::uint32_t> seen;
    std::uint32_t k = 0;
    std::int64_t last_lat = 0;
    std::int64_t last_lon = 0;
    auto plane = [&](std::int64_t v, Sink& lo, Sink& hi) {
        const std::uint64_t z = (static_cast<std::uint64_t>(v) << 1U) ^ static_cast<std::uint64_t>(v >> 63);
        if (z < 0xFFFFU) {
            lo.u8(z & 0xFFU);
            hi.u8(z >> 8U);
        } else {
            lo.u8(0xFF);
            hi.u8(0xFF);
            escapes.varu(z);
        }
    };
    for (const Ring& r : rings) {
        counts.varu(r.lat.size());
        std::int64_t previous_ref = -1;
        for (std::size_t i = 0; i < r.lat.size(); ++i) {
            const std::int64_t lat = r.lat[i];
            const std::int64_t lon = r.lon[i];
            const std::uint64_t key =
                (std::uint64_t{static_cast<std::uint32_t>(r.lat[i])} << 32U) | static_cast<std::uint32_t>(r.lon[i]);
            if (const auto it = seen.find(key); it != seen.end()) {
                kinds.u8(1);
                if (previous_ref >= 0) {
                    next_refs.varz(std::int64_t{it->second} - (previous_ref - 1));
                } else {
                    first_refs.varu(k - it->second);
                }
                previous_ref = it->second;
            } else {
                kinds.u8(0);
                if (i == 0) {
                    jumps.varz(lat - last_lat);
                    jumps.varz(lon - last_lon);
                } else {
                    plane(lat - last_lat, planes[0], planes[1]);
                    plane(lon - last_lon, planes[2], planes[3]);
                }
                previous_ref = -1;
            }
            seen[key] = k;
            ++k;
            last_lat = lat;
            last_lon = lon;
        }
    }
    Sink s;
    s.block(counts);
    s.block(kinds);
    s.block(jumps);
    for (const Sink& p : planes) s.block(p);
    s.block(escapes);
    s.block(first_refs);
    s.block(next_refs);
    return s.bytes;
}

/// KEEP: the codes of every ring of at least six vertices.
inline std::vector<std::byte> keep(const std::vector<Ring>& rings) {
    Sink s;
    for (const Ring& r : rings) {
        if (r.lat.size() < tiles::keep::min_points) continue;
        for (const std::uint8_t c : r.keep) s.u8(c);
    }
    return s.bytes;
}

/// VALUE: values already written by the caller (a type byte and its payload each).
inline std::vector<std::byte> raw_values(const Sink& written) { return written.bytes; }

} // namespace columns

/// A zstd frame of `raw` in raw (stored) blocks, with its content size: what the core's decompressor reads, made
/// without a compressor (the core vendors zstd's decompression only).
[[nodiscard]] inline std::vector<std::byte> zstd_frame(std::span<const std::byte> raw) {
    Sink out;
    out.u32(0xFD2F'B528U); // the frame's magic
    out.u8(0xE0);          // eight bytes of content size, a single segment, no checksum, no dictionary
    out.u64(raw.size());
    constexpr std::size_t block = 128U << 10U;
    std::size_t at = 0;
    do {
        const std::size_t n = std::min(block, raw.size() - at);
        const bool last = at + n == raw.size();
        const std::uint32_t header =
            (last ? 1U : 0U) | (0U << 1U) | (static_cast<std::uint32_t>(n) << 3U); // a raw block
        out.u8(header & 0xFFU);
        out.u8((header >> 8U) & 0xFFU);
        out.u8((header >> 16U) & 0xFFU);
        out.raw(raw.subspan(at, n));
        at += n;
    } while (at < raw.size());
    return out.bytes;
}

/// A section to write into a pack.
struct SectionBytes {
    std::string name;
    std::vector<std::byte> stored;
    std::uint64_t raw = 0;
    tiles::Method method = tiles::Method::stored;
};

/// A chunk to write: its tile, its decompressed bytes, and its entry in `reach` (a tile's own: TileBytes::reach()).
struct ChunkBytes {
    tiles::TileKey tile;
    std::vector<std::byte> raw;
    std::uint32_t count = 0;
    tiles::Reach reach{};
};

/// The `rhead` bytes for `levels` and the Westerkerk's frame, with one view, `home`, at ladder index `tolerance`, and
/// `bands` (the living map's one band at index 0 by default).
[[nodiscard]] inline std::vector<std::byte>
head_bytes(std::span<const std::uint8_t> levels, tiles::Profile profile = tiles::Profile::living,
           std::uint8_t tolerance = 0,
           const std::vector<tiles::HeadBand>& bands = {{.tolerance = 0, .up_to = std::nullopt}}) {
    Sink h;
    h.u32(tiles::head_magic);
    h.u16(tiles::payload_version);
    h.u8(static_cast<std::uint8_t>(profile));
    h.u8(levels.size());
    for (const std::uint8_t z : levels) h.u8(z);
    h.f64(52.3746);
    h.f64(4.884);
    h.f64(67954.56338551514);
    h.f64(110540.0);
    h.utf8("amsterdam");
    h.utf8("a1-test");
    h.utf8("s1.1-test");
    h.utf8("0123456789abcdef");
    h.varu(1);
    h.utf8("home");
    h.u8(8); // a map of one entry: dist = 430.0
    h.varu(1);
    h.utf8("dist");
    h.u8(5);
    h.f64(430.0);
    h.u8(tolerance);
    h.u8(bands.size());
    for (const tiles::HeadBand& b : bands) h.u8(b.tolerance);
    for (const tiles::HeadBand& b : bands) {
        if (b.up_to) h.f64(*b.up_to);
    }
    return h.bytes;
}

/// The `reach` bytes of `chunks`, entries of `entry` bytes (the fields past the twenty this payload knows are zero).
[[nodiscard]] inline std::vector<std::byte> reach_bytes(std::span<const ChunkBytes> chunks,
                                                        std::size_t entry = tiles::reach_entry_bytes) {
    Sink r;
    r.u32(tiles::reach_magic);
    r.u16(tiles::payload_version);
    r.u16(entry);
    r.u32(chunks.size());
    for (const auto& c : chunks) {
        for (const std::int32_t v :
             {c.reach.box.south, c.reach.box.west, c.reach.box.north, c.reach.box.east, c.reach.top_cm}) {
            r.u32(static_cast<std::uint32_t>(v));
        }
        for (std::size_t k = tiles::reach_entry_bytes; k < entry; ++k) r.u8(0);
    }
    return r.bytes;
}

/// Typed values (rendertiles' Values) for the test sections, each a writer of its bytes.
namespace values {

using ValueBytes = std::function<void(Sink&)>;

/// A string, a Double, an Int, a boolean, null.
[[nodiscard]] inline ValueBytes text(std::string_view v) {
    return [v](Sink& s) {
        s.u8(6);
        s.utf8(v);
    };
}
[[nodiscard]] inline ValueBytes real(double v) {
    return [v](Sink& s) {
        s.u8(5);
        s.f64(v);
    };
}
[[nodiscard]] inline ValueBytes integer(std::int64_t v) {
    return [v](Sink& s) {
        s.u8(3);
        s.varz(v);
    };
}
[[nodiscard]] inline ValueBytes boolean(bool v) {
    return [v](Sink& s) { s.u8(v ? 2 : 1); };
}
[[nodiscard]] inline ValueBytes null() {
    return [](Sink& s) { s.u8(0); };
}

/// A list of values; a map of keys and values, in its order.
[[nodiscard]] inline ValueBytes list(std::vector<ValueBytes> values) {
    return [values = std::move(values)](Sink& s) {
        s.u8(7);
        s.varu(values.size());
        for (const ValueBytes& v : values) v(s);
    };
}
using Field = std::pair<std::string_view, ValueBytes>;
[[nodiscard]] inline ValueBytes map(std::vector<Field> fields) {
    return [fields = std::move(fields)](Sink& s) {
        s.u8(8);
        s.varu(fields.size());
        for (const auto& [key, v] : fields) {
            s.utf8(key);
            v(s);
        }
    };
}

} // namespace values

/// The tables of a test pack's style: the engine's style file's values (app/android/mapcore/style/engine.jsonc) with
/// its land, area and surface tables empty (so a test pack's ground draws no land fill and every area in
/// `area_other`), the port's default state (WATER_Y -1.5, no TONE, no WIN_STYLE), an empty scene, and a table the core
/// does not know: `LAND` = {"park": "#a8c890"}.
[[nodiscard]] inline std::vector<values::Field> style_tables() {
    namespace v = values;
    return {
        {"LAND", v::map({{"park", v::text("#a8c890")}})},
        {"land", v::map({})},
        {"area", v::map({})},
        {"surf", v::map({})},
        {"surf_colour", v::map({})},
        {"ground", v::map({{"area_other", v::text("#DCD3C4")}, {"cycleway", v::text("#C98A7A")}})},
        {"rail", v::map({{"ballast", v::text("#A0978B")},
                         {"ballast_half_width", v::real(1.6)},
                         {"rails_day", v::text("#5E5A56")},
                         {"rails_night", v::text("#1A1C22")},
                         {"rails_width", v::real(0.7)}})},
        {"big_roads", v::map({{"asphalt_day", v::text("#77777A")},
                              {"asphalt_night", v::text("#22252E")},
                              {"lines_day", v::text("#F2F1EC")},
                              {"lines_night", v::text("#5A5E6A")},
                              {"lines_width", v::real(0.18)},
                              {"lane_dash", v::list({v::real(3.0), v::real(9.0)})}})},
        {"runways", v::map({{"pavement_day", v::text("#5F6064")},
                            {"pavement_night", v::text("#1E2128")},
                            {"marks_day", v::text("#F4F3EE")},
                            {"marks_night", v::text("#4A4F5C")},
                            {"centre_width", v::real(0.9)},
                            {"centre_dash", v::list({v::real(30.0), v::real(20.0)})}})},
        {"taxiways", v::map({{"pavement_day", v::text("#8C8C8D")},
                             {"pavement_night", v::text("#20232A")},
                             {"lines_day", v::text("#E2BC3C")},
                             {"lines_night", v::text("#8A7A3A")},
                             {"lines_width", v::real(0.3)},
                             {"lead_in_width", v::real(0.25)}})},
        {"marine", v::map({{"breakwater", v::list({v::real(9.0), v::text("#9E9A92")})},
                           {"groyne", v::list({v::real(4.0), v::text("#8E8A84")})},
                           {"pier", v::list({v::real(5.0), v::text("#A89A86")})},
                           {"quay", v::list({v::real(3.0), v::text("#B4AEA4")})}})},
        {"quays", v::map({{"waterline_day", v::text("#2C2A22")},
                          {"waterline_night", v::text("#06070A")},
                          {"coping", v::text("#B8B0A2")}})},
        {"reflections", v::map({{"opacity_day", v::real(0.92)},
                                {"opacity_night", v::real(0.95)},
                                {"dark_day", v::real(0.74)},
                                {"dark_overcast", v::real(0.62)},
                                {"dark_night", v::real(0.5)},
                                {"windows_day", v::text("#2B3440")},
                                {"windows_night", v::text("#05070C")},
                                {"windows_opacity", v::real(0.55)},
                                {"trees_day", v::text("#3E5A36")},
                                {"trees_night", v::text("#0A0F0D")},
                                {"streak", v::list({v::text("#FFE3AE"), v::text("#FFC27A"), v::text("#FFB060")})},
                                {"streak_opacity", v::list({v::real(0.95), v::real(0.45), v::real(0.0)})},
                                {"glitter", v::text("#FFF7E2")}})},
        {"drizzle", v::map({{"colour", v::text("#A9B8D8")}, {"width", v::real(0.4)}, {"opacity", v::real(0.55)}})},
        {"park_edges", v::map({{"day", v::text("#2F6B3E")},
                               {"night", v::text("#4C8A5A")},
                               {"width", v::real(2.2)},
                               {"dash", v::list({v::real(7.0), v::real(5.0)})},
                               {"opacity", v::real(0.85)}})},
        {"trams", v::map({{"day", v::text("#6E6A66")}, {"night", v::text("#2A2E3A")}, {"width", v::real(0.9)}})},
        {"bridges", v::map({{"lines", v::text("#5A4E44")}, {"lines_width", v::real(1.2)}})},
        {"setts",
         v::map({{"marks", v::text("#7E7264")}, {"marks_width", v::real(0.4)}, {"marks_opacity", v::real(0.6)}})},
        {"buildings", v::map({{"roof_edge_day", v::text("#6F695F")},
                              {"roof_edge_night", v::text("#2E3446")},
                              {"roof_edge_slope", v::real(0.25)},
                              {"roof_edge_top", v::real(0.45)},
                              {"wall_edge", v::real(0.3)}})},
        {"houseboats", v::map({{"hull", v::text("#23272B")},
                               {"deck", v::text("#6E5E4C")},
                               {"cabin_edge", v::text("#2A2C30")},
                               {"cabin_edge_width", v::real(0.4)},
                               {"cabin_glass_night", v::text("#10141E")},
                               {"cabin_frame_day", v::text("#E8E4DA")},
                               {"cabin_frame_night", v::text("#2A2E38")},
                               {"cabin_frame_width", v::real(0.5)}})},
        {"windows", v::map({{"glass_top", v::text("#B7CBE0")},
                            {"glass_bottom", v::text("#3E4C5E")},
                            {"glass_overcast_top", v::text("#C7CDD5")},
                            {"glass_overcast_bottom", v::text("#59626E")},
                            {"lit_top", v::text("#E8EEF4")},
                            {"lit_bottom", v::text("#8496AA")},
                            {"dark_day", v::text("#2E3440")},
                            {"dark_night", v::text("#141824")},
                            {"lit", v::text("#9FB2C4")},
                            {"warehouse_day", v::text("#2C4A3B")},
                            {"warehouse_night", v::text("#0E1612")},
                            {"frame_day", v::text("#F2EEE6")},
                            {"frame_night", v::text("#3A3F4E")},
                            {"frame_width", v::real(0.7)},
                            {"sill_day", v::text("#DAD5CA")},
                            {"sill_night", v::text("#2E323E")},
                            {"shop_frame_day", v::text("#2A2D30")},
                            {"shop_frame_night", v::text("#30343E")},
                            {"shop_frame_width", v::real(0.8)},
                            {"plinth_day", v::text("#47443F")},
                            {"plinth_night", v::text("#121318")},
                            {"cornice_day", v::text("#E6E1D6")},
                            {"cornice_night", v::text("#3A3C44")}})},
        {"trees", v::map({{"leaf", v::text("#4E6B35")},
                          {"autumn", v::text("#9C8A36")},
                          {"bark_day", v::text("#4B4038")},
                          {"bark_night", v::text("#0B0D10")},
                          {"bark_lit", v::text("#8A7B6C")},
                          {"bark_lit_opacity", v::real(0.55)},
                          {"crown", v::text("#6E9450")},
                          {"trunk_day", v::text("#4A3A2E")},
                          {"trunk_night", v::text("#0C100E")}})},
        {"things", v::map({{"mast_day", v::text("#2A2E33")},       {"mast_night", v::text("#161A22")},
                           {"lantern_day", v::text("#3B4148")},    {"lantern_night", v::text("#FFE7B8")},
                           {"cap_day", v::text("#22262B")},        {"cap_night", v::text("#2A2620")},
                           {"glint", v::text("#E6ECF2")},          {"glint_opacity", v::real(0.7)},
                           {"bench", v::text("#8A6A4C")},          {"bench_back", v::text("#7A5C40")},
                           {"basket", v::text("#2F4A3C")},         {"bollard_day", v::text("#4A2620")},
                           {"bollard_night", v::text("#140B0A")},  {"bollard_head", v::text("#B07A6A")},
                           {"bollard_head_opacity", v::real(0.6)}, {"rack_day", v::text("#6B7282")},
                           {"rack_night", v::text("#2A2E3A")},     {"rack_width", v::real(0.9)},
                           {"railing_day", v::text("#26292C")},    {"railing_night", v::text("#0A0B0E")}})},
        {"doors", v::map({{"door_1", v::text("#1F3A30")},
                          {"door_2", v::text("#23262C")},
                          {"door_3", v::text("#3B2220")},
                          {"door_4", v::text("#1E2C3E")},
                          {"door_5", v::text("#2E3B2A")},
                          {"outline", v::text("#E8E2D6")},
                          {"outline_width", v::real(0.5)},
                          {"stoop_day", v::text("#9C9488")},
                          {"stoop_night", v::text("#22232A")},
                          {"plate_day", v::text("#F2EEE4")},
                          {"plate_night", v::text("#3A3A40")},
                          {"number_day", v::text("#1E2A44")},
                          {"number_night", v::text("#B9C0D0")}})},
        {"items", v::map({{"shaft", v::text("#C9C6BE")},
                          {"glass_day", v::text("#7F98AA")},
                          {"glass_night", v::text("#1A2230")},
                          {"gallery", v::text("#E2E0DA")},
                          {"antenna", v::text("#8C9096")},
                          {"shadow_night", v::text("#05060A")},
                          {"steel_day", v::text("#7D8993")},
                          {"steel_night", v::text("#1A1E24")},
                          {"machinery_day", v::text("#C9CCCF")},
                          {"machinery_night", v::text("#20242A")},
                          {"floodlights_day", v::text("#5E646B")},
                          {"floodlights_night", v::text("#15181E")},
                          {"conveyor_day", v::text("#B4B1A9")},
                          {"conveyor_night", v::text("#1C1E22")},
                          {"glow_inner", v::text("#FFE9C2")},
                          {"glow_inner_opacity", v::real(0.75)},
                          {"glow_middle", v::text("#FFD9A0")},
                          {"glow_middle_opacity", v::real(0.22)},
                          {"glow_outer_opacity", v::integer(0)},
                          {"glow_core", v::text("#FFF4DC")},
                          {"turbine_day", v::text("#E9ECEE")},
                          {"turbine_night", v::text("#3A3F4C")},
                          {"blades_day", v::text("#F2F4F5")},
                          {"blades_night", v::text("#454B58")},
                          {"blade_edge_day", v::text("#C9CDD1")},
                          {"blade_edge_night", v::text("#2A2E38")},
                          {"blade_edge_width", v::real(0.4)},
                          {"hub_day", v::text("#DADDE0")},
                          {"hub_night", v::text("#3A3F4C")},
                          {"turbine_light", v::text("#FF3B30")},
                          {"turbine_light_opacity", v::real(0.9)},
                          {"pylon_day", v::text("#6E747C")},
                          {"pylon_night", v::text("#1A1E26")},
                          {"conductor_day", v::text("#4A4F57")},
                          {"conductor_night", v::text("#1A1E26")},
                          {"conductor_width", v::real(0.45)},
                          {"conductor_opacity", v::real(0.8)},
                          {"coaster_steel_top_day", v::text("#E6E3DC")},
                          {"coaster_steel_side_day", v::text("#5F656D")},
                          {"coaster_steel_top_night", v::text("#2A2E36")},
                          {"coaster_steel_side_night", v::text("#1A1D22")},
                          {"coaster_wood_top_day", v::text("#B9925E")},
                          {"coaster_wood_side_day", v::text("#8A6640")},
                          {"coaster_wood_top_night", v::text("#2E261C")},
                          {"coaster_wood_side_night", v::text("#1E1912")},
                          {"coaster_mono_top_day", v::text("#D6D2C8")},
                          {"coaster_mono_side_day", v::text("#8C877C")},
                          {"coaster_mono_top_night", v::text("#2A2A2A")},
                          {"coaster_mono_side_night", v::text("#1A1A1A")},
                          {"woods_green_1", v::text("#3F5E34")},
                          {"woods_green_2", v::text("#4A6538")},
                          {"woods_green_3", v::text("#435F3C")},
                          {"woods_green_4", v::text("#526A3A")},
                          {"woods_green_5", v::text("#4B5F33")}})},
        {"tree_forms", v::map({{"ulmus", v::text("vase")},
                               {"tilia", v::text("ovoid")},
                               {"platanus", v::text("broad")},
                               {"quercus", v::text("broad")},
                               {"aesculus", v::text("dome")},
                               {"acer", v::text("dome")},
                               {"populus", v::text("column")},
                               {"salix", v::text("weep")},
                               {"fraxinus", v::text("ovoid")},
                               {"betula", v::text("ovoid")},
                               {"robinia", v::text("broad")}})},
        {"canal_names", v::map({{"fill", v::text("#EAF2FA")},
                                {"opacity", v::real(0.85)},
                                {"weight", v::integer(700)},
                                {"size", v::real(3.2)},
                                {"spacing", v::real(0.3)}})},
        {"state", v::map({{"water_y", v::real(-1.5)},
                          {"quay_col", v::text("#6E5546")},
                          {"quay_course", v::real(0.0625)},
                          {"tone", v::boolean(false)},
                          {"win_style", v::null()}})},
        {"scene", v::map({})},
    };
}

/// The `style` bytes of `tables`.
[[nodiscard]] inline std::vector<std::byte> style_bytes(std::span<const values::Field> tables) {
    Sink s;
    s.u32(tiles::style_magic);
    s.u16(tiles::payload_version);
    s.varu(tables.size());
    for (const auto& [name, v] : tables) {
        s.utf8(name);
        v(s);
    }
    return s.bytes;
}

/// The `style` bytes of a test pack (style_tables()).
[[nodiscard]] inline std::vector<std::byte> style_bytes() { return style_bytes(style_tables()); }

/// What a test pack holds other than its own: another `reach` or `rhead`, none of `reach`, sections after the others.
struct PackOptions {
    std::optional<std::vector<std::byte>> reach;
    std::optional<std::vector<std::byte>> head;
    bool omit_reach = false;
    std::vector<SectionBytes> extra;
    /// Style tables after style_tables()'s (a region's `tradition`).
    std::vector<values::Field> style_tables;
};

/// A format-3 pack of `chunks`, with `rhead` for `levels`, as Format3Writer lays it out (D1's container).
[[nodiscard]] inline std::vector<std::byte> pack_bytes(std::span<const ChunkBytes> chunks,
                                                       std::span<const std::uint8_t> levels,
                                                       std::uint8_t format = tiles::format_render,
                                                       const PackOptions& options = {}) {
    Sink table;
    table.varu(chunks.size());
    std::vector<std::byte> stored;
    std::uint64_t raw_total = 0;
    for (const auto& c : chunks) {
        const std::vector<std::byte> z = zstd_frame(c.raw);
        table.u8(c.tile.z);
        table.varu(c.tile.x);
        table.varu(c.tile.y);
        table.varu(z.size());
        table.varu(c.raw.size());
        table.varu(c.count);
        const tiles::Sha256Digest d = tiles::Sha256::of(c.raw);
        table.raw(d);
        stored.insert(stored.end(), z.begin(), z.end());
        raw_total += c.raw.size();
    }
    std::vector<SectionBytes> sections;
    sections.push_back({"tiles", table.bytes, table.size(), tiles::Method::stored});
    if (format == tiles::format_render && !options.omit_reach) {
        const auto reach = options.reach.value_or(reach_bytes(chunks));
        sections.push_back({"reach", reach, reach.size(), tiles::Method::stored});
    }
    sections.push_back({"chunks", stored, format == tiles::format_render ? raw_total : stored.size(),
                        format == tiles::format_render ? tiles::Method::zstd : tiles::Method::stored});
    if (format == tiles::format_render) {
        const auto head = options.head.value_or(head_bytes(levels));
        sections.push_back({"rhead", head, head.size(), tiles::Method::stored});
        std::vector<values::Field> tables = style_tables();
        tables.insert(tables.end(), options.style_tables.begin(), options.style_tables.end());
        const auto style = style_bytes(tables);
        sections.push_back({"style", zstd_frame(style), style.size(), tiles::Method::zstd});
    }
    sections.insert(sections.end(), options.extra.begin(), options.extra.end());
    const std::string manifest = R"({"format":"atlas-pack","version":3,"layout":"render-tiles"})";
    auto directory = [&](std::uint64_t base) {
        Sink d;
        std::uint64_t offset = base;
        for (const auto& s : sections) {
            d.utf8(s.name);
            d.u64be(offset);
            d.u64be(s.stored.size());
            d.u64be(s.raw);
            d.u32be(tiles::crc32(s.stored));
            d.u8(static_cast<std::uint8_t>(s.method));
            offset += s.stored.size();
        }
        return d;
    };
    const std::size_t dir_length = directory(0).size();
    const Sink dir = directory(tiles::container_header_bytes + manifest.size() + dir_length);
    Sink out;
    out.raw(tiles::as_bytes("ATLASPCK"));
    out.u8(0);
    out.u8(format);
    out.u8(0);
    out.u8(0);
    out.u32be(manifest.size());
    out.u32be(dir.size());
    out.raw(tiles::as_bytes(manifest));
    out.raw(dir.bytes);
    for (const auto& s : sections) out.raw(s.stored);
    return out.bytes;
}

} // namespace atlas::test
