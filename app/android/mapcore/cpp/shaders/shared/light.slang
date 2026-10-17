// The light on a surface (plan-vector D8): the port's light() (render/amsterdam.py:261, R/Amsterdam.kt light) and
// tohex (render/city.py:13), shared by the C++ twin (CP/style/Light.hpp) and the GPU.
//
// light() and tohex() are the port's own arithmetic in binary64, operation for operation and in the port's order, so
// they give the port's bits: exactness class E0, as C++ and on a GPU with shaderFloat64 (lavapipe on CI and this Mac;
// CP/tests/style/LightTest.cpp, MT/style/LightParityTest.kt). Nothing in them is left to a driver: they use only +, -
// and * (correctly rounded, never fused: the probe is an exact module, compiled with -fp-mode precise and checked by
// tools/nocontract), comparisons, selects, conversions, floor and bit casts. The port's two divisions and the exp of
// TONE's shoulder are IEEE division and StrictMath.exp. C++ computes them as such (its division, and
// pymath::strict::exp). The GPU has neither for binary64: Vulkan leaves OpFDiv's rounding open, and GLSL.std.450's Exp
// is defined for 16- and 32-bit floats only. So below, for the GPU alone, a quotient is corrected by its exact
// remainder, and exp is fdlibm 5.3's e_exp.c (which StrictMath.exp is) written with exact operations.
//
// Exact on a GPU means two things the device must have: shaderFloat64, and binary64 add, subtract and multiply that
// round correctly. Vulkan promises double precision only "at least that of single precision", so the probe proves the
// second per device (lavapipe's are IEEE). Most phones' GPUs have no shaderFloat64 (4.3 % of the Android devices on
// vulkan.gpuinfo.org do, October 2026) and cannot run light() at all. They get light_f32(): the same rule in binary32,
// exactness class E1, within light_f32_bound (CP/style/Light.hpp, where its derivation is) of the port's value, so its
// bytes are the port's wherever the port's value lies farther than that from a half level, and one level off at most
// where it lies nearer.
//
// Not here: the per-object aerial haze (th['_hz']), which the renders apply at eye level only (outside the map, D9).
#pragma once

#include "theme.slang"

#if !defined(__cplusplus)

// A binary64 constant (Slang's unsuffixed literals are float; C++'s Light.hpp defines this as the plain literal).
#define ATLAS_D(x) x##l

/// The double of a low and a high word (the theme's bits_ lanes, fdlibm's constants).
inline double light_bits(uint lo, uint hi) {
    return asdouble(lo, hi);
}

inline double light_floor(double x) {
    return floor(x);
}

/// Python's max(0.0, x): x only when it is greater, so -0.0 and NaN give 0.0.
inline double light_max0(double x) {
    return x > ATLAS_D(0.0) ? x : ATLAS_D(0.0);
}

/// Python's min(255, n) on a whole level n >= 0.
inline double light_clamp_level(double r) {
    return r > ATLAS_D(255.0) ? ATLAS_D(255.0) : r;
}

/// e^x for light_f32's shoulder: the GPU's own (E1 allows its rounding).
inline float light_exp_f32(float x) {
    return exp(x);
}

/// Two doubles whose exact sum is a value: the rounded value and what rounding left out.
struct LightPair {
    double hi;
    double lo;
};

/// a = hi + lo, each with at most 26 significant bits (Veltkamp's split; 134217729 = 2^27 + 1).
inline LightPair light_split(double a) {
    double t = ATLAS_D(134217729.0) * a;
    LightPair r;
    r.hi = t - (t - a);
    r.lo = a - r.hi;
    return r;
}

/// a * b = hi + lo exactly (Dekker's product), without a fused operation.
inline LightPair light_product(double a, double b) {
    LightPair r;
    r.hi = a * b;
    LightPair x = light_split(a);
    LightPair y = light_split(b);
    r.lo = (((x.hi * y.hi - r.hi) + x.hi * y.lo) + x.lo * y.hi) + x.lo * y.lo;
    return r;
}

/// The doubles next to a positive normal q: the one above, and the one below.
inline double light_next_up(double q) {
    uint lo;
    uint hi;
    asuint(q, lo, hi);
    uint l = lo + 1u;
    return asdouble(l, l == 0u ? hi + 1u : hi);
}

inline double light_next_down(double q) {
    uint lo;
    uint hi;
    asuint(q, lo, hi);
    return asdouble(lo - 1u, lo == 0u ? hi - 1u : hi);
}

