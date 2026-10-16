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
import app.atlas.ui.mine.MineState
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.MSy
import app.atlas.ui.mine.TextPill
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
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

/* The Sources board's inks (`canvas/project/Sources.dc.html`). */
private const val W_INK = 0xFF16261E
private const val W_SOFT = 0xFF5A6E60

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
        title = stringResource(R.string.mine_water_title), onBack = { nav.back() }, background = Color(0xFFEAF1EC), ink = Color(W_INK), titleSize = 32f,
        gap = 0.dp, bottomPadding = 22.dp,
    ) {
        Hero(WaterModel.fill(leftMl / 1000.0, bottleMl / 1000.0), leftMl / 1000.0, supply, next, verdict, tempC, units, onClick = { dialog = true })
        // "ON YOUR TRAIL": 12, 800, 1.44 px apart, 20 px under the bottle, the rows 6.5 px under it
        BText(stringResource(if (route != null) R.string.mine_water_on_trail else R.string.mine_water_around).uppercase(), bt(12f, 800, 15.5f, spacing = 1.44f), W_SOFT, Modifier.padding(start = 2.dp, top = 20.dp, bottom = 6.5.dp).semantics { heading() })
        when {
            sources == null -> BText(stringResource(R.string.kit_loading), bt(13f, 500, 17f), W_SOFT)
            list.isEmpty() -> MineState("water.flowing", stringResource(R.string.mine_water_none), text = stringResource(R.string.mine_water_none_text), tint = Color(W_SOFT))
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in list.take(6)) SourceRow(s, units, onClick = { services.remember(s.place); nav.go(Dest.Place.of(s.place)) })
            }
        }
        // the report card: 64 high, radius 20, amber, 14 px in, the 26 px symbol and 12 px on the line (13, 700)
        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().height(64.dp).bbox(0xFFFFF1D6, cr(20f)).clickable(role = Role.Button) { next?.let { n -> services.remember(n.place); nav.go(Dest.Place.of(n.place)) } }.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("water.flowing", 26f, 0xFF8A5A00)
            BText(stringResource(R.string.mine_water_report), bt(13f, 700, 17f), 0xFF6A4A00, Modifier.weight(1f), lines = 2)
        }
        // the dark button: 56 high, radius 28, the 20 px symbol and the label (15, 800), 8 px apart
        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().height(56.dp).bbox(W_INK, cr(28f)).clickable(role = Role.Button) { nav.go(Dest.Needs("water")) },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("ui.map", 20f, WHITE)
            BText(stringResource(R.string.mine_water_on_map), bt(15f, 800, 19.5f), WHITE)
        }
        BText(stringResource(R.string.mine_water_footer), bt(10f, 400, 13f, align = TextAlign.Center), 0xFF7A8E80, Modifier.fillMaxWidth().padding(top = 7.dp), lines = 3)
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
    val range = GoFormat.distance(supply.rangeKm * 1000.0, units)
    val cd = stringResource(R.string.mine_water_bottle_cd, String.format(Locale.ROOT, "%.1f", litres))
    // the board's card: 212 high, radius 26, green at 135°, 8 px in; the bottle (120 × 200, squeezed to 119.11 by the row)
    // and 14 px on the column, 8 px apart: ENOUGH UNTIL, the distance (30, 900), how long (13 on 17) and the verdict
    Row(
        Modifier.fillMaxWidth().height(212.dp).bbox(r = cr(26f), grad = listOf(cssLinear(135f, 0f to 0xFF2F6B3A, 1f to 0xFF1E4A2A))).clickable(role = Role.Button, onClick = onClick).padding(start = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Bottle(fill, litres, Modifier.size(119.11.dp, 200.dp).semantics { contentDescription = cd })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BText(stringResource(R.string.mine_water_enough).uppercase(), bt(12f, 800, 15.5f, spacing = 1.44f), 0xFFA8C8A8)
            BText(stringResource(R.string.mine_water_km, range.unit, range.value), bt(30f, 900, 30f, tnum = true), 0xFFEEF4EC)
            val temp = tempC?.let { "${it.roundToInt()}°" }
            BText(
                listOfNotNull(
                    stringResource(R.string.mine_water_lasts, minutesText(supply.minutes), temp ?: stringResource(R.string.mine_water_mild)),
                    next?.let { stringResource(R.string.mine_water_next, next.name.ifBlank { stringResource(R.string.mine_water_source) }, minutesText(WaterModel.walkMinutes((next.alongM ?: next.distanceM)))) },
                ).joinToString(" · "), bt(13f, 400, 17f, tnum = true), 0xFFC8DCC8, lines = 3,
            )
            val (text, bg) = when (verdict) {
                WaterModel.Verdict.FINE -> R.string.mine_water_fine to 0xFF7CFF6B
                WaterModel.Verdict.TIGHT -> R.string.mine_water_tight to 0xFFFFD27A
                WaterModel.Verdict.LOW -> R.string.mine_water_low to 0xFFFF8A6B
                WaterModel.Verdict.NO_SOURCE -> R.string.mine_water_nosource to 0xFFC8DCC8
            }
            TextPill(stringResource(text), bt(12f, 900, 15.5f), bg, W_INK, h = 26f, padH = 10f)
        }
    }
}

