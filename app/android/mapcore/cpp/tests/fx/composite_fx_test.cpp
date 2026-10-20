// The composite's K17a steps on the CPU (shaders/shared/composite.slang as C++, the twin's text): the grain's integer
// overlay against Skia's lowp stages written apart (fx/Grain.hpp) on every canvas level, grain grey, alpha and both
// opacities; mat's rule at a silhouette against its rational value; the edge table; the 10-bit grain against the
// 8-bit one; the byte tables' edge levels in the GPU's layout.
#include "frame/FrameBlock.hpp"
#include "fx/ByteTables.hpp"
#include "fx/Grain.hpp"
#include "fx/Mat.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>

using namespace atlas;

TEST_CASE("the composite's grain is Skia's lowp overlay at every canvas level, grey, alpha and opacity") {
    CHECK(fx::skia_byte(fx::grain_opacity_day) == shaders::COMPOSITE_GRAIN_DAY);
    CHECK(fx::skia_byte(fx::grain_opacity_night) == shaders::COMPOSITE_GRAIN_NIGHT);
    std::uint64_t checked = 0;
    std::uint64_t differ = 0;
    std::uint64_t changed = 0;
    for (const std::uint32_t opacity : {shaders::COMPOSITE_GRAIN_DAY, shaders::COMPOSITE_GRAIN_NIGHT}) {
        for (std::uint32_t alpha = 0; alpha < 256; ++alpha) {
            for (std::uint32_t grey = 0; grey <= alpha; ++grey) { // premultiplied
                for (std::uint32_t d = 0; d < 256; ++d) {
                    const std::uint32_t got = shaders::composite_grain_8(d, grey, alpha, opacity);
                    if (got != fx::grain_overlay(d, grey, alpha, opacity)) ++differ;
                    if (got != d) ++changed;
                    ++checked;
                }
            }
        }
    }
    MESSAGE(checked << " canvas levels, greys, alphas and opacities; the grain changes " << changed << " of them");
    CHECK(differ == 0);
    CHECK(changed > checked / 2);
}

TEST_CASE("the 10-bit grain makes the 8-bit one's change to the nearest 8-bit level, scaled") {
    std::uint32_t worst = 0;
    for (std::uint32_t d = 0; d < 1024; d += 3) {
        for (std::uint32_t alpha = 0; alpha < 256; alpha += 5) {
            for (std::uint32_t grey = 0; grey <= alpha; grey += 7) {
                const hlsl::uint3 grain{grey, alpha, shaders::COMPOSITE_GRAIN_DAY};
                const float ten = shaders::composite_grain_level(static_cast<float>(d), grain, 1023.0F);
                const std::uint32_t d8 = ((d * 255U) + 511U) / 1023U;
                const auto eight = static_cast<double>(shaders::composite_grain_8(d8, grey, alpha, grain.z));
                // Against the 8-bit result moved by the 10-bit level's own offset from its 8-bit level.
                const double expected = (static_cast<double>(d) / 1023.0) + ((eight - d8) / 255.0);
                const auto miss = static_cast<std::uint32_t>(std::lround(
                    std::abs((static_cast<double>(ten) / 1023.0) - std::clamp(expected, 0.0, 1.0)) * 1023.0));
                worst = std::max(worst, miss);
            }
        }
    }
    MESSAGE("the 10-bit grain within " << worst << " of its 10-bit levels of the 8-bit one's change");
    CHECK(worst <= 1);
}

TEST_CASE("mat's rule at a silhouette composites the edge level with alpha 1.1·(k/n)² over the others, in whole "
          "levels") {
    for (const std::uint32_t n : {2U, 4U}) {
        for (std::uint32_t k = 1; k < n; ++k) {
            const double alpha = 1.1 * (static_cast<double>(k) / n) * (static_cast<double>(k) / n);
            for (std::uint32_t e = 0; e < 1024; e += 11) {
                for (std::uint32_t g = 0; g < 1024; g += 13) {
                    const hlsl::float3 r = shaders::composite_silhouette({e, e, e}, {g, g, g}, k, n);
                    const double want = (e * alpha) + (g * (1.0 - alpha));
                    CHECK(std::abs(static_cast<double>(r.x) - want) <= 0.5 + 1e-9);
                }
            }
        }
    }
    // The mean of k samples, rounded half up.
    const hlsl::uint3 m = shaders::composite_mean_of({7.0F, 8.0F, 9.0F}, 2);
    CHECK(m.x == 4);
    CHECK(m.y == 4);
    CHECK(m.z == 5);
}

TEST_CASE("mat's edge levels are the interior's over 1.1 in linear light, and the GPU's tables hold them") {
    for (const std::uint32_t levels : {255U, 1023U}) {
        const auto t = fx::mat_edge_table(levels);
        REQUIRE(t.size() == levels + 1);
        CHECK(t[0] == 0);
        bool rising = true;
        bool below = true;
        for (std::uint32_t v = 1; v <= levels; ++v) {
            rising = rising && t[v] >= t[v - 1];
            below = below && t[v] <= v;
        }
        CHECK(rising);
        CHECK(below);
    }
    // White's edge: sRGB of 1/1.1, 244.53 of 255.
    CHECK(fx::mat_edge_table(255)[255] == 245);
    const std::vector<std::uint8_t> tables = fx::gpu_tables();
    REQUIRE(tables.size() == fx::tables_bytes);
    for (std::uint32_t v = 0; v < 256; ++v) {
        const auto entry = static_cast<std::uint32_t>(tables[fx::table_edge8 + (2 * v)] |
                                                      (tables[fx::table_edge8 + (2 * v) + 1] << 8U));
        CHECK(entry == fx::mat_edge_table(255)[v]);
    }
    for (std::uint32_t v = 0; v < 1024; ++v) {
        const auto entry = static_cast<std::uint32_t>(tables[fx::table_edge10 + (2 * v)] |
                                                      (tables[fx::table_edge10 + (2 * v) + 1] << 8U));
        CHECK(entry == fx::mat_edge_table(1023)[v]);
    }
}
