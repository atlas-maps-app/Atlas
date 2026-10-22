// UTF-8 text as code points, as HarfBuzz reads it: what the shaper (text/Shaper.hpp) and stage P's loads (Python's
// `str` of a tag value: its length, its digits, its case) read text by. Apart from the shaper, so stage P builds
// without the text stack (the packer's tool, stagep/).
#pragma once

#include <string>
#include <string_view>

namespace atlas::text {

/// The UTF-8 text's code points; invalid bytes become U+FFFD, as HarfBuzz reads them. Nothing on overlong input.
[[nodiscard]] std::u32string code_points(std::string_view utf8);

} // namespace atlas::text
