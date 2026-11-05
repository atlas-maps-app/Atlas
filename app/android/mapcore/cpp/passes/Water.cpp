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
// On the GPU (the opaque ground): first, before what lies above the water, the w0 rings' fans counted in the stencil's
// three low bits (front faces up, back faces down, wrapping), where that count is not zero the w0 bit set and the count
// cleared, so the app's map's streets keep off the water (passes/Ground.cpp's record_streets); after what lies above
// it, the wclip rings' fans counted where the w0 bit is set, then the surface where the count is not zero, at the
// water's painter depth (opaque: what lies above it was drawn first). Its tint and the end of its clip are translucent
// phases at their orders, so K16's and K17's water elements draw inside the clip between them. Without weather the w0
// rings' count is the clip: the surface's plain draw keeps it for the streets, drawn after the land, and it is cleared
// after them (record_water_end).
//
// The app's map's thin water (4 November, the far flicker; the streets' least width's rule, ground.slang's
// least_point): a canal or a ditch narrower on the screen than 1.5 device px takes 0 to 4 quarters of its pixels'
// samples as the camera moves, so it flickered. Stage T measures the water's width across each edge of its pieces to
// the other bank (geometry/Water.hpp's thin_edges, GroundGpu::thin). Where it is narrower than 1.5 px at either end of
// such a stretch and the frame draws both banks' pieces, the stretch's quad from its bank across the water sets the
// thin bit where the water is counted once and clears the count there (record_water_thin), so the surface leaves those
// samples to the band: after the land and the plate, along the water's middle 1.5 px wide at the share of them the
// water covers (as wide as the water where wider), in the surface's colour with its tint over it, at the water's depth
// (so not over the surface, nor over what lies above the water), each sample once (both banks draw it), before the
// streets (record_water_band). The reflections, the glints, the glitter and the rings keep the water's own samples:
// their clip is the count or the thin bit, never the band beyond the water. The tint leaves the thin bit's samples
// (the band holds it). The thin bit and the band's are cleared with the clip (record_water_end), not by a pass of
// their own. The vertex stage decides each stretch, so the CPU leaves out beforehand, by chunks of a tile's stretches
// in their order, those it would fold every one of (cull_thin): wider on the screen than 1.5 px at both ends of each
// by a bound that never overstates it, off the screen with their bands' reach or before the near plane, or on pieces
// the frame does not draw; the rest draw in runs, the frame's pixels as without the cull.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <span>

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

/// The most runs a drawn tile's thin edges are drawn in (Ground::cull_thin), and the fewest edges left out between two
/// runs: two runs nearer each other are joined, the edges between them drawn and folded, since a draw costs more
/// than the vertex stage's work on that many edges.
constexpr std::size_t thin_runs_most = 4;
constexpr std::uint32_t thin_runs_apart = 2 * geo::thin_chunk;

} // namespace

ThinView::ThinView(const shaders::FrameCamera& c) {
    const auto d = [](float v) { return static_cast<double>(v); };
    const std::array<double, 3> r{d(c.right.x), d(c.right.y), d(c.right.z)};
    const std::array<double, 3> u{d(c.up.x), d(c.up.y), d(c.up.z)};
    forward = {d(c.forward.x), d(c.forward.y), d(c.forward.z)};
    const double focal = d(c.forward.w);
    const double cx = d(c.right.w);
    const double cy = d(c.up.w);
    // the view's CSS px: its device px over the density, as clip_of maps them
    const double width = 2.0 * d(c.device.z) / d(c.device.x);
    const double height = 2.0 * d(c.device.z) / d(c.device.y);
    const double m = 2.0;
    for (std::size_t i = 0; i < 3; ++i) {
        sides[0][i] = (focal * r[i]) + ((cx + m) * forward[i]);
        sides[1][i] = ((width + m - cx) * forward[i]) - (focal * r[i]);
        sides[2][i] = ((cy + m) * forward[i]) - (focal * u[i]);
        sides[3][i] = (focal * u[i]) + ((height + m - cy) * forward[i]);
    }
    near = d(c.frame.w);
    px = focal * d(c.frame.z);
}

