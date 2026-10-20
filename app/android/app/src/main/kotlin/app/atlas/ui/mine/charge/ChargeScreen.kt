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
import app.atlas.mapview.StageMap
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.BackButton
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.MSy
import app.atlas.ui.mine.MineState
import app.atlas.ui.mine.SERIF
import app.atlas.ui.mine.TextPill
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
import app.atlas.ui.mine.foot
import app.atlas.ui.mine.shift
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.mt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt


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
    Box(Modifier.fillMaxSize()) {
        StageMap(
            state = map, tiles = tiles, modifier = Modifier.fillMaxSize(), background = Color(0xFFF4F1EA),
            overlays = MapOverlays(markers = markers, location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) }),
            contentPadding = PaddingValues(top = 200.dp, bottom = 380.dp),
            onTap = { hit -> if (hit is MapHit.OnMarker) selected = hit.marker.id else if (hit is MapHit.Empty) selected = null },
        )
        // the board's wash over the map's top: the paper at 90 % fading out over 190 px
        Box(Modifier.fillMaxWidth().height(190.dp + shift()).bbox(grad = listOf(cssLinear(180f, 0f to 0xE6F4F1EA, 1f to 0x00F4F1EA))))
        Column(
            Modifier.fillMaxWidth().padding(start = if (pick == null) 16.dp else 12.dp, end = if (pick == null) 16.dp else 12.dp, top = (if (pick == null) 42.dp else 44.dp) + shift()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pick == null) {
                TopBar(onBack = { nav.back() }, onFuel = { nav.go(Dest.Pump(centre.lat, centre.lon)) }, onPark = { nav.go(Dest.Category("parking")) }, onBike = { nav.go(Dest.Twowheel()) })
                // the connector tiles: five of 71.2 × 66, 6 px apart
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
        // The sheet: the Plugs board's white one (radius 30, 16 px in) for the overview, the Charge board's paper one (18 px
        // in) for a site; a shadow 10 px up, the grabber 10 px down, then 12 (or 14) px between its parts.
        val card = pick != null
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 400.dp)
                .bbox(if (card) 0xFFFBFAF7 else WHITE, CssCorners(30f, 30f, 0f, 0f), listOf(CssShadow(0f, -10f, 30f, 0f, if (card) 0x24181C30 else 0x2914161B)))
                .padding(start = if (card) 18.dp else 16.dp, end = if (card) 18.dp else 16.dp, top = 10.dp, bottom = foot(23.5f)),
            verticalArrangement = Arrangement.spacedBy(if (card) 14.dp else 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).bbox(if (card) 0xFFD8D4CC else 0xFFD5D8DE, cr(3f)))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (card) 14.dp else 12.dp)) {
                if (pick != null) CardSheet(pick, car, centre, onClose = { selected = null }, onGo = { c ->
                    nav.go(Dest.Route(c.place.location.lat, c.place.location.lon, c.name.ifBlank { c.operator ?: "" }, centre.lat, centre.lon, null, "CAR"))
                }, onCar = { carDialog = true }, onMore = {
                    val url = "https://chargemap.com/map?lat=${"%.5f".format(Locale.ROOT, pick.place.location.lat)}&lng=${"%.5f".format(Locale.ROOT, pick.place.location.lon)}&zoom=16"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                else Overview(shown, result, availableOnly, onAvailable = { availableOnly = !availableOnly }, car = car, onCar = { carDialog = true }, onPick = { selected = it.key },
                    onDownload = { nav.go(Dest.RegionPicker(centre.lat, centre.lon)) })
            }
            // "Plan a long trip with stops": 48 high, radius 24, the 18 px route and the label (14, 800), 8 px apart
            if (pick == null) Row(
                Modifier.fillMaxWidth().height(48.dp).bbox(INK, cr(24f)).clickable(role = Role.Button) { nav.go(Dest.ChargingRoute()) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                MSy("ui.route.2", 18f, WHITE)
                BText(stringResource(R.string.mine_charge_trip), bt(14f, 800, 18f), WHITE)
            }
        }
    }
    if (carDialog) CarDialog(store, car, onClose = { carDialog = false })
}

