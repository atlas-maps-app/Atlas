package app.atlas.ui.eat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.Locale

/** "$$" for a price level 1..4. */
internal fun priceText(level: Int?): String? = level?.takeIf { it in 1..4 }?.let { "$".repeat(it) }

/** "4.8" with one decimal, whatever the phone's locale. */
internal fun starsText(stars: Double): String = String.format(Locale.ROOT, "%.1f", stars)

/** The meta line of a list row: walk minutes, then each of [parts] that is known, joined by middle dots. */
internal fun metaLine(walkMinutes: Int, vararg parts: String?): String =
    (listOf("$walkMinutes′") + parts.filterNotNull().filter { it.isNotEmpty() }).joinToString(" · ")

/**
 * Light status-bar icons while a dark object (the bar card, the slate, the neon wall, the noren, dark wood) fills the
 * top of the screen; the previous look comes back when the screen leaves.
 */
@Composable
internal fun LightStatusIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val before = controller.isAppearanceLightStatusBars
            controller.isAppearanceLightStatusBars = false
            onDispose { controller.isAppearanceLightStatusBars = before }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The top padding that puts something at [board] px from the top of the screen, as the board places it (its
 * coordinates already allow for a status bar), but never under a taller status bar than the board's.
 */
@Composable
internal fun boardTop(board: Dp, clearance: Dp = 4.dp): Dp {
    val bar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return maxOf(board, bar + clearance)
}

