#include "tiles/Payload.hpp"

#include "tiles/Bytes.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Columns.hpp"
#include "tiles/Sections.hpp"
#include "tiles/TileBox.hpp"

#include <algorithm>
#include <limits>

namespace atlas::tiles {
namespace {

/// Where a tile's layer directory entry says its layer is.
struct Entry {
    std::uint8_t id = 0;
    std::uint16_t columns = 0;
    std::uint32_t count = 0;
    std::span<const std::byte> bytes;
};

[[nodiscard]] std::unexpected<TileError> fail(Fault f, Site s, std::uint64_t detail = 0) noexcept {
    return std::unexpected(error(f, s, detail));
}

/// The block of `raw` at `at` of `length` bytes, if it lies inside.
[[nodiscard]] bool block_of(std::span<const std::byte> raw, std::uint64_t at, std::uint64_t length,
                            std::span<const std::byte>& out) noexcept {
    if (at > raw.size() || length > raw.size() - at) return false;
    out = raw.subspan(static_cast<std::size_t>(at), static_cast<std::size_t>(length));
    return true;
}

} // namespace

std::int64_t Layer::integer(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present) return 0;
    return c.codec == Codec::u8 ? std::int64_t{c.flags[i]} : c.ints[i];
}

std::optional<std::int64_t> Layer::nullable_integer(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present || c.flags[i] == 0) return std::nullopt;
    return c.ints[i];
}

std::optional<double> Layer::decimal(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present) return std::nullopt;
    if (c.codec == Codec::centi && c.flags[i] == 0) return std::nullopt;
    return c.reals[i];
}

std::optional<std::string_view> Layer::string(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present || c.refs[i] == 0) return std::nullopt;
    return strings[c.refs[i] - 1];
}

StringOrNumber Layer::string_or_number(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    StringOrNumber out;
    if (!c.present || c.flags[i] == 0) return out;
    if (c.flags[i] == 1) {
        out.kind = StringOrNumber::Kind::string;
        out.text = strings[static_cast<std::size_t>(c.ints[i])];
    } else {
        out.kind = StringOrNumber::Kind::number;
        out.number = c.ints[i];
    }
    return out;
}

std::optional<bool> Layer::boolean(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present || c.flags[i] == 0) return std::nullopt;
    return c.flags[i] == 2;
}

RingView Layer::ring(std::uint8_t slot, std::size_t i) const noexcept {
    RingView v;
    const Column& g = columns[slot];
    std::uint32_t from = 0;
    std::uint32_t to = 0;
    if (g.present) {
        from = g.offsets[i];
        to = g.offsets[i + 1];
        v.lat = g.lat.subspan(from, to - from);
        v.lon = g.lon.subspan(from, to - from);
    }
    const std::uint8_t k = schema->keep_for[slot];
    if (k != 0xFF && columns[k].present) {
        v.has_keep = true;
        v.keep = columns[k].flags.subspan(from, to - from);
    }
    return v;
}

GridPoint Layer::point(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    return {c.lat[i], c.lon[i]};
}

std::size_t Layer::tag_count(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    return c.present ? c.offsets[i + 1] - c.offsets[i] : 0;
}

std::pair<std::string_view, std::string_view> Layer::tag(std::uint8_t slot, std::size_t i,
                                                         std::size_t k) const noexcept {
    const Column& c = columns[slot];
    const std::size_t at = 2 * (std::size_t{c.offsets[i]} + k);
    return {strings[c.refs[at]], strings[c.refs[at + 1]]};
}

std::optional<std::string_view> Layer::tag(std::uint8_t slot, std::size_t i, std::string_view key) const noexcept {
    for (std::size_t k = 0; k < tag_count(slot, i); ++k) {
        const auto [name, value] = tag(slot, i, k);
        if (name == key) return value;
    }
    return std::nullopt;
}

std::span<const std::int64_t> Layer::ids(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present) return {};
    return c.ints.subspan(c.offsets[i], c.offsets[i + 1] - c.offsets[i]);
}

