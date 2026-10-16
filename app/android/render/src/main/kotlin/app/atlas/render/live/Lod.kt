package app.atlas.render.live

import app.atlas.render.Regions

/**
 * Levels of detail: `regions.load(view)` simplifies footprints and areas with the view's tolerance (regions.py's
 * `GENERALISE`, metres: detail below it does not show at that distance). The live map loads a region at a few
 * tolerance bands, the steps of that table, and picks one from the camera's distance; a camera on one of the region's
 * own views takes that view's own tolerance, so it renders exactly as the board did.
 */
object Lod {
    /** The bands, metres: street level, close, near, the town, the overview. */
    val BANDS = doubleArrayOf(0.0, 0.15, 0.3, 0.6, 0.8)

    /**
     * The distances (metres from the eye to the target) where a band ends, read off the table: the street-level camera
     * at 0, Notting Hill's close view (330 m) at 0.15, the 430–470 m close views at 0.2–0.3, the 560–880 m ones at
     * 0.4, the town views (1.6–3.6 km) at 0.5–0.9.
     */
    val UPTO = doubleArrayOf(250.0, 400.0, 700.0, 2000.0)

    /** The band for a camera [dist] metres from its target. */
    fun band(dist: Double): Double {
        for (i in UPTO.indices) if (dist < UPTO[i]) return BANDS[i]
        return BANDS[BANDS.size - 1]
    }

    /** The tolerance `regions.load` gives a named [view] of [region], as its own lookup does. */
    fun ofView(region: String, view: String): Double =
        Regions.GENERALISE[view] ?: Regions.GENERALISE[view.replace("_detail", "").replace("_kit", "")] ?: Regions.GENERALISE[region] ?: 0.0

    /** The tolerance for a camera: its view's own when it matched one, else the band of its distance. */
    fun tolerance(region: String, view: String?, dist: Double): Double = if (view != null) ofView(region, view) else band(dist)

    /** The view name a band loads under: `<region>_lod<millimetres>`, registered with that tolerance. */
    fun bandView(region: String, tolerance: Double): String = "${region}_lod${Math.round(tolerance * 1000)}"
}
