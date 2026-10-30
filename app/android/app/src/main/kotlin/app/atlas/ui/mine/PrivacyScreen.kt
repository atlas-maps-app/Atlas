package app.atlas.ui.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
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
    val osmUser by services.osmAccount.user.collectAsStateWithLifecycle()
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
    BoardPage(
        stringResource(R.string.mine_privacy), onBack = { nav.back() }, gap = 6.dp,
        bottomBar = {
            // Export and forget: the board's two 54 px buttons, 8 px apart.
            Box(
                Modifier.weight(187.5f).height(54.dp).bbox(WHITE, cr(27f), border = ring(1.5f, 0xFFD5D8DE)).androidClick { export.launch("atlas-backup.json") },
                contentAlignment = Alignment.Center,
            ) { BText(stringResource(R.string.mine_privacy_export), bt(14f, 800, 18f), INK) }
            Box(Modifier.weight(184.5f).height(54.dp).bbox(0xFFC8322A, cr(27f)).androidClick { confirm = true }, contentAlignment = Alignment.Center) {
                BText(stringResource(R.string.mine_privacy_delete), bt(14f, 800, 18f), WHITE)
            }
        },
    ) {
        // The promise: the shield and two lines on the deep green (96 px, 16 px in, 14 px between).
        Row(
            Modifier.fillMaxWidth().height(96.dp).bbox(0xFF0F3D2A, cr(22f)).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("ui.privacy", 45.38f, 0xFFEAF6EE, h = 56f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BText(stringResource(R.string.mine_privacy_head), bt(17f, 800, 20.4f), 0xFFEAF6EE)
                BText(stringResource(R.string.mine_privacy_text), bt(12f, 400, 15.5f), 0xFFA8D8B8, lines = 2)
            }
        }
        // What is kept: the label 12 px in, then the rows (53 px: a hairline, 9 px, the 34 px tile, 9 px).
        BoardCard(Modifier.padding(top = 6.dp), padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
            CardLabel(stringResource(R.string.mine_privacy_kept), Modifier.padding(bottom = 6.dp))
            KeptRow(
                "ui.pin.2", stringResource(R.string.mine_kept_trail),
                if (!trailOn && trail.first == 0) stringResource(R.string.mine_kept_trail_off) else stringResource(R.string.mine_kept_trail_on, pluralStringResource(R.plurals.mine_n_points, trail.first, trail.first), RegionEngine.size(trail.second)),
                toggle = { MineSwitch(trailOn, { on -> scope.launch { settings.set(MineKeys.TRAIL, on) } }, stringResource(R.string.mine_kept_trail)) },
                clear = if (trail.first > 0) ({ services.mine.trail.clear(); tick++ }) else null,
            )
            KeptRow(
                "ui.gauge", stringResource(R.string.mine_kept_pressure),
                when {
                    !barometer -> stringResource(R.string.mine_kept_pressure_none)
                    pressureOn -> stringResource(R.string.mine_kept_pressure_on, pluralStringResource(R.plurals.mine_n_readings, pressure, pressure))
                    else -> stringResource(R.string.mine_kept_pressure_off)
                },
                toggle = { MineSwitch(pressureOn, { on -> scope.launch { settings.set(MineKeys.PRESSURE, on) } }, stringResource(R.string.mine_kept_pressure), enabled = barometer) },
                clear = if (pressure > 0) ({ services.mine.pressure.clear(); tick++ }) else null,
            )
            KeptRow(
                "ui.bookmark", stringResource(R.string.mine_kept_lists), stringResource(R.string.mine_kept_lists_sub, pluralStringResource(R.plurals.mine_n_lists, lists.size, lists.size), pluralStringResource(R.plurals.mine_n_places, placesSaved, placesSaved)),
                onOpen = { nav.go(Dest.Lists()) },
            )
            KeptRow(
                "ui.search.2", stringResource(R.string.mine_kept_searches), stringResource(R.string.mine_kept_searches_sub, pluralStringResource(R.plurals.mine_n_kept, recents.size, recents.size)),
                clear = if (recents.isNotEmpty()) ({ scope.launch(Dispatchers.IO) { services.saved.clearRecents() } }) else null,
            )
            KeptRow(
                "place.home", stringResource(R.string.mine_kept_homework),
                listOfNotNull(home?.let { stringResource(R.string.mine_settings_home) }, work?.let { stringResource(R.string.mine_settings_work) }).joinToString(" · ").ifEmpty { stringResource(R.string.mine_settings_not_set) },
                clear = if (home != null || work != null) ({ scope.launch { services.go.store.setHome(null); services.go.store.setWork(null) } }) else null,
            )
            KeptRow(
                "ui.account", stringResource(R.string.osm_account_title),
                osmUser?.let { stringResource(R.string.osm_privacy_signed_in, it) } ?: stringResource(R.string.osm_privacy_signed_out),
                onOpen = { nav.go(Dest.OsmAccount) },
            )
        }
        // What goes online: the label, then one line a source the app asks now (a blue dot, the name, what it is sent and
        // its hosts), 10 px apart: a source switched off is asked no more, as the app's HTTP client holds its hosts back.
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
            CardLabel(stringResource(R.string.mine_privacy_online), Modifier.padding(bottom = 9.dp))
            val live = SourcesModel.asked(rows)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in live) OnlineRow(r.info.name, r.host, sentLine(r.info.id))
                if (live.isEmpty()) BText(stringResource(R.string.mine_privacy_online_none), bt(12f, 400, 15.5f), SOFT, lines = 3)
            }
            // What a download you start asks of its server (these hosts see your address and what you asked for), where its
            // source is not switched off.
            val packs = live.any { it.info.id == SourcesModel.ATLAS_PACKS.id }
            val routing = live.any { it.info.id == SourcesModel.BROUTER.id }
            if (packs || routing) {
                CardLabel(stringResource(R.string.mine_privacy_downloads), Modifier.padding(top = 14.dp, bottom = 9.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (packs) OnlineRow(stringResource(R.string.mine_offline_title), "github.com", stringResource(R.string.mine_sent_pack))
                    if (routing) OnlineRow(stringResource(R.string.mine_offline_layer_routes), "brouter.de", stringResource(R.string.mine_sent_routing))
                }
            }
        }
        exported?.let { BText(stringResource(R.string.mine_privacy_exported, it), bt(12f, 500, 15.5f), 0xFF178A4E, Modifier.padding(horizontal = 8.dp), lines = 2) }
        if (exportFailed) BText(stringResource(R.string.mine_phone_export_failed), bt(12f, 500, 15.5f), 0xFFC8322A, Modifier.padding(horizontal = 8.dp), lines = 2)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.mine_privacy_delete_title)) },
        text = { Text(stringResource(R.string.mine_privacy_delete_text)) },
        confirmButton = { TextButton({ confirm = false; scope.launch(Dispatchers.IO) { Backup.wipe(services); tick++ } }) { Text(stringResource(R.string.mine_privacy_delete), color = Color(0xFFC8322A)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.mine_offline_keep)) } },
    )
}