/// a / b as IEEE division rounds it, for b > 0, given r within 2^-50 of 1/b (relative), where a, b and the quotient
/// are normal. q1 below is within half a unit and 2^-47 units of a / b, so it is faithful, and its remainder
/// a - q1 * b is then a double, computed exactly by Dekker's product; the remainder against half the gap to each
/// neighbour (b times a power of two: exact) decides between q1 and its neighbours. A quotient of doubles is never
/// a halfway point, so there is no tie to break.
inline double light_quotient(double a, double b, double r) {
    if (a == ATLAS_D(0.0)) {
        return a; // +-0 / b
    }
    double x = a < ATLAS_D(0.0) ? -a : a;
    double q0 = x * r;
    LightPair p0 = light_product(q0, b);
    double q1 = q0 + ((x - p0.hi) - p0.lo) * r; // x - p0.hi is exact (Sterbenz)
    LightPair p1 = light_product(q1, b);
    double rem = (x - p1.hi) - p1.lo; // exact: the remainder of a faithful quotient is a double
    double up = light_next_up(q1);
    double down = light_next_down(q1);
    if (rem > ATLAS_D(0.0) && rem * ATLAS_D(2.0) > b * (up - q1)) {
        q1 = up;
    } else if (rem < ATLAS_D(0.0) && -rem * ATLAS_D(2.0) > b * (q1 - down)) {
        q1 = down;
    }
    return a < ATLAS_D(0.0) ? -q1 : q1;
}

/// The port's x / 255 (IEEE division); 1/255 to the nearest double.
inline double light_div255(double x) {
    return light_quotient(x, ATLAS_D(255.0), light_bits(0x10101010u, 0x3f701010u));
}

/// The port's x / 50; 1/50 to the nearest double.
inline double light_div50(double x) {
    return light_quotient(x, ATLAS_D(50.0), light_bits(0x47ae147bu, 0x3f947ae1u));
}

/// a / b for b in [1.5, 2.5] (fdlibm exp's 2 - c), its reciprocal by five Newton steps from 1/2 (|1 - b/2| <= 1/4).
inline double light_quotient_near2(double a, double b) {
    double r = ATLAS_D(0.5);
    for (int i = 0; i < 5; ++i) {
        r = r + r * (ATLAS_D(1.0) - b * r);
    }
    return light_quotient(a, b, r);
}

/// e^x as fdlibm 5.3's e_exp.c computes it (Sun's notice: "Permission to use, copy, modify, and distribute this
/// software is freely granted, provided that this notice is preserved"), which is Java's StrictMath.exp and so the
/// port's PyMath.exp. The GPU's own, because SPIR-V has no binary64 exp and pymath::strict::exp, the C++ side's, cannot
/// run on a GPU; its one division is light_quotient_near2's. The same bits as StrictMath.exp for every x whose result
/// is normal (x >= -708.39); a device may flush a subnormal result to zero.
inline double light_exp(double x) {
    // fdlibm's constants by their words, as e_exp.c documents them
    double o_threshold = light_bits(0xFEFA39EFu, 0x40862E42u);
    double u_threshold = light_bits(0xD52D3051u, 0xc0874910u);
    double ln2_hi = light_bits(0xfee00000u, 0x3fe62e42u);
    double ln2_lo = light_bits(0x35793c76u, 0x3dea39efu);
    double invln2 = light_bits(0x652b82feu, 0x3ff71547u);
    double p1 = light_bits(0x5555553Eu, 0x3FC55555u);
    double p2 = light_bits(0x16BEBD93u, 0xBF66C16Cu);
    double p3 = light_bits(0xAF25DE2Cu, 0x3F11566Au);
    double p4 = light_bits(0xC5D26BF1u, 0xBEBBBD41u);
    double p5 = light_bits(0x72BEA4D0u, 0x3E663769u);
    double huge = ATLAS_D(1.0e+300);
    double twom1000 = light_bits(0x00000000u, 0x01700000u); // 2^-1000

    uint lx;
    uint hx;
    asuint(x, lx, hx);
    uint xsb = (hx >> 31u) & 1u; // the sign bit of x
    hx = hx & 0x7fffffffu;       // the high word of |x|

    // non-finite and huge arguments
    if (hx >= 0x40862E42u) { // |x| >= 709.78...
        if (hx >= 0x7ff00000u) {
            if (((hx & 0xfffffu) | lx) != 0u) {
                return x + x; // NaN
            }
            return xsb == 0u ? x : ATLAS_D(0.0); // exp(+-inf) = {inf, 0}
        }
        if (x > o_threshold) {
            return huge * huge; // overflow
        }
        if (x < u_threshold) {
            return twom1000 * twom1000; // underflow
        }
    }

    // argument reduction
    double hi = ATLAS_D(0.0);
    double lo = ATLAS_D(0.0);
    int k = 0;
    if (hx > 0x3fd62e42u) {     // |x| > 0.5 ln2
        if (hx < 0x3FF0A2B2u) { // and |x| < 1.5 ln2
            hi = xsb == 0u ? x - ln2_hi : x + ln2_hi;
            lo = xsb == 0u ? ln2_lo : -ln2_lo;
            k = 1 - int(xsb) - int(xsb);
        } else {
            k = int(invln2 * x + (xsb == 0u ? ATLAS_D(0.5) : ATLAS_D(-0.5)));
            double t = double(k);
            hi = x - t * ln2_hi; // t * ln2_hi is exact here
            lo = t * ln2_lo;
        }
        x = hi - lo;
    } else if (hx < 0x3e300000u) { // |x| < 2^-28
        return ATLAS_D(1.0) + x;
    }

    // x is now in the primary range
    double t = x * x;
    double c = x - t * (p1 + t * (p2 + t * (p3 + t * (p4 + t * p5))));
    // (x * c) / (c - 2.0) is -((x * c) / (2.0 - c)): IEEE division and rounding are symmetric in sign
    double q = light_quotient_near2(x * c, ATLAS_D(2.0) - c);
    if (k == 0) {
        return ATLAS_D(1.0) - (-q - x);
    }
    double y = ATLAS_D(1.0) - ((lo - q) - hi);
    uint ly;
    uint hy;
    asuint(y, ly, hy);
    if (k >= -1021) {
        return asdouble(ly, uint(int(hy) + (k << 20))); // k added to y's exponent
    }
    return asdouble(ly, uint(int(hy) + ((k + 1000) << 20))) * twom1000;
}

