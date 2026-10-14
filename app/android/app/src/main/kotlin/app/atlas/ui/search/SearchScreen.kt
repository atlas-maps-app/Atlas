package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.search.QueryResult
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.BackButton
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.SymbolTile
import app.atlas.ui.kit.ToggleChip
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.shortDistance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

/**
 * Search, as on the Search board: the field with the mic and live results as you type (offline, typos forgiven), the
 * chips that narrow a kind of place, each result with its status in colour and walk minutes, "Every pharmacy on the
 * map", the "Works offline" card, recent searches (coordinates and plus codes welcome) and sentences to just say.
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
    val c = Atlas.colors
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
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

    Column(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton({ nav.back() })
            Row(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(c.card)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.ink else c.line, CircleShape)
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Symbol("ui-search", tint = c.ink, size = 22.dp)
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; vm.onText(it) },
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = searchLabel },
                    singleLine = true,
                    textStyle = Atlas.type.headline.copy(color = c.ink, fontSize = 19.sp),
                    cursorBrush = SolidColor(c.go),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.remember(field, null) }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (field.isEmpty()) {
                                val hint = category?.let { stringResource(R.string.search_hint_in, it.name(lang)) } ?: stringResource(R.string.search_hint)
                                Text(hint, style = Atlas.type.body.copy(fontSize = 18.sp), color = c.inkFaint, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
                if (field.isNotEmpty()) {
                    SymbolButton("ui-x", stringResource(R.string.search_clear), { field = ""; vm.onText("") }, size = 36.dp, elevation = 0.dp, background = c.cardMuted, tint = c.inkSoft)
                }
                SymbolButton("ui-mic", stringResource(R.string.kit_mic), mic, size = 44.dp, elevation = 0.dp, background = c.card, tint = c.ask)
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val results = state.results
            if (results != null && results.refinements.isNotEmpty()) {
                item(key = "chips") { RefinementChips(results, state.selected, vm::toggle) }
            }
            when {
                state.text.isBlank() -> Unit
                state.error != null -> item(key = "error") {
                    StateMessage("ui-alert-triangle", stringResource(R.string.search_failed), text = state.error, tint = c.alert)
                }
                results == null -> item(key = "looking") {
                    Text(stringResource(R.string.kit_loading), Modifier.padding(start = 4.dp), style = Atlas.type.caption, color = c.inkSoft)
                }
                state.refined != null -> item(key = "refined") { RefinedCard(state.refined!!, here, lang, now, openPlace) }
                results.items.isEmpty() -> item(key = "none") {
                    NothingFound(state.text, coversHere || services.placeStores.size > 0, onDownload = { nav.go(Dest.RegionPicker(here.lat, here.lon)) })
                }
                else -> item(key = "results") {
                    ResultsCard(results, here, lang, now, nav, openPlace, onQuery = { vm.remember(field, null) })
                }
            }
            if (coversHere && state.text.isNotBlank()) item(key = "offline") { WorksOffline() }
            item(key = "recent") {
                Recents(
                    recents = recents.filter { state.text.isBlank() || it.text.contains(state.text.trim(), ignoreCase = true) }.take(8),
                    onText = { t -> field = t; vm.onText(t) },
                    onPlace = openPlace,
                    onPoint = { p -> nav.go(Dest.WhatsHere(p.lat, p.lon)) },
                    onClear = { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.clearRecents() } } },
                )
            }
            if (state.text.isBlank()) {
                item(key = "lists") { ListsRow(nav) }
                item(key = "more") { MoreWays(nav) }
            }
            item(key = "say") { JustSayIt(nav) }
            if (state.text.isBlank()) {
                item(key = "kinds") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Kicker(stringResource(R.string.search_categories))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (cat in Categories.groups()) {
                                Chip(cat.name(lang), symbol = cat.symbol, symbolTint = Color(cat.colour), onClick = { nav.go(Dest.Category(cat.id)) })
                            }
                        }
                    }
                }
            }
            item(key = "footer") { HonestyFooter(stringResource(R.string.search_footer)) }
            item(key = "end") { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
    }
}

/** The chips that narrow a kind of place or a brand: "Open now", "Open tonight", "Step-free"… with how many each leaves. */
@Composable
private fun RefinementChips(results: SearchResults, selected: Set<String>, onToggle: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in results.refinements) {
            val on = r.chip.value in selected
            ToggleChip(r.chip.label, on, { onToggle(r.chip.value) })
        }
    }
}

