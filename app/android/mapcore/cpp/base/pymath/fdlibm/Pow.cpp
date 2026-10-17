// fdlibm 5.3's e_pow.c: StrictMath.pow.
//
// ====================================================
// Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
//
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================
//
// Ported to C++ for Atlas (plan-vector K3b). Comments marked "fdlibm:" are the original's.
//
// The pow Java defines (plan-vector T3's "the JDK's fix"): fdlibm 5.3 is the release whose readme lists the fix
// of 5.2's wrong results for x very close to -1 and y very large (the sign s now kept in the |y| > 2^31 branch), and
// Java's StrictMath.pow (FdLibm.Pow) computes the same bits as this C, which the parity tests check over the edges of
// every branch. The JDK's port differs only in form: it tests y == 0.5 as sqrt(x + 0.0) for every x >= -MAX_VALUE (NaN
// for negative x, as here), and drops the |y| > 2^64 shortcut, whose results its general path reproduces.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/fdlibm/Fdlibm.hpp"
#include "base/pymath/fdlibm/Words.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

namespace atlas::pymath::strict {

namespace {

using fdlibm::constant;
using fdlibm::exponent;
using fdlibm::from_words;
using fdlibm::hi;
using fdlibm::lo;
using fdlibm::with_hi;
using fdlibm::with_lo;

// NOLINTBEGIN(readability-identifier-naming): fdlibm's own names, so each constant reads against its C source
constexpr std::array<double, 2> bp{1.0, 1.5};
constexpr std::array<double, 2> dp_h{0.0, constant(5.84962487220764160156e-01, 0x3FE2B803, 0x40000000)};
constexpr std::array<double, 2> dp_l{0.0, constant(1.35003920212974897128e-08, 0x3E4CFDEB, 0x43CFD006)};
constexpr double zero = 0.0;
constexpr double one = 1.0;
constexpr double two = 2.0;
constexpr double two53 = constant(9007199254740992.0, 0x43400000, 0x00000000);
constexpr double huge = 1.0e300;
constexpr double tiny = 1.0e-300;
// fdlibm: poly coefs for (3/2)*(log(x)-2s-2/3*s**3
constexpr double L1 = constant(5.99999999999994648725e-01, 0x3FE33333, 0x33333303);
constexpr double L2 = constant(4.28571428578550184252e-01, 0x3FDB6DB6, 0xDB6FABFF);
constexpr double L3 = constant(3.33333329818377432918e-01, 0x3FD55555, 0x518F264D);
constexpr double L4 = constant(2.72728123808534006489e-01, 0x3FD17460, 0xA91D4101);
constexpr double L5 = constant(2.30660745775561754067e-01, 0x3FCD864A, 0x93C9DB65);
constexpr double L6 = constant(2.06975017800338417784e-01, 0x3FCA7E28, 0x4A454EEF);
constexpr double P1 = constant(1.66666666666666019037e-01, 0x3FC55555, 0x5555553E);
constexpr double P2 = constant(-2.77777777770155933842e-03, 0xBF66C16C, 0x16BEBD93);
constexpr double P3 = constant(6.61375632143793436117e-05, 0x3F11566A, 0xAF25DE2C);
constexpr double P4 = constant(-1.65339022054652515390e-06, 0xBEBBBD41, 0xC5D26BF1);
constexpr double P5 = constant(4.13813679705723846039e-08, 0x3E663769, 0x72BEA4D0);
constexpr double lg2 = constant(6.93147180559945286227e-01, 0x3FE62E42, 0xFEFA39EF);
constexpr double lg2_h = constant(6.93147182464599609375e-01, 0x3FE62E43, 0x00000000);
constexpr double lg2_l = constant(-1.90465429995776804525e-09, 0xBE205C61, 0x0CA86C39);
constexpr double ovt = 8.0085662595372944372e-0017;                                 // fdlibm: -(1024-log2(ovfl+.5ulp))
constexpr double cp = constant(9.61796693925975554329e-01, 0x3FEEC709, 0xDC3A03FD); // fdlibm: 2/(3ln2)
constexpr double cp_h = constant(9.61796700954437255859e-01, 0x3FEEC709, 0xE0000000);    // fdlibm: (float)cp
constexpr double cp_l = constant(-7.02846165095275826516e-09, 0xBE3E2FE0, 0x145B01F5);   // fdlibm: tail of cp_h
constexpr double ivln2 = constant(1.44269504088896338700e+00, 0x3FF71547, 0x652B82FE);   // fdlibm: 1/ln2
constexpr double ivln2_h = constant(1.44269502162933349609e+00, 0x3FF71547, 0x60000000); // fdlibm: 24b 1/ln2
constexpr double ivln2_l = constant(1.92596299112661746887e-08, 0x3E54AE0B, 0xF85DDF44); // fdlibm: 1/ln2 tail

// NOLINTEND(readability-identifier-naming)
/// fdlibm's (x-x)/(x-x): a quiet NaN.
[[nodiscard]] double invalid() noexcept { return std::numeric_limits<double>::quiet_NaN(); }

} // namespace

double pow(double x, double y) noexcept {
    const std::int32_t hx = hi(x);
    const std::uint32_t lx = lo(x);
    const std::int32_t hy = hi(y);
    const std::uint32_t ly = lo(y);
    std::int32_t ix = hx & 0x7fffffff;
    const std::int32_t iy = hy & 0x7fffffff;

    // fdlibm: y==zero: x**0 = 1
    if ((static_cast<std::uint32_t>(iy) | ly) == 0) {
        return one;
    }

    // fdlibm: +-NaN return x+y
    if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lx != 0) || iy > 0x7ff00000 || (iy == 0x7ff00000 && ly != 0)) {
        return x + y;
    }

    // fdlibm: determine if y is an odd int when x < 0
    // yisint = 0 ... y is not an integer, 1 ... y is an odd int, 2 ... y is an even int
    int yisint = 0;
    if (hx < 0) {
        if (iy >= 0x43400000) {
            yisint = 2; // fdlibm: even integer y
        } else if (iy >= 0x3ff00000) {
            const int k = (iy >> 20) - 0x3ff; // fdlibm: exponent
            if (k > 20) {
                const std::uint32_t j = ly >> static_cast<unsigned>(52 - k);
                if ((j << static_cast<unsigned>(52 - k)) == ly) {
                    yisint = 2 - static_cast<int>(j & 1U);
                }
            } else if (ly == 0) {
                const std::int32_t j = iy >> (20 - k);
                if ((j << (20 - k)) == iy) {
                    yisint = 2 - (j & 1);
                }
            }
        }
    }

    // fdlibm: special value of y
    if (ly == 0) {
        if (iy == 0x7ff00000) {                                            // fdlibm: y is +-inf
            if ((static_cast<std::uint32_t>(ix - 0x3ff00000) | lx) == 0) { // fdlibm: inf**+-1 is NaN
                return y - y; // NOLINT(misc-redundant-expression): fdlibm: inf**+-1 is NaN
            }
            if (ix >= 0x3ff00000) { // fdlibm: (|x|>1)**+-inf = inf,0
                return hy >= 0 ? y : zero;
            }
            return hy < 0 ? -y : zero; // fdlibm: (|x|<1)**-,+inf = inf,0
        }
        if (iy == 0x3ff00000) { // fdlibm: y is  +-1
            if (hy < 0) {
                return one / x;
            }
            return x;
        }
        if (hy == 0x40000000) {
            return x * x; // fdlibm: y is  2
        }
        if (hy == 0x3fe00000) { // fdlibm: y is  0.5
            if (hx >= 0) {      // fdlibm: x >= +0
                return std::sqrt(x);
            }
        }
    }

    double ax = std::fabs(x);
    // fdlibm: special value of x
    if (lx == 0) {
        if (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) {
            double z = ax; // fdlibm: x is +-0,+-inf,+-1
            if (hy < 0) {
                z = one / z; // fdlibm: z = (1/|x|)
            }
            if (hx < 0) {
                if ((static_cast<std::uint32_t>(ix - 0x3ff00000) | static_cast<std::uint32_t>(yisint)) == 0) {
                    z = invalid(); // fdlibm: (-1)**non-int is NaN
                } else if (yisint == 1) {
                    z = -z; // fdlibm: (x<0)**odd = -(|x|**odd)
                }
            }
            return z;
        }
    }

    int n = (hx >> 31) + 1;

    // fdlibm: (x<0)**(non-int) is NaN
    if ((n | yisint) == 0) {
        return invalid();
    }

    double s = one; // fdlibm: s (sign of result -ve**odd) = -1 else = 1
    if ((n | (yisint - 1)) == 0) {
        s = -one; // fdlibm: (-ve)**(odd int)
    }

    double t1 = 0.0;
    double t2 = 0.0;
    // fdlibm: |y| is huge
    if (iy > 0x41e00000) {     // fdlibm: if |y| > 2**31
        if (iy > 0x43f00000) { // fdlibm: if |y| > 2**64, must o/uflow
            if (ix <= 0x3fefffff) {
                return hy < 0 ? huge * huge : tiny * tiny;
            }
            if (ix >= 0x3ff00000) {
                return hy > 0 ? huge * huge : tiny * tiny;
            }
        }
        // fdlibm: over/underflow if x is not close to one
        if (ix < 0x3fefffff) {
            return hy < 0 ? s * huge * huge : s * tiny * tiny;
        }
        if (ix > 0x3ff00000) {
            return hy > 0 ? s * huge * huge : s * tiny * tiny;
        }
        // fdlibm: now |1-x| is tiny <= 2**-20, suffice to compute log(x) by x-x^2/2+x^3/3-x^4/4
        const double t = ax - one; // fdlibm: t has 20 trailing zeros
        const double w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
        const double u = ivln2_h * t; // fdlibm: ivln2_h has 21 sig. bits
        const double v = t * ivln2_l - w * ivln2;
        t1 = with_lo(u + v, 0);
        t2 = v - (t1 - u);
    } else {
        n = 0;
        // fdlibm: take care subnormal number
        if (ix < 0x00100000) {
            ax *= two53;
            n -= 53;
            ix = hi(ax);
        }
        n += ((ix) >> 20) - 0x3ff;
        const std::int32_t j = ix & 0x000fffff;
        // fdlibm: determine interval
        ix = j | 0x3ff00000; // fdlibm: normalize ix
        std::size_t k = 0;
        if (j <= 0x3988E) {
            k = 0; // fdlibm: |x|<sqrt(3/2)
        } else if (j < 0xBB67A) {
            k = 1; // fdlibm: |x|<sqrt(3)
        } else {
            k = 0;
            n += 1;
            ix -= 0x00100000;
        }
        ax = with_hi(ax, ix);

        // fdlibm: compute ss = s_h+s_l = (x-1)/(x+1) or (x-1.5)/(x+1.5)
        double u = ax - bp[k]; // fdlibm: bp[0]=1.0, bp[1]=1.5
        double v = one / (ax + bp[k]);
        const double ss = u * v;
        const double s_h = with_lo(ss, 0);
        // fdlibm: t_h=ax+bp[k] High
        double t_h = from_words(((ix >> 1) | 0x20000000) + 0x00080000 + (static_cast<std::int32_t>(k) << 18), 0);
        double t_l = ax - (t_h - bp[k]);
        const double s_l = v * ((u - s_h * t_h) - s_h * t_l);
        // fdlibm: compute log(ax)
        double s2 = ss * ss;
        double r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
        r += s_l * (s_h + ss);
        s2 = s_h * s_h;
        t_h = with_lo(3.0 + s2 + r, 0);
        t_l = r - ((t_h - 3.0) - s2);
        // fdlibm: u+v = ss*(1+...)
        u = s_h * t_h;
        v = s_l * t_h + t_l * ss;
        // fdlibm: 2/(3log2)*(ss+...)
        const double p_h = with_lo(u + v, 0);
        const double p_l = v - (p_h - u);
        const double z_h = cp_h * p_h; // fdlibm: cp_h+cp_l = 2/(3*log2)
        const double z_l = cp_l * p_h + p_l * cp + dp_l[k];
        // fdlibm: log2(ax) = (ss+..)*2/(3*log2) = n + dp_h + z_h + z_l
        const auto t = static_cast<double>(n);
        t1 = with_lo(((z_h + z_l) + dp_h[k]) + t, 0);
        t2 = z_l - (((t1 - t) - dp_h[k]) - z_h);
    }

    // fdlibm: split up y into y1+y2 and compute (y1+y2)*(t1+t2)
    const double y1 = with_lo(y, 0);
    const double p_l = (y - y1) * t1 + y * t2;
    double p_h = y1 * t1;
    double z = p_l + p_h;
    std::int32_t j = hi(z);
    const std::uint32_t i = lo(z);
    if (j >= 0x40900000) {                                           // fdlibm: z >= 1024
        if ((static_cast<std::uint32_t>(j - 0x40900000) | i) != 0) { // fdlibm: if z > 1024
            return s * huge * huge;                                  // fdlibm: overflow
        }
        if (p_l + ovt > z - p_h) {
            return s * huge * huge; // fdlibm: overflow
        }
    } else if ((j & 0x7fffffff) >= 0x4090cc00) {                        // fdlibm: z <= -1075
        if (((static_cast<std::uint32_t>(j) - 0xc090cc00U) | i) != 0) { // fdlibm: z < -1075
            return s * tiny * tiny;                                     // fdlibm: underflow
        }
        if (p_l <= z - p_h) {
            return s * tiny * tiny; // fdlibm: underflow
        }
    }
    // fdlibm: compute 2**(p_h+p_l)
    const std::int32_t abs_j = j & 0x7fffffff;
    std::int32_t k = (abs_j >> 20) - 0x3ff;
    n = 0;
    if (abs_j > 0x3fe00000) { // fdlibm: if |z| > 0.5, set n = [z+0.5]
        n = j + (0x00100000 >> (k + 1));
        k = ((n & 0x7fffffff) >> 20) - 0x3ff; // fdlibm: new k for n
        const double t = from_words(n & ~(0x000fffff >> k), 0);
        n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
        if (j < 0) {
            n = -n;
        }
        p_h -= t;
    }
    double t = with_lo(p_l + p_h, 0);
    const double u = t * lg2_h;
    const double v = (p_l - (t - p_h)) * lg2 + t * lg2_l;
    z = u + v;
    const double w = v - (z - u);
    t = z * z;
    const double t1_poly = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
    const double r = (z * t1_poly) / (t1_poly - two) - (w + z * w);
    z = one - (r - z);
    j = hi(z);
    j = static_cast<std::int32_t>(static_cast<std::uint32_t>(j) + static_cast<std::uint32_t>(exponent(n)));
    if ((j >> 20) <= 0) {
        z = fdlibm::scalbn(z, n); // fdlibm: subnormal output
    } else {
        z = fdlibm::add_hi(z, exponent(n));
    }
    return s * z;
}

} // namespace atlas::pymath::strict
