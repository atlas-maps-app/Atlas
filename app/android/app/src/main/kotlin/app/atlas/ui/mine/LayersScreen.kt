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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Sun
import app.atlas.mapview.AtlasMap
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
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val fix by services.location.last.collectAsStateWithLifecycle()
    val settings = services.settings
    val themeName by settings.flow(SettingsKeys.MAP_THEME, Theme.AUTO.name).collectAsStateWithLifecycle(Theme.AUTO.name)
    val saved by settings.flow(SettingsKeys.MAP_LAYERS).collectAsStateWithLifecycle(null)
    val threeD by settings.flow(SettingsKeys.MAP_3D, true).collectAsStateWithLifecycle(true)
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
    Box(Modifier.fillMaxSize().background(Atlas.colors.paper)) {
        AtlasMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            style = style,
            overlays = MapOverlays(location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) }),
            contentPadding = PaddingValues(top = 150.dp, bottom = 440.dp),
        )
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color(0xF2F4F1EA), Color(0x00F4F1EA)))))
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val back = stringResource(R.string.kit_back)
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) { Symbol("ui.back", tint = Color(0xFF14161B), size = 20.dp) }
                Text(stringResource(R.string.mine_layers), Modifier.weight(1f), style = serifTitle(32f), color = Color(0xFF14161B))
                Box(Modifier.height(34.dp).clip(CircleShape).background(Color.White).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(if (Sun.position(map.time!!, here).altitudeDeg > -2) "weather.sun" else "ui.moon", tint = Color(0xFF14161B), size = 15.dp)
                        Text(LayersModel.clock(minutes, twelve), style = mt(12f, 800, tabular = true), color = Color(0xFF14161B))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(end = 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in LayersModel.THEMES) ThemeChip(t, selected = t == theme) { scope.launch { settings.set(SettingsKeys.MAP_THEME, t.name) } }
            }
        }
        // The sheet.
        val sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(430.dp).softShadow(sheet, Color(0x29141E3A), 18.dp).clip(sheet).background(MineTone.of(0xFFFFFFFF, 0xFF1B1D22))
                .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(MineTone.of(0xFFD5D8DE, 0xFF3A3E46)))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LayerChips(layers) { layer, on ->
                    scope.launch {
                        if (layer == MapLayer.BUILDINGS_3D) settings.set(SettingsKeys.MAP_3D, on)
                        else settings.set(SettingsKeys.MAP_LAYERS, LayersModel.toggled(layers, layer, on).map { it.name }.toSet())
                    }
                }
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

/** A theme chip over the map: dark when chosen, frosted white otherwise (34 dp, 12 sp, 800), as the overlay board's chips. */
@Composable
private fun ThemeChip(theme: Theme, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when (theme) { Theme.AUTO -> R.string.mine_theme_auto; Theme.DAY -> R.string.mine_theme_day; Theme.NIGHT -> R.string.mine_theme_night; Theme.CHART -> R.string.mine_theme_chart; Theme.GRAY -> R.string.mine_theme_calm },
    )
    Box(
        Modifier.height(34.dp).softShadow(CircleShape, Color(0x24141E3A), 6.dp).clip(CircleShape).background(if (selected) Color(0xFF14161B) else Color(0xF2FFFFFF)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = mt(12f, 800), color = if (selected) Color.White else Color(0xFF14161B), maxLines = 1) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LayerChips(layers: Set<MapLayer>, onToggle: (MapLayer, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.mine_layers_layers), colour = MineTone.steel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (info in LayersModel.LAYERS) {
                val on = info.layer in layers
                val name = stringResource(layerName(info.key))
                val c = Atlas.colors
                Row(
                    Modifier.height(36.dp).clip(CircleShape).background(if (on) c.pill else MineTone.of(0xFFF1F3F6, 0xFF2A2D34)).clickable { onToggle(info.layer, !on) }.padding(horizontal = 12.dp).semantics { contentDescription = name },
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(info.symbol, tint = if (on) c.onPill else c.ink, size = 17.dp)
                    Text(name, style = mt(12f, 700), color = if (on) c.onPill else c.ink, maxLines = 1)
                }
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
    val c = Atlas.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.mine_layers_light), Modifier.weight(1f), MineTone.steel)
            Text(LayersModel.clock(minutes, twelve), style = mt(14f, 800, tabular = true), color = c.ink)
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
            drawCircle(Color(0xFF14161B), 12.dp.toPx(), Offset(x.coerceIn(12.dp.toPx(), size.width - 12.dp.toPx()), size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sun.first?.let { MiniButton(stringResource(R.string.mine_layers_sunrise), { onChange(it) }, primary = false, symbol = "weather.sunrise") }
            MiniButton(stringResource(R.string.mine_layers_noon), { onChange(sun.second) }, primary = false, symbol = "weather.sun")
            sun.third?.let { MiniButton(stringResource(R.string.mine_layers_sunset), { onChange(it) }, primary = false, symbol = "weather.sunset") }
            MiniButton(stringResource(R.string.mine_layers_now), onNow)
        }
    }
}

@Composable
private fun NamesRow(current: String?, onPick: (String?) -> Unit) {
    val c = Atlas.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.mine_layers_names), colour = MineTone.steel)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (code in SettingsModel.NAME_LANGUAGES) {
                val on = code == current
                Box(Modifier.height(32.dp).clip(CircleShape).background(if (on) c.pill else MineTone.of(0xFFF1F3F6, 0xFF2A2D34)).clickable { onPick(code) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(code?.uppercase() ?: stringResource(R.string.mine_layers_local), style = mt(12f, 800), color = if (on) c.onPill else c.ink)
                }
            }
        }
    }
}

/** What OpenStreetMap knows of the streets here, as four bars; with no connection, a line that says so. */
@Composable
private fun CoverageCard(coverage: StreetCoverage?, done: Boolean, onQuests: () -> Unit) {
    val c = Atlas.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MineTone.of(0xFFF4F5F7, 0xFF24272D)).clickable(onClick = onQuests).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.mine_layers_known), Modifier.weight(1f), MineTone.steel)
            if (coverage != null) Text(stringResource(R.string.mine_layers_known_pct, coverage.known), style = mt(12f, 800, tabular = true), color = c.ink)
        }
        if (coverage == null) {
            Text(stringResource(if (done) R.string.mine_layers_known_none else R.string.kit_loading), style = mt(12f, 400, line = 16f), color = c.inkSoft)
        } else {
            for ((label, share, colour) in listOf(
                Triple(R.string.mine_layers_lighting, coverage.lighting, Color(0xFFF2C230)),
                Triple(R.string.mine_layers_surfaces, coverage.surfaces, Color(0xFF8C5A2B)),
                Triple(R.string.mine_layers_sidewalks, coverage.sidewalks, Color(0xFF2E6BFF)),
                Triple(R.string.mine_layers_parking, coverage.parking, Color(0xFF8E3CF7)),
            )) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = mt(12f, 700), color = c.ink, modifier = Modifier.width(78.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(MineTone.of(0xFFE3E6EB, 0xFF3A3E46))) { Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(8.dp).background(colour)) }
                Text("${Math.round(share * 100)}%", style = mt(12f, 700, tabular = true), color = c.inkSoft, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Text(stringResource(R.string.mine_layers_known_help), style = mt(11f, 400, line = 15f), color = c.inkFaint)
        }
    }
}
