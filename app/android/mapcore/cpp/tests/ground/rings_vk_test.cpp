// The drizzle's rings on the host's Vulkan (passes/Rings.cpp): the Westerkerk home on a wet day, the ground drawn alone
// over bare water (no reflections, no glitter, the shadows at no opacity), with the rings and without. Where the frame
// without them shows the bare water around a pixel, the pixel with them is the rings' pattern there at the rect's
// opacity over that water: the tile of water_layer's 28 rings (random.Random(9), as the port prints them) repeated
// from the frame's corner, at each standard 4x sample the union of the rings whose strokes hold it (within .2 of the
// ellipse, the distance measured exactly), the pixel their mean.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// The standard 4x sample positions within a pixel.
constexpr std::array<std::array<double, 2>, 4> samples{
    {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};

struct Ring {
    double cx;
    double cy;
    double rx;
    double ry;
    double opacity;
};

/// water_layer's tile: random.Random(9)'s 28 radii, then each ring's centre and opacity, as the SVG prints them.
std::vector<Ring> tile_rings() {
    pymath::PyRandom rnd(9);
    std::array<double, 28> radii{};
    for (double& r : radii) r = rnd.uniform(0.8, 2.4);
    std::vector<Ring> out;
    for (const double r : radii) {
        const double cx = rnd.uniform(0, 173);
        const double cy = rnd.uniform(0, 97);
        const double op = rnd.uniform(0.4, 1);
        out.push_back({.cx = pymath::round(cx, 1),
                       .cy = pymath::round(cy, 1),
                       .rx = pymath::round(r, 1),
                       .ry = pymath::round(r * 0.36, 2),
                       .opacity = pymath::round(op, 2)});
    }
    return out;
}

/// The distance from p (relative to the centre) to the ellipse of semi-axes (a, b), a >= b: the closest point by
/// D. Eberly's bisection, in double.
double ellipse_distance(double px, double py, double a, double b) {
    const double y0 = std::abs(px);
    const double y1 = std::abs(py);
    if (y1 > 0) {
        if (y0 > 0) {
            const double z0 = y0 / a;
            const double z1 = y1 / b;
            const double g = (z0 * z0) + (z1 * z1) - 1;
            if (g == 0) return 0;
            const double r0 = (a / b) * (a / b);
            const double n0 = r0 * z0;
            double s0 = z1 - 1;
            double s1 = g < 0 ? 0 : pymath::hypot(n0, z1) - 1;
            double s = 0;
            for (int i = 0; i < 200; ++i) {
                s = 0.5 * (s0 + s1);
                if (s == s0 || s == s1) break;
                const double ra = n0 / (s + r0);
                const double rb = z1 / (s + 1);
                const double h = (ra * ra) + (rb * rb) - 1;
                if (h > 0) {
                    s0 = s;
                } else if (h < 0) {
                    s1 = s;
                } else {
                    break;
                }
            }
            return pymath::hypot((r0 * y0 / (s + r0)) - y0, (y1 / (s + 1)) - y1);
        }
        return std::abs(y1 - b);
    }
    const double numer = a * y0;
    const double denom = (a * a) - (b * b);
    if (numer < denom) {
        const double q = numer / denom;
        return pymath::hypot((a * q) - y0, b * std::sqrt(1 - (q * q)));
    }
    return std::abs(y0 - a);
}

/// Whether pixel (x, y) of a w × h frame lies on the composite's seam (buildings_vk_test.cpp's on_seam).
[[nodiscard]] bool on_seam(std::size_t x, std::size_t y, std::size_t w, std::size_t h) noexcept {
    const std::uint64_t above = std::uint64_t{y} * w;
    const std::uint64_t below = above + w;
    const std::uint64_t left = std::uint64_t{x} * h;
    const std::uint64_t right = left + h;
    return above < right && below > left;
}

std::array<double, 3> channels(const style::Rgb8& c) {
    return {static_cast<double>(c.r), static_cast<double>(c.g), static_cast<double>(c.b)};
}

} // namespace

