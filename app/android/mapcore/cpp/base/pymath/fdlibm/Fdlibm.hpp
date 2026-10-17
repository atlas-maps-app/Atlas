// fdlibm 5.3's internal kernels, ported to C++ for pymath's StrictMath functions (base/pymath/PyMath.hpp). Each keeps
// the C source's operations in their order and its constants bit for bit; the core compiles without contraction
// (-ffp-contract=off), so every operation rounds as Java's StrictMath and the JDK's fdlibm round it.
#pragma once

#include <array>
#include <cstdint>
#include <span>

namespace atlas::pymath::fdlibm {

/// __kernel_sin(x, y, iy): sin on [-pi/4, pi/4]; y is the tail of x, iy is 0 when y is zero.
[[nodiscard]] double kernel_sin(double x, double y, int iy) noexcept;

/// __kernel_cos(x, y): cos on [-pi/4, pi/4]; y is the tail of x.
[[nodiscard]] double kernel_cos(double x, double y) noexcept;

/// __kernel_tan(x, y, iy): tan (iy = 1) or -1/tan (iy = -1) on [-pi/4, pi/4]; y is the tail of x.
[[nodiscard]] double kernel_tan(double x, double y, int iy) noexcept;

/// __ieee754_rem_pio2(x, y): x - n*pi/2 as y[0] + y[1]; returns n (only its low bits matter to the callers).
[[nodiscard]] int rem_pio2(double x, std::array<double, 2>& y) noexcept;

/// __kernel_rem_pio2 for double precision (prec 2): the reduction of a large argument given as up to three 24-bit
/// pieces `x` scaled by 2^e0; writes the remainder to y[0] + y[1] and returns n & 7.
[[nodiscard]] int kernel_rem_pio2(std::span<const double> x, std::array<double, 2>& y, int e0) noexcept;

/// scalbn(x, n): x * 2^n, rounded once, as fdlibm 5.3 computes it.
[[nodiscard]] double scalbn(double x, int n) noexcept;

} // namespace atlas::pymath::fdlibm
