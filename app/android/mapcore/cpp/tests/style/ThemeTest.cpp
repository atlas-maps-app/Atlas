// The theme block's reader, the shaders' Theme made from it and its dump (style/Theme.hpp).
#include "style/Theme.hpp"

#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <functional>
#include <limits>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using atlas::style::MomentName;
using atlas::style::ThemeBlock;
using atlas::style::ThemeError;
namespace flag = atlas::style::theme_flag;

/// amsterdam.py's sunny moment of 1 October 2026, 16:00, as Kotlin writes it (values from the port's dict).
ThemeBlock sunny() {
    ThemeBlock b{};
    b.version = 1;
    b.flags = flag::weather | flag::shadow_blur | flag::cloud_shadows | flag::water_gradient | flag::wet_set;
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 16;
    b.minute = 0;
    b.moment = MomentName::sunny;
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
    b.rain = 0.0;
    b.wind = 14.0;
    b.gust = 27.0;
    b.wind_direction = 225.0;
    b.temperature = 14.2;
    b.pressure = 1012.5;
    b.visibility = 24000.0;
    b.humidity = 72.0;
    return b;
}

/// amsterdam.py's fixed night moment: no weather, no water gradient, the sun straight down.
ThemeBlock night() {
    ThemeBlock b = sunny();
    b.flags = flag::night;
    b.moment = MomentName::night;
    b.weather_code = 255;
    b.water_top = {};
    b.water_bottom = {};
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

std::array<std::byte, 256> bytes(const ThemeBlock& b) {
    std::array<std::byte, 256> out{};
    std::memcpy(out.data(), &b, sizeof b);
    return out;
}

/// Why the reader refuses `b` (when it accepts it, the test case fails and the answer is ThemeError{}).
ThemeError refusal(const ThemeBlock& b) {
    auto raw = bytes(b);
    auto r = atlas::style::read_theme_block(raw);
    ATLAS_REQUIRE_OR_RETURN(ThemeError{}, !r.has_value(), "the reader accepted it");
    return r.error();
}

bool accepted(const ThemeBlock& b) {
    auto raw = bytes(b);
    return atlas::style::read_theme_block(raw).has_value();
}

/// One change to a good block, and the refusal it must meet.
struct Breakage {
    const char* what;
    std::function<void(ThemeBlock&)> apply;
    ThemeError error;
};

} // namespace

TEST_CASE("a theme block reads back field by field") {
    for (const ThemeBlock& want : {sunny(), night()}) {
        const auto raw = bytes(want);
        const auto got = atlas::style::read_theme_block(raw);
        ATLAS_REQUIRE(got.has_value(), atlas::style::name_of(got.error()));
        CHECK(bytes(*got) == raw);
    }
    const auto raw = bytes(sunny());
    const auto got = atlas::style::read_theme_block(raw);
    ATLAS_REQUIRE(got.has_value(), atlas::style::name_of(got.error()));
    CHECK(got->has(flag::weather));
    CHECK_FALSE(got->has(flag::night));
}

