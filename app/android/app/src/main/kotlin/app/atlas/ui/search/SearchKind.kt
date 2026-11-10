package app.atlas.ui.search

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.Palette
import app.atlas.search.CategoryResults
import app.atlas.search.EveryKind
import app.atlas.search.Facet
import app.atlas.search.FacetSet
import app.atlas.search.FacetValue
import app.atlas.search.Facets
import app.atlas.search.Intent
import app.atlas.search.Intents
import app.atlas.search.Layout
import app.atlas.search.LooseAspect
import app.atlas.search.Loosened
import app.atlas.search.Moment
import app.atlas.search.Order
import app.atlas.search.Outlook
import app.atlas.search.Part
import app.atlas.search.Purpose
import app.atlas.search.Purposes
import app.atlas.search.ResultHit
import app.atlas.search.ResultItem
import app.atlas.search.Sure
import app.atlas.search.Travel
import app.atlas.search.TravelMode
import app.atlas.search.WarningKind
import app.atlas.ui.go.segmentLine
import app.atlas.ui.home.HomeRow
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.signOf
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Inside a kind, or what typed words asked, over the map (decision 12; rebuilt on 4 November, the user doubting the
 * sentence of "words you can hold", and slimmed the same day: "if a user taps the eat category he wants to see places
 * right away"): the kind's band, the sheet's top in its colour, the answer where the words asked a question, one row of
 * the narrower kinds and what the kind is for (swimming, on lakes), one row of the order and the filters, then the
 * places, laid out by what the kind is for ([Purpose.layout]): the nearest leads where the nearest is the point, the
 * hours ahead lead out of doors, the time to spare on an errand, the power at a charger. At half height the first
 * places are in view under the two rows, and the map above.
 */
@Composable
internal fun KindList(
    s: SearchSheetState,
    find: SearchSheetModel,
    nav: Navigator,
    lang: String,
    now: LocalDateTime,
    room: Dp,
    kept: Map<String, Long>,
    onPlace: (Place) -> Unit,
    onBack: () -> Unit,
    onExplain: (Explained) -> Unit,
) {
    val node = s.node ?: return
    val services = LocalServices.current
    val results = s.results?.takeIf { it.node.id == node.id }
    val purpose = results?.purpose ?: remember(node.id) { Purposes.of(node) }
    val subs = results?.subcategories?.filter { it.second > 0 }.orEmpty()
    // the shelves its row of kinds leads into at its end (Sport from Fun), with as many around as the grid's tile says
    val doors = node.doors.mapNotNull { d -> s.pulses[d.id]?.count?.takeIf { it > 0 }?.let { d to it } }.takeIf { s.asked == null }.orEmpty()
    // the kinds that also sell what it is named for (a pastry shop's cakes at the bakeries), and those that belong with
    // it (a minigolf's golf courses), a press from their own view: none of the kind near, they come before the far ones;
    // else the row of kinds brings them up under it, pulled down or at its "See also" (the owner, 6 November: "Expand
    // the see also rows right into view by sliding down?")
    val also = s.alsoSold.takeIf { s.asked == null }.orEmpty()
    val see = s.seeAlso.takeIf { s.asked == null }.orEmpty()
    val othersFirst = s.noneWithin != null || results?.hits?.isEmpty() == true
    val rowAlso = also.takeUnless { othersFirst }.orEmpty()
    val rowSee = see.takeUnless { othersFirst }.orEmpty()
    // their places shown here too, kept for the kind (the owner, 6 November: "Fill the view with them and save that as a
    // combined view if desired?"): the banks in the cash machines' view
    val kin = remember(node.id) { SearchSheetModel.kin(node) }.takeIf { s.asked == null && s.intent == null }.orEmpty()
    val together = kin.isNotEmpty() && node.id in s.prefs.together
    val joining: (() -> Unit)? = if (kin.isEmpty()) null else { { find.together(node, !together) } }
    val door = remember(node.id) { AtlasScreens.of(node) }.takeIf { s.asked == null }
    // what one may go to the kind for (a lake: to swim, to walk round, to fish): among asked places it is theirs already
    val aims = remember(node.id) { Intents.of(node) }.takeIf { s.asked == null && s.intent == null && it.size > 1 }.orEmpty()
    // every kind it holds, near or not, on a list of its own, where it holds two or more (the owner, 6 November: "Theres
    // no way to see every kind that belongs to a category from within that category")
    val holds = remember(node.id) { EveryKind.of(node).size }
    val every = s.asked == null && (subs.size >= 2 || holds >= 2)
    var asking by remember { mutableStateOf<Unknown?>(null) }
    // a brand's page: what is known of it, its branches' week, whether that week is shown
    val chain = s.asked as? Asked.Chain
    val wiki = rememberBrandWiki(chain?.brand, lang)
    val hours = rememberChainHours(results.takeIf { chain != null }, s.centre)
    var week by remember(chain?.brand?.id) { mutableStateOf(false) }
    val labels: (String) -> String = { key -> Facets.label(key, lang) }
    // a wish no yes or no answers (a colour, a year) is told on the place's page
    val row = RowWays(
        lang, now, s.prefs.mode, purpose.layout, onPlace, onExplain, node = node.id, answered = s.answered, asked = find::answeredBefore, label = labels, kept = kept,
        onUnknown = { h, key -> if (Quests.yesNo(key)) asking = Unknown(h, key, labels(key)) else onPlace(h.record.place) },
        chain = chain?.let { brandName(it.brand) },
        // the park's own page, you where the phone is (the park kept for it, so it opens at once)
        onPlan = if (s.intent?.id == Intents.WALK) { p -> services.remember(p); nav.go(Dest.Parkland.of(p)) } else null,
        plans = { p -> p.bbox != null && s.intent?.roomy?.matches(p.tags) != false },
    )
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        // a kind's hero and a brand's band stay the sheet's top while its places scroll under them; what words asked
        // scrolls with them
        when {
            chain != null -> {
                val id = HomeRow.idOf(chain.brand)
                BrandBand(chain.brand, results, wiki, s.prefs.cards, lang, onBack, find::showCards, starred = id in s.prefs.row, onStar = { find.star(id, it) })
            }
            s.asked == null -> {
                val id = HomeRow.idOf(node)
                KindHead(s, node, kin.takeIf { together }.orEmpty(), results, lang, now, onBack, find::showCards, starred = id in s.prefs.row, onStar = { find.star(id, it) })
            }
        }
        // a round walk asked for leaves the list's end for the top, under the band, in sight as the sheet comes down to
        // show its way on the map; let go, it goes back to the end
        if (s.walking != null) RoundWalkRow(s, find, nav, pinned = true)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag(KIND_LIST_TAG), state = list, contentPadding = PaddingValues(bottom = room)) {
            s.asked?.takeIf { chain == null }?.let { asked -> item(key = "head") { AskedHead(asked, s, results, find, nav, lang, onBack) } }
            chain?.let { ch ->
                item(key = "brand") { BrandDetails(ch.brand, wiki, results, hours, week, now, lang) { week = !week } }
                if (week) hours?.let { h -> item(key = "week") { HoursCard(h, now, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) } }
            }
            (s.asked as? Asked.Words)?.let { w ->
                w.count?.let { c -> item(key = "count") { CountCard(c, Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp)) } }
                w.hours?.let { h -> item(key = "hours") { HoursCard(h, now, Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp)) } }
            }
            val rowOfKinds = subs.isNotEmpty() || doors.isNotEmpty() || aims.isNotEmpty() || door != null || every || rowAlso.isNotEmpty() || rowSee.isNotEmpty()
            if (rowOfKinds) {
                item(key = "subs") {
                    SubRow(
                        subs, node, doors, aims, s.aim, door, find, nav, lang, now, asked = s.asked != null, every = every, also = rowAlso, see = rowSee,
                        together = together, onTogether = joining,
                        open = node.id in s.unfolded, atTop = { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 },
                        onHold = { onExplain(Explained(it)) },
                    ) { find.unfold(node.id, it) }
                }
            }
            // under the row of kinds, which keeps room for its chips' shadows, the gap is that room
            if (results != null) item(key = "filters") { FilterRow(s, results, purpose, find, lang, top = if (rowOfKinds) FILTER_GAP - SHADOW_ROOM else FILTER_GAP) }
            if (purpose.outdoors) s.moment.outlook?.let { o -> item(key = "sky") { SkyLine(o, s.moment) } }
            // nowhere near, the nearest wherever they are; nothing just as asked, the closest (what words let go of says it already)
            s.noneWithin?.let { r -> item(key = "far") { RelaxNote("ui-map-pin", stringResource(R.string.relax_none_within, reachWords(r, s.prefs.mode))) } }
            if (results?.closest == true && (s.asked as? Asked.Words)?.loosened.isNullOrEmpty()) {
                item(key = "closest") { RelaxNote("ui-filter", stringResource(R.string.relax_closest)) }
            }
            // none of the kind near: the kinds near that sell its goods too, and those that belong with it, before the far ones
            if (othersFirst) otherKinds(also, see, lang, together, joining, onHold = { onExplain(Explained(it)) }) { find.enter(it.id, door = true) }
            when {
                results == null && s.error != null -> item(key = "error") {
                    StateMessage("ui-alert-triangle", stringResource(R.string.search_failed), Modifier.padding(16.dp), text = s.error, tint = Color(0xFFC8322A))
                }
                results == null -> item(key = "looking") { Looking() }
                results.hits.isEmpty() -> item(key = "none") {
                    StateMessage(
                        if (s.selection.isEmpty) node.symbol else "ui-filter",
                        stringResource(if (s.selection.isEmpty) R.string.category_none else R.string.find_none_left),
                        Modifier.padding(16.dp), text = s.error, tint = indexColors().soft,
                    )
                }
                // as cards: the same places in the same order, one row of them to slide through under the map
                s.prefs.cards -> item(key = "cards") {
                    ResultCards(if (results.parts.size > 1) results.parts.flatMap { it.items } else results.items, row, node, s.focus, find::focus)
                }
                // an intent chosen (swimming): the places part by how surely each serves it, the surest first; all of them
                // one part, their rows say it
                results.parts.size > 1 -> for (part in results.parts) {
                    item(key = "part:" + part.sure) { PartHead(part, s.aim) }
                    rows(part.items, row, "part:" + part.sure)
                }
                // the nearest is the point (a toilet, a cash machine): the way to it leads, the rest after
                purpose.layout == Layout.NEAREST && results.items.firstOrNull() is ResultItem.One -> {
                    val lead = (results.items.first() as ResultItem.One).hit
                    item(key = "lead") { LeadCard(lead, s.prefs.mode, lang, now, nav, onPlace, onExplain) }
                    rows(results.items.drop(1), row, "rest")
                }
                else -> rows(results.items, row, "all")
            }
            // a walk's places first, a way round from here second (the owner, 9 November: "can a walk gather as many
            // places to walk to first? A route as a second feature?")
            if (s.intent?.id == Intents.WALK && results != null && s.walking == null) item(key = ROUND_KEY) { RoundWalkRow(s, find, nav) }
            // the chains of its kind around, a press from their own pages; the way to hide it
            chain?.let { ch ->
                item(key = "kin") { AlsoNear(ch.brand, s.brands, find::sibling) }
                item(key = "hide") { HideBrand(ch.brand) }
            }
            s.origin?.let { o ->
                item(key = "origin") { Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp)) { OriginLine(o, s.error) } }
            }
        }
    }
    asking?.let { u -> UnknownDialog(u, find, lang) { asking = null } }
}

