package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.geo.CoordinateFormat
import app.atlas.geo.CoordinateParser
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Categories
import app.atlas.search.QueryResult
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.shortDistance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

/** The Search board's page, ink, greys and accents. */
private const val PAGE = 0xFFF1F3F6
private const val INK = 0xFF14161B
private const val KICKER = 0xFF676D7C
private const val FOOT = 0xFF7A7F8C
private const val ASK = 0xFF7B4DFF
private const val NEAR = 0xFF2E5BFF
private const val NEEDS = 0xFF4B34B8

/** The board's 11 px capitals 0.12 em apart, over each part of the page. */
private val KICKER_STYLE = boardText(11f, 800, 14f, spacing = 1.32f)

/**
 * Search, as on the Search board: the field with the mic and live results as you type (offline, typos forgiven), the
 * chips that narrow a kind of place, each result with its status and walk minutes, "Every pharmacy on the map", the
 * "Works offline" card, recent searches (coordinates and plus codes welcome) and sentences to just say. The board's
 * numbers: the field 50 px at y 42, the chips 32 px at 104, the results card at 150, 14 to 24 px between the parts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(args: Dest.Search, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    val vm = viewModel(key = "search") { SearchViewModel(services, args.query, args.category, { here }, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    val recents by services.saved.recents.collectAsStateWithLifecycle()
    var field by rememberSaveable { mutableStateOf(args.query) }
    val now = rememberNow(state.results)
    val focus = remember { FocusRequester() }
    val mic = rememberSpeechInput(onText = { field = it; vm.onText(it) })
    val coversHere = remember(here) { services.placeStores.bounds?.let { here in it } == true }
    val category = remember(args.category) { args.category?.let { Categories.byId(it) } }
    val searchLabel = stringResource(R.string.search_title)

    val openPlace: (Place) -> Unit = { p ->
        vm.remember(field, p)
        services.remember(p)
        nav.go(Dest.Place.of(p))
    }
    LaunchedEffect(Unit) { if (args.query.isEmpty()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Color(PAGE)).padding(top = boardShift())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp).height(50.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardRoundButton("ui.back", stringResource(R.string.kit_back), { nav.back() }, tint = INK)
            Row(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .boardBox(0xFFFFFFFF, round(25f), listOf(spreadRing(2f, INK)))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BoardSymbol("ui.search.2", 18f, INK)
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; vm.onText(it) },
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = searchLabel },
                    singleLine = true,
                    textStyle = boardText(17f, 600, 22f).copy(color = Color(INK)),
                    cursorBrush = SolidColor(Color(INK)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.remember(field, null) }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (field.isEmpty()) {
                                val hint = category?.let { stringResource(R.string.search_hint_in, it.name(lang)) } ?: stringResource(R.string.search_hint)
                                BoardLine(hint, boardText(17f, 600, 22f), 0xFF9AA0AC)
                            }
                            inner()
                        }
                    },
                )
                BoardSymbol(
                    "ui.mic", 20f, ASK,
                    Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.kit_mic), onClick = mic),
                    description = stringResource(R.string.kit_mic),
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).imePadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            val results = state.results
            if (results != null && results.refinements.isNotEmpty()) {
                item(key = "chips") { RefinementChips(results, state.selected, vm::toggle, Modifier.padding(top = 12.dp)) }
            }
            val card = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)
            when {
                state.text.isBlank() -> Unit
                state.error != null -> item(key = "error") {
                    StateMessage("ui-alert-triangle", stringResource(R.string.search_failed), card, text = state.error, tint = Color(0xFFC8322A))
                }
                results == null -> item(key = "looking") {
                    BoardLine(stringResource(R.string.kit_loading), ROW_LINE, ROW_SOFT, Modifier.padding(start = 18.dp, top = 14.dp))
                }
                state.refined != null -> item(key = "refined") { RefinedCard(state.refined!!, here, lang, now, openPlace, card) }
                results.items.isEmpty() -> item(key = "none") {
                    NothingFound(state.text, coversHere || services.placeStores.size > 0, card, onDownload = { nav.go(Dest.RegionPicker(here.lat, here.lon)) })
                }
                else -> item(key = "results") {
                    ResultsCard(results, here, lang, now, nav, openPlace, onQuery = { vm.remember(field, null) }, card)
                }
            }
            if (coversHere && state.text.isNotBlank()) item(key = "offline") { WorksOffline(Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp)) }
            item(key = "recent") {
                Recents(
                    recents = recents.take(8),
                    onText = { t -> field = t; vm.onText(t) },
                    onPlace = openPlace,
                    onPoint = { p -> nav.go(Dest.WhatsHere(p.lat, p.lon)) },
                    onClear = { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.clearRecents() } } },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                )
            }
            if (state.text.isBlank()) {
                item(key = "lists") { ListsRow(nav, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) }
                item(key = "more") { MoreWays(nav, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) }
            }
            item(key = "say") { JustSayIt(nav, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) }
            if (state.text.isBlank()) {
                item(key = "kinds") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoardLine(stringResource(R.string.search_categories).uppercase(Locale.getDefault()), KICKER_STYLE, KICKER, Modifier.padding(start = 2.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (cat in Categories.groups()) {
                                SmallChip(cat.name(lang), symbol = cat.symbol, symbolTint = cat.colour.toLong() and 0xFFFFFFFFL, onClick = { nav.go(Dest.Category(cat.id)) })
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.search_footer),
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 13.dp),
                    style = boardText(11f, 400, 14f, align = TextAlign.Center), color = Color(FOOT),
                )
            }
            item(key = "end") { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
    }
}

/**
 * The chips that narrow a kind of place or a brand, as the board's filters: 32 px pills 6 px apart, 11 px in, 12 / 800;
 * on: ink with white words, off: white with ink.
 */
