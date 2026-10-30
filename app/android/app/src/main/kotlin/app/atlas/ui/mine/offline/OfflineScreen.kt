package app.atlas.ui.mine.offline

import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.RegionLayer
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.mine.MineState
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.FAINT
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.MSy
import app.atlas.ui.mine.SERIF
import app.atlas.ui.mine.TextPill
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
import app.atlas.ui.mine.ring
import app.atlas.ui.mine.shift
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.BoardSwitch
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.mt
import app.atlas.ui.mine.serifTitle
import androidx.compose.ui.res.pluralStringResource
import app.atlas.mapview.MapEngine
import app.atlas.mapview.vk.Windows
import app.atlas.tiles.Pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/* The Offline board's paper (`canvas/project/Offline.dc.html`). */
private const val PAPER = 0xFFE9E3D8
private const val BODY = 0xFF464C5A

/**
 * The Offline board: the regions on this phone, each a paper card with a map drawn from its own places, the total with
 * the five-colour storage bar, add buttons (draw an area, along a route, a whole country, automatic) and the voices.
 */
@Composable
fun OfflineScreen(args: Dest.Offline, nav: Navigator) {
    val services = LocalServices.current
    val manager = services.mine.offline
    val details by manager.details.collectAsStateWithLifecycle()
    val wifi by services.settings.flow(MineKeys.WIFI_ONLY, true).collectAsStateWithLifecycle(true)
    val auto by services.settings.flow(MineKeys.AUTO_REGIONS, false).collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    val clock = appClock()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { manager.engine.checkUpdates() } } }
    val capacity = remember { runCatching { StatFs(services.dirs.files.also { it.mkdirs() }.path).totalBytes }.getOrDefault(0L) }
    var asking by remember { mutableStateOf<RegionDetail?>(null) }
    BoardPage(title = "", onBack = null, header = false, background = Color(PAPER), gap = 16.dp) {
        // The header at y 48: the 48 px back round, the upright serif title (36 on 36) and the green "works offline" round.
        Row(Modifier.fillMaxWidth().padding(top = 48.dp + shift()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.kit_back)
            Box(
                Modifier.size(48.dp).bbox(0xFFF6F2EA, cr(24f)).clickable(role = Role.Button) { nav.back() }.semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) { MSy("ui.back", 22f, INK) }
            BText(stringResource(R.string.mine_offline_title), bt(36f, 400, 36f, SERIF), INK, Modifier.weight(1f).semantics { heading() })
            val works = stringResource(R.string.mine_offline_works)
            Box(Modifier.size(48.dp).bbox(0xFF178A4E, cr(24f)).semantics { contentDescription = works }, contentAlignment = Alignment.Center) {
                MSy("ui.offline", 22f, WHITE)
            }
        }
        val age = OfflineModel.refreshAge(details, clock.instant())?.let { (unit, n) ->
            stringResource(
                when (unit) {
                    OfflineModel.AgeUnit.NOW -> R.string.mine_age_now
                    OfflineModel.AgeUnit.MINUTES -> R.string.mine_age_min
                    OfflineModel.AgeUnit.HOURS -> R.string.mine_age_h
                    OfflineModel.AgeUnit.DAYS -> R.string.mine_age_d
                    OfflineModel.AgeUnit.WEEKS -> R.string.mine_age_w
                },
                n.toInt(),
            )
        }
        StorageCard(details, capacity, age)
        CountryCards(manager, details, nav)
        if (details.isEmpty()) {
            MineState("ui.offline", stringResource(R.string.mine_offline_none_title), text = stringResource(R.string.mine_offline_none_text), tint = Color(0xFF178A4E))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (d in details) RegionCard(d, manager, onDelete = { asking = d })
            }
        }
        AddRow(
            auto = auto,
            onArea = { nav.go(Dest.RegionPicker(mode = "area")) },
            onRoute = { nav.go(Dest.RegionPicker(mode = "route")) },
            onCountry = { nav.go(Dest.RegionPicker(mode = "country")) },
            onAuto = { scope.launch { services.settings.set(MineKeys.AUTO_REGIONS, !auto) } },
        )
        VoicesRow(wifi = wifi, onWifi = { scope.launch { services.settings.set(MineKeys.WIFI_ONLY, it) } })
        BText(stringResource(R.string.mine_offline_footer), bt(10f, 500, 15f), FAINT, Modifier.padding(horizontal = 8.dp), lines = 4)
        Spacer(Modifier.height(LocalBottomBarInset.current))
    }
    asking?.let { d ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.mine_offline_delete_title, d.name)) },
            text = { Text(stringResource(R.string.mine_offline_delete_text, RegionEngine.size(d.bytes))) },
            confirmButton = { TextButton({ asking = null; scope.launch { manager.delete(d.id) } }) { Text(stringResource(R.string.mine_offline_delete_confirm), color = Color(0xFFC8322A)) } },
            dismissButton = { TextButton({ asking = null }) { Text(stringResource(R.string.mine_offline_keep)) } },
        )
    }
}

