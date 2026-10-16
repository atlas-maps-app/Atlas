package app.atlas.ui.mine.water

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.search.PlaceSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A source of water from the map, with what its tags say about drinking it. */
data class WaterSource(val place: Place, val kind: Kind, val name: String, val status: Status, val distanceM: Double, val alongM: Double?) {
    enum class Kind(val symbol: String, val colour: Long) {
        SPRING("nature.spring.2", 0xFF1E9E5A), TAP("place.tap", 0xFF2E7BC4), WELL("place.hand-pump", 0xFF8A929C), POINT("place.drinking-water", 0xFF2E7BC4), FOUNTAIN("water.flowing", 0xFF2E7BC4),
    }

    /** What the tags say: drinkable (potable), seasonal (it may be dry), not for drinking, or nothing said. */
    enum class Status(val colour: Long) { POTABLE(0xFF2E7BC4), SEASONAL(0xFF8A929C), NOT_DRINKING(0xFFC8322A), UNSAID(0xFF1E9E5A) }

    val key: String get() = place.key
}

/** The water you carry and how far it takes you. */
data class Supply(val litresLeft: Double, val rateLitresPerHour: Double, val minutes: Double, val rangeKm: Double)

/** What the water screen works out from the map and the forecast: sources around or along a route, and whether your bottle is enough. */
object WaterModel {
    private const val WALKING_KMH = 4.5
    private const val BEHIND_M = 50.0

    /** Sources within [radiusM] of [centre], nearest first, or, with a [route], those within [offRouteM] of it, in order along it. */
    fun sources(source: PlaceSource, centre: LatLon, radiusM: Double = 6_000.0, route: Route? = null, offRouteM: Double = 300.0): List<WaterSource> {
        val box = BBox.around(centre, radiusM).let { b -> route?.let { r -> BBox.of(r.points)?.buffer(offRouteM) } ?: b }
        val found = LinkedHashMap<String, Place>()
        for (r in source.byTag("natural", "spring", box, 60)) found[r.place.key] = r.place
        for (r in source.byTag("amenity", "drinking_water", box, 80)) found[r.place.key] = r.place
        for (r in source.byTag("man_made", "water_tap", box, 60)) found[r.place.key] = r.place
        for (r in source.byTag("man_made", "water_well", box, 60)) found[r.place.key] = r.place
        for (r in source.byTag("amenity", "water_point", box, 60)) found[r.place.key] = r.place
        for (r in source.byTag("amenity", "fountain", box, 60)) if (r.tags["drinking_water"] == "yes") found[r.place.key] = r.place
        val out = ArrayList<WaterSource>()
        for (p in found.values) {
            val d = Geo.distance(centre, p.location)
            val snap = route?.line?.snap(p.location)
            if (route != null) { if (snap == null || snap.offset > offRouteM) continue } else if (d > radiusM) continue
            out += WaterSource(p, kind(p), p.name() ?: "", status(p), d, snap?.along)
        }
        return if (route != null) out.sortedBy { it.alongM } else out.sortedBy { it.distanceM }
    }

    fun kind(p: Place): WaterSource.Kind = when {
        p.tags["natural"] == "spring" -> WaterSource.Kind.SPRING
        p.tags["man_made"] == "water_tap" || p.tags["amenity"] == "water_point" -> WaterSource.Kind.TAP
        p.tags["man_made"] == "water_well" -> WaterSource.Kind.WELL
        p.tags["amenity"] == "fountain" -> WaterSource.Kind.FOUNTAIN
        else -> WaterSource.Kind.POINT
    }

    fun status(p: Place): WaterSource.Status = when {
        p.tags["drinking_water"] == "no" -> WaterSource.Status.NOT_DRINKING
        p.tags["seasonal"] == "yes" || p.tags["seasonal"] == "summer" || p.tags["intermittent"] == "yes" -> WaterSource.Status.SEASONAL
        p.tags["drinking_water"] == "yes" || p.tags["amenity"] == "drinking_water" || p.tags["man_made"] == "water_tap" -> WaterSource.Status.POTABLE
        else -> WaterSource.Status.UNSAID
    }

    /** True for what a thirsty walker can use: drinking water, springs and wells with nothing said against them, not seasonal. */
    fun usable(s: WaterSource): Boolean = s.status == WaterSource.Status.POTABLE || s.status == WaterSource.Status.UNSAID

    /** Litres an hour walking at [tempC]: 0.4 at 15 °C, 0.02 more for each degree above, never under 0.25 or over 1.2. */
    fun rate(tempC: Double): Double = (0.4 + 0.02 * (tempC - 15.0)).coerceIn(0.25, 1.2)

    /** What [litresLeft] gives at [tempC]: the minutes it lasts and the kilometres walked in them. */
    fun supply(litresLeft: Double, tempC: Double): Supply {
        val r = rate(tempC)
        val minutes = litresLeft / r * 60.0
        return Supply(litresLeft, r, minutes, minutes / 60.0 * WALKING_KMH)
    }

    /** Minutes to walk [metres] at hiking pace. */
    fun walkMinutes(metres: Double): Double = metres / 1000.0 / WALKING_KMH * 60.0

    enum class Verdict { FINE, TIGHT, LOW, NO_SOURCE }

    /** Whether the water is enough to reach the nearest usable source: fine with a fifth to spare, tight when just enough, low when not. */
    fun verdict(supply: Supply, next: WaterSource?): Verdict {
        next ?: return Verdict.NO_SOURCE
        val km = (next.alongM ?: next.distanceM) / 1000.0
        return when {
            km <= supply.rangeKm * 0.8 -> Verdict.FINE
            km <= supply.rangeKm -> Verdict.TIGHT
            else -> Verdict.LOW
        }
    }

    /** The kilometre mark of a source: along the route when there is one, else the distance from you. */
    fun km(s: WaterSource): Double = (s.alongM ?: s.distanceM) / 1000.0

    /**
     * The sources as they lie ahead of you: with a [trip] their marks count from the [travelled] metres of the route already
     * behind, and a source more than 50 m behind is left out. Without one the list is as it is (distances from you).
     */
    fun ahead(all: List<WaterSource>, trip: Boolean, travelled: Double): List<WaterSource> =
        if (!trip) all else all.mapNotNull { s -> s.alongM?.let { a -> if (a - travelled < -BEHIND_M) null else s.copy(alongM = (a - travelled).coerceAtLeast(0.0)) } }

    /** The bottle's fill, 0..1. */
    fun fill(litresLeft: Double, bottleLitres: Double): Float = if (bottleLitres <= 0) 0f else min(1f, max(0f, (litresLeft / bottleLitres).toFloat()))
}