std::optional<std::span<const std::uint32_t>> Layer::string_list(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present || c.flags[i] == 0) return std::nullopt;
    return c.refs.subspan(c.offsets[i], c.offsets[i + 1] - c.offsets[i]);
}

Value Layer::value(std::uint8_t slot, std::size_t i) const noexcept {
    const Column& c = columns[slot];
    if (!c.present) return {};
    return {c.nodes, strings, c.refs[i]};
}

const Layer* Tile::layer(LayerId id) const noexcept {
    const auto it = std::ranges::find(layers, id, &Layer::id);
    return it == layers.end() ? nullptr : &*it;
}

std::uint64_t Tile::feature_count() const noexcept {
    std::uint64_t n = 0;
    for (const Layer& l : layers) n += l.count;
    for (const SkippedLayer& s : skipped) n += s.count;
    return n;
}

Result<Reach> reach_of(const Tile& tile) noexcept {
    Reach r;
    bool any = false;
    for (const CellInfo& c : tile.cells) {
        if (c.total == 0) continue;
        if (!any) {
            r.box = {.south = c.south, .west = c.west, .north = c.north, .east = c.east};
            any = true;
        } else {
            r.box.south = std::min(r.box.south, c.south);
            r.box.west = std::min(r.box.west, c.west);
            r.box.north = std::max(r.box.north, c.north);
            r.box.east = std::max(r.box.east, c.east);
        }
        r.top_cm = std::max(r.top_cm, c.top_cm);
    }
    if (!any) return fail(Fault::missing, Site::reach, tile.header.level);
    return r;
}

Result<void> check_against(const Tile& tile, const ChunkRef& chunk) noexcept {
    const TileHeader& h = tile.header;
    if (TileKey{h.level, h.x, h.y} != chunk.tile) return fail(Fault::bad_value, Site::tile, chunk.tile.packed());
    const auto reach = reach_of(tile);
    if (!reach) return std::unexpected(reach.error());
    if (*reach != chunk.reach) return fail(Fault::bad_count, Site::reach, chunk.tile.packed());
    return {};
}

