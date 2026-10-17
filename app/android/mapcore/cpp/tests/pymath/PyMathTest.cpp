// Python's arithmetic in C++ (base/pymath/PyMath.hpp): goldens from CPython 3.9.6 (the renders' Python) for what
// involves no libm (%, //, fmod, int(), round(), hypot, dist, sum), Python's errors where the port throws, and the end
// of the process at the caller's line for the functions that do not return their errors.
#include "base/pymath/PyMath.hpp"

#include "tests/base/death.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>

// NOLINTBEGIN(modernize-use-designated-initializers): golden rows, positional as they were printed

namespace {

namespace pm = atlas::pymath;
using pm::Error;

constexpr double inf = std::numeric_limits<double>::infinity();
constexpr double qnan = std::numeric_limits<double>::quiet_NaN();

[[nodiscard]] std::uint64_t bits(double x) { return std::bit_cast<std::uint64_t>(x); }
[[nodiscard]] double from(std::uint64_t b) { return std::bit_cast<double>(b); }

template <class T>
[[nodiscard]] bool fails_with(const pm::Result<T>& r, Error e) {
    return !r && r.error() == e;
}

} // namespace

TEST_CASE("float %, // and math.fmod equal CPython 3.9's") {
    struct Case {
        std::uint64_t x, y, mod, floor_div, fmod;
    };
    constexpr std::array<Case, 11> cases{
        Case{0x401E000000000000U, 0x4000000000000000U, 0x3FF8000000000000U, 0x4008000000000000U, 0x3FF8000000000000U},
        Case{0xC01E000000000000U, 0x4000000000000000U, 0x3FE0000000000000U, 0xC010000000000000U, 0xBFF8000000000000U},
        Case{0x401E000000000000U, 0xC000000000000000U, 0xBFE0000000000000U, 0xC010000000000000U, 0x3FF8000000000000U},
        Case{0xC01E000000000000U, 0xC000000000000000U, 0xBFF8000000000000U, 0x4008000000000000U, 0xBFF8000000000000U},
        Case{0x0000000000000000U, 0xC008000000000000U, 0x8000000000000000U, 0x8000000000000000U, 0x0000000000000000U},
        Case{0x8000000000000000U, 0x4008000000000000U, 0x0000000000000000U, 0x8000000000000000U, 0x8000000000000000U},
        Case{0x7E37E43C8800759CU, 0x01A56E1FC2F8F359U, 0x0194F722A6F79F9CU, 0x7FF0000000000000U, 0x0194F722A6F79F9CU},
        Case{0x4014000000000000U, 0x7FF0000000000000U, 0x4014000000000000U, 0x0000000000000000U, 0x4014000000000000U},
        Case{0xC014000000000000U, 0x7FF0000000000000U, 0x7FF0000000000000U, 0xBFF0000000000000U, 0xC014000000000000U},
        Case{0x3FF0000000000000U, 0x3FB999999999999AU, 0x3FB9999999999996U, 0x4022000000000000U, 0x3FB9999999999996U},
        Case{0x800012688B70E62BU, 0x401C000000000000U, 0x401C000000000000U, 0xBFF0000000000000U, 0x800012688B70E62BU},
    };
    for (const Case& c : cases) {
        const double x = from(c.x);
        const double y = from(c.y);
        INFO(x, " and ", y);
        CHECK(bits(pm::mod(x, y)) == c.mod);
        CHECK(bits(pm::floor_div(x, y)) == c.floor_div);
        CHECK(bits(pm::fmod(x, y)) == c.fmod);
        const auto [q, r] = pm::divmod(x, y);
        CHECK(bits(q) == c.floor_div);
        CHECK(bits(r) == c.mod);
    }
    CHECK(fails_with(pm::checked::mod(1.0, 0.0), Error::float_modulo));
    CHECK(fails_with(pm::checked::mod(1.0, -0.0), Error::float_modulo));
    CHECK(fails_with(pm::checked::floor_div(1.0, 0.0), Error::float_floor_division));
    CHECK(fails_with(pm::checked::fmod(1.0, 0.0), Error::domain));
    CHECK(fails_with(pm::checked::fmod(inf, 1.0), Error::domain));
    CHECK(pm::checked::fmod(-3.0, -inf).value() == -3.0);
    CHECK(std::isnan(pm::mod(qnan, 2.0)));
}

