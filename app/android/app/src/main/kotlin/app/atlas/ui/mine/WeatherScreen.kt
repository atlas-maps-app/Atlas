package app.atlas.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.geo.Moon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Forecast
import app.atlas.providers.WeatherHour
import app.atlas.services.Units
import app.atlas.ui.kit.CurrentWeather
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.skySymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val WInk = Color(0xFF14203A)
private val WSoft = Color(0xFF5A6A88)
private val Rain = Color(0xFF3E7BC4)

/**
 * The Weather board: the brass barometer (the phone's own, else the forecast's pressure), now in words, the next hours
 * with their rain, what it means for you (from the forecast's numbers), and the sun and moon. With no signal it shows
 * the forecast kept on the phone with its age.
 */
@Composable
fun WeatherScreen(args: Dest.Weather, nav: Navigator) {
    val services = LocalServices.current
    val context = LocalContext.current
    val here = rememberHere()
    val at = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    var forecast by remember(at) { mutableStateOf<Forecast?>(null) }
    var done by remember(at) { mutableStateOf(false) }
    var live by remember { mutableStateOf<Double?>(null) }
    var cafe by remember(at) { mutableStateOf<app.atlas.model.Place?>(null) }
    LaunchedEffect(at) {
        forecast = withContext(Dispatchers.IO) {
            runCatching {
                if (services.config.offline || !services.connectivity.online.value) services.weather.kept(at) else services.weather.forecast(at)
            }.getOrNull()
                // The network said online but the answer failed (a captive portal, a server error): the kept forecast still serves.
                ?: runCatching { services.weather.kept(at) }.getOrNull()
        }
        done = true
    }
    LaunchedEffect(Unit) {
        live = withContext(Dispatchers.IO) {
            Barometers.read(context)?.also { hPa ->
                // Android gives no barometer readings to the background worker, so a reading taken here (the screen is open)
                // also goes into the log when "pressure for the forecast" is on.
                if (services.settings.flow(MineKeys.PRESSURE, false).first()) services.mine.pressure.addIfDue(hPa, services.clock.instant())
            }
        }
    }
    val clock = appClock()
    val now = clock.instant()
    val f = forecast
    val advice = remember(f, now) { f?.let { WeatherAdvice.of(it, now) }.orEmpty() }
    LaunchedEffect(at, advice.any { it.kind == Advice.Kind.RAIN_SOON || it.kind == Advice.Kind.RAIN_NOW }) {
        if (advice.any { it.kind == Advice.Kind.RAIN_SOON || it.kind == Advice.Kind.RAIN_NOW }) {
            cafe = withContext(Dispatchers.IO) { runCatching { services.places.nearby("cafe", at, 800.0, 3).first { it.places.isNotEmpty() || it.error != null }.places.firstOrNull() }.getOrNull() }
        }
    }
    val log = remember(f, live) { services.mine.pressure.readings() }
    val barometer = remember(log, live, f) {
        val readings = if (live != null) log + PressureReading(now, live!!) else log
        Barometer.of(readings, now, fallback = f?.now?.pressureMsl?.takeIf { it.isFinite() })
    }
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val fahrenheit by services.settings.flow(MineKeys.TEMP_F, false).collectAsStateWithLifecycle(false)
    val twelve by services.settings.flow(MineKeys.CLOCK_12, false).collectAsStateWithLifecycle(false)
    // The hours of the forecast are the place's own: its clock, not the phone's, when they differ (a place you look at from afar).
    val zone: ZoneId = f?.let { ZoneOffset.ofTotalSeconds(it.utcOffsetSeconds) } ?: clock.zone
    fun hm(i: Instant): String = ZonedLocal.hm(i, zone, twelve)
    val current = CurrentWeather.of(f, now)
    BoardPage(
        title = stringResource(R.string.mine_weather),
        onBack = { nav.back() },
        background = Color(0xFFE8EEF6),
        backgroundBrush = Brush.verticalGradient(0f to Color(0xFFC9D8EC), 0.45f to Color(0xFFE8EEF6), 1f to Color(0xFFE8EEF6)),
        ink = WInk,
        titleSize = 34f,
        gap = 14.dp,
        trailing = {
            Box(Modifier.height(34.dp).clip(CircleShape).background(Color.White).clickable { nav.go(Dest.Forecast) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.mine_weather_models), style = mt(12f, 800), color = WInk)
            }
        },
    ) {
        when {
            f != null && current != null -> {
                NowCard(f, current, barometer, live != null || log.isNotEmpty(), ::hm, { ZonedLocal.hm(it, clock.zone, twelve) }, units, fahrenheit, now, onModels = { nav.go(Dest.Forecast) })
                HoursStrip(f, now, zone, twelve, fahrenheit)
                if (advice.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.mine_weather_means), Modifier.padding(start = 2.dp), WSoft)
                    for (a in advice) AdviceRow(a, f, ::hm, units, cafe?.name(), onCafe = cafe?.let { c -> { nav.go(Dest.Place.of(c)) } })
                }
                SunStrip(at, zone, now, twelve)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutLink("water.flowing", stringResource(R.string.mine_water_title), Modifier.weight(1f)) { nav.go(Dest.Water(at.lat, at.lon)) }
                    OutLink("ui.charge", stringResource(R.string.mine_power_title), Modifier.weight(1f)) { nav.go(Dest.Power) }
                }
            }
            done -> app.atlas.ui.kit.StateMessage("ui.no-signal", stringResource(R.string.mine_weather_none), text = stringResource(R.string.mine_weather_none_text), tint = WSoft)
            else -> Text(stringResource(R.string.kit_loading), style = mt(13f, 500), color = WSoft)
        }
        Text(stringResource(R.string.mine_weather_footer), style = mt(10f, 400, line = 15f), color = Color(0xFF6A7A98), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Formats a clock time in the setting's way. */
internal object ZonedLocal {
    fun hm(i: Instant, zone: ZoneId, twelve: Boolean): String = i.atZone(zone).let { SettingsModel.clock(it.hour, it.minute, twelve) }
}

@Composable
private fun NowCard(f: Forecast, current: CurrentWeather, barometer: Barometer?, fromPhone: Boolean, hm: (Instant) -> String, phoneHm: (Instant) -> String, units: Units, fahrenheit: Boolean, now: Instant, onModels: () -> Unit) {
    val rainAt = f.nextRain(now)
    val words = buildString {
        append(stringResource(skyWord(current.sky.name)))
        if (f.rainEnds(now) != null) append(" · ").append(stringResource(R.string.mine_weather_raining))
        else if (rainAt != null && Duration.between(now, rainAt) < Duration.ofHours(12)) append(" · ").append(stringResource(R.string.mine_weather_rain_at, hm(rainAt)))
    }
    val nowHour = f.now
    val wind = nowHour?.takeIf { it.windSpeed.isFinite() }?.let { WeatherAdvice.wind(it.windSpeed, it.windDirection) }
    val uv = nowHour?.uvIndex?.takeIf { it.isFinite() && it >= 1 }?.let { "UV ${it.roundToInt()}" }
    val details = listOfNotNull(wind?.let { stringResource(R.string.mine_weather_wind, it) }, uv).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White).padding(start = 12.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BarometerDial(barometer, fromPhone, Modifier.size(160.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SettingsModel.temperature(current.temperature, fahrenheit), style = mt(52f, 900, tabular = true, line = 52f), color = WInk)
            Text(words, style = mt(14f, 700, line = 18f), color = WInk)
            if (details.isNotEmpty()) Text(details, style = mt(12f, 400, tabular = true, line = 16f), color = WSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (f.kept) Pill(stringResource(R.string.mine_weather_kept_chip, phoneHm(f.fetchedAt)), Color(0xFFFFF1D6), Color(0xFF8A5A00), {})
                Pill(stringResource(R.string.mine_weather_sources), Color(0xFFE1F2EA), Color(0xFF12663A), onModels)
            }
        }
    }
}