@Composable
private fun RefinementChips(results: SearchResults, selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in results.refinements) {
            val on = r.chip.value in selected
            Box(
                Modifier
                    .height(32.dp)
                    .boardBox(if (on) INK else 0xFFFFFFFF, round(16f))
                    .toggleable(on, role = Role.Checkbox) { onToggle(r.chip.value) }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) { BoardLine(r.chip.label, boardText(12f, 800, 15.5f), if (on) 0xFFFFFFFF else INK) }
        }
    }
}

/** The answer as the board's white card (corners 22, 2 px by 14 px inside) of 64 px rows under hairlines. */
@Composable
private fun ResultsCard(
    results: SearchResults,
    here: LatLon,
    lang: String,
    now: LocalDateTime,
    nav: Navigator,
    openPlace: (Place) -> Unit,
    onQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = results.items.filter { it !is SearchItem.Recent }
    Column(modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(22f)).padding(horizontal = 14.dp, vertical = 2.dp)) {
        for (item in rows) SearchItemRow(item, here, lang, now, nav, openPlace, onQuery)
    }
}

/** The places left after the chips narrowed the answer. */
@Composable
private fun RefinedCard(result: QueryResult, here: LatLon, lang: String, now: LocalDateTime, openPlace: (Place) -> Unit, modifier: Modifier = Modifier) {
    if (result.hits.isEmpty()) {
        StateMessage("ui-filter", stringResource(R.string.search_none_left), modifier)
        return
    }
    Column(modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(22f)).padding(horizontal = 14.dp, vertical = 2.dp)) {
        for (h in result.hits) {
            val line = remember(h, now) { PlaceLine.of(h.place, here, lang, now, h.open, h.record.name(lang), h.distance) }
            PlaceResultRow(line, now, lang, onClick = { openPlace(h.place) })
        }
    }
}

