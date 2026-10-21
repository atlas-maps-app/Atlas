// The grid a blurred group's filter runs on (fx/BlurGrid.hpp), step by step as the port's raster takes them.
#include "fx/BlurGrid.hpp"

#include "fx/Blur.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::fx {

namespace {

/// WebKit's `ImageBuffer::sizeNeedsClamping` (SvgCanvas.clampScale): the factor that brings a w × h pixel area within
/// 4096².
[[nodiscard]] double clamp_scale(double w, double h) noexcept {
    constexpr double most = 4096.0 * 4096.0;
    if (!(w > 0.0) || !(h > 0.0)) return 1.0;
    return std::floor(w) * std::floor(h) > most ? std::sqrt(most / (w * h)) : 1.0;
}

[[nodiscard]] std::int32_t floor_of(double v) noexcept {
    return static_cast<std::int32_t>(std::clamp(std::floor(v), -2.0e9, 2.0e9));
}

[[nodiscard]] std::int32_t ceil_of(double v) noexcept {
    return static_cast<std::int32_t>(std::clamp(std::ceil(v), -2.0e9, 2.0e9));
}

[[nodiscard]] GridRect intersect(const GridRect& a, const GridRect& b) noexcept {
    return {.left = std::max(a.left, b.left),
            .top = std::max(a.top, b.top),
            .right = std::min(a.right, b.right),
            .bottom = std::min(a.bottom, b.bottom)};
}

} // namespace

BlurGrid blur_grid(const std::array<float, 4>& box, float sigma, float density, std::int32_t clip_width,
                   std::int32_t clip_height) noexcept {
    BlurGrid g;
    // filtered(): the region from the box, in objectBoundingBox units (the parser's -10f / 100f and 120f / 100f); the
    // box's size a float difference, widened
    const auto bw = static_cast<double>(box[2] - box[0]);
    const auto bh = static_cast<double>(box[3] - box[1]);
    if (!(bw > 0.0) || !(bh > 0.0)) return g;
    constexpr float at = -0.1F;
    constexpr float size = 1.2F;
    const double r0 = static_cast<double>(box[0]) + (static_cast<double>(at) * bw);
    const double r1 = static_cast<double>(box[1]) + (static_cast<double>(at) * bh);
    const double r2 = static_cast<double>(size) * bw;
    const double r3 = static_cast<double>(size) * bh;
    if (!(r2 > 0.0) || !(r3 > 0.0)) return g;
    // the canvas's scale (hypot of its float matrix), clamped by the source's size within the region (a filtered
    // node's reach, 1e4 CSS px, reaches past the region), then by the region's
    auto sx = static_cast<double>(density);
    constexpr double pad = 1e4;
    const double w =
        std::min(static_cast<double>(box[2]) + pad, r0 + r2) - std::max(static_cast<double>(box[0]) - pad, r0);
    const double h =
        std::min(static_cast<double>(box[3]) + pad, r1 + r3) - std::max(static_cast<double>(box[1]) - pad, r1);
    const double k1 = clamp_scale(w * sx, h * sx);
    sx *= k1;
    const double k2 = clamp_scale(r2 * sx, r3 * sx);
    sx *= k2;
    g.scale = sx;
    g.clamp = k1 * k2;
    // the frame's clip in filter px, with a pixel around it
    const GridRect visible{.left = floor_of(0.0 * sx) - 1,
                           .top = floor_of(0.0 * sx) - 1,
                           .right = ceil_of(static_cast<double>(clip_width) * sx) + 1,
                           .bottom = ceil_of(static_cast<double>(clip_height) * sx) + 1};
    // prepare(): the region's enclosing pixels (Rect.enclosing), the output within the visible ones
    const double x = r0 * sx;
    const double y = r1 * sx;
    g.region = {
        .left = floor_of(x), .top = floor_of(y), .right = ceil_of(x + (r2 * sx)), .bottom = ceil_of(y + (r3 * sx))};
    g.output = intersect(g.region, visible);
    if (g.output.empty()) return g;
    // the blur node: WebKit's box for the deviation at the scale (the float σ widened, times the scale, as a float),
    // its reach, and vImage's odd square of it
    g.kernel = box_size(static_cast<float>(static_cast<double>(sigma) * sx));
    g.reach = 3 * ((g.kernel / 2) + 1);
    g.box = g.kernel % 2 == 0 ? g.kernel + 1 : g.kernel;
    const auto r = static_cast<std::int32_t>(g.reach);
    g.input = intersect({.left = g.output.left - r,
                         .top = g.output.top - r,
                         .right = g.output.right + r,
                         .bottom = g.output.bottom + r},
                        g.region);
    g.drawn = g.kernel > 0 && !g.input.empty();
    return g;
}

} // namespace atlas::fx
