// What the loaders take from render/real.py and Python's text (ENGINE.md step 3, stage P), as the port computes it
// (R/Real.kt, R/PyRe.kt): a tag value's leading number and the year a date begins with. real.py's plane helpers
// (signed_area, pip, centroid, dist) are geometry/RoofShapes.hpp's.
//
// Python's `\s` and `\d` read Unicode, and the port tests each UTF-16 unit as Java does: so a space here is Python's
// (the port's PyRe.isSpace) and a digit a decimal digit of the Basic Multilingual Plane (Java's Character.isDigit of
// a unit, Unicode 13.0 as CPython 3.9's within the plane: text/Digits.inc); a code point beyond the plane is neither.
#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

namespace atlas::load {

/// `c.isspace()`, Python's `\s`.
[[nodiscard]] bool is_space(char32_t c) noexcept;

/// The value 0 to 9 of a decimal digit of the Basic Multilingual Plane, or nothing.
[[nodiscard]] std::optional<int> digit_of(char32_t c) noexcept;

/// real.py's `num(v)`: the number the text starts with, `re.match(r'\s*(-?\d+(?:\.\d+)?)', v)` read by float() ("12 m"
/// is 12.0, "-0" is -0.0), or nothing, as for no text (`num(t.get(key))`). Past the largest double it is infinite, as
/// float() makes it.
[[nodiscard]] std::optional<double> num(std::optional<std::string_view> text) noexcept;

/// `re.match(r'\s*(\d{4})', text)` read as an int: the year a start_date begins with, or nothing.
[[nodiscard]] std::optional<std::int64_t> leading_year(std::string_view text) noexcept;

} // namespace atlas::load
