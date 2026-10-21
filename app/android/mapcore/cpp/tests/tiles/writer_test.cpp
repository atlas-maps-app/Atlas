// The render tile's writer (tiles/TileWriter.hpp) held to the packer: every tile of the bundled packs
// (ATLAS_TILES_DIR), decoded and filled into the writer as stage P will fill it, is written back as its own bytes, the
// packer's Format3Writer.encodeTile's, which the chunk table's SHA-256 names. Then each codec on values the bundled
// packs never hold (numbers among strings, nulls between deltas, an escaped vertex, a small ring's codes), read back
// equal by the core's reader, and what the format cannot hold refused, named. Then the pack's writer
// (tiles/PackWriter.hpp): the canal ring's tiles written again into a pack of their own, which the core opens, checks
// and reads as the packer's.
#include "base/arena.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/Directory.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cmath>
#include <limits>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace tl = atlas::tiles;
using atlas::test::held;

/// A decoded tile filled into the writer, feature by feature and column by column, as stage P fills one.
tl::TileFill fill_of(const tl::Tile& t) {
    tl::TileFill out(t.header.level, t.header.x, t.header.y);
    for (std::size_t c = 0; c < t.cells.size() && c < out.cells().size(); ++c) {
        const tl::CellInfo& in = t.cells[c];
        out.cells()[c] = {.south = in.south, .west = in.west, .north = in.north, .east = in.east, .top_cm = in.top_cm};
    }
    std::vector<std::pair<std::string_view, std::string_view>> pairs;
    std::vector<std::string_view> list;
    for (const tl::Layer& l : t.layers) {
        tl::LayerFill& f = out.layer(l.id);
        for (std::size_t c = 0; c + 1 < l.cell_first.size(); ++c) {
            for (std::uint32_t i = l.cell_first[c]; i < l.cell_first[c + 1]; ++i) f.add(static_cast<std::uint16_t>(c));
        }
        const std::span<const tl::ColumnSpec> columns = l.schema->columns;
        for (std::size_t s = 0; s < columns.size(); ++s) {
            const auto slot = static_cast<std::uint8_t>(s);
            const tl::Column& column = l.column(slot);
            if (!column.present) continue;
            for (std::uint32_t i = 0; i < l.count; ++i) {
                switch (columns[s].codec) {
                case tl::Codec::u8:
                case tl::Codec::varu:
                case tl::Codec::varz:
                case tl::Codec::delta: f.integer(slot, i, l.integer(slot, i)); break;
                case tl::Codec::nvarz:
                case tl::Codec::ndelta: f.nullable_integer(slot, i, l.nullable_integer(slot, i)); break;
                case tl::Codec::centi:
                case tl::Codec::f64: f.decimal(slot, i, l.decimal(slot, i)); break;
                case tl::Codec::str: f.string(slot, i, l.string(slot, i)); break;
                case tl::Codec::strn: {
                    const tl::StringOrNumber v = l.string_or_number(slot, i);
                    if (v.kind == tl::StringOrNumber::Kind::null) {
                        f.string_or_number(slot, i, std::nullopt);
                    } else if (v.kind == tl::StringOrNumber::Kind::string) {
                        f.string_or_number(slot, i, v.text);
                    } else {
                        f.string_or_number(slot, i, std::to_string(v.number));
                    }
                    break;
                }
                case tl::Codec::boolean: f.boolean(slot, i, l.boolean(slot, i)); break;
                case tl::Codec::geom: {
                    const tl::RingView r = l.ring(slot, i);
                    const std::uint8_t keep = l.schema->keep_for[slot];
                    const bool coded = keep != 0xFF && l.column(keep).present;
                    f.ring(slot, i, r.lat, r.lon, coded ? std::optional(r.keep) : std::nullopt);
                    break;
                }
                case tl::Codec::point: f.point(slot, i, l.point(slot, i)); break;
                case tl::Codec::tags:
                    pairs.clear();
                    for (std::size_t k = 0; k < l.tag_count(slot, i); ++k) pairs.push_back(l.tag(slot, i, k));
                    f.tags(slot, i, pairs);
                    break;
                case tl::Codec::ids: f.ids(slot, i, l.ids(slot, i)); break;
                case tl::Codec::strs: {
                    const auto indexes = l.string_list(slot, i);
                    if (!indexes) {
                        f.string_list(slot, i, std::nullopt);
                        break;
                    }
                    list.clear();
                    for (const std::uint32_t k : *indexes) list.push_back(l.strings[k]);
                    f.string_list(slot, i, list);
                    break;
                }
                case tl::Codec::keep:
                case tl::Codec::value: break; // keep codes come with their rings; no layer has a value column
                }
            }
        }
    }
    return out;
}

