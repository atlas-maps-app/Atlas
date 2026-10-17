// Inputs for the fused multiply-add checks: (a, b, c) triples, the same on every host (splitmix64, no library
// distributions). Most are random: signs, significands and exponents from 2^-20 to 2^20 for a and b, as N17 tested.
// The rest are the hard cases for an emulation: c cancelling a·b's rounded product (the result is then a·b's
// rounding error), c within a few ulps of that, c far below a·b (only the sticky bits of the rounding see it), and
// products that land exactly halfway between two floats, with and without a c that breaks the tie.
#pragma once

#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace atlas::test {

/// splitmix64: a tiny, fully specified generator.
class SplitMix64 {
public:
    explicit constexpr SplitMix64(std::uint64_t seed) noexcept : state_(seed) {}
    constexpr std::uint64_t next() noexcept {
        std::uint64_t z = (state_ += 0x9E3779B97F4A7C15ULL);
        z = (z ^ (z >> 30U)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27U)) * 0x94D049BB133111EBULL;
        return z ^ (z >> 31U);
    }
    /// A value in [0, n).
    constexpr std::uint32_t below(std::uint32_t n) noexcept { return static_cast<std::uint32_t>(next() % n); }

private:
    std::uint64_t state_;
};

/// A float with a random sign and significand and the given unbiased exponent (a normal number).
[[nodiscard]] inline float random_float(SplitMix64& random, int exponent) {
    const auto sign = static_cast<std::uint32_t>(random.next() & 1U) << 31U;
    const auto biased = static_cast<std::uint32_t>(exponent + 127) << 23U;
    const auto significand = static_cast<std::uint32_t>(random.next()) & 0x7FFFFFU;
    return std::bit_cast<float>(sign | biased | significand);
}

/// The unbiased exponent of a normal float.
[[nodiscard]] inline int exponent_of(float x) {
    return static_cast<int>((std::bit_cast<std::uint32_t>(x) >> 23U) & 0xFFU) - 127;
}

/// The float next to x, away from zero (`steps` ulps; negative steps go toward zero).
[[nodiscard]] inline float ulps_from(float x, int steps) {
    return std::bit_cast<float>(
        static_cast<std::uint32_t>(static_cast<std::int64_t>(std::bit_cast<std::uint32_t>(x)) + steps));
}

/// `count` triples (a, b, c, 0), deterministic for a seed.
[[nodiscard]] inline std::vector<std::array<float, 4>> fma_inputs(std::size_t count, std::uint64_t seed) {
    SplitMix64 random(seed);
    std::vector<std::array<float, 4>> out;
    out.reserve(count);
    // Products exactly halfway between two floats: (1 + i·2^-12)(1 + j·2^-12) has bits down to 2^-24.
    for (std::uint32_t i = 1; i <= 24 && out.size() < count; ++i) {
        for (std::uint32_t j = 1; j <= 24 && out.size() < count; ++j) {
            const float a = 1.0F + (static_cast<float>(i) * 0x1p-12F);
            const float b = 1.0F + (static_cast<float>(j) * 0x1p-12F);
            for (const float c : {0.0F, 0x1p-60F, -0x1p-60F, -1.0F, 0x1p-30F}) {
                if (out.size() < count) out.push_back({a, b, c, 0.0F});
            }
        }
    }
    while (out.size() < count) {
        const float a = random_float(random, static_cast<int>(random.below(41)) - 20);
        const float b = random_float(random, static_cast<int>(random.below(41)) - 20);
        const float product = a * b; // rounded: what an unfused a·b + c would add
        float c = 0.0F;
        switch (random.below(10)) {
        case 0:
        case 1: c = -product; break; // cancellation
        case 2:
        case 3: c = ulps_from(-product, static_cast<int>(random.below(9)) - 4); break; // near it
        case 4:
            c = random_float(random, exponent_of(product) - 24 - static_cast<int>(random.below(30)));
            break;                                                                         // sticky
        default: c = random_float(random, static_cast<int>(random.below(81)) - 40); break; // anywhere
        }
        out.push_back({a, b, c, 0.0F});
    }
    return out;
}

} // namespace atlas::test
