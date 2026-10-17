// fdlibm 5.3's s_sin.c, s_cos.c and s_tan.c: StrictMath.sin, cos and tan.
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
#include "base/pymath/fdlibm/Fdlibm.hpp"
#include "base/pymath/fdlibm/Words.hpp"

#include <array>
#include <cstdint>

#if defined(ATLAS_PLANTED_PLATFORM_SIN)
#include <cmath>
#endif

namespace atlas::pymath::strict {

using fdlibm::hi;

double sin(double x) noexcept {
#if defined(ATLAS_PLANTED_PLATFORM_SIN)
    // The planted failure of plan-vector D16 (L0 d): the platform's sin in pymath's place. Never in a real build; CI
    // builds it on purpose and requires pymath's bit tests to fail on at least one host.
    return std::sin(x); // NOLINT(bugprone-unsafe-functions): the plant is the point
#else
    const std::int32_t ix = hi(x) & 0x7fffffff; // fdlibm: high word of x
    if (ix <= 0x3fe921fb) {                     // fdlibm: |x| ~< pi/4
        return fdlibm::kernel_sin(x, 0.0, 0);
    }
    if (ix >= 0x7ff00000) { // fdlibm: sin(Inf or NaN) is NaN
        return x - x;       // NOLINT(misc-redundant-expression): fdlibm: NaN for an infinity, the NaN itself for a NaN
    }
    std::array<double, 2> y{}; // fdlibm: argument reduction needed
    const int n = fdlibm::rem_pio2(x, y);
    switch (n & 3) {
    case 0: return fdlibm::kernel_sin(y[0], y[1], 1);
    case 1: return fdlibm::kernel_cos(y[0], y[1]);
    case 2: return -fdlibm::kernel_sin(y[0], y[1], 1);
    default: return -fdlibm::kernel_cos(y[0], y[1]);
    }
#endif
}

double cos(double x) noexcept {
    const std::int32_t ix = hi(x) & 0x7fffffff; // fdlibm: high word of x
    if (ix <= 0x3fe921fb) {                     // fdlibm: |x| ~< pi/4
        return fdlibm::kernel_cos(x, 0.0);
    }
    if (ix >= 0x7ff00000) { // fdlibm: cos(Inf or NaN) is NaN
        return x - x;       // NOLINT(misc-redundant-expression): fdlibm: NaN for an infinity, the NaN itself for a NaN
    }
    std::array<double, 2> y{}; // fdlibm: argument reduction needed
    const int n = fdlibm::rem_pio2(x, y);
    switch (n & 3) {
    case 0: return fdlibm::kernel_cos(y[0], y[1]);
    case 1: return -fdlibm::kernel_sin(y[0], y[1], 1);
    case 2: return -fdlibm::kernel_cos(y[0], y[1]);
    default: return fdlibm::kernel_sin(y[0], y[1], 1);
    }
}

double tan(double x) noexcept {
    const std::int32_t ix = hi(x) & 0x7fffffff; // fdlibm: high word of x
    if (ix <= 0x3fe921fb) {                     // fdlibm: |x| ~< pi/4
        return fdlibm::kernel_tan(x, 0.0, 1);
    }
    if (ix >= 0x7ff00000) { // fdlibm: tan(Inf or NaN) is NaN
        return x - x;       // NOLINT(misc-redundant-expression): fdlibm: NaN for an infinity, the NaN itself for a NaN
    }
    std::array<double, 2> y{}; // fdlibm: argument reduction needed
    const int n = fdlibm::rem_pio2(x, y);
    return fdlibm::kernel_tan(y[0], y[1], 1 - ((n & 1) << 1)); // fdlibm: 1 -- n even, -1 -- n odd
}

} // namespace atlas::pymath::strict
