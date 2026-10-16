package app.atlas.ui.shop

import android.content.Intent
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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFF14161B)
private val HandInk = Color(0xFF1F3E86)
private val HandSoft = Color(0xFF8A9AC4)

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

    Box(
        Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xFFE4E8EB), 0.45f to Color(0xFFF2F4F5), 1f to Color(0xFFE6EAED))),
    ) {
        // The fridge handle.
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 180.dp).size(14.dp, 300.dp)
                .cssShadow(RoundedCornerShape(7.dp), y = 4.dp, x = (-3).dp, blur = 8.dp, colour = Color(0x26000000))
                .background(Brush.horizontalGradient(0f to Color(0xFFB9C0C7), 0.45f to Color(0xFFF4F6F8), 1f to Color(0xFF9DA5AE)), RoundedCornerShape(7.dp)),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 4.dp, bottom = 100.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundSymbolButton("ui.back", stringResource(R.string.shop_back_pantry), { nav.back() }, size = 44.dp, fill = Color.White.copy(alpha = 0.8f), iconSize = 20.dp, weight = 1f)
                Spacer(Modifier.weight(1f))
                RoundSymbolButton(
                    "ui.add-person", stringResource(R.string.shop_list_share),
                    {
                        val text = items.joinToString("\n") { (if (it.atHome) "✓ " else "• ") + it.text }
                        startSafely(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, shareTitle).putExtra(Intent.EXTRA_TEXT, text), shareTitle))
                    },
                    size = 44.dp, fill = Color.White.copy(alpha = 0.8f), iconSize = 19.dp,
                )
            }
            Notepad(items, run, repo)
            Spacer(Modifier.height(14.dp))
            PlanPills(plan, canCheap) { by = it.name }
            Spacer(Modifier.height(10.dp))
            Receipt(run, toBuy, area, prices, plan)
        }
        val r = run
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp)) {
            val enabled = r != null && r.stops.isNotEmpty()
            BigPill(
                text = if (enabled) stringResource(R.string.shop_go_min, r!!.minutes) else stringResource(R.string.shop_go),
                onClick = { if (enabled) nav.go(routeOf(r!!)) },
                modifier = Modifier.fillMaxWidth(),
                fill = if (enabled) Color(0xFF2E5BFF) else Color(0xFFB9C0C7),
                icon = { Symbol("mode.walk", tint = Color.White, size = 20.dp) },
            )
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

/** The pad: a magnet, ruled lines every 36 px, the items in blue handwriting with the glyph of the shop and the number of the stop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Notepad(items: List<ListEntry>, run: Run?, repo: ShopRepository) {
    val stopOf = HashMap<String, Int>()
    run?.stops?.forEachIndexed { i, s -> s.items.forEach { stopOf[it.text] = i + 1 } }
    val hand = ShopType.hand
    Box(Modifier.fillMaxWidth().padding(top = 28.dp, start = 34.dp, end = 78.dp)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 334.dp - 28.dp).graphicsLayer { rotationZ = -2f }
                .cssShadow(RoundedCornerShape(0.dp), y = 10.dp, blur = 22.dp, colour = Color(0x24000000))
                .background(Color(0xFFFFFEF6))
                .drawBehind {
                    val top = 30.dp.toPx()
                    var y = top + 35.dp.toPx()
                    while (y < size.height) {
                        drawRect(Color(0xFFC9D8EA), Offset(0f, y), Size(size.width, 1.dp.toPx()))
                        y += 36.dp.toPx()
                    }
                }
                .padding(start = 22.dp, end = 18.dp, top = 30.dp, bottom = 12.dp),
        ) {
            for (e in items) {
                val label = e.text
                val removeLabel = stringResource(R.string.shop_list_remove, label)
                Row(
                    Modifier.fillMaxWidth().height(36.dp)
                        .combinedClickable(onClickLabel = stringResource(R.string.shop_list_toggle, label), onLongClickLabel = stringResource(R.string.shop_list_remove, label), onLongClick = { repo.list.remove(e) }) { repo.list.toggleHome(e) }
                        .semantics {
                            contentDescription = label
                            customActions = listOf(CustomAccessibilityAction(removeLabel) { repo.list.remove(e); true })
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.text,
                        Modifier.weight(1f),
                        style = TextStyle(fontFamily = hand, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, textDecoration = if (e.atHome) TextDecoration.LineThrough else null),
                        color = if (e.atHome) HandSoft else HandInk,
                        maxLines = 1,
                    )
                    if (e.atHome) {
                        Text(stringResource(R.string.shop_list_at_home), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = HandSoft)
                    } else {
                        e.kind?.let { k -> Symbol(k.glyph, tint = hueOf(k.glyph) ?: Ink, size = 22.dp) }
                        stopOf[e.text]?.let { n ->
                            Box(Modifier.size(22.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
                                Text(n.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp), color = Color.White)
                            }
                        }
                    }
                }
            }
            AddRow(items, repo)
        }
        // The magnet holding the pad to the fridge.
        Box(
            Modifier.align(Alignment.TopCenter).offset(x = (-8).dp, y = (-14).dp).size(36.dp)
                .cssShadow(CircleShape, y = 4.dp, blur = 6.dp, colour = Color(0x4D000000))
                .background(Brush.radialGradient(0f to Color(0xFFFF9A76), 0.6f to Color(0xFFE8622C), 1f to Color(0xFFB8441B), center = Offset(36f, 32f), radius = 80f), CircleShape),
        )
    }
}

@Composable
private fun AddRow(items: List<ListEntry>, repo: ShopRepository) {
    var text by remember { mutableStateOf("") }
    val label = stringResource(R.string.shop_list_add_label)
    val submit = { if (repo.list.add(text)) text = "" }
    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+", style = TextStyle(fontFamily = ShopType.hand, fontSize = 25.sp), color = HandSoft)
        Box(Modifier.weight(1f)) {
            val style = TextStyle(fontFamily = ShopType.hand, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, color = HandInk)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(HandInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
            if (text.isEmpty()) Text(stringResource(R.string.shop_list_add), style = style, color = HandSoft)
        }
    }
    if (items.isEmpty()) Suggestions(repo)
}

/** Things that are on most lists, to start a pad that is still empty: a tap writes one down. */
@Composable
private fun Suggestions(repo: ShopRepository) {
    val lang = remember { Locale.getDefault().language }
    val names = remember(lang) { listOf("milk", "bread", "eggs", "apples", "cheese", "coffee").mapNotNull { id -> ShopItems.byId(id)?.name(lang) } }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.shop_list_suggest), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = HandSoft)
        for (row in names.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (n in row) {
                    Text(
                        "+ $n",
                        Modifier.tap(n) { repo.list.add(n) }.padding(vertical = 4.dp),
                        style = TextStyle(fontFamily = ShopType.hand, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                        color = HandSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanPills(selected: PlanBy, canCheap: Boolean, onPick: (PlanBy) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
            .cssShadow(RoundedCornerShape(24.dp), y = 2.dp, blur = 8.dp, colour = Color(0x0F000000))
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
            val fg = if (on) Color.White else Ink.copy(alpha = if (enabled) 1f else 0.35f)
            Row(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(20.dp)).background(if (on) Ink else Color.Transparent)
                    .then(if (enabled) Modifier.tap(label) { onPick(p) } else Modifier.semantics { contentDescription = label })
                    .semantics { this.selected = on },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(
                    when (p) { PlanBy.FEWEST -> "ui.route"; PlanBy.BEST -> "ui.star"; PlanBy.CHEAPEST -> "ui.tag" },
                    tint = fg, size = if (p == PlanBy.FEWEST) 16.dp else 15.dp,
                )
                Text(label, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = fg, maxLines = 1)
            }
        }
    }
}

/** The till receipt: stops with their walk and items, red closing warnings, the estimated basket, a barcode and a torn edge. */
@Composable
private fun Receipt(run: Run?, toBuy: List<ListEntry>, area: ShopArea, prices: ListPrices, by: PlanBy) {
    val mono = ShopType.receipt
    val base = TextStyle(fontFamily = mono, fontSize = 14.sp, color = Color(0xFF1D1D1D))
    val dash = Modifier.fillMaxWidth().height(2.dp).drawBehind {
        drawLine(Color(0xFF999999), Offset(0f, 1.dp.toPx()), Offset(size.width, 1.dp.toPx()), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }
    Column(Modifier.padding(horizontal = 44.dp).fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().cssShadow(RoundedCornerShape(0.dp), y = 8.dp, blur = 14.dp, colour = Color(0x29000000)).background(Color.White).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.shop_run_title), style = base.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 3.2.sp))
                val sub = when {
                    toBuy.isEmpty() -> stringResource(R.string.shop_run_none)
                    run == null -> stringResource(R.string.shop_run_planning)
                    run.stops.isEmpty() -> stringResource(R.string.shop_run_unmet)
                    else -> {
                        val km = String.format(Locale.getDefault(), "%.1f", run.metres / 1000.0)
                        if (run.stops.size == 1) stringResource(R.string.shop_run_summary_one, km, run.minutes) else stringResource(R.string.shop_run_summary, run.stops.size, km, run.minutes)
                    }
                }
                Text(sub, style = base.copy(fontSize = 12.sp, color = Color(0xFF555555), fontFeatureSettings = "tnum"), textAlign = TextAlign.Center)
            }
            if (run != null) {
                Box(dash)
                run.stops.forEachIndexed { i, s ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${i + 1}", style = base.copy(fontWeight = FontWeight.Bold))
                            Text(s.shop.name.uppercase(), Modifier.weight(1f), style = base.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${s.walkMinutes}′", style = base.copy(fontFeatureSettings = "tnum"))
                        }
                        Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.items.joinToString(", ") { it.text }, Modifier.weight(1f), style = base.copy(fontSize = 13.sp))
                            if (s.closesAt != null && s.closingSoon) {
                                Text(stringResource(R.string.shop_run_closes, s.closesAt.hm()), style = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB03A3A), fontFeatureSettings = "tnum"))
                            } else if (!s.hoursKnown) {
                                Text(stringResource(R.string.shop_run_hours_unknown), style = base.copy(fontSize = 13.sp, color = Color(0xFF888888)))
                            }
                        }
                    }
                }
                if (run.unmet.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shop_run_unmet), style = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB03A3A)))
                        Text(run.unmet.joinToString(", ") { it.text }, Modifier.padding(start = 16.dp), style = base.copy(fontSize = 13.sp))
                    }
                }
                if (run.late) Text(stringResource(R.string.shop_run_late), style = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB03A3A)))
                if (run.stops.isNotEmpty()) {
                    Box(dash)
                    val b = run.basket
                    Row {
                        Text(stringResource(R.string.shop_run_basket), Modifier.weight(1f), style = base.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                        Text(if (b != null) money(b.total, prices.index.currency) + if (b.known < b.of) "+" else "" else "?", style = base.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFeatureSettings = "tnum"))
                    }
                    Row {
                        Text(
                            if (prices.offline) stringResource(R.string.shop_run_prices_offline) else stringResource(R.string.shop_run_prices_known, run.basket?.known ?: 0, toBuy.size),
                            Modifier.weight(1f),
                            style = base.copy(fontSize = 12.sp, color = Color(0xFF555555)),
                        )
                        Text(stringResource(R.string.shop_run_done, run.doneAt.hm()), style = base.copy(fontSize = 12.sp, color = Color(0xFF555555), fontFeatureSettings = "tnum"))
                    }
                    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                        // The board's barcode: 288 wide, bars between x 40 and 248.
                        val k = size.width / 288f
                        var x = 40f
                        var h = run.stops.joinToString { it.shop.key }.hashCode().toLong()
                        while (x < 248f) {
                            h = h * 6364136223846793005L + 1442695040888963407L
                            val w = 1f + ((h ushr 40) % 4).toInt()
                            drawRect(Color(0xFF1D1D1D), Offset(x * k, 0f), Size(w * k, size.height))
                            x += w + 2f + ((h ushr 20) % 4).toInt()
                        }
                    }
                }
            }
        }
        TornEdge()
    }
}

/** The receipt's torn bottom: a row of white triangles, 12 px each, as the board's two gradients. */
@Composable
private fun TornEdge() {
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val tooth = 12.dp.toPx()
        val path = Path()
        var x = 0f
        path.moveTo(0f, 0f)
        while (x < size.width) {
            path.lineTo(x + tooth / 2f, size.height)
            path.lineTo(minOf(x + tooth, size.width), 0f)
            x += tooth
        }
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
