package app.atlas.ui.mine

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.services.Units
import app.atlas.ui.go.SavedPlace
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.mine.offline.OfflineModel
import app.atlas.ui.mine.offline.RegionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * Your Atlas, the settings board: the voice on its steel knob, the units as three pills, the map look, text size, ears
 * only, home and work, saved places and offline regions, the car, and the way to the data sources, privacy, a new phone
 * and the licences. Everything reads and writes the phone's own settings; nothing is sent anywhere.
 */
@Composable
fun SettingsScreen(args: Dest.Settings, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = services.settings
    val units by settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val fahrenheit by settings.flow(MineKeys.TEMP_F, false).collectAsStateWithLifecycle(false)
    val twelve by settings.flow(MineKeys.CLOCK_12, false).collectAsStateWithLifecycle(false)
    val muted by settings.flow(MineKeys.VOICE_MUTED, false).collectAsStateWithLifecycle(false)
    val haptics by settings.flow(MineKeys.HAPTICS, true).collectAsStateWithLifecycle(true)
    val theme by settings.flow(SettingsKeys.MAP_THEME, "AUTO").collectAsStateWithLifecycle("AUTO")
    val names by settings.flow(SettingsKeys.NAME_LANGUAGE).collectAsStateWithLifecycle(null)
    val ready by services.voice.ready.collectAsStateWithLifecycle()
    val home by services.go.store.home.collectAsStateWithLifecycle(null)
    val work by services.go.store.work.collectAsStateWithLifecycle(null)
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val details by services.mine.offline.details.collectAsStateWithLifecycle()
    val ears by services.go.ears.collectAsStateWithLifecycle()
    val car by settings.flow(MineKeys.EV_MODEL).collectAsStateWithLifecycle(null)
    val off by settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).collectAsStateWithLifecycle(emptySet())
    val keys by services.keys.keys.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var namesOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val marks = remember { HashMap<String, Float>() }
    LaunchedEffect(args.section) {
        val section = args.section ?: return@LaunchedEffect
        // The rows report where they are once laid out; scroll to the one asked for when they have.
        repeat(20) { if (marks[section] == null) kotlinx.coroutines.delay(50) }
        marks[section]?.let { scroll.animateScrollTo(it.toInt()) }
    }
    val (sourcesOn, _) = SourcesModel.summary(SourcesModel.rows(off, keys.orEmpty()))
    val carName = remember(car) { car?.let { id -> app.atlas.providers.EvModels.bundled().byId(id)?.let { "${it.make} ${it.model}" } } }
    val sample = stringResource(R.string.mine_voice_sample_text)
    val notSet = stringResource(R.string.mine_settings_not_set)
    val local = stringResource(R.string.mine_settings_names_local)
    val noneYet = stringResource(R.string.mine_settings_offline_none)
    val noCar = stringResource(R.string.mine_settings_car_none)
    val offlineValue = if (details.isEmpty()) "" else stringResource(R.string.mine_settings_offline_value, details.size, RegionEngine.size(details.sumOf { it.bytes }))
    val look = when (theme) {
        "DAY" -> stringResource(R.string.mine_theme_day)
        "NIGHT" -> stringResource(R.string.mine_theme_night)
        "CHART" -> stringResource(R.string.mine_theme_chart)
        "GRAY" -> stringResource(R.string.mine_theme_calm)
        else -> stringResource(R.string.mine_settings_look_auto)
    }
    BoardPage(stringResource(R.string.mine_settings_title), onBack = { nav.back() }, scrollState = scroll) {
        // The voice: the steel knob, what it says and its buttons (the board's card: 4 px in, a 96 px row, 14 px gaps).
        BoardCard(Modifier.onGloballyPositioned { marks["voice"] = it.positionInParent().y }, padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val toggle = stringResource(if (muted) R.string.mine_voice_off else R.string.mine_voice_on)
                Box(Modifier.clickable(role = Role.Switch) { scope.launch { settings.set(MineKeys.VOICE_MUTED, !muted); services.go.setMuted(!muted) } }.semantics { contentDescription = toggle }) {
                    VoiceKnob(on = !muted, Modifier.size(76.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BText(toggle.substringBefore(',').substringBefore(" ("), bt(16f, 800, 20.5f), INK)
                    BText(stringResource(if (ready) R.string.mine_voice_sub_ready else R.string.mine_voice_sub_none), bt(12f, 400, 15.5f), SOFT, lines = 2)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniButton(stringResource(R.string.mine_voice_sample), { services.voice.say(sample) })
                        MiniButton(stringResource(R.string.mine_voice_haptics), { scope.launch { settings.set(MineKeys.HAPTICS, !haptics); (services.voice as? app.atlas.ui.go.TtsVoice)?.setHaptics(!haptics) } }, primary = haptics, symbol = "ui.vibrate")
                        MiniButton(stringResource(R.string.mine_voice_engine), { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, primary = false)
                    }
                }
            }
        }
        // The looks of the app and the map.
        BoardCard {
            Row(Modifier.onGloballyPositioned { marks["units"] = it.positionInParent().y }) {
                SettingRow(
                    stringResource(R.string.mine_settings_units), symbol = "ui.units", tile = MineTone.steel,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (u in Units.entries) UnitPill(unitName(u), units == u) { scope.launch { settings.set(SettingsKeys.UNITS, u.name) } }
                            UnitPill(stringResource(if (fahrenheit) R.string.mine_settings_fahrenheit else R.string.mine_settings_celsius), true) { scope.launch { settings.set(MineKeys.TEMP_F, !fahrenheit) } }
                            UnitPill(stringResource(if (twelve) R.string.mine_settings_12h else R.string.mine_settings_24h), true) { scope.launch { settings.set(MineKeys.CLOCK_12, !twelve) } }
                        }
                    },
                )
            }
            SettingRow(stringResource(R.string.mine_settings_look), symbol = "ui.layers.2", tile = Color(INK), value = look, onClick = { nav.go(Dest.Layers) })
            SettingRow(stringResource(R.string.mine_settings_names), symbol = "ui.globe", tile = Color(0xFF2E7BC4), value = names?.let(SettingsModel::languageName) ?: local, onClick = { namesOpen = true })
            val scale = LocalContext.current.resources.configuration.fontScale
            SettingRow(
                stringResource(R.string.mine_settings_text_size), symbol = "ui.text-size", tile = MineTone.blue, value = stringResource(R.string.mine_settings_text_size_value, Math.round(scale * 100)),
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            )
            SettingRow(stringResource(R.string.mine_settings_ears), symbol = "ui.eye-off", tile = Color.Black, value = stringResource(if (ears != null) R.string.mine_settings_ears_on else R.string.mine_settings_ears_off), onClick = { nav.go(Dest.Ears) })
        }
        // Your places.
        BoardCard {
            SettingRow(stringResource(R.string.mine_settings_home), symbol = "place.home", tile = MineTone.amber, value = home?.name?.ifBlank { null } ?: home?.let { coords(it.at) } ?: notSet, onClick = { picking = true })
            SettingRow(stringResource(R.string.mine_settings_work), symbol = "place.tools", tile = MineTone.purple, value = work?.name?.ifBlank { null } ?: work?.let { coords(it.at) } ?: notSet, onClick = { picking = false })
            SettingRow(stringResource(R.string.mine_settings_saved), symbol = "ui.bookmark", tile = Color(0xFFC4452F), value = pluralStringResource(R.plurals.mine_n_lists, lists.size, lists.size), onClick = { nav.go(Dest.Lists()) })
            SettingRow(
                stringResource(R.string.mine_settings_offline), symbol = "ui.no-signal.2", tile = MineTone.green,
                value = if (details.isEmpty()) noneYet else offlineValue,
                onClick = { nav.go(Dest.Offline) },
            )
            SettingRow(stringResource(R.string.mine_settings_car), symbol = "ui.charge", tile = Color(0xFF3FCF8E), value = carName ?: noCar, onClick = { nav.go(Dest.Charge()) })
        }
        // What is kept and where it comes from.
        BoardCard {
            SettingRow(stringResource(R.string.mine_settings_privacy), symbol = "ui.privacy.2", tile = Color(0xFF0F3D2A), value = stringResource(R.string.mine_settings_privacy_value), onClick = { nav.go(Dest.Privacy) })
            SettingRow(stringResource(R.string.mine_settings_data), symbol = "ui.key", tile = MineTone.purple, value = stringResource(R.string.mine_settings_data_value, sourcesOn), onClick = { nav.go(Dest.DataSources()) })
            SettingRow(stringResource(R.string.mine_settings_new_phone), symbol = "ui.smartphone", tile = MineTone.blue, value = stringResource(R.string.mine_settings_new_phone_value), onClick = { nav.go(Dest.NewPhone) })
            SettingRow(stringResource(R.string.mine_settings_about), symbol = "ui.info", tile = MineTone.steel, onClick = { nav.go(Dest.About) })
        }
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
        // The board's footer: 11 on 14, grey, centred.
        BText(
            stringResource(R.string.mine_settings_footer, version), bt(11f, 400, 14f, align = androidx.compose.ui.text.style.TextAlign.Center), 0xFF7A7F8C,
            Modifier.fillMaxWidth().padding(top = 12.dp), lines = 2,
        )
    }
    picking?.let { isHome ->
        PlacePicker(
            title = stringResource(if (isHome) R.string.mine_pick_title_home else R.string.mine_pick_title_work),
            current = if (isHome) home else work,
            onPick = { p -> picking = null; scope.launch { if (isHome) services.go.store.setHome(p) else services.go.store.setWork(p) } },
            onClose = { picking = null },
        )
    }
    if (namesOpen) NamesDialog(names, onPick = { code -> namesOpen = false; scope.launch { settings.set(SettingsKeys.NAME_LANGUAGE, code) } }, onClose = { namesOpen = false })
}

