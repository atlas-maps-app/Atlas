// The moment's theme as the shaders read it (plan-vector D8, D14): the port's weather_theme (render/amsterdam.py:224)
// with the sun vector of its hour and the profile's TONE, made by the core from the theme block that Kotlin writes once
// a minute (CP/style/Theme.cpp, MK/state/ThemeBlock.kt). Colours are 0-255, as the port's hx reads them.
//
// The port's values come in two forms: rounded to float in the four-lane vectors every shader reads, and, for the
// exact light (light.slang), the doubles light() reads as their 64 bits (the bits_ lanes: low word, then high word),
// so a module that reads only the floats needs no Float64 capability.
//
// Shared with the C++ twin (after base/hlsl.hpp, by CP/style/Light.hpp): only the subset base/hlsl.hpp describes. A
// module takes it with #include, as every file of shaders/shared/. Every member is a four-lane vector of 32-bit
// values, so std140, std430 and C++ lay the struct out the same way: 288 bytes.
#pragma once

struct Theme {
    float4 ambient;      // rgb: amb; a: amb_k
    float4 sun;          // rgb: sun; a: sun_k
    float4 light;        // xyz: the sun vector _L (toward the sun; straight down at night); w: sky_k, 1 without
    float4 haze;         // rgb: haze; a: haze_max
    float4 sky_top;      // rgb: sky_top
    float4 sky_horizon;  // rgb: sky_hor
    float4 ground;       // rgb: ground
    float4 road;         // rgb: road
    float4 water;        // rgb: water
    float4 water_top;    // rgb: water_top (weather moments)
    float4 water_bottom; // rgb: water_bot (weather moments)
    float4 shadow;       // rgb: shadow; a: shadow_op
    float4 weather;      // x: shadow_blur; y: cloud_shadows; z: _dusk; w: the hour's cloud cover (0-1)
    float4 wind;         // x: wind speed (km/h); y: its direction (degrees); z: rain (mm); w: visibility (m)
    uint4 flags;         // x: the THEME_ bits below; yzw: 0
    uint4 bits_amb_sun;  // amb_k and sun_k, each as its low and high word
    uint4 bits_sky_lx;   // sky_k (1 without) and the sun vector's x
    uint4 bits_lyz;      // the sun vector's y and z
};

// The theme's switches: the theme block's flag bits, carried through unchanged.
static const uint THEME_NIGHT = 1u;           // th['night']: the sun more than 2 degrees under the horizon
static const uint THEME_WET = 2u;             // th['wet']: rain over 0.05 mm in the hour
static const uint THEME_WEATHER = 4u;         // the moment has weather (th['wx']): sky layer, mat, wfx, fin
static const uint THEME_OVERCAST = 8u;        // th['sky_k'] is set (85 % cloud or more)
static const uint THEME_SHADOW_BLUR = 16u;    // th['shadow_blur'] is set
static const uint THEME_CLOUD_SHADOWS = 32u;  // th['cloud_shadows'] is set (it may be 0)
static const uint THEME_WATER_GRADIENT = 64u; // th['water_top'] and th['water_bot'] are set
static const uint THEME_DUSK = 128u;          // th['_dusk'] is set (the daylight's dusk)
static const uint THEME_WET_SET = 256u;       // th['wet'] is set (fixed moments have none)
static const uint THEME_GOLDEN = 512u;        // the golden moment: the golden wash over the frame
static const uint THEME_TONE = 1024u;         // the profile's TONE: highlights ease off above 205 (the regions' profile)

/// True when the theme's flags hold `flag`.
inline bool theme_has(Theme th, uint flag) {
    return (th.flags.x & flag) != 0u;
}
