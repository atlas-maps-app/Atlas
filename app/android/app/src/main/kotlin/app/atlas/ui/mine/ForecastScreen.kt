package app.atlas.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.atlas.ui.kit.CssCorners
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/* The Forecast board's inks (`canvas/project/Forecast.dc.html`). */
private const val F_INK = 0xFF14203A
private const val F_SOFT = 0xFF5A6A88

/**
 * The Forecast sources board: the next 48 hours' temperature from several weather models at once, drawn together so you
 * can see how much they agree; how many of them expect rain each hour; and who makes the numbers. It is asked from
 * Open-Meteo, which serves the national models through one open API; with no signal the last answer kept is shown.
 */
@Composable
fun ForecastScreen(args: Dest.Forecast, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    var models by remember(here) { mutableStateOf<ModelsForecast?>(null) }
    var done by remember(here) { mutableStateOf(false) }
    var askedOnline by remember(here) { mutableStateOf(false) }
    val fetcher = remember { ModelsFetcher(services.http, services.dirs.providers, { services.clock.instant() }) }
    LaunchedEffect(here) {
        askedOnline = !services.config.offline && services.connectivity.online.value
        models = if (!askedOnline) fetcher.kept(here) else fetcher.get(here)
        done = true
    }
    val m = models
    val zone = appClock().zone
    BoardPage(
        title = stringResource(R.string.mine_forecast_title),
        onBack = { nav.back() },
        background = Color(0xFFE3EAF3),
        ink = Color(F_INK),
        titleSize = 30f,
        top = 10.dp,
        gap = 11.dp,
        trailing = {
            if (m != null) TextPill(pluralStringResource(R.plurals.mine_n_models, m.count, m.count), bt(12f, 800, 15.5f), 0xFFE1F2EA, 0xFF12663A, h = 34f, padH = 12f)
        },
    ) {
        if (m != null) {
            ChartCard(m, zone)
            Sources(m)
        } else if (done) {
            MineState("ui.no-signal", stringResource(R.string.mine_forecast_none), text = stringResource(noForecastText(askedOnline, R.string.mine_forecast_none_text)), tint = Color(F_SOFT))
        } else BText(stringResource(R.string.kit_loading), bt(13f, 500, 17f), F_SOFT)
        // the board's foot: 10 on 15, centred, 36 px under the last card
        BText(stringResource(R.string.mine_forecast_footer), bt(10f, 400, 15f, align = TextAlign.Center), 0xFF6A7A98, Modifier.fillMaxWidth().padding(start = 1.dp, end = 1.dp, top = 25.dp), lines = 4)
    }
}

/**
 * The chart card as the board's (radius 24, 12 px above, 16 at the sides, 10 below, 8 px between): the label and the
 * frost note, the 162 px chart, the legend, the rain label, the 32 px of rain bars and the times under them.
 */
@Composable
private fun ChartCard(m: ModelsForecast, zone: java.time.ZoneId) {
    val frost = m.frost()
    Column(Modifier.fillMaxWidth().bbox(WHITE, cr(24f)).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BText(stringResource(R.string.mine_forecast_temp).uppercase(), bt(11f, 800, 14f, spacing = 1.32f), F_SOFT, Modifier.weight(1f).alignByBaseline())
            if (frost != null) BText(
                stringResource(R.string.mine_forecast_frost, Instant.ofEpochSecond(m.hours[frost.first]).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), m.frostModels(), m.count),
                bt(11f, 800, 14f), 0xFFC8322A, Modifier.alignByBaseline(),
            )
        }
        TemperatureChart(m, Modifier.fillMaxWidth().height(162.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (c in m.curves) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp, 3.dp).bbox(c.model.colour, cr(2f)))
                BText(c.model.label, bt(11f, 800, 14f), F_INK)
            }
        }
        BText(stringResource(R.string.mine_forecast_rain).uppercase(), bt(11f, 800, 14f, spacing = 1.32f), F_SOFT)
        RainBars(m, Modifier.fillMaxWidth().height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val first = Instant.ofEpochSecond(m.hours.first())
            for (i in 0..4) {
                val t = first.plusSeconds(i * 12L * 3600)
                val z = t.atZone(zone)
                BText(if (i == 0) stringResource(R.string.mine_weather_now) else "${z.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${String.format(Locale.ROOT, "%02d:00", z.hour)}", bt(10f, 700, 13f, tnum = true), 0xFF8A96A8)
            }
        }
    }
}

