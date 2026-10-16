package app.atlas.ui.mine.charge

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Sockets
import app.atlas.ui.kit.Atlas
import app.atlas.places.Brands
import app.atlas.services.Units
import app.atlas.ui.go.GoFormat
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.search.BrandLogo
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.softShadow
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.art.MineArtImage
import app.atlas.ui.mine.mt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFF14161B)
private val Green = Color(0xFF22A85C)
private val Amber = Color(0xFFF2A71B)

/**
 * Chargers around you for your car, on the map with the Plugs board's chips and its sheet: your plugs and the least power
 * up top, the sites coloured by power (blue AC, green 50, orange 150, purple 300+; grey when nothing is free), the sheet
 * with the count of sites, the free points when a live source says, the cheapest price, the mix of powers and the nearest
 * cards. Tap a site for the Charge board's card: its plugs with kW and free points, the time to charge your car from
 * where it is to 80 %, what is near while you wait, and the way there. Chargemap has no public API: a link opens its own map.
 */
@Composable
fun ChargeScreen(args: Dest.Charge, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val here = rememberHere()
    val centre = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val fix by services.location.last.collectAsStateWithLifecycle()
    val store = remember { CarStore(services.settings, services.go.store) }
    val car by store.car.collectAsStateWithLifecycle(Car(null, Car.DEFAULT_SOC, emptySet()))
    val repo = remember { ChargeRepository(services) }
    var result by remember(centre) { mutableStateOf<ChargeResult?>(null) }
    LaunchedEffect(centre) { result = repo.load(centre) }
    val lang = remember { Locale.getDefault().language }
    val all = remember(result, centre) { result?.places.orEmpty().map { ChargeModel.of(it, centre, lang) }.sortedBy { it.distanceM ?: Double.MAX_VALUE } }
    var plugs by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var plugsTouched by rememberSaveable { mutableStateOf(false) }
    var minStep by rememberSaveable { mutableIntStateOf(0) }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(args.select) }
    var carDialog by remember { mutableStateOf(false) }
    // The car's own ports are the plugs until the person chooses others.
    val activePlugs = if (plugsTouched) plugs else ChargeModel.plugIdsOf(car.ports)
    val ports = ChargeModel.portsOf(activePlugs)
    val shown = remember(all, ports, minStep, availableOnly) { ChargeModel.filter(all, ports, ChargeModel.MIN_KW_STEPS[minStep], availableOnly) }
    // "nearest" (the CI's way to open a card) means the closest station that passes the filters.
    val pick = if (selected == "nearest") shown.firstOrNull() else shown.firstOrNull { it.key == selected } ?: all.firstOrNull { it.key == selected }
    val map = remember(centre) { MapState(CameraPosition(centre, 15.6, 0.0, 40.0)) }
    LaunchedEffect(pick?.key) { pick?.let { map.animateTo(CameraPosition(it.place.location, 16.6, 0.0, 40.0), 500) } }
    val markers = remember(shown, pick) {
        shown.take(60).mapIndexed { i, c ->
            Marker(
                id = c.key, at = c.place.location, symbol = "charging_station",
                colour = if (c.busy) PowerBand.UNKNOWN.colour else c.band.colour, style = MarkerStyle.CHIP,
                value = c.kw?.let { "${it.roundToInt()}" }, ring = if (c.live && (c.points ?: 0) > 0) (c.free ?: 0).toFloat() / c.points!! else null,
                selected = c.key == pick?.key, priority = 100 - i + if (c.key == pick?.key) 1000 else 0, place = c.place,
            )
        }
    }
    Box(Modifier.fillMaxSize().background(Atlas.colors.paper)) {
        AtlasMap(
            state = map, tiles = tiles, modifier = Modifier.fillMaxSize(),
            overlays = MapOverlays(markers = markers, location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) }),
            contentPadding = PaddingValues(top = 200.dp, bottom = 380.dp),
            onTap = { hit -> if (hit is MapHit.OnMarker) selected = hit.marker.id else if (hit is MapHit.Empty) selected = null },
        )
        Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Color(0xE6F4F1EA), Color(0x00F4F1EA)))))
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pick == null) {
                TopBar(onBack = { nav.back() }, onFuel = { nav.go(Dest.Pump(centre.lat, centre.lon)) }, onPark = { nav.go(Dest.Category("parking")) }, onBike = { nav.go(Dest.Twowheel()) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in ChargeModel.PLUGS) PlugTile(p, p.id in activePlugs, Modifier.weight(1f)) { plugsTouched = true; plugs = if (p.id in activePlugs) activePlugs - p.id else activePlugs + p.id }
                    MinPowerTile(ChargeModel.MIN_KW_STEPS[minStep], Modifier.weight(1f)) { minStep = (minStep + 1) % ChargeModel.MIN_KW_STEPS.size }
                }
            } else {
                // A site is open: the Charge board's top, with the car's battery and the three chips.
                CarTop(
                    car, ChargeModel.MIN_KW_STEPS[minStep], availableOnly,
                    onBack = { nav.back() }, onCar = { carDialog = true }, onMinKw = { minStep = (minStep + 1) % ChargeModel.MIN_KW_STEPS.size },
                    onAvailable = { availableOnly = !availableOnly }, onTrip = { nav.go(Dest.ChargingRoute()) },
                )
            }
        }
        val sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 400.dp).softShadow(sheet, Color(0x29141E3A), 18.dp).clip(sheet).background(Color(0xFFFBFAF7))
                .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(Color(0xFFD8D4CC)))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pick != null) CardSheet(pick, car, centre, onClose = { selected = null }, onGo = { c ->
                    nav.go(Dest.Route(c.place.location.lat, c.place.location.lon, c.name.ifBlank { c.operator ?: "" }, centre.lat, centre.lon, null, "CAR"))
                }, onCar = { carDialog = true }, onMore = {
                    val url = "https://chargemap.com/map?lat=${"%.5f".format(Locale.ROOT, pick.place.location.lat)}&lng=${"%.5f".format(Locale.ROOT, pick.place.location.lon)}&zoom=16"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                else Overview(shown, result, availableOnly, onAvailable = { availableOnly = !availableOnly }, car = car, onCar = { carDialog = true }, onPick = { selected = it.key },
                    onDownload = { nav.go(Dest.RegionPicker(centre.lat, centre.lon)) })
            }
            if (pick == null) Row(
                Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Ink).clickable { nav.go(Dest.ChargingRoute()) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ui.along-route", tint = Color.White, size = 18.dp)
                Text(stringResource(R.string.mine_charge_trip), style = mt(14f, 800), color = Color.White)
            }
        }
    }
    if (carDialog) CarDialog(store, car, onClose = { carDialog = false })
}

