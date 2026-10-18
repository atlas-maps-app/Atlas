// Glyph outlines and Slug's textures on the CPU (plan-vector D13, K13): HarfBuzz's outlines of the bundled fonts as
// closed quadratic contours; the curve and band textures of every glyph the ground text draws, within their budget;
// the bands list every curve a sample's ray can meet (banded coverage equals the coverage over every curve); Slug's
// coverage against the exact, supersampled coverage of the outline at sizes from 4 to 40 px, turned and sheared; the
// root code's table; and the atlas's refusals.
#include "tests/base/require.hpp"
#include "tests/text/text_support.hpp"
#include "tests/tiles/support.hpp"
#include "text/Outlines.hpp"
#include "text/Shaper.hpp"
#include "text/SlugBands.hpp"
#include "tiles/Checksums.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <random>
#include <set>
#include <span>
#include <vector>

namespace {

using namespace atlas;
using atlas::test::bundled_fonts;
namespace slug = text::slug;

/// The winding number of `curves` (em units) about (x, y): each crossing of the ray to +x, exactly, in double.
int winding(std::span<const slug::Curve> curves, double x, double y) {
    int w = 0;
    for (const auto& c : curves) {
        const auto x1 = static_cast<double>(c.x1);
        const auto y1 = static_cast<double>(c.y1);
        const auto x2 = static_cast<double>(c.x2);
        const auto y2 = static_cast<double>(c.y2);
        const auto x3 = static_cast<double>(c.x3);
        const auto y3 = static_cast<double>(c.y3);
        const double ay = y1 - (2.0 * y2) + y3;
        const double by = y1 - y2;
        const double cy = y1 - y;
        double roots[2];
        int n = 0;
        if (std::fabs(ay) < 1e-12) {
            if (by != 0.0) roots[n++] = cy / (2.0 * by);
        } else {
            const double disc = (by * by) - (ay * cy);
            if (disc >= 0.0) {
                const double s = std::sqrt(disc);
                roots[n++] = (by - s) / ay;
                roots[n++] = (by + s) / ay;
            }
        }
        for (int i = 0; i < n; ++i) {
            const double t = roots[i];
            if (t < 0.0 || t >= 1.0) continue;
            const double ax = x1 - (2.0 * x2) + x3;
            const double bx = x1 - x2;
            const double cx = (((ax * t) - (2.0 * bx)) * t) + x1;
            if (cx <= x) continue;
            const double dy = (2.0 * ay * t) - (2.0 * by); // dy/dt
            if (dy < 0.0)
                ++w; // downward: clockwise outer contours count +1
            else if (dy > 0.0)
                --w; // upward
        }
    }
    return w;
}

/// A text-to-pixel map: a glyph at `size` px per em, turned by `angle` radians and sheared by `shear`, with its em
/// origin at (ox, oy) px, y down on screen. em → px is A · em + o.
struct Placement {
    double a = 0.0;
    double b = 0.0;
    double c = 0.0;
    double d = 0.0;
    double ox = 0.0;
    double oy = 0.0;

