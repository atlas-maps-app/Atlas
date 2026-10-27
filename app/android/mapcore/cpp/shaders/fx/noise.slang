// WebKit's turbulence on the GPU (plan-vector N17, D10; CP/fx/Noise.hpp's color and fill, the port's
// Turbulence.colorWebKit), every byte the CPU's wherever binary32 addition and multiplication are correctly rounded
// (Vulkan's precision of OpFAdd, OpFSub and OpFMul). The port fuses four gradient products per channel per octave,
// `smooth` and libc++'s std::lerp; here each fused operation is probe/exact_probe.slang's fma_emulated (Dekker's
// product, TwoSum and one addition rounded to odd: correctly rounded from binary32 arithmetic alone), as no Android
// device has VK_KHR_shader_fma, and every other operation is rounded on its own (an exact module: NoContraction on
// each). CP/fx/GpuNoise.hpp's byte check holds a device's bytes to the CPU's before the core relies on them.
//
// noise_fill_cs: a block of one turbulence's filter pixels, pixel (x, y) the noise at the user point ((x + 1) · inv_x,
// (y + 1) · inv_y), its channels in `need` (the others 0, alpha 255), packed a r g b a word, a row of the block
// `stride` words from the last.
//
// A module that includes this one after `#define ATLAS_NOISE_FUNCTIONS` takes its functions alone, reading the
// lattices from a buffer `lattice` it declares before the include (fx/fields.slang: the screen fields' eight).
//
// atlas: exact

#define ATLAS_EXACT_FUNCTIONS
#include "probe/exact_probe.slang"

#if !defined(ATLAS_NOISE_FUNCTIONS)
/// The seed's lattice (fx::Lattice): its selector's 514 entries, then its gradients, channel c's entry i at
/// 514 + (c · 256 + i) · 2 (x) and the word after it (y), each a float's bits.
[[vk::binding(0, 0)]] StructuredBuffer<uint> lattice;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> pixels;

struct NoisePush {
    int4 block;  // its left, top, width and height in filter pixels
    float4 grid; // inv_x, inv_y (one over the filter scale, as floats), base_x, base_y
    uint4 spec;  // octaves; fractal (1) or turbulence (0); need (bits 0-3: r g b a); the rows' stride in words
};

[[vk::push_constant]] ConstantBuffer<NoisePush> push;
#endif

static const uint NOISE_SELECTOR = 514u;

/// A turbulence as noise_at reads it: where its lattice begins in `lattice`, its base frequency, its octaves, fractal
/// noise or turbulence, and the channels it makes (bits 0-3: r g b a).
struct NoiseSpec {
    uint base;
    float2 frequency;
    uint octaves;
    bool fractal;
    uint need;
};

float noise_gradient(uint base, uint c, uint entry, uint k) {
    return asfloat(lattice[base + NOISE_SELECTOR + (c * 256u + entry) * 2u + k]);
}

/// The spec's smooth step as the port fuses it: (t · t) · fma(−2, t, 3).
float noise_smooth(float t) {
    return (t * t) * fma_emulated(-2.0f, t, 3.0f);
}

/// libc++'s std::lerp(a, b, t) with its products fused as clang emits them (fx::lerp).
float noise_lerp(float a, float b, float t) {
    if ((a <= 0.0f && b >= 0.0f) || (a >= 0.0f && b <= 0.0f)) {
        return fma_emulated(t, b, (1.0f - t) * a);
    }
    if (t == 1.0f) {
        return b;
    }
    float x = fma_emulated(t, b - a, a);
    if ((t > 1.0f) == (b > a)) {
        return b < x ? x : b;
    }
    return x < b ? x : b;
}

/// One channel's byte from its sum: (sum + 1) / 2 for fractal noise, times 255, truncated, clamped to 0 to 255.
uint noise_byte(float sum, bool fractal) {
    float s = sum;
    if (fractal) {
        s = (s * 0.5f) + 0.5f;
    }
    return uint(clamp(int(s * 255.0f), 0, 255));
}

/// The noise `spec`'s pixel at the user point (x, y): a r g b, its channels in `need` alone (the others 0, alpha 255).
uint noise_at(NoiseSpec spec, float x, float y) {
    uint base = spec.base;
    float vx = x * spec.frequency.x;
    float vy = y * spec.frequency.y;
    float sums[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float inv_ratio = 1.0f; // 1 / ratio: the octave's division as a multiplication, exactly
    for (uint o = 0u; o < spec.octaves; ++o) {
        float px = vx + 4096.0f;
        int ix = int(px);
        float fx = px - float(ix);
        float py = vy + 4096.0f;
        int iy = int(py);
        float fy = py - float(iy);
        uint i = lattice[base + (uint(ix) & 255u)];
        uint j = lattice[base + (uint(ix + 1) & 255u)];
        uint by0 = uint(iy) & 255u;
        uint by1 = uint(iy + 1) & 255u;
        uint b00 = lattice[base + i + by0];
        uint b10 = lattice[base + j + by0];
        uint b01 = lattice[base + i + by1];
        uint b11 = lattice[base + j + by1];
        float sx = noise_smooth(fx);
        float sy = noise_smooth(fy);
        float fx1 = fx - 1.0f;
        float fy1 = fy - 1.0f;
        for (uint c = 0u; c < 4u; ++c) {
            if ((spec.need & (1u << c)) == 0u) {
                continue;
            }
            float u = fma_emulated(fx, noise_gradient(base, c, b00, 0u), fy * noise_gradient(base, c, b00, 1u));
            float v = fma_emulated(fx1, noise_gradient(base, c, b10, 0u), fy * noise_gradient(base, c, b10, 1u));
            float a = noise_lerp(u, v, sx);
            u = fma_emulated(fx, noise_gradient(base, c, b01, 0u), fy1 * noise_gradient(base, c, b01, 1u));
            v = fma_emulated(fx1, noise_gradient(base, c, b11, 0u), fy1 * noise_gradient(base, c, b11, 1u));
            float b = noise_lerp(u, v, sx);
            float n = noise_lerp(a, b, sy);
            sums[c] = sums[c] + (spec.fractal ? n : abs(n)) * inv_ratio;
        }
        vx = vx * 2.0f;
        vy = vy * 2.0f;
        inv_ratio = inv_ratio * 0.5f;
    }
    uint out = 0u;
    for (uint k = 0u; k < 4u; ++k) {
        uint byte = (spec.need & (1u << k)) == 0u ? (k == 3u ? 255u : 0u) : noise_byte(sums[k], spec.fractal);
        out = out | (byte << (k == 3u ? 24u : 16u - 8u * k));
    }
    return out;
}

#if !defined(ATLAS_NOISE_FUNCTIONS)
[shader("compute")]
[numthreads(8, 8, 1)]
void noise_fill_cs(uint3 id : SV_DispatchThreadID) {
    if (int(id.x) >= push.block.z || int(id.y) >= push.block.w) {
        return;
    }
    float x = float(push.block.x + int(id.x) + 1) * push.grid.x;
    float y = float(push.block.y + int(id.y) + 1) * push.grid.y;
    NoiseSpec spec;
    spec.base = 0u;
    spec.frequency = push.grid.zw;
    spec.octaves = push.spec.x;
    spec.fractal = push.spec.y != 0u;
    spec.need = push.spec.z;
    pixels[id.y * push.spec.w + id.x] = noise_at(spec, x, y);
}
#endif
