// The ground's sky (passes/Ground.hpp; plan-vector D10 "Sky gradient": a band above the horizon row, only when it is
// on screen). render draws `<rect width=W height=H fill="url(#sky)"/>` first of all (amsterdam.py:644, ScreenLayers'
// skyRect): #sky is a linearGradient in user space from sky_top at row hy - 300 to sky_hor at the horizon's row hy,
// each printed '%.0f', padded beyond, so below the horizon (and over the whole frame when the horizon lies above it, as
// at every map pitch) it is sky_hor. The core draws it as one triangle over the frame at the ground's first painter
// slot, opaque, last of the opaque ground: only what nothing nearer covers is shaded. The sky layer over it (with
// weather and the horizon on screen: the clouds and the sun's glow, K17's; the city's glow at night, K18's) takes the
// sky group's next slots (geometry/Ground.hpp's group::sky); no board or sweep camera shows the horizon (N5).
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"

#include <cmath>

namespace atlas::passes {

namespace geo = atlas::geometry;

void Ground::decide_sky(const view::Camera& cam) {
    GroundFrame& f = frame_;
    f.horizon = cam.horizon_y();
    // ScreenLayers.skyDef: y1 '%.0f' of hy - 300, y2 '%.0f' of hy
    f.sky_y1 = printed(f.horizon - 300.0, 0);
    f.sky_y2 = printed(f.horizon, 0);
    // water_layer's wgrad: y1 '%d' of max(0, hy), y2 '%d' of H (Python's int(): toward zero)
    f.wgrad_y1 = std::trunc(pymath::max(0.0, f.horizon));
    f.wgrad_y2 = std::trunc(cam.height());
}

gpu::Result<void> Ground::make_sky_pipelines(const frame::PassContext& context, Pipelines& p) const {
    gpu::GraphicsDesc sky = ground_desc(context, p, "ground sky", "screen_vs", "gradient_fs");
    opaque_depth(sky);
    ATLAS_GPU_LET(s, frame::make_pipeline(context, sky, frame::Overdraw::opaque));
    p.sky = std::move(s);
    return {};
}

void Ground::record_sky(const frame::Recording& r) {
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    const style::ThemeBlock& th = r.state.theme;
    auto rgba = [](const style::Rgb8& c) { return geo::Rgba{.r = c.r, .g = c.g, .b = c.b, .a = 255}.packed(); };
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->sky);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::sky, 0, 0), 0U, 0U};
    pc.rows = {static_cast<float>(frame_.sky_y1), static_cast<float>(frame_.sky_y2), 1.0F, 0.0F};
    pc.colour = {rgba(th.sky_top), rgba(th.sky_horizon), 0U, 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground sky", 1);
}

void Ground::emit_sky(const frame::FrameState& state, twin::Stream& out) const {
    if (!state.view) return;
    const style::ThemeBlock& th = state.theme;
    auto argb = [](const style::Rgb8& c) {
        return 0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
    };
    const std::array<float, 2> offsets{0.0F, 1.0F};
    const std::array<std::uint32_t, 2> colours{argb(th.sky_top), argb(th.sky_horizon)};
    const std::int32_t sky = out.gradient(
        {.id = "sky",
         .radial = false,
         .coords = {0.0F, static_cast<float>(frame_.sky_y1), 0.0F, static_cast<float>(frame_.sky_y2), 0.0F},
         .units = twin::Units::user_space,
         .offsets = offsets,
         .colours = colours});
    out.at(slot(geo::group::sky, 0, 0), out.root());
    twin::Style s;
    s.fill = twin::Paint::gradient(sky);
    out.rect(s, 0.0F, 0.0F, static_cast<float>(state.view->camera().width()),
             static_cast<float>(state.view->camera().height()));
}

} // namespace atlas::passes
