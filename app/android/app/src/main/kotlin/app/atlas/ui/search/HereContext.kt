package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.places.PlaceCountry
import app.atlas.render.data.Countries
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a place keeps its time: the country whose holidays it keeps (null when unknown) and its clock's zone. */
data class PlaceLocale(val country: String?, val zone: ZoneId) {
    /** The moment [clock] reads on the place's own clock. */
    fun now(clock: Clock): LocalDateTime = LocalDateTime.now(clock.withZone(zone))

    /** Opening hours as they are read at [at] in this country and on this clock: its holidays and the sun there. */
    fun hours(at: LatLon): OpeningHoursContext = OpeningHoursContext.forPlace(country, zone, at.lat, at.lon)
}

/**
 * Where "here" is, as opening hours and brands need it: the country (its public holidays, the chains that trade there)
 * and the clock. One way for every screen (the reviewer, 17 November, finding 8): a place's own `addr:country`, else the
 * commonest `addr:country` of the places around in the downloaded regions, else the map's own country at that point
 * ([Countries]), never the phone's language: an en-US phone in Amsterdam keeps King's Day, not Thanksgiving.
 */
object HereContext {
    private const val LOOK_M = 2_000.0
    private const val SAMPLE = 400

    /** Within this far of the phone a place keeps the phone's clock (the same zone, in practice). */
    internal const val NEAR_PHONE_M = 300_000.0

    private val countries = ConcurrentHashMap<String, String>()

    /** Where the phone is, as the screens that need "here" take it: its fix, else where the map started, else the Westerkerk. */
    fun phoneAt(services: Services): LatLon =
        services.location.last.value?.location ?: services.startCameraNow()?.target ?: Services.WESTERKERK

    /** The ISO country at [at]: the downloaded regions', else the map's own; null only far from every country it knows. */
    fun country(services: Services, at: LatLon): String? = regionCountry(services, at) ?: Countries.of(at)

    /**
     * [country] as far as it is known without asking the regions, for a frame that cannot wait: what they said at [at]
     * before, else the map's own there.
     */
    fun countryNow(services: Services, at: LatLon): String? {
        val store = services.placeStores
        val said = if (store.size == 0) null else countries[key(store.size, at)]?.ifEmpty { null }
        return said ?: Countries.of(at)
    }

    /** Opening hours as they are read at [at]: its country's holidays and its sunrise and sunset. */
    fun hours(services: Services, at: LatLon, zone: ZoneId): OpeningHoursContext =
        OpeningHoursContext.forPlace(country(services, at), zone, at.lat, at.lon)

    /**
     * The country and clock of [place], seen from a phone at [phone] whose own zone is [phoneZone]: its country its own
     * `addr:country`, else [country] at its point ([PlaceCountry]); its clock the phone's near the phone, else its
     * country's (the zone nearest the sun's hour there), else the sun's own hour. Asks the regions: off the main thread.
     */
    fun localeOf(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        locale(PlaceCountry.of(place.tags, country(services, place.location)), place.location, phone, phoneZone)

    /** The same for a point and the places around it, as an area's screens read them all ([localeOf]). */
    fun localeAt(services: Services, at: LatLon, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        locale(country(services, at), at, phone, phoneZone)

    private fun locale(country: String?, at: LatLon, phone: LatLon, phoneZone: ZoneId): PlaceLocale {
        val near = Geo.distance(phone, at) <= NEAR_PHONE_M
        return PlaceLocale(country, if (near) phoneZone else PlaceZone.guess(country, at.lon))
    }

    /** The country the regions on the phone say, kept per kilometre and per count of regions (a download changes it). */
    private fun regionCountry(services: Services, at: LatLon): String? {
        val store = services.placeStores
        if (store.size == 0) return null
        val key = key(store.size, at)
        countries[key]?.let { return it.ifEmpty { null } }
        val counts = HashMap<String, Int>()
        runCatching { store.inBox(BBox.around(at, LOOK_M), SAMPLE) }.getOrNull()?.forEach { r ->
            PlaceCountry.tagged(r.tags)?.let { counts.merge(it, 1, Int::plus) }
        }
        val found = counts.maxByOrNull { it.value }?.key
        if (countries.size > 256) countries.clear()
        countries[key] = found.orEmpty()
        return found
    }

    private fun key(regions: Int, at: LatLon) = "$regions:${(at.lat * 100).roundToInt()}:${(at.lon * 100).roundToInt()}"
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
