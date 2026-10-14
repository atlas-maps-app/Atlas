package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.model.Mode
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.softShadow
import app.atlas.ui.kit.walkMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Twowheel board's colours (canvas/project/Twowheel.dc.html). */
private object TwoColours {
    val bg = Color(0xFFEAF1EC)
    val ink = Color(0xFF15251C)
    val count = Color(0xFF6A7E70)
    val sub = Color(0xFF5A6E60)
    val free = Color(0xFF12663A)
    val tipBg = Color(0xFFFFF1D6)
    val tipText = Color(0xFF6A4A00)
    val note = Color(0xFF7A8E80)
}

private val TwoType = androidx.compose.ui.text.TextStyle(fontFamily = InterFamily)

/** The kinds in the board's grid, two rows of four. */
private val GRID_KINDS = listOf(ParkKind.STANDS, ParkKind.COVERED, ParkKind.LOCKERS, ParkKind.GARAGE, ParkKind.CARGO, ParkKind.MOTORBIKE, ParkKind.SCOOTER, ParkKind.CHARGE)

private val TWO_WHEELS = listOf(Mode.BIKE, Mode.EBIKE, Mode.CARGO_BIKE, Mode.MOTORCYCLE)
private const val RADIUS_M = 600.0

/**
 * Two wheels, as on the Twowheel board: where to park, lock or charge a bike, an e-bike, a cargo bike or a motorbike
 * around you. The vehicle row; the nearest suitable spot as the board's drawn rack of Sheffield stands with its capacity
 * and whether it is covered; the counts by kind (stands, covered, lockers, guarded garages, cargo, motorbike bays,
 * scooters, chargers); the nearest spots with what they offer, and the nearest repair stand; a tip for locking up. From
 * the offline place store, else the map. Capacities are as mapped: there are no live counts.
 */
@Composable
fun TwowheelScreen(args: Dest.Twowheel, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    var mode by rememberSaveable { mutableStateOf(args.mode?.let { n -> runCatching { Mode.valueOf(n) }.getOrNull() }?.takeIf { it in TWO_WHEELS } ?: Mode.BIKE) }
    val places by produceState<List<app.atlas.model.Place>?>(null, here) { value = services.go.placesAround(here, RADIUS_M) }
    val ui = places?.let { Twowheel.around(it, here, mode) }
    val units = services.go.units
    // The street each of the nearest spots is on, from the map (most stands have no name of their own).
    val shown = ui?.spots?.take(4).orEmpty() + listOfNotNull(ui?.repair)
    val streets by produceState(emptyMap<String, String>(), shown) {
        value = withContext(Dispatchers.IO) {
            val namer = TileWayNamer({ services.tiles.value })
            shown.mapNotNull { s -> namer.nearest(s.place.location, 40.0)?.let { s.place.key to it } }.toMap()
        }
    }

    Column(
        Modifier.fillMaxSize().background(TwoColours.bg).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoardHeader(stringResource(R.string.go_twowheel_title), dark = false, onBack = { nav.back() })
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in TWO_WHEELS) {
                val gm = GoMode.of(m)
                VehicleDisc(gm.symbol, stringResource(gm.label), m == mode) { mode = m }
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            ui == null -> StateLine(stringResource(R.string.go_looking))
            ui.counts.isEmpty() -> StateLine(stringResource(R.string.go_twowheel_none), stringResource(R.string.go_twowheel_none_text))
            else -> {
                ui.spots.firstOrNull()?.let { RackCard(it, mode, units) { openSpot(nav, it) } }
                KindGrid(ui.counts)
                for (s in ui.spots.take(4)) SpotRow(s, streets[s.place.key]) { openSpot(nav, s) }
                ui.repair?.let { r -> SpotRow(r, streets[r.place.key]) { openSpot(nav, r) } }
                if (ui.spots.isEmpty()) StateLine(stringResource(R.string.go_twowheel_none_for))
            }
        }
        TipCard(stringResource(if (mode == Mode.MOTORCYCLE) R.string.go_tip_moto else R.string.go_tip_bike))
        Text(stringResource(R.string.go_twowheel_source), style = TwoType.copy(fontSize = 10.sp), color = TwoColours.note, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
}

private fun openSpot(nav: Navigator, s: ParkSpot) {
    nav.go(Dest.Place(s.place.location.lat, s.place.location.lon, s.place.id?.toString(), s.place.name()))
}

@Composable
private fun StateLine(title: String, text: String? = null) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = TwoType.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = TwoColours.ink)
        text?.let { Text(it, style = TwoType.copy(fontSize = 12.sp), color = TwoColours.sub) }
    }
}

