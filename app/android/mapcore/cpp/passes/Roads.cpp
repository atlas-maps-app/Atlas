// The ground's fills in the twin (passes/Ground.hpp; plan-vector D16's L1): the plate, the land cover by kind and its
// canopy, the areas, the roads and draw_over's ballast, asphalt and marine works, each as the port's SVG element at its
// painter slot (render/amsterdam.py:615 render's gp and drawRoads, regions.py's draw_under and draw_over), so L1
// matches them element by element. The roads are the fills' largest share (their ribbons, geometry/Ribbons.cpp, their
// colours by surface, surface:colour, a cycleway's red and the night's fifth, geometry/Ground.cpp); on the GPU they
// draw with the other fills above the water (Ground.cpp).
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"

#include <algorithm>

namespace atlas::passes {

namespace geo = atlas::geometry;

namespace {

[[nodiscard]] std::uint32_t argb(const style::Rgb8& c) noexcept {
    return 0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
}

[[nodiscard]] std::uint32_t argb(const geo::Rgba& c) noexcept {
    return 0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
}

} // namespace

std::uint32_t paint_argb(const geo::Paint& paint, bool night, const style::ThemeBlock& th) noexcept {
    const geo::Rgba c = night ? paint.night : paint.day;
    if (c.a != 0) return argb(c);
    switch (static_cast<geo::ThemeColour>(c.r)) {
    case geo::ThemeColour::ground: return argb(th.ground);
    case geo::ThemeColour::road: {
        const geo::Rgba r{.r = th.road.r, .g = th.road.g, .b = th.road.b, .a = 255};
        return argb(night ? geo::darken(r, 0.2) : r);
    }
    case geo::ThemeColour::water: return argb(th.water);
    }
    return 0xFF00'0000U;
}

void Ground::emit_fills(const frame::FrameState& state, twin::Stream& out, std::int32_t mat, std::int32_t above) const {
    if (!state.view) return;
    TwinPlacer pr(state, state.view->camera());
    const style::ThemeBlock& th = state.theme;
    const bool night = frame_.night;
    std::vector<twin::Point> points;
    // the plate
    if (frame_.plate) {
        const double gr = frame_.ground_r;
        const std::array<geo::P2, 4> rect{
            {{.x = -gr, .z = -gr}, {.x = gr, .z = -gr}, {.x = gr, .z = gr}, {.x = -gr, .z = gr}}};
        points.clear();
        if (pr.frame_ring(rect, 0.0, points) >= 3) {
            out.at(slot(geo::group::plate, 0, 0), out.root());
            twin::Style s;
            s.fill = twin::Paint::colour(argb(th.ground));
            out.path(s, points, true);
        }
    }
    // the land cover: per kind (by rank) one path of its drawn rings in S['land']'s order, in draw_under's group
    {
        struct Ring {
            std::uint32_t order;
            std::uint32_t tile;
            std::uint32_t fill;
        };
        std::vector<Ring> rings;
        for (std::uint32_t t = 0; t < frame_.tiles.size(); ++t) {
            const geo::GroundTile& g = *frame_.tiles[t].ground;
            for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
                if (g.fills[i].kind != geo::FillKind::land) continue;
                if ((frame_.drawn[frame_.tiles[t].decisions + i] & drawn_bit::street) == 0) continue;
                rings.push_back({.order = g.fills[i].order, .tile = t, .fill = i});
            }
        }
        // stable: a feature's pieces in several tiles share its order and keep the tiles' order, on every libc++
        std::ranges::stable_sort(rings, {}, &Ring::order);
        // draw_under's `<g filter="url(#mat)">`, whatever the moment (`mat` none where render defines none)
        const std::int32_t under =
            rings.empty() ? out.root() : out.group({.node = {.filter = mat}, .parent = out.root()});
        for (std::uint16_t k = 0; k < geo::group::land_kinds; ++k) {
            if (frame_.kind_rank[k] < 0) continue;
            points.clear();
            std::vector<std::uint32_t> ends;
            geo::Paint paint;
            for (const Ring& r : rings) {
                const DrawnTile& t = frame_.tiles[r.tile];
                const geo::Fill& fill = t.ground->fills[r.fill];
                if (fill.group != geo::group::land + k) continue;
                paint = fill.paint;
                if (pr.ring(t, std::span(t.ground->points).subspan(fill.first, fill.count), fill.y, points) >= 3) {
                    ends.push_back(static_cast<std::uint32_t>(points.size()));
                }
            }
            std::vector<twin::Subpath> subpaths;
            std::uint32_t start = 0;
            for (const std::uint32_t e : ends) {
                subpaths.push_back({.points = std::span<const twin::Point>(points).subspan(start, e - start)});
                start = e;
            }
            out.at(slot(static_cast<std::uint16_t>(geo::group::land + k), 0, 0), under);
            twin::Style s;
            s.fill = twin::Paint::colour(paint_argb(paint, night, th));
            out.path(s, subpaths);
        }
    }
    // the canopy: one path of the drawn forest, wood and scrub rings, black at .55 with its filter (K17's)
    if (frame_.canopy) {
        points.clear();
        std::vector<std::uint32_t> ends;
        for (const DrawnTile& t : frame_.tiles) {
            const geo::GroundTile& g = *t.ground;
            for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
                const geo::Fill& fill = g.fills[i];
                if (fill.kind != geo::FillKind::canopy || (frame_.drawn[t.decisions + i] & drawn_bit::street) == 0) {
                    continue;
                }
                if (pr.ring(t, std::span(g.points).subspan(fill.first, fill.count), fill.y, points) >= 3) {
                    ends.push_back(static_cast<std::uint32_t>(points.size()));
                }
            }
        }
        std::vector<twin::Subpath> subpaths;
        std::uint32_t start = 0;
        for (const std::uint32_t e : ends) {
            subpaths.push_back({.points = std::span<const twin::Point>(points).subspan(start, e - start)});
            start = e;
        }
        out.at(slot(geo::group::canopy, 0, 0), out.root());
        twin::Style s;
        s.fill = twin::Paint::colour(0xFF00'0000U);
        out.path(s, subpaths, {.opacity = 0.55F, .filter = out.filter("canopy")});
    }
    // the areas, the roads and draw_over's fills, in render's `mat` group
    for (const DrawnTile& t : frame_.tiles) {
        const geo::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
            const geo::Fill& fill = g.fills[i];
            if (fill.kind == geo::FillKind::land || fill.kind == geo::FillKind::canopy ||
                fill.kind == geo::FillKind::water || !fill.drawn) {
                continue;
            }
            if ((frame_.drawn[t.decisions + i] & drawn_bit::street) == 0) continue;
            points.clear();
            if (pr.ring(t, std::span(g.points).subspan(fill.first, fill.count), fill.y, points) < 3) {
                continue;
            }
            out.at(slot(fill.group, fill_order(t, i), fill.sub), above);
            twin::Style s;
            s.fill = twin::Paint::colour(paint_argb(fill.paint, night, th));
            out.path(s, points, true);
        }
    }
}

} // namespace atlas::passes
