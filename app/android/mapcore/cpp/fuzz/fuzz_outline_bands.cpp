// Fuzzes the glyph-outline to Slug band builder (text/Outlines.hpp, text/SlugBands.hpp; plan-vector T10): outlines
// drawn from the input (moves, lines, quadratics, cubics and closes at 16-bit coordinates, the units per em from 16 to
// 4,096), up to eight glyphs into one atlas. A refused glyph leaves the textures as they were; every glyph accepted
// must hold together: its headers in one row of the band texture, each list inside the texture and its entries naming
// curve texels whose next texel is in the same row, every curve whose extent reaches a band listed in it, the lists
// in decreasing order of the curves' far coordinate, and Slug's coverage through the bands equal to the coverage over
// every curve (up to the order of the sums) and within 0 to 1 at samples about the glyph.
//
// Input: a u16 (the units per em), then ops: a byte whose low three bits are 0 move, 1 line, 2 quadratic, 3 cubic,
// 4 close, 5 the glyph's end, followed by the op's int16 coordinates. Seeds: outlines of Geist's glyphs
// (fuzz/corpus/fuzz_outline_bands, written by tests/text/slug_test.cpp with ATLAS_TEXT_SEEDS).
#include "base/assert.hpp"
#include "text/Outlines.hpp"
#include "text/SlugBands.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace {

using namespace atlas::text;

class Input {
public:
    explicit Input(std::span<const std::uint8_t> bytes) : bytes_(bytes) {}
    [[nodiscard]] bool empty() const noexcept { return at_ >= bytes_.size(); }
    std::uint8_t byte() noexcept { return empty() ? 0 : bytes_[at_++]; }
    std::int16_t i16() noexcept {
        const auto lo = static_cast<std::uint16_t>(byte());
        const auto hi = static_cast<std::uint16_t>(byte());
        return static_cast<std::int16_t>(static_cast<std::uint16_t>(lo | static_cast<std::uint16_t>(hi << 8U)));
    }
    float coordinate() noexcept { return static_cast<float>(i16()); }

private:
    std::span<const std::uint8_t> bytes_;
    std::size_t at_ = 0;
};

std::uint64_t splitmix(std::uint64_t& state) {
    std::uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30U)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27U)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31U);
}

float unit(std::uint64_t& state) { return static_cast<float>(splitmix(state) >> 40U) / static_cast<float>(1U << 24U); }

/// The band a value falls in with the builder's margin (SlugBands.cpp's band_of).
std::uint32_t band_of(float v, std::uint32_t count) {
    const float f = std::floor(v);
    if (!(f > 0.0F)) return 0;
    if (f >= static_cast<float>(count - 1)) return count - 1;
    return static_cast<std::uint32_t>(f);
}

bool same(const slug::Curve& a, const slug::Curve& b) {
    return a.x1 == b.x1 && a.y1 == b.y1 && a.x2 == b.x2 && a.y2 == b.y2 && a.x3 == b.x3 && a.y3 == b.y3;
}

