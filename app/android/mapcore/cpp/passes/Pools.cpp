// The night's light on the ground (passes/Ground.hpp; ENGINE.md step 4, the night; render/amsterdam.py:812's `pools`,
// R/Amsterdam.kt): at night, after the quay walls, each street lamp's pool, `<ellipse cx cy rx ry fill="url(#pool)"/>`
// of 10 m about its foot: its centre the foot's point on the picture, rx 10 m at its depth (10 · focal / z), ry the
// picture's height of 10 m along z from it (at least .6), from 2 m ahead, within the radius cut, in S['nodes']'s order
// across the drawn tiles; the `pool` gradient over its box (#FFB45E at .55, #FFA848 at .18 at .5, at 0 at its edge).
// The lit streets' glow and their wet cores take the group's first two subs (passes/LitGlow.cpp).
//
// On the GPU one quad an ellipse (pool_vs, instanced in their order at the lamps' slot), the gradient at each pixel's
// centre (it fades to nothing at the ellipse's edge, so it needs no edge of its own), translucent: every nearer element
// hides it.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <optional>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

/// A stop of the pool's gradient as the SVG parser keeps it: its colour with the stop's opacity folded into its alpha
/// (Java's Math.round(255 · opacity) of the float), RGBA8 packed.
[[nodiscard]] std::uint32_t folded(std::uint32_t rgb, float opacity) noexcept {
    const auto alpha = static_cast<std::uint32_t>(std::floor((255.0F * opacity) + 0.5F));
    return geo::Rgba{.r = static_cast<std::uint8_t>((rgb >> 16U) & 0xFFU),
                     .g = static_cast<std::uint8_t>((rgb >> 8U) & 0xFFU),
                     .b = static_cast<std::uint8_t>(rgb & 0xFFU),
                     .a = static_cast<std::uint8_t>(alpha)}
        .packed();
}

/// The `pool` gradient's stops at 0, .5 and 1.
[[nodiscard]] std::array<std::uint32_t, 3> pool_stops() noexcept {
    return {folded(0xFFB45EU, 0.55F), folded(0xFFA848U, 0.18F), folded(0xFFA848U, 0.0F)};
}

/// The twin's ARGB of an RGBA8 stop.
[[nodiscard]] std::uint32_t argb_of(std::uint32_t rgba) noexcept {
    return (rgba & 0xFF00'FF00U) | ((rgba & 0xFFU) << 16U) | ((rgba >> 16U) & 0xFFU);
}

/// A pool's radius about its lamp's foot (m).
constexpr double pool_reach = 10.0;

} // namespace

void Ground::decide_pools(const view::Camera& cam) {
    GroundFrame& f = frame_;
    f.lamp_pools.clear();
    if (!f.night || no_pools_ || only_setts_ || f.tiles.empty() || f.out(style::camera_part::lamp_pools)) return;
    // the street lamps in S['nodes']'s order across the drawn tiles
    struct Source {
        std::uint32_t order;
        std::uint32_t tile;
        std::uint32_t k;
    };
    std::vector<Source> lamps;
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        const geo::GroundTile& g = *f.tiles[ti].ground;
        for (std::uint32_t k = 0; k < g.lamps.size(); ++k)
            lamps.push_back({.order = g.lamps[k].index, .tile = ti, .k = k});
    }
    std::ranges::stable_sort(lamps, {}, &Source::order);
    for (const Source& l : lamps) {
        const geo::P2& at = f.tiles[l.tile].ground->lamps[l.k].at;
        if (f.engine && beyond_radius(at.x, at.z, f.target_x, f.target_z, f.radius)) continue;
        const view::Vec3 c = cam.cam({at.x, 0.0, at.z});
        if (c.z < 2) continue;
        const view::Vec2 p = cam.pc(c);
        const double rx = (pool_reach * cam.focal()) / c.z;
        const std::optional<view::Vec2> q = cam.proj({at.x, 0.0, at.z + pool_reach});
        const double ry = pm::max(0.6, std::abs((q ? q->y : p.y) - p.y));
        f.lamp_pools.push_back(
            f.exact ? LampPool{.cx = printed(p.x, 1), .cy = printed(p.y, 1), .rx = printed(rx, 1), .ry = printed(ry, 1)}
                    : LampPool{.cx = p.x, .cy = p.y, .rx = rx, .ry = ry});
    }
}

void Ground::write_pools(std::vector<std::uint32_t>& words) {
    Words& w = *words_;
    w.lamp_pools = static_cast<std::uint32_t>(words.size());
    w.lamp_pool_count = static_cast<std::uint32_t>(frame_.lamp_pools.size());
    for (const LampPool& p : frame_.lamp_pools) {
        for (const double v : {p.cx, p.cy, p.rx, p.ry})
            words.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(v)));
    }
}

gpu::Result<void> Ground::make_pool_pipelines(const frame::PassContext& context, Pipelines& p) const {
    gpu::GraphicsDesc pool = ground_desc(context, p, "ground lamp pools", "pool_vs", "pool_fs");
    translucent(pool);
    ATLAS_GPU_LET(made, frame::make_pipeline(context, pool, frame::Overdraw::translucent));
    p.lamp_pool = std::move(made);
    return {};
}

void Ground::record_pools(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.lamp_pool_count == 0 || !*pipelines_->lamp_pool) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->lamp_pool);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::pools, 0, geo::pools_sub::lamps), w.lamp_pools, 0U};
    const std::array<std::uint32_t, 3> stops = pool_stops();
    pc.colour = {stops[0], stops[1], stops[2], 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(6, w.lamp_pool_count, 0, 0);
    r.counters.draw("ground lamp pools", std::uint64_t{w.lamp_pool_count} * 2, w.lamp_pool_count);
}

void Ground::emit_pools(twin::Stream& out) const {
    const GroundFrame& f = frame_;
    if (f.lamp_pools.empty()) return;
    const std::array<std::uint32_t, 3> stops = pool_stops();
    const std::array<std::uint32_t, 3> colours{argb_of(stops[0]), argb_of(stops[1]), argb_of(stops[2])};
    const std::array<float, 3> offsets{0.0F, 0.5F, 1.0F};
    const std::int32_t pool = out.gradient({.id = "pool",
                                            .radial = true,
                                            .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                                            .units = twin::Units::bounding_box,
                                            .offsets = offsets,
                                            .colours = colours});
    out.at(slot(geo::group::pools, 0, geo::pools_sub::lamps), out.root());
    twin::Style s;
    s.fill = twin::Paint::gradient(pool);
    for (const LampPool& p : f.lamp_pools) {
        out.ellipse(s, static_cast<float>(p.cx), static_cast<float>(p.cy), static_cast<float>(p.rx),
                    static_cast<float>(p.ry));
    }
}

} // namespace atlas::passes
