package app.atlas.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.atlas.ui.kit.cssLinear
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Sun
import app.atlas.mapview.StageMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapLayer
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.Theme
import app.atlas.mapview.UserLocation
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.softShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The map's look: the real map behind a sheet, so every switch shows at once. Theme chips along the top (auto day and
 * night, day, night, chart, calm); in the sheet the layers (3D buildings, shadows, trees, places, labels, hillshade,
 * contours, transit, cycle routes, hiking, sea marks), the time of light with a scrubber that moves the sun and its
 * shadows, the language of names, and how much of the streets here OpenStreetMap knows (lighting, surfaces, pavements,
 * parking), with the way to fill in the gaps. Choices are the Map's own settings, kept on the phone.
 */
@Composable
fun LayersScreen(args: Dest.Layers, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val here = rememberHere()
    val fix by services.location.last.collectAsStateWithLifecycle()
    val settings = services.settings
    val themeName by settings.flow(SettingsKeys.MAP_THEME, Theme.AUTO.name).collectAsStateWithLifecycle(Theme.AUTO.name)
    val saved by settings.flow(SettingsKeys.MAP_LAYERS).collectAsStateWithLifecycle(null)
    val threeD by settings.flow(SettingsKeys.MAP_3D, true).collectAsStateWithLifecycle(true)
    val haze by settings.flow(SettingsKeys.MAP_HAZE, true).collectAsStateWithLifecycle(true)
    val vignette by settings.flow(SettingsKeys.MAP_VIGNETTE, true).collectAsStateWithLifecycle(true)
    val grain by settings.flow(SettingsKeys.MAP_GRAIN, true).collectAsStateWithLifecycle(true)
    val language by settings.flow(SettingsKeys.NAME_LANGUAGE).collectAsStateWithLifecycle(null)
    val twelve by settings.flow(MineKeys.CLOCK_12, false).collectAsStateWithLifecycle(false)
    val theme = runCatching { Theme.valueOf(themeName) }.getOrDefault(Theme.AUTO)
    val layers = LayersModel.layers(saved, threeD, MapStyle().layers)
    val clock = appClock()
    val zone = clock.zone
    val today: LocalDate = remember(clock) { LocalDate.now(clock) }
    var minutes by remember { mutableIntStateOf(LayersModel.minuteOf(clock.instant(), zone)) }
    val map = remember(here) { MapState(CameraPosition(here, 16.4, 0.0, 42.0)) }
    map.time = LayersModel.at(today, minutes, zone)
    val style = MapStyle(theme = theme, layers = layers, language = language, calm = true)
    val sun = remember(here, today) { LayersModel.sunMinutes(today, here, zone) }
    var coverage by remember(here) { mutableStateOf<StreetCoverage?>(null) }
    var coverageDone by remember(here) { mutableStateOf(false) }
    LaunchedEffect(here) {
        coverage = withContext(Dispatchers.IO) { runCatching { streetsAround(services, here) }.getOrNull() }
        coverageDone = true
    }
    Box(Modifier.fillMaxSize()) {
        StageMap(
            state = map,
            modifier = Modifier.fillMaxSize(),
            background = Color(0xFFF4F1EA),
            style = style,
            overlays = MapOverlays(location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) }),
            contentPadding = PaddingValues(top = 150.dp, bottom = 440.dp),
        )
        // the Overlay board's wash over the map's top: the paper at 95 % fading out over 170 px
        Box(Modifier.fillMaxWidth().height(170.dp + shift()).bbox(grad = listOf(cssLinear(180f, 0f to 0xF2F4F1EA, 1f to 0x00F4F1EA))))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // the title row at y 42 with the time of light in the board's white pill (34 high, 12 px in, 12, 800)
            TitleRow(stringResource(R.string.mine_layers), onBack = { nav.back() }) {
                TextPill(
                    LayersModel.clock(minutes, twelve), bt(12f, 800, 15.5f, tnum = true), WHITE, INK, h = 34f, padH = 12f,
                    symbol = if (Sun.position(map.time!!, here).altitudeDeg > -2) "weather.sun" else "ui.moon", symbolSize = 15f, gap = 5f,
                )
            }
            // the theme chips from x 16, 6 px apart, running off the right edge as the board's do
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in LayersModel.THEMES) ThemeChip(t, selected = t == theme) { scope.launch { settings.set(SettingsKeys.MAP_THEME, t.name) } }
            }
        }
        // The sheet.
        // the board's sheet: white, radius 30 on top, its shadow 10 px up, 16 px in, the grabber (40 × 5) 10 px down
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(430.dp)
                .bbox(WHITE, app.atlas.ui.kit.CssCorners(30f, 30f, 0f, 0f), listOf(app.atlas.ui.kit.CssShadow(0f, -10f, 30f, 0f, 0x2914161B)))
                .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).bbox(0xFFD5D8DE, cr(3f)))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LayerChips(layers) { layer, on ->
                    scope.launch {
                        if (layer == MapLayer.BUILDINGS_3D) settings.set(SettingsKeys.MAP_3D, on)
                        else settings.set(SettingsKeys.MAP_LAYERS, LayersModel.toggled(layers, layer, on).map { it.name }.toSet())
                    }
                }
                ScreenChips(
                    haze, vignette, grain,
                    onHaze = { on -> scope.launch { settings.set(SettingsKeys.MAP_HAZE, on) } },
                    onVignette = { on -> scope.launch { settings.set(SettingsKeys.MAP_VIGNETTE, on) } },
                    onGrain = { on -> scope.launch { settings.set(SettingsKeys.MAP_GRAIN, on) } },
                )
                LightScrubber(minutes, sun, twelve, onChange = { minutes = it }, onNow = { minutes = LayersModel.minuteOf(clock.instant(), zone) })
                NamesRow(language) { code -> scope.launch { settings.set(SettingsKeys.NAME_LANGUAGE, code) } }
                CoverageCard(coverage, coverageDone, onQuests = { nav.go(Dest.Quests(here.lat, here.lon)) })
            }
        }
    }
}

