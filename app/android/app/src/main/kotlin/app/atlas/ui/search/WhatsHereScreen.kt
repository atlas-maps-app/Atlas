package app.atlas.ui.search

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.CoordinateFormat
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.StageMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Fact
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.Facts
import app.atlas.places.Localise
import app.atlas.places.OpeningHoursContext
import app.atlas.search.PlaceRecord
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.SymbolTile
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/** What "What's here" knows: the facts once found, and where a note for mappers stands. */
data class WhatsHereState(val facts: HereFacts? = null, val note: Sending = Sending.Idle)

/** Finds what is at [at] once, and leaves a note for mappers there when the user has confirmed its exact text. */
class WhatsHereViewModel(private val services: Services, private val at: LatLon, private val lang: String) : ViewModel() {
    private val mutable = MutableStateFlow(WhatsHereState())
    val state: StateFlow<WhatsHereState> = mutable.asStateFlow()

    val online: Boolean get() = !services.config.offline && services.connectivity.online.value

    init {
        viewModelScope.launch {
            val facts = try {
                HereFinder.find(services, at, lang, online)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HereFacts(null, null, emptyList(), null)
            }
            mutable.update { it.copy(facts = facts) }
        }
    }

    /** Leaves [text] as an anonymous OpenStreetMap note at the point. */
    fun leaveNote(text: String) {
        mutable.update { it.copy(note = Sending.Busy) }
        viewModelScope.launch {
            val r = try {
                Sending.Done(withContext(Dispatchers.IO) { services.osmNotes.create(at, text) }.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sending.Failed(e.message ?: e.toString())
            }
            mutable.update { it.copy(note = r) }
        }
    }
}

/**
 * What is at a point, the long press on the map: the map with a pin there, the address (from the region, the map's
 * labels, or online), the building or place it is in with its facts, the named things nearest, the point in every
 * coordinate format (tap to copy), and a note for mappers, sent only after the user has read its exact text.
 */
@Composable
fun WhatsHereScreen(args: Dest.WhatsHere, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val p = args.at
    val here = rememberHere()
    val vm = viewModel(key = "whatshere:" + args.route) { WhatsHereViewModel(services, p, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val c = Atlas.colors
    val map = remember { MapState(CameraPosition(p, 17.4, 0.0, 40.0)) }
    val sheet = rememberSheetState(SheetStop.HALF, setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL))
    val context = LocalContext.current
    var writing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val facts = state.facts
    val title = facts?.address?.line ?: facts?.spot?.let { Localise.name(it.tags, lang) } ?: CoordinateFormat.decimal(p)
    val shareLabel = stringResource(R.string.place_share)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        StageMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            background = c.paper,
            overlays = MapOverlays(
                markers = listOf(Marker("here", p, "ui-map-pin", 0xFFD23C3C, MarkerStyle.PIN, selected = true, priority = 1_000)),
                location = UserLocation(here, 15.0),
                measure = false,
            ),
            contentPadding = PaddingValues(top = 80.dp, bottom = maxHeight * 0.55f),
            onLongPress = { at -> nav.replace(Dest.WhatsHere(at.lat, at.lon)) },
        )
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SymbolButton("ui-chevron-left", stringResource(R.string.kit_back), { nav.back() }, size = 52.dp)
            Spacer(Modifier.weight(1f))
            SymbolButton("ui-share", shareLabel, {
                val text = listOfNotNull(facts?.address?.line, CoordinateFormat.decimal(p), CoordinateFormat.geoUri(p), osmLink(p)).joinToString("\n")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, size = 52.dp)
            val point = remember(p) { Place(null, p, emptyMap(), source = "point") }
            val lists by services.saved.lists.collectAsStateWithLifecycle()
            val saved = remember(lists, point.key) { lists.any { l -> l.places.any { it.place.key == point.key } } }
            SymbolButton("ui-bookmark", stringResource(if (saved) R.string.place_saved_plain else R.string.place_save_plain), {
                services.scope.launch(Dispatchers.IO) { runCatching { services.saved.toggle(point.copy(tags = facts?.address?.line?.let { mapOf("name" to it) } ?: emptyMap())) } }
            }, size = 52.dp, selected = saved)
        }
        Sheet(sheet, peekHeight = 170.dp, halfFraction = 0.55f, fullTopGap = 96.dp) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(title, facts, p, here)
                PrimaryButton(
                    walkOrDistance(Geo.distance(here, p)),
                    onClick = { nav.go(Dest.Route(p.lat, p.lon, title)) },
                    modifier = Modifier.fillMaxWidth(),
                    symbol = "ui-corner-up-right",
                )
                when {
                    facts == null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = c.inkSoft)
                    else -> {
                        facts.spot?.let { s -> SpotCard(s, lang) { services.remember(s); nav.go(Dest.Place.of(s)) } }
                        if (facts.nearby.isNotEmpty()) {
                            Kicker(stringResource(R.string.whatshere_nearby))
                            Card(padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), elevation = 0.dp, colour = c.cardMuted) {
                                val now = app.atlas.ui.kit.rememberNow(facts)
                                facts.nearby.forEachIndexed { i, n ->
                                    val line = remember(n, now) { PlaceLine.of(n, p, lang, now) }
                                    PlaceResultRow(line, now, lang, onClick = { services.remember(n); nav.go(Dest.Place.of(n)) })
                                    if (i < facts.nearby.lastIndex) Hairline()
                                }
                            }
                        } else if (facts.spot == null) {
                            Text(stringResource(R.string.whatshere_nothing_named), style = Atlas.type.caption, color = c.inkSoft)
                        }
                    }
                }
                Coordinates(p)
                NoteCard(state.note, onWrite = { writing = true })
                app.atlas.ui.kit.Chip(
                    stringResource(R.string.whatshere_quests), symbol = "ui-help", symbolTint = c.caution,
                    onClick = { nav.go(Dest.Quests(p.lat, p.lon)) },
                )
                facts?.origin?.let { o -> OriginLine(o, null) }
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(24.dp))
            }
        }
    }
    if (writing) {
        NoteDialog(onNext = { words -> writing = false; confirm = Quests.pointNote(words, aboutOf(facts, p)) }, onDismiss = { writing = false })
    }
    confirm?.let { text ->
        ConfirmSend(
            title = stringResource(R.string.place_note_confirm),
            text = "$text\n\nvia Atlas",
            note = stringResource(R.string.place_note_note),
            confirm = stringResource(R.string.place_note_send),
            canSend = vm.online,
            onConfirm = { vm.leaveNote(text) },
            onDismiss = { confirm = null },
        )
    }
}

