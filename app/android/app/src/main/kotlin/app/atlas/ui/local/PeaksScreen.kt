package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.go.CompassHeadings
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssCorners
import androidx.compose.foundation.layout.Spacer

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF676D7C)
private val Brown = Color(0xFF6E4A2A)
private val Dark = Color(0xD114161B)

private const val FRAME_W = 412f
private const val HORIZON_AT_REST = 250f
private const val WANT_Y = 165f
private const val LABEL_W = 140f
private const val LABEL_H = 42f

/** Where one peak's dot and label go in the 412 wide drawing. */
private class Spot(val peak: Peak, val angle: Double, val x: Float, val y: Float, val boxX: Float, val boxY: Float)

/** What's that peak?: the named peaks round you on a compass tape and a stand-in ridge, with their height, distance and bearing. */
@Composable
fun PeaksScreen(args: Dest.Peaks, nav: Navigator) {
    val services = LocalServices.current
    val here = args.at ?: rememberHere()
    val lang = remember { Locale.getDefault().language }
    val clock = appClock()
    val context = LocalContext.current
    val sensor by produceState<Double?>(null) { CompassHeadings.flow(context).collect { value = it } }
    val loaded by produceState<List<Peak>?>(null, here) {
        value = Peaks.of(here, services.local.finder.find("natural=peak and name", here, Peaks.RADIUS_M, limit = 600).places.map { it.place }, lang)
    }
    val elevation by produceState<Double?>(null, here) { value = services.local.elevation(here) }
    val eye = elevation ?: 0.0
    var turned by remember { mutableStateOf<Double?>(null) }
    var picked by remember { mutableStateOf<Peak?>(null) }
    val placeName = rememberPlaceName(here)
    val peaks = loaded

    Box(Modifier.fillMaxSize().background(Color(0xFFE6ECF2))) {
        when {
            peaks == null -> Column(Modifier.padding(top = boardShift()).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.peaks_title), onBack = { nav.back() }, colour = Ink, size = 30, backBackground = Color(0xE6FFFFFF), backTint = Ink)
                Spacer(Modifier.height(16.dp))
                BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, Soft.css(), 18f))
            }
            peaks.isEmpty() -> Column(Modifier.padding(top = boardShift()).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.peaks_title), onBack = { nav.back() }, colour = Ink, size = 30, backBackground = Color(0xE6FFFFFF), backTint = Ink)
                StateMessage("nature.peak", stringResource(R.string.peaks_none), text = stringResource(R.string.peaks_none_text), tint = Soft)
            }
            else -> {
                // Facing the most striking peak until the phone or a finger says otherwise.
                val standout = remember(peaks, eye) { peaks.mapNotNull { p -> Peaks.angle(p, eye)?.let { p to it } }.maxByOrNull { it.second }?.first }
                val heading = Geo.normalize(turned ?: sensor ?: standout?.bearing ?: 0.0)
                val f = (FRAME_W / 2 / tan(Math.toRadians(Peaks.FOV / 2))).toFloat()
                val view = remember(peaks, heading, eye) { Peaks.inView(peaks, heading, eye) }
                val pitch = remember(view) { Peaks.pitch(view, f, HORIZON_AT_REST, WANT_Y) }
                val spots = remember(view, heading, pitch) { layout(view, heading, pitch, f) }
                val ranked = remember(peaks, view) { (view.map { it.first } + peaks.filter { p -> view.none { it.first === p } }).take(24) }
                val current = picked ?: spots.firstOrNull()?.peak ?: ranked.firstOrNull()
                val sun = remember(peaks, here, eye) { Peaks.sunBehind(here, clock.instant(), clock.zone, peaks, eye) }

                Ridge(peaks, heading, pitch, f, eye, spots, current, onTurn = { turned = Geo.normalize(heading + it) })
                Labels(spots, current) { p -> picked = p }
                Tape(heading)
                BoardTitleBar(
                    stringResource(R.string.peaks_title), onBack = { nav.back() }, Modifier.padding(top = boardShift()).padding(horizontal = 16.dp),
                    colour = Ink, size = 30, backBackground = Color(0xE6FFFFFF), backTint = Ink,
                )
                // Nothing named in view: the message in the labels' own dark pill.
                if (spots.isEmpty()) BoardPara(
                    stringResource(R.string.peaks_no_view), boardStyle(13f, 800, 0xFFFFFFFF, 17f, align = TextAlign.Center),
                    Modifier.align(Alignment.TopCenter).padding(top = 200.dp, start = 40.dp, end = 40.dp).boardBox(Dark.css(), corners(10f)).padding(horizontal = 9.dp, vertical = 5.dp),
                )
                SheetBottom(
                    Modifier.align(Alignment.BottomCenter), placeName, elevation, sun, ranked, current, here, eye, elevation != null,
                    onPick = { p -> picked = p; turned = p.bearing },
                    onHike = { p -> nav.go(Dest.Route(p.place.location.lat, p.place.location.lon, p.name, mode = "WALK")) },
                )
            }
        }
    }
}

