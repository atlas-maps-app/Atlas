package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.Argb
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.mapview.Theme
import app.atlas.model.Hazard
import app.atlas.model.ManeuverType
import app.atlas.model.Vehicle
import app.atlas.route.HazardKind
import app.atlas.services.Units
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.RoadSign
import app.atlas.ui.kit.SignKind
import app.atlas.ui.kit.softShadow
import kotlin.math.roundToInt

private const val GEIST = "Geist, system-ui, sans-serif"
private const val OVERPASS = "Overpass, sans-serif"
private const val SOFT: Argb = 0xFFA9B1C7
private const val NIGHT_BUTTON: Argb = 0xE00C1120

/**
 * Drive: cars, motorbikes and campers (and, with [truck], lorries) on the night road of the Drive board, each element
 * where the board has it. The maneuver card with the arrow, the distance, the street's blade, the lanes and what comes
 * next; the speed limit and your speed; the voice and the whole route; for a truck, the restriction ahead as its sign
 * against your vehicle and the tachograph's break timer; the last line the voice said; and the arrival.
 */
@Composable
fun DriveView(show: NavShow, actions: NavActions, truck: Boolean = false) {
    AtlasTheme(dark = true, night = true) {
        val v = show.view
        var overview by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            GuidanceMap(
                v, Modifier.fillMaxSize(), theme = if (show.night) Theme.NIGHT else Theme.AUTO, follow = !overview,
                padding = PaddingValues(top = 200.dp, bottom = 240.dp), background = NavColours.night,
            )
            BoardPlane(dy = statusShift(40f)) {
                Box(Modifier.offset(12.dp, 40.dp).requiredWidth(388.dp)) { ManeuverCard(show) }
                Column(Modifier.offset(14.dp, 250.dp).requiredWidth(58.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SpeedLimit(v.state?.maxspeed, show.units)
                    Speedometer(v.state?.speed ?: 0.0, v.state?.maxspeed, show.units)
                    if (truck) v.tacho?.let { TachoRing(it, size = 58.dp) }
                }
                Column(Modifier.offset(346.dp, 250.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("ui.volume.3", stringResource(R.string.go_status_voice), actions.mute, on = !show.muted)
                    GlassButton("tab.map", stringResource(R.string.go_overview), { overview = !overview }, on = overview)
                }
                if (truck) Box(Modifier.offset(90.dp, 430.dp)) { Restriction(show) }
            }
            BoardPlane(bottom = true, dy = -navShift(891f)) {
                // The voice's line ends 12 px above the footer, however many lines it takes.
                Box(Modifier.offset(12.dp, 0.dp).requiredSize(388.dp, 779.dp), contentAlignment = Alignment.BottomStart) { VoiceBubble(show.lastSaid) }
                DriveFooter(show, actions, truck)
            }
        }
    }
}

/**
 * The night glass card at the top (388 px wide, 28 px corners, 14 × 16 px padding): the arrow (64 px), the distance
 * with its unit, the street's blade, the lanes at the right; under a hairline, the maneuver after.
 */
@Composable
private fun ManeuverCard(show: NavShow) {
    val v = show.view
    val s = v.state
    Column(
        Modifier
            .fillMaxWidth()
            .cssBox(bg = 0xE60C1120, r = CssCorners(28f, 28f, 28f, 28f), shadows = listOf(CssShadow(0f, 12f, 30f, 0f, 0x66000000), CssShadow(0f, 0f, 0f, 1f, 0x0FFFFFFF, inset = true)), blur = 20f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TurnArrow(arrowOf(v), size = 64.dp, weight = 2.4f / 2.3f, contentDescription = v.instruction)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    v.arrived -> BoardText(stringResource(R.string.go_notify_arrived), boardType(GEIST, 24f, 700, lineHeight = 38f), 0xFFFFFFFF)
                    s != null -> {
                        val d = GoFormat.distance(s.distanceToManeuver, show.units)
                        Row {
                            BoardText(d.value, boardType(GEIST, 38f, 700, lineHeight = 38f, tnum = true), 0xFFFFFFFF, Modifier.alignByBaseline())
                            BoardText(" " + d.unit, boardType(GEIST, 20f, 600, lineHeight = 20f, tnum = true), SOFT, Modifier.alignByBaseline())
                        }
                    }
                    else -> BoardText(stringResource(R.string.go_notify_starting), boardType(GEIST, 17f, 600, lineHeight = 38f), 0xFFFFFFFF)
                }
                val street = v.street
                if (street != null && !v.arrived) StreetSign(street) else v.instruction?.let {
                    BoardText(it, boardType(GEIST, 15f, 600, lineHeight = 19.5f), 0xFFFFFFFF, lines = 2)
                }
            }
            LaneArrows(s?.lanes.orEmpty(), Modifier.align(Alignment.Top))
        }
        val then = v.then
        if (then != null || s?.offRoute == true || v.rerouting) {
            Row(
                Modifier.fillMaxWidth().cssBox(border = CssBorder(1f, 0f, 0f, 0f, longArrayOf(0x14FFFFFF, 0, 0, 0))).padding(top = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val small = boardType(GEIST, 14f, lineHeight = 18f, tnum = true)
                when {
                    v.rerouting || s?.offRoute == true -> {
                        Symbol("nav.turn-back", Modifier.size(16.dp), tint = NavColours.amber, size = 16.dp)
                        BoardText(stringResource(if (v.rerouting) R.string.go_notify_rerouting else R.string.go_off_route_title), small, 0xFFFFB547)
                    }
                    then != null -> {
                        TurnArrow(ArrowShape(then.maneuver.type, then.maneuver.turn, then.maneuver.exit), size = 16.dp, colour = argb(SOFT), weight = 2f / 2.3f)
                        if (then.maneuver.type == ManeuverType.ARRIVE) {
                            Symbol("nav.destination", Modifier.size(16.dp), tint = Color(0xFFFF8A4C), size = 16.dp, contentDescription = stringResource(R.string.go_destination))
                            if (s?.arrivalSide == app.atlas.guide.Side.LEFT) Symbol("ui.back", Modifier.size(14.dp), tint = argb(SOFT), size = 14.dp)
                            if (s?.arrivalSide == app.atlas.guide.Side.RIGHT) Symbol("ui.chevron-right", Modifier.size(14.dp), tint = argb(SOFT), size = 14.dp)
                        }
                        BoardText(GoFormat.distance(s?.next?.distance ?: 0.0, show.units).toString(), small, SOFT)
                        then.street?.let { BoardText(it, small, SOFT, Modifier.weight(1f, fill = false)) }
                    }
                }
            }
        }
    }
}

