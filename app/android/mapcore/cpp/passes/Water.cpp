// The ground's water (passes/Ground.hpp; plan-vector D10 "Water: surface, gradient, tint"; render/amsterdam.py:457
// water_layer, R/Amsterdam.kt waterLayer). With weather, the renders draw the water inside two clips: w0, every water
// ring at the street (y 0) and every island turned the other way, and wclip, the same rings at their water level,
// itself clipped by w0, so the surface shows below the quay on the far bank and is cut by the quay's edge on the near
// one; inside, wgrad's rect (water_top to water_bot from the horizon, or the frame's top, to its bottom), the
// reflections (K16), the water colour's tint at .12 (.04 at night), the glints (K17), the sun's glitter and the
// drizzle's rings (K16). Without weather, every water ring is a path of th['water'] at the street (islands are not
// drawn).
//
// Each ring is decided whole, as the port decides it: Camera.poly at 900 px of margin and an area over 0.2, at the
// street and at its level, over the ring's pieces in the drawn tiles (the shoelace sums of the pieces add up to the
// ring's, their boxes to its box). Without a ring at its level the renders draw no water at all.
//
// On the GPU (the opaque ground, after what lies above the water): the w0 rings' fans counted in the stencil's three
// low bits (front faces up, back faces down, wrapping), where that count is not zero the w0 bit set and the count
// cleared; the wclip rings' fans counted where the w0 bit is set; then the surface where the count is not zero, at the
// water's painter depth (opaque: what lies above it was drawn first). Its tint and the end of its clip are translucent
// phases at their orders, so K16's and K17's water elements draw inside the clip between them.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::passes {

namespace geo = atlas::geometry;

namespace {

/// A ring's pieces together, at one height: the clipped points, the shoelace sum, the box.
struct Whole {
    std::size_t points = 0;
    double sum = 0;
    double min_x = std::numeric_limits<double>::infinity();
    double max_x = -std::numeric_limits<double>::infinity();
    double min_y = std::numeric_limits<double>::infinity();
    double max_y = -std::numeric_limits<double>::infinity();
    bool maybe = false; ///< moving: a piece's box may reach the frame

    void add(const PieceView& p) {
        maybe = maybe || p.maybe;
        if (p.points < 3) return;
        points += p.points;
        sum += p.sum;
        min_x = pymath::min(min_x, p.min_x);
        max_x = pymath::max(max_x, p.max_x);
        min_y = pymath::min(min_y, p.min_y);
        max_y = pymath::max(max_y, p.max_y);
    }

    /// Camera.poly's margin test and area(sp) > 0.2 on the whole ring.
    [[nodiscard]] bool drawn(const view::Camera& cam, double margin) const {
        if (points < 3) return false;
        if (max_x < -margin || min_x > cam.width() + margin || max_y < -margin || min_y > cam.height() + margin) {
            return false;
        }
        return std::abs(sum) / 2 > 0.2;
    }
};

/// One piece of the drawn tiles: its ring's key, its tile and its view.
struct Piece {
    std::uint64_t ring = 0; ///< (hole << 32) | index
    std::uint32_t tile = 0;
    const WaterPiece* piece = nullptr;
};

[[nodiscard]] geo::Rgba rgba(const style::Rgb8& c) noexcept { return {.r = c.r, .g = c.g, .b = c.b, .a = 255}; }

} // namespace

WaterPiece Ground::view_water(const view::Camera& cam, const geo::GroundTile& g, std::uint32_t i) {
    const geo::Fill& fill = g.fills[i];
    WaterPiece w{.fill = i};
    const std::span<const geo::P2> ring = std::span(g.points).subspan(fill.first, fill.count);
    for (int h = 0; h < (frame_.weather ? 2 : 1); ++h) {
        const double y = h == 0 ? 0.0 : fill.level;
        PieceView& v = h == 0 ? w.street : w.level;
        if (box_test(cam, fill, y, fill.margin) == Box::outside) continue;
        if (!frame_.exact) {
            v.maybe = true;
            continue;
        }
        const ProjectedPoly p = project_poly(cam, ring, y, fill.margin, cam_, sp_);
        if (p.points < 3) continue;
        v = {.points = static_cast<std::uint32_t>(p.points),
             .sum = p.sum,
             .min_x = p.min_x,
             .max_x = p.max_x,
             .min_y = p.min_y,
             .max_y = p.max_y};
    }
    return w;
}