/** What every row of a kind's places is drawn with: the language, the hour, the way of getting about, the kind's layout. */
internal class RowWays(
    val lang: String,
    val now: LocalDateTime,
    val mode: TravelMode,
    val layout: Layout,
    val onPlace: (Place) -> Unit,
    val onExplain: (Explained) -> Unit,
    /** The kind the view is of: its rows do not say it again. */
    val node: String? = null,
    /** The wishes answered from rows so far ([SearchSheetState.answered]). */
    val answered: Map<String, Sending> = emptyMap(),
    /** Whether a wish of a place was answered from this phone before: its "?" is not asked again. */
    val asked: (Place, String) -> Boolean = { _, _ -> false },
    /** What a wish's key is called ("Toilets"). */
    val label: (String) -> String = { it },
    /** A "?" tapped: the place and the key of the wish it asks of. */
    val onUnknown: ((ResultHit, String) -> Unit)? = null,
    /** The places kept in a list, by key: the colour of the list, for the bookmark on their rows ([keptColours]). */
    val kept: Map<String, Long> = emptyMap(),
    /** The name of the brand whose page the view is: every row is of it, so each says which branch it is ([branchName]). */
    val chain: String? = null,
    /** Names its rows say, by place key, over the places' own: in a kind's deck, the street each is on ([DeckRow]). */
    val names: Map<String, String> = emptyMap(),
    /** The way from a green's row to its own plan (a walk's view): null where its places have none. */
    val onPlan: ((Place) -> Unit)? = null,
    /** Which of its places have a plan to go to ([onPlan]): a green with an outline, not a monument's. */
    val plans: (Place) -> Boolean = { it.bbox != null },
) {
    /** [h]'s name as its row and its card say it: as [names] has it, on a brand's page which branch it is, else its own. */
    fun nameOf(h: ResultHit): String? = names[h.record.place.key] ?: chain?.let { branchName(h.record.place, it, lang) } ?: h.record.name(lang)

    private val view = node?.let { CategoryTree.byId(it) }

    /** What a row says [k] is: under the kind the view is of, what its name adds to that ("Italian" in the restaurants'). */
    fun kindName(k: CategoryNode): String = view?.let { k.nameUnder(it, lang) } ?: k.name(lang)

    /** These ways for the rows of a deck of the [kind]'s places: none says the kind again, each named as [names] has it. */
    fun within(kind: String, names: Map<String, String>) =
        RowWays(lang, now, mode, layout, onPlace, onExplain, kind, answered, asked, label, onUnknown, kept, chain, names)
}