/**
 * The street's name blade as the Drive board draws it: white Overpass on road-sign green, a white rule 2 px in, a dark
 * lip below; the name 24 px as the board's number, smaller for a long name.
 */
@Composable
internal fun StreetSign(name: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val size = remember(name, density) {
        val max = with(density) { 180.dp.roundToPx() }
        listOf(24f, 22f, 20f, 18f, 16f).firstOrNull { s ->
            measurer.measure(dotted(name, OVERPASS), boardType(OVERPASS, s, 800, lineHeight = s, spacing = s * 0.01f), maxLines = 1, softWrap = false).size.width <= max
        } ?: 15f
    }
    Box(
        modifier
            .cssBox(
                bg = 0xFF0C6B3E, r = CssCorners(6f, 6f, 6f, 6f),
                shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF0C6B3E, inset = true), CssShadow(0f, 0f, 0f, 3.5f, 0xFFF2F2F2, inset = true), CssShadow(0f, 2f, 0f, 0f, 0xFF06381F)),
            )
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 3.dp),
    ) {
        BoardText(name, boardType(OVERPASS, size, 800, lineHeight = 24f, spacing = size * 0.01f), 0xFFFFFFFF, Modifier.widthIn(max = 180.dp))
    }
}

/**
 * The posted limit: a European round sign, or with imperial units the white American "SPEED LIMIT" plate of the Drive
 * board (58 × 72, a black rule 2 px in); nothing when the road has no limit mapped.
 */
