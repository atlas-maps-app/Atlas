package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.TagFilter
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Navy = Color(0xFF0C2A40)
private val Blue = Color(0xFF1560A8)
private val Muted = Color(0xFF5A6E80)

private class Water(val id: String, val filter: String, val symbol: String, val label: Int, val desc: Int)

private val WATERS = listOf(
    Water("indoor", "(leisure=swimming_pool or (leisure=sports_centre and sport=swimming)) and (indoor=yes or location=indoor or building) and not access=private and not access=no", "place.pool", R.string.swim_indoor, R.string.swim_indoor_desc),
    Water("outdoor", "leisure=swimming_pool and not indoor=yes and not location=indoor and not building and not access=private and not access=no", "place.pool.2", R.string.swim_outdoor, R.string.swim_outdoor_desc),
    Water("lakes", "leisure=bathing_place or leisure=swimming_area or (natural=water and (swimming=yes or sport=swimming))", "place.lake", R.string.swim_lakes, R.string.swim_lakes_desc),
    Water("sea", "natural=beach or leisure=beach_resort", "place.beach", R.string.swim_sea, R.string.swim_sea_desc),
)

private const val RADIUS = 6_000.0

/**
 * Swim: indoor pools, outdoor pools, lakes and the sea within 6 km, the nearest of the chosen kind on a card with its water
 * temperature and a brass tide clock where the sea has tides (Open-Meteo's marine model), and a list below.
 */
@Composable
fun SwimScreen(args: Dest.Swim, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val local = remember(clock) { LocalDateTime.ofInstant(now, zone) }
    var kind by rememberSaveable { mutableStateOf(args.kind?.takeIf { k -> WATERS.any { it.id == k } } ?: "indoor") }
    val found by produceState<Map<String, FindResult>?>(null, here) { value = services.local.finder.findMany(WATERS.associate { it.id to TagFilter.parse(it.filter) }, here, RADIUS, limit = 60) }
    val marine by produceState<app.atlas.providers.MarineForecast?>(null, here) { value = services.local.marine(here) }
    val tide = remember(marine, now) { TideNow.of(marine, now) }
    val chosen = WATERS.first { it.id == kind }
    val items = found?.get(kind)?.places.orEmpty()
    val first = items.firstOrNull()
    val hm = DateTimeFormatter.ofPattern("H:mm")

    Box(Modifier.fillMaxSize().background(Color(0xFF9ED3EA)).poolTiles()) {
        BoardPage(
            background = Color.Transparent,
            bottomRoom = 72.dp,
            bottom = {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(Navy).clickable { nav.go(Dest.Relax("sauna")) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("place.sauna", tint = Color.White, size = 20.dp)
                    Text(stringResource(R.string.swim_sauna_after), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
        ) {
            BoardTitleBar(stringResource(R.string.swim_title), onBack = { nav.back() }, italic = false, size = 34, colour = Color.White)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (w in WATERS) {
                    val on = w.id == kind
                    val n = found?.get(w.id)?.places?.size
                    val label = stringResource(w.label) + (n?.let { " $it" } ?: "")
                    val wdesc = stringResource(w.desc)
                    Column(
                        Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Navy else Color.White.copy(alpha = 0.92f))
                            .clickable(role = androidx.compose.ui.semantics.Role.Tab) { kind = w.id }.semantics { contentDescription = "$wdesc, ${n ?: 0}"; selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Glyph(w.symbol, if (on) Color.White else Navy, 26.dp)
                        Text(label, color = if (on) Color.White else Navy, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            when {
                found == null -> Text(stringResource(R.string.kit_loading), color = Color.White, fontSize = 14.sp)
                first == null -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFFFBF7EE))) {
                    val err = found?.get(kind)?.error
                    StateMessage(
                        if (err != null) "ui-cloud-off" else chosen.symbol, stringResource(if (err != null) R.string.swim_offline else R.string.swim_none, app.atlas.ui.kit.shortDistance(RADIUS)),
                        text = if (err == null && !services.local.online()) stringResource(R.string.picnic_offline_text) else null, tint = if (err != null) Color(0xFFC8322A) else Muted,
                    )
                }
                else -> {
                    val name = app.atlas.places.Localise.name(first.place.tags, java.util.Locale.getDefault().language) ?: stringResource(chosen.label)
                    val water = marine?.now?.waterTemperature?.takeIf { it.isFinite() && kind in setOf("lakes", "sea") }
                    val tags = first.place.tags
                    val bits = buildList {
                        if (tide?.nextHigh != null && kind == "sea") add(stringResource(R.string.swim_high_tide, hm.format(tide.nextHigh.atZone(zone))))
                        when (tags["lifeguard"]) { "yes" -> add(stringResource(R.string.swim_lifeguard)); "no" -> add(stringResource(R.string.swim_no_lifeguard)) }
                        when (tags["fee"]) { "yes" -> add(stringResource(R.string.swim_paid)); "no" -> add(stringResource(R.string.swim_free)) }
                    }
                    FeaturedCard(name, water, bits, tide.takeIf { kind == "sea" || kind == "lakes" }, now, chosen.symbol, app.atlas.ui.kit.shortDistance(first.distanceM))
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in items.drop(1).take(5)) {
                            val t = f.place.tags
                            val facts = buildList {
                                add(stringResource(if (kind == "indoor") R.string.swim_indoor_fact else R.string.swim_outdoor_fact))
                                when (t["fee"]) { "yes" -> add(stringResource(R.string.swim_paid)); "no" -> add(stringResource(R.string.swim_free)) }
                                t["wheelchair"]?.takeIf { it == "yes" }?.let { add(stringResource(R.string.picnic_step_free)) }
                            }
                            PlaceCard(f, here, facts, local, background = Color.White.copy(alpha = 0.94f), ink = Navy, soft = Muted, symbol = chosen.symbol, tile = Blue, height = 80, onClick = { services.remember(f.place); nav.go(Dest.Place.of(f.place)) })
                        }
                    }
                }
            }
        }
        // The lane rope along the top edge.
        Box(Modifier.fillMaxWidth().height(12.dp).drawBehind {
            var x = 0f
            var red = true
            while (x < size.width) { drawRect(if (red) Color(0xFFD93A3A) else Color.White, Offset(x, 0f), Size(14.dp.toPx(), size.height)); x += 14.dp.toPx(); red = !red }
        })
    }
}

/** The pool tiles: a white grid every 22 dp and two soft lights. */
private fun Modifier.poolTiles(): Modifier = this.drawBehind {
    val step = 22.dp.toPx()
    val line = Color(0x59FFFFFF)
    var x = 0f
    while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx()); x += step }
    var y = 0f
    while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx()); y += step }
    drawCircle(Brush.radialGradient(listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)), center = Offset(size.width * 0.3f, size.height * 0.2f), radius = size.width * 0.5f), size.width * 0.5f, Offset(size.width * 0.3f, size.height * 0.2f))
}

