package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import app.atlas.ui.kit.cssRadial

/* The Cafe board's chalk and slate (`canvas/project/Cafe.dc.html`). */
private val CHALK = Color(0xFFF2F0E6)
private val CHALK_YELLOW = Color(0xFFF4E48A)
private val CHALK_GREEN = Color(0xFFCFE8C9)
private val SLATE = Color(0xFF2B302C)
private val FRAME = 12.dp

/* The board's Caveat as its page loads it (500 to 700, so its 400 draws at 500). */
private const val CAVEAT = "Caveat"
private val TITLE = cssText(CAVEAT, 54f, 700, colour = 0xFFF2F0E6, lineHeight = 48.6f, shadow = CssShadow(0f, 0f, 1f, 0f, 0x99FFFFFF))
private val COUNT = cssText(CAVEAT, 22f, 500, colour = 0xFFCFE8C9, lineHeight = 27.5f, tnum = true)
private val WORD = cssText(CAVEAT, 26f, 600, lineHeight = 33f)
private val NAME = cssText(CAVEAT, 30f, 700, colour = 0xFFF2F0E6, lineHeight = 31.5f)
private val WALK = cssText(CAVEAT, 26f, 700, colour = 0xFFF2F0E6, lineHeight = 27.3f, tnum = true)
private val FACTS = cssText(CAVEAT, 20f, 500, colour = 0xB8F2F0E6, lineHeight = 25f)
private val SPECIAL = cssText(CAVEAT, 20f, 500, colour = 0xFFF4E48A, lineHeight = 25f)
private val SPECIAL_NAME = cssText(CAVEAT, 26f, 700, colour = 0xFFF2F0E6, lineHeight = 26f)

