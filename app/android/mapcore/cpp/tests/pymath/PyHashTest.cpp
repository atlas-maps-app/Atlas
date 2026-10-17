// CPython's hash() in C++ (base/pymath/PyHash.hpp): goldens from CPython 3.9.6 with PYTHONHASHSEED=0 (SipHash's key
// zero, as the port's) for ints, floats, str (SipHash-2-4 over CPython's one-, two- and four-byte forms) and tuples of
// each, the renders' own tuples included. SipHash-1-3 (3.11 and later) against the port is
// MT/numerics/PyRandomParityTest.kt's.
#include "base/pymath/PyHash.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string_view>

// NOLINTBEGIN(modernize-use-designated-initializers): golden rows, positional as they were printed

namespace {

namespace pm = atlas::pymath;

} // namespace

TEST_CASE("ints hash as CPython's, reduced modulo 2^61 - 1") {
    CHECK(pm::hash_int(0) == 0);
    CHECK(pm::hash_int(1) == 1);
    CHECK(pm::hash_int(-1) == -2);
    CHECK(pm::hash_int(-2) == -2);
    CHECK(pm::hash_int((std::int64_t{1} << 61) - 1) == 0);
    CHECK(pm::hash_int(std::int64_t{1} << 61) == 1);
    CHECK(pm::hash_int(std::numeric_limits<std::int64_t>::max()) == 3);
    CHECK(pm::hash_int(std::numeric_limits<std::int64_t>::min()) == -4);
    CHECK(pm::hash_int(12345678901234567) == 12345678901234567);
    CHECK(pm::hash_unsigned(std::numeric_limits<std::uint64_t>::max()) == 7);
}

TEST_CASE("floats hash as CPython's, integral ones as their ints") {
    struct Case {
        double x;
        std::int64_t h;
    };
    const std::array<Case, 14> cases{
        Case{0.0, 0},
        Case{-0.0, 0},
        Case{0.5, 1152921504606846976},
        Case{1.5, 1152921504606846977},
        Case{-1.5, -1152921504606846977},
        Case{1e300, 1224995262755759164},
        Case{5e-324, 16777216},
        Case{2.5e-310, 386353265232576880},
        Case{1e22, 1864712049423028464},
        Case{3.14159, 326484311674566659},
        Case{-273.15, -345876451382001937},
        Case{std::numeric_limits<double>::infinity(), 314159},
        Case{-std::numeric_limits<double>::infinity(), -314159},
        Case{123.4, 922337203685490811},
    };
    for (const Case& c : cases) {
        INFO(c.x);
        CHECK(pm::hash_float(c.x).value() == c.h);
    }
    CHECK(pm::hash_float(7.0).value() == pm::hash_int(7));
    CHECK(pm::hash_float(-1.0).value() == -2);
    CHECK(pm::hash_float(std::nan("")).error() == pm::Error::not_hashable);
}

TEST_CASE("strs hash as CPython 3.9's, in one, two or four bytes a code point") {
    struct Case {
        std::string_view text;
        std::int64_t h;
    };
    const std::array<Case, 10> cases{
        Case{"", 0},
        Case{"a", -7583489610679606711},
        Case{"roof", -6279385849719433871},
        Case{"bk", -2336064738915967939},
        Case{"café", -1432176324953013652},              // Latin-1: one byte each
        Case{"Ⅻ", -7783520191093633826},                 // two bytes
        Case{"名字", 4462667506049234100},               // two bytes
        Case{"\U0001F600", 2694534307785527896},         // four bytes
        Case{"a\U0001F600b", 2199712620763043091},       // four bytes each, the ASCII too
        Case{"abcdefghijklmnopq", -6196428185306681828}, // more than two SipHash words
    };
    for (const Case& c : cases) {
        INFO(c.text);
        CHECK(pm::hash_str(c.text).value() == c.h);
    }
    // 3.11 and later hash with SipHash-1-3: a different stream.
    CHECK(pm::hash_str("roof", pm::Compat{12}).value() != pm::hash_str("roof").value());
    CHECK(pm::hash_str("roof", pm::Compat{11}).value() == pm::hash_str("roof", pm::Compat{12}).value());
    // A str's hash is the hash of its CPython bytes: for ASCII, its UTF-8.
    constexpr std::string_view roof = "roof";
    CHECK(pm::hash_bytes(std::as_bytes(std::span(roof.data(), roof.size()))) == pm::hash_str(roof).value());
}

TEST_CASE("text that is not UTF-8 was never a str; surrogates were") {
    CHECK(pm::hash_str("\xc0\x80").error() == pm::Error::invalid_text);         // overlong
    CHECK(pm::hash_str("\xf4\x90\x80\x80").error() == pm::Error::invalid_text); // past U+10FFFF
    CHECK(pm::hash_str("\xe2\x82").error() == pm::Error::invalid_text);         // truncated
    CHECK(pm::hash_str("\x80").error() == pm::Error::invalid_text);             // a stray continuation
    CHECK(pm::hash_str("\xed\xa0\x80").has_value());                            // U+D800, a lone surrogate
}

TEST_CASE("tuples hash as CPython 3.9's, the renders' seeds included") {
    CHECK(pm::hash_tuple(pm::renders).value() == 5740354900026072187);
    CHECK(pm::hash_tuple(std::int64_t{1}, std::int64_t{2}) == -3550055125485641917);
    CHECK(pm::hash_tuple(1.5, -2.25) == -6151272265558060089);
    CHECK(pm::hash_tuple("roof", 12.3, -45.6) == -7064294287997192737);
    // (round(101.26, 1), round(-7.04, 1)): Random(hash((round(x, 1), round(z, 1))) & 0xffffff) in living.py.
    CHECK(pm::hash_tuple(pm::round(101.26, 1), pm::round(-7.04, 1)) == -8740681005539048808);
    CHECK(pm::hash_tuple(7, 1.5, "bk", true) == 6059862960484001204);
    pm::TupleHash h;
    h.add_hash(pm::hash_float(1.5).value());
    h.add_hash(pm::hash_float(-2.25).value());
    CHECK(h.value() == -6151272265558060089);
    CHECK(pm::hash_tuple(pm::renders, 1.0, std::nan("")).error() == pm::Error::not_hashable);
}

// NOLINTEND(modernize-use-designated-initializers)
