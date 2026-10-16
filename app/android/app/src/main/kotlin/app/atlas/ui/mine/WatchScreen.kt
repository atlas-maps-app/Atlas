package app.atlas.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssRepeatingLinear
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.Units
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.appClock

/* The Watch board's ink (`canvas/project/Watch.dc.html`). */
private const val WATCH_INK = 0xFF1C1F24

/**
 * The Watch board, "On the wrist": a steel smartwatch showing the next turn of the trip being guided (with no trip, a
 * right turn as a preview), and the legend of vibrations you feel with no looking. Each row of the legend is a button
 * that makes the phone vibrate that way, so the code can be learnt in a minute; the guide uses the same patterns.
 */
@Composable
fun WatchScreen(args: Dest.Watch, nav: Navigator) {
    val services = LocalServices.current
    val session by services.go.nav.collectAsStateWithLifecycle()
    val view = session?.view?.collectAsStateWithLifecycle()?.value
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val zone = appClock().zone
    val state = view?.state
    val next = state?.next
    val glyph = if (next != null) WatchModel.glyph(next.maneuver.type, next.maneuver.turn) else WatchModel.Glyph.RIGHT
    val distance = state?.distanceToManeuver?.let { SettingsModel.distance(it, units) } ?: SettingsModel.distance(120.0, units)
    val street = next?.street ?: state?.let { null }
    val twelve by services.settings.flow(MineKeys.CLOCK_12, false).collectAsStateWithLifecycle(false)
    val eta = state?.eta?.let { ZonedLocal.hm(it, zone, twelve) }
    val second = if (state == null) stringResource(R.string.mine_watch_preview) else listOfNotNull(street, eta).joinToString(" · ")
    val progress = state?.progress?.toFloat() ?: 0.65f
    val description = stringResource(R.string.mine_watch_cd, distance, second)
    // the board's page: a radial light at 50 % 35 % from #F6F4EF to #DDD8CE at 75 %
    Box(Modifier.fillMaxSize().bbox(0xFFDDD8CE, grad = listOf(cssRadial(false, 206f, 320.25f, 0f to 0xFFF6F4EF, 0.75f to 0xFFDDD8CE)))) {
        BoardPage(
            title = stringResource(R.string.mine_watch_title),
            onBack = { nav.back() },
            background = Color.Transparent,
            ink = Color(WATCH_INK),
            titleSize = 32f,
            top = 10.dp,
            gap = 0.dp,
            bottomPadding = 20.dp,
        ) {
            Box(Modifier.fillMaxWidth().height(504.dp)) {
                // The strap (140 × 480, radius 30, its sheen across), its ribbing every 20 px and the crown.
                Box(Modifier.offset(120.dp, 0.dp).size(140.dp, 480.dp).bbox(r = cr(30f), grad = listOf(cssLinear(90f, 0f to 0xFF262A30, 0.5f to 0xFF3A3F46, 1f to 0xFF262A30))))
                Box(Modifier.offset(120.dp, 14.dp).size(140.dp, 452.dp).bbox(r = cr(26f), grad = listOf(cssRepeatingLinear(180f, 20f, 0f to 0x00000000, 18f to 0x00000000, 18f to 0x40000000, 20f to 0x40000000))))
                Box(Modifier.offset(336.dp, 222.dp).size(14.dp, 34.dp).bbox(r = cr(4f), grad = listOf(cssLinear(90f, 0f to 0xFF8A929C, 1f to 0xFFE5E8EC))))
                WatchFace(glyph, distance, second, progress, Modifier.offset(40.dp, 90.dp).size(300.dp).semantics { contentDescription = description })
            }
            Legend(onFeel = { g -> services.voice.play(WatchModel.haptic(g)) })
        }
    }
}

/** The watch itself: steel bezel, 60 ticks, a progress ring, the pulses, the turn arrow, the distance and the street and time. */
@Composable
private fun WatchFace(glyph: WatchModel.Glyph, distance: String, second: String, progress: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val big = bt(30f, 900)
    val small = bt(13f, 700)
    Canvas(modifier) {
        val k = size.minDimension / 300f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        // SVG text, its baseline at y and centred on x, the board's face at its size in the 300-unit square
        fun text(s: String, x: Float, y: Float, style: androidx.compose.ui.text.TextStyle, colour: Color) {
            val t = measurer.measure(s, style.copy(fontSize = style.fontSize * (k / density)))
            drawText(t, colour, Offset(p(x, y).x - t.size.width / 2f, p(x, y).y - t.firstBaseline))
        }
        drawCircle(Brush.linearGradient(0f to Color(0xFFF2F4F6), 0.45f to Color(0xFF8A929C), 1f to Color(0xFFD5D9DF), start = p(2f, 2f), end = p(298f, 298f)), 148f * k, p(150f, 150f))
        drawCircle(Color(0xFF050608), 136f * k, p(150f, 150f))
        for ((i, t) in WatchModel.ticks().withIndex()) drawLine(Color(0xFF8A8F98), p(t[0], t[1]), p(t[2], t[3]), (if (i % 5 == 0) 2f else 1f) * k)
        drawCircle(Color(0xFF1E2A22), 112f * k, p(150f, 150f), style = Stroke(8f * k))
        val arc = Path().apply {
            moveTo(150f * k, 38f * k)
            arcTo(androidx.compose.ui.geometry.Rect(p(38f, 38f), p(262f, 262f)), -90f, progress.coerceIn(0f, 0.9999f) * 360f, false)
        }
        drawPath(arc, Color(0xFF3FCF8E), style = Stroke(8f * k, cap = StrokeCap.Round))
        // The pulses the turn is felt with, as the board's "● ● ●" (12 px, its baseline at 80) draws them: dots 10.5 apart.
        val n = WatchModel.dots(glyph)
        for (i in 0 until n) drawCircle(Color(0xFF3FCF8E), 2.7f * k, p(150f + (i - (n - 1) / 2f) * 10.5f, 75.7f))
        arrow(glyph, k)
        text(distance, 150f, 216f, big, Color.White)
        text(second, 150f, 236f, small, Color(0xFF9AA2B2))
    }
}

