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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRepeatingLinear
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

/* The board's type: Courier Prime (400, 700) typed, Special Elite stamped. */
private val STAMP_TEXT = cssText("Special Elite", 34f, colour = 0xFF2F6B34, lineHeight = 34f, spacing = 2.72f)
private val TAG_TEXT = cssText("Courier Prime", 12f, 700, lineHeight = 13.5f)
private val DAYS_TITLE = cssText("Special Elite", 16f, colour = 0xFF6E5230, lineHeight = 16.5f, spacing = 1.6f)
private val DAY_LETTER = cssText("Courier Prime", 13f, 700, lineHeight = 14.5f)
private val DAY_DATE = cssText("Courier Prime", 10f, lineHeight = 11.5f, tnum = true)
private val SESSION = cssText("Courier Prime", 15f, colour = 0xFF2E2214, lineHeight = 16.5f)
private val CHECK_TEXT = cssText("Courier Prime", 16f, lineHeight = 18f)
private val SLIP = cssText("Courier Prime", 17f, colour = 0xFF2E2214, lineHeight = 19f, tnum = true)

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
    val type = cssText("Courier Prime", 14f, colour = 0xFF2E2214, lineHeight = 16f)

    Box(Modifier.fillMaxSize().background(KRAFT).drawBehind { drawKraft() }) {
        // The board: the stamp at 112 px, the strings of tags from 176, the market days at 388, the checks 68.5 px
        // under them, the slips 14 px under the checks, 8 px apart.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomRoom(40.dp))) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = boardTop(112.dp)), contentAlignment = Alignment.Center) {
                    RubberStamp(stringResource(R.string.eat_market).uppercase() + " · " + (board?.count?.toString() ?: "…"))
                }
            }
            item {
                TagStrings(board?.stalls.orEmpty(), stall, Modifier.padding(top = 14.dp).bleed(6.dp)) { s -> stall = if (stall == s) null else s }
            }
            item {
                board?.let { b ->
                    val chosenDay = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: ctx.at.toLocalDate()
                    MarketDays(b.week, chosenDay, ctx.at.toLocalDate(), ctx.lang, Modifier.padding(top = 22.dp)) { d -> day = d.toString() }
                }
            }
            item {
                FlowRow(Modifier.fillMaxWidth().padding(top = 68.5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in MarketCheck.entries) {
                        val n = board?.checks?.get(c) ?: 0
                        CheckBox(stringResource(marketCheckName(c)), on = c in checks, enabled = n > 0) { checks = if (c in checks) checks - c else checks + c }
                    }
                }
            }
            itemsIndexed(board?.rows.orEmpty(), key = { _, r -> r.place.key }) { i, r -> PaperSlip(r, i, ctx, Modifier.padding(top = if (i == 0) 14.dp else 8.dp)) { ctx.open(nav, r.place.place) } }
            item {
                if (board != null && board.rows.isEmpty() && !ctx.area.loading) {
                    Text(
                        stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_market_none),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp), style = type, textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(KRAFT_INK.copy(alpha = 0.6f))
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let { Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = type.copy(fontSize = 11.sp, lineHeight = 13.sp, color = KRAFT_INK.copy(alpha = 0.6f)), textAlign = TextAlign.Center) }
            }
        }
        Planks(boardTop(96.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = boardTop(42.dp))) {
            KraftButton("ui.back", stringResource(R.string.kit_back), 20) { nav.back() }
            Spacer(Modifier.weight(1f))
            Box {
                KraftButton("ui.order", stringResource(R.string.eat_pad), 19) { nav.go(Dest.Eat("order")) }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        CssLine(pad.size.toString(), cssText("Geist", 10f, 700, colour = 0xFFFFFFFF, lineHeight = 13f))
                    }
                }
            }
        }
    }
}

/** A cream round button over the planks (44 px, #F3E6CC, a 0 2 6 shadow). */
@Composable
private fun KraftButton(symbol: String, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .cssBox(bg = 0xFFF3E6CC, r = round(44f), shadows = listOf(CssShadow(0f, 2f, 6f, 0f, 0x40000000)))
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = KRAFT_INK, size = sizeDp.dp) }
}

/**
 * The green rubber stamp, turned −2° at 90 %: a 3 px border with 6 px corners, a 2 px ring at 35 % inside it,
 * 6 18 4 px round "MARKET · 44" in Special Elite.
 */