TEST_CASE("int % and // equal CPython's, and wrap where the port's Math.floorDiv does") {
    struct Case {
        std::int64_t a, b, mod, floor_div;
    };
    constexpr std::array<Case, 6> cases{
        Case{7, 2, 1, 3},
        Case{-7, 2, 1, -4},
        Case{7, -2, -1, -4},
        Case{-7, -2, -1, 3},
        Case{std::numeric_limits<std::int64_t>::min(), 7, 6, -1317624576693539402},
        Case{std::numeric_limits<std::int64_t>::max(), -10, -3, -922337203685477581},
    };
    for (const Case& c : cases) {
        INFO(c.a, " and ", c.b);
        CHECK(pm::mod(c.a, c.b) == c.mod);
        CHECK(pm::floor_div(c.a, c.b) == c.floor_div);
    }
    CHECK(fails_with(pm::checked::mod(std::int64_t{5}, std::int64_t{0}), Error::integer_division));
    CHECK(fails_with(pm::checked::floor_div(std::int64_t{5}, std::int64_t{0}), Error::integer_division));
    constexpr std::int64_t min = std::numeric_limits<std::int64_t>::min();
    CHECK(pm::floor_div(min, std::int64_t{-1}) == min); // Python's int would grow; the port's long wraps
    CHECK(pm::mod(min, std::int64_t{-1}) == 0);
}

TEST_CASE("int(), math.floor and math.ceil as ints equal CPython's, and refuse what does not fit") {
    struct Case {
        std::uint64_t x;
        std::int64_t to_int, floor, ceil;
    };
    constexpr std::array<Case, 8> cases{
        Case{0x4007333333333333U, 2, 2, 3},
        Case{0xC007333333333333U, -2, -3, -2},
        Case{0x3FEFF7CED916872BU, 0, 0, 1},
        Case{0xBFEFF7CED916872BU, 0, -1, 0},
        Case{0x43DFEB3DD0676600U, 9200000000000000000, 9200000000000000000, 9200000000000000000},
        Case{0xC3DFEB3DD0676600U, -9200000000000000000, -9200000000000000000, -9200000000000000000},
        Case{0x43DFFFFFFFFFFFFFU, 9223372036854774784, 9223372036854774784, 9223372036854774784},
        Case{0xC3E0000000000000U, std::numeric_limits<std::int64_t>::min(), std::numeric_limits<std::int64_t>::min(),
             std::numeric_limits<std::int64_t>::min()},
    };
    for (const Case& c : cases) {
        const double x = from(c.x);
        INFO(x);
        CHECK(pm::to_int(x) == c.to_int);
        CHECK(pm::floor_int(x) == c.floor);
        CHECK(pm::ceil_int(x) == c.ceil);
    }
    CHECK(fails_with(pm::checked::to_int(inf), Error::not_finite));
    CHECK(fails_with(pm::checked::to_int(qnan), Error::not_finite));
    CHECK(fails_with(pm::checked::to_int(9223372036854775808.0), Error::too_large));
    CHECK(fails_with(pm::checked::ceil_int(-inf), Error::not_finite));
}

TEST_CASE("round(x) ties to even, as CPython's") {
    struct Case {
        std::uint64_t x;
        std::int64_t r;
    };
    constexpr std::array<Case, 10> cases{
        Case{0x3FE0000000000000U, 0},
        Case{0x3FF8000000000000U, 2},
        Case{0x4004000000000000U, 2},
        Case{0xBFE0000000000000U, 0},
        Case{0xBFF8000000000000U, -2},
        Case{0xC004000000000000U, -2},
        Case{0x400BFFFFFFFFFFFFU, 3},
        Case{0x4341C37937E08000U, 10000000000000000},
        Case{0x4340000000000000U, 9007199254740992},
        Case{0xC01E000000000000U, -8},
    };
    for (const Case& c : cases) {
        INFO(from(c.x));
        CHECK(pm::round(from(c.x)) == c.r);
    }
    CHECK(fails_with(pm::checked::round(qnan), Error::not_finite));
    CHECK(fails_with(pm::checked::round(1e19), Error::too_large));
}

