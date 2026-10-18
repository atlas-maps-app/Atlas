// The glyph shader (plan-vector D13, K13): the Slug algorithm (Eric Lengyel, "GPU-Centered Font Rendering Directly
// from Glyph Outlines", JCGT 6(2), 2017; its patent dedicated to the public domain), the coverage of a glyph at each
// pixel computed from its outline's quadratic curves, exact under any scale, rotation and shear, with no atlas of
// rasterised glyphs. text/SlugBands.cpp builds the two textures and holds the same arithmetic on the CPU (coverage),
// which tests/text/slug_vk_test.cpp holds this module to on lavapipe.
//
// A glyph is drawn as one quad over its em box, dilated in the vertex stage by half a pixel each way (through the
// inverse of the glyph's em-to-pixel map, so it holds under any affine map); the fragment stage casts a ray to +x
// through the sample's horizontal band and one to +y through its vertical band, each crossing counted by how far it
// lies from the sample within half a pixel (pixels per em from the derivatives of the em coordinate), the two combined
// by how near their nearest crossings are; nonzero fill. The coverage goes through the face's 256-entry table
// (calibrated against Skia's text on the emulator, identity until then) and scales the glyph's premultiplied colour.

/// One glyph to draw: its em-to-pixel map, its box and bands, its colour.
struct SlugGlyph {
    float4 row0;     // device px = (row0.x, row0.y) · em + row0.z
    float4 row1;     // device py = (row1.x, row1.y) · em + row1.z
    float4 box;      // the em box: x0, y0, x1, y1
    float4 bands;    // a sample's band indices: em · (x, y) + (z, w), truncated and clamped
    uint4 location;  // the glyph's first texel in the band texture (x, y); its vertical and horizontal bands minus one
    float4 colour;   // premultiplied: the fill at its opacity
    uint4 table;     // x: the face's coverage table
};

struct SlugFrame {
    float4 screen;   // the target's pixels (x, y) and their reciprocals (z, w)
};

[[vk::binding(0, 0)]] StructuredBuffer<SlugGlyph> glyphs;
[[vk::binding(1, 0)]] [[vk::image_format("rgba32f")]] Texture2D<float4> curves; // rows of 4096 texels
[[vk::binding(2, 0)]] [[vk::image_format("unknown")]] Texture2D<uint4> bands;   // RG16UI, rows of 4096 texels
[[vk::binding(3, 0)]] StructuredBuffer<float> tables; // 256 entries a face
[[vk::push_constant]] ConstantBuffer<SlugFrame> frame;

static const uint log_band_width = 12;

struct SlugOut {
    float4 position : SV_Position;
    float2 em : TEXCOORD0;
    nointerpolation uint glyph : TEXCOORD1;
};

static const float2 slug_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
SlugOut slug_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    SlugGlyph g = glyphs[instance];
    // Half a pixel each way, in em units: the inverse map's rows bound how far half a pixel reaches along em x and y.
    float det = g.row0.x * g.row1.y - g.row0.y * g.row1.x;
    float2 margin = 0.5f * float2(abs(g.row1.y) + abs(g.row0.y), abs(g.row1.x) + abs(g.row0.x)) / abs(det);
    float2 c = slug_corners[vertex];
    float2 em = lerp(g.box.xy - margin, g.box.zw + margin, c);
    float2 px = float2(dot(g.row0.xy, em) + g.row0.z, dot(g.row1.xy, em) + g.row1.z);
    SlugOut o;
    o.position = float4(px * frame.screen.zw * 2.0f - 1.0f, 0.0f, 1.0f);
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

/// A band list's texel, wrapping into the next rows.
int2 slug_band_location(uint2 glyph, uint offset) {
    uint x = glyph.x + offset;
    return int2(int(x & ((1u << log_band_width) - 1u)), int(glyph.y + (x >> log_band_width)));
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
        uint2 at = bands.Load(int3(slug_band_location(glyph, header.y + k), 0)).xy;
        float4 p12 = curves.Load(int3(int2(at), 0)) - float4(sample, sample);
        float2 p3 = curves.Load(int3(int2(at) + int2(1, 0), 0)).xy - sample;
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
float slug_coverage(SlugGlyph g, float2 em, float2 pixels_per_em) {
    uint2 glyph = g.location.xy;
    uint vband = slug_band(em.x * g.bands.x + g.bands.z, g.location.z);
    uint hband = slug_band(em.y * g.bands.y + g.bands.w, g.location.w);
    uint2 hheader = bands.Load(int3(int(glyph.x + hband), int(glyph.y), 0)).xy;
    uint2 vheader = bands.Load(int3(int(glyph.x + g.location.w + 1u + vband), int(glyph.y), 0)).xy;
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
    float a = tables[table * 256u + i];
    float b = tables[table * 256u + i + 1u];
    return lerp(a, b, f - float(i));
}

[shader("fragment")]
float4 slug_fs(SlugOut v) : SV_Target0 {
    SlugGlyph g = glyphs[v.glyph];
    float2 pixels_per_em = 1.0f / fwidth(v.em);
    float coverage = slug_table(g.table.x, slug_coverage(g, v.em, pixels_per_em));
    return g.colour * coverage;
}

/// The coverage alone at the samples of a probe (tests/text/slug_vk_test.cpp): per probe its glyph, its em sample and
/// its pixels per em, the GPU's arithmetic without the rasteriser's derivatives.
struct SlugProbe {
    float4 sample;  // em x, em y, pixels per em along x and y
    uint4 glyph;    // x: the glyph
};

[[vk::binding(4, 0)]] StructuredBuffer<SlugProbe> probes;
[[vk::binding(5, 0)]] RWStructuredBuffer<float> probed;

[shader("compute")]
[numthreads(64, 1, 1)]
void slug_probe(uint3 id : SV_DispatchThreadID) {
    uint n;
    uint stride;
    probes.GetDimensions(n, stride);
    if (id.x >= n) return;
    SlugProbe p = probes[id.x];
    probed[id.x] = slug_coverage(glyphs[p.glyph.x], p.sample.xy, p.sample.zw);
}
