// The exact probe (plan-vector T6, package K0): a correctly rounded fused multiply-add built from binary32 add and
// multiply alone, Dekker's exact product, Knuth's TwoSum and one addition rounded to odd (S. Boldo and G. Melquiond,
// "Emulation of a FMA and correctly rounded sums: proved algorithms using rounding to odd", IEEE Transactions on
// Computers 57(4), 2008). The GPU noise path uses it where a device lacks VK_KHR_shader_fma (plan-vector N17, D10).
// It is exact only if no operation is contracted or reassociated, so this is an exact module: it compiles with
// -fp-mode precise and tools/nocontract checks that every float operation carries NoContraction.
//
// The functions before the entry point are shared: tests/base/hlsl_test.cpp compiles them as C++ through
// base/hlsl.hpp and holds them to std::fma, and tests/base/vulkan_vk_test.cpp runs the entry point on lavapipe against
// std::fma: as Slang's own SPIR-V, through Slang's GLSL route, and with OpFmaKHR where the driver has it. A module
// takes the functions alone with `#define ATLAS_EXACT_FUNCTIONS` before it includes this file (fx/noise.slang).
//
// atlas: variant fmakhr ATLAS_FMA_KHR
// atlas: glsl-route fma_probe:compute
#pragma once

struct FloatPair {
    float hi;
    float lo;
};

// a + b = hi + lo exactly (Knuth's TwoSum).
inline FloatPair two_sum(float a, float b) {
    FloatPair r;
    r.hi = a + b;
    float bb = r.hi - a;
    r.lo = (a - (r.hi - bb)) + (b - bb);
    return r;
}

// a = hi + lo, each half with at most 12 significant bits (Veltkamp's split; 4097 = 2^12 + 1).
inline FloatPair split(float a) {
    float t = 4097.0f * a;
    FloatPair r;
    r.hi = t - (t - a);
    r.lo = a - r.hi;
    return r;
}

// a · b = hi + lo exactly (Dekker's product), without a fused operation.
inline FloatPair two_product(float a, float b) {
    FloatPair r;
    r.hi = a * b;
    FloatPair x = split(a);
    FloatPair y = split(b);
    r.lo = (((x.hi * y.hi - r.hi) + x.hi * y.lo) + x.lo * y.hi) + x.lo * y.lo;
    return r;
}

// a + b rounded to odd: the sum when it is representable, else the neighbour whose last significand bit is 1.
inline float add_round_to_odd(float a, float b) {
    FloatPair s = two_sum(a, b);
    uint bits = asuint(s.hi);
    if (s.lo != 0.0f && (bits & 1u) == 0u) {
        // One unit in the last place away from zero when the error has the sum's sign, else toward zero.
        bits = (s.lo > 0.0f) == (s.hi > 0.0f) ? bits + 1u : bits - 1u;
    }
    return asfloat(bits);
}

// a · b + c rounded once, for finite operands below 2^114 in magnitude whose nonzero product is at least 2^-100 (so
// no error term underflows) and whose result does not overflow.
inline float fma_emulated(float a, float b, float c) {
    FloatPair p = two_product(a, b);
    FloatPair t = two_sum(c, p.hi);
    return t.hi + add_round_to_odd(t.lo, p.lo);
}

#if !defined(__cplusplus) && !defined(ATLAS_EXACT_FUNCTIONS)

struct ExactPush {
    uint count;
};

[[vk::push_constant]] ConstantBuffer<ExactPush> push;
[[vk::binding(0, 0)]] StructuredBuffer<float4> operands; // a, b, c and an unused lane
[[vk::binding(1, 0)]] RWStructuredBuffer<float> results;

#if defined(ATLAS_FMA_KHR)
// The device's own correctly rounded fused multiply-add (VK_KHR_shader_fma).
inline float fma_device(float a, float b, float c) {
    return spirv_asm {
        OpExtension "SPV_KHR_fma";
        OpCapability FMAKHR;
        result:$$float = OpFmaKHR $a $b $c
    };
}
#else
inline float fma_device(float a, float b, float c) { return fma_emulated(a, b, c); }
#endif

[shader("compute")]
[numthreads(64, 1, 1)]
void fma_probe(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i < push.count) {
        float4 o = operands[i];
        results[i] = fma_device(o.x, o.y, o.z);
    }
}

#endif
