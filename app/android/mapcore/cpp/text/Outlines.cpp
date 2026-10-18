#include "text/Outlines.hpp"

#include <algorithm>
#include <hb.h>

namespace atlas::text {

std::size_t GlyphOutline::curve_count() const noexcept {
    std::size_t curves = 0;
    for (std::size_t c = 0; c < contours.size(); ++c) curves += (contour(c).size() - 1) / 2;
    return curves;
}

std::span<const OutlinePoint> GlyphOutline::contour(std::size_t c) const noexcept {
    const std::size_t start = contours[c];
    const std::size_t end = c + 1 < contours.size() ? contours[c + 1] : points.size();
    return std::span(points).subspan(start, end - start);
}

void OutlineBuilder::start_if_needed() {
    if (open_) return;
    // A line or curve without a move begins at the last point (HarfBuzz always moves first; a test may not).
    contours_.push_back(static_cast<std::uint32_t>(points_.size()));
    points_.push_back(last_);
    start_ = last_;
    open_ = true;
}

void OutlineBuilder::move_to(float x, float y) {
    if (open_) close();
    last_ = {.x = x, .y = y};
    open_ = false;
    start_if_needed();
}

void OutlineBuilder::line_to(float x, float y) {
    start_if_needed();
    const OutlinePoint to{.x = x, .y = y};
    if (to == last_) return;
    // A line is the quadratic whose control point is its middle.
    points_.push_back({.x = (last_.x + x) * 0.5F, .y = (last_.y + y) * 0.5F});
    points_.push_back(to);
    last_ = to;
}

void OutlineBuilder::quadratic_to(float cx, float cy, float x, float y) {
    start_if_needed();
    const OutlinePoint control{.x = cx, .y = cy};
    const OutlinePoint to{.x = x, .y = y};
    if (to == last_ && control == last_) return;
    points_.push_back(control);
    points_.push_back(to);
    last_ = to;
}

void OutlineBuilder::cubic_to(float c1x, float c1y, float c2x, float c2y, float x, float y) {
    // Four pieces, each the quadratic through the piece's ends whose control point is (3 (c1 + c2) - (p0 + p3)) / 4,
    // the midpoint approximation: within a small part of an em for the cubics fonts draw.
    start_if_needed();
    const OutlinePoint p0 = last_;
    auto at = [&](float t) {
        const float u = 1.0F - t;
        const float a = u * u * u;
        const float b = 3.0F * u * u * t;
        const float c = 3.0F * u * t * t;
        const float d = t * t * t;
        return OutlinePoint{.x = (a * p0.x) + (b * c1x) + (c * c2x) + (d * x),
                            .y = (a * p0.y) + (b * c1y) + (c * c2y) + (d * y)};
    };
    auto tangent = [&](float t) { // the derivative, divided by 3
        const float u = 1.0F - t;
        return OutlinePoint{.x = (u * u * (c1x - p0.x)) + (2.0F * u * t * (c2x - c1x)) + (t * t * (x - c2x)),
                            .y = (u * u * (c1y - p0.y)) + (2.0F * u * t * (c2y - c1y)) + (t * t * (y - c2y))};
    };
    constexpr int pieces = 4;
    for (int i = 0; i < pieces; ++i) {
        const float t0 = static_cast<float>(i) / pieces;
        const float t1 = static_cast<float>(i + 1) / pieces;
        const OutlinePoint a = at(t0);
        const OutlinePoint b = i + 1 == pieces ? OutlinePoint{.x = x, .y = y} : at(t1);
        // The sub-cubic's control points from its ends' tangents, then the midpoint approximation.
        const float h = (t1 - t0);
        const OutlinePoint ta = tangent(t0);
        const OutlinePoint tb = tangent(t1);
        const OutlinePoint s1{.x = a.x + (h * ta.x), .y = a.y + (h * ta.y)};
        const OutlinePoint s2{.x = b.x - (h * tb.x), .y = b.y - (h * tb.y)};
        quadratic_to(((3.0F * (s1.x + s2.x)) - (a.x + b.x)) * 0.25F, ((3.0F * (s1.y + s2.y)) - (a.y + b.y)) * 0.25F,
                     b.x, b.y);
    }
}

void OutlineBuilder::close() {
    if (!open_) return;
    if (!(last_ == start_)) line_to(start_.x, start_.y);
    open_ = false;
}

GlyphOutline OutlineBuilder::finish(std::uint32_t glyph) {
    close();
    GlyphOutline out;
    out.glyph = glyph;
    bool first = true;
    for (std::size_t c = 0; c < contours_.size(); ++c) {
        const std::size_t start = contours_[c];
        const std::size_t end = c + 1 < contours_.size() ? contours_[c + 1] : points_.size();
        // At least one curve, closed, and not every point the same.
        if (end - start < 3 || (end - start) % 2 == 0) continue;
        const std::span<const OutlinePoint> contour = std::span(points_).subspan(start, end - start);
        const bool flat = std::ranges::all_of(contour, [&](const OutlinePoint& p) { return p == contour.front(); });
        if (flat) continue;
        out.contours.push_back(static_cast<std::uint32_t>(out.points.size()));
        for (const OutlinePoint& p : contour) {
            out.points.push_back(p);
            if (first) {
                out.box = {.x0 = p.x, .y0 = p.y, .x1 = p.x, .y1 = p.y};
                first = false;
            } else {
                out.box = {.x0 = std::min(out.box.x0, p.x),
                           .y0 = std::min(out.box.y0, p.y),
                           .x1 = std::max(out.box.x1, p.x),
                           .y1 = std::max(out.box.y1, p.y)};
            }
        }
    }
    points_.clear();
    contours_.clear();
    open_ = false;
    return out;
}

namespace {

OutlineBuilder& builder_of(void* data) { return *static_cast<OutlineBuilder*>(data); }

void draw_move(hb_draw_funcs_t* /*funcs*/, void* data, hb_draw_state_t* /*state*/, float x, float y, void* /*user*/) {
    builder_of(data).move_to(x, y);
}
void draw_line(hb_draw_funcs_t* /*funcs*/, void* data, hb_draw_state_t* /*state*/, float x, float y, void* /*user*/) {
    builder_of(data).line_to(x, y);
}
void draw_quadratic(hb_draw_funcs_t* /*funcs*/, void* data, hb_draw_state_t* /*state*/, float cx, float cy, float x,
                    float y, void* /*user*/) {
    builder_of(data).quadratic_to(cx, cy, x, y);
}
void draw_cubic(hb_draw_funcs_t* /*funcs*/, void* data, hb_draw_state_t* /*state*/, float c1x, float c1y, float c2x,
                float c2y, float x, float y, void* /*user*/) {
    builder_of(data).cubic_to(c1x, c1y, c2x, c2y, x, y);
}
void draw_close(hb_draw_funcs_t* /*funcs*/, void* data, hb_draw_state_t* /*state*/, void* /*user*/) {
    builder_of(data).close();
}

hb_draw_funcs_t* draw_funcs() {
    static hb_draw_funcs_t* const funcs = [] {
        hb_draw_funcs_t* made = hb_draw_funcs_create();
        hb_draw_funcs_set_move_to_func(made, draw_move, nullptr, nullptr);
        hb_draw_funcs_set_line_to_func(made, draw_line, nullptr, nullptr);
        hb_draw_funcs_set_quadratic_to_func(made, draw_quadratic, nullptr, nullptr);
        hb_draw_funcs_set_cubic_to_func(made, draw_cubic, nullptr, nullptr);
        hb_draw_funcs_set_close_path_func(made, draw_close, nullptr, nullptr);
        hb_draw_funcs_make_immutable(made);
        return made;
    }();
    return funcs;
}

} // namespace

GlyphOutline outline(const Instance& instance, std::uint32_t glyph) {
    OutlineBuilder builder;
    (void)hb_font_draw_glyph_or_fail(instance.font(), glyph, draw_funcs(), &builder);
    return builder.finish(glyph);
}

} // namespace atlas::text
