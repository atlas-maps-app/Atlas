// The paper grain (plan-vector D5.4, D10, K17a; render/amsterdam.py:964, fin): the grain filter's result (fractal
// noise, seed 8, .9, one octave, made grey by feColorMatrix saturate 0) drawn over the whole finished frame at opacity
// .09 (.12 at night) with mix-blend-mode:overlay, before the vignette. fx/Fields.hpp keeps the filter's result per
// pixel, premultiplied sRGB grey and alpha (noise_rgba's green and blue).
//
// The oracle draws it as Skia does: the filter's result in a layer, restored over the canvas with the paint's alpha and
// the overlay mode, through Skia's 8-bit raster pipeline (SkRasterPipeline's lowp stages, in 16-bit lanes): the
// paint's float alpha made a byte, f · 255 + ½ truncated (from_float: 23 by day, 31 at night); the layer's channels
// scaled by it (scale_1_float); the overlay of the scaled source over the canvas, its whole sum divided by 255 once.
// Skia divides by 255 one way with NEON (rounded exactly) and another on x86 ((v + 255) / 256, one off for about a
// quarter of values); the references the gates read are made on CI's x86_64 Linux (D16), so the composite divides as
// x86 does (shaders/shared/composite.slang's composite_grain_8, the GPU's and the twin's; this file is its reference,
// written from Skia's stages apart from it). Integers throughout, so every device and the twin compute the same bytes.
#pragma once

#include <cstdint>
#include <span>

namespace atlas::fx {

class ScreenFields;

/// fin's grain opacity: .09 by day, .12 at night.
inline constexpr float grain_opacity_day = 0.09F;
inline constexpr float grain_opacity_night = 0.12F;

/// Skia's lowp from_float: a paint's alpha as the byte its stages scale by, f · 255 + ½ truncated.
[[nodiscard]] constexpr std::uint32_t skia_byte(float f) noexcept {
    return static_cast<std::uint32_t>((f * 255.0F) + 0.5F); // NOLINT(bugprone-incorrect-roundings): Skia's own rounding
}

/// A 16-bit lane's value: Skia's lowp arithmetic wraps there.
[[nodiscard]] constexpr std::uint32_t lane16(std::uint32_t v) noexcept { return v & 0xFFFFU; }

/// Skia's lowp div255 on x86: (v + 255) / 256, in a 16-bit lane.
[[nodiscard]] constexpr std::uint32_t skia_div255(std::uint32_t v) noexcept { return lane16(v + 255U) >> 8U; }

/// One channel of the canvas (`d`, an 8-bit level under alpha `da`) after the grain: the grain's premultiplied sRGB
/// grey `g` and alpha `a` at the pixel and the paint's alpha byte `opacity` (skia_byte of fin's opacity), overlaid as
/// Skia's lowp pipeline does: s = g·o/255, sa = a·o/255, then (s·(255 − da) + d·(255 − sa) + (2d ≤ da ? 2sd :
/// sa·da − 2(sa − s)(da − d))) / 255, each step in its 16-bit lane.
[[nodiscard]] constexpr std::uint32_t grain_overlay(std::uint32_t d, std::uint32_t g, std::uint32_t a,
                                                    std::uint32_t opacity, std::uint32_t da = 255) noexcept {
    const std::uint32_t s = skia_div255(lane16(g * opacity));
    const std::uint32_t sa = skia_div255(lane16(a * opacity));
    const std::uint32_t term =
        2 * d <= da ? lane16(2 * s * d) : lane16(lane16(sa * da) - lane16(2 * lane16(sa - s) * lane16(da - d)));
    const std::uint32_t sum = lane16(lane16(s * (255 - da)) + lane16(d * (255 - sa)) + term);
    return skia_div255(sum);
}

/// fin's grain over an opaque 8-bit frame (R, G, B bytes a pixel, rows from the top, `fields`' size), as the oracle
/// draws it: every channel through grain_overlay with the pixel's grey and alpha and the opacity of the moment.
void apply_grain(std::span<std::uint8_t> rgb, const ScreenFields& fields, bool night) noexcept;

} // namespace atlas::fx
