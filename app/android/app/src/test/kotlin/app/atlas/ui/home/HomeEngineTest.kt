package app.atlas.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.atlas.Services
import app.atlas.mapview.MapEngine
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.render.live.LiveEngine
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Map home drawn by the 1:1 engine from the app's own region data, at the boards' size (412 × 915 dp at density 2):
 * CI's demo (the bundled Westerkerk, offline), and, where the canal ring's pack is on this machine, the Main board's
 * view lit by the kept forecast at Thursday 16:00.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomeEngineTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = File(shotsDir, "engine").also { it.mkdirs() }

    @After
    fun unwire() {
        MapEngine.regions = null
        LiveEngine.forget()
    }

    /** Waits until the map says its picture is the engine's frame. */
    private fun awaitMap(timeoutMs: Long = 300_000): String {
        var state = ""
        compose.waitUntil(timeoutMs) {
            val n = compose.onAllNodes(hasContentDescription("Map")).fetchSemanticsNodes().firstOrNull()
            state = n?.config?.getOrNull(SemanticsProperties.StateDescription) ?: ""
            state == "ready" || state.startsWith("no map data")
        }
        compose.settleScreen()
        return state
    }

    private fun wired(services: Services): Services = services.apply { mapData.wire() }

    @Test
    fun ciDemoDrawsTheBundledWesterkerkAtOnce() {
        val services = wired(testServices(clock = HomeBoardTest.THURSDAY).apply { keepFixtureForecast(); ciDemo = true })
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        assertEquals("ready", awaitMap())
        assertEquals(listOf("amsterdam"), services.mapData.packs.local().map { it.id })
        val shot = File(out, "home_ci_westerkerk.png")
        compose.onRoot().captureRoboImage(shot.absolutePath)
        assertTrue(shot.length() > 100_000)
    }

    @Test
    fun theHomeOnTheMainBoardsViewFromTheCanalRingsPack() {
        val repo = File(System.getProperty("atlas.repo") ?: "../..")
        val grachten = File(repo, "cache/packs/regions/grachten.atlas")
        assumeTrue("the canal ring's pack is on the owner's machine only", grachten.isFile)
        val services = testServices(places = true, clock = HomeBoardTest.THURSDAY).apply { keepFixtureForecast() }
        services.mapData.packs.install("grachten") { grachten.inputStream() }
        wired(services)
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        assertEquals("ready", awaitMap())
        val shot = File(out, "home_board_engine.png")
        compose.onRoot().captureRoboImage(shot.absolutePath)
        assertTrue(shot.length() > 100_000)
        if (BoardShots.reference.isFile) println(BoardShots.compare(BoardShots.read(BoardShots.reference), BoardShots.read(shot), out).joinToString("\n"))
    }
}