@Composable
private fun SpeedLimit(maxspeed: String?, units: Units) {
    val limit = speedNumber(maxspeed) ?: return
    val mph = (limit / 1.609344).roundToInt()
    // What the sign shows is what is said: km/h on the round sign, mph on the American plate.
    val said = stringResource(R.string.go_speed_limit, if (units == Units.IMPERIAL) mph else limit)
    if (units != Units.IMPERIAL) {
        RoadSign(SignKind.SPEED, limit.toString(), size = 58.dp, contentDescription = said)
        return
    }
    Column(
        Modifier
            .size(58.dp, 72.dp)
            .cssBox(bg = 0xFFFFFFFF, r = CssCorners(7f, 7f, 7f, 7f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFFFFFFFF, inset = true), CssShadow(0f, 0f, 0f, 4f, 0xFF111111, inset = true), CssShadow(0f, 6f, 16f, 0f, 0x80000000)))
            .semantics(mergeDescendants = true) { contentDescription = said },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val words = boardType(OVERPASS, 10f, 800, lineHeight = 9.5f, spacing = 0.2f)
        BoardText("SPEED", words, 0xFF111111)
        BoardText("LIMIT", words, 0xFF111111)
        BoardText("$mph", boardType(OVERPASS, 30f, 900, lineHeight = 28.5f), 0xFF111111, Modifier.padding(top = 2.dp))
    }
}

/** Your speed in the Drive board's glass disc (58 px) with its blue ring 3 px in; the ring turns red above the limit. */
@Composable
private fun Speedometer(ms: Double, maxspeed: String?, units: Units) {
    val sp = GoFormat.speed(ms, if (units == Units.NAUTICAL) Units.METRIC else units)
    val over = speedNumber(maxspeed)?.let { (ms * 3.6) > it + 3 } ?: false
    Column(
        Modifier.size(58.dp)
            .cssBox(bg = NIGHT_BUTTON, r = CssCorners(29f, 29f, 29f, 29f), shadows = listOf(CssShadow(0f, 0f, 0f, 3f, if (over) 0xFFD93A3A else 0xFF2E5BFF, inset = true), CssShadow(0f, 6f, 16f, 0f, 0x66000000)))
            .semantics(mergeDescendants = true) { contentDescription = sp.toString() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        BoardText(sp.value, boardType(GEIST, 22f, 700, lineHeight = 22f, tnum = true), 0xFFFFFFFF)
        BoardText(sp.unit, boardType(GEIST, 10f, lineHeight = 10f), SOFT)
    }
}

/** A round glass button of the Drive board's right column (52 px); [on] lights its symbol. */
@Composable
private fun GlassButton(symbol: String, description: String, onClick: () -> Unit, on: Boolean = true) {
    Box(
        Modifier.size(52.dp).cssBox(bg = NIGHT_BUTTON, r = CssCorners(26f, 26f, 26f, 26f), shadows = listOf(CssShadow(0f, 6f, 16f, 0f, 0x66000000))).button(description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, Modifier.size(22.dp), tint = if (on) Color.White else argb(SOFT), size = 22.dp)
    }
}

/** The limit in km/h of an OSM maxspeed ("50", "30 mph" in km/h, "NL:urban" unknown). */
internal fun speedNumber(maxspeed: String?): Int? {
    val m = maxspeed?.trim() ?: return null
    m.toIntOrNull()?.let { return it }
    MPH.find(m)?.let { return (it.groupValues[1].toInt() * 1.609344).roundToInt() }
    KMH.find(m)?.let { return it.groupValues[1].toInt() }
    return null
}

private val MPH = Regex("""^(\d{1,3})\s*mph$""", RegexOption.IGNORE_CASE)
private val KMH = Regex("""^(\d{1,3})\s*km/?h$""", RegexOption.IGNORE_CASE)

/**
 * The Drive board's footer (388 × 100 px, 24 px above the bottom): the arrival in green, minutes and distance left, the
 * truck's weight, and the end.
 */
@Composable
private fun DriveFooter(show: NavShow, actions: NavActions, truck: Boolean) {
    val s = show.view.state
    Row(
        Modifier.offset(12.dp, 791.dp).requiredSize(388.dp, 100.dp)
            .cssBox(bg = 0xF00C1120, r = CssCorners(30f, 30f, 30f, 30f), shadows = listOf(CssShadow(0f, -4f, 30f, 0f, 0x59000000), CssShadow(0f, 0f, 0f, 1f, 0x0FFFFFFF, inset = true)), blur = 20f)
            .padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoardText(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", boardType(GEIST, 30f, 700, lineHeight = 30f, tnum = true), 0xFF7CE0A5)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val small = boardType(GEIST, 14f, lineHeight = 18f, tnum = true)
                if (s != null) BoardText(GoFormat.minutes(s.remainingTime), small, SOFT)
                BoardText(GoFormat.distance(s?.remaining ?: show.view.route.distance, show.units).toString(), small, SOFT)
            }
        }
        Spacer(Modifier.weight(1f))
        if (truck) show.vehicle.weightT?.let { WeightChip(it) }
        EndButton(actions.end, size = 60.dp, background = NavColours.end, colour = Color.White, cross = 24.dp)
    }
}