private fun coords(at: LatLon) = String.format(java.util.Locale.ROOT, "%.4f, %.4f", at.lat, at.lon)

@Composable
private fun unitName(u: Units): String = stringResource(
    when (u) { Units.METRIC -> R.string.mine_settings_km; Units.IMPERIAL -> R.string.mine_settings_mi; Units.NAUTICAL -> R.string.mine_settings_nm },
)

/** A pill of the units row (28 high, 9 px at the sides, 12 px 800 on 15.5): dark when it is what is set, light otherwise. */
@Composable
private fun UnitPill(text: String, selected: Boolean, onClick: () -> Unit) {
    TextPill(text, bt(12f, 800, 15.5f, tnum = true), if (selected) INK else HAIR, if (selected) WHITE else INK, padH = 9f, onClick = onClick)
}

/**
 * The steel knob of the settings board (76 × 76): a silver disc, ten ticks over 270° and a needle. Here it is the voice's
 * switch: the needle points to 1.0× when the voice is on and to the stop when it is off.
 */
@Composable
internal fun VoiceKnob(on: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = size.minDimension / 76f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        drawCircle(Brush.linearGradient(listOf(Color(0xFFF2F4F6), Color(0xFF9AA2AE)), start = p(4f, 4f), end = p(72f, 72f)), radius = 34f * k, center = p(38f, 38f))
        drawCircle(Color(0xFF6B7282), radius = 34f * k, center = p(38f, 38f), style = Stroke(2f * k))
        for (i in 0..9) {
            val a = Math.toRadians(135.0 + 30.0 * i)
            drawLine(Color(0xFF14161B), p(38f + 26f * cos(a).toFloat(), 38f + 26f * sin(a).toFloat()), p(38f + 30f * cos(a).toFloat(), 38f + 30f * sin(a).toFloat()), 1.5f * k)
        }
        val tip = if (on) p(58f, 24f) else p(38f + 20f * cos(Math.toRadians(135.0)).toFloat(), 38f + 20f * sin(Math.toRadians(135.0)).toFloat())
        drawLine(Color(0xFF14161B), p(38f, 38f), tip, 4f * k, cap = StrokeCap.Round)
        drawCircle(Color(0xFF14161B), radius = 6f * k, center = p(38f, 38f))
    }
}

