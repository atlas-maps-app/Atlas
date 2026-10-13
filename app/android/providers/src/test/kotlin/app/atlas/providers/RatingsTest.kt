package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

class RatingsTest {
    private val wester = Place(OsmId(OsmType.NODE, 2720971344), LatLon(52.3760284, 4.8838771), mapOf("amenity" to "cafe", "name" to "Café Wester"))

    private class Fake(
        override val info: ProviderInfo,
        val result: Rating?,
        val wait: Long = 0,
        val st: ProviderState = ProviderState.READY,
        override val policy: RatingPolicy = RatingPolicy(),
        val fail: Exception? = null,
    ) : RatingProvider {
        override val symbol = info.id
        override fun state() = st
        override suspend fun rating(place: Place): Rating? {
            delay(wait)
            fail?.let { throw it }
            return result
        }
    }

    private fun info(id: String) = ProviderInfo(id, id, "test", id, "https://example.org")

    @Test
    fun mergesInParallelWithinTheBudget() = runBlocking {
        val providers = listOf(
            Fake(info("a"), Rating("a", 4.5, 40)),
            Fake(info("b"), Rating("b", 4.0, 10), wait = 50),
            Fake(info("slow"), Rating("slow", 1.0, 100), wait = 5_000),
            Fake(info("keyed"), null, st = ProviderState.NEEDS_KEY),
            Fake(info("broken"), null, fail = ProviderException("broken", "HTTP 500")),
            Fake(info("yelp"), Rating("yelp", 2.0, 300), policy = RatingPolicy(mixable = false, maxCacheHours = 24)),
        )
        val start = System.nanoTime()
        val agg = RatingsAggregator(providers, budget = 400.milliseconds).rate(wester)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        assertEquals(setOf("slow", "keyed", "broken"), agg.missing.keys)
        assertEquals("timed out", agg.missing["slow"])
        assertEquals("needs key", agg.missing["keyed"])
        // Yelp is answered but kept out of the score: its terms forbid mixing.
        assertTrue(agg.sources.any { it.provider == "yelp" })
        assertEquals(50, agg.count)
        val expected = (RatingsAggregator.PRIOR_STARS * RatingsAggregator.PRIOR_WEIGHT + 4.5 * 40 + 4.0 * 10) / (RatingsAggregator.PRIOR_WEIGHT + 50)
        assertEquals(expected, agg.score!!, 1e-9)
        assertEquals(0.5, agg.spread, 1e-9)
    }

    @Test
    fun aProvidersOwnTimeoutIsItsFailureNotTheCallers() = runBlocking {
        // A provider that times out by itself throws TimeoutCancellationException, a CancellationException that is not ours.
        val impatient = object : RatingProvider {
            override val info = info("impatient")
            override val symbol = "impatient"
            override fun state() = ProviderState.READY
            override suspend fun rating(place: Place): Rating? = kotlinx.coroutines.withTimeout(10) { delay(1_000); null }
        }
        val agg = RatingsAggregator(listOf(impatient, Fake(info("a"), Rating("a", 4.5, 40))), budget = 2_000.milliseconds).rate(wester)
        assertEquals("timed out", agg.missing["impatient"])
        assertEquals(40, agg.count)
    }