    static Placement of(double size, double angle, double shear, double ox, double oy) {
        // y up in em, y down on screen; then shear and turn
        const double cs = std::cos(angle); // NOLINT(bugprone-unsafe-functions): a test's camera, not the port's
        const double sn = std::sin(angle); // NOLINT(bugprone-unsafe-functions)
        const double m00 = size;
        const double m01 = size * shear;
        const double m10 = 0.0;
        const double m11 = -size;
        return {.a = (cs * m00) - (sn * m10),
                .b = (cs * m01) - (sn * m11),
                .c = (sn * m00) + (cs * m10),
                .d = (sn * m01) + (cs * m11),
                .ox = ox,
                .oy = oy};
    }
    /// The em point at pixel (px, py).
    [[nodiscard]] std::pair<double, double> em_at(double px, double py) const {
        const double det = (a * d) - (b * c);
        const double x = px - ox;
        const double y = py - oy;
        return {((d * x) - (b * y)) / det, ((-c * x) + (a * y)) / det};
    }
    /// Pixels per em along em x and y as the shader's 1 / fwidth(renderCoord) gives them.
    [[nodiscard]] std::pair<float, float> pixels_per_em() const {
        const double det = (a * d) - (b * c);
        // d(em)/d(px) = A^-1: row 0 (d / det, -b / det), row 1 (-c / det, a / det)
        const double ex = (std::fabs(d) + std::fabs(b)) / std::fabs(det);
        const double ey = (std::fabs(c) + std::fabs(a)) / std::fabs(det);
        return {static_cast<float>(1.0 / ex), static_cast<float>(1.0 / ey)};
    }
};

/// The comparison of Slug's coverage with the exact one over a glyph's pixels.
struct Comparison {
    std::size_t pixels = 0;
    std::size_t solid = 0; ///< pixels wholly inside or outside, with their neighbours
    double solid_worst = 0.0;
    std::size_t edge = 0;
    double edge_sum = 0.0;
    double edge_worst = 0.0;
};

Comparison compare(const slug::Atlas& atlas, const slug::Glyph& g, std::span<const slug::Curve> curves,
                   const Placement& p) {
    // The glyph's pixel box, a pixel of margin around it.
    double x0 = 1e9;
    double y0 = 1e9;
    double x1 = -1e9;
    double y1 = -1e9;
    for (const double ex : {static_cast<double>(g.box.x0), static_cast<double>(g.box.x1)}) {
        for (const double ey : {static_cast<double>(g.box.y0), static_cast<double>(g.box.y1)}) {
            const double px = (p.a * ex) + (p.b * ey) + p.ox;
            const double py = (p.c * ex) + (p.d * ey) + p.oy;
            x0 = std::min(x0, px);
            y0 = std::min(y0, py);
            x1 = std::max(x1, px);
            y1 = std::max(y1, py);
        }
    }
    const int ix0 = static_cast<int>(std::floor(x0)) - 2;
    const int iy0 = static_cast<int>(std::floor(y0)) - 2;
    const int w = static_cast<int>(std::ceil(x1)) + 2 - ix0;
    const int h = static_cast<int>(std::ceil(y1)) + 2 - iy0;
    constexpr int sub = 16;
    auto index = [w](int i, int j) {
        return (static_cast<std::size_t>(j) * static_cast<std::size_t>(w)) + static_cast<std::size_t>(i);
    };
    std::vector<double> exact(static_cast<std::size_t>(w) * static_cast<std::size_t>(h));
    for (int j = 0; j < h; ++j) {
        for (int i = 0; i < w; ++i) {
            int inside = 0;
            for (int sj = 0; sj < sub; ++sj) {
                for (int si = 0; si < sub; ++si) {
                    const auto [ex, ey] = p.em_at(ix0 + i + ((si + 0.5) / sub), iy0 + j + ((sj + 0.5) / sub));
                    inside += winding(curves, ex, ey) != 0 ? 1 : 0;
                }
            }
            exact[index(i, j)] = inside / static_cast<double>(sub * sub);
        }
    }
    const auto [ppx, ppy] = p.pixels_per_em();
    Comparison out;
    for (int j = 0; j < h; ++j) {
        for (int i = 0; i < w; ++i) {
            const auto [ex, ey] = p.em_at(ix0 + i + 0.5, iy0 + j + 0.5);
            const auto s =
                static_cast<double>(slug::coverage(atlas, g, static_cast<float>(ex), static_cast<float>(ey), ppx, ppy));
            const double e = exact[index(i, j)];
            bool solid = e == 0.0 || e == 1.0;
            for (int dj = -1; dj <= 1 && solid; ++dj) {
                for (int di = -1; di <= 1 && solid; ++di) {
                    const int ii = std::clamp(i + di, 0, w - 1);
                    const int jj = std::clamp(j + dj, 0, h - 1);
                    solid = exact[index(ii, jj)] == e;
                }
            }
            ++out.pixels;
            if (solid) {
                ++out.solid;
                out.solid_worst = std::max(out.solid_worst, std::fabs(s - e));
            } else {
                ++out.edge;
                out.edge_sum += std::fabs(s - e);
                out.edge_worst = std::max(out.edge_worst, std::fabs(s - e));
            }
        }
    }
    return out;
}

/// Every glyph the fixture's runs draw, by face and weight.
std::map<std::pair<int, int>, std::set<std::uint32_t>> fixture_glyphs() {
    std::map<std::pair<int, int>, std::set<std::uint32_t>> out;
    for (const auto& run : test::android_runs()) {
        auto& set = out[{static_cast<int>(run.face()), run.weight}];
        set.insert(run.glyphs.begin(), run.glyphs.end());
    }
    return out;
}

} // namespace

