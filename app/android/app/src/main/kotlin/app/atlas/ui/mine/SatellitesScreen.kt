package app.atlas.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.GnssInfo
import app.atlas.services.LocationQuality
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import kotlinx.coroutines.flow.collect
import java.time.Duration

private val Ink = Color(0xFFE6ECFF)
private val Panel = Color(0xFF121936)
private val Muted = Color(0xFF9AA6D0)

/**
 * The Satellites board: a polar sky plot of what the phone's GNSS chip hears (circles for GPS, squares for Galileo,
 * diamonds for GLONASS, triangles for BeiDou; hollow means not used in the fix), the signal strength of the satellites in
 * use, and the accuracy. It reads the chip's own report (`GnssStatus`) and adds nothing; with no GPS running it says so.
 */
@Composable
fun SatellitesScreen(args: Dest.Satellites, nav: Navigator) {
    val services = LocalServices.current
    val gnss by services.location.gnss.collectAsStateWithLifecycle()
    val fix by services.location.last.collectAsStateWithLifecycle()
    // The GPS runs while this screen is shown, so the chip has something to report.
    LaunchedEffect(Unit) { services.location.fixes(LocationQuality.MAP).collect { } }
    val info = gnss
    BoardPage(
        title = stringResource(R.string.mine_sat_title),
        onBack = { nav.back() },
        background = Color(0xFF080C20),
        ink = Ink,
        backFill = Color(0x1AFFFFFF),
        titleSize = 34f,
        trailing = {
            fix?.accuracy?.takeIf { it.isFinite() }?.let { a ->
                Box(Modifier.height(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x247CFFB2)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("± ${if (a < 10) String.format(java.util.Locale.ROOT, "%.1f", a) else Math.round(a).toString()} m", style = mt(12f, 800, tabular = true), color = Color(0xFF7CFFB2))
                }
            }
        },
    ) {
        if (info == null || info.satellites.isEmpty()) {
            StateMessage("local.gps", stringResource(R.string.mine_sat_none), text = stringResource(R.string.mine_sat_none_text), tint = Muted)
        } else {
            Plot(info, Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(340.dp))
            Legend(info)
            Strength(info)
            Tiles(info, fix?.time)
        }
        Text(stringResource(R.string.mine_sat_footer), style = mt(10f, 400, line = 15f), color = Color(0xFF6A76A0), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** The sky plot of the board (340 units wide): rings of 0°, 30° and 60° elevation, the compass letters, one mark per satellite. */
@Composable
private fun Plot(info: GnssInfo, modifier: Modifier) {
    val named = SatellitesModel.named(info)
    val description = stringResource(R.string.mine_sat_plot_cd, info.visible, SatellitesModel.systems(info), info.used)
    Canvas(modifier.semantics { contentDescription = description }) {
        val k = minOf(size.width, size.height) / 340f
        val ox = (size.width - 340f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, y * k)
        drawCircle(Color(0xFF0B1024), 160f * k, p(170f, 170f))
        drawCircle(Color(0xFF2A3460), 160f * k, p(170f, 170f), style = Stroke(2f * k))
        for (r in listOf(150f, 100f, 50f)) drawCircle(Color(0xFF1E2748), r * k, p(170f, 170f), style = Stroke(1.2f * k))
        drawLine(Color(0xFF1E2748), p(170f, 20f), p(170f, 320f), 1.2f * k)
        drawLine(Color(0xFF1E2748), p(20f, 170f), p(320f, 170f), 1.2f * k)
        text("N", p(170f, 16f), 11f * k, Muted, bold = true)
        text("E", p(330f, 174f), 11f * k, Muted, bold = true)
        text("S", p(170f, 336f), 11f * k, Muted, bold = true)
        text("W", p(10f, 174f), 11f * k, Muted, bold = true)
        text("30°", p(176f, 70f), 9f * k, Color(0xFF5A6690))
        text("60°", p(176f, 120f), 9f * k, Color(0xFF5A6690))
        for (s in info.satellites.sortedBy { it.usedInFix }) {
            val c = Constellation.of(s.constellation) ?: continue
            val (x, y) = SatellitesModel.point(s.azimuth, s.elevation)
            mark(c, p(x, y), k, s.usedInFix)
            if (s in named) text(SatellitesModel.label(s), p(x + 9f, y - 7f), 8f * k, Muted, mono = true, left = true)
        }
        drawCircle(Color.White, 4f * k, p(170f, 170f))
    }
}

/** One satellite's mark: the system's shape, filled when used in the fix, an outline when not (the board's 2-unit stroke). */
private fun DrawScope.mark(c: Constellation, at: Offset, k: Float, used: Boolean) {
    val colour = Color(c.colour)
    val stroke = Stroke(2f * k)
    when (c.shape) {
        Constellation.Shape.CIRCLE -> if (used) drawCircle(colour, 7f * k, at) else drawCircle(colour, 7f * k, at, style = stroke)
        Constellation.Shape.SQUARE -> {
            val tl = Offset(at.x - 6.5f * k, at.y - 6.5f * k)
            if (used) drawRect(colour, tl, Size(13f * k, 13f * k)) else drawRect(colour, tl, Size(13f * k, 13f * k), style = stroke)
        }
        Constellation.Shape.DIAMOND -> {
            val d = Path().apply { moveTo(at.x, at.y - 8f * k); lineTo(at.x + 8f * k, at.y); lineTo(at.x, at.y + 8f * k); lineTo(at.x - 8f * k, at.y); close() }
            if (used) drawPath(d, colour) else drawPath(d, colour, style = stroke)
        }
        Constellation.Shape.TRIANGLE -> {
            val d = Path().apply { moveTo(at.x, at.y - 6.5f * k); lineTo(at.x + 8f * k, at.y + 6.5f * k); lineTo(at.x - 8f * k, at.y + 6.5f * k); close() }
            if (used) drawPath(d, colour) else drawPath(d, colour, style = stroke)
        }
    }
}

private fun DrawScope.text(s: String, at: Offset, size: Float, colour: Color, bold: Boolean = false, mono: Boolean = false, left: Boolean = false) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour.toArgb()
        textAlign = if (left) android.graphics.Paint.Align.LEFT else android.graphics.Paint.Align.CENTER
        typeface = if (mono) android.graphics.Typeface.MONOSPACE else if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }
    drawContext.canvas.nativeCanvas.drawText(s, at.x, at.y + if (left) 0f else size * 0.35f, paint)
}

/** The four systems with their mark and "used/in view". */
@Composable
private fun Legend(info: GnssInfo) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for ((c, used, seen) in SatellitesModel.counts(info)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(16.dp)) { mark(c, Offset(size.width / 2, size.height / 2), size.width / 16f * 0.95f, true) }
            Text(stringResource(
                when (c) { Constellation.GPS -> R.string.mine_sat_gps; Constellation.GALILEO -> R.string.mine_sat_galileo; Constellation.GLONASS -> R.string.mine_sat_glonass; else -> R.string.mine_sat_beidou },
            ), style = mt(12f, 800), color = Ink)
            Text("$used/$seen", style = mt(12f, 500, tabular = true), color = Muted)
        }
    }
}

