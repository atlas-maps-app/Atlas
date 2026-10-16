package app.atlas.ui.eat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import java.util.Locale

/** [text] in small caps: each word's first letter at [size], the rest upper case and smaller. */
internal fun smallCaps(text: String, size: TextUnit, colour: Color? = null, lang: String = "en"): AnnotatedString = buildAnnotatedString {
    val locale = Locale.forLanguageTag(lang)
    val small = SpanStyle(fontSize = size * 0.78f, color = colour ?: Color.Unspecified)
    val big = SpanStyle(fontSize = size, color = colour ?: Color.Unspecified)
    var start = true
    for (ch in text) {
        if (ch.isWhitespace()) {
            append(ch)
            start = true
            continue
        }
        if (start || ch.isDigit()) withStyle(big) { append(ch.uppercase(locale)) } else withStyle(small) { append(ch.uppercase(locale)) }
        start = false
    }
}

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

