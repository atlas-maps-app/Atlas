package app.atlas.ui.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.Theme
import app.atlas.mapview.palette.Family
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteFace
import app.atlas.mapview.palette.Slot
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Colour the map (the Colour the map board, `Colour.dc.html`): the live map in the palette as it changes, under the
 * board's row (leave without saving, the title with where the palette comes from, undo, save) and over its sheet: the
 * groups of colours as tabs, the colour picked with how the light draws it, its lightness, colour and hue, its hex and
 * OKLCH, whether its whole group follows, and the group's colours, each changed one dotted. Holding "before" shows the
 * preset's colours. Saving keeps the palette as the user's (MapLookWrites.save); leaving gives the map back its own.
 */
@Composable
fun ColourMapScreen(args: Dest.ColourMap, nav: Navigator) {
    val services = LocalServices.current
    val settings = services.settings
    val scope = rememberCoroutineScope()
    val here = rememberHere()
    val themeName by settings.flow(SettingsKeys.MAP_THEME, Theme.AUTO.name).collectAsStateWithLifecycle(Theme.AUTO.name)
    val savedLayers by settings.flow(SettingsKeys.MAP_LAYERS).collectAsStateWithLifecycle(null)
    val threeD by settings.flow(SettingsKeys.MAP_3D, true).collectAsStateWithLifecycle(true)
    val language by settings.flow(SettingsKeys.NAME_LANGUAGE).collectAsStateWithLifecycle(null)
    val theme = runCatching { Theme.valueOf(themeName) }.getOrDefault(Theme.AUTO)
    val style = MapStyle(theme = theme, layers = LayersModel.layers(savedLayers, threeD, MapStyle().layers), language = language, calm = true)
    val map = remember(here) { MapState(CameraPosition(here, 16.4, 0.0, 45.0)) }
    val names = presetNames()
    // what the map drew as the screen opened, and the saved palette it was (its code), read once
    var original by remember { mutableStateOf<Palette?>(null) }
    var from by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(Palette()) }
    val undo = remember { mutableStateListOf<Palette>() }
    var family by remember { mutableStateOf(Family.GROUND) }
    var slot by remember { mutableStateOf(Slot.PAPER) }
    var group by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    // what a tap met and where, a building's face or the ground: its colour picked, its callout over the map until the
    // map moves or the sheet picks another
    var tapped by remember { mutableStateOf<Tapped?>(null) }
    LaunchedEffect(Unit) {
        val prefs = settings.data.first()
        val now = MapLookModel.current(prefs[SettingsKeys.MAP_PALETTE])
        val list = MapLookModel.savedOf(prefs[SettingsKeys.MAP_PALETTES])
        from = list.getOrNull(MapLookModel.savedIndex(list, now))?.code
        original = now
        working = now
    }
    // the map draws the palette as it changes, or the preset's colours while "before" is held
    LaunchedEffect(working, before, original) {
        if (original != null) MapEngine.palette(if (before) working.copy(colours = emptyMap()) else working)
    }
    val restore by rememberUpdatedState(original)
    DisposableEffect(Unit) {
        onDispose { if (!saved) restore?.let { MapEngine.palette(it) } }
    }
    fun leave() {
        original?.let { MapEngine.palette(it) }
        nav.back()
    }
    BackHandler { leave() }
    fun change(next: Palette, keep: Boolean = true) {
        if (next == working) return
        if (keep) undo += working
        working = next
    }
    fun paint(colour: Int, keep: Boolean = true) =
        change(if (group) ColourMapModel.withGroup(working, slot, colour) else ColourMapModel.withColour(working, slot, colour), keep)

    Box(Modifier.fillMaxSize()) {
        app.atlas.mapview.StageMap(
            state = map,
            modifier = Modifier.fillMaxSize(),
            background = opaque(working.colour(Slot.PAPER)),
            style = style,
            contentPadding = PaddingValues(top = 130.dp, bottom = 424.dp),
            onTap = { tapped = null },
            onTapBuilding = { pick, at ->
                val face = PaletteFace.of(pick.slot, working.switches)
                tapped = Tapped.Face(pick.roof, face, pick.rgb, at)
                face.slot?.let {
                    family = it.family
                    slot = it
                }
            },
            onTapGround = { pick, at ->
                // the slot that paints the ground there; a colour no slot moves (sand, a cycleway's red) picks nothing
                val painted = Slot.ofCode(pick.slot)
                tapped = painted?.let { Tapped.Ground(it, at) }
                painted?.let {
                    family = it.family
                    slot = it
                }
            },
            onCameraMove = { _, byUser -> if (byUser) tapped = null },
        )
        tapped?.let { TapMarker(it.at) }
        // the board's wash over the map's top: the page's grey at 85 % fading out over 130 px
        Box(Modifier.fillMaxWidth().height(130.dp + shift()).bbox(grad = listOf(cssLinear(180f, 0f to 0xD9F1F3F6, 1f to 0x00F1F3F6))))
        val chrome = listOf(CssShadow(0f, 1f, 6f, 0f, 0x1F000000))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp + shift()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val leaveCd = stringResource(R.string.mine_colour_leave)
            Box(
                Modifier.size(44.dp).bbox(WHITE, cr(22f), chrome).clickable(role = Role.Button) { leave() }.semantics { contentDescription = leaveCd },
                contentAlignment = Alignment.Center,
            ) { PathIcon(LookIcons.CROSS, 18f, INK) }
            Column(Modifier.weight(1f).height(44.dp).bbox(WHITE, cr(22f), chrome).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                BText(stringResource(R.string.mine_look_colour), bt(15f, 800, 16.5f), INK)
                BText(changedLine(working, names), bt(11.5f, 600, 14f), SOFT)
            }
            val undoCd = stringResource(R.string.mine_colour_undo)
            Box(
                Modifier.size(44.dp).bbox(WHITE, cr(22f), chrome).alpha(if (undo.isEmpty()) 0.45f else 1f)
                    .clickable(enabled = undo.isNotEmpty(), role = Role.Button) { working = undo.removeAt(undo.lastIndex) }
                    .semantics { contentDescription = undoCd },
                contentAlignment = Alignment.Center,
            ) { PathIcon(LookIcons.UNDO, 18f, INK) }
            TextPill(stringResource(R.string.mine_colour_save), bt(14f, 800, 18f), BLUE, WHITE, h = 44f, padH = 16f) {
                val base = names.getValue(working.base)
                val kept = working
                saved = true
                scope.launch {
                    settings.edit { MapLookWrites.save(it, kept, from, base) }
                    nav.back()
                }
            }
        }
        tapped?.let { t -> TapCallout(t, t.colour(working)) }
        val sheetHeight = 412.dp
        // Hold: before, 15 px over the sheet at the right
        val beforeCd = stringResource(R.string.mine_colour_before_cd)
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = foot(12f) + sheetHeight + 15.dp).height(40.dp)
                .bbox(WHITE, cr(20f), listOf(CssShadow(0f, 1f, 6f, 0f, 0x26000000)))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        before = true
                        tryAwaitRelease()
                        before = false
                    })
                }
                .semantics { contentDescription = beforeCd }
                .padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PathIcon(LookIcons.EYE, 17f, INK)
            BText(stringResource(R.string.mine_colour_before), bt(12.5f, 800, 16f), INK)
        }
        // the sheet: 12 px from the edges, 412 high, radius 30
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = foot(12f)).fillMaxWidth().height(sheetHeight)
                .bbox(WHITE, cr(30f), listOf(CssShadow(0f, -2f, 18f, 0f, 0x24000000))),
        ) {
            Box(Modifier.padding(top = 8.dp, bottom = 6.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).bbox(0xFFD5D8DF, cr(2f)))
            val groupsCd = stringResource(R.string.mine_colour_groups_cd)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).selectableGroup()
                    .semantics { contentDescription = groupsCd },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (f in Family.entries) {
                    val on = f == family
                    TextPill(
                        stringResource(groupName(f)), bt(12.5f, 800, 16f), if (on) INK else HAIR, if (on) WHITE else INK,
                        Modifier.selectable(selected = on, role = Role.Tab) {
                            family = f
                            slot = ColourMapModel.slots(f).first()
                            tapped = null
                        },
                        h = 34f, padH = 12f,
                    )
                }
            }
            if (original != null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Entry(working, slot, onReset = { change(ColourMapModel.reset(working, slot)) })
                    LitRow(working, slot)
                    Sliders(working.colour(slot), onDrag = { c -> paint(c, keep = false) }, onStart = { undo += working }, onSet = { c -> paint(c) })
                    HexRow(working.colour(slot), family, group, onHex = { c -> paint(c) }, onGroup = { group = !group })
                    FamilyRow(working, family, slot) { s ->
                        slot = s
                        tapped = null
                    }
                }
            }
        }
    }
}

