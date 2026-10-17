// Python's random stream in C++ (base/pymath/PyRandom.hpp): goldens from CPython 3.9.6 for every draw that involves no
// libm (random, getrandbits, randrange, randint, choice, uniform, shuffle), from int, big and float seeds; gauss's
// cached second value; Python's errors. gauss's bits against the port are MT/numerics/PyRandomParityTest.kt's.
#include "base/pymath/PyRandom.hpp"

#include "tests/base/death.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <span>
#include <string>

// NOLINTBEGIN(modernize-use-designated-initializers): golden rows, positional as they were printed

namespace {

namespace pm = atlas::pymath;

[[nodiscard]] std::uint64_t bits(double x) { return std::bit_cast<std::uint64_t>(x); }

struct Golden {
    std::array<std::uint64_t, 3> randoms;
    std::uint64_t bits5;
    std::uint64_t bits40;
    std::int64_t below1000;
    std::int64_t stepped;
    std::int64_t dice;
    std::int64_t choice;
    std::uint64_t uniform;
    std::array<std::int64_t, 10> shuffled;
};

/// Draws as py39.py did: [random() x3], getrandbits(5), getrandbits(40), randrange(1000), randrange(-50, 50, 3),
/// randint(-5, 5), choice(range(17)), uniform(-3.0, 7.0), then shuffle(list(range(10))).
void check_stream(pm::PyRandom& r, const Golden& g) {
    for (const std::uint64_t expected : g.randoms) {
        CHECK(bits(r.random()) == expected);
    }
    CHECK(r.getrandbits(5) == g.bits5);
    CHECK(r.getrandbits(40) == g.bits40);
    CHECK(r.randrange(1000) == g.below1000);
    CHECK(r.randrange(-50, 50, 3) == g.stepped);
    CHECK(r.randint(-5, 5) == g.dice);
    std::array<std::int64_t, 17> range17{};
    for (std::size_t i = 0; i < range17.size(); ++i) {
        range17[i] = static_cast<std::int64_t>(i);
    }
    CHECK(r.choice(std::span(range17)) == g.choice);
    CHECK(bits(r.uniform(-3.0, 7.0)) == g.uniform);
    std::array<std::int64_t, 10> deck{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    r.shuffle(std::span(deck));
    CHECK(deck == g.shuffled);
}

} // namespace

TEST_CASE("int seeds give CPython 3.9's streams") {
    struct Seeded {
        std::int64_t seed;
        Golden golden;
    };
    const std::array<Seeded, 5> cases{
        Seeded{0,
               {{0x3FEB0580F98A7DBEU, 0x3FE84129978F9C1AU, 0x3FDAEAA51052E978U},
                8,
                562492388602,
                497,
                25,
                -1,
                15,
                0x3FE293679F55D4F8U,
                {5, 4, 9, 6, 0, 1, 7, 2, 8, 3}}},
        Seeded{1,
               {{0x3FC132D8F91B7584U, 0x3FEB1E2D5B3584F8U, 0x3FE870D778409F13U},
                8,
                541672336265,
                779,
                34,
                2,
                12,
                0x40138C86F10CBDFDU,
                {4, 9, 2, 8, 5, 3, 6, 0, 7, 1}}},
        Seeded{42,
               {{0x3FE4762F307200C5U, 0x3F999C6B5EEB2060U, 0x3FD19A1491F589DCU},
                7,
                808053162473,
                104,
                -35,
                4,
                13,
                0xC005751739B3B81AU,
                {5, 7, 2, 9, 0, 6, 4, 8, 3, 1}}},
        Seeded{-42,
               {{0x3FE4762F307200C5U, 0x3F999C6B5EEB2060U, 0x3FD19A1491F589DCU},
                7,
                808053162473,
                104,
                -35,
                4,
                13,
                0xC005751739B3B81AU,
                {5, 7, 2, 9, 0, 6, 4, 8, 3, 1}}}, // Python seeds with abs(n)
        Seeded{123456789012345678,
               {{0x3FDBB8FB4599F0F0U, 0x3FE04FA4A3FBFE65U, 0x3FCA0C2C3E2AEF38U},
                30,
                885332969388,
                703,
                -26,
                5,
                11,
                0x40140FE17F063DDCU,
                {4, 1, 6, 2, 0, 9, 3, 5, 7, 8}}},
    };
    for (const Seeded& c : cases) {
        INFO("seed ", c.seed);
        pm::PyRandom r(c.seed);
        check_stream(r, c.golden);
    }
}

TEST_CASE("big seeds, as their 32-bit words, give CPython 3.9's streams") {
    pm::PyRandom r(0);
    constexpr std::array<std::uint32_t, 3> two64_plus5{5, 0, 1}; // 2**64 + 5
    r.seed_words(two64_plus5);
    check_stream(r, {{0x3FE056A876DF9621U, 0x3FEC26FB9E44EFAFU, 0x3FE07A5C2C66F72AU},
                     24,
                     791764919003,
                     68,
                     10,
                     4,
                     1,
                     0x3FEB877B64234408U,
                     {2, 7, 1, 4, 5, 8, 3, 0, 9, 6}});
    constexpr std::array<std::uint32_t, 5> two100{12345, 0, 0, 16, 0}; // 2**100 + 12345, with a leading zero word
    r.seed_words(two100);
    check_stream(r, {{0x3FDC78CD1084EDE2U, 0x3FD355EC9CC53848U, 0x3FE637E63F0D4FA5U},
                     0,
                     171566221318,
                     469,
                     -50,
                     -1,
                     0,
                     0x40071B0479003FA0U,
                     {5, 4, 1, 9, 7, 3, 2, 0, 6, 8}});
}

TEST_CASE("a float seed is its hash, as CPython 3.9 seeds anything that is not an int") {
    pm::PyRandom r(0);
    REQUIRE(r.seed_float(1.5).has_value());
    CHECK(bits(r.random()) == 0x3FE1A80C674ABA1AU);
    CHECK(bits(r.random()) == 0x3FD063D44129E0E2U);
    CHECK(!r.seed_float(std::nan("")).has_value());
    // An integral float hashes like its int, so it seeds like it.
    pm::PyRandom a(7);
    pm::PyRandom b(0);
    REQUIRE(b.seed_float(7.0).has_value());
    CHECK(a.random() == b.random());
}

TEST_CASE("gauss caches its second value, and reseeding forgets it") {
    pm::PyRandom a(7);
    pm::PyRandom b(7);
    const double g1 = a.gauss();
    const double g2 = a.gauss();
    const double u1 = b.random();
    const double u2 = b.random();
    const double x2pi = u1 * pm::tau;
    const double g2rad = std::sqrt(-2.0 * pm::strict::log(1.0 - u2));
    CHECK(g1 == pm::strict::cos(x2pi) * g2rad);
    CHECK(g2 == pm::strict::sin(x2pi) * g2rad);
    CHECK(a.random() == b.random()); // the second gauss drew nothing
    CHECK(a.gauss(10.0, 2.0) == 10.0 + b.gauss() * 2.0);
    a.seed(7);
    b.seed(7);
    (void)b.gauss(); // a cached value is dropped by seed()
    b.seed(7);
    CHECK(a.gauss() == b.gauss());
}

TEST_CASE("getrandbits takes whole words, and randbelow redraws above the bound") {
    pm::PyRandom a(3);
    pm::PyRandom b(3);
    const std::uint64_t lo = b.genrand_uint32();
    const std::uint64_t hi = b.genrand_uint32();
    CHECK(a.getrandbits(64) == (lo | (hi << 32U)));
    CHECK(a.getrandbits(0) == 0);
    CHECK(a.randbelow(0) == 0);
    for (int i = 0; i < 1000; ++i) {
        const std::int64_t r = a.randbelow(5);
        CHECK((r >= 0 && r < 5));
    }
}

TEST_CASE("empty ranges and sequences are Python's errors") {
    pm::PyRandom r(1);
    CHECK(r.checked_randrange(0).error() == pm::Error::empty_range);
    CHECK(r.checked_randrange(5, 5).error() == pm::Error::empty_range);
    CHECK(r.checked_randrange(0, 10, 0).error() == pm::Error::empty_range);
    CHECK(r.checked_randrange(10, 0, 2).error() == pm::Error::empty_range);
    CHECK(r.checked_randrange(10, 0, -3).value() >= 1);
    CHECK(r.checked_choice_index(0).error() == pm::Error::empty_sequence);
    const auto death = atlas::test::run_in_child([] {
        pm::PyRandom q(1);
        std::span<int> none;
        (void)q.choice(none);
    });
    CHECK(death.aborted);
    CHECK(death.stderr_text.find("pymath: Cannot choose from an empty sequence") != std::string::npos);
    CHECK(death.stderr_text.find("PyRandomTest.cpp:") != std::string::npos);
}

// NOLINTEND(modernize-use-designated-initializers)
