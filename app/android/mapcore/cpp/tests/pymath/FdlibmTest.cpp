// StrictMath in C++ (base/pymath/fdlibm/): golden results from Java's StrictMath (Temurin 17), the special values
// Java documents, and the symmetries fdlibm keeps to the bit. The full parity, 10^7 arguments a function against the
// JVM, is MT/numerics/PyMathParityTest.kt; check/NumericsCheck.cpp repeats a sample of it on every host.
#include "base/pymath/PyMath.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>

// NOLINTBEGIN(modernize-use-designated-initializers): golden rows, positional as they were printed

namespace {

namespace strict = atlas::pymath::strict;

constexpr double inf = std::numeric_limits<double>::infinity();
constexpr double qnan = std::numeric_limits<double>::quiet_NaN();

[[nodiscard]] std::uint64_t bits(double x) { return std::bit_cast<std::uint64_t>(x); }
[[nodiscard]] double from(std::uint64_t b) { return std::bit_cast<double>(b); }

/// Equal to the bit, or both NaN.
[[nodiscard]] bool same(double a, double b) { return (std::isnan(a) && std::isnan(b)) || bits(a) == bits(b); }

// The arguments of the JVM's goldens below.
constexpr std::array<double, 16> args{0.5,
                                      1.0,
                                      2.0,
                                      3.0,
                                      10.0,
                                      100.0,
                                      1e22,
                                      -1e22,
                                      1e300,
                                      0.7853981633974483,
                                      1.5707963267948966,
                                      3.141592653589793,
                                      1e-10,
                                      6.283185307179586,
                                      2.356194490192345,
                                      823549.6619};

template <class F>
void check_goldens(F f, const std::array<std::uint64_t, 16>& expected) {
    for (std::size_t i = 0; i < args.size(); ++i) {
        INFO("argument ", args[i]);
        CHECK(bits(f(args[i])) == expected[i]);
    }
}

} // namespace

TEST_CASE("sin, cos and tan equal Java's StrictMath, huge arguments included") {
    check_goldens(strict::sin, {0x3FDEAEE8744B05F0U, 0x3FEAED548F090CEEU, 0x3FED18F6EAD1B446U, 0x3FC210386DB6D55BU,
                                0xBFE1689EF5F34F52U, 0xBFE03425B78C4DB8U, 0xBFEB453AB76BF397U, 0x3FEB453AB76BF397U,
                                0xBFEA2C16B010E385U, 0x3FE6A09E667F3BCCU, 0x3FF0000000000000U, 0x3CA1A62633145C07U,
                                0x3DDB7CDFD9D7BDBBU, 0xBCB1A62633145C07U, 0x3FE6A09E667F3BCDU, 0xBF65F9E72A2EF95EU});
    check_goldens(strict::cos, {0x3FEC1528065B7D50U, 0x3FE14A280FB5068CU, 0xBFDAA22657537205U, 0xBFEFAE04BE85E5D2U,
                                0xBFEAD9AC890C6B1FU, 0x3FEB981DBF665FDFU, 0x3FE0BE2CEF01C8F4U, 0x3FE0BE2CEF01C8F4U,
                                0xBFE2699022ADC4C1U, 0x3FE6A09E667F3BCDU, 0x3C91A62633145C07U, 0xBFF0000000000000U,
                                0x3FF0000000000000U, 0x3FF0000000000000U, 0xBFE6A09E667F3BCCU, 0x3FEFFFF8742F9A87U});
    check_goldens(strict::tan, {0x3FE17B4F5BF3474AU, 0x3FF8EB245CBEE3A6U, 0xC0017AF62E0950F8U, 0xBFC23EF71254B86FU,
                                0x3FE4BF5F34BE3782U, 0xBFE2CA74D62B5D38U, 0xBFFA0F79C1B6B258U, 0x3FFA0F79C1B6B258U,
                                0x3FF6BE411F37AC77U, 0x3FEFFFFFFFFFFFFFU, 0x434D02967C31CDB5U, 0xBCA1A62633145C07U,
                                0x3DDB7CDFD9D7BDBBU, 0xBCB1A62633145C07U, 0xBFF0000000000001U, 0xBF65F9EC58DF65A1U});
    // fdlibm's famous check of the large-argument reduction: sin(1e22) = -0.8522008497671888.
    CHECK(strict::sin(1e22) == -0.8522008497671888);
}