/** What a tap met, and where (px). */
private sealed interface Tapped {
    val at: Offset

    /** The colour its callout shows in [p]. */
    fun colour(p: Palette): Int

    /** A building's face ([roof], else a wall), how the palette paints it, and its own colour. */
    data class Face(val roof: Boolean, val face: PaletteFace, val rgb: Int, override val at: Offset) : Tapped {
        override fun colour(p: Palette): Int = face.slot?.let { p.colour(it) } ?: rgb
    }

    /** The ground, and the slot that paints it there. */
    data class Ground(val slot: Slot, override val at: Offset) : Tapped {
        override fun colour(p: Palette): Int = p.colour(slot)
    }
}

/** The board's mark of a tap on the map: a ring of 17 px, 2 of ink over 4 of white, and a dot of 3. */
@Composable
private fun TapMarker(at: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Color.White, 17.dp.toPx(), at, style = Stroke(4.dp.toPx()))
        drawCircle(Color(INK), 17.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        drawCircle(Color(INK), 3.dp.toPx(), at)
    }
}

/**
 * The board's callout of a tap: 32 high, radius 16, white with its shadow, the colour (20 px, [colour]) and what the
 * tap met (12.5 px, 800), its left 38 px left of the tap and its foot 25 px over it (under it where the top row would
 * cover it), kept 12 px within the screen.
 */
