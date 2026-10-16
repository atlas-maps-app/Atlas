package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.nav.Navigator
import app.atlas.places.Symbols
import app.atlas.providers.Rating
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.backdropSource
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.frosted
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberSpeechInput
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INK = Color(0xFF16151A)
private val FLAME = Color(0xFFE8622C)
private val OPEN_GREEN = Color(0xFF178A4E)
private val MUTED = Color(0xFF464C5A)

/* The board's type: Geist, Instrument Serif for the cards' names. */
private val FIELD = cssText("Geist", 16f, 500, colour = 0xFF14161B, lineHeight = 20.5f)
private val PRICE = cssText("Geist", 14f, 700, lineHeight = 18f, spacing = 0.28f)
private val TAB_TEXT = cssText("Geist", 14f, 600, lineHeight = 18f)
private val COUNT = cssText("Geist", 13f, colour = 0xFF464C5A, lineHeight = 17f, tnum = true)
private val CARD_NAME = cssText("Instrument Serif", 25f, colour = 0xFF14161B, lineHeight = 25f)
private val CARD_WALK = cssText("Geist", 13f, colour = 0xFF464C5A, lineHeight = 17f, tnum = true)
private val CARD_SCORE = cssText("Geist", 26f, 700, colour = 0xFF14161B, lineHeight = 26f, tnum = true)
private val CHIP = cssText("Geist", 11f, 600, colour = 0xFF14161B, lineHeight = 14f, tnum = true)
private val SOURCES = cssText("Geist", 11f, colour = 0xFF464C5A, lineHeight = 14f, tnum = true)
private val CARD_NOTE = cssText("Geist", 13f, 600, colour = 0xFF464C5A, lineHeight = 17f)
private val CARD_PRICE = cssText("Geist", 13f, 700, lineHeight = 17f, spacing = 0.26f)
private val OPEN_TIME = cssText("Geist", 13f, 600, lineHeight = 17f, tnum = true)

/**
 * The eat map, as on the Eat board: places to eat as pins (a ring for how long each stays open, a flame when its
 * reviews are rising, a sparkle when it is new), round filters, and a sheet of cards by Trending, Top, New and Loved.
 * Each card has a drawn plate for its cuisine, the ratings each provider gave (or none yet, honestly), the trend of
 * its reviews, price, symbols and an open-until dial. Tabs with nothing to go on say so and show the nearest open.
 */
