// The text runs a pass lays on the ground for the ground's text pass to draw (passes/GroundText.hpp), each at its
// painter slot among the ground's: the runways' designators (passes/Ground.cpp, regions.py's draw_over: runway_ends).
// The frame builder holds them, empties them as each frame's passes update and hands them to every pass
// (FrameState::ground_runs): the passes that lay runs update before the ground's text, which draws them.
#pragma once

#include "text/GroundText.hpp"

#include <cstdint>
#include <vector>

namespace atlas::frame {

/// One run laid on the ground: the port's `<text>` at its painter slot.
struct GroundRun {
    text::TextRun run;
    std::uint32_t slot = 0;
};

/// The frame's runs, in the order they were laid.
struct GroundRuns {
    std::vector<GroundRun> runs;
};

} // namespace atlas::frame