#endif

// ---------------------------------------------------------------- the exact light: binary64, the port's bits

/// The theme's doubles light() reads, from their bits.
inline double theme_amb_k(Theme th) {
    return light_bits(th.bits_amb_sun.x, th.bits_amb_sun.y);
}

inline double theme_sun_k(Theme th) {
    return light_bits(th.bits_amb_sun.z, th.bits_amb_sun.w);
}

inline double theme_sky_k(Theme th) {
    return light_bits(th.bits_sky_lx.x, th.bits_sky_lx.y);
}

inline double3 theme_sun_vector(Theme th) {
    double3 v = {light_bits(th.bits_sky_lx.z, th.bits_sky_lx.w), light_bits(th.bits_lyz.x, th.bits_lyz.y),
                 light_bits(th.bits_lyz.z, th.bits_lyz.w)};
    return v;
}

/// TONE's soft shoulder on a highlight (amsterdam.py:270): a channel over 205 eases toward 255.
inline double tone_shoulder(double v) {
    if (v < ATLAS_D(205.0)) {
        return v;
    }
    return ATLAS_D(205.0) + ATLAS_D(50.0) * (ATLAS_D(1.0) - light_exp(light_div50(-(v - ATLAS_D(205.0)))));
}

/// One channel of the day's light (amsterdam.py:266-269): the ambient mixed toward the theme's, the sky from above
/// and the sun, as amb[i] + base[i] * sky + base[i] * lam * 1.05 * sun_k * sun[i] / 255.
inline double light_day(double base, double amb, double sun, double amb_k, double amb_mix, double sky, double lam,
                        double sun_k) {
    double a = base * amb_k;
    double b = amb * (amb_k * ATLAS_D(0.55));
    double ambient = a + (b - a) * amb_mix;
    return ambient + base * sky + light_div255(base * lam * ATLAS_D(1.05) * sun_k * sun);
}

/// One channel at night (amsterdam.py:264): the colour mixed toward the ambient, times k.
inline double light_night(double base, double amb, double k) {
    return (base + (amb - base) * ATLAS_D(0.55)) * k;
}

