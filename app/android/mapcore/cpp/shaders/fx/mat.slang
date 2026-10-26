// The screen-wide fields and `mat` in the scene's shaders (plan-vector D5.4, D5.6, D10, K17a; CP/fx/FieldCache.hpp,
// CP/fx/Mat.hpp). Set 1 holds the fields and the byte tables (CP/frame/FrameLayout.hpp), which FieldCache provides:
//   noise_rgba (binding 7): mat's grey, the grain's premultiplied sRGB grey and alpha, the cloud shadows' red;
//   noise_rg (binding 8): the ripple's red and green, the crowns' red and green; noise_r (binding 9): the glints' red,
//   the crown tone's red;
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

/// The byte a UNORM8 channel holds.
uint fx_byte(float v) {
    return uint(v * 255.0f + 0.5f);
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
uint3 fx_crown(int2 p) {
    int3 at = {p.x, p.y, 0};
    float4 d = noise_rg.Load(at);
    uint3 b = {fx_byte(d.b), fx_byte(d.a), fx_byte(noise_r.Load(at).g)};
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

/// Per probe: the ten bytes the fields hold at its pixel (rgba's four in x, rg's four in y, r's two in z).
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
    uint4 out = {a.x | (a.y << 8u) | (a.z << 16u) | (a.w << 24u), b.x | (b.y << 8u) | (k.x << 16u) | (k.y << 24u),
                 c | (k.z << 8u), 0u};
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
