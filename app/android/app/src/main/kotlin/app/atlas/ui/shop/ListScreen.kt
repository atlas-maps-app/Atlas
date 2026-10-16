package app.atlas.ui.shop

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssRepeatingLinear
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** What the Open Prices lookup for the list said: the prices (maybe none) and whether the phone was offline. */
internal class ListPrices(val index: PriceIndex, val offline: Boolean)

/**
 * The shopping list as a notepad on the fridge, and the run it makes: each item is matched to the kinds of shop that
 * sell it, and the stops (a set cover over the shops open when you can reach them, walked in the best order, closing
 * times respected) are printed on a till receipt: "YOUR RUN · 3 STOPS · 1.2 KM · 18 MIN". The numbers on the pad are
 * the stops the items are bought at. "Go" opens the route with the stops as waypoints.
 */
@Composable
fun ListScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val items by repo.list.items.collectAsStateWithLifecycle()
    val notes by repo.priceNotes.items.collectAsStateWithLifecycle()
    val toBuy = items.filterNot { it.atHome }
    val filter = remember(toBuy.map { it.text }) { RunPlanner.filterFor(toBuy) }
    val area by produceState(ShopArea.empty(here, RUN_RADIUS_M), here, filter.toText()) {
        repo.area("run:" + filter.toText(), filter, here, RUN_RADIUS_M).collect { value = it }
    }
    val prices by produceState(ListPrices(PriceIndex.Empty, offline = false), toBuy.map { it.text }, notes, here) {
        val data = repo.priceData(items, here)
        value = ListPrices(PriceIndex.of(items, data, notes), offline = data.error != null)
    }
    var by by rememberSaveable { mutableStateOf(PlanBy.FEWEST.name) }
    val canCheap = prices.index.any
    val plan = PlanBy.valueOf(by).takeIf { it != PlanBy.CHEAPEST || canCheap } ?: PlanBy.FEWEST
    // Kept while the next plan is worked out (every minute the closing times move), so the receipt does not blink.
    var run by remember { mutableStateOf<Run?>(null) }
    LaunchedEffect(toBuy.map { it.text }, area, plan, prices, now) {
        run = if (toBuy.isEmpty() || area.waiting) null else withContext(Dispatchers.Default) {
            RunPlanner.plan(toBuy, area.places, here, now, area.hours, plan, { e, s -> prices.index.price(e, s.key) })
        }
    }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.shop_list_share_title)

    Box(Modifier.fillMaxSize().boardBox(grad = listOf(cssLinear(90f, 0f to 0xFFE4E8EB, 0.45f to 0xFFF2F4F5, 1f to 0xFFE6EAED)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 110.dp)) {
            Box(Modifier.fillMaxWidth()) {
                // The fridge handle, right 14 px, from y 180.
                Box(
                    Modifier.at(384f, 180f, 14f, 300f).boardBox(
                        r = round(7f), shadows = listOf(CssShadow(-3f, 4f, 8f, 0f, 0x26000000)),
                        grad = listOf(cssLinear(90f, 0f to 0xFFB9C0C7, 0.45f to 0xFFF4F6F8, 1f to 0xFF9DA5AE)), clip = false,
                    ),
                )
                Column {
                    Spacer(Modifier.height(84.dp))
                    Notepad(items, run, repo)
                    Spacer(Modifier.height(22.dp))
                    PlanPills(plan, canCheap) { by = it.name }
                    Spacer(Modifier.height(2.dp))
                    Receipt(run, toBuy, area, prices, plan)
                }
                Box(
                    Modifier.at(16f, 44f, 44f, 44f).boardBox(0xCCFFFFFF, round(22f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x14000000))).tap(stringResource(R.string.shop_back_pantry)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.back", 20f, 20f) }
                Box(
                    Modifier.at(352f, 44f, 44f, 44f).boardBox(0xCCFFFFFF, round(22f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x14000000))).tap(stringResource(R.string.shop_list_share)) {
                        val text = items.joinToString("\n") { (if (it.atHome) "✓ " else "• ") + it.text }
                        startSafely(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, shareTitle).putExtra(Intent.EXTRA_TEXT, text), shareTitle))
                    },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.add-person", 19f, 19f) }
            }
        }
        val r = run
        val enabled = r != null && r.stops.isNotEmpty()
        // Go: 56 high, 16 px from the sides, 26 above the bottom; grey while there is no run to walk.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 26.dp + boardLift()).fillMaxWidth().height(56.dp)
                .boardBox(if (enabled) 0xFF2E5BFF else 0xFFB9C0C7, round(28f))
                .clickable(enabled = enabled, role = Role.Button) { nav.go(routeOf(r!!)) },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sym("mode.walk", 20f, 20f, 0xFFFFFFFF)
            Line(if (enabled) stringResource(R.string.shop_go_min, r!!.minutes) else stringResource(R.string.shop_go), bt(16f, 600, 20.5f, tnum = true), 0xFFFFFFFF)
        }
    }
}