/** One line of the answer, drawn for what it is: a place, a kind, a brand, goods, a query, a point or an online find. */
@Composable
private fun SearchItemRow(
    item: SearchItem,
    here: LatLon,
    lang: String,
    now: LocalDateTime,
    nav: Navigator,
    openPlace: (Place) -> Unit,
    onQuery: () -> Unit,
) {
    when (item) {
        is SearchItem.PlaceResult -> {
            val line = remember(item, now) { PlaceLine.of(item.hit.place, here, lang, now, item.hit.open, item.title, item.hit.distance) }
            PlaceResultRow(line, now, lang, subtitle = item.subtitle, parts = item.parts, onClick = { openPlace(item.hit.place) })
        }
        is SearchItem.Element -> {
            val line = remember(item, now) { PlaceLine.of(item.hit.place, here, lang, now, item.hit.open, distanceM = item.hit.distance) }
            PlaceResultRow(line, now, lang, subtitle = item.hit.place.id?.toString(), onClick = { openPlace(item.hit.place) })
        }
        is SearchItem.Online -> {
            val line = remember(item, now) { PlaceLine.of(item.place, here, lang, now, distanceM = item.distance) }
            PlaceResultRow(
                line, now, lang,
                subtitle = item.place.extra["label"] ?: addressOf(item.place),
                badge = "ui-cloud",
                onClick = { openPlace(item.place) },
            )
        }
        is SearchItem.Kind -> {
            // Kinds are nouns: lower case inside a sentence, except in German.
            val label = if (lang == "de") item.label else item.label.lowercase(Locale.getDefault())
            ResultRow(
                title = stringResource(R.string.search_every, label),
                titleLines = 2,
                line = AnnotatedString(stringResource(R.string.search_every_sub, item.count)),
                symbol = "ui.query-open",
                tile = Color(ASK),
                trailingSymbol = "ui-arrow-right",
                trailingColour = Color(ASK),
                onClick = {
                    onQuery()
                    val cat = (item.query as? app.atlas.search.Query.Category)?.id
                    nav.go(if (cat != null) Dest.Category(cat) else Dest.Query(item.label))
                },
            )
        }
        is SearchItem.BrandResult -> {
            val brand = item.brand
            ResultRow(
                title = stringResource(R.string.search_brand_all, item.count, brandName(brand)),
                line = AnnotatedString(stringResource(R.string.search_brand_sub)),
                leading = { BrandLogo(brand, 40.dp) },
                trailingSymbol = "ui-arrow-right",
                trailingColour = Color(ASK),
                onClick = { onQuery(); nav.go(Dest.Brand(brand.id)) },
            )
        }
        is SearchItem.GoodsResult -> {
            val kinds = item.sellers.filter { it.count > 0 }
            ResultRow(
                title = item.label,
                line = AnnotatedString(listOf(pluralStringResource(R.plurals.search_goods_kinds, kinds.size, kinds.size), kinds.take(3).joinToString(", ") { it.label }).filter { it.isNotEmpty() }.joinToString(" · ")),
                symbol = item.symbol,
                tile = Color(0xFFB8461A),
                trailingSymbol = "ui-arrow-right",
                trailingColour = Color(ASK),
                onClick = { onQuery(); nav.go(Dest.Query(item.label)) },
            )
        }
        is SearchItem.Structured -> {
            val n = item.results.firstOrNull()?.total ?: 0
            val plan = item.parsed.isPlan
            ResultRow(
                title = item.parsed.chips.filter { it.row == 0 && it.step == 0 }.joinToString(" ") { it.label }.ifEmpty { item.parsed.text },
                line = AnnotatedString(
                    if (plan) pluralStringResource(R.plurals.search_plan_steps, item.parsed.steps.size, item.parsed.steps.size)
                    else pluralStringResource(R.plurals.search_query_places, n, n),
                ),
                symbol = if (plan) "fact.new" else "ui.query-open",
                tile = Color(if (plan) ASK else NEAR),
                badge = if (item.online) "ui-cloud" else null,
                trailingSymbol = "ui-arrow-right",
                trailingColour = Color(if (plan) ASK else NEAR),
                onClick = { onQuery(); nav.go(if (plan) Dest.Ask(item.parsed.text) else Dest.Query(item.parsed.text)) },
            )
        }
        is SearchItem.Coordinate -> {
            ResultRow(
                title = CoordinateFormat.decimal(item.location),
                line = AnnotatedString(item.plusCode + " · " + shortDistance(app.atlas.geo.Geo.distance(here, item.location))),
                symbol = "ui-map-pin",
                tile = Color(0xFF6B7282),
                trailingSymbol = "ui-arrow-right",
                trailingColour = Color(INK),
                onClick = { onQuery(); nav.go(Dest.WhatsHere(item.location.lat, item.location.lon)) },
            )
        }
        is SearchItem.Recent -> Unit
    }
}

