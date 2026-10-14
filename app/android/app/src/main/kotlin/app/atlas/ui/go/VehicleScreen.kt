package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Vehicle
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.search.PlaceSource
import app.atlas.services.Units
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.RoadSign
import app.atlas.ui.kit.SignKind
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The vehicles with a form, in the order of the board's top row. */
private val FORM_MODES = listOf(Mode.CAR, Mode.EBIKE, Mode.CAMPER, Mode.TRUCK, Mode.BOAT)

/**
 * Your vehicle, as on the Truck & coach board: the vehicle row; for a truck the tachograph (the 4 h 30 block and the
 * 45-minute break of EU 561/2006 as a ring timer with seven-segment digits, and the day's driving) and the truck parks
 * and coach bays mapped around you; the dimensions routing respects as tiles you step up or down (and, for boats, the
 * bridges around with their clearance against your air draft; for an electric car, its battery). Kept on the phone.
 */
@Composable
fun VehicleScreen(args: Dest.Vehicle, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.go
    var mode by rememberSaveable { mutableStateOf(runCatching { Mode.valueOf(args.mode) }.getOrNull()?.takeIf { it in FORM_MODES } ?: Mode.TRUCK) }
    val vehicles by hub.store.vehicles.collectAsStateWithLifecycle(emptyMap())
    val v = vehicles[mode] ?: GoStore.defaultVehicle(mode)
    val scope = rememberCoroutineScope()
    val save: (Vehicle) -> Unit = { nv -> scope.launch { hub.store.setVehicle(mode, nv) } }
    val units = hub.units
    val here = rememberHere()

    AtlasTheme(dark = true) {
        Column(
            Modifier.fillMaxSize().background(TruckColours.bg).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BoardHeader(stringResource(titleFor(mode)), dark = true, onBack = { nav.back() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (m in FORM_MODES) {
                    val label = stringResource(if (m == Mode.SAIL) R.string.go_mode_sail else GoMode.of(m).label)
                    VehicleDisc(vehicleSymbol(m), label, m == mode, dark = true) { mode = m }
                }
            }
            if (mode == Mode.TRUCK) {
                val tacho by hub.tacho.collectAsStateWithLifecycle()
                TachoCard(tacho ?: TachoState())
            }
            if (mode == Mode.CAR || mode == Mode.EBIKE) ElectricToggle(v.ev != null) { on ->
                save(if (on) v.withEv { it }.let { if (mode == Mode.EBIKE) it.copy(ev = it.ev!!.copy(batteryKwh = 0.5, consumption = 1.0, maxDcKw = 0.5, maxAcKw = 0.5)) else it } else v.copy(ev = null))
            }
            val fields = VehicleField.forMode(mode).filter { f -> f.unit == "m" || f.unit == "t" || v.ev != null }
            if (fields.isNotEmpty()) FieldTiles(mode, v, fields, units, save)
            if (mode == Mode.CAR && v.ev != null) {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(TruckColours.tile)
                        .button(stringResource(R.string.go_charging_route)) { nav.go(Dest.ChargingRoute()) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ArtIcon(BoardArt.charge, size = 24.dp, tint = Color(0xFF7CFF6B))
                    Text(stringResource(R.string.go_charging_route), style = TruckType.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.text, modifier = Modifier.weight(1f))
                    GoSymbol("ui-chevron-right", tint = TruckColours.soft, size = 16.dp)
                }
            }
            when (mode) {
                Mode.TRUCK -> TruckParks(here, units)
                Mode.BOAT, Mode.SAIL -> BridgesAround(here, v.airDraftM, units)
                else -> Unit
            }
            Text(
                stringResource(if (mode == Mode.TRUCK) R.string.go_truck_footer else R.string.go_vehicle_footer),
                style = TruckType.copy(fontSize = 10.sp), color = Color(0xFF7A8292), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )
        }
    }
}

/** The Truck board's colours (canvas/project/Truck.dc.html). */
internal object TruckColours {
    val bg = Color(0xFF12151B)
    val tile = Color(0xFF1E232C)
    val raised = Color(0xFF2A3040)
    val text = Color(0xFFEDEFF3)
    val soft = Color(0xFF9AA2B2)
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFFF5A3C)
    val green = Color(0xFF7CFF6B)
}

internal val TruckType = TextStyle(fontFamily = app.atlas.ui.kit.InterFamily)