private const val RUN_RADIUS_M = 1_500.0

/** The route of a run: from here on foot through every stop, ending at the last. */
internal fun routeOf(run: Run): Dest.Route {
    val last = run.stops.last().shop
    val via = run.stops.dropLast(1).joinToString("|") { s ->
        String.format(Locale.ROOT, "%.6f,%.6f,%s", s.shop.location.lat, s.shop.location.lon, s.shop.name.replace('|', ' '))
    }.ifEmpty { null }
    return Dest.Route(last.location.lat, last.location.lon, last.name, mode = "WALK", via = via)
}

private const val HAND_INK: Long = 0xFF1F3E86
private const val HAND_SOFT: Long = 0xFF8A9AC4
private const val PAPER_INK: Long = 0xFF1D1D1D
private const val PAPER_SOFT: Long = 0xFF555555
private const val RED: Long = 0xFFB03A3A

/**
 * The pad, as the List board's: 300 wide at x 34, at least 334 high, turned 2° to the left, ruled every 36 px (a 1 px
 * line at 35), the magnet at its top; the items in blue Caveat with the glyph of the shop and the number of the stop.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Notepad(items: List<ListEntry>, run: Run?, repo: ShopRepository) {
    val stopOf = HashMap<String, Int>()
    run?.stops?.forEachIndexed { i, s -> s.items.forEach { stopOf[it.text] = i + 1 } }
    Box(Modifier.padding(start = 34.dp).width(300.dp).heightIn(min = 334.dp).graphicsLayer { rotationZ = -2f }) {
        Column(
            Modifier.matchParentSize().boardBox(
                0xFFFFFEF6, shadows = listOf(CssShadow(0f, 10f, 22f, 0f, 0x24000000)),
                grad = listOf(cssRepeatingLinear(180f, 36f, 0f to 0x00000000, 35f to 0x00000000, 35f to 0xFFC9D8EA, 36f to 0xFFC9D8EA)), clip = false,
            ),
        ) {}
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 30.dp, bottom = 16.dp)) {
            val hand = bt(27f, 600, 36f, CAVEAT)
            for (e in items) {
                val label = e.text
                val removeLabel = stringResource(R.string.shop_list_remove, label)
                Row(
                    Modifier.fillMaxWidth().height(36.dp)
                        .combinedClickable(onClickLabel = stringResource(R.string.shop_list_toggle, label), onLongClickLabel = removeLabel, onLongClick = { repo.list.remove(e) }) { repo.list.toggleHome(e) }
                        .semantics {
                            contentDescription = label
                            customActions = listOf(CustomAccessibilityAction(removeLabel) { repo.list.remove(e); true })
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (e.atHome) {
                        Line(e.text, hand.copy(textDecoration = TextDecoration.LineThrough), HAND_SOFT, Modifier.weight(1f))
                        Line(stringResource(R.string.shop_list_at_home), bt(12f, 600, 17f), HAND_SOFT)
                    } else {
                        Line(e.text, hand, HAND_INK, Modifier.weight(1f))
                        e.kind?.let { k -> Sym(k.glyph, 22f, 22f, hue(k.glyph) ?: INK) }
                        stopOf[e.text]?.let { n ->
                            Box(Modifier.size(22.dp).boardBox(INK, round(11f)), contentAlignment = Alignment.Center) {
                                Line(n.toString(), bt(11f, 700, 14f), 0xFFFFFFFF)
                            }
                        }
                    }
                }
            }
            AddRow(items, repo)
        }
        // The magnet holding the pad to the fridge: 36 round at x 132, 14 px above the pad.
        Box(
            Modifier.at(132f, -14f, 36f, 36f).boardBox(
                r = round(18f), shadows = listOf(CssShadow(0f, 4f, 6f, 0f, 0x4C000000)),
                grad = listOf(cssRadial(true, 12.6f, 10.8f, 0f to 0xFFFF9A76, 0.6f to 0xFFE8622C, 1f to 0xFFB8441B)), clip = false,
            ),
        )
    }
}

@Composable
private fun AddRow(items: List<ListEntry>, repo: ShopRepository) {
    var text by remember { mutableStateOf("") }
    val label = stringResource(R.string.shop_list_add_label)
    val submit = { if (repo.list.add(text)) text = "" }
    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Line("+", bt(25f, 400, 32f, CAVEAT), HAND_SOFT)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val style = bt(25f, 600, 40f, CAVEAT)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                textStyle = style.copy(color = argb(HAND_INK)),
                cursorBrush = SolidColor(argb(HAND_INK)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
            if (text.isEmpty()) Line(stringResource(R.string.shop_list_add), style, 0xFFA9A9A9)
        }
    }
    if (items.isEmpty()) Suggestions(repo)
}

/** Things that are on most lists, to start a pad that is still empty: a tap writes one down. */
@Composable
private fun Suggestions(repo: ShopRepository) {
    val lang = remember { Locale.getDefault().language }
    val names = remember(lang) { listOf("milk", "bread", "eggs", "apples", "cheese", "coffee").mapNotNull { id -> ShopItems.byId(id)?.name(lang) } }
    // On the pad's own lines: a 36 px row for the label and for each three things.
    Column {
        Box(Modifier.height(36.dp), contentAlignment = Alignment.CenterStart) { Line(stringResource(R.string.shop_list_suggest), bt(12f, 600, 17f), HAND_SOFT) }
        for (row in names.chunked(3)) {
            Row(Modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                for (n in row) Line("+ $n", bt(22f, 600, 32f, CAVEAT), HAND_SOFT, Modifier.tap(n) { repo.list.add(n) })
            }
        }
    }
}

