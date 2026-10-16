package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.places.SvgPath
import app.atlas.ui.kit.ComposePathSink
import java.util.Locale

/**
 * The spin wheel of the Where board, its own SVG taken as it is: eight flat wedges in the board's colours turned 12
 * degrees, a cream rim, a white glyph in each wedge, the dark pointer, a faint shadow disc and the cream hub, all in the
 * board's 300 × 320 frame. A wedge with nothing near that fits is washed grey. The SPIN button is the board's: 72 px,
 * dark, in 900 weight with 0.06 em letter-spacing.
 */
internal object WheelArt {
    const val WIDTH = 300f
    const val HEIGHT = 320f
    const val BASE_TURN = 12f
    val DARK = Color(0xFF1E1830)
    val CREAM = Color(0xFFFBF3E6)
    val SPIN_TEXT = Color(0xFFFFE3C4)

    /** The wedge fills, clockwise from the top: water, views, food, quiet, night, nature, culture, odd. */
    val FILLS = longArrayOf(0xFF1560A8, 0xFFF2A71B, 0xFFE8622C, 0xFF6E9E5A, 0xFF2A2350, 0xFF2F6B3A, 0xFFB5532E, 0xFF7B4DFF)

    /** One white glyph of the board: its path in its own frame, where it sits in the wheel, and how it is drawn. */
    class Glyph(val d: String, val x: Float, val y: Float, val scale: Float = 1f, val stroke: Float? = null, val round: Boolean = true)

    val GLYPHS = listOf(
        Glyph("M0-10s7 7.5 7 12.5a7 7 0 0 1-14 0C-7-2.5 0-10 0-10z", 38f, -92f, 1.2f),
        Glyph("M80-24h24", 0f, 0f, stroke = 3f, round = false),
        Glyph("M-8-10v8M-10-10v5a2 2 0 0 0 4 0v-5M-8-2v12M8 10v-20c-3 1-4 4-4 8h4", 92f, 38f, stroke = 2.4f),
        Glyph("M-10 4h20M-8 4c0-4 3-8 8-8s8 4 8 8M-2 -8l2-4 2 4", 38f, 92f, stroke = 2.4f),
        Glyph("M8 2A9 9 0 0 1-4-10a9 9 0 1 0 12 12z", -38f, 92f),
        Glyph("M0 12V4M0-12l-8 12h5l-5 7h16l-5-7h5z", -92f, 38f),
        Glyph("M-10 10v-12l10-8 10 8v12zM-4 10v-7h8v7", -92f, -38f, stroke = 2.4f),
        Glyph("M0-10l2.5 7h7l-5.5 4 2 7-6-4-6 4 2-7-5.5-4h7z", -38f, -92f),
    )

    /** The board's pointer, in the 300 × 320 frame. */
    const val POINTER = "M136 4h28l-14 30z"

    fun path(d: String): Path = Path().also { SvgPath.parse(d).replay(ComposePathSink(it)) }
}

/**
 * The wheel: [angle] says how far it has turned (degrees), read only while drawing so a spin redraws without
 * recomposing; [places] says which wedges have something to land on.
 */
