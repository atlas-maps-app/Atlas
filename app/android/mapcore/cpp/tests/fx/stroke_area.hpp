// The objects' thin strokes' coverage as the GPU computes it (shaders/fx/strokes.slang's stroke_area), step by step in
// float, for the tests that hold frames with thin strokes to a CPU model of them (tests/fx/strokes_vk_test.cpp,
// tests/building/buildings_vk_test.cpp).
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace atlas::test {

/// The area of the segment from (ax, ay) to (bx, by) (output px) stroked `hw` either side with butt ends, within pixel
/// (x, y): the pixel's square in the segment's own frame, clipped to the rectangle (Sutherland and Hodgman), by the
/// shoelace, at most 1.
[[nodiscard]] inline float stroke_area(float ax, float ay, float bx, float by, float hw, std::uint32_t x,
                                       std::uint32_t y) {
    const float dx = bx - ax;
    const float dy = by - ay;
    const float len = std::sqrt((dx * dx) + (dy * dy));
    if (!(len > 0.0F) || !(hw > 0.0F)) return 0.0F;
    const float ux = dx / len;
    const float uy = dy / len;
    const float nx = -uy;
    const float ny = ux;
    const float rx = (static_cast<float>(x) + 0.5F) - ax;
    const float ry = (static_cast<float>(y) + 0.5F) - ay;
    const float sc = (rx * ux) + (ry * uy);
    const float tc = (rx * nx) + (ry * ny);
    constexpr float half_diagonal = 0.70710678F;
    if (std::abs(tc) > hw + half_diagonal || sc < -half_diagonal || sc > len + half_diagonal) return 0.0F;
    std::vector<std::array<float, 2>> p;
    for (const auto& o :
         {std::array{-0.5F, -0.5F}, std::array{0.5F, -0.5F}, std::array{0.5F, 0.5F}, std::array{-0.5F, 0.5F}}) {
        p.push_back({(o[0] * ux) + (o[1] * uy), tc + ((o[0] * nx) + (o[1] * ny))});
    }
    auto clip = [&p](std::size_t axis, float bound, float side) {
        std::vector<std::array<float, 2>> q;
        for (std::size_t i = 0; i < p.size(); ++i) {
            const auto& c = p[i];
            const auto& e = p[(i + 1) % p.size()];
            const float dc = side * (c[axis] - bound);
            const float de = side * (e[axis] - bound);
            if (dc <= 0.0F) q.push_back(c);
            if ((dc <= 0.0F) != (de <= 0.0F)) {
                const float t = dc / (dc - de);
                q.push_back({c[0] + ((e[0] - c[0]) * t), c[1] + ((e[1] - c[1]) * t)});
            }
        }
        p = std::move(q);
    };
    clip(0, -sc, -1.0F);
    clip(0, len - sc, 1.0F);
    clip(1, -hw, -1.0F);
    clip(1, hw, 1.0F);
    float twice = 0.0F;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& c = p[i];
        const auto& e = p[(i + 1) % p.size()];
        twice += (c[0] * e[1]) - (e[0] * c[1]);
    }
    return std::min(std::abs(twice) * 0.5F, 1.0F);
}

} // namespace atlas::test
