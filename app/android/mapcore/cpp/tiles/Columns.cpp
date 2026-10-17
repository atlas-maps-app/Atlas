#include "tiles/Columns.hpp"

#include "tiles/Bytes.hpp"

#include <algorithm>
#include <array>
#include <limits>

namespace atlas::tiles {
namespace {

/// One column being decoded: its input, a reader over its bytes, and where its arrays go.
class Decoding {
public:
    Decoding(const ColumnInput& in, std::span<const std::byte> bytes, Arena& arena,
             TileDecoder::Scratch& scratch) noexcept
        : in(in), r(bytes), arena(arena), scratch(scratch) {}

    /// An error in this column: the reader's own fault when it failed, else `fault`.
    [[nodiscard]] std::unexpected<TileError> fail(Fault fault, std::uint64_t detail = 0) const noexcept {
        return std::unexpected(column_error(r.ok() ? fault : r.fault(), in.layer, in.name, detail));
    }

    /// The column, read whole: nothing failed and no byte is left over.
    [[nodiscard]] Result<Column> finish(Column c) const noexcept {
        if (!r.ok()) return fail(r.fault());
        if (!r.done()) return fail(Fault::left_over, r.remaining());
        c.codec = in.codec;
        c.present = true;
        return c;
    }

    template <class T>
    [[nodiscard]] std::span<T> array(std::size_t n) {
        return arena.allocate_array<T>(n);
    }

    template <class T>
    [[nodiscard]] std::span<const T> copy(const std::vector<T>& from) {
        const std::span<T> to = array<T>(from.size());
        std::ranges::copy(from, to.begin());
        return to;
    }

    /// A string index read as a varint count: within the tile's table.
    [[nodiscard]] std::uint32_t string_index() noexcept {
        const std::uint32_t i = r.count();
        if (r.ok() && i >= in.strings.size()) r.fail(Fault::bad_value);
        return i;
    }

