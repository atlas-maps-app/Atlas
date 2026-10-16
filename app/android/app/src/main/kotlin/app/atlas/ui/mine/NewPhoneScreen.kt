package app.atlas.ui.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import app.atlas.ui.kit.Argb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.mine.offline.RegionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Move to a new phone: the settings, saved places and lists as one plain file through the system's file picker (a drive,
 * a cable, a message to yourself), and the way back in on the other phone. The file holds no keys. Offline regions are
 * large and public: download them again there (Offline lists what to take).
 */
@Composable
fun NewPhoneScreen(args: Dest.NewPhone, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val details by services.mine.offline.details.collectAsStateWithLifecycle()
    val trailOn by services.settings.flow(MineKeys.TRAIL, false).collectAsStateWithLifecycle(false)
    var withTrail by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val text = withContext(Dispatchers.IO) { Backup.export(services, trail = withTrail) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no file") }
            }
            message = if (ok.isSuccess) true to context.getString(R.string.mine_phone_exported) else false to context.getString(R.string.mine_phone_export_failed)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { Backup.read(it) } ?: error("no file") }
                withContext(Dispatchers.IO) { Backup.import(services, text) }
            }
            message = r.fold(
                { s -> true to context.getString(R.string.mine_phone_imported, s.settings, s.lists, s.places) },
                { e ->
                    false to when {
                        e is BackupException && e.tooBig -> context.getString(R.string.mine_phone_too_big)
                        e is BackupException && e.newer -> context.getString(R.string.mine_phone_newer)
                        e is BackupException -> context.getString(R.string.mine_phone_not_backup)
                        else -> context.getString(R.string.mine_phone_failed)
                    }
                },
            )
        }
    }
    val places = lists.sumOf { it.places.size }
    BoardPage(stringResource(R.string.mine_phone_title), onBack = { nav.back() }) {
        BoardCard(padding = PaddingValues(16.dp)) {
            // This phone to the next, in the board symbols: two phones and the way between them.
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                MSy("ui.smartphone", 64f, INK)
                MSy("ui.arrow-right", 28f, FAINT)
                MSy("ui.smartphone", 64f, INK)
            }
            BText(stringResource(R.string.mine_phone_text), bt(13f, 400, 18.2f), SOFT, Modifier.padding(top = 8.dp), lines = 8)
        }
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
            CardLabel(stringResource(R.string.mine_phone_in_file), Modifier.padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Count("ui.bookmark", pluralStringResource(R.plurals.mine_n_lists, lists.size, lists.size), Modifier.weight(1f))
                Count("ui.pin.2", pluralStringResource(R.plurals.mine_n_places, places, places), Modifier.weight(1f))
                Count("ui.settings", stringResource(R.string.mine_phone_settings), Modifier.weight(1f))
            }
            if (trailOn) SettingRow(stringResource(R.string.mine_phone_trail), symbol = "ui.pin.2", tile = MineTone.steel, trailing = { MineSwitch(withTrail, { withTrail = it }, stringResource(R.string.mine_phone_trail)) }, chevron = false)
        }
        BigButton(stringResource(R.string.mine_phone_export), "ui.share", 0xFF2E5BFF) { export.launch("atlas-backup.json") }
        BigButton(stringResource(R.string.mine_phone_import), "ui.download", INK) { import.launch(arrayOf("application/json", "text/plain", "*/*")) }
        message?.let { (ok, text) ->
            Row(
                Modifier.fillMaxWidth().bbox(if (ok) 0xFFE7F4EC else 0xFFFBE7E5, cr(16f)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                MSy(if (ok) "ui.check" else "ui.warning", 20f, if (ok) 0xFF12663A else 0xFF9B2A24)
                BText(text, bt(13f, 600, 17f), if (ok) 0xFF12663A else 0xFF9B2A24, lines = 4)
            }
        }
        BoardCard {
            SettingRow(
                stringResource(R.string.mine_phone_regions), symbol = "ui.no-signal.2", tile = MineTone.green,
                subtitle = if (details.isEmpty()) stringResource(R.string.mine_phone_regions_none)
                else stringResource(R.string.mine_phone_regions_sub, details.joinToString(", ") { it.name }, RegionEngine.size(details.sumOf { it.bytes })),
                onClick = { nav.go(Dest.Offline) },
            )
        }
        BText(stringResource(R.string.mine_phone_keys), bt(11f, 400, 14f, align = TextAlign.Center), 0xFF7A7F8C, Modifier.fillMaxWidth().padding(top = 12.dp), lines = 4)
    }
}

/** What the file holds: a pale tile (radius 14) with the symbol and a count (12, 700 on 15.5). */
@Composable
private fun Count(symbol: String, text: String, modifier: Modifier) {
    Column(modifier.bbox(PAGE, cr(14f)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MSy(symbol, 20f, INK)
        BText(text, bt(12f, 700, 15.5f, tnum = true), INK)
    }
}

/** A wide button of the boards: 54 high, radius 27, the 20 px symbol and the label (14, 800 on 18) in white on [colour]. */
@Composable
private fun BigButton(text: String, symbol: String, colour: Argb, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).bbox(colour, cr(27f)).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        MSy(symbol, 20f, WHITE)
        BText(text, bt(14f, 800, 18f), WHITE)
    }
}
