package app.atlas.ui.mine.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.RegionLayer
import app.atlas.services.RegionRequest
import app.atlas.tiles.Pack
import app.atlas.ui.kit.Atlas
import app.atlas.ui.mine.MineState
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.MineSwitch
import app.atlas.ui.mine.TextPill
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.BoardSwitch
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.mt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Locale
import kotlin.math.roundToInt

/** What taking the chosen boxes would download. [bytes] sums the layers; null where one box could not say. */
private class Quote(val bytes: Map<RegionLayer, Long?>, val packed: Boolean, val kind: MapKind)

/**
 * Takes an area offline in one of three ways: draw a loop (or set a radius) around where you are, follow a route with a
 * corridor of a few kilometres, or take a whole published country pack. Each shows the size it would be, layer by layer, as
 * the engine works it out (from a pack, built on the phone, or older tiles), before anything is downloaded.
 */
@Composable
fun RegionPickerScreen(args: Dest.RegionPicker, nav: Navigator) {
    val services = LocalServices.current
    val manager = services.mine.offline
    val scope = rememberCoroutineScope()
    val mode = PickerModel.Mode.of(args.mode)
    val here = rememberHere()
    val centre = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    val details by manager.details.collectAsStateWithLifecycle()
    val wifi by services.settings.flow(MineKeys.WIFI_ONLY, true).collectAsStateWithLifecycle(true)
    val session by services.go.nav.collectAsStateWithLifecycle()
    val navView by remember(session) { session?.view ?: MutableStateFlow(null) }.collectAsStateWithLifecycle(null)
    val route = navView?.route?.points.orEmpty()
    val destination = session?.destinationName

    var radius by remember { mutableFloatStateOf(12f) }
    var drawn by remember { mutableStateOf<BBox?>(null) }
    var frozen by remember { mutableFloatStateOf(0f) }
    var corridor by remember { mutableFloatStateOf(10f) }
    var routing by remember { mutableStateOf(true) }
    var places by remember { mutableStateOf(true) }
    var packs by remember { mutableStateOf<List<Pack>?>(null) }
    var picked by remember { mutableStateOf<Pack?>(null) }
    var quote by remember { mutableStateOf<Quote?>(null) }
    var failed by remember { mutableStateOf(false) }
    var town by remember(centre) { mutableStateOf<String?>(null) }

    val layers = buildSet {
        add(RegionLayer.MAP); add(RegionLayer.VOICE)
        if (routing) add(RegionLayer.ROUTING)
        if (places) add(RegionLayer.PLACES)
    }
    val boxes: List<BBox> = remember(mode, drawn, radius, centre, route, corridor, picked) {
        when (mode) {
            PickerModel.Mode.AREA -> listOf(drawn ?: PickerModel.radiusBox(centre, radius.toDouble()))
            PickerModel.Mode.ROUTE -> if (route.size > 1) PickerModel.corridorBoxes(route, corridor * 1000.0) else emptyList()
            PickerModel.Mode.COUNTRY -> listOfNotNull(picked?.bbox)
        }
    }
    LaunchedEffect(mode) {
        if (mode == PickerModel.Mode.COUNTRY) packs = withContext(Dispatchers.IO) { runCatching { manager.engine.packs() }.getOrDefault(emptyList()) }
            .filter { it.atlas != null || it.places != null }.sortedBy { it.name }
    }
    LaunchedEffect(centre) { town = withContext(Dispatchers.IO) { runCatching { PickerModel.townNear(services.placeStores, centre, "") }.getOrNull()?.takeIf { it.isNotBlank() } } }
    LaunchedEffect(boxes, layers) {
        quote = null; failed = false
        if (boxes.isEmpty()) return@LaunchedEffect
        delay(250)
        withContext(Dispatchers.IO) {
            try {
                val e = manager.engine.estimateAll(boxes, layers)
                quote = Quote(e.bytes, e.packed, e.kind)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }

    val aroundHere = stringResource(R.string.mine_region_name)
    val routeName = destination?.let { stringResource(R.string.mine_picker_name_route, it) } ?: stringResource(R.string.mine_picker_name_route_plain)
    val baseName = when (mode) {
        PickerModel.Mode.AREA -> town?.let { stringResource(R.string.mine_picker_name_area, it) } ?: aroundHere
        PickerModel.Mode.ROUTE -> routeName
        PickerModel.Mode.COUNTRY -> picked?.name.orEmpty()
    }
    val q = quote
    val tooBig = q != null && boxes.any { PickerModel.tooBig(it, q.packed) }
    val ready = boxes.isNotEmpty() && q != null && !tooBig
    val totalBytes = q?.bytes?.values?.sumOf { it ?: 0L } ?: 0L
    val partName = stringResource(R.string.mine_picker_name_part)
    Box(Modifier.fillMaxSize()) {
    BoardPage(
        title = stringResource(when (mode) { PickerModel.Mode.AREA -> R.string.mine_region_pick; PickerModel.Mode.ROUTE -> R.string.mine_picker_title_route; PickerModel.Mode.COUNTRY -> R.string.mine_picker_title_country }),
        onBack = { nav.back() }, italic = false, titleSize = 28f, gap = 12.dp, bottomPadding = 110.dp,
    ) {
        when (mode) {
            PickerModel.Mode.AREA -> AreaBody(centre, radius, { radius = it }, drawn, frozen, onLoop = { loop ->
                PickerModel.lassoBox(loop)?.let { b -> if (drawn == null) frozen = (radius * 2.2f).coerceAtLeast(12f); drawn = b }
            }, onClear = { drawn = null }, boxes = boxes, pinLabel = town)
            PickerModel.Mode.ROUTE -> RouteBody(route, corridor, { corridor = it }, boxes)
            PickerModel.Mode.COUNTRY -> CountryBody(packs, picked, details.filter { it.complete }.map { it.id }.toSet()) { picked = it }
        }
        if (boxes.isNotEmpty()) {
            LayerChips(routing, { routing = it }, places, { places = it })
            val caption = when {
                q == null -> ""
                q.kind == MapKind.ATLAS && q.packed -> stringResource(R.string.mine_picker_from_pack)
                q.kind == MapKind.ATLAS -> stringResource(R.string.mine_picker_built)
                q.kind == MapKind.TILES -> stringResource(R.string.mine_picker_tiles)
                else -> stringResource(R.string.mine_est_unknown)
            } + if (boxes.size > 1) " · " + stringResource(R.string.mine_picker_parts, boxes.size) else ""
            EstimateCard(if (failed) emptyMap<RegionLayer, Long?>() else q?.bytes, caption)
            if (tooBig) Text(stringResource(R.string.mine_picker_too_big), style = mt(12f, 700, line = 16f), color = MineTone.red)
            if (failed) Text(stringResource(R.string.mine_picker_failed), style = mt(12f, 700, line = 16f), color = MineTone.red)
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BText(stringResource(R.string.mine_picker_wifi), bt(14f, 700, 18f), INK, Modifier.weight(1f))
                MineSwitch(wifi, { scope.launch { services.settings.set(MineKeys.WIFI_ONLY, it) } }, stringResource(R.string.mine_offline_wifi_only))
            }
        }
    }
    // The button sits over the page's foot, as the board's do.
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)) {
        val label = if (ready && totalBytes > 0) stringResource(R.string.mine_picker_download_size, RegionEngine.size(totalBytes)) else stringResource(R.string.mine_picker_download)
        Box(
            Modifier.fillMaxWidth().height(56.dp).bbox(if (ready) INK else 0xFFD5D8DE, cr(28f))
                .clickable(enabled = ready) {
                    val taken = details.map { it.id }.toMutableSet()
                    for ((i, b) in boxes.withIndex()) {
                        val name = PickerModel.unique(if (boxes.size > 1) "$baseName $partName ${i + 1}" else baseName, taken)
                        taken += RegionEngine.idFor(name)
                        manager.download(RegionRequest(name, b, layers))
                    }
                    nav.back()
                },
            contentAlignment = Alignment.Center,
        ) { BText(label, bt(15f, 800, 19.5f), WHITE) }
    }
    }
}