/** Dots at the peaks' tops and labels for the most striking, none on top of another; the rest are left to the list. */
private fun layout(view: List<Pair<Peak, Double>>, heading: Double, pitch: Double, f: Float): List<Spot> {
    val out = ArrayList<Spot>()
    for ((p, angle) in view) {
        if (out.size >= 3) break
        val x = Peaks.x(p.bearing, heading, FRAME_W) ?: continue
        val y = Peaks.y(angle, pitch, f, HORIZON_AT_REST)
        if (y < 70f || y > 330f) continue
        val bx = (x - LABEL_W / 2).coerceIn(12f, FRAME_W - 12f - LABEL_W)
        // Above the dot first, higher and higher; then below it, in the ridge; the first place that is free of the labels already down.
        val by = listOf(y - 62f, y - 122f, y - 182f, y + 16f, y + 76f).firstOrNull { c ->
            c >= 56f && c + LABEL_H <= 430f && out.none { abs(it.boxX - bx) < LABEL_W + 4 && abs(it.boxY - c) < LABEL_H + 6 }
        } ?: continue
        out += Spot(p, angle, x, y, bx, by)
    }
    return out
}

/** The compass tape across the top: a tick every 5 degrees, a longer one and a label every 15, the gold marker at the middle. */
@Composable
private fun Tape(heading: Double) {
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, SansFamily) }
    val label = stringResource(R.string.peaks_heading_label, compassName(heading), heading.roundToInt() % 360)
    val names = mapOf(0 to stringResource(R.string.cosmos_compass_n), 45 to stringResource(R.string.cosmos_compass_ne), 90 to stringResource(R.string.cosmos_compass_e), 135 to stringResource(R.string.cosmos_compass_se), 180 to stringResource(R.string.cosmos_compass_s), 225 to stringResource(R.string.cosmos_compass_sw), 270 to stringResource(R.string.cosmos_compass_w), 315 to stringResource(R.string.cosmos_compass_nw))
    Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = label }) {
        val s = size.width / FRAME_W
        drawRect(Brush.verticalGradient(listOf(Color(0x7314161B), Color(0x0014161B))))
        scale(s, s, Offset.Zero) {
            val pxPerDeg = FRAME_W / Peaks.FOV.toFloat()
            val first = (Math.floor((heading - Peaks.FOV / 2 - 5) / 5).toInt()) * 5
            var d = first
            while (d <= heading + Peaks.FOV / 2 + 5) {
                val x = FRAME_W / 2 + (d - heading).toFloat() * pxPerDeg
                val norm = ((d % 360) + 360) % 360
                val major = norm % 15 == 0
                drawLine(Color.White, Offset(x, 0f), Offset(x, if (major) 12f else 6f), if (major) 1.6f else 1f)
                if (major) text.draw(this, names[norm] ?: norm.toString(), x, 24f, 10f, Color.White, anchor = 0.5f, weight = 800)
                d += 5
            }
            val marker = Path().apply { moveTo(206f, 0f); lineTo(201f, 8f); lineTo(211f, 8f); close() }
            drawPath(marker, Color(0xFFFFD24A))
        }
    }
}

