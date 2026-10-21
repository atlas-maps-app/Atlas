// The render tile's writer (tiles/TileWriter.hpp).
#include "tiles/TileWriter.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "tiles/Bytes.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Sink.hpp"
#include "tiles/TileBox.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::tiles {

namespace {

/// Kotlin's Long arithmetic: wraps.
[[nodiscard]] constexpr std::int64_t wrap_sub(std::int64_t a, std::int64_t b) noexcept {
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) - static_cast<std::uint64_t>(b));
}

/// The strings of the tile being written, in the order the columns use them (Format3Writer's StringTable): by their
/// text, so a column's name and a value with the same text are one string.
class Table {
public:
    std::uint32_t of(std::string_view s) {
        const auto [it, added] = index_.try_emplace(s, static_cast<std::uint32_t>(list_.size()));
        if (added) list_.push_back(s);
        return it->second;
    }
    [[nodiscard]] std::span<const std::string_view> list() const noexcept { return list_; }

private:
    std::unordered_map<std::string_view, std::uint32_t> index_;
    std::vector<std::string_view> list_;
};

/// What the encoder reads of one column, a default for the features past its arrays.
class Values {
public:
    explicit Values(const ColumnFill& c) noexcept : c_(&c) {}

    [[nodiscard]] std::int64_t integer(std::size_t f) const noexcept { return f < c_->ints.size() ? c_->ints[f] : 0; }
    [[nodiscard]] std::uint8_t flag(std::size_t f) const noexcept { return f < c_->flags.size() ? c_->flags[f] : 0; }
    [[nodiscard]] double real(std::size_t f) const noexcept { return f < c_->reals.size() ? c_->reals[f] : 0.0; }
    [[nodiscard]] FillSpan span(std::size_t f) const noexcept {
        return f < c_->spans.size() ? c_->spans[f] : FillSpan{};
    }
    /// A str or strn column's string, or StringPool::none for null.
    [[nodiscard]] StringPool::Id ref(std::size_t f) const noexcept {
        return f < c_->refs.size() ? c_->refs[f] : StringPool::none;
    }

private:
    const ColumnFill* c_;
};

/// A per-feature array's values of the first `n` features (fewer when the rest are its default).
template <class T>
[[nodiscard]] std::span<const T> first(const std::vector<T>& v, std::size_t n) noexcept {
    return std::span(v).first(std::min(v.size(), n));
}

[[nodiscard]] std::unexpected<TileError> refuse(Fault fault, const LayerFill& layer, std::uint8_t slot,
                                                std::uint64_t detail = 0) noexcept {
    return std::unexpected(column_error(fault, layer.id(), layer.schema().columns[slot].name, detail));
}

/// Whether a column is left out: its every value is its codec's default (Format3Writer.omitted). Refuses a point or
/// double column with a feature unset, and keep codes on some rings but not on others.
[[nodiscard]] Result<bool> omitted(const LayerFill& layer, std::uint8_t slot) {
    const Codec codec = layer.schema().columns[slot].codec;
    const std::uint32_t n = layer.count();
    if (codec == Codec::keep) {
        const std::uint8_t ring = layer.schema().keep_of[slot];
        const Values v(layer.column(ring));
        std::uint32_t coded = 0;
        bool any = false;
        for (std::uint32_t f = 0; f < n; ++f) {
            if (v.flag(f) == 2) ++coded;
            if (v.span(f).count > 0) any = true;
        }
        if (coded != 0 && coded != n) return refuse(Fault::bad_count, layer, slot, coded);
        return coded == 0 || !any;
    }
    const ColumnFill& c = layer.column(slot);
    const auto all_flags_zero = [&] {
        return std::ranges::all_of(first(c.flags, n), [](std::uint8_t b) { return b == 0; });
    };
    switch (codec) {
    case Codec::point:
    case Codec::f64:
        for (std::uint32_t f = 0; f < n; ++f) {
            if (f >= c.flags.size() || c.flags[f] == 0) return refuse(Fault::missing, layer, slot, f);
        }
        return false;
    case Codec::geom:
    case Codec::tags:
    case Codec::ids: return std::ranges::all_of(first(c.spans, n), [](const FillSpan& s) { return s.count == 0; });
    case Codec::u8:
    case Codec::varu:
    case Codec::varz:
    case Codec::delta: return std::ranges::all_of(first(c.ints, n), [](std::int64_t x) { return x == 0; });
    case Codec::str:
        return std::ranges::all_of(first(c.refs, n), [](StringPool::Id id) { return id == StringPool::none; });
    case Codec::centi:
    case Codec::nvarz:
    case Codec::ndelta:
    case Codec::boolean:
    case Codec::strn:
    case Codec::strs: return all_flags_zero();
    case Codec::keep:
    case Codec::value: break;
    }
    return true; // a value column: no layer of this payload has one (LayerFill refuses to fill it)
}

