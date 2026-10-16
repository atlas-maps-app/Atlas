package app.atlas.ui.shop

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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.time.LocalDate
import java.util.Locale

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF464C5A)
private val Grey = Color(0xFF676D7C)
private val Green = Color(0xFF1E7A46)

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

    Box(Modifier.fillMaxSize().background(Color(0xFFDCDDD8)).drawBehind {
        val step = 6.dp.toPx()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawCircle(Color.Black.copy(alpha = 0.05f), 0.6.dp.toPx(), Offset(x + 1f, y + 1f))
                x += step
            }
            y += step
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 4.dp, bottom = 100.dp)) {
            Header(nav, cmp, toBuy.size, data == null) { noting = true }
            Spacer(Modifier.height(32.dp))
            when {
                toBuy.isEmpty() -> StateMessage(
                    "cat.groceries", stringResource(R.string.shop_cmp_no_list), text = stringResource(R.string.shop_cmp_no_list_text),
                    action = { PillButton(onClick = { nav.go(Dest.Groceries("list")) }) { Text(stringResource(R.string.shop_cmp_open_list), style = app.atlas.ui.kit.Atlas.type.label) } },
                )
                data == null -> Text(stringResource(R.string.shop_loading), Modifier.padding(horizontal = 16.dp), style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp), color = Soft)
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
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp)) {
                BigPill(
                    goLabel,
                    onClick = {
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
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Symbol("mode.walk", tint = Color.White, size = 20.dp) },
                )
            }
        }
    }
    if (noting) NotePriceDialog(toBuy, here, onDone = { saved -> noting = false; if (saved) snapped = true })
}

@Composable
private fun Header(nav: Navigator, cmp: Comparison, items: Int, loading: Boolean, onNote: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundSymbolButton("ui.back", stringResource(R.string.shop_back_pantry), { nav.back() }, size = 44.dp, fill = Color.White.copy(alpha = 0.8f), iconSize = 20.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.shop_basket), style = TextStyle(fontFamily = SerifFamily, fontSize = 32.sp, lineHeight = 32.sp), color = Ink)
            val sub = if (cmp.baskets.isEmpty() || loading) stringResource(R.string.shop_basket_sub_none, items) else stringResource(R.string.shop_basket_sub, items, cmp.baskets.size)
            Text(sub, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Soft)
        }
        RoundSymbolButton("ui.camera", stringResource(R.string.shop_note_price), onNote, size = 44.dp, fill = Ink, tint = Color.White, iconSize = 19.dp)
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
    // Receipts in the order of the board: the middle one is the best, the others lean away from it.
    val ordered = if (baskets.size == 3) listOf(baskets[1], baskets[0], baskets[2]) else baskets
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        ordered.forEach { b ->
            val isBest = b === best
            val tilt = if (baskets.size == 3) (if (isBest) 0f else if (b === baskets[1]) -2f else 1.5f) else 0f
            val top = if (isBest) 0.dp else if (tilt < 0f) 16.dp else 26.dp
            Receipt(b, isBest, chosen[b.store.key].orEmpty(), tilt, Modifier.weight(if (isBest) 128f else 122f).padding(top = top))
        }
    }
    Spacer(Modifier.height(28.dp))
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
                Symbol("ui.clock", tint = Soft, size = 16.dp)
                Text(
                    if (age.last == 0) stringResource(R.string.shop_reported_today)
                    else stringResource(R.string.shop_reported_ago, if (age.first == age.last) "${age.first}" else "${age.first}–${age.last}"),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Soft,
                )
            }
        }
        cmp.gap?.let { MoneyCard(it, cmp.currency, snapped, onNote) }
    }
}

