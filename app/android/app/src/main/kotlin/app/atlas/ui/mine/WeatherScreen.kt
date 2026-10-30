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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.heightIn
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

/* The Weather board's inks (`canvas/project/Weather.dc.html`). */
private const val W_INK = 0xFF14203A
private const val W_SOFT = 0xFF5A6A88
private const val RAIN = 0xFF3E7BC4

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
    var askedOnline by remember(at) { mutableStateOf(false) }
    var live by remember { mutableStateOf<Double?>(null) }
    var cafe by remember(at) { mutableStateOf<app.atlas.model.Place?>(null) }
    LaunchedEffect(at) {
        askedOnline = !services.config.offline && services.connectivity.online.value
        forecast = withContext(Dispatchers.IO) {
            runCatching {
                if (!askedOnline) services.weather.kept(at) else services.weather.forecast(at)
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
        ink = Color(W_INK),
        titleSize = 34f,
        gap = 0.dp,
        trailing = {
            // the board's pill: 34 high, white, 12 px in, 12, 800 on 15.5
            TextPill(stringResource(R.string.mine_weather_models), bt(12f, 800, 15.5f), WHITE, W_INK, h = 34f, padH = 12f, onClick = { nav.go(Dest.Forecast) })
        },
    ) {
        when {
            f != null && current != null -> {
                NowCard(f, current, barometer, live != null || log.isNotEmpty(), ::hm, { ZonedLocal.hm(it, clock.zone, twelve) }, units, fahrenheit, now, onModels = { nav.go(Dest.Forecast) })
                HoursStrip(f, now, zone, twelve, fahrenheit, Modifier.padding(top = 14.dp))
                if (advice.isNotEmpty()) {
                    // "WHAT IT MEANS FOR YOU": 12, 800 on 15.5, 1.44 px apart, 18 px under the hours, the rows 6.5 px under it
                    BText(stringResource(R.string.mine_weather_means).uppercase(), bt(12f, 800, 15.5f, spacing = 1.44f), W_SOFT, Modifier.padding(start = 2.dp, top = 18.dp, bottom = 6.5.dp).semantics { heading() })
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (a in advice) AdviceRow(a, f, ::hm, units, cafe?.name(), onCafe = cafe?.let { c -> { nav.go(Dest.Place.of(c)) } })
                    }
                }
                SunStrip(at, zone, now, twelve, Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutLink("water.flowing", stringResource(R.string.mine_water_title), Modifier.weight(1f)) { nav.go(Dest.Water(at.lat, at.lon)) }
                    OutLink("ui.charge", stringResource(R.string.mine_power_title), Modifier.weight(1f)) { nav.go(Dest.Power) }
                }
            }
            done -> MineState("ui.no-signal", stringResource(R.string.mine_weather_none), text = stringResource(noForecastText(askedOnline, R.string.mine_weather_none_text)), tint = Color(W_SOFT))
            else -> BText(stringResource(R.string.kit_loading), bt(13f, 500, 17f), W_SOFT)
        }
        // the board's foot: 10 on 13, centred, 25 px under the sun's strip
        BText(stringResource(R.string.mine_weather_footer), bt(10f, 400, 13f, align = TextAlign.Center), 0xFF6A7A98, Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 25.dp), lines = 4)
    }
}

/**
 * The text of a screen's no-forecast state: [offline] (the screen's own "no connection" text) when it was not asked
 * online, and that the weather services did not answer when it was. Until 30 October a failure with a connection said
 * "No connection" too.
 */
