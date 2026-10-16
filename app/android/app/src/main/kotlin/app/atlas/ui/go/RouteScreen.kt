package app.atlas.ui.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.RouteLine
import app.atlas.mapview.Theme
import app.atlas.mapview.UserLocation
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.ElevationProfile
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetLook
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSheetState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val GEIST = "Geist, system-ui, sans-serif"

/** The Route board's colours (canvas/project/Route.dc.html). */
private object RouteColours {
    const val INK: Argb = 0xFF14161B
    const val SUB: Argb = 0xFF464C5A
    const val GLASS: Argb = 0xEBFFFFFF
    const val PILL: Argb = 0xE6FFFFFF
    const val RULE: Argb = 0xFFECE8E1
    const val CHIP: Argb = 0xFFF1EEE8
    const val BLUE: Argb = 0xFF2E5BFF
    const val SHEET: Argb = 0xFFFBFAF7
    const val PICKED: Argb = 0xFFEEF2FF
    const val GREEN: Argb = 0xFF178A4E
    const val PARKING: Argb = 0xFF1F5F99
    const val GRABBER: Argb = 0xFFD8D4CC
    const val IDLE: Argb = 0xFFB5B9C2
}

/** The board's glass: the ends card and the back button (white at 90–92 % over a 20 px blur, a soft shadow). */
private fun Modifier.routeGlass(bg: Argb = RouteColours.GLASS, r: Float = 24f) =
    cssBox(bg = bg, r = CssCorners(r, r, r, r), shadows = listOf(CssShadow(0f, 6f, 20f, 0f, 0x1F181C30)), blur = 20f)

/** The board's chip glass: the 38 px pills over the map. */
private fun Modifier.chipGlass(on: Boolean = false) =
    if (on) cssBox(bg = RouteColours.INK, r = CssCorners(19f, 19f, 19f, 19f))
    else cssBox(bg = RouteColours.PILL, r = CssCorners(19f, 19f, 19f, 19f), shadows = listOf(CssShadow(0f, 4f, 14f, 0f, 0x1A181C30)), blur = 20f)

private val small = boardType(GEIST, 13f, lineHeight = 17f, tnum = true)

/**
 * The route planner, as on the Route board: from (here) and to with swap, stops on the way (add from your places or
 * by a long press on the map, reorder, remove); when ("Now ▾", leave at, arrive by); the preferences (avoid tolls,
 * motorways, ferries, unpaved, steps; quiet, green, flat, lit); the map with the chosen route and its alternatives; a
 * row per mode with its time, its key fact and the arrival; the height profile; the ways compared (fastest, quiet, flat
 * or without motorways and tolls, with the minutes each costs); the routing data to download when the area lacks it;
 * and Go (or simulate, which drives the guide along the route without moving). The chrome sits where the board has it.
 */
