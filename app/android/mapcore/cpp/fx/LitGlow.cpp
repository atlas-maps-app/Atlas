// The lit streets' glow on the CPU (fx/LitGlow.hpp): SvgCanvas's region and SvgFilterRaster's chain, step by step.
#include "fx/LitGlow.hpp"

#include "base/assert.hpp"
#include "fx/Blur.hpp"
#include "fx/ByteTables.hpp"

#include <cmath>
#include <cstddef>

namespace atlas::fx {

namespace {

/// A double as a filter px coordinate, within reach of an int.
[[nodiscard]] std::int32_t px(double v) noexcept {
    constexpr double most = 1e9;
    return static_cast<std::int32_t>(std::clamp(v, -most, most));
}

} // namespace

GlowBlur litglow_blur(float density) noexcept {
    // BlurNode: kernel(fe.deviationX * sx), the deviation a float and sx the filter scale as a double; its input's
    // reach 3 * (kx / 2 + 1)
    const std::uint32_t size = box_size(static_cast<float>(static_cast<double>(2.6F) * static_cast<double>(density)));
    return {.size = size, .side = vimage_box(size), .reach = 3 * (static_cast<std::int32_t>(size / 2) + 1)};
}

std::optional<GlowRect> litglow_region(const std::array<float, 4>& box, float density) noexcept {
    // SvgCanvas.filtered: bw = (box[2] - box[0]).toDouble(), the floats' difference; region[0] = box[0] +
    // f.region[0] * bw with the region's -20% and 140% as the parser's floats (-20f / 100f, 140f / 100f)
    const auto bw = static_cast<double>(box[2] - box[0]);
    const auto bh = static_cast<double>(box[3] - box[1]);
    if (!(bw > 0.0) || !(bh > 0.0)) return std::nullopt;
    const double x = static_cast<double>(box[0]) + (static_cast<double>(-20.0F / 100.0F) * bw);
    const double y = static_cast<double>(box[1]) + (static_cast<double>(-20.0F / 100.0F) * bh);
    const double w = static_cast<double>(140.0F / 100.0F) * bw;
    const double h = static_cast<double>(140.0F / 100.0F) * bh;
    // SvgFilterRaster.prepare: Rect.enclosing(region[0] * sx, region[1] * sy, region[2] * sx, region[3] * sy)
    const auto s = static_cast<double>(density);
    const double ex = x * s;
    const double ey = y * s;
    return GlowRect{.x0 = px(std::floor(ex)),
                    .y0 = px(std::floor(ey)),
                    .x1 = px(std::ceil(ex + (w * s))),
                    .y1 = px(std::ceil(ey + (h * s)))};
}

GlowPlan litglow_plan(const GlowRect& region, std::uint32_t width, std::uint32_t height,
                      const GlowBlur& blur) noexcept {
    // the canvas's clip a pixel beyond the frame either side; the output within it, the input its reach beyond
    const GlowRect visible{
        .x0 = -1, .y0 = -1, .x1 = static_cast<std::int32_t>(width) + 1, .y1 = static_cast<std::int32_t>(height) + 1};
    GlowPlan p{.region = region, .out = region.within(visible), .input = {}};
    if (p.out.empty()) {
        p.out = {};
        return p;
    }
    p.input = p.out.grown(blur.reach).within(region);
    return p;
}

std::vector<Argb> litglow_cpu(std::span<const Argb> layer, std::uint32_t width, std::uint32_t height,
                              const GlowRect& input, const GlowBlur& blur) {
    const std::size_t n = std::size_t{width} * height;
    ATLAS_ASSERT(layer.size() >= n, "the glow's layer covers its frame");
    std::vector<Argb> out(n, 0);
    const GlowRect in = input.within(
        {.x0 = 0, .y0 = 0, .x1 = static_cast<std::int32_t>(width), .y1 = static_cast<std::int32_t>(height)});
    if (in.empty()) return out;
    const auto iw = static_cast<std::uint32_t>(in.x1 - in.x0);
    const auto ih = static_cast<std::uint32_t>(in.y1 - in.y0);
    // the input in linear RGB, premultiplied, a plane a channel
    std::array<std::vector<std::uint8_t>, 4> planes;
    for (std::vector<std::uint8_t>& plane : planes) plane.resize(std::size_t{iw} * ih);
    for (std::uint32_t y = 0; y < ih; ++y) {
        for (std::uint32_t x = 0; x < iw; ++x) {
            const std::size_t at =
                ((static_cast<std::size_t>(in.y0) + y) * width) + static_cast<std::size_t>(in.x0) + x;
            const Argb p = bytes::srgb_to_linear_premultiplied(layer[at]);
            for (std::size_t c = 0; c < 4; ++c) {
                planes[c][(std::size_t{y} * iw) + x] = static_cast<std::uint8_t>((p >> (8 * c)) & 0xFFU);
            }
        }
    }
    // vImage's three square boxes over the input, its edge repeated past it
    if (blur.side > 0) {
        for (std::vector<std::uint8_t>& plane : planes) square_blur_cpu(plane, iw, ih, blur.side, 3);
    }
    for (std::uint32_t y = 0; y < ih; ++y) {
        for (std::uint32_t x = 0; x < iw; ++x) {
            const std::size_t i = (std::size_t{y} * iw) + x;
            out[((static_cast<std::size_t>(in.y0) + y) * width) + static_cast<std::size_t>(in.x0) + x] =
                Argb{planes[0][i]} | (Argb{planes[1][i]} << 8U) | (Argb{planes[2][i]} << 16U) |
                (Argb{planes[3][i]} << 24U);
        }
    }
    return out;
}

} // namespace atlas::fx
