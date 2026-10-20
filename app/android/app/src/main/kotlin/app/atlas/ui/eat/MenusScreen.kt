package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.softShadow
import app.atlas.ui.kit.walkMinutes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Eat & drink home, "the table" of the Menus board: the meal, its time and party; "ask the waiter"; six objects
 * lying on the table, each counting the places open at the table's time and opening its own screen; the map and a die
 * that picks a good open place at random.
 */
@Composable
fun MenusScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    val worked = rememberWorked(ctx.area, ctx.at) { EatSets.counts(ctx.area, ctx.at) }
    val counts = worked.value
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var question by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<Waiter.Answer?>(null) }
    var picking by remember { mutableStateOf<Picker?>(null) }
    var nothingToPick by remember { mutableStateOf(false) }

    val ask: (String) -> Unit = { text ->
        if (text.isNotBlank()) scope.launch {
            answer = Waiter.askSafely { withContext(Dispatchers.Default) { Waiter.ask(text, ctx.area, ctx.at, ctx.services.clock.zone, ctx.lang) } }
        }
    }
    val mic = rememberSpeechInput(onText = { question = it; ask(it) })

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawLinen() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // The board's top: the header row at 46 px, the waiter's field at 108, the table from 168.
            Column(Modifier.padding(horizontal = 16.dp)) {
                TableHeader(ctx, padCount = pad.size, onMap = { nav.go(Dest.Eat("map")) }, onPad = { nav.go(Dest.Eat("order")) }, onPick = { picking = if (picking == it) null else it })
                picking?.let { p -> TablePicker(p, ctx, onDone = { picking = null }) }
                Spacer(Modifier.height(13.dp))
                AskField(
                    text = question,
                    onText = { question = it; if (it.isBlank()) answer = null },
                    onAsk = { ask(question) },
                    onMic = mic,
                )
                answer?.let { a ->
                    Spacer(Modifier.height(10.dp))
                    WaiterCard(a, ctx, onPlace = { ctx.open(nav, it.place) }, onClose = { answer = null; question = "" })
                }
                if (ctx.area.waiting || !worked.fresh) Looking(MUTED, vertical = 1.dp) else Spacer(Modifier.height(10.dp))
            }
            TableTop(counts, loading = ctx.area.loading, onOpen = { kind -> nav.go(Dest.Eat(kind)) })
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (ctx.area.error != null && ctx.area.places.isEmpty()) {
                    EatProblem(ctx.area, Modifier.padding(top = 12.dp))
                } else if (!ctx.area.loading && ctx.area.places.isEmpty()) {
                    EatEmpty(Modifier.padding(top = 12.dp))
                }
                if (nothingToPick) {
                    Text(stringResource(R.string.eat_surprise_none), Modifier.fillMaxWidth().padding(top = 8.dp), style = Atlas.type.caption, color = MUTED)
                }
            }
            Spacer(Modifier.height(bottomRoom(96.dp)))
        }
        // The board's foot, 26 px from the bottom: the dark Map pill (318 × 54) and the die (54), 8 px apart.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(54.dp).cssBox(bg = 0xFF14161B, r = round(54f)).clip(CircleShape).clickable { nav.go(Dest.Eat("map")) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("tab.map", tint = Color.White, size = 19.dp)
                CssLine(stringResource(R.string.eat_map), cssText("Geist", 15f, 600, colour = 0xFFFFFFFF, lineHeight = 19.5f))
            }
            Dice(
                description = stringResource(R.string.eat_surprise),
                onRoll = {
                    val pick = EatSets.surprise(ctx.area, ctx.at, ctx.ratings)
                    nothingToPick = pick == null
                    if (pick != null) ctx.open(nav, pick.place)
                },
            )
        }
    }
}

private val INK = Color(0xFF14161B)
private val MUTED = Color(0xFF5A5347)

/** The board's light buttons: 70 % white (78 % for the die) with a warm 0 2 8 shadow at 12 %. */
private fun Modifier.paleButton(alpha: Float = 0.7f): Modifier =
    cssBox(bg = Color.White.copy(alpha = alpha).css(), r = round(48f), shadows = listOf(CssShadow(0f, 2f, 8f, 0f, 0x1F3C280A))).clip(CircleShape)

