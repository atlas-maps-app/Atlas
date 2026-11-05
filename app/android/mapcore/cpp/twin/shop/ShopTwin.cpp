#include "twin/shop/ShopTwin.hpp"

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "style/Theme.hpp"
#include "text/GroundText.hpp"

#include <array>
#include <cmath>
#include <vector>

namespace atlas::twin {

namespace {

[[nodiscard]] Paint colour(std::uint32_t rgb) noexcept { return Paint::colour(0xFF00'0000U | rgb); }

/// SVG's fill where the port's path names none: black (a line paints nothing with it).
constexpr Paint black = Paint::colour(0xFF00'0000U);

/// A gradient stop's colour with its stop-opacity folded into its alpha, as the SVG parser keeps it (Java's
/// Math.round(255 · opacity) of the float).
[[nodiscard]] std::uint32_t folded(std::uint32_t rgb, float opacity) noexcept {
    const auto alpha = static_cast<std::uint32_t>(std::floor((255.0F * opacity) + 0.5F));
    return (alpha << 24U) | rgb;
}

/// `rotate(degrees cx cy)` as the SVG parser reads it (SvgValues.parseTransform): in double, then as floats.
[[nodiscard]] Affine rotate_about(double degrees, double cx, double cy) noexcept {
    using M = std::array<double, 6>;
    const auto multiply = [](const M& a, const M& b) {
        return M{(a[0] * b[0]) + (a[2] * b[1]),        (a[1] * b[0]) + (a[3] * b[1]),
                 (a[0] * b[2]) + (a[2] * b[3]),        (a[1] * b[2]) + (a[3] * b[3]),
                 (a[0] * b[4]) + (a[2] * b[5]) + a[4], (a[1] * b[4]) + (a[3] * b[5]) + a[5]};
    };
    const double r = degrees * pymath::pi / 180;
    const double c = pymath::cos(r);
    const double s = pymath::sin(r);
    const M m =
        multiply(multiply(M{1.0, 0.0, 0.0, 1.0, cx, cy}, M{c, s, -s, c, 0.0, 0.0}), M{1.0, 0.0, 0.0, 1.0, -cx, -cy});
    return {static_cast<float>(m[0]), static_cast<float>(m[1]), static_cast<float>(m[2]),
            static_cast<float>(m[3]), static_cast<float>(m[4]), static_cast<float>(m[5])};
}

} // namespace

void emit_shops(const frame::FrameState& state, const passes::ShopFrame& shops, const tiles::ShopfrontsStyle& style,
                Stream& out) {
    if (shops.shops.empty() && shops.glows.empty()) return;
    const bool night = state.theme.has(style::theme_flag::night);
    const bool rest = state.rest;
    const auto at = [rest](double v) { return rest ? text::printed_float(v, 1) : static_cast<float>(v); };
    const auto point = [&at](view::Vec2 p) { return Point{.x = at(p.x), .y = at(p.y)}; };
    out.layer(Layer::objects); // the port's objects image (`o`)
    if (!shops.shops.empty()) {
        // the objects group: the port's `<g stroke-linejoin="round" filter="url(#mat)">` (shopfronts draw with weather
        // alone)
        const std::int32_t objects = out.group({.node = {.filter = out.filter("mat")}, .parent = out.root()});
        // the glass's gradients by day, as the walls' (amsterdam.py's defs: `glass` by the sky's cover, then
        // `glassLit`)
        Paint glass_plain;
        Paint glass_lit;
        if (!night) {
            const shaders::FrameWindows& w = state.block.windows;
            const bool overcast = (state.block.theme.flags.x & shaders::THEME_OVERCAST) != 0U;
            const std::array<float, 2> offsets{0.0F, 1.0F};
            const std::array<std::uint32_t, 2> glass{0xFF00'0000U | (overcast ? w.glass.z : w.glass.x),
                                                     0xFF00'0000U | (overcast ? w.glass.w : w.glass.y)};
            const std::array<std::uint32_t, 2> lit{0xFF00'0000U | w.lit.x, 0xFF00'0000U | w.lit.y};
            glass_plain = Paint::gradient(out.gradient(
                {.id = "glass", .coords = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, .offsets = offsets, .colours = glass}));
            glass_lit = Paint::gradient(out.gradient(
                {.id = "glassLit", .coords = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, .offsets = offsets, .colours = lit}));
        }
        std::vector<Point> pts;
        const auto face = [&](const passes::ShopPoly& f, std::uint32_t slot, std::int32_t group, std::int32_t item,
                              const Style& s) {
            if (f.count == 0) return;
            pts.clear();
            for (std::uint32_t i = 0; i < f.count; ++i) pts.push_back(point(f.points.at(i)));
            out.at(slot, group, item);
            out.path(s, std::span<const Point>(pts), true);
        };
        const auto line = [&](const passes::ShopLine& l, std::uint32_t slot, std::int32_t item, std::uint32_t rgb) {
            if (!l.drawn) return;
            const std::array<Point, 2> ends{point(l.a), point(l.b)};
            out.at(slot, objects, item);
            out.path({.fill = black,
                      .stroke = colour(rgb),
                      .stroke_width = static_cast<float>(l.width),
                      .cap = Cap::butt,
                      .join = Join::round},
                     ends, false);
        };
        const auto stroked = [](std::uint32_t fill, std::uint32_t stroke, float width) {
            return Style{.fill = colour(fill), .stroke = colour(stroke), .stroke_width = width, .join = Join::round};
        };
        const auto text_of = [&](const passes::ShopText& t, std::uint32_t slot, std::int32_t item, float spacing) {
            if (!t.drawn) return;
            const Font font{.family = text::geist_family,
                            .size = text::printed_float(t.size, 1),
                            .weight = 800,
                            .italic = false,
                            .letter_spacing = spacing,
                            .anchor = TextAnchor::middle};
            Node node;
            if (t.turned) {
                node.transformed = true;
                node.transform = rotate_about(90.0, static_cast<double>(at(t.cx)), static_cast<double>(at(t.cy)));
            }
            out.at(slot, objects, item);
            out.text({.fill = colour(t.fill)}, t.text, at(t.x), at(t.y), font, node);
        };
        const auto board_of = [&](std::int64_t id, const passes::BoardShape& b) {
            const std::uint32_t slot = shaders::rank_slot(b.rank);
            const std::int32_t item = out.item({.owner = Owner::node, .id = id, .kind = "shopfront"});
            face(b.frame, slot, objects, item, {.fill = colour(b.frame_colour)});
            face(b.glass, slot + 1, objects, item,
                 {.fill = night ? colour(b.glass_colour) : (b.glass_lit ? glass_lit : glass_plain)});
            for (const passes::ShopLine& m : b.mullions) line(m, slot + 2, item, style.mullion);
            face(b.awning, slot + 3, objects, item, stroked(b.awning_colour, b.awning_outline, style.awning_width));
            if (!b.stripes.empty()) {
                // '<path d="%s" fill="%s" opacity=".85"/>': the stripes' closed subpaths in one path
                std::vector<std::vector<Point>> rings;
                std::vector<Subpath> subpaths;
                rings.reserve(b.stripes.size());
                subpaths.reserve(b.stripes.size());
                for (const passes::ShopPoly& p : b.stripes) {
                    std::vector<Point>& ring = rings.emplace_back();
                    for (std::uint32_t i = 0; i < p.count; ++i) ring.push_back(point(p.points.at(i)));
                }
                for (const std::vector<Point>& ring : rings) subpaths.push_back({.points = ring, .closed = true});
                out.at(slot + 5, objects, item);
                out.path({.fill = colour(b.stripe_colour)}, subpaths, {.opacity = style.stripes_opacity});
            }
            face(b.sign, slot + 6, objects, item, stroked(b.sign_colour, style.sign_outline, style.sign_width));
            text_of(b.label, slot + 8, item, text::printed_float(b.label.spacing, 2));
            if (b.spill.count > 0) {
                // '<g opacity=".55">%s</g>' of its face
                const std::int32_t spill = out.group({.node = {.opacity = style.spill_opacity}, .parent = objects});
                face(b.spill, slot + 9, spill, item, {.fill = colour(style.spill)});
            }
        };
        const auto blade_of = [&](std::int64_t id, const passes::BladeShape& bl) {
            const std::uint32_t slot = shaders::rank_slot(bl.rank);
            const std::int32_t item = out.item({.owner = Owner::node, .id = id, .kind = "blade"});
            line(bl.bracket, slot, item, style.sign_outline);
            face(bl.blade, slot + 1, objects, item, stroked(bl.colour, style.sign_outline, style.blade_width));
            text_of(bl.text, slot + 3, item, 0.0F);
        };
        for (const passes::ShopShape& s : shops.shops) {
            if (s.board) board_of(s.id, *s.board);
            if (s.blade) blade_of(s.id, *s.blade);
            for (const passes::TableShape& t : s.tables) {
                const std::uint32_t slot = shaders::rank_slot(t.rank);
                const std::int32_t item = out.item({.owner = Owner::node, .id = s.id, .kind = "terrace"});
                line(t.leg, slot, item, t.wood);
                for (std::size_t c = 0; c < t.chairs.size(); ++c) {
                    const passes::ChairShape& ch = t.chairs.at(c);
                    const auto first = static_cast<std::uint32_t>(slot + 1 + (3 * c));
                    line(ch.leg, first, item, ch.leg_colour);
                    face(ch.seat, first + 1, objects, item, {.fill = colour(ch.seat_colour)});
                    face(ch.back, first + 2, objects, item, {.fill = colour(ch.back_colour)});
                }
                face(t.top, slot + 7, objects, item, stroked(t.top_colour, style.top_outline, style.top_width));
                if (!t.parasol) continue;
                line(t.pole, slot + 9, item, style.pole);
                for (std::size_t i = 0; i < t.panels.size(); ++i) {
                    face(t.panels.at(i), slot + 10 + static_cast<std::uint32_t>(i), objects, item,
                         {.fill = colour(t.panel_colours.at(i))});
                }
            }
        }
    }
    if (shops.glows.empty()) return;
    // the night's glows: '<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#open)"/>', after every object and before wfx,
    // the haze and fin (Stream::after's)
    const std::array<float, 3> offsets{0.0F, style.open_middle_offset, 1.0F};
    const std::array<std::uint32_t, 3> colours{folded(style.open_inner, style.open_inner_opacity),
                                               folded(style.open_middle, style.open_middle_opacity),
                                               folded(style.open_outer, style.open_outer_opacity)};
    const std::int32_t open = out.gradient({.id = "open",
                                            .radial = true,
                                            .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                                            .units = Units::bounding_box,
                                            .offsets = offsets,
                                            .colours = colours});
    out.at(Stream::after - 1, out.root());
    for (const passes::GlowShape& g : shops.glows)
        out.circle({.fill = Paint::gradient(open)}, at(g.x), at(g.y), at(g.r));
}

} // namespace atlas::twin
