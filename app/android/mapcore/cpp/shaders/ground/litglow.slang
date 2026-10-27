// The lit streets' glow (passes/LitGlow.cpp; CP/fx/LitGlow.hpp, the CPU's reference the tests hold this stage to bit
// for bit): one of vImage's three square boxes of SvgFilterRaster's `litglow` (feGaussianBlur 2.6, both axes alike)
// over the filter's input, in the glow's layer's px (the view's upright device px and a margin about them), one word a
// texel (a r g b, premultiplied linear RGB, as the filter holds them). The first pass reads the layer (premultiplied
// sRGB, held in linear RGB as it is read: Core Graphics' conversion through the byte tables), the others the last
// pass's buffer. Each texel becomes the sum of the box around it, the input's edge texels repeated past it, divided
// once and rounded to the nearest level: (sum + side² / 2) / side² in whole numbers. A pass writes its region
// (push.region): the CPU works the regions out back from the composite's, each what the next pass reads, so no pass
// reads a texel left unwritten.
//
// A workgroup writes a tile of GLOW_TILE × GLOW_TILE texels: it holds the tile's texels and the half box around them
// (at most GLOW_HALF_MOST), sums each held row over the tile's columns (a window slid along it), then each column's row
// sums down four rows a thread.
//
// atlas: exact

#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"

/// The glow's layer, drawn and resolved: premultiplied sRGB.
[[vk::binding(0, 3)]] Texture2D<float4> layer;
[[vk::binding(1, 3)]] StructuredBuffer<uint> source;
[[vk::binding(2, 3)]] RWStructuredBuffer<uint> target;

struct GlowPush {
    uint4 size;  // x: the layer's width (the buffers' words a row); y: the box's side (odd); z: 1 for the first pass,
                 // which reads the layer; w: 0
    int4 input;  // the filter's input, [x0, x1) × [y0, y1): its edge texels repeat past it
    int4 region; // the texels the pass writes
};

[[vk::push_constant]] ConstantBuffer<GlowPush> push;

static const int GLOW_TILE = 16;      // passes/LitGlow.cpp's glow_tile
static const int GLOW_HALF_MOST = 16; // passes/LitGlow.cpp's glow_half_most
static const int GLOW_SPAN = GLOW_TILE + 2 * GLOW_HALF_MOST;
static const uint GLOW_THREADS = 64u;

/// The held texels, GLOW_SPAN to a row.
groupshared uint held[GLOW_SPAN * GLOW_SPAN];
/// The held rows' sums over the tile's columns, GLOW_TILE to a row: the first two channels in x, the last two in y,
/// 16 bits each (at most (2 · GLOW_HALF_MOST + 1) · 255).
groupshared uint2 sums[GLOW_SPAN * GLOW_TILE];

/// A word's four bytes, and four bytes as a word.
uint4 bytes_of(uint p) {
    uint4 c = {p & 0xFFu, (p >> 8u) & 0xFFu, (p >> 16u) & 0xFFu, p >> 24u};
    return c;
}
uint word_of(uint4 c) {
    return c.x | (c.y << 8u) | (c.z << 16u) | (c.w << 24u);
}

/// The input's texel at `p` (within it): the layer's, held in linear RGB (the first pass), or the last pass's.
uint texel_at(int2 p) {
    if (push.size.z != 0u) {
        float4 t = layer.Load(int3(p, 0));
        uint s = (fx_byte(t.a) << 24u) | (fx_byte(t.r) << 16u) | (fx_byte(t.g) << 8u) | fx_byte(t.b);
        return fx_to_linear_premultiplied(s);
    }
    return source[uint(p.y) * push.size.x + uint(p.x)];
}

/// The sums of held row `row` over the tile's column `column`, a channel each.
uint4 sum_at(int row, int column) {
    uint2 s = sums[row * GLOW_TILE + column];
    uint4 c = {s.x & 0xFFFFu, s.x >> 16u, s.y & 0xFFFFu, s.y >> 16u};
    return c;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void glow_box_cs(uint3 group : SV_GroupID, uint3 local : SV_GroupThreadID) {
    int2 tile = push.region.xy + int2(group.xy) * GLOW_TILE;
    int side = int(push.size.y);
    int half = side / 2;
    int span = GLOW_TILE + 2 * half;
    uint t = local.x;

    // the tile's texels and the half box around them, the input's edge repeated past it
    for (uint k = t; k < uint(span * span); k += GLOW_THREADS) {
        int row = int(k) / span;
        int column = int(k) % span;
        int2 p = clamp(tile - int2(half, half) + int2(column, row), push.input.xy, push.input.zw - 1);
        held[row * GLOW_SPAN + column] = texel_at(p);
    }
    GroupMemoryBarrierWithGroupSync();

    // each held row's sums over the tile's columns: column c's window is held columns c to c + side - 1
    for (uint row = t; row < uint(span); row += GLOW_THREADS) {
        int at = int(row) * GLOW_SPAN;
        uint4 s = {0u, 0u, 0u, 0u};
        for (int i = 0; i < side; ++i) {
            s = s + bytes_of(held[at + i]);
        }
        for (int c = 0; c < GLOW_TILE; ++c) {
            if (c > 0) {
                s = s + bytes_of(held[at + c + side - 1]) - bytes_of(held[at + c - 1]);
            }
            uint2 pair = {s.x | (s.y << 16u), s.z | (s.w << 16u)};
            sums[int(row) * GLOW_TILE + c] = pair;
        }
    }
    GroupMemoryBarrierWithGroupSync();

    // four rows of one column a thread: the column's sums down each row's window (the sums' rows r to r + side - 1),
    // divided once and rounded
    int column = int(t % uint(GLOW_TILE));
    int first_row = int(t / uint(GLOW_TILE)) * 4;
    uint area = uint(side * side);
    uint4 s = {0u, 0u, 0u, 0u};
    for (int j = 0; j < side; ++j) {
        s = s + sum_at(first_row + j, column);
    }
    for (int r = 0; r < 4; ++r) {
        int row = first_row + r;
        if (r > 0) {
            s = s + sum_at(row + side - 1, column) - sum_at(row - 1, column);
        }
        int2 p = tile + int2(column, row);
        if (p.x < push.region.z && p.y < push.region.w) {
            target[uint(p.y) * push.size.x + uint(p.x)] = word_of((s + area / 2u) / area);
        }
    }
}
