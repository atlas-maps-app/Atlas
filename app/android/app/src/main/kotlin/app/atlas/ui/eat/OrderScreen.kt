package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Cuisine
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.Symbol
import kotlinx.coroutines.launch

/* The Order board's inks and measures (`canvas/project/Order.dc.html`). */
private val INK = Color(0xFF1D3C8F)
private val INK_SOFT = Color(0xFF4F66AE)
private val RED = Color(0xFF8E2B24)
private val PENCIL = Color(0xFF8A7F6E)
private val COUNT_INK = Color(0xFF6E6457)
private val RULE = Color(0xFFBFD3EA)
private val MARGIN = Color(0xFFE7A3A0)
private val CHECK_PAPER = Color(0xFFFFFEF7)
private val CHIP = Color(0xFFEFE7D6)
private val WOOD = Color(0xFF3B2A1E)
private val CREAM = Color(0xFFF3E9D8)
private val GUTTER = 50.dp
private val COUNT = 58.dp
private val ROW = 40.dp

/**
 * The order pad, as on the Order board: a red-headed guest check on dark wood. Each handwritten line is one clause of
 * a restaurant query, with the places left after it in the right-hand column (214 → 31 → 12 …); faint lines are
 * wishes that only rank. "Ranked by" stamps, presets, the `{} QL` export and the Serve cloche, which lists the places.
 * It compiles to `:search` queries and runs offline over the places around.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    LightStatusIcons()
    val book = ctx.repo.orders
    val pad by book.pad.collectAsStateWithLifecycle()
    val saved by book.saved.collectAsStateWithLifecycle()
    val jotted by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val worked = rememberWorked(ctx.area, ctx.at, pad, ctx.ratings) {
        OrderModel.evaluate(pad, ctx.at, ctx.services.clock.zone, ctx.area, ctx.ratings, ctx.lang)
    }
    val result = worked.value
    var editing by rememberSaveable { mutableStateOf<Line?>(null) }
    // A blank check opens with the clauses to write offered at once.
    var adding by rememberSaveable { mutableStateOf(Line.entries.none { it != Line.TIME && pad.has(it) }) }
    var served by rememberSaveable { mutableStateOf(false) }
    var showQl by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val number = remember(ctx.at) { "Nº " + ctx.at.format(java.time.format.DateTimeFormatter.ofPattern("MMdd")) }

    Box(Modifier.fillMaxSize().background(WOOD).drawBehind { drawWood() }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val back = stringResource(R.string.kit_back)
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
                    BoardIcon(EatGlyphs.backBold, CREAM, 20.dp)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(44.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).clickable { showQl = !showQl }.padding(horizontal = 12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Overpass QL" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BoardIcon(EatGlyphs.braces, CREAM, 16.dp)
                    Text("QL", style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = CREAM))
                }
                val clear = stringResource(R.string.eat_order_clear)
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0x1FFFFFFF))
                        .clickable { book.set(OrderPad(party = ctx.table.party)); served = false; editing = null; adding = true }
                        .semantics { contentDescription = clear },
                    contentAlignment = Alignment.Center,
                ) { BoardIcon(EatGlyphs.trash, CREAM, 18.dp) }
            }
            // The check itself: 376 wide on the board, turned -0.8°.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .rotate(-0.8f)
                    .boxShadow(RectangleShape, 1f, 20f, 40f, Color(0x73000000))
                    .boxShadow(RectangleShape, 1f, 2f, 4f, Color(0x4D000000))
                    .background(CHECK_PAPER),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp).background(RED).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val head = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.22.em, color = Color(0xFFFBEFE0))
                    Rivet()
                    Text(stringResource(R.string.eat_guest_check).uppercase(), style = head)
                    Spacer(Modifier.weight(1f))
                    Text(number, style = head)
                    Rivet()
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .heightIn(min = viewport)
                            .drawBehind { drawRules() }
                            .padding(top = 6.dp),
                    ) {
                        if (showQl) QlCard(OrderModel.ql(pad, ctx.at, ctx.area)) { showQl = false }
                        val counts = result?.lines?.associate { it.line to it.count }.orEmpty()
                        val last = result?.lines?.lastOrNull()?.line
                        for (line in Line.entries) {
                            if (!pad.has(line) && editing != line) continue
                            PadLine(
                                line, pad, counts[line], isLast = line == last, ctx = ctx,
                                showAdd = line == last && !adding,
                                onAdd = { adding = true },
                                onTap = { editing = if (editing == line) null else line },
                            )
                            if (editing == line) LineEditor(line, pad, ctx, onChange = { book.set(it) }, onDone = { editing = null })
                        }
                        if (adding) AddLine(pad, onClose = { adding = false }, onAdd = { l -> adding = false; editing = l })
                        RankedBy(pad.rank) { k -> book.update { p -> p.copy(rank = if (k in p.rank) p.rank - k else p.rank + k) } }
                        Presets(saved, onPreset = { book.set(it); served = false; adding = false }, onBookmark = { book.bookmark() }, bookmarked = saved == pad)
                        if (served && result != null) Served(result, ctx, jotted.map { it.key }.toSet(), onPlace = { ctx.open(nav, it) }, onJot = { p -> ctx.area[p.key]?.let { ctx.repo.shortlist.toggle(it) } })
                        if (jotted.isNotEmpty()) OnThePad(jotted, onOpen = { j -> nav.go(Dest.Place(j.at.lat, j.at.lon, j.osmId, j.name, j.key)) })
                        if (ctx.area.waiting || !worked.fresh) Looking(PENCIL)
                        Spacer(Modifier.height(16.dp))
                    }
                }
                Perforation()
                Footer(result, onServe = {
                    served = true
                    scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                })
            }
        }
    }
}

/** A grey rivet of the check's red head (6 px). */
@Composable
private fun Rivet() {
    Canvas(Modifier.size(6.dp)) {
        drawCircle(Color(0x66000000), size.minDimension / 2f, center + Offset(0f, 1.dp.toPx()))
        drawCircle(Color(0xFFD9D4C8), size.minDimension / 2f)
    }
}

