package app.atlas.ui.go

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.Theme
import app.atlas.nav.LocalServices
import app.atlas.services.Units
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.argb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val GEIST = "Geist, system-ui, sans-serif"

/** The Sail board's colours (canvas/project/Sail.dc.html). */
private object SailColours {
    const val INK: Argb = 0xFF14161B
    const val SUB: Argb = 0xFF464C5A
    const val DEEP: Argb = 0xFF1F5F99
    const val BLUE: Argb = 0xFF2E5BFF
    const val GREEN: Argb = 0xFF178A4E
    const val FOOTER: Argb = 0xFFFBFAF7
    const val MARINA: Argb = 0xFFE6F1FB
    const val GLASS: Argb = 0xE0FFFFFF
}

/** The Sail board's glass pills and buttons: white at 88 % over a 20 px blur with a soft shadow. */
private fun Modifier.sailGlass(r: Float) = cssBox(bg = SailColours.GLASS, r = CssCorners(r, r, r, r), shadows = listOf(CssShadow(0f, 4f, 14f, 0f, 0x1F181C30)), blur = 20f)

/**
 * Sail: a boat guided by ear, on the nautical chart as on the Sail board, each element where the board has it. The brass
 * compass turned to the course, the speed in knots and the boat's draft; the bridges ahead; the next bridge as a drawing
 * of the bridge with your mast under it, its clearance against your air draft and the margin; the chart with the course
 * dashed in magenta; the last line the voice said; and the arrival with the distance in nautical miles.
 */
@Composable
fun SailView(show: NavShow, actions: NavActions) {
    AtlasTheme(dark = false) {
        val services = LocalServices.current
        val v = show.view
        val s = v.state
        val bridges by produceState(emptyList<RouteBridge>(), v.route) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    RouteBridges.find(v.route, services.placeStores, services.localData.bridges(v.route))
                }.getOrDefault(emptyList())
            }
        }
        val next = RouteBridges.next(bridges, v.travelled)
        // Only the bridges whose clearance is known go on the chart, as their height; the others are many (a city's canals).
        val markers = bridges.mapIndexedNotNull { i, b ->
            val c = b.clearance ?: return@mapIndexedNotNull null
            val low = show.vehicle.airDraftM?.let { it > c } == true
            Marker("bridge$i", b.location, "maki-bridge", if (low) 0xFFD2232A else 0xFF3B4A5C, MarkerStyle.CHIP, value = GoFormat.size(c, show.units), priority = 50)
        }
        Box(Modifier.fillMaxSize()) {
            GuidanceMap(
                v, Modifier.fillMaxSize(), theme = Theme.CHART, routeColour = 0xFFC2185B, dashed = true, zoom = 16.4, pitch = 40.0,
                padding = PaddingValues(top = 260.dp, bottom = 200.dp), markers = markers, background = Color(0xFFE4F2F9),
            )
            BoardPlane(dy = statusShift(44f)) {
                val course = s?.routeBearing ?: v.route.line.bearingAt(0.0)
                BrassCompass(course.toFloat(), Modifier.offset(14.dp, 44.dp), contentDescription = stringResource(R.string.go_course, course.toInt()))
                Column(Modifier.offset(128.dp, 50.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val kn = GoFormat.knots(s?.speed ?: 0.0)
                    val knots = stringResource(R.string.go_speed_knots_said, kn)
                    Row(
                        Modifier.height(44.dp).sailGlass(22f).padding(horizontal = 14.dp).semantics(mergeDescendants = true) { contentDescription = knots },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BoardText(kn, boardType(GEIST, 22f, 700, lineHeight = 28.5f, tnum = true), SailColours.INK, Modifier.alignByBaseline())
                        BoardText("kn", boardType(GEIST, 13f, lineHeight = 17f, tnum = true), SailColours.SUB, Modifier.alignByBaseline())
                    }
                    show.vehicle.draftM?.let { d ->
                        val said = stringResource(R.string.go_draft_said, GoFormat.size(d, show.units))
                        Row(
                            Modifier.height(36.dp).sailGlass(18f).padding(horizontal = 12.dp).semantics(mergeDescendants = true) { contentDescription = said },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Symbol("mode.boat.2", Modifier.size(16.dp), tint = argb(SailColours.DEEP), size = 16.dp)
                            BoardText(GoFormat.size(d, show.units), boardType(GEIST, 14f, 600, lineHeight = 18f, tnum = true), SailColours.DEEP)
                        }
                    }
                }
                val ahead = bridges.count { it.along >= v.travelled - 10 }
                if (ahead > 0) {
                    val said = pluralStringResource(R.plurals.go_bridges_ahead, ahead, ahead)
                    // The board's dark chip at the top right (its VHF channel there; the bridges ahead here).
                    Box(Modifier.offset(0.dp, 44.dp).requiredSize(398.dp, 48.dp), contentAlignment = Alignment.CenterEnd) {
                        Row(
                            Modifier.height(48.dp).cssBox(bg = SailColours.INK, r = CssCorners(24f, 24f, 24f, 24f)).padding(start = 12.dp, end = 14.dp)
                                .semantics(mergeDescendants = true) { contentDescription = said },
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Symbol("place.bridge", Modifier.size(20.dp), tint = Color.White, size = 20.dp, weight = 1.9f / 2f)
                            BoardText("$ahead", boardType(GEIST, 16f, 700, lineHeight = 20.5f, tnum = true), 0xFFFFFFFF)
                        }
                    }
                }
                next?.let { (b, d) -> Box(Modifier.offset(14.dp, 166.dp).requiredWidth(384.dp)) { BridgeCard(b, d, show.vehicle.airDraftM, show.units) } }
            }
            BoardPlane(bottom = true, dy = -navShift(891f)) {
                // The voice's line ends 12 px above the footer, however many lines it takes.
                Box(Modifier.offset(12.dp, 0.dp).requiredSize(388.dp, 783.dp), contentAlignment = Alignment.BottomStart) { VoiceBubble(show.lastSaid, dark = false) }
                SailFooter(show, actions)
            }
        }
    }
}

