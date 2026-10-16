package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import app.atlas.ui.kit.cssConic
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.Argb
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.services.Units
import app.atlas.guide.Haptics
import app.atlas.model.ManeuverType
import app.atlas.model.Route
import app.atlas.model.Turn
import app.atlas.route.HazardKind
import app.atlas.ui.kit.AtlasTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The Ride board's colours (canvas/project/Ride.dc.html). */
internal object RideColours {
    val lime = Color(0xFFB8F36A)
    val soft = Color(0xFF8A8F99)
    val cobbles = Color(0xFFFFB547)
    val climb = Color(0xFFFF6B3D)
    val profileFill = Color(0xFF1E2A12)
    val endBg = Color(0xFF2A1414)
    val end = Color(0xFFFF7A7A)
    val wind = Color(0xFFC9CDD4)
}


private const val GEIST = "Geist, system-ui, sans-serif"
private const val LIME: Argb = 0xFFB8F36A
private const val CHIP: Argb = 0xFF121212
private const val SOFT: Argb = 0xFF8A8F99

/**
 * Ride: the bike, eyes-free, black for OLED as on the Ride board, every element where the board has it. The status row
 * (voice, vibrations, screen off, offline); a huge turn arrow in a ring that fills as the turn nears; the distance; the
 * turn's vibration pattern; the surface and the climb ahead; the height profile with where you are; and the arrival
 * time, what is left and the wind.
 */
@Composable
fun RideView(show: NavShow, actions: NavActions) {
    AtlasTheme(dark = true, night = true) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val fit = boardFit(maxHeight, firstY = 52f, lastY = 885f)
            BoardPlane(dy = fit.top, scale = fit.scale) { RideTop(show, actions) }
            BoardPlane(bottom = true, dy = -fit.bottom, scale = fit.scale) { RideBottom(show, actions) }
        }
    }
}

/** The board's top: the status row, the ring with the arrow, the distance and the haptic pill. */
@Composable
private fun RideTop(show: NavShow, actions: NavActions) {
    val v = show.view
    val s = v.state
    StatusRow(show, actions)
    val stepLength = s?.let { st -> v.route.steps.getOrNull(st.stepIndex)?.distance }?.takeIf { it > 0 }
    val progress = if (v.arrived) 1f else if (s == null || stepLength == null) 0f else (1 - s.distanceToManeuver / stepLength).toFloat().coerceIn(0f, 1f)
    // The board's ring: a conic gradient, lime up to how far the turn has come, under a black disc 9 px in.
    Bx(66f, 150f, 280f, 280f, grad = listOf(cssConic(0f, 140f, 140f, 0f to LIME, progress to LIME, progress to 0xFF1C1C1C, 1f to 0xFF1C1C1C)), r = CssCorners(140f, 140f, 140f, 140f))
    Bx(75f, 159f, 262f, 262f, bg = 0xFF000000, r = CssCorners(131f, 131f, 131f, 131f))
    TurnArrow(arrowOf(v), Modifier.offset(121.dp, 205.dp), size = 170.dp, contentDescription = v.instruction)
    BigDistance(show)
    when {
        v.arrived -> Unit
        s != null && (s.offRoute || v.rerouting) -> Box(Modifier.offset(0.dp, 542.dp).requiredSize(412.dp, 40.dp), contentAlignment = Alignment.Center) {
            BoardText(stringResource(if (v.rerouting) R.string.go_notify_rerouting else R.string.go_off_route_title), boardType(GEIST, 16f, 700, lineHeight = 20f), 0xFFFFB547)
        }
        else -> Box(Modifier.offset(0.dp, 542.dp).requiredSize(412.dp, 40.dp), contentAlignment = Alignment.Center) { HapticPill(s?.next) }
    }
}

/** The board's bottom: the surface and climb tiles, the profile and the footer. */
@Composable
private fun RideBottom(show: NavShow, actions: NavActions) {
    val v = show.view
    Box(Modifier.offset(16.dp, 620.dp).requiredSize(380.dp, 64.dp)) { AheadTiles(show) }
    Profile(v.route, v.travelled, show.units)
    RideFooter(show, actions)
}

