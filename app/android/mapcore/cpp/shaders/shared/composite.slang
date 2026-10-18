// The composite's per-pixel arithmetic (plan-vector D5.4): what the composite subpass does to one pixel once the scene
// subpass has drawn it, shared with the C++ twin (after base/hlsl.hpp, by CP/passes/Composite.hpp).
//
// The renders draw the scene, then the screen effects over it, each composited into an 8-bit raster: wfx (the cloud
// shadows by multiply, the rain), the haze with the golden wash, then fin (the grain by overlay, the vignette)
// (render/amsterdam.py:615 render, R/Amsterdam.kt render). The composite takes a pixel's samples, makes the pixel's
// colour from them, and applies those steps once, in that order, each rounded to the output's levels as the renders'
// raster rounds it. This revision (K7) draws the haze and the vignette; the coverage rule of `mat` at the objects group's
// silhouettes and the grain (K17a), the cloud shadows, the rain and the wash (K17) are stubbed: a step whose bit is off
// in FrameLayers.steps is skipped, so with every bit off the composite passes the colours through.
//
// The pixel's colour from its samples is exact (the twin computes the GPU's bits): each sample is read as the output's
// level it holds (round(c · levels): an n-bit UNORM sample decodes to within a hair of level / levels), the levels are
// summed, and the mean is rounded to the nearest level with ties to even. Where every sample holds one colour that
// colour comes back unchanged, bit for bit (CP/tests/frame/composite_vk_test.cpp); where the samples differ, the mean
// of their levels, which the resolve then keeps unchanged (the composite writes the same colour to every sample). The
// colour is written as level / levels, which the attachment's conversion turns back into that level exactly.
//
// The haze is the renders' linearGradient of the theme's haze colour (ScreenLayers.haze: 17 stops down from the
// horizon, or the eye-level band of 25), its opacity interpolated linearly between the stops it prints (CP/view/
// ScreenLayers.cpp rounds them to the SVG's precision before they are uploaded), source-over the pixel. The vignette is
// fin's radialGradient over the frame's box (centre (.5, .46), radius .75 of the box, transparent to .55, the corner
// opacity at 1), black source-over.
//
// It reads its samples' colour only, never their alpha: the frame may be rgb10a2, which keeps 2 bits of alpha
// (gpu/Facts.hpp). Not exact as a module (the vignette takes a square root); the pass-through is exact by construction.
#pragma once

/// The screen layers the composite applies, for one frame: 288 bytes.
struct FrameLayers {
    float4 haze;            // x: the haze's top row y0, y: its bottom row y1 (CSS px); z: 1 / (y1 − y0), 0 for none;
                            // w: how many stops it has (17; 25 for the eye-level band; 0: no haze)
    float4 haze_colour;     // rgb: the theme's haze colour, its 8-bit levels / 255; a: 0
    float4 haze_offsets[7]; // the stops' offsets (0 to 1, ascending), four a vector, as the SVG prints them
    float4 haze_alphas[7];  // the stops' opacities, as the SVG prints them
    float4 vignette;        // x: fin's opacity at the corners (.2, .38 at night); y, z, w: 0
    uint4 steps;            // x: the COMPOSITE_ bits of the steps that run; y: the output's levels (255 or 1023); z, w: 0
};

// The composite's steps, in the renders' order of compositing. A step whose bit is off is skipped (stubbed).
static const uint COMPOSITE_MAT = 1u;           // `mat`'s coverage rule at the objects group's silhouettes (K17a)
static const uint COMPOSITE_CLOUD_SHADOWS = 2u; // wfx: the clouds' shadows by multiply (K17)
static const uint COMPOSITE_RAIN = 4u;          // wfx: the rain's streaks (K17)
static const uint COMPOSITE_HAZE = 8u;          // the haze by ground depth (ScreenLayers.haze)
static const uint COMPOSITE_WASH = 16u;         // the golden wash (K17)
static const uint COMPOSITE_GRAIN = 32u;        // fin: the paper grain by overlay (K17a)
static const uint COMPOSITE_VIGNETTE = 64u;     // fin: the vignette
/// The steps K7 draws; the others are stubbed whatever their bit says.
static const uint COMPOSITE_DRAWN = 72u; // COMPOSITE_HAZE | COMPOSITE_VIGNETTE

