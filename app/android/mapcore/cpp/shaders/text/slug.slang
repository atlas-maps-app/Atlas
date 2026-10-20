// The glyph shader (plan-vector D13, K13): the Slug algorithm (Eric Lengyel, "GPU-Centered Font Rendering Directly
// from Glyph Outlines", JCGT 6(2), 2017; its patent dedicated to the public domain), the coverage of a glyph at each
// pixel computed from its outline's quadratic curves, exact under any scale, rotation and shear, with no atlas of
// rasterised glyphs. text/SlugBands.cpp builds the curve and band data and holds the same arithmetic on the CPU
// (coverage), which tests/text/slug_vk_test.cpp holds this module to on lavapipe (the probe entry), and
// tests/text/ground_text_vk_test.cpp the glyphs the frame draws.
//
// The ground's text (passes/GroundText.cpp, the scene subpass's translucent slot): a glyph is one quad over its em box,
// dilated in the vertex stage by half a device pixel each way (through the inverse of the glyph's em-to-pixel map, so
// it holds under any affine map), placed by its run's matrix in CSS px (the ground's affine at the run's anchor, D13)
// and drawn at its painter slot's depth (painter.slang) with the depth test on and writes off; the fragment stage casts
// a ray to +x through the sample's horizontal band and one to +y through its vertical band, each crossing counted by how
// far it lies from the sample within half a pixel (pixels per em from the derivatives of the em coordinate), the two
// combined by how near their nearest crossings are; nonzero fill. The coverage goes through the face's 256-entry table
// (calibrated against Skia's text on the emulator, identity until then) and scales the glyph's premultiplied colour.
//
// The glyphs' data are set 1's (frame/FrameLayout.hpp's glyph_curves and glyph_bands, which passes/GroundText.hpp's
// GlyphGlobals fills): the curves (two points a texel, em units, rows of 4096 texels) and the bands (a texel's two 16-bit
// values packed low first, the same rows). The frame slot's glyphs and the coverage tables are the pass's own set (2).

#include "shared/frame.slang"
#include "shared/painter.slang"

/// One glyph to draw: its em-to-CSS map, its box and bands, its colour, its table and slot.
struct GlyphDraw {
    float4 row0;    // CSS x = (row0.x, row0.y) · em + row0.z
    float4 row1;    // CSS y = (row1.x, row1.y) · em + row1.z
    float4 box;     // the em box: x0, y0, x1, y1
    float4 bands;   // a sample's band indices: em · (x, y) + (z, w), truncated and clamped
    uint4 location; // the glyph's first band texel (x, y); its vertical and horizontal bands minus one
    float4 colour;  // premultiplied: the fill at its opacity and its fade
    uint4 info;     // x: the face's coverage table; y: its painter slot
};

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(11, 1)]] StructuredBuffer<float4> glyph_curves;
[[vk::binding(12, 1)]] StructuredBuffer<uint> glyph_bands;
[[vk::binding(0, 2)]] StructuredBuffer<GlyphDraw> glyph_draws;
[[vk::binding(1, 2)]] StructuredBuffer<float> coverage_tables; // 256 entries a face

static const uint log_band_width = 12;

struct GlyphOut {
    float4 position : SV_Position;
    float2 em : TEXCOORD0;
    nointerpolation uint glyph : TEXCOORD1;
};

static const float2 slug_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
GlyphOut ground_text_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    GlyphDraw g = glyph_draws[instance];
    // Half a device pixel each way, in em units: the inverse map's rows bound how far half a pixel reaches along em x
    // and y; the map to device px is the density times the CSS one.
    float density = frame.camera.frame.z;
    float det = g.row0.x * g.row1.y - g.row0.y * g.row1.x;
    float2 margin = 0.5f * float2(abs(g.row1.y) + abs(g.row0.y), abs(g.row1.x) + abs(g.row0.x)) / (abs(det) * density);
    float2 c = slug_corners[vertex];
    float2 em = lerp(g.box.xy - margin, g.box.zw + margin, c);
    float2 css = float2(dot(g.row0.xy, em) + g.row0.z, dot(g.row1.xy, em) + g.row1.z);
    GlyphOut o;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(g.info.y));
    o.em = em;
    o.glyph = instance;
    return o;
}

