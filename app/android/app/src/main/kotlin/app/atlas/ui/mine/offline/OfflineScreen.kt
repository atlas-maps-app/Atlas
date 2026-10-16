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
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.mine.BoardPage
import app.atlas.ui.mine.BoardSwitch
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.mt
import app.atlas.ui.mine.serifTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Cream = Color(0xFFF4EDDD)
private val Panel = Color(0xFFF6F2EA)
private val Dashed = Color(0xFFB5AB98)

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
    BoardPage(
        title = stringResource(R.string.mine_offline_title),
        onBack = { nav.back() },
        background = Atlas.colors.paperDeep,
        backFill = MineTone.of(0xFFF6F2EA, 0xFF1B1D22),
        italic = false,
        titleSize = 36f,
        gap = 16.dp,
        trailing = {
            val works = stringResource(R.string.mine_offline_works)
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MineTone.greenDeep).semantics { contentDescription = works },
                contentAlignment = Alignment.Center,
            ) { Symbol("ui.offline", tint = Color.White, size = 22.dp) }
        },
    ) {
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
        if (details.isEmpty()) {
            StateMessage("ui.offline", stringResource(R.string.mine_offline_none_title), text = stringResource(R.string.mine_offline_none_text), tint = MineTone.greenDeep)
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
        Text(stringResource(R.string.mine_offline_footer), style = mt(10f, 500, line = 15f), color = Atlas.colors.inkFaint, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(LocalBottomBarInset.current))
    }
    asking?.let { d ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.mine_offline_delete_title, d.name)) },
            text = { Text(stringResource(R.string.mine_offline_delete_text, RegionEngine.size(d.bytes))) },
            confirmButton = { TextButton({ asking = null; scope.launch { manager.delete(d.id) } }) { Text(stringResource(R.string.mine_offline_delete_confirm), color = MineTone.red) } },
            dismissButton = { TextButton({ asking = null }) { Text(stringResource(R.string.mine_offline_keep)) } },
        )
    }
}

/** The total, its age and the bar of what the phone keeps: map, routes, search, voices, and what is left of the storage. */
@Composable
private fun StorageCard(details: List<RegionDetail>, capacity: Long, age: String?) {
    val slices = OfflineModel.slices(details)
    val total = details.sumOf { it.bytes }
    val (number, unit) = OfflineModel.total(total)
    val shares = OfflineModel.shares(slices, capacity)
    val capText = if (capacity > 0) OfflineModel.total(capacity).let { "${it.first} ${it.second}" } else ""
    val description = stringResource(R.string.mine_offline_storage_cd, "$number $unit", capText)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MineTone.of(0xFFF6F2EA, 0xFF1B1D22)).padding(14.dp).semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(number, style = mt(24f, 700, tabular = true), color = Atlas.colors.ink)
            Text(unit, style = mt(14f, 500), color = Atlas.colors.inkSoft, modifier = Modifier.padding(bottom = 2.dp))
            Spacer(Modifier.weight(1f))
            if (age != null) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui.refresh", tint = Atlas.colors.inkSoft, size = 15.dp)
                Text(age, style = mt(13f, 500, tabular = true), color = Atlas.colors.inkSoft)
            }
        }
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((i, s) in slices.withIndex()) Box(Modifier.weight(shares[i]).fillMaxHeight().background(Color(s.colour)))
            val rest = (1f - shares.sum()).coerceAtLeast(0.02f)
            Box(Modifier.weight(rest).fillMaxHeight().background(MineTone.of(0xFFDDD6C9, 0xFF2C2F36)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (s in slices) {
                val name = layerName(s.key)
                Row(
                    Modifier.semantics { contentDescription = "$name ${RegionEngine.size(details.sumOf { it.sizes[s.layer] ?: 0L })}" },
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(s.colour)))
                    Symbol(s.symbol, tint = Atlas.colors.inkSoft, size = 15.dp)
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

/** One region: a paper map on the left, folded, and on the right its name in serif, its size, what it holds and its state. */
@Composable
private fun RegionCard(d: RegionDetail, manager: AtlasRegionManager, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    var open by rememberSaveable(d.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .cssShadow(shape, 8.dp, 18.dp, Color(0x2E3C2D14))
            .cssShadow(shape, 1.dp, 3.dp, Color(0x333C2D14))
            .clip(shape)
            .background(MineTone.of(0xFFF4EDDD, 0xFF1B1D22))
            .clickable { open = !open },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 128.dp)) {
            MiniMap(d, Modifier.width(150.dp).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, top = 12.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(d.name, style = serifTitle(24f, italic = false).copy(lineHeight = 24.sp), color = Atlas.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d.bytes > 0) Text(RegionEngine.size(d.bytes), style = mt(13f, 500, tabular = true, line = 16f), color = Atlas.colors.inkSoft)
                val icons = OfflineModel.icons(d)
                if (icons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (s in icons) Symbol(s, tint = Atlas.colors.ink, size = 16.dp)
                }
                Spacer(Modifier.weight(1f, fill = true))
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

@Composable
private fun ActionPill(text: String, symbol: String, danger: Boolean = false, onClick: () -> Unit) {
    val fg = if (danger) MineTone.red else Atlas.colors.ink
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(MineTone.of(0xFFFFFFFF, 0xFF24272D)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = fg, size = 15.dp)
        Text(text, style = mt(12f, 700), color = fg)
    }
}

/** The state at the foot of a card: progress with its percentage, waiting, a failure, an update, or the green check. */
@Composable
private fun StateChip(d: RegionDetail) {
    val p = d.progress
    when {
        p != null && p.waiting -> Chip("ui.clock", stringResource(R.string.mine_offline_waiting_wifi), Color(0xFFEFE8FF), Color(0xFF4B34B8))
        p != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MineTone.of(0xFFE3DCCD, 0xFF2C2F36))) {
                Box(Modifier.fillMaxWidth(p.fraction.coerceIn(0f, 1f)).height(8.dp).background(MineTone.blue))
            }
            Text("${(p.fraction * 100).roundToInt()}%", style = mt(12f, 500, tabular = true), color = Atlas.colors.inkSoft)
        }
        d.error != null -> Chip("ui.warning", stringResource(R.string.mine_offline_stopped), Color(0xFFFBE7E5), Color(0xFF9B2A24))
        !d.complete -> Chip("ui.clock", stringResource(R.string.mine_offline_stopped), Color(0xFFFFF1D6), Color(0xFF8A5A00))
        d.update != null -> Chip("ui.refresh", d.update.bytes?.let { stringResource(R.string.mine_offline_update_chip, RegionEngine.size(it)) } ?: stringResource(R.string.mine_offline_actions_update), Color(0xFFE4EAFF), Color(0xFF1F3FC7))
        else -> Row(
            Modifier.height(24.dp).clip(CircleShape).background(Color(0xFFE7F4EC)).padding(horizontal = 8.dp).semantics { contentDescription = "" },
            verticalAlignment = Alignment.CenterVertically,
        ) { Symbol("ui.check", tint = Color(0xFF12663A), size = 12.dp, weight = 1.4f) }
    }
}

