package app.atlas.ui.mine

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.mapview.Theme
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.PaletteFlags
import app.atlas.mapview.palette.Preset
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.DEFAULT_PALETTE
import app.atlas.services.DEFAULT_TONE_ON
import app.atlas.services.SettingsKeys
import app.atlas.services.SettingsStore
import app.atlas.ui.kit.CssShadow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Map look (the Map look board, `Styles.dc.html`): the six presets as glyphs of their colours, the Tone and its
 * strength, Atlas's guesses, the bare ground and whether mapped colours follow, the palettes the user saved with a way to
 * share each as a code and a QR code and to add one from a code, and the way into Colour the map. Each choice is the
 * map's at once (MapLookWrites); a code handed to Atlas ([Dest.MapLook.code]: the QR's link, a shared message) is added
 * to the palettes and taken.
 */
@Composable
fun MapLookScreen(args: Dest.MapLook, nav: Navigator) {
    val services = LocalServices.current
    val settings = services.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val themeName by settings.flow(SettingsKeys.MAP_THEME, Theme.AUTO.name).collectAsStateWithLifecycle(Theme.AUTO.name)
    val code by settings.flow(SettingsKeys.MAP_PALETTE).collectAsStateWithLifecycle(null)
    val savedText by settings.flow(SettingsKeys.MAP_PALETTES).collectAsStateWithLifecycle(null)
    val toneOn by settings.flow(SettingsKeys.MAP_TONE_ON, DEFAULT_TONE_ON).collectAsStateWithLifecycle(DEFAULT_TONE_ON)
    val tone by settings.flow(SettingsKeys.MAP_TONE, 1f).collectAsStateWithLifecycle(1f)
    val current = remember(code) { MapLookModel.current(code) }
    val saved = remember(savedText) { MapLookModel.savedOf(savedText) }
    val savedAt = MapLookModel.savedIndex(saved, current)
    var sharing by rememberSaveable { mutableStateOf<Int?>(null) }
    val names = presetNames()
    val words = AddWords.of()
    fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { settings.edit(block) }
    }
    // a code handed to Atlas: added to the palettes and taken, once
    var handed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(args.code) {
        if (args.code != null && !handed) {
            handed = true
            toast(context, addFrom(settings, args.code, names, words))
        }
    }
    val look = when (themeName) {
        Theme.DAY.name -> stringResource(R.string.mine_theme_day)
        Theme.NIGHT.name -> stringResource(R.string.mine_theme_night)
        else -> stringResource(R.string.mine_settings_look_auto)
    }
    Box(Modifier.fillMaxSize()) {
        // the board's cards from y 98, 12 px apart; the page leaves room under the last for the button at the foot
        BoardPage(
            stringResource(R.string.mine_settings_look), onBack = { nav.back() }, bottomPadding = 90.dp,
            trailing = { BText(look, bt(12f, 600, 15f), SOFT) },
        ) {
            PresetsCard(current, saved.getOrNull(savedAt)?.name, names) { p -> edit { MapLookWrites.take(it, Palette(p)) } }
            ToneCard(toneOn, tone) { on, strength -> edit { MapLookWrites.tone(it, on, strength) } }
            SwitchesCard(
                guesses = !MapLookModel.on(current, PaletteFlags.NO_GUESSES),
                grass = MapLookModel.on(current, PaletteFlags.GRASS),
                follow = MapLookModel.on(current, PaletteFlags.MAPPED_FOLLOW),
                onGuesses = { on -> edit { MapLookWrites.switch(it, PaletteFlags.NO_GUESSES, !on) } },
                onGrass = { on -> edit { MapLookWrites.switch(it, PaletteFlags.GRASS, on) } },
                onFollow = { on -> edit { MapLookWrites.switch(it, PaletteFlags.MAPPED_FOLLOW, on) } },
            )
            PalettesCard(
                saved, savedAt, names,
                onTake = { i -> saved[i].palette?.let { p -> edit { MapLookWrites.take(it, p) } } },
                onShare = { i -> sharing = i },
                onAdd = {
                    scope.launch { toast(context, addFrom(settings, clipboardText(context), names, words)) }
                },
            )
        }
        // the main button: 56 high, 22 px from the foot, blue with the drop
        Row(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = foot(22f)).fillMaxWidth().height(56.dp)
                .bbox(BLUE, cr(28f)).clickable(role = Role.Button) { nav.go(Dest.ColourMap) },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PathIcon(LookIcons.DROP, 20f, WHITE)
            BText(stringResource(R.string.mine_look_colour), bt(16f, 800, 20f), WHITE)
        }
        val shown = sharing?.let { saved.getOrNull(it) }
        if (shown != null) {
            val index = sharing!!
            PaletteSheet(
                shown.name, shown.code,
                onClose = { sharing = null },
                onRename = { name -> edit { MapLookWrites.rename(it, index, name) } },
                onDelete = {
                    sharing = null
                    edit { MapLookWrites.remove(it, index) }
                },
            )
        }
    }
}

