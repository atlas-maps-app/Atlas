package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.Argb
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.RoadSign
import app.atlas.ui.kit.SignKind
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The vehicles with a form, in the order of the board's top row. */
private val FORM_MODES = listOf(Mode.CAR, Mode.EBIKE, Mode.CAMPER, Mode.TRUCK, Mode.BOAT)

private const val GEIST = "Geist, system-ui, sans-serif"

/**
 * Your vehicle, as on the Truck & coach board, each element where the board has it: the vehicle row; for a truck the
 * tachograph (the 4 h 30 block and the 45-minute break of EU 561/2006 as the board's dial with VT323 figures, and the
 * day's driving) and the truck parks and coach bays mapped around you; the dimensions routing respects as tiles you
 * step up or down (and, for boats, the bridges around with their clearance against your air draft; for an electric
 * car, its battery). Kept on the phone.
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
    val units by hub.unitsFlow.collectAsStateWithLifecycle()
    val here = rememberHere()

    AtlasTheme(dark = true) {
        BoardPage(0xFF12151B, stringResource(if (mode == Mode.TRUCK) R.string.go_truck_footer else R.string.go_vehicle_footer), 0xFF7A8292) {
            BoardHeader(stringResource(titleFor(mode)), dark = true, onBack = { nav.back() })
            Spacer(Modifier.height(12.dp))
            VehicleRow(FORM_MODES, mode, dark = true, label = { m -> stringResource(if (m == Mode.SAIL) R.string.go_mode_sail else GoMode.of(m).label) }) { mode = it }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        Modifier.fillMaxWidth().height(60.dp).cssBox(bg = TruckColours.TILE, r = CssCorners(20f, 20f, 20f, 20f))
                            .button(stringResource(R.string.go_charging_route)) { nav.go(Dest.ChargingRoute()) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Symbol("ui.charge", Modifier.size(24.dp), tint = argb(TruckColours.GREEN), size = 24.dp)
                        BoardText(stringResource(R.string.go_charging_route), boardType(GEIST, 14f, 800, lineHeight = 18f), TruckColours.TEXT, Modifier.weight(1f))
                        Symbol("ui.chevron-right", Modifier.size(16.dp), tint = argb(TruckColours.SOFT), size = 16.dp)
                    }
                }
                when (mode) {
                    Mode.TRUCK -> TruckParks(here, units)
                    Mode.BOAT, Mode.SAIL -> BridgesAround(here, v.airDraftM, units)
                    else -> Unit
                }
            }
        }
    }
}

/** The Truck board's colours (canvas/project/Truck.dc.html). */
internal object TruckColours {
    const val BG: Argb = 0xFF12151B
    const val TILE: Argb = 0xFF1E232C
    const val RAISED: Argb = 0xFF2A3040
    const val TEXT: Argb = 0xFFEDEFF3
    const val SOFT: Argb = 0xFF9AA2B2
    const val AMBER: Argb = 0xFFFFB547
    const val RED: Argb = 0xFFFF5A3C
    const val GREEN: Argb = 0xFF7CFF6B
}

