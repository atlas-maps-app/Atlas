package app.atlas.ui.search

import android.os.Looper
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import app.atlas.places.OpeningHours
import app.atlas.search.PlaceRecord
import app.atlas.search.ResultHit
import app.atlas.search.TravelMode
import app.atlas.testing.Nowhere
import app.atlas.testing.testServices
import app.atlas.ui.kit.walkMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** What the review of the search package fixed: far places keep their own clock, quests can be retried, safe links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindReviewTest {
    private fun until(timeoutMs: Long = 15_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun aFarPlaceIsReadOnItsOwnClock() {
        // The test clock is Tuesday 19:30 in Amsterdam: it is 02:30 on Wednesday in Tokyo, where this place is open 01:00-04:00.
        val s = testServices()
        val cafe = Place(
            OsmId.parse("n42")!!, LatLon(35.6595, 139.7005),
            mapOf("name" to "Shibuya Kissa", "amenity" to "cafe", "opening_hours" to "Mo-Su 01:00-04:00", "addr:country" to "JP"),
        )
        val vm = PlaceViewModel(s, s.remember(cafe), null, null, null, "en")
        until { !vm.state.value.looking && vm.state.value.place != null }
        val st = vm.state.value
        assertEquals(ZoneId.of("Asia/Tokyo"), HereContext.localeOf(s, cafe, HereContext.phoneAt(s), ZoneId.of("Europe/Amsterdam")).zone)
        assertEquals(2, st.now.hour)
        assertEquals(true, st.status?.isOpen)
        assertEquals("JP", st.country)
    }

    @Test
    fun aNearPlaceKeepsThePhonesClockAndCountry() {
        val s = testServices(places = true)
        val near = Place(OsmId.parse("n7")!!, LatLon(52.3745, 4.8838), mapOf("name" to "Near"))
        val l = HereContext.localeOf(s, near, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam"))
        assertEquals(ZoneId.of("Europe/Amsterdam"), l.zone)
        assertEquals("NL", l.country)
    }

    @Test
    fun aFarPlaceWithoutACountryKeepsTheMapsOwn() {
        // Sydney, tagged with no country and with no region on the phone: the map's own country there (finding 8).
        val s = testServices()
        val far = Place(OsmId.parse("n8")!!, LatLon(-33.87, 151.21), mapOf("name" to "Far"))
        val l = HereContext.localeOf(s, far, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam"))
        assertEquals("AU", l.country)
        // Australia's zone nearest the sun's hour there: +10 at 151 degrees east, before its summer time.
        assertEquals(10 * 3600, l.zone.rules.getStandardOffset(java.time.Instant.parse("2026-10-13T00:00:00Z")).totalSeconds)
    }

    @Test
    fun aPlaceFarFromEveryCountryHasNoHolidaysOfTheirs() {
        val s = testServices()
        val far = Place(OsmId.parse("n9")!!, LatLon(-25.07, -130.10), mapOf("name" to "Adamstown"))
        val l = HereContext.localeOf(s, far, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam"))
        assertNull(l.country)
        // The sun's own hour there: -9 at 130 degrees west.
        assertEquals(-9 * 3600, l.zone.rules.getOffset(java.time.Instant.parse("2026-10-13T00:00:00Z")).totalSeconds)
    }

    @Test
    fun theZoneOfACountryNearestTheSun() {
        // Zones that keep one clock (Detroit, New York) are as good as each other: compare what they read.
        val noon = java.time.Instant.parse("2026-10-13T12:00:00Z")
        fun offset(z: ZoneId) = z.rules.getOffset(noon)
        assertEquals(offset(ZoneId.of("America/Los_Angeles")), offset(PlaceZone.guess("US", LatLon(37.77, -122.4), noon)))
        assertEquals(offset(ZoneId.of("America/New_York")), offset(PlaceZone.guess("US", LatLon(40.71, -74.0), noon)))
        assertEquals(offset(ZoneId.of("America/Chicago")), offset(PlaceZone.guess("US", LatLon(41.88, -87.6), noon)))
        assertEquals(ZoneId.of("Asia/Tokyo"), PlaceZone.guess("JP", LatLon(35.68, 139.7), noon))
    }

    /**
     * A far place keeps its country's own clock (the app maintainer, 18 November 2026, batch 4 B15): the zone of its
     * country nearest the sun's hour put Madrid on the Canaries' clock, Porto on the Azores' and Chengdu on Urumqi's. Near
     * the phone, across a border, the phone's clock was taken: Porto from Vigo read Spain's, an hour off.
     */
    @Test
    fun aPlaceKeepsItsCountrysOwnClock() {
        val s = testServices()
        val amsterdam = LatLon(52.37, 4.89)
        val vigo = LatLon(42.2406, -8.7207)
        var n = 9_100L
        fun zoneOf(at: LatLon, country: String, phone: LatLon = amsterdam, phoneZone: ZoneId = ZoneId.of("Europe/Amsterdam")): ZoneId {
            val place = Place(OsmId(OsmType.NODE, n++), at, mapOf("name" to "X", "addr:country" to country))
            return HereContext.localeOf(s, place, phone, phoneZone).zone
        }
        assertEquals(ZoneId.of("Europe/Madrid"), zoneOf(LatLon(40.4168, -3.7038), "ES"))
        assertEquals(ZoneId.of("Europe/Lisbon"), zoneOf(LatLon(41.1496, -8.6110), "PT"))
        assertEquals(ZoneId.of("Asia/Shanghai"), zoneOf(LatLon(30.6586, 104.0647), "CN"))
        // the islands keep theirs
        assertEquals(ZoneId.of("Atlantic/Canary"), zoneOf(LatLon(28.1235, -15.4363), "ES"))
        assertEquals(ZoneId.of("Atlantic/Azores"), zoneOf(LatLon(37.7412, -25.6756), "PT"))
        // Porto from Vigo, 120 km off over the border: Portugal's clock, not the phone's; Santiago from Vigo: Spain's
        assertEquals(ZoneId.of("Europe/Lisbon"), zoneOf(LatLon(41.1496, -8.6110), "PT", vigo, ZoneId.of("Europe/Madrid")))
        assertEquals(ZoneId.of("Europe/Madrid"), zoneOf(LatLon(42.8782, -8.5448), "ES", vigo, ZoneId.of("Europe/Madrid")))
    }

    /**
     * A far place in a country of several clocks is read by the sun's hour there, as on 38ddafa1 (the app maintainer, 18
     * November 2026, batch 5 A8): the engine's table cuts them by longitude, or gives them one zone, and put Halifax and
     * Moncton on Toronto's clock, St John's on Halifax's, Manaus, Cuiabá and Porto Velho on São Paulo's, Rio Branco two
     * hours off, Tijuana and Mexicali on Mexico City's, Nashville and Birmingham on New York's, Kaliningrad on Moscow's,
     * Broken Hill on Sydney's and Easter Island on Santiago's. None reads further off than on 38ddafa1, in January or in
     * July; a country of one clock keeps it (Madrid, Lisbon, Shanghai), and the islands theirs. With Santiago, Ambon
     * and Sorong (19 November 2026, batch 6 B9: Santiago on Easter Island's clock, and Indonesia by the table's cuts,
     * Ambon and Sorong on Makassar's, passed every test).
     */
    @Test
    fun aFarPlaceIsReadNoFurtherOffThanBefore() {
        val jan = java.time.Instant.parse("2026-01-15T12:00:00Z")
        val jul = java.time.Instant.parse("2026-07-15T12:00:00Z")
        // 38ddafa1's guess, for every country: its zone nearest the sun's hour at [lon], by standard offset at [at]
        fun before(country: String, lon: Double, at: java.time.Instant): ZoneId {
            val sun = lon / 15.0
            val ids = android.icu.util.TimeZone.getAvailableIDs(country).filter { runCatching { ZoneId.of(it) }.isSuccess }
            return ids.minByOrNull { id -> kotlin.math.abs(ZoneId.of(id).rules.getStandardOffset(at).totalSeconds / 3600.0 - sun) }?.let { ZoneId.of(it) }
                ?: java.time.ZoneOffset.ofHours(kotlin.math.round(sun).toInt())
        }
        fun off(z: ZoneId, right: ZoneId, at: java.time.Instant) = kotlin.math.abs(z.rules.getOffset(at).totalSeconds - right.rules.getOffset(at).totalSeconds)
        val far = listOf(
            Triple("America/Halifax", "CA", LatLon(44.6488, -63.5752)),
            Triple("America/Moncton", "CA", LatLon(46.0878, -64.7782)),
            Triple("America/St_Johns", "CA", LatLon(47.5615, -52.7126)),
            Triple("America/Manaus", "BR", LatLon(-3.1190, -60.0217)),
            Triple("America/Cuiaba", "BR", LatLon(-15.6014, -56.0979)),
            Triple("America/Porto_Velho", "BR", LatLon(-8.7612, -63.9004)),
            Triple("America/Rio_Branco", "BR", LatLon(-9.9754, -67.8249)),
            Triple("America/Tijuana", "MX", LatLon(32.5149, -117.0382)),
            Triple("America/Tijuana", "MX", LatLon(32.6245, -115.4523)),
            Triple("America/Chicago", "US", LatLon(36.1627, -86.7816)),
            Triple("America/Chicago", "US", LatLon(33.5186, -86.8104)),
            Triple("Europe/Kaliningrad", "RU", LatLon(54.7104, 20.4522)),
            Triple("Australia/Broken_Hill", "AU", LatLon(-31.9539, 141.4539)),
            Triple("Pacific/Easter", "CL", LatLon(-27.15, -109.4333)),
            Triple("America/Santiago", "CL", LatLon(-33.4489, -70.6693)),
            Triple("Asia/Jayapura", "ID", LatLon(-3.6954, 128.1814)),
            Triple("Asia/Jayapura", "ID", LatLon(-0.8762, 131.2558)),
            Triple("Europe/Madrid", "ES", LatLon(40.4168, -3.7038)),
            Triple("Europe/Lisbon", "PT", LatLon(41.1496, -8.6110)),
            Triple("Asia/Shanghai", "CN", LatLon(30.6586, 104.0647)),
        )
        val worse = ArrayList<String>()
        for ((right, country, at) in far) for (i in listOf(jan, jul)) {
            val r = ZoneId.of(right)
            val now = PlaceZone.guess(country, at, i)
            val was = before(country, at.lon, i)
            if (off(now, r, i) > off(was, r, i)) worse += "$right ($country $at) on $i: $now, where 38ddafa1 read $was"
        }
        assertEquals("further off than on 38ddafa1", emptyList<String>(), worse)
        // a country of one clock keeps it exactly, and the islands theirs
        assertEquals(ZoneId.of("Europe/Madrid"), PlaceZone.guess("ES", LatLon(40.4168, -3.7038), jan))
        assertEquals(ZoneId.of("Europe/Lisbon"), PlaceZone.guess("PT", LatLon(41.1496, -8.6110), jan))
        assertEquals(ZoneId.of("Asia/Shanghai"), PlaceZone.guess("CN", LatLon(30.6586, 104.0647), jan))
        assertEquals(ZoneId.of("Atlantic/Canary"), PlaceZone.guess("ES", LatLon(28.1235, -15.4363), jan))
        assertEquals(ZoneId.of("Atlantic/Azores"), PlaceZone.guess("PT", LatLon(37.7412, -25.6756), jan))
        // over the border from Los Angeles (200 km), the phone's country another: Mexico's clock there, not Mexico City's
        val s = testServices()
        val tijuana = Place(OsmId(OsmType.NODE, 9_300L), LatLon(32.5149, -117.0382), mapOf("name" to "X", "addr:country" to "MX"))
        val seen = HereContext.localeOf(s, tijuana, LatLon(34.0522, -118.2437), ZoneId.of("America/Los_Angeles")).zone
        for (i in listOf(jan, jul)) assertEquals("$i", ZoneId.of("America/Tijuana").rules.getOffset(i), seen.rules.getOffset(i))
    }

    /**
     * Where a country's zones tie at the standard offset nearest the sun's hour, the table's for the point comes before
     * the alphabet's first (the app maintainer, 19 November 2026, batch 6 B13): Toronto, Montréal and Ottawa read
     * Atikokan's clock, which keeps no summer time (an hour off in July, on 38ddafa1 too), and Honolulu Adak's, which
     * does. Where the table's is not among them, the first as before: Halifax and Moncton keep Blanc-Sablon's, as the
     * table cuts Canada at 60° W (the far list above).
     */
    @Test
    fun whereZonesTieTheTablesComesFirst() {
        val jul = java.time.Instant.parse("2026-07-15T12:00:00Z")
        val toronto = ZoneId.of("America/Toronto")
        assertEquals(toronto, PlaceZone.guess("CA", LatLon(43.6532, -79.3832), jul))
        assertEquals(toronto, PlaceZone.guess("CA", LatLon(45.5019, -73.5674), jul))
        assertEquals(toronto, PlaceZone.guess("CA", LatLon(45.4215, -75.6972), jul))
        assertEquals(ZoneId.of("Pacific/Honolulu"), PlaceZone.guess("US", LatLon(21.3069, -157.8583), jul))
        // as a place's page reads it, far from the phone
        val s = testServices()
        val montreal = Place(OsmId(OsmType.NODE, 9_310L), LatLon(45.5019, -73.5674), mapOf("name" to "X", "addr:country" to "CA"))
        assertEquals(toronto, HereContext.localeOf(s, montreal, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam")).zone)
    }

    /**
     * Near the phone over a border, a place in a country of several clocks reads one of its country's zones like the
     * phone's (the app maintainer, 19 November 2026, batch 6 A6): Windsor from Detroit read Edmonton's clock, Detroit
     * from Windsor Chicago's, Toronto from Buffalo and Montréal from Burlington Atikokan's (no summer time), Nuevo
     * Laredo from Laredo Ciudad Juárez's, Foz do Iguaçu from Puerto Iguazú Boa Vista's, Smolensk from Orsha
     * Kaliningrad's; 38ddafa1 took the phone's zone within 300 km whatever the country. A country the table answers
     * keeps its own (Vigo from Porto Madrid's, though the Canaries keep Lisbon's offsets); Kaliningrad from Gdańsk in
     * January its own, as no zone of Russia's keeps Poland's offset then; beyond 300 km nothing changes (A8's places
     * from Amsterdam). The regions name the phone's country, as the boxes alone put Windsor in the United States and
     * Laredo in Mexico.
     */
    @Test
    fun aPlaceOverABorderNearThePhoneReadsAZoneOfItsCountryLikeThePhones() {
        val jan = java.time.Instant.parse("2026-01-15T17:00:00Z")
        val jul = java.time.Instant.parse("2026-07-15T16:00:00Z")
        var n = 9_400L
        var size = 2
        // a place at [at] in [country], from a phone at [phone] on [phoneZone] whose regions say [phoneCountry], the
        // app's clock at [now]; each store a place larger than the last, as what the regions said is kept by their
        // count
        fun zoneOf(at: LatLon, country: String, phone: LatLon, phoneZone: String, phoneCountry: String, now: java.time.Instant): ZoneId {
            val here = (1..size++).map { i ->
                Place(OsmId(OsmType.NODE, n++), LatLon(phone.lat + i * 0.0005, phone.lon), mapOf("name" to "Here $i", "amenity" to "cafe", "addr:country" to phoneCountry))
            }
            val s = testServices(clock = java.time.Clock.fixed(now, ZoneId.of(phoneZone)), stores = listOf(app.atlas.search.MemoryPlaces(here, country = null)))
            try {
                assertEquals("the phone's country", phoneCountry, HereContext.country(s, phone))
                val place = Place(OsmId(OsmType.NODE, n++), at, mapOf("name" to "X", "addr:country" to country))
                return HereContext.localeOf(s, place, phone, ZoneId.of(phoneZone)).zone
            } finally {
                s.close()
            }
        }
        fun reads(right: String, seen: ZoneId, at: java.time.Instant) = assertEquals("$right at $at", ZoneId.of(right).rules.getOffset(at), seen.rules.getOffset(at))
        val detroit = LatLon(42.3314, -83.0458)
        val windsor = LatLon(42.3149, -83.0364)
        for (i in listOf(jan, jul)) {
            reads("America/Toronto", zoneOf(windsor, "CA", detroit, "America/Detroit", "US", i), i)
            reads("America/Detroit", zoneOf(detroit, "US", windsor, "America/Toronto", "CA", i), i)
            reads("America/Toronto", zoneOf(LatLon(43.6532, -79.3832), "CA", LatLon(42.8864, -78.8784), "America/New_York", "US", i), i)
            reads("America/Toronto", zoneOf(LatLon(45.5019, -73.5674), "CA", LatLon(44.4759, -73.2121), "America/New_York", "US", i), i)
            reads("America/Matamoros", zoneOf(LatLon(27.4763, -99.5164), "MX", LatLon(27.5306, -99.4803), "America/Chicago", "US", i), i)
            reads("America/Sao_Paulo", zoneOf(LatLon(-25.5469, -54.5882), "BR", LatLon(-25.5972, -54.5786), "America/Argentina/Cordoba", "AR", i), i)
            reads("Europe/Moscow", zoneOf(LatLon(54.7826, 32.0453), "RU", LatLon(54.5081, 30.4172), "Europe/Minsk", "BY", i), i)
        }
        // neighbours, as before: a country the table answers keeps its own; Kaliningrad its own from Gdańsk in January
        val porto = LatLon(41.1496, -8.6110)
        val vigo = LatLon(42.2406, -8.7207)
        assertEquals(ZoneId.of("Europe/Madrid"), zoneOf(vigo, "ES", porto, "Europe/Lisbon", "PT", jan))
        assertEquals(ZoneId.of("Europe/Lisbon"), zoneOf(porto, "PT", vigo, "Europe/Madrid", "ES", jan))
        reads("Europe/Kaliningrad", zoneOf(LatLon(54.7104, 20.4522), "RU", LatLon(54.3520, 18.6466), "Europe/Warsaw", "PL", jan), jan)
        // beyond 300 km nothing changes: A8's places, from Amsterdam, read as the guess reads them
        val s = testServices()
        val amsterdam = LatLon(52.37, 4.89)
        val far = listOf(
            "CA" to LatLon(44.6488, -63.5752), "CA" to LatLon(46.0878, -64.7782), "CA" to LatLon(47.5615, -52.7126),
            "BR" to LatLon(-3.1190, -60.0217), "BR" to LatLon(-15.6014, -56.0979), "BR" to LatLon(-8.7612, -63.9004),
            "BR" to LatLon(-9.9754, -67.8249), "MX" to LatLon(32.5149, -117.0382), "MX" to LatLon(32.6245, -115.4523),
            "US" to LatLon(36.1627, -86.7816), "US" to LatLon(33.5186, -86.8104), "RU" to LatLon(54.7104, 20.4522),
            "AU" to LatLon(-31.9539, 141.4539), "CL" to LatLon(-27.15, -109.4333),
        )
        for ((country, at) in far) {
            val place = Place(OsmId(OsmType.NODE, n++), at, mapOf("name" to "X", "addr:country" to country))
            assertEquals("$country $at", PlaceZone.guess(country, at), HereContext.localeOf(s, place, amsterdam, ZoneId.of("Europe/Amsterdam")).zone)
        }
        s.close()
    }

    @Test
    fun aFailedNoteLeavesItsQuestionOpenToAskAgain() {
        val place = Place(OsmId.parse("n9")!!, LatLon(52.37, 4.88), mapOf("name" to "X", "amenity" to "cafe"))
        val quests = Quests.of(app.atlas.places.Facts.of(place, "en", java.time.LocalDateTime.now(), app.atlas.places.OpeningHoursContext.Default))
        assertTrue(quests.size >= 2)
        val st = PlaceState(place = place, quests = quests)
        assertEquals(quests[0], st.openQuest)
        assertEquals(quests[1], st.copy(answered = mapOf(quests[0].key to Sending.Busy)).openQuest)
        assertEquals(quests[1], st.copy(answered = mapOf(quests[0].key to Sending.Done("u"))).openQuest)
        assertEquals(quests[0], st.copy(answered = mapOf(quests[0].key to Sending.Failed("offline"))).openQuest)
        assertNull(st.copy(answered = quests.associate { it.key to Sending.Done("u") }).openQuest)
    }

    @Test
    fun aFailedAnswerKeepsItsQuestOnTheQuestsScreen() {
        val place = Place(OsmId.parse("n9")!!, LatLon(52.37, 4.88), mapOf("name" to "X", "amenity" to "cafe"))
        val facts = app.atlas.places.Facts.of(place, "en", java.time.LocalDateTime.now(), app.atlas.places.OpeningHoursContext.Default)
        val quest = Quests.of(facts).first()
        val q = PlaceQuest(place, quest, 30.0)
        val base = QuestsState(quests = listOf(q))
        assertEquals(q, base.current)
        assertNull(base.copy(sending = mapOf(q.id to Sending.Busy)).current)
        assertNull(base.copy(sending = mapOf(q.id to Sending.Done(null))).current)
        assertEquals(q, base.copy(sending = mapOf(q.id to Sending.Failed("offline"))).current)
        assertNull(base.copy(skipped = setOf(q.id), sending = mapOf(q.id to Sending.Failed("offline"))).current)
    }

    @Test
    fun onlyWebAddressesAreOpened() {
        assertEquals("https://example.nl", webUrl("example.nl"))
        assertEquals("http://example.nl/menu", webUrl(" http://example.nl/menu ; other"))
        assertEquals("https://example.nl:8080/x", webUrl("example.nl:8080/x"))
        assertNull(webUrl("javascript:alert(1)"))
        assertNull(webUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(webUrl("file:///sdcard/x"))
        assertNull(webUrl("ftp://example.nl"))
        assertNull(webUrl("two words"))
        assertNull(webUrl(""))
        assertNull(webUrl(null))
    }

    @Test
    fun aFarWalkIsADistance() {
        assertEquals(walkMinutes(app.atlas.ui.kit.WalkTime.seconds(500.0)), walkOrDistance(500.0))
        assertEquals("12 km", walkOrDistance(12_000.0))
        assertEquals("480 km", walkOrDistance(480_000.0))
    }

    @Test
    fun anEveningEndingAtMidnightSaysSo() {
        assertEquals("18:00–24:00", spanWords(listOf(OpenSpan(18 * 60, 1440, OpenState.OPEN))))
        assertEquals("24 h", spanWords(listOf(OpenSpan(0, 1440, OpenState.OPEN))))
        assertEquals("18:00–1:00", spanWords(listOf(OpenSpan(18 * 60, 25 * 60, OpenState.OPEN))))
    }

    @Test
    fun anOpenEndNamesNoClosingTime() {
        // a day's words: the stated end and a "+", or the start and a "+", never the guess of how long it lasts
        assertEquals("16:00–2:00+", spanWords(OpeningHours.parse("Tu-Su 16:00-02:00+").weekTable(weekOf = null, context = Nowhere)[6]))
        assertEquals("17:00+", spanWords(OpeningHours.parse("Mo-Su 17:00+").weekTable(weekOf = null, context = Nowhere)[0]))
        assertEquals("10:00–14:00, 17:00+", spanWords(OpeningHours.parse("Mo 10:00-14:00,17:00+").weekTable(weekOf = null, context = Nowhere)[0]))
        // a row at 21:00: neither "closed when you get there" nor minutes to spare from the guess
        val evening = LocalDateTime.of(2026, 11, 17, 21, 0)
        val bar = OpeningHours.parse("Mo-Su 17:00+").status(evening, context = Nowhere)
        assertNull(arrivalOf(bar, 90, evening))
        val record = PlaceRecord(Place(OsmId(OsmType.NODE, 1), LatLon(52.37, 4.89), mapOf("amenity" to "bar", "opening_hours" to "Mo-Su 17:00+")), null, null, null, "maki-bar")
        val hit = ResultHit(record, 400.0, 5, TravelMode.WALK, bar, hoursConfirmed = true, hoursStale = false, node = null, score = 1.0)
        assertNull(spareMinutes(hit, evening))
        // as before where the hours state the closing
        val pub = OpeningHours.parse("Mo-Su 17:00-22:00").status(evening, context = Nowhere)
        assertEquals(Arrival.CLOSED to -30, arrivalOf(pub, 90, evening))
        assertEquals(55, spareMinutes(hit.copy(open = pub), evening))
    }
}
