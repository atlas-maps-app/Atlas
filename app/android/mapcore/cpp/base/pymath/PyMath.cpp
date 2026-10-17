// The port's PyMath (R/py/PyMath.kt) in C++: Python's operators and the math module's checks over fdlibm. Each function
// follows the port's Kotlin line for line; where the port throws, checked:: returns the error and the plain function
// ends the process with Python's message (base/pymath/PyMath.hpp).
#include "base/pymath/PyMath.hpp"

#include "base/assert.hpp"
#include "base/pymath/fdlibm/Fdlibm.hpp"

#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <source_location>
#include <span>
#include <string_view>

namespace atlas::pymath {

namespace {

/// Java's Math.getExponent: the unbiased exponent, -1023 for zero and subnormals, 1024 for infinities and NaN.
[[nodiscard]] int get_exponent(double x) noexcept {
    return static_cast<int>((std::bit_cast<std::uint64_t>(x) >> 52U) & 0x7ffU) - 1023;
}

/// Java's Math.scalb: d * 2^n rounded once. fdlibm's scalbn rounds the same way; n is first clamped as Java clamps it
/// (beyond 2099 every finite non-zero value over- or underflows), which keeps scalbn's own sums in range.
[[nodiscard]] double scalb(double d, int n) noexcept {
    constexpr int max_scale = 2099; // Double.MAX_EXPONENT - Double.MIN_EXPONENT + 53 + 1
    return fdlibm::scalbn(d, n < -max_scale ? -max_scale : (n > max_scale ? max_scale : n));
}

constexpr double two_54 = 18014398509481984.0;

} // namespace

std::string_view message(Error error) noexcept {
    switch (error) {
    case Error::float_modulo: return "float modulo";
    case Error::float_floor_division: return "float floor division by zero";
    case Error::integer_division: return "integer division or modulo by zero";
    case Error::domain: return "math domain error";
    case Error::range: return "math range error";
    case Error::not_finite: return "cannot convert float to integer";
    case Error::too_large: return "int too large to convert to a 64-bit integer";
    case Error::zero_to_negative_power: return "0.0 cannot be raised to a negative power";
    case Error::negative_to_fractional_power: return "negative number cannot be raised to a fractional power";
    case Error::power_out_of_range: return "Numerical result out of range";
    case Error::dimensions: return "both points must have the same number of dimensions";
    case Error::not_hashable: return "hash(nan) depends on the object's address since 3.10";
    case Error::empty_sequence: return "Cannot choose from an empty sequence";
    case Error::empty_range: return "empty range for randrange()";
    case Error::invalid_text: return "a str that is not well-formed UTF-8";
    }
    return "unknown Python error";
}

void fail(Error error, std::source_location where) noexcept { ::atlas::fail("pymath", message(error), where); }

// ---------------------------------------------------------------- operators

Result<double> checked::mod(double a, double b) noexcept {
    if (b == 0.0) {
        return std::unexpected(Error::float_modulo);
    }
    double m = std::fmod(a, b); // Kotlin's a % b on doubles: C's fmod, exact
    if (m != 0.0) {
        if ((b < 0) != (m < 0)) {
            m += b;
        }
    } else {
        m = std::copysign(0.0, b);
    }
    return m;
}

Result<std::int64_t> checked::mod(std::int64_t a, std::int64_t b) noexcept {
    if (b == 0) {
        return std::unexpected(Error::integer_division);
    }
    if (b == -1) {
        return 0; // Math.floorMod, without C++'s overflow of INT64_MIN % -1
    }
    std::int64_t r = a % b;
    if (r != 0 && ((r ^ b) < 0)) {
        r += b;
    }
    return r;
}

Result<double> checked::floor_div(double a, double b) noexcept {
    if (b == 0.0) {
        return std::unexpected(Error::float_floor_division);
    }
    double m = std::fmod(a, b);
    double div = (a - m) / b;
    if (m != 0.0) {
        if ((b < 0) != (m < 0)) {
            m += b;
            div -= 1.0;
        }
    }
    if (div != 0.0) {
        double f = std::floor(div);
        if (div - f > 0.5) {
            f += 1.0;
        }
        return f;
    }
    return std::copysign(0.0, a / b);
}

Result<std::int64_t> checked::floor_div(std::int64_t a, std::int64_t b) noexcept {
    if (b == 0) {
        return std::unexpected(Error::integer_division);
    }
    if (a == std::numeric_limits<std::int64_t>::min() && b == -1) {
        return a; // Math.floorDiv wraps where Python's int would grow
    }
    std::int64_t q = a / b;
    if (((a ^ b) < 0) && (q * b != a)) {
        --q;
    }
    return q;
}

Result<double> checked::fmod(double a, double b) noexcept {
    if (std::isinf(b) && std::isfinite(a)) {
        return a;
    }
    if (b == 0.0 || std::isinf(a)) {
        return std::unexpected(Error::domain);
    }
    return std::fmod(a, b);
}

double mod(double a, double b, std::source_location where) noexcept { return value_or_fail(checked::mod(a, b), where); }

std::int64_t mod(std::int64_t a, std::int64_t b, std::source_location where) noexcept {
    return value_or_fail(checked::mod(a, b), where);
}

double floor_div(double a, double b, std::source_location where) noexcept {
    return value_or_fail(checked::floor_div(a, b), where);
}

std::int64_t floor_div(std::int64_t a, std::int64_t b, std::source_location where) noexcept {
    return value_or_fail(checked::floor_div(a, b), where);
}

DivMod<double> divmod(double a, double b, std::source_location where) noexcept {
    return {.quotient = floor_div(a, b, where), .remainder = mod(a, b, where)};
}

DivMod<std::int64_t> divmod(std::int64_t a, std::int64_t b, std::source_location where) noexcept {
    return {.quotient = floor_div(a, b, where), .remainder = mod(a, b, where)};
}

double fmod(double a, double b, std::source_location where) noexcept {
    return value_or_fail(checked::fmod(a, b), where);
}

// ---------------------------------------------------------------- sum

double sum(std::span<const double> values, Compat compat) noexcept {
    if (values.empty()) {
        return 0.0;
    }
    double f = 0.0 + values[0]; // CPython starts from the int 0
    if (!compat.compensated_sum()) {
        for (const double x : values.subspan(1)) {
            f += x;
        }
        return f;
    }
    double c = 0.0;
    for (const double x : values.subspan(1)) {
        const double t = f + x; // Neumaier's compensation, as CPython 3.12 adds
        c += std::fabs(f) >= std::fabs(x) ? (f - t) + x : (x - t) + f;
        f = t;
    }
    if (c != 0.0 && std::isfinite(c)) {
        f += c;
    }
    return f;
}

// ---------------------------------------------------------------- the math module

Result<double> checked::sqrt(double x) noexcept {
    if (x < 0) {
        return std::unexpected(Error::domain);
    }
    return std::sqrt(x);
}

Result<double> checked::asin(double x) noexcept {
    const double r = strict::asin(x);
    if (std::isnan(r) && !std::isnan(x)) {
        return std::unexpected(Error::domain); // math_1: a NaN from a non-NaN is a domain error
    }
    return r;
}

Result<double> checked::acos(double x) noexcept {
    const double r = strict::acos(x);
    if (std::isnan(r) && !std::isnan(x)) {
        return std::unexpected(Error::domain);
    }
    return r;
}

Result<double> checked::exp(double x) noexcept {
    const double r = strict::exp(x);
    if (std::isinf(r) && std::isfinite(x)) {
        return std::unexpected(Error::range); // math_1: an infinity from a finite value is a range error
    }
    return r;
}

Result<double> checked::sinh(double x) noexcept {
    const double r = strict::sinh(x);
    if (std::isinf(r) && std::isfinite(x)) {
        return std::unexpected(Error::range);
    }
    return r;
}

Result<double> checked::log(double x) noexcept {
    if (x <= 0) {
        return std::unexpected(Error::domain);
    }
    return strict::log(x);
}

Result<double> checked::log(double x, double base) noexcept {
    return log(x).and_then([base](double lx) { return log(base).transform([lx](double lb) { return lx / lb; }); });
}

Result<double> checked::log10(double x) noexcept {
    if (x <= 0) {
        return std::unexpected(Error::domain);
    }
    return strict::log10(x);
}

Result<double> checked::log2(double x) noexcept {
    if (x <= 0) {
        return std::unexpected(Error::domain);
    }
    const int m = get_exponent(x);
    // CPython uses the C library's log2; for exact powers of two it is exact (and the port answers them so).
    if (x == scalb(1.0, m)) {
        return static_cast<double>(m);
    }
    return strict::log(x) / strict::log(2.0);
}

Result<double> checked::pow(double x, double y) noexcept {
    if (x == 0.0 && y < 0) {
        return std::unexpected(Error::zero_to_negative_power);
    }
    if (x < 0 && y != std::floor(y) && std::isfinite(y)) {
        return std::unexpected(Error::negative_to_fractional_power);
    }
    const double r = strict::pow(x, y);
    if (std::isinf(r) && std::isfinite(x) && std::isfinite(y)) {
        return std::unexpected(Error::power_out_of_range);
    }
    return r;
}

double sqrt(double x, std::source_location where) noexcept { return value_or_fail(checked::sqrt(x), where); }
double asin(double x, std::source_location where) noexcept { return value_or_fail(checked::asin(x), where); }
double acos(double x, std::source_location where) noexcept { return value_or_fail(checked::acos(x), where); }
double exp(double x, std::source_location where) noexcept { return value_or_fail(checked::exp(x), where); }
double sinh(double x, std::source_location where) noexcept { return value_or_fail(checked::sinh(x), where); }
double log(double x, std::source_location where) noexcept { return value_or_fail(checked::log(x), where); }

double log(double x, double base, std::source_location where) noexcept {
    return value_or_fail(checked::log(x, base), where);
}

double log10(double x, std::source_location where) noexcept { return value_or_fail(checked::log10(x), where); }
double log2(double x, std::source_location where) noexcept { return value_or_fail(checked::log2(x), where); }
double pow(double x, double y, std::source_location where) noexcept { return value_or_fail(checked::pow(x, y), where); }

double pow(double x, int n, std::source_location where) noexcept {
    return value_or_fail(checked::pow(x, static_cast<double>(n)), where);
}

double floor(double x) noexcept { return std::floor(x); }
double ceil(double x) noexcept { return std::ceil(x); }
double copysign(double x, double y) noexcept { return std::copysign(x, y); } // Math.copySign: the raw sign bit
double fabs(double x) noexcept { return std::fabs(x); }

bool isclose(double a, double b, double rel_tol, double abs_tol) noexcept {
    if (a == b) {
        return true;
    }
    if (std::isinf(a) || std::isinf(b)) {
        return false;
    }
    const double diff = std::fabs(b - a);
    return (diff <= std::fabs(rel_tol * b)) || (diff <= std::fabs(rel_tol * a)) || (diff <= abs_tol);
}

int frexp_exp(double x) noexcept {
    if (x == 0.0 || !std::isfinite(x)) {
        return 0;
    }
    const int g = get_exponent(x);
    if (g == -1023) { // Double.MIN_EXPONENT - 1: a subnormal
        return get_exponent(x * two_54) + 1 - 54;
    }
    return g + 1;
}

Frexp frexp(double x) noexcept {
    if (x == 0.0 || !std::isfinite(x)) {
        return {.mantissa = x, .exponent = 0};
    }
    const int ex = frexp_exp(x);
    return {.mantissa = scalb(x, -ex), .exponent = ex};
}

double ldexp(double x, int i) noexcept { return scalb(x, i); }

} // namespace atlas::pymath
