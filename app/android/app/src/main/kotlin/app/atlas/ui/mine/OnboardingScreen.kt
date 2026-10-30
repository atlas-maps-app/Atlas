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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
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
import app.atlas.ui.kit.Argb
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

private val STEPS = 3

/**
 * Welcome, as the Welcome board: three steps, each skippable. Where you are (the location permission, said plainly), taking
 * the area around you offline (the board's map of it, what it costs layer by layer, Wi-Fi only), and how Atlas speaks and
 * measures. Finishing or skipping marks the welcome as seen and goes on to the map.
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
    Box(Modifier.fillMaxSize().background(Color(PAGE))) {
        // The steps: three bars of 6 px at y 48, 6 px apart, green when done, ink for this one, grey to come.
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 48.dp + shift()).semantics { contentDescription = progress; progressBarRangeInfo = ProgressBarRangeInfo((step + 1).toFloat(), 1f..STEPS.toFloat(), STEPS - 1) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until STEPS) Box(Modifier.weight(1f).height(6.dp).bbox(if (i < step) 0xFF1E9E5A else if (i == step) INK else 0xFFD5D8DE, cr(3f)))
        }
        Column(Modifier.fillMaxSize().padding(top = 54.dp + shift())) {
            when (step) {
                0 -> LocationStep(onNext = { step = 1 }, onSkip = { step = 1 })
                1 -> RegionStep(args, onNext = { step = 2 })
                else -> VoiceStep(onDone = { finish() })
            }
        }
    }
}

/**
 * A step's head as the board sets it: the serif title's line boxes from y 72 (38 on 39.9, 24 px in), the lead from y 158
 * (15 on 21, grey) or under a longer title, then [gap] to what follows.
 */
@Composable
private fun StepHead(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
        Box(Modifier.heightIn(min = 86.dp)) {
            BText(title, bt(38f, 400, 39.9f, SERIF), INK, Modifier.semantics { heading() }, lines = 3)
        }
        BText(text, bt(15f, 400, 21f), SOFT, lines = 6)
    }
}

/** The foot of a step, 32 px from the bottom: "Later" on white (110 × 56) and the dark pill (15, 800 on 19.5), 8 px apart. */
@Composable
private fun Foot(later: String?, onLater: () -> Unit, next: String, onNext: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = foot(32f)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (later != null) Box(
            Modifier.width(110.dp).height(56.dp).bbox(WHITE, cr(28f)).clickable(role = Role.Button, onClick = onLater),
            contentAlignment = Alignment.Center,
        ) { BText(later, bt(15f, 800, 19.5f), INK) }
        Box(
            Modifier.weight(1f).height(56.dp).bbox(if (enabled) INK else 0xFFD5D8DE, cr(28f)).clickable(enabled = enabled, role = Role.Button, onClick = onNext),
            contentAlignment = Alignment.Center,
        ) { BText(next, bt(15f, 800, 19.5f), WHITE) }
    }
}

