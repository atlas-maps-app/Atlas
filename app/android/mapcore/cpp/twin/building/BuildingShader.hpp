// The buildings' vertex stage as C++ (plan-vector T6): shaders/building/building.slangh and windows.slangh compiled
// after base/hlsl.hpp and frame/FrameBlock.hpp (the camera, painter and theme modules), so the pass, its twin and its
// tests run the shader's own text. Its trigonometry (the outline's arcs) is StrictMath's, rounded to float; the GPU
// takes its own, which places an arc's points a few ulps apart from these (the outline is not exact code).
#pragma once

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "style/Light.hpp"

namespace atlas::shaders {

[[nodiscard]] inline float building_cos(float a) noexcept {
    return static_cast<float>(pymath::cos(static_cast<double>(a)));
}
[[nodiscard]] inline float building_sin(float a) noexcept {
    return static_cast<float>(pymath::sin(static_cast<double>(a)));
}
[[nodiscard]] inline float building_atan2(float y, float x) noexcept {
    return static_cast<float>(pymath::atan2(static_cast<double>(y), static_cast<double>(x)));
}

#include "shaders/building/building.slangh"
#include "shaders/building/windows.slangh"

static_assert(sizeof(BuildingPush) == 64, "building.slangh's push constants: a TilePlace and two four-lane vectors");

} // namespace atlas::shaders
