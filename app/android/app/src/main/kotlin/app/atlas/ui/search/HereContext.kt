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
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
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
 * there; batch 4 A5: the boxes put Maastricht in Belgium), else the phone's own country where its box holds the point
 * and no index in memory says it is not its (the app maintainer, 19 November 2026, batch 6 A2: the boxes put Munich in
 * Austria), else the map's own country at that point ([Countries]): an en-US phone in Amsterdam keeps King's Day, not
 * Thanksgiving, as no box of the United States holds it. Where a country's holidays differ within it, the state or
 * nation the place's own tags or most of the places around name ([region]; finding 10): Epiphany in Bavaria,
 * 2 January in Scotland, St Patrick's Day in Northern Ireland.
 */
object HereContext {
    private const val LOOK_M = 2_000.0
    private const val SAMPLE = 400

    /**
     * How many points' answers are kept ([said]): a list of saved places across a country is asked once, not at each
     * opening (the app maintainer, 18 November 2026, batch 5 B12: past 256 they were all let go, so a list of 500
     * cafés across the Netherlands asked every one of its kilometres again each time it opened).
     */
    private const val KEPT = 4_096

    /**
     * Within this far of the phone a place in the phone's country keeps the phone's clock (the same zone, in practice),
     * and one over a border a zone of its own country like the phone's where its country keeps several
     * ([PlaceZone.besidePhone]).
     */
    internal const val NEAR_PHONE_M = 300_000.0

    /** What the regions said, per point ([key]): the country and the state or nation most of the places there name. */
    private val said = ConcurrentHashMap<String, HereRegion>()

    /** The points being asked now, each with the turn those asking it take: the rows of one kilometre ask once. */
    private val asking = ConcurrentHashMap<String, Any>()

    /**
     * Whether [at]'s kilometre is being asked now, with the regions as they are: its turn is let go once it is
     * answered. For a test (the app maintainer, 19 November 2026, batch 6 B9: kept for ever, it passed every test).
     */
    internal fun beingAsked(services: Services, at: LatLon): Boolean =
        asking.containsKey(key(services.placeStores.size, services.packCountries.generation, at))

    /** Where the phone is, as the screens that need "here" take it: its fix, else where the map started, else the Westerkerk. */
    fun phoneAt(services: Services): LatLon =
        services.location.last.value?.location ?: services.startCameraNow()?.target ?: Services.WESTERKERK

    /**
     * The ISO country at [at]: the downloaded regions', else the pack's that holds it, else the phone's where its box
     * holds it, else the map's own; null only far from every country it knows.
     */
    fun country(services: Services, at: LatLon): String? = region(services, at).country

    /**
     * The country of the downloaded region whose store [r] is a place of, by its pack ([PackCountries.ofStore], each
     * region's file read once, and again once written since): a region answers for every point its store covers, and a
     * place of its own is one, so a typed search's hits across the country ask no 2 km query, region file or covers
     * query for each kilometre cell (the app maintainer, 18 November 2026, batch 5 A6). Null where [r] is no region's
     * place, where two regions hold it (on their border: [country] weighs them there), or where its region names no
     * country; the point's country is asked then. Reads the stores: off the main thread.
     */
    fun regionOf(services: Services, r: PlaceRecord): String? {
        val id = r.id ?: return null
        val holding = services.placeStores.stores.filter { s -> runCatching { s.get(id) }.getOrNull() != null }
        return holding.singleOrNull()?.let(services.packCountries::ofStore)
    }

    /**
     * The country at [at] ([country]) and its state or nation there: the one most of the places around name
     * ([PlaceCountry.subdivision]: their `ISO3166-2`, a German `addr:state`, a French or British postcode), when it is
     * of that country; none when they name none. Asks the regions: off the main thread.
     */
    fun region(services: Services, at: LatLon): HereRegion = settled(services, regionsSay(services, at), at, services.packCountries::of)

    /**
     * [region] as far as it is known without asking the regions, for a frame that cannot wait: what they said at [at]
     * before (by the count of places they held when last asked and the packs as last read, [PackCountries.generation],
     * so no store or file is touched: the first touch opens every region's file; the app maintainer, 18 November 2026,
     * batch 5 A5), else the country of the tiles there as the list read so far names it, else the phone's where its
     * box holds it ([beyondRegions]), else the map's own country there.
     */
    fun regionNow(services: Services, at: LatLon): HereRegion {
        val counted = services.regionsCounted
        val kept = if (counted <= 0) null else said[key(counted, services.packCountries.generation, at)]
        return settled(services, kept, at, services.packCountries::now)
    }

