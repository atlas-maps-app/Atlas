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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import java.util.Locale

/* The Bar board's golds and blacks (`canvas/project/Bar.dc.html`). */
private val GOLD_TEXT = Color(0xFFE8CF8E)
private val GOLD_HOOK = Color(0xFFC9A45C)
private val GOLD_META = Color(0xFFB99A5E)
private val GOLD_RING = Color(0xFF8C6A2A)
private val OFF_RING = Color(0xFF5E4A22)
private val OFF_ICON = Color(0xFF9C8350)
private val NIGHT = Color(0xFF0E0D0B)
private val PILL_TEXT = Color(0xFF120F08)
private val GOLD_FILL = Brush.verticalGradient(listOf(Color(0xFFF1D38A), Color(0xFFB98A36)))
private val GOLD_TITLE = Brush.verticalGradient(0f to Color(0xFFF6E2A8), 0.6f to Color(0xFFB98A36), 1f to Color(0xFFF1D38A))
private val GOLD_STOPS = arrayOf(0f to Color(0xFFF6E2A8), 0.45f to Color(0xFFB98A36), 1f to Color(0xFFE9C977))

/* The board's type: Cormorant Garamond as its page loads it (upright 600 and 700, italic 500 only, so its 400 italic draws at 500). */
private const val CORMORANT = "Cormorant Garamond"
private val TITLE = cssText("Poiret One", 50f, lineHeight = 50f, spacing = 4f, brush = GOLD_TITLE)
private val COUNT_LINE = cssText(CORMORANT, 15f, 600, colour = 0xFFB99A5E, lineHeight = 18.5f, spacing = 3.6f, tnum = true, smallCaps = true, align = TextAlign.Center)
private val TAB = cssText(CORMORANT, 15f, 700, lineHeight = 18.5f, spacing = 1.8f)
private val NAME = cssText(CORMORANT, 26f, 700, colour = 0xFFF1D38A, lineHeight = 31.5f, spacing = 3.12f, smallCaps = true, align = TextAlign.Center)
private val HOOK = cssText(CORMORANT, 17f, 500, italic = true, colour = 0xFFC9A45C, lineHeight = 20.5f, align = TextAlign.Center)
private val META = cssText("Geist", 13f, colour = 0xFFB99A5E, lineHeight = 17f, spacing = 0.52f, tnum = true)
private val CHIP = cssText(CORMORANT, 22f, 700, colour = 0xFF3A2A10, lineHeight = 27.5f, tnum = true)
private val WALK = cssText("Geist", 15f, 600, colour = 0xFF120F08, lineHeight = 19.5f, tnum = true)

