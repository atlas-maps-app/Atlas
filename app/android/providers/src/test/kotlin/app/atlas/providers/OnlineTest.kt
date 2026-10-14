package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The real services, once each. Skipped unless -Datlas.online=true. Nothing here writes anything anywhere:
 * no review is submitted and no note is created.
 */
class OnlineTest {
    private val http = Http()
    private val westerkerk = LatLon(52.3745, 4.8838)

    @Before
    fun online() = assumeOnline()

    private fun report(line: String) = println("ONLINE $line")

    @Test
    fun overpassCafesNearTheWesterkerk() = runBlocking {
        val overpass = Overpass(http)
        val cafes = overpass.places(OverpassQl.places(listOf(OverpassQl.tag("amenity", "cafe")), OverpassArea.Around(westerkerk, 350.0)))
        report("Overpass: ${cafes.size} cafés within 350 m of the Westerkerk via ${overpass.lastEndpoint}: ${cafes.mapNotNull { it.name() }.take(6)}")
        assertTrue(cafes.size >= 10)
        assertTrue(cafes.all { Geo.distance(it.location, westerkerk) < 400 })
    }

    @Test
    fun mangroveReviewsForAReviewedPlace() = runBlocking {
        val place = Place(OsmId(OsmType.NODE, 6654969636), LatLon(50.7736799, 6.0911333), mapOf("amenity" to "restaurant", "name" to "Pfannenzauber"))
        val mangrove = Mangrove(http)
        val rating = mangrove.rating(place)!!
        val reviews = mangrove.reviewsFor(place)
        report("Mangrove: Pfannenzauber (Aachen) ${reviews.size} reviews, ${rating.count} reviewer(s), ${rating.stars} stars; all signatures valid: ${reviews.all { MangroveWriter.verify(it.jwt!!) }}")
        val viewport = mangrove.near(app.atlas.geo.BBox(4.85, 52.35, 4.92, 52.39))
        report("Mangrove: ${viewport.size} reviews in central Amsterdam")
        assertTrue(rating.count >= 1)
        assertTrue(reviews.isNotEmpty())
    }

    @Test
    fun openChargeMapNearAmsterdam() = runBlocking {
        val key = System.getProperty("atlas.ocm.key")
        if (key.isNullOrBlank()) {
            // Without a key OCM still refuses, and the provider says so instead of pretending.
            val res = http.execute(http.request("https://api.openchargemap.io/v3/poi/?output=json&maxresults=1&latitude=52.3745&longitude=4.8838").build())
            report("Open Charge Map without a key: HTTP ${res.code} ${res.text.take(100)}")
            assertTrue(res.code == 403 || res.code == 401)
            assertEquals(ProviderState.NEEDS_KEY, OpenChargeMap(http).state())
        } else {
            val near = OpenChargeMap(http, Keys.of("ocm" to key)).near(westerkerk, 5_000.0)
            report("Open Charge Map: ${near.size} licensed chargers within 5 km of the Westerkerk")
            assertTrue(near.isNotEmpty())
        }
    }

    @Test
    fun openMeteoForAmsterdamAndTheSea() = runBlocking {
        val weather = Weather(http)
        val f = weather.forecast(westerkerk)
        val now = f.now!!
        report("Open-Meteo: ${now.temperature} °C, ${now.sky}, wind ${now.windSpeed} km/h from ${now.windDirection}°, gusts ${now.windGusts}; ${f.hours.size} h, ${f.days.size} d; next rain ${f.nextRain(Instant.now())}")
        assertEquals(48, f.hours.size)
        assertEquals(7, f.days.size)
        val sea = weather.marine(LatLon(52.47, 4.45))!!
        report("Open-Meteo marine off IJmuiden: waves ${sea.now?.waveHeight} m from ${sea.now?.waveDirection}°, water ${sea.now?.waterTemperature} °C")
        assertTrue(sea.hours.isNotEmpty())
    }

    @Test
    fun wikidataAlbertHeijnAndTheWesterkerk() = runBlocking {
        val got = Wikidata(http).entities(listOf("Q1653985", "Q1130722"), listOf("nl", "en"))
        val ah = got.getValue("Q1653985")
        val kerk = got.getValue("Q1130722")
        report("Wikidata: ${ah.label(listOf("en"))}: logo ${ah.logo}, since ${ah.inception}; ${kerk.label(listOf("en"))}: architect ${kerk.architects.map { it.label(listOf("en")) }}, style ${kerk.styles.map { it.label(listOf("en")) }}, ${kerk.inception}")
        assertEquals("Albert Heijn", ah.label(listOf("nl")))
        assertNotNull(ah.logo)
        val files = Commons(http).files(listOfNotNull(ah.logo, kerk.image))
        report("Commons: ${files.values.map { "${it.title}: ${it.licence}" }}")
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun photonAndNominatimFindTheWesterkerk() = runBlocking {
        val photon = Photon(http).search("Westerkerk", westerkerk, "en", 5)
        report("Photon: ${photon.size} results, first ${photon.first().name()} ${photon.first().id} ${photon.first().extra["label"]}")
        assertEquals(OsmId(OsmType.WAY, 99205257), photon.first().id)
        val reverse = Photon(http).reverse(westerkerk, "en")
        report("Photon reverse: ${reverse?.extra?.get("label")}")
        val nominatim = Nominatim(http).search("Westerkerk Amsterdam", westerkerk)
        report("Nominatim: ${nominatim.size} results, first ${nominatim.firstOrNull()?.id} ${nominatim.firstOrNull()?.extra?.get("label")}")
        assertTrue(nominatim.any { it.id == OsmId(OsmType.WAY, 99205257) })
    }

    @Test
    fun afdcWithTheDemoKey() = runBlocking {
        val near = Afdc(http, appKey = "DEMO_KEY").near(LatLon(38.8977, -77.0365), 3_000.0, ChargerFilters(minKw = 50.0, maxResults = 5))
        report("AFDC: ${near.size} fast chargers within 3 km of the White House: ${near.map { it["name"] + " " + it[Sockets.POWER_KW] + " kW" }}")
        assertTrue(near.isNotEmpty())
    }

    @Test
    fun nationalRegistersAreWhereTheyShouldBe() = runBlocking {
        val head = http.exchange(http.request(NdwChargers.URL).head().build()) { it.code to it.header("Last-Modified") + " " + it.header("Content-Length") }
        report("NDW: HTTP ${head.first}, ${head.second}")
        assertEquals(200, head.first)
        val page = http.execute(http.request(BnetzaChargers.PAGE).header("Accept", "text/html").build()).requireOk().text
        val csv = BnetzaChargers.csvLink(page)
        report("Bundesnetzagentur register: $csv")
        assertNotNull(csv)
    }

    @Test
    fun transitousPlansAJourney() = runBlocking {
        val journeys = Transitous(http).plan(westerkerk, LatLon(52.3791, 4.9003), Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS), count = 2)
        report("Transitous: ${journeys.size} journeys; first ${journeys.firstOrNull()?.legs?.map { it.mode + (it.line?.let { l -> " $l" } ?: "") }} in ${journeys.firstOrNull()?.durationSeconds?.div(60)} min")
        assertTrue(journeys.isNotEmpty())
    }

    @Test
    fun osmNotesNearTheWesterkerk() = runBlocking {
        val notes = OsmNotes(http).inBox(app.atlas.geo.BBox.around(westerkerk, 1_000.0), limit = 20)
        report("OSM notes: ${notes.size} open notes within 1 km (read only; nothing is posted)")
        assertTrue(notes.all { it.id > 0 })
    }
}
