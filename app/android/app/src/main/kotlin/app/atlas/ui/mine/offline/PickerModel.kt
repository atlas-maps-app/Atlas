package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.search.PlaceSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** What the region picker works out before anything is downloaded: boxes from a radius, a lasso or a route, and names. */
object PickerModel {
    enum class Mode {
        AREA, ROUTE, COUNTRY;

        companion object {
            fun of(s: String?): Mode = when (s) { "route" -> ROUTE; "country" -> COUNTRY; else -> AREA }
        }
    }

    /** A drawn area bigger than this (square km) is refused unless a published pack covers it: it would take hours to build. */
    const val MAX_DRAWN_KM2 = 40_000.0

    /** The area [km] around [centre]. */
    fun radiusBox(centre: LatLon, km: Double): BBox = BBox.around(centre, km * 1000.0)

    /** The box around a hand-drawn loop, at least [minKm] each way; null for a scribble of fewer than three points. */
    fun lassoBox(points: List<LatLon>, minKm: Double = 1.0): BBox? {
        if (points.size < 3) return null
        val b = BBox.of(points) ?: return null
        val (w, h) = sides(b)
        return if (w >= minKm && h >= minKm) b else b.buffer(minKm * 500.0)
    }

    /** A point on a canvas of [w] × [h] pixels showing [view], as a position on the earth (x east, y down). */
    fun toLatLon(x: Float, y: Float, w: Float, h: Float, view: BBox): LatLon =
        LatLon(view.north - (y / h) * (view.north - view.south), view.west + (x / w) * (view.east - view.west))

    /**
     * [view] grown until it has the shape [aspect] (width over height) with the same centre, so that a kilometre is as long
     * across as it is down on a canvas of that shape.
     */
    fun fitView(view: BBox, aspect: Float): BBox {
        val (w, h) = sides(view)
        val wk = max(w, h * aspect)
        val hk = wk / aspect
        val c = view.center
        val dLat = hk / 111.32 / 2
        val dLon = wk / (111.32 * cos(Math.toRadians(c.lat))) / 2
        return BBox(c.lon - dLon, c.lat - dLat, c.lon + dLon, c.lat + dLat)
    }

    /** The width and height of [b] in kilometres. */
    fun sides(b: BBox): Pair<Double, Double> {
        val midLat = Math.toRadians((b.south + b.north) / 2)
        return abs(b.east - b.west) * 111.32 * cos(midLat) to abs(b.north - b.south) * 111.32
    }

    /**
     * The boxes that take the corridor of a route offline: the line is cut where one box would grow past [maxSideKm], so a
     * long diagonal drive costs its road and not the whole rectangle around it. Each box is [widthM] wider on every side.
     * At most [maxParts] boxes: the limit grows until the route fits.
     */
    fun corridorBoxes(points: List<LatLon>, widthM: Double, maxSideKm: Double = 40.0, maxParts: Int = 12): List<BBox> {
        if (points.isEmpty()) return emptyList()
        var limit = maxSideKm
        while (true) {
            val parts = cut(points, limit).map { it.buffer(widthM) }
            if (parts.size <= maxParts || limit > 20_000.0) return parts
            limit *= 1.5
        }
    }

    private fun cut(points: List<LatLon>, maxSideKm: Double): List<BBox> {
        val out = ArrayList<BBox>()
        var cur = BBox(points[0].lon, points[0].lat, points[0].lon, points[0].lat)
        var last = points[0]
        for (i in 1 until points.size) {
            val p = points[i]
            val grown = cur.extend(p)
            val (w, h) = sides(grown)
            if (max(w, h) > maxSideKm) {
                out += cur
                cur = BBox(last.lon, last.lat, last.lon, last.lat).extend(p)
            } else cur = grown
            last = p
        }
        out += cur
        return out
    }

    /** A round grid step in kilometres for a view [spanKm] wide: 1, 2, 5, 10, 20, 50… giving four to eight lines. */
    fun gridStepKm(spanKm: Double): Double {
        val raw = spanKm / 5.0
        var step = 1.0
        for (m in listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0)) { step = m; if (m >= raw) break }
        return step
    }

    /** "Amsterdam" for a box centred in it: the nearest town, city, village or suburb the phone knows, else [fallback]. */
    fun townNear(source: PlaceSource, centre: LatLon, fallback: String): String {
        val box = BBox.around(centre, 25_000.0)
        var best: Pair<Double, String>? = null
        for (kind in listOf("city", "town", "village", "suburb", "hamlet")) {
            for (r in runCatching { source.byTag("place", kind, box, 20) }.getOrDefault(emptyList())) {
                val name = r.place.name()?.takeIf { it.isNotBlank() } ?: continue
                val d = Geo.distance(centre, r.place.location) * (if (kind == "city" || kind == "town") 0.5 else 1.0)
                if (best == null || d < best.first) best = d to name
            }
        }
        return best?.second ?: fallback
    }

    /** [name] unless a region of that id exists: then "name 2", "name 3". */
    fun unique(name: String, takenIds: Set<String>): String {
        if (RegionEngine.idFor(name) !in takenIds) return name
        var n = 2
        while (RegionEngine.idFor("$name $n") in takenIds) n++
        return "$name $n"
    }

    /** Whether a box is too large to build, given whether a published pack covers it. */
    fun tooBig(b: BBox, packed: Boolean): Boolean = !packed && RegionEngine.area(b) > MAX_DRAWN_KM2

    /** The kilometres across the shorter side, rounded: "radius" of a box for the slider's label. */
    fun radiusKm(b: BBox): Int { val (w, h) = sides(b); return (min(w, h) / 2).toInt() }
}