/**
 * The countries whose map the engine draws from their tiles (ENGINE.md step 3, one pack per country): a card each with
 * what the map took of it as it was looked at, and its download whole.
 */
@Composable
private fun CountryCards(manager: AtlasRegionManager, details: List<RegionDetail>, nav: Navigator) {
    var countries by remember { mutableStateOf(emptyList<Pack>()) }
    LaunchedEffect(Unit) {
        countries = withContext(Dispatchers.IO) { runCatching { manager.engine.packs() }.getOrDefault(emptyList()) }.filter { manager.engine.tilesOf(it) != null }
    }
    for (p in countries) CountryCard(p, manager.engine.countryBytes(p), details, nav)
}

/**
 * One country's map as the phone has it (the user, 30 October: "Why not make the automatic map downloading part
 * visible?"): the areas the map took of it as they were looked at (from the country's tiles, and any built on the phone
 * from their data where the tiles did not give them) and what they take, what the map is getting now, why the tiles
 * did not answer where they did not, and the country whole for offline use: its download, how far it has got, or that
 * it is on the phone. The board's paper card (radius 22, 14 px in) as the storage's.
 */
@Composable
private fun CountryCard(pack: Pack, bytes: Long, details: List<RegionDetail>, nav: Navigator) {
    var kept by remember { mutableStateOf(Windows.Kept(0, 0, 0L)) }
    var status by remember { mutableStateOf<Windows.Status?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pack.id) {
        val box = doubleArrayOf(pack.bbox.south, pack.bbox.west, pack.bbox.north, pack.bbox.east)
        // what the map takes changes as it is used: read again every two seconds while the board is open
        while (true) {
            kept = withContext(Dispatchers.IO) { runCatching { Windows.kept(MapEngine.windowsDir(), box) }.getOrDefault(kept) }
            val w = MapEngine.windowsNow
            status = w?.status
            problem = w?.tilesProblem
            delay(COUNTRY_READ_MS)
        }
    }
    val name = pack.country?.let { java.util.Locale("", it).getDisplayCountry(java.util.Locale.getDefault()).takeIf { n -> n.isNotBlank() && n != it } } ?: pack.name
    val id = RegionEngine.idFor(pack.name)
    val region = details.firstOrNull { it.meta.packId == pack.id || it.id == id }
    val whole = region?.takeIf { it.hasTiles && it.complete }
    Column(
        Modifier.fillMaxWidth().bbox(0xFFF6F2EA, cr(22f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MSy("ui.map", 18f, INK)
            BText(name, bt(22f, 400, 26f, SERIF), INK, Modifier.weight(1f), lines = 1)
            Chip("ui.refresh", stringResource(R.string.mine_country_auto), 0xFFE7F4EC, 0xFF12663A)
        }
        BText(
            if (kept.places == 0) stringResource(R.string.mine_country_none)
            else pluralStringResource(R.plurals.mine_country_areas, kept.places, kept.places, RegionEngine.size(kept.bytes)),
            bt(13f, 400, 17f, tnum = true), BODY, lines = 2,
        )
        if (kept.built > 0) BText(stringResource(R.string.mine_country_built, kept.built), bt(12f, 400, 15.5f, tnum = true), BODY, lines = 2)
        val now = when (status) {
            is Windows.Status.Fetching -> if ((status as Windows.Status.Fetching).tiles) stringResource(R.string.mine_country_getting) else stringResource(R.string.mine_country_building)
            is Windows.Status.Building -> stringResource(R.string.mine_country_building)
            is Windows.Status.Waiting -> stringResource(R.string.mine_country_waiting)
            else -> null
        }
        if (now != null) BText(now, bt(12f, 600, 15.5f), 0xFF1F3FC7, lines = 1)
        problem?.let { BText(stringResource(R.string.mine_country_problem, it.take(140)), bt(12f, 600, 15.5f), 0xFF9B2A24, lines = 3) }
        Spacer(Modifier.height(2.dp))
        val p = region?.progress
        when {
            whole != null -> Chip("ui.check", stringResource(R.string.mine_country_kept), 0xFFE7F4EC, 0xFF12663A)
            p != null && !p.waiting -> Chip("ui.download", stringResource(R.string.mine_country_keeping, (p.fraction * 100).roundToInt()), 0xFFE4EAFF, 0xFF1F3FC7)
            else -> TextPill(
                stringResource(R.string.mine_country_keep, RegionEngine.size(bytes)), bt(13f, 700, 17f), INK, WHITE, h = 36f, padH = 14f,
                symbol = "ui.download", symbolSize = 16f, gap = 6f, onClick = { nav.go(Dest.RegionPicker(mode = "country", pack = pack.id)) },
            )
        }
    }
}

/** How often the country card reads again what the map took. */
private const val COUNTRY_READ_MS = 2_000L

/** The total, its age and the bar of what the phone keeps: map, routes, search, voices, and what is left of the storage. */
@Composable
private fun StorageCard(details: List<RegionDetail>, capacity: Long, age: String?) {
    val slices = OfflineModel.slices(details)
    val total = details.sumOf { it.bytes }
    val (number, unit) = OfflineModel.total(total)
    val shares = OfflineModel.shares(slices, capacity)
    val capText = if (capacity > 0) OfflineModel.total(capacity).let { "${it.first} ${it.second}" } else ""
    val description = stringResource(R.string.mine_offline_storage_cd, "$number $unit", capText)
    // the board's card: 14 px in, the total (24, 700 on 31) and its unit (14 on 18) on a baseline, the age at the right,
    // the bar 8 px under (12 px, radius 6, 2 px between), then the legend 8 px under it (an 8 px dot and a 15 px symbol)
    Column(
        Modifier.fillMaxWidth().bbox(0xFFF6F2EA, cr(22f)).padding(14.dp).semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BText(number, bt(24f, 700, 31f, tnum = true), INK, Modifier.alignByBaseline())
                BText(unit, bt(14f, 400, 18f, tnum = true), BODY, Modifier.alignByBaseline())
            }
            if (age != null) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                MSy("ui.refresh", 15f, BODY)
                BText(age, bt(13f, 400, 17f, tnum = true), BODY)
            }
        }
        Row(Modifier.fillMaxWidth().height(12.dp).bbox(r = cr(6f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((i, s) in slices.withIndex()) Box(Modifier.weight(shares[i]).fillMaxHeight().bbox(s.colour))
            val rest = (1f - shares.sum()).coerceAtLeast(0.02f)
            Box(Modifier.weight(rest).fillMaxHeight().bbox(0xFFDDD6C9))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (s in slices) {
                val name = layerName(s.key)
                Row(
                    Modifier.semantics { contentDescription = "$name ${RegionEngine.size(details.sumOf { it.sizes[s.layer] ?: 0L })}" },
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).bbox(s.colour, cr(4f)))
                    MSy(s.symbol, 15f, BODY)
                }
            }
        }
    }
}