/** What the header's time and party open when tapped. */
internal enum class Picker { TIME, PARTY }

private val MEAL = cssText("Instrument Serif", 30f, colour = 0xFF14161B, lineHeight = 30f)
private val META = cssText("Geist", 13f, colour = 0xFF5A5347, lineHeight = 17f, tnum = true)

/**
 * The header row at 46 px, 10 px between its parts: the map button (48), "Dinner" (30 px on a 30 px line) over its
 * time and party (13 px, 2 px below, the two 10 px apart, each symbol 3 px from its figure), the order pad (48) with
 * its shortlist badge.
 */
@Composable
private fun TableHeader(ctx: EatContext, padCount: Int, onMap: () -> Unit, onPad: () -> Unit, onPick: (Picker) -> Unit) {
    val mapLabel = stringResource(R.string.eat_map)
    // A table set for tomorrow (earlier than half an hour ago means the next day) says which day.
    val time = dayTime(ctx.at, LocalDateTime.now(ctx.services.clock), ctx.lang, HM_PADDED)
    val timeLabel = stringResource(R.string.eat_time_at, time)
    val partyLabel = stringResource(R.string.eat_party_of, ctx.table.party)
    Row(Modifier.fillMaxWidth().padding(top = boardTop(46.dp)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(48.dp).paleButton().clickable(onClick = onMap).semantics { contentDescription = mapLabel },
            contentAlignment = Alignment.Center,
        ) { Symbol("tab.map", tint = INK, size = 21.dp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CssLine(stringResource(mealName(Meal.at(ctx.at.toLocalTime()))), MEAL)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clickable { onPick(Picker.TIME) }.semantics(mergeDescendants = true) { contentDescription = timeLabel },
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.clock", tint = MUTED, size = 13.dp)
                    CssLine(time, META)
                }
                Row(
                    Modifier.clickable { onPick(Picker.PARTY) }.semantics(mergeDescendants = true) { contentDescription = partyLabel },
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("fact.groups", tint = MUTED, size = 13.dp)
                    CssLine(ctx.table.party.toString(), META)
                }
            }
        }
        OrderPadButton(padCount, onPad)
    }
}

/**
 * The board's dark order-pad button (48 px, 0 4 12 shadow) with the orange badge counting the shortlist: 20 px in a
 * 2 px ring of the table's colour, 2 px over the button's top right.
 */
@Composable
internal fun OrderPadButton(count: Int, onClick: () -> Unit, background: Color = INK, edge: Color = Color(0xFFEDE6DA)) {
    val description = if (count > 0) stringResource(R.string.eat_pad_count, count) else stringResource(R.string.eat_pad)
    Box {
        Box(
            Modifier
                .size(48.dp)
                .cssBox(bg = background.css(), r = round(48f), shadows = listOf(CssShadow(0f, 4f, 12f, 0f, 0x4014161B)))
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.order", tint = Color.White, size = 20.dp) }
        if (count > 0) {
            val ring = edge.css()
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(24.dp)
                    .cssBox(bg = 0xFFE8622C, r = corners(10f), border = CssBorder(2f, 2f, 2f, 2f, longArrayOf(ring, ring, ring, ring))),
                contentAlignment = Alignment.Center,
            ) {
                CssLine(count.toString(), cssText("Geist", 11f, 700, colour = 0xFFFFFFFF, lineHeight = 14f))
            }
        }
    }
}

