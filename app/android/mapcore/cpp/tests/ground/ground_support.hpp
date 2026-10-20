// The ground's tests' cameras and moments (plan-vector K9): the Westerkerk home, a region view of a bundled pack
// snapped as the map's camera snaps onto it, both at S1 (1080 × 2400 px, 412 × 916 dp) at rest in engine mode; the
// sunny moment and the fixed night as the port's dicts hold them (style/ThemeTest.cpp's values).
#pragma once

#include "base/pymath/PyMath.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "tests/tiles/support.hpp"
#include "view/Ladder.hpp"

#include <cmath>
#include <filesystem>
#include <string>

namespace atlas::test {

/// A camera state at S1, at rest, in engine mode, looking at (lat, lon) from `distance` with `bearing` and the map's
/// `pitch`, its principal point `cy` dp down.
[[nodiscard]] inline style::CameraState s1_camera(double lat, double lon, double bearing, double pitch, double distance,
                                                  double cy = 440.0) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.principal_x = 206.0;
    c.principal_y = cy;
    c.density = 1080.0 / 412.0;
    c.width_px = 1080;
    c.height_px = 2400;
    c.width_dp = 412;
    c.height_dp = 916;
    c.flags = style::camera_flag::rest | style::camera_flag::engine_mode;
    return c;
}

/// `c` while the camera moves: not at rest (the vertex stage does not snap, the passes decide as frames in motion do).
[[nodiscard]] inline style::CameraState moving(style::CameraState c) {
    c.flags &= ~style::camera_flag::rest;
    return c;
}

/// The Westerkerk home (amsterdam.py's main camera): 10 m west and 30 m north of the Westerkerk, yaw 52°, 38° down,
/// 430 m.
[[nodiscard]] inline style::CameraState home_camera() {
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    return s1_camera(52.3746 - (-30.0) / 110540.0, 4.8840 + (-10.0) / kx, 52.0, 52.0, 430.0);
}

/// The camera of view `view` of pack `pack` in `dir`, which the ladder snaps onto that view (its target, yaw, pitch,
/// distance and principal point); the home camera when the pack or the view is missing.
[[nodiscard]] inline style::CameraState view_camera(const std::string& dir, const std::string& pack,
                                                    std::string_view view) {
    auto p = open_pack((std::filesystem::path(dir) / (pack + ".atlas")).string());
    if (!p || p->head() == nullptr) return home_camera();
    const auto ladder = view::Ladder::of(*p->head());
    if (!ladder) return home_camera();
    for (const view::Ladder::Knot& k : ladder->knots()) {
        if (k.name != view) continue;
        const view::GroundFrame& f = ladder->frame();
        const double lon = f.lon0 + (k.target[0] / f.kx);
        const double lat = f.lat0 - (k.target[2] / f.kz);
        return s1_camera(lat, lon, view::bearing_of(k.yaw), 90.0 - k.pitch, k.dist, std::isnan(k.cy) ? 440.0 : k.cy);
    }
    return home_camera();
}

/// amsterdam.py's sunny moment of 1 October 2026, 16:00 (the port's dict's colours; style/ThemeTest.cpp's values).
[[nodiscard]] inline style::ThemeBlock sunny() {
    namespace flag = style::theme_flag;
    style::ThemeBlock b{};
    b.version = 1;
    b.flags = flag::weather | flag::shadow_blur | flag::cloud_shadows | flag::water_gradient | flag::wet_set;
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 16;
    b.minute = 0;
    b.moment = style::MomentName::sunny;
    b.weather_code = 2;
    b.sky_top = {.r = 0x7F, .g = 0xA6, .b = 0xDA, .a = 255};
    b.sky_horizon = {.r = 0xDC, .g = 0xE7, .b = 0xF2, .a = 255};
    b.haze = {.r = 0xDF, .g = 0xE6, .b = 0xEE, .a = 255};
    b.sun = {.r = 0xFF, .g = 0xEB, .b = 0xCB, .a = 255};
    b.ambient = {.r = 0x93, .g = 0xA6, .b = 0xCC, .a = 255};
    b.ground = {.r = 0xD6, .g = 0xCF, .b = 0xC2, .a = 255};
    b.road = {.r = 0xC9, .g = 0xC1, .b = 0xB4, .a = 255};
    b.water = {.r = 0x4F, .g = 0x75, .b = 0x90, .a = 255};
    b.water_top = {.r = 0xA6, .g = 0xBF, .b = 0xD4, .a = 255};
    b.water_bottom = {.r = 0x2D, .g = 0x50, .b = 0x6C, .a = 255};
    b.shadow = {.r = 0x27, .g = 0x30, .b = 0x5A, .a = 255};
    b.haze_max = 0.22 * 0.85;
    b.sun_k = 1.0;
    b.amb_k = 0.6;
    b.sky_k = 1.0;
    b.shadow_opacity = 0.32;
    b.shadow_blur = 0.6;
    b.cloud_shadows = 0.42;
    const double len = std::sqrt((0.5307 * 0.5307) + (0.2779 * 0.2779) + (0.8006 * 0.8006));
    b.sun_vector = {0.5307 / len, 0.2779 / len, 0.8006 / len};
    b.sun_azimuth = 213.5;
    b.sun_elevation = 16.1;
    b.cloud = 0.42;
    b.cloud_low = 0.1;
    b.wind = 14.0;
    b.gust = 27.0;
    b.wind_direction = 225.0;
    b.temperature = 14.2;
    b.pressure = 1012.5;
    b.visibility = 24000.0;
    b.humidity = 72.0;
    return b;
}

/// amsterdam.py's fixed night: no weather, no water gradient, the sun straight down.
[[nodiscard]] inline style::ThemeBlock night() {
    style::ThemeBlock b = sunny();
    b.flags = style::theme_flag::night;
    b.moment = style::MomentName::night;
    b.weather_code = 255;
    b.sky_top = {.r = 0x05, .g = 0x08, .b = 0x14, .a = 255};
    b.sky_horizon = {.r = 0x1C, .g = 0x23, .b = 0x40, .a = 255};
    b.haze = {.r = 0x16, .g = 0x1D, .b = 0x36, .a = 255};
    b.ground = {.r = 0x12, .g = 0x15, .b = 0x1F, .a = 255};
    b.road = {.r = 0x1B, .g = 0x1F, .b = 0x2C, .a = 255};
    b.water = {.r = 0x0B, .g = 0x14, .b = 0x22, .a = 255};
    b.water_top = {};
    b.water_bottom = {};
    b.shadow = {.r = 0, .g = 0, .b = 0, .a = 255};
    b.shadow_opacity = 0.0;
    b.haze_max = 0.5;
    b.sun_k = 0.0;
    b.shadow_blur = 0.0;
    b.cloud_shadows = 0.0;
    b.sun_vector = {0.0, -1.0, 0.0};
    b.sun_elevation = -21.4;
    b.cloud = 0.0;
    b.cloud_low = 0.0;
    b.wind = 0.0;
    b.gust = 0.0;
    b.wind_direction = 0.0;
    b.temperature = 0.0;
    b.pressure = 0.0;
    b.visibility = 0.0;
    b.humidity = 0.0;
    return b;
}

} // namespace atlas::test
