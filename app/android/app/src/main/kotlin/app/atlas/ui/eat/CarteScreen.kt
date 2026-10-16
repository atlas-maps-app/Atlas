package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Cuisine
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssRadial
import java.util.Locale
import kotlinx.coroutines.launch

/** The Carte board's paper, inks and burgundy (`canvas/project/Carte.dc.html`). */
internal val PAPER = Color(0xFFFBF6EA)
internal val MENU_INK = Color(0xFF2A1D14)
internal val MENU_SOFT = Color(0xFF7A6A55)
internal val BURGUNDY = Color(0xFF7A2A28)
private val TAB_OFF = Color(0xFF8A7A63)
private val TAB_DOT = Color(0xFFA8977E)
private val RULE = Color(0xFFD9C9AE)
private val LEADER = Color(0xFFC9B89B)

/* The board's type: Cormorant Garamond (500 to 700, italic 500 and 600) and Geist for the figures. */
private const val CORMORANT = "Cormorant Garamond"
private val TITLE = cssText(CORMORANT, 46f, 600, italic = true, colour = 0xFF2A1D14, lineHeight = 46f)
private val COUNT_LINE = cssText(CORMORANT, 15f, 600, colour = 0xFF6B5A45, lineHeight = 18.5f, spacing = 1.8f, tnum = true, smallCaps = true)
private val SORT = cssText(CORMORANT, 16f, 700, lineHeight = 19.5f, spacing = 1.6f, smallCaps = true)
private val CHAPTER = cssText(CORMORANT, 15f, 700, lineHeight = 18.5f, spacing = 3f, smallCaps = true)
private val CUISINE = cssText(CORMORANT, 21f, 600, colour = 0xFF2A1D14, lineHeight = 25.5f)
private val SUBS = cssText(CORMORANT, 16f, 500, italic = true, colour = 0xFF7A6A55, lineHeight = 19.5f)
private val COUNT = cssText("Geist", 14f, 600, colour = 0xFF2A1D14, lineHeight = 18f, tnum = true)
private val WALK = cssText("Geist", 13f, 500, colour = 0xFF7A6A55, lineHeight = 17f, tnum = true, align = TextAlign.End)
private val TAB_CODE = cssText("Geist", 11f, 700, colour = 0xFFFFFFFF, lineHeight = 14f, spacing = 1.1f)
private val DIET = cssText("Geist", 13f, 700, lineHeight = 17f)

/**
 * La Carte, as on the Carte board: every restaurant around as a printed menu, by cuisine family (binder tabs on the
 * right jump to each), each line a cuisine with dotted leaders to how many are open and the nearest on foot, italic
 * sub-styles, sorted by nearest, most, best rated or A–Z; a diet bar filters on `diet:*`.
 */
