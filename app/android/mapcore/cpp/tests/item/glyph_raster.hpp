// The exact blend of a frame's Slug glyphs (passes/Doors.cpp's numbers, passes/Shopfronts.cpp's signs) over the items'
// raster (tests/item/item_raster.hpp), which doors_vk_test and shopfronts_vk_test hold the GPU's frames to on lavapipe.
#pragma once

#include "base/pymath/PyMath.hpp"
#include "gen/layouts.hpp"
#include "tests/item/item_raster.hpp"
#include "text/SlugBands.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <span>
#include <utility>
#include <vector>

namespace atlas::test::items {

/// The glyphs (a door's number, a sign's text) blended over raster `r` in their order: on each sample their dilated
/// quad covers (the vertex stage's dilation by half a device pixel) whose depth their own passes (GREATER), at the
/// coverage the CPU's Slug gives at the pixel's centre, rounded to levels as the GPU blends; such a sample may lie two
/// levels off. A sample within 0.02 px of the quad's edge lies near a boundary, as at a shape's: the rasteriser's
/// sub-pixel grid decides whether the quad covers it. The samples blended over, and the pixels a glyph covers some of.
struct Blended {
    std::size_t samples = 0;
    std::size_t pixels = 0;
};
inline Blended blend_glyphs(Raster& r, std::span<const layouts::text_slug::GlyphDraw> glyphs,
                            const shaders::FrameCamera& cam) {
    Blended out;
    const auto density = static_cast<double>(cam.frame.z);
    const std::size_t n = r.samples.size();
    std::vector<std::uint8_t> touched(std::size_t{r.w} * r.h, 0);
    text::slug::shared_glyphs().read([&](const text::slug::Atlas& atlas) {
        for (const auto& d : glyphs) {
            // em to device px: density · (M · em + t)
            const double a = static_cast<double>(d.row0[0]) * density;
            const double b = static_cast<double>(d.row0[1]) * density;
            const double c = static_cast<double>(d.row1[0]) * density;
            const double e = static_cast<double>(d.row1[1]) * density;
            const double tx = static_cast<double>(d.row0[2]) * density;
            const double ty = static_cast<double>(d.row1[2]) * density;
            const double det = (a * e) - (b * c);
            const double i00 = e / det;
            const double i01 = -b / det;
            const double i10 = -c / det;
            const double i11 = a / det;
            const auto fwx = static_cast<float>(std::fabs(i00) + std::fabs(i01));
            const auto fwy = static_cast<float>(std::fabs(i10) + std::fabs(i11));
            // the vertex stage's dilation, in em units (slug.slang's glyph_corner)
            const auto cdet = static_cast<double>((d.row0[0] * d.row1[1]) - (d.row0[1] * d.row1[0]));
            const double mx = 0.5 *
                              (std::fabs(static_cast<double>(d.row1[1])) + std::fabs(static_cast<double>(d.row0[1]))) /
                              (std::fabs(cdet) * density);
            const double my = 0.5 *
                              (std::fabs(static_cast<double>(d.row1[0])) + std::fabs(static_cast<double>(d.row0[0]))) /
                              (std::fabs(cdet) * density);
            const double bx0 = static_cast<double>(d.box[0]) - mx;
            const double by0 = static_cast<double>(d.box[1]) - my;
            const double bx1 = static_cast<double>(d.box[2]) + mx;
            const double by1 = static_cast<double>(d.box[3]) + my;
            text::slug::Glyph g{};
            g.box = {.x0 = d.box[0], .y0 = d.box[1], .x1 = d.box[2], .y1 = d.box[3]};
            g.band_x = d.location[0];
            g.band_y = d.location[1];
            g.vbands = d.location[2] + 1;
            g.hbands = d.location[3] + 1;
            g.scale_x = d.bands[0];
            g.scale_y = d.bands[1];
            g.offset_x = d.bands[2];
            g.offset_y = d.bands[3];
            const auto depth = static_cast<double>(std::bit_cast<float>(d.info[2]));
            double x0 = 1e9;
            double y0 = 1e9;
            double x1 = -1e9;
            double y1 = -1e9;
            for (const auto& [u, v] :
                 {std::pair{bx0, by0}, std::pair{bx1, by0}, std::pair{bx0, by1}, std::pair{bx1, by1}}) {
                x0 = std::min(x0, (a * u) + (b * v) + tx);
                y0 = std::min(y0, (c * u) + (e * v) + ty);
                x1 = std::max(x1, (a * u) + (b * v) + tx);
                y1 = std::max(y1, (c * u) + (e * v) + ty);
            }
            const int ix0 = std::max(0, static_cast<int>(std::floor(x0)) - 1);
            const int iy0 = std::max(0, static_cast<int>(std::floor(y0)) - 1);
            const int ix1 = std::min(static_cast<int>(r.w) - 1, static_cast<int>(std::ceil(x1)) + 1);
            const int iy1 = std::min(static_cast<int>(r.h) - 1, static_cast<int>(std::ceil(y1)) + 1);
            const auto em_at = [&](double px, double py) {
                const double sx = px - tx;
                const double sy = py - ty;
                return std::pair{(i00 * sx) + (i01 * sy), (i10 * sx) + (i11 * sy)};
            };
            // em x and y a device pixel: a sample's distance from the quad's edges in px
            const double per_x = pymath::hypot(i00, i01);
            const double per_y = pymath::hypot(i10, i11);
            constexpr double tol = 0.02;
            for (int y = iy0; y <= iy1; ++y) {
                for (int x = ix0; x <= ix1; ++x) {
                    const auto [ex, ey] = em_at(x + 0.5, y + 0.5);
                    const float cover = text::slug::coverage(atlas, g, static_cast<float>(ex), static_cast<float>(ey),
                                                             1.0F / fwx, 1.0F / fwy);
                    if (cover <= 0.0F) continue;
                    const std::size_t p = (static_cast<std::size_t>(y) * r.w) + static_cast<std::size_t>(x);
                    for (std::size_t q = 0; q < n; ++q) {
                        const auto [sx, sy] =
                            em_at(x + static_cast<double>(r.samples[q].x), y + static_cast<double>(r.samples[q].y));
                        const std::size_t k = (p * n) + q;
                        const double in_x = std::min(sx - bx0, bx1 - sx) / per_x;
                        const double in_y = std::min(sy - by0, by1 - sy) / per_y;
                        if (std::min(in_x, in_y) >= -tol && std::min(std::abs(in_x), std::abs(in_y)) <= tol) {
                            r.edge[k] = 1;
                        }
                        if (sx < bx0 || sx > bx1 || sy < by0 || sy > by1) continue;
                        if (r.covered[k] != 0 && !(depth > r.depth[k])) continue;
                        touched[p] = 1;
                        hlsl::float3& under = r.colour[k];
                        const float alpha = d.colour[3] * cover;
                        const auto over = [&](float dst, float src) {
                            return std::round(((src * cover) + (dst * (1.0F - alpha))) * 255.0F) / 255.0F;
                        };
                        under = {over(under.x, d.colour[0]), over(under.y, d.colour[1]), over(under.z, d.colour[2])};
                        r.blended[k] = static_cast<std::uint8_t>(std::min(255, r.blended[k] + 2));
                        if (r.covered[k] == 0) r.covered[k] = 2;
                        ++out.samples;
                    }
                }
            }
        }
    });
    out.pixels = static_cast<std::size_t>(std::ranges::count(touched, 1));
    return out;
}

} // namespace atlas::test::items
