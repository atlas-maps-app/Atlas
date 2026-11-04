// The shopfronts' twin (plan-vector D16's L1; ENGINE.md step 4, the facades and the night): what the shopfronts pass
// draws at a frame, as the port's SVG elements (render/amsterdam.py's shopfront and the night's `open` glows), into the
// twin's stream, in the port's objects group (with weather alone: its `mat` filter):
//   - a shopfront's board, in its item (its node, the kind "shopfront") at the item's slots: its frame and its glass
//     (closed paths, by day the glass the walls' `glass` gradient, `glassLit` while it is open), its mullions (open
//     paths, stroked), its awning (filled and stroked .4, the group's round joins), its stripes (one path of closed
//     subpaths at opacity .85), its sign board (filled and stroked .4), its text (`Geist, Helvetica, sans-serif` 800,
//     anchored in the middle, its letter spacing) and at night while it is open its spill (a closed path in a group at
//     opacity .55);
//   - its blade, in its own item (the kind "blade"): the bracket, the blade (filled and stroked .5), its text
//     (turned by rotate(90) about its anchor where the blade is seen edge-on);
//   - each table of its terrace, in its own item (the kind "terrace"): its leg, each chair's leg, seat and back, its
//     top (filled and stroked .3), by day its parasol's pole and twelve panels.
// And at night each open place's glow: a circle filled with the `open` gradient, after every object (the renders'
// `glow`, before wfx, the haze and fin), outside any group and item.
// At rest every coordinate is as the SVG prints it ('%.1f', read back as SvgParser reads it); every text's size always
// is, as its glyphs are shaped at it.
#pragma once

#include "passes/Shopfronts.hpp"
#include "twin/Twin.hpp"

namespace atlas::frame {
struct FrameState;
} // namespace atlas::frame

namespace atlas::twin {

/// Emits the shops `shops` (shop_shapes') of frame `state` with the active pack's shopfront colours `style` into `out`.
void emit_shops(const frame::FrameState& state, const passes::ShopFrame& shops, const tiles::ShopfrontsStyle& style,
                Stream& out);

} // namespace atlas::twin
