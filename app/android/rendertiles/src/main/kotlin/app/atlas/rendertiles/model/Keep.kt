package app.atlas.rendertiles.model

/**
 * Keep codes: one byte per vertex of a simplified ring (plan-vector D2, N2).
 *
 * `regions.py`'s `simplify` (Douglas–Peucker on a closed ring) splits where the farthest point lies, which does not
 * depend on the tolerance, so a vertex kept at a tolerance is kept at every smaller one. A vertex's code is the number of
 * the ladder's positive tolerances ([TOLERANCES] from index 1) it survives: 0 survives none (only the unsimplified
 * ring, tolerance 0, has it), [ALL] survives every one. So `simplify(ring, TOLERANCES[i])` is the vertices whose code is
 * at least i, in order, with `simplify`'s two exceptions, which the reader applies itself: a ring of fewer than
 * [MIN_POINTS] points is never simplified, and a result of fewer than 3 points or of area 2 m² or less falls back to the
 * whole ring (the area in the pack's frame, the port's `signed_area`).
 *
 * Codes are computed in the pack's frame (its metres decide the distances), and only at the ladder's tolerances.
 */
object Keep {
    /**
     * The ladder, metres: 0 (no simplification); every tolerance `regions.py`'s `GENERALISE` names and the live map's
     * bands (`Lod.BANDS`); 1.5 m for L13; 4, 16 and 25 m for the far levels. Codes 14 and 15 are spare.
     */
    val TOLERANCES: DoubleArray get() = doubleArrayOf(0.0, 0.15, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.5, 4.0, 16.0, 25.0)

    /** The code of a vertex that survives every tolerance of the ladder (the ring's anchors, and clip points). */
    const val ALL = 13

    /** `simplify` leaves rings of fewer points than this as they are. */
    const val MIN_POINTS = 6

    /** The ladder index of [tolerance], or -1 when it is not on the ladder. */
    fun indexOf(tolerance: Double): Int = TOLERANCES.indexOfFirst { it == tolerance }
}
