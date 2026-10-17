// fdlibm 5.3's e_exp.c, s_expm1.c, e_sinh.c, e_log.c, e_log10.c and s_log1p.c: StrictMath.exp, expm1, sinh, log,
// log10 and log1p.
//
// ====================================================
// Copyright (C) 1993, 2004 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================
//
// Ported to C++ for Atlas (plan-vector K3b). Comments marked "fdlibm:" are the original's.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/fdlibm/Words.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

namespace atlas::pymath::strict {

namespace {

using fdlibm::add_hi;
using fdlibm::constant;
using fdlibm::exponent;
using fdlibm::from_words;
using fdlibm::hi;
using fdlibm::lo;
using fdlibm::with_hi;

// NOLINTBEGIN(readability-identifier-naming): fdlibm's own names, so each constant reads against its C source
constexpr double one = 1.0;
constexpr double zero = 0.0;
constexpr double huge = 1.0e+300;
constexpr double tiny = 1.0e-300;

// e_exp.c
constexpr std::array<double, 2> halF{0.5, -0.5};
constexpr double twom1000 = constant(9.33263618503218878990e-302, 0x01700000, 0x00000000); // fdlibm: 2**-1000
constexpr double o_threshold = constant(7.09782712893383973096e+02, 0x40862E42, 0xFEFA39EF);
constexpr double u_threshold = constant(-7.45133219101941108420e+02, 0xc0874910, 0xD52D3051);
constexpr std::array<double, 2> ln2HI{constant(6.93147180369123816490e-01, 0x3fe62e42, 0xfee00000),
                                      constant(-6.93147180369123816490e-01, 0xbfe62e42, 0xfee00000)};
constexpr std::array<double, 2> ln2LO{constant(1.90821492927058770002e-10, 0x3dea39ef, 0x35793c76),
                                      constant(-1.90821492927058770002e-10, 0xbdea39ef, 0x35793c76)};
constexpr double invln2 = constant(1.44269504088896338700e+00, 0x3ff71547, 0x652b82fe);
constexpr double P1 = constant(1.66666666666666019037e-01, 0x3FC55555, 0x5555553E);
constexpr double P2 = constant(-2.77777777770155933842e-03, 0xBF66C16C, 0x16BEBD93);
constexpr double P3 = constant(6.61375632143793436117e-05, 0x3F11566A, 0xAF25DE2C);
constexpr double P4 = constant(-1.65339022054652515390e-06, 0xBEBBBD41, 0xC5D26BF1);
constexpr double P5 = constant(4.13813679705723846039e-08, 0x3E663769, 0x72BEA4D0);

// s_expm1.c (its ln2_hi, ln2_lo and invln2 equal e_exp.c's)
constexpr double ln2_hi = constant(6.93147180369123816490e-01, 0x3fe62e42, 0xfee00000);
constexpr double ln2_lo = constant(1.90821492927058770002e-10, 0x3dea39ef, 0x35793c76);
constexpr double Q1 = constant(-3.33333333333331316428e-02, 0xBFA11111, 0x111110F4);
constexpr double Q2 = constant(1.58730158725481460165e-03, 0x3F5A01A0, 0x19FE5585);
constexpr double Q3 = constant(-7.93650757867487942473e-05, 0xBF14CE19, 0x9EAADBB7);
constexpr double Q4 = constant(4.00821782732936239552e-06, 0x3ED0CFCA, 0x86E65239);
constexpr double Q5 = constant(-2.01099218183624371326e-07, 0xBE8AFDB7, 0x6E09C32D);

// e_log.c and s_log1p.c (log1p's Lp1..Lp7 equal log's Lg1..Lg7)
constexpr double two54 = constant(1.80143985094819840000e+16, 0x43500000, 0x00000000);
constexpr double Lg1 = constant(6.666666666666735130e-01, 0x3FE55555, 0x55555593);
constexpr double Lg2 = constant(3.999999999940941908e-01, 0x3FD99999, 0x9997FA04);
constexpr double Lg3 = constant(2.857142874366239149e-01, 0x3FD24924, 0x94229359);
constexpr double Lg4 = constant(2.222219843214978396e-01, 0x3FCC71C5, 0x1D8E78AF);
constexpr double Lg5 = constant(1.818357216161805012e-01, 0x3FC74664, 0x96CB03DE);
constexpr double Lg6 = constant(1.531383769920937332e-01, 0x3FC39A09, 0xD078C69F);
constexpr double Lg7 = constant(1.479819860511658591e-01, 0x3FC2F112, 0xDF3E5244);

// e_log10.c
constexpr double ivln10 = constant(4.34294481903251816668e-01, 0x3FDBCB7B, 0x1526E50E);
constexpr double log10_2hi = constant(3.01029995663611771306e-01, 0x3FD34413, 0x509F6000);
constexpr double log10_2lo = constant(3.69423907715893078616e-13, 0x3D59FEF3, 0x11F12B36);

// NOLINTEND(readability-identifier-naming)
/// fdlibm's -two54/zero: log(+-0) = -inf.
[[nodiscard]] double minus_infinity() noexcept { return -std::numeric_limits<double>::infinity(); }

/// fdlibm's (x-x)/zero and (x-x)/(x-x): log of a negative number is NaN.
[[nodiscard]] double invalid() noexcept { return std::numeric_limits<double>::quiet_NaN(); }

} // namespace

double exp(double x) noexcept {
    double hi_part = 0.0;
    double lo_part = 0.0;
    int k = 0;
    auto hx = static_cast<std::uint32_t>(hi(x));                 // fdlibm: high word of x
    const auto xsb = static_cast<std::size_t>((hx >> 31U) & 1U); // fdlibm: sign bit of x
    hx &= 0x7fffffffU;                                           // fdlibm: high word of |x|

    // fdlibm: filter out non-finite argument
    if (hx >= 0x40862E42U) {     // fdlibm: if |x|>=709.78...
        if (hx >= 0x7ff00000U) { // fdlibm: NaN, or exp(+-inf)={inf,0}
            if (((hx & 0xfffffU) | lo(x)) != 0) {
                return x + x;
            }
            return xsb == 0 ? x : 0.0;
        }
        if (x > o_threshold) {
            return huge * huge; // fdlibm: overflow
        }
        if (x < u_threshold) {
            return twom1000 * twom1000; // fdlibm: underflow
        }
    }

    // fdlibm: argument reduction
    if (hx > 0x3fd62e42U) {     // fdlibm: if  |x| > 0.5 ln2
        if (hx < 0x3FF0A2B2U) { // fdlibm: and |x| < 1.5 ln2
            hi_part = x - ln2HI[xsb];
            lo_part = ln2LO[xsb];
            k = 1 - static_cast<int>(xsb) - static_cast<int>(xsb);
        } else {
            k = static_cast<int>(invln2 * x + halF[xsb]);
            const auto t = static_cast<double>(k);
            hi_part = x - t * ln2HI[0]; // fdlibm: t*ln2HI is exact here
            lo_part = t * ln2LO[0];
        }
        x = hi_part - lo_part;
    } else if (hx < 0x3e300000U) { // fdlibm: when |x|<2**-28
        return one + x;            // fdlibm: huge+x>one always holds: trigger inexact
    } else {
        k = 0;
    }

    // fdlibm: x is now in primary range
    const double t = x * x;
    const double c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
    if (k == 0) {
        return one - ((x * c) / (c - 2.0) - x);
    }
    const double y = one - ((lo_part - (x * c) / (2.0 - c)) - hi_part);
    if (k >= -1021) {
        return add_hi(y, exponent(k)); // fdlibm: add k to y's exponent
    }
    return add_hi(y, exponent(k + 1000)) * twom1000; // fdlibm: add k to y's exponent
}

double expm1(double x) noexcept {
    double y = 0.0;
    double hi_part = 0.0;
    double lo_part = 0.0;
    double c = 0.0;
    double t = 0.0;
    int k = 0;
    auto hx = static_cast<std::uint32_t>(hi(x)); // fdlibm: high word of x
    const std::uint32_t xsb = hx & 0x80000000U;  // fdlibm: sign bit of x
    y = xsb == 0 ? x : -x;                       // fdlibm: y = |x|
    hx &= 0x7fffffffU;                           // fdlibm: high word of |x|

    // fdlibm: filter out huge and non-finite argument
    if (hx >= 0x4043687AU) {         // fdlibm: if |x|>=56*ln2
        if (hx >= 0x40862E42U) {     // fdlibm: if |x|>=709.78...
            if (hx >= 0x7ff00000U) { // fdlibm: NaN, or exp(+-inf)={inf,-1}
                if (((hx & 0xfffffU) | lo(x)) != 0) {
                    return x + x;
                }
                return xsb == 0 ? x : -1.0;
            }
            if (x > o_threshold) {
                return huge * huge; // fdlibm: overflow
            }
        }
        if (xsb != 0) {        // fdlibm: x < -56*ln2, return -1.0 with inexact
            return tiny - one; // fdlibm: x+tiny<0.0 always holds
        }
    }

    // fdlibm: argument reduction
    if (hx > 0x3fd62e42U) {     // fdlibm: if  |x| > 0.5 ln2
        if (hx < 0x3FF0A2B2U) { // fdlibm: and |x| < 1.5 ln2
            if (xsb == 0) {
                hi_part = x - ln2_hi;
                lo_part = ln2_lo;
                k = 1;
            } else {
                hi_part = x + ln2_hi;
                lo_part = -ln2_lo;
                k = -1;
            }
        } else {
            k = static_cast<int>(invln2 * x + (xsb == 0 ? 0.5 : -0.5));
            t = static_cast<double>(k);
            hi_part = x - t * ln2_hi; // fdlibm: t*ln2_hi is exact here
            lo_part = t * ln2_lo;
        }
        x = hi_part - lo_part;
        c = (hi_part - x) - lo_part;
    } else if (hx < 0x3c900000U) { // fdlibm: when |x|<2**-54, return x
        t = huge + x;              // fdlibm: return x with inexact flags when x!=0
        return x - (t - (huge + x));
    } else {
        k = 0;
    }

    // fdlibm: x is now in primary range
    const double hfx = 0.5 * x;
    const double hxs = x * hfx;
    const double r1 = one + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))));
    t = 3.0 - r1 * hfx;
    double e = hxs * ((r1 - t) / (6.0 - x * t));
    if (k == 0) {
        return x - (x * e - hxs); // fdlibm: c is 0
    }
    e = (x * (e - c) - c);
    e -= hxs;
    if (k == -1) {
        return 0.5 * (x - e) - 0.5;
    }
    if (k == 1) {
        if (x < -0.25) {
            return -2.0 * (e - (x + 0.5));
        }
        return one + 2.0 * (x - e);
    }
    if (k <= -2 || k > 56) { // fdlibm: suffice to return exp(x)-1
        y = one - (e - x);
        y = add_hi(y, exponent(k)); // fdlibm: add k to y's exponent
        return y - one;
    }
    if (k < 20) {
        t = from_words(0x3ff00000 - (0x200000 >> k), 0); // fdlibm: t=1-2^-k
        y = t - (e - x);
        y = add_hi(y, exponent(k)); // fdlibm: add k to y's exponent
    } else {
        t = from_words(exponent(0x3ff - k), 0); // fdlibm: 2^-k
        y = x - (e + t);
        y += one;
        y = add_hi(y, exponent(k)); // fdlibm: add k to y's exponent
    }
    return y;
}