/// Where two byte strings first differ (their shorter length when one is the other's start).
std::size_t first_difference(std::span<const std::byte> a, std::span<const std::byte> b) {
    const auto [ia, ib] = std::ranges::mismatch(a, b);
    return static_cast<std::size_t>(ia - a.begin());
}

} // namespace

TEST_CASE("tiles: the writer writes every tile of the bundled packs as the packer wrote it") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not written again here");
        return;
    }
    std::map<tl::Codec, std::size_t> codecs; // the columns written, by codec
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack(dir + "/" + name + ".atlas");
        ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        atlas::Arena arena(8U << 20U, atlas::Growth::grows, "writer test");
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        std::size_t tiles = 0;
        std::size_t bytes = 0;
        std::size_t wrong = 0;
        for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
            arena.reset();
            auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
            CHECK(tile->skipped.empty());
            for (const tl::Layer& l : tile->layers) {
                for (std::size_t s = 0; s < l.schema->columns.size(); ++s) {
                    if (l.column(static_cast<std::uint8_t>(s)).present) ++codecs[l.schema->columns[s].codec];
                }
            }
            const tl::TileFill fill = fill_of(*tile);
            const auto written = tl::encode_tile(fill);
            ATLAS_REQUIRE(written.has_value(), tl::describe(written.error()));
            ++tiles;
            bytes += written->size();
            if (*written != raw || tl::Sha256::of(*written) != chunk.sha256) {
                if (wrong++ < 3) {
                    FAIL_CHECK(name, " tile ", int{chunk.tile.z}, "/", chunk.tile.x, "/", chunk.tile.y, ": ",
                               written->size(), " bytes written for ", raw.size(), ", the first difference at byte ",
                               first_difference(*written, raw));
                }
            }
        }
        MESSAGE(std::string(name), ": ", tiles, " tiles written again, ", bytes, " bytes, ", wrong,
                " not the packer's");
        CHECK(tiles > 0);
        CHECK(wrong == 0);
    }
    std::string seen;
    for (const auto& [codec, n] : codecs)
        seen += " " + std::to_string(static_cast<int>(codec)) + ":" + std::to_string(n);
    MESSAGE("the columns written, by codec id:", seen);
}

