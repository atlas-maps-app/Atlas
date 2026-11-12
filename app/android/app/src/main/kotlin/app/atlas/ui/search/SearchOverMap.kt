package app.atlas.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.Follow
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.RouteLine
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.model.Place
import app.atlas.places.CategoryNode
import app.atlas.places.OpeningHours
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brand
import app.atlas.providers.Forecast
import app.atlas.ui.home.forecastOrKept
import app.atlas.ui.kit.CurrentWeather
import app.atlas.search.ResultHit
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.Travel
import app.atlas.search.TravelMode
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.BrandSigns
import app.atlas.ui.kit.markOf
import app.atlas.ui.kit.signOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

/**
 * The one search on a page of its own (Search, a kind's "every one on the map", a brand's page): over the map on
 * screen as the user left it, the places as pins, the sheet below; typing pulls it up. [query] is typed in; [kind]
 * opens inside a kind (an Atlas category or group id, or a node of the tree: "preset:amenity/cafe"); [brand] on that
 * chain's page.
 */
@Composable
internal fun SearchOverMap(nav: Navigator, query: String = "", kind: String? = null, brand: Brand? = null) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    // the weather where the searcher is, as the home's search weighs it: the kept forecast at once, a fresh one online
    var forecast by remember { mutableStateOf<Forecast?>(null) }
    val find = viewModel(key = "find") {
        SearchSheetModel(services, here, lang, weather = { CurrentWeather.of(forecast, services.clock.instant()) }, forecast = { forecast })
    }
    LaunchedEffect(here) {
        val online = !services.config.offline && services.connectivity.online.value
        forecast = withContext(Dispatchers.IO) { runCatching { forecastOrKept(services.weather, here, online) }.getOrNull() } ?: forecast
        find.skyChanged()
    }
    // typed words inside an Atlas category look among its places
    val typed = viewModel(key = "search") { SearchViewModel(services, query, kind?.takeIf { app.atlas.places.Categories.byId(it) != null }, { here }, lang) }
    val state by find.state.collectAsStateWithLifecycle()
    val text by typed.state.collectAsStateWithLifecycle()
    var field by rememberSaveable { mutableStateOf(query) }
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(here) { find.moveTo(here) }
    LaunchedEffect(kind, brand) {
        if (!entered) {
            if (kind != null) find.enter(kind)
            if (brand != null) find.chain(brand)
        }
        entered = true
    }
    val over = rememberOverMap(Unit) { CameraPosition(here, 15.5) }
    // a kind or a brand opens at half, over its places on the map; the search itself, typed or not, at full
    val sheet = rememberSheetState(if (query.isNotEmpty() || kind == null && brand == null) SheetStop.FULL else SheetStop.HALF, SEARCH_SHEET_STOPS)
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val openPlace: (Place) -> Unit = { p -> services.remember(p); nav.go(Dest.Place.page(p)) }
    // back steps out of the words, then up the kinds to the one the page opened on (or back to its brand), then off it
    val opened = remember(kind) { kind?.let { app.atlas.places.CategoryTree.byId(it) ?: app.atlas.places.CategoryTree.category(it) } }
    val onBrand = brand != null && state.within == null && (state.asked as? Asked.Chain)?.let { it.brand.id == brand.id && it.from == null } == true
    val inside = field.isNotEmpty() || !onBrand && (state.asked != null || state.every != null || (state.node != null && state.node?.id != opened?.id))
    fun stepOut() {
        val asked = state.asked
        when {
            field.isNotEmpty() -> {
                field = ""
                typed.onText("")
            }
            // out of what the words asked, back to the words; out of a chain reached from another's page, back to it
            asked != null && state.within == null -> find.backFromAsked()?.let { words ->
                field = words
                typed.onText(words)
            }
            else -> find.up()
        }
    }
    BackHandler(enabled = inside) { stepOut() }
    LaunchedEffect(Unit) { if (query.isEmpty() && kind == null && brand == null) runCatching { focus.requestFocus() } }
    // what opens shows over the map: the sheet steps down to half, the places' pins above it, and goes up when pulled
    val subject = state.asked?.text ?: state.node?.let { "kind" }
    LaunchedEffect(subject) { if (subject != null && sheet.stop == SheetStop.FULL) sheet.animateTo(SheetStop.HALF) }
    // cards are for sliding through under the map: a full sheet steps down to show it
    LaunchedEffect(state.prefs.cards) { if (state.prefs.cards && state.inside && sheet.stop == SheetStop.FULL) sheet.animateTo(SheetStop.HALF) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val band = bandBetween(24.dp, maxHeight * HALF)
        // closed places lighter, as their rows are: open or not then as now, the sheet's
        val now = rememberNow(state.results)
        val markers = remember(state.results, text.results, field, state.focus, state.prefs.cards, now) {
            if (field.isNotBlank()) typedMarkers(text.results, now, state.centre, state.prefs.mode) else sheetMarkers(state, now)
        }
        FitToPlaces(over.state, state, band)
        FitToWalk(over.state, state, band)
        FollowCards(over.state, state, band)
        OverStageMap(
            over,
            modifier = Modifier.fillMaxSize(),
            background = Atlas.colors.paper,
            overlays = MapOverlays(markers = markers, routes = walkLines(state), location = UserLocation(here, 15.0), measure = false),
            ownPadding = PaddingValues(top = 24.dp, bottom = maxHeight * HALF),
            onTap = { hit ->
                when (hit) {
                    // as cards, a pin brings its card into view; a tap on the card opens it
                    is MapHit.OnMarker -> hit.marker.place?.let { p -> if (showsCard(state, field, p)) find.focus(p.key) else openPlace(p) }
                    is MapHit.OnFeature -> openPlace(hit.place)
                    is MapHit.Empty -> Unit
                }
            },
            onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
            onLongPressBuilding = { b -> nav.go(Dest.WhatsHere(b.at.lat, b.at.lon, b.id.toString())) },
            // a short tap on a building opens the place it is, or the one in it under the finger (MapHit.OnFeature)
            featureOf = { b -> services.localData.feature(b.id, b.at) },
        )
        SearchSheet(
            find, typed, field,
            onField = { field = it; typed.onText(it) },
            sheet = sheet, nav = nav, lang = lang, onPlace = openPlace,
            // the way back on the sheet's bands is the system's back: out of the words, up the kinds, off the page
            onBack = { if (inside) stepOut() else nav.back() },
            onPulledAway = { if (!nav.back()) sheet.snapTo(SheetStop.PEEK) },
            narrowedTo = kind?.let { app.atlas.places.Categories.byId(it)?.name(lang) },
            onTyping = { scope.launch { sheet.animateTo(SheetStop.FULL) } },
            focus = focus,
            halfFraction = HALF, fullTopGap = 40.dp,
        )
    }
}

