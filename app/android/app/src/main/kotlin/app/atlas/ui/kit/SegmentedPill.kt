package app.atlas.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One choice of a [SegmentedPill]: a label, an optional symbol and an optional count shown after the label. */
@Immutable
data class Segment(val label: String, val symbol: String? = null, val count: Int? = null)

/**
 * The Brands board's "Chains or independent" control: a 56 px sand track (#E4E2DA, 24 round, 4 in, 4 between) whose
 * chosen segment is ink (#14161B, 20 round) with white text, the others clear with ink text; a 16 px symbol 6 before
 * the label in Geist 600 at 14 with tabular figures. The segments grow from their content's width by equal shares, as
 * the board's flex row does ("Chains 84" 160 px, "Independent 212" 208 px on a 412 px phone).
 */
@Composable
fun SegmentedPill(
    segments: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = txStyle(14f, 600, 18f, tnum = true)
    Layout(
        content = {
            segments.forEachIndexed { i, s ->
                val on = i == selected
                val fg = if (on) PILL_ON_INK else PILL_INK
                Row(
                    Modifier
                        .fillMaxHeight()
                        .cssEl(if (on) PILL_ON else 0, r = SEGMENT, clip = true)
                        .selectable(on, role = Role.Tab) { onSelect(i) }
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (s.symbol != null) Symbol(s.symbol, tint = argb(fg), size = 16.dp)
                    TxLine(if (s.count != null) "${s.label} ${s.count}" else s.label, label, fg)
                }
            }
        },
        modifier = modifier.fillMaxWidth().height(56.dp).cssEl(PILL_TRACK, r = TRACK).padding(4.dp).selectableGroup(),
    ) { items, constraints ->
        val gap = 4.dp.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val basis = items.map { it.maxIntrinsicWidth(height) }
        val free = width - gap * (items.size - 1).coerceAtLeast(0) - basis.sum()
        // CSS flex: free room is shared equally (flex-grow 1); missing room is taken in proportion to the basis (flex-shrink 1).
        val total = basis.sum().coerceAtLeast(1)
        val widths = basis.map { b -> if (free >= 0) b + free.toFloat() / items.size else b + free.toFloat() * b / total }
        var x = 0f
        val placeables = items.mapIndexed { i, m ->
            val w = widths[i].roundToInt().coerceAtLeast(0)
            m.measure(Constraints.fixed(w, height))
        }
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                p.place(x.roundToInt(), 0)
                x += widths[i] + gap
            }
        }
    }
}

private const val PILL_TRACK: Argb = 0xFFE4E2DA
private const val PILL_ON: Argb = 0xFF14161B
private const val PILL_ON_INK: Argb = 0xFFFFFFFF
private const val PILL_INK: Argb = 0xFF14161B
private val TRACK = CssCorners(24f, 24f, 24f, 24f)
private val SEGMENT = CssCorners(20f, 20f, 20f, 20f)