@Composable
private fun Chip(symbol: String, text: String, bg: Color, fg: Color) {
    Row(
        Modifier.height(24.dp).clip(CircleShape).background(bg).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = fg, size = 12.dp, weight = 1.2f)
        Text(text, style = mt(12f, 600, tabular = true), color = fg, maxLines = 1)
    }
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
    val paper = MineTone.of(0xFFEFE6D0, 0xFF2A2822)
    val water = MineTone.of(0xFFA9CFE6, 0xFF2D4656)
    val line = MineTone.of(0xFFFFFFFF, 0xFF4A4636)
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
            drawCircle(MineTone.blue, 4.5f * density, Offset(x, y))
        }
        // The fold: the board's gradient over the middle of the map.
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent, 0.48f to Color.Transparent, 0.50f to Color(0x12000000), 0.52f to Color(0x40FFFFFF), 0.56f to Color.Transparent, 1f to Color.Transparent,
            ),
        )
        // The page's own shadow on the card's side.
        drawRect(Brush.horizontalGradient(listOf(Color(0x0F000000), Color.Transparent), startX = w - 6f * density, endX = w), topLeft = Offset(w - 6f * density, 0f), size = Size(6f * density, h))
    }
}

/** The dashed add buttons: draw an area, along a route, a whole country, and the dark sparkle for automatic downloads. */
@Composable
private fun AddRow(auto: Boolean, onArea: () -> Unit, onRoute: () -> Unit, onCountry: () -> Unit, onAuto: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(18.dp)
        for ((symbol, label, click) in listOf(
            Triple("ui.draw-area", R.string.mine_offline_add_area, onArea),
            Triple("ui.along-route", R.string.mine_offline_add_route, onRoute),
            Triple("ui.web", R.string.mine_offline_add_country, onCountry),
        )) {
            val cd = stringResource(label)
            Box(
                Modifier.weight(1f).height(64.dp).clip(shape).clickable(onClick = click).semantics { contentDescription = cd }.dashedBorder(Dashed, 18.dp),
                contentAlignment = Alignment.Center,
            ) { Symbol(symbol, tint = Atlas.colors.ink, size = 24.dp) }
        }
        val cd = stringResource(if (auto) R.string.mine_offline_auto_on else R.string.mine_offline_add_auto)
        Box(
            Modifier.weight(1f).height(64.dp).clip(shape).background(if (auto) MineTone.greenDeep else Color(0xFF14161B)).clickable(onClick = onAuto).semantics { contentDescription = cd },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.sparkles", tint = Color(0xFFFFE7A3), size = 24.dp) }
    }
}

/** A dashed rounded outline of [colour], 1.5 dp wide, as the board's add buttons have. */
private fun Modifier.dashedBorder(colour: Color, radius: androidx.compose.ui.unit.Dp): Modifier = this.drawWithContent {
    drawContent()
    val w = 1.5.dp.toPx()
    drawRoundRect(
        colour, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MineTone.of(0xFFF6F2EA, 0xFF1B1D22)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui.volume", tint = Atlas.colors.inkSoft, size = 22.dp, contentDescription = label)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (lang in VoiceLanguages.shown(kept).take(3)) {
                val on = lang in kept
                Box(
                    Modifier.height(32.dp).clip(CircleShape).background(if (on) Atlas.colors.pill else MineTone.of(0xFFFFFFFF, 0xFF24272D))
                        .clickable {
                            scope.launch {
                                val next = if (on) kept - lang else kept + lang
                                services.settings.set(MineKeys.VOICE_LANGS, next.takeIf { it.isNotEmpty() })
                                if (!on && VoiceLanguages.installed(context, lang) == false) VoiceLanguages.install(context)
                            }
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(lang.uppercase(), style = mt(13f, 600), color = if (on) Atlas.colors.onPill else Atlas.colors.ink, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol("fact.wifi", tint = Atlas.colors.inkSoft, size = 18.dp)
            BoardSwitch(wifi, onWifi, stringResource(R.string.mine_offline_wifi_only), onColour = MineTone.blue)
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