/* The Welcome board's dark pill and its white text. */
private val Color14 = Color(0xFF14161B)
private val OnPill = Color.White

@Composable
private fun AreaBody(
    centre: LatLon, radius: Float, onRadius: (Float) -> Unit, drawn: BBox?, frozen: Float, onLoop: (List<LatLon>) -> Unit, onClear: () -> Unit, boxes: List<BBox>, pinLabel: String?,
) {
    val services = LocalServices.current
    val half = (if (drawn != null) frozen else (radius * 2.2f).coerceAtLeast(12f)).toDouble()
    val view = remember(centre, half) { PickerModel.fitView(BBox.around(centre, half * 1000.0), 1.29f) }
    var dots by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    LaunchedEffect(view) { dots = withContext(Dispatchers.IO) { runCatching { OfflineModel.dots(services.placeStores, view, 8, 24, 24) }.getOrDefault(emptyList()) } }
    val cd = stringResource(R.string.mine_picker_map_cd)
    PreviewMap(view, Modifier.fillMaxWidth(), dots = dots, frames = boxes, pin = centre, pinLabel = pinLabel, onLoop = onLoop, description = cd)
    Text(stringResource(R.string.mine_picker_hint_area), style = mt(13f, 400, line = 18f), color = MineTone.of(0xFF5A5F6C, 0xFFA0A5B1))
    if (drawn == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.mine_picker_radius, radius.roundToInt()), style = mt(14f, 800, tabular = true))
            Slider(radius, onRadius, Modifier.weight(1f), valueRange = 2f..60f, colors = sliderColors())
        }
    } else {
        val (w, h) = PickerModel.sides(drawn)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.mine_picker_drawn, String.format(Locale.ROOT, "%.0f", w), String.format(Locale.ROOT, "%.0f", h)), Modifier.weight(1f), style = mt(14f, 800, tabular = true))
            TextPill(stringResource(R.string.mine_picker_clear), bt(12f, 700, 15.5f), WHITE, INK, h = 34f, padH = 12f, symbol = "ui.close", symbolSize = 14f, gap = 6f, onClick = onClear)
        }
    }
}

