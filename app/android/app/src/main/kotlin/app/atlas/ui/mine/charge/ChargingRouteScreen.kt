package app.atlas.ui.mine.charge

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.RouteRequest
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.services.Units
import app.atlas.ui.go.GoFormat
import app.atlas.nav.Navigator
import app.atlas.route.RouteResult
import app.atlas.ui.go.SavedPlace
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.mine.BoardCard
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.MiniButton
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.PlacePicker
import app.atlas.ui.mine.SectionLabel
import app.atlas.ui.mine.mt
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A route with charging stops: the trip from here to a place for your car, with the stops the planner chose (power,
 * arrival and departure charge, minutes), what the charging adds to the trip, and the charge along the way. It plans through
 * the app's router with your car's battery (chargers from OpenStreetMap and, with the data source on, Open Charge Map).
 */
@Composable
fun ChargingRouteScreen(args: Dest.ChargingRoute, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val store = remember { CarStore(services.settings, services.go.store) }
    val car by store.car.collectAsStateWithLifecycle(Car(null, Car.DEFAULT_SOC, emptySet()))
    var target by remember { mutableStateOf(if (args.toLat != null && args.toLon != null) SavedPlace(args.toName.orEmpty(), LatLon(args.toLat, args.toLon)) else null) }
    var asking by remember { mutableStateOf(target == null) }
    var carDialog by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<TripState>(TripState.Waiting) }
    LaunchedEffect(target, car.model?.id, car.soc) {
        val to = target ?: return@LaunchedEffect
        val model = car.model
        if (model == null) { state = TripState.NoCar; return@LaunchedEffect }
        state = TripState.Planning
        val req = RouteRequest(Mode.CAR, listOf(here, to.at), vehicle = app.atlas.model.Vehicle(ev = model.toEvSpec(socNow = car.soc)))
        state = when (val r = services.go.plan(req)) {
            is RouteResult.Found -> TripState.Ready(ChargingTrip.of(r.best))
            is RouteResult.NoData -> TripState.NoData(r.missingTiles)
            is RouteResult.Failure -> TripState.Failed(r.message)
            else -> TripState.Failed("")
        }
    }
    ChargingRouteView(target, car, state, nav, onPickPlace = { asking = true }, onPickCar = { carDialog = true })
    if (asking) PlacePicker(stringResource(R.string.mine_route_choose), target, onPick = { target = it; asking = false }, onClose = { asking = false })
    if (carDialog) CarDialog(store, car, onClose = { carDialog = false })
}

/** The screen's page: where to, the car, and what the plan says. Stateless, so a test can give it any trip. */
@Composable
internal fun ChargingRouteView(target: SavedPlace?, car: Car, state: TripState, nav: Navigator, onPickPlace: () -> Unit, onPickCar: () -> Unit) {
    val units by LocalServices.current.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    BoardPage(stringResource(R.string.mine_charging_route), onBack = { nav.back() }, background = MineTone.of(0xFFF1F3F6, 0xFF101114)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Atlas.colors.card).clickable(onClick = onPickPlace).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("ui.pin", tint = MineTone.blue, size = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mine_route_to), style = mt(11f, 800, spacing = 1.2f), color = Atlas.colors.inkSoft)
                Text(target?.name?.ifBlank { null } ?: target?.let { String.format(Locale.ROOT, "%.4f, %.4f", it.at.lat, it.at.lon) } ?: stringResource(R.string.mine_route_choose), style = mt(16f, 800), color = Atlas.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Symbol("ui.edit", tint = Atlas.colors.inkFaint, size = 18.dp)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Atlas.colors.card).clickable(onClick = onPickCar).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("drop.battery", tint = Atlas.colors.ink, size = 24.dp)
            Column(Modifier.weight(1f)) {
                Text(car.name ?: stringResource(R.string.mine_car_pick), style = mt(15f, 800), color = Atlas.colors.ink)
                if (car.model != null) Text(stringResource(R.string.mine_route_battery, (car.soc * 100).roundToInt(), GoFormat.distance((car.rangeKm ?: 0.0) * 1000.0, units).toString()), style = mt(12f, 400), color = Atlas.colors.inkSoft)
            }
            Symbol("ui.chevron-right", tint = Atlas.colors.inkFaint, size = 16.dp)
        }
        TripBody(state, car, target, onGo = { t -> nav.go(Dest.Navigate(t.at.lat, t.at.lon, t.name.ifBlank { null }, "CAR")) }, onOffline = { nav.go(Dest.Offline) }, onCar = onPickCar)
        Text(stringResource(R.string.mine_route_footer), style = mt(10.5f, 400, line = 15f), color = Atlas.colors.inkFaint, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

internal sealed interface TripState {
    data object Waiting : TripState
    data object Planning : TripState
    data object NoCar : TripState
    data class NoData(val tiles: List<String>) : TripState
    data class Failed(val message: String) : TripState
    data class Ready(val trip: ChargingTrip) : TripState
}

/** What the trip shows: waiting for a place, planning, no car, no routes, a failure, or the plan. */
@Composable
internal fun TripBody(state: TripState, car: Car, target: SavedPlace?, onGo: (SavedPlace) -> Unit, onOffline: () -> Unit, onCar: () -> Unit) {
    when (state) {
        TripState.Waiting -> StateMessage("ui.along-route", stringResource(R.string.mine_route_waiting), text = stringResource(R.string.mine_charging_route_text), tint = Atlas.colors.inkSoft)
        TripState.Planning -> StateMessage("ui.refresh", stringResource(R.string.mine_route_planning), tint = Atlas.colors.inkSoft)
        TripState.NoCar -> StateMessage("drop.battery", stringResource(R.string.mine_route_nocar), text = stringResource(R.string.mine_route_nocar_text), tint = MineTone.amber, action = { MiniButton(stringResource(R.string.mine_car_pick), onCar) })
        is TripState.NoData -> StateMessage("ui.no-signal", stringResource(R.string.mine_route_nodata), text = stringResource(R.string.mine_route_nodata_text), tint = MineTone.amber, action = { MiniButton(stringResource(R.string.mine_offline_title), onOffline) })
        is TripState.Failed -> StateMessage("ui.warning", stringResource(R.string.mine_route_failed), text = state.message.ifBlank { null }, tint = MineTone.red)
        is TripState.Ready -> TripPlan(state.trip, car, target, onGo)
    }
}

@Composable
private fun TripPlan(trip: ChargingTrip, car: Car, target: SavedPlace?, onGo: (SavedPlace) -> Unit) {
    val c = Atlas.colors
    val total = (trip.route.duration / 60).roundToInt()
    val units by LocalServices.current.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val distance = GoFormat.distance(trip.route.distance, units)
    BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
            Big(hm(total), stringResource(R.string.mine_route_total))
            Big(distance.value, distance.unit)
            Big("${trip.stops.size}", stringResource(R.string.mine_route_stops))
            if (trip.stops.isNotEmpty()) Big("${trip.chargeMinutes.roundToInt()}′", stringResource(R.string.mine_route_charging))
        }
        SocChart(trip, car, Modifier.fillMaxWidth().padding(top = 12.dp).height(90.dp))
    }
    if (trip.stops.isEmpty()) Text(stringResource(R.string.mine_route_no_stops), style = mt(13f, 600, line = 18f), color = MineTone.greenDeep, modifier = Modifier.padding(horizontal = 4.dp))
    else {
        SectionLabel(stringResource(R.string.mine_route_stops_title), colour = MineTone.steel)
        for (s in trip.stops) StopRow(s)
    }
    target?.let {
        Row(Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(MineTone.blue).clickable { onGo(it) }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Symbol("tab.go", tint = Color.White, size = 22.dp)
            Text(stringResource(R.string.mine_route_start), style = mt(16f, 800), color = Color.White)
        }
    }
}

