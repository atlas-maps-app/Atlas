package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.services.Units
import app.atlas.guide.Haptics
import app.atlas.model.ManeuverType
import app.atlas.model.Route
import app.atlas.model.Turn
import app.atlas.route.HazardKind
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.ProgressRing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The Ride board's colours (canvas/project/Ride.dc.html). */
internal object RideColours {
    val lime = Color(0xFFB8F36A)
    val chip = Color(0xFF121212)
    val soft = Color(0xFF8A8F99)
    val track = Color(0xFF1C1C1C)
    val cobbles = Color(0xFFFFB547)
    val climb = Color(0xFFFF6B3D)
    val profileFill = Color(0xFF1E2A12)
    val profileBg = Color(0xFF0C0C0C)
    val endBg = Color(0xFF2A1414)
    val end = Color(0xFFFF7A7A)
    val wind = Color(0xFFC9CDD4)
}

/** The boards' type in sp: the Ride board's huge distance and its tabular figures. */
private val Figures = TextStyle(fontFamily = SansFamily, fontFeatureSettings = "tnum")

/**
 * Ride: the bike, eyes-free, black for OLED as on the Ride board. The status row (voice, vibrations, screen off,
 * offline); a huge turn arrow in a ring that fills as the turn nears; the distance; the turn's vibration pattern; the
 * surface and the climb ahead; the height profile with where you are; and the arrival time, what is left and the wind.
 */
@Composable
fun RideView(show: NavShow, actions: NavActions) {
    AtlasTheme(dark = true, night = true) {
        val v = show.view
        val s = v.state
        Column(
            Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            StatusRow(show, actions)
            Spacer(Modifier.weight(0.6f))
            val stepLength = s?.let { st -> v.route.steps.getOrNull(st.stepIndex)?.distance }?.takeIf { it > 0 }
            val progress = if (s == null || stepLength == null) 0f else (1 - s.distanceToManeuver / stepLength).toFloat()
            ProgressRing(if (v.arrived) 1f else progress, size = 280.dp, stroke = 9.dp, colour = RideColours.lime, track = RideColours.track) {
                TurnArrow(arrowOf(v), size = 170.dp, contentDescription = v.instruction)
            }
            Spacer(Modifier.height(22.dp))
            BigDistance(show)
            Spacer(Modifier.height(14.dp))
            if (!v.arrived) HapticPill(s?.next)
            Spacer(Modifier.weight(1f))
            AheadTiles(show)
            Spacer(Modifier.height(14.dp))
            Profile(v.route, v.travelled, show.units)
            Spacer(Modifier.weight(0.4f))
            RideFooter(show, actions)
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** The four round status chips of the Ride board's top row: lit lime when on. */
@Composable
internal fun StatusRow(show: NavShow, actions: NavActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusChip(BoardArt.rideHeadphones, !show.muted, stringResource(R.string.go_status_voice), onClick = actions.mute)
        StatusChip(BoardArt.rideHaptic, show.haptics, stringResource(R.string.go_status_haptics), onClick = actions.haptics)
        StatusChip(BoardArt.rideScreenOff, show.background, stringResource(R.string.go_status_screen_off))
        StatusChip(BoardArt.cloudCheck, false, stringResource(R.string.go_status_offline))
    }
}

/** A round status chip: [art] in lime when [on], grey when not; tapping toggles where it is a setting. */
@Composable
internal fun StatusChip(art: Art, on: Boolean, description: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val state = stringResource(if (on) R.string.go_state_on else R.string.go_state_off)
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(RideColours.chip)
            .then(if (onClick != null) Modifier.clickable(role = Role.Switch, onClick = onClick) else Modifier)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        ArtIcon(art, size = 19.dp, tint = if (on) RideColours.lime else RideColours.soft)
    }
}

/** "120 m" as large as the board has it, or the arrival, or off the route. */
@Composable
internal fun BigDistance(show: NavShow) {
    val v = show.view
    val s = v.state
    when {
        v.arrived -> Text(stringResource(R.string.go_notify_arrived), style = Figures.copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold), color = Color.White, textAlign = TextAlign.Center)
        s == null -> Text("–", style = Figures.copy(fontSize = 76.sp, fontWeight = FontWeight.ExtraBold), color = Color.White)
        else -> {
            val d = GoFormat.distance(s.distanceToManeuver, show.units)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${d.value} ${d.unit}" }) {
                Text(d.value, style = Figures.copy(fontSize = 76.sp, lineHeight = 76.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp), color = Color.White)
                Text(" ${d.unit}", style = Figures.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), color = RideColours.soft, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (s.offRoute || v.rerouting) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (v.rerouting) R.string.go_notify_rerouting else R.string.go_off_route_title),
                    style = Atlas.type.bodyStrong, color = NavColours.amber,
                )
            }
        }
    }
}

/**
 * The haptic pattern pill: the rhythm the next turn will be felt as, drawn as its pulses (lime bars as long as each
 * pulse), so riders learn the code by seeing it.
 */
@Composable
fun HapticPill(step: app.atlas.model.Step?, modifier: Modifier = Modifier) {
    val cue = step?.let { Haptics.forManeuver(it.maneuver.type, it.maneuver.turn, urgent = false) } ?: return
    val described = stringResource(R.string.go_haptic_pattern, cue.pulses)
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(RideColours.chip)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = described },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(BoardArt.hapticSmall, size = 18.dp, tint = RideColours.soft)
        for (i in cue.pattern.indices) {
            val amp = cue.amplitudes[i]
            if (amp <= 0) continue
            // A short tap is the board's 26 dp bar at half; a long pulse is twice as wide.
            val w = if (cue.pattern[i] > HapticPlan.TAP_MS) 40.dp else 18.dp
            Box(Modifier.size(width = w, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(RideColours.lime.copy(alpha = 0.45f + 0.55f * amp / 255f)))
        }
        ArtIcon(BoardArt.chevronRight.tinted.weighted(1.3f), size = 16.dp, tint = RideColours.lime)
    }
}