@Composable
fun RouteScreen(args: Dest.Route, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val context = LocalContext.current
    val vm = viewModel(key = "route:" + args.route) {
        val from = args.from?.let { Endpoint(args.fromName, it) } ?: Endpoint(null, here, here = true)
        val to = args.to?.let { Endpoint(args.toName, it) }
        RouteViewModel(services, from, to, args.mode?.let { n -> runCatching { Mode.valueOf(n) }.getOrNull() }, viaStops(args.via))
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val sheet = rememberSheetState(SheetStop.HALF)
    val units by services.go.unitsFlow.collectAsStateWithLifecycle()
    // A panel (when, preferences, stops) gets the room: the sheet steps down to its peek while one is open.
    LaunchedEffect(panel) { if (panel != null && sheet.stop != SheetStop.PEEK) sheet.animateTo(SheetStop.PEEK) }

    val go: (Boolean) -> Unit = { demo ->
        scope.launch {
            val to = ui.to ?: return@launch
            if (vm.start(context, demo)) nav.go(Dest.Navigate(to.at.lat, to.at.lon, to.name, ui.mode.name, demo = demo || ui.demo))
        }
    }
    val dy = statusShift(44f)

    BoxWithConstraints(Modifier.fillMaxSize().background(argb(0xFFEFE5DA))) {
        val height = maxHeight.value
        PlannerMap(ui, vm, here)
        Column(Modifier.padding(top = 44.dp + dy, start = 12.dp, end = 12.dp).fillMaxWidth()) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.width(48.dp).fillMaxHeight().routeGlass(bg = RouteColours.PILL).button(stringResource(R.string.kit_back)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { Symbol("ui.back", Modifier.size(22.dp), tint = argb(RouteColours.INK), size = 22.dp) }
                EndsCard(ui, vm, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WhenChip(ui) { panel = if (panel == "when") null else "when" }
                RoundChip("ui.sliders.2", stringResource(R.string.go_prefs), panel == "prefs") { panel = if (panel == "prefs") null else "prefs" }
                if (ui.mode.motor) RoundChip("fact.no-toll", stringResource(R.string.go_avoid_tolls), ui.prefs.avoidTolls) { vm.setPrefs(ui.prefs.copy(avoidTolls = !ui.prefs.avoidTolls)) }
                if (ui.mode.onFoot) RoundChip("fact.step-free", stringResource(R.string.go_avoid_steps), ui.prefs.avoidSteps) { vm.setPrefs(ui.prefs.copy(avoidSteps = !ui.prefs.avoidSteps)) }
                RoundChip("ui.plus", stringResource(R.string.go_add_stop), panel == "stops") { panel = if (panel == "stops") null else "stops" }
                if (ui.mode == Mode.TRUCK || ui.mode == Mode.CAMPER || ui.mode.water || (ui.mode == Mode.CAR && ui.vehicle(Mode.CAR).ev != null)) {
                    RoundChip(GoMode.of(ui.mode).board, stringResource(R.string.go_your_vehicle, stringResource(GoMode.of(ui.mode).label)), false) { nav.go(Dest.Vehicle(ui.mode.name)) }
                }
            }
            when (panel) {
                "when" -> PanelCard { TimingPanel(ui, vm, services.clock) }
                "prefs" -> PanelCard { PrefsPanel(ui, vm) }
                "stops" -> PanelCard { StopsPanel(vm, onDone = { panel = null }) }
            }
        }
        // The board's sheet rests with its top 385 px above the bottom (530 of 915), its rows from 553.
        Sheet(
            sheet, peekHeight = 180.dp, halfFraction = (385f / height).coerceIn(0.2f, 0.9f),
            look = SheetLook(
                Modifier.cssBox(bg = RouteColours.SHEET, r = CssCorners(30f, 30f, 0f, 0f), shadows = listOf(CssShadow(0f, -10f, 30f, 0f, 0x24181C30)), clip = true),
                argb(RouteColours.GRABBER),
            ),
        ) {
            PlannerSheet(ui, vm, units, services.clock.zone, onPick = { vm.setTo(it); panel = null })
        }
        GoBar(ui, Modifier.align(Alignment.BottomCenter), onGo = { go(false) }, onSimulate = { go(true) })
    }
}

/** The map: the chosen route in blue with its time, the alternatives in grey with theirs, the ends, the stops and you. */
@Composable
private fun PlannerMap(ui: RouteUi, vm: RouteViewModel, here: app.atlas.geo.LatLon) {
    val services = LocalServices.current
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val map = remember { MapState(CameraPosition(ui.to?.at ?: here, 15.0, 0.0, 45.0)) }
    val found = ui.plan as? Plan.Found
    val chosen = ui.chosen.coerceIn(0, ((found?.routes?.size ?: 1) - 1).coerceAtLeast(0))
    val density = LocalDensity.current
    LaunchedEffect(found?.routes, ui.to, ui.stops) {
        val pts = found?.routes?.flatMap { it.points } ?: listOfNotNull(ui.from.at, ui.to?.at) + ui.stops.map { it.at }
        BBox.of(pts)?.let { box -> map.fit(box.buffer(60.0), with(density) { 40.dp.roundToPx() }) }
    }
    val routes = found?.routes.orEmpty().mapIndexed { i, r ->
        RouteLine("r$i", r.points, colour = if (i == chosen) 0xFF2E5BFF else 0xFF8A93A3, widthDp = if (i == chosen) 8f else 6f, alternative = i != chosen, label = GoFormat.minutes(r.duration))
    }.sortedBy { it.id != "r$chosen" }
    val markers = buildList {
        ui.to?.let { add(Marker("to", it.at, "ui-map-pin", 0xFFE8612B, MarkerStyle.PIN, priority = 100)) }
        if (!ui.from.here) add(Marker("from", ui.from.at, "ui-navigation", 0xFF2E5BFF, MarkerStyle.DOT, priority = 90))
        ui.stops.forEachIndexed { i, s -> add(Marker("stop$i", s.at, "ui-flag", 0xFF14161B, MarkerStyle.PIN, badge = "${i + 1}", priority = 95)) }
        val fact = found?.fact
        if (fact is KeyFact.Parking && fact.count > 0 && ui.to != null) add(Marker("p", ui.to.at, "ui-parking", 0xFF1F5F99, MarkerStyle.CHIP, value = "${fact.count}", priority = 80))
    }
    AtlasMap(
        state = map,
        tiles = tiles,
        modifier = Modifier.fillMaxSize(),
        style = MapStyle(theme = Theme.DAY, calm = true),
        overlays = MapOverlays(markers = markers, routes = routes, location = UserLocation(here, 10.0), measure = false),
        contentPadding = PaddingValues(top = 190.dp, bottom = 410.dp, start = 24.dp, end = 24.dp),
        onTap = { hit ->
            // A tap on an alternative chooses it.
            if (hit is MapHit.Empty && found != null) {
                val near = found.routes.withIndex().mapNotNull { (i, r) -> r.line.snap(hit.at)?.let { i to it.offset } }.minByOrNull { it.second }
                if (near != null && near.second < 40.0) vm.choose(near.first)
            }
        },
        onLongPress = { at -> if (ui.to == null) vm.setToAt(at) else vm.addStopAt(at) },
    )
}

/**
 * The from/to card of the Route board (332 px of glass): the blue start square, the dashed line and the pin on the
 * left; the ends (and stops) in rows of 35 and 34 px with a hairline between; swap on the right.
 */
@Composable
private fun EndsCard(ui: RouteUi, vm: RouteViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier.routeGlass().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EndsRail(ui.stops.size)
        Column(Modifier.weight(1f)) {
            val fromName = ui.from.name
            EndRow(rule = true) {
                if (ui.from.here || fromName == null) {
                    val mine = stringResource(R.string.go_from_here_long)
                    Symbol("ui.locate", Modifier.size(16.dp), tint = argb(RouteColours.BLUE), size = 16.dp, contentDescription = mine)
                } else BoardText(fromName, boardType(GEIST, 15f, 400, lineHeight = 19.5f), RouteColours.SUB)
            }
            ui.stops.forEachIndexed { i, s ->
                EndRow(rule = true) {
                    BoardText(s.name ?: stringResource(R.string.go_stop_n, i + 1), boardType(GEIST, 15f, 400, lineHeight = 19.5f), RouteColours.SUB, Modifier.weight(1f))
                    if (i > 0) TinyButton("ui.chevron-up", stringResource(R.string.go_stop_earlier)) { vm.moveStop(i, -1) }
                    if (i < ui.stops.size - 1) TinyButton("ui.chevron-down", stringResource(R.string.go_stop_later)) { vm.moveStop(i, 1) }
                    TinyButton("ui.close", stringResource(R.string.go_stop_remove)) { vm.removeStop(i) }
                }
            }
            EndRow(rule = false) {
                val to = ui.to
                if (to != null) BoardText(to.name ?: stringResource(R.string.go_destination), boardType(GEIST, 15f, 600, lineHeight = 19.5f), RouteColours.INK)
                else BoardText(stringResource(R.string.go_pick_destination), boardType(GEIST, 15f, 600, lineHeight = 19.5f), RouteColours.SUB)
            }
        }
        Box(
            Modifier.size(44.dp).cssBox(bg = RouteColours.CHIP, r = CssCorners(22f, 22f, 22f, 22f)).button(stringResource(R.string.go_swap)) { vm.swap() },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.swap.3", Modifier.size(18.dp), tint = argb(RouteColours.INK), size = 18.dp) }
    }
}

/**
 * The card's left rail, as the board draws it: the start (a 17 px blue square with a white border and a pale ring),
 * then for each stop a dashed line and a dot, then a dashed line and the destination's pin, 3 px apart.
 */
@Composable
private fun EndsRail(stops: Int) {
    // Row tops within the card's content: the start's row is 35 px, each stop's 35, the destination's 34.
    val toTop = 35f + 35f * stops
    Box(Modifier.width(17.dp).height((toTop + 34f).dp)) {
        Bx(0f, 8f, 17f, 17f, bg = RouteColours.BLUE, r = CssCorners(6f, 6f, 6f, 6f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0xFFC9D3FF)),
            border = app.atlas.ui.kit.CssBorder(2.5f, 2.5f, 2.5f, 2.5f, longArrayOf(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF)))
        var above = 25f
        for (k in 0 until stops) {
            val dot = 35f + 35f * k + 12.5f
            Dash(above + 3f, dot - 3f)
            Bx(3.5f, dot, 10f, 10f, bg = RouteColours.INK, r = CssCorners(5f, 5f, 5f, 5f))
            above = dot + 10f
        }
        Dash(above + 3f, toTop + 10f - 3f)
        Sw("pin.place", 1.5f, toTop + 10f, 14f, 16f)
    }
}

