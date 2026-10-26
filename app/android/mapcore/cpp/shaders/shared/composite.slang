// The composite's per-pixel arithmetic (plan-vector D5.4): what the composite subpass does to one pixel once the scene
// subpass has drawn it, shared with the C++ twin (after base/hlsl.hpp, by CP/passes/Composite.hpp).
//
// The renders draw the scene, then the screen effects over it, each composited into an 8-bit raster: wfx (the cloud
// shadows by multiply, the rain), the haze with the golden wash, then fin (the grain by overlay, the vignette)
// (render/amsterdam.py:615 render, R/Amsterdam.kt render). The composite takes a pixel's samples, makes the pixel's
// colour from them, and applies those steps once, in that order, each rounded to the output's levels as the renders'
// raster rounds it. It draws the haze and the vignette (K7), `mat`'s coverage rule at the objects group's silhouettes
// and the grain (K17a), the cloud shadows, the rain and the wash (K17): a step whose bit is off in FrameLayers.steps is
// skipped, so with every bit off the composite passes the colours through.
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
// (gpu/Facts.hpp). Every step is integer arithmetic, exact by construction: the pass-through, the rule, the cloud
// shadows, the rain, the haze, the wash, the grain and the vignette.
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
/// The steps the composite draws (every one).
static const uint COMPOSITE_DRAWN = 127u;

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

/// wfx's cloud shadows (render/amsterdam.py:943): below the horizon, before the haze, the `cloudsh` filter's pixel (its
/// table's entry by the noise's red: premultiplied sRGB, a r g b) multiplied over the pixel at the rect's opacity .42. In
/// whole numbers: the source's opacity a = A · .42 in 1/4096, (A · 1720 + 127) / 255; its colour unpremultiplied,
/// (C · 255 + A / 2) / A; and the W3C multiply over the opaque backdrop, b (1 − a) + a C b = b (1 − a (1 − C)), rounded
/// half up: a whole level `c` under the source of colour `cs` (0 to 255) at opacity `a12`.
inline float composite_cloud_level(float c, uint cs, uint a12) {
    uint b = uint(c);
    return float((b * (1044480u - (255u - cs) * a12) + 522240u) / 1044480u);
}

/// wfx's rain (render/amsterdam.py:947; ScreenLayers.kt wfx, rainStreaks): on a wet moment, after the cloud shadows and
/// before the haze, a pattern of 26 short faint streaks over a rect across the frame at .45, source-over. The tile is
/// 127 × 211 px; random.Random(4) gives each streak in turn its x and y in the tile, its length (3 to 8), its width (.3
/// to .55) and its opacity (.35 to .9), as the SVG prints them; each is a vertical segment stroked in #C3CEE8 with
/// round caps; the pattern turns with the wind (patternTransform rotate(int(wind · .8))). At four points of the pixel
/// (the 4x standard sample positions, whatever samples the frame has) the composite takes the point's place in the tile
/// (its CSS point turned back by the angle, the tile repeated), the streaks that reach it there (a streak is the points
/// within half its width of its segment, measured exactly; the tile clips what lies beyond it, and its grid lists the
/// streaks that reach each of its 8 px cells), their layer (1 − Π(1 − opacity): one colour, painted in order), and the
/// four points' mean at .45. In whole numbers, from the frame's tables' rain block (passes/Composite.cpp's rain_block):
/// the point's place in 1/65536 px by an integer transform (the angle's cosine and sine over the density in 1/65536,
/// the points' offsets turned likewise), the tile's coordinates in 1/256 px (rounded; the transform's own rounding
/// moves a point by at most 1/131072 px for each device px it lies across and down from the frame's corner), the
/// squared distances in 1/65536 px², the opacities in 1/100 and the layer in 1/4096.
static const uint COMPOSITE_RAIN_AT = 258u;            // the rain's block in the frame's tables, after the clouds'
static const uint COMPOSITE_RAIN_STREAKS = 10u;        // its streaks, 4 words each: x, y0, y1 (1/256 px), r² | op << 16
static const uint COMPOSITE_RAIN_CELLS = 114u;         // its grid's cells, a row of 16 at a time: first | count << 16
static const uint COMPOSITE_RAIN_LISTS = 546u;         // the cells' streaks, cell after cell
static const uint COMPOSITE_RAIN_WORDS = 704u;         // the block's length
static const uint COMPOSITE_RAIN_COUNT = 26u;          // the tile's streaks
static const uint COMPOSITE_RAIN_GRID_X = 16u;         // the grid's cells across (8 px, 2048 in 1/256: 127 px)
static const uint COMPOSITE_RAIN_GRID_Y = 27u;         // and down (211 px)
static const uint COMPOSITE_RAIN_SIDE_X = 8323072u;    // the tile's width in 1/65536 px: 127 · 65536
static const uint COMPOSITE_RAIN_SIDE_Y = 13828096u;   // its height: 211 · 65536
static const uint COMPOSITE_RAIN_BIAS_X = 1498152960u; // 180 widths, which lift every place the block allows above 0
static const uint COMPOSITE_RAIN_BIAS_Y = 1493434368u; // 108 heights
static const uint COMPOSITE_RAIN_R = 195u;             // #C3CEE8
static const uint COMPOSITE_RAIN_G = 206u;
static const uint COMPOSITE_RAIN_B = 232u;
/// The frame's tables after their header and the rain's block: the haze's rows, then the vignette's squares.
static const uint COMPOSITE_TABLE_HEAD = 962u;