/** The words a code added (or not) is told with, read where a composable can. */
internal class AddWords(val added: String, val none: String, val mistyped: String, val newer: String, val not: String) {
    companion object {
        @Composable
        fun of() = AddWords(
            stringResource(R.string.mine_look_added), stringResource(R.string.mine_look_no_code), stringResource(R.string.mine_look_bad_code),
            stringResource(R.string.mine_look_newer_code), stringResource(R.string.mine_look_not_code),
        )
    }
}

/**
 * Adds the palette whose code [text] holds (a code alone, the QR's link, a shared message with its name before the code)
 * and the map takes it; what to tell the user.
 */
internal suspend fun addFrom(settings: SettingsStore, text: String?, names: Map<Preset, String>, words: AddWords): String {
    val code = MapLookModel.codeIn(text) ?: return words.none
    return when (val read = PaletteCode.read(code)) {
        is PaletteCode.Read.Ok -> {
            var name = ""
            settings.edit { name = MapLookWrites.add(it, read.palette, MapLookModel.nameIn(text), names.getValue(read.palette.base)) }
            words.added.format(name)
        }
        is PaletteCode.Read.Bad -> when (read.fault) {
            PaletteCode.Fault.CHECK, PaletteCode.Fault.LETTER -> words.mistyped
            PaletteCode.Fault.PRESET, PaletteCode.Fault.SLOT -> words.newer
            PaletteCode.Fault.FORM -> words.not
        }
    }
}

/** The clipboard's text, or null. */
private fun clipboardText(context: Context): String? = runCatching {
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/** The presets' names in the user's language. */
@Composable
internal fun presetNames(): Map<Preset, String> = Preset.entries.associateWith { stringResource(presetName(it)) }

internal fun presetName(p: Preset): Int = when (p) {
    Preset.TODAY -> R.string.mine_look_preset_today
    Preset.ALPHA_22 -> R.string.mine_look_preset_a22
    Preset.REAL -> R.string.mine_look_preset_real
    Preset.QUIET -> R.string.mine_look_preset_quiet
    Preset.SEPIA -> R.string.mine_look_preset_sepia
    Preset.INK -> R.string.mine_look_preset_ink
}

private fun presetSub(p: Preset): Int = when (p) {
    Preset.TODAY -> R.string.mine_look_preset_today_sub
    Preset.ALPHA_22 -> R.string.mine_look_preset_a22_sub
    Preset.REAL -> R.string.mine_look_preset_real_sub
    Preset.QUIET -> R.string.mine_look_preset_quiet_sub
    Preset.SEPIA -> R.string.mine_look_preset_sepia_sub
    Preset.INK -> R.string.mine_look_preset_ink_sub
}

/**
 * The board's presets card (radius 22, 12 px above, 14 under): PRESETS and what the map draws at the right, then the
 * glyphs 112 px wide and 10 apart, the one the map draws ringed, each with its name (13, 800) and its line (11, 500).
 */
@Composable
private fun PresetsCard(current: Palette, savedName: String?, names: Map<Preset, String>, onPick: (Preset) -> Unit) {
    val ringed = MapLookModel.ringed(current)
    val line = paletteLine(current, savedName, names)
    BoardCard(padding = PaddingValues(top = 12.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CardLabel(stringResource(R.string.mine_look_presets), Modifier.weight(1f))
            BText(line, bt(12f, 600, 15f), SOFT)
        }
        // 4 px above and below the glyphs for their ring, which the board's scrolling row cuts at the top
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (p in Preset.entries) {
                val name = names.getValue(p)
                Column(
                    Modifier.width(112.dp).selectable(selected = p == ringed, role = Role.RadioButton) { onPick(p) },
                ) {
                    Glyph(MapLookModel.glyph(Palette(p)), ring = p == ringed)
                    BText(name, bt(13f, 800, 16f), INK, Modifier.padding(top = 7.dp))
                    BText(stringResource(presetSub(p)), bt(11f, 500, 13.75f), SOFT, lines = 2)
                }
            }
        }
    }
}

