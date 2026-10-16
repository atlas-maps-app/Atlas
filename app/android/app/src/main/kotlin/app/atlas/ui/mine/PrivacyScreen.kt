package app.atlas.ui.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.mine.art.MineArtImage
import app.atlas.ui.mine.offline.RegionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On this phone, the privacy board: the promise, what is kept here with a switch or a button to forget each thing (where
 * you have been is off until you switch it on), what goes online and to which host, and Export all / Delete everything.
 */
@Composable
fun PrivacyScreen(args: Dest.Privacy, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = services.settings
    val trailOn by settings.flow(MineKeys.TRAIL, false).collectAsStateWithLifecycle(false)
    val pressureOn by settings.flow(MineKeys.PRESSURE, false).collectAsStateWithLifecycle(false)
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val recents by services.saved.recents.collectAsStateWithLifecycle()
    val off by settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).collectAsStateWithLifecycle(emptySet())
    val keys by services.keys.keys.collectAsStateWithLifecycle()
    val home by services.go.store.home.collectAsStateWithLifecycle(null)
    val work by services.go.store.work.collectAsStateWithLifecycle(null)
    var tick by remember { mutableStateOf(0) }
    val trail = remember(trailOn, tick) { services.mine.trail.let { it.count() to it.bytes() } }
    val pressure = remember(pressureOn, tick) { services.mine.pressure.readings().size }
    val barometer = remember { Barometers.available(context) }
    var confirm by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val saved = runCatching {
                val text = withContext(Dispatchers.IO) { Backup.export(services, trail = trailOn) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no file") }
            }
            exported = if (saved.isSuccess) uri.lastPathSegment else null
            exportFailed = saved.isFailure
        }
    }
    val rows = SourcesModel.rows(off, keys.orEmpty())
    val placesSaved = lists.sumOf { it.places.size }
    BoardPage(stringResource(R.string.mine_privacy), onBack = { nav.back() }) {
        // The promise.
        Row(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0F3D2A)).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MineArtImage("privacy.shield", 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.mine_privacy_head), style = mt(17f, 800, line = 20.4f), color = Color(0xFFEAF6EE))
                Text(stringResource(R.string.mine_privacy_text), style = mt(12f, 400, line = 15f), color = Color(0xFFA8D8B8))
            }
        }
        // What is kept.
        BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)) {
            SectionLabel(stringResource(R.string.mine_privacy_kept), Modifier.padding(top = 4.dp, bottom = 6.dp), MineTone.steel)
            KeptRow(
                "ui.pin", stringResource(R.string.mine_kept_trail),
                if (!trailOn && trail.first == 0) stringResource(R.string.mine_kept_trail_off) else stringResource(R.string.mine_kept_trail_on, pluralStringResource(R.plurals.mine_n_points, trail.first, trail.first), RegionEngine.size(trail.second)),
                first = true,
                toggle = { BoardSwitch(trailOn, { on -> scope.launch { settings.set(MineKeys.TRAIL, on) } }, stringResource(R.string.mine_kept_trail)) },
                clear = if (trail.first > 0) ({ services.mine.trail.clear(); tick++ }) else null,
            )
            KeptRow(
                "ui.gauge", stringResource(R.string.mine_kept_pressure),
                when {
                    !barometer -> stringResource(R.string.mine_kept_pressure_none)
                    pressureOn -> stringResource(R.string.mine_kept_pressure_on, pluralStringResource(R.plurals.mine_n_readings, pressure, pressure))
                    else -> stringResource(R.string.mine_kept_pressure_off)
                },
                toggle = { BoardSwitch(pressureOn, { on -> scope.launch { settings.set(MineKeys.PRESSURE, on) } }, stringResource(R.string.mine_kept_pressure), enabled = barometer) },
                clear = if (pressure > 0) ({ services.mine.pressure.clear(); tick++ }) else null,
            )
            KeptRow(
                "ui.bookmark", stringResource(R.string.mine_kept_lists), stringResource(R.string.mine_kept_lists_sub, pluralStringResource(R.plurals.mine_n_lists, lists.size, lists.size), pluralStringResource(R.plurals.mine_n_places, placesSaved, placesSaved)),
                onOpen = { nav.go(Dest.Lists()) },
            )
            KeptRow(
                "ui.search", stringResource(R.string.mine_kept_searches), stringResource(R.string.mine_kept_searches_sub, pluralStringResource(R.plurals.mine_n_kept, recents.size, recents.size)),
                clear = if (recents.isNotEmpty()) ({ scope.launch(Dispatchers.IO) { services.saved.clearRecents() } }) else null,
            )
            KeptRow(
                "place.home", stringResource(R.string.mine_kept_homework),
                listOfNotNull(home?.let { stringResource(R.string.mine_settings_home) }, work?.let { stringResource(R.string.mine_settings_work) }).joinToString(" · ").ifEmpty { stringResource(R.string.mine_settings_not_set) },
                clear = if (home != null || work != null) ({ scope.launch { services.go.store.setHome(null); services.go.store.setWork(null) } }) else null,
            )
        }
        // What goes online.
        BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
            SectionLabel(stringResource(R.string.mine_privacy_online), Modifier.padding(bottom = 4.dp), MineTone.steel)
            val live = rows.filter { it.on && (it.state == app.atlas.providers.ProviderState.READY) }
            for (r in live) OnlineRow(r.info.name, r.host, sentLine(r.info.id))
            if (live.isEmpty()) Text(stringResource(R.string.mine_privacy_online_none), style = mt(12f, 400), color = Atlas.colors.inkSoft, modifier = Modifier.padding(vertical = 6.dp))
            // What a download you start asks of its server (these hosts see your address and what you asked for).
            SectionLabel(stringResource(R.string.mine_privacy_downloads), Modifier.padding(top = 10.dp, bottom = 4.dp), MineTone.steel)
            OnlineRow(stringResource(R.string.mine_offline_title), "github.com", stringResource(R.string.mine_sent_pack))
            OnlineRow(stringResource(R.string.mine_offline_layer_routes), "brouter.de", stringResource(R.string.mine_sent_routing))
            OnlineRow(stringResource(R.string.mine_offline_layer_map), "tiles.openfreemap.org", stringResource(R.string.mine_sent_tiles))
        }
        // Export and forget.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val c = Atlas.colors
            Box(
                Modifier.weight(1f).height(54.dp).clip(CircleShape).border(1.5.dp, MineTone.of(0xFFD5D8DE, 0xFF3A3E46), CircleShape).background(c.card)
                    .androidClick { export.launch("atlas-backup.json") },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.mine_privacy_export), style = mt(14f, 800), color = c.ink) }
            Box(Modifier.weight(1f).height(54.dp).clip(CircleShape).background(MineTone.red).androidClick { confirm = true }, contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.mine_privacy_delete), style = mt(14f, 800), color = Color.White)
            }
        }
        exported?.let { Text(stringResource(R.string.mine_privacy_exported, it), style = mt(12f, 500), color = MineTone.greenDeep, modifier = Modifier.padding(horizontal = 8.dp)) }
        if (exportFailed) Text(stringResource(R.string.mine_phone_export_failed), style = mt(12f, 500), color = MineTone.red, modifier = Modifier.padding(horizontal = 8.dp))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.mine_privacy_delete_title)) },
        text = { Text(stringResource(R.string.mine_privacy_delete_text)) },
        confirmButton = { TextButton({ confirm = false; scope.launch(Dispatchers.IO) { Backup.wipe(services); tick++ } }) { Text(stringResource(R.string.mine_privacy_delete), color = MineTone.red) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.mine_offline_keep)) } },
    )
}

