#include "load/Real.hpp"

#include "base/pymath/PyMath.hpp"
#include "text/Utf8.hpp"

#include <algorithm>
#include <array>
#include <iterator>
#include <string>

namespace atlas::load {

namespace {

#include "text/Digits.inc"

} // namespace

bool is_space(char32_t c) noexcept {
    switch (c) {
    case 0x09:
    case 0x0A:
    case 0x0B:
    case 0x0C:
    case 0x0D:
    case 0x20:
    case 0x85:
    case 0xA0:
    case 0x1680:
    case 0x2028:
    case 0x2029:
    case 0x202F:
    case 0x205F:
    case 0x3000: return true;
    default: return (c >= 0x1C && c <= 0x1F) || (c >= 0x2000 && c <= 0x200A);
    }
}

std::optional<int> digit_of(char32_t c) noexcept {
    if (c >= 0x10000) return std::nullopt;
    const auto* const after = std::ranges::upper_bound(digit_zeros, c);
    if (after == digit_zeros.begin()) return std::nullopt;
    const char32_t value = c - *std::prev(after);
    if (value >= 10) return std::nullopt;
    return static_cast<int>(value);
}

std::optional<double> num(std::optional<std::string_view> text) noexcept {
    if (!text) return std::nullopt;
    const std::u32string cps = text::code_points(*text);
    std::size_t i = 0;
    while (i < cps.size() && is_space(cps[i])) ++i;
    const bool negative = i < cps.size() && cps[i] == U'-';
    if (negative) ++i;
    std::string digits;
    while (i < cps.size()) {
        const auto d = digit_of(cps[i]);
        if (!d) break;
        digits += static_cast<char>('0' + *d);
        ++i;
    }
    if (digits.empty()) return std::nullopt;
    std::int64_t e10 = 0;
    if (i + 1 < cps.size() && cps[i] == U'.' && digit_of(cps[i + 1])) {
        ++i;
        while (i < cps.size()) {
            const auto d = digit_of(cps[i]);
            if (!d) break;
            digits += static_cast<char>('0' + *d);
            --e10;
            ++i;
        }
    }
    const double v = pymath::decimal_to_double(digits, e10);
    return negative ? -v : v;
}

std::optional<std::int64_t> leading_year(std::string_view text) noexcept {
    const std::u32string cps = text::code_points(text);
    std::size_t i = 0;
    while (i < cps.size() && is_space(cps[i])) ++i;
    if (i + 4 > cps.size()) return std::nullopt;
    std::int64_t year = 0;
    for (std::size_t k = i; k < i + 4; ++k) {
        const auto d = digit_of(cps[k]);
        if (!d) return std::nullopt;
        year = year * 10 + *d;
    }
    return year;
}

} // namespace atlas::load
