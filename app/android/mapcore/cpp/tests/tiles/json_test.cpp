// A JSON document as typed values (tiles/Json.hpp): each value as Python's json module and the port's PyJson read it
// (an int within 32 bits, a Long past them, a float with its sign and correctly rounded, strings decoded, keys in their
// order), every container's children together, and each refusal named at its byte.
#include "tests/base/require.hpp"
#include "tiles/Json.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <string_view>

namespace {

namespace tiles = atlas::tiles;
using tiles::ValueType;

std::expected<tiles::JsonValue, tiles::JsonError> read(std::string_view s) {
    return tiles::read_json(std::as_bytes(std::span(s.data(), s.size())));
}

/// A map's value for `key`, or null.
tiles::Value at(const tiles::Value& map, std::string_view key) { return map.find(key).value_or(tiles::Value()); }

/// The fault reading `s` gives, or nothing where it reads.
std::optional<tiles::JsonError::Fault> fault_of(std::string_view s) {
    const auto v = read(s);
    if (v) return std::nullopt;
    return v.error().fault;
}

} // namespace

TEST_CASE("json: values as Python's json module reads them") {
    const auto doc = read(R"( {"a": 1, "b": -2147483649, "c": 1.5, "d": "xé😀\n\/", "e": [true, false, null],
        "f": {"g": -0.0, "h": -0, "i": 1e400, "j": 0.1}, "k": 2147483647, "l": -9223372036854775808, "m": ""} )");
    ATLAS_REQUIRE(doc.has_value());
    const tiles::Value root = doc->root();
    REQUIRE(root.type() == ValueType::map);
    REQUIRE(root.size() == 9);
    const char* const keys[] = {"a", "b", "c", "d", "e", "f", "k", "l", "m"};
    for (std::size_t i = 0; i < root.size(); ++i) CHECK(root.key(i) == keys[i]); // in their order
    CHECK(at(root, "a").type() == ValueType::int32);
    CHECK(at(root, "a").integer() == 1);
    CHECK(at(root, "b").type() == ValueType::int64); // a Long, as PyJson's
    CHECK(at(root, "b").integer() == -2147483649LL);
    CHECK(at(root, "k").type() == ValueType::int32);
    CHECK(at(root, "l").integer() == std::numeric_limits<std::int64_t>::min());
    CHECK(at(root, "c").real() == 1.5);
    CHECK(at(root, "c").integer() == std::nullopt); // a float is not an int
    CHECK(at(root, "d").string() == "x\xc3\xa9\xf0\x9f\x98\x80\n/");
    CHECK(at(root, "m").string() == "");
    const tiles::Value e = at(root, "e");
    REQUIRE(e.size() == 3);
    CHECK(e.child(0).boolean() == true);
    CHECK(e.child(1).boolean() == false);
    CHECK(e.child(2).is_null());
    const tiles::Value f = at(root, "f");
    CHECK(std::bit_cast<std::uint64_t>(at(f, "g").real().value_or(0.0)) ==
          std::bit_cast<std::uint64_t>(-0.0));    // the sign kept
    CHECK(at(f, "h").type() == ValueType::int32); // int("-0") is 0
    CHECK(at(f, "h").integer() == 0);
    CHECK(std::isinf(at(f, "i").real().value_or(0.0)));
    CHECK(std::bit_cast<std::uint64_t>(at(f, "j").real().value_or(0.0)) == std::bit_cast<std::uint64_t>(0.1));
    // a container's children are together: the root's are its count of nodes from its first
    const auto nodes = root.nodes();
    const tiles::ValueNode& r = nodes[root.node()];
    CHECK(r.first + r.count <= root.node()); // the root comes last
}

TEST_CASE("json: a scalar document, and white space around it") {
    const auto s = read(" \t\r\n\"alone\" \n");
    ATLAS_REQUIRE(s.has_value());
    CHECK(s->root().string() == "alone");
    const auto n = read("-12.5e-1");
    ATLAS_REQUIRE(n.has_value());
    CHECK(n->root().real() == -1.25);
}

TEST_CASE("json: what it refuses, by name") {
    using Fault = tiles::JsonError::Fault;
    CHECK(fault_of(R"({"a": 1, "a": 2})") == Fault::duplicate); // Python keeps the last value in the first place
    CHECK(fault_of(R"(["\ud800"])") == Fault::bad_utf8);        // a lone surrogate
    CHECK(fault_of(R"(["\udc00x"])") == Fault::bad_utf8);
    CHECK(fault_of("[\"\xff\"]") == Fault::bad_utf8);
    CHECK(fault_of("[\"a\x01\"]") == Fault::syntax); // a control character unescaped
    CHECK(fault_of("[") == Fault::truncated);
    CHECK(fault_of("[1,]") == Fault::syntax);
    CHECK(fault_of("NaN") == Fault::syntax);
    CHECK(fault_of("[Infinity]") == Fault::syntax);
    CHECK(fault_of("01") == Fault::syntax);
    CHECK(fault_of("1 2") == Fault::syntax);
    CHECK(fault_of("\xef\xbb\xbf{}") == Fault::syntax); // a byte order mark
    CHECK(fault_of("") == Fault::truncated);
    CHECK(fault_of("99999999999999999999") == Fault::bad_number); // past 64 bits
    CHECK(fault_of("9223372036854775808") == Fault::bad_number);
    CHECK(fault_of("[1.]") == Fault::syntax);
    CHECK(fault_of("{\"a\" 1}") == Fault::syntax);
    std::string deep(33, '[');
    deep += std::string(33, ']');
    CHECK(fault_of(deep) == std::nullopt); // 32 levels below the root
    std::string deeper(34, '[');
    deeper += std::string(34, ']');
    CHECK(fault_of(deeper) == Fault::too_deep);
    const auto at = read(R"({"a": 1, "b": [1, 2, x]})");
    REQUIRE_FALSE(at.has_value());
    CHECK(at.error().at == 21);
    CHECK(tiles::name_of(Fault::duplicate) == "duplicate");
}
