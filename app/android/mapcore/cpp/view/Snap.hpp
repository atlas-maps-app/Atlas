// Snapping at rest (plan-vector D4, F-M5): when the camera rests, everything the frame draws lands where the renders'
// SVG prints it. The vertex stage rounds every projected point to 0.1 CSS px as City.P prints it (`%.1f`:
// shaders/shared/camera.slang's snap_tenth, in float, which the twin runs as C++); circle centres and radii print at
// `%.1f`, and stroke widths, gradient stops and ground-text matrices at the precision their SVG prints them, which the
// core rounds its values to before the GPU sees them (decimals).
//
// Python's '%.nf' rounds the exact binary value of a double to n decimals, ties to even, and keeps a zero's sign (-0.0
// for a small negative). These do the same, exactly: |v| = m · 2^(e − 52) with its 53-bit significand m, so
// 10^n·|v| = 10^n·m · 2^(e − 52), whose integer part and remainder come from one shift (10^n·m < 2^63 for n ≤ 3), and
// the remainder against a half decides, an exact half going to the even integer; the result is that integer over 10^n,
// an IEEE division, so it is the double nearest the printed decimal, which prints the same.
#pragma once

#include <cstdint>

namespace atlas::view {

/// The double Python's '%.<n>f' % v prints, n from 0 to 3: v rounded to n decimals (ties to even, on the exact binary
/// value), its sign kept; a whole double, an infinity or a NaN unchanged.
[[nodiscard]] double decimals(double v, int n) noexcept;

/// The double '%.1f' % v prints (City.P's coordinates): decimals(v, 1).
[[nodiscard]] inline double snap_tenth(double v) noexcept { return decimals(v, 1); }

/// The float the vertex stage snaps `v` to (camera.slang's snap_tenth, as C++).
[[nodiscard]] float snap_tenth(float v) noexcept;

} // namespace atlas::view
