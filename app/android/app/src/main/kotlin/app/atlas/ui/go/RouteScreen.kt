package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.Geo
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
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.ElevationProfile
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.softShadow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The route planner, as on the Route board: from (here) and to with swap, stops on the way (add from your places or
 * by a long press on the map, reorder, remove); when ("Now ▾", leave at, arrive by); the preferences (avoid tolls,
 * motorways, ferries, unpaved, steps; quiet, green, flat, lit); the map with the chosen route and its alternatives; a
 * row per mode with its time, its key fact and the arrival; the height profile; the ways compared (fastest, quiet, flat
 * or without motorways and tolls, with the minutes each costs); the routing data to download when the area lacks it;
 * and Go (or simulate, which drives the guide along the route without moving).
 */
@Composable
fun RouteScreen(args: Dest.Route, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val context = LocalContext.current
    val vm = viewModel(key = "route:" + args.route) {
        val from = args.from?.let { Endpoint(args.fromName, it) } ?: Endpoint(null, here, here = true)
        val to = args.to?.let { Endpoint(args.toName, it) }
        RouteViewModel(services, from, to, args.mode?.let { n -> runCatching { Mode.valueOf(n) }.getOrNull() })
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val sheet = rememberSheetState(SheetStop.HALF)
    val units = services.go.units
    // A panel (when, preferences, stops) gets the room: the sheet steps down to its peek while one is open.
    LaunchedEffect(panel) { if (panel != null && sheet.stop != SheetStop.PEEK) sheet.animateTo(SheetStop.PEEK) }

    val go: (Boolean) -> Unit = { demo ->
        scope.launch {
            val to = ui.to ?: return@launch
            if (vm.start(context, demo)) nav.go(Dest.Navigate(to.at.lat, to.at.lon, to.name, ui.mode.name, demo = demo || ui.demo))
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFEFE5DA))) {
        PlannerMap(ui, vm, here)
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.width(48.dp).fillMaxHeight().softShadow(RoundedCornerShape(24.dp), RouteColours.shadow, 10.dp)
                        .clip(RoundedCornerShape(24.dp)).background(RouteColours.glass).button(stringResource(R.string.kit_back)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { ArtIcon(BoardArt.back, size = 22.dp, tint = RouteColours.ink) }
                EndsCard(ui, vm, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WhenChip(ui) { panel = if (panel == "when") null else "when" }
                RoundChip(BoardArt.sliders, stringResource(R.string.go_prefs), panel == "prefs") { panel = if (panel == "prefs") null else "prefs" }
                if (ui.mode.motor) RoundChip(BoardArt.noTolls, stringResource(R.string.go_avoid_tolls), ui.prefs.avoidTolls) { vm.setPrefs(ui.prefs.copy(avoidTolls = !ui.prefs.avoidTolls)) }
                if (ui.mode.onFoot) RoundChip(null, stringResource(R.string.go_avoid_steps), ui.prefs.avoidSteps, symbol = "ui-stairs") { vm.setPrefs(ui.prefs.copy(avoidSteps = !ui.prefs.avoidSteps)) }
                RoundChip(null, stringResource(R.string.go_add_stop), panel == "stops", symbol = "ui-plus") { panel = if (panel == "stops") null else "stops" }
                if (ui.mode == Mode.TRUCK || ui.mode == Mode.CAMPER || ui.mode.water || (ui.mode == Mode.CAR && ui.vehicle(Mode.CAR).ev != null)) {
                    RoundChip(null, stringResource(R.string.go_your_vehicle, stringResource(GoMode.of(ui.mode).label)), false, symbol = GoMode.of(ui.mode).symbol) { nav.go(Dest.Vehicle(ui.mode.name)) }
                }
            }
            when (panel) {
                "when" -> PanelCard { TimingPanel(ui, vm, services.clock) }
                "prefs" -> PanelCard { PrefsPanel(ui, vm) }
                "stops" -> PanelCard { StopsPanel(vm, onDone = { panel = null }) }
            }
        }
        Sheet(sheet, peekHeight = 180.dp, halfFraction = 0.42f) {
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
        if (fact is KeyFact.Parking && fact.count > 0 && ui.to != null) add(Marker("p", ui.to.at, "ui-parking", 0xFF1F4FA0, MarkerStyle.CHIP, value = "${fact.count}", priority = 80))
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

/** The Route board's colours (canvas/project/Route.dc.html). */
private object RouteColours {
    val ink = Color(0xFF14161B)
    val sub = Color(0xFF464C5A)
    val glass = Color(0xEBFFFFFF)
    val shadow = Color(0x1F181C30)
    val rule = Color(0xFFECE8E1)
    val chip = Color(0xFFF1EEE8)
    val blue = Color(0xFF2E5BFF)
    val orange = Color(0xFFE8622C)
    val sheet = Color(0xFFFBFAF7)
    val picked = Color(0xFFEEF2FF)
    val green = Color(0xFF178A4E)
    val parking = Color(0xFF1F5F99)
}

/** The from/to card of the Route board: the dots and the dashed line on the left, the ends (and stops), and swap. */
@Composable
private fun EndsCard(ui: RouteUi, vm: RouteViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier.softShadow(RoundedCornerShape(24.dp), RouteColours.shadow, 10.dp).clip(RoundedCornerShape(24.dp)).background(RouteColours.glass)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFC9D3FF)).padding(1.dp).clip(CircleShape).background(Color.White).padding(2.5.dp).clip(CircleShape).background(RouteColours.blue))
            repeat(ui.stops.size + 1) { i ->
                Canvas(Modifier.size(width = 2.dp, height = 14.dp)) {
                    drawLine(Color(0xFFB5B9C2), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), size.width,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                }
                if (i < ui.stops.size) Box(Modifier.size(10.dp).clip(CircleShape).background(RouteColours.ink))
            }
            ArtIcon(BoardArt.pin, size = 16.dp)
        }
        Column(Modifier.weight(1f)) {
            EndRow(ui.from.name ?: stringResource(R.string.go_from_here_long), lead = if (ui.from.here) BoardArt.myLocation else null, first = true)
            ui.stops.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EndRow(s.name ?: stringResource(R.string.go_stop_n, i + 1), Modifier.weight(1f))
                    if (i > 0) TinyButton("ui-chevron-up", stringResource(R.string.go_stop_earlier)) { vm.moveStop(i, -1) }
                    if (i < ui.stops.size - 1) TinyButton("ui-chevron-down", stringResource(R.string.go_stop_later)) { vm.moveStop(i, 1) }
                    TinyButton("ui-x", stringResource(R.string.go_stop_remove)) { vm.removeStop(i) }
                }
            }
            EndRow(ui.to?.name ?: ui.to?.let { stringResource(R.string.go_destination) } ?: stringResource(R.string.go_pick_destination), bold = true, last = true)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(RouteColours.chip).button(stringResource(R.string.go_swap)) { vm.swap() }, contentAlignment = Alignment.Center) {
            ArtIcon(BoardArt.swap, size = 18.dp, tint = RouteColours.ink)
        }
    }
}