/**
 * One host something is sent to, as the board's lines: a 6 px blue dot, the name (13, 800 on 17) and 8 px on what it is
 * told and the host (12 on 15.5, grey, wrapping beside the name).
 */
@Composable
private fun OnlineRow(name: String, host: String?, line: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).bbox(0xFF2E7BFF, cr(3f)))
        BText(name, bt(13f, 800, 17f), INK, Modifier.widthIn(max = 170.dp), lines = 2)
        BText(if (host != null) "$line · $host" else line, bt(12f, 400, 15.5f), SOFT, Modifier.weight(1f).padding(top = 1.dp), lines = 3)
    }
}

/** What each source is sent, in a few plain words. */
@Composable
internal fun sentLine(id: String): String = stringResource(
    when (id) {
        "overpass" -> R.string.mine_sent_area
        // the chargers around where the user looks, or along the route planned: not "nothing about you"
        "osm-chargers", "ocm", "afdc" -> R.string.mine_sent_chargers
        "ndw", "bnetza", "iss-orbit" -> R.string.mine_sent_nothing
        "photon" -> R.string.mine_sent_words
        "nominatim" -> R.string.mine_sent_reverse
        "mangrove" -> R.string.mine_sent_mangrove
        "foursquare", "yelp", "tripadvisor" -> R.string.mine_sent_ratings
        "open-meteo", "met-norway" -> R.string.mine_sent_weather
        "wikidata" -> R.string.mine_sent_wikidata
        "commons" -> R.string.mine_sent_images
        "osm-notes" -> R.string.mine_sent_notes
        "osm-edits" -> R.string.mine_sent_edits
        "atlas-packs" -> R.string.mine_sent_windows
        "brouter" -> R.string.mine_sent_brouter
        "panoramax" -> R.string.mine_sent_photo
        "open-prices" -> R.string.mine_sent_prices
        "transitous" -> R.string.mine_sent_transit
        "tankerkoenig" -> R.string.mine_sent_fuel
        else -> R.string.mine_sent_area
    },
)

/**
 * A kept thing, as the board's rows: a hairline, 9 px, the pale 34 px tile with the symbol, its name (14, 800 on 18) and
 * what is kept (11 on 14, grey), then a "Clear" button, a switch or the way in.
 */
@Composable
private fun KeptRow(symbol: String, title: String, sub: String, toggle: (@Composable () -> Unit)? = null, clear: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().bbox(border = hairTop()).then(if (onOpen != null) Modifier.androidClick(onOpen) else Modifier).padding(top = 10.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).bbox(PAGE, cr(10f)), contentAlignment = Alignment.Center) { MSy(symbol, 18f, INK) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BText(title, bt(14f, 800, 18f), INK)
            BText(sub, bt(11f, 400, 14f), SOFT, lines = 2)
        }
        if (clear != null) MiniButton(stringResource(R.string.mine_privacy_clear), clear, primary = false)
        toggle?.invoke()
        if (onOpen != null) MSy("ui.chevron-right", 16f, FAINT)
    }
}

internal fun Modifier.androidClick(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)