/** The rail's dashed line: 2 px wide, 3 px on and 3 off, from [top] to [bottom]. */
@Composable
private fun Dash(top: Float, bottom: Float) {
    if (bottom <= top) return
    Bx(7.5f, top, 2f, bottom - top, grad = listOf(app.atlas.ui.kit.cssRepeatingLinear(180f, 6f, 0f to RouteColours.IDLE, 3f to RouteColours.IDLE, 3f to 0x00000000, 6f to 0x00000000)))
}

/** One row of the ends card: 35 px with a hairline under it ([rule]), or the last, 34 px. */
@Composable
private fun EndRow(rule: Boolean, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(if (rule) 35.dp else 34.dp)
            .then(if (rule) Modifier.cssBox(border = app.atlas.ui.kit.CssBorder(0f, 0f, 1f, 0f, longArrayOf(0, 0, RouteColours.RULE, 0))) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

@Composable
private fun TinyButton(symbol: String, description: String, size: Int = 26, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).cssBox(bg = RouteColours.CHIP, r = CssCorners(size / 2f, size / 2f, size / 2f, size / 2f)).button(description, onClick), contentAlignment = Alignment.Center) {
        Symbol(symbol, Modifier.size((size * 0.5f).dp), tint = argb(RouteColours.INK), size = (size * 0.5f).dp)
    }
}