/** The Overpass tags of the highways within 400 m of [at], judged: null when offline or nothing came back. */
private suspend fun streetsAround(services: app.atlas.Services, at: app.atlas.geo.LatLon): StreetCoverage? {
    if (services.config.offline || !services.connectivity.online.value) return null
    // The Overpass source may be switched off in Data sources: then nothing is asked of it.
    if ("overpass" in services.settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).first()) return null
    val tags = ArrayList<Map<String, String>>()
    services.overpass.stream(StreetCoverage.query(at)) { tags += it.tags }
    return StreetCoverage.of(tags)
}

/** A theme chip over the map, as the board's: 34 high, 12 px in, 12, 800; dark when chosen, 95 % white otherwise, a soft shadow. */
@Composable
private fun ThemeChip(theme: Theme, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when (theme) { Theme.AUTO -> R.string.mine_theme_auto; Theme.DAY -> R.string.mine_theme_day; Theme.NIGHT -> R.string.mine_theme_night; Theme.CHART -> R.string.mine_theme_chart; Theme.GRAY -> R.string.mine_theme_calm },
    )
    TextPill(
        label, bt(12f, 800, 15.5f), if (selected) INK else 0xF2FFFFFF, if (selected) WHITE else INK, h = 34f, padH = 12f,
        shadows = listOf(app.atlas.ui.kit.CssShadow(0f, 3f, 10f, 0f, 0x2414161B)), onClick = onClick,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LayerChips(layers: Set<MapLayer>, onToggle: (MapLayer, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardLabel(stringResource(R.string.mine_layers_layers))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (info in LayersModel.LAYERS) {
                val on = info.layer in layers
                val name = stringResource(layerName(info.key))
                // a layer: 36 high, 12 px in, its 17 px symbol and name (12, 700); dark when on, the board's pale tiles' grey when off
                TextPill(
                    name, bt(12f, 700, 15.5f), if (on) INK else 0xFFF4F5F7, if (on) WHITE else INK, Modifier.semantics { contentDescription = name },
                    h = 36f, padH = 12f, symbol = info.symbol, symbolSize = 17f, gap = 6f, onClick = { onToggle(info.layer, !on) },
                )
            }
        }
    }
}