/** One handwritten clause with its gutter mark and the places left after it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PadLine(line: Line, pad: OrderPad, count: Int?, isLast: Boolean, ctx: EatContext, showAdd: Boolean, onAdd: () -> Unit, onTap: () -> Unit) {
    val soft = line == Line.WISHES
    val hand = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 40.sp, color = INK)
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW).clickable(onClick = onTap).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(GUTTER), contentAlignment = Alignment.Center) { Gutter(line) }
        FlowRow(
            Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (line == Line.FEATURES || line == Line.WISHES || line == Line.PAYMENT) 12.dp else 8.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            when (line) {
                Line.TIME -> {
                    val moment = OrderModel.momentOf(pad, ctx.at)
                    val parts = listOfNotNull(moment.format(HM), stringResource(R.string.eat_order_for, pad.party), durationText(pad.minutes).takeIf { pad.minutes > 1 })
                    Text(parts.joinToString(" · "), style = hand, maxLines = 1)
                }
                Line.CUISINES -> pad.cuisines.forEachIndexed { i, c ->
                    if (i > 0) Badge(stringResource(R.string.eat_order_or), filled = false)
                    Text(Cuisine.name(c, ctx.lang), style = hand)
                }
                Line.NOT -> {
                    Badge(stringResource(R.string.eat_order_not), filled = true)
                    for (n in pad.not) {
                        val word = if (n == OrderPad.FAST_FOOD) stringResource(R.string.eat_order_fast_food) else Cuisine.name(n, ctx.lang).lowercase()
                        Text(word, style = hand.copy(fontWeight = FontWeight.Normal, fontSize = 25.sp, color = RED, textDecoration = TextDecoration.LineThrough))
                    }
                }
                Line.FEATURES -> {
                    if (pad.outside) Feature(EatGlyphs.umbrella, stringResource(R.string.eat_order_outside), INK)
                    if (pad.vegan) Feature(EatGlyphs.leaf, stringResource(R.string.eat_diet_vegan).lowercase(), Color(0xFF2F6B34))
                    else if (pad.vegetarian) Feature(EatGlyphs.leaf, stringResource(R.string.eat_diet_vegetarian).lowercase(), Color(0xFF2F6B34))
                }
                Line.ACCESS -> Feature(EatGlyphs.stepFree, stringResource(R.string.eat_order_step_free), INK)
                Line.WISHES -> for (w in pad.wishes) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        wishGlyph(w)?.let { BoardIcon(it, INK_SOFT, 17.dp) } ?: Symbol(w.symbol, tint = INK_SOFT, size = 17.dp)
                        Text(stringResource(wishName(w)), style = hand.copy(fontWeight = FontWeight.Normal, fontSize = 25.sp, color = INK_SOFT))
                    }
                }
                Line.PAYMENT -> {
                    pad.maxPrice?.let { Text("≤ " + "$".repeat(it), style = hand.copy(fontSize = 26.sp)) }
                    if (pad.card) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            BoardIcon(EatGlyphs.card, INK, 18.dp)
                            Text(stringResource(R.string.eat_order_card), style = hand.copy(fontSize = 26.sp))
                        }
                    }
                }
                Line.WALK -> pad.maxWalk?.let { Text(stringResource(R.string.eat_order_walk, it), style = hand.copy(fontSize = 26.sp)) }
            }
            if (showAdd) {
                Box(
                    Modifier
                        .size(30.dp)
                        .drawBehind {
                            drawCircle(Color(0xFF8A9CD0), size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        }
                        .clip(CircleShape)
                        .clickable(onClick = onAdd)
                        .semantics { contentDescription = "add a line" },
                    contentAlignment = Alignment.Center,
                ) { BoardIcon(EatGlyphs.plus, INK_SOFT, 14.dp) }
            }
        }
        Box(Modifier.width(COUNT), contentAlignment = Alignment.Center) {
            when {
                soft -> BoardIcon(EatGlyphs.upArrow, INK_SOFT, 16.dp, contentDescription = stringResource(R.string.eat_order_ranks_up))
                count != null -> Text(
                    count.toString(),
                    style = TextStyle(fontFamily = EatType.hand, fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal, fontSize = if (isLast) 24.sp else 22.sp, color = if (isLast) RED else COUNT_INK),
                )
            }
        }
    }
}

/** The gutter's mark for a line, the board's own. */
@Composable
private fun Gutter(line: Line) {
    when (line) {
        Line.TIME -> BoardIcon(EatGlyphs.clockGutter, PENCIL, 20.dp)
        Line.CUISINES -> BoardIcon(EatGlyphs.cutleryGutter, PENCIL, 20.dp)
        Line.FEATURES -> Canvas(Modifier.size(24.dp, 20.dp)) { drawBoardGlyph(EatGlyphs.mustHave, INK, side = size.width) }
        Line.WISHES -> BoardIcon(EatGlyphs.tick, Color(0xFF6E7FB8), 16.dp)
        Line.PAYMENT -> Text("$", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = PENCIL))
        Line.WALK -> BoardIcon(EatGlyphs.pin, PENCIL, 18.dp)
        Line.NOT, Line.ACCESS -> Unit
    }
}

