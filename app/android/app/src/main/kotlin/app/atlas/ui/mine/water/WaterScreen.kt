package app.atlas.ui.mine.water

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.Units
import app.atlas.ui.go.GoFormat
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.SectionLabel
import app.atlas.ui.mine.mt
import app.atlas.ui.mine.serifTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val WInk = Color(0xFF16261E)
private val WSoft = Color(0xFF5A6E60)

/**
 * "Water out there": the springs, taps, wells and drinking fountains of OpenStreetMap along your trip (or around you), with
 * what their tags say (drinking water, seasonal), and the bottle you carry, with how long and how far it lasts at today's
 * temperature and whether it reaches the next usable source. The bottle's size and what is left are yours to set.
 */
@Composable
fun WaterScreen(args: Dest.Water, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val here = rememberHere()
    val centre = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    val bottleMl by services.settings.flow(MineKeys.WATER_BOTTLE_ML, 1000).collectAsStateWithLifecycle(1000)
    val leftMl by services.settings.flow(MineKeys.WATER_LEFT_ML, 400).collectAsStateWithLifecycle(400)
    val session by services.go.nav.collectAsStateWithLifecycle()
    val navView by remember(session) { session?.view ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsStateWithLifecycle(null)
    val route = navView?.route
    var sources by remember(centre, route) { mutableStateOf<List<WaterSource>?>(null) }
    var tempC by remember(centre) { mutableStateOf<Double?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val clock = appClock()
    LaunchedEffect(centre, route) {
        withContext(Dispatchers.IO) {
            sources = runCatching { WaterModel.sources(services.placeStores, centre, route = route) }.getOrDefault(emptyList())
            tempC = runCatching { services.weather.kept(centre)?.let { f -> f.now?.temperature?.takeIf { it.isFinite() } ?: f.hourAt(clock.instant())?.temperature } }.getOrNull()
        }
    }
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val temp = tempC ?: 15.0
    val supply = WaterModel.supply(leftMl / 1000.0, temp)
    // On a trip the marks count from where you are now, not from where the route began; what is behind you is left out.
    val travelled = navView?.travelled ?: 0.0
    val list = remember(sources, route, travelled) { WaterModel.ahead(sources.orEmpty(), route != null, travelled) }
    val next = list.firstOrNull { WaterModel.usable(it) }
    val verdict = WaterModel.verdict(supply, next)
    BoardPage(
        title = stringResource(R.string.mine_water_title), onBack = { nav.back() }, background = Color(0xFFEAF1EC), ink = WInk, titleSize = 32f,
        bottomPadding = 28.dp,
    ) {
        Hero(WaterModel.fill(leftMl / 1000.0, bottleMl / 1000.0), leftMl / 1000.0, supply, next, verdict, tempC, units, onClick = { dialog = true })
        SectionLabel(stringResource(if (route != null) R.string.mine_water_on_trail else R.string.mine_water_around), Modifier.padding(start = 2.dp), Color(0xFF5A6E60))
        when {
            sources == null -> Text(stringResource(R.string.kit_loading), style = mt(13f, 500), color = WSoft)
            list.isEmpty() -> StateMessage("water.flowing", stringResource(R.string.mine_water_none), text = stringResource(R.string.mine_water_none_text), tint = WSoft)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in list.take(6)) SourceRow(s, units, onClick = { services.remember(s.place); nav.go(Dest.Place.of(s.place)) })
            }
        }
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFF1D6)).clickable { next?.let { n -> services.remember(n.place); nav.go(Dest.Place.of(n.place)) } }.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("water.flowing", tint = Color(0xFF8A5A00), size = 26.dp)
            Text(stringResource(R.string.mine_water_report), Modifier.weight(1f), style = mt(13f, 700, line = 17f), color = Color(0xFF6A4A00))
        }
        Row(Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(WInk).clickable { nav.go(Dest.Needs("water")) }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Symbol("ui.map", tint = Color.White, size = 20.dp)
            Text(stringResource(R.string.mine_water_on_map), style = mt(15f, 800), color = Color.White)
        }
        Text(stringResource(R.string.mine_water_footer), style = mt(10f, 400, line = 15f), color = Color(0xFF7A8E80), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (dialog) BottleDialog(bottleMl, leftMl, onClose = { dialog = false }, onSave = { b, l -> scope.launch { services.settings.set(MineKeys.WATER_BOTTLE_ML, b); services.settings.set(MineKeys.WATER_LEFT_ML, l); services.settings.set(MineKeys.WATER_STAMP, clock.millis()) } })
}

