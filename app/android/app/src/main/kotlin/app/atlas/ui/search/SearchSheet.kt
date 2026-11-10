package app.atlas.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.CategoryNode
import app.atlas.places.NodeKind
import app.atlas.search.Query
import app.atlas.search.SearchItem
import app.atlas.ui.home.HomeRow
import app.atlas.ui.kit.SHEET_GRABBER_ROOM
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetState
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.rememberSpeechInput
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The rests of the search's sheet: HIDDEN among them, for a pull that closes it. */
internal val SEARCH_SHEET_STOPS = setOf(SheetStop.HIDDEN, SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL)

/**
 * The one search over the map (decision 12), wherever it opens, in the Index's look (IndexLook.kt). Quiet by default
 * (the user, 3 November: "simple and clean by default… Everything can expand but it doesnt have to"): the moment as a
 * hero (the day's dial, what fits now), the ways to find of their own, what was looked for before, and every kind as
 * a collection of coloured cards. A card opens into its kind, a hero in the same colour and motif, its narrower kinds,
 * the filters most worth a tap and its places nearest first; every tag of them is a filter one tap further. The field
 * rests at the bottom as the home's bar does, under the thumb; typed words answer above it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SearchSheet(
    find: SearchSheetModel,
    typed: SearchViewModel,
    field: String,
    onField: (String) -> Unit,
    sheet: SheetState,
    nav: Navigator,
    lang: String,
    onPlace: (Place) -> Unit,
    /** The way back on the bands, as the system's back: out of typed words, up from a kind, closed at the top. */
    onBack: () -> Unit,
    /** Pulled all the way down: the search is closed, from whichever of its views. */
    onPulledAway: () -> Unit,
    /** The kind the typed words look among, by name, when they are narrowed to one. */
    narrowedTo: String? = null,
    /** The field took the keyboard: the host pulls the sheet up. */
    onTyping: () -> Unit = {},
    focus: FocusRequester = remember { FocusRequester() },
    peekHeight: Dp = 176.dp,
    halfFraction: Float = 0.52f,
    fullTopGap: Dp = 72.dp,
) {
    val state by find.state.collectAsStateWithLifecycle()
    val text by typed.state.collectAsStateWithLifecycle()
    // a pull all the way down closes it, with no arrow to find (the owner, 9 November: "Why does the pull up to part have
    // a back arrow? Where does it lead?", "Why does a pull down not close it?"); the grid has nothing to peek at
    SideEffect { sheet.skipsPeek = onGrid(state, field) }
    // a round walk found is shown on the map: a full sheet steps down to let it be seen
    val walk = state.walking?.walk
    LaunchedEffect(walk) { if (walk != null && sheet.stop == SheetStop.FULL) sheet.animateTo(SheetStop.HALF) }
    LaunchedEffect(sheet.stop) { if (sheet.stop == SheetStop.HIDDEN) onPulledAway() }
    val now = rememberNow(state.results)
    val services = LocalServices.current
    val recents by services.saved.recents.collectAsStateWithLifecycle()
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    var explained by remember { mutableStateOf<Explained?>(null) }
    var tuneOpen by rememberSaveable { mutableStateOf(false) }
    val c = indexColors()
    // the grid keeps where it was scrolled to while a kind is open
    val gridList = rememberLazyListState()
    val pane = paneOf(state, field)
    val filtering = field.isBlank() && state.node != null && state.filtersOpen
    // the field rests on the grid and over typed words; a kind is browsed, its filters have their own bar
    val docked = field.isNotBlank() || state.node == null
    val barShown = (docked || filtering) && sheet.stop != SheetStop.PEEK
    val room = if (barShown) BAR_ROOM else 28.dp
    val focusManager = LocalFocusManager.current
    // a hero wears its colour up to the sheet's top edge, the grabber light on it: a kind's always, the grid's till it
    // is scrolled past
    val gridTop by remember { derivedStateOf { gridList.firstVisibleItemIndex == 0 } }
    val heroUnder = inKind(state, field) || everyBanded(state, field) || onGrid(state, field) && gridTop
    val grabber by animateColorAsState(if (heroUnder) HERO_GRABBER else c.sheet.grabber, tween(320), label = "grabber")
    Box(Modifier.fillMaxSize()) {
        Sheet(sheet, peekHeight = peekHeight, halfFraction = halfFraction, fullTopGap = fullTopGap, look = c.sheet, grabberOver = true, grabber = grabber) {
            SharedTransitionLayout(Modifier.fillMaxWidth().weight(1f)) {
                val shared = this
                AnimatedContent(
                    targetState = pane,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { dive() },
                    contentAlignment = Alignment.TopCenter,
                    label = "search",
                    contentKey = { it.key },
                ) { p ->
                    val view = this
                    CompositionLocalProvider(LocalDive provides remember(shared, view) { Dive(shared, view) }) {
                        // every view keeps the grabber's room but those a hero heads, which reaches up under it
                        Box(Modifier.fillMaxSize().padding(top = if (inKind(p.s, p.field) || everyBanded(p.s, p.field) || onGrid(p.s, p.field)) 0.dp else SHEET_GRABBER_ROOM)) {
                            PaneView(p, text, typed, find, nav, lang, now, recents, lists, tuneOpen, gridList, room, onField, onPlace, onBack,
                                onTune = { tuneOpen = !tuneOpen },
                                onClear = { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.clearRecents() } } },
                            ) { explained = it }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            barShown,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 },
            label = "bar",
        ) {
            if (filtering) FilterBar(state, find)
            else Dock(field, narrowedTo, onField, focus, onTyping, onSearch = { typed.remember(field, null); searchKey(field, text, find, nav, onPlace) { focusManager.clearFocus(); onField("") } }, onNear = { nav.go(Dest.Query()) })
        }
    }
    explained?.let { e ->
        val id = HomeRow.idOf(e.node)
        ExplainDialog(
            e, lang, nav, onAll = { find.enter(e.node.id) }, onDismiss = { explained = null },
            inRow = (id in state.prefs.row).takeIf { HomeRow.resolve(id) != null },
            onStar = { find.star(id, it) },
        )
    }
}

/** The view [p] stands for: typed words, the grid, a kind's filters, or the kind. */
@Composable
private fun PaneView(
    p: Pane,
    text: SearchState,
    typed: SearchViewModel,
    find: SearchSheetModel,
    nav: Navigator,
    lang: String,
    now: LocalDateTime,
    recents: List<Recent>,
    lists: List<SavedList>,
    tuneOpen: Boolean,
    gridList: LazyListState,
    room: Dp,
    onField: (String) -> Unit,
    onPlace: (Place) -> Unit,
    onBack: () -> Unit,
    onTune: () -> Unit,
    onClear: () -> Unit,
    onExplain: (Explained) -> Unit,
) {
    val s = p.s
    val focusManager = LocalFocusManager.current
    when {
        p.field.isNotBlank() -> LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(top = 4.dp, bottom = room)) {
            // what the words found opens here, over the map: the words step aside for it, and come back on the way back
            typedPane(text, typed, p.field, s.centre, s.prefs.mode, lang, now, nav, onPlace, onOpen = { item ->
                text.results?.let { r ->
                    if (find.open(item, r)) {
                        focusManager.clearFocus()
                        onField("")
                    }
                }
            })
        }
        s.asked is Asked.Outing -> OutingList(s.asked, s, find, nav, lang, room, onPlace)
        s.node == null && s.asked == null && s.every != null -> EveryKindList(s.every, find, lang, room, onBack, onExplain, row = s.prefs.row)
        s.node == null -> LazyColumn(Modifier.fillMaxSize().testTag(GRID_TAG), state = gridList, contentPadding = PaddingValues(bottom = room)) {
            gridPane(s, find, nav, lang, now, recents, lists, tuneOpen, onTune, onText = onField, onPlace = onPlace, onClear = onClear, onExplain = onExplain)
        }
        s.filtersOpen -> {
            val unfolded = remember { mutableStateListOf<String>() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = room)) { filtersPane(s, find, lang, now, unfolded) }
        }
        else -> KindList(s, find, nav, lang, now, room, remember(lists) { keptColours(lists) }, onPlace, onBack, onExplain)
    }
}

