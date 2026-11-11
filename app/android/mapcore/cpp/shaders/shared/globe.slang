// The world's look beyond 18 km (ENGINE.md step 5, "The style ladder" and "The globe": its step 8): what the world pass
// (shaders/world/world.slang) shades each fragment with, shared with the C++ tests (after base/hlsl.hpp).
//
// The ladder's weights are the frame's (scene/WorldLook.hpp: smoothsteps of log D between its knots), and every value
// here is the world's style's (tiles::WorldLadder, load::world_ladder: the Globe board's Earth as CosmosArt.drawEarth
// holds it), handed over in the frame's look block:
//
//   - the moment's palette hands over to the day's under the board's night caps from 18 to 75 km (w.x), so a view
//     across the terminator is lit where the sun is up: the caps where the sun stands under 0°, −6°, −12° and −18°,
//     each over the last, their edges a pixel soft on the globe and a degree soft below it (a terminator a pixel sharp
//     would cross a view of 100 km as a line);
//   - the land and the water go to the board's ramps from 600 to 6,000 km (w.y), both keyed to the surface's normal in
//     the view, which on the board's disc is its screen gradient: the sea's a radial ramp about a point up and left of
//     the disc's middle, the land's a ramp down the disc;
//   - the graticule over the water, the atmosphere's ring, the outline, the rim and the shine from 2,000 km, whole by
//     6,000 (w.z), and space behind where a pitched view's sky was, from 75 to 2,000 km (w.w).
//
// With every weight 0 a fragment's colour is its palette's, bit for bit.
//
// Shared with C++: only the subset base/hlsl.hpp describes.
#pragma once

/// What a fill or a line is to the ladder (WorldOut's kind): the land (and its cover, the caps), the water, or
/// another (the roads, the railways: their colour under the caps alone).
static const uint GLOBE_OTHER = 0u;
static const uint GLOBE_LAND = 1u;
static const uint GLOBE_WATER = 2u;

/// The coast's palette entry (scene::world_palette::coast): the line along the land's edge, the board's, which the
/// globe's tiles carry and the ladder draws with its ramps.
static const uint GLOBE_COAST_ENTRY = 7u;

/// The frame's look: the ladder's weights, the sun and the planet's axes in the frame's axes (x east, y up, z south at
/// the camera's target), and the world's style's values. Four-lane vectors of 32-bit floats only, so std140, std430
/// and C++ lay it out the same way.
struct WorldLookBlock {
    float4 weights;      // x: the night's hand-over (18 to 75 km); y: the board's ramps (600 to 6,000 km); z: the ring,
                         // the graticule, the rim and the shine (2,000 to 6,000 km); w: space (75 to 2,000 km)
    float4 sun;          // xyz: the unit vector toward the sun; w: the caps' softness below the globe, as a sine
    float4 pole;         // xyz: the north pole's direction; w: the sine of the latitude the meridians end at
    float4 prime;        // xyz: the direction of latitude 0, longitude 0; w: the graticule's half width (device px)
    float4 east;         // xyz: the direction of latitude 0, longitude 90° east; w: how many planes of meridians
                         // there are (half a turn over their step)
    float4 centre;       // xyz: the sphere's centre from the eye (frame metres); w: its radius
    float4 sea[3];       // rgb: the sea's ramp's stops (0 to 1); a: each stop's offset
    float4 sea_at;       // xy: the ramp's centre on the disc (x right, y up, radii); z: 1 / its radius; w: 0
    float4 land[5];      // rgb: the land's ramp's stops, down the disc from its top; a: each stop's offset
    float4 coast;        // rgb: the coast's line; a: its opacity; (its palette entry is GLOBE_COAST_ENTRY)
    float4 coast_at;     // x: the line's width (device px); yzw: 0
    float4 night;        // rgb: the night's colour; a: 0
    float4 cap_sines;    // the sines of the altitudes under which the four caps lie
    float4 cap_alphas;   // their opacities
    float4 graticule;    // rgb: the graticule's colour; a: its opacity
    float4 parallels;    // xyz: the sines of the parallels' latitudes north of the equator (the equator's 0, then two;
                         // their mirrors south); w: the cosine of the meridians' step
    float4 space;        // rgb: space; a: 0
    float4 space_glow;   // rgb: space's glow at its middle; a: where it ends, as a share of its radius
    float4 space_at;     // xy: the glow's middle (device px); z: 1 / its radius (device px); w: 0
    float4 air;          // rgb: the atmosphere's ring; a: its opacity at its brightest
    float4 air_at;       // x: where the ring begins, y: where it is brightest, z: where it ends (radii of the disc); w: 0
    float4 outline;      // rgb: the dashed outline behind the disc; a: its opacity
    float4 outline_at;   // x: its half width (device px); y: its dash, z: its gap (device px); w: 0
    float4 rim;          // rgb: the rim; a: its opacity
    float4 shine;        // rgb: the shine; a: its opacity at its middle
    float4 shine_at;     // xy: its middle on the disc (x right, y up, radii); z: 1 / its radius; w: the rim's half
                         // width (device px)
    float4 light[3];     // rgb: a city's light in the deep night, the middle and the dusk; a: its opacity
    float4 light_at;     // xyz: a light's radius by its place's size (device px); w: its halo's radius, in its own
    float4 light_rule;   // x: the halo's opacity of the light's; y: the sun's depth under the horizon the lights begin
                         // at, z: the depth over which they come in (degrees); w: the facing they are whole from
    float4 light_least;  // x: the facing under which a light is left out; y: how much of a light must show; zw: 0
};