private fun hm(minutes: Int) = if (minutes < 60) "$minutes′" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

@Composable
private fun Big(value: String, label: String) {
    Column {
        Text(value, style = mt(24f, 900, tabular = true, line = 26f), color = Atlas.colors.ink)
        Text(label, style = mt(11f, 700), color = Atlas.colors.inkSoft)
    }
}

/** The charge along the trip: the line falls with the driving and jumps at each stop; the floor is what the planner keeps in hand. */
@Composable
private fun SocChart(trip: ChargingTrip, car: Car, modifier: Modifier) {
    val cd = stringResource(R.string.mine_route_chart_cd, trip.stops.size)
    val line = Atlas.colors.go
    Canvas(modifier.semantics { contentDescription = cd }) {
        val total = trip.route.distance.coerceAtLeast(1.0)
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(s: Double) = (size.height * (1.0 - s.coerceIn(0.0, 1.0))).toFloat()
        val p = Path()
        var soc = car.soc
        var at = 0.0
        p.moveTo(0f, y(soc))
        for (s in trip.stops) {
            val arrive = s.arriveSoc ?: soc
            p.lineTo(x(s.along), y(arrive))
            p.lineTo(x(s.along), y(s.departSoc ?: arrive))
            soc = s.departSoc ?: arrive
            at = s.along
        }
        val endSoc = (soc - (total - at) / 1000.0 * (car.model?.consumption ?: 18.0) / 100.0 / (car.model?.batteryKwh ?: 60.0)).coerceAtLeast(0.0)
        p.lineTo(size.width, y(endSoc))
        drawLine(Color(0x33C8322A), Offset(0f, y(0.1)), Offset(size.width, y(0.1)), 1.dp.toPx())
        drawPath(p, line, style = Stroke(2.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        for (s in trip.stops) drawCircle(Color(0xFF22A85C), 5.dp.toPx(), Offset(x(s.along), y(s.departSoc ?: s.arriveSoc ?: soc)))
    }
}

@Composable
private fun StopRow(s: StopView) {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.size(44.dp).clip(CircleShape).background(Color(PowerBand.of(s.powerKw).colour)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(s.powerKw?.let { "${it.roundToInt()}" } ?: "?", style = mt(13f, 900, tabular = true, line = 13f), color = Color.White)
            Text("kW", style = mt(9f, 700, line = 9f), color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name.ifBlank { stringResource(R.string.mine_charge_site) }, style = mt(15f, 800), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    s.arriveSoc?.let { "${(it * 100).roundToInt()}%" }, s.departSoc?.let { "→ ${(it * 100).roundToInt()}%" },
                ).joinToString(" "), style = mt(12f, 500, tabular = true), color = c.inkSoft,
            )
        }
        s.chargeMinutes?.let { Text("${it.roundToInt()}′", style = mt(16f, 900, tabular = true), color = c.ink) }
    }
}
