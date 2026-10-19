// `mat`, the renders' material texture (plan-vector D5.4, D10, D12, K17a; render/amsterdam.py:635): the walls', roofs'
// and pavements' colour varied by a fine streaky noise, "stronger along the courses than across them". The filter:
//   feTurbulence (fractal, .16 × .75, 2 octaves, seed 13), feColorMatrix (.33 r + .33 g + .33 b, opaque) → g;
//   feComposite SourceGraphic with g, arithmetic k1 .2 k2 .9 → t;  feComposite t in SourceAlpha.
// In linear light, a source colour c becomes c · (0.9 + 0.2 g), and a pixel the group covers with coverage A comes out
// with alpha min(1, 1.1 A) · A: the group's silhouettes thin (N13).
//
// Here the filter's byte arithmetic as SvgFilterRaster computes it, pixel by pixel, for the GPU's tables and the
// tests: the source drawn into linear RGB (the byte tables), the arithmetic composite in float with WebKit's NEON
// truncation, the colour corrected to its alpha, the `in` by the source's alpha, and the result written as
// premultiplied sRGB. For an opaque fragment the result depends on its colour level and the grey alone: mat_opaque, the
// renders' bytes exactly, which the scene's fragment shaders read from a 64 KB table (shaders/fx/mat.slang); the
// composite's rule at the objects group's silhouettes takes the general pixel (D5.4).
#pragma once

#include "fx/Noise.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace atlas::fx {

/// The arithmetic composite's coefficients as SvgFilterRaster holds them: k1 / 255 (the products are of bytes) and k2,
/// in float, as Kotlin computes `0.2f / 255f`.
inline constexpr float mat_k1 = 0.2F / 255.0F;
inline constexpr float mat_k2 = 0.9F;

/// One channel of `mat`'s arithmetic composite: the source's premultiplied linear byte `c` with the grey's byte `g`,
/// (c · k2 + g · 0) + (c · g) · k1 in float, truncated, 0 below zero, at most 255.
[[nodiscard]] std::uint32_t mat_arithmetic(std::uint32_t c, std::uint32_t g) noexcept;

/// `mat`'s filter on one pixel: the source `p` (premultiplied sRGB, a r g b, as the objects group's raster holds it)
/// and the grey `g` (its field's byte there); the filter's result, premultiplied sRGB, as SvgFilterRaster writes it.
[[nodiscard]] Argb mat_pixel(Argb p, std::uint32_t g) noexcept;

/// An opaque pixel's sRGB level `c` (one channel) after `mat` at grey `g`: mat_pixel of an opaque grey, one channel.
[[nodiscard]] std::uint32_t mat_opaque(std::uint32_t c, std::uint32_t g) noexcept;

/// The opaque table the GPU reads (set 1's byte tables, after the two conversion tables): mat_opaque(c, g) at
/// c · 256 + g.
[[nodiscard]] std::span<const std::uint8_t, 65536> mat_table() noexcept;

} // namespace atlas::fx