double sinh(double x) noexcept {
    // e_sinh.c (Copyright (C) 1993 by Sun Microsystems, Inc., the notice above).
    constexpr double shuge = 1.0e307;
    const std::int32_t jx = hi(x); // fdlibm: high word of |x|
    const std::int32_t ix = jx & 0x7fffffff;

    if (ix >= 0x7ff00000) { // fdlibm: x is INF or NaN
        return x + x;
    }

    const double h = jx < 0 ? -0.5 : 0.5;
    // fdlibm: |x| in [0,22], return sign(x)*0.5*(E+E/(E+1)))
    if (ix < 0x40360000) {     // fdlibm: |x|<22
        if (ix < 0x3e300000) { // fdlibm: |x|<2**-28
            return x;          // fdlibm: shuge+x>one always holds: sinh(tiny) = tiny with inexact
        }
        const double t = expm1(std::fabs(x));
        if (ix < 0x3ff00000) {
            return h * (2.0 * t - t * t / (t + one));
        }
        return h * (t + t / (t + one));
    }

    // fdlibm: |x| in [22, log(maxdouble)] return 0.5*exp(|x|)
    if (ix < 0x40862E42) {
        return h * exp(std::fabs(x));
    }

    // fdlibm: |x| in [log(maxdouble), overflowthresold]
    const std::uint32_t lx = lo(x);
    if (ix < 0x408633CE || (ix == 0x408633ce && lx <= 0x8fb9f87dU)) {
        const double w = exp(0.5 * std::fabs(x));
        const double t = h * w;
        return t * w;
    }

    // fdlibm: |x| > overflowthresold, sinh(x) overflow
    return x * shuge;
}

