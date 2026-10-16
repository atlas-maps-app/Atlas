package app.atlas.ui.local

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.Moon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Forecast
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.local.astro.NightView
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithContent
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.cssLinear

private val Navy = Color(0xFF14203A)
private val Slate = Color(0xFF5A6A88)

/** How hard it rains in a quarter hour, as the board's radar colours it. */
internal enum class Intensity(val colour: Color) {
    NONE(Color(0xFFDDE6F0)), LIGHT(Color(0xFF3FCF8E)), MODERATE(Color(0xFFF2C230)), HEAVY(Color(0xFFE8322A));

    companion object {
        fun of(mmPerQuarter: Double): Intensity = when {
            mmPerQuarter < 0.1 -> NONE
            mmPerQuarter < 0.6 -> LIGHT
            mmPerQuarter < 1.6 -> MODERATE
            else -> HEAVY
        }
    }
}

/** The next [count] quarter hours of rain from [from]: millimetres in each, from the forecast's 15-minute series. */
internal fun nowcast(f: Forecast?, from: Instant, count: Int): List<Double> {
    if (f == null) return emptyList()
    return List(count) { i ->
        val t = from.plusSeconds(900L * i)
        f.quarters.firstOrNull { !it.time.isAfter(t) && it.time.plusSeconds(900).isAfter(t) }?.mm
            ?: f.hourAt(t)?.precipitation?.div(4.0)
            ?: 0.0
    }
}