/** The four round status chips of the Ride board's top row (40 px, 10 apart, from 111 px): lit lime when on. */
@Composable
internal fun StatusRow(show: NavShow, actions: NavActions) {
    Row(Modifier.offset(111.dp, 52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusChip("ui.headphones", !show.muted, stringResource(R.string.go_status_voice), onClick = actions.mute)
        StatusChip("ui.vibrate", show.haptics, stringResource(R.string.go_status_haptics), onClick = actions.haptics)
        StatusChip("ui.screen-off", show.background, stringResource(R.string.go_status_screen_off))
        StatusChip("ui.offline", false, stringResource(R.string.go_status_offline))
    }
}

/** A round status chip: [symbol] in lime when [on], grey when not; tapping toggles where it is a setting. */
@Composable
internal fun StatusChip(symbol: String, on: Boolean, description: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val state = stringResource(if (on) R.string.go_state_on else R.string.go_state_off)
    Box(
        modifier
            .size(40.dp)
            .cssBox(bg = CHIP, r = CssCorners(20f, 20f, 20f, 20f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Switch, onClick = onClick) else Modifier)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, Modifier.size(19.dp), tint = if (on) RideColours.lime else RideColours.soft, size = 19.dp)
    }
}

/**
 * "120 m" as the board sets it under the ring: the figures in a 76 px line box from 452 px, the unit 30 px in grey on
 * the same baseline; or the arrival.
 */
@Composable
internal fun BigDistance(show: NavShow) {
    val v = show.view
    val s = v.state
    Box(Modifier.offset(0.dp, 452.dp).requiredSize(412.dp, 76.dp), contentAlignment = Alignment.TopCenter) {
        when {
            v.arrived -> BoardText(stringResource(R.string.go_notify_arrived), boardType(GEIST, 34f, 800, lineHeight = 76f, align = TextAlign.Center), 0xFFFFFFFF)
            else -> {
                val d = if (s == null) Amount("–", "") else GoFormat.distance(s.distanceToManeuver, show.units)
                Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${d.value} ${d.unit}" }) {
                    BoardText(d.value, boardType(GEIST, 76f, 800, lineHeight = 76f, spacing = -1.52f, tnum = true), 0xFFFFFFFF, Modifier.alignByBaseline())
                    if (d.unit.isNotEmpty()) BoardText(" " + d.unit, boardType(GEIST, 30f, 600, lineHeight = 30f, spacing = -1.52f, tnum = true), SOFT, Modifier.alignByBaseline())
                }
            }
        }
    }
}

/**
 * The haptic pill: the rhythm the next turn will be felt as, drawn as its pulses (lime bars, 26 px for a long pulse as
 * the board has them, half that for a tap), so riders learn the code by seeing it.
 */
@Composable
fun HapticPill(step: app.atlas.model.Step?, modifier: Modifier = Modifier) {
    val cue = step?.let { Haptics.forManeuver(it.maneuver.type, it.maneuver.turn, urgent = false) } ?: return
    val described = stringResource(R.string.go_haptic_pattern, cue.pulses)
    Row(
        modifier
            .height(40.dp)
            .cssBox(bg = CHIP, r = CssCorners(20f, 20f, 20f, 20f))
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = described },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui.vibrate.2", Modifier.size(18.dp), tint = RideColours.soft, size = 18.dp)
        for (i in cue.pattern.indices) {
            if (cue.amplitudes[i] <= 0) continue
            val w = if (cue.pattern[i] > HapticPlan.TAP_MS) 26f else 13f
            Box(Modifier.size(w.dp, 8.dp).cssBox(bg = LIME, r = CssCorners(4f, 4f, 4f, 4f)))
        }
        Symbol("ui.chevron-right", Modifier.size(16.dp), tint = RideColours.lime, size = 16.dp)
    }
}

/**
 * The surface and the climb ahead as the board's two dark tiles side by side: the next rough stretch with its distance,
 * and the steepest grade in the next kilometre.
 */
