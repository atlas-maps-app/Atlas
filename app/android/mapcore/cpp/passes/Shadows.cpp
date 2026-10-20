// The ground's shadows (passes/Ground.hpp; plan-vector D8 "Ground shadows": in A1 a stencil union composited once at
// shadow_op in th['shadow'], sharp; render/amsterdam.py:742–771). By day with the sun up (and where the region draws
// them), every solid casts its shadow on the ground: a prism the hull of its footprint and its top moved by
// −L.xz / L.y · h, a raised part its top's outline moved, a tree its crown's 16-gon moved by the shadow of 0.7 of its
// height; the renders' radius cut drops a caster whose centroid (a tree's trunk) lies beyond it.
//
// The sun moves them on the GPU, so nothing is made per sun bucket: stage T keeps each prism's footprint hull (hull2,
// sun-free), each raised outline and each crown disc (geometry/Ground.cpp's build_shadows), and the vertex stage moves
// them by the moment's sun. The hull of a footprint and its moved copy is the footprint's hull swept along the offset:
// each hull point comes twice, before and after its turn, moved where the edge on that side faces the offset (its
// outward normal · offset > 0), so the polygon over the doubled points is the swept hull, convex, a fan. Each frame
// decides the radius cut per caster (kept at rest with the other decisions); the drawn shadows' triangles set the
// stencil's shadow bit at the shadows' painter depth (not where a nearer object was drawn), and one triangle over the
// frame composites th['shadow'] at shadow_op where the bit is set, clearing it.
//
// The twin draws the port's own polygons: sun::shadows_of over the tile's casters (K8's hull2 of the footprint and its
// moved top, rounded to millimetres), each kept where Camera.poly keeps it (900 px of margin for a solid's, 40 for a
// tree's), in `<g opacity fill filter>` at the shadows' slot.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"
#include "sun/Hulls.hpp"
#include "sun/Samples.hpp"

#include <algorithm>
#include <cmath>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

/// render's `far`: math.hypot(x - TX, z - TZ) > RAD, the squares deciding where they tell.
[[nodiscard]] bool far(double x, double z, double tx, double tz, double radius) noexcept {
    const double dx = x - tx;
    const double dz = z - tz;
    const double d2 = (dx * dx) + (dz * dz);
    const double r2 = radius * radius;
    if (d2 < r2 * (1 - 1e-9)) return false;
    if (d2 > r2 * (1 + 1e-9)) return true;
    return pm::hypot(dx, dz) > radius;
}

[[nodiscard]] std::uint32_t argb(const style::Rgb8& c) noexcept {
    return 0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
}

} // namespace

void Ground::decide_shadows(const DrawnTile& t, TileDecisions& d) const {
    const GroundFrame& f = frame_;
    const geo::GroundTile& g = *t.ground;
    d.shadow_drawn.clear();
    if (!f.casting || g.no_shadows) return;
    d.shadow_drawn.assign(g.shadows.size(), 0);
    if (f.engine && g.shadow_at[1] >= g.shadow_at[0]) {
        // the tile's casters wholly within the radius or wholly beyond it, by the box of their points
        const std::array<double, 4>& b = g.shadow_at;
        const double nx = pm::max(0.0, pm::max(b[0] - f.target_x, f.target_x - b[1]));
        const double nz = pm::max(0.0, pm::max(b[2] - f.target_z, f.target_z - b[3]));
        const double fx = pm::max(std::abs(b[0] - f.target_x), std::abs(b[1] - f.target_x));
        const double fz = pm::max(std::abs(b[2] - f.target_z), std::abs(b[3] - f.target_z));
        const double r2 = f.radius * f.radius;
        if ((fx * fx) + (fz * fz) < r2 * (1 - 1e-9)) {
            std::ranges::fill(d.shadow_drawn, std::uint8_t{1});
            return;
        }
        if ((nx * nx) + (nz * nz) > r2 * (1 + 1e-9)) return;
    }
    for (std::uint32_t i = 0; i < g.shadows.size(); ++i) {
        const geo::Shadow& sh = g.shadows[i];
        // the radius cut, in engine mode (a solid's centroid, a tree's trunk); every caster counts, in a visible cell
        // or not: its shadow may reach into one
        if (f.engine && far(sh.at.x, sh.at.z, f.target_x, f.target_z, f.radius)) continue;
        d.shadow_drawn[i] = 1;
    }
}

