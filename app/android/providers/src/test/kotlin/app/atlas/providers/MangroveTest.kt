package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MangroveTest {
    private val now = Instant.parse("2026-10-13T18:00:00Z")
    private val pfannenzauber = Place(OsmId(OsmType.NODE, 6654969636), LatLon(50.7736799, 6.0911333), mapOf("amenity" to "restaurant", "name" to "Pfannenzauber"))

    @Test
    fun subjectsAreWrittenAsMapCompleteWritesThem() {
        assertEquals("geo:52.3572677,4.8916152?q=Vegan%20Junk%20Food%20Bar&u=30", GeoSubject(LatLon(52.3572677, 4.8916152), "Vegan Junk Food Bar").toString())
        assertEquals("geo:52.3864765,4.9090476?q=Caf%C3%A9%20Modern&u=10", GeoSubject(LatLon(52.3864765, 4.9090476), "Café  Modern ", 10).toString())
        // A no-break space is white space to reviews/mangrove.py (str.split), so the same place gets the same subject.
        assertEquals("geo:52.3864765,4.9090476?q=Caf%C3%A9%20Modern&u=10", GeoSubject(LatLon(52.3864765, 4.9090476), "Café\u00A0Modern", 10).toString())
        // Clients that print every digit are rounded to seven decimals (about a centimetre), as MapComplete writes.
        assertEquals("geo:50.8404626,4.4012195?q=Le%20Pain%20Quotidien&u=30", GeoSubject(LatLon(50.840462599999995, 4.4012195), "Le Pain Quotidien").toString())
        assertEquals("Julia's%20Cucina%20Italiana", GeoSubject.encodeComponent("Julia's Cucina Italiana"))
    }

    @Test
    fun everySubjectInTheRecordedPoolRoundTrips() {
        val reviews = Mangrove.parseReviews(Fixtures.text("mangrove_geo.json").let { ProviderJson.parseToJsonElement(it).asObject() })
        assertEquals(14, reviews.size)
        for (r in reviews) {
            val s = GeoSubject.parse(r.sub) ?: error("unparsed ${r.sub}")
            val decimals = r.sub.substringAfter("geo:").substringBefore('?').split(',').maxOf { it.substringAfter('.', "").length }
            if (decimals <= 7) assertEquals(r.sub, s.toString()) else assertTrue(GeoSubject.parse(s.toString())!!.sameAs(s))
        }
    }

    @Test
    fun sameAsFollowsMangrovesRule() {
        val a = GeoSubject(LatLon(52.0, 4.0), "Café 't Smalle", 30)
        assertTrue(a.sameAs(GeoSubject(LatLon(52.0003, 4.0), "cafe t smalle", 30)))
        assertFalse(a.sameAs(GeoSubject(LatLon(52.0010, 4.0), "Cafe 't Smalle", 30)))
        assertFalse(a.sameAs(GeoSubject(LatLon(52.0, 4.0), "Smalle", 30)))
    }

    @Test
    fun realReviewSignaturesVerify() {
        val all = Mangrove.parseReviews(ProviderJson.parseToJsonElement(Fixtures.text("mangrove_geo.json")).asObject()) +
            Mangrove.parseReviews(ProviderJson.parseToJsonElement(Fixtures.text("mangrove_reviews.json")).asObject())
        assertEquals(19, all.size)
        for (r in all) assertTrue("signature of ${r.sub}", MangroveWriter.verify(r.jwt!!))
        // A changed payload no longer matches its signature.
        val jwt = all.first().jwt!!
        val parts = jwt.split('.')
        val forged = MangroveWriter.payloadOf(jwt)!!.toString().replace("\"rating\":100", "\"rating\":0")
        assertFalse(MangroveWriter.verify(parts[0] + "." + MangroveWriter.b64url(forged.toByteArray()) + "." + parts[2]))
    }

    @Test
    fun oneReviewerIsOneVoice() {
        val reviews = Mangrove.parseReviews(ProviderJson.parseToJsonElement(Fixtures.text("mangrove_reviews.json")).asObject())
        assertEquals(5, reviews.size)
        val r = Mangrove.ratingOf(reviews, pfannenzauber, now)
        // Five lib.reviews imports by one key: the newest (100 → 5 stars) is that reviewer's verdict, as Mangrove's own quality says.
        assertEquals(1, r.count)
        assertEquals(5.0, r.stars!!, 1e-9)
        assertEquals(5, r.recent.size)
        assertEquals("CC-BY-SA-4.0", r.recent.first().licence)
        assertEquals("Schreini", r.recent.first().author)
        assertEquals(OsmId(OsmType.NODE, 6654969636), reviews.first().osmId)
        assertNotNull(r.trend)
        assertEquals(1, r.trend!!.total)
    }

    @Test
    fun machineWrittenReviewsNeverCount() {
        fun review(sig: String, kid: String, rating: Int, generated: Boolean) = Mangrove.parseReview(
            ProviderJson.parseToJsonElement(
                """{"signature":"$sig","kid":"$kid","payload":{"sub":"geo:50.7736799,6.0911333?q=Pfannenzauber&u=30","rating":$rating,"iat":1760000000,"metadata":{"is_generated":$generated}}}""",
            ).asObject()!!,
        )!!
        val bots = Mangrove.ratingOf(listOf(review("s1", "k1", 100, true), review("s2", "k2", 100, true)), pfannenzauber, now)
        assertNull(bots.stars)
        assertEquals(0, bots.count)
        assertTrue(bots.recent.isEmpty())
        assertNull(RatingsAggregator.merge(listOf(bots)).score)
        val mixed = Mangrove.ratingOf(listOf(review("s1", "k1", 100, true), review("s3", "k3", 25, false)), pfannenzauber, now)
        assertEquals(2.0, mixed.stars!!, 1e-9)
        assertEquals(1, mixed.count)
    }

    @Test
    fun summariesUseMangrovesScale() {
        val s = Mangrove.parseSummary(ProviderJson.parseToJsonElement(Fixtures.text("mangrove_subject.json")).asObject()!!)!!
        assertEquals(100.0, s.quality!!, 1e-9)
        assertEquals(5, s.count)
        assertEquals(5.0, Mangrove.stars(s.quality.toInt()), 1e-9)
        assertEquals(1.0, Mangrove.stars(0), 0.0)
        assertEquals(50, Mangrove.ratingOf(3.0))
    }

    @Test
    fun ratingAsksForThePlacesSubject() = runBlocking {
        Mock().use { m ->
            m.fixture("mangrove_reviews.json")
            val r = Mangrove(testHttp(), m.base) { now }.rating(pfannenzauber)!!
            val req = m.take()
            assertEquals("/reviews", req.url.encodedPath)
            assertEquals("geo:50.7736799,6.0911333?q=Pfannenzauber&u=30", req.url.queryParameter("sub"))
            assertEquals(5.0, r.stars!!, 0.0)
            assertEquals("https://mangrove.reviews/list?sub=geo%3A50.7736799%2C6.0911333%3Fq%3DPfannenzauber%26u%3D30", r.url)
        }
    }

    @Test
    fun aPlaceWithoutANameIsFoundByItsOsmIdNearby() = runBlocking {
        Mock().use { m ->
            m.fixture("mangrove_geo.json")
            val unnamed = Place(OsmId(OsmType.NODE, 2825262963), LatLon(52.356249, 4.9115256), mapOf("amenity" to "restaurant"))
            val reviews = Mangrove(testHttp(), m.base).reviewsFor(unnamed)
            assertEquals("/geo", m.take().url.encodedPath)
            assertEquals(1, reviews.size)
            assertEquals("geo:52.356249,4.9115256?q=Veganees&u=50", reviews.single().sub)
        }
    }

    @Test
    fun viewportReviewsAreMatchedToPlaces() {
        val reviews = Mangrove.parseReviews(ProviderJson.parseToJsonElement(Fixtures.text("mangrove_geo.json")).asObject())
        val places = listOf(
            Place(OsmId(OsmType.NODE, 2817516948), LatLon(52.3552405, 4.8911077), mapOf("name" to "De Patchka")),
            Place(OsmId(OsmType.NODE, 1), LatLon(52.3864700, 4.9090400), mapOf("name" to "Cafe Modern")),
            Place(OsmId(OsmType.NODE, 2), LatLon(52.3800, 4.8800), mapOf("name" to "Elsewhere")),
        )
        val m = Mangrove.match(reviews, places)
        assertEquals(1, m["n2817516948"]?.size)
        assertEquals(1, m["n1"]?.size)
        assertNull(m["n2"])
    }

    @Test
    fun batchSummaries() = runBlocking {
        Mock().use { m ->
            m.fixture("mangrove_batch.json")
            val subs = listOf("geo:50.7736799,6.0911333?q=Pfannenzauber&u=30", "geo:50.7750682,6.0877905?q=Frittenwerk&u=30")
            val got = Mangrove(testHttp(), m.base).summaries(subs)
            assertEquals(25.0, got[subs[1]]!!.quality!!, 1e-9)
            val req = m.take()
            assertEquals("POST", req.method)
            assertTrue(req.text.contains("Frittenwerk"))
            assertEquals("/batch", req.url.toString().toHttpUrl().encodedPath)
        }
    }
}