/**
 * The bar, as on the Bar board: a black-and-gold Art Deco card. Tabs pick the kind of evening, ring toggles the mood;
 * each venue has a small-caps name and an italic hook read from its tags; the open count and last call come from the
 * opening hours; three venues make a crawl, ordered for the shortest walk.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    LightStatusIcons()
    var tab by rememberSaveable { mutableStateOf<BarTab?>(null) }
    var moods by rememberSaveable { mutableStateOf(emptySet<BarMood>()) }
    var picks by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val worked = rememberWorked(ctx.area, ctx.at, tab, moods) { BarModel.card(ctx.area, ctx.at, tab, moods) }
    val card = worked.value
    val crawl = remember(card, picks) {
        card?.let { c -> BarModel.crawl(ctx.area.centre, picks.mapNotNull { ctx.area[it] }, c.venues) }
    }
    val tray = crawl?.takeIf { it.stops.isNotEmpty() }

    Box(Modifier.fillMaxSize().drawBehind { drawDecoCard() }) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(start = 36.dp, end = 36.dp, bottom = if (tray != null) 164.dp else 44.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .drawWithContent {
                    drawContent()
                    // The list fades out above the crawl tray.
                    drawRect(Brush.verticalGradient(listOf(NIGHT.copy(alpha = 0f), NIGHT), size.height - 28.dp.toPx(), size.height), Offset(0f, size.height - 28.dp.toPx()))
                },
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The title block at 156 px: the gilt title on its 50 px line, 4 px, the count in small caps.
            item {
                Column(Modifier.fillMaxWidth().padding(top = boardTop(156.dp)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CssLine(stringResource(R.string.eat_bar_title), TITLE, Modifier.semantics { heading() })
                    val line = card?.let { c ->
                        val open = if (c.countsOpen) stringResource(R.string.eat_bar_open, c.open) else stringResource(R.string.eat_carte_places, c.open)
                        listOfNotNull(open, c.lastCall?.let { stringResource(R.string.eat_bar_last_call, it.format(HM)) }).joinToString(" · ")
                    } ?: stringResource(R.string.kit_loading)
                    CssLine(line.lowercase(Locale.forLanguageTag(ctx.lang)), COUNT_LINE)
                }
            }
            // The tabs at 238 px: 36 px high, 8 px apart, wrapped and centred.
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 9.5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (t in BarTab.entries) {
                        val n = card?.tabCounts?.get(t) ?: 0
                        DecoTab(stringResource(barTabName(t)), on = tab == t, enabled = n > 0) { tab = if (tab == t) null else t }
                    }
                }
            }
            // The moods at 334 px, from 40 to 372.
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (m in BarMood.entries) {
                        val n = card?.moodCounts?.get(m) ?: 0
                        MoodRing(m, stringResource(barMoodName(m)), on = m in moods, enabled = n > 0) { moods = if (m in moods) moods - m else moods + m }
                    }
                }
            }
            // The venues from 404 px, 44 px in from each side: rule, 14, entry, 14, rule…
            val venues = card?.venues.orEmpty()
            itemsIndexed(venues, key = { _, v -> v.place.key }) { i, v ->
                Column(Modifier.fillMaxWidth().padding(top = if (i == 0) 22.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DiamondRule()
                    VenueEntry(
                        v, ctx,
                        inCrawl = v.place.key in picks,
                        onOpen = { ctx.open(nav, v.place.place) },
                        onCrawl = {
                            val k = v.place.key
                            picks = if (k in picks) picks - k else (picks + k).takeLast(3)
                        },
                    )
                }
            }
            item {
                if (card != null && venues.isEmpty() && !ctx.area.loading) {
                    Text(
                        stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_bar_none),
                        Modifier.padding(vertical = 30.dp),
                        style = HOOK.copy(fontSize = 19.sp, lineHeight = 23.sp),
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(GOLD_META)
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let {
                    Text(it, Modifier.padding(top = 18.dp), style = META.copy(fontSize = 11.sp, letterSpacing = 0.sp, lineHeight = 14.sp), color = GOLD_META.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
        }
        // The two gold rings at 48 px, 40 px in: back to the table, and the order pad.
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = boardTop(48.dp))) {
            GoldRing("ui.back", 20, stringResource(R.string.kit_back)) { nav.back() }
            Spacer(Modifier.weight(1f))
            Box {
                GoldRing("ui.order", 19, stringResource(R.string.eat_pad)) { nav.go(Dest.Eat("order")) }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        Text(pad.size.toString(), style = cssText("Geist", 10f, 700, colour = 0xFFFFFFFF, lineHeight = 13f))
                    }
                }
            }
        }
        tray?.let { c ->
            CrawlTray(
                c,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 36.dp, end = 36.dp, bottom = 44.dp),
                onStop = { ctx.open(nav, it.place) },
                onWalk = { c.stops.firstOrNull()?.let { ctx.walkTo(nav, it) } },
            )
        }
    }
}

/** A 44 px ring, inset 1 px of #8C6A2A, round one of the board's symbols in #E8CF8E. */
@Composable
private fun GoldRing(symbol: String, side: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).cssBox(r = round(44f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0xFF8C6A2A, inset = true)))
            .clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = GOLD_TEXT, size = side.dp) }
}

/** A tab of the card: 36 px, 12 px either side, gold-ruled; the chosen one filled with gold. */
@Composable
private fun DecoTab(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.35f
    Box(
        Modifier
            .height(36.dp)
            .then(
                if (on) Modifier.cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFF1D38A, 1f to 0xFFB98A36)), r = corners(2f))
                else Modifier.cssBox(r = corners(2f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, GOLD_RING.copy(alpha = alpha).css(), inset = true))),
            )
            .clip(RoundedCornerShape(2.dp))
            .selectable(on, enabled = enabled || on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CssLine(text.uppercase(), TAB, color = if (on) PILL_TEXT else GOLD_TEXT.copy(alpha = alpha))
    }
}

