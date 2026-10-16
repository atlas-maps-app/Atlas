package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.RoadSign
import app.atlas.ui.kit.SignKind
import app.atlas.ui.kit.softShadow
import kotlin.math.roundToInt

private val Figures = TextStyle(fontFamily = SansFamily, fontFeatureSettings = "tnum")

/**
 * Drive: cars, motorbikes and campers (and, with [truck], lorries) on the night road of the Drive board. The maneuver
 * card with the arrow, the distance, the street's blade, the lanes and what comes next; the speed limit and your speed;
 * the voice and the whole route; for a truck, the restriction ahead as its sign against your vehicle (or "route avoids
 * it") and the tachograph's break timer; the last line the voice said; and the arrival.
 */
@Composable
fun DriveView(show: NavShow, actions: NavActions, truck: Boolean = false) {
    AtlasTheme(dark = true, night = true) {
        val v = show.view
        var overview by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(NavColours.night)) {
            GuidanceMap(
                v, Modifier.fillMaxSize(), theme = if (show.night) Theme.NIGHT else Theme.AUTO, follow = !overview,
                padding = PaddingValues(top = 200.dp, bottom = 240.dp),
            )
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 4.dp)) {
                ManeuverCard(show)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SpeedLimit(v.state?.maxspeed, show.units)
                        Speedometer(v.state?.speed ?: 0.0, v.state?.maxspeed, show.units)
                        if (truck) v.tacho?.let { TachoRing(it) }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(BoardArt.voice, stringResource(R.string.go_status_voice), actions.mute, on = !show.muted)
                        GlassButton(BoardArt.overview, stringResource(R.string.go_overview), { overview = !overview }, on = overview)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (truck) Restriction(show, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                VoiceBubble(show.lastSaid)
                Spacer(Modifier.height(12.dp))
                DriveFooter(show, actions, truck)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The dark card at the top: the arrow, the distance, the street's blade, the lanes, and the maneuver after. */
@Composable
private fun ManeuverCard(show: NavShow) {
    val v = show.view
    val s = v.state
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(RoundedCornerShape(28.dp), Color(0x66000000), 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(NavColours.nightPanel)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TurnArrow(arrowOf(v), size = 64.dp, weight = 2.4f / 2.3f, contentDescription = v.instruction)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    v.arrived -> Text(stringResource(R.string.go_notify_arrived), style = Figures.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = Color.White)
                    s != null -> {
                        val d = GoFormat.distance(s.distanceToManeuver, show.units)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(d.value, style = Figures.copy(fontSize = 38.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold), color = Color.White)
                            Text(" ${d.unit}", style = Figures.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = NavColours.textSoft, modifier = Modifier.padding(bottom = 3.dp))
                        }
                    }
                    else -> Text(stringResource(R.string.go_notify_starting), style = Figures.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                }
                val street = v.street
                if (street != null && !v.arrived) StreetSign(street) else v.instruction?.let {
                    Text(it, style = Figures.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            LaneArrows(s?.lanes.orEmpty(), Modifier.align(Alignment.Top))
        }
        val then = v.then
        if (then != null || s?.offRoute == true || v.rerouting) {
            Row(
                Modifier.fillMaxWidth().drawTopRule().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    v.rerouting || s?.offRoute == true -> {
                        ArtIcon(BoardArt.uTurn, size = 16.dp, tint = NavColours.amber)
                        Text(stringResource(if (v.rerouting) R.string.go_notify_rerouting else R.string.go_off_route_title), color = NavColours.amber, style = Figures.copy(fontSize = 14.sp))
                    }
                    then != null -> {
                        TurnArrow(ArrowShape(then.maneuver.type, then.maneuver.turn, then.maneuver.exit), size = 16.dp, colour = NavColours.textSoft, weight = 0.9f)
                        if (then.maneuver.type == ManeuverType.ARRIVE) {
                            ArtIcon(BoardArt.flag, size = 16.dp, tint = Color(0xFFFF8A4C), contentDescription = stringResource(R.string.go_destination))
                            if (s?.arrivalSide == app.atlas.guide.Side.LEFT) ArtIcon(BoardArt.chevronLeft, size = 14.dp, tint = NavColours.textSoft)
                            if (s?.arrivalSide == app.atlas.guide.Side.RIGHT) ArtIcon(BoardArt.chevronLeft.mirrored, size = 14.dp, tint = NavColours.textSoft)
                        }
                        Text(GoFormat.distance(s?.next?.distance ?: 0.0, show.units).toString(), style = Figures.copy(fontSize = 14.sp), color = NavColours.textSoft)
                        then.street?.let { Text(it, style = Figures.copy(fontSize = 14.sp), color = NavColours.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** A hairline across the top of the then-row. */
private fun Modifier.drawTopRule(): Modifier = drawBehind {
    drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
}

/**
 * The street's name blade as the Drive board draws it: white letters on road-sign green, a white rule inset from the
 * edge, and a darker lip below.
 */
@Composable
internal fun StreetSign(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .softShadow(RoundedCornerShape(6.dp), Color(0xFF06381F), 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(NavColours.signGreen)
            .padding(2.dp)
            .border(1.5.dp, Color(0xFFF2F2F2), RoundedCornerShape(5.dp))
            .padding(start = 9.dp, end = 9.dp, top = 3.dp, bottom = 2.dp),
    ) {
        Text(name, style = Figures.copy(fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The posted limit: a European round sign, or with imperial units the white American "SPEED LIMIT" plate of the Drive
 * board; nothing when the road has no limit mapped.
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
    val measurer = rememberTextMeasurer(cacheSize = 4)
    Canvas(Modifier.size(width = 58.dp, height = 72.dp).softShadow(RoundedCornerShape(7.dp), Color(0x80000000), 8.dp).semantics { contentDescription = said }) {
        val r = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
        drawRoundRect(Color.White, cornerRadius = r)
        val inset = 3.dp.toPx()
        drawRoundRect(Color(0xFF111111), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
        val small = TextStyle(fontFamily = SansFamily, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF111111))
        for ((i, word) in listOf("SPEED", "LIMIT").withIndex()) {
            val l = measurer.measure(word, small)
            drawText(l, topLeft = Offset((size.width - l.size.width) / 2f, 9.dp.toPx() + i * 10.dp.toPx()))
        }
        val big = measurer.measure("$mph", small.copy(fontSize = 30.sp, fontWeight = FontWeight.Black))
        drawText(big, topLeft = Offset((size.width - big.size.width) / 2f, 30.dp.toPx()))
    }
}

/** Your speed in a glass disc with the Drive board's blue ring; the ring turns red above the limit. */
@Composable
private fun Speedometer(ms: Double, maxspeed: String?, units: Units) {
    val sp = GoFormat.speed(ms, if (units == Units.NAUTICAL) Units.METRIC else units)
    val over = speedNumber(maxspeed)?.let { (ms * 3.6) > it + 3 } ?: false
    val ring = if (over) NavColours.end else Color(0xFF2E5BFF)
    Box(
        Modifier.size(58.dp).softShadow(CircleShape, Color(0x66000000), 8.dp).clip(CircleShape).background(NavColours.nightButton)
            .semantics(mergeDescendants = true) { contentDescription = sp.toString() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(58.dp)) { drawCircle(ring, size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx())) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(sp.value, style = Figures.copy(fontSize = 22.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold), color = Color.White)
            Text(sp.unit, style = Figures.copy(fontSize = 10.sp, lineHeight = 10.sp), color = NavColours.textSoft)
        }
    }
}

/** A round glass button of the Drive board's right column; [on] lights its symbol. */
@Composable
private fun GlassButton(art: Art, description: String, onClick: () -> Unit, on: Boolean = true) {
    Box(
        Modifier.size(52.dp).softShadow(CircleShape, Color(0x66000000), 8.dp).clip(CircleShape).background(NavColours.nightButton).button(description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        ArtIcon(art, size = 22.dp, tint = if (on) Color.White else NavColours.textSoft)
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

/** The Drive board's footer: the arrival in green, minutes and distance left, the truck's weight, and the end. */
@Composable
private fun DriveFooter(show: NavShow, actions: NavActions, truck: Boolean) {
    val s = show.view.state
    Row(
        Modifier.fillMaxWidth().height(100.dp).softShadow(RoundedCornerShape(30.dp), Color(0x59000000), 16.dp).clip(RoundedCornerShape(30.dp))
            .background(Color(0xF00C1120)).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(30.dp))
            .padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s?.let { GoFormat.clock(it.eta, show.zone) } ?: "–", style = Figures.copy(fontSize = 30.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold), color = NavColours.eta)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s != null) Text(GoFormat.minutes(s.remainingTime), style = Figures.copy(fontSize = 14.sp), color = NavColours.textSoft)
                Text(GoFormat.distance(s?.remaining ?: show.view.route.distance, show.units).toString(), style = Figures.copy(fontSize = 14.sp), color = NavColours.textSoft)
            }
        }
        Spacer(Modifier.weight(1f))
        if (truck) show.vehicle.weightT?.let { WeightChip(it) }
        EndButton(actions.end, size = 60.dp, background = NavColours.end, colour = Color.White)
    }
}

/** The truck's weight as the Drive board's chip: a truck and "40 t". */
@Composable
private fun WeightChip(t: Double) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(NavColours.panelHigh).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(BoardArt.truck, size = 20.dp, tint = Color.White)
        Text(VehicleField.WEIGHT.format(t), style = Figures.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

/**
 * The next limit on the truck's way, from the route's restriction hazards, as the Drive board's chip: its sign, an
 * arrow, your vehicle and its measure, and a green check (fits) or a red cross; for a limit the route was planned
 * around, "route avoids it".
 */
@Composable
private fun Restriction(show: NavShow, modifier: Modifier = Modifier) {
    val v = show.view
    val next = remember(v.route, (v.travelled / 50).roundToInt()) {
        RouteFacts.nextHazard(v.route, v.travelled, RESTRICTIONS) ?: v.route.hazards.firstOrNull { it.kind in RESTRICTIONS && it.tags["avoided"] == "yes" }?.let { it to 0.0 }
    } ?: return
    val (h, d) = next
    val (kind, ours) = signFor(h, show.vehicle) ?: return
    val limit = h.value ?: return
    val avoided = h.tags["avoided"] == "yes"
    val fits = ours == null || ours <= limit
    val label = if (kind == SignKind.WEIGHT) VehicleField.WEIGHT.format(limit) else GoFormat.size(limit, show.units)
    val text = if (avoided) stringResource(R.string.go_route_avoids) else GoFormat.distance(d, show.units).toString()
    Row(
        modifier.height(58.dp).softShadow(RoundedCornerShape(29.dp), Color(0x66000000), 10.dp).clip(RoundedCornerShape(29.dp)).background(NavColours.nightButton)
            .padding(start = 10.dp, end = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $text" },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RoadSign(kind, label, size = 40.dp)
        if (avoided) {
            Text(text, style = Figures.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = NavColours.amber)
            return@Row
        }
        Text(text, style = Figures.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = NavColours.textSoft)
        ArtIcon(BoardArt.arrowRight, size = 18.dp, tint = NavColours.textSoft)
        if (ours != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtIcon(BoardArt.truck, size = 24.dp, tint = Color.White)
                Text(if (kind == SignKind.WEIGHT) VehicleField.WEIGHT.format(ours) else GoFormat.size(ours, show.units), style = Figures.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (fits) Color(0xFF178A4E) else NavColours.end), contentAlignment = Alignment.Center) {
                ArtIcon(if (fits) BoardArt.check.weighted(1.5f) else BoardArt.close.tinted.weighted(1.25f), size = 15.dp, tint = Color.White)
            }
        }
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
 * The tachograph's break timer in miniature, after the Truck board's dial: the block driven in amber, the time left
 * before the break in red, and that time in seven-segment figures.
 */
@Composable
internal fun TachoRing(t: TachoState, size: androidx.compose.ui.unit.Dp = 72.dp) {
    val desc = stringResource(R.string.go_to_break, GoFormat.hoursMinutes(t.toBreak))
    Box(
        Modifier.size(size).softShadow(CircleShape, Color(0x66000000), 8.dp).clip(CircleShape).background(Color(0xFF2A2F38))
            .border(1.5.dp, Color(0xFF4A5260), CircleShape).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) { drawTachoDial(t, ring = 5.dp.toPx(), inset = 6.dp.toPx(), ticks = false) }
        SevenSegment(GoFormat.hoursMinutes(t.toBreak), height = size * 0.24f)
    }
}

/** The Truck board's dial: the track, the block driven in amber from the top, what is left before the break in red. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTachoDial(t: TachoState, ring: Float, inset: Float, ticks: Boolean) {
    val d = size.minDimension - 2 * inset - ring
    val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
    drawArc(Color(0xFF3A404C), -90f, 360f, false, tl, Size(d, d), style = Stroke(ring))
    val driven = t.blockShare
    if (driven > 0f) drawArc(if (t.breakDue) Color(0xFFFF5A3C) else Color(0xFFFFB547), -90f, 360f * driven, false, tl, Size(d, d), style = Stroke(ring))
    // The last half hour before the break shows red, as the dial's remaining arc on the board.
    if (driven < 1f && t.toBreak <= 30 * 60) drawArc(Color(0xFFFF5A3C), -90f + 360f * driven, 360f * (1f - driven), false, tl, Size(d, d), style = Stroke(ring))
    if (ticks) {
        val c = center
        val r0 = size.minDimension / 2f - inset * 0.45f
        for (k in 0 until 9) {
            val a = Math.toRadians(-90.0 + k * 40.0)
            val cx = kotlin.math.cos(a).toFloat()
            val sy = kotlin.math.sin(a).toFloat()
            drawLine(Color(0xFF9AA2B2), Offset(c.x + r0 * cx, c.y + r0 * sy), Offset(c.x + (r0 - inset * 0.6f) * cx, c.y + (r0 - inset * 0.6f) * sy), 2f * size.minDimension / 180f, StrokeCap.Butt)
        }
    }
}
