// The sky layer's clouds on the CPU (fx/Clouds.hpp).
#include "fx/Clouds.hpp"

#include "base/pymath/PyMath.hpp"
#include "fx/ByteTables.hpp"
#include "view/Snap.hpp"

#include <cmath>

namespace atlas::fx {

namespace {

/// What the SVG's reader makes of '%.<n>f' of v.
[[nodiscard]] float printed(double v, int n) noexcept { return static_cast<float>(view::decimals(v, n)); }

} // namespace

CloudMatrix cloud_matrix(double red, double green, double blue, double k, double t) noexcept {
    return {.red = printed(red, 2),
            .green = printed(green, 2),
            .blue = printed(blue, 2),
            .k = printed(k, 1),
            .offset = printed(-k * t, 2)};
}

CloudMatrix cloud_shadow_matrix(double red, double green, double blue, double k, double t) noexcept {
    return {.red = printed(red * 0.62, 2),
            .green = printed(green * 0.64, 2),
            .blue = printed(blue * 0.7, 2),
            .k = printed(k, 1),
            .offset = printed(-k * (t + 0.04), 2)};
}

std::array<Argb, 256> cloud_table(const CloudMatrix& m) noexcept {
    const bytes::ColourMatrix matrix({0.0F, 0.0F, 0.0F, 0.0F, m.red,  0.0F, 0.0F, 0.0F, 0.0F, m.green,
                                      0.0F, 0.0F, 0.0F, 0.0F, m.blue, m.k,  0.0F, 0.0F, 0.0F, m.offset});
    std::array<Argb, 256> out{};
    for (std::uint32_t r = 0; r < 256; ++r) {
        // the matrix's rows read the red alone: the pixel's other channels change nothing
        const Argb linear = matrix.apply(bytes::pack(255, r, 0, 0));
        out[r] = (linear >> 24U) == 0 ? 0 : bytes::premultiply(bytes::unpremultiply_exact(linear, false));
    }
    return out;
}

CloudOffset cloud_offset(float scale) noexcept {
    // OffsetNode: dy = fe.dy · sy in doubles (5 times the scale, exact), its floor and the fraction left
    const double dy = 5.0 * static_cast<double>(scale);
    const double rows = std::floor(dy);
    // the fraction in 1/2^24: whole at any scale from a half up (a float's last place there is 2^-24 or more)
    const double above = std::floor((dy - rows) * static_cast<double>(cloud_weight_one));
    return {.rows = static_cast<std::int32_t>(rows), .above = static_cast<std::uint32_t>(above)};
}

std::uint32_t cloud_rows(double sky_bottom, float scale, std::uint32_t height) noexcept {
    const double rows = std::ceil(std::trunc(sky_bottom) * static_cast<double>(scale));
    if (!(rows > 0.0)) return 0;
    return rows < static_cast<double>(height) ? static_cast<std::uint32_t>(rows) : height;
}

CloudReds cloud_reds(const ScreenFields& fields, CloudOffset offset, std::uint32_t x, std::uint32_t y) noexcept {
    const auto at = [&](std::int64_t row) -> std::array<std::uint8_t, 2> {
        if (row < 0) return {0, 0}; // above the region: nothing
        return fields.at(Field::clouds, x, static_cast<std::uint32_t>(row));
    };
    const std::array<std::uint8_t, 2> here = fields.at(Field::clouds, x, y);
    const std::int64_t up = static_cast<std::int64_t>(y) - offset.rows;
    const std::array<std::uint8_t, 2> p = at(up);
    const std::array<std::uint8_t, 2> q = offset.above != 0 ? at(up - 1) : std::array<std::uint8_t, 2>{0, 0};
    return {.shadow = cloud_red(cloud_blend(p[0], q[0], offset.above), cloud_blend(p[1], q[1], offset.above)),
            .cloud = cloud_red(here[0], here[1])};
}

} // namespace atlas::fx
