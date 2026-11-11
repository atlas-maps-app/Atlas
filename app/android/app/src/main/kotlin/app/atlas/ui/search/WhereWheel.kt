package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.places.SvgPath
import app.atlas.ui.kit.ComposePathSink
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The spin wheel of the Where board, its own SVG taken as it is: flat wedges turned 12 degrees, a cream rim, a white glyph
 * in each wedge, the dark pointer, a faint shadow disc and the cream hub, all in the board's 300 × 320 frame. A wedge with
 * nothing near that fits is flat grey. The SPIN button is the board's: 72 px, dark, in 900 weight with 0.06 em
 * letter-spacing. The wedges' colours are theirs ([Wedge.colour]).
 */
internal object WheelArt {
    const val WIDTH = 300f
    const val HEIGHT = 320f
    const val BASE_TURN = 12f

    /** The wheel's middle in the board's frame, its rim's radius, and how far out the glyphs sit. */
    val HUB = Offset(150f, 168f)
    const val RADIUS = 140f
    private const val GLYPH_RING = 99.5f

    val DARK = Color(0xFF1E1830)
    val CREAM = Color(0xFFFBF3E6)
    val SPIN_TEXT = Color(0xFFFFE3C4)

    /**
     * A wedge with nothing near that fits: flat grey with its glyph a darker grey, so it reads as off. Washed three
     * quarters to grey, as it was, the sunset's amber came out tan and read as one more colour.
     */
    val OFF = Color(0xFFD6D0C6)
    val OFF_GLYPH = Color(0xFFA39C91)

    /** One white glyph of the board: its path centred on 0, 0 in its own frame, and how it is drawn. */
    class Glyph(val d: String, val scale: Float = 1f, val stroke: Float? = null, val round: Boolean = true)

    /**
     * Each wedge's glyph, by the wedge's id: the thing behind it, as the app draws it elsewhere where it can (10 November,
     * judged on the wedge's colour at the card's 26 px and the wheel's 30, upright and turned).
     */
    val GLYPHS: Map<String, Glyph> = mapOf(
        "water" to Glyph("M0-10s7 7.5 7 12.5a7 7 0 0 1-14 0C-7-2.5 0-10 0-10z", 1.2f),
        // the board's sun on the horizon, made to read: half a sun with three rays over the horizon and a shorter line of
        // water; its whole disc filled the card's frame and read as a plain white dot
        "views" to Glyph("M-10.5 4h21M-6 4a6 6 0 0 1 12 0M0-5.5V-9M-7.25-3.5l-2-2M7.25-3.5l2-2M-6 8.5h12", stroke = 2.4f),
        // filled: three cut tines and a solid blade over a narrower handle; drawn in lines, the tines ran into a block and
        // the hollow blade read as a 9
        "food" to Glyph("M-9.6-11h1.6v7h1.2v-7h1.6v7h1.2v-7h1.6v7.5c0 1.6-1 2.9-2.4 3.3V9.8a1.2 1.2 0 0 1-2.4 0V-.2C-8.6-.6-9.6-1.9-9.6-3.5zM9.6-11.2C6.8-10 5-6.5 5-2.5v4h2.2v8.3a1.2 1.2 0 0 0 2.4 0z"),
        // the app's candle: worship's symbol and the Relax screen's Stillness, which no one faith owns; most quiet places
        // are churches and the like, the fewer libraries
        "quiet" to Glyph("M-3 9V-1h6v10M-6.5 9h13M0-9.5c-.8 1.2-1.2 2.2-1.2 3a1.2 1.2 0 0 0 2.4 0c0-.8-.4-1.8-1.2-3z", stroke = 2.4f),
        "night" to Glyph("M8 2A9 9 0 0 1-4-10a9 9 0 1 0 12 12z"),
        // the fir on its trunk (a trunk drawn as a bare line never showed in a filled glyph, and the fir floated)
        "nature" to Glyph("M-1.5 6.5h3v5h-3zM0-12l-8 12h5l-5 7h16l-5-7h5z"),
        // the columned front, the Culture & sights family's own landmark and the app's museum
        "culture" to Glyph("M-.88-9.8a2 2 0 0 1 1.76 0l7.84 3.85A.5 .5 0 0 1 8.5-5h-17a.5 .5 0 0 1-.22-.95zM-6-1v6M-2-1v6M2-1v6M6-1v6M-9 9.5h18", stroke = 2.4f),
        // the sights' camera; a star read as the app's starred
        "odd" to Glyph("M2-8a2 2 0 0 1 1.76 1.05l.49.9A2 2 0 0 0 6-5h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H-8a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2h2a2 2 0 0 0 1.76-1.05l.49-.9A2 2 0 0 1-2-8zM-3 1.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0z", stroke = 2.4f),
    )