TEST_CASE("a theme block the port could not have made is refused, with the reason") {
    std::array<std::byte, 255> short_block{};
    CHECK(atlas::style::read_theme_block(short_block).error() == ThemeError::size);
    std::array<std::byte, 257> long_block{};
    CHECK(atlas::style::read_theme_block(long_block).error() == ThemeError::size);
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    const std::vector<Breakage> breakages = {
        {"version 2", [](ThemeBlock& b) { b.version = 2; }, ThemeError::version},
        {"an unknown flag", [](ThemeBlock& b) { b.flags |= 1U << 11U; }, ThemeError::flags},
        {"wet without wet_set", [](ThemeBlock& b) { b.flags = (b.flags | flag::wet) & ~flag::wet_set; },
         ThemeError::flags},
        {"reserved0", [](ThemeBlock& b) { b.reserved0 = 1; }, ThemeError::reserved},
        {"reserved1", [](ThemeBlock& b) { b.reserved1 = 1; }, ThemeError::reserved},
        {"month 0", [](ThemeBlock& b) { b.month = 0; }, ThemeError::date},
        {"month 13", [](ThemeBlock& b) { b.month = 13; }, ThemeError::date},
        {"day 0", [](ThemeBlock& b) { b.day = 0; }, ThemeError::date},
        {"31 September",
         [](ThemeBlock& b) {
             b.month = 9;
             b.day = 31;
         },
         ThemeError::date},
        {"29 February 2026",
         [](ThemeBlock& b) {
             b.month = 2;
             b.day = 29;
         },
         ThemeError::date},
        {"hour 24", [](ThemeBlock& b) { b.hour = 24; }, ThemeError::date},
        {"minute 60", [](ThemeBlock& b) { b.minute = 60; }, ThemeError::date},
        {"the year 1800", [](ThemeBlock& b) { b.year = 1800; }, ThemeError::date},
        {"moment 8", [](ThemeBlock& b) { b.moment = static_cast<MomentName>(8); }, ThemeError::moment},
        {"a NaN sun vector", [nan](ThemeBlock& b) { b.sun_vector[1] = nan; }, ThemeError::not_finite},
        {"an infinite humidity", [inf](ThemeBlock& b) { b.humidity = inf; }, ThemeError::not_finite},
        {"no ambient", [](ThemeBlock& b) { b.ambient = {}; }, ThemeError::colour},
        {"no sun colour", [](ThemeBlock& b) { b.sun = {}; }, ThemeError::colour},
        {"a colour half there", [](ThemeBlock& b) { b.ground.a = 128; }, ThemeError::reserved},
        {"an absent colour with bytes", [](ThemeBlock& b) { b.water_top = {.r = 1, .g = 0, .b = 0, .a = 0}; },
         ThemeError::reserved},
        {"water_top without its pair", [](ThemeBlock& b) { b.water_bottom = {}; }, ThemeError::colour},
        {"a gradient without the flag", [](ThemeBlock& b) { b.flags &= ~flag::water_gradient; }, ThemeError::colour},
        {"haze_max 5", [](ThemeBlock& b) { b.haze_max = 5.0; }, ThemeError::value},
        {"amb_k -3", [](ThemeBlock& b) { b.amb_k = -3.0; }, ThemeError::value},
        {"sun_k 1e30", [](ThemeBlock& b) { b.sun_k = 1e30; }, ThemeError::value},
        {"sun_k 1.5", [](ThemeBlock& b) { b.sun_k = 1.5; }, ThemeError::value},
        {"amb_k 1.1", [](ThemeBlock& b) { b.amb_k = 1.1; }, ThemeError::value},
        {"sky_k 2 with overcast",
         [](ThemeBlock& b) {
             b.flags |= flag::overcast;
             b.sky_k = 2.0;
         },
         ThemeError::value},
        {"shadow_op -1", [](ThemeBlock& b) { b.shadow_opacity = -1.0; }, ThemeError::value},
        {"sky_k 1.7 without overcast", [](ThemeBlock& b) { b.sky_k = 1.7; }, ThemeError::value},
        {"shadow_blur without its flag", [](ThemeBlock& b) { b.flags &= ~flag::shadow_blur; }, ThemeError::value},
        {"cloud_shadows 2", [](ThemeBlock& b) { b.cloud_shadows = 2.0; }, ThemeError::value},
        {"_dusk without its flag", [](ThemeBlock& b) { b.dusk = 0.3; }, ThemeError::value},
        {"a zero sun vector", [](ThemeBlock& b) { b.sun_vector = {0.0, 0.0, 0.0}; }, ThemeError::sun},
        {"a sun vector of length 2", [](ThemeBlock& b) { b.sun_vector[1] *= 2.0; }, ThemeError::sun},
        {"a sun under the horizon with the sun up", [](ThemeBlock& b) { b.sun_vector[1] = -b.sun_vector[1]; },
         ThemeError::sun},
        {"the sun up and its vector straight down", [](ThemeBlock& b) { b.sun_vector = {0.0, -1.0, 0.0}; },
         ThemeError::sun},
        {"azimuth 400", [](ThemeBlock& b) { b.sun_azimuth = 400.0; }, ThemeError::sun},
        {"elevation 120", [](ThemeBlock& b) { b.sun_elevation = 120.0; }, ThemeError::sun},
        {"cloud cover 1.5", [](ThemeBlock& b) { b.cloud = 1.5; }, ThemeError::weather},
        {"rain -1", [](ThemeBlock& b) { b.rain = -1.0; }, ThemeError::weather},
        {"pressure 0", [](ThemeBlock& b) { b.pressure = 0.0; }, ThemeError::weather},
        {"humidity 101", [](ThemeBlock& b) { b.humidity = 101.0; }, ThemeError::weather},
        {"WMO code 100", [](ThemeBlock& b) { b.weather_code = 100; }, ThemeError::weather},
        {"weather without the flag", [](ThemeBlock& b) { b.flags &= ~flag::weather; }, ThemeError::weather},
    };
    for (const Breakage& k : breakages) {
        ThemeBlock b = sunny();
        k.apply(b);
        // refusal() asserts, so no message may call it: doctest writes a message while it holds its lock
        INFO(std::string_view(k.what));
        const ThemeError error = refusal(b);
        CHECK_MESSAGE(error == k.error, std::string_view(atlas::style::name_of(error)));
    }
    // at night: no weather, so any weather value is refused, and the sun must point straight down
    ThemeBlock b = night();
    CHECK(accepted(b));
    b.wind = 3.0;
    CHECK(refusal(b) == ThemeError::weather);
    b = night();
    b.weather_code = 3;
    CHECK(refusal(b) == ThemeError::weather);
    b = night();
    b.sun_vector = {0.0, 1.0, 0.0};
    CHECK(refusal(b) == ThemeError::sun);
    // what the port can make is accepted: 29 February in a leap year, an azimuth of 360, overcast's sky_k, no colour
    b = sunny();
    b.year = 2028;
    b.month = 2;
    b.day = 29;
    b.sun_azimuth = 360.0;
    b.flags |= flag::overcast | flag::dusk | flag::wet | flag::tone | flag::golden;
    b.sky_k = 1.7 * (1 - (0.55 * 0.3));
    b.dusk = 0.3;
    CHECK(accepted(b));
}

