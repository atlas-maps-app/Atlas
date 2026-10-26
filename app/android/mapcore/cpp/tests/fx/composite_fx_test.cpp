// The composite's K17a steps on the CPU (shaders/shared/composite.slang as C++, the twin's text): the grain's integer
// overlay against Skia's lowp stages written apart (fx/Grain.hpp) on every canvas level, grain grey, alpha and both
// opacities; mat's rule at a silhouette against its rational value; the edge table; the 10-bit grain against the
// 8-bit one; the byte tables' edge levels in the GPU's layout; the rain's block and its whole-number streaks against a
// model in doubles.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "fx/ByteTables.hpp"
#include "fx/Grain.hpp"
#include "fx/Mat.hpp"
#include "passes/Composite.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <span>
#include <tuple>
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

namespace {

/// The rain's opacity at pixel (px, py) in 1/4096 by a model in doubles, with every streak's half width less and more
/// `slack` px (the opacity the pixel may have where its points' places are within `slack` of their own): the pixel's
/// four points (the 4x standard sample positions) as CSS points turned back by `angle` degrees, the tile repeated,
/// every streak of the block measured at each (no grid), their layers' mean at .45.
std::array<double, 2> rain_model(std::span<const std::uint32_t> block, double angle, double density, std::uint32_t px,
                                 std::uint32_t py, double slack) {
    const double radians = pymath::radians(angle);
    const double c = pymath::cos(radians);
    const double s = pymath::sin(radians);
    constexpr std::array<std::array<double, 2>, 4> points{
        {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};
    const auto wrap = [](double v, double side) { return v - (side * std::floor(v / side)); };
    std::array<double, 2> sum{};
    for (const auto& point : points) {
        const double ux = (static_cast<double>(px) + point[0]) / density;
        const double uy = (static_cast<double>(py) + point[1]) / density;
        const double tx = wrap((c * ux) + (s * uy), 127.0);
        const double ty = wrap((c * uy) - (s * ux), 211.0);
        std::array<double, 2> clear{1.0, 1.0};
        for (std::uint32_t i = 0; i < shaders::COMPOSITE_RAIN_COUNT; ++i) {
            const std::uint32_t* w = &block[shaders::COMPOSITE_RAIN_STREAKS + (std::size_t{4} * i)];
            const double x = w[0] / 256.0;
            const double y0 = w[1] / 256.0;
            const double y1 = w[2] / 256.0;
            const double r = std::sqrt((w[3] & 0xFFFFU) / 65536.0);
            const double dx = tx - x;
            const double dy = ty < y0 ? y0 - ty : (ty > y1 ? ty - y1 : 0.0);
            const double d = std::sqrt((dx * dx) + (dy * dy));
            const double opacity = (w[3] >> 16U) / 100.0;
            if (d <= r - slack) clear[0] *= 1.0 - opacity;
            if (d <= r + slack) clear[1] *= 1.0 - opacity;
        }
        sum[0] += 1.0 - clear[0];
        sum[1] += 1.0 - clear[1];
    }
    return {sum[0] / 4.0 * 0.45 * 4096.0, sum[1] / 4.0 * 0.45 * 4096.0};
}

} // namespace

TEST_CASE("the rain's block holds the tile's streaks as the SVG prints them, and its whole numbers are a model's in "
          "doubles at every angle and density") {
    std::vector<std::uint32_t> block(shaders::COMPOSITE_RAIN_WORDS);
    passes::rain_block(0, 1.0, block);
    // random.Random(4)'s first, second and last streaks as render/amsterdam.py:948 prints them:
    // M30.0 21.8v5.0 .34 wide at .39, M51.0 193.7v7.0 .49 at .47, M112.9 175.4v5.5 .31 at .49
    for (const auto& [i, x, y, l, w, op] :
         {std::tuple{0U, 30.0, 21.8, 5.0, 0.34, 39U}, std::tuple{1U, 51.0, 193.7, 7.0, 0.49, 47U},
          std::tuple{25U, 112.9, 175.4, 5.5, 0.31, 49U}}) {
        const std::uint32_t at = shaders::COMPOSITE_RAIN_STREAKS + (4 * i);
        CHECK(block[at] == static_cast<std::uint32_t>(std::lround(x * 256.0)));
        CHECK(block[at + 1] == static_cast<std::uint32_t>(std::lround(y * 256.0)));
        CHECK(block[at + 2] == static_cast<std::uint32_t>(std::lround((y + l) * 256.0)));
        CHECK(block[at + 3] == (static_cast<std::uint32_t>(std::lround(w * w * 16384.0)) | (op << 16U)));
    }
    // every streak listed in the cells it reaches, within the lists' room
    std::uint32_t listed = 0;
    for (std::uint32_t k = 0; k < shaders::COMPOSITE_RAIN_GRID_X * shaders::COMPOSITE_RAIN_GRID_Y; ++k) {
        listed += block[shaders::COMPOSITE_RAIN_CELLS + k] >> 16U;
    }
    CHECK(listed >= shaders::COMPOSITE_RAIN_COUNT);
    CHECK(listed <= shaders::COMPOSITE_RAIN_WORDS - shaders::COMPOSITE_RAIN_LISTS);
    // The whole numbers against the model over a frame of 400 × 300 px: within the opacities the model gives with the
    // streaks 1/128 px narrower and wider (a point's place may be off by 1/131072 px for each px across and down, 700
    // at the frame's far corner, and by the rounding to 1/256 px: 1/137 px at most), but at a point within that of the
    // tile's seam.
    for (const std::int64_t angle : {0, 18, 45, 90, 203, 400}) {
        for (const double density : {1.0, 2.625, 3.5}) {
            passes::rain_block(angle, density, block);
            std::uint32_t rained = 0;
            std::uint32_t apart = 0;
            double area = 0;
            for (std::uint32_t py = 0; py < 300; ++py) {
                for (std::uint32_t px = 0; px < 400; ++px) {
                    const std::int32_t got = shaders::composite_rain_alpha12(block, 0, hlsl::uint2{px, py});
                    const std::array<double, 2> want =
                        rain_model(block, static_cast<double>(angle), density, px, py, 1.0 / 128.0);
                    if (got > 0) ++rained;
                    area += want[1];
                    const auto g = static_cast<double>(got);
                    if (g < want[0] - 2.0 || g > want[1] + 2.0) ++apart;
                }
            }
            // the streaks' share of the frame: about a sixth of one per cent, at .45 and their opacities
            const double share = area / (0.45 * 4096.0) / (400.0 * 300.0);
            INFO("angle " << angle << " at density " << density << ": " << rained << " pixels rained on, " << apart
                          << " apart from the model; the streaks' share " << share);
            CHECK(rained > 300);
            CHECK(apart <= 2);
            CHECK(share > 0.0008);
            CHECK(share < 0.003);
        }
    }
}
