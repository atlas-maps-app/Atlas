package app.atlas.ui.local

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every screen of the local package, through the real navigation, on the test phone at the Westerkerk: one PNG each to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class LocalScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(places: Boolean = true): Services = testServices(places = places).apply { keepFixtureForecast() }

    private fun show(dest: Dest, services: Services, dark: Boolean = false): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = dark) }
        compose.settle(30_000)
        return nav
    }

    private fun capture(name: String): File {
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        compose.settle()
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    @Test
    fun season() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Season, s)
        capture("local_season")
    }

    @Test
    fun soon() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Soon, s)
        capture("local_soon")
    }

    @Test
    fun sky() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Sky, s)
        capture("local_sky")
    }

    @Test
    fun moments() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Moments, s)
        capture("local_moments")
    }

    @Test
    fun local() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Local(), s)
        capture("local_cabinet")
    }

    @Test
    fun localDrawerOpen() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Local("seasons"), s)
        capture("local_cabinet_open")
    }

    @Test
    fun year() {
        show(Dest.Year, phone())
        capture("local_year")
    }

    @Test
    fun collect() {
        val s = phone()
        s.local.waste.import(javaClass.getResourceAsStream("/local/collect_sample.ics")!!.bufferedReader().readText(), "collect_sample.ics")
        show(Dest.Collect, s)
        capture("local_collect")
    }

    @Test
    fun collectEmpty() {
        show(Dest.Collect, phone())
        capture("local_collect_empty")
    }

    @Test
    fun namedAfter() {
        val s = phone()
        s.local.onlineOverride = true
        s.local.etymology = LocalFixtures.etymology
        s.local.finder.fake = { _, _, _ -> listOf(LocalFixtures.rembrandtplein) }
        show(Dest.NamedAfter(52.36605, 4.89664, name = "Rembrandtplein"), s)
        capture("local_namedafter")
    }

    @Test
    fun buildingStory() {
        val s = phone()
        s.local.onlineOverride = true
        s.local.etymology = LocalFixtures.westerkerk
        show(Dest.BuildingStory(52.37452, 4.88377, "w99205257"), s)
        capture("local_building")
    }

    @Test
    fun picnic() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Picnic(), s)
        capture("local_picnic")
    }

    @Test
    fun swim() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Swim(), s)
        capture("local_swim")
    }

    @Test
    fun swimSea() {
        val s = phone()
        LocalFixtures.answerWith(s)
        s.local.marineOverride = { LocalFixtures.tideAt(s.clock.instant()) }
        show(Dest.Swim("sea"), s)
        capture("local_swim_sea")
    }

    @Test
    fun relax() {
        val s = phone()
        LocalFixtures.answerWith(s)
        s.local.soundMeter = object : SoundMeter { override suspend fun measure(millis: Long) = 38.0 }
        show(Dest.Relax(), s)
        compose.onNodeWithContentDescription("Sound meter", substring = true).performClick()
        compose.waitForIdle()
        capture("local_relax")
    }

    @Test
    fun nature() {
        val s = phone()
        // The place store of the canal ring: trees, canals, a garden or two.
        show(Dest.Nature(), s)
        capture("local_nature")
    }

    @Test
    fun natureGardens() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Nature("gardens"), s)
        capture("local_nature_gardens")
    }

    @Test
    fun time() {
        val s = phone()
        LocalFixtures.answerWithWesterkerk(s)
        show(Dest.Time(lat = 52.3745, lon = 4.8840), s)
        capture("local_time")
    }

    @Test
    fun look() {
        val s = phone()
        LocalFixtures.answerWithWesterkerk(s)
        s.local.onlineOverride = true
        s.local.photos = LocalFixtures.noStreetPhoto
        show(Dest.Look(lat = 52.37495, lon = 4.88356, heading = 315.0), s)
        capture("local_look")
    }

    @Test
    fun lookPhoto() {
        val s = phone()
        LocalFixtures.answerWithWesterkerk(s)
        s.local.onlineOverride = true
        s.local.photos = LocalFixtures.streetPhoto
        show(Dest.Look(lat = 52.3745, lon = 4.8840, heading = 0.0), s)
        compose.onNodeWithText("photo · 2023").performClick()
        capture("local_look_photo")
    }

    @Test
    fun peaks() {
        val s = phone()
        LocalFixtures.answerWithInnsbruck(s)
        s.local.onlineOverride = true
        show(Dest.Peaks(lat = 47.2692, lon = 11.4041), s)
        capture("local_peaks")
    }

    @Test
    fun darkLocal() {
        val s = phone()
        LocalFixtures.answerWith(s)
        show(Dest.Local(), s, dark = true)
        capture("local_dark")
    }

    @Test
    fun parkland() {
        val s = phone()
        LocalFixtures.answerWithWesterpark(s)
        show(Dest.Parkland(lat = 52.3873, lon = 4.8728), s)
        capture("local_parkland")
    }

    @Test
    fun globe() {
        show(Dest.Globe, phone())
        capture("local_globe")
    }

    @Test
    fun moon() {
        show(Dest.Moon, phone())
        capture("local_moon")
    }

    @Test
    fun planets() {
        show(Dest.Planets, phone())
        capture("local_planets")
    }

    @Test
    fun galaxy() {
        show(Dest.Galaxy, phone())
        capture("local_galaxy")
    }
}