/**
 * The café, as on the Cafe board: a slate in a wooden frame. Chalk words pick coffee, tea, bubble tea, bakery or
 * brunch; chalk icons ask for wifi, quiet, a terrace, vegan, early or takeaway; the list is handwritten with dotted
 * leaders to the walk; the box at the foot is today's special, from the opening hours.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CafeScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    LightStatusIcons()
    var kind by rememberSaveable { mutableStateOf<CafeKind?>(null) }
    var toggles by rememberSaveable { mutableStateOf(emptySet<CafeToggle>()) }
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val worked = rememberWorked(ctx.area, ctx.at, kind, toggles) { CafeModel.board(ctx.area, ctx.at, kind, toggles) }
    val board = worked.value
    val slateShape = RoundedCornerShape(30.dp)

    Box(Modifier.fillMaxSize().drawBehind { drawRect(cssLinear(135f, 0f to 0xFF9A6739, 0.5f to 0xFF7A4D2A, 1f to 0xFF8E5C33).brush(size, density)) }) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(FRAME)
                .clip(slateShape)
                .drawBehind { drawSlate() }
                .innerShadow(slateShape, BoxShadow(radius = 26.dp, color = Color(0x99000000)))
                .innerShadow(slateShape, BoxShadow(radius = 0.dp, spread = 2.dp, color = Color(0x66000000))),
        ) {
            // The board's column: 28 px in from the screen's sides, from 40 px down, 14 px between its parts.
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = bottomRoom(150.dp)),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = boardTop(40.dp) - FRAME), verticalAlignment = Alignment.CenterVertically) {
                        ChalkRing("ui.back", stringResource(R.string.kit_back), 20) { nav.back() }
                        Spacer(Modifier.weight(1f))
                        Box {
                            ChalkRing("ui.order", stringResource(R.string.eat_pad), 19) { nav.go(Dest.Eat("order")) }
                            if (pad.isNotEmpty()) {
                                Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                                    Text(pad.size.toString(), style = cssText("Geist", 10f, 700, colour = 0xFFFFFFFF, lineHeight = 13f))
                                }
                            }
                        }
                    }
                }
                // The title on its 48.6 px line, the swash (220 × 18) and the count.
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CssLine(stringResource(R.string.eat_cafe_title), TITLE, Modifier.semantics { heading() })
                        Canvas(Modifier.size(220.dp, 18.dp)) { swash() }
                        val count = board?.let { b ->
                            when {
                                !b.countsOpen -> stringResource(R.string.eat_carte_places, b.open)
                                ctx.table.time == null -> stringResource(R.string.eat_cafe_open_now, b.open)
                                else -> stringResource(R.string.eat_cafe_open_at, b.open, ctx.at.format(HM))
                            }
                        } ?: stringResource(R.string.kit_loading)
                        CssLine(count, COUNT)
                    }
                }
                // The words: 33 px lines, 16 px apart, 2 px between lines.
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (k in CafeKind.entries) {
                            val n = board?.kindCounts?.get(k) ?: 0
                            ChalkWord(stringResource(cafeKindName(k)), on = kind == k, enabled = n > 0) { kind = if (kind == k) null else k }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (t in CafeToggle.entries) {
                            val n = board?.toggleCounts?.get(t) ?: 0
                            ChalkToggle(t, stringResource(cafeToggleName(t)), on = t in toggles, enabled = n > 0) {
                                toggles = if (t in toggles) toggles - t else toggles + t
                            }
                        }
                    }
                }
                // The chalk rule (356 × 10): `M2 5C80 2 200 8 354 4`, 1.5 px at 35 %.
                item {
                    Canvas(Modifier.padding(top = 14.dp).fillMaxWidth().height(10.dp)) {
                        val k = size.width / 356f
                        val p = Path().apply { moveTo(2 * k, 5 * density); cubicTo(80 * k, 2 * density, 200 * k, 8 * density, 354 * k, 4 * density) }
                        drawPath(p, CHALK.copy(alpha = 0.35f), style = Stroke(1.5f * density, cap = StrokeCap.Round))
                    }
                }
                itemsIndexed(board?.lines.orEmpty(), key = { _, l -> l.place.key }) { i, line ->
                    ChalkLine(line, ctx, Modifier.padding(top = if (i == 0) 14.dp else 12.dp)) { ctx.open(nav, line.place.place) }
                }
                item {
                    if (board != null && board.lines.isEmpty() && !ctx.area.loading) {
                        Text(
                            stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_cafe_none),
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            style = FACTS.copy(fontSize = 22.sp, lineHeight = 27.5.sp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (ctx.area.waiting || !worked.fresh) Looking(CHALK.copy(alpha = 0.72f))
                    if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                    sourceLine(ctx.area)?.let {
                        Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = FACTS.copy(fontSize = 16.sp, lineHeight = 20.sp, color = CHALK.copy(alpha = 0.5f)), textAlign = TextAlign.Center)
                    }
                }
            }
            board?.special?.let { s ->
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp)
                        .background(Brush.verticalGradient(listOf(SLATE.copy(alpha = 0f), SLATE.copy(alpha = 0.95f), SLATE))),
                )
                SpecialBox(
                    s,
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 28.dp, end = 28.dp, bottom = 24.dp),
                ) { ctx.open(nav, s.place.place) }
            }
        }
    }
}

/** A chalk ring round a board symbol (44 px, inset 2 px of chalk at 55 %). */
@Composable
private fun ChalkRing(symbol: String, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).cssBox(r = round(44f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0x8CF2F0E6, inset = true)))
            .clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = CHALK, size = sizeDp.dp) }
}

/** A category in chalk script (26 px, 600); the chosen one yellow, 8 px either side, ringed with the board's own loop. */
@Composable
private fun ChalkWord(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .selectable(on, enabled = enabled || on, role = Role.Tab, onClick = onClick)
            .drawWithContent {
                drawContent()
                if (on) ring()
            }
            .padding(horizontal = if (on) 8.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        val colour = if (on) CHALK_YELLOW else CHALK.copy(alpha = if (enabled) 0.8f else 0.35f)
        CssLine(text, WORD, color = colour)
    }
}