/** The sky: the rain on its way for the next two hours, the space station, the night's planets and the Moon, and a reminder before the rain. */
@Composable
fun SkyScreen(args: Dest.Sky, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.local
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val snap by produceState<SoonSnapshot?>(null, here, now) { value = loadSoon(services, here, now, zone) }
    val f = snap?.forecast
    // A kept forecast that has run out does not reach now: the rain bars and "dry" would be invented.
    val covers = f?.hourAt(now) != null
    val quarters = remember(f, now) { if (covers) nowcast(f, now, 8) else emptyList() }
    val rainMoment = snap?.moments?.firstOrNull { it is Moment.RainStarts || it is Moment.RainStops }
    var reminderAt by remember(rainMoment) { mutableStateOf(hub.reminders.scheduledAt("sky.rain")) }
    val warn = stringResource(R.string.sky_reminder_title)
    val warnText = stringResource(R.string.sky_reminder_text)
    fun toggle() {
        if (hub.reminders.scheduledAt("sky.rain") != null) {
            hub.reminders.cancel("sky.rain"); reminderAt = null
        } else if (rainMoment is Moment.RainStarts) {
            val at = rainMoment.at.minusSeconds(600).takeIf { it.isAfter(now) } ?: now.plusSeconds(60)
            hub.reminders.schedule("sky.rain", at, warn, warnText)
            reminderAt = at
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) toggle() }
    val hm = DateTimeFormatter.ofPattern("H:mm")

    BoardPage(
        background = Color(0xFFE3EAF3),
        bottomRoom = 120.dp,
        bottom = {
            Column {
                // The sources, pinned 92 px from the foot as the board pins them; then the reminder, 56 px of navy.
                BoardPara(stringResource(R.string.sky_source), boardStyle(10f, 400, 0xFF6A7A98, 13f, align = TextAlign.Center), Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                Spacer(Modifier.height(8.dp))
                val canRemind = rainMoment is Moment.RainStarts || reminderAt != null
                if (canRemind) {
                    val label = if (reminderAt != null) stringResource(R.string.sky_reminder_set, hm.format(reminderAt!!.atZone(zone))) else stringResource(R.string.sky_remind)
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).boardBox(Navy.css(), corners(28f))
                            .clickable { if (Build.VERSION.SDK_INT >= 33 && reminderAt == null) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else toggle() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { BoardLine(label, boardStyle(15f, 800, 0xFFFFFFFF, 19.5f)) }
                } else Spacer(Modifier.height(56.dp))
            }
        },
    ) {
        BoardTitleBar(
            stringResource(R.string.sky_title), onBack = { nav.back() }, colour = Navy,
            trailing = {
                val cur = app.atlas.ui.kit.CurrentWeather.of(f, now)
                val wind = f?.now?.let { "${compassName(it.windDirection)} ${it.windSpeed.roundToInt()}" }
                if (cur != null && wind != null) HeaderPill(stringResource(R.string.sky_pill, cur.temperature.roundToInt(), wind), ink = Navy, size = 12f, lineHeight = 15.5f) { nav.go(Dest.Weather()) }
            },
        )
        Spacer(Modifier.height(12.dp))
        RadarCard()
        Spacer(Modifier.height(10.dp))
        Timeline(quarters)
        Spacer(Modifier.height(6.dp))
        val headline = when (rainMoment) {
            is Moment.RainStarts -> if (rainMoment.lasts != null) stringResource(R.string.sky_rain_in_lasts, rainMoment.minutes, rainMoment.lasts) else stringResource(R.string.sky_rain_in, rainMoment.minutes)
            is Moment.RainStops -> stringResource(R.string.sky_raining_until, hm.format(rainMoment.at.atZone(zone)))
            else -> if (!covers) stringResource(R.string.sky_no_forecast) else stringResource(R.string.sky_dry)
        }
        BoardLine(headline, boardStyle(16f, 800, Navy.css(), 20.5f))
        Spacer(Modifier.height(47.5.dp))
        // The board's cards: 72 px, 8 apart.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val outlook = snap?.outlook
            val pass = outlook?.nextVisible
            if (outlook != null) {
                val v = when {
                    pass != null -> stringResource(R.string.moment_station, hm.format(pass.peak.atZone(zone)))
                    outlook.noData -> stringResource(R.string.cosmos_iss_title, 92)
                    else -> stringResource(R.string.sky_station_none)
                }
                val line = when {
                    pass != null -> stringResource(R.string.moment_station_line, max(1, ((pass.visibleDuration.seconds + 30) / 60).toInt()), pass.maxElevationDeg.roundToInt(), compassName(pass.riseAzimuthDeg), compassName(pass.setAzimuthDeg))
                    outlook.noData -> stringResource(R.string.cosmos_iss_nodata)
                    else -> stringResource(R.string.cosmos_iss_none)
                }
                SkyRow("sky.satellite", 0xFF5A7ACA, v, line, trailing = stringResource(R.string.sky_orbit), onClick = { nav.go(Dest.Globe) })
                // Orbit data over ten days old moves a pass by minutes: say so, as the Earth card does.
                if (outlook.orbit?.stale == true) BoardPara(stringResource(R.string.cosmos_iss_stale, outlook.orbit.ageDays.roundToInt()), boardStyle(11f, 400, Slate.css(), 14f), Modifier.padding(horizontal = 4.dp))
            }
            val up = snap?.planets?.filter { it.view != NightView.NOT_UP }.orEmpty()
            val clearFrom = remember(f, now, here) { clearFrom(f, now, zone, here) }
            val tonightTitle = when {
                !covers -> stringResource(R.string.sky_stars)
                clearFrom != null -> stringResource(R.string.sky_clear_from, hm.format(clearFrom.atZone(zone)))
                else -> stringResource(R.string.sky_cloudy_tonight)
            }
            val names = up.take(3).map { planetLabel(it.planet) }.joinToString(", ")
            SkyRow("ui.star.2", Navy.css(), tonightTitle, if (names.isNotEmpty()) names else stringResource(R.string.sky_no_planets), trailing = stringResource(R.string.sky_planets_link), onClick = { nav.go(Dest.Planets) })
            val moon = remember(now) { Moon.phase(now) }
            val times = remember(now, here) { Moon.times(LocalDate.ofInstant(now, zone), here.lat, here.lon, zone) }
            val rise = times.rise?.let { hm.format(it) }
            SkyRow(
                "ui.moon", 0xFF3A3A40, stringResource(if (moon.waxing) R.string.cosmos_moon_title_waxing else R.string.cosmos_moon_title_waning, (moon.illumination * 100).roundToInt()),
                listOfNotNull(rise?.let { stringResource(R.string.sky_moonrise, it) }, times.set?.let { stringResource(R.string.sky_moonset, hm.format(it)) }).joinToString(" · ").ifEmpty { " " },
                trailing = null, onClick = { nav.go(Dest.Moon) },
            )
        }
    }
}

@Composable
internal fun planetLabel(p: app.atlas.ui.local.astro.Planet): String = when (p) {
    app.atlas.ui.local.astro.Planet.MERCURY -> stringResource(R.string.cosmos_planet_mercury); app.atlas.ui.local.astro.Planet.VENUS -> stringResource(R.string.cosmos_planet_venus)
    app.atlas.ui.local.astro.Planet.EARTH -> stringResource(R.string.cosmos_planet_earth); app.atlas.ui.local.astro.Planet.MARS -> stringResource(R.string.cosmos_planet_mars)
    app.atlas.ui.local.astro.Planet.JUPITER -> stringResource(R.string.cosmos_planet_jupiter); app.atlas.ui.local.astro.Planet.SATURN -> stringResource(R.string.cosmos_planet_saturn)
    app.atlas.ui.local.astro.Planet.URANUS -> stringResource(R.string.cosmos_planet_uranus); app.atlas.ui.local.astro.Planet.NEPTUNE -> stringResource(R.string.cosmos_planet_neptune)
}

