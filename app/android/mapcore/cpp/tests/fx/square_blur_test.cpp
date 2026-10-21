// The ground shadows' blur on the CPU: vImage's square boxes (fx/Blur.hpp's square_blur_cpu) held to a transcription
// of SvgFilterRaster.boxSquare (its sums slid along each row and down each column, the ends' indices clamped) pass by
// pass; the GPU's division ((n + ½) · 1/box² truncated, shaders/ground/shadow_blur.slang) equal to the whole numbers'
// for every sum a box of up to 49 texels makes; and the port's filter grid (fx/BlurGrid.hpp) at the boards' views, from
// the shadows' boxes the port's SVG holds there (the values worked out step by step in the port's float and double).
#include "fx/Blur.hpp"
#include "fx/BlurGrid.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <random>
#include <utility>
#include <vector>

namespace {

using namespace atlas::fx;

/// A generator seeded with `seed`: the same images every run.
[[nodiscard]] std::mt19937 seeded(std::uint32_t seed) { return std::mt19937(seed); }

/// SvgFilterRaster.boxSquare on one channel, as it is written.
void box_square(std::vector<int>& buf, int w, int h, int k) {
    const int half = k / 2;
    const int area = k * k;
    std::vector<int> ha(buf.size());
    const auto at = [](int i) { return static_cast<std::size_t>(i); };
    for (int y = 0; y < h; ++y) {
        const int row = y * w;
        int sa = 0;
        for (int i = -half; i <= half; ++i) sa += buf[at(row + std::clamp(i, 0, w - 1))];
        for (int x = 0; x < w; ++x) {
            ha[at(row + x)] = sa;
            const int out = buf[at(row + std::clamp(x - half, 0, w - 1))];
            const int inn = buf[at(row + std::clamp(x + half + 1, 0, w - 1))];
            sa += inn - out;
        }
    }
    const int round = area / 2;
    std::vector<int> tmp(buf.size());
    for (int x = 0; x < w; ++x) {
        int sa = 0;
        for (int i = -half; i <= half; ++i) sa += ha[at((std::clamp(i, 0, h - 1) * w) + x)];
        for (int y = 0; y < h; ++y) {
            tmp[at((y * w) + x)] = (sa + round) / area;
            const int out = (std::clamp(y - half, 0, h - 1) * w) + x;
            const int inn = (std::clamp(y + half + 1, 0, h - 1) * w) + x;
            sa += ha[at(inn)] - ha[at(out)];
        }
    }
    buf = tmp;
}

} // namespace

TEST_CASE("vImage's square boxes are SvgFilterRaster.boxSquare's, pass by pass, at every size the shadows take") {
    std::mt19937 rng = seeded(7);
    std::uniform_int_distribution<int> byte(0, 255);
    std::uniform_int_distribution<int> coin(0, 9);
    const std::array<std::array<int, 3>, 9> sizes{{{1, 1, 3},
                                                   {2, 5, 3},
                                                   {5, 3, 3},
                                                   {37, 29, 5},
                                                   {64, 64, 7},
                                                   {100, 7, 9},
                                                   {3, 90, 13},
                                                   {70, 61, 21},
                                                   {48, 50, 49}}};
    for (const auto& [w, h, k] : sizes) {
        for (int kind = 0; kind < 2; ++kind) {
            // noise, and a coverage mask: mostly empty or full, as the shadows' union is
            std::vector<std::uint8_t> image(static_cast<std::size_t>(w) * static_cast<std::size_t>(h));
            for (std::uint8_t& v : image) {
                const int c = coin(rng);
                v = static_cast<std::uint8_t>(kind == 0 ? byte(rng) : (c < 5 ? 0 : (c < 9 ? 255 : 64 * (c - 8))));
            }
            std::vector<int> port(image.begin(), image.end());
            for (int pass = 0; pass < 3; ++pass) box_square(port, w, h, k);
            square_blur_cpu(image, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h),
                            static_cast<std::uint32_t>(k), 3);
            std::size_t differ = 0;
            for (std::size_t i = 0; i < image.size(); ++i) differ += std::cmp_not_equal(image[i], port[i]) ? 1 : 0;
            CHECK_MESSAGE(differ == 0, w << " x " << h << ", box " << k << ", kind " << kind);
        }
    }
}