bool thin_folded(const geo::ThinChunk& k, const shaders::TilePlace& p, const ThinView& v, double y0, double y1) {
    const auto sx = static_cast<double>(p.scale.x);
    if (!(sx > 0.0)) return false;
    // its eye-relative box (eye_relative's), a centimetre out for the vertex stage's float
    const auto ox = static_cast<double>(p.offset.x);
    const auto oy = static_cast<double>(p.offset.y);
    const auto oz = static_cast<double>(p.offset.z);
    std::array<double, 3> lo{(k.x0 * sx) + ox - 0.01, y0 + oy - 0.01, k.z0 + oz - 0.01};
    std::array<double, 3> hi{(k.x1 * sx) + ox + 0.01, y1 + oy + 0.01, k.z1 + oz + 0.01};
    // the most and the least of n · p over the box
    const auto most = [&lo, &hi](const std::array<double, 3>& n) {
        double s = 0;
        for (std::size_t i = 0; i < 3; ++i) s += pymath::max(n[i] * lo[i], n[i] * hi[i]);
        return s;
    };
    const auto least = [&lo, &hi](const std::array<double, 3>& n) {
        double s = 0;
        for (std::size_t i = 0; i < 3; ++i) s += pymath::min(n[i] * lo[i], n[i] * hi[i]);
        return s;
    };
    if (most(v.forward) < v.near) return true;
    const double h = -hi[1];
    double far = 0;
    for (std::size_t i = 0; i < 3; ++i) far += pymath::max(lo[i] * lo[i], hi[i] * hi[i]);
    const double depth = least(v.forward);
    if (!(h > 0.0) || !(depth > 0.0) || !(4.0 * depth * depth >= far)) return false;
    const double per_metre = v.px * h * pymath::min(1.0, sx) / far;
    if (per_metre * k.least >= 1.05 * 1.5) return true;
    const double reach = (1.05 * 0.75 * pymath::max(1.0, sx) / per_metre) + 0.01;
    lo[0] -= reach;
    hi[0] += reach;
    lo[2] -= reach;
    hi[2] += reach;
    return least(v.forward) > 0.0 &&
           std::ranges::any_of(v.sides, [&most](const std::array<double, 3>& n) { return most(n) < 0.0; });
}

