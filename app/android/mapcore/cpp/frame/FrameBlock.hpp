// The frame's shared shader modules as C++ (plan-vector T6, D5.6): shaders/shared/frame.slang, with camera.slang,
// composite.slang and theme.slang, compiled after base/hlsl.hpp, so the frame builder writes the block the shaders read
// and the twin runs the shaders' own text (the camera's projection and snap, painter depth, the composite's
// arithmetic). Everything lands in atlas::shaders, beside style/Light.hpp's light.
#pragma once

#include "base/hlsl.hpp"

#include <cmath>
#include <cstddef>

namespace atlas::shaders {

using namespace atlas::hlsl;

/// The square root the composite's vignette takes (IEEE's, correctly rounded; the GPU rounds its own way, and the
/// vignette is not exact code).
[[nodiscard]] inline float sqrt(float x) noexcept { return std::sqrt(x); }

#include "shaders/shared/frame.slang"
#include "shaders/shared/painter.slang"

static_assert(sizeof(FrameCamera) == 112, "camera.slang's FrameCamera: seven four-lane vectors");
static_assert(sizeof(TilePlace) == 32);
static_assert(sizeof(FrameLayers) == 288, "composite.slang's FrameLayers: eighteen four-lane vectors");
static_assert(sizeof(FrameBlock) == 704, "frame.slang's FrameBlock: the camera, the theme, the layers and the counts");
static_assert(offsetof(FrameBlock, theme) == 112 && offsetof(FrameBlock, layers) == 400 &&
              offsetof(FrameBlock, info) == 688);
static_assert(alignof(FrameBlock) == 16);

} // namespace atlas::shaders
