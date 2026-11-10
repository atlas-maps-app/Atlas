package app.atlas.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.CoordinateParser
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.search.CategoryPulse
import app.atlas.search.FacetSection
import app.atlas.search.FitReason
import app.atlas.search.Intent
import app.atlas.search.Intents
import app.atlas.search.Relevance
import app.atlas.search.TellKind
import app.atlas.search.TravelMode
import app.atlas.services.SettingsKeys
import app.atlas.ui.eat.Meal
import app.atlas.ui.eat.mealName
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.guessedTime
import app.atlas.ui.kit.openTotal
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The grid, its form following what its kinds are doing (the user, 3 November, of concept 6 and concept 5 slid up):
 * the one hero for now (the wheel the last of its picks), what is yours (the lists kept, the searches made), the kinds
 * about to close on a shelf with the time to spare once there, every kind as a card in its colour, the chains around by
 * their signs, those there at any hour as small tokens, those asleep as a deck, and last the questions about places
 * around the map cannot answer yet. The ways to find that were keys of their own are where they are used (the owner, 4
 * November: "Ask can go, where to, brands, map quests and lists needs to be better integrated"): what Ask did, the
 * field does.
 */
internal fun LazyListScope.gridPane(
    s: SearchSheetState,
    find: SearchSheetModel,
    nav: Navigator,
    lang: String,
    now: LocalDateTime,
    recents: List<Recent>,
    lists: List<SavedList>,
    tuneOpen: Boolean,
    onTune: () -> Unit,
    onText: (String) -> Unit,
    onPlace: (Place) -> Unit,
    onClear: () -> Unit,
    onExplain: (Explained) -> Unit,
) {
    // where the phone holds no places around, the cards cannot tell what is near: their names only
    val known = s.gridOrigin != null
    val smart = s.prefs.smart && known
    val lead = s.now.firstOrNull()?.takeIf { known }
    val states = if (smart) kindStates(s, now, lead?.node?.id) else NO_STATES
    val wheel: () -> Unit = { nav.go(Dest.Where) }
    item(key = "hero") { if (lead != null) LeadHero(lead, s, find, lang, now, wheel) else MomentHero(s, find, lang, now, wheel) }
    val kept = lists.filter { it.places.isNotEmpty() }
    if (kept.isNotEmpty() || recents.isNotEmpty()) item(key = "yours") {
        Yours(kept, recents.take(RECENTS), onList = { nav.go(Dest.Lists(it.id)) }, onText, onPlace, onPoint = { p -> nav.go(Dest.WhatsHere(p.lat, p.lon, go = true)) }, onClear = onClear)
    }
    if (states.closing.isNotEmpty()) item(key = "closing") { ClosingShelf(states.closing, s.prefs.mode, lang, find, onExplain) }
    item(key = "kinds") { KindsHead(s, find, tuneOpen, onTune) }
    val shelves = orderedShelves(s).filter { !(s.prefs.nearbyOnly && known) || (s.pulses[it.id]?.count ?: 0) > 0 }
    val (everything, kinds) = shelves.partition { it.category.family == CategoryTree.OSM }
    // the kind that matters most now on a card of its own, then two by two; under a lead hero, which says it, all two by two
    val rows = if (kinds.isEmpty() || lead != null) kinds.chunked(2) else listOf(kinds.take(1)) + kinds.drop(1).chunked(2)
    rows.forEachIndexed { i, row ->
        val height = when {
            i == 0 && lead == null -> 156.dp
            row.size == 1 -> 112.dp
            else -> 136.dp
        }
        item(key = "cards:" + row.first().id) { CardRow(row, s, lang, smart, known, height, find, onExplain) }
    }
    // the questions on the grid's last line read every fact of the places near: they are counted once the grid is
    // scrolled past the kinds towards them, so the line is there by the time it comes into view
    val towardsQuests: @Composable () -> Unit = { if (known) LaunchedEffect(s.quests == null) { if (s.quests == null) find.countQuests() } }
    if (s.brands.isNotEmpty()) item(key = "brands") { towardsQuests(); BrandsShelf(s.brands, lang, onBrand = find::chain, onAll = { nav.go(Dest.Brands()) }) }
    if (states.anytime.isNotEmpty()) item(key = "anytime") { towardsQuests(); AnyTime(states.anytime, lang, find, onExplain) }
    if (states.asleep.isNotEmpty()) item(key = "asleep") { towardsQuests(); Asleep(states.asleep, lang, now, find, onExplain) }
    for (shelf in everything) item(key = "cards:" + shelf.id) { towardsQuests(); CardRow(listOf(shelf), s, lang, smart, known, 96.dp, find, onExplain) }
    if (known) item(key = "quests") {
        towardsQuests()
        s.quests?.takeIf { it > 0 }?.let { n -> QuestsLine(n) { nav.go(Dest.Quests(s.centre.lat, s.centre.lon)) } }
    }
}

