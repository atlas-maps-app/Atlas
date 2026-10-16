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
import app.atlas.ui.mine.MineState
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.BackButton
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.MSy
import app.atlas.ui.mine.TextPill
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
import app.atlas.ui.mine.shift
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

/* The Pump board's inks (`canvas/project/Pump.dc.html`). */
private const val P_INK = 0xFF2A2520
private const val P_SOFT = 0xFF6B6358
private const val LCD = 0xFFFFB547

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
    BoardPage(title = "", onBack = null, header = false, background = Color(0xFFEDE6DA), ink = Color(P_INK), gap = 0.dp, bottomPadding = 28.dp) {
        // The header at y 42: the back round and the segmented way to move (44 high, ink at 8 %, 4 px in, four of 76.5 px).
        Row(Modifier.padding(top = 42.dp + shift()).height(44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton({ nav.back() }, tint = P_INK)
            Row(Modifier.weight(1f).height(44.dp).bbox(0x1414161B, cr(22f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((symbol, label, on, go) in listOf(
                    Seg("place.fuel", R.string.mine_move_fuel, true) {}, Seg("ui.charge", R.string.mine_move_charge, false) { nav.go(Dest.Charge(centre.lat, centre.lon)) },
                    Seg("park.parking", R.string.mine_move_park, false) { nav.go(Dest.Category("parking")) }, Seg("ui.wrench", R.string.mine_move_bike, false) { nav.go(Dest.Twowheel()) },
                )) Row(
                    Modifier.weight(1f).height(36.dp).bbox(if (on) INK else 0, cr(18f)).clickable(role = Role.Button, onClick = go),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    MSy(symbol, 16f, if (on) WHITE else INK)
                    BText(stringResource(label), bt(12f, 800, 15.5f), if (on) WHITE else INK)
                }
            }
        }
        // The gauge row, 18 px under: the gauge (64 × 40) and 12 px on the tank (16, 800 on 20.5) over what fills it (12).
        Row(Modifier.fillMaxWidth().padding(top = 18.dp).clickable(role = Role.Button) { tankDialog = true }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FuelGauge(level, Modifier.size(64.dp, 40.dp))
            Column(Modifier.weight(1f)) {
                val word = PumpModel.tankWord(level)
                BText(if (word == "full" || word == "empty") stringResource(if (word == "full") R.string.mine_pump_full else R.string.mine_pump_empty) else stringResource(R.string.mine_pump_tank, word), bt(16f, 800, 20.5f), P_INK)
                BText(stringResource(R.string.mine_pump_to_fill, litres.roundToInt(), tank), bt(12f, 400, 15.5f), P_SOFT)
            }
        }
        PumpCard(grade, counts, litres, bestPrice, Modifier.padding(top = 16.dp), onPick = { g -> grade = g; scope.launch { services.settings.set(MineKeys.FUEL_GRADE, g.name) } })
        when {
            stations == null -> BText(stringResource(R.string.kit_loading), bt(13f, 500, 17f), P_SOFT, Modifier.padding(top = 16.dp))
            ranked.isEmpty() -> MineState(
                "place.fuel", stringResource(if (all.isEmpty()) R.string.mine_pump_none else R.string.mine_pump_none_grade, grade.label),
                text = stringResource(if (failure != null) R.string.mine_pump_offline else R.string.mine_pump_none_text), tint = Color(P_SOFT),
            )
            else -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, s) in ranked.take(4).withIndex()) StationRow(s, grade, ranked, litres, units, best = i == 0, onClick = {
                    nav.go(Dest.Route(s.place.location.lat, s.place.location.lon, s.name, centre.lat, centre.lon, null, "CAR"))
                })
            }
        }
        BText(stringResource(if (priced) R.string.mine_pump_source_tk else R.string.mine_pump_source_none), bt(10.5f, 400, 15f), 0xFF8A8478, Modifier.padding(start = 6.dp, end = 6.dp, top = 16.dp), lines = 4)
    }
    if (tankDialog) TankDialog(tank, level, onClose = { tankDialog = false }, onSave = { t, l -> scope.launch { services.settings.set(MineKeys.FUEL_TANK_L, t); services.settings.set(MineKeys.FUEL_LEFT, l) } })
}

private data class Seg(val symbol: String, val label: Int, val on: Boolean, val go: () -> Unit)

