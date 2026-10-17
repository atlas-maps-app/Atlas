// fdlibm 5.3's trigonometric kernels and argument reduction: k_sin.c, k_cos.c, k_tan.c, e_rem_pio2.c, k_rem_pio2.c.
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
// Ported to C++ for Atlas (plan-vector K3b): the same operations in the same order, the same constants (checked against
// their documented words), the words read with std::bit_cast. Comments marked "fdlibm:" are the original's.
#include "base/pymath/fdlibm/Fdlibm.hpp"
#include "base/pymath/fdlibm/Words.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <span>

namespace atlas::pymath::fdlibm {

namespace detail {
// Never called: base/pymath/fdlibm/Words.hpp names it only to stop a compilation whose constant is mistyped.
void constant_differs_from_its_words() noexcept {}
} // namespace detail

namespace {

// NOLINTBEGIN(readability-identifier-naming): fdlibm's own names, so each constant reads against its C source
constexpr double one = 1.0;
constexpr double half = constant(5.00000000000000000000e-01, 0x3FE00000, 0x00000000);

// k_sin.c
constexpr double S1 = constant(-1.66666666666666324348e-01, 0xBFC55555, 0x55555549);
constexpr double S2 = constant(8.33333333332248946124e-03, 0x3F811111, 0x1110F8A6);
constexpr double S3 = constant(-1.98412698298579493134e-04, 0xBF2A01A0, 0x19C161D5);
constexpr double S4 = constant(2.75573137070700676789e-06, 0x3EC71DE3, 0x57B1FE7D);
constexpr double S5 = constant(-2.50507602534068634195e-08, 0xBE5AE5E6, 0x8A2B9CEB);
constexpr double S6 = constant(1.58969099521155010221e-10, 0x3DE5D93A, 0x5ACFD57C);

// k_cos.c
constexpr double C1 = constant(4.16666666666666019037e-02, 0x3FA55555, 0x5555554C);
constexpr double C2 = constant(-1.38888888888741095749e-03, 0xBF56C16C, 0x16C15177);
constexpr double C3 = constant(2.48015872894767294178e-05, 0x3EFA01A0, 0x19CB1590);
constexpr double C4 = constant(-2.75573143513906633035e-07, 0xBE927E4F, 0x809C52AD);
constexpr double C5 = constant(2.08757232129817482790e-09, 0x3E21EE9E, 0xBDB4B1C4);
constexpr double C6 = constant(-1.13596475577881948265e-11, 0xBDA8FAE9, 0xBE8838D4);

// k_tan.c: T[0..12], then one, pio4 and pio4lo.
constexpr std::array<double, 13> T{
    constant(3.33333333333334091986e-01, 0x3FD55555, 0x55555563),
    constant(1.33333333333201242699e-01, 0x3FC11111, 0x1110FE7A),
    constant(5.39682539762260521377e-02, 0x3FABA1BA, 0x1BB341FE),
    constant(2.18694882948595424599e-02, 0x3F9664F4, 0x8406D637),
    constant(8.86323982359930005737e-03, 0x3F8226E3, 0xE96E8493),
    constant(3.59207910759131235356e-03, 0x3F6D6D22, 0xC9560328),
    constant(1.45620945432529025516e-03, 0x3F57DBC8, 0xFEE08315),
    constant(5.88041240820264096874e-04, 0x3F4344D8, 0xF2F26501),
    constant(2.46463134818469906812e-04, 0x3F3026F7, 0x1A8D1068),
    constant(7.81794442939557092300e-05, 0x3F147E88, 0xA03792A6),
    constant(7.14072491382608190305e-05, 0x3F12B80F, 0x32F0A7E9),
    constant(-1.85586374855275456654e-05, 0xBEF375CB, 0xDB605373),
    constant(2.59073051863633712884e-05, 0x3EFB2A70, 0x74BF7AD4),
};
constexpr double pio4 = constant(7.85398163397448278999e-01, 0x3FE921FB, 0x54442D18);
constexpr double pio4lo = constant(3.06161699786838301793e-17, 0x3C81A626, 0x33145C07);

// e_rem_pio2.c: 396 hex digits (476 decimal) of 2/pi, 24 bits per entry.
constexpr std::array<std::int32_t, 66> two_over_pi{
    0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7,
    0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129, 0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C,
    0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11,
    0x8B5A0A, 0x6D1F6D, 0x367ECF, 0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7,
    0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08, 0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, 0x91615E,
    0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
};

constexpr std::array<std::int32_t, 32> npio2_hw{
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB,
    0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB,
    0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
    0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB,
};

constexpr double zero = 0.0;
constexpr double two24 = constant(1.67772160000000000000e+07, 0x41700000, 0x00000000);
constexpr double invpio2 = constant(6.36619772367581382433e-01, 0x3FE45F30, 0x6DC9C883);
constexpr double pio2_1 = constant(1.57079632673412561417e+00, 0x3FF921FB, 0x54400000);
constexpr double pio2_1t = constant(6.07710050650619224932e-11, 0x3DD0B461, 0x1A626331);
constexpr double pio2_2 = constant(6.07710050630396597660e-11, 0x3DD0B461, 0x1A600000);
constexpr double pio2_2t = constant(2.02226624879595063154e-21, 0x3BA3198A, 0x2E037073);
constexpr double pio2_3 = constant(2.02226624871116645580e-21, 0x3BA3198A, 0x2E000000);
constexpr double pio2_3t = constant(8.47842766036889956997e-32, 0x397B839A, 0x252049C1);

// k_rem_pio2.c
constexpr std::array<double, 8> PIo2{
    constant(1.57079625129699707031e+00, 0x3FF921FB, 0x40000000),
    constant(7.54978941586159635335e-08, 0x3E74442D, 0x00000000),
    constant(5.39030252995776476554e-15, 0x3CF84698, 0x80000000),
    constant(3.28200341580791294123e-22, 0x3B78CC51, 0x60000000),
    constant(1.27065575308067607349e-29, 0x39F01B83, 0x80000000),
    constant(1.22933308981111328932e-36, 0x387A2520, 0x40000000),
    constant(2.73370053816464559624e-44, 0x36E38222, 0x80000000),
    constant(2.16741683877804819444e-51, 0x3569F31D, 0x00000000),
};
constexpr double twon24 = constant(5.96046447753906250000e-08, 0x3E700000, 0x00000000);

// NOLINTEND(readability-identifier-naming)
/// fdlibm: (double)(int)x, for the values below 2^31 that the reduction truncates.
[[nodiscard]] double truncated(double x) noexcept { return static_cast<double>(static_cast<std::int32_t>(x)); }

} // namespace

double kernel_sin(double x, double y, int iy) noexcept {
    const std::int32_t ix = hi(x) & 0x7fffffff; // fdlibm: high word of x
    if (ix < 0x3e400000) {                      // fdlibm: |x| < 2**-27
        return x;                               // fdlibm: (int)x == 0 always holds here: return x, inexact
    }
    const double z = x * x;
    const double v = z * x;
    const double r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if (iy == 0) {
        return x + v * (S1 + z * r);
    }
    return x - ((z * (half * y - v * r) - y) - v * S1);
}

double kernel_cos(double x, double y) noexcept {
    const std::int32_t ix = hi(x) & 0x7fffffff; // fdlibm: ix = |x|'s high word
    if (ix < 0x3e400000) {                      // fdlibm: if x < 2**27
        return one;                             // fdlibm: (int)x == 0 always holds here: generate inexact
    }
    const double z = x * x;
    const double r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if (ix < 0x3FD33333) { // fdlibm: if |x| < 0.3
        return one - (0.5 * z - (z * r - x * y));
    }
    double qx = 0.0;
    if (ix > 0x3fe90000) { // fdlibm: x > 0.78125
        qx = 0.28125;
    } else {
        qx = from_words(ix - 0x00200000, 0); // fdlibm: x/4
    }
    const double hz = 0.5 * z - qx;
    const double a = one - qx;
    return a - (hz - (z * r - x * y));
}

double kernel_tan(double x, double y, int iy) noexcept {
    double z = 0.0;
    double r = 0.0;
    double v = 0.0;
    double w = 0.0;
    double s = 0.0;
    const std::int32_t hx = hi(x);           // fdlibm: high word of x
    const std::int32_t ix = hx & 0x7fffffff; // fdlibm: high word of |x|
    if (ix < 0x3e300000) {                   // fdlibm: x < 2**-28
        // fdlibm: (int)x == 0 always holds here (generate inexact).
        if (((static_cast<std::uint32_t>(ix) | lo(x)) | static_cast<std::uint32_t>(iy + 1)) == 0) {
            return one / std::fabs(x);
        }
        if (iy == 1) {
            return x;
        }
        // fdlibm: compute -1 / (x+y) carefully
        w = x + y;
        z = with_lo(w, 0);
        v = y - (z - x);
        const double a = -one / w;
        const double t = with_lo(a, 0);
        s = one + t * z;
        return t + a * (s + t * v);
    }
    if (ix >= 0x3FE59428) { // fdlibm: |x| >= 0.6744
        if (hx < 0) {
            x = -x;
            y = -y;
        }
        z = pio4 - x;
        w = pio4lo - y;
        x = z + w;
        y = 0.0;
    }
    z = x * x;
    w = z * z;
    // fdlibm: break x^5*(T[1]+x^2*T[2]+...) into x^5(T[1]+x^4*T[3]+...+x^20*T[11]) +
    // x^5(x^2*(T[2]+x^4*T[4]+...+x^22*[T12]))
    r = T[1] + w * (T[3] + w * (T[5] + w * (T[7] + w * (T[9] + w * T[11]))));
    v = z * (T[2] + w * (T[4] + w * (T[6] + w * (T[8] + w * (T[10] + w * T[12])))));
    s = z * x;
    r = y + z * (s * (r + v) + y);
    r += T[0] * s;
    w = x + r;
    if (ix >= 0x3FE59428) {
        v = static_cast<double>(iy);
        return static_cast<double>(1 - ((hx >> 30) & 2)) * (v - 2.0 * (x - (w * w / (w + v) - r)));
    }
    if (iy == 1) {
        return w;
    }
    // fdlibm: compute -1.0 / (x+r) accurately
    z = with_lo(w, 0);
    v = r - (z - x);           // fdlibm: z+v = r+x
    const double a = -1.0 / w; // fdlibm: a = -1.0/w
    const double t = with_lo(a, 0);
    s = 1.0 + t * z;
    return t + a * (s + t * v);
}

int rem_pio2(double x, std::array<double, 2>& y) noexcept {
    const std::int32_t hx = hi(x); // fdlibm: high word of x
    const std::int32_t ix = hx & 0x7fffffff;
    if (ix <= 0x3fe921fb) { // fdlibm: |x| ~<= pi/4, no need for reduction
        y[0] = x;
        y[1] = 0;
        return 0;
    }
    if (ix < 0x4002d97c) { // fdlibm: |x| < 3pi/4, special case with n=+-1
        if (hx > 0) {
            double z = x - pio2_1;
            if (ix != 0x3ff921fb) { // fdlibm: 33+53 bit pi is good enough
                y[0] = z - pio2_1t;
                y[1] = (z - y[0]) - pio2_1t;
            } else { // fdlibm: near pi/2, use 33+33+53 bit pi
                z -= pio2_2;
                y[0] = z - pio2_2t;
                y[1] = (z - y[0]) - pio2_2t;
            }
            return 1;
        }
        double z = x + pio2_1; // fdlibm: negative x
        if (ix != 0x3ff921fb) {
            y[0] = z + pio2_1t;
            y[1] = (z - y[0]) + pio2_1t;
        } else {
            z += pio2_2;
            y[0] = z + pio2_2t;
            y[1] = (z - y[0]) + pio2_2t;
        }
        return -1;
    }
    if (ix <= 0x413921fb) { // fdlibm: |x| ~<= 2^19*(pi/2), medium size
        double t = std::fabs(x);
        const auto n = static_cast<std::int32_t>(t * invpio2 + half);
        const auto fn = static_cast<double>(n);
        double r = t - fn * pio2_1;
        double w = fn * pio2_1t; // fdlibm: 1st round good to 85 bit
        if (n < 32 && ix != npio2_hw[static_cast<std::size_t>(n - 1)]) {
            y[0] = r - w; // fdlibm: quick check no cancellation
        } else {
            const std::int32_t j = ix >> 20;
            y[0] = r - w;
            std::int32_t i = j - ((hi(y[0]) >> 20) & 0x7ff);
            if (i > 16) { // fdlibm: 2nd iteration needed, good to 118
                t = r;
                w = fn * pio2_2;
                r = t - w;
                w = fn * pio2_2t - ((t - r) - w);
                y[0] = r - w;
                i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if (i > 49) { // fdlibm: 3rd iteration need, 151 bits acc
                    t = r;    // fdlibm: will cover all possible cases
                    w = fn * pio2_3;
                    r = t - w;
                    w = fn * pio2_3t - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if (hx < 0) {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        }
        return n;
    }
    // fdlibm: all other (large) arguments
    if (ix >= 0x7ff00000) {  // fdlibm: x is inf or NaN
        y[0] = y[1] = x - x; // NOLINT(misc-redundant-expression): fdlibm: NaN for an infinity or a NaN
        return 0;
    }
    // fdlibm: set z = scalbn(|x|,ilogb(x)-23)
    const std::int32_t e0 = (ix >> 20) - 1046; // fdlibm: e0 = ilogb(z)-23
    double z = from_words(ix - (e0 << 20), lo(x));
    std::array<double, 3> tx{};
    for (std::size_t i = 0; i < 2; ++i) {
        tx[i] = truncated(z);
        z = (z - tx[i]) * two24;
    }
    tx[2] = z;
    std::size_t nx = 3;
    while (tx[nx - 1] == zero) {
        --nx; // fdlibm: skip zero term
    }
    const int n = kernel_rem_pio2(std::span<const double>(tx.data(), nx), y, e0);
    if (hx < 0) {
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }
    return n;
}

int kernel_rem_pio2(std::span<const double> x, std::array<double, 2>& y, int e0) noexcept {
    // fdlibm's arrays hold at most 20 entries; jz never passes jk + the few recomputed terms.
    std::array<std::int32_t, 20> iq{};
    std::array<double, 20> f{};
    std::array<double, 20> fq{};
    std::array<double, 20> q{};
    const auto at = [](auto& a, int i) -> auto& { return a[static_cast<std::size_t>(i)]; };
    const auto ipio2 = [](int i) { return two_over_pi[static_cast<std::size_t>(i)]; };

    // fdlibm: initialize jk (init_jk[prec] with prec 2: double precision)
    const int jk = 4;
    const int jp = jk;

    // fdlibm: determine jx,jv,q0, note that 3>q0
    const int nx = static_cast<int>(x.size());
    const int jx = nx - 1;
    const int jv = std::max((e0 - 3) / 24, 0);
    int q0 = e0 - 24 * (jv + 1);

    // fdlibm: set up f[0] to f[jx+jk] where f[jx+jk] = ipio2[jv+jk]
    int j = jv - jx;
    const int m = jx + jk;
    for (int i = 0; i <= m; ++i, ++j) {
        at(f, i) = (j < 0) ? zero : static_cast<double>(ipio2(j));
    }

    // fdlibm: compute q[0],q[1],...q[jk]
    double fw = 0.0;
    for (int i = 0; i <= jk; ++i) {
        fw = 0.0;
        for (j = 0; j <= jx; ++j) {
            fw += x[static_cast<std::size_t>(j)] * at(f, jx + i - j);
        }
        at(q, i) = fw;
    }

    int jz = jk;
    int n = 0;
    int ih = 0;
    double z = 0.0;
    for (;;) { // fdlibm: recompute:
        // fdlibm: distill q[] into iq[] reversingly
        int i = 0;
        j = jz;
        z = at(q, jz);
        for (; j > 0; ++i, --j) {
            fw = truncated(twon24 * z);
            at(iq, i) = static_cast<std::int32_t>(z - two24 * fw);
            z = at(q, j - 1) + fw;
        }

        // fdlibm: compute n
        z = scalbn(z, q0);                // fdlibm: actual value of z
        z -= 8.0 * std::floor(z * 0.125); // fdlibm: trim off integer >= 8
        n = static_cast<std::int32_t>(z);
        z -= static_cast<double>(n);
        ih = 0;
        if (q0 > 0) { // fdlibm: need iq[jz-1] to determine n
            i = (at(iq, jz - 1) >> (24 - q0));
            n += i;
            at(iq, jz - 1) -= i << (24 - q0);
            ih = at(iq, jz - 1) >> (23 - q0);
        } else if (q0 == 0) {
            ih = at(iq, jz - 1) >> 23;
        } else if (z >= 0.5) {
            ih = 2;
        }

        if (ih > 0) { // fdlibm: q > 0.5
            n += 1;
            int carry = 0;
            for (i = 0; i < jz; ++i) { // fdlibm: compute 1-q
                j = at(iq, i);
                if (carry == 0) {
                    if (j != 0) {
                        carry = 1;
                        at(iq, i) = 0x1000000 - j;
                    }
                } else {
                    at(iq, i) = 0xffffff - j;
                }
            }
            if (q0 > 0) { // fdlibm: rare case: chance is 1 in 12
                switch (q0) {
                case 1: at(iq, jz - 1) &= 0x7fffff; break;
                case 2: at(iq, jz - 1) &= 0x3fffff; break;
                default: break;
                }
            }
            if (ih == 2) {
                z = one - z;
                if (carry != 0) {
                    z -= scalbn(one, q0);
                }
            }
        }

        // fdlibm: check if recomputation is needed
        if (z == zero) {
            j = 0;
            for (i = jz - 1; i >= jk; --i) {
                j |= at(iq, i);
            }
            if (j == 0) { // fdlibm: need recomputation
                int k = 1;
                while (at(iq, jk - k) == 0) {
                    ++k; // fdlibm: k = no. of terms needed
                }
                for (i = jz + 1; i <= jz + k; ++i) { // fdlibm: add q[jz+1] to q[jz+k]
                    at(f, jx + i) = static_cast<double>(ipio2(jv + i));
                    fw = 0.0;
                    for (j = 0; j <= jx; ++j) {
                        fw += x[static_cast<std::size_t>(j)] * at(f, jx + i - j);
                    }
                    at(q, i) = fw;
                }
                jz += k;
                continue; // fdlibm: goto recompute
            }
        }
        break;
    }

    // fdlibm: chop off zero terms
    if (z == 0.0) {
        jz -= 1;
        q0 -= 24;
        while (at(iq, jz) == 0) {
            --jz;
            q0 -= 24;
        }
    } else { // fdlibm: break z into 24-bit if necessary
        z = scalbn(z, -q0);
        if (z >= two24) {
            fw = truncated(twon24 * z);
            at(iq, jz) = static_cast<std::int32_t>(z - two24 * fw);
            jz += 1;
            q0 += 24;
            at(iq, jz) = static_cast<std::int32_t>(fw);
        } else {
            at(iq, jz) = static_cast<std::int32_t>(z);
        }
    }

    // fdlibm: convert integer "bit" chunk to floating-point value
    fw = scalbn(one, q0);
    for (int i = jz; i >= 0; --i) {
        at(q, i) = fw * static_cast<double>(at(iq, i));
        fw *= twon24;
    }

    // fdlibm: compute PIo2[0,...,jp]*q[jz,...,0]
    for (int i = jz; i >= 0; --i) {
        fw = 0.0;
        for (int k = 0; k <= jp && k <= jz - i; ++k) {
            fw += PIo2[static_cast<std::size_t>(k)] * at(q, i + k);
        }
        at(fq, jz - i) = fw;
    }

    // fdlibm: compress fq[] into y[] (prec 2)
    fw = 0.0;
    for (int i = jz; i >= 0; --i) {
        fw += at(fq, i);
    }
    y[0] = (ih == 0) ? fw : -fw;
    fw = at(fq, 0) - fw;
    for (int i = 1; i <= jz; ++i) {
        fw += at(fq, i);
    }
    y[1] = (ih == 0) ? fw : -fw;
    return n & 7;
}

} // namespace atlas::pymath::fdlibm