/**
 * The board's chalk loop round the chosen word: `M10 26C4 12 40 2 70 6s22 22 0 30-58 4-62-8` on its 92 × 44 box at
 * (-6, -4), 2 px; a longer or shorter word than the board's "Coffee" stretches it across.
 */
private fun DrawScope.ring() {
    val sx = (size.width + 24.09f * density) / 92f
    val sy = density
    val ox = -6.dp.toPx()
    val oy = -4.dp.toPx()
    val p = Path().apply {
        moveTo(ox + 10 * sx, oy + 26 * sy)
        cubicTo(ox + 4 * sx, oy + 12 * sy, ox + 40 * sx, oy + 2 * sy, ox + 70 * sx, oy + 6 * sy)
        cubicTo(ox + 100 * sx, oy + 10 * sy, ox + 92 * sx, oy + 28 * sy, ox + 70 * sx, oy + 36 * sy)
        cubicTo(ox + 48 * sx, oy + 44 * sy, ox + 12 * sx, oy + 40 * sy, ox + 8 * sx, oy + 28 * sy)
    }
    drawPath(p, CHALK_YELLOW, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}

/** A chalk icon toggle (46 px, the symbol 26): yellow when on, chalk at 55 % when off, faint when nothing has it. */
@Composable
private fun ChalkToggle(t: CafeToggle, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colour = when {
        on -> CHALK_YELLOW
        enabled -> CHALK.copy(alpha = 0.55f)
        else -> CHALK.copy(alpha = 0.22f)
    }
    Box(
        Modifier.size(46.dp).toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(cafeToggleSymbol(t), tint = colour, size = 26.dp) }
}

/**
 * "Low Tide Coffee ✦ ······ 4′" on the board's 36.5 px row lined up on its baseline (the name's line 1.25 px down, the
 * marks 9, the dotted band 25.4, the walk's line 4.85), and under it "★ 4.8 · wifi · terrace · → 18:00"; closed
 * ones at 55 %.
 */
@Composable
private fun ChalkLine(line: CafeLine, ctx: EatContext, modifier: Modifier, onClick: () -> Unit) {
    val p = line.place
    val closed = line.openness == Openness.CLOSED
    Column(modifier.fillMaxWidth().alpha(if (closed) 0.55f else 1f).clickable(onClick = onClick)) {
        LeaderLine(
            CHALK.copy(alpha = 0.4f), Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp,
            height = 36.5.dp, startTop = 0.dp, endTop = 4.85.dp, dotsTop = 25.41.dp,
            start = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CssLine(p.name, NAME, Modifier.weight(1f, fill = false).padding(top = 1.25.dp))
                    if (line.isNew) Symbol("fact.new", Modifier.padding(top = 9.dp), tint = CHALK_YELLOW, size = 18.dp, contentDescription = stringResource(R.string.eat_tab_new))
                    if (CafeToggle.VEGAN in line.has) Symbol("fact.vegan.2", Modifier.padding(top = 9.dp), tint = CHALK_GREEN, size = 18.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                }
            },
        ) {
            CssLine("${p.walkMinutes}′", WALK)
        }
        val facts = buildList {
            ctx.ratings.stars(p.key)?.let { add("★ " + starsText(it)) }
            if (CafeToggle.WIFI in line.has) add(stringResource(R.string.eat_fact_wifi))
            if (CafeToggle.TERRACE in line.has) add(stringResource(R.string.eat_fact_terrace))
            if (CafeToggle.TAKEAWAY in line.has) add(stringResource(R.string.eat_fact_takeaway))
            openWords(p, ctx)?.first?.let { add(it) }
        }
        if (facts.isNotEmpty() && !closed) {
            CssLine(facts.joinToString(" · "), FACTS)
        }
    }
}