/** The bars of the board: the satellites in use, strongest first, each in its system's colour. */
@Composable
private fun Strength(info: GnssInfo) {
    val bars = SatellitesModel.bars(info)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.mine_sat_strength), style = mt(11f, 800, spacing = 0.6f), color = Muted)
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            for (s in bars) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(SatellitesModel.barHeight(s.cn0, 76f).dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(Color(Constellation.of(s.constellation)?.colour ?: 0xFF9AA6D0)))
                Text(SatellitesModel.label(s), style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.sp, lineHeight = 8.sp), color = Color(0xFF6A76A0), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Three tiles: satellites in use of those in view, how many systems, and how old the last fix is. */
@Composable
private fun Tiles(info: GnssInfo, fixTime: java.time.Instant?) {
    val now = appClock().instant()
    val age = fixTime?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("${info.used}/${info.visible}", stringResource(R.string.mine_sat_in_use), Modifier.weight(1f))
        Tile("${SatellitesModel.systems(info)}", stringResource(R.string.mine_sat_systems), Modifier.weight(1f))
        Tile(age?.let { if (it < 90) "$it s" else "${it / 60} min" } ?: "–", stringResource(R.string.mine_sat_fix_age), Modifier.weight(1f))
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Column(modifier.height(72.dp).clip(RoundedCornerShape(18.dp)).background(Panel), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, style = mt(20f, 900, tabular = true), color = Ink)
        Text(label, style = mt(11f, 400), color = Muted)
    }
}
