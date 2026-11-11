package app.atlas.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.atlas.R
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.CategoryNode
import app.atlas.places.ChangeKind
import app.atlas.places.OpenStatus
import app.atlas.search.Facets
import app.atlas.search.Hit
import app.atlas.search.HoursFrom
import app.atlas.search.Layout
import app.atlas.search.Miss
import app.atlas.search.ResultHit
import app.atlas.search.ResultItem
import app.atlas.search.Tell
import app.atlas.search.TellKey
import app.atlas.search.TellTone
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes
import app.atlas.ui.kit.markOf
import app.atlas.ui.kit.signOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * A row of the Index: its key (a kind's disc, a logo), its name over a line, what follows (what it offers, an arrow),
 * and at its end how far, in the minutes the boards write ("7′", none for what is not a place); a hairline under it
 * from where its words start. In by [indent] where it stands under the row above it (a narrower kind under its broader).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IndexLine(
    title: String,
    line: AnnotatedString?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reach: (@Composable () -> Unit)? = null,
    lead: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    titleLines: Int = 1,
    hairline: Boolean = true,
    indent: Dp = 0.dp,
) {
    val c = indexColors()
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .drawBehind {
                if (hairline) {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(c.hair, Offset((ROW_TEXT_START + indent).toPx(), y), Offset(size.width - 18.dp.toPx(), y), 1.dp.toPx())
                }
            }
            .padding(start = 16.dp + indent, end = 18.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = INDEX_NAME, color = c.ink, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            line?.let { Text(it, style = INDEX_META, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
        // right-aligned in a column of their own, so the primes stand under each other
        if (reach != null) Box(Modifier.padding(start = 12.dp).widthIn(min = REACH_WIDTH), contentAlignment = Alignment.CenterEnd) { reach() }
    }
}

/** The room the minutes at a row's end take at least: "99′". */
private val REACH_WIDTH = 32.dp

/**
 * An outing in one sentence, over the map (until 4 November it had a page of its own): each stop timed from now, the
 * walk to it and the usual stay, the other places that would do for it a tap away (the user, 4 November: "propose
 * multiple stops and options and you can quickly in depth click your route together"), the ways to change it, and the
 * way past every stop into the route planner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OutingList(o: Asked.Outing, s: SearchSheetState, find: SearchSheetModel, nav: Navigator, lang: String, room: Dp, onPlace: (Place) -> Unit) {
    val plan = o.plan
    val walked = plan?.let { "${shortDistance(it.walkM)} ${stringResource(R.string.ask_on_foot)}" + (it.end?.let { end -> " · ${stringResource(R.string.ask_until)} ${clock(end)}" } ?: "") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = room)) {
        item(key = "head") { SectionHead(o.text, walked, null) }
        item(key = "refine") {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IndexChip(stringResource(R.string.ask_closer), { find.refine(Refine.CLOSER) }, selected = Refine.CLOSER in o.refine)
                IndexChip(stringResource(R.string.ask_surely_open), { find.refine(Refine.OPEN) }, selected = Refine.OPEN in o.refine)
                IndexChip(stringResource(R.string.search_step_free), { find.refine(Refine.STEP_FREE) }, selected = Refine.STEP_FREE in o.refine)
            }
        }
        when {
            plan == null && s.error != null -> item(key = "error") {
                StateMessage("ui-alert-triangle", stringResource(R.string.search_failed), Modifier.padding(16.dp), text = s.error, tint = Color(0xFFC8322A))
            }
            plan == null -> item(key = "looking") { Looking() }
            else -> itemsIndexed(plan.stops, key = { i, stop -> "stop:$i:" + (stop.hit?.place?.key ?: "") }) { i, stop ->
                val hit = stop.hit
                val name = hit?.let { it.record.name(lang) ?: kindTitleOf(it.place, lang) } ?: stringResource(R.string.ask_none_for, stop.label)
                val parts = buildList {
                    if (hit != null) {
                        kindOf(hit.place, lang)?.takeIf { hit.record.name(lang) != null && it != name }?.let { add(it) }
                        if (stop.walkM > 0) add(stringResource(R.string.ask_walk, app.atlas.ui.kit.walkMinutes(app.atlas.ui.kit.WalkTime.seconds(stop.walkM))))
                        if (stop.openThen == true) add(stringResource(R.string.ask_open_then)) else if (stop.openThen == null) add(stringResource(R.string.kit_hours_unknown))
                        add(stringResource(R.string.ask_stay, stop.stay.toMinutes().toInt()))
                    } else {
                        add(stringResource(R.string.ask_none_text))
                    }
                }
                IndexLine(
                    name, AnnotatedString(parts.joinToString(" · ")), { hit?.let { onPlace(it.place) } },
                    reach = { Text(if (hit != null) clock(stop.arrive) else "–", style = indexFigure(14f, 700), color = indexColors().ink, maxLines = 1) },
                    lead = { KindPin(hit?.symbol ?: stop.symbol, if (hit != null) NEAR else 0xFFB9B4AA.toInt(), size = 38.dp) },
                    hairline = i < plan.stops.lastIndex && stop.options.isEmpty(),
                )
                if (stop.options.isNotEmpty()) StopOptions(stop, lang, hairline = i < plan.stops.lastIndex) { h -> find.pick(stop.step, h) }
            }
        }
        if (plan != null && plan.found > 0) item(key = "go") { GoPast(plan, lang, nav) }
    }
}

/** The other places that would do for a stop, each with its walk: one taken, the stops after it are planned again. */
@Composable
private fun StopOptions(stop: AskStop, lang: String, hairline: Boolean, onPick: (Hit) -> Unit) {
    val c = indexColors()
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (hairline) {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(c.hair, Offset(ROW_TEXT_START.toPx(), y), Offset(size.width - 18.dp.toPx(), y), 1.dp.toPx())
                }
            }
            .horizontalScroll(rememberScrollState())
            .padding(start = ROW_TEXT_START, end = 18.dp, bottom = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.ask_instead), style = INDEX_META, color = c.faint)
        for ((h, walk) in stop.options) {
            val name = h.record.name(lang) ?: kindTitleOf(h.place, lang) ?: stop.label
            IndexChip("$name ${walkMinutes(WalkTime.seconds(walk))}", { onPick(h) }, symbol = h.symbol, role = Role.Button)
        }
    }
}