@Composable
private fun AheadTiles(show: NavShow) {
    val v = show.view
    val rough = remember(v.route, (v.travelled / 10).roundToInt()) {
        RouteFacts.nextHazard(v.route, v.travelled, setOf(HazardKind.COBBLES, HazardKind.UNPAVED, HazardKind.STEPS, HazardKind.TRAM_TRACKS))
    }
    val climb = remember(v.route, (v.travelled / 25).roundToInt()) { RouteFacts.climbAhead(v.route, v.travelled) }
    if (rough == null && climb == null) return
    FlexRow(Modifier.fillMaxWidth(), gap = 10.dp) {
        if (rough != null) {
            val (h, d) = rough
            val what = stringResource(
                when (h.kind) {
                    HazardKind.COBBLES -> R.string.go_surface_cobbles
                    HazardKind.UNPAVED -> R.string.go_surface_unpaved
                    HazardKind.STEPS -> R.string.go_surface_steps
                    else -> R.string.go_surface_tram
                },
            )
            val dist = GoFormat.distance(d, show.units)
            Tile("$what, $dist") {
                Symbol("fact.cobbles", Modifier.size(28.dp), tint = RideColours.cobbles, size = 28.dp)
                Figure(dist.value, " " + dist.unit)
            }
        }
        if (climb != null) {
            val pct = climb.roundToInt().coerceAtLeast(1)
            Tile(stringResource(R.string.go_climb_ahead, pct)) {
                ArtIcon(BoardArt.climbWedge, size = 40.dp)
                Figure("$pct", "%")
            }
        }
    }
}

/** A tile's figure: 20 px bold white, its unit 14 px bold grey right after it on the same baseline. */
@Composable
private fun Figure(value: String, unit: String) {
    Row {
        BoardText(value, boardType(GEIST, 20f, 700, lineHeight = 26f, tnum = true), 0xFFFFFFFF, Modifier.alignByBaseline())
        BoardText(unit, boardType(GEIST, 14f, 700, lineHeight = 18f, tnum = true), SOFT, Modifier.alignByBaseline())
    }
}

