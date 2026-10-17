// fdlibm 5.3's s_cbrt.c: StrictMath.cbrt.
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
#include "base/pymath/PyMath.hpp"
#include "base/pymath/fdlibm/Words.hpp"

#include <cstdint>

namespace atlas::pymath {

namespace {

using fdlibm::constant;
using fdlibm::from_words;
using fdlibm::hi;
using fdlibm::lo;
using fdlibm::with_hi;
using fdlibm::with_lo;

// NOLINTBEGIN(readability-identifier-naming): fdlibm's own names, so each constant reads against its C source
// s_cbrt.c
constexpr std::uint32_t B1 = 715094163; // fdlibm: B1 = (682-0.03306235651)*2**20
constexpr std::uint32_t B2 = 696219795; // fdlibm: B2 = (664-0.03306235651)*2**20
constexpr double C = constant(5.42857142857142815906e-01, 0x3FE15F15, 0xF15F15F1);  // fdlibm: 19/35
constexpr double D = constant(-7.05306122448979611050e-01, 0xBFE691DE, 0x2532C834); // fdlibm: -864/1225
constexpr double E = constant(1.41428571428571436819e+00, 0x3FF6A0EA, 0x0EA0EA0F);  // fdlibm: 99/70
constexpr double F = constant(1.60714285714285720630e+00, 0x3FF9B6DB, 0x6DB6DB6E);  // fdlibm: 45/28
constexpr double G = constant(3.57142857142857150787e-01, 0x3FD6DB6D, 0xB6DB6DB7);  // fdlibm: 5/14

// NOLINTEND(readability-identifier-naming)
} // namespace

double strict::cbrt(double x) noexcept {
    std::int32_t hx = hi(x);                                        // fdlibm: high word of x
    const auto sign = static_cast<std::uint32_t>(hx) & 0x80000000U; // fdlibm: sign= sign(x)
    hx = static_cast<std::int32_t>(static_cast<std::uint32_t>(hx) ^ sign);
    if (hx >= 0x7ff00000) {
        return x + x; // fdlibm: cbrt(NaN,INF) is itself
    }
    if ((static_cast<std::uint32_t>(hx) | lo(x)) == 0) {
        return x; // fdlibm: cbrt(0) is itself
    }

    x = with_hi(x, hx); // fdlibm: x <- |x|
    // fdlibm: rough cbrt to 5 bits
    double t = 0.0;
    if (hx < 0x00100000) {             // fdlibm: subnormal number
        t = from_words(0x43500000, 0); // fdlibm: set t= 2**54
        t *= x;
        t = with_hi(t, static_cast<std::int32_t>(static_cast<std::uint32_t>(hi(t) / 3) + B2));
    } else {
        t = with_hi(t, static_cast<std::int32_t>(static_cast<std::uint32_t>(hx / 3) + B1));
    }

    // fdlibm: new cbrt to 23 bits, may be implemented in single precision
    double r = t * t / x;
    double s = C + r * t;
    t *= G + F / (s + E + D / s);

    // fdlibm: chopped to 20 bits and make it larger than cbrt(x)
    t = with_lo(t, 0);
    t = fdlibm::add_hi(t, 1);

    // fdlibm: one step newton iteration to 53 bits with error less than 0.667 ulps
    s = t * t; // fdlibm: t*t is exact
    r = x / s;
    const double w = t + t;
    r = (r - t) / (w + r); // fdlibm: r-s is exact
    t = t + t * r;

    // fdlibm: restore the sign bit
    return with_hi(t, static_cast<std::int32_t>(static_cast<std::uint32_t>(hi(t)) | sign));
}

} // namespace atlas::pymath