/** A must-have written with its symbol and a double underline. */
@Composable
private fun Feature(glyph: BoardGlyph, word: String, tint: Color) {
    Row(
        Modifier.drawBehind {
            val y = size.height - 1.dp.toPx()
            drawLine(INK, Offset(0f, y - 2.dp.toPx()), Offset(size.width, y - 2.dp.toPx()), 1.dp.toPx())
            drawLine(INK, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoardIcon(glyph, tint, 18.dp)
        Text(word, style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, color = INK))
    }
}

/** "OR" ringed in red, "NOT" on a red tag (Geist 700 10 px, letter-spaced). */
@Composable
private fun Badge(text: String, filled: Boolean) {
    Text(
        text.uppercase(),
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (filled) Modifier.background(RED) else Modifier.border(1.5.dp, RED, RoundedCornerShape(9.dp)))
            .padding(horizontal = 6.dp, vertical = if (filled) 2.dp else 1.dp),
        style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.12.em, color = if (filled) Color.White else RED),
    )
}

/** Minutes as the pad writes them: "45′", "1 h", "1½ h", "2 h". */
internal fun durationText(minutes: Int): String = when {
    minutes < 60 -> "$minutes′"
    minutes % 60 == 0 -> "${minutes / 60} h"
    minutes % 60 == 30 -> "${minutes / 60}½ h"
    else -> "${minutes / 60} h ${minutes % 60}"
}

