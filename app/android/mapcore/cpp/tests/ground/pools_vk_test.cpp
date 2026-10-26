// The street lamps' pools at night on the host's Vulkan (passes/Pools.cpp): the Westerkerk home at night, the ground
// drawn alone with its pools and without them. The pools are the ground's last elements, so a pixel inside one pool
// alone (its centre within .95 of the ellipse's radii, apart from the others) is the `pool` gradient there over the
// frame without them, within two levels (the hardware's blend and its rounding), and a pixel no pool reaches is the
// frame without them.
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// A colour as floats of 0 to 255, with an opacity of 0 to 1.
struct Colour {
    double r = 0;
    double g = 0;
    double b = 0;
    double a = 1;
};

[[nodiscard]] Colour of(std::uint32_t rgb, double opacity) {
    return {.r = static_cast<double>((rgb >> 16U) & 0xFFU),
            .g = static_cast<double>((rgb >> 8U) & 0xFFU),
            .b = static_cast<double>(rgb & 0xFFU),
            .a = opacity};
}

[[nodiscard]] Colour mix(const Colour& p, const Colour& q, double t) {
    return {.r = p.r + ((q.r - p.r) * t),
            .g = p.g + ((q.g - p.g) * t),
            .b = p.b + ((q.b - p.b) * t),
            .a = p.a + ((q.a - p.a) * t)};
}

/// A stop's opacity as the SVG parser folds it into the colour's alpha.
[[nodiscard]] double folded(float opacity) {
    return static_cast<double>(std::floor((255.0F * opacity) + 0.5F)) / 255.0;
}

/// Where t lies along the ellipse `p` for the CSS point (x, y): its radius normalised by the ellipse's.
[[nodiscard]] double along(const passes::LampPool& p, double x, double y) {
    const double dx = (x - p.cx) / p.rx;
    const double dy = (y - p.cy) / p.ry;
    return std::sqrt((dx * dx) + (dy * dy));
}

} // namespace

TEST_CASE("ground: the street lamps' pools at night are their gradient over the ground, pixel for pixel") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the pools are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    const style::ThemeBlock night = test::night();
    const auto with = test::draw_ground(*session, camera, night, dir, "amsterdam");
    ATLAS_REQUIRE(with.has_value(), "the frame with the pools was not drawn");
    const auto without = test::draw_ground(*session, camera, night, dir, "amsterdam", {}, 40,
                                           [](passes::Ground& g) { g.draw_pools(false); });
    ATLAS_REQUIRE(without.has_value(), "the frame without the pools was not drawn");
    CHECK(without->decided.lamp_pools.empty());
    const std::vector<passes::LampPool>& pools = with->decided.lamp_pools;
    CHECK(pools.size() > 20);
    const gpu::Pixels& a = with->pixels;
    const gpu::Pixels& b = without->pixels;
    REQUIRE((a.width == b.width && a.height == b.height));
    const double density = camera.density;
    const Colour s0 = of(0xFFB45EU, folded(0.55F));
    const Colour s1 = of(0xFFA848U, folded(0.18F));
    const Colour s2 = of(0xFFA848U, folded(0.0F));
    std::size_t inside = 0;
    std::size_t outside = 0;
    std::size_t wrong = 0;
    std::size_t stray = 0;
    double worst = 0;
    for (std::uint32_t y = 0; y < a.height; ++y) {
        for (std::uint32_t x = 0; x < a.width; ++x) {
            const double ux = (static_cast<double>(x) + 0.5) / density;
            const double uy = (static_cast<double>(y) + 0.5) / density;
            const passes::LampPool* in = nullptr;
            std::size_t reached = 0;
            double t = 0;
            for (const passes::LampPool& p : pools) {
                const double u = along(p, ux, uy);
                if (u > 1.0 + (2.0 / (density * std::min(p.rx, p.ry)))) continue;
                ++reached;
                in = &p;
                t = u;
            }
            const std::size_t i = ((static_cast<std::size_t>(y) * a.width) + x) * 4;
            if (reached == 0) {
                ++outside;
                if (std::memcmp(&a.rgba[i], &b.rgba[i], 3) != 0) ++stray;
                continue;
            }
            if (reached > 1 || in == nullptr || t > 0.95) continue;
            const Colour g = t <= 0.5 ? mix(s0, s1, t * 2.0) : mix(s1, s2, (t - 0.5) * 2.0);
            const std::array<double, 3> under{static_cast<double>(b.rgba[i]), static_cast<double>(b.rgba[i + 1]),
                                              static_cast<double>(b.rgba[i + 2])};
            const std::array<double, 3> want{(g.r * g.a) + (under[0] * (1 - g.a)), (g.g * g.a) + (under[1] * (1 - g.a)),
                                             (g.b * g.a) + (under[2] * (1 - g.a))};
            double apart = 0;
            for (std::size_t c = 0; c < 3; ++c) apart = std::max(apart, std::abs(a.rgba[i + c] - want[c]));
            worst = std::max(worst, apart);
            if (apart > 2.0) ++wrong;
            ++inside;
        }
    }
    MESSAGE("the home at night: " << pools.size() << " lamps' pools; " << inside << " pixels inside one, the worst "
                                  << worst << " levels apart, " << wrong << " beyond two; " << outside
                                  << " pixels no pool reaches, " << stray << " of them changed");
    CHECK(inside > 1000);
    CHECK(wrong == 0);
    CHECK(stray == 0);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}