/** The back round and the four ways to move (fuel, charge here, park, bike) on the board's 94 % white, 4 px in, 76.5 px each. */
@Composable
private fun TopBar(onBack: () -> Unit, onFuel: () -> Unit, onPark: () -> Unit, onBike: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack, bg = 0xF0FFFFFF)
        Row(Modifier.weight(1f).height(44.dp).bbox(0xF0FFFFFF, cr(22f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((symbol, label, on, click) in listOf(
                Quad("place.fuel", R.string.mine_move_fuel, false, onFuel), Quad("ui.charge", R.string.mine_move_charge, true, {}),
                Quad("park.parking", R.string.mine_move_park, false, onPark), Quad("ui.wrench", R.string.mine_move_bike, false, onBike),
            )) {
                Row(
                    Modifier.weight(1f).height(36.dp).bbox(if (on) INK else 0, cr(18f)).clickable(role = Role.Button, onClick = click),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    MSy(symbol, 16f, if (on) WHITE else INK)
                    BText(stringResource(label), bt(12f, 800, 15.5f), if (on) WHITE else INK)
                }
            }
        }
    }
}

private data class Quad(val symbol: String, val label: Int, val on: Boolean, val click: () -> Unit)

/** A connector tile (66 high, radius 18, a soft shadow): the plug's 30 px symbol over its name (11, 800); dark when wanted. */
@Composable
private fun PlugTile(p: ChargeModel.Plug, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(66.dp).bbox(if (on) INK else 0xF0FFFFFF, cr(18f), listOf(CssShadow(0f, 4f, 12f, 0f, 0x2414161B))).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = p.label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        MSy(p.art, 30f, if (on) WHITE else INK)
        BText(p.label, bt(11f, 800, 14f), if (on) WHITE else INK)
    }
}

/** The "≥ 50 kW" tile: the least power (15, 900) over "kW" (11, 800); tap to raise it. */
@Composable
private fun MinPowerTile(kw: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(66.dp).bbox(if (kw > 0) INK else 0xF0FFFFFF, cr(18f), listOf(CssShadow(0f, 4f, 12f, 0f, 0x2414161B))).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        BText(if (kw == 0) "kW" else "≥ $kw", bt(15f, 900, 19.5f, tnum = true), if (kw > 0) WHITE else INK)
        BText(if (kw == 0) stringResource(R.string.mine_charge_any) else "kW", bt(11f, 800, 14f), if (kw > 0) WHITE else INK)
    }
}

/** The Plugs sheet: sites, free now and the cheapest price, the power mix, the nearest sites. */
@Composable
private fun Overview(shown: List<Charger>, result: ChargeResult?, availableOnly: Boolean, onAvailable: () -> Unit, car: Car, onCar: () -> Unit, onPick: (Charger) -> Unit, onDownload: () -> Unit) {
    val sum = ChargeModel.summary(shown)
    // the figures (26, 900) and what they count (12, 700, grey) on one baseline, 14 px between them
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Stat("${sum.sites}", stringResource(R.string.mine_charge_sites), INK)
        if (sum.free != null) Stat("${sum.free}", stringResource(R.string.mine_charge_free_now), 0xFF12663A)
        if (sum.cheapest != null) Stat(String.format(Locale.ROOT, "%.2f", sum.cheapest), (if (sum.currency == "EUR") "€" else sum.currency ?: "") + "/kWh " + stringResource(R.string.mine_charge_min), INK)
    }
    // with no car chosen yet, the invitation to pick one (the tiles follow the car's plugs once there is one)
    if (car.model == null) CarPill(car, onCar)
    if (shown.isEmpty()) {
        val offline = result?.online == false
        MineState(
            if (result == null) "ui.refresh" else "ui.charge", stringResource(if (result == null) R.string.kit_loading else R.string.mine_charge_none),
            text = if (result != null) stringResource(if (offline) R.string.mine_charge_none_offline else R.string.mine_charge_none_text) else null, tint = Color(0xFF8A8F98),
            action = if (offline) ({ TextPill(stringResource(R.string.mine_charge_download), bt(13f, 800, 17f), INK, WHITE, h = 40f, padH = 16f, symbol = "ui.download", symbolSize = 16f, gap = 6f, onClick = onDownload) }) else null,
        )
    } else {
        PowerMix(shown)
        for ((i, c) in shown.take(2).withIndex()) SiteCard(c, car, highlight = i == 0, onClick = { onPick(c) })
        if (shown.any { it.live }) Row(Modifier.clickable(role = Role.Switch, onClick = onAvailable), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).bbox(if (availableOnly) 0xFF22A85C else 0xFFD8D4CC, cr(5f)))
            BText(stringResource(R.string.mine_charge_available_only), bt(12f, 700, 15.5f), 0xFF464C5A)
        }
    }
}

