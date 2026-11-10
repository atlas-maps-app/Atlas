package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.model.Place
import app.atlas.search.CountAnswer
import app.atlas.search.DaySpread
import app.atlas.search.Facets
import app.atlas.search.HoursAnswer
import app.atlas.search.KindCount
import app.atlas.search.ResultHit
import app.atlas.search.SearchItem
import app.atlas.ui.kit.Symbol
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The answers to what typed words asked rather than the places they list (the user, 3 November: "how many trashcans in
 * this town", "overview of bakery opening times", and asked of a place, "how tall is", "does it have toilets"): each a
 * card over the places it speaks of, in the sheet's answer to the words and while they are typed.
 */

/**
 * "How many bins in this town": the number large, where they were counted ([area], around the searcher when null), how
 * many of them surely (their tags say so, not only perhaps), told by place where asked ("bars per neighbourhood"), and
 * of which kinds, the most first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CountCard(a: CountAnswer, modifier: Modifier = Modifier, area: String? = null, onClick: (() -> Unit)? = null) {
    val c = indexColors()
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .raised(shape, c, y = 3.dp, blur = 12.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(a.total.toString(), style = indexFigure(46f), color = c.ink, maxLines = 1)
            Column(Modifier.padding(bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caps(area?.let { stringResource(R.string.find_count_in, it) } ?: stringResource(R.string.find_count_found))
                if (a.sure < a.total) Text(stringResource(R.string.find_count_sure, a.sure, a.total - a.sure), style = INDEX_META, color = c.soft, maxLines = 1)
            }
        }
        if (a.groups.isNotEmpty()) Groups(a.groups)
        if (a.kinds.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in a.kinds.take(KINDS)) Tally(k)
            }
        }
    }
}

/** A count told by place: each quarter's name and number over a bar of its share, the most first. */
@Composable
private fun Groups(groups: List<KindCount>) {
    val c = indexColors()
    val most = groups.maxOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (g in groups.take(GROUPS)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.label, Modifier.weight(1f), style = boardText(13.5f, 600, 17f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.count.toString(), style = indexFigure(14f, 700), color = c.soft, maxLines = 1)
                }
                Box(Modifier.fillMaxWidth(g.count / most.toFloat()).height(4.dp).clip(CircleShape).background(c.ink))
            }
        }
    }
}

