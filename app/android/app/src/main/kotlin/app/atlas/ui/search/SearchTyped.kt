package app.atlas.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.CoordinateFormat
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.ui.go.GoJson
import app.atlas.nav.Navigator
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.Travel
import app.atlas.search.TravelMode
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberLocaleOf
import app.atlas.ui.kit.shortDistance
import java.time.LocalDateTime
import java.util.Locale

/** Typed words: the live results (places, kinds, brands, queries) in the Index's rows, a kind among them opening here. */
internal fun LazyListScope.typedPane(
    text: SearchState,
    typed: SearchViewModel,
    field: String,
    here: LatLon,
    mode: TravelMode,
    lang: String,
    now: LocalDateTime,
    nav: Navigator,
    onPlace: (Place) -> Unit,
    onOpen: (SearchItem) -> Unit,
) {
    val results = text.results
    if (results != null && results.refinements.isNotEmpty()) item(key = "chips") { Refinements(results, text.selected, typed::toggle) }
    val open: (Place) -> Unit = { p -> typed.remember(field, p); onPlace(p) }
    val onQuery = { typed.remember(field, null) }
    val refined = text.refined
    when {
        text.error != null -> item(key = "error") {
            Panel { StateMessage("ui-alert-triangle", stringResource(R.string.search_failed), text = text.error, tint = Color(0xFFC8322A)) }
        }
        results == null -> item(key = "looking") { Looking() }
        refined != null && refined.hits.isEmpty() -> item(key = "none-left") { Panel { StateMessage("ui-filter", stringResource(R.string.search_none_left)) } }
        // what the words found, the rows of one panel as the "NEAREST" rows of What's here are
        refined != null -> itemsIndexed(refined.hits) { i, h ->
            TypedPlace(h.place, h.record.name(lang), null, 1, h.distance, h.open, here, mode, lang, now, PanelSeat.of(i, refined.hits.size)) { open(h.place) }
        }
        results.items.isEmpty() -> item(key = "none") {
            Panel { StateMessage("ui-search", stringResource(R.string.search_nothing, field.trim()), text = stringResource(R.string.search_nothing_text)) }
        }
        else -> {
            // a chain the words name outright leads as its page's band, a tap from its page (as the search key opens it)
            val named = results.items.firstOrNull { it is SearchItem.BrandResult && it.named } as SearchItem.BrandResult?
            named?.let { b ->
                item(key = "named") { NamedBrand(b, lang, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) { onQuery(); onOpen(b) } }
            }
            // what the words asked rather than listed leads: how many, their hours, a fact; a tap opens it over its places
            results.items.firstOrNull { it is SearchItem.Count || it is SearchItem.Hours || it is SearchItem.Fact }?.let { a ->
                item(key = "answer") {
                    val m = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    when (a) {
                        is SearchItem.Count -> CountCard(a.answer, m, a.area) { onQuery(); onOpen(a) }
                        is SearchItem.Hours -> HoursCard(a.answer, now, m) { onQuery(); onOpen(a) }
                        is SearchItem.Fact -> FactCard(a, lang, m, open)
                        else -> Unit
                    }
                }
            }
            // the lines of the answer; what is a card of its own above them, or the field's own (a recent), is no row
            val rows = results.items.filter { it !is SearchItem.Recent && it !== named && it !is SearchItem.Count && it !is SearchItem.Hours && it !is SearchItem.Fact }
            itemsIndexed(rows) { i, item -> TypedRow(item, here, mode, lang, now, nav, open, onQuery, onOpen, PanelSeat.of(i, rows.size)) }
        }
    }
}

/** The chips that narrow typed words to a kind's own ("Open now", "Terrace"). */
@Composable
private fun Refinements(results: SearchResults, selected: Set<String>, onToggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (r in results.refinements) IndexChip(r.chip.label, { onToggle(r.chip.value) }, selected = r.chip.value in selected)
    }
}