/** "Electric" as a dark switch row. */
@Composable
private fun ElectricToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.go_electric)
    Row(
        Modifier.fillMaxWidth().height(52.dp).cssBox(bg = TruckColours.TILE, r = CssCorners(16f, 16f, 16f, 16f))
            .toggleable(on, role = Role.Switch, onValueChange = onToggle).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol("ui.charge", Modifier.size(22.dp), tint = argb(if (on) TruckColours.GREEN else TruckColours.SOFT), size = 22.dp)
        BoardText(label, boardType(GEIST, 14f, 800, lineHeight = 18f), TruckColours.TEXT, Modifier.weight(1f))
        Box(Modifier.size(width = 44.dp, height = 26.dp).cssBox(bg = if (on) TruckColours.GREEN else 0x29FFFFFF, r = CssCorners(13f, 13f, 13f, 13f)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).cssBox(bg = if (on) TruckColours.BG else TruckColours.TEXT, r = CssCorners(10f, 10f, 10f, 10f)))
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
 * The Truck board's tachograph card (380 × 200 px, a dark gradient with a 2 px rule): the dial drawn from the board's
 * SVG (the 4 h 30 block driven in amber from the top, what is left before the break in red when it is short, nine
 * ticks) with the time to the break in amber VT323; beside it the block driven against 4:30, the rules, and what is
 * left of the day on the amber pill.
 */
@Composable
internal fun TachoCard(t: TachoState) {
    val toBreak = GoFormat.hoursMinutes(t.toBreak)
    val said = stringResource(R.string.go_tacho_said, toBreak, GoFormat.hoursMinutes(t.drivingSinceBreak), GoFormat.hoursMinutes(t.drivenToday))
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val toBreakLabel = stringResource(R.string.go_to_break_short)
    Row(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .cssBox(grad = listOf(cssLinear(180f, 0f to 0xFF232833, 1f to 0xFF171B22)), r = CssCorners(26f, 26f, 26f, 26f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF2E3440, inset = true)))
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(180.dp)) {
            val k = size.minDimension / 180f
            // The figures at the board's sizes, measured at the drawing's scale.
            val big = measurer.measure(toBreak, boardType("VT323, monospace", 36f * k / (density * fontScale)))
            val small = measurer.measure(toBreakLabel, boardType("Geist, sans-serif", 11f * k / (density * fontScale), 800))
            drawBoardTacho(t, k)
            drawText(big, argb(TruckColours.AMBER), Offset(90f * k - big.size.width / 2f, 86f * k - big.firstBaseline))
            drawText(small, argb(TruckColours.SOFT), Offset(90f * k - small.size.width / 2f, 108f * k - small.firstBaseline))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardText(stringResource(R.string.go_driven_today), boardType(GEIST, 12f, 800, lineHeight = 15.5f, spacing = 1.44f), TruckColours.SOFT)
            BoardText("${GoFormat.hoursMinutes(t.drivingSinceBreak)} / ${GoFormat.hoursMinutes(Tachograph.MAX_BLOCK)}", boardType("VT323, monospace", 32f, lineHeight = 32f), TruckColours.AMBER)
            BoardText(stringResource(R.string.go_tacho_rules, (t.breakNeeded / 60).toInt()), boardType(GEIST, 12f, lineHeight = 15.5f), TruckColours.SOFT)
            Box(Modifier.height(26.dp).cssBox(bg = TruckColours.AMBER, r = CssCorners(13f, 13f, 13f, 13f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                BoardText(stringResource(R.string.go_tacho_day_left, GoFormat.hoursMinutes(t.leftToday)), boardType(GEIST, 12f, 800, lineHeight = 15.5f), 0xFF1B1F24)
            }
        }
    }
}

/**
 * The Truck board's dial on its 180 grid scaled by [k]: the face (#2A2F38, a 3 px rim), the 12 px track, the block
 * driven in amber clockwise from the top, the rest before the break in red when half an hour or less is left, and the
 * nine ticks every 40°.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBoardTacho(t: TachoState, k: Float) {
    val c = Offset(90f * k, 90f * k)
    drawCircle(Color(0xFF2A2F38), 86f * k, c)
    drawCircle(Color(0xFF4A5260), 86f * k, c, style = Stroke(3f * k))
    val tl = Offset(20f * k, 20f * k)
    val box = androidx.compose.ui.geometry.Size(140f * k, 140f * k)
    drawArc(Color(0xFF3A404C), -90f, 360f, false, tl, box, style = Stroke(12f * k))
    val driven = t.blockShare
    if (driven > 0f) drawArc(argb(if (t.breakDue) TruckColours.RED else TruckColours.AMBER), -90f, 360f * driven, false, tl, box, style = Stroke(12f * k))
    if (driven < 1f && t.toBreak <= 30 * 60) drawArc(argb(TruckColours.RED), -90f + 360f * driven, 360f * (1f - driven), false, tl, box, style = Stroke(12f * k))
    for (i in 0 until 9) {
        val a = Math.toRadians(-90.0 + i * 40.0)
        val cx = kotlin.math.cos(a).toFloat()
        val sy = kotlin.math.sin(a).toFloat()
        drawLine(Color(0xFF9AA2B2), Offset(c.x + 80f * k * cx, c.y + 80f * k * sy), Offset(c.x + 74f * k * cx, c.y + 74f * k * sy), 2f * k)
    }
}

/**
 * The vehicle's numbers as the Truck board's tiles (44 px, four to a row, 6 apart): the value in heavy figures over its
 * name; tapping one raises it with the amber ring and opens its stepper under the tiles.
 */
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
                    val on = editing == f.name
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .cssBox(bg = if (on) TruckColours.RAISED else TruckColours.TILE, r = CssCorners(14f, 14f, 14f, 14f), shadows = if (on) listOf(CssShadow(0f, 0f, 0f, 2f, TruckColours.AMBER)) else emptyList())
                            .button("$label $shown") { editing = if (on) null else f.name },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        BoardText(shown, boardType(GEIST, 14f, 900, lineHeight = 18f, tnum = true), TruckColours.TEXT)
                        BoardText(label, boardType(GEIST, 10f, 700, lineHeight = 13f, tnum = true), TruckColours.SOFT)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        val f = fields.firstOrNull { it.name == editing } ?: return@Column
        val value = f.get(v)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().height(64.dp).cssBox(bg = TruckColours.TILE, r = CssCorners(20f, 20f, 20f, 20f)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            signFor(f)?.let { kind -> RoadSign(kind, value?.let { f.format(it, units) } ?: "?", size = 44.dp) }
            BoardText(stringResource(f.label), boardType(GEIST, 14f, 800, lineHeight = 18f), TruckColours.TEXT, Modifier.weight(1f))
            Stepper("ui-minus", stringResource(R.string.go_less)) { save(f.set(v, f.stepped(value, false, VehicleField.start(f, mode)))) }
            BoardText(value?.let { f.format(it, units) } ?: "?", boardType(GEIST, 18f, 900, lineHeight = 23f, tnum = true, align = TextAlign.Center), TruckColours.TEXT, Modifier.width(84.dp))
            Stepper("ui.plus", stringResource(R.string.go_more)) { save(f.set(v, f.stepped(value, true, VehicleField.start(f, mode)))) }
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
    Box(Modifier.size(40.dp).cssBox(bg = 0x1AFFFFFF, r = CssCorners(20f, 20f, 20f, 20f)).button(description, onClick), contentAlignment = Alignment.Center) {
        Symbol(symbol, Modifier.size(20.dp), tint = argb(TruckColours.TEXT), size = 20.dp)
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
        BoardText(stringResource(R.string.go_truck_parks_none), boardType(GEIST, 13f, lineHeight = 17f), TruckColours.SOFT, lines = 3)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEachIndexed { i, p -> if (p.coach) CoachCard(p, units) else ParkCard(p, units, first = i == 0) }
    }
}

/** What a truck park offers, as the board's tags. */
@Composable
private fun parkTags(p: TruckPark): List<String> = listOfNotNull(
    stringResource(R.string.go_tag_showers).takeIf { p.tags["shower"] == "yes" || p.tags["showers"] == "yes" },
    stringResource(R.string.go_tag_toilets).takeIf { p.tags["toilets"] == "yes" },
    stringResource(R.string.go_tag_food).takeIf { p.tags["restaurant"] == "yes" || p.tags["food"] == "yes" },
    stringResource(R.string.go_tag_fuel).takeIf { p.tags["fuel"] == "yes" },
)

/**
 * A truck park as the board's card (96 px, 20 px corners; the nearest raised with the amber ring): how far in a 52 px
 * column, the name, what it is and costs, its tags, and the spaces as mapped in green VT323.
 */
@Composable
private fun ParkCard(p: TruckPark, units: Units, first: Boolean) {
    val d = GoFormat.distance(p.metres, units)
    val facts = buildList {
        if (p.tags["supervised"] == "yes") add(stringResource(R.string.go_guarded))
        if (p.tags["fence"] == "yes" || p.tags["barrier"] == "fence") add(stringResource(R.string.go_fenced))
        p.tags["fee"]?.let { add(if (it == "no") stringResource(R.string.go_free) else stringResource(R.string.go_fee)) }
    }.joinToString(" · ")
    val tags = parkTags(p)
    val capacity = (p.tags["capacity:hgv"] ?: p.tags["capacity"])?.toIntOrNull()
    val name = p.name ?: stringResource(R.string.go_truck_park)
    Row(
        Modifier.fillMaxWidth().height(96.dp)
            .cssBox(bg = if (first) TruckColours.RAISED else TruckColours.TILE, r = CssCorners(20f, 20f, 20f, 20f), shadows = if (first) listOf(CssShadow(0f, 0f, 0f, 2f, TruckColours.AMBER)) else emptyList())
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(d.toString(), name, facts.ifEmpty { null }, capacity?.toString()).joinToString(", ") }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
            BoardText(d.unit, boardType(GEIST, 11f, 800, lineHeight = 14f), TruckColours.SOFT)
            BoardText(d.value, boardType(GEIST, 20f, 900, lineHeight = 26f, tnum = true), TruckColours.TEXT)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BoardText(name, boardType(GEIST, 15f, 800, lineHeight = 19.5f), TruckColours.TEXT)
            if (facts.isNotEmpty()) BoardText(facts, boardType(GEIST, 12f, lineHeight = 15.5f, tnum = true), TruckColours.SOFT)
            if (tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (tag in tags) Box(Modifier.height(22.dp).cssBox(bg = 0x1AFFFFFF, r = CssCorners(11f, 11f, 11f, 11f)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    BoardText(tag, boardType(GEIST, 11f, 700, lineHeight = 14f), TruckColours.TEXT)
                }
            }
        }
        if (capacity != null) Column(horizontalAlignment = Alignment.End) {
            BoardText("$capacity", boardType("VT323, monospace", 30f, lineHeight = 30f), TruckColours.GREEN)
            BoardText(stringResource(R.string.go_spaces_short), boardType(GEIST, 10f, 800, lineHeight = 13f), TruckColours.SOFT)
        }
    }
}

