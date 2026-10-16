package app.atlas.ui.shop

import app.atlas.geo.LatLon
import app.atlas.providers.Http
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** The Open Prices client against a local server that answers with a real recorded page. */
class OpenPricesTest {
    private val page = File(System.getProperty("atlas.repo"), "app/android/app/src/test/resources/shop/open_prices_amsterdam.json").readText()

    @Test
    fun itAsksForACategoryAroundAPointAndReadsTheAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(page).build())
            server.start()
            val client = OpenPrices(Http(), base = server.url("/").toString().trimEnd('/'))
            val reports = client.around(LatLon(52.3745, 4.8838), "en:milks", radiusKm = 3.0, today = LocalDate.of(2026, 10, 13))
            val req = server.takeRequest()
            val q = req.url
            assertEquals("/api/v1/prices", q.encodedPath)
            assertEquals("en:milks", q.queryParameter("product__categories_tags__contains"))
            assertEquals("3.0", q.queryParameter("radius_km"))
            assertEquals("2026-04-16", q.queryParameter("date__gte"))
            assertEquals("-date", q.queryParameter("order_by"))
            // The person's position goes out blurred to about 100 m, not to the metre.
            assertTrue(q.queryParameter("lat")!!.substringAfter('.').length <= 3)
            assertTrue(q.queryParameter("lon")!!.substringAfter('.').length <= 3)
            assertTrue(reports.isNotEmpty())
        }
    }

    @Test
    fun anOfflinePhoneFailsAtOnceAndTheBookSaysWhy() = runBlocking {
        val data = PriceBook(OpenPrices(null)).load(listOf(ListEntry("milk")), LatLon(52.3745, 4.8838))
        assertEquals("offline", data.error)
        assertTrue(data.reports.values.all { it.isEmpty() })
    }

    @Test(expected = IOException::class)
    fun withoutAClientItThrows() {
        runBlocking { OpenPrices(null).around(LatLon(52.0, 4.0), "en:milks") }
    }
}