/** The clauses not yet written, offered as chips under the last line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddLine(pad: OrderPad, onClose: () -> Unit, onAdd: (Line) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = ROW).padding(start = GUTTER + 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in Line.entries) if (!pad.has(l)) PadChip(stringResource(lineName(l)), false) { onAdd(l) }
        }
        val close = stringResource(R.string.kit_close)
        Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClose).semantics { contentDescription = close }, contentAlignment = Alignment.Center) {
            Symbol("ui-close", tint = PENCIL, size = 14.dp)
        }
    }
}

/** The editor under a line: chips that change it, and done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineEditor(line: Line, pad: OrderPad, ctx: EatContext, onChange: (OrderPad) -> Unit, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = GUTTER + 8.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)
            .boxShadow(RoundedCornerShape(14.dp), 1f, 2f, 10f, Color(0x22000000))
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (line) {
                Line.TIME -> {
                    val moment = OrderModel.momentOf(pad, ctx.at)
                    PadChip("−15′", false) { onChange(pad.copy(time = moment.toLocalTime().minusMinutes(15))) }
                    PadChip(moment.format(HM), true) {}
                    PadChip("+15′", false) { onChange(pad.copy(time = moment.toLocalTime().plusMinutes(15))) }
                    PadChip(stringResource(R.string.eat_now), pad.time == null) { onChange(pad.copy(time = null)) }
                    PadChip("−", false) { onChange(pad.copy(party = (pad.party - 1).coerceAtLeast(1))) }
                    PadChip(stringResource(R.string.eat_order_for, pad.party), true) {}
                    PadChip("+", false) { onChange(pad.copy(party = (pad.party + 1).coerceAtMost(Table.MAX_PARTY))) }
                    for (m in listOf(0, 45, 60, 90, 120, 180)) {
                        PadChip(if (m == 0) stringResource(R.string.eat_order_any_time) else durationText(m), pad.minutes == m || (m == 0 && pad.minutes <= 1)) {
                            onChange(pad.copy(minutes = m))
                        }
                    }
                }
                Line.CUISINES -> for (c in OrderModel.commonCuisines(ctx.area)) {
                    PadChip(Cuisine.name(c, ctx.lang), c in pad.cuisines) { onChange(pad.copy(cuisines = if (c in pad.cuisines) pad.cuisines - c else pad.cuisines + c)) }
                }
                Line.NOT -> {
                    PadChip(stringResource(R.string.eat_order_fast_food), OrderPad.FAST_FOOD in pad.not) {
                        onChange(pad.copy(not = if (OrderPad.FAST_FOOD in pad.not) pad.not - OrderPad.FAST_FOOD else pad.not + OrderPad.FAST_FOOD))
                    }
                    for (c in OrderModel.commonCuisines(ctx.area, 10)) {
                        PadChip(Cuisine.name(c, ctx.lang), c in pad.not) { onChange(pad.copy(not = if (c in pad.not) pad.not - c else pad.not + c)) }
                    }
                }
                Line.FEATURES -> {
                    PadChip(stringResource(R.string.eat_order_outside), pad.outside) { onChange(pad.copy(outside = !pad.outside)) }
                    PadChip(stringResource(R.string.eat_diet_vegan), pad.vegan) { onChange(pad.copy(vegan = !pad.vegan)) }
                    PadChip(stringResource(R.string.eat_diet_vegetarian), pad.vegetarian) { onChange(pad.copy(vegetarian = !pad.vegetarian)) }
                }
                Line.ACCESS -> PadChip(stringResource(R.string.eat_order_step_free), pad.stepFree) { onChange(pad.copy(stepFree = !pad.stepFree)) }
                Line.WISHES -> for (w in Wish.entries) {
                    PadChip(stringResource(wishName(w)), w in pad.wishes) { onChange(pad.copy(wishes = if (w in pad.wishes) pad.wishes - w else pad.wishes + w)) }
                }
                Line.PAYMENT -> {
                    for (p in 1..4) PadChip("≤ " + "$".repeat(p), pad.maxPrice == p) { onChange(pad.copy(maxPrice = if (pad.maxPrice == p) null else p)) }
                    PadChip(stringResource(R.string.eat_order_card), pad.card) { onChange(pad.copy(card = !pad.card)) }
                }
                Line.WALK -> for (m in listOf(5, 10, 15, 20)) {
                    PadChip("≤ $m′", pad.maxWalk == m) { onChange(pad.copy(maxWalk = if (pad.maxWalk == m) null else m)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (line != Line.TIME) {
                Text(
                    stringResource(R.string.eat_order_strike),
                    Modifier.clip(CircleShape).clickable { onChange(cleared(pad, line)); onDone() }.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = Atlas.type.label,
                    color = RED,
                )
            }
            if (line == Line.PAYMENT) {
                Text(stringResource(R.string.eat_order_price_note), Modifier.weight(1f).padding(horizontal = 6.dp), style = Atlas.type.caption, color = PENCIL)
            } else Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.eat_done),
                Modifier.clip(CircleShape).background(INK).clickable(onClick = onDone).padding(horizontal = 14.dp, vertical = 6.dp),
                style = Atlas.type.label,
                color = Color.White,
            )
        }
    }
}

/** The pad without [line]. */
private fun cleared(p: OrderPad, line: Line): OrderPad = when (line) {
    Line.TIME -> p
    Line.CUISINES -> p.copy(cuisines = emptyList())
    Line.NOT -> p.copy(not = emptyList())
    Line.FEATURES -> p.copy(outside = false, vegan = false, vegetarian = false)
    Line.ACCESS -> p.copy(stepFree = false)
    Line.WISHES -> p.copy(wishes = emptySet())
    Line.PAYMENT -> p.copy(maxPrice = null, card = false)
    Line.WALK -> p.copy(maxWalk = null)
}

