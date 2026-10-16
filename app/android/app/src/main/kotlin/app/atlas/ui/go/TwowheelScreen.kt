package app.atlas.ui.go

import androidx.compose.foundation.background
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.Argb
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.model.Mode
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.walkMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Twowheel board's colours (canvas/project/Twowheel.dc.html). */
private object TwoColours {
    const val BG: Argb = 0xFFEAF1EC
    const val INK: Argb = 0xFF15251C
    const val COUNT: Argb = 0xFF6A7E70
    const val SUB: Argb = 0xFF5A6E60
    const val FREE: Argb = 0xFF12663A
    const val TIP_BG: Argb = 0xFFFFF1D6
    const val TIP_TEXT: Argb = 0xFF6A4A00
    const val NOTE: Argb = 0xFF7A8E80
}

private const val GEIST = "Geist, system-ui, sans-serif"

/** The kinds in the board's grid, two rows of four. */
private val GRID_KINDS = listOf(ParkKind.STANDS, ParkKind.COVERED, ParkKind.LOCKERS, ParkKind.GARAGE, ParkKind.CARGO, ParkKind.MOTORBIKE, ParkKind.SCOOTER, ParkKind.CHARGE)

private val TWO_WHEELS = listOf(Mode.BIKE, Mode.EBIKE, Mode.CARGO_BIKE, Mode.MOTORCYCLE)
private const val RADIUS_M = 600.0

/**
 * Two wheels, as on the Twowheel board, each element where the board has it: where to park, lock or charge a bike, an
 * e-bike, a cargo bike or a motorbike around you. The vehicle row; the nearest suitable spot as the board's drawn rack
 * of Sheffield stands with its capacity and whether it is covered; the counts by kind (stands, covered, lockers, guarded
 * garages, cargo, motorbike bays, scooters, chargers); the nearest spots with what they offer, and the nearest repair
 * stand; a tip for locking up. From the offline place store, else the map. Capacities are as mapped: no live counts.
 */
@Composable
fun TwowheelScreen(args: Dest.Twowheel, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    var mode by rememberSaveable { mutableStateOf(args.mode?.let { n -> runCatching { Mode.valueOf(n) }.getOrNull() }?.takeIf { it in TWO_WHEELS } ?: Mode.BIKE) }
    val places by produceState<List<app.atlas.model.Place>?>(null, here) { value = services.go.placesAround(here, RADIUS_M) }
    val ui = places?.let { Twowheel.around(it, here, mode) }
    val units by services.go.unitsFlow.collectAsStateWithLifecycle()
    // The street each of the nearest spots is on, from the map (most stands have no name of their own).
    val shown = ui?.spots?.take(4).orEmpty() + listOfNotNull(ui?.repair)
    val streets by produceState(emptyMap<String, String>(), shown) {
        value = withContext(Dispatchers.IO) {
            val namer = TileWayNamer({ services.tiles.value })
            shown.mapNotNull { s -> namer.nearest(s.place.location, 40.0)?.let { s.place.key to it } }.toMap()
        }
    }

    BoardPage(TwoColours.BG, stringResource(R.string.go_twowheel_source), TwoColours.NOTE) {
        BoardHeader(stringResource(R.string.go_twowheel_title), dark = false, onBack = { nav.back() })
        Spacer(Modifier.height(12.dp))
        VehicleRow(TWO_WHEELS, mode, dark = false) { mode = it }
        Spacer(Modifier.height(16.dp))
        when {
            ui == null -> StateLine(stringResource(R.string.go_looking))
            ui.counts.isEmpty() -> StateLine(stringResource(R.string.go_twowheel_none), stringResource(R.string.go_twowheel_none_text))
            else -> {
                ui.spots.firstOrNull()?.let {
                    RackCard(it, mode, units) { openSpot(nav, it) }
                    Spacer(Modifier.height(10.dp))
                }
                KindGrid(ui.counts)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in ui.spots.take(4)) SpotRow(s, streets[s.place.key]) { openSpot(nav, s) }
                    ui.repair?.let { r -> SpotRow(r, streets[r.place.key]) { openSpot(nav, r) } }
                    if (ui.spots.isEmpty()) StateLine(stringResource(R.string.go_twowheel_none_for))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TipCard(stringResource(if (mode == Mode.MOTORCYCLE) R.string.go_tip_moto else R.string.go_tip_bike))
    }
}

/**
 * A page of the parking boards (Twowheel, Truck): [content] in a column 16 px in from the sides, from the board's
 * 42 px down (below a taller status bar), scrolling when it is long; the [note] centred 14 px above the bottom.
 */
@Composable
internal fun BoardPage(bg: Argb, note: String, noteColour: Argb, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(argb(bg))) {
        val top = 42.dp + statusShift(42f)
        val bottom = 14.dp + navShift(901f)
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = top, bottom = bottom + 26.dp + 24.dp).fillMaxWidth(), content = content)
            BoardText(note, boardType(GEIST, 10f, lineHeight = 13f, align = TextAlign.Center), noteColour,
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottom).fillMaxWidth(), lines = 2)
        }
    }
}