TEST_CASE("HarfBuzz's outlines are closed quadratic contours in the font's units") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const auto geist = fonts->instance({.face = text::Face::sans, .weight = 700});
    ATLAS_REQUIRE(geist.has_value());
    const text::GlyphOutline o = text::outline(**geist, (*geist)->glyph_of(U'O'));
    CHECK(o.contours.size() == 2); // the O's outer and inner contours
    CHECK(o.curve_count() >= 8);
    for (std::size_t c = 0; c < o.contours.size(); ++c) {
        const auto pts = o.contour(c);
        CHECK(pts.size() % 2 == 1);
        CHECK(pts.front() == pts.back());
    }
    CHECK(o.box.x0 >= 0.0F);
    CHECK(o.box.x1 <= 800.0F);
    CHECK(o.box.y0 < 0.0F); // overshoot below the baseline
    CHECK(o.box.y1 > 700.0F);
    // A space has no contours; the weight changes the outline (gvar).
    CHECK(text::outline(**geist, (*geist)->glyph_of(U' ')).empty());
    const auto thin = fonts->instance({.face = text::Face::sans, .weight = 100});
    ATLAS_REQUIRE(thin.has_value());
    const text::GlyphOutline o100 = text::outline(**thin, (*thin)->glyph_of(U'O'));
    CHECK(o100.points.size() == o.points.size());
    CHECK(o100.points != o.points);

    // The builder: lines become quadratics through their middles, cubics four quadratics, open contours close, flat
    // ones go.
    text::OutlineBuilder b;
    b.move_to(0, 0);
    b.line_to(10, 0);
    b.line_to(10, 10); // left open: closed back to (0, 0)
    b.move_to(5, 5);
    b.line_to(5, 5); // a contour of one point: dropped
    b.move_to(20, 0);
    b.cubic_to(20, 10, 30, 10, 30, 0);
    b.close();
    const text::GlyphOutline built = b.finish(7);
    REQUIRE(built.contours.size() == 2);
    CHECK(built.contour(0).size() == 7); // three lines
    CHECK(built.contour(0)[1] == text::OutlinePoint{5, 0});
    CHECK(built.contour(1).size() == 11); // four quadratics and the closing line
    CHECK(built.box.x0 == 0.0F);
    CHECK(built.box.x1 == 30.0F);
}

TEST_CASE("every glyph of the fixture's runs fits the textures, within the glyph budget") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    slug::Atlas atlas;
    std::size_t glyphs = 0;
    std::size_t curves = 0;
    std::size_t bands = 0;
    for (const auto& [key, set] : fixture_glyphs()) {
        const auto instance = fonts->instance(
            {.face = static_cast<text::Face>(key.first), .weight = static_cast<std::uint16_t>(key.second)});
        ATLAS_REQUIRE(instance.has_value());
        for (const std::uint32_t id : set) {
            const text::GlyphOutline o = text::outline(**instance, id);
            const auto g = atlas.add(o, (*instance)->units_per_em());
            ATLAS_REQUIRE(g.has_value(), slug::describe(g.error()));
            ++glyphs;
            curves += g->curves;
            bands += g->hbands + g->vbands;
            CHECK(g->drawn() == !o.empty());
            if (!g->drawn()) continue;
            // Headers in one row; the box covers the curves; bands 1 to 16.
            CHECK(g->band_x + g->hbands + g->vbands <= slug::texture_width);
            CHECK(g->hbands >= 1);
            CHECK(g->hbands <= slug::max_bands);
        }
    }
    MESSAGE(glyphs << " glyphs, " << curves << " curves, " << bands << " bands: " << atlas.curve_rows()
                   << " curve rows and " << atlas.band_rows() << " band rows, " << atlas.bytes() << " bytes");
    CHECK(glyphs > 100);
    CHECK(atlas.bytes() < 3'000'000); // plan-vector §3: glyph curves and bands within 3 MB with the rest
}

