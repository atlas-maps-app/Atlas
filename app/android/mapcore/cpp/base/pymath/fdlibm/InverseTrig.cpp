// fdlibm 5.3's e_asin.c, e_acos.c, s_atan.c and e_atan2.c: StrictMath.asin, acos, atan and atan2.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
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

using fdlibm::constant;
using fdlibm::hi;
using fdlibm::lo;
using fdlibm::with_lo;

// NOLINTBEGIN(readability-identifier-naming): fdlibm's own names, so each constant reads against its C source
constexpr double one = 1.0;
constexpr double pi = constant(3.14159265358979311600e+00, 0x400921FB, 0x54442D18);
constexpr double pio2_hi = constant(1.57079632679489655800e+00, 0x3FF921FB, 0x54442D18);
constexpr double pio2_lo = constant(6.12323399573676603587e-17, 0x3C91A626, 0x33145C07);
constexpr double pio4_hi = constant(7.85398163397448278999e-01, 0x3FE921FB, 0x54442D18);
// fdlibm: coefficients for R(x^2)
constexpr double pS0 = constant(1.66666666666666657415e-01, 0x3FC55555, 0x55555555);
constexpr double pS1 = constant(-3.25565818622400915405e-01, 0xBFD4D612, 0x03EB6F7D);
constexpr double pS2 = constant(2.01212532134862925881e-01, 0x3FC9C155, 0x0E884455);
constexpr double pS3 = constant(-4.00555345006794114027e-02, 0xBFA48228, 0xB5688F3B);
constexpr double pS4 = constant(7.91534994289814532176e-04, 0x3F49EFE0, 0x7501B288);
constexpr double pS5 = constant(3.47933107596021167570e-05, 0x3F023DE1, 0x0DFDF709);
constexpr double qS1 = constant(-2.40339491173441421878e+00, 0xC0033A27, 0x1C8A2D4B);
constexpr double qS2 = constant(2.02094576023350569471e+00, 0x40002AE5, 0x9C598AC8);
constexpr double qS3 = constant(-6.88283971605453293030e-01, 0xBFE6066C, 0x1B8D0159);
constexpr double qS4 = constant(7.70381505559019352791e-02, 0x3FB3B8C5, 0xB12E9282);

// s_atan.c
constexpr std::array<double, 4> atanhi{
    constant(4.63647609000806093515e-01, 0x3FDDAC67, 0x0561BB4F), // fdlibm: atan(0.5)hi
    constant(7.85398163397448278999e-01, 0x3FE921FB, 0x54442D18), // fdlibm: atan(1.0)hi
    constant(9.82793723247329054082e-01, 0x3FEF730B, 0xD281F69B), // fdlibm: atan(1.5)hi
    constant(1.57079632679489655800e+00, 0x3FF921FB, 0x54442D18), // fdlibm: atan(inf)hi
};
constexpr std::array<double, 4> atanlo{
    constant(2.26987774529616870924e-17, 0x3C7A2B7F, 0x222F65E2), // fdlibm: atan(0.5)lo
    constant(3.06161699786838301793e-17, 0x3C81A626, 0x33145C07), // fdlibm: atan(1.0)lo
    constant(1.39033110312309984516e-17, 0x3C700788, 0x7AF0CBBD), // fdlibm: atan(1.5)lo
    constant(6.12323399573676603587e-17, 0x3C91A626, 0x33145C07), // fdlibm: atan(inf)lo
};
constexpr std::array<double, 11> aT{
    constant(3.33333333333329318027e-01, 0x3FD55555, 0x5555550D),
    constant(-1.99999999998764832476e-01, 0xBFC99999, 0x9998EBC4),
    constant(1.42857142725034663711e-01, 0x3FC24924, 0x920083FF),
    constant(-1.11111104054623557880e-01, 0xBFBC71C6, 0xFE231671),
    constant(9.09088713343650656196e-02, 0x3FB745CD, 0xC54C206E),
    constant(-7.69187620504482999495e-02, 0xBFB3B0F2, 0xAF749A6D),
    constant(6.66107313738753120669e-02, 0x3FB10D66, 0xA0D03D51),
    constant(-5.83357013379057348645e-02, 0xBFADDE2D, 0x52DEFD9A),
    constant(4.97687799461593236017e-02, 0x3FA97B4B, 0x24760DEB),
    constant(-3.65315727442169155270e-02, 0xBFA2B444, 0x2C6A6C2F),
    constant(1.62858201153657823623e-02, 0x3F90AD3A, 0xE322DA11),
};

// e_atan2.c
constexpr double tiny = 1.0e-300;
constexpr double zero = 0.0;
constexpr double pi_o_4 = constant(7.8539816339744827900E-01, 0x3FE921FB, 0x54442D18);
constexpr double pi_o_2 = constant(1.5707963267948965580E+00, 0x3FF921FB, 0x54442D18);
constexpr double pi_lo = constant(1.2246467991473531772E-16, 0x3CA1A626, 0x33145C07);