/** The board symbol for a vehicle of the form's row. */
private fun vehicleSymbol(m: Mode): String = when (m) {
    Mode.CAR -> "go-car"
    Mode.EBIKE -> "go-ebike"
    Mode.CAMPER -> "go-camper"
    Mode.TRUCK -> "go-truck"
    Mode.BOAT -> "go-boat"
    else -> "go-sail"
}

/** The boards' header: the round back button and the serif italic title, 32 sp. */
@Composable
internal fun BoardHeader(title: String, dark: Boolean, onBack: () -> Unit) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (dark) Color.White.copy(alpha = 0.1f) else Color.White)
                .button(stringResource(R.string.kit_back), onBack),
            contentAlignment = Alignment.Center,
        ) { ArtIcon(BoardArt.back, size = 20.dp, tint = if (dark) TruckColours.text else Color(0xFF15251C)) }
        Text(
            title, Modifier.weight(1f).semantics { heading() },
            style = Atlas.type.displayItalic.copy(fontSize = 32.sp, lineHeight = 34.sp), color = if (dark) TruckColours.text else Color(0xFF15251C),
        )
    }
}

/** "Electric" as a dark switch row. */
@Composable
private fun ElectricToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.go_electric)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(TruckColours.tile)
            .toggleable(on, role = Role.Switch, onValueChange = onToggle).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtIcon(BoardArt.charge, size = 22.dp, tint = if (on) TruckColours.green else TruckColours.soft)
        Text(label, style = TruckType.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.text, modifier = Modifier.weight(1f))
        Box(Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape).background(if (on) TruckColours.green else Color.White.copy(alpha = 0.16f)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) TruckColours.bg else TruckColours.text))
        }
    }
}

private fun titleFor(mode: Mode): Int = when (mode) {
    Mode.TRUCK -> R.string.go_vehicle_truck
    Mode.CAMPER -> R.string.go_vehicle_camper
    Mode.BOAT -> R.string.go_vehicle_boat
    Mode.SAIL -> R.string.go_vehicle_sail
    Mode.EBIKE -> R.string.go_vehicle_ebike
    else -> R.string.go_vehicle_car
}

/**
 * The Truck board's tachograph card: the dial (the 4 h 30 block driven in amber, what is left before the break in red
 * when it is short, the ticks every half hour) with the time to the break in amber seven-segment figures, and beside it
 * the block driven, the rules, and what is left of the day.
 */
@Composable
internal fun TachoCard(t: TachoState) {
    val toBreak = GoFormat.hoursMinutes(t.toBreak)
    val said = stringResource(R.string.go_tacho_said, toBreak, GoFormat.hoursMinutes(t.drivingSinceBreak), GoFormat.hoursMinutes(t.drivenToday))
    Row(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF232833), Color(0xFF171B22))))
            .border(2.dp, Color(0xFF2E3440), RoundedCornerShape(26.dp))
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp)) {
                val k = size.minDimension / 180f
                drawCircle(Color(0xFF2A2F38), 86f * k)
                drawCircle(Color(0xFF4A5260), 86f * k, style = Stroke(3f * k))
                drawTachoDial(t, ring = 12f * k, inset = 14f * k, ticks = true)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SevenSegment(toBreak, height = 30.dp, colour = TruckColours.amber)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.go_to_break_short), style = TruckType.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.soft)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.go_driven_today), style = TruckType.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp), color = TruckColours.soft)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SevenSegment(GoFormat.hoursMinutes(t.drivingSinceBreak), height = 26.dp, colour = TruckColours.amber)
                Text("/", style = TruckType.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = TruckColours.amber)
                SevenSegment(GoFormat.hoursMinutes(Tachograph.MAX_BLOCK), height = 26.dp, colour = TruckColours.amber)
            }
            Text(stringResource(R.string.go_tacho_rules, (t.breakNeeded / 60).toInt()), style = TruckType.copy(fontSize = 12.sp), color = TruckColours.soft)
            Text(
                stringResource(R.string.go_tacho_day_left, GoFormat.hoursMinutes(t.leftToday)),
                style = TruckType.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold), color = Color(0xFF1B1F24),
                modifier = Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(TruckColours.amber).padding(horizontal = 10.dp).wrapContentHeight(),
            )
        }
    }
}