TEST_CASE("the bands list every curve a ray can meet: banded coverage is the coverage over every curve") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    std::mt19937 random(20261018); // NOLINT(cert-msc51-cpp): the same samples on every run
    std::size_t samples = 0;
    double worst = 0.0;
    for (const auto& [key, set] : fixture_glyphs()) {
        const auto instance = fonts->instance(
            {.face = static_cast<text::Face>(key.first), .weight = static_cast<std::uint16_t>(key.second)});
        ATLAS_REQUIRE(instance.has_value());
        slug::Atlas atlas;
        for (const std::uint32_t id : set) {
            const text::GlyphOutline o = text::outline(**instance, id);
            const auto g = atlas.add(o, (*instance)->units_per_em());
            ATLAS_REQUIRE(g.has_value());
            if (!g->drawn()) continue;
            const auto curves = slug::em_curves(o, (*instance)->units_per_em());
            std::uniform_real_distribution<float> ux(g->box.x0 - 0.05F, g->box.x1 + 0.05F);
            std::uniform_real_distribution<float> uy(g->box.y0 - 0.05F, g->box.y1 + 0.05F);
            std::uniform_real_distribution<float> scale(4.0F, 400.0F);
            for (int k = 0; k < 200; ++k) {
                const float x = ux(random);
                const float y = uy(random);
                const float ppx = scale(random);
                const float ppy = scale(random);
                const auto banded = static_cast<double>(slug::coverage(atlas, *g, x, y, ppx, ppy));
                const auto all = static_cast<double>(slug::coverage_unbanded(curves, x, y, ppx, ppy));
                worst = std::max(worst, std::fabs(banded - all));
                ++samples;
            }
        }
    }
    MESSAGE(samples << " samples, the largest difference " << worst);
    CHECK(samples > 20'000);
    CHECK(worst < 1e-5); // the same terms, summed in another order
}

TEST_CASE("Slug's coverage is the exact coverage inside and outside, and near it at the edges, from 4 to 40 px") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const auto geist = fonts->instance({.face = text::Face::sans, .weight = 700});
    ATLAS_REQUIRE(geist.has_value());
    slug::Atlas atlas;
    Comparison all;
    for (const char32_t c : std::u32string(U"OAEGRS8&W@")) {
        const text::GlyphOutline o = text::outline(**geist, (*geist)->glyph_of(c));
        const auto g = atlas.add(o, 1000);
        ATLAS_REQUIRE(g.has_value());
        const auto curves = slug::em_curves(o, 1000);
        for (const double size : {4.0, 9.5, 18.0, 40.0}) {
            for (const auto& [angle, shear] : {std::pair{0.0, 0.0}, {0.7, 0.0}, {2.6, 0.3}, {-1.2, -0.45}}) {
                const Comparison r = compare(atlas, *g, curves, Placement::of(size, angle, shear, 3.3, 2.7));
                all.pixels += r.pixels;
                all.solid += r.solid;
                all.solid_worst = std::max(all.solid_worst, r.solid_worst);
                all.edge += r.edge;
                all.edge_sum += r.edge_sum;
                all.edge_worst = std::max(all.edge_worst, r.edge_worst);
            }
        }
    }
    const double edge_mean = all.edge_sum / static_cast<double>(all.edge);
    MESSAGE(all.pixels << " pixels: " << all.solid << " wholly in or out, worst " << all.solid_worst << "; " << all.edge
                       << " at edges, mean difference " << edge_mean << ", worst " << all.edge_worst);
    CHECK(all.solid_worst < 1e-3);
    CHECK(edge_mean < 0.06);
    CHECK(all.edge_worst < 0.6);
}

TEST_CASE("the root code: which roots cross the ray, by the control points' signs; -0 counts as below") {
    // first root (bit 0) for a downward crossing, second (bit 8) for an upward one, both for a dip across and back
    CHECK(slug::root_code(1, 1, 1) == 0U);
    CHECK(slug::root_code(-1, -1, -1) == 0U);
    CHECK(slug::root_code(1, 1, -1) == 0x001U);
    CHECK(slug::root_code(-1, 1, 1) == 0x100U);
    CHECK(slug::root_code(1, -1, 1) == 0x101U);
    CHECK(slug::root_code(-1, 1, -1) == 0x101U);
    CHECK(slug::root_code(-1, -1, 1) == 0x100U);
    CHECK(slug::root_code(1, -1, -1) == 0x001U);
    CHECK(slug::root_code(0.0F, 0.0F, -0.0F) == 0x001U);
    CHECK(slug::root_code(-0.0F, 0.0F, 0.0F) == 0x100U);
    // band offsets wrap into the next row
    CHECK(slug::band_location(4090, 3, 10).x == 4);
    CHECK(slug::band_location(4090, 3, 10).y == 4);
    CHECK(slug::band_count(1) == 1);
    CHECK(slug::band_count(16) == 8);
    CHECK(slug::band_count(1000) == slug::max_bands);
}

