package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R

/** One row of a [DepartureBoard]: when, which line (its badge text and colour), where to, the platform and any delay. */
@Immutable
data class Departure(
    val time: String,
    val line: String,
    val lineColour: Color,
    val destination: String,
    val platform: String? = null,
    val delay: String? = null,
    val delayColour: Color = Color(0xFF7BE08F),
)

private val BOARD = Color(0xFF15171B)
private val CELL = Color(0xFF23262C)
private val AMBER = Color(0xFFFFC53D)
private val FLAP = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AMBER, letterSpacing = 0.04.sp)

/**
 * The split-flap departure board of the Transit board: amber letters on dark flaps, each flap split across the middle,
 * a coloured badge per line, the platform and the delay. Times and names come as the caller formats them.
 */
@Composable
fun DepartureBoard(departures: List<Departure>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BOARD)
            .border(2.dp, Color(0xFF2F333A), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Header(stringResource(R.string.kit_departure_time), Modifier.width(66.dp))
            Gap(36.dp)
            Header(stringResource(R.string.kit_departure_to), Modifier.weight(1f))
            Header(stringResource(R.string.kit_departure_platform), Modifier.width(30.dp))
            Header("+", Modifier.width(40.dp))
        }
        for (d in departures) DepartureRow(d)
    }
}

@Composable
private fun RowScope.Gap(width: Dp) = Box(Modifier.width(width))

@Composable
private fun Header(text: String, modifier: Modifier) =
    Text(text.uppercase(), modifier, style = Atlas.type.kicker.copy(fontFamily = FontFamily.Monospace), color = Color(0xFF8A8F98), maxLines = 1)

@Composable
private fun DepartureRow(d: Departure) {
    val description = buildString {
        append(d.time).append(", ").append(d.line).append(", ").append(d.destination)
        d.platform?.let { append(", ").append(it) }
        d.delay?.let { append(", ").append(it) }
    }
    Row(
        Modifier.fillMaxWidth().height(40.dp).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Flap(d.time, Modifier.width(66.dp))
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(d.lineColour), contentAlignment = Alignment.Center) {
            Text(d.line, style = Atlas.type.label.copy(fontWeight = FontWeight.ExtraBold), color = contentColourFor(d.lineColour), maxLines = 1)
        }
        Flap(d.destination.uppercase(), Modifier.weight(1f))
        Flap(d.platform ?: "", Modifier.width(30.dp))
        Text(d.delay ?: "", Modifier.width(40.dp), style = FLAP.copy(fontSize = 13.sp, color = d.delayColour), maxLines = 1)
    }
}

/** One flap: dark, lit from above, split across the middle. */
@Composable
private fun Flap(text: String, modifier: Modifier) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.verticalGradient(listOf(CELL, Color(0xFF1A1C21), CELL)))
            .drawWithContent {
                drawContent()
                val y = size.height / 2f
                drawLine(Color(0xFF0A0B0D), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                drawLine(Color(0x22FFFFFF), Offset(0f, y + 1.5.dp.toPx()), Offset(size.width, y + 1.5.dp.toPx()), strokeWidth = 0.5.dp.toPx())
            }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = FLAP, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
