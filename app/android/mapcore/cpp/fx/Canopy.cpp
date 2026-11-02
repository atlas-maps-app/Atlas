// The regions' canopy on the CPU (fx/Canopy.hpp).
#include "fx/Canopy.hpp"

#include "base/pymath/PyMath.hpp"
#include "fx/ByteTables.hpp"

namespace atlas::fx {

namespace {

/// The canopy's feColorMatrix as regions.py writes it.
[[nodiscard]] const bytes::ColourMatrix& canopy_matrix() noexcept {
    static const bytes::ColourMatrix m({0.0F, 0.0F, 0.0F, 0.0F, 0.16F, 0.0F,  0.0F, 0.0F, 0.0F, 0.24F,
                                        0.0F, 0.0F, 0.0F, 0.0F, 0.12F, -3.4F, 0.0F, 0.0F, 0.0F, 2.1F});
    return m;
}

/// The filter's result over a pixel the path covers whole for each alpha, by the matrix `m`.
[[nodiscard]] std::array<Argb, 256> table_of(const bytes::ColourMatrix& m) noexcept {
    // the colour rows' constants, unpremultiplied: the matrix on a red whose alpha is whole (fma(2.1, 255, 0) is
    // 535.5, so red 0's)
    const Argb c = m.apply(bytes::pack(255, 0, 0, 0));
    std::array<Argb, 256> t{};
    for (std::uint32_t a = 1; a < 256; ++a) {
        const Argb linear =
            bytes::premultiply(bytes::pack(a, bytes::channel(c, 16), bytes::channel(c, 8), bytes::channel(c, 0)));
        t[a] = bytes::premultiply(bytes::unpremultiply_exact(linear, false));
    }
    return t;
}

} // namespace

const std::array<std::uint8_t, 256>& canopy_alpha() noexcept {
    static const std::array<std::uint8_t, 256> table = [] {
        std::array<std::uint8_t, 256> t{};
        for (std::uint32_t r = 0; r < 256; ++r) {
            // the alpha row reads the red alone: the pixel's other channels change nothing
            t[r] = static_cast<std::uint8_t>(canopy_matrix().apply(bytes::pack(255, r, 0, 0)) >> 24U);
        }
        return t;
    }();
    return table;
}

const std::array<Argb, 256>& canopy_table() noexcept {
    static const std::array<Argb, 256> table = table_of(canopy_matrix());
    return table;
}

std::array<Argb, 256> canopy_table_of(std::uint32_t rgb) noexcept {
    const auto linear = [](std::uint32_t v) {
        const double c = static_cast<double>(v & 0xFFU) / 255.0;
        return static_cast<float>(c <= 0.04045 ? c / 12.92 : pymath::strict::pow((c + 0.055) / 1.055, 2.4));
    };
    const bytes::ColourMatrix m({0.0F, 0.0F, 0.0F, 0.0F, linear(rgb >> 16U), 0.0F,  0.0F, 0.0F, 0.0F, linear(rgb >> 8U),
                                 0.0F, 0.0F, 0.0F, 0.0F, linear(rgb),        -3.4F, 0.0F, 0.0F, 0.0F, 2.1F});
    return table_of(m);
}

} // namespace atlas::fx
