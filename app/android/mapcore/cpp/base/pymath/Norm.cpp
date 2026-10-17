// CPython's math.hypot and math.dist (its vector_norm) as the port computes them (R/py/PyMath.kt): 3.9's sum of
// squared ratios, or 3.12's lossless squaring with a differential correction, whose exact product errors come from the
// port's ExactMath.productError (Dekker's split), not from a fused multiply-add.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/fdlibm/Fdlibm.hpp"

#include <cmath>
#include <cstddef>
#include <limits>
#include <source_location>
#include <span>

namespace atlas::pymath {

namespace {

constexpr double split = 134217729.0; // 2^27 + 1
constexpr double min_normal = std::numeric_limits<double>::min();

/// CPython 3.9's vector_norm body: the Neumaier sum of the squared ratios to the maximum.
template <class Value>
[[nodiscard]] double classic_norm(std::size_t n, const Value& value, double max) noexcept {
    double csum = 1.0;
    double frac = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        double x = value(i);
        x /= max;
        x *= x;
        const double old = csum;
        csum += x;
        frac += (old - csum) + x;
    }
    return max * std::sqrt(csum - 1.0 + frac);
}

/// CPython 3.12's vector_norm body for a maximum that is not tiny: lossless squaring with a differential correction.
template <class Value>
[[nodiscard]] double modern_norm(std::size_t n, const Value& value, double max) noexcept {
    const double scale = fdlibm::scalbn(1.0, -frexp_exp(max));
    double csum = 1.0;
    double frac1 = 0.0;
    double frac2 = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        double x = value(i);
        x *= scale;
        const double pr_hi = x * x;
        const double pr_lo = product_error(x, x, pr_hi);
        const double sm_hi = csum + pr_hi;
        const double sm_lo = (csum - sm_hi) + pr_hi;
        csum = sm_hi;
        frac1 += pr_lo;
        frac2 += sm_lo;
    }
    double h = std::sqrt(csum - 1.0 + (frac1 + frac2));
    const double pr_hi = -h * h;
    const double pr_lo = product_error(-h, h, pr_hi);
    const double sm_hi = csum + pr_hi;
    const double sm_lo = (csum - sm_hi) + pr_hi;
    csum = sm_hi;
    frac1 += pr_lo;
    frac2 += sm_lo;
    const double x = csum - 1.0 + (frac1 + frac2);
    h += x / (2.0 * h);
    return h / scale;
}

/// vector_norm over n coordinates whose magnitudes `value(i)` gives, with their maximum and whether one is NaN.
template <class Value>
[[nodiscard]] double vector_norm(std::size_t n, const Value& value, double max, bool nan, Compat compat) noexcept {
    if (std::isinf(max)) {
        return max;
    }
    if (nan) {
        return std::numeric_limits<double>::quiet_NaN();
    }
    if (max == 0.0 || n <= 1) {
        return max;
    }
    if (!compat.modern_hypot()) {
        return classic_norm(n, value, max);
    }
    if (frexp_exp(max) < -1023) {
        // ldexp(1.0, -max_e) would overflow: the port divides every coordinate by DBL_MIN and recurses, and the
        // recursion's maximum is normal, so its checks pass straight to the body.
        const auto rescaled = [&value](std::size_t i) { return value(i) / min_normal; };
        return min_normal * modern_norm(n, rescaled, max / min_normal);
    }
    return modern_norm(n, value, max);
}

} // namespace

double product_error(double a, double b, double p) noexcept {
    double t = split * a;
    const double ah = t - (t - a);
    const double al = a - ah;
    t = split * b;
    const double bh = t - (t - b);
    const double bl = b - bh;
    return ((ah * bh - p) + ah * bl + al * bh) + al * bl;
}

double hypot(double x, double y, Compat compat) noexcept {
    const double a = std::fabs(x);
    const double b = std::fabs(y);
    // The port's two-argument form takes the larger as `b > a ? b : a`, so a NaN first argument stays the maximum
    // (hypot(nan, inf) is NaN here, where CPython's loop from 0.0 gives inf); kept, as the port is the reference.
    const double max = pymath::max(a, b);
    const bool nan = std::isnan(a) || std::isnan(b);
    const auto value = [a, b](std::size_t i) { return i == 0 ? a : b; };
    return vector_norm(2, value, max, nan, compat);
}

double hypot(std::span<const double> coordinates, Compat compat) noexcept {
    double max = 0.0;
    bool nan = false;
    for (const double c : coordinates) {
        const double x = std::fabs(c);
        nan = nan || std::isnan(x);
        max = pymath::max(max, x); // `if (x > max) max = x`, as CPython's loop
    }
    const auto value = [coordinates](std::size_t i) { return std::fabs(coordinates[i]); };
    return vector_norm(coordinates.size(), value, max, nan, compat);
}

Result<double> checked::dist(std::span<const double> p, std::span<const double> q, Compat compat) noexcept {
    if (p.size() != q.size()) {
        return std::unexpected(Error::dimensions);
    }
    double max = 0.0;
    bool nan = false;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const double x = std::fabs(p[i] - q[i]);
        nan = nan || std::isnan(x);
        max = pymath::max(max, x); // `if (x > max) max = x`, as CPython's loop
    }
    const auto value = [p, q](std::size_t i) { return std::fabs(p[i] - q[i]); };
    return vector_norm(p.size(), value, max, nan, compat);
}

double dist(std::span<const double> p, std::span<const double> q, Compat compat, std::source_location where) noexcept {
    return value_or_fail(checked::dist(p, q, compat), where);
}

} // namespace atlas::pymath