/**
 * The keyboard's search key takes the best answer to [field] as found: a place opens, a way asked for opens the route
 * planner, what opens over the map opens there ([SearchSheetModel.open]: a kind, what the words ask, how many) and
 * [onOpened] then clears the words.
 */
private fun searchKey(field: String, text: SearchState, find: SearchSheetModel, nav: Navigator, onPlace: (Place) -> Unit, onOpened: () -> Unit) {
    val r = text.results?.takeIf { it.text.trim() == field.trim() } ?: return
    when (val top = r.items.firstOrNull { it !is SearchItem.Recent }) {
        null -> Unit
        is SearchItem.PlaceResult -> onPlace(top.hit.place)
        is SearchItem.Route -> nav.go(routeTo(top))
        else -> if (find.open(top, r)) onOpened()
    }
}

/** What a long press asked about: a kind, and the place it was held on, with how sure its hours are. */
internal data class Explained(val node: CategoryNode, val place: Place? = null, val brandHours: Boolean = false, val kindHours: Boolean = false, val stale: Boolean = false)

/** One of the sheet's views, and what it shows: the same [key] is the same view, a deeper [depth] a dive into it. */
@Immutable
private data class Pane(val key: String, val depth: Int, val s: SearchSheetState, val field: String)

/** The grid's view, its hero for now first in it ([PaneView]'s order). */
private fun onGrid(s: SearchSheetState, field: String): Boolean = field.isBlank() && s.asked !is Asked.Outing && s.node == null && !onEvery(s, field)