WaterPiece Ground::view_water(const view::Camera& cam, const geo::GroundTile& g, std::uint32_t i,
                              PolyScratch& s) const {
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
        const ProjectedPoly p = project_poly(cam, ring, y, fill.margin, s.cs, s.sp);
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
    // the app's map's thin water: where a thin edge's quad lies on water counted once, the count and the thin bit
    // turned over (the count cleared, the bit set), so a sample takes it once. A ring turns clockwise on the screen
    // (orient_water's turn, x east and z south): its fan's back faces count its samples down, so once is the count's 7.
    gpu::GraphicsDesc thin = ground_desc(context, p, "ground water thin", "thin_vs", "thin_fs");
    colour_off(thin);
    thin.stencil_test = true;
    thin.stencil = {.failOp = vk::StencilOp::eKeep,
                    .passOp = vk::StencilOp::eInvert,
                    .depthFailOp = vk::StencilOp::eInvert,
                    .compareOp = vk::CompareOp::eEqual,
                    .compareMask = ground_stencil::water_count | ground_stencil::water_thin,
                    .writeMask = ground_stencil::water_count | ground_stencil::water_thin,
                    .reference = ground_stencil::water_count};
    ATLAS_GPU_LET(th, frame::make_pipeline(context, thin, frame::Overdraw::none));
    p.water_thin = std::move(th);
    // their band over what lies under it at its share, at the water's depth (not over the surface or what lies above
    // the water), each sample once by the band's bit (cleared with the clip, record_water_end)
    gpu::GraphicsDesc band = ground_desc(context, p, "ground water band", "thin_band_vs", "thin_fs");
    translucent(band);
    band.depth_write = true;
    band.stencil_test = true;
    band.stencil = {.failOp = vk::StencilOp::eKeep,
                    .passOp = vk::StencilOp::eReplace,
                    .depthFailOp = vk::StencilOp::eKeep,
                    .compareOp = vk::CompareOp::eNotEqual,
                    .compareMask = ground_stencil::water_band,
                    .writeMask = ground_stencil::water_band,
                    .reference = ground_stencil::water_band};
    ATLAS_GPU_LET(bd, frame::make_pipeline(context, band, frame::Overdraw::translucent));
    p.water_band = std::move(bd);
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
    // without weather: th['water'] where the count is not zero, the count kept for the app's map's streets, which are
    // drawn after the land (record_streets; record_water_end clears it after them)
    gpu::GraphicsDesc flat = ground_desc(context, p, "ground water plain", "screen_vs", "flat_fs");
    opaque_depth(flat);
    flat.stencil_test = true;
    flat.stencil = inside;
    ATLAS_GPU_LET(fl, frame::make_pipeline(context, flat, frame::Overdraw::opaque));
    p.water_flat = std::move(fl);
    // the tint inside the clip, translucent
    gpu::GraphicsDesc tint = ground_desc(context, p, "ground water tint", "screen_vs", "flat_fs");
    translucent(tint);
    tint.stencil_test = true;
    tint.stencil = inside;
    ATLAS_GPU_LET(ti, frame::make_pipeline(context, tint, frame::Overdraw::translucent));
    p.water_tint = std::move(ti);
    // the water's elements' clip: where the count is not zero or the thin bit is set (the thin water's own samples)
    vk::StencilOpState water = inside;
    water.compareMask = ground_stencil::water_count | ground_stencil::water_thin;
    // the reflections inside the clip (Reflections.cpp): in the renders' order, each written at its slot's depth
    gpu::GraphicsDesc mirrored = ground_desc(context, p, "ground water reflections", "reflection_vs", "reflection_fs");
    translucent(mirrored);
    mirrored.depth_write = true;
    mirrored.stencil_test = true;
    mirrored.stencil = water;
    ATLAS_GPU_LET(rf, frame::make_pipeline(context, mirrored, frame::Overdraw::translucent));
    p.reflection = std::move(rf);
    // the rippled reflections inside the clip (Ripple.cpp): the filter's result at the group's opacity, at its slot
    gpu::GraphicsDesc rippled = ground_desc(context, p, "ground water ripple", "screen_vs", "ripple_fs");
    translucent(rippled);
    rippled.stencil_test = true;
    rippled.stencil = water;
    ATLAS_GPU_LET(rc, frame::make_pipeline(context, rippled, frame::Overdraw::translucent));
    p.ripple_composite = std::move(rc);
    // the glints inside the clip (Glints.cpp): the filter's result from the moment's table at the rect's opacity
    gpu::GraphicsDesc glints = ground_desc(context, p, "ground water glints", "screen_vs", "glints_fs");
    translucent(glints);
    glints.stencil_test = true;
    glints.stencil = water;
    ATLAS_GPU_LET(gn, frame::make_pipeline(context, glints, frame::Overdraw::translucent));
    p.wind_glints = std::move(gn);
    // the sun's glitter (Glitter.cpp): screened over the water, each sample once by the first glint to reach it
    // (inside the clip, its w0 bit still set: then cleared; the thin bit lies under w0's, so the test is w0's with
    // either), per sample where the device can
    gpu::GraphicsDesc glitter = ground_desc(context, p, "ground water glitter", "glitter_vs", "glitter_fs");
    translucent(glitter);
    glitter.blends[0] = gpu::Blend::screen();
    glitter.stencil_test = true;
    glitter.stencil = {.failOp = vk::StencilOp::eKeep,
                       .passOp = vk::StencilOp::eZero,
                       .depthFailOp = vk::StencilOp::eKeep,
                       .compareOp = vk::CompareOp::eLess,
                       .compareMask =
                           ground_stencil::water_count | ground_stencil::water_thin | ground_stencil::water_w0,
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
    rings.stencil = water;
    ATLAS_GPU_LET(rg, frame::make_pipeline(context, rings, frame::Overdraw::translucent));
    p.rings = std::move(rg);
    // the clip's end: its bits and the thin water's cleared over the frame
    gpu::GraphicsDesc end = ground_desc(context, p, "ground water end", "screen_vs", "flat_fs");
    colour_off(end);
    end.stencil_test = true;
    end.stencil = {.failOp = vk::StencilOp::eZero,
                   .passOp = vk::StencilOp::eZero,
                   .depthFailOp = vk::StencilOp::eZero,
                   .compareOp = vk::CompareOp::eAlways,
                   .compareMask = 0,
                   .writeMask = ground_stencil::water_count | ground_stencil::water_w0 | ground_stencil::water_thin |
                                ground_stencil::water_band,
                   .reference = 0};
    ATLAS_GPU_LET(e, frame::make_pipeline(context, end, frame::Overdraw::none));
    p.water_end = std::move(e);
    return {};
}

void Ground::water_fans(const frame::Recording& r, std::uint32_t flags) const {
    push(r, flags, slot(geo::group::water, 0, 0));
    for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
        const DrawnTile& t = frame_.tiles[i];
        const geo::Words draw = t.ground->layout.fans;
        if (draw.count == 0 || !t.pass->ranges[0]) continue;
        r.frame.cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
        r.counters.draw("ground water", draw.count / 3);
    }
}