/** The back button and the four ways to move: fuel, charge (here), park, bike. */
@Composable
private fun TopBar(onBack: () -> Unit, onFuel: () -> Unit, onPark: () -> Unit, onBike: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val back = stringResource(R.string.kit_back)
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xF0FFFFFF)).clickable(onClick = onBack).semantics { contentDescription = back }, contentAlignment = Alignment.Center) { Symbol("ui.back", tint = Ink, size = 20.dp) }
        Row(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(Color(0xF0FFFFFF)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((symbol, label, on, click) in listOf(
                Quad("place.fuel", R.string.mine_move_fuel, false, onFuel), Quad("ui.charge", R.string.mine_move_charge, true, {}),
                Quad("park.parking", R.string.mine_move_park, false, onPark), Quad("mode.bike", R.string.mine_move_bike, false, onBike),
            )) {
                Row(
                    Modifier.weight(1f).height(36.dp).clip(CircleShape).background(if (on) Ink else Color.Transparent).clickable(onClick = click),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(symbol, tint = if (on) Color.White else Ink, size = 16.dp)
                    Text(stringResource(label), style = mt(12f, 800), color = if (on) Color.White else Ink, maxLines = 1)
                }
            }
        }
    }
}

private data class Quad(val symbol: String, val label: Int, val on: Boolean, val click: () -> Unit)