/**
 * What the map draws, in a few words: the saved palette's name ([savedName]), else where an unsaved palette comes from
 * and how many colours it changes, else the preset's name ("Alpha 22 · the default" for the default look).
 */
@Composable
internal fun paletteLine(current: Palette, savedName: String?, names: Map<Preset, String>): String = when {
    savedName != null -> savedName
    current.changed > 0 -> changedLine(current, names)
    current == DEFAULT_PALETTE -> stringResource(R.string.mine_look_default, names.getValue(DEFAULT_PALETTE.base))
    else -> names.getValue(current.base)
}

/** "from Today · 6 colours changed", or "from Today" for a palette that changes no colour. */
@Composable
internal fun changedLine(p: Palette, names: Map<Preset, String>): String {
    val base = names.getValue(p.base)
    return if (p.changed == 0) stringResource(R.string.mine_look_from, base) else pluralStringResource(R.plurals.mine_look_changed, p.changed, p.changed, base)
}

/** The board's glyph paths (112 × 84), each with the index of its colour in MapLookModel.GLYPH. */
private val GLYPH_PATHS: List<Pair<Path, Int>> by lazy {
    listOf(
        "M0 50 C18 44 30 58 52 54 S90 40 112 46 L112 62 C92 58 70 70 50 68 S16 60 0 66 Z" to 1,
        "M62 0 L74 0 L44 84 L32 84 Z" to 2,
        "M78 66 C86 60 100 62 106 70 C110 78 98 84 86 84 C76 84 72 72 78 66 Z" to 3,
        "M6 8 h20 v14 h-20 z" to 4, "M6 22 h20 v8 h-20 z" to 5,
        "M30 6 h18 v12 h-18 z" to 6, "M30 18 h18 v9 h-18 z" to 7,
        "M80 10 h24 v16 h-24 z" to 8, "M80 26 h24 v8 h-24 z" to 9,
        "M8 72 C12 66 20 66 22 72 C24 78 12 80 8 72 Z" to 10,
    ).map { (d, i) -> PathParser().parsePathString(d).toPath() to i }
}

/**
 * A preset's glyph as the board draws it (112 × 84, radius 14): the bare ground, the water, a street, a lawn, three
 * roofs over their walls and a tree, in [colours] (MapLookModel.glyph); [ring] the board's 2 px ring 2 px out.
 */
@Composable
internal fun Glyph(colours: List<Int>, ring: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(112.dp, 84.dp)) {
        val k = size.width / 112f
        if (ring) drawRoundRect(Color(INK), Offset(-3 * k, -3 * k), Size(size.width + 6 * k, size.height + 6 * k), CornerRadius(17 * k), style = Stroke(2 * k))
        scale(k, k, pivot = Offset.Zero) {
            val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, 112f, 84f, CornerRadius(14f))) }
            clipPath(outline) {
                drawRect(opaque(colours[0]), Offset.Zero, Size(112f, 84f))
                for ((path, i) in GLYPH_PATHS) drawPath(path, opaque(colours[i]))
            }
        }
    }
}

/** An opaque colour of 0xRRGGBB. */
internal fun opaque(c: Int) = Color(0xFF000000.toInt() or c)

/**
 * The board's Tone card (12 px above, 14 under and at the sides): the orange tile with the sun, Tone and its line, the
 * switch; under them the strength from off to 2× (the board's track: 4 px, today's 100 % ticked, a 26 px thumb), faint
 * while Tone is off, and its value in Geist Mono between "off" and "2×". Dragging shows the strength as it moves and
 * keeps it when the finger lifts; setting one turns Tone on.
 */
