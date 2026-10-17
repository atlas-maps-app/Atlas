// pymath (plan-vector T3, K3b): the port's numerics in C++, so the core computes the port's bits on every libm.
//
// - strict:: is Java's StrictMath, which Java defines as fdlibm 5.3: sin, cos, tan, asin, acos, atan, atan2, exp, log,
//   log10, pow, cbrt, expm1, log1p and sinh, ported from fdlibm's C (base/pymath/fdlibm/). The platform's libm is never
//   used: Bionic, glibc and Apple's libm each round differently in the last bit.
// - pymath:: is the port's PyMath (R/py/PyMath.kt): Python's arithmetic where it differs from C++'s (floor `%` and
// `//`,
//   `round()`, `int()`, `max`/`min` order, `sum()`), and the `math` functions the renders use, with CPython's `hypot`
//   and `dist` (its vector_norm) and Python's error checks.
//
// Where Python raises, the port throws and the render ends; here the same condition ends the process through the core's
// one failure handler (base/assert.hpp), naming Python's message and the caller's line. checked:: returns the same
// results as values, for code that must handle them (and for the parity tests).
#pragma once

#include <bit>
#include <cmath>
#include <cstdint>
#include <expected>
#include <initializer_list>
#include <numbers>
#include <source_location>
#include <span>
#include <string_view>

namespace atlas::pymath {

/// Which CPython the port reproduces where CPython versions differ in the last bit (the port's PyCompat): SipHash-2-4
/// or -1-3 for str and bytes, plain or compensated `sum()`, and the 3.9 or 3.12 `hypot`. 3.9 made the reference
/// renders.
struct Compat {
    int minor = 9; ///< CPython 3.<minor>; 3.10 and 3.11 get the nearer of 3.9 and 3.12, as in the port