/// HLSL's saturate with a NaN at 0, as SlugBands.cpp's: a root of a curve flat across the ray counts for nothing.
float slug_saturate(float v) {
    return v > 0.0f ? (v < 1.0f ? v : 1.0f) : 0.0f;
}

/// Which of a quadratic's roots cross the ray (bit 0 the first, bit 8 the second), from the control points' signs
/// across it: Slug's table 0x2E74.
uint slug_root_code(float c1, float c2, float c3) {
    uint i1 = asuint(c1) >> 31u;
    uint i2 = asuint(c2) >> 30u;
    uint i3 = asuint(c3) >> 29u;
    uint shift = (i2 & 2u) | (i1 & ~2u);
    shift = (i3 & 4u) | (shift & ~4u);
    return (0x2E74u >> shift) & 0x0101u;
}

/// Where along the ray (u) the curve crosses it (v = 0), for its two roots: v(t) = a t^2 - 2 b t + p1, the
/// discriminant clamped at zero, a nearly linear v solved as a line.
float2 slug_solve(float2 p1, float2 p2, float2 p3) {
    float2 a = p1 - p2 * 2.0f + p3;
    float2 b = p1 - p2;
    float ra = 1.0f / a.y;
    float rb = 0.5f / b.y;
    float d = sqrt(max(b.y * b.y - a.y * p1.y, 0.0f));
    float t1 = (b.y - d) * ra;
    float t2 = (b.y + d) * ra;
    if (abs(a.y) < 1.0f / 65536.0f) {
        t1 = p1.y * rb;
        t2 = t1;
    }
    return float2((a.x * t1 - b.x * 2.0f) * t1 + p1.x, (a.x * t2 - b.x * 2.0f) * t2 + p1.x);
}

/// A band texel's two values; nothing past the buffer (set 1's placeholder until GlyphGlobals gives the bands: a glyph
/// then has no curves, and draws nothing).
uint2 slug_band_texel(uint2 at) {
    uint n;
    uint stride;
    glyph_bands.GetDimensions(n, stride);
    uint i = (at.y << log_band_width) + at.x;
    uint t = i < n ? glyph_bands[i] : 0u;
    return uint2(t & 0xFFFFu, t >> 16u);
}

/// A curve texel: two points; nothing past the buffer.
float4 slug_curve_texel(uint2 at) {
    uint n;
    uint stride;
    glyph_curves.GetDimensions(n, stride);
    uint i = (at.y << log_band_width) + at.x;
    return i < n ? glyph_curves[i] : float4(0.0f, 0.0f, 0.0f, 0.0f);
}

/// A band list's texel, wrapping into the next rows.
uint2 slug_band_location(uint2 glyph, uint offset) {
    uint x = glyph.x + offset;
    return uint2(x & ((1u << log_band_width) - 1u), glyph.y + (x >> log_band_width));
}

/// A sample's band along one axis: the transformed coordinate truncated, clamped to the bands (NaN at 0).
uint slug_band(float v, uint last) {
    float top = float(last) + 0.5f;
    float c = v > 0.0f ? (v < top ? v : top) : 0.0f;
    return uint(c);
}

