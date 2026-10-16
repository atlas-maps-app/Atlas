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
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.kit.CssShadow

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

    // The board's pool: blue with a white grid every 22 px and two soft lights.
    Box(Modifier.fillMaxSize().background(Color(0xFF9ED3EA)).poolTiles().boardBox(grad = LIGHTS, clip = false)) {
        BoardPage(
            background = Color.Transparent,
            bottomRoom = 72.dp,
            bottom = {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).boardBox(Navy.css(), corners(28f)).clickable { nav.go(Dest.Relax("sauna")) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("place.sauna", tint = Color.White, size = 20.dp)
                    BoardLine(stringResource(R.string.swim_sauna_after), boardStyle(15f, 700, 0xFFFFFFFF, 19.5f))
                }
            },
        ) {
            BoardTitleBar(
                stringResource(R.string.swim_title), onBack = { nav.back() }, italic = false, size = 34, colour = Color.White, backTint = Navy,
                shadow = CssShadow(0f, 2f, 6f, 0f, 0x590C2A40),
            )
            Spacer(Modifier.height(14.dp))
            // The kinds of water: four tiles of 76 px, 8 apart, navy when chosen.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (w in WATERS) {
                    val on = w.id == kind
                    val n = found?.get(w.id)?.places?.size
                    val label = stringResource(w.label) + (n?.let { " $it" } ?: "")
                    val wdesc = stringResource(w.desc)
                    Column(
                        Modifier.weight(1f).height(76.dp).boardBox(if (on) Navy.css() else 0xEBFFFFFF, corners(18f))
                            .clickable(role = androidx.compose.ui.semantics.Role.Tab) { kind = w.id }.semantics { contentDescription = "$wdesc, ${n ?: 0}"; selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Glyph(w.symbol, if (on) Color.White else Navy, 26.dp)
                        BoardLine(label, boardStyle(12f, 800, if (on) 0xFFFFFFFF else Navy.css(), 15.5f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                found == null -> BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 700, 0xFFFFFFFF, 18f))
                first == null -> Box(Modifier.fillMaxWidth().boardBox(0xFFFBF7EE, corners(28f))) {
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
                    Spacer(Modifier.height(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in items.drop(1).take(5)) {
                            val t = f.place.tags
                            val facts = buildList {
                                add(stringResource(if (kind == "indoor") R.string.swim_indoor_fact else R.string.swim_outdoor_fact))
                                when (t["fee"]) { "yes" -> add(stringResource(R.string.swim_paid)); "no" -> add(stringResource(R.string.swim_free)) }
                                t["wheelchair"]?.takeIf { it == "yes" }?.let { add(stringResource(R.string.picnic_step_free)) }
                            }
                            val shut = remember(f, local) { isShut(f.place, here, local) }
                            PlaceCard(
                                f, here, facts, local, SWIM_CARD, caution = shut,
                                tile = { Box(Modifier.size(46.dp).boardBox(0xFFE4F2FA, corners(14f)), contentAlignment = Alignment.Center) { Glyph(chosen.symbol, Blue, 24.dp) } },
                                onClick = { services.remember(f.place); nav.go(Dest.Place.of(f.place)) },
                            )
                        }
                    }
                }
            }
        }
        // The lane rope along the top edge: red and white every 14 px.
        Box(Modifier.fillMaxWidth().height(12.dp).boardBox(grad = listOf(cssRepeatingLinear(90f, 28f, 0f to 0xFFD93A3A, 14f to 0xFFD93A3A, 14f to 0xFFFFFFFF, 28f to 0xFFFFFFFF))))
    }
}

/** The board's swim card: 80 px, radius 22, white at 94 per cent, no shadow. */
private val SWIM_CARD = CardLook(bg = 0xF0FFFFFF, radius = 22f, minHeight = 80f, shadows = emptyList(), ink = 0xFF0C2A40, soft = 0xFF5A6E80, walkSize = 17f, walkLine = 22f)

/** The two soft lights over the pool: ellipses at 30 / 20 per cent (white 35 per cent to clear at 40) and 70 / 70 (25 per cent to clear at 35). */
private val LIGHTS = listOf(
    cssRadialAt(0.3f, 0.2f, 0f to 0x59FFFFFF, 0.4f to 0x00FFFFFF),
    cssRadialAt(0.7f, 0.7f, 0f to 0x40FFFFFF, 0.35f to 0x00FFFFFF),
)

/** The pool tiles: in each 22 px square, a 1.5 px white line at 35 per cent along the left and the bottom. */
private fun Modifier.poolTiles(): Modifier = this.drawBehind {
    val step = 22.dp.toPx()
    val w = 1.5.dp.toPx()
    val line = Color(0x59FFFFFF)
    var x = 0f
    while (x < size.width) { drawRect(line, Offset(x, 0f), Size(w, size.height)); x += step }
    var y = step - w
    while (y < size.height) { drawRect(line, Offset(0f, y), Size(size.width, w)); y += step }
}

