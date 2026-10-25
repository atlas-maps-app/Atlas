package app.atlas.ui.go

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.Argb
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.guide.Nearby
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.Units
import app.atlas.ui.kit.AtlasTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The Ears board's colours (canvas/project/Ears.dc.html). */
private object EarsColours {
    val disc = Color(0xFF0E3A36)
    val ring = Color(0xFF2A6A62)
    val mint = Color(0xFF7CFFB2)
    val text = Color(0xFFE6FBF6)
    val cafe = Color(0xFFFFB547)
    val transit = Color(0xFF7CC4FF)
    const val TEXT: Argb = 0xFFE6FBF6
    const val MINT: Argb = 0xFF7CFFB2
    const val GLASS: Argb = 0x1FE6FBF6
}

private const val GEIST = "Geist, system-ui, sans-serif"

/**
 * Ears only, as on the Ears board, each element where the board has it: the world around you by sound, the phone in
 * your pocket. A radar of the places nearby by their direction and distance (ahead is up when the phone knows which way
 * you face), the lines the voice said (beacon and callouts), and Where am I? / Around me / Beacon. It runs in the
 * navigation service, so it goes on with the screen off; leaving the screen ends it.
 */
@Composable
fun EarsScreen(args: Dest.Ears, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.go
    val context = LocalContext.current
    val running by hub.ears.collectAsStateWithLifecycle()
    val units by hub.unitsFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (hub.ears.value == null) hub.startEars(context, CompassHeadings.flow(context.applicationContext))
    }
    val leave: () -> Unit = {
        hub.stopEars()
        nav.back()
    }
    BackHandler(onBack = leave)
    val empty = remember { MutableStateFlow(EarsView()) }
    val view by (running?.view ?: empty).collectAsStateWithLifecycle()

    AtlasTheme(dark = true) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // The board's ground: an ellipse of deep green lit at half the width, 30 % down.
                .drawWithCache {
                    val ground = cssRadial(false, size.width / 2f / density, size.height * 0.3f / density, 0f to 0xFF145048, 0.7f to 0xFF0A2A28).brush(size, density)
                    onDrawBehind { drawRect(ground) }
                },
        ) {
            val fit = boardFit(maxHeight, firstY = 42f, lastY = 887f)
            BoardPlane(dy = fit.top, scale = fit.scale) {
                Row(Modifier.offset(16.dp, 42.dp).requiredWidth(380.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(44.dp).cssBox(bg = EarsColours.GLASS, r = CssCorners(22f, 22f, 22f, 22f)).button(stringResource(R.string.kit_back), leave),
                        contentAlignment = Alignment.Center,
                    ) { Symbol("ui.back", Modifier.size(20.dp), tint = EarsColours.text, size = 20.dp) }
                    BoardText(stringResource(R.string.go_ears_title), boardType("Instrument Serif, serif", 32f, italic = true, lineHeight = 32f), EarsColours.TEXT, Modifier.weight(1f).semantics { heading() })
                    SymbolFit("ears.headphones", Modifier.size(52.dp, 44.dp), EarsColours.text, contentDescription = stringResource(R.string.go_ears_headphones))
                }
                Radar(view, units, Modifier.offset(16.dp, 100.dp).requiredSize(380.dp))
                Column(Modifier.offset(16.dp, 494.dp).requiredWidth(380.dp)) {
                    Transcript(view)
                    Spacer(Modifier.height(41.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val s = running
                        EarsTile("ui.pin.2", stringResource(R.string.go_where_am_i), Modifier.weight(1f)) { s?.whereAmI() }
                        EarsTile("ears.around", stringResource(R.string.go_around_me), Modifier.weight(1f)) { s?.aroundMe() }
                        EarsTile("ears.beacon", stringResource(R.string.go_beacon), Modifier.weight(1f), on = view.beacon != null) { s?.toggleBeacon() }
                    }
                    Spacer(Modifier.height(16.dp))
                    BoardText(stringResource(R.string.go_ears_footer), boardType(GEIST, 12f, lineHeight = 18f, align = TextAlign.Center), 0xB3E6FBF6, Modifier.fillMaxWidth(), lines = 3)
                }
            }
            BoardPlane(bottom = true, dy = -fit.bottom, scale = fit.scale) {
                Box(
                    Modifier
                        .offset(16.dp, 831.dp)
                        .requiredSize(380.dp, 56.dp)
                        .cssBox(bg = EarsColours.TEXT, r = CssCorners(28f, 28f, 28f, 28f))
                        .button(stringResource(R.string.go_step_free_too)) {
                            services.scope.launch {
                                hub.store.setPrefs(hub.store.prefs.first().copy(avoidSteps = true))
                            }
                            hub.stopEars()
                            nav.replace(Dest.Route(mode = app.atlas.model.Mode.WALK.name))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BoardText(stringResource(R.string.go_step_free_too), boardType(GEIST, 15f, 800, lineHeight = 19.5f), 0xFF0E3A36)
                }
            }
        }
    }
}