/** The way past every stop found, in turn, on foot: into the route planner, the last stop its end, the others on the way. */
@Composable
private fun GoPast(plan: AskPlan, lang: String, nav: Navigator) {
    val found = plan.stops.mapNotNull { it.hit }
    val names = found.map { it.record.name(lang) ?: kindTitleOf(it.place, lang) ?: "" }
    val last = found.last().record.location
    IndexLine(
        stringResource(R.string.ask_route_all), AnnotatedString(names.joinToString(" → ")),
        { nav.go(Dest.Route(last.lat, last.lon, names.last(), mode = "WALK", via = viaOf(found.dropLast(1).map { it.record.location }.zip(names.dropLast(1))))) },
        lead = { KindPin("ui-route", NEAR, size = 38.dp) },
        hairline = false,
    )
}

/** How far, at a row's end: the minutes to it ("7′"), or past an hour and a half of them its kilometres ("12 km"). */
@Composable
internal fun Reach(minutes: Int?, metres: Double?) {
    val c = indexColors()
    when {
        minutes != null && minutes < 100 -> MinutesFigure(minutes)
        metres != null -> {
            val km = metres / 1000
            val unit = stringResource(R.string.find_unit_km)
            Text(
                buildAnnotatedString {
                    append(if (km < 10) String.format(Locale.getDefault(), "%.1f", km) else km.roundToInt().toString())
                    withStyle(SpanStyle(color = c.faint, fontSize = 0.6.em)) { append(" $unit") }
                },
                style = indexFigure(if (km < 100) 20f else 17f, 800), color = c.ink, maxLines = 1,
            )
        }
    }
}

