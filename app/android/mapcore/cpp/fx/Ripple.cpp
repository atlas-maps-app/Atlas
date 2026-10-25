// The water's ripple on the CPU (fx/Ripple.hpp): SvgFilterRaster's chain, step by step.
#include "fx/Ripple.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "fx/Blur.hpp"
#include "fx/ByteTables.hpp"

#include <array>
#include <cmath>
#include <cstddef>
#include <utility>

namespace atlas::fx {

RippleDisplacement ripple_displacement(float scale, float density) noexcept {
    // DisplacementNode: absX = (fe.scale * sx).toFloat(), the scale a float and sx the filter scale as a double;
    // (absX / 255.0).toFloat(), (0.5 - absX * 0.5).toFloat(); the reach ceil(abs(absX) / 2.0 + 1)
    const auto abs = static_cast<float>(static_cast<double>(scale) * static_cast<double>(density));
    const auto wide = static_cast<double>(abs);
    return {.factor = static_cast<float>(wide / 255.0),
            .offset = static_cast<float>(0.5 - (wide * 0.5)),
            .reach = static_cast<std::int32_t>(std::ceil((std::abs(wide) / 2.0) + 1.0))};
}

float ripple_scale(double wind) noexcept {
    // water_layer: sc = 0.8 + w['wind'] * 0.17, printed '%.1f', read back as a float
    return static_cast<float>(pymath::round(0.8 + (wind * 0.17), 1));
}

RippleBlur ripple_blur(float density) noexcept {
    // stdDeviation=".1 .45": the deviations as floats, times the filter scale as a double, sized as kernel() sizes them
    const auto d = static_cast<double>(density);
    return {.x = box_size(static_cast<float>(static_cast<double>(0.1F) * d)),
            .y = box_size(static_cast<float>(static_cast<double>(0.45F) * d))};
}

Argb ripple_to_linear(Argb p) noexcept { return bytes::srgb_to_linear_premultiplied(p); }

Argb ripple_to_srgb(Argb p) noexcept {
    // the chain's result read as premultiplied sRGB: by its exact unpremultiplied colour, then premultiplied
    if ((p >> 24U) == 0) return 0;
    return bytes::premultiply(bytes::unpremultiply_exact(p, false));
}

std::vector<Argb> ripple_cpu(std::span<const Argb> layer, std::span<const std::uint8_t> field, std::uint32_t width,
                             std::uint32_t height, float scale, float density) {
    const std::size_t n = std::size_t{width} * height;
    ATLAS_ASSERT(layer.size() >= n && field.size() >= 2 * n, "a ripple's layer and field cover its frame");
    // the source in linear RGB, premultiplied
    std::vector<Argb> source(n);
    for (std::size_t i = 0; i < n; ++i) source[i] = ripple_to_linear(layer[i]);
    // the displacement: each pixel the source pixel the map moves it to, transparent beyond the frame
    const RippleDisplacement d = ripple_displacement(scale, density);
    std::vector<Argb> moved(n, 0);
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::size_t i = (std::size_t{y} * width) + x;
            const auto red = static_cast<float>(field[2 * i]);
            const auto green = static_cast<float>(field[(2 * i) + 1]);
            const std::int32_t sx =
                static_cast<std::int32_t>(x) + static_cast<std::int32_t>(std::fma(d.factor, red, d.offset));
            const std::int32_t sy =
                static_cast<std::int32_t>(y) + static_cast<std::int32_t>(std::fma(d.factor, green, d.offset));
            if (sx < 0 || sy < 0 || std::cmp_greater_equal(sx, width) || std::cmp_greater_equal(sy, height)) continue;
            moved[i] = source[(static_cast<std::size_t>(sy) * width) + static_cast<std::size_t>(sx)];
        }
    }
    // the blur over the frame
    const RippleBlur blur = ripple_blur(density);
    if (blur.square()) {
        std::array<std::vector<std::uint8_t>, 4> planes;
        for (std::size_t c = 0; c < 4; ++c) {
            planes[c].resize(n);
            for (std::size_t i = 0; i < n; ++i) planes[c][i] = static_cast<std::uint8_t>((moved[i] >> (8 * c)) & 0xFFU);
            square_blur_cpu(planes[c], width, height, blur.side(), 3);
        }
        for (std::size_t i = 0; i < n; ++i) {
            moved[i] = Argb{planes[0][i]} | (Argb{planes[1][i]} << 8U) | (Argb{planes[2][i]} << 16U) |
                       (Argb{planes[3][i]} << 24U);
        }
    } else {
        // WebKit's separable passes: the three boxes of each axis, x then y each time, transparent past the frame
        const Run rx = webkit_boxes(blur.x);
        const Run ry = webkit_boxes(blur.y);
        std::vector<std::uint32_t> next(n);
        for (std::uint32_t k = 0; k < 3; ++k) {
            for (const Axis axis : {Axis::x, Axis::y}) {
                const Run& whole = axis == Axis::x ? rx : ry;
                if (k >= whole.count) continue;
                BlurRect rect{.x = 0, .y = 0, .width = width, .height = height};
                rect.run.boxes[0] = whole.boxes[k];
                rect.run.count = 1;
                blur_cpu(moved, next, width, rect,
                         {.axis = axis, .rounding = Rounding::truncate, .edge = Edge::transparent}, BlurFormat::rgba8);
                moved.swap(next);
            }
        }
    }
    return moved;
}

} // namespace atlas::fx