/// smoothstep's cubic of t in 0 to 1.
inline float globe_ease(float t) {
    float x = saturate(t);
    return x * x * (3.0f - 2.0f * x);
}

/// A ramp of three stops (rgb, each stop's offset in a) at t.
inline float3 globe_ramp3(float4 a, float4 b, float4 c, float t) {
    if (t <= a.a) return a.rgb;
    if (t < b.a) return a.rgb + (b.rgb - a.rgb) * ((t - a.a) / (b.a - a.a));
    if (t < c.a) return b.rgb + (c.rgb - b.rgb) * ((t - b.a) / (c.a - b.a));
    return c.rgb;
}

/// The sea's ramp `distance` radii from its centre on the disc (the caller's square root: a surface whose normal in the
/// view is (x right, y up) lies length(n − sea_at.xy) from it).
inline float3 globe_sea(WorldLookBlock l, float distance) {
    return globe_ramp3(l.sea[0], l.sea[1], l.sea[2], distance * l.sea_at.z);
}

/// The land's ramp there: down the disc, 0 at its top and 1 at its bottom.
inline float3 globe_land(WorldLookBlock l, float2 n) {
    float t = 0.5f - 0.5f * n.y;
    float3 c = l.land[4].rgb;
    if (t <= l.land[0].a) {
        c = l.land[0].rgb;
    } else if (t < l.land[1].a) {
        c = l.land[0].rgb + (l.land[1].rgb - l.land[0].rgb) * ((t - l.land[0].a) / (l.land[1].a - l.land[0].a));
    } else if (t < l.land[2].a) {
        c = l.land[1].rgb + (l.land[2].rgb - l.land[1].rgb) * ((t - l.land[1].a) / (l.land[2].a - l.land[1].a));
    } else if (t < l.land[3].a) {
        c = l.land[2].rgb + (l.land[3].rgb - l.land[2].rgb) * ((t - l.land[2].a) / (l.land[3].a - l.land[2].a));
    } else if (t < l.land[4].a) {
        c = l.land[3].rgb + (l.land[4].rgb - l.land[3].rgb) * ((t - l.land[3].a) / (l.land[4].a - l.land[3].a));
    }
    return c;
}

/// How dark the night's caps lie where the sine of the sun's altitude is `sine`, 0 to 1: each cap over the last, its
/// edge `soft` wide (a sine: the pixel's on the globe, the block's below it).
inline float globe_night(WorldLookBlock l, float sine, float soft) {
    float s = max(soft, 1e-9f);
    float lit = 1.0f;
    lit *= 1.0f - l.cap_alphas.x * saturate((l.cap_sines.x - sine) / s + 0.5f);
    lit *= 1.0f - l.cap_alphas.y * saturate((l.cap_sines.y - sine) / s + 0.5f);
    lit *= 1.0f - l.cap_alphas.z * saturate((l.cap_sines.z - sine) / s + 0.5f);
    lit *= 1.0f - l.cap_alphas.w * saturate((l.cap_sines.w - sine) / s + 0.5f);
    return 1.0f - lit;
}

/// How much of a city's light shows where the sun stands `altitude` degrees up and the surface faces the eye by
/// `facing` (the cosine between its normal and the view): the board's clamp((−altitude − 1) / 10) · min(1, 3 · facing).
inline float globe_light(WorldLookBlock l, float altitude, float facing) {
    return saturate((-altitude - l.light_rule.y) / l.light_rule.z) * min(1.0f, facing * l.light_rule.w);
}

/// A light's colour by how much of it shows (the board's: the deep night's above .66, the middle's above .33).
inline float4 globe_light_colour(WorldLookBlock l, float night) {
    if (night > 0.66f) return l.light[0];
    if (night > 0.33f) return l.light[1];
    return l.light[2];
}