/** The chart: the band between the lowest and highest model, a line per model, the freezing line, and the coldest hour. */
@Composable
private fun TemperatureChart(m: ModelsForecast, modifier: Modifier) {
    val cd = stringResource(R.string.mine_forecast_chart_cd, m.count)
    val frost = m.frost()
    val measurer = rememberTextMeasurer()
    val zeroStyle = bt(9f, 800)
    Canvas(modifier.semantics { contentDescription = cd }) {
        val n = m.hours.size
        val (lo, hi) = m.range ?: return@Canvas
        // Zero is on the chart only when some model comes near it: a mild week fills the chart with its own range.
        val top = if (lo < 5.0) minOf(lo - 1.0, -0.5) else lo - 1.0
        val bottom = hi + 1.0
        val h = size.height - 12.dp.toPx()
        fun y(t: Double) = 6.dp.toPx() + ((bottom - t) / (bottom - top)).toFloat() * h
        fun x(i: Int) = size.width * i / (n - 1).coerceAtLeast(1)
        // Freezing.
        if (top < 0 && bottom > 0) {
            val zy = y(0.0)
            drawLine(Color(0xFFC8322A), Offset(0f, zy), Offset(size.width, zy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            // the board's "0°": Geist 9, 800, its end at the right, its baseline 3 px over the line
            val t = measurer.measure("0°", zeroStyle)
            drawText(t, Color(0xFFC8322A), Offset(size.width - t.size.width, zy - 3.dp.toPx() - t.firstBaseline))
        }
        // The band of all models.
        val band = Path()
        var started = false
        val his = ArrayList<Pair<Int, Double>>()
        val los = ArrayList<Pair<Int, Double>>()
        for (i in 0 until n) {
            val v = m.curves.mapNotNull { it.temperature.getOrNull(i)?.takeIf { t -> t.isFinite() } }
            if (v.isEmpty()) continue
            his += i to v.max(); los += i to v.min()
        }
        for ((i, t) in his) { if (!started) { band.moveTo(x(i), y(t)); started = true } else band.lineTo(x(i), y(t)) }
        for ((i, t) in los.reversed()) band.lineTo(x(i), y(t))
        band.close()
        drawPath(band, Color(0x388FB4FF))
        for (c in m.curves) {
            val p = Path()
            var first = true
            for (i in 0 until n) {
                val t = c.temperature[i]
                if (!t.isFinite()) { first = true; continue }
                if (first) { p.moveTo(x(i), y(t)); first = false } else p.lineTo(x(i), y(t))
            }
            drawPath(p, Color(c.model.colour).copy(alpha = 0.9f), style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
        }
        // the coldest hour: the board's dashed ink at half, over the data's 150 px (from y 6, the viewBox's 0)
        if (frost != null) drawLine(Color(0x8014203A), Offset(x(frost.first), 6.dp.toPx()), Offset(x(frost.first), 156.dp.toPx()), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
    }
}


/**
 * One bar an hour, as the board's (1 px apart, the top corners round by 2): 4 px and pale when no model expects rain, then 5 px
 * more for each that does, pale grey for one in five, light blue for two or three, the full blue for four and more.
 */
@Composable
private fun RainBars(m: ModelsForecast, modifier: Modifier) {
    val counts = remember(m) { m.rainAgreement() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
        for (n in counts) {
            val share = n.toFloat() / m.count.coerceAtLeast(1)
            val colour = when {
                n == 0 -> 0xFFEEF2F7
                share >= 0.79f -> 0xFF2E7BFF
                share >= 0.39f -> 0xFF8FB4FF
                else -> 0xFFD5DEEC
            }
            Box(Modifier.weight(1f).height((4f + 25f * share).dp).bbox(colour, CssCorners(2f, 2f, 0f, 0f)))
        }
    }
}

/** "Who makes it", as the board's card (radius 24, 14 px in): each model that answered, Open-Meteo that serves them, and the phone's barometer. */
@Composable
private fun Sources(m: ModelsForecast) {
    Column(Modifier.fillMaxWidth().bbox(WHITE, cr(24f)).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
        CardLabel(stringResource(R.string.mine_forecast_who), Modifier.padding(bottom = 8.dp), F_SOFT)
        for (c in m.curves) SourceRow(c.model.label, c.model.maker, stringResource(R.string.mine_forecast_open), c.model.colour, "weather.cloud")
        SourceRow("Open-Meteo", stringResource(R.string.mine_forecast_openmeteo), stringResource(R.string.mine_forecast_cc), 0xFF0A6E7E, "ui.source")
        SourceRow(stringResource(R.string.mine_forecast_baro), stringResource(R.string.mine_forecast_baro_sub), stringResource(R.string.mine_forecast_offline), 0xFF6B5A40, "ui.gauge")
    }
}

/** A row of the board: the hairline, 9 px, the 36 px tile (radius 10), 12 px on the name (14, 800 on 18) and its line (11 on 14), the green tag. */
@Composable
private fun SourceRow(name: String, sub: String, tag: String, colour: Long, symbol: String) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop(0xFFEEF2F7)).padding(top = 10.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).bbox(colour, cr(10f)), contentAlignment = Alignment.Center) { MSy(symbol, 18f, WHITE) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BText(name, bt(14f, 800, 18f), F_INK)
            BText(sub, bt(11f, 400, 14f), F_SOFT)
        }
        TextPill(tag, bt(10f, 900, 13f), 0xFFE1F2EA, 0xFF12663A, h = 22f, padH = 8f)
    }
}
