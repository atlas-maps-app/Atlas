// SIMD loops must equal their scalar references bit for bit (plan-vector T7): the lanes' arithmetic is IEEE, the
// fused operation is explicit, and nothing is contracted.
#include "base/simd.hpp"
#include "tests/base/fma_inputs.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace {

// A polynomial step in the style of the noise fields: explicit fused operations and separately rounded ones mixed.
float scalar_step(float x, float y, float z) {
    const float t = std::fma(x, y, z);
    const float u = (t * x) - (y * 0.5F);
    return std::fma(u, u, -t) + std::floor(u);
}

atlas::simd::f32x4 lanes_step(atlas::simd::f32x4 x, atlas::simd::f32x4 y, atlas::simd::f32x4 z) {
    using namespace atlas::simd;
    const f32x4 t = fma(x, y, z);
    const f32x4 u = (t * x) - (y * 0.5F);
    return fma(u, u, -t) + floor(u);
}

} // namespace

TEST_CASE("lanes equal the scalar reference bit for bit") {
    const auto inputs = atlas::test::fma_inputs(400'000, 0x51D);
    std::size_t mismatches = 0;
    for (std::size_t i = 0; i + 4 <= inputs.size(); i += 4) {
        atlas::simd::f32x4 x;
        atlas::simd::f32x4 y;
        atlas::simd::f32x4 z;
        for (int lane = 0; lane < 4; ++lane) {
            const auto& in = inputs[i + static_cast<std::size_t>(lane)];
            x[lane] = in[0];
            y[lane] = in[1];
            z[lane] = in[2];
        }
        const auto out = atlas::simd::lanes(lanes_step(x, y, z));
        for (std::size_t lane = 0; lane < 4; ++lane) {
            const auto& in = inputs[i + lane];
            const float expected = scalar_step(in[0], in[1], in[2]);
            if (std::bit_cast<std::uint32_t>(out[lane]) != std::bit_cast<std::uint32_t>(expected)) ++mismatches;
        }
    }
    CHECK(mismatches == 0);
}

TEST_CASE("loads, stores, selects, conversions and sums") {
    using namespace atlas::simd;
    const std::array<float, 5> memory{0.0F, 1.5F, -2.0F, 3.25F, 4.0F};
    const auto v = load<f32x4>(memory.data() + 1); // unaligned
    CHECK(v.x == 1.5F);
    CHECK(v.w == 4.0F);
    std::array<float, 4> out{};
    store(out.data(), v * 2.0F);
    CHECK(out[2] == 6.5F);
    const f32x4 picked = select(v > 2.0F, v, f32x4(0.0F));
    CHECK(picked.x == 0.0F);
    CHECK(picked.z == 3.25F);
    CHECK(to_int(v)[1] == -2);
    CHECK(to_float(i32x4{1, 2, 3, 4}).w == 4.0F);
    CHECK(sum(v) == ((1.5F + -2.0F) + 3.25F) + 4.0F);
    CHECK(max(v, f32x4(0.0F)).y == 0.0F);
    CHECK(abs(v).y == 2.0F);
    const f64x2 d = fma(f64x2{1.0, 2.0}, f64x2{3.0, 4.0}, f64x2{0.5, 0.5});
    CHECK(d.y == 8.5);
    MESSAGE("hardware fma on this target: " << std::string(hardware_fma ? "yes" : "no (libm's fma per lane)"));
}

TEST_CASE("min and max are Python's in every lane: signed zeros and NaN as pymath, on every target") {
    using namespace atlas::simd;
    // Every ordered pair of these, with operands the compiler cannot see (volatile), so neither the lanes nor the
    // reference can fold into the hardware's minimum.
    volatile float values[] = {-0.0F, 0.0F, -1.0F, 1.0F, std::nanf("")};
    std::size_t pairs = 0;
    for (const float a : values) {
        for (const float b : values) {
            const float low = lanes(min(f32x4(a), f32x4(b)))[2];
            const float high = lanes(max(f32x4(a), f32x4(b)))[1];
            const float python_low = b < a ? b : a; // Python's min(a, b): b only when b < a
            const float python_high = b > a ? b : a;
            CHECK(std::bit_cast<std::uint32_t>(low) == std::bit_cast<std::uint32_t>(python_low));
            CHECK(std::bit_cast<std::uint32_t>(high) == std::bit_cast<std::uint32_t>(python_high));
            ++pairs;
        }
    }
    CHECK(pairs == 25);
    // The cases the hardware's minimum gets differently on arm64 or x86_64, spelled out.
    CHECK(std::signbit(lanes(min(f32x4(-0.0F), f32x4(0.0F)))[0]));
    CHECK(!std::signbit(lanes(min(f32x4(0.0F), f32x4(-0.0F)))[0]));
    CHECK(std::signbit(lanes(max(f32x4(-0.0F), f32x4(0.0F)))[0]));
    CHECK(!std::signbit(lanes(max(f32x4(0.0F), f32x4(-0.0F)))[0]));
}