TEST_CASE("tiles: the writer's codecs read back equal where the bundled packs hold no such values") {
    // a label layer (point, varu, str, strn, nvarz ...) and a building layer with rings, keep codes, lists and tags
    tl::TileFill tile(14, 8414, 5384);
    for (tl::CellFill& c : tile.cells()) c = {.south = 1, .west = 2, .north = 3, .east = 4, .top_cm = 500};
    tl::LayerFill& b = tile.layer(tl::LayerId::building);
    namespace bu = tl::building;
    // three buildings: a six-point ring that shares an edge with the next (backwards), a small ring, an escaped jump
    const std::array<std::int32_t, 6> lat0{523'740'000, 523'740'100, 523'740'200,
                                           523'740'300, 523'740'200, 523'740'100};
    const std::array<std::int32_t, 6> lon0{48'840'000, 48'840'100, 48'840'100, 48'840'000, 48'839'900, 48'839'900};
    const std::array<std::uint8_t, 6> keep0{13, 3, 0, 13, 7, 1};
    const std::array<std::int32_t, 4> lat1{523'740'200, 523'740'100, 523'740'150, 523'740'250};
    const std::array<std::int32_t, 4> lon1{48'840'100, 48'840'100, 48'840'300, 48'840'300};
    const std::array<std::uint8_t, 4> keep1{13, 13, 13, 13};
    const std::array<std::int32_t, 3> lat2{523'000'000, 523'740'000, 524'000'000}; // deltas past 16 bits: escaped
    const std::array<std::int32_t, 3> lon2{48'000'000, 49'840'000, 48'840'000};
    const std::array<std::uint8_t, 3> keep2{13, 13, 13};
    const std::array<std::int64_t, 3> places{900'000'000'000LL, -5, 7};
    const std::array<std::string_view, 2> guessed{"height", "roof:shape"};
    const std::uint8_t guessed_slot = tl::schema_of(tl::LayerId::building)->slot("guessed");
    for (std::uint32_t i = 0; i < 3; ++i) {
        const std::uint32_t f = b.add(static_cast<std::uint16_t>(i == 2 ? 9 : 0));
        b.integer(bu::index, f, 100 - (i * 7));
        b.integer(bu::id, f, i == 1 ? -42 : 1'234'567'890'123LL);
        b.integer(bu::wall_rgb, f, 0xA0522D);
        b.decimal(bu::h, f, std::array<std::optional<double>, 3>{12.25, std::nullopt, -0.0}[i]);
        b.decimal(bu::min, f, std::array<std::optional<double>, 3>{0.1 + 0.2, std::nan(""), 1e300}[i]);
        b.nullable_integer(bu::year, f, i == 1 ? std::nullopt : std::optional<std::int64_t>(1650 + i));
        b.string(bu::roof, f, i == 0 ? std::optional<std::string_view>("gabled") : std::nullopt);
        b.boolean(bu::hidden, f, std::array<std::optional<bool>, 3>{true, false, std::nullopt}[i]);
        b.string_list(guessed_slot, f,
                      i == 2 ? std::nullopt : std::optional(std::span<const std::string_view>(guessed)));
        b.ids(bu::places, f, i == 0 ? std::span<const std::int64_t>(places) : std::span<const std::int64_t>{});
        if (i == 0) b.ring(bu::ring, f, lat0, lon0, std::span<const std::uint8_t>(keep0));
        if (i == 1) b.ring(bu::ring, f, lat1, lon1, std::span<const std::uint8_t>(keep1));
        if (i == 2) b.ring(bu::ring, f, lat2, lon2, std::span<const std::uint8_t>(keep2));
    }
    // items: a byte, nullable ids, points and every tag; trees: house numbers among strings (strn)
    tl::LayerFill& t = tile.layer(tl::LayerId::item);
    namespace it = tl::item;
    const std::array<std::optional<std::string_view>, 6> numbers{"18", "18C", std::nullopt, "0", "007", "12"};
    for (std::uint32_t i = 0; i < numbers.size(); ++i) {
        const std::uint32_t f = t.add(static_cast<std::uint16_t>(i));
        t.integer(it::source, f, i % 5);
        t.nullable_integer(it::id, f, i == 2 ? std::nullopt : std::optional<std::int64_t>(-1 - std::int64_t{i}));
        t.point(it::pos, f,
                {static_cast<std::int32_t>(523'740'000 + (i * 17)), static_cast<std::int32_t>(48'840'000 - i)});
        const std::array<std::pair<std::string_view, std::string_view>, 2> tags{
            {{"man_made", "crane"}, {"height", i % 2 == 0 ? "40" : "35.5"}}};
        t.tags(it::tags, f, i == 3 ? std::span<const std::pair<std::string_view, std::string_view>>{} : tags);
    }
    tl::LayerFill& trees = tile.layer(tl::LayerId::tree);
    const std::uint8_t housenumber = tl::schema_of(tl::LayerId::tree)->slot("tag:addr:housenumber");
    for (std::uint32_t i = 0; i < numbers.size(); ++i) {
        const std::uint32_t f = trees.add(static_cast<std::uint16_t>(i));
        trees.point(tl::tree::pos, f, {static_cast<std::int32_t>(523'750'000 - i), 48'850'000});
        trees.string_or_number(housenumber, f, numbers[i]);
    }
    const auto written = tl::encode_tile(tile);
    ATLAS_REQUIRE(written.has_value(), tl::describe(written.error()));
    tl::TileDecoder decoder;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "writer test");
    const auto read = decoder.decode(*written, arena);
    ATLAS_REQUIRE(read.has_value(), tl::describe(read.error()));
    // the layers in the order they were filled, each read back value for value
    REQUIRE(read->layers.size() == 3);
    CHECK(read->layers[0].id == tl::LayerId::building);
    const tl::Layer& rb = read->layers[0];
    REQUIRE(rb.count == 3);
    CHECK(rb.integer(bu::id, 1) == -42);
    CHECK(rb.integer(bu::index, 2) == 86);
    CHECK(rb.decimal(bu::h, 0) == 12.25);
    CHECK(!rb.decimal(bu::h, 1).has_value());
    CHECK(std::signbit(held(rb.decimal(bu::h, 2)))); // -0 is not hundredths: its double's bits
    CHECK(held(rb.decimal(bu::min, 0)) == 0.1 + 0.2);
    CHECK(std::isnan(held(rb.decimal(bu::min, 1))));
    CHECK(held(rb.decimal(bu::min, 2)) == 1e300);
    CHECK(rb.nullable_integer(bu::year, 2) == 1652);
    CHECK(!rb.nullable_integer(bu::year, 1).has_value());
    CHECK(rb.string(bu::roof, 0) == "gabled");
    CHECK(!rb.string(bu::roof, 1).has_value());
    CHECK(rb.boolean(bu::hidden, 0) == true);
    CHECK(rb.boolean(bu::hidden, 1) == false);
    CHECK(!rb.boolean(bu::hidden, 2).has_value());
    CHECK(!rb.string_list(guessed_slot, 2).has_value());
    const auto guessed_read = rb.string_list(guessed_slot, 0);
    REQUIRE(guessed_read.has_value());
    if (guessed_read) CHECK(rb.strings[(*guessed_read)[1]] == "roof:shape");
    CHECK(std::ranges::equal(rb.ids(bu::places, 0), places));
    CHECK(rb.ids(bu::places, 1).empty());
    const tl::RingView r0 = rb.ring(bu::ring, 0);
    const tl::RingView r1 = rb.ring(bu::ring, 1);
    const tl::RingView r2 = rb.ring(bu::ring, 2);
    CHECK(std::ranges::equal(r0.lat, lat0));
    CHECK(std::ranges::equal(r0.keep, keep0));
    CHECK(std::ranges::equal(r1.lon, lon1));
    CHECK(std::ranges::equal(r1.keep, keep1)); // a small ring's codes, not written, read as every tolerance's
    CHECK(std::ranges::equal(r2.lat, lat2));
    CHECK(std::ranges::equal(r2.lon, lon2));
    const tl::Layer& rt = read->layers[1];
    const tl::Layer& rr = read->layers[2];
    REQUIRE(rt.count == numbers.size());
    REQUIRE(rr.count == numbers.size());
    for (std::uint32_t i = 0; i < numbers.size(); ++i) {
        CHECK(rt.integer(it::source, i) == i % 5);
        CHECK(rt.nullable_integer(it::id, i) ==
              (i == 2 ? std::nullopt : std::optional<std::int64_t>(-1 - std::int64_t{i})));
        CHECK(rt.point(it::pos, i) == tl::GridPoint(static_cast<std::int32_t>(523'740'000 + (i * 17)),
                                                    static_cast<std::int32_t>(48'840'000 - i)));
        CHECK(rt.tag_count(it::tags, i) == (i == 3 ? 0U : 2U));
        const tl::StringOrNumber v = rr.string_or_number(housenumber, i);
        const std::string text =
            v.kind == tl::StringOrNumber::Kind::number ? std::to_string(v.number) : std::string(v.text);
        const std::optional<std::string_view> want = numbers[i];
        if (!want) {
            CHECK(v.kind == tl::StringOrNumber::Kind::null);
            continue;
        }
        const std::string_view number = *want;
        CHECK(v.kind ==
              (tl::canonical_int(number) ? tl::StringOrNumber::Kind::number : tl::StringOrNumber::Kind::string));
        CHECK(text == number);
    }
    CHECK(rt.tag(it::tags, 5, "height") == "35.5");
    // the cells: each feature counted in its own, the boxes as given
    REQUIRE(read->cells.size() == 64);
    CHECK(read->cells[0].total == 4); // two buildings, an item and a tree
    CHECK(read->cells[9].total == 1); // the third building
    CHECK(read->cells[9].top_cm == 500);
    CHECK(read->cells[63].total == 0);
    // and written again, the same bytes
    const auto again = tl::encode_tile(fill_of(*read));
    ATLAS_REQUIRE(again.has_value(), tl::describe(again.error()));
    CHECK(*again == *written);
}

