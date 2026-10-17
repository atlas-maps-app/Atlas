#include "tiles/Dump.hpp"

#include "tiles/Bytes.hpp"

#include <array>
#include <bit>
#include <charconv>
#include <string_view>

namespace atlas::tiles {
namespace {

/// Appends to a string.
class TextSink {
public:
    explicit TextSink(std::string& out) noexcept : out_(out) {}
    void put(std::string_view s) { out_.append(s); }
    void put(char c) { out_.push_back(c); }

private:
    std::string& out_;
};

/// Hashes through a buffer.
class HashSink {
public:
    void put(std::string_view s) {
        while (!s.empty()) {
            const std::size_t n = std::min(s.size(), buffer_.size() - fill_);
            std::ranges::copy(s.substr(0, n), buffer_.begin() + static_cast<std::ptrdiff_t>(fill_));
            fill_ += n;
            s.remove_prefix(n);
            if (fill_ == buffer_.size()) flush();
        }
    }
    void put(char c) { put(std::string_view(&c, 1)); }
    [[nodiscard]] Sha256Digest finish() {
        flush();
        return hash_.finish();
    }

private:
    void flush() {
        hash_.update(as_bytes(std::string_view(buffer_.data(), fill_)));
        fill_ = 0;
    }

    Sha256 hash_;
    std::array<char, 16384> buffer_{};
    std::size_t fill_ = 0;
};

template <class Sink>
void put_int(Sink& out, std::int64_t v) {
    std::array<char, 24> b{};
    const auto [end, ec] = std::to_chars(b.data(), b.data() + b.size(), v);
    out.put(std::string_view(b.data(), static_cast<std::size_t>(end - b.data())));
}

template <class Sink>
void put_uint(Sink& out, std::uint64_t v) {
    std::array<char, 24> b{};
    const auto [end, ec] = std::to_chars(b.data(), b.data() + b.size(), v);
    out.put(std::string_view(b.data(), static_cast<std::size_t>(end - b.data())));
}

/// A double as Kotlin's CanonicalDump writes it: "d" and Long.toHexString of its bits.
template <class Sink>
void put_double(Sink& out, std::uint64_t bits) {
    std::array<char, 24> b{};
    b[0] = 'd';
    const auto [end, ec] = std::to_chars(b.data() + 1, b.data() + b.size(), bits, 16);
    out.put(std::string_view(b.data(), static_cast<std::size_t>(end - b.data())));
}

template <class Sink>
void put_string(Sink& out, std::string_view s) {
    constexpr std::string_view hex = "0123456789abcdef";
    out.put('"');
    std::size_t run = 0; // characters written as they are, put in one piece
    for (std::size_t i = 0; i < s.size(); ++i) {
        const auto c = static_cast<unsigned char>(s[i]);
        if (c != '"' && c != '\\' && c >= 0x20U) continue;
        out.put(s.substr(run, i - run));
        run = i + 1;
        if (c == '"') {
            out.put("\\\"");
        } else if (c == '\\') {
            out.put("\\\\");
        } else {
            const std::array<char, 6> u = {'\\', 'u', '0', '0', hex[c >> 4U], hex[c & 0xFU]};
            out.put(std::string_view(u.data(), u.size()));
        }
    }
    out.put(s.substr(run));
    out.put('"');
}

template <class Sink>
void put_value(Sink& out, const Value& v) {
    switch (v.type()) {
    case ValueType::null: out.put("null"); return;
    case ValueType::boolean_false: out.put("false"); return;
    case ValueType::boolean_true: out.put("true"); return;
    case ValueType::int32:
    case ValueType::int64: put_int(out, v.integer().value_or(0)); return;
    case ValueType::real: put_double(out, std::bit_cast<std::uint64_t>(v.real().value_or(0.0))); return;
    case ValueType::string: put_string(out, v.string().value_or(std::string_view{})); return;
    case ValueType::list:
        out.put('<');
        for (std::size_t i = 0; i < v.size(); ++i) {
            if (i > 0) out.put(',');
            put_value(out, v.child(i));
        }
        out.put('>');
        return;
    case ValueType::map:
        out.put('{');
        for (std::size_t i = 0; i < v.size(); ++i) {
            if (i > 0) out.put(',');
            put_string(out, v.key(i));
            out.put(':');
            put_value(out, v.child(i));
        }
        out.put('}');
        return;
    }
}

/// Writes ` name=value` for feature `i`'s field in `slot`, unless it holds its column's default.
template <class Sink>
void put_field(Sink& out, const Layer& layer, std::uint8_t slot, std::size_t i) {
    const Column& c = layer.column(slot);
    const ColumnSpec& spec = layer.schema->columns[slot];
    auto name = [&] {
        out.put(' ');
        out.put(spec.name);
        out.put('=');
    };
    switch (spec.codec) {
    case Codec::u8:
    case Codec::varu:
    case Codec::varz:
    case Codec::delta: {
        const std::int64_t v = layer.integer(slot, i);
        if (v == 0) return;
        name();
        put_int(out, v);
        return;
    }
    case Codec::nvarz:
    case Codec::ndelta:
        if (const auto v = layer.nullable_integer(slot, i)) {
            name();
            put_int(out, *v);
        }
        return;
    case Codec::centi:
    case Codec::f64:
        if (const auto v = layer.decimal(slot, i)) {
            name();
            put_double(out, std::bit_cast<std::uint64_t>(*v));
        }
        return;
    case Codec::str:
        if (const auto v = layer.string(slot, i)) {
            name();
            put_string(out, *v);
        }
        return;
    case Codec::strn: {
        const StringOrNumber v = layer.string_or_number(slot, i);
        if (v.kind == StringOrNumber::Kind::null) return;
        name();
        if (v.kind == StringOrNumber::Kind::string) {
            put_string(out, v.text);
        } else {
            out.put('"');
            put_int(out, v.number);
            out.put('"');
        }
        return;
    }
    case Codec::boolean:
        if (const auto v = layer.boolean(slot, i)) {
            name();
            out.put(*v ? "true" : "false");
        }
        return;
    case Codec::geom: {
        const RingView ring = layer.ring(slot, i);
        if (ring.size() == 0 && !ring.has_keep) return;
        name();
        out.put('[');
        for (std::size_t k = 0; k < ring.size(); ++k) {
            if (k > 0) out.put(';');
            put_int(out, ring.lat[k]);
            out.put(',');
            put_int(out, ring.lon[k]);
            if (ring.has_keep) {
                out.put('/');
                put_int(out, ring.keep[k]);
            }
        }
        out.put(']');
        return;
    }
    case Codec::keep: return; // printed with its ring
    case Codec::point: {
        const GridPoint p = layer.point(slot, i);
        name();
        out.put('(');
        put_int(out, p.lat);
        out.put(',');
        put_int(out, p.lon);
        out.put(')');
        return;
    }
    case Codec::tags: {
        const std::size_t n = layer.tag_count(slot, i);
        if (n == 0) return;
        name();
        out.put('{');
        for (std::size_t k = 0; k < n; ++k) {
            if (k > 0) out.put(',');
            const auto [key, value] = layer.tag(slot, i, k);
            put_string(out, key);
            out.put(':');
            put_string(out, value);
        }
        out.put('}');
        return;
    }
    case Codec::ids: {
        const std::span<const std::int64_t> ids = layer.ids(slot, i);
        if (ids.empty()) return;
        name();
        out.put('<');
        for (std::size_t k = 0; k < ids.size(); ++k) {
            if (k > 0) out.put(',');
            put_int(out, ids[k]);
        }
        out.put('>');
        return;
    }
    case Codec::strs:
        if (const auto list = layer.string_list(slot, i)) {
            name();
            out.put('<');
            for (std::size_t k = 0; k < list->size(); ++k) {
                if (k > 0) out.put(',');
                put_string(out, layer.strings[(*list)[k]]);
            }
            out.put('>');
        }
        return;
    case Codec::value: {
        const Value v = layer.value(slot, i);
        if (v.is_null()) return;
        name();
        put_value(out, v);
        return;
    }
    }
    (void)c;
}

template <class Sink>
void write_tile(Sink& out, const Tile& tile) {
    const TileHeader& h = tile.header;
    out.put("tile ");
    put_uint(out, h.level);
    out.put('/');
    put_uint(out, h.x);
    out.put('/');
    put_uint(out, h.y);
    out.put(" n=");
    put_uint(out, h.cells_per_side);
    out.put('\n');
    for (std::size_t c = 0; c < tile.cells.size(); ++c) {
        std::uint64_t known = 0;
        for (const Layer& l : tile.layers) known += l.cell_first[c + 1] - l.cell_first[c];
        if (known == 0) continue; // a cell whose features all lie in layers this reader skips prints nothing
        const CellInfo& cell = tile.cells[c];
        out.put("cell ");
        put_uint(out, c);
        out.put(" box=");
        put_int(out, cell.south);
        out.put(',');
        put_int(out, cell.west);
        out.put(',');
        put_int(out, cell.north);
        out.put(',');
        put_int(out, cell.east);
        out.put(" top=");
        put_int(out, cell.top_cm);
        for (const Layer& l : tile.layers) {
            const std::uint32_t k = l.cell_first[c + 1] - l.cell_first[c];
            if (k == 0) continue;
            out.put(' ');
            out.put(l.schema->label);
            out.put('=');
            put_uint(out, k);
        }
        out.put('\n');
    }
    for (const Layer& l : tile.layers) {
        for (std::size_t c = 0; c + 1 < l.cell_first.size(); ++c) {
            for (std::uint32_t i = l.cell_first[c]; i < l.cell_first[c + 1]; ++i) {
                out.put(l.schema->label);
                out.put(" @");
                put_uint(out, c);
                for (const std::uint8_t slot : l.schema->dump_order) put_field(out, l, slot, i);
                out.put('\n');
            }
        }
    }
}

} // namespace

std::string canonical_dump(const Tile& tile) {
    std::string text;
    TextSink sink(text);
    write_tile(sink, tile);
    return text;
}

Sha256Digest canonical_hash(const Tile& tile) {
    HashSink sink;
    write_tile(sink, tile);
    return sink.finish();
}

} // namespace atlas::tiles
