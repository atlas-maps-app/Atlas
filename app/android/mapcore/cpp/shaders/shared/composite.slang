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
// opacity at 1), black source-over. Both in whole numbers at the pixel's upright device position (K17a): the floats
// the composite is given become integers by exact steps alone (composite_fixed, composite_twelfths), the opacities are
// 1/4096ths and the vignette's distance an integer square root, so the GPU and the twin compute the same bits whatever
// a driver fuses or rounds.
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
// (gpu/Facts.hpp). Every step is integer arithmetic, exact by construction: the pass-through, the rule, the haze, the
// grain and the vignette.
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

/// floor(v · 2^k) as an integer, for a float the renders' arithmetic gives the composite (a stop's offset, an
/// opacity, a length in CSS px times the density): the product by a power of two is exact and floor is exact, so the
/// GPU and the twin take the same integer (no rounding a driver could fuse away). `v · d` first, for a length: one
/// product, correctly rounded on both.
inline int composite_fixed(float v, float scale) {
    return int(floor(v * scale));
}

/// v rounded to the nearest 1/4096 (half up), as an integer: v · 4096 is exact, and the one addition of a half
/// rounds alike on the GPU and the twin (the product is exact, so fusing them changes nothing).
inline int composite_twelfths(float v) {
    return int(floor(v * 4096.0f + 0.5f));
}

/// The largest integer whose square is at most n (n below 2^32 with a root below 65536): the float root, then
/// corrected in whole numbers, so the result never depends on how the root was rounded.
inline uint composite_isqrt(uint n) {
    uint s = uint(sqrt(float(n)));
    if (s > 65535u) {
        s = 65535u;
    }
    while (s > 0u && s * s > n) {
        s = s - 1u;
    }
    while (s < 65535u && (s + 1u) * (s + 1u) <= n) {
        s = s + 1u;
    }
    return s;
}

/// The haze's opacity at the upright device pixel row `row`, in 1/4096: the SVG's linearGradient (userSpaceOnUse,
/// from y0 down to y1) at the row's centre, its stops (offsets and opacities as the SVG prints them, each to the
/// nearest 1/4096) interpolated linearly in whole numbers, padded before the first and after the last. y0 and y1 are
/// taken times the density in 1/16 px; the row's place along the gradient, t, in 1/4096 of it, rounded down.
inline int composite_haze_alpha12(FrameLayers l, uint row, float density) {
    int p0 = composite_fixed(l.haze.x * density, 16.0f);
    int p1 = composite_fixed(l.haze.y * density, 16.0f);
    int y = int(row) * 16 + 8;
    int span = p1 - p0;
    int t = y >= p0 ? 4096 : 0;
    if (span > 0) {
        int along = y - p0;
        along = along < 0 ? 0 : (along > span ? span : along);
        while (span > 524287) { // along · 4096 stays below 2^31
            span = span >> 1;
            along = along >> 1;
        }
        t = (along * 4096) / span;
    }
    uint count = uint(l.haze.w);
    int before_offset = composite_twelfths(l.haze_offsets[0].x);
    int before_alpha = composite_twelfths(l.haze_alphas[0].x);
    if (t <= before_offset) {
        return before_alpha;
    }
    for (uint i = 1u; i < count; ++i) {
        int o = composite_twelfths(l.haze_offsets[i >> 2u][i & 3u]);
        int a = composite_twelfths(l.haze_alphas[i >> 2u][i & 3u]);
        if (t <= o) {
            int gap = o - before_offset;
            return gap > 0 ? before_alpha + ((a - before_alpha) * (t - before_offset)) / gap : a;
        }
        before_offset = o;
        before_alpha = a;
    }
    return before_alpha;
}

/// The vignette's distance along one axis, squared: the upright device column (or row) `at` of a box `size16` 1/16 px
/// across, its centre's fraction of the box in 1/65536 less the gradient's centre (`centre`: .5 or .46 of 65536), over
/// the radius (.75), squared; COMPOSITE_BEYOND where it reaches the radius or more on its own. A column's and a row's
/// depend on their own place alone, so the composite takes them from tables made once a frame (gain 2).
static const uint COMPOSITE_BEYOND = 4294967295u;
inline uint composite_vignette_square(uint at, uint size16, int centre) {
    uint a = at < 4095u ? at : 4095u;
    int f = int(((2u * a + 1u) << 19u) / (size16 > 0u ? size16 : 1u));
    f = f > 131072 ? 131072 : f;
    int d = ((f - centre) * 4) / 3;
    uint ad = uint(d < 0 ? -d : d);
    return ad >= 65536u ? COMPOSITE_BEYOND : ad * ad;
}

/// The box's width (or height) in 1/16 px, as the vignette takes it: W (or H) times the density.
inline uint composite_vignette_size16(float size, float density) {
    return uint(composite_fixed(size * density, 16.0f));
}