Result<Tile> TileDecoder::decode(std::span<const std::byte> raw, Arena& arena) {
    if (raw.size() < tile_header_bytes) return fail(Fault::truncated, Site::tile);
    if (raw.size() > max_tile_bytes) return fail(Fault::too_big, Site::tile, raw.size());
    Reader h(raw);
    if (h.u32le() != tile_magic) return fail(Fault::bad_magic, Site::tile);
    const std::uint16_t payload = h.u16le();
    if (payload != payload_version) return fail(Fault::bad_version, Site::tile, payload);
    Tile tile;
    TileHeader& th = tile.header;
    th.level = h.u8();
    th.cells_per_side = h.u8();
    th.x = h.u32le();
    th.y = h.u32le();
    th.south = static_cast<std::int32_t>(h.u32le());
    th.west = static_cast<std::int32_t>(h.u32le());
    th.north = static_cast<std::int32_t>(h.u32le());
    th.east = static_cast<std::int32_t>(h.u32le());
    const std::uint32_t strings_at = h.u32le();
    const std::uint32_t strings_length = h.u32le();
    const std::uint32_t cells_at = h.u32le();
    const std::uint32_t cells_length = h.u32le();
    const std::uint16_t layer_count = h.u16le();
    (void)h.u16le(); // zero
    // the bounds: the level, the column and row on it, the cells a side, and the box, which must be the grid's own
    if (th.level > max_zoom) return fail(Fault::bad_value, Site::tile, th.level);
    const std::uint64_t side = std::uint64_t{1} << th.level;
    if (th.x >= side || th.y >= side) return fail(Fault::bad_value, Site::tile, th.x);
    if (th.cells_per_side != cells_per_side(th.level)) return fail(Fault::bad_value, Site::tile, th.cells_per_side);
    if (!tile_box_matches(th)) return fail(Fault::bad_value, Site::tile, 16);
    std::span<const std::byte> strings_block;
    std::span<const std::byte> cells_block;
    if (!block_of(raw, strings_at, strings_length, strings_block)) return fail(Fault::truncated, Site::strings);
    if (!block_of(raw, cells_at, cells_length, cells_block)) return fail(Fault::truncated, Site::cells);

    // The layer directory. Ids are bytes, so a directory of more than 256 layers names one twice.
    if (layer_count > 256) return fail(Fault::duplicate, Site::layers, layer_count);
    const std::span<Entry> entries = arena.allocate_array<Entry>(layer_count);
    for (std::size_t i = 0; i < layer_count; ++i) {
        Entry& e = entries[i];
        e.id = h.u8();
        (void)h.u8(); // zero
        e.columns = h.u16le();
        e.count = h.u32le();
        const std::uint32_t at = h.u32le();
        const std::uint32_t length = h.u32le();
        if (!h.ok()) return fail(Fault::truncated, Site::layers, i);
        if (e.count > max_features) return fail(Fault::too_big, Site::layers, e.count);
        if (!block_of(raw, at, length, e.bytes)) return fail(Fault::truncated, Site::layers, i);
        for (std::size_t j = 0; j < i; ++j) {
            if (entries[j].id == e.id) return fail(Fault::duplicate, Site::layers, e.id);
        }
    }

    // The string table.
    Reader sr(strings_block);
    const std::uint32_t string_count = sr.count();
    if (!sr.ok()) return fail(sr.fault(), Site::strings);
    if (string_count > sr.remaining()) return fail(Fault::bad_count, Site::strings, string_count);
    const std::span<std::string_view> strings = arena.allocate_array<std::string_view>(string_count);
    for (auto& s : strings) s = sr.utf8();
    if (!sr.ok()) return fail(sr.fault(), Site::strings);
    if (!sr.done()) return fail(Fault::left_over, Site::strings, sr.remaining());
    tile.strings = strings;

    // The cell table: each cell's total, box and top, and every layer's count in it.
    const std::size_t cell_count = std::size_t{th.cells_per_side} * th.cells_per_side;
    const std::span<CellInfo> cells = arena.allocate_array<CellInfo>(cell_count);
    const std::span<std::uint32_t> counts = arena.allocate_array<std::uint32_t>(cell_count * layer_count);
    std::ranges::fill(counts, 0U);
    Reader cr(cells_block);
    for (std::size_t c = 0; c < cell_count; ++c) {
        CellInfo& cell = cells[c];
        cell = CellInfo{};
        cell.total = cr.count();
        if (!cr.ok()) return fail(cr.fault(), Site::cells, c);
        if (cell.total == 0) continue;
        const std::int64_t s = wrap_add(cr.varz(), th.south);
        const std::int64_t w = wrap_add(cr.varz(), th.west);
        const std::int64_t n = wrap_add(cr.varz(), th.south);
        const std::int64_t e = wrap_add(cr.varz(), th.west);
        const std::int64_t top = cr.varz();
        for (const std::int64_t v : {s, w, n, e, top}) {
            if (v < std::numeric_limits<std::int32_t>::min() || v > std::numeric_limits<std::int32_t>::max()) {
                return fail(Fault::overflow, Site::cells, c);
            }
        }
        cell.south = static_cast<std::int32_t>(s);
        cell.west = static_cast<std::int32_t>(w);
        cell.north = static_cast<std::int32_t>(n);
        cell.east = static_cast<std::int32_t>(e);
        cell.top_cm = static_cast<std::int32_t>(top);
        std::uint64_t sum = 0;
        for (std::size_t j = 0; j < layer_count; ++j) {
            const std::uint32_t k = cr.count();
            counts[(j * cell_count) + c] = k;
            sum += k;
        }
        if (!cr.ok()) return fail(cr.fault(), Site::cells, c);
        if (sum != cell.total) return fail(Fault::bad_count, Site::cells, c);
    }
    if (!cr.done()) return fail(Fault::left_over, Site::cells, cr.remaining());
    tile.cells = cells;

    // Each layer's features add up across its cells.
    std::size_t known = 0;
    for (std::size_t j = 0; j < layer_count; ++j) {
        std::uint64_t sum = 0;
        for (std::size_t c = 0; c < cell_count; ++c) sum += counts[(j * cell_count) + c];
        if (sum != entries[j].count) return fail(Fault::bad_count, Site::layers, entries[j].id);
        if (schema_of(static_cast<LayerId>(entries[j].id)) != nullptr) ++known;
    }

    const std::span<Layer> layers = arena.allocate_array<Layer>(known);
    const std::span<SkippedLayer> skipped = arena.allocate_array<SkippedLayer>(layer_count - known);
    std::size_t at_known = 0;
    std::size_t at_skipped = 0;
    for (std::size_t j = 0; j < layer_count; ++j) {
        const Entry& e = entries[j];
        const LayerSchema* schema = schema_of(static_cast<LayerId>(e.id));
        if (schema == nullptr) {
            skipped[at_skipped++] = {.id = e.id, .count = e.count};
            continue;
        }
        Layer& layer = layers[at_known++];
        layer = Layer{};
        layer.id = schema->id;
        layer.schema = schema;
        layer.count = e.count;
        layer.strings = strings;
        const std::span<std::uint32_t> first = arena.allocate_array<std::uint32_t>(cell_count + 1);
        first[0] = 0;
        for (std::size_t c = 0; c < cell_count; ++c) first[c + 1] = first[c] + counts[(j * cell_count) + c];
        layer.cell_first = first;

        // The column directory: the columns this payload defines, by name; the others are skipped.
        std::array<std::span<const std::byte>, max_columns> blocks{};
        std::array<bool, max_columns> present{};
        Reader lr(e.bytes);
        for (std::size_t k = 0; k < e.columns; ++k) {
            const std::uint32_t name_index = lr.count();
            const std::uint8_t codec = lr.u8();
            Reader block = lr.block();
            if (!lr.ok()) return std::unexpected(column_error(lr.fault(), layer.id, "", k));
            if (name_index >= strings.size()) return std::unexpected(column_error(Fault::bad_value, layer.id, "", k));
            const std::string_view name = strings[name_index];
            const std::uint8_t slot = schema->slot(name);
            if (slot == 0xFF) continue; // a column this reader does not know
            if (codec != static_cast<std::uint8_t>(schema->columns[slot].codec)) {
                return std::unexpected(column_error(Fault::bad_value, layer.id, name, codec));
            }
            if (present[slot]) return std::unexpected(column_error(Fault::duplicate, layer.id, name));
            present[slot] = true;
            blocks[slot] = block.bytes(block.remaining());
        }
        if (!lr.done()) return fail(Fault::left_over, Site::layers, e.id);

        const std::span<Column> columns = arena.allocate_array<Column>(schema->columns.size());
        for (std::size_t slot = 0; slot < schema->columns.size(); ++slot) {
            const ColumnSpec& spec = schema->columns[slot];
            columns[slot] = Column{};
            columns[slot].codec = spec.codec;
            if (!present[slot]) {
                // a missing column holds its default, but positions and doubles have none
                if (spec.codec == Codec::point || spec.codec == Codec::f64) {
                    return std::unexpected(column_error(Fault::missing, layer.id, spec.name));
                }
                continue;
            }
            const ColumnInput in{
                .layer = layer.id, .name = spec.name, .codec = spec.codec, .count = e.count, .strings = strings};
            const Column* ring = spec.codec == Codec::keep ? &columns[schema->keep_of[slot]] : nullptr;
            auto decoded = decode_column(in, blocks[slot], ring, arena, scratch_);
            if (!decoded) return std::unexpected(decoded.error());
            columns[slot] = *decoded;
        }
        layer.columns = columns;
    }
    tile.layers = layers;
    tile.skipped = skipped;
    return tile;
}

} // namespace atlas::tiles
