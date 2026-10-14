package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.model.Mode
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.Units
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.softShadow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** The modes in the Go board's order, five to a row, with Ears only last. */
private val GRID = listOf(GoMode.WALK, GoMode.BIKE, GoMode.CAR, GoMode.EBIKE, GoMode.MOTO, GoMode.TRUCK, GoMode.CAMPER, GoMode.BOAT, GoMode.CARGO)

/**
 * The Go tab, as on the Go board: the night sky with where you are, the weather and whether routing works offline here;
 * "Where to?"; a motorway gantry whose guide signs are home, work and the places you went to, with the time there by
 * your mode and when to leave; quick ways to Ears only, two-wheeler parking and the shady way; "How are you going?" with
 * the modes; "Keep it moving" (fuel, charge, park, bike repair, lockers within reach); and the places you went to.
 */
@Composable
fun GoScreen(args: Dest.Go, nav: Navigator) {
    val services = LocalServices.current
    val vm = viewModel(key = "go") { GoTabViewModel(services) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val speak = rememberSpeechInput(onText = { nav.go(Dest.Search(it)) })
    val zone = services.clock.zone
    val units = services.go.units

    fun route(p: SavedPlace) = nav.go(Dest.Route(p.at.lat, p.at.lon, p.name.ifBlank { null }, mode = ui.mode.name))

    Column(Modifier.fillMaxSize().background(SignColours.paper).verticalScroll(rememberScrollState())) {
        // ---- The night sky: where you are, where to, the gantry, the quick chips --------------------------------------
        Column(Modifier.fillMaxWidth().background(GoHeaderColours).windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 36.dp)) {
            Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.go_title), Modifier.semantics { heading() },
                        style = SignType.copy(fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp), color = Color.White,
                    )
                    Text(whereLine(ui, zone), style = SignType.copy(fontSize = 12.sp, lineHeight = 15.sp), color = SignColours.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp))
                }
                Spacer(Modifier.width(10.dp))
                ReadinessChip(ui, onDownload = vm::download)
            }
            Spacer(Modifier.height(14.dp))
            SearchPill(onSearch = { nav.go(Dest.Search()) }, onMic = speak, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(158.dp)) {
                // The city behind, its feet hidden under the sheet; the truss; the signs hanging from it.
                ArtBox(BoardArt.skyline, Modifier.offset(y = 88.dp).fillMaxWidth().height(150.dp), fill = true)
                GantryTruss()
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val signs = ui.signs
                    for (i in 0 until GoTabViewModel.MAX_SIGNS) {
                        val sign = signs.getOrNull(i)
                        if (sign != null) SignFor(sign, ui.mode, units, zone, Modifier.weight(1f).button(null) { route(sign.place) })
                        else EmptySign(if (i == 0 && signs.isEmpty()) GuideSign.Kind.HOME else GuideSign.Kind.WORK, Modifier.weight(1f).button(null) { nav.go(Dest.Search()) })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NightChip(stringResource(R.string.go_ears_title), onClick = { nav.go(Dest.Ears) }) { GoSymbol("ui-headphones", tint = Color.White, size = 16.dp) }
                NightChip(stringResource(R.string.go_twowheel_title), onClick = { nav.go(Dest.Twowheel(ui.mode.name.takeIf { ui.mode.cycling || ui.mode == Mode.MOTORCYCLE })) }) {
                    GoSymbol("go-bike", tint = Color.White, size = 16.dp)
                }
                val shady = ui.prefs.shade >= 0.5
                NightChip(stringResource(R.string.go_shady_way), on = shady, onClick = {
                    vm.setPrefs(ui.prefs.copy(shade = if (shady) 0.0 else 1.0, green = if (shady) 0.0 else maxOf(ui.prefs.green, 0.6)))
                }) {
                    // The board's shade bar: the share of the way in shade, blue, then the sun's amber.
                    Box(Modifier.size(width = 16.dp, height = 6.dp).clip(RoundedCornerShape(3.dp))
                        .background(Brush.horizontalGradient(0f to Color(0xFF3D4BFF), 0.7f to Color(0xFF3D4BFF), 0.7f to Color(0xFFFFB020), 1f to Color(0xFFFFB020))))
                }
            }
        }

        // ---- The sheet: how, keep it moving, the places you went to --------------------------------------------------
        Column(
            Modifier
                .offset(y = (-18).dp)
                .fillMaxWidth()
                .softShadow(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), Color(0x40000000), 16.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(SignColours.paper),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color(0x2E14161B)))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(horizontal = 16.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.go_how), style = SignType.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold), color = SignColours.ink,
                    modifier = Modifier.weight(1f).semantics { heading() })
                StepFreeChip(ui.prefs.avoidSteps) { vm.setPrefs(ui.prefs.copy(avoidSteps = !ui.prefs.avoidSteps)) }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val cells: List<GoMode?> = GRID + listOf(null)
                for (row in cells.chunked(5)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (m in row) {
                            if (m != null) ModeButton(m.symbol, stringResource(m.label), ui.mode == m.mode, onClick = {
                                if (ui.mode == m.mode && hasVehicleForm(m.mode)) nav.go(Dest.Vehicle(m.mode.name)) else vm.setMode(m.mode)
                            }, modifier = Modifier.weight(1f))
                            else ModeButton("ui-headphones", stringResource(R.string.go_ears_short), false, onClick = { nav.go(Dest.Ears) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (hasVehicleForm(ui.mode)) {
                Spacer(Modifier.height(12.dp))
                VehicleRow(ui.mode, onClick = { nav.go(Dest.Vehicle(ui.mode.name)) })
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.go_keep_moving), style = SignType.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold), color = SignColours.ink,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
            Spacer(Modifier.height(8.dp))
            KeepMoving(ui, units, onPick = { kind ->
                when (kind) {
                    Moving.FUEL -> nav.go(Dest.Category("fuel"))
                    Moving.CHARGE -> nav.go(Dest.Charge(ui.here.lat, ui.here.lon))
                    Moving.PARK -> nav.go(Dest.Category("parking"))
                    Moving.BIKE_FIX, Moving.LOCKERS -> nav.go(Dest.Twowheel())
                }
            })
            if (ui.recent.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                for (pair in ui.recent.take(4).chunked(2)) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in pair) RecentCard(p, ui, units, Modifier.weight(1f)) { route(p) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.go_attribution), style = SignType.copy(fontSize = 11.sp, lineHeight = 15.sp), color = Color(0xFF5A5F6C),
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(LocalBottomBarInset.current + 16.dp))
        }
    }
}

