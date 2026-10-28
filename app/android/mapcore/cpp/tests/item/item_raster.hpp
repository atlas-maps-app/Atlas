// The exact CPU raster of the region items' records (shaders/item/items.slangh), which items_vk_test and doors_vk_test
// hold the GPU's frames to on lavapipe: each face's fan by its edges and its stroke's edges sample by sample
// (items.slangh's own tests, in float, at the samples' CSS points), each segment's and circle's samples; each sample's
// depth as the rasteriser interpolates the vertex stage's corners over the triangle it lies in (item_segment_corner's
// quads for the strokes and segments); each sample ending with the record of the greatest depth (the first drawn where
// two tie, as GREATER keeps it), a sample where two of different colours lie within the rasteriser's rounding of each
// other counted apart, and a sample within 0.02 px of a shape's boundary; then the translucent records (the conductors
// leaving the samples their path's earlier segments cover, a glow at its gradient, a light) blended over it in the
// GPU's order at each one's opacity where its depth passes (within a level a blend: lavapipe blends in fixed point);
// the samples averaged as the composite averages them.
#pragma once

#include "base/pymath/PyMath.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "passes/Composite.hpp"
#include "twin/item/ItemShader.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <format>
#include <span>
#include <string>
#include <vector>

namespace atlas::test::items {

namespace sh = atlas::shaders;

[[nodiscard]] inline std::vector<hlsl::float2> sample_positions(vk::SampleCountFlagBits samples) {
    switch (samples) {
        using F = hlsl::float2;
    case vk::SampleCountFlagBits::e4:
        return {F{0.375F, 0.125F}, F{0.875F, 0.375F}, F{0.125F, 0.625F}, F{0.625F, 0.875F}};
    case vk::SampleCountFlagBits::e2: return {F{0.75F, 0.75F}, F{0.25F, 0.25F}};
    default: return {F{0.5F, 0.5F}};
    }
}

/// Whether pixel (x, y) of a w × h frame lies on the composite's seam (buildings_vk_test.cpp's on_seam).
[[nodiscard]] inline bool on_seam(std::size_t x, std::size_t y, std::size_t w, std::size_t h) noexcept {
    const std::uint64_t above = std::uint64_t{y} * w;
    const std::uint64_t below = above + w;
    const std::uint64_t left = std::uint64_t{x} * h;
    const std::uint64_t right = left + h;
    return above < right && below > left;
}

/// A triangle the vertex stage draws: its corners (CSS px) and their depths.
struct Tri {
    std::array<std::array<double, 3>, 3> v;
};

/// Where a sample lies against a triangle: its barycentric weights' least (inside where 0 or more), its depth there
/// interpolated as the rasteriser does, and its distance from the triangle's nearest edge.
struct InTri {
    double least = -1e300;
    double depth = 0;
    double edge = 1e300;
};

[[nodiscard]] inline InTri in_tri(const Tri& t, double x, double y) {
    InTri r;
    const auto& a = t.v[0];
    const auto& b = t.v[1];
    const auto& c = t.v[2];
    const double det = ((b[0] - a[0]) * (c[1] - a[1])) - ((c[0] - a[0]) * (b[1] - a[1]));
    if (std::abs(det) < 1e-12) return r;
    const double l1 = (((x - a[0]) * (c[1] - a[1])) - ((c[0] - a[0]) * (y - a[1]))) / det;
    const double l2 = (((b[0] - a[0]) * (y - a[1])) - ((x - a[0]) * (b[1] - a[1]))) / det;
    const double l0 = 1.0 - l1 - l2;
    r.least = std::min({l0, l1, l2});
    r.depth = (l0 * a[2]) + (l1 * b[2]) + (l2 * c[2]);
    for (std::size_t i = 0; i < 3; ++i) {
        const auto& p = t.v[i];
        const auto& q = t.v[(i + 1) % 3];
        const double ex = q[0] - p[0];
        const double ey = q[1] - p[1];
        const double len = std::sqrt((ex * ex) + (ey * ey));
        if (len > 0) r.edge = std::min(r.edge, std::abs((ex * (y - p[1])) - (ey * (x - p[0]))) / len);
    }
    return r;
}

/// The quad's two triangles (corners 0 1 2, 0 2 3) item_segment_corner gives about a segment.
[[nodiscard]] inline std::array<Tri, 2> segment_tris(hlsl::float2 a, hlsl::float2 b, float d0, float d1, float e) {
    std::array<std::array<double, 3>, 4> k{};
    for (std::uint32_t i = 0; i < 4; ++i) {
        const sh::ItemCorner c = sh::item_segment_corner(a, b, d0, d1, e, i);
        k[i] = {static_cast<double>(c.p.x), static_cast<double>(c.p.y), static_cast<double>(c.depth)};
    }
    return {Tri{{k[0], k[1], k[2]}}, Tri{{k[0], k[2], k[3]}}};
}

/// What a frame's comparison with the exact raster found.
struct Compared {
    std::size_t pixels = 0;   ///< pixels some record covers in the reference
    std::size_t agree = 0;    ///< pixels equal to the reference
    std::size_t boundary = 0; ///< pixels that differ, a sample within 0.02 px of a shape's boundary
    std::size_t tied = 0;     ///< pixels that differ, two colours tied in depth at a sample
    std::size_t seam = 0;     ///< pixels that differ on the composite's seam
    std::size_t wrong = 0;
    std::string first;
};

/// A sample's coverage by one record: whether it covers it, whether it lies within the tolerance of the shape's
/// boundary, and its depth there.
struct Hit {
    bool inside = false;
    bool near = false;
    double depth = 0;
};

/// The exact raster of a frame's records, sample by sample (raster's): each sample's colour (8-bit levels over 255),
/// whether a record covers it (1 an opaque one, 2 a translucent one alone), whether it lies within the tolerance of a
/// shape's boundary, whether two colours tie in depth there, how many blends it took, and its depth.
struct Raster {
    std::uint32_t w = 0;
    std::uint32_t h = 0;
    std::vector<hlsl::float2> samples;
    std::vector<hlsl::float3> colour;
    std::vector<std::uint8_t> covered;
    std::vector<std::uint8_t> edge;
    std::vector<std::uint8_t> tie;
    std::vector<std::uint8_t> blended;
    std::vector<double> depth;
};

/// The exact raster of the records (faces, then the opaque parts, then the translucent ones, in the GPU's order) with
/// the moment's `tones` on a `w` × `h` frame cleared to `clear` (0xRRGGBB).
[[nodiscard]] inline Raster raster(std::span<const sh::ItemRecord> faces, std::span<const sh::ItemRecord> parts,
                                   std::span<const sh::ItemRecord> sides, std::span<const std::uint32_t> tones,
                                   const sh::FrameBlock& block, const gpu::Configuration& config, std::uint32_t w,
                                   std::uint32_t h, std::uint32_t clear) {
    const sh::FrameCamera& cam = block.camera;
    const bool painter = (cam.flags.x & sh::CAMERA_PAINTER) != 0U;
    Raster out{.w = w, .h = h, .samples = sample_positions(config.samples())};
    const std::vector<hlsl::float2>& samples = out.samples;
    const std::size_t n = samples.size();
    const auto density = static_cast<double>(cam.frame.z);
    const double tol = 0.02 / density;
    constexpr double slack = 3.814697265625e-6; // 2^-18: the rasteriser's rounding of a depth against the raster's
    std::vector<hlsl::float3>& colour = out.colour;
    colour.resize(std::size_t{w} * h * n);
    std::vector<std::uint8_t>& covered = out.covered;
    covered.assign(colour.size(), 0);
    std::vector<std::uint8_t>& edge = out.edge;
    edge.assign(colour.size(), 0);
    std::vector<std::uint8_t>& tie = out.tie;
    tie.assign(colour.size(), 0);
    std::vector<std::uint8_t>& blended = out.blended;
    blended.assign(colour.size(), 0);
    std::vector<double>& depth = out.depth;
    depth.assign(colour.size(), 0.0);
    std::vector<std::uint32_t> owner(colour.size(), 0xFFFF'FFFFU); // the colour the nearest opaque record gave
    const hlsl::float3 background{static_cast<float>((clear >> 16U) & 0xFFU) / 255.0F,
                                  static_cast<float>((clear >> 8U) & 0xFFU) / 255.0F,
                                  static_cast<float>(clear & 0xFFU) / 255.0F};
    std::ranges::fill(colour, background);
    const auto f = [](std::uint32_t v) { return std::bit_cast<float>(v); };
    // the samples of a box of CSS px, each its index, device point and CSS point
    const auto each = [&](double x0, double y0, double x1, double y1, auto&& at) {
        const auto px0 = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(x0 * density)) - 1);
        const auto py0 = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(y0 * density)) - 1);
        const auto px1 = std::min<std::int64_t>(w - 1, static_cast<std::int64_t>(std::ceil(x1 * density)) + 1);
        const auto py1 = std::min<std::int64_t>(h - 1, static_cast<std::int64_t>(std::ceil(y1 * density)) + 1);
        for (std::int64_t y = py0; y <= py1; ++y) {
            for (std::int64_t x = px0; x <= px1; ++x) {
                for (std::size_t q = 0; q < n; ++q) {
                    const hlsl::float2 dev{static_cast<float>(x) + samples[q].x, static_cast<float>(y) + samples[q].y};
                    const hlsl::float2 s = sh::css_of_output(cam, dev);
                    at((((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * n) + q, s,
                       hlsl::float2{static_cast<float>(x) + 0.5F, static_cast<float>(y) + 0.5F});
                }
            }
        }
    };
    // a hit by the triangles `tris` covering through `covers` (the shape's own test), its depth the triangles'
    const auto tri_hit = [&](std::span<const Tri> tris, hlsl::float2 s, bool covers, double boundary) {
        Hit hit{.inside = covers, .near = boundary <= tol};
        double best = -1e300;
        for (const Tri& t : tris) {
            const InTri in = in_tri(t, static_cast<double>(s.x), static_cast<double>(s.y));
            if (in.least > best) {
                best = in.least;
                hit.depth = in.depth;
            }
        }
        return hit;
    };
    // an opaque sample: the nearest wins (GREATER: the first of two tied); two colours within the slack are tied
    const auto opaque = [&](std::size_t k, const Hit& hit, std::uint32_t c, hlsl::float3 rgb) {
        if (hit.near) edge[k] = 1;
        if (!hit.inside) return;
        const double d = hit.depth;
        if (covered[k] != 0 && owner[k] != c && !painter && std::abs(d - depth[k]) <= std::max(d, depth[k]) * slack) {
            tie[k] = 1;
        }
        if (covered[k] != 0 && !(d > depth[k])) return;
        depth[k] = d;
        colour[k] = rgb;
        owner[k] = c;
        covered[k] = 1;
    };
    // the faces: the fan by its edges, each edge's stroke sample by sample
    for (const sh::ItemRecord& r : faces) {
        if ((r.a.x & sh::ITEM_RECORD_KIND) != sh::ITEM_FACE) continue;
        const std::uint32_t count = sh::item_count(r);
        std::vector<Tri> fan;
        double x0 = 1e300;
        double y0 = 1e300;
        double x1 = -1e300;
        double y1 = -1e300;
        float gx0 = sh::item_point(r, 0).x;
        float gx1 = gx0;
        for (std::uint32_t i = 0; i < count; ++i) {
            const hlsl::float2 p = sh::item_point(r, i);
            x0 = std::min(x0, static_cast<double>(p.x));
            y0 = std::min(y0, static_cast<double>(p.y));
            x1 = std::max(x1, static_cast<double>(p.x));
            y1 = std::max(y1, static_cast<double>(p.y));
            gx0 = std::min(gx0, p.x);
            gx1 = std::max(gx1, p.x);
        }
        for (std::uint32_t t = 0; t + 2 < count; ++t) {
            std::array<std::array<double, 3>, 3> v{};
            const std::array<std::uint32_t, 3> at{0U, t + 1, t + 2};
            for (std::size_t i = 0; i < 3; ++i) {
                const hlsl::float2 p = sh::item_point(r, at[i]);
                v[i] = {static_cast<double>(p.x), static_cast<double>(p.y),
                        static_cast<double>(sh::item_corner_depth(r, at[i]))};
            }
            fan.push_back(Tri{v});
        }
        const bool stroked = (r.a.x & sh::ITEM_STROKED) != 0U;
        const float half = f(r.a.w);
        const double grow = stroked ? static_cast<double>(half) + 1.0 : 0.0;
        const bool gradient = (r.a.x & sh::ITEM_GRADIENT) != 0U;
        each(x0 - grow, y0 - grow, x1 + grow, y1 + grow, [&](std::size_t k, hlsl::float2 s, hlsl::float2 centre) {
            hlsl::float3 rgb = sh::tree_rgb(r.a.y);
            if (gradient) {
                const hlsl::float2 c = sh::css_of_output(cam, centre);
                rgb = sh::item_gradient_at(sh::tree_rgb(r.a.y), sh::tree_rgb(r.a.w), sh::tree_rgb(r.e.w),
                                           (r.a.x & sh::ITEM_HIGH) != 0U, gx0, gx1, c.x);
            }
            const std::uint32_t key = gradient ? 0x1000'0000U ^ r.a.y : r.a.y;
            // the fan: inside a triangle by its edges
            bool inside = false;
            double boundary = 1e300;
            for (const Tri& t : fan) {
                const InTri in = in_tri(t, static_cast<double>(s.x), static_cast<double>(s.y));
                inside = inside || in.least >= 0;
                boundary = std::min(boundary, in.edge);
            }
            if (inside || boundary <= tol) opaque(k, tri_hit(fan, s, inside, boundary), key, rgb);
            if (!stroked) return;
            for (std::uint32_t e = 0; e < count; ++e) {
                const hlsl::float2 a = sh::item_point(r, e);
                const hlsl::float2 b = sh::item_point(r, (e + 1) % count);
                const bool covers = sh::tree_segment_covers(s, a, b, half, sh::ITEM_ROUND);
                const double ex = static_cast<double>(b.x) - static_cast<double>(a.x);
                const double ey = static_cast<double>(b.y) - static_cast<double>(a.y);
                const double dx = static_cast<double>(s.x) - static_cast<double>(a.x);
                const double dy = static_cast<double>(s.y) - static_cast<double>(a.y);
                const double ee = (ex * ex) + (ey * ey);
                const double t = ee > 0 ? std::clamp(((dx * ex) + (dy * ey)) / ee, 0.0, 1.0) : 0.0;
                const double dist = pymath::hypot(dx - (ex * t), dy - (ey * t));
                if (!covers && std::abs(dist - static_cast<double>(half)) > tol) continue;
                const std::array<Tri, 2> quad = segment_tris(
                    a, b, sh::item_corner_depth(r, e), sh::item_corner_depth(r, (e + 1) % count), half + cam.device.z);
                opaque(k, tri_hit(quad, s, covers, std::abs(dist - static_cast<double>(half))), key, rgb);
            }
        });
    }
    // a segment's or a circle's samples, and its depth there
    const auto part_hit = [&](const sh::ItemRecord& r, hlsl::float2 s) {
        const std::uint32_t kind = r.a.x & sh::ITEM_RECORD_KIND;
        const hlsl::float2 a = sh::item_point(r, 0);
        const float radius = f(r.a.w);
        if (kind == sh::ITEM_SEGMENT) {
            const hlsl::float2 b = sh::item_point(r, 1);
            const bool covers = sh::tree_segment_covers(s, a, b, radius, r.a.x);
            const double ex = static_cast<double>(b.x) - static_cast<double>(a.x);
            const double ey = static_cast<double>(b.y) - static_cast<double>(a.y);
            const double dx = static_cast<double>(s.x) - static_cast<double>(a.x);
            const double dy = static_cast<double>(s.y) - static_cast<double>(a.y);
            const double ee = (ex * ex) + (ey * ey);
            const double along = ee > 0 ? ((dx * ex) + (dy * ey)) / ee : 0.0;
            const double t = std::clamp(along, 0.0, 1.0);
            double boundary = std::abs(pymath::hypot(dx - (ex * t), dy - (ey * t)) - static_cast<double>(radius));
            const double len = std::sqrt(ee);
            if (len > 0 && (r.a.x & sh::ITEM_ROUND_START) == 0U) boundary = std::min(boundary, std::abs(along) * len);
            if (len > 0 && (r.a.x & sh::ITEM_ROUND_END) == 0U)
                boundary = std::min(boundary, std::abs(along - 1.0) * len);
            std::array<Tri, 2> quad = segment_tris(a, b, f(r.d.z), f(r.d.w), radius + cam.device.z);
            if ((r.a.x & sh::ITEM_PLANE) != 0U) {
                // on its plane: each corner at the plane's depth there (items.slangh's item_part_depth); the quad's
                // triangles are its corners 0 1 2 and 0 2 3
                std::array<double, 4> z{};
                for (std::uint32_t i = 0; i < 4; ++i) {
                    const sh::ItemCorner k =
                        sh::item_segment_corner(a, b, f(r.d.z), f(r.d.w), radius + cam.device.z, i);
                    z.at(i) = static_cast<double>(sh::item_part_depth(r, k));
                }
                quad[0].v[0][2] = z[0];
                quad[0].v[1][2] = z[1];
                quad[0].v[2][2] = z[2];
                quad[1].v[0][2] = z[0];
                quad[1].v[1][2] = z[2];
                quad[1].v[2][2] = z[3];
            }
            return tri_hit(quad, s, covers, boundary);
        }
        const bool covers = sh::tree_circle_covers(s, a, radius);
        const double d = pymath::hypot(static_cast<double>(s.x) - static_cast<double>(a.x),
                                       static_cast<double>(s.y) - static_cast<double>(a.y));
        return Hit{.inside = covers,
                   .near = std::abs(d - static_cast<double>(radius)) <= tol,
                   .depth = static_cast<double>(f(r.d.z))};
    };
    const auto part_box = [&](const sh::ItemRecord& r) {
        const hlsl::float2 a = sh::item_point(r, 0);
        const hlsl::float2 b = (r.a.x & sh::ITEM_RECORD_KIND) == sh::ITEM_SEGMENT ? sh::item_point(r, 1) : a;
        const double e = static_cast<double>(f(r.a.w)) + 1.0;
        const auto d = [](float v) { return static_cast<double>(v); };
        return std::array<double, 4>{std::min(d(a.x), d(b.x)) - e, std::min(d(a.y), d(b.y)) - e,
                                     std::max(d(a.x), d(b.x)) + e, std::max(d(a.y), d(b.y)) + e};
    };
    for (const sh::ItemRecord& r : parts) {
        if ((r.a.x & sh::ITEM_RECORD_KIND) == sh::ITEM_NONE) continue;
        const std::array<double, 4> b = part_box(r);
        each(b[0], b[1], b[2], b[3], [&](std::size_t k, hlsl::float2 s, hlsl::float2) {
            opaque(k, part_hit(r, s), r.a.y, sh::tree_rgb(r.a.y));
        });
    }
    // the translucent ones in the GPU's order: a conveyor's segment leaves what its path's earlier segments cover
    for (std::size_t i = 0; i < sides.size(); ++i) {
        const sh::ItemRecord& r = sides[i];
        const std::uint32_t kind = r.a.x & sh::ITEM_RECORD_KIND;
        if (kind == sh::ITEM_NONE) continue;
        const std::uint32_t back = r.a.x >> sh::ITEM_BACK_SHIFT;
        const std::array<double, 4> b = part_box(r);
        each(b[0], b[1], b[2], b[3], [&](std::size_t k, hlsl::float2 s, hlsl::float2 centre) {
            const Hit hit = part_hit(r, s);
            if (hit.near) edge[k] = 1;
            if (!hit.inside) return;
            for (std::uint32_t j = 1; j <= back && j <= i; ++j) {
                if (part_hit(sides[i - j], s).inside) return;
            }
            if (covered[k] != 0 && !(hit.depth > depth[k])) return;
            hlsl::float3 c = sh::tree_rgb(r.a.y);
            float alpha = f(r.a.z);
            if (kind == sh::ITEM_GLOW) {
                const hlsl::float2 at = sh::css_of_output(cam, centre);
                const hlsl::float2 a = sh::item_point(r, 0);
                const float dx = at.x - a.x;
                const float dy = at.y - a.y;
                const hlsl::float4 g = sh::item_glow_at(
                    sh::tree_rgb(tones[sh::ITEM_TONE_GLOW]), sh::tree_rgb(tones[sh::ITEM_TONE_GLOW + 1]),
                    sh::tree_rgb(tones[sh::ITEM_TONE_GLOW + 2]), f(tones[sh::ITEM_TONE_GLOW_OPACITY]),
                    f(tones[sh::ITEM_TONE_GLOW_OPACITY + 1]), f(tones[sh::ITEM_TONE_GLOW_OPACITY + 2]),
                    std::sqrt((dx * dx) + (dy * dy)) / f(r.a.w));
                c = {g.x, g.y, g.z};
                alpha = g.w;
            }
            const hlsl::float3 under = colour[k];
            const hlsl::float3 over = (c * alpha) + (under * (1.0F - alpha));
            colour[k] = {std::round(over.x * 255.0F) / 255.0F, std::round(over.y * 255.0F) / 255.0F,
                         std::round(over.z * 255.0F) / 255.0F};
            ++blended[k];
            if (covered[k] == 0) covered[k] = 2;
        });
    }
    return out;
}

