// The standing fades' dither (shaders/shared/fade.slang, compiled here after base/hlsl.hpp as frame/FrameBlock.hpp
// compiles the frame's modules): a range from 0 to 1 keeps every sample of a pixel and an empty one none; ranges that
// meet end to end share no sample and leave none out, over every pixel of the 4 x 4 block and every sample count;
// every sample of a pixel or none (the composite's coverage rule reads a pixel split between the objects and the rest
// as a silhouette); over the block a range keeps its share of the 16 pixels within half a pixel; and a fade's samples
// only grow as it rises, so a tile fading in never drops a sample it drew.
#include "base/hlsl.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cmath>
#include <cstdint>
#include <set>

namespace atlas::shaders {
using namespace atlas::hlsl;
#include "shaders/shared/fade.slang"
} // namespace atlas::shaders

using namespace atlas;

namespace {

constexpr std::uint32_t all(std::uint32_t n) { return (std::uint32_t{1} << n) - 1; }

/// The samples a range keeps over the 4 x 4 block.
int kept(std::uint32_t n, std::uint32_t word) {
    int k = 0;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) k += std::popcount(shaders::fade_mask(shaders::int2{x, y}, n, word));
    }
    return k;
}

} // namespace

TEST_CASE("the standing fades' dither: Bayer's 4 x 4 ranks, each once, the same in every 4 x 4 block") {
    const int rows[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    std::set<std::uint32_t> seen;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            const std::uint32_t r = shaders::fade_rank(shaders::int2{x, y});
            CHECK(r == static_cast<std::uint32_t>(rows[y][x]));
            seen.insert(r);
            CHECK(shaders::fade_rank(shaders::int2{x + 4, y + 8}) == r);
            CHECK(shaders::fade_rank(shaders::int2{x + 1080, y + 2396}) == r);
        }
    }
    CHECK(seen.size() == 16);
}

TEST_CASE("the standing fades' dither: 0 to 1 keeps every sample, an empty range none") {
    for (const std::uint32_t n : {1U, 2U, 4U}) {
        for (int y = 0; y < 4; ++y) {
            for (int x = 0; x < 4; ++x) {
                const shaders::int2 p{x, y};
                CHECK(shaders::fade_mask(p, n, shaders::fade_word(0.0F, 1.0F)) == all(n));
                CHECK(shaders::fade_mask(p, n, shaders::fade_word(0.0F, 0.0F)) == 0U);
                CHECK(shaders::fade_mask(p, n, shaders::fade_word(1.0F, 1.0F)) == 0U);
                CHECK(shaders::fade_mask(p, n, shaders::fade_word(0.6F, 0.4F)) == 0U);
                // beyond 0 and 1 as at them
                CHECK(shaders::fade_mask(p, n, shaders::fade_word(-0.5F, 1.5F)) == all(n));
            }
        }
    }
    // the range in bits 8 to 23, 0 the whole of it; the carrier's own bits 0 to 7 (and those above) left to it
    CHECK(shaders::fade_word(0.0F, 1.0F) == 0U);
    CHECK(shaders::fade_word(1.0F, 1.0F) == 0xFF0000U);
    CHECK(shaders::fade_word(0.0F, 0.0F) == 0xFF00U);
    CHECK(shaders::fade_mask(shaders::int2{1, 2}, 4, 0U) == all(4));
    for (int x = 0; x < 4; ++x) {
        const shaders::int2 p{x, 3 - x};
        const std::uint32_t word = shaders::fade_word(0.3F, 0.7F);
        CHECK(shaders::fade_mask(p, 4, word | 0xFF0000FFU) == shaders::fade_mask(p, 4, word));
    }
    // a count beyond 1 to 4 as the nearest
    CHECK(shaders::fade_mask(shaders::int2{0, 0}, 0, shaders::fade_word(0.0F, 1.0F)) == 1U);
    CHECK(shaders::fade_mask(shaders::int2{0, 0}, 8, shaders::fade_word(0.0F, 1.0F)) == all(4));
}

TEST_CASE("the standing fades' dither: ranges that meet share no sample and leave none out") {
    // a chain: a tile drawn from 0 to a, the one kept under it from a to b (its own fade over the next), that one's
    // from max(a, b) to 1, at every pair of 255ths
    for (const std::uint32_t n : {1U, 2U, 4U}) {
        for (int ia = 0; ia <= 255; ia += 3) {
            for (int ib = 0; ib <= 255; ib += 17) {
                const float a = static_cast<float>(ia) / 255.0F;
                const float b = static_cast<float>(ib) / 255.0F;
                for (int y = 0; y < 4; ++y) {
                    for (int x = 0; x < 4; ++x) {
                        const shaders::int2 p{x, y};
                        const std::uint32_t top = shaders::fade_mask(p, n, shaders::fade_word(0.0F, a));
                        const std::uint32_t mid = shaders::fade_mask(p, n, shaders::fade_word(a, b));
                        const std::uint32_t low = shaders::fade_mask(p, n, shaders::fade_word(a > b ? a : b, 1.0F));
                        CHECK((top & mid) == 0U);
                        CHECK((top & low) == 0U);
                        CHECK((mid & low) == 0U);
                        CHECK((top | mid | low) == all(n));
                    }
                }
            }
        }
    }
}

TEST_CASE("the standing fades' dither: a range keeps its share of the block's pixels, every sample of each or none, "
          "and a rising fade only adds") {
    for (const std::uint32_t n : {1U, 2U, 4U}) {
        std::uint32_t before[4][4] = {};
        for (int i = 0; i <= 255; ++i) {
            const float f = static_cast<float>(i) / 255.0F;
            const auto d = static_cast<double>(f);
            const std::uint32_t word = shaders::fade_word(0.0F, f);
            // half a pixel, and the fade's own rounding to 255ths
            const double within = 0.5 + (0.5 * 16.0 / 255.0) + 1e-6;
            const auto pixels = [n](std::uint32_t w) { return static_cast<double>(kept(n, w)) / n; };
            CHECK(std::abs(pixels(word) - (d * 16.0)) <= within);
            CHECK(std::abs(pixels(shaders::fade_word(f, 1.0F)) - ((1.0 - d) * 16.0)) <= within);
            for (int y = 0; y < 4; ++y) {
                for (int x = 0; x < 4; ++x) {
                    const std::uint32_t m = shaders::fade_mask(shaders::int2{x, y}, n, word);
                    CHECK((m == 0U || m == all(n)));
                    CHECK((m & before[y][x]) == before[y][x]);
                    before[y][x] = m;
                }
            }
        }
    }
    // spread: at a quarter 4 of the block's pixels, at an eighth 2, each with all its samples
    CHECK(kept(4, shaders::fade_word(0.0F, 0.25F)) == 16);
    CHECK(kept(4, shaders::fade_word(0.0F, 0.125F)) == 8);
    CHECK(kept(1, shaders::fade_word(0.0F, 0.125F)) == 2);
}