/** The answer as one white card of rows, separated by hairlines. */
@Composable
private fun ResultsCard(
    results: SearchResults,
    here: LatLon,
    lang: String,
    now: LocalDateTime,
    nav: Navigator,
    openPlace: (Place) -> Unit,
    onQuery: () -> Unit,
) {
    val rows = results.items.filter { it !is SearchItem.Recent }
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
        rows.forEachIndexed { i, item ->
            SearchItemRow(item, here, lang, now, nav, openPlace, onQuery)
            if (i < rows.lastIndex) Hairline()
        }
    }
}

/** The places left after the chips narrowed the answer. */
@Composable
private fun RefinedCard(result: QueryResult, here: LatLon, lang: String, now: LocalDateTime, openPlace: (Place) -> Unit) {
    if (result.hits.isEmpty()) {
        StateMessage("ui-filter", stringResource(R.string.search_none_left))
        return
    }
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
        result.hits.forEachIndexed { i, h ->
            val line = remember(h, now) { PlaceLine.of(h.place, here, lang, now, h.open, h.record.name(lang), h.distance) }
            PlaceResultRow(line, now, lang, onClick = { openPlace(h.place) })
            if (i < result.hits.lastIndex) Hairline()
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
    val c = Atlas.colors
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
                symbol = "ui-near",
                tile = c.ask,
                trailingSymbol = "ui-arrow-right",
                trailingColour = c.ask,
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
                leading = { BrandLogo(brand, 48.dp) },
                trailingSymbol = "ui-arrow-right",
                trailingColour = c.ask,
                onClick = { onQuery(); nav.go(Dest.Brand(brand.id)) },
            )
        }
        is SearchItem.GoodsResult -> {
            val kinds = item.sellers.filter { it.count > 0 }
            ResultRow(
                title = item.label,
                line = AnnotatedString(listOf(pluralStringResource(R.plurals.search_goods_kinds, kinds.size, kinds.size), kinds.take(3).joinToString(", ") { it.label }).filter { it.isNotEmpty() }.joinToString(" · ")),
                symbol = item.symbol,
                tile = c.eat,
                trailingSymbol = "ui-arrow-right",
                trailingColour = c.ask,
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
                symbol = if (plan) "ui-sparkles" else "ui-near",
                tile = if (plan) c.ask else c.go,
                badge = if (item.online) "ui-cloud" else null,
                trailingSymbol = "ui-arrow-right",
                trailingColour = if (plan) c.ask else c.go,
                onClick = { onQuery(); nav.go(if (plan) Dest.Ask(item.parsed.text) else Dest.Query(item.parsed.text)) },
            )
        }
        is SearchItem.Coordinate -> {
            ResultRow(
                title = CoordinateFormat.decimal(item.location),
                line = AnnotatedString(item.plusCode + " · " + shortDistance(app.atlas.geo.Geo.distance(here, item.location))),
                symbol = "ui-map-pin",
                tile = c.pill,
                trailingSymbol = "ui-arrow-right",
                trailingColour = c.ink,
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
        trailingColour = if (line.trailing == distance) Atlas.colors.inkSoft else toneColour(line.tone),
        badge = badge,
        onClick = onClick,
    )
}

/** Nothing matched: say so, and when no region is on the phone, offer one. */
@Composable
private fun NothingFound(text: String, hasRegion: Boolean, onDownload: () -> Unit) {
    val c = Atlas.colors
    if (hasRegion) {
        StateMessage("ui-search", stringResource(R.string.search_nothing, text.trim()), text = stringResource(R.string.search_nothing_text))
    } else {
        StateMessage(
            "ui-cloud-off", stringResource(R.string.search_no_regions), text = stringResource(R.string.search_no_regions_text), tint = c.go,
            action = { Chip(stringResource(R.string.search_download), symbol = "ui-download", onClick = onDownload) },
        )
    }
}

/** The pale blue card that says the search needs no signal here. */
@Composable
private fun WorksOffline() {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.goSoft).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui-wifi-off", tint = c.go, size = 26.dp)
        Column {
            Text(stringResource(R.string.search_offline_title), style = Atlas.type.bodyStrong, color = c.ink)
            Text(stringResource(R.string.search_offline_text), style = Atlas.type.caption, color = c.inkSoft)
        }
    }
}