/** One host something is sent to: its name, the address, and what it is told. */
@Composable
private fun OnlineRow(name: String, host: String?, line: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(MineTone.blue))
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text(name, style = mt(13f, 800), color = Atlas.colors.ink)
                host?.let { Text(it, style = mt(11f, 500, tabular = true), color = Atlas.colors.inkFaint) }
            }
            Text(line, style = mt(12f, 400, line = 16f), color = Atlas.colors.inkSoft)
        }
    }
}

/** What each source is sent, in a few plain words. */
@Composable
internal fun sentLine(id: String): String = stringResource(
    when (id) {
        "overpass", "osm-chargers", "ocm", "ndw", "bnetza", "afdc" -> R.string.mine_sent_area
        "nominatim", "photon" -> R.string.mine_sent_words
        "mangrove" -> R.string.mine_sent_mangrove
        "foursquare", "yelp", "tripadvisor" -> R.string.mine_sent_ratings
        "open-meteo" -> R.string.mine_sent_weather
        "wikidata" -> R.string.mine_sent_wikidata
        "osm-notes" -> R.string.mine_sent_notes
        "transitous" -> R.string.mine_sent_transit
        "tankerkoenig" -> R.string.mine_sent_fuel
        else -> R.string.mine_sent_area
    },
)

/** A kept thing: a tile, its name and what is kept, and a switch, a "Clear" button or a way in. */
@Composable
private fun KeptRow(symbol: String, title: String, sub: String, first: Boolean = false, toggle: (@Composable () -> Unit)? = null, clear: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    val c = Atlas.colors
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().height(52.dp).then(if (onOpen != null) Modifier.androidClick(onOpen) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MineTone.of(0xFFF1F3F6, 0xFF2A2D34)), contentAlignment = Alignment.Center) { Symbol(symbol, tint = c.ink, size = 18.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = mt(14f, 800), color = c.ink)
                Text(sub, style = mt(11f, 500, line = 14f), color = c.inkSoft, maxLines = 2)
            }
            if (clear != null) MiniButton(stringResource(R.string.mine_privacy_clear), clear, primary = false)
            toggle?.invoke()
            if (onOpen != null) Symbol("ui.chevron-right", tint = c.inkFaint, size = 16.dp)
        }
    }
}

internal fun Modifier.androidClick(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)
