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
        bottomRoom = 76.dp,
        bottom = {
            val canRemind = rainMoment is Moment.RainStarts || reminderAt != null
            if (canRemind) {
                val label = if (reminderAt != null) stringResource(R.string.sky_reminder_set, hm.format(reminderAt!!.atZone(zone))) else stringResource(R.string.sky_remind)
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Navy)
                        .clickable { if (Build.VERSION.SDK_INT >= 33 && reminderAt == null) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else toggle() },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(if (reminderAt != null) "ui.check" else "ui.bell", tint = Color.White, size = 20.dp)
                    Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) {
        BoardTitleBar(
            stringResource(R.string.sky_title), onBack = { nav.back() }, colour = Navy,
            trailing = {
                val cur = app.atlas.ui.kit.CurrentWeather.of(f, now)
                val wind = f?.now?.let { "${compassName(it.windDirection)} ${it.windSpeed.roundToInt()}" }
                if (cur != null && wind != null) HeaderPill(stringResource(R.string.sky_pill, cur.temperature.roundToInt(), wind), ink = Navy) { nav.go(Dest.Weather()) }
            },
        )
        Spacer(Modifier.height(12.dp))
        NowcastCard(quarters, covers)
        Spacer(Modifier.height(10.dp))
        val headline = when (rainMoment) {
            is Moment.RainStarts -> if (rainMoment.lasts != null) stringResource(R.string.sky_rain_in_lasts, rainMoment.minutes, rainMoment.lasts) else stringResource(R.string.sky_rain_in, rainMoment.minutes)
            is Moment.RainStops -> stringResource(R.string.sky_raining_until, hm.format(rainMoment.at.atZone(zone)))
            else -> if (!covers) stringResource(R.string.sky_no_forecast) else stringResource(R.string.sky_dry)
        }
        Text(headline, color = Navy, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(14.dp))
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
                SkyRow("sky.satellite", Color(0xFF5A7ACA), v, line, trailing = stringResource(R.string.sky_orbit), onClick = { nav.go(Dest.Globe) })
                // Orbit data over ten days old moves a pass by minutes: say so, as the Earth card does.
                if (outlook.orbit?.stale == true) Text(stringResource(R.string.cosmos_iss_stale, outlook.orbit.ageDays.roundToInt()), color = Slate, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
            val up = snap?.planets?.filter { it.view != NightView.NOT_UP }.orEmpty()
            val clearFrom = remember(f, now, here) { clearFrom(f, now, zone, here) }
            val tonightTitle = when {
                !covers -> stringResource(R.string.sky_stars)
                clearFrom != null -> stringResource(R.string.sky_clear_from, hm.format(clearFrom.atZone(zone)))
                else -> stringResource(R.string.sky_cloudy_tonight)
            }
            val names = up.take(3).map { planetLabel(it.planet) }.joinToString(", ")
            SkyRow("ui.star", Navy, tonightTitle, if (names.isNotEmpty()) names else stringResource(R.string.sky_no_planets), trailing = stringResource(R.string.sky_planets_link), onClick = { nav.go(Dest.Planets) })
            val moon = remember(now) { Moon.phase(now) }
            val times = remember(now, here) { Moon.times(LocalDate.ofInstant(now, zone), here.lat, here.lon, zone) }
            val rise = times.rise?.let { hm.format(it) }
            SkyRow(
                "ui.moon", Color(0xFF3A3A40), stringResource(if (moon.waxing) R.string.cosmos_moon_title_waxing else R.string.cosmos_moon_title_waning, (moon.illumination * 100).roundToInt()),
                listOfNotNull(rise?.let { stringResource(R.string.sky_moonrise, it) }, times.set?.let { stringResource(R.string.sky_moonset, hm.format(it)) }).joinToString(" · ").ifEmpty { " " },
                trailing = null, onClick = { nav.go(Dest.Moon) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.sky_source), color = Color(0xFF6A7A98), fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), lineHeight = 14.sp)
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

@Composable
private fun SkyRow(symbol: String, tile: Color, title: String, line: String, trailing: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tile), contentAlignment = Alignment.Center) { Symbol(symbol, tint = Color.White, size = 24.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Navy, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = Slate, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = Slate, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** The next two hours of rain at your spot: a bar for each quarter hour in the radar's colours, you at the left. */
@Composable
private fun NowcastCard(quarters: List<Double>, haveForecast: Boolean) {
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, SansFamily) }
    val labels = listOf(stringResource(R.string.sky_now), "+30", "+60", "+90", "+120")
    val desc = stringResource(R.string.sky_nowcast_desc)
    Canvas(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(22.dp)).semantics { contentDescription = desc }) {
        val s = size.width / 380f
        scale(s, s, Offset.Zero) {
            drawRect(Color(0xFFE9EEF4))
            // The street-map grid of the board's radar card, in white.
            for (y in listOf(80f, 180f)) drawLine(Color.White, Offset(0f, y), Offset(380f, y), 6f)
            for (x in listOf(110f, 250f)) drawLine(Color.White, Offset(x, 0f), Offset(x, 260f), 6f)
            drawRect(Color(0xFFCFE0F0), Offset(0f, 215f), Size(380f, 45f))
            if (quarters.isEmpty()) return@scale
            val n = quarters.size
            val slot = 340f / n
            for ((i, mm) in quarters.withIndex()) {
                val level = Intensity.of(mm)
                val h = if (level == Intensity.NONE) 6f else (24f + (mm / 2.0).coerceIn(0.0, 1.0).toFloat() * 150f)
                drawRoundRect(level.colour.copy(alpha = if (level == Intensity.NONE) 0.9f else 0.95f), Offset(20f + slot * i + 4f, 200f - h), Size(slot - 8f, h), CornerRadius(6f, 6f))
            }
            // You, at the foot of the first bar.
            drawCircle(Color.White, 12f, Offset(40f, 226f))
            drawCircle(Color(0xFF2E5BFF), 9f, Offset(40f, 226f))
            for ((i, l) in labels.withIndex()) text.draw(this, l, 20f + 340f * i / 4f, 252f, 11f, if (i == 0) Navy else Color(0xFF7A8AA8), bold = true, anchor = if (i == 0) 0f else 0.5f)
        }
    }
    if (!haveForecast) Text(stringResource(R.string.sky_no_forecast), color = Slate, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}
