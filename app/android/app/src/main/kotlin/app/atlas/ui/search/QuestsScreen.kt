package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import app.atlas.mapview.MapHit
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
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
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
    val current: PlaceQuest? get() = quests?.firstOrNull { open(it) }

    /** The ones after it. */
    val next: List<PlaceQuest> get() = quests.orEmpty().filter { open(it) && it !== current }

    /** A quest is open until it is skipped or its answer is on its way or sent; an answer that failed leaves it open. */
    private fun open(q: PlaceQuest): Boolean = q.id !in skipped && sending[q.id].let { it == null || it is Sending.Failed }
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

/** The Quests board's page, ink, greens, amber and the soft green-grey of its words. */
private const val PAGE = 0xFFF1F4EC
private const val INK = 0xFF1B2A1E
private const val SOFT = 0xFF6A7E6E
private const val AMBER = 0xFFF2C230

/**
 * Map quests, as on the Quests board: small questions about the places around that nobody has mapped yet, each with
 * the thing drawn and its unknown part a ghost ("Is there a terrace?"), answered Yes, No or skipped. An answer becomes
 * an anonymous OpenStreetMap note for the mappers nearby, sent only after the exact text is shown; Atlas edits nothing
 * itself. The map shows where the questions are (tap a pin to open its place); the count is of answers from this phone.
 * The board's numbers: the title row at 42, the map 380 × 230 at 100, the question card at 344, the dark card 37.5 px
 * under it, the footer 64 px from the foot.
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
    val month = remember { YearMonth.now(services.clock) }
    val zone = remember { ZonedDateTime.now(services.clock).zone }
    val thisMonth = answers.count { YearMonth.from(it.at.atZone(zone)) == month }
    val openPlace: (app.atlas.model.Place) -> Unit = { p -> services.remember(p); nav.go(Dest.Place.of(p)) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(PAGE))) {
        val page = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = page)
                .padding(top = boardShift()).windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            BoardTitleRow(stringResource(R.string.quests_title), INK, onBack = { nav.back() }, size = 32f) {
                val d = pluralStringResource(R.plurals.quests_all, answers.size, answers.size)
                Row(
                    Modifier.height(34.dp).boardBox(INK, round(17f)).semantics(mergeDescendants = true) { contentDescription = d }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BoardSymbol("ui.check", 14f, AMBER)
                    BoardLine(answers.size.toString(), boardText(12f, 900, 15.5f, tnum = true), AMBER)
                }
            }
            QuestMap(here, state, onPlace = openPlace, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
            val q = state.current
            val card = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)
            when {
                state.quests == null -> BoardLine(stringResource(R.string.kit_loading), boardText(12f, 400, 15.5f), SOFT, card)
                state.origin == null && state.quests.isNullOrEmpty() -> StateMessage(
                    "ui-cloud-off", stringResource(R.string.search_no_regions), card, text = stringResource(R.string.quests_no_region), tint = Color(0xFF2E5BFF),
                    action = { app.atlas.ui.kit.Chip(stringResource(R.string.search_download), symbol = "ui-download", onClick = { nav.go(Dest.RegionPicker(here.lat, here.lon)) }) },
                )
                q == null -> StateMessage("ui-check", stringResource(R.string.quests_done), card, text = stringResource(R.string.quests_done_text), tint = Color(0xFF2F7A3A))
                else -> QuestCard(
                    q, lang, state.sending.values.lastOrNull(),
                    onYes = { confirm = q to true }, onNo = { confirm = q to false }, onSkip = { vm.skip(q) },
                    onPlace = { openPlace(q.place) },
                    modifier = card,
                )
            }
            Impact(thisMonth, answers.size, Modifier.padding(start = 16.dp, end = 16.dp, top = 37.5.dp))
            if (state.origin == PlaceOrigin.ONLINE) Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp)) { OriginLine(PlaceOrigin.ONLINE, null) }
            Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
            Text(
                stringResource(R.string.quests_footer),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 64.dp),
                style = boardText(11f, 400, 14f, align = TextAlign.Center), color = Color(SOFT),
            )
        }
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

/** The board's street card (380 × 230, corners 24, #E8EDE0) as the live map: a pin at each question, the current one large. */
@Composable
private fun QuestMap(here: LatLon, state: QuestsState, onPlace: (app.atlas.model.Place) -> Unit, modifier: Modifier) {
    val services = LocalServices.current
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
    Box(modifier.fillMaxWidth().height(230.dp).boardBox(0xFFE8EDE0, round(24f))) {
        AtlasMap(
            map, tiles, Modifier.fillMaxWidth().height(230.dp),
            overlays = MapOverlays(markers = markers, location = UserLocation(here, 15.0), measure = false),
            onTap = { hit -> (hit as? MapHit.OnMarker)?.marker?.place?.let(onPlace) },
        )
    }
}