@Composable
private fun TapCallout(t: Tapped, colour: Int) {
    val line = tapLine(t)
    val top = shift()
    Layout(
        content = {
            Row(
                Modifier.height(32.dp).bbox(WHITE, cr(16f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x2E000000)))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = 6.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).bbox(0xFF000000 or colour.toLong(), cr(10f)))
                BText(line, bt(12.5f, 800, 16f), INK, lines = 1)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = 12.dp.roundToPx()
        val pill = measurables.first().measure(Constraints(maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (t.at.x - 38.dp.toPx()).roundToInt().coerceIn(margin, (constraints.maxWidth - margin - pill.width).coerceAtLeast(margin))
            val over = (t.at.y - 57.dp.toPx()).roundToInt()
            val y = if (over < (96.dp + top).roundToPx()) (t.at.y + 25.dp.toPx()).roundToInt() else over
            pill.place(x, y)
        }
    }
}

/** What a tap met, as the callout says it ("Red tiles · a guessed roof", "Water · canals, rivers and lakes in sun"). */
@Composable
private fun tapLine(t: Tapped): String = when (t) {
    is Tapped.Ground -> "${slotName(t.slot)} · ${stringResource(slotSub(t.slot))}"
    is Tapped.Face -> faceLine(t)
}

/** A building's face a tap met, as the callout says it ("Red tiles · a guessed roof"). */
@Composable
private fun faceLine(t: Tapped.Face): String {
    val name = t.face.slot?.let { slotName(it) }.orEmpty()
    return when (t.face.colour) {
        PaletteFace.How.GUESSED -> stringResource(if (t.roof) R.string.mine_colour_tap_guessed_roof else R.string.mine_colour_tap_guessed_wall, name)
        PaletteFace.How.GUESSED_PLAIN -> stringResource(if (t.roof) R.string.mine_colour_tap_plain_roof else R.string.mine_colour_tap_plain_wall, name)
        PaletteFace.How.MAPPED -> stringResource(if (t.roof) R.string.mine_colour_tap_mapped_roof else R.string.mine_colour_tap_mapped_wall, name)
        PaletteFace.How.MAPPED_OWN -> stringResource(if (t.roof) R.string.mine_colour_tap_own_roof else R.string.mine_colour_tap_own_wall)
        PaletteFace.How.NONE -> stringResource(if (t.roof) R.string.mine_colour_tap_none_roof else R.string.mine_colour_tap_none_wall)
    }
}

