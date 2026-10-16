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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.cssRadial
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.GnssInfo
import app.atlas.services.LocationQuality
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import kotlinx.coroutines.flow.collect
import java.time.Duration

/* The Satellites board's night inks (`canvas/project/Satellites.dc.html`). */
private const val S_INK = 0xFFE6ECFF
private const val PANEL = 0xFF121936
private const val S_MUTED = 0xFF9AA6D0

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
    // the board's night: a radial glow at 50 % 25 % from #141C44 to #060A1C at 65 %
    Box(Modifier.fillMaxSize().bbox(0xFF060A1C, grad = listOf(cssRadial(false, 206f, 228.75f, 0f to 0xFF141C44, 0.65f to 0xFF060A1C)))) {
        BoardPage(
            title = stringResource(R.string.mine_sat_title),
            onBack = null,
            background = Color.Transparent,
            ink = Color(S_INK),
            titleSize = 34f,
            header = false,
            gap = 0.dp,
        ) {
            // the title row: the frosted back round (10 % white), the serif title and the accuracy pill (green at 14 %)
            Row(Modifier.fillMaxWidth().padding(top = 42.dp + shift()).height(44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton({ nav.back() }, bg = 0x1AFFFFFF, tint = S_INK)
                BText(stringResource(R.string.mine_sat_title), bt(34f, 400, 34f, SERIF, italic = true), S_INK, Modifier.weight(1f).semantics { heading() }, box = 44f)
                fix?.accuracy?.takeIf { it.isFinite() }?.let { a ->
                    TextPill("± ${if (a < 10) String.format(java.util.Locale.ROOT, "%.1f", a) else Math.round(a).toString()} m", bt(12f, 800, 15.5f, tnum = true), 0x247CFFB2, 0xFF7CFFB2, h = 34f, padH = 12f)
                }
            }
            if (info == null || info.satellites.isEmpty()) {
                MineState("local.gps", stringResource(R.string.mine_sat_none), text = stringResource(R.string.mine_sat_none_text), tint = Color(S_MUTED), modifier = Modifier.padding(top = 10.dp))
            } else {
                Plot(info, Modifier.padding(start = 20.dp, top = 10.dp).size(340.dp))
                Legend(info, Modifier.padding(top = 12.dp))
                Strength(info, Modifier.padding(top = 18.dp))
                Tiles(info, fix?.time, Modifier.padding(top = 14.dp))
            }
            BText(stringResource(R.string.mine_sat_footer), bt(10f, 400, 13f, align = TextAlign.Center), 0xFF6A76A0, Modifier.fillMaxWidth().padding(top = 24.dp), lines = 4)
        }
    }
}

/**
 * The sky plot of the board (340 units): the dark disc and its rim, rings of 0°, 30° and 60° elevation and the cross, the
 * compass letters (Geist 11, 800), one mark per satellite with the strongest named (Geist Mono 8), and you at the centre.
 */