TEST_CASE("atan, exp, log, log10, cbrt, expm1 and log1p equal Java's StrictMath") {
    check_goldens(strict::atan, {0x3FDDAC670561BB4FU, 0x3FE921FB54442D18U, 0x3FF1B6E192EBBE44U, 0x3FF3FC176B7A8560U,
                                 0x3FF789BD2C160054U, 0x3FF8F905EB2DEF22U, 0x3FF921FB54442D18U, 0xBFF921FB54442D18U,
                                 0x3FF921FB54442D18U, 0x3FE54E04C05D06A0U, 0x3FF00FE987ED02FFU, 0x3FF433B8A322DDD2U,
                                 0x3DDB7CDFD9D7BDBBU, 0x3FF69B8154BAF42EU, 0x3FF2B5F4B53C7949U, 0x3FF921FA0E51262AU});
    check_goldens([](double x) { return strict::exp(x / 100); },
                  {0x3FF0148802529619U, 0x3FF0292A5D2F2226U, 0x3FF052BEA3EC0132U, 0x3FF07CBDE4B58641U,
                   0x3FF1AEC7B35A00D4U, 0x4005BF0A8B14576AU, 0x7FF0000000000000U, 0x0000000000000000U,
                   0x7FF0000000000000U, 0x3FF0204BEC1549EBU, 0x3FF040D909432BE1U, 0x3FF082B8E6507C64U,
                   0x3FF0000000001198U, 0x3FF1099DD19852DCU, 0x3FF061A7DB212F20U, 0x7FF0000000000000U});
    check_goldens([](double x) { return std::isnan(strict::log(x)) ? qnan : strict::log(x); },
                  {0xBFE62E42FEFA39EFU, 0x0000000000000000U, 0x3FE62E42FEFA39EFU, 0x3FF193EA7AAD030AU,
                   0x40026BB1BBB55516U, 0x40126BB1BBB55516U, 0x40495414621954FEU, 0x7FF8000000000000U,
                   0x4085963447F87FB5U, 0xBFCEEB95B094C193U, 0x3FDCE6BB25AA1315U, 0x3FF250D048E7A1BDU,
                   0xC037069E2AA2AA5BU, 0x3FFD67F1C864BEB4U, 0x3FEB6CEF8934D5B0U, 0x402B3E2567DA7A43U});
    check_goldens([](double x) { return std::isnan(strict::log10(x)) ? qnan : strict::log10(x); },
                  {0xBFD34413509F79FFU, 0x0000000000000000U, 0x3FD34413509F79FFU, 0x3FDE8927964FD5FDU,
                   0x3FF0000000000000U, 0x4000000000000000U, 0x4036000000000000U, 0x7FF8000000000000U,
                   0x4072C00000000000U, 0xBFBADB63B88D410DU, 0x3FC91A74C4F85377U, 0x3FDFD14DB31BA3BAU,
                   0xC024000000000000U, 0x3FE98AB081DD8EDDU, 0x3FD7D24EA82C85BAU, 0x4017A9AA95CCD5AAU});
    check_goldens(strict::cbrt, {0x3FE965FEA53D6E3DU, 0x3FF0000000000000U, 0x3FF428A2F98D728BU, 0x3FF7137449123EF6U,
                                 0x40013C484138704FU, 0x401290FCA9C761F8U, 0x41748BD9AE67B4BAU, 0xC1748BD9AE67B4BAU,
                                 0x54B249AD2594C37DU, 0x3FED8639FDCB60EAU, 0x3FF2996264E0E3FEU, 0x3FF76EF7E73104B7U,
                                 0x3F3E6B4B396428E8U, 0x3FFD8639FDCB60EAU, 0x3FF54A6CAAD6204EU, 0x40576EF7E6C3BC8CU});
    check_goldens([](double x) { return strict::expm1(x / 100); },
                  {0x3F74880252961978U, 0x3F84952E9791133FU, 0x3F94AFA8FB004C8AU, 0x3F9F2F792D619028U,
                   0x3FBAEC7B35A00D3AU, 0x3FFB7E151628AED2U, 0x7FF0000000000000U, 0xBFF0000000000000U,
                   0x7FF0000000000000U, 0x3F8025F60AA4F585U, 0x3F90364250CAF832U, 0x3FA0571CCA0F8C7FU,
                   0x3D719799812DF3BDU, 0x3FB099DD19852DBCU, 0x3F9869F6C84BC7E0U, 0x7FF0000000000000U});
    check_goldens([](double x) { return std::isnan(strict::log1p(x)) ? qnan : strict::log1p(x); },
                  {0x3FD9F323ECBF984CU, 0x3FE62E42FEFA39EFU, 0x3FF193EA7AAD030AU, 0x3FF62E42FEFA39EFU,
                   0x40032EE3B77F374CU, 0x401275E2271BBA31U, 0x40495414621954FEU, 0x7FF8000000000000U,
                   0x4085963447F87FB5U, 0x3FE28C6C3A79FE29U, 0x3FEE3703DB0AB11AU, 0x3FF6BCBED09F00AFU,
                   0x3DDB7CDFD9D1D693U, 0x3FFFC4E3465E2150U, 0x3FF35F77EFCA0C16U, 0x402B3E259098D982U});
}