/** One kind among counted places, sunk into the card ("🪑 Bench 412"). */
@Composable
private fun Tally(k: KindCount) {
    val c = indexColors()
    Row(
        Modifier.height(30.dp).recessed(CircleShape, c).padding(start = 9.dp, end = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(k.symbol, tint = c.soft, size = 13.dp)
        Text(k.label, style = boardText(12.5f, 550, 16f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(k.count.toString(), style = indexFigure(12.5f, 600), color = c.faint, maxLines = 1)
    }
}

/**
 * "Overview of bakery opening times", a brand's week: the week as its differences, read at a glance (the owner, 6
 * November, of a brand's week: "only show differences at a glance and what stays the same fades or doesnt show a unique
 * own number/entry"). Days alike in a row share a line ("Mon–Fri", [WeekLine]): a bar across the day, light from the
 * first to open till the last to close, dark where most are open (the middle opening to the middle closing), today's
 * with a mark at the hour, and the dark part's hours in figures. The usual line, of the most days, says its hours in
 * ink; any other fades what it shares with it, so a Saturday's later opening stands out and its closing, the same, does
 * not. How many are open shows only where some days not all of them are, and fades where it is the usual line's too.
 * Under them today in words, and how many places say no hours at all.
 */
@Composable
internal fun HoursCard(h: HoursAnswer, now: LocalDateTime, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = indexColors()
    val shape = RoundedCornerShape(24.dp)
    val days = h.days.filter { it.earliest != null && it.latest != null }
    Column(
        modifier
            .fillMaxWidth()
            .raised(shape, c, y = 3.dp, blur = 12.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Caps(stringResource(R.string.find_hours_title), Modifier.padding(start = 2.dp, bottom = 6.dp))
        if (days.isEmpty()) {
            Text(stringResource(R.string.find_hours_none), style = INDEX_META, color = c.soft)
        } else {
            // the axis: from the hour the first opens on any day to the hour after the last closes
            val from = days.minOf { it.earliest!! } / 60 * 60
            val to = ((days.maxOf { it.latest!! } + 59) / 60 * 60).coerceAtLeast(from + 60)
            val lines = remember(h.days) { WeekLine.of(h.days) }
            val usual = remember(lines) { WeekLine.usual(lines) }
            // how many are open says something only where some days not all of them are
            val counts = h.days.any { it.open in 1 until h.known }
            val closed = stringResource(R.string.kit_closed)
            val labels = lines.map { it.label(Locale.getDefault()) }
            val hours = lines.map { l -> l.opens?.let { o -> "${hm(o)}–${hm(l.closes!!)}" } ?: closed }
            // the columns as wide as their widest words, so the bars line up
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val (dayWidth, hoursWidth) = remember(labels, hours, density) {
                with(density) {
                    labels.maxOf { measurer.measure(it, WEEK_DAY_BOLD).size.width }.toDp() + 10.dp to
                        hours.maxOf { measurer.measure(it, WEEK_HOURS).size.width }.toDp() + 12.dp
                }
            }
            val countWidth = if (counts) COUNT else 0.dp
            lines.forEachIndexed { i, l -> WeekBar(l, usual, labels[i], hours[i], from, to, now, dayWidth, hoursWidth, countWidth) }
            Ticks(from, to, dayWidth, hoursWidth + countWidth)
            h.days.firstOrNull { it.day == now.dayOfWeek }?.let { t -> Text(todayWords(t), Modifier.padding(top = 6.dp), style = INDEX_META, color = c.soft) }
        }
        if (h.unknown > 0) {
            Text(stringResource(R.string.find_hours_known, h.known, h.known + h.unknown), style = INDEX_META, color = c.faint)
        }
    }
}

/**
 * Days alike in a row of the week, [first] to [last], one line of the hours card: how they are spread ([spread], the
 * first's, which the others' are but for the day).
 */
internal data class WeekLine(val first: DayOfWeek, val last: DayOfWeek, val spread: DaySpread) {
    /** Whether [day] is one of its days. */
    operator fun contains(day: DayOfWeek): Boolean = day.ordinal in first.ordinal..last.ordinal

    /** How many days it holds. */
    val length: Int get() = last.ordinal - first.ordinal + 1

    /** When most are open, the middle opening to the middle closing (minutes after midnight); null where none opens. */
    val opens: Int? get() = spread.opensMiddle ?: spread.earliest
    val closes: Int? get() = spread.closesMiddle ?: spread.latest

    /** Its days as the line says them in [locale]: "Sat", "Mon–Fri". */
    fun label(locale: Locale): String {
        val a = first.getDisplayName(TextStyle.SHORT, locale)
        return if (first == last) a else a + "–" + last.getDisplayName(TextStyle.SHORT, locale)
    }

    /** Whether its days are spread as [other]'s are. */
    fun alike(other: WeekLine): Boolean = spread.copy(day = other.spread.day) == other.spread

    companion object {
        /** [days], Monday to Sunday, as lines: days alike in a row on one. */
        fun of(days: List<DaySpread>): List<WeekLine> {
            val out = ArrayList<WeekLine>()
            for (d in days) {
                val line = WeekLine(d.day, d.day, d)
                val before = out.lastOrNull()
                if (before != null && before.last.ordinal + 1 == d.day.ordinal && before.alike(line)) out[out.lastIndex] = before.copy(last = d.day)
                else out += line
            }
            return out
        }

        /** The usual line, against which the others say what differs: the open one of the most days, the first of those. */
        fun usual(lines: List<WeekLine>): WeekLine? = lines.filter { it.opens != null }.maxByOrNull { it.length }
    }
}

/**
 * One line of the week: its days, its bar across the hours from [from] to [to] (minutes after midnight), when most are
 * open in figures and, where [countWidth] makes room, how many open; what it shares with [usual] faded, all of it where
 * it is the usual line's days apart.
 */
@Composable
private fun WeekBar(l: WeekLine, usual: WeekLine?, label: String, hours: String, from: Int, to: Int, now: LocalDateTime, dayWidth: Dp, hoursWidth: Dp, countWidth: Dp) {
    val c = indexColors()
    val today = now.dayOfWeek in l
    val d = l.spread
    val other = usual?.takeIf { it !== l }
    val same = other != null && l.alike(other)
    val counted = countWidth > 0.dp && d.open > 0
    val said = if (counted) "$label $hours, ${d.open}" else "$label $hours"
    Row(Modifier.fillMaxWidth().height(20.dp).semantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(dayWidth), style = boardText(12f, if (today) 700 else 550, 15f), color = if (today) c.ink else c.soft, maxLines = 1)
        val track = c.tray
        val spread = c.open.copy(alpha = 0.3f)
        val most = c.open
        val mark = c.ink
        Canvas(Modifier.weight(1f).height(12.dp).alpha(if (same) SAME_ALPHA else 1f)) {
            val span = (to - from).toFloat()
            fun x(m: Int) = ((m - from) / span).coerceIn(0f, 1f) * size.width
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val e = d.earliest
            val la = d.latest
            if (e != null && la != null) {
                drawRoundRect(spread, Offset(x(e), 0f), Size(x(la) - x(e), size.height), r)
                val o = d.opensMiddle
                val cl = d.closesMiddle
                if (o != null && cl != null && cl > o) drawRoundRect(most, Offset(x(o), 0f), Size(x(cl) - x(o), size.height), r)
            }
            if (today) {
                val m = now.hour * 60 + now.minute
                if (m in from..to) drawLine(mark, Offset(x(m), -3f), Offset(x(m), size.height + 3f), strokeWidth = 2.dp.toPx())
            }
        }
        // its hours: in ink what differs from the usual line's, faded what is the same
        val o = l.opens
        val cl = l.closes
        Text(
            if (o == null || cl == null) AnnotatedString(hours) else buildAnnotatedString {
                withStyle(SpanStyle(color = if (o == other?.opens) c.faint else c.ink)) { append(hm(o)) }
                append("–")
                withStyle(SpanStyle(color = if (cl == other?.closes) c.faint else c.ink)) { append(hm(cl)) }
            },
            Modifier.width(hoursWidth), style = WEEK_HOURS, color = c.faint, maxLines = 1, textAlign = TextAlign.End,
        )
        if (countWidth > 0.dp) Text(
            if (counted) d.open.toString() else "", Modifier.width(countWidth), style = indexFigure(12.5f, 600),
            color = if (d.open == other?.spread?.open) c.faint else c.soft, maxLines = 1, textAlign = TextAlign.End,
        )
    }
}

/** The hours under the bars, every three or six of them by how many the bars cover. */
@Composable
private fun Ticks(from: Int, to: Int, start: Dp, end: Dp) {
    val c = indexColors()
    val step = if (to - from > 12 * 60) 6 * 60 else 3 * 60
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(start))
        BoxWithConstraints(Modifier.weight(1f).height(14.dp)) {
            var m = (from + step - 1) / step * step
            while (m <= to) {
                val f = (m - from).toFloat() / (to - from)
                Text(hm(m), Modifier.offset(x = maxWidth * f - 18.dp).width(36.dp), style = boardText(10f, 500, 13f, tnum = true), color = c.faint, maxLines = 1, textAlign = TextAlign.Center)
                m += step
            }
        }
        Spacer(Modifier.width(end))
    }
}

/** Today in words: when most are open, when the first opens and the last closes. */
@Composable
private fun todayWords(t: DaySpread): String {
    val e = t.earliest
    val l = t.latest
    if (e == null || l == null) return stringResource(R.string.find_hours_today_none)
    val o = t.opensMiddle ?: e
    val cl = t.closesMiddle ?: l
    return stringResource(R.string.find_hours_today, hm(o), hm(cl), hm(e), hm(l))
}

/** Minutes after midnight as a clock ("6:30"; past midnight "0:30"). */
internal fun hm(m: Int): String = "%d:%02d".format(Locale.ROOT, (m / 60) % 24, m % 60)

/**
 * "How tall is the Westertoren", "does the HEMA have toilets": what the place found says, large, with its name; what
 * other places of that name say under it; that the map does not say it where none does.
 */
@Composable
internal fun FactCard(f: SearchItem.Fact, lang: String, modifier: Modifier = Modifier, onPlace: (Place) -> Unit) {
    val c = indexColors()
    val first = f.answers.firstOrNull() ?: return
    val shape = RoundedCornerShape(24.dp)
    val others = f.answers.drop(1).filter { it.value != null }.take(OTHERS)
    Column(
        modifier
            .fillMaxWidth()
            .raised(shape, c, y = 3.dp, blur = 12.dp)
            .clickable(role = Role.Button) { onPlace(first.hit.place) }
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Caps(Facets.label(f.key, lang))
        val value = first.value
        Text(
            if (value != null) factWords(f.key, value) else stringResource(R.string.find_fact_none),
            style = if (value != null) indexTitle(25f) else boardText(15f, 600, 20f), color = if (value != null) c.ink else c.soft, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(first.hit.record.name(lang) ?: kindTitleOf(first.hit.place, lang).orEmpty(), style = INDEX_META, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        for (o in others) {
            Text(
                (o.hit.record.name(lang) ?: kindTitleOf(o.hit.place, lang).orEmpty()) + " · " + factWords(f.key, o.value!!),
                Modifier.padding(top = 4.dp), style = INDEX_META, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A fact's value as one says it: yes and no in the app's words, heights in metres, lists with commas. */
@Composable
private fun factWords(asked: String, value: String): String = when {
    value == "yes" -> stringResource(R.string.quests_yes)
    value == "no" -> stringResource(R.string.quests_no)
    asked in METRES && value.trim().toDoubleOrNull() != null -> value.trim() + " m"
    else -> value.replace(";", ", ")
}

/** A wish of a search that a place's tags do not say ([key], "toilets"), to be asked of the searcher. */
internal data class Unknown(val hit: ResultHit, val key: String, val label: String)

/**
 * Asks the searcher what a place does not say of what they searched for ("Toilets?"): yes or no, then the exact note
 * that answer leaves for the mappers nearby, which goes only once confirmed ([ConfirmSend]).
 */
@Composable
internal fun UnknownDialog(u: Unknown, find: SearchSheetModel, lang: String, onDismiss: () -> Unit) {
    val c = indexColors()
    val p = u.hit.record.place
    var answer by remember { mutableStateOf<Boolean?>(null) }
    val yes = answer
    if (yes == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.paper,
            icon = { Symbol("ui-help", tint = c.soon, size = 28.dp) },
            title = { Text(stringResource(R.string.find_unknown_title, u.label), style = indexTitle(19f), color = c.ink) },
            text = { Text(stringResource(R.string.find_unknown_text, u.hit.record.name(lang) ?: kindTitleOf(p, lang).orEmpty()), style = INDEX_META, color = c.soft) },
            confirmButton = { TextButton(onClick = { answer = true }) { Text(stringResource(R.string.quests_yes), color = c.ink) } },
            dismissButton = { TextButton(onClick = { answer = false }) { Text(stringResource(R.string.quests_no), color = c.ink) } },
        )
    } else {
        ConfirmSend(
            title = stringResource(R.string.place_note_confirm),
            text = Quests.keyNote(p, u.key, yes) + "\n\nvia Atlas",
            note = stringResource(R.string.place_note_note),
            confirm = stringResource(R.string.place_note_send),
            canSend = find.canSend(),
            onConfirm = { find.answer(p, u.key, yes) },
            onDismiss = onDismiss,
        )
    }
}

private val COUNT = 30.dp

/** A week line's days (measured bold, as today's are) and its hours. */
private val WEEK_DAY_BOLD = boardText(12f, 700, 15f)
private val WEEK_HOURS = indexFigure(12.5f, 600)

/** A week line the usual one's but for its days: its bar this faded. */
private const val SAME_ALPHA = 0.45f

/** At most this many kinds a count names, places it is told by, and other places a fact's answer names. */
private const val KINDS = 6
private const val GROUPS = 8
private const val OTHERS = 3

/** The facts asked in metres. */
private val METRES = setOf("height", "ele", "est_height", "building:height")