/** The picked colour (the board's entry, 12 px down, 16 in): its swatch (52, radius 14), name, what it paints, Reset. */
@Composable
private fun Entry(p: Palette, slot: Slot, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).bbox(opaque(p.colour(slot)).argbLong(), cr(14f), listOf(CssShadow(0f, 0f, 0f, 1f, 0x14000000, inset = true))))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BText(slotName(slot), bt(18f, 900, 22f), INK)
            BText(stringResource(slotSub(slot)), bt(12f, 500, 15f), SOFT)
        }
        TextPill(stringResource(R.string.mine_colour_reset), bt(12f, 800, 15f), HAIR, INK, h = 30f, padH = 11f, onClick = onReset)
    }
}

/** How the light draws the picked colour (the board's three tiles: in sun, in shade, at night), the palette's sun and shade. */
@Composable
private fun LitRow(p: Palette, slot: Slot) {
    val c = p.colour(slot)
    val sun = p.colour(Slot.SUN)
    val shade = p.colour(Slot.SHADE)
    val description = stringResource(R.string.mine_colour_lit_cd)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, lit) in listOf(
            R.string.mine_colour_sun to ColourMapModel.light(c, 0.62, false, sun, shade),
            R.string.mine_colour_shade to ColourMapModel.light(c, 0.0, false, sun, shade),
            R.string.mine_colour_night to ColourMapModel.light(c, 0.0, true, sun, shade),
        )) {
            Box(Modifier.weight(1f).height(30.dp).bbox(opaque(lit).argbLong(), cr(10f)), contentAlignment = Alignment.Center) {
                BText(stringResource(name), bt(11f, 800, 14f), if (ColourMapModel.darkText(lit)) INK else WHITE)
            }
        }
    }
}

/**
 * The board's sliders of the picked colour: its lightness (0.15 to 0.97), colour (chroma, 0 to 0.25) and hue, each on a
 * track of the colours it reaches, the knob in the colour with a white ring. A drag paints the map as it moves
 * ([onDrag], after [onStart]); a tap sets it ([onSet]). The hue and chroma are held while the lightness moves, so a grey
 * keeps the hue it was given.
 */
@Composable
private fun Sliders(colour: Int, onDrag: (Int) -> Unit, onStart: () -> Unit, onSet: (Int) -> Unit) {
    var held by remember { mutableStateOf<ColourMapModel.Lch?>(null) }
    // a colour from outside (another slot, undo, a hex) is read afresh; one the sliders made keeps their hue
    val lch = held?.takeIf { ColourMapModel.rgb(it) == colour } ?: ColourMapModel.lch(colour)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
        val rows = listOf(
            Triple(R.string.mine_colour_lightness, (lch.l - ColourMapModel.L_MIN) / (ColourMapModel.L_MAX - ColourMapModel.L_MIN), (lch.l * 100).roundToInt().toString()),
            Triple(R.string.mine_colour_chroma, lch.c / ColourMapModel.C_MAX, "%.2f".format(java.util.Locale.ROOT, lch.c)),
            Triple(R.string.mine_colour_hue, lch.h / 360f, "${lch.h.roundToInt()}°"),
        )
        rows.forEachIndexed { i, (name, at, value) ->
            fun at(t: Float): ColourMapModel.Lch {
                val u = t.coerceIn(0f, 1f)
                return when (i) {
                    0 -> lch.copy(l = ColourMapModel.L_MIN + u * (ColourMapModel.L_MAX - ColourMapModel.L_MIN))
                    1 -> lch.copy(c = u * ColourMapModel.C_MAX)
                    else -> lch.copy(h = u * 360f)
                }
            }
            // the track's seven stops, as the board's gradient (the hue's at a chroma of at least 0.06)
            val stops = (0..6).map { k ->
                val t = k / 6f
                val x = when (i) {
                    0 -> at(t)
                    1 -> at(t)
                    else -> at(t).let { it.copy(c = maxOf(it.c, 0.06f)) }
                }
                opaque(ColourMapModel.rgb(x))
            }
            val label = stringResource(name)
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BText(label, bt(12f, 700, 15f), 0xFF464C5A, Modifier.width(66.dp))
                Track(
                    stops, at.coerceIn(0f, 1f), opaque(colour), "$label $value", Modifier.weight(1f),
                    onStart = onStart,
                    onDrag = { t -> at(t).also { held = it }.let { onDrag(ColourMapModel.rgb(it)) } },
                    onSet = { t -> at(t).also { held = it }.let { onSet(ColourMapModel.rgb(it)) } },
                )
                BText(value, bt(12f, 700, 15f, MONO, align = TextAlign.End), INK, Modifier.width(44.dp))
            }
        }
    }
}