gpu::Result<void> Ground::make_shadow_pipelines(const frame::PassContext& context, Pipelines& p) const {
    // the union: every drawn shadow's triangles set the shadow bit at the shadows' depth (not under a nearer object)
    gpu::GraphicsDesc un = ground_desc(context, p, "ground shadow union", "shadow_vs", "colour_fs");
    translucent(un);
    colour_off(un);
    un.stencil_test = true;
    un.stencil = {.failOp = vk::StencilOp::eKeep,
                  .passOp = vk::StencilOp::eReplace,
                  .depthFailOp = vk::StencilOp::eKeep,
                  .compareOp = vk::CompareOp::eAlways,
                  .compareMask = 0,
                  .writeMask = ground_stencil::shadows,
                  .reference = ground_stencil::shadows};
    ATLAS_GPU_LET(u, frame::make_pipeline(context, un, frame::Overdraw::none));
    p.shadow_union = std::move(u);
    // the composite: th['shadow'] at shadow_op where the bit is set, clearing it
    gpu::GraphicsDesc co = ground_desc(context, p, "ground shadow composite", "screen_vs", "flat_fs");
    translucent(co);
    co.stencil_test = true;
    co.stencil = {.failOp = vk::StencilOp::eKeep,
                  .passOp = vk::StencilOp::eZero,
                  .depthFailOp = vk::StencilOp::eZero,
                  .compareOp = vk::CompareOp::eEqual,
                  .compareMask = ground_stencil::shadows,
                  .writeMask = ground_stencil::shadows,
                  .reference = ground_stencil::shadows};
    ATLAS_GPU_LET(c, frame::make_pipeline(context, co, frame::Overdraw::translucent));
    p.shadow_composite = std::move(c);
    return {};
}

void Ground::record_shadows(const frame::Recording& r) {
    if (!frame_.shadows) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::shadow, 0, 0), 0U, 0U};
    pc.rows = {static_cast<float>(frame_.sun_x), static_cast<float>(frame_.sun_y), static_cast<float>(frame_.sun_z),
               0.0F};
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->shadow_union);
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
        const DrawnTile& t = frame_.tiles[i];
        const geo::Words draw = t.ground->layout.shadow_draw;
        if (draw.count == 0 || !t.pass->ranges[0] || t.ground->no_shadows) continue;
        cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
        r.counters.draw("ground shadows", draw.count / 3);
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->shadow_composite);
    const style::ThemeBlock& th = r.state.theme;
    pc.rows = {0.0F, 0.0F, static_cast<float>(th.shadow_opacity), 0.0F};
    pc.colour = {0U, 0U, geo::Rgba{.r = th.shadow.r, .g = th.shadow.g, .b = th.shadow.b, .a = 255}.packed(), 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground shadows", 1);
}

