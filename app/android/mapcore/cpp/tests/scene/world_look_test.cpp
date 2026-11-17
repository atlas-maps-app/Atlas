// The world's look beyond 18 km (ENGINE.md step 5, "The style ladder" and "The globe"; scene/WorldLook.hpp): the
// ladder's weights at its knots, the point the moment's sun is overhead of, the planet's axes in a frame, the board's
// ramps, caps and lights (shaders/shared/globe.slang, its own text here) and the frame's look block.
#include "base/pymath/PyMath.hpp"
#include "load/PackStyle.hpp"
#include "scene/WorldLook.hpp"
#include "style/Theme.hpp"
#include "tests/base/require.hpp"
#include "view/Bend.hpp"
#include "view/Camera.hpp"
#include "view/ScreenLayers.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <optional>

namespace {

using namespace atlas;

/// The place the tests' theme blocks are written for (the bundled packs', the Westerkerk).
constexpr double place_latitude = 52.3746;
constexpr double place_longitude = 4.8840;

/// A colour's 8-bit levels of a 0-1 rgb.
[[nodiscard]] std::uint32_t hex(hlsl::float3 c) {
    const auto level = [](float v) { return static_cast<std::uint32_t>(std::lround(v * 255.0F)); };
    return (level(c.x) << 16U) | (level(c.y) << 8U) | level(c.z);
}

/// The sun's azimuth (from north, clockwise) and elevation (degrees) over (`lat`, `lon`) when it is overhead of `sun`:
/// the port's sunPosition from the hour angle on.
[[nodiscard]] std::array<double, 2> seen_from(double lat, double lon, scene::GeoPoint sun) {
    const double la = pymath::radians(lat);
    const double dec = pymath::radians(sun.lat);
    const double ha = pymath::radians(lon - sun.lon);
    const double el =
        pymath::asin((pymath::sin(la) * pymath::sin(dec)) + (pymath::cos(la) * pymath::cos(dec) * pymath::cos(ha)));
    const double az =
        pymath::atan2(-pymath::sin(ha), (pymath::tan(dec) * pymath::cos(la)) - (pymath::sin(la) * pymath::cos(ha)));
    const double degrees = pymath::degrees(az);
    return {degrees < 0.0 ? degrees + 360.0 : degrees, pymath::degrees(el)};
}

/// A frame's bend with its target at (`lat`, `lon`), its eye `height` metres over it.
[[nodiscard]] scene::WorldBend bend_at(double lat, double lon, double height) {
    scene::WorldBend b;
    b.lat = lat;
    b.lon = lon;
    b.sin_lat = pymath::sin(pymath::radians(lat));
    b.cos_lat = pymath::cos(pymath::radians(lat));
    b.sphere = 1.0;
    b.eye = {0.0, height, 0.0};
    return b;
}

} // namespace

