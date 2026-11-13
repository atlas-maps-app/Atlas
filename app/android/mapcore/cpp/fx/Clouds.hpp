// The sky layer's clouds and their shadows (ENGINE.md part 4, the effects; render/amsterdam.py's sky_layer, away from
// lit clouds: `<rect width=W height=hb filter="url(#cloudsh2)"/><rect width=W height=hb filter="url(#cloudf)"/>`
// before the sun's glow). Both filters are userSpaceOnUse over the sky layer's rows (x 0, y 0, W × int(hb)) and read
// one noise, feTurbulence fractalNoise .0055 × .017, 4 octaves, seed 31 (the clouds' screen field, fx/Fields.hpp):
//   cloudf    feColorMatrix "0 0 0 0 r  0 0 0 0 g  0 0 0 0 b  k 0 0 0 -k t": the cloud's colour where the noise's red
//             passes t, as dense as k makes it;
//   cloudsh2  feOffset dy 5, then feColorMatrix "0 0 0 0 .62 r  0 0 0 0 .64 g  0 0 0 0 .7 b  k 0 0 0 -k (t + .04)":
//             the same noise 5 px lower, darker and a little smaller: the cloud's shaded underside.
// By day r g b are .98 .98 .99 with k 9 and t .62 less a quarter of the cloud cover; under an overcast sky .66 .68 .72,
// 2.2 and .18; at night .17 .15 .19, 1.5 and -.1 (view/ScreenLayers.hpp's Clouds).
//
// The matrices read the noise's red alone (unpremultiplied, after the raster made it premultiplied, as MatrixNode
// reads its input), so each filter's result at a pixel is one of 256, a table a moment made as the glints'
// (fx/Glints.hpp: WebKit's float matrix, premultiplied, written back as premultiplied sRGB). cloudsh2's offset is 5 px
// at the filter's scale: SvgFilterRaster's OffsetNode moves the premultiplied noise down whole rows and, where the
// scale leaves a fraction, blends each pixel with the one a row above it (in doubles, rounded half up: here in whole
// numbers of 1/2^24, the same sums); what lies above the region's first row is nothing, whose red reads 0.
#pragma once

#include "fx/Fields.hpp"
#include "fx/Noise.hpp"

#include <array>
#include <cstdint>

namespace atlas::fx {

/// A cloud filter's colour matrix's numbers as sky_layer prints them, read as floats: the colour's rows' offsets
/// ('%.2f'), the alpha row's red ('%.1f') and its offset ('%.2f').
struct CloudMatrix {
    float red = 0;
    float green = 0;
    float blue = 0;
    float k = 0;
    float offset = 0;

    friend bool operator==(const CloudMatrix&, const CloudMatrix&) = default;
};

/// cloudf's matrix for clouds of colour (`red`, `green`, `blue`), alpha k · (n − t).
[[nodiscard]] CloudMatrix cloud_matrix(double red, double green, double blue, double k, double t) noexcept;
/// cloudsh2's for the same clouds: the colour at .62, .64 and .7, alpha k · (n − (t + .04)).
[[nodiscard]] CloudMatrix cloud_shadow_matrix(double red, double green, double blue, double k, double t) noexcept;

/// A cloud filter's result for each of the 256 reds its matrix reads (premultiplied sRGB, a r g b).
[[nodiscard]] std::array<Argb, 256> cloud_table(const CloudMatrix& m) noexcept;

/// The fixed point of the offset's blend: weights in 1/2^24.
inline constexpr std::uint32_t cloud_weight_one = std::uint32_t{1} << 24U;

/// cloudsh2's feOffset at a filter scale: the whole rows it moves the noise down (floor of 5 · scale) and the weight
/// of the row above (the fraction left, in 1/2^24; 0 at a whole number of rows).
struct CloudOffset {
    std::int32_t rows = 0;
    std::uint32_t above = 0;

    friend bool operator==(const CloudOffset&, const CloudOffset&) = default;
};
[[nodiscard]] CloudOffset cloud_offset(float scale) noexcept;

/// The offset's blend of a premultiplied channel: `at` the pixel's `rows` up, `over` the one a row above that, by the
/// weight `above` of the latter: Kotlin's roundToInt of (1 − f) · at + f · over, both products and their sum exact.
[[nodiscard]] constexpr std::uint32_t cloud_blend(std::uint32_t at, std::uint32_t over, std::uint32_t above) noexcept {
    return (((cloud_weight_one - above) * at) + (above * over) + (cloud_weight_one >> 1U)) >> 24U;
}

/// The red a matrix reads of a premultiplied pixel's red and alpha: vImage's unpremultiply ((c · 255 + a / 2) / a, at
/// most 255; nothing of nothing, the red itself of an opaque pixel).
[[nodiscard]] constexpr std::uint32_t cloud_red(std::uint32_t red, std::uint32_t alpha) noexcept {
    if (alpha == 255U) return red;
    if (alpha == 0U) return 0U;
    const std::uint32_t q = ((red * 255U) + (alpha / 2U)) / alpha;
    return q < 255U ? q : 255U;
}

/// The filters' region's rows of a frame `height` px tall at filter scale `scale`: SvgFilterRaster's enclosing
/// rectangle of `<filter ... y="0" height="%d">` of the sky layer's bottom `sky_bottom` (hb: Python's int() of it,
/// toward zero), ceil(int(hb) · scale), within the frame.
[[nodiscard]] std::uint32_t cloud_rows(double sky_bottom, float scale, std::uint32_t height) noexcept;

/// The reds the two matrices read at pixel (x, y) of `fields`: cloudsh2's through its offset, and cloudf's.
struct CloudReds {
    std::uint32_t shadow = 0;
    std::uint32_t cloud = 0;
};
[[nodiscard]] CloudReds cloud_reds(const ScreenFields& fields, CloudOffset offset, std::uint32_t x,
                                   std::uint32_t y) noexcept;

} // namespace atlas::fx
