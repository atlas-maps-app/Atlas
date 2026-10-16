package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.model.Mode
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.Units
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Sy
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.rememberSpeechInput
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** The modes in the Go board's grid, five to a row, with Ears only last. */
private val GRID = listOf(GoMode.WALK, GoMode.BIKE, GoMode.CAR, GoMode.EBIKE, GoMode.MOTO, GoMode.TRUCK, GoMode.CAMPER, GoMode.BOAT, GoMode.CARGO)

private const val OVERPASS = "Overpass, sans-serif"
private const val GEIST = "Geist, system-ui, sans-serif"

/**
 * The Go tab, as on the Go board: the night sky with where you are, the weather and whether routing works offline here;
 * "Where to?"; a motorway gantry whose guide signs are home, work and the places you went to, with the time there by
 * your mode and when to leave; quick ways to Ears only, two-wheeler parking and the shady way; "How are you going?" with
 * the modes; "Keep it moving" (fuel, charge, park, bike repair, lockers within reach); and your vehicle and the places
 * you went to as the board's two cards. Every element sits where the board has it; a short screen scrolls.
 */
@Composable
fun GoScreen(args: Dest.Go, nav: Navigator) {
    val services = LocalServices.current
    val vm = viewModel(key = "go") { GoTabViewModel(services) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val speak = rememberSpeechInput(onText = { nav.go(Dest.Search(it)) })
    val zone = services.clock.zone
    val units by services.go.unitsFlow.collectAsStateWithLifecycle()
    val dy = statusShift(36f)
    val bar = LocalBottomBarInset.current

    fun route(p: SavedPlace) = nav.go(Dest.Route(p.at.lat, p.at.lon, p.name.ifBlank { null }, mode = ui.mode.name))

    Column(Modifier.fillMaxSize().background(argb(GoBoardColours.PAPER)).verticalScroll(rememberScrollState())) {
        // The board's 837 px above the tab bar, or more where the bar sits higher; plus any shift for the status bar.
        Box(Modifier.fillMaxWidth().height(max(915.dp, 837.dp + bar) + dy)) {
            Box(Modifier.fillMaxWidth().height(420.dp + dy).drawWithCache { val sky = GoSky.brush(size, density); onDrawBehind { drawRect(sky) } })
            BoardPlane(dy = dy) {
                Sky(ui, zone, onDownload = vm::download, onSearch = { nav.go(Dest.Search()) }, onMic = speak)
                for (i in 0 until GoTabViewModel.MAX_SIGNS) {
                    val sign = ui.signs.getOrNull(i)
                    val x0 = 16f + 196f * i
                    if (sign != null) SignFor(x0, sign, ui.mode, units, zone) { route(sign.place) }
                    else EmptySign(x0, if (i == 0 && ui.signs.isEmpty()) GuideSign.Kind.HOME else GuideSign.Kind.WORK) { nav.go(Dest.Search()) }
                }
                QuickChips(ui, vm, nav)
                Sheet(ui, vm, nav, units)
            }
        }
    }
}

/** The night sky's top: the skyline, "Go" and where you are, the readiness chip, and "Where to?" with the mic. */
@Composable
private fun Sky(ui: GoTabUi, zone: ZoneId, onDownload: () -> Unit, onSearch: () -> Unit, onMic: () -> Unit) {
    ArtAt(BoardArt.skyline, 0f, 250f, 412f, 150f, fill = true)
    Tl(stringResource(R.string.go_title), 16f, 36.25f, 240f, 41f, OVERPASS, 32f, weight = 900, colour = 0xFFFFFFFF, lineHeight = 32f, spacing = -0.32f,
        modifier = Modifier.semantics { heading() })
    Tl(whereLine(ui, zone), 16f, 75.75f, 236f, 15.5f, GEIST, 12f, colour = GoBoardColours.SUB, lineHeight = 15.5f, tnum = true)
    Box(Modifier.offset(0.dp, 49.dp).requiredSize(396.dp, 34.dp), contentAlignment = Alignment.CenterEnd) { ReadinessChip(ui, onDownload) }
    val where = stringResource(R.string.go_where)
    Bx(16f, 102f, 380f, 50f, bg = 0xFFFFFFFF, r = CssCorners(25f, 25f, 25f, 25f), shadows = listOf(CssShadow(0f, 10f, 26f, 0f, 0x4C000000)),
        modifier = Modifier.button(where, onSearch))
    Sy("ui.search", 32f, 117f, 20f, 20f, tint = GoBoardColours.INK)
    Tl(where, 64f, 116f, 274f, 22f, GEIST, 17f, weight = 600, colour = 0xFFA9A9A9, lineHeight = 22f)
    Bx(350f, 107f, 40f, 40f, bg = GoBoardColours.INK, r = CssCorners(20f, 20f, 20f, 20f), modifier = Modifier.button(stringResource(R.string.go_search_voice), onMic))
    Sy("ui.mic", 361f, 118f, 18f, 18f, tint = 0xFFFFFFFF)
    ArtAt(BoardArt.gantry, 0f, 162f, 412f, 44f, fill = true)
}

/** "from Prinsengracht · 12° · dry till 18:40". */
@Composable
private fun whereLine(ui: GoTabUi, zone: ZoneId): String {
    val parts = ArrayList<String>()
    ui.street?.let { parts += stringResource(R.string.go_from_street, it) }
    ui.weather?.let { parts += "${it.temperature.roundToInt()}°" }
    when {
        ui.rainUntil != null -> parts += stringResource(R.string.go_rain_till, GoFormat.clock(ui.rainUntil, zone))
        ui.dryUntil != null -> parts += stringResource(R.string.go_dry_till, GoFormat.clock(ui.dryUntil, zone))
        ui.dryAhead -> parts += stringResource(R.string.go_dry)
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.go_from_here_line) }
}