TEST_CASE("the ladder: its weights are 0 up to their first knot and 1 from their last, easing in log D between") {
    const tiles::WorldLadder l = load::world_ladder();
    CHECK(l.from == 18'000.0);
    CHECK(l.caps == 75'000.0);
    CHECK(l.ramps == 600'000.0);
    CHECK(l.ring == 2'000'000.0);
    CHECK(l.board == 6'000'000.0);
    // up to 18 km nothing of it
    for (const double d : {0.0, 430.0, 17'999.0, 18'000.0}) {
        const scene::LadderWeights w = scene::ladder_weights(l, d);
        CHECK(w.caps == 0.0);
        CHECK(w.ramps == 0.0);
        CHECK(w.ring == 0.0);
        CHECK(w.space == 0.0);
        CHECK(w.haze == 0.0);
    }
    // at each knot
    const scene::LadderWeights at75 = scene::ladder_weights(l, 75'000.0);
    CHECK(at75.caps == 1.0);
    CHECK(at75.space == 0.0);
    CHECK(at75.ramps == 0.0);
    const scene::LadderWeights at600 = scene::ladder_weights(l, 600'000.0);
    CHECK(at600.ramps == 0.0);
    CHECK(at600.haze == 0.0);
    CHECK(at600.space > 0.5);
    CHECK(at600.space < 1.0);
    const scene::LadderWeights at2000 = scene::ladder_weights(l, 2'000'000.0);
    CHECK(at2000.space == 1.0);
    CHECK(at2000.haze == 1.0);
    CHECK(at2000.ring == 0.0);
    CHECK(at2000.ramps > 0.0);
    const scene::LadderWeights at6000 = scene::ladder_weights(l, 6'000'000.0);
    CHECK(at6000.ramps == 1.0);
    CHECK(at6000.ring == 1.0);
    // half way in log D, half
    CHECK(scene::ladder_weight(std::sqrt(18'000.0 * 75'000.0), 18'000.0, 75'000.0) == doctest::Approx(0.5));
    // and rising throughout
    double last = 0.0;
    for (int i = 0; i <= 200; ++i) {
        const double d = 18'000.0 * pymath::pow(60'000.0 / 18.0, static_cast<double>(i) / 200.0);
        const double w = scene::ladder_weights(l, d).caps + scene::ladder_weights(l, d).ramps;
        CHECK(w >= last);
        last = w;
    }
    // the world's style holds it; a pack's own style holds none
    const tiles::Style* world = load::world_style();
    ATLAS_REQUIRE(world != nullptr);
    const std::optional<tiles::WorldLadder>& held = world->ladder;
    ATLAS_REQUIRE(held.has_value());
    CHECK(held->board == l.board);
    CHECK(held->sea[0].colour == 0x3A86C8U);
    auto section = load::world_style_section();
    ATLAS_REQUIRE(section.has_value());
    auto tables = tiles::read_style(std::move(*section));
    ATLAS_REQUIRE(tables.has_value());
    auto typed = tiles::Style::of(*tables);
    ATLAS_REQUIRE(typed.has_value());
    CHECK(!typed->ladder.has_value());
}

TEST_CASE("the ladder's sun: the point it is overhead of follows from its azimuth and elevation over the moment's "
          "place, by day and under the horizon") {
    for (const scene::GeoPoint sun :
         {scene::GeoPoint{.lat = 10.0, .lon = -30.0}, scene::GeoPoint{.lat = -23.4, .lon = 4.884},
          scene::GeoPoint{.lat = 23.4, .lon = 170.0}, scene::GeoPoint{.lat = 0.0, .lon = -175.0},
          scene::GeoPoint{.lat = -5.0, .lon = 95.0}}) {
        const std::array<double, 2> seen = seen_from(place_latitude, place_longitude, sun);
        const scene::GeoPoint back = scene::sub_solar(seen[0], seen[1], place_latitude, place_longitude);
        CHECK(back.lat == doctest::Approx(sun.lat).epsilon(1e-9));
        CHECK(pymath::cos(pymath::radians(back.lon - sun.lon)) == doctest::Approx(1.0).epsilon(1e-12));
        // and from another place
        const std::array<double, 2> tokyo = seen_from(35.68, 139.69, sun);
        const scene::GeoPoint again = scene::sub_solar(tokyo[0], tokyo[1], 35.68, 139.69);
        CHECK(again.lat == doctest::Approx(sun.lat).epsilon(1e-9));
        CHECK(pymath::cos(pymath::radians(again.lon - sun.lon)) == doctest::Approx(1.0).epsilon(1e-12));
    }
    // the sun at the zenith is overhead of the place itself
    const scene::GeoPoint here = scene::sub_solar(0.0, 90.0, place_latitude, place_longitude);
    CHECK(here.lat == doctest::Approx(place_latitude));
    CHECK(here.lon == doctest::Approx(place_longitude));
}

TEST_CASE("the ladder's axes: a point's direction from the planet's centre in a frame is up at its target, and the "
          "sun's altitude anywhere is its angle to the sun's") {
    const scene::WorldBend b = bend_at(52.37, 4.89, 1e7);
    const view::Vec3 up = scene::direction_of(b, 52.37, 4.89);
    CHECK(up.x == doctest::Approx(0.0));
    CHECK(up.y == doctest::Approx(1.0));
    CHECK(up.z == doctest::Approx(0.0));
    // a degree north of the target lies to the north (−z), a degree east to the east (+x)
    const view::Vec3 north = scene::direction_of(b, 53.37, 4.89);
    CHECK(north.z == doctest::Approx(-pymath::sin(pymath::radians(1.0))));
    CHECK(north.x == doctest::Approx(0.0));
    const view::Vec3 east = scene::direction_of(b, 52.37, 5.89);
    CHECK(east.x > 0.0);
    // the pole's direction is the frame's north tilted up by the latitude
    const view::Vec3 pole = scene::direction_of(b, 90.0, 0.0);
    CHECK(pole.y == doctest::Approx(b.sin_lat));
    CHECK(pole.z == doctest::Approx(-b.cos_lat));
    // the sun's altitude at a place is the angle between the place's direction and the sun's, less a quarter turn
    const scene::GeoPoint sun{.lat = 12.0, .lon = -40.0};
    const view::Vec3 s = scene::direction_of(b, sun.lat, sun.lon);
    for (const scene::GeoPoint place :
         {scene::GeoPoint{.lat = 52.37, .lon = 4.89}, scene::GeoPoint{.lat = 35.68, .lon = 139.69},
          scene::GeoPoint{.lat = -33.9, .lon = 18.4}}) {
        const view::Vec3 n = scene::direction_of(b, place.lat, place.lon);
        const double altitude = pymath::degrees(pymath::asin((n.x * s.x) + (n.y * s.y) + (n.z * s.z)));
        CHECK(altitude == doctest::Approx(seen_from(place.lat, place.lon, sun)[1]).epsilon(1e-9));
    }
}

TEST_CASE("the globe's look: the board's ramps at their stops, the night's caps each over the last, and the lights by "
          "the board's rule") {
    const tiles::WorldLadder l = load::world_ladder();
    style::ThemeBlock theme{};
    theme.sun_azimuth = 180.0;
    theme.sun_elevation = 40.0;
    theme.place_latitude = static_cast<float>(place_latitude);
    theme.place_longitude = static_cast<float>(place_longitude);
    const scene::WorldBend b = bend_at(20.0, 10.0, 54'000'000.0);
    const scene::WorldLook look = scene::world_look(l, 54'000'000.0, theme, b, b.eye, 2.0, 824.0, 1830.0);
    const shaders::WorldLookBlock& k = look.block;
    CHECK(k.weights.x == 1.0F);
    CHECK(k.weights.y == 1.0F);
    CHECK(k.weights.z == 1.0F);
    CHECK(k.weights.w == 1.0F);
    // the sea: #3A86C8 at the ramp's centre, #1C5A9A 0.6 of its radius out, #0B2A55 at its end and beyond
    CHECK(hex(shaders::globe_sea(k, 0.0F)) == 0x3A86C8U);
    CHECK(hex(shaders::globe_sea(k, 0.6F * 1.5F)) == 0x1C5A9AU);
    CHECK(hex(shaders::globe_sea(k, 1.5F)) == 0x0B2A55U);
    CHECK(hex(shaders::globe_sea(k, 2.0F)) == 0x0B2A55U);
    CHECK(k.sea_at.x == -0.24F);
    CHECK(k.sea_at.y == 0.32F);
    // the land: down the disc, its top where the normal's up is 1
    CHECK(hex(shaders::globe_land(k, {0.0F, 1.0F})) == 0xC9D8B4U);
    CHECK(hex(shaders::globe_land(k, {0.0F, 1.0F - (2.0F * 0.3F)})) == 0x6E9E58U);
    CHECK(hex(shaders::globe_land(k, {0.3F, 1.0F - (2.0F * 0.55F)})) == 0x8FA35AU);
    CHECK(hex(shaders::globe_land(k, {0.0F, 1.0F - (2.0F * 0.75F)})) == 0x6E9E58U);
    CHECK(hex(shaders::globe_land(k, {0.0F, -1.0F})) == 0xE8EEF2U);
    // the night: nothing by day, then .50, and .20, .15 and .12 of what is left under each twilight's line
    const auto dark = [&](double altitude) {
        return shaders::globe_night(k, static_cast<float>(pymath::sin(pymath::radians(altitude))), 1e-6F);
    };
    CHECK(dark(10.0) == 0.0F);
    CHECK(dark(0.5) == 0.0F);
    CHECK(dark(-3.0) == doctest::Approx(0.5));
    CHECK(dark(-9.0) == doctest::Approx(1.0 - (0.5 * 0.8)));
    CHECK(dark(-15.0) == doctest::Approx(1.0 - (0.5 * 0.8 * 0.85)));
    CHECK(dark(-40.0) == doctest::Approx(1.0 - (0.5 * 0.8 * 0.85 * 0.88)));
    // an edge a degree soft is half way on the line itself
    CHECK(shaders::globe_night(k, 0.0F, static_cast<float>(pymath::sin(pymath::radians(1.0)))) ==
          doctest::Approx(0.25));
    // on the globe the edge is the pixel's (the block's softness 0), below it a degree's
    CHECK(k.sun.w == 0.0F);
    const scene::WorldLook mid = scene::world_look(l, 100'000.0, theme, b, b.eye, 2.0, 824.0, 1830.0);
    CHECK(mid.block.sun.w == doctest::Approx(pymath::sin(pymath::radians(1.0))));
    CHECK(mid.block.weights.y == 0.0F);
    // the lights: none until the sun is a degree under, whole eleven under; dimmer toward the limb; their colours
    CHECK(shaders::globe_light(k, 5.0F, 1.0F) == 0.0F);
    CHECK(shaders::globe_light(k, -1.0F, 1.0F) == 0.0F);
    CHECK(shaders::globe_light(k, -6.0F, 1.0F) == doctest::Approx(0.5));
    CHECK(shaders::globe_light(k, -11.0F, 1.0F) == 1.0F);
    CHECK(shaders::globe_light(k, -30.0F, 0.2F) == doctest::Approx(0.6));
    CHECK(hex(shaders::globe_light_colour(k, 0.9F).xyz) == 0xFFE2A0U);
    CHECK(shaders::globe_light_colour(k, 0.9F).w == 1.0F);
    CHECK(hex(shaders::globe_light_colour(k, 0.5F).xyz) == 0xFFD27AU);
    CHECK(shaders::globe_light_colour(k, 0.5F).w == doctest::Approx(0x8C / 255.0));
    CHECK(hex(shaders::globe_light_colour(k, 0.2F).xyz) == 0xFFC45CU);
    CHECK(k.light_at.x == doctest::Approx(2.6 * 2.0));
    CHECK(k.light_at.z == doctest::Approx(1.3 * 2.0));
    CHECK(k.light_at.w == doctest::Approx(2.2));
    // the planet's centre under the target, its radius; the graticule's six planes of meridians
    CHECK(k.centre.y == doctest::Approx(-(view::earth_radius + 54'000'000.0)));
    CHECK(k.centre.w == doctest::Approx(view::earth_radius));
    CHECK(k.east.w == 6.0F);
    CHECK(k.parallels.x == doctest::Approx(0.5));
    // the sun where the block's place sees it at 40° in the south (the place to a float's precision)
    CHECK(look.sun.lat == doctest::Approx(place_latitude - 50.0).epsilon(1e-6));
    CHECK(look.sun.lon == doctest::Approx(place_longitude).epsilon(1e-6));
    // the same sun over another place is overhead of another point: a named moment follows the camera's place
    theme.place_latitude = 35.68F;
    theme.place_longitude = 139.77F;
    const scene::WorldLook tokyo = scene::world_look(l, 54'000'000.0, theme, b, b.eye, 2.0, 824.0, 1830.0);
    CHECK(tokyo.sun.lat == doctest::Approx(35.68 - 50.0).epsilon(1e-6));
    CHECK(tokyo.sun.lon == doctest::Approx(139.77).epsilon(1e-6));
    CHECK(!look.night);
    CHECK(!look.plain);
}

TEST_CASE("the ladder leaves a frame plain where it cannot change a pixel: by day under the ramps, the sun up over all "
          "the eye sees") {
    const tiles::WorldLadder l = load::world_ladder();
    const auto look_at = [&](double distance, double elevation, bool night) {
        style::ThemeBlock theme{};
        theme.sun_azimuth = 180.0;
        theme.sun_elevation = elevation;
        if (night) theme.flags |= style::theme_flag::night;
        theme.place_latitude = static_cast<float>(place_latitude);
        theme.place_longitude = static_cast<float>(place_longitude);
        const scene::WorldBend b = bend_at(place_latitude, place_longitude, distance);
        return scene::world_look(l, distance, theme, b, b.eye, 2.0, 824.0, 1830.0);
    };
    // from 72 km the eye sees 8.6° about the point under it: with the sun 40° up, or 14° on a winter's noon, no cap
    // lies in view, and the block's first weight is 0 though the ladder's is not
    for (const double elevation : {40.0, 14.0}) {
        const scene::WorldLook plain = look_at(72'000.0, elevation, false);
        CHECK(plain.plain);
        CHECK(plain.weights.caps > 0.9);
        CHECK(plain.block.weights.x == 0.0F);
    }
    // the sun 5° up: the terminator's softness may reach the view's far edge
    CHECK(!look_at(72'000.0, 5.0, false).plain);
    CHECK(look_at(72'000.0, 5.0, false).block.weights.x > 0.9F);
    // a night hands its palette over, whatever the sun
    CHECK(!look_at(72'000.0, -30.0, true).plain);
    CHECK(!look_at(72'000.0, 40.0, true).plain);
    // from 300 km the eye sees 17° about: plain at 40°, not at 14°; and never where the ramps have begun
    CHECK(look_at(300'000.0, 40.0, false).plain);
    CHECK(!look_at(300'000.0, 14.0, false).plain);
    CHECK(!look_at(1'000'000.0, 80.0, false).plain);
    CHECK(look_at(1'000'000.0, 80.0, false).block.weights.x == 1.0F);
    // up to 18 km there is no look to leave out
    CHECK(!look_at(18'000.0, 40.0, false).plain);
}

TEST_CASE("a lit place's name rises off its light by what the light follows: not at all up to 18 km or by day, whole "
          "from 29 km where the sun is a degree and a half under the horizon, and smoothly between") {
    const tiles::WorldLadder l = load::world_ladder();
    // the sun overhead of (0, 0): on the equator its altitude is 90 degrees less the longitude
    const scene::GeoPoint sun{.lat = 0.0, .lon = 0.0};
    const auto night = [&](double altitude) { return scene::light_night(l, sun, 0.0, 90.0 - altitude); };
    CHECK(night(30.0) == 0.0);
    CHECK(night(-0.5) == 0.0);
    CHECK(night(-6.0) == doctest::Approx(0.5));
    CHECK(night(-11.5) == 1.0);
    CHECK(night(-70.0) == 1.0);
    // the board's rule, from the ladder: none of the light a degree under the horizon, all of it ten deeper
    CHECK(l.light_from == 1.0);
    CHECK(l.light_over == 10.0);
    const auto rise = [&](double distance, double altitude) {
        return scene::name_rise(l, scene::ladder_weights(l, distance), sun, 0.0, 90.0 - altitude);
    };
    // up to 18 km no light is drawn, whatever the night; from where the caps' weight is a quarter the name is whole
    CHECK(rise(9'000.0, -40.0) == 0.0);
    CHECK(rise(18'000.0, -40.0) == 0.0);
    CHECK(rise(22'000.0, -40.0) > 0.0);
    CHECK(rise(22'000.0, -40.0) < 1.0);
    CHECK(scene::ladder_weights(l, 28'000.0).caps < scene::name_rise_caps);
    CHECK(scene::ladder_weights(l, 29'000.0).caps > scene::name_rise_caps);
    CHECK(rise(29'000.0, -40.0) == 1.0);
    CHECK(rise(600'000.0, -40.0) == 1.0);
    CHECK(rise(12'000'000.0, -40.0) == 1.0);
    // by day none; whole where the light begins to be drawn (the least of it the world pass draws)
    CHECK(rise(600'000.0, 25.0) == 0.0);
    CHECK(rise(600'000.0, -0.9) == 0.0);
    CHECK(rise(600'000.0, -1.25) == doctest::Approx(0.5).epsilon(0.02));
    CHECK(rise(600'000.0, -1.6) == 1.0);
    for (int half = 3; half < 80; ++half) {
        const double altitude = -0.5 * half;
        if (night(altitude) >= scene::light_least_night) CHECK(rise(600'000.0, altitude - 0.001) == 1.0);
    }
    CHECK(scene::world_look(l, 600'000.0, style::ThemeBlock{}, bend_at(0.0, 0.0, 600'000.0), {0.0, 600'000.0, 0.0}, 2.0,
                            824.0, 1830.0)
              .block.light_least.y == static_cast<float>(scene::light_least_night));
    // without a step: across the terminator and through the zoom the rise moves by little, and never back
    double before = 0.0;
    for (int i = 0; i <= 100; ++i) {
        const double r = rise(600'000.0, -0.8 - (0.01 * i));
        CHECK(r >= before);
        CHECK(r - before < 0.04);
        before = r;
    }
    CHECK(before == 1.0);
    before = 0.0;
    for (int i = 0; i <= 120; ++i) {
        const double r = rise(18'000.0 + (100.0 * i), -40.0);
        CHECK(r >= before);
        CHECK(r - before < 0.04);
        before = r;
    }
    CHECK(before == 1.0);
    // the light's halo by its place's people, dp: the ladder's radii times its halo
    CHECK(scene::light_halo_dp(l, 12e6) == doctest::Approx(2.6 * 2.2));
    CHECK(scene::light_halo_dp(l, 5e6) == doctest::Approx(1.9 * 2.2));
    CHECK(scene::light_halo_dp(l, 2e5) == doctest::Approx(1.3 * 2.2));
    CHECK(scene::light_halo_dp(l, 0.0) == doctest::Approx(1.3 * 2.2));
}

TEST_CASE("the haze's stops over the bent ground run from its own horizon's row by its own depth, off the space over "
          "it; where nothing bends they are the flat ground's") {
    const tiles::WorldLadder l = load::world_ladder();
    constexpr double width = 412.0;
    constexpr double height = 916.0;
    constexpr double haze_max = 0.3;
    const auto camera = [&](double pitch, double distance) {
        return view::Camera({.target = {0, 0, 0},
                             .yaw = 20.0,
                             .pitch = pitch,
                             .dist = distance,
                             .width = width,
                             .height = height,
                             .cy = height * 0.48});
    };
    style::ThemeBlock theme{};
    theme.haze_max = haze_max;
    // nothing bends: the flat ground's 17 stops (view::haze_of's), their rows and their opacities
    for (const double pitch : {15.0, 40.0, 90.0}) {
        const view::Camera cam = camera(pitch, 10'000.0);
        const view::LayerSettings settings{
            .width = width, .height = height, .pitch = pitch, .haze0 = 8'500.0, .haze1 = 22'000.0};
        const view::Haze flat = view::layers_of(cam, theme, settings).haze;
        const view::Haze bent = scene::bent_haze(cam, 0.0, 0.0, haze_max, 8'500.0, 22'000.0, height, 0.0);
        ATLAS_REQUIRE(flat.count == 17);
        ATLAS_REQUIRE(bent.count == 17);
        CHECK(std::abs(bent.y0 - flat.y0) < 1.0);
        CHECK(bent.y1 == flat.y1);
        for (std::size_t i = 0; i < 17; ++i) {
            CHECK(bent.offsets.at(i) == flat.offsets.at(i));
            CHECK(std::abs(bent.alphas.at(i) - flat.alphas.at(i)) < 0.01);
        }
    }
    // from 1,000 km, the view 25 degrees under the horizontal: the world is the sphere, and its horizon lies under
    // the flat ground's (which stands above the frame's top here) by the dip of the eye's height over it
    {
        const double distance = 1'000'000.0;
        const view::Camera cam = camera(25.0, distance);
        const double flat_row = cam.horizon_y();
        const double down = pymath::atan((cam.cy() - flat_row) / cam.focal()); // the view under the horizontal
        const view::Vec3& eye = cam.eye();
        const double from_centre = pymath::sqrt(
            (eye.x * eye.x) + ((eye.y + view::earth_radius) * (eye.y + view::earth_radius)) + (eye.z * eye.z));
        // the horizon seen from the eye lies acos(R / d) under the eye's own horizontal, which the eye's place behind
        // the target tips up from the frame's by the arc between them
        const double behind = pymath::sqrt((eye.x * eye.x) + (eye.z * eye.z));
        const double dip =
            pymath::acos(view::earth_radius / from_centre) - pymath::atan2(behind, eye.y + view::earth_radius);
        const double limb_row = cam.cy() - (cam.focal() * pymath::tan(down - dip));
        const scene::LadderWeights w = scene::ladder_weights(l, distance);
        CHECK(view::sphere_weight(distance) == 1.0);
        CHECK(w.space > 0.0);
        const view::Haze h = scene::bent_haze(cam, view::drop_of(distance), 1.0, haze_max, l.haze_start * distance,
                                              l.haze_reach * distance, height, w.space);
        MESSAGE(std::format("from 1,000 km at 25 degrees: the flat horizon's row {:.1f}, the sphere's {:.1f}, the "
                            "haze's first row {:.1f}; {} stops, over space {:.3f}, at the horizon {:.3f}, at the foot "
                            "{:.3f}",
                            flat_row, limb_row, h.y0, h.count, h.alphas[0], h.alphas[2], h.alphas.at(h.count - 1)));
        CHECK(limb_row > flat_row + 20.0);
        CHECK(std::abs(h.y0 - limb_row) < 1.5);
        CHECK(h.y1 == height);
        // over space what the ladder keeps of the haze there; the horizon's own a pixel and a half under it
        ATLAS_REQUIRE(h.count == 19);
        CHECK(h.offsets[0] == 0.0);
        CHECK(h.alphas[0] == doctest::Approx(h.alphas[2] * (1.0 - w.space)));
        CHECK(h.alphas[1] == h.alphas[2]);
        CHECK(h.offsets[1] == doctest::Approx(1.5 / (height - h.y0)));
        // down the frame the ground is nearer and the haze thinner, never thicker; the stops' rows ascend
        for (std::size_t i = 2; i + 1 < h.count; ++i) {
            CHECK(h.alphas.at(i + 1) <= h.alphas.at(i) + 1e-12);
            CHECK(h.offsets.at(i + 1) >= h.offsets.at(i));
        }
        // (the horizon is 2,500 km from the eye, within the haze's reach of 3,050: most of the haze, not all)
        CHECK(h.alphas[2] > 0.5 * haze_max);
        CHECK(h.alphas[2] <= haze_max);
        CHECK(h.alphas.at(h.count - 1) < h.alphas[2]);
        CHECK(h.offsets.at(h.count - 1) == 1.0);
    }
    // straight down the horizon is above the frame: the 17 stops from its top, none for space
    {
        const double distance = 100'000.0;
        const view::Camera cam = camera(90.0, distance);
        const view::Haze h = scene::bent_haze(cam, view::drop_of(distance), view::sphere_weight(distance), haze_max,
                                              l.haze_start * distance, l.haze_reach * distance, height, 0.2);
        CHECK(h.count == 17);
        CHECK(h.y0 == 0.0);
        CHECK(h.y1 == height);
        for (std::size_t i = 0; i < 17; ++i) CHECK(h.alphas.at(i) < 0.05);
    }
}
