package app.atlas.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brands
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.time.LocalDate
import java.util.Locale


/**
 * The basket compared, as on the Compare board: a thermal receipt per shop (up to three) with the same product on every
 * line, a rubber BEST stamp on the cheapest, "One stop" against "Split the basket", how old the prices are, and where
 * the money is: the item with the widest gap between shops. The prices come from Open Prices (open data, no key) and from
 * prices you noted yourself; a price nobody has reported is a "?". Where Open Prices has nothing for the area, the
 * screen says so and offers to note prices.
 */
@Composable
fun CompareScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val items by repo.list.items.collectAsStateWithLifecycle()
    val notes by repo.priceNotes.items.collectAsStateWithLifecycle()
    val toBuy = items.filterNot { it.atHome }
    val data by produceState<PriceData?>(null, toBuy.map { it.text }, here) { value = repo.priceData(items, here) }
    val cmp = remember(data, notes, toBuy, now) {
        BasketCompare.compare(toBuy, data ?: PriceData(emptyMap(), emptyList()), notes, here, now.toLocalDate())
    }
    var plan by rememberSaveable { mutableStateOf("one") }
    var noting by rememberSaveable { mutableStateOf(false) }
    var snapped by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(argb(0xFFDCDDD8)).drawDots()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 110.dp)) {
            Box(Modifier.fillMaxWidth().height(124.dp)) { Header(nav, cmp, toBuy.size, data == null) { noting = true } }
            when {
                toBuy.isEmpty() -> StateMessage(
                    "cat.groceries", stringResource(R.string.shop_cmp_no_list), text = stringResource(R.string.shop_cmp_no_list_text),
                    action = { PillButton(onClick = { nav.go(Dest.Groceries("list")) }) { Text(stringResource(R.string.shop_cmp_open_list), style = app.atlas.ui.kit.Atlas.type.label) } },
                )
                data == null -> Line(stringResource(R.string.shop_loading), bt(13f, 400, 17f), SOFT, Modifier.padding(horizontal = 22.dp))
                cmp.empty -> StateMessage(
                    "ui.tag",
                    stringResource(if (data!!.error != null) R.string.shop_cmp_offline_title else R.string.shop_cmp_empty_title),
                    text = stringResource(if (data!!.error != null) R.string.shop_cmp_offline_text else R.string.shop_cmp_empty_text),
                    action = { PillButton(onClick = { noting = true }) { Symbol("ui.camera", size = 18.dp); Text(stringResource(R.string.shop_note_price), style = app.atlas.ui.kit.Atlas.type.label) } },
                )
                else -> Compared(cmp, plan, { plan = it }, snapped, { noting = true })
            }
        }
        val one = cmp.oneStop
        if (!cmp.empty && one != null) {
            val split = cmp.split
            val goLabel = if (plan == "split" && split != null) {
                stringResource(R.string.shop_go_split, split.minutes, split.order.joinToString(", ") { it.name })
            } else stringResource(R.string.shop_go_to, one.walkMinutes, one.store.name)
            Row(
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 26.dp + boardLift()).fillMaxWidth().height(56.dp)
                    .boardBox(0xFF2E5BFF, round(28f))
                    .clickable(role = Role.Button) {
                        nav.go(
                            if (plan == "split" && split != null) {
                                val last = split.order.last()
                                Dest.Route(
                                    last.at.lat, last.at.lon, last.name, mode = "WALK",
                                    via = split.order.dropLast(1).joinToString("|") { String.format(Locale.ROOT, "%.6f,%.6f,%s", it.at.lat, it.at.lon, it.name.replace('|', ' ')) }.ifEmpty { null },
                                )
                            } else Dest.Route(one.store.at.lat, one.store.at.lon, one.store.name, mode = "WALK"),
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("mode.walk", 20f, 20f, 0xFFFFFFFF)
                Line(goLabel, bt(16f, 600, 20.5f, tnum = true), 0xFFFFFFFF, Modifier.padding(end = 4.dp))
            }
        }
    }
    if (noting) NotePriceDialog(toBuy, here, onDone = { saved -> noting = false; if (saved) snapped = true })
}

