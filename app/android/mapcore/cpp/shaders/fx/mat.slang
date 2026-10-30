// The screen-wide fields and `mat` in the scene's shaders (plan-vector D5.4, D5.6, D10, K17a; CP/fx/FieldCache.hpp,
// CP/fx/Mat.hpp). Set 1 holds the fields and the byte tables (CP/frame/FrameLayout.hpp), which FieldCache provides:
//   noise_rgba (binding 7): mat's grey, the grain's premultiplied sRGB grey and alpha, the cloud shadows' red;
//   noise_rg (binding 8): the ripple's red and green, the crowns' red and green; noise_r (binding 9): the glints' red,
//   the crown tone's red, the leaves' red and green; noise_canopy (binding 14): the canopy's alpha, with its mipmaps
//   and a sampler that filters (CP/fx/Canopy.hpp);
//   byte_tables (binding 10): bytes packed four to a uint, little-endian: the to-linear table at 0, the to-sRGB table
//   at 65536, mat's opaque table at 131072, each indexed row · 256 + column.
// The fields are the frame's upright device pixels; fx_pixel finds a fragment's. Every module that reads a field or a
// table declares them here, once.
//
// `mat`: an opaque fragment of one of the renders' two `mat` groups (the objects group: walls, roofs, the buildings'
// parts; the ground's: areas and roads, a region's land cover) takes the colour the renders draw at its pixel, from
// the grey of mat's field there. The renders' result for an opaque pixel depends on its colour level and the grey
// alone, so it is a table, computed on the CPU with SvgFilterRaster's byte arithmetic (fx/Mat.cpp, held to the port on
// every pixel and grey) and read here: integer reads only, exact in any module, exact or not. A translucent fragment
// of a group takes the same factor on its colour before it blends; the renders apply mat to the group's composite
// instead, which differs only where translucent paint lies inside a group (D11, E2).
//
// A module takes the declarations and functions with `#define ATLAS_FX_FUNCTIONS` before it includes this file; built
// on its own, the file is the device check of the fields and of `mat` against the CPU's (CP/tests/fx/fields_vk_test.cpp).
#pragma once

#include "shared/camera.slang"
#include "shared/frame.slang"

/// Where each table begins in set 1's byte tables (CP/fx/FieldCache.hpp's table_): bytes, then 16-bit levels.
static const uint FX_TABLE_TO_LINEAR = 0u;
static const uint FX_TABLE_TO_SRGB = 65536u;
static const uint FX_TABLE_MAT = 131072u;
static const uint FX_TABLE_EDGE8 = 196608u;
static const uint FX_TABLE_EDGE10 = 197120u;
static const uint FX_TABLE_CROWN = 199168u;

[[vk::binding(7, 1)]] Sampler2D<float4> noise_rgba;
[[vk::binding(8, 1)]] Sampler2D<float4> noise_rg;
[[vk::binding(9, 1)]] Sampler2D<float4> noise_r;
[[vk::binding(10, 1)]] StructuredBuffer<uint> byte_tables;
[[vk::binding(14, 1)]] Sampler2D<float4> noise_canopy;

/// Byte `index` of the byte tables.
uint fx_table_byte(uint index) {
    uint word = byte_tables[index >> 2u];
    return (word >> ((index & 3u) * 8u)) & 0xFFu;
}

/// The 16-bit entry `index` of the byte tables' 16-bit tables beginning at byte `at` (little-endian).
uint fx_table_u16(uint at, uint index) {
    uint b = at + index * 2u;
    uint word = byte_tables[b >> 2u];
    return (word >> ((b & 2u) * 8u)) & 0xFFFFu;
}

/// vImage's premultiply of an unpremultiplied pixel (a r g b, a byte each): (c · a + 127) / 255 (CP/fx/ByteTables.hpp).
uint fx_premultiply(uint p) {
    uint a = p >> 24u;
    if (a == 255u) {
        return p;
    }
    if (a == 0u) {
        return 0u;
    }
    uint r = (((p >> 16u) & 0xFFu) * a + 127u) / 255u;
    uint g = (((p >> 8u) & 0xFFu) * a + 127u) / 255u;
    uint b = ((p & 0xFFu) * a + 127u) / 255u;
    return (a << 24u) | (r << 16u) | (g << 8u) | b;
}

/// A premultiplied pixel (a r g b) by its exact unpremultiplied colour in the colour space of the table at byte `table`
/// (FX_TABLE_TO_LINEAR or FX_TABLE_TO_SRGB), unpremultiplied: Core Graphics' conversion (bytes::unpremultiply_exact).
uint fx_unpremultiply_exact(uint p, uint table) {
    uint a = p >> 24u;
    if (a == 0u) {
        return 0u;
    }
    uint row = table + (a << 8u);
    return (a << 24u) | (fx_table_byte(row + ((p >> 16u) & 0xFFu)) << 16u) |
           (fx_table_byte(row + ((p >> 8u) & 0xFFu)) << 8u) | fx_table_byte(row + (p & 0xFFu));
}