void Ground::water_screen(const frame::Recording& r, const vk::raii::Pipeline& pipeline, std::array<float, 4> rows,
                          std::array<std::uint32_t, 4> colour) const {
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::water, 0, 0), 0U, 0U};
    pc.rows = {rows[0], rows[1], rows[2], rows[3]};
    pc.colour = {colour[0], colour[1], colour[2], colour[3]};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground water", 1);
}

void Ground::record_water_count(const frame::Recording& r) {
    if (!frame_.water) return;
    r.frame.cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->water_count);
    water_fans(r, 0);
    if (frame_.weather) water_screen(r, pipelines_->water_convert, {}, {});
}

void Ground::record_water_opaque(const frame::Recording& r) {
    if (!frame_.water) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Pipelines& p = *pipelines_;
    const style::ThemeBlock* th = &r.state.theme;
    if (!frame_.weather) {
        // every drawn ring in th['water'], at the street (record_water_count counted them), but the thin water's
        record_water_thin(r, 0);
        water_screen(r, p.water_flat, {0.0F, 0.0F, 1.0F, 0.0F}, {0U, 0U, rgba(th->water).packed(), 0U});
        return;
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *p.water_count_level);
    water_fans(r, ground_flag::water_level);
    record_water_thin(r, ground_flag::water_level);
    // wgrad: water_top at max(0, hy) to water_bot at the frame's bottom
    water_screen(r, p.water_surface,
                 {static_cast<float>(frame_.wgrad_y1), static_cast<float>(frame_.wgrad_y2), 1.0F, 0.0F},
                 {rgba(th->water_top).packed(), rgba(th->water_bottom).packed(), 0U, 0U});
}

void Ground::cull_thin(const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.thin_runs.clear();
    if (!f.water || !f.grounded || !thin_water_ || !state.view) return;
    const ThinView view(state.block.camera);
    // the bits and heights thin_of reads: with weather its pieces' at their level, without at the street
    const std::uint8_t bit = f.weather ? drawn_bit::level : drawn_bit::street;
    for (std::size_t i = 0; i < f.tiles.size(); ++i) {
        const DrawnTile& t = f.tiles[i];
        if (t.ground->layout.thin.count == 0 || !t.pass->ranges[0]) continue;
        const auto drawn = [&f, &t, bit](std::uint32_t fill) { return (f.drawn[t.decisions + fill] & bit) != 0; };
        const std::size_t from = f.thin_runs.size();
        for (const geo::ThinChunk& k : t.ground->thin_chunks) {
            if (thin_folded(k, t.place, view, f.weather ? k.y0 : 0.0, f.weather ? k.y1 : 0.0)) {
                continue;
            }
            // on pieces the frame draws: none of its pairs both drawn (where it names them all)
            if (k.pairs <= geo::thin_pairs && std::ranges::none_of(std::span(k.pair.data(), k.pairs),
                                                                   [&drawn](const std::array<std::uint32_t, 2>& q) {
                                                                       return drawn(q[0]) && drawn(q[1]);
                                                                   })) {
                continue;
            }
            // the run before it extended over fewer than thin_runs_apart edges left out
            if (f.thin_runs.size() > from &&
                k.first - (f.thin_runs.back().first + f.thin_runs.back().count) < thin_runs_apart) {
                f.thin_runs.back().count = k.first + k.count - f.thin_runs.back().first;
            } else {
                f.thin_runs.push_back({.tile = static_cast<std::uint32_t>(i), .first = k.first, .count = k.count});
            }
        }
        // at most thin_runs_most a tile: the two nearest each other joined, over and over
        while (f.thin_runs.size() - from > thin_runs_most) {
            std::size_t best = from;
            std::uint32_t least = std::numeric_limits<std::uint32_t>::max();
            for (std::size_t j = from; j + 1 < f.thin_runs.size(); ++j) {
                const std::uint32_t gap = f.thin_runs[j + 1].first - (f.thin_runs[j].first + f.thin_runs[j].count);
                if (gap < least) {
                    least = gap;
                    best = j;
                }
            }
            GroundFrame::ThinRun& a = f.thin_runs[best];
            const GroundFrame::ThinRun& b = f.thin_runs[best + 1];
            a.count = b.first + b.count - a.first;
            f.thin_runs.erase(f.thin_runs.begin() + static_cast<std::ptrdiff_t>(best) + 1);
        }
    }
}