    /// SipHash's compression rounds for str and bytes: 2 (SipHash-2-4) up to 3.10, 1 (SipHash-1-3) from 3.11.
    [[nodiscard]] constexpr int sip_rounds() const noexcept { return minor >= 11 ? 1 : 2; }
    /// Whether `sum()` of floats is compensated (Neumaier, 3.12 and later).
    [[nodiscard]] constexpr bool compensated_sum() const noexcept { return minor >= 12; }
    /// Whether `math.hypot` and `math.dist` use 3.12's lossless squaring with a differential correction.
    [[nodiscard]] constexpr bool modern_hypot() const noexcept { return minor >= 12; }
};

/// The Python of the owner's reference renders, CPython 3.9: the port's default and the core's.
inline constexpr Compat renders{};

/// Why Python would raise where the port throws ArithmeticException (or IllegalArgumentException).
enum class Error : std::uint8_t {
    float_modulo,                 ///< ZeroDivisionError: x % 0.0
    float_floor_division,         ///< ZeroDivisionError: x // 0.0
    integer_division,             ///< ZeroDivisionError: an int divided by zero
    domain,                       ///< ValueError: math domain error
    range,                        ///< OverflowError: math range error
    not_finite,                   ///< ValueError or OverflowError: an infinity or NaN converted to int
    too_large,                    ///< the int does not fit in 64 bits (the port's longValueExact)
    zero_to_negative_power,       ///< ZeroDivisionError: 0.0 ** negative
    negative_to_fractional_power, ///< ValueError: negative ** fractional
    power_out_of_range,           ///< OverflowError: x ** y overflows
    dimensions,                   ///< ValueError: math.dist's points differ in length
    not_hashable,                 ///< hash(nan), since 3.10 an object's address: not reproducible
    empty_sequence,               ///< IndexError: random.choice of an empty sequence
    empty_range,                  ///< ValueError: an empty range for random.randrange
    invalid_text,                 ///< a str that is not well-formed UTF-8, so Python never had it
};

/// Python's message for an error, as the port throws it.
[[nodiscard]] std::string_view message(Error error) noexcept;

/// Ends the process for a Python error at the caller's line: the render would have raised and stopped here.
[[noreturn]] void fail(Error error, std::source_location where = std::source_location::current()) noexcept;

/// A result or the Python error the port throws for its inputs.
template <class T>
using Result = std::expected<T, Error>;

/// The value of a result, or the end of the process with its Python error at the caller's line.
template <class T>
[[nodiscard]] constexpr T value_or_fail(const Result<T>& result, std::source_location where) noexcept {
    if (!result.has_value()) [[unlikely]] {
        fail(result.error(), where);
    }
    return *result;
}

// ---------------------------------------------------------------- StrictMath: fdlibm 5.3

/// Java's StrictMath, bit for bit: fdlibm 5.3 as Java defines it, IEEE special values included, no Python checks.
namespace strict {
[[nodiscard]] double sin(double x) noexcept;
[[nodiscard]] double cos(double x) noexcept;
[[nodiscard]] double tan(double x) noexcept;
[[nodiscard]] double asin(double x) noexcept;
[[nodiscard]] double acos(double x) noexcept;
[[nodiscard]] double atan(double x) noexcept;
[[nodiscard]] double atan2(double y, double x) noexcept;
[[nodiscard]] double exp(double x) noexcept;
[[nodiscard]] double log(double x) noexcept;
[[nodiscard]] double log10(double x) noexcept;
/// x ** y as fdlibm 5.3's e_pow.c computes it, with the fix its readme lists for x near -1 and huge y; Java's
/// StrictMath.pow (FdLibm.Pow) returns the same bits.
[[nodiscard]] double pow(double x, double y) noexcept;
[[nodiscard]] double cbrt(double x) noexcept;
[[nodiscard]] double expm1(double x) noexcept;
[[nodiscard]] double log1p(double x) noexcept;
/// fdlibm 5.3's e_sinh.c, which StrictMath.sinh is (the tile reader's row edges: atan(sinh(n)), K3).
[[nodiscard]] double sinh(double x) noexcept;
} // namespace strict

// ---------------------------------------------------------------- constants

inline constexpr double pi = std::numbers::pi; ///< math.pi, Math.PI
inline constexpr double e = std::numbers::e;   ///< math.e, Math.E
inline constexpr double tau = 2 * pi;          ///< math.tau

// ---------------------------------------------------------------- results as values

/// The functions below that Python can raise from, returning the error instead of ending the process.
namespace checked {
[[nodiscard]] Result<double> mod(double a, double b) noexcept;
[[nodiscard]] Result<std::int64_t> mod(std::int64_t a, std::int64_t b) noexcept;
[[nodiscard]] Result<double> floor_div(double a, double b) noexcept;
[[nodiscard]] Result<std::int64_t> floor_div(std::int64_t a, std::int64_t b) noexcept;
[[nodiscard]] Result<double> fmod(double a, double b) noexcept;
[[nodiscard]] Result<std::int64_t> round(double x) noexcept;
[[nodiscard]] Result<std::int64_t> to_int(double x) noexcept;
[[nodiscard]] Result<std::int64_t> floor_int(double x) noexcept;
[[nodiscard]] Result<std::int64_t> ceil_int(double x) noexcept;
[[nodiscard]] Result<double> sqrt(double x) noexcept;
[[nodiscard]] Result<double> asin(double x) noexcept;
[[nodiscard]] Result<double> acos(double x) noexcept;
[[nodiscard]] Result<double> exp(double x) noexcept;
[[nodiscard]] Result<double> sinh(double x) noexcept;
[[nodiscard]] Result<double> log(double x) noexcept;
[[nodiscard]] Result<double> log(double x, double base) noexcept;
[[nodiscard]] Result<double> log10(double x) noexcept;
[[nodiscard]] Result<double> log2(double x) noexcept;
[[nodiscard]] Result<double> pow(double x, double y) noexcept;
[[nodiscard]] Result<double> dist(std::span<const double> p, std::span<const double> q,
                                  Compat compat = renders) noexcept;
} // namespace checked

// ---------------------------------------------------------------- Python's operators

/// Python's `a % b` on floats: the result takes the sign of `b`.
[[nodiscard]] double mod(double a, double b, std::source_location where = std::source_location::current()) noexcept;
/// Python's `a % b` on ints.
[[nodiscard]] std::int64_t mod(std::int64_t a, std::int64_t b,
                               std::source_location where = std::source_location::current()) noexcept;
/// Python's `a // b` on floats.
[[nodiscard]] double floor_div(double a, double b,
                               std::source_location where = std::source_location::current()) noexcept;
/// Python's `a // b` on ints.
[[nodiscard]] std::int64_t floor_div(std::int64_t a, std::int64_t b,
                                     std::source_location where = std::source_location::current()) noexcept;

/// Python's `divmod(a, b)`.
template <class T>
struct DivMod {
    T quotient;
    T remainder;
};
[[nodiscard]] DivMod<double> divmod(double a, double b,
                                    std::source_location where = std::source_location::current()) noexcept;
[[nodiscard]] DivMod<std::int64_t> divmod(std::int64_t a, std::int64_t b,
                                          std::source_location where = std::source_location::current()) noexcept;

/// `math.fmod`: the C remainder, with the sign of `a`.
[[nodiscard]] double fmod(double a, double b, std::source_location where = std::source_location::current()) noexcept;

/// Python's `round(x)`: the nearest int, ties to even.
[[nodiscard]] std::int64_t round(double x, std::source_location where = std::source_location::current()) noexcept;
/// Python's `round(x, n)` for a float: the double nearest to `x` correctly rounded to `n` decimals, ties to even.
[[nodiscard]] double round(double x, int n) noexcept;
/// Python's `round(n, k)` for an int: `n` for k >= 0, else the nearest multiple of 10^-k, ties to even.
[[nodiscard]] std::int64_t round(std::int64_t n, int k) noexcept;

/// Python's `int(x)` for a float: truncation toward zero.
[[nodiscard]] std::int64_t to_int(double x, std::source_location where = std::source_location::current()) noexcept;
/// `math.floor(x)` as an int.
[[nodiscard]] std::int64_t floor_int(double x, std::source_location where = std::source_location::current()) noexcept;
/// `math.ceil(x)` as an int.
[[nodiscard]] std::int64_t ceil_int(double x, std::source_location where = std::source_location::current()) noexcept;

namespace detail {
/// `v`, hidden from the optimiser. clang (Apple's 21 and the NDK r30's) folds `x < 0.0 ? x : 0.0` and its mirror images
/// into arm64's fminnm and fmaxnm, which order -0.0 below +0.0 where Python keeps the first of equal values, so
/// min(0.0, -0.0) came out -0.0 on arm64 and 0.0 on x86_64. An opaque comparison keeps the select a select.
[[nodiscard]] inline bool opaque(bool v) noexcept {
    asm("" : "+r"(v)); // an optimisation barrier: no instruction
    return v;
}
} // namespace detail

/// Python's `max(a, b)`: the first of equal values, and `b` only when `b > a` (so NaN and signed zeros as Python).
[[nodiscard]] constexpr double max(double a, double b) noexcept {
    if consteval {
        return b > a ? b : a;
    }
    return detail::opaque(b > a) ? b : a;
}
/// Python's `min(a, b)`: the first of equal values, and `b` only when `b < a`.
[[nodiscard]] constexpr double min(double a, double b) noexcept {
    if consteval {
        return b < a ? b : a;
    }
    return detail::opaque(b < a) ? b : a;
}
/// Python's `max(a, b, c, ...)` of at least one value.
[[nodiscard]] constexpr double max(std::initializer_list<double> values) noexcept {
    const double* it = values.begin();
    double m = *it;
    for (++it; it != values.end(); ++it) {
        m = max(m, *it);
    }
    return m;
}
/// Python's `min(a, b, c, ...)` of at least one value.
[[nodiscard]] constexpr double min(std::initializer_list<double> values) noexcept {
    const double* it = values.begin();
    double m = *it;
    for (++it; it != values.end(); ++it) {
        m = min(m, *it);
    }
    return m;
}

// ---------------------------------------------------------------- sum

/// Python's `sum()` of floats, as the CPython of `compat` adds them (0.0 for none).
[[nodiscard]] double sum(std::span<const double> values, Compat compat = renders) noexcept;

/// An incremental Python `sum()` of floats, for loops that build no array.
class Summer {
public:
    explicit constexpr Summer(Compat compat = renders) noexcept : compensated_(compat.compensated_sum()) {}