/** The places kept in [lists], by key: the colour of the first list each is kept in. */
internal fun keptColours(lists: List<SavedList>): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (l in lists) for (p in l.places) out.putIfAbsent(p.place.key, l.colour)
    return out
}

/** The rows of [items]: a place, or places on one deck (a brand's branches, a kind's places with no name of their own). */
private fun LazyListScope.rows(items: List<ResultItem>, w: RowWays, part: String) {
    items(items, key = { part + ":" + it.first.record.place.key }) { item ->
        when (item) {
            is ResultItem.One -> PlaceRow(item.hit, w, hairline = item !== items.lastOrNull())
            is ResultItem.Deck -> DeckRow(item, w)
        }
    }
}

// --------------------------------------------------------------------------------------------------------------- heads

/**
 * The kind's band: its card grown into the sheet's top (the dive), its colour and art out to the sheet's edges, no
 * taller than the way back, its name and what it says now: how many are near ("21 of 68" once filters narrow them), how
 * many are open, the minutes to the nearest. The places start right under it (the user, 4 November, of the hero it
 * was: "too much space wasted, if a user taps the eat category he wants to see places right away"). No close: the way
 * back, the category row's key and the system's back all close it.
 */
/**
 * A kind's hero: the way back, its name (with the kinds its view shows [with] it, each its symbol on its colour as on
 * its chip and its pins, named to a screen reader: three names, "Frozen Food + Supermarket + Convenience Store", did not
 * fit the band's line), its figures, the way its places are shown.
 */