TEST_CASE("asin, acos, atan2 and pow equal Java's StrictMath") {
    constexpr std::array<double, 11> us{-1.0, -0.98, -0.5, -0.3, 0.0, 1e-9, 0.25, 0.5, 0.75, 0.976, 1.0};
    constexpr std::array<std::uint64_t, 11> asins{0xBFF921FB54442D18U, 0xBFF5ED690583BE07U, 0xBFE0C152382D7366U,
                                                  0xBFD380159E14F6FFU, 0x0000000000000000U, 0x3E112E0BE826D695U,
                                                  0x3FD02BE9CE0B87CDU, 0x3FE0C152382D7366U, 0x3FEB235315C680DCU,
                                                  0x3FF59EC9E036730AU, 0x3FF921FB54442D18U};
    constexpr std::array<std::uint64_t, 11> acoss{0x400921FB54442D18U, 0x400787B22CE3F590U, 0x4000C152382D7366U,
                                                  0x3FFE0200BBC96AD8U, 0x3FF921FB54442D18U, 0x3FF921FB53FF74E9U,
                                                  0x3FF51700E0C14B25U, 0x3FF0C152382D7366U, 0x3FE720A392C1D955U,
                                                  0x3FCC198BA06DD073U, 0x0000000000000000U};
    for (std::size_t i = 0; i < us.size(); ++i) {
        INFO("argument ", us[i]);
        CHECK(bits(strict::asin(us[i])) == asins[i]);
        CHECK(bits(strict::acos(us[i])) == acoss[i]);
    }
    struct Pair {
        double a;
        double b;
        std::uint64_t expected;
    };
    // pow, the last case the bug fdlibm 5.3's readme lists as fixed: x just below -1, y huge and odd, close to -e.
    constexpr std::array<Pair, 10> pows{
        Pair{2.0, 0.5, 0x3FF6A09E667F3BCDU},       Pair{10.0, -3.0, 0x3F50624DD2F1A9FCU},
        Pair{0.5, 1.3, 0x3FD9FDF8BCCE533DU},       Pair{1.0000001, 3e9, 0x5AFC05A56D6753A4U},
        Pair{-2.0, 3.0, 0xC020000000000000U},      Pair{-2.0, 0.5, 0x7FF8000000000000U},
        Pair{2.0, 1023.9, 0x7FEDDB680117AA8EU},    Pair{2.0, -1074.5, 0x0000000000000001U},
        Pair{7.0, 1.0 / 3.0, 0x3FFE9B5DBA58189DU}, Pair{-1.0000000000000002, 4.503599627370497e15, 0xC005BF0A8B14576AU},
    };
    for (const Pair& p : pows) {
        INFO("pow ", p.a, " ", p.b);
        CHECK(same(strict::pow(p.a, p.b), from(p.expected)));
    }
    constexpr std::array<Pair, 8> atan2s{
        Pair{1.0, 1.0, 0x3FE921FB54442D18U},      Pair{-1.0, -1.0, 0xC002D97C7F3321D2U},
        Pair{1.0, -1e-300, 0x3FF921FB54442D19U},  Pair{0.0, -1.0, 0x400921FB54442D18U},
        Pair{-0.0, -1.0, 0xC00921FB54442D18U},    Pair{3.0, 4.0, 0x3FE4978FA3269EE1U},
        Pair{1e300, 1e-300, 0x3FF921FB54442D18U}, Pair{-5.0, 2.0, 0xBFF30B6D796A4DA8U},
    };
    for (const Pair& p : atan2s) {
        INFO("atan2 ", p.a, " ", p.b);
        CHECK(bits(strict::atan2(p.a, p.b)) == p.expected);
    }
}

