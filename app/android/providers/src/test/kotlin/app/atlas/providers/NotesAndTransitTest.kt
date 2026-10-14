package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant

class NotesAndTransitTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    @Test
    fun aNoteRequestIsBuiltButNotSent() {
        val notes = OsmNotes(testHttp(), "https://api.openstreetmap.org")
        val req = notes.request(LatLon(52.37341, 4.89369), "The café here closed in September.")
        assertEquals("POST", req.method)
        assertEquals("https://api.openstreetmap.org/api/0.6/notes.json", req.url.toString())
        val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
        val form = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("52.37341", form["lat"])
        assertEquals("4.89369", form["lon"])
        assertEquals("The café here closed in September.\n\nvia Atlas", form["text"])
        for (bad in listOf("   ", "x".repeat(2000))) {
            try {
                notes.request(westerkerk, bad)
                fail("accepted a bad note")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aCreatedNoteIsParsed() = runBlocking {
        Mock().use { m ->
            m.fixture("osm_note.json")
            val note = OsmNotes(testHttp(), m.base).create(LatLon(52.37341, 4.89369), "Loading bay here")
            assertEquals(5568759L, note.id)
            assertEquals("open", note.status)
            assertEquals(Instant.parse("2026-10-12T15:10:37Z"), note.created)
            assertEquals(LatLon(52.37341, 4.89369), note.location)
            assertEquals("opened", note.comments.single().action)
            assertNull(note.comments.single().user)
            assertEquals("https://www.openstreetmap.org/note/5568759", note.url)
            assertEquals("/api/0.6/notes.json", m.take().url.encodedPath)
        }
    }

    @Test
    fun notesInAnArea() = runBlocking {
        Mock().use { m ->
            m.fixture("osm_notes_bbox.json")
            val notes = OsmNotes(testHttp(), m.base).inBox(BBox(4.86, 52.365, 4.91, 52.385), limit = 3)
            assertEquals(3, notes.size)
            assertEquals("Dushi_", notes[1].comments.single().user)
            assertEquals("4.86,52.365,4.91,52.385", m.take().url.queryParameter("bbox"))
        }
    }

    @Test
    fun aRefusedNoteSaysWhy() = runBlocking {
        Mock().use { m ->
            m.enqueue("Too many requests", 429)
            try {
                OsmNotes(testHttp(), m.base).create(westerkerk, "x")
                fail()
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("wait"))
            }
        }
    }

    @Test
    fun transitJourneysFromMotis() = runBlocking {
        Mock().use { m ->
            m.fixture("transitous_plan.json")
            val centraal = LatLon(52.3791, 4.9003)
            val journeys = Transitous(testHttp(), m.base).plan(westerkerk, centraal, Instant.parse("2026-10-14T07:30:00Z"), count = 2)
            val req = m.take()
            assertEquals("/api/v6/plan", req.url.encodedPath)
            assertEquals("52.374500,4.883800", req.url.queryParameter("fromPlace"))
            assertEquals("2026-10-14T07:30:00Z", req.url.queryParameter("time"))
            assertEquals(2, journeys.size)
            val j = journeys.first()
            assertEquals(1140L, j.durationSeconds)
            assertEquals(0, j.transfers)
            assertEquals(listOf("WALK", "TRAM", "WALK"), j.legs.map { it.mode })
            val tram = j.legs[1]
            assertTrue(tram.transit)
            assertEquals("2", tram.line)
            assertEquals("Centraal Station", tram.headsign)
            assertEquals("GVB", tram.agency)
            assertEquals("Amsterdam, Paleisstraat", tram.from.name)
            assertEquals(2, tram.stops.size)
            assertTrue(tram.geometry.size > 10)
            assertTrue(tram.geometry.all { Geo.distance(it, westerkerk) < 3_000 })
            assertEquals(0L, tram.delaySeconds)
            assertEquals("", j.legs.first().from.name)
        }
    }

    @Test
    fun catalogueListsEverySourceWithItsCredit() {
        val ids = Catalogue.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(Catalogue.ALL.all { it.attribution.isNotBlank() && it.licence.isNotBlank() })
        assertTrue(!Catalogue.byId("chargemap")!!.onByDefault)
        assertTrue(!Catalogue.byId("transitous")!!.onByDefault)
        assertTrue(Catalogue.byId("mangrove")!!.onByDefault)
        assertTrue(Catalogue.ALL.none { "google" in it.url.lowercase() })
    }
}
