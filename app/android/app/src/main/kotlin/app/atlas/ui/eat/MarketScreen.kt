package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/* The Market board's kraft, wood and green stamp (`canvas/project/Market.dc.html`). */
private val KRAFT = Color(0xFFD9BE92)
private val KRAFT_INK = Color(0xFF2E2214)
private val STAMP = Color(0xFF2F6B34)
private val CARD = Color(0xFFFFF8E8)
private val TAG = Color(0xFFE9D2A6)
private val BROWN = Color(0xFF6E5230)

/**
 * Markets and fresh food, as on the Market board: kraft paper under wooden planks, a green rubber stamp with the
 * count, price tags on strings for each kind of stall, the week's market days from the markets' opening hours, and
 * the stalls as typed paper slips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    var stall by rememberSaveable { mutableStateOf<Stall?>(null) }
    var checks by rememberSaveable { mutableStateOf(emptySet<MarketCheck>()) }
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val worked = rememberWorked(ctx.area, ctx.at, stall, checks) { MarketModel.board(ctx.area, ctx.at, stall, checks) }
    val board = worked.value
    val type = TextStyle(fontFamily = EatType.courier, color = KRAFT_INK)

    Box(Modifier.fillMaxSize().background(KRAFT).drawBehind { drawKraft() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = boardTop(112.dp)), contentAlignment = Alignment.Center) {
                    RubberStamp(stringResource(R.string.eat_market).uppercase() + " · " + (board?.count?.toString() ?: "…"))
                }
            }
            item {
                TagStrings(board?.stalls.orEmpty(), stall, Modifier.padding(top = 10.dp, start = (-6).dp, end = (-6).dp)) { s -> stall = if (stall == s) null else s }
            }
            item {
                board?.let { b ->
                    val chosenDay = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: ctx.at.toLocalDate()
                    MarketDays(b.week, chosenDay, ctx.at.toLocalDate(), ctx.lang, Modifier.padding(top = 12.dp)) { d -> day = d.toString() }
                }
            }
            item {
                FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in MarketCheck.entries) {
                        val n = board?.checks?.get(c) ?: 0
                        CheckBox(stringResource(marketCheckName(c)), on = c in checks, enabled = n > 0, style = type) { checks = if (c in checks) checks - c else checks + c }
                    }
                }
            }
            itemsIndexed(board?.rows.orEmpty(), key = { _, r -> r.place.key }) { i, r -> PaperSlip(r, i, ctx, type, Modifier.padding(top = 8.dp)) { ctx.open(nav, r.place.place) } }
            item {
                if (board != null && board.rows.isEmpty() && !ctx.area.loading) {
                    Text(
                        stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_market_none),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp), style = type.copy(fontSize = 14.sp), textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(KRAFT_INK.copy(alpha = 0.6f))
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let { Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = type.copy(fontSize = 11.sp, color = KRAFT_INK.copy(alpha = 0.6f)), textAlign = TextAlign.Center) }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(boardTop(96.dp))) { drawPlanks() }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = boardTop(42.dp))) {
            KraftButton(EatGlyphs.backBold, stringResource(R.string.kit_back), 20) { nav.back() }
            Spacer(Modifier.weight(1f))
            Box {
                KraftButton(EatGlyphs.pad, stringResource(R.string.eat_pad), 19) { nav.go(Dest.Eat("order")) }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        Text(pad.size.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
                    }
                }
            }
        }
    }
}

/** A cream round button over the planks (44 px, a 0 2 6 shadow). */
@Composable
private fun KraftButton(glyph: BoardGlyph, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .boxShadow(CircleShape, 1f, 2f, 6f, Color(0x40000000))
            .clip(CircleShape)
            .background(Color(0xFFF3E6CC))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(glyph, KRAFT_INK, sizeDp.dp) }
}

/** The green rubber stamp, turned −2°: a 3 px border, a 2 px inner ring at 35 %, Special Elite at 90 %. */
@Composable
private fun RubberStamp(text: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .rotate(-2f)
            .alpha(0.9f)
            .border(3.dp, STAMP, shape)
            .innerShadow(shape, BoxShadow(radius = 0.dp, spread = 5.dp, color = Color(0x592F6B34)))
            .innerShadow(shape, BoxShadow(radius = 0.dp, spread = 3.dp, color = KRAFT))
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 4.dp),
    ) {
        Text(text, Modifier.semantics { heading() }, style = TextStyle(fontFamily = EatType.specialElite, fontSize = 34.sp, letterSpacing = 0.08.em, color = STAMP))
    }
}

/** The tags' outline (88 × 82): the top corners cut at 16 px, as the board's `clip-path`. */
private val TagCut: Shape = GenericShape { size, _ ->
    val c = 16 * (size.width / 88f)
    moveTo(c, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c); lineTo(size.width, size.height); lineTo(0f, size.height); lineTo(0f, c); close()
}

