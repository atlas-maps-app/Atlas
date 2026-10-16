package app.atlas.ui.mine.power

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Forecast
import app.atlas.services.Units
import app.atlas.ui.go.GoFormat
import app.atlas.ui.kit.Atlas
import app.atlas.ui.mine.MineState
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.walkMinutes
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.CardLabel
import app.atlas.ui.mine.FAINT
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.MSy
import app.atlas.ui.mine.PAGE
import app.atlas.ui.mine.SOFT
import app.atlas.ui.mine.TextPill
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
import app.atlas.ui.mine.hairTop
import app.atlas.ui.mine.ring
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.ZonedLocal
import app.atlas.ui.mine.SectionLabel
import app.atlas.ui.mine.mt
import app.atlas.ui.mine.serifTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt


/**
 * The Power board, for the phone's own battery: how much is left and for how long (when the phone says), the fastest ways
 * to charge around you (power-bank lockers, cafés and libraries with outlets, charging pillars, solar benches: from the
 * `amenity=device_charging_station`, `power_supply` and `socket:*` tags of the places on this phone), and for off the grid
 * the day's yield of a 100 W panel from the sun's height and the forecast's cloud, your power bank's worth, and a campsite
 * with a hook-up when one is near.
 */
@Composable
fun PowerScreen(args: Dest.Power, nav: Navigator) {
    val services = LocalServices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    var battery by remember { mutableStateOf(PhoneBattery.read(context)) }
    var spots by remember(here) { mutableStateOf<List<PowerSpot>?>(null) }
    var hookup by remember(here) { mutableStateOf<PowerSpot?>(null) }
    var forecast by remember(here) { mutableStateOf<Forecast?>(null) }
    val bank by services.settings.flow(MineKeys.POWER_BANK_MAH).collectAsStateWithLifecycle(null)
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val twelve by services.settings.flow(MineKeys.CLOCK_12, false).collectAsStateWithLifecycle(false)
    var bankDialog by remember { mutableStateOf(false) }
    LaunchedEffect(here) {
        withContext(Dispatchers.IO) {
            spots = runCatching { PowerModel.spots(services.placeStores, here) }.getOrDefault(emptyList())
            hookup = runCatching { PowerModel.hookup(services.placeStores, here) }.getOrNull()
            forecast = runCatching { services.weather.kept(here) ?: if (!services.config.offline && services.connectivity.online.value) services.weather.forecast(here) else null }.getOrNull()
        }
        battery = PhoneBattery.read(context)
    }
    val today = remember(clock) { java.time.LocalDate.now(clock) }
    val curve = remember(here, forecast, today) { PowerModel.solarCurve(here, zone, today, forecast) }
    val wh = PowerModel.wattHours(curve)
    val clouds = remember(forecast, today) { PowerModel.cloudsAt(forecast, zone, today) }
    BoardPage(
        title = stringResource(R.string.mine_power_title), onBack = { nav.back() }, background = Color(0xFFF1EFEA), ink = Color(INK), titleSize = 34f,
        top = 10.dp, gap = 0.dp,
        trailing = {
            // the board's pill: amber, 34 high, 12 px in, 12, 800 on 15.5
            val saver = battery?.saver == true
            TextPill(
                stringResource(if (saver) R.string.mine_power_saver_on else R.string.mine_power_saver), bt(12f, 800, 15.5f), 0xFFFFF1D6, 0xFF8A5A00, h = 34f, padH = 12f,
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            )
        },
    ) {
        BatteryCard(battery)
        CardLabel(stringResource(R.string.mine_power_fastest), Modifier.padding(start = 2.dp, top = 16.dp, bottom = 6.dp))
        val list = spots
        when {
            list == null -> BText(stringResource(R.string.kit_loading), bt(13f, 500, 17f), SOFT)
            list.isEmpty() -> MineState("power.bank", stringResource(R.string.mine_power_none), text = stringResource(R.string.mine_power_none_text), tint = Color(SOFT))
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { for (s in list.take(5)) SpotRow(s, units, onClick = { services.remember(s.place); nav.go(Dest.Place.of(s.place)) }) }
        }
        OffGrid(curve, wh, clouds?.let { ZonedLocal.hm(it, zone, twelve) }, today, zone, bank, battery, hookup, units, onBank = { bankDialog = true }, Modifier.padding(top = 10.dp))
        BText(stringResource(R.string.mine_power_footer), bt(10f, 400, 14.5f, align = TextAlign.Center), 0xFF7A7F8C, Modifier.fillMaxWidth().padding(start = 1.dp, end = 1.dp, top = 8.dp), lines = 4)
    }
    if (bankDialog) {
        var text by remember { mutableStateOf(bank?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { bankDialog = false },
            title = { Text(stringResource(R.string.mine_power_bank_title)) },
            text = {
                Box(Modifier.fillMaxWidth().bbox(PAGE, cr(12f)).padding(12.dp)) {
                    val fieldName = stringResource(R.string.mine_power_bank_title)
                    if (text.isEmpty()) BText("20000", bt(15f, 500, 19.5f), FAINT)
                    BasicTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, textStyle = bt(15f, 600, 19.5f).copy(color = Color(INK)), cursorBrush = SolidColor(Color(INK)), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldName })
                }
            },
            confirmButton = { TextButton({ bankDialog = false; scope.launch { services.settings.set(MineKeys.POWER_BANK_MAH, text.toIntOrNull()?.takeIf { it in 500..100_000 }) } }) { Text(stringResource(R.string.mine_data_save)) } },
            dismissButton = { TextButton({ bankDialog = false }) { Text(stringResource(R.string.mine_offline_keep)) } },
        )
    }
}

