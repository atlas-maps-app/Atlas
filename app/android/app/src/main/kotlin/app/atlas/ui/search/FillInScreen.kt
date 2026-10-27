package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.StageMap
import app.atlas.model.OsmId
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.providers.OsmElement
import app.atlas.providers.OsmType
import app.atlas.services.EditSender
import app.atlas.nav.LocalServices
import app.atlas.ui.kit.BoardFonts
import app.atlas.ui.kit.BoardScale
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.El
import app.atlas.ui.kit.Sy
import app.atlas.ui.kit.Tx
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRepeatingLinear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * "Fill in the gaps" (canvas/project/FillIn.dc.html): one building's open questions, asked over the living map at the
 * building, then the user's edit to OpenStreetMap to review and send with their own account (EditQueue, EditSender). The
 * building is read from OpenStreetMap as it is now, so the questions ask only what is truly unmapped and the edit keeps
 * what the user saw. The board's flow is [FillInState]; its chrome is the generated board's (cache/claude-session/boards/
 * kt/FillInBoard.kt), its other states laid out from the board's markup.
 */
@Composable
fun FillInScreen(args: Dest.FillIn, nav: Navigator) {
    val services = LocalServices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = OsmType.entries.firstOrNull { it.api == args.type } ?: OsmType.WAY
    var load by remember(args) { mutableStateOf<FillInLoad>(FillInLoad.Reading) }
    LaunchedEffect(args) {
        load = try {
            withContext(Dispatchers.IO) { services.osmEdits.element(type, args.id) }?.let { FillInLoad.Read(it) } ?: FillInLoad.Gone
        } catch (_: IOException) {
            FillInLoad.Offline
        }
    }
    val user by services.osmAccount.user.collectAsState()
    val element = (load as? FillInLoad.Read)?.element
    val asks = remember(element) { element?.let { FillIn.asks(it.tags) }.orEmpty() }
    var state by rememberSaveable(asks, stateSaver = stateSaver(asks)) { mutableStateOf(FillInState(asks)) }
    val map = remember(args) { MapState(CameraPosition(args.at, 18.2, bearing = 0.0, pitch = 55.0)) }
    var pin by remember { mutableStateOf<Offset?>(null) }
    // the building asked about as the board draws it, where the core draws the map (ENGINE.md step 6)
    val asked = buildingId(type, args.id)?.let { state.asked(it) }
    val overlays = remember(asked) { MapOverlays(asked = asked) }
    LaunchedEffect(map) {
        snapshotFlow { map.camera to map.visibleBounds }.collect { pin = map.latLonToScreen(args.at)?.let { (x, y) -> Offset(x, y) } }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(argb(0xFF0B0D14))) {
        StageMap(state = map, modifier = Modifier.fillMaxSize(), overlays = overlays, background = argb(0xFF0B0D14))
        // the board's px for the projected point: the board is the screen's width
        val boardPerPx = 412f / (constraints.maxWidth.toFloat().coerceAtLeast(1f))
        BoardScale {
            FillInView(
                state = state,
                head = element?.let { headLine(it) },
                elementLine = element?.let { FillInState.elementLine(it) }.orEmpty(),
                tags = element?.tags.orEmpty(),
                signedIn = user != null,
                message = when (load) {
                    FillInLoad.Offline -> stringResource(R.string.fillin_offline)
                    FillInLoad.Gone -> stringResource(R.string.fillin_gone)
                    is FillInLoad.Read -> if (asks.isEmpty()) stringResource(R.string.fillin_nothing_open) else null
                    FillInLoad.Reading -> null
                },
                pinAt = pin?.let { Offset(it.x * boardPerPx, it.y * boardPerPx) },
                onBack = { nav.back() },
                onColour = { state = state.pick(it) },
                onRoof = { state = state.pick(it) },
                onNext = { state = state.next() },
                onSkip = { state = state.skip() },
                onRestart = { state = state.restart() },
                onSend = {
                    val e = element
                    val edit = e?.let { FillIn.edit(type, args.id, it.tags, state.colour, state.roof) }
                    if (e != null && edit != null) {
                        val label = listOfNotNull(FillInState.address(e.tags), edit.set.keys.joinToString(", ")).joinToString(" · ")
                        val signedIn = user != null
                        scope.launch(Dispatchers.IO) {
                            services.edits.add(edit, label)
                            // drawn at once: the windows that hold the building built again with it (ENGINE.md step 6)
                            MapEngine.edited(args.lat, args.lon)
                            if (signedIn) EditSender.schedule(context, comment(edit.set.keys))
                        }
                    }
                    state = state.send()
                },
                onMore = { nav.go(Dest.Quests(args.lat, args.lon)) },
            )
        }
    }
}

