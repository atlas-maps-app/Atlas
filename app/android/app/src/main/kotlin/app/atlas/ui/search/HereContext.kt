package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.MapEngine
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.places.PlaceCountry
import app.atlas.render.data.Countries
import app.atlas.search.PlaceRecord
import app.atlas.services.PackCountries
import app.atlas.tiles.WindowTiles
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

    /** The moment a phone's clock on [phoneZone] reads as [phoneNow], on the place's own clock. */
    fun fromPhone(phoneNow: LocalDateTime, phoneZone: ZoneId): LocalDateTime = phoneNow.atZone(phoneZone).withZoneSameInstant(zone).toLocalDateTime()
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
 * commonest `addr:country` of the places around in the downloaded regions, else the country of the pack that holds the
 * point (the region's whose store has places around it, else the published country's whose tiles serve the windows
 * there; batch 4 A5: the boxes put Maastricht in Belgium), else the map's own country at that point ([Countries]), never
 * the phone's language: an en-US phone in Amsterdam keeps King's Day, not Thanksgiving. Where a
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

    /** The ISO country at [at]: the downloaded regions', else the pack's that holds it, else the map's own; null only far from every country it knows. */
    fun country(services: Services, at: LatLon): String? = region(services, at).country

    /**
     * The country at [at] ([country]) and its state or nation there: the one most of the places around name
     * ([PlaceCountry.subdivision]: their `ISO3166-2`, a German `addr:state`, a French or British postcode), when it is
     * of that country; none when they name none. Asks the regions: off the main thread.
     */
    fun region(services: Services, at: LatLon): HereRegion = settled(services, regionsSay(services, at), at, services.packCountries::of)

    /**
     * [region] as far as it is known without asking the regions, for a frame that cannot wait: what they said at [at]
     * before (by the count of places they held when last asked, so no store is touched: the first touch opens every
     * region's file; the app maintainer, 18 November 2026, batch 5 A5), else the country of the tiles there as the list
     * read so far names it, else the map's own country there.
     */
    fun regionNow(services: Services, at: LatLon): HereRegion {
        val counted = services.regionsCounted
        return settled(services, if (counted <= 0) null else said[key(counted, at)], at, services.packCountries::now)
    }

    /** [country] as far as it is known without asking the regions ([regionNow]). */
    fun countryNow(services: Services, at: LatLon): String? = regionNow(services, at).country

    /** Opening hours as they are read at [at]: its country's holidays, its state's or nation's, and its sunrise and sunset. */
    fun hours(services: Services, at: LatLon, zone: ZoneId): OpeningHoursContext = region(services, at).hours(at, zone)

    /**
     * The country and clock of [place], seen from a phone at [phone] whose own zone is [phoneZone]: its country its own
     * `addr:country`, else [country] at its point ([PlaceCountry]); its clock the phone's near the phone in the phone's
     * country, else its country's ([PlaceZone]), else the sun's own hour. Asks the regions: off the main thread.
     */
    fun localeOf(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        localeAmong(region(services, place.location), place, phone, phoneZone) { HereContext.country(services, phone) }

    /** [localeOf] as far as it is known without asking the regions ([regionNow]), for a frame that cannot wait. */
    fun localeNow(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        localeAmong(regionNow(services, place.location), place, phone, phoneZone) { countryNow(services, phone) }

    /** [place]'s country and state or nation, its own first, else of [around], and its clock ([locale]). */
    private fun localeAmong(around: HereRegion, place: Place, phone: LatLon, phoneZone: ZoneId, phoneCountry: () -> String?): PlaceLocale {
        val country = PlaceCountry.of(place.tags, around.country)
        val subdivision = PlaceCountry.subdivision(place.tags, country) ?: around.subdivision?.takeIf { country != null && it.startsWith("$country-") }
        return locale(HereRegion(country, subdivision), place.location, phone, phoneZone, phoneCountry)
    }

    /** The same for a point and the places around it, as an area's screens read them all ([localeOf]). */
    fun localeAt(services: Services, at: LatLon, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        locale(region(services, at), at, phone, phoneZone) { HereContext.country(services, phone) }

    /** [region]'s clock at [at] from a phone at [phone] on [phoneZone], in [phoneCountry], asked only within [NEAR_PHONE_M]. */
    private fun locale(region: HereRegion, at: LatLon, phone: LatLon, phoneZone: ZoneId, phoneCountry: () -> String?): PlaceLocale {
        // the phone's clock near it only in its own country, or where the place's is unknown: a border between may be a
        // clock between (the app maintainer, 18 November 2026, batch 4 B15: Porto from Vigo read Spain's)
        val near = Geo.distance(phone, at) <= NEAR_PHONE_M && (region.country == null || region.country == phoneCountry())
        return PlaceLocale(region.country, if (near) phoneZone else PlaceZone.guess(region.country, at), region.subdivision)
    }

    /**
     * What the regions said ([said]) made whole: their country, else the tiles' there ([tilesCountry], each pack's country
     * by [pack]), else the map's own; their state or nation if of it.
     */
    private fun settled(services: Services, said: HereRegion?, at: LatLon, pack: (String) -> String?): HereRegion {
        val country = said?.country ?: tilesCountry(services, at, pack) ?: Countries.of(at)
        return HereRegion(country, said?.subdivision?.takeIf { country != null && it.startsWith("$country-") })
    }

    /**
     * The country whose published tiles serve the windows at [at], as [pack] names a pack's: of the map's countries whose
     * box holds the point, the one whose tiles' index lists it the most finely ([PackCountries.Tiles]: its window before
     * a frame cell's level tile, which reaches past a border: Maastricht's L12 holds Lanaken), the boxes saying where two
     * list it as finely (the app maintainer, 18 November 2026, batch 5 A3), and where a country whose box holds the point
     * has no index in memory, which may list it more finely than another's level tile does; never by a box alone (the
     * Netherlands' holds Antwerp). Null where no index in memory lists it. Reads memory alone: no index is read or
     * fetched for the asking (the camera has them read), no store is asked, and it never blocks.
     */
    private fun tilesCountry(services: Services, at: LatLon, pack: (String) -> String?): String? {
        val tiles = services.packCountries.tiles ?: return null
        var finest = 0
        var unread = false
        val holding = ArrayList<String>(2)
        for (c in MapEngine.countries) {
            if (!c.holds(at.lat, at.lon)) continue
            val level = tiles.level(c.id, at.lat, at.lon)
            if (level == null) {
                unread = true
                continue
            }
            if (level <= 0 || level < finest) continue
            if (level > finest) {
                finest = level
                holding.clear()
            }
            holding += c.id
        }
        if (finest == 0 || (unread && finest < WindowTiles.LEVEL)) return null
        return holding.map(pack).distinct().singleOrNull()
    }

    /**
     * What the regions on the phone say at [at]: the `addr:country` most of the places around name, else the country of
     * the region whose store holds the point ([app.atlas.services.PackCountries.ofRegionAt], its pack's), and the state or
     * nation most name, each read in its place's country; null with no region downloaded. Kept per kilometre and per
     * count of regions (a download changes it).
     */
    private fun regionsSay(services: Services, at: LatLon): HereRegion? {
        val store = services.placeStores
        val size = store.size
        services.regionsCounted = size
        if (size == 0) return null
        val key = key(size, at)
        said[key]?.let { return it }
        val sample = runCatching { store.inBox(BBox.around(at, LOOK_M), SAMPLE) }.getOrNull().orEmpty()
        val country = commonest(sample) { PlaceCountry.tagged(it.tags) } ?: services.packCountries.ofRegionAt(store.stores, at)
        val around = country ?: tilesCountry(services, at, services.packCountries::of) ?: Countries.of(at)
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

/** Which clock a far-away place keeps, worked out without a network: its country's, else the sun's hour. */
object PlaceZone {
    /**
     * The clock at [p] in [country]: the country's own ([Countries.timezone], by longitude where it spans several), the
     * Canaries' and the Azores' their own (the app maintainer, 18 November 2026, batch 4 B15: the zone nearest the sun's
     * hour put Madrid on the Canaries' clock, Porto on the Azores' and Chengdu on Urumqi's); for a country it does not
     * name, the country's zone nearest the sun's hour there; the sun's whole hour itself where the country is unknown.
     */
    fun guess(country: String?, p: LatLon, at: Instant = Instant.now()): ZoneId {
        islands(country, p)?.let { return ZoneId.of(it) }
        Countries.timezone(country, p).takeIf { it != "UTC" }?.let { id -> runCatching { ZoneId.of(id) }.getOrNull()?.let { return it } }
        val sun = p.lon / 15.0
        val best = zonesOf(country).minByOrNull { id -> abs(ZoneId.of(id).rules.getStandardOffset(at).totalSeconds / 3600.0 - sun) }
        return best?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.ofHours(sun.roundToInt().coerceIn(-12, 14))
    }

    /** The islands far off their country's own clock: the Canaries west of 12° W, the Azores west of 20° W. */
    private fun islands(country: String?, p: LatLon): String? = when {
        country == "ES" && p.lon < -12.0 -> "Atlantic/Canary"
        country == "PT" && p.lon < -20.0 -> "Atlantic/Azores"
        else -> null
    }

    /** The zone ids of [country] (ISO 3166 alpha-2), from the system's ICU data; none when unknown. */
    private fun zonesOf(country: String?): List<String> {
        if (country == null) return emptyList()
        return runCatching { android.icu.util.TimeZone.getAvailableIDs(country).filter { runCatching { ZoneId.of(it) }.isSuccess } }.getOrNull().orEmpty()
    }
}