/** True for the modes whose vehicle has dimensions or a battery worth entering. */
internal fun hasVehicleForm(mode: Mode) = mode in setOf(Mode.TRUCK, Mode.CAMPER, Mode.BOAT, Mode.SAIL, Mode.CAR, Mode.EBIKE)

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
    val colour = if (ready) Color(0xFF7FE0A8) else NavColours.amber
    val base = if (ready) Color(0xFF3EC97A) else NavColours.amber
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(base.copy(alpha = 0.16f))
            .border(1.dp, base.copy(alpha = 0.4f), CircleShape)
            .then(if (!ready) Modifier.button(stringResource(R.string.go_download_routing, text), onDownload) else Modifier)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(if (ready) BoardArt.cloudCheck else BoardArt.cloudCheck.without(1), size = 17.dp, tint = colour)
        Text(text, style = SignType.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = colour, maxLines = 1)
    }
}

/** The white "Where to?" pill with the black mic button, as on the board. */
@Composable
private fun SearchPill(onSearch: () -> Unit, onMic: () -> Unit, modifier: Modifier = Modifier) {
    val where = stringResource(R.string.go_where)
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .softShadow(CircleShape, Color(0x4D000000), 14.dp)
            .clip(CircleShape)
            .background(Color.White)
            .button(where, onSearch)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtIcon(BoardArt.search, size = 20.dp, tint = SignColours.ink)
        Text(where, style = SignType.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFF8A8F98), modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).background(SignColours.ink).button(stringResource(R.string.go_search_voice), onMic), contentAlignment = Alignment.Center) {
            ArtIcon(BoardArt.mic, size = 18.dp, tint = Color.White)
        }
    }
}