/** "55 min" or "2 h", in the person's language. */
@Composable
private fun minutesText(minutes: Double): String =
    if (minutes < 90) stringResource(R.string.mine_min_n, maxOf(1, minutes.roundToInt())) else stringResource(R.string.mine_h_n, (minutes / 60).roundToInt())

/** The green card: the bottle with its level, "enough until km 3.2", how long that is at this heat, and the verdict. */
@Composable
private fun Hero(fill: Float, litres: Double, supply: Supply, next: WaterSource?, verdict: WaterModel.Verdict, tempC: Double?, units: Units, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val range = GoFormat.distance(supply.rangeKm * 1000.0, units)
    val cd = stringResource(R.string.mine_water_bottle_cd, String.format(Locale.ROOT, "%.1f", litres))
    Row(
        Modifier.fillMaxWidth().height(212.dp).clip(shape).background(Brush.linearGradient(listOf(Color(0xFF2F6B3A), Color(0xFF1E4A2A)))).clickable(onClick = onClick).padding(start = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Bottle(fill, litres, Modifier.size(120.dp, 200.dp).semantics { contentDescription = cd })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mine_water_enough).uppercase(), style = mt(12f, 800, spacing = 1.4f), color = Color(0xFFA8C8A8))
            Text(stringResource(R.string.mine_water_km, range.unit, range.value), style = mt(30f, 900, tabular = true, line = 30f), color = Color(0xFFEEF4EC))
            val temp = tempC?.let { "${it.roundToInt()}°" }
            Text(
                listOfNotNull(
                    stringResource(R.string.mine_water_lasts, minutesText(supply.minutes), temp ?: stringResource(R.string.mine_water_mild)),
                    next?.let { stringResource(R.string.mine_water_next, next.name.ifBlank { stringResource(R.string.mine_water_source) }, minutesText(WaterModel.walkMinutes((next.alongM ?: next.distanceM)))) },
                ).joinToString(" · "), style = mt(13f, 400, tabular = true, line = 17f), color = Color(0xFFC8DCC8),
            )
            val (text, bg, fg) = when (verdict) {
                WaterModel.Verdict.FINE -> Triple(R.string.mine_water_fine, Color(0xFF7CFF6B), WInk)
                WaterModel.Verdict.TIGHT -> Triple(R.string.mine_water_tight, Color(0xFFFFD27A), WInk)
                WaterModel.Verdict.LOW -> Triple(R.string.mine_water_low, Color(0xFFFF8A6B), WInk)
                WaterModel.Verdict.NO_SOURCE -> Triple(R.string.mine_water_nosource, Color(0xFFC8DCC8), WInk)
            }
            Box(Modifier.height(26.dp).clip(CircleShape).background(bg).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text(stringResource(text), style = mt(12f, 900), color = fg) }
        }
    }
}

/** The bottle of the board (120 × 200): aluminium with a green cap, water to its level and a label with the litres. */
@Composable
private fun Bottle(fill: Float, litres: Double, modifier: Modifier) {
    Canvas(modifier) {
        val k = size.width / 120f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        val body = Path().apply {
            moveTo(40f * k, 30f * k); lineTo(80f * k, 30f * k); lineTo(80f * k, 44f * k)
            cubicTo(94f * k, 52f * k, 102f * k, 64f * k, 102f * k, 80f * k); lineTo(102f * k, 180f * k)
            cubicTo(102f * k, 188f * k, 96f * k, 194f * k, 88f * k, 194f * k); lineTo(32f * k, 194f * k)
            cubicTo(24f * k, 194f * k, 18f * k, 188f * k, 18f * k, 180f * k); lineTo(18f * k, 80f * k)
            cubicTo(18f * k, 64f * k, 26f * k, 52f * k, 40f * k, 44f * k); close()
        }
        drawRoundRect(Color(0xFF2F6B3A), p(44f, 6f), androidx.compose.ui.geometry.Size(32f * k, 26f * k), androidx.compose.ui.geometry.CornerRadius(5f * k))
        drawPath(body, Brush.horizontalGradient(0f to Color(0xFF8A929C), 0.35f to Color(0xFFEEF1F4), 1f to Color(0xFF7A828C), startX = 18f * k, endX = 102f * k))
        drawPath(body, Color(0xFF5A626C), style = Stroke(2f * k))
        clipPath(body) {
            val y = (194f - fill * 150f) * k
            drawRect(Color(0xFF2E9CDB).copy(alpha = 0.85f), Offset(0f, y), androidx.compose.ui.geometry.Size(120f * k, 80f * k))
            val wave = Path().apply { moveTo(0f, y); cubicTo(20f * k, y - 6f * k, 40f * k, y + 6f * k, 60f * k, y); cubicTo(80f * k, y - 6f * k, 100f * k, y + 6f * k, 120f * k, y) }
            drawPath(wave, Color(0xFF9FDCFF), style = Stroke(3f * k))
        }
        drawRect(Color(0xFF6E7F4A).copy(alpha = 0.9f), p(18f, 96f), androidx.compose.ui.geometry.Size(84f * k, 30f * k))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * k; color = Color.White.toArgb(); typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER }
        drawContext.canvas.nativeCanvas.drawText(String.format(Locale.ROOT, "%.1f L", litres), 60f * k, 117f * k, paint)
    }
}