/** One row of the ends card: 34 dp, a hairline under all but the last. */
@Composable
private fun EndRow(text: String, modifier: Modifier = Modifier, lead: Art? = null, bold: Boolean = false, first: Boolean = false, last: Boolean = false) {
    Row(
        modifier.height(34.dp).then(if (!last) Modifier.drawBehind { drawLine(RouteColours.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) } else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lead?.let { ArtIcon(it, size = 16.dp, tint = RouteColours.blue) }
        Text(text, style = RouteType.copy(fontSize = 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = if (bold) RouteColours.ink else RouteColours.sub,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val RouteType = androidx.compose.ui.text.TextStyle(fontFamily = app.atlas.ui.kit.InterFamily)

@Composable
private fun TinyButton(symbol: String, description: String, size: Int = 32, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(RouteColours.chip).button(description, onClick), contentAlignment = Alignment.Center) {
        GoSymbol(symbol, tint = RouteColours.ink, size = (size * 0.5f).dp)
    }
}

/** A 38 dp round chip of the board's chip row: white glass, or ink when on (as "avoiding tolls"). */
@Composable
private fun RoundChip(art: Art?, description: String, on: Boolean, symbol: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).softShadow(CircleShape, RouteColours.shadow, 8.dp).clip(CircleShape).background(if (on) RouteColours.ink else RouteColours.glass)
            .button(description, onClick).semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (on) Color.White else RouteColours.ink
        if (art != null) ArtIcon(art, size = 17.dp, tint = tint) else GoSymbol(symbol ?: "ui-plus", tint = tint, size = 17.dp)
    }
}

/** "Now ▾", "Leave 8:15 ▾" or "By 9:00 ▾", as the board's white pill. */
@Composable
private fun WhenChip(ui: RouteUi, onClick: () -> Unit) {
    val t = ui.timing
    val text = when (t.kind) {
        Timing.Kind.NOW -> stringResource(R.string.go_now)
        Timing.Kind.DEPART -> stringResource(R.string.go_leave_at, t.at?.toLocalTime()?.toString() ?: "")
        Timing.Kind.ARRIVE -> stringResource(R.string.go_arrive_by, t.at?.toLocalTime()?.toString() ?: "")
    }
    Row(
        Modifier.height(38.dp).softShadow(CircleShape, RouteColours.shadow, 8.dp).clip(CircleShape).background(RouteColours.glass)
            .button(text, onClick).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtIcon(BoardArt.clock, size = 16.dp, tint = RouteColours.ink)
        Text(text, style = RouteType.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RouteColours.ink)
        ArtIcon(BoardArt.chevronDown, size = 12.dp, tint = RouteColours.ink)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().softShadow(RoundedCornerShape(24.dp), Atlas.colors.shadow, 12.dp).clip(RoundedCornerShape(24.dp))
            .background(Atlas.colors.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
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
            TinyButton("ui-minus", stringResource(R.string.go_earlier), size = 44) { vm.setTiming(t.copy(at = at.minusMinutes(15))) }
            Text(at.toLocalTime().toString(), style = Atlas.type.numeral.copy(fontSize = 30.sp), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TinyButton("ui-plus", stringResource(R.string.go_later), size = 44) { vm.setTiming(t.copy(at = at.plusMinutes(15))) }
        }
    }
}

/** What the route should avoid and favour: these become the router's preferences. */
@Composable
private fun PrefsPanel(ui: RouteUi, vm: RouteViewModel) {
    val p = ui.prefs
    Text(stringResource(R.string.go_avoid).uppercase(), style = Atlas.type.kicker, color = Atlas.colors.inkSoft)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_tolls_short), p.avoidTolls, { vm.setPrefs(p.copy(avoidTolls = it)) }, symbol = "ui-coins")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_motorways), p.avoidMotorways, { vm.setPrefs(p.copy(avoidMotorways = it)) }, symbol = "ui-car")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_ferries), p.avoidFerries, { vm.setPrefs(p.copy(avoidFerries = it)) }, symbol = "ui-ship")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_unpaved), p.avoidUnpaved, { vm.setPrefs(p.copy(avoidUnpaved = it)) }, symbol = "ui-route")
        app.atlas.ui.kit.ToggleChip(stringResource(R.string.go_avoid_steps_short), p.avoidSteps, { vm.setPrefs(p.copy(avoidSteps = it)) }, symbol = "ui-stairs")
    }
    Text(stringResource(R.string.go_favour).uppercase(), style = Atlas.type.kicker, color = Atlas.colors.inkSoft)
    PrefSlider("ui-moon", stringResource(R.string.go_pref_quiet), p.quiet) { vm.setPrefs(p.copy(quiet = it)) }
    PrefSlider("ui-trees", stringResource(R.string.go_pref_green), p.green) { vm.setPrefs(p.copy(green = it)) }
    PrefSlider("ui-mountain", stringResource(R.string.go_pref_flat), p.flat) { vm.setPrefs(p.copy(flat = it)) }
    PrefSlider("ui-lightbulb", stringResource(R.string.go_pref_lit), p.lit) { vm.setPrefs(p.copy(lit = it)) }
}