@Composable
private fun Plot(info: GnssInfo, modifier: Modifier) {
    val named = SatellitesModel.named(info)
    val description = stringResource(R.string.mine_sat_plot_cd, info.visible, SatellitesModel.systems(info), info.used)
    val measurer = rememberTextMeasurer()
    val compass = bt(11f, 800)
    val degrees = bt(9f, 800)
    val names = bt(8f, 400, family = MONO)
    Canvas(modifier.semantics { contentDescription = description }) {
        val k = minOf(size.width, size.height) / 340f
        val ox = (size.width - 340f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, y * k)
        // SVG text: its baseline at y, centred on x (text-anchor middle) or starting there
        fun text(s: String, x: Float, y: Float, style: androidx.compose.ui.text.TextStyle, colour: Long, middle: Boolean = true) {
            val t = measurer.measure(s, style)
            val at = p(x, y)
            drawText(t, Color(colour), Offset(if (middle) at.x - t.size.width / 2f else at.x, at.y - t.firstBaseline))
        }
        drawCircle(Color(0xFF0B1024), 160f * k, p(170f, 170f))
        drawCircle(Color(0xFF2A3460), 160f * k, p(170f, 170f), style = Stroke(2f * k))
        for (r in listOf(150f, 100f, 50f)) drawCircle(Color(0xFF1E2748), r * k, p(170f, 170f), style = Stroke(1.2f * k))
        drawLine(Color(0xFF1E2748), p(170f, 20f), p(170f, 320f), 1.2f * k)
        drawLine(Color(0xFF1E2748), p(20f, 170f), p(320f, 170f), 1.2f * k)
        text("N", 170f, 16f, compass, S_MUTED)
        text("E", 330f, 174f, compass, S_MUTED)
        text("S", 170f, 336f, compass, S_MUTED)
        text("W", 10f, 174f, compass, S_MUTED)
        // the rings' elevations: 30° on the ring of radius 100, 60° on the ring of radius 50
        text("30°", 176f, 70f, degrees, 0xFF5A6690)
        text("60°", 176f, 120f, degrees, 0xFF5A6690)
        for (s in info.satellites.sortedBy { it.usedInFix }) {
            val c = Constellation.of(s.constellation) ?: continue
            val (x, y) = SatellitesModel.point(s.azimuth, s.elevation)
            mark(c, p(x, y), k, s.usedInFix)
            if (s in named) text(SatellitesModel.label(s), x + 9f, y - 7f, names, S_MUTED, middle = false)
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


/** The four systems as the board's legend: the system's 16 px symbol, 6 px, its name (12, 800) and 6 px on "used/in view" in grey. */
@Composable
private fun Legend(info: GnssInfo, modifier: Modifier = Modifier) {
    SpaceBetweenRow(modifier.fillMaxWidth()) {
        for ((c, used, seen) in SatellitesModel.counts(info)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // the system's mark in its own colour, as the board's legend draws it
            MSy("gnss.${c.key}", 16f, c.colour)
            BText(stringResource(
                when (c) { Constellation.GPS -> R.string.mine_sat_gps; Constellation.GALILEO -> R.string.mine_sat_galileo; Constellation.GLONASS -> R.string.mine_sat_glonass; else -> R.string.mine_sat_beidou },
            ), bt(12f, 800, 15.5f), S_INK)
            BText("$used/$seen", bt(12f, 800, 15.5f, tnum = true), S_MUTED)
        }
    }
}

/**
 * The bars of the board (150 high, radius 20, 12 px above, 14 at the sides, 8 below): the label, then the satellites in
 * use strongest first, 3 px apart, each its system's colour (top corners round by 3) over its name (Geist Mono 7).
 */
@Composable
private fun Strength(info: GnssInfo, modifier: Modifier = Modifier) {
    val bars = SatellitesModel.bars(info)
    Column(modifier.fillMaxWidth().height(150.dp).bbox(PANEL, cr(20f)).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // in capitals as the board sets it, the unit as it is written ("SIGNAL STRENGTH · dB-Hz")
        val label = stringResource(R.string.mine_sat_strength)
        BText(label.substringBeforeLast(" · ").uppercase() + label.substring(label.substringBeforeLast(" · ").length), bt(11f, 800, 14f, spacing = 1.32f), S_MUTED)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            for (s in bars) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(SatellitesModel.barHeight(s.cn0, 75f).dp).bbox(Constellation.of(s.constellation)?.colour ?: S_MUTED, CssCorners(3f, 3f, 0f, 0f)))
                BText(SatellitesModel.label(s), bt(7f, 400, 9f, MONO), 0xFF6A76A0)
            }
        }
    }
}

/** Three tiles as the board's (72 high, radius 18, 8 px apart): satellites in use of those in view, the systems, the fix's age. */
@Composable
private fun Tiles(info: GnssInfo, fixTime: java.time.Instant?, modifier: Modifier = Modifier) {
    val now = appClock().instant()
    val age = fixTime?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("${info.used}/${info.visible}", stringResource(R.string.mine_sat_in_use), Modifier.weight(1f))
        Tile("${SatellitesModel.systems(info)}", stringResource(R.string.mine_sat_systems), Modifier.weight(1f))
        Tile(age?.let { if (it < 90) "$it s" else "${it / 60} min" } ?: "–", stringResource(R.string.mine_sat_fix_age), Modifier.weight(1f))
    }
}

/** A tile: the value (20, 900 on 26) and 2 px under it what it is (11 on 14, grey), centred. */
@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Column(modifier.height(72.dp).bbox(PANEL, cr(18f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        BText(value, bt(20f, 900, 26f, tnum = true), S_INK)
        BText(label, bt(11f, 400, 14f, tnum = true), S_MUTED)
    }
}
