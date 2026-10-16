package app.atlas.ui.local

import app.atlas.ui.kit.SymbolPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Every symbol id written in this package's code exists in the symbol catalogue; a typo would silently draw the marker pin. */
@RunWith(RobolectricTestRunner::class)
class LocalSymbolsTest {
    private val prefixes = listOf(
        "ui", "weather", "bin", "place", "nature", "food", "sign", "sky", "fact", "mode", "need", "cat", "tab", "local", "board", "object", "sport", "water", "shop",
    )

    @Test
    fun everySymbolIdInTheLocalPackageIsInTheCatalogue() {
        val dir = File(System.getProperty("atlas.repo"), "app/android/app/src/main/kotlin/app/atlas/ui/local")
        val literal = Regex("\"((?:${prefixes.joinToString("|")})[.-][a-z0-9]+(?:[.-][a-z0-9]+)*)\"")
        // Reminder keys ("sky.rain") look like ids; the calls that take them are not symbol calls.
        val key = Regex("(?:schedule|scheduledAt|cancel|keys)\\(\"([^\"]+)\"")
        val ids = dir.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f ->
                val text = f.readText()
                val keys = key.findAll(text).map { it.groupValues[1] }.toSet()
                literal.findAll(text).map { it.groupValues[1] }.filter { it !in keys }.map { it to f.name }
            }
            .distinct().toList()
        assertTrue("found symbol ids", ids.size > 30)
        val missing = ids.filter { (id, _) -> SymbolPaths.of(id) == null }
        assertTrue("symbols missing from the catalogue: $missing", missing.isEmpty())
    }
}
