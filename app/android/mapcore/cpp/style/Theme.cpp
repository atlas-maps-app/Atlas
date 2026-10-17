// The theme block's reader, the shaders' Theme made from it, and its dump for the parity tests.
#include "style/Theme.hpp"

#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <format>
#include <iterator>

namespace atlas::style {

static_assert(std::endian::native == std::endian::little,
              "the blocks are little-endian, as every Android ABI and host is");

namespace {

/// lo <= v <= hi (false for NaN).
[[nodiscard]] constexpr bool within(double v, double lo, double hi) noexcept { return v >= lo && v <= hi; }

/// v in [lo, hi] where the theme has the value, and exactly `without` where it has none.
[[nodiscard]] constexpr bool value_or(bool has, double v, double lo, double hi, double without) noexcept {
    return has ? within(v, lo, hi) : v == without;
}

[[nodiscard]] constexpr bool leap(int year) noexcept { return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0; }

/// Whether year-month-day hh:mm is a real date and time of day, in the years the block may name.
[[nodiscard]] constexpr bool real_date(int year, int month, int day, int hour, int minute) noexcept {
    constexpr std::array<int, 12> days = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    if (year < 1900 || year > 2200 || month < 1 || month > 12 || hour > 23 || minute > 59) {
        return false;
    }
    const int last = month == 2 && leap(year) ? 29 : days[static_cast<std::size_t>(month - 1)];
    return day >= 1 && day <= last;
}

/// A colour is either present (a 255) or absent and all zero.
[[nodiscard]] constexpr bool well_formed(Rgb8 c) noexcept {
    return c.a == 255U || (c.a == 0U && c.r == 0U && c.g == 0U && c.b == 0U);
}

[[nodiscard]] std::expected<void, ThemeError> check_colours(const ThemeBlock& b) noexcept {
    const std::array all = {b.sky_top, b.sky_horizon, b.haze,      b.sun,          b.ambient, b.ground,
                            b.road,    b.water,       b.water_top, b.water_bottom, b.shadow};
    for (const Rgb8 c : all) {
        if (!well_formed(c)) {
            return std::unexpected(ThemeError::reserved);
        }
    }
    const std::array required = {b.sky_top, b.sky_horizon, b.haze,  b.sun,   b.ambient,
                                 b.ground,  b.road,        b.water, b.shadow};
    for (const Rgb8 c : required) {
        if (c.a != 255U) {
            return std::unexpected(ThemeError::colour);
        }
    }
    const bool gradient = b.has(theme_flag::water_gradient);
    if ((b.water_top.a == 255U) != gradient || (b.water_bottom.a == 255U) != gradient) {
        return std::unexpected(ThemeError::colour);
    }
    return {};
}

[[nodiscard]] bool values_in_range(const ThemeBlock& b) noexcept {
    return within(b.haze_max, 0.0, 1.0) && within(b.sun_k, 0.0, 1.2) && within(b.amb_k, 0.0, 1.0) &&
           value_or(b.has(theme_flag::overcast), b.sky_k, 0.0, 1.7, 1.0) && within(b.shadow_opacity, 0.0, 1.0) &&
           value_or(b.has(theme_flag::shadow_blur), b.shadow_blur, 0.0, 64.0, 0.0) &&
           value_or(b.has(theme_flag::cloud_shadows), b.cloud_shadows, 0.0, 1.0, 0.0) &&
           value_or(b.has(theme_flag::dusk), b.dusk, 0.0, 1.0, 0.0);
}

/// The sun as the render takes it: L = sun_vector(az, el), a unit vector, when the sun is above the horizon, else
/// straight down (amsterdam.py:328 and :623).
[[nodiscard]] bool sun_in_range(const ThemeBlock& b) noexcept {
    const auto& l = b.sun_vector;
    const double square = (l[0] * l[0]) + (l[1] * l[1]) + (l[2] * l[2]);
    if (!within(b.sun_azimuth, 0.0, 360.0) || !within(b.sun_elevation, -90.0, 90.0) ||
        !within(square, 1.0 - 1e-12, 1.0 + 1e-12)) {
        return false;
    }
    if (b.sun_elevation > 0.0) {
        return l[1] > 0.0;
    }
    return l[0] == 0.0 && l[1] == -1.0 && l[2] == 0.0;
}

[[nodiscard]] bool weather_in_range(const ThemeBlock& b) noexcept {
    const std::array wx = {b.cloud,          b.cloud_low,   b.rain,     b.wind,       b.gust,
                           b.wind_direction, b.temperature, b.pressure, b.visibility, b.humidity};
    if (!b.has(theme_flag::weather)) {
        for (const double v : wx) {
            if (v != 0.0) {
                return false;
            }
        }
        return b.weather_code == 255U;
    }
    return within(b.cloud, 0.0, 1.0) && within(b.cloud_low, 0.0, 1.0) && within(b.rain, 0.0, 500.0) &&
           within(b.wind, 0.0, 500.0) && within(b.gust, 0.0, 500.0) && within(b.wind_direction, 0.0, 360.0) &&
           within(b.temperature, -100.0, 70.0) && within(b.pressure, 800.0, 1200.0) && within(b.visibility, 0.0, 1e6) &&
           within(b.humidity, 0.0, 100.0) && (b.weather_code <= 99U || b.weather_code == 255U);
}

[[nodiscard]] shaders::float4 colour(Rgb8 c, float a) noexcept {
    const shaders::float4 v = {static_cast<float>(c.r), static_cast<float>(c.g), static_cast<float>(c.b), a};
    return v;
}

[[nodiscard]] float f(double v) noexcept { return static_cast<float>(v); }

/// Two doubles as the four words of a bits_ lane: low word, high word, low word, high word.
[[nodiscard]] shaders::uint4 words(double a, double b) noexcept {
    const auto x = std::bit_cast<std::uint64_t>(a);
    const auto y = std::bit_cast<std::uint64_t>(b);
    const shaders::uint4 v = {static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(x >> 32U),
                              static_cast<std::uint32_t>(y), static_cast<std::uint32_t>(y >> 32U)};
    return v;
}

} // namespace

std::expected<ThemeBlock, ThemeError> read_theme_block(std::span<const std::byte> bytes) noexcept {
    if (bytes.size() != theme_block_size) {
        return std::unexpected(ThemeError::size);
    }
    ThemeBlock b{};
    std::memcpy(&b, bytes.data(), sizeof b);
    if (b.version != theme_block_version) {
        return std::unexpected(ThemeError::version);
    }
    if ((b.flags & ~theme_flag::known) != 0U || (b.has(theme_flag::wet) && !b.has(theme_flag::wet_set))) {
        return std::unexpected(ThemeError::flags);
    }
    if (b.reserved0 != 0U || b.reserved1 != 0U) {
        return std::unexpected(ThemeError::reserved);
    }
    if (!real_date(b.year, b.month, b.day, b.hour, b.minute)) {
        return std::unexpected(ThemeError::date);
    }
    if (static_cast<std::uint8_t>(b.moment) > static_cast<std::uint8_t>(MomentName::truth)) {
        return std::unexpected(ThemeError::moment);
    }
    const std::array doubles = {b.haze_max,      b.sun_k,         b.amb_k,         b.sky_k,          b.shadow_opacity,
                                b.shadow_blur,   b.cloud_shadows, b.dusk,          b.sun_vector[0],  b.sun_vector[1],
                                b.sun_vector[2], b.sun_azimuth,   b.sun_elevation, b.cloud,          b.cloud_low,
                                b.rain,          b.wind,          b.gust,          b.wind_direction, b.temperature,
                                b.pressure,      b.visibility,    b.humidity};
    for (const double v : doubles) {
        if (!std::isfinite(v)) {
            return std::unexpected(ThemeError::not_finite);
        }
    }
    if (auto colours = check_colours(b); !colours) {
        return std::unexpected(colours.error());
    }
    if (!values_in_range(b)) {
        return std::unexpected(ThemeError::value);
    }
    if (!sun_in_range(b)) {
        return std::unexpected(ThemeError::sun);
    }
    if (!weather_in_range(b)) {
        return std::unexpected(ThemeError::weather);
    }
    return b;
}

shaders::Theme shader_theme(const ThemeBlock& b) noexcept {
    shaders::Theme t{};
    t.ambient = colour(b.ambient, f(b.amb_k));
    t.sun = colour(b.sun, f(b.sun_k));
    const shaders::float4 light = {f(b.sun_vector[0]), f(b.sun_vector[1]), f(b.sun_vector[2]), f(b.sky_k)};
    t.light = light;
    t.haze = colour(b.haze, f(b.haze_max));
    t.sky_top = colour(b.sky_top, 0.0F);
    t.sky_horizon = colour(b.sky_horizon, 0.0F);
    t.ground = colour(b.ground, 0.0F);
    t.road = colour(b.road, 0.0F);
    t.water = colour(b.water, 0.0F);
    t.water_top = colour(b.water_top, 0.0F);
    t.water_bottom = colour(b.water_bottom, 0.0F);
    t.shadow = colour(b.shadow, f(b.shadow_opacity));
    const shaders::float4 weather = {f(b.shadow_blur), f(b.cloud_shadows), f(b.dusk), f(b.cloud)};
    t.weather = weather;
    const shaders::float4 wind = {f(b.wind), f(b.wind_direction), f(b.rain), f(b.visibility)};
    t.wind = wind;
    const shaders::uint4 flags = {b.flags, 0U, 0U, 0U};
    t.flags = flags;
    // sky_k is 1 in a block without overcast (the reader holds it to that), as th.get('sky_k', 1.0) reads it
    t.bits_amb_sun = words(b.amb_k, b.sun_k);
    t.bits_sky_lx = words(b.sky_k, b.sun_vector[0]);
    t.bits_lyz = words(b.sun_vector[1], b.sun_vector[2]);
    return t;
}

std::string dump(const ThemeBlock& b) {
    std::string out;
    out.reserve(2048);
    auto line = [&out](std::string_view name, auto value) {
        std::format_to(std::back_inserter(out), "{}={}\n", name, value);
    };
    auto bits = [&out](std::string_view name, double value) {
        std::format_to(std::back_inserter(out), "{}={:016x}\n", name, std::bit_cast<std::uint64_t>(value));
    };
    auto rgb = [&out](std::string_view name, Rgb8 c) {
        if (c.a == 0) {
            std::format_to(std::back_inserter(out), "{}=-\n", name);
        } else {
            std::format_to(std::back_inserter(out), "{}=#{:02x}{:02x}{:02x}\n", name, c.r, c.g, c.b);
        }
    };
    line("version", b.version);
    line("flags", b.flags);
    line("year", b.year);
    line("month", static_cast<unsigned>(b.month));
    line("day", static_cast<unsigned>(b.day));
    line("hour", static_cast<unsigned>(b.hour));
    line("minute", static_cast<unsigned>(b.minute));
    line("moment", static_cast<unsigned>(b.moment));
    line("code", static_cast<unsigned>(b.weather_code));
    rgb("sky_top", b.sky_top);
    rgb("sky_hor", b.sky_horizon);
    rgb("haze", b.haze);
    rgb("sun", b.sun);
    rgb("amb", b.ambient);
    rgb("ground", b.ground);
    rgb("road", b.road);
    rgb("water", b.water);
    rgb("water_top", b.water_top);
    rgb("water_bot", b.water_bottom);
    rgb("shadow", b.shadow);
    bits("haze_max", b.haze_max);
    bits("sun_k", b.sun_k);
    bits("amb_k", b.amb_k);
    bits("sky_k", b.sky_k);
    bits("shadow_op", b.shadow_opacity);
    bits("shadow_blur", b.shadow_blur);
    bits("cloud_shadows", b.cloud_shadows);
    bits("_dusk", b.dusk);
    bits("L0", b.sun_vector[0]);
    bits("L1", b.sun_vector[1]);
    bits("L2", b.sun_vector[2]);
    bits("az", b.sun_azimuth);
    bits("el", b.sun_elevation);
    bits("cloud", b.cloud);
    bits("low", b.cloud_low);
    bits("rain", b.rain);
    bits("wind", b.wind);
    bits("gust", b.gust);
    bits("wind_dir", b.wind_direction);
    bits("temp", b.temperature);
    bits("pressure", b.pressure);
    bits("vis", b.visibility);
    bits("hum", b.humidity);
    return out;
}

const char* name_of(ThemeError error) noexcept {
    switch (error) {
    case ThemeError::size: return "size";
    case ThemeError::version: return "version";
    case ThemeError::flags: return "flags";
    case ThemeError::reserved: return "reserved";
    case ThemeError::date: return "date";
    case ThemeError::moment: return "moment";
    case ThemeError::not_finite: return "not_finite";
    case ThemeError::colour: return "colour";
    case ThemeError::value: return "value";
    case ThemeError::sun: return "sun";
    case ThemeError::weather: return "weather";
    }
    return "unknown";
}

} // namespace atlas::style
