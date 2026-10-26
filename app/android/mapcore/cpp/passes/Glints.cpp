// The water's glints (passes/Ground.hpp; ENGINE.md step 4, the effects; render/amsterdam.py:487 water_layer's "wind
// ripples", R/Amsterdam.kt waterLayer; fx/Glints.hpp): with weather where the water is drawn, after its tint, sky-
// coloured glints where the noise peaks. At a map pitch one rect over the frame, `<rect y="0.0" width=W height=H
// filter="url(#glint0)" opacity=rip/>`, its filter the glints' field (feTurbulence .02 × .3, seed 5) through the
// colour matrix that keeps the glint's colour where 11 · red − 6.9 is positive. At eye level the port draws a rect a
// glint band, each its noise's frequencies by the band's distance: neither side draws them yet (they come with the
// GPU's turbulence).
//
// The rect is all filter: its own pixels never show (a generator-only filter, which the likeness's structural
// references leave out with its element, AnchorCompiler.generatorOnly). So in full mode alone: where the fields are
// bound, one triangle over the frame inside the water's clip at the glints' slot (glints_fs), the field's red at the
// pixel picking the filter's result from the moment's table of 256 (made on the CPU: fx::glint_table), at the rect's
// opacity, premultiplied; before the fields come, nothing. In structural mode neither side draws them.
#include "fx/Glints.hpp"

#include "frame/FrameBuilder.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"

#include <cstdint>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;

void Ground::decide_wind_glints(const view::Camera& cam, const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.wind_glints = false;
    if (!f.weather || !f.water || no_wind_glints_ || only_setts_ || f.tiles.empty()) return;
    if (state.mode != frame::Mode::full) return;
    // `eye = cam.f[1] > -0.25 and 0 < hy < H`: at eye level the glint bands (still to come), else one band, the frame
    const double hy = cam.horizon_y();
    const bool eye = cam.forward().y > -0.25 && 0 < hy && hy < cam.height();
    if (eye) return;
    f.wind_glints = true;
    f.wind_glints_opacity = fx::glint_opacity(state.theme.wind, f.night);
}

bool Ground::glints_filtered(const frame::FrameState& state) const noexcept {
    return frame_.wind_glints && state.mode == frame::Mode::full &&
           (state.block.info.x & shaders::FRAME_FIELDS) != 0U && pipelines_ != nullptr && *pipelines_->wind_glints;
}

void Ground::write_wind_glints(std::vector<std::uint32_t>& words, const frame::FrameState& state) {
    Words& w = *words_;
    w.wind_glints = static_cast<std::uint32_t>(words.size());
    if (!glints_filtered(state)) return;
    const std::array<fx::Argb, 256> table = fx::glint_table(fx::glint_colour(frame_.night));
    words.insert(words.end(), table.begin(), table.end());
}

void Ground::record_wind_glints(const frame::Recording& r) {
    if (!glints_filtered(r.state)) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->wind_glints);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::water_top, 0, geo::water_sub::glints), w.wind_glints, 0U};
    pc.rows = {0.0F, 0.0F, static_cast<float>(frame_.wind_glints_opacity), 0.0F};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground glints", 1);
}

void Ground::emit_wind_glints(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const {
    if (!frame_.wind_glints || !state.view) return;
    const view::Camera& cam = state.view->camera();
    out.at(slot(geo::group::water_top, 0, geo::water_sub::glints), water);
    twin::Style s;
    s.fill = twin::Paint::colour(0xFF00'0000U);
    out.rect(s, 0.0F, 0.0F, static_cast<float>(cam.width()), static_cast<float>(cam.height()), 0.0F, 0.0F,
             {.opacity = static_cast<float>(frame_.wind_glints_opacity), .filter = out.filter("glint0")});
}

} // namespace atlas::passes