@Composable
private fun RowScope.Stat(value: String, label: String, colour: Long) {
    Row(Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BText(value, bt(26f, 900, 33.5f, tnum = true), colour, Modifier.alignByBaseline())
        BText(label, bt(12f, 700, 15.5f, tnum = true), 0xFF676D7C, Modifier.alignByBaseline())
    }
}

/** The car as a small pill (34 high, pale): its charge and range, or an invitation to pick one. */
@Composable
private fun CarPill(car: Car, onClick: () -> Unit) {
    val units by LocalServices.current.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val text = if (car.model != null) "${(car.soc * 100).roundToInt()}%" + (car.rangeKm?.let { " · ${GoFormat.distance(it * 1000.0, units)}" } ?: "") else stringResource(R.string.mine_car_pick)
    TextPill(text, bt(12f, 800, 15.5f, tnum = true), 0xFFF1EEE8, INK, h = 34f, padH = 10f, symbol = "drop.battery", symbolSize = 18f, gap = 6f, tint = INK, onClick = onClick)
}

/** The bar of the Plugs sheet: 12 px, radius 6, the bands 3 px apart as wide as their counts, and under it each band and "×n" (11, 800). */
@Composable
private fun PowerMix(list: List<Charger>) {
    val mix = ChargeModel.mix(list)
    val description = mix.joinToString(", ") { "${it.second} × ${it.first.label}" }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(12.dp).bbox(r = cr(6f)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((b, n) in mix) Box(Modifier.weight(n.toFloat()).height(12.dp).bbox(b.colour))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((b, n) in mix) Row(Modifier.weight(n.toFloat()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BText(b.label, bt(11f, 800, 14f, tnum = true), 0xFF464C5A)
                BText("×$n", bt(11f, 800, 14f, tnum = true), 0xFF8A8F98)
            }
        }
    }
}

/**
 * A site as the board's cards (64 high, radius 18, 12 px in): the nearest on cream ringed in its band's colour, the rest pale;
 * the power as a 44 px disc ("150" 13, 900 over "kW" 9, 900), its name (15, 800) and what is known (12), how far and how many are free.
 */