@Composable
fun EatMapScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    val services = ctx.services
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val fix by services.location.last.collectAsStateWithLifecycle()
    val jotted by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val aggregates by ctx.repo.ratings.aggregates.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(EatTab.TRENDING) }
    var filters by rememberSaveable { mutableStateOf(emptySet<EatFilter>()) }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<Waiter.Answer?>(null) }
    val loved = remember(jotted) { jotted.map { it.key }.toSet() }
    val worked = rememberWorked(ctx.area, ctx.at, tab, filters, ctx.ratings, loved) { EatMapModel.view(ctx.area, ctx.at, tab, filters, ctx.ratings, loved) }
    val view = worked.value
    val places = answer?.let { a -> a.hits.mapNotNull { ctx.area[it.place.key] } } ?: view?.places.orEmpty()
    val map = remember { MapState(CameraPosition(ctx.area.centre, 16.2, 0.0, 40.0)) }
    val carousel = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selected by remember(places) { derivedStateOf { places.getOrNull(carousel.firstVisibleItemIndex)?.key } }
    val ask: (String) -> Unit = { text ->
        if (text.isNotBlank()) scope.launch { answer = Waiter.askSafely { withContext(Dispatchers.Default) { Waiter.ask(text, ctx.area, ctx.at, services.clock.zone, ctx.lang, limit = 30) } } }
    }
    val mic = rememberSpeechInput(onText = { question = it; ask(it) })

    val markers = remember(places, selected, view, ctx.at) {
        places.mapIndexed { i, p ->
            val rising = EatMapModel.trendOf(p, ctx.ratings)?.rising == true
            Marker(
                id = p.key,
                at = p.location,
                symbol = Symbols.symbolFor(p.tags),
                colour = 0xFF14161B,
                style = MarkerStyle.PIN,
                badge = when {
                    rising -> "ui-flame"
                    view?.fresh?.containsKey(p.key) == true -> "ui-sparkles"
                    else -> null
                },
                ring = EatMapModel.ring(p, ctx.at, ctx.area),
                selected = p.key == selected,
                priority = places.size - i + if (p.key == selected) 1_000 else 0,
                place = p.place,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        AtlasMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize().backdropSource(LocalBackdrop.current),
            overlays = MapOverlays(markers = markers, location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) }),
            contentPadding = PaddingValues(top = 150.dp, bottom = 408.dp),
            onTap = { hit ->
                when (hit) {
                    is MapHit.OnMarker -> places.indexOfFirst { it.key == hit.marker.id }.takeIf { it >= 0 }?.let { scope.launch { carousel.animateScrollToItem(it) } }
                    is MapHit.OnFeature -> ctx.open(nav, hit.place)
                    is MapHit.Empty -> Unit
                }
            },
        )
        // The top at 44 px, 12 px in: back (52 px of frosted glass) and the search pill, 8 apart; the filters 10 under.
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = boardTop(44.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val back = stringResource(R.string.kit_back)
                Box(
                    Modifier.size(52.dp).cssShadow(CircleShape, y = 6.dp, blur = 20.dp, colour = Color(0x1F181C30)).frosted(CircleShape, Color(0xDBFFFFFF), 22.dp).clip(CircleShape)
                        .clickable { nav.back() }.semantics { contentDescription = back },
                    contentAlignment = Alignment.Center,
                ) { Symbol("ui.back", tint = Color(0xFF14161B), size = 22.dp) }
                SearchPill(
                    question,
                    hint = stringResource(mealName(Meal.at(ctx.at.toLocalTime()))).lowercase() + " " + stringResource(R.string.eat_order_for, ctx.table.party),
                    onText = { question = it; if (it.isBlank()) answer = null },
                    onAsk = { ask(question) },
                    onMic = mic,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (f in EatFilter.entries) {
                    val n = view?.filterCounts?.get(f) ?: 0
                    FilterBall(f, stringResource(eatFilterName(f)), on = f in filters, enabled = n > 0) { filters = if (f in filters) filters - f else filters + f }
                }
            }
        }
        // The sheet: 408 px up from the foot (its top at 507 on the board), the handle 10 px down, the tabs 14 under it,
        // the cards 14 under the tabs.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(408.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .cssBox(bg = 0xFFFBFAF7, r = CssCorners(32f, 32f, 0f, 0f), shadows = listOf(CssShadow(0f, -10f, 40f, 0f, 0x29181C30)))
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 5.dp).cssBox(bg = 0xFFD8D4CC, r = corners(3f)))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                answer?.let { a ->
                    TabPill(a.parsed.chips.joinToString(" · ") { it.label }.ifEmpty { a.text }, "fact.new", on = true, tint = Color(0xFF7B4DFF)) { answer = null; question = "" }
                } ?: run {
                    TabPill(stringResource(R.string.eat_tab_trending), "fact.trending", on = tab == EatTab.TRENDING, tint = Color(0xFFFF8A4C)) { tab = EatTab.TRENDING }
                    TabBall("ui.star", stringResource(R.string.eat_tab_top), on = tab == EatTab.TOP, tint = INK) { tab = EatTab.TOP }
                    TabBall("fact.new", stringResource(R.string.eat_tab_new), on = tab == EatTab.NEW, tint = Color(0xFF8A5A00)) { tab = EatTab.NEW }
                    TabBall("ui.heart", stringResource(R.string.eat_tab_loved), on = tab == EatTab.LOVED, tint = Color(0xFF7B4DFF)) { tab = EatTab.LOVED }
                }
                Spacer(Modifier.weight(1f))
                CssLine(places.size.toString(), COUNT)
            }
            if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
            if (places.isEmpty() && view != null && !ctx.area.loading) {
                Text(stringResource(R.string.eat_map_none), Modifier.padding(18.dp), style = Atlas.type.body, color = Atlas.colors.inkSoft)
            }
            LazyRow(state = carousel, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(places, key = { _, p -> p.key }) { _, p ->
                    LaunchedEffect(p.key) { ctx.repo.ratings.ask(p) }
                    EatCard(p, ctx, aggregates[p.key]?.sources, onOpen = { ctx.open(nav, p.place) })
                }
            }
            // What the tab could not go on, under the cards, where the board's sheet has room, so the cards keep their place.
            if (answer == null && view != null && !view.ranked) {
                Text(
                    stringResource(honestNote(tab, ctx.ratings.state)),
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp),
                    style = cssText("Geist", 12f, colour = 0xFF464C5A, lineHeight = 16f),
                    maxLines = 2,
                )
            }
            if (ctx.area.waiting || !worked.fresh) Looking(Atlas.colors.inkSoft, vertical = 4.dp)
        }
    }
    LaunchedEffect(selected) {
        val p = places.firstOrNull { it.key == selected } ?: return@LaunchedEffect
        map.animateTo(map.camera.copy(target = p.location), 500)
    }
}