private const val SOFT: Long = 0xFF464C5A
private const val GREEN: Long = 0xFF1E7A46
private const val PAPER_INK: Long = 0xFF1D1D1D

/** The board's dotted grey card: a 1 px dot of 5 % black in the middle of every 6 px square. */
private fun Modifier.drawDots(): Modifier = drawBehind {
    val step = 6.dp.toPx()
    val r = 1.15.dp.toPx()
    val dot = Color(0x0D000000)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(dot, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** The title row at y 44: back, "Basket" over what is compared, the camera for a price you saw. */
@Composable
private fun BoxScope.Header(nav: Navigator, cmp: Comparison, items: Int, loading: Boolean, onNote: () -> Unit) {
    Box(Modifier.at(16f, 46.5f, 44f, 44f).boardBox(0xCCFFFFFF, round(22f)).tap(stringResource(R.string.shop_back_pantry)) { nav.back() }, contentAlignment = Alignment.Center) {
        Sym("ui.back", 20f, 20f)
    }
    Block(stringResource(R.string.shop_basket), bt(32f, 400, 32f, SERIF, align = TextAlign.Center), INK, Modifier.at(70f, 39f, 272f, 41.5f).semantics { heading() })
    val sub = if (cmp.baskets.isEmpty() || loading) stringResource(R.string.shop_basket_sub_none, items) else stringResource(R.string.shop_basket_sub, items, cmp.baskets.size)
    Line(sub, bt(13f, 400, 17f, tnum = true, align = TextAlign.Center), SOFT, Modifier.at(70f, 76f, 272f, 17f))
    Box(Modifier.at(352f, 46.5f, 44f, 44f).boardBox(INK, round(22f)).tap(stringResource(R.string.shop_note_price), onNote), contentAlignment = Alignment.Center) {
        Sym("ui.camera", 19f, 19f, 0xFFFFFFFF)
    }
}

@Composable
private fun Compared(cmp: Comparison, plan: String, onPlan: (String) -> Unit, snapped: Boolean, onNote: () -> Unit) {
    val baskets = cmp.baskets
    val best = baskets.first()
    // The lines marked on each receipt: everything at the one stop, or what each shop is cheapest for in the split.
    val chosen: Map<String, Set<String>> = if (plan == "split" && cmp.split != null) {
        val m = HashMap<String, MutableSet<String>>()
        for (e in cmp.entries) {
            val pick = baskets.mapNotNull { b -> b.lines.firstOrNull { it.entry == e }?.price?.let { b to it } }.minByOrNull { it.second }?.first ?: continue
            m.getOrPut(pick.store.key) { HashSet() }.add(e.text)
        }
        m
    } else mapOf(best.store.key to cmp.entries.map { it.text }.toSet())
    // The board's receipts, 14 px from the sides and 8 apart: the best in the middle (128 wide), the others (122) lean
    // away from it, 16 and 26 px lower; three of them shrink to fit as flex items do.
    val ordered = if (baskets.size == 3) listOf(baskets[1], baskets[0], baskets[2]) else baskets
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        ordered.forEach { b ->
            val isBest = b === best
            val tilt = if (baskets.size == 3) (if (isBest) 0f else if (b === baskets[1]) -2f else 1.5f) else 0f
            val top = if (isBest) 0f else if (tilt < 0f) 16f else 26f
            Receipt(b, isBest, isBest || chosen.containsKey(b.store.key) && plan == "split", chosen[b.store.key].orEmpty(), tilt, Modifier.weight(if (isBest) 128f else 122f).padding(top = top.dp))
        }
    }
    Spacer(Modifier.height(64.dp))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlanCard(
            on = plan != "split", symbol = "ui.pin", title = stringResource(R.string.shop_one_stop),
            sub = stringResource(R.string.shop_split_sub, best.store.name, best.walkMinutes),
            price = money(best.total, cmp.currency) + if (best.complete) "" else "+", saving = null, onClick = { onPlan("one") },
        )
        val split = cmp.split
        if (split != null) {
            PlanCard(
                on = plan == "split", symbol = "ui.route", title = stringResource(R.string.shop_split_basket),
                sub = split.order.map { it.name }.let { names -> if (names.size == 2) stringResource(R.string.shop_then, names[0], names[1]) else names.joinToString(", ") } + " · ${split.minutes}′",
                price = money(split.total, cmp.currency), saving = if (split.saving >= 0.005) "−" + String.format(Locale.getDefault(), "%.2f", split.saving) else null,
                onClick = { onPlan("split") },
            )
        }
        cmp.freshness?.let { age ->
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Sym("ui.clock", 16f, 16f, SOFT)
                Line(
                    if (age.last == 0) stringResource(R.string.shop_reported_today)
                    else stringResource(R.string.shop_reported_ago, if (age.first == age.last) "${age.first}" else "${age.first}–${age.last}"),
                    bt(13f, 400, 17f, tnum = true), SOFT,
                )
            }
        }
    }
    cmp.gap?.let {
        Spacer(Modifier.height(15.dp))
        MoneyCard(it, cmp.currency, snapped, onNote)
    }
}

