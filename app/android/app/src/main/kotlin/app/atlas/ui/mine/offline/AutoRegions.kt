package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.geo.LatLon

/**
 * "Download automatically": which regions to take offline around the places you go. Home and work get an area of
 * [RADIUS_KM] around them; a trip being guided gets the corridor along its road. A place already inside a region (or a
 * trip whose boxes are) is not asked for again, and what was asked for once is remembered so a region you delete does not
 * come back until the place changes.
 */
object AutoRegions {
    const val RADIUS_KM = 12.0
    const val CORRIDOR_M = 10_000.0

    enum class Kind { HOME, WORK, TRIP }

    /** One region wanted: what to call it, its box, why, and a key that says it was asked for (box corners, rounded). */
    data class Wanted(val kind: Kind, val name: String, val bbox: BBox) {
        val key: String get() = String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", bbox.west, bbox.south, bbox.east, bbox.north)
    }

    /**
     * The regions to ask for, given where [home] and [work] are, the points of the [trip] being guided (if any), what is on
     * the phone already ([have], boxes) and what was asked for before ([done], keys). Names come from the caller (they are
     * words in the person's language): [homeName], [workName] and [tripName].
     */
    fun plan(
        home: LatLon?, work: LatLon?, trip: List<LatLon>, have: List<BBox>, done: Set<String>,
        homeName: String, workName: String, tripName: String, partName: String,
    ): List<Wanted> {
        val out = ArrayList<Wanted>()
        home?.let { out += Wanted(Kind.HOME, homeName, PickerModel.radiusBox(it, RADIUS_KM)) }
        work?.let { out += Wanted(Kind.WORK, workName, PickerModel.radiusBox(it, RADIUS_KM)) }
        if (trip.size > 1) {
            val boxes = PickerModel.corridorBoxes(trip, CORRIDOR_M)
            boxes.forEachIndexed { i, b -> out += Wanted(Kind.TRIP, if (boxes.size > 1) "$tripName $partName ${i + 1}" else tripName, b) }
        }
        return out.filter { w -> w.key !in done && have.none { covers(it, w.bbox) } }
            .distinctBy { it.key }
    }

    /** Whether [region] holds [wanted] almost entirely: its centre and at least 80 % of its width and height. */
    fun covers(region: BBox, wanted: BBox): Boolean {
        if (wanted.center !in region) return false
        val w = overlap(region.west, region.east, wanted.west, wanted.east) / (wanted.east - wanted.west).coerceAtLeast(1e-9)
        val h = overlap(region.south, region.north, wanted.south, wanted.north) / (wanted.north - wanted.south).coerceAtLeast(1e-9)
        return w >= 0.8 && h >= 0.8
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0.0)
}
