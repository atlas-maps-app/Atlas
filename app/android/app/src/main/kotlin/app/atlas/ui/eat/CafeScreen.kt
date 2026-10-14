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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import kotlin.math.hypot

/* The Cafe board's chalk and slate (`canvas/project/Cafe.dc.html`). */
private val CHALK = Color(0xFFF2F0E6)
private val CHALK_YELLOW = Color(0xFFF4E48A)
private val CHALK_GREEN = Color(0xFFCFE8C9)
private val SLATE = Color(0xFF2B302C)
private val FRAME = 12.dp

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
    val chalk = TextStyle(fontFamily = EatType.hand, color = CHALK)
    val slateShape = RoundedCornerShape(30.dp)

    Box(Modifier.fillMaxSize().drawBehind { drawFrame() }) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(FRAME)
                .clip(slateShape)
                .drawBehind { drawSlate() }
                .innerShadow(slateShape, BoxShadow(radius = 15.dp, color = Color(0x99000000)))
                .innerShadow(slateShape, BoxShadow(radius = 0.dp, spread = 2.dp, color = Color(0x66000000))),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChalkRing(EatGlyphs.backBold, stringResource(R.string.kit_back), 20) { nav.back() }
                        Spacer(Modifier.weight(1f))
                        Box {
                            ChalkRing(EatGlyphs.pad, stringResource(R.string.eat_pad), 19) { nav.go(Dest.Eat("order")) }
                            if (pad.isNotEmpty()) {
                                Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                                    Text(pad.size.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
                                }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.eat_cafe_title),
                            Modifier.semantics { heading() },
                            style = chalk.copy(fontWeight = FontWeight.Bold, fontSize = 54.sp, lineHeight = 50.sp, shadow = Shadow(Color(0x99FFFFFF), blurRadius = 2f)),
                        )
                        Canvas(Modifier.size(220.dp, 18.dp)) { swash() }
                        val count = board?.let { b ->
                            when {
                                !b.countsOpen -> stringResource(R.string.eat_carte_places, b.open)
                                ctx.table.time == null -> stringResource(R.string.eat_cafe_open_now, b.open)
                                else -> stringResource(R.string.eat_cafe_open_at, b.open, ctx.at.format(HM))
                            }
                        } ?: stringResource(R.string.kit_loading)
                        Text(count, style = chalk.copy(fontSize = 22.sp, color = CHALK_GREEN))
                    }
                }
                item {
                    FlowRow(
                        Modifier.fillMaxWidth(),
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (t in CafeToggle.entries) {
                            val n = board?.toggleCounts?.get(t) ?: 0
                            ChalkToggle(t, stringResource(cafeToggleName(t)), on = t in toggles, enabled = n > 0) {
                                toggles = if (t in toggles) toggles - t else toggles + t
                            }
                        }
                    }
                }
                item {
                    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        val k = size.width / 356f
                        val p = Path().apply { moveTo(2 * k, 5 * density); cubicTo(80 * k, 2 * density, 200 * k, 8 * density, 354 * k, 4 * density) }
                        drawPath(p, CHALK.copy(alpha = 0.35f), style = Stroke(1.5f * density, cap = StrokeCap.Round))
                    }
                }
                items(board?.lines.orEmpty(), key = { it.place.key }) { line ->
                    ChalkLine(line, ctx) { ctx.open(nav, line.place.place) }
                }
                item {
                    if (board != null && board.lines.isEmpty() && !ctx.area.loading) {
                        Text(
                            stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_cafe_none),
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            style = chalk.copy(fontSize = 22.sp, color = CHALK.copy(alpha = 0.72f)),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (ctx.area.waiting || !worked.fresh) Looking(CHALK.copy(alpha = 0.72f))
                    if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                    sourceLine(ctx.area)?.let {
                        Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = chalk.copy(fontSize = 16.sp, color = CHALK.copy(alpha = 0.5f)), textAlign = TextAlign.Center)
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

/** A chalk ring round a glyph (44 px, inset 2 px of chalk at 55 %). */
@Composable
private fun ChalkRing(glyph: BoardGlyph, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(2.dp, CHALK.copy(alpha = 0.55f), CircleShape).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(glyph, CHALK, sizeDp.dp) }
}

/** A category in chalk script (26 px); the chosen one yellow, ringed with the board's own loop. */
@Composable
private fun ChalkWord(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .selectable(on, enabled = enabled || on, role = Role.Tab, onClick = onClick)
            .drawBehind { if (on) ring() }
            .padding(horizontal = if (on) 8.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val colour = if (on) CHALK_YELLOW else CHALK.copy(alpha = if (enabled) 0.8f else 0.35f)
        Text(text, style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = colour))
    }
}