void Ground::emit_shadows(const frame::FrameState& state, twin::Stream& out) const {
    if (!frame_.shadows || !state.view) return;
    const style::ThemeBlock& th = state.theme;
    const view::Camera& cam = state.view->camera();
    TwinPlacer pr(state, cam);
    const sun::Sun light{.x = frame_.sun_x, .y = frame_.sun_y, .z = frame_.sun_z};
    // the port's polygons, per tile
    struct Cast {
        std::uint32_t tile;
        sun::TileShadows shadows;
    };
    std::vector<Cast> all;
    for (std::uint32_t t = 0; t < frame_.tiles.size(); ++t) {
        const geo::GroundTile& g = *frame_.tiles[t].ground;
        if (g.casters == nullptr || g.no_shadows) continue;
        all.push_back({.tile = t, .shadows = sun::shadows_of(g.casters->solids, g.casters->trees, light, {})});
    }
    // the polygons in the renders' loops' order over every tile: S['buildings'], S['parts'], then S['trees']
    struct Polygon {
        std::uint64_t order;
        std::uint32_t cast;
        std::uint32_t index;
    };
    std::vector<Polygon> polygons;
    for (std::uint32_t c = 0; c < all.size(); ++c) {
        const geo::GroundTile& g = *frame_.tiles[all[c].tile].ground;
        const sun::TileShadows& s = all[c].shadows;
        for (std::uint32_t p = 0; p < s.size(); ++p) {
            const bool tree = s.kind[p] == sun::ShadowKind::tree;
            const std::vector<std::uint64_t>& order = tree ? g.tree_order : g.solid_order;
            const std::uint32_t owner = s.owner[p];
            polygons.push_back(
                {.order = owner < order.size() ? order[owner] : ~std::uint64_t{0}, .cast = c, .index = p});
        }
    }
    std::ranges::stable_sort(polygons, {}, &Polygon::order);
    std::vector<geo::P2> ring;
    std::vector<twin::Point> points;
    std::vector<std::uint32_t> ends;
    std::vector<view::Vec3> cs;
    std::vector<view::Vec2> sp;
    for (const Polygon& q : polygons) {
        const DrawnTile& t = frame_.tiles[all[q.cast].tile];
        const geo::GroundTile& g = *t.ground;
        const sun::TileShadows& s = all[q.cast].shadows;
        const std::uint32_t p = q.index;
        const bool tree = s.kind[p] == sun::ShadowKind::tree;
        // the radius cut by the caster's centroid or trunk
        if (frame_.engine) {
            geo::P2 at;
            if (tree) {
                at = {.x = g.casters->trees.x[s.owner[p]], .z = g.casters->trees.z[s.owner[p]]};
            } else {
                const std::span<const double> xs = g.casters->solids.xs(s.owner[p]);
                const std::span<const double> zs = g.casters->solids.zs(s.owner[p]);
                ring.clear();
                for (std::size_t k = 0; k < xs.size(); ++k) ring.push_back({xs[k], zs[k]});
                at = geo::centroid(ring);
            }
            if (far(at.x, at.z, frame_.target_x, frame_.target_z, frame_.radius)) continue;
        }
        // cam.poly(..., margin): kept where its near-clipped projection reaches within the margin
        ring.clear();
        const std::span<const double> xs = s.xs(p);
        const std::span<const double> zs = s.zs(p);
        for (std::size_t k = 0; k < xs.size(); ++k) ring.push_back({xs[k], zs[k]});
        const ProjectedPoly pp = project_poly(cam, ring, s.y[p], tree ? 40.0 : 900.0, cs, sp);
        if (pp.points < 3 || !pp.inside) continue;
        if (pr.ring(t, ring, s.y[p], points) >= 3) {
            ends.push_back(static_cast<std::uint32_t>(points.size()));
        }
    }
    // `<g opacity="%.2f" fill="..." filter="url(#sblur)">` around one path each (the blur where the moment has one)
    if (ends.empty()) return;
    const std::int32_t blur = th.shadow_blur > 0 ? out.filter("sblur") : twin::none;
    const std::int32_t group = out.group(
        {.node = {.opacity = static_cast<float>(printed(th.shadow_opacity, 2)), .filter = blur}, .parent = out.root()});
    out.at(slot(geo::group::shadow, 0, 0), group);
    twin::Style style;
    style.fill = twin::Paint::colour(argb(th.shadow));
    std::uint32_t start = 0;
    for (const std::uint32_t e : ends) {
        out.path(style, std::span<const twin::Point>(points).subspan(start, e - start), true);
        start = e;
    }
}

} // namespace atlas::passes