/** The board's "Offline ready" chip in green when routing works here without a signal; else the segment to get, in amber. */
@Composable
private fun ReadinessChip(ui: GoTabUi, onDownload: () -> Unit) {
    val r = ui.readiness ?: return
    val ready = r is Readiness.Ready
    val text = when {
        ready -> stringResource(R.string.go_offline_ready)
        ui.downloading != null -> stringResource(R.string.go_downloading, (ui.downloading * 100).roundToInt())
        else -> (r as Readiness.Missing).need.label
    }
    val fg = if (ready) GoBoardColours.READY else GoBoardColours.AMBER
    Row(
        Modifier
            .height(34.dp)
            .cssBox(
                bg = if (ready) 0x293EC97A else 0x29FFB547, r = CssCorners(17f, 17f, 17f, 17f),
                shadows = listOf(CssShadow(0f, 0f, 0f, 1f, if (ready) 0x663EC97A else 0x66FFB547, inset = true)),
            )
            .then(if (!ready) Modifier.button(stringResource(R.string.go_download_routing, text), onDownload) else Modifier)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ready) Symbol("ui.offline", Modifier.size(17.dp), tint = argb(fg), size = 17.dp)
        else Symbol("ui.download", Modifier.size(17.dp), tint = argb(fg), size = 17.dp, weight = 2f / 2.6f)
        BoardText(text, boardType(GEIST, 12f, 700, lineHeight = 15.5f), fg)
    }
}

/** One destination as a guide sign: the plate, the name, the arrow and the way there by the chosen mode. */
@Composable
private fun SignFor(x0: Float, sign: GuideSign, mode: Mode, units: Units, zone: ZoneId, onClick: () -> Unit) {
    val timed = sign.leaveAt != null || sign.place.arriveBy != null
    val plate = when {
        sign.leaveAt != null -> stringResource(R.string.go_plate_leave, GoFormat.clock(sign.leaveAt, zone))
        sign.place.arriveBy != null -> stringResource(R.string.go_plate_by, GoFormat.time(sign.place.arriveBy))
        sign.kind == GuideSign.Kind.HOME -> stringResource(R.string.go_plate_home)
        sign.kind == GuideSign.Kind.WORK -> stringResource(R.string.go_plate_work)
        else -> stringResource(R.string.go_plate_last)
    }
    val placeName = sign.place.name.ifBlank { stringResource(R.string.go_destination) }
    // A timed plate says when; the sign then names the kind ("Home") over the place, as the board's "Home / Kent Av".
    val kind = when {
        timed && sign.kind == GuideSign.Kind.HOME -> stringResource(R.string.go_sign_home)
        timed && sign.kind == GuideSign.Kind.WORK -> stringResource(R.string.go_sign_work)
        else -> null
    }
    val time = sign.seconds?.let { signMinutes(it) }
    val dist = GoFormat.distance(sign.metres ?: sign.crowMetres, units).toString()
    val line = listOfNotNull(time, dist).joinToString(" · ")
    GuideSignAt(x0, plate, placeName, sign.bearing.toFloat(), "$plate, ${listOfNotNull(kind, placeName).joinToString(" ")}, $line", onClick, kind) {
        SignFooter(GoMode.of(mode), time, if (time != null) "· $dist" else dist)
    }
}