/** One slider's track (16 high, radius 8, its colours) and knob (26, the colour, a 3 px white ring), a drag or a tap along it. */
@Composable
private fun Track(
    stops: List<Color>,
    at: Float,
    knob: Color,
    description: String,
    modifier: Modifier,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onSet: (Float) -> Unit,
) {
    BoxWithConstraints(
        modifier.height(26.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(at, 0f..1f)
                setProgress { v -> onSet(v.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) { detectTapGestures { p -> onSet(p.x / size.width) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { onStart() }) { change, _ -> onDrag(change.position.x / size.width) }
            },
    ) {
        val width = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val h = 16.dp.toPx()
            val top = (size.height - h) / 2
            drawRoundRect(Brush.horizontalGradient(stops), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(h / 2))
            // the track's inset 1 px line at 6 % black
            val px = 1.dp.toPx()
            drawRoundRect(Color(0x0F000000), Offset(px / 2, top + px / 2), androidx.compose.ui.geometry.Size(size.width - px, h - px), CornerRadius(h / 2 - px / 2), style = Stroke(px))
        }
        Box(
            Modifier.offset(x = width * at - 13.dp).size(26.dp)
                .bbox(knob.argbLong(), cr(13f), listOf(CssShadow(0f, 1f, 4f, 0f, 0x59000000)), ring(3f, WHITE)),
        )
    }
}

/** The picked colour's hex (typed over to set it), its OKLCH, and whether its whole group follows it (dark when on). */
@Composable
private fun HexRow(colour: Int, family: Family, group: Boolean, onHex: (Int) -> Unit, onGroup: () -> Unit) {
    val focus = LocalFocusManager.current
    var typed by remember(colour) { mutableStateOf(ColourMapModel.hex(colour)) }
    val lch = ColourMapModel.lch(colour)
    val hexCd = stringResource(R.string.mine_colour_hex_cd)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.height(30.dp).bbox(PAGE, cr(10f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            BasicTextField(
                typed, { typed = it.take(7) },
                Modifier.width(66.dp).semantics { contentDescription = hexCd },
                textStyle = bt(13f, 700, 16f, MONO).copy(color = Color(INK)),
                singleLine = true,
                cursorBrush = SolidColor(Color(INK)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    ColourMapModel.hexOf(typed)?.let(onHex) ?: run { typed = ColourMapModel.hex(colour) }
                    focus.clearFocus()
                }),
            )
        }
        BText(
            "OKLCH %.2f %.3f %d°".format(java.util.Locale.ROOT, lch.l, lch.c, lch.h.roundToInt()), bt(12f, 600, 15f), SOFT,
            Modifier.weight(1f),
        )
        TextPill(
            stringResource(allName(family)), bt(12f, 800, 15f), if (group) INK else HAIR, if (group) WHITE else INK,
            Modifier.selectable(selected = group, role = Role.Switch) { onGroup() }, h = 30f, padH = 11f,
        )
    }
}

