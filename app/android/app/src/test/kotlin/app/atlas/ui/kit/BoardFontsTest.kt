package app.atlas.ui.kit

import android.app.Application
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import app.atlas.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Every bundled face loads, and every family the boards name has its own (none falls back to Geist unseen). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardFontsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun everyBundledFaceLoads() {
        val fonts = R.font::class.java.fields
        assertTrue(fonts.size >= 50)
        for (f in fonts) assertNotNull(f.name, ResourcesCompat.getFont(app, f.getInt(null)))
    }

    @Test
    fun everyFamilyTheBoardsNameIsBundled() {
        val repo = File(System.getProperty("atlas.repo") ?: "../../..")
        val specs = File(repo, "cache/claude-session/boards/specs").listFiles { f -> f.extension == "json" } ?: return
        // the JSON string, escaped quotes and all ("\"Instrument Serif\", serif")
        val family = Regex("\"font-family\": ?\"((?:[^\"\\\\]|\\\\.)*)\"")
        val names = specs.flatMap { family.findAll(it.readText()).map { m -> m.groupValues[1].replace("\\\"", "\"") }.toList() }
            .map { it.split(',').first().trim().trim('"', '\'') }
            .toSet()
        val missing = names.filter { n -> n.lowercase() !in setOf("geist", "system-ui", "sans-serif") && BoardFonts.of(n) === SansFamily }
        assertTrue("faces the boards name that are not bundled: $missing", missing.isEmpty())
    }
}
