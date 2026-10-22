// Python's `str.capitalize()` as the port computes it (ENGINE.md step 3, stage P: poi_style.py's kind label of a shop
// it has no name for, `t['shop'].replace('_', ' ').capitalize()`). The port's PoiStyle.capitalize takes Java's
// Character.toTitleCase of the first code point and Kotlin's lowercase() (String.toLowerCase(Locale.ROOT)) of the rest;
// the tables (text/Case.inc, from CPython 3.9's Unicode 13.0, which is JDK 17's) give the same code points, and
// :mapcorecheck's CaseParityTest holds them to Java's over every code point.
//
// Where they still part: Java lowercases a capital sigma to the final ς when a cased letter comes before it within its
// word and none after, by Java's word breaks; here, when the code point just before it is cased and the one just after
// is not. The two agree for a sigma between letters, spaces, punctuation and the text's ends, and part only where a
// mark, an apostrophe or a digit stands between it and the letters of its word.
#pragma once

#include <string>
#include <string_view>

namespace atlas::text {

/// The port's `s.capitalize()` of UTF-8 text: the first code point titlecased, the rest lowercased (İ to i̇, Σ to ς at
/// a word's end). Invalid UTF-8 bytes become U+FFFD.
[[nodiscard]] std::string capitalize(std::string_view utf8);

/// Kotlin's `s.lowercase()` (Java's toLowerCase(Locale.ROOT)) of UTF-8 text: each code point's lowercase, İ to i̇, Σ to
/// ς at a word's end (by the code points beside it, as capitalize() decides it). Invalid UTF-8 bytes become U+FFFD.
[[nodiscard]] std::string lowercase(std::string_view utf8);

} // namespace atlas::text