/** The vehicle's numbers as the Truck board's tiles (four to a row); tapping one opens its stepper under the tiles. */
@Composable
private fun FieldTiles(mode: Mode, v: Vehicle, fields: List<VehicleField>, units: Units, save: (Vehicle) -> Unit) {
    var editing by rememberSaveable(mode) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in fields.chunked(4)) {
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in row) {
                    val value = f.get(v)
                    val label = stringResource(f.label)
                    val shown = value?.let { f.format(it, units) } ?: "?"
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (editing == f.name) TruckColours.raised else TruckColours.tile)
                            .then(if (editing == f.name) Modifier.border(2.dp, TruckColours.amber, RoundedCornerShape(14.dp)) else Modifier)
                            .button("$label $shown") { editing = if (editing == f.name) null else f.name },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(shown, style = TruckType.copy(fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black), color = TruckColours.text, maxLines = 1)
                        Text(label, style = TruckType.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold), color = TruckColours.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        val f = fields.firstOrNull { it.name == editing } ?: return@Column
        val value = f.get(v)
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(TruckColours.tile).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            signFor(f)?.let { kind -> RoadSign(kind, value?.let { f.format(it, units) } ?: "?", size = 44.dp) }
            Text(stringResource(f.label), style = TruckType.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.text, modifier = Modifier.weight(1f))
            Stepper("ui-minus", stringResource(R.string.go_less)) { save(f.set(v, f.stepped(value, false, VehicleField.start(f, mode)))) }
            Text(value?.let { f.format(it, units) } ?: "?", style = TruckType.copy(fontSize = 18.sp, fontWeight = FontWeight.Black), color = TruckColours.text,
                modifier = Modifier.width(84.dp), textAlign = TextAlign.Center, maxLines = 1)
            Stepper("ui-plus", stringResource(R.string.go_more)) { save(f.set(v, f.stepped(value, true, VehicleField.start(f, mode)))) }
        }
    }
}

private fun signFor(f: VehicleField): SignKind? = when (f) {
    VehicleField.HEIGHT, VehicleField.AIR_DRAFT -> SignKind.HEIGHT
    VehicleField.WIDTH, VehicleField.BEAM -> SignKind.WIDTH
    VehicleField.WEIGHT, VehicleField.AXLE -> SignKind.WEIGHT
    VehicleField.LENGTH -> SignKind.LENGTH
    else -> null
}

@Composable
private fun Stepper(symbol: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).button(description, onClick), contentAlignment = Alignment.Center) {
        GoSymbol(symbol, tint = TruckColours.text, size = 20.dp)
    }
}

/** A parking for trucks or coaches found around you. */
data class TruckPark(val name: String?, val at: LatLon, val metres: Double, val coach: Boolean, val tags: Map<String, String>)

/** Truck parks and coach bays from the offline place store: `hgv`/`bus`/`coach` parking and motorway services. */
object TruckParks {
    fun around(store: PlaceSource, at: LatLon, radiusM: Double = 30_000.0, limit: Int = 6): List<TruckPark> {
        if (store.size == 0) return emptyList()
        val box = BBox.around(at, radiusM)
        val seen = HashSet<String>()
        val out = ArrayList<TruckPark>()
        fun take(r: app.atlas.search.PlaceRecord, coach: Boolean) {
            if (!seen.add(r.place.key)) return
            val d = Geo.distance(at, r.location)
            if (d <= radiusM) out += TruckPark(r.name(), r.location, d, coach, r.tags)
        }
        for (r in store.byTag("hgv", null, box)) if (isTruckPark(r.tags)) take(r, false)
        for (r in store.byTag("highway", "services", box)) take(r, false)
        for (r in store.byTag("highway", "rest_area", box)) if (r.tags["hgv"] != "no") take(r, false)
        for (k in listOf("bus", "coach", "tourist_bus")) for (r in store.byTag(k, null, box)) if (isCoachBay(r.tags)) take(r, true)
        return out.sortedBy { it.metres }.take(limit)
    }

    fun isTruckPark(t: Map<String, String>) = t["amenity"] == "parking" && t["hgv"] in setOf("designated", "yes")
    fun isCoachBay(t: Map<String, String>) = t["amenity"] == "parking" && listOf("bus", "coach", "tourist_bus").any { t[it] in setOf("designated", "yes") }
}