/** How a place stands once there, where that is not how it stands now ([arrivalOf]), and the light it shows. */
internal enum class Arrival(val tone: Tone) { CLOSED(Tone.CLOSED), CLOSES(Tone.SOON), OPEN(Tone.OPEN), OPENS(Tone.SOON) }

/**
 * How a place stands once there, [minutes] from [now], where that is not how it stands now (concept 6, "Opens 19:00,
 * 14 min after you arrive"): closed by then, closing soon after, open by then, or opening soon after, with the minutes
 * from arriving to that change; null where now's state holds.
 */
internal fun arrivalOf(open: OpenStatus?, minutes: Int, now: LocalDateTime): Pair<Arrival, Int>? {
    if (open == null) return null
    val at = open.changesAt ?: return null
    val after = Duration.between(now.plusMinutes(minutes.toLong()), at).toMinutes().toInt()
    return when {
        open.isOpen && open.nextChangeKind == ChangeKind.CLOSES -> when {
            after <= 0 -> Arrival.CLOSED to after
            after <= ARRIVE_SOON -> Arrival.CLOSES to after
            else -> null
        }
        !open.isOpen && open.nextChangeKind == ChangeKind.OPENS -> when {
            after <= 0 -> Arrival.OPEN to after
            after <= ARRIVE_WAIT -> Arrival.OPENS to after
            else -> null
        }
        else -> null
    }
}

/** [arrivalOf] in words, with its light. */
@Composable
internal fun arrivalWords(open: OpenStatus?, minutes: Int, now: LocalDateTime): Pair<String, Tone>? {
    val (arrival, after) = arrivalOf(open, minutes, now) ?: return null
    val words = when (arrival) {
        Arrival.CLOSED -> stringResource(R.string.find_arrive_closed)
        Arrival.CLOSES -> stringResource(R.string.find_arrive_closes, after)
        Arrival.OPEN -> stringResource(R.string.find_arrive_open)
        Arrival.OPENS -> stringResource(R.string.find_arrive_opens, clock(open?.changesAt ?: return null), after)
    }
    return words to arrival.tone
}

/**
 * Whether a place [minutes] away is closed as its row's light says it: by the time one gets there, where that is not
 * how it stands now ([arrivalOf]), else now; never where its hours are not known. Its mark is lighter, on the map and in
 * the list (the owner, 6 November: "Lighter for closed is a good idea").
 */
internal fun closedThere(open: OpenStatus?, minutes: Int?, now: LocalDateTime): Boolean {
    val arrival = minutes?.let { arrivalOf(open, it, now) }
    if (arrival != null) return arrival.first == Arrival.CLOSED
    return open != null && !open.unknown && !open.isOpen
}

/**
 * Open or not in its colour, then the rest of what a row says. Hours not checked for long fade, with the year they
 * were last looked at written small after them ([staleMark]).
 */
@Composable
internal fun statusOf(words: String?, tone: Tone, mark: String?, rest: List<String>): AnnotatedString {
    val c = indexColors()
    return buildAnnotatedString {
        if (words != null) withStyle(SpanStyle(color = c.of(tone).copy(alpha = if (mark != null) STALE_ALPHA else 1f))) { append(words) }
        if (mark != null) withStyle(SpanStyle(color = c.faint, fontSize = 0.72.em, baselineShift = BaselineShift.Superscript)) { append(" " + mark) }
        val more = rest.filter { it.isNotBlank() }
        if (more.isNotEmpty()) {
            if (words != null || mark != null) append(" · ")
            append(more.joinToString(" · "))
        }
    }
}

/** For hours not checked for long, the year they last were ("’17"), "?" when never; null for hours still sure. */
internal fun staleMark(h: ResultHit): String? =
    if (!h.hoursStale) null else h.hoursChecked?.let { "’" + "%02d".format(it.year % 100) } ?: "?"