/** The nearest spot as the board's rack card: where and whether covered, its capacity, and the rack of Sheffield stands. */
@Composable
private fun RackCard(s: ParkSpot, mode: Mode, units: app.atlas.services.Units, onClick: () -> Unit) {
    val where = if (s.metres < 60) stringResource(R.string.go_right_here) else GoFormat.distance(s.metres, units).toString()
    val title = listOfNotNull(where, stringResource(R.string.go_covered).takeIf { s.covered }).joinToString(" · ")
    val cap = s.capacity?.let { stringResource(R.string.go_spaces, it) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFF7FAF8), Color(0xFFDDE8E1))))
            .button(listOfNotNull(title, cap).joinToString(", "), onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TwoType.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = TwoColours.ink, modifier = Modifier.weight(1f))
            cap?.let { Text(it, style = TwoType.copy(fontSize = 13.sp, fontWeight = FontWeight.Black), color = TwoColours.free) }
        }
        // The board's rack, without its "free" highlights: the capacity is as mapped, not live.
        ArtBox(if (mode == Mode.MOTORCYCLE) MOTO_RACK else FREE_RACK, Modifier.fillMaxWidth().height(130.dp))
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

/** The counts by kind, the board's grid of eight white tiles. */
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
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .semantics(mergeDescendants = true) { contentDescription = "$label $n" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        GoSymbol(k.symbol, tint = Color(k.colour).copy(alpha = if (n == 0) 0.35f else 1f), size = 24.dp, weight = 2f / 1.8f)
                        Row {
                            Text(label, style = TwoType.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold), color = TwoColours.ink.copy(alpha = if (n == 0) 0.45f else 1f), maxLines = 1)
                            Text(" $n", style = TwoType.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"), color = TwoColours.count)
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

/** One spot as the board's white row: its kind's coloured tile, its name (or kind), what it offers, and the walk. */
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
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .button("$name, $facts, $walk", onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color(main.colour)), contentAlignment = Alignment.Center) {
            GoSymbol(main.symbol, tint = Color.White, size = 24.dp, weight = 2f / 1.8f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = TwoType.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = TwoColours.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) Text(facts, style = TwoType.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = TwoColours.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(walk, style = TwoType.copy(fontSize = 16.sp, fontWeight = FontWeight.Black, fontFeatureSettings = "tnum"), color = TwoColours.ink)
    }
}

/** The board's amber tip: a padlock and the sentence. */
@Composable
internal fun TipCard(text: String) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(TwoColours.tipBg).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(BoardArt.lock, size = 26.dp)
        Text(text, style = TwoType.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = TwoColours.tipText, maxLines = 2)
    }
}

/**
 * A round vehicle button of the Truck and Twowheel boards' top row: 38 dp, white glass with the chosen one ink on the
 * light board, or smoke with the chosen one white on the dark one.
 */
@Composable
internal fun VehicleDisc(symbol: String, label: String, selected: Boolean, dark: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        dark && selected -> Color.White
        dark -> Color.White.copy(alpha = 0.12f)
        selected -> Color(0xFF14161B)
        else -> Color.White.copy(alpha = 0.94f)
    }
    val fg = when {
        dark && selected -> Color(0xFF14161B)
        dark -> Color(0xFFF2F2F2)
        selected -> Color.White
        else -> Color(0xFF14161B)
    }
    Box(
        Modifier
            .size(38.dp)
            .softShadow(CircleShape, Color(0x2414161B), 6.dp)
            .clip(CircleShape)
            .background(bg)
            .button(label, onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        GoSymbol(symbol, tint = fg, size = 21.dp, weight = 1.9f / 1.8f)
    }
}