/** A chip of the board's presets: 30 px, #EFE7D6, Geist 600 13; navy when on. */
@Composable
private fun PadChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (on) INK else CHIP)
            .toggleable(on, role = Role.Checkbox) { onClick() }
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (on) Color.White else Color(0xFF14161B)))
    }
}

/** "RANKED BY" and the six round stamps (38 px); a pressed stamp carries its priority on a red dot. */
@Composable
private fun RankedBy(rank: List<RankKey>, onToggle: (RankKey) -> Unit) {
    Column(Modifier.fillMaxWidth().height(80.dp).padding(start = 62.dp, end = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.eat_order_ranked_by).uppercase(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.2.em, color = PENCIL))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (k in RankKey.entries) {
                val i = rank.indexOf(k)
                val on = i >= 0
                val name = stringResource(rankName(k))
                Box {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (on) INK else Color.Transparent)
                            .then(if (on) Modifier else Modifier.border(1.5.dp, Color(0xFFC9C0B0), CircleShape))
                            .toggleable(on, role = Role.Checkbox) { onToggle(k) }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = when {
                            !on -> PENCIL
                            k == RankKey.RATING -> Color(0xFFFFC53D)
                            else -> Color.White
                        }
                        when (k) {
                            RankKey.RATING -> BoardIcon(EatGlyphs.star, tint, 17.dp)
                            RankKey.WALK -> BoardIcon(EatGlyphs.walkThin, tint, 17.dp)
                            RankKey.TRENDING -> BoardIcon(EatGlyphs.flame, tint, 16.dp)
                            RankKey.LATE -> BoardIcon(EatGlyphs.moon, tint, 16.dp)
                            RankKey.NEW -> BoardIcon(EatGlyphs.sparkle, tint, 16.dp)
                            RankKey.PRICE -> Text("$", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint))
                        }
                    }
                    if (on) {
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(16.dp).clip(CircleShape).background(RED),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text((i + 1).toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
                        }
                    }
                }
            }
        }
    }
}