/** A 38 px round chip of the board's chip row: white glass, or ink with the symbol white when on (as "avoiding tolls"). */
@Composable
private fun RoundChip(symbol: String, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).chipGlass(on).button(description, onClick).semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, Modifier.size(17.dp), tint = argb(if (on) 0xFFFFFFFF else RouteColours.INK), size = 17.dp)
    }
}

/** "Now ▾", "Leave 8:15 ▾" or "By 9:00 ▾", as the board's glass pill: the clock, the words, the chevron. */
@Composable
private fun WhenChip(ui: RouteUi, onClick: () -> Unit) {
    val t = ui.timing
    val text = when (t.kind) {
        Timing.Kind.NOW -> stringResource(R.string.go_now)
        Timing.Kind.DEPART -> stringResource(R.string.go_leave_at, t.at?.toLocalTime()?.let { GoFormat.time(it) } ?: "")
        Timing.Kind.ARRIVE -> stringResource(R.string.go_arrive_by, t.at?.toLocalTime()?.let { GoFormat.time(it) } ?: "")
    }
    Row(
        Modifier.height(38.dp).chipGlass().button(text, onClick).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol("ui.clock", Modifier.size(16.dp), tint = argb(RouteColours.INK), size = 16.dp)
        BoardText(text, boardType(GEIST, 14f, 600, lineHeight = 18f), RouteColours.INK)
        Symbol("ui.chevron-down", Modifier.size(12.dp), tint = argb(RouteColours.INK), size = 12.dp)
    }
}

/** A panel under the chips, on the board's glass. */
@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().routeGlass().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A small capitals heading of the planner's panels and sheet, in the board's soft ink. */
@Composable
private fun Kicker(text: String, modifier: Modifier = Modifier) {
    BoardText(text.uppercase(), boardType(GEIST, 11f, 800, lineHeight = 14f, spacing = 0.88f), RouteColours.SUB, modifier)
}

/** Leave now, leave at, or be there by; the time stepped a quarter hour at a time. */
@Composable
private fun TimingPanel(ui: RouteUi, vm: RouteViewModel, clock: java.time.Clock) {
    val t = ui.timing
    val now = java.time.LocalDateTime.now(clock).withSecond(0).withNano(0)
    val at = t.at ?: now.plusMinutes((15 - now.minute % 15).toLong())
    app.atlas.ui.kit.SegmentedPill(
        listOf(app.atlas.ui.kit.Segment(stringResource(R.string.go_now)), app.atlas.ui.kit.Segment(stringResource(R.string.go_leave)), app.atlas.ui.kit.Segment(stringResource(R.string.go_arrive))),
        selected = t.kind.ordinal,
        onSelect = { i -> vm.setTiming(if (i == 0) Timing() else Timing(Timing.Kind.entries[i], at)) },
    )
    if (t.kind != Timing.Kind.NOW) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TinyButton("ui.minus", stringResource(R.string.go_earlier), size = 44) { vm.setTiming(t.copy(at = at.minusMinutes(15))) }
            BoardText(GoFormat.time(at.toLocalTime()), boardType(GEIST, 30f, 700, tnum = true, align = TextAlign.Center), RouteColours.INK, Modifier.weight(1f))
            TinyButton("ui.plus", stringResource(R.string.go_later), size = 44) { vm.setTiming(t.copy(at = at.plusMinutes(15))) }
        }
    }
}

/** What the route should avoid and favour: these become the router's preferences. */
@Composable
private fun PrefsPanel(ui: RouteUi, vm: RouteViewModel) {
    val p = ui.prefs
    Kicker(stringResource(R.string.go_avoid))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_tolls_short), p.avoidTolls, { vm.setPrefs(p.copy(avoidTolls = it)) }, symbol = "fact.no-toll")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_motorways), p.avoidMotorways, { vm.setPrefs(p.copy(avoidMotorways = it)) }, symbol = "mode.car")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_ferries), p.avoidFerries, { vm.setPrefs(p.copy(avoidFerries = it)) }, symbol = "mode.boat")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_unpaved), p.avoidUnpaved, { vm.setPrefs(p.copy(avoidUnpaved = it)) }, symbol = "fact.cobbles")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_steps_short), p.avoidSteps, { vm.setPrefs(p.copy(avoidSteps = it)) }, symbol = "fact.step-free")
    }
    Kicker(stringResource(R.string.go_favour))
    PrefSlider("ui-moon", stringResource(R.string.go_pref_quiet), p.quiet) { vm.setPrefs(p.copy(quiet = it)) }
    PrefSlider("nature.tree", stringResource(R.string.go_pref_green), p.green) { vm.setPrefs(p.copy(green = it)) }
    PrefSlider("ui.trending-up", stringResource(R.string.go_pref_flat), p.flat) { vm.setPrefs(p.copy(flat = it)) }
    PrefSlider("ui-lightbulb", stringResource(R.string.go_pref_lit), p.lit) { vm.setPrefs(p.copy(lit = it)) }
}