/** The first hour after dark when the sky is mostly clear (cloud cover under 40 per cent), or null when it stays cloudy. */
internal fun clearFrom(f: Forecast?, now: Instant, zone: ZoneId, here: app.atlas.geo.LatLon): Instant? {
    f ?: return null
    val night = app.atlas.ui.local.astro.Tonight.night(now, here.lat, here.lon, zone) ?: return null
    val from = if (now.isAfter(night.first)) now else night.first
    return f.hours.firstOrNull { !it.time.isBefore(from) && it.time.isBefore(night.second) && it.cloudCover < 40.0 }?.time
}

/** One of the board's sky cards: 72 px, 14 px in, the 44 px tile (radius 14) and its 24 px symbol, title and line 2 apart, a link at the right. */
@Composable
private fun SkyRow(symbol: String, tile: Argb, title: String, line: String, trailing: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).boardBox(0xFFFFFFFF, corners(20f)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).boardBox(tile, corners(14f)), contentAlignment = Alignment.Center) { Symbol(symbol, tint = Color.White, size = 24.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardLine(title, boardStyle(15f, 800, Navy.css(), 19.5f))
            BoardLine(line, boardStyle(12f, 400, Slate.css(), 15.5f, tnum = true))
        }
        if (trailing != null) BoardLine(trailing, boardStyle(12f, 800, Slate.css(), 15.5f))
    }
}

/**
 * The board's radar card, 380 x 260 with radius 22: the street grid, the river's bank and you. The rain field itself
 * comes from radar pictures the app does not fetch, so it is left out; the rain at your spot is on the timeline below.
 */
@Composable
private fun RadarCard() {
    Canvas(Modifier.size(380.dp, 260.dp)) {
        val s = size.width / 380f
        scale(s, s, Offset.Zero) {
            val card = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, 380f, 260f, CornerRadius(22f, 22f))) }
            clipPath(card) {
                drawRect(Color(0xFFE9EEF4))
                for (y in listOf(80f, 180f)) drawLine(Color.White, Offset(0f, y), Offset(380f, y), 6f)
                for (x in listOf(110f, 250f)) drawLine(Color.White, Offset(x, 0f), Offset(x, 260f), 6f)
                drawPath(BANK, Color(0xFFCFE0F0))
            }
            drawCircle(Color(0xFF2E5BFF), 9f, Offset(250f, 120f))
            drawCircle(Color.White, 9f, Offset(250f, 120f), style = Stroke(3f))
        }
    }
}

/** The board's bank: `M0 200c60-20 120 20 180 0s120-30 200-10v70H0z`. */
private val BANK = Path().apply {
    moveTo(0f, 200f)
    cubicTo(60f, 180f, 120f, 220f, 180f, 200f)
    cubicTo(240f, 180f, 300f, 170f, 380f, 190f)
    lineTo(380f, 260f)
    lineTo(0f, 260f)
    close()
}

/**
 * The next hour at your spot, as the board's timeline: an 8 px bar coloured by quarter hour in the radar's colours (hard
 * stops), the 18 px knob at now with its white ring, and now, +15 … +60 under it, 6 px apart.
 */
@Composable
private fun Timeline(quarters: List<Double>) {
    val stops = remember(quarters) {
        val levels = List(4) { i -> quarters.getOrNull(i)?.let { Intensity.of(it) } ?: Intensity.NONE }
        levels.flatMapIndexed { i, l -> listOf(i / 4f to l.colour.css(), (i + 1) / 4f to l.colour.css()) }.toTypedArray()
    }
    val desc = stringResource(R.string.sky_nowcast_desc)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = desc }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .drawWithContent {
                    drawContent()
                    // The knob: 18 px at the bar's start, 5 px above it, ringed by a 3 px white spread.
                    val c = Offset(9.dp.toPx(), 4.dp.toPx())
                    drawCircle(Color.White, 12.dp.toPx(), c)
                    drawCircle(Navy, 9.dp.toPx(), c)
                }
                .boardBox(r = corners(4f), grad = listOf(cssLinear(90f, *stops))),
        )
        Row(Modifier.fillMaxWidth()) {
            val labels = listOf(stringResource(R.string.sky_now), "+15", "+30", "+45", "+60")
            for ((i, l) in labels.withIndex()) {
                BoardLine(l, boardStyle(11f, 800, if (i == 0) Navy.css() else 0xFF7A8AA8, 14f, tnum = true, align = TextAlign.Center), Modifier.weight(1f))
            }
        }
    }
}