internal fun noForecastText(askedOnline: Boolean, offline: Int): Int = if (askedOnline) R.string.mine_weather_unanswered_text else offline

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
    // the board's card: 190 high, radius 26, 12 px in on the left and 14 on the right, the dial (160, squeezed to 153.46 by
    // the row) and 10 px on the column: the temperature (52, 900 on 52), the sky (14, 700 on 18), the wind (12 on 15.5)
    // and the pills (28 high), 6 px apart
    Row(
        Modifier.fillMaxWidth().heightIn(min = 190.dp).bbox(WHITE, cr(26f)).padding(start = 12.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BarometerDial(barometer, fromPhone, Modifier.size(153.46.dp, 160.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BText(SettingsModel.temperature(current.temperature, fahrenheit), bt(52f, 900, 52f, tnum = true), W_INK)
            BText(words, bt(14f, 700, 18f), W_INK, lines = 2)
            if (details.isNotEmpty()) BText(details, bt(12f, 400, 15.5f, tnum = true), W_SOFT, lines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (f.kept) TextPill(stringResource(R.string.mine_weather_kept_chip, phoneHm(f.fetchedAt)), bt(12f, 800, 15.5f), 0xFFFFF1D6, 0xFF8A5A00)
                TextPill(stringResource(R.string.mine_weather_sources), bt(12f, 800, 15.5f), 0xFFE1F2EA, 0xFF12663A, onClick = onModels)
            }
        }
    }
}

/** A card that leads from the weather to what it asks for outdoors (water to carry, power to find), in the advice rows' white. */
@Composable
private fun OutLink(symbol: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).bbox(WHITE, cr(18f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        MSy(symbol, 22f, W_INK)
        BText(text, bt(14f, 800, 18f), W_INK, Modifier.weight(1f))
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
    val measurer = rememberTextMeasurer()
    val serif = bt(9f, 400, family = SERIF)
    val reading = bt(9f, 800)
    Canvas(modifier.semantics { contentDescription = cd }) {
        // the SVG's 160 square fitted into its box and centred (`xMidYMid meet`)
        val k = size.minDimension / 160f
        val ox = (size.width - 160f * k) / 2f
        val oy = (size.height - 160f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
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
        for ((text, at, deg) in words) rotate(deg, p(at.x, at.y - 3f)) { label(measurer, text, p(at.x, at.y), serif, k, Color(0xFF3A2A10)) }
        if (b != null) {
            fun tip(deg: Double, len: Float): Offset { val a = Math.toRadians(deg); return p(80f + len * sin(a).toFloat(), 80f - len * cos(a).toFloat()) }
            b.setHandDeg?.let { drawLine(Color(0xFFC8322A), p(80f, 80f), tip(it, 50f), 2f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * k, 2f * k))) }
            drawLine(Color(0xFF1B1208), p(80f, 80f), tip(b.needleDeg, 52f), 3.5f * k, StrokeCap.Round)
        }
        drawCircle(Brush.linearGradient(0f to Color(0xFFF6E2A8), 0.5f to Color(0xFFB98A36), 1f to Color(0xFFE9C977), start = p(74f, 74f), end = p(86f, 86f)), 6f * k, p(80f, 80f))
        drawCircle(Color(0xFF3A2A10), 6f * k, p(80f, 80f), style = Stroke(1f * k))
        label(measurer, label, p(80f, 118f), reading, k, Color(0xFF6A5A40))
    }
}

/** SVG text with `text-anchor="middle"`: [text] in [style] (its units scaled by [k] px) with its baseline centred at [at]. */
private fun DrawScope.label(measurer: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, style: androidx.compose.ui.text.TextStyle, k: Float, colour: Color) {
    val t = measurer.measure(text, style.copy(fontSize = style.fontSize * (k / density), lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified))
    drawText(t, colour, Offset(at.x - t.size.width / 2f, at.y - t.firstBaseline))
}

/** The sky as the Weather board's symbols draw it: the cloud, the rain, the moon by night, the sun, the snow. */
internal fun boardSky(sky: app.atlas.providers.Sky, day: Boolean): String = when (sky) {
    app.atlas.providers.Sky.CLEAR -> if (day) "weather.sun" else "ui.moon"
    app.atlas.providers.Sky.MAINLY_CLEAR, app.atlas.providers.Sky.PARTLY_CLOUDY -> if (day) "weather.partly-cloudy" else "ui.moon"
    app.atlas.providers.Sky.DRIZZLE, app.atlas.providers.Sky.FREEZING_DRIZZLE, app.atlas.providers.Sky.RAIN, app.atlas.providers.Sky.FREEZING_RAIN,
    app.atlas.providers.Sky.SHOWERS, app.atlas.providers.Sky.THUNDERSTORM, app.atlas.providers.Sky.THUNDERSTORM_HAIL -> "weather.rain.2"
    app.atlas.providers.Sky.SNOW, app.atlas.providers.Sky.SNOW_GRAINS, app.atlas.providers.Sky.SNOW_SHOWERS -> "weather.snow"
    else -> "weather.cloud"
}

/**
 * The next eight hours as the board's frosted card (104 high, radius 22, 70 % white): columns 46 px wide from 6 px in, each
 * the hour (11, 800 on 14), 4 px, the sky (22 px), 4 px, the temperature (13, 900 on 17), 4 px and a rain bar (10 wide).
 */
@Composable
private fun HoursStrip(f: Forecast, now: Instant, zone: ZoneId, twelve: Boolean, fahrenheit: Boolean, modifier: Modifier = Modifier) {
    val hours = f.hours.filter { it.time > now.minus(Duration.ofMinutes(59)) }.take(8)
    Row(modifier.fillMaxWidth().height(104.dp).bbox(0xB2FFFFFF, cr(22f)).padding(start = 6.dp, end = 6.dp, top = 12.dp)) {
        for ((i, h) in hours.withIndex()) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = if (i == 0) stringResource(R.string.mine_weather_now) else h.time.atZone(zone).hour.let { if (twelve) (if (it % 12 == 0) 12 else it % 12).toString() else it.toString() }
            BText(label, bt(11f, 800, 14f), W_SOFT)
            MSy(boardSky(h.sky, h.isDay), 22f, W_INK)
            BText(if (h.temperature.isFinite()) SettingsModel.temperature(h.temperature, fahrenheit) else "–", bt(13f, 900, 17f, tnum = true), W_INK)
            val mm = h.precipitation.takeIf { it.isFinite() } ?: 0.0
            Box(Modifier.size(10.dp, (2 + (mm * 12).coerceAtMost(22.0)).dp).bbox(RAIN, cr(3f)))
        }
    }
}

