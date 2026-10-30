// The regions' canopy on the CPU (fx/Canopy.hpp).
#include "fx/Canopy.hpp"

#include "fx/ByteTables.hpp"

namespace atlas::fx {

namespace {

/// The canopy's feColorMatrix as regions.py writes it.
[[nodiscard]] const bytes::ColourMatrix& canopy_matrix() noexcept {
    static const bytes::ColourMatrix m({0.0F, 0.0F, 0.0F, 0.0F, 0.16F, 0.0F,  0.0F, 0.0F, 0.0F, 0.24F,
                                        0.0F, 0.0F, 0.0F, 0.0F, 0.12F, -3.4F, 0.0F, 0.0F, 0.0F, 2.1F});
    return m;
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
    static const std::array<Argb, 256> table = [] {
        // the colour rows' constants, unpremultiplied: the matrix on a red whose alpha is whole (fma(2.1, 255, 0) is
        // 535.5, so red 0's)
        const Argb c = canopy_matrix().apply(bytes::pack(255, 0, 0, 0));
        std::array<Argb, 256> t{};
        for (std::uint32_t a = 1; a < 256; ++a) {
            const Argb linear =
                bytes::premultiply(bytes::pack(a, bytes::channel(c, 16), bytes::channel(c, 8), bytes::channel(c, 0)));
            t[a] = bytes::premultiply(bytes::unpremultiply_exact(linear, false));
        }
        return t;
    }();
    return table;
}

} // namespace atlas::fx
