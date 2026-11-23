package app.atlas.ui.mine

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import app.atlas.R
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.providers.Overpass
import app.atlas.providers.Wikipedia
import app.atlas.services.SettingsKeys
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Privacy screen says what was asked of each source since Atlas started, from the app's own count
 * ([Services.asked]): how often and when last, what was held back, and hosts beyond a source's list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class PrivacyAskedTest {
    @get:Rule
    val compose = createComposeRule()

    private fun asked(): Services = testServices().apply {
        runBlocking { settings.set(SettingsKeys.PROVIDERS_OFF, setOf(Wikipedia.ID)) }
        // as the root's interceptors count them: Overpass twice, Panoramax once at an instance beyond its list, Wikipedia held back
        repeat(2) { asked.sent(Overpass.INFO.id) }
        asked.sent(SourcesModel.PANORAMAX.id, beyond = "nl.panoramax.xyz")
        asked.refused(Wikipedia.ID)
    }

    private fun show(services: Services) {
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home(), Dest.Privacy))) }
        compose.settle(30_000)
    }

    private fun shown(text: String) = assertTrue(text, compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    /** A card's label, which [CardLabel] draws in capitals. */
    private fun label(text: String) = shown(text.uppercase())

    private fun at(services: Services) = app.atlas.ui.go.GoFormat.clock(services.clock.instant(), services.clock.zone)

    @Test
    fun eachRowSaysWhatWasAsked() {
        val s = asked()
        show(s)
        val t = at(s)
        shown("asked 2 times, last at $t")
        shown("asked 1 time, last at $t · also at nl.panoramax.xyz")
        shown("not asked")
        label("Not asked now")
        shown("held back 1 time, last at $t")
        assertEquals("the screen reads the app's own count", 2L, s.asked.of(Overpass.INFO.id).sent)
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        val f = File(shotsDir, "mine_privacy_asked.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
    }

    /**
     * The lower section, pictured: Wikipedia asked three times and then switched off, held back once; Commons switched
     * off and never asked (not listed); Panoramax asked at five instances beyond its list (three named, "and 2 more").
     */
    @Test
    fun theLowerSectionAsItReads() {
        val s = testServices().apply {
            runBlocking { settings.set(SettingsKeys.PROVIDERS_OFF, setOf(Wikipedia.ID, "commons")) }
            repeat(2) { asked.sent(Overpass.INFO.id) }
            repeat(3) { asked.sent(Wikipedia.ID) }
            asked.refused(Wikipedia.ID)
            for (h in listOf("nl.panoramax.xyz", "panoramax.ign.fr", "panoramax.openstreetmap.fr", "api.panoramax.xyz", "panoramax.mapcomplete.org")) {
                asked.sent(SourcesModel.PANORAMAX.id, beyond = h)
            }
        }
        show(s)
        val t = at(s)
        shown("Counted since Atlas started, at $t. Nothing of it is kept.")
        shown("asked 3 times, last at $t · held back 1 time, last at $t")
        shown("also at api.panoramax.xyz, nl.panoramax.xyz, panoramax.ign.fr and 2 more")
        // the lower section's last line brought into view, so its heading and rows stand above it
        compose.onNodeWithText("asked 3 times, last at $t · held back 1 time, last at $t").performScrollTo()
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        val f = File(shotsDir, "mine_privacy_lower.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
    }

    @Test
    @Config(qualifiers = "fr-w412dp-h915dp-xhdpi")
    fun inFrench() {
        val s = asked()
        show(s)
        val t = at(s)
        shown("demandé 2 fois, la dernière à $t")
        label("Pas demandé maintenant")
    }

    @Test
    fun everyLanguageSaysItWithItsCountAndTime() {
        val res = RuntimeEnvironment.getApplication().resources
        for (lang in listOf("en", "de", "fr", "nl")) {
            val c = android.content.res.Configuration(res.configuration).apply { setLocale(java.util.Locale.forLanguageTag(lang)) }
            val r = RuntimeEnvironment.getApplication().createConfigurationContext(c).resources
            for (n in listOf(1, 2, 12)) {
                val asked = r.getQuantityString(R.plurals.mine_asked, n, n, "14:02")
                assertTrue("$lang: $asked", "$n" in asked && "14:02" in asked)
                val held = r.getQuantityString(R.plurals.mine_asked_refused, n, n, "14:02")
                assertTrue("$lang: $held", "$n" in held && "14:02" in held)
            }
            assertTrue(lang, "nl.panoramax.xyz" in r.getString(R.string.mine_asked_hosts, "nl.panoramax.xyz"))
        }
    }
}
