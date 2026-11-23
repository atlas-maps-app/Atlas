/* The layout of the cleaning's interface as the library gives it (rust/clean/src/lib.rs's #[repr(C)] structs, on
 * a 64-bit target, the only kind the core is built for): each struct's size and alignment, and each field's offset,
 * in bytes. Written by the library's test (rust/clean/src/layout.rs; in rust/clean, `ATLAS_CLEAN_LAYOUT=write cargo
 * test --features std layout`), which fails while this file is not what it would write: not to be edited by hand.
 * load/Clean.cpp holds load/CleanAbi.h's structs to it. */
#ifndef ATLAS_LOAD_CLEAN_LAYOUT_H
#define ATLAS_LOAD_CLEAN_LAYOUT_H

/* clang-format off */

/* S(struct, size, alignment), for each struct */
#define ATLAS_CLEAN_STRUCTS(S) \
    S(atlas_clean_point, 8, 4) \
    S(atlas_clean_footprint, 32, 8) \
    S(atlas_clean_input, 64, 8) \
    S(atlas_clean_counts, 80, 8) \
    S(atlas_clean_output, 120, 8)

/* F(struct, field, offset), for each field in its order */
#define ATLAS_CLEAN_FIELDS(F) \
    F(atlas_clean_point, lat, 0) \
    F(atlas_clean_point, lon, 4) \
    F(atlas_clean_footprint, first_ring, 0) \
    F(atlas_clean_footprint, rings, 4) \
    F(atlas_clean_footprint, min, 8) \
    F(atlas_clean_footprint, top, 16) \
    F(atlas_clean_footprint, part, 24) \
    F(atlas_clean_footprint, pad, 25) \
    F(atlas_clean_input, points, 0) \
    F(atlas_clean_input, point_count, 8) \
    F(atlas_clean_input, ring_sizes, 16) \
    F(atlas_clean_input, ring_count, 24) \
    F(atlas_clean_input, footprints, 32) \
    F(atlas_clean_input, footprint_count, 40) \
    F(atlas_clean_input, metres_per_lon, 48) \
    F(atlas_clean_input, metres_per_lat, 56) \
    F(atlas_clean_counts, overlapping_pairs, 0) \
    F(atlas_clean_counts, uncovered_outlines, 4) \
    F(atlas_clean_counts, courtyards, 8) \
    F(atlas_clean_counts, covered, 12) \
    F(atlas_clean_counts, hidden, 16) \
    F(atlas_clean_counts, clipped, 20) \
    F(atlas_clean_counts, raised, 24) \
    F(atlas_clean_counts, vanished, 28) \
    F(atlas_clean_counts, seamed, 32) \
    F(atlas_clean_counts, unsettled, 36) \
    F(atlas_clean_counts, unmeasured, 40) \
    F(atlas_clean_counts, uncovered_m2, 48) \
    F(atlas_clean_counts, sliver_m2, 56) \
    F(atlas_clean_counts, dropped_m2, 64) \
    F(atlas_clean_counts, pairs_before, 72) \
    F(atlas_clean_counts, pad, 76) \
    F(atlas_clean_output, state, 0) \
    F(atlas_clean_output, min, 8) \
    F(atlas_clean_output, ring_first, 16) \
    F(atlas_clean_output, points, 24) \
    F(atlas_clean_output, counts, 32) \
    F(atlas_clean_output, owner, 112)

/* clang-format on */

#endif