@Composable
internal fun Wheel(places: WheelPlaces?, angle: () -> Float, onSpin: () -> Unit, lang: String, modifier: Modifier = Modifier) {
    val wedges = WherePicker.WEDGES
    val spinLabel = stringResource(R.string.where_spin)
    val glyphs = remember { WheelArt.GLYPHS.map { WheelArt.path(it.d) } }
    val pointer = remember { WheelArt.path(WheelArt.POINTER) }
    val description = wedges.map { stringResource(it.label) }.joinToString()
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(WheelArt.WIDTH / WheelArt.HEIGHT)) {
        val unit = maxWidth.value / WheelArt.WIDTH
        Canvas(Modifier.fillMaxWidth().aspectRatio(WheelArt.WIDTH / WheelArt.HEIGHT).semantics { contentDescription = description }) {
            val k = size.width / WheelArt.WIDTH
            scale(k, k, pivot = Offset.Zero) {
                // The board's order: the pointer first, its tip under the wheel's rim.
                drawPath(pointer, WheelArt.DARK)
                drawPath(pointer, WheelArt.CREAM, style = Stroke(3f, join = StrokeJoin.Round))
                drawCircle(WheelArt.DARK.copy(alpha = 0.12f), 146f, Offset(150f, 172f))
                translate(150f, 168f) {
                    rotate(angle() + WheelArt.BASE_TURN, Offset.Zero) {
                        val sweep = 360f / wedges.size
                        for ((i, w) in wedges.withIndex()) {
                            val base = Color(WheelArt.FILLS[i])
                            val empty = places != null && places.of(w).isEmpty()
                            drawArc(if (empty) lerp(base, Color(0xFFBDB7AC), 0.75f) else base, -90f + i * sweep, sweep, true, Offset(-140f, -140f), Size(280f, 280f))
                        }
                        drawCircle(WheelArt.CREAM, 140f, Offset.Zero, style = Stroke(4f))
                        for ((i, g) in WheelArt.GLYPHS.withIndex()) {
                            val empty = places != null && places.of(wedges[i]).isEmpty()
                            val colour = Color.White.copy(alpha = if (empty) 0.6f else 1f)
                            translate(g.x, g.y) {
                                scale(g.scale, g.scale, pivot = Offset.Zero) {
                                    if (g.stroke == null) drawPath(glyphs[i], colour)
                                    else drawPath(
                                        glyphs[i], colour,
                                        style = Stroke(g.stroke, cap = if (g.round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round),
                                    )
                                }
                            }
                        }
                        // The second glyph of the board, the sun's disc, sits with its line: a circle of radius 9 at (92, -38).
                        drawCircle(Color.White.copy(alpha = if (places != null && places.of(wedges[1]).isEmpty()) 0.6f else 1f), 9f, Offset(92f, -38f))
                    }
                }
                drawCircle(WheelArt.CREAM, 30f, Offset(150f, 168f))
                drawCircle(WheelArt.DARK, 30f, Offset(150f, 168f), style = Stroke(3f))
            }
        }
        // The board's SPIN button: 72 px, centred on the hub, 900 weight, 0.06 em apart.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(maxWidth * (114f / WheelArt.WIDTH), maxHeight * (132f / WheelArt.HEIGHT))
                .size(maxWidth * (72f / WheelArt.WIDTH))
                .clip(CircleShape)
                .background(WheelArt.DARK)
                .clickable(role = Role.Button, onClickLabel = spinLabel, onClick = onSpin)
                .semantics { contentDescription = spinLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                spinLabel.uppercase(Locale.getDefault()),
                style = TextStyle(fontWeight = FontWeight.Black, fontSize = (15f * unit).sp, letterSpacing = (15f * unit * 0.06f).sp),
                color = WheelArt.SPIN_TEXT, maxLines = 1,
            )
        }
    }
}

/**
 * The white glyph of wedge [index] on its own, [side] px, as the board's result card shows its kind (its 24 px frame
 * centred on the glyph).
 */
@Composable
internal fun WedgeGlyph(index: Int, side: Float) {
    val g = WheelArt.GLYPHS[index]
    val path = remember(index) { WheelArt.path(g.d) }
    Canvas(Modifier.size(side.dp)) {
        val k = size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            // The wheel places each glyph by its middle; on the card it sits in the middle of the 24 px frame.
            // (the card's drop is the wheel's at its own size, as the board draws it in a 24 px frame at 12, 12.5)
            val (dx, dy) = if (index == 1) (12f - 92f) to (12.5f + 35f) else 12f to 12.5f
            translate(dx, dy) {
                if (g.stroke == null) drawPath(path, Color.White)
                else drawPath(path, Color.White, style = Stroke(g.stroke, cap = if (g.round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round))
                if (index == 1) drawCircle(Color.White, 9f, Offset(92f, -38f))
            }
        }
    }
}
