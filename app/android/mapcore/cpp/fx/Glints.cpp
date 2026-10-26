// The water's glints on the CPU (fx/Glints.hpp).
#include "fx/Glints.hpp"

#include "base/pymath/PyMath.hpp"
#include "fx/ByteTables.hpp"

namespace atlas::fx {

std::array<float, 3> glint_colour(bool night) noexcept {
    if (!night) return {1.0F, 1.0F, 1.0F};
    return {0.55F, 0.62F, 0.8F};
}

double glint_opacity(double wind, bool night) noexcept {
    // rip = min(0.4, 0.08 + w['wind'] * 0.012), '%.2f' % (rip if not night else 0.18)
    if (night) return 0.18;
    return pymath::round(pymath::min(0.4, 0.08 + (wind * 0.012)), 2);
}

std::array<Argb, 256> glint_table(const std::array<float, 3>& gl) noexcept {
    const bytes::ColourMatrix m({0.0F, 0.0F, 0.0F, 0.0F, gl[0], 0.0F,  0.0F, 0.0F, 0.0F, gl[1],
                                 0.0F, 0.0F, 0.0F, 0.0F, gl[2], 11.0F, 0.0F, 0.0F, 0.0F, -6.9F});
    std::array<Argb, 256> out{};
    for (std::uint32_t r = 0; r < 256; ++r) {
        // the matrix's rows read the red alone: the pixel's other channels change nothing
        const Argb linear = m.apply(bytes::pack(255, r, 0, 0));
        out[r] = (linear >> 24U) == 0 ? 0 : bytes::premultiply(bytes::unpremultiply_exact(linear, false));
    }
    return out;
}

} // namespace atlas::fx
