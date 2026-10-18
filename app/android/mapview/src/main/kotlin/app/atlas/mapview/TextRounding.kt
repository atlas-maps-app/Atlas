package app.atlas.mapview

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.text.TextRunShaper
import android.os.Build
import app.atlas.mapcore.Native
import app.atlas.mapview.svg.SvgFonts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * The platform's rule for kerning (plan-vector D13, K13): how its text stack's HarfBuzz scales a font's units to its
 * 1/256 px. HarfBuzz used to take the floor (Robolectric's text stack, HarfBuzz 2.6.4, the oracle's); newer releases
 * round. The core shapes as the platform does (`CP/text/Shaper.hpp`), so the app measures the rule once with Android's
 * own Paint on a kerned pair where the two rules differ and tells the core. That holds on any maker's text stack,
 * whatever its HarfBuzz.
 *
 * VK facts logs the verdict (`text.rounding: floor|round (probe …)`) and then checks the core's shaping against
 * Android's on the same runs as `CP/tests/text/android_shaping.txt` (`text.glyphs: …`).
 */
object TextRounding {
    /** A rule, as the core names it. */
    enum class Rule(val word: String) { FLOOR("floor"), ROUND("round") }

    /**
     * The probe: O and E at 3.2 px in Geist 700 (the canal names' face) without letter spacing. GPOS kerns the pair 12
     * units plus a variation delta of -4.8 at this weight; at HarfBuzz's scale of 819/1000 the floor gives 9 - 4 = 5,
     * the rounding 10 - 4 = 6 (`CP/tests/text/shaper_test.cpp` holds the core to both).
     */
    const val PROBE = "OE"
    const val FLOOR_KERN = 5
    const val ROUND_KERN = 6

    /** What the probe measured: the rule (null when the kern is neither) and the kern in 1/256 px. */
    class Verdict(val rule: Rule?, val kern: Float) {
        val line: String
            get() = "text.rounding: ${rule?.word ?: "unknown"} (probe O-E, Geist 700 at 3.2 px: kern " +
                "${String.format(Locale.ROOT, "%.3f", kern)}/256 px; floor $FLOOR_KERN, round $ROUND_KERN)"
    }

    /** The paint as `SvgCanvas.font` sets it up for a `<text>` in [family] at [weight], [size] and [spacing]. */
    fun paint(fonts: SvgFonts, family: String, weight: Int, size: Float, spacing: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = fonts.typeface(family, weight, false)
            fontVariationSettings = fonts.variation(family, weight)
            textSize = size
            letterSpacing = if (size > 0f) spacing / size else 0f
            textAlign = Paint.Align.CENTER
        }

    /** Measures the probe with Android's Paint: the kern is O's advance before E less its advance alone. */
    fun probe(fonts: SvgFonts): Verdict {
        val p = paint(fonts, GEIST, 700, 3.2f, 0f)
        val pair = FloatArray(2)
        p.getTextRunAdvances(PROBE.toCharArray(), 0, 2, 0, 2, false, pair, 0)
        val alone = FloatArray(1)
        p.getTextRunAdvances(PROBE.toCharArray(), 0, 1, 0, 1, false, alone, 0)
        val kern = (pair[0] - alone[0]) * 256f
        val rule = when (kern) {
            FLOOR_KERN.toFloat() -> Rule.FLOOR
            ROUND_KERN.toFloat() -> Rule.ROUND
            else -> null
        }
        return Verdict(rule, kern)
    }

    /** Tells the core [verdict]'s rule (an unknown one leaves the core's own: the oracle's floor); the rule it takes. */
    fun apply(verdict: Verdict): String = Native.textRounding(verdict.rule?.word ?: "")

    private fun hex(v: Float) = String.format(Locale.ROOT, "%08x", java.lang.Float.floatToRawIntBits(v))

    /**
     * One row of Android's shaping, as `CP/tests/text/android_shaping.txt` holds them: the style's name, weight, size
     * and spacing (floats' bits), the text, then each glyph's id and x (from the run's start, in text units), the
     * run's advance and measureText. Android 12 (API 31) and later: TextRunShaper.
     */
    fun row(style: String, family: String, weight: Int, size: Float, spacing: Float, text: String, fonts: SvgFonts): String {
        check(Build.VERSION.SDK_INT >= 31) { "TextRunShaper needs Android 12" }
        val p = paint(fonts, family, weight, size, spacing)
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, p)
        val sb = StringBuilder()
        sb.append(style).append(' ').append(weight).append(' ').append(hex(size)).append(' ').append(hex(spacing))
            .append(" | ").append(text).append(" |")
        for (i in 0 until glyphs.glyphCount()) {
            sb.append(' ').append(glyphs.getGlyphId(i)).append(':').append(hex(glyphs.getGlyphX(i)))
        }
        sb.append(" | ").append(hex(glyphs.advance)).append(' ').append(hex(p.measureText(text)))
        return sb.toString()
    }

    /** The CSS family a check run's style draws with (the core's `PlatformRun::face`). */
    fun familyOf(style: String): String = if (style.startsWith("gelasio")) GELASIO else GEIST

    /**
     * Android's rows for the core's check runs (`Native.textCheckRuns`, `<style> <weight> <size> <spacing> | <text>` a
     * line), or null below Android 12.
     */
    fun platformRows(fonts: SvgFonts, runs: String): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val out = StringBuilder()
        for (line in runs.lineSequence()) {
            val bar = line.indexOf(" | ")
            if (bar < 0) continue
            val head = line.substring(0, bar).split(' ')
            if (head.size != 4) continue
            val size = java.lang.Float.intBitsToFloat(head[2].toLong(16).toInt())
            val spacing = java.lang.Float.intBitsToFloat(head[3].toLong(16).toInt())
            out.append(row(head[0], familyOf(head[0]), head[1].toInt(), size, spacing, line.substring(bar + 3), fonts)).append('\n')
        }
        return out.toString()
    }

    /** A bundled font's bytes in a direct buffer, for the core. */
    private fun asset(assets: AssetManager, name: String): ByteBuffer {
        val bytes = assets.open("${SvgFonts.DIR}/$name").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
    }

    /**
     * VK facts' text lines (off the main thread): the probe's verdict, told to the core, and the core's glyph check
     * against Android's own shaping under that rule (`text.glyphs: <n> runs, <m> glyphs equal Android's (rule …)` or
     * `FAIL …`; skipped below Android 12, which has no TextRunShaper).
     */
    fun factsLines(assets: AssetManager): List<String> {
        val fonts = SvgFonts.fromAssets(assets)
        val verdict = probe(fonts)
        apply(verdict)
        val rows = platformRows(fonts, Native.textCheckRuns())
            ?: return listOf(verdict.line, "text.glyphs: skipped (Android ${Build.VERSION.SDK_INT} has no TextRunShaper)")
        val check = Native.textCheck(asset(assets, "Geist-wght.ttf"), asset(assets, "Gelasio-wght.ttf"), rows)
        return listOf(verdict.line, check)
    }

    const val GEIST = "Geist, Helvetica, sans-serif"
    const val GELASIO = "Georgia, serif"
}
