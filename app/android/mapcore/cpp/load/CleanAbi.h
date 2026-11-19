/* The C side of the buildings' cleaning, a Rust library (app/android/mapcore/rust/clean, its src/lib.rs the other
 * side, which these declarations must match): a window's footprints in, on OSM's grid, and out what can stand there,
 * each as one ring that carries its courtyards and pieces by seams; and for stage T, which edges of a ring are seams.
 * load/Clean.hpp is the core's way in. */
#ifndef ATLAS_LOAD_CLEAN_ABI_H
#define ATLAS_LOAD_CLEAN_ABI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* A C header whose names are the library's (src/lib.rs): the core's rules for a type's name and for typedef are not
 * its own. NOLINTBEGIN(modernize-use-using,readability-identifier-naming) */

/* A grid point: latitude and longitude in 1e-7 degrees. */
typedef struct atlas_clean_point {
    int32_t lat;
    int32_t lon;
} atlas_clean_point;

/* A footprint: its rings (`rings` of them from `first_ring`: its outer ring, then its inner rings), the heights its
 * walls span (metres) and whether it is a building:part. */
typedef struct atlas_clean_footprint {
    uint32_t first_ring;
    uint32_t rings;
    double min;
    double top;
    uint8_t part;
    uint8_t pad[7];
} atlas_clean_footprint;

/* A window's footprints: the rings' points one after another, each ring's count, the footprints; and the grid's units
 * in metres where the window lies. */
typedef struct atlas_clean_input {
    const atlas_clean_point* points;
    size_t point_count;
    const uint32_t* ring_sizes;
    size_t ring_count;
    const atlas_clean_footprint* footprints;
    size_t footprint_count;
    double metres_per_lon;
    double metres_per_lat;
} atlas_clean_input;

/* What the cleaning counts. */
typedef struct atlas_clean_counts {
    uint32_t overlapping_pairs;  /* drawn footprints still overlapping, in plan and in height */
    uint32_t uncovered_outlines; /* outlines more than 5 m^2 of which nothing draws */
    uint32_t courtyards;         /* footprints with inner rings */
    uint32_t covered;            /* outlines cut by their parts */
    uint32_t hidden;             /* outlines their parts cover whole */
    uint32_t clipped;            /* footprints cut where another stands */
    uint32_t raised;             /* footprints standing on others, their base raised to them */
    uint32_t vanished;           /* footprints others cover whole */
    uint32_t seamed;             /* rings that carry seams */
    uint32_t unsettled;          /* operations whose arrangement did not settle (their footprints left as they were) */
    uint32_t unmeasured;         /* outlines whose uncovered area could not be measured */
    double uncovered_m2;         /* the area of the outlines nothing draws, but slivers and crumbs */
    double sliver_m2;      /* the outlines' slivers (narrower than 0.5 m) and crumbs (under 3 m^2) nothing draws */
    double dropped_m2;     /* what the cuts left that went as slivers or crumbs, of every footprint */
    uint32_t pairs_before; /* drawn pairs overlapping in plan and in height before any was clipped */
    uint32_t pad;
} atlas_clean_counts;

/* Per footprint: its state (ATLAS_CLEAN_DRAWN, ATLAS_CLEAN_RING: its new ring is points[ring_first[k]] up to
 * points[ring_first[k + 1]], clockwise as latitude and longitude are read; and what changed it, ATLAS_CLEAN_COVERED:
 * its parts cut it, ATLAS_CLEAN_CLIPPED: another footprint did, ATLAS_CLEAN_COURTYARD: it has courtyards as mapped)
 * and its base. Owned by the library until atlas_clean_free. */
typedef struct atlas_clean_output {
    const uint8_t* state;
    const double* min;
    const size_t* ring_first;
    const atlas_clean_point* points;
    atlas_clean_counts counts;
    void* owner;
} atlas_clean_output;

#define ATLAS_CLEAN_DRAWN 1
#define ATLAS_CLEAN_RING 2
#define ATLAS_CLEAN_COVERED 4
#define ATLAS_CLEAN_CLIPPED 8
#define ATLAS_CLEAN_COURTYARD 16

/* Cleans a window's footprints into `out`: 0 done, 1 an input whose counts do not add up (`out` then holds nothing). */
int32_t atlas_clean_run(const atlas_clean_input* input, atlas_clean_output* out);

/* Frees what atlas_clean_run made. */
void atlas_clean_free(atlas_clean_output* out);

/* For a closed ring of `count` points (x, z pairs, compared bit for bit): out[i] is 1 where the edge from point i to
 * point i + 1 is a seam (its reverse is an edge of the ring too), else 0. Returns how many are. */
size_t atlas_clean_seams(const double* xz, size_t count, uint8_t* out);

/* NOLINTEND(modernize-use-using,readability-identifier-naming) */

#ifdef __cplusplus
}
#endif

#endif