/// The geometry column (Geometry.encode): each vertex a neighbour shares written once.
void encode_geom(const LayerFill& layer, const ColumnFill& c, Sink& out) {
    Sink counts;
    Sink kinds;
    Sink jumps;
    std::array<Sink, 4> planes;
    Sink escapes;
    Sink first_refs;
    Sink next_refs;
    std::unordered_map<std::uint64_t, std::uint32_t> seen;
    std::uint32_t k = 0;
    std::int64_t last_lat = 0;
    std::int64_t last_lon = 0;
    constexpr std::uint64_t escape = 0xFFFFU;
    const auto plane = [&](std::int64_t v, Sink& lo, Sink& hi) {
        const std::uint64_t z = Sink::zigzag(v);
        if (z < escape) {
            lo.u8(z & 0xFFU);
            hi.u8(z >> 8U);
        } else {
            lo.u8(0xFF);
            hi.u8(0xFF);
            escapes.varu(z);
        }
    };
    const Values values(c);
    for (std::uint32_t f = 0; f < layer.count(); ++f) {
        const FillSpan s = values.span(f);
        counts.varu(s.count);
        std::int64_t previous_ref = -1;
        for (std::uint32_t i = 0; i < s.count; ++i) {
            const std::int64_t lat = c.lat[s.first + i];
            const std::int64_t lon = c.lon[s.first + i];
            const std::uint64_t key =
                (static_cast<std::uint64_t>(lat) << 32U) | (static_cast<std::uint64_t>(lon) & 0xFFFF'FFFFU);
            if (const auto it = seen.find(key); it != seen.end()) {
                kinds.u8(1);
                if (previous_ref >= 0) {
                    next_refs.varz(std::int64_t{it->second} - (previous_ref - 1));
                } else {
                    first_refs.varu(k - it->second);
                }
                previous_ref = it->second;
                it->second = k;
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
                seen.emplace(key, k);
            }
            ++k;
            last_lat = lat;
            last_lon = lon;
        }
    }
    out.block(counts);
    out.block(kinds);
    out.block(jumps);
    for (const Sink& p : planes) out.block(p);
    out.block(escapes);
    out.block(first_refs);
    out.block(next_refs);
}

/// One column's bytes (Columns.encode), its strings entered in the table as they are met.
[[nodiscard]] Result<void> encode_column(const LayerFill& layer, std::uint8_t slot, const StringPool& pool,
                                         Table& table, Sink& out) {
    const Codec codec = layer.schema().columns[slot].codec;
    const std::uint32_t n = layer.count();
    const ColumnFill& c = layer.column(slot);
    const Values v(c);
    switch (codec) {
    case Codec::u8:
        for (std::uint32_t f = 0; f < n; ++f) {
            const std::int64_t x = v.integer(f);
            if (x < 0 || x > 0xFF) return refuse(Fault::bad_value, layer, slot, static_cast<std::uint64_t>(x));
            out.u8(static_cast<std::uint64_t>(x));
        }
        break;
    case Codec::varu:
        for (std::uint32_t f = 0; f < n; ++f) {
            const std::int64_t x = v.integer(f);
            if (x < 0) return refuse(Fault::bad_value, layer, slot, static_cast<std::uint64_t>(x));
            out.varu(static_cast<std::uint64_t>(x));
        }
        break;
    case Codec::varz:
        for (std::uint32_t f = 0; f < n; ++f) out.varz(v.integer(f));
        break;
    case Codec::delta: {
        std::int64_t previous = 0;
        for (std::uint32_t f = 0; f < n; ++f) {
            const std::int64_t x = v.integer(f);
            out.varz(wrap_sub(x, previous));
            previous = x;
        }
        break;
    }
    case Codec::centi:
        for (std::uint32_t f = 0; f < n; ++f) {
            const double x = v.real(f);
            if (v.flag(f) == 0) {
                out.varu(1);
            } else if (is_centi(x)) {
                out.varu(Sink::zigzag(pymath::java::round(x * 100)) << 2U);
            } else {
                out.varu(2);
                out.f64(x);
            }
        }
        break;
    case Codec::str:
        for (std::uint32_t f = 0; f < n; ++f) {
            const StringPool::Id id = v.ref(f);
            out.varu(id != StringPool::none ? std::uint64_t{table.of(pool.at(id))} + 1 : 0);
        }
        break;
    case Codec::strn: {
        std::int64_t previous = 0;
        for (std::uint32_t f = 0; f < n; ++f) {
            switch (v.flag(f)) {
            case 0: out.varu(0); break;
            case 2: {
                const std::int64_t x = v.integer(f);
                const std::int64_t d = wrap_sub(x, previous);
                previous = x;
                out.varu((2 * Sink::zigzag(d)) + 2);
                break;
            }
            default: out.varu((2 * std::uint64_t{table.of(pool.at(v.ref(f)))}) + 1);
            }
        }
        break;
    }
    case Codec::boolean:
        for (std::uint32_t f = 0; f < n; ++f) out.u8(v.flag(f));
        break;
    case Codec::geom: encode_geom(layer, c, out); break;
    case Codec::keep: {
        const ColumnFill& ring = layer.column(layer.schema().keep_of[slot]);
        const Values r(ring);
        for (std::uint32_t f = 0; f < n; ++f) {
            const FillSpan s = r.span(f);
            const auto codes = std::span(ring.keep).subspan(s.first, s.count);
            if (s.count < keep::min_points) {
                // simplify never touches a small ring: its codes are not written, and must be every tolerance's
                if (std::ranges::any_of(codes, [](std::uint8_t k) { return k != keep::all; })) {
                    return refuse(Fault::bad_value, layer, slot, f);
                }
                continue;
            }
            for (const std::uint8_t k : codes) {
                if (k > keep::max_code) return refuse(Fault::bad_value, layer, slot, k);
                out.u8(k);
            }
        }
        break;
    }
    case Codec::point: {
        Sink lat;
        Sink lon;
        std::int64_t a = 0;
        std::int64_t o = 0;
        for (std::uint32_t f = 0; f < n; ++f) {
            lat.varz(std::int64_t{c.lat[f]} - a);
            lon.varz(std::int64_t{c.lon[f]} - o);
            a = c.lat[f];
            o = c.lon[f];
        }
        out.block(lat);
        out.block(lon);
        break;
    }
    case Codec::nvarz:
        for (std::uint32_t f = 0; f < n; ++f) {
            if (v.flag(f) == 0) {
                out.varu(0);
                continue;
            }
            const std::int64_t x = v.integer(f);
            if (x == std::numeric_limits<std::int64_t>::min()) return refuse(Fault::overflow, layer, slot, f);
            out.varu(Sink::zigzag(x) + 1);
        }
        break;
    case Codec::ndelta: {
        std::int64_t previous = 0;
        for (std::uint32_t f = 0; f < n; ++f) {
            if (v.flag(f) == 0) {
                out.varu(0);
                continue;
            }
            const std::int64_t x = v.integer(f);
            const std::int64_t d = wrap_sub(x, previous);
            if (d == std::numeric_limits<std::int64_t>::min()) return refuse(Fault::overflow, layer, slot, f);
            previous = x;
            out.varu(Sink::zigzag(d) + 1);
        }
        break;
    }
    case Codec::tags:
        for (std::uint32_t f = 0; f < n; ++f) {
            const FillSpan s = v.span(f);
            out.varu(s.count);
            for (std::uint32_t k = 0; k < s.count; ++k) {
                out.varu(table.of(pool.at(c.strings[s.first + (2 * k)])));
                out.varu(table.of(pool.at(c.strings[s.first + (2 * k) + 1])));
            }
        }
        break;
    case Codec::ids:
        for (std::uint32_t f = 0; f < n; ++f) {
            const FillSpan s = v.span(f);
            out.varu(s.count);
            std::int64_t previous = 0;
            for (std::uint32_t k = 0; k < s.count; ++k) {
                const std::int64_t x = c.items[s.first + k];
                out.varz(wrap_sub(x, previous));
                previous = x;
            }
        }
        break;
    case Codec::strs:
        for (std::uint32_t f = 0; f < n; ++f) {
            if (v.flag(f) == 0) {
                out.varu(0);
                continue;
            }
            const FillSpan s = v.span(f);
            out.varu(std::uint64_t{s.count} + 1);
            for (std::uint32_t k = 0; k < s.count; ++k) out.varu(table.of(pool.at(c.strings[s.first + k])));
        }
        break;
    case Codec::f64:
        for (std::uint32_t f = 0; f < n; ++f) out.f64(c.reals[f]);
        break;
    case Codec::value: return refuse(Fault::unsupported, layer, slot);
    }
    return {};
}

} // namespace

