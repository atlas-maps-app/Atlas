// The tile decoder (tiles/Payload.hpp, Columns.hpp) on tiles written to the specification by the tests' own writer
// (support.hpp): every codec's values through the layer's accessors and the canonical dump, the shared vertices of the
// geometry codec, keep codes, skipped layers and columns (by Format's one rule), the header's bounds, a tile against
// its chunk and its reach, and every refusal with the field it names.
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Columns.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <functional>
#include <string>

namespace {

using namespace atlas::tiles;
using atlas::test::ColumnBytes;
using atlas::test::held;
using atlas::test::LayerBytes;
using atlas::test::Ring;
using atlas::test::Sink;
using atlas::test::TileBytes;
namespace col = atlas::test::columns;

/// Three buildings (two sharing a wall), three trees and one item, in the Westerkerk's L14 tile.
struct Fixture {
    TileBytes t;
    std::vector<Ring> rings;

    Fixture() {
        const std::int32_t s = 523'740'000;
        const std::int32_t w = 48'790'000;
        // A has six vertices; B shares A's eastern wall (its vertices 2 and 3, walked backwards), so it repeats them
        rings.push_back({{s, s, s + 300, s + 300, s + 150, s + 100},
                         {w, w + 400, w + 400, w, w - 50, w - 60},
                         {13, 0, 5, 13, 2, 13}});
        rings.push_back({{s + 300, s, s - 10, s - 20, s + 310, s + 320},
                         {w + 400, w + 400, w + 900, w + 1000, w + 950, w + 70000}, // the last delta escapes the planes
                         {13, 13, 1, 0, 9, 13}});
        rings.push_back(
            {{s + 5000, s + 5010, s + 5020, s + 5000}, {w + 5000, w + 5010, w + 5000, w + 5020}, {13, 13, 13, 13}});
        LayerBytes b;
        b.id = static_cast<std::uint8_t>(LayerId::building);
        b.cells = {0, 0, 9};
        Sink extra;
        extra.u8(0); // null
        extra.u8(8); // {"a": 1, "b": <true, 2.5, "x">}
        extra.varu(2);
        extra.varu(t.string("a"));
        extra.u8(3);
        extra.varz(1);
        extra.varu(t.string("b"));
        extra.u8(7);
        extra.varu(3);
        extra.u8(2);
        extra.u8(5);
        extra.f64(2.5);
        extra.u8(6);
        extra.varu(t.string("x"));
        extra.u8(4); // a Long
        extra.varz(-7);
        b.columns = {
            {"index", Codec::delta, col::delta({3, 7, 12})},
            {"id", Codec::delta, col::delta({100, 200, 300})},
            {"ring", Codec::geom, col::geom(rings)},
            {"ring.keep", Codec::keep, col::keep(rings)},
            {"h", Codec::centi, col::centi({12.5, std::nullopt, 1.0 / 3.0})},
            {"year", Codec::nvarz, col::nvarz({1650, std::nullopt, -5})},
            {"roof", Codec::str, col::str(t, {"gabled", std::nullopt, "flat"})},
            {"hidden", Codec::boolean, col::boolean({std::nullopt, false, true})},
            {"guessed", Codec::strs,
             col::strs(t,
                       {std::nullopt, std::vector<std::string>{}, std::vector<std::string>{"height", "roof:shape"}})},
            {"places", Codec::ids, col::ids({{}, {5, 3, 9}, {}})},
            {"flags", Codec::varu, col::varu({0, 16, 208})},
            {"wall_rgb", Codec::varu, col::varu({0xA0522D, 0, 0xFFFFFF})},
            {"extra", Codec::value, extra.bytes}, // payload 1's first schema had it; now a column the schema lacks
            {"col_draw", Codec::str, col::str(t, {"#aa0000", std::nullopt, std::nullopt})}, // likewise
            {"tag:building", Codec::str, col::str(t, {"house", "apartments", std::nullopt})},
        };
        t.layers.push_back(std::move(b));

        LayerBytes trees;
        trees.id = static_cast<std::uint8_t>(LayerId::tree);
        trees.cells = {5, 5, 63};
        trees.columns = {
            {"index", Codec::delta, col::delta({0, 1, 2})},
            {"node", Codec::delta, col::delta({40, 41, 45})},
            {"id", Codec::delta, col::delta({9001, 9002, 9003})},
            {"pos", Codec::point, col::point({{s + 1, w + 1}, {s + 2, w - 2}, {s - 3, w + 3}})},
            {"tag:addr:housenumber", Codec::strn, col::strn(t, {"12", "12a", std::nullopt})},
        };
        t.layers.push_back(std::move(trees));

        LayerBytes items;
        items.id = static_cast<std::uint8_t>(LayerId::item);
        items.cells = {9};
        items.columns = {
            {"source", Codec::u8, col::u8({2})},
            {"pos", Codec::point, col::point({{s + 7, w + 7}})},
            {"tags", Codec::tags, col::tags(t, {{{"man_made", "tower"}, {"height", "43"}}})},
            {"door_number", Codec::ndelta, {}},                              // a column items do not have: skipped
            {"later", static_cast<Codec>(99), {std::byte{1}, std::byte{2}}}, // whatever its codec byte
            {"x", Codec::centi, col::centi({12.25})},
        };
        t.layers.push_back(std::move(items));
    }