/** A place in a result list: tile, name, the status words in colour then its kind and distance, and walk minutes. */
@Composable
internal fun PlaceResultRow(
    line: PlaceLine,
    now: LocalDateTime,
    lang: String,
    subtitle: String? = null,
    parts: Int = 1,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val s = line.summary
    val stepFree = if (line.stepFree) stringResource(R.string.search_step_free) else null
    val distance = s.distanceM?.let { shortDistance(it) }
    val more = if (parts > 1) pluralStringResource(R.plurals.search_parts, parts, parts) else null
    ResultRow(
        title = s.name,
        line = statusLine(line, now, stepFree, subtitle ?: s.kind, more, distance.takeIf { line.trailing != distance }),
        symbol = s.symbol,
        tile = Color(s.colour),
        trailing = line.trailing,
        trailingColour = if (line.trailing == distance) Color(ROW_SOFT) else toneColour(line.tone),
        badge = badge,
        onClick = onClick,
    )
}

/** Nothing matched: say so, and when no region is on the phone, offer one. */
@Composable
private fun NothingFound(text: String, hasRegion: Boolean, modifier: Modifier, onDownload: () -> Unit) {
    if (hasRegion) {
        StateMessage("ui-search", stringResource(R.string.search_nothing, text.trim()), modifier, text = stringResource(R.string.search_nothing_text))
    } else {
        StateMessage(
            "ui-cloud-off", stringResource(R.string.search_no_regions), modifier, text = stringResource(R.string.search_no_regions_text), tint = Color(NEAR),
            action = { SmallChip(stringResource(R.string.search_download), symbol = "ui-download", symbolTint = NEAR, onClick = onDownload) },
        )
    }
}

/** The board's pale blue card: 70 px, corners 20, the no-signal symbol and two lines, 14 px in and 12 apart. */
@Composable
private fun WorksOffline(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 70.dp).boardBox(0xFFE6F0FF, round(20f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardSymbol("ui.no-signal.2", 24f, NEAR)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardLine(stringResource(R.string.search_offline_title), boardText(14f, 800, 18f), INK)
            BoardLine(stringResource(R.string.search_offline_text), ROW_LINE, 0xFF3A4E78, lines = 2)
        }
    }
}

/**
 * A board pill of the recents: 30 px, 11 px in, white with a 1 px #E0E3E8 ring, a 14 px symbol 6 px before 12 / 700
 * words (Geist Mono for coordinates and plus codes).
 */
@Composable
private fun SmallChip(
    text: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolTint: Long = 0xFF9AA0AC,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(30.dp)
            .boardBox(0xFFFFFFFF, round(15f), listOf(spreadRing(1f, 0xFFE0E3E8)))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) BoardSymbol(symbol, 14f, symbolTint)
        BoardLine(text, boardText(12f, 700, 15.5f, family = if (mono) GEIST_MONO else GEIST), INK)
    }
}