@Composable
private fun PrefSlider(symbol: String, label: String, value: Double, onChange: (Double) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GoSymbol(symbol, tint = Atlas.colors.ink, size = 20.dp)
        Text(label, style = Atlas.type.body, modifier = Modifier.width(64.dp), maxLines = 1)
        androidx.compose.material3.Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local.toDouble()) },
            steps = 3, modifier = Modifier.weight(1f).semantics { contentDescription = label },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Atlas.colors.ink, activeTrackColor = Atlas.colors.go, inactiveTrackColor = Atlas.colors.line,
                activeTickColor = Atlas.colors.card, inactiveTickColor = Atlas.colors.inkFaint,
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
    Text(stringResource(R.string.go_add_stop).uppercase(), style = Atlas.type.kicker, color = Atlas.colors.inkSoft)
    val places = listOfNotNull(home?.let { "ui-house" to it }, work?.let { "ui-briefcase" to it }) + recent.take(4).map { "ui-history" to it }
    for ((symbol, p) in places) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).button(p.name) { vm.addStop(Endpoint(p.name.ifBlank { null }, p.at)); onDone() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GoSymbol(symbol, tint = Atlas.colors.ink, size = 20.dp)
            Text(p.name.ifBlank { stringResource(R.string.go_destination) }, style = Atlas.type.body)
        }
    }
    Text(stringResource(R.string.go_long_press_stop), style = Atlas.type.caption, color = Atlas.colors.inkSoft)
}

