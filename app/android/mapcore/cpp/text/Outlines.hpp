// Glyph outlines (plan-vector D13, K13): each glyph's closed contours at its instance's weight, as HarfBuzz draws them
// from the variable font (glyf with gvar's deltas, hb_font_draw_glyph), in the font's own units with y up. Every
// segment is a quadratic Bézier, as Slug wants them: TrueType's own quadratics as they are, a line as the quadratic
// whose control point is its middle, a cubic (CFF only; the bundled fonts have none) split into quadratics.
//
// A contour is kept as its points in order, on-curve and control alternating, its first point repeated at its end:
// curve k of a contour is (point 2k, point 2k + 1, point 2k + 2), the layout Slug's curve texture takes
// (SlugBands.hpp).
#pragma once

#include "text/Fonts.hpp"

#include <cstdint>
#include <expected>
#include <span>
#include <vector>

namespace atlas::text {

/// A point in a font's units, y up.
struct OutlinePoint {
    float x = 0.0F;
    float y = 0.0F;

    friend constexpr bool operator==(const OutlinePoint&, const OutlinePoint&) = default;
};

/// A box in a font's units.
struct OutlineBox {
    float x0 = 0.0F;
    float y0 = 0.0F;
    float x1 = 0.0F;
    float y1 = 0.0F;

    [[nodiscard]] constexpr bool empty() const noexcept { return !(x1 > x0) || !(y1 > y0); }
};

/// A glyph's outline: its contours' points (each contour 2n + 1 points for n curves), where each contour starts, and
/// the box around every point.
struct GlyphOutline {
    std::uint32_t glyph = 0;
    std::vector<OutlinePoint> points;
    std::vector<std::uint32_t> contours; ///< index of each contour's first point; a contour ends where the next starts
    OutlineBox box;

    [[nodiscard]] std::size_t curve_count() const noexcept;
    /// Contour c's points.
    [[nodiscard]] std::span<const OutlinePoint> contour(std::size_t c) const noexcept;
    [[nodiscard]] bool empty() const noexcept { return contours.empty(); }
};

/// Collects a glyph's outline from HarfBuzz's drawing calls (or a test's): moves, lines, quadratics, cubics, closes.
class OutlineBuilder {
public:
    void move_to(float x, float y);
    void line_to(float x, float y);
    void quadratic_to(float cx, float cy, float x, float y);
    void cubic_to(float c1x, float c1y, float c2x, float c2y, float x, float y);
    void close();

    /// The outline drawn, closing any contour left open; contours of no area (fewer than one curve, or every point
    /// the same) are dropped.
    [[nodiscard]] GlyphOutline finish(std::uint32_t glyph);

private:
    void start_if_needed();
    std::vector<OutlinePoint> points_;
    std::vector<std::uint32_t> contours_;
    OutlinePoint start_;
    OutlinePoint last_;
    bool open_ = false;
};

/// `glyph`'s outline at `instance`'s weight, in the font's units; an empty outline for a glyph without contours (a
/// space). Allocates: draw a glyph once and keep it.
[[nodiscard]] GlyphOutline outline(const Instance& instance, std::uint32_t glyph);

} // namespace atlas::text