/// One ray's coverage and weight over a band (horizontal: the ray to +x, its curves sorted by decreasing x).
float2 slug_ray(uint2 glyph, uint2 header, float2 sample, float pixels_per_em, bool horizontal) {
    float coverage = 0.0f;
    float weight = 0.0f;
    for (uint k = 0; k < header.x; ++k) {
        uint2 at = slug_band_texel(slug_band_location(glyph, header.y + k));
        float4 p12 = slug_curve_texel(at) - float4(sample, sample);
        float2 p3 = slug_curve_texel(at + uint2(1, 0)).xy - sample;
        // Along the ray: x for the horizontal one, y for the vertical; across it the other.
        float2 q1 = horizontal ? p12.xy : p12.yx;
        float2 q2 = horizontal ? p12.zw : p12.wz;
        float2 q3 = horizontal ? p3 : p3.yx;
        if (max(max(q1.x, q2.x), q3.x) * pixels_per_em < -0.5f) break;
        uint code = slug_root_code(q1.y, q2.y, q3.y);
        if (code != 0u) {
            float2 r = slug_solve(q1, q2, q3) * pixels_per_em;
            // The horizontal ray counts a downward crossing up and an upward one down; the vertical one mirrored.
            float s = horizontal ? 1.0f : -1.0f;
            if ((code & 1u) != 0u) {
                coverage += s * slug_saturate(r.x + 0.5f);
                weight = max(weight, slug_saturate(1.0f - abs(r.x) * 2.0f));
            }
            if (code > 1u) {
                coverage -= s * slug_saturate(r.y + 0.5f);
                weight = max(weight, slug_saturate(1.0f - abs(r.y) * 2.0f));
            }
        }
    }
    return float2(coverage, weight);
}

/// Slug's coverage of glyph `g` at the em sample `em`, a pixel being `pixels_per_em` along em x and y.
float slug_coverage(GlyphDraw g, float2 em, float2 pixels_per_em) {
    uint2 glyph = g.location.xy;
    uint vband = slug_band(em.x * g.bands.x + g.bands.z, g.location.z);
    uint hband = slug_band(em.y * g.bands.y + g.bands.w, g.location.w);
    uint2 hheader = slug_band_texel(uint2(glyph.x + hband, glyph.y));
    uint2 vheader = slug_band_texel(uint2(glyph.x + g.location.w + 1u + vband, glyph.y));
    float2 x = slug_ray(glyph, hheader, em, pixels_per_em.x, true);
    float2 y = slug_ray(glyph, vheader, em, pixels_per_em.y, false);
    // The rays weighted by how near their crossings are; where neither is near, the smaller; nonzero fill.
    float weighted = abs(x.x * x.y + y.x * y.y) / max(x.y + y.y, 1.0f / 65536.0f);
    return slug_saturate(max(weighted, min(abs(x.x), abs(y.x))));
}

/// The face's table at `coverage`, between its two nearest entries.
float slug_table(uint table, float coverage) {
    float f = coverage * 255.0f;
    uint i = min(uint(f), 254u);
    float a = coverage_tables[table * 256u + i];
    float b = coverage_tables[table * 256u + i + 1u];
    return lerp(a, b, f - float(i));
}

[shader("fragment")]
float4 ground_text_fs(GlyphOut v) : SV_Target0 {
    GlyphDraw g = glyph_draws[v.glyph];
    float2 pixels_per_em = 1.0f / fwidth(v.em);
    float coverage = slug_table(g.info.x, slug_coverage(g, v.em, pixels_per_em));
    return g.colour * coverage;
}

/// The coverage alone at the samples of a probe (tests/text/slug_vk_test.cpp): per probe its glyph, its em sample and
/// its pixels per em, the GPU's arithmetic without the rasteriser's derivatives.
struct SlugProbe {
    float4 sample; // em x, em y, pixels per em along x and y
    uint4 glyph;   // x: the glyph
};

[[vk::binding(2, 2)]] StructuredBuffer<SlugProbe> probes;
[[vk::binding(3, 2)]] RWStructuredBuffer<float> probed;

[shader("compute")]
[numthreads(64, 1, 1)]
void slug_probe(uint3 id : SV_DispatchThreadID) {
    uint n;
    uint stride;
    probes.GetDimensions(n, stride);
    if (id.x >= n) return;
    SlugProbe p = probes[id.x];
    probed[id.x] = slug_coverage(glyph_draws[p.glyph.x], p.sample.xy, p.sample.zw);
}