/** The building [type] [id] as the map names it: a way or a relation (a node is no building's outline). */
private fun buildingId(type: OsmType, id: Long): OsmId? = when (type) {
    OsmType.WAY -> OsmId(app.atlas.model.OsmType.WAY, id)
    OsmType.RELATION -> OsmId(app.atlas.model.OsmType.RELATION, id)
    OsmType.NODE -> null
}

/** What reading the building came to. */
internal sealed interface FillInLoad {
    data object Reading : FillInLoad

    data class Read(val element: OsmElement) : FillInLoad

    data object Gone : FillInLoad

    data object Offline : FillInLoad
}

/** The changeset's comment for the keys an edit sets. */
internal fun comment(keys: Set<String>): String {
    val what = buildList {
        if ("building:colour" in keys) add("building colour")
        if ("roof:shape" in keys) add("roof shape")
    }.ifEmpty { listOf("building details") }
    return what.joinToString(" and ").replaceFirstChar { it.uppercase() } + " as seen from the street"
}

private fun stateSaver(asks: List<FillIn.Ask>) = listSaver<FillInState, Any?>(
    save = { listOf(it.step, it.colour?.id, it.roof?.shape, it.sent) },
    restore = { v ->
        FillInState(
            asks,
            step = v[0] as Int,
            colour = FillIn.COLOURS.firstOrNull { it.id == v[1] },
            roof = FillIn.ROOFS.firstOrNull { it.shape == v[2] },
            sent = v[3] as Boolean,
        )
    },
)

/** The line under the title: "Leliegracht 49 · built 1673 · 16.1 m", each part where the tags have it. */
@Composable
private fun headLine(e: OsmElement): String = listOfNotNull(
    FillInState.address(e.tags),
    (e.tags["start_date"] ?: e.tags["building:start_date"])?.let { stringResource(R.string.fillin_built, it) },
    e.tags["height"]?.let { stringResource(R.string.fillin_height, it) },
).joinToString(" · ")

// ---- The board ----------------------------------------------------------------------------------------------------

private const val INK = 0xFFEEF1F7
private const val MUTED = 0xFF9FAAC2
private const val GUESS = 0xFFFFC27A
private const val MINT = 0xFF7FE0A8
private const val BLUE = 0xFF2E5BFF
private const val LINK = 0xFF9FC0FF
private const val SUB = 0xFFA9B1C4

