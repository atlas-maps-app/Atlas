package app.atlas.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One choice of a [SegmentedPill]: a label, an optional symbol and an optional count shown after the label. */
@Immutable
data class Segment(val label: String, val symbol: String? = null, val count: Int? = null)

/**
 * A pill track of choices, the selected one dark: "🔗 Chains 84 | ▢ Independent 212" on the Brands board, or
 * Fuel / Charge / Park. Segments share the width equally.
 */
@Composable
fun SegmentedPill(
    segments: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Atlas.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.paperDeep)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEachIndexed { i, s ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.pill else c.paperDeep, label = "segment")
            val fg = if (on) c.onPill else c.ink
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(on, role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (s.symbol != null) Symbol(s.symbol, tint = fg, size = 18.dp)
                val text = if (s.count != null) "${s.label} ${s.count}" else s.label
                Text(text, style = Atlas.type.bodyStrong, color = fg, maxLines = 1)
            }
        }
    }
}