/** One destination as a guide sign: plate, name, arrow and the way there by the chosen mode. */
@Composable
private fun SignFor(sign: GuideSign, mode: Mode, units: Units, zone: ZoneId, modifier: Modifier) {
    val plate = when {
        sign.leaveAt != null -> stringResource(R.string.go_plate_leave, GoFormat.clock(sign.leaveAt, zone))
        sign.place.arriveBy != null -> stringResource(R.string.go_plate_by, sign.place.arriveBy.toString())
        sign.kind == GuideSign.Kind.HOME -> stringResource(R.string.go_plate_home)
        sign.kind == GuideSign.Kind.WORK -> stringResource(R.string.go_plate_work)
        else -> stringResource(R.string.go_plate_last)
    }
    val name = when (sign.kind) {
        GuideSign.Kind.HOME -> sign.place.name.ifBlank { stringResource(R.string.go_plate_home) }
        GuideSign.Kind.WORK -> sign.place.name.ifBlank { stringResource(R.string.go_plate_work) }
        else -> sign.place.name.ifBlank { stringResource(R.string.go_destination) }
    }
    val time = sign.seconds?.let { GoFormat.minutes(it) }
    val dist = GoFormat.distance(sign.metres ?: sign.crowMetres, units).toString()
    val line = listOfNotNull(time, dist).joinToString(" · ")
    GuideSignBoard(plate, name, sign.bearing.toFloat(), modifier, description = "$plate, $name, $line") {
        GoSymbol(modeSymbol(mode), tint = Color.White, size = 18.dp, weight = 2.2f / 1.8f)
        Text(line, style = SignType.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
    }
}

/** A sign with nothing on it yet: home or work not set, pointing to the search. */
@Composable
private fun EmptySign(kind: GuideSign.Kind, modifier: Modifier) {
    val plate = stringResource(if (kind == GuideSign.Kind.HOME) R.string.go_plate_home else R.string.go_plate_work)
    GuideSignBoard(plate, stringResource(R.string.go_sign_empty), null, modifier) {
        Text(stringResource(R.string.go_sign_empty_hint), style = SignType.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

/** A glass chip of the night sky's quick row, as on the board; [on] lights it. */
@Composable
private fun NightChip(text: String, on: Boolean = false, onClick: () -> Unit, lead: @Composable () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(if (on) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.13f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .button(text, onClick)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Text(text, style = SignType.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

/** The board's step-free chip: white, or blue when on. */
@Composable
private fun StepFreeChip(on: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.go_step_free)
    Row(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (on) SignColours.blue else Color.White)
            .border(1.dp, Color(0x1A14161B), CircleShape)
            .button(label, onClick)
            .padding(start = 8.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(BoardArt.stepFree, size = 16.dp, tint = if (on) Color.White else SignColours.ink)
        Text(label, style = SignType.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = if (on) Color.White else SignColours.ink)
    }
}

/** "Your truck · 16.5 m · 4.0 m · 40 t ›": the vehicle the routes respect, to change. */
@Composable
private fun VehicleRow(mode: Mode, onClick: () -> Unit) {
    val services = LocalServices.current
    val v by services.go.store.vehicle(mode).collectAsStateWithLifecycle(GoStore.defaultVehicle(mode))
    val facts = VehicleFacts.short(mode, v, services.go.units)
    val label = stringResource(R.string.go_your_vehicle, stringResource(GoMode.of(mode).label))
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(52.dp)
            .softShadow(RoundedCornerShape(16.dp), Color(0x14181C30), 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .button("$label, $facts", onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GoSymbol(GoMode.of(mode).symbol, tint = SignColours.blue, size = 22.dp)
        Text(label, style = SignType.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = SignColours.ink)
        Text(facts.ifEmpty { stringResource(R.string.go_vehicle_unset) }, style = SignType.copy(fontSize = 12.sp), color = SignColours.soft,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        GoSymbol("ui-chevron-right", tint = SignColours.soft, size = 16.dp)
    }
}

/** The five blue service signs with what is within reach. */
@Composable
private fun KeepMoving(ui: GoTabUi, units: Units, onPick: (Moving) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        for (kind in Moving.entries) {
            val c = ui.moving?.firstOrNull { it.kind == kind }
            val value = when {
                ui.moving == null -> "…"
                c == null || c.count == 0 -> stringResource(R.string.go_none_near)
                else -> GoFormat.distance(c.nearestM ?: 0.0, units).toString()
            }
            ServiceSign(movingArt(kind), stringResource(movingLabel(kind)), value, Modifier.weight(1f), onClick = { onPick(kind) })
        }
    }
}

/** The board's service sign for each service; parking is its "P". */
internal fun movingArt(kind: Moving): Art? = when (kind) {
    Moving.FUEL -> BoardArt.fuel
    Moving.CHARGE -> BoardArt.charge
    Moving.PARK -> null
    Moving.BIKE_FIX -> BoardArt.wrench
    Moving.LOCKERS -> BoardArt.padlock
}

internal fun movingLabel(kind: Moving): Int = when (kind) {
    Moving.FUEL -> R.string.go_fuel
    Moving.CHARGE -> R.string.go_charge
    Moving.PARK -> R.string.go_park
    Moving.BIKE_FIX -> R.string.go_bike_fix
    Moving.LOCKERS -> R.string.go_lockers
}

/** A place you went to, as the board's white card: its initial on a green badge, the name, and how far and when. */
@Composable
private fun RecentCard(p: SavedPlace, ui: GoTabUi, units: Units, modifier: Modifier, onClick: () -> Unit) {
    val d = GoFormat.distance(app.atlas.geo.Geo.distance(ui.here, p.at), units).toString()
    val name = p.name.ifBlank { stringResource(R.string.go_destination) }
    val sub = d + (p.usedAt.takeIf { it > 0 }?.let { " · " + ago(Instant.ofEpochMilli(it)) } ?: "")
    Row(
        modifier
            .height(52.dp)
            .softShadow(RoundedCornerShape(16.dp), Color(0x14181C30), 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .button("$name, $sub", onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(SignColours.green), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = SignType.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = SignType.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold), color = SignColours.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = SignType.copy(fontSize = 12.sp, lineHeight = 15.sp), color = SignColours.soft, maxLines = 1)
        }
    }
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
