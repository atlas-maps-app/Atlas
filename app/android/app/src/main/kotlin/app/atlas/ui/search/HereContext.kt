package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.places.PlaceCountry
import app.atlas.render.data.Countries
import app.atlas.search.PlaceRecord
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a place keeps its time: the country whose holidays it keeps (null when unknown), its state or nation where they
 * differ by it ([HereRegion.subdivision]) and its clock's zone.
 */
data class PlaceLocale(val country: String?, val zone: ZoneId, val subdivision: String? = null) {
    /** The moment [clock] reads on the place's own clock. */
    fun now(clock: Clock): LocalDateTime = LocalDateTime.now(clock.withZone(zone))

    /** Opening hours as they are read at [at] in this country and on this clock: its holidays and the sun there. */
    fun hours(at: LatLon): OpeningHoursContext = OpeningHoursContext.forPlace(country, zone, at.lat, at.lon, subdivision)
}

/**
 * What a point's hours and brands are read in: its country (ISO 3166 alpha-2) and, where that country's holidays differ
 * within it, its state or nation (ISO 3166-2: "DE-BY", "GB-SCT"; the reviewer, 17 November, finding 10); either null
 * where nothing says.
 */
data class HereRegion(val country: String?, val subdivision: String? = null) {
    /** Opening hours as they are read at [at] in this region, on the clock of [zone]: its holidays and the sun there. */
    fun hours(at: LatLon, zone: ZoneId): OpeningHoursContext = OpeningHoursContext.forPlace(country, zone, at.lat, at.lon, subdivision)
}

/**
 * Where "here" is, as opening hours and brands need it: the country (its public holidays, the chains that trade there)
 * and the clock. One way for every screen (the reviewer, 17 November, finding 8): a place's own `addr:country`, else the
 * commonest `addr:country` of the places around in the downloaded regions, else the map's own country at that point
 * ([Countries]), never the phone's language: an en-US phone in Amsterdam keeps King's Day, not Thanksgiving. Where a
 * country's holidays differ within it, the state or nation the place's own tags or most of the places around name
 * ([region]; finding 10): Epiphany in Bavaria, 2 January in Scotland, St Patrick's Day in Northern Ireland.
 */
object HereContext {
    private const val LOOK_M = 2_000.0
    private const val SAMPLE = 400

    /** Within this far of the phone a place keeps the phone's clock (the same zone, in practice). */
    internal const val NEAR_PHONE_M = 300_000.0

    /** What the regions said, per point ([key]): the country and the state or nation most of the places there name. */
    private val said = ConcurrentHashMap<String, HereRegion>()

    /** Where the phone is, as the screens that need "here" take it: its fix, else where the map started, else the Westerkerk. */
    fun phoneAt(services: Services): LatLon =
        services.location.last.value?.location ?: services.startCameraNow()?.target ?: Services.WESTERKERK

    /** The ISO country at [at]: the downloaded regions', else the map's own; null only far from every country it knows. */
    fun country(services: Services, at: LatLon): String? = region(services, at).country

    /**
     * The country at [at] ([country]) and its state or nation there: the one most of the places around name
     * ([PlaceCountry.subdivision]: their `ISO3166-2`, a German `addr:state`, a French or British postcode), when it is
     * of that country; none when they name none. Asks the regions: off the main thread.
     */
    fun region(services: Services, at: LatLon): HereRegion = settled(regionsSay(services, at), at)

    /**
     * [region] as far as it is known without asking the regions, for a frame that cannot wait: what they said at [at]
     * before, else the map's own country there.
     */
    fun regionNow(services: Services, at: LatLon): HereRegion {
        val store = services.placeStores
        return settled(if (store.size == 0) null else said[key(store.size, at)], at)
    }

    /** [country] as far as it is known without asking the regions ([regionNow]). */
    fun countryNow(services: Services, at: LatLon): String? = regionNow(services, at).country

    /** Opening hours as they are read at [at]: its country's holidays, its state's or nation's, and its sunrise and sunset. */
    fun hours(services: Services, at: LatLon, zone: ZoneId): OpeningHoursContext = region(services, at).hours(at, zone)

    /**
     * The country and clock of [place], seen from a phone at [phone] whose own zone is [phoneZone]: its country its own
     * `addr:country`, else [country] at its point ([PlaceCountry]); its clock the phone's near the phone, else its
     * country's (the zone nearest the sun's hour there), else the sun's own hour. Asks the regions: off the main thread.
     */
    fun localeOf(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale {
        val around = region(services, place.location)
        val country = PlaceCountry.of(place.tags, around.country)
        val subdivision = PlaceCountry.subdivision(place.tags, country) ?: around.subdivision?.takeIf { country != null && it.startsWith("$country-") }
        return locale(HereRegion(country, subdivision), place.location, phone, phoneZone)
    }

    /** The same for a point and the places around it, as an area's screens read them all ([localeOf]). */
    fun localeAt(services: Services, at: LatLon, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        locale(region(services, at), at, phone, phoneZone)

    private fun locale(region: HereRegion, at: LatLon, phone: LatLon, phoneZone: ZoneId): PlaceLocale {
        val near = Geo.distance(phone, at) <= NEAR_PHONE_M
        return PlaceLocale(region.country, if (near) phoneZone else PlaceZone.guess(region.country, at.lon), region.subdivision)
    }

    /** What the regions said ([said]) made whole: their country, else the map's own; their state or nation if of it. */
    private fun settled(said: HereRegion?, at: LatLon): HereRegion {
        val country = said?.country ?: Countries.of(at)
        return HereRegion(country, said?.subdivision?.takeIf { country != null && it.startsWith("$country-") })
    }

    /**
     * What the regions on the phone say at [at]: the `addr:country` most of the places around name, and the state or
     * nation most name, each read in its place's country; null with no region downloaded. Kept per kilometre and per
     * count of regions (a download changes it).
     */
    private fun regionsSay(services: Services, at: LatLon): HereRegion? {
        val store = services.placeStores
        if (store.size == 0) return null
        val key = key(store.size, at)
        said[key]?.let { return it }
        val sample = runCatching { store.inBox(BBox.around(at, LOOK_M), SAMPLE) }.getOrNull().orEmpty()
        val country = commonest(sample) { PlaceCountry.tagged(it.tags) }
        val around = country ?: Countries.of(at)
        val found = HereRegion(country, commonest(sample) { PlaceCountry.subdivision(it.tags, PlaceCountry.of(it.tags, around)) })
        if (said.size > 256) said.clear()
        said[key] = found
        return found
    }

    private inline fun commonest(records: List<PlaceRecord>, of: (PlaceRecord) -> String?): String? {
        val counts = HashMap<String, Int>()
        for (r in records) of(r)?.let { counts.merge(it, 1, Int::plus) }
        return counts.maxByOrNull { it.value }?.key
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