@Composable
fun CarteScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    var sort by rememberSaveable { mutableStateOf(CarteSort.NEAREST) }
    var diets by rememberSaveable { mutableStateOf(emptySet<Diet>()) }
    val unmappedRestaurant = stringResource(R.string.eat_unmapped_restaurant)
    val unmappedFastFood = stringResource(R.string.eat_unmapped_fast_food)
    val unmappedNote = stringResource(R.string.eat_unmapped_note)
    val names: (String) -> String = { key ->
        when (key) {
            CarteModel.UNMAPPED_RESTAURANT -> unmappedRestaurant
            CarteModel.UNMAPPED_FAST_FOOD -> unmappedFastFood
            else -> Cuisine.name(key, ctx.lang)
        }
    }
    val worked = rememberWorked(ctx.area, ctx.at, sort, diets, ctx.ratings) { CarteModel.build(ctx.area, ctx.at, sort, diets, ctx.ratings, ctx.lang, names) }
    val carte = worked.value
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().drawBehind { drawMenuPaper() }) {
        // Where each chapter's heading sits in the list, for the thumb index.
        val headerItems = 3
        val chapterIndex = HashMap<CuisineFamily, Int>()
        carte?.let { c ->
            var i = headerItems
            for (ch in c.chapters) {
                chapterIndex[ch.family] = i
                i += 1 + ch.lines.size
            }
        }
        val current = carte?.chapters?.let { chs ->
            val first = list.firstVisibleItemIndex
            chs.lastOrNull { (chapterIndex[it.family] ?: Int.MAX_VALUE) <= first + 1 }?.family ?: chs.firstOrNull()?.family
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 30.dp, end = 44.dp, bottom = bottomRoom(120.dp)),
        ) {
            item {
                CarteTopRow(
                    padCount = pad.size,
                    onBack = { nav.back() },
                    onSurprise = { EatSets.surprise(ctx.area, ctx.at, ctx.ratings)?.let { ctx.open(nav, it.place) } },
                    onPad = { nav.go(Dest.Eat("order")) },
                )
            }
            // The title block 18 px under the top row: the flourish (120 × 16), 4, "La Carte" (46 on 46), 4, the count.
            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Flourish(Modifier.size(120.dp, 16.dp))
                    CssLine(stringResource(R.string.eat_la_carte), TITLE, Modifier.semantics { heading() })
                    val count = carte?.let { c ->
                        if (c.countsOpen) stringResource(R.string.eat_carte_open, c.counted, ctx.at.format(HM_PADDED))
                        else stringResource(R.string.eat_carte_places, c.counted)
                    } ?: stringResource(R.string.kit_loading)
                    CssLine(count.lowercase(Locale.forLanguageTag(ctx.lang)), COUNT_LINE)
                }
            }
            item { SortTabs(sort, enabledRated = carte?.anyRated == true, onSort = { sort = it }) }
            carte?.let { c ->
                // Chapters 18 px apart, each a heading and its lines 6 px apart.
                for (ch in c.chapters) {
                    val faded = c.countsOpen && ch.lines.all { it.allClosed }
                    item(key = "h:${ch.family}") {
                        ChapterHeading(ch.family, faded, onWorld = CuisineWorlds.forFamily(ch.family)?.let { w -> { nav.go(Dest.Eat("world:${w.id}")) } })
                    }
                    for (line in ch.lines) {
                        item(key = "l:${line.cuisine}") {
                            val world = CuisineWorlds.forCuisine(line.cuisine)
                            CarteLineRow(line, countsOpen = c.countsOpen, unmappedNote = unmappedNote, world = world != null, faded = faded, modifier = Modifier.padding(top = 6.dp)) {
                                nav.go(Dest.Eat(world?.second?.id ?: "carte:${line.cuisine}"))
                            }
                        }
                    }
                }
                item {
                    if (c.chapters.isEmpty() && !ctx.area.loading) {
                        Text(
                            stringResource(if (diets.isEmpty()) R.string.eat_none_here else R.string.eat_carte_no_diet),
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            style = cssText(CORMORANT, 21f, 500, italic = true, colour = 0xFF7A6A55, lineHeight = 25.5f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    val notes = listOfNotNull(
                        sourceLine(ctx.area),
                        if (c.countsOpen) stringResource(R.string.eat_carte_hours_note) else null,
                        if (sort == CarteSort.BEST_RATED && !c.anyRated) stringResource(R.string.eat_no_ratings_yet) else null,
                    )
                    HonestyFooter(notes.joinToString(" · "), Modifier.padding(top = 18.dp))
                }
            }
            if (ctx.area.waiting || !worked.fresh) item { Looking(MENU_SOFT) }
            if (ctx.area.error != null && ctx.area.places.isEmpty()) item { EatProblem(ctx.area) }
        }
        // The binder's thumb index down the right edge, from 250 px down; the chapter in view sticks out.
        ThumbIndex(
            present = carte?.chapters?.map { it.family }?.toSet().orEmpty(),
            current = current,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = boardTop(250.dp)),
            onJump = { f -> chapterIndex[f]?.let { scope.launch { list.animateScrollToItem(it) } } },
        )
        DietBar(
            diets = diets,
            counts = carte?.dietCounts.orEmpty(),
            onToggle = { d -> diets = if (d in diets) diets - d else diets + d },
            onMap = { nav.go(Dest.Eat("map")) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Back, the die for "surprise me" (none without [onSurprise]), and the burgundy order pad with the shortlist's badge
 * (44 px each, 8 apart), 44 px down: the Carte board's top row, which the carte's cuisine pages share.
 */
@Composable
internal fun CarteTopRow(padCount: Int, onBack: () -> Unit, onSurprise: (() -> Unit)?, onPad: () -> Unit) {
    val back = stringResource(R.string.kit_back)
    val surprise = stringResource(R.string.eat_surprise)
    Row(
        Modifier.fillMaxWidth().padding(top = boardTop(44.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).cssBox(bg = 0x147A2A28, r = round(44f)).clip(CircleShape).clickable(onClick = onBack).semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
            Symbol("ui.back", tint = BURGUNDY, size = 20.dp)
        }
        Spacer(Modifier.weight(1f))
        if (onSurprise != null) {
            Box(Modifier.size(44.dp).cssBox(bg = 0x147A2A28, r = round(44f)).clip(CircleShape).clickable(onClick = onSurprise).semantics { contentDescription = surprise }, contentAlignment = Alignment.Center) {
                Symbol("ui.surprise", tint = BURGUNDY, size = 20.dp)
            }
        }
        Box {
            val padLabel = stringResource(R.string.eat_pad)
            Box(Modifier.size(44.dp).cssBox(bg = 0xFF7A2A28, r = round(44f)).clip(CircleShape).clickable(onClick = onPad).semantics { contentDescription = padLabel }, contentAlignment = Alignment.Center) {
                Symbol("ui.order", tint = Color.White, size = 19.dp)
            }
            // The badge: 18 px in a 2 px ring of paper, 3 px over the top right.
            if (padCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(22.dp)
                        .cssBox(bg = 0xFFE8622C, r = corners(9f), border = CssBorder(2f, 2f, 2f, 2f, longArrayOf(0xFFFBF6EA, 0xFFFBF6EA, 0xFFFBF6EA, 0xFFFBF6EA))),
                    contentAlignment = Alignment.Center,
                ) { CssLine(padCount.toString(), cssText("Geist", 10f, 700, colour = 0xFFFFFFFF, lineHeight = 13f)) }
            }
        }
    }
}

/** The menu card: #FBF6EA lit from the top, a burgundy spine 12 px wide and its shadow on the paper. */
internal fun DrawScope.drawMenuPaper() {
    drawRect(PAPER)
    drawRect(cssRadial(false, size.width / density / 2f, 0f, 0f to 0xCCFFFFFF, 0.6f to 0x00FFFFFF).brush(size, density))
    val spine = 12.dp.toPx()
    drawRect(Brush.horizontalGradient(0f to Color(0xFF3A1010), 0.6f to Color(0xFF6A2422), 1f to Color(0xFF4E1817), startX = 0f, endX = spine), size = Size(spine, size.height))
    drawRect(Brush.horizontalGradient(listOf(Color(0x243C1E0A), Color(0x003C1E0A)), spine, spine + 18.dp.toPx()), topLeft = Offset(spine, 0f), size = Size(18.dp.toPx(), size.height))
}

/** The engraved flourish over the title, the board's own (120 × 16). */
@Composable
internal fun Flourish(modifier: Modifier = Modifier, colour: Color = Color(0xFF9A7B4A)) {
    Canvas(modifier) { drawBoardGlyph(EatGlyphs.flourish, colour, side = size.width) }
}

/** The sorts, 18 px under the count: small caps 16 px, 14 apart with dots between, the chosen one burgundy over 1 px and a 1.5 px rule. */
@Composable
private fun SortTabs(sort: CarteSort, enabledRated: Boolean, onSort: (CarteSort) -> Unit) {
    val labels = listOf(
        CarteSort.NEAREST to R.string.eat_sort_nearest,
        CarteSort.MOST to R.string.eat_sort_most,
        CarteSort.BEST_RATED to R.string.eat_sort_rated,
        CarteSort.A_Z to R.string.eat_sort_az,
    )
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
        labels.forEachIndexed { i, (s, label) ->
            if (i > 0) CssLine("·", SORT, color = TAB_DOT)
            val on = s == sort
            val colour = when {
                on -> BURGUNDY
                s == CarteSort.BEST_RATED && !enabledRated -> TAB_OFF.copy(alpha = 0.55f)
                else -> TAB_OFF
            }
            CssLine(
                stringResource(label).lowercase(),
                SORT,
                Modifier
                    .selectable(on, role = Role.Tab) { onSort(s) }
                    .drawBehind { if (on) drawRect(BURGUNDY, Offset(0f, size.height - 1.5.dp.toPx()), Size(size.width, 1.5.dp.toPx())) }
                    .padding(bottom = 2.5.dp),
                color = colour,
            )
        }
    }
}

/** A chapter, 18 px under what is above: its name in its colour (small caps 15, 0.2 em apart), 10 px, a 1 px #D9C9AE rule. */
@Composable
private fun ChapterHeading(f: CuisineFamily, faded: Boolean, onWorld: (() -> Unit)?) {
    val colour = Color(f.colour)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .alpha(if (faded) 0.55f else 1f)
            .then(if (onWorld != null) Modifier.clickable(onClick = onWorld) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CssLine(stringResource(familyName(f)).lowercase(), CHAPTER, Modifier.semantics { heading() }, color = colour)
        Box(Modifier.weight(1f).height(1.dp).background(RULE))
    }
}

/**
 * "Ramen ······ 12  5′": a cuisine (21 px), its italic sub-styles, the dotted leader, the count and the nearest walk
 * (30 px, right) in Geist, 8 px apart on the board's 25.5 px row lined up on its baseline (the subs 4.5 px down, the
 * count 5.5, the walk 6.5, the dots' band 18). A line that opens a cuisine world is set in its family's colour on a
 * faint band reaching 10 px past the column (4 px above and below), as the board's Ramen; a line whose places are all
 * closed is faded.
 */
@Composable
private fun CarteLineRow(line: CarteLine, countsOpen: Boolean, unmappedNote: String, world: Boolean, faded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val closed = countsOpen && line.allClosed
    val ink = if (world) Color(line.family.colour) else MENU_INK
    val leader = if (world) Color(0xFF9DA8C4) else LEADER
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (closed || faded) 0.55f else 1f)
            .widen(if (world) 10.dp else 0.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (world) Color(line.family.colour).copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (world) 10.dp else 0.dp, vertical = if (world) 4.dp else 0.dp),
    ) {
        val sub = if (line.cuisine == CarteModel.UNMAPPED_RESTAURANT || line.cuisine == CarteModel.UNMAPPED_FAST_FOOD) unmappedNote else line.subStyles.joinToString(", ")
        LeaderLine(
            leader, Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp, height = 25.5.dp, dotsTop = 18.dp,
            start = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CssLine(line.name, CUISINE.copy(fontWeight = if (world) FontWeight.Bold else FontWeight.SemiBold), Modifier.weight(1f, fill = false), color = ink)
                    if (sub.isNotEmpty()) CssLine(sub, SUBS, Modifier.weight(1f, fill = false).padding(top = 4.5.dp))
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CssLine(
                    if (closed) line.places.size.toString() else line.open.toString(),
                    COUNT.copy(fontWeight = if (world) FontWeight.Bold else FontWeight.SemiBold, fontStyle = if (closed) FontStyle.Italic else FontStyle.Normal),
                    Modifier.padding(top = 5.5.dp),
                    color = ink,
                )
                CssLine("${line.nearestWalk}′", WALK, Modifier.padding(top = 6.5.dp).width(30.dp), color = if (world) ink else MENU_SOFT)
            }
        }
    }
}