/** The search pill (52 px, white, 0 6 20 shadow, 16 px in, 10 apart): the cutlery chip (30), the waiter's field ("dinner for 2") and the mic (20). */
@Composable
private fun SearchPill(text: String, hint: String, onText: (String) -> Unit, onAsk: () -> Unit, onMic: () -> Unit, modifier: Modifier) {
    val micLabel = stringResource(R.string.kit_mic)
    val askLabel = stringResource(R.string.eat_ask)
    Row(
        modifier.height(52.dp).cssBox(bg = 0xFFFFFFFF, r = round(52f), shadows = listOf(CssShadow(0f, 6f, 20f, 0f, 0x1F181C30))).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(30.dp).cssBox(bg = 0xFFFCE5D8, r = round(30f)), contentAlignment = Alignment.Center) { Symbol("cat.eat", tint = Color(0xFFB8461A), size = 17.dp) }
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) CssLine(hint, FIELD)
            BasicTextField(
                value = text, onValueChange = onText, singleLine = true,
                textStyle = FIELD.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAsk() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = askLabel },
            )
        }
        // The mic in a 36 px target whose centre is the board's (16 px in from the pill's end).
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onMic).semantics { contentDescription = micLabel }, contentAlignment = Alignment.Center) {
            Symbol("ui.mic", tint = MUTED, size = 20.dp)
        }
    }
}

/** A round filter (42 px): dark when on (the star turns gold), 88 % white when off with the board's tints. */
@Composable
private fun FilterBall(f: EatFilter, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.4f
    val tint = when {
        on && f == EatFilter.RATED -> Color(0xFFFFC53D)
        on -> Color.White
        f == EatFilter.VEGAN -> Color(0xFF2F6B34)
        f == EatFilter.WHEELCHAIR -> Color(0xFF1F5F99)
        f == EatFilter.DOGS -> Color(0xFF6B4A2E)
        else -> INK
    }.copy(alpha = alpha)
    Box(
        Modifier
            .height(42.dp)
            .then(if (f == EatFilter.PRICE) Modifier else Modifier.size(42.dp))
            .cssBox(bg = if (on) 0xFF14161B else 0xE0FFFFFF, r = round(42f))
            .clip(CircleShape)
            .toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }
            .semantics { contentDescription = description }
            .then(if (f == EatFilter.PRICE) Modifier.padding(horizontal = 12.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (f) {
            EatFilter.PRICE -> Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = (if (on) Color.White else INK).copy(alpha = alpha))) { append("$$") }
                    withStyle(SpanStyle(color = Color(0xFF9AA0AC).copy(alpha = alpha))) { append("$") }
                },
                Modifier.lineBox(18f),
                style = PRICE.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
            )
            else -> Symbol(filterSymbol(f), tint = tint, size = 19.dp)
        }
    }
}

private fun filterSymbol(f: EatFilter): String = when (f) {
    EatFilter.OPEN -> "ui.clock"
    EatFilter.RATED -> "ui.star"
    EatFilter.VEGAN -> "fact.vegan"
    EatFilter.TERRACE -> "fact.terrace"
    EatFilter.WHEELCHAIR -> "fact.wheelchair"
    EatFilter.DOGS -> "fact.dog"
    EatFilter.RESERVE -> "ui.calendar"
    EatFilter.PRICE -> "ui.star"
}

/** The chosen tab's pill (40 px, dark, 11 and 14 px in): its symbol (16) and name, 7 apart. */
@Composable
private fun TabPill(text: String, symbol: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).cssBox(bg = if (on) 0xFF14161B else 0xFFF1EEE8, r = round(40f)).clip(CircleShape).selectable(on, role = Role.Tab, onClick = onClick).padding(start = 11.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Symbol(symbol, tint = tint, size = 16.dp)
        CssLine(text, TAB_TEXT, color = if (on) Color.White else INK)
    }
}

