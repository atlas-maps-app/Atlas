package app.atlas.ui.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
        BoardCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            PhonesToPhone(Modifier.fillMaxWidth().height(120.dp))
            Text(stringResource(R.string.mine_phone_text), style = mt(13f, 400, line = 18f), color = Atlas.colors.inkSoft, modifier = Modifier.padding(top = 8.dp))
        }
        BoardCard {
            SectionLabel(stringResource(R.string.mine_phone_in_file), Modifier.padding(top = 10.dp, bottom = 4.dp), MineTone.steel)
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Count("ui.bookmark", pluralStringResource(R.plurals.mine_n_lists, lists.size, lists.size), Modifier.weight(1f))
                Count("ui.pin", pluralStringResource(R.plurals.mine_n_places, places, places), Modifier.weight(1f))
                Count("ui.settings", stringResource(R.string.mine_phone_settings), Modifier.weight(1f))
            }
            if (trailOn) SettingRow(stringResource(R.string.mine_phone_trail), symbol = "ui.pin", tile = MineTone.steel, trailing = { BoardSwitch(withTrail, { withTrail = it }, stringResource(R.string.mine_phone_trail)) }, chevron = false)
        }
        BigButton(stringResource(R.string.mine_phone_export), "ui.share", MineTone.blue) { export.launch("atlas-backup.json") }
        BigButton(stringResource(R.string.mine_phone_import), "ui.download", Color(0xFF14161B)) { import.launch(arrayOf("application/json", "text/plain", "*/*")) }
        message?.let { (ok, text) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (ok) Color(0xFFE7F4EC) else Color(0xFFFBE7E5)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(if (ok) "ui.check" else "ui.warning", tint = if (ok) Color(0xFF12663A) else Color(0xFF9B2A24), size = 20.dp)
                Text(text, style = mt(13f, 600, line = 17f), color = if (ok) Color(0xFF12663A) else Color(0xFF9B2A24))
            }
        }
        BoardCard(onClick = { nav.go(Dest.Offline) }, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MineTone.green), contentAlignment = Alignment.Center) { Symbol("ui.offline", tint = Color.White, size = 18.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.mine_phone_regions), style = mt(14f, 800), color = Atlas.colors.ink)
                    Text(
                        if (details.isEmpty()) stringResource(R.string.mine_phone_regions_none)
                        else stringResource(R.string.mine_phone_regions_sub, details.joinToString(", ") { it.name }, RegionEngine.size(details.sumOf { it.bytes })),
                        style = mt(12f, 400, line = 16f), color = Atlas.colors.inkSoft,
                    )
                }
                Symbol("ui.chevron-right", tint = Atlas.colors.inkFaint, size = 16.dp)
            }
        }
        Text(stringResource(R.string.mine_phone_keys), style = mt(11.5f, 400, line = 16f), color = Atlas.colors.inkFaint, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun Count(symbol: String, text: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MineTone.of(0xFFF1F3F6, 0xFF2A2D34)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Symbol(symbol, size = 20.dp)
        Text(text, style = mt(12f, 700, tabular = true), color = Atlas.colors.ink)
    }
}

@Composable
private fun BigButton(text: String, symbol: String, colour: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(colour).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = Color.White, size = 20.dp)
        Text(text, style = mt(15f, 800), color = Color.White)
    }
}

/** Two phones and a dashed way from one to the other, drawn in the board's ink. */
@Composable
private fun PhonesToPhone(modifier: Modifier) {
    val ink = Atlas.colors.ink
    val soft = Atlas.colors.inkFaint
    Canvas(modifier) {
        val s = density
        fun phone(cx: Float, tilt: Float, fill: Color) {
            val w = 54f * s
            val h = 100f * s
            val left = cx - w / 2
            val top = (size.height - h) / 2
            drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(10f * s))
            drawRoundRect(ink, Offset(left, top), Size(w, h), CornerRadius(10f * s), style = Stroke(2.2f * s))
            drawRoundRect(ink, Offset(cx - 9f * s, top + 6f * s), Size(18f * s, 3.5f * s), CornerRadius(2f * s))
            // A few lines of a screen.
            for (i in 0..3) drawLine(soft, Offset(left + 9f * s, top + 28f * s + i * 12f * s), Offset(left + w - 9f * s - (i % 2) * 12f * s, top + 28f * s + i * 12f * s), 2.4f * s, StrokeCap.Round)
        }
        phone(size.width * 0.2f, 0f, Color(0x14000000))
        phone(size.width * 0.8f, 0f, Color(0x00000000))
        val y = size.height / 2
        drawLine(ink, Offset(size.width * 0.2f + 40f * s, y), Offset(size.width * 0.8f - 44f * s, y), 2.4f * s, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f * s, 6f * s)))
        val ax = size.width * 0.8f - 44f * s
        drawLine(ink, Offset(ax - 9f * s, y - 8f * s), Offset(ax, y), 2.4f * s, StrokeCap.Round)
        drawLine(ink, Offset(ax - 9f * s, y + 8f * s), Offset(ax, y), 2.4f * s, StrokeCap.Round)
    }
}