@Composable
private fun TruckParks(here: LatLon, units: Units) {
    val services = LocalServices.current
    val parks by produceState<List<TruckPark>?>(null, here) {
        value = withContext(Dispatchers.IO) { runCatching { TruckParks.around(services.placeStores, here) }.getOrDefault(emptyList()) }
    }
    val list = parks ?: return
    if (list.isEmpty()) {
        Text(stringResource(R.string.go_truck_parks_none), style = TruckType.copy(fontSize = 13.sp), color = TruckColours.soft)
        return
    }
    list.forEachIndexed { i, p ->
        val d = GoFormat.distance(p.metres, units)
        val facts = buildList {
            if (p.tags["supervised"] == "yes") add(stringResource(R.string.go_guarded))
            if (p.tags["fence"] == "yes" || p.tags["barrier"] == "fence") add(stringResource(R.string.go_fenced))
            p.tags["fee"]?.let { add(if (it == "no") stringResource(R.string.go_free) else stringResource(R.string.go_fee)) }
        }.joinToString(" · ")
        val tags = listOfNotNull(
            stringResource(R.string.go_tag_showers).takeIf { p.tags["shower"] == "yes" || p.tags["showers"] == "yes" },
            stringResource(R.string.go_tag_toilets).takeIf { p.tags["toilets"] == "yes" },
            stringResource(R.string.go_tag_food).takeIf { p.tags["restaurant"] == "yes" || p.tags["food"] == "yes" },
            stringResource(R.string.go_tag_fuel).takeIf { p.tags["fuel"] == "yes" },
        )
        val capacity = (p.tags["capacity:hgv"] ?: p.tags["capacity:bus"] ?: p.tags["capacity"])?.toIntOrNull()
        Row(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).background(if (i == 0) TruckColours.raised else TruckColours.tile)
                .then(if (i == 0) Modifier.border(2.dp, TruckColours.amber, RoundedCornerShape(20.dp)) else Modifier).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                Text(d.unit, style = TruckType.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.soft)
                Text(d.value, style = TruckType.copy(fontSize = 20.sp, fontWeight = FontWeight.Black, fontFeatureSettings = "tnum"), color = TruckColours.text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(p.name ?: stringResource(if (p.coach) R.string.go_coach_bays else R.string.go_truck_park), style = TruckType.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                    color = TruckColours.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (facts.isNotEmpty()) Text(facts, style = TruckType.copy(fontSize = 12.sp), color = TruckColours.soft, maxLines = 1)
                if (tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (tag in tags) Text(tag, style = TruckType.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = TruckColours.text,
                        modifier = Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp).wrapContentHeight())
                }
            }
            if (capacity != null) Column(horizontalAlignment = Alignment.End) {
                Text("$capacity", style = TruckType.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold), color = TruckColours.green)
                Text(stringResource(R.string.go_spaces_short), style = TruckType.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.soft)
            }
        }
    }
}

/** For boats: the bridges mapped around you, lowest first, against your air draft. */
@Composable
private fun BridgesAround(here: LatLon, airDraft: Double?, units: Units) {
    val services = LocalServices.current
    val bridges by produceState<List<Pair<app.atlas.search.PlaceRecord, Double>>?>(null, here) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                services.placeStores.byTag("seamark:type", "bridge", BBox.around(here, 2_000.0))
                    .mapNotNull { r -> RouteBridges.clearanceOf(r.tags)?.let { r to it } }
                    .sortedBy { it.second }.take(5)
            }.getOrDefault(emptyList())
        }
    }
    val list = bridges ?: return
    if (list.isEmpty()) return
    Text(stringResource(R.string.go_bridges_around), style = TruckType.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp), color = TruckColours.soft)
    for ((r, c) in list) {
        val fits = airDraft == null || airDraft <= c
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(TruckColours.tile).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            RoadSign(SignKind.HEIGHT, GoFormat.size(c, units), size = 42.dp)
            Column(Modifier.weight(1f)) {
                Text(r.tags["seamark:name"] ?: r.name() ?: stringResource(R.string.go_bridge), style = TruckType.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = TruckColours.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(GoFormat.distance(Geo.distance(here, r.location), units).toString(), style = TruckType.copy(fontSize = 12.sp), color = TruckColours.soft)
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (fits) Color(0xFF178A4E) else NavColours.end), contentAlignment = Alignment.Center) {
                ArtIcon(if (fits) BoardArt.check.weighted(1.4f) else BoardArt.close.tinted.weighted(1.2f), size = 15.dp, tint = Color.White)
            }
        }
    }
}
