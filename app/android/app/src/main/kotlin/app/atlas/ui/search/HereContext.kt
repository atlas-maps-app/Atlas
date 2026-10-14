package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.places.OpeningHoursContext
import java.time.ZoneId
import java.util.Locale

/**
 * Where "here" is, as opening hours need it: the country (for its public holidays) and the sun. The country is the
 * commonest `addr:country` of the places around in the downloaded regions, else the phone's own country.
 */
object HereContext {
    private const val LOOK_M = 2_000.0
    private const val SAMPLE = 400

    /** The ISO country at [at], or null when neither the regions nor the phone say. */
    fun country(services: Services, at: LatLon): String? =
        fromRegions(services, at) ?: Locale.getDefault().country.takeIf { it.length == 2 }

    /** Opening hours as they are read at [at]: its country's holidays and its sunrise and sunset. */
    fun hours(services: Services, at: LatLon, zone: ZoneId): OpeningHoursContext =
        OpeningHoursContext.forPlace(country(services, at), zone, at.lat, at.lon)

    private fun fromRegions(services: Services, at: LatLon): String? {
        val store = services.placeStores
        if (store.size == 0) return null
        val counts = HashMap<String, Int>()
        runCatching { store.inBox(BBox.around(at, LOOK_M), SAMPLE) }.getOrNull()?.forEach { r ->
            r.tags["addr:country"]?.trim()?.uppercase()?.takeIf { it.length == 2 }?.let { counts.merge(it, 1, Int::plus) }
        }
        return counts.maxByOrNull { it.value }?.key
    }
}