/// The vignette's opacity in 1/4096 from the column's and the row's squares (composite_vignette_square).
inline int composite_vignette_from_squares(FrameLayers l, uint uu, uint vv) {
    int corner = composite_twelfths(l.vignette.x);
    if (uu == COMPOSITE_BEYOND || vv == COMPOSITE_BEYOND) {
        return corner;
    }
    if (uu > 4294967295u - vv) { // beyond the radius: the square passes 2^32
        return corner;
    }
    uint r = composite_isqrt(uu + vv);
    if (r <= 36045u) { // .55 of the radius
        return 0;
    }
    if (r >= 65536u) {
        return corner;
    }
    return (corner * (int(r) - 36045)) / (65536 - 36045);
}

/// The vignette's opacity at the upright device pixel `pixel` of the W × H CSS frame (`frame`) at `density`, in
/// 1/4096: fin's radialGradient (objectBoundingBox: centre (.5, .46), radius .75 of the box's width and height),
/// transparent out to .55 of the radius, then linear to the corner opacity at the radius, and that beyond. In whole
/// numbers: the pixel's centre as a fraction of the box (W and H times the density, in 1/16 px) in 1/65536, the
/// distance from the centre in 1/65536 of the radius by an integer square root.
inline int composite_vignette_alpha12(FrameLayers l, uint2 pixel, float3 frame) {
    uint uu = composite_vignette_square(pixel.x, composite_vignette_size16(frame.x, frame.z), 32768); // .5
    uint vv = composite_vignette_square(pixel.y, composite_vignette_size16(frame.y, frame.z), 30147); // .46: 30146.56
    return composite_vignette_from_squares(l, uu, vv);
}

/// A whole level `c` of an output of `n` levels under the haze of colour `h` (its 8-bit levels / 255) at opacity
/// `alpha` (1/4096): c + (H − c) · alpha, rounded half up, in whole numbers.
inline float composite_haze_level(float c, float h, int alpha, float n) {
    uint l = uint(c);
    uint a = uint(alpha < 0 ? 0 : (alpha > 4096 ? 4096 : alpha));
    uint h8 = uint(floor(h * 255.0f + 0.5f));
    if (n == 255.0f) {
        return float((l * (4096u - a) + h8 * a + 2048u) / 4096u);
    }
    return float((l * (4096u - a) * 255u + h8 * 1023u * a + 522240u) / 1044480u);
}

/// A whole level `c` under fin's black vignette at opacity `alpha` (1/4096): c · (1 − alpha), rounded half up.
inline float composite_vignette_level(float c, int alpha) {
    uint a = uint(alpha < 0 ? 0 : (alpha > 4096 ? 4096 : alpha));
    return float((uint(c) * (4096u - a) + 2048u) / 4096u);
}

/// The finished colour of a pixel, as levels of the output (whole numbers), from its colour (levels: the mean of its
/// samples, or the rule's at a silhouette) at the upright device pixel `pixel` of the W × H CSS frame at the density
/// (`frame`: W, H, density): the screen steps that run, each composited in whole numbers. `grain`: the grain field's
/// grey and alpha at the pixel and fin's opacity byte, 0 where the grain does not run (the fields not bound).
/// The finished colour from the haze's and the vignette's opacities at the pixel (1/4096), however they were found: the
/// composite takes them from the frame's tables (gain 2), the twin from composite_steps_fx's own arithmetic.
inline float3 composite_steps_alphas(FrameLayers l, float3 levels, int haze, int vignette, uint3 grain) {
    float n = float(l.steps.y);
    uint steps = l.steps.x & COMPOSITE_DRAWN;
    float3 c = levels;
    if ((steps & COMPOSITE_HAZE) != 0u && l.haze.w > 0.0f) {
        int a = haze;
        float3 h = {composite_haze_level(c.x, l.haze_colour.x, a, n), composite_haze_level(c.y, l.haze_colour.y, a, n),
                    composite_haze_level(c.z, l.haze_colour.z, a, n)};
        c = h;
    }
    if ((steps & COMPOSITE_GRAIN) != 0u && grain.z != 0u) {
        float3 g = {composite_grain_level(c.x, grain, n), composite_grain_level(c.y, grain, n),
                    composite_grain_level(c.z, grain, n)};
        c = g;
    }
    if ((steps & COMPOSITE_VIGNETTE) != 0u) {
        int a = vignette;
        float3 v = {composite_vignette_level(c.x, a), composite_vignette_level(c.y, a), composite_vignette_level(c.z, a)};
        c = v;
    }
    return c;
}

inline float3 composite_steps_fx(FrameLayers l, float3 levels, uint2 pixel, float3 frame, uint3 grain) {
    uint steps = l.steps.x & COMPOSITE_DRAWN;
    int haze = (steps & COMPOSITE_HAZE) != 0u && l.haze.w > 0.0f ? composite_haze_alpha12(l, pixel.y, frame.z) : 0;
    int vignette = (steps & COMPOSITE_VIGNETTE) != 0u ? composite_vignette_alpha12(l, pixel, frame) : 0;
    return composite_steps_alphas(l, levels, haze, vignette, grain);
}

/// The colour to write for whole levels `levels` of the output: level / levels, which the UNORM attachment turns back
/// into that level.
inline float4 composite_output(float3 levels, float n) {
    float r = 1.0f / n;
    float4 o = {levels.x * r, levels.y * r, levels.z * r, 1.0f};
    return o;
}
