package app.atlas.ui.search

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.OpeningHours
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.softShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The page of the Lists board: warm sand. */
private val SAND = Color(0xFFEDE5D8)

/**
 * Saved places, as on the Lists board: each list a notebook with an elastic band and its title by hand, "+" for a
 * new one, and the chosen list open below with its places numbered, open or not; share it as text with links, or
 * export it as GPX or GeoJSON. Lists live on the phone only.
 */
@Composable
fun ListsScreen(args: Dest.Lists, nav: Navigator) {
    val services = LocalServices.current
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    var chosenId by rememberSaveable { mutableStateOf(args.id) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedList?>(null) }
    val chosen = lists.firstOrNull { it.id == chosenId } ?: lists.firstOrNull { it.places.isNotEmpty() } ?: lists.firstOrNull()
    val c = Atlas.colors
    val scope = rememberCoroutineScope()

    AtlasScreen(
        stringResource(R.string.lists_title), onBack = { nav.back() }, italic = true, background = if (c.isDark) c.paper else SAND,
        actions = { SymbolButton("ui-plus", stringResource(R.string.lists_new_list), { naming = true }, size = 52.dp, background = c.pill, tint = c.onPill) },
    ) {
        Notebooks(lists, chosen?.id, onChoose = { chosenId = it })
        chosen?.let { l ->
            ListCard(l, nav, onDelete = { deleting = l })
            ListActions(l)
        }
        HonestyFooter(stringResource(R.string.lists_footer))
        Spacer(Modifier.heightIn(min = 16.dp))
    }
    if (naming) {
        NewListDialog(onNew = { name ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { services.saved.createList(name) }.getOrNull() }?.let { chosenId = it }
            }
        }, onDismiss = { naming = false })
    }
    deleting?.let { l ->
        SearchDialog(stringResource(if (l.isDefault) R.string.lists_empty_q else R.string.lists_delete_q, listName(l)), { deleting = null }) {
            Text(pluralStringResource(R.plurals.lists_places, l.places.size, l.places.size), style = Atlas.type.body, color = c.inkSoft)
            PrimaryButton(
                stringResource(if (l.isDefault) R.string.lists_empty else R.string.lists_delete), onClick = {
                    services.scope.launch(Dispatchers.IO) { runCatching { services.saved.deleteList(l.id) } }
                    deleting = null
                },
                modifier = Modifier.fillMaxWidth(), symbol = "ui-trash", colour = c.alert,
            )
        }
    }
}

@Composable
private fun listName(l: SavedList): String = l.name ?: stringResource(R.string.lists_default)