private fun openSpot(nav: Navigator, s: ParkSpot) {
    nav.go(Dest.Place(s.place.location.lat, s.place.location.lon, s.place.id?.toString(), s.place.name()))
}

@Composable
private fun StateLine(title: String, text: String? = null) {
    Column(Modifier.fillMaxWidth().cssBox(bg = 0xFFFFFFFF, r = CssCorners(20f, 20f, 20f, 20f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoardText(title, boardType(GEIST, 15f, 800, lineHeight = 19.5f), TwoColours.INK)
        text?.let { BoardText(it, boardType(GEIST, 12f, lineHeight = 15.5f), TwoColours.SUB, lines = 3) }
    }
}

/**
 * The nearest spot as the board's rack card (170 px, pale green): where and whether covered, its capacity, and the
 * rack of Sheffield stands.
 */
@Composable
private fun RackCard(s: ParkSpot, mode: Mode, units: app.atlas.services.Units, onClick: () -> Unit) {
    val where = if (s.metres < 60) stringResource(R.string.go_right_here) else GoFormat.distance(s.metres, units).toString()
    val title = listOfNotNull(where, stringResource(R.string.go_covered).takeIf { s.covered }).joinToString(" · ")
    val cap = s.capacity?.let { stringResource(R.string.go_spaces, it) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFF7FAF8, 1f to 0xFFDDE8E1)), r = CssCorners(26f, 26f, 26f, 26f))
            .button(listOfNotNull(title, cap).joinToString(", "), onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardText(title, boardType(GEIST, 15f, 800, lineHeight = 19.5f), TwoColours.INK, Modifier.weight(1f).alignByBaseline())
            cap?.let { BoardText(it, boardType(GEIST, 13f, 900, lineHeight = 17f, tnum = true), TwoColours.FREE, Modifier.alignByBaseline()) }
        }
        // The board's rack, without its "free" highlights: the capacity is as mapped, not live.
        // An SVG clips its drawing to its box: the last bike is cut at the rack's end, as on the board.
        ArtBox(if (mode == Mode.MOTORCYCLE) MOTO_RACK else FREE_RACK, Modifier.fillMaxWidth().height(130.dp).clipToBounds())
    }
}

private val FREE_RACK: Art by lazy { BoardArt.rack.without(10, 16) }

/** For motorbikes: the rack's rail and ground with the board's motorbike glyph in three bays. */
private val MOTO_RACK: Art by lazy {
    val r = BoardArt.rack
    val moto = BoardArt.vMoto
    Art(r.x, r.y, r.w, r.h, listOf(r.parts[0], r.parts[1]) + listOf(70f, 174f, 278f).flatMap { cx ->
        moto.parts.map { p -> ArtPart(p.d, p.fill, 0xFFC8322A, 1.9f, p.cap, p.join, p.dash, p.alpha, null, cx - 36f, 48f) }
    } + r.parts.last())
}

