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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Cuisine
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.SansFamily
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
            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Flourish(Modifier.size(120.dp, 16.dp))
                    Text(
                        stringResource(R.string.eat_la_carte),
                        Modifier.semantics { heading() },
                        style = TextStyle(fontFamily = EatType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, lineHeight = 46.sp, color = MENU_INK),
                    )
                    val count = carte?.let { c ->
                        if (c.countsOpen) stringResource(R.string.eat_carte_open, c.counted, ctx.at.format(HM_PADDED))
                        else stringResource(R.string.eat_carte_places, c.counted)
                    } ?: stringResource(R.string.kit_loading)
                    Text(
                        smallCaps(count.lowercase(), 15.sp),
                        style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.SemiBold, letterSpacing = 0.12.em, color = Color(0xFF6B5A45), fontFeatureSettings = "lnum"),
                    )
                }
            }
            item { SortTabs(sort, enabledRated = carte?.anyRated == true, onSort = { sort = it }) }
            carte?.let { c ->
                for (ch in c.chapters) {
                    val faded = c.countsOpen && ch.lines.all { it.allClosed }
                    item(key = "h:${ch.family}") {
                        ChapterHeading(ch.family, faded, onWorld = CuisineWorlds.forFamily(ch.family)?.let { w -> { nav.go(Dest.Eat("world:${w.id}")) } })
                    }
                    for (line in ch.lines) {
                        item(key = "l:${line.cuisine}") {
                            val world = CuisineWorlds.forCuisine(line.cuisine)
                            CarteLineRow(line, countsOpen = c.countsOpen, unmappedNote = unmappedNote, world = world != null, faded = faded) {
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
                            style = TextStyle(fontFamily = EatType.cormorant, fontStyle = FontStyle.Italic, fontSize = 21.sp, color = MENU_SOFT),
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
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 206.dp),
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

/** Back, the die for "surprise me", and the burgundy order pad with the shortlist's badge (44 px each). */
@Composable
private fun CarteTopRow(padCount: Int, onBack: () -> Unit, onSurprise: () -> Unit, onPad: () -> Unit) {
    val back = stringResource(R.string.kit_back)
    val surprise = stringResource(R.string.eat_surprise)
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x147A2A28)).clickable(onClick = onBack).semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
            BoardIcon(EatGlyphs.backBold, BURGUNDY, 20.dp)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x147A2A28)).clickable(onClick = onSurprise).semantics { contentDescription = surprise }, contentAlignment = Alignment.Center) {
            BoardIcon(EatGlyphs.diceFour, BURGUNDY, 20.dp)
        }
        Box {
            val padLabel = stringResource(R.string.eat_pad)
            Box(Modifier.size(44.dp).clip(CircleShape).background(BURGUNDY).clickable(onClick = onPad).semantics { contentDescription = padLabel }, contentAlignment = Alignment.Center) {
                BoardIcon(EatGlyphs.pad, Color.White, 19.dp)
            }
            if (padCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(18.dp).clip(CircleShape).background(PAPER).padding(2.dp).clip(CircleShape).background(Color(0xFFE8622C)),
                    contentAlignment = Alignment.Center,
                ) { Text(padCount.toString(), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color.White)) }
            }
        }
    }
}

/** The menu card: #FBF6EA lit from the top, a burgundy spine 12 px wide and its shadow on the paper. */
internal fun DrawScope.drawMenuPaper() {
    drawRect(PAPER)
    drawRect(Brush.radialGradient(0f to Color(0xCCFFFFFF), 0.6f to Color(0x00FFFFFF), center = Offset(size.width / 2f, 0f), radius = size.maxDimension * 0.75f))
    val spine = 12.dp.toPx()
    drawRect(Brush.horizontalGradient(0f to Color(0xFF3A1010), 0.6f to Color(0xFF6A2422), 1f to Color(0xFF4E1817), startX = 0f, endX = spine), size = Size(spine, size.height))
    drawRect(Brush.horizontalGradient(listOf(Color(0x243C1E0A), Color(0x003C1E0A)), spine, spine + 18.dp.toPx()), topLeft = Offset(spine, 0f), size = Size(18.dp.toPx(), size.height))
}

/** The engraved flourish over the title, the board's own (120 × 16). */
@Composable
internal fun Flourish(modifier: Modifier = Modifier, colour: Color = Color(0xFF9A7B4A)) {
    Canvas(modifier) { drawBoardGlyph(EatGlyphs.flourish, colour, side = size.width) }
}