@Composable
private fun KindHead(
    s: SearchSheetState,
    node: CategoryNode,
    with: List<CategoryNode>,
    results: CategoryResults?,
    lang: String,
    now: LocalDateTime,
    onBack: () -> Unit,
    onCards: (Boolean) -> Unit,
    starred: Boolean,
    onStar: (Boolean) -> Unit,
) {
    val art = remember(node.id) { artOf(node) }
    Row(
        Modifier
            .fillMaxWidth()
            .dive(if (s.intent == null) diveKey(node) else null)
            .bandSurface(Color(node.colour), art, clear = BAND_CONTROLS)
            .padding(start = 4.dp, end = 8.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandBack(stringResource(R.string.find_up), onBack)
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            val title = s.intent?.let { intentName(it, lang, now.toLocalTime()) } ?: node.name(lang)
            if (s.intent != null || with.isEmpty()) {
                Text(title, style = indexTitle(20f).onCard(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), style = indexTitle(20f).onCard(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    for (k in with) {
                        Text("+", Modifier.padding(horizontal = 6.dp).clearAndSetSemantics {}, style = indexTitle(20f).onCard())
                        Box(
                            Modifier.size(24.dp).border(1.5.dp, Color.White, CircleShape).clip(CircleShape).background(cardBrush(Color(k.colour)))
                                .semantics { contentDescription = k.name(lang) },
                            contentAlignment = Alignment.Center,
                        ) { Symbol(k.symbol, tint = Color.White, size = 13.dp) }
                    }
                }
            }
            // out of doors (a walk's parks, monuments and views) few places keep hours: "open" would count those that do
            // with Largest chosen the region's largest are listed beyond the circle: near are those in it
            if (results != null) Figures(
                results, Color.White, Modifier.padding(top = 1.dp), size = 14f, showOpen = s.intent?.outdoors != true,
                circle = s.radius.takeIf { s.order == Order.LARGEST && it > 0.0 },
            )
        }
        // the way back at one end, the way the places are shown at the other, the home's row's star beside it (a kind's,
        // not what serves an intent)
        if (s.intent == null) RowStar(starred, onStar)
        ViewSwitch(s.prefs.cards, Color.White.copy(alpha = 0.94f), Color(node.colour), onCards)
    }
}

/**
 * A walk round from where one is, at the head of a walk's view (the owner, 9 November: "If theres a walk chip can
 * results be more advanced?"): how long, in a press; the way found comes up on the map behind the sheet with how far it
 * is and the green it goes through; one press starts it.
 */
@Composable
private fun RoundWalkRow(s: SearchSheetState, find: SearchSheetModel, nav: Navigator, pinned: Boolean = false) {
    val c = indexColors()
    val asked = s.walking
    Column(
        Modifier.fillMaxWidth()
            .then(if (pinned) Modifier.drawBehind { drawLine(c.hair, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) } else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (pinned) 10.dp else 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol("ui-loop", tint = c.ink, size = 19.dp)
            Text(stringResource(R.string.walk_round), Modifier.weight(1f), style = INDEX_NAME, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            for (m in ROUND_WALKS) {
                val said = spokenMinutes(m)
                IndexChip(primeMinutes(m), { find.roundWalk(m) }, Modifier.semantics { contentDescription = said }, selected = asked?.minutes == m)
            }
        }
        asked?.let { RoundWalkAnswer(it, find, nav) }
    }
}

/** What came of the round walk asked for: where it goes and the way to start it, or being looked for, or why there is none. */
@Composable
private fun RoundWalkAnswer(a: RoundWalkState, find: SearchSheetModel, nav: Navigator) {
    val c = indexColors()
    val walk = a.walk
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = stringResource(R.string.walk_round)
    val online by LocalServices.current.connectivity.online.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val words = when {
            walk != null -> listOfNotNull(walk.through?.let { stringResource(R.string.walk_through, it) }, shortDistance(walk.route.distance)).joinToString(" · ")
            a.fetch != null -> segmentLine(a.fetch)
            a.needs.isNotEmpty() -> listOf(stringResource(R.string.go_needs_data_title), a.needs.joinToString { it.label }).joinToString(" · ")
            a.failed != null -> stringResource(R.string.walk_none)
            else -> stringResource(R.string.walk_finding)
        }
        Text(words, Modifier.weight(1f), style = if (walk != null) INDEX_NAME else INDEX_META, color = if (walk != null) c.ink else c.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when {
            walk != null -> GoKey((walk.route.duration / 60).roundToInt(), stringResource(R.string.walk_start)) {
                scope.launch {
                    val end = walk.request.waypoints.last()
                    if (find.startWalk(context, name)) nav.go(Dest.Navigate(end.lat, end.lon, name, Mode.WALK.name))
                }
            }
            a.needs.isNotEmpty() && a.fetch == null ->
                IndexChip(stringResource(if (online) R.string.go_download_data else R.string.go_download_offline), { if (online) find.fetchWalkData() }, symbol = "ui.download", faint = !online, role = Role.Button)
        }
    }
}

/** The minutes offered for a round walk. */
private val ROUND_WALKS = listOf(20, 40, 60)

/** The key of the round walk's row on a walk's list. */
internal const val ROUND_KEY = "round"


/** The key that starts a way: the arrow and its minutes, in ink. */
@Composable
private fun GoKey(minutes: Int, said: String, onClick: () -> Unit) {
    val c = indexColors()
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(c.ink)
            .clickable(role = Role.Button, onClickLabel = said, onClick = onClick)
            .semantics { contentDescription = said }
            .padding(start = 13.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui-navigation", tint = c.onInk, size = 17.dp)
        MinutesFigure(minutes, size = 21f, colour = c.onInk)
    }
}

/** The way back on a band's colour: its chevron in white (a brand's band: in its lettering's colour), with no key around it. */
@Composable
internal fun BandBack(description: String, onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol("ui-chevron-left", tint = tint, size = 22.dp) }
}

/**
 * The star that keeps a kind or a chain in the home's own row ([HomeRow]; the owner, 6 November: "Favorite re order
 * customize?"): the boards' star filled while it is in it (`ui.star`), else drawn round (`ui.star.2`; `ui-star` stands
 * for the filled one too); a press puts it at the row's end or takes it out; on a band's colour as the way back is, in
 * white (a brand's band: in its lettering's colour), with no key around it.
 */
@Composable
internal fun RowStar(on: Boolean, onStar: (Boolean) -> Unit, tint: Color = Color.White) {
    val said = stringResource(R.string.row_star)
    Box(
        Modifier.padding(end = 6.dp).size(40.dp).clip(CircleShape).toggleable(on, role = Role.Switch, onValueChange = onStar).semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) { Symbol(if (on) "ui.star" else "ui.star.2", tint = tint, size = 22.dp) }
}

/**
 * How many there are (of how many before filters narrowed them), how many are open, the minutes to the nearest. With
 * [circle] (metres) the places listed beyond it are not counted as near (a region's largest, Largest chosen).
 */
@Composable
internal fun Figures(results: CategoryResults, colour: Color, modifier: Modifier = Modifier, size: Float = 20f, showOpen: Boolean = true, circle: Double? = null) {
    val hits = circle?.let { r -> results.hits.filter { it.metres <= r } } ?: results.hits
    val open = hits.filter { it.open?.isOpen == true }
    val total = results.facets.total
    val narrowed = results.facets.kept.size < total && total > hits.size
    val near = if (narrowed) stringResource(R.string.find_word_of, total) else stringResource(R.string.find_word_near)
    // with "open now" chosen, all of them open says nothing more
    val openSaid = results.facets.byId(Facets.OPEN)?.selected == true && open.size == hits.size
    val nearest = hits.minOfOrNull { it.minutes }
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy((size * 0.8f).dp)) {
        Figure(TellFigure(hits.size.toString(), near), colour, size)
        if (showOpen && open.isNotEmpty() && !openSaid) Figure(openTell(open.size, open.count { !it.hoursConfirmed }), colour, size)
        if (nearest != null && hits.size > 1) Figure(minutesTell(nearest), colour, size)
    }
}

/**
 * What typed words asked, as the sheet's head on its paper: the words, what they were read as (each part a tap from
 * gone, the words then asked again), the same in OpenStreetMap's own query language a tap further; what was let go of
 * where nothing was just as asked, an area no map knows, and how many there are.
 */