/**
 * A place: its kind's disc with the open light, its name, then open or not in colour ("≈" by its brand's hours, faded
 * with a year when not checked for long), on an errand the minutes to spare before it closes, what it was asked to be
 * near and how far, what its kind's purpose says of it in words ([ResultHit.tells]: its cuisine, that it is free) and
 * its kind where the view is not of that kind already; a closest match says first what it misses of what was asked
 * ("Opens in 20 min"). At its end what a symbol says alone (held, the symbol says what it means), a charger's power,
 * each wish of the search its tags do not say as a "?" that asks the searcher, and last the minutes to it ("7′").
 */
@Composable
internal fun PlaceRow(h: ResultHit, w: RowWays, hairline: Boolean = true) {
    val c = indexColors()
    val p = h.record.place
    val lang = w.lang
    val line = remember(h, w.now) { PlaceLine.of(p, null, lang, w.now, h.open, w.nameOf(h), h.metres) }
    val arrival = arrivalWords(h.open, h.minutes, w.now)
    val tone = arrival?.second ?: if (h.open == null) Tone.UNKNOWN else line.tone
    val words = (arrival?.first ?: openWords(line))?.let { if (!h.hoursConfirmed) "≈ $it" else it }
    val kind = h.node?.takeIf { it.id != w.node }?.let(w::kindName) ?: line.summary.kind.takeIf { h.node == null }
    val power = h.tells.firstOrNull { it.key == TellKey.POWER && it.text != null }.takeIf { w.layout == Layout.POWER }
    val worded = h.tells.filter { it.text != null && it !== power }
    val symbols = h.tells.filter { it.text == null }.take(SYMBOLS)
    val spare = if (w.layout == Layout.ERRAND && arrival == null) spareMinutes(h, w.now) else null
    val near = h.near.map { m -> listOfNotNull(kindOf(m.record.place, lang) ?: m.record.name(lang), shortDistance(m.distance)).joinToString(" ") }
    val unknown = h.unknown.filter { k -> answerKey(p, k) in w.answered || !w.asked(p, k) }.take(UNKNOWNS)
    val missed = h.misses.take(MISSES).mapNotNull { missWords(it) }
    val kept = w.kept[p.key]
    val plan = w.onPlan?.takeIf { w.plans(p) }
    // where it misses the hour asked, that says more than open or not now
    val timed = h.misses.firstOrNull().let { it is Miss.Opens || it is Miss.Closed || it == Miss.HoursUnknown }
    IndexLine(
        line.summary.name, rowStatus(words.takeUnless { timed }, tone, staleMark(h).takeUnless { timed }, spare, near, worded, listOfNotNull(kind), missed),
        onClick = { w.onPlace(p) }, modifier = Modifier.testTag(PLACE_TAG),
        reach = { Reach(h.minutes, h.metres) },
        lead = { remember(p) { signOf(p) }?.let { SignDisc(it, tone) } ?: KindDisc(line.summary.symbol, line.summary.colour, tone) },
        trailing = if (kept == null && power == null && symbols.isEmpty() && unknown.isEmpty() && plan == null) null else ({
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // kept in a list: its bookmark, in the list's colour
                kept?.let { Symbol("ui.bookmark", tint = Color(it), size = 15.dp, contentDescription = stringResource(R.string.lists_default)) }
                for (k in unknown) UnknownMark(Facets.symbol(k), w.label(k), w.answered[answerKey(p, k)]) { w.onUnknown?.invoke(h, k) ?: w.onPlace(p) }
                for (t in symbols) TipSymbol(t.symbol, t.key.label(lang), if (t.stale) c.faint.copy(alpha = STALE_ALPHA) else c.faint) { w.onPlace(p) }
                power?.let { PowerFigure(it) }
                plan?.let { PlanKey { it(p) } }
            }
        }),
        onLongClick = { h.node?.let { w.onExplain(explained(it, h)) } },
        hairline = hairline,
    )
}

/**
 * The way to a green's own plan, its paths, toilets, water and cafés (the board on two posts it is drawn on): a key of
 * its own at the row's end, the row itself still opening the place.
 */
