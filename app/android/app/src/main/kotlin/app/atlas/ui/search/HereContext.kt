package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a place keeps its time: the country whose holidays it keeps (null when unknown) and its clock's zone. */
data class PlaceLocale(val country: String?, val zone: ZoneId) {
    /** The moment [clock] reads on the place's own clock. */
    fun now(clock: Clock): LocalDateTime = LocalDateTime.now(clock.withZone(zone))
}

/**
 * Where "here" is, as opening hours need it: the country (for its public holidays) and the sun. The country is the
 * commonest `addr:country` of the places around in the downloaded regions, else the phone's own country.
 */
object HereContext {
    private const val LOOK_M = 2_000.0
    private const val SAMPLE = 400

    /** Within this far of the phone a place keeps the phone's clock and country (the same zone, in practice). */
    internal const val NEAR_PHONE_M = 300_000.0

    private val countries = ConcurrentHashMap<String, String>()

    /** Where the phone is, as the screens that need "here" take it: its fix, else where the map started, else the Westerkerk. */
    fun phoneAt(services: Services): LatLon =
        services.location.last.value?.location ?: services.startCameraNow()?.target ?: Services.WESTERKERK

    /** The ISO country at [at], or null when neither the regions nor the phone say. */
    fun country(services: Services, at: LatLon): String? = regionCountry(services, at) ?: phoneCountry()

    /** Opening hours as they are read at [at]: its country's holidays and its sunrise and sunset. */
    fun hours(services: Services, at: LatLon, zone: ZoneId): OpeningHoursContext =
        OpeningHoursContext.forPlace(country(services, at), zone, at.lat, at.lon)

    /**
     * The country and clock of [place], seen from a phone at [phone] whose own zone is [phoneZone]. Near the phone both
     * are the phone's; far from it the phone's say nothing, so the place's own `addr:country` or the downloaded regions
     * speak, and the zone is the country's (its zone nearest the sun's hour there), else the sun's own hour.
     */
    fun localeOf(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale {
        val near = Geo.distance(phone, place.location) <= NEAR_PHONE_M
        val tagged = place.tags["addr:country"]?.trim()?.uppercase()?.takeIf { it.length == 2 }
        val country = tagged ?: regionCountry(services, place.location) ?: if (near) phoneCountry() else null
        val zone = if (near) phoneZone else PlaceZone.guess(country, place.location.lon)
        return PlaceLocale(country, zone)
    }

    private fun phoneCountry(): String? = Locale.getDefault().country.takeIf { it.length == 2 }

    /** The country the regions on the phone say, kept per kilometre and per count of regions (a download changes it). */
    private fun regionCountry(services: Services, at: LatLon): String? {
        val store = services.placeStores
        if (store.size == 0) return null
        val key = "${store.size}:${(at.lat * 100).roundToInt()}:${(at.lon * 100).roundToInt()}"
        countries[key]?.let { return it.ifEmpty { null } }
        val counts = HashMap<String, Int>()
        runCatching { store.inBox(BBox.around(at, LOOK_M), SAMPLE) }.getOrNull()?.forEach { r ->
            r.tags["addr:country"]?.trim()?.uppercase()?.takeIf { it.length == 2 }?.let { counts.merge(it, 1, Int::plus) }
        }
        val found = counts.maxByOrNull { it.value }?.key
        if (countries.size > 256) countries.clear()
        countries[key] = found.orEmpty()
        return found
    }
}

/** Which clock a far-away place keeps, worked out without a network: the country's zones, else the sun's hour. */
object PlaceZone {
    /** The zone of [country] nearest to the sun's hour at [lon]; the sun's whole hour itself when the country is unknown. */
    fun guess(country: String?, lon: Double, at: Instant = Instant.now()): ZoneId {
        val sun = lon / 15.0
        val best = zonesOf(country).minByOrNull { id -> abs(ZoneId.of(id).rules.getStandardOffset(at).totalSeconds / 3600.0 - sun) }
        return best?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.ofHours(sun.roundToInt().coerceIn(-12, 14))
    }

    /** The zone ids of [country] (ISO 3166 alpha-2), from the system's ICU data; none when unknown. */
    private fun zonesOf(country: String?): List<String> {
        if (country == null) return emptyList()
        return runCatching { android.icu.util.TimeZone.getAvailableIDs(country).filter { runCatching { ZoneId.of(it) }.isSuccess } }.getOrNull().orEmpty()
    }
}