/** Date night, Quick lunch, Kids, the bookmarked order, and the bookmark itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(saved: OrderPad?, onPreset: (OrderPad) -> Unit, onBookmark: () -> Unit, bookmarked: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = ROW).padding(start = 62.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PadChip(stringResource(R.string.eat_preset_date), false) { onPreset(OrderPad.DATE_NIGHT) }
        PadChip(stringResource(R.string.eat_preset_lunch), false) { onPreset(OrderPad.QUICK_LUNCH) }
        PadChip(stringResource(R.string.eat_preset_kids), false) { onPreset(OrderPad.KIDS) }
        if (saved != null && !bookmarked) PadChip(stringResource(R.string.eat_preset_mine), false) { onPreset(saved) }
        val label = stringResource(R.string.eat_order_bookmark)
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (bookmarked) RED else INK).clickable(onClick = onBookmark).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { BoardIcon(EatGlyphs.bookmark, Color.White, 14.dp) }
    }
}

/** The places served: handwritten, numbered, nearest walk on the right; each opens its place, and can be jotted. */
@Composable
private fun Served(r: OrderResult, ctx: EatContext, jotted: Set<String>, onPlace: (app.atlas.model.Place) -> Unit, onJot: (app.atlas.model.Place) -> Unit) {
    val hand = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, color = INK)
    Column(Modifier.padding(top = 10.dp)) {
        Text(stringResource(R.string.eat_order_served).uppercase(), Modifier.padding(start = 62.dp), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.2.em, color = PENCIL))
        if (r.served.isEmpty()) {
            Text(stringResource(R.string.eat_order_none), Modifier.padding(start = 62.dp, top = 8.dp), style = hand.copy(fontSize = 22.sp, color = PENCIL))
        }
        r.served.forEachIndexed { i, h ->
            val p = ctx.area[h.place.key]
            Row(Modifier.fillMaxWidth().heightIn(min = ROW).clickable { onPlace(h.place) }, verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(GUTTER).padding(end = 6.dp), style = hand.copy(fontSize = 20.sp, color = PENCIL), textAlign = TextAlign.End)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(p?.name ?: h.place.name().orEmpty(), style = hand, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p?.let { openWords(it, ctx) }?.let { (w, c) -> Text(w, style = Atlas.type.caption, color = c) }
                }
                val on = h.place.key in jotted
                val label = stringResource(if (on) R.string.eat_pad_remove else R.string.eat_pad_add)
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (on) INK else CHIP).toggleable(on, role = Role.Checkbox) { onJot(h.place) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { if (on) Symbol("ui-check", tint = Color.White, size = 14.dp) else BoardIcon(EatGlyphs.plus, INK, 14.dp) }
                Box(Modifier.width(COUNT), contentAlignment = Alignment.Center) {
                    Text(p?.let { "${it.walkMinutes}′" } ?: "", style = hand.copy(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = COUNT_INK))
                }
            }
        }
    }
}

/** The places jotted from the carte, the bar or the machine: the pad's shortlist. */
@Composable
private fun OnThePad(items: List<Jotted>, onOpen: (Jotted) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(stringResource(R.string.eat_order_on_pad).uppercase(), Modifier.padding(start = 62.dp), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.2.em, color = PENCIL))
        for (j in items) {
            Row(Modifier.fillMaxWidth().heightIn(min = ROW).clickable { onOpen(j) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(GUTTER), contentAlignment = Alignment.Center) { BoardIcon(EatGlyphs.bookmark, PENCIL, 14.dp) }
                Text(j.name, Modifier.padding(start = 12.dp), style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, color = INK), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The tear line: a dashed rule over holes punched every 16 px, showing the wood. */
@Composable
private fun Perforation() {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        drawLine(Color(0xFFD6CCBA), Offset(0f, 1.dp.toPx()), Offset(size.width, 1.dp.toPx()), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        val step = 16.dp.toPx()
        var x = 7.dp.toPx()
        while (x < size.width) {
            drawCircle(WOOD, 4.dp.toPx(), Offset(x, 7.dp.toPx()))
            x += step
        }
    }
}

/** "6 of 214 · 0.03 s" and the Serve cloche. */
@Composable
private fun Footer(r: OrderResult?, onServe: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(96.dp).padding(start = 18.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(r?.total?.toString() ?: "…", style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.Bold, fontSize = 54.sp, lineHeight = 54.sp, color = RED))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r?.let { stringResource(R.string.eat_order_of, it.base) } ?: "", style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = COUNT_INK))
            Text(r?.let { String.format(java.util.Locale.ROOT, "%.2f s", it.micros / 1e6) } ?: "", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = COUNT_INK))
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(56.dp).clip(CircleShape).background(INK).clickable(onClick = onServe).padding(start = 18.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardIcon(EatGlyphs.cloche, Color.White, 22.dp)
            Text(stringResource(R.string.eat_order_serve), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White))
        }
    }
}