/** A card that leads from the weather to what it asks for outdoors: water to carry, power to find. */
@Composable
private fun OutLink(symbol: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = WInk, size = 22.dp)
        Text(text, Modifier.weight(1f), style = mt(14f, 800), color = WInk, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.height(28.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = mt(12f, 800), color = fg, maxLines = 1)
    }
}

private fun skyWord(name: String): Int = when (name) {
    "CLEAR" -> R.string.mine_sky_clear
    "MAINLY_CLEAR" -> R.string.mine_sky_mainly_clear
    "PARTLY_CLOUDY" -> R.string.mine_sky_partly
    "OVERCAST" -> R.string.mine_sky_cloudy
    "FOG" -> R.string.mine_sky_fog
    "DRIZZLE", "FREEZING_DRIZZLE" -> R.string.mine_sky_drizzle
    "RAIN", "FREEZING_RAIN", "SHOWERS" -> R.string.mine_sky_rain
    "SNOW", "SNOW_GRAINS", "SNOW_SHOWERS" -> R.string.mine_sky_snow
    "THUNDERSTORM", "THUNDERSTORM_HAIL" -> R.string.mine_sky_thunder
    else -> R.string.mine_sky_cloudy
}

/**
 * The brass aneroid of the board (160 units): a brass rim, a cream face with 25 ticks, STORMY RAIN CHANGE FAIR, the black
 * needle at the pressure and a dashed red hand where it stood three hours ago, and the reading below.
 */