void Ground::decide_water(const view::Camera& cam) {
    GroundFrame& f = frame_;
    f.water = false;
    const bool wx = f.weather;
    std::vector<Piece> pieces;
    for (std::uint32_t t = 0; t < f.tiles.size(); ++t) {
        const geo::GroundTile& g = *f.tiles[t].ground;
        for (const WaterPiece& w : f.tiles[t].decided->water) {
            const geo::Fill& fill = g.fills[w.fill];
            pieces.push_back({.ring = (std::uint64_t{fill.hole} << 32U) | fill.ring, .tile = t, .piece = &w});
        }
    }
    // stable: a ring's pieces keep the tiles' order, so its sum of areas is the same on every libc++
    std::ranges::stable_sort(pieces, {}, &Piece::ring);
    for (std::size_t a = 0; a < pieces.size();) {
        std::size_t b = a;
        while (b < pieces.size() && pieces[b].ring == pieces[a].ring) ++b;
        // the ring at the street (w0, or the plain fill) and at its level (wclip)
        Whole street;
        Whole level;
        for (std::size_t k = a; k < b; ++k) {
            street.add(pieces[k].piece->street);
            level.add(pieces[k].piece->level);
        }
        const bool at_street = f.exact ? street.drawn(cam, 900) : street.maybe;
        const bool at_level = wx && (f.exact ? level.drawn(cam, 900) : level.maybe);
        for (std::size_t k = a; k < b; ++k) {
            std::uint8_t& d = f.drawn[f.tiles[pieces[k].tile].decisions + pieces[k].piece->fill];
            d = static_cast<std::uint8_t>((at_street ? drawn_bit::street : 0U) | (at_level ? drawn_bit::level : 0U));
        }
        // with weather the water is drawn when a ring is drawn at its level (`if not r1: return ''`)
        f.water = f.water || (wx ? at_level : at_street);
        a = b;
    }
}