    fun glyph(w: Wedge): Glyph = GLYPHS.getValue(w.id)

    /** Where wedge [index] of [count] holds its glyph, from the wheel's middle before it turns: halfway along its arc. */
    fun glyphAt(index: Int, count: Int): Offset {
        val a = (index + 0.5) * 2 * PI / count
        return Offset((GLYPH_RING * sin(a)).toFloat(), (-GLYPH_RING * cos(a)).toFloat())
    }

    /**
     * The angle to spin to from [from] so the middle of wedge [index] of [count] stops under the pointer, after at least
     * [turns] whole turns [clockwise] or against.
     */
    fun landing(from: Float, index: Int, count: Int, clockwise: Boolean, turns: Int = 4): Float {
        val sweep = 360f / count
        val target = -(index * sweep + sweep / 2 + BASE_TURN)
        return if (clockwise) {
            val base = from + 360f * turns
            base + ((target - base) % 360f + 360f) % 360f
        } else {
            val base = from - 360f * turns
            base - ((base - target) % 360f + 360f) % 360f
        }
    }

    /** The board's pointer, in the 300 × 320 frame. */
    const val POINTER = "M136 4h28l-14 30z"

    fun path(d: String): Path = Path().also { SvgPath.parse(d).replay(ComposePathSink(it)) }
}

/** [g] drawn as the board draws it, filled or stroked, in [colour], about the frame's 0, 0. */
private fun DrawScope.drawGlyph(g: WheelArt.Glyph, path: Path, colour: Color) {
    scale(g.scale, g.scale, pivot = Offset.Zero) {
        if (g.stroke == null) drawPath(path, colour)
        else drawPath(path, colour, style = Stroke(g.stroke, cap = if (g.round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round))
    }
}

/** The wheel small and whole, for a way to it: its wedges in their own colours round its hub, under its pointer. */
internal fun DrawScope.smallWheel() {
    val r = size.minDimension / 2f
    val wedges = WherePicker.WEDGES
    val sweep = 360f / wedges.size
    rotate(WheelArt.BASE_TURN) {
        for ((i, w) in wedges.withIndex()) drawArc(Color(w.colour), -90f + i * sweep, sweep, useCenter = true)
    }
    drawCircle(WheelArt.CREAM, r * 0.36f)
    drawCircle(WheelArt.DARK, r * 0.27f)
    val tip = Path().apply {
        moveTo(center.x - r * 0.3f, 0f)
        lineTo(center.x + r * 0.3f, 0f)
        lineTo(center.x, r * 0.52f)
        close()
    }
    drawPath(tip, WheelArt.CREAM, style = Stroke(r * 0.14f, join = StrokeJoin.Round))
    drawPath(tip, WheelArt.DARK)
}

/** How fast a finger has to leave the wheel for it to spin, in degrees a second; slower, the wheel stays where it was left. */
private const val FLING = 200f

/**
 * The wheel: [angle] says how far it has turned (degrees), read only while drawing so a spin redraws without
 * recomposing; [places] says which wedges have something to land on. A finger on the wheel takes it round ([onTurn], in
 * degrees), and let go with a flick it spins that way ([onFling], true clockwise). Its glyphs stay upright however it
 * turns, so the one under the pointer reads as drawn.
 */
@Composable
internal fun Wheel(
    places: WheelPlaces?,
    angle: () -> Float,
    onSpin: () -> Unit,
    onTurn: (Float) -> Unit,
    onFling: (Boolean) -> Unit,
    lang: String,
    modifier: Modifier = Modifier,
) {
    val wedges = WherePicker.WEDGES
    val spinLabel = stringResource(R.string.where_spin)
    val glyphs = remember { wedges.map { WheelArt.path(WheelArt.glyph(it).d) } }
    val pointer = remember { WheelArt.path(WheelArt.POINTER) }
    val description = wedges.map { stringResource(it.label) }.joinToString()
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(WheelArt.WIDTH / WheelArt.HEIGHT)) {
        val unit = maxWidth.value / WheelArt.WIDTH
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(WheelArt.WIDTH / WheelArt.HEIGHT)
                .semantics { contentDescription = description }
                .pointerInput(Unit) { turnByHand(onTurn, onFling) },
        ) {
            val k = size.width / WheelArt.WIDTH
            scale(k, k, pivot = Offset.Zero) {
                // The board's order: the pointer first, its tip under the wheel's rim.
                drawPath(pointer, WheelArt.DARK)
                drawPath(pointer, WheelArt.CREAM, style = Stroke(3f, join = StrokeJoin.Round))
                drawCircle(WheelArt.DARK.copy(alpha = 0.12f), 146f, Offset(150f, 172f))
                val turn = angle() + WheelArt.BASE_TURN
                translate(WheelArt.HUB.x, WheelArt.HUB.y) {
                    rotate(turn, Offset.Zero) {
                        val sweep = 360f / wedges.size
                        for ((i, w) in wedges.withIndex()) {
                            val off = places != null && places.of(w).isEmpty()
                            drawArc(if (off) WheelArt.OFF else Color(w.colour), -90f + i * sweep, sweep, true, Offset(-WheelArt.RADIUS, -WheelArt.RADIUS), Size(2 * WheelArt.RADIUS, 2 * WheelArt.RADIUS))
                        }
                        drawCircle(WheelArt.CREAM, WheelArt.RADIUS, Offset.Zero, style = Stroke(4f))
                        for ((i, w) in wedges.withIndex()) {
                            val off = places != null && places.of(w).isEmpty()
                            val at = WheelArt.glyphAt(i, wedges.size)
                            translate(at.x, at.y) {
                                rotate(-turn, Offset.Zero) { drawGlyph(WheelArt.glyph(w), glyphs[i], if (off) WheelArt.OFF_GLYPH else Color.White) }
                            }
                        }
                    }
                }
                drawCircle(WheelArt.CREAM, 30f, WheelArt.HUB)
                drawCircle(WheelArt.DARK, 30f, WheelArt.HUB, style = Stroke(3f))
            }
        }
        // The board's SPIN button: 72 px, centred on the hub, 900 weight, 0.06 em apart; a longer word shrinks to fit.
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
                Modifier.padding(horizontal = (9f * unit).dp),
                style = TextStyle(fontWeight = FontWeight.Black, letterSpacing = 0.06.em, textAlign = TextAlign.Center),
                color = WheelArt.SPIN_TEXT, maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = (9f * unit).sp, maxFontSize = (15f * unit).sp, stepSize = (0.5f * unit).sp),
            )
        }
    }
}

