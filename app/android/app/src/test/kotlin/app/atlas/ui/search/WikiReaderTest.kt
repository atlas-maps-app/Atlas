package app.atlas.ui.search

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.atlas.R
import app.atlas.providers.SwitchedOffException
import app.atlas.ui.kit.AtlasTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * What the Wikipedia reader says where it has no article: that Wikipedia is switched off, naming the Settings row
 * the user taps to switch it on as that row names itself, in each language; and only then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WikiReaderTest {
    @get:Rule
    val compose = createComposeRule()

    private val android = File(System.getProperty("atlas.repo") ?: "../..", "app/android")

    private fun read(load: suspend () -> app.atlas.providers.WikipediaArticle?) {
        compose.setContent { AtlasTheme(dark = false) { WikiReader("https://en.wikipedia.org/wiki/Westerkerk", load = load) {} } }
        val none = RuntimeEnvironment.getApplication().getString(R.string.place_wiki_none)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(none)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The sentence the reader shows with Wikipedia off, and the Settings row's label it must hold. */
    private fun switchedOffNamesTheRow() {
        val app = RuntimeEnvironment.getApplication()
        val row = app.getString(R.string.mine_settings_data)
        // the row the user taps in Settings is that label's (SettingsScreen's "Data sources & keys" row)
        val settings = File(android, "app/src/main/kotlin/app/atlas/ui/mine/SettingsScreen.kt").readText()
        assertTrue(Regex("""SettingRow\(stringResource\(R\.string\.mine_settings_data\)[^\n]*Dest\.DataSources\(\)""").containsMatchIn(settings))
        read { throw SwitchedOffException("wikipedia", "en.wikipedia.org") }
        val said = app.getString(R.string.place_wiki_off, row)
        assertTrue("\"$said\" names the row \"$row\"", row in said)
        compose.onNodeWithText(said).assertExists()
    }

    @Test
    @Config(qualifiers = "fr")
    fun inFrenchTheSentenceNamesTheSettingsRow() = switchedOffNamesTheRow()

    @Test
    @Config(qualifiers = "de")
    fun inGermanTheSentenceNamesTheSettingsRow() = switchedOffNamesTheRow()

    @Test
    fun inEnglishTheSentenceNamesTheSettingsRow() = switchedOffNamesTheRow()
}
