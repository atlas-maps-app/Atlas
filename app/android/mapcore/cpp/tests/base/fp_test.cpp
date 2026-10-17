// The floating point the port's bits need (plan-vector T3): no contraction anywhere in the core (clang fuses a*b + c
// by default on arm64; Python and the JVM never do), round to nearest, no excess precision, subnormals kept.
#include "base/simd.hpp"
#include "base/std_check.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cfenv>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>

namespace {

// Operands where the fused and the separately rounded results differ: a·b = 1 + 2^-11 + 2^-24 rounds (to even) to
// 1 + 2^-11, so a·b + c is 0 rounded twice and 2^-24 rounded once.
constexpr float a32 = 1.0F + 0x1p-12F;
constexpr float c32 = -(1.0F + 0x1p-11F);
constexpr double a64 = 1.0 + 0x1p-27;
constexpr double c64 = -(1.0 + 0x1p-26);

} // namespace

TEST_CASE("a*b + c is two roundings in the core, as in the port") {
    volatile float a = a32;
    volatile float c = c32;
    const float unfused = (a * a) + c;
    CHECK(unfused == 0.0F);
    CHECK(std::fma(a32, a32, c32) == 0x1p-24F);

    volatile double ad = a64;
    volatile double cd = c64;
    const double unfused64 = (ad * ad) + cd;
    CHECK(unfused64 == 0.0);
    CHECK(std::fma(a64, a64, c64) == 0x1p-54);
}

TEST_CASE("vector lanes are not fused either, unless fma says so") {
    volatile float a = a32;
    volatile float c = c32;
    const atlas::simd::f32x4 va(a);
    const atlas::simd::f32x4 vc(c);
    const atlas::simd::f32x4 unfused = (va * va) + vc;
    const atlas::simd::f32x4 fused = atlas::simd::fma(va, va, vc);
    for (int i = 0; i < 4; ++i) {
        CHECK(unfused[i] == 0.0F);
        CHECK(fused[i] == 0x1p-24F);
    }
}

namespace {

// The shapes of Python's min(x, 0.0) and max(x, -0.0), as C++ defines them by ternaries. Not inlined, and called
// with operands known only at run time, so the compiler cannot fold them.
[[gnu::noinline]] double min_with_zero(double x) { return x < 0.0 ? x : 0.0; }
[[gnu::noinline]] double max_with_negative_zero(double x) { return x > -0.0 ? x : -0.0; }

[[nodiscard]] bool negative(double x) { return (std::bit_cast<std::uint64_t>(x) >> 63U) != 0; }

} // namespace

TEST_CASE("canary: clang still turns a signed-zero ternary into fminnm on aarch64") {
    // C++ (and Python) give min(-0.0, 0.0) = +0.0 and max(+0.0, -0.0) = -0.0. On aarch64 from -O1 up, Apple clang
    // 21.0.0 (clang-2100.1.1.101) and NDK r30's clang 21.0.0 (Android 16134705, r574158c) compile the min ternary to
    // fminnm, which orders -0.0 below +0.0 and gives -0.0 (found by K3b, 16 October). The max ternary becomes fmaxnm
    // (+0.0) wherever ASan or TSan does not instrument it: measured in the release builds, in the Android debug build
    // (-O1 -g with UBSan's minimal runtime) and at plain -O1 and -O2, but not in the host debug (ASan) and TSan
    // builds. So the core writes Python's min and max with pymath::min and pymath::max, never std::min, std::max or a
    // bare ternary. This canary asserts the miscompile that every optimised aarch64 build makes; when it fails there,
    // clang has changed, and pymath's own tests stay the guard.
    volatile double negative_zero = -0.0;
    volatile double positive_zero = 0.0;
    const double low = min_with_zero(negative_zero);
    const double high = max_with_negative_zero(positive_zero);
#if defined(__aarch64__) && defined(__OPTIMIZE__)
    CHECK(negative(low)); // miscompiled: fminnm(-0.0, +0.0) = -0.0
    MESSAGE("max(+0.0, -0.0) in this build: " << std::string(negative(high) ? "-0.0, as C++ defines it"
                                                                            : "+0.0, miscompiled to fmaxnm"));
#else
    CHECK(!negative(low)); // as C++ defines it (x86_64's minsd and maxsd return the second operand for two zeros)
    CHECK(negative(high));
#endif
}

TEST_CASE("rounding is to nearest, without excess precision, and subnormals survive") {
    CHECK(std::fegetround() == FE_TONEAREST); // FLT_EVAL_METHOD == 0 is base/std_check.hpp's
    volatile float tiny = std::numeric_limits<float>::denorm_min();
    const float doubled = tiny * 2.0F;
    CHECK(doubled > 0.0F);
    CHECK(std::bit_cast<std::uint32_t>(doubled) == 2U);
    volatile float half_min = std::numeric_limits<float>::min() * 0.5F;
    CHECK(half_min > 0.0F); // flushing to zero would make it 0
    // A float operation rounds to float even when its operands came from doubles.
    volatile float third = 1.0F / 3.0F;
    CHECK(std::bit_cast<std::uint32_t>(third) == 0x3EAAAAABU);
}