TEST_CASE("the shaders' theme carries the block's colours, values, flags and the exact light's bits") {
    ThemeBlock b = sunny();
    const atlas::shaders::Theme t = atlas::style::shader_theme(b);
    CHECK(t.ambient.x == 0x93);
    CHECK(t.ambient.y == 0xA6);
    CHECK(t.ambient.z == 0xCC);
    CHECK(t.ambient.w == static_cast<float>(0.6));
    CHECK(t.sun.w == 1.0F);
    CHECK(t.light.x == static_cast<float>(b.sun_vector[0]));
    CHECK(t.light.w == 1.0F); // no sky_k: the light's sky term is unscaled
    CHECK(t.haze.w == static_cast<float>(0.22 * 0.85));
    CHECK(t.shadow.w == static_cast<float>(0.32));
    CHECK(t.weather.x == static_cast<float>(0.6));
    CHECK(t.wind.y == 225.0F);
    CHECK(t.flags.x == b.flags);
    CHECK(atlas::shaders::theme_has(t, atlas::shaders::THEME_WEATHER));
    // the doubles the exact light reads, bit for bit
    CHECK(std::bit_cast<std::uint64_t>(atlas::shaders::theme_amb_k(t)) == std::bit_cast<std::uint64_t>(b.amb_k));
    CHECK(std::bit_cast<std::uint64_t>(atlas::shaders::theme_sun_k(t)) == std::bit_cast<std::uint64_t>(b.sun_k));
    CHECK(std::bit_cast<std::uint64_t>(atlas::shaders::theme_sky_k(t)) == std::bit_cast<std::uint64_t>(1.0));
    const atlas::shaders::double3 l = atlas::shaders::theme_sun_vector(t);
    CHECK(std::bit_cast<std::uint64_t>(l.x) == std::bit_cast<std::uint64_t>(b.sun_vector[0]));
    CHECK(std::bit_cast<std::uint64_t>(l.y) == std::bit_cast<std::uint64_t>(b.sun_vector[1]));
    CHECK(std::bit_cast<std::uint64_t>(l.z) == std::bit_cast<std::uint64_t>(b.sun_vector[2]));
    // overcast: sky_k scales the sky's light
    b.flags |= flag::overcast;
    b.sky_k = 1.7;
    CHECK(atlas::style::shader_theme(b).light.w == static_cast<float>(1.7));
    CHECK(atlas::shaders::theme_sky_k(atlas::style::shader_theme(b)) == 1.7);
}

TEST_CASE("the dump names every field, colours by hex and doubles by their bits") {
    const ThemeBlock b = night();
    const std::string d = atlas::style::dump(b);
    CHECK(d.find("amb=#93a6cc\n") != std::string::npos);
    CHECK(d.find("water_top=-\n") != std::string::npos);
    CHECK(d.find("year=2026\n") != std::string::npos);
    CHECK(d.find("sky_k=3ff0000000000000\n") != std::string::npos);
    CHECK(d.find("hum=0000000000000000\n") != std::string::npos);
}