/** One line of the answer, drawn for what it is: a place, a kind, a brand, goods, a query, a point or an online find. */
@Composable
private fun TypedRow(
    item: SearchItem,
    here: LatLon,
    mode: TravelMode,
    lang: String,
    now: LocalDateTime,
    nav: Navigator,
    open: (Place) -> Unit,
    onQuery: () -> Unit,
    onOpen: (SearchItem) -> Unit,
    seat: PanelSeat,
) {
    when (item) {
        is SearchItem.PlaceResult -> TypedPlace(item.hit.place, item.title, item.subtitle, item.parts, item.hit.distance, item.hit.open, here, mode, lang, now, seat) { open(item.hit.place) }
        is SearchItem.Element -> TypedPlace(item.hit.place, null, item.hit.place.id?.toString(), 1, item.hit.distance, item.hit.open, here, mode, lang, now, seat) { open(item.hit.place) }
        is SearchItem.Online -> {
            // an online find comes with no status: its hours read where it is, on its own clock, as its pin fades by
            // them (the app maintainer, 18 November 2026: the row said nothing; batch 4 B18: on the phone's clock)
            val where = rememberLocaleOf(item.place)
            val zone = appClock().zone
            val local = remember(now, where, zone) { where.fromPhone(now, zone) }
            val status = remember(item.place, local, where) { OpeningHours.parseOrNull(item.place.tags["opening_hours"])?.status(local, where.hours(item.place.location)) }
            TypedPlace(item.place, null, item.place.extra["label"] ?: addressOf(item.place, where.country), 1, item.distance, status, here, mode, lang, local, seat, online = true) { open(item.place) }
        }
        is SearchItem.Kind -> {
            // kinds are nouns: lower case inside a sentence, except in German
            val label = if (lang == "de") item.label else item.label.lowercase(Locale.getDefault())
            // the kind in the tree, so that it opens here as the sentence's first word
            val node = remember(item.query) { kindNode(item.query) }
            TypedWay(stringResource(R.string.search_every, label), stringResource(R.string.search_every_sub, item.count), { KindTile(item.symbol, node?.colour ?: ASK, tone = null) }, seat, lines = 2) {
                onQuery()
                onOpen(item)
            }
        }
        is SearchItem.BrandResult -> TypedWay(
            stringResource(R.string.search_brand_all, item.count, brandName(item.brand)), stringResource(R.string.search_brand_sub), { BrandLogo(item.brand, ROW_TILE) }, seat,
        ) { onQuery(); onOpen(item) }
        is SearchItem.GoodsResult -> {
            val kinds = item.sellers.filter { it.count > 0 }
            val line = listOf(pluralStringResource(R.plurals.search_goods_kinds, kinds.size, kinds.size), kinds.take(3).joinToString(", ") { it.label }).filter { it.isNotEmpty() }.joinToString(" · ")
            TypedWay(item.label, line, { KindTile(item.symbol, GOODS, tone = null) }, seat) { onQuery(); onOpen(item) }
        }
        is SearchItem.Structured -> {
            val n = item.results.firstOrNull()?.total ?: 0
            val plan = item.parsed.isPlan
            TypedWay(
                item.parsed.chips.filter { it.row == 0 && it.step == 0 }.joinToString(" ") { it.label }.ifEmpty { item.parsed.text },
                if (plan) pluralStringResource(R.plurals.search_plan_steps, item.parsed.steps.size, item.parsed.steps.size) else pluralStringResource(R.plurals.search_query_places, n, n),
                { KindTile(if (plan) "fact.new" else "ui.query-open", if (plan) ASK else NEAR, tone = null) }, seat, badge = "ui-cloud".takeIf { item.online },
            ) { onQuery(); onOpen(item) }
        }
        is SearchItem.Coordinate -> TypedWay(
            CoordinateFormat.decimal(item.location), item.plusCode + " · " + shortDistance(Geo.distance(here, item.location)), { KindTile("ui-map-pin", POINT, tone = null) }, seat,
        ) { onQuery(); nav.go(Dest.WhatsHere(item.location.lat, item.location.lon, go = true)) }
        is SearchItem.Recent -> Unit
        // the answers to "how many", "opening times" and "how tall is" are cards of their own, above the lines
        is SearchItem.Count, is SearchItem.Hours, is SearchItem.Fact -> Unit
        is SearchItem.Route -> {
            val to = item.to
            val from = item.from
            val start = from?.let { stringResource(R.string.search_route_from, it.name ?: it.text) } ?: stringResource(R.string.search_route_here)
            val past = item.via.takeIf { it.isNotEmpty() }?.let { v -> stringResource(R.string.search_route_via, v.joinToString(", ") { it.name ?: it.text }) }
            TypedWay(
                stringResource(R.string.search_route_to, to.name ?: to.text), listOfNotNull(start, past).joinToString(" · "),
                { KindTile("ui-route", NEAR, tone = null) }, seat,
            ) {
                onQuery()
                nav.go(routeTo(item))
            }
        }
    }
}

