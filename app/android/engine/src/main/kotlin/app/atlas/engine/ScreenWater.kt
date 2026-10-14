package app.atlas.engine

/**
 * Water whose colour the renderer chooses per frame by where it lies on the screen ([Theme.waterTop] to
 * [Theme.waterBottom]). Scenes mark such vertices with a key: the theme's water (or glint) colour with alpha
 * [KEY_ALPHA], which no drawn colour has, so the renderer can tell them apart and replace them before drawing.
 */
internal object ScreenWater {
    /** The alpha that marks a key; the renderer always replaces such colours with opaque ones. */
    const val KEY_ALPHA = 0xFE

    /** The key for [colour]. */
    fun key(colour: Int): Int = (colour and 0xFFFFFF) or (KEY_ALPHA shl 24)

    /** Whether [theme] colours its water down the screen. */
    fun on(theme: Theme): Boolean = theme.waterTop != null && theme.waterBottom != null

    /** The colour scenes give open water under [theme]: its key when the water is coloured by the screen. */
    fun water(theme: Theme): Int = if (on(theme)) key(theme.water) else theme.water

    /** The colour scenes give a glint's core under [theme] (null when it has none). */
    fun glint(theme: Theme): Int? = theme.glint?.let { if (on(theme)) key(it) else it }
}
