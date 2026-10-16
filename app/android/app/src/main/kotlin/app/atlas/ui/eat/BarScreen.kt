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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.Symbol
import kotlin.math.hypot

/* The Bar board's golds and blacks (`canvas/project/Bar.dc.html`). */
private val GOLD_TEXT = Color(0xFFE8CF8E)
private val GOLD_NAME = Color(0xFFF1D38A)
private val GOLD_HOOK = Color(0xFFC9A45C)
private val GOLD_META = Color(0xFFB99A5E)
private val GOLD_RING = Color(0xFF8C6A2A)
private val OFF_RING = Color(0xFF5E4A22)
private val OFF_ICON = Color(0xFF9C8350)
private val NIGHT = Color(0xFF0E0D0B)
private val PILL_TEXT = Color(0xFF120F08)
private val GOLD_FILL = Brush.verticalGradient(listOf(Color(0xFFF1D38A), Color(0xFFB98A36)))
private val GOLD_TITLE = Brush.verticalGradient(0f to Color(0xFFF6E2A8), 0.6f to Color(0xFFB98A36), 1f to Color(0xFFF1D38A))

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

    Box(Modifier.fillMaxSize().background(NIGHT).drawBehind { drawDecoCard() }) {
        val titleTop = boardTop(156.dp)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(start = 36.dp, end = 36.dp, bottom = 172.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .drawWithContent {
                    drawContent()
                    // The list fades out above the crawl tray.
                    drawRect(Brush.verticalGradient(listOf(NIGHT.copy(alpha = 0f), NIGHT), size.height - 28.dp.toPx(), size.height), Offset(0f, size.height - 28.dp.toPx()))
                },
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = titleTop), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.eat_bar_title),
                        Modifier.semantics { heading() },
                        style = TextStyle(fontFamily = EatType.poiret, fontSize = 50.sp, lineHeight = 50.sp, letterSpacing = 0.08.em, brush = GOLD_TITLE),
                        maxLines = 1,
                    )
                    val line = card?.let { c ->
                        val open = if (c.countsOpen) stringResource(R.string.eat_bar_open, c.open) else stringResource(R.string.eat_carte_places, c.open)
                        listOfNotNull(open, c.lastCall?.let { stringResource(R.string.eat_bar_last_call, it.format(HM)) }).joinToString(" · ")
                    } ?: stringResource(R.string.kit_loading)
                    Text(
                        smallCaps(line.lowercase(), 15.sp),
                        style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.SemiBold, letterSpacing = 0.24.em, color = GOLD_META, fontFeatureSettings = "lnum"),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (t in BarTab.entries) {
                        val n = card?.tabCounts?.get(t) ?: 0
                        DecoTab(stringResource(barTabName(t)), on = tab == t, enabled = n > 0) { tab = if (tab == t) null else t }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (m in BarMood.entries) {
                        val n = card?.moodCounts?.get(m) ?: 0
                        MoodRing(m, stringResource(barMoodName(m)), on = m in moods, enabled = n > 0) { moods = if (m in moods) moods - m else moods + m }
                    }
                }
            }
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
                        style = TextStyle(fontFamily = EatType.cormorant, fontStyle = FontStyle.Italic, fontSize = 19.sp, color = GOLD_HOOK),
                        textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(GOLD_META)
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let {
                    Text(it, Modifier.padding(top = 18.dp), style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp), color = GOLD_META.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
        }
        // The two gold rings at the top, 48 px down: back to the table, and the order pad.
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = boardTop(48.dp))) {
            GoldRing(EatGlyphs.back, stringResource(R.string.kit_back)) { nav.back() }
            Spacer(Modifier.weight(1f))
            Box {
                GoldRing(EatGlyphs.padThin, stringResource(R.string.eat_pad)) { nav.go(Dest.Eat("order")) }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        Text(pad.size.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
                    }
                }
            }
        }
        crawl?.takeIf { it.stops.isNotEmpty() }?.let { c ->
            CrawlTray(
                c,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 36.dp, end = 36.dp, bottom = 44.dp),
                onStop = { ctx.open(nav, it.place) },
                onWalk = { c.stops.firstOrNull()?.let { ctx.walkTo(nav, it) } },
            )
        }
    }
}

/** A ring of #8C6A2A round one of the board's glyphs (44 px). */
@Composable
private fun GoldRing(glyph: BoardGlyph, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.dp, GOLD_RING, CircleShape).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(glyph, GOLD_TEXT, if (glyph === EatGlyphs.back) 20.dp else 19.dp) }
}

