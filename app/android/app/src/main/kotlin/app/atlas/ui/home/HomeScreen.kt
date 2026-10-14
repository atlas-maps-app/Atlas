package app.atlas.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapLayer
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalChrome
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Categories
import app.atlas.places.Symbols
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.rememberSpeechInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    val markers = remember(results, args.focus) {
        buildList {
            results?.let { r ->
                val colour = Categories.byId(r.categoryId)?.colour?.toLong()?.and(0xFFFFFFFFL) ?: 0xFF14161BL
                r.places.forEachIndexed { i, p ->
                    add(Marker(p.key, p.location, Symbols.symbolFor(p.tags), colour, MarkerStyle.PIN, priority = r.places.size - i, place = p))
                }
            }
            args.focus?.let { add(Marker("focus", it, "maki-marker", 0xFF2E5BFF, MarkerStyle.PIN, priority = 1_000)) }
        }
    }
    val overlays = MapOverlays(
        markers = markers,
        location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) },
    )
    // Read the camera through derived states, so panning (a new camera every frame) does not recompose the screen.
    val coveredOffline by remember(regions) {
        derivedStateOf { vm.map.camera.target.let { t -> regions.any { it.complete && t in it.bbox } } }
    }
    val bearing by remember { derivedStateOf { vm.map.camera.bearing } }
    val offline = when {
        coveredOffline -> OfflineState.READY
        online -> OfflineState.ONLINE
        else -> OfflineState.NONE
    }

    Box(Modifier.fillMaxSize()) {
        AtlasMap(
            state = vm.map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            style = style,
            overlays = overlays,
            contentPadding = PaddingValues(top = 140.dp, bottom = 200.dp),
            onTap = { hit ->
                when (hit) {
                    is MapHit.OnMarker -> hit.marker.place?.let(openPlace)
                    is MapHit.OnFeature -> openPlace(hit.place)
                    is MapHit.Empty -> Unit
                }
            },
            onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
            onCameraMove = vm::onCameraMove,
        )

        // Top left: the weather and offline state, what is coming up, the quick needs.
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WeatherChip(weather, offline, onClick = { nav.go(Dest.Weather()) })
            if (moments.isNotEmpty()) MomentsChip(moments.size, onClick = { nav.tab(app.atlas.nav.Tab.SOON) })
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
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = 16.dp, top = 12.dp),
        )

        // Bottom: attribution, the category row, and the search bar (which steps aside for results).
        val rowAlpha = if (showingResults && sheet.stop == SheetStop.FULL) 0f else 1f
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Read in the layout phase only: dragging the sheet moves the row without recomposing the screen.
                .offset { IntOffset(0, if (showingResults) -sheet.visibleHeightPx.roundToInt() else 0) }
                .padding(bottom = if (showingResults) 8.dp else barInset),
        ) {
            Text(
                stringResource(R.string.home_attribution),
                Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 4.dp),
                style = Atlas.type.caption,
                color = Atlas.colors.inkSoft.copy(alpha = 0.85f),
            )
            if (!permitted && !services.location.isDemo) {
                LocationPermissionCard(
                    onGranted = { services.location.refreshPermission() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            AnimatedVisibility(rowAlpha > 0f, enter = fadeIn(), exit = fadeOut()) {
                CategoryRow(results?.categoryId, onSelect = vm::selectCategory, lang = lang)
            }
            if (!showingResults) {
                SearchBar(
                    onSearch = { nav.go(Dest.Search()) },
                    onNear = { nav.go(Dest.Query()) },
                    onMic = mic,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
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

/** The colour of a category as a Compose colour. */
internal fun categoryColour(id: String): Color = Categories.byId(id)?.let { Color(it.colour) } ?: Color(0xFF14161B)