@Composable
internal fun PlanKey(onClick: () -> Unit) {
    val c = indexColors()
    val said = stringResource(R.string.walk_plan)
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c.tray).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) { Symbol("temaki-info_board", tint = c.ink, size = 18.dp) }
}

/** The minutes to spare on arriving before it closes, where it closes within the errand's window; null where not. */
private fun spareMinutes(h: ResultHit, now: LocalDateTime): Int? {
    val open = h.open?.takeIf { it.isOpen && it.nextChangeKind == ChangeKind.CLOSES } ?: return null
    val at = open.changesAt ?: return null
    return Duration.between(now.plusMinutes(h.minutes.toLong()), at).toMinutes().toInt().takeIf { it in MIN_SPARE..CLOSING_WINDOW }
}

/**
 * A row's line: what a closest match misses of what was asked, in amber, open or not ([statusOf]), the minutes to
 * spare, then what it is near, its tells in their tone (good news green, a warning amber, faded with the year where
 * not checked for long) and the rest.
 */
@Composable
private fun rowStatus(
    words: String?,
    tone: Tone,
    mark: String?,
    spare: Int?,
    near: List<String>,
    tells: List<Tell>,
    rest: List<String>,
    misses: List<String> = emptyList(),
): AnnotatedString {
    val c = indexColors()
    val spared = spare?.let { stringResource(R.string.find_spare, it) }
    val start = statusOf(words, tone, mark, emptyList())
    return buildAnnotatedString {
        fun gap() {
            if (length > 0) append(" · ")
        }
        for (m in misses) {
            gap()
            withStyle(SpanStyle(color = c.soon)) { append(m) }
        }
        if (start.isNotEmpty()) {
            gap()
            append(start)
        }
        if (spare != null && spared != null) {
            gap()
            withStyle(SpanStyle(color = if (spare < ARRIVE_SOON) c.soon else c.soft)) { append(spared) }
        }
        for (n in near) {
            gap()
            withStyle(SpanStyle(color = c.ink)) { append(n) }
        }
        for (t in tells) {
            gap()
            val colour = when (t.tone) {
                TellTone.GOOD -> c.open
                TellTone.WARN -> c.soon
                TellTone.PLAIN -> c.soft
            }
            withStyle(SpanStyle(color = colour.copy(alpha = if (t.stale) STALE_ALPHA else 1f))) { append(t.text.orEmpty()) }
            if (t.stale) withStyle(SpanStyle(color = c.faint, fontSize = 0.72.em, baselineShift = BaselineShift.Superscript)) { append(" " + yearMark(t.checked)) }
        }
        for (r in rest.filter { it.isNotBlank() }) {
            gap()
            append(r)
        }
    }
}

/**
 * What a closest match misses of what was asked, in a few words ("Opens in 20 min", "300 m beyond the circle", "Rated
 * 4.1 for 4.5+"); null where it has nothing to say.
 */
@Composable
internal fun missWords(m: Miss): String? = when (m) {
    is Miss.Opens -> if (m.minutes < 60) stringResource(R.string.relax_opens_in, m.minutes.toInt()) else stringResource(R.string.relax_opens_at, clock(m.at))
    is Miss.Closed -> stringResource(R.string.relax_closed_at, clock(m.at))
    Miss.HoursUnknown -> stringResource(R.string.relax_hours_unknown)
    is Miss.Beyond -> stringResource(R.string.relax_beyond, shortDistance(m.metres))
    is Miss.Farther -> m.metres?.let { stringResource(R.string.relax_farther, shortDistance(it), m.label) } ?: stringResource(R.string.relax_farther_none, m.label)
    is Miss.Rated -> m.rating?.let { stringResource(R.string.relax_rated, rating(it), rating(m.wanted)) } ?: stringResource(R.string.relax_unrated, rating(m.wanted))
    is Miss.Price -> if (m.steps >= 0) pluralStringResource(R.plurals.relax_price_up, m.steps, m.steps) else pluralStringResource(R.plurals.relax_price_down, -m.steps, -m.steps)
    is Miss.Lacks -> m.label.takeIf { it.isNotBlank() }?.let { stringResource(if (m.unsaid) R.string.relax_unsaid else R.string.relax_lacks, it) }
    is Miss.Outside -> m.area.takeIf { it.isNotBlank() }?.let { a -> m.metres?.let { stringResource(R.string.relax_outside, shortDistance(it), a) } ?: stringResource(R.string.relax_elsewhere, a) }
}

