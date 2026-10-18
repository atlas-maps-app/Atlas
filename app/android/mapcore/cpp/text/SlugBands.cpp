#include "text/SlugBands.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <numeric>

namespace atlas::text::slug {

namespace {

/// A curve's place in the curve texture and its control points' extent, em units.
struct Placed {
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    float x0 = 0.0F;
    float x1 = 0.0F;
    float y0 = 0.0F;
    float y1 = 0.0F;
};

/// The band a coordinate falls in, as the shader computes it (a float, truncated, clamped), here the floor of a value
/// moved by a margin.
[[nodiscard]] std::uint32_t band_of(float v, std::uint32_t count) noexcept {
    const float f = std::floor(v);
    if (!(f > 0.0F)) return 0;
    if (f >= static_cast<float>(count - 1)) return count - 1;
    return static_cast<std::uint32_t>(f);
}

/// A band's margin: 1/64 of a band, so a sample's band in the GPU's rounding still lists a curve that reaches it.
constexpr float band_margin = 1.0F / 64.0F;

/// HLSL's saturate, with a NaN at 0 (the shader's select-based one): a root of a curve flat across the ray, solved as a
/// line of no slope, is not a number and must count for nothing.
[[nodiscard]] float saturate(float v) noexcept { return v > 0.0F ? (v < 1.0F ? v : 1.0F) : 0.0F; }

/// The shader's SolveHorizPoly (and, with x and y exchanged, SolveVertPoly): the coordinates along the ray where the
/// quadratic crosses it, for its two roots.
struct Roots {
    float r1 = 0.0F;
    float r2 = 0.0F;
};

[[nodiscard]] Roots solve(float p1u, float p1v, float p2u, float p2v, float p3u, float p3v) noexcept {
    // u is along the ray, v across it: v(t) = a.v t^2 - 2 b.v t + p1.v; the discriminant clamped at zero, so missing
    // roots become the double root at the extremum, and a nearly linear v solved as a line.
    const float au = (p1u - (p2u * 2.0F)) + p3u;
    const float av = (p1v - (p2v * 2.0F)) + p3v;
    const float bu = p1u - p2u;
    const float bv = p1v - p2v;
    const float ra = 1.0F / av;
    const float rb = 0.5F / bv;
    const float d = std::sqrt(std::max((bv * bv) - (av * p1v), 0.0F));
    float t1 = (bv - d) * ra;
    float t2 = (bv + d) * ra;
    if (std::fabs(av) < 1.0F / 65536.0F) {
        t1 = p1v * rb;
        t2 = t1;
    }
    return {.r1 = (((au * t1) - (bu * 2.0F)) * t1) + p1u, .r2 = (((au * t2) - (bu * 2.0F)) * t2) + p1u};
}

} // namespace

std::string_view describe(AtlasError error) noexcept {
    switch (error) {
    case AtlasError::full: return "the glyph textures are full";
    case AtlasError::too_large: return "the glyph is too large for the textures";
    case AtlasError::not_finite: return "the outline has a point that is not finite";
    }
    return "unknown atlas error";
}

std::uint32_t band_count(std::size_t curves) noexcept {
    return static_cast<std::uint32_t>(std::clamp<std::size_t>((curves + 1) / 2, 1, max_bands));
}

std::uint32_t root_code(float c1, float c2, float c3) noexcept {
    const std::uint32_t i1 = std::bit_cast<std::uint32_t>(c1) >> 31U;
    const std::uint32_t i2 = std::bit_cast<std::uint32_t>(c2) >> 30U;
    const std::uint32_t i3 = std::bit_cast<std::uint32_t>(c3) >> 29U;
    std::uint32_t shift = (i2 & 2U) | (i1 & ~2U);
    shift = (i3 & 4U) | (shift & ~4U);
    return (0x2E74U >> shift) & 0x0101U;
}

std::uint32_t Atlas::curve_rows() const noexcept {
    return static_cast<std::uint32_t>(curves_.size() >> log_texture_width);
}

std::uint32_t Atlas::band_rows() const noexcept {
    return static_cast<std::uint32_t>(bands_.size() >> log_texture_width);
}

std::size_t Atlas::bytes() const noexcept {
    return (curves_.size() * sizeof(CurveTexel)) + (bands_.size() * sizeof(BandTexel));
}

bool Atlas::reserve_in_row(std::uint32_t& x, std::uint32_t& y, std::uint32_t count) noexcept {
    if (count > texture_width) return false;
    if (x + count > texture_width) {
        x = 0;
        ++y;
    }
    return true;
}

void Atlas::grow_curves(std::uint32_t rows) {
    const std::size_t want = static_cast<std::size_t>(rows) << log_texture_width;
    if (curves_.size() < want) curves_.resize(want);
}

void Atlas::grow_bands(std::uint32_t rows) {
    const std::size_t want = static_cast<std::size_t>(rows) << log_texture_width;
    if (bands_.size() < want) bands_.resize(want);
}

std::expected<Glyph, AtlasError> Atlas::add(const GlyphOutline& outline, unsigned units_per_em) {
    Glyph g;
    g.glyph = outline.glyph;
    if (outline.empty() || units_per_em == 0) return g;
    const auto em = static_cast<float>(units_per_em);

    // The points in em units, and their box.
    std::vector<OutlinePoint> points(outline.points.size());
    bool first = true;
    for (std::size_t i = 0; i < points.size(); ++i) {
        const OutlinePoint p{.x = outline.points[i].x / em, .y = outline.points[i].y / em};
        if (!std::isfinite(p.x) || !std::isfinite(p.y)) return std::unexpected(AtlasError::not_finite);
        points[i] = p;
        g.box = first ? OutlineBox{.x0 = p.x, .y0 = p.y, .x1 = p.x, .y1 = p.y}
                      : OutlineBox{.x0 = std::min(g.box.x0, p.x),
                                   .y0 = std::min(g.box.y0, p.y),
                                   .x1 = std::max(g.box.x1, p.x),
                                   .y1 = std::max(g.box.y1, p.y)};
        first = false;
    }

    // Each contour's place in the curve texture, in one row, and every curve's texel and extent; written once every
    // check has passed, so a refused glyph leaves the textures as they were.
    std::vector<Placed> placed;
    placed.reserve(outline.curve_count());
    std::vector<TexelLocation> starts;
    std::uint32_t cx = curve_x_;
    std::uint32_t cy = curve_y_;
    for (std::size_t c = 0; c < outline.contours.size(); ++c) {
        const std::size_t start = outline.contours[c];
        const std::size_t end = c + 1 < outline.contours.size() ? outline.contours[c + 1] : points.size();
        const auto n = static_cast<std::uint32_t>((end - start - 1) / 2); // curves
        if (!reserve_in_row(cx, cy, n + 1)) return std::unexpected(AtlasError::too_large);
        if (cy >= 65536U) return std::unexpected(AtlasError::full);
        starts.push_back({.x = cx, .y = cy});
        for (std::uint32_t k = 0; k < n; ++k) {
            const std::size_t at = start + (2 * std::size_t{k});
            const OutlinePoint& on = points[at];
            const OutlinePoint& off = points[at + 1];
            const OutlinePoint& to = points[at + 2];
            placed.push_back({.x = cx + k,
                              .y = cy,
                              .x0 = std::min({on.x, off.x, to.x}),
                              .x1 = std::max({on.x, off.x, to.x}),
                              .y0 = std::min({on.y, off.y, to.y}),
                              .y1 = std::max({on.y, off.y, to.y})});
        }
        cx += n + 1;
    }
    if (placed.empty()) return g;

    // The bands: equal parts of the box, each listing the curves whose extent reaches it.
    const std::uint32_t h = band_count(placed.size());
    const std::uint32_t v = h;
    const float width = g.box.x1 - g.box.x0;
    const float height = g.box.y1 - g.box.y0;
    g.scale_x = width > 0.0F ? static_cast<float>(v) / width : 0.0F;
    g.scale_y = height > 0.0F ? static_cast<float>(h) / height : 0.0F;
    g.offset_x = -g.box.x0 * g.scale_x;
    g.offset_y = -g.box.y0 * g.scale_y;
    std::vector<std::vector<std::uint32_t>> lists(h + v);
    for (std::uint32_t i = 0; i < placed.size(); ++i) {
        const Placed& p = placed[i];
        const std::uint32_t j0 = band_of((p.y0 * g.scale_y) + g.offset_y - band_margin, h);
        const std::uint32_t j1 = band_of((p.y1 * g.scale_y) + g.offset_y + band_margin, h);
        for (std::uint32_t j = j0; j <= j1; ++j) lists[j].push_back(i);
        const std::uint32_t k0 = band_of((p.x0 * g.scale_x) + g.offset_x - band_margin, v);
        const std::uint32_t k1 = band_of((p.x1 * g.scale_x) + g.offset_x + band_margin, v);
        for (std::uint32_t k = k0; k <= k1; ++k) lists[h + k].push_back(i);
    }
    // Horizontal rays stop at the first curve wholly to the sample's left, vertical ones below it.
    for (std::uint32_t j = 0; j < h + v; ++j) {
        const bool horizontal = j < h;
        std::ranges::stable_sort(lists[j], [&](std::uint32_t a, std::uint32_t b) {
            return horizontal ? placed[a].x1 > placed[b].x1 : placed[a].y1 > placed[b].y1;
        });
    }

    // The glyph's block: its headers in one row, then its lists.
    std::uint32_t bx = band_x_;
    std::uint32_t by = band_y_;
    if (!reserve_in_row(bx, by, h + v)) return std::unexpected(AtlasError::too_large);
    std::size_t entries = 0;
    for (const auto& list : lists) entries += list.size();
    if (h + v + entries > 65535U) return std::unexpected(AtlasError::too_large);
    const TexelLocation end = band_location(bx, by, static_cast<std::uint32_t>(h + v + entries));
    if (end.y >= 65536U || cy >= 65536U) return std::unexpected(AtlasError::full);

    // The contours' texels: texel k of a contour is (point 2k, point 2k + 1), its last (its first point again, 0, 0).
    grow_curves(cy + 1);
    for (std::size_t c = 0; c < outline.contours.size(); ++c) {
        const std::size_t start = outline.contours[c];
        const std::size_t stop = c + 1 < outline.contours.size() ? outline.contours[c + 1] : points.size();
        const auto n = static_cast<std::uint32_t>((stop - start - 1) / 2);
        const TexelLocation at = starts[c];
        for (std::uint32_t k = 0; k <= n; ++k) {
            const std::size_t point = start + (2 * std::size_t{k});
            const OutlinePoint& on = points[point];
            const OutlinePoint off = k < n ? points[point + 1] : OutlinePoint{};
            curves_[(static_cast<std::size_t>(at.y) << log_texture_width) + at.x + k] = {
                .x0 = on.x, .y0 = on.y, .x1 = off.x, .y1 = off.y};
        }
    }
    grow_bands(end.y + 1);
    auto put = [&](std::uint32_t offset, BandTexel texel) {
        const TexelLocation at = band_location(bx, by, offset);
        bands_[(static_cast<std::size_t>(at.y) << log_texture_width) + at.x] = texel;
    };
    std::uint32_t offset = h + v;
    for (std::uint32_t j = 0; j < h + v; ++j) {
        put(j, {.a = static_cast<std::uint16_t>(lists[j].size()), .b = static_cast<std::uint16_t>(offset)});
        for (const std::uint32_t i : lists[j]) {
            put(offset++, {.a = static_cast<std::uint16_t>(placed[i].x), .b = static_cast<std::uint16_t>(placed[i].y)});
        }
    }

    // Committed: the cursors move on.
    curve_x_ = cx;
    curve_y_ = cy;
    band_x_ = end.x;
    band_y_ = end.y;
    g.band_x = bx;
    g.band_y = by;
    g.hbands = h;
    g.vbands = v;
    g.curves = static_cast<std::uint32_t>(placed.size());
    return g;
}

namespace {

/// A ray's sums: its coverage and how near its nearest crossing lies (1 at the sample, 0 half a pixel off or more).
struct Ray {
    float coverage = 0.0F;
    float weight = 0.0F;
};

/// One curve's part in the horizontal ray (`horizontal`, its points relative to the sample) or the vertical one:
/// SlugRender's loop body. Returns false where the curve lies wholly behind the sample by more than half a pixel, so
/// that the curves sorted after it can be passed over.
bool contribute(Ray& ray, bool horizontal, float p1x, float p1y, float p2x, float p2y, float p3x, float p3y,
                float pixels_per_em) noexcept {
    if (horizontal) {
        if (std::max({p1x, p2x, p3x}) * pixels_per_em < -0.5F) return false;
        const std::uint32_t code = root_code(p1y, p2y, p3y);
        if (code == 0U) return true;
        const Roots r = solve(p1x, p1y, p2x, p2y, p3x, p3y);
        const float r1 = r.r1 * pixels_per_em;
        const float r2 = r.r2 * pixels_per_em;
        if ((code & 1U) != 0U) {
            ray.coverage += saturate(r1 + 0.5F);
            ray.weight = std::max(ray.weight, saturate(1.0F - (std::fabs(r1) * 2.0F)));
        }
        if (code > 1U) {
            ray.coverage -= saturate(r2 + 0.5F);
            ray.weight = std::max(ray.weight, saturate(1.0F - (std::fabs(r2) * 2.0F)));
        }
        return true;
    }
    if (std::max({p1y, p2y, p3y}) * pixels_per_em < -0.5F) return false;
    const std::uint32_t code = root_code(p1x, p2x, p3x);
    if (code == 0U) return true;
    const Roots r = solve(p1y, p1x, p2y, p2x, p3y, p3x);
    const float r1 = r.r1 * pixels_per_em;
    const float r2 = r.r2 * pixels_per_em;
    if ((code & 1U) != 0U) {
        ray.coverage -= saturate(r1 + 0.5F);
        ray.weight = std::max(ray.weight, saturate(1.0F - (std::fabs(r1) * 2.0F)));
    }
    if (code > 1U) {
        ray.coverage += saturate(r2 + 0.5F);
        ray.weight = std::max(ray.weight, saturate(1.0F - (std::fabs(r2) * 2.0F)));
    }
    return true;
}

/// CalcCoverage: the rays weighted by how near their crossings are; where neither is near, the smaller; nonzero fill.
float combine(const Ray& x, const Ray& y) noexcept {
    const float weighted =
        std::fabs((x.coverage * x.weight) + (y.coverage * y.weight)) / std::max(x.weight + y.weight, 1.0F / 65536.0F);
    return saturate(std::max(weighted, std::min(std::fabs(x.coverage), std::fabs(y.coverage))));
}

/// A sample's band along one axis, as the shader takes it: int() of the transformed coordinate (toward zero), clamped.
std::uint32_t sample_band(float v, std::uint32_t count) noexcept {
    if (!(v >= 1.0F)) return 0; // int() of anything below 1, negative or NaN, clamped, is band 0
    if (v >= static_cast<float>(count)) return count - 1;
    return static_cast<std::uint32_t>(v);
}

} // namespace

float coverage(const Atlas& atlas, const Glyph& glyph, float sample_x, float sample_y, float pixels_per_em_x,
               float pixels_per_em_y) noexcept {
    if (!glyph.drawn()) return 0.0F;
    const std::uint32_t hband = sample_band((sample_y * glyph.scale_y) + glyph.offset_y, glyph.hbands);
    const std::uint32_t vband = sample_band((sample_x * glyph.scale_x) + glyph.offset_x, glyph.vbands);
    std::array<Ray, 2> rays{};
    for (std::size_t axis = 0; axis < 2; ++axis) {
        const bool horizontal = axis == 0;
        const BandTexel header =
            atlas.band_texel(glyph.band_x + (horizontal ? hband : glyph.hbands + vband), glyph.band_y);
        for (std::uint32_t k = 0; k < header.a; ++k) {
            const TexelLocation entry = band_location(glyph.band_x, glyph.band_y, header.b + k);
            const BandTexel at = atlas.band_texel(entry.x, entry.y);
            const CurveTexel p12 = atlas.curve_texel(at.a, at.b);
            const CurveTexel p3 = atlas.curve_texel(at.a + 1U, at.b);
            if (!contribute(rays[axis], horizontal, p12.x0 - sample_x, p12.y0 - sample_y, p12.x1 - sample_x,
                            p12.y1 - sample_y, p3.x0 - sample_x, p3.y0 - sample_y,
                            horizontal ? pixels_per_em_x : pixels_per_em_y)) {
                break;
            }
        }
    }
    return combine(rays[0], rays[1]);
}

std::vector<Curve> em_curves(const GlyphOutline& outline, unsigned units_per_em) {
    std::vector<Curve> out;
    if (units_per_em == 0) return out;
    const auto em = static_cast<float>(units_per_em);
    for (std::size_t c = 0; c < outline.contours.size(); ++c) {
        const auto points = outline.contour(c);
        for (std::size_t k = 0; k + 2 < points.size(); k += 2) {
            out.push_back({points[k].x / em, points[k].y / em, points[k + 1].x / em, points[k + 1].y / em,
                           points[k + 2].x / em, points[k + 2].y / em});
        }
    }
    return out;
}

float coverage_unbanded(std::span<const Curve> curves, float sample_x, float sample_y, float pixels_per_em_x,
                        float pixels_per_em_y) noexcept {
    std::array<Ray, 2> rays{};
    for (const Curve& c : curves) {
        for (std::size_t axis = 0; axis < 2; ++axis) {
            const bool horizontal = axis == 0;
            (void)contribute(rays[axis], horizontal, c.x1 - sample_x, c.y1 - sample_y, c.x2 - sample_x, c.y2 - sample_y,
                             c.x3 - sample_x, c.y3 - sample_y, horizontal ? pixels_per_em_x : pixels_per_em_y);
        }
    }
    return combine(rays[0], rays[1]);
}

} // namespace atlas::text::slug