// ---------------------------------------------------------------- the strings

StringPool::Id StringPool::intern(std::string_view s) {
    if (const auto it = index_.find(s); it != index_.end()) return it->second;
    const auto id = static_cast<Id>(strings_.size());
    ATLAS_ASSERT(id != none, "a tile with more strings than a pool holds");
    const std::string& kept = strings_.emplace_back(s);
    index_.emplace(kept, id);
    return id;
}

// ---------------------------------------------------------------- a layer

LayerFill::LayerFill(LayerId id, StringPool& strings) : id_(id), schema_(schema_of(id)), strings_(&strings) {
    ATLAS_ASSERT(schema_ != nullptr, "a layer the payload does not define");
    columns_.resize(schema_->columns.size());
}

std::uint32_t LayerFill::add(std::uint16_t cell) {
    ATLAS_ASSERT(cells_.empty() || cell >= cells_.back(), "a layer's features out of their cells' order");
    cells_.push_back(cell);
    return static_cast<std::uint32_t>(cells_.size() - 1);
}

ColumnFill& LayerFill::at(std::uint8_t slot, std::uint32_t f, std::initializer_list<Codec> codecs) {
    ATLAS_ASSERT(slot < columns_.size(), "a column the layer's schema lacks");
    ATLAS_ASSERT(f < count(), "a value for a feature not added");
    const Codec codec = schema_->columns[slot].codec;
    ATLAS_ASSERT(std::ranges::find(codecs, codec) != codecs.end(), "a value of another codec than its column's");
    ColumnFill& c = columns_[slot];
    const std::size_t n = std::size_t{f} + 1;
    const auto grow = [n](auto& v, auto value) {
        if (v.size() < n) v.resize(n, value);
    };
    switch (codec) {
    case Codec::u8:
    case Codec::varu:
    case Codec::varz:
    case Codec::delta: grow(c.ints, std::int64_t{0}); break;
    case Codec::nvarz:
    case Codec::ndelta:
        grow(c.ints, std::int64_t{0});
        grow(c.flags, std::uint8_t{0});
        break;
    case Codec::centi:
    case Codec::f64:
        grow(c.reals, 0.0);
        grow(c.flags, std::uint8_t{0});
        break;
    case Codec::str: grow(c.refs, StringPool::none); break;
    case Codec::strn:
        grow(c.flags, std::uint8_t{0});
        grow(c.ints, std::int64_t{0});
        grow(c.refs, StringPool::none);
        break;
    case Codec::boolean: grow(c.flags, std::uint8_t{0}); break;
    case Codec::geom:
        grow(c.spans, FillSpan{});
        grow(c.flags, std::uint8_t{0});
        break;
    case Codec::point:
        grow(c.lat, std::int32_t{0});
        grow(c.lon, std::int32_t{0});
        grow(c.flags, std::uint8_t{0});
        break;
    case Codec::tags:
    case Codec::ids: grow(c.spans, FillSpan{}); break;
    case Codec::strs:
        grow(c.spans, FillSpan{});
        grow(c.flags, std::uint8_t{0});
        break;
    case Codec::keep:
    case Codec::value: ATLAS_ASSERT(false, "keep codes come with their ring, and no layer has a value column");
    }
    return c;
}

