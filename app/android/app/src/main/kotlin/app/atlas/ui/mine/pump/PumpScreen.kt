package app.atlas.ui.mine.pump

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brands
import app.atlas.services.Units
import app.atlas.ui.go.GoFormat
import app.atlas.ui.kit.Atlas
import app.atlas.ui.search.BrandLogo
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.Vt323Family
import app.atlas.ui.mine.mt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Ink = Color(0xFF2A2520)
private val Soft = Color(0xFF6B6358)
private val Lcd = Color(0xFFFFB547)

/**
 * The Pump board: the fuel stations around you on a red pump. The display shows what filling the tank costs at the best
 * station for the fuel you press (E10, 95, 98, Diesel, LPG, AdBlue), the stations below it show the fuels they sell from
 * their OpenStreetMap tags with their brand, street and what is there (shop, wash, air, toilets, open round the clock).
 * Prices come only from a source that is open to Atlas: Tankerkönig in Germany with your own key; elsewhere the display says
 * it has none rather than guess.
 */
@Composable
fun PumpScreen(args: Dest.Pump, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val here = rememberHere()
    val centre = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    val tank by services.settings.flow(MineKeys.FUEL_TANK_L, 50).collectAsStateWithLifecycle(50)
    val level by services.settings.flow(MineKeys.FUEL_LEFT, 0.25f).collectAsStateWithLifecycle(0.25f)
    val gradeName by services.settings.flow(MineKeys.FUEL_GRADE, Fuel.E10.name).collectAsStateWithLifecycle(Fuel.E10.name)
    val keys by services.keys.keys.collectAsStateWithLifecycle()
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    var grade by rememberSaveable(gradeName) { mutableStateOf(Fuel.of(gradeName) ?: Fuel.E10) }
    var stations by remember(centre, keys?.get(Tankerkoenig.ID)) { mutableStateOf<List<Station>?>(null) }
    var priced by remember(centre) { mutableStateOf(false) }
    var failure by remember(centre) { mutableStateOf<String?>(null) }
    var tankDialog by remember { mutableStateOf(false) }
    val lang = remember { Locale.getDefault().language }
    LaunchedEffect(centre, keys?.get(Tankerkoenig.ID)) {
        val found = withContext(Dispatchers.IO) {
            runCatching {
                services.places.nearby("fuel", centre, 5_000.0, 40).first { it.places.isNotEmpty() || it.error != null || it.source == app.atlas.services.PlaceOrigin.STORE }
            }.getOrNull()
        }
        val places = found?.places.orEmpty()
        failure = found?.error
        val tk = Tankerkoenig(services.http, services.keys)
        val prices = if (tk.ready && Tankerkoenig.inGermany(centre) && !services.config.offline && services.connectivity.online.value) {
            withContext(Dispatchers.IO) { runCatching { tk.near(centre) }.getOrElse { failure = it.message; emptyList() } }
        } else emptyList()
        priced = prices.isNotEmpty()
        stations = if (prices.isEmpty()) places.map { PumpModel.station(it, centre, lang) } else Tankerkoenig.merge(places, prices, centre)
    }
    val all = stations.orEmpty()
    val ranked = remember(all, grade) { PumpModel.ranked(all, grade) }
    val counts = remember(all) { PumpModel.counts(all) }
    val litres = PumpModel.litresToFill(tank, level)
    val best = ranked.firstOrNull()
    val bestPrice = best?.price(grade)
    val pageBg = Color(0xFFEDE6DA)
    BoardPage(
        title = "", onBack = null, header = false, background = pageBg, ink = Ink, gap = 12.dp, bottomPadding = 100.dp,
    ) {
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.kit_back)
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) { Symbol("ui.back", tint = Ink, size = 20.dp) }
            Row(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(Color(0x14141B1B)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((symbol, label, on, go) in listOf(
                    Seg("place.fuel", R.string.mine_move_fuel, true) {}, Seg("ui.charge", R.string.mine_move_charge, false) { nav.go(Dest.Charge(centre.lat, centre.lon)) },
                    Seg("park.parking", R.string.mine_move_park, false) { nav.go(Dest.Category("parking")) }, Seg("mode.bike", R.string.mine_move_bike, false) { nav.go(Dest.Twowheel()) },
                )) Row(
                    Modifier.weight(1f).height(36.dp).clip(CircleShape).background(if (on) Ink else Color.Transparent).clickable(onClick = go),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(symbol, tint = if (on) Color.White else Color(0xFF14161B), size = 16.dp)
                    Text(stringResource(label), style = mt(12f, 800), color = if (on) Color.White else Color(0xFF14161B), maxLines = 1)
                }
            }
        }
        // The gauge row.
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 0.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { tankDialog = true }) { FuelGauge(level, Modifier.size(64.dp, 40.dp)) }
            Column(Modifier.weight(1f).clickable { tankDialog = true }) {
                val word = PumpModel.tankWord(level)
                Text(if (word == "full" || word == "empty") stringResource(if (word == "full") R.string.mine_pump_full else R.string.mine_pump_empty) else stringResource(R.string.mine_pump_tank, word), style = mt(16f, 800), color = Ink)
                Text(stringResource(R.string.mine_pump_to_fill, litres.roundToInt(), tank), style = mt(12f, 400), color = Soft)
            }
        }
        PumpCard(grade, counts, litres, bestPrice, onPick = { g -> grade = g; scope.launch { services.settings.set(MineKeys.FUEL_GRADE, g.name) } })
        when {
            stations == null -> Text(stringResource(R.string.kit_loading), style = mt(13f, 500), color = Soft)
            ranked.isEmpty() -> StateMessage(
                "place.fuel", stringResource(if (all.isEmpty()) R.string.mine_pump_none else R.string.mine_pump_none_grade, grade.label),
                text = stringResource(if (failure != null) R.string.mine_pump_offline else R.string.mine_pump_none_text), tint = Soft,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, s) in ranked.take(4).withIndex()) StationRow(s, grade, ranked, litres, units, best = i == 0, onClick = {
                    nav.go(Dest.Route(s.place.location.lat, s.place.location.lon, s.name, centre.lat, centre.lon, null, "CAR"))
                })
            }
        }
        Text(
            stringResource(if (priced) R.string.mine_pump_source_tk else R.string.mine_pump_source_none),
            style = mt(10.5f, 400, line = 15f), color = Color(0xFF8A8478), modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
    if (tankDialog) TankDialog(tank, level, onClose = { tankDialog = false }, onSave = { t, l -> scope.launch { services.settings.set(MineKeys.FUEL_TANK_L, t); services.settings.set(MineKeys.FUEL_LEFT, l) } })
}