/** Two strings of price tags: each a stall kind with its board glyph and count; tilts as on the board. */
@Composable
private fun TagStrings(counts: Map<Stall, Int>, chosen: Stall?, modifier: Modifier, onTag: (Stall) -> Unit) {
    val tilts = floatArrayOf(-3f, 2f, -1f, 3f, 2f, -2f, 1f, -3f)
    Box(modifier.fillMaxWidth().height(188.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val k = size.width / 412f
            for (y in listOf(10f, 104f)) {
                val p = Path().apply { moveTo(-4 * k, y * density); cubicTo(100 * k, (y + 24) * density, 300 * k, (y + 24) * density, 416 * k, y * density) }
                drawPath(p, BROWN, style = Stroke(1.6f * density))
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((r, row) in Stall.entries.chunked(4).withIndex()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEachIndexed { i, s -> PriceTag(s, counts[s] ?: 0, on = chosen == s, tilt = tilts[r * 4 + i]) { onTag(s) } }
                }
            }
        }
    }
}

@Composable
private fun PriceTag(s: Stall, n: Int, on: Boolean, tilt: Float, onClick: () -> Unit) {
    val name = stringResource(stallName(s))
    val ink = (if (on) STAMP else KRAFT_INK).copy(alpha = if (n > 0 || on) 1f else 0.45f)
    Column(
        Modifier
            .size(88.dp, 82.dp)
            .rotate(tilt)
            .dropShadow(TagCut, BoxShadow(radius = 1.5.dp, color = Color(0x33000000), offset = DpOffset(0.dp, 3.dp)))
            .clip(TagCut)
            .background(if (on) CARD else TAG)
            .selectable(on, enabled = n > 0 || on, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "$name $n" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(KRAFT).innerShadow(CircleShape, BoxShadow(radius = 1.dp, color = Color(0x66000000), offset = DpOffset(0.dp, 1.dp))))
        val glyph = stallGlyph(s)
        if (glyph.viewBox > 24f) {
            Canvas(Modifier.size(26.dp, 24.dp)) { drawBoardGlyph(glyph, ink, side = size.width) }
        } else {
            BoardIcon(glyph, ink, 24.dp)
        }
        Text("$name $n", style = TextStyle(fontFamily = EatType.courier, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ink), maxLines = 1)
    }
}