/**
 * The wheel turned by hand: a finger down on its disc takes it round, and let go with a flick it spins the way it was
 * flicked. A touch off the disc is left to the page, which still scrolls there.
 */
private suspend fun PointerInputScope.turnByHand(onTurn: (Float) -> Unit, onFling: (Boolean) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val k = size.width / WheelArt.WIDTH
        val hub = WheelArt.HUB * k
        if ((down.position - hub).getDistance() > WheelArt.RADIUS * k) return@awaitEachGesture
        val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        var last = bearing(start.position - hub)
        onTurn(shortest(last - bearing(down.position - hub)))
        // the last tenth of a second of the turn, to tell a flick from a finger lifted off
        val recent = ArrayDeque<Pair<Long, Float>>()
        var turned = 0f
        recent.addLast(start.uptimeMillis to turned)
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val now = bearing(change.position - hub)
            val d = shortest(now - last)
            last = now
            turned += d
            onTurn(d)
            change.consume()
            recent.addLast(change.uptimeMillis to turned)
            while (recent.size > 2 && change.uptimeMillis - recent.first().first > 100) recent.removeFirst()
        }
        val (t0, a0) = recent.first()
        val (t1, a1) = recent.last()
        val speed = if (t1 > t0) (a1 - a0) * 1000f / (t1 - t0) else 0f
        if (abs(speed) > FLING) onFling(speed > 0f)
    }
}

/** Which way [v] points from the wheel's middle, in degrees clockwise from straight up, as the wheel turns. */
private fun bearing(v: Offset): Float = Math.toDegrees(atan2(v.x, -v.y).toDouble()).toFloat()

/** [d] the short way round: between -180 and 180 degrees. */
private fun shortest(d: Float): Float = ((d % 360f) + 540f) % 360f - 180f

/**
 * The glyph of wedge [w] on its own, [side] px, as the board's result card shows its kind (its 24 px frame centred on the
 * glyph).
 */
@Composable
internal fun WedgeGlyph(w: Wedge, side: Float) {
    val g = WheelArt.glyph(w)
    val path = remember(w.id) { WheelArt.path(g.d) }
    Canvas(Modifier.size(side.dp)) {
        val k = size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            translate(12f, 12.5f) { drawGlyph(g, path, Color.White) }
        }
    }
}