/** What a note about the point starts with: its address, or its coordinates. */
private fun aboutOf(facts: HereFacts?, p: LatLon): String =
    listOfNotNull(facts?.address?.line, facts?.address?.area).joinToString(", ").ifEmpty { CoordinateFormat.decimal(p) }

/** The openstreetmap.org link to the point at street zoom. */
internal fun osmLink(p: LatLon): String =
    "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=19/%.6f/%.6f".format(Locale.ROOT, p.lat, p.lon, p.lat, p.lon)

/** The serif address (or the point), the quarter and town, and how far away it is. */
@Composable
private fun Header(title: String, facts: HereFacts?, p: LatLon, here: LatLon) {
    val c = Atlas.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SymbolTile("ui-map-pin", c.alert, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Kicker(stringResource(R.string.whatshere_title))
            Text(title, style = Atlas.type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val area = facts?.address?.area
            val far = shortDistance(Geo.distance(here, p))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (facts?.address?.origin == PlaceOrigin.ONLINE) Symbol("ui-cloud", tint = c.inkSoft, size = 14.dp)
                Text(listOfNotNull(area, far).joinToString(" · "), style = Atlas.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The place the point is in: its tile, name and kind, its facts as symbols, and its building's year, floors and height. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotCard(place: Place, lang: String, onOpen: () -> Unit) {
    val services = LocalServices.current
    val c = Atlas.colors
    val (symbol, colour) = remember(place) { symbolOf(place) }
    val facts = remember(place) {
        val now = LocalDateTime.now(services.clock)
        val ctx = OpeningHoursContext.forPlace(place.tags["addr:country"], ZonedDateTime.now(services.clock).zone, place.location.lat, place.location.lon)
        Facts.of(place, lang, now, ctx, geometry = PlaceRecord.geometryOf(place))
    }
    val building = remember(place) { buildingLines(place.tags) }
    Card(padding = PaddingValues(16.dp), elevation = 0.dp, colour = c.cardMuted, onClick = onOpen, onClickLabel = stringResource(R.string.whatshere_open_place)) {
        Kicker(stringResource(R.string.whatshere_at_spot))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolTile(symbol, Color(colour), size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(Localise.name(place.tags, lang) ?: kindOf(place, lang) ?: "", style = Atlas.type.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                kindOf(place, lang)?.let { Text(it, style = Atlas.type.caption, color = c.inkSoft, maxLines = 1) }
            }
            Symbol("ui-arrow-right", tint = c.go, size = 20.dp)
        }
        val symbols = facts.filter { it.mapped && it.kind !in NOT_SPOT }.distinctBy { it.symbol }.take(8)
        if (symbols.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in symbols) SpotFact(f)
            }
        }
        if (building.isNotEmpty()) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((s, label, value) in building) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(s, tint = c.inkSoft, size = 16.dp)
                        Text(stringResource(label), Modifier.width(96.dp), style = Atlas.type.caption, color = c.inkSoft)
                        Text(value, style = Atlas.type.body, color = c.ink, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Facts the spot card leaves to the place sheet. */
private val NOT_SPOT = setOf(FactKind.OPENING_HOURS, FactKind.PHONE, FactKind.WEBSITE, FactKind.WIKIPEDIA, FactKind.WIKIDATA, FactKind.CAPACITY)

@Composable
private fun SpotFact(f: Fact) {
    val c = Atlas.colors
    val tint = when (f.state) {
        FactState.YES -> c.ink
        FactState.NO, FactState.UNKNOWN -> c.inkFaint
        FactState.LIMITED -> c.caution
        FactState.ONLY -> c.open
    }
    FactSymbol(f.symbol, struck = f.state == FactState.NO, tint = tint, description = listOfNotNull(f.label ?: f.key, f.valueLabel ?: f.value).joinToString(": "), size = 18.dp)
}

/** The building's facts worth a line: when it was built, its floors and height, its architect and heritage. */
internal fun buildingLines(t: Map<String, String>): List<Triple<String, Int, String>> = buildList {
    (t["start_date"] ?: t["building:start_date"])?.let { add(Triple("ui-history", R.string.place_wiki_built, it)) }
    t["building:levels"]?.let { add(Triple("ui-layers", R.string.whatshere_levels, it)) }
    t["height"]?.let { h -> add(Triple("ui-arrow-up", R.string.whatshere_height, if (h.any { it.isLetter() }) h else "$h m")) }
    t["architect"]?.let { add(Triple("ui-building", R.string.place_wiki_architect, it)) }
    (t["heritage:operator"] ?: t["heritage"]?.let { "heritage $it" })?.let { op ->
        add(Triple("ui-landmark", R.string.whatshere_heritage, listOfNotNull(op, t["ref:rce"]?.let { "#$it" }).joinToString(" ")))
    }
}

/** The point in every format, each a row to tap and copy. */
@Composable
private fun Coordinates(p: LatLon) {
    val c = Atlas.colors
    val context = LocalContext.current
    val rows = remember(p) {
        listOfNotNull(
            "Decimal" to CoordinateFormat.decimal(p),
            "DMS" to CoordinateFormat.dms(p),
            "DM" to CoordinateFormat.dm(p),
            "Plus code" to CoordinateFormat.plusCode(p),
            CoordinateFormat.utm(p)?.let { "UTM" to it },
            CoordinateFormat.mgrs(p)?.let { "MGRS" to it },
            "geo:" to CoordinateFormat.geoUri(p),
        )
    }
    var copied by remember { mutableStateOf<String?>(null) }
    val copyLabel = stringResource(R.string.place_copy)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(stringResource(R.string.whatshere_coordinates))
        Card(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), elevation = 0.dp, colour = c.cardMuted) {
            rows.forEachIndexed { i, (label, value) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClickLabel = copyLabel) {
                        copy(context, label, value)
                        copied = label
                    }.semantics(mergeDescendants = true) { contentDescription = "$label $value" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label, Modifier.width(76.dp), style = Atlas.type.caption, color = c.inkSoft)
                    Text(value, Modifier.weight(1f), style = Atlas.type.mono.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (copied == label) Symbol("ui-check", tint = c.open, size = 16.dp) else CopyGlyph(c.inkFaint, 16.dp)
                }
                if (i < rows.lastIndex) Hairline()
            }
        }
    }
}

/** "Leave a note for mappers": what it does, and where the last note stands. */
@Composable
private fun NoteCard(note: Sending, onWrite: () -> Unit) {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(questPaper()).clickable(role = Role.Button, onClick = onWrite).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui-mail", tint = questInk(), size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.whatshere_note), style = Atlas.type.bodyStrong, color = c.ink)
            val line = when (note) {
                Sending.Idle -> stringResource(R.string.whatshere_note_text)
                Sending.Busy -> stringResource(R.string.place_note_sending)
                is Sending.Done -> stringResource(R.string.place_note_sent)
                is Sending.Failed -> stringResource(R.string.place_note_failed, note.why)
            }
            Text(line, style = Atlas.type.caption, color = if (note is Sending.Failed) c.alert else c.inkSoft, maxLines = 3)
        }
    }
}

/** The words of a note for mappers; "Next" shows the exact note before anything is sent. */
@Composable
private fun NoteDialog(onNext: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    var words by rememberSaveable { mutableStateOf("") }
    SearchDialog(stringResource(R.string.whatshere_note), onDismiss) {
        Text(stringResource(R.string.whatshere_note_help), style = Atlas.type.caption, color = c.inkSoft)
        BasicTextField(
            words, { words = it.take(1_000) },
            Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
            textStyle = Atlas.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.go),
            decorationBox = { inner ->
                if (words.isEmpty()) Text(stringResource(R.string.whatshere_note_hint), style = Atlas.type.body, color = c.inkFaint)
                inner()
            },
        )
        PrimaryButton(stringResource(R.string.place_review_next), onClick = { onNext(words.trim()) }, modifier = Modifier.fillMaxWidth(), symbol = "ui-mail", enabled = words.isNotBlank())
    }
}