/**
 * The brass compass of the Sail board (104 px), drawn from its SVG: the shadow, the brass bezel, the dark ring, the
 * card; on the card turned to [heading] the dashed tick ring, the needle (navy, its north half red), the bar and the N;
 * the red lubber mark at the top, the brass hub and the highlight.
 */
@Composable
internal fun BrassCompass(heading: Float, modifier: Modifier = Modifier, size: Dp = 104.dp, contentDescription: String? = null) {
    val measurer = rememberTextMeasurer(cacheSize = 2)
    // The card turns the short way round, smoothly.
    val angle = remember { Animatable(-heading) }
    LaunchedEffect(heading) {
        var goal = -heading
        while (goal - angle.value > 180f) goal -= 360f
        while (goal - angle.value < -180f) goal += 360f
        angle.animateTo(goal, tween(250))
    }
    Canvas(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        val k = this.size.minDimension / 104f
        // The N is set at 11 of the drawing's units: measured at that many px, then scaled with the rest.
        val n = measurer.measure("N", boardType("Georgia, serif", 11f / (density * fontScale), 700))
        scale(k, k, Offset.Zero) { drawBrassCompass(angle.value, n) }
    }
}

private val NEEDLE: Path by lazy { ArtPart("M52 18l6 34-6 34-6-34z").path }
private val NORTH: Path by lazy { ArtPart("M52 18l6 34h-12z").path }
private val BAR: Path by lazy { ArtPart("M18 52l34-5 34 5-34 5z").path }
private val HIGHLIGHT: Path by lazy { ArtPart("M30 30a30 30 0 0 1 20-10").path }

/** A radial gradient as SVG sets one in a circle's bounding box ([cx], [cy], [r] as fractions of it). */
private fun DrawScope.boxRadial(cx: Float, cy: Float, r: Float, centre: Offset, radius: Float, vararg stops: Pair<Float, Long>): Brush {
    val d = radius * 2
    return Brush.radialGradient(
        *stops.map { it.first to Color(it.second.toInt()) }.toTypedArray(),
        center = Offset(centre.x - radius + cx * d, centre.y - radius + cy * d), radius = r * d,
    )
}

/** The compass in its own 104-unit space, the card turned by [card] degrees. */
private fun DrawScope.drawBrassCompass(card: Float, n: TextLayoutResult) {
    val c = Offset(52f, 52f)
    drawCircle(Color.Black.copy(alpha = 0.18f), 50f, Offset(52f, 54f))
    drawCircle(boxRadial(0.35f, 0.3f, 0.8f, c, 50f, 0f to 0xFFF6E3A6, 0.55f to 0xFFC9A24E, 1f to 0xFF8A6A25), 50f, c)
    drawCircle(Color(0xFF3A2C10), 43f, c)
    drawCircle(boxRadial(0.45f, 0.4f, 0.7f, c, 41f, 0f to 0xFFFFFDF6, 1f to 0xFFEDE4CE), 41f, c)
    rotate(card, c) {
        drawCircle(Color(0xFF6B5A3A), 36f, c, style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.8f, 5.48f))))
        drawPath(NEEDLE, Color(0xFF1F2A44))
        drawPath(NORTH, Color(0xFFC0392B))
        drawPath(BAR, Color(0xFF6B5A3A), alpha = 0.55f)
        // SVG's text sits on its baseline at y = 30, centred on x = 52.
        drawText(n, Color(0xFFC0392B), Offset(52f - n.size.width / 2f, 30f - n.firstBaseline))
    }
    drawLine(Color(0xFFC0392B), Offset(52f, 4f), Offset(52f, 16f), 3f, StrokeCap.Round)
    drawCircle(boxRadial(0.35f, 0.3f, 0.8f, c, 5f, 0f to 0xFFF6E3A6, 0.55f to 0xFFC9A24E, 1f to 0xFF8A6A25), 5f, c)
    drawCircle(Color(0xFF6B5A3A), 5f, c, style = Stroke(1f))
    drawPath(HIGHLIGHT, Color.White, alpha = 0.7f, style = Stroke(3f, cap = StrokeCap.Round))
}