/** The surface and the climb ahead: the next rough stretch with its distance, and the steepest grade in the next kilometre. */
@Composable
private fun AheadTiles(show: NavShow) {
    val v = show.view
    val rough = remember(v.route, (v.travelled / 10).roundToInt()) {
        RouteFacts.nextHazard(v.route, v.travelled, setOf(HazardKind.COBBLES, HazardKind.UNPAVED, HazardKind.STEPS, HazardKind.TRAM_TRACKS))
    }
    val climb = remember(v.route, (v.travelled / 25).roundToInt()) { RouteFacts.climbAhead(v.route, v.travelled) }
    if (rough == null && climb == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Tile(Modifier.weight(1f), "$what, $dist") {
                ArtIcon(BoardArt.cobbles, size = 28.dp)
                Figure(dist.value, dist.unit)
            }
        }
        if (climb != null) {
            val pct = climb.roundToInt().coerceAtLeast(1)
            Tile(Modifier.weight(1f), stringResource(R.string.go_climb_ahead, pct)) {
                ArtIcon(BoardArt.climbWedge, size = 40.dp)
                Figure("$pct", "%", space = false)
            }
        }
    }
}

@Composable
private fun Figure(value: String, unit: String, space: Boolean = true) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = Figures.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = Color.White)
        Text((if (space) " " else "") + unit, style = Figures.copy(fontSize = 14.sp), color = RideColours.soft, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun Tile(modifier: Modifier, description: String, content: @Composable () -> Unit) {
    Row(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(RideColours.chip)
            .padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The route's height ahead as on the Ride board: a lime line over a dark green fill, the steep stretches (5 % or more)
 * in orange, and a white dot where you are. Heights spread over at least 12 m, so a flat ride looks flat.
 */
@Composable
internal fun Profile(route: Route, travelled: Double, units: Units = Units.METRIC) {
    val e = route.elevations
    if (e.size != route.points.size || e.count { !it.isNaN() } < 2) return
    val cum = route.line.cumulative
    val total = cum.last().takeIf { it > 0 } ?: return
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(RideColours.profileBg).padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "" },
    ) {
        val description = stringResource(R.string.go_profile, GoFormat.height(route.climb, units).toString())
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (x in e) if (!x.isNaN()) { lo = minOf(lo, x); hi = maxOf(hi, x) }
            val span = max(hi - lo, 12.0)
            val w = size.width
            val h = size.height
            fun px(i: Int) = (cum[i] / total).toFloat() * w
            fun py(x: Double) = (h - 4f - ((x - lo) / span).toFloat() * (h - 10f))
            val line = Path()
            var started = false
            for (i in e.indices) {
                if (e[i].isNaN()) continue
                if (!started) { line.moveTo(px(i), py(e[i])); started = true } else line.lineTo(px(i), py(e[i]))
            }
            val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(area, RideColours.profileFill)
            drawPath(line, RideColours.lime, style = Stroke(2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
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
            val k = (travelled / total).coerceIn(0.0, 1.0)
            val at = cum.indexOfFirst { it >= travelled }.let { if (it < 0) e.size - 1 else it }
            val y = e.getOrNull(at)?.takeIf { !it.isNaN() } ?: lo
            drawCircle(Color.White, 5.dp.toPx(), Offset((k * w).toFloat(), py(y)))
        }
    }
}

/** The Ride board's footer: the arrival in lime, what is left, the wind, and the end button. */
@Composable
private fun RideFooter(show: NavShow, actions: NavActions) {
    val s = show.view.state
    Row(
        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(28.dp)).background(RideColours.chip).padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", style = Figures.copy(fontSize = 28.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold), color = RideColours.lime)
            Text(GoFormat.distance(s?.remaining ?: show.view.route.distance, show.units).toString(), style = Figures.copy(fontSize = 14.sp), color = RideColours.soft)
        }
        Wind(show)
        Spacer(Modifier.weight(1f))
        EndButton(actions.end, size = 60.dp, background = RideColours.endBg, colour = RideColours.end)
    }
}

/** The round end button of the guidance screens. */
@Composable
internal fun EndButton(onEnd: () -> Unit, size: Dp, background: Color, colour: Color) {
    val end = stringResource(R.string.go_end_trip)
    Box(Modifier.size(size).clip(CircleShape).background(background).button(end, onEnd), contentAlignment = Alignment.Center) {
        ArtIcon(BoardArt.close.tinted.weighted(1.2f), size = 22.dp, tint = colour)
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
        ArtIcon(BoardArt.wind, size = 20.dp, tint = if (against) RideColours.wind else RideColours.soft)
        Text(GoFormat.speed(speed / 3.6, units).value, style = Figures.copy(fontSize = 14.sp), color = RideColours.wind)
    }
}

/** The arrow for the trip's next maneuver (the flag on arrival). */
internal fun arrowOf(v: NavView): ArrowShape {
    if (v.arrived) return ArrowShape(ManeuverType.ARRIVE, Turn.STRAIGHT)
    val m = v.state?.next?.maneuver ?: v.route.steps.getOrNull(1)?.maneuver ?: return ArrowShape(ManeuverType.CONTINUE, Turn.STRAIGHT)
    return ArrowShape(m.type, m.turn, m.exit)
}
