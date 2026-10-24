// The trees' shared shader code as C++ (plan-vector T6): shaders/tree/tree.slangh compiled after base/hlsl.hpp and
// frame/FrameBlock.hpp (the camera, painter and theme modules), so the pass, its twin and its tests run the shader's
// own text.
#pragma once

#include "frame/FrameBlock.hpp"

namespace atlas::shaders {

#include "shaders/tree/tree.slangh"

static_assert(sizeof(TreeRecord) == 48, "tree.slangh's records: three four-lane vectors");

} // namespace atlas::shaders