// NOLINTEND(readability-identifier-naming)
/// fdlibm's (x-x)/(x-x) for an argument outside the domain: a quiet NaN.
[[nodiscard]] double invalid() noexcept { return std::numeric_limits<double>::quiet_NaN(); }

/// The polynomial asin and acos share: p/q of R(z).
[[nodiscard]] double p_of(double z) noexcept {
    return z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
}
[[nodiscard]] double q_of(double z) noexcept { return one + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4))); }

} // namespace

double asin(double x) noexcept {
    const std::int32_t hx = hi(x);
    const std::int32_t ix = hx & 0x7fffffff;
    if (ix >= 0x3ff00000) { // fdlibm: |x|>= 1
        if ((static_cast<std::uint32_t>(ix - 0x3ff00000) | lo(x)) == 0) {
            return x * pio2_hi + x * pio2_lo; // fdlibm: asin(1)=+-pi/2 with inexact
        }
        return invalid(); // fdlibm: asin(|x|>1) is NaN
    }
    if (ix < 0x3fe00000) {     // fdlibm: |x|<0.5
        if (ix < 0x3e400000) { // fdlibm: if |x| < 2**-27
            return x;          // fdlibm: huge+x>one always holds: return x with inexact if x!=0
        }
        const double t = x * x;
        const double p = p_of(t);
        const double q = q_of(t);
        const double w = p / q;
        return x + x * w;
    }
    // fdlibm: 1> |x|>= 0.5
    double w = one - std::fabs(x);
    double t = w * 0.5;
    double p = p_of(t);
    double q = q_of(t);
    const double s = std::sqrt(t);
    if (ix >= 0x3FEF3333) { // fdlibm: if |x| > 0.975
        w = p / q;
        t = pio2_hi - (2.0 * (s + s * w) - pio2_lo);
    } else {
        w = with_lo(s, 0);
        const double c = (t - w * w) / (s + w);
        const double r = p / q;
        p = 2.0 * s * r - (pio2_lo - 2.0 * c);
        q = pio4_hi - 2.0 * w;
        t = pio4_hi - (p - q);
    }
    return hx > 0 ? t : -t;
}

double acos(double x) noexcept {
    const std::int32_t hx = hi(x);
    const std::int32_t ix = hx & 0x7fffffff;
    if (ix >= 0x3ff00000) {                                               // fdlibm: |x| >= 1
        if ((static_cast<std::uint32_t>(ix - 0x3ff00000) | lo(x)) == 0) { // fdlibm: |x|==1
            if (hx > 0) {
                return 0.0; // fdlibm: acos(1) = 0
            }
            return pi + 2.0 * pio2_lo; // fdlibm: acos(-1)= pi
        }
        return invalid(); // fdlibm: acos(|x|>1) is NaN
    }
    if (ix < 0x3fe00000) {      // fdlibm: |x| < 0.5
        if (ix <= 0x3c600000) { // fdlibm: if|x|<2**-57
            return pio2_hi + pio2_lo;
        }
        const double z = x * x;
        const double p = p_of(z);
        const double q = q_of(z);
        const double r = p / q;
        return pio2_hi - (x - (pio2_lo - x * r));
    }
    if (hx < 0) { // fdlibm: x < -0.5
        const double z = (one + x) * 0.5;
        const double p = p_of(z);
        const double q = q_of(z);
        const double s = std::sqrt(z);
        const double r = p / q;
        const double w = r * s - pio2_lo;
        return pi - 2.0 * (s + w);
    }
    // fdlibm: x > 0.5
    const double z = (one - x) * 0.5;
    const double s = std::sqrt(z);
    const double df = with_lo(s, 0);
    const double c = (z - df * df) / (s + df);
    const double p = p_of(z);
    const double q = q_of(z);
    const double r = p / q;
    const double w = r * s + c;
    return 2.0 * (df + w);
}

