package app.atlas.ui.search

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import app.atlas.ui.eat.EatType
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.cssShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The page of the Lists board: warm sand. */
private val SAND = Color(0xFFEDE4D6)

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

/**
 * One notebook, as the Lists board writes it: a flat cover in the list's colour (corners 6 and 16), a darker 10 px edge
 * on its left, an elastic band 18 px from its right, the title in Caveat 28 and the count under it, put down at a tilt.
 */
@Composable
private fun Notebook(list: SavedList, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = listName(list)
    val count = pluralStringResource(R.plurals.lists_places, list.places.size, list.places.size)
    val tilt = TILTS[Math.floorMod(list.id, TILTS.size)]
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    Box(
        modifier
            .height(186.dp)
            .graphicsLayer { rotationZ = tilt; translationY = if (chosen) -6.dp.toPx() else 0f }
            .cssShadow(shape, y = 10.dp, blur = 20.dp, colour = Color(0x40000000))
            .clip(shape)
            .background(Color(list.colour))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $count"; selected = chosen },
    ) {
        Box(Modifier.fillMaxHeight().width(10.dp).background(Color(0x2E000000)))
        Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp).fillMaxHeight().width(8.dp).background(Color(0x59000000)))
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
            // The board's 28 px, for names that fit; a long word shrinks rather than breaks, and a hyphen may end a line.
            val size = when (val longest = name.split(' ', '-').maxOf { it.length }) {
                in 0..6 -> 28.sp
                7 -> 24.sp
                else -> if (longest > 10) 17.sp else 20.sp
            }
            Text(
                name.replace("-", "-\u200B"), style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size),
                color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(count, style = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp), color = Color.White.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}

/** The tilt of each notebook, in turn: the board's -3, 2, -1, 3, -2 and 1 degrees. */
private val TILTS = floatArrayOf(-3f, 2f, -1f, 3f, -2f, 1f)

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
            Text(
                listName(list), Modifier.weight(1f),
                style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp), color = colour,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
    // The board's row: 46 px high, corners 14, a 22 px disc, the name in 14 / 800 and the status in 12 / 700.
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(c.cardMuted).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = TextStyle(fontWeight = FontWeight.Black, fontSize = 11.sp), color = Color.White)
        }
        Text(line.summary.name, Modifier.weight(1f), style = Atlas.type.body.copy(fontWeight = FontWeight.ExtraBold, fontSize = 14.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        status?.let { Text(it, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp), color = if (line.tone == Tone.OPEN) c.open else if (line.tone == Tone.SOON) c.caution else c.inkSoft, maxLines = 1) }
        line.trailing?.let { Text(it, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp), color = c.inkSoft) }
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
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        // Only the format is kept across a turn of the phone (a long list's text would not fit a saved state): the file is made then.
        val ext = pending
        pending = null
        if (uri != null && ext != null) {
            val at = services.clock.instant()
            services.scope.launch(Dispatchers.IO) {
                runCatching {
                    val text = if (ext == "gpx") SavedExport.gpx(title, places, at, lang) else SavedExport.geoJson(title, places)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }
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
            for ((label, ext) in listOf("GPX" to "gpx", "GeoJSON" to "geojson")) {
                PrimaryButton(label, onClick = {
                    pending = ext
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