/** "Plan by": three buttons in a 56 high white pill (4 px padding and gaps), the chosen one ink with white words. */
@Composable
private fun PlanPills(selected: PlanBy, canCheap: Boolean, onPick: (PlanBy) -> Unit) {
    FlexRow(
        4f,
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp)
            .boardBox(0xD9FFFFFF, round(24f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x0F000000)), clip = false).padding(4.dp),
    ) {
        for (p in PlanBy.entries) {
            val on = p == selected
            val enabled = p != PlanBy.CHEAPEST || canCheap
            val label = stringResource(
                when (p) {
                    PlanBy.FEWEST -> R.string.shop_by_fewest
                    PlanBy.BEST -> R.string.shop_by_best
                    PlanBy.CHEAPEST -> R.string.shop_by_cheapest
                },
            )
            val fg = if (on) 0xFFFFFFFF else if (enabled) INK else 0x5914161B
            Row(
                Modifier.boardBox(if (on) INK else 0, round(20f))
                    .then(if (enabled) Modifier.tap(label) { onPick(p) } else Modifier.semantics { contentDescription = label })
                    .semantics { this.selected = on },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val side = if (p == PlanBy.FEWEST) 16f else 15f
                Sym(when (p) { PlanBy.FEWEST -> "ui.route"; PlanBy.BEST -> "ui.star"; PlanBy.CHEAPEST -> "ui.tag" }, side, side, fg)
                Line(label, bt(14f, 600, 18f), fg)
            }
        }
    }
}

/** A receipt's dashed rule: `border-top: 1.5px dashed #999`. */
@Composable
private fun Dashes() {
    Canvas(Modifier.fillMaxWidth().height(1.5.dp)) {
        val w = 1.5.dp.toPx()
        drawLine(Color(0xFF999999), Offset(0f, w / 2), Offset(size.width, w / 2), strokeWidth = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * w, 3 * w)))
    }
}

/**
 * The till receipt, as the List board's: 324 wide at x 44, Courier Prime on white (padding 14 18 12, 6 px between
 * blocks), the stops with their walk and items, red closing warnings, the estimated basket, the barcode and a torn edge.
 */