/** A dashed rule of the receipts: `border-top: 1px dashed #999`. */
@Composable
private fun Dashes() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        val w = 1.dp.toPx()
        drawLine(Color(0xFF999999), Offset(0f, w / 2), Offset(size.width, w / 2), strokeWidth = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * w, 3 * w)))
    }
}

/** The receipts' highlighter: `linear-gradient(100deg, …)` of yellow at 60 %, soft at both ends. */
private val Marker = cssLinear(100f, 0f to 0x00FFDE3C, 0.05f to 0x99FFDE3C, 0.95f to 0x99FFDE3C, 1f to 0x00FFDE3C)

/**
 * A thermal receipt, as the board's (padding 12 10, 6 px between lines, Courier Prime 12): the shop's mark, its lines
 * with prices, a total and how far it is; [ringed] when it is part of the plan (an inset 2 px green ring), torn along
 * the bottom in 8 px teeth, under a drop shadow.
 */
@Composable
private fun Receipt(b: StoreBasket, best: Boolean, ringed: Boolean, marked: Set<String>, tilt: Float, modifier: Modifier) {
    val r12 = bt(12f, 400, 13.5f, COURIER, tnum = true)
    val brand = remember(b.store.name) { Brands.search(b.store.name, limit = 1).firstOrNull()?.takeIf { it.brand.equals(b.store.name, true) || it.name.startsWith(b.store.name, true) } }
    val shadow = if (best) CssShadow(0f, 14f, 18f, 0f, 0x38000000) else CssShadow(0f, 6f, 10f, 0f, 0x24000000)
    Box(modifier.graphicsLayer { rotationZ = tilt }) {
        Column {
            Column(
                Modifier.fillMaxWidth().boardBox(0xFFFFFFFF, shadows = listOf(shadow) + if (ringed) listOf(CssShadow(0f, 0f, 0f, 2f, GREEN, inset = true)) else emptyList(), clip = false)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReceiptLogo(brand, b.store.name, b.store.key, Modifier.align(Alignment.CenterHorizontally))
                Line(b.store.name.uppercase(), bt(12f, 700, 13.5f, COURIER, align = TextAlign.Center), PAPER_INK, Modifier.fillMaxWidth())
                Dashes()
                for (l in b.lines) {
                    val missing = l.price == null
                    val ink = if (missing) 0xFF999999 else PAPER_INK
                    Row(
                        Modifier.fillMaxWidth().layout { m, c ->
                            // margin 0 -4px, padding 0 4px: the marker reaches 4 px past the text on both sides
                            val p = m.measure(c.copy(maxWidth = c.maxWidth + 8.dp.roundToPx(), minWidth = c.maxWidth + 8.dp.roundToPx()))
                            layout(c.maxWidth, p.height) { p.place(-4.dp.roundToPx(), 0) }
                        }.then(if (l.entry.text in marked && !missing) Modifier.boardBox(grad = listOf(Marker), r = round(2f)) else Modifier).padding(horizontal = 4.dp),
                    ) {
                        Line(l.entry.text, r12, ink, Modifier.weight(1f))
                        Line(l.price?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "?", r12, ink)
                    }
                }
                Dashes()
                Row {
                    val tot = if (best) bt(15f, 700, 16.5f, COURIER, tnum = true) else bt(14f, 700, 16f, COURIER, tnum = true)
                    val ink = if (best) GREEN else PAPER_INK
                    Line("TOT", tot, ink, Modifier.weight(1f))
                    Line(String.format(Locale.getDefault(), "%.2f", b.total) + if (b.complete) "" else "+", tot, ink)
                }
                Line("${b.walkMinutes}′ · ${b.known}/${b.lines.size}", bt(11f, 400, 12.5f, COURIER, tnum = true), 0xFF666666)
            }
            Teeth(8f)
        }
        if (best) {
            // The rubber stamp: left 48, top -14, padding 3 9 1, a 3 px red border, 6 px corners, turned 14° back, at 82 %.
            Box(
                Modifier.align(Alignment.TopStart).offset(48.dp, (-14).dp).graphicsLayer { rotationZ = -14f; alpha = 0.82f }
                    .boardBox(0xB3FFFFFF, round(6f), border = ring(3f, 0xFFC8322A)).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            ) { Line(stringResource(R.string.shop_best), bt(20f, 400, 20f, ELITE, spacing = 2f), 0xFFC8322A) }
        }
    }
}