void LayerFill::integer(std::uint8_t slot, std::uint32_t f, std::int64_t v) {
    at(slot, f, {Codec::u8, Codec::varu, Codec::varz, Codec::delta}).ints[f] = v;
}

void LayerFill::nullable_integer(std::uint8_t slot, std::uint32_t f, std::optional<std::int64_t> v) {
    ColumnFill& c = at(slot, f, {Codec::nvarz, Codec::ndelta});
    c.ints[f] = v.value_or(0);
    c.flags[f] = v ? 1 : 0;
}

void LayerFill::decimal(std::uint8_t slot, std::uint32_t f, std::optional<double> v) {
    ColumnFill& c = at(slot, f, {Codec::centi, Codec::f64});
    ATLAS_ASSERT(v.has_value() || schema_->columns[slot].codec == Codec::centi, "a double column holds no null");
    c.reals[f] = v.value_or(0.0);
    c.flags[f] = v ? 1 : 0;
}

void LayerFill::string(std::uint8_t slot, std::uint32_t f, std::optional<std::string_view> v) {
    at(slot, f, {Codec::str}).refs[f] = v ? strings_->intern(*v) : StringPool::none;
}

void LayerFill::string_or_number(std::uint8_t slot, std::uint32_t f, std::optional<std::string_view> v) {
    ColumnFill& c = at(slot, f, {Codec::strn});
    c.flags[f] = 0;
    c.ints[f] = 0;
    c.refs[f] = StringPool::none;
    if (!v) return;
    if (const auto number = canonical_int(*v)) {
        c.flags[f] = 2;
        c.ints[f] = *number;
    } else {
        c.flags[f] = 1;
        c.refs[f] = strings_->intern(*v);
    }
}

