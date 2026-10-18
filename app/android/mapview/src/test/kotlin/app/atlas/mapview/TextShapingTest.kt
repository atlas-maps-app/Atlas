package app.atlas.mapview

import app.atlas.anchor.AnchorCases
import app.atlas.render.live.LiveEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Android's own shaping of the engine's ground text (plan-vector D13, K13), as the oracle draws it: `SvgCanvas`'s text
 * paint (anti-aliased, subpixel, linear metrics) with the bundled variable fonts at the renders' weights and the SVG's
 * letter spacing, shaped by Minikin and HarfBuzz with Skia's advances. Every canal name of the Westerkerk's and the
 * canal ring's scenes, Schiphol's runway designators, kerning pairs and every capital and digit, each with its glyphs
 * and their x positions bit for bit, are written to `mapcore/cpp/tests/text/android_shaping.txt` by [TextRounding.row]
 * (the rows VK facts' glyph check makes on a device), and the core's shaper is held to them
 * (`tests/text/shaper_test.cpp`). The test fails when the committed file differs from what Android shapes now, and
 * leaves the new one in the build folder. Robolectric's text stack runs HarfBuzz 2.6.4, whose kerning floors: the probe
 * says so.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TextShapingTest {
    private class Style(val name: String, val family: String, val weight: Int, val size: Float, val spacing: Float)

    private val canal = Style("canal", TextRounding.GEIST, 700, 3.2f, 0.3f)
    private val runway = Style("runway", TextRounding.GEIST, 800, 18f, 0.05f)

    private fun line(style: Style, text: String): String =
        TextRounding.row(style.name, style.family, style.weight, style.size, style.spacing, text, EngineTestKit.fonts)

    /** The canal names of a scene, uppercased as the port does (`name.uppercase()`), in source order, each once. */
    private fun canalNames(): List<String> {
        val out = LinkedHashSet<String>()
        val regions = listOfNotNull(AnchorCases.westerkerk(), AnchorCases.grachten())
        for (region in regions) {
            val scene = LiveEngine.loadScene(region)
            @Suppress("UNCHECKED_CAST")
            for (label in scene.S["labels"] as List<Map<String, Any?>>) {
                if (label["kind"] == "canal") out += (label["name"] as String).uppercase()
            }
        }
        return out.toList()
    }

    @Test
    fun androidShapesTheGroundTextAsTheCommittedFileSays() {
        val lines = ArrayList<String>()
        lines += "# Android's shaping of the engine's ground text (mapview's TextShapingTest, Robolectric SDK 34): style, weight, size and letter spacing"
        lines += "# (float bits), the text, then glyph:x per glyph (x as float bits, in text units from the run's start), the advance and measureText."
        val names = canalNames()
        assertTrue("the scenes hold canal names", names.size >= 8)
        for (n in names) lines += line(canal, n)
        for (d in listOf("18R", "36L", "18C", "36C", "18L", "36R", "06", "24", "09", "27", "04", "22")) lines += line(runway, d)
        // Kerning pairs and the rest of the alphabet, at the canal names' style and at others the engine draws.
        val stress = listOf(
            "AVATAR", "TOWARD", "WAVY", "YACHT", "LT", "FA", "PA", "AY", "AW", "VA", "TA", "LY", "KO", "RT",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789", "ÉCLUSE", "ÅÄÖ ÆØ", "STRAßE", "IJ IJSSEL", "A-B, C.",
        )
        for (s in stress) lines += line(canal, s)
        // Every capital and digit alone: its advance without kerning.
        for (c in ('A'..'Z') + ('0'..'9')) lines += line(canal, c.toString())
        for (s in listOf("WAVY", "0123456789", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")) {
            lines += line(Style("geist400", TextRounding.GEIST, 400, 12f, 0f), s)
            lines += line(Style("geist800", TextRounding.GEIST, 800, 40f, 0.18f), s)
            lines += line(Style("geist100", TextRounding.GEIST, 100, 1f, 0.18f), s)
            lines += line(Style("gelasio", TextRounding.GELASIO, 400, 3f, 0f), s)
            lines += line(Style("gelasio700", TextRounding.GELASIO, 700, 8f, 0.1f), s)
        }
        val text = lines.joinToString("\n", postfix = "\n")
        val built = File(System.getProperty("atlas.shots") ?: "build", "../text/android_shaping.txt").canonicalFile
        built.parentFile.mkdirs()
        built.writeText(text)
        val committed = File(EngineTestKit.repo, "app/android/mapcore/cpp/tests/text/android_shaping.txt")
        assertTrue("no $committed: copy $built there", committed.isFile)
        assertEquals("Android shapes differently from $committed now; the new file is $built", committed.readText(), text)
        assertEquals(Locale.US, Locale.getDefault())
    }

    @Test
    fun theProbeFindsTheFloorOfRobolectricsHarfBuzz() {
        val verdict = TextRounding.probe(EngineTestKit.fonts)
        assertEquals(TextRounding.FLOOR_KERN.toFloat(), verdict.kern)
        assertEquals(TextRounding.Rule.FLOOR, verdict.rule)
        assertEquals("text.rounding: floor (probe O-E, Geist 700 at 3.2 px: kern 5.000/256 px; floor 5, round 6)", verdict.line)
    }
}
