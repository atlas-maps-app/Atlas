package app.atlas.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.BuildConfig
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.mapview.StageMap
import app.atlas.mapview.HomeView
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapLayer
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapStyle
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.Theme
import app.atlas.mapview.UserLocation
import app.atlas.mapview.vk.ChromeFade
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalChrome
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Categories
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.LocalFrostedGlass
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.backdropSource
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.markOf
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.search.SearchSheetModel
import app.atlas.ui.search.SearchSheet
import app.atlas.ui.search.FitToPlaces
import app.atlas.ui.search.FitToWalk
import app.atlas.ui.search.FollowCards
import app.atlas.ui.search.showsCard
import app.atlas.ui.search.PLACE_SHEET_HALF
import app.atlas.ui.search.SearchViewModel
import app.atlas.ui.search.bandBetween
import app.atlas.ui.search.walkLines
import app.atlas.ui.search.sheetMarkers
import app.atlas.ui.search.SEARCH_SHEET_STOPS
import app.atlas.ui.search.typedMarkers
import app.atlas.ui.search.bandOver
import app.atlas.ui.search.PlaceOverMap
import app.atlas.places.Brand
import app.atlas.ui.search.Asked
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Map home, as on the Main board: the map full screen with the location puck; the weather and offline chip, the
 * moments and the quick needs at the top left; the control column on the right; the category row and "Search or ask"
 * above the bottom bar. A category shows its places nearby as pins with a results sheet; a long press asks what is
 * there; a pin opens its place over the map, which keeps the view.
 */
