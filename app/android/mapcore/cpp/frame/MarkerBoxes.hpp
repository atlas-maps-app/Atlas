// The boxes the markers take on the frame, for the labels to keep clear of (FrameState::marker_boxes): passes/Markers
// lays the markers out and fills them, each placed marker's box and a named pin's name beside it (device px, as
// OverlayPainter.layoutMarkers hands them to the labels), and passes/Labels, which updates after it, places the labels
// clear of them. The frame builder holds them, empties them as each frame's passes update and hands them to every pass.
#pragma once

#include "labels/Collision.hpp"

#include <vector>

namespace atlas::frame {

/// The frame's markers' boxes.
struct MarkerBoxes {
    std::vector<labels::Box> boxes;
};

} // namespace atlas::frame
