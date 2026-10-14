package app.atlas.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.atlas.R
import app.atlas.model.Place
import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.places.PublicHolidays
import app.atlas.providers.Service
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.ToggleChip
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** A dialog card with a title and a close button, as every sheet of the search package opens. */
@Composable
internal fun SearchDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp), elevation = 0.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = Atlas.type.headline)
                SymbolButton("ui-x", stringResource(R.string.kit_close), onDismiss, size = 40.dp, elevation = 0.dp, background = Atlas.colors.cardMuted)
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/**
 * The last step before anything leaves the phone: the exact [text] that will be published, what happens to it
 * ([note]: public, its licence), and the button that sends it. [canSend] false says why it cannot go now.
 */
@Composable
internal fun ConfirmSend(
    title: String,
    text: String,
    note: String,
    confirm: String,
    canSend: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Atlas.colors
    SearchDialog(title, onDismiss) {
        Text(
            text,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.cardMuted).heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()).padding(14.dp),
            style = Atlas.type.mono, color = c.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Symbol("ui-globe", tint = c.inkSoft, size = 18.dp)
            Text(note, style = Atlas.type.caption, color = c.inkSoft)
        }
        if (!canSend) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui-cloud-off", tint = c.alert, size = 18.dp)
                Text(stringResource(R.string.place_send_offline), style = Atlas.type.caption, color = c.alert)
            }
        }
        PrimaryButton(confirm, onClick = { onConfirm(); onDismiss() }, modifier = Modifier.fillMaxWidth(), symbol = "ui-check", enabled = canSend)
    }
}

/** Every tag of [place] in monospace, with copy and a link to the element on openstreetmap.org. */
@Composable
internal fun RawTagsDialog(place: Place, onDismiss: () -> Unit) {
    val c = Atlas.colors
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val tags = remember(place) { place.tags.entries.sortedBy { it.key } }
    val text = remember(tags) { tags.joinToString("\n") { "${it.key}=${it.value}" } }
    SearchDialog("{ } " + stringResource(R.string.place_tags, tags.size), onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).background(c.cardMuted)
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((k, v) in tags) {
                Row {
                    Text(k, Modifier.width(140.dp), style = Atlas.type.mono.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(v, style = Atlas.type.mono, color = c.inkSoft)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.place_copy), symbol = "ui-receipt", onClick = { copy(context, place.name() ?: "tags", text) })
            place.id?.let { id -> Chip(stringResource(R.string.place_open_osm), symbol = "ui-map", onClick = { uri.openUri(id.url) }) }
        }
    }
}

/** Copies [text] to the clipboard under [label]. */
internal fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * The week of [hours] from [today]: each day's open time as a bar over 24 hours (green open, amber unknown) and in
 * words, today in bold, public holidays named; the raw `opening_hours` underneath.
 */
@Composable
internal fun WeekTableDialog(hours: OpeningHours, raw: String, today: LocalDate, ctx: OpeningHoursContext, country: String?, onDismiss: () -> Unit) {
    val c = Atlas.colors
    val locale = Locale.getDefault()
    val days = remember(hours, today) { (0 until 7).map { d -> today.plusDays(d.toLong()).let { it to hours.spans(it, ctx) } } }
    val holidays = remember(country) { country?.let { PublicHolidays.forCountry(it) } }
    SearchDialog(stringResource(R.string.place_hours), onDismiss) {
        for ((date, spans) in days) {
            val isToday = date == today
            val holiday = holidays?.holiday(date)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(56.dp)) {
                    Text(
                        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) },
                        style = if (isToday) Atlas.type.bodyStrong else Atlas.type.body, color = c.ink,
                    )
                    if (holiday != null) Text(holiday.localName, style = Atlas.type.caption, color = c.alert, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DayBar(spans, Modifier.weight(1f).height(14.dp))
                Text(
                    spanWords(spans) ?: stringResource(R.string.kit_closed),
                    Modifier.width(110.dp), style = if (isToday) Atlas.type.label else Atlas.type.caption,
                    color = if (spans.any { it.state == OpenState.OPEN }) c.ink else c.inkSoft, maxLines = 2,
                )
            }
        }
        Hairline()
        Text(raw, style = Atlas.type.mono, color = c.inkSoft)
    }
}

/** "9:00–17:00, 18:00–1:00" for a day's open spans; null when it is closed all day. */
internal fun spanWords(spans: List<OpenSpan>): String? {
    val open = spans.filter { it.state != OpenState.CLOSED }
    if (open.isEmpty()) return null
    if (open.size == 1 && open[0].start <= 0 && open[0].end >= 1440) return "24 h"
    return open.joinToString(", ") { s ->
        val a = "%d:%02d".format(Locale.ROOT, (s.start / 60) % 24, s.start % 60)
        val b = "%d:%02d".format(Locale.ROOT, (s.end / 60) % 24, s.end % 60)
        if (s.state == OpenState.UNKNOWN) "$a–$b?" else "$a–$b"
    }
}