/**
 * The board's dark card (92 high, radius 22, 16 px in, 14 between): the battery (88 × 44, a 3 px white rim with 4 px inside,
 * its charge as a share of the cell in red, amber or green, and the terminal), the percentage (28, 900) and how long it lasts.
 */
@Composable
private fun BatteryCard(b: PhoneBattery?) {
    val level = b?.percent ?: 0
    val colour = when { level <= 15 -> 0xFFFF5A3C; level <= 40 -> 0xFFF2A71B; else -> 0xFF3FCF8E }
    val cd = if (b == null) stringResource(R.string.mine_power_battery_none) else stringResource(R.string.mine_power_battery_cd, level)
    Row(
        Modifier.fillMaxWidth().height(92.dp).bbox(INK, cr(22f)).padding(horizontal = 16.dp).semantics { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(88.dp, 44.dp)) {
            Box(Modifier.fillMaxSize().bbox(r = cr(10f), border = ring(3f, WHITE), clip = false).padding(7.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).bbox(colour, cr(4f)))
            }
            Box(Modifier.offset(91.dp, 15.dp).size(6.dp, 14.dp).bbox(WHITE, CssCorners(0f, 3f, 3f, 0f)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(if (b == null) "–" else "$level%", bt(28f, 900, 28f, tnum = true), WHITE)
            val line = when {
                b == null -> stringResource(R.string.mine_power_battery_none)
                b.charging -> stringResource(R.string.mine_power_charging)
                b.hoursLeft != null -> stringResource(R.string.mine_power_left, durationText(b.hoursLeft))
                else -> stringResource(R.string.mine_power_left_unknown)
            }
            BText(line, bt(12f, 400, 15.5f, tnum = true), 0xFFC9CED8, lines = 2)
        }
    }
}

@Composable
private fun SpotRow(s: PowerSpot, units: Units, onClick: () -> Unit) {
    val feeWith = stringResource(R.string.mine_power_d_fee_x, "%s")
    val detail = PowerModel.detail(
        s.place,
        PowerModel.Words(
            usb = stringResource(R.string.mine_power_d_usb), plugs = stringResource(R.string.mine_power_d_plugs), outlets = stringResource(R.string.mine_power_d_outlets),
            free = stringResource(R.string.mine_power_d_free), fee = stringResource(R.string.mine_power_d_fee), feeWith = { feeWith.replace("%s", it) },
            open24h = stringResource(R.string.mine_power_d_24h),
        ),
    )
    // the board's row: 62 high, radius 18, 12 px in; the 40 px tile (radius 12, a 21 px symbol), 12 px on the name (15, 800
    // on 19.5) and 2 px under it how (12 on 15.5), and at the right how far (15, 900)
    Row(
        Modifier.fillMaxWidth().height(62.dp).bbox(WHITE, cr(18f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).bbox(s.kind.colour, cr(12f)), contentAlignment = Alignment.Center) { MSy(s.kind.symbol, 21f, WHITE) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(s.name.ifBlank { stringResource(kindName(s.kind)) }, bt(15f, 800, 19.5f), INK)
            BText(detail.ifBlank { stringResource(kindName(s.kind)) }, bt(12f, 400, 15.5f), SOFT)
        }
        BText(if (s.distanceM < 150) GoFormat.distance(s.distanceM, units).toString() else walkMinutes(app.atlas.ui.kit.WalkTime.seconds(s.distanceM)), bt(15f, 900, 19.5f, tnum = true), INK)
    }
}

/** "40 min", "1.5 h", "12 h" in the person's language. */
@Composable
private fun durationText(hours: Double): String = when {
    hours < 1 -> stringResource(R.string.mine_min_n, maxOf(1, (hours * 60).roundToInt()))
    hours < 10 -> stringResource(R.string.mine_h_x, String.format(java.util.Locale.ROOT, "%.1f", hours).removeSuffix(".0"))
    else -> stringResource(R.string.mine_h_n, hours.roundToInt())
}

private fun kindName(k: PowerSpot.Kind): Int = when (k) {
    PowerSpot.Kind.LOCKER -> R.string.mine_power_k_locker; PowerSpot.Kind.CAFE -> R.string.mine_power_k_cafe; PowerSpot.Kind.PILLAR -> R.string.mine_power_k_pillar
    PowerSpot.Kind.LIBRARY -> R.string.mine_power_k_library; PowerSpot.Kind.SOLAR -> R.string.mine_power_k_solar; PowerSpot.Kind.OUTLET -> R.string.mine_power_k_outlet
}

/**
 * "Off the grid" as the board's card (radius 22, 14 px in, 10 above): the label, the yield curve (230 × 74) beside the day's
 * watt-hours (18, 900) and when the clouds come (11 on 14.3), then the rows: the bank and a hook-up.
 */
@Composable
private fun OffGrid(curve: DoubleArray, wh: Double, clouds: String?, today: java.time.LocalDate, zone: java.time.ZoneId, bank: Int?, battery: PhoneBattery?, hookup: PowerSpot?, units: Units, onBank: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().bbox(WHITE, cr(22f)).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp)) {
        CardLabel(stringResource(R.string.mine_power_off_grid))
        Row(Modifier.padding(top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SolarChart(curve, java.time.LocalTime.now(java.time.Clock.system(zone)).let { it.hour * 60 + it.minute }, Modifier.size(230.dp, 74.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BText("≈ ${wh.roundToInt()} Wh", bt(18f, 900, 23.5f, tnum = true), INK)
                BText(listOfNotNull(stringResource(R.string.mine_power_panel), clouds?.let { stringResource(R.string.mine_power_clouds, it) }).joinToString("\n"), bt(11f, 400, 14.3f, tnum = true), SOFT, lines = 3)
            }
        }
        val worth = bank?.let { PowerModel.bankWorth(it, battery?.capacityMah?.let { mAh -> mAh * 3.85 / 1000.0 } ?: 17.0) }
        OffRow("power.bank", 0xFF7B4DFF, if (bank != null) stringResource(R.string.mine_power_bank, java.text.NumberFormat.getIntegerInstance().format(bank)) else stringResource(R.string.mine_power_bank_set), worth?.let { stringResource(R.string.mine_power_bank_worth, String.format(java.util.Locale.ROOT, "%.1f", it.first), String.format(java.util.Locale.ROOT, "%.1f", it.second)) } ?: stringResource(R.string.mine_power_bank_help), onClick = onBank)
        if (hookup != null) OffRow("power.hookup", 0xFF2F6B3A, stringResource(R.string.mine_power_hookup), listOfNotNull(hookup.name.ifBlank { null }, GoFormat.distance(hookup.distanceM, units).toString()).joinToString(" · "))
    }
}

/** A row of the card: the hairline, 9 px, the 36 px tile (radius 10, a 19 px symbol), 12 px on the name (14, 800) and its line (11). */
@Composable
private fun OffRow(symbol: String, colour: Long, title: String, sub: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop(0xFFEEEAE0)).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(top = 10.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).bbox(colour, cr(10f)), contentAlignment = Alignment.Center) { MSy(symbol, 19f, WHITE) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BText(title, bt(14f, 800, 18f), INK)
            BText(sub, bt(11f, 400, 14f), SOFT)
        }
    }
}