/** The binder's coloured tabs (30 × 46, 3 apart, the chapter in view 38 wide), from IT to EU at 250 px; a family with no places is faint. */
@Composable
private fun ThumbIndex(present: Set<CuisineFamily>, current: CuisineFamily?, modifier: Modifier = Modifier, onJump: (CuisineFamily) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.End) {
        for (f in CuisineFamily.entries) {
            if (f == CuisineFamily.MORE && f !in present) continue
            val on = f in present
            val name = stringResource(familyName(f))
            Box(
                Modifier
                    .size(width = if (f == current) 38.dp else 30.dp, height = 46.dp)
                    .cssBox(bg = Color(f.colour).copy(alpha = if (on) 1f else 0.3f).css(), r = CssCorners(6f, 0f, 0f, 6f))
                    .clickable(enabled = on) { onJump(f) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                // CSS `writing-mode: vertical-rl`: the code read top to bottom, turned a quarter clockwise.
                Text(f.code, Modifier.rotate(90f), style = TAB_CODE, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The foot: the paper fading in over the list (104 px from 12 px in, opaque from 26 %), and the dark diet bar (58 px,
 * 26 from the bottom, 8 px in): V VG GF H K (42 px, 6 apart, ringed 1.5 px of #8A7A63, green when on) and the map.
 */
@Composable
private fun DietBar(diets: Set<Diet>, counts: Map<Diet, Int>, onToggle: (Diet) -> Unit, onMap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .background(Brush.verticalGradient(0f to PAPER.copy(alpha = 0f), 0.26f to PAPER, 1f to PAPER))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 18.dp, end = 16.dp, top = 20.dp, bottom = 26.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).cssBox(bg = 0xFF2A1D14, r = round(58f)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (d in Diet.entries) {
                val on = d in diets
                val name = stringResource(dietName(d))
                val none = (counts[d] ?: 0) == 0
                val ring = TAB_OFF.copy(alpha = if (none) 0.45f else 1f).css()
                Box(
                    Modifier
                        .size(42.dp)
                        .then(
                            if (on) Modifier.cssBox(bg = 0xFF5FAE6A, r = round(42f))
                            else Modifier.cssBox(r = round(42f), border = CssBorder(1.5f, 1.5f, 1.5f, 1.5f, longArrayOf(ring, ring, ring, ring))),
                        )
                        .clip(CircleShape)
                        .toggleable(on, role = Role.Switch) { onToggle(d) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    CssLine(d.code, DIET, color = if (on) Color.White else Color(0xFFE9DCC4).copy(alpha = if (none) 0.45f else 1f))
                }
            }
            Spacer(Modifier.weight(1f))
            val mapLabel = stringResource(R.string.eat_map)
            Box(Modifier.size(42.dp).cssBox(bg = 0xFFF4E6C8, r = round(42f)).clip(CircleShape).clickable(onClick = onMap).semantics { contentDescription = mapLabel }, contentAlignment = Alignment.Center) {
                Symbol("tab.map", tint = MENU_INK, size = 19.dp)
            }
        }
    }
}

internal fun familyName(f: CuisineFamily): Int = when (f) {
    CuisineFamily.ITALY -> R.string.eat_family_it
    CuisineFamily.JAPAN -> R.string.eat_family_jp
    CuisineFamily.CHINA_KOREA -> R.string.eat_family_cn
    CuisineFamily.SOUTHEAST_ASIA -> R.string.eat_family_sea
    CuisineFamily.INDIA -> R.string.eat_family_in
    CuisineFamily.MIDDLE_EAST -> R.string.eat_family_me
    CuisineFamily.AFRICA -> R.string.eat_family_af
    CuisineFamily.AMERICAS -> R.string.eat_family_am
    CuisineFamily.EUROPE -> R.string.eat_family_eu
    CuisineFamily.MORE -> R.string.eat_family_more
}

internal fun dietName(d: Diet): Int = when (d) {
    Diet.VEGETARIAN -> R.string.eat_diet_vegetarian
    Diet.VEGAN -> R.string.eat_diet_vegan
    Diet.GLUTEN_FREE -> R.string.eat_diet_gluten_free
    Diet.HALAL -> R.string.eat_diet_halal
    Diet.KOSHER -> R.string.eat_diet_kosher
}

/** Lets a row reach [by] past each side of its column, as the board's `margin: 0 -10px`. */
private fun Modifier.widen(by: androidx.compose.ui.unit.Dp): Modifier = layout { m, c ->
    val extra = (by * 2).roundToPx()
    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}