/** A rating as written where it is read: "4.5", "4,5". */
private fun rating(r: Double): String = String.format(Locale.getDefault(), "%.1f", r)

/** What a held row explains of a place: its kind, and whether its hours are its brand's or its kind's around it. */
internal fun explained(node: CategoryNode, h: ResultHit): Explained {
    val guessed = h.open != null
    return Explained(node, h.record.place, brandHours = guessed && h.hoursFrom == HoursFrom.BRAND, kindHours = guessed && h.hoursFrom == HoursFrom.KIND, stale = h.hoursStale)
}

/** The year something was last checked, small ("’17"), "?" when never. */
private fun yearMark(checked: LocalDate?): String = checked?.let { "’" + "%02d".format(it.year % 100) } ?: "?"

/** A charger's power at a row's end, large, its unit under it ("150 / KW"). */
@Composable
private fun PowerFigure(t: Tell) {
    val c = indexColors()
    val parts = t.text.orEmpty().split(' ', limit = 2)
    Column(Modifier.padding(start = 4.dp).width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(parts[0], style = indexFigure(20f), color = c.ink, maxLines = 1)
        Text(parts.getOrElse(1) { "" }.uppercase(Locale.getDefault()), style = INDEX_CAPS.copy(fontSize = 8.sp, lineHeight = 10.sp), color = c.faint, maxLines = 1)
    }
}

/**
 * A wish the search made of a place that its tags do not say, as the wish's symbol and a question mark ("📶?"): a tap
 * asks the searcher, whose answer leaves a note for the mappers; once sent, a tick in place of the question.
 */
@Composable
private fun UnknownMark(symbol: String, label: String, state: Sending?, onClick: () -> Unit) {
    val c = indexColors()
    val done = state is Sending.Done
    val ask = stringResource(R.string.find_unknown_ask, label)
    Row(
        Modifier
            .height(28.dp)
            .clip(CircleShape)
            .border(1.dp, c.hair, CircleShape)
            .clickable(enabled = state == null || state is Sending.Failed, role = Role.Button, onClickLabel = ask, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 8.dp, end = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = c.soft, size = 14.dp)
        if (done) Symbol("ui-check", tint = c.open, size = 12.dp) else Text("?", style = boardText(13f, 700, 16f), color = c.soon, maxLines = 1)
    }
}

/**
 * A symbol that says what it means when held (the user, 3 November: "Long tap a symbol, to open up a small
 * explanation. Not every symbol is obvious"); a tap is [onTap]'s, as if the row under it were tapped.
 */
@Composable
internal fun TipSymbol(symbol: String, label: String, tint: Color, size: Dp = 14.dp, onTap: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Box(
        Modifier
            .semantics { contentDescription = label }
            .pointerInput(label) { detectTapGestures(onLongPress = { shown = true }, onTap = { onTap() }) }
            .padding(2.dp),
    ) {
        Symbol(symbol, tint = tint, size = size)
        if (shown) Tip(label) { shown = false }
    }
}

/** A word of explanation over what was held, in ink, gone at the next touch. */
@Composable
internal fun Tip(text: String, onDismiss: () -> Unit) {
    val c = indexColors()
    val lift = with(LocalDensity.current) { 34.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -lift), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink).padding(horizontal = 10.dp, vertical = 6.dp),
            style = boardText(12.5f, 550, 16f), color = c.onInk, maxLines = 2,
        )
    }
}

/** At most this many tells a symbol says alone, and wishes a "?" asks of, at a row's end. */
private const val SYMBOLS = 3
private const val UNKNOWNS = 2