/// The output's level a sample holds: an n-bit UNORM value c decodes within 2^-12 of level / levels, so
/// floor(c · levels + 1/2) is its level exactly.
inline float composite_level(float c, float levels) {
    return floor(c * levels + 0.5f);
}

/// The nearest whole number to x (a sum of levels times 1/4 or 1/2: a multiple of a quarter), ties to even.
inline float composite_round_even(float x) {
    float k = floor(x);
    float f = x - k; // exact: x and its floor share their leading bits
    if (f > 0.5f) {
        return k + 1.0f;
    }
    if (f < 0.5f) {
        return k;
    }
    float half = floor(k * 0.5f);
    return half * 2.0f == k ? k : k + 1.0f;
}

/// The mean of `n` samples (1, 2 or 4) whose levels sum to `sum`, as a level: ties to even.
inline float3 composite_mean(float3 sum, uint n) {
    float share = n == 4u ? 0.25f : (n == 2u ? 0.5f : 1.0f);
    float3 m = {composite_round_even(sum.x * share), composite_round_even(sum.y * share),
                composite_round_even(sum.z * share)};
    return m;
}

/// A colour (0 to 1) at the nearest of the output's levels, as the renders' raster keeps each composited layer.
inline float3 composite_quantise(float3 c, float levels) {
    float3 q = {composite_level(c.x, levels), composite_level(c.y, levels), composite_level(c.z, levels)};
    return q;
}

/// The haze's opacity at CSS row y: the gradient's stops interpolated linearly, padded before the first and after the
/// last, as the SVG's linearGradient (userSpaceOnUse, from y0 down to y1) draws it.
inline float composite_haze_alpha(FrameLayers l, float y) {
    uint count = uint(l.haze.w);
    float t = (y - l.haze.x) * l.haze.z;
    float first = l.haze_offsets[0].x;
    if (t <= first) {
        return l.haze_alphas[0].x;
    }
    float before_offset = first;
    float before_alpha = l.haze_alphas[0].x;
    for (uint i = 1u; i < count; ++i) {
        float o = l.haze_offsets[i >> 2u][i & 3u];
        float a = l.haze_alphas[i >> 2u][i & 3u];
        if (t <= o) {
            float span = o - before_offset;
            return span > 0.0f ? before_alpha + (a - before_alpha) * ((t - before_offset) / span) : a;
        }
        before_offset = o;
        before_alpha = a;
    }
    return before_alpha;
}

/// The vignette's opacity at CSS point p of a W × H frame: fin's radialGradient (objectBoundingBox: centre (.5, .46),
/// radius .75 of the box's width and height), transparent out to .55 of the radius, then linear to the corner opacity
/// at the radius, and that beyond.
inline float composite_vignette_alpha(FrameLayers l, float2 p, float2 frame) {
    float u = (p.x / frame.x - 0.5f) / 0.75f;
    float v = (p.y / frame.y - 0.46f) / 0.75f;
    float r = sqrt(u * u + v * v);
    if (r <= 0.55f) {
        return 0.0f;
    }
    if (r >= 1.0f) {
        return l.vignette.x;
    }
    return l.vignette.x * ((r - 0.55f) / 0.45f);
}

/// The finished colour of a pixel, as levels of the output (whole numbers), from the mean of its samples (levels) at CSS
/// point p of a W × H frame (`frame`): the screen steps that run, each composited and rounded to the output's levels.
inline float3 composite_steps(FrameLayers l, float3 levels, float2 p, float2 frame) {
    float n = float(l.steps.y);
    uint steps = l.steps.x & COMPOSITE_DRAWN;
    float3 c = levels;
    if ((steps & COMPOSITE_HAZE) != 0u && l.haze.w > 0.0f) {
        float a = composite_haze_alpha(l, p.y);
        float3 x = c * (1.0f / n);
        float3 h = l.haze_colour.xyz;
        c = composite_quantise(x + (h - x) * a, n);
    }
    if ((steps & COMPOSITE_VIGNETTE) != 0u) {
        float a = composite_vignette_alpha(l, p, frame);
        float3 x = c * (1.0f / n);
        c = composite_quantise(x * (1.0f - a), n);
    }
    return c;
}

/// The colour to write for whole levels `levels` of the output: level / levels, which the UNORM attachment turns back
/// into that level.
inline float4 composite_output(float3 levels, float n) {
    float r = 1.0f / n;
    float4 o = {levels.x * r, levels.y * r, levels.z * r, 1.0f};
    return o;
}