void LayerFill::boolean(std::uint8_t slot, std::uint32_t f, std::optional<bool> v) {
    at(slot, f, {Codec::boolean}).flags[f] = !v ? 0 : (*v ? 2 : 1);
}

void LayerFill::ring(std::uint8_t slot, std::uint32_t f, std::span<const std::int32_t> lat,
                     std::span<const std::int32_t> lon, std::optional<std::span<const std::uint8_t>> keep) {
    ColumnFill& c = at(slot, f, {Codec::geom});
    ATLAS_ASSERT(lat.size() == lon.size(), "a ring of other numbers of latitudes and longitudes");
    ATLAS_ASSERT(!keep || keep->size() == lat.size(), "keep codes other than one a vertex");
    ATLAS_ASSERT(!keep || schema_->keep_for[slot] != 0xFF, "keep codes for a ring whose layer keeps none");
    c.spans[f] = {.first = static_cast<std::uint32_t>(c.lat.size()), .count = static_cast<std::uint32_t>(lat.size())};
    c.lat.insert(c.lat.end(), lat.begin(), lat.end());
    c.lon.insert(c.lon.end(), lon.begin(), lon.end());
    if (keep) {
        c.keep.resize(c.lat.size() - lat.size(), keep::all); // the rings before without codes
        c.keep.insert(c.keep.end(), keep->begin(), keep->end());
    }
    c.flags[f] = keep ? 2 : 1;
}

void LayerFill::point(std::uint8_t slot, std::uint32_t f, GridPoint p) {
    ColumnFill& c = at(slot, f, {Codec::point});
    c.lat[f] = p.lat;
    c.lon[f] = p.lon;
    c.flags[f] = 1;
}