/** Recent searches as the board's clock pills, 6 px apart; coordinates and plus codes in Geist Mono. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Recents(
    recents: List<Recent>,
    onText: (String) -> Unit,
    onPlace: (Place) -> Unit,
    onPoint: (LatLon) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            BoardLine(stringResource(R.string.search_recent).uppercase(Locale.getDefault()), KICKER_STYLE, KICKER, Modifier.weight(1f))
            if (recents.isNotEmpty()) {
                BoardLine(
                    stringResource(R.string.search_recent_clear).uppercase(Locale.getDefault()), KICKER_STYLE, KICKER,
                    Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClear).padding(horizontal = 4.dp),
                )
            }
        }
        if (recents.isEmpty()) {
            BoardLine(stringResource(R.string.search_recent_none), ROW_LINE, FOOT, Modifier.padding(start = 2.dp), lines = 3)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in recents) {
                val point = remember(r.text) { CoordinateParser.parse(r.text) }
                val label = r.text.ifEmpty { r.place?.name() ?: "" }
                SmallChip(label, symbol = "ui.clock.3", mono = point != null) {
                    when {
                        r.place != null -> onPlace(r.place)
                        point != null -> onPoint(point)
                        else -> onText(r.text)
                    }
                }
            }
        }
    }
}

/** "My lists": the saved places, one tap away from the empty search, as a white board row with the notebooks' spines. */
@Composable
private fun ListsRow(nav: Navigator, modifier: Modifier = Modifier) {
    val services = LocalServices.current
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val saved = lists.sumOf { it.places.size }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).boardBox(0xFFFFFFFF, round(22f))
            .clickable(role = Role.Button) { nav.go(Dest.Lists()) }.padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(40.dp), horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            for (l in lists.take(3)) Box(Modifier.size(width = 20.dp, height = 28.dp).boardBox(l.colour.toLong() and 0xFFFFFFFFL, CssCorners(2f, 5f, 5f, 2f)))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardLine(stringResource(R.string.lists_title), ROW_TITLE, INK)
            BoardLine(
                pluralStringResource(R.plurals.search_saved_places, saved, saved) + " · " + pluralStringResource(R.plurals.search_lists_n, lists.size, lists.size),
                ROW_LINE, ROW_SOFT,
            )
        }
        BoardLine("→", ROW_VALUE, INK)
    }
}

/** Other ways to find: every chain around, a wheel of ideas, and questions mappers would like answered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreWays(nav: Navigator, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallChip(stringResource(R.string.brands_title), symbol = "cat.shops", symbolTint = 0xFFB8461A, onClick = { nav.go(Dest.Brands()) })
        SmallChip(stringResource(R.string.where_title), symbol = "ui-compass", symbolTint = ASK, onClick = { nav.go(Dest.Where) })
        SmallChip(stringResource(R.string.quests_title), symbol = "ui-help", symbolTint = 0xFF8A5A00, onClick = { nav.go(Dest.Quests()) })
    }
}

/** "Or just say it": the board's three white rows (40 px, corners 14, 6 apart) that open Ask, A near B and the needs. */
@Composable
private fun JustSayIt(nav: Navigator, modifier: Modifier = Modifier) {
    val ask = stringResource(R.string.search_say_ask)
    val near = stringResource(R.string.search_say_near)
    val needs = stringResource(R.string.search_say_needs)
    Column(modifier) {
        BoardLine(stringResource(R.string.search_say).uppercase(Locale.getDefault()), KICKER_STYLE, KICKER, Modifier.padding(start = 2.dp, bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SayRow(ask, stringResource(R.string.search_to_ask), ASK) { nav.go(Dest.Ask(ask)) }
            SayRow(near, stringResource(R.string.search_to_near), NEAR) { nav.go(Dest.Query(near)) }
            SayRow(needs, stringResource(R.string.search_to_needs), NEEDS) { nav.go(Dest.Needs("toilet")) }
        }
    }
}

/** One sentence to say: the sparkle in [colour], the words in quotes (13 / 600), and where it leads (11 / 800). */
@Composable
private fun SayRow(sentence: String, target: String, colour: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .boardBox(0xFFFFFFFF, round(14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardSymbol("fact.new", 15f, colour)
        BoardLine("“$sentence”", boardText(13f, 600, 17f), INK, Modifier.weight(1f))
        BoardLine("$target →", boardText(11f, 800, 14f), colour)
    }
}