    /** [country] as far as it is known without asking the regions ([regionNow]). */
    fun countryNow(services: Services, at: LatLon): String? = regionNow(services, at).country

    /** Opening hours as they are read at [at]: its country's holidays, its state's or nation's, and its sunrise and sunset. */
    fun hours(services: Services, at: LatLon, zone: ZoneId): OpeningHoursContext = region(services, at).hours(at, zone)

    /**
     * The country and clock of [place], seen from a phone at [phone] whose own zone is [phoneZone]: its country its own
     * `addr:country`, else [country] at its point ([PlaceCountry]); its clock the phone's near the phone in the phone's
     * country, near it over a border one of its country's like the phone's where its country keeps several
     * ([PlaceZone.besidePhone]), else its country's ([PlaceZone]), else the sun's own hour. Asks the regions: off the
     * main thread.
     */
    fun localeOf(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        localeAmong(region(services, place.location), place, phone, phoneZone, services.clock.instant()) { HereContext.country(services, phone) }

    /** [localeOf] as far as it is known without asking the regions ([regionNow]), for a frame that cannot wait. */
    fun localeNow(services: Services, place: Place, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        localeAmong(regionNow(services, place.location), place, phone, phoneZone, services.clock.instant()) { countryNow(services, phone) }

    /** [place]'s country and state or nation, its own first, else of [around], and its clock at [now] ([locale]). */
    private fun localeAmong(around: HereRegion, place: Place, phone: LatLon, phoneZone: ZoneId, now: Instant, phoneCountry: () -> String?): PlaceLocale {
        val country = PlaceCountry.of(place.tags, around.country)
        val subdivision = PlaceCountry.subdivision(place.tags, country) ?: around.subdivision?.takeIf { country != null && it.startsWith("$country-") }
        return locale(HereRegion(country, subdivision), place.location, phone, phoneZone, now, phoneCountry)
    }

    /** The same for a point and the places around it, as an area's screens read them all ([localeOf]). */
    fun localeAt(services: Services, at: LatLon, phone: LatLon, phoneZone: ZoneId): PlaceLocale =
        locale(region(services, at), at, phone, phoneZone, services.clock.instant()) { HereContext.country(services, phone) }

    /**
     * [region]'s clock at [at] from a phone at [phone] on [phoneZone], in [phoneCountry], asked only within
     * [NEAR_PHONE_M]; [now] the app's moment.
     */
    private fun locale(region: HereRegion, at: LatLon, phone: LatLon, phoneZone: ZoneId, now: Instant, phoneCountry: () -> String?): PlaceLocale {
        val country = region.country
        val zone = when {
            Geo.distance(phone, at) > NEAR_PHONE_M -> PlaceZone.guess(country, at)
            // the phone's clock near it in its own country, or where the place's is unknown: a border between may be a
            // clock between (the app maintainer, 18 November 2026, batch 4 B15: Porto from Vigo read Spain's)
            country == null || country == phoneCountry() -> phoneZone
            // over a border near it, in a country of several clocks: one of its zones like the phone's, else the guess
            // (19 November 2026, batch 6 A6: Windsor from Detroit read Edmonton's clock, Toronto from Buffalo
            // Atikokan's)
            else -> PlaceZone.besidePhone(country, at, phoneZone, now) ?: PlaceZone.guess(country, at)
        }
        return PlaceLocale(country, zone, region.subdivision)
    }

    /**
     * What the regions said ([said]) made whole: their country, else the one where they name none ([beyondRegions],
     * each pack's country by [pack]); their state or nation if of it.
     */
    private fun settled(services: Services, said: HereRegion?, at: LatLon, pack: (String) -> String?): HereRegion {
        val country = said?.country ?: beyondRegions(services, at, pack)
        return HereRegion(country, said?.subdivision?.takeIf { country != null && it.startsWith("$country-") })
    }

    /**
     * The country at [at] where the regions name none: the tiles' there ([tilesCountry], each pack's country by
     * [pack]), else the phone's ([phonesCountry]), else the map's own, the smallest box that holds it ([Countries.of]).
     * One rule for the point and for the places around it ([settled], [regionsSay]). Reads memory alone.
     */
    private fun beyondRegions(services: Services, at: LatLon, pack: (String) -> String?): String? =
        tilesCountry(services, at, pack) ?: phonesCountry(services, at, pack) ?: Countries.of(at)

    /**
     * The phone's own country (its locale's, as 38ddafa1 read it) where a box of it holds [at] ([Countries.holds]) and
     * no index in memory says the point is not its: a published country of it, its tiles' index in memory, listing
     * nothing there (each pack's country by [pack]; the app maintainer, 19 November 2026, batch 6 A2: the smallest box
     * put Maastricht, Eindhoven and Terneuzen in Belgium till the camera had read the Netherlands' index, Munich in
     * Austria and Arlon in Luxembourg). Null on a phone of no country. Reads memory alone.
     */
    private fun phonesCountry(services: Services, at: LatLon, pack: (String) -> String?): String? {
        val phone = Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: return null
        if (!Countries.holds(phone, at)) return null
        val tiles = services.packCountries.tiles ?: return phone
        // its own index lists nothing there: not its (Antwerp, in the Netherlands' box)
        val notIts = MapEngine.countries.any { c -> pack(c.id) == phone && tiles.level(c.id, at.lat, at.lon) == 0 }
        return phone.takeUnless { notIts }
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
     * nation most name, each read in its place's country; null with no region downloaded. Kept per kilometre, per count
     * of regions (a download changes it) and per [PackCountries.generation] (a region's pack found changed; the app
     * maintainer, 19 November 2026, batch 6 A1: the kilometres asked while a region came read no pack of it for the
     * run), [KEPT] at most. A point is asked once at a time: the rows of a list in one kilometre, read together, take
     * the first one's answer (18 November 2026, batch 5 B12: 500 saved places in 14 kilometres asked 136 times, the
     * store's lock 64 deep).
     */
    private fun regionsSay(services: Services, at: LatLon): HereRegion? {
        val store = services.placeStores
        val size = store.size
        services.regionsCounted = size
        if (size == 0) return null
        // the regions' files written since their packs were read are read again first: a pack named since lets go what
        // was said without it
        services.packCountries.recheck(store.stores)
        val key = key(size, services.packCountries.generation, at)
        said[key]?.let { return it }
        val turn = asking.computeIfAbsent(key) { Any() }
        try {
            return synchronized(turn) {
                said[key] ?: run {
                    val sample = runCatching { store.inBox(BBox.around(at, LOOK_M), SAMPLE) }.getOrNull().orEmpty()
                    val country = commonest(sample) { PlaceCountry.tagged(it.tags) } ?: services.packCountries.ofRegionAt(store.stores, at)
                    val around = country ?: beyondRegions(services, at, services.packCountries::of)
                    val found = HereRegion(country, commonest(sample) { PlaceCountry.subdivision(it.tags, PlaceCountry.of(it.tags, around)) })
                    if (said.size >= KEPT) said.clear()
                    said[key] = found
                    found
                }
            }
        } finally {
            asking.remove(key, turn)
        }
    }

    private inline fun commonest(records: List<PlaceRecord>, of: (PlaceRecord) -> String?): String? {
        val counts = HashMap<String, Int>()
        for (r in records) of(r)?.let { counts.merge(it, 1, Int::plus) }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun key(regions: Int, generation: Int, at: LatLon) = "$regions:$generation:${(at.lat * 100).roundToInt()}:${(at.lon * 100).roundToInt()}"
}

/** Which clock a far-away place keeps, worked out without a network: its country's, else the sun's hour. */
object PlaceZone {
    /**
     * The clock at [p] in [country]: the islands far off their country's own their own ([islands]); in a country of
     * several clocks ([SEVERAL]) the country's zone nearest the sun's hour there, as before batch 4 (the app maintainer,
     * 18 November 2026, batch 5 A8: the engine's table cuts them by longitude, or gives them one zone, and put Halifax on
     * Toronto's clock, Manaus on São Paulo's, Tijuana on Mexico City's, Nashville on New York's and Kaliningrad on
     * Moscow's); in every other its own ([Countries.timezone]; batch 4 B15: the zone nearest the sun's hour put Madrid on
     * the Canaries' clock, Porto on the Azores' and Chengdu on Urumqi's), else the one nearest the sun's hour there; the
     * sun's whole hour itself where the country is unknown.
     */
    fun guess(country: String?, p: LatLon, at: Instant = Instant.now()): ZoneId {
        own(country, p)?.let { return it }
        val sun = p.lon / 15.0
        val best = zonesOf(country).minByOrNull { id -> abs(ZoneId.of(id).rules.getStandardOffset(at).totalSeconds / 3600.0 - sun) }
        return best?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.ofHours(sun.roundToInt().coerceIn(-12, 14))
    }

    /**
     * The clock at [p] in [country], not the phone's, near a phone on [phoneZone] ([HereContext.NEAR_PHONE_M]), where
     * [guess] reads it by the sun's hour (a country of several clocks, or one the table names none for): the phone's
     * zone where [country] keeps one with its offsets [now] and half a year on; else the one of its zones with the
     * phone's offset now nearest the sun's hour there, as [guess] picks; null where it keeps none such, or where
     * [guess] reads the country's own ([own]): [guess] then (the app maintainer, 19 November 2026, batch 6 A6: Windsor
     * from Detroit read Edmonton's clock, Toronto from Buffalo Atikokan's, Smolensk from Orsha Kaliningrad's, where
     * 38ddafa1 took the phone's within 300 km whatever the country). Vigo from Porto keeps Madrid's, though the
     * Canaries keep Lisbon's offsets.
     */
    fun besidePhone(country: String, p: LatLon, phoneZone: ZoneId, now: Instant): ZoneId? {
        if (own(country, p) != null) return null
        val zones = zoneIdsOf(country)
        val later = now.plus(HALF_YEAR)
        val atNow = phoneZone.rules.getOffset(now)
        val atLater = phoneZone.rules.getOffset(later)
        if (zones.any { it.rules.getOffset(now) == atNow && it.rules.getOffset(later) == atLater }) return phoneZone
        val sun = p.lon / 15.0
        return zones.filter { it.rules.getOffset(now) == atNow }.minByOrNull { abs(it.rules.getStandardOffset(now).totalSeconds / 3600.0 - sun) }
    }

    /**
     * [country]'s own clock at [p], where [guess] does not read it by the sun's hour: its islands' ([islands]), else
     * the table's ([Countries.timezone]), but in [SEVERAL].
     */
    private fun own(country: String?, p: LatLon): ZoneId? {
        islands(country, p)?.let { return ZoneId.of(it) }
        if (country in SEVERAL) return null
        return Countries.timezone(country, p).takeIf { it != "UTC" }?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() }
    }

    /**
     * The islands far off their country's own clock: the Canaries west of 12° W, the Azores west of 20° W, Easter Island
     * west of 100° W (Chile's, two hours behind Santiago: batch 5 A8, right on 38ddafa1).
     */
    private fun islands(country: String?, p: LatLon): String? = when {
        country == "ES" && p.lon < -12.0 -> "Atlantic/Canary"
        country == "PT" && p.lon < -20.0 -> "Atlantic/Azores"
        country == "CL" && p.lon < -100.0 -> "Pacific/Easter"
        else -> null
    }

    /**
     * The countries of several clocks, read by the sun's hour (batch 5 A8): those the engine's table cuts by longitude
     * (the United States, Canada, Australia, Russia, Indonesia), those it gives one zone (Mexico, Brazil), and those it
     * names none for (Kazakhstan, Mongolia, both Congos), so they stay so if it ever does.
     */
    private val SEVERAL = setOf("US", "CA", "MX", "BR", "RU", "AU", "ID", "KZ", "MN", "CD", "CG")

    /** Half a year: the offsets [besidePhone] compares are now's and this far on. */
    private val HALF_YEAR: Duration = Duration.ofDays(182)

    /** [zonesOf] as zones, listed once a country: [besidePhone] runs in a frame ([HereContext.localeNow]). */
    private val zones = ConcurrentHashMap<String, List<ZoneId>>()

    private fun zoneIdsOf(country: String): List<ZoneId> = zones.getOrPut(country) { zonesOf(country).map { ZoneId.of(it) } }

    /** The zone ids of [country] (ISO 3166 alpha-2), from the system's ICU data; none when unknown. */
    private fun zonesOf(country: String?): List<String> {
        if (country == null) return emptyList()
        return runCatching { android.icu.util.TimeZone.getAvailableIDs(country).filter { runCatching { ZoneId.of(it) }.isSuccess } }.getOrNull().orEmpty()
    }
}
