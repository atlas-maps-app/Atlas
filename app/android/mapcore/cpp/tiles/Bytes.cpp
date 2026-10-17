#include "tiles/Bytes.hpp"

namespace atlas::tiles {

bool valid_utf8(std::span<const std::byte> text) noexcept {
    const std::size_t n = text.size();
    std::size_t i = 0;
    auto at = [&](std::size_t k) { return std::to_integer<unsigned>(text[k]); };
    auto continuation = [&](std::size_t k) { return k < n && (at(k) & 0xC0U) == 0x80U; };
    while (i < n) {
        const unsigned b = at(i);
        if (b < 0x80U) {
            ++i;
            continue;
        }
        if (b >= 0xC2U && b <= 0xDFU) { // two bytes, U+0080 to U+07FF
            if (!continuation(i + 1)) return false;
            i += 2;
        } else if (b >= 0xE0U && b <= 0xEFU) { // three bytes, without overlong forms and surrogates
            if (!continuation(i + 1) || !continuation(i + 2)) return false;
            const unsigned b1 = at(i + 1);
            if ((b == 0xE0U && b1 < 0xA0U) || (b == 0xEDU && b1 > 0x9FU)) return false;
            i += 3;
        } else if (b >= 0xF0U && b <= 0xF4U) { // four bytes, up to U+10FFFF
            if (!continuation(i + 1) || !continuation(i + 2) || !continuation(i + 3)) return false;
            const unsigned b1 = at(i + 1);
            if ((b == 0xF0U && b1 < 0x90U) || (b == 0xF4U && b1 > 0x8FU)) return false;
            i += 4;
        } else {
            return false; // a continuation byte first, an overlong lead (C0, C1) or past U+10FFFF (F5 and up)
        }
    }
    return true;
}

} // namespace atlas::tiles