gpu::Result<void> Ground::make_water_pipelines(const frame::PassContext& context, Pipelines& p) const {
    vk::StencilOpState up{.failOp = vk::StencilOp::eKeep,
                          .passOp = vk::StencilOp::eIncrementAndWrap,
                          .depthFailOp = vk::StencilOp::eIncrementAndWrap,
                          .compareOp = vk::CompareOp::eAlways,
                          .compareMask = 0,
                          .writeMask = ground_stencil::water_count,
                          .reference = 0};
    vk::StencilOpState down = up;
    down.passOp = vk::StencilOp::eDecrementAndWrap;
    down.depthFailOp = vk::StencilOp::eDecrementAndWrap;
    // w0 (and the plain water): every drawn ring's fan, counted
    gpu::GraphicsDesc count = ground_desc(context, p, "ground water count", "water_vs", "colour_fs");
    colour_off(count);
    count.stencil_test = true;
    count.stencil = up;
    count.stencil_back = down;
    ATLAS_GPU_LET(c0, frame::make_pipeline(context, count, frame::Overdraw::none));
    p.water_count = std::move(c0);
    // where the count is not zero: the w0 bit set, the count cleared
    gpu::GraphicsDesc convert = ground_desc(context, p, "ground water w0", "screen_vs", "flat_fs");
    colour_off(convert);
    convert.stencil_test = true;
    convert.stencil = {.failOp = vk::StencilOp::eKeep,
                       .passOp = vk::StencilOp::eReplace,
                       .depthFailOp = vk::StencilOp::eReplace,
                       .compareOp = vk::CompareOp::eNotEqual,
                       .compareMask = ground_stencil::water_count,
                       .writeMask = ground_stencil::water_count | ground_stencil::water_w0,
                       .reference = ground_stencil::water_w0};
    ATLAS_GPU_LET(cv, frame::make_pipeline(context, convert, frame::Overdraw::none));
    p.water_convert = std::move(cv);
    // wclip: every drawn ring's fan at its level, counted where w0 is set
    gpu::GraphicsDesc level = count;
    level.label.name = "ground water wclip";
    up.compareOp = vk::CompareOp::eEqual;
    up.compareMask = ground_stencil::water_w0;
    up.reference = ground_stencil::water_w0;
    down.compareOp = up.compareOp;
    down.compareMask = up.compareMask;
    down.reference = up.reference;
    level.stencil = up;
    level.stencil_back = down;
    ATLAS_GPU_LET(c1, frame::make_pipeline(context, level, frame::Overdraw::none));
    p.water_count_level = std::move(c1);
    // the surface where the count is not zero: wgrad (with weather), at the water's depth, opaque
    const vk::StencilOpState inside{.failOp = vk::StencilOp::eKeep,
                                    .passOp = vk::StencilOp::eKeep,
                                    .depthFailOp = vk::StencilOp::eKeep,
                                    .compareOp = vk::CompareOp::eNotEqual,
                                    .compareMask = ground_stencil::water_count,
                                    .writeMask = 0,
                                    .reference = 0};
    gpu::GraphicsDesc surface = ground_desc(context, p, "ground water surface", "screen_vs", "gradient_fs");
    opaque_depth(surface);
    surface.stencil_test = true;
    surface.stencil = inside;
    ATLAS_GPU_LET(s, frame::make_pipeline(context, surface, frame::Overdraw::opaque));
    p.water_surface = std::move(s);
    // without weather: th['water'] where the count is not zero, the count cleared as it goes
    gpu::GraphicsDesc flat = ground_desc(context, p, "ground water plain", "screen_vs", "flat_fs");
    opaque_depth(flat);
    flat.stencil_test = true;
    flat.stencil = {.failOp = vk::StencilOp::eKeep,
                    .passOp = vk::StencilOp::eZero,
                    .depthFailOp = vk::StencilOp::eZero,
                    .compareOp = vk::CompareOp::eNotEqual,
                    .compareMask = ground_stencil::water_count,
                    .writeMask = ground_stencil::water_count,
                    .reference = 0};
    ATLAS_GPU_LET(fl, frame::make_pipeline(context, flat, frame::Overdraw::opaque));
    p.water_flat = std::move(fl);
    // the tint inside the clip, translucent
    gpu::GraphicsDesc tint = ground_desc(context, p, "ground water tint", "screen_vs", "flat_fs");
    translucent(tint);
    tint.stencil_test = true;
    tint.stencil = inside;
    ATLAS_GPU_LET(ti, frame::make_pipeline(context, tint, frame::Overdraw::translucent));
    p.water_tint = std::move(ti);
    // the reflections inside the clip (Reflections.cpp): in the renders' order, each written at its slot's depth
    gpu::GraphicsDesc mirrored = ground_desc(context, p, "ground water reflections", "reflection_vs", "reflection_fs");
    translucent(mirrored);
    mirrored.depth_write = true;
    mirrored.stencil_test = true;
    mirrored.stencil = inside;
    ATLAS_GPU_LET(rf, frame::make_pipeline(context, mirrored, frame::Overdraw::translucent));
    p.reflection = std::move(rf);
    // the rippled reflections inside the clip (Ripple.cpp): the filter's result at the group's opacity, at its slot
    gpu::GraphicsDesc rippled = ground_desc(context, p, "ground water ripple", "screen_vs", "ripple_fs");
    translucent(rippled);
    rippled.stencil_test = true;
    rippled.stencil = inside;
    ATLAS_GPU_LET(rc, frame::make_pipeline(context, rippled, frame::Overdraw::translucent));
    p.ripple_composite = std::move(rc);
    // the sun's glitter (Glitter.cpp): screened over the water, each sample once by the first glint to reach it
    // (inside the clip, its w0 bit still set: then cleared), per sample where the device can
    gpu::GraphicsDesc glitter = ground_desc(context, p, "ground water glitter", "glitter_vs", "glitter_fs");
    translucent(glitter);
    glitter.blends[0] = gpu::Blend::screen();
    glitter.stencil_test = true;
    glitter.stencil = {.failOp = vk::StencilOp::eKeep,
                       .passOp = vk::StencilOp::eZero,
                       .depthFailOp = vk::StencilOp::eKeep,
                       .compareOp = vk::CompareOp::eLess,
                       .compareMask = ground_stencil::water_count | ground_stencil::water_w0,
                       .writeMask = ground_stencil::water_w0,
                       .reference = ground_stencil::water_w0};
    glitter.sample_shading = context.device.enabled().sample_rate_shading;
    p.glitter_per_sample = glitter.sample_shading;
    ATLAS_GPU_LET(gl, frame::make_pipeline(context, glitter, frame::Overdraw::translucent));
    p.glitter = std::move(gl);
    // the drizzle's rings (Rings.cpp): their pattern over the frame inside the clip, at the rings' slot
    gpu::GraphicsDesc rings = ground_desc(context, p, "ground water rings", "screen_vs", "rings_fs");
    translucent(rings);
    rings.stencil_test = true;
    rings.stencil = inside;
    ATLAS_GPU_LET(rg, frame::make_pipeline(context, rings, frame::Overdraw::translucent));
    p.rings = std::move(rg);
    // the clip's end: its bits cleared over the frame
    gpu::GraphicsDesc end = ground_desc(context, p, "ground water end", "screen_vs", "flat_fs");
    colour_off(end);
    end.stencil_test = true;
    end.stencil = {.failOp = vk::StencilOp::eZero,
                   .passOp = vk::StencilOp::eZero,
                   .depthFailOp = vk::StencilOp::eZero,
                   .compareOp = vk::CompareOp::eAlways,
                   .compareMask = 0,
                   .writeMask = ground_stencil::water_count | ground_stencil::water_w0,
                   .reference = 0};
    ATLAS_GPU_LET(e, frame::make_pipeline(context, end, frame::Overdraw::none));
    p.water_end = std::move(e);
    return {};
}