/** The notebooks, three to a row, each tilted a little its own way. */
@Composable
private fun Notebooks(lists: List<SavedList>, chosen: Int?, onChoose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
        for (row in lists.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (l in row) Notebook(l, l.id == chosen, Modifier.weight(1f)) { onChoose(l.id) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One notebook: a cloth cover in the list's colour, its spine, an elastic band, the title by hand and the count. */
@Composable
private fun Notebook(list: SavedList, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = listName(list)
    val count = pluralStringResource(R.plurals.lists_places, list.places.size, list.places.size)
    val cover = Color(list.colour)
    val tilt = TILTS[Math.floorMod(list.id, TILTS.size)]
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 14.dp, bottomEnd = 14.dp)
    Box(
        modifier
            .aspectRatio(0.74f)
            .graphicsLayer { rotationZ = tilt; translationY = if (chosen) -6.dp.toPx() else 0f }
            .softShadow(shape, Color(0x55301E0A), if (chosen) 16.dp else 9.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $count"; selected = chosen },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawNotebook(cover) }
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 14.dp)) {
            // Handwriting shrinks for long words rather than breaking them; a hyphen may end a line.
            val longest = name.split(' ', '-').maxOf { it.length }
            val size = when {
                longest > 9 -> 17.sp
                longest > 7 -> 20.sp
                else -> 25.sp
            }
            Text(
                name.replace("-", "-\u200B"), style = Atlas.type.displayItalic.copy(fontSize = size, lineHeight = size), color = Color.White,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(count, style = Atlas.type.caption.copy(fontSize = 11.sp), color = Color.White.copy(alpha = 0.92f), maxLines = 1)
        }
    }
}

/** The cover: cloth with a soft light from the top, a darker spine, page edges and the elastic band. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNotebook(cover: Color) {
    val w = size.width
    val h = size.height
    val dark = lerp(cover, Color.Black, 0.28f)
    val light = lerp(cover, Color.White, 0.10f)
    drawRect(Brush.verticalGradient(listOf(light, cover, lerp(cover, Color.Black, 0.08f))))
    // The spine: a darker band with a crease.
    val spine = w * 0.11f
    drawRect(Brush.horizontalGradient(listOf(dark, lerp(cover, Color.Black, 0.18f)), 0f, spine), size = Size(spine, h))
    drawLine(lerp(cover, Color.Black, 0.4f), Offset(spine, 0f), Offset(spine, h), strokeWidth = 1.5f)
    // Page edges at the fore-edge, under the cover's lip.
    val pages = Color(0xFFF4ECDD)
    drawRect(pages.copy(alpha = 0.85f), Offset(w - 3.dp.toPx(), 6.dp.toPx()), Size(1.5.dp.toPx(), h - 12.dp.toPx()))
    // The elastic band, with a highlight on its left edge.
    val band = w * 0.80f
    val bw = w * 0.065f
    drawRect(lerp(cover, Color.Black, 0.5f), Offset(band, 0f), Size(bw, h))
    drawRect(Color.White.copy(alpha = 0.12f), Offset(band, 0f), Size(bw * 0.25f, h))
    // Cloth grain: faint diagonal threads, kept on the cover.
    val step = 6.dp.toPx()
    clipRect(0f, 0f, w, h) {
        var x = -h
        while (x < w) {
            drawLine(Color.White.copy(alpha = 0.035f), Offset(x, h), Offset(x + h, 0f), strokeWidth = 1f)
            x += step
        }
    }
}

/** The tilt of each notebook, in turn, as if put down by hand. */
private val TILTS = floatArrayOf(-2.2f, 1.6f, -1.0f, 2.4f, -1.8f, 0.9f)

/** The chosen list open: its title in its colour, what was added lately, its places numbered with their status. */
@Composable
private fun ListCard(list: SavedList, nav: Navigator, onDelete: () -> Unit) {
    val services = LocalServices.current
    val c = Atlas.colors
    val colour = Color(list.colour)
    val here = rememberHere()
    val lang = remember { Locale.getDefault().language }
    val now = rememberNow(list)
    val zone = remember { ZonedDateTime.now(services.clock).zone }
    Card(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listName(list), Modifier.weight(1f), style = Atlas.type.title.copy(fontStyle = FontStyle.Italic), color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(added(list, now.toLocalDate(), zone), style = Atlas.type.label, color = c.inkSoft)
            SymbolButton("ui-trash", stringResource(if (list.isDefault) R.string.lists_empty else R.string.lists_delete), onDelete, size = 40.dp, elevation = 0.dp, background = Color.Transparent, tint = c.inkFaint)
        }
        if (list.places.isEmpty()) {
            Text(stringResource(R.string.lists_empty_text), Modifier.padding(top = 8.dp), style = Atlas.type.body, color = c.inkSoft)
            return@Card
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.places.forEachIndexed { i, s ->
                SavedRow(i + 1, s, colour, here, lang, now) { services.remember(s.place); nav.go(Dest.Place.of(s.place)) }
            }
        }
    }
}

/** "2 added today", or when the last one was added. */
@Composable
private fun added(list: SavedList, today: LocalDate, zone: ZoneId): String {
    val days = list.places.map { it.addedAt.atZone(zone).toLocalDate() }
    val todayCount = days.count { it == today }
    return when {
        todayCount > 0 -> pluralStringResource(R.plurals.lists_added_today, todayCount, todayCount)
        days.isEmpty() -> ""
        else -> stringResource(R.string.lists_added_on, days.max().let { "%d %s".format(Locale.getDefault(), it.dayOfMonth, it.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())) })
    }
}

/** A saved place: its number on a disc of the list's colour, its name, and open or when it opens. */
@Composable
private fun SavedRow(n: Int, saved: SavedPlace, colour: Color, here: LatLon, lang: String, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val services = LocalServices.current
    val c = Atlas.colors
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val line = remember(saved, now) { PlaceLine.of(saved.place, here, lang, now, open = OpeningHours.parseOrNull(saved.place.tags["opening_hours"])?.status(now, ctx)) }
    val status = statusWords(line)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(c.cardMuted).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = Atlas.type.label, color = Color.White)
        }
        Text(line.summary.name, Modifier.weight(1f), style = Atlas.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        status?.let { Text(it, style = Atlas.type.label, color = if (line.tone == Tone.OPEN) c.open else if (line.tone == Tone.SOON) c.caution else c.inkSoft, maxLines = 1) }
        line.trailing?.let { Text(it, style = Atlas.type.label, color = c.inkSoft) }
    }
}