/** A mood toggle (48 px): inset 1 px of #5E4A22 round #9C8350, or #2A2214 inset 1.5 px of #E8CF8E round #F6E2A8 when on. */
@Composable
private fun MoodRing(m: BarMood, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.4f
    Box(
        Modifier
            .size(48.dp)
            .then(
                if (on) Modifier.cssBox(bg = 0xFF2A2214, r = round(48f), shadows = listOf(CssShadow(0f, 0f, 0f, 1.5f, 0xFFE8CF8E, inset = true)))
                else Modifier.cssBox(r = round(48f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, OFF_RING.copy(alpha = alpha).css(), inset = true))),
            )
            .clip(CircleShape)
            .toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(moodSymbol(m), tint = if (on) Color(0xFFF6E2A8) else OFF_ICON.copy(alpha = alpha), size = 22.dp) }
}

/** "—◆—": the card's rule between venues (60 × 10), the board's own drawing. */
@Composable
private fun DiamondRule() {
    Canvas(Modifier.size(60.dp, 10.dp)) {
        val k = density
        drawLine(GOLD_RING, Offset(0f, 5 * k), Offset(22 * k, 5 * k), 1 * k)
        drawLine(GOLD_RING, Offset(38 * k, 5 * k), Offset(60 * k, 5 * k), 1 * k)
        drawPath(Path().apply { moveTo(30 * k, 0f); lineTo(35 * k, 5 * k); lineTo(30 * k, 10 * k); lineTo(25 * k, 5 * k); close() }, GOLD_HOOK)
    }
}

/**
 * A venue, 2 px between its lines: the name in small caps (31.5 px line), the italic hook (20.5) and
 * "3′ · → 2:00 · ★ 4.8 · $$" (17); the chip beside the meta puts it on the crawl without making its line taller.
 */
@Composable
private fun VenueEntry(v: Venue, ctx: EatContext, inCrawl: Boolean, onOpen: () -> Unit, onCrawl: () -> Unit) {
    val closed = v.openness == Openness.CLOSED
    val alpha = if (closed) 0.5f else 1f
    Column(
        Modifier.fillMaxWidth().alpha(alpha).clickable(onClick = onOpen).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(v.place.name, style = NAME, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val hook = v.hooks.map { hookText(it) }.joinToString(", ")
        CssLine(hook.ifEmpty { v.place.kindName(ctx.lang)?.lowercase().orEmpty() }, HOOK)
        val stars = ctx.ratings.stars(v.place.key)?.let { "★ " + starsText(it) }
        // Geist has no ★: the browser's fallback face for it makes the board's meta line 19.5 px, not 17.
        val lineH = if (stars != null) 19.5f else 17f
        Row(Modifier.height(lineH.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val open = when {
                v.openness == Openness.CLOSED -> v.place.opensAt(ctx.at, ctx.area.hours)?.let { stringResource(R.string.eat_opens_at, dayTime(it, ctx.at, ctx.lang)) } ?: stringResource(R.string.kit_closed)
                v.place.alwaysOpen -> "24/7"
                v.closes != null && v.hooks.none { it is Hook.Until } -> "→ " + v.closes.format(HM)
                else -> null
            }
            val price = priceText(ctx.ratings[v.place.key]?.priceLevel)
            CssLine(metaLine(v.place.walkMinutes, open, stars, price), META.copy(lineHeight = lineH.sp))
            val label = stringResource(if (inCrawl) R.string.eat_crawl_remove else R.string.eat_crawl_add)
            Box(Modifier.height(lineH.dp).wrapContentHeight(unbounded = true)) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (inCrawl) GOLD_HOOK else Color.Transparent)
                        .border(1.dp, GOLD_RING, CircleShape)
                        .toggleable(inCrawl, role = Role.Checkbox) { onCrawl() }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { if (inCrawl) Symbol("ui-check", tint = NIGHT, size = 12.dp) else Symbol("ui.plus", tint = GOLD_META, size = 12.dp) }
            }
        }
    }
}