/** The small card under the header that sets the time (quarter hours, or now) or the party size. */
@Composable
private fun TablePicker(picker: Picker, ctx: EatContext, onDone: () -> Unit) {
    val state = ctx.repo.table
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .softShadow(RoundedCornerShape(20.dp), Atlas.colors.shadow, 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFFFFCF6))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (picker) {
            Picker.TIME -> {
                SymbolButton("ui-minus", stringResource(R.string.eat_earlier), { state.shift(-15) }, size = 40.dp, elevation = 0.dp, background = Atlas.colors.cardMuted)
                Text(dayTime(ctx.at, LocalDateTime.now(ctx.services.clock), ctx.lang), Modifier.weight(1f), style = Atlas.type.numeralSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                SymbolButton("ui-plus", stringResource(R.string.eat_later), { state.shift(15) }, size = 40.dp, elevation = 0.dp, background = Atlas.colors.cardMuted)
                PillButton(onClick = { state.setTime(null); onDone() }, background = Color(0xFF16151A)) {
                    Text(stringResource(R.string.eat_now), style = Atlas.type.label)
                }
            }
            Picker.PARTY -> {
                SymbolButton("ui-minus", stringResource(R.string.eat_fewer), { state.setParty(ctx.table.party - 1) }, size = 40.dp, elevation = 0.dp, background = Atlas.colors.cardMuted)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Symbol("ui-users", size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.table.party.toString(), style = Atlas.type.numeralSmall)
                }
                SymbolButton("ui-plus", stringResource(R.string.eat_more_people), { state.setParty(ctx.table.party + 1) }, size = 40.dp, elevation = 0.dp, background = Atlas.colors.cardMuted)
                SymbolButton("ui-check", stringResource(R.string.eat_done), onDone, size = 40.dp, elevation = 0.dp, background = Color(0xFF16151A), tint = Color.White)
            }
        }
    }
}

/** "Ask the waiter" at 108 px: 50 px of 78 % white, 0 2 8 shadow at 10 %, 16 px in, 10 apart: the violet sparkle (18), the field (Geist 500 16) and the mic (19). */
@Composable
private fun AskField(text: String, onText: (String) -> Unit, onAsk: () -> Unit, onMic: () -> Unit) {
    val micLabel = stringResource(R.string.kit_mic)
    val askLabel = stringResource(R.string.eat_ask)
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .cssBox(bg = 0xC7FFFFFF, r = round(50f), shadows = listOf(CssShadow(0f, 2f, 8f, 0f, 0x1A3C280A)))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol("fact.new", tint = Color(0xFF7B4DFF), size = 18.dp)
        Box(Modifier.weight(1f)) {
            val style = cssText("Geist", 16f, 500, lineHeight = 20.5f)
            if (text.isEmpty()) CssLine(stringResource(R.string.eat_ask), style, color = Color(0xFFA9A9A9))
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = style.copy(color = INK, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAsk() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = askLabel },
            )
        }
        // The mic in a 40 px target whose centre is the board's (16 px in from the field's end).
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMic).semantics { contentDescription = micLabel }, contentAlignment = Alignment.Center) {
            Symbol("ui.mic", tint = MUTED, size = 19.dp)
        }
    }
}