/** A white card of the Welcome board: radius 20, 12 px above and below, 14 at the sides. */
private fun Modifier.welcomeCard(bg: Argb = WHITE): Modifier = this.fillMaxWidth().bbox(bg, cr(20f)).padding(horizontal = 14.dp, vertical = 12.dp)

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
        // Pin → phone → lock: where you are stays on this phone (the board symbols, 30 px in 64 px rounds).
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Bubble("ui.pin.2", 0xFF2E7BFF)
            MSy("ui.arrow-right", 22f, FAINT)
            Bubble("ui.smartphone", INK)
            MSy("ui.arrow-right", 22f, FAINT)
            Bubble("ui.lock", 0xFF1E9E5A)
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((symbol, text) in listOf("ui.search.2" to R.string.mine_welcome_loc_p1, "ui.route" to R.string.mine_welcome_loc_p2, "ui.no-signal.2" to R.string.mine_welcome_loc_p3)) {
                Row(Modifier.welcomeCard(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MSy(symbol, 22f, INK)
                    BText(stringResource(text), bt(14f, 700, 18f), INK, lines = 2)
                }
            }
            if (permitted) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MSy("ui.check", 18f, 0xFF1E9E5A)
                BText(stringResource(R.string.mine_welcome_loc_on), bt(14f, 800, 18f), 0xFF1E9E5A)
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

/** A 64 px round of [tint] at 14 % with its 30 px symbol. */
@Composable
private fun Bubble(symbol: String, tint: Argb) {
    Box(Modifier.size(64.dp).bbox((tint and 0xFFFFFF) or 0x24000000, cr(32f)), contentAlignment = Alignment.Center) { MSy(symbol, 30f, tint) }
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
        // The board's map (348 × 270 at x 32, 22 px under the lead) of the area around you, the radius over its corner.
        Box(Modifier.padding(start = 32.dp, end = 32.dp, top = 22.dp)) {
            PreviewMap(view, Modifier.fillMaxWidth(), dots = dots, frames = listOf(box), pin = centre, pinLabel = town, description = cd)
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in listOf(5, 12, 25)) {
                    val on = r == radius
                    TextPill(stringResource(R.string.mine_welcome_radius, r), bt(12f, 800, 15.5f, tnum = true), if (on) INK else WHITE, if (on) WHITE else INK, padH = 9f) { radius = r }
                }
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp)) {
            if (have != null) {
                Row(Modifier.welcomeCard(0xFFE7F4EC), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MSy("ui.check", 20f, 0xFF178A4E)
                    BText(stringResource(R.string.mine_welcome_have, have.name, RegionEngine.size(have.bytes)), bt(14f, 700, 18f), INK, lines = 3)
                }
            } else {
                val caption = when {
                    bytes == null -> ""
                    kind == MapKind.TILES || kind == MapKind.ATLAS && packed -> stringResource(R.string.mine_picker_from_pack)
                    kind == MapKind.ATLAS -> stringResource(R.string.mine_picker_built)
                    else -> stringResource(R.string.mine_est_unknown)
                }
                EstimateCard(bytes, caption)
                // Wi-Fi only: 14, 700 on 18 and the switch, 15 px under the card.
                Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    BText(stringResource(R.string.mine_welcome_wifi), bt(14f, 700, 18f), INK, Modifier.weight(1f))
                    MineSwitch(wifi, { scope.launch { services.settings.set(MineKeys.WIFI_ONLY, it) } }, stringResource(R.string.mine_offline_wifi_only))
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
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lang in VoiceLanguages.ALL) {
                    val on = lang in kept
                    val label = remember(lang) { Locale.forLanguageTag(lang).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.uppercase() } } }
                    TextPill(
                        label, bt(14f, 700, 18f), if (on) INK else WHITE, if (on) WHITE else INK, h = 40f, padH = 14f,
                        symbol = if (on) "ui.check" else null, symbolSize = 14f, gap = 6f,
                    ) {
                        scope.launch {
                            val next = if (on) kept - lang else kept + lang
                            services.settings.set(MineKeys.VOICE_LANGS, next.takeIf { it.isNotEmpty() })
                            if (!on && VoiceLanguages.installed(context, lang) == false) VoiceLanguages.install(context)
                        }
                    }
                }
            }
            BText(stringResource(R.string.mine_welcome_units_title), bt(28f, 400, 29.4f, SERIF), INK, Modifier.semantics { heading() })
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (u in Units.entries) {
                    val on = u == units
                    val name = stringResource(when (u) { Units.METRIC -> R.string.mine_welcome_u_metric; Units.IMPERIAL -> R.string.mine_welcome_u_imperial; Units.NAUTICAL -> R.string.mine_welcome_u_nautical })
                    val sample = "${SettingsModel.distance(12_400.0, u)} · ${SettingsModel.speed(50.0, u)}"
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).bbox(if (on) INK else WHITE, cr(20f)).clickable(role = Role.Button) {
                            scope.launch {
                                services.settings.set(SettingsKeys.UNITS, u.name)
                                services.settings.set(MineKeys.TEMP_F, u == Units.IMPERIAL)
                            }
                        }.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BText(name, bt(15f, 800, 19.5f), if (on) WHITE else INK, Modifier.weight(1f))
                        BText(sample, bt(13f, 500, 17f, tnum = true), if (on) 0xBFFFFFFF else SOFT)
                        if (on) MSy("ui.check", 18f, WHITE)
                    }
                }
            }
        }
    }
    Foot(later = null, onLater = {}, next = stringResource(R.string.mine_welcome_done), onNext = onDone)
}
