// Sine and cosine for the stand-in's camera, without the platform's libm (plan-vector T3: <cmath>'s transcendentals
// are not used in the core outside pymath). The stand-in is a load, not the map, so it needs no port bits: a
// polynomial after reduction to [-π/4, π/4], accurate to about 1e-15, is plenty, and gives every libm the same frame.
#pragma once

#include <cmath>
#include <cstdint>

namespace atlas::standin {

inline constexpr double pi = 3.141592653589793238462643383279502884;

namespace detail {

/// sin(x) for |x| <= π/4 (Taylor to x^15).
[[nodiscard]] constexpr double sin_kernel(double x) noexcept {
    const double x2 = x * x;
    return x * (1.0 + x2 * (-1.0 / 6 +
                            x2 * (1.0 / 120 + x2 * (-1.0 / 5040 +
                                                    x2 * (1.0 / 362880 + x2 * (-1.0 / 39916800 +
                                                                               x2 * (1.0 / 6227020800 +
                                                                                     x2 * (-1.0 / 1307674368000))))))));
}

/// cos(x) for |x| <= π/4 (Taylor to x^16).
[[nodiscard]] constexpr double cos_kernel(double x) noexcept {
    const double x2 = x * x;
    return 1.0 +
           x2 * (-1.0 / 2 +
                 x2 * (1.0 / 24 +
                       x2 * (-1.0 / 720 +
                             x2 * (1.0 / 40320 + x2 * (-1.0 / 3628800 +
                                                       x2 * (1.0 / 479001600 + x2 * (-1.0 / 87178291200 +
                                                                                     x2 * (1.0 / 20922789888000))))))));
}

} // namespace detail

/// sin and cos of `x` radians, together.
struct SinCos {
    double sin;
    double cos;
};

[[nodiscard]] inline SinCos sincos(double x) noexcept {
    // x = k·π/2 + r with |r| <= π/4; floor and the products are exact enough at the stand-in's angles (|x| < 10^4).
    const double k = std::floor(x / (pi / 2) + 0.5);
    const double r = x - k * (pi / 2);
    const double s = detail::sin_kernel(r);
    const double c = detail::cos_kernel(r);
    switch (static_cast<std::int64_t>(k) & 3) {
    case 0: return {.sin = s, .cos = c};
    case 1: return {.sin = c, .cos = -s};
    case 2: return {.sin = -s, .cos = -c};
    default: return {.sin = -c, .cos = s};
    }
}

[[nodiscard]] constexpr double radians(double degrees) noexcept { return degrees * (pi / 180.0); }

/// tan by its sine and cosine.
[[nodiscard]] inline double tan(double x) noexcept {
    const SinCos sc = sincos(x);
    return sc.sin / sc.cos;
}

} // namespace atlas::standin
