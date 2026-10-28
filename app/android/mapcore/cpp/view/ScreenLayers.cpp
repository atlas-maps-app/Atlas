// The screen layers (view/ScreenLayers.hpp): R/live/ScreenLayers.kt's `of`, `haze` and `sunPoint`, line for line.
#include "view/ScreenLayers.hpp"

#include "base/pymath/PyMath.hpp"
#include "view/Ladder.hpp"
#include "view/Snap.hpp"

#include <cmath>
#include <cstdlib>

namespace atlas::view {

namespace {

/// City.clamp(x): Python's max(0, min(1, x)).
[[nodiscard]] double clamp01(double x) noexcept { return pymath::max(0.0, pymath::min(1.0, x)); }

[[nodiscard]] float f32(double v) noexcept { return static_cast<float>(v); }

} // namespace

LayerSettings settings_of(const Placed& cfg) noexcept {
    LayerSettings s;
    s.width = cfg.number("W").value_or(0.0);
    s.height = cfg.number("H").value_or(0.0);
    s.pitch = cfg.number("pitch").value_or(90.0);
    s.haze0 = cfg.number("haze0").value_or(haze0_default);
    s.haze1 = cfg.number("haze1").value_or(haze1_default);
    s.ground_y = cfg.camera().target.y;
    s.no_fin = cfg.truthy("no_fin");
    return s;
}

Haze haze_of(const Camera& cam, double haze_max, double haze0, double haze1, double ground_y, bool eye, double hy,
             double height) {
    // render/amsterdam.py:899 render: the 17 stops, and the eye-level band (ScreenLayers.haze)
    const double h_frame = height;
    auto alpha = [&](std::optional<double> gd) {
        return !gd ? haze_max : haze_max * pymath::pow(clamp01((*gd - haze0) / haze1), haze_power);
    };
    Haze h;
    if (eye && hy > 0) {
        // at eye level, far things sit on the horizon line from both sides: a soft band, never an edge
        std::array<double, 25> ys{};
        std::array<double, 25> as{};
        for (int k = -12; k <= 12; ++k) {
            const double dy = (h_frame - hy) * pymath::pow(static_cast<double>(std::abs(k)) / 12.0, 2.0) * 0.5;
            const std::optional<double> gd = cam.ground_depth(hy + pymath::max(dy, 0.01), ground_y);
            const int index = k + 12;
            const auto i = static_cast<std::size_t>(index);
            ys[i] = hy + (k > 0 ? dy : -dy);
            as[i] = pymath::min(alpha(gd), haze_max * 0.18);
        }
        h.band = true;
        h.y0 = ys[0];
        h.y1 = ys[24];
        h.count = 25;
        for (std::size_t i = 0; i < 25; ++i) {
            h.offsets[i] = (ys[i] - h.y0) / (h.y1 - h.y0);
            h.alphas[i] = as[i] * 0.8;
        }
        return h;
    }
    const double top = pymath::max(0.0, hy);
    h.band = false;
    h.y0 = top;
    h.y1 = h_frame;
    h.count = 17;
    for (int k = 0; k < 17; ++k) {
        const double sy = top + (h_frame - top) * static_cast<double>(k) / 16;
        const auto i = static_cast<std::size_t>(k);
        h.alphas[i] = alpha(cam.ground_depth(sy + 0.01, ground_y));
        h.offsets[i] = static_cast<double>(k) / 16.0;
    }
    return h;
}

std::optional<Vec2> sun_point(const Camera& cam, const std::array<double, 3>& sun, double width, double hy) {
    // render/amsterdam.py:297 sky_layer: the sun's glow (ScreenLayers.sunPoint)
    const Vec3& c = cam.eye();
    const std::optional<Vec2> sp = cam.proj({c.x + sun[0] * 4000, c.y + sun[1] * 4000, c.z + sun[2] * 4000});
    if (sp && -sun_margin < sp->x && sp->x < width + sun_margin && -sun_margin < sp->y &&
        sp->y < hy + sun_below_horizon) {
        return sp;
    }
    return std::nullopt;
}

Layers layers_of(const Camera& cam, const style::ThemeBlock& th, const LayerSettings& s, bool front) {
    // ScreenLayers.of
    Layers l;
    l.width = s.width;
    l.height = s.height;
    l.front = front;
    l.weather = th.has(style::theme_flag::weather);
    l.night = th.has(style::theme_flag::night);
    // truthy(th['sky_k']): set, and not 0 (a theme without it reads 1 with the flag clear)
    l.overcast = th.has(style::theme_flag::overcast) && th.sky_k != 0.0;
    const double pitch = s.pitch;
    l.eye = l.weather && pitch < 10;
    const double hy = cam.horizon_y();
    l.horizon = hy;
    if (l.weather && hy > 4) {
        if (l.night) {
            l.clouds = Clouds{.k = 1.5, .t = -0.1, .red = 0.17, .green = 0.15, .blue = 0.19};
        } else if (l.overcast) {
            l.clouds = Clouds{.k = 2.2, .t = 0.18, .red = 0.66, .green = 0.68, .blue = 0.72};
        } else {
            l.clouds = Clouds{.k = 9.0, .t = 0.62 - th.cloud * 0.25, .red = 0.98, .green = 0.98, .blue = 0.99};
        }
        if (!l.night && th.sun_vector[1] > 0) l.sun = sun_point(cam, th.sun_vector, s.width, hy);
    }
    l.haze = haze_of(cam, th.haze_max, s.haze0, s.haze1, s.ground_y, l.eye, hy, s.height);
    // truthy(th['cloud_shadows']): set, and not 0
    const bool cloud_shadows = th.has(style::theme_flag::cloud_shadows) && th.cloud_shadows != 0.0;
    if (l.weather && cloud_shadows && !l.eye) l.cloud_shadow_cut = -7 * (0.62 - th.cloud_shadows * 0.3);
    if (l.weather && th.has(style::theme_flag::wet)) l.rain_angle = pymath::to_int(th.wind * 0.8);
    l.draws_fin = l.weather && !front && !l.eye && !s.no_fin;
    l.sky_bottom = pymath::min(s.height, hy + 2);
    l.wash = th.has(style::theme_flag::golden);
    return l;
}

shaders::FrameLayers block_of(const Layers& layers, const style::ThemeBlock& th, std::uint32_t steps,
                              std::uint32_t levels) noexcept {
    shaders::FrameLayers b{};
    const Haze& h = layers.haze;
    // As the SVG prints them: the band's rows at %.1f, the stops' first row at %.1f and last at %d (a whole height).
    const double y0 = decimals(h.y0, 1);
    const double y1 = h.band ? decimals(h.y1, 1) : std::trunc(h.y1);
    const double span = y1 - y0;
    b.haze = {f32(y0), f32(y1), span > 0 ? f32(1.0 / span) : 0.0F, f32(static_cast<double>(h.count))};
    b.haze_colour = {static_cast<float>(th.haze.r) / 255.0F, static_cast<float>(th.haze.g) / 255.0F,
                     static_cast<float>(th.haze.b) / 255.0F, 0.0F};
    for (std::size_t i = 0; i < h.count && i < 28; ++i) {
        b.haze_offsets[i / 4][static_cast<int>(i % 4)] = f32(decimals(h.offsets[i], 3));
        b.haze_alphas[i / 4][static_cast<int>(i % 4)] = f32(decimals(h.alphas[i], 3));
    }
    b.vignette = {layers.draws_fin ? f32(layers.vignette_opacity()) : 0.0F, 0.0F, 0.0F, 0.0F};
    std::uint32_t on = steps;
    if (!layers.draws_fin) on &= ~(shaders::COMPOSITE_VIGNETTE | shaders::COMPOSITE_GRAIN);
    if (h.count == 0) on &= ~shaders::COMPOSITE_HAZE;
    if (!layers.wash) on &= ~shaders::COMPOSITE_WASH;
    if (!layers.cloud_shadows()) on &= ~(shaders::COMPOSITE_CLOUD_SHADOWS | shaders::COMPOSITE_CLOUD_GROUND);
    if (!layers.rain_angle || layers.front) on &= ~shaders::COMPOSITE_RAIN;
    b.steps = {on, levels, 0U, 0U};
    return b;
}

} // namespace atlas::view