/**
 * The radar of the Ears board, drawn from its SVG on the 380 grid: the disc and its three rings, the cone you face, you
 * in the middle, and each place as a ringed ping at its direction and distance with its name 18 out (11 px, 800); the
 * beacon's target in amber.
 */
@Composable
private fun Radar(view: EarsView, units: Units, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val label = boardType("Geist, sans-serif", 11f, 800)
    val described = view.around.take(6).joinToString("; ") { "${it.label} ${GoFormat.distance(it.distance, units)}" }
    val loading = stringResource(R.string.go_looking)
    Box(modifier.semantics { contentDescription = if (view.loading) loading else described }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.minDimension / 380f
            val c = center
            drawCircle(EarsColours.disc, 186f * k, c)
            for (r in floatArrayOf(75f, 120f, 165f)) drawCircle(EarsColours.ring, r * k, c, style = Stroke(1f * k))
            val cone = Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x - 93f * k, c.y - 161.1f * k)
                arcTo(androidx.compose.ui.geometry.Rect(c.x - 186f * k, c.y - 186f * k, c.x + 186f * k, c.y + 186f * k), -120f, 60f, false)
                close()
            }
            drawPath(cone, EarsColours.mint, alpha = 0.08f)
            if (view.heading == null) drawNorth(c, 186f * k, measurer)
            val range = radarRange(view)
            val taken = ArrayList<androidx.compose.ui.geometry.Rect>()
            val beacon = view.beacon
            beacon?.let { drawPing(it, view, c, k, range, measurer, label, units, beacon = true, taken) }
            for (n in view.around.take(RADAR_PINGS)) {
                if (beacon != null && Geo.distance(n.place.location, beacon.place.location) < 2.0) continue
                drawPing(n, view, c, k, range, measurer, label, units, beacon = false, taken)
            }
            drawCircle(EarsColours.text, 16f * k, c)
            drawPath(Path().apply { moveTo(c.x, c.y - 22f * k); lineTo(c.x + 9f * k, c.y - 8f * k); lineTo(c.x - 9f * k, c.y - 8f * k); close() }, EarsColours.text)
        }
    }
}

private const val RADAR_PINGS = 7

/** The radar's range: the smallest of 60, 120 and 200 m that holds every ping shown. */
internal fun radarRange(view: EarsView): Double {
    val far = (view.around.take(RADAR_PINGS).map { it.distance } + listOfNotNull(view.beacon?.distance)).maxOrNull() ?: return EarsSession.RADAR_M
    return listOf(60.0, 120.0, EarsSession.RADAR_M).firstOrNull { far <= it } ?: EarsSession.RADAR_M
}

/**
 * Where on the radar [n] sits, on the board's 380 grid scaled by [k]: its angle from up (relative to the heading when
 * known, else from north) and its distance on a square-root scale, so near places spread out and [rangeM] lands on the
 * outer ring.
 */
internal fun radarOffset(n: Nearby, heading: Double?, centre: Offset, k: Float, rangeM: Double = EarsSession.RADAR_M): Offset {
    val angle = n.relative ?: (heading?.let { n.bearing - it } ?: n.bearing)
    val d = (kotlin.math.sqrt(min(n.distance, rangeM) / rangeM) * 165.0).toFloat() * k
    val a = Math.toRadians(angle)
    return Offset(centre.x + d * sin(a).toFloat(), centre.y - d * cos(a).toFloat())
}

/**
 * Where a ping's label of [labelWidth] starts on a radar [width] wide: right of the ping in the left half, left of it in
 * the right half, 18 out on the board's grid scaled by [k], kept on the radar; a label as wide as the radar starts at
 * its left edge.
 */
internal fun pingLabelLeft(pingX: Float, centreX: Float, k: Float, labelWidth: Float, width: Float): Float =
    (if (pingX < centreX) pingX + 18f * k else pingX - 18f * k - labelWidth).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))