/**
 * The screen's effects over the map, each a chip as the layers' are (dark when on): the haze that thickens with the
 * ground's distance and the vignette that darkens the corners, both the renders' and on by default; off, the map keeps
 * its own colours there (and the phone does less work a pixel).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScreenChips(
    haze: Boolean,
    vignette: Boolean,
    grain: Boolean,
    onHaze: (Boolean) -> Unit,
    onVignette: (Boolean) -> Unit,
    onGrain: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardLabel(stringResource(R.string.mine_layers_screen))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, on, toggle) in listOf(
                Triple(stringResource(R.string.mine_layers_haze), haze, onHaze),
                Triple(stringResource(R.string.mine_layers_vignette), vignette, onVignette),
                Triple(stringResource(R.string.mine_layers_grain), grain, onGrain),
            )) {
                TextPill(
                    name, bt(12f, 700, 15.5f), if (on) INK else 0xFFF4F5F7, if (on) WHITE else INK,
                    Modifier.semantics { contentDescription = name },
                    h = 36f, padH = 12f, onClick = { toggle(!on) },
                )
            }
        }
    }
}

private fun layerName(key: String): Int = when (key) {
    "buildings" -> R.string.mine_layers_3d
    "shadows" -> R.string.mine_layers_shadows
    "trees" -> R.string.mine_layers_trees
    "pois" -> R.string.mine_layers_pois
    "labels" -> R.string.mine_layers_labels
    "hillshade" -> R.string.mine_layers_hillshade
    "contours" -> R.string.mine_layers_contours
    "transit" -> R.string.mine_layers_transit
    "cycle" -> R.string.mine_layers_cycle
    "hiking" -> R.string.mine_layers_hiking
    else -> R.string.mine_layers_seamarks
}

/**
 * The time of light: a track of the day's own light (night blue, dawn and dusk amber, day white) with sunrise and sunset
 * marked, a thumb you drag, and quick ways to sunrise, noon, sunset and now. The map behind redraws its sun and shadows.
 */
