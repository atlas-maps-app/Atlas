package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.guide.Haptics
import app.atlas.model.Step
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.softShadow

/** The guidance screens' colours, from the Ride, Drive and Sail boards. */
object NavColours {
    val black = Color(0xFF000000)
    val panel = Color(0xFF121212)
    val panelHigh = Color(0xFF1A2238)
    val lime = Color(0xFFB8F36A)
    val end = Color(0xFFD93A3A)
    val amber = Color(0xFFFFB547)
    /** The Drive board's night: its background and its glass panels. */
    val night = Color(0xFF0B1020)
    val nightPanel = Color(0xE60C1120)
    val nightButton = Color(0xE00C1120)
    val signGreen = Color(0xFF0C6B3E)
    val eta = Color(0xFF7CE0A5)
    val text = Color(0xFFFFFFFF)
    val textSoft = Color(0xFFA9B1C7)
}

/**
 * The last thing the voice said, as the Drive and Sail boards show it: the speaking waveform and the line in quotes, on
 * night glass ([dark]) or on white.
 */
@Composable
fun VoiceBubble(text: String?, modifier: Modifier = Modifier, dark: Boolean = true) {
    if (text.isNullOrBlank()) return
    val bg = if (dark) Color(0xC70C1120) else Color(0xE6FFFFFF)
    val fg = if (dark) Color(0xFFDDE3F2) else Color(0xFF2A2E38)
    val wave = if (dark) Color(0xFF8FB0FF) else Color(0xFF2E5BFF)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (dark) Modifier else Modifier.softShadow(RoundedCornerShape(20.dp), Color(0x1F181C30), 10.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ArtBox(BoardArt.waveform.tinted, Modifier.padding(top = 1.dp).size(width = 36.dp, height = 20.dp), tint = wave)
        Text("“$text”", style = Atlas.type.body.copy(fontSize = 14.sp, lineHeight = 19.6.sp), color = fg, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Seven-segment figures, as on a tachograph's display: each digit drawn from its lit segments, the unlit ones faintly
 * there. Digits and ':' only.
 */
@Composable
fun SevenSegment(text: String, modifier: Modifier = Modifier, height: Dp = 34.dp, colour: Color = NavColours.amber) {
    val digitW = height * 0.56f
    val colonW = height * 0.24f
    val width = text.sumOf { (if (it == ':') colonW else digitW + height * 0.1f).value.toDouble() }.toFloat().dp
    Canvas(modifier.size(width, height).semantics { contentDescription = text }) {
        val h = size.height
        val dw = digitW.toPx()
        val cw = colonW.toPx()
        val gap = h * 0.1f
        val t = h * 0.11f
        var x = 0f
        for (ch in text) {
            if (ch == ':') {
                drawCircle(colour, t * 0.6f, Offset(x + cw / 2, h * 0.32f))
                drawCircle(colour, t * 0.6f, Offset(x + cw / 2, h * 0.7f))
                x += cw
                continue
            }
            val lit = SEGMENTS[ch] ?: 0
            for (seg in 0 until 7) {
                val on = lit and (1 shl seg) != 0
                drawSegment(seg, x, dw, h, t, if (on) colour else colour.copy(alpha = 0.035f))
            }
            x += dw + gap
        }
    }
}

/** Segments a–g as bits 0–6. */
private val SEGMENTS = mapOf(
    '0' to 0b0111111, '1' to 0b0000110, '2' to 0b1011011, '3' to 0b1001111, '4' to 0b1100110,
    '5' to 0b1101101, '6' to 0b1111101, '7' to 0b0000111, '8' to 0b1111111, '9' to 0b1101111, '-' to 0b1000000,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegment(seg: Int, x: Float, w: Float, h: Float, t: Float, c: Color) {
    val half = h / 2
    val inset = t * 0.6f
    fun horizontal(y: Float) = Path().apply {
        moveTo(x + inset, y); lineTo(x + inset + t / 2, y - t / 2); lineTo(x + w - inset - t / 2, y - t / 2)
        lineTo(x + w - inset, y); lineTo(x + w - inset - t / 2, y + t / 2); lineTo(x + inset + t / 2, y + t / 2); close()
    }
    fun vertical(cx: Float, y0: Float, y1: Float) = Path().apply {
        moveTo(cx, y0 + inset); lineTo(cx + t / 2, y0 + inset + t / 2); lineTo(cx + t / 2, y1 - inset - t / 2)
        lineTo(cx, y1 - inset); lineTo(cx - t / 2, y1 - inset - t / 2); lineTo(cx - t / 2, y0 + inset + t / 2); close()
    }
    val path = when (seg) {
        0 -> horizontal(t / 2)
        1 -> vertical(x + w - t / 2, t / 2, half)
        2 -> vertical(x + w - t / 2, half, h - t / 2)
        3 -> horizontal(h - t / 2)
        4 -> vertical(x + t / 2, half, h - t / 2)
        5 -> vertical(x + t / 2, t / 2, half)
        else -> horizontal(half)
    }
    drawPath(path, c)
}