/** The part of the day, as the sheet's title says it. */
private fun daypart(hour: Int): Daypart = when (hour) {
    in 5..10 -> Daypart.MORNING
    in 11..13 -> Daypart.MIDDAY
    in 14..17 -> Daypart.AFTERNOON
    in 18..22 -> Daypart.EVENING
    else -> Daypart.NIGHT
}

/** A part of the day: its title and its sky, darkest at the top left. */
private enum class Daypart(val title: Int, val sky: List<Long>, val stars: Boolean) {
    MORNING(R.string.find_part_morning, listOf(0xFF2A3B63, 0xFF5E5A8C, 0xFFB97A72), false),
    MIDDAY(R.string.find_part_midday, listOf(0xFF173F73, 0xFF2766A8, 0xFF4E92CC), false),
    AFTERNOON(R.string.find_part_afternoon, listOf(0xFF3E2B22, 0xFF7C4A2A, 0xFFC0803A), false),
    EVENING(R.string.find_part_evening, listOf(0xFF17142E, 0xFF3D2652, 0xFF94455E), true),
    NIGHT(R.string.find_part_night, listOf(0xFF05081A, 0xFF0D1430, 0xFF1A2550), true),
}

/**
 * The moment as the sheet's band where no place around is known: the part of the day on its own sky ("This evening",
 * the date over it) as the sheet's top, out to its edges, then the wheel and what fits now as chips, each a press into
 * its kind.
 */