TEST_CASE("the atlas refuses a contour longer than a row and points that are not finite, and passes over spaces") {
    slug::Atlas atlas;
    text::OutlineBuilder b;
    b.move_to(0, 0);
    for (int i = 1; i <= 5000; ++i) b.line_to(static_cast<float>(i), static_cast<float>(i % 2));
    b.close();
    CHECK(atlas.add(b.finish(1), 1000).error() == slug::AtlasError::too_large);
    b.move_to(0, 0);
    b.line_to(std::numeric_limits<float>::infinity(), 0);
    b.line_to(0, 10);
    CHECK(atlas.add(b.finish(2), 1000).error() == slug::AtlasError::not_finite);
    const auto space = atlas.add(text::GlyphOutline{}, 1000);
    ATLAS_REQUIRE(space.has_value());
    CHECK(!space->drawn());
    CHECK(slug::coverage(atlas, *space, 0.5F, 0.5F, 10, 10) == 0.0F);
    CHECK(atlas.bytes() == 0); // nothing refused was kept
}

TEST_CASE("the fuzz target's seeds: Geist's glyph outlines (written when ATLAS_TEXT_SEEDS is set)") {
    const std::string out = test::environment("ATLAS_TEXT_SEEDS");
    if (out.empty()) {
        MESSAGE("ATLAS_TEXT_SEEDS is not set: no seeds written");
        return;
    }
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const std::filesystem::path dir = std::filesystem::path(out) / "fuzz_outline_bands";
    std::filesystem::create_directories(dir);
    // fuzz_outline_bands' format: the units per em, then per glyph its contours as a move and quadratics, closed, and
    // the glyph's end; the coordinates rounded to whole units.
    auto put16 = [](std::vector<std::byte>& bytes, int v) {
        const auto u = static_cast<std::uint16_t>(static_cast<std::int16_t>(v));
        bytes.push_back(static_cast<std::byte>(u & 0xFFU));
        bytes.push_back(static_cast<std::byte>(u >> 8U));
    };
    std::size_t written = 0;
    for (const auto& [weight, glyphs] :
         {std::pair{700, std::u32string(U"OAE8")}, {700, U"&W@g"}, {100, U"RS"}, {900, U"MBQ%"}}) {
        const auto instance = fonts->instance({.face = text::Face::sans, .weight = static_cast<std::uint16_t>(weight)});
        ATLAS_REQUIRE(instance.has_value());
        std::vector<std::byte> bytes;
        put16(bytes, 1000 - 16); // units per em: 16 + raw % 4081
        for (const char32_t c : glyphs) {
            const text::GlyphOutline o = text::outline(**instance, (*instance)->glyph_of(c));
            for (std::size_t k = 0; k < o.contours.size(); ++k) {
                const auto pts = o.contour(k);
                bytes.push_back(std::byte{0});
                put16(bytes, static_cast<int>(std::lround(pts[0].x)));
                put16(bytes, static_cast<int>(std::lround(pts[0].y)));
                for (std::size_t i = 1; i + 1 < pts.size(); i += 2) {
                    bytes.push_back(std::byte{2});
                    for (const auto& q : {pts[i], pts[i + 1]}) {
                        put16(bytes, static_cast<int>(std::lround(q.x)));
                        put16(bytes, static_cast<int>(std::lround(q.y)));
                    }
                }
                bytes.push_back(std::byte{4});
            }
            bytes.push_back(std::byte{5});
        }
        std::ofstream file(dir / tiles::hex(tiles::Sha256::of(bytes)).substr(0, 16), std::ios::binary);
        file.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
        ++written;
    }
    MESSAGE(written << " seeds in " << dir.string());
}