/** One day as a bar: a pale track over 24 hours with the open (green) and unknown (amber) spans on it. */
@Composable
private fun DayBar(spans: List<OpenSpan>, modifier: Modifier) {
    val c = Atlas.colors
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(c.line, cornerRadius = r)
        for (s in spans) {
            if (s.state == OpenState.CLOSED) continue
            val a = s.start.coerceIn(0, 1440) / 1440f * size.width
            val b = s.end.coerceIn(0, 1440) / 1440f * size.width
            if (b <= a) continue
            drawRoundRect(if (s.state == OpenState.OPEN) c.open else c.caution, Offset(a, 0f), Size(b - a, size.height), r)
        }
        // noon
        drawLine(c.paper, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
    }
}

/** The lists to save [place] to, ticked where it is, and a field to start a new list. */
@Composable
internal fun SaveToDialog(lists: List<SavedList>, holding: Set<Int>, onToggle: (Int) -> Unit, onNew: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    var name by rememberSaveable { mutableStateOf("") }
    SearchDialog(stringResource(R.string.lists_save_to), onDismiss) {
        for (l in lists) {
            val on = l.id in holding
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Checkbox) { onToggle(l.id) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(18.dp).height(26.dp).clip(RoundedCornerShape(3.dp)).background(Color(l.colour)))
                Text(l.name ?: stringResource(R.string.lists_default), Modifier.weight(1f), style = Atlas.type.bodyStrong)
                Text(l.places.size.toString(), style = Atlas.type.caption, color = c.inkSoft)
                Symbol(if (on) "ui-check" else "ui-plus", tint = if (on) c.open else c.inkFaint, size = 20.dp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                name, { name = it.take(60) },
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
                singleLine = true, textStyle = Atlas.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.go),
                decorationBox = { inner ->
                    if (name.isEmpty()) Text(stringResource(R.string.lists_new_hint), style = Atlas.type.body, color = c.inkFaint)
                    inner()
                },
            )
            PillButton(onClick = { if (name.isNotBlank()) { onNew(name.trim()); name = "" } }) {
                Symbol("ui-plus", size = 18.dp)
                Text(stringResource(R.string.lists_new), style = Atlas.type.label)
            }
        }
    }
}

/**
 * Writing a Mangrove review: the stars, one line, and for places to eat how the food was had. "Review" moves on to
 * [ConfirmSend], which shows the exact review before anything is published.
 */
@Composable
internal fun ReviewDialog(placeName: String, eatery: Boolean, onReview: (stars: Int, text: String, how: Service?) -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    var stars by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var how by rememberSaveable { mutableStateOf<String?>(null) }
    SearchDialog(stringResource(R.string.place_review_title, placeName), onDismiss) {
        val words = (1..5).map { stringResource(R.string.place_review_stars, it) }
        StarPicker(stars, { stars = it }, { words[it - 1] })
        BasicTextField(
            text, { text = it.take(500) },
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
            textStyle = Atlas.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.go), maxLines = 4,
            decorationBox = { inner ->
                if (text.isEmpty()) Text(stringResource(R.string.place_review_hint), style = Atlas.type.body, color = c.inkFaint)
                inner()
            },
        )
        if (eatery) {
            Kicker(stringResource(R.string.place_review_how))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((s, label) in listOf(Service.EATEN_THERE to R.string.place_review_there, Service.TAKEAWAY to R.string.place_review_takeaway, Service.DELIVERED to R.string.place_review_delivered)) {
                    ToggleChip(stringResource(label), how == s.value, { how = if (it) s.value else null })
                }
            }
        }
        PrimaryButton(
            stringResource(R.string.place_review_next), onClick = { onReview(stars, text.trim(), Service.of(how)) },
            modifier = Modifier.fillMaxWidth(), symbol = "ui-star", enabled = stars in 1..5,
        )
    }
}

/** The review as it will be published: stars, words, the place, and how the food was had. */
internal fun reviewText(place: Place, stars: Int, opinion: String, how: Service?): String = buildString {
    append("★".repeat(stars)).append("☆".repeat(5 - stars)).append('\n')
    if (opinion.isNotBlank()) append('“').append(opinion.trim()).append("”\n")
    append(place.name() ?: "").append('\n')
    append(app.atlas.geo.CoordinateFormat.decimal(place.location))
    place.id?.let { append(" · ").append(it.url) }
    how?.let { append('\n').append(it.value.replace('_', ' ')) }
}

/** The time of day [t] as the boards write it: "7:00", "23:00". */
internal fun clock(t: LocalDateTime): String = "%d:%02d".format(Locale.ROOT, t.hour, t.minute)

/** A closing time as the boards write it: "→ 23:00", and midnight as "24:00" (the end of the day, not its start). */
internal fun untilClock(t: LocalDateTime): String = if (t.hour == 0 && t.minute == 0) "24:00" else clock(t)

/** The level of [tags] worth a chip ("L1", "L-1"): none for the ground floor or when not mapped. */
internal fun levelOf(tags: Map<String, String>): String? =
    tags["level"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }
