package app.atlas.ui.mine

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.services.RegionLayer
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.testServices
import app.atlas.ui.mine.offline.PickerModel
import app.atlas.ui.mine.offline.RegionScheduler
import kotlinx.coroutines.flow.first
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

/** What the Welcome and the region picker do when they are used: through the real navigation, with downloads queued by a fake. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MineFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private class Queue : RegionScheduler {
        val queued = ArrayList<Triple<String, Boolean, Boolean>>()
        override fun schedule(id: String, wifiOnly: Boolean, update: Boolean) { queued += Triple(id, wifiOnly, update) }
        override fun cancel(id: String) = Unit
    }

    private val queue = Queue()

    private fun phone(): Services = testServices(places = true).apply {
        keepFixtureForecast()
        mine.useDeps(MineFixtures.packDeps(this, clock.instant()))
        mine.useScheduler(queue)
    }

    private fun show(dest: Dest, s: Services): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(s, nav, dark = false) }
        compose.settle(30_000)
        return nav
    }

    private fun estimated() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("working it out…").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    /** Waits for a setting that is written in the background (DataStore writes on its own thread). */
    private fun eventually(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; compose.waitForIdle(); Thread.sleep(50) }
        throw AssertionError("never: $what")
    }

    @Test
    fun theWelcomeTakesTheAreaAroundYouThenAsksForVoiceAndUnitsAndEndsOnTheMap() {
        val s = phone()
        val nav = show(Dest.Onboarding(1), s)
        estimated()
        compose.onNodeWithText("Download & continue").performClick()
        compose.settle(10_000)
        val region = s.mine.offline.details.value.single()
        assertEquals(listOf(Triple(region.id, true, false)), queue.queued)
        assertTrue("12 km around where you are", region.meta.bbox.let { b -> LatLon(52.3733, 4.8840) in b && PickerModel.sides(b).first in 23.0..25.0 })
        assertEquals(setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.ROUTING, RegionLayer.VOICE), region.meta.wanted)
        compose.onNodeWithText("How should Atlas speak?").assertExistsText()
        compose.onNodeWithText("Imperial").performClick()
        compose.onNodeWithText("Deutsch").performClick()
        eventually("imperial units") { runBlocking { s.settings.units.first() } == app.atlas.services.Units.IMPERIAL }
        eventually("Fahrenheit with them") { runBlocking { s.settings.flow(MineKeys.TEMP_F, false).first() } }
        eventually("German voice kept") { "de" in runBlocking { s.settings.flow(MineKeys.VOICE_LANGS, emptySet()).first() } }
        assertFalse(runBlocking { s.settings.flow(SettingsKeys.ONBOARDED, false).first() })
        compose.onNodeWithText("Start exploring").performClick()
        eventually("welcome seen") { runBlocking { s.settings.flow(SettingsKeys.ONBOARDED, false).first() } }
        assertTrue("back on the map", nav.current is Dest.Home)
    }

    @Test
    fun everyStepCanBeSkippedAndNothingIsDownloaded() {
        val s = phone()
        val nav = show(Dest.Onboarding(0), s)
        compose.onNodeWithText("Later").performClick()          // location
        compose.settle(10_000)
        estimated()
        compose.onNodeWithText("Later").performClick()          // the area
        compose.settle(10_000)
        compose.onNodeWithText("Start exploring").performClick()
        eventually("welcome seen") { runBlocking { s.settings.flow(SettingsKeys.ONBOARDED, false).first() } }
        assertTrue(queue.queued.isEmpty())
        assertTrue(s.mine.offline.details.value.isEmpty())
        assertTrue(nav.current is Dest.Home)
    }

    @Test
    fun anAreaWithoutRoutesIsQueuedWithoutThem() {
        val s = phone()
        val nav = show(Dest.RegionPicker(Services.WESTERKERK.lat, Services.WESTERKERK.lon, "area"), s)
        estimated()
        compose.onNodeWithText("Routes").performClick()
        estimated()
        compose.onNodeWithText("Download", substring = true).performClick()
        compose.settle(10_000)
        val region = s.mine.offline.details.value.single()
        assertEquals(setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.VOICE), region.meta.wanted)
        assertEquals(1, queue.queued.size)
        assertTrue(nav.current is Dest.Home)
    }

    @Test
    fun aDrawnLoopIsTheAreaThatIsQueued() {
        val s = phone()
        show(Dest.RegionPicker(Services.WESTERKERK.lat, Services.WESTERKERK.lon, "area"), s)
        estimated()
        compose.onNodeWithContentDescription("Map of the area to take offline").performTouchInput {
            val c = center
            val r = width / 6f
            down(c + androidx.compose.ui.geometry.Offset(-r, -r))
            for (p in listOf(androidx.compose.ui.geometry.Offset(r, -r), androidx.compose.ui.geometry.Offset(r, r), androidx.compose.ui.geometry.Offset(-r, r), androidx.compose.ui.geometry.Offset(-r, -r))) moveTo(c + p)
            up()
        }
        compose.onNodeWithText("Clear").assertExistsText()
        estimated()
        compose.onNodeWithText("Download", substring = true).performClick()
        compose.settle(10_000)
        val box = s.mine.offline.details.value.single().meta.bbox
        val (w, h) = PickerModel.sides(box)
        assertTrue("$w x $h km, a loop a third of the view wide", w in 10.0..40.0 && h in 8.0..40.0)
        assertTrue(LatLon(52.3733, 4.8840) in box)
    }

    @Test
    fun aRouteIsQueuedAsOneRegionPerBoxAlongIt() {
        val s = phone()
        compose.runOnUiThread { MineFixtures.startTrip(s, androidx.test.core.app.ApplicationProvider.getApplicationContext()) }
        show(Dest.RegionPicker(mode = "route"), s)
        estimated()
        val expected = PickerModel.corridorBoxes(MineFixtures.trip().points, 10_000.0).size
        assertTrue(expected > 1)
        compose.onNodeWithText("Download", substring = true).performClick()
        compose.settle(10_000)
        val names = s.mine.offline.details.value.map { it.name }
        assertEquals(expected, names.size)
        assertTrue(names.all { it.startsWith("Route to Rotterdam part ") })
        assertEquals(expected, queue.queued.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun aCountryIsTheWholePack() {
        val s = phone()
        show(Dest.RegionPicker(mode = "country"), s)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Netherlands").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Netherlands").performClick()
        estimated()
        compose.onNodeWithText("Download", substring = true).performClick()
        compose.settle(10_000)
        val region = s.mine.offline.details.value.single()
        assertEquals("netherlands", region.id)
        assertEquals(MineFixtures.packs().first { it.id == "netherlands" }.bbox, region.meta.bbox)
    }

    @Test
    fun withNoRouteThePickerSaysSoAndOffersNoDownload() {
        val s = phone()
        show(Dest.RegionPicker(mode = "route"), s)
        compose.onNodeWithText("No route to follow").assertExistsText()
        assertTrue(queue.queued.isEmpty())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsText() { assertExists() }
}