/** A thermal receipt: the shop's mark, its lines with prices, a total and how far it is; torn along the bottom. */
@Composable
private fun Receipt(b: StoreBasket, best: Boolean, marked: Set<String>, tilt: Float, modifier: Modifier) {
    val mono = ShopType.receipt
    val base = TextStyle(fontFamily = mono, fontSize = 12.sp, color = Color(0xFF1D1D1D))
    val brand = remember(b.store.name) { Brands.search(b.store.name, limit = 1).firstOrNull()?.takeIf { it.brand.equals(b.store.name, true) || it.name.startsWith(b.store.name, true) } }
    val dash = Modifier.fillMaxWidth().height(1.dp).drawBehind {
        drawLine(Color(0xFF999999), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
    }
    Box(modifier.graphicsLayer { rotationZ = tilt }) {
        Column(Modifier.cssShadow(RoundedCornerShape(0.dp), y = if (best) 14.dp else 6.dp, blur = if (best) 18.dp else 10.dp, colour = Color(if (best) 0x38000000 else 0x24000000))) {
            Column(
                Modifier.fillMaxWidth().background(Color.White).then(if (best) Modifier.border(2.dp, Green) else Modifier).padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShopLogo(brand, b.store.name, 34.dp, Modifier.align(Alignment.CenterHorizontally), key = b.store.key, round = true)
                Text(b.store.name.uppercase(), Modifier.fillMaxWidth(), style = base.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(dash)
                for (l in b.lines) {
                    val missing = l.price == null
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 0.dp).then(
                            if (l.entry.text in marked && !missing) Modifier.drawBehind {
                                drawRect(Brush.horizontalGradient(0f to Color(0x00FFDE3C), 0.05f to Color(0x99FFDE3C), 0.95f to Color(0x99FFDE3C), 1f to Color(0x00FFDE3C)), Offset(-4.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(size.width + 8.dp.toPx(), size.height))
                            } else Modifier,
                        ),
                    ) {
                        Text(l.entry.text, Modifier.weight(1f), style = base.copy(color = if (missing) Color(0xFF999999) else base.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l.price?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "?", style = base.copy(color = if (missing) Color(0xFF999999) else base.color, fontFeatureSettings = "tnum"))
                    }
                }
                Box(dash)
                Row {
                    Text("TOT", Modifier.weight(1f), style = base.copy(fontWeight = FontWeight.Bold, fontSize = if (best) 15.sp else 14.sp, color = if (best) Green else base.color))
                    Text(String.format(Locale.getDefault(), "%.2f", b.total) + if (b.complete) "" else "+", style = base.copy(fontWeight = FontWeight.Bold, fontSize = if (best) 15.sp else 14.sp, color = if (best) Green else base.color, fontFeatureSettings = "tnum"))
                }
                Text("${b.walkMinutes}′ · ${b.known}/${b.lines.size}", style = base.copy(fontSize = 11.sp, color = Color(0xFF666666), fontFeatureSettings = "tnum"))
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val tooth = 8.dp.toPx()
                val p = Path()
                var x = 0f
                p.moveTo(0f, 0f)
                while (x < size.width) {
                    p.lineTo(x + tooth / 2f, size.height)
                    p.lineTo(minOf(x + tooth, size.width), 0f)
                    x += tooth
                }
                p.close()
                drawPath(p, Color.White)
            }
        }
        if (best) {
            Text(
                stringResource(R.string.shop_best),
                Modifier.align(Alignment.TopStart).offset(x = 38.dp, y = (-14).dp).graphicsLayer { rotationZ = -14f; alpha = 0.82f }
                    .border(3.dp, Color(0xFFC8322A), RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(start = 9.dp, end = 9.dp, top = 3.dp, bottom = 1.dp),
                style = TextStyle(fontFamily = ShopType.typewriter, fontSize = 20.sp, letterSpacing = 2.sp),
                color = Color(0xFFC8322A),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PlanCard(on: Boolean, symbol: String, title: String, sub: String, price: String, saving: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.7f))
            .then(if (on) Modifier.border(2.dp, Color(0xFF2E5BFF), shape) else Modifier)
            .tap(title, onClick).semantics { selected = on }.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(if (on) Color(0xFFE4EAFF) else Color(0xFFF1EEE8), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Symbol(symbol, tint = if (on) Color(0xFF2E5BFF) else Ink, size = 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp), color = Ink)
            Text(sub, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, fontFeatureSettings = "tnum"), color = Ink)
            if (saving != null) Text(saving, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFeatureSettings = "tnum"), color = Green)
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
        Modifier.fillMaxWidth().cssShadow(RoundedCornerShape(20.dp), y = 2.dp, blur = 10.dp, colour = Color(0x0F000000)).background(Color.White, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.shop_money_where, gap.entry.text.uppercase()), Modifier.weight(1f), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.4.sp), color = Color(0xFF5A5F6C), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val what = gap.product?.quantity ?: gap.product?.name
            if (what != null) Text(what, Modifier.padding(start = 8.dp), style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp), color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        for ((store, price) in gap.rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(store.name, Modifier.width(76.dp), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(10.dp)) {
                    if (price != null) {
                        Box(Modifier.fillMaxSize().background(Color(0xFFEEF0F3), RoundedCornerShape(5.dp)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth((price / worst).toFloat().coerceIn(0.05f, 1f)).background(if (price == best) Green else Color(0xFFE8862C), RoundedCornerShape(5.dp)))
                    } else {
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                drawRoundRect(Color(0xFFB9BEC8), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                            },
                        )
                    }
                }
                Text(
                    if (price == null) stringResource(R.string.shop_no_price) else String.format(Locale.getDefault(), "%.2f", price) + if (price == best) " " + stringResource(R.string.shop_best_word) else "",
                    Modifier.width(74.dp),
                    style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, fontFeatureSettings = "tnum"),
                    color = if (price == null) Color(0xFF8A8F9C) else if (price == best) Green else Ink,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
        val label = if (snapped) stringResource(R.string.shop_snap_done) else stringResource(R.string.shop_snap_at, missing?.name ?: gap.rows.last().first.name)
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (snapped) Color(0xFFE3F1E8) else Color(0xFFF1F3F6)).tap(label, onNote),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(if (snapped) "ui.check" else "ui.camera", tint = if (snapped) Color(0xFF1F6B3E) else Ink, size = 16.dp)
            Text(label, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = if (snapped) Color(0xFF1F6B3E) else Ink, maxLines = 1)
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
