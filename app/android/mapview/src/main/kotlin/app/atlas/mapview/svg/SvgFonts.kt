package app.atlas.mapview.svg

import android.content.res.AssetManager
import android.graphics.Typeface
import java.io.File

/**
 * The typefaces of the engine's text. The renders name two CSS families: `Georgia, serif` (drawn with Gelasio, an OFL
 * typeface made metric-compatible with Georgia) and `Geist, Helvetica, sans-serif` (Geist, OFL). Both are variable
 * fonts with a weight axis; Gelasio also has an italic file. They ship in this library's assets under [DIR].
 */
class SvgFonts private constructor(private val open: (String) -> Typeface.Builder?) {
    private val cache = HashMap<Long, Typeface>()

    /** The typeface for the CSS [family] list at [weight] (100 … 900), upright or [italic]. */
    fun typeface(family: String, weight: Int, italic: Boolean): Typeface {
        val face = face(family)
        val key = (face.ordinal.toLong() shl 32) or (weight.toLong() shl 1) or (if (italic) 1L else 0L)
        return cache.getOrPut(key) { build(face, weight, italic) }
    }

    /**
     * The `font-variation-settings` that select [weight] in the bundled variable font for [family], or null for a
     * system face. Set on the paint as well as the typeface: some renderers only honour the paint's.
     */
    fun variation(family: String, weight: Int): String? = when (face(family)) {
        Face.SERIF -> "'wght' ${weight.coerceIn(400, 700)}"
        Face.SANS -> "'wght' ${weight.coerceIn(100, 900)}"
        else -> null
    }

    private enum class Face { SERIF, SANS, SYSTEM_SANS, SYSTEM_SERIF }

    private fun face(family: String): Face {
        for (raw in family.split(',')) {
            when (raw.trim().trim('"', '\'').lowercase()) {
                "georgia", "gelasio" -> return Face.SERIF
                "geist" -> return Face.SANS
                "helvetica", "arial", "sans-serif" -> return Face.SYSTEM_SANS
                "serif", "times", "times new roman" -> return Face.SYSTEM_SERIF
            }
        }
        return Face.SYSTEM_SERIF
    }

    private fun build(face: Face, weight: Int, italic: Boolean): Typeface {
        val file = when (face) {
            Face.SERIF -> if (italic) SERIF_ITALIC else SERIF
            Face.SANS -> SANS
            Face.SYSTEM_SANS -> return Typeface.create(Typeface.SANS_SERIF, weight, italic)
            Face.SYSTEM_SERIF -> return Typeface.create(Typeface.SERIF, weight, italic)
        }
        // Gelasio's weight axis runs 400 … 700, Geist's 100 … 900: a heavier request takes the heaviest instance.
        val w = if (face == Face.SERIF) weight.coerceIn(400, 700) else weight.coerceIn(100, 900)
        val builder = open(file) ?: return Typeface.create(if (face == Face.SERIF) Typeface.SERIF else Typeface.SANS_SERIF, weight, italic)
        return builder
            .setFontVariationSettings("'wght' $w")
            .setWeight(w)
            .setItalic(italic && face == Face.SERIF)
            .build()
    }

    companion object {
        /** The assets folder of the bundled fonts. */
        const val DIR = "svgfonts"
        private const val SERIF = "Gelasio-wght.ttf"
        private const val SERIF_ITALIC = "Gelasio-Italic-wght.ttf"
        private const val SANS = "Geist-wght.ttf"

        /** The bundled fonts, read from the app's assets. */
        fun fromAssets(assets: AssetManager) = SvgFonts { Typeface.Builder(assets, "$DIR/$it") }

        /** The fonts in [dir] (tests read them from the source tree). */
        fun fromDirectory(dir: File) = SvgFonts { name -> File(dir, name).takeIf { it.isFile }?.let { Typeface.Builder(it) } }

        /** Android's own serif and sans-serif only. */
        fun system() = SvgFonts { null }
    }
}