/** The gauge of the board (64 × 40): an arc from E to F, the red zone at E, three ticks, E and F (Geist 8, 900), and the needle at the level. */
@Composable
private fun FuelGauge(level: Float, modifier: Modifier) {
    val cd = stringResource(R.string.mine_pump_gauge_cd, (level * 100).roundToInt())
    val measurer = rememberTextMeasurer()
    val letter = bt(8f, 900)
    Canvas(modifier.semantics { contentDescription = cd }) {
        val k = size.width / 64f
        val ink = Color(P_INK)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        val arc = Path().apply { arcTo(androidx.compose.ui.geometry.Rect(p(6f, 10f), p(58f, 62f)), 180f, 180f, true) }
        drawPath(arc, ink, style = Stroke(3f * k, cap = StrokeCap.Round))
        // the red zone: from E (6, 36) to (10.6, 21.2), 34.7° of the arc
        val red = Path().apply { arcTo(androidx.compose.ui.geometry.Rect(p(6f, 10f), p(58f, 62f)), 180f, 34.7f, true) }
        drawPath(red, Color(0xFFC8322A), style = Stroke(5f * k))
        for (t in listOf(floatArrayOf(32f, 10f, 32f, 15f), floatArrayOf(13.6f, 17.6f, 17.1f, 21.1f), floatArrayOf(50.4f, 17.6f, 46.9f, 21.1f))) drawLine(ink, p(t[0], t[1]), p(t[2], t[3]), 2f * k)
        for ((s, x) in listOf("E" to 4f, "F" to 56f)) {
            val t = measurer.measure(s, letter.copy(fontSize = letter.fontSize * (k / density)))
            drawText(t, ink, Offset(p(x, 30f).x, p(x, 30f).y - t.firstBaseline))
        }
        val a = Math.PI * (1.0 - level.coerceIn(0f, 1f))
        drawLine(Color(0xFFC8322A), p(32f, 36f), p(32f + 19.8f * cos(a).toFloat(), 36f - 19.8f * sin(a).toFloat()), 3f * k, StrokeCap.Round)
        drawCircle(ink, 4f * k, p(32f, 36f))
    }
}

/**
 * The red pump as the board's (radius 28, its red falling from #D8423A to #B32B24, a soft red shadow and a light top edge,
 * 12 px in): the cream display (138 high, radius 16, 8 px in) holding the dark LCD (radius 10, shaded inside) with what the
 * best station would charge for the tank, and 10 px under it the six grade buttons, 6 px apart.
 */
@Composable
private fun PumpCard(grade: Fuel, counts: Map<Fuel, Int>, litres: Double, price: Double?, modifier: Modifier = Modifier, onPick: (Fuel) -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .bbox(r = cr(28f), shadows = listOf(CssShadow(0f, 16f, 30f, 0f, 0x4CB32B24), CssShadow(0f, 2f, 0f, 0f, 0x4CFFFFFF, inset = true)), grad = listOf(cssLinear(180f, 0f to 0xFFD8423A, 1f to 0xFFB32B24)))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(138.dp).bbox(r = cr(16f), grad = listOf(cssLinear(180f, 0f to 0xFFF4EEE2, 1f to 0xFFDCD3C2))).padding(8.dp)) {
            Column(
                Modifier.fillMaxWidth().height(122.dp).bbox(0xFF1B1F24, cr(10f), listOf(CssShadow(0f, 3f, 10f, 0f, 0x99000000, inset = true))).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceAround,
            ) {
                LcdRow(stringResource(R.string.mine_pump_to_pay), price?.let { PumpModel.money(it * litres) } ?: "--.--", 40f, 1f, glow = true)
                LcdRow(stringResource(R.string.mine_pump_litres), String.format(Locale.ROOT, "%.2f", litres), 30f, 0.85f)
                LcdRow(stringResource(R.string.mine_pump_per_litre), price?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-.---", 30f, 0.85f)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in Fuel.entries) GradeButton(f, f == grade, (counts[f] ?: 0) > 0, Modifier.weight(1f)) { onPick(f) }
        }
    }
}

/** A line of the LCD: its name (11, 800, 1.54 px apart, khaki) and the amber VT323 figures at the right ([glow] for the total's 8 px glow). */
@Composable
private fun LcdRow(label: String, value: String, size: Float, alpha: Float, glow: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BText(label.uppercase(), bt(11f, 800, 14f, spacing = 1.54f), 0xFF9A8A6A)
        // the board's text-shadow 0 0 8px: a Gaussian of σ = 4 px, as Skia's blur radius
        val d = androidx.compose.ui.platform.LocalDensity.current.density
        val style = bt(size, 400, size, "VT323").let { if (glow) it.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99FFB547), Offset.Zero, (8f * d / 2f - 0.5f) / 0.57735f)) else it }
        BText(value, style, if (alpha < 1f) ((alpha * 255).roundToInt().toLong() shl 24) or (LCD and 0xFFFFFF) else LCD)
    }
}

/**
 * A grade button of the board (58 high, radius 12, cream): the grade's colour bar (26 × 6) and 3 px under it its name (900);
 * the one pressed ringed in yellow with a glow, the others shaded at their foot.
 */