void Ground::record_water_opaque(const frame::Recording& r) {
    if (!frame_.water) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Pipelines& p = *pipelines_;
    const Words& w = *words_;
    const std::uint32_t surface = slot(geo::group::water, 0, 0);
    auto fans = [&](std::uint32_t flags) {
        push(r, flags, surface);
        for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
            const DrawnTile& t = frame_.tiles[i];
            const geo::Words draw = t.ground->layout.fans;
            if (draw.count == 0 || !t.pass->ranges[0]) continue;
            cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
            r.counters.draw("ground water", draw.count / 3);
        }
    };
    auto screen = [&](const vk::raii::Pipeline& pipeline, layouts::ground_ground::PushConstants pc) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline);
        pc.at = {w.tiles, w.slots, w.theme, w.runs};
        cmd.pushConstants<layouts::ground_ground::PushConstants>(*p.layout, frame::push_stages, 0, pc);
        cmd.draw(3, 1, 0, 0);
        r.counters.draw("ground water", 1);
    };
    const style::ThemeBlock* th = &r.state.theme;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *p.water_count);
    fans(0);
    if (!frame_.weather) {
        // every drawn ring in th['water'], at the street
        layouts::ground_ground::PushConstants pc{};
        pc.mode = {0U, surface, 0U, 0U};
        pc.rows = {0.0F, 0.0F, 1.0F, 0.0F};
        pc.colour = {0U, 0U, rgba(th->water).packed(), 0U};
        screen(p.water_flat, pc);
        return;
    }
    layouts::ground_ground::PushConstants convert{};
    convert.mode = {0U, surface, 0U, 0U};
    screen(p.water_convert, convert);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *p.water_count_level);
    fans(ground_flag::water_level);
    // wgrad: water_top at max(0, hy) to water_bot at the frame's bottom
    layouts::ground_ground::PushConstants grad{};
    grad.mode = {0U, surface, 0U, 0U};
    grad.rows = {static_cast<float>(frame_.wgrad_y1), static_cast<float>(frame_.wgrad_y2), 1.0F, 0.0F};
    grad.colour = {rgba(th->water_top).packed(), rgba(th->water_bottom).packed(), 0U, 0U};
    screen(p.water_surface, grad);
}

void Ground::record_water_tint(const frame::Recording& r) {
    if (!frame_.water || !frame_.weather) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->water_tint);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::water_top, 0, geo::water_sub::tint), 0U, 0U};
    pc.rows = {0.0F, 0.0F, frame_.night ? 0.04F : 0.12F, 0.0F};
    pc.colour = {0U, 0U, rgba(r.state.theme.water).packed(), 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground water", 1);
}

void Ground::record_water_end(const frame::Recording& r) {
    if (!frame_.water || !frame_.weather) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->water_end);
    push(r, 0, 0);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground water", 1);
}