/** Today's special: 92 px, turned -1.5°, ruled 2.5 px of yellow chalk at 75 % with 12 px corners; a cup, the words and an arrow. */
@Composable
private fun SpecialBox(s: Special, modifier: Modifier, onClick: () -> Unit) {
    val what = when {
        s.brunch && s.until != null -> stringResource(R.string.eat_special_brunch_till, s.until.format(HM))
        s.brunch -> stringResource(R.string.eat_special_brunch)
        s.until != null -> stringResource(R.string.eat_special_open_till, s.until.format(HM))
        else -> ""
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(92.dp)
            .rotate(-1.5f)
            .cssBox(r = corners(12f), border = CssBorder(2.5f, 2.5f, 2.5f, 2.5f, longArrayOf(0xBFF4E48A, 0xBFF4E48A, 0xBFF4E48A, 0xBFF4E48A)))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol("food.coffee", tint = CHALK_YELLOW, size = 36.dp)
        Column(Modifier.weight(1f)) {
            CssLine(stringResource(R.string.eat_special), SPECIAL)
            Text("${s.place.name} — $what", style = SPECIAL_NAME, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Symbol("ui.arrow-right.2", tint = CHALK, size = 28.dp)
    }
}

/** The slate: #2B302C with the board's three faint patches of old chalk (ellipses at 20 % 15 %, 80 % 60 % and 40 % 90 %). */
private fun DrawScope.drawSlate() {
    drawRect(SLATE)
    val w = size.width / density
    val h = size.height / density
    drawRect(cssRadial(false, w * 0.2f, h * 0.15f, 0f to 0x12FFFFFF, 0.4f to 0x00FFFFFF).brush(size, density))
    drawRect(cssRadial(false, w * 0.8f, h * 0.6f, 0f to 0x0DFFFFFF, 0.45f to 0x00FFFFFF).brush(size, density))
    drawRect(cssRadial(false, w * 0.4f, h * 0.9f, 0f to 0x0AFFFFFF, 0.4f to 0x00FFFFFF).brush(size, density))
}

/** The swash under the title: two yellow strokes, as the board draws them on 220 × 18. */
private fun DrawScope.swash() {
    val k = size.width / 220f
    val a = Path().apply { moveTo(4 * k, 10 * k); cubicTo(50 * k, 4 * k, 120 * k, 14 * k, 216 * k, 6 * k) }
    val b = Path().apply { moveTo(30 * k, 14 * k); cubicTo(80 * k, 10 * k, 140 * k, 16 * k, 190 * k, 12 * k) }
    drawPath(a, CHALK_YELLOW.copy(alpha = 0.8f), style = Stroke(3 * k, cap = StrokeCap.Round))
    drawPath(b, CHALK_YELLOW.copy(alpha = 0.45f), style = Stroke(1.5f * k, cap = StrokeCap.Round))
}

private fun cafeKindName(k: CafeKind): Int = when (k) {
    CafeKind.COFFEE -> R.string.eat_cafe_coffee
    CafeKind.TEA -> R.string.eat_cafe_tea
    CafeKind.BUBBLE_TEA -> R.string.eat_cafe_bubble_tea
    CafeKind.BAKERY -> R.string.eat_cafe_bakery
    CafeKind.BRUNCH -> R.string.eat_cafe_brunch
}

private fun cafeToggleName(t: CafeToggle): Int = when (t) {
    CafeToggle.WIFI -> R.string.eat_fact_wifi
    CafeToggle.QUIET -> R.string.eat_fact_quiet
    CafeToggle.TERRACE -> R.string.eat_fact_terrace
    CafeToggle.VEGAN -> R.string.eat_diet_vegan
    CafeToggle.EARLY -> R.string.eat_fact_early
    CafeToggle.TAKEAWAY -> R.string.eat_fact_takeaway
}

private fun cafeToggleSymbol(t: CafeToggle): String = when (t) {
    CafeToggle.WIFI -> "fact.wifi"
    CafeToggle.QUIET -> "fact.quiet"
    CafeToggle.TERRACE -> "fact.terrace"
    CafeToggle.VEGAN -> "fact.vegan"
    CafeToggle.EARLY -> "weather.sunrise"
    CafeToggle.TAKEAWAY -> "fact.takeaway-cup"
}