@Composable
private fun BarometerDial(b: Barometer?, fromPhone: Boolean, modifier: Modifier) {
    val label = if (b == null) stringResource(R.string.mine_baro_none) else "${b.hPa.roundToInt()} hPa ${b.trend?.symbol ?: ""} · ${stringResource(if (fromPhone) R.string.mine_baro_phone else R.string.mine_baro_forecast)}".replace("  ", " ")
    val cd = stringResource(R.string.mine_baro_cd, label)
    val words = listOf(
        Triple(stringResource(R.string.mine_baro_stormy), Offset(41.4f, 93.4f), 255f), Triple(stringResource(R.string.mine_baro_rain), Offset(45.4f, 63.0f), 300f),
        Triple(stringResource(R.string.mine_baro_change), Offset(80f, 43f), 360f), Triple(stringResource(R.string.mine_baro_fair), Offset(114.6f, 63f), 420f),
    )
    Canvas(modifier.semantics { contentDescription = cd }) {
        val k = size.minDimension / 160f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        drawCircle(Color(0x26000000), 78f * k, p(80f, 82f))
        drawCircle(Brush.linearGradient(0f to Color(0xFFF6E2A8), 0.5f to Color(0xFFB98A36), 1f to Color(0xFFE9C977), start = p(2f, 2f), end = p(158f, 158f)), 78f * k, p(80f, 80f))
        drawCircle(Color(0xFFF8F0DC), 68f * k, p(80f, 80f))
        for (i in 0..24) {
            val deg = 150.0 + 10.0 * i
            val major = i % 3 == 0
            val a = Math.toRadians(deg)
            val outer = 62f
            val inner = if (major) 54f else 57.5f
            drawLine(Color(0xFF3A2A10), p(80f + outer * cos(a).toFloat(), 80f + outer * sin(a).toFloat()), p(80f + inner * cos(a).toFloat(), 80f + inner * sin(a).toFloat()), (if (major) 1.6f else 0.8f) * k)
        }
        for ((text, at, deg) in words) rotate(deg, p(at.x, at.y - 3f)) { label(text, p(at.x, at.y), 9f * k, Color(0xFF3A2A10), serif = true) }
        if (b != null) {
            fun tip(deg: Double, len: Float): Offset { val a = Math.toRadians(deg); return p(80f + len * sin(a).toFloat(), 80f - len * cos(a).toFloat()) }
            b.setHandDeg?.let { drawLine(Color(0xFFC8322A), p(80f, 80f), tip(it, 50f), 2f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * k, 2f * k))) }
            drawLine(Color(0xFF1B1208), p(80f, 80f), tip(b.needleDeg, 52f), 3.5f * k, StrokeCap.Round)
        }
        drawCircle(Brush.linearGradient(0f to Color(0xFFF6E2A8), 0.5f to Color(0xFFB98A36), 1f to Color(0xFFE9C977), start = p(74f, 74f), end = p(86f, 86f)), 6f * k, p(80f, 80f))
        drawCircle(Color(0xFF3A2A10), 6f * k, p(80f, 80f), style = Stroke(1f * k))
        label(label, p(80f, 118f), 9f * k, Color(0xFF6A5A40), bold = true)
    }
}

