// The ground shadows' blur (passes/Shadows.cpp): one of vImage's three square boxes over the grid's input rectangle
// (shared/shadow_grid.slang), as the port's raster runs WebKit's equal-size boxes (fx/Blur.hpp's square_blur_cpu, the
// CPU's reference the tests hold it to bit for bit). Each texel becomes the sum of the box around it, the rectangle's
// edge texels repeated past it, divided once and rounded to the nearest level: (sum + box² / 2) / box², in whole
// numbers (computed as ((n + ½) · 1/box²) truncated, which equals it for every sum a box of at most 49 texels makes:
// tests/fx/square_blur_test.cpp). The first pass reads the mask the shadows' union was resolved into, 63 for each of
// its four samples covered, so its coverage n/4 is (255 · n + 2) / 4 of 255; the last writes the texels faded by the
// opacity as the port fades its bitmap, (a · opacity + ½) truncated in float. Texels lie four to a word, push.stride
// words a row.
//
// A workgroup writes a tile of 32 × 32 texels: it holds the tile's texels and the half box around them (at most
// HALF_MOST, so 80 × 80 held), sums each held row over the tile's columns (a window slid along it), then each column's
// row sums down the tile, four rows and four columns a thread, and writes each row's four texels as a word.
//
// atlas: exact

#include "shared/shadow_grid.slang"

[[vk::binding(0, 0)]] StructuredBuffer<ShadowGrid> grid;
/// The resolved mask (the first pass).
[[vk::binding(1, 0)]] Texture2D<float> mask;
/// The last pass's texels (the other passes).
[[vk::binding(2, 0)]] StructuredBuffer<uint> source;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> target;

struct BlurPush {
    uint first;  // 1: the first pass, which reads the mask
    uint last;   // 1: the last pass, which writes the texels faded by the opacity
    uint stride; // words a row of `source` and `target`
    uint pad;
};

[[vk::push_constant]] ConstantBuffer<BlurPush> push;

static const uint TILE = 32u;
static const uint HALF_MOST = 24u;              // ground.slang's SHADOW_HALF_MOST
static const uint SPAN = TILE + 2u * HALF_MOST; // the most rows and columns held
static const uint THREADS = 64u;

/// The held texels, four to a word, SPAN to a row.
groupshared uint held[SPAN * SPAN / 4u];
/// The held rows' sums over the tile's columns, two to a word, TILE to a row (at most 49 · 255).
groupshared uint sums[SPAN * TILE / 2u];

/// The texel at p, within the rectangle.
uint texel_at(int2 p) {
    if (push.first != 0u) {
        uint v = uint(mask.Load(int3(p, 0)) * 255.0f + 0.5f);
        uint n = (v + 31u) / 63u;
        return (n * 255u + 2u) / 4u;
    }
    uint w = source[uint(p.y) * push.stride + (uint(p.x) >> 2u)];
    return (w >> ((uint(p.x) & 3u) * 8u)) & 0xFFu;
}

uint held_at(uint row, uint column) {
    uint i = row * SPAN + column;
    return (held[i >> 2u] >> ((i & 3u) * 8u)) & 0xFFu;
}

uint sum_at(uint row, uint column) {
    uint i = row * TILE + column;
    return (sums[i >> 1u] >> ((i & 1u) * 16u)) & 0xFFFFu;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void blur_cs(uint3 group : SV_GroupID, uint3 local : SV_GroupThreadID) {
    ShadowGrid g = grid[0];
    int width = g.input.z;
    int height = g.input.w;
    int x0 = int(group.x * TILE);
    int y0 = int(group.y * TILE);
    if ((g.blur.w & SHADOW_GRID_DRAWN) == 0u || x0 >= width || y0 >= height) {
        return;
    }
    uint box = g.blur.x;
    uint half = g.blur.y;
    uint span = TILE + 2u * half;
    uint t = local.x;

    // the tile's texels and the half box around them, the rectangle's edge repeated past it
    uint row_words = (span + 3u) / 4u;
    for (uint k = t; k < span * row_words; k += THREADS) {
        uint row = k / row_words;
        uint at = (k % row_words) * 4u;
        int y = clamp(y0 - int(half) + int(row), 0, height - 1);
        uint word = 0u;
        for (uint i = 0u; i < 4u; ++i) {
            int x = clamp(x0 - int(half) + int(at + i), 0, width - 1);
            word = word | (texel_at(int2(x, y)) << (8u * i));
        }
        held[(row * SPAN + at) >> 2u] = word;
    }
    GroupMemoryBarrierWithGroupSync();

    // each held row's sums over the tile's columns: column c's window is held columns c to c + box - 1
    for (uint row = t; row < span; row += THREADS) {
        uint s = 0u;
        for (uint i = 0u; i < box; ++i) {
            s = s + held_at(row, i);
        }
        uint pair = 0u;
        for (uint c = 0u; c < TILE; ++c) {
            if (c > 0u) {
                s = s + held_at(row, c + box - 1u) - held_at(row, c - 1u);
            }
            if ((c & 1u) == 0u) {
                pair = s;
            } else {
                sums[(row * TILE + c) >> 1u] = pair | (s << 16u);
            }
        }
    }
    GroupMemoryBarrierWithGroupSync();

    // four columns and four rows a thread: each column's sums down its rows (row r's window is the sums' rows r to
    // r + box - 1), divided once, the last pass faded; each row's four texels a word
    uint column = (t % 8u) * 4u;
    uint first_row = (t / 8u) * 4u;
    if (x0 + int(column) >= width) {
        return;
    }
    uint4 s = uint4(0u, 0u, 0u, 0u);
    for (uint j = 0u; j < box; ++j) {
        s = s + uint4(sum_at(first_row + j, column), sum_at(first_row + j, column + 1u),
                      sum_at(first_row + j, column + 2u), sum_at(first_row + j, column + 3u));
    }
    for (uint r = 0u; r < 4u; ++r) {
        uint row = first_row + r;
        if (r > 0u) {
            s = s + uint4(sum_at(row + box - 1u, column), sum_at(row + box - 1u, column + 1u),
                          sum_at(row + box - 1u, column + 2u), sum_at(row + box - 1u, column + 3u));
            s = s - uint4(sum_at(row - 1u, column), sum_at(row - 1u, column + 1u), sum_at(row - 1u, column + 2u),
                          sum_at(row - 1u, column + 3u));
        }
        int y = y0 + int(row);
        if (y >= height) {
            break;
        }
        uint word = 0u;
        for (uint i = 0u; i < 4u; ++i) {
            uint q = uint((float(s[i] + g.blur.z) + 0.5f) * g.inv.x);
            if (push.last != 0u) {
                q = uint(float(q) * g.scale.z + 0.5f);
            }
            word = word | (min(q, 255u) << (8u * i));
        }
        target[uint(y) * push.stride + uint(x0 + int(column)) / 4u] = word;
    }
}
