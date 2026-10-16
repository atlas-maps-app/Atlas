package app.atlas.ui.mine

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.RegionLayer
import app.atlas.services.RegionRequest
import app.atlas.services.SettingsKeys
import app.atlas.services.Units
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.mine.offline.EstimateCard
import app.atlas.ui.mine.offline.MapKind
import app.atlas.ui.mine.offline.OfflineModel
import app.atlas.ui.mine.offline.PickerModel
import app.atlas.ui.mine.offline.PreviewMap
import app.atlas.ui.mine.offline.RegionEngine
import app.atlas.ui.mine.offline.VoiceLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Soft = Color(0xFF5A5F6C)
/** The dark pill of the boards: the theme's pill colour, light by night, with the colour of its text. */
private val Dark: Color @Composable get() = Atlas.colors.pill
private val OnDark: Color @Composable get() = Atlas.colors.onPill
private val STEPS = 3

/**
 * Welcome: three steps, each skippable. Where you are (the location permission, said plainly), taking the area around you
 * offline (the board's map, what it costs layer by layer, Wi-Fi only), and how Atlas speaks and measures. Finishing or
 * skipping marks the welcome as seen and goes on to the map.
 */
@Composable
fun OnboardingScreen(args: Dest.Onboarding, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(args.step.coerceIn(0, STEPS - 1)) }
    fun finish() {
        scope.launch { services.settings.set(SettingsKeys.ONBOARDED, true) }
        if (!nav.back()) nav.tab(app.atlas.nav.Tab.MAP)
    }
    val progress = stringResource(R.string.mine_welcome_step_cd, step + 1, STEPS)
    Box(Modifier.fillMaxSize().background(MineTone.of(0xFFF1F3F6, 0xFF101114)).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp).semantics { contentDescription = progress; progressBarRangeInfo = ProgressBarRangeInfo((step + 1).toFloat(), 1f..STEPS.toFloat(), STEPS - 1) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until STEPS) Box(
                Modifier.weight(1f).height(6.dp).clip(CircleShape).background(
                    when { i < step -> MineTone.green; i == step -> MineTone.of(0xFF14161B, 0xFFEDEEF1); else -> MineTone.of(0xFFD5D8DE, 0xFF3A3E46) },
                ),
            )
        }
        Column(Modifier.fillMaxSize().padding(top = 48.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            when (step) {
                0 -> LocationStep(onNext = { step = 1 }, onSkip = { step = 1 })
                1 -> RegionStep(args, onNext = { step = 2 })
                else -> VoiceStep(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun StepHead(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = serifTitle(38f, italic = false), color = Atlas.colors.ink, modifier = Modifier.semantics { heading() })
        Text(text, style = mt(15f, 400, line = 21f), color = MineTone.of(0xFF5A5F6C, 0xFFA0A5B1))
    }
}

/** The foot of a step: a light "Later" and a dark pill, as the board has them. */
@Composable
private fun Foot(later: String?, onLater: () -> Unit, next: String, onNext: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (later != null) Box(
            Modifier.width(110.dp).height(56.dp).clip(CircleShape).background(MineTone.of(0xFFFFFFFF, 0xFF1B1D22)).clickable(onClick = onLater),
            contentAlignment = Alignment.Center,
        ) { Text(later, style = mt(15f, 800), color = Atlas.colors.ink) }
        Box(
            Modifier.weight(1f).height(56.dp).clip(CircleShape).background(if (enabled) Dark else MineTone.of(0xFFD5D8DE, 0xFF3A3E46)).clickable(enabled = enabled, onClick = onNext),
            contentAlignment = Alignment.Center,
        ) { Text(next, style = mt(15f, 800), color = OnDark, maxLines = 1) }
    }
}

// ---- 1: where you are ----

@Composable
private fun ColumnScope.LocationStep(onNext: () -> Unit, onSkip: () -> Unit) {
    val services = LocalServices.current
    val context = LocalContext.current
    val permitted by services.location.permitted.collectAsStateWithLifecycle()
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        asked = true
        services.location.refreshPermission()
        if (grants.values.any { it }) onNext()
    }
    val activity = context as? Activity
    val blocked = asked && !permitted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepHead(stringResource(R.string.mine_welcome_loc_title), stringResource(R.string.mine_welcome_loc_text))
        // Pin → phone → lock: where you are stays on this phone.
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Bubble("ui-map-pin", Atlas.colors.go)
            Symbol("ui-arrow-right", tint = Atlas.colors.inkFaint, size = 22.dp)
            Bubble("ui-smartphone", Atlas.colors.ink)
            Symbol("ui-arrow-right", tint = Atlas.colors.inkFaint, size = 22.dp)
            Bubble("ui-lock", MineTone.green)
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((symbol, text) in listOf("ui.search" to R.string.mine_welcome_loc_p1, "ui.route" to R.string.mine_welcome_loc_p2, "ui.offline" to R.string.mine_welcome_loc_p3)) {
                val line = stringResource(text)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MineTone.of(0xFFFFFFFF, 0xFF1B1D22)).padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(symbol, tint = Atlas.colors.ink, size = 22.dp)
                    Text(line, style = mt(14f, 700, line = 19f), color = Atlas.colors.ink)
                }
            }
            if (permitted) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui.check", tint = MineTone.green, size = 18.dp, weight = 1.4f)
                Text(stringResource(R.string.mine_welcome_loc_on), style = mt(14f, 800), color = MineTone.green)
            }
        }
    }
    Foot(
        later = stringResource(R.string.mine_welcome_later), onLater = onSkip,
        next = stringResource(if (permitted) R.string.mine_welcome_next else if (blocked) R.string.mine_welcome_loc_settings else R.string.mine_welcome_loc_allow),
        onNext = {
            when {
                permitted -> onNext()
                blocked -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
    )
}

@Composable
private fun Bubble(symbol: String, tint: Color) {
    Box(Modifier.size(64.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Symbol(symbol, tint = tint, size = 30.dp) }
}

// ---- 2: the area around you ----

@Composable
private fun ColumnScope.RegionStep(args: Dest.Onboarding, onNext: () -> Unit) {
    val services = LocalServices.current
    val manager = services.mine.offline
    val scope = rememberCoroutineScope()
    val here = rememberHere()
    val home by services.go.store.home.collectAsStateWithLifecycle(null)
    // The first fix after the permission was given: before it the screen only knows the map's last look (or the Westerkerk),
    // and offering that area to someone elsewhere would download the wrong city.
    val fix by services.location.last.collectAsStateWithLifecycle()
    var anchor by remember { mutableStateOf(services.location.last.value?.location) }
    LaunchedEffect(Unit) { services.location.fixes(app.atlas.services.LocationQuality.MAP).collect { } }
    LaunchedEffect(fix) { if (anchor == null) anchor = fix?.location }
    val centre: LatLon = home?.at ?: anchor ?: here
    val wifi by services.settings.flow(MineKeys.WIFI_ONLY, true).collectAsStateWithLifecycle(true)
    val details by manager.details.collectAsStateWithLifecycle()
    var radius by rememberSaveable { mutableIntStateOf(12) }
    var bytes by remember { mutableStateOf<Map<RegionLayer, Long?>?>(null) }
    var kind by remember { mutableStateOf(MapKind.NONE) }
    var packed by remember { mutableStateOf(false) }
    var town by remember(centre) { mutableStateOf<String?>(null) }
    var dots by remember(centre) { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    val box = remember(centre, radius) { PickerModel.radiusBox(centre, radius.toDouble()) }
    val view = remember(centre) { PickerModel.fitView(BBox.around(centre, 30_000.0), 1.29f) }
    val layers = remember { setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.ROUTING, RegionLayer.VOICE) }
    val have = details.firstOrNull { it.complete && centre in it.meta.bbox }
    LaunchedEffect(centre) {
        withContext(Dispatchers.IO) {
            town = runCatching { PickerModel.townNear(services.placeStores, centre, "") }.getOrNull()?.takeIf { it.isNotBlank() }
            dots = runCatching { OfflineModel.dots(services.placeStores, view, 8, 24, 24) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(box) {
        bytes = null
        withContext(Dispatchers.IO) {
            runCatching { manager.engine.estimate(box, layers) }.onSuccess { e -> kind = e.mapKind; packed = e.pack != null; bytes = e.bytes }.onFailure { bytes = emptyMap() }
        }
    }
    val aroundHere = stringResource(R.string.mine_region_name)
    val name = town?.let { stringResource(R.string.mine_picker_name_area, it) } ?: aroundHere
    val cd = stringResource(R.string.mine_picker_map_cd)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepHead(stringResource(R.string.mine_welcome_take_title), stringResource(R.string.mine_welcome_take_text))
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PreviewMap(view, Modifier.fillMaxWidth(), dots = dots, frames = listOf(box), pin = centre, pinLabel = town, description = cd)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (r in listOf(5, 12, 25)) {
                    val on = r == radius
                    Box(
                        Modifier.height(36.dp).clip(CircleShape).background(if (on) Dark else MineTone.of(0xFFFFFFFF, 0xFF24272D)).clickable { radius = r }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.mine_welcome_radius, r), style = mt(13f, 700), color = if (on) OnDark else Atlas.colors.ink) }
                }
            }
            if (have != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MineTone.of(0xFFE7F4EC, 0xFF1B2B22)).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.check", tint = MineTone.greenDeep, size = 20.dp, weight = 1.4f)
                    Text(stringResource(R.string.mine_welcome_have, have.name, RegionEngine.size(have.bytes)), style = mt(14f, 700, line = 19f), color = Atlas.colors.ink)
                }
            } else {
                val caption = when {
                    bytes == null -> ""
                    kind == MapKind.ATLAS && packed -> stringResource(R.string.mine_picker_from_pack)
                    kind == MapKind.ATLAS -> stringResource(R.string.mine_picker_built)
                    kind == MapKind.TILES -> stringResource(R.string.mine_picker_tiles)
                    else -> stringResource(R.string.mine_est_unknown)
                }
                EstimateCard(bytes, caption)
                Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mine_welcome_wifi), Modifier.weight(1f), style = mt(14f, 700))
                    BoardSwitch(wifi, { scope.launch { services.settings.set(MineKeys.WIFI_ONLY, it) } }, stringResource(R.string.mine_offline_wifi_only))
                }
            }
        }
    }
    val total = bytes?.values?.sumOf { it ?: 0L } ?: 0L
    Foot(
        later = if (have == null) stringResource(R.string.mine_welcome_later) else null, onLater = onNext,
        next = stringResource(if (have != null) R.string.mine_welcome_next else R.string.mine_welcome_download),
        onNext = {
            if (have == null) manager.download(RegionRequest(PickerModel.unique(name, details.map { it.id }.toSet()), box, layers))
            onNext()
        },
        enabled = have != null || bytes != null,
    )
}