/** The truck's weight as the Drive board's chip: a truck and "40 t" on the deep blue pill. */
@Composable
private fun WeightChip(t: Double) {
    Row(
        Modifier.height(36.dp).cssBox(bg = 0xFF1A2238, r = CssCorners(18f, 18f, 18f, 18f)).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("mode.truck.2", Modifier.size(20.dp), tint = Color.White, size = 20.dp, weight = 1.8f / 1.9f)
        BoardText(VehicleField.WEIGHT.format(t), boardType(GEIST, 13f, 600, lineHeight = 17f, tnum = true), 0xFFFFFFFF)
    }
}

/**
 * The next limit on the truck's way, from the route's restriction hazards, as the Drive board's chip: its sign (the
 * American yellow diamond in imperial units), an arrow, your vehicle and its measure, and a green check (it fits, or the
 * route was planned around it) or a red cross.
 */
@Composable
private fun Restriction(show: NavShow) {
    val v = show.view
    val next = remember(v.route, (v.travelled / 50).roundToInt()) {
        RouteFacts.nextHazard(v.route, v.travelled, RESTRICTIONS) ?: v.route.hazards.firstOrNull { it.kind in RESTRICTIONS && it.tags["avoided"] == "yes" }?.let { it to 0.0 }
    } ?: return
    val (h, d) = next
    val (kind, ours) = signFor(h, show.vehicle) ?: return
    val limit = h.value ?: return
    val avoided = h.tags["avoided"] == "yes"
    val fits = avoided || ours == null || ours <= limit
    val label = if (kind == SignKind.WEIGHT) VehicleField.WEIGHT.format(limit) else GoFormat.size(limit, show.units)
    val where = if (avoided) stringResource(R.string.go_route_avoids) else GoFormat.distance(d, show.units).toString()
    Row(
        Modifier.height(58.dp)
            .cssBox(bg = NIGHT_BUTTON, r = CssCorners(29f, 29f, 29f, 29f), shadows = listOf(CssShadow(0f, 8f, 20f, 0f, 0x66000000)), blur = 16f)
            .padding(start = 10.dp, end = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $where" },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (show.units == Units.IMPERIAL && kind != SignKind.WEIGHT) Diamond(label) else RoadSign(kind, label, size = 40.dp)
        Symbol("ui.arrow-right.2", Modifier.size(18.dp), tint = argb(SOFT), size = 18.dp)
        if (ours != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("mode.truck.2", Modifier.size(24.dp), tint = Color.White, size = 24.dp, weight = 1.8f / 1.9f)
                BoardText(if (kind == SignKind.WEIGHT) VehicleField.WEIGHT.format(ours) else GoFormat.size(ours, show.units), boardType(GEIST, 14f, 600, lineHeight = 18f, tnum = true), 0xFFFFFFFF)
            }
        }
        Box(Modifier.size(26.dp).cssBox(bg = if (fits) 0xFF178A4E else 0xFFD93A3A, r = CssCorners(13f, 13f, 13f, 13f)), contentAlignment = Alignment.Center) {
            Symbol(if (fits) "ui.check" else "ui.close", Modifier.size(15.dp), tint = Color.White, size = 15.dp, weight = 3f / (if (fits) 2.8f else 3f))
        }
    }
}

