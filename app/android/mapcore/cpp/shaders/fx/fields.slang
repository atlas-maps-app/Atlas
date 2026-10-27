// The screen-wide noise fields on the GPU (plan-vector D10, N17; CP/fx/Fields.hpp, CP/fx/GpuFields.hpp): a block of a
// frame's eight fields, each pixel's noises fx/noise.slang's turbulence (every byte the CPU's where the device passes
// the byte check) and the bytes the core keeps of each as fx/FieldCache.cpp's make_fields keeps them, from the same
// tables and with the same arithmetic, packed as the GPU's three images hold them.
//
// fields_make_cs: pixel (x, y) of the block is the frame's filter pixel (left + x, top + y), its noises at the user point
// ((left + x + 1) · inv_x, (top + y + 1) · inv_y). Its three words (rgba, rg, r) go to `fields` at y · width + x of
// three planes of width · height words, one after the other: the images' bytes as fx::ScreenFields holds them.
//
// atlas: exact

/// The eight fields' specs in fx/Fields.hpp's order, four words each (base_x's bits, base_y's bits, the octaves, the
/// channels needed with fractal noise at bit 8), then their lattices one after the other, each fx/GpuNoise.hpp's
/// lattice_words.
[[vk::binding(0, 0)]] StructuredBuffer<uint> lattice;
/// make_fields' keep tables, four bytes a word, the first in the low byte: a channel premultiplied and unpremultiplied
/// again at each alpha (entry a · 256 + c), then the grain's grey written as premultiplied sRGB at each alpha
/// (65536 + a · 256 + grey).
[[vk::binding(1, 0)]] StructuredBuffer<uint> keep;
/// The block's three planes of words.
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> fields;

#define ATLAS_NOISE_FUNCTIONS
#include "fx/noise.slang"

struct FieldsPush {
    int4 block;  // its left, top, width and height in filter pixels
    float4 grid; // inv_x, inv_y (one over the filter scale, as floats); unused, unused
};

[[vk::push_constant]] ConstantBuffer<FieldsPush> push;

static const uint FIELDS_COUNT = 8u;
static const uint FIELDS_SPEC_WORDS = 4u;
static const uint FIELDS_LATTICE_WORDS = 2562u; // a lattice's words: its selector's 514, its gradients' 2048
static const uint FIELDS_GRAIN_SRGB = 65536u;   // the second keep table's first entry

/// Field `f`'s turbulence.
NoiseSpec fields_spec(uint f) {
    uint at = f * FIELDS_SPEC_WORDS;
    NoiseSpec s;
    s.base = FIELDS_COUNT * FIELDS_SPEC_WORDS + f * FIELDS_LATTICE_WORDS;
    s.frequency = float2(asfloat(lattice[at]), asfloat(lattice[at + 1u]));
    s.octaves = lattice[at + 2u];
    s.fractal = (lattice[at + 3u] >> 8u) != 0u;
    s.need = lattice[at + 3u] & 15u;
    return s;
}

/// Entry `i` of the keep tables.
uint fields_keep(uint i) {
    return (keep[i >> 2u] >> ((i & 3u) * 8u)) & 255u;
}

/// A noise pixel's channel at `shift` (16 red, 8 green, 0 blue) premultiplied and unpremultiplied again by its alpha.
uint fields_round_trip(uint p, uint shift) {
    return fields_keep(((p >> 24u) << 8u) | ((p >> shift) & 255u));
}

/// A noise pixel's channel at `shift` as it is: what the displacements read.
uint fields_channel(uint p, uint shift) {
    return (p >> shift) & 255u;
}

/// Float round half to even of 0 ≤ t < 2^22, exactly: adding 2^23 leaves no fraction (make_fields' rint_small).
float fields_rint(float t) {
    return (t + 8388608.0f) - 8388608.0f;
}

/// mat's grey: its colour matrix's row (.33 .33 .33) on the noise premultiplied and unpremultiplied again, fma(.33, b,
/// fma(.33, r, .33 · g)) (the row's zero terms leave it as it is), stored like Uint8ClampedArray.
uint fields_mat(uint p) {
    float r = float(fields_round_trip(p, 16u));
    float g = float(fields_round_trip(p, 8u));
    float b = float(fields_round_trip(p, 0u));
    float t = fma_emulated(0.33f, b, fma_emulated(0.33f, r, 0.33f * g));
    float v = !(t > 0.0f) ? 0.0f : (t >= 255.0f ? 255.0f : fields_rint(t));
    return uint(v);
}

/// The grain's two bytes: its grey (saturate 0 through vImage's integer path, 55 r + 183 g + 18 b, rounded) written as
/// premultiplied sRGB, then its alpha, the noise's.
uint fields_grain(uint p) {
    uint a = p >> 24u;
    uint r = fields_round_trip(p, 16u);
    uint g = fields_round_trip(p, 8u);
    uint b = fields_round_trip(p, 0u);
    uint grey = min((55u * r + 183u * g + 18u * b + 128u) >> 8u, 255u);
    return fields_keep(FIELDS_GRAIN_SRGB + ((a << 8u) | grey)) | (a << 8u);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void fields_make_cs(uint3 id : SV_DispatchThreadID) {
    if (int(id.x) >= push.block.z || int(id.y) >= push.block.w) {
        return;
    }
    float x = float(push.block.x + int(id.x) + 1) * push.grid.x;
    float y = float(push.block.y + int(id.y) + 1) * push.grid.y;
    // rgba: mat's grey, the grain's grey and alpha, the cloud shadows' red; rg: the ripple's red and green, the
    // crown's red and green; r: the glints' red, the crown tone's red, the leaf's red and green.
    uint rgba = 0u;
    uint rg = 0u;
    uint r = 0u;
    // One field at a time, in fx/Fields.hpp's order (a loop, so the module holds the turbulence once).
    [loop]
    for (uint f = 0u; f < FIELDS_COUNT; ++f) {
        uint p = noise_at(fields_spec(f), x, y);
        if (f == 0u) { // mat
            rgba = rgba | fields_mat(p);
        } else if (f == 1u) { // the grain
            rgba = rgba | (fields_grain(p) << 8u);
        } else if (f == 2u) { // the ripple
            rg = rg | fields_channel(p, 16u) | (fields_channel(p, 8u) << 8u);
        } else if (f == 3u) { // the glints
            r = r | fields_round_trip(p, 16u);
        } else if (f == 4u) { // the cloud shadows
            rgba = rgba | (fields_round_trip(p, 16u) << 24u);
        } else if (f == 5u) { // the crown
            rg = rg | (fields_channel(p, 16u) << 16u) | (fields_channel(p, 8u) << 24u);
        } else if (f == 6u) { // the crown tone
            r = r | (fields_round_trip(p, 16u) << 8u);
        } else { // the leaf
            r = r | (fields_channel(p, 16u) << 16u) | (fields_channel(p, 8u) << 24u);
        }
    }
    uint plane = uint(push.block.z) * uint(push.block.w);
    uint at = id.y * uint(push.block.z) + id.x;
    fields[at] = rgba;
    fields[plane + at] = rg;
    fields[2u * plane + at] = r;
}