    const ColumnInput& in; // NOLINT: a view for the decoders below
    Reader r;
    Arena& arena;                  // NOLINT
    TileDecoder::Scratch& scratch; // NOLINT
};

[[nodiscard]] bool fits_int32(std::int64_t v) noexcept {
    return v >= std::numeric_limits<std::int32_t>::min() && v <= std::numeric_limits<std::int32_t>::max();
}

[[nodiscard]] std::span<const std::uint8_t> as_u8(std::span<const std::byte> b) noexcept {
    return {reinterpret_cast<const std::uint8_t*>(b.data()), b.size()}; // NOLINT: bytes as bytes
}

Result<Column> decode_u8(Decoding& d) {
    Column c;
    c.flags = as_u8(d.r.bytes(d.in.count));
    return d.finish(c);
}

Result<Column> decode_boolean(Decoding& d) {
    Column c;
    c.flags = as_u8(d.r.bytes(d.in.count));
    if (const auto it = std::ranges::find_if(c.flags, [](std::uint8_t b) { return b > 2; }); it != c.flags.end()) {
        return d.fail(Fault::bad_value, *it);
    }
    return d.finish(c);
}

Result<Column> decode_integers(Decoding& d) {
    const std::span<std::int64_t> v = d.array<std::int64_t>(d.in.count);
    switch (d.in.codec) {
    case Codec::varu:
        for (auto& x : v) x = static_cast<std::int64_t>(d.r.varu());
        break;
    case Codec::varz:
        for (auto& x : v) x = d.r.varz();
        break;
    default: { // delta
        std::int64_t previous = 0;
        for (auto& x : v) x = previous = wrap_add(previous, d.r.varz());
    }
    }
    Column c;
    c.ints = v;
    return d.finish(c);
}

Result<Column> decode_nullable_integers(Decoding& d) {
    const std::span<std::int64_t> v = d.array<std::int64_t>(d.in.count);
    const std::span<std::uint8_t> present = d.array<std::uint8_t>(d.in.count);
    std::int64_t previous = 0;
    for (std::size_t i = 0; i < v.size(); ++i) {
        const std::uint64_t t = d.r.varu();
        present[i] = t != 0 ? 1 : 0;
        if (t == 0) {
            v[i] = 0;
        } else if (d.in.codec == Codec::nvarz) {
            v[i] = Reader::unzigzag(t - 1);
        } else { // ndelta
            v[i] = previous = wrap_add(previous, Reader::unzigzag(t - 1));
        }
    }
    Column c;
    c.ints = v;
    c.flags = present;
    return d.finish(c);
}

Result<Column> decode_centi(Decoding& d) {
    const std::span<double> v = d.array<double>(d.in.count);
    const std::span<std::uint8_t> present = d.array<std::uint8_t>(d.in.count);
    for (std::size_t i = 0; i < v.size(); ++i) {
        const std::uint64_t t = d.r.varu();
        switch (t & 3U) {
        case 0: // hundredths, exactly: n / 100.0 as Kotlin divides
            v[i] = static_cast<double>(Reader::unzigzag(t >> 2U)) / 100.0;
            present[i] = 1;
            break;
        case 1:
            if (t != 1) return d.fail(Fault::bad_value, t);
            v[i] = 0.0;
            present[i] = 0;
            break;
        case 2:
            if (t != 2) return d.fail(Fault::bad_value, t);
            v[i] = d.r.f64le();
            present[i] = 1;
            break;
        default: return d.fail(Fault::bad_value, t);
        }
    }
    Column c;
    c.reals = v;
    c.flags = present;
    return d.finish(c);
}

Result<Column> decode_f64(Decoding& d) {
    const std::span<double> v = d.array<double>(d.in.count);
    for (auto& x : v) x = d.r.f64le();
    Column c;
    c.reals = v;
    return d.finish(c);
}

Result<Column> decode_str(Decoding& d) {
    const std::span<std::uint32_t> v = d.array<std::uint32_t>(d.in.count);
    for (auto& x : v) {
        const std::uint64_t t = d.r.varu();
        if (t != 0 && t - 1 >= d.in.strings.size()) return d.fail(Fault::bad_value, t);
        x = static_cast<std::uint32_t>(t);
    }
    Column c;
    c.refs = v;
    return d.finish(c);
}

Result<Column> decode_strn(Decoding& d) {
    const std::span<std::int64_t> v = d.array<std::int64_t>(d.in.count);
    const std::span<std::uint8_t> kind = d.array<std::uint8_t>(d.in.count);
    std::int64_t previous = 0;
    for (std::size_t i = 0; i < v.size(); ++i) {
        const std::uint64_t t = d.r.varu();
        if (t == 0) {
            kind[i] = 0;
            v[i] = 0;
        } else if ((t & 1U) != 0) { // a string of the table
            const std::uint64_t index = (t - 1) >> 1U;
            if (index >= d.in.strings.size()) return d.fail(Fault::bad_value, t);
            kind[i] = 1;
            v[i] = static_cast<std::int64_t>(index);
        } else { // a number, as a delta from the column's previous number
            previous = wrap_add(previous, Reader::unzigzag((t - 2) >> 1U));
            if (previous < 0) return d.fail(Fault::bad_value, t);
            kind[i] = 2;
            v[i] = previous;
        }
    }
    Column c;
    c.ints = v;
    c.flags = kind;
    return d.finish(c);
}

Result<Column> decode_geom(Decoding& d) {
    Reader counts = d.r.block();
    Reader kinds = d.r.block();
    Reader jumps = d.r.block();
    std::array<Reader, 4> planes = {d.r.block(), d.r.block(), d.r.block(), d.r.block()};
    Reader escapes = d.r.block();
    Reader first_refs = d.r.block();
    Reader next_refs = d.r.block();
    if (!d.r.ok()) return d.fail(d.r.fault());

    const std::span<std::uint32_t> offsets = d.array<std::uint32_t>(std::size_t{d.in.count} + 1);
    offsets[0] = 0;
    std::uint64_t total = 0;
    for (std::uint32_t f = 0; f < d.in.count; ++f) {
        total += counts.count();
        if (total > kinds.remaining()) return d.fail(Fault::bad_count, total); // a kind byte per vertex
        offsets[f + 1] = static_cast<std::uint32_t>(total);
    }
    if (!counts.ok()) return d.fail(counts.fault());

    const std::span<std::int32_t> lat = d.array<std::int32_t>(total);
    const std::span<std::int32_t> lon = d.array<std::int32_t>(total);
    auto plane = [&escapes](Reader& lo, Reader& hi) {
        const std::uint64_t z0 = std::uint64_t{lo.u8()} | (std::uint64_t{hi.u8()} << 8U);
        return Reader::unzigzag(z0 == 0xFFFFU ? escapes.varu() : z0);
    };
    std::int64_t last_lat = 0;
    std::int64_t last_lon = 0;
    std::uint32_t k = 0; // the column's vertex
    for (std::uint32_t f = 0; f < d.in.count; ++f) {
        const std::uint32_t n = offsets[f + 1] - offsets[f];
        std::int64_t previous_ref = -1;
        for (std::uint32_t i = 0; i < n; ++i, ++k) {
            const std::uint8_t kind = kinds.u8();
            if (kind == 1) { // a repeat of an earlier vertex of the column
                std::int64_t t = 0;
                if (previous_ref >= 0) {
                    // its vertex minus (the previous repeat's − 1): within [0, k)
                    const std::int64_t delta = next_refs.varz();
                    if (delta < 1 - previous_ref || delta >= std::int64_t{k} - previous_ref + 1) {
                        return d.fail(Fault::bad_value, k);
                    }
                    t = previous_ref - 1 + delta;
                } else {
                    const std::uint64_t back = first_refs.varu();
                    if (back < 1 || back > k) return d.fail(Fault::bad_value, k);
                    t = std::int64_t{k} - static_cast<std::int64_t>(back);
                }
                lat[k] = lat[static_cast<std::size_t>(t)];
                lon[k] = lon[static_cast<std::size_t>(t)];
                previous_ref = t;
            } else if (kind == 0) { // a new vertex
                std::int64_t a = 0;
                std::int64_t o = 0;
                if (i == 0) {
                    a = wrap_add(last_lat, jumps.varz());
                    o = wrap_add(last_lon, jumps.varz());
                } else {
                    a = wrap_add(last_lat, plane(planes[0], planes[1]));
                    o = wrap_add(last_lon, plane(planes[2], planes[3]));
                }
                if (!fits_int32(a) || !fits_int32(o)) return d.fail(Fault::overflow, k);
                lat[k] = static_cast<std::int32_t>(a);
                lon[k] = static_cast<std::int32_t>(o);
                previous_ref = -1;
            } else {
                return d.fail(Fault::bad_value, kind);
            }
            last_lat = lat[k];
            last_lon = lon[k];
        }
    }
    for (const Reader* b :
         {&counts, &kinds, &jumps, &planes[0], &planes[1], &planes[2], &planes[3], &escapes, &first_refs, &next_refs}) {
        if (!b->ok()) return d.fail(b->fault());
        if (!b->done()) return d.fail(Fault::left_over, b->remaining());
    }
    Column c;
    c.offsets = offsets;
    c.lat = lat;
    c.lon = lon;
    return d.finish(c);
}

Result<Column> decode_keep(Decoding& d, const Column* ring) {
    std::span<const std::uint32_t> offsets;
    if (ring != nullptr && ring->present) {
        offsets = ring->offsets;
    } else {
        const std::span<std::uint32_t> zeros = d.array<std::uint32_t>(std::size_t{d.in.count} + 1);
        std::ranges::fill(zeros, 0U);
        offsets = zeros;
    }
    const std::span<std::uint8_t> codes = d.array<std::uint8_t>(offsets[d.in.count]);
    for (std::uint32_t f = 0; f < d.in.count; ++f) {
        const std::span<std::uint8_t> dst = codes.subspan(offsets[f], offsets[f + 1] - offsets[f]);
        if (dst.size() < keep::min_points) { // simplify never touches a small ring: every vertex at every tolerance
            std::ranges::fill(dst, keep::all);
            continue;
        }
        const std::span<const std::uint8_t> src = as_u8(d.r.bytes(dst.size()));
        if (src.size() != dst.size()) return d.fail(Fault::truncated, f);
        for (std::size_t j = 0; j < src.size(); ++j) {
            if (src[j] > keep::max_code) return d.fail(Fault::bad_value, src[j]);
            dst[j] = src[j];
        }
    }
    Column c;
    c.offsets = offsets;
    c.flags = codes;
    return d.finish(c);
}

Result<Column> decode_point(Decoding& d) {
    Reader lat_block = d.r.block();
    Reader lon_block = d.r.block();
    const std::span<std::int32_t> lat = d.array<std::int32_t>(d.in.count);
    const std::span<std::int32_t> lon = d.array<std::int32_t>(d.in.count);
    std::int64_t a = 0;
    std::int64_t o = 0;
    for (std::uint32_t i = 0; i < d.in.count; ++i) {
        a = wrap_add(a, lat_block.varz());
        o = wrap_add(o, lon_block.varz());
        if (!fits_int32(a) || !fits_int32(o)) return d.fail(Fault::overflow, i);
        lat[i] = static_cast<std::int32_t>(a);
        lon[i] = static_cast<std::int32_t>(o);
    }
    for (const Reader* b : {&lat_block, &lon_block}) {
        if (!b->ok()) return d.fail(b->fault());
        if (!b->done()) return d.fail(Fault::left_over, b->remaining());
    }
    Column c;
    c.lat = lat;
    c.lon = lon;
    return d.finish(c);
}

/// Whether the keys of the pairs from `first` on (key, value, key, value, ...) are all different strings.
[[nodiscard]] bool unique_keys(const std::vector<std::uint32_t>& pairs, std::size_t first,
                               std::span<const std::string_view> strings, std::vector<std::uint32_t>& scratch) {
    const std::size_t n = (pairs.size() - first) / 2;
    if (n <= 16) {
        for (std::size_t i = 1; i < n; ++i) {
            for (std::size_t j = 0; j < i; ++j) {
                if (strings[pairs[first + (2 * i)]] == strings[pairs[first + (2 * j)]]) return false;
            }
        }
        return true;
    }
    scratch.clear();
    for (std::size_t i = 0; i < n; ++i) scratch.push_back(pairs[first + (2 * i)]);
    std::ranges::sort(scratch, [&](std::uint32_t a, std::uint32_t b) { return strings[a] < strings[b]; });
    return std::ranges::adjacent_find(
               scratch, [&](std::uint32_t a, std::uint32_t b) { return strings[a] == strings[b]; }) == scratch.end();
}

Result<Column> decode_tags(Decoding& d) {
    const std::span<std::uint32_t> offsets = d.array<std::uint32_t>(std::size_t{d.in.count} + 1);
    auto& pairs = d.scratch.refs;
    pairs.clear();
    offsets[0] = 0;
    for (std::uint32_t i = 0; i < d.in.count; ++i) {
        const std::uint32_t n = d.r.count();
        if (n > d.r.remaining()) return d.fail(Fault::bad_count, n);
        const std::size_t first = pairs.size();
        for (std::uint32_t k = 0; k < n; ++k) {
            pairs.push_back(d.string_index()); // the key, then its value
            pairs.push_back(d.string_index());
        }
        if (!d.r.ok()) return d.fail(d.r.fault(), i);
        if (!unique_keys(pairs, first, d.in.strings, d.scratch.keys)) return d.fail(Fault::duplicate, i);
        offsets[i + 1] = static_cast<std::uint32_t>(pairs.size() / 2);
    }
    Column c;
    c.offsets = offsets;
    c.refs = d.copy(pairs);
    return d.finish(c);
}

Result<Column> decode_ids(Decoding& d) {
    const std::span<std::uint32_t> offsets = d.array<std::uint32_t>(std::size_t{d.in.count} + 1);
    auto& items = d.scratch.ints;
    items.clear();
    offsets[0] = 0;
    for (std::uint32_t i = 0; i < d.in.count; ++i) {
        const std::uint32_t n = d.r.count();
        if (n > d.r.remaining()) return d.fail(Fault::bad_count, n);
        std::int64_t previous = 0;
        for (std::uint32_t k = 0; k < n; ++k) items.push_back(previous = wrap_add(previous, d.r.varz()));
        offsets[i + 1] = static_cast<std::uint32_t>(items.size());
    }
    Column c;
    c.offsets = offsets;
    c.ints = d.copy(items);
    return d.finish(c);
}

Result<Column> decode_strs(Decoding& d) {
    const std::span<std::uint32_t> offsets = d.array<std::uint32_t>(std::size_t{d.in.count} + 1);
    const std::span<std::uint8_t> present = d.array<std::uint8_t>(d.in.count);
    auto& items = d.scratch.refs;
    items.clear();
    offsets[0] = 0;
    for (std::uint32_t i = 0; i < d.in.count; ++i) {
        const std::uint64_t t = d.r.varu();
        present[i] = t != 0 ? 1 : 0;
        if (t != 0) {
            const std::uint64_t n = t - 1;
            if (n > max_count) return d.fail(Fault::overflow, t);
            if (n > d.r.remaining()) return d.fail(Fault::bad_count, n);
            for (std::uint64_t k = 0; k < n; ++k) items.push_back(d.string_index());
        }
        offsets[i + 1] = static_cast<std::uint32_t>(items.size());
    }
    Column c;
    c.offsets = offsets;
    c.flags = present;
    c.refs = d.copy(items);
    return d.finish(c);
}

Result<Column> decode_values(Decoding& d) {
    ValueReader& values = d.scratch.values;
    values.reset(d.in.strings, false);
    const std::span<std::uint32_t> roots = d.array<std::uint32_t>(d.in.count);
    for (std::uint32_t i = 0; i < d.in.count; ++i) {
        const auto root = values.read(d.r);
        if (!root) return d.fail(root.error().fault, i);
        roots[i] = *root;
    }
    const std::span<const ValueNode> nodes = values.nodes();
    const std::span<ValueNode> kept = d.array<ValueNode>(nodes.size());
    std::ranges::copy(nodes, kept.begin());
    Column c;
    c.refs = roots;
    c.nodes = kept;
    return d.finish(c);
}

} // namespace

Result<Column> decode_column(const ColumnInput& in, std::span<const std::byte> bytes, const Column* ring, Arena& arena,
                             TileDecoder::Scratch& scratch) {
    Decoding d(in, bytes, arena, scratch);
    // Every codec but keep codes takes a byte a feature at least (a double eight): a column too short for its features
    // is refused before its arrays are made, so a small damaged tile cannot ask for large ones.
    const std::uint64_t least = in.codec == Codec::keep ? 0 : (in.codec == Codec::f64 ? 8U : 1U);
    if (std::uint64_t{in.count} * least > bytes.size()) return d.fail(Fault::truncated, in.count);
    switch (in.codec) {
    case Codec::u8: return decode_u8(d);
    case Codec::boolean: return decode_boolean(d);
    case Codec::varu:
    case Codec::varz:
    case Codec::delta: return decode_integers(d);
    case Codec::nvarz:
    case Codec::ndelta: return decode_nullable_integers(d);
    case Codec::centi: return decode_centi(d);
    case Codec::f64: return decode_f64(d);
    case Codec::str: return decode_str(d);
    case Codec::strn: return decode_strn(d);
    case Codec::geom: return decode_geom(d);
    case Codec::keep: return decode_keep(d, ring);
    case Codec::point: return decode_point(d);
    case Codec::tags: return decode_tags(d);
    case Codec::ids: return decode_ids(d);
    case Codec::strs: return decode_strs(d);
    case Codec::value: return decode_values(d);
    }
    return std::unexpected(column_error(Fault::bad_value, in.layer, in.name, static_cast<std::uint64_t>(in.codec)));
}

} // namespace atlas::tiles