    @Test
    fun cancellingTheCallerStillCancels() = runBlocking {
        val job = launch { RatingsAggregator(listOf(Fake(info("slow"), Rating("slow", 4.0, 3), wait = 10_000)), budget = 20_000.milliseconds).rate(wester) }
        delay(50)
        job.cancel()
        kotlinx.coroutines.withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun eatenThereAgainstDeliveredIsSaidOutLoud() {
        val r = Rating("mangrove", 3.9, 9, byService = mapOf(Service.EATEN_THERE to ServiceRating(4.6, 6), Service.DELIVERED to ServiceRating(2.8, 3)))
        val d = RatingsAggregator.merge(listOf(r)).disagreement!!
        assertEquals(Disagreement.Reason.EATEN_THERE_VS_DELIVERED, d.reason)
        assertEquals("eaten_there", d.high.label)
        assertEquals(1.8, d.gap, 1e-9)
        // A delivery platform's whole rating counts as "delivered".
        val withPlatform = RatingsAggregator.merge(
            listOf(Rating("mangrove", 4.5, 5, byService = mapOf(Service.EATEN_THERE to ServiceRating(4.5, 5))), Rating("deliver", 3.0, 80)),
            mapOf("deliver" to RatingPolicy(delivery = true)),
        )
        assertEquals("delivered", withPlatform.disagreement!!.low.label)
    }

    @Test
    fun providersThatDisagreeAreReportedAndSmallGapsAreNot() {
        val d = RatingsAggregator.merge(listOf(Rating("a", 4.6, 20), Rating("b", 3.1, 12))).disagreement!!
        assertEquals(Disagreement.Reason.PROVIDERS, d.reason)
        assertNull(RatingsAggregator.merge(listOf(Rating("a", 4.6, 20), Rating("b", 4.0, 12))).disagreement)
        assertNull(RatingsAggregator.merge(emptyList()).score)
    }

    @Test
    fun trendsSeeRisingAndNewPlaces() {
        val now = Instant.parse("2026-10-13T12:00:00Z")
        fun at(daysAgo: Long, stars: Double = 4.0) = Review("m", stars, null, null, now.minus(daysAgo, ChronoUnit.DAYS))
        val rising = Trends.fromReviews(listOf(at(5), at(20), at(40), at(70), at(200)), now)!!
        assertTrue(rising.rising)
        assertEquals(2, rising.last30)
        assertEquals(4, rising.last90)
        val steady = Trends.fromReviews((0 until 12).map { at(30L * it + 10) }, now)!!
        assertTrue(!steady.rising)
        val better = Trends.fromReviews(listOf(at(10, 5.0), at(20, 5.0), at(150, 3.0), at(200, 3.0), at(250, 3.5)), now)!!
        assertTrue(better.momentum >= 1.5)
        assertTrue(better.rising)
        assertTrue(Trends.fromReviews(listOf(at(10), at(30)), now)!!.newcomer)
        assertNull(Trends.fromReviews(listOf(Review("m", 4.0, null, null, null)), now))
    }

    @Test
    fun freshnessFromOsmDates() {
        val today = LocalDate.of(2026, 10, 13)
        fun p(vararg tags: Pair<String, String>) = Place(null, LatLon(0.0, 0.0), mapOf("amenity" to "restaurant", *tags))
        assertEquals(Freshness.Kind.OPENING_SOON, Trends.freshness(p("opening_date" to "2026-12"), today)!!.kind)
        assertEquals(Freshness.Kind.NEW, Trends.freshness(p("opening_date" to "2026-08-01"), today)!!.kind)
        assertEquals(Freshness.Kind.NEW, Trends.freshness(p("start_date" to "~2026-07"), today)!!.kind)
        assertEquals(Freshness.Kind.RECENTLY_CHECKED, Trends.freshness(p("check_date" to "2026-09-30", "start_date" to "1990"), today)!!.kind)
        assertNull(Trends.freshness(p("start_date" to "1990"), today))
        val closed = Place(null, LatLon(0.0, 0.0), mapOf("disused:amenity" to "restaurant", "name" to "Gone"))
        assertEquals(Freshness.Kind.CLOSED, Trends.freshness(closed, today)!!.kind)
        // The key named is the one that says it closed, not any lifecycle key that comes first.
        val renamed = Place(null, LatLon(0.0, 0.0), linkedMapOf("was:name" to "Old Name", "disused:shop" to "bakery"))
        assertEquals("disused:shop", Trends.freshness(renamed, today)!!.key)
        // A lifecycle key beside a live main key is history, not a closure.
        assertNull(Trends.freshness(Place(null, LatLon(0.0, 0.0), mapOf("was:amenity" to "bar", "amenity" to "cafe")), today))
        assertEquals(LocalDate.of(2026, 5, 12), Trends.parseDate("2026-05-12..2026-05-14"))
        assertEquals(LocalDate.of(2026, 1, 1), Trends.parseDate("early 2026"))
        assertNull(Trends.parseDate("before 1900"))
        assertNull(Trends.parseDate("2026-13-40"))
    }

    @Test
    fun foursquareSendsItsHeadersAndNormalisesTenToFive() = runBlocking {
        Mock().use { m ->
            m.fixture("doc_foursquare_search.json")
            val fsq = FoursquareRatings(testHttp(), Keys.of("foursquare" to "fsq3secret"), m.base)
            val r = fsq.rating(wester)!!
            val req = m.take()
            assertEquals("Bearer fsq3secret", req.headers["Authorization"])
            assertEquals("2025-06-17", req.headers["X-Places-Api-Version"])
            assertEquals("Café Wester", req.url.queryParameter("query"))
            assertEquals(4.2, r.stars!!, 1e-9)
            assertEquals(214, r.count)
            assertEquals(2, r.priceLevel)
            assertEquals("https://foursquare.com/v/4a27c1a4f964a520e5911fe3", r.url)
            assertEquals("Apple pie on the terrace by the church.", r.recent.first().text)
        }
    }

    @Test
    fun yelpIsShownApartAndTripadvisorIsNotKept() = runBlocking {
        Mock().use { m ->
            m.fixture("doc_yelp_search.json")
            val yelp = YelpRatings(testHttp(), Keys.of("yelp" to "y"), m.base)
            val r = yelp.rating(wester)!!
            assertEquals(4.0, r.stars!!, 0.0)
            assertEquals(87, r.count)
            assertEquals(2, r.priceLevel)
            assertEquals("https://www.yelp.com/biz/caf%C3%A9-wester-amsterdam", r.url)
            assertTrue(!yelp.policy.mixable)
            assertEquals("Bearer y", m.take().headers["Authorization"])
        }
        Mock().use { m ->
            m.fixture("doc_terra_nearby.json")
            val ta = TripAdvisorRatings(testHttp(), Keys.of("tripadvisor" to "t"), m.base)
            val r = ta.rating(wester)!!
            assertEquals(4.5, r.stars!!, 0.0)
            assertEquals(412, r.count)
            assertEquals(2, r.priceLevel)
            assertEquals(0, ta.policy.maxCacheHours)
            val req = m.take()
            assertEquals("t", req.headers["X-API-Key"])
            assertEquals("1", req.url.queryParameter("version"))
        }
    }

    @Test
    fun keyedProvidersWithoutAKeySaySo() = runBlocking {
        val fsq = FoursquareRatings(testHttp(), Keys.NONE, "http://127.0.0.1:9")
        assertEquals(ProviderState.NEEDS_KEY, fsq.state())
        try {
            fsq.rating(wester)
            fail()
        } catch (_: NeedsKeyException) {
        }
        Mock().use { m ->
            m.enqueue("""{"message":"Invalid authentication"}""", 401)
            try {
                YelpRatings(testHttp(), Keys.of("yelp" to "bad"), m.base).rating(wester)
                fail()
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("refused the key"))
            }
        }
    }

    @Test
    fun nameMatchingIsForgiving() {
        assertEquals("cafe t smalle", NameMatch.normalise("Café 't Smalle"))
        assertEquals(1.0, NameMatch.similarity("Café Wester", "cafe wester"), 0.0)
        assertTrue(NameMatch.similarity("Fastned", "Fastned B.V.") >= 0.85)
        assertTrue(NameMatch.similarity("De Hoek", "Teds") < 0.5)
        val best = NameMatch.best("Cafe Wester", LatLon(52.37603, 4.88388), listOf("Café Wester" to LatLon(52.37605, 4.88390), "Café Westers" to LatLon(52.3765, 4.8840)), nameOf = { it.first }, where = { it.second })
        assertNotNull(best)
        assertEquals("Café Wester", best!!.first)
    }
}