/// The words the rain reads: the frame's tables on the GPU, a span of them in the twin.
#if defined(__cplusplus)
using CompositeWords = std::span<const uint>;
#else
typedef StructuredBuffer<uint> CompositeWords;
#endif

/// A place `q` along one side of the tile (1/65536 px) as the tile's coordinate in 1/256 px, rounded: lifted by `bias`
/// (whole tiles) above 0, so the remainder is the tile's whatever the place's sign.
inline uint composite_rain_wrap(int q, uint side, uint bias) {
    return (((uint(q) + bias) % side) + 128u) >> 8u;
}

/// The transparency `clear` (1/4096) of a point at tile coordinates `t` (1/256 px) once streak `s` (x, y0, y1,
/// r² | opacity << 16) is painted: the streak's opacity over it where the point lies within its half width of the
/// streak's segment (r², 1/65536 px²).
inline uint composite_rain_over(uint2 t, uint4 s, uint clear) {
    int dx = int(t.x) - int(s.x);
    int dy = 0;
    if (t.y < s.y) {
        dy = int(s.y) - int(t.y);
    } else if (t.y > s.z) {
        dy = int(t.y) - int(s.z);
    }
    if (dx < -255 || dx > 255 || dy > 255) {
        return clear;
    }
    if (uint(dx * dx + dy * dy) > (s.w & 0xFFFFu)) {
        return clear;
    }
    return (clear * (100u - (s.w >> 16u)) + 50u) / 100u;
}

/// The rain's opacity at the upright device pixel `pixel`, in 1/4096 (0 to 1843): the mean of its four points' layers
/// at .45, from the rain's block at word `at` of `t` (the block: [0] the cosine and [1] the sine of the angle over the
/// density in 1/65536, [2..9] the four points' offsets from the pixel's corner turned likewise, x then y; then the
/// streaks, the cells and their lists).
inline int composite_rain_alpha12(CompositeWords t, uint at, uint2 pixel) {
    int c = int(t[at]);
    int s = int(t[at + 1u]);
    int px = int(pixel.x < 4095u ? pixel.x : 4095u);
    int py = int(pixel.y < 4095u ? pixel.y : 4095u);
    int qx = c * px + s * py;
    int qy = c * py - s * px;
    uint sum = 0u;
    for (uint k = 0u; k < 4u; ++k) {
        uint2 p = {composite_rain_wrap(qx + int(t[at + 2u + (2u * k)]), COMPOSITE_RAIN_SIDE_X, COMPOSITE_RAIN_BIAS_X),
                   composite_rain_wrap(qy + int(t[at + 3u + (2u * k)]), COMPOSITE_RAIN_SIDE_Y, COMPOSITE_RAIN_BIAS_Y)};
        uint cell = t[at + COMPOSITE_RAIN_CELLS + ((p.y >> 11u) * COMPOSITE_RAIN_GRID_X) + (p.x >> 11u)];
        uint first = at + COMPOSITE_RAIN_LISTS + (cell & 0xFFFFu);
        uint count = cell >> 16u;
        uint clear = 4096u;
        for (uint i = 0u; i < count; ++i) {
            uint w = at + COMPOSITE_RAIN_STREAKS + (4u * t[first + i]);
            uint4 streak = {t[w], t[w + 1u], t[w + 2u], t[w + 3u]};
            clear = composite_rain_over(p, streak, clear);
        }
        sum = sum + (4096u - clear);
    }
    return int((sum * 45u + 200u) / 400u);
}