@Composable
fun HomeScreen(args: Dest.Home, nav: Navigator) {
    val services = LocalServices.current
    val vm = viewModel(key = "home") { HomeViewModel(services, args.focus, args.zoom, pinned = nav.pins(args)) }
    val style by vm.style.collectAsStateWithLifecycle()
    val fix by vm.location.collectAsStateWithLifecycle()
    val permitted by vm.permitted.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val moments by vm.moments.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // The Main board's map labels in its Instrument Serif italic.
    val labelFace = remember { runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.instrument_serif_italic) }.getOrNull() }
    // Where the chrome stands as laid out (dp): no map label goes under it.
    val chromeBoxes = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val dpPerPx = 1f / LocalDensity.current.density
    fun Modifier.keepsLabelsOff(key: String): Modifier = onGloballyPositioned { c ->
        val b = c.boundsInRoot()
        val r = androidx.compose.ui.geometry.Rect(b.left * dpPerPx, b.top * dpPerPx, b.right * dpPerPx, b.bottom * dpPerPx)
        if (chromeBoxes[key] != r) chromeBoxes[key] = r
    }
    val online by services.connectivity.online.collectAsStateWithLifecycle()
    // The Look settings' Frame timings chip, its flow kept across this screen's frequent recompositions
    val timings by remember(services) { services.settings.flow(SettingsKeys.MAP_TIMINGS, true) }.collectAsStateWithLifecycle(true)
    val regions by services.regions.regions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val chrome = LocalChrome.current
    val barInset = LocalBottomBarInset.current
    val sheet = rememberSheetState(SheetStop.PEEK, SEARCH_SHEET_STOPS)
    val lang = remember { java.util.Locale.getDefault().language }

    // The one search (decision 12) in a sheet over this map: the category row opens it inside a kind, the search bar
    // on the grid of every kind; typed words show their results in it.
    val find = viewModel(key = "find") { SearchSheetModel(services, vm.map.camera.target, lang, weather = { vm.weather.value }, forecast = { vm.forecast.value }) }
    val typed = viewModel(key = "find-text") { SearchViewModel(services, "", null, { find.state.value.centre }, lang) }
    val found by find.state.collectAsStateWithLifecycle()
    val typedState by typed.state.collectAsStateWithLifecycle()
    // the weather the search weighs follows the chip's
    LaunchedEffect(weather) { find.skyChanged() }
    // what typed words open shows over the map: the sheet steps down to half, the places' pins above it
    LaunchedEffect(found.asked?.text) { if (found.asked != null && sheet.stop == SheetStop.FULL) sheet.animateTo(SheetStop.HALF) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findText by rememberSaveable { mutableStateOf("") }
    // opened on the grid (the search bar), back from a kind returns to it; from the category row it closes the sheet
    var findFromGrid by rememberSaveable { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    // The home's own row (HomeRow): Atlas's eight till the searcher makes it theirs; null till it is read, so that the
    // eight never show for a moment before the searcher's own.
    val row by remember(services) { services.settings.flow(SettingsKeys.HOME_ROW).map(HomeRow::read).flowOn(Dispatchers.Default) }
        .collectAsStateWithLifecycle<HomeRow.Kept?>(null)
    // the row being changed (a long press on a button), and the list of every kind open to add to it
    var rowEditing by rememberSaveable { mutableStateOf(false) }
    var rowAdding by rememberSaveable { mutableStateOf(false) }
    // the chain a button of the row opened, whose way back closes the sheet as a kind's does
    var rowChain by rememberSaveable { mutableStateOf<String?>(null) }
    // how far the row is scrolled and what it last showed, kept while the sheet is full over it
    val rowState = rememberOwnRowState()
    // Around the user when the map shows where they are, else around what the map shows.
    fun searchCentre(): LatLon {
        val target = vm.map.camera.target
        val me = fix?.location
        return if (me != null && app.atlas.geo.Geo.distance(me, target) < NEAR_ME_M) me else target
    }
    fun openFind(kind: String?, stop: SheetStop) {
        find.moveTo(searchCentre())
        if (kind == null) find.close() else find.enter(kind)
        findOpen = true
        findFromGrid = kind == null
        rowChain = null
        sheet.snapTo(stop)
    }
    fun closeFind() {
        findOpen = false
        findText = ""
        typed.onText("")
        rowChain = null
        find.close()
    }
    fun changeRow(change: (List<String>) -> List<String>) {
        scope.launch { HomeRow.edit(services.settings, change) }
    }
    // a chain in the row opens its page over the map as a kind's button opens the kind
    fun openChain(brand: Brand) {
        openFind(null, SheetStop.HALF)
        find.chain(brand)
        findFromGrid = false
        rowChain = brand.id
    }
    // "+" at the row's end: the list of every kind, to add one from
    fun addToRow() {
        rowAdding = true
        openFind(null, SheetStop.FULL)
        find.openEvery(picking = true)
    }
    // the list to add from closed, a kind picked or not: the sheet with it, back to the row as it is being changed
    LaunchedEffect(rowAdding) {
        if (!rowAdding) return@LaunchedEffect
        find.state.first { it.every?.picking != true }
        rowAdding = false
        closeFind()
    }
    // the sheet opened for anything else ends the change
    LaunchedEffect(findOpen) { if (findOpen && !rowAdding) rowEditing = false }

    // The search's sheet, or a place opened over the map, takes the bottom of the screen: the bar steps aside while
    // either is up.
    val showingResults = findOpen
    val opened = vm.opened
    LaunchedEffect(showingResults, opened != null) { chrome.barHidden = showingResults || opened != null }
    DisposableEffect(Unit) { onDispose { chrome.barHidden = false } }
    // Back from the system settings (or another app) the location may have been allowed or taken away.
    LifecycleResumeEffect(Unit) {
        services.location.refreshPermission()
        onPauseOrDispose {}
    }
    // Back steps out of typed words, then up from a kind to its shelf; a shelf (the category row's) closes the sheet.
    // The way back on the sheet's bands is this same back.
    fun stepBack() {
        val asked = found.asked
        when {
            findText.isNotEmpty() -> { findText = ""; typed.onText("") }
            // out of a chain a button of the row opened, the sheet closes as out of a kind it opened
            asked is Asked.Chain && asked.brand.id == rowChain && asked.from == null && found.within == null -> closeFind()
            // out of what the words asked, back to the words; out of a chain a sign opened, back to where it was
            asked != null && found.within == null -> find.backFromAsked()?.let { words -> findText = words; typed.onText(words) }
            asked != null -> find.up()
            found.node?.parent != null -> find.up()
            // out of the list of every kind to where it was opened, and from a kind opened on it back to it
            found.every?.let { e -> found.node == null || found.node?.id == e.entered } == true -> find.up()
            found.node != null && findFromGrid -> find.close()
            else -> closeFind()
        }
    }
    BackHandler(enabled = showingResults) { stepBack() }
    // back ends the row's change, where no sheet is up to step back in
    BackHandler(enabled = rowEditing && !showingResults) { rowEditing = false }

    // Spoken words go to the search's sheet; a phone that cannot recognise speech opens it with the keyboard instead.
    val mic = rememberSpeechInput(
        onText = { t -> openFind(null, SheetStop.HALF); findText = t; typed.onText(t) },
        onUnavailable = { openFind(null, SheetStop.FULL); runCatching { findFocus.requestFocus() } },
    )

    val edits by services.edits.all.collectAsStateWithLifecycle()
    val editWaiting = stringResource(R.string.osm_chip_waiting)
    val editNotSent = stringResource(R.string.osm_chip_not_sent)
    val pin = vm.pin
    // closed places lighter, as their rows are: open or not then as now, the sheet's
    val now = rememberNow(found.results)
    val markers = remember(findOpen, found.results, found.focus, found.prefs.cards, typedState.results, findText, highlights, pin, opened, edits, now) {
        buildList {
            // The search's places replace the area's highlights.
            if (!findOpen) addAll(HomeHighlights.markers(highlights))
            // The user's edits waiting to go or not sent, at their buildings: drawn as mapped once OpenStreetMap took them.
            addAll(EditMarkers.of(edits, editWaiting, editNotSent))
            if (findOpen) addAll(if (findText.isNotBlank()) typedMarkers(typedState.results, now, found.centre, found.prefs.mode) else sheetMarkers(found, now))
            // the point of a map view a link opened, until the map is moved away from it
            pin?.let { add(Marker("focus", it, "maki-marker", 0xFF2E5BFF, MarkerStyle.PIN, priority = 1_000)) }
            // An opened place's pin, chosen, as the Place board draws it: in place of its marker among the others.
            opened?.let { p ->
                removeAll { it.place?.key == p.key }
                val (symbol, colour) = markOf(p)
                add(Marker(p.key, p.location, symbol, colour.toLong() and 0xFFFFFFFFL, MarkerStyle.PIN, selected = true, priority = 1_000, place = p))
            }
        }
    }
    val overlays = MapOverlays(
        markers = markers,
        labels = labels,
        // the chrome as laid out, and the status bar's band (the board's top 40 px)
        labelKeepOut = chromeBoxes.values.toList() + androidx.compose.ui.geometry.Rect(0f, 0f, Float.MAX_VALUE, WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value.coerceAtLeast(40f)),
        routes = if (findOpen) walkLines(found) else emptyList(),
        location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) },
    )
    // Read the camera through derived states, so panning (a new camera every frame) does not recompose the screen.
    val coveredOffline by remember(regions) {
        derivedStateOf { vm.map.camera.target.let { t -> regions.any { it.complete && t in it.bbox } } }
    }
    val bearing by remember { derivedStateOf { vm.map.camera.bearing } }
    // The wash at the top takes the map's own paper: light by day, dark when the map has turned to night.
    val mapNight by remember(style) { derivedStateOf { mapIsNight(style, vm.map.time ?: Instant.now(), vm.map.camera.target) } }
    val offline = when {
        coveredOffline -> OfflineState.READY
        online -> OfflineState.ONLINE
        else -> OfflineState.NONE
    }

    val look = homeLook()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // The board's overlays start 48 dp down; a taller status bar pushes them below it.
    val top = maxOf(48.dp, statusTop + 8.dp)
    // The chrome over the map is solid unless the Look settings' frosted glass is on: then the boards' glass, which the
    // map's core blurs under each pill.
    val glass by services.frostedGlass.collectAsStateWithLifecycle(initialValue = false)

    CompositionLocalProvider(LocalFrostedGlass provides glass) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val screenHeight = maxHeight
      // A place opened from the map or the results comes up over this map, which keeps the user's view: it moves only
      // when the place would be under the place's sheet or buttons, by the least pan (the zoom, bearing and tilt kept).
      val placeBand = bandOver(PLACE_SHEET_HALF)
      val openPlace: (Place) -> Unit = { p ->
          services.remember(p)
          vm.open(p)
          vm.reveal(p.location, placeBand)
      }
      // The map and its wash are what the frosted pills, the control column and the tab bar blur behind them.
        StageMap(
            state = vm.map,
            modifier = Modifier.fillMaxSize(),
            style = style,
            overlays = overlays,
            // The board's principal point: 440 of 915 dp down.
            contentPadding = HomeView.padding(screenHeight.value),
            onTap = { hit ->
                // a tap on the map ends the row's change, and only that
                if (rowEditing) rowEditing = false
                else when (hit) {
                    is MapHit.OnMarker -> {
                        val p = hit.marker.place
                        when {
                            // the search's places as cards: a pin brings its card into view, the card opens it
                            p != null && findOpen && showsCard(found, findText, p) -> find.focus(p.key)
                            p != null -> openPlace(p)
                            EditMarkers.isEdit(hit.marker) -> nav.go(Dest.OsmAccount)
                        }
                    }
                    is MapHit.OnFeature -> openPlace(hit.place)
                    // a tap beside the places closes the one open
                    is MapHit.Empty -> vm.open(null)
                }
            },
            onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
            onLongPressBuilding = { b -> nav.go(Dest.WhatsHere(b.at.lat, b.at.lon, b.id.toString())) },
            onCameraMove = vm::onCameraMove,
            // Debug and bench builds show each exact frame's timings, so a phone can be read without logcat, unless the
            // Look settings' Frame timings chip is off.
            debug = BuildConfig.TIMINGS && timings,
            labelTypeface = labelFace,
            hostModifier = Modifier.backdropSource(LocalBackdrop.current),
            above = {
                // The board's wash over the top 170 dp, so the pills read over the far, busy part of the view.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(scrim(if (mapNight) NIGHT_PAPER else look.scrim)),
                )
            },
        )

        // Top left: the weather and offline state, what is coming up, the quick needs. The home's chrome steps aside for
        // an opened place (its boxes stay, so the map's labels keep their places).
        if (opened == null) Column(Modifier.padding(start = 16.dp, top = top).keepsLabelsOff("top")) {
            WeatherChip(weather, offline, onClick = { nav.go(Dest.Weather()) })
            if (moments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MomentsChip(moments.size, onClick = { nav.tab(app.atlas.nav.Tab.SOON) })
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }
            NeedsPill(
                onWater = { nav.go(Dest.Needs("water")) },
                onFood = { nav.go(Dest.Needs("food")) },
                onToilet = { nav.go(Dest.Needs("toilet")) },
            )
        }

        // Right: the controls.
        if (opened == null) ControlColumn(
            bearing = bearing,
            is3d = MapLayer.BUILDINGS_3D in style.layers,
            follow = vm.map.follow,
            onCompass = vm::northUp,
            on3d = vm::toggle3d,
            onLayers = { nav.go(Dest.Layers) },
            onLocate = {
                if (!permitted) services.location.refreshPermission()
                vm.locate()
            },
            onGlobe = { nav.go(Dest.Globe) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = top)
                .keepsLabelsOff("controls"),
        )

        // Bottom: attribution, the category row and the search bar, 90 dp up on the board (which steps aside for
        // results).
        val rowAlpha = if (showingResults && sheet.stop == SheetStop.FULL) 0f else 1f
        if (opened == null) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Read in the layout phase only: dragging the sheet moves the row without recomposing the screen.
                .offset { IntOffset(0, if (showingResults) -sheet.visibleHeightPx.roundToInt() else 0) }
                .padding(start = 12.dp, end = 12.dp, bottom = if (showingResults) 8.dp else barInset + 12.dp)
                .keepsLabelsOff("bottom"),
        ) {
            Text(
                stringResource(R.string.home_attribution),
                Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 10.dp),
                style = boardText(9f, FontWeight.Normal).copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        if (mapNight) Color.Black else look.halo,
                        blurRadius = with(LocalDensity.current) { 3.dp.toPx() },
                    ),
                ),
                color = if (mapNight) Color.White.copy(alpha = 0.7f) else look.attribution,
            )
            if (!permitted && !services.location.isDemo) {
                LocationPermissionCard(
                    onGranted = { services.location.refreshPermission() },
                    modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 10.dp),
                )
            }
            AnimatedVisibility(rowAlpha > 0f, enter = fadeIn(), exit = fadeOut()) {
                ChromeFade(this) {
                    val r = row
                    // its room kept till it is read
                    if (r == null) Spacer(Modifier.height(44.dp)) else {
                        val chosen = if (findOpen) HomeRow.chosen(found, r.buttons.map { it.id }) else null
                        OwnRow(
                            r.buttons, chosen,
                            onSelect = { b ->
                                when {
                                    b.id == chosen -> closeFind()
                                    b is HomeRow.Chain -> openChain(b.brand)
                                    else -> openFind(b.id, SheetStop.HALF)
                                }
                            },
                            editing = rowEditing,
                            onEditing = { rowEditing = it },
                            onOrder = { ids -> changeRow { ids } },
                            onRemove = { id -> changeRow { it - id } },
                            onAdd = ::addToRow,
                            onReset = if (r.own) ({ changeRow { HOME_CATEGORIES } }) else null,
                            lang = lang,
                            state = rowState,
                        )
                    }
                }
            }
            if (!showingResults) {
                SearchBar(
                    onSearch = { openFind(null, SheetStop.HALF) },
                    onNear = { nav.go(Dest.Query()) },
                    onMic = mic,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        if (opened == null && findOpen) {
            // a kind's nearest places come into the band over the sheet when they are not in view
            FitToPlaces(vm.map, found, bandBetween(top, maxHeight * FIND_BAND))
            FitToWalk(vm.map, found, bandBetween(top, maxHeight * FIND_BAND))
            FollowCards(vm.map, found, bandBetween(top, maxHeight * FIND_BAND))
            // cards are for sliding through under the map: a full sheet steps down to show it
            LaunchedEffect(found.prefs.cards) { if (found.prefs.cards && found.inside && sheet.stop == SheetStop.FULL) sheet.animateTo(SheetStop.HALF) }
            SearchSheet(
                find, typed, findText,
                onField = { findText = it; typed.onText(it) },
                sheet = sheet, nav = nav, lang = lang, onPlace = openPlace,
                onBack = ::stepBack,
                onPulledAway = ::closeFind,
                onTyping = { scope.launch { sheet.animateTo(SheetStop.FULL) } },
                focus = findFocus,
            )
        }

        // A place opened from the map or the results, over this map: its back closes it, back to the home as it was.
        opened?.let { p -> PlaceOverMap(Dest.Place.of(p), nav, vm.placeModels, onBack = { vm.open(null) }) }
    }
    }
}

/** How near the map's middle the user must be for the search to look around them (else around the map's middle). */
private const val NEAR_ME_M = 1_500.0

/** The share of the screen under the search's sheet when places are fitted into view. */
private const val FIND_BAND = 0.52f

/** The board's wash over the top of the map: [paper] at 90 % fading to nothing. */
private fun scrim(paper: Color) = Brush.verticalGradient(listOf(paper.copy(alpha = 0.9f), paper.copy(alpha = 0f)))

/** The night map's paper, under the wash when the map has turned to night. */
private val NIGHT_PAPER = Color(0xFF101114)

/** True when the map draws its night look for [style] at [time] over [at]: the engine's rule, the sun 2° under the horizon. */
internal fun mapIsNight(style: MapStyle, time: Instant, at: LatLon): Boolean = when (style.theme) {
    Theme.NIGHT -> true
    Theme.AUTO -> Sun.position(time, at.lat, at.lon).altitudeDeg < NIGHT_BELOW
    else -> false
}

/** amsterdam.py's weather_theme: night once the sun is more than 2° under the horizon. */
private const val NIGHT_BELOW = -2.0