@Composable
private fun ToneCard(on: Boolean, strength: Float, onChange: (Boolean, Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: if (on) strength else 0f
    val lit = on || dragging != null
    val percent = (shown * 100).roundToInt()
    val name = stringResource(R.string.mine_layers_tone)
    val value = when {
        !lit -> stringResource(R.string.mine_look_tone_off)
        percent == 100 -> stringResource(R.string.mine_layers_tone_today, percent)
        else -> stringResource(R.string.mine_layers_tone_value, percent)
    }
    val description = stringResource(R.string.mine_layers_tone_cd, percent)
    BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).bbox(0xFFE8622C, cr(10f)), contentAlignment = Alignment.Center) { PathIcon(LookIcons.SUN, 18f, WHITE) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BText(name, bt(16f, 800, 20f), INK)
                BText(stringResource(R.string.mine_look_tone_sub), bt(12f, 500, 15f), SOFT, lines = 3)
            }
            LookSwitch(on, { onChange(it, strength) }, name)
        }
        // 0 to 2 in steps of 5 %, so today's 1 is a step
        fun at(x: Float, width: Float) = (x / width * 40f).roundToInt().coerceIn(0, 40) / 20f
        BoxWithConstraints(
            Modifier.padding(start = 2.dp, end = 2.dp, top = 14.dp).fillMaxWidth().height(28.dp).alpha(if (lit) 1f else 0.4f)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(shown, 0f..2f, 39)
                    setProgress { v -> onChange(true, ((v * 20f).roundToInt() / 20f).coerceIn(0f, 2f)); true }
                }
                .pointerInput(Unit) { detectTapGestures { p -> onChange(true, at(p.x, size.width.toFloat())) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { dragging?.let { onChange(true, it) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = at(change.position.x, size.width.toFloat()) }
                },
        ) {
            val width = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                val bar = 4.dp.toPx()
                val top = 12.dp.toPx()
                drawRoundRect(Color(0xFFE6E8EE), Offset(0f, top), Size(size.width, bar), CornerRadius(bar / 2))
                drawRoundRect(Color(INK), Offset(0f, top), Size(size.width * shown / 2f, bar), CornerRadius(bar / 2))
                // today's 100 %
                drawRect(Color(FAINT), Offset(size.width / 2f, 8.dp.toPx()), Size(2.dp.toPx(), 12.dp.toPx()))
            }
            Box(
                Modifier.offset(x = width * (shown / 2f) - 13.dp, y = 1.dp).size(26.dp)
                    .bbox(WHITE, cr(13f), listOf(CssShadow(0f, 1f, 4f, 0f, 0x4D000000))),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BText(stringResource(R.string.mine_look_tone_off), bt(11f, 600, 14f), LABEL)
            BText(value, bt(11f, 600, 14f, MONO), INK)
            BText(stringResource(R.string.mine_look_tone_max), bt(11f, 600, 14f), LABEL)
        }
    }
}

/**
 * The board's card of switches (2 px above and below, 14 at the sides): Atlas's guesses with what they are on or off,
 * the bare ground as paper or grass, and whether mapped colours follow the palette; a hairline between the rows.
 */