/** A board text style (the generated board's Tx, for text laid out by Compose). */
private fun style(size: Float, weight: Int = 400, colour: Long = INK, lineHeight: Float = 0f, spacing: Float = 0f, mono: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(
        fontFamily = BoardFonts.of(if (mono) "Geist Mono, monospace" else "Geist, sans-serif"),
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        lineHeight = if (lineHeight > 0f) lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        letterSpacing = if (spacing != 0f) (spacing / size).em else androidx.compose.ui.unit.TextUnit.Unspecified,
        color = argb(colour),
        textAlign = align,
    )

/**
 * The FillIn board over the map, for [state]: the header, the progress and the pin over the building at [pinAt] (board
 * px), and the sheet's question, review or done. Stateless, so its screenshots show every state.
 */
@Composable
internal fun FillInView(
    state: FillInState,
    head: String?,
    elementLine: String,
    tags: Map<String, String>,
    signedIn: Boolean,
    message: String?,
    pinAt: Offset?,
    onBack: () -> Unit,
    onColour: (FillIn.Colour) -> Unit,
    onRoof: (FillIn.Roof) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onRestart: () -> Unit,
    onSend: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.requiredSize(412.dp, 915.dp)) {
        // the top's shade, the back button, the title and the building's line
        El(0f, 0f, 412f, 130f, grad = listOf(cssLinear(180f, 0f to 0xB2080A12, 1f to 0x00080A12)))
        val back = stringResource(R.string.fillin_back_map)
        El(16f, 42f, 44f, 44f, bg = 0xB80C0E16, r = CssCorners(22f, 22f, 22f, 22f), blur = 12f)
        Sy("ui.back", 28f, 54f, 20f, 20f, tint = 0xFFFFFFFF)
        Box(
            Modifier.offset(16.dp, 42.dp).requiredSize(44.dp).clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = back },
        )
        Tx(stringResource(R.string.fillin_title), 72f, 40.2f, 215f, 28.5f, "Geist, sans-serif", 22f, weight = 800, colour = 0xFFFFFFFF, lineHeight = 23.1f, shadow = CssShadow(0f, 1f, 8f, 0f, 0x80000000))
        if (head != null) {
            Tx(head, 72f, 69.3f, 215f, 15.5f, "Geist, system-ui, sans-serif", 12f, colour = 0xFFE3E7F0, lineHeight = 15.5f, shadow = CssShadow(0f, 1f, 8f, 0f, 0x80000000))
        }
        if (state.asks.isNotEmpty()) Progress(state)
        if (pinAt != null && !state.done && state.asks.isNotEmpty()) Pin(state, pinAt)

        // the sheet: the board's glass, its content box 30..382 × 585..883
        El(12f, 567f, 388f, 332f, bg = 0xE00C0E16, r = CssCorners(28f, 28f, 28f, 28f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x14FFFFFF, inset = true), CssShadow(0f, -10f, 30f, 0f, 0x4C000000)), blur = 20f, saturate = 1.4f)
        Column(Modifier.offset(30.dp, 585.dp).requiredSize(352.dp, 298.dp)) {
            when {
                message != null -> Text(message, style = style(14f, 600, INK, 19.6f))
                state.asks.isEmpty() -> Unit
                state.done -> Done(state, signedIn, onBack, onMore)
                state.reviewing -> Review(state, elementLine, tags, signedIn, onRestart, onSend)
                state.ask == FillIn.Ask.COLOUR -> ColourQuestion(state, onColour, onNext, onSkip)
                else -> RoofQuestion(state, onRoof, onNext, onSkip, onRestart)
            }
        }
    }
}

/** The pill at the top right: the bar (8, 50 or 100 %) and "1 / 2", or done. */
@Composable
private fun Progress(state: FillInState) {
    val n = state.asks.size
    val label = if (state.step < n) stringResource(R.string.fillin_step, state.step + 1, n) else stringResource(R.string.fillin_step_done)
    val aria = if (state.step < n) stringResource(R.string.fillin_progress, state.step + 1, n) else stringResource(R.string.fillin_progress_done)
    val fraction = when {
        state.step >= n -> 1f
        state.step == 0 -> 0.08f
        else -> state.step.toFloat() / n
    }
    El(292.43f, 48f, 103.57f, 32f, bg = 0xB80C0E16, r = CssCorners(16f, 16f, 16f, 16f), blur = 12f)
    Box(Modifier.offset(292.43.dp, 48.dp).requiredSize(103.57.dp, 32.dp).semantics { contentDescription = aria })
    Tx(label, 354.43f, 55.5f, 36f, 17f, "Geist, sans-serif", 13f, weight = 800, colour = 0xFFFFFFFF, lineHeight = 17f, tnum = true)
    El(304.43f, 61f, 44f, 6f, bg = 0x2EFFFFFF, r = CssCorners(3f, 3f, 3f, 3f), clip = true) {
        El(0f, 0f, 44f * fraction, 6f, bg = MINT)
    }
}