@Composable
private fun SiteCard(c: Charger, car: Car, highlight: Boolean, onClick: () -> Unit) {
    val colour = if (c.busy) PowerBand.UNKNOWN.colour else c.band.colour
    val walkMin = c.distanceM?.let { (it * 1.3 / 1.35 / 60).roundToInt().coerceAtLeast(1) }
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .bbox(if (highlight) 0xFFFFF4E8 else 0xFFF4F5F7, cr(18f), if (highlight) listOf(CssShadow(0f, 0f, 0f, 2f, colour)) else emptyList(), clip = false)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.size(44.dp).bbox(colour, cr(22f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (c.kw != null) {
                BText("${c.kw.roundToInt()}", bt(13f, 900, 13f, tnum = true), WHITE)
                BText("kW", bt(9f, 900, 9f), WHITE)
            } else MSy("ui.charge", 22f, WHITE)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(c.name.ifBlank { stringResource(R.string.mine_charge_site) }, bt(15f, 800, 19.5f), INK)
            BText(detail(c), bt(12f, 400, 15.5f, tnum = true), 0xFF676D7C)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (walkMin != null) BText("$walkMin′", bt(15f, 900, 19.5f, tnum = true), INK)
            if (c.live) BText(stringResource(R.string.mine_charge_free_of, c.free ?: 0, c.points ?: 0), bt(12f, 800, 15.5f, tnum = true), if (c.busy) 0xFFC8322A else 0xFF12663A)
            else c.points?.let { BText(pluralStringResource(R.plurals.mine_n_points, it, it), bt(12f, 700, 15.5f, tnum = true), 0xFF676D7C) }
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
                for ((id, symbol) in listOf("cafe" to "place.cafe", "toilet" to "need.toilet.2")) {
                    val found = runCatching { services.places.nearby(id, c.place.location, 250.0, 1).let { f -> f.firstOrNull { it.places.isNotEmpty() || it.error != null } } }.getOrNull()
                    if (found?.places?.isNotEmpty() == true) add(symbol)
                }
            }
        }
    }
    val driveMin = c.distanceM?.let { (it * 1.4 / 8.3 / 60).roundToInt().coerceAtLeast(1) }
    // the head: the operator's 48 px tile (radius 14), 12 px on the name (18, 600) over the drive and the price (13, grey),
    // and the LIVE chip (26 high, a 7 px dot)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClose)) {
        // The operator's own logo from the brand index when it knows it; else the board's tile with the operator's initial
        // (or the bolt when nobody is named).
        val brand = remember(c.place) { Brands.forTags(c.place.tags) }
        val initial = (c.operator ?: c.name).firstOrNull()?.uppercase()
        if (brand != null) BrandLogo(brand, 48.dp)
        else Box(Modifier.size(48.dp).bbox(0xFF10233F, cr(14f)), contentAlignment = Alignment.Center) {
            if (initial != null) BText(initial, bt(30f, 400, 30f, SERIF, italic = true), 0xFF7EE0A4)
            else MSy("ui.charge", 26f, 0xFF7EE0A4)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BText(c.name.ifBlank { c.operator ?: stringResource(R.string.mine_charge_site) }, bt(18f, 600, 23.5f), INK)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (driveMin != null) Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    MSy("mode.car.2", 13f, 0xFF464C5A)
                    BText("$driveMin′", bt(13f, 400, 17f, tnum = true), 0xFF464C5A)
                }
                c.pricePerKwh?.let { BText(String.format(Locale.ROOT, "%s%.2f/kWh", if (c.currency == "EUR") "€" else "", it), bt(13f, 400, 17f, tnum = true), 0xFF464C5A) }
                if (c.plugAndCharge) Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    MSy("fact.socket", 13f, 0xFF178A4E)
                    MSy("ui.check", 12f, 0xFF178A4E)
                }
            }
        }
        if (c.live) Row(Modifier.height(26.dp).bbox(0xFFE7F4EC, cr(13f)).padding(horizontal = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).bbox(0xFF22A85C, cr(4f)))
            BText(stringResource(R.string.mine_charge_live), bt(12f, 700, 15.5f), 0xFF12663A)
        }
    }
    // The plugs: white cards (radius 18, a 1 px ring) two to a row, 10 px apart.
    val shown = c.sockets.ifEmpty { emptyList() }
    if (shown.isEmpty()) BText(stringResource(R.string.mine_charge_no_sockets), bt(12f, 400, 15.5f), 0xFF676D7C, lines = 3)
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
    else Row(Modifier.fillMaxWidth().height(44.dp).bbox(0xFFF1EEE8, cr(14f)).clickable(role = Role.Button, onClick = onCar).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MSy("drop.battery", 20f, INK)
        BText(stringResource(if (model == null) R.string.mine_charge_pick_car else R.string.mine_charge_no_plan), bt(13f, 700, 17f), INK)
    }
    // What is near while you wait (40 px rounds), the Chargemap link, and the way there (52 high, the board's blue).
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in nearby) Box(Modifier.size(40.dp).bbox(0xFFF1EEE8, cr(20f)), contentAlignment = Alignment.Center) { MSy(s, 18f, 0xFF464C5A) }
        Spacer(Modifier.weight(1f))
        BText(stringResource(R.string.mine_charge_chargemap), bt(12f, 700, 15.5f), 0xFF2E5BFF, Modifier.clickable(role = Role.Button, onClick = onMore).padding(8.dp))
        Row(
            Modifier.height(52.dp).bbox(0xFF2E5BFF, cr(26f)).clickable(role = Role.Button) { onGo(c) }.padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("tab.go", 20f, WHITE)
            if (driveMin != null) BText("$driveMin′", bt(16f, 600, 20.5f, tnum = true), WHITE)
        }
    }
}