/** The tray at the foot (120 px, #16130D, inset 1 px of #5E4A22, 16 px in): chips joined by dotted gold, and the gold walk pill. */
@Composable
private fun CrawlTray(c: Crawl, modifier: Modifier, onStop: (EatPlace) -> Unit, onWalk: () -> Unit) {
    val walkLabel = stringResource(R.string.eat_walk_desc, c.minutes)
    Row(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .cssBox(bg = 0xFF16130D, r = corners(4f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0xFF5E4A22, inset = true)))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tilts = floatArrayOf(-8f, 5f, -3f)
        c.stops.forEachIndexed { i, p ->
            if (i > 0) Canvas(Modifier.size(18.dp, 2.dp)) {
                var x = 1.dp.toPx()
                while (x < size.width) {
                    drawCircle(GOLD_HOOK, 1.dp.toPx(), Offset(x, size.height / 2f))
                    x += 4.dp.toPx()
                }
            }
            PokerChip(i + 1, tilts[i], Modifier.clickable { onStop(p) }.semantics { contentDescription = "${i + 1}. ${p.name}" })
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .height(52.dp)
                .cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFF1D38A, 1f to 0xFFB98A36)), r = round(52f))
                .clip(CircleShape)
                .clickable(onClick = onWalk)
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = walkLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Symbol("mode.walk", tint = PILL_TEXT, size = 18.dp)
            CssLine("${c.minutes}′", WALK)
        }
    }
}

