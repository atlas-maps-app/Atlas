package app.atlas.ui.search

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.OpeningHours
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The Lists board's page (warm sand), ink, soft brown and the open place's green. */
private const val SAND = 0xFFEDE4D6
private const val INK = 0xFF2A2420
private const val BROWN = 0xFF7A6A58

/**
 * Saved places, as on the Lists board: each list a notebook with an elastic band and its title by hand, "+" for a
 * new one, and the chosen list open below with its places numbered, open or not; share it as text with links, or
 * export it as GPX or GeoJSON. Lists live on the phone only. Hold a notebook to empty or delete its list.
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(SAND))) {
        val page = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = page)
                .padding(top = boardShift()).windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            BoardTitleRow(stringResource(R.string.lists_title), INK, onBack = { nav.back() }) {
                val newList = stringResource(R.string.lists_new_list)
                Box(
                    Modifier.size(44.dp).boardBox(INK, round(22f)).clickable(role = Role.Button) { naming = true }
                        .semantics { contentDescription = newList },
                    contentAlignment = Alignment.Center,
                ) { BoardLine("+", boardText(22f, 800, 28.5f), 0xFFFFFFFF) }
            }
            Notebooks(lists, chosen?.id, onChoose = { chosenId = it }, onHold = { deleting = it }, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp))
            chosen?.let { l ->
                ListCard(l, nav, Modifier.padding(start = 16.dp, end = 16.dp, top = 40.dp))
                ListActions(l, Modifier.padding(start = 16.dp, end = 16.dp, top = 29.dp))
            }
            Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
            Text(
                stringResource(R.string.lists_footer),
                Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 30.dp),
                style = boardText(11f, 400, 14f, align = TextAlign.Center), color = Color(BROWN),
            )
        }
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

/** The notebooks, three to a row as the board's grid: 20 px from the sides, 14 px between them, rows 20 px apart. */
@Composable
private fun Notebooks(lists: List<SavedList>, chosen: Int?, onChoose: (Int) -> Unit, onHold: (SavedList) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for (row in lists.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (l in row) Notebook(l, l.id == chosen, Modifier.weight(1f), onClick = { onChoose(l.id) }, onHold = { onHold(l) })
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One notebook, as the Lists board writes it: 186 px, a flat cover in the list's colour (corners 6 and 16) with a
 * 10 px darker edge on its left (an inset shadow) and its drop shadow (0 10 20, black at 25 %), the elastic band 8 px
 * wide 18 px from its right, padded 16 · 14 · 14 · 22, the title in Caveat 28 / 700 and the count (11 / 800, white at
 * 85 %) at the foot, put down at its tilt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Notebook(list: SavedList, chosen: Boolean, modifier: Modifier, onClick: () -> Unit, onHold: () -> Unit) {
    val name = listName(list)
    val count = pluralStringResource(R.plurals.lists_places, list.places.size, list.places.size)
    val tilt = TILTS[Math.floorMod(list.id, TILTS.size)]
    val hold = stringResource(if (list.isDefault) R.string.lists_empty else R.string.lists_delete)
    Box(
        modifier
            .height(186.dp)
            .graphicsLayer { rotationZ = tilt }
            .boardBox(list.colour.toLong() and 0xFFFFFFFFL, COVER, listOf(CssShadow(0f, 10f, 20f, 0f, 0x40000000)))
            .combinedClickable(role = Role.Button, onLongClickLabel = hold, onLongClick = onHold, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $count"
                selected = chosen
                customActions = listOf(CustomAccessibilityAction(hold) { onHold(); true })
            },
    ) {
        Box(Modifier.fillMaxHeight().width(10.dp).background(Color(0x2E000000)))
        Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp).fillMaxHeight().width(8.dp).background(Color(0x59000000)))
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
            // The board's 28 px for names that fit; a long word shrinks rather than breaks (its "Quiet corners" is 25).
            val size = when (val longest = name.split(' ', '-').maxOf { it.length }) {
                in 0..6 -> 28f
                7 -> 25f
                else -> if (longest > 10) 17f else 21f
            }
            BoardBlock(name.replace("-", "-\u200B"), boardText(size, 700, size, family = CAVEAT), 0xFFFFFFFF, lines = 3)
            Spacer(Modifier.weight(1f))
            BoardLine(count, boardText(11f, 800, 14f), 0xD9FFFFFF)
        }
    }
}