/**
 * A kind's places as pins, each with its mark ([markOf]) as its row has it, lighter where its row's light says closed
 * at [now] ([closedThere]), ringed where it is of a kind people come to the view for most ([isCoreKind]): the first
 * [PINS] in full, the rest as dots; an outing's stops.
 */
internal fun sheetMarkers(s: SearchSheetState, now: LocalDateTime): List<Marker> {
    (s.asked as? Asked.Outing)?.let { o ->
        val stops = o.plan?.stops.orEmpty().mapNotNull { it.hit }
        return stops.mapIndexed { i, h -> Marker(h.place.key, h.record.location, h.symbol, 0xFF2E5BFF, MarkerStyle.PIN, priority = stops.size - i, place = h.place) }
    }
    val hits = s.results?.items.orEmpty().flatMap { it.hits }
    // as cards, the pin of the card in view is the chosen one, over the others
    val chosen = s.focus.takeIf { s.prefs.cards }
    return hits.mapIndexed { i, h ->
        val p = h.record.place
        val on = p.key == chosen
        pinOf(p, full = i < PINS || on, closedThere(h.open, h.minutes, now), rank = hits.size - i, selected = on, outlined = isCoreKind(s.node, h))
    }
}

/**
 * Whether [h] is of a kind people come to [view] for most ([CategoryNode.core]: a supermarket or a convenience store
 * among the groceries), ringed on the map (the owner, 6 November: "thats what people primarily search for when looking
 * for groceries", and of the ring "Yes, map only"): the chips and rows name their kind already.
 */
internal fun isCoreKind(view: CategoryNode?, h: ResultHit): Boolean =
    view != null && view.core.isNotEmpty() && view.isCore(h.node?.preset ?: Presets.match(h.record.tags))

/**
 * [p] as a pin ([full]) or a dot: a chain's on its own sign ([signOf]), as the brand's page pins it, else its mark
 * ([markOf]); lighter where [closed] and ringed where [outlined] (of a kind people come for most), but the chosen one.
 * A dot keeps the family's colour, a chain's too: the dots that fold into it show as a cluster in its colour. Where they
 * crowd, the pins go first, then an open place's dot before a closed one's, the ringed before the rest, each by [rank]:
 * a cluster is light only where all in it are closed.
 */
private fun pinOf(p: Place, full: Boolean, closed: Boolean, rank: Int, selected: Boolean = false, outlined: Boolean = false): Marker {
    val faded = closed && !selected
    val ringed = outlined && !selected
    val priority = rank + (if (ringed) TIER else 0) + when {
        selected -> 6 * TIER
        full -> 4 * TIER
        faded -> 0
        else -> 2 * TIER
    }
    if (full) signOf(p)?.let { brand ->
        return Marker(p.key, p.location, Symbols.symbolFor(p.tags), BrandSigns.argb(brand).toLong() and 0xFFFFFFFFL, MarkerStyle.BRAND, logo = brand.id, selected = selected, priority = priority, place = p, faded = faded, outlined = ringed)
    }
    val (symbol, colour) = markOf(p)
    val style = if (full) MarkerStyle.PIN else MarkerStyle.DOT
    return Marker(p.key, p.location, symbol, colour.toLong() and 0xFFFFFFFFL, style, selected = selected, priority = priority, place = p, faded = faded, outlined = ringed)
}

