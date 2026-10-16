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

private val FInk = Color(0xFF14203A)
private val FSoft = Color(0xFF5A6A88)

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
    val fetcher = remember { ModelsFetcher(services.http, File(services.dirs.cache, "providers"), { services.clock.instant() }) }
    LaunchedEffect(here) {
        models = if (services.config.offline || !services.connectivity.online.value) fetcher.kept(here) else fetcher.get(here)
        done = true
    }
    val m = models
    val zone = appClock().zone
    BoardPage(
        title = stringResource(R.string.mine_forecast_title),
        onBack = { nav.back() },
        background = Color(0xFFE3EAF3),
        ink = FInk,
        titleSize = 30f,
        trailing = {
            if (m != null) Box(Modifier.height(34.dp).clip(CircleShape).background(Color(0xFFE1F2EA)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(pluralStringResource(R.plurals.mine_n_models, m.count, m.count), style = mt(12f, 800), color = Color(0xFF12663A))
            }
        },
    ) {
        if (m != null) {
            ChartCard(m, zone)
            Sources(m)
        } else if (done) {
            app.atlas.ui.kit.StateMessage("ui.no-signal", stringResource(R.string.mine_forecast_none), text = stringResource(R.string.mine_forecast_none_text), tint = FSoft)
        } else Text(stringResource(R.string.kit_loading), style = mt(13f, 500), color = FSoft)
        Text(stringResource(R.string.mine_forecast_footer), style = mt(10f, 400, line = 15f), color = Color(0xFF6A7A98), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ChartCard(m: ModelsForecast, zone: java.time.ZoneId) {
    val frost = m.frost()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.mine_forecast_temp).uppercase(), style = mt(11f, 800, spacing = 1.3f), color = FSoft, modifier = Modifier.weight(1f))
            if (frost != null) Text(
                stringResource(R.string.mine_forecast_frost, Instant.ofEpochSecond(m.hours[frost.first]).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), m.frostModels(), m.count),
                style = mt(11f, 800), color = Color(0xFFC8322A),
            )
        }
        TemperatureChart(m, Modifier.fillMaxWidth().height(162.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (c in m.curves) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(c.model.colour)))
                Text(c.model.label, style = mt(11f, 800), color = FInk)
            }
        }
        Text(stringResource(R.string.mine_forecast_rain).uppercase(), style = mt(11f, 800, spacing = 1.3f), color = FSoft)
        RainBars(m, Modifier.fillMaxWidth().height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val first = Instant.ofEpochSecond(m.hours.first())
            for (i in 0..4) {
                val t = first.plusSeconds(i * 12L * 3600)
                val z = t.atZone(zone)
                Text(if (i == 0) stringResource(R.string.mine_weather_now) else "${z.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${String.format(Locale.ROOT, "%02d:00", z.hour)}", style = mt(10f, 700, tabular = true), color = Color(0xFF8A96A8))
            }
        }
    }
}

/** The chart: the band between the lowest and highest model, a line per model, the freezing line, and the coldest hour. */
@Composable
private fun TemperatureChart(m: ModelsForecast, modifier: Modifier) {
    val cd = stringResource(R.string.mine_forecast_chart_cd, m.count)
    val frost = m.frost()
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
            text("0°", Offset(size.width, zy - 3.dp.toPx()), 9.dp.toPx(), Color(0xFFC8322A))
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
        if (frost != null) drawLine(Color(0x80141E3A), Offset(x(frost.first), 0f), Offset(x(frost.first), size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
    }
}

private fun DrawScope.text(s: String, at: Offset, size: Float, colour: Color) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; color = colour.toArgb(); textAlign = android.graphics.Paint.Align.RIGHT; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    drawContext.canvas.nativeCanvas.drawText(s, at.x, at.y, paint)
}

/** One bar an hour: tall and strong when every model expects rain, low and pale when only some do, a sliver when none. */
@Composable
private fun RainBars(m: ModelsForecast, modifier: Modifier) {
    val counts = remember(m) { m.rainAgreement() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
        for (n in counts) {
            val share = n.toFloat() / m.count.coerceAtLeast(1)
            Box(
                Modifier.weight(1f).height((if (n == 0) 4f else 8f + 21f * share).dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (n == 0) Color(0xFFEEF2F7) else if (share >= 0.99f) Color(0xFF2E7BFF) else Color(0xFF2E7BFF).copy(alpha = 0.35f + 0.5f * share)),
            )
        }
    }
}

/** "Who makes it": each model that answered, Open-Meteo that serves them, and the phone's barometer that needs no one. */
@Composable
private fun Sources(m: ModelsForecast) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)) {
        SectionLabel(stringResource(R.string.mine_forecast_who), Modifier.padding(top = 4.dp, bottom = 8.dp), FSoft)
        for ((i, c) in m.curves.withIndex()) SourceRow(c.model.label, c.model.maker, stringResource(R.string.mine_forecast_open), Color(c.model.colour), "weather.cloud", i == 0)
        SourceRow("Open-Meteo", stringResource(R.string.mine_forecast_openmeteo), stringResource(R.string.mine_forecast_cc), Color(0xFF0A6E7E), "weather.sun", false)
        SourceRow(stringResource(R.string.mine_forecast_baro), stringResource(R.string.mine_forecast_baro_sub), stringResource(R.string.mine_forecast_offline), Color(0xFF6A5A3A), "ui.gauge", false)
    }
}

@Composable
private fun SourceRow(name: String, sub: String, tag: String, colour: Color, symbol: String, first: Boolean) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEF2F7)))
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colour), contentAlignment = Alignment.Center) { Symbol(symbol, tint = Color.White, size = 18.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, style = mt(14f, 800), color = FInk)
                Text(sub, style = mt(11f, 400), color = FSoft, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            TagPill(tag, Color(0xFFE1F2EA), Color(0xFF12663A), height = 22.dp)
        }
    }
}