/** A dialog to set home or work: where you are now, or an address or a place searched offline and online. */
@Composable
internal fun PlacePicker(title: String, current: SavedPlace?, onPick: (SavedPlace?) -> Unit, onClose: () -> Unit) {
    val services = LocalServices.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SavedPlace>()) }
    var searched by remember { mutableStateOf(false) }
    val here by services.location.last.collectAsStateWithLifecycle()
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); searched = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        val near = here?.location
        results = withContext(Dispatchers.IO) {
            val offline = runCatching { services.placeStores.text(query, near, null, 6).mapNotNull { r -> r.name()?.let { SavedPlace(it, r.location, r.id?.toString()) } } }.getOrDefault(emptyList())
            val online = if (!services.config.offline && services.connectivity.online.value) runCatching { services.geocoder.search(query, near, null, 6).mapNotNull { p -> p.name()?.let { SavedPlace(it, p.location, p.id?.toString()) } } }.getOrDefault(emptyList()) else emptyList()
            (offline + online).distinctBy { it.name to (it.at.lat * 1000).toInt() to (it.at.lon * 1000).toInt() }.take(8)
        }
        searched = true
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                here?.let { fix ->
                    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color(PAGE)).clickable { onPick(SavedPlace("", fix.location)) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol("ui.locate", size = 20.dp)
                        Text(stringResource(R.string.mine_pick_here), style = mt(14f, 700))
                    }
                }
                Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color(PAGE)).padding(12.dp)) {
                    val hint = stringResource(R.string.mine_pick_search)
                    if (query.isEmpty()) Text(hint, style = mt(14f, 400), color = Color(FAINT))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = mt(14f, 500).copy(color = Color(INK)), cursorBrush = SolidColor(Color(INK)), modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search))
                }
                for (r in results) Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol("ui.pin", size = 18.dp, tint = Color(SOFT))
                    Text(r.name, style = mt(14f, 600), maxLines = 1)
                }
                if (searched && results.isEmpty()) Text(stringResource(R.string.mine_pick_none), style = mt(13f, 400), color = Color(SOFT))
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.mine_pick_close)) } },
        dismissButton = if (current != null) ({ TextButton({ onPick(null) }) { Text(stringResource(R.string.mine_pick_clear), color = MineTone.red) } }) else null,
    )
}

/** The languages for names on the map, the first being the local names. */
@Composable
private fun NamesDialog(current: String?, onPick: (String?) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.mine_names_title)) },
        text = {
            Column {
                for (code in SettingsModel.NAME_LANGUAGES) Row(Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(code?.let(SettingsModel::languageName) ?: stringResource(R.string.mine_names_local), style = mt(15f, if (code == current) 800 else 500), modifier = Modifier.weight(1f))
                    if (code == current) Symbol("ui.check", size = 18.dp)
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.mine_pick_close)) } },
    )
}