@Composable
private fun SwitchesCard(
    guesses: Boolean,
    grass: Boolean,
    follow: Boolean,
    onGuesses: (Boolean) -> Unit,
    onGrass: (Boolean) -> Unit,
    onFollow: (Boolean) -> Unit,
) {
    BoardCard {
        val guessName = stringResource(R.string.mine_look_guesses)
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BText(guessName, bt(16f, 800, 20f), INK)
                BText(stringResource(if (guesses) R.string.mine_look_guesses_on else R.string.mine_look_guesses_off), bt(12f, 500, 15.6f), SOFT, lines = 4)
            }
            LookSwitch(guesses, onGuesses, guessName)
        }
        val groundName = stringResource(R.string.mine_look_ground)
        Row(Modifier.fillMaxWidth().height(54.dp).bbox(border = hairTop()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BText(groundName, bt(15f, 800, 19f), INK, Modifier.weight(1f))
            Row(Modifier.selectableGroup().semantics { contentDescription = groundName }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((label, isGrass) in listOf(stringResource(R.string.mine_look_paper) to false, stringResource(R.string.mine_look_grass) to true)) {
                    val picked = grass == isGrass
                    TextPill(
                        label, bt(12f, 800, 15f), if (picked) INK else HAIR, if (picked) WHITE else INK,
                        Modifier.selectable(selected = picked, role = Role.RadioButton) { onGrass(isGrass) },
                        h = 30f, padH = 12f,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).bbox(border = hairTop()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BText(stringResource(R.string.mine_look_follow), bt(15f, 800, 19f), INK)
                BText(stringResource(R.string.mine_look_follow_sub), bt(12f, 500, 15f), SOFT, lines = 3)
            }
            LookSwitch(follow, onFollow, stringResource(R.string.mine_look_follow_cd))
        }
    }
}

/**
 * The board's Your palettes card (10 px above, 4 under): each saved palette a 54 px row (its tile of four of its colours,
 * its name and where it comes from, the share button), the one the map draws with its tile ringed; then Add from a code.
 */
@Composable
private fun PalettesCard(
    saved: List<SavedPalette>,
    current: Int,
    names: Map<Preset, String>,
    onTake: (Int) -> Unit,
    onShare: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
        CardLabel(stringResource(R.string.mine_look_yours))
        saved.forEachIndexed { i, s ->
            val p = s.palette ?: return@forEachIndexed
            Row(
                Modifier.fillMaxWidth().height(54.dp).then(if (i > 0) Modifier.bbox(border = hairTop()) else Modifier)
                    .selectable(selected = i == current, role = Role.RadioButton) { onTake(i) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PaletteTile(p, ring = i == current)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BText(s.name, bt(15f, 800, 19f), INK)
                    BText(changedLine(p, names), bt(12f, 500, 15f), SOFT)
                }
                val share = stringResource(R.string.mine_look_share_cd, s.name)
                Box(
                    Modifier.size(40.dp).bbox(HAIR, cr(20f)).clickable(role = Role.Button) { onShare(i) }.semantics { contentDescription = share },
                    contentAlignment = Alignment.Center,
                ) { PathIcon(LookIcons.SHARE, 18f, INK) }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(50.dp).then(if (saved.isNotEmpty()) Modifier.bbox(border = hairTop()) else Modifier)
                .clickable(role = Role.Button, onClick = onAdd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(34.dp).bbox(HAIR, cr(10f)), contentAlignment = Alignment.Center) { PathIcon(LookIcons.PLUS, 18f, INK) }
            BText(stringResource(R.string.mine_look_add), bt(15f, 800, 19f), INK, Modifier.weight(1f))
            BText(stringResource(R.string.mine_look_add_how), bt(12f, 600, 15f), SOFT)
        }
    }
}

/** A saved palette's tile (34 px, radius 10): its bare ground, red tiles, lawn and water, two by two; [ring] as a glyph's. */
@Composable
private fun PaletteTile(p: Palette, ring: Boolean) {
    val colours = MapLookModel.TILE.map { opaque(p.colour(it)) }
    Canvas(Modifier.size(34.dp)) {
        if (ring) drawRoundRect(Color(INK), Offset(-3.dp.toPx(), -3.dp.toPx()), Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius(13.dp.toPx()), style = Stroke(2.dp.toPx()))
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(10.dp.toPx()))) }
        clipPath(outline) {
            val half = Size(size.width / 2f, size.height / 2f)
            drawRect(colours[0], Offset.Zero, half)
            drawRect(colours[1], Offset(half.width, 0f), half)
            drawRect(colours[2], Offset(0f, half.height), half)
            drawRect(colours[3], Offset(half.width, half.height), half)
        }
    }
}

/** The board's larger switch (52 × 32, radius 16): green when on, grey when off, the white 26 px knob 3 px in. */
@Composable
internal fun LookSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(52.dp, 32.dp).bbox(if (checked) 0xFF1E9E5A else 0xFFC9CDD6, cr(16f), clip = false)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "on" else "off"
            },
    ) {
        Box(Modifier.padding(start = if (checked) 23.dp else 3.dp, top = 3.dp).size(26.dp).bbox(WHITE, cr(13f), listOf(CssShadow(0f, 1f, 3f, 0f, 0x40000000))))
    }
}

/** The boards' blue of a main button. */
internal const val BLUE: app.atlas.ui.kit.Argb = 0xFF2E5BFF

/** The boards' icons of the Map look and Colour the map: their paths on a 24 × 24 grid, stroked 2, round caps and joins. */
internal object LookIcons {
    val SUN = listOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val SHARE = listOf("M12 3v12M7 8l5-5 5 5M5 14v5a2 2 0 002 2h10a2 2 0 002-2v-5")
    val PLUS = listOf("M12 5v14M5 12h14")
    val DROP = listOf("M12 3c3 4 6 7.2 6 10.5A6 6 0 016 13.5C6 10.2 9 7 12 3z")
    val CROSS = listOf("M6 6l12 12M18 6L6 18")
    val TRASH = listOf("M4 7h16", "M10 11v6M14 11v6", "M6 7l1 12a2 2 0 002 2h6a2 2 0 002-2l1-12", "M9 7V4h6v3")
    val UNDO = listOf("M9 14L4 9l5-5", "M4 9h10a6 6 0 010 12h-3")
    val EYE = listOf("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
}

/** A board icon: [paths] on a 24 × 24 grid in a [size] px box, stroked [width] in [colour], round caps and joins. */
@Composable
internal fun PathIcon(paths: List<String>, size: Float, colour: app.atlas.ui.kit.Argb, width: Float = 2f, modifier: Modifier = Modifier) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size.dp)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            for (p in parsed) drawPath(p, Color(colour), style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