TEST_CASE("Java's Math.round: halves up on the exact value, 0 for NaN, Long's limits beyond them") {
    constexpr std::int64_t max = std::numeric_limits<std::int64_t>::max();
    constexpr std::int64_t min = std::numeric_limits<std::int64_t>::min();
    struct Case {
        double x;
        std::int64_t r;
    };
    const std::array<Case, 24> cases{
        Case{0.5, 1},
        Case{-0.5, 0},
        Case{1.5, 2},
        Case{-1.5, -1},
        Case{2.5, 3},
        Case{-2.5, -2},
        Case{0.49999999999999994, 0}, // floor(x + 0.5) in double would give 1: the sum rounds up first
        Case{-0.49999999999999994, 0},
        Case{4503599627370495.5, 4503599627370496}, // 2^52 - 0.5
        Case{-4503599627370495.5, -4503599627370495},
        Case{4503599627370497.0, 4503599627370497}, // 2^52 + 1: x + 0.5 is not a double there
        Case{9007199254740993.0, 9007199254740992}, // the literal itself is 2^53
        Case{9223372036854774784.0, 9223372036854774784},
        Case{9223372036854775808.0, max},
        Case{-9223372036854775808.0, min},
        Case{1e300, max},
        Case{-1e300, min},
        Case{std::numeric_limits<double>::infinity(), max},
        Case{-std::numeric_limits<double>::infinity(), min},
        Case{qnan, 0},
        Case{5e-324, 0},
        Case{-5e-324, 0},
        Case{-0.0, 0},
        Case{1234567.4999999998, 1234567},
    };
    for (const Case& c : cases) {
        INFO(c.x);
        CHECK(pm::java::round(c.x) == c.r);
    }
    // Where Java and Python part: ties.
    CHECK(pm::java::round(2.5) == 3);
    CHECK(pm::round(2.5) == 2);
}

TEST_CASE("round(x, n) is correctly rounded at n decimals, ties to even, as CPython's") {
    struct Case {
        std::uint64_t x;
        int n;
        std::uint64_t r;
    };
    constexpr std::array<Case, 21> cases{
        Case{0x4005666666666666U, 2, 0x40055C28F5C28F5CU}, // round(2.675, 2) == 2.67: 2.675 is below the tie
        Case{0x3FC0000000000000U, 2, 0x3FBEB851EB851EB8U}, // round(0.125, 2) == 0.12: a true tie, to even
        Case{0x3FD8000000000000U, 2, 0x3FD851EB851EB852U}, // round(0.375, 2) == 0.38
        Case{0x3FF0147AE147AE14U, 2, 0x3FF0000000000000U}, // round(1.005, 2) == 1.0
        Case{0xC005666666666666U, 2, 0xC0055C28F5C28F5CU},
        Case{0x405EDD2F1A9FBE77U, -1, 0x405E000000000000U}, // round(123.456, -1) == 120.0
        Case{0x4063600000000000U, -1, 0x4064000000000000U}, // round(155.0, -1) == 160.0
        Case{0x4064A00000000000U, -1, 0x4064000000000000U}, // round(165.0, -1) == 160.0
        Case{0x40C81CD6C8B43958U, -2, 0x40C8060000000000U},
        Case{0x7E37E43C8800759CU, -298, 0x7E37E43C8800759CU},
        Case{0x0000000000000001U, 330, 0x0000000000000001U}, // the smallest subnormal at 330 places
        Case{0x3FE0000000000000U, 0, 0x0000000000000000U},
        Case{0x3FF8000000000000U, 0, 0x4000000000000000U},
        Case{0x4004000000000000U, 0, 0x4000000000000000U},
        Case{0xBFA47AE147AE147BU, 1, 0x8000000000000000U}, // round(-0.04, 1) == -0.0
        Case{0x40934A456D5CFAADU, 3, 0x40934A45A1CAC083U},
        Case{0x3BC79CA10C924223U, 25, 0x3BC79CA10C924223U},
        Case{0x40F81CD6E9E1B08AU, 17, 0x40F81CD6E9E1B08AU},
        Case{0x4023FD70A3D70A3DU, 2, 0x4023FAE147AE147BU}, // round(9.995, 2) == 9.99
        Case{0x0000000000000001U, 324, 0x0000000000000001U},
        Case{0x4011666666666666U, 1, 0x4011333333333333U}, // round(4.35, 1) == 4.3
    };
    for (const Case& c : cases) {
        INFO("round(", from(c.x), ", ", c.n, ")");
        CHECK(bits(pm::round(from(c.x), c.n)) == c.r);
    }
    CHECK(bits(pm::round(-0.0, 2)) == bits(-0.0));
    CHECK(pm::round(inf, 3) == inf);
    CHECK(std::isnan(pm::round(qnan, 3)));
    CHECK(bits(pm::round(5e-324, 1000)) == bits(5e-324));
    CHECK(bits(pm::round(-1e300, -400)) == bits(-0.0));
    // Where the port and CPython part: round(1.7e308, -308) raises OverflowError in CPython; the port's
    // parseDouble("2E+308") is infinity, and pymath follows the port.
    CHECK(pm::round(1.7e308, -308) == inf);
}

