package app.atlas.ui.search

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.search.CategoryResults
import app.atlas.search.Facet
import app.atlas.search.FacetKind
import app.atlas.search.FacetSection
import app.atlas.ui.kit.Symbol
import java.time.LocalDateTime
import java.util.Locale

/** Every facet of the places, by section: when they are open (the day's curve), then every tag's values with counts. */
internal fun LazyListScope.filtersPane(s: SearchSheetState, find: SearchSheetModel, lang: String, now: LocalDateTime, unfolded: MutableList<String>) {
    val results = s.results ?: return
    val facets = results.facets
    item(key = "f-head") { FiltersHead(s, find, lang, now) }
    item(key = "f-when") { WhenBlock(s, results, find, now) }
    for (section in FacetSection.entries) {
        val list = facets.section(section)
        if (list.isEmpty()) continue
        if (section != FacetSection.WHEN) item(key = "f-section-$section") { SectionLabel(sectionName(section)) }
        items(list, key = { "f-facet-" + it.id }) { f ->
            FacetBlock(f, s, find, f.id in unfolded) { if (f.id in unfolded) unfolded.remove(f.id) else unfolded.add(f.id) }
        }
    }
}

/**
 * The way back, what the filters are of (the kind, or the words asked) over their title, then how far it looks and
 * the filters chosen (a tap takes one out, a hold keeps it), then how many places are left of how many.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersHead(s: SearchSheetState, find: SearchSheetModel, lang: String, now: LocalDateTime) {
    val c = indexColors()
    val facets = s.results?.facets
    val words = facets?.let { chosenWords(it) }.orEmpty()
    val of = s.intent?.let { intentName(it, lang, now.toLocalTime()) } ?: s.asked?.text ?: s.node?.name(lang)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IndexRound("ui-chevron-left", stringResource(R.string.find_up), { find.showFilters(false) })
            Column(Modifier.weight(1f)) {
                of?.let { Caps(it) }
                Text(stringResource(R.string.find_filters_title), style = indexTitle(21f), color = c.ink, maxLines = 2)
            }
        }
        FlowRow(
            Modifier.padding(start = 52.dp, top = 12.dp, bottom = 14.dp).animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReachChip(s, find)
            for ((f, v) in words) {
                IndexChip(
                    v.chip, { find.toggle(f.id, v.id) }, symbol = v.symbol, selected = true, kept = "${f.id}=${v.id}" in s.prefs.sticky,
                    hides = facets?.let { hidden(f, it) }, onLongClick = { find.keep(f.id, v.id) }, onClickLabel = stringResource(R.string.find_word_remove),
                )
            }
        }
        if (words.isNotEmpty() && facets != null && facets.total > 0) {
            Meter(facets.kept.size, facets.total, Color(s.node?.colour ?: ASK))
        } else {
            Text(stringResource(R.string.find_filters_hint), Modifier.padding(start = 52.dp, end = 8.dp), style = INDEX_META, color = c.soft)
        }
    }
}

/** How many of the places have every word picked ("11 of 41"), over a bar of their share in the kind's colour. */
@Composable
private fun Meter(kept: Int, total: Int, colour: Color) {
    val c = indexColors()
    val share by animateFloatAsState(kept.toFloat() / total, label = "meter")
    Row(Modifier.padding(start = 52.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(kept.toString(), style = indexFigure(32f), color = if (c.dark) lerp(colour, Color.White, 0.3f) else colour, maxLines = 1)
        Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.find_picked_all, total), style = INDEX_META, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.hair)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).clip(CircleShape).background(colour))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Caps(text, Modifier.padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 2.dp))
}

/** "When": now or a day, and the day's hours as bars of how many are open in each, a tap or a drag picking one. */
@Composable
private fun WhenBlock(s: SearchSheetState, r: CategoryResults, find: SearchSheetModel, now: LocalDateTime) {
    val today = s.moment.now.toLocalDate()
    val at = s.at
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.find_section_when))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IndexChip(stringResource(R.string.find_open_at_now), { find.openAt(null) }, symbol = "ui-clock", selected = at == null, role = Role.RadioButton)
            for (d in 0 until 7) {
                val day = today.plusDays(d.toLong())
                val label = when (d) {
                    0 -> stringResource(R.string.find_today)
                    1 -> stringResource(R.string.find_tomorrow)
                    else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                }
                val hour = at?.hour ?: (s.moment.now.hour + 1).coerceAtMost(23)
                IndexChip(label, { find.openAt(day.atTime(hour, 0)) }, selected = at?.toLocalDate() == day, role = Role.RadioButton)
            }
        }
        val from = r.hoursFrom
        if (from != null && r.byHour.isNotEmpty()) {
            HourCurve(r.byHour, from, now, at, accent = Color(r.node.colour), onPick = { find.openAt(it) }, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp))
        }
    }
}

/** One facet: its name and how many of the places map it, then its values with their counts (a long list folded). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetBlock(f: Facet, s: SearchSheetState, find: SearchSheetModel, unfolded: Boolean, onFold: () -> Unit) {
    val c = indexColors()
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(f.symbol, tint = c.soft, size = 15.dp)
            Text(f.label, Modifier.weight(1f), style = boardText(14f, 600, 18f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.mapped}/${f.over}", style = boardText(11f, 500, 14f, tnum = true), color = c.faint)
        }
        if ((f.kind == FacetKind.PRICE || f.kind == FacetKind.CHECKED) && f.values.size in 2..SEGMENTS) {
            Segments(f, find)
        } else {
            val all = if (f.kind == FacetKind.MAPPED) f.values.take(2) else f.values
            val folded = !unfolded && all.size > FOLD + 1 && all.drop(FOLD).none { it.selected }
            val shown = if (folded) all.take(FOLD) else all
            // a chosen word says what it hides in place of what it has
            val hides = s.results?.facets?.let { hidden(f, it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in shown) {
                    val hiding = if (v.selected) hides else null
                    IndexChip(
                        v.label, { find.toggle(f.id, v.id) }, symbol = v.symbol, count = v.count.takeIf { hiding == null }, selected = v.selected,
                        kept = "${f.id}=${v.id}" in s.prefs.sticky, faint = v.count == 0 && v.elsewhere > 0, hides = hiding, onLongClick = { find.keep(f.id, v.id) },
                    )
                }
                if (folded) IndexChip("+${all.size - shown.size}", onFold, faint = true, role = Role.Button)
                else if (unfolded && all.size > FOLD + 1) IndexChip("−", onFold, faint = true, role = Role.Button)
            }
        }
    }
}

/** A price or how recently checked as a segmented switch sunk into its tray. */
@Composable
private fun Segments(f: Facet, find: SearchSheetModel) {
    val c = indexColors()
    val outer = RoundedCornerShape(21.dp)
    val inner = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().height(42.dp).recessed(outer, c).padding(3.dp)) {
        for (v in f.values) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (v.selected) Modifier.raised(inner, c, y = 1.dp, blur = 4.dp) else Modifier.clip(inner))
                    .toggleable(v.selected, role = Role.Checkbox) { find.toggle(f.id, v.id) }
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(v.chip, style = boardText(12.5f, 600, 16f), color = if (v.selected) c.ink else c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v.count.toString(), style = boardText(11f, 500, 14f, tnum = true), color = c.faint, maxLines = 1)
            }
        }
    }
}