@Composable
private fun AskedHead(asked: Asked, s: SearchSheetState, results: CategoryResults?, find: SearchSheetModel, nav: Navigator, lang: String, onBack: () -> Unit) {
    val c = indexColors()
    val within = s.within
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IndexRound("ui-chevron-left", stringResource(R.string.find_up), onBack)
            when (asked) {
                is Asked.Chain -> Box(Modifier.padding(start = 12.dp)) { BrandLogo(asked.brand, 36.dp) }
                is Asked.Sells -> KindPin(asked.goods.symbol, GOODS, Modifier.padding(start = 12.dp), size = 36.dp)
                is Asked.Tags -> KindPin(asked.symbol, s.node?.colour ?: POINT, Modifier.padding(start = 12.dp), size = 36.dp)
                else -> Unit
            }
            Text(asked.text, Modifier.weight(1f).padding(horizontal = 12.dp), style = indexTitle(21f), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ViewSwitch(s.prefs.cards, c.card, c.ink, find::showCards)
        }
        if (asked is Asked.Words) {
            val out = stringResource(R.string.find_word_remove)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (chip in asked.parsed.chips) {
                    val span = chip.span
                    // what is not wanted with a cross for its symbol: no grammar of "not" in four languages
                    IndexChip(
                        chip.label, { span?.let { find.reword(asked.text.removeRange(span).replace(SPACES, " ").trim()) } },
                        symbol = if (chip.negated) "ui-x" else chip.symbol, selected = span != null, onClickLabel = out.takeIf { span != null },
                    )
                }
                IndexChip(stringResource(R.string.find_ql), { nav.go(Dest.Query(asked.text)) }, ghost = true, role = Role.Button)
            }
            val notes = buildList {
                asked.unresolvedArea?.let { add("ui-map-pin" to stringResource(R.string.find_area_unknown, it)) }
                if (asked.loosened.isNotEmpty()) add("ui-filter" to loosenedWords(asked.loosened))
                if (asked.online) add("ui-cloud" to stringResource(R.string.find_online))
            }
            for ((symbol, words) in notes) {
                Row(Modifier.padding(start = 22.dp, end = 18.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Symbol(symbol, Modifier.padding(top = 1.dp), tint = c.soon, size = 14.dp)
                    Text(words, style = INDEX_META, color = c.soft)
                }
            }
        }
        // narrowed to one kind among them: which, a tap from all of them again
        within?.let { w ->
            Row(Modifier.padding(start = 16.dp, top = 12.dp)) {
                IndexChip(w.name(lang), { find.narrow(null) }, symbol = w.symbol, selected = true)
            }
        }
        if (results != null) Figures(results, c.ink, Modifier.padding(start = 22.dp, top = 12.dp))
    }
}

/**
 * What was let go of where nothing was just as asked: the time, how far around, how near to what was named, being
 * inside, what it should have, the rating, the area.
 */
@Composable
private fun loosenedWords(loosened: List<Loosened>): String {
    val parts = loosened.map { l ->
        when (l.aspect) {
            LooseAspect.TIME -> stringResource(R.string.find_loose_time)
            LooseAspect.FAR -> stringResource(R.string.find_loose_far)
            LooseAspect.NEAR -> stringResource(R.string.find_loose_near)
            LooseAspect.INSIDE -> stringResource(R.string.find_loose_inside)
            LooseAspect.WISH -> stringResource(R.string.relax_loose_wish)
            LooseAspect.RATING -> stringResource(R.string.relax_loose_rating)
            LooseAspect.AREA -> stringResource(R.string.relax_loose_area)
        }
    }.distinct()
    return stringResource(R.string.find_loosened, parts.joinToString(", "))
}

private val SPACES = Regex("\\s+")

/** A word over the places where they are not just as asked: none fits all of it, or none is within the widest circle. */
@Composable
private fun RelaxNote(symbol: String, words: String) {
    val c = indexColors()
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Symbol(symbol, Modifier.padding(top = 1.dp), tint = c.soon, size = 14.dp)
        Text(words, style = INDEX_META, color = c.soft)
    }
}

// ------------------------------------------------------------------------------------------------------------- filters

/**
 * The order and the filters, in one row under the head: the order the places are in first (a tap offers the kind's
 * other orders), all the filters, how far the places are looked for (in minutes of the searcher's way of getting about,
 * a tap the next way), the chosen ones (a tap takes one out, a hold keeps it in every search: the brass ribbon), then
 * those most worth a tap with how many of the places shown have each.
 */
@Composable
private fun FilterRow(s: SearchSheetState, results: CategoryResults, purpose: Purpose, find: SearchSheetModel, lang: String, top: Dp = FILTER_GAP) {
    val facets = results.facets
    val chosen = chosenWords(facets)
    val next = facets.chips(CHIPS + chosen.size).filterNot { it.second.selected }
    val out = stringResource(R.string.find_word_remove)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = top, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (results.hits.isNotEmpty()) OrderChip(s, results, purpose, find, lang)
        IndexChip(stringResource(R.string.find_filters), { find.showFilters(true) }, symbol = "ui-sliders", count = chosen.size.takeIf { it > 0 }, role = Role.Button)
        ReachChip(s, find)
        for ((f, v) in chosen) {
            IndexChip(
                v.chip, { find.toggle(f.id, v.id) }, symbol = v.symbol, selected = true, kept = "${f.id}=${v.id}" in s.prefs.sticky,
                hides = hidden(f, facets), onLongClick = { find.keep(f.id, v.id) }, onClickLabel = out,
            )
        }
        for ((f, v) in next) {
            IndexChip(
                v.chip, { find.toggle(f.id, v.id) }, symbol = v.symbol, count = v.count, share = share(v.count, f.over), ghost = true,
                kept = "${f.id}=${v.id}" in s.prefs.sticky, onLongClick = { find.keep(f.id, v.id) },
            )
        }
    }
}

/** How far the places are looked for, in minutes of the way of getting about ("🚶 20′"): a tap counts them the next way. */
@Composable
internal fun ReachChip(s: SearchSheetState, find: SearchSheetModel) {
    val mode = s.prefs.mode
    val next = TravelMode.entries[(mode.ordinal + 1) % TravelMode.entries.size]
    val said = stringResource(R.string.find_within, reachWords(s.radius, mode, aloud = true)) + ", " + modeName(mode)
    IndexChip(
        reachWords(s.radius, mode), { find.setMode(next) },
        Modifier.semantics { contentDescription = said }, symbol = mode.symbol, role = Role.Button,
        onClickLabel = stringResource(R.string.find_reach_next, modeName(next).replaceFirstChar { it.lowercase(Locale.getDefault()) }),
    )
}

/**
 * How far [radius] reaches by [mode], in minutes rounded up to five ("20′"), or as said ("20 min"); from a hundred
 * minutes on, where hours on foot say little, the distance itself ("25 km").
 */
@Composable
private fun reachWords(radius: Double, mode: TravelMode, aloud: Boolean = false): String {
    val minutes = (Travel.minutes(radius, mode) + 4) / 5 * 5
    return when {
        minutes >= 100 -> shortDistance(radius)
        aloud -> spokenMinutes(minutes)
        else -> primeMinutes(minutes)
    }
}

/** The words chosen on a kind, in the order of their facets. */
internal fun chosenWords(facets: FacetSet): List<Pair<Facet, FacetValue>> = facets.facets.flatMap { f -> f.values.filter { it.selected }.map { f to it } }

