// fdlibm 5.3's s_scalbn.c (with s_copysign.c): x * 2^n rounded once, for pow, the argument reduction and math.ldexp.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunPro, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================
//
// Ported to C++ for Atlas (plan-vector K3b). Comments marked "fdlibm:" are the original's.
#include "base/pymath/fdlibm/Fdlibm.hpp"
#include "base/pymath/fdlibm/Words.hpp"

#include <cstdint>

namespace atlas::pymath::fdlibm {

namespace {

constexpr double two54 = constant(1.80143985094819840000e+16, 0x43500000, 0x00000000);
constexpr double twom54 = constant(5.55111512312578270212e-17, 0x3C900000, 0x00000000);
constexpr double huge = 1.0e+300;
constexpr double tiny = 1.0e-300;

/// s_copysign.c: x with the sign of y.
[[nodiscard]] double copysign_words(double x, double y) noexcept {
    return with_hi(x, (hi(x) & 0x7fffffff) | (hi(y) & INT32_MIN));
}

} // namespace

double scalbn(double x, int n) noexcept {
    std::int32_t hx = hi(x);
    const std::uint32_t lx = lo(x);
    std::int32_t k = (hx & 0x7ff00000) >> 20; // fdlibm: extract exponent
    if (k == 0) {                             // fdlibm: 0 or subnormal x
        if ((lx | static_cast<std::uint32_t>(hx & 0x7fffffff)) == 0) {
            return x; // fdlibm: +-0
        }
        x *= two54;
        hx = hi(x);
        k = ((hx & 0x7ff00000) >> 20) - 54;
        if (n < -50000) {
            return tiny * x; // fdlibm: underflow
        }
    }
    if (k == 0x7ff) {
        return x + x; // fdlibm: NaN or Inf
    }
    k = k + n;
    if (k > 0x7fe) {
        return huge * copysign_words(huge, x); // fdlibm: overflow
    }
    if (k > 0) { // fdlibm: normal result
        return with_hi(x, (hx & static_cast<std::int32_t>(0x800fffffU)) | exponent(k));
    }
    if (k <= -54) {
        if (n > 50000) {                           // fdlibm: in case integer overflow in n+k
            return huge * copysign_words(huge, x); // fdlibm: overflow
        }
        return tiny * copysign_words(tiny, x); // fdlibm: underflow
    }
    k += 54; // fdlibm: subnormal result
    return with_hi(x, (hx & static_cast<std::int32_t>(0x800fffffU)) | exponent(k)) * twom54;
}

} // namespace atlas::pymath::fdlibm
