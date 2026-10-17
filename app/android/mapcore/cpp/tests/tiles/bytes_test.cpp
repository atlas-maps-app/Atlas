// The byte reader (tiles/Bytes.hpp): fixed-width integers both ways round, strict varints, counts, blocks, UTF-8.
#include "tests/tiles/support.hpp"
#include "tiles/Bytes.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cmath>
#include <limits>
#include <vector>

namespace {

using atlas::tiles::Fault;
using atlas::tiles::Reader;

std::vector<std::byte> bytes(std::initializer_list<unsigned> values) {
    std::vector<std::byte> out;
    for (const unsigned v : values) out.push_back(static_cast<std::byte>(v));
    return out;
}

TEST_CASE("tiles: varints read back exactly what the writer writes, at every length") {
    std::vector<std::uint64_t> values = {0, 1, 127, 128, 255, 300, 16'383, 16'384, 0x7FFF'FFFFU, 0xFFFF'FFFFU};
    for (unsigned shift = 0; shift < 64; ++shift) {
        values.push_back(std::uint64_t{1} << shift);
        values.push_back((std::uint64_t{1} << shift) - 1);
    }
    values.push_back(std::numeric_limits<std::uint64_t>::max());
    atlas::test::Sink s;
    for (const std::uint64_t v : values) s.varu(v);
    Reader r(s.bytes);
    for (const std::uint64_t v : values) CHECK(r.varu() == v);
    CHECK(r.done());
}

TEST_CASE("tiles: zig-zag varints carry every signed value") {
    const std::vector<std::int64_t> values = {0,
                                              -1,
                                              1,
                                              -2,
                                              2,
                                              63,
                                              -64,
                                              64,
                                              std::numeric_limits<std::int64_t>::max(),
                                              std::numeric_limits<std::int64_t>::min(),
                                              -123'456'789'012LL};
    atlas::test::Sink s;
    for (const std::int64_t v : values) s.varz(v);
    Reader r(s.bytes);
    for (const std::int64_t v : values) CHECK(r.varz() == v);
    CHECK(r.done());
    CHECK(Reader::unzigzag(0) == 0);
    CHECK(Reader::unzigzag(1) == -1);
    CHECK(Reader::unzigzag(2) == 1);
}

TEST_CASE("tiles: a varint that is not the shortest, too long or cut short is refused") {
    SUBCASE("a trailing zero group") {
        const auto b = bytes({0x80, 0x00});
        Reader r(b);
        (void)r.varu();
        CHECK(r.fault() == Fault::overlong);
    }
    SUBCASE("a tenth byte of 2") {
        const auto b = bytes({0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x02});
        Reader r(b);
        (void)r.varu();
        CHECK(r.fault() == Fault::overflow);
    }
    SUBCASE("an eleventh byte") {
        const auto b = bytes({0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x81, 0x00});
        Reader r(b);
        (void)r.varu();
        CHECK(r.fault() == Fault::overflow);
    }
    SUBCASE("the largest value, in ten bytes") {
        const auto b = bytes({0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01});
        Reader r(b);
        CHECK(r.varu() == std::numeric_limits<std::uint64_t>::max());
        CHECK(r.done());
    }
    SUBCASE("cut short") {
        const auto b = bytes({0x80, 0x80});
        Reader r(b);
        CHECK(r.varu() == 0);
        CHECK(r.fault() == Fault::truncated);
    }
    SUBCASE("a zero is one byte, and fine") {
        const auto b = bytes({0x00});
        Reader r(b);
        CHECK(r.varu() == 0);
        CHECK(r.done());
    }
}

TEST_CASE("tiles: a count is at most Kotlin's Int, and a failure sticks") {
    atlas::test::Sink s;
    s.varu(0x7FFF'FFFFU);
    s.varu(0x8000'0000U);
    s.varu(5);
    Reader r(s.bytes);
    CHECK(r.count() == 0x7FFF'FFFFU);
    CHECK(r.ok());
    CHECK(r.count() == 0);
    CHECK(r.fault() == Fault::overflow);
    CHECK(r.count() == 0); // stuck: the rest reads as zero
    CHECK(r.remaining() == 0);
    CHECK(!r.done());
}

TEST_CASE("tiles: fixed-width integers, little-endian in the payload and big-endian in the directory") {
    const auto b = bytes({0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});
    Reader le(b);
    CHECK(le.u16le() == 0x0201);
    CHECK(le.u16le() == 0x0403);
    CHECK(le.u32le() == 0x0807'0605U);
    CHECK(le.done());
    Reader be(b);
    CHECK(be.u32be() == 0x0102'0304U);
    CHECK(be.u32be() == 0x0506'0708U);
    Reader be64(b);
    CHECK(be64.u64be() == 0x0102'0304'0506'0708ULL);
    Reader le64(b);
    CHECK(le64.u64le() == 0x0807'0605'0403'0201ULL);
    Reader short_read(std::span(b).first(3));
    CHECK(short_read.u32le() == 0);
    CHECK(short_read.fault() == Fault::truncated);
}

TEST_CASE("tiles: a double keeps its exact bits, a NaN's payload and a negative zero included") {
    for (const std::uint64_t bits : {0x0000'0000'0000'0000ULL, 0x8000'0000'0000'0000ULL, 0x7FF8'0000'0000'0001ULL,
                                     0x7FF0'0000'0000'0001ULL, 0x3FB9'9999'9999'999AULL, 0xFFF0'0000'0000'0000ULL}) {
        atlas::test::Sink s;
        s.u64(bits);
        Reader r(s.bytes);
        CHECK(std::bit_cast<std::uint64_t>(r.f64le()) == bits);
    }
}

TEST_CASE("tiles: blocks and strings stay inside their bytes") {
    atlas::test::Sink s;
    s.utf8("Prinsengracht");
    s.utf8("Brouwersgracht \xE2\x80\x94 Noord"); // an em dash
    s.utf8("\xF0\x9F\x9A\xB2");                  // U+1F6B2, four bytes
    s.varu(3);
    s.u8('a');
    Reader r(s.bytes);
    CHECK(r.utf8() == "Prinsengracht");
    CHECK(r.utf8() == "Brouwersgracht \xE2\x80\x94 Noord");
    CHECK(r.utf8() == "\xF0\x9F\x9A\xB2");
    Reader b = r.block(); // three bytes promised, one there
    CHECK(b.remaining() == 0);
    CHECK(r.fault() == Fault::truncated);
}

TEST_CASE("tiles: strings must be UTF-8: no overlong forms, no surrogates, nothing past U+10FFFF") {
    using atlas::tiles::valid_utf8;
    auto valid = [](std::initializer_list<unsigned> v) { return valid_utf8(bytes(v)); };
    CHECK(valid({}));
    CHECK(valid({'a', 'b'}));
    CHECK(valid({0xC2, 0x80}));
    CHECK(valid({0xDF, 0xBF}));
    CHECK(valid({0xE0, 0xA0, 0x80}));
    CHECK(valid({0xED, 0x9F, 0xBF}));        // U+D7FF
    CHECK(valid({0xEE, 0x80, 0x80}));        // U+E000
    CHECK(valid({0xF0, 0x90, 0x80, 0x80}));  // U+10000
    CHECK(valid({0xF4, 0x8F, 0xBF, 0xBF}));  // U+10FFFF
    CHECK(!valid({0x80}));                   // a continuation first
    CHECK(!valid({0xC0, 0x80}));             // overlong NUL
    CHECK(!valid({0xC1, 0xBF}));             // overlong
    CHECK(!valid({0xE0, 0x9F, 0xBF}));       // overlong three bytes
    CHECK(!valid({0xED, 0xA0, 0x80}));       // a surrogate
    CHECK(!valid({0xF0, 0x8F, 0xBF, 0xBF})); // overlong four bytes
    CHECK(!valid({0xF4, 0x90, 0x80, 0x80})); // past U+10FFFF
    CHECK(!valid({0xF5, 0x80, 0x80, 0x80}));
    CHECK(!valid({0xE2, 0x80})); // cut short
    CHECK(!valid({0xC3, 0x41})); // not a continuation
    const auto bad = bytes({0x02, 0xC0, 0x80});
    Reader r(bad);
    CHECK(r.utf8().empty());
    CHECK(r.fault() == Fault::bad_utf8);
}

TEST_CASE("tiles: wrapping arithmetic wraps as Kotlin's Long does") {
    using atlas::tiles::wrap_add;
    using atlas::tiles::wrap_sub;
    constexpr auto max = std::numeric_limits<std::int64_t>::max();
    constexpr auto min = std::numeric_limits<std::int64_t>::min();
    CHECK(wrap_add(max, 1) == min);
    CHECK(wrap_add(-1, min) == max);
    CHECK(wrap_sub(min, 1) == max);
    CHECK(wrap_add(5, -7) == -2);
}

} // namespace