@Composable
private fun SortTabs(sort: CarteSort, enabledRated: Boolean, onSort: (CarteSort) -> Unit) {
    val labels = listOf(
        CarteSort.NEAREST to R.string.eat_sort_nearest,
        CarteSort.MOST to R.string.eat_sort_most,
        CarteSort.BEST_RATED to R.string.eat_sort_rated,
        CarteSort.A_Z to R.string.eat_sort_az,
    )
    val style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.Bold, letterSpacing = 0.1.em)
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, (s, label) ->
            if (i > 0) Text("·", style = style.copy(fontSize = 16.sp, color = TAB_DOT))
            val on = s == sort
            val colour = when {
                on -> BURGUNDY
                s == CarteSort.BEST_RATED && !enabledRated -> TAB_OFF.copy(alpha = 0.55f)
                else -> TAB_OFF
            }
            Text(
                smallCaps(stringResource(label).lowercase(), 16.sp),
                Modifier
                    .selectable(on, role = Role.Tab) { onSort(s) }
                    .drawBehind { if (on) drawLine(BURGUNDY, Offset(0f, size.height), Offset(size.width, size.height), 1.5.dp.toPx()) }
                    .padding(bottom = 1.dp),
                style = style.copy(color = colour),
            )
        }
    }
}

/** A chapter's name in its colour, letter-spaced small caps, then a #D9C9AE rule. */
@Composable
private fun ChapterHeading(f: CuisineFamily, faded: Boolean, onWorld: (() -> Unit)?) {
    val colour = Color(f.colour)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp)
            .alpha(if (faded) 0.55f else 1f)
            .then(if (onWorld != null) Modifier.clickable(onClick = onWorld) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            smallCaps(stringResource(familyName(f)).lowercase(), 15.sp),
            Modifier.semantics { heading() },
            style = TextStyle(fontFamily = EatType.cormorant, fontWeight = FontWeight.Bold, letterSpacing = 0.2.em, color = colour),
        )
        Box(Modifier.weight(1f).height(1.dp).background(RULE))
    }
}

/**
 * "Ramen ······ 12  5′": a cuisine in Cormorant, its italic sub-styles, the dotted leader, the count and the nearest
 * walk in Geist. A line that opens a cuisine world is set in its family's colour on a faint band, as the board's
 * Ramen; a line whose places are all closed is faded.
 */
@Composable
private fun CarteLineRow(line: CarteLine, countsOpen: Boolean, unmappedNote: String, world: Boolean, faded: Boolean, onClick: () -> Unit) {
    val closed = countsOpen && line.allClosed
    val ink = if (world) Color(line.family.colour) else MENU_INK
    val leader = if (world) Color(0xFF9DA8C4) else LEADER
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .alpha(if (closed || faded) 0.55f else 1f)
            .widen(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (world) Color(line.family.colour).copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = EatType.cormorant, fontWeight = if (world) FontWeight.Bold else FontWeight.SemiBold, fontSize = 21.sp, color = ink)) { append(line.name) }
            val sub = if (line.cuisine == CarteModel.UNMAPPED_RESTAURANT || line.cuisine == CarteModel.UNMAPPED_FAST_FOOD) unmappedNote else line.subStyles.joinToString(", ")
            if (sub.isNotEmpty()) {
                withStyle(SpanStyle(fontFamily = EatType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MENU_SOFT)) { append("  "); append(sub) }
            }
        }
        LeaderLine(leader, Modifier.fillMaxWidth(), gap = 4.dp, dot = 2.dp, lift = 5.dp, start = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (closed) line.places.size.toString() else line.open.toString(),
                    style = TextStyle(fontFamily = SansFamily, fontWeight = if (world) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp, color = ink, fontStyle = if (closed) FontStyle.Italic else FontStyle.Normal),
                )
                Text(
                    "${line.nearestWalk}′",
                    Modifier.width(30.dp),
                    style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (world) ink else MENU_SOFT),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** The binder's coloured tabs (30 × 46, the chapter in view 38 wide), from IT to EU; a family with no places is faint. */
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
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(Color(f.colour).copy(alpha = if (on) 1f else 0.3f))
                    .clickable(enabled = on) { onJump(f) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    f.code,
                    Modifier.rotate(90f),
                    style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.1.em, color = Color.White),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The foot: the paper fading in over the list, and the dark diet bar (V VG GF H K) with the map. */
@Composable
private fun DietBar(diets: Set<Diet>, counts: Map<Diet, Int>, onToggle: (Diet) -> Unit, onMap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .background(Brush.verticalGradient(0f to PAPER.copy(alpha = 0f), 0.26f to PAPER, 1f to PAPER))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 18.dp, end = 16.dp, top = 46.dp, bottom = 26.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).clip(CircleShape).background(MENU_INK).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (d in Diet.entries) {
                val on = d in diets
                val name = stringResource(dietName(d))
                val none = (counts[d] ?: 0) == 0
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (on) Color(0xFF5FAE6A) else Color.Transparent)
                        .then(if (on) Modifier else Modifier.border(1.5.dp, TAB_OFF.copy(alpha = if (none) 0.45f else 1f), CircleShape))
                        .toggleable(on, role = Role.Switch) { onToggle(d) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(d.code, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (on) Color.White else Color(0xFFE9DCC4).copy(alpha = if (none) 0.45f else 1f)))
                }
            }
            Spacer(Modifier.weight(1f))
            val mapLabel = stringResource(R.string.eat_map)
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFF4E6C8)).clickable(onClick = onMap).semantics { contentDescription = mapLabel }, contentAlignment = Alignment.Center) {
                BoardIcon(EatGlyphs.map, MENU_INK, 19.dp)
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