/// A premultiplied sRGB pixel as a filter holds it in linear RGB, premultiplied (bytes::srgb_to_linear_premultiplied),
/// and a filter's premultiplied linear result written back as premultiplied sRGB.
uint fx_to_linear_premultiplied(uint p) {
    return fx_premultiply(fx_unpremultiply_exact(p, FX_TABLE_TO_LINEAR));
}
uint fx_to_srgb_premultiplied(uint p) {
    return fx_premultiply(fx_unpremultiply_exact(p, FX_TABLE_TO_SRGB));
}

/// Whether `mat` runs this frame: the fields are bound in full mode (FRAME_FIELDS) and the moment has weather (the
/// renders give the objects group and the ground's mat groups the filter only then: render/amsterdam.py:965, th['wx']).
/// The `on` the mat groups' fragment stages give mat_colour, and the composite's rule at the silhouettes.
bool mat_on(FrameBlock block) {
    return (block.info.x & FRAME_FIELDS) != 0u && theme_has(block.theme, THEME_WEATHER);
}

/// Whether `mat`'s grain colours the fills this frame: where mat_on says, unless the app's Look settings turn the grain
/// off (FRAME_NO_GRAIN). The `on` the mat groups' fragment stages give mat_colour, and the composite's rule at the
/// silhouettes.
bool mat_grain_on(FrameBlock block) {
    return mat_on(block) && (block.info.x & FRAME_NO_GRAIN) == 0u;
}

/// `mat`'s edge level (CP/fx/Mat.hpp's mat_edge_table) for an interior level `v` of an output of `levels` levels (255
/// or 1023): the colour the filter gives a pixel the objects group covers in part.
uint mat_edge_level(uint v, uint levels) {
    return fx_table_u16(levels == 255u ? FX_TABLE_EDGE8 : FX_TABLE_EDGE10, v);
}

/// The frame's upright device pixel under a fragment at SV_Position `position` (the output's pre-rotation undone): the
/// pixel the screen-wide fields index. A pixel's centre lies half a pixel from its edges, far beyond css_of_output's
/// rounding, so the floor is exact.
int2 fx_pixel(FrameCamera camera, float2 position) {
    float2 px = css_of_output(camera, position) * camera.frame.z;
    int2 p = {int(floor(px.x)), int(floor(px.y))};
    return p;
}

/// Index `v` of an axis of `n` texels mirrored into it: past either end the axis reads on as its mirror image (0 to n
/// - 1, then n - 1 back to 0, and so on), so a field read anywhere is continuous.
int fx_mirror(int v, int n) {
    int m = v % (2 * n);
    if (m < 0) {
        m = m + 2 * n;
    }
    return m < n ? m : (2 * n - 1) - m;
}

/// The fields' pixel `q` mirrored into them (fx_mirror on each axis): each field is a block of its noise the frame's
/// size, which a lookup anchored to an object or to the ground (a tree's crown, the water's streaks) may leave.
int2 fx_wrap(int2 q) {
    uint w = 0u;
    uint h = 0u;
    noise_rgba.GetDimensions(w, h);
    int2 m = {fx_mirror(q.x, int(w)), fx_mirror(q.y, int(h))};
    return m;
}

/// The byte a UNORM8 channel holds.
uint fx_byte(float v) {
    return uint(v * 255.0f + 0.5f);
}

/// The water's map (passes::water_map): the fields' point (device px) of the water's plane under upright device point
/// `p`, by its rows r0, r1 and r2, each step rounded on its own; its z 0 where the plane does not lie there (at and
/// above the horizon).
float3 fx_water_point(float3 r0, float3 r1, float3 r2, float2 p) {
    float w = ((r2.x * p.x) + (r2.y * p.y)) + r2.z;
    if (!(w < 0.0f)) {
        return float3(0.0f);
    }
    float u = ((r0.x * p.x) + (r0.y * p.y)) + r0.z;
    float v = ((r1.x * p.x) + (r1.y * p.y)) + r1.z;
    float3 q = {u / w, v / w, 1.0f};
    return q;
}