/** The Overpass QL of the order, selectable, with a copy button. */
@Composable
private fun QlCard(ql: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1D22))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BoardIcon(EatGlyphs.braces, Color(0xFFE8E4DA), 16.dp)
            Text("Overpass QL", Modifier.weight(1f), style = Atlas.type.label, color = Color(0xFFE8E4DA))
            val copy = stringResource(R.string.eat_order_copy)
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable { clipboard.setText(AnnotatedString(ql)) }.semantics { contentDescription = copy }, contentAlignment = Alignment.Center) {
                Symbol("ui-share", tint = Color.White, size = 16.dp)
            }
            val close = stringResource(R.string.kit_close)
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable(onClick = onClose).semantics { contentDescription = close }, contentAlignment = Alignment.Center) {
                Symbol("ui-close", tint = Color.White, size = 16.dp)
            }
        }
        SelectionContainer {
            Text(ql, Modifier.padding(top = 8.dp), style = Atlas.type.mono.copy(fontSize = 11.sp, lineHeight = 15.sp), color = Color(0xFFB8E0B0))
        }
    }
}

/** The board's dark wood: #3B2A1E with grain stripes repeating every 11 px, turned 4° off the vertical. */
private fun DrawScope.drawWood() {
    val k = density
    val period = 11 * k
    val tilt = size.height * kotlin.math.tan(Math.toRadians(4.0)).toFloat()
    var x = -tilt
    while (x < size.width + tilt) {
        fun band(from: Float, to: Float, c: Color) {
            val p = Path().apply {
                moveTo(x + from * k, 0f); lineTo(x + to * k, 0f); lineTo(x + to * k + tilt, size.height); lineTo(x + from * k + tilt, size.height); close()
            }
            drawPath(p, c)
        }
        band(0f, 2f, Color(0x1F000000))
        band(2f, 5f, Color(0x08FFFFFF))
        band(5f, 11f, Color(0x0F000000))
        x += period
    }
}

/** The check's ruling: a blue line every 40 px, the red margin at 50 px and the count column's rule 58 px from the right. */
private fun DrawScope.drawRules() {
    val step = ROW.toPx()
    var y = 6.dp.toPx() + step - 1.dp.toPx()
    while (y < size.height) {
        drawLine(RULE, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        y += step
    }
    drawRect(MARGIN, Offset(GUTTER.toPx(), 0f), Size(1.5.dp.toPx(), size.height))
    drawRect(Color(0xFFE8DCC8), Offset(size.width - COUNT.toPx(), 0f), Size(1.dp.toPx(), size.height))
}

private fun wishGlyph(w: Wish): BoardGlyph? = when (w) {
    Wish.DOGS -> EatGlyphs.paw
    Wish.QUIET -> EatGlyphs.quiet
    else -> null
}

private fun lineName(l: Line): Int = when (l) {
    Line.TIME -> R.string.eat_line_time
    Line.CUISINES -> R.string.eat_line_cuisines
    Line.NOT -> R.string.eat_line_not
    Line.FEATURES -> R.string.eat_line_features
    Line.ACCESS -> R.string.eat_line_access
    Line.WISHES -> R.string.eat_line_wishes
    Line.PAYMENT -> R.string.eat_line_payment
    Line.WALK -> R.string.eat_line_walk
}

private fun wishName(w: Wish): Int = when (w) {
    Wish.DOGS -> R.string.eat_wish_dogs
    Wish.QUIET -> R.string.eat_wish_quiet
    Wish.KIDS -> R.string.eat_wish_kids
    Wish.TAKEAWAY -> R.string.eat_wish_takeaway
    Wish.RESERVABLE -> R.string.eat_wish_reservable
}

private fun rankName(k: RankKey): Int = when (k) {
    RankKey.RATING -> R.string.eat_rank_rating
    RankKey.WALK -> R.string.eat_rank_walk
    RankKey.TRENDING -> R.string.eat_rank_trending
    RankKey.LATE -> R.string.eat_rank_late
    RankKey.NEW -> R.string.eat_rank_new
    RankKey.PRICE -> R.string.eat_rank_price
}