private val COVER = CssCorners(6f, 16f, 16f, 6f)

/** The tilt of each notebook, in turn: the board's -3, 2, -1, 3, -2 and 1 degrees. */
private val TILTS = floatArrayOf(-3f, 2f, -1f, 3f, -2f, 1f)

/**
 * The chosen list open, as the board's white card (corners 24, 16 px in): its title in Caveat 26 in the list's
 * colour, what was added lately (12 / 800), and its places numbered, 6 px apart.
 */
@Composable
private fun ListCard(list: SavedList, nav: Navigator, modifier: Modifier) {
    val services = LocalServices.current
    val colour = list.colour.toLong() and 0xFFFFFFFFL
    val here = rememberHere()
    val lang = remember { Locale.getDefault().language }
    val now = rememberNow(list)
    val zone = remember { ZonedDateTime.now(services.clock).zone }
    Column(modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(24f)).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(Modifier.height(33.dp), verticalAlignment = Alignment.CenterVertically) {
            BoardLine(listName(list), boardText(26f, 700, 33f, family = CAVEAT), colour, Modifier.weight(1f))
            BoardLine(added(list, now.toLocalDate(), zone), boardText(12f, 800, 15.5f), BROWN)
        }
        if (list.places.isEmpty()) {
            BoardLine(stringResource(R.string.lists_empty_text), boardText(12f, 400, 15.5f), BROWN, Modifier.padding(top = 10.dp), lines = 4)
            return@Column
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/**
 * A saved place, as the board's row: 46 px, corners 14, on #F4EDE2, 12 px in; its number (11 / 900) on a 22 px disc of
 * the list's colour, the name 10 px after it in 14 / 800, and open (green) or when it opens (brown) in 12 / 700.
 */
@Composable
private fun SavedRow(n: Int, saved: SavedPlace, colour: Long, here: LatLon, lang: String, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val services = LocalServices.current
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val line = remember(saved, now) { PlaceLine.of(saved.place, here, lang, now, open = OpeningHours.parseOrNull(saved.place.tags["opening_hours"])?.status(now, ctx)) }
    val status = statusWords(line)
    Row(
        Modifier.fillMaxWidth().height(46.dp).boardBox(0xFFF4EDE2, round(14f)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(22.dp).boardBox(colour, round(11f)), contentAlignment = Alignment.Center) {
            BoardLine(n.toString(), boardText(11f, 900, 14f), 0xFFFFFFFF)
        }
        BoardLine(line.summary.name, boardText(14f, 800, 18f), INK, Modifier.weight(1f))
        status?.let { BoardLine(it, boardText(12f, 700, 15.5f), if (line.tone == Tone.OPEN) 0xFF12663A else if (line.tone == Tone.SOON) 0xFF8A5A00 else BROWN) }
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

/**
 * Share the list as text with links, and export it as GPX or GeoJSON to a file the user picks: the board's two 52 px
 * buttons 8 px apart, the first ink, the second white in a 1.5 px #D8CCBA ring, both 13 / 800.
 */
@Composable
private fun ListActions(list: SavedList, modifier: Modifier) {
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
    val off = places.isEmpty()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(184.5f).height(52.dp).alpha(if (off) 0.4f else 1f).boardBox(INK, round(26f))
                .clickable(role = Role.Button, enabled = !off) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, SavedExport.text(title, places, lang))
                    runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            contentAlignment = Alignment.Center,
        ) { BoardLine(shareLabel, boardText(13f, 800, 17f), 0xFFFFFFFF) }
        Box(
            Modifier.weight(187.5f).height(52.dp).alpha(if (off) 0.4f else 1f).boardBox(0xFFFFFFFF, round(26f), border = ring(1.5f, 0xFFD8CCBA))
                .clickable(role = Role.Button, enabled = !off, onClickLabel = exportLabel) { exporting = true },
            contentAlignment = Alignment.Center,
        ) { BoardLine(exportLabel, boardText(13f, 800, 17f), INK) }
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