@Composable
private fun MomentHero(s: SearchSheetState, find: SearchSheetModel, lang: String, now: LocalDateTime, onWheel: () -> Unit) {
    val part = daypart(now.hour)
    val picks = s.now.take(PICKS)
    val sky = remember(part) { part.sky.map { Color(it) } }
    val date = remember(now.toLocalDate()) { now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(sky, start = Offset.Zero, end = Offset.Infinite))
            .drawBehind {
                // the glow low on the horizon, and the first stars
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width, size.height), radius = size.width * 0.7f),
                    radius = size.width * 0.7f, center = Offset(size.width, size.height),
                )
                if (part.stars) {
                    for ((x, y, r) in STARS) drawCircle(Color.White.copy(alpha = 0.55f), r * density, Offset(x * size.width, y * size.height))
                }
            }
            .padding(top = 16.dp, bottom = 14.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Caps(date, colour = Color.White.copy(alpha = 0.66f))
            Text(stringResource(part.title), style = indexTitle(22f).onCard(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Picks(picks, find, lang, now.toLocalTime(), onWheel)
    }
}

private val STARS = listOf(Triple(0.52f, 0.08f, 1.1f), Triple(0.63f, 0.17f, 0.7f), Triple(0.71f, 0.06f, 0.8f), Triple(0.79f, 0.2f, 1.2f))

/**
 * The one hero for now: what fits the moment most, as its kind's band at the sheet's top, out to its edges (the user,
 * 4 November, of the card it was: "either no card or a design more like a concept", then of the hero it became: "too
 * much space wasted"): the moment and why it fits, the kind's name, how many are open and how far the nearest is, then
 * the wheel for when none of the picks decides it and the moment's other picks as chips. Pressed, it dives into its
 * kind.
 */
@Composable
private fun LeadHero(lead: NowItem, s: SearchSheetState, find: SearchSheetModel, lang: String, now: LocalDateTime, onWheel: () -> Unit) {
    val node = lead.node
    val art = remember(node.id) { artOf(node) }
    val p = lead.pulse
    val open = p?.takeIf { it.open > 0 }?.let { "${openTotal(it.open, it.unconfirmed)} ${stringResource(R.string.find_word_open)}" }
    val nearest = p?.nearest?.let { r -> r.name(lang) ?: r.place.name() }?.takeIf { it.isNotBlank() }
    val minutes = p?.minutes
    val away = if (nearest != null && minutes != null) stringResource(R.string.find_away, nearest, minutes) else null
    // "for this time" says nothing the hour does not
    val reason = lead.reason.takeIf { it != FitReason.TIME }?.let { why(it) }
    val others = s.now.drop(1).take(PICKS - 1)
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .dive(if (lead.intent == null) diveKey(node) else null)
            .bandSurface(Color(node.colour), art)
            .clickable(interaction, null, role = Role.Button) { if (lead.intent != null) find.enter(lead.intent) else find.enter(node.id) }
            .padding(top = 16.dp, bottom = 14.dp),
    ) {
        // what it says gives under a finger: the band itself keeps to the sheet's edges
        Column(Modifier.fillMaxWidth().pressable(interaction, depth = 0.02f).padding(horizontal = 16.dp)) {
            Caps(listOfNotNull(stringResource(daypart(now.hour).title), clock(now), reason).joinToString(" · "), colour = Color.White.copy(alpha = 0.78f))
            Text(lead.intent?.let { intentName(it, lang, now.toLocalTime()) } ?: node.name(lang), style = indexTitle(22f).onCard(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(open, away).takeIf { it.isNotEmpty() }?.let { said ->
                Text(said.joinToString(" · "), style = boardText(13f, 500, 17f).onCard().copy(color = Color.White.copy(alpha = 0.9f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Picks(others, find, lang, now.toLocalTime(), onWheel)
    }
}

/**
 * What [intent] is called at [at]: the time to eat by its meal, in the Eat screen's own words, Lunch or Dinner ([Meal];
 * the owner, 10 November, of the Eat card beside the shelf of that name: "And yes?"), and by its own name between
 * meals; any other by its name.
 */
@Composable
internal fun intentName(intent: Intent, lang: String, at: LocalTime): String {
    val meal = Meal.at(at)
    return if (intent.id == Intents.EAT && (meal == Meal.LUNCH || meal == Meal.DINNER)) stringResource(mealName(meal)) else intent.name(lang)
}

/** The wheel, then the moment's picks as chips, under a band's words, in a row that scrolls to the sheet's edges. */
@Composable
private fun Picks(picks: List<NowItem>, find: SearchSheetModel, lang: String, at: LocalTime, onWheel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WheelKey(onWheel)
        for (n in picks) PickWord(n, lang, at) { if (n.intent != null) find.enter(n.intent) else find.enter(n.node.id) }
    }
}

/**
 * The way to the wheel, for when none of the picks decides it: the wheel itself, small, in its own colours on a chip's
 * solid disc, first in the row, where a band's picture never lies (the owner, 9 November: "The spinning wheel button is
 * almost not visible": a white outline and white words, last in the row, off the screen's edge and over the plate).
 */
@Composable
private fun WheelKey(onClick: () -> Unit) {
    val said = stringResource(R.string.where_title)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(38.dp)
            .pressable(interaction, depth = 0.05f)
            .clip(CircleShape)
            .background(indexColors().card)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).drawBehind { smallWheel() })
    }
}

/**
 * One of the moment's picks as a chip on a band: its kind's disc, its name, and what it says now or, where nothing
 * around is known, why it fits the moment ("out of the rain").
 */
@Composable
private fun PickWord(n: NowItem, lang: String, at: LocalTime, onClick: () -> Unit) {
    val c = indexColors()
    val figure = n.pulse?.let { tellFigure(it) }
    val note = figure?.shown ?: n.reason.takeIf { it != FitReason.TIME }?.let { why(it) }
    // out of doors the place itself says what a count would not: "A walk  Westerpark 7′"
    val place = n.pulse?.takeIf { n.intent?.outdoors == true && it.minutes != null }?.nearest?.let { r -> r.name(lang) ?: r.place.name() }?.takeIf { it.isNotBlank() }
    val aloud = place?.let { stringResource(R.string.find_away, it, n.pulse?.minutes ?: 0) } ?: figure?.aloud ?: note
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(38.dp)
            .pressable(interaction, depth = 0.05f)
            .clip(CircleShape)
            .background(c.card)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .padding(start = 5.dp, end = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(cardBrush(Color(n.node.colour))), contentAlignment = Alignment.Center) {
            Symbol(n.intent?.symbol ?: n.node.symbol, tint = Color.White, size = 15.dp)
        }
        Text(n.intent?.let { intentName(it, lang, at) } ?: n.node.name(lang), style = boardText(13.5f, 650, 17f), color = c.ink, maxLines = 1)
        if (note != null || place != null) {
            Row(Modifier.clearAndSetSemantics { aloud?.let { contentDescription = it } }, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                place?.let { Text(it, Modifier.widthIn(max = PICK_PLACE_WIDTH), style = boardText(12.5f, 500, 16f), color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                note?.let { Text(it, style = boardText(12.5f, 500, 16f, tnum = true), color = c.soft, maxLines = 1) }
            }
        }
    }
}

/**
 * A figure with its word in small capitals, the word first where it reads first ("till 01:00"). A new figure rolls in
 * over the old one, from below as it grows and from above as a word narrows it.
 */
@Composable
internal fun Figure(f: TellFigure, colour: Color, size: Float) {
    val word = @Composable {
        if (f.word.isNotEmpty()) Text(f.word.uppercase(Locale.getDefault()), Modifier.padding(bottom = 2.dp), style = INDEX_CAPS.copy(fontSize = 8.sp, lineHeight = 10.sp), color = colour.copy(alpha = 0.66f), maxLines = 1)
    }
    Row(f.said?.let { said -> Modifier.clearAndSetSemantics { contentDescription = said } } ?: Modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (f.before) word()
        AnimatedContent(
            f.figure,
            transitionSpec = {
                val up = rolled(targetState) >= rolled(initialState)
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut()) using SizeTransform(clip = true)
            },
            label = "figure",
        ) { Text(it, style = indexFigure(size), color = colour, maxLines = 1) }
        if (!f.before) word()
    }
}

/**
 * What is yours, a tap away: the lists of places kept, each with how many it holds (pressed, it opens on its notebook),
 * then what was looked for before (a coordinate or plus code opens what is there). Clearing clears the searches only.
 */
@Composable
private fun Yours(
    lists: List<SavedList>,
    recents: List<Recent>,
    onList: (SavedList) -> Unit,
    onText: (String) -> Unit,
    onPlace: (Place) -> Unit,
    onPoint: (LatLon) -> Unit,
    onClear: () -> Unit,
) {
    val c = indexColors()
    Column(Modifier.fillMaxWidth().padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.height(30.dp).padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Caps(stringResource(R.string.tab_mine), Modifier.weight(1f))
            if (recents.isNotEmpty()) Caps(
                stringResource(R.string.find_clear_recent),
                Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClear).padding(horizontal = 10.dp, vertical = 6.dp),
                colour = c.soft,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (l in lists) {
                YoursChip({ onList(l) }) {
                    Symbol("ui.bookmark", tint = Color(l.colour), size = 14.dp)
                    Text(l.name ?: stringResource(R.string.lists_default), style = boardText(13f, 600, 17f), color = c.ink, maxLines = 1)
                    Text("${l.places.size}", style = boardText(12.5f, 500, 16f, tnum = true), color = c.soft, maxLines = 1)
                }
            }
            for (r in recents) {
                val point = remember(r.text) { CoordinateParser.parse(r.text) }
                val label = r.text.ifEmpty { r.place?.name() ?: "" }
                YoursChip({
                    when {
                        r.place != null -> onPlace(r.place)
                        point != null -> onPoint(point)
                        else -> onText(r.text)
                    }
                }) {
                    Symbol(if (r.place != null) "ui-map-pin" else "ui.clock.3", tint = c.faint, size = 14.dp)
                    Text(label, style = boardText(13f, 550, 17f, family = if (point != null) GEIST_MONO else GEIST), color = c.ink, maxLines = 1)
                }
            }
        }
    }
}

/** One of [Yours]'s chips, raised off the paper. */
@Composable
private fun YoursChip(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .height(38.dp)
            .raised(CircleShape, indexColors(), y = 2.dp, blur = 6.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** "Every kind", and folded under its key how the cards are shown (and how the minutes are counted). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindsHead(s: SearchSheetState, find: SearchSheetModel, open: Boolean, onTune: () -> Unit) {
    val c = indexColors()
    val p = s.prefs
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 30.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.find_every_kind), Modifier.weight(1f), style = indexTitle(20f), color = c.ink, maxLines = 1)
            // the plain list of every kind around, with how many there are; the cards under the head are the families
            val kinds = s.kinds.size
            if (kinds > 0) {
                IndexChip(
                    pluralStringResource(R.plurals.find_kinds, kinds, kinds), { find.openEvery() }, Modifier.padding(end = 8.dp),
                    symbol = "ui.list", ghost = true, role = Role.Button,
                )
            }
            IndexRound(if (open) "ui.chevron-up" else "ui-sliders", stringResource(R.string.find_tune), onTune, size = 36.dp, glyph = 16.dp)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            FlowRow(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val next = TravelMode.entries[(p.mode.ordinal + 1) % TravelMode.entries.size]
                IndexChip(modeName(p.mode), { find.setMode(next) }, symbol = p.mode.symbol, role = Role.Button)
                IndexToggle(stringResource(R.string.find_smart), "ui-sparkles", p.smart, { find.setPref(SettingsKeys.SEARCH_SMART, it) })
                IndexToggle(stringResource(R.string.find_nearby), "ui-compass", p.nearbyOnly, { find.setPref(SettingsKeys.SEARCH_NEARBY, it) })
                IndexToggle(stringResource(R.string.find_fixed), "ui-lock", p.fixed, { find.setPref(SettingsKeys.SEARCH_FIXED, it) })
            }
        }
    }
}

