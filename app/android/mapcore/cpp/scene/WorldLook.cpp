// The world's look of a frame (scene/WorldLook.hpp).
#include "scene/WorldLook.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cmath>

namespace atlas::scene {

namespace {

[[nodiscard]] float f32(double v) noexcept { return static_cast<float>(v); }

/// A 0xRRGGBB colour as 0-1 values, with `a` beside them.
[[nodiscard]] hlsl::float4 rgb_of(std::uint32_t c, double a) noexcept {
    return {static_cast<float>((c >> 16U) & 0xFFU) / 255.0F, static_cast<float>((c >> 8U) & 0xFFU) / 255.0F,
            static_cast<float>(c & 0xFFU) / 255.0F, f32(a)};
}

[[nodiscard]] hlsl::float4 vec_of(const view::Vec3& v, double w) noexcept {
    return {f32(v.x), f32(v.y), f32(v.z), f32(w)};
}

[[nodiscard]] double sine_of_degrees(double degrees) noexcept { return pymath::sin(pymath::radians(degrees)); }

} // namespace

double ladder_weight(double distance, double from, double to) noexcept {
    if (!(distance > from)) return 0.0;
    if (distance >= to) return 1.0;
    const double t = pymath::log(distance / from) / pymath::log(to / from);
    return t * t * (3.0 - (2.0 * t));
}

LadderWeights ladder_weights(const tiles::WorldLadder& ladder, double distance) noexcept {
    return {.caps = ladder_weight(distance, ladder.from, ladder.caps),
            .ramps = ladder_weight(distance, ladder.ramps, ladder.board),
            .ring = ladder_weight(distance, ladder.ring, ladder.board),
            .space = ladder_weight(distance, ladder.caps, ladder.ring),
            .haze = ladder_weight(distance, ladder.ramps, ladder.ring)};
}

GeoPoint sub_solar(double azimuth, double elevation, double lat, double lon) noexcept {
    const double az = pymath::radians(azimuth);
    const double el = pymath::radians(elevation);
    const double p = pymath::radians(lat);
    const double l = pymath::radians(lon);
    // the sun's direction at the place: east, north and up
    const double e = pymath::sin(az) * pymath::cos(el);
    const double n = pymath::cos(az) * pymath::cos(el);
    const double u = pymath::sin(el);
    // in the planet's axes (x through 0° 0°, y through 0° 90° E, z through the north pole)
    const double sp = pymath::sin(p);
    const double cp = pymath::cos(p);
    const double sl = pymath::sin(l);
    const double cl = pymath::cos(l);
    const double x = (-sl * e) + (-sp * cl * n) + (cp * cl * u);
    const double y = (cl * e) + (-sp * sl * n) + (cp * sl * u);
    const double z = (cp * n) + (sp * u);
    return {.lat = pymath::degrees(pymath::asin(std::clamp(z, -1.0, 1.0))),
            .lon = pymath::degrees(pymath::atan2(y, x))};
}

double light_night(const tiles::WorldLadder& ladder, const GeoPoint& sun, double lat, double lon) noexcept {
    const double p = pymath::radians(lat);
    const double s = pymath::radians(sun.lat);
    const double sine = (pymath::sin(p) * pymath::sin(s)) +
                        (pymath::cos(p) * pymath::cos(s) * pymath::cos(pymath::radians(lon - sun.lon)));
    const double altitude = pymath::degrees(pymath::asin(std::clamp(sine, -1.0, 1.0)));
    if (!(ladder.light_over > 0.0)) return 0.0;
    return std::clamp((-altitude - ladder.light_from) / ladder.light_over, 0.0, 1.0);
}

double name_rise(const tiles::WorldLadder& ladder, const LadderWeights& weights, const GeoPoint& sun, double lat,
                 double lon) noexcept {
    const auto smooth = [](double v) {
        const double t = std::clamp(v, 0.0, 1.0);
        return t * t * (3.0 - (2.0 * t));
    };
    const double shown = smooth(weights.caps / name_rise_caps);
    if (!(shown > 0.0)) return 0.0;
    return shown * smooth(light_night(ladder, sun, lat, lon) / light_least_night);
}

double light_halo_dp(const tiles::WorldLadder& ladder, double people) noexcept {
    const std::size_t size = people >= static_cast<double>(ladder.light_sizes[0])
                                 ? 0U
                                 : (people >= static_cast<double>(ladder.light_sizes[1]) ? 1U : 2U);
    return ladder.light_radii.at(size) * ladder.light_halo;
}

view::Haze bent_haze(const view::Camera& cam, double drop, double sphere, double haze_max, double haze0, double haze1,
                     double height, double space) noexcept {
    // The frame's middle column looks along the ground from the target in one direction (the view's and its up's,
    // flat), so its rows are the points of the bent ground's profile there: `s` metres from the target along it,
    // dropped by the drop and blended to the sphere by w₂ (scene::world_point's blend, along a great circle).
    const view::Vec3& f = cam.forward();
    const view::Vec3& u = cam.up();
    const view::Vec3& target = cam.spec().target;
    const view::Vec3& eye = cam.eye();
    double dx = f.x + u.x;
    double dz = f.z + u.z;
    const double flat = pymath::sqrt((dx * dx) + (dz * dz));
    if (!(flat > 0.0)) return {};
    dx /= flat;
    dz /= flat;
    const double w = std::clamp(sphere, 0.0, 1.0);
    constexpr double radius = view::earth_radius;
    const auto point = [&](double s) {
        const double a = s / radius;
        const double along = s + (((radius * pymath::sin(a)) - s) * w);
        const double down = ((drop * s * s) * (1.0 - w)) + ((radius * (1.0 - pymath::cos(a))) * w);
        return view::Vec3{target.x + (dx * along), target.y - down, target.z + (dz * along)};
    };
    // a point's row on the frame, or far below it where it stands behind the eye
    const auto row = [&](double s) {
        const std::optional<view::Vec2> p = cam.proj(point(s));
        return p ? p->y : 1e18;
    };
    // The horizon: the highest row the profile reaches ahead of the eye's foot, a quarter of the way round the
    // planet at most (the rows fall to it and rise again beyond it).
    const double foot = ((eye.x - target.x) * dx) + ((eye.z - target.z) * dz);
    double lo = foot;
    double hi = 0.49 * pymath::pi * radius;
    for (int i = 0; i < 200 && hi - lo > 1.0; ++i) {
        const double a = lo + ((hi - lo) / 3.0);
        const double b = hi - ((hi - lo) / 3.0);
        if (row(a) > row(b)) {
            lo = a;
        } else {
            hi = b;
        }
    }
    const double limb = (lo + hi) / 2.0;
    const double top = std::max(0.0, row(limb));
    // the nearest ground the frame can show: behind the eye's foot, as far as it still stands before the eye
    const double height_over = std::max(eye.y - target.y, 1.0);
    double near = foot;
    for (int i = 0; i < 60 && row(near) < height + 1.0; ++i) near -= height_over;
    const auto alpha = [&](double depth) {
        return haze_max * pymath::pow(std::clamp((depth - haze0) / haze1, 0.0, 1.0), view::haze_power);
    };
    view::Haze h;
    h.band = false;
    h.y0 = top;
    h.y1 = height;
    // over space, a stop of what the haze keeps there, and the horizon's own a pixel and a half below it
    const bool over_space = space > 0.0 && top > 0.0 && height - top > 3.0;
    const std::uint32_t first = over_space ? 2U : 0U;
    const double step = over_space ? 1.5 / (height - top) : 0.0;
    h.count = 17 + first;
    for (int k = 0; k < 17; ++k) {
        const double sy = top + ((height - top) * static_cast<double>(k) / 16.0);
        // the ground at the row: the profile's point there, between the nearest ground and the horizon (the rows
        // fall as s grows)
        double a = near;
        double b = limb;
        for (int i = 0; i < 60 && b - a > 0.5; ++i) {
            const double m = (a + b) / 2.0;
            if (row(m) > sy + 0.01) {
                a = m;
            } else {
                b = m;
            }
        }
        const auto i = static_cast<std::size_t>(k) + first;
        h.alphas.at(i) = alpha(cam.depth(point((a + b) / 2.0)));
        h.offsets.at(i) = std::max(static_cast<double>(k) / 16.0, k == 0 ? step : 0.0);
    }
    if (over_space) {
        h.alphas[0] = h.alphas[2] * (1.0 - std::clamp(space, 0.0, 1.0));
        h.offsets[0] = 0.0;
        h.alphas[1] = h.alphas[2];
        h.offsets[1] = step;
        h.offsets[2] = std::max(h.offsets[2], step);
    }
    return h;
}

view::Vec3 direction_of(const WorldBend& bend, double lat, double lon) noexcept {
    const double p = pymath::radians(lat);
    const double l = pymath::radians(lon - bend.lon);
    const double cp = pymath::cos(p);
    const double sp = pymath::sin(p);
    // east, up and north at the target, the target's meridian at l = 0
    const double east = cp * pymath::sin(l);
    const double up = (bend.cos_lat * cp * pymath::cos(l)) + (bend.sin_lat * sp);
    const double north = (bend.cos_lat * sp) - (bend.sin_lat * cp * pymath::cos(l));
    return {east, up, -north};
}

WorldLook world_look(const tiles::WorldLadder& ladder, double distance, const style::ThemeBlock& theme,
                     const WorldBend& bend, const view::Vec3& eye, double density, double width,
                     double height) noexcept {
    WorldLook look;
    look.weights = ladder_weights(ladder, distance);
    look.sun = sub_solar(theme.sun_azimuth, theme.sun_elevation, static_cast<double>(theme.place_latitude),
                         static_cast<double>(theme.place_longitude));
    look.night = theme.has(style::theme_flag::night);
    shaders::WorldLookBlock& b = look.block;
    const LadderWeights& w = look.weights;
    b.weights = {f32(w.caps), f32(w.ramps), f32(w.ring), f32(w.space)};
    // by day under the ramps the look changes a fragment only where a cap lies: nowhere while the sun stands over all
    // the eye sees, the cap of the planet about the point under it out to its horizon
    const view::Vec3 up{eye.x - bend.target.x, eye.y - bend.target.y + view::earth_radius, eye.z - bend.target.z};
    const double from_centre = pymath::sqrt((up.x * up.x) + (up.y * up.y) + (up.z * up.z));
    if (!look.night && w.caps > 0.0 && w.ramps == 0.0 && w.ring == 0.0 && from_centre > view::earth_radius) {
        const view::Vec3 sun = direction_of(bend, look.sun.lat, look.sun.lon);
        const double sine = ((up.x * sun.x) + (up.y * sun.y) + (up.z * sun.z)) / from_centre;
        const double under_eye = pymath::degrees(pymath::asin(std::clamp(sine, -1.0, 1.0)));
        const double horizon = pymath::degrees(pymath::acos(view::earth_radius / from_centre));
        constexpr double margin = 0.5;
        look.plain = under_eye - horizon > ladder.cap_soft + margin;
        if (look.plain) b.weights.x = 0.0F;
    }
    // the caps' edges a degree soft below the globe, the pixel's on it (globe_night takes the wider)
    b.sun = vec_of(direction_of(bend, look.sun.lat, look.sun.lon), sine_of_degrees(ladder.cap_soft) * (1.0 - w.ramps));
    b.pole = vec_of(direction_of(bend, 90.0, 0.0), sine_of_degrees(ladder.meridian_most));
    b.prime = vec_of(direction_of(bend, 0.0, 0.0), 0.5 * ladder.graticule_width * density);
    b.east = vec_of(direction_of(bend, 0.0, 90.0), std::floor((180.0 / ladder.meridian_step) + 0.5));
    b.centre = {f32(bend.target.x - eye.x), f32(bend.target.y - view::earth_radius - eye.y), f32(bend.target.z - eye.z),
                f32(view::earth_radius)};
    for (std::size_t i = 0; i < ladder.sea.size(); ++i) b.sea[i] = rgb_of(ladder.sea.at(i).colour, ladder.sea.at(i).at);
    b.sea_at = {f32(ladder.sea_centre[0]), f32(ladder.sea_centre[1]), f32(1.0 / ladder.sea_radius), 0.0F};
    for (std::size_t i = 0; i < ladder.land.size(); ++i) {
        b.land[i] = rgb_of(ladder.land.at(i).colour, ladder.land.at(i).at);
    }
    b.coast = rgb_of(ladder.coast.colour, ladder.coast.opacity);
    b.coast_at = {f32(ladder.coast_width * density), 0.0F, 0.0F, 0.0F};
    b.night = rgb_of(ladder.night, 0.0);
    b.cap_sines = {f32(sine_of_degrees(ladder.cap_altitudes[0])), f32(sine_of_degrees(ladder.cap_altitudes[1])),
                   f32(sine_of_degrees(ladder.cap_altitudes[2])), f32(sine_of_degrees(ladder.cap_altitudes[3]))};
    b.cap_alphas = {f32(ladder.cap_opacities[0]), f32(ladder.cap_opacities[1]), f32(ladder.cap_opacities[2]),
                    f32(ladder.cap_opacities[3])};
    b.graticule = rgb_of(ladder.graticule.colour, ladder.graticule.opacity);
    b.parallels = {f32(sine_of_degrees(ladder.parallel_step)), f32(sine_of_degrees(2.0 * ladder.parallel_step)),
                   f32(sine_of_degrees(ladder.parallel_most)), f32(pymath::cos(pymath::radians(ladder.meridian_step)))};
    b.space = rgb_of(ladder.space, 0.0);
    b.space_glow = rgb_of(ladder.glow, ladder.glow_end);
    b.space_at = {f32(ladder.glow_centre[0] * width), f32(ladder.glow_centre[1] * height),
                  f32(1.0 / (ladder.glow_radius * std::max(width, height))), 0.0F};
    b.air = rgb_of(ladder.air.colour, ladder.air.opacity);
    b.air_at = {f32(ladder.air_at[0]), f32(ladder.air_at[1]), f32(ladder.air_at[2]), 0.0F};
    b.outline = rgb_of(ladder.outline.colour, ladder.outline.opacity);
    b.outline_at = {f32(0.5 * ladder.outline_width * density), f32(ladder.outline_dash[0] * density),
                    f32(ladder.outline_dash[1] * density), 0.0F};
    b.rim = rgb_of(ladder.rim.colour, ladder.rim.opacity);
    b.shine = rgb_of(ladder.shine.colour, ladder.shine.opacity);
    b.shine_at = {f32(ladder.shine_centre[0]), f32(ladder.shine_centre[1]), f32(1.0 / ladder.shine_radius),
                  f32(0.5 * ladder.rim_width * density)};
    for (std::size_t i = 0; i < ladder.lights.size(); ++i) {
        b.light[i] = rgb_of(ladder.lights.at(i).colour, ladder.lights.at(i).opacity);
    }
    b.light_at = {f32(ladder.light_radii[0] * density), f32(ladder.light_radii[1] * density),
                  f32(ladder.light_radii[2] * density), f32(ladder.light_halo)};
    b.light_rule = {f32(ladder.light_halo_opacity), f32(ladder.light_from), f32(ladder.light_over),
                    f32(ladder.light_facing)};
    b.light_least = {f32(ladder.light_least_facing), f32(light_least_night), 0.0F, 0.0F};
    return look;
}

} // namespace atlas::scene