private data class Seg(val symbol: String, val label: Int, val on: Boolean, val go: () -> Unit)

/** The gauge of the board (64 × 40): an arc from E to F, a red zone at E, three ticks, and a needle at the level. */
@Composable
private fun FuelGauge(level: Float, modifier: Modifier) {
    val cd = stringResource(R.string.mine_pump_gauge_cd, (level * 100).roundToInt())
    Canvas(modifier.semantics { contentDescription = cd }) {
        val k = size.width / 64f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        val arc = Path().apply { arcTo(androidx.compose.ui.geometry.Rect(p(6f, 10f), p(58f, 62f)), 180f, 180f, true) }
        drawPath(arc, Ink, style = Stroke(3f * k, cap = StrokeCap.Round))
        val red = Path().apply { arcTo(androidx.compose.ui.geometry.Rect(p(6f, 10f), p(58f, 62f)), 180f, 29.5f, true) }
        drawPath(red, Color(0xFFC8322A), style = Stroke(5f * k))
        for (t in listOf(floatArrayOf(32f, 10f, 32f, 15f), floatArrayOf(13.6f, 17.6f, 17.1f, 21.1f), floatArrayOf(50.4f, 17.6f, 46.9f, 21.1f))) drawLine(Ink, p(t[0], t[1]), p(t[2], t[3]), 2f * k)
        label("E", p(4f, 30f), 8f * k, Ink, left = true)
        label("F", p(56f, 30f), 8f * k, Ink, left = true)
        val a = Math.PI * (1.0 - level.coerceIn(0f, 1f))
        drawLine(Color(0xFFC8322A), p(32f, 36f), p(32f + 19.8f * cos(a).toFloat(), 36f - 19.8f * sin(a).toFloat()), 3f * k, StrokeCap.Round)
        drawCircle(Ink, 4f * k, p(32f, 36f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(s: String, at: Offset, size: Float, colour: Color, left: Boolean = false) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = colour.toArgb(); typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = if (left) android.graphics.Paint.Align.LEFT else android.graphics.Paint.Align.CENTER }
    drawContext.canvas.nativeCanvas.drawText(s, at.x, at.y, paint)
}

/** The red pump: the amber display with what the best station would charge for the tank, and the six grade buttons. */
@Composable
private fun PumpCard(grade: Fuel, counts: Map<Fuel, Int>, litres: Double, price: Double?, onPick: (Fuel) -> Unit) {
    val body = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().cssShadow(body, 16.dp, 30.dp, Color(0x4DB32B24)).clip(body).background(Brush.verticalGradient(listOf(Color(0xFFD8423A), Color(0xFFB32B24)))).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(138.dp).clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(Color(0xFFF4EEE2), Color(0xFFDCD3C2)))).padding(8.dp)) {
            Column(
                Modifier.fillMaxWidth().height(122.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1F24)).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceAround,
            ) {
                LcdRow(stringResource(R.string.mine_pump_to_pay), price?.let { PumpModel.money(it * litres) } ?: "--.--", 40f, 1f)
                LcdRow(stringResource(R.string.mine_pump_litres), String.format(Locale.ROOT, "%.2f", litres), 30f, 0.85f)
                LcdRow(stringResource(R.string.mine_pump_per_litre), price?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-.---", 30f, 0.85f)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in Fuel.entries) GradeButton(f, f == grade, (counts[f] ?: 0) > 0, Modifier.weight(1f)) { onPick(f) }
        }
    }
}