/** A receipt's shop mark, 34 round: the chain's own logo on its colour, else its first letter in Geist Black 18. */
@Composable
private fun ReceiptLogo(brand: app.atlas.places.Brand?, name: String, key: String, modifier: Modifier) {
    val bg = brandColour(brand, key)
    val fg = brand?.textColour?.let { Color(it) } ?: app.atlas.ui.kit.contentColourFor(bg)
    Box(modifier.size(34.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        val slug = brand?.logoSlug
        if (slug != null) Symbol("logo-$slug", tint = fg, size = 20.dp)
        else Text(name.trim().take(1).uppercase(), style = bt(18f, 900, 23.5f), color = fg)
    }
}

/** A torn edge of [tooth] px teeth, as the boards' two gradient tiles (135° and 225°, white to the half). */
@Composable
internal fun Teeth(tooth: Float) {
    Canvas(Modifier.fillMaxWidth().height(tooth.dp)) {
        val t = tooth.dp.toPx()
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(0f, size.height)
        var x = 0f
        while (x < size.width) {
            path.lineTo(minOf(x + t / 2f, size.width), size.height / 2f)
            path.lineTo(minOf(x + t, size.width), size.height)
            x += t
        }
        path.lineTo(size.width, 0f)
        path.close()
        drawPath(path, Color.White)
    }
}

/** One of the two plans: 76 high, 22 px corners, white with a 2 px blue ring when chosen, else 70 % white. */
@Composable
private fun PlanCard(on: Boolean, symbol: String, title: String, sub: String, price: String, saving: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .boardBox(if (on) 0xFFFFFFFF else 0xB3FFFFFF, round(22f), if (on) listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF2E5BFF)) else emptyList())
            .tap(title, onClick).semantics { selected = on }.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).boardBox(if (on) 0xFFE4EAFF else 0xFFF1EEE8, round(14f)), contentAlignment = Alignment.Center) {
            Sym(symbol, 22f, 22f, if (on) 0xFF2E5BFF else INK)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(title, bt(16f, 700, 20.5f), INK)
            Line(sub, bt(13f, 400, 17f, tnum = true), SOFT)
        }
        Column(horizontalAlignment = Alignment.End) {
            Line(price, bt(22f, 800, 28.5f, tnum = true), INK)
            if (saving != null) Line(saving, bt(12f, 700, 15.5f, tnum = true), GREEN)
        }
    }
}