/** The pin over the building: what is asked, the answer, or "Your edit", its stem's foot at [at]. */
@Composable
private fun Pin(state: FillInState, at: Offset) {
    val label = when (state.ask) {
        FillIn.Ask.COLOUR -> state.colour?.let { stringResource(R.string.fillin_pin_answer, colourName(it)) } ?: stringResource(R.string.fillin_pin_colour)
        FillIn.Ask.ROOF -> state.roof?.let { stringResource(R.string.fillin_pin_answer, roofName(it)) } ?: stringResource(R.string.fillin_pin_roof)
        null -> stringResource(R.string.fillin_pin_edit)
    }
    val open = (state.ask == FillIn.Ask.COLOUR && state.colour == null) || (state.ask == FillIn.Ask.ROOF && state.roof == null)
    val bg = if (open) GUESS else MINT
    // the board's pin: a 28 px chip with a 14 px stem under it, centred on the building
    val top = (at.y - 42f).coerceIn(96f, 520f)
    Box(Modifier.offset((at.x - 100f).dp, top.dp).requiredSize(200.dp, 42.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            label,
            Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(argb(bg)).padding(horizontal = 10.dp),
            style = style(12f, 800, 0xFF1A1400, 28f),
            maxLines = 1,
        )
        Box(Modifier.offset(y = 28.dp).size(2.dp, 14.dp).background(argb(bg)))
    }
}

/** The row atop the sheet: its label, and on the right a guess's note or a link back. */
@Composable
private fun SheetHead(label: String, right: String?, rightColour: Long = GUESS, onRight: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(label, style = style(12f, 800, MUTED, 15.5f, spacing = 1.44f), maxLines = 1)
        if (right != null) {
            Text(
                right,
                if (onRight != null) Modifier.clickable(role = Role.Button, onClick = onRight) else Modifier,
                style = style(12f, if (onRight != null) 700 else 400, rightColour, 15.5f),
                maxLines = 1,
            )
        }
    }
}

/** The sheet's two buttons at its foot: the blue one (dimmed until it can go) and "Can't tell". */
@Composable
private fun ColumnScope.Buttons(primary: String, enabled: Boolean, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            // dimmed whole, its text too, until it can go (the board's opacity .4)
            Modifier.weight(1f).height(44.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(22.dp)).background(argb(BLUE))
                .clickable(enabled = enabled, role = Role.Button, onClick = onPrimary),
            contentAlignment = Alignment.Center,
        ) { Text(primary, style = style(14f, 700, 0xFFFFFFFF, 18f), maxLines = 1) }
        if (secondary != null) {
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(argb(0x1AFFFFFF)).clickable(role = Role.Button, onClick = onSecondary)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(secondary, style = style(14f, 700, 0xFFFFFFFF, 18f), maxLines = 1) }
        }
    }
}

@Composable
private fun colourName(c: FillIn.Colour): String = stringResource(
    when (c.id) {
        "brown" -> R.string.fillin_colour_brown
        "red" -> R.string.fillin_colour_red
        "white" -> R.string.fillin_colour_white
        "sand" -> R.string.fillin_colour_sand
        else -> R.string.fillin_colour_black
    },
)

@Composable
private fun roofName(r: FillIn.Roof): String = stringResource(
    when (r.shape) {
        "gabled" -> R.string.fillin_roof_gabled
        "hipped" -> R.string.fillin_roof_hipped
        else -> R.string.fillin_roof_flat
    },
)

/** The question's number: "QUESTION 1 OF 2". */
@Composable
private fun questionLabel(state: FillInState) = stringResource(R.string.fillin_question, state.step + 1, state.asks.size)