@Composable
private fun Tile(description: String, content: @Composable () -> Unit) {
    Row(
        Modifier
            .height(64.dp)
            .cssBox(bg = CHIP, r = CssCorners(20f, 20f, 20f, 20f))
            .padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The route's height as on the Ride board, in its 356 × 48 px plot 12 px inside the dark card: a lime line (2 px) over
 * a dark green fill, the steep stretches (5 % or more) in orange (3 px), and a white dot where you are. The lowest point
 * sits 8 px above the bottom and the highest 14 px below the top, as the board's; heights spread over at least 12 m, so
 * a flat ride looks flat.
 */
@Composable
internal fun Profile(route: Route, travelled: Double, units: Units = Units.METRIC) {
    val e = route.elevations
    if (e.size != route.points.size || e.count { !it.isNaN() } < 2) return
    val cum = route.line.cumulative
    val total = cum.last().takeIf { it > 0 } ?: return
    Bx(16f, 698f, 380f, 64f, bg = 0xFF0C0C0C, r = CssCorners(20f, 20f, 20f, 20f))
    val description = stringResource(R.string.go_profile, GoFormat.height(route.climb, units).toString())
    Canvas(Modifier.offset(28.dp, 706.dp).requiredSize(356.dp, 48.dp).semantics { contentDescription = description }) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (x in e) if (!x.isNaN()) { lo = minOf(lo, x); hi = maxOf(hi, x) }
        val span = max(hi - lo, 12.0)
        val w = size.width
        val k = size.height / 48f
        fun px(i: Int) = (cum[i] / total).toFloat() * w
        fun py(x: Double) = (40f - ((x - lo) / span).toFloat() * 26f) * k
        val line = Path()
        var started = false
        for (i in e.indices) {
            if (e[i].isNaN()) continue
            if (!started) { line.moveTo(px(i), py(e[i])); started = true } else line.lineTo(px(i), py(e[i]))
        }
        val area = Path().apply { addPath(line); lineTo(w, size.height); lineTo(0f, size.height); close() }
        drawPath(area, RideColours.profileFill)
        drawPath(line, RideColours.lime, style = Stroke(2.dp.toPx()))
        // The steep stretches over 30 m or more, in orange.
        var j = 0
        for (i in e.indices) {
            if (e[i].isNaN()) continue
            if (j < i) j = i
            while (j < e.size - 1 && cum[j] - cum[i] < 30.0) j++
            val d = cum[j] - cum[i]
            if (d >= 15.0 && !e[j].isNaN() && (e[j] - e[i]) / d >= 0.05) {
                drawLine(RideColours.climb, Offset(px(i), py(e[i])), Offset(px(j), py(e[j])), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        val f = (travelled / total).coerceIn(0.0, 1.0)
        val at = cum.indexOfFirst { it >= travelled }.let { if (it < 0) e.size - 1 else it }
        val y = e.getOrNull(at)?.takeIf { !it.isNaN() } ?: lo
        drawCircle(Color.White, 5.dp.toPx(), Offset((f * w).toFloat(), py(y)))
    }
}

/** The Ride board's footer (380 × 84 px, 28 px corners): the arrival in lime, what is left, the wind, and the end. */
@Composable
private fun RideFooter(show: NavShow, actions: NavActions) {
    val s = show.view.state
    Row(
        Modifier.offset(16.dp, 801.dp).requiredSize(380.dp, 84.dp).cssBox(bg = CHIP, r = CssCorners(28f, 28f, 28f, 28f)).padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardText(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", boardType(GEIST, 28f, 700, lineHeight = 28f, tnum = true), LIME)
            BoardText(GoFormat.distance(s?.remaining ?: show.view.route.distance, show.units).toString(), boardType(GEIST, 14f, lineHeight = 18f, tnum = true), SOFT)
        }
        Spacer(Modifier.width(18.dp))
        Wind(show)
        Spacer(Modifier.weight(1f))
        EndButton(actions.end, size = 60.dp, background = RideColours.endBg, colour = RideColours.end)
    }
}

/** The round end button of the guidance screens: the close cross (22 px) in [colour] on [background]. */
@Composable
internal fun EndButton(onEnd: () -> Unit, size: Dp, background: Color, colour: Color, cross: Dp = 22.dp) {
    val end = stringResource(R.string.go_end_trip)
    Box(Modifier.size(size).clip(CircleShape).background(background).button(end, onEnd), contentAlignment = Alignment.Center) {
        Symbol("ui.close", Modifier.size(cross), tint = colour, size = cross)
    }
}

/** The wind against you (or behind), as on the Ride board: the wind's mark and its speed; nothing without a forecast. */
@Composable
internal fun Wind(show: NavShow) {
    val speed = show.windKmh ?: return
    val head = show.headwindKmh ?: return
    val against = head > 1
    // In the units of Settings (the nautical ones are for the water: a bike's wind is read in km/h or mph).
    val units = if (show.units == Units.IMPERIAL) Units.IMPERIAL else Units.METRIC
    val said = GoFormat.speed(abs(head) / 3.6, units).toString()
    val desc = stringResource(if (against) R.string.go_wind_against else R.string.go_wind_behind, said)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol("weather.wind.2", Modifier.size(20.dp), tint = if (against) RideColours.wind else RideColours.soft, size = 20.dp)
        BoardText(GoFormat.speed(speed / 3.6, units).value, boardType(GEIST, 14f, lineHeight = 18f, tnum = true), 0xFFC9CDD4)
    }
}

/** The arrow for the trip's next maneuver (the flag on arrival). */
internal fun arrowOf(v: NavView): ArrowShape {
    if (v.arrived) return ArrowShape(ManeuverType.ARRIVE, Turn.STRAIGHT)
    val m = v.state?.next?.maneuver ?: v.route.steps.getOrNull(1)?.maneuver ?: return ArrowShape(ManeuverType.CONTINUE, Turn.STRAIGHT)
    return ArrowShape(m.type, m.turn, m.exit)
}