TEST_CASE("sinh equals Java's StrictMath over the tile reader's range and fdlibm's branch points") {
    constexpr std::array<double, 16> xs{0.5,
                                        1.0,
                                        2.0,
                                        3.0,
                                        3.141592653589793,
                                        -3.141592653589793,
                                        1e-10,
                                        0x1p-28,
                                        21.999999,
                                        22.0,
                                        100.0,
                                        709.78,
                                        710.0,
                                        710.4758600739439,
                                        711.0,
                                        -0.75};
    constexpr std::array<std::uint64_t, 16> expected{
        0x3FE0ACD00FE63B97U, 0x3FF2CD9FC44EB982U, 0x400D03CF63B6E1A0U, 0x40240926E70949AEU,
        0x402718F45D72E672U, 0xC02718F45D72E672U, 0x3DDB7CDFD9D7BDBBU, 0x3E30000000000000U,
        0x41DAB5ABF9A70E9EU, 0x41DAB5ADB9C43600U, 0x48E3494A9B171BF5U, 0x7FDFE9CE5C4C52B4U,
        0x7FE3E21A464507FAU, 0x7FEFFFFFFFFFFD3BU, 0x7FF0000000000000U, 0xBFEA506B2DD3C690U};
    for (std::size_t i = 0; i < xs.size(); ++i) {
        INFO("sinh(", xs[i], ")");
        CHECK(bits(strict::sinh(xs[i])) == expected[i]);
    }
    CHECK(bits(strict::sinh(-0.0)) == bits(-0.0));
    CHECK(strict::sinh(-inf) == -inf);
    CHECK(std::isnan(strict::sinh(qnan)));
    CHECK(atlas::pymath::checked::sinh(711.0).error() == atlas::pymath::Error::range);
    CHECK(atlas::pymath::checked::sinh(inf).value() == inf);
}

TEST_CASE("special values are Java's: signed zeros, infinities and NaN") {
    for (const double z : {0.0, -0.0}) {
        CHECK(bits(strict::sin(z)) == bits(z));
        CHECK(bits(strict::tan(z)) == bits(z));
        CHECK(bits(strict::asin(z)) == bits(z));
        CHECK(bits(strict::atan(z)) == bits(z));
        CHECK(bits(strict::cbrt(z)) == bits(z));
        CHECK(bits(strict::expm1(z)) == bits(z));
        CHECK(bits(strict::log1p(z)) == bits(z));
        CHECK(strict::cos(z) == 1.0);
        CHECK(strict::exp(z) == 1.0);
        CHECK(strict::log(z) == -inf);
        CHECK(strict::log10(z) == -inf);
    }
    for (const double i : {inf, -inf}) {
        CHECK(std::isnan(strict::sin(i)));
        CHECK(std::isnan(strict::cos(i)));
        CHECK(std::isnan(strict::tan(i)));
        CHECK(bits(strict::cbrt(i)) == bits(i));
    }
    CHECK(strict::atan(inf) == strict::atan2(1.0, 0.0));
    CHECK(strict::exp(inf) == inf);
    CHECK(bits(strict::exp(-inf)) == bits(0.0));
    CHECK(strict::expm1(inf) == inf);
    CHECK(strict::expm1(-inf) == -1.0);
    CHECK(strict::log(inf) == inf);
    CHECK(std::isnan(strict::log(-1.0)));
    CHECK(std::isnan(strict::log10(-1.0)));
    CHECK(strict::log1p(-1.0) == -inf);
    CHECK(std::isnan(strict::log1p(-2.0)));
    CHECK(std::isnan(strict::asin(1.0000000000000002)));
    CHECK(std::isnan(strict::acos(-1.0000000000000002)));
    CHECK(strict::cbrt(-8.0) == -2.0);
    CHECK(strict::exp(709.78) < inf);
    CHECK(strict::exp(709.79) == inf);
    CHECK(strict::exp(-745.2) == 0.0);
    double ten_to_k = 1.0; // exact up to 10^22
    for (int k = 0; k <= 22; ++k, ten_to_k *= 10.0) {
        CHECK(strict::log10(ten_to_k) == static_cast<double>(k));
    }
    CHECK(std::isnan(strict::sin(qnan)));
    CHECK(std::isnan(strict::exp(qnan)));
    CHECK(std::isnan(strict::pow(qnan, 2.0)));
    CHECK(std::isnan(strict::pow(qnan, -2.5)));
}