/** A row of the collection: one card, or two side by side. */
@Composable
private fun CardRow(row: List<CategoryNode>, s: SearchSheetState, lang: String, smart: Boolean, known: Boolean, height: Dp, find: SearchSheetModel, onExplain: (Explained) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (shelf in row) {
            val pulse = s.pulses[shelf.id]?.takeIf { known }
            CategoryCard(
                shelf, shelf.name(lang), if (smart) pulse?.let { tellFigure(it) } else null, faded = pulse != null && pulse.count == 0,
                onClick = { find.enter(shelf.id) }, onLongClick = { onExplain(Explained(shelf)) }, modifier = Modifier.weight(1f), height = height,
            )
        }
    }
}

/** The kinds near by what they are doing now: about to close, there at any hour, asleep till they open. */
private class KindStates(val closing: List<Closing>, val anytime: List<CategoryPulse>, val asleep: List<CategoryPulse>)

/** A kind about to close: when the last of its open places closes, and the minutes left once at the nearest. */
private class Closing(val pulse: CategoryPulse, val closes: LocalDateTime, val spare: Int)

private val NO_STATES = KindStates(emptyList(), emptyList(), emptyList())

/** Sorts the kinds near into [KindStates], in the moment's order; [lead] (the hero's) stays out, so no card is on the grid twice. */
private fun kindStates(s: SearchSheetState, now: LocalDateTime, lead: String?): KindStates {
    val rank = Relevance.order(Categories.leaves().map { it.id }, s.moment).withIndex().associate { it.value to it.index }
    val kinds = s.pulses.values
        .filter { it.node.kind == NodeKind.CATEGORY && it.count > 0 && it.node.id != lead && it.node.category.id != CATCH_ALL }
        .sortedBy { rank[it.node.category.id] ?: Int.MAX_VALUE }
    val closing = kinds.mapNotNull { p ->
        val closes = p.until?.takeIf { p.open > 0 && p.endless == 0 } ?: return@mapNotNull null
        val left = Duration.between(now, closes).toMinutes().toInt()
        val spare = left - (p.minutes ?: 0)
        if (left > CLOSING_WINDOW || spare < MIN_SPARE) null else Closing(p, closes, spare)
    }.sortedBy { it.closes }.take(SHELF)
    val anytime = kinds.filter { it.open > 0 && it.endless == it.open && it.minutes != null }.take(TOKENS)
    val asleep = kinds.filter { it.open == 0 && it.opensAt != null }.sortedBy { it.opensAt }
    return KindStates(closing, anytime, asleep)
}