/// The fields' bytes at the upright pixel `p`: noise_rgba's four, the ripple's two (noise_rg's first), the glints' one
/// (noise_r's first), and the crowns' three (noise_rg's last two and noise_r's second: the displacement's red and
/// green, the tone's red).
uint4 fx_rgba(int2 p) {
    int3 at = {p.x, p.y, 0};
    float4 t = noise_rgba.Load(at);
    uint4 b = {fx_byte(t.r), fx_byte(t.g), fx_byte(t.b), fx_byte(t.a)};
    return b;
}
uint2 fx_rg(int2 p) {
    int3 at = {p.x, p.y, 0};
    float4 t = noise_rg.Load(at);
    uint2 b = {fx_byte(t.r), fx_byte(t.g)};
    return b;
}
uint fx_r(int2 p) {
    int3 at = {p.x, p.y, 0};
    return fx_byte(noise_r.Load(at).r);
}
/// The glints' red at the fields' point `q` (device px, anywhere: fx_wrap), between its four pixels' bytes as their
/// bilinear blend, each step rounded on its own, to the nearest byte.
uint fx_glints_red(float2 q) {
    float2 f = floor(q);
    int2 i = int2(f);
    float2 t = q - f;
    float a = float(fx_r(fx_wrap(i)));
    float b = float(fx_r(fx_wrap(i + int2(1, 0))));
    float c = float(fx_r(fx_wrap(i + int2(0, 1))));
    float d = float(fx_r(fx_wrap(i + int2(1, 1))));
    float top = a + ((b - a) * t.x);
    float bottom = c + ((d - c) * t.x);
    float r = top + ((bottom - top) * t.y);
    return uint(r + 0.5f);
}
/// The cloud shadows' alpha at the fields' point `q` (device px, anywhere: fx_wrap), between its four pixels' bytes as
/// fx_glints_red reads the glints' red: where the ground under a pixel lies in the fields (the composite's clouds on the
/// ground).
uint fx_cloud_alpha(float2 q) {
    float2 f = floor(q);
    int2 i = int2(f);
    float2 t = q - f;
    float a = float(fx_rgba(fx_wrap(i)).w);
    float b = float(fx_rgba(fx_wrap(i + int2(1, 0))).w);
    float c = float(fx_rgba(fx_wrap(i + int2(0, 1))).w);
    float d = float(fx_rgba(fx_wrap(i + int2(1, 1))).w);
    float top = a + ((b - a) * t.x);
    float bottom = c + ((d - c) * t.x);
    float r = top + ((bottom - top) * t.y);
    return uint(r + 0.5f);
}
/// The ripple's red and green at the fields' point `q` (device px, anywhere: fx_wrap), each between its four pixels'
/// bytes as fx_glints_red reads the glints' red: where the water under a pixel lies in the fields (the ripple on the
/// water).
uint2 fx_rg_between(float2 q) {
    float2 f = floor(q);
    int2 i = int2(f);
    float2 t = q - f;
    float2 a = float2(fx_rg(fx_wrap(i)));
    float2 b = float2(fx_rg(fx_wrap(i + int2(1, 0))));
    float2 c = float2(fx_rg(fx_wrap(i + int2(0, 1))));
    float2 d = float2(fx_rg(fx_wrap(i + int2(1, 1))));
    float2 top = a + ((b - a) * t.x);
    float2 bottom = c + ((d - c) * t.x);
    float2 r = top + ((bottom - top) * t.y);
    uint2 o = {uint(r.x + 0.5f), uint(r.y + 0.5f)};
    return o;
}
uint3 fx_crown(int2 p) {
    int3 at = {p.x, p.y, 0};
    float4 d = noise_rg.Load(at);
    uint3 b = {fx_byte(d.b), fx_byte(d.a), fx_byte(noise_r.Load(at).g)};
    return b;
}
/// The canopy's alpha at the upright pixel `p` (noise_canopy's first level): its matrix's, as the renders read it there.
uint fx_canopy(int2 p) {
    int3 at = {p.x, p.y, 0};
    return fx_byte(noise_canopy.Load(at).r);
}
/// The canopy's alpha (0 to 255) at the fields' point `q` (device px, anywhere: fx_wrap's mirror images), which a step
/// of a pixel across moves by `dx` and down by `dy`: between the field's texels and its mipmaps' levels, anisotropic
/// where the device allows (CP/fx/FieldCache.hpp's canopy_sampler), a texel's value at its pixel's point as
/// fx_glints_red takes it. Where the ground under a pixel lies in the field (the canopy on the ground): a far pixel
/// takes the mean of the canopy it sees.
float fx_canopy_on_ground(float2 q, float2 dx, float2 dy) {
    uint w = 0u;
    uint h = 0u;
    noise_rgba.GetDimensions(w, h);
    float2 size = {float(w), float(h)};
    return noise_canopy.SampleGrad((q + 0.5f) / size, dx / size, dy / size).r * 255.0f;
}
/// The leaves' displacement's red and green at the upright pixel `p` (noise_r's last two).
uint2 fx_leaf(int2 p) {
    int3 at = {p.x, p.y, 0};
    float4 d = noise_r.Load(at);
    uint2 b = {fx_byte(d.b), fx_byte(d.a)};
    return b;
}