TEST_CASE("atan2 at its special arguments, as Java documents them") {
    const double pi = atlas::pymath::pi;
    CHECK(bits(strict::atan2(0.0, 0.0)) == bits(0.0));
    CHECK(bits(strict::atan2(-0.0, 0.0)) == bits(-0.0));
    CHECK(strict::atan2(0.0, -0.0) == pi);
    CHECK(strict::atan2(-0.0, -0.0) == -pi);
    CHECK(strict::atan2(1.0, 0.0) == pi / 2);
    CHECK(strict::atan2(-1.0, -0.0) == -pi / 2);
    CHECK(bits(strict::atan2(1.0, inf)) == bits(0.0));
    CHECK(bits(strict::atan2(-1.0, inf)) == bits(-0.0));
    CHECK(strict::atan2(1.0, -inf) == pi);
    CHECK(strict::atan2(-1.0, -inf) == -pi);
    CHECK(strict::atan2(inf, inf) == pi / 4);
    CHECK(strict::atan2(-inf, -inf) == -3 * pi / 4);
    CHECK(strict::atan2(inf, 5.0) == pi / 2);
    CHECK(std::isnan(strict::atan2(qnan, 1.0)));
    CHECK(std::isnan(strict::atan2(1.0, qnan)));
}

TEST_CASE("pow at the special cases fdlibm lists") {
    CHECK(strict::pow(qnan, 0.0) == 1.0);              // 1: anything ** 0
    CHECK(strict::pow(-3.5, 1.0) == -3.5);             // 2: anything ** 1
    CHECK(std::isnan(strict::pow(2.0, qnan)));         // 3
    CHECK(std::isnan(strict::pow(qnan, 2.0)));         // 4
    CHECK(strict::pow(-1.5, inf) == inf);              // 5
    CHECK(bits(strict::pow(1.5, -inf)) == bits(0.0));  // 6
    CHECK(bits(strict::pow(0.5, inf)) == bits(0.0));   // 7
    CHECK(strict::pow(-0.5, -inf) == inf);             // 8
    CHECK(std::isnan(strict::pow(-1.0, inf)));         // 9
    CHECK(bits(strict::pow(0.0, 3.0)) == bits(0.0));   // 10
    CHECK(bits(strict::pow(-0.0, 2.0)) == bits(0.0));  // 11
    CHECK(strict::pow(0.0, -2.0) == inf);              // 12
    CHECK(strict::pow(-0.0, -2.0) == inf);             // 13
    CHECK(bits(strict::pow(-0.0, 3.0)) == bits(-0.0)); // 14
    CHECK(strict::pow(-0.0, -3.0) == -inf);
    CHECK(strict::pow(inf, 0.5) == inf);               // 15
    CHECK(bits(strict::pow(inf, -0.5)) == bits(0.0));  // 16
    CHECK(bits(strict::pow(-inf, 3.0)) == bits(-inf)); // 17
    CHECK(bits(strict::pow(-inf, -3.0)) == bits(-0.0));
    CHECK(strict::pow(-2.0, 10.0) == 1024.0); // 18
    CHECK(strict::pow(-2.0, -1.0) == -0.5);
    CHECK(std::isnan(strict::pow(-2.0, 1.5)));       // 19
    CHECK(std::isnan(strict::pow(-8.0, 1.0 / 3.0))); // (-8)**(1/3) is not real
    CHECK(strict::pow(10.0, 308.0) < inf);
    CHECK(strict::pow(10.0, 309.0) == inf);
    CHECK(strict::pow(-10.0, 309.0) == -inf);
}

TEST_CASE("odd functions stay odd to the bit, from tiny to huge arguments") {
    std::uint64_t s = 12345;
    for (int i = 0; i < 20000; ++i) {
        s = s * 6364136223846793005U + 1442695040888963407U;
        const auto e = static_cast<std::uint64_t>((s >> 33U) % 2046U) + 1U;
        const double x = from((e << 52U) | ((s * 0x9E3779B97F4A7C15U) >> 12U));
        INFO("x = ", x);
        CHECK(same(strict::sin(-x), -strict::sin(x)));
        CHECK(same(strict::tan(-x), -strict::tan(x)));
        CHECK(same(strict::atan(-x), -strict::atan(x)));
        CHECK(same(strict::asin(-x), -strict::asin(x)));
        CHECK(same(strict::cbrt(-x), -strict::cbrt(x)));
        CHECK(same(strict::cos(-x), strict::cos(x)));
        CHECK(same(strict::sinh(-x), -strict::sinh(x)));
    }
}

// NOLINTEND(modernize-use-designated-initializers)
