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
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.walkMinutes
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

private val PInk = Color(0xFF14161B)
private val PSoft = Color(0xFF5A5F6C)

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
        title = stringResource(R.string.mine_power_title), onBack = { nav.back() }, background = MineTone.of(0xFFF1EFEA, 0xFF101114), ink = PInk, titleSize = 34f,
        trailing = {
            val saver = battery?.saver == true
            Box(
                Modifier.height(34.dp).clip(CircleShape).background(Color(0xFFFFF1D6)).clickable { runCatching { context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(if (saver) R.string.mine_power_saver_on else R.string.mine_power_saver), style = mt(12f, 800), color = Color(0xFF8A5A00)) }
        },
    ) {
        BatteryCard(battery)
        SectionLabel(stringResource(R.string.mine_power_fastest), Modifier.padding(start = 2.dp), Color(0xFF676D7C))
        val list = spots
        when {
            list == null -> Text(stringResource(R.string.kit_loading), style = mt(13f, 500), color = PSoft)
            list.isEmpty() -> StateMessage("power.bank", stringResource(R.string.mine_power_none), text = stringResource(R.string.mine_power_none_text), tint = PSoft)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { for (s in list.take(5)) SpotRow(s, units, onClick = { services.remember(s.place); nav.go(Dest.Place.of(s.place)) }) }
        }
        OffGrid(curve, wh, clouds?.let { ZonedLocal.hm(it, zone, twelve) }, today, zone, bank, battery, hookup, units, onBank = { bankDialog = true })
        Text(stringResource(R.string.mine_power_footer), style = mt(10f, 400, line = 15f), color = Color(0xFF7A7F8C), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (bankDialog) {
        var text by remember { mutableStateOf(bank?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { bankDialog = false },
            title = { Text(stringResource(R.string.mine_power_bank_title)) },
            text = {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Atlas.colors.cardMuted).padding(12.dp)) {
                    val fieldName = stringResource(R.string.mine_power_bank_title)
                    if (text.isEmpty()) Text("20000", style = mt(15f, 500), color = Atlas.colors.inkFaint)
                    BasicTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, textStyle = mt(15f, 600).copy(color = Atlas.colors.ink), cursorBrush = SolidColor(Atlas.colors.ink), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldName })
                }
            },
            confirmButton = { TextButton({ bankDialog = false; scope.launch { services.settings.set(MineKeys.POWER_BANK_MAH, text.toIntOrNull()?.takeIf { it in 500..100_000 }) } }) { Text(stringResource(R.string.mine_data_save)) } },
            dismissButton = { TextButton({ bankDialog = false }) { Text(stringResource(R.string.mine_offline_keep)) } },
        )
    }
}

/** The dark card: a battery glyph with its sliver of charge (red when low) and the percentage, with the hours it lasts. */
@Composable
private fun BatteryCard(b: PhoneBattery?) {
    val level = b?.percent ?: 0
    val colour = when { level <= 15 -> Color(0xFFFF5A3C); level <= 40 -> Color(0xFFF2A71B); else -> Color(0xFF3FCF8E) }
    val cd = if (b == null) stringResource(R.string.mine_power_battery_none) else stringResource(R.string.mine_power_battery_cd, level)
    Row(
        Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(22.dp)).background(PInk).padding(horizontal = 16.dp).semantics { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(100.dp, 44.dp)) {
            val w = 88.dp.toPx()
            drawRoundRect(Color.White, Offset.Zero, Size(w, 44.dp.toPx()), CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
            drawRoundRect(Color.White, Offset(w + 1.dp.toPx(), 12.dp.toPx()), Size(6.dp.toPx(), 14.dp.toPx()), CornerRadius(3.dp.toPx()))
            val inner = w - 14.dp.toPx()
            drawRoundRect(colour, Offset(7.dp.toPx(), 7.dp.toPx()), Size((inner * level / 100f).coerceAtLeast(4.dp.toPx()), 30.dp.toPx()), CornerRadius(4.dp.toPx()))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (b == null) "–" else "$level%", style = mt(28f, 900, tabular = true, line = 28f), color = Color.White)
            val line = when {
                b == null -> stringResource(R.string.mine_power_battery_none)
                b.charging -> stringResource(R.string.mine_power_charging)
                b.hoursLeft != null -> stringResource(R.string.mine_power_left, durationText(b.hoursLeft))
                else -> stringResource(R.string.mine_power_left_unknown)
            }
            Text(line, style = mt(12f, 400, tabular = true), color = Color(0xFFC9CED8))
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
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(18.dp)).background(Atlas.colors.card).clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(s.kind.colour)), contentAlignment = Alignment.Center) { Symbol(s.kind.symbol, tint = Color.White, size = 21.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name.ifBlank { stringResource(kindName(s.kind)) }, style = mt(15f, 800), color = PInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail.ifBlank { stringResource(kindName(s.kind)) }, style = mt(12f, 400), color = Color(0xFF5A5F6C), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (s.distanceM < 150) GoFormat.distance(s.distanceM, units).toString() else walkMinutes(app.atlas.ui.kit.WalkTime.seconds(s.distanceM)), style = mt(15f, 900, tabular = true), color = PInk)
        }
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