    /// Adds one float item.
    void add(double x) noexcept {
        if (first_) {
            f_ = 0.0 + x; // CPython starts from the int 0
            first_ = false;
            return;
        }
        if (!compensated_) {
            f_ += x;
            return;
        }
        const double t = f_ + x; // Neumaier's compensation, as CPython 3.12 adds
        c_ += std::fabs(f_) >= std::fabs(x) ? (f_ - t) + x : (x - t) + f_;
        f_ = t;
    }

    /// The sum so far.
    [[nodiscard]] double value() const noexcept {
        return compensated_ && c_ != 0.0 && std::isfinite(c_) ? f_ + c_ : f_;
    }

private:
    double f_ = 0.0;
    double c_ = 0.0;
    bool first_ = true;
    bool compensated_ = false;
};

// ---------------------------------------------------------------- the math module

[[nodiscard]] double sqrt(double x, std::source_location where = std::source_location::current()) noexcept;
[[nodiscard]] inline double sin(double x) noexcept { return strict::sin(x); }
[[nodiscard]] inline double cos(double x) noexcept { return strict::cos(x); }
[[nodiscard]] inline double tan(double x) noexcept { return strict::tan(x); }
[[nodiscard]] double asin(double x, std::source_location where = std::source_location::current()) noexcept;
[[nodiscard]] double acos(double x, std::source_location where = std::source_location::current()) noexcept;
[[nodiscard]] inline double atan(double x) noexcept { return strict::atan(x); }
[[nodiscard]] inline double atan2(double y, double x) noexcept { return strict::atan2(y, x); }
[[nodiscard]] double exp(double x, std::source_location where = std::source_location::current()) noexcept;
/// `math.sinh(x)`: a range error where it overflows from a finite x.
[[nodiscard]] double sinh(double x, std::source_location where = std::source_location::current()) noexcept;
/// `math.log(x)`.
[[nodiscard]] double log(double x, std::source_location where = std::source_location::current()) noexcept;
/// `math.log(x, base)`: CPython divides the two natural logs.
[[nodiscard]] double log(double x, double base, std::source_location where = std::source_location::current()) noexcept;
[[nodiscard]] double log10(double x, std::source_location where = std::source_location::current()) noexcept;
/// `math.log2(x)`: exact for powers of two, else the quotient of two natural logs, as the port computes it.
[[nodiscard]] double log2(double x, std::source_location where = std::source_location::current()) noexcept;
/// `x ** y` on floats (and `math.pow`).
[[nodiscard]] double pow(double x, double y, std::source_location where = std::source_location::current()) noexcept;
/// `x ** n` for a float and an int exponent, as CPython computes it (through the C pow).
[[nodiscard]] double pow(double x, int n, std::source_location where = std::source_location::current()) noexcept;

[[nodiscard]] double floor(double x) noexcept;
[[nodiscard]] double ceil(double x) noexcept;

/// `math.radians(x)`: x times the double nearest pi/180.
[[nodiscard]] constexpr double radians(double x) noexcept { return x * (pi / 180.0); }
/// `math.degrees(x)`: x times the double nearest 180/pi.
[[nodiscard]] constexpr double degrees(double x) noexcept { return x * (180.0 / pi); }
[[nodiscard]] double copysign(double x, double y) noexcept;
[[nodiscard]] double fabs(double x) noexcept;

/// `math.isclose(a, b, rel_tol, abs_tol)`.
[[nodiscard]] bool isclose(double a, double b, double rel_tol = 1e-9, double abs_tol = 0.0) noexcept;

/// The exponent `e` of `math.frexp(x)` (x = m * 2**e with 0.5 <= |m| < 1); 0 for zero, infinities and NaN.
[[nodiscard]] int frexp_exp(double x) noexcept;
/// `math.frexp(x)`.
struct Frexp {
    double mantissa;
    int exponent;
};
[[nodiscard]] Frexp frexp(double x) noexcept;
/// `math.ldexp(x, i)`: x * 2**i rounded once.
[[nodiscard]] double ldexp(double x, int i) noexcept;

/// `math.hypot(x, y)`.
[[nodiscard]] double hypot(double x, double y, Compat compat = renders) noexcept;
/// `math.hypot(*coordinates)`.
[[nodiscard]] double hypot(std::span<const double> coordinates, Compat compat = renders) noexcept;
/// `math.dist(p, q)`.
[[nodiscard]] double dist(std::span<const double> p, std::span<const double> q, Compat compat = renders,
                          std::source_location where = std::source_location::current()) noexcept;

/// Java's own numerics, where the Kotlin beside the port (the tile writer's grid and columns, the packer) rounds as
/// Java does rather than as Python does.
namespace java {
/// Java's Math.round(double): floor(x + 1/2) of the exact value (halves round up), 0 for NaN, Long's limits beyond
/// them. Kotlin's Double.roundToLong() is the same, and roundToInt() this clamped to Int's limits (NaN throws there).
[[nodiscard]] std::int64_t round(double x) noexcept;

/// Java's Math.max(double, double) (Kotlin's maxOf): a NaN wins, and -0.0 is below +0.0, unlike Python's max. JDK
/// 17's own steps, the last comparison opaque as in pymath::max, so clang cannot fold it into fmaxnm.
[[nodiscard]] constexpr double max(double a, double b) noexcept {
    if (a != a) {
        return a; // a is NaN
    }
    if (a == 0.0 && b == 0.0 && (std::bit_cast<std::uint64_t>(a) >> 63U) != 0) {
        return b; // -0.0 and a zero: the zero b
    }
    if consteval {
        return a >= b ? a : b;
    }
    return detail::opaque(a >= b) ? a : b; // a NaN b falls through to b
}

/// Java's Math.min(double, double) (Kotlin's minOf): a NaN wins, and -0.0 is below +0.0.
[[nodiscard]] constexpr double min(double a, double b) noexcept {
    if (a != a) {
        return a; // a is NaN
    }
    if (a == 0.0 && b == 0.0 && (std::bit_cast<std::uint64_t>(b) >> 63U) != 0) {
        return b; // a zero and -0.0: the -0.0
    }
    if consteval {
        return a <= b ? a : b;
    }
    return detail::opaque(a <= b) ? a : b;
}
} // namespace java

/// The exact rounding error of the product `p = a * b` by Dekker's split, as the port's ExactMath.productError
/// computes it (not a fused multiply-add: the two differ where the split's partial products underflow).
[[nodiscard]] double product_error(double a, double b, double p) noexcept;

} // namespace atlas::pymath
