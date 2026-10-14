package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.FactKind
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.softShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Locale

/** What the quests screen knows: the quests around (null while looking), where they came from, and the answers sent. */
data class QuestsState(
    val quests: List<PlaceQuest>? = null,
    val origin: PlaceOrigin? = null,
    /** Quests skipped here, by id. */
    val skipped: Set<String> = emptySet(),
    /** Answers on their way or sent, by quest id. */
    val sending: Map<String, Sending> = emptyMap(),
) {
    /** The quest to answer now: the nearest one not skipped and not answered. */
    val current: PlaceQuest? get() = quests?.firstOrNull { it.id !in skipped && it.id !in sending }

    /** The ones after it. */
    val next: List<PlaceQuest> get() = quests.orEmpty().filter { it.id !in skipped && it.id !in sending && it !== current }
}

/** Finds the quests around once; an answer goes out as an OpenStreetMap note only after the user confirmed its text. */
class QuestsViewModel(private val services: Services, private val at: LatLon, private val lang: String) : ViewModel() {
    private val mutable = MutableStateFlow(QuestsState())
    val state: StateFlow<QuestsState> = mutable.asStateFlow()

    val online: Boolean get() = !services.config.offline && services.connectivity.online.value

    init {
        viewModelScope.launch {
            val (found, origin) = try {
                QuestFinder.near(services, at, lang, online)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<PlaceQuest>() to null
            }
            mutable.update { it.copy(quests = found, origin = origin) }
        }
    }

    fun skip(q: PlaceQuest) = mutable.update { it.copy(skipped = it.skipped + q.id) }

    /** Leaves the note for [q] answered [yes]; the screen has shown its exact text first. */
    fun answer(q: PlaceQuest, yes: Boolean) {
        mutable.update { it.copy(sending = it.sending + (q.id to Sending.Busy)) }
        viewModelScope.launch {
            val r = try {
                val note = withContext(Dispatchers.IO) { services.osmNotes.create(q.place.location, Quests.noteText(q.place, q.quest, yes)) }
                withContext(Dispatchers.IO) { runCatching { services.saved.recordAnswer(q.place.key, q.quest.key, yes, note.url) } }
                Sending.Done(note.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sending.Failed(e.message ?: e.toString())
            }
            mutable.update { it.copy(sending = it.sending + (q.id to r)) }
        }
    }
}

/**
 * Map quests, as on the Quests and FillIn boards: small questions about the places around that nobody has mapped yet,
 * each with the thing drawn and its unknown part a ghost ("Is there a terrace?"), answered Yes, No or skipped. An answer
 * becomes an anonymous OpenStreetMap note for the mappers nearby, sent only after the exact text is shown; Atlas edits
 * nothing itself. The map shows where the questions are; the count is of answers from this phone.
 */
@Composable
fun QuestsScreen(args: Dest.Quests, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val phoneHere = rememberHere()
    val here = args.at ?: phoneHere
    val vm = viewModel(key = "quests:" + args.route) { QuestsViewModel(services, here, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    val answers by services.saved.answers.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<PlaceQuest, Boolean>?>(null) }
    val c = Atlas.colors
    val month = remember { YearMonth.now(services.clock) }
    val zone = remember { ZonedDateTime.now(services.clock).zone }
    val thisMonth = answers.count { YearMonth.from(it.at.atZone(zone)) == month }

    AtlasScreen(
        stringResource(R.string.quests_title), onBack = { nav.back() }, italic = true,
        actions = {
            Row(
                Modifier.heightIn(min = 40.dp).clip(CircleShape).background(c.pill).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol("ui-check", tint = c.onPill, size = 16.dp)
                Text(answers.size.toString(), style = Atlas.type.label, color = c.onPill)
            }
        },
    ) {
        QuestMap(here, state)
        val q = state.current
        when {
            state.quests == null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = c.inkSoft)
            state.origin == null && state.quests.isNullOrEmpty() -> StateMessage(
                "ui-cloud-off", stringResource(R.string.search_no_regions), text = stringResource(R.string.quests_no_region), tint = c.go,
                action = { app.atlas.ui.kit.Chip(stringResource(R.string.search_download), symbol = "ui-download", onClick = { nav.go(Dest.RegionPicker(here.lat, here.lon)) }) },
            )
            q == null -> StateMessage("ui-check", stringResource(R.string.quests_done), text = stringResource(R.string.quests_done_text), tint = c.open)
            else -> QuestCard(
                q, lang, state.sending.values.lastOrNull(),
                onYes = { confirm = q to true }, onNo = { confirm = q to false }, onSkip = { vm.skip(q) },
                onPlace = { services.remember(q.place); nav.go(Dest.Place.of(q.place)) },
            )
        }
        Impact(thisMonth, answers.size)
        val next = state.next
        if (next.isNotEmpty()) {
            Kicker(stringResource(R.string.quests_next))
            Card(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), elevation = 0.dp) {
                next.take(8).forEachIndexed { i, n ->
                    NextRow(n, lang, onClick = { services.remember(n.place); nav.go(Dest.Place.of(n.place)) })
                    if (i < minOf(next.size, 8) - 1) app.atlas.ui.kit.Hairline()
                }
            }
        }
        state.origin?.let { OriginLine(it, null) }
        HonestyFooter(stringResource(R.string.quests_footer))
    }
    confirm?.let { (q, yes) ->
        ConfirmSend(
            title = stringResource(R.string.place_note_confirm),
            text = Quests.noteText(q.place, q.quest, yes) + "\n\nvia Atlas",
            note = stringResource(R.string.place_note_note),
            confirm = stringResource(R.string.place_note_send),
            canSend = vm.online,
            onConfirm = { vm.answer(q, yes) },
            onDismiss = { confirm = null },
        )
    }
}