/** A tab not chosen (44 × 40, #F1EEE8) with its symbol (17); dark when chosen. */
@Composable
private fun TabBall(symbol: String, description: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp, 40.dp).cssBox(bg = if (on) 0xFF14161B else 0xFFF1EEE8, r = round(40f)).clip(CircleShape).selectable(on, role = Role.Tab, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = if (on && tint == INK) Color.White else tint, size = 17.dp) }
}

/**
 * A card of the carousel (300 px): the plate, name and walk; the rating with each provider's chip, or "no ratings
 * yet" with a way to write one; the review sparkline; price, symbols and the open-until dial.
 */
@Composable
private fun EatCard(p: EatPlace, ctx: EatContext, sources: List<Rating>?, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val area = ctx.area
    val single = ctx.ratings[p.key]
    val rated = (sources?.filter { it.stars != null } ?: listOfNotNull(single?.takeIf { it.stars != null }))
    val dish = remember(p.key) { dishOf(p, area.country) }
    Column(
        Modifier.width(300.dp)
            .cssBox(bg = 0xFFFFFFFF, r = corners(24f), shadows = listOf(CssShadow(0f, 2f, 10f, 0f, 0x14181C30), CssShadow(0f, 0f, 0f, 1f, 0x0D14161B)))
            .clip(shape).clickable(onClick = onOpen),
    ) {
        Canvas(Modifier.fillMaxWidth().height(116.dp)) { drawPlate(dish, p.key.hashCode()) }
        // 12 14 14 px in, 10 between the rows: the name on its 25 px line (the walk on its baseline, 6 px down), the
        // ratings (35 px, centred), the facts (22 px, centred).
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.height(25.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CssLine(p.name, CARD_NAME, Modifier.weight(1f))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Symbol("mode.walk", tint = MUTED, size = 13.dp)
                    CssLine("${p.walkMinutes}′", CARD_WALK)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rated.isNotEmpty()) {
                    val score = rated.mapNotNull { it.stars }.average()
                    CssLine(starsText(score), CARD_SCORE)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (r in rated.take(3)) ProviderChip(r) }
                        val count = rated.sumOf { it.count }
                        CssLine(stringResource(R.string.eat_card_sources, NumberFormat.getIntegerInstance(Locale.forLanguageTag(ctx.lang)).format(count), rated.size), SOURCES)
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        CssLine(stringResource(R.string.eat_no_ratings_yet), CARD_NOTE)
                        CssLine(stringResource(R.string.eat_card_write_one), CARD_NOTE, Modifier.clickable(onClick = onOpen), color = Color(0xFF2E5BFF))
                    }
                }
                EatMapModel.sparkline(single?.trend)?.let { Sparkline(it, Modifier.size(width = 64.dp, height = 28.dp)) }
            }
            Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PriceLevel(single?.priceLevel ?: sources?.firstNotNullOfOrNull { it.priceLevel })
                if (p.outside) Symbol("fact.terrace", tint = MUTED, size = 18.dp, contentDescription = stringResource(R.string.eat_fact_terrace))
                if (p.diet("vegan") || p.diet("vegetarian")) Symbol("fact.vegan", tint = Color(0xFF2F6B34), size = 18.dp, contentDescription = stringResource(R.string.eat_diet_vegetarian))
                if (p.stepFree) Symbol("fact.wheelchair", tint = MUTED, size = 18.dp, contentDescription = stringResource(R.string.eat_order_step_free))
                if (EatFilter.RESERVE.has(p, ctx.at, area, ctx.ratings)) Symbol("ui.calendar", tint = MUTED, size = 18.dp, contentDescription = stringResource(R.string.eat_wish_reservable))
                Spacer(Modifier.weight(1f))
                OpenUntil(p, ctx)
            }
        }
    }
}

/** "G 4.6": a provider's letter in its colour and its stars on a #F1EEE8 chip (18 px). */
@Composable
private fun ProviderChip(r: Rating) {
    val colour = when (r.provider.lowercase()) {
        "mangrove" -> Color(0xFF2F6B34)
        "yelp" -> Color(0xFFB03A3A)
        "foursquare" -> Color(0xFF7B4DFF)
        "tripadvisor" -> Color(0xFF178A4E)
        else -> Color(0xFF1F5F99)
    }
    Row(
        Modifier.height(18.dp).cssBox(bg = 0xFFF1EEE8, r = corners(5f)).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CssLine(r.provider.take(1).uppercase(), CHIP.copy(fontWeight = FontWeight.Black), color = colour)
        CssLine(r.stars?.let { starsText(it) } ?: "–", CHIP)
    }
}

