package app.atlas.ui.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.io.File

/** The saved places store: lists, saving and taking out, recents, layers, hidden brands, and keeping it all on disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedPlacesTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-13T17:30:00Z"), ZoneOffset.UTC)

    private fun place(id: String, name: String, vararg tags: Pair<String, String>) =
        Place(OsmId.parse(id), LatLon(52.3745, 4.8838), mapOf("name" to name) + tags, source = "osm")

    @Test
    fun theDefaultListIsAlwaysThereAndFirst() {
        SavedPlaces.open(null, clock).use { s ->
            val lists = s.lists.value
            assertEquals(1, lists.size)
            assertTrue(lists[0].isDefault)
            assertNull(lists[0].name)
            assertTrue(lists[0].places.isEmpty())
        }
    }

    @Test
    fun aDamagedFileIsPutAsideAndTheScreensStillOpen() {
        val dir = Files.createTempDirectory("saved-broken").toFile()
        val file = File(dir, "saved.db")
        file.writeText("this is not a database, it is a lot of text that SQLite will refuse ".repeat(100))
        SavedPlaces.open(file, clock).use { s ->
            assertEquals(1, s.lists.value.size)
            s.toggle(place("n1", "Kept", "amenity" to "cafe"))
            assertTrue(s.isSaved("n1"))
        }
        assertTrue(File(dir, "saved.db.broken").isFile)
        // And what was kept in the fresh file is there on the next start.
        SavedPlaces.open(file, clock).use { s -> assertTrue(s.isSaved("n1")) }
    }

    @Test
    fun saveToggleAndRemove() {
        SavedPlaces.open(null, clock).use { s ->
            val kalkhoven = place("n1147560517", "Café Kalkhoven", "amenity" to "cafe")
            assertFalse(s.isSaved(kalkhoven.key))
            assertTrue(s.toggle(kalkhoven))
            assertTrue(s.isSaved(kalkhoven.key))
            assertEquals(setOf(SavedPlaces.DEFAULT_LIST), s.listsHolding(kalkhoven.key))
            // Saving again keeps one entry and refreshes the tags.
            s.save(kalkhoven.copy(tags = kalkhoven.tags + ("opening_hours" to "Tu-Sa 09:00-24:00")))
            val saved = s.lists.value[0].places.single()
            assertEquals("Tu-Sa 09:00-24:00", saved.place.tags["opening_hours"])
            assertEquals(Instant.parse("2026-10-13T17:30:00Z"), saved.addedAt)
            assertFalse(s.toggle(kalkhoven))
            assertFalse(s.isSaved(kalkhoven.key))
        }
    }

    @Test
    fun listsKeepTheirOrderColoursAndPlaces() {
        SavedPlaces.open(null, clock).use { s ->
            val rainy = s.createList("  Rainy day  ")
            val coffee = s.createList("Coffee crawl")
            val names = s.lists.value.map { it.name }
            assertEquals(listOf(null, "Rainy day", "Coffee crawl"), names)
            // Each new notebook takes the next colour.
            assertEquals(SavedPlaces.COLOURS[1], s.lists.value[1].colour)
            assertEquals(SavedPlaces.COLOURS[2], s.lists.value[2].colour)
            val a = place("n1", "A")
            val b = place("n2", "B")
            s.save(b, rainy)
            s.save(a, rainy)
            s.save(a, coffee)
            assertEquals(listOf("B", "A"), s.lists.value.first { it.id == rainy }.places.map { it.place.name() })
            assertEquals(setOf(rainy, coffee), s.listsHolding(a.key))
            s.renameList(rainy, "Rain")
            assertEquals("Rain", s.lists.value.first { it.id == rainy }.name)
            s.remove(a.key, rainy)
            assertEquals(listOf("B"), s.lists.value.first { it.id == rainy }.places.map { it.place.name() })
            s.deleteList(rainy)
            assertTrue(s.lists.value.none { it.id == rainy })
            // The default list is emptied, never deleted.
            s.save(a)
            s.deleteList(SavedPlaces.DEFAULT_LIST)
            assertTrue(s.lists.value.first().isDefault)
            assertTrue(s.lists.value.first().places.isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aListNeedsAName() {
        SavedPlaces.open(null, clock).use { it.createList("   ") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun savingToAMissingListFails() {
        SavedPlaces.open(null, clock).use { it.save(place("n1", "A"), 42) }
    }

    @Test
    fun recentsAreNewestFirstWithoutRepeatsAndCapped() {
        SavedPlaces.open(null, clock).use { s ->
            s.addRecent("apotheek")
            s.addRecent("52.3745, 4.8838")
            s.addRecent("APOTHEEK")
            assertEquals(listOf("APOTHEEK", "52.3745, 4.8838"), s.recents.value.map { it.text })
            val kalkhoven = place("n1147560517", "Café Kalkhoven")
            s.addRecent("kalk", kalkhoven)
            s.addRecent("kalkhoven", kalkhoven)
            assertEquals("kalkhoven", s.recents.value.first().text)
            assertEquals(kalkhoven.key, s.recents.value.first().place?.key)
            assertEquals(1, s.recents.value.count { it.place?.key == kalkhoven.key })
            s.addRecent("   ")
            assertEquals(3, s.recents.value.size)
            repeat(30) { s.addRecent("q$it") }
            assertEquals(20, s.recents.value.size)
            s.clearRecents()
            assertTrue(s.recents.value.isEmpty())
        }
    }

    @Test
    fun layersAndHiddenBrands() {
        SavedPlaces.open(null, clock).use { s ->
            s.keepLayer("cafe near bicycle rental 200m")
            s.keepLayer("cafe near bicycle rental 200m", alert = true)
            assertEquals(1, s.layers.value.size)
            assertTrue(s.layers.value.single().alert)
            s.removeLayer("cafe near bicycle rental 200m")
            assertTrue(s.layers.value.isEmpty())
            s.hideBrand("albertheijn-1cf55d", true)
            s.hideBrand("albertheijn-1cf55d", true)
            assertEquals(setOf("albertheijn-1cf55d"), s.hiddenBrands.value)
            s.hideBrand("albertheijn-1cf55d", false)
            assertTrue(s.hiddenBrands.value.isEmpty())
        }
    }

    @Test
    fun everythingIsKeptOnDisk() {
        val file = File(Files.createTempDirectory("saved").toFile(), "saved/saved.db")
        val odd = place("w99205257", "Westerkerk", "name:zh" to "西教堂", "note" to "quote \" and \\ backslash")
        SavedPlaces.open(file, clock).use { s ->
            val l = s.createList("Kerken")
            s.save(odd, l)
            s.addRecent("westerkerk", odd)
            s.keepLayer("amenity=place_of_worship")
            s.hideBrand("hema-6b3b3c", true)
        }
        SavedPlaces.open(file, clock).use { s ->
            val l = s.lists.value.first { it.name == "Kerken" }
            assertEquals(odd.tags, l.places.single().place.tags)
            assertEquals(odd.id, l.places.single().place.id)
            assertEquals("westerkerk", s.recents.value.single().text)
            assertEquals(listOf("amenity=place_of_worship"), s.layers.value.map { it.text })
            assertEquals(setOf("hema-6b3b3c"), s.hiddenBrands.value)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun nothingIsWrittenAfterClose() {
        val s = SavedPlaces.open(null, clock)
        s.close()
        s.close()
        s.addRecent("late")
    }
}
