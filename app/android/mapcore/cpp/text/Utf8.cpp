#include "text/Utf8.hpp"

#include <cstddef>
#include <utility>

namespace atlas::text {

std::u32string code_points(std::string_view utf8) {
    std::u32string out;
    out.reserve(utf8.size());
    const auto* p = reinterpret_cast<const unsigned char*>(utf8.data());
    const auto* end = p + utf8.size();
    while (p < end) {
        const unsigned char b = *p;
        char32_t c = 0;
        std::size_t length = 0;
        char32_t least = 0;
        if (b < 0x80) {
            c = b;
            length = 1;
        } else if ((b & 0xE0U) == 0xC0U) {
            c = b & 0x1FU;
            length = 2;
            least = 0x80;
        } else if ((b & 0xF0U) == 0xE0U) {
            c = b & 0x0FU;
            length = 3;
            least = 0x800;
        } else if ((b & 0xF8U) == 0xF0U) {
            c = b & 0x07U;
            length = 4;
            least = 0x10000;
        }
        bool valid = length != 0 && std::cmp_greater_equal(end - p, length);
        for (std::size_t k = 1; valid && k < length; ++k) {
            valid = (p[k] & 0xC0U) == 0x80U;
            c = (c << 6U) | (p[k] & 0x3FU);
        }
        valid = valid && c >= least && c <= 0x10FFFF && (c < 0xD800 || c > 0xDFFF);
        if (valid) {
            out.push_back(c);
            p += length;
        } else {
            out.push_back(0xFFFD);
            ++p;
        }
    }
    return out;
}

} // namespace atlas::text
