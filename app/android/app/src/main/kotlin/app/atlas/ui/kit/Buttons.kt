package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R

/** The boards' ink, #14161B. */
internal const val BOARD_INK: Argb = 0xFF14161B

/** The Design board's hairline ring and card edge, #E6E1D6 (`box-shadow: 0 0 0 1px`). */
internal const val BOARD_RING: Argb = 0xFFE6E1D6

/**
 * A round icon-only button. By default it is the Place board's button over the map: frosted white (86 %, blurred 20 px
 * behind it) with the boards' cool shadow (0 6 20, 14 %); a [background] given makes it that solid colour instead, and
 * [elevation] 0 drops the shadow. [selected] fills it with ink. The [contentDescription] is required: it is the button's
 * only words.
 */
@Composable
fun SymbolButton(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = argb(BOARD_INK),
    background: Color = Color.Unspecified,
    selected: Boolean = false,
    elevation: Dp = 8.dp,
    symbolSize: Dp = size * 0.42f,
) {
    val r = CssCorners(size.value / 2, size.value / 2, size.value / 2, size.value / 2)
    val shadows = if (elevation > 0.dp) listOf(CssShadow(0f, 6f, 20f, 0f, 0x24181C30)) else emptyList()
    val look = when {
        selected -> Modifier.cssEl(BOARD_INK, r = r, shadows = shadows, clip = true)
        background == Color.Unspecified -> Modifier.cssEl(0xDBFFFFFF, r = r, shadows = shadows, blur = 20f, clip = true)
        else -> Modifier.cssEl(background.argbLong(), r = r, shadows = shadows, clip = true)
    }
    Box(
        modifier
            .size(size)
            .then(look)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint = if (selected) Color.White else tint, size = symbolSize)
    }
}

/** A colour as the boards' ARGB. */
internal fun Color.argbLong(): Argb = toArgb().toLong() and 0xFFFFFFFFL

/**
 * The round back button at the top left of a screen, as the Design board's components draw it: 44 px, white, a 1 px
 * #E6E1D6 ring, the 20 px chevron in ink.
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.kit_back)
    Box(
        modifier
            .size(44.dp)
            .cssEl(0xFFFFFFFF, r = CssCorners(22f, 22f, 22f, 22f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, BOARD_RING)), clip = true)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol("ui.back", tint = argb(BOARD_INK), size = 20.dp) }
}

/**
 * The tall, fully round call to action at the bottom of a screen, the Design board's "Main action": 56 px, 28 round,
 * ink by default (white on a dark screen; [colour] for another, such as the Place board's go blue), the label in
 * Geist 800 at 15 with a 20 px [symbol] 8 before it. Disabled, it turns to the board's ring colour with grey words.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    colour: Color = Color.Unspecified,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
) {
    // Unspecified is the board's ink; on a dark screen (the night guidance) it turns white so it still reads.
    val base = if (colour != Color.Unspecified) colour else if (Atlas.colors.isDark) Color.White else argb(BOARD_INK)
    val bg = if (enabled) base.argbLong() else BOARD_RING
    val fg = if (!enabled) 0xFF676D7CL else if (base.luminance() > 0.6f) BOARD_INK else 0xFFFFFFFFL
    Row(
        modifier
            .heightIn(min = 56.dp)
            .cssEl(bg, r = CssCorners(28f, 28f, 28f, 28f), clip = true)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = argb(fg), size = 20.dp)
        TxLine(text, txStyle(15f, 800, 19.5f), fg)
    }
}

/**
 * A dark pill with a symbol and short text, the boards' secondary action ("✦ 3", "Plan it for me"): the Main board's
 * ink pill with its shadow (0 6 18, ink at 25 %), at least 44 px high.
 */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = argb(BOARD_INK),
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val fg = if (background.luminance() > 0.6f) argb(BOARD_INK) else Color.White
    Row(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .cssEl(background.argbLong(), r = CssCorners(22f, 22f, 22f, 22f), shadows = listOf(CssShadow(0f, 6f, 18f, 0f, 0x4014161B)), clip = true)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}
