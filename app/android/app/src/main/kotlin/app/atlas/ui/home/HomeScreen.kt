package app.atlas.ui.home

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import app.atlas.places.Symbols
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.backdropSource
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.rememberSpeechInput
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The Map home, as on the Main board: the map full screen with the location puck; the weather and offline chip, the
 * moments and the quick needs at the top left; the control column on the right; the category row and "Search or ask"
 * above the bottom bar. A category shows its places nearby as pins with a results sheet; a long press asks what is
 * there; a pin opens its place.
 */
@Composable
fun HomeScreen(args: Dest.Home, nav: Navigator) {
    val services = LocalServices.current
    val vm = viewModel(key = "home") { HomeViewModel(services, args.focus, args.zoom) }
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val fix by vm.location.collectAsStateWithLifecycle()
    val permitted by vm.permitted.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val moments by vm.moments.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
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
    val regions by services.regions.regions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val chrome = LocalChrome.current
    val barInset = LocalBottomBarInset.current
    val sheet = rememberSheetState(SheetStop.PEEK, setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL))
    val lang = remember { java.util.Locale.getDefault().language }

    // The results sheet takes the bottom of the screen: the bar steps aside while it is up.
    val showingResults = results != null
    LaunchedEffect(showingResults) {
        chrome.barHidden = showingResults
        if (showingResults) sheet.snapTo(SheetStop.PEEK)
    }
    DisposableEffect(Unit) { onDispose { chrome.barHidden = false } }
    // Back from the system settings (or another app) the location may have been allowed or taken away.
    LifecycleResumeEffect(Unit) {
        services.location.refreshPermission()
        onPauseOrDispose {}
    }
    BackHandler(enabled = showingResults) { vm.selectCategory(null) }

    val openPlace: (Place) -> Unit = { p ->
        services.remember(p)
        nav.go(Dest.Place.of(p))
    }
    // Spoken words go to Search; a phone that cannot recognise speech opens Search with the keyboard instead.
    val mic = rememberSpeechInput(onText = { nav.go(Dest.Search(it)) }, onUnavailable = { nav.go(Dest.Search()) })

    val markers = remember(results, highlights, args.focus) {
        buildList {
            // A chosen category's places replace the area's highlights.
            if (results == null) addAll(HomeHighlights.markers(highlights))
            results?.let { r ->
                val colour = categoryColour(r.categoryId).toArgb().toLong() and 0xFFFFFFFFL
                r.places.forEachIndexed { i, p ->
                    add(Marker(p.key, p.location, Symbols.symbolFor(p.tags), colour, MarkerStyle.PIN, priority = r.places.size - i, place = p))
                }
            }
            args.focus?.let { add(Marker("focus", it, "maki-marker", 0xFF2E5BFF, MarkerStyle.PIN, priority = 1_000)) }
        }
    }
    val overlays = MapOverlays(
        markers = markers,
        labels = labels,
        // the chrome as laid out, and the status bar's band (the board's top 40 px)
        labelKeepOut = chromeBoxes.values.toList() + androidx.compose.ui.geometry.Rect(0f, 0f, Float.MAX_VALUE, WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value.coerceAtLeast(40f)),
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
      val screenHeight = maxHeight
      // The map and its wash are what the frosted pills, the control column and the tab bar blur behind them.
        StageMap(
            state = vm.map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            style = style,
            overlays = overlays,
            // The board's principal point: 440 of 915 dp down.
            contentPadding = HomeView.padding(screenHeight.value),
            onTap = { hit ->
                when (hit) {
                    is MapHit.OnMarker -> hit.marker.place?.let(openPlace)
                    is MapHit.OnFeature -> openPlace(hit.place)
                    is MapHit.Empty -> Unit
                }
            },
            onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
            onCameraMove = vm::onCameraMove,
            // Debug and bench builds show each exact frame's timings, so a phone can be read without logcat.
            debug = BuildConfig.TIMINGS,
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

        // Top left: the weather and offline state, what is coming up, the quick needs.
        Column(Modifier.padding(start = 16.dp, top = top).keepsLabelsOff("top")) {
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
        ControlColumn(
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
        Column(
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
                ChromeFade(this) { CategoryRow(results?.categoryId, onSelect = vm::selectCategory, lang = lang) }
            }
            if (!showingResults) {
                SearchBar(
                    onSearch = { nav.go(Dest.Search()) },
                    onNear = { nav.go(Dest.Query()) },
                    onMic = mic,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        results?.let { r ->
            ResultsSheet(
                results = r,
                state = sheet,
                from = fix?.location,
                lang = lang,
                onPlace = openPlace,
                onAll = { nav.go(Dest.Category(r.categoryId)) },
                onClose = { scope.launch { vm.selectCategory(null) } },
            )
        }
    }
}

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

/** The colour of a category as a Compose colour: the board's own for the home's eight, else the category's. */
internal fun categoryColour(id: String): Color =
    HOME_CATEGORY_LOOK[id]?.colour ?: Categories.byId(id)?.let { Color(it.colour) } ?: Color(0xFF14161B)