/** The counts by kind: the board's grid of eight white tiles (76 px, 8 apart), the symbol over the name and count. */
@Composable
private fun KindGrid(counts: Map<ParkKind, Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in GRID_KINDS.chunked(4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in row) {
                    val n = counts[k] ?: 0
                    val label = stringResource(kindLabel(k))
                    Column(
                        Modifier
                            .weight(1f)
                            .height(76.dp)
                            .cssBox(bg = 0xFFFFFFFF, r = CssCorners(18f, 18f, 18f, 18f))
                            .semantics(mergeDescendants = true) { contentDescription = "$label $n" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Symbol(k.symbol, Modifier.size(24.dp), tint = Color(k.colour).copy(alpha = if (n == 0) 0.35f else 1f), size = 24.dp, weight = strokeTo(k.symbol, 2f))
                        Row {
                            val type = boardType("Geist, sans-serif", 12f, 800, lineHeight = 15.5f, tnum = true)
                            BoardText(label, type, if (n == 0) 0x7315251C else TwoColours.INK)
                            BoardText(" $n", type, TwoColours.COUNT)
                        }
                    }
                }
            }
        }
    }
}

internal fun kindLabel(k: ParkKind): Int = when (k) {
    ParkKind.STANDS -> R.string.go_kind_stands
    ParkKind.COVERED -> R.string.go_kind_covered
    ParkKind.LOCKERS -> R.string.go_kind_lockers
    ParkKind.GARAGE -> R.string.go_kind_garage
    ParkKind.CARGO -> R.string.go_kind_cargo
    ParkKind.MOTORBIKE -> R.string.go_kind_motorbike
    ParkKind.SCOOTER -> R.string.go_kind_scooter
    ParkKind.CHARGE -> R.string.go_kind_charge
    ParkKind.REPAIR -> R.string.go_kind_repair
}

/** Which kind names a spot that is several: a garage before lockers before a shed before plain stands. */
private val PRIORITY = listOf(ParkKind.GARAGE, ParkKind.MOTORBIKE, ParkKind.LOCKERS, ParkKind.CARGO, ParkKind.SCOOTER, ParkKind.COVERED, ParkKind.STANDS, ParkKind.REPAIR)

internal fun kindTitle(k: ParkKind): Int = when (k) {
    ParkKind.STANDS -> R.string.go_title_stands
    ParkKind.COVERED -> R.string.go_title_covered
    ParkKind.LOCKERS -> R.string.go_title_lockers
    ParkKind.GARAGE -> R.string.go_title_garage
    ParkKind.CARGO -> R.string.go_title_cargo
    ParkKind.MOTORBIKE -> R.string.go_title_motorbike
    ParkKind.SCOOTER -> R.string.go_title_scooter
    ParkKind.CHARGE -> R.string.go_kind_charge
    ParkKind.REPAIR -> R.string.go_title_repair
}

/**
 * One spot as the board's white row (72 px, 20 px corners): its kind's coloured 46 px tile, its name (or kind), what it
 * offers, and the walk.
 */
@Composable
private fun SpotRow(s: ParkSpot, street: String?, onClick: () -> Unit) {
    val main = PRIORITY.firstOrNull { it in s.kinds } ?: s.kinds.first()
    val name = s.place.name() ?: stringResource(kindTitle(main))
    val facts = buildList {
        street?.let { add(it) }
        if (s.supervised && main != ParkKind.GARAGE) add(stringResource(R.string.go_guarded))
        if (s.covered && main !in setOf(ParkKind.COVERED, ParkKind.GARAGE, ParkKind.LOCKERS)) add(stringResource(R.string.go_covered))
        if (s.lit) add(stringResource(R.string.go_lit))
        s.capacity?.let { add(stringResource(R.string.go_spaces, it)) }
        when (s.fee) {
            true -> add(stringResource(R.string.go_fee))
            false -> add(stringResource(R.string.go_free))
            null -> Unit
        }
    }.joinToString(" · ")
    val walk = walkMinutes(s.metres / 1.3)
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .cssBox(bg = 0xFFFFFFFF, r = CssCorners(20f, 20f, 20f, 20f))
            .button("$name, $facts, $walk", onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).cssBox(bg = main.colour, r = CssCorners(14f, 14f, 14f, 14f)), contentAlignment = Alignment.Center) {
            Symbol(main.symbol, Modifier.size(24.dp), tint = Color.White, size = 24.dp, weight = strokeTo(main.symbol, 2f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BoardText(name, boardType(GEIST, 15f, 800, lineHeight = 19.5f), TwoColours.INK)
            if (facts.isNotEmpty()) BoardText(facts, boardType(GEIST, 12f, lineHeight = 15.5f, tnum = true), TwoColours.SUB)
        }
        BoardText(walk, boardType(GEIST, 16f, 900, lineHeight = 20.5f, tnum = true), TwoColours.INK)
    }
}