TEST_CASE("round(n, k) of an int equals CPython's, and wraps where the port's toLong does") {
    struct Case {
        std::int64_t n;
        int k;
        std::int64_t r;
    };
    constexpr std::array<Case, 11> cases{
        Case{15, -1, 20},        Case{25, -1, 20},     Case{35, -1, 40},
        Case{-25, -1, -20},      Case{149, -2, 100},   Case{150, -2, 200},
        Case{250, -2, 200},      Case{-150, -2, -200}, Case{5000000000000000000, -19, 0},
        Case{123456789, -20, 0}, Case{987, 3, 987},
    };
    for (const Case& c : cases) {
        INFO("round(", c.n, ", ", c.k, ")");
        CHECK(pm::round(c.n, c.k) == c.r);
    }
    // CPython gives 10**19 for round(5000000000000000001, -19), past 64 bits; the port's toLong keeps its low 64.
    CHECK(pm::round(std::int64_t{5000000000000000001}, -19) == -8446744073709551616);
    CHECK(pm::round(std::int64_t{-5000000000000000001}, -19) == 8446744073709551616);
}

TEST_CASE("math.hypot, math.dist and sum equal CPython 3.9's") {
    const auto check_hypot = [](std::initializer_list<std::uint64_t> in, std::uint64_t expected) {
        std::array<double, 4> v{};
        std::size_t n = 0;
        for (const std::uint64_t b : in) {
            v[n++] = from(b);
        }
        CHECK(bits(pm::hypot(std::span<const double>(v.data(), n))) == expected);
        if (n == 2) {
            CHECK(bits(pm::hypot(v[0], v[1])) == expected);
        }
    };
    check_hypot({0x4008000000000000U, 0x4010000000000000U}, 0x4014000000000000U);
    check_hypot({0x7E37E43C8800759CU, 0x7E37E43C8800759CU}, 0x7E40E4D50F99B211U);
    check_hypot({0x0000000000000001U, 0x0000000000000001U}, 0x0000000000000001U);
    check_hypot({0x3FF0000000000000U, 0x3C670EF54646D497U}, 0x3FF0000000000000U);
    check_hypot({0x3FB999999999999AU, 0x3FC999999999999AU, 0x3FD3333333333333U}, 0x3FD7F254DAB9CC3AU);
    check_hypot({0x000012688B70E62BU, 0x00003739A252B281U}, 0x00003A365FF2EA11U);
    check_hypot({0x4004000000000000U, 0x401D000000000000U, 0x3FF0000000000000U, 0x4023000000000000U},
                0x4028800000000000U);

    constexpr std::array<double, 2> p2{0.0, 0.0};
    constexpr std::array<double, 2> q2{3.0, 4.0};
    CHECK(pm::dist(p2, q2) == 5.0);
    constexpr std::array<double, 3> p3{1.5, -2.5, 3.0};
    constexpr std::array<double, 3> q3{-0.5, 0.5, 8.0};
    CHECK(bits(pm::dist(p3, q3)) == 0x4018A85C24F7065AU);
    const std::array<double, 1> p1{from(0x00000000000007E8U)};
    const std::array<double, 1> q1{from(0x00000000000017B8U)};
    CHECK(bits(pm::dist(p1, q1)) == 0x0000000000000FD0U);
    CHECK(!pm::checked::dist(p2, q3).has_value());

    const auto check_sum = [](std::initializer_list<double> values, std::uint64_t expected) {
        CHECK(bits(pm::sum(std::span<const double>(values.begin(), values.size()))) == expected);
        pm::Summer s;
        for (const double v : values) {
            s.add(v);
        }
        CHECK(bits(s.value()) == expected);
    };
    check_sum({0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1}, 0x3FEFFFFFFFFFFFFFU);
    check_sum({1e100, 1.0, -1e100}, 0x0000000000000000U);
    check_sum({-0.0}, 0x0000000000000000U); // CPython starts from the int 0, so -0.0 sums to 0.0
    check_sum({-0.0, -0.0}, 0x0000000000000000U);
    check_sum({}, 0x0000000000000000U);
    check_sum({0.1, 0.2, 0.3, 0.4}, 0x3FF0000000000000U);
    check_sum({1e16, 1.0, 1.0}, 0x4341C37937E08000U);
}

