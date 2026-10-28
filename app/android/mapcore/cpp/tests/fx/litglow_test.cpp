// The lit streets' glow on the CPU (fx/LitGlow.hpp): its blur's sizes and reach at the densities phones have (WebKit's
// size for 2.6 times the filter scale, in the port's float, worked out step by step), its region about a group's box
// (SvgCanvas's objectBoundingBox arithmetic in the port's float and double, worked out alike), what it covers of a
// frame, and its chain against SvgFilterRaster's as it is written: the source held in linear RGB over the input, three
// square boxes with the input's edge repeated (boxSquare), each pass's sums divided once.
#include "fx/ByteTables.hpp"
#include "fx/LitGlow.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <random>
#include <vector>

namespace {

using namespace atlas::fx;

/// SvgFilterRaster.boxSquare on packed a r g b texels of a `w` × `h` image, as it is written: each channel's sums slid
/// along each row, then down each column, the ends' indices clamped, divided once with rounding.
void box_square(std::vector<Argb>& buf, int w, int h, int k) {
    const int half = k / 2;
    const int area = k * k;
    const auto at = [](int i) { return static_cast<std::size_t>(i); };
    std::array<std::vector<int>, 4> sums;
    for (std::vector<int>& s : sums) s.resize(buf.size());
    const auto channel = [](Argb p, std::size_t c) { return static_cast<int>((p >> (8 * c)) & 0xFFU); };
    for (int y = 0; y < h; ++y) {
        const int row = y * w;
        for (std::size_t c = 0; c < 4; ++c) {
            int sa = 0;
            for (int i = -half; i <= half; ++i) sa += channel(buf[at(row + std::clamp(i, 0, w - 1))], c);
            for (int x = 0; x < w; ++x) {
                sums[c][at(row + x)] = sa;
                sa += channel(buf[at(row + std::clamp(x + half + 1, 0, w - 1))], c) -
                      channel(buf[at(row + std::clamp(x - half, 0, w - 1))], c);
            }
        }
    }
    std::vector<Argb> out(buf.size(), 0);
    for (std::size_t c = 0; c < 4; ++c) {
        for (int x = 0; x < w; ++x) {
            int sa = 0;
            for (int i = -half; i <= half; ++i) sa += sums[c][at((std::clamp(i, 0, h - 1) * w) + x)];
            for (int y = 0; y < h; ++y) {
                out[at((y * w) + x)] |= static_cast<Argb>((sa + (area / 2)) / area) << (8 * c);
                sa += sums[c][at((std::clamp(y + half + 1, 0, h - 1) * w) + x)] -
                      sums[c][at((std::clamp(y - half, 0, h - 1) * w) + x)];
            }
        }
    }
    buf = out;
}

/// A generator seeded with `seed`: the same layers every run.
[[nodiscard]] std::mt19937 seeded(std::uint32_t seed) { return std::mt19937(seed); }

} // namespace

TEST_CASE("the lit streets' glow: WebKit's box for 2.6 at each density, vImage's square of it, the input's reach") {
    struct Want {
        float density;
        std::uint32_t size;
        std::uint32_t side;
        std::int32_t reach;
    };
    // kernel((2.6F as a double) · the density as a double): ⌊float(σ) · float(3/4 · √(2π)) + ½⌋ in float
    const std::array<Want, 9> wants{{
        {.density = 1.0F, .size = 5, .side = 5, .reach = 9},
        {.density = 2.0F, .size = 10, .side = 11, .reach = 18},
        {.density = 1080.0F / 412.0F, .size = 13, .side = 13, .reach = 21},
        {.density = 2.75F, .size = 13, .side = 13, .reach = 21},
        {.density = 3.0F, .size = 15, .side = 15, .reach = 24},
        {.density = 3.5F, .size = 17, .side = 17, .reach = 27},
        {.density = 4.0F, .size = 20, .side = 21, .reach = 33},
        {.density = 4.9F, .size = 24, .side = 25, .reach = 39},
        {.density = 6.7F, .size = 33, .side = 33, .reach = 51},
    }};
    for (const Want& w : wants) {
        const GlowBlur b = litglow_blur(w.density);
        CHECK(b.size == w.size);
        CHECK(b.side == w.side);
        CHECK(b.reach == w.reach);
        CHECK(b.half() == static_cast<std::int32_t>(w.side / 2));
    }
}