/**
 * The question as the board's white card (corners 28, 16 px in, its shadow 0 12 28 of the ink at 12 %), centred, 10 px
 * between: where (12 / 800, 0.12 em apart), the thing drawn (200 × 120) with its unknown part a ghost, the question in
 * 22 / 900, then Yes in green, No in ink and a grey 76 px skip, 56 high, corners 18, 8 px apart.
 */
@Composable
private fun QuestCard(
    q: PlaceQuest,
    lang: String,
    last: Sending?,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onSkip: () -> Unit,
    onPlace: () -> Unit,
    modifier: Modifier,
) {
    val name = app.atlas.places.Localise.name(q.place.tags, lang) ?: ""
    val question = questionOf(q.quest)
    Column(
        modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(28f), listOf(CssShadow(0f, 12f, 28f, 0f, 0x1F1B2A1E))).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoardLine(
            "$name · ${shortDistance(q.distanceM)}".uppercase(Locale.getDefault()), boardText(12f, 800, 15.5f, spacing = 1.44f), SOFT,
            Modifier.align(Alignment.Start).clickable(role = Role.Button, onClick = onPlace),
        )
        QuestArt(q.quest.fact.kind, q.quest.fact.symbol, question, Modifier.size(200.dp, 120.dp))
        Text(question, Modifier.fillMaxWidth(), style = boardText(22f, 900, 28.5f, align = TextAlign.Center), color = Color(INK))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnswerButton(stringResource(R.string.quests_yes), 0xFF2F7A3A, 0xFFFFFFFF, Modifier.weight(1f), onYes)
            AnswerButton(stringResource(R.string.quests_no), INK, 0xFFFFFFFF, Modifier.weight(1f), onNo)
            AnswerButton(stringResource(R.string.quests_skip), 0xFFEEF1EA, INK, Modifier.width(76.dp), onSkip, small = true)
        }
        when (last) {
            Sending.Busy -> BoardLine(stringResource(R.string.place_note_sending), boardText(12f, 400, 15.5f), SOFT)
            is Sending.Done -> BoardLine(stringResource(R.string.place_note_sent), boardText(12f, 400, 15.5f), 0xFF2F7A3A)
            is Sending.Failed -> BoardLine(stringResource(R.string.place_note_failed, last.why), boardText(12f, 400, 15.5f), 0xFFC8322A, lines = 3)
            else -> Unit
        }
    }
}

@Composable
private fun AnswerButton(text: String, background: Long, ink: Long, modifier: Modifier, onClick: () -> Unit, small: Boolean = false) {
    Box(
        modifier.height(56.dp).boardBox(background, round(18f)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BoardLine(text, if (small) boardText(13f, 800, 17f) else boardText(16f, 900, 20.5f), ink, Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * The board's dark card of what the answers do (90 px, corners 22, 16 px in, 14 between): how many this month from
 * this phone in amber 36 / 900, what they are (14 / 800) and who reads them (12, green-grey).
 */
@Composable
private fun Impact(thisMonth: Int, all: Int, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 90.dp).boardBox(INK, round(22f)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardLine(thisMonth.toString(), boardText(36f, 900, 46.5f, tnum = true), AMBER)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BoardLine(pluralStringResource(R.plurals.quests_month, thisMonth, thisMonth), boardText(14f, 800, 18f), 0xFFEEF4EC, lines = 2)
            BoardLine(pluralStringResource(R.plurals.quests_all, all, all), boardText(12f, 400, 15.5f), 0xFFA8C0A8, lines = 2)
        }
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
