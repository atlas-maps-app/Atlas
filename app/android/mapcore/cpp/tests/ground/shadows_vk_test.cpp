// The ground shadows on the host's Vulkan (plan-vector K9, D8 "Ground shadows"; L2 (b) for the shadows): at the
// Westerkerk home in the sunny moment, the frame drawn with the sun up and with it set differ only by the shadows, so
// their difference is the shadows' coverage per pixel (th['shadow'] over the ground at shadow_op). It is held to a CPU
// raster of the twin's shadow paths (the port's own polygons: K8's hull2 of each footprint and its moved top, each
// raised outline moved, each crown's 16-gon), their union at the standard 4x sample positions: the vertex stage's swept
// hulls (passes/Shadows.cpp) cover what the port's hulls cover, sample for sample but where an edge passes within
// float rounding of a sample.
#if !defined(__ANDROID__)

#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <filesystem>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// The standard 4x sample positions within a pixel.
constexpr std::array<std::array<double, 2>, 4> samples{
    {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};

/// Whether (x, y) is inside the polygon by the nonzero rule.
bool inside(const std::vector<std::array<double, 2>>& poly, double x, double y) {
    int w = 0;
    const std::size_t n = poly.size();
    for (std::size_t i = 0; i < n; ++i) {
        const auto& a = poly[i];
        const auto& b = poly[(i + 1) % n];
        const double cross = ((b[0] - a[0]) * (y - a[1])) - ((x - a[0]) * (b[1] - a[1]));
        if (a[1] <= y) {
            if (b[1] > y && cross > 0) ++w;
        } else if (b[1] <= y && cross < 0) {
            --w;
        }
    }
    return w != 0;
}

} // namespace

TEST_CASE("ground: the shadows' union covers the twin's shadow paths, sample for sample") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the shadows are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    const style::ThemeBlock up = test::sunny();
    style::ThemeBlock set = up;
    set.sun_vector = {0.0, -1.0, 0.0};
    set.sun_elevation = -5.0;
    // the frame with the sun up, and its twin's shadow paths in device px
    std::vector<std::vector<std::array<double, 2>>> polys;
    std::size_t tiles = 0;
    const auto a = test::draw_ground(
        *session, camera, up, dir, "amsterdam", [&](passes::Ground& g, const frame::FrameState& state) {
            twin::Stream s;
            g.emit(state, s, passes::Ground::phase_opaque);
            const twin::Decoded d = twin::decode(s.bytes());
            REQUIRE(d.error.empty());
            const std::uint32_t slot = g.slot(geometry::group::shadow, 0, 0);
            // device px as the vertex stage finds them: CSS px times the density, f32
            const float density = state.block.camera.frame.z;
            for (const twin::Element& e : d.document.elements) {
                if (e.layer != twin::Layer::ground || e.slot != slot) continue;
                if (e.kind != twin::Op::path || e.clip != twin::none) continue;
                std::vector<std::array<double, 2>> p;
                p.reserve(e.points.size());
                for (const twin::Point& q : e.points) {
                    p.push_back({static_cast<double>(q.x * density), static_cast<double>(q.y * density)});
                }
                polys.push_back(std::move(p));
            }
            tiles = g.decided().tiles.size();
        });
    ATLAS_REQUIRE(a.has_value(), "the frame with the sun up was not drawn");
    CHECK(a->decided.shadows);
    const auto b = test::draw_ground(*session, camera, set, dir, "amsterdam");
    ATLAS_REQUIRE(b.has_value(), "the frame with the sun set was not drawn");
    CHECK_FALSE(b->decided.shadows);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE(polys.size() > 100);
    // the union of the paths at the four samples of each pixel
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    std::vector<std::uint8_t> cpu(std::size_t{w} * h, 0);
    for (const auto& p : polys) {
        double x0 = p[0][0];
        double x1 = x0;
        double y0 = p[0][1];
        double y1 = y0;
        for (const auto& q : p) {
            x0 = std::min(x0, q[0]);
            x1 = std::max(x1, q[0]);
            y0 = std::min(y0, q[1]);
            y1 = std::max(y1, q[1]);
        }
        const auto px0 = static_cast<std::int64_t>(std::max(0.0, std::floor(x0)));
        const auto px1 = static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1)));
        const auto py0 = static_cast<std::int64_t>(std::max(0.0, std::floor(y0)));
        const auto py1 = static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1)));
        for (std::int64_t y = py0; y <= py1; ++y) {
            for (std::int64_t x = px0; x <= px1; ++x) {
                std::uint8_t& c = cpu[(static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)];
                for (std::size_t k = 0; k < samples.size(); ++k) {
                    if ((c & (1U << k)) != 0) continue;
                    if (inside(p, static_cast<double>(x) + samples[k][0], static_cast<double>(y) + samples[k][1])) {
                        c = static_cast<std::uint8_t>(c | (1U << k));
                    }
                }
            }
        }
    }
    // the GPU's coverage: (without - with) / (shadow_op · (without - shadow)), on the channel that tells it best
    const double op = up.shadow_opacity;
    const std::array<double, 3> shadow{static_cast<double>(up.shadow.r), static_cast<double>(up.shadow.g),
                                       static_cast<double>(up.shadow.b)};
    std::size_t off = 0;
    std::size_t covered = 0;
    std::size_t told = 0;
    double worst = 0;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        const std::uint8_t* pa = &a->pixels.rgba[i * 4];
        const std::uint8_t* pb = &b->pixels.rgba[i * 4];
        int best = 0;
        double span = 0;
        for (int k = 0; k < 3; ++k) {
            const double s = std::abs(static_cast<double>(pb[k]) - shadow[static_cast<std::size_t>(k)]);
            if (s > span) {
                span = s;
                best = k;
            }
        }
        const double want = static_cast<double>(std::popcount(cpu[i])) / 4;
        if (want == 1.0) ++covered;
        if (span < 24) continue; // the ground's colour too near the shadow's to tell
        ++told;
        const double got = (static_cast<double>(pb[best]) - static_cast<double>(pa[best])) /
                           (op * (static_cast<double>(pb[best]) - shadow[static_cast<std::size_t>(best)]));
        const double d = std::abs(got - want);
        worst = std::max(worst, d);
        // a sample may flip where an edge passes within float rounding of it: a quarter, and the 8 bits' rounding
        if (d > 0.25 + 0.05) ++off;
    }
    MESSAGE("home sunny: " << tiles << " tiles, " << polys.size() << " shadow paths in the twin; " << covered
                           << " pixels wholly in shadow; of " << told << " pixels that tell, " << off
                           << " off by more than a sample, the worst by " << worst);
    CHECK(static_cast<double>(covered) > 0.2 * static_cast<double>(w) * h);
    CHECK(off <= told / 20000);
}

#endif