/// The golden wash (render/amsterdam.py:921; ScreenLayers.kt washDef): at the golden moment, after the haze, a
/// linearGradient over the frame's box (objectBoundingBox, from (0, 0) to (1, .35)) of #FFB25E at .14, #FFC98A at .05 at
/// .45 and #FFC98A at 0 at its end, source-over. In whole numbers: the pixel's centre as a fraction of the box in
/// 1/65536 along each side (as the vignette takes it), its place along the gradient t = (u + .35 v) / (1 + .35²) =
/// (400 u + 140 v) / 449 in 1/4096, the stops' offsets and opacities to the nearest 1/4096 (as the haze takes them), and
/// each channel's level interpolated between the stops' and rounded half up.
static const int COMPOSITE_WASH_MID = 1843; // .45 in 1/4096
static const int COMPOSITE_WASH_A0 = 573;   // .14
static const int COMPOSITE_WASH_A1 = 205;   // .05
static const uint COMPOSITE_WASH_R0 = 255u; // #FFB25E
static const uint COMPOSITE_WASH_G0 = 178u;
static const uint COMPOSITE_WASH_B0 = 94u;
static const uint COMPOSITE_WASH_R1 = 255u; // #FFC98A
static const uint COMPOSITE_WASH_G1 = 201u;
static const uint COMPOSITE_WASH_B1 = 138u;

/// The pixel's place along the wash, t in 1/4096 (0 to 4096).
inline int composite_wash_t(uint2 pixel, float3 frame) {
    uint w16 = composite_vignette_size16(frame.x, frame.z);
    uint h16 = composite_vignette_size16(frame.y, frame.z);
    uint px = pixel.x < 4095u ? pixel.x : 4095u;
    uint py = pixel.y < 4095u ? pixel.y : 4095u;
    int fu = int(((2u * px + 1u) << 19u) / (w16 > 0u ? w16 : 1u));
    int fv = int(((2u * py + 1u) << 19u) / (h16 > 0u ? h16 : 1u));
    fu = fu > 131072 ? 131072 : fu;
    fv = fv > 131072 ? 131072 : fv;
    int t = ((400 * fu + 140 * fv) / 449) >> 4;
    return t > 4096 ? 4096 : t;
}

/// The wash's opacity at `t`, in 1/4096.
inline int composite_wash_alpha12(int t) {
    if (t <= COMPOSITE_WASH_MID) {
        return COMPOSITE_WASH_A0 + ((COMPOSITE_WASH_A1 - COMPOSITE_WASH_A0) * t) / COMPOSITE_WASH_MID;
    }
    return COMPOSITE_WASH_A1 - (COMPOSITE_WASH_A1 * (t - COMPOSITE_WASH_MID)) / (4096 - COMPOSITE_WASH_MID);
}

/// A whole level `c` under the wash at `t`: the channel's colour between its two stops' (`c0` to `c1` over the first
/// part, `c1` after), at the wash's opacity.
inline float composite_wash_level(float c, uint c0, uint c1, int t, float n) {
    uint w = c1;
    if (t <= COMPOSITE_WASH_MID) {
        w = c0 + ((c1 - c0) * uint(t) + uint(COMPOSITE_WASH_MID / 2)) / uint(COMPOSITE_WASH_MID);
    }
    return composite_haze_level(c, float(w) / 255.0f, composite_wash_alpha12(t), n);
}

