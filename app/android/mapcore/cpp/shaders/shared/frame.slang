// The frame's uniform block (plan-vector D5.6): set 0, binding 0, at each frame's dynamic offset in the frame's ring.
// What every pass reads of the frame: the camera (camera.slang), the moment's theme (theme.slang), the screen layers the
// composite applies (composite.slang), the frame's counts and its pack's strokes. CP/frame/FrameBuilder.cpp writes it once a frame, before
// the frame records; set 0 also holds the frame's rank table (binding 1: each item's first painter slot, CP/frame/
// Ranks.cpp) and its selector table (binding 2: per item kind, the frame's detail choices, CP/frame/Selectors.cpp),
// storage buffers at the same frame's offsets.
//
// Shared with the C++ twin (after base/hlsl.hpp, by CP/frame/FrameBlock.hpp): four-lane vectors of 32-bit values only,
// so std140, std430 and C++ lay it out the same way: 880 bytes.
#pragma once

#include "camera.slang"
#include "composite.slang"
#include "theme.slang"

/// The frame's mode (FrameBlock.info.x).
static const uint FRAME_FULL = 1u;   // full mode: every effect on (D16); else structural: the core's filters off
static const uint FRAME_ENGINE = 2u; // engine mode: the renders' radius cut (D9)
static const uint FRAME_NIGHT = 4u;  // the moment is night (th['night'])
// The screen-wide noise fields and the byte tables are bound (K17a's provider of set 1, frame/Globals.hpp) and the frame
// is in full mode: the effects that read them (mat, grain) run; off, they are stubbed.
static const uint FRAME_FIELDS = 256u;
// The app's Look settings' grain is off: the materials' grain (`mat`) leaves the fills, as the paper's grain leaves the
// composite (its COMPOSITE_GRAIN step). The crowns' filter, which also runs where mat_on says, stays.
static const uint FRAME_NO_GRAIN = 512u;
// The map leaves the trees' leaf filters out (style::camera_part::leaf_filters): the crowns are drawn as their circles,
// as where mat_on says no.
static const uint FRAME_NO_CROWNS = 1024u;

/// The thin strokes of the buildings in the style of the frame's active pack (tiles/Style.hpp's EdgesStyle, the engine's
/// style file's `buildings` and `houseboats`; a coarser pack drawn around it, made with the same file, draws them so
/// too): the roofs' edges by day and at night and a houseboat cabin roof's, sRGB 0 to 1 at 8-bit levels (opaque); their
/// widths in CSS px: x a roof's on its slopes, y on its tops, z a cabin roof's, w a wall's (in its own colour).
struct FrameStrokes {
    float4 roof_day;
    float4 roof_night;
    float4 cabin;
    float4 widths;
};

/// The windows in the style of the frame's active pack (tiles/Style.hpp's WindowsStyle, the style file's `windows` and
/// `houseboats`), each colour 0xRRGGBB (sRGB): the glass's gradients by day with weather (top, then bottom), its flat
/// glass without weather and at night, a warehouse's shutters, the frames, the frames' width (CSS px, a float's bits)
/// and the deep windows' sills; a shop's glazing's frame and its width; the facades' plinths and cornices; a houseboat
/// cabin's windows' frames, their glass at night and the frames' width.
struct FrameWindows {
    uint4 glass;   // x, y: the glass under a clear sky; z, w: under an overcast one
    uint4 lit;     // x, y: the glass the sun is on; z: the lit glass without weather; w: the frames' width
    uint4 dark;    // x: the glass without weather by day, y: at night; z: a warehouse's shutters by day, w: at night
    uint4 frame;   // x: the frames by day, y: at night; z: the sills by day, w: at night
    uint4 shop;    // x: a shop's glazing's frame by day, y: at night; z: its width; w: 0
    uint4 facades; // x: the plinths by day, y: at night; z: the cornices by day, w: at night
    uint4 cabin;   // x: a cabin's windows' frames by day, y: at night; z: their glass at night; w: the frames' width
};

struct FrameBlock {
    FrameCamera camera;
    Theme theme;
    FrameLayers layers;
    uint4 info; // x: the FRAME_ bits; y: the frame's painter slots (the ground's and the items'); z: its items; w: 0
    FrameStrokes strokes;
    FrameWindows windows;
};

/// Set 0's bindings: the block, the rank table, the selector table.
static const uint FRAME_SET = 0u;
static const uint FRAME_BLOCK_BINDING = 0u;
static const uint FRAME_RANKS_BINDING = 1u;
static const uint FRAME_SELECTORS_BINDING = 2u;