/** The board's amber tip (56 px): the padlock and the sentence. */
@Composable
internal fun TipCard(text: String) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).cssBox(bg = TwoColours.TIP_BG, r = CssCorners(18f, 18f, 18f, 18f)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("fact.lock", Modifier.size(26.dp), tint = Color(0xFF8A5A00), size = 26.dp, weight = strokeTo("fact.lock", 2.2f))
        BoardText(text, boardType(GEIST, 13f, 700, lineHeight = 17f), TwoColours.TIP_TEXT, lines = 2)
    }
}

/** The boards' header (Truck, Twowheel): the 44 px round back button and the title in italic Instrument Serif, 32 px. */
@Composable
internal fun BoardHeader(title: String, dark: Boolean, onBack: () -> Unit) {
    val ink: Argb = if (dark) 0xFFEDEFF3 else 0xFF15251C
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(44.dp).cssBox(bg = if (dark) 0x1AFFFFFF else 0xFFFFFFFF, r = CssCorners(22f, 22f, 22f, 22f)).button(stringResource(R.string.kit_back), onBack),
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.back", Modifier.size(20.dp), tint = argb(ink), size = 20.dp) }
        BoardText(title, boardType("Instrument Serif, serif", 32f, italic = true, lineHeight = 32f), ink, Modifier.weight(1f).semantics { heading() })
    }
}

/** The board symbol of a vehicle in the parking boards' row. */
internal fun vehicleSymbol(m: Mode): String = when (m) {
    Mode.CAR -> "mode.car"
    Mode.MOTORCYCLE, Mode.MOPED -> "mode.moto"
    Mode.BIKE -> "park.bike"
    Mode.EBIKE -> "mode.ebike"
    Mode.CARGO_BIKE -> "mode.cargo-bike"
    Mode.CAMPER -> "mode.camper"
    Mode.TRUCK -> "mode.truck"
    Mode.BOAT, Mode.SAIL -> "mode.boat"
    else -> GoMode.of(m).board
}

/** The parking boards' row of vehicle discs (38 px, 4.75 px apart as the board's nine), [selected] lit. */
@Composable
internal fun VehicleRow(modes: List<Mode>, selected: Mode, dark: Boolean, label: @Composable (Mode) -> String = { stringResource(GoMode.of(it).label) }, onPick: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.75.dp)) {
        for (m in modes) VehicleDisc(vehicleSymbol(m), label(m), m == selected, dark) { onPick(m) }
    }
}

/**
 * A round vehicle button of the Truck and Twowheel boards' top row: 38 px with a soft shadow; on the light board white
 * at 94 % with the chosen one ink, on the dark one smoke with the chosen one white.
 */
@Composable
internal fun VehicleDisc(symbol: String, label: String, selected: Boolean, dark: Boolean = false, onClick: () -> Unit) {
    val bg: Argb = when {
        dark && selected -> 0xFFFFFFFF
        dark -> 0x1FFFFFFF
        selected -> 0xFF14161B
        else -> 0xF0FFFFFF
    }
    val fg: Argb = when {
        dark && selected -> 0xFF14161B
        dark -> 0xFFF2F2F2
        selected -> 0xFFFFFFFF
        else -> 0xFF14161B
    }
    Box(
        Modifier
            .size(38.dp)
            .cssBox(bg = bg, r = CssCorners(19f, 19f, 19f, 19f), shadows = listOf(CssShadow(0f, 3f, 10f, 0f, 0x2414161B)))
            .button(label, onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, Modifier.size(21.dp), tint = argb(fg), size = 21.dp, weight = strokeTo(symbol, 1.9f))
    }
}