/** "Where the money is": the item with the widest price gap, a bar per shop, and a way to note a price at a shop with none. */
@Composable
private fun MoneyCard(gap: PriceGap, currency: String, snapped: Boolean, onNote: () -> Unit) {
    val worst = gap.worst ?: return
    val best = gap.best
    val missing = gap.rows.firstOrNull { it.second == null }?.first
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().boardBox(0xFFFFFFFF, round(20f), listOf(CssShadow(0f, 2f, 10f, 0f, 0x0F000000)), clip = false)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Line(stringResource(R.string.shop_money_where, gap.entry.text.uppercase()), bt(12f, 800, 15.5f, spacing = 1.44f), 0xFF5A5F6C, Modifier.weight(1f, fill = false))
            val what = gap.product?.quantity ?: gap.product?.name
            if (what != null) Line(what, bt(11f, 400, 14f), 0xFF676D7C, Modifier.padding(start = 8.dp))
        }
        for ((store, price) in gap.rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Line(store.name, bt(13f, 700, 17f), INK, Modifier.width(76.dp))
                Box(Modifier.weight(1f).height(10.dp)) {
                    if (price != null) {
                        Box(Modifier.fillMaxSize().boardBox(0xFFEEF0F3, round(5f)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth((price / worst).toFloat().coerceIn(0.05f, 1f)).boardBox(if (price == best) GREEN else 0xFFE8862C, round(5f)))
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 1.5.dp.toPx()
                            drawRoundRect(
                                Color(0xFFB9BEC8), topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx() - w / 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * w, 3 * w))),
                            )
                        }
                    }
                }
                Line(
                    if (price == null) stringResource(R.string.shop_no_price) else String.format(Locale.getDefault(), "%.2f", price) + if (price == best) " " + stringResource(R.string.shop_best_word) else "",
                    bt(13f, 800, 17f, tnum = true, align = TextAlign.End),
                    if (price == null) 0xFF8A8F9C else if (price == best) GREEN else INK,
                    Modifier.width(74.dp),
                )
            }
        }
        val label = if (snapped) stringResource(R.string.shop_snap_done) else stringResource(R.string.shop_snap_at, missing?.name ?: gap.rows.last().first.name)
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).height(34.dp).boardBox(if (snapped) 0xFFE3F1E8 else 0xFFF1F3F6, round(17f)).tap(label, onNote),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!snapped) Sym("ui.camera", 16f, 16f)
            Line(label, bt(13f, 700, 17f), if (snapped) 0xFF1F6B3E else INK)
        }
    }
}

/** Writing down a price: what, where (the shops around that sell it) and how much. Kept on the phone, used in the comparison. */
@Composable
private fun NotePriceDialog(toBuy: List<ListEntry>, here: app.atlas.geo.LatLon, onDone: (Boolean) -> Unit) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val now = rememberLiveNow()
    var what by rememberSaveable { mutableStateOf(toBuy.firstOrNull()?.text) }
    var where by rememberSaveable { mutableStateOf<String?>(null) }
    var price by rememberSaveable { mutableStateOf("") }
    val entry = toBuy.firstOrNull { it.text == what }
    val filter = remember(entry) { RunPlanner.filterFor(listOfNotNull(entry)) }
    val area by produceState(ShopArea.empty(here), filter.toText(), here) { repo.area("note:" + filter.toText(), filter, here).collect { value = it } }
    val shops = remember(area, entry) { (entry?.let { RunPlanner.sellingShops(it, area.places).map { p -> p.first } } ?: area.places).take(6) }
    val value = price.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val shop = shops.firstOrNull { it.key == where }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.shop_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.shop_note_item), style = app.atlas.ui.kit.Atlas.type.kicker)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    toBuy.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { e -> app.atlas.ui.kit.ToggleChip(e.text, what == e.text, { what = e.text; where = null }) }
                        }
                    }
                }
                Text(stringResource(R.string.shop_note_shop), style = app.atlas.ui.kit.Atlas.type.kicker)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shops.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { s -> app.atlas.ui.kit.ToggleChip(s.name, where == s.key, { where = s.key }) }
                        }
                    }
                }
                OutlinedTextField(
                    value = price, onValueChange = { price = it.take(8) }, singleLine = true,
                    label = { Text(stringResource(R.string.shop_note_price_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(stringResource(R.string.shop_note_hint), style = app.atlas.ui.kit.Atlas.type.caption)
            }
        },
        confirmButton = {
            TextButton(
                enabled = entry != null && shop != null && value != null,
                onClick = {
                    repo.priceNotes.add(PriceNote(entry!!.text, shop!!.key, shop.name, value!!, LocalDate.of(now.year, now.month, now.dayOfMonth).toString(), shop.location.lat, shop.location.lon))
                    onDone(true)
                },
            ) { Text(stringResource(R.string.shop_note_save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text(stringResource(R.string.shop_cancel)) } },
    )
}
