package app.atlas.mapview

import android.graphics.Paint
import android.graphics.Typeface
import app.atlas.engine.LabelStyle
import app.atlas.engine.TextMeasurer

/** The fonts behind the engine's label styles: serif italic for places and water, sans for streets and the rest. */
class LabelTypefaces(
    val serifItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC),
    val sans: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
    val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL),
) {
    fun of(style: LabelStyle): Typeface = when (style) {
        LabelStyle.SERIF_ITALIC -> serifItalic
        LabelStyle.SANS -> sans
        LabelStyle.SANS_MEDIUM -> sansMedium
    }
}

/**
 * Text widths from the platform's fonts, for the engine's label placement. One instance per thread: it owns a [Paint].
 * Letter spacing is left to the engine, which adds it itself.
 */
class AndroidTextMeasurer(private val typefaces: LabelTypefaces = LabelTypefaces()) : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var style: LabelStyle? = null
    private var size = -1f

    private fun set(style: LabelStyle, size: Float) {
        if (style != this.style) {
            paint.typeface = typefaces.of(style)
            this.style = style
        }
        if (size != this.size) {
            paint.textSize = size
            this.size = size
        }
    }

    override fun width(text: String, style: LabelStyle, size: Float): Float {
        if (text.isEmpty()) return 0f
        set(style, size)
        return paint.measureText(text)
    }

    override fun advances(text: String, style: LabelStyle, size: Float, out: FloatArray) {
        if (text.isEmpty()) return
        set(style, size)
        paint.getTextWidths(text, out)
    }
}