@Composable
private fun layerName(key: String): String = stringResource(
    when (key) {
        "map" -> R.string.mine_offline_layer_map
        "routes" -> R.string.mine_offline_layer_routes
        "search" -> R.string.mine_offline_layer_search
        "terrain" -> R.string.mine_offline_layer_terrain
        else -> R.string.mine_offline_layer_voices
    },
)

/**
 * One region as the board's paper cards (radius 10, its white top edge and two shadows): the folded map 150 px wide on the
 * left, then 16 px in its name (serif 24 on 24), its size (13 on 17), what it holds (16 px symbols 7 apart) and its state.
 */
@Composable
private fun RegionCard(d: RegionDetail, manager: AtlasRegionManager, onDelete: () -> Unit) {
    var open by rememberSaveable(d.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .bbox(
                0xFFF4EDDD, cr(10f),
                listOf(CssShadow(0f, 1f, 0f, 0f, 0xFFFFFFFF, inset = true), CssShadow(0f, 8f, 18f, 0f, 0x2E3C2D14), CssShadow(0f, 1f, 3f, 0f, 0x333C2D14)),
            )
            .clickable { open = !open },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 128.dp)) {
            MiniMap(d, Modifier.width(150.dp).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .bbox(grad = listOf(cssLinear(90f, 0f to 0x0F000000, 0.06f to 0x00000000)))
                    .padding(start = 16.dp, top = 13.75.dp, end = 14.dp, bottom = 9.dp),
            ) {
                BText(d.name, bt(24f, 400, 24f, SERIF), INK, lines = 2)
                if (d.bytes > 0) BText(RegionEngine.size(d.bytes), bt(13f, 400, 17f, tnum = true), BODY, Modifier.padding(top = 8.25.dp))
                val icons = OfflineModel.icons(d)
                if (icons.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (s in icons) MSy(s, 16f, INK)
                }
                Spacer(Modifier.weight(1f, fill = true).heightIn(min = 8.dp))
                StateChip(d)
            }
        }
        AnimatedVisibility(open) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = d.progress != null
                if (running) ActionPill(stringResource(R.string.mine_offline_actions_cancel), "ui.close") { manager.cancel(d.id) }
                else {
                    if (!d.complete || d.error != null) ActionPill(stringResource(R.string.mine_offline_actions_retry), "ui.refresh") { manager.update(d.id) }
                    else if (d.update != null) ActionPill(stringResource(R.string.mine_offline_actions_update), "ui.refresh") { manager.update(d.id) }
                }
                ActionPill(stringResource(R.string.mine_offline_actions_delete), "ui.trash", danger = true, onClick = onDelete)
            }
        }
    }
}

