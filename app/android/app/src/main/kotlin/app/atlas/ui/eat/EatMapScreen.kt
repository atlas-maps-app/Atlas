package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.softShadow
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INK = Color(0xFF16151A)
private val FLAME = Color(0xFFE8622C)
private val OPEN_GREEN = Color(0xFF178A4E)
private val MUTED = Color(0xFF464C5A)

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
        if (text.isNotBlank()) scope.launch { answer = withContext(Dispatchers.Default) { Waiter.ask(text, ctx.area, ctx.at, services.clock.zone, ctx.lang, limit = 30) } }
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
            modifier = Modifier.fillMaxSize(),
            overlays = MapOverlays(markers = markers, location = fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) }),
            contentPadding = PaddingValues(top = 150.dp, bottom = 380.dp),
            onTap = { hit ->
                when (hit) {
                    is MapHit.OnMarker -> places.indexOfFirst { it.key == hit.marker.id }.takeIf { it >= 0 }?.let { scope.launch { carousel.animateScrollToItem(it) } }
                    is MapHit.OnFeature -> ctx.open(nav, hit.place)
                    is MapHit.Empty -> Unit
                }
            },
        )
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = boardTop(44.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val back = stringResource(R.string.kit_back)
                Box(
                    Modifier.size(52.dp).boxShadow(CircleShape, 1f, 6f, 20f, Color(0x1F181C30)).clip(CircleShape).background(Color(0xDBFFFFFF))
                        .clickable { nav.back() }.semantics { contentDescription = back },
                    contentAlignment = Alignment.Center,
                ) { BoardIcon(EatGlyphs.backMap, INK, 22.dp) }
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
        val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .boxShadow(sheet, 1f, -10f, 40f, Color(0x29181C30))
                .clip(sheet)
                .background(Color(0xFFFBFAF7))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 5.dp).clip(CircleShape).background(Color(0xFFD8D4CC)))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                answer?.let { a ->
                    TabPill(a.parsed.chips.joinToString(" · ") { it.label }.ifEmpty { a.text }, EatGlyphs.sparkle, on = true, tint = Color(0xFF7B4DFF)) { answer = null; question = "" }
                } ?: run {
                    TabPill(stringResource(R.string.eat_tab_trending), EatGlyphs.flame, on = tab == EatTab.TRENDING, tint = Color(0xFFFF8A4C)) { tab = EatTab.TRENDING }
                    TabBall(EatGlyphs.star, stringResource(R.string.eat_tab_top), on = tab == EatTab.TOP, tint = INK) { tab = EatTab.TOP }
                    TabBall(EatGlyphs.sparkle, stringResource(R.string.eat_tab_new), on = tab == EatTab.NEW, tint = Color(0xFF8A5A00)) { tab = EatTab.NEW }
                    TabBall(EatGlyphs.heart, stringResource(R.string.eat_tab_loved), on = tab == EatTab.LOVED, tint = Color(0xFF7B4DFF)) { tab = EatTab.LOVED }
                }
                Spacer(Modifier.weight(1f))
                Text(places.size.toString(), style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = MUTED))
            }
            if (answer == null && view != null && !view.ranked) {
                Text(
                    stringResource(honestNote(tab, ctx.ratings.state)),
                    Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp),
                    style = Atlas.type.caption,
                    color = Atlas.colors.inkSoft,
                    maxLines = 2,
                )
            }
            if (ctx.area.waiting || !worked.fresh) Looking(Atlas.colors.inkSoft, vertical = 4.dp)
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
        }
    }
    LaunchedEffect(selected) {
        val p = places.firstOrNull { it.key == selected } ?: return@LaunchedEffect
        map.animateTo(map.camera.copy(target = p.location), 500)
    }
}

/** The search pill (52 px, white): the cutlery chip, the waiter's field ("dinner for 2") and the mic. */
@Composable
private fun SearchPill(text: String, hint: String, onText: (String) -> Unit, onAsk: () -> Unit, onMic: () -> Unit, modifier: Modifier) {
    val micLabel = stringResource(R.string.kit_mic)
    Row(
        modifier.height(52.dp).boxShadow(CircleShape, 1f, 6f, 20f, Color(0x1F181C30)).clip(CircleShape).background(Color.White).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFFCE5D8)), contentAlignment = Alignment.Center) { BoardIcon(EatGlyphs.cutleryChip, Color(0xFFB8461A), 17.dp) }
        Box(Modifier.weight(1f)) {
            val style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = INK)
            if (text.isEmpty()) Text(hint, style = style)
            BasicTextField(
                value = text, onValueChange = onText, singleLine = true,
                textStyle = style,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAsk() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ask" },
            )
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onMic).semantics { contentDescription = micLabel }, contentAlignment = Alignment.Center) {
            BoardIcon(EatGlyphs.mic, MUTED, 20.dp)
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
            .clip(CircleShape)
            .background(if (on) INK else Color(0xE0FFFFFF))
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
                style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.02.em),
            )
            else -> BoardIcon(filterGlyph(f), tint, 19.dp)
        }
    }
}