/** The group's colours (the board's swatches: 34 px, radius 10, 6 apart), the picked one ringed, each changed one dotted. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FamilyRow(p: Palette, family: Family, slot: Slot, onPick: (Slot) -> Unit) {
    val slots = ColourMapModel.slots(family)
    val label = stringResource(R.string.mine_colour_family, stringResource(familyLabel(family)), slots.size)
    CardLabel(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (s in slots) {
            val name = slotName(s)
            val picked = s == slot
            val dot = ColourMapModel.changed(p, s)
            Canvas(
                Modifier.size(34.dp).selectable(selected = picked, role = Role.RadioButton) { onPick(s) }.semantics { contentDescription = name },
            ) {
                val r = 10.dp.toPx()
                if (picked) drawRoundRect(Color(INK), Offset(-3.dp.toPx(), -3.dp.toPx()), androidx.compose.ui.geometry.Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius(r + 3.dp.toPx()), style = Stroke(2.dp.toPx()))
                drawRoundRect(opaque(p.colour(s)), cornerRadius = CornerRadius(r))
                val px = 1.dp.toPx()
                drawRoundRect(Color(0x14000000), Offset(px / 2, px / 2), androidx.compose.ui.geometry.Size(size.width - px, size.height - px), CornerRadius(r - px / 2), style = Stroke(px))
                if (dot) {
                    // the board's dot: 9 px of blue with a 2 px white ring, 3 px out at the top right
                    val c = Offset(size.width + 3.dp.toPx() - 4.5.dp.toPx(), -3.dp.toPx() + 4.5.dp.toPx())
                    drawCircle(Color.White, 4.5.dp.toPx(), c)
                    drawCircle(Color(BLUE), 2.5.dp.toPx(), c)
                }
            }
        }
    }
}

private fun groupName(f: Family): Int = when (f) {
    Family.GROUND -> R.string.mine_colour_g_ground
    Family.WATER -> R.string.mine_colour_g_water
    Family.ROADS -> R.string.mine_colour_g_roads
    Family.WALLS -> R.string.mine_colour_g_walls
    Family.ROOFS -> R.string.mine_colour_g_roofs
    Family.TREES -> R.string.mine_colour_g_trees
    Family.NIGHT -> R.string.mine_colour_g_night
}

private fun familyLabel(f: Family): Int = when (f) {
    Family.GROUND -> R.string.mine_colour_l_ground
    Family.WATER -> R.string.mine_colour_l_water
    Family.ROADS -> R.string.mine_colour_l_roads
    Family.WALLS -> R.string.mine_colour_l_walls
    Family.ROOFS -> R.string.mine_colour_l_roofs
    Family.TREES -> R.string.mine_colour_l_trees
    Family.NIGHT -> R.string.mine_colour_l_night
}

private fun allName(f: Family): Int = when (f) {
    Family.GROUND -> R.string.mine_colour_all_ground
    Family.WATER -> R.string.mine_colour_all_water
    Family.ROADS -> R.string.mine_colour_all_roads
    Family.WALLS -> R.string.mine_colour_all_walls
    Family.ROOFS -> R.string.mine_colour_all_roofs
    Family.TREES -> R.string.mine_colour_all_trees
    Family.NIGHT -> R.string.mine_colour_all_night
}

/** A colour's name in the user's language ("Red tiles", "Old brick 2"). */
@Composable
internal fun slotName(s: Slot): String {
    val (id, n) = slotNameId(s)
    return if (n == null) stringResource(id) else stringResource(id, n)
}

