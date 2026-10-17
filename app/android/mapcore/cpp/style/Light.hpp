// The shaders' theme and light as C++ (plan-vector T6, D8): shaders/shared/theme.slang and light.slang compiled after
// base/hlsl.hpp, so the twin and the tests run the shader's own text. Everything lands in atlas::shaders.
//
// light.slang's few primitives that differ by language are defined here for C++: the port's IEEE division and
// StrictMath.exp (pymath::strict::exp, the port's own exp; the GPU's fdlibm copy exists only in the shader), Python's
// max and min through pymath, and the theme's doubles from their bits.
#pragma once

#include "base/hlsl.hpp"
#include "base/pymath/PyMath.hpp"

#include <bit>
#include <cmath>
#include <cstdint>

namespace atlas::shaders {

using namespace atlas::hlsl;

/// Three doubles, as Slang's double3 (the shared text uses only .x, .y, .z and braces). A plain struct: clang's
/// 256-bit vector of three doubles would change the x86_64 ABI where AVX is off (the emulator's), as -Wpsabi says.
struct Double3 {
    double x;
    double y;
    double z;
};
using double3 = Double3;

// A binary64 constant in the shared text: the plain literal in C++ (light.slang appends Slang's double suffix).
#define ATLAS_D(x) x

/// The double of a low and a high word (the theme's bits_ lanes).
[[nodiscard]] constexpr double light_bits(std::uint32_t lo, std::uint32_t hi) noexcept {
    return std::bit_cast<double>((static_cast<std::uint64_t>(hi) << 32U) | lo);
}

[[nodiscard]] inline double light_floor(double x) noexcept { return std::floor(x); }

/// Python's max(0.0, x).
[[nodiscard]] constexpr double light_max0(double x) noexcept { return pymath::max(0.0, x); }

/// Python's min(255, n) on a whole level n >= 0.
[[nodiscard]] constexpr double light_clamp_level(double r) noexcept { return pymath::min(255.0, r); }

/// The port's x / 255 and x / 50: IEEE division.
[[nodiscard]] constexpr double light_div255(double x) noexcept { return x / 255.0; }
[[nodiscard]] constexpr double light_div50(double x) noexcept { return x / 50.0; }

/// The port's math.exp: StrictMath.exp (fdlibm 5.3), as pymath computes it.
[[nodiscard]] inline double light_exp(double x) noexcept { return pymath::strict::exp(x); }

/// e^x for light_f32's shoulder: StrictMath.exp rounded to float (the GPU uses its own; E1 allows either).
[[nodiscard]] inline float light_exp_f32(float x) noexcept {
    return static_cast<float>(pymath::strict::exp(static_cast<double>(x)));
}

#include "shaders/shared/light.slang" // after theme.slang, which it includes first

#undef ATLAS_D

static_assert(sizeof(Theme) == 288, "theme.slang's Theme: eighteen four-lane vectors");
static_assert(alignof(Theme) == 16);

/// How far light_f32 may lie from the port's light(), in levels, for the surfaces and themes the renders draw (colours
/// 0-255, amb_k up to 1, sun_k up to 1.2, sky_k up to 1.7, unit normals; the theme block's reader refuses any other
/// theme, style/Theme.hpp). A first-order bound of binary32's error in units u = 2^-24, on the worst channel (a TONE
/// highlight in full sun, up to 680 levels before the shoulder): the sun's term (up to 321 levels; the dot product's 5u
/// absolute, eight roundings and the float inputs and constants) at most 4,500u; the ambient 1,100u; the sky 900u; the
/// two sums 1,040u; the shoulder (which passes errors on at most 1:1), its subtraction, division and exp (Vulkan's
/// 3 + 2|x| ulps, |x| up to 9.5) 600u; a division done as Vulkan's 2.5-ulp OpFDiv instead of a reciprocal 1,000u more.
/// 9,100u is 5.4e-4, rounded up. A driver that fuses a multiply and an add only removes a rounding. Measured once on
/// lavapipe and as C++ over 3,000,000 random surfaces: 6.8e-5 at worst, about a tenth of it; CP/tests/style holds every
/// run to the bound.
inline constexpr double light_f32_bound = 6.0e-4;

} // namespace atlas::shaders