/**
 * How many more places there would be without [f]'s word: those every other word keeps but it does not. Null where
 * none is hidden, or where [f] has several words (each alone hides less than all of them).
 */
internal fun hidden(f: Facet, facets: FacetSet): Int? =
    if (f.values.count { it.selected } == 1) (f.over - facets.kept.size).takeIf { it > 0 } else null

internal fun share(count: Int, over: Int): Float = if (over > 0) count.toFloat() / over else 0f

/** Out of doors, the hours ahead: how warm it is, the one thing to know before going out ([Outlook.warning]), the sunset. */
@Composable
private fun SkyLine(o: Outlook, m: Moment) {
    val c = indexColors()
    val w = o.warning()
    val said = buildList {
        m.weather?.tempC?.let { add(Triple("ui-thermometer", "${it.roundToInt()}°", false)) }
        if (w != null) {
            val at = clock(w.at)
            add(
                when (w.kind) {
                    WarningKind.STORM -> Triple("ui-cloud-lightning", stringResource(R.string.find_sky_storm, at), true)
                    WarningKind.HEAVY_RAIN -> Triple("ui-cloud-rain", stringResource(R.string.find_sky_heavy, at), true)
                    WarningKind.RAIN -> Triple("ui-cloud-rain", stringResource(R.string.home_moment_rain, at), false)
                    WarningKind.DRY -> Triple("ui-cloud-rain", stringResource(R.string.home_moment_dry, at), true)
                },
            )
        } else if (o.wetNow) {
            add(Triple("ui-cloud-rain", stringResource(R.string.find_sky_wet), true))
        } else {
            add(Triple("ui-sun", stringResource(R.string.find_sky_dry), false))
        }
        o.sunset?.let { add(Triple("ui-sunset", stringResource(R.string.home_moment_sunset, "%d:%02d".format(Locale.ROOT, it.hour, it.minute)), false)) }
    }
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((symbol, words, warn) in said) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(symbol, tint = if (warn) c.soon else c.soft, size = 14.dp)
                Text(words, style = INDEX_META, color = if (warn) c.soon else c.soft, maxLines = 1)
            }
        }
    }
}

/**
 * The order the places are in, as the row's first chip ("Best now", "Open longest · for Sat 23:00" where an hour is
 * asked about): a tap offers the kind's other orders.
 */