/** The bottle of the board (120 × 200): aluminium with a green cap, water to its level and a label with the litres. */
@Composable
private fun Bottle(fill: Float, litres: Double, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = bt(15f, 900)
    Canvas(modifier) {
        // the SVG's 120 × 200 fitted into its box and centred (`xMidYMid meet`)
        val k = minOf(size.width / 120f, size.height / 200f)
        translate((size.width - 120f * k) / 2f, (size.height - 200f * k) / 2f) {
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        val body = Path().apply {
            moveTo(40f * k, 30f * k); lineTo(80f * k, 30f * k); lineTo(80f * k, 44f * k)
            cubicTo(94f * k, 52f * k, 102f * k, 64f * k, 102f * k, 80f * k); lineTo(102f * k, 180f * k)
            cubicTo(102f * k, 188f * k, 96f * k, 194f * k, 88f * k, 194f * k); lineTo(32f * k, 194f * k)
            cubicTo(24f * k, 194f * k, 18f * k, 188f * k, 18f * k, 180f * k); lineTo(18f * k, 80f * k)
            cubicTo(18f * k, 64f * k, 26f * k, 52f * k, 40f * k, 44f * k); close()
        }
        drawRoundRect(Color(0xFF2F6B3A), p(44f, 6f), androidx.compose.ui.geometry.Size(32f * k, 26f * k), androidx.compose.ui.geometry.CornerRadius(5f * k))
        drawPath(body, Brush.horizontalGradient(0f to Color(0xFF8A929C), 0.35f to Color(0xFFEEF1F4), 1f to Color(0xFF7A828C), startX = 17f * k, endX = 103f * k))
        drawPath(body, Color(0xFF5A626C), style = Stroke(2f * k))
        clipPath(body) {
            val y = (194f - fill * 150f) * k
            drawRect(Color(0xFF2E9CDB).copy(alpha = 0.85f), Offset(0f, y), androidx.compose.ui.geometry.Size(120f * k, 80f * k))
            val wave = Path().apply { moveTo(0f, y); cubicTo(20f * k, y - 6f * k, 40f * k, y + 6f * k, 60f * k, y); cubicTo(80f * k, y - 6f * k, 100f * k, y + 6f * k, 120f * k, y) }
            drawPath(wave, Color(0xFF9FDCFF), style = Stroke(3f * k))
        }
        drawRect(Color(0xFF6E7F4A).copy(alpha = 0.9f), p(18f, 96f), androidx.compose.ui.geometry.Size(84f * k, 30f * k))
        // the label: Geist 15, 900, white, centred on 60 with its baseline at 117
        val t = measurer.measure(String.format(Locale.ROOT, "%.1f L", litres), label.copy(fontSize = label.fontSize * (k / density)))
        drawText(t, Color.White, Offset(60f * k - t.size.width / 2f, 117f * k - t.firstBaseline))
        }
    }
}

@Composable
private fun SourceRow(s: WaterSource, units: Units, onClick: () -> Unit) {
    val away = GoFormat.distance(s.alongM ?: s.distanceM, units)
    val (chip, chipColour) = when (s.status) {
        WaterSource.Status.POTABLE -> stringResource(R.string.mine_water_potable) to 0xFF2E7BC4
        WaterSource.Status.SEASONAL -> stringResource(R.string.mine_water_seasonal) to 0xFF8A929C
        WaterSource.Status.NOT_DRINKING -> stringResource(R.string.mine_water_not_drinking) to 0xFFC8322A
        WaterSource.Status.UNSAID -> stringResource(R.string.mine_water_unsaid) to 0xFF1E9E5A
    }
    val sub = when (s.status) {
        WaterSource.Status.POTABLE -> stringResource(R.string.mine_water_sub_potable)
        WaterSource.Status.SEASONAL -> stringResource(R.string.mine_water_sub_seasonal)
        WaterSource.Status.NOT_DRINKING -> stringResource(R.string.mine_water_sub_not)
        WaterSource.Status.UNSAID -> stringResource(R.string.mine_water_sub_unsaid)
    }
    // the board's row: 76 high, radius 20, 12 px in; how far (the unit 10, 800 over the figure 18, 900) in 48 px, the 44 px
    // tile (radius 14, a 24 px symbol), the name (15, 800) over what the tags say (12), 3 px apart, and the chip (11, 900)
    Row(
        Modifier.fillMaxWidth().height(76.dp).bbox(WHITE, cr(20f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BText(away.unit, bt(10f, 800, 13f), 0xFF8A9A8E)
            BText(away.value, bt(18f, 900, 23.5f, tnum = true), W_INK)
        }
        Box(Modifier.size(44.dp).bbox(s.kind.colour, cr(14f)), contentAlignment = Alignment.Center) { MSy(s.kind.symbol, 24f, WHITE) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BText(s.name.ifBlank { stringResource(kindName(s.kind)) }, bt(15f, 800, 19.5f), W_INK)
            BText(sub, bt(12f, 400, 15.5f), if (s.status == WaterSource.Status.SEASONAL) 0xFF8A5A00 else W_SOFT, lines = 2)
        }
        TextPill(chip, bt(11f, 900, 14f), chipColour, WHITE, h = 24f, padH = 8f)
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
                BText(stringResource(R.string.mine_water_bottle_size, String.format(Locale.ROOT, "%.1f", size)), bt(13f, 700, 17f), W_INK)
                Slider(size, { size = it; left = left.coerceAtMost(it) }, valueRange = 0.5f..3f)
                BText(stringResource(R.string.mine_water_bottle_left, String.format(Locale.ROOT, "%.2f", left)), bt(13f, 700, 17f), W_INK)
                Slider(left, { left = it.coerceAtMost(size) }, valueRange = 0f..size.coerceAtLeast(0.5f))
            }
        },
        confirmButton = { TextButton({ onSave((size * 1000).roundToInt(), (left * 1000).roundToInt()); onClose() }) { Text(stringResource(R.string.mine_data_save)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.mine_offline_keep)) } },
    )
}