bool Ground::thin_water() const noexcept { return !frame_.thin_runs.empty(); }

void Ground::thin_draws(const frame::Recording& r, std::uint32_t flags, std::array<float, 4> rows,
                        std::array<std::uint32_t, 4> colour) const {
    const Words& w = *words_;
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.rows = {rows[0], rows[1], rows[2], rows[3]};
    pc.colour = {colour[0], colour[1], colour[2], colour[3]};
    std::uint32_t tile = std::numeric_limits<std::uint32_t>::max();
    for (const GroundFrame::ThinRun& run : frame_.thin_runs) {
        if (run.tile != tile) {
            // thin_of finds a vertex's record from its tile's first, six vertices a record
            tile = run.tile;
            const DrawnTile& t = frame_.tiles[tile];
            pc.mode = {flags, slot(geo::group::water, 0, 0),
                       (t.pass->ranges[0].offset / 4) + t.ground->layout.thin.first, 0U};
            r.frame.cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0,
                                                                             pc);
        }
        // a draw a run, from its first record's first vertex
        r.frame.cmd.draw(6 * run.count, 1, 6 * run.first, tile);
        r.counters.draw("ground water", 2 * std::uint64_t{run.count});
    }
}

void Ground::record_water_thin(const frame::Recording& r, std::uint32_t flags) {
    if (!thin_water()) return;
    r.frame.cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->water_thin);
    thin_draws(r, flags, {}, {});
}

void Ground::record_water_band(const frame::Recording& r) {
    if (!thin_water()) return;
    const style::ThemeBlock& th = r.state.theme;
    r.frame.cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->water_band);
    // the surface's colour (with weather wgrad, as record_water_opaque draws it, and its tint over it at
    // record_water_tint's opacity; else th['water']), at the edges' height there (their level or the street)
    if (frame_.weather) {
        thin_draws(r, ground_flag::water_level,
                   {static_cast<float>(frame_.wgrad_y1), static_cast<float>(frame_.wgrad_y2),
                    frame_.night ? 0.04F : 0.12F, 0.0F},
                   {rgba(th.water_top).packed(), rgba(th.water_bottom).packed(), rgba(th.water).packed(), 0U});
    } else {
        const std::uint32_t c = rgba(th.water).packed();
        thin_draws(r, 0, {}, {c, c, c, 0U});
    }
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

void Ground::record_water_end(const frame::Recording& r, bool weather) {
    if (!frame_.water || frame_.weather != weather) return;
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
    emit_wind_glints(state, out, group);
    out.at(slot(geo::group::water_top, 0, geo::water_sub::tint), group);
    twin::Style tint;
    tint.fill = twin::Paint::colour(argb(th.water));
    out.rect(tint, 0.0F, 0.0F, w, h, 0.0F, 0.0F, {.opacity = frame_.night ? 0.04F : 0.12F});
}

} // namespace atlas::passes