@Composable
private fun PrefSlider(symbol: String, label: String, value: Double, onChange: (Double) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(symbol, Modifier.size(20.dp), tint = argb(RouteColours.INK), size = 20.dp)
        BoardText(label, boardType(GEIST, 14f, 600, lineHeight = 18f), RouteColours.INK, Modifier.width(64.dp))
        androidx.compose.material3.Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local.toDouble()) },
            steps = 3, modifier = Modifier.weight(1f).semantics { contentDescription = label },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = argb(RouteColours.INK), activeTrackColor = argb(RouteColours.BLUE), inactiveTrackColor = argb(RouteColours.RULE),
                activeTickColor = Color.White, inactiveTickColor = argb(RouteColours.IDLE),
            ),
        )
    }
}

/** Adds a stop: home, work or a place you went to (or a long press on the map). */
@Composable
private fun StopsPanel(vm: RouteViewModel, onDone: () -> Unit) {
    val services = LocalServices.current
    val store = services.go.store
    val home by store.home.collectAsStateWithLifecycle(null)
    val work by store.work.collectAsStateWithLifecycle(null)
    val recent by store.recent.collectAsStateWithLifecycle(emptyList())
    Kicker(stringResource(R.string.go_add_stop))
    val places = listOfNotNull(home?.let { "ui-house" to it }, work?.let { "ui-briefcase" to it }) + recent.take(4).map { "ui.clock" to it }
    for ((symbol, p) in places) {
        Row(
            Modifier.fillMaxWidth().button(p.name) { vm.addStop(Endpoint(p.name.ifBlank { null }, p.at)); onDone() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(symbol, Modifier.size(20.dp), tint = argb(RouteColours.INK), size = 20.dp)
            BoardText(p.name.ifBlank { stringResource(R.string.go_destination) }, boardType(GEIST, 15f, 600, lineHeight = 19.5f), RouteColours.INK)
        }
    }
    BoardText(stringResource(R.string.go_long_press_stop), boardType(GEIST, 12f, lineHeight = 15.5f), RouteColours.SUB, lines = 3)
}

/** The sheet: a row per mode, then the chosen mode's profile, the ways compared, and what is missing. */
@Composable
private fun ColumnScope.PlannerSheet(ui: RouteUi, vm: RouteViewModel, units: app.atlas.services.Units, zone: java.time.ZoneId, onPick: (Endpoint) -> Unit) {
    // The grabber's 21 px, then the rows 2 px further down (553 of the board's 530), 52 px apart.
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (ui.to == null) {
            WhereTo(onPick)
            return@Column
        }
        for (m in ui.rowModes) ModeRow(m, ui, vm, units, zone)
        val found = ui.plan as? Plan.Found
        val route = ui.route
        if (route != null && hilly(route)) {
            Spacer(Modifier.height(8.dp))
            ElevationProfile(
                route.elevations, distances = route.line.cumulative, height = 56.dp,
                line = argb(RouteColours.BLUE), steep = argb(0xFFFF6B3D),
                contentDescription = stringResource(R.string.go_profile, GoFormat.height(route.climb, units).toString()),
            )
        }
        if (found != null) CompareWays(ui, vm)
        when (val p = ui.plan) {
            is Plan.NeedsData -> NeedsData(p, ui, vm)
            is Plan.Failed -> BoardText(failureText(p), boardType(GEIST, 14f, lineHeight = 18f), RouteColours.SUB, Modifier.padding(12.dp), lines = 3)
            else -> Unit
        }
        if (ui.demo) BoardText(stringResource(R.string.go_demo_route), boardType(GEIST, 12f, lineHeight = 15.5f), RouteColours.SUB, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), lines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            SaveChip("ui-house", stringResource(R.string.go_save_home)) { vm.saveAs(home = true) }
            SaveChip("ui-briefcase", stringResource(R.string.go_save_work)) { vm.saveAs(home = false) }
        }
        Spacer(Modifier.height(120.dp))
    }
}

/** "Home", "Work": keeps the destination as one, in the board's sand pill. */
@Composable
private fun SaveChip(symbol: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).cssBox(bg = RouteColours.CHIP, r = CssCorners(19f, 19f, 19f, 19f)).button(label, onClick).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(symbol, Modifier.size(16.dp), tint = argb(RouteColours.INK), size = 16.dp)
        BoardText(label, boardType(GEIST, 14f, 600, lineHeight = 18f), RouteColours.INK)
    }
}

@Composable
private fun failureText(p: Plan.Failed): String = when (p.kind) {
    Plan.Failed.Kind.NO_ROUTE -> stringResource(R.string.go_no_route)
    Plan.Failed.Kind.UNSUPPORTED -> stringResource(R.string.go_unsupported)
    Plan.Failed.Kind.TIMEOUT -> stringResource(R.string.go_timeout)
    Plan.Failed.Kind.ERROR -> stringResource(R.string.go_route_error, p.message)
}

