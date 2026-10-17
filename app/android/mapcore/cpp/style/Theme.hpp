// The moment's theme in the core (plan-vector D14, Appendix D): the 256-byte theme block Kotlin writes from the port's
// weather_theme about once a minute (MK/state/ThemeBlock.kt, through MapCore.moment), read and checked here, and the
// shaders' Theme made from it.
//
// The block, version 1, little-endian; every value is the port's own, the doubles bit for bit:
//
//     0  u32  version (1)                     64  f64  haze_max, sun_k, amb_k, sky_k (1 without),
//     4  u32  flags (theme_flag)                       shadow_op, shadow_blur, cloud_shadows, _dusk (0 without)
//     8  i16  year     10 u8 month  11 u8 day  128  f64  the sun vector _L, x y z
//    12  u8   hour     13 u8 minute            152  f64  the sun's azimuth and elevation (degrees)
//    14  u8   the moment's name (MomentName)  168  f64  the hour's weather (th['wx']): cloud, low, rain, wind, gust,
//    15  u8   the hour's WMO code, 255 without           wind_dir, temp, pressure, vis, hum (0 without)
//    16  rgba sky_top, sky_hor, haze, sun, amb, 248  u64  reserved (0)
//             ground, road, water, water_top,
//             water_bot, shadow (a: 255 where the
//             theme has the colour, else 0)
//    60  u32  reserved (0)
//
// The reader accepts a block only as the port makes one:
// - a real date in the years 1900-2200 and a time of day; a moment name it knows;
// - the colours every theme has (all but water_top and water_bot, which come as a pair, with water_gradient); an
//   absent colour all zero;
// - haze_max, amb_k and shadow_op in 0-1, sun_k in 0-1.2, sky_k in 0-1.7 with overcast and exactly 1 without (the
//   ranges light_f32's bound holds for, style/Light.hpp; the port's own values reach 1.0, 0.84 and 1.7); shadow_blur
//   in 0-64, cloud_shadows and _dusk in 0-1 with their flags and 0 without; wet only where wet is set;
// - a unit sun vector (within 10^-12 in its square), above the horizon when the sun's elevation is, straight down
//   when it is not; an azimuth in 0-360 and an elevation within ±90 degrees;
// - with weather, cloud and low in 0-1, rain up to 500 mm, wind and gust up to 500 km/h, a direction in 0-360, a
//   temperature of -100 to 70 °C, a pressure of 800-1200 hPa, a visibility up to 10^6 m, a humidity of 0-100 % and a
//   WMO code up to 99 (or 255, none); without weather, all 0 and the code 255.
#pragma once

#include "style/Light.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <string>

namespace atlas::style {

/// The theme block's layout version (ThemeBlock.VERSION in Kotlin).
inline constexpr std::uint32_t theme_block_version = 1;

/// The theme block's size in bytes (ThemeBlock.SIZE in Kotlin).
inline constexpr std::size_t theme_block_size = 256;

/// The theme block's flag bits (ThemeBlock.NIGHT ... TONE): theme.slang's THEME_ bits, which the shaders read
/// unchanged.
namespace theme_flag {
inline constexpr std::uint32_t night = shaders::THEME_NIGHT;
inline constexpr std::uint32_t wet = shaders::THEME_WET;
inline constexpr std::uint32_t weather = shaders::THEME_WEATHER;
inline constexpr std::uint32_t overcast = shaders::THEME_OVERCAST;
inline constexpr std::uint32_t shadow_blur = shaders::THEME_SHADOW_BLUR;
inline constexpr std::uint32_t cloud_shadows = shaders::THEME_CLOUD_SHADOWS;
inline constexpr std::uint32_t water_gradient = shaders::THEME_WATER_GRADIENT;
inline constexpr std::uint32_t dusk = shaders::THEME_DUSK;
inline constexpr std::uint32_t wet_set = shaders::THEME_WET_SET;
inline constexpr std::uint32_t golden = shaders::THEME_GOLDEN;
inline constexpr std::uint32_t tone = shaders::THEME_TONE;
/// Every bit version 1 knows; a block with another is refused.
inline constexpr std::uint32_t known = 0x7FFU;
} // namespace theme_flag

/// The moment's name (th['name']) as a number (ThemeBlock.MOMENTS); a region's own moments and the live moment's other
/// names are `other`.
enum class MomentName : std::uint8_t {
    other = 0,
    sunny = 1,
    grey = 2,
    wet = 3,
    night = 4,
    golden = 5,
    noon = 6,
    truth = 7
};

/// A colour of the theme as the port's hx reads its hex (CSS's #rgb as #rrggbb); a is 255 where the theme has the
/// colour, 0 where it has none.
struct Rgb8 {
    std::uint8_t r;
    std::uint8_t g;
    std::uint8_t b;
    std::uint8_t a;
};

/// The theme block, version 1, as Kotlin writes it (the layout above).
struct ThemeBlock {
    std::uint32_t version;
    std::uint32_t flags;
    std::int16_t year;
    std::uint8_t month;
    std::uint8_t day;
    std::uint8_t hour;
    std::uint8_t minute;
    MomentName moment;
    std::uint8_t weather_code;
    Rgb8 sky_top;
    Rgb8 sky_horizon;
    Rgb8 haze;
    Rgb8 sun;
    Rgb8 ambient;
    Rgb8 ground;
    Rgb8 road;
    Rgb8 water;
    Rgb8 water_top;
    Rgb8 water_bottom;
    Rgb8 shadow;
    std::uint32_t reserved0;
    double haze_max;
    double sun_k;
    double amb_k;
    double sky_k;
    double shadow_opacity;
    double shadow_blur;
    double cloud_shadows;
    double dusk;
    std::array<double, 3> sun_vector;
    double sun_azimuth;
    double sun_elevation;
    double cloud;
    double cloud_low;
    double rain;
    double wind;
    double gust;
    double wind_direction;
    double temperature;
    double pressure;
    double visibility;
    double humidity;
    std::uint64_t reserved1;

