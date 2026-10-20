// The composite's per-pixel arithmetic (plan-vector D5.4): what the composite subpass does to one pixel once the scene
// subpass has drawn it, shared with the C++ twin (after base/hlsl.hpp, by CP/passes/Composite.hpp).
//
// The renders draw the scene, then the screen effects over it, each composited into an 8-bit raster: wfx (the cloud
// shadows by multiply, the rain), the haze with the golden wash, then fin (the grain by overlay, the vignette)
// (render/amsterdam.py:615 render, R/Amsterdam.kt render). The composite takes a pixel's samples, makes the pixel's
// colour from them, and applies those steps once, in that order, each rounded to the output's levels as the renders'
// raster rounds it. It draws the haze and the vignette (K7), `mat`'s coverage rule at the objects group's silhouettes
// and the grain (K17a); the cloud shadows, the rain and the wash (K17) are stubbed: a step whose bit is off in
// FrameLayers.steps is skipped, so with every bit off the composite passes the colours through.
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
// `mat`'s coverage rule (D5.4, N13; K17a): the renders filter the objects group as one layer, and the filter's `in`
// SourceAlpha squares a pixel's coverage A, thinning the group's silhouettes: its alpha is min(1, 1.1·A)·A, its colour
// the interior's over 1.1 in linear light (CP/fx/Mat.hpp's mat_edge_table). Where a pixel's samples are the group's in
// part (k of n by the objects bit, A = k/n), the composite takes the mean level E of the group's samples through the
// edge table and the mean level G of the others, and composites them as the renders do, in whole numbers:
// (E·11k² + G·(10n² − 11k²) + 5n²) / 10n². Where every sample or none is the group's, the mean as above.
//
// The grain (fin; K17a): the grain field's premultiplied grey and alpha overlaid at fin's opacity as Skia's 8-bit
// pipeline overlays them, x86's division by 255 (CP/fx/Grain.hpp is its reference): integers, after the haze and
// before the vignette. At 10 bits a channel, the change the 8-bit overlay makes to the nearest 8-bit level, scaled.
//
// It reads its samples' colour only, never their alpha: the frame may be rgb10a2, which keeps 2 bits of alpha
// (gpu/Facts.hpp). Not exact as a module (the vignette takes a square root); the pass-through, the rule and the grain
// are integer arithmetic, exact by construction.
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
/// The steps the composite draws; the others are stubbed whatever their bit says.
static const uint COMPOSITE_DRAWN = 105u; // COMPOSITE_MAT | COMPOSITE_HAZE | COMPOSITE_GRAIN | COMPOSITE_VIGNETTE

/// fin's grain opacity as the byte Skia's stages scale by (CP/fx/Grain.hpp's skia_byte of .09, and of .12 at night).
static const uint COMPOSITE_GRAIN_DAY = 23u;
static const uint COMPOSITE_GRAIN_NIGHT = 31u;

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

/// The mean level of `k` samples (1 to 4) whose whole levels sum to `sum`, rounded half up.
inline uint3 composite_mean_of(float3 sum, uint k) {
    uint3 s = {uint(sum.x), uint(sum.y), uint(sum.z)};
    uint twice = 2u * k;
    uint3 m = {(2u * s.x + k) / twice, (2u * s.y + k) / twice, (2u * s.z + k) / twice};
    return m;
}

/// `mat`'s coverage rule at a silhouette of the objects group: the group's `k` samples of the pixel's `n` (0 < k < n)
/// through the edge table (`edge`, whole levels) composited with alpha 1.1·(k/n)² over the mean of the others
/// (`other`), as whole levels: (E·11k² + G·(10n² − 11k²) + 5n²) / 10n².
inline float3 composite_silhouette(uint3 edge, uint3 other, uint k, uint n) {
    uint w = 11u * k * k;
    uint d = 10u * n * n;
    uint half = d / 2u;
    float3 r = {float((edge.x * w + other.x * (d - w) + half) / d), float((edge.y * w + other.y * (d - w) + half) / d),
                float((edge.z * w + other.z * (d - w) + half) / d)};
    return r;
}