/// The crown filter's colour of an opaque crown pixel (render/closeup.py's crown_filter; CP/fx/ByteTables.hpp's
/// crown_table): each level of `colour` (0 to 1, at 8-bit levels) at the crown tone's byte `red` there.
float3 crown_colour(float3 colour, uint red) {
    uint r = uint(floor(colour.r * 255.0f + 0.5f));
    uint g = uint(floor(colour.g * 255.0f + 0.5f));
    uint b = uint(floor(colour.b * 255.0f + 0.5f));
    float3 c = {float(fx_table_byte(FX_TABLE_CROWN + r * 256u + red)),
                float(fx_table_byte(FX_TABLE_CROWN + g * 256u + red)),
                float(fx_table_byte(FX_TABLE_CROWN + b * 256u + red))};
    return c * (1.0f / 255.0f);
}

/// `mat`'s grey at the upright pixel `p`.
uint mat_grey(int2 p) {
    int3 at = {p.x, p.y, 0};
    return fx_byte(noise_rgba.Load(at).r);
}

/// An opaque colour's levels (0 to 255, whole) after `mat` at grey `g`: the renders' levels.
float3 mat_levels(float3 levels, uint g) {
    uint r = uint(levels.r);
    uint gr = uint(levels.g);
    uint b = uint(levels.b);
    float3 out = {float(fx_table_byte(FX_TABLE_MAT + r * 256u + g)), float(fx_table_byte(FX_TABLE_MAT + gr * 256u + g)),
                  float(fx_table_byte(FX_TABLE_MAT + b * 256u + g))};
    return out;
}

/// A colour (0 to 1, at 8-bit levels) after `mat` at the fragment at `position` (SV_Position) when `on` (mat_on of the
/// frame block: the fields bound in full mode, and the moment with weather), else the colour itself.
float3 mat_colour(FrameCamera camera, float3 colour, float2 position, bool on) {
    if (!on) {
        return colour;
    }
    float3 levels = {floor(colour.r * 255.0f + 0.5f), floor(colour.g * 255.0f + 0.5f), floor(colour.b * 255.0f + 0.5f)};
    return mat_levels(levels, mat_grey(fx_pixel(camera, position))) * (1.0f / 255.0f);
}

#if !defined(ATLAS_FX_FUNCTIONS)

// The device checks (CP/tests/fx/fields_vk_test.cpp; L2 (c) for VK check, plan-vector D16).

/// A probe: an upright pixel of the fields and a colour's levels (r | g << 8 | b << 16).
struct FxProbe {
    int2 pixel;
    uint colour;
    uint unused;
};

[[vk::binding(0, 2)]] StructuredBuffer<FxProbe> fx_probes;
[[vk::binding(1, 2)]] RWStructuredBuffer<uint4> fx_results;

/// Per probe: the thirteen bytes the fields hold at its pixel (rgba's four in x, rg's four in y, r's four in z, the
/// canopy's in w).
[shader("compute")]
[numthreads(64, 1, 1)]
void fields_check(uint3 id : SV_DispatchThreadID) {
    uint count = 0u;
    uint stride = 0u;
    fx_probes.GetDimensions(count, stride);
    if (id.x >= count) {
        return;
    }
    int2 p = fx_probes[id.x].pixel;
    uint4 a = fx_rgba(p);
    uint2 b = fx_rg(p);
    uint c = fx_r(p);
    uint3 k = fx_crown(p);
    uint2 l = fx_leaf(p);
    uint4 out = {a.x | (a.y << 8u) | (a.z << 16u) | (a.w << 24u), b.x | (b.y << 8u) | (k.x << 16u) | (k.y << 24u),
                 c | (k.z << 8u) | (l.x << 16u) | (l.y << 24u), fx_canopy(p)};
    fx_results[id.x] = out;
}

/// Per probe: mat's grey at its pixel, then its colour's levels after mat (grey | r << 8 | g << 16 | b << 24).
[shader("compute")]
[numthreads(64, 1, 1)]
void mat_check(uint3 id : SV_DispatchThreadID) {
    uint count = 0u;
    uint stride = 0u;
    fx_probes.GetDimensions(count, stride);
    if (id.x >= count) {
        return;
    }
    FxProbe probe = fx_probes[id.x];
    uint g = mat_grey(probe.pixel);
    float3 levels = {float(probe.colour & 0xFFu), float((probe.colour >> 8u) & 0xFFu),
                     float((probe.colour >> 16u) & 0xFFu)};
    float3 m = mat_levels(levels, g);
    uint4 out = {g | (uint(m.r) << 8u) | (uint(m.g) << 16u) | (uint(m.b) << 24u), 0u, 0u, 0u};
    fx_results[id.x] = out;
}

#endif