private fun DrawScope.label(text: String, at: Offset, size: Float, colour: Color, serif: Boolean = false, bold: Boolean = false) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = when { serif -> android.graphics.Typeface.SERIF; bold -> android.graphics.Typeface.DEFAULT_BOLD; else -> android.graphics.Typeface.DEFAULT }
    }
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, paint)
}

/** The next eight hours: the hour, the sky, the temperature and a bar for the rain, in a frosted card. */
@Composable
private fun HoursStrip(f: Forecast, now: Instant, zone: ZoneId, twelve: Boolean, fahrenheit: Boolean) {
    val hours = f.hours.filter { it.time > now.minus(Duration.ofMinutes(59)) }.take(8)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xB3FFFFFF)).padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
        for ((i, h) in hours.withIndex()) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = if (i == 0) stringResource(R.string.mine_weather_now) else h.time.atZone(zone).hour.let { if (twelve) (if (it % 12 == 0) 12 else it % 12).toString() else it.toString() }
            Text(label, style = mt(11f, 800), color = WSoft)
            Symbol(skySymbol(h.sky, h.isDay), tint = WInk, size = 22.dp)
            Text(if (h.temperature.isFinite()) SettingsModel.temperature(h.temperature, fahrenheit) else "–", style = mt(13f, 900, tabular = true), color = WInk)
            val mm = h.precipitation.takeIf { it.isFinite() } ?: 0.0
            Box(Modifier.size(10.dp, (2 + (mm * 12).coerceAtMost(24.0)).dp).clip(RoundedCornerShape(3.dp)).background(Rain))
        }
    }
}