/** The board's colour question: five swatches (bricks drawn with their courses), the hint, Next and Can't tell. */
@Composable
private fun ColumnScope.ColourQuestion(state: FillInState, onColour: (FillIn.Colour) -> Unit, onNext: () -> Unit, onSkip: () -> Unit) {
    SheetHead(questionLabel(state), stringResource(R.string.fillin_guess, "building:colour"))
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.fillin_colour_question), style = style(22f, 800, INK, 26.4f))
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in FillIn.COLOURS) {
            val name = colourName(c)
            val on = state.colour == c
            Column(
                Modifier.weight(1f).clickable(role = Role.Button) { onColour(c) }.semantics { contentDescription = name; selected = on },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(48.dp)) {
                    val brick = c.material == "brick"
                    El(
                        0f, 0f, 48f, 48f, bg = 0xFF000000 or java.lang.Long.parseLong(c.hex.removePrefix("#"), 16),
                        grad = if (brick) {
                            listOf(
                                cssRepeatingLinear(0f, 6f, 0f to 0x1F000000, 1f to 0x1F000000, 1f to 0x00000000, 6f to 0x00000000),
                                cssRepeatingLinear(90f, 12f, 0f to 0x17000000, 1f to 0x17000000, 1f to 0x00000000, 12f to 0x00000000),
                            )
                        } else {
                            emptyList()
                        },
                        r = CssCorners(14f, 14f, 14f, 14f),
                        // chosen: the board's two rings (3 px of the sheet, 5 px of white); else its faint inner edge
                        shadows = if (on) listOf(CssShadow(0f, 0f, 0f, 3f, 0xFF0C0E16), CssShadow(0f, 0f, 0f, 5f, 0xFFFFFFFF)) else listOf(CssShadow(0f, 0f, 0f, 1f, 0x2EFFFFFF, inset = true)),
                    )
                    if (on) Check(Modifier.align(Alignment.Center).size(16.dp), 0xFFFFFFFF)
                }
                // the board breaks each two-word name after its first word
                Text(name.replaceFirst(' ', '\n'), style = style(11f, 600, 0xFFDDE2EC, 12.65f, align = TextAlign.Center))
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.fillin_colour_hint), style = style(12f, 400, MUTED, 16.8f))
    Buttons(stringResource(R.string.fillin_next), state.canNext, onNext, stringResource(R.string.fillin_cant_tell), onSkip)
}

/** The board's roof question: three shapes drawn as its icons, the hint, Review and Can't tell. */
@Composable
private fun ColumnScope.RoofQuestion(state: FillInState, onRoof: (FillIn.Roof) -> Unit, onNext: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    val first = state.asks.firstOrNull() == FillIn.Ask.COLOUR
    SheetHead(questionLabel(state), if (first) stringResource(R.string.fillin_back_colour) else null, LINK, if (first) onBack else null)
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.fillin_roof_question), style = style(22f, 800, INK, 26.4f))
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in FillIn.ROOFS) {
            val name = roofName(r)
            val on = state.roof == r
            val fg = if (on) 0xFF0B0D14 else INK
            Column(
                Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(16.dp)).background(argb(if (on) 0xFFFFFFFF else 0x14FFFFFF))
                    .clickable(role = Role.Button) { onRoof(r) }.semantics { contentDescription = name; selected = on },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
                Icon(ROOF_ICONS.getValue(r.shape), Modifier.size(30.dp), fg, 1.8f)
                Text(name, style = style(12f, 700, fg, 13.8f, align = TextAlign.Center))
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.fillin_roof_hint), style = style(12f, 400, MUTED, 16.8f))
    Buttons(stringResource(R.string.fillin_review), state.canNext, onNext, stringResource(R.string.fillin_cant_tell), onSkip)
}

