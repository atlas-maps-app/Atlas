// The relief's light (ENGINE.md part 6, terrain, step 2): what the ground's colour is multiplied by where the terrain
// slopes or lies in a hollow, as a share of the light flat open ground takes, so flat open ground keeps its colours
// exactly. Each effect is a function of its own: the light of a sun and a sky (relief_factor), its shoulder
// (relief_shoulder), the shade's tint (relief_colour), a texel's normal (relief_normal). The values come with the frame
// (ReliefLight: fx/Relief.hpp composes it from the moment's theme and the relief's style, app/android/mapcore/style/
// relief.jsonc), so refining the look is an edit to data.
//
// The light of a point with unit normal n (x east, y up, z south) that sees the share v of the sky (its sky-view):
//   sky  = (1 − w)·v + w·max(0, n·W) / W_y      the sky's light: its share w comes from one side (W: the north-west,
//                                               the renders' overcast direction), the rest from all of it
//   f    = (sky + s·max(0, n·L)) / (1 + s·L_y)  beside the sun's (L, of weight s to the sky's 1): 1 on flat open ground
//   f    = shoulder(f)·(1 + a·(v − 1))          eased toward its most, and the sky-view's reading over all of it
// and never under the floor. With w = 0 and v = 1 it is the renders' 1 + k·(max(0, n·L) − L_y) for s = k / (1 − k·L_y)
// (render/relief.py); with s = 0 and w = k·W_y their overcast 1 + k·(n·W − W_y).
//
// Shared with the C++ twin (after base/hlsl.hpp, by fx/Relief.hpp). Not exact code: the GPU rounds its own way, and
// L2 holds it to the twin within a level.
#pragma once

/// The relief's light for a frame: 80 bytes.
struct ReliefLight {
    float4 sun;   // xyz: the sun's direction L (unit, toward it); w: its weight s beside the sky's 1 (0: no sun)
    float4 sky;   // xyz: the direction W of the sky's share that comes from one side (unit, W_y > 0); w: that share w
    float4 shape; // x: 1 / (1 + s·L_y); y: the sky-view's relief factor a; z: where the shoulder begins; w: the most
                  // the light reaches (the shoulder's end)
    float4 tint;  // rgb: the shade's tint at its full; w: how fast it comes as the light falls, q = (1 − f)·w
    float4 range; // x: the floor, the least light; y: 1 / W_y; z: 1 with the sky-view, 0 without (v read as 1); w: the
                  // light's strength over the frame (0 to 1: the relief fades out with the camera's distance)
};

/// A texel's two lanes (the normal's x and z) as the unit normal. A lane holds (n + 1)·511 of its ten bits' 1,023, so
/// level ground is a level of its own (511) and reads back as exactly 0.
inline float3 relief_normal(float2 lanes) {
    float x = lanes.x * 2.0019569f - 1.0f; // 1,023 / 511
    float z = lanes.y * 2.0019569f - 1.0f;
    float3 n = {x, sqrt(max(0.0f, 1.0f - x * x - z * z)), z};
    return n;
}

/// The light `f` eased toward `top` from `from` on (unchanged up to `from`; never past `top`): the renders clip at
/// their 1.16, and a clipped slope loses its gradient.
inline float relief_shoulder(float f, float from, float top) {
    float span = top - from;
    if (f <= from || span <= 0.0f) {
        return min(f, max(from, top));
    }
    return from + span * tanh((f - from) / span);
}

/// The light at a point of unit normal `n` and sky-view `v` (0 to 1), as a share of flat open ground's.
inline float relief_factor(ReliefLight l, float3 n, float v) {
    float3 sun = {l.sun.x, l.sun.y, l.sun.z};
    float3 side = {l.sky.x, l.sky.y, l.sky.z};
    float seen = 1.0f + l.range.z * (v - 1.0f);
    float sky = (1.0f - l.sky.w) * seen + l.sky.w * max(0.0f, dot(n, side)) * l.range.y;
    float f = (sky + l.sun.w * max(0.0f, dot(n, sun))) * l.shape.x;
    f = relief_shoulder(f, l.shape.z, l.shape.w) * (1.0f + l.shape.y * (seen - 1.0f));
    return max(l.range.x, f);
}

/// What a colour is multiplied by under light `f`: the light, and in shade the tint, coming as the light falls.
inline float3 relief_colour(ReliefLight l, float f) {
    float q = saturate((1.0f - f) * l.tint.w);
    float3 c = {f * (1.0f + (l.tint.x - 1.0f) * q), f * (1.0f + (l.tint.y - 1.0f) * q),
                f * (1.0f + (l.tint.z - 1.0f) * q)};
    return c;
}

/// The colour's factor at a texel's lanes (the normal's two, then the sky-view), at the frame's strength times
/// `strength` (a tile's own, 0 to 1): 1 where the strength is 0.
inline float3 relief_at(ReliefLight l, float3 lanes, float strength) {
    float2 xz = {lanes.x, lanes.y};
    float3 c = relief_colour(l, relief_factor(l, relief_normal(xz), lanes.z));
    float m = saturate(strength * l.range.w);
    float3 out_ = {1.0f + m * (c.x - 1.0f), 1.0f + m * (c.y - 1.0f), 1.0f + m * (c.z - 1.0f)};
    return out_;
}