/** One thing the weather means for you: a coloured tile, a line in bold and a line of why. */
@Composable
private fun AdviceRow(a: Advice, f: Forecast, hm: (Instant) -> String, units: Units, cafe: String?, onCafe: (() -> Unit)?) {
    val (symbol, colour) = when (a.kind) {
        Advice.Kind.RAIN_NOW, Advice.Kind.RAIN_SOON -> "weather.rain" to Color(0xFF5A7ACA)
        Advice.Kind.DRY_DAY -> "weather.sun" to Color(0xFF1E9E5A)
        Advice.Kind.WINDY -> "weather.wind" to Color(0xFF3FCF8E)
        Advice.Kind.FROST, Advice.Kind.SNOW -> "weather.snow" to Color(0xFF8A929C)
        Advice.Kind.HOT, Advice.Kind.UV -> "weather.sun" to Color(0xFFE8922A)
        Advice.Kind.FOG -> "weather.cloud" to Color(0xFF8A929C)
    }
    val title = when (a.kind) {
        Advice.Kind.RAIN_NOW -> stringResource(R.string.mine_adv_rain_now, a.until?.let { hm(it) } ?: "")
        Advice.Kind.RAIN_SOON -> if (a.until != null) stringResource(R.string.mine_adv_rain_soon_until, hm(a.at!!), hm(a.until)) else stringResource(R.string.mine_adv_rain_soon, hm(a.at!!))
        Advice.Kind.DRY_DAY -> stringResource(R.string.mine_adv_dry)
        Advice.Kind.WINDY -> stringResource(R.string.mine_adv_windy, SettingsModel.speed(a.value ?: 0.0, units))
        Advice.Kind.FROST -> stringResource(R.string.mine_adv_frost, hm(a.at!!))
        Advice.Kind.HOT -> stringResource(R.string.mine_adv_hot, a.value?.roundToInt() ?: 0)
        Advice.Kind.UV -> stringResource(R.string.mine_adv_uv, a.value?.roundToInt() ?: 0)
        Advice.Kind.FOG -> stringResource(R.string.mine_adv_fog)
        Advice.Kind.SNOW -> stringResource(R.string.mine_adv_snow)
    }
    val sub = when (a.kind) {
        Advice.Kind.RAIN_NOW, Advice.Kind.RAIN_SOON -> if (cafe != null) stringResource(R.string.mine_adv_rain_cafe, cafe) else stringResource(R.string.mine_adv_rain_plain)
        Advice.Kind.DRY_DAY -> stringResource(R.string.mine_adv_dry_sub)
        Advice.Kind.WINDY -> stringResource(R.string.mine_adv_windy_sub, hm(a.at!!))
        Advice.Kind.FROST -> stringResource(R.string.mine_adv_frost_sub)
        Advice.Kind.HOT -> stringResource(R.string.mine_adv_hot_sub)
        Advice.Kind.UV -> stringResource(R.string.mine_adv_uv_sub)
        Advice.Kind.FOG -> stringResource(R.string.mine_adv_fog_sub)
        Advice.Kind.SNOW -> stringResource(R.string.mine_adv_snow_sub)
    }
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).then(if (onCafe != null && (a.kind == Advice.Kind.RAIN_NOW || a.kind == Advice.Kind.RAIN_SOON)) Modifier.clickable(onClick = onCafe) else Modifier).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colour), contentAlignment = Alignment.Center) { Symbol(symbol, tint = Color.White, size = 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = mt(15f, 800), color = WInk, maxLines = 1)
            Text(sub, style = mt(12f, 400), color = WSoft, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (onCafe != null && (a.kind == Advice.Kind.RAIN_NOW || a.kind == Advice.Kind.RAIN_SOON)) Symbol("ui.chevron-right", tint = Color(0xFF9AA6BC), size = 16.dp)
    }
}

/** Sunrise, golden hour, sunset and the moon's lit share, from the sun and moon's own arithmetic for here. */
@Composable
private fun SunStrip(at: LatLon, zone: ZoneId, now: Instant, twelve: Boolean) {
    val date = now.atZone(zone).toLocalDate()
    val sun = remember(at, date) { app.atlas.geo.Sun.times(date, at, zone) }
    val moon = remember(now) { Moon.phase(now) }
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x9EFFFFFF)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        fun t(z: java.time.ZonedDateTime?) = z?.let { SettingsModel.clock(it.hour, it.minute, twelve) } ?: "–"
        SunItem("weather.sunrise", t(sun.sunrise), WInk)
        SunItem("weather.golden-hour", stringResource(R.string.mine_weather_golden, t(sun.goldenHourEveningStart)), Color(0xFFB8741A))
        SunItem("weather.sunset", t(sun.sunset), WInk)
        SunItem("ui.moon", "${(moon.illumination * 100).roundToInt()}%", Color(0xFF4B34B8))
    }
}

@Composable
private fun SunItem(symbol: String, text: String, colour: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol(symbol, tint = colour, size = 16.dp)
        Text(text, style = mt(12f, 700, tabular = true), color = colour, maxLines = 1)
    }
}