void check_glyph(const slug::Atlas& atlas, const slug::Glyph& g, const GlyphOutline& o, unsigned upem,
                 std::uint64_t seed) {
    if (!g.drawn()) return;
    const std::uint32_t rows = atlas.band_rows();
    const std::uint32_t curve_rows = atlas.curve_rows();
    ATLAS_ASSERT(g.band_x + g.hbands + g.vbands <= slug::texture_width && g.band_y < rows, "headers in one row");
    const auto curves = slug::em_curves(o, upem);
    ATLAS_ASSERT(curves.size() == g.curves, "every curve placed");
    for (std::uint32_t j = 0; j < g.hbands + g.vbands; ++j) {
        const bool horizontal = j < g.hbands;
        const slug::BandTexel header = atlas.band_texel(g.band_x + j, g.band_y);
        std::vector<slug::Curve> listed;
        float last = 0.0F;
        for (std::uint32_t k = 0; k < header.a; ++k) {
            const auto at = slug::band_location(g.band_x, g.band_y, header.b + k);
            ATLAS_ASSERT(at.y < rows, "a list inside the band texture");
            const slug::BandTexel entry = atlas.band_texel(at.x, at.y);
            ATLAS_ASSERT(entry.b < curve_rows && entry.a + 1U < slug::texture_width, "a curve's two texels in a row");
            const slug::CurveTexel p12 = atlas.curve_texel(entry.a, entry.b);
            const slug::CurveTexel p3 = atlas.curve_texel(entry.a + 1U, entry.b);
            const slug::Curve c{.x1 = p12.x0, .y1 = p12.y0, .x2 = p12.x1, .y2 = p12.y1, .x3 = p3.x0, .y3 = p3.y0};
            const float far = horizontal ? std::max({c.x1, c.x2, c.x3}) : std::max({c.y1, c.y2, c.y3});
            ATLAS_ASSERT(k == 0 || far <= last, "a list in decreasing order");
            last = far;
            listed.push_back(c);
        }
        // Every curve reaching the band is listed in it.
        for (const slug::Curve& c : curves) {
            const float lo = horizontal ? std::min({c.y1, c.y2, c.y3}) : std::min({c.x1, c.x2, c.x3});
            const float hi = horizontal ? std::max({c.y1, c.y2, c.y3}) : std::max({c.x1, c.x2, c.x3});
            const float scale = horizontal ? g.scale_y : g.scale_x;
            const float offset = horizontal ? g.offset_y : g.offset_x;
            const std::uint32_t count = horizontal ? g.hbands : g.vbands;
            const std::uint32_t band = horizontal ? j : j - g.hbands;
            const std::uint32_t b0 = band_of((lo * scale) + offset - (1.0F / 64.0F), count);
            const std::uint32_t b1 = band_of((hi * scale) + offset + (1.0F / 64.0F), count);
            if (band < b0 || band > b1) continue;
            const bool found = std::ranges::any_of(listed, [&](const slug::Curve& l) { return same(l, c); });
            ATLAS_ASSERT(found, "a curve reaching a band is listed in it");
        }
    }
    // Coverage through the bands is the coverage over every curve.
    std::uint64_t state = seed;
    const float w = g.box.x1 - g.box.x0;
    const float h = g.box.y1 - g.box.y0;
    for (int s = 0; s < 48; ++s) {
        const float x = g.box.x0 + (w * ((unit(state) * 1.2F) - 0.1F));
        const float y = g.box.y0 + (h * ((unit(state) * 1.2F) - 0.1F));
        const float ppx = 1.0F + (unit(state) * 2000.0F);
        const float ppy = 1.0F + (unit(state) * 2000.0F);
        const float banded = slug::coverage(atlas, g, x, y, ppx, ppy);
        const float all = slug::coverage_unbanded(curves, x, y, ppx, ppy);
        ATLAS_ASSERT(banded >= 0.0F && banded <= 1.0F, "coverage in 0 to 1");
        ATLAS_ASSERT(std::fabs(banded - all) <= 1e-5F * static_cast<float>(1U + g.curves), "banded coverage");
    }
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    Input in({data, size});
    const auto raw = static_cast<std::uint16_t>(in.i16());
    const unsigned upem = 16U + (raw % 4081U);
    slug::Atlas atlas;
    std::uint64_t seed = size;
    for (int glyph = 0; glyph < 8 && !in.empty(); ++glyph) {
        OutlineBuilder b;
        int ops = 0;
        while (!in.empty() && ops++ < 4096) {
            const std::uint8_t op = in.byte() & 7U;
            if (op == 5) break;
            switch (op) {
            case 0: {
                const float x = in.coordinate();
                b.move_to(x, in.coordinate());
                break;
            }
            case 1: {
                const float x = in.coordinate();
                b.line_to(x, in.coordinate());
                break;
            }
            case 2: {
                const float cx = in.coordinate();
                const float cy = in.coordinate();
                const float x = in.coordinate();
                b.quadratic_to(cx, cy, x, in.coordinate());
                break;
            }
            case 3: {
                const float c1x = in.coordinate();
                const float c1y = in.coordinate();
                const float c2x = in.coordinate();
                const float c2y = in.coordinate();
                const float x = in.coordinate();
                b.cubic_to(c1x, c1y, c2x, c2y, x, in.coordinate());
                break;
            }
            case 4: b.close(); break;
            default: break;
            }
        }
        const GlyphOutline o = b.finish(static_cast<std::uint32_t>(glyph));
        for (std::size_t c = 0; c < o.contours.size(); ++c) {
            const auto pts = o.contour(c);
            ATLAS_ASSERT(pts.size() >= 3 && pts.size() % 2 == 1 && pts.front() == pts.back(), "a closed contour");
        }
        const std::size_t bytes = atlas.bytes();
        const auto g = atlas.add(o, upem);
        seed = (seed * 31U) + static_cast<std::uint64_t>(o.points.size());
        if (!g) {
            ATLAS_ASSERT(atlas.bytes() == bytes, "a refused glyph leaves the textures");
            continue;
        }
        check_glyph(atlas, *g, o, upem, seed);
    }
    return 0;
}