    [[nodiscard]] constexpr bool has(std::uint32_t flag) const noexcept { return (flags & flag) != 0U; }
};

static_assert(sizeof(ThemeBlock) == theme_block_size);
static_assert(offsetof(ThemeBlock, moment) == 14 && offsetof(ThemeBlock, weather_code) == 15);
static_assert(offsetof(ThemeBlock, sky_top) == 16 && offsetof(ThemeBlock, shadow) == 56 &&
              offsetof(ThemeBlock, reserved0) == 60);
static_assert(offsetof(ThemeBlock, haze_max) == 64 && offsetof(ThemeBlock, dusk) == 120);
static_assert(offsetof(ThemeBlock, sun_vector) == 128 && offsetof(ThemeBlock, sun_azimuth) == 152);
static_assert(offsetof(ThemeBlock, cloud) == 168 && offsetof(ThemeBlock, humidity) == 240 &&
              offsetof(ThemeBlock, reserved1) == 248);

/// Why a theme block was refused.
enum class ThemeError : std::uint8_t {
    size,       // not 256 bytes
    version,    // another layout version
    flags,      // a flag bit version 1 does not know, or wet without wet_set
    reserved,   // a reserved field that is not 0, or an absent colour that is not all 0
    date,       // not a real date and time of day in 1900-2200
    moment,     // a moment name past the known ones
    not_finite, // a NaN or an infinity among the doubles
    colour,     // a colour every theme has is missing, or water_top and water_bot disagree with water_gradient
    value,      // haze_max, sun_k, amb_k, sky_k, shadow_op, shadow_blur, cloud_shadows or _dusk out of range
    sun, // the sun vector is not a unit vector or disagrees with the elevation, or the sun's position is out of range
    weather, // the hour's weather out of range, or weather without the weather flag
};

/// The block in `bytes`, checked: its size, version, flags, reserved fields, date, colours and the ranges above.
[[nodiscard]] std::expected<ThemeBlock, ThemeError> read_theme_block(std::span<const std::byte> bytes) noexcept;

/// The shaders' Theme (theme.slang) for a checked block: colours 0-255, the port's values rounded to float, and the
/// doubles the exact light reads as their bits.
[[nodiscard]] shaders::Theme shader_theme(const ThemeBlock& block) noexcept;

/// Every field of a block as the core holds it, one `name=value` line each (integers in decimal, colours as #rrggbb
/// with their presence, doubles as their 64 bits in hex), for the tests that hold it to the port's dict.
[[nodiscard]] std::string dump(const ThemeBlock& block);

/// The name of a refusal, for logs and tests.
[[nodiscard]] const char* name_of(ThemeError error) noexcept;

} // namespace atlas::style