/** The sheet: a row per mode, then the chosen mode's profile, the ways compared, and what is missing. */
@Composable
private fun ColumnScope.PlannerSheet(ui: RouteUi, vm: RouteViewModel, units: app.atlas.services.Units, zone: java.time.ZoneId, onPick: (Endpoint) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ui.to == null) {
            WhereTo(onPick)
            return@Column
        }
        for (m in ui.rowModes) ModeRow(m, ui, vm, units, zone)
        val found = ui.plan as? Plan.Found
        val route = ui.route
        if (route != null && hilly(route)) {
            Spacer(Modifier.height(6.dp))
            ElevationProfile(
                route.elevations, distances = route.line.cumulative, height = 56.dp,
                contentDescription = stringResource(R.string.go_profile, route.climb.roundToInt()),
            )
        }
        if (found != null) CompareWays(ui, vm)
        when (val p = ui.plan) {
            is Plan.NeedsData -> NeedsData(p, ui, vm)
            is Plan.Failed -> Text(failureText(p), style = Atlas.type.body, color = Atlas.colors.inkSoft, modifier = Modifier.padding(8.dp))
            else -> Unit
        }
        if (ui.demo) Text(stringResource(R.string.go_demo_route), style = Atlas.type.caption, color = Atlas.colors.inkSoft, modifier = Modifier.padding(horizontal = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            app.atlas.ui.kit.Chip(stringResource(R.string.go_save_home), symbol = "ui-house", outlined = true, onClick = { vm.saveAs(home = true) })
            app.atlas.ui.kit.Chip(stringResource(R.string.go_save_work), symbol = "ui-briefcase", outlined = true, onClick = { vm.saveAs(home = false) })
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun failureText(p: Plan.Failed): String = when (p.kind) {
    Plan.Failed.Kind.NO_ROUTE -> stringResource(R.string.go_no_route)
    Plan.Failed.Kind.UNSUPPORTED -> stringResource(R.string.go_unsupported)
    Plan.Failed.Kind.TIMEOUT -> stringResource(R.string.go_timeout)
    Plan.Failed.Kind.ERROR -> stringResource(R.string.go_route_error, p.message)
}

/** No destination yet: home, work and the places you went to. */
@Composable
private fun WhereTo(onPick: (Endpoint) -> Unit) {
    val services = LocalServices.current
    val store = services.go.store
    val home by store.home.collectAsStateWithLifecycle(null)
    val work by store.work.collectAsStateWithLifecycle(null)
    val recent by store.recent.collectAsStateWithLifecycle(emptyList())
    Text(stringResource(R.string.go_where), style = Atlas.type.headline, modifier = Modifier.padding(8.dp))
    val places = listOfNotNull(home?.let { "ui-house" to it }, work?.let { "ui-briefcase" to it }) + recent.map { "ui-history" to it }
    if (places.isEmpty()) Text(stringResource(R.string.go_where_empty), style = Atlas.type.body, color = Atlas.colors.inkSoft, modifier = Modifier.padding(8.dp))
    for ((symbol, p) in places) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).button(p.name) { onPick(Endpoint(p.name.ifBlank { null }, p.at)) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GoSymbol(symbol, tint = Atlas.colors.ink, size = 22.dp)
            Text(p.name.ifBlank { stringResource(R.string.go_destination) }, style = Atlas.type.bodyStrong)
        }
    }
}