    [[nodiscard]] Result<Tile> decode(atlas::Arena& arena, TileDecoder& decoder, std::vector<std::byte>& raw) {
        raw = t.encode();
        return decoder.decode(raw, arena);
    }
};

TEST_CASE("tiles: every codec decodes to the writer's values") {
    Fixture f;
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    std::vector<std::byte> raw;
    auto tile = f.decode(arena, decoder, raw);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    CHECK(tile->header.level == 14);
    CHECK(tile->header.cells_per_side == 8);
    CHECK(tile->feature_count() == 7);
    CHECK(tile->skipped.empty());

    const Layer* b = tile->layer(LayerId::building);
    ATLAS_REQUIRE(b != nullptr);
    CHECK(b->count == 3);
    CHECK(b->cell(0) == std::pair<std::uint32_t, std::uint32_t>{0, 2});
    CHECK(b->cell(9) == std::pair<std::uint32_t, std::uint32_t>{2, 3});
    CHECK(b->integer(building::index, 0) == 3);
    CHECK(b->integer(building::index, 2) == 12);
    CHECK(b->integer(building::id, 1) == 200);
    for (std::size_t i = 0; i < 3; ++i) {
        const RingView r = b->ring(building::ring, i);
        ATLAS_REQUIRE(r.size() == f.rings[i].lat.size());
        CHECK(r.has_keep);
        for (std::size_t k = 0; k < r.size(); ++k) {
            CHECK(r.lat[k] == f.rings[i].lat[k]);
            CHECK(r.lon[k] == f.rings[i].lon[k]);
            CHECK(r.keep[k] == f.rings[i].keep[k]);
        }
    }
    CHECK(b->decimal(building::h, 0) == 12.5);
    CHECK(!b->decimal(building::h, 1));
    CHECK(std::bit_cast<std::uint64_t>(held(b->decimal(building::h, 2))) == std::bit_cast<std::uint64_t>(1.0 / 3.0));
    CHECK(b->nullable_integer(building::year, 0) == 1650);
    CHECK(!b->nullable_integer(building::year, 1));
    CHECK(b->nullable_integer(building::year, 2) == -5);
    CHECK(b->string(building::roof, 0) == "gabled");
    CHECK(!b->string(building::roof, 1));
    CHECK(!b->boolean(building::hidden, 0));
    CHECK(b->boolean(building::hidden, 1) == false);
    CHECK(b->boolean(building::hidden, 2) == true);
    CHECK(b->ids(building::places, 1).size() == 3);
    CHECK(b->ids(building::places, 1)[2] == 9);
    CHECK(b->ids(building::places, 0).empty());
    CHECK(!b->string_list(b->schema->slot("guessed"), 0));
    CHECK(held(b->string_list(b->schema->slot("guessed"), 1)).empty());
    const auto guessed = b->string_list(b->schema->slot("guessed"), 2);
    ATLAS_REQUIRE(guessed && guessed->size() >= 2);
    CHECK(b->strings[(*guessed)[1]] == "roof:shape");
    CHECK(b->integer(building::flags, 2) == 208);
    CHECK((static_cast<std::uint64_t>(b->integer(building::flags, 2)) & building::flag_wall_drawn) != 0);
    CHECK(b->integer(building::wall_rgb, 0) == 0xA0522D);
    CHECK(b->integer(building::wall_rgb, 1) == 0);
    CHECK(b->integer(building::roof_rgb, 0) == 0); // a column the tile leaves out
    CHECK(b->string(building::tag_building, 1) == "apartments");
    CHECK(!b->string(building::mat_draw, 0)); // a column the tile leaves out holds its default

    const Layer* trees = tile->layer(LayerId::tree);
    ATLAS_REQUIRE(trees != nullptr);
    CHECK(trees->point(tree::pos, 1) == GridPoint{523'740'002, 48'789'998});
    const std::uint8_t number = trees->schema->slot("tag:addr:housenumber");
    CHECK(trees->string_or_number(number, 0).kind == StringOrNumber::Kind::number);
    CHECK(trees->string_or_number(number, 0).number == 12);
    CHECK(trees->string_or_number(number, 1).text == "12a");
    CHECK(trees->string_or_number(number, 2).kind == StringOrNumber::Kind::null);

    const Layer* items = tile->layer(LayerId::item);
    ATLAS_REQUIRE(items != nullptr);
    CHECK(items->tag(item::tags, 0, "height") == "43");
    CHECK(items->tag_count(item::tags, 0) == 2);
    CHECK(items->integer(item::source, 0) == 2);
    CHECK(items->decimal(item::x, 0) == 12.25);
    CHECK(!items->decimal(item::z, 0));
}

TEST_CASE("tiles: VALUE columns decode typed values, though no layer of this payload has one") {
    std::vector<std::string_view> table = {"a", "b", "x"};
    Sink v;
    v.u8(0); // null
    v.u8(8); // {"a": 1, "b": <true, 2.5, "x">}
    v.varu(2);
    v.varu(0);
    v.u8(3);
    v.varz(1);
    v.varu(1);
    v.u8(7);
    v.varu(3);
    v.u8(2);
    v.u8(5);
    v.f64(2.5);
    v.u8(6);
    v.varu(2);
    v.u8(4); // a Long
    v.varz(-7);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder::Scratch scratch;
    auto decode = [&](const std::vector<std::byte>& bytes, std::uint32_t count) {
        const ColumnInput in{
            .layer = LayerId::building, .name = "values", .codec = Codec::value, .count = count, .strings = table};
        return decode_column(in, bytes, nullptr, arena, scratch);
    };
    const auto c = decode(v.bytes, 3);
    ATLAS_REQUIRE(c, describe(c.error()));
    ATLAS_REQUIRE(c->refs.size() == 3);
    CHECK(Value(c->nodes, table, c->refs[0]).is_null());
    const Value m(c->nodes, table, c->refs[1]);
    ATLAS_REQUIRE(m.type() == ValueType::map);
    CHECK(held(m.find("a")).integer() == 1);
    ATLAS_REQUIRE(held(m.find("b")).size() == 3);
    CHECK(held(m.find("b")).child(1).real() == 2.5);
    CHECK(held(m.find("b")).child(2).string() == "x");
    CHECK(Value(c->nodes, table, c->refs[2]).integer() == -7);
    CHECK(Value(c->nodes, table, c->refs[2]).type() == ValueType::int64);

    SUBCASE("a value nested 34 deep") {
        Sink deep;
        for (int i = 0; i < 34; ++i) {
            deep.u8(7);
            deep.varu(1);
        }
        deep.u8(0);
        const auto e = decode(deep.bytes, 1);
        ATLAS_REQUIRE(!e);
        CHECK(e.error().fault == Fault::too_big);
        CHECK(e.error().column_name() == "values");
    }
    SUBCASE("a map's key twice") {
        Sink twice;
        twice.u8(8);
        twice.varu(2);
        twice.varu(0);
        twice.u8(0);
        twice.varu(0);
        twice.u8(1);
        const auto e = decode(twice.bytes, 1);
        ATLAS_REQUIRE(!e);
        CHECK(e.error().fault == Fault::duplicate);
    }
}

TEST_CASE("tiles: the canonical dump writes what Kotlin's CanonicalDump writes") {
    Fixture f;
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    std::vector<std::byte> raw;
    auto tile = f.decode(arena, decoder, raw);
    ATLAS_REQUIRE(tile);
    const std::string dump = canonical_dump(*tile);
    const auto box = f.t.grid_box();
    CHECK(dump.starts_with("tile 14/8414/5384 n=8\n"));
    CHECK(dump.contains(std::format("cell 0 box={},{},{},{} top=0 building=2\n", box[0], box[1], box[2], box[3])));
    CHECK(
        dump.contains(std::format("cell 9 box={},{},{},{} top=0 building=1 item=1\n", box[0], box[1], box[2], box[3])));
    CHECK(dump.contains(
        "\nbuilding @0 h=d4029000000000000 id=100 index=3 ring=[523740000,48790000/13;523740000,48790400/0;"
        "523740300,48790400/5;523740300,48790000/13;523740150,48789950/2;523740100,48789940/13] "
        "roof=\"gabled\" tag:building=\"house\" wall_rgb=10506797 year=1650\n"));
    CHECK(dump.contains("\nbuilding @0 flags=16 guessed=<> hidden=false id=200 index=7 places=<5,3,9> ring=["));
    CHECK(dump.contains("\nbuilding @9 flags=208 guessed=<\"height\",\"roof:shape\"> h=d3fd5555555555555 hidden=true "
                        "id=300 index=12 ring=[523745000,48795000/13;523745010,48795010/13;523745020,48795000/13;"
                        "523745000,48795020/13] roof=\"flat\" wall_rgb=16777215 year=-5\n"));
    CHECK(dump.contains("\ntree @5 id=9001 node=40 pos=(523740001,48790001) tag:addr:housenumber=\"12\"\n"));
    CHECK(dump.contains("\ntree @5 id=9002 index=1 node=41 pos=(523740002,48789998) tag:addr:housenumber=\"12a\"\n"));
    CHECK(dump.contains("\ntree @63 id=9003 index=2 node=45 pos=(523739997,48790003)\n"));
    CHECK(dump.contains("\nitem @9 pos=(523740007,48790007) source=2 tags={\"man_made\":\"tower\",\"height\":\"43\"} "
                        "x=d4028800000000000\n"));
    CHECK(!dump.contains("extra"));
    CHECK(!dump.contains("col_draw"));
    CHECK(!dump.contains("later"));
    CHECK(hex(canonical_hash(*tile)) == hex(Sha256::of(as_bytes(dump))));
}

TEST_CASE("tiles: a layer this reader does not define is counted and skipped") {
    Fixture f;
    LayerBytes built;
    built.id = static_cast<std::uint8_t>(LayerId::built);
    built.cells = {1, 9};
    built.columns = {{"density", Codec::varu, col::varu({7, 8})}};
    f.t.layers.insert(f.t.layers.begin() + 1, built);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    std::vector<std::byte> raw;
    auto tile = f.decode(arena, decoder, raw);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    ATLAS_REQUIRE(tile->skipped.size() == 1);
    CHECK(tile->skipped[0].id == static_cast<std::uint8_t>(LayerId::built));
    CHECK(tile->skipped[0].count == 2);
    CHECK(tile->feature_count() == 9);
    CHECK(tile->cells[1].total == 1);
    const std::string dump = canonical_dump(*tile);
    CHECK(!dump.contains("cell 1 ")); // only the skipped layer's features lie there
    CHECK(dump.contains("top=0 building=1 item=1\n"));
}

TEST_CASE(
    "tiles: keep codes without their ring column make empty rings that print, and a small ring's codes are all 13") {
    TileBytes t;
    LayerBytes g;
    g.id = static_cast<std::uint8_t>(LayerId::ground);
    g.cells = {0, 1};
    g.columns = {{"ring.keep", Codec::keep, {}}, {"kind", Codec::str, col::str(t, {"park", "square"})}};
    t.layers.push_back(g);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto raw = t.encode();
    auto tile = decoder.decode(raw, arena);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    const std::string dump = canonical_dump(*tile);
    CHECK(dump.contains("\nground @0 kind=\"park\" ring=[]\n"));
    CHECK(dump.contains("\nground @1 kind=\"square\" ring=[]\n"));
}

/// Decodes the fixture after `change`, expecting a refusal at `site` (and for a column, its layer and name).
void refused(const std::function<void(Fixture&)>& change, Fault fault, Site site, LayerId layer = LayerId::none,
             std::string_view column = {}) {
    Fixture f;
    change(f);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    std::vector<std::byte> raw;
    auto tile = f.decode(arena, decoder, raw);
    ATLAS_REQUIRE(!tile, "decoded what should be refused");
    INFO(describe(tile.error()));
    CHECK(tile.error().fault == fault);
    CHECK(tile.error().site == site);
    if (layer != LayerId::none) CHECK(tile.error().layer == layer);
    if (!column.empty()) CHECK(tile.error().column_name() == column);
}

ColumnBytes& column(Fixture& f, std::size_t layer, std::string_view name) {
    for (auto& c : f.t.layers[layer].columns) {
        if (c.name == name) return c;
    }
    FAIL("no column " << name);
    return f.t.layers[layer].columns[0];
}

TEST_CASE("tiles: a damaged value is refused, naming its column") {
    SUBCASE("a keep code past 15") {
        refused([](Fixture& f) { column(f, 0, "ring.keep").bytes[1] = std::byte{16}; }, Fault::bad_value, Site::column,
                LayerId::building, "ring.keep");
    }
    SUBCASE("a CENTI tag of 3") {
        refused([](Fixture& f) { column(f, 0, "h").bytes = col::varu({3, 1, 1}); }, Fault::bad_value, Site::column,
                LayerId::building, "h");
    }
    SUBCASE("a CENTI null that is not 1") {
        refused([](Fixture& f) { column(f, 0, "h").bytes = col::varu({1, 5, 1}); }, Fault::bad_value, Site::column,
                LayerId::building, "h");
    }
    SUBCASE("a boolean of 3") {
        refused([](Fixture& f) { column(f, 0, "hidden").bytes = col::u8({0, 3, 1}); }, Fault::bad_value, Site::column,
                LayerId::building, "hidden");
    }
    SUBCASE("a string past the table") {
        refused([](Fixture& f) { column(f, 0, "roof").bytes = col::varu({0, 999, 0}); }, Fault::bad_value, Site::column,
                LayerId::building, "roof");
    }
    SUBCASE("a tag twice") {
        refused([](Fixture& f) { column(f, 2, "tags").bytes = col::tags(f.t, {{{"height", "1"}, {"height", "2"}}}); },
                Fault::duplicate, Site::column, LayerId::item, "tags");
    }
    SUBCASE("a house number below zero") {
        refused(
            [](Fixture& f) {
                Sink v;
                v.varu(2 * 3 + 2); // the zig-zag of −2: a number below 0
                v.varu(0);
                v.varu(0);
                column(f, 1, "tag:addr:housenumber").bytes = v.bytes;
            },
            Fault::bad_value, Site::column, LayerId::tree, "tag:addr:housenumber");
    }
    SUBCASE("a value left over") {
        refused([](Fixture& f) { column(f, 0, "flags").bytes = col::varu({0, 16, 80, 1}); }, Fault::left_over,
                Site::column, LayerId::building, "flags");
    }
    SUBCASE("a column cut short") {
        refused([](Fixture& f) { column(f, 0, "year").bytes = col::varu({3}); }, Fault::truncated, Site::column,
                LayerId::building, "year");
    }
    SUBCASE("a point past 32 bits") {
        refused(
            [](Fixture& f) {
                Sink lat;
                lat.varz(3'000'000'000LL);
                Sink lon;
                lon.varz(0);
                Sink s;
                s.block(lat);
                s.block(lon);
                column(f, 2, "pos").bytes = s.bytes;
            },
            Fault::overflow, Site::column, LayerId::item, "pos");
    }
}

TEST_CASE("tiles: a damaged geometry is refused, naming its column") {
    SUBCASE("a repeat of a vertex not yet seen") {
        refused(
            [](Fixture& f) {
                auto& c = column(f, 0, "ring");
                // the first references block: B's first vertex repeats A's 4th (3 back from vertex 6); make it 9 back
                Reader r(c.bytes);
                std::vector<Reader> blocks;
                blocks.reserve(10);
                for (int i = 0; i < 10; ++i) blocks.push_back(r.block());
                Sink s;
                for (std::size_t i = 0; i < 10; ++i) {
                    Sink b;
                    const auto bytes = blocks[i].bytes(blocks[i].remaining());
                    b.raw(bytes);
                    if (i == 8) b.bytes[0] = std::byte{9};
                    s.block(b);
                }
                c.bytes = s.bytes;
            },
            Fault::bad_value, Site::column, LayerId::building, "ring");
    }
    SUBCASE("a vertex past 32 bits") {
        refused(
            [](Fixture& f) {
                // one feature's single vertex, its jump from (0, 0) past the grid
                auto& b = f.t.layers[0];
                b.cells = {0};
                Sink counts;
                counts.varu(1);
                Sink kinds;
                kinds.u8(0);
                Sink jumps;
                jumps.varz(3'000'000'000LL);
                jumps.varz(0);
                Sink s;
                s.block(counts);
                s.block(kinds);
                s.block(jumps);
                for (int i = 0; i < 7; ++i) s.block(Sink{});
                b.columns = {{"ring", Codec::geom, s.bytes}};
            },
            Fault::overflow, Site::column, LayerId::building, "ring");
    }
    SUBCASE("a vertex kind of 2") {
        refused(
            [](Fixture& f) {
                auto& c = column(f, 0, "ring");
                Reader r(c.bytes);
                std::vector<std::vector<std::byte>> blocks;
                for (int i = 0; i < 10; ++i) {
                    Reader b = r.block();
                    const auto bytes = b.bytes(b.remaining());
                    blocks.emplace_back(bytes.begin(), bytes.end());
                }
                blocks[1][3] = std::byte{2};
                Sink s;
                for (const auto& b : blocks) {
                    Sink x;
                    x.raw(b);
                    s.block(x);
                }
                c.bytes = s.bytes;
            },
            Fault::bad_value, Site::column, LayerId::building, "ring");
    }
}

TEST_CASE("tiles: a damaged structure is refused where it is damaged") {
    SUBCASE("a POINT column missing") {
        refused(
            [](Fixture& f) {
                std::erase_if(f.t.layers[1].columns, [](const ColumnBytes& c) { return c.name == "pos"; });
            },
            Fault::missing, Site::column, LayerId::tree, "pos");
    }
    SUBCASE("a known column of another codec") {
        refused([](Fixture& f) { column(f, 0, "h").codec = Codec::varu; }, Fault::bad_value, Site::column,
                LayerId::building, "h");
    }
    SUBCASE("a column twice") {
        refused([](Fixture& f) { f.t.layers[0].columns.push_back({"flags", Codec::varu, col::varu({1, 2, 3})}); },
                Fault::duplicate, Site::column, LayerId::building, "flags");
    }
    SUBCASE("a layer twice") {
        refused([](Fixture& f) { f.t.layers.push_back(f.t.layers[2]); }, Fault::duplicate, Site::layers);
    }
    SUBCASE("more columns than the layer holds") {
        refused([](Fixture& f) { f.t.layers[0].column_count_override = 99; }, Fault::truncated, Site::column,
                LayerId::building);
    }
    SUBCASE("a level past the finest") {
        refused([](Fixture& f) { f.t.level = 25; }, Fault::bad_value, Site::tile);
    }
    SUBCASE("a column past its zoom's grid") {
        refused([](Fixture& f) { f.t.x = 1U << 14U; }, Fault::bad_value, Site::tile);
    }
    SUBCASE("no cells") {
        refused([](Fixture& f) { f.t.n = 0; }, Fault::bad_value, Site::tile);
    }
    SUBCASE("cells a side other than the level's: 4 at L14, 8 at L12") {
        refused([](Fixture& f) { f.t.n = 4; }, Fault::bad_value, Site::tile);
        refused(
            [](Fixture& f) {
                f.t.level = 12;
                f.t.x = 2103;
                f.t.y = 1346;
            },
            Fault::bad_value, Site::tile);
    }
}

TEST_CASE("tiles: a tile's box must be the grid's, to the unit at each edge") {
    for (std::size_t edge = 0; edge < 4; ++edge) {
        refused(
            [edge](Fixture& f) {
                auto b = f.t.grid_box();
                b[edge] += 1;
                f.t.box = b;
            },
            Fault::bad_value, Site::tile);
    }
    Fixture f;
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    std::vector<std::byte> raw;
    auto tile = f.decode(arena, decoder, raw);
    ATLAS_REQUIRE(tile);
    const TileHeader& h = tile->header;
    CHECK(std::array{h.south, h.west, h.north, h.east} == f.t.grid_box());
}

TEST_CASE("tiles: a tile of more than 4 MiB is refused before anything is read") {
    std::vector<std::byte> big(std::size_t{max_tile_bytes} + 1);
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    const auto tile = decoder.decode(big, arena);
    ATLAS_REQUIRE(!tile);
    CHECK(tile.error().fault == Fault::too_big);
    CHECK(tile.error().site == Site::tile);
}

TEST_CASE("tiles: a tile reaches where its cells' boxes and tops say, and is checked against its chunk") {
    Fixture f;
    // cell 0's features reach past the tile to the west; cell 63's draw 31 m up; cell 9 keeps the tile's box
    const auto tb = f.t.grid_box();
    f.t.cell_boxes[0] = {{tb[0] + 10, tb[1] - 4000, tb[0] + 900, tb[1] + 700}, 1250};
    f.t.cell_boxes[63] = {{tb[0] - 30, tb[3] - 500, tb[0] + 20, tb[3] + 60}, 3100};
    f.t.cell_boxes[5] = {{tb[0], tb[1], tb[2], tb[3]}, -40}; // a top below the street counts as the street
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    std::vector<std::byte> raw;
    auto tile = f.decode(arena, decoder, raw);
    ATLAS_REQUIRE(tile, describe(tile.error()));
    const auto reach = reach_of(*tile);
    ATLAS_REQUIRE(reach);
    const Reach want = f.t.reach();
    CHECK(*reach == want);
    CHECK(reach->box == GridBox{.south = tb[0] - 30, .west = tb[1] - 4000, .north = tb[2], .east = tb[3] + 60});
    CHECK(reach->top_cm == 3100);

    ChunkRef chunk;
    chunk.tile = {14, 8414, 5384};
    chunk.reach = want;
    CHECK(check_against(*tile, chunk));
    ChunkRef elsewhere = chunk;
    elsewhere.tile.x += 1;
    CHECK(check_against(*tile, elsewhere).error().site == Site::tile);
    ChunkRef shorter = chunk;
    shorter.reach.box.west += 1;
    CHECK(check_against(*tile, shorter).error().site == Site::reach);
    ChunkRef lower = chunk;
    lower.reach.top_cm -= 1;
    CHECK(check_against(*tile, lower).error().fault == Fault::bad_count);

    // a tile without features reaches nowhere
    TileBytes empty;
    std::vector<std::byte> none = empty.encode();
    auto nothing = decoder.decode(none, arena);
    ATLAS_REQUIRE(nothing);
    CHECK(reach_of(*nothing).error().fault == Fault::missing);
}

TEST_CASE("tiles: the header and the blocks are checked before anything is read") {
    Fixture f;
    auto raw = f.t.encode();
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    auto decode = [&](std::vector<std::byte> b) { return decoder.decode(b, arena); };
    {
        auto b = raw;
        b[0] = std::byte{'X'};
        CHECK(decode(b).error().fault == Fault::bad_magic);
    }
    {
        auto b = raw;
        b[4] = std::byte{2};
        CHECK(decode(b).error().fault == Fault::bad_version);
    }
    {
        auto b = raw;
        b[32] = std::byte{0xFF}; // the string table's offset, past the tile
        b[33] = std::byte{0xFF};
        b[34] = std::byte{0xFF};
        CHECK(decode(b).error().site == Site::strings);
    }
    {
        auto b = raw;
        b.resize(51);
        CHECK(decode(b).error().fault == Fault::truncated);
    }
    {
        auto b = raw;
        b.resize(b.size() - 1); // the last layer's block runs past the tile
        CHECK(decode(b).error().site == Site::layers);
    }
    CHECK(decode(raw)); // and the tile itself still decodes
}

TEST_CASE("tiles: counts that disagree are refused") {
    Fixture f;
    auto raw = f.t.encode();
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    // the building layer's feature count in its directory entry (bytes 56–59): one more than its cells hold
    auto b = raw;
    b[56] = std::byte{4};
    auto tile = decoder.decode(b, arena);
    ATLAS_REQUIRE(!tile);
    CHECK(tile.error().fault == Fault::bad_count);
    CHECK(tile.error().site == Site::layers);
}

} // namespace
