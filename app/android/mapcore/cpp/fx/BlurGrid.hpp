// The grid a blurred group's filter runs on, as the port's raster lays it out (MV/svg/SvgCanvas.kt's filtered and
// SvgFilterRaster's prepare and blur node, WebKit's software filters on macOS): the ground shadows' `sblur`
// (render/amsterdam.py:925, `<filter x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur .../></filter>` on
// the shadows' group). The CPU's reference, in the port's own mix of float and double; the GPU folds the same steps
// (shaders/ground/ground.slang's shadow_grid_cs) in float, from the shadows' box it measured.
//
//   - The filter region is the group's box (its paths' printed points) grown by a tenth of its size on every side.
//   - WebKit draws a filter at most 4096² pixels large: where the region would cover more at the canvas's scale, the
//     scale shrinks until it does not (ImageBuffer::sizeNeedsClamping). The filter's pixels are then coarser than the
//     device's, and the result is drawn back over them, filtered bilinearly. At the boards' views this always holds
//     (the shadows reach 900 CSS px past the frame): the home's scale is 0.43 of the density, the Main board's 0.70.
//   - Filter pixels are CSS px times that scale, from the user space's origin. The output is the region's pixels
//     within the frame's clip and a pixel around it; the blur reads the output grown by its reach, within the region.
//   - The blur's box is WebKit's size for σ at that scale (d = ⌊σ · 3√(2π)/4 + ½⌋, at least 2); equal sizes along both
//     axes run as vImage's three square boxes of d, made odd (fx/Blur.hpp's square_blur_cpu), the region's edge
//     repeated past it.
#pragma once

#include <array>
#include <cstdint>

namespace atlas::fx {

/// A rectangle of filter pixels: [left, right) × [top, bottom).
struct GridRect {
    std::int32_t left = 0;
    std::int32_t top = 0;
    std::int32_t right = 0;
    std::int32_t bottom = 0;

    [[nodiscard]] std::int32_t width() const noexcept { return right - left; }
    [[nodiscard]] std::int32_t height() const noexcept { return bottom - top; }
    [[nodiscard]] bool empty() const noexcept { return right <= left || bottom <= top; }
    friend constexpr bool operator==(const GridRect&, const GridRect&) = default;
};

/// What the port's raster does with a blurred group.
struct BlurGrid {
    /// The group draws: its box has an area, and its region reaches the frame.
    bool drawn = false;
    /// Filter px per CSS px: the canvas's scale times the clamp.
    double scale = 0;
    /// WebKit's clamp of the filter's size (1: none; below 1, the result is drawn back filtered).
    double clamp = 1;
    /// The filter region, in filter px.
    GridRect region;
    /// What is drawn: the region within the frame's clip and a pixel around it.
    GridRect output;
    /// What the blur reads: the output grown by its reach, within the region.
    GridRect input;
    /// WebKit's box size d for σ at the scale (SvgFilterRaster.kernel), and vImage's square of it (d, made odd).
    std::uint32_t kernel = 0;
    std::uint32_t box = 0;
    /// How far the blur reads past the output: 3 · (d / 2 + 1).
    std::uint32_t reach = 0;
};

/// The filter's grid for a group whose box is `box` (CSS px: left, top, right, bottom, as SvgBounds measures its
/// printed points), blurred by a deviation of `sigma` CSS px (the filter's stdDeviation), on a canvas of `density`
/// device px per CSS px (its matrix's float) whose clip is `clip_width` × `clip_height` CSS px from the origin (the
/// frame's, rounded out).
[[nodiscard]] BlurGrid blur_grid(const std::array<float, 4>& box, float sigma, float density, std::int32_t clip_width,
                                 std::int32_t clip_height) noexcept;

} // namespace atlas::fx