/** A card's action: a white pill (34 high, 12 px at the sides) with its 15 px symbol and name (12, 700). */
@Composable
private fun ActionPill(text: String, symbol: String, danger: Boolean = false, onClick: () -> Unit) {
    TextPill(text, bt(12f, 700, 15.5f), WHITE, if (danger) 0xFFC8322A else INK, h = 34f, padH = 12f, symbol = symbol, symbolSize = 15f, gap = 6f, onClick = onClick)
}

/** The state at the foot of a card: progress with its percentage, waiting, a failure, an update, or the green check. */
@Composable
private fun StateChip(d: RegionDetail) {
    val p = d.progress
    when {
        p != null && p.waiting -> Chip("ui.clock", stringResource(R.string.mine_offline_waiting_wifi), 0xFFEFE8FF, 0xFF4B34B8)
        // the board's progress: an 8 px track (radius 4) and the percentage 6 px under it (12 on 15.5)
        p != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(8.dp).bbox(0xFFE3DCCD, cr(4f))) {
                Box(Modifier.fillMaxWidth(p.fraction.coerceIn(0f, 1f)).height(8.dp).bbox(0xFF2E5BFF))
            }
            BText("${(p.fraction * 100).roundToInt()}%", bt(12f, 400, 15.5f, tnum = true), BODY)
        }
        d.error != null -> Chip("ui.warning", stringResource(R.string.mine_offline_stopped), 0xFFFBE7E5, 0xFF9B2A24)
        !d.complete -> Chip("ui.clock", stringResource(R.string.mine_offline_stopped), 0xFFFFF1D6, 0xFF8A5A00)
        d.update != null -> Chip("ui.refresh", d.update.bytes?.let { stringResource(R.string.mine_offline_update_chip, RegionEngine.size(it)) } ?: stringResource(R.string.mine_offline_actions_update), 0xFFE4EAFF, 0xFF1F3FC7)
        else -> Box(Modifier.size(28.dp, 24.dp).bbox(0xFFE7F4EC, cr(12f)).semantics { contentDescription = "" }, contentAlignment = Alignment.Center) {
            MSy("ui.check", 12f, 0xFF12663A)
        }
    }
}

