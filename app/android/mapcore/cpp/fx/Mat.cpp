// `mat`'s byte arithmetic (fx/Mat.hpp), SvgFilterRaster's steps in its order.
#include "fx/Mat.hpp"

#include "fx/ByteTables.hpp"

#include <algorithm>

namespace atlas::fx {

std::uint32_t mat_arithmetic(std::uint32_t c, std::uint32_t g) noexcept {
    const auto i1 = static_cast<float>(c);
    const auto i2 = static_cast<float>(g);
    // CompositeNode's arithmetic, its terms in its order: k2 · i1, then k3 · i2 (k3 is 0), then (i1 · i2) · k1
    float r = i1 * mat_k2;
    r += i2 * 0.0F;
    r += (i1 * i2) * mat_k1;
    if (r <= 0.0F) return 0;
    const auto v = static_cast<std::uint32_t>(static_cast<std::int32_t>(r)); // WebKit's truncation (r < 2^31 here)
    return std::min(v, 255U);
}

Argb mat_pixel(Argb p, std::uint32_t g) noexcept {
    // the source as the raster reads it: drawn into a linear-RGB buffer, premultiplied
    const Argb src = bytes::srgb_to_linear_premultiplied(p);
    // the arithmetic composite with the colour matrix's result, an opaque grey g
    const std::uint32_t ta = mat_arithmetic(src >> 24U, 255);
    // read by the next primitive corrected to valid premultiplied colour (its colour at most its alpha)
    const std::uint32_t tr = std::min(mat_arithmetic(bytes::channel(src, 16), g), ta);
    const std::uint32_t tg = std::min(mat_arithmetic(bytes::channel(src, 8), g), ta);
    const std::uint32_t tb = std::min(mat_arithmetic(bytes::channel(src, 0), g), ta);
    // `in` SourceAlpha: scaled by the source's own alpha
    const Argb q = bytes::scale(bytes::pack(ta, tr, tg, tb), p >> 24U);
    // the filter's result, written as premultiplied sRGB (never corrected)
    return bytes::premultiply(bytes::unpremultiply_exact(q, false));
}

std::uint32_t mat_opaque(std::uint32_t c, std::uint32_t g) noexcept {
    return bytes::channel(mat_pixel(bytes::pack(255, c, c, c), g), 16);
}

std::span<const std::uint8_t, 65536> mat_table() noexcept {
    static const std::array<std::uint8_t, 65536> table = [] {
        std::array<std::uint8_t, 65536> t{};
        for (std::uint32_t c = 0; c < 256; ++c) {
            for (std::uint32_t g = 0; g < 256; ++g) t[(c * 256) + g] = static_cast<std::uint8_t>(mat_opaque(c, g));
        }
        return t;
    }();
    return table;
}

} // namespace atlas::fx
