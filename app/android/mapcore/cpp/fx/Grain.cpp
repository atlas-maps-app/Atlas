// The paper grain's reference (fx/Grain.hpp).
#include "fx/Grain.hpp"

#include "fx/Fields.hpp"

#include <array>

namespace atlas::fx {

void apply_grain(std::span<std::uint8_t> rgb, const ScreenFields& fields, bool night) noexcept {
    const FieldSize size = fields.size();
    const std::uint32_t opacity = skia_byte(night ? grain_opacity_night : grain_opacity_day);
    for (std::uint32_t y = 0; y < size.height; ++y) {
        for (std::uint32_t x = 0; x < size.width; ++x) {
            const std::array<std::uint8_t, 2> grain = fields.at(Field::grain, x, y);
            const std::size_t at = ((static_cast<std::size_t>(y) * size.width) + x) * 3;
            if (at + 3 > rgb.size()) return;
            for (std::size_t c = 0; c < 3; ++c) {
                rgb[at + c] = static_cast<std::uint8_t>(grain_overlay(rgb[at + c], grain[0], grain[1], opacity));
            }
        }
    }
}

} // namespace atlas::fx
