// The ground shadows' blur grid (passes/Shadows.cpp, fx/BlurGrid.hpp): the box of the frame's drawn shadows that
// shaders/ground/ground.slang's shadow_box_cs measures, and the filter's grid shadow_grid_cs works out from it, as the
// port's raster lays out `sblur` (its region, WebKit's 4096² clamp of the scale, the rectangles drawn and read, vImage's
// box). Read by the mask's vertex stage, the blur (shaders/ground/shadow_blur.slang) and the composite. 96 bytes, every
// member a four-lane vector of 32-bit values.
#pragma once

struct ShadowGrid {
    uint4 box;    // the drawn shadows' box in CSS px (min x, min y, max x, max y) as order-preserving keys (box_key)
    float4 scale; // x: filter px per CSS px (the density times the clamp), y: the clamp, z: the opacity, w: 0
    int4 input;   // the rectangle the blur reads, filter px: left, top, width, height (the mask's texel (0, 0) is its
                  // corner)
    int4 output;  // the rectangle drawn, filter px: left, top, right, bottom
    uint4 blur;   // x: vImage's box (odd), y: half of it, z: box² / 2 (the rounding), w: the SHADOW_GRID_ flags
    float4 inv;   // x: 1 / box² (the division, shadow_blur.slang); y, z, w: 0
};

/// The shadows draw: their box has an area and the region reaches the frame.
static const uint SHADOW_GRID_DRAWN = 1u;
/// WebKit's clamp shrank the scale: the result is drawn back filtered (bilinearly), as the port draws its bitmap.
static const uint SHADOW_GRID_FILTERED = 2u;

/// A float's key that orders as the float does, as an unsigned integer (atomic min and max): negative values flipped
/// whole, positive ones with the sign bit set.
inline uint box_key(float v) {
    uint b = asuint(v);
    return (b & 0x80000000u) != 0u ? ~b : (b | 0x80000000u);
}

/// The float of a key.
inline float box_value(uint k) {
    return asfloat((k & 0x80000000u) != 0u ? (k & 0x7FFFFFFFu) : ~k);
}