/** No destination yet: home, work and the places you went to, as the board's rows. */
@Composable
private fun WhereTo(onPick: (Endpoint) -> Unit) {
    val services = LocalServices.current
    val store = services.go.store
    val home by store.home.collectAsStateWithLifecycle(null)
    val work by store.work.collectAsStateWithLifecycle(null)
    val recent by store.recent.collectAsStateWithLifecycle(emptyList())
    BoardText(stringResource(R.string.go_where), boardType("Overpass, sans-serif", 17f, 800, lineHeight = 21.5f), RouteColours.INK, Modifier.padding(12.dp))
    val places = listOfNotNull(home?.let { "ui-house" to it }, work?.let { "ui-briefcase" to it }) + recent.map { "ui.clock" to it }
    if (places.isEmpty()) BoardText(stringResource(R.string.go_where_empty), boardType(GEIST, 14f, lineHeight = 18f), RouteColours.SUB, Modifier.padding(12.dp), lines = 3)
    for ((symbol, p) in places) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).button(p.name) { onPick(Endpoint(p.name.ifBlank { null }, p.at)) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).cssBox(bg = RouteColours.CHIP, r = CssCorners(11f, 11f, 11f, 11f)), contentAlignment = Alignment.Center) {
                Symbol(symbol, Modifier.size(20.dp), tint = argb(RouteColours.SUB), size = 20.dp)
            }
            BoardText(p.name.ifBlank { stringResource(R.string.go_destination) }, boardType(GEIST, 15f, 600, lineHeight = 19.5f), RouteColours.INK)
        }
    }
}

/**
 * One mode as the board's 50 px row: its 36 px tile, the time (19 px bold, 44 px wide), the key fact, and the arrival
 * at the right; the chosen mode's row is lit pale blue.
 */
@Composable
private fun ModeRow(mode: Mode, ui: RouteUi, vm: RouteViewModel, units: app.atlas.services.Units, zone: java.time.ZoneId) {
    val plan = ui.plans[mode] ?: Plan.Loading
    val selected = mode == ui.mode
    val label = stringResource(GoMode.of(mode).label)
    val found = plan as? Plan.Found
    val route = found?.let { if (selected) ui.route ?: it.best else it.best }
    val time = route?.let { GoFormat.minutes(it.duration) }
    val arrive = route?.let { GoFormat.clock(vm.times(it).second, zone) }
    val tile = modeTile(mode)
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .cssBox(bg = if (selected) RouteColours.PICKED else 0, r = CssCorners(16f, 16f, 16f, 16f))
            .semantics { this.selected = selected }
            .button(listOfNotNull(label, time, arrive).joinToString(", ")) { vm.setMode(mode) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).cssBox(bg = tile.bg, r = CssCorners(11f, 11f, 11f, 11f)), contentAlignment = Alignment.Center) {
            Symbol(tile.symbol, Modifier.size(tile.size.dp), tint = argb(tile.fg), size = tile.size.dp)
        }
        Spacer(Modifier.width(12.dp))
        BoardText(time ?: "", boardType(GEIST, 19f, 700, lineHeight = 24.5f, tnum = true), RouteColours.INK, Modifier.width(44.dp))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (plan) {
                is Plan.Loading -> BoardText("…", small, RouteColours.SUB)
                is Plan.Found -> FactChips(plan.fact, route!!, units)
                is Plan.NeedsData -> {
                    Symbol("ui.download", Modifier.size(14.dp), tint = argb(0xFFB7791F), size = 14.dp, weight = 2f / 2.6f)
                    BoardText(plan.needs.joinToString { it.label }, small, RouteColours.SUB)
                }
                is Plan.Failed -> BoardText(failureText(plan), small, RouteColours.SUB)
                Plan.Waiting -> Unit
            }
        }
        BoardText(arrive ?: "", small, RouteColours.SUB)
    }
}

/** A mode's tile on the Route board: its pale ground, its symbol and that symbol's colour and size. */
private class Tile(val bg: Argb, val fg: Argb, val symbol: String, val size: Float = 20f)

private fun modeTile(mode: Mode): Tile = when {
    mode == Mode.CAR -> Tile(0xFFE4EAFF, RouteColours.BLUE, "mode.car.3")
    mode == Mode.BIKE -> Tile(0xFFE3F3EA, RouteColours.GREEN, "mode.bike.2", 21f)
    mode.cycling -> Tile(0xFFE3F3EA, RouteColours.GREEN, GoMode.of(mode).board)
    mode == Mode.WALK -> Tile(RouteColours.CHIP, RouteColours.SUB, "mode.walk.5")
    mode.onFoot -> Tile(RouteColours.CHIP, RouteColours.SUB, GoMode.of(mode).board)
    mode == Mode.TRANSIT -> Tile(0xFFE6F1FB, RouteColours.PARKING, "transit.bus")
    mode.water -> Tile(0xFFE6F1FB, RouteColours.PARKING, GoMode.of(mode).board)
    else -> Tile(0xFFE4EAFF, RouteColours.BLUE, GoMode.of(mode).board)
}