void LayerFill::tags(std::uint8_t slot, std::uint32_t f,
                     std::span<const std::pair<std::string_view, std::string_view>> pairs) {
    ColumnFill& c = at(slot, f, {Codec::tags});
    c.spans[f] = {.first = static_cast<std::uint32_t>(c.strings.size()),
                  .count = static_cast<std::uint32_t>(pairs.size())};
    for (const auto& [key, value] : pairs) {
        c.strings.push_back(strings_->intern(key));
        c.strings.push_back(strings_->intern(value));
    }
}

void LayerFill::ids(std::uint8_t slot, std::uint32_t f, std::span<const std::int64_t> v) {
    ColumnFill& c = at(slot, f, {Codec::ids});
    c.spans[f] = {.first = static_cast<std::uint32_t>(c.items.size()), .count = static_cast<std::uint32_t>(v.size())};
    c.items.insert(c.items.end(), v.begin(), v.end());
}

void LayerFill::string_list(std::uint8_t slot, std::uint32_t f, std::optional<std::span<const std::string_view>> v) {
    ColumnFill& c = at(slot, f, {Codec::strs});
    c.flags[f] = v ? 1 : 0;
    c.spans[f] = {.first = static_cast<std::uint32_t>(c.strings.size()),
                  .count = v ? static_cast<std::uint32_t>(v->size()) : 0U};
    if (v) {
        for (const std::string_view s : *v) c.strings.push_back(strings_->intern(s));
    }
}

// ---------------------------------------------------------------- a tile

TileFill::TileFill(std::uint8_t level, std::uint32_t x, std::uint32_t y)
    : level_(level), x_(x), y_(y), strings_(std::make_unique<StringPool>()) {
    const std::size_t n = tiles::cells_per_side(level);
    cells_.resize(n * n);
}

LayerFill& TileFill::layer(LayerId id) {
    for (LayerFill& l : layers_) {
        if (l.id() == id) return l;
    }
    return layers_.emplace_back(id, *strings_);
}

Result<std::vector<std::byte>> encode_tile(const TileFill& tile) {
    const std::size_t cells = tile.cells().size();
    const auto n = static_cast<std::uint32_t>(cells);
    const StringPool& pool = tile.strings();
    Table table;
    struct Block {
        const LayerFill* layer = nullptr;
        std::uint16_t columns = 0;
        Sink bytes;
        std::vector<std::uint32_t> per_cell; ///< features in each cell
    };
    std::vector<Block> blocks;
    for (const LayerFill& layer : tile.layers()) {
        if (layer.count() == 0) continue;
        if (layer.count() > max_features) return std::unexpected(error(Fault::too_big, Site::layers, layer.count()));
        Block& b = blocks.emplace_back();
        b.layer = &layer;
        b.per_cell.assign(cells, 0);
        for (const std::uint16_t c : layer.cells()) {
            if (c >= n) return std::unexpected(error(Fault::bad_value, Site::cells, c));
            ++b.per_cell[c];
        }
        Sink column;
        for (std::size_t s = 0; s < layer.schema().columns.size(); ++s) {
            const auto slot = static_cast<std::uint8_t>(s);
            const auto left_out = omitted(layer, slot);
            if (!left_out) return std::unexpected(left_out.error());
            if (*left_out) continue;
            column.clear();
            if (auto made = encode_column(layer, slot, pool, table, column); !made) {
                return std::unexpected(made.error());
            }
            const ColumnSpec& spec = layer.schema().columns[slot];
            b.bytes.varu(table.of(spec.name));
            b.bytes.u8(static_cast<std::uint8_t>(spec.codec));
            b.bytes.block(column);
            ++b.columns;
        }
    }
    // the string table, in the order the columns met the strings
    Sink strings;
    strings.varu(table.list().size());
    for (const std::string_view s : table.list()) {
        if (!valid_utf8(std::as_bytes(std::span(s.data(), s.size())))) {
            return std::unexpected(error(Fault::bad_utf8, Site::strings));
        }
        strings.utf8(s);
    }
    // the cell table: each cell's features of every layer, the box and top of what they draw, and each layer's count
    const std::uint8_t z = tile.level();
    const std::int32_t south = row_edge(z, tile.y() + 1);
    const std::int32_t west = column_edge(z, tile.x());
    const std::int32_t north = row_edge(z, tile.y());
    const std::int32_t east = column_edge(z, tile.x() + 1);
    Sink cell_table;
    for (std::size_t c = 0; c < cells; ++c) {
        std::uint64_t total = 0;
        for (const Block& b : blocks) total += b.per_cell[c];
        cell_table.varu(total);
        if (total == 0) continue;
        const CellFill& box = tile.cells()[c];
        cell_table.varz(std::int64_t{box.south} - south);
        cell_table.varz(std::int64_t{box.west} - west);
        cell_table.varz(std::int64_t{box.north} - south);
        cell_table.varz(std::int64_t{box.east} - west);
        cell_table.varz(box.top_cm);
        for (const Block& b : blocks) cell_table.varu(b.per_cell[c]);
    }
    // the header, the layer directory, then the blocks
    const std::size_t string_at = tile_header_bytes + (blocks.size() * layer_entry_bytes);
    const std::size_t cell_at = string_at + strings.size();
    std::size_t total = cell_at + cell_table.size();
    for (const Block& b : blocks) total += b.bytes.size();
    if (total > max_tile_bytes) return std::unexpected(error(Fault::too_big, Site::tile, total));
    Sink out;
    out.reserve(total);
    out.u32(tile_magic);
    out.u16(payload_version);
    out.u8(z);
    out.u8(tile.cells_per_side());
    out.u32(tile.x());
    out.u32(tile.y());
    out.i32(south);
    out.i32(west);
    out.i32(north);
    out.i32(east);
    out.u32(string_at);
    out.u32(strings.size());
    out.u32(cell_at);
    out.u32(cell_table.size());
    out.u16(blocks.size());
    out.u16(0);
    std::size_t at = cell_at + cell_table.size();
    for (const Block& b : blocks) {
        out.u8(static_cast<std::uint8_t>(b.layer->id()));
        out.u8(0);
        out.u16(b.columns);
        out.u32(b.layer->count());
        out.u32(at);
        out.u32(b.bytes.size());
        at += b.bytes.size();
    }
    ATLAS_ASSERT(out.size() == string_at, "the layer directory's end is the string table's start");
    out.raw(strings.bytes());
    out.raw(cell_table.bytes());
    for (const Block& b : blocks) out.raw(b.bytes.bytes());
    return out.take();
}