/**
 * The bridge card of the Sail board (384 × 118 px of glass): the board's drawing of the bridge with a boat under it and
 * the clearance marked, then the clearance in blue, your air draft, and the margin (green with a check, or red), with
 * the bridge's name and distance.
 */
@Composable
internal fun BridgeCard(bridge: RouteBridge, distance: Double, airDraft: Double?, units: Units, modifier: Modifier = Modifier) {
    val clearance = bridge.clearance
    val margin = if (clearance != null && airDraft != null) clearance - airDraft else null
    val fits = margin == null || margin >= 0
    val said = stringResource(
        R.string.go_bridge_said, bridge.name ?: stringResource(R.string.go_bridge), GoFormat.distance(distance, units).toString(),
        clearance?.let { GoFormat.size(it, units) } ?: stringResource(R.string.go_clearance_unknown),
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(118.dp)
            .cssBox(bg = 0xEBFFFFFF, r = CssCorners(24f, 24f, 24f, 24f), shadows = listOf(CssShadow(0f, 8f, 24f, 0f, 0x24181C30)), blur = 20f)
            .padding(start = 10.dp, end = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtBox(if (fits) BoardArt.bridge else TOO_LOW, Modifier.size(width = 170.dp, height = 98.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (clearance != null) {
                val c = GoFormat.size(clearance, units).split(' ')
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BoardText(c.first(), boardType(GEIST, 26f, 700, lineHeight = 26f, tnum = true), SailColours.BLUE, Modifier.alignByBaseline())
                    if (c.size > 1) BoardText(c[1], boardType(GEIST, 13f, lineHeight = 17f, tnum = true), SailColours.BLUE, Modifier.alignByBaseline())
                }
            } else {
                BoardText(stringResource(R.string.go_clearance_unknown), boardType(GEIST, 14f, 600, lineHeight = 18f), SailColours.SUB)
            }
            airDraft?.let {
                val a = GoFormat.size(it, units).split(' ')
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Symbol("mode.boat.2", Modifier.size(14.dp).alignByBaseline(), tint = argb(SailColours.SUB), size = 14.dp)
                    BoardText(a.first(), boardType(GEIST, 16f, 600, lineHeight = 20.5f, tnum = true), SailColours.SUB, Modifier.alignByBaseline())
                    if (a.size > 1) BoardText(a[1], boardType(GEIST, 12f, lineHeight = 15.5f, tnum = true), SailColours.SUB, Modifier.alignByBaseline())
                }
            }
            if (margin != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val colour = if (fits) SailColours.GREEN else 0xFFD93A3A
                    Symbol(if (fits) "ui.check" else "ui.close", Modifier.size(16.dp), tint = argb(colour), size = 16.dp, weight = if (fits) 1f else 2.8f / 3f)
                    BoardText((if (margin >= 0) "+" else "−") + GoFormat.size(abs(margin), units), boardType(GEIST, 15f, 700, lineHeight = 19.5f, tnum = true), colour)
                }
            }
            BoardText(listOfNotNull(bridge.name, GoFormat.distance(distance, units).toString()).joinToString(" · "), boardType(GEIST, 12f, lineHeight = 15.5f, tnum = true), SailColours.SUB)
        }
    }
}

/** The board's bridge with a mast that reaches the deck: no green gap, a red mast top. */
private val TOO_LOW: Art by lazy {
    val b = BoardArt.bridge
    Art(b.x, b.y, b.w, b.h, b.parts.dropLast(1) + ArtPart("M108 50V25.5", stroke = 0xFFD93A3A, width = 2.2f, cap = StrokeCap.Round))
}

/**
 * The Sail board's footer (388 × 96 px, 24 px above the bottom): the marina, the arrival in green and the distance in
 * nautical miles, voice, and the end.
 */
@Composable
private fun SailFooter(show: NavShow, actions: NavActions) {
    val s = show.view.state
    val remaining = s?.remaining ?: show.view.route.distance
    Row(
        Modifier
            .offset(12.dp, 795.dp)
            .requiredSize(388.dp, 96.dp)
            .cssBox(bg = SailColours.FOOTER, r = CssCorners(30f, 30f, 30f, 30f), shadows = listOf(CssShadow(0f, -4f, 30f, 0f, 0x2E181C30)))
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).cssBox(bg = SailColours.MARINA, r = CssCorners(14f, 14f, 14f, 14f)), contentAlignment = Alignment.Center) {
            Symbol("place.marina", Modifier.size(22.dp), tint = argb(SailColours.DEEP), size = 22.dp)
        }
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BoardText(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", boardType(GEIST, 26f, 700, lineHeight = 26f, tnum = true), SailColours.GREEN)
            BoardText(GoFormat.distance(remaining, Units.NAUTICAL).toString(), boardType(GEIST, 13f, lineHeight = 17f, tnum = true), SailColours.SUB)
        }
        Spacer(Modifier.weight(1f))
        LightButton("ui.volume.3", stringResource(R.string.go_status_voice), actions.mute, on = !show.muted)
        EndButton(actions.end, size = 58.dp, background = NavColours.end, colour = Color.White)
    }
}