@Composable
private fun OrderChip(s: SearchSheetState, results: CategoryResults, purpose: Purpose, find: SearchSheetModel, lang: String) {
    val c = indexColors()
    var open by remember { mutableStateOf(false) }
    val order = results.order
    val orders = (purpose.orders + listOfNotNull(Order.ASKED.takeIf { order == it })).distinct()
    val more = orders.size > 1
    val at = s.at?.let { t -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + clock(t) }
    Box {
        Row(
            Modifier
                .height(38.dp)
                .raised(CircleShape, c, y = 1.dp, blur = 4.dp)
                .clickable(enabled = more, role = Role.Button, onClickLabel = stringResource(R.string.find_order_change)) { open = true }
                .padding(start = 11.dp, end = if (more) 10.dp else 13.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(order.symbol, tint = c.ink, size = 15.dp)
            Text(
                order.label(lang) + (at?.let { " · " + stringResource(R.string.find_order_for, it) } ?: ""),
                style = boardText(13.5f, 550, 17f), color = c.ink, maxLines = 1,
            )
            if (more) Symbol("ui.chevron-down", tint = c.faint, size = 12.dp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = c.card) {
            for (o in orders) {
                DropdownMenuItem(
                    text = { Text(o.label(lang), style = boardText(14f, if (o == order) 650 else 500, 18f), color = c.ink) },
                    leadingIcon = { Symbol(o.symbol, tint = if (o == order) c.ink else c.soft, size = 16.dp) },
                    onClick = { open = false; find.setOrder(o) },
                )
            }
        }
    }
}

/** A part of the places by how surely each serves what was chosen ("✓ surely", "? not said"), with how many. */
@Composable
private fun PartHead(part: Part, aim: Intent?) {
    val c = indexColors()
    val (mark, words, tint) = when (part.sure) {
        Sure.YES -> Triple("ui-check", R.string.find_sure_yes, c.open)
        Sure.LIKELY -> Triple("ui-check", R.string.find_sure_likely, c.soon)
        Sure.MAYBE -> Triple("ui-help", R.string.find_sure_maybe, c.soft)
        Sure.NO -> Triple("ui-x", R.string.find_sure_no, c.faint)
    }
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        aim?.let { Symbol(it.symbol, tint = c.soft, size = 15.dp) }
        Symbol(mark, tint = tint, size = 15.dp)
        Text(stringResource(words), Modifier.weight(1f), style = boardText(14f, 650, 18f), color = c.ink, maxLines = 1)
        Text(part.hits.size.toString(), style = indexFigure(14f, 650), color = c.faint, maxLines = 1)
    }
}

/**
 * Where the nearest is the point (a toilet, a cash machine, a pharmacy): it leads as a card of its own, its kind's
 * disc, its name and open or not, and at its end the way there one tap away, the minutes to it on the key ("➤ 3′").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeadCard(h: ResultHit, mode: TravelMode, lang: String, now: LocalDateTime, nav: Navigator, onPlace: (Place) -> Unit, onExplain: (Explained) -> Unit) {
    val c = indexColors()
    val p = h.record.place
    val line = remember(h, now) { PlaceLine.of(p, null, lang, now, h.open, h.record.name(lang), h.metres) }
    val arrival = arrivalWords(h.open, h.minutes, now)
    val tone = arrival?.second ?: if (h.open == null) Tone.UNKNOWN else line.tone
    val words = (arrival?.first ?: openWords(line))?.let { if (!h.hoursConfirmed) "≈ $it" else it }
    val name = line.summary.name
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .fillMaxWidth()
            .pressable(interaction, depth = 0.02f)
            .raised(shape, c, y = 4.dp, blur = 16.dp)
            .combinedClickable(
                interactionSource = interaction, indication = null,
                onLongClick = { h.node?.let { onExplain(explained(it, h)) } },
                onClick = { onPlace(p) },
            )
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        remember(p) { signOf(p) }?.let { SignDisc(it, tone, size = 44.dp) } ?: KindDisc(line.summary.symbol, line.summary.colour, tone, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caps(stringResource(R.string.find_nearest))
            Text(name, style = INDEX_NAME, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(statusOf(words, tone, staleMark(h), listOfNotNull(h.misses.firstOrNull()?.let { missWords(it) }, h.node?.name(lang))), style = INDEX_META, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GoKey(h.minutes, stringResource(R.string.find_go)) { nav.go(Dest.Route(p.location.lat, p.location.lon, name, mode = routeMode(mode))) }
    }
}

/** The route planner's way of getting about for the search's. */
internal fun routeMode(mode: TravelMode): String? = runCatching { Mode.valueOf(mode.name) }.getOrNull()?.name

/** The row of a kind's narrower kinds, for tests to pull open. */
internal const val SUBS_TAG = "kind-subs"

/**
 * The narrower kinds as chips with how many of each are here, then what one may go to the kind for ("swim", "walk
 * round": one chosen, its places are looked for and the results part by it), then the kind's own screen in Atlas,
 * where it has one: one row, so the places start under it. Where they run past the sheet's edge, a pull down on the
 * row unfolds it into lines that show every one, and a pull up folds it again (the owner, 6 November: "Pull down on
 * the subcategory row to expand?"); the row gives a little to the finger before it goes. Where the kind has kinds that
 * also sell what it is named for, or that belong with it, its dashed "See also" ends the row with how many, and the
 * pull, or a press on it, brings them up under the row as well, every chip in view (the owner, 6 November: "Expand the
 * see also rows right into view by sliding down?"), with the switch that shows their places here too at the end of
 * their caption ([Together]); while it is on, "See also" stays inked folded too. Only with the places at their top, so that while
 * they are scrolled a drag on it is still the list's; read aloud, an action does the same.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubRow(
    subs: List<Pair<CategoryNode, Int>>,
    /** The kind they are narrower kinds of, which says the rest of their names ("Italian" in the restaurants'). */
    under: CategoryNode?,
    doors: List<Pair<CategoryNode, Int>>,
    aims: List<Intent>,
    aim: Intent?,
    door: Pair<Dest, Int>?,
    find: SearchSheetModel,
    nav: Navigator,
    lang: String,
    /** The time the places are for: the time to eat among the aims is named after its meal then. */
    now: LocalDateTime,
    asked: Boolean,
    every: Boolean,
    also: List<Pair<CategoryNode, Int>>,
    see: List<Pair<CategoryNode, Int>>,
    together: Boolean,
    onTogether: (() -> Unit)?,
    open: Boolean,
    atTop: () -> Boolean,
    /** A chip held: what its kind is, and its star ([ExplainDialog]). */
    onHold: (CategoryNode) -> Unit,
    onOpen: (Boolean) -> Unit,
) {
    val scroll = rememberScrollState()
    // the other kinds the row brings up when open
    val kin = also.size + see.size
    // folded, there is more to show where the chips run past the edge, or other kinds wait
    val more = open || scroll.maxValue > 0 || kin > 0
    val give = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reach = with(LocalDensity.current) { PULL.toPx() }
    val unfolded by rememberUpdatedState(open)
    val pullable by rememberUpdatedState(more)
    val turn by rememberUpdatedState(onOpen)
    val action = stringResource(if (open) R.string.find_chips_fold else R.string.find_chips_unfold)
    val chips: @Composable () -> Unit = {
        // among asked places a kind narrows them; in a kind it opens the narrower kind around. What people come for most
        // leads (Browse.subcategories), unringed: the chip names it already
        for ((sub, n) in subs) SubChip(sub.symbol, under?.let { sub.nameUnder(it, lang) } ?: sub.name(lang), n, sub.colour, onLongClick = { onHold(sub) }) { if (asked) find.narrow(sub) else find.enter(sub.id) }
        // after its own kinds and those it holds too, the shelves it leads into (Sport from Fun), back coming back here;
        // dashed as the ways onward are, so that they read as a way out and not as more of this one's kinds
        for ((d, n) in doors) SubChip(d.symbol, d.name(lang), n, d.colour, ghost = true, onLongClick = { onHold(d) }) { find.enter(d.id) }
        for (i in aims) {
            val on = aim?.id == i.id
            IndexChip(intentName(i, lang, now.toLocalTime()), { find.aimAt(if (on) null else i) }, symbol = i.symbol, selected = on, role = Role.RadioButton)
        }
        // every kind under this one at any depth, near or not, as a plain list to sort (the owner, 6 November)
        if (every) IndexChip(stringResource(R.string.find_every_kind), { find.openEvery() }, symbol = "ui.list", ghost = true, role = Role.Button)
        // the kinds that also sell what it is named for and those that belong with it, brought up under the row
        if (kin > 0) IndexChip(stringResource(R.string.kind_see_also), { onOpen(!open) }, symbol = "ui-link", count = kin, selected = open || together, ghost = !open && !together, role = Role.Button)
        door?.let { (dest, title) -> IndexChip(stringResource(title), { nav.go(dest) }, symbol = "ui-corner-up-right", ghost = true, role = Role.Button) }
    }
    val padding = Modifier.padding(start = 16.dp, end = 16.dp, top = if (asked) 12.dp else 10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(SUBS_TAG)
            .animateContentSize()
            .semantics { if (more) customActions = listOf(CustomAccessibilityAction(action) { onOpen(!open); true }) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!pullable || !atTop()) return@awaitEachGesture
                    val unfolding = !unfolded
                    var pulled = 0f
                    // only a pull the row's way is its own, down to unfold and up to fold: a drag across goes on to
                    // the chips' scroll, any other to the list and the sheet
                    val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        if (over > 0f == unfolding) {
                            change.consume()
                            pulled = over
                        }
                    } ?: return@awaitEachGesture
                    var turned = false
                    verticalDrag(start.id) { change ->
                        // read before it is consumed: a consumed change has moved nothing
                        val moved = change.positionChange().y
                        change.consume()
                        if (turned) return@verticalDrag
                        pulled += moved
                        turned = if (unfolding) pulled >= reach else pulled <= -reach
                        if (turned) turn(unfolding)
                        scope.launch { give.snapTo(if (turned) 0f else pulled * GIVE) }
                    }
                    scope.launch { give.animateTo(0f) }
                }
            },
    ) {
        val giving = Modifier.graphicsLayer { translationY = give.value }
        if (open) {
            Column(Modifier.fillMaxWidth().then(giving).padding(bottom = SHADOW_ROOM)) {
                FlowRow(
                    Modifier.fillMaxWidth().then(padding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { chips() }
                val onKind: (CategoryNode) -> Unit = { find.enter(it.id, door = true) }
                val switch: (@Composable () -> Unit)? = onTogether?.let { { Together(together, it) } }
                if (also.isNotEmpty()) OtherKinds(R.string.kind_also_sold, ALSO_SOLD_TAG, also, lang, onKind, onHold, wrap = true, end = switch)
                if (see.isNotEmpty()) OtherKinds(R.string.kind_see_also, SEE_ALSO_TAG, see, lang, onKind, onHold, wrap = true, end = switch.takeIf { also.isEmpty() })
            }
        } else {
            Row(
                Modifier.fillMaxWidth().then(giving).horizontalScroll(scroll).then(padding).padding(bottom = SHADOW_ROOM),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { chips() }
        }
    }
}

/** How far the row is pulled, past the touch slop, to unfold or fold it. */
private val PULL = 20.dp

/** How much of the pull the row follows before it turns. */
private const val GIVE = 0.4f

/**
 * Room under the row's chips for their shadows, inside the row (the owner, 10 November: "The top pills bottom shadow is
 * capped?"). The row animates its size, and that clips whatever lies past its edge: a raised chip's shadow reaches 5 dp
 * below it, a chosen one's 10. The filter row under it gives the same room back ([FILTER_GAP]), so nothing moves.
 */
private val SHADOW_ROOM = 10.dp

/** Between the row of kinds and the filter row under it, and above the filter row where it comes first. */
private val FILTER_GAP = 10.dp

/**
 * A narrower kind as a chip: its disc in its colour as the map draws its pins, its name, how many are here, the disc
 * faded and no count where none is. A [ghost] is a shelf of its own the row leads into: only its dashed outline, its
 * count that shelf's. Held, it says what its kind is, as a card does, with the star of the home's own row.
 */
@Composable
private fun SubChip(symbol: String, label: String, count: Int, colour: Int, ghost: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = indexColors()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(38.dp)
            .pressable(interaction, depth = 0.05f)
            .then(if (ghost) Modifier.dashed(c) else Modifier.raised(CircleShape, c, y = 1.dp, blur = 4.dp))
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(start = 5.dp, end = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).alpha(if (count == 0) 1f - Palette.CLOSED_FADE else 1f).clip(CircleShape).background(cardBrush(Color(colour))),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(symbol, tint = Color.White, size = 15.dp)
        }
        Text(label, style = boardText(13.5f, 600, 17f), color = c.ink, maxLines = 1)
        if (count > 0) Text(count.toString(), style = boardText(12f, 500, 15f, tnum = true), color = c.faint, maxLines = 1)
    }
}