/** The turn arrow as the board draws it: a white 15-unit stroke with a filled head (mirrored for a left turn). */
private fun DrawScope.arrow(g: WatchModel.Glyph, k: Float) {
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val white = Color.White
    val stroke = Stroke(15f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        WatchModel.Glyph.RIGHT, WatchModel.Glyph.LEFT -> {
            val sx = if (g == WatchModel.Glyph.LEFT) -1f else 1f
            fun x(v: Float) = 150f + (v - 150f) * sx
            val body = Path().apply {
                moveTo(x(114f) * k, 180f * k); lineTo(x(114f) * k, 136f * k)
                arcTo(androidx.compose.ui.geometry.Rect(p(minOf(x(114f), x(142f)), 108f), p(maxOf(x(114f), x(142f)), 136f)), if (sx > 0) 180f else 0f, if (sx > 0) 90f else -90f, false)
                lineTo(x(164f) * k, 122f * k)
            }
            drawPath(body, white, style = stroke)
            val head = Path().apply { moveTo(x(158f) * k, 99f * k); lineTo(x(183f) * k, 122f * k); lineTo(x(158f) * k, 145f * k); close() }
            drawPath(head, white)
        }
        WatchModel.Glyph.U_TURN -> {
            val body = Path().apply { moveTo(130f * k, 180f * k); lineTo(130f * k, 120f * k); arcTo(androidx.compose.ui.geometry.Rect(p(130f, 96f), p(178f, 144f)), 180f, 180f, false); lineTo(178f * k, 150f * k) }
            drawPath(body, white, style = stroke)
            drawPath(Path().apply { moveTo(160f * k, 150f * k); lineTo(196f * k, 150f * k); lineTo(178f * k, 176f * k); close() }, white)
        }
        WatchModel.Glyph.ARRIVE -> {
            drawLine(white, p(124f, 96f), p(124f, 184f), 10f * k, StrokeCap.Round)
            drawPath(Path().apply { moveTo(124f * k, 98f * k); lineTo(184f * k, 98f * k); lineTo(168f * k, 118f * k); lineTo(184f * k, 138f * k); lineTo(124f * k, 138f * k); close() }, white)
        }
        WatchModel.Glyph.STRAIGHT -> {
            drawLine(white, p(150f, 186f), p(150f, 120f), 15f * k, StrokeCap.Round)
            drawPath(Path().apply { moveTo(122f * k, 128f * k); lineTo(150f * k, 94f * k); lineTo(178f * k, 128f * k); close() }, white)
        }
    }
}


/**
 * "Feel the way", as the board's card (radius 24, 16 px in, 12 below): the label (12, 800, 1.44 px apart), then a row for
 * each rhythm (33 px: a hairline and 10 px around 12 px dots and 34 px bars, 6 px apart) beside what it means (14, 700),
 * and the note (12, grey). Tap a row to feel it on the phone.
 */
@Composable
private fun Legend(onFeel: (WatchModel.Glyph) -> Unit) {
    Column(Modifier.fillMaxWidth().bbox(WHITE, cr(24f)).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
        BText(stringResource(R.string.mine_watch_feel).uppercase(), bt(12f, 800, 15.5f, spacing = 1.44f), LABEL, Modifier.padding(bottom = 9.dp).semantics { heading() })
        for ((g, label) in listOf(
            WatchModel.Glyph.LEFT to R.string.mine_watch_left,
            WatchModel.Glyph.RIGHT to R.string.mine_watch_right,
            WatchModel.Glyph.U_TURN to R.string.mine_watch_wrong,
            WatchModel.Glyph.ARRIVE to R.string.mine_watch_arrived,
        )) {
            val name = stringResource(label)
            Row(
                Modifier.fillMaxWidth().bbox(border = hairTop(0xFFEEEAE0)).clickable(onClickLabel = stringResource(R.string.mine_watch_feel_it)) { onFeel(g) }
                    .semantics { contentDescription = name }.padding(top = 8.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.width(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (long in WatchModel.rhythm(g)) Box(Modifier.size(if (long) 34.dp else 12.dp, 12.dp).bbox(WATCH_INK, cr(6f)))
                }
                BText(name, bt(14f, 700, 18f), WATCH_INK)
            }
        }
        BText(stringResource(R.string.mine_watch_speeds), bt(12f, 400, 15.5f), LABEL, Modifier.padding(top = 8.dp), lines = 3)
    }
}