/** A small map with an amber pin at each question, the current one large. */
@Composable
private fun QuestMap(here: LatLon, state: QuestsState) {
    val services = LocalServices.current
    val c = Atlas.colors
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val map = remember { MapState(CameraPosition(here, 16.2)) }
    val current = state.current
    val markers = remember(state.quests, current) {
        state.quests.orEmpty().distinctBy { it.place.key }.mapIndexed { i, q ->
            val now = current != null && q.place.key == current.place.key
            Marker(
                q.place.key, q.place.location, q.quest.fact.symbol, 0xFFE29A12, if (now) MarkerStyle.PIN else MarkerStyle.DOT,
                selected = now, priority = if (now) 2_000 else 1_000 - i, place = q.place,
            )
        }
    }
    LaunchedEffect(state.quests) {
        val pts = state.quests.orEmpty().take(15).map { it.place.location }
        snapshotFlow { map.visibleBounds }.first { it != null }
        BBox.of(pts + here)?.let { runCatching { map.fit(it.buffer(40.0), 24) } }
    }
    Box(Modifier.fillMaxWidth().height(200.dp).softShadow(RoundedCornerShape(24.dp), c.shadow, 8.dp).clip(RoundedCornerShape(24.dp))) {
        AtlasMap(map, tiles, Modifier.fillMaxWidth().height(200.dp), overlays = MapOverlays(markers = markers, location = UserLocation(here, 15.0), measure = false))
    }
}

