package app.atlas.ui.search

import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.search.HoursFrom
import app.atlas.search.MemoryPlaces
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

/**
 * A place's own page for a place that maps no hours (the owner, 6 November, of two Birkenstock stores, one mapped closed
 * and one with no hours: "if the other branch is closed the other one is probably closed too"): it borrows its brand's
 * branches' near, else its kind's around it, as the search's rows do, and says "≈" and whose they are. On the Amsterdam
 * extract at the Westerkerk on a Tuesday evening, offline; the page to `find_place_birkenstock*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class BorrowedHoursTest {
    @get:Rule
    val compose = createComposeRule()

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs the main looper until [done] or the time is up, letting background threads work meanwhile. */
    private fun until(timeoutMs: Long = 15_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun aBranchThatMapsNoHoursTakesItsSiblings() {
        val vm = PlaceViewModel(testServices(places = true), null, HARTENSTRAAT, null, null, "en")
        until { vm.state.value.guess != null }
        val st = vm.state.value
        val guess = st.guess!!
        assertEquals(HoursFrom.BRAND, guess.from)
        assertEquals(1, guess.places)
        assertEquals("Raadhuisstraat 43-H", guess.branch)
        assertSame(guess.hours, st.hours)
        assertEquals("Mo 12:00-17:30; Tu-Fr 11:00-17:30; Sa 11:00-17:00", guess.hours.raw)
        // the place itself still maps none
        assertNull(st.place!!.tags["opening_hours"])
        // Tuesday 19:30: closed as Raadhuisstraat 43-H is, opening on Wednesday at 11:00
        assertEquals(false, st.status!!.isOpen)
        assertEquals(LocalDateTime.of(2026, 10, 14, 11, 0), st.status!!.changesAt)

        // the branch that maps its hours keeps its own
        val own = PlaceViewModel(testServices(places = true), null, RAADHUISSTRAAT, null, null, "en")
        until { !own.state.value.looking }
        idle(300)
        assertNull(own.state.value.guess)
        assertEquals("Mo 12:00-17:30; Tu-Fr 11:00-17:30; Sa 11:00-17:00", own.state.value.hours?.raw)
    }

    @Test
    fun theNearestBranchesThatMapHoursLendThem() {
        fun starbucks(id: Long, north: Double, hours: String?) = place(
            id, north, *listOfNotNull("amenity" to "cafe", "name" to "Starbucks", "brand" to "Starbucks", "brand:wikidata" to "Q37158", hours?.let { "opening_hours" to it }).toTypedArray(),
        )
        val bare = starbucks(1, 0.0, null)
        // nearer branches that map none are passed over; the nearest nine that map hours lend theirs, seven of them alike
        val unmapped = (1..5).map { starbucks(50L + it, it * 50.0, null) }
        val nearest = (1..7).map { starbucks(10L + it, it * 100.0, "Mo-Su 07:00-21:00") } + (8..9).map { starbucks(10L + it, it * 100.0, "Mo-Su 08:00-20:00") }
        val farther = (1..3).map { starbucks(30L + it, 5_000.0 + it * 100.0, "Mo-Su 06:00-23:00") }
        val beyond = starbucks(40, 30_000.0, "24/7")
        val guess = BorrowedHours.of(MemoryPlaces(listOf(bare) + unmapped + nearest + farther + beyond), bare, "en")!!
        assertEquals(HoursFrom.BRAND, guess.from)
        assertEquals(9, guess.places)
        assertEquals(7, guess.keep)
        assertNull(guess.branch)
        assertEquals("Mo-Su 07:00-21:00", guess.hours.raw)

        // one branch lends its hours, and it is named; one 30 km away lends nothing
        val one = starbucks(60, 300.0, "Mo-Fr 08:00-18:00").let { Place(it.id, it.location, it.tags + mapOf("addr:street" to "Leidsestraat", "addr:housenumber" to "1")) }
        val lent = BorrowedHours.of(MemoryPlaces(listOf(bare, one, beyond)), bare, "en")!!
        assertEquals(1, lent.places)
        assertEquals(1, lent.keep)
        assertEquals("Leidsestraat 1", lent.branch)
        assertNull(BorrowedHours.of(MemoryPlaces(listOf(bare, beyond)), bare, "en"))
    }

    @Test
    fun withoutBranchesItsKindAroundLendsItsAndAParkBorrowsNone() {
        fun bakery(id: Long, north: Double, hours: String?) =
            place(id, north, *listOfNotNull("shop" to "bakery", "name" to "Bakker $id", hours?.let { "opening_hours" to it }).toTypedArray())
        val bakeries = listOf(
            bakery(1, 100.0, "Mo-Sa 07:00-18:00"), bakery(2, 200.0, "Mo-Sa 07:30-18:00"),
            bakery(3, 300.0, "Mo-Sa 07:00-17:30"), bakery(4, 400.0, "Tu-Sa 08:00-18:00"),
        )
        val bare = bakery(10, 250.0, null)
        val guess = BorrowedHours.of(MemoryPlaces(bakeries + bare), bare, "en")!!
        assertEquals(HoursFrom.KIND, guess.from)
        assertEquals(4, guess.places)
        // the lower medians, day by day: as the first bakery keeps them
        assertEquals("Mo-Sa 07:00-18:00", guess.hours.raw)
        assertEquals(1, guess.keep)
        // hours of its own that do not read are no gap to fill
        val unread = bakery(11, 250.0, "when the bread is out")
        assertNull(BorrowedHours.of(MemoryPlaces(bakeries + unread), unread, "en"))
        // a park is open without hours
        val parks = (1..4).map { place(20L + it, it * 40.0, "leisure" to "park", "name" to "Park $it", "opening_hours" to "Mo-Su 08:00-20:00") }
        val park = place(30, 90.0, "leisure" to "park", "name" to "Park zonder uren")
        assertNull(BorrowedHours.of(MemoryPlaces(parks + park), park, "en"))
    }

    /** Hartenstraat 16-H's page: "≈" and whose hours they are under its dial; its week leads on to the real ones. */
    @Test
    fun aBranchsPageSaysWhoseHoursItBorrows() {
        val nav = BackStackNavigator(listOf(Dest.Home(), Dest.Place(52.3723666, 4.8860062, HARTENSTRAAT, "Birkenstock")))
        compose.setContent { AtlasRoot(testServices(places = true), nav) }
        compose.settle(15_000)
        waitFor("as at Raadhuisstraat 43-H")
        compose.onNodeWithText("≈ opens Wed 11:00", substring = true).assertExists()
        capture("place_birkenstock")
        compose.onNodeWithText("≈ opens Wed 11:00", substring = true).performClick()
        waitFor("≈ Opening hours")
        compose.onNodeWithText("Correct the opening hours").assertExists()
        capture("place_birkenstock_week")
        compose.onNodeWithText("Correct the opening hours").performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.WhatsHere)
    }

    /** The same in Dutch, whose words are the longest. */
    @Test
    @Config(qualifiers = "nl-w412dp-h915dp-xhdpi")
    fun aBranchsPageSaysWhoseHoursItBorrowsInDutch() {
        val nav = BackStackNavigator(listOf(Dest.Home(), Dest.Place(52.3723666, 4.8860062, HARTENSTRAAT, "Birkenstock")))
        compose.setContent { AtlasRoot(testServices(places = true), nav) }
        compose.settle(15_000)
        waitFor("zoals Raadhuisstraat 43-H")
        capture("place_birkenstock_nl")
    }

    private fun waitFor(text: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Captures until three frames in a row come out the same (the map has drawn), then writes `find_<name>.png`. */
    private fun capture(name: String): File {
        val end = System.currentTimeMillis() + 30_000
        var last: android.graphics.Bitmap? = null
        var same = 0
        while (System.currentTimeMillis() < end && same < 3) {
            compose.waitForIdle()
            val now = compose.onRoot().captureToImage().asAndroidBitmap()
            same = if (last != null && now.sameAs(last)) same + 1 else 0
            last = now
            Thread.sleep(120)
        }
        val f = File(shotsDir, "find_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun place(id: Long, north: Double, vararg tags: Pair<String, String>) =
        Place(OsmId.parse("n$id"), LatLon(HERE.lat + north / 111_320.0, HERE.lon), mapOf(*tags))

    private companion object {
        /** Birkenstock at Hartenstraat 16-H, which maps no hours, and at Raadhuisstraat 43-H, which does. */
        const val HARTENSTRAAT = "n2721938467"
        const val RAADHUISSTRAAT = "n6488693366"
        val HERE = LatLon(52.37, 4.88)
    }
}
