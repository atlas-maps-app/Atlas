package app.atlas.ui.local

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.search.Outlines
import app.atlas.search.PlaceStore
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A park's own page as its kinds open it, on the test phone at the Westerkerk: a national park's wild sign with its rules,
 * trails, season and notes; a pocket park outlined offline from the bundled pack; a park drawn offline from its country's
 * place store, its paths with it; and the place page's way to the plan.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ParkPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(): Services = testServices(places = true).apply { keepFixtureForecast() }

    private fun show(dest: Dest, services: Services): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav) }
        compose.settle(30_000)
        return nav
    }

    private fun waitFor(text: String, timeout: Long = 20_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String): File {
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        compose.settle()
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    @Test
    fun aNationalParksWildSign() {
        val s = phone()
        LocalFixtures.answerWithStonewood(s)
        val park = LocalFixtures.stonewood
        s.remember(park)
        show(Dest.Parkland.of(park), s)
        // its own name under its shield, what it is and how big, the visitor centre first, the rules and trails as mapped
        waitFor("STONEWOOD")
        waitFor("NATIONAL PARK · ")
        waitFor("km²")
        waitFor("Stonewood Visitor Centre")
        waitFor("dogs on lead")
        waitFor("no drones")
        waitFor("fires in rings")
        waitFor("3 trails")
        waitFor("Stonewood Loop · 12.5 km")
        capture("local_park_national")
        // its season and its notes: the birds first in the wilds, then the old oak, never the landscape's every tree
        compose.onNodeWithText("Outline, paths, trees and rules: OpenStreetMap").performScrollTo()
        waitFor("Birds, right now")
        waitFor("Stonewood Fen")
        waitFor("Reedbed Hide")
        waitFor("Stonewood Oak")
        capture("local_park_national_notes")
    }

    @Test
    fun theWildNotesLookFurtherWhenNothingWildIsNear() {
        // nothing wild within 2.5 km of the Westerkerk; Stonewood, some 11 km out, is found by the second, wider look
        val s = phone()
        LocalFixtures.answerWithStonewood(s)
        show(Dest.Nature("wild"), s)
        waitFor("Stonewood National Park")
        capture("local_nature_wild_far")
    }

    @Test
    fun aPocketParkIsDrawnFromTheBundledPack() {
        // no outline from the test: the plan comes from the Westerkerk's bundled pack, offline
        val s = phone()
        LocalFixtures.answerWith(s)
        val park = Place(
            OsmId(OsmType.WAY, 1423270125), LatLon(52.37113, 4.89047), mapOf("leisure" to "park", "name" to "Postzegelpark"),
            BBox(4.890113, 52.3706534, 4.8908224, 52.371604),
        )
        s.remember(park)
        show(Dest.Parkland.of(park), s)
        waitFor("POSTZEGELPARK")
        // an outline, not the box: its size is on the sign
        waitFor(" ha")
        capture("local_park_offline")
    }

    /** A line of (lat, lon) pairs in 1e-7 degree, as a place store keeps them. */
    private fun e7(line: List<LatLon>): IntArray = line.flatMap { listOf(Math.round(it.lat * 1e7).toInt(), Math.round(it.lon * 1e7).toInt()) }.toIntArray()

    @Test
    fun aParkIsDrawnFromItsCountrysStoreOffline() {
        // a country kept as its tiles, the phone offline: no element pack holds the Westerpark, so without a store it is
        // drawn as its box
        val s = phone()
        val park = LocalFixtures.westerpark
        LocalFixtures.answerWithWesterpark(s)
        s.local.parkShape = null
        s.local.onlineOverride = false
        s.remember(park)
        assertFalse(runBlocking { Parkland.load(s, park) }.shape.outlined)
        // its place store keeps its outline and the paths in it, as the packer writes them
        val drawn = Parkland.shapeOf(LocalFixtures.westerparkElements, park.id!!)!!
        val rings = drawn.rings.map { r -> e7(if (r.first() == r.last()) r else r + r.first()) }
        val holes = drawn.holes.ifEmpty { rings.map { false } }
        val store = PlaceStore.inMemory()
        store.writer().use { w ->
            assertTrue(w.putOutline(park.id!!, rings, holes))
            w.putPaths(park.id!!, drawn.paths.map(::e7), Outlines.toleranceOf(rings))
            w.finish()
        }
        s.placeStores.attach(store)
        // every path the network's plan draws, simplified within a metre
        val shape = runBlocking { Parkland.load(s, park) }.shape
        assertTrue(shape.outlined)
        assertEquals(drawn.paths.size, shape.paths.size)
        show(Dest.Parkland.of(park), s)
        waitFor("WESTERPARK")
        // an outline, not the box: its size is on the sign, as the outline from the network has it
        val online = ParkShape.areaOf(drawn.rings, holes)
        waitFor(Parkland.areaText(online))
        capture("local_park_store")
        // a store from before paths were kept: still the outline offline, without them
        s.placeStores.detach(store)
        val before = PlaceStore.inMemory()
        before.writer().use { w ->
            w.putOutline(park.id!!, rings, holes)
            w.finish()
        }
        s.placeStores.attach(before)
        val outlineOnly = runBlocking { Parkland.load(s, park) }.shape
        assertTrue(outlineOnly.outlined)
        assertTrue(outlineOnly.paths.isEmpty())
        assertEquals(online, outlineOnly.areaM2!!, online * 0.02)
    }

    @Test
    fun aParksPlacePageLeadsToItsPlanAndBack() {
        val s = phone()
        LocalFixtures.answerWithWesterpark(s)
        val park = LocalFixtures.westerpark
        s.remember(park)
        val nav = show(Dest.Place.of(park), s)
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Park plan")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Park plan").performClick()
        compose.waitUntil(15_000) { nav.current is Dest.Parkland }
        assertEquals(Dest.Parkland.of(park), nav.current)
        // the plan's details lead on to the place page
        waitFor("WESTERPARK")
        compose.onNodeWithContentDescription("Details").performClick()
        compose.waitUntil(15_000) { nav.current is Dest.Place }
    }
}
