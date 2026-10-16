package app.atlas.mapview

import kotlin.math.roundToInt

/** Colours as packed ARGB ints (0xAARRGGBB), as Android's Canvas takes them: the overlays' small colour maths. */
internal object Colours {
    fun withAlpha(c: Int, a: Int): Int = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun withAlpha(c: Int, a: Float): Int = withAlpha(c, (a * 255f).roundToInt())

    /** [a] moved toward [b] by [t] (0..1) in every channel, alpha included. */
    fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val s = 1f - t
        val aa = (a ushr 24) * s + (b ushr 24) * t
        val r = ((a shr 16) and 0xFF) * s + ((b shr 16) and 0xFF) * t
        val g = ((a shr 8) and 0xFF) * s + ((b shr 8) and 0xFF) * t
        val bb = (a and 0xFF) * s + (b and 0xFF) * t
        return ((aa + 0.5f).toInt() shl 24) or ((r + 0.5f).toInt() shl 16) or ((g + 0.5f).toInt() shl 8) or (bb + 0.5f).toInt()
    }

    /** Perceived brightness 0..255 (Rec. 601 weights). */
    fun luma(c: Int): Float = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
}
