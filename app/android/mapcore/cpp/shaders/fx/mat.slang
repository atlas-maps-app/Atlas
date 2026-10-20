// The screen-wide fields and `mat` in the scene's shaders (plan-vector D5.4, D5.6, D10, K17a; CP/fx/FieldCache.hpp,
// CP/fx/Mat.hpp). Set 1 holds the fields and the byte tables (CP/frame/FrameLayout.hpp), which FieldCache provides:
//   noise_rgba (binding 7): mat's grey, the grain's premultiplied sRGB grey and alpha, the cloud shadows' red;
//   noise_rg (binding 8): the ripple's red and green; noise_r (binding 9): the glints' red;
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

/// Where each table begins in set 1's byte tables (CP/fx/FieldCache.hpp's table_).
static const uint FX_TABLE_TO_LINEAR = 0u;
static const uint FX_TABLE_TO_SRGB = 65536u;
static const uint FX_TABLE_MAT = 131072u;

[[vk::binding(7, 1)]] Sampler2D<float4> noise_rgba;
[[vk::binding(8, 1)]] Sampler2D<float4> noise_rg;
[[vk::binding(9, 1)]] Sampler2D<float4> noise_r;
[[vk::binding(10, 1)]] StructuredBuffer<uint> byte_tables;

/// Byte `index` of the byte tables.
uint fx_table_byte(uint index) {
    uint word = byte_tables[index >> 2u];
    return (word >> ((index & 3u) * 8u)) & 0xFFu;
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

/// The fields' bytes at the upright pixel `p`: noise_rgba's four, noise_rg's two, noise_r's one.
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

/// A colour (0 to 1, at 8-bit levels) after `mat` at the fragment at `position` (SV_Position) when `on` (the frame
/// block's FRAME_FIELDS, which the frame sets in full mode alone, once the fields are bound), else the colour itself.
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
[[vk::binding(1, 2)]] RWStructuredBuffer<uint2> fx_results;

/// Per probe: the seven bytes the fields hold at its pixel (rgba's four in x; rg's two and r's one in y).
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
    uint2 out = {a.x | (a.y << 8u) | (a.z << 16u) | (a.w << 24u), b.x | (b.y << 8u) | (c << 16u)};
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
    uint2 out = {g | (uint(m.r) << 8u) | (uint(m.g) << 16u) | (uint(m.b) << 24u), 0u};
    fx_results[id.x] = out;
}

#endif
