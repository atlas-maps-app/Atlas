package app.atlas.ui.mine

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import app.atlas.R
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.providers.Overpass
import app.atlas.providers.Wikipedia
import app.atlas.services.SettingsKeys
import app.atlas.testing.settle
import app.atlas.testing.testServices
import app.atlas.ui.go.GoFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The Privacy screen's count ([Services.asked]): every source asked or held back since Atlas started is shown, the
 * ones not asked now under their own heading; each row's line says what holds, in one order; the head says once since
 * when it counts, and that nothing of it is kept; a time not of today carries its date; and the screen reads the count
 * again when it comes back to the front.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class PrivacyCountTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A clock the test moves: the app's "now" and the count's. */
    private class Moving(var at: Instant, private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = Moving(at, zone)
        override fun instant(): Instant = at
    }

    private fun services(off: Set<String> = emptySet(), clock: Clock? = null): Services =
        (if (clock != null) testServices(clock = clock) else testServices()).apply {
            runBlocking { settings.set(SettingsKeys.PROVIDERS_OFF, off.takeIf { it.isNotEmpty() }) }
        }

    private fun show(services: Services) {
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home(), Dest.Privacy))) }
        compose.settle(30_000)
    }

    private fun texts(part: String): List<String> = compose.onAllNodesWithText(part, substring = true).fetchSemanticsNodes()
        .map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") }

    private fun shown(text: String) = assertTrue(text, texts(text).isNotEmpty())

    private fun notShown(text: String) = assertTrue("$text: ${texts(text)}", texts(text).isEmpty())

    /** A card's label, which [CardLabel] draws in capitals. */
    private fun label(text: String) = text.uppercase()

    private fun name(id: String) = SourcesModel.ALL.first { it.id == id }.name

    private fun at(services: Services) = GoFormat.clock(services.clock.instant(), services.clock.zone)

    // H9: every source asked or held back since the start is shown.

    @Test
    fun aSourceAskedAndThenSwitchedOffIsListedWithItsCount() {
        val s = services(off = setOf(Wikipedia.ID))
        repeat(3) { s.asked.sent(Wikipedia.ID) }
        show(s)
        shown(label("Not asked now"))
        shown(name(Wikipedia.ID))
        shown("asked 3 times")
        notShown("held back")
    }

    @Test
    fun aSourceOffAndNeverAskedNorHeldBackIsNotListedAndNoHeadingIsEmpty() {
        val s = services(off = setOf(Wikipedia.ID))
        show(s)
        notShown(label("Not asked now"))
        notShown(name(Wikipedia.ID))
    }

    @Test
    fun aRowSaysWhatWasAskedThenWhatWasHeldBackThenTheHostsBeyondThreeAtMost() {
        val s = services()
        repeat(3) { s.asked.sent(SourcesModel.PANORAMAX.id) }
        for (h in listOf("e.example", "a.example", "d.example", "b.example", "c.example")) s.asked.sent(SourcesModel.PANORAMAX.id, beyond = h)
        repeat(2) { s.asked.refused(SourcesModel.PANORAMAX.id) }
        show(s)
        val line = texts("also at a.example").single()
        val asked = line.indexOf("asked 8 times")
        val held = line.indexOf("held back 2 times")
        val hosts = line.indexOf("also at a.example, b.example, c.example and 2 more")
        assertTrue(line, asked >= 0 && held > asked && hosts > held)
        assertFalse(line, "d.example" in line || "e.example" in line)
    }

    @Test
    fun aSourceOnThatWasOnlyHeldBackNeverReadsNotAsked() {
        val s = services()
        s.asked.refused(Overpass.INFO.id)
        show(s)
        shown("held back 1 time")
        assertTrue(texts("held back").none { "not asked" in it })
        shown("not asked")
    }
}
