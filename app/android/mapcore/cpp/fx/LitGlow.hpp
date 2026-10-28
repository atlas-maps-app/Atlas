// The lit streets' glow (ENGINE.md step 4, the night; render/amsterdam.py:817's `litglow`, `<filter id="litglow"
// x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="2.6"/></filter>` on the glow's group):
// the filter as SvgCanvas sets it up and SvgFilterRaster runs it, the CPU reference the GPU (shaders/ground/
// litglow.slang) is held to bit for bit. In filter pixels (the frame's device px, the filter scale its density as a
// float):
//   - the region: the group's bounding box (its paths' points as the SVG's parser reads them, floats, strokes left out)
//     grown by 20% of its width and height either side, its enclosing rectangle at the filter scale (none where the box
//     has no width or no height: the port draws nothing then);
//   - the output: the region within the visible frame (the canvas's clip, a pixel beyond it either side); the input:
//     the output grown by the blur's reach, 3 · (size / 2 + 1) for WebKit's size, within the region;
//   - the group drawn over the input (premultiplied sRGB), held in linear RGB (Core Graphics' conversion of each pixel
//     by its exact unpremultiplied colour, then premultiplied), blurred by vImage's three square boxes of WebKit's size
//     for 2.6 (both axes alike) made odd, the input's edge texels repeated past it, each sum divided once and rounded;
//     the result written back as premultiplied sRGB, which the group's opacity then screens over the ground.
#pragma once

#include "fx/Noise.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace atlas::fx {

/// The glow's blur at a filter scale: WebKit's box size for 2.6 times it, vImage's square box (the size made odd) and
/// how far the filter's input reaches beyond its output (SvgFilterRaster's BlurNode: 3 · (size / 2 + 1)).
struct GlowBlur {
    std::uint32_t size = 0;
    std::uint32_t side = 0;
    std::int32_t reach = 0;
    /// Half the square box: how far one pass reads either side.
    [[nodiscard]] constexpr std::int32_t half() const noexcept { return static_cast<std::int32_t>(side / 2); }
};

/// The blur at the filter scale `density` (a float, as the frame's).
[[nodiscard]] GlowBlur litglow_blur(float density) noexcept;

/// A rectangle of filter px, [x0, x1) × [y0, y1).
struct GlowRect {
    std::int32_t x0 = 0;
    std::int32_t y0 = 0;
    std::int32_t x1 = 0;
    std::int32_t y1 = 0;

    [[nodiscard]] constexpr bool empty() const noexcept { return x0 >= x1 || y0 >= y1; }
    [[nodiscard]] constexpr bool contains(std::int32_t x, std::int32_t y) const noexcept {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }
    [[nodiscard]] constexpr GlowRect grown(std::int32_t d) const noexcept {
        return {.x0 = x0 - d, .y0 = y0 - d, .x1 = x1 + d, .y1 = y1 + d};
    }
    [[nodiscard]] constexpr GlowRect within(const GlowRect& o) const noexcept {
        return {.x0 = std::max(x0, o.x0), .y0 = std::max(y0, o.y0), .x1 = std::min(x1, o.x1), .y1 = std::min(y1, o.y1)};
    }
    [[nodiscard]] constexpr GlowRect moved(std::int32_t dx, std::int32_t dy) const noexcept {
        return {.x0 = x0 + dx, .y0 = y0 + dy, .x1 = x1 + dx, .y1 = y1 + dy};
    }
    friend constexpr bool operator==(const GlowRect&, const GlowRect&) = default;
};

/// The filter's region in filter px for the group's bounding box `box` (left, top, right, bottom, as the parser's
/// floats) at the filter scale `density`: SvgCanvas.filtered's objectBoundingBox region (box + (-0.2F) · its width,
/// 1.4F · it, in double) and SvgFilterRaster.prepare's enclosing rectangle of it times the scale. None where the box
/// has no width or no height.
[[nodiscard]] std::optional<GlowRect> litglow_region(const std::array<float, 4>& box, float density) noexcept;

/// What the filter covers in a `width` × `height` px frame: its region; the output, the region within the frame and a
/// pixel beyond it either side (SvgCanvas's visible rectangle); the input, the output grown by the blur's reach within
/// the region. Empty where the output is.
struct GlowPlan {
    GlowRect region;
    GlowRect out;
    GlowRect input;
};
[[nodiscard]] GlowPlan litglow_plan(const GlowRect& region, std::uint32_t width, std::uint32_t height,
                                    const GlowBlur& blur) noexcept;

/// The filter over `input` of a `width` × `height` layer (premultiplied sRGB, packed a r g b, rows from the top; its
/// pixels outside `input` unread): the result over the layer, premultiplied linear RGB, transparent outside `input`
/// (ripple_to_srgb writes each pixel back).
[[nodiscard]] std::vector<Argb> litglow_cpu(std::span<const Argb> layer, std::uint32_t width, std::uint32_t height,
                                            const GlowRect& input, const GlowBlur& blur);

} // namespace atlas::fx