/**
 * The board's featured card: 250 px, radius 28, cream with its navy shadow, 18 px in; the 170 px tide clock (or the same
 * brass ring round the kind's symbol where there is no tide), then 16 px on: the name, the water's warmth (or the
 * distance) and the small facts, 10 apart.
 */
@Composable
private fun FeaturedCard(name: String, water: Double?, bits: List<String>, tide: TideNow?, now: java.time.Instant, symbol: String, distance: String) {
    Row(
        Modifier.fillMaxWidth().height(250.dp).boardBox(0xFFFBF7EE, corners(28f), shadow(0f, 14f, 28f, 0x400C2A40), clip = false).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tide != null) TideClock(tide, now, Modifier.size(170.dp))
        else Canvas(Modifier.size(170.dp)) {
            val s = size.width / 170f
            scale(s, s, Offset.Zero) {
                drawCircle(Color(0x260C2A40), 80f, Offset(85f, 87f))
                drawCircle(BRASS, 80f, Offset(85f, 85f))
                drawCircle(Color(0xFF0E3A5C), 70f, Offset(85f, 85f))
            }
            drawSymbol(symbol, Offset(size.width / 2 - 28.dp.toPx(), size.height / 2 - 28.dp.toPx()), 56.dp.toPx(), Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoardPara(name, boardStyle(18f, 800, Navy.css(), 19.8f), lines = 2)
            if (water != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BoardLine("${water.roundToInt()}°", boardStyle(30f, 900, Navy.css(), 39f, tnum = true), Modifier.alignByBaseline())
                BoardLine(stringResource(R.string.swim_water), boardStyle(12f, 700, Muted.css(), 15.5f), Modifier.alignByBaseline())
            } else BoardLine(distance, boardStyle(30f, 900, Navy.css(), 39f, tnum = true))
            if (bits.isNotEmpty()) BoardPara(bits.joinToString(" · "), boardStyle(12f, 400, Muted.css(), 15.5f, tnum = true))
        }
    }
}

/** The board's brass: a diagonal gradient, light, dark and light again. */
private val BRASS = Brush.linearGradient(0f to Color(0xFFF6E2A8), 0.5f to Color(0xFFB98A36), 1f to Color(0xFFE9C977), start = Offset(5f, 5f), end = Offset(165f, 165f))

/**
 * The brass tide clock of the board: HIGH at the top and LOW at the bottom, the hours to them round the face (Geist at
 * 800), the gold hand at where the sea is in its cycle and, while it rises, the way still to come to high water.
 */
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
            drawCircle(BRASS, 80f, c)
            drawCircle(Color(0xFF0E3A5C), 70f, c)
            // The way the sea has still to come to high water: from the hand clockwise to the top.
            val handAngle = (phase * 2 * PI - PI / 2).toFloat()
            val start = Math.toDegrees(handAngle.toDouble()).toFloat()
            val sweep = (270f - start).let { if (it < 0) it + 360f else it }.let { if (tide.rising) it else 0f }
            if (sweep > 0f) drawArc(Color(0x801560A8), start, sweep, true, Offset(15f, 15f), Size(140f, 140f), style = Fill)
            text.draw(this, high, 85f, 34f, 11f, Color.White, anchor = 0.5f, weight = 800)
            text.draw(this, low, 85f, 146f, 11f, Color.White, anchor = 0.5f, weight = 800)
            for ((label, x, y, size) in HOURS) text.draw(this, label, x, y, size, Color.White, anchor = 0.5f, weight = 800)
            drawLine(Color(0xFFFFD24A), c, Offset(c.x + 50f * cos(handAngle), c.y + 50f * sin(handAngle)), 5f, StrokeCap.Round)
            drawCircle(Brush.linearGradient(0f to Color(0xFFF6E2A8), 0.5f to Color(0xFFB98A36), 1f to Color(0xFFE9C977), start = Offset(77f, 77f), end = Offset(93f, 93f)), 8f, c)
        }
    }
}

/** The hours round the board's tide clock: to high water on the left, to low water on the right. */
private data class Hour(val label: String, val x: Float, val y: Float, val size: Float)

private val HOURS = listOf(
    Hour("3", 36f, 89f, 11f), Hour("3", 134f, 89f, 11f), Hour("2", 52f, 54f, 10f), Hour("4", 118f, 54f, 10f), Hour("4", 52f, 124f, 10f), Hour("2", 118f, 124f, 10f),
)