/** A plug on the card (56 high, 12 px in): its face (30 × 36), 10 px on a dot per point (10 px, 3 apart: green free, amber busy, grey unknown) over the kW (13, 700). */
@Composable
private fun SocketCard(s: app.atlas.providers.Socket, c: Charger, modifier: Modifier) {
    val face = ChargeModel.PLUGS.firstOrNull { s.type in it.types }?.face
    val live = c.place.extra["status:${s.type}"]?.split('/')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
    val label = "${plugName(s.type)}${s.kw?.let { ", ${it.roundToInt()} kW" } ?: ""}"
    Row(
        modifier.height(56.dp).bbox(WHITE, cr(18f), listOf(CssShadow(0f, 0f, 0f, 1f, 0xFFECE8E1))).padding(horizontal = 12.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (face != null) MSy(face, 30f, INK, h = 36f) else MSy("fact.socket", 30f, INK)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val total = (live?.get(1) ?: s.count).coerceIn(1, 8)
                val free = live?.get(0)
                for (i in 0 until total) Box(Modifier.size(10.dp).bbox(if (free == null) 0xFFC9C5BC else if (i < free) 0xFF22A85C else 0xFFF2A71B, cr(5f)))
            }
            BText(s.kw?.let { "${it.roundToInt()} kW" } ?: plugName(s.type), bt(13f, 700, 17f, tnum = true), INK)
        }
    }
}

/** "37% → 80% in 20′" as the board's bar: the labels (13, 600), 6 px, then 14 px of pale with the charge now in amber and what one stop adds in green stripes (10 px on, 2 off). */
@Composable
private fun PlanBar(soc: Double, minutes: Double) {
    val now = (soc * 100).roundToInt()
    val cd = stringResource(R.string.mine_charge_plan_cd, now, 80, ChargeModel.minutes(minutes))
    Column(Modifier.fillMaxWidth().semantics { contentDescription = cd }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BText("$now%", bt(13f, 600, 17f, tnum = true), 0xFF9A5B00)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                MSy("ui.clock", 13f, INK)
                BText(ChargeModel.minutes(minutes), bt(13f, 600, 17f, tnum = true), INK)
            }
            BText("80%", bt(13f, 600, 17f, tnum = true), 0xFF178A4E)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).bbox(0xFFECE8E1, cr(7f))) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().weight(soc.toFloat().coerceIn(0.001f, 1f)).bbox(0xFFF2A71B))
                Box(Modifier.fillMaxHeight().weight((0.8f - soc.toFloat()).coerceAtLeast(0.001f)).bbox(grad = listOf(cssRepeatingLinear(90f, 12f, 0f to 0xFF22A85C, 10f to 0xFF22A85C, 10f to 0xFF3FC27A, 12f to 0xFF3FC27A))))
                Spacer(Modifier.fillMaxHeight().weight(0.2f))
            }
        }
    }
}