/** A connector tile: the plug's face drawn as the board draws it, over its name; dark when it is one of the plugs wanted. */
@Composable
private fun PlugTile(p: ChargeModel.Plug, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.height(66.dp).cssShadow(shape, 4.dp, 12.dp, Color(0x24141E3A)).clip(shape).background(if (on) Ink else Color(0xF0FFFFFF)).clickable(onClick = onClick).semantics { contentDescription = p.label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        MineArtImage(p.art, 30.dp, tint = if (on) Color.White else Ink)
        Text(p.label, style = mt(11f, 800), color = if (on) Color.White else Ink, maxLines = 1)
    }
}

/** The "≥ 50 kW" tile: tap to raise the least power wanted. */
@Composable
private fun MinPowerTile(kw: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.height(66.dp).cssShadow(shape, 4.dp, 12.dp, Color(0x24141E3A)).clip(shape).background(if (kw > 0) Ink else Color(0xF0FFFFFF)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(if (kw == 0) "kW" else "≥ $kw", style = mt(15f, 900, tabular = true), color = if (kw > 0) Color.White else Ink)
        Text(if (kw == 0) stringResource(R.string.mine_charge_any) else "kW", style = mt(11f, 800), color = if (kw > 0) Color.White else Ink)
    }
}

/** The Plugs sheet: sites, free now and the cheapest price, the power mix, the nearest sites, and "Plan a long trip with stops". */
@Composable
private fun Overview(shown: List<Charger>, result: ChargeResult?, availableOnly: Boolean, onAvailable: () -> Unit, car: Car, onCar: () -> Unit, onPick: (Charger) -> Unit, onDownload: () -> Unit) {
    val sum = ChargeModel.summary(shown)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Stat("${sum.sites}", stringResource(R.string.mine_charge_sites), Ink)
        if (sum.free != null) Stat("${sum.free}", stringResource(R.string.mine_charge_free_now), Color(0xFF12663A))
        if (sum.cheapest != null) Stat(String.format(Locale.ROOT, "%.2f", sum.cheapest), (if (sum.currency == "EUR") "€" else sum.currency ?: "") + "/kWh " + stringResource(R.string.mine_charge_min), Ink)
        Spacer(Modifier.weight(1f))
        CarPill(car, onCar)
    }
    if (shown.isEmpty()) {
        val offline = result?.online == false
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Symbol(if (result == null) "ui.refresh" else "ui.charge", tint = Color(0xFF8A8F98), size = 36.dp)
            Text(stringResource(if (result == null) R.string.kit_loading else R.string.mine_charge_none), style = mt(15f, 800), color = Ink)
            if (result != null) Text(stringResource(if (offline) R.string.mine_charge_none_offline else R.string.mine_charge_none_text), style = mt(12f, 400, line = 16f), color = Color(0xFF676D7C), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (offline) Row(Modifier.height(40.dp).clip(CircleShape).background(Ink).clickable(onClick = onDownload).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Symbol("ui.download", tint = Color.White, size = 16.dp)
                Text(stringResource(R.string.mine_charge_download), style = mt(13f, 800), color = Color.White)
            }
        }
    } else {
        PowerMix(shown)
        for ((i, c) in shown.take(2).withIndex()) SiteCard(c, car, highlight = i == 0, onClick = { onPick(c) })
        if (shown.any { it.live }) Row(Modifier.clickable(onClick = onAvailable), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (availableOnly) Green else Color(0xFFD8D4CC)))
            Text(stringResource(R.string.mine_charge_available_only), style = mt(12f, 700), color = Color(0xFF464C5A))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, colour: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = mt(26f, 900, tabular = true, line = 28f), color = colour)
        Text(label, style = mt(12f, 700, line = 16f), color = Color(0xFF676D7C), modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** The car as a small pill: its charge and range, or an invitation to pick one. */
@Composable
private fun CarPill(car: Car, onClick: () -> Unit) {
    val units by LocalServices.current.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val text = if (car.model != null) "${(car.soc * 100).roundToInt()}%" + (car.rangeKm?.let { " · ${GoFormat.distance(it * 1000.0, units)}" } ?: "") else stringResource(R.string.mine_car_pick)
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(Color(0xFFF1EEE8)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("drop.battery", tint = Ink, size = 18.dp)
        Text(text, style = mt(12f, 800, tabular = true), color = Ink, maxLines = 1)
    }
}

/** The bar of the Plugs sheet: the sites by power band, widths as their numbers, with the count under each. */
@Composable
private fun PowerMix(list: List<Charger>) {
    val mix = ChargeModel.mix(list)
    val description = mix.joinToString(", ") { "${it.second} × ${it.first.label}" }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((b, n) in mix) Box(Modifier.weight(n.toFloat()).height(12.dp).background(Color(b.colour)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((b, n) in mix) Row(Modifier.weight(n.toFloat()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(b.label, style = mt(11f, 800, tabular = true), color = Color(0xFF464C5A), maxLines = 1, softWrap = false)
                Text("×$n", style = mt(11f, 800, tabular = true), color = Color(0xFF8A8F98), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A site as a card: the power as a disc, its name, what is known of it, how far and how many are free. */
@Composable
private fun SiteCard(c: Charger, car: Car, highlight: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val colour = Color(if (c.busy) PowerBand.UNKNOWN.colour else c.band.colour)
    val walkMin = c.distanceM?.let { (it * 1.3 / 1.35 / 60).roundToInt().coerceAtLeast(1) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(shape).then(if (highlight) Modifier.background(Color(0xFFFFF4E8)).border(2.dp, colour, shape) else Modifier.background(Color(0xFFF4F5F7))).clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.size(44.dp).clip(CircleShape).background(colour), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (c.kw != null) {
                Text("${c.kw.roundToInt()}", style = mt(13f, 900, tabular = true, line = 13f), color = Color.White)
                Text("kW", style = mt(9f, 700, line = 9f), color = Color.White)
            } else Symbol("ui.charge", tint = Color.White, size = 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name.ifBlank { stringResource(R.string.mine_charge_site) }, style = mt(15f, 800), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail(c), style = mt(12f, 400, tabular = true), color = Color(0xFF676D7C), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (walkMin != null) Text("$walkMin′", style = mt(15f, 900, tabular = true), color = Ink)
            if (c.live) Text(stringResource(R.string.mine_charge_free_of, c.free ?: 0, c.points ?: 0), style = mt(12f, 800, tabular = true), color = if (c.busy) Color(0xFFC8322A) else Color(0xFF12663A))
            else c.points?.let { Text(pluralStringResource(R.plurals.mine_n_points, it, it), style = mt(12f, 700, tabular = true), color = Color(0xFF676D7C)) }
        }
    }
}

@Composable
private fun detail(c: Charger): String = listOfNotNull(
    c.pricePerKwh?.let { String.format(Locale.ROOT, "%.2f %s/kWh", it, if (c.currency == "EUR") "€" else c.currency ?: "") } ?: if (c.free0) stringResource(R.string.mine_charge_free_fee) else null,
    c.operator?.takeIf { it != c.name },
    c.types.firstOrNull()?.let { plugName(it) },
    c.place.tags["ref"],
    if (c.pricePerKwh == null && c.sockets.isEmpty()) c.points?.let { pluralStringResource(R.plurals.mine_n_points, it, it) } else null,
).joinToString(" · ").ifEmpty { stringResource(R.string.mine_charge_no_details) }

/** A socket's name as people say it: "CCS2", "Type 2", "CHAdeMO". */
internal fun plugName(type: String): String = when (type) {
    "type2_combo" -> "CCS2"; "type2", "type2_cable" -> "Type 2"; "chademo" -> "CHAdeMO"; "schuko" -> "Schuko"; "type1" -> "Type 1"; "type1_combo" -> "CCS1"
    "tesla_supercharger", "nacs" -> "NACS"; "tesla_destination" -> "Tesla"; "cee_blue" -> "CEE blue"; "cee_red_16a", "cee_red_32a" -> "CEE red"
    else -> type.replace('_', ' ')
}

/** The Charge board's card: a station selected on the map, in the sheet. */
@Composable
private fun CardSheet(c: Charger, car: Car, here: LatLon, onClose: () -> Unit, onGo: (Charger) -> Unit, onCar: () -> Unit, onMore: () -> Unit) {
    val services = LocalServices.current
    var nearby by remember(c.key) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(c.key) {
        nearby = withContext(Dispatchers.IO) {
            buildList {
                for ((id, symbol) in listOf("cafe" to "place.cafe", "toilet" to "need.toilet")) {
                    val found = runCatching { services.places.nearby(id, c.place.location, 250.0, 1).let { f -> f.firstOrNull { it.places.isNotEmpty() || it.error != null } } }.getOrNull()
                    if (found?.places?.isNotEmpty() == true) add(symbol)
                }
            }
        }
    }
    val driveMin = c.distanceM?.let { (it * 1.4 / 8.3 / 60).roundToInt().coerceAtLeast(1) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClose)) {
        // The operator's own logo from the brand index when it knows it; else the board's tile with the operator's initial
        // (or the bolt when nobody is named).
        val brand = remember(c.place) { Brands.forTags(c.place.tags) }
        val initial = (c.operator ?: c.name).firstOrNull()?.uppercase()
        if (brand != null) BrandLogo(brand, 48.dp)
        else Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF10233F)), contentAlignment = Alignment.Center) {
            if (initial != null) Text(initial, style = androidx.compose.ui.text.TextStyle(fontFamily = SerifFamily, fontStyle = FontStyle.Italic, fontSize = 30.sp, lineHeight = 30.sp), color = Color(0xFF7EE0A4))
            else Symbol("ui.charge", tint = Color(0xFF7EE0A4), size = 26.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.name.ifBlank { c.operator ?: stringResource(R.string.mine_charge_site) }, style = mt(18f, 600), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (driveMin != null) Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol("mode.car", tint = Color(0xFF464C5A), size = 13.dp)
                    Text("$driveMin′", style = mt(13f, 500, tabular = true), color = Color(0xFF464C5A))
                }
                c.pricePerKwh?.let { Text(String.format(Locale.ROOT, "%s%.2f/kWh", if (c.currency == "EUR") "€" else "", it), style = mt(13f, 500, tabular = true), color = Color(0xFF464C5A)) }
                if (c.plugAndCharge) Symbol("ui.check", tint = Color(0xFF178A4E), size = 13.dp)
            }
        }
        if (c.live) Row(Modifier.height(26.dp).clip(CircleShape).background(Color(0xFFE7F4EC)).padding(horizontal = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Green))
            Text(stringResource(R.string.mine_charge_live), style = mt(12f, 700), color = Color(0xFF12663A))
        }
    }
    // The plugs.
    val shown = c.sockets.ifEmpty { emptyList() }
    if (shown.isEmpty()) Text(stringResource(R.string.mine_charge_no_sockets), style = mt(12f, 400, line = 16f), color = Color(0xFF676D7C))
    else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in shown.take(4).chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in row) SocketCard(s, c, Modifier.weight(1f))
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    // The charge: from where the car is to 80 %.
    val model = car.model
    val socket = model?.let { m -> c.sockets.filter { it.type in m.connectors }.maxByOrNull { it.kw ?: 0.0 } }
    val kw = socket?.kw ?: c.kw
    val minutes = if (model != null && socket != null && kw != null && kw > 0) ChargeModel.chargeMinutes(model, car.soc, 0.8, kw, socket.type in Sockets.DC) else null
    if (model != null && minutes != null && car.soc < 0.8) PlanBar(car.soc, minutes)
    else Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1EEE8)).clickable(onClick = onCar).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol("drop.battery", tint = Ink, size = 20.dp)
        Text(stringResource(if (model == null) R.string.mine_charge_pick_car else R.string.mine_charge_no_plan), style = mt(13f, 700), color = Ink)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in nearby) Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFF1EEE8)), contentAlignment = Alignment.Center) { Symbol(s, tint = Color(0xFF464C5A), size = 18.dp) }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.mine_charge_chargemap), style = mt(12f, 700), color = Color(0xFF2E5BFF), modifier = Modifier.clickable(onClick = onMore).padding(8.dp))
        Row(
            Modifier.height(52.dp).clip(CircleShape).background(Color(0xFF2E5BFF)).clickable { onGo(c) }.padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("tab.go", tint = Color.White, size = 20.dp)
            if (driveMin != null) Text("$driveMin′", style = mt(16f, 600, tabular = true), color = Color.White)
        }
    }
}