/// A surface of colour `base` (0-255) with unit normal `n` in the moment's light: ambient, the sky from above, and the
/// sun where `lit` (its sun bit); at night the ambient alone. Unrounded, as the port returns it (some rules scale it
/// before rounding: the reflections' 0.74, 0.62 and 0.5).
inline double3 light(Theme th, double3 base, double3 n, bool lit) {
    double3 amb = {double(th.ambient.x), double(th.ambient.y), double(th.ambient.z)};
    if (theme_has(th, THEME_NIGHT)) {
        double k = ATLAS_D(0.4) + ATLAS_D(0.08) * light_max0(n.y);
        double3 m = {light_night(base.x, amb.x, k), light_night(base.y, amb.y, k), light_night(base.z, amb.z, k)};
        return m;
    }
    double amb_k = theme_amb_k(th);
    double amb_mix = theme_has(th, THEME_TONE) ? ATLAS_D(0.26) : ATLAS_D(0.35);
    double3 l = theme_sun_vector(th);
    double lam = lit ? light_max0(n.x * l.x + n.y * l.y + n.z * l.z) : ATLAS_D(0.0);
    double sky = (ATLAS_D(0.18) + ATLAS_D(0.12) * light_max0(n.y)) * theme_sky_k(th);
    double sun_k = theme_sun_k(th);
    double3 sun = {double(th.sun.x), double(th.sun.y), double(th.sun.z)};
    double3 c = {light_day(base.x, amb.x, sun.x, amb_k, amb_mix, sky, lam, sun_k),
                 light_day(base.y, amb.y, sun.y, amb_k, amb_mix, sky, lam, sun_k),
                 light_day(base.z, amb.z, sun.z, amb_k, amb_mix, sky, lam, sun_k)};
    if (theme_has(th, THEME_TONE)) {
        c.x = tone_shoulder(c.x);
        c.y = tone_shoulder(c.y);
        c.z = tone_shoulder(c.z);
    }
    return c;
}

/// One channel as tohex writes it (city.py:13): Python's round, half to even, then clamped to 0-255. Exact: below 0
/// every value rounds to 0 or less, and above it the fraction v - floor(v) is a double.
inline double tohex(double v) {
    if (v <= ATLAS_D(0.0)) {
        return ATLAS_D(0.0);
    }
    double r = light_floor(v);
    double f = v - r;
    double half = r * ATLAS_D(0.5);
    if (f > ATLAS_D(0.5) || (f == ATLAS_D(0.5) && half != light_floor(half))) {
        r = r + ATLAS_D(1.0);
    }
    return light_clamp_level(r);
}

/// A colour as tohex writes it: each channel a whole level 0-255.
inline uint3 tohex3(double3 c) {
    uint3 r = {uint(tohex(c.x)), uint(tohex(c.y)), uint(tohex(c.z))};
    return r;
}

/// The light as the SVG prints it: light() through tohex.
inline uint3 light_bytes(Theme th, double3 base, double3 n, bool lit) {
    return tohex3(light(th, base, n, lit));
}

// ---------------------------------------------------------------- binary32: for GPUs without shaderFloat64 (E1)

/// tone_shoulder in binary32, with the GPU's own exp.
inline float tone_shoulder_f32(float v) {
    return v < 205.0f ? v : 205.0f + 50.0f * (1.0f - light_exp_f32(-(v - 205.0f) / 50.0f));
}

/// light() in binary32: the port's rule and order on the theme's float lanes and on float inputs.
inline float3 light_f32(Theme th, float3 base, float3 n, bool lit) {
    if (theme_has(th, THEME_NIGHT)) {
        float3 m = base + (th.ambient.xyz - base) * 0.55f;
        return m * (0.4f + 0.08f * max(0.0f, n.y));
    }
    float amb_k = th.ambient.w;
    float3 a = base * amb_k;
    float3 b = th.ambient.xyz * (amb_k * 0.55f);
    float3 amb = a + (b - a) * (theme_has(th, THEME_TONE) ? 0.26f : 0.35f);
    float lam = lit ? max(0.0f, dot(n, th.light.xyz)) : 0.0f;
    float sky = (0.18f + 0.12f * max(0.0f, n.y)) * th.light.w;
    float3 c = amb + base * sky + base * lam * 1.05f * th.sun.w * th.sun.xyz / 255.0f;
    if (theme_has(th, THEME_TONE)) {
        c.x = tone_shoulder_f32(c.x);
        c.y = tone_shoulder_f32(c.y);
        c.z = tone_shoulder_f32(c.z);
    }
    return c;
}

/// tohex's channel in binary32: the same rounding, exact on the float it is given.
inline float tohex_f32(float v) {
    if (v <= 0.0f) {
        return 0.0f;
    }
    float r = floor(v);
    float f = v - r;
    float half = r * 0.5f;
    if (f > 0.5f || (f == 0.5f && half != floor(half))) {
        r = r + 1.0f;
    }
    return r > 255.0f ? 255.0f : r;
}

inline uint3 tohex3_f32(float3 c) {
    uint3 r = {uint(tohex_f32(c.x)), uint(tohex_f32(c.y)), uint(tohex_f32(c.z))};
    return r;
}