@Composable
private fun Receipt(run: Run?, toBuy: List<ListEntry>, area: ShopArea, prices: ListPrices, by: PlanBy) {
    val b14 = bt(14f, 700, 16f, COURIER)
    val r14 = bt(14f, 400, 16f, COURIER, tnum = true)
    val r13 = bt(13f, 400, 14.5f, COURIER)
    val r12 = bt(12f, 400, 13.5f, COURIER, tnum = true)
    Column(Modifier.padding(start = 44.dp).width(324.dp)) {
        Column(
            Modifier.fillMaxWidth().boardBox(0xFFFFFFFF, shadows = listOf(CssShadow(0f, 8f, 14f, 0f, 0x29000000)), clip = false)
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(stringResource(R.string.shop_run_title), bt(16f, 700, 18f, COURIER, spacing = 3.2f), PAPER_INK)
                val sub = when {
                    toBuy.isEmpty() -> stringResource(R.string.shop_run_none)
                    run == null -> stringResource(R.string.shop_run_planning)
                    run.stops.isEmpty() -> stringResource(R.string.shop_run_unmet)
                    else -> {
                        val km = String.format(Locale.getDefault(), "%.1f", run.metres / 1000.0)
                        if (run.stops.size == 1) stringResource(R.string.shop_run_summary_one, km, run.minutes) else stringResource(R.string.shop_run_summary, run.stops.size, km, run.minutes)
                    }
                }
                Line(sub, r12.copy(textAlign = TextAlign.Center), PAPER_SOFT, lines = 2)
            }
            if (run != null) {
                Dashes()
                run.stops.forEachIndexed { i, s ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Line("${i + 1}", b14, PAPER_INK)
                            Line(s.shop.name.uppercase(), b14, PAPER_INK, Modifier.weight(1f))
                            Line("${s.walkMinutes}′", r14, PAPER_INK)
                        }
                        Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Line(s.items.joinToString(", ") { it.text }, r13, PAPER_INK, Modifier.weight(1f), lines = 3)
                            if (s.closesAt != null && s.closingSoon) {
                                Line(stringResource(R.string.shop_run_closes, s.closesAt.hm()), bt(13f, 700, 14.5f, COURIER, tnum = true), RED)
                            } else if (!s.hoursKnown) {
                                Line(stringResource(R.string.shop_run_hours_unknown), r13, 0xFF888888)
                            }
                        }
                    }
                }
                if (run.unmet.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Line(stringResource(R.string.shop_run_unmet), bt(12f, 700, 13.5f, COURIER), RED)
                        Line(run.unmet.joinToString(", ") { it.text }, r13, PAPER_INK, Modifier.padding(start = 16.dp), lines = 3)
                    }
                }
                if (run.late) Line(stringResource(R.string.shop_run_late), bt(12f, 700, 13.5f, COURIER), RED, lines = 2)
                if (run.stops.isNotEmpty()) {
                    Dashes()
                    val b = run.basket
                    val b15 = bt(15f, 700, 16.5f, COURIER, tnum = true)
                    Row {
                        Line(stringResource(R.string.shop_run_basket), b15, PAPER_INK, Modifier.weight(1f))
                        Line(if (b != null) money(b.total, prices.index.currency) + if (b.known < b.of) "+" else "" else "?", b15, PAPER_INK)
                    }
                    Row {
                        Line(
                            if (prices.offline) stringResource(R.string.shop_run_prices_offline) else stringResource(R.string.shop_run_prices_known, run.basket?.known ?: 0, toBuy.size),
                            r12, PAPER_SOFT, Modifier.weight(1f),
                        )
                        Line(stringResource(R.string.shop_run_done, run.doneAt.hm()), r12, PAPER_SOFT)
                    }
                    BoardArt(ShopBoards.RECEIPT_BARCODE, Modifier.fillMaxWidth(), tint = argb(PAPER_INK), width = 288.dp, height = 16.dp)
                }
            }
        }
        TornEdge()
    }
}

/**
 * The receipt's torn bottom, as the board's two 12 px gradient tiles (135° and 225°, white to the half): white points
 * down at every 12 px, notches 6 px deep between them.
 */
@Composable
private fun TornEdge() {
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val tooth = 12.dp.toPx()
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(0f, size.height)
        var x = 0f
        while (x < size.width) {
            path.lineTo(minOf(x + tooth / 2f, size.width), size.height / 2f)
            path.lineTo(minOf(x + tooth, size.width), size.height)
            x += tooth
        }
        path.lineTo(size.width, 0f)
        path.close()
        drawPath(path, Color.White)
    }
}

/** A price in [currency] as the phone's language writes it: "€21.40", "€ 21,40". */
internal fun money(amount: Double, currency: String): String {
    val c = runCatching { java.util.Currency.getInstance(currency) }.getOrNull()
    val format = java.text.NumberFormat.getCurrencyInstance(Locale.getDefault())
    if (c != null) format.currency = c
    return format.format(amount)
}
