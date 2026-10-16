package app.atlas.ui.go

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The Ears board's colours (canvas/project/Ears.dc.html). */
private object EarsColours {
    val glow = Color(0xFF145048)
    val deep = Color(0xFF0A2A28)
    val disc = Color(0xFF0E3A36)
    val ring = Color(0xFF2A6A62)
    val mint = Color(0xFF7CFFB2)
    val text = Color(0xFFE6FBF6)
    val glass = Color(0x1FE6FBF6)
    val card = Color(0x0FE6FBF6)
    val rule = Color(0x1FE6FBF6)
    val note = Color(0xB3E6FBF6)
    val cafe = Color(0xFFFFB547)
    val transit = Color(0xFF7CC4FF)
}

/**
 * Ears only, as on the Ears board: the world around you by sound, the phone in your pocket. A radar of the places
 * nearby by their direction and distance (ahead is up when the phone knows which way you face), the lines the voice
 * said (beacon and callouts), and Where am I? / Around me / Beacon. It runs in the navigation service, so it goes on
 * with the screen off; leaving the screen ends it.
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
        Column(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Brush.radialGradient(listOf(EarsColours.glow, EarsColours.deep), center = Offset(size.width / 2f, size.height * 0.3f), radius = size.maxDimension * 0.7f))
                }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(EarsColours.glass).button(stringResource(R.string.kit_back), leave),
                    contentAlignment = Alignment.Center,
                ) { ArtIcon(BoardArt.back, size = 20.dp, tint = EarsColours.text) }
                Text(
                    stringResource(R.string.go_ears_title), Modifier.weight(1f).semantics { heading() },
                    style = Atlas.type.displayItalic.copy(fontSize = 32.sp, lineHeight = 32.sp), color = EarsColours.text,
                )
                ArtIcon(BoardArt.headphones, size = 52.dp)
            }
            Spacer(Modifier.height(14.dp))
            Radar(view, units, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(14.dp))
            Transcript(view)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = running
                EarsTile(BoardArt.whereAmI, stringResource(R.string.go_where_am_i), Modifier.weight(1f)) { s?.whereAmI() }
                EarsTile(BoardArt.aroundMe, stringResource(R.string.go_around_me), Modifier.weight(1f)) { s?.aroundMe() }
                EarsTile(BoardArt.beacon, stringResource(R.string.go_beacon), Modifier.weight(1f), on = view.beacon != null) { s?.toggleBeacon() }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.go_ears_footer), style = Atlas.type.caption.copy(fontSize = 12.sp, lineHeight = 18.sp), color = EarsColours.note,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(CircleShape)
                    .background(EarsColours.text)
                    .button(stringResource(R.string.go_step_free_too)) {
                        services.scope.launch {
                            hub.store.setPrefs(hub.store.prefs.first().copy(avoidSteps = true))
                        }
                        hub.stopEars()
                        nav.replace(Dest.Route(mode = app.atlas.model.Mode.WALK.name))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.go_step_free_too), style = Atlas.type.name.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = EarsColours.disc)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The radar of the Ears board: the disc and its three rings, the cone you face, you in the middle, and each place as a
 * ringed ping at its direction and distance with its name; the beacon's target in amber.
 */
@Composable
private fun Radar(view: EarsView, units: Units, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val described = view.around.take(6).joinToString("; ") { "${it.label} ${GoFormat.distance(it.distance, units)}" }
    val loading = stringResource(R.string.go_looking)
    Box(modifier.semantics { contentDescription = if (view.loading) loading else described }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // The board's radar on its 380 grid: the disc, three rings, the cone you face, the pings, you.
            val k = size.minDimension / 380f
            val c = center
            drawCircle(EarsColours.disc, 186f * k, c)
            for (r in floatArrayOf(75f, 120f, 165f)) drawCircle(EarsColours.ring, r * k, c, style = Stroke(1.dp.toPx()))
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
    val layout = measurer.measure(text, style.copy(color = colour))
    // The label on the side with room: right of the ping in the left half, left of it in the right half, 18 out.
    val x = (if (p.x < c.x) p.x + 18f * k else p.x - 18f * k - layout.size.width).coerceIn(0f, size.width - layout.size.width)
    val box = androidx.compose.ui.geometry.Rect(x, p.y - layout.size.height / 2f, x + layout.size.width, p.y + layout.size.height / 2f)
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

/** The last lines the voice said, each with its kind in mint capitals, as on the board. */
@Composable
private fun Transcript(view: EarsView) {
    val lines = view.lines.take(3)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(EarsColours.card).padding(horizontal = 14.dp, vertical = 6.dp)) {
        if (lines.isEmpty()) {
            Text(
                stringResource(if (view.loading) R.string.go_looking else R.string.go_ears_listening),
                style = Atlas.type.body.copy(fontSize = 14.sp), color = EarsColours.note, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        lines.forEach { l ->
            Row(
                Modifier.fillMaxWidth().drawBehind { drawLine(EarsColours.rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(lineKind(l.kind)), Modifier.width(58.dp).padding(top = 3.dp),
                    style = Atlas.type.kicker.copy(fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold), color = EarsColours.mint, maxLines = 1,
                )
                Text("“${l.text}”", style = Atlas.type.bodyStrong.copy(fontSize = 14.sp, fontStyle = FontStyle.Normal), color = EarsColours.text, maxLines = 2)
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

/** One of the board's three glass buttons: its symbol over its name. */
@Composable
private fun EarsTile(art: Art, label: String, modifier: Modifier, on: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .height(80.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) EarsColours.mint.copy(alpha = 0.28f) else EarsColours.glass)
            .button(label, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        ArtIcon(art, size = 26.dp, tint = EarsColours.text)
        Text(label, style = Atlas.type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = EarsColours.text, maxLines = 1)
    }
}