/** At most this many of what a closest match misses its row says. */
private const val MISSES = 2

/**
 * Places on one deck, one card on two more: a brand's branches by its logo, or a kind's places with no name of their
 * own by its symbol and its name ("Playground"); how many are near and open, the nearest's minutes. A tap spreads them
 * out, a kind's places each by the street it is on ([rememberStreets]), as none has a name to tell it by.
 */
@Composable
internal fun DeckRow(b: ResultItem.Deck, w: RowWays) {
    val c = indexColors()
    val id = when (b) {
        is ResultItem.Bundle -> b.brand
        is ResultItem.Unnamed -> b.kind.id
    }
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val first = b.first
    val mark = remember(first.record.place) { markOf(first.record.place) }
    val anyOpen = b.hits.count { it.open?.isOpen == true }
    // none of which says whether it is open (gift shops mapping no hours): how many alone, no "0 open"
    val told = b.hits.any { it.open != null }
    val name = when (b) {
        is ResultItem.Bundle -> b.name
        is ResultItem.Unnamed -> b.kind.preset?.fullName(w.lang) ?: b.kind.name(w.lang)
    }
    val streets = rememberStreets((b as? ResultItem.Unnamed)?.hits?.takeIf { open }.orEmpty(), w.lang)
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().padding(bottom = if (open) 0.dp else 8.dp)) {
            if (!open) {
                // the deck's lower cards, their edges under the top one
                Box(Modifier.matchParentSize().padding(horizontal = 18.dp).offset(y = 8.dp).raised(shape, c, y = 1.dp, blur = 3.dp))
                Box(Modifier.matchParentSize().padding(horizontal = 9.dp).offset(y = 4.dp).raised(shape, c, y = 1.dp, blur = 3.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(DECK_TAG)
                    .raised(shape, c, y = 3.dp, blur = 12.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // a chain's deck is a row of the list: on its own sign, as its pins have it, else its logo on its family's
                // colour; lighter where every branch is closed, as their pins are
                val shut = b.hits.all { closedThere(it.open, it.minutes, w.now) }
                remember(first.record.place) { signOf(first.record.place) }?.let { SignDisc(it, null, faded = shut) }
                    ?: KindDisc(mark.first, mark.second, null, faded = shut)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = INDEX_NAME, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (told) stringResource(R.string.find_counts, b.hits.size.toString(), anyOpen.toString())
                        else stringResource(R.string.find_count_near, b.hits.size.toString()),
                        style = INDEX_META, color = if (anyOpen > 0) c.open else c.soft, maxLines = 1,
                    )
                }
                Reach(first.minutes, first.metres)
                Symbol(if (open) "ui.chevron-up" else "ui.chevron-down", tint = c.faint, size = 16.dp)
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            // a kind's places under its name: none says the kind again, each says where it is
            val rows = if (b is ResultItem.Unnamed) w.within(b.kind.id, streets) else w
            Column(Modifier.padding(top = 4.dp)) {
                b.hits.forEachIndexed { i, h -> PlaceRow(h, rows, hairline = i < b.hits.lastIndex) }
            }
        }
    }
}

/**
 * The street each of [hits] is on, by place key: its address where it maps one ("Westermarkt 12"), else the street the
 * map's data has nearest it (most playgrounds and benches have neither name nor address); read off the main thread,
 * none for a place with no street near.
 */
@Composable
private fun rememberStreets(hits: List<ResultHit>, lang: String): Map<String, String> {
    val services = LocalServices.current
    return produceState(emptyMap<String, String>(), hits) {
        value = hits.mapNotNull { h ->
            val p = h.record.place
            val mapped = p.tags["addr:street"]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { street -> listOfNotNull(street, p.tags["addr:housenumber"]?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ") }
            val street = mapped ?: try {
                services.localData.streetNear(p.location, STREET_M, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            street?.let { p.key to it }
        }.toMap()
    }.value
}

/** How far from a place the street it is on may run, as the two-wheel board has it for the stands. */
private const val STREET_M = 40.0
