package app.atlas.ui.search

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.ToggleChip
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
import java.time.LocalDateTime
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

/** The colours of a plan's steps, in turn: café brown, purple, red, blue, green, amber (as on the Ask board). */
private val STEP_COLOURS = longArrayOf(0xFFB5532A, 0xFF7B4FD6, 0xFFD23C3C, 0xFF2E5BFF, 0xFF1F9D55, 0xFFE29A12)

private fun stepColour(step: Int) = Color(STEP_COLOURS[step % STEP_COLOURS.size])

/**
 * Ask, as on the Ask board: a whole outing in one sentence, read on the phone into coloured parts, and a timed plan of
 * real places, each searched near the one before and chosen open when you get there; refine it (closer, surely open,
 * step-free), see the walk and when it ends, say it differently, or share it.
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
    val c = Atlas.colors
    val shareLabel = stringResource(R.string.place_share)

    AtlasScreen(
        stringResource(R.string.ask_title), onBack = { nav.back() }, italic = true,
        actions = {
            Row(
                Modifier.heightIn(min = 40.dp).clip(CircleShape).background(c.card).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol("ui-lock", tint = c.ink, size = 16.dp)
                Text(stringResource(R.string.ask_on_phone), style = Atlas.type.label, color = c.ink)
            }
        },
    ) {
        Sentence(state, onEdit = { editing = true })
        when {
            state.text.isBlank() -> StateMessage("ui-sparkles", stringResource(R.string.ask_empty), text = stringResource(R.string.ask_empty_text), tint = c.ask)
            state.error != null -> StateMessage("ui-alert-triangle", stringResource(R.string.query_failed), text = state.error, tint = c.alert)
            state.parsed == null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = c.inkSoft)
            state.plan == null -> StateMessage("ui-help", stringResource(R.string.query_not_read), text = stringResource(R.string.ask_not_read), tint = c.caution)
            services.placeStores.size == 0 -> StateMessage(
                "ui-cloud-off", stringResource(R.string.search_no_regions), text = stringResource(R.string.ask_no_region), tint = c.go,
                action = { app.atlas.ui.kit.Chip(stringResource(R.string.search_download), symbol = "ui-download", onClick = { nav.go(Dest.RegionPicker(here.lat, here.lon)) }) },
            )
            else -> {
                val plan = state.plan!!
                Kicker(stringResource(R.string.ask_your_plan))
                Timeline(plan, onPlace = { p -> services.remember(p); nav.go(Dest.Place.of(p)) })
                Refinements(state.refine, vm::toggle)
                Totals(plan, state.plan!!.stops.firstOrNull()?.arrive)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
            PillButton(onClick = mic, modifier = Modifier.weight(1f).heightIn(min = 58.dp)) {
                Spacer(Modifier.weight(1f))
                Symbol("ui-mic", size = 20.dp)
                Text(stringResource(R.string.ask_say_again), style = Atlas.type.name)
                Spacer(Modifier.weight(1f))
            }
            SymbolButton("ui-share", shareLabel, {
                val text = shareText(state, lang)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, size = 58.dp)
        }
    }
    if (editing) {
        EditDialog(state.text, onDone = { vm.onText(it); editing = false }, onDismiss = { editing = false })
    }
}

/** The sentence in serif with its parts as coloured chips in place, and "understood in 0.04 s · 2 parts · 2 places". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sentence(state: AskState, onEdit: () -> Unit) {
    val c = Atlas.colors
    val parsed = state.parsed
    val edit = stringResource(R.string.ask_edit)
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp), onClick = onEdit, onClickLabel = edit) {
        val pieces = remember(parsed, state.text) { piecesOf(state.text, parsed) }
        val serif = Atlas.type.title.copy(fontSize = 24.sp, lineHeight = 34.sp)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            for (p in pieces) {
                when (p) {
                    is Piece.Words -> for (w in p.text.split(' ').filter { it.isNotEmpty() }) {
                        Text("$w ", style = serif, color = c.ink)
                    }
                    is Piece.Token -> {
                        val col = if (p.chip.kind == ChipKind.KIND) stepColour(p.chip.step) else if (p.chip.kind == ChipKind.DISTANCE || p.chip.kind == ChipKind.AREA) c.go else c.open
                        Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(col).padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Symbol(p.chip.symbol, tint = Color.White, size = 15.dp)
                                Text(p.text, style = serif.copy(fontSize = 17.sp, lineHeight = 20.sp), color = Color.White, maxLines = 1)
                            }
                            if (p.trail.isNotEmpty()) Text(p.trail, style = serif, color = c.ink)
                        }
                    }
                }
            }
        }
        if (parsed != null && state.plan != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.open))
                val secs = String.format(Locale.getDefault(), "%.2f s", state.micros / 1e6)
                Text(
                    listOf(
                        stringResource(R.string.ask_understood, secs),
                        pluralStringResource(R.plurals.search_parts, parsed.chips.size, parsed.chips.size),
                        pluralStringResource(R.plurals.ask_places, state.plan.found, state.plan.found),
                    ).joinToString(" · "),
                    style = Atlas.type.caption, color = c.inkSoft,
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

/** The plan as a timeline: the time you arrive, the node in the step's colour, the place, and why it fits. */
@Composable
private fun Timeline(plan: AskPlan, onPlace: (app.atlas.model.Place) -> Unit) {
    val c = Atlas.colors
    val lang = remember { Locale.getDefault().language }
    Column {
        plan.stops.forEachIndexed { i, s ->
            val col = if (s.hit != null) stepColour(s.step) else c.inkFaint
            val next = plan.stops.getOrNull(i + 1)
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .then(if (s.place != null) Modifier.clickable(role = Role.Button) { onPlace(s.place!!) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (s.hit != null) clock(s.arrive) else "–", Modifier.width(50.dp).padding(top = 12.dp), style = Atlas.type.bodyStrong, color = c.ink)
                Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                        Symbol(s.hit?.symbol ?: s.symbol, tint = Color.White, size = 22.dp)
                    }
                    if (next != null) {
                        val nextCol = if (next.hit != null) stepColour(next.step) else c.line
                        Canvas(Modifier.width(4.dp).weight(1f).heightIn(min = 18.dp)) {
                            drawLine(col, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height / 2), size.width)
                            drawLine(nextCol, Offset(size.width / 2, size.height / 2), Offset(size.width / 2, size.height), size.width)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(top = 4.dp, bottom = 14.dp)) {
                    val hit = s.hit
                    if (hit == null) {
                        Text(stringResource(R.string.ask_none_for, s.label), style = Atlas.type.name, color = c.inkSoft)
                        Text(stringResource(R.string.ask_none_text), style = Atlas.type.caption, color = c.inkFaint)
                    } else {
                        Text(hit.record.name(lang) ?: kindOf(hit.place, lang) ?: s.label, style = Atlas.type.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stopLine(s, lang), style = Atlas.type.caption, color = c.inkSoft, maxLines = 2)
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

/** Refine chips: closer together, surely open, step-free. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Refinements(on: Set<Refine>, onToggle: (Refine) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(stringResource(R.string.ask_closer), Refine.CLOSER in on, { onToggle(Refine.CLOSER) }, symbol = "ui-walk")
        ToggleChip(stringResource(R.string.ask_surely_open), Refine.OPEN in on, { onToggle(Refine.OPEN) }, symbol = "ui-clock")
        ToggleChip(stringResource(R.string.search_step_free), Refine.STEP_FREE in on, { onToggle(Refine.STEP_FREE) }, symbol = "ui-wheelchair")
    }
}

/** The plan in three numbers: how far on foot, how many stops, and when it ends. */
@Composable
private fun Totals(plan: AskPlan, start: LocalDateTime?) {
    val c = Atlas.colors
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 16.dp, horizontal = 8.dp), elevation = 0.dp) {
        Row(Modifier.fillMaxWidth()) {
            Total(shortDistance(plan.walkM), stringResource(R.string.ask_on_foot), Modifier.weight(1f))
            Total("${plan.found}/${plan.stops.size}", stringResource(R.string.ask_stops), Modifier.weight(1f))
            Total(plan.end?.let { clock(it) } ?: "–", stringResource(R.string.ask_until), Modifier.weight(1f))
        }
        Text(stringResource(R.string.ask_estimates), Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp, end = 12.dp), style = Atlas.type.caption, color = c.inkFaint)
    }
}

@Composable
private fun Total(value: String, label: String, modifier: Modifier) {
    val c = Atlas.colors
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$value $label" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Atlas.type.numeralSmall.copy(fontSize = 24.sp), color = c.ink)
        Text(label, style = Atlas.type.caption, color = c.inkSoft)
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
