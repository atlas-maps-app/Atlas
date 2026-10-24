// The ground's lines' shared shader code as C++ (plan-vector T6): shaders/ground/lines.slangh, the line stage's
// segment shapes and coverage, compiled after base/hlsl.hpp and frame/FrameBlock.hpp, and setts.slangh, a sett road's
// courses as one layer, over a run of lines kept in memory, so the pass and the tests run the shader's own text.
#pragma once

#include "frame/FrameBlock.hpp"

namespace atlas::shaders {

/// Slang's length, as the shared modules take it.
[[nodiscard]] inline float length(float2 v) noexcept { return sqrt(dot(v, v)); }

#include "shaders/ground/lines.slangh"

/// A pair run (a sett road's courses) as the tests keep it: its lines, their ends in device px.
struct SettRun {
    const SettLine* lines = nullptr;
};

/// Line i of the run.
[[nodiscard]] inline SettLine sett_line(SettRun r, uint i) noexcept { return r.lines[i]; }

// after lines.slangh, SettRun and sett_line, which it reads
#include "shaders/ground/setts.slangh"

} // namespace atlas::shaders