/** Coach bays as the board's coach card (60 px): the coach, the bays' name and how far, and the spaces as mapped. */
@Composable
private fun CoachCard(p: TruckPark, units: Units) {
    val name = p.name ?: stringResource(R.string.go_coach_bays)
    val d = GoFormat.distance(p.metres, units).toString()
    val capacity = (p.tags["capacity:bus"] ?: p.tags["capacity:coach"] ?: p.tags["capacity"])?.toIntOrNull()
    val spaces = capacity?.let { stringResource(R.string.go_spaces, it) }
    Row(
        Modifier.fillMaxWidth().height(60.dp).cssBox(bg = TruckColours.TILE, r = CssCorners(20f, 20f, 20f, 20f))
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, d, spaces).joinToString(", ") }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("mode.bus", Modifier.size(24.dp), tint = argb(TruckColours.TEXT), size = 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardText(name, boardType(GEIST, 14f, 800, lineHeight = 18f), TruckColours.TEXT)
            BoardText(d, boardType(GEIST, 12f, lineHeight = 15.5f, tnum = true), TruckColours.SOFT)
        }
        spaces?.let { BoardText(it, boardType(GEIST, 15f, 900, lineHeight = 19.5f, tnum = true), TruckColours.TEXT) }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardText(stringResource(R.string.go_bridges_around), boardType(GEIST, 12f, 800, lineHeight = 15.5f, spacing = 1.44f), TruckColours.SOFT)
        for ((r, c) in list) {
            val fits = airDraft == null || airDraft <= c
            Row(
                Modifier.fillMaxWidth().height(64.dp).cssBox(bg = TruckColours.TILE, r = CssCorners(20f, 20f, 20f, 20f)).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                RoadSign(SignKind.HEIGHT, GoFormat.size(c, units), size = 42.dp)
                Column(Modifier.weight(1f)) {
                    BoardText(r.tags["seamark:name"] ?: r.name() ?: stringResource(R.string.go_bridge), boardType(GEIST, 15f, 800, lineHeight = 19.5f), TruckColours.TEXT)
                    BoardText(GoFormat.distance(Geo.distance(here, r.location), units).toString(), boardType(GEIST, 12f, lineHeight = 15.5f, tnum = true), TruckColours.SOFT)
                }
                Box(Modifier.size(26.dp).cssBox(bg = if (fits) 0xFF178A4E else 0xFFD93A3A, r = CssCorners(13f, 13f, 13f, 13f)), contentAlignment = Alignment.Center) {
                    Symbol(if (fits) "ui.check" else "ui.close", Modifier.size(15.dp), tint = Color.White, size = 15.dp)
                }
            }
        }
    }
}