/** One mode as the board's 50 dp row: its tile, the time, the key fact, and the arrival; the chosen mode's row is lit. */
@Composable
private fun ModeRow(mode: Mode, ui: RouteUi, vm: RouteViewModel, units: app.atlas.services.Units, zone: java.time.ZoneId) {
    val plan = ui.plans[mode] ?: Plan.Loading
    val selected = mode == ui.mode
    val gm = GoMode.of(mode)
    val label = stringResource(gm.label)
    val found = plan as? Plan.Found
    val route = found?.let { if (selected) ui.route ?: it.best else it.best }
    val time = route?.let { GoFormat.minutes(it.duration) }
    val arrive = route?.let { GoFormat.clock(vm.times(it).second, zone) }
    val (tileBg, tileFg) = modeTile(mode)
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) RouteColours.picked else Color.Transparent)
            .button(listOfNotNull(label, time, arrive).joinToString(", ")) { vm.setMode(mode) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tileBg), contentAlignment = Alignment.Center) {
            GoSymbol(gm.symbol, tint = tileFg, size = 20.dp, weight = 1.9f / 1.8f)
        }
        Text(time ?: "", style = RouteType.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = RouteColours.ink,
            modifier = Modifier.width(52.dp), maxLines = 1)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (plan) {
                is Plan.Loading -> Text("…", style = RouteType.copy(fontSize = 13.sp), color = RouteColours.sub)
                is Plan.Found -> FactChips(plan.fact, route!!, units)
                is Plan.NeedsData -> {
                    ArtIcon(BoardArt.cloudCheck.without(1), size = 16.dp, tint = NavColours.amber)
                    Text(plan.needs.joinToString { it.label }, style = RouteType.copy(fontSize = 13.sp), color = RouteColours.sub, maxLines = 1)
                }
                is Plan.Failed -> Text(failureText(plan), style = RouteType.copy(fontSize = 13.sp), color = RouteColours.sub, maxLines = 1)
                Plan.Waiting -> Unit
            }
        }
        Text(arrive ?: "", style = RouteType.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = RouteColours.sub)
    }
}

/** A mode's tile colours on the Route board: blue for driving, green for the bike, sand for walking, steel blue on water. */
private fun modeTile(mode: Mode): Pair<Color, Color> = when {
    mode.cycling -> Color(0xFFE3F3EA) to Color(0xFF178A4E)
    mode.onFoot -> Color(0xFFF1EEE8) to Color(0xFF464C5A)
    mode.water || mode == Mode.TRANSIT -> Color(0xFFE6F1FB) to Color(0xFF1F5F99)
    else -> Color(0xFFE4EAFF) to Color(0xFF2E5BFF)
}

/** The key fact of a mode's row, as on the board: parking for cars, climb and car-free share for bikes, distance on foot. */
@Composable
private fun FactChips(fact: KeyFact?, route: Route, units: app.atlas.services.Units) {
    val small = RouteType.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
    when (fact) {
        is KeyFact.Parking -> {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(RouteColours.parking), contentAlignment = Alignment.Center) {
                Text("P", style = RouteType.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold), color = Color.White)
            }
            if (fact.count > 0) {
                ArtIcon(BoardArt.check.tinted.weighted(1.3f), size = 14.dp, tint = RouteColours.green)
                Text(stringResource(R.string.go_parking_near, fact.count), style = small, color = RouteColours.sub)
                if (fact.free > 0) Text(stringResource(R.string.go_parking_free, fact.free), style = small, color = RouteColours.green)
            } else Text(stringResource(R.string.go_parking_none), style = small, color = RouteColours.sub)
        }
        is KeyFact.Ride -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ArtIcon(BoardArt.trendUp, size = 14.dp, tint = RouteColours.sub)
                Text("${fact.climbM.roundToInt()} m", style = small, color = RouteColours.sub)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ArtIcon(BoardArt.leaf, size = 14.dp, tint = RouteColours.green)
                Text(stringResource(R.string.go_car_free, (fact.carFreeShare * 100).roundToInt()), style = small, color = RouteColours.green)
            }
        }
        is KeyFact.Walk -> {
            Text(GoFormat.distance(fact.metres, units).toString(), style = small, color = RouteColours.sub)
            if (route.climb >= 10) {
                ArtIcon(BoardArt.trendUp, size = 14.dp, tint = RouteColours.sub)
                Text("${route.climb.roundToInt()} m", style = small, color = RouteColours.sub)
            }
        }
        is KeyFact.Water -> {
            if (fact.locks > 0) Text(stringResource(R.string.go_locks, fact.locks), style = small, color = RouteColours.sub)
            if (fact.bridges > 0) Text(stringResource(R.string.go_bridges, fact.bridges), style = small, color = RouteColours.sub)
            Text(GoFormat.distance(route.distance, app.atlas.services.Units.NAUTICAL).toString(), style = small, color = RouteColours.sub)
        }
        null -> Text(GoFormat.distance(route.distance, units).toString(), style = small, color = RouteColours.sub)
    }
}

