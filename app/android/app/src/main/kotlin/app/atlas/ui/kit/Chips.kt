package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A pill chip: an optional symbol, a short label and an optional count ("Open now", "☕ café 12"). With [onClick] it is a
 * button; without, a static tag.
 */
@Composable
fun Chip(
    text: String?,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolTint: Color = Atlas.colors.ink,
    count: Int? = null,
    background: Color = Atlas.colors.card,
    outlined: Boolean = false,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Atlas.colors
    val fg = contentColourFor(background)
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (outlined) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = if (text == null) 10.dp else 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = if (symbolTint == c.ink) fg else symbolTint, size = 18.dp)
        if (text != null) Text(text, style = Atlas.type.label, color = fg, maxLines = 1)
        if (count != null) Text(count.toString(), style = Atlas.type.label, color = fg.copy(alpha = 0.6f))
    }
}

/**
 * A chip that is on or off ("Open now", "Step-free"); dark when on, as on the boards. A chip with only a [symbol]
 * needs a [contentDescription].
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
    val c = Atlas.colors
    val bg = if (selected) c.pill else c.card
    val fg = if (selected) c.onPill else c.ink
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (!selected) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .toggleable(selected, role = Role.Switch, onValueChange = onToggle)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = if (text == null) 10.dp else 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = if (selected) fg else (symbolTint ?: fg), size = 18.dp)
        if (text != null) Text(text, style = Atlas.type.label, color = fg, maxLines = 1)
    }
}

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