/** The board's chip: 24 high, radius 12, 8 px in, a 12 px symbol and 4 px on its words (12, 600 on 15.5). */
@Composable
private fun Chip(symbol: String, text: String, bg: Long, fg: Long) {
    TextPill(text, bt(12f, 600, 15.5f, tnum = true), bg, fg, h = 24f, padH = 8f, symbol = symbol, symbolSize = 12f, gap = 4f)
}

/**
 * A paper map of the region, 150 dp wide, folded down the middle as on the board: streets as white lines, the places the
 * phone knows as small dots at their real positions, and the blue of the phone's own position when it is inside.
 */
@Composable
private fun MiniMap(d: RegionDetail, modifier: Modifier) {
    val services = LocalServices.current
    var dots by remember(d.id, d.meta.updatedAt) { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    LaunchedEffect(d.id, d.meta.updatedAt) {
        dots = withContext(Dispatchers.IO) { runCatching { OfflineModel.dots(services.placeStores, d.meta.bbox) }.getOrDefault(emptyList()) }
    }
    val here by services.location.last.collectAsStateWithLifecycle()
    val paper = Color(0xFFEFE6D0)
    val line = Color(0xFFFFFFFF)
    Canvas(modifier.semantics { contentDescription = "" }) {
        val w = size.width
        val h = size.height
        drawRect(paper)
        // A graticule of the box, as a paper map's folds and streets: three each way, each a little crooked.
        for (i in 1..3) {
            val x = w * i / 4f
            drawLine(line, Offset(x - 6f * (i % 2), 0f), Offset(x + 8f * (i % 2 - 1), h), strokeWidth = 3f)
            val y = h * i / 4f
            drawLine(line, Offset(0f, y + 5f * (i % 2)), Offset(w, y - 6f * (1 - i % 2)), strokeWidth = 3f)
        }
        for ((x, y) in dots) drawCircle(Color(0xFFB8905C).copy(alpha = 0.75f), radius = 2.2f * density, center = Offset(x * w, y * h))
        val fix = here
        if (fix != null && fix.location in d.meta.bbox) {
            val b = d.meta.bbox
            val x = ((fix.location.lon - b.west) / (b.east - b.west)).toFloat() * w
            val y = ((b.north - fix.location.lat) / (b.north - b.south)).toFloat() * h
            drawCircle(Color.White, 6.5f * density, Offset(x, y))
            drawCircle(Color(0xFF2E5BFF), 4.5f * density, Offset(x, y))
        }
        // The fold: the board's gradient over the middle of the map.
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent, 0.48f to Color.Transparent, 0.50f to Color(0x12000000), 0.52f to Color(0x40FFFFFF), 0.56f to Color.Transparent, 1f to Color.Transparent,
            ),
        )
    }
}

/** The board's add buttons (64 high, radius 18, 8 px apart): three dashed (1.5 px of B5AB98) and the dark sparkle for automatic. */
@Composable
private fun AddRow(auto: Boolean, onArea: () -> Unit, onRoute: () -> Unit, onCountry: () -> Unit, onAuto: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((symbol, label, click) in listOf(
            Triple("ui.draw-area", R.string.mine_offline_add_area, onArea),
            Triple("ui.along-route", R.string.mine_offline_add_route, onRoute),
            Triple("ui.web", R.string.mine_offline_add_country, onCountry),
        )) {
            val cd = stringResource(label)
            Box(
                Modifier.weight(1f).height(64.dp).bbox(r = cr(18f)).clickable(role = Role.Button, onClick = click).semantics { contentDescription = cd }.dashedBorder(Color(0xFFB5AB98), 18.dp),
                contentAlignment = Alignment.Center,
            ) { MSy(symbol, 24f, INK) }
        }
        val cd = stringResource(if (auto) R.string.mine_offline_auto_on else R.string.mine_offline_add_auto)
        Box(
            Modifier.weight(1f).height(64.dp).bbox(if (auto) 0xFF178A4E else INK, cr(18f)).clickable(role = Role.Button, onClick = onAuto).semantics { contentDescription = cd },
            contentAlignment = Alignment.Center,
        ) { MSy("ui.sparkles", 24f, 0xFFFFE7A3) }
    }
}