/** The picture: sky, two ridges drawn from the peaks (far and near), the valley; a drag turns it. */
@Composable
private fun Ridge(peaks: List<Peak>, heading: Double, pitch: Double, f: Float, eye: Double, spots: List<Spot>, current: Peak?, onTurn: (Double) -> Unit) {
    // The angles and widths are worked out once, not on every frame of a drag.
    val humps = remember(peaks, eye) {
        listOf(true, false).map { far -> peaks.filter { (it.distanceM > 12_000) == far }.mapNotNull { p -> Peaks.angle(p, eye)?.let { Hump(it, Math.toDegrees(kotlin.math.atan(2500.0 / p.distanceM)).coerceIn(2.5, 16.0), p.bearing) } } }
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures { change, drag -> change.consume(); onTurn((-drag.x / (size.width / Peaks.FOV.toFloat())).toDouble()) }
        },
    ) {
        val s = size.width / FRAME_W
        val total = size.height / s
        scale(s, s, Offset.Zero) {
            val horizonY = Peaks.y(0.0, pitch, f, HORIZON_AT_REST)
            drawRect(Brush.verticalGradient(0f to Color(0xFF86B1DC), 1f to Color(0xFFF1E3CC), startY = 0f, endY = horizonY.coerceAtLeast(120f)), size = Size(FRAME_W, total))
            // Two ridges: what stands beyond 12 km, paler; and what is nearer.
            for ((i, colour) in listOf(Color(0xFF8A9BB2), Color(0xFF55657A)).withIndex()) {
                drawPath(ridge(humps[i], heading, pitch, f), colour)
            }
            // The valley and the forest at its foot.
            drawRect(Brush.verticalGradient(0f to Color(0xFF53624A), 1f to Color(0xFF38442F), startY = horizonY, endY = total), Offset(0f, horizonY), Size(FRAME_W, total - horizonY))
            for (sp in spots) {
                val on = sp.peak === current
                drawLine(Color.White, Offset(sp.x, sp.y - 4f), Offset(sp.x, if (sp.boxY + LABEL_H < sp.y) sp.boxY + LABEL_H else sp.boxY), 1.5f)
                drawCircle(Color.White, if (on) 5.5f else 4f, Offset(sp.x, sp.y))
                drawCircle(Ink, if (on) 5.5f else 4f, Offset(sp.x, sp.y), style = Stroke(1.5f))
            }
        }
    }
}

/** One peak as a hump of the ridge: how high it stands (degrees), how wide (half-width, degrees) and where (bearing). */
private class Hump(val angle: Double, val half: Double, val bearing: Double)

/** The ridge of [humps] as a filled shape down to the bottom of the picture: each peak a hump as wide as a few kilometres of it at its distance. */
private fun ridge(humps: List<Hump>, heading: Double, pitch: Double, f: Float): Path {
    val path = Path()
    path.moveTo(0f, 2000f)
    var x = 0f
    while (x <= FRAME_W + 2) {
        val bearing = heading + Math.toDegrees(kotlin.math.atan(((x - FRAME_W / 2) / f).toDouble()))
        var best = -3.0
        for (h in humps) {
            val d = Geo.bearingDelta(bearing, h.bearing)
            if (d < h.half) best = max(best, h.angle - (h.angle + 3.0) * (d / h.half) * (d / h.half * 0.35 + 0.65))
        }
        path.lineTo(x, Peaks.y(best, pitch, f, HORIZON_AT_REST))
        x += 3f
    }
    path.lineTo(FRAME_W + 2, 2000f)
    path.close()
    return path
}

/** The labels over the peaks: name and "712 m · 2.1 km · 335°" on a dark pill. */
@Composable
private fun Labels(spots: List<Spot>, current: Peak?, onPick: (Peak) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = maxWidth.value / FRAME_W
        for (sp in spots) {
            val p = sp.peak
            val km = distanceText(p.distanceM)
            val detail = if (p.eleM != null) stringResource(R.string.peaks_label_detail, p.eleM.roundToInt(), km, p.bearing.roundToInt() % 360) else stringResource(R.string.peaks_label_detail_noele, km, p.bearing.roundToInt() % 360)
            val desc = if (p.eleM != null) stringResource(R.string.peaks_desc, p.name, p.eleM.roundToInt(), km.removeSuffix(" km")) else stringResource(R.string.peaks_desc_noele, p.name, km.removeSuffix(" km"))
            // The board's label: 140 x 42, ink at 82 per cent, radius 10, 9 by 5 px in; the name and the numbers 1 px apart.
            Column(
                Modifier.offset(x = (sp.boxX * scale).dp, y = (sp.boxY * scale).dp).width((LABEL_W * scale).dp).boardBox(Dark.css(), corners(10f))
                    .clickable(role = Role.Button) { onPick(p) }.padding(horizontal = 9.dp, vertical = 5.dp).semantics { contentDescription = desc },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                BoardLine(p.name, boardStyle(13f, 800, 0xFFFFFFFF, 17f))
                BoardLine(detail, boardStyle(11f, 700, 0xFFC9D6E6, 14f, tnum = true))
            }
        }
    }
}