@Composable
private fun RubberStamp(text: String) {
    Box(
        Modifier
            .rotate(-2f)
            .alpha(0.9f)
            .cssBox(r = corners(6f), border = CssBorder(3f, 3f, 3f, 3f, longArrayOf(0xFF2F6B34, 0xFF2F6B34, 0xFF2F6B34, 0xFF2F6B34)))
            .padding(3.dp)
            .cssBox(r = corners(3f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0x592F6B34, inset = true)))
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 4.dp),
    ) {
        CssLine(text, STAMP_TEXT, Modifier.semantics { heading() })
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
    Box(modifier.fillMaxWidth().height(190.dp)) {
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
    // The board's drop-shadow filter is cut away by its clip-path (CSS filters before it clips), so the tags lie flat.
    Column(
        Modifier
            .size(88.dp, 82.dp)
            .rotate(tilt)
            .clip(TagCut)
            .background(if (on) CARD else TAG)
            .selectable(on, enabled = n > 0 || on, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "$name $n" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(8.dp).cssBox(bg = 0xFFD9BE92, r = round(8f), shadows = listOf(CssShadow(0f, 1f, 2f, 0f, 0x66000000, inset = true))))
        val (symbol, w) = stallSymbol(s)
        BoxSymbol(symbol, w.toFloat(), 24f, ink)
        CssLine("$name $n", TAG_TEXT, color = ink)
    }
}

/** "MARKET DAYS": Monday to Sunday, a green dot on market days, today a green pill with its date; the day's markets. */
@Composable
private fun MarketDays(week: List<MarketDay>, chosen: LocalDate, today: LocalDate, lang: String, modifier: Modifier, onDay: (LocalDate) -> Unit) {
    val locale = Locale.forLanguageTag(lang)
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.fillMaxWidth().cssBox(bg = 0xFFFFF8E8, r = corners(6f), shadows = listOf(CssShadow(0f, 6f, 16f, 0f, 0x383C280A))).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CssLine(stringResource(R.string.eat_market_days).uppercase(), DAYS_TITLE)
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
                    CssLine(label, DAY_LETTER, color = ink)
                    when {
                        isToday -> CssLine(d.date.dayOfMonth.toString(), DAY_DATE, color = ink)
                        market -> Box(Modifier.size(6.dp).clip(CircleShape).background(STAMP))
                    }
                }
            }
        }
        val sessions = week.firstOrNull { it.date == chosen }?.sessions.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sessions.isEmpty()) {
                CssLine(stringResource(R.string.eat_market_none_on, chosen.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale)), SESSION, color = KRAFT_INK.copy(alpha = 0.6f))
            }
            for (s in sessions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The times never break ("10–18:30" stays whole); the name gives way.
                    Text("${hour(s.from)}–${hour(s.to)}", Modifier.widthIn(min = 64.dp), style = SESSION.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false)
                    Column(Modifier.weight(1f)) {
                        CssLine(s.place.name, SESSION)
                        s.what?.let { CssLine(it, SESSION.copy(fontSize = 12.sp, lineHeight = 13.5.sp), color = KRAFT_INK.copy(alpha = 0.7f)) }
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
private fun CheckBox(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
        CssLine(text, CHECK_TEXT, color = KRAFT_INK.copy(alpha = alpha))
    }
}

/**
 * A typed paper slip (56 px, a little askew, 14 px in): the stall in bold, a dotted leader (its band 32 px down, as the
 * board's `translateY(5px)`), the walk; closed ones at 70 % with their next day.
 */
@Composable
private fun PaperSlip(r: StallRow, index: Int, ctx: EatContext, modifier: Modifier, onClick: () -> Unit) {
    val closed = r.openness == Openness.CLOSED
    val tilts = floatArrayOf(-0.6f, 0.5f, -0.3f)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .rotate(tilts[index % tilts.size])
            .alpha(if (closed) 0.7f else 1f)
            .cssBox(bg = 0xFFFFF8E8, shadows = listOf(CssShadow(0f, 3f, 8f, 0f, 0x333C280A)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        LeaderLine(Color(0xFFB59B73), Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp, height = 56.dp, startTop = 18.5.dp, endTop = 18.5.dp, dotsTop = 32.dp, start = {
            CssLine(r.place.name, SLIP.copy(fontWeight = FontWeight.Bold))
        }) {
            val end = if (closed && r.opens != null && r.opens.toLocalDate() != ctx.at.toLocalDate()) {
                r.opens.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.forLanguageTag(ctx.lang)).take(2)
            } else "${r.place.walkMinutes}′"
            CssLine(end, SLIP)
        }
    }
}

/** Kraft paper: #D9BE92 with the board's two fibre patterns, 1 px lines every 5 px at 12° and every 7 px at −8°. */
private fun DrawScope.drawKraft() {
    drawRect(cssRepeatingLinear(12f, 5f, 0f to 0x0F785A32, 1f to 0x0F785A32, 1f to 0x00000000, 5f to 0x00000000).brush(size, density))
    drawRect(cssRepeatingLinear(-8f, 7f, 0f to 0x14FFFFFF, 1f to 0x14FFFFFF, 1f to 0x00000000, 7f to 0x00000000).brush(size, density))
}

/**
 * Three wooden planks across the top, 30 px each and 3 apart, each darker at its foot and edged inset 0 -2 0; the
 * last casts 0 4 8. They hang from the top, so on a taller status bar than the board's the first grows.
 */
@Composable
private fun Planks(bottom: Dp) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(bottom - 66.dp).cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFA8783F, 1f to 0xFF8E6130)), shadows = listOf(CssShadow(0f, -2f, 0f, 0f, 0x33000000, inset = true))))
        Box(Modifier.fillMaxWidth().height(30.dp).cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFB08244, 1f to 0xFF926533)), shadows = listOf(CssShadow(0f, -2f, 0f, 0f, 0x33000000, inset = true))))
        Box(Modifier.fillMaxWidth().height(30.dp).cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFA8783F, 1f to 0xFF8A5E2E)), shadows = listOf(CssShadow(0f, -2f, 0f, 0f, 0x40000000, inset = true), CssShadow(0f, 4f, 8f, 0f, 0x403C280A))))
    }
}

/** The board's symbol for a stall and its width (the bread and the fish are 26 px wide, the rest 24). */
private fun stallSymbol(s: Stall): Pair<String, Int> = when (s) {
    Stall.PRODUCE -> "market.produce" to 24
    Stall.BUTCHER -> "market.butcher" to 24
    Stall.FISH -> "market.fish" to 26
    Stall.CHEESE -> "market.cheese" to 24
    Stall.DELI -> "market.deli" to 24
    Stall.BREAD -> "food.bread" to 26
    Stall.WINE -> "market.wine" to 24
    Stall.FARM -> "market.farm" to 24
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