/** "open", "opens 18:00", "closes in 20 min" or "closed" for a row; null when the hours are not known. */
@Composable
internal fun statusWords(line: PlaceLine): String? = when (val w = line.words) {
    is app.atlas.ui.kit.OpenWords.OpenUntil -> stringResource(R.string.kit_open)
    app.atlas.ui.kit.OpenWords.Open, app.atlas.ui.kit.OpenWords.Always -> stringResource(R.string.kit_open)
    is app.atlas.ui.kit.OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, w.minutes)
    is app.atlas.ui.kit.OpenWords.Opens -> stringResource(R.string.place_opens, if (w.sameDay) clock(w.at) else w.at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + clock(w.at))
    app.atlas.ui.kit.OpenWords.Closed -> stringResource(R.string.kit_closed)
    null -> null
}

/** Share the list as text with links, and export it as GPX or GeoJSON to a file the user picks. */
@Composable
private fun ListActions(list: SavedList) {
    val services = LocalServices.current
    val context = LocalContext.current
    val c = Atlas.colors
    val title = listName(list)
    val lang = remember { Locale.getDefault().language }
    val places = remember(list) { list.places.map { it.place } }
    val shareLabel = stringResource(R.string.lists_share)
    val exportLabel = stringResource(R.string.lists_export)
    var exporting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val text = pending
        pending = null
        if (uri != null && text != null) {
            services.scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(onClick = {
            if (places.isEmpty()) return@PillButton
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, SavedExport.text(title, places, lang))
            runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, modifier = Modifier.weight(1f).heightIn(min = 54.dp).alpha(if (places.isEmpty()) 0.4f else 1f)) {
            Spacer(Modifier.weight(1f))
            Symbol("ui-share", size = 18.dp)
            Text(shareLabel, style = Atlas.type.bodyStrong)
            Spacer(Modifier.weight(1f))
        }
        Row(
            Modifier.weight(1f).heightIn(min = 54.dp).clip(CircleShape).background(c.card).border(1.5.dp, c.ink, CircleShape)
                .clickable(role = Role.Button, enabled = places.isNotEmpty(), onClickLabel = exportLabel) { exporting = true }
                .semantics(mergeDescendants = true) { contentDescription = exportLabel }.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("ui-download", tint = if (places.isEmpty()) c.inkFaint else c.ink, size = 18.dp)
            Text("GPX · GeoJSON", style = Atlas.type.bodyStrong, color = if (places.isEmpty()) c.inkFaint else c.ink, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
    if (exporting) {
        SearchDialog(stringResource(R.string.lists_export), { exporting = false }) {
            Text(stringResource(R.string.lists_export_text), style = Atlas.type.caption, color = c.inkSoft)
            val now = remember { services.clock.instant() }
            for ((label, ext, make) in listOf<Triple<String, String, () -> String>>(
                Triple("GPX", "gpx") { SavedExport.gpx(title, places, now, lang) },
                Triple("GeoJSON", "geojson") { SavedExport.geoJson(title, places) },
            )) {
                PrimaryButton(label, onClick = {
                    pending = make()
                    exporting = false
                    runCatching { save.launch(SavedExport.fileName(title, ext)) }
                }, modifier = Modifier.fillMaxWidth(), symbol = "ui-download", colour = c.pill)
            }
        }
    }
}

/** The name of a new list, then "Add". */
@Composable
private fun NewListDialog(onNew: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    var name by rememberSaveable { mutableStateOf("") }
    SearchDialog(stringResource(R.string.lists_new_list), onDismiss) {
        BasicTextField(
            name, { name = it.take(60) },
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
            singleLine = true, textStyle = Atlas.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.go),
            decorationBox = { inner ->
                if (name.isEmpty()) Text(stringResource(R.string.lists_new_example), style = Atlas.type.body, color = c.inkFaint)
                inner()
            },
        )
        PrimaryButton(stringResource(R.string.lists_new), onClick = { onNew(name.trim()); onDismiss() }, modifier = Modifier.fillMaxWidth(), symbol = "ui-plus", enabled = name.isNotBlank())
    }
}