TEST_CASE("tiles: the writer refuses what the format cannot hold, naming the column") {
    namespace bu = tl::building;
    const std::array<std::int32_t, 6> lat{1, 2, 3, 4, 5, 6};
    const std::array<std::int32_t, 6> lon{1, 2, 3, 4, 5, 6};
    const std::array<std::uint8_t, 6> codes{13, 1, 1, 1, 1, 13};
    {
        // keep codes on one ring and not on the other
        tl::TileFill tile(14, 8414, 5384);
        tl::LayerFill& b = tile.layer(tl::LayerId::building);
        b.ring(bu::ring, b.add(0), lat, lon, std::span<const std::uint8_t>(codes));
        b.ring(bu::ring, b.add(0), lat, lon);
        const auto r = tl::encode_tile(tile);
        REQUIRE(!r.has_value());
        CHECK(r.error().fault == tl::Fault::bad_count);
        CHECK(r.error().column_name() == "ring.keep");
    }
    {
        // a small ring whose codes are not every tolerance's
        tl::TileFill tile(14, 8414, 5384);
        tl::LayerFill& b = tile.layer(tl::LayerId::building);
        const std::array<std::uint8_t, 3> small{13, 2, 13};
        b.ring(bu::ring, b.add(0), std::span(lat).first(3), std::span(lon).first(3),
               std::span<const std::uint8_t>(small));
        const auto r = tl::encode_tile(tile);
        REQUIRE(!r.has_value());
        CHECK(r.error().fault == tl::Fault::bad_value);
        CHECK(r.error().column_name() == "ring.keep");
    }
    {
        // a point column with a feature unset
        tl::TileFill tile(14, 8414, 5384);
        tl::LayerFill& t = tile.layer(tl::LayerId::item);
        t.point(tl::item::pos, t.add(0), {1, 2});
        static_cast<void>(t.add(1));
        const auto r = tl::encode_tile(tile);
        REQUIRE(!r.has_value());
        CHECK(r.error().fault == tl::Fault::missing);
        CHECK(r.error().column_name() == "pos");
    }
    {
        // a string that is not UTF-8
        tl::TileFill tile(14, 8414, 5384);
        tl::LayerFill& b = tile.layer(tl::LayerId::building);
        b.string(bu::roof, b.add(0), std::string_view("\xC0\xAF"));
        const auto r = tl::encode_tile(tile);
        REQUIRE(!r.has_value());
        CHECK(r.error().fault == tl::Fault::bad_utf8);
    }
    {
        // a u8 past a byte
        tl::TileFill tile(14, 8414, 5384);
        tl::LayerFill& t = tile.layer(tl::LayerId::item);
        const std::uint32_t f = t.add(0);
        t.point(tl::item::pos, f, {1, 2});
        t.integer(tl::item::source, f, 256);
        const auto r = tl::encode_tile(tile);
        REQUIRE(!r.has_value());
        CHECK(r.error().fault == tl::Fault::bad_value);
        CHECK(r.error().column_name() == "source");
    }
}