double log(double x) noexcept {
    std::int32_t hx = hi(x);        // fdlibm: high word of x
    const std::uint32_t lx = lo(x); // fdlibm: low  word of x

    int k = 0;
    if (hx < 0x00100000) { // fdlibm: x < 2**-1022
        if ((static_cast<std::uint32_t>(hx & 0x7fffffff) | lx) == 0) {
            return minus_infinity(); // fdlibm: log(+-0)=-inf
        }
        if (hx < 0) {
            return invalid(); // fdlibm: log(-#) = NaN
        }
        k -= 54;
        x *= two54; // fdlibm: subnormal number, scale up x
        hx = hi(x); // fdlibm: high word of x
    }
    if (hx >= 0x7ff00000) {
        return x + x;
    }
    k += (hx >> 20) - 1023;
    hx &= 0x000fffff;
    std::int32_t i = (hx + 0x95f64) & 0x100000;
    x = with_hi(x, hx | (i ^ 0x3ff00000)); // fdlibm: normalize x or x/2
    k += (i >> 20);
    const double f = x - 1.0;
    if ((0x000fffff & (2 + hx)) < 3) { // fdlibm: |f| < 2**-20
        if (f == zero) {
            if (k == 0) {
                return zero;
            }
            const auto dk = static_cast<double>(k);
            return dk * ln2_hi + dk * ln2_lo;
        }
        const double r = f * f * (0.5 - 0.33333333333333333 * f);
        if (k == 0) {
            return f - r;
        }
        const auto dk = static_cast<double>(k);
        return dk * ln2_hi - ((r - dk * ln2_lo) - f);
    }
    const double s = f / (2.0 + f);
    const auto dk = static_cast<double>(k);
    const double z = s * s;
    i = hx - 0x6147a;
    const double w = z * z;
    const std::int32_t j = 0x6b851 - hx;
    const double t1 = w * (Lg2 + w * (Lg4 + w * Lg6));
    const double t2 = z * (Lg1 + w * (Lg3 + w * (Lg5 + w * Lg7)));
    i |= j;
    const double r = t2 + t1;
    if (i > 0) {
        const double hfsq = 0.5 * f * f;
        if (k == 0) {
            return f - (hfsq - s * (hfsq + r));
        }
        return dk * ln2_hi - ((hfsq - (s * (hfsq + r) + dk * ln2_lo)) - f);
    }
    if (k == 0) {
        return f - s * (f - r);
    }
    return dk * ln2_hi - ((s * (f - r) - dk * ln2_lo) - f);
}

