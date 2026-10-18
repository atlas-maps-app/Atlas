// Painter's order as depth (plan-vector D6): the depth buffer holds painter ranks, not distances. Every primitive of a
// frame has a slot, its place in the renders' painter's order (the ground's slots first, by their ground keys, then the
// items far to near, each item's primitives in its emission order: CP/frame/Ranks.cpp gives each item the first of its
// slots), and draws at the depth of its slot. A later slot is nearer: its depth is smaller, so with the depth test LESS
// the opaque primitives can be drawn front to back (early depth testing shades each visible pixel about once), and the
// translucent ones afterwards in painter's order with the test on and writes off. The two passes give strict painter's
// order exactly: a translucent primitive passes where no opaque primitive of a later slot covers the sample, and the
// translucent ones blend in their order over the latest opaque one below them (CP/tests/frame/painter_vk_test.cpp).
//
// The depths are exact in binary32 and in a 24-bit depth buffer: slot s draws at 1 − (s + 1)·2^-20, a multiple of 2^-20
// (s below 2^20, a million slots: the Main board needs about 88,000, G-M3), which D24 stores 16 levels apart and D32 as
// it is, so the rounding of z·w / w in the rasteriser (a few units of 2^-24) never reorders two slots. The clear depth is
// 1, behind every slot.
//
// The objects bit (stencil bit 7): every primitive of the objects group that is opaque or drawn by alpha-to-coverage sets
// it on the samples it covers; the ground never does; the water's clip and the shadows' union use the low bits. The
// composite reads it to find the objects group's silhouettes (D5.4).
//
// Shared with the C++ twin (after base/hlsl.hpp, by CP/frame/Ranks.hpp). Exact: +, −, · and conversions of integers
// below 2^24 only.
#pragma once

/// How many slots a frame may have: 2^20.
static const uint PAINTER_SLOTS = 1048576u;

/// The objects group's stencil bit.
static const uint OBJECTS_BIT = 0x80u;

/// The stencil bits the ground and the overlays below the objects may use (the water's clip, the shadows' union).
static const uint GROUND_STENCIL_BITS = 0x7Fu;

/// The depth of painter slot `slot` (below PAINTER_SLOTS): 1 − (slot + 1)·2^-20, exact.
inline float painter_depth(uint slot) {
    return 1.0f - float(slot + 1u) * 9.5367431640625e-7f; // 2^-20
}

/// A rank table entry (set 0, binding 1; CP/frame/Ranks.cpp): an item's first painter slot (bits 0-23) and the detail
/// band the render thread chose for it (bits 30-31), from the ranks' depth in double against its kind's thresholds in
/// double, as the port compares them. A shader takes an item's band from here and never recomputes it from a float
/// depth, which can fall on the other side of a threshold. RANK_NONE: the item is not drawn this frame.
static const uint RANK_NONE = 0xFFFFFFFFu;
static const uint RANK_SLOT_BITS = 0x00FFFFFFu;
static const uint RANK_BAND_SHIFT = 30u;

/// The entry of an item drawn from painter slot `slot` (below PAINTER_SLOTS) in detail band `band` (0 to 3).
inline uint rank_entry(uint slot, uint band) {
    return slot | (band << RANK_BAND_SHIFT);
}

/// An entry's first painter slot.
inline uint rank_slot(uint entry) {
    return entry & RANK_SLOT_BITS;
}

/// An entry's detail band.
inline uint rank_band(uint entry) {
    return entry >> RANK_BAND_SHIFT;
}