/** A cream poker chip (58 px): lit at 40 % 35 %, inset 4 px of #B3A078, its 0 4 8 shadow, the stop's number. */
@Composable
private fun PokerChip(n: Int, tilt: Float, modifier: Modifier) {
    Box(
        modifier
            .rotate(tilt)
            .size(58.dp)
            .cssBox(
                grad = listOf(cssRadial(true, 23.2f, 20.3f, 0f to 0xFFE9DCC0, 1f to 0xFFCDBB94)), r = round(58f),
                shadows = listOf(CssShadow(0f, 0f, 0f, 4f, 0xFFB3A078, inset = true), CssShadow(0f, 4f, 8f, 0f, 0x80000000)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        CssLine(n.toString(), CHIP)
    }
}

/**
 * The card itself, the board's SVG: the radial night, a gold double frame with stepped corners (the top part held to
 * the top, the bottom part to the bottom) and the fan of seven rays over the title. Each gold stroke takes the
 * gradient across its own bounds, as SVG's objectBoundingBox does (the board's rule under the fan has no height, so
 * the browser draws nothing there, and neither does this).
 */
private fun DrawScope.drawDecoCard() {
    drawRect(cssRadial(false, size.width / density / 2f, size.height / density * 0.1f, 0f to 0xFF1D1A14, 0.6f to 0xFF0E0D0B).brush(size, density))
    val k = density
    val h = size.height
    val w = size.width
    val x = { v: Float -> v * k }
    // The stepped top and bottom, then the sides, in 1.5 px of gold.
    val outer = Path().apply {
        moveTo(x(18f), x(60f)); lineTo(x(18f), x(40f)); lineTo(x(38f), x(40f)); lineTo(x(38f), x(20f)); lineTo(w - x(38f), x(20f)); lineTo(w - x(38f), x(40f)); lineTo(w - x(18f), x(40f)); lineTo(w - x(18f), x(60f))
        moveTo(x(18f), h - x(60f)); lineTo(x(18f), h - x(40f)); lineTo(x(38f), h - x(40f)); lineTo(x(38f), h - x(20f)); lineTo(w - x(38f), h - x(20f)); lineTo(w - x(38f), h - x(40f)); lineTo(w - x(18f), h - x(40f)); lineTo(w - x(18f), h - x(60f))
    }
    drawPath(outer, svgDiagonal(x(18f), x(20f), w - x(36f), h - x(40f), *GOLD_STOPS), style = Stroke(1.5f * k))
    val sides = Path().apply { moveTo(x(18f), x(60f)); lineTo(x(18f), h - x(60f)); moveTo(w - x(18f), x(60f)); lineTo(w - x(18f), h - x(60f)) }
    drawPath(sides, svgDiagonal(x(18f), x(60f), w - x(36f), h - x(120f), *GOLD_STOPS), style = Stroke(1.5f * k))
    // The inner frame in 0.8 px of #8C6A2A.
    val inner = Path().apply {
        moveTo(x(26f), x(64f)); lineTo(x(26f), x(46f)); lineTo(x(44f), x(46f)); lineTo(x(44f), x(28f)); lineTo(w - x(44f), x(28f)); lineTo(w - x(44f), x(46f)); lineTo(w - x(26f), x(46f)); lineTo(w - x(26f), x(64f))
        moveTo(x(26f), h - x(64f)); lineTo(x(26f), h - x(46f)); lineTo(x(44f), h - x(46f)); lineTo(x(44f), h - x(28f)); lineTo(w - x(44f), h - x(28f)); lineTo(w - x(44f), h - x(46f)); lineTo(w - x(26f), h - x(46f)); lineTo(w - x(26f), h - x(64f))
        moveTo(x(26f), x(64f)); lineTo(x(26f), h - x(64f)); moveTo(w - x(26f), x(64f)); lineTo(w - x(26f), h - x(64f))
    }
    drawPath(inner, GOLD_RING, style = Stroke(0.8f * k))
    // The fan: seven rays from (206, 44), at 75 %, the gradient across the fan's own bounds.
    val c = w / 2f
    val fan = Path().apply {
        for ((rx, ry) in listOf(150f to 132f, 166f to 138f, 184f to 142f, 206f to 144f, 228f to 142f, 246f to 138f, 262f to 132f)) {
            moveTo(c, x(44f)); lineTo(c + x(rx - 206f), x(ry))
        }
    }
    drawPath(fan, svgDiagonal(c - x(56f), x(44f), x(112f), x(100f), *GOLD_STOPS), alpha = 0.75f, style = Stroke(1f * k))
}

@Composable
private fun hookText(h: Hook): String = when (h) {
    Hook.LiveMusic -> stringResource(R.string.eat_hook_live_music)
    Hook.Karaoke -> stringResource(R.string.eat_hook_karaoke)
    Hook.Rooftop -> stringResource(R.string.eat_hook_rooftop)
    Hook.BeerGarden -> stringResource(R.string.eat_hook_beer_garden)
    Hook.Terrace -> stringResource(R.string.eat_hook_terrace)
    Hook.Cocktails -> stringResource(R.string.eat_hook_cocktails)
    Hook.Brews -> stringResource(R.string.eat_hook_brews)
    is Hook.OnTap -> stringResource(R.string.eat_hook_on_tap, h.beers.joinToString(", "))
    is Hook.Sport -> stringResource(R.string.eat_hook_sport, sportWord(h.sport))
    Hook.OpenFire -> stringResource(R.string.eat_hook_fire)
    Hook.Lgbtq -> stringResource(R.string.eat_hook_lgbtq)
    Hook.CraftBeer -> stringResource(R.string.eat_hook_craft_beer)
    is Hook.Until -> stringResource(R.string.eat_hook_until, h.at.format(HM))
    is Hook.Says -> h.text.replaceFirstChar { it.lowercase() }
    is Hook.On -> stringResource(R.string.eat_hook_on, h.street)
}

@Composable
private fun sportWord(sport: String): String = when (sport) {
    "soccer" -> stringResource(R.string.eat_sport_soccer)
    "darts" -> stringResource(R.string.eat_sport_darts)
    "billiards", "pool", "snooker" -> stringResource(R.string.eat_sport_pool)
    else -> sport.replace('_', ' ')
}

private fun moodSymbol(m: BarMood): String = when (m) {
    BarMood.HAPPY_HOUR -> "food.bar.2"
    BarMood.TERRACE -> "fact.terrace"
    BarMood.MUSIC -> "cat.fun"
    BarMood.SPORT -> "fact.screen"
    BarMood.QUIET -> "fact.language"
}

private fun barTabName(t: BarTab): Int = when (t) {
    BarTab.COCKTAILS -> R.string.eat_bar_cocktails
    BarTab.WINE -> R.string.eat_bar_wine
    BarTab.BEER -> R.string.eat_bar_beer
    BarTab.PUBS -> R.string.eat_bar_pubs
    BarTab.GARDENS -> R.string.eat_bar_gardens
    BarTab.LIVE_MUSIC -> R.string.eat_bar_live_music
}

private fun barMoodName(m: BarMood): Int = when (m) {
    BarMood.HAPPY_HOUR -> R.string.eat_mood_happy_hour
    BarMood.TERRACE -> R.string.eat_mood_terrace
    BarMood.MUSIC -> R.string.eat_mood_music
    BarMood.SPORT -> R.string.eat_mood_sport
    BarMood.QUIET -> R.string.eat_mood_quiet
}