@Composable
private fun LcdRow(label: String, value: String, size: Float, alpha: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = mt(11f, 800, spacing = 1.5f), color = Color(0xFF9A8A6A))
        Text(value, style = TextStyle(fontFamily = Vt323Family, fontSize = size.sp, lineHeight = size.sp), color = Lcd.copy(alpha = alpha))
    }
}

@Composable
private fun GradeButton(f: Fuel, on: Boolean, available: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val label = f.label
    Column(
        modifier.height(58.dp).then(if (on) Modifier.cssShadow(shape, 0.dp, 14.dp, Color(0xE6FFE08A)) else Modifier).clip(shape).background(Color(0xFFF4EEE2))
            .then(if (on) Modifier.border2() else Modifier).clickable(onClick = onClick).semantics { contentDescription = label; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(26.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(Color(f.colour).copy(alpha = if (available) 1f else 0.3f)))
        Text(label, style = mt(if (f == Fuel.ADBLUE) 11f else if (f == Fuel.DIESEL) 12f else 13f, 900), color = if (available) Ink else Ink.copy(alpha = 0.4f), maxLines = 1)
    }
}

private fun Modifier.border2(): Modifier = this.border(2.dp, Color(0xFFFFE08A), RoundedCornerShape(12.dp))

/** A station: the amber price box (or the grade it sells, when no price is known), its name and tag, street, what is there, how far. */
@Composable
private fun StationRow(s: Station, grade: Fuel, all: List<Station>, litres: Double, units: Units, best: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val price = s.price(grade)
    val brand = remember(s.place) { Brands.forTags(s.place.tags) }
    val saving = PumpModel.saving(s, all, grade, litres)
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).background(if (best) Color.White else Color(0xB3FFFFFF)).then(if (best) Modifier.androidRing(shape) else Modifier).clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val logoOnly = price == null && brand != null
        Box(Modifier.size(if (logoOnly) 44.dp else 92.dp, 44.dp).clip(RoundedCornerShape(10.dp)).background(if (logoOnly) Color.Transparent else Color(0xFF1B1F24)), contentAlignment = Alignment.Center) {
            if (price != null) {
                val (main, sup) = PumpModel.lcd(price)
                Row(verticalAlignment = Alignment.Top) {
                    Text(main, style = TextStyle(fontFamily = Vt323Family, fontSize = 30.sp, lineHeight = 30.sp), color = Color(0xFFFFD27A))
                    Text(sup, style = TextStyle(fontFamily = Vt323Family, fontSize = 18.sp, lineHeight = 20.sp), color = Color(0xFFFFD27A))
                }
            } else if (brand != null) BrandLogo(brand, 44.dp)
            else Text(grade.label, style = TextStyle(fontFamily = Vt323Family, fontSize = 26.sp, lineHeight = 26.sp), color = Color(0xFFFFD27A).copy(alpha = 0.8f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name.ifBlank { stringResource(R.string.mine_pump_station) }, style = mt(16f, 800), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (saving != null && saving.second >= 0.5) Tag(stringResource(R.string.mine_pump_save, PumpModel.money(saving.second)), Color(0xFFE1F2EA), Color(0xFF12663A))
                else if (best && price == null) Tag(stringResource(R.string.mine_pump_closest), Color(0xFFEEE8DC), Soft)
            }
            Text(listOfNotNull(s.street, if (s.open24h) null else null).joinToString(" · ").ifEmpty { s.brand ?: "" }, style = mt(12f, 400), color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (a in s.amenities.take(5)) Symbol(a.symbol, tint = Soft, size = 16.dp) }
        }
        s.distanceM?.let { Text(GoFormat.distance(it, units).toString(), style = mt(15f, 900, tabular = true), color = Ink) }
    }
}

private fun Modifier.androidRing(shape: RoundedCornerShape): Modifier = this.border(2.dp, Color(0xFFC8322A), shape)

@Composable
private fun Tag(text: String, bg: Color, fg: Color) {
    Box(Modifier.height(20.dp).clip(CircleShape).background(bg).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) { Text(text, style = mt(11f, 800), color = fg, maxLines = 1) }
}

/** The tank: its size in litres and how full it is, kept on the phone. */
@Composable
private fun TankDialog(tank: Int, level: Float, onClose: () -> Unit, onSave: (Int, Float) -> Unit) {
    var size by remember { mutableFloatStateOf(tank.toFloat()) }
    var left by remember { mutableFloatStateOf(level) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.mine_pump_tank_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mine_pump_tank_size, size.roundToInt()), style = mt(13f, 700))
                Slider(size, { size = it }, valueRange = 10f..120f)
                Text(stringResource(R.string.mine_pump_tank_level, (left * 100).roundToInt()), style = mt(13f, 700))
                Slider(left, { left = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = { TextButton({ onSave(size.roundToInt(), left); onClose() }) { Text(stringResource(R.string.mine_data_save)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.mine_offline_keep)) } },
    )
}
