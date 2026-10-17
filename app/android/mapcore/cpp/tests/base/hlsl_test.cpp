// The shaders' shared subset compiled as C++ (base/hlsl.hpp): the exact probe's own Slang text runs here on the CPU,
// as the twin runs the shaders' text, and is held to std::fma bit for bit.
#include "base/hlsl.hpp"
#include "tests/base/fma_inputs.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace atlas::hlsl {
#include "shaders/probe/exact_probe.slang"
} // namespace atlas::hlsl

TEST_CASE("the vector subset behaves as Slang's") {
    using namespace atlas::hlsl;
    float4 v = {1.0F, -2.0F, 3.5F, 0.25F};
    float3 xyz = v.xyz;
    float2 wz = v.wz;
    CHECK(xyz.y == -2.0F);
    CHECK(wz.x == 0.25F);
    CHECK(float3(2.0F).z == 2.0F);
    CHECK(saturate(v).y == 0.0F);
    CHECK(saturate(v).z == 1.0F);
    CHECK(abs(v).y == 2.0F);
    CHECK(floor(float2{-1.5F, 1.5F}).x == -2.0F);
    CHECK(frac(-1.25F) == 0.75F);
    CHECK(step(1.0F, 0.5F) == 0.0F);
    CHECK(step(float2(1.0F), float2{0.5F, 2.0F}).y == 1.0F);
    CHECK(select(true, 1.0F, 2.0F) == 1.0F);
    CHECK(lerp(2.0F, 4.0F, 0.25F) == 2.5F);
    CHECK(dot(float3{1.0F, 2.0F, 3.0F}, float3{4.0F, 5.0F, 6.0F}) == 32.0F);
    const float3 c = cross(float3{1.0F, 0.0F, 0.0F}, float3{0.0F, 1.0F, 0.0F});
    CHECK(c.z == 1.0F);
    CHECK(asuint(1.0F) == 0x3F800000U);
    CHECK(asfloat(0x40490FDBU) == 3.14159274F);
    CHECK(fma_exact(0.1F, 10.0F, -1.0F) == std::fma(0.1F, 10.0F, -1.0F));
}

TEST_CASE("the exact probe's pieces are exact") {
    using namespace atlas::hlsl;
    const FloatPair s = two_sum(1.0F, 0x1p-30F);
    CHECK(s.hi == 1.0F);
    CHECK(s.lo == 0x1p-30F);
    const FloatPair p = two_product(1.0F + 0x1p-12F, 1.0F + 0x1p-12F);
    CHECK(p.hi == 1.0F + 0x1p-11F);
    CHECK(p.lo == 0x1p-24F);
    // Round to odd: 1 + 2^-25 lies between 1 and its successor; the odd one is the successor.
    CHECK(std::bit_cast<std::uint32_t>(add_round_to_odd(1.0F, 0x1p-25F)) == 0x3F800001U);
    CHECK(add_round_to_odd(1.0F, 0x1p-23F) == 1.0F + 0x1p-23F); // representable: exact
    CHECK(std::bit_cast<std::uint32_t>(add_round_to_odd(-1.0F, -0x1p-25F)) == 0xBF800001U);
}

TEST_CASE("the emulated fused multiply-add equals std::fma over 10^6 inputs") {
    const auto inputs = atlas::test::fma_inputs(1'000'000, 0xA71A5);
    std::size_t differs_from_unfused = 0;
    std::size_t mismatches = 0;
    for (const auto& [a, b, c, unused] : inputs) {
        const float expected = std::fma(a, b, c);
        const float emulated = atlas::hlsl::fma_emulated(a, b, c);
        if (std::bit_cast<std::uint32_t>(emulated) != std::bit_cast<std::uint32_t>(expected)) {
            if (mismatches++ < 5) {
                MESSAGE("fma(" << a << ", " << b << ", " << c << "): emulated " << emulated << ", std::fma "
                               << expected);
            }
        }
        if ((a * b) + c != expected) ++differs_from_unfused;
    }
    CHECK(mismatches == 0);
    // The inputs are hard ones: in a large share, rounding twice would give another float.
    CHECK(differs_from_unfused > inputs.size() / 5);
    MESSAGE("inputs where a*b + c rounded twice differs from fma: " << differs_from_unfused << " of " << inputs.size());
}

TEST_CASE("min, max, clamp and saturate are Python's: the same bits on arm64 and x86_64") {
    using namespace atlas::hlsl;
    volatile float values[] = {-0.0F, 0.0F, -1.0F, 1.0F, std::nanf("")};
    auto bits = [](float f) { return std::bit_cast<std::uint32_t>(f); };
    for (const float a : values) {
        for (const float b : values) {
            CHECK(bits(min(a, b)) == bits(b < a ? b : a));
            CHECK(bits(max(a, b)) == bits(b > a ? b : a));
            const float3 v = min(float3(a), float3(b));
            CHECK(bits(v.z) == bits(b < a ? b : a));
            const float4 w = max(float4(a), float4(b));
            CHECK(bits(w.y) == bits(b > a ? b : a));
        }
    }
    volatile float negative_zero = -0.0F;
    CHECK(std::signbit(saturate(float(negative_zero)))); // Python: min(max(-0.0, 0.0), 1.0) is -0.0
    CHECK(std::signbit(clamp(float(negative_zero), 0.0F, 1.0F)));
    CHECK(!std::signbit(clamp(0.0F, float(negative_zero), 1.0F)));
    const float2 outside = {-2.0F, 3.0F};
    CHECK(saturate(outside).x == 0.0F);
    CHECK(saturate(outside).y == 1.0F);
}