private fun slotNameId(s: Slot): Pair<Int, Int?> = when (s) {
    Slot.PAPER -> R.string.mine_slot_paper to null
    Slot.LAWN -> R.string.mine_slot_lawn to null
    Slot.WOODS -> R.string.mine_slot_woods to null
    Slot.SQUARES -> R.string.mine_slot_squares to null
    Slot.PARKING -> R.string.mine_slot_parking to null
    Slot.PLAYGROUND -> R.string.mine_slot_playground to null
    Slot.MEMORIAL -> R.string.mine_slot_memorial to null
    Slot.BRIDGE_DECK -> R.string.mine_slot_bridge_deck to null
    Slot.WATER -> R.string.mine_slot_water to null
    Slot.WATER_SKY -> R.string.mine_slot_water_sky to null
    Slot.WATER_DEEP -> R.string.mine_slot_water_deep to null
    Slot.QUAY_WALLS -> R.string.mine_slot_quay_walls to null
    Slot.STREET -> R.string.mine_slot_street to null
    Slot.ASPHALT -> R.string.mine_slot_asphalt to null
    Slot.PAVING_STONES -> R.string.mine_slot_paving_stones to null
    Slot.SETTS -> R.string.mine_slot_setts to null
    Slot.CONCRETE_PAVING -> R.string.mine_slot_concrete_paving to null
    Slot.BRICK_PAVING -> R.string.mine_slot_brick_paving to null
    Slot.BOARDWALK -> R.string.mine_slot_boardwalk to null
    Slot.OLD_BRICK_1 -> R.string.mine_slot_old_brick to 1
    Slot.OLD_BRICK_2 -> R.string.mine_slot_old_brick to 2
    Slot.OLD_BRICK_3 -> R.string.mine_slot_old_brick to 3
    Slot.OLD_BRICK_4 -> R.string.mine_slot_old_brick to 4
    Slot.OLD_BRICK_5 -> R.string.mine_slot_old_brick to 5
    Slot.OLD_BRICK_6 -> R.string.mine_slot_old_brick to 6
    Slot.PLASTER_1 -> R.string.mine_slot_plaster to 1
    Slot.PLASTER_2 -> R.string.mine_slot_plaster to 2
    Slot.PLASTER_3 -> R.string.mine_slot_plaster to 3
    Slot.NEW_BRICK_1 -> R.string.mine_slot_new_brick to 1
    Slot.NEW_BRICK_2 -> R.string.mine_slot_new_brick to 2
    Slot.NEW_BRICK_3 -> R.string.mine_slot_new_brick to 3
    Slot.NEW_BRICK_4 -> R.string.mine_slot_new_brick to 4
    Slot.NEW_BRICK_5 -> R.string.mine_slot_new_brick to 5
    Slot.LIMESTONE -> R.string.mine_slot_limestone to null
    Slot.CONCRETE -> R.string.mine_slot_concrete to null
    Slot.RED_TILES -> R.string.mine_slot_red_tiles to null
    Slot.DARK_TILES_1 -> R.string.mine_slot_dark_tiles to 1
    Slot.DARK_TILES_2 -> R.string.mine_slot_dark_tiles to 2
    Slot.DARK_TILES_3 -> R.string.mine_slot_dark_tiles to 3
    Slot.FLAT_GREY_1 -> R.string.mine_slot_flat_grey to 1
    Slot.FLAT_GREY_2 -> R.string.mine_slot_flat_grey to 2
    Slot.FLAT_GREY_3 -> R.string.mine_slot_flat_grey to 3
    Slot.BITUMEN -> R.string.mine_slot_bitumen to null
    Slot.GRAVEL -> R.string.mine_slot_gravel to null
    Slot.SLATE -> R.string.mine_slot_slate to null
    Slot.ZINC -> R.string.mine_slot_zinc to null
    Slot.LEAD -> R.string.mine_slot_lead to null
    Slot.LEAVES -> R.string.mine_slot_leaves to null
    Slot.EVERGREEN -> R.string.mine_slot_evergreen to null
    Slot.BARK -> R.string.mine_slot_bark to null
    Slot.BENCHES -> R.string.mine_slot_benches to null
    Slot.LAMP_POSTS -> R.string.mine_slot_lamp_posts to null
    Slot.BOLLARDS -> R.string.mine_slot_bollards to null
    Slot.TURBINES -> R.string.mine_slot_turbines to null
    Slot.PYLONS -> R.string.mine_slot_pylons to null
    Slot.NIGHT_GROUND -> R.string.mine_slot_night_ground to null
    Slot.NIGHT_WATER -> R.string.mine_slot_night_water to null
    Slot.LIT_WINDOWS -> R.string.mine_slot_lit_windows to null
    Slot.SKY -> R.string.mine_slot_sky to null
    Slot.SUN -> R.string.mine_slot_sun to null
    Slot.SHADE -> R.string.mine_slot_shade to null
    Slot.SHADOWS -> R.string.mine_slot_shadows to null
    Slot.HAZE -> R.string.mine_slot_haze to null
}

