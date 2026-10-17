// fdlibm's view of a double as two 32-bit words (its __HI and __LO macros), without the pointer casts that make the
// original C undefined behaviour under strict aliasing: std::bit_cast, so every access is defined and constexpr.
#pragma once

#include <bit>
#include <cstdint>

namespace atlas::pymath::fdlibm {

/// The high word: sign, exponent and the top 20 bits of the significand, as fdlibm's signed `int`.
[[nodiscard]] constexpr std::int32_t hi(double x) noexcept {
    return static_cast<std::int32_t>(static_cast<std::uint32_t>(std::bit_cast<std::uint64_t>(x) >> 32U));
}

/// The low word: the bottom 32 bits of the significand.
[[nodiscard]] constexpr std::uint32_t lo(double x) noexcept {
    return static_cast<std::uint32_t>(std::bit_cast<std::uint64_t>(x));
}

/// The double made of a high and a low word.
[[nodiscard]] constexpr double from_words(std::int32_t high, std::uint32_t low) noexcept {
    return std::bit_cast<double>((static_cast<std::uint64_t>(static_cast<std::uint32_t>(high)) << 32U) | low);
}

/// `x` with its high word replaced (fdlibm's `__HI(x) = high`).
[[nodiscard]] constexpr double with_hi(double x, std::int32_t high) noexcept { return from_words(high, lo(x)); }

/// `x` with its low word replaced (fdlibm's `__LO(x) = low`).
[[nodiscard]] constexpr double with_lo(double x, std::uint32_t low) noexcept { return from_words(hi(x), low); }

/// `x` with `delta` added to its high word as fdlibm's 32-bit `int` arithmetic does it (`__HI(x) += delta`), wrapping
/// where the C would overflow.
[[nodiscard]] constexpr double add_hi(double x, std::int32_t delta) noexcept {
    return with_hi(x, static_cast<std::int32_t>(static_cast<std::uint32_t>(hi(x)) + static_cast<std::uint32_t>(delta)));
}

/// fdlibm's `k << 20`, k moved into the high word's exponent field, for negative k too (two's complement).
[[nodiscard]] constexpr std::int32_t exponent(int k) noexcept {
    return static_cast<std::int32_t>(static_cast<std::uint32_t>(k) << 20U);
}

namespace detail {
/// Not constexpr: reached only when a constant's decimal value and its words disagree, which stops the compilation.
void constant_differs_from_its_words() noexcept;
} // namespace detail

/// One of fdlibm's constants, written as its decimal literal (as the C source compiles it) and checked at compile time
/// against the hexadecimal words fdlibm documents beside it, so neither can be mistyped.
consteval double constant(double value, std::uint32_t high, std::uint32_t low) {
    if (std::bit_cast<std::uint64_t>(value) != ((static_cast<std::uint64_t>(high) << 32U) | low)) {
        detail::constant_differs_from_its_words();
    }
    return value;
}

} // namespace atlas::pymath::fdlibm