Result<Reach> reach_of(const TileFill& tile) {
    std::vector<std::uint32_t> held(tile.cells().size(), 0);
    for (const LayerFill& layer : tile.layers()) {
        for (const std::uint16_t c : layer.cells()) {
            if (c < held.size()) ++held[c];
        }
    }
    Reach r{.box = {.south = std::numeric_limits<std::int32_t>::max(),
                    .west = std::numeric_limits<std::int32_t>::max(),
                    .north = std::numeric_limits<std::int32_t>::min(),
                    .east = std::numeric_limits<std::int32_t>::min()},
            .top_cm = 0};
    bool any = false;
    for (std::size_t c = 0; c < held.size(); ++c) {
        if (held[c] == 0) continue;
        const CellFill& b = tile.cells()[c];
        r.box.south = std::min(r.box.south, b.south);
        r.box.west = std::min(r.box.west, b.west);
        r.box.north = std::max(r.box.north, b.north);
        r.box.east = std::max(r.box.east, b.east);
        r.top_cm = std::max(r.top_cm, b.top_cm);
        any = true;
    }
    if (!any) return std::unexpected(error(Fault::missing, Site::reach));
    return r;
}

std::optional<std::int64_t> canonical_int(std::string_view s) noexcept {
    if (s.empty() || s.size() > 15 || (s.size() > 1 && s[0] == '0')) return std::nullopt;
    std::int64_t n = 0;
    for (const char c : s) {
        if (c < '0' || c > '9') return std::nullopt;
        n = (n * 10) + (c - '0');
    }
    return n;
}

bool is_centi(double v) noexcept {
    if (!std::isfinite(v) || std::abs(v) > 9e15 / 100) return false;
    const std::int64_t n = pymath::java::round(v * 100);
    return static_cast<double>(n) / 100.0 == v && (v != 0.0 || !std::signbit(v));
}

} // namespace atlas::tiles