/** What a colour paints, as the board's entries say ("one gabled canal house in four"). */
internal fun slotSub(s: Slot): Int = when (s) {
    Slot.PAPER -> R.string.mine_slot_paper_sub
    Slot.LAWN -> R.string.mine_slot_lawn_sub
    Slot.WOODS -> R.string.mine_slot_woods_sub
    Slot.SQUARES -> R.string.mine_slot_squares_sub
    Slot.PARKING -> R.string.mine_slot_parking_sub
    Slot.PLAYGROUND -> R.string.mine_slot_playground_sub
    Slot.MEMORIAL -> R.string.mine_slot_memorial_sub
    Slot.BRIDGE_DECK -> R.string.mine_slot_bridge_deck_sub
    Slot.WATER -> R.string.mine_slot_water_sub
    Slot.WATER_SKY -> R.string.mine_slot_water_sky_sub
    Slot.WATER_DEEP -> R.string.mine_slot_water_deep_sub
    Slot.QUAY_WALLS -> R.string.mine_slot_quay_walls_sub
    Slot.STREET -> R.string.mine_slot_street_sub
    Slot.ASPHALT -> R.string.mine_slot_asphalt_sub
    Slot.PAVING_STONES -> R.string.mine_slot_paving_stones_sub
    Slot.SETTS -> R.string.mine_slot_setts_sub
    Slot.CONCRETE_PAVING -> R.string.mine_slot_concrete_paving_sub
    Slot.BRICK_PAVING -> R.string.mine_slot_brick_paving_sub
    Slot.BOARDWALK -> R.string.mine_slot_boardwalk_sub
    Slot.OLD_BRICK_1, Slot.OLD_BRICK_2, Slot.OLD_BRICK_3, Slot.OLD_BRICK_4, Slot.OLD_BRICK_5, Slot.OLD_BRICK_6 -> R.string.mine_slot_old_brick_sub
    Slot.PLASTER_1 -> R.string.mine_slot_plaster_1_sub
    Slot.PLASTER_2, Slot.PLASTER_3 -> R.string.mine_slot_plaster_sub
    Slot.NEW_BRICK_1, Slot.NEW_BRICK_2, Slot.NEW_BRICK_3, Slot.NEW_BRICK_4, Slot.NEW_BRICK_5 -> R.string.mine_slot_new_brick_sub
    Slot.LIMESTONE -> R.string.mine_slot_limestone_sub
    Slot.CONCRETE -> R.string.mine_slot_concrete_sub
    Slot.RED_TILES -> R.string.mine_slot_red_tiles_sub
    Slot.DARK_TILES_1, Slot.DARK_TILES_2, Slot.DARK_TILES_3 -> R.string.mine_slot_dark_tiles_sub
    Slot.FLAT_GREY_1, Slot.FLAT_GREY_2, Slot.FLAT_GREY_3 -> R.string.mine_slot_flat_grey_sub
    Slot.BITUMEN -> R.string.mine_slot_bitumen_sub
    Slot.GRAVEL -> R.string.mine_slot_gravel_sub
    Slot.SLATE -> R.string.mine_slot_slate_sub
    Slot.ZINC -> R.string.mine_slot_zinc_sub
    Slot.LEAD -> R.string.mine_slot_lead_sub
    Slot.LEAVES -> R.string.mine_slot_leaves_sub
    Slot.EVERGREEN -> R.string.mine_slot_evergreen_sub
    Slot.BARK -> R.string.mine_slot_bark_sub
    Slot.BENCHES -> R.string.mine_slot_benches_sub
    Slot.LAMP_POSTS -> R.string.mine_slot_lamp_posts_sub
    Slot.BOLLARDS -> R.string.mine_slot_bollards_sub
    Slot.TURBINES -> R.string.mine_slot_turbines_sub
    Slot.PYLONS -> R.string.mine_slot_pylons_sub
    Slot.NIGHT_GROUND -> R.string.mine_slot_night_ground_sub
    Slot.NIGHT_WATER -> R.string.mine_slot_night_water_sub
    Slot.LIT_WINDOWS -> R.string.mine_slot_lit_windows_sub
    Slot.SKY -> R.string.mine_slot_sky_sub
    Slot.SUN -> R.string.mine_slot_sun_sub
    Slot.SHADE -> R.string.mine_slot_shade_sub
    Slot.SHADOWS -> R.string.mine_slot_shadows_sub
    Slot.HAZE -> R.string.mine_slot_haze_sub
}