@Composable
private fun FeaturedCard(name: String, water: Double?, bits: List<String>, tide: TideNow?, now: java.time.Instant, symbol: String, distance: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFFFBF7EE)).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tide != null) TideClock(tide, now, Modifier.size(150.dp))
        else Box(Modifier.size(110.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF6E2A8), Color(0xFFB98A36), Color(0xFFE9C977)))).padding(8.dp).clip(CircleShape).background(Color(0xFF0E3A5C)), contentAlignment = Alignment.Center) {
            Glyph(symbol, Color.White, 44.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = Navy, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (water != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${water.roundToInt()}°", color = Navy, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.swim_water), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
            } else Text(distance, color = Navy, fontSize = 26.sp, fontWeight = FontWeight.Black)
            if (bits.isNotEmpty()) Text(bits.joinToString(" · "), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** The brass tide clock of the board: HIGH at the top, LOW at the bottom, the gold hand at where the sea is in its cycle. */
@Composable
private fun TideClock(tide: TideNow, now: java.time.Instant, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, SansFamily) }
    val high = stringResource(R.string.swim_clock_high)
    val low = stringResource(R.string.swim_clock_low)
    val phase = tide.phase(now)
    val desc = stringResource(if (tide.rising) R.string.swim_tide_rising else R.string.swim_tide_falling)
    Canvas(modifier.semantics { contentDescription = desc }) {
        val s = size.width / 170f
        scale(s, s, Offset.Zero) {
            val c = Offset(85f, 85f)
            drawCircle(Color(0x260C2A40), 80f, Offset(85f, 87f))
            drawCircle(Brush.linearGradient(listOf(Color(0xFFF6E2A8), Color(0xFFB98A36), Color(0xFFE9C977)), Offset(0f, 0f), Offset(170f, 170f)), 80f, c)
            drawCircle(Color(0xFF0E3A5C), 70f, c)
            // The way the sea has still to come to high water: from the hand clockwise to the top.
            val handAngle = (phase * 2 * PI - PI / 2).toFloat()
            val start = Math.toDegrees(handAngle.toDouble()).toFloat()
            val sweep = (270f - start).let { if (it < 0) it + 360f else it }.let { if (tide.rising) it else 0f }
            if (sweep > 0f) drawArc(Color(0x801560A8), start, sweep, true, Offset(15f, 15f), Size(140f, 140f), style = Fill)
            text.draw(this, high, 85f, 34f, 11f, Color.White, bold = true, anchor = 0.5f)
            text.draw(this, low, 85f, 146f, 11f, Color.White, bold = true, anchor = 0.5f)
            for ((label, ang) in listOf("3" to 0.0, "2" to 45.0, "4" to 135.0)) {
                val x = 85f + 49f * cos(Math.toRadians(ang)).toFloat()
                val y = 89f + 49f * sin(Math.toRadians(ang)).toFloat()
                text.draw(this, label, x, y, 10f, Color.White, bold = true, anchor = 0.5f)
            }
            drawLine(Color(0xFFFFD24A), c, Offset(c.x + 42f * cos(handAngle), c.y + 42f * sin(handAngle)), 5f, StrokeCap.Round)
            drawCircle(Brush.linearGradient(listOf(Color(0xFFF6E2A8), Color(0xFFB98A36), Color(0xFFE9C977)), Offset(77f, 77f), Offset(93f, 93f)), 8f, c)
        }
    }
}