double log10(double x) noexcept {
    std::int32_t hx = hi(x);        // fdlibm: high word of x
    const std::uint32_t lx = lo(x); // fdlibm: low word of x

    int k = 0;
    if (hx < 0x00100000) { // fdlibm: x < 2**-1022
        if ((static_cast<std::uint32_t>(hx & 0x7fffffff) | lx) == 0) {
            return minus_infinity(); // fdlibm: log(+-0)=-inf
        }
        if (hx < 0) {
            return invalid(); // fdlibm: log(-#) = NaN
        }
        k -= 54;
        x *= two54; // fdlibm: subnormal number, scale up x
        hx = hi(x); // fdlibm: high word of x
    }
    if (hx >= 0x7ff00000) {
        return x + x;
    }
    k += (hx >> 20) - 1023;
    const auto i = static_cast<std::int32_t>((static_cast<std::uint32_t>(k) & 0x80000000U) >> 31U);
    hx = (hx & 0x000fffff) | exponent(0x3ff - i);
    const auto y = static_cast<double>(k + i);
    x = with_hi(x, hx);
    const double z = y * log10_2lo + ivln10 * log(x);
    return z + y * log10_2hi;
}

double log1p(double x) noexcept {
    double f = 0.0;
    double c = 0.0;
    const std::int32_t hx = hi(x); // fdlibm: high word of x
    const std::int32_t ax = hx & 0x7fffffff;
    std::int32_t hu = 0;

    int k = 1;
    if (hx < 0x3FDA827A) {      // fdlibm: x < 0.41422
        if (ax >= 0x3ff00000) { // fdlibm: x <= -1.0
            if (x == -1.0) {
                return minus_infinity(); // fdlibm: -two54/zero, -inf (its comment says +inf)
            }
            return invalid(); // fdlibm: log1p(x<-1)=NaN
        }
        if (ax < 0x3e200000) {     // fdlibm: |x| < 2**-29
            if (ax < 0x3c900000) { // fdlibm: |x| < 2**-54 (two54+x>zero always holds)
                return x;
            }
            return x - x * x * 0.5;
        }
        constexpr auto minus_0_2929 = static_cast<std::int32_t>(0xbfd2bec3U); // the high word of -0.2929
        if (hx > 0 || hx <= minus_0_2929) {                                   // fdlibm: -0.2929<x<0.41422
            k = 0;
            f = x;
            hu = 1;
        }
    }
    if (hx >= 0x7ff00000) {
        return x + x;
    }
    if (k != 0) {
        double u = 0.0;
        if (hx < 0x43400000) {
            u = 1.0 + x;
            hu = hi(u); // fdlibm: high word of u
            k = (hu >> 20) - 1023;
            c = (k > 0) ? 1.0 - (u - x) : x - (u - 1.0); // fdlibm: correction term
            c /= u;
        } else {
            u = x;
            hu = hi(u); // fdlibm: high word of u
            k = (hu >> 20) - 1023;
            c = 0;
        }
        hu &= 0x000fffff;
        if (hu < 0x6a09e) {
            u = with_hi(u, hu | 0x3ff00000); // fdlibm: normalize u
        } else {
            k += 1;
            u = with_hi(u, hu | 0x3fe00000); // fdlibm: normalize u/2
            hu = (0x00100000 - hu) >> 2;
        }
        f = u - 1.0;
    }
    const double hfsq = 0.5 * f * f;
    if (hu == 0) { // fdlibm: |f| < 2**-20
        if (f == zero) {
            if (k == 0) {
                return zero;
            }
            c += k * ln2_lo;
            return k * ln2_hi + c;
        }
        const double r = hfsq * (1.0 - 0.66666666666666666 * f);
        if (k == 0) {
            return f - r;
        }
        return k * ln2_hi - ((r - (k * ln2_lo + c)) - f);
    }
    const double s = f / (2.0 + f);
    const double z = s * s;
    const double r = z * (Lg1 + z * (Lg2 + z * (Lg3 + z * (Lg4 + z * (Lg5 + z * (Lg6 + z * Lg7))))));
    if (k == 0) {
        return f - (hfsq - s * (hfsq + r));
    }
    return k * ln2_hi - ((hfsq - (s * (hfsq + r) + (k * ln2_lo + c))) - f);
}

} // namespace atlas::pymath::strict