TEST_CASE("CPython 3.12's hypot and sum differ from 3.9's where they should") {
    constexpr pm::Compat py312{12};
    CHECK(pm::hypot(3.0, 4.0, py312) == 5.0);
    CHECK(pm::hypot(-5e-324, 0.0, py312) == 5e-324);
    // 3.12's compensated sum recovers what 3.9's plain sum loses.
    constexpr std::array<double, 3> v{1e16, 1.0, 1.0};
    CHECK(pm::sum(v) == 1e16);
    CHECK(pm::sum(v, py312) == 1e16 + 2.0);
    constexpr std::array<double, 10> tenths{0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1};
    CHECK(pm::sum(tenths, py312) == 1.0);
    // The 3.12 path's exact product errors are Dekker's, equal to a fused multiply-add's for ordinary values.
    std::uint64_t s = 99;
    for (int i = 0; i < 10000; ++i) {
        s = s * 6364136223846793005U + 1442695040888963407U;
        const double a = from(0x3FE0000000000000U | (s >> 12U));
        const double b = from(0x3FF0000000000000U | ((s * 0x9E3779B97F4A7C15U) >> 12U));
        CHECK(pm::product_error(a, b, a * b) == std::fma(a, b, -(a * b)));
    }
}

TEST_CASE("hypot keeps the port's quirk at NaN, and CPython's elsewhere") {
    // The port's two-argument hypot takes `b > a ? b : a`, so a NaN first stays the maximum; its loop form starts
    // from 0.0 as CPython does, where an infinity wins.
    CHECK(std::isnan(pm::hypot(qnan, inf)));
    CHECK(pm::hypot(inf, qnan) == inf);
    constexpr std::array<double, 2> v{qnan, inf};
    CHECK(pm::hypot(v) == inf);
    CHECK(std::isnan(pm::hypot(qnan, 1.0)));
    CHECK(pm::hypot(-inf, 1.0) == inf);
}

TEST_CASE("the math module's checks are Python's") {
    CHECK(fails_with(pm::checked::sqrt(-1.0), Error::domain));
    CHECK(pm::checked::sqrt(-0.0).value() == 0.0);
    CHECK(fails_with(pm::checked::asin(1.5), Error::domain));
    CHECK(fails_with(pm::checked::acos(-1.5), Error::domain));
    CHECK(std::isnan(pm::checked::asin(qnan).value()));
    CHECK(fails_with(pm::checked::exp(710.0), Error::range));
    CHECK(pm::checked::exp(inf).value() == inf);
    CHECK(pm::checked::exp(-1000.0).value() == 0.0);
    CHECK(fails_with(pm::checked::log(0.0), Error::domain));
    CHECK(fails_with(pm::checked::log(-1.0), Error::domain));
    CHECK(fails_with(pm::checked::log10(0.0), Error::domain));
    CHECK(fails_with(pm::checked::log2(-0.0), Error::domain));
    CHECK(fails_with(pm::checked::log(8.0, 0.0), Error::domain));
    CHECK(pm::checked::log(8.0, 2.0).value() == pm::strict::log(8.0) / pm::strict::log(2.0));
    CHECK(pm::checked::log(8.0, 1.0).value() == inf); // the port divides by log(1) = 0
    for (int k = -1023; k <= 1023; ++k) {
        CHECK(pm::log2(std::ldexp(1.0, k)) == static_cast<double>(k));
    }
    // Below 2^-1023 the port's exact test (x == scalb(1.0, getExponent(x))) no longer fires: the quotient of logs.
    for (int k = -1074; k < -1023; ++k) {
        const double x = std::ldexp(1.0, k);
        CHECK(pm::log2(x) == pm::strict::log(x) / pm::strict::log(2.0));
    }
    CHECK(pm::log2(inf) == 1024.0); // the port's getExponent test takes infinity for 2^1024
    CHECK(fails_with(pm::checked::pow(0.0, -1.0), Error::zero_to_negative_power));
    CHECK(fails_with(pm::checked::pow(-8.0, 1.0 / 3.0), Error::negative_to_fractional_power));
    CHECK(fails_with(pm::checked::pow(10.0, 400.0), Error::power_out_of_range));
    CHECK(pm::checked::pow(-8.0, 3.0).value() == -512.0);
    CHECK(pm::checked::pow(-8.0, -inf).value() == 0.0);
    CHECK(pm::pow(2.0, 10) == 1024.0);
    CHECK(pm::message(Error::domain) == "math domain error");
}