/** "MARKET DAYS": Monday to Sunday, a green dot on market days, today a green pill with its date; the day's markets. */
@Composable
private fun MarketDays(week: List<MarketDay>, chosen: LocalDate, today: LocalDate, lang: String, modifier: Modifier, onDay: (LocalDate) -> Unit) {
    val type = TextStyle(fontFamily = EatType.courier, color = KRAFT_INK)
    val locale = Locale.forLanguageTag(lang)
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.fillMaxWidth().boxShadow(shape, 1f, 6f, 16f, Color(0x383C280A)).clip(shape).background(CARD).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.eat_market_days).uppercase(), style = TextStyle(fontFamily = EatType.specialElite, fontSize = 16.sp, letterSpacing = 0.1.em, color = BROWN))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in week) {
                val isToday = d.date == today
                val on = d.date == chosen
                val market = d.sessions.isNotEmpty()
                val label = d.date.dayOfWeek.getDisplayName(DateTextStyle.NARROW, locale)
                val dayShape = if (isToday) RoundedCornerShape(22.dp) else RoundedCornerShape(6.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(dayShape)
                        .background(if (isToday) STAMP else Color(0xFFF1E4C8))
                        .then(if (on && !isToday) Modifier.border(1.5.dp, STAMP, dayShape) else Modifier)
                        .selectable(on, role = Role.Tab) { onDay(d.date) }
                        .semantics { contentDescription = d.date.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale) + if (market) ", market" else "" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val ink = when {
                        isToday -> Color.White
                        market -> KRAFT_INK
                        else -> Color(0xFF8A7658)
                    }
                    Text(label, style = type.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink))
                    when {
                        isToday -> Text(d.date.dayOfMonth.toString(), style = type.copy(fontSize = 10.sp, color = ink))
                        market -> Box(Modifier.size(6.dp).clip(CircleShape).background(STAMP))
                    }
                }
            }
        }
        val sessions = week.firstOrNull { it.date == chosen }?.sessions.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.eat_market_none_on, chosen.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale)), style = type.copy(fontSize = 15.sp, color = KRAFT_INK.copy(alpha = 0.6f)))
            }
            for (s in sessions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${hour(s.from)}–${hour(s.to)}", Modifier.width(64.dp), style = type.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Column {
                        Text(s.place.name, style = type.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.what?.let { Text(it, style = type.copy(fontSize = 12.sp, color = KRAFT_INK.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** "8" for 8:00, "9:30" otherwise, as the board writes market hours. */
private fun hour(t: LocalTime): String = if (t.minute == 0) t.hour.toString() else t.format(HM)

/** A checkbox as the board's (20 px, green when ticked) and its typed label. */
@Composable
private fun CheckBox(text: String, on: Boolean, enabled: Boolean, style: TextStyle, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.4f
    Row(
        Modifier.height(36.dp).toggleable(on, enabled = enabled || on, role = Role.Checkbox) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (on) STAMP else Color.White.copy(alpha = alpha))
                .then(if (on) Modifier else Modifier.border(1.dp, Color(0xFF767676).copy(alpha = alpha), RoundedCornerShape(3.dp))),
            contentAlignment = Alignment.Center,
        ) { if (on) BoardIcon(EatGlyphs.tickBox, Color.White, 16.dp) }
        Text(text, style = style.copy(fontSize = 16.sp, color = KRAFT_INK.copy(alpha = alpha)))
    }
}

/** A typed paper slip (56 px, a little askew): the stall in bold, a dotted leader, the walk; closed ones at 70 % with their next day. */
@Composable
private fun PaperSlip(r: StallRow, index: Int, ctx: EatContext, type: TextStyle, modifier: Modifier, onClick: () -> Unit) {
    val closed = r.openness == Openness.CLOSED
    val tilts = floatArrayOf(-0.6f, 0.5f, -0.3f)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .rotate(tilts[index % tilts.size])
            .alpha(if (closed) 0.7f else 1f)
            .boxShadow(RectangleShape, 1f, 3f, 8f, Color(0x333C280A))
            .background(CARD)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        LeaderLine(Color(0xFFB59B73), Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp, lift = 1.dp, start = {
            Text(r.place.name, style = type.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }) {
            val end = if (closed && r.opens != null && r.opens.toLocalDate() != ctx.at.toLocalDate()) {
                r.opens.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.forLanguageTag(ctx.lang)).take(2)
            } else "${r.place.walkMinutes}′"
            Text(end, style = type.copy(fontSize = 17.sp))
        }
    }
}

/** Kraft paper: #D9BE92 with the board's two fibre patterns, 1 px lines every 5 px at 12° and every 7 px at −8°. */
private fun DrawScope.drawKraft() {
    fun lines(step: Float, degrees: Double, c: Color) {
        val t = kotlin.math.tan(Math.toRadians(degrees)).toFloat()
        val s = step * density
        var y = -size.width * kotlin.math.abs(t)
        while (y < size.height + size.width * kotlin.math.abs(t)) {
            drawLine(c, Offset(0f, y), Offset(size.width, y - size.width * t), 1 * density)
            y += s
        }
    }
    lines(5f, 12.0, Color(0x0F785A32))
    lines(7f, -8.0, Color(0x14FFFFFF))
}

/** Three wooden planks across the top (30 px each, 3 px apart), each darker at its foot. */
private fun DrawScope.drawPlanks() {
    val k = density
    val bottom = size.height
    val tones = listOf(Color(0xFFA8783F) to Color(0xFF8E6130), Color(0xFFB08244) to Color(0xFF926533), Color(0xFFA8783F) to Color(0xFF8A5E2E))
    // The planks hang from the top; on a tall status bar the first grows.
    val first = bottom - 3 * (30 * k) - 2 * (3 * k) + 30 * k
    var top = 0f
    tones.forEachIndexed { i, (a, b) ->
        val h = if (i == 0) first else 30 * k
        drawRect(Brush.verticalGradient(listOf(a, b), top, top + h), Offset(0f, top), Size(size.width, h))
        drawRect(Color(if (i == 2) 0x40000000 else 0x33000000), Offset(0f, top + h - 2 * k), Size(size.width, 2 * k))
        top += h + 3 * k
    }
    drawRect(Brush.verticalGradient(listOf(Color(0x403C280A), Color(0x003C280A)), bottom, bottom + 8 * k), Offset(0f, bottom), Size(size.width, 8 * k))
}

private fun stallGlyph(s: Stall): BoardGlyph = when (s) {
    Stall.PRODUCE -> EatGlyphs.produce
    Stall.BUTCHER -> EatGlyphs.butcher
    Stall.FISH -> EatGlyphs.fish
    Stall.CHEESE -> EatGlyphs.cheese
    Stall.DELI -> EatGlyphs.deli
    Stall.BREAD -> EatGlyphs.bread
    Stall.WINE -> EatGlyphs.wine
    Stall.FARM -> EatGlyphs.farm
}

private fun stallName(s: Stall): Int = when (s) {
    Stall.PRODUCE -> R.string.eat_stall_produce
    Stall.BUTCHER -> R.string.eat_stall_butcher
    Stall.FISH -> R.string.eat_stall_fish
    Stall.CHEESE -> R.string.eat_stall_cheese
    Stall.DELI -> R.string.eat_stall_deli
    Stall.BREAD -> R.string.eat_stall_bread
    Stall.WINE -> R.string.eat_bar_wine
    Stall.FARM -> R.string.eat_stall_farm
}

private fun marketCheckName(c: MarketCheck): Int = when (c) {
    MarketCheck.ORGANIC -> R.string.eat_market_organic
    MarketCheck.SUNDAY -> R.string.eat_market_sunday
    MarketCheck.JAR -> R.string.eat_market_jar
    MarketCheck.CARD -> R.string.eat_order_card
}
