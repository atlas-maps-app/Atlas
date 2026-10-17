// Typed values (tiles/Values.hpp) and the canonical text of strings and doubles (tiles/Dump.hpp).
#include "tests/tiles/support.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Values.hpp"
#include "tiles/layers/Labels.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <limits>

namespace {

using namespace atlas::tiles;
using atlas::test::Sink;

TEST_CASE("tiles: values of every type, with written-out strings") {
    Sink s;
    s.u8(8); // {"n": null, "f": false, "t": true, "i": -2147483648, "l": 9007199254740993, "d": -0.0, "s": "é", "l2":
             // <1, <>>}
    s.varu(8);
    s.utf8("n");
    s.u8(0);
    s.utf8("f");
    s.u8(1);
    s.utf8("t");
    s.u8(2);
    s.utf8("i");
    s.u8(3);
    s.varz(std::numeric_limits<std::int32_t>::min());
    s.utf8("l");
    s.u8(4);
    s.varz(9'007'199'254'740'993LL);
    s.utf8("d");
    s.u8(5);
    s.f64(-0.0);
    s.utf8("s");
    s.u8(6);
    s.utf8("\xC3\xA9");
    s.utf8("l2");
    s.u8(7);
    s.varu(2);
    s.u8(3);
    s.varz(1);
    s.u8(7);
    s.varu(0);
    ValueReader reader({}, true);
    Reader r(s.bytes);
    const auto root = reader.read(r);
    REQUIRE(root);
    CHECK(r.done());
    const Value v(reader.nodes(), reader.strings(), *root);
    REQUIRE(v.type() == ValueType::map);
    CHECK(v.size() == 8);
    using atlas::test::held;
    CHECK(held(v.find("n")).is_null());
    CHECK(held(v.find("f")).boolean() == false);
    CHECK(held(v.find("t")).boolean() == true);
    CHECK(held(v.find("i")).integer() == std::numeric_limits<std::int32_t>::min());
    CHECK(held(v.find("i")).type() == ValueType::int32);
    CHECK(held(v.find("l")).integer() == 9'007'199'254'740'993LL);
    CHECK(std::bit_cast<std::uint64_t>(held(held(v.find("d")).real())) == 0x8000'0000'0000'0000ULL);
    CHECK(held(v.find("s")).string() == "\xC3\xA9");
    CHECK(held(v.find("l2")).size() == 2);
    CHECK(held(v.find("l2")).child(1).size() == 0);
    CHECK(held(v.find("l2")).child(1).type() == ValueType::list);
    CHECK(v.key(7) == "l2");
    CHECK(!v.find("missing"));
    CHECK(held(v.find("l")).number() == 9'007'199'254'740'992.0); // Python's float(int): to nearest, ties to even
}

TEST_CASE("tiles: values refuse an Int past 32 bits, a list longer than its bytes, an unknown type, a string past the "
          "table") {
    auto refused = [](const Sink& s, bool inline_strings, Fault fault) {
        const std::array<std::string_view, 1> table = {"only"};
        ValueReader reader(inline_strings ? std::span<const std::string_view>{}
                                          : std::span<const std::string_view>(table),
                           inline_strings);
        Reader r(s.bytes);
        const auto v = reader.read(r);
        REQUIRE(!v);
        CHECK(v.error().fault == fault);
    };
    Sink big;
    big.u8(3);
    big.varz(std::int64_t{1} << 31U);
    refused(big, true, Fault::overflow);
    Sink list;
    list.u8(7);
    list.varu(5);
    list.u8(0);
    refused(list, true, Fault::bad_count);
    Sink unknown;
    unknown.u8(9);
    refused(unknown, true, Fault::bad_value);
    Sink past;
    past.u8(6);
    past.varu(1);
    refused(past, false, Fault::bad_value);
    Sink bad_text;
    bad_text.u8(6);
    bad_text.varu(1);
    bad_text.u8(0xFF);
    refused(bad_text, true, Fault::bad_utf8);
    Sink nested; // 33 levels of lists: the deepest value lies at depth 33
    for (int i = 0; i < 33; ++i) {
        nested.u8(7);
        nested.varu(1);
    }
    nested.u8(0);
    refused(nested, true, Fault::too_big);
    Sink deepest; // 32 levels are fine
    for (int i = 0; i < 32; ++i) {
        deepest.u8(7);
        deepest.varu(1);
    }
    deepest.u8(0);
    ValueReader reader({}, true);
    Reader r(deepest.bytes);
    CHECK(reader.read(r));
    CHECK(r.done());
}

TEST_CASE("tiles: the canonical dump quotes strings and writes doubles as Kotlin does") {
    atlas::test::TileBytes t;
    atlas::test::LayerBytes l;
    l.id = static_cast<std::uint8_t>(LayerId::label);
    l.cells = {0, 0, 0};
    namespace col = atlas::test::columns;
    l.columns = {
        {"name", Codec::str,
         col::str(t, {R"(say "hi"\ok)", std::string_view("tab\there\x01\x1F\x7F"), "Prinsengracht \xE2\x80\x94"})},
        {"pos", Codec::point, col::point({{0, 0}, {-1, 1}, {5, -5}})},
        {"prev", Codec::point, col::point({{0, 0}, {0, 0}, {0, 0}})},
        {"id", Codec::nvarz,
         col::nvarz({std::numeric_limits<std::int64_t>::min() + 1, 0, std::nullopt})}, // NVARZ holds no Long.MIN_VALUE
        {"index", Codec::delta, col::delta({0, -1, std::numeric_limits<std::int64_t>::max()})},
    };
    t.layers.push_back(l);
    const auto raw = t.encode();
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "test");
    TileDecoder decoder;
    auto tile = decoder.decode(raw, arena);
    REQUIRE_MESSAGE(tile, describe(tile.error()));
    const std::string dump = canonical_dump(*tile);
    CHECK(dump.contains("\nlabel @0 id=-9223372036854775807 name=\"say \\\"hi\\\"\\\\ok\" pos=(0,0) prev=(0,0)\n"));
    CHECK(dump.contains("\nlabel @0 id=0 index=-1 name=\"tab\\u0009here\\u0001\\u001f\x7F\" pos=(-1,1) prev=(0,0)\n"));
    CHECK(dump.contains(
        "\nlabel @0 index=9223372036854775807 name=\"Prinsengracht \xE2\x80\x94\" pos=(5,-5) prev=(0,0)\n"));
}

} // namespace