/** The route planner for a way the words asked for: to its end, from its start (here when none), past its stops, as asked. */
internal fun routeTo(item: SearchItem.Route): Dest.Route {
    val to = item.to
    val from = item.from
    return Dest.Route(
        to.location.lat, to.location.lon, to.name ?: to.text, from?.location?.lat, from?.location?.lon, from?.let { it.name ?: it.text },
        mode = item.ask.mode, via = viaOf(item.via.map { it.location to (it.name ?: it.text) }), wish = item.ask.prefs?.let { GoJson.wishes(it) },
    )
}

/** Stops on a way as the route planner takes them ([Dest.Route.via]), in order; null for none. */
internal fun viaOf(stops: List<Pair<LatLon, String>>): String? =
    stops.joinToString("|") { (at, name) -> String.format(Locale.ROOT, "%.6f,%.6f,%s", at.lat, at.lon, name.replace('|', ' ')) }.ifEmpty { null }

/** A place among typed results, a row of their panel: its kind's tile with the open light, its name, open or not, its kind and how far. */
@Composable
private fun TypedPlace(
    place: Place,
    name: String?,
    subtitle: String?,
    parts: Int,
    distance: Double?,
    open: OpenStatus?,
    here: LatLon,
    mode: TravelMode,
    lang: String,
    now: LocalDateTime,
    seat: PanelSeat,
    online: Boolean = false,
    onClick: () -> Unit,
) {
    val c = indexColors()
    // its chain the one of its own country, not the phone's (the app maintainer, 18 November 2026, batch 4 B18)
    val country = rememberLocaleOf(place).country
    val line = remember(place, now, open, distance, country) { PlaceLine.of(place, here, lang, now, open, country, name, distance) }
    val metres = line.summary.distanceM
    val minutes = metres?.let { Travel.minutes(it, mode) }
    val arrival = if (minutes != null) arrivalWords(open, minutes, now) else null
    val words = arrival?.first ?: openWords(line)
    val tone = arrival?.second ?: if (words == null) Tone.UNKNOWN else line.tone
    val more = if (parts > 1) pluralStringResource(R.plurals.search_parts, parts, parts) else null
    val stepFree = if (line.stepFree) stringResource(R.string.search_step_free) else null
    IndexLine(
        line.summary.name, statusOf(words, tone, null, listOfNotNull(stepFree, subtitle ?: line.summary.kind, more, metres?.let(::shortDistance)?.takeIf { minutes != null && minutes < 100 })), onClick, Modifier.testTag(PLACE_TAG),
        reach = { Reach(minutes, metres) },
        lead = { KindTile(line.summary.symbol, line.summary.colour, tone) },
        trailing = if (online) ({ Symbol("ui-cloud", tint = c.faint, size = 14.dp) }) else null,
        seat = seat,
    )
}

/** A way on from typed words (every one of a kind, a brand's branches, a query, a point): its key and an arrow. */
@Composable
private fun TypedWay(title: String, line: String?, lead: @Composable () -> Unit, seat: PanelSeat, lines: Int = 1, badge: String? = null, onClick: () -> Unit) {
    val c = indexColors()
    IndexLine(
        title, line?.let { AnnotatedString(it) }, onClick, lead = lead, titleLines = lines, seat = seat,
        trailing = {
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                badge?.let { Symbol(it, tint = c.faint, size = 14.dp) }
                Symbol("ui-arrow-right", tint = c.faint, size = 16.dp)
            }
        },
    )
}