double atan(double x) noexcept {
    const std::int32_t hx = hi(x);
    const std::int32_t ix = hx & 0x7fffffff;
    int id = -1;
    if (ix >= 0x44100000) { // fdlibm: if |x| >= 2^66
        if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lo(x) != 0)) {
            return x + x; // fdlibm: NaN
        }
        if (hx > 0) {
            return atanhi[3] + atanlo[3];
        }
        return -atanhi[3] - atanlo[3];
    }
    if (ix < 0x3fdc0000) {     // fdlibm: |x| < 0.4375
        if (ix < 0x3e200000) { // fdlibm: |x| < 2^-29
            return x;          // fdlibm: huge+x>one always holds: raise inexact
        }
        id = -1;
    } else {
        x = std::fabs(x);
        if (ix < 0x3ff30000) {     // fdlibm: |x| < 1.1875
            if (ix < 0x3fe60000) { // fdlibm: 7/16 <=|x|<11/16
                id = 0;
                x = (2.0 * x - one) / (2.0 + x);
            } else { // fdlibm: 11/16<=|x|< 19/16
                id = 1;
                x = (x - one) / (x + one);
            }
        } else {
            if (ix < 0x40038000) { // fdlibm: |x| < 2.4375
                id = 2;
                x = (x - 1.5) / (one + 1.5 * x);
            } else { // fdlibm: 2.4375 <= |x| < 2^66
                id = 3;
                x = -1.0 / x;
            }
        }
    }
    // fdlibm: end of argument reduction
    double z = x * x;
    const double w = z * z;
    // fdlibm: break sum from i=0 to 10 aT[i]z**(i+1) into odd and even poly
    const double s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))));
    const double s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))));
    if (id < 0) {
        return x - x * (s1 + s2);
    }
    const auto i = static_cast<std::size_t>(id);
    z = atanhi[i] - ((x * (s1 + s2) - atanlo[i]) - x);
    return hx < 0 ? -z : z;
}

double atan2(double y, double x) noexcept {
    const std::int32_t hx = hi(x);
    const std::int32_t ix = hx & 0x7fffffff;
    const std::uint32_t lx = lo(x);
    const std::int32_t hy = hi(y);
    const std::int32_t iy = hy & 0x7fffffff;
    const std::uint32_t ly = lo(y);
    // fdlibm: x or y is NaN ((lx|-lx)>>31 is 1 when lx is not zero)
    if ((static_cast<std::uint32_t>(ix) | ((lx | (0U - lx)) >> 31U)) > 0x7ff00000U ||
        (static_cast<std::uint32_t>(iy) | ((ly | (0U - ly)) >> 31U)) > 0x7ff00000U) {
        return x + y;
    }
    if (((static_cast<std::uint32_t>(hx) - 0x3ff00000U) | lx) == 0) { // wrapping, as the C's int does
        return atan(y);                                               // fdlibm: x=1.0
    }
    const std::int32_t m = ((hy >> 31) & 1) | ((hx >> 30) & 2); // fdlibm: 2*sign(x)+sign(y)

    // fdlibm: when y = 0
    if ((static_cast<std::uint32_t>(iy) | ly) == 0) {
        switch (m) {
        case 0:
        case 1: return y;           // fdlibm: atan(+-0,+anything)=+-0
        case 2: return pi + tiny;   // fdlibm: atan(+0,-anything) = pi
        default: return -pi - tiny; // fdlibm: atan(-0,-anything) =-pi
        }
    }
    // fdlibm: when x = 0
    if ((static_cast<std::uint32_t>(ix) | lx) == 0) {
        return hy < 0 ? -pi_o_2 - tiny : pi_o_2 + tiny;
    }
    // fdlibm: when x is INF
    if (ix == 0x7ff00000) {
        if (iy == 0x7ff00000) {
            switch (m) {
            case 0: return pi_o_4 + tiny;         // fdlibm: atan(+INF,+INF)
            case 1: return -pi_o_4 - tiny;        // fdlibm: atan(-INF,+INF)
            case 2: return 3.0 * pi_o_4 + tiny;   // fdlibm: atan(+INF,-INF)
            default: return -3.0 * pi_o_4 - tiny; // fdlibm: atan(-INF,-INF)
            }
        }
        switch (m) {
        case 0: return zero;        // fdlibm: atan(+...,+INF)
        case 1: return -zero;       // fdlibm: atan(-...,+INF)
        case 2: return pi + tiny;   // fdlibm: atan(+...,-INF)
        default: return -pi - tiny; // fdlibm: atan(-...,-INF)
        }
    }
    // fdlibm: when y is INF
    if (iy == 0x7ff00000) {
        return hy < 0 ? -pi_o_2 - tiny : pi_o_2 + tiny;
    }

    // fdlibm: compute y/x
    double z = 0.0;
    const std::int32_t k = (iy - ix) >> 20;
    if (k > 60) {
        z = pi_o_2 + 0.5 * pi_lo; // fdlibm: |y/x| >  2**60
    } else if (hx < 0 && k < -60) {
        z = 0.0; // fdlibm: |y|/x < -2**60
    } else {
        z = atan(std::fabs(y / x)); // fdlibm: safe to do y/x
    }
    switch (m) {
    case 0: return z;                                     // fdlibm: atan(+,+)
    case 1: return fdlibm::with_hi(z, hi(z) ^ INT32_MIN); // fdlibm: atan(-,+): the sign bit flipped
    case 2: return pi - (z - pi_lo);                      // fdlibm: atan(+,-)
    default: return (z - pi_lo) - pi;                     // fdlibm: atan(-,-)
    }
}

} // namespace atlas::pymath::strict