/** A section of the grid: its name, a line under it, and how many it holds, or a word to tap on the right ([action]). */
@Composable
internal fun SectionHead(title: String, line: String?, count: Int?, action: Pair<String, () -> Unit>? = null) {
    val c = indexColors()
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 32.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = indexTitle(20f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line?.let { Text(it, style = INDEX_META, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        when {
            action != null -> Caps(action.first, Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = action.second).padding(horizontal = 10.dp, vertical = 6.dp), colour = c.soft)
            count != null -> Text(count.toString(), Modifier.padding(end = 8.dp, bottom = 1.dp), style = indexFigure(15f, 650), color = c.faint, maxLines = 1)
        }
    }
}

/** The kinds about to close, the soonest first, on a shelf: each card says when, and how long is left once there. */
@Composable
private fun ClosingShelf(closing: List<Closing>, mode: TravelMode, lang: String, find: SearchSheetModel, onExplain: (Explained) -> Unit) {
    // one card across the sheet, two side by side, more on a shelf that scrolls (and says how many)
    val few = closing.size <= 2
    Column(Modifier.fillMaxWidth()) {
        val first = closing.first()
        SectionHead(stringResource(R.string.find_closing), stringResource(R.string.find_closing_line, guessedTime(untilClock(first.closes), first.pulse.untilGuessed)), closing.size.takeIf { !few })
        Row(
            Modifier.fillMaxWidth().then(if (few) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (k in closing) key(k.pulse.node.id) {
                ClosingCard(
                    k, mode, lang, if (few) Modifier.weight(1f) else Modifier.width(158.dp), wide = closing.size == 1,
                    onClick = { find.enter(k.pulse.node.id) }, onLongClick = { onExplain(Explained(k.pulse.node)) },
                )
            }
        }
    }
}

/**
 * A kind about to close as a card: the minutes to its nearest on a chip, its name, when the last of them closes, and a
 * fuse burnt down to the minutes to spare once there; [wide] when it is the only one, across the sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClosingCard(k: Closing, mode: TravelMode, lang: String, modifier: Modifier, wide: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val node = k.pulse.node
    val art = remember(node.id) { artOf(node) }
    val name = node.name(lang)
    val closes = stringResource(R.string.find_closes_at, guessedTime(untilClock(k.closes), k.pulse.untilGuessed))
    val spare = stringResource(R.string.find_spare, k.spare)
    val reach = k.pulse.minutes?.let { primeMinutes(it) }
    val reachSaid = k.pulse.minutes?.let { spokenMinutes(it) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .height(if (wide) 140.dp else 178.dp)
            .dive(diveKey(node))
            .pressable(interaction)
            .cardSurface(Color(node.colour), art, RoundedCornerShape(24.dp))
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .clearAndSetSemantics {
                text = AnnotatedString(listOfNotNull(name, closes, reachSaid, spare).joinToString(", "))
                role = Role.Button
                onClick { onClick(); true }
                onLongClick { onLongClick(); true }
            }
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
    ) {
        reach?.let { CardWord(mode.symbol, it, Color(node.colour)) }
        Spacer(Modifier.weight(1f))
        Text(name, style = indexTitle(18f).onCard(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(closes, Modifier.padding(top = 1.dp), style = boardText(12.5f, 550, 16f), color = Color.White.copy(alpha = 0.86f), maxLines = 1)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fuse(k.spare.toFloat() / CLOSING_WINDOW, Modifier.weight(1f))
            Text(spare, style = boardText(11f, 650, 14f, tnum = true), color = Color.White, maxLines = 1)
        }
    }
}

/** What is left of the time, as a fuse: the lit part, as long as there is time to spare, and the spark where it burns. */
@Composable
private fun Fuse(left: Float, modifier: Modifier = Modifier) {
    val glow by rememberInfiniteTransition(label = "fuse").animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "spark")
    BoxWithConstraints(modifier.height(12.dp), contentAlignment = Alignment.CenterStart) {
        val lit = maxWidth * left.coerceIn(0.05f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.width(lit).height(4.dp).clip(CircleShape).background(Color.White))
        Box(
            Modifier
                .offset(x = lit - 6.dp)
                .size(12.dp)
                .graphicsLayer { scaleX = glow; scaleY = glow }
                .background(Brush.radialGradient(listOf(Color(0xFFFFFBE6), Color(0xFFFFCE6E), Color(0x00FFCE6E))), CircleShape),
        )
    }
}

/** A few words on a white chip on a card, in its colour: the minutes to it, by the way one goes. */
@Composable
private fun CardWord(symbol: String, words: String, colour: Color) {
    Row(
        Modifier
            .height(26.dp)
            .clip(CircleShape)
            .background(Color.White)
            .padding(start = 8.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = colour, size = 13.dp)
        Text(words, style = boardText(12f, 650, 15f, tnum = true), color = colour, maxLines = 1)
    }
}

/** The kinds there at any hour (cash, water, a toilet) as small tokens, three to a row. */
@Composable
private fun AnyTime(kinds: List<CategoryPulse>, lang: String, find: SearchSheetModel, onExplain: (Explained) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHead(stringResource(R.string.find_anytime), stringResource(R.string.find_anytime_line), kinds.size)
        for (row in kinds.chunked(3)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in row) key(p.node.id) { Token(p, lang, Modifier.weight(1f), onClick = { find.enter(p.node.id) }, onLongClick = { onExplain(Explained(p.node)) }) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A kind as a small token in its colour: its symbol, its name, the minutes to the nearest and how many are near. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Token(p: CategoryPulse, lang: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val node = p.node
    val art = remember(node.id) { artOf(node) }
    val name = node.name(lang)
    val near = "${p.count} ${stringResource(R.string.find_word_near)}"
    val line = listOfNotNull(p.minutes?.let { primeMinutes(it) }, near).joinToString(" · ")
    val said = listOfNotNull(p.minutes?.let { spokenMinutes(it) }, near).joinToString(", ")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .height(104.dp)
            .dive(diveKey(node))
            .pressable(interaction, depth = 0.05f)
            .cardSurface(Color(node.colour), art, RoundedCornerShape(20.dp), lift = 5.dp)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .clearAndSetSemantics {
                text = AnnotatedString("$name, $said")
                role = Role.Button
                onClick { onClick(); true }
                onLongClick { onLongClick(); true }
            }
            .padding(start = 11.dp, end = 10.dp, top = 11.dp, bottom = 10.dp),
    ) {
        // an emblem is the symbol already
        if (art is Scene) Symbol(node.symbol, tint = Color.White, size = 20.dp)
        Spacer(Modifier.weight(1f))
        Text(name, style = boardText(13.5f, 650, 16.5f).onCard(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(line, Modifier.padding(top = 1.dp), style = boardText(11f, 550, 14f, tnum = true), color = Color.White.copy(alpha = 0.84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The kinds asleep till they open, as a deck: the first to wake on the others, when it opens and how far; a tap fans
 * the deck out into its cards, each a tap into its kind.
 */
@Composable
private fun Asleep(kinds: List<CategoryPulse>, lang: String, now: LocalDateTime, find: SearchSheetModel, onExplain: (Explained) -> Unit) {
    var fanned by rememberSaveable { mutableStateOf(false) }
    val first = kinds.first()
    val wakes = first.opensAt ?: return
    // after dusk what wakes before noon sleeps till morning; by day the deck is only closed for now
    val morning = (now.hour >= DUSK || now.hour < MORNING) && wakes.hour < NOON
    val fan = stringResource(if (fanned) R.string.find_fold_up else R.string.find_fan_out)
    // the animation clips to its bounds: room inside them for the cards' shadows
    Column(Modifier.fillMaxWidth().animateContentSize().padding(bottom = 14.dp)) {
        SectionHead(
            stringResource(if (morning) R.string.find_asleep_night else R.string.find_asleep_day),
            stringResource(R.string.find_asleep_line, first.node.name(lang), guessedTime(clock(wakes), first.opensGuessed)), null,
            action = "${kinds.size} · $fan" to { fanned = !fanned },
        )
        if (!fanned) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp)) {
                // the cards under the first, their tops showing behind it
                val shape = RoundedCornerShape(24.dp)
                kinds.getOrNull(2)?.let { Box(Modifier.matchParentSize().padding(horizontal = 22.dp).offset(y = (-14).dp).clip(shape).background(lerp(asleepColour(it.node), Color.Black, 0.22f))) }
                kinds.getOrNull(1)?.let { Box(Modifier.matchParentSize().padding(horizontal = 11.dp).offset(y = (-7).dp).clip(shape).background(lerp(asleepColour(it.node), Color.Black, 0.1f))) }
                AsleepCard(first, lang, Modifier.fillMaxWidth(), dive = false, onClick = { fanned = true }, onLongClick = null)
            }
        } else {
            for (row in kinds.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (p in row) key(p.node.id) {
                        AsleepCard(p, lang, Modifier.weight(1f), dive = true, onClick = { find.enter(p.node.id) }, onLongClick = { onExplain(Explained(p.node)) })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A kind asleep as a card in its colour dimmed for the night, a moon on it: when it opens, and how far. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AsleepCard(p: CategoryPulse, lang: String, modifier: Modifier, dive: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val node = p.node
    val art = remember(node.id) { artOf(node) }
    val name = node.name(lang)
    val opens = p.opensAt?.let { stringResource(R.string.find_asleep_opens, guessedTime(clock(it), p.opensGuessed)) }
    val line = listOfNotNull(opens, p.minutes?.let { primeMinutes(it) }).joinToString(" · ")
    val said = listOfNotNull(opens, p.minutes?.let { spokenMinutes(it) }).joinToString(", ")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(108.dp)
            .dive(if (dive) diveKey(node) else null)
            .pressable(interaction)
            .cardSurface(asleepColour(node), art, RoundedCornerShape(24.dp), lift = 4.dp, faded = true)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .clearAndSetSemantics {
                text = AnnotatedString("$name, $said")
                role = Role.Button
                onClick { onClick(); true }
                if (onLongClick != null) onLongClick { onLongClick(); true }
            }
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Symbol("ui.moon", Modifier.align(Alignment.TopEnd), tint = Color.White.copy(alpha = 0.72f), size = 18.dp)
        Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = indexTitle(17f).onCard().copy(color = Color.White.copy(alpha = 0.94f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(line, style = boardText(12f, 550, 15f, tnum = true), color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A kind's colour after dark: its own, deepened toward the night. */
private fun asleepColour(node: CategoryNode): Color = lerp(Color(node.colour), Color(0xFF242632), 0.58f)

/**
 * The chains around by their signs, stacked by kind (the owner, 6 November: "Brands around stacks by kind?"): a kind's
 * chains on one stack ([BrandsAround.stacks], [ChainStack]), the kind's name under it; a kind with one chain is that
 * chain's sign, how many of its branches are near on a tab at its corner. A stack pressed is dealt out in its place, one
 * at a time, the kind's disc first, which stacks it again; a sign pressed shows its branches here, over the map; the
 * last sign opens every brand around on its own board.
 */
@Composable
private fun BrandsShelf(stacks: List<BrandStack>, lang: String, onBrand: (Brand) -> Unit, onAll: () -> Unit) {
    var dealt by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Caps(stringResource(R.string.find_brands_around), Modifier.padding(horizontal = 22.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = SHELF_EDGE, end = SHELF_EDGE, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (stack in stacks) {
                val only = stack.chains.singleOrNull()
                key(stack.id) {
                    Box(Modifier.animateContentSize()) {
                        when {
                            only != null -> ChainSign(only, SIGN, { onBrand(only.brand) }, Modifier.width(CELL))
                            dealt == stack.id -> Dealt(stack, lang, onStack = { dealt = null }, onBrand = onBrand)
                            else -> ChainStack(stack, stack.name(lang), SIGN, { dealt = stack.id }, Modifier.width(CELL))
                        }
                    }
                }
            }
            AllSigns(onAll)
        }
    }
}

/**
 * A stack dealt out on a tray of its own, brought into view with the shelf's margin on either side: the kind's disc
 * first, which stacks it again, then the sign of each of its chains.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dealt(stack: BrandStack, lang: String, onStack: () -> Unit, onBrand: (Brand) -> Unit) {
    val c = indexColors()
    val into = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { SHELF_EDGE.toPx() }
    Row(
        Modifier
            .bringIntoViewRequester(into)
            .onSizeChanged { size -> scope.launch { into.bringIntoView(Rect(-edge, 0f, size.width + edge, size.height.toFloat())) } }
            .clip(RoundedCornerShape(18.dp))
            .background(c.tray),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StackHead(stack, stack.name(lang), SIGN, onStack, Modifier.width(CELL))
        for (chain in stack.chains) ChainSign(chain, SIGN, { onBrand(chain.brand) }, Modifier.width(CELL))
    }
}

/** The last sign: every brand around, on its own board. */
@Composable
private fun AllSigns(onClick: () -> Unit) {
    val c = indexColors()
    SignColumn(onClick, Modifier.width(CELL)) {
        Box(Modifier.size(SIGN).raised(RoundedCornerShape(cornerOf(SIGN)), c, y = 2.dp, blur = 6.dp), contentAlignment = Alignment.Center) {
            Symbol("ui.brands", tint = c.soft, size = 24.dp)
        }
        Text(stringResource(R.string.find_brands_all), style = SIGN_NAME, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A sign on the shelf, and the room it takes with its name. */
/** How wide the name of the nearest place may run in a pick's chip. */
private val PICK_PLACE_WIDTH = 132.dp

private val SIGN = 52.dp
private val CELL = SIGN + 18.dp

/** The shelf's margin at either end, kept beside a stack dealt out. */
private val SHELF_EDGE = 14.dp

/**
 * The questions about places around that the map cannot answer yet, last on the grid: each answered (mostly yes or no,
 * from the pavement) leaves a note for the mappers, and counts on Mine's medal.
 */
@Composable
private fun QuestsLine(count: Int, onClick: () -> Unit) {
    val c = indexColors()
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 22.dp)
            .raised(shape, c, y = 2.dp, blur = 6.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(cardBrush(MEDAL)), contentAlignment = Alignment.Center) {
            Symbol("ui-help", tint = Color.White, size = 19.dp)
        }
        Column(Modifier.weight(1f)) {
            Caps(stringResource(R.string.quests_title))
            Text(pluralStringResource(R.plurals.find_quests_around, count, count), style = boardText(13.5f, 600, 18f), color = c.ink, maxLines = 2)
        }
        Symbol("ui-chevron-right", tint = c.faint, size = 18.dp)
    }
}

/** Mine's medal gold, where the answers are counted. */
private val MEDAL = Color(0xFFA67C2C)

/** The shelves in the moment's order (the one whose kinds matter most now first), or Atlas's; everything else last. */
private fun orderedShelves(s: SearchSheetState): List<CategoryNode> {
    val all = CategoryTree.shelves()
    if (s.prefs.fixed) return all
    val rank = Relevance.order(Categories.leaves().map { it.id }, s.moment).withIndex().associate { it.value to it.index }
    return all.sortedWith(
        compareBy<CategoryNode> { it.category.family == CategoryTree.OSM }
            .thenBy { shelf -> shelf.children.minOfOrNull { rank[it.category.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE },
    )
}

/** What a card says now, as a figure and its word: how many open, the nearest's minutes, when they open or close. */
@Composable
private fun tellFigure(p: CategoryPulse): TellFigure? {
    val v = p.value
    return when (p.tell) {
        TellKind.OPEN_COUNT -> openTell(p.open, p.unconfirmed)
        TellKind.COUNT -> TellFigure(v ?: p.count.toString(), stringResource(R.string.find_word_near))
        TellKind.NEAREST -> v?.toIntOrNull()?.let { minutesTell(it) }
        TellKind.OPENS_AT -> v?.let { TellFigure(guessedTime(it, p.opensGuessed), stringResource(R.string.find_word_opens), before = true) }
        TellKind.OPEN_TILL -> v?.let { TellFigure(guessedTime(it, p.untilGuessed), stringResource(R.string.find_word_till), before = true) }
        TellKind.FASTEST_KW -> v?.let { TellFigure(it, "kW") }
        TellKind.FREE_COUNT -> TellFigure(v ?: "0", stringResource(R.string.find_word_free))
        TellKind.NONE -> null
    }
}

@Composable
private fun why(r: FitReason): String = stringResource(
    when (r) {
        FitReason.TIME -> R.string.find_why_time
        FitReason.NIGHT -> R.string.find_why_night
        FitReason.SEASON -> R.string.find_why_season
        FitReason.SUNSET -> R.string.find_why_sunset
        FitReason.SUN -> R.string.find_why_sun
        FitReason.RAIN -> R.string.find_why_rain
        FitReason.WARM -> R.string.find_why_warm
        FitReason.COLD -> R.string.find_why_cold
        FitReason.STORM -> R.string.find_why_storm
        FitReason.HISTORY -> R.string.find_why_history
        FitReason.NEARBY -> R.string.find_why_nearby
    },
)

/** A section of the filters, by name. */
@Composable
internal fun sectionName(section: FacetSection): String = stringResource(
    when (section) {
        FacetSection.WHEN -> R.string.find_section_when
        FacetSection.KIND -> R.string.find_section_kind
        FacetSection.FOOD -> R.string.find_section_food
        FacetSection.ACCESS -> R.string.find_section_access
        FacetSection.COST -> R.string.find_section_cost
        FacetSection.PAYMENT -> R.string.find_section_payment
        FacetSection.SERVICES -> R.string.find_section_services
        FacetSection.CONTACT -> R.string.find_section_contact
        FacetSection.ADDRESS -> R.string.find_section_address
        FacetSection.NAMES -> R.string.find_section_names
        FacetSection.MAPPING -> R.string.find_section_mapping
        FacetSection.OTHER -> R.string.find_section_other
    },
)

@Composable
internal fun modeName(m: TravelMode): String = stringResource(
    when (m) {
        TravelMode.WALK -> R.string.find_mode_walk
        TravelMode.BIKE -> R.string.find_mode_bike
        TravelMode.CAR -> R.string.find_mode_car
        TravelMode.TRANSIT -> R.string.find_mode_transit
    },
)