/** A tab of the card: 36 px, gold-ruled; the chosen one filled with gold. */
@Composable
private fun DecoTab(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.35f
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(2.dp))
            .then(if (on) Modifier.background(GOLD_FILL) else Modifier.border(1.dp, GOLD_RING.copy(alpha = alpha), RoundedCornerShape(2.dp)))
            .selectable(on, enabled = enabled || on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.12.em),
            color = if (on) PILL_TEXT else GOLD_TEXT.copy(alpha = alpha),
        )
    }
}

/** A mood toggle (48 px): a thin ring of #5E4A22, or #2A2214 with a pale gold ring when on. */
@Composable
private fun MoodRing(m: BarMood, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.4f
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (on) Color(0xFF2A2214) else Color.Transparent)
            .border(if (on) 1.5.dp else 1.dp, if (on) GOLD_TEXT else OFF_RING.copy(alpha = alpha), CircleShape)
            .toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(moodGlyph(m), if (on) Color(0xFFF6E2A8) else OFF_ICON.copy(alpha = alpha), 22.dp) }
}

/** "—◆—": the card's rule between venues (60 × 10). */
@Composable
private fun DiamondRule() {
    Canvas(Modifier.size(60.dp, 10.dp)) {
        val k = density
        drawLine(GOLD_RING, Offset(0f, 5 * k), Offset(22 * k, 5 * k), 1 * k)
        drawLine(GOLD_RING, Offset(38 * k, 5 * k), Offset(60 * k, 5 * k), 1 * k)
        drawPath(Path().apply { moveTo(30 * k, 0f); lineTo(35 * k, 5 * k); lineTo(30 * k, 10 * k); lineTo(25 * k, 5 * k); close() }, GOLD_HOOK)
    }
}

/** A venue: its small-caps name, the italic hook and "3′ · → 2:00 · ★ 4.8 · $$"; the chip puts it on the crawl. */
@Composable
private fun VenueEntry(v: Venue, ctx: EatContext, inCrawl: Boolean, onOpen: () -> Unit, onCrawl: () -> Unit) {
    val closed = v.openness == Openness.CLOSED
    val alpha = if (closed) 0.5f else 1f
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            smallCaps(v.place.name, 26.sp, lang = ctx.lang),
            style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em, color = GOLD_NAME.copy(alpha = alpha)),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val hook = v.hooks.map { hookText(it) }.joinToString(", ")
        Text(
            hook.ifEmpty { v.place.kindName(ctx.lang)?.lowercase().orEmpty() },
            style = TextStyle(fontFamily = EatType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = GOLD_HOOK.copy(alpha = alpha)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val open = when {
                v.openness == Openness.CLOSED -> v.place.opensAt(ctx.at, ctx.area.hours)?.let { stringResource(R.string.eat_opens_at, dayTime(it, ctx.at, ctx.lang)) } ?: stringResource(R.string.kit_closed)
                v.place.alwaysOpen -> "24/7"
                v.closes != null && v.hooks.none { it is Hook.Until } -> "→ " + v.closes.format(HM)
                else -> null
            }
            val stars = ctx.ratings.stars(v.place.key)?.let { "★ " + starsText(it) }
            val price = priceText(ctx.ratings[v.place.key]?.priceLevel)
            Text(
                metaLine(v.place.walkMinutes, open, stars, price),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, letterSpacing = 0.04.em, color = GOLD_META.copy(alpha = alpha)),
            )
            val label = stringResource(if (inCrawl) R.string.eat_crawl_remove else R.string.eat_crawl_add)
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (inCrawl) GOLD_HOOK else Color.Transparent)
                    .border(1.dp, GOLD_RING, CircleShape)
                    .toggleable(inCrawl, role = Role.Checkbox) { onCrawl() }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { if (inCrawl) Symbol("ui-check", tint = NIGHT, size = 12.dp) else BoardIcon(EatGlyphs.plus, GOLD_META, 12.dp) }
        }
    }
}

/** The tray at the foot (120 px): three poker chips joined by dotted gold, and the gold walk pill. */
@Composable
private fun CrawlTray(c: Crawl, modifier: Modifier, onStop: (EatPlace) -> Unit, onWalk: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val walkLabel = stringResource(R.string.eat_walk_desc, c.minutes)
    Row(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(shape)
            .background(Color(0xFF16130D))
            .border(1.dp, OFF_RING, shape)
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
                .clip(CircleShape)
                .background(GOLD_FILL)
                .clickable(onClick = onWalk)
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = walkLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BoardIcon(EatGlyphs.walk, PILL_TEXT, 18.dp)
            Text("${c.minutes}′", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = PILL_TEXT))
        }
    }
}

