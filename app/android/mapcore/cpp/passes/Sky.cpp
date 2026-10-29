// The ground's sky (passes/Ground.hpp; plan-vector D10 "Sky gradient": a band above the horizon row, only when it is
// on screen). render draws `<rect width=W height=H fill="url(#sky)"/>` first of all (amsterdam.py:644, ScreenLayers'
// skyRect): #sky is a linearGradient in user space from sky_top at row hy - 300 to sky_hor at the horizon's row hy,
// each printed '%.0f', padded beyond, so below the horizon (and over the whole frame when the horizon lies above it, as
// at every map pitch) it is sky_hor. The core draws it as one triangle over the frame at the ground's first painter
// slot, opaque, last of the opaque ground: only what nothing nearer covers is shaded. The sky layer over it (with
// weather and the horizon on screen: the clouds and the sun's glow, K17's; the city's glow at night, K18's) takes the
// sky group's next slots (geometry/Ground.hpp's sky_sub); no board or sweep camera shows the horizon (N5).
//
// The sky layer away from eye level (render/amsterdam.py:284 sky_layer's second branch, ScreenLayers.skyLayer; the
// app's steepest tilt is far from it): where the sun's point lies within the margins by day, its glow (a circle of
// radius 170, 230 overcast, filled with `sunglow`: a radialGradient over its box, #FFF8E6 at 1 (.75 overcast), #FFE9C2
// at .45 (.25) at .25, #FFE0B0 at 0 at its edge) and, under a sky that is not overcast, its disc (a circle of 9 px in
// #FFFDF4); at night the city's glow (`cityglow`: a linearGradient of #FF9E5A from 0 at 220 px above the horizon to .28
// on it, over a rect down to the sky layer's bottom). Each is drawn translucent at its slot, so the ground nearer than
// the sky hides it as the renders paint the ground over it: the glow at each pixel's centre (it fades to nothing at its
// edge), the disc and the rect's bottom edge sample by sample. The clouds come with their field (K17).
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"

#include <array>
#include <cmath>
#include <cstdint>