/** The yield curve of the board (230 × 74): a filled hill in amber, the day's watts from the sun's height and the cloud, and the time now dashed. */
@Composable
private fun SolarChart(curve: DoubleArray, nowMinute: Int, modifier: Modifier) {
    val cd = stringResource(R.string.mine_power_solar_cd, PowerModel.wattHours(curve).roundToInt())
    Canvas(modifier.semantics { contentDescription = cd }) {
        val k = size.width / 230f
        val top = curve.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        fun x(i: Int) = i.toFloat() / (curve.size - 1) * 230f * k
        fun y(w: Double) = ((70.0 - w / top * 55.0).toFloat() + 4f) * k
        val line = Path()
        val area = Path()
        area.moveTo(0f, y(0.0))
        for (i in curve.indices) { val p = Offset(x(i), y(curve[i])); if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y); area.lineTo(p.x, p.y) }
        area.lineTo(230f * k, y(0.0)); area.close()
        drawPath(area, Color(0x8CFFD24A))
        drawPath(line, Color(0xFFE8922A), style = Stroke(2.4f * k, join = StrokeJoin.Round))
        val nx = nowMinute / 1440f * 230f * k
        // the time now: the board's dashed ink over the curve's 70 units (the viewBox starts 4 above)
        drawLine(Color(0xFF14161B), Offset(nx, 4f * k), Offset(nx, 74f * k), 1.5f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * k, 3f * k)))
    }
}
