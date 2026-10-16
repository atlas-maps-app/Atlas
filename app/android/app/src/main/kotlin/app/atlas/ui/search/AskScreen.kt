package app.atlas.ui.search

import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssLinear
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.CoordinateFormat
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.search.ChipKind
import app.atlas.search.EvalContext
import app.atlas.search.OfflineEvaluator
import app.atlas.search.ParsedQuery
import app.atlas.search.QueryParser
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSpeechInput
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.Locale

/** What Ask shows: the sentence, how it was read, the plan, how long reading and planning took, and the refinements on. */
data class AskState(
    val text: String = "",
    val parsed: ParsedQuery? = null,
    val plan: AskPlan? = null,
    val micros: Long = 0,
    val refine: Set<Refine> = emptySet(),
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * Ask's brain: the sentence read on the phone into steps ("then"), each step searched around the stop before over the
 * downloaded regions, timed from now with walks and usual stays. Nothing leaves the phone.
 */
class AskViewModel(private val services: Services, initial: String, private val here: () -> LatLon, private val lang: String) : ViewModel() {
    private val mutable = MutableStateFlow(AskState(text = initial))
    val state: StateFlow<AskState> = mutable.asStateFlow()
    private var job: Job? = null

    init {
        run()
    }

    fun onText(t: String) {
        if (t.trim() == mutable.value.text.trim()) return
        mutable.update { it.copy(text = t) }
        run()
    }

    fun toggle(r: Refine) {
        mutable.update { it.copy(refine = if (r in it.refine) it.refine - r else it.refine + r) }
        run()
    }

    private fun run() {
        job?.cancel()
        val s = mutable.value
        if (s.text.isBlank()) {
            mutable.update { it.copy(parsed = null, plan = null, running = false, error = null) }
            return
        }
        mutable.update { it.copy(running = true) }
        job = viewModelScope.launch {
            try {
                val started = System.nanoTime()
                val r = withContext(Dispatchers.Default) {
                    val now = ZonedDateTime.now(services.clock)
                    val from = here()
                    val parsed = QueryParser.parse(s.text, lang, now)
                    val hours = HereContext.hours(services, from, now.zone)
                    val evaluator = OfflineEvaluator(services.placeStores)
                    val plan = if (parsed.steps.isEmpty()) null else AskPlanner.plan(parsed, from, now.toLocalDateTime(), hours, s.refine) { q, at ->
                        evaluator.run(q, EvalContext(location = at, now = now, lang = lang, limit = 20, hours = hours))
                    }
                    parsed to plan
                }
                mutable.update { it.copy(parsed = r.first, plan = r.second, micros = (System.nanoTime() - started) / 1000, running = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }
}

/** The colours of a plan's steps, in turn, as the Ask board paints them: café brown, green, blue, purple, red, amber. */
private val STEP_COLOURS = longArrayOf(0xFFB8461A, 0xFF1E9E5A, 0xFF2E6BFF, 0xFF7B4DFF, 0xFFC8322A, 0xFFE29A12)

private fun stepArgb(step: Int) = STEP_COLOURS[step % STEP_COLOURS.size]

/** The Ask board's page, ink, soft and faint greys, and the green of what is understood. */
private const val PAGE = 0xFFF6F4EF
private const val INK = 0xFF1C1B22
private const val SOFT = 0xFF6B6878
private const val FAINT = 0xFF8A8698
private const val GREEN = 0xFF1E9E5A

/**
 * Ask, as on the Ask board: a whole outing in one sentence, read on the phone into coloured parts, and a timed plan of
 * real places, each searched near the one before and chosen open when you get there; refine it (closer, surely open,
 * step-free), see the walk and when it ends, say it differently, open it in the query builder, or share it. The board's
 * numbers: the sentence card at 100, the plan's kicker 35 px under it, 72 px stops, the totals card 14 px under the
 * chips, and the two buttons 24 px from the foot.
 */
@Composable
fun AskScreen(args: Dest.Ask, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    val vm = viewModel(key = "ask") { AskViewModel(services, args.text, { here }, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mic = rememberSpeechInput(onText = vm::onText)
    var editing by remember { mutableStateOf(false) }
    val shareLabel = stringResource(R.string.place_share)

    Column(Modifier.fillMaxSize().background(Color(PAGE)).padding(top = boardShift())) {
        BoardTitleRow(stringResource(R.string.ask_title), INK, onBack = { nav.back() }) {
            Row(
                Modifier.height(34.dp).boardBox(0xFFFFFFFF, round(17f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BoardSymbol("ui.lock", 14f, INK)
                BoardLine(stringResource(R.string.ask_on_phone), boardText(12f, 800, 15.5f), INK)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Sentence(state, onEdit = { editing = true }, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
            val part = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)
            when {
                state.text.isBlank() -> StateMessage("ui-sparkles", stringResource(R.string.ask_empty), part, text = stringResource(R.string.ask_empty_text), tint = Color(0xFF7B4DFF))
                state.error != null -> StateMessage("ui-alert-triangle", stringResource(R.string.query_failed), part, text = state.error, tint = Color(0xFFC8322A))
                state.parsed == null -> BoardLine(stringResource(R.string.kit_loading), boardText(12f, 400, 15.5f), SOFT, part)
                state.plan == null -> StateMessage("ui-help", stringResource(R.string.query_not_read), part, text = stringResource(R.string.ask_not_read), tint = Color(0xFF8A5A00))
                services.placeStores.size == 0 -> StateMessage(
                    "ui-cloud-off", stringResource(R.string.search_no_regions), part, text = stringResource(R.string.ask_no_region), tint = Color(0xFF2E5BFF),
                    action = { app.atlas.ui.kit.Chip(stringResource(R.string.search_download), symbol = "ui-download", onClick = { nav.go(Dest.RegionPicker(here.lat, here.lon)) }) },
                )
                else -> {
                    val plan = state.plan!!
                    val kicker = boardText(12f, 800, 15.5f, spacing = 1.44f)
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 35.dp), verticalAlignment = Alignment.CenterVertically) {
                        BoardLine(stringResource(R.string.ask_your_plan).uppercase(Locale.getDefault()), kicker, FAINT, Modifier.weight(1f))
                        BoardLine(
                            shareLabel.uppercase(Locale.getDefault()), kicker, FAINT,
                            Modifier.clickable(role = Role.Button) {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(state, lang))
                                runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                        )
                    }
                    Timeline(plan, onPlace = { p -> services.remember(p); nav.go(Dest.Place.of(p)) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.5.dp))
                    Refinements(state.refine, vm::toggle, Modifier.padding(horizontal = 16.dp))
                    Totals(plan, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
                    BoardLine(stringResource(R.string.ask_estimates), boardText(11f, 400, 14f), FAINT, Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp), lines = 3)
                }
            }
            Spacer(Modifier.height(25.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp).windowInsetsPadding(WindowInsets.navigationBars),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(56.dp).boardBox(INK, round(28f)).clickable(role = Role.Button, onClick = mic),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardSymbol("ui.mic", 20f, 0xFFFFFFFF)
                BoardLine(stringResource(R.string.ask_say_again), boardText(15f, 800, 19.5f), 0xFFFFFFFF)
            }
            BoardRoundButton(
                "ui.query-open", stringResource(R.string.ask_open_query), { nav.go(Dest.Query(state.text)) },
                side = 56f, glyph = 22f, tint = INK,
            )
        }
    }
    if (editing) {
        EditDialog(state.text, onDone = { vm.onText(it); editing = false }, onDismiss = { editing = false })
    }
}

/**
 * The sentence as the board's white card (corners 26, 16 · 16 · 12 in, its soft shadow): Instrument Serif 23 with a
 * line height of 1.55, its parts as 30 px chips in place (7 px before a 16 px symbol, 5 between, 10 after the words in
 * 15 / 800), and "understood in 0.04 s · 2 parts · 2 places" after a green dot (11 / 700).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sentence(state: AskState, onEdit: () -> Unit, modifier: Modifier) {
    val parsed = state.parsed
    val edit = stringResource(R.string.ask_edit)
    Column(
        modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(26f), listOf(CssShadow(0f, 10f, 26f, 0f, 0x141C1B22)))
            .clickable(role = Role.Button, onClickLabel = edit, onClick = onEdit)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    ) {
        val pieces = remember(parsed, state.text) { piecesOf(state.text, parsed) }
        val serif = boardText(23f, 400, 35.65f, family = SERIF)
        val chip = boardText(15f, 800, 23.25f, family = SERIF)
        FlowRow(verticalArrangement = Arrangement.spacedBy(1.6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            for (p in pieces) {
                when (p) {
                    is Piece.Words -> for (w in p.text.split(' ').filter { it.isNotEmpty() }) {
                        Text("$w ", style = serif, color = Color(INK))
                    }
                    is Piece.Token -> {
                        val col = if (p.chip.kind == ChipKind.KIND) stepArgb(p.chip.step) else GREEN
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.padding(horizontal = 2.dp, vertical = 3.dp).height(30.dp).boardBox(col, round(15f)).padding(start = 7.dp, end = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                BoardSymbol(p.chip.symbol, 16f, 0xFFFFFFFF)
                                BoardLine(p.text, chip, 0xFFFFFFFF)
                            }
                            Text(p.trail + " ", style = serif, color = Color(INK))
                        }
                    }
                }
            }
        }
        if (parsed != null && state.plan != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).boardBox(GREEN, round(3f)))
                val secs = String.format(Locale.getDefault(), "%.2f s", state.micros / 1e6)
                BoardLine(
                    listOf(
                        stringResource(R.string.ask_understood, secs),
                        pluralStringResource(R.plurals.search_parts, parsed.chips.size, parsed.chips.size),
                        pluralStringResource(R.plurals.ask_places, state.plan.found, state.plan.found),
                    ).joinToString(" · "),
                    boardText(11f, 700, 14f), FAINT,
                )
            }
        }
    }
}

/** A run of the sentence: plain words, or the words a chip stands for. */
private sealed interface Piece {
    data class Words(val text: String) : Piece
    /** [trail] is punctuation that follows the chip at once (", then"), kept with it on its line. */
    data class Token(val text: String, val chip: app.atlas.search.Chip, val trail: String = "") : Piece
}

/** [text] cut at its chips' spans; chips with no words of their own follow at the end. */
private fun piecesOf(text: String, parsed: ParsedQuery?): List<Piece> {
    if (parsed == null) return listOf(Piece.Words(text))
    val spans = parsed.chips.mapNotNull { ch -> ch.span?.takeIf { it.last < text.length }?.let { it to ch } }.sortedBy { it.first.first }
    val out = ArrayList<Piece>()
    var at = 0
    for ((span, chip) in spans) {
        if (span.first < at) continue
        if (span.first > at) out += Piece.Words(text.substring(at, span.first))
        out += Piece.Token(text.substring(span.first, span.last + 1), chip)
        at = span.last + 1
    }
    if (at < text.length) out += Piece.Words(text.substring(at))
    // Punctuation right after a chip stays with it: "terrace," not "terrace ,".
    for (i in 0 until out.size - 1) {
        val t = out[i] as? Piece.Token ?: continue
        val w = out[i + 1] as? Piece.Words ?: continue
        val p = w.text.takeWhile { it in PUNCTUATION }
        if (p.isNotEmpty()) {
            out[i] = t.copy(trail = p)
            out[i + 1] = Piece.Words(w.text.drop(p.length))
        }
    }
    return out
}

private const val PUNCTUATION = ",.;:!?"

/**
 * The plan as the board's timeline: 72 px stops, the time you arrive (13 / 900, right in 44 px), the node in the step's
 * colour (40 px in a 4 px ring of the page) on a 3 px line shading from one step's colour to the next, the place in
 * 15 / 800 and why it fits in 12 under it.
 */
@Composable
private fun Timeline(plan: AskPlan, onPlace: (app.atlas.model.Place) -> Unit, modifier: Modifier) {
    val lang = remember { Locale.getDefault().language }
    val colours = plan.stops.map { if (it.hit != null) stepArgb(it.step) else 0xFFD8D4CC }
    Box(modifier.fillMaxWidth()) {
        if (plan.stops.size > 1) {
            val line = if (colours.size > 1) cssLinear(180f, *colours.mapIndexed { i, c -> i.toFloat() / (colours.size - 1) to c }.toTypedArray()) else null
            Box(
                Modifier.padding(start = 75.dp, top = 20.dp).width(3.dp).height((72 * plan.stops.size - 40).dp)
                    .boardBox(colours.first(), round(2f), grad = listOfNotNull(line)),
            )
        }
        Column {
            plan.stops.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().height(72.dp)
                        .then(if (s.place != null) Modifier.clickable(role = Role.Button) { onPlace(s.place!!) } else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (s.hit != null) clock(s.arrive) else "–", Modifier.width(44.dp),
                        style = boardText(13f, 900, 17f, tnum = true, align = TextAlign.End), color = Color(INK), maxLines = 1,
                    )
                    Box(Modifier.size(40.dp).boardBox(colours[i], round(20f), listOf(spreadRing(4f, PAGE))), contentAlignment = Alignment.Center) {
                        BoardSymbol(s.hit?.symbol ?: s.symbol, 20f, 0xFFFFFFFF)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val hit = s.hit
                        if (hit == null) {
                            BoardLine(stringResource(R.string.ask_none_for, s.label), boardText(15f, 800, 19.5f), FAINT)
                            BoardLine(stringResource(R.string.ask_none_text), boardText(12f, 400, 15.5f), FAINT)
                        } else {
                            BoardLine(hit.record.name(lang) ?: kindOf(hit.place, lang) ?: s.label, boardText(15f, 800, 19.5f), INK)
                            BoardLine(stopLine(s, lang), boardText(12f, 400, 15.5f, tnum = true), SOFT)
                        }
                    }
                }
            }
        }
    }
}

/** "café · 3′ walk · open until 23:00 · ~45 min here". */
@Composable
private fun stopLine(s: AskStop, lang: String): String {
    val hit = s.hit ?: return ""
    val parts = ArrayList<String>()
    kindOf(hit.place, lang)?.takeIf { it != hit.record.name(lang) }?.let { parts += it }
    if (s.walkM > 0) parts += stringResource(R.string.ask_walk, walkMinutes(WalkTime.seconds(s.walkM)))
    when (s.openThen) {
        true -> parts += stringResource(R.string.ask_open_then)
        null -> parts += stringResource(R.string.kit_hours_unknown)
        false -> Unit
    }
    parts += stringResource(R.string.ask_stay, s.stay.toMinutes().toInt())
    return parts.joinToString(" · ")
}

/** Refine chips as the board's: 36 px, 12 px in, a 1.5 px #D8D4CC ring on white, 12 / 800, 6 px apart; on in ink. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Refinements(on: Set<Refine>, onToggle: (Refine) -> Unit, modifier: Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RefineChip(stringResource(R.string.ask_closer), Refine.CLOSER in on) { onToggle(Refine.CLOSER) }
        RefineChip(stringResource(R.string.ask_surely_open), Refine.OPEN in on) { onToggle(Refine.OPEN) }
        RefineChip(stringResource(R.string.search_step_free), Refine.STEP_FREE in on) { onToggle(Refine.STEP_FREE) }
    }
}

@Composable
private fun RefineChip(text: String, on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.height(36.dp).boardBox(if (on) INK else 0xFFFFFFFF, round(18f), border = ring(1.5f, if (on) INK else 0xFFD8D4CC))
            .toggleable(on, role = Role.Checkbox) { onToggle() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { BoardLine(text, boardText(12f, 800, 15.5f), if (on) 0xFFFFFFFF else INK) }
}

/** The plan in three numbers on the board's white card (84 px, corners 22, spaced around): on foot, stops, and when it ends. */
@Composable
private fun Totals(plan: AskPlan, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().height(84.dp).boardBox(0xFFFFFFFF, round(22f)),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Total(shortDistance(plan.walkM), stringResource(R.string.ask_on_foot))
        Total("${plan.found}/${plan.stops.size}", stringResource(R.string.ask_stops))
        Total(plan.end?.let { clock(it) } ?: "–", stringResource(R.string.ask_until))
    }
}

@Composable
private fun Total(value: String, label: String) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$value $label" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BoardLine(value, boardText(20f, 900, 26f, tnum = true), INK)
        BoardLine(label, boardText(11f, 700, 14f), FAINT)
    }
}

/** The plan as text to send: each stop's time, name, and its link. */
private fun shareText(state: AskState, lang: String): String {
    val plan = state.plan ?: return state.text
    return buildString {
        append('“').append(state.text.trim()).append("”\n")
        for (s in plan.stops) {
            val hit = s.hit ?: continue
            append('\n').append(clock(s.arrive)).append("  ").append(hit.record.name(lang) ?: s.label).append('\n')
            append(hit.place.id?.url ?: CoordinateFormat.geoUri(hit.record.location)).append('\n')
        }
    }
}

/** Typing the sentence instead of saying it. */
@Composable
private fun EditDialog(text: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    var t by rememberSaveable { mutableStateOf(text) }
    SearchDialog(stringResource(R.string.ask_edit), onDismiss) {
        BasicTextField(
            t, { t = it.take(300) },
            Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
            textStyle = Atlas.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.go),
            decorationBox = { inner ->
                if (t.isEmpty()) Text(stringResource(R.string.search_say_ask), style = Atlas.type.body, color = c.inkFaint)
                inner()
            },
        )
        PrimaryButton(stringResource(R.string.ask_plan_it), onClick = { onDone(t.trim()) }, modifier = Modifier.fillMaxWidth(), symbol = "ui-sparkles", colour = c.ask, enabled = t.isNotBlank())
    }
}