@Composable
private fun GradeButton(f: Fuel, on: Boolean, available: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = f.label
    Column(
        modifier.height(58.dp)
            .bbox(
                0xFFF4EEE2, cr(12f),
                if (on) listOf(CssShadow(0f, 0f, 0f, 2f, 0xFFFFE08A), CssShadow(0f, 0f, 14f, 0f, 0xE6FFE08A)) else listOf(CssShadow(0f, -3f, 0f, 0f, 0x26000000, inset = true)),
            )
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(26.dp, 6.dp).bbox(if (available) f.colour else (f.colour and 0xFFFFFF) or 0x4D000000, cr(3f)))
        val (size, line) = when (f) { Fuel.ADBLUE -> 11f to 14f; Fuel.DIESEL -> 12f to 15.5f; else -> 13f to 17f }
        BText(label, bt(size, 900, line), if (available) P_INK else (P_INK and 0xFFFFFF) or 0x66000000)
    }
}

/** A station: the amber price box (or the grade it sells, when no price is known), its name and tag, street, what is there, how far. */
@Composable
private fun StationRow(s: Station, grade: Fuel, all: List<Station>, litres: Double, units: Units, best: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val price = s.price(grade)
    val brand = remember(s.place) { Brands.forTags(s.place.tags) }
    val saving = PumpModel.saving(s, all, grade, litres)
    // the board's row: 76 high, radius 22, 12 px in; the best white and ringed in red, the rest at 70 %; the dark price box
    // (92 × 44, radius 10) with its amber VT323 figures, 12 px on the name (16, 800) and its tag, the street (12) and what
    // is there (16 px symbols 6 apart), 3 px apart, and how far at the right (15, 900)
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .bbox(if (best) WHITE else 0xB2FFFFFF, cr(22f), if (best) listOf(CssShadow(0f, 0f, 0f, 2f, 0xFFC8322A)) else emptyList(), clip = false)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val logoOnly = price == null && brand != null
        Box(
            Modifier.size(if (logoOnly) 44.dp else 92.dp, 44.dp).then(if (logoOnly) Modifier else Modifier.bbox(0xFF1B1F24, cr(10f), listOf(CssShadow(0f, -2f, 0f, 0f, 0x14FFFFFF, inset = true)))),
            contentAlignment = Alignment.Center,
        ) {
            if (price != null) {
                val (main, sup) = PumpModel.lcd(price)
                Row(verticalAlignment = Alignment.Top) {
                    BText(main, bt(30f, 400, 30f, "VT323"), 0xFFFFD27A)
                    BText(sup, bt(18f, 400, 19.8f, "VT323"), 0xFFFFD27A, Modifier.padding(top = 0.5.dp))
                }
            } else if (brand != null) BrandLogo(brand, 44.dp)
            else BText(grade.label, bt(26f, 400, 26f, "VT323"), 0xCCFFD27A)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BText(s.name.ifBlank { stringResource(R.string.mine_pump_station) }, bt(16f, 800, 20.5f), P_INK, Modifier.weight(1f, fill = false))
                if (saving != null && saving.second >= 0.5) Tag(stringResource(R.string.mine_pump_save, PumpModel.money(saving.second)), 0xFFE1F2EA, 0xFF12663A)
                else if (best && price == null) Tag(stringResource(R.string.mine_pump_closest), 0xFFEEE8DC, P_SOFT)
            }
            BText(listOfNotNull(s.street).joinToString(" · ").ifEmpty { s.brand ?: "" }, bt(12f, 400, 15.5f), P_SOFT)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (a in s.amenities.take(5)) MSy(a.symbol, 16f, P_SOFT) }
        }
        s.distanceM?.let { BText(GoFormat.distance(it, units).toString(), bt(15f, 900, 19.5f, tnum = true), P_INK) }
    }
}

/** A tag of the board: 20 high, radius 10, 7 px in, 11, 800. */
@Composable
private fun Tag(text: String, bg: Long, fg: Long) {
    TextPill(text, bt(11f, 800, 14f), bg, fg, h = 20f, padH = 7f)
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
                BText(stringResource(R.string.mine_pump_tank_size, size.roundToInt()), bt(13f, 700, 17f), P_INK)
                Slider(size, { size = it }, valueRange = 10f..120f)
                BText(stringResource(R.string.mine_pump_tank_level, (left * 100).roundToInt()), bt(13f, 700, 17f), P_INK)
                Slider(left, { left = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = { TextButton({ onSave(size.roundToInt(), left); onClose() }) { Text(stringResource(R.string.mine_data_save)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.mine_offline_keep)) } },
    )
}