TEST_CASE("the GPU's division equals the whole numbers' for every sum a box of up to 49 texels makes") {
    // (sum + box² / 2) / box² as shadow_blur.slang computes it: ((n + ½) · 1/box², each step rounded) truncated
    for (std::uint32_t box = 1; box <= 49; box += 2) {
        const std::uint32_t area = box * box;
        const float inv = 1.0F / static_cast<float>(area);
        std::uint32_t wrong = 0;
        for (std::uint32_t sum = 0; sum <= area * 255; ++sum) {
            const std::uint32_t n = sum + (area / 2);
            const auto q = static_cast<std::uint32_t>((static_cast<float>(n) + 0.5F) * inv);
            if (q != n / area) ++wrong;
        }
        CHECK_MESSAGE(wrong == 0, "box " << box);
    }
}

TEST_CASE("the port's filter grid at the boards' views: WebKit's clamp, the output, the blur's input and box") {
    struct Case {
        const char* name = "";
        std::array<float, 4> box{};
        float sigma = 0;
        double scale = 0;
        GridRect region;
        GridRect output;
        GridRect input;
        std::uint32_t kernel = 0;
        std::uint32_t box_size = 0;
    };
    // the boxes of the shadows' group in the port's SVG at S1 (412 × 916 CSS px at 1080 / 412 device px a CSS px)
    const std::array<Case, 5> cases{{
        {.name = "home sunny",
         .box = {-1176.7F, -46.1F, 1715.3F, 3196.3F},
         .sigma = 0.6F,
         .scale = 1.1146698008343563,
         .region = {.left = -1634, .top = -413, .right = 2235, .bottom = 3925},
         .output = {.left = -1, .top = -1, .right = 461, .bottom = 1023},
         .input = {.left = -7, .top = -7, .right = 467, .bottom = 1029},
         .kernel = 2,
         .box_size = 3},
        {.name = "home grey",
         .box = {-1088.4F, -29.0F, 1659.8F, 3196.3F},
         .sigma = 2.6F,
         .scale = 1.1464878061665624,
         .region = {.left = -1563, .top = -404, .right = 2219, .bottom = 4035},
         .output = {.left = -1, .top = -1, .right = 474, .bottom = 1052},
         .input = {.left = -13, .top = -13, .right = 486, .bottom = 1064},
         .kernel = 6,
         .box_size = 7},
        {.name = "board sunny",
         .box = {-779.1F, -30.1F, 1278.6F, 1637.5F},
         .sigma = 0.6F,
         .scale = 1.842645203385535,
         .region = {.left = -1815, .top = -363, .right = 2736, .bottom = 3325},
         .output = {.left = -1, .top = -1, .right = 761, .bottom = 1689},
         .input = {.left = -7, .top = -7, .right = 767, .bottom = 1695},
         .kernel = 2,
         .box_size = 3},
        {.name = "board grey",
         .box = {-784.0F, -9.1F, 1278.6F, 1637.5F},
         .sigma = 2.6F,
         .scale = 1.8521540915733188,
         .region = {.left = -1835, .top = -322, .right = 2751, .bottom = 3338},
         .output = {.left = -1, .top = -1, .right = 765, .bottom = 1698},
         .input = {.left = -16, .top = -16, .right = 780, .bottom = 1713},
         .kernel = 9,
         .box_size = 9},
        // a close view with shadows inside the region's 4096² limit: the device's own grid
        {.name = "sweep 0 grey",
         .box = {-86.5F, 255.6F, 684.6F, 568.6F},
         .sigma = 2.6F,
         .scale = 2.62135910987854,
         .region = {.left = -429, .top = 587, .right = 1997, .bottom = 1573},
         .output = {.left = -1, .top = 587, .right = 1081, .bottom = 1573},
         .input = {.left = -22, .top = 587, .right = 1102, .bottom = 1573},
         .kernel = 13,
         .box_size = 13},
    }};
    for (const Case& c : cases) {
        const BlurGrid g = blur_grid(c.box, c.sigma, static_cast<float>(1080 / 412.0), 412, 916);
        CAPTURE(c.name);
        CHECK(g.drawn);
        CHECK(g.scale == doctest::Approx(c.scale).epsilon(1e-12));
        CHECK(g.region == c.region);
        CHECK(g.output == c.output);
        CHECK(g.input == c.input);
        CHECK(g.kernel == c.kernel);
        CHECK(g.box == c.box_size);
        CHECK(g.reach == 3 * ((c.kernel / 2) + 1));
    }
    // a box with no area draws nothing
    CHECK_FALSE(blur_grid({10.0F, 10.0F, 10.0F, 50.0F}, 0.6F, 2.0F, 412, 916).drawn);
}
