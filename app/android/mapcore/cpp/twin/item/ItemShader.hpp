// The region items' shared shader code as C++ (plan-vector T6): shaders/item/items.slangh compiled after the trees'
// (twin/tree/TreeShader.hpp: tree.slangh's coverage tests and prints, which it uses), so the pass, its twin and its
// tests run the shader's own text.
#pragma once

#include "twin/tree/TreeShader.hpp"

namespace atlas::shaders {

#include "shaders/item/items.slangh"

static_assert(sizeof(ItemRecord) == 80, "items.slangh's records: five four-lane vectors");

} // namespace atlas::shaders