private fun DrawScope.drawPing(
    n: Nearby, view: EarsView, c: Offset, k: Float, range: Double, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, units: Units, beacon: Boolean,
    taken: MutableList<androidx.compose.ui.geometry.Rect>,
) {
    val p = radarOffset(n, view.heading, c, k, range)
    val colour = if (beacon) EarsColours.cafe else pingColour(n.category)
    drawCircle(colour, 22f * k, p, alpha = 0.35f, style = Stroke(1.5f * k))
    drawCircle(colour, 14f * k, p, alpha = 0.6f, style = Stroke(2f * k))
    drawCircle(colour, 7f * k, p)
    val text = "${n.label} · ${GoFormat.distance(n.distance, units)}"
    // One line no wider than the radar: a long name in large type ends in an ellipsis (wider than the radar, it left the
    // label no room, and placing it threw).
    val layout = measurer.measure(
        text, style.copy(color = colour), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = size.width.toInt()),
    )
    // The label on the side with room, on a baseline 4 below the ping's centre, as the board sets its text.
    val x = pingLabelLeft(p.x, c.x, k, layout.size.width.toFloat(), size.width)
    val top = p.y + 4f * k - layout.firstBaseline
    val box = androidx.compose.ui.geometry.Rect(x, top, x + layout.size.width, top + layout.size.height)
    if (taken.any { it.overlaps(box) }) return
    taken += box
    drawText(layout, topLeft = box.topLeft)
}

/** The board's ping colours: white for crossings, amber for food and drink, blue for transit, mint for the rest. */
private fun pingColour(category: String?): Color = when (category) {
    "crossing", "traffic_signals" -> Color.White
    "cafe", "restaurant", "bar", "pub", "fast_food", "bakery", "ice_cream" -> EarsColours.cafe
    "bus_stop", "tram_stop", "station", "platform", "subway_entrance", "ferry_terminal" -> EarsColours.transit
    else -> EarsColours.mint
}


private fun DrawScope.drawNorth(c: Offset, r: Float, measurer: androidx.compose.ui.text.TextMeasurer) {
    val layout = measurer.measure("N", TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EarsColours.mint))
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - r - layout.size.height * 0.2f))
}

/**
 * The last lines the voice said, as the board's card (6 × 14 px padding, a hairline over each row): each with its kind
 * in mint capitals, 58 px wide, and the line in quotes on its baseline.
 */
@Composable
private fun Transcript(view: EarsView) {
    val lines = view.lines.take(3)
    Column(Modifier.fillMaxWidth().cssBox(bg = 0x0FE6FBF6, r = CssCorners(22f, 22f, 22f, 22f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
        // A CSS border takes its width in the row: 1 px of rule over 8 px of padding.
        val rule = Modifier.cssBox(border = CssBorder(1f, 0f, 0f, 0f, longArrayOf(0x1FE6FBF6, 0, 0, 0))).padding(top = 9.dp, bottom = 8.dp)
        if (lines.isEmpty()) {
            Row(Modifier.fillMaxWidth().then(rule)) {
                BoardText(stringResource(if (view.loading) R.string.go_looking else R.string.go_ears_listening), boardType(GEIST, 14f, 600, lineHeight = 18f), 0xB3E6FBF6)
            }
        }
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().then(rule), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BoardText(stringResource(lineKind(l.kind)).uppercase(), boardType(GEIST, 10f, 800, lineHeight = 13f, spacing = 1f), EarsColours.MINT, Modifier.width(58.dp).alignByBaseline())
                BoardText("“${l.text}”", boardType(GEIST, 14f, 600, lineHeight = 18f), EarsColours.TEXT, Modifier.weight(1f).alignByBaseline())
            }
        }
    }
}

private fun lineKind(k: EarsLine.Kind): Int = when (k) {
    EarsLine.Kind.BEACON -> R.string.go_line_beacon
    EarsLine.Kind.CALLOUT -> R.string.go_line_callout
    EarsLine.Kind.WHERE -> R.string.go_line_where
    EarsLine.Kind.AROUND -> R.string.go_line_around
}

/** One of the board's three glass buttons (80 px, 22 px corners): its symbol (26 px) over its name, 6 px apart. */
@Composable
private fun EarsTile(symbol: String, label: String, modifier: Modifier, on: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .height(80.dp)
            .cssBox(bg = if (on) 0x477CFFB2 else EarsColours.GLASS, r = CssCorners(22f, 22f, 22f, 22f))
            .semantics { selected = on }
            .button(label, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Symbol(symbol, Modifier.size(26.dp), tint = EarsColours.text, size = 26.dp)
        BoardText(label, boardType("Geist, sans-serif", 13f, 800, lineHeight = 17f), EarsColours.TEXT)
    }
}