/** The question: where, the thing drawn with its unknown part a ghost, the question in words, Yes, No and skip. */
@Composable
private fun QuestCard(q: PlaceQuest, lang: String, last: Sending?, onYes: () -> Unit, onNo: () -> Unit, onSkip: () -> Unit, onPlace: () -> Unit) {
    val c = Atlas.colors
    val name = app.atlas.places.Localise.name(q.place.tags, lang) ?: ""
    val question = questionOf(q.quest)
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onPlace), verticalAlignment = Alignment.CenterVertically) {
            Kicker("$name · ${shortDistance(q.distanceM)}", Modifier.weight(1f))
            Symbol("ui-arrow-right", tint = c.inkFaint, size = 16.dp)
        }
        QuestArt(q.quest.fact.kind, q.quest.fact.symbol, question, Modifier.fillMaxWidth().height(170.dp).padding(vertical = 8.dp))
        Text(question, Modifier.fillMaxWidth(), style = Atlas.type.headline, color = c.ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.quests_only_see), Modifier.fillMaxWidth().padding(top = 4.dp), style = Atlas.type.caption, color = c.inkSoft, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnswerPill(stringResource(R.string.quests_yes), "ui-check", c.open, Modifier.weight(1f), onYes)
            AnswerPill(stringResource(R.string.quests_no), "ui-x", c.alert, Modifier.weight(1f), onNo)
        }
        Text(
            stringResource(R.string.quests_skip),
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onSkip).padding(10.dp),
            style = Atlas.type.label, color = c.inkSoft, textAlign = TextAlign.Center,
        )
        when (last) {
            Sending.Busy -> Text(stringResource(R.string.place_note_sending), style = Atlas.type.caption, color = c.inkSoft)
            is Sending.Done -> Text(stringResource(R.string.place_note_sent), style = Atlas.type.caption, color = c.open)
            is Sending.Failed -> Text(stringResource(R.string.place_note_failed, last.why), style = Atlas.type.caption, color = c.alert)
            else -> Unit
        }
    }
}

@Composable
private fun AnswerPill(text: String, symbol: String, colour: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    Row(
        modifier.heightIn(min = 54.dp).clip(CircleShape).background(c.card).border(1.5.dp, colour, CircleShape).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = colour, size = 20.dp, weight = 1.3f)
        Text(text, style = Atlas.type.name, color = c.ink)
    }
}

/** The dark card of what the answers do: how many this month from this phone, and who reads them. */
@Composable
private fun Impact(thisMonth: Int, all: Int) {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.pill).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(thisMonth.toString(), style = Atlas.type.numeral, color = c.onPill)
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.quests_month, thisMonth, thisMonth), style = Atlas.type.bodyStrong, color = c.onPill)
            Text(pluralStringResource(R.plurals.quests_all, all, all), style = Atlas.type.caption, color = c.onPill.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun NextRow(q: PlaceQuest, lang: String, onClick: () -> Unit) {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(questPaper()), contentAlignment = Alignment.Center) {
            Symbol(q.quest.fact.symbol, tint = questInk(), size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(app.atlas.places.Localise.name(q.place.tags, lang) ?: "", style = Atlas.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(questionOf(q.quest), style = Atlas.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(shortDistance(q.distanceM), style = Atlas.type.label, color = c.inkSoft)
    }
}

/** The question in words, for the kinds a passer-by is asked most; else the fact's name with a "?". */
@Composable
internal fun questionOf(q: Quest): String = when (q.fact.kind) {
    FactKind.INTERNET -> stringResource(R.string.quest_q_wifi)
    FactKind.OUTDOOR_SEATING -> stringResource(R.string.quest_q_terrace)
    FactKind.WHEELCHAIR -> stringResource(R.string.quest_q_wheelchair)
    FactKind.TOILETS -> stringResource(R.string.quest_q_toilets)
    FactKind.TOILETS_WHEELCHAIR -> stringResource(R.string.quest_q_toilets_wheelchair)
    FactKind.TAKEAWAY -> stringResource(R.string.quest_q_takeaway)
    FactKind.LIT -> stringResource(R.string.quest_q_lit)
    FactKind.DIET -> stringResource(R.string.quest_q_vegetarian)
    FactKind.CHANGING_TABLE -> stringResource(R.string.quest_q_changing)
    FactKind.RESERVATION -> stringResource(R.string.quest_q_reservation)
    else -> (q.fact.label ?: q.key.replace('_', ' ')).trimEnd('?') + "?"
}