namespace atlas::passes {

namespace geo = atlas::geometry;

namespace {

/// The sun's glow's stops (offsets 0, .25 and 1) and the city's glow's (0 and 1) as the SVG parser keeps them: each
/// colour with its stop's opacity folded into its alpha, Java's Math.round(255 · opacity) of the float, RGBA8 packed.
[[nodiscard]] std::uint32_t folded(std::uint32_t rgb, float opacity) noexcept {
    const auto alpha = static_cast<std::uint32_t>(std::floor((255.0F * opacity) + 0.5F));
    return geo::Rgba{.r = static_cast<std::uint8_t>((rgb >> 16U) & 0xFFU),
                     .g = static_cast<std::uint8_t>((rgb >> 8U) & 0xFFU),
                     .b = static_cast<std::uint8_t>(rgb & 0xFFU),
                     .a = static_cast<std::uint8_t>(alpha)}
        .packed();
}

[[nodiscard]] std::array<std::uint32_t, 3> sun_stops(bool overcast) noexcept {
    return {folded(0xFFF8E6U, overcast ? 0.75F : 1.0F), folded(0xFFE9C2U, overcast ? 0.25F : 0.45F),
            folded(0xFFE0B0U, 0.0F)};
}

[[nodiscard]] std::array<std::uint32_t, 2> city_stops() noexcept {
    return {folded(0xFF9E5AU, 0.0F), folded(0xFF9E5AU, 0.28F)};
}

/// The twin's ARGB of an RGBA8 stop.
[[nodiscard]] std::uint32_t argb_of(std::uint32_t rgba) noexcept {
    return (rgba & 0xFF00'FF00U) | ((rgba & 0xFFU) << 16U) | ((rgba >> 16U) & 0xFFU);
}

/// The sun's disc's colour (opaque).
constexpr std::uint32_t sun_disc_rgb = 0xFFFDF4U;
/// The disc's radius and the city's glow's rise above the horizon (CSS px).
constexpr double sun_disc_r = 9.0;
constexpr double city_glow_rise = 220.0;

} // namespace

void Ground::decide_sky(const view::Camera& cam, const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.horizon = cam.horizon_y();
    // ScreenLayers.skyDef: y1 '%.0f' of hy - 300, y2 '%.0f' of hy
    f.sky_y1 = printed(f.horizon - 300.0, 0);
    f.sky_y2 = printed(f.horizon, 0);
    // water_layer's wgrad: y1 '%d' of max(0, hy), y2 '%d' of H (Python's int(): toward zero)
    f.wgrad_y1 = std::trunc(pymath::max(0.0, f.horizon));
    f.wgrad_y2 = std::trunc(cam.height());
    // sky_layer (with weather and hy > 4, where the layers have clouds), away from eye level
    f.glow = false;
    f.city_glow = false;
    const std::optional<view::Layers>& l = state.layers;
    if (!l || !l->clouds || l->eye || f.out(style::camera_part::sky)) return;
    f.sky_bottom = printed(l->sky_bottom, 1);
    if (l->sun) {
        // '<circle cx="%.1f" cy="%.1f" r="%d" fill="url(#sunglow)"/>'
        f.glow = true;
        f.glow_x = printed(l->sun->x, 1);
        f.glow_y = printed(l->sun->y, 1);
        f.overcast = l->overcast;
        f.glow_r = l->overcast ? 230.0 : 170.0;
    }
    if (l->night && !l->front) {
        // ScreenLayers.cityGlowDef: y1 '%.0f' of hy - 220, y2 '%.0f' of hy
        f.city_glow = true;
        f.city_y1 = printed(f.horizon - city_glow_rise, 0);
        f.city_y2 = printed(f.horizon, 0);
    }
}

gpu::Result<void> Ground::make_sky_pipelines(const frame::PassContext& context, Pipelines& p) const {
    gpu::GraphicsDesc sky = ground_desc(context, p, "ground sky", "screen_vs", "gradient_fs");
    opaque_depth(sky);
    ATLAS_GPU_LET(s, frame::make_pipeline(context, sky, frame::Overdraw::opaque));
    p.sky = std::move(s);
    // the sky layer: translucent at its slots, behind every nearer ground
    gpu::GraphicsDesc glow = ground_desc(context, p, "ground sun glow", "disc_vs", "sunglow_fs");
    translucent(glow);
    ATLAS_GPU_LET(g, frame::make_pipeline(context, glow, frame::Overdraw::translucent));
    p.sun_glow = std::move(g);
    gpu::GraphicsDesc disc = ground_desc(context, p, "ground sun disc", "disc_vs", "sundisc_fs");
    translucent(disc);
    ATLAS_GPU_LET(d, frame::make_pipeline(context, disc, frame::Overdraw::translucent));
    p.sun_disc = std::move(d);
    gpu::GraphicsDesc city = ground_desc(context, p, "ground city glow", "screen_vs", "cityglow_fs");
    translucent(city);
    ATLAS_GPU_LET(c, frame::make_pipeline(context, city, frame::Overdraw::translucent));
    p.city_glow = std::move(c);
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
    pc.mode = {0U, slot(geo::group::sky, 0, geo::sky_sub::gradient), 0U, 0U};
    pc.rows = {static_cast<float>(frame_.sky_y1), static_cast<float>(frame_.sky_y2), 1.0F, 0.0F};
    pc.colour = {rgba(th.sky_top), rgba(th.sky_horizon), 0U, 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground sky", 1);
}

void Ground::record_sky_layer(const frame::Recording& r) {
    const GroundFrame& f = frame_;
    if (!f.glow && !f.city_glow) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto push = [&] {
        cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    };
    if (f.glow) {
        // the glow, then (under a sky that is not overcast) the disc, at the sun's slot
        pc.mode = {0U, slot(geo::group::sky, 0, geo::sky_sub::sun), 0U, samples};
        const std::array<std::uint32_t, 3> stops = sun_stops(f.overcast);
        pc.rows = {static_cast<float>(f.glow_x), static_cast<float>(f.glow_y), static_cast<float>(f.glow_r), 0.0F};
        pc.colour = {stops[0], stops[1], stops[2], 0U};
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->sun_glow);
        push();
        cmd.draw(6, 1, 0, 0);
        r.counters.draw("ground sun glow", 1);
        if (!f.overcast) {
            pc.rows = {static_cast<float>(f.glow_x), static_cast<float>(f.glow_y), static_cast<float>(sun_disc_r),
                       0.0F};
            pc.colour = {folded(sun_disc_rgb, 1.0F), 0U, 0U, 0U};
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->sun_disc);
            push();
            cmd.draw(6, 1, 0, 0);
            r.counters.draw("ground sun disc", 1);
        }
    }
    if (f.city_glow) {
        pc.mode = {0U, slot(geo::group::sky, 0, geo::sky_sub::city), 0U, samples};
        const std::array<std::uint32_t, 2> stops = city_stops();
        pc.rows = {static_cast<float>(f.city_y1), static_cast<float>(f.city_y2), static_cast<float>(f.sky_bottom),
                   0.0F};
        pc.colour = {stops[0], stops[1], 0U, 0U};
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->city_glow);
        push();
        cmd.draw(3, 1, 0, 0);
        r.counters.draw("ground city glow", 1);
    }
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
    out.at(slot(geo::group::sky, 0, geo::sky_sub::gradient), out.root());
    twin::Style s;
    s.fill = twin::Paint::gradient(sky);
    const auto width = static_cast<float>(state.view->camera().width());
    out.rect(s, 0.0F, 0.0F, width, static_cast<float>(state.view->camera().height()));
    // sky_layer: the sun's glow and its disc, the city's glow (ScreenLayers.skyLayer)
    const GroundFrame& f = frame_;
    if (f.glow) {
        const std::array<std::uint32_t, 3> stops = sun_stops(f.overcast);
        const std::array<std::uint32_t, 3> glow_colours{argb_of(stops[0]), argb_of(stops[1]), argb_of(stops[2])};
        const std::array<float, 3> glow_offsets{0.0F, 0.25F, 1.0F};
        const std::int32_t glow = out.gradient({.id = "sunglow",
                                                .radial = true,
                                                .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                                                .units = twin::Units::bounding_box,
                                                .offsets = glow_offsets,
                                                .colours = glow_colours});
        out.at(slot(geo::group::sky, 0, geo::sky_sub::sun), out.root());
        twin::Style g;
        g.fill = twin::Paint::gradient(glow);
        const auto x = static_cast<float>(f.glow_x);
        const auto y = static_cast<float>(f.glow_y);
        out.circle(g, x, y, static_cast<float>(f.glow_r));
        if (!f.overcast) {
            twin::Style d;
            d.fill = twin::Paint::colour(0xFF00'0000U | sun_disc_rgb);
            out.circle(d, x, y, static_cast<float>(sun_disc_r));
        }
    }
    if (f.city_glow) {
        const std::array<std::uint32_t, 2> stops = city_stops();
        const std::array<std::uint32_t, 2> city_colours{argb_of(stops[0]), argb_of(stops[1])};
        const std::int32_t city =
            out.gradient({.id = "cityglow",
                          .radial = false,
                          .coords = {0.0F, static_cast<float>(f.city_y1), 0.0F, static_cast<float>(f.city_y2), 0.0F},
                          .units = twin::Units::user_space,
                          .offsets = offsets, // 0 and 1, as the sky's
                          .colours = city_colours});
        out.at(slot(geo::group::sky, 0, geo::sky_sub::city), out.root());
        twin::Style c;
        c.fill = twin::Paint::gradient(city);
        out.rect(c, 0.0F, 0.0F, width, static_cast<float>(f.sky_bottom));
    }
}

} // namespace atlas::passes