/** The board's review: the element's tags as OpenStreetMap has them and the ones the answers add, then Send. */
@Composable
private fun ColumnScope.Review(state: FillInState, elementLine: String, tags: Map<String, String>, signedIn: Boolean, onRestart: () -> Unit, onSend: () -> Unit) {
    SheetHead(stringResource(R.string.fillin_edit_head), stringResource(R.string.fillin_change), LINK, onRestart)
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(14.dp)).background(argb(0xFF06080E))
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(elementLine, style = style(12.5f, 500, 0xFF6F7890, 19.375f, mono = true), maxLines = 1)
        // the building's own tags in three lines at most (the board's three), so the additions always show
        val shown = FillInState.reviewTags(tags)
        for ((k, v) in shown) Text("  $k=$v", style = style(12.5f, 500, SUB, 19.375f, mono = true), maxLines = 1)
        val more = tags.size - shown.size
        if (more > 0) Text("  " + pluralStringResource(R.plurals.fillin_more_tags, more, more), style = style(12.5f, 500, 0xFF6F7890, 19.375f, mono = true), maxLines = 1)
        for ((k, v) in state.adds) Text("+ $k=$v", style = style(12.5f, 500, MINT, 19.375f, mono = true), maxLines = 1)
        if (state.adds.isEmpty()) Text(stringResource(R.string.fillin_nothing), style = style(12.5f, 500, 0xFF6F7890, 19.375f, mono = true))
    }
    Spacer(Modifier.height(10.dp))
    val still = FillInState.stillGuessed(tags, state.adds)
    if (still.isNotEmpty()) {
        Text(stringResource(R.string.fillin_still_guessed, still.joinToString(" · ")), style = style(12f, 400, GUESS, 16.8f))
        Spacer(Modifier.height(10.dp))
    }
    Text(stringResource(if (signedIn) R.string.fillin_send_note else R.string.fillin_send_note_local), style = style(12f, 400, MUTED, 16.8f))
    val n = state.adds.size
    Buttons(if (n > 0) pluralStringResource(R.plurals.fillin_send, n, n) else stringResource(R.string.fillin_leave), true, onSend, null) {}
}

/** The board's done: the green check, what came of it, the note, and the way back. */
@Composable
private fun ColumnScope.Done(state: FillInState, signedIn: Boolean, onBack: () -> Unit, onMore: () -> Unit) {
    val what = listOfNotNull(
        state.colour?.let { colourName(it).lowercase() },
        state.roof?.let { stringResource(R.string.fillin_roof_word, roofName(it).lowercase()) },
    ).joinToString(", ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(argb(0xFF1E9E5A)), contentAlignment = Alignment.Center) {
            Check(Modifier.size(16.dp), 0xFFFFFFFF)
        }
        Column {
            Text(stringResource(if (state.sent) R.string.fillin_done_sent else R.string.fillin_done_skipped), style = style(20f, 800, INK, 25f))
            val sub = when {
                !state.sent -> stringResource(R.string.fillin_done_skipped_sub)
                signedIn -> stringResource(R.string.fillin_done_sent_sub, what)
                else -> stringResource(R.string.fillin_done_local_sub, what)
            }
            Text(sub, style = style(13f, 400, SUB, 16.25f))
        }
    }
    if (state.sent) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.fillin_done_note), style = style(12f, 400, MUTED, 16.8f))
    }
    Buttons(stringResource(R.string.fillin_back_map), true, onBack, stringResource(R.string.fillin_more), onMore)
}

/** The board's roofs, its icons' paths (24 × 24, stroked). */
private val ROOF_ICONS = mapOf(
    "gabled" to listOf("M4 20V11l8-7 8 7v9z", "M9 20v-5h6v5"),
    "hipped" to listOf("M3 11l5-5h8l5 5v9H3z", "M3 11h18"),
    "flat" to listOf("M4 20V7h16v13z", "M3 7h18"),
)

/** The board's check, stroked 2.6 on its 24 × 24 grid. */
@Composable
private fun Check(modifier: Modifier, colour: Long) = Icon(listOf("M5 12.5l4.5 4.5L19 7.5"), modifier, colour, 2.6f)

/** A board icon: [paths] on a 24 × 24 grid, stroked [width] in [colour], round caps and joins. */
@Composable
private fun Icon(paths: List<String>, modifier: Modifier, colour: Long, width: Float) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            for (p in parsed) drawPath(p, argb(colour), style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