@Composable
private fun RouteBody(route: List<LatLon>, corridor: Float, onCorridor: (Float) -> Unit, boxes: List<BBox>) {
    if (route.size < 2) {
        MineState("ui.along-route", stringResource(R.string.mine_picker_route_none), text = stringResource(R.string.mine_picker_route_none_text), tint = MineTone.greenDeep)
        return
    }
    val services = LocalServices.current
    val all = remember(boxes) { boxes.reduce { a, b -> a.union(b) } }
    val view = remember(all) { PickerModel.fitView(all.buffer(2_000.0), 1.29f) }
    var dots by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    LaunchedEffect(view) { dots = withContext(Dispatchers.IO) { runCatching { OfflineModel.dots(services.placeStores, view, 8, 24, 24) }.getOrDefault(emptyList()) } }
    val cd = stringResource(R.string.mine_picker_map_cd)
    PreviewMap(view, Modifier.fillMaxWidth(), dots = dots, frames = boxes, route = route, description = cd)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.mine_picker_corridor, corridor.roundToInt()), style = mt(14f, 800, tabular = true))
        Slider(corridor, onCorridor, Modifier.weight(1f), valueRange = 2f..40f, colors = sliderColors())
    }
}

@Composable
private fun CountryBody(packs: List<Pack>?, picked: Pack?, have: Set<String>, onPick: (Pack) -> Unit) {
    when {
        packs == null -> Text(stringResource(R.string.mine_picker_country_loading), style = mt(13f, 500), color = MineTone.of(0xFF5A5F6C, 0xFFA0A5B1))
        packs.isEmpty() -> MineState("ui.web", stringResource(R.string.mine_picker_country_none), text = stringResource(R.string.mine_picker_country_none_text), tint = MineTone.greenDeep)
        else -> {
            if (picked != null) {
                val services = LocalServices.current
                val view = remember(picked) { PickerModel.fitView(picked.bbox.buffer(RegionEngine.area(picked.bbox).let { Math.sqrt(it) * 100.0 }), 1.29f) }
                var dots by remember(picked) { mutableStateOf(emptyList<Pair<Float, Float>>()) }
                LaunchedEffect(picked) { dots = withContext(Dispatchers.IO) { runCatching { OfflineModel.dots(services.placeStores, view, 8, 24, 24) }.getOrDefault(emptyList()) } }
                PreviewMap(view, Modifier.fillMaxWidth(), dots = dots, frames = listOf(picked.bbox), description = stringResource(R.string.mine_picker_map_cd))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in packs) {
                    val on = p.id == picked?.id
                    val size = RegionEngine.size(p.diskBytes.takeIf { it > 0 } ?: p.downloadBytes)
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Color14 else MineTone.of(0xFFFFFFFF, 0xFF1B1D22)).clickable { onPick(p) }.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("ui.web", tint = if (on) OnPill else Color(INK), size = 20.dp)
                        Text(p.name, Modifier.weight(1f), style = mt(15f, 800), color = if (on) OnPill else Color(INK), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (RegionEngine.idFor(p.name) in have) Symbol("ui.check", tint = MineTone.green, size = 16.dp, weight = 1.4f)
                        Text(size, style = mt(12f, 500, tabular = true), color = if (on) OnPill.copy(alpha = 0.75f) else MineTone.of(0xFF5A5F6C, 0xFFA0A5B1))
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerChips(routing: Boolean, onRouting: (Boolean) -> Unit, places: Boolean, onPlaces: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Chip(stringResource(R.string.mine_picker_layer_map), "ui.map", true, null)
        Chip(stringResource(R.string.mine_picker_layer_routes), "ui.route", routing) { onRouting(!routing) }
        Chip(stringResource(R.string.mine_picker_layer_places), "ui.search", places) { onPlaces(!places) }
    }
}

/** A layer to take: a pill of the boards (36 high, 12 px in), dark when taken, white when not. */
@Composable
private fun Chip(text: String, symbol: String, on: Boolean, onClick: (() -> Unit)?) {
    TextPill(text, bt(13f, 700, 17f), if (on) INK else WHITE, if (on) WHITE else INK, h = 36f, padH = 12f, symbol = symbol, symbolSize = 16f, gap = 6f, onClick = onClick)
}

@Composable
private fun sliderColors() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = Color(INK), activeTrackColor = Color(INK), inactiveTrackColor = Color(0xFFD5D8DE),
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
)