/** A plug on the card: the face as the board draws it, a dot per point (green free, amber busy, grey unknown) and the kW. */
@Composable
private fun SocketCard(s: app.atlas.providers.Socket, c: Charger, modifier: Modifier) {
    val art = when (s.type) { "type2_combo" -> "plug.ccs2.face"; "type2", "type2_cable" -> "plug.type2.face"; else -> null }
    val live = c.place.extra["status:${s.type}"]?.split('/')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
    val label = "${plugName(s.type)}${s.kw?.let { ", ${it.roundToInt()} kW" } ?: ""}"
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Color(0xFFECE8E1), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art != null) MineArtImage(art, 36.dp) else Symbol("fact.socket", tint = Ink, size = 30.dp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val total = (live?.get(1) ?: s.count).coerceIn(1, 8)
                val free = live?.get(0)
                for (i in 0 until total) Box(Modifier.size(10.dp).clip(CircleShape).background(if (free == null) Color(0xFFC9C5BC) else if (i < free) Green else Amber))
            }
            Text(s.kw?.let { "${it.roundToInt()} kW" } ?: plugName(s.type), style = mt(13f, 700, tabular = true), color = Ink)
        }
    }
}

/** "37% → 80% in 20′": the charge now in amber, what one stop adds in green stripes. */
@Composable
private fun PlanBar(soc: Double, minutes: Double) {
    val now = (soc * 100).roundToInt()
    val cd = stringResource(R.string.mine_charge_plan_cd, now, 80, ChargeModel.minutes(minutes))
    Column(Modifier.fillMaxWidth().semantics { contentDescription = cd }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$now%", style = mt(13f, 600, tabular = true), color = Color(0xFF9A5B00))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui.clock", tint = Ink, size = 13.dp)
                Text(ChargeModel.minutes(minutes), style = mt(13f, 600, tabular = true), color = Ink)
            }
            Text("80%", style = mt(13f, 600, tabular = true), color = Color(0xFF178A4E))
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            drawRect(Color(0xFFECE8E1))
            drawRect(Amber, Offset.Zero, androidx.compose.ui.geometry.Size(size.width * soc.toFloat(), size.height))
            val x0 = size.width * soc.toFloat()
            val x1 = size.width * 0.8f
            var x = x0
            var stripe = true
            while (x < x1) {
                val w = minOf(if (stripe) 10.dp.toPx() else 2.dp.toPx(), x1 - x)
                drawRect(if (stripe) Color(0xFF22A85C) else Color(0xFF3FC27A), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                x += w
                stripe = !stripe
            }
        }
    }
}