@Composable
private fun LightScrubber(minutes: Int, sun: Triple<Int?, Int, Int?>, twelve: Boolean, onChange: (Int) -> Unit, onNow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(stringResource(R.string.mine_layers_light), Modifier.weight(1f))
            BText(LayersModel.clock(minutes, twelve), bt(14f, 800, 18f, tnum = true), INK)
        }
        val description = stringResource(R.string.mine_layers_light_cd, LayersModel.clock(minutes, twelve))
        Canvas(
            Modifier.fillMaxWidth().height(34.dp).semantics {
                contentDescription = description
                // A slider to a screen reader too: it can be set by the accessibility actions, not only by a finger on the track.
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(minutes.toFloat(), 0f..1439f)
                setProgress { v -> onChange(v.toInt().coerceIn(0, 1439)); true }
            }
                .pointerInput(Unit) { detectTapGestures { p -> onChange((p.x / size.width * 1439).toInt().coerceIn(0, 1439)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onChange((change.position.x / size.width * 1439).toInt().coerceIn(0, 1439)) } },
        ) {
            val track = Size(size.width, 14.dp.toPx())
            val top = (size.height - track.height) / 2
            fun frac(m: Int) = m / 1439f
            val rise = sun.first?.let(::frac)
            val set = sun.third?.let(::frac)
            val night = Color(0xFF1D2A52)
            val dawn = Color(0xFFF2A65A)
            val day = Color(0xFFFFF3C4)
            val stops = if (rise != null && set != null) arrayOf(
                0f to night, (rise - 0.05f).coerceAtLeast(0.01f) to night, rise to dawn, (rise + 0.05f) to day, (set - 0.05f) to day, set to dawn, (set + 0.05f).coerceAtMost(0.99f) to night, 1f to night,
            ) else arrayOf(0f to night, 1f to night)
            drawRoundRect(Brush.horizontalGradient(*stops, startX = 0f, endX = size.width), Offset(0f, top), track, CornerRadius(7.dp.toPx()))
            val x = frac(minutes) * size.width
            drawCircle(Color.White, 12.dp.toPx(), Offset(x.coerceIn(12.dp.toPx(), size.width - 12.dp.toPx()), size.height / 2))
            drawCircle(Color(INK), 12.dp.toPx(), Offset(x.coerceIn(12.dp.toPx(), size.width - 12.dp.toPx()), size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sun.first?.let { MiniButton(stringResource(R.string.mine_layers_sunrise), { onChange(it) }, primary = false, symbol = "weather.sunrise.2") }
            MiniButton(stringResource(R.string.mine_layers_noon), { onChange(sun.second) }, primary = false, symbol = "weather.sun")
            sun.third?.let { MiniButton(stringResource(R.string.mine_layers_sunset), { onChange(it) }, primary = false, symbol = "weather.sunset.3") }
            MiniButton(stringResource(R.string.mine_layers_now), onNow)
        }
    }
}

@Composable
private fun NamesRow(current: String?, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardLabel(stringResource(R.string.mine_layers_names))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (code in SettingsModel.NAME_LANGUAGES) {
                val on = code == current
                TextPill(code?.uppercase() ?: stringResource(R.string.mine_layers_local), bt(12f, 800, 15.5f), if (on) INK else 0xFFF4F5F7, if (on) WHITE else INK, h = 32f, padH = 12f) { onPick(code) }
            }
        }
    }
}

/** What OpenStreetMap knows of the streets here, as four bars; with no connection, a line that says so. */
@Composable
private fun CoverageCard(coverage: StreetCoverage?, done: Boolean, onQuests: () -> Unit) {
    // the board's pale tile (radius 16, 14 px in): what is known of the streets here, and the way to fill the gaps
    Column(
        Modifier.fillMaxWidth().bbox(0xFFF4F5F7, cr(16f)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onQuests).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(stringResource(R.string.mine_layers_known), Modifier.weight(1f))
            if (coverage != null) BText(stringResource(R.string.mine_layers_known_pct, coverage.known), bt(12f, 800, 15.5f, tnum = true), INK)
        }
        if (coverage == null) {
            BText(stringResource(if (done) R.string.mine_layers_known_none else R.string.kit_loading), bt(12f, 400, 15.5f), SOFT, lines = 3)
        } else {
            for ((label, share, colour) in listOf(
                Triple(R.string.mine_layers_lighting, coverage.lighting, Color(0xFFF2C230)),
                Triple(R.string.mine_layers_surfaces, coverage.surfaces, Color(0xFF8C5A2B)),
                Triple(R.string.mine_layers_sidewalks, coverage.sidewalks, Color(0xFF2E6BFF)),
                Triple(R.string.mine_layers_parking, coverage.parking, Color(0xFF8E3CF7)),
            )) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BText(stringResource(label), bt(12f, 700, 15.5f), INK, Modifier.width(78.dp))
                Box(Modifier.weight(1f).height(8.dp).bbox(0xFFE3E6EB, cr(4f))) { Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(8.dp).background(colour)) }
                BText("${Math.round(share * 100)}%", bt(12f, 700, 15.5f, tnum = true, align = androidx.compose.ui.text.style.TextAlign.End), SOFT, Modifier.width(36.dp))
            }
            BText(stringResource(R.string.mine_layers_known_help), bt(11f, 400, 14f), FAINT, lines = 3)
        }
    }
}