/** The waiter's answer: what was understood and the best places, each a tap away. */
@Composable
private fun WaiterCard(a: Waiter.Answer, ctx: EatContext, onPlace: (app.atlas.search.Hit) -> Unit, onClose: () -> Unit) {
    val c = Atlas.colors
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(RoundedCornerShape(22.dp), c.shadow, 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFFFFCF6))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol("ui-sparkles", tint = c.ask, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            val understood = a.parsed.chips.joinToString(" · ") { it.label }.ifEmpty { a.text }
            Text(understood, Modifier.weight(1f), style = Atlas.type.label, color = c.ask, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${a.total}", style = Atlas.type.label, color = c.inkSoft)
            SymbolButton("ui-close", stringResource(R.string.kit_close), onClose, size = 32.dp, elevation = 0.dp, background = Color.Transparent)
        }
        if (a.hits.isEmpty()) {
            Text(stringResource(R.string.eat_waiter_none), Modifier.padding(vertical = 8.dp), style = Atlas.type.body, color = c.inkSoft)
        }
        for (h in a.hits) {
            val p = ctx.area[h.place.key]
            Row(
                Modifier.fillMaxWidth().clickable { onPlace(h) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Symbol(h.symbol, tint = Color(0xFF8A2A2C), size = 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(p?.name ?: h.place.name() ?: "", style = Atlas.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p?.let { ep -> openWords(ep, ctx)?.let { (w, col) -> Text(w, style = Atlas.type.caption, color = col) } }
                }
                Text(h.distance?.let { walkMinutes(app.atlas.ui.kit.WalkTime.seconds(it)) } ?: "", style = Atlas.type.numeralSmall)
            }
            Hairline()
        }
    }
}

/**
 * The six objects on the table where the board puts them (left, top, width, height in board px on the 412-wide
 * frame, and each one's tilt), scaled to the screen's width; later objects lie on top, as on the board.
 */
@Composable
private fun TableTop(counts: EatSets.Counts?, loading: Boolean, onOpen: (String) -> Unit) {
    val carte = stringResource(R.string.eat_carte)
    val cafe = stringResource(R.string.eat_cafe)
    val late = stringResource(R.string.eat_late)
    val bar = stringResource(R.string.eat_bar)
    val sweets = stringResource(R.string.eat_sweets)
    val market = stringResource(R.string.eat_market)
    val open = stringResource(R.string.eat_open_count)
    fun say(name: String, c: EatArea.Count?) = if (c == null) name else if (c.open) "$name, ${c.value} $open" else "$name, ${c.value}"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth.value / 412f
        Box(Modifier.fillMaxWidth().height((648 * u).dp)) {
            Placed(u, 22f, 186f, 176f, 250f) {
                TableObject(-3f, say(carte, counts?.carte), { onOpen("carte") }) { LeatherMenu(counts?.carte, carte, u) }
            }
            Placed(u, 214f, 176f, 176f, 200f) {
                TableObject(2.5f, say(cafe, counts?.cafe), { onOpen("cafe") }) { Chalkboard(counts?.cafe, cafe, u) }
            }
            Placed(u, 222f, 400f, 166f, 236f) {
                TableObject(4f, say(bar, counts?.bar), { onOpen("bar") }) { DecoCard(counts?.bar, bar, u) }
            }
            Placed(u, 26f, 458f, 176f, 170f) {
                TableObject(-2f, say(late, counts?.late), { onOpen("late") }) { NeonLate(counts?.late, counts?.lateUntil?.format(HM), late, u, loading) }
            }
            Placed(u, 32f, 652f, 160f, 150f) {
                TableObject(3f, say(sweets, counts?.sweets), { onOpen("sweets") }) { TicketStub(counts?.sweets, sweets, u) }
            }
            Placed(u, 226f, 664f, 150f, 140f) {
                TableObject(-4f, say(market, counts?.market), { onOpen("market") }) {
                    Canvas(Modifier.fillMaxSize()) { tagString(Offset(-40f * u * density, -30f * u * density), u * density) }
                    KraftTag(counts?.market, market, u)
                }
            }
        }
    }
}

/** Where the table's frame starts on the board: just above the café's slate. */
private const val TABLE_TOP = 168f

/** A box at board coordinates ([x], [y], [w], [h] on the 412-wide board), scaled by [u]. */
@Composable
private fun Placed(u: Float, x: Float, y: Float, w: Float, h: Float, content: @Composable () -> Unit) {
    Box(Modifier.offset((x * u).dp, ((y - TABLE_TOP) * u).dp).size((w * u).dp, (h * u).dp)) { content() }
}

/** The round button with a die on it: "surprise me" (54 px, 78 % white). */
@Composable
private fun Dice(description: String, onRoll: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .cssBox(bg = 0xC7FFFFFF, r = round(54f), shadows = listOf(CssShadow(0f, 2f, 8f, 0f, 0x1F3C280A)))
            .clip(CircleShape)
            .clickable(onClick = onRoll)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol("ui.surprise.2", tint = INK, size = 22.dp) }
}

/** The tablecloth: #EDE6DA woven with faint 1 px threads every 3 px both ways, darkening to the edges (an ellipse at 50 % 45 %). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinen() {
    drawRect(Color(0xFFEDE6DA))
    val step = 3.dp.toPx()
    val thread = Color(0x0D78644A)
    var x = 0f
    while (x < size.width) {
        drawRect(thread, Offset(x, 0f), androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawRect(thread, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
        y += step
    }
    drawRect(cssRadial(false, size.width / density / 2f, size.height / density * 0.45f, 0.55f to 0x00FFFFFF, 1f to 0x29503C1E).brush(size, density))
}

internal val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

internal fun mealName(m: Meal): Int = when (m) {
    Meal.BREAKFAST -> R.string.eat_meal_breakfast
    Meal.LUNCH -> R.string.eat_meal_lunch
    Meal.AFTERNOON -> R.string.eat_meal_afternoon
    Meal.DINNER -> R.string.eat_meal_dinner
    Meal.LATE -> R.string.eat_meal_late
}