@Composable
private fun SourceRow(s: WaterSource, units: Units, onClick: () -> Unit) {
    val away = GoFormat.distance(s.alongM ?: s.distanceM, units)
    val (chip, chipColour) = when (s.status) {
        WaterSource.Status.POTABLE -> stringResource(R.string.mine_water_potable) to Color(0xFF2E7BC4)
        WaterSource.Status.SEASONAL -> stringResource(R.string.mine_water_seasonal) to Color(0xFF8A929C)
        WaterSource.Status.NOT_DRINKING -> stringResource(R.string.mine_water_not_drinking) to Color(0xFFC8322A)
        WaterSource.Status.UNSAID -> stringResource(R.string.mine_water_unsaid) to Color(0xFF1E9E5A)
    }
    val sub = when (s.status) {
        WaterSource.Status.POTABLE -> stringResource(R.string.mine_water_sub_potable)
        WaterSource.Status.SEASONAL -> stringResource(R.string.mine_water_sub_seasonal)
        WaterSource.Status.NOT_DRINKING -> stringResource(R.string.mine_water_sub_not)
        WaterSource.Status.UNSAID -> stringResource(R.string.mine_water_sub_unsaid)
    }
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(away.unit, style = mt(10f, 800), color = Color(0xFF8A9A8E))
            Text(away.value, style = mt(18f, 900, tabular = true), color = WInk)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(s.kind.colour)), contentAlignment = Alignment.Center) { Symbol(s.kind.symbol, tint = Color.White, size = 24.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.name.ifBlank { stringResource(kindName(s.kind)) }, style = mt(15f, 800), color = WInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = mt(12f, 400, line = 15f), color = if (s.status == WaterSource.Status.SEASONAL) Color(0xFF8A5A00) else WSoft, maxLines = 2)
        }
        Box(Modifier.height(24.dp).clip(CircleShape).background(chipColour).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { Text(chip, style = mt(11f, 900), color = Color.White, maxLines = 1) }
    }
}

private fun kindName(k: WaterSource.Kind): Int = when (k) {
    WaterSource.Kind.SPRING -> R.string.mine_water_k_spring; WaterSource.Kind.TAP -> R.string.mine_water_k_tap; WaterSource.Kind.WELL -> R.string.mine_water_k_well
    WaterSource.Kind.POINT -> R.string.mine_water_k_point; WaterSource.Kind.FOUNTAIN -> R.string.mine_water_k_fountain
}

/** The bottle's size in litres and what is left in it, kept on the phone. */
@Composable
private fun BottleDialog(bottleMl: Int, leftMl: Int, onClose: () -> Unit, onSave: (Int, Int) -> Unit) {
    var size by remember { mutableFloatStateOf(bottleMl / 1000f) }
    var left by remember { mutableFloatStateOf(leftMl / 1000f) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.mine_water_bottle_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mine_water_bottle_size, String.format(Locale.ROOT, "%.1f", size)), style = mt(13f, 700))
                Slider(size, { size = it; left = left.coerceAtMost(it) }, valueRange = 0.5f..3f)
                Text(stringResource(R.string.mine_water_bottle_left, String.format(Locale.ROOT, "%.2f", left)), style = mt(13f, 700))
                Slider(left, { left = it.coerceAtMost(size) }, valueRange = 0f..size.coerceAtLeast(0.5f))
            }
        },
        confirmButton = { TextButton({ onSave((size * 1000).roundToInt(), (left * 1000).roundToInt()); onClose() }) { Text(stringResource(R.string.mine_data_save)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.mine_offline_keep)) } },
    )
}