/** Fastest against quiet and flat (or without motorways and tolls), with the minutes each costs; tap one to go by it. */
@Composable
private fun CompareWays(ui: RouteUi, vm: RouteViewModel) {
    val fastest = ui.ways.firstOrNull { it.kind == WayKind.FASTEST }?.route ?: return
    // A way that is the fastest one again (a city with no motorway to avoid) says nothing: only the different ones show.
    val ways = remember(ui.ways) { RouteViewModel.distinctWays(ui.ways) }
    if (ways.size < 2) return
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.go_compare_ways), style = Atlas.type.kicker, color = Atlas.colors.inkSoft, modifier = Modifier.padding(horizontal = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in ways) {
            val cost = RouteViewModel.cost(w.route, fastest)
            val current = ui.prefs == RouteViewModel.prefsFor(w.kind, ui.prefs)
            val label = stringResource(wayLabel(w.kind))
            val extra = if (w.kind == WayKind.FASTEST) GoFormat.minutes(w.route.duration) else "+" + ((cost / 60).roundToInt().coerceAtLeast(0)).toString() + "′"
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (current) Atlas.colors.pill else Atlas.colors.paperDeep)
                    .button("$label, $extra") { vm.pickWay(w.kind) }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GoSymbol(waySymbol(w.kind), tint = if (current) Atlas.colors.onPill else Atlas.colors.ink, size = 20.dp)
                Text(label, style = Atlas.type.label, color = if (current) Atlas.colors.onPill else Atlas.colors.ink, maxLines = 1)
                Text(extra, style = Atlas.type.bodyStrong, color = if (current) Atlas.colors.onPill else Atlas.colors.ink)
            }
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

/** The routing data the area lacks, its size, and the download. */
@Composable
private fun NeedsData(p: Plan.NeedsData, ui: RouteUi, vm: RouteViewModel) {
    val services = LocalServices.current
    val online by services.connectivity.online.collectAsStateWithLifecycle()
    val progress = p.needs.mapNotNull { ui.downloading[it.tile] }.takeIf { it.isNotEmpty() }?.average()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFF1D6)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoSymbol("ui-download", tint = Color(0xFF8A5A00), size = 22.dp)
            Text(stringResource(R.string.go_needs_data_title), style = Atlas.type.bodyStrong, color = Color(0xFF6B4500))
        }
        Text(stringResource(R.string.go_needs_data_text, p.needs.joinToString { it.label }), style = Atlas.type.body, color = Color(0xFF6B4500))
        ui.downloadError?.let { Text(it, style = Atlas.type.caption, color = Atlas.colors.alert) }
        app.atlas.ui.kit.PrimaryButton(
            when {
                progress != null -> stringResource(R.string.go_downloading, (progress * 100).roundToInt())
                !online -> stringResource(R.string.go_download_offline)
                else -> stringResource(R.string.go_download_data)
            },
            onClick = vm::download, symbol = "ui-download", enabled = online && progress == null, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The board's bottom bar: the big blue Go with the navigation arrow and the chosen mode's time, and simulate beside it. */
@Composable
private fun GoBar(ui: RouteUi, modifier: Modifier, onGo: () -> Unit, onSimulate: () -> Unit) {
    val route = ui.route
    val label = route?.let { GoFormat.minutes(it.duration) } ?: stringResource(R.string.go_start)
    Row(
        modifier.fillMaxWidth().background(RouteColours.sheet).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(27.dp)).background(if (route != null) RouteColours.blue else Color(0xFFD8D4CC))
                .then(if (route != null) Modifier.button(stringResource(R.string.go_start) + ", " + label, onGo) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtIcon(BoardArt.navArrow.tinted, size = 20.dp, tint = Color.White)
            Text(label, style = RouteType.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Color.White)
        }
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(RouteColours.chip)
                .then(if (route != null) Modifier.button(stringResource(R.string.go_simulate), onSimulate) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            GoSymbol("go-simulate", tint = if (route != null) RouteColours.ink else Color(0xFFB5B9C2), size = 22.dp)
        }
    }
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
