// The water's ripple (passes/Ripple.cpp; CP/fx/Ripple.hpp, the CPU's reference the tests hold these stages to bit for
// bit): SvgFilterRaster's `ripple` over the reflections' layer, in the frame's upright device px, one word a pixel
// (a r g b, premultiplied; linear RGB between the stages, as the filter holds them):
//   - ripple_displace_cs: each pixel takes the layer's pixel the ripple's field moves it to, in linear RGB, or nothing
//     where that lies outside the frame. The field's red and green each move it by its entry in the frame's table of
//     the 256 offsets (int(fma(float(abs / 255), c, float(0.5 − abs · 0.5))), made on the CPU with an exact fma), so
//     no device's own fma decides a pixel;
//   - ripple_square_cs: one of vImage's three square boxes, the frame's edge texels repeated past it, each sum divided
//     once and rounded, (sum + side² / 2) / side² in whole numbers;
//   - ripple_line_cs: one of WebKit's separable box passes along x or y, the texels past the frame transparent, each
//     sum times the box's 1 / size in single precision and truncated.
// Each stage reads `source` (the displacement: `layer`) and writes `target` over its region (push.region): the CPU
// works them out back from the result's, each what the next stage reads, so no stage reads a pixel left unwritten,
// and the layer is drawn over its content alone; the pass binds the two buffers both ways.
//
// atlas: exact

#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"

/// The ground's words of the frame (the offsets' table).
[[vk::binding(0, 2)]] StructuredBuffer<uint> words;
/// The reflections' layer, drawn and resolved: premultiplied sRGB.
[[vk::binding(0, 3)]] Texture2D<float4> layer;
[[vk::binding(1, 3)]] StructuredBuffer<uint> source;
[[vk::binding(2, 3)]] RWStructuredBuffer<uint> target;

struct RipplePush {
    uint4 size;  // x, y: the frame's upright width and height (device px); z: the offsets' table's first word in `words`
    uint4 box;   // ripple_square_cs: x the box's side (odd); ripple_line_cs: x the axis (0 x, 1 y), y its left, z right
    float4 inv;  // ripple_line_cs: x 1 / (left + right), as the CPU's float
    int4 region; // the pixels the stage writes, [x0, x1) × [y0, y1): what the next stage reads of it
    int4 layer;  // ripple_displace_cs: the layer's drawn box, its content (transparent beyond it)
};

[[vk::push_constant]] ConstantBuffer<RipplePush> push;

/// The layer's pixel at `p` as a r g b bytes.
uint layer_at(int2 p) {
    float4 t = layer.Load(int3(p, 0));
    return (fx_byte(t.a) << 24u) | (fx_byte(t.r) << 16u) | (fx_byte(t.g) << 8u) | fx_byte(t.b);
}

/// A word's four bytes, and four bytes as a word.
uint4 bytes_of(uint p) {
    uint4 c = {p & 0xFFu, (p >> 8u) & 0xFFu, (p >> 16u) & 0xFFu, p >> 24u};
    return c;
}
uint word_of(uint4 c) {
    return c.x | (c.y << 8u) | (c.z << 16u) | (c.w << 24u);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void ripple_displace_cs(uint3 id : SV_DispatchThreadID) {
    int2 at = push.region.xy + int2(id.xy);
    if (at.x >= push.region.z || at.y >= push.region.w) {
        return;
    }
    uint2 rg = fx_rg(at);
    int sx = at.x + asint(words[push.size.z + rg.x]);
    int sy = at.y + asint(words[push.size.z + rg.y]);
    uint p = 0u;
    // the frame's layer is transparent beyond its drawn box
    if (sx >= push.layer.x && sy >= push.layer.y && sx < push.layer.z && sy < push.layer.w) {
        p = fx_to_linear_premultiplied(layer_at(int2(sx, sy)));
    }
    target[uint(at.y) * push.size.x + uint(at.x)] = p;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void ripple_square_cs(uint3 id : SV_DispatchThreadID) {
    int w = int(push.size.x);
    int h = int(push.size.y);
    int2 at = push.region.xy + int2(id.xy);
    if (at.x >= push.region.z || at.y >= push.region.w) {
        return;
    }
    int half = int(push.box.x / 2u);
    uint4 sum = {0u, 0u, 0u, 0u};
    for (int dy = -half; dy <= half; ++dy) {
        int y = clamp(at.y + dy, 0, h - 1);
        for (int dx = -half; dx <= half; ++dx) {
            int x = clamp(at.x + dx, 0, w - 1);
            sum = sum + bytes_of(source[uint(y) * push.size.x + uint(x)]);
        }
    }
    uint area = push.box.x * push.box.x;
    uint4 m = (sum + area / 2u) / area;
    target[uint(at.y) * push.size.x + uint(at.x)] = word_of(m);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void ripple_line_cs(uint3 id : SV_DispatchThreadID) {
    int w = int(push.size.x);
    int h = int(push.size.y);
    int2 p = push.region.xy + int2(id.xy);
    if (p.x >= push.region.z || p.y >= push.region.w) {
        return;
    }
    bool along_x = push.box.x == 0u;
    int length = along_x ? w : h;
    int at = along_x ? p.x : p.y;
    int left = int(push.box.y);
    int right = int(push.box.z);
    uint4 sum = {0u, 0u, 0u, 0u};
    for (int q = at - left; q < at + right; ++q) {
        if (q < 0 || q >= length) {
            continue;
        }
        uint x = along_x ? uint(q) : uint(p.x);
        uint y = along_x ? uint(p.y) : uint(q);
        sum = sum + bytes_of(source[y * push.size.x + x]);
    }
    float inv = push.inv.x;
    uint4 m = {uint(float(sum.x) * inv), uint(float(sum.y) * inv), uint(float(sum.z) * inv), uint(float(sum.w) * inv)};
    target[uint(p.y) * push.size.x + uint(p.x)] = word_of(m);
}
