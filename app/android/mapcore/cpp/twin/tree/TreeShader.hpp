// The trees' and the street furniture's shared shader code as C++ (plan-vector T6): shaders/tree/tree.slangh and
// things.slangh compiled after base/hlsl.hpp and frame/FrameBlock.hpp (the camera, painter and theme modules), so the
// passes, their twins and their tests run the shader's own text.
#pragma once

#include "frame/FrameBlock.hpp"

namespace atlas::shaders {

#include "shaders/tree/tree.slangh"
// after tree.slangh, whose records and helpers it uses
#include "shaders/tree/things.slangh"

static_assert(sizeof(TreeRecord) == 48, "tree.slangh's records: three four-lane vectors");

} // namespace atlas::shaders
