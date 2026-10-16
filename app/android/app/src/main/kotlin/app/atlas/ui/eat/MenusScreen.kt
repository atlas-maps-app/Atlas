package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.InterFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.draw.dropShadow
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
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
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TableHeader(ctx, padCount = pad.size, onMap = { nav.go(Dest.Eat("map")) }, onPad = { nav.go(Dest.Eat("order")) }, onPick = { picking = if (picking == it) null else it })
                picking?.let { p -> TablePicker(p, ctx, onDone = { picking = null }) }
                Spacer(Modifier.height(12.dp))
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
                if (ctx.area.waiting || !worked.fresh) Looking(MUTED, vertical = 1.dp) else Spacer(Modifier.height(18.dp))
            }
            TableTop(counts, loading = ctx.area.loading, onOpen = { kind -> nav.go(Dest.Eat(kind)) })
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (ctx.area.sketchOnly) {
                    sourceLine(ctx.area)?.let { Text(it, Modifier.fillMaxWidth().padding(top = 6.dp), style = Atlas.type.caption, color = MUTED) }
                }
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
        // The board's foot: the dark Map pill and the die, 26 px from the bottom.
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
                Modifier.weight(1f).height(54.dp).clip(CircleShape).background(INK).clickable { nav.go(Dest.Eat("map")) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardIcon(EatGlyphs.map, Color.White, 19.dp)
                Text(stringResource(R.string.eat_map), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White))
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

/** The board's light buttons: 70 % white with a warm 0 2 8 shadow. */
private fun Modifier.paleButton(alpha: Float = 0.7f): Modifier = this
    .dropShadow(CircleShape, BoxShadow(radius = 4.dp, color = Color(0x1F3C280A), offset = DpOffset(0.dp, 2.dp)))
    .clip(CircleShape)
    .background(Color.White.copy(alpha = alpha))

/** What the header's time and party open when tapped. */
internal enum class Picker { TIME, PARTY }

/** "Dinner", its time and party, the map button and the order pad with its shortlist badge, as on the board. */
@Composable
private fun TableHeader(ctx: EatContext, padCount: Int, onMap: () -> Unit, onPad: () -> Unit, onPick: (Picker) -> Unit) {
    val mapLabel = stringResource(R.string.eat_map)
    // A table set for tomorrow (earlier than half an hour ago means the next day) says which day.
    val time = dayTime(ctx.at, LocalDateTime.now(ctx.services.clock), ctx.lang, HM_PADDED)
    val timeLabel = stringResource(R.string.eat_time_at, time)
    val partyLabel = stringResource(R.string.eat_party_of, ctx.table.party)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(48.dp).paleButton().clickable(onClick = onMap).semantics { contentDescription = mapLabel },
            contentAlignment = Alignment.Center,
        ) { BoardIcon(EatGlyphs.map, INK, 21.dp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(mealName(Meal.at(ctx.at.toLocalTime()))), style = TextStyle(fontFamily = SerifFamily, fontSize = 30.sp, lineHeight = 32.sp, color = INK))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val meta = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = MUTED)
                Row(
                    Modifier.clip(CircleShape).clickable { onPick(Picker.TIME) }.padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics(mergeDescendants = true) { contentDescription = timeLabel },
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoardIcon(EatGlyphs.clock, MUTED, 13.dp)
                    Text(time, style = meta)
                }
                Row(
                    Modifier.clip(CircleShape).clickable { onPick(Picker.PARTY) }.padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics(mergeDescendants = true) { contentDescription = partyLabel },
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoardIcon(EatGlyphs.party, MUTED, 13.dp)
                    Text(ctx.table.party.toString(), style = meta)
                }
            }
        }
        OrderPadButton(padCount, onPad)
    }
}

/** The board's dark order-pad button (48 px) with the orange badge counting the shortlist. */
@Composable
internal fun OrderPadButton(count: Int, onClick: () -> Unit, background: Color = INK, edge: Color = Color(0xFFEDE6DA)) {
    val description = if (count > 0) stringResource(R.string.eat_pad_count, count) else stringResource(R.string.eat_pad)
    Box {
        Box(
            Modifier
                .size(48.dp)
                .dropShadow(CircleShape, BoxShadow(radius = 6.dp, color = Color(0x4014161B), offset = DpOffset(0.dp, 4.dp)))
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { BoardIcon(EatGlyphs.pad, Color.White, 20.dp) }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(20.dp).clip(CircleShape).background(edge).padding(2.dp).clip(CircleShape).background(Color(0xFFE8622C)),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
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

/** "Ask the waiter": the violet sparkle, the field and the mic, on 78 % white, 50 px high. */
@Composable
private fun AskField(text: String, onText: (String) -> Unit, onAsk: () -> Unit, onMic: () -> Unit) {
    val micLabel = stringResource(R.string.kit_mic)
    val askLabel = stringResource(R.string.eat_ask)
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .dropShadow(CircleShape, BoxShadow(radius = 4.dp, color = Color(0x1A3C280A), offset = DpOffset(0.dp, 2.dp)))
            .clip(CircleShape)
            .background(Color(0xC7FFFFFF))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoardIcon(EatGlyphs.sparkle, Color(0xFF7B4DFF), 18.dp)
        Box(Modifier.weight(1f)) {
            val style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            if (text.isEmpty()) Text(stringResource(R.string.eat_ask), style = style, color = Color(0xFF8B857B))
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = style.copy(color = INK),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAsk() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = askLabel },
            )
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMic).semantics { contentDescription = micLabel }, contentAlignment = Alignment.Center) {
            BoardIcon(EatGlyphs.mic, MUTED, 19.dp)
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
            .paleButton(0.78f)
            .clickable(onClick = onRoll)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(EatGlyphs.dice, INK, 22.dp) }
}

/** The tablecloth: #EDE6DA woven with faint 1 px threads every 3 px both ways, darkening towards the edges. */
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
    val c = Offset(size.width * 0.5f, size.height * 0.45f)
    val r = kotlin.math.hypot(size.width * 0.5f, size.height * 0.55f)
    drawRect(Brush.radialGradient(0f to Color(0x00FFFFFF), 0.55f to Color(0x00FFFFFF), 1f to Color(0x29503C1E), center = c, radius = r))
}

internal val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

internal fun mealName(m: Meal): Int = when (m) {
    Meal.BREAKFAST -> R.string.eat_meal_breakfast
    Meal.LUNCH -> R.string.eat_meal_lunch
    Meal.AFTERNOON -> R.string.eat_meal_afternoon
    Meal.DINNER -> R.string.eat_meal_dinner
    Meal.LATE -> R.string.eat_meal_late
}