/** An icon of the row's facts and its words, 3 px apart, as the board sets "↗ 12 m". */
@Composable
private fun Fact(symbol: String?, text: String, colour: Argb, description: String? = null) {
    Row(
        Modifier.then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (symbol != null) Symbol(symbol, Modifier.size(14.dp), tint = argb(colour), size = 14.dp)
        BoardText(text, small, colour)
    }
}

/** The board's parking badge: a blue 18 px square with "P". */
@Composable
private fun ParkingBadge() {
    Box(Modifier.requiredSize(18.dp).cssBox(bg = RouteColours.PARKING, r = CssCorners(5f, 5f, 5f, 5f)), contentAlignment = Alignment.Center) {
        BoardText("P", boardType(GEIST, 11f, 800, lineHeight = 14f), 0xFFFFFFFF)
    }
}

/** The key fact of a mode's row, as on the board: parking for cars, climb and car-free share for bikes, distance on foot. */
@Composable
private fun FactChips(fact: KeyFact?, route: Route, units: app.atlas.services.Units) {
    when (fact) {
        is KeyFact.Parking -> {
            ParkingBadge()
            if (fact.count > 0) {
                Symbol("ui.check", Modifier.size(14.dp), tint = argb(RouteColours.GREEN), size = 14.dp)
                BoardText(stringResource(R.string.go_parking_near, fact.count), small, RouteColours.SUB)
                if (fact.free > 0) BoardText(stringResource(R.string.go_parking_free, fact.free), small, RouteColours.GREEN)
            } else BoardText(stringResource(R.string.go_parking_none), small, RouteColours.SUB)
        }
        is KeyFact.Ride -> {
            Fact("ui.trending-up", GoFormat.height(fact.climbM, units).toString(), RouteColours.SUB)
            val share = (fact.carFreeShare * 100).roundToInt()
            Fact("fact.vegan.2", "$share%", RouteColours.GREEN, stringResource(R.string.go_car_free, share))
        }
        is KeyFact.Walk -> {
            BoardText(GoFormat.distance(fact.metres, units).toString(), small, RouteColours.SUB)
            if (route.climb >= 10) Fact("ui.trending-up", GoFormat.height(route.climb, units).toString(), RouteColours.SUB)
        }
        is KeyFact.Water -> {
            if (fact.locks > 0) BoardText(stringResource(R.string.go_locks, fact.locks), small, RouteColours.SUB)
            if (fact.bridges > 0) BoardText(stringResource(R.string.go_bridges, fact.bridges), small, RouteColours.SUB)
            BoardText(GoFormat.distance(route.distance, app.atlas.services.Units.NAUTICAL).toString(), small, RouteColours.SUB)
        }
        null -> BoardText(GoFormat.distance(route.distance, units).toString(), small, RouteColours.SUB)
    }
}

/** Fastest against quiet and flat (or without motorways and tolls), with the minutes each costs; tap one to go by it. */
@Composable
private fun CompareWays(ui: RouteUi, vm: RouteViewModel) {
    val fastest = ui.ways.firstOrNull { it.kind == WayKind.FASTEST }?.route ?: return
    // A way that is the fastest one again (a city with no motorway to avoid) says nothing: only the different ones show.
    val ways = remember(ui.ways) { RouteViewModel.distinctWays(ui.ways) }
    if (ways.size < 2) return
    Spacer(Modifier.height(6.dp))
    Kicker(stringResource(R.string.go_compare_ways), Modifier.padding(horizontal = 12.dp))
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in ways) {
            val cost = RouteViewModel.cost(w.route, fastest)
            val current = ui.prefs == RouteViewModel.prefsFor(w.kind, ui.prefs)
            val label = stringResource(wayLabel(w.kind))
            val extra = if (w.kind == WayKind.FASTEST) GoFormat.minutes(w.route.duration) else "+" + ((cost / 60).roundToInt().coerceAtLeast(0)).toString() + "′"
            val fg = if (current) 0xFFFFFFFF else RouteColours.INK
            Column(
                Modifier
                    .weight(1f)
                    .cssBox(bg = if (current) RouteColours.INK else RouteColours.CHIP, r = CssCorners(16f, 16f, 16f, 16f))
                    .button("$label, $extra") { vm.pickWay(w.kind) }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Symbol(waySymbol(w.kind), Modifier.size(20.dp), tint = argb(fg), size = 20.dp)
                BoardText(label, boardType(GEIST, 12f, 600, lineHeight = 15.5f), fg)
                BoardText(extra, boardType(GEIST, 15f, 700, lineHeight = 19.5f, tnum = true), fg)
            }
        }
    }
}