/** A cream poker chip (58 px): lit from the upper left, a 4 px inner ring, its shadow, the stop's number. */
@Composable
private fun PokerChip(n: Int, tilt: Float, modifier: Modifier) {
    Box(
        modifier
            .rotate(tilt)
            .size(58.dp)
            .boxShadow(CircleShape, 1f, 4f, 8f, Color(0x80000000))
            .clip(CircleShape)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Color(0xFFE9DCC0), Color(0xFFCDBB94)), Offset(size.width * 0.4f, size.height * 0.35f), hypot(size.width * 0.6f, size.height * 0.65f)))
            }
            .innerShadow(CircleShape, BoxShadow(radius = 0.dp, spread = 4.dp, color = Color(0xFFB3A078))),
        contentAlignment = Alignment.Center,
    ) {
        Text(n.toString(), style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF3A2A10), fontFeatureSettings = "lnum"))
    }
}

/**
 * The card itself, the board's SVG: a gold double frame with stepped corners (the top part held to the top, the
 * bottom part to the bottom), the fan of seven rays over the title and the rule under it.
 */
private fun DrawScope.drawDecoCard() {
    drawRect(Brush.radialGradient(0f to Color(0xFF1D1A14), 0.6f to NIGHT, center = Offset(size.width / 2f, size.height * 0.1f), radius = hypot(size.width / 2f, size.height * 0.9f)))
    val k = size.width / 412f
    val h = size.height
    val gold = Brush.linearGradient(0f to Color(0xFFF6E2A8), 0.45f to Color(0xFFB98A36), 1f to Color(0xFFE9C977), start = Offset.Zero, end = Offset(size.width, h))
    fun frame(l: Float, t1: Float, t2: Float, t3: Float, x2: Float, brushOrColour: Any, width: Float) {
        val r = 412f - l
        val p = Path().apply {
            // Top: up from the side, a step in, a step up, across, and down again on the right.
            moveTo(l * k, t1 * k); lineTo(l * k, t2 * k); lineTo(x2 * k, t2 * k); lineTo(x2 * k, t3 * k)
            lineTo((412f - x2) * k, t3 * k); lineTo((412f - x2) * k, t2 * k); lineTo(r * k, t2 * k); lineTo(r * k, t1 * k)
            // The sides.
            moveTo(l * k, t1 * k); lineTo(l * k, h - t1 * k)
            moveTo(r * k, t1 * k); lineTo(r * k, h - t1 * k)
            // Bottom, mirrored.
            moveTo(l * k, h - t1 * k); lineTo(l * k, h - t2 * k); lineTo(x2 * k, h - t2 * k); lineTo(x2 * k, h - t3 * k)
            lineTo((412f - x2) * k, h - t3 * k); lineTo((412f - x2) * k, h - t2 * k); lineTo(r * k, h - t2 * k); lineTo(r * k, h - t1 * k)
        }
        when (brushOrColour) {
            is Brush -> drawPath(p, brushOrColour, style = Stroke(width * k))
            is Color -> drawPath(p, brushOrColour, style = Stroke(width * k))
        }
    }
    frame(18f, 60f, 40f, 20f, 38f, gold, 1.5f)
    frame(26f, 64f, 46f, 28f, 44f, GOLD_RING, 0.8f)
    val apex = Offset(206 * k, 44 * k)
    for ((x, y) in listOf(150f to 132f, 166f to 138f, 184f to 142f, 206f to 144f, 228f to 142f, 246f to 138f, 262f to 132f)) {
        drawLine(gold, apex, Offset(x * k, y * k), 1 * k, alpha = 0.75f)
    }
    drawLine(gold, Offset(140 * k, 150 * k), Offset(272 * k, 150 * k), 1 * k)
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

private fun moodGlyph(m: BarMood): BoardGlyph = when (m) {
    BarMood.HAPPY_HOUR -> EatGlyphs.happyHour
    BarMood.TERRACE -> EatGlyphs.outdoors
    BarMood.MUSIC -> EatGlyphs.music
    BarMood.SPORT -> EatGlyphs.screen
    BarMood.QUIET -> EatGlyphs.talk
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
