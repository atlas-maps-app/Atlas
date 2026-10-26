// The sun's glitter on the host's Vulkan (passes/Glitter.cpp): the Westerkerk by day, looking toward the sun so that
// the water mirrors it on the picture, the ground drawn alone without the reflections (bare water under the glints)
// and the shadows at no opacity, with the glitter and without. Where the frame without it shows the bare water around a
// pixel, the pixel with it is the twin's glints' layer screened over that water: at each standard 4x sample the union
// of the glints over it (1 - Π(1 - opacity)), the group's colour screened at that alpha, the pixel their mean.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
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
#include <optional>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// The standard 4x sample positions within a pixel.
constexpr std::array<std::array<double, 2>, 4> samples{
    {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};

/// A twin glint in device px.
struct Glint {
    double cx = 0;
    double cy = 0;
    double rx = 0;
    double ry = 0;
    double opacity = 0;
};

/// Whether pixel (x, y) of a w × h frame lies on the composite's seam, where lavapipe shades its triangle twice in
/// place (buildings_vk_test.cpp's on_seam).
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

TEST_CASE("ground: the sun's glitter is its glints' layer screened over the water, sample for sample") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the glitter is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    // over the Prinsengracht toward the sun (16° up), 70° from straight down: the first bearing whose picture holds the
    // point of the water that mirrors it
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    std::vector<Glint> glints;
    std::uint32_t colour = 0;
    double wgrad_y1 = 0;
    double wgrad_y2 = 0;
    style::ThemeBlock th{};
    style::CameraState camera{};
    std::optional<test::GroundDraw> a;
    for (const double bearing : {146.5, 213.5, 33.5, 326.5}) {
        camera = test::s1_camera(52.3756, 4.8846 + (40.0 / kx), bearing, 70.0, 260.0);
        glints.clear();
        a = test::draw_ground(
            *session, camera, day, dir, "amsterdam",
            [&](passes::Ground& g, const frame::FrameState& state) {
                twin::Stream s;
                g.emit(state, s, passes::Ground::phase_opaque);
                const twin::Decoded d = twin::decode(s.bytes());
                REQUIRE(d.error.empty());
                const passes::GroundFrame& f = g.decided();
                wgrad_y1 = f.wgrad_y1;
                wgrad_y2 = f.wgrad_y2;
                th = state.theme;
                const auto density = static_cast<double>(state.block.camera.frame.z);
                const std::uint32_t slot = g.slot(geometry::group::water_top, 0, geometry::water_sub::glitter);
                for (const twin::Element& e : d.document.elements) {
                    if (e.layer != twin::Layer::ground || e.kind != twin::Op::ellipse || e.slot != slot) continue;
                    colour = e.style.fill.argb;
                    glints.push_back({.cx = static_cast<double>(e.points[0].x) * density,
                                      .cy = static_cast<double>(e.points[0].y) * density,
                                      .rx = static_cast<double>(e.points[1].x) * density,
                                      .ry = static_cast<double>(e.points[1].y) * density,
                                      .opacity = static_cast<double>(e.node.opacity)});
                }
            },
            40,
            [](passes::Ground& g) {
                g.draw_reflections(false);
                g.draw_wind_glints(false);
            });
        if (!a || !glints.empty()) break;
    }
    ATLAS_REQUIRE(a.has_value(), "the view was not drawn with its glitter");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const auto b = test::draw_ground(*session, camera, day, dir, "amsterdam", {}, 40, [](passes::Ground& g) {
        g.draw_reflections(false);
        g.draw_glitter(false);
        g.draw_wind_glints(false);
    });
    ATLAS_REQUIRE(b.has_value(), "the view was not drawn without its glitter");
    REQUIRE(glints.size() == 260);
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    const auto density = static_cast<double>(camera.density);
    // each sample's union of the glints over it, and whether a sample lies within float rounding of a glint's edge
    std::vector<std::array<double, 4>> keep(std::size_t{w} * h, {1, 1, 1, 1});
    std::vector<std::array<std::uint8_t, 4>> over(std::size_t{w} * h, {0, 0, 0, 0});
    std::vector<std::uint8_t> near_edge(std::size_t{w} * h, 0);
    for (const Glint& g : glints) {
        for (auto y = static_cast<std::int64_t>(std::max(0.0, std::floor(g.cy - g.ry - 1)));
             y <= static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(g.cy + g.ry + 1))); ++y) {
            for (auto x = static_cast<std::int64_t>(std::max(0.0, std::floor(g.cx - g.rx - 1)));
                 x <= static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(g.cx + g.rx + 1)));
                 ++x) {
                const std::size_t i = (static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x);
                for (std::size_t k = 0; k < samples.size(); ++k) {
                    const double u = pymath::hypot((static_cast<double>(x) + samples[k][0] - g.cx) / g.rx,
                                                   (static_cast<double>(y) + samples[k][1] - g.cy) / g.ry);
                    if (std::abs(u - 1) * std::min(g.rx, g.ry) < 0.01) near_edge[i] = 1;
                    if (u > 1) continue;
                    keep[i][k] *= 1 - g.opacity;
                    ++over[i][k];
                }
            }
        }
    }
    const std::array<double, 3> top = channels(th.water_top);
    const std::array<double, 3> bottom = channels(th.water_bottom);
    const std::array<double, 3> water = channels(th.water);
    const std::array<double, 3> s{static_cast<double>((colour >> 16U) & 0xFFU),
                                  static_cast<double>((colour >> 8U) & 0xFFU), static_cast<double>(colour & 0xFFU)};
    constexpr double tint = 0.12;
    // the bare water of each row (wgrad under the tint), and the pixels showing it with their neighbours
    const auto plain_at = [&](std::size_t row) {
        const double css_y = (static_cast<double>(row) + 0.5) / density;
        const double t = wgrad_y2 != wgrad_y1 ? std::clamp((css_y - wgrad_y1) / (wgrad_y2 - wgrad_y1), 0.0, 1.0)
                                              : (css_y < wgrad_y1 ? 0.0 : 1.0);
        std::array<double, 3> out{};
        for (std::size_t c = 0; c < 3; ++c)
            out[c] = ((top[c] + ((bottom[c] - top[c]) * t)) * (1 - tint)) + (water[c] * tint);
        return out;
    };
    std::vector<std::uint8_t> plain_water(std::size_t{w} * h, 0);
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        const std::array<double, 3> plain = plain_at(i / w);
        bool bare = true;
        for (std::size_t c = 0; c < 3; ++c) {
            bare = bare && std::abs(static_cast<double>(b->pixels.rgba[(i * 4) + c]) - plain[c]) <= 2;
        }
        plain_water[i] = bare ? 1 : 0;
    }
    std::size_t glinted = 0;
    std::size_t overlapping = 0;
    std::size_t boundary = 0;
    std::size_t seam = 0;
    std::size_t wrong = 0;
    std::size_t stray = 0;
    std::string first;
    for (std::size_t row = 1; row + 1 < h; ++row) {
        for (std::size_t column = 1; column + 1 < w; ++column) {
            bool bare = true;
            for (std::size_t y = row - 1; y <= row + 1 && bare; ++y) {
                for (std::size_t x = column - 1; x <= column + 1; ++x) bare = bare && plain_water[(y * w) + x] != 0;
            }
            if (!bare) continue;
            const std::size_t i = (row * w) + column;
            const std::uint8_t* pa = &a->pixels.rgba[i * 4];
            const std::uint8_t* pb = &b->pixels.rgba[i * 4];
            double mean = 0;
            bool many = false;
            for (std::size_t k = 0; k < samples.size(); ++k) {
                mean += (1 - keep[i][k]) / static_cast<double>(samples.size());
                many = many || over[i][k] > 1;
            }
            if (mean == 0) {
                bool same = true;
                for (std::size_t c = 0; c < 3; ++c) same = same && pa[c] == pb[c];
                if (!same && near_edge[i] == 0) ++stray;
                continue;
            }
            ++glinted;
            if (many) ++overlapping;
            // the layer screened over the water: b + u · s · (1 - b), in bytes
            double worst = 0;
            std::array<double, 3> want{};
            for (std::size_t c = 0; c < 3; ++c) {
                const auto under = static_cast<double>(pb[c]);
                want[c] = under + ((mean * s[c] / 255.0) * (255.0 - under));
                worst = std::max(worst, std::abs(static_cast<double>(pa[c]) - want[c]));
            }
            if (worst <= 2) continue;
            if (near_edge[i] != 0) {
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
    MESSAGE("the glitter toward the sun: "
            << glints.size() << " glints in the twin, sample-rate shading "
            << std::string(session->device->enabled().sample_rate_shading ? "on" : "off") << "; " << glinted
            << " pixels of bare water under them (" << overlapping << " where glints overlap): " << boundary
            << " another colour by a sample at a glint's edge, " << seam << " on the composite's seam, " << wrong
            << " otherwise; " << stray << " left bare but not the water's");
    CHECK(glinted > 200);
    CHECK(overlapping > 10);
    CHECK_MESSAGE(wrong == 0, first);
    CHECK(boundary <= glinted / 100);
    CHECK(seam <= 20);
    CHECK(stray == 0);
}

#endif