@Composable
private fun distanceText(m: Double): String {
    val km = if (m >= 10_000) (m / 1000).roundToInt().toString() else String.format(Locale.ROOT, "%.1f", m / 1000)
    return stringResource(R.string.peaks_km, km)
}

/**
 * The board's sheet: white from 593 (radius 30, its shadow), 10 px down and 16 in, 10 apart: the grabber, where you
 * stand and the sunset behind a peak, the three nearest rows of 48 under hairlines, the hike, the sources.
 */
@Composable
private fun SheetBottom(
    modifier: Modifier, place: String?, eleHere: Double?, sun: Peaks.SunBehind?, ranked: List<Peak>, current: Peak?, here: app.atlas.geo.LatLon, eye: Double, eyeKnown: Boolean,
    onPick: (Peak) -> Unit, onHike: (Peak) -> Unit,
) {
    val time = DateTimeFormatter.ofPattern("HH:mm")
    Column(
        modifier.fillMaxWidth().boardBox(0xFFFFFFFF, CssCorners(30f, 30f, 0f, 0f), shadow(0f, -10f, 30f, 0x29000000), clip = false)
            .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).boardBox(0xFFD5D8DE, corners(3f)))
        Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val head = when {
                place != null && eleHere != null -> stringResource(R.string.peaks_here, place, eleHere.roundToInt())
                place != null -> place
                eleHere != null -> stringResource(R.string.peaks_here_unnamed, eleHere.roundToInt())
                else -> stringResource(R.string.peaks_here_flat)
            }
            BoardLine(head.uppercase(), boardStyle(12f, 800, Soft.css(), 15.5f, spacing = 1.44f), Modifier.weight(1f))
            if (sun != null) Box(Modifier.height(26.dp).boardBox(0xFFFFF1D6, corners(13f)).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                BoardLine(stringResource(R.string.peaks_sunset_behind, sun.peak.name, time.format(sun.at)), boardStyle(11f, 800, 0xFF8A5A00, 14f))
            }
        }
        if (!eyeKnown) BoardPara(stringResource(R.string.peaks_eye_unknown), boardStyle(11f, 400, Soft.css(), 14f))
        Column {
            for (p in ranked.take(3)) {
                val detail = if (p.eleM != null) stringResource(R.string.peaks_row_detail, p.eleM.roundToInt(), distanceText(p.distanceM)) else stringResource(R.string.peaks_row_detail_noele, distanceText(p.distanceM))
                // A row of the board: a 1 px hairline above, 48 px, the peak's symbol, the name 12 px after it, the numbers at the right.
                Row(
                    Modifier.fillMaxWidth().height(49.dp).boardBox(border = CssBorder(1f, 0f, 0f, 0f, longArrayOf(0xFFEEF0F4, 0, 0, 0))).clickable(role = Role.Button) { onPick(p) }.padding(top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Symbol("nature.peak", tint = Brown, size = 22.dp)
                    BoardLine(p.name, boardStyle(15f, 800, Ink.css(), 19.5f), Modifier.weight(1f))
                    BoardLine(detail, boardStyle(13f, 400, Soft.css(), 17f, tnum = true))
                }
            }
        }
        if (current != null) {
            // A time needs both heights: with your own unknown (sea level assumed) the climb would be wildly too long.
            val minutes = if (current.eleM != null && eyeKnown) Peaks.hikeMinutes(current.distanceM, current.eleM - eye) else null
            val span = minutes?.let { if (it >= 60) stringResource(R.string.peaks_hike_hm, it / 60, it % 60) else stringResource(R.string.peaks_hike_m, it) }
            Box(Modifier.fillMaxWidth().height(52.dp).boardBox(Brown.css(), corners(26f)).clickable(role = Role.Button) { onHike(current) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                BoardLine(if (span != null) stringResource(R.string.peaks_hike, current.name, span) else stringResource(R.string.peaks_hike_plain, current.name), boardStyle(14f, 800, 0xFFFFFFFF, 18f))
            }
        }
        BoardPara(stringResource(R.string.peaks_footer), boardStyle(10f, 400, 0xFF8A8F98, 13f, align = TextAlign.Center), Modifier.fillMaxWidth())
    }
}