/// The GPU's frame `got` against raster `r` (raster's, maybe blended over since).
[[nodiscard]] inline Compared compare(const gpu::Pixels& got, const Raster& r, const gpu::Configuration& config) {
    const std::uint32_t w = got.width;
    const std::uint32_t h = got.height;
    const std::size_t n = r.samples.size();
    const std::vector<hlsl::float3>& colour = r.colour;
    const std::vector<std::uint8_t>& covered = r.covered;
    const std::vector<std::uint8_t>& edge = r.edge;
    const std::vector<std::uint8_t>& tie = r.tie;
    const std::vector<std::uint8_t>& blended = r.blended;
    Compared c;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        bool any = false;
        bool near_edge = false;
        bool tied = false;
        std::uint32_t blends = 0;
        for (std::size_t q = 0; q < n; ++q) {
            any = any || covered[(i * n) + q] != 0 || edge[(i * n) + q] != 0;
            near_edge = near_edge || edge[(i * n) + q] != 0;
            tied = tied || tie[(i * n) + q] != 0;
            blends = std::max<std::uint32_t>(blends, blended[(i * n) + q]);
        }
        if (!any) continue;
        ++c.pixels;
        shaders::FrameLayers stubbed{};
        stubbed.steps = {0U, 255U, 0U, 0U};
        const hlsl::float3 p =
            passes::composite_pixel(stubbed, std::span(colour.data() + (i * n), n), {0.5F, 0.5F}, {1.0F, 1.0F});
        const std::array<std::uint32_t, 3> want{static_cast<std::uint32_t>(p.x), static_cast<std::uint32_t>(p.y),
                                                static_cast<std::uint32_t>(p.z)};
        const std::array<std::uint32_t, 3> have{got.rgba[i * 4], got.rgba[(i * 4) + 1], got.rgba[(i * 4) + 2]};
        // a level a blend, and one for a gradient's float against the raster's
        const std::uint32_t levels = blends + 1U;
        bool same = true;
        for (std::size_t k = 0; k < 3; ++k) {
            if ((want[k] > have[k] ? want[k] - have[k] : have[k] - want[k]) > levels) same = false;
        }
        if (same) {
            ++c.agree;
        } else if (near_edge) {
            ++c.boundary;
        } else if (tied) {
            ++c.tied;
        } else if (config.target == gpu::CompositeTarget::resolve && on_seam(i % w, i / w, w, h)) {
            ++c.seam;
        } else {
            if (c.wrong == 0) {
                c.first =
                    std::format("pixel ({}, {}): {:02x}{:02x}{:02x} where the exact raster has {:02x}{:02x}{:02x}",
                                i % w, i / w, have[0], have[1], have[2], want[0], want[1], want[2]);
            }
            ++c.wrong;
        }
    }
    return c;
}

/// The GPU's frame `got` against the exact raster of the records (faces, then the opaque parts, then the translucent
/// ones, in the GPU's order) with the moment's `tones`.
[[nodiscard]] inline Compared compare(const gpu::Pixels& got, std::span<const sh::ItemRecord> faces,
                                      std::span<const sh::ItemRecord> parts, std::span<const sh::ItemRecord> sides,
                                      std::span<const std::uint32_t> tones, const sh::FrameBlock& block,
                                      const gpu::Configuration& config, std::uint32_t clear) {
    return compare(got, raster(faces, parts, sides, tones, block, config, got.width, got.height, clear), config);
}

} // namespace atlas::test::items