namespace {
// Runtime operands against a constant zero: the shape clang folds into arm64's fminnm and fmaxnm
// (base/pymath/PyMath.hpp).
[[gnu::noinline]] double min_with_zero(double x) { return pm::min(0.0, x); }
[[gnu::noinline]] double max_with_negative_zero(double x) { return pm::max(-0.0, x); }
[[gnu::noinline]] double min_of_list(double x) { return pm::min({0.0, x, 3.0}); }
[[gnu::noinline]] double max_with_zero(double x) { return pm::max(x, 0.0); }
[[gnu::noinline]] double max_with_zero_first(double x) { return pm::max(0.0, x); }
[[gnu::noinline]] double min_with_negative_zero(double x) { return pm::min(-0.0, x); }
[[gnu::noinline]] double min_with_zero_second(double x) { return pm::min(x, 0.0); }
[[gnu::noinline]] double java_max_with_zero(double x) { return pm::java::max(x, 0.0); }
[[gnu::noinline]] double java_min_with_zero(double x) { return pm::java::min(0.0, x); }
[[gnu::noinline]] double java_max_with_negative_zero(double x) { return pm::java::max(-0.0, x); }
[[gnu::noinline]] double java_min_with_negative_zero(double x) { return pm::java::min(x, -0.0); }
} // namespace

TEST_CASE("Python's max and min keep the first of equal values and NaN's order") {
    CHECK(bits(pm::max(0.0, -0.0)) == bits(0.0));
    CHECK(bits(pm::max(-0.0, 0.0)) == bits(-0.0));
    CHECK(bits(pm::min(-0.0, 0.0)) == bits(-0.0));
    CHECK(std::isnan(pm::max(qnan, 1.0)));
    CHECK(pm::max(1.0, qnan) == 1.0);
    CHECK(pm::max({1.0, 3.0, 2.0, 3.0}) == 3.0);
    CHECK(pm::min({1.0, -3.0, 2.0}) == -3.0);
    // The same at run time, where clang would otherwise emit fminnm: min(0.0, -0.0) is 0.0 in Python, not -0.0.
    volatile double negative_zero = -0.0;
    volatile double zero = 0.0;
    volatile double nan_value = qnan;
    CHECK(bits(min_with_zero(negative_zero)) == bits(0.0));
    CHECK(bits(max_with_negative_zero(zero)) == bits(-0.0));
    CHECK(bits(min_of_list(negative_zero)) == bits(0.0));
    CHECK(bits(max_with_zero(negative_zero)) == bits(-0.0));
    CHECK(min_with_zero(nan_value) == 0.0);
    CHECK(std::isnan(max_with_zero(nan_value)));
    // Every place of a constant zero of either sign: Python keeps the first of equal values.
    CHECK(bits(max_with_zero_first(negative_zero)) == bits(0.0));   // x > 0.0 ? x : 0.0
    CHECK(bits(min_with_negative_zero(zero)) == bits(-0.0));        // x < -0.0 ? x : -0.0
    CHECK(bits(min_with_zero_second(negative_zero)) == bits(-0.0)); // 0.0 < x ? 0.0 : x
    CHECK(bits(max_with_negative_zero(negative_zero)) == bits(-0.0));
    static_assert(std::bit_cast<std::uint64_t>(pm::min(0.0, -0.0)) == 0);
    static_assert(std::bit_cast<std::uint64_t>(pm::max(-0.0, 0.0)) == 0x8000000000000000U);
}

