// Depth (plan-vector D6, rethought on 20 October 2026: true depth for what stands up). The depth buffer is reversed:
// the clear is 0, infinitely far, and a nearer sample has the greater depth, so the depth tests are GREATER (GREATER
// OR EQUAL where a primitive draws over its own fill). It holds two kinds of depth, in two ranges that never meet:
//   - True depth, for what stands up (the buildings, their parts and roofs; trees and things as they come): the
//     reciprocal depth r = near / k.z, 1 at the near plane and toward 0 far away, which float keeps to its relative
//     precision at every distance and the rasteriser interpolates exactly over a plane (r is affine in screen space).
//     What stands up occludes by where it is, at every angle: the renders order whole items by one point each, so at
//     some angles their buildings melt into each other, and the engine does not copy that.
//   - Painter depth, for the ground and what the renders layer on its one plane (land cover, areas, roads, water,
//     shadows, the canal names): every such primitive has a slot, its place in the renders' painter's order (the
//     ground's slots first, by their ground keys, then the items far to near, each item's primitives in its
//     emission order: CP/frame/Ranks.cpp gives each item the first of its slots), and draws at its slot's depth,
//     (slot + 1)·S, so a later slot is nearer. S puts every slot behind every true depth: 2^-60 on float depth (the
//     last slot, 2^-40, lies behind anything nearer than 6·10^11 m); 2^-20 on 24-bit depth, where the ground's slots
//     take the lower half and the true depths the upper one (0.5 + 0.5·r).
// The opaque primitives draw front to back (early depth testing shades each visible pixel about once), the translucent
// ones afterwards in painter's order with the test on and writes off: a translucent primitive passes where no opaque
// primitive of a later slot covers the sample, and the translucent ones blend in their order over the latest opaque one
// below them (CP/tests/frame/painter_vk_test.cpp). The painter switch (camera.slang's CAMERA_PAINTER: the comparison
// with the renders, and the owner mode) draws the objects at their slots' painter depths, as the renders order them.
//
// Painter depths are exact in binary32 and in a 24-bit depth buffer: slot s draws at (s + 1)·S, s below 2^20 (a million
// slots: the Main board needs about 88,000, G-M3), a 21-bit integer times a power of two, which D24 stores 16 levels
// apart and D32 as it is, so the rounding of z·w / w in the rasteriser (a few units of the last place) never reorders
// two slots.
//
// The objects bit (stencil bit 7): every primitive of the objects group that is opaque or drawn by alpha-to-coverage sets
// it on the samples it covers; the ground never does; the water's clip and the shadows' union use the low bits. The
// composite reads it to find the objects group's silhouettes (D5.4).
//
// Shared with the C++ twin (after base/hlsl.hpp and camera.slang, by CP/frame/FrameBlock.hpp). The painter depths
// are exact: conversions of integers below 2^24 and products with powers of two only.
#pragma once

/// How many slots a frame may have: 2^20.
static const uint PAINTER_SLOTS = 1048576u;

/// The objects group's stencil bit.
static const uint OBJECTS_BIT = 0x80u;

/// The stencil bits the ground and the overlays below the objects may use (the water's clip, the shadows' union).
static const uint GROUND_STENCIL_BITS = 0x7Fu;

/// The depth attachment's painter scale S: 2^-20 at 24 bits, 2^-60 in float.
inline float painter_scale(FrameCamera cam) {
    return (cam.flags.x & CAMERA_DEPTH24) != 0u ? 9.5367431640625e-7f : 8.673617379884035e-19f;
}

/// The depth of painter slot `slot` (below PAINTER_SLOTS): (slot + 1)·S, exact.
inline float painter_depth(FrameCamera cam, uint slot) {
    return float(slot + 1u) * painter_scale(cam);
}

/// The painter slot a painter depth holds, plus one (0: the clear, nothing drawn), read back to the nearest slot (the
/// owner mode: the rasteriser's interpolation stays within a few units of the last place, far inside half a slot).
inline uint painter_ordinal(FrameCamera cam, float depth) {
    float inverse = (cam.flags.x & CAMERA_DEPTH24) != 0u ? 1048576.0f : 1152921504606846976.0f; // 2^20, 2^60
    return uint(depth * inverse + 0.5f);
}

/// The true depth of reciprocal depth `r` (near / k.z, at most 1): r in float, 0.5 + 0.5·r at 24 bits (above every
/// painter slot).
inline float true_depth(FrameCamera cam, float r) {
    return (cam.flags.x & CAMERA_DEPTH24) != 0u ? 0.5f + 0.5f * r : r;
}

/// A rank table entry (set 0, binding 1; CP/frame/Ranks.cpp): an item's first painter slot (bits 0-19) and the detail
/// the render thread chose for it (bits 20-31): bit i set where its depth lies nearer than its kind's i-th detail
/// threshold (CP/frame/Selectors.hpp's details: the renders' `d < 460`, `d < win_d`, ...), compared in double as the
/// port compares them. A shader takes an item's detail from here and never recomputes it from a float depth, which
/// can fall on the other side of a threshold. RANK_NONE: the item is not drawn this frame (no drawn item's first slot
/// is the last, so no entry is it).
static const uint RANK_NONE = 0xFFFFFFFFu;
static const uint RANK_SLOT_BITS = 0x000FFFFFu;
static const uint RANK_DETAIL_SHIFT = 20u;

/// The entry of an item drawn from painter slot `slot` (below PAINTER_SLOTS - 1) with detail `detail` (12 bits).
inline uint rank_entry(uint slot, uint detail) {
    return slot | (detail << RANK_DETAIL_SHIFT);
}

/// An entry's first painter slot.
inline uint rank_slot(uint entry) {
    return entry & RANK_SLOT_BITS;
}

/// An entry's detail: bit i where its item lies nearer than its kind's i-th detail threshold.
inline uint rank_detail(uint entry) {
    return entry >> RANK_DETAIL_SHIFT;
}