/// The finished colour of a pixel, as levels of the output (whole numbers), from its colour (levels: the mean of its
/// samples, or the rule's at a silhouette) at the upright device pixel `pixel` of the W × H CSS frame at the density
/// (`frame`: W, H, density): the screen steps that run, each composited in whole numbers. `grain`: the grain field's
/// grey and alpha at the pixel and fin's opacity byte, 0 where the grain does not run (the fields not bound); `cloud`:
/// the cloud shadows' pixel there, 0 for none; `rain`: the rain's opacity there (1/4096, composite_rain_alpha12), 0 for
/// none. The finished colour from the haze's and the vignette's opacities at the pixel (1/4096), however they were
/// found: the composite takes them from the frame's tables (gain 2), the twin from composite_steps_fx's own arithmetic.
inline float3 composite_steps_alphas(FrameLayers l, float3 levels, uint cloud, int rain, int haze, int wash,
                                     int vignette, uint3 grain) {
    float n = float(l.steps.y);
    uint steps = l.steps.x & COMPOSITE_DRAWN;
    float3 c = levels;
    if ((steps & COMPOSITE_CLOUD_SHADOWS) != 0u && cloud != 0u) {
        uint a8 = cloud >> 24u;
        uint a12 = (a8 * 1720u + 127u) / 255u;
        uint r = ((((cloud >> 16u) & 0xFFu) * 255u) + (a8 / 2u)) / a8;
        uint g = ((((cloud >> 8u) & 0xFFu) * 255u) + (a8 / 2u)) / a8;
        uint b = (((cloud & 0xFFu) * 255u) + (a8 / 2u)) / a8;
        float3 s = {composite_cloud_level(c.x, r < 255u ? r : 255u, a12),
                    composite_cloud_level(c.y, g < 255u ? g : 255u, a12),
                    composite_cloud_level(c.z, b < 255u ? b : 255u, a12)};
        c = s;
    }
    if ((steps & COMPOSITE_RAIN) != 0u && rain > 0) {
        float3 r = {composite_haze_level(c.x, float(COMPOSITE_RAIN_R) / 255.0f, rain, n),
                    composite_haze_level(c.y, float(COMPOSITE_RAIN_G) / 255.0f, rain, n),
                    composite_haze_level(c.z, float(COMPOSITE_RAIN_B) / 255.0f, rain, n)};
        c = r;
    }
    if ((steps & COMPOSITE_HAZE) != 0u && l.haze.w > 0.0f) {
        int a = haze;
        float3 h = {composite_haze_level(c.x, l.haze_colour.x, a, n), composite_haze_level(c.y, l.haze_colour.y, a, n),
                    composite_haze_level(c.z, l.haze_colour.z, a, n)};
        c = h;
    }
    if ((steps & COMPOSITE_WASH) != 0u) {
        float3 w = {composite_wash_level(c.x, COMPOSITE_WASH_R0, COMPOSITE_WASH_R1, wash, n),
                    composite_wash_level(c.y, COMPOSITE_WASH_G0, COMPOSITE_WASH_G1, wash, n),
                    composite_wash_level(c.z, COMPOSITE_WASH_B0, COMPOSITE_WASH_B1, wash, n)};
        c = w;
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

inline float3 composite_steps_fx(FrameLayers l, float3 levels, uint2 pixel, float3 frame, uint3 grain, uint cloud,
                                 int rain) {
    uint steps = l.steps.x & COMPOSITE_DRAWN;
    int haze = (steps & COMPOSITE_HAZE) != 0u && l.haze.w > 0.0f ? composite_haze_alpha12(l, pixel.y, frame.z) : 0;
    int wash = (steps & COMPOSITE_WASH) != 0u ? composite_wash_t(pixel, frame) : 0;
    int vignette = (steps & COMPOSITE_VIGNETTE) != 0u ? composite_vignette_alpha12(l, pixel, frame) : 0;
    return composite_steps_alphas(l, levels, cloud, rain, haze, wash, vignette, grain);
}

/// The colour to write for whole levels `levels` of the output: level / levels, which the UNORM attachment turns back
/// into that level.
inline float4 composite_output(float3 levels, float n) {
    float r = 1.0f / n;
    float4 o = {levels.x * r, levels.y * r, levels.z * r, 1.0f};
    return o;
}