/** The American warning diamond of the Drive board: black, yellow 2 px in, the clearance in 10 px black Overpass. */
@Composable
private fun Diamond(label: String) {
    Box(Modifier.size(40.dp).drawWithCache {
        fun diamond(inset: Float) = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width / 2, inset); lineTo(size.width - inset, size.height / 2); lineTo(size.width / 2, size.height - inset); lineTo(inset, size.height / 2); close()
        }
        val outer = diamond(0f)
        val inner = diamond(2.dp.toPx())
        onDrawBehind {
            drawPath(outer, Color(0xFF111111))
            drawPath(inner, Color(0xFFF7C600))
        }
    }, contentAlignment = Alignment.Center) {
        BoardText(label, boardType(OVERPASS, 10f, 900, lineHeight = 10f), 0xFF111111)
    }
}

private val RESTRICTIONS = setOf(HazardKind.MAXHEIGHT, HazardKind.MAXWEIGHT, HazardKind.MAXWIDTH, HazardKind.MAXLENGTH, HazardKind.HEIGHT_RESTRICTOR, HazardKind.MAXAXLELOAD)

/** The sign for a restriction hazard and the vehicle's own measure of what it limits. */
private fun signFor(h: Hazard, v: Vehicle): Pair<SignKind, Double?>? = when (h.kind) {
    HazardKind.MAXHEIGHT, HazardKind.HEIGHT_RESTRICTOR -> SignKind.HEIGHT to v.heightM
    HazardKind.MAXWEIGHT -> SignKind.WEIGHT to v.weightT
    HazardKind.MAXAXLELOAD -> SignKind.WEIGHT to v.axleLoadT
    HazardKind.MAXWIDTH -> SignKind.WIDTH to v.widthM
    HazardKind.MAXLENGTH -> SignKind.LENGTH to v.lengthM
    else -> null
}

/**
 * The tachograph's break timer in miniature, the Truck board's dial at [size] in the Drive board's left column (with
 * its glass disc's shadow): the block driven in amber, the time left before the break in red when it is short, and
 * that time in the dial's amber VT323.
 */
@Composable
internal fun TachoRing(t: TachoState, size: androidx.compose.ui.unit.Dp = 72.dp) {
    val desc = stringResource(R.string.go_to_break, GoFormat.hoursMinutes(t.toBreak))
    Box(
        Modifier.size(size).cssBox(r = CssCorners(size.value / 2, size.value / 2, size.value / 2, size.value / 2), shadows = listOf(CssShadow(0f, 6f, 16f, 0f, 0x66000000)))
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) { drawBoardTacho(t, this.size.minDimension / 180f) }
        BoardText(GoFormat.hoursMinutes(t.toBreak), boardType("VT323, monospace", maxOf(15f, 36f * size.value / 180f), lineHeight = maxOf(15f, 36f * size.value / 180f)), TruckColours.AMBER)
    }
}