/** A dashed rounded outline of [colour], 1.5 px wide, inside the box as CSS draws a border. */
private fun Modifier.dashedBorder(colour: Color, radius: androidx.compose.ui.unit.Dp): Modifier = this.drawWithContent {
    drawContent()
    val w = 1.5.dp.toPx()
    drawRoundRect(
        colour, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(radius.toPx() - w / 2),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.5f * density, 4.5f * density))),
    )
}

/** The voices and Wi-Fi row: a speaker, the language chips (dark when kept) and the Wi-Fi-only switch. */
@Composable
private fun VoicesRow(wifi: Boolean, onWifi: (Boolean) -> Unit) {
    val services = LocalServices.current
    val saved by services.settings.flow(MineKeys.VOICE_LANGS, emptySet()).collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val device = remember { java.util.Locale.getDefault().language }
    val kept = VoiceLanguages.kept(saved, device)
    val label = stringResource(R.string.mine_offline_voices)
    val context = LocalContext.current
    // the board's row: 12 px above and below, 14 at the sides, 10 px apart; the 22 px speaker, the language chips (32 high,
    // 10 px in, 13, 600), and at the right the Wi-Fi symbol and its check box
    Row(
        Modifier.fillMaxWidth().bbox(0xFFF6F2EA, cr(20f)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        MSy("ui.volume.3", 22f, BODY, description = label)
        for (lang in VoiceLanguages.shown(kept).take(3)) {
            val on = lang in kept
            TextPill(lang.uppercase(), bt(13f, 600, 17f), if (on) INK else WHITE, if (on) WHITE else INK, h = 32f, padH = 10f) {
                scope.launch {
                    val next = if (on) kept - lang else kept + lang
                    services.settings.set(MineKeys.VOICE_LANGS, next.takeIf { it.isNotEmpty() })
                    if (!on && VoiceLanguages.installed(context, lang) == false) VoiceLanguages.install(context)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val wifiLabel = stringResource(R.string.mine_offline_wifi_only)
        Row(
            Modifier.toggleable(wifi, role = Role.Checkbox, onValueChange = onWifi).semantics { contentDescription = wifiLabel },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("fact.wifi", 18f, BODY)
            // the board's check box (20 px) as its picture draws it: the check alone when on, an empty square when off
            Box(
                Modifier.size(20.dp).then(if (wifi) Modifier else Modifier.padding(2.dp).bbox(WHITE, cr(4f), border = ring(1.5f, FAINT))),
                contentAlignment = Alignment.Center,
            ) { if (wifi) MSy("ui.check", 18f, INK) }
        }
    }
}

/** The voice languages Atlas offers to keep with a region, and the check that the phone's speech engine has their data. */
object VoiceLanguages {
    val ALL = listOf("en", "nl", "de", "fr", "es", "it", "pt", "sv", "da", "nb", "pl")

    /** What is kept: the languages chosen, or the phone's own when none was. */
    fun kept(saved: Set<String>, device: String): Set<String> = saved.ifEmpty { setOf(if (device in ALL) device else "en") }

    /** The chips to show: the kept languages, then the others of [ALL] up to a row of five. */
    fun shown(kept: Set<String>): List<String> = (kept.toList().sorted() + ALL.filter { it !in kept }).take(5)

    /**
     * Whether the phone's speech engine has voice data for [lang]: true, false, or null when there is no engine (or none
     * answered in a few seconds).
     */
    suspend fun installed(context: android.content.Context, lang: String): Boolean? = kotlinx.coroutines.withTimeoutOrNull(4_000) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var engine: android.speech.tts.TextToSpeech? = null
            engine = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                val e = engine
                val answer = if (status != android.speech.tts.TextToSpeech.SUCCESS || e == null) null
                else e.isLanguageAvailable(java.util.Locale.forLanguageTag(lang)) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE
                runCatching { e?.shutdown() }
                if (cont.isActive) cont.resumeWith(Result.success(answer))
            }
            cont.invokeOnCancellation { runCatching { engine?.shutdown() } }
        }
    }

    /** Opens the speech engine's own screen to install voice data. */
    fun install(context: android.content.Context) {
        runCatching {
            context.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