/** The board's chalk loop round the chosen word: `M10 26C4 12 40 2 70 6s22 22 0 30-58 4-62-8` on a 92 × 44 box. */
private fun DrawScope.ring() {
    val sx = (size.width + 12.dp.toPx()) / 92f
    val sy = (size.height + 8.dp.toPx()) / 44f
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

/** A chalk icon toggle (46 px, the glyph 26): yellow when on, chalk at 55 % when off, faint when nothing has it. */
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
    ) { BoardIcon(cafeToggleGlyph(t), colour, 26.dp) }
}

/** "Low Tide Coffee ✦ ······ 4′" and under it "★ 4.8 · wifi · terrace · → 18:00"; closed ones at 55 %. */
@Composable
private fun ChalkLine(line: CafeLine, ctx: EatContext, onClick: () -> Unit) {
    val p = line.place
    val closed = line.openness == Openness.CLOSED
    val name = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 32.sp, color = CHALK)
    Column(Modifier.fillMaxWidth().alpha(if (closed) 0.55f else 1f).clickable(onClick = onClick)) {
        LeaderLine(CHALK.copy(alpha = 0.4f), Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp, lift = 6.dp, start = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, Modifier.weight(1f, fill = false), style = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNew) BoardIcon(EatGlyphs.sparkle, CHALK_YELLOW, 18.dp, contentDescription = stringResource(R.string.eat_tab_new))
                if (CafeToggle.VEGAN in line.has) BoardIcon(EatGlyphs.leafMark, CHALK_GREEN, 18.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
            }
        }) {
            Text("${p.walkMinutes}′", style = name.copy(fontWeight = FontWeight.Normal, fontSize = 26.sp))
        }
        val facts = buildList {
            ctx.ratings.stars(p.key)?.let { add("★ " + starsText(it)) }
            if (CafeToggle.WIFI in line.has) add(stringResource(R.string.eat_fact_wifi))
            if (CafeToggle.TERRACE in line.has) add(stringResource(R.string.eat_fact_terrace))
            if (CafeToggle.TAKEAWAY in line.has) add(stringResource(R.string.eat_fact_takeaway))
            openWords(p, ctx)?.first?.let { add(it) }
        }
        if (facts.isNotEmpty() && !closed) {
            Text(facts.joinToString(" · "), style = TextStyle(fontFamily = EatType.hand, fontSize = 20.sp, color = CHALK.copy(alpha = 0.72f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Today's special: a yellow chalk box, a little askew, with a cup, the words and an arrow. */
@Composable
private fun SpecialBox(s: Special, modifier: Modifier, onClick: () -> Unit) {
    val what = when {
        s.brunch && s.until != null -> stringResource(R.string.eat_special_brunch_till, s.until.format(HM))
        s.brunch -> stringResource(R.string.eat_special_brunch)
        s.until != null -> stringResource(R.string.eat_special_open_till, s.until.format(HM))
        else -> ""
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(92.dp)
            .rotate(-1.5f)
            .clip(shape)
            .background(SLATE)
            .border(2.5.dp, CHALK_YELLOW.copy(alpha = 0.75f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardIcon(EatGlyphs.specialCup, CHALK_YELLOW, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.eat_special), style = TextStyle(fontFamily = EatType.hand, fontSize = 20.sp, color = CHALK_YELLOW))
            Text("${s.place.name} — $what", style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 26.sp, color = CHALK), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BoardIcon(EatGlyphs.arrow, CHALK, 28.dp)
    }
}

/** The wooden frame: the board's 135° gradient of three browns. */
private fun DrawScope.drawFrame() {
    drawRect(Brush.linearGradient(0f to Color(0xFF9A6739), 0.5f to Color(0xFF7A4D2A), 1f to Color(0xFF8E5C33), start = Offset.Zero, end = Offset(size.width, size.height)))
}

/** The slate: #2B302C with the board's three faint patches of old chalk. */
private fun DrawScope.drawSlate() {
    drawRect(SLATE)
    fun patch(x: Float, y: Float, alpha: Float, stop: Float) {
        val c = Offset(size.width * x, size.height * y)
        val r = hypot(maxOf(x, 1 - x) * size.width, maxOf(y, 1 - y) * size.height)
        drawRect(Brush.radialGradient(0f to Color.White.copy(alpha = alpha), stop to Color.White.copy(alpha = 0f), center = c, radius = r))
    }
    patch(0.2f, 0.15f, 0.07f, 0.4f)
    patch(0.8f, 0.6f, 0.05f, 0.45f)
    patch(0.4f, 0.9f, 0.04f, 0.4f)
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

private fun cafeToggleGlyph(t: CafeToggle): BoardGlyph = when (t) {
    CafeToggle.WIFI -> EatGlyphs.wifi
    CafeToggle.QUIET -> EatGlyphs.speakerOff
    CafeToggle.TERRACE -> EatGlyphs.umbrellaChalk
    CafeToggle.VEGAN -> EatGlyphs.leafChalk
    CafeToggle.EARLY -> EatGlyphs.sunrise
    CafeToggle.TAKEAWAY -> EatGlyphs.takeawayCup
}