// ---- 3: voice and units ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.VoiceStep(onDone: () -> Unit) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saved by services.settings.flow(MineKeys.VOICE_LANGS, emptySet()).collectAsStateWithLifecycle(emptySet())
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    val device = remember { Locale.getDefault().language }
    val kept = VoiceLanguages.kept(saved, device)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepHead(stringResource(R.string.mine_welcome_voice_title), stringResource(R.string.mine_welcome_voice_text))
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lang in VoiceLanguages.ALL) {
                    val on = lang in kept
                    val label = remember(lang) { Locale.forLanguageTag(lang).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.uppercase() } } }
                    Row(
                        Modifier.height(40.dp).clip(CircleShape).background(if (on) Dark else MineTone.of(0xFFFFFFFF, 0xFF24272D)).clickable {
                            scope.launch {
                                val next = if (on) kept - lang else kept + lang
                                services.settings.set(MineKeys.VOICE_LANGS, next.takeIf { it.isNotEmpty() })
                                if (!on && VoiceLanguages.installed(context, lang) == false) VoiceLanguages.install(context)
                            }
                        }.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (on) Symbol("ui.check", tint = OnDark, size = 14.dp, weight = 1.4f)
                        Text(label, style = mt(14f, 700), color = if (on) OnDark else Atlas.colors.ink, maxLines = 1)
                    }
                }
            }
            Text(stringResource(R.string.mine_welcome_units_title), style = serifTitle(28f, italic = false), color = Atlas.colors.ink, modifier = Modifier.semantics { heading() })
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (u in Units.entries) {
                    val on = u == units
                    val name = stringResource(when (u) { Units.METRIC -> R.string.mine_welcome_u_metric; Units.IMPERIAL -> R.string.mine_welcome_u_imperial; Units.NAUTICAL -> R.string.mine_welcome_u_nautical })
                    val sample = "${SettingsModel.distance(12_400.0, u)} · ${SettingsModel.speed(50.0, u)}"
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Dark else MineTone.of(0xFFFFFFFF, 0xFF1B1D22)).clickable {
                            scope.launch {
                                services.settings.set(SettingsKeys.UNITS, u.name)
                                services.settings.set(MineKeys.TEMP_F, u == Units.IMPERIAL)
                            }
                        }.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, Modifier.weight(1f), style = mt(15f, 800), color = if (on) OnDark else Atlas.colors.ink)
                        Text(sample, style = mt(13f, 500, tabular = true), color = if (on) OnDark.copy(alpha = 0.75f) else Soft)
                        if (on) Symbol("ui.check", tint = OnDark, size = 18.dp, weight = 1.4f)
                    }
                }
            }
        }
    }
    Foot(later = null, onLater = {}, next = stringResource(R.string.mine_welcome_done), onNext = onDone)
}