/**
 * The kinds near that also sell what the kind in view is named for (the owner, 6 November: "A bakery might sell
 * pastries"), the likeliest first, then the kinds that belong with it ("Link kinds that belong to each other": a
 * minigolf's golf courses), those near first: a row of chips each, under its caption, where none of the kind is near.
 */
private fun LazyListScope.otherKinds(
    also: List<Pair<CategoryNode, Int>>,
    see: List<Pair<CategoryNode, Int>>,
    lang: String,
    together: Boolean,
    onTogether: (() -> Unit)?,
    onHold: (CategoryNode) -> Unit,
    onKind: (CategoryNode) -> Unit,
) {
    val switch: (@Composable () -> Unit)? = onTogether?.let { { Together(together, it) } }
    if (also.isNotEmpty()) item(key = "also") { OtherKinds(R.string.kind_also_sold, ALSO_SOLD_TAG, also, lang, onKind, onHold, end = switch) }
    if (see.isNotEmpty()) item(key = "see") { OtherKinds(R.string.kind_see_also, SEE_ALSO_TAG, see, lang, onKind, onHold, end = switch.takeIf { also.isEmpty() }) }
}

/**
 * The switch that shows the places of the kinds named under it in this view too, with its own, each with its own pin
 * (the owner, 6 November: "Fill the view with them and save that as a combined view if desired?"): the banks in the cash
 * machines' view ("a bank is almost always an atm too"). At the end of the first caption's line, beside the chips it
 * fills the view with. Kept for the kind, so that its view opens so from then on, its title naming them ("ATM + Bank"),
 * until it is switched off.
 */
@Composable
private fun Together(on: Boolean, onClick: () -> Unit) {
    IndexToggle(stringResource(R.string.kind_together), "ui-plus", on, { onClick() }, Modifier.testTag(TOGETHER_TAG))
}

/**
 * Other kinds than the one in view as a [caption] and their chips, each with how many are around: one line, or with
 * [wrap] as many as show every chip; [end] at the end of the caption's line. A press opens its view, and back comes back
 * to this one. Its own count stays the kind's own: these say theirs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherKinds(
    @StringRes caption: Int,
    tag: String,
    kinds: List<Pair<CategoryNode, Int>>,
    lang: String,
    onKind: (CategoryNode) -> Unit,
    onHold: (CategoryNode) -> Unit,
    wrap: Boolean = false,
    end: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = if (end != null) 12.dp else 20.dp).testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Caps(stringResource(caption), Modifier.weight(1f))
            end?.invoke()
        }
        val chips: @Composable () -> Unit = { for ((k, n) in kinds) SubChip(k.symbol, k.name(lang), n, k.colour, onLongClick = { onHold(k) }) { onKind(k) } }
        if (wrap) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { chips() }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { chips() }
        }
    }
}

internal const val ALSO_SOLD_TAG = "kind-also-sold"
internal const val SEE_ALSO_TAG = "kind-see-also"
internal const val TOGETHER_TAG = "kind-together"

/** Waiting for the places: three quiet rows where they will be. */
@Composable
internal fun Looking() {
    val c = indexColors()
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        for (w in listOf(0.62f, 0.48f, 0.55f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).background(c.tray))
                Box(Modifier.size(38.dp).clip(CircleShape).background(c.tray))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth(w).height(11.dp).clip(CircleShape).background(c.tray))
                    Box(Modifier.fillMaxWidth(w * 0.7f).height(9.dp).clip(CircleShape).background(c.tray))
                }
            }
        }
    }
}
