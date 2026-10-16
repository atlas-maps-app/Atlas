package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A pill chip, the Design board's: 34 px high, 17 round, 12 px in, the label in Geist 800 at 12, an optional 16 px
 * symbol 6 before it and a count after it ("Open now", "status", "café 12"). White with an ink label by default; a
 * [background] and [textColour] give a status chip (the board's #E1F2EA with #12663A), [outlined] adds the board's
 * 1 px #E6E1D6 ring. With [onClick] it is a button; without, a static tag.
 */
@Composable
fun Chip(
    text: String?,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolTint: Color = Color.Unspecified,
    count: Int? = null,
    background: Color = Color.White,
    outlined: Boolean = false,
    contentDescription: String? = null,
    textColour: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val fg = if (textColour == Color.Unspecified) chipInk(background) else textColour.argbLong()
    Row(
        modifier
            .heightIn(min = 34.dp)
            .cssEl(background.argbLong(), r = CHIP, shadows = if (outlined) listOf(CssShadow(0f, 0f, 0f, 1f, BOARD_RING)) else emptyList(), clip = true)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = if (text == null) 9.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = if (symbolTint == Color.Unspecified) argb(fg) else symbolTint, size = 16.dp)
        if (text != null) TxLine(text, CHIP_TEXT, fg)
        if (count != null) Text(count.toString(), style = CHIP_TEXT, color = argb(fg).copy(alpha = 0.6f), maxLines = 1)
    }
}

/**
 * A chip that is on or off ("Open now", "Step-free"), as the Design board's filter chips: ink with white words when
 * on, white with ink words when off. A chip with only a [symbol] needs a
 * [contentDescription].
 */
@Composable
fun ToggleChip(
    text: String?,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolTint: Color? = null,
    contentDescription: String? = null,
) {
    val fg: Argb = if (selected) 0xFFFFFFFF else BOARD_INK
    Row(
        modifier
            .heightIn(min = 34.dp)
            .cssEl(
                if (selected) BOARD_INK else 0xFFFFFFFF, r = CHIP,
                clip = true,
            )
            .toggleable(selected, role = Role.Switch, onValueChange = onToggle)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = if (text == null) 9.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = if (selected) argb(fg) else (symbolTint ?: argb(fg)), size = 16.dp)
        if (text != null) TxLine(text, CHIP_TEXT, fg)
    }
}

/** The Design board's chips: 17 round, Geist 800 at 12. */
private val CHIP = CssCorners(17f, 17f, 17f, 17f)
private val CHIP_TEXT = txStyle(12f, 800, 15.5f)

/** Ink on a light chip, white on a dark one. */
private fun chipInk(background: Color): Argb = if (background.luminance() > 0.45f) BOARD_INK else 0xFFFFFFFF

/**
 * A symbol with a value, the way the boards show a fact at a glance: "⚡ 150", "★ 4.8", "🚶 4′". The symbol sits in a
 * small disc of [tint]; [dark] makes the chip itself ink, as for a selected charger.
 */
@Composable
fun ValueChip(
    symbol: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Atlas.colors
    val bg = if (dark) c.pill else c.card
    val fg = if (dark) c.onPill else c.ink
    Row(
        modifier
            .heightIn(min = 36.dp)
            .softShadow(CircleShape, c.shadow, 6.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription } else Modifier)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Symbol(symbol, tint = contentColourFor(tint), size = 16.dp, weight = 1.2f)
        }
        Text(value, style = Atlas.type.bodyStrong, color = fg, maxLines = 1)
    }
}