/** A sign's time as the board writes it: "6 min", or "1 h 05". */
@Composable
private fun signMinutes(seconds: Double): String {
    val m = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (m < 60) stringResource(R.string.go_sign_min, m) else GoFormat.minutes(seconds)
}

/** A sign with nothing on it yet: home or work not set, pointing to the search. */
@Composable
private fun EmptySign(x0: Float, kind: GuideSign.Kind, onClick: () -> Unit) {
    val plate = stringResource(if (kind == GuideSign.Kind.HOME) R.string.go_plate_home else R.string.go_plate_work)
    val name = stringResource(R.string.go_sign_empty)
    val hint = stringResource(R.string.go_sign_empty_hint)
    GuideSignAt(x0, plate, name, null, "$plate, $name, $hint", onClick) {
        BoardText(hint, boardType(OVERPASS, 15f, 600, lineHeight = 18.5f), 0xFFFFFFFF)
    }
}

/** The night sky's row of glass chips: Ears only, two-wheeler parking, and the shady way (on or off). */
@Composable
private fun QuickChips(ui: GoTabUi, vm: GoTabViewModel, nav: Navigator) {
    Row(Modifier.offset(16.dp, 334.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NightChip(stringResource(R.string.go_ears_title), onClick = { nav.go(Dest.Ears) }) {
            Symbol("ui.headphones", Modifier.size(16.dp), tint = argb(0xFFFFFFFF), size = 16.dp)
        }
        NightChip(stringResource(R.string.go_twowheel_title), onClick = { nav.go(Dest.Twowheel(ui.mode.name.takeIf { ui.mode.cycling || ui.mode == Mode.MOTORCYCLE })) }) {
            Symbol("park.bike", Modifier.size(16.dp), tint = argb(0xFFFFFFFF), size = 16.dp)
        }
        val shady = ui.prefs.shade >= 0.5
        NightChip(stringResource(R.string.go_shady_way), on = shady, onClick = {
            vm.setPrefs(ui.prefs.copy(shade = if (shady) 0.0 else 1.0, green = if (shady) 0.0 else maxOf(ui.prefs.green, 0.6)))
        }) {
            // The board's shade bar: the way in shade in blue, then the sun's amber.
            Box(Modifier.size(16.dp, 6.dp).cssBox(grad = listOf(cssLinear(90f, 0.7f to 0xFF3D4BFF, 0.7f to 0xFFFFB020)), r = CssCorners(3f, 3f, 3f, 3f)))
        }
    }
}

/** The sheet: how you are going (the modes, step-free), keep it moving, the two cards, the attribution. */
@Composable
private fun Sheet(ui: GoTabUi, vm: GoTabViewModel, nav: Navigator, units: Units) {
    Bx(0f, 386f, 412f, 529f, bg = GoBoardColours.PAPER, r = CssCorners(28f, 28f, 0f, 0f), shadows = listOf(CssShadow(0f, -8f, 24f, 0f, 0x40000000)))
    Bx(188f, 394f, 36f, 4f, bg = 0x2E14161B, r = CssCorners(2f, 2f, 2f, 2f))
    SheetHeading(stringResource(R.string.go_how), 408.25f)
    Box(Modifier.offset(0.dp, 404.dp).requiredSize(396.dp, 30.dp), contentAlignment = Alignment.CenterEnd) {
        val on = ui.prefs.avoidSteps
        StepFreeChip(stringResource(if (on) R.string.go_step_free_on else R.string.go_step_free), on) { vm.setPrefs(ui.prefs.copy(avoidSteps = !on)) }
    }
    for ((k, m) in GRID.withIndex()) {
        ModeCell(k % 5, k / 5, m.board, m.stroke, stringResource(m.label), ui.mode == m.mode) {
            if (ui.mode == m.mode && hasVehicleForm(m.mode)) nav.go(Dest.Vehicle(m.mode.name)) else vm.setMode(m.mode)
        }
    }
    ModeCell(4, 1, "ui.headphones", 2f, stringResource(R.string.go_ears_short), false) { nav.go(Dest.Ears) }

    SheetHeading(stringResource(R.string.go_keep_moving), 596f)
    for ((i, kind) in Moving.entries.withIndex()) {
        val c = ui.moving?.firstOrNull { it.kind == kind }
        val value = when {
            ui.moving == null -> "…"
            c == null || c.count == 0 -> stringResource(R.string.go_none_near)
            else -> GoFormat.distance(c.nearestM ?: 0.0, units).toString()
        }
        ServiceSignAt(i, movingSymbol(kind), stringResource(movingLabel(kind)), value) {
            when (kind) {
                Moving.FUEL -> nav.go(Dest.Pump(ui.here.lat, ui.here.lon))
                Moving.CHARGE -> nav.go(Dest.Charge(ui.here.lat, ui.here.lon))
                Moving.PARK -> nav.go(Dest.Category("parking"))
                Moving.BIKE_FIX, Moving.LOCKERS -> nav.go(Dest.Twowheel())
            }
        }
    }

    // The board's two cards: your vehicle when the mode has one to set, then the places you went to.
    var slot = 0
    if (hasVehicleForm(ui.mode)) {
        VehicleCard(16f, ui.mode, units) { nav.go(Dest.Vehicle(ui.mode.name)) }
        slot++
    }
    for (p in ui.recent) {
        if (slot >= 2) break
        RecentCard(16f + 194f * slot, p, ui, units) { nav.go(Dest.Route(p.at.lat, p.at.lon, p.name.ifBlank { null }, mode = ui.mode.name)) }
        slot++
    }
    Tl(stringResource(R.string.go_attribution), 20f, 790f, 372f, 29.7f, GEIST, 11f, colour = GoBoardColours.FOOT, lineHeight = 14.85f, lines = 2)
}

/** True for the modes whose vehicle has dimensions or a battery worth entering. */
internal fun hasVehicleForm(mode: Mode) = mode in setOf(Mode.TRUCK, Mode.CAMPER, Mode.BOAT, Mode.SAIL, Mode.CAR, Mode.EBIKE)

/** "Your truck · 16.5 m · 4.0 m · 40 t": the vehicle the routes respect, as a card, to change it. */
@Composable
private fun VehicleCard(x: Float, mode: Mode, units: Units, onClick: () -> Unit) {
    val services = LocalServices.current
    val v by services.go.store.vehicle(mode).collectAsStateWithLifecycle(GoStore.defaultVehicle(mode))
    val facts = VehicleFacts.short(mode, v, units).ifEmpty { stringResource(R.string.go_vehicle_unset) }
    val m = GoMode.of(mode)
    val label = stringResource(R.string.go_your_vehicle, stringResource(m.label))
    GoCardAt(x, label, facts, "$label, $facts", onClick) {
        Box(Modifier.requiredSize(28.dp).cssBox(bg = GoBoardColours.BLUE, r = CssCorners(14f, 14f, 14f, 14f)), contentAlignment = Alignment.Center) {
            Symbol(m.board, Modifier.size(17.dp), tint = argb(0xFFFFFFFF), size = 17.dp)
        }
    }
}

/** A place you went to as the board's white card: its initial on a green badge, the name, and how far and when. */
@Composable
private fun RecentCard(x: Float, p: SavedPlace, ui: GoTabUi, units: Units, onClick: () -> Unit) {
    val d = GoFormat.distance(app.atlas.geo.Geo.distance(ui.here, p.at), units).toString()
    val name = p.name.ifBlank { stringResource(R.string.go_destination) }
    val sub = d + (p.usedAt.takeIf { it > 0 }?.let { " · " + ago(Instant.ofEpochMilli(it)) } ?: "")
    GoCardAt(x, name, sub, "$name, $sub", onClick) { LetterBadge(name.take(1).uppercase(), GoBoardColours.GREEN) }
}

/** The board symbol of each service sign; parking is its "P" (null). */
internal fun movingSymbol(kind: Moving): String? = when (kind) {
    Moving.FUEL -> "place.fuel"
    Moving.CHARGE -> "ui.charge"
    Moving.PARK -> null
    Moving.BIKE_FIX -> "ui.wrench"
    Moving.LOCKERS -> "fact.locker"
}

internal fun movingLabel(kind: Moving): Int = when (kind) {
    Moving.FUEL -> R.string.go_fuel
    Moving.CHARGE -> R.string.go_charge
    Moving.PARK -> R.string.go_park
    Moving.BIKE_FIX -> R.string.go_bike_fix
    Moving.LOCKERS -> R.string.go_lockers
}

/** "today", "yesterday", or the day it was ("Mon"). */
@Composable
private fun ago(t: Instant): String {
    val services = LocalServices.current
    val zone = services.clock.zone
    val today = services.clock.instant().atZone(zone).toLocalDate()
    val day = t.atZone(zone).toLocalDate()
    return when {
        day == today -> stringResource(R.string.go_today)
        day == today.minusDays(1) -> stringResource(R.string.go_yesterday)
        else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    }
}