private fun filterGlyph(f: EatFilter): BoardGlyph = when (f) {
    EatFilter.OPEN -> EatGlyphs.clockFilter
    EatFilter.RATED -> EatGlyphs.star
    EatFilter.VEGAN -> EatGlyphs.leafFilter
    EatFilter.TERRACE -> EatGlyphs.umbrella
    EatFilter.WHEELCHAIR -> EatGlyphs.stepFree
    EatFilter.DOGS -> EatGlyphs.paw
    EatFilter.RESERVE -> EatGlyphs.calendar
    EatFilter.PRICE -> EatGlyphs.star
}

/** The chosen tab's pill (40 px, dark): its glyph and name. */
@Composable
private fun TabPill(text: String, glyph: BoardGlyph, on: Boolean, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(if (on) INK else Color(0xFFF1EEE8)).selectable(on, role = Role.Tab, onClick = onClick).padding(start = 11.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BoardIcon(glyph, tint, 16.dp)
        Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Color.White else INK), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A tab not chosen (44 × 40, #F1EEE8) with its glyph; dark when chosen. */
@Composable
private fun TabBall(glyph: BoardGlyph, description: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp, 40.dp).clip(CircleShape).background(if (on) INK else Color(0xFFF1EEE8)).selectable(on, role = Role.Tab, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(glyph, if (on && tint == INK) Color.White else tint, 17.dp) }
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
        Modifier.width(300.dp).boxShadow(shape, 1f, 2f, 10f, Color(0x14181C30)).clip(shape).background(Color.White).border(1.dp, Color(0x0D14161B), shape).clickable(onClick = onOpen),
    ) {
        Canvas(Modifier.fillMaxWidth().height(116.dp)) { drawPlate(dish, p.key.hashCode()) }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, Modifier.weight(1f), style = TextStyle(fontFamily = SerifFamily, fontSize = 25.sp, lineHeight = 26.sp, color = INK), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BoardIcon(EatGlyphs.walkCard, MUTED, 13.dp)
                    Text("${p.walkMinutes}′", style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = MUTED))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rated.isNotEmpty()) {
                    val score = rated.mapNotNull { it.stars }.average()
                    Text(starsText(score), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 26.sp, color = INK))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (r in rated.take(3)) ProviderChip(r) }
                        val count = rated.sumOf { it.count }
                        Text(
                            stringResource(R.string.eat_card_sources, NumberFormat.getIntegerInstance(Locale.forLanguageTag(ctx.lang)).format(count), rated.size),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = MUTED),
                        )
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.eat_no_ratings_yet), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MUTED))
                        Text(stringResource(R.string.eat_card_write_one), Modifier.clickable(onClick = onOpen), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF2E5BFF)))
                    }
                }
                EatMapModel.sparkline(single?.trend)?.let { Sparkline(it, Modifier.size(width = 64.dp, height = 28.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PriceLevel(single?.priceLevel ?: sources?.firstNotNullOfOrNull { it.priceLevel })
                if (p.outside) BoardIcon(EatGlyphs.umbrella, MUTED, 18.dp, contentDescription = stringResource(R.string.eat_fact_terrace))
                if (p.diet("vegan") || p.diet("vegetarian")) BoardIcon(EatGlyphs.leafFilter, Color(0xFF2F6B34), 18.dp, contentDescription = stringResource(R.string.eat_diet_vegetarian))
                if (p.stepFree) BoardIcon(EatGlyphs.stepFree, MUTED, 18.dp, contentDescription = stringResource(R.string.eat_order_step_free))
                if (EatFilter.RESERVE.has(p, ctx.at, area, ctx.ratings)) BoardIcon(EatGlyphs.calendar, MUTED, 18.dp, contentDescription = stringResource(R.string.eat_wish_reservable))
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
        Modifier.height(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF1EEE8)).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = INK)
        Text(r.provider.take(1).uppercase(), style = style.copy(fontWeight = FontWeight.Bold, color = colour))
        Text(r.stars?.let { starsText(it) } ?: "–", style = style)
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
        style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.02.em),
    )
}

/** Reviews a month over the last year and more, as the board's orange line ending in a dot. */
@Composable
private fun Sparkline(points: FloatArray, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "trend" }) {
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
        Text(text.removePrefix("→ "), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = ink))
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