/** Recent searches as chips with a clock; coordinates and plus codes in monospace. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Recents(
    recents: List<Recent>,
    onText: (String) -> Unit,
    onPlace: (Place) -> Unit,
    onPoint: (LatLon) -> Unit,
    onClear: () -> Unit,
) {
    val c = Atlas.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(stringResource(R.string.search_recent), Modifier.weight(1f))
            if (recents.isNotEmpty()) {
                Text(
                    stringResource(R.string.search_recent_clear),
                    Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClear).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = Atlas.type.caption, color = c.inkSoft,
                )
            }
        }
        if (recents.isEmpty()) {
            Text(stringResource(R.string.search_recent_none), style = Atlas.type.caption, color = c.inkFaint)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in recents) {
                val point = remember(r.text) { CoordinateParser.parse(r.text) }
                val label = r.text.ifEmpty { r.place?.name() ?: "" }
                Row(
                    Modifier
                        .heightIn(min = 38.dp)
                        .clip(CircleShape)
                        .background(c.card)
                        .border(1.dp, c.line, CircleShape)
                        .clickable(role = Role.Button) {
                            when {
                                r.place != null -> onPlace(r.place)
                                point != null -> onPoint(point)
                                else -> onText(r.text)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui-clock", tint = c.inkSoft, size = 16.dp)
                    Text(
                        label,
                        style = if (point != null) Atlas.type.mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else Atlas.type.label,
                        color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "My lists": the saved places, one tap away from the empty search. */
@Composable
private fun ListsRow(nav: Navigator) {
    val services = LocalServices.current
    val c = Atlas.colors
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val saved = lists.sumOf { it.places.size }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(c.card)
            .clickable(role = Role.Button) { nav.go(Dest.Lists()) }.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            for (l in lists.take(3)) Box(Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp, topStart = 2.dp, bottomStart = 2.dp)).background(Color(l.colour)))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lists_title), style = Atlas.type.bodyStrong, color = c.ink)
            Text(
                pluralStringResource(R.plurals.search_saved_places, saved, saved) + " · " + pluralStringResource(R.plurals.search_lists_n, lists.size, lists.size),
                style = Atlas.type.caption, color = c.inkSoft,
            )
        }
        Symbol("ui-arrow-right", tint = c.ink, size = 20.dp)
    }
}

/** Other ways to find: every chain around, a wheel of ideas, and questions mappers would like answered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreWays(nav: Navigator) {
    val c = Atlas.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.brands_title), symbol = "ui-store", symbolTint = c.eat, onClick = { nav.go(Dest.Brands()) })
        Chip(stringResource(R.string.where_title), symbol = "ui-compass", symbolTint = c.ask, onClick = { nav.go(Dest.Where) })
        Chip(stringResource(R.string.quests_title), symbol = "ui-help", symbolTint = c.caution, onClick = { nav.go(Dest.Quests()) })
    }
}

/** "Or just say it": sentences that open Ask, A near B and the needs. */
@Composable
private fun JustSayIt(nav: Navigator) {
    val c = Atlas.colors
    val ask = stringResource(R.string.search_say_ask)
    val near = stringResource(R.string.search_say_near)
    val needs = stringResource(R.string.search_say_needs)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(stringResource(R.string.search_say))
        SayRow(ask, stringResource(R.string.search_to_ask), c.ask) { nav.go(Dest.Ask(ask)) }
        SayRow(near, stringResource(R.string.search_to_near), c.go) { nav.go(Dest.Query(near)) }
        SayRow(needs, stringResource(R.string.search_to_needs), c.ask) { nav.go(Dest.Needs("toilet")) }
    }
}

@Composable
private fun SayRow(sentence: String, target: String, colour: Color, onClick: () -> Unit) {
    val c = Atlas.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui-sparkles", tint = c.ask, size = 18.dp)
        Text("“$sentence”", Modifier.weight(1f), style = Atlas.type.bodyStrong.copy(fontStyle = FontStyle.Italic), color = c.ink, maxLines = 2)
        Text("$target →", style = Atlas.type.label, color = colour)
    }
}