/** A step in the pins' priorities, above any rank among them. */
private const val TIER = 1_000_000

/** The place of the card in view while the places show as cards, where it is among them; null where not. */
internal fun focusedPlace(s: SearchSheetState): Place? {
    if (!s.prefs.cards || s.focus == null) return null
    return s.results?.items.orEmpty().firstNotNullOfOrNull { item ->
        item.hits.firstOrNull { it.record.place.key == s.focus }?.record?.place
    }
}

/** Whether a pin tapped on the map stands for one of the cards in view: the cards then slide to it rather than open it. */
internal fun showsCard(s: SearchSheetState, field: String, place: Place): Boolean =
    s.prefs.cards && field.isBlank() && s.asked !is Asked.Outing && !s.filtersOpen &&
        s.results?.items.orEmpty().any { it.first.record.place.key == place.key }

/**
 * The places among typed results as pins, each with its mark ([markOf]) as its row has it, lighter where its row's light
 * says closed at [now], [mode] from [here] ([closedThere]).
 */
internal fun typedMarkers(r: SearchResults?, now: LocalDateTime, here: LatLon, mode: TravelMode): List<Marker> {
    val places = r?.items.orEmpty().mapNotNull { item ->
        when (item) {
            is SearchItem.PlaceResult -> Triple(item.hit.place, item.hit.open, item.hit.distance)
            // an online place's hours are its own, as its row reads them
            is SearchItem.Online -> Triple(item.place, OpeningHours.parseOrNull(item.place.tags["opening_hours"])?.status(now), item.distance)
            is SearchItem.Element -> Triple(item.hit.place, item.hit.open, item.hit.distance)
            else -> null
        }
    }
    return places.mapIndexed { i, (p, open, distance) ->
        val minutes = Travel.minutes(distance ?: Geo.distance(here, p.location), mode)
        pinOf(p, full = i < PINS, closedThere(open, minutes, now), rank = places.size - i)
    }
}

/**
 * Brings a kind's nearest places into [band] when they are not all in view: once per kind and circle (a widened
 * circle's places lie further off), and only once the map knows what it shows.
 */
@Composable
internal fun FitToPlaces(map: MapState, s: SearchSheetState, band: Rect) {
    val r = s.results
    val key = r?.let { "${it.node.id}:${s.radius}:${it.hits.firstOrNull()?.record?.place?.key}" }
    LaunchedEffect(key) {
        if (r == null || r.hits.isEmpty()) return@LaunchedEffect
        val near = r.hits.sortedBy { it.metres }.take(FIT).map { it.record.location }
        val shown = snapshotFlow { map.visibleBounds }.first { it != null }!!
        if (near.all { it in shown }) return@LaunchedEffect
        BBox.of(near + s.centre)?.let { b ->
            map.follow = Follow.NONE
            runCatching { map.fit(b.buffer(60.0), 36, band) }
        }
    }
}

/** The round walk found, as a line on the map behind the sheet. */
internal fun walkLines(s: SearchSheetState): List<RouteLine> = s.walking?.walk?.let { listOf(RouteLine("round-walk", it.route.points)) }.orEmpty()

/** Brings a round walk found into [band], the whole of it, once the map knows what it shows. */
@Composable
internal fun FitToWalk(map: MapState, s: SearchSheetState, band: Rect) {
    val walk = s.walking?.walk
    LaunchedEffect(walk) {
        val box = walk?.route?.line?.bbox ?: return@LaunchedEffect
        snapshotFlow { map.visibleBounds }.first { it != null }
        map.follow = Follow.NONE
        runCatching { map.fit(box.buffer(40.0), 36, band) }
    }
}

/**
 * Keeps the place of the card in view on the map while the places show as cards: where its pin is out of [band] the
 * map glides to bring it into the band's middle; where it shows already, the map stays still.
 */
@Composable
internal fun FollowCards(map: MapState, s: SearchSheetState, band: Rect) {
    val place = remember(s.focus, s.results, s.prefs.cards) { focusedPlace(s) }
    LaunchedEffect(place?.key) {
        val p = place ?: return@LaunchedEffect
        val shown = map.latLonToScreen(p.location)?.let { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) }
        if (shown != null && band.contains(shown)) return@LaunchedEffect
        val to = map.placing(p.location, band.center.x, band.center.y, map.camera) ?: return@LaunchedEffect
        map.follow = Follow.NONE
        runCatching { map.animateTo(to, 450) }
    }
}

/** How much of the screen the sheet takes at half. */
private const val HALF = 0.5f

/** How many places get a full pin (the rest dots), and how many nearest the map brings into view. */
private const val PINS = 30
private const val FIT = 8