/** One thing the weather means for you: a coloured tile, a line in bold and a line of why. */
@Composable
private fun AdviceRow(a: Advice, f: Forecast, hm: (Instant) -> String, units: Units, cafe: String?, onCafe: (() -> Unit)?) {
    val (symbol, colour) = when (a.kind) {
        Advice.Kind.RAIN_NOW, Advice.Kind.RAIN_SOON -> "weather.rain.2" to 0xFF5A7ACA
        Advice.Kind.DRY_DAY -> "park.bike" to 0xFF1E9E5A
        Advice.Kind.WINDY -> "weather.wind" to 0xFF3FCF8E
        Advice.Kind.FROST, Advice.Kind.SNOW -> "weather.snow" to 0xFF8A96A8
        Advice.Kind.HOT, Advice.Kind.UV -> "weather.sun" to 0xFFE8922A
        Advice.Kind.FOG -> "weather.cloud" to 0xFF8A96A8
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
    // the board's row: 62 high, radius 18, 12 px in; the 40 px tile (radius 12, a 22 px symbol), 12 px on the title (15, 800
    // on 19.5) and 2 px under it the why (12 on 15.5), and the chevron 12 px after
    val open = onCafe != null && (a.kind == Advice.Kind.RAIN_NOW || a.kind == Advice.Kind.RAIN_SOON)
    Row(
        Modifier.fillMaxWidth().height(62.dp).bbox(WHITE, cr(18f)).then(if (open) Modifier.clickable(role = Role.Button, onClick = onCafe!!) else Modifier).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).bbox(colour, cr(12f)), contentAlignment = Alignment.Center) { MSy(symbol, 22f, WHITE) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(title, bt(15f, 800, 19.5f), W_INK)
            BText(sub, bt(12f, 400, 15.5f), W_SOFT)
        }
        if (open) MSy("ui.chevron-right", 16f, 0xFF9AA6BC)
    }
}

/** Sunrise, golden hour, sunset and the moon's lit share, from the sun and moon's own arithmetic for here. */
@Composable
private fun SunStrip(at: LatLon, zone: ZoneId, now: Instant, twelve: Boolean, modifier: Modifier = Modifier) {
    val date = now.atZone(zone).toLocalDate()
    val sun = remember(at, date) { app.atlas.geo.Sun.times(date, at, zone) }
    val moon = remember(now) { Moon.phase(now) }
    // the board's strip: 40 high, radius 14, 62 % white, 14 px in, four times spread: a 16 px symbol, 5 px, 12, 700 on 15.5
    Row(
        modifier.fillMaxWidth().height(40.dp).bbox(0x9EFFFFFF, cr(14f)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        fun t(z: java.time.ZonedDateTime?) = z?.let { SettingsModel.clock(it.hour, it.minute, twelve) } ?: "–"
        SunItem("weather.sunrise.2", t(sun.sunrise), W_INK)
        SunItem("weather.golden-hour", stringResource(R.string.mine_weather_golden, t(sun.goldenHourEveningStart)), 0xFFB8741A)
        SunItem("weather.sunset.3", t(sun.sunset), W_INK)
        SunItem("ui.moon", "${(moon.illumination * 100).roundToInt()}%", 0xFF4B34B8)
    }
}

@Composable
private fun SunItem(symbol: String, text: String, colour: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        MSy(symbol, 16f, colour)
        BText(text, bt(12f, 700, 15.5f, tnum = true), colour)
    }
}