TEST_CASE("the lit streets' glow: its region about the group's box, as SvgCanvas and SvgFilterRaster set it up") {
    // box (-30.4, 100.1, 500.7, 1200.3) as floats at 1080/412: bw 531.1000366, region x -136.6200085 and width
    // 743.5400386 (in double from the floats), times the scale -358.13…, enclosing to -359 … 1591 and -315 … 3724
    const std::optional<GlowRect> r = litglow_region({-30.4F, 100.1F, 500.7F, 1200.3F}, 1080.0F / 412.0F);
    REQUIRE(r.has_value());
    const GlowRect region = r.value_or(GlowRect{});
    CHECK(region == GlowRect{.x0 = -359, .y0 = -315, .x1 = 1591, .y1 = 3724});
    // a box without width or height: no region (the port draws nothing)
    CHECK_FALSE(litglow_region({10.0F, 20.0F, 10.0F, 30.0F}, 2.0F).has_value());
    CHECK_FALSE(litglow_region({10.0F, 20.0F, 40.0F, 20.0F}, 2.0F).has_value());
    // what it covers of a 1080 × 2400 frame: the output a pixel beyond it either side, the input the reach beyond that
    const GlowBlur blur = litglow_blur(1080.0F / 412.0F);
    const GlowPlan whole = litglow_plan(region, 1080, 2400, blur);
    CHECK(whole.out == GlowRect{.x0 = -1, .y0 = -1, .x1 = 1081, .y1 = 2401});
    CHECK(whole.input == GlowRect{.x0 = -22, .y0 = -22, .x1 = 1102, .y1 = 2422});
    // a region inside the frame: the input stops at its edge
    const GlowPlan inside = litglow_plan({.x0 = 100, .y0 = 200, .x1 = 300, .y1 = 500}, 1080, 2400, blur);
    CHECK(inside.out == GlowRect{.x0 = 100, .y0 = 200, .x1 = 300, .y1 = 500});
    CHECK(inside.input == inside.out);
    // a region beyond the frame: nothing
    CHECK(litglow_plan({.x0 = 2000, .y0 = 0, .x1 = 2100, .y1 = 50}, 1080, 2400, blur).out.empty());
}

TEST_CASE("the lit streets' glow: its chain is SvgFilterRaster's over the input, texel for texel") {
    std::mt19937 rng = seeded(11);
    std::uniform_int_distribution<int> coin(0, 9);
    std::uniform_int_distribution<int> byte(0, 255);
    for (const float density : {1.0F, 1080.0F / 412.0F, 4.0F}) {
        const GlowBlur blur = litglow_blur(density);
        constexpr std::uint32_t w = 61;
        constexpr std::uint32_t h = 47;
        // a layer of the glow's colour at coverages its strokes leave (empty, full, or between at an edge)
        std::vector<Argb> layer(std::size_t{w} * h);
        for (Argb& p : layer) {
            const int c = coin(rng);
            const auto a = static_cast<std::uint32_t>(c < 5 ? 0 : (c < 8 ? 255 : byte(rng)));
            p = bytes::premultiply(bytes::pack(a, 0xFF, 0xB8, 0x66));
        }
        const GlowRect input{.x0 = 3, .y0 = 5, .x1 = 52, .y1 = 44};
        const std::vector<Argb> got = litglow_cpu(layer, w, h, input, blur);
        // the port's: the source over the input in linear RGB, boxSquare three times over it
        const int iw = input.x1 - input.x0;
        const int ih = input.y1 - input.y0;
        std::vector<Argb> port(static_cast<std::size_t>(iw) * static_cast<std::size_t>(ih));
        for (int y = 0; y < ih; ++y) {
            for (int x = 0; x < iw; ++x) {
                port[(static_cast<std::size_t>(y) * static_cast<std::size_t>(iw)) + static_cast<std::size_t>(x)] =
                    bytes::srgb_to_linear_premultiplied(
                        layer[(static_cast<std::size_t>(y + input.y0) * w) + static_cast<std::size_t>(x + input.x0)]);
            }
        }
        for (int k = 0; k < 3; ++k) box_square(port, iw, ih, static_cast<int>(blur.side));
        std::size_t differ = 0;
        std::size_t stray = 0;
        for (std::uint32_t y = 0; y < h; ++y) {
            for (std::uint32_t x = 0; x < w; ++x) {
                const Argb g = got[(std::size_t{y} * w) + x];
                const auto ix = static_cast<std::int32_t>(x);
                const auto iy = static_cast<std::int32_t>(y);
                if (!input.contains(ix, iy)) {
                    if (g != 0) ++stray;
                    continue;
                }
                const auto at = (static_cast<std::size_t>(iy - input.y0) * static_cast<std::size_t>(iw)) +
                                static_cast<std::size_t>(ix - input.x0);
                if (g != port[at]) ++differ;
            }
        }
        CHECK(differ == 0);
        CHECK(stray == 0);
    }
}
