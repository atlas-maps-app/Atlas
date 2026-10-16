package app.atlas.ui.mine.charge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.LocalServices
import app.atlas.services.Units
import app.atlas.ui.go.GoFormat
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.mine.art.MineArtImage
import app.atlas.ui.mine.mt
import kotlin.math.roundToInt

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF464C5A)
private val Bolt = Color(0xFF7EE0A4)

/**
 * The top of the Charge board: the back button, and a white pill with the car's battery as the board draws it (a pale case
 * round a dark cell pack, lit to the charge, four dividers), the charge and range, and the plugs the car takes. Below it
 * the board's three chips: the least power wanted, "free now only", and the way to plan a trip with stops. Pressing the
 * pill opens the car.
 */
@Composable
internal fun CarTop(
    car: Car, minKw: Int, availableOnly: Boolean,
    onBack: () -> Unit, onCar: () -> Unit, onMinKw: () -> Unit, onAvailable: () -> Unit, onTrip: () -> Unit,
) {
    val units by LocalServices.current.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val shape = RoundedCornerShape(26.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val back = stringResource(R.string.kit_back)
            Box(
                Modifier.size(52.dp, 64.dp).cssShadow(shape, 6.dp, 20.dp, Color(0x1F181C30)).clip(shape).background(Color(0xE6FFFFFF)).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) { Symbol("ui.back", tint = Ink, size = 22.dp) }
            val range = car.rangeKm?.let { GoFormat.distance(it * 1000.0, units).toString() }
            val description = if (car.model == null) stringResource(R.string.mine_car_pick)
            else stringResource(R.string.mine_car_gauge_cd, (car.soc * 100).roundToInt(), range ?: "")
            Row(
                Modifier.weight(1f).height(64.dp).cssShadow(shape, 6.dp, 20.dp, Color(0x1F181C30)).clip(shape).background(Color(0xEBFFFFFF)).clickable(role = Role.Button, onClick = onCar)
                    .padding(start = 14.dp, end = 10.dp).semantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                CarBattery(if (car.model != null) car.soc.toFloat() else Float.NaN, Modifier.size(92.dp, 40.dp))
                if (car.model != null) Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("${(car.soc * 100).roundToInt()}%", style = mt(20f, 700, tabular = true, line = 22f), color = Ink)
                    if (range != null) Text(range, style = mt(12f, 400, tabular = true), color = Soft)
                } else Text(stringResource(R.string.mine_car_pick), style = mt(14f, 700), color = Ink, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (car.model != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in ChargeModel.PLUGS.filter { it.id in ChargeModel.plugIdsOf(car.ports) }.take(3)) {
                        Box(Modifier.size(34.dp, 40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1EEE8)), contentAlignment = Alignment.Center) {
                            MineArtImage(p.art, 24.dp, tint = Color(0xFF2B2F38))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val power = if (minKw == 0) stringResource(R.string.mine_charge_any_power) else stringResource(R.string.mine_charge_min_power, minKw)
            Row(
                Modifier.height(38.dp).clip(CircleShape).background(if (minKw > 0) Ink else Color(0xE6FFFFFF)).clickable(role = Role.Button, onClick = onMinKw).padding(start = 9.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ui.charge", tint = if (minKw > 0) Bolt else Ink, size = 15.dp)
                Text(power, style = mt(14f, 600, tabular = true), color = if (minKw > 0) Color.White else Ink, maxLines = 1)
            }
            val free = stringResource(R.string.mine_charge_available_only)
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (availableOnly) Ink else Color(0xE6FFFFFF)).clickable(role = Role.Switch, onClick = onAvailable).semantics { contentDescription = free },
                contentAlignment = Alignment.Center,
            ) {
                val dot = if (availableOnly) Bolt else Color(0xFF8A8F98)
                Canvas(Modifier.size(18.dp)) {
                    if (availableOnly) drawCircle(dot.copy(alpha = 0.25f), 9.dp.toPx())
                    drawCircle(dot, 5.dp.toPx())
                }
            }
            val trip = stringResource(R.string.mine_charge_trip)
            Row(
                Modifier.height(38.dp).cssShadow(CircleShape, 4.dp, 14.dp, Color(0x1A181C30)).clip(CircleShape).background(Color(0xE6FFFFFF)).clickable(role = Role.Button, onClick = onTrip).padding(start = 10.dp, end = 12.dp).semantics { contentDescription = trip },
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ui.along-route", tint = Ink, size = 16.dp)
                Symbol("ui.charge", tint = Ink, size = 14.dp)
            }
        }
    }
}

/**
 * The battery of the Charge board (92 × 40): a pale case with a rim and a terminal, the dark cell pack inside, juice in
 * the board's green lit to [soc] (amber below 30 %, red below 15 %), four dividers and a soft gloss. With no charge known
 * ([soc] is not a number) the pack is dark.
 */
@Composable
internal fun CarBattery(soc: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = size.width / 92f
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun s(w: Float, h: Float) = Size(w * k, h * k)
        val level = if (soc.isNaN()) 0f else soc.coerceIn(0f, 1f)
        val juice = when {
            level >= 0.30f -> listOf(Color(0xFF7EE0A4), Color(0xFF22A85C), Color(0xFF16804A))
            level >= 0.15f -> listOf(Color(0xFFFFD27A), Color(0xFFF2A516), Color(0xFFB87408))
            else -> listOf(Color(0xFFFF9A8F), Color(0xFFE5483F), Color(0xFF9E2A23))
        }
        // The case: #FAFAF8 → #D9D6CF → #B9B5AC down the 4..36 band, a 1-unit rim in #8E8A80.
        drawRoundRect(Brush.verticalGradient(0f to Color(0xFFFAFAF8), 0.5f to Color(0xFFD9D6CF), 1f to Color(0xFFB9B5AC), startY = 4f * k, endY = 36f * k), o(1f, 4f), s(82f, 32f), CornerRadius(7f * k))
        drawRoundRect(Color(0xFF8E8A80), o(1f, 4f), s(82f, 32f), CornerRadius(7f * k), style = Stroke(1f * k))
        drawRoundRect(Color(0xFF9E9A90), o(84f, 13f), s(6f, 14f), CornerRadius(2f * k))
        drawRoundRect(Color(0xFF2A2D33), o(5f, 8f), s(74f, 24f), CornerRadius(4f * k))
        if (level > 0f) drawRoundRect(Brush.verticalGradient(0f to juice[0], 0.5f to juice[1], 1f to juice[2], startY = 8f * k, endY = 32f * k), o(5f, 8f), s(74f * level, 24f), CornerRadius(4f * k))
        for (x in listOf(19.5f, 34f, 48.5f, 63f)) drawLine(Color(0xFF1A1C20), o(x, 8f), o(x, 32f), 1.2f * k)
        drawRoundRect(Color.White.copy(alpha = 0.18f), o(7f, 10f), s(70f, 5f), CornerRadius(2.5f * k))
    }
}
