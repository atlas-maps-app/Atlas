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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R

/**
 * A round icon-only button: a symbol on white with a soft shadow, as on the map's control column and every board's
 * back button. [selected] fills it with ink. The [contentDescription] is required: it is the button's only words.
 */
@Composable
fun SymbolButton(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Atlas.colors.ink,
    background: Color = Atlas.colors.card,
    selected: Boolean = false,
    elevation: Dp = 8.dp,
    symbolSize: Dp = size * 0.46f,
) {
    val c = Atlas.colors
    val bg = if (selected) c.pill else background
    val fg = if (selected) c.onPill else tint
    Box(
        modifier
            .size(size)
            .then(if (elevation > 0.dp) Modifier.softShadow(CircleShape, c.shadow, elevation) else Modifier)
            .clip(CircleShape)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint = fg, size = symbolSize)
    }
}

/** The round back button at the top left of every screen. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    SymbolButton("ui-chevron-left", stringResource(R.string.kit_back), onClick, modifier, size = 52.dp)

/**
 * The tall, fully round call to action at the bottom of a screen ("↱ 12′", "Reserve a space"): action blue by default.
 * A [symbol] leads the label.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    colour: Color = Atlas.colors.go,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
) {
    val c = Atlas.colors
    val bg = if (enabled) colour else c.line
    val fg = if (enabled) contentColourFor(colour) else c.inkFaint
    Row(
        modifier
            .heightIn(min = 56.dp)
            .then(if (enabled) Modifier.softShadow(CircleShape, if (c.isDark) c.shadow else colour.copy(alpha = 0.35f), 10.dp) else Modifier)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = fg, size = 22.dp, weight = 1.2f)
        Text(text, style = Atlas.type.name, color = fg)
    }
}

/** A dark pill with a symbol and short text, the boards' secondary action ("✦ 3", "Plan it for me"). */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Atlas.colors.pill,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val fg = contentColourFor(background)
    Row(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .softShadow(CircleShape, Atlas.colors.shadow, 8.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}