TEST_CASE("ground: the drizzle's rings are their pattern at its opacity over the water, sample for sample") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the drizzle is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    style::ThemeBlock wet = test::sunny();
    wet.flags |= style::theme_flag::wet;
    wet.shadow_opacity = 0.0;
    double wgrad_y1 = 0;
    double wgrad_y2 = 0;
    std::size_t bands = 0;
    double opacity = 0;
    double hw = 0;
    std::uint32_t colour = 0;
    style::ThemeBlock th{};
    const auto quiet = [](passes::Ground& g) {
        g.draw_reflections(false);
        g.draw_glitter(false);
    };
    const auto a = test::draw_ground(
        *session, camera, wet, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) {
            const passes::GroundFrame& f = g.decided();
            wgrad_y1 = f.wgrad_y1;
            wgrad_y2 = f.wgrad_y2;
            bands = f.ring_bands.size();
            th = state.theme;
            const tiles::DrizzleStyle& d = f.tiles.front().ground->drizzle_style;
            opacity = d.opacity;
            hw = d.width / 2;
            colour = d.colour;
        },
        40, quiet);
    ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its rings");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE(bands == 1);
    const auto b = test::draw_ground(*session, camera, wet, dir, "amsterdam", {}, 40, [&](passes::Ground& g) {
        quiet(g);
        g.draw_rings(false);
    });
    ATLAS_REQUIRE(b.has_value(), "the home was not drawn without its rings");
    const std::vector<Ring> rings = tile_rings();
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    const auto density = static_cast<double>(camera.density);
    const std::array<double, 3> top = channels(th.water_top);
    const std::array<double, 3> bottom = channels(th.water_bottom);
    const std::array<double, 3> water = channels(th.water);
    const std::array<double, 3> c{static_cast<double>((colour >> 16U) & 0xFFU),
                                  static_cast<double>((colour >> 8U) & 0xFFU), static_cast<double>(colour & 0xFFU)};
    constexpr double tint = 0.12;
    const auto plain_at = [&](std::size_t row) {
        const double css_y = (static_cast<double>(row) + 0.5) / density;
        const double t = wgrad_y2 != wgrad_y1 ? std::clamp((css_y - wgrad_y1) / (wgrad_y2 - wgrad_y1), 0.0, 1.0)
                                              : (css_y < wgrad_y1 ? 0.0 : 1.0);
        std::array<double, 3> out{};
        for (std::size_t k = 0; k < 3; ++k) {
            out[k] = ((top[k] + ((bottom[k] - top[k]) * t)) * (1 - tint)) + (water[k] * tint);
        }
        return out;
    };
    std::vector<std::uint8_t> plain_water(std::size_t{w} * h, 0);
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        const std::array<double, 3> plain = plain_at(i / w);
        bool bare = true;
        for (std::size_t k = 0; k < 3; ++k) {
            bare = bare && std::abs(static_cast<double>(b->pixels.rgba[(i * 4) + k]) - plain[k]) <= 2;
        }
        plain_water[i] = bare ? 1 : 0;
    }
    std::size_t ringed = 0;
    std::size_t bare = 0;
    std::size_t boundary = 0;
    std::size_t seam = 0;
    std::size_t wrong = 0;
    std::size_t stray = 0;
    std::string first;
    for (std::size_t row = 1; row + 1 < h; ++row) {
        for (std::size_t column = 1; column + 1 < w; ++column) {
            bool around = true;
            for (std::size_t y = row - 1; y <= row + 1 && around; ++y) {
                for (std::size_t x = column - 1; x <= column + 1; ++x) around = around && plain_water[(y * w) + x] != 0;
            }
            if (!around) continue;
            ++bare;
            // each sample's place in the tile, the rings whose strokes hold it, their union
            double mean = 0;
            bool edge = false;
            for (const auto& s : samples) {
                const double x = (static_cast<double>(column) + s[0]) / density;
                const double y = (static_cast<double>(row) + s[1]) / density;
                const double tx = x - (173.0 * std::floor(x / 173.0));
                const double ty = y - (97.0 * std::floor(y / 97.0));
                double keep = 1;
                for (const Ring& r : rings) {
                    const double dx = tx - r.cx;
                    const double dy = ty - r.cy;
                    if (std::abs(dx) > r.rx + hw + 0.01 || std::abs(dy) > r.ry + hw + 0.01) continue;
                    const double d = ellipse_distance(dx, dy, r.rx, r.ry);
                    if (std::abs(d - hw) < 0.002) edge = true;
                    if (d <= hw) keep *= 1 - r.opacity;
                }
                mean += (1 - keep) / static_cast<double>(samples.size());
            }
            const std::size_t i = (row * w) + column;
            const std::uint8_t* pa = &a->pixels.rgba[i * 4];
            const std::uint8_t* pb = &b->pixels.rgba[i * 4];
            if (mean == 0) {
                bool same = true;
                for (std::size_t k = 0; k < 3; ++k) same = same && pa[k] == pb[k];
                if (!same && !edge && !on_seam(column, row, w, h)) ++stray;
                continue;
            }
            ++ringed;
            // the pattern over the water at the rect's opacity: b + o · u · (c - b)
            double worst = 0;
            std::array<double, 3> want{};
            for (std::size_t k = 0; k < 3; ++k) {
                const auto under = static_cast<double>(pb[k]);
                want[k] = under + ((opacity * mean) * (c[k] - under));
                worst = std::max(worst, std::abs(static_cast<double>(pa[k]) - want[k]));
            }
            if (worst <= 2) continue;
            if (edge) {
                ++boundary;
                continue;
            }
            if (on_seam(column, row, w, h)) {
                ++seam;
                continue;
            }
            if (wrong == 0) {
                first = std::format("pixel {} {}: {} {} {}, the twin's {:.1f} {:.1f} {:.1f} (layer {:.3f})", column,
                                    row, int(pa[0]), int(pa[1]), int(pa[2]), want[0], want[1], want[2], mean);
            }
            ++wrong;
        }
    }
    MESSAGE("the drizzle at the home: " << bare << " pixels of bare water, " << ringed << " of them under its rings: "
                                        << boundary << " another colour by a sample at a stroke's edge, " << seam
                                        << " on the composite's seam, " << wrong << " otherwise; " << stray
                                        << " left bare but not the water's");
    CHECK(ringed > 2000);
    CHECK_MESSAGE(wrong == 0, first);
    CHECK(boundary <= ringed / 100);
    CHECK(seam <= 20);
    CHECK(stray == 0);
}

#endif