/// Skia's lowp division by 255 on x86, in a 16-bit lane: (v + 255) / 256.
inline uint composite_div255(uint v) {
    return ((v + 255u) & 0xFFFFu) >> 8u;
}

/// An 8-bit level `d` of the opaque canvas after the grain: its premultiplied grey `grey` and alpha `alpha` scaled by
/// the opacity byte, then Skia's lowp overlay (s·(255 − da) is 0 under an opaque canvas), each step in its 16-bit lane.
inline uint composite_grain_8(uint d, uint grey, uint alpha, uint opacity) {
    uint s = composite_div255((grey * opacity) & 0xFFFFu);
    uint sa = composite_div255((alpha * opacity) & 0xFFFFu);
    uint term = 2u * d <= 255u ? (2u * s * d) & 0xFFFFu : (((sa * 255u) & 0xFFFFu) - ((2u * (sa - s) * (255u - d)) & 0xFFFFu)) & 0xFFFFu;
    uint sum = (((d * (255u - sa)) & 0xFFFFu) + term) & 0xFFFFu;
    return composite_div255(sum);
}

/// A whole level of an output of `n` levels after the grain (`grain`: grey, alpha, opacity byte): at 8 bits
/// composite_grain_8; at 10, the change it makes to the nearest 8-bit level, scaled to 10 bits and rounded.
inline float composite_grain_level(float level, uint3 grain, float n) {
    uint d = uint(level);
    if (n == 255.0f) {
        return float(composite_grain_8(d, grain.x, grain.y, grain.z));
    }
    uint d8 = (d * 255u + 511u) / 1023u;
    int delta = int(composite_grain_8(d8, grain.x, grain.y, grain.z)) - int(d8);
    int r = int(d) + (delta * 1023 + (delta >= 0 ? 127 : -127)) / 255;
    return float(r < 0 ? 0 : (r > 1023 ? 1023 : r));
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

/// The finished colour of a pixel, as levels of the output (whole numbers), from its colour (levels: the mean of its
/// samples, or the rule's at a silhouette) at CSS point p of a W × H frame (`frame`): the screen steps that run, each
/// composited and rounded to the output's levels. `grain`: the grain field's grey and alpha at the pixel and fin's
/// opacity byte, 0 where the grain does not run (the fields not bound).
inline float3 composite_steps_fx(FrameLayers l, float3 levels, float2 p, float2 frame, uint3 grain) {
    float n = float(l.steps.y);
    uint steps = l.steps.x & COMPOSITE_DRAWN;
    float3 c = levels;
    if ((steps & COMPOSITE_HAZE) != 0u && l.haze.w > 0.0f) {
        float a = composite_haze_alpha(l, p.y);
        float3 x = c * (1.0f / n);
        float3 h = l.haze_colour.xyz;
        c = composite_quantise(x + (h - x) * a, n);
    }
    if ((steps & COMPOSITE_GRAIN) != 0u && grain.z != 0u) {
        float3 g = {composite_grain_level(c.x, grain, n), composite_grain_level(c.y, grain, n),
                    composite_grain_level(c.z, grain, n)};
        c = g;
    }
    if ((steps & COMPOSITE_VIGNETTE) != 0u) {
        float a = composite_vignette_alpha(l, p, frame);
        float3 x = c * (1.0f / n);
        c = composite_quantise(x * (1.0f - a), n);
    }
    return c;
}

/// composite_steps_fx without the grain.
inline float3 composite_steps(FrameLayers l, float3 levels, float2 p, float2 frame) {
    uint3 none = {0u, 0u, 0u};
    return composite_steps_fx(l, levels, p, frame, none);
}

/// The colour to write for whole levels `levels` of the output: level / levels, which the UNORM attachment turns back
/// into that level.
inline float4 composite_output(float3 levels, float n) {
    float r = 1.0f / n;
    float4 o = {levels.x * r, levels.y * r, levels.z * r, 1.0f};
    return o;
}
