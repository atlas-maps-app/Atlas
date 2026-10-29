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

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"

#include <array>
#include <bit>
#include <cstdint>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

std::array<float, 9> water_map(const view::Camera& cam, double water_y, double density, std::uint32_t width,
                               std::uint32_t height) {
    // the home's noise on the ground: device px of the fields a metre across its view (cos yaw, sin yaw) and down it
    // (-sin yaw, cos yaw), at its target: its focal over its distance, down foreshortened by its pitch's sine
    constexpr double yaw = 52.0;
    constexpr double pitch = 38.0;
    constexpr double dist = 430.0;
    constexpr double fov = 36.0;
    constexpr double frame_h = 915.0;
    const double k = ((frame_h / 2) / pm::tan(pm::radians(fov) / 2)) / dist * density;
    const double cos_yaw = pm::cos(pm::radians(yaw));
    const double sin_yaw = pm::sin(pm::radians(yaw));
    const double down = pm::sin(pm::radians(pitch));
    const std::array<double, 4> l{k * cos_yaw, k * sin_yaw, -k * down * sin_yaw, k * down * cos_yaw};
    // the ray through upright device point (x, y): d = dx x + dy y + d1, its CSS point (x / s, y / s)
    const view::Vec3& r = cam.right();
    const view::Vec3& u = cam.up();
    const view::Vec3& f = cam.forward();
    const view::Vec3& c = cam.eye();
    const double fs = cam.focal() * density;
    const std::array<double, 3> dx{r.x / fs, r.y / fs, r.z / fs};
    const std::array<double, 3> dy{-u.x / fs, -u.y / fs, -u.z / fs};
    const double ox = -cam.cx() / cam.focal();
    const double oy = cam.cy() / cam.focal();
    const std::array<double, 3> d1{f.x + (r.x * ox) + (u.x * oy), f.y + (r.y * ox) + (u.y * oy),
                                   f.z + (r.z * ox) + (u.z * oy)};
    // the plane: P = C + (water_y - C.y) d / d.y, its x and z each a row over d.y
    const double hh = water_y - c.y;
    std::array<double, 3> px{};
    std::array<double, 3> pz{};
    std::array<double, 3> w{};
    const std::array<const std::array<double, 3>*, 3> cols{&dx, &dy, &d1};
    for (std::size_t i = 0; i < 3; ++i) {
        const std::array<double, 3>& d = *cols[i];
        px[i] = (c.x * d[1]) + (hh * d[0]);
        pz[i] = (c.z * d[1]) + (hh * d[2]);
        w[i] = d[1];
    }
    std::array<double, 3> r0{};
    std::array<double, 3> r1{};
    for (std::size_t i = 0; i < 3; ++i) {
        r0[i] = (l[0] * px[i]) + (l[1] * pz[i]);
        r1[i] = (l[2] * px[i]) + (l[3] * pz[i]);
    }
    // whole mirror periods off, about the frame's middle (where it lies below the horizon)
    const double mx = static_cast<double>(width) / 2;
    const double my = static_cast<double>(height) / 2;
    const double wm = (w[0] * mx) + (w[1] * my) + w[2];
    if (wm < 0) {
        const double period_x = 2.0 * width;
        const double period_y = 2.0 * height;
        const double qx = ((r0[0] * mx) + (r0[1] * my) + r0[2]) / wm;
        const double qy = ((r1[0] * mx) + (r1[1] * my) + r1[2]) / wm;
        const double sx = period_x * pm::floor(qx / period_x);
        const double sy = period_y * pm::floor(qy / period_y);
        for (std::size_t i = 0; i < 3; ++i) {
            r0[i] -= sx * w[i];
            r1[i] -= sy * w[i];
        }
    }
    std::array<float, 9> m{};
    for (std::size_t i = 0; i < 3; ++i) {
        m[i] = static_cast<float>(r0[i]);
        m[3 + i] = static_cast<float>(r1[i]);
        m[6 + i] = static_cast<float>(w[i]);
    }
    return m;
}

WaterPoint water_point(const std::array<float, 9>& m, float x, float y) noexcept {
    // fx_water_point's float steps, left to right, each rounded on its own
    const float w = ((m[6] * x) + (m[7] * y)) + m[8];
    if (!(w < 0.0F)) return {};
    const float u = ((m[0] * x) + (m[1] * y)) + m[2];
    const float v = ((m[3] * x) + (m[4] * y)) + m[5];
    return {.x = u / w, .y = v / w, .on = true};
}

double water_level(const GroundFrame& f) noexcept {
    return !f.tiles.empty() && f.tiles.front().ground != nullptr ? f.tiles.front().ground->water_y : 0.0;
}

void Ground::decide_wind_glints(const view::Camera& cam, const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.wind_glints = false;
    if (!f.weather || !f.water || no_wind_glints_ || only_setts_ || f.tiles.empty() ||
        f.out(style::camera_part::glints)) {
        return;
    }
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
    if (!glints_filtered(state) || !state.view) return;
    const std::array<fx::Argb, 256> table = fx::glint_table(fx::glint_colour(frame_.night));
    words.insert(words.end(), table.begin(), table.end());
    // then the water's map, where glints_fs reads the field
    const std::array<float, 9> map = water_map(state.view->camera(), water_level(frame_), state.camera.density,
                                               state.camera.width_px, state.camera.height_px);
    for (const float v : map) words.push_back(std::bit_cast<std::uint32_t>(v));
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
