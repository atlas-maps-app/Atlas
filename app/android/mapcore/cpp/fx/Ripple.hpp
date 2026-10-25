// The water's ripple (ENGINE.md step 4, the effects; render/amsterdam.py's water_layer: `<g filter="url(#ripple)"
// opacity=".92">` over the reflections, .95 at night): the filter as SvgFilterRaster runs it, the CPU reference the
// GPU (shaders/ground/ripple.slang) is held to bit for bit. The filter (userSpaceOnUse, the frame its region):
//   n: feTurbulence fractalNoise .012 × .26, 2 octaves, seed 11 (the ripple's screen field, fx/Fields.hpp);
//   feDisplacementMap SourceGraphic by n, scale '%.1f' of 0.8 + wind · 0.17, R and G;
//   feGaussianBlur stdDeviation .1 .45.
// In filter pixels (the frame's device px, the filter scale its density as a float): the group drawn (premultiplied
// sRGB) is held in linear RGB (Core Graphics' conversion of each pixel by its exact unpremultiplied colour, then
// premultiplied); each pixel takes the source pixel its map's red and green move it to, x + int(fma(float(abs / 255),
// red, float(0.5 − abs · 0.5))) (abs the scale times the filter scale, as a float; the sum truncated towards zero),
// transparent where that lies outside the frame; the blur's boxes are WebKit's for each deviation times the filter
// scale: equal, vImage's three square boxes of the size made odd, the frame's edge texels repeated, each rounded; else
// WebKit's three separable passes (x, then y, each time), a transparent edge, truncated. The result is written back
// as premultiplied sRGB, which the group's opacity then composites.
#pragma once

#include "fx/Noise.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::fx {

/// The displacement's arithmetic at a frame (SvgFilterRaster's DisplacementNode): abs = float(scale · filter scale),
/// the map's channel times float(abs / 255) plus float(0.5 − abs · 0.5), and how far a pixel may move.
struct RippleDisplacement {
    float factor = 0.0F;
    float offset = 0.0F;
    std::int32_t reach = 0; ///< ceil(|abs| / 2 + 1)
};

/// The displacement of a printed `scale` (as the port's parser reads it, a float) under the filter scale `density` (a
/// float, as the frame's).
[[nodiscard]] RippleDisplacement ripple_displacement(float scale, float density) noexcept;

/// The ripple's scale as water_layer prints it, '%.1f' of 0.8 + wind · 0.17, read as a float.
[[nodiscard]] float ripple_scale(double wind) noexcept;

/// The blur's boxes along x and y (WebKit's sizes for .1 and .45 times the filter scale) and whether they run as
/// vImage's square boxes (both equal: the size made odd).
struct RippleBlur {
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    [[nodiscard]] constexpr bool square() const noexcept { return x == y && x > 0; }
    /// The square box's side (odd), for square().
    [[nodiscard]] constexpr std::uint32_t side() const noexcept { return x % 2 == 0 ? x + 1 : x; }
};

[[nodiscard]] RippleBlur ripple_blur(float density) noexcept;

/// The source pixel a premultiplied sRGB pixel becomes in the filter's linear RGB (Bytes.srgbToLinearPremultiplied).
[[nodiscard]] Argb ripple_to_linear(Argb p) noexcept;
/// The filter's premultiplied linear result written back as premultiplied sRGB (the chain's last conversion).
[[nodiscard]] Argb ripple_to_srgb(Argb p) noexcept;

/// The ripple of a `width` × `height` frame: `layer` the group drawn (premultiplied sRGB, packed a r g b, rows from the
/// top), `field` the ripple field's red and green, two bytes a pixel; the filter's result as the blur leaves it
/// (premultiplied linear RGB: ripple_to_srgb writes each pixel back).
[[nodiscard]] std::vector<Argb> ripple_cpu(std::span<const Argb> layer, std::span<const std::uint8_t> field,
                                           std::uint32_t width, std::uint32_t height, float scale, float density);

} // namespace atlas::fx