TEST_CASE("tiles: a pack written of the canal ring's tiles opens, checks and reads as the packer's") {
    CHECK(tl::hilbert(1, 0, 0) == 0);
    CHECK(tl::hilbert(1, 0, 1) == 1);
    CHECK(tl::hilbert(1, 1, 1) == 2);
    CHECK(tl::hilbert(1, 1, 0) == 3);
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: no pack is written here");
        return;
    }
    auto pack = atlas::test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
    ATLAS_REQUIRE(pack->head() != nullptr);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "pack writer test");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    std::vector<tl::PackTile> tiles;
    // in the reverse of the table's order, so the writer orders them itself
    const std::span<const tl::ChunkRef> chunks = pack->chunks().chunks();
    for (auto it = chunks.rbegin(); it != chunks.rend(); ++it) {
        arena.reset();
        auto tile = pack->decode(*it, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
        const tl::TileFill fill = fill_of(*tile);
        auto written = tl::encode_tile(fill);
        ATLAS_REQUIRE(written.has_value(), tl::describe(written.error()));
        const auto reach = tl::reach_of(fill);
        ATLAS_REQUIRE(reach.has_value(), tl::describe(reach.error()));
        CHECK(*reach == it->reach);
        tiles.push_back({.key = it->tile, .raw = std::move(*written), .count = it->count, .reach = *reach});
    }
    // its rhead as it is, its style's tables decompressed
    const tl::Section* head = tl::find_section(pack->sections(), tl::section_head);
    const tl::Section* style = tl::find_section(pack->sections(), tl::section_style);
    ATLAS_REQUIRE(head != nullptr && style != nullptr);
    std::vector<std::byte> head_scratch;
    std::vector<std::byte> style_scratch;
    const auto head_bytes = pack->source().bytes(head->offset, head->stored, head_scratch);
    const auto style_stored = pack->source().bytes(style->offset, style->stored, style_scratch);
    ATLAS_REQUIRE(head_bytes.has_value() && style_stored.has_value());
    std::vector<std::byte> style_tables(style->raw);
    ATLAS_REQUIRE(zstd.frame(*style_stored, style_tables, tl::Site::style).has_value());
    const std::string manifest = R"({"layout":"render-tiles","payload":1,"builder":"writer_test"})";
    const auto bytes = tl::write_pack(
        tiles, {.levels = pack->head()->levels, .manifest = manifest, .head = *head_bytes, .style = style_tables});
    ATLAS_REQUIRE(bytes.has_value(), tl::describe(bytes.error()));
    auto again = tl::Pack::open(std::make_unique<tl::MemorySource>(*bytes));
    ATLAS_REQUIRE(again.has_value(), tl::describe(again.error()));
    const auto verified = again->verify();
    CHECK_MESSAGE(verified.has_value(), (verified ? std::string() : tl::describe(verified.error())));
    // the same chunks in the same order, each the packer's tile, the same levels and style
    const std::span<const tl::ChunkRef> mine = again->chunks().chunks();
    REQUIRE(mine.size() == chunks.size());
    for (std::size_t i = 0; i < mine.size(); ++i) {
        CHECK(mine[i].tile == chunks[i].tile);
        CHECK(mine[i].sha256 == chunks[i].sha256);
        CHECK(mine[i].raw == chunks[i].raw);
        CHECK(mine[i].count == chunks[i].count);
        CHECK(mine[i].reach == chunks[i].reach);
    }
    ATLAS_REQUIRE(again->head() != nullptr && again->style() != nullptr && pack->style() != nullptr);
    CHECK(again->head()->levels == pack->head()->levels);
    CHECK(again->style()->water_y == pack->style()->water_y);
    arena.reset();
    auto first = again->decode(mine[0], zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE(first.has_value(), tl::describe(first.error()));
    MESSAGE("the canal ring's ", mine.size(), " tiles in a pack of ", bytes->size(),
            " bytes (the packer's: ", pack->source().size(), ")");
    // a tile twice, or of a level rhead lacks, is refused
    std::vector<tl::PackTile> twice{tiles[0], tiles[0]};
    const auto refused =
        tl::write_pack(twice, {.levels = pack->head()->levels, .head = *head_bytes, .style = style_tables});
    REQUIRE(!refused.has_value());
    CHECK(refused.error().fault == tl::Fault::duplicate);
    const std::array<std::uint8_t, 1> other{17};
    const auto off_level =
        tl::write_pack(std::span(tiles).first(1), {.levels = other, .head = *head_bytes, .style = style_tables});
    REQUIRE(!off_level.has_value());
    CHECK(off_level.error().fault == tl::Fault::bad_value);
}
