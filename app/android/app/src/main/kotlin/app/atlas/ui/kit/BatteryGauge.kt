package app.atlas.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The car battery of the Charge board: a dark cell pack with lit segments, green when there is plenty, amber when it
 * runs low and red near empty, a bolt while [charging]; then the charge in per cent and the range ([rangeKm]).
 */
@Composable
fun BatteryGauge(
    soc: Float,
    modifier: Modifier = Modifier,
    rangeKm: Double? = null,
    charging: Boolean = false,
    cells: Int = 8,
    width: Dp = 92.dp,
    showText: Boolean = true,
    contentDescription: String? = null,
) {
    val known = !soc.isNaN()
    val level = fraction(soc)
    val pct = (level * 100).roundToInt()
    val range = rangeKm?.takeIf { it.isFinite() && it >= 0 }
    val c = Atlas.colors
    val fill = when {
        level >= 0.3f -> Color(0xFF2FB463)
        level >= 0.15f -> Color(0xFFF2A516)
        else -> Color(0xFFE5483F)
    }
    val percent = if (known) "$pct%" else NO_VALUE
    val description = contentDescription ?: buildString {
        append(percent)
        if (range != null) append(", ${range.roundToInt()} km")
    }
    Row(
        modifier.clearAndSetSemantics { this.contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width, width * 0.42f)) {
            val capW = size.width * 0.06f
            val bodyW = size.width - capW
            val h = size.height
            val radius = CornerRadius(h * 0.18f)
            // Body: dark, with a lighter rim.
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4A4E55), Color(0xFF1C1E22))), size = Size(bodyW, h), cornerRadius = radius)
            drawRoundRect(Color(0xFF2A2D33), Offset(h * 0.06f, h * 0.06f), Size(bodyW - h * 0.12f, h * 0.88f), CornerRadius(h * 0.13f))
            // The terminal cap.
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9CA0A6), Color(0xFF5B5F66))), Offset(bodyW, h * 0.3f), Size(capW, h * 0.4f), CornerRadius(capW * 0.4f))
            // Cells: lit up to the level, the partly filled one lit in proportion.
            val pad = h * 0.13f
            val gap = h * 0.06f
            val cellW = (bodyW - pad * 2 - gap * (cells - 1)) / cells
            val cellH = h - pad * 2
            for (i in 0 until cells) {
                val x = pad + i * (cellW + gap)
                drawRoundRect(Color(0xFF3A3E45), Offset(x, pad), Size(cellW, cellH), CornerRadius(cellW * 0.18f))
                val part = (level * cells - i).coerceIn(0f, 1f)
                if (part > 0f) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(fill.copy(alpha = 0.85f), fill, fill.copy(red = fill.red * 0.8f, green = fill.green * 0.8f, blue = fill.blue * 0.8f)), pad, pad + cellH),
                        Offset(x, pad), Size(cellW * part, cellH), CornerRadius(cellW * 0.18f),
                    )
                }
            }
            // Gloss along the top.
            drawRoundRect(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x00FFFFFF)), 0f, h * 0.5f), Offset(h * 0.06f, h * 0.06f), Size(bodyW - h * 0.12f, h * 0.4f), CornerRadius(h * 0.13f))
            if (charging) drawSymbolCentred("ui-bolt", Offset(bodyW / 2f, h / 2f), h * 0.62f, Color.White, weight = 1.3f)
        }
        if (showText) {
            Column {
                Text(percent, style = Atlas.type.numeralSmall, color = c.ink)
                if (range != null) Text("${range.roundToInt()} km", style = Atlas.type.caption, color = c.inkSoft)
            }
        }
    }
}