/** The routing data the area lacks, its size, and the download. */
@Composable
private fun NeedsData(p: Plan.NeedsData, ui: RouteUi, vm: RouteViewModel) {
    val services = LocalServices.current
    val online by services.connectivity.online.collectAsStateWithLifecycle()
    val progress = p.needs.mapNotNull { ui.downloading[it.tile] }.takeIf { it.isNotEmpty() }?.average()
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier.fillMaxWidth().cssBox(bg = 0xFFFFF1D6, r = CssCorners(20f, 20f, 20f, 20f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol("ui.download", Modifier.size(22.dp), tint = argb(0xFF8A5A00), size = 22.dp, weight = 2f / 2.6f)
            BoardText(stringResource(R.string.go_needs_data_title), boardType(GEIST, 15f, 700, lineHeight = 19.5f), 0xFF6B4500)
        }
        BoardText(stringResource(R.string.go_needs_data_text, p.needs.joinToString { it.label }), boardType(GEIST, 14f, lineHeight = 18f), 0xFF6B4500, lines = 4)
        ui.downloadError?.let { BoardText(it, boardType(GEIST, 12f, lineHeight = 15.5f), 0xFFD93025, lines = 3) }
        val enabled = online && progress == null
        val text = when {
            progress != null -> stringResource(R.string.go_downloading, (progress * 100).roundToInt())
            !online -> stringResource(R.string.go_download_offline)
            else -> stringResource(R.string.go_download_data)
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).cssBox(bg = if (enabled) RouteColours.INK else 0xFFD8D4CC, r = CssCorners(24f, 24f, 24f, 24f))
                .then(if (enabled) Modifier.button(text, vm::download) else Modifier.semantics { contentDescription = text }),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("ui.download", Modifier.size(18.dp), tint = argb(if (enabled) 0xFFFFFFFF else RouteColours.SUB), size = 18.dp, weight = 2f / 2.6f)
            BoardText(text, boardType(GEIST, 15f, 600, lineHeight = 19.5f), if (enabled) 0xFFFFFFFF else RouteColours.SUB)
        }
    }
}

/**
 * The board's bottom bar, 42 px above the bottom: the big blue Go (326 × 54) with the navigation arrow and the chosen
 * mode's time, and beside it, in the board's sand disc, simulate.
 */
@Composable
private fun GoBar(ui: RouteUi, modifier: Modifier, onGo: () -> Unit, onSimulate: () -> Unit) {
    val route = ui.route
    val label = route?.let { GoFormat.minutes(it.duration) } ?: stringResource(R.string.go_start)
    Row(
        modifier.fillMaxWidth().background(argb(RouteColours.SHEET)).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 42.dp + navShift(873f)),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(54.dp).cssBox(bg = if (route != null) RouteColours.BLUE else RouteColours.GRABBER, r = CssCorners(27f, 27f, 27f, 27f))
                .then(if (route != null) Modifier.button(stringResource(R.string.go_start) + ", " + label, onGo) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("nav.arrow", Modifier.size(20.dp), tint = Color.White, size = 20.dp)
            BoardText(label, boardType(GEIST, 17f, 600, lineHeight = 22f, tnum = true), 0xFFFFFFFF)
        }
        Box(
            Modifier.size(54.dp).cssBox(bg = RouteColours.CHIP, r = CssCorners(27f, 27f, 27f, 27f))
                .then(if (route != null) Modifier.button(stringResource(R.string.go_simulate), onSimulate) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Symbol("ui.play", Modifier.size(20.dp), tint = argb(if (route != null) RouteColours.INK else RouteColours.IDLE), size = 20.dp)
        }
    }
}

internal fun wayLabel(k: WayKind): Int = when (k) {
    WayKind.FASTEST -> R.string.go_way_fastest
    WayKind.QUIET -> R.string.go_way_quiet
    WayKind.FLAT -> R.string.go_way_flat
    WayKind.NO_MOTORWAYS -> R.string.go_way_no_motorways
    WayKind.NO_TOLLS -> R.string.go_way_no_tolls
}

private fun waySymbol(k: WayKind): String = when (k) {
    WayKind.FASTEST -> "ui-timer"
    WayKind.QUIET -> "ui-trees"
    WayKind.FLAT -> "ui-mountain"
    WayKind.NO_MOTORWAYS -> "ui-signpost"
    WayKind.NO_TOLLS -> "ui-coins"
}

/** True when a route climbs enough for its profile to say something (5 m between its lowest and highest point). */
internal fun hilly(route: Route): Boolean {
    val e = route.elevations
    if (e.size != route.points.size) return false
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (x in e) if (!x.isNaN()) { if (x < lo) lo = x; if (x > hi) hi = x }
    return hi - lo >= 5.0
}

/** The stops of a route's `via` argument (`lat,lon,name` joined by `|`), in order; unreadable ones are left out. */
internal fun viaStops(via: String?): List<Endpoint> = via.orEmpty().split('|').mapNotNull { s ->
    val p = s.split(',', limit = 3)
    val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
    Endpoint(p.getOrNull(2)?.takeIf { it.isNotBlank() }, app.atlas.geo.LatLon(lat, lon))
}
