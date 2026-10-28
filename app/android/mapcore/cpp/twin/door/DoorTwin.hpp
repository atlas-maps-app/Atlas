// The doors' twin (plan-vector D16's L1; ENGINE.md step 4, the facades): what the doors pass draws at a frame, as the
// port's SVG elements (render/amsterdam.py's facade_details, its doors), into the twin's stream, each door in its item
// (its first address's node, the kind "door") at the item's slots, in the port's objects group:
//   - the door: a closed path as face() prints it, filled in its colour and stroked .5 in the outline's (the group's
//     round joins), at the item's first slot;
//   - its stoop: a closed path, filled, at its third;
//   - where it has one, its plate: a rect with its rx (SVG's ry follows it), at its fourth; and its number: a run in
//     `Georgia, serif` at its size, anchored in the middle, at its fifth.
// At rest every coordinate is as the SVG prints it ('%.1f', read back as SvgParser reads it); the number's size always
// is, as its glyphs are shaped at it.
#pragma once

#include "passes/Doors.hpp"
#include "twin/Twin.hpp"

#include <span>

namespace atlas::frame {
struct FrameState;
} // namespace atlas::frame

namespace atlas::twin {

/// The family the renders name for a door's number.
inline constexpr std::string_view door_number_family = "Georgia, serif";

/// Emits the doors `doors` (door_shapes') of frame `state` with the active pack's door colours `style` into `out`.
void emit_doors(const frame::FrameState& state, std::span<const passes::DoorShape> doors,
                const tiles::DoorsStyle& style, Stream& out);

} // namespace atlas::twin