void Ground::emit_water(const frame::FrameState& state, twin::Stream& out) const {
    if (!frame_.water || !state.view) return;
    TwinPlacer placer(state, state.view->camera());
    const style::ThemeBlock& th = state.theme;
    const auto w = static_cast<float>(state.view->camera().width());
    const auto h = static_cast<float>(state.view->camera().height());
    // every drawn ring's pieces at a height, each piece a subpath turned as `wound` turns it, in the rings' order (the
    // water's, then the islands', each by its place in its list); `rings` the subpaths' first and count per ring
    struct Ring {
        std::uint64_t key;
        std::uint32_t first;
        std::uint32_t count;
    };
    auto pieces = [&](bool level, std::vector<twin::Point>& points, std::vector<twin::Subpath>& subpaths,
                      std::vector<Ring>& rings) {
        struct Piece {
            std::uint64_t key;
            std::uint32_t start;
            std::uint32_t end;
        };
        std::vector<Piece> found;
        for (const DrawnTile& t : frame_.tiles) {
            const geo::GroundTile& g = *t.ground;
            for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
                const geo::Fill& fill = g.fills[i];
                if (fill.kind != geo::FillKind::water) continue;
                const std::uint8_t d = frame_.drawn[t.decisions + i];
                if ((d & (level ? drawn_bit::level : drawn_bit::street)) == 0) continue;
                const std::size_t at = points.size();
                const std::size_t n = placer.ring(t, std::span(g.points).subspan(fill.first, fill.count),
                                                  level ? fill.level : 0.0, points);
                if (n < 3) continue;
                // wound: a water area turning (on the screen) positively, an island the other way
                double a2 = 0;
                for (std::size_t k = 0; k < n; ++k) {
                    const twin::Point& p = points[at + k];
                    const twin::Point& q = points[at + ((k + 1) % n)];
                    a2 += (static_cast<double>(p.x) * static_cast<double>(q.y)) -
                          (static_cast<double>(q.x) * static_cast<double>(p.y));
                }
                if ((a2 > 0) == fill.hole) std::reverse(points.begin() + static_cast<std::ptrdiff_t>(at), points.end());
                found.push_back({.key = (std::uint64_t{fill.hole} << 32U) | fill.ring,
                                 .start = static_cast<std::uint32_t>(at),
                                 .end = static_cast<std::uint32_t>(points.size())});
            }
        }
        std::ranges::stable_sort(found, {}, &Piece::key);
        for (const Piece& q : found) {
            if (rings.empty() || rings.back().key != q.key) {
                rings.push_back({.key = q.key, .first = static_cast<std::uint32_t>(subpaths.size()), .count = 0});
            }
            ++rings.back().count;
            subpaths.push_back(
                {.points = std::span<const twin::Point>(points).subspan(q.start, q.end - q.start), .closed = true});
        }
    };
    auto argb = [](const style::Rgb8& c) {
        return 0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
    };
    const std::uint32_t surface = slot(geo::group::water, 0, 0);
    if (!frame_.weather) {
        // without weather: every drawn ring a path of th['water'], its pieces in one path (where a tile's edge cuts
        // it, the pieces' union is the ring: their nonzero fill draws its pixels, without a seam along the cut)
        std::vector<twin::Point> points;
        std::vector<twin::Subpath> subpaths;
        std::vector<Ring> rings;
        pieces(false, points, subpaths, rings);
        out.at(surface, out.root());
        twin::Style s;
        s.fill = twin::Paint::colour(argb(th.water));
        for (const Ring& r : rings) out.path(s, std::span<const twin::Subpath>(subpaths).subspan(r.first, r.count));
        return;
    }
    // the clips: w0 at the street, wclip at the level clipped by w0
    std::vector<twin::Point> p0;
    std::vector<twin::Subpath> s0;
    std::vector<Ring> r0;
    pieces(false, p0, s0, r0);
    std::vector<twin::Point> p1;
    std::vector<twin::Subpath> s1;
    std::vector<Ring> r1;
    pieces(true, p1, s1, r1);
    const std::int32_t w0 = out.begin_clip("w0");
    out.path(twin::Style{}, s0);
    out.end_clip();
    const std::int32_t wclip = out.begin_clip("wclip", twin::Units::user_space, w0);
    out.path(twin::Style{}, s1);
    out.end_clip();
    const std::array<float, 2> offsets{0.0F, 1.0F};
    const std::array<std::uint32_t, 2> colours{argb(th.water_top), argb(th.water_bottom)};
    const std::int32_t wgrad = out.gradient(
        {.id = "wgrad",
         .radial = false,
         .coords = {0.0F, static_cast<float>(frame_.wgrad_y1), 0.0F, static_cast<float>(frame_.wgrad_y2), 0.0F},
         .units = twin::Units::user_space,
         .offsets = offsets,
         .colours = colours});
    const std::int32_t group = out.group({.node = {.clip = wclip}, .parent = out.root()});
    out.at(surface, group);
    twin::Style grad;
    grad.fill = twin::Paint::gradient(wgrad);
    out.rect(grad, 0.0F, 0.0F, w, h);
    emit_reflections(state, out, group);
    emit_glitter(state, out, group);
    emit_rings(state, out, group);
    out.at(slot(geo::group::water_top, 0, geo::water_sub::tint), group);
    twin::Style tint;
    tint.fill = twin::Paint::colour(argb(th.water));
    out.rect(tint, 0.0F, 0.0F, w, h, 0.0F, 0.0F, {.opacity = frame_.night ? 0.04F : 0.12F});
}

} // namespace atlas::passes