/** "Off the grid": today's solar curve, the bank and a hook-up. */
@Composable
private fun OffGrid(curve: DoubleArray, wh: Double, clouds: String?, today: java.time.LocalDate, zone: java.time.ZoneId, bank: Int?, battery: PhoneBattery?, hookup: PowerSpot?, units: Units, onBank: () -> Unit) {
    val c = Atlas.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp)) {
        SectionLabel(stringResource(R.string.mine_power_off_grid), colour = Color(0xFF676D7C))
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SolarChart(curve, java.time.LocalTime.now(java.time.Clock.system(zone)).let { it.hour * 60 + it.minute }, Modifier.size(230.dp, 74.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("≈ ${wh.roundToInt()} Wh", style = mt(18f, 900, tabular = true), color = PInk)
                Text(listOfNotNull(stringResource(R.string.mine_power_panel), clouds?.let { stringResource(R.string.mine_power_clouds, it) }).joinToString("\n"), style = mt(11f, 400, line = 14f), color = Color(0xFF5A5F6C))
            }
        }
        val worth = bank?.let { PowerModel.bankWorth(it, battery?.capacityMah?.let { mAh -> mAh * 3.85 / 1000.0 } ?: 17.0) }
        OffRow("power.bank", Color(0xFF7B4DFF), if (bank != null) stringResource(R.string.mine_power_bank, java.text.NumberFormat.getIntegerInstance().format(bank)) else stringResource(R.string.mine_power_bank_set), worth?.let { stringResource(R.string.mine_power_bank_worth, String.format(java.util.Locale.ROOT, "%.1f", it.first), String.format(java.util.Locale.ROOT, "%.1f", it.second)) } ?: stringResource(R.string.mine_power_bank_help), first = true, onClick = onBank)
        if (hookup != null) OffRow("power.hookup", Color(0xFF2F6B3A), stringResource(R.string.mine_power_hookup), listOfNotNull(hookup.name.ifBlank { null }, GoFormat.distance(hookup.distanceM, units).toString()).joinToString(" · "))
    }
}

@Composable
private fun OffRow(symbol: String, colour: Color, title: String, sub: String, first: Boolean = false, onClick: (() -> Unit)? = null) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Atlas.colors.line))
        Row(Modifier.fillMaxWidth().height(54.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colour), contentAlignment = Alignment.Center) { Symbol(symbol, tint = Color.White, size = 19.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = mt(14f, 800), color = PInk, maxLines = 1)
                Text(sub, style = mt(11f, 400), color = Color(0xFF5A5F6C), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
        drawLine(Color(0xFF14161B), Offset(nx, 0f), Offset(nx, size.height), 1.5f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * k, 3f * k)))
    }
}