/** The plain list of every kind ([EveryKindList]). */
private fun onEvery(s: SearchSheetState, field: String): Boolean = field.isBlank() && s.asked == null && s.node == null && s.every != null

/** The list of every kind under a kind, that kind's band the sheet's top as in its own view. */
private fun everyBanded(s: SearchSheetState, field: String): Boolean = onEvery(s, field) && s.every?.scope != null

/** A kind's own view, its hero the sheet's top ([PaneView]'s order). */
private fun inKind(s: SearchSheetState, field: String): Boolean = field.isBlank() && s.asked == null && s.node != null && !s.filtersOpen

private fun paneOf(s: SearchSheetState, field: String): Pane {
    val node = s.node
    val asked = s.asked
    return when {
        field.isNotBlank() -> Pane(TYPED, 0, s, field)
        asked is Asked.Outing -> Pane("outing:${asked.text}", 1, s, field)
        asked != null && !s.filtersOpen -> Pane("asked:${asked.text}:${s.within?.id.orEmpty()}", 1 + (s.within?.depth ?: 0), s, field)
        // the list of every kind is as deep as where it was opened, the grid or a kind: a kind on it is a dive further
        node == null && asked == null && s.every != null -> Pane(EVERY + s.every.scope?.id.orEmpty(), 1 + (s.every.scope?.depth ?: 0), s, field)
        node == null -> Pane(GRID, 0, s, field)
        s.filtersOpen -> Pane("filters:${node.id}", FILTERS_DEPTH, s, field)
        else -> Pane("kind:${node.id}:${s.intent?.id.orEmpty()}", 1 + node.depth, s, field)
    }
}

/** Into a kind the view comes up from a little further off and the one left goes past; out of it, the other way. */
private fun AnimatedContentTransitionScope<Pane>.dive(): ContentTransform {
    if (initialState.key == TYPED || targetState.key == TYPED) return fadeIn(tween(200)) togetherWith fadeOut(tween(140))
    // a shelf's card grows into its hero and back (Modifier.dive): around it the views only fade
    fun shelf(p: Pane) = p.s.node?.kind == NodeKind.SHELF && p.s.intent == null && !p.s.filtersOpen
    if (initialState.key == GRID && shelf(targetState) || targetState.key == GRID && shelf(initialState)) {
        return (fadeIn(tween(320, delayMillis = 80)) togetherWith fadeOut(tween(220))).using(SizeTransform(clip = false))
    }
    // as deep as where it goes, a view dives in, but out of the list of every kind to the kind it was opened from
    val deeper = targetState.depth > initialState.depth || targetState.depth == initialState.depth && !initialState.key.startsWith(EVERY)
    val top = TransformOrigin(0.5f, 0f)
    val enter = fadeIn(tween(280, delayMillis = 70)) +
        scaleIn(tween(400, easing = FastOutSlowInEasing), initialScale = if (deeper) 0.9f else 1.05f, transformOrigin = top)
    val exit = fadeOut(tween(180)) + scaleOut(tween(320, easing = FastOutSlowInEasing), targetScale = if (deeper) 1.05f else 0.92f, transformOrigin = top)
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}

// --------------------------------------------------------------------------------------------------------------- bars

