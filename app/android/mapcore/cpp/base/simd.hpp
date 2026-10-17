// Lanes for the hot loops (plan-vector T7): clang's vector extensions, which compile to NEON on arm64 and to SSE or AVX
// on x86_64 (the emulator and CI), with one source for both. Every operation here is exact under IEEE arithmetic and
// gives the same bits on every target, so a SIMD loop equals its scalar reference bit for bit: contraction is off for
// the whole core; the one fused operation, fma, is explicit and correctly rounded on every target (vfmaq_f32 and fmla
// on arm64, FMA3 where the x86_64 target has it, else libm's fmaf per lane, which is correctly rounded too); and min
// and max are Python's (pymath::min and pymath::max lane by lane), not the hardware's minimum, whose signed zeros
// differ between arm64 and x86_64.
#pragma once

#include "base/opaque.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace atlas::simd {

using f32x4 = float __attribute__((ext_vector_type(4)));
using f64x2 = double __attribute__((ext_vector_type(2)));
using i32x4 = std::int32_t __attribute__((ext_vector_type(4)));
using u32x4 = std::uint32_t __attribute__((ext_vector_type(4)));
using i64x2 = std::int64_t __attribute__((ext_vector_type(2)));
using u8x16 = std::uint8_t __attribute__((ext_vector_type(16)));

/// Whether the target fuses in hardware (otherwise fma runs libm's correctly rounded fma per lane, slower but equal).
#if defined(__aarch64__) || defined(__FMA__)
inline constexpr bool hardware_fma = true;
#else
inline constexpr bool hardware_fma = false;
#endif

/// Loads lanes from memory with any alignment.
template <class V, class T>
[[nodiscard]] inline V load(const T* from) noexcept {
    static_assert(sizeof(V) % sizeof(T) == 0);
    V v;
    std::memcpy(&v, from, sizeof(V));
    return v;
}

/// Stores lanes to memory with any alignment.
template <class V, class T>
inline void store(T* to, V v) noexcept {
    static_assert(sizeof(V) % sizeof(T) == 0);
    std::memcpy(to, &v, sizeof(V));
}

/// The correctly rounded fused multiply-add a·b + c, lane by lane.
[[nodiscard]] inline f32x4 fma(f32x4 a, f32x4 b, f32x4 c) noexcept { return __builtin_elementwise_fma(a, b, c); }
[[nodiscard]] inline f64x2 fma(f64x2 a, f64x2 b, f64x2 c) noexcept { return __builtin_elementwise_fma(a, b, c); }

/// Picks a's lanes where the mask is set (all bits, as comparisons produce), else b's.
[[nodiscard]] inline f32x4 select(i32x4 mask, f32x4 a, f32x4 b) noexcept { return mask != 0 ? a : b; }

/// Python's min(a, b) in each lane: b only where b < a, so the first of equal values (min(-0.0, +0.0) is -0.0,
/// min(+0.0, -0.0) is +0.0) and a NaN in a stays, as pymath::min. The mask is opaque, so clang cannot fold the select
/// into fminnm or minps (base/opaque.hpp).
[[nodiscard]] inline f32x4 min(f32x4 a, f32x4 b) noexcept { return select(opaque(b < a), b, a); }
/// Python's max(a, b) in each lane: b only where b > a, as pymath::max.
[[nodiscard]] inline f32x4 max(f32x4 a, f32x4 b) noexcept { return select(opaque(b > a), b, a); }
[[nodiscard]] inline f32x4 abs(f32x4 a) noexcept { return __builtin_elementwise_abs(a); }
[[nodiscard]] inline f32x4 floor(f32x4 a) noexcept { return __builtin_elementwise_floor(a); }
[[nodiscard]] inline f32x4 ceil(f32x4 a) noexcept { return __builtin_elementwise_ceil(a); }
[[nodiscard]] inline f32x4 trunc(f32x4 a) noexcept { return __builtin_elementwise_trunc(a); }
[[nodiscard]] inline f64x2 floor(f64x2 a) noexcept { return __builtin_elementwise_floor(a); }

/// The lanes' sum in a fixed order, ((v0 + v1) + v2) + v3, so it equals the scalar loop that adds in that order.
[[nodiscard]] inline float sum(f32x4 v) noexcept { return ((v.x + v.y) + v.z) + v.w; }

/// Truncates toward zero, as a static_cast of each lane would.
[[nodiscard]] inline i32x4 to_int(f32x4 v) noexcept { return __builtin_convertvector(v, i32x4); }

/// Converts each lane exactly or with round-to-nearest, as a static_cast of each lane would.
[[nodiscard]] inline f32x4 to_float(i32x4 v) noexcept { return __builtin_convertvector(v, f32x4); }

/// The lanes as an array, for scalar code and tests.
[[nodiscard]] inline std::array<float, 4> lanes(f32x4 v) noexcept { return {v.x, v.y, v.z, v.w}; }

} // namespace atlas::simd