TEST_CASE("Java's Math.max and Math.min: NaN wins, and -0.0 is below +0.0") {
    volatile double negative_zero = -0.0;
    volatile double zero = 0.0;
    volatile double nan_value = qnan;
    CHECK(bits(java_max_with_zero(negative_zero)) == bits(0.0));
    CHECK(bits(java_min_with_zero(negative_zero)) == bits(-0.0));
    CHECK(bits(java_max_with_negative_zero(zero)) == bits(0.0));
    CHECK(bits(java_min_with_negative_zero(zero)) == bits(-0.0));
    CHECK(bits(java_max_with_negative_zero(negative_zero)) == bits(-0.0));
    CHECK(std::isnan(java_max_with_zero(nan_value)));
    CHECK(std::isnan(java_min_with_zero(nan_value)));
    CHECK(std::isnan(pm::java::max(1.0, qnan)));
    CHECK(std::isnan(pm::java::min(qnan, 1.0)));
    CHECK(pm::java::max(2.0, 3.0) == 3.0);
    CHECK(pm::java::min(2.0, 3.0) == 2.0);
    CHECK(pm::java::max(-inf, inf) == inf);
    static_assert(std::bit_cast<std::uint64_t>(pm::java::max(-0.0, 0.0)) == 0);
    static_assert(std::bit_cast<std::uint64_t>(pm::java::min(0.0, -0.0)) == 0x8000000000000000U);
    // Python and Java part at the zeros and at NaN.
    CHECK(bits(pm::max(-0.0, 0.0)) == bits(-0.0));
    CHECK(bits(pm::java::max(-0.0, 0.0)) == bits(0.0));
    CHECK(pm::max(1.0, qnan) == 1.0);
}

TEST_CASE("frexp, ldexp, radians, degrees, copysign and isclose are Python's") {
    const pm::Frexp f = pm::frexp(48.0);
    CHECK(f.mantissa == 0.75);
    CHECK(f.exponent == 6);
    CHECK(pm::frexp_exp(5e-324) == -1073);
    CHECK(pm::frexp(5e-324).mantissa == 0.5);
    CHECK(pm::frexp(inf).exponent == 0);
    CHECK(pm::ldexp(0.75, 6) == 48.0);
    CHECK(pm::ldexp(1.0, -1074) == 5e-324);
    CHECK(pm::ldexp(1.5, -1075) == 5e-324); // one rounding: 0.75 ulp rounds up
    CHECK(pm::ldexp(1.0, -1075) == 0.0);    // a tie rounds to even
    CHECK(pm::ldexp(1.0, 1024) == inf);
    CHECK(pm::ldexp(5e-324, 2147483647) == inf);
    CHECK(pm::ldexp(1e300, -2147483647 - 1) == 0.0);
    CHECK(pm::radians(180.0) == pm::pi);
    CHECK(pm::degrees(pm::pi) == 180.0);
    CHECK(std::signbit(pm::copysign(1.0, -qnan)));
    CHECK(pm::isclose(1.0, 1.0 + 1e-10));
    CHECK(!pm::isclose(1.0, 1.0 + 1e-8));
    CHECK(!pm::isclose(inf, -inf));
    CHECK(pm::isclose(inf, inf));
    CHECK(pm::isclose(0.0, 1e-12, 1e-9, 1e-11));
}

TEST_CASE("a Python error ends the process with Python's message at the caller's line") {
    const auto death = atlas::test::run_in_child([] {
        volatile double x = -1.0;
        (void)pm::log(x);
    });
    CHECK(death.aborted);
    CHECK(death.stderr_text.find("pymath: math domain error") != std::string::npos);
    CHECK(death.stderr_text.find("PyMathTest.cpp:") != std::string::npos);

    const auto zero = atlas::test::run_in_child([] {
        volatile double y = 0.0;
        (void)pm::mod(1.0, y);
    });
    CHECK(zero.aborted);
    CHECK(zero.stderr_text.find("pymath: float modulo") != std::string::npos);

    const auto fine = atlas::test::run_in_child([] { (void)pm::log(2.0); });
    CHECK_FALSE(fine.aborted);
}

// NOLINTEND(modernize-use-designated-initializers)