/** The field at the bottom, as the home's bar: the words, then A near B and the voice, or the words' clear. */
@Composable
private fun Dock(
    field: String,
    inside: String?,
    onField: (String) -> Unit,
    focus: FocusRequester,
    onTyping: () -> Unit,
    onSearch: () -> Unit,
    onNear: () -> Unit,
) {
    val c = indexColors()
    val label = stringResource(R.string.search_title)
    val mic = rememberSpeechInput(onText = onField)
    val words = boardText(16.5f, 550, 21f)
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp)
            .height(58.dp)
            .cssShadow(CircleShape, y = 10.dp, blur = 30.dp, colour = c.shadow)
            .clip(CircleShape)
            .background(c.card)
            .padding(start = 18.dp, end = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("main.search", tint = c.ink, size = 20.dp)
        BasicTextField(
            value = field,
            onValueChange = onField,
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { if (it.isFocused) onTyping() }.semantics { contentDescription = label },
            singleLine = true,
            textStyle = words.copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (field.isEmpty()) {
                        Text(
                            inside?.let { stringResource(R.string.find_hint_in, it) } ?: stringResource(R.string.home_search_hint),
                            style = words, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (field.isNotEmpty()) {
            Disc("ui.close", stringResource(R.string.find_clear), c.tray, c.ink) { onField("") }
        } else {
            Disc("main.near", stringResource(R.string.query_title), c.tray, c.ink, onNear)
            Disc("main.mic", stringResource(R.string.kit_mic), c.ink, c.onInk, mic)
        }
    }
}

/** A round key of the bars. */
@Composable
private fun Disc(symbol: String, description: String, fill: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(fill).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = tint, size = 19.dp) }
}

/** The filters' bar in the field's place: show the places they keep, and clear them once any is chosen. */
@Composable
private fun FilterBar(s: SearchSheetState, find: SearchSheetModel) {
    val c = indexColors()
    val kept = s.results?.facets?.kept?.size ?: 0
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp).height(54.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!s.selection.isEmpty) {
            Box(
                Modifier.fillMaxHeight().raised(CircleShape, c, y = 8.dp, blur = 24.dp).clickable(role = Role.Button, onClick = find::clearFilters).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.find_filters_clear), style = boardText(14.5f, 600, 18f), color = c.ink, maxLines = 1) }
        }
        Box(
            Modifier.fillMaxHeight().cssShadow(CircleShape, y = 10.dp, blur = 26.dp, colour = c.shadow).clip(CircleShape).background(c.ink)
                .clickable(role = Role.Button) { find.showFilters(false) }.padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.find_filters_done, kept.toString()), style = boardText(14.5f, 650, 18f), color = c.onInk, maxLines = 1) }
    }
}


/** The test tags of a place's row and a brand's deck. */
internal const val PLACE_TAG = "place"
internal const val DECK_TAG = "deck"
internal const val GRID_TAG = "grid-list"

/** The test tag of the list inside a kind, what words asked or a brand's page. */
internal const val KIND_LIST_TAG = "kind-list"

private const val TYPED = "typed"
private const val GRID = "grid"
private const val EVERY = "every:"
private const val FILTERS_DEPTH = 50

/** Room under the lists for the bar resting over them. */
private val BAR_ROOM = 104.dp

/** Where a row's words start: past its disc. */
internal val ROW_TEXT_START = 68.dp

/** The keys' colours of a way on from typed words: a question or plan, a query, goods, a point. */
internal const val ASK = 0xFF7B4DFF.toInt()
internal const val NEAR = 0xFF2E5BFF.toInt()
internal const val GOODS = 0xFFB8461A.toInt()
internal const val POINT = 0xFF6B7282.toInt()

internal const val PICKS = 3

/** A kind is about to close when the last of it closes within this many minutes, with at least [MIN_SPARE] left once there. */
internal const val CLOSING_WINDOW = 90
internal const val MIN_SPARE = 5

/** The services' catch-all ("Other"): it names no kind of its own, so it is not on a shelf by what it is doing. */
internal const val CATCH_ALL = "other"

/** At most this many cards on the closing shelf, and tokens of the kinds there at any hour. */
internal const val SHELF = 8
internal const val TOKENS = 6

/** The night, for the deck of the kinds asleep: from dusk till morning, they wake before noon. */
internal const val DUSK = 18
internal const val MORNING = 5
internal const val NOON = 12

/** A row says how a place stands once there when it closes within this many minutes of arriving, or opens within the wait. */
internal const val ARRIVE_SOON = 30
internal const val ARRIVE_WAIT = 60
internal const val RECENTS = 10
internal const val CHIPS = 6

/** Stands for the reach in the sentence's "within %s", to find which side of it the word goes. */
internal const val MARK = "⁣"

/** How much of its colour a row's hours keep when they were not checked for long. */
internal const val STALE_ALPHA = 0.62f

/** A facet's values shown before the rest fold behind "+n"; a price or check date as segments up to this many. */
internal const val FOLD = 8
internal const val SEGMENTS = 4