/** "$$$" with the known level dark and the rest grey; nothing when no provider gave a price. */
@Composable
private fun PriceLevel(level: Int?) {
    if (level == null) return
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = INK)) { append("$".repeat(level)) }
            withStyle(SpanStyle(color = Color(0xFFB5B9C2))) { append("$".repeat(4 - level)) }
        },
        Modifier.lineBox(17f),
        style = CARD_PRICE.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
    )
}

/** Reviews a month over the last year and more, as the board's orange line ending in a dot. */
@Composable
private fun Sparkline(points: FloatArray, modifier: Modifier) {
    val label = stringResource(R.string.eat_trend_desc)
    Canvas(modifier.semantics { contentDescription = label }) {
        val max = points.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val p = Path()
        val r = 2.5.dp.toPx()
        points.forEachIndexed { i, v ->
            val x = r + i * (size.width - 2 * r) / (points.size - 1)
            val y = size.height - r - v / max * (size.height - 2 * r)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, FLAME, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(FLAME, r, Offset(size.width - r, size.height - r - points.last() / max * (size.height - 2 * r)))
    }
}

/**
 * The open-until dial (22 px), coloured as the board's: a pale green face, today's open hours as a green band on a
 * 24-hour clock (midnight at the top), a hand at the table's time, then "23:00".
 */
@Composable
private fun OpenUntil(p: EatPlace, ctx: EatContext) {
    val (text, colour) = openWords(p, ctx) ?: return
    val spans = remember(p.key, ctx.at.toLocalDate()) { p.hours?.spans(ctx.at.toLocalDate(), ctx.area.hours).orEmpty() }
    val ink = if (colour == Atlas.colors.open) OPEN_GREEN else colour
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(22.dp)) {
            val k = size.width / 24f
            val c = Offset(12 * k, 12 * k)
            drawCircle(Color(0xFFE7F4EC), 10 * k, c)
            for (sp in spans) {
                if (sp.state != app.atlas.places.OpenState.OPEN) continue
                val from = sp.start / 1440f * 360f - 90f
                val sweep = (minOf(sp.end, 1440) - sp.start) / 1440f * 360f
                drawArc(OPEN_GREEN.copy(alpha = 0.75f), from, sweep, true, Offset(2 * k, 2 * k), Size(20 * k, 20 * k))
            }
            drawCircle(OPEN_GREEN, 10 * k, c, style = Stroke(1.4f * k))
            val now = ctx.at.toLocalTime()
            val a = Math.toRadians((now.hour * 60 + now.minute) / 1440.0 * 360.0 - 90.0)
            drawLine(INK, c, c + Offset((kotlin.math.cos(a) * 7.5 * k).toFloat(), (kotlin.math.sin(a) * 7.5 * k).toFloat()), 1.6f * k, StrokeCap.Round)
        }
        CssLine(text.removePrefix("→ "), OPEN_TIME, color = ink)
    }
}

private fun honestNote(tab: EatTab, state: AreaRatings.State): Int = when (tab) {
    EatTab.TRENDING, EatTab.TOP -> when (state) {
        AreaRatings.State.OFFLINE -> R.string.eat_map_no_ratings_offline
        AreaRatings.State.LOADING -> R.string.eat_map_ratings_loading
        else -> R.string.eat_map_no_ratings
    }
    EatTab.NEW -> R.string.eat_map_nothing_new
    EatTab.LOVED -> R.string.eat_map_nothing_loved
}

private fun eatFilterName(f: EatFilter): Int = when (f) {
    EatFilter.OPEN -> R.string.kit_open
    EatFilter.RATED -> R.string.eat_filter_rated
    EatFilter.VEGAN -> R.string.eat_diet_vegetarian
    EatFilter.TERRACE -> R.string.eat_fact_terrace
    EatFilter.WHEELCHAIR -> R.string.eat_order_step_free
    EatFilter.DOGS -> R.string.eat_wish_dogs
    EatFilter.RESERVE -> R.string.eat_wish_reservable
    EatFilter.PRICE -> R.string.eat_filter_price
}
