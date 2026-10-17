// The subset of Slang (HLSL) that the shaders' shared modules are written in, as C++ (plan-vector T6). A file in
// shaders/shared/ compiles both as Slang for the GPU and, after this header, as C++ for the twin, so the CPU runs the
// shader's own text. The vector types are clang's ext_vector_type, with swizzles (v.xy, v.zyx) and lane-wise operators.
//
// The rules of the shared subset, so one text means the same in both languages:
// - write float literals with the f suffix (`0.5f`: an unsuffixed literal is a double in C++), declare functions
//   `inline`, guard the file with `#pragma once`, and cast with `float(x)` or `uint(x)` (no C casts);
// - construct a vector from components by declaration, `float3 v = {x, y, z};` (C++ has no `float3(x, y, z)`),
//   splat with `float3(s)`; return structs instead of `out` parameters; no attributes, semantics or resources outside
//   `#if !defined(__cplusplus)`;
// - exact code (noise, rank depths, snapping, the composite's 8-bit stages) uses only +, -, * (correctly rounded and,
//   with contraction off, never fused), comparisons, selects, bit casts and the exact functions below; it multiplies
//   by an exact reciprocal instead of dividing, and fuses only by calling a fused multiply-add explicitly. Slang's
//   lerp, dot, cross, sqrt and fma are not exact on a GPU (Vulkan leaves their rounding open), so exact modules do not
//   call them; the build's nocontract check rejects them in exact modules (cmake/Shaders.cmake).
#pragma once

#include "base/opaque.hpp"

#include <bit>
#include <concepts>
#include <cstdint>
#include <type_traits>

namespace atlas::hlsl {

using uint = std::uint32_t;

using float2 = float __attribute__((ext_vector_type(2)));
using float3 = float __attribute__((ext_vector_type(3)));
using float4 = float __attribute__((ext_vector_type(4)));
using int2 = std::int32_t __attribute__((ext_vector_type(2)));
using int3 = std::int32_t __attribute__((ext_vector_type(3)));
using int4 = std::int32_t __attribute__((ext_vector_type(4)));
using uint2 = std::uint32_t __attribute__((ext_vector_type(2)));
using uint3 = std::uint32_t __attribute__((ext_vector_type(3)));
using uint4 = std::uint32_t __attribute__((ext_vector_type(4)));

/// A float or a float vector: what the arithmetic helpers accept.
template <class T>
concept FloatLike =
    std::same_as<T, float> || std::same_as<T, float2> || std::same_as<T, float3> || std::same_as<T, float4>;

// Bit casts, exact.
[[nodiscard]] constexpr float asfloat(uint bits) noexcept { return std::bit_cast<float>(bits); }
[[nodiscard]] constexpr float asfloat(std::int32_t bits) noexcept { return std::bit_cast<float>(bits); }
[[nodiscard]] constexpr uint asuint(float value) noexcept { return std::bit_cast<uint>(value); }
[[nodiscard]] constexpr std::int32_t asint(float value) noexcept { return std::bit_cast<std::int32_t>(value); }

// Exact functions: the rounding functions, absolute value, minimum, maximum and clamps. On the GPU, FMin, FMax and
// FClamp may order -0 and +0 either way and treat NaN as they like, so shader code whose result must not depend on the
// driver keeps signed zeros and NaN away from them. Here they are Python's min and max (b only where b < a, or b > a,
// as pymath::min and pymath::max), so the twin gives the same bits on arm64 and x86_64, where the hardware's minimum
// would not: clamp(x, lo, hi) is min(max(x, lo), hi) in that order, and saturate(-0.0) is -0.0, as Python's
// min(max(-0.0, 0.0), 1.0) is.
template <FloatLike T>
[[nodiscard]] T abs(T x) noexcept {
    return __builtin_elementwise_abs(x);
}
template <FloatLike T>
[[nodiscard]] T floor(T x) noexcept {
    return __builtin_elementwise_floor(x);
}
template <FloatLike T>
[[nodiscard]] T ceil(T x) noexcept {
    return __builtin_elementwise_ceil(x);
}
template <FloatLike T>
[[nodiscard]] T trunc(T x) noexcept {
    return __builtin_elementwise_trunc(x);
}
template <FloatLike T>
[[nodiscard]] T frac(T x) noexcept {
    return x - floor(x);
}
template <FloatLike T>
[[nodiscard]] T min(T a, T b) noexcept {
    if constexpr (std::same_as<T, float>) {
        return opaque(b < a) ? b : a;
    } else {
        return opaque(b < a) != 0 ? b : a; // lane by lane: the comparison's mask, opaque, selects
    }
}
template <FloatLike T>
[[nodiscard]] T max(T a, T b) noexcept {
    if constexpr (std::same_as<T, float>) {
        return opaque(b > a) ? b : a;
    } else {
        return opaque(b > a) != 0 ? b : a;
    }
}
template <FloatLike T>
[[nodiscard]] T clamp(T x, T lo, T hi) noexcept {
    return min(max(x, lo), hi);
}
template <FloatLike T>
[[nodiscard]] T saturate(T x) noexcept {
    return clamp(x, T(0.0F), T(1.0F));
}

/// 0 where x < edge, else 1, lane by lane (HLSL's step).
template <FloatLike T>
[[nodiscard]] T step(T edge, T x) noexcept {
    if constexpr (std::same_as<T, float>) {
        return x < edge ? 0.0F : 1.0F;
    } else {
        return x < edge ? T(0.0F) : T(1.0F);
    }
}

/// Picks a where the condition holds, else b (HLSL 2021's select).
template <FloatLike T>
[[nodiscard]] T select(bool condition, T a, T b) noexcept {
    return condition ? a : b;
}

/// The correctly rounded fused multiply-add, a·b + c rounded once: where an exact rule fuses, it says so with this.
template <FloatLike T>
[[nodiscard]] T fma_exact(T a, T b, T c) noexcept {
    return __builtin_elementwise_fma(a, b, c);
}

// Approximate helpers for shared code that is not exact (light, colour): the GPU rounds these its own way.

/// a + t·(b − a), HLSL's definition of lerp.
template <FloatLike T>
[[nodiscard]] T lerp(T a, T b, T t) noexcept {
    return a + t * (b - a);
}

/// The dot product, summed left to right.
[[nodiscard]] inline float dot(float2 a, float2 b) noexcept { return (a.x * b.x) + (a.y * b.y); }
[[nodiscard]] inline float dot(float3 a, float3 b) noexcept { return (a.x * b.x) + (a.y * b.y) + (a.z * b.z); }
[[nodiscard]] inline float dot(float4 a, float4 b) noexcept {
    return (a.x * b.x) + (a.y * b.y) + (a.z * b.z) + (a.w * b.w);
}

[[nodiscard]] inline float3 cross(float3 a, float3 b) noexcept {
    float3 r = {(a.y * b.z) - (a.z * b.y), (a.z * b.x) - (a.x * b.z), (a.x * b.y) - (a.y * b.x)};
    return r;
}

} // namespace atlas::hlsl
