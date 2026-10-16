package app.atlas.ui.shop

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRepeatingLinear
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.util.Locale


/**
 * The five dots of a likelihood (`●●●○○` on the board, a monospace cell of 0.6 em and 1 px of spacing each): filled in
 * rust (tan when it is only a maybe), the rest as tan rings.
 */
@Composable
private fun Dots(n: Int, size: Float, tab: Boolean) {
    val k = n.coerceIn(0, 5)
    val cell = size * 0.6f + 1f
    Canvas(Modifier.size((cell * 5).dp, size.dp)) {
        val c = cell.dp.toPx()
        val r = size * 0.28f * density
        val ring = 1.1f * density
        for (i in 0 until 5) {
            val centre = Offset(c * i + (c - 1.dp.toPx()) / 2f, this.size.height / 2f)
            if (i < k) drawCircle(argb(if (k <= 1 && tab) TAN else RUST), r, centre)
            else drawCircle(argb(TAN), r - ring / 2f, centre, style = androidx.compose.ui.graphics.drawscope.Stroke(ring))
        }
    }
}

/**
 * "Where can I buy X?", as the Find board: a library card catalogue. The question is the drawer's brass label; each index
 * card is a kind of shop that may sell it, with five dots for how likely, stacked likeliest at the front; the front card
 * lists the shops of that kind around you with closing times and walk minutes, and "ask" rings the shop to check stock.
 * A tap on a tab brings its card to the front. The answers come from the grocery table, the goods catalogue and the
 * preset terms of `:places`, never from guesses.
 */
@Composable
fun FindScreen(initial: String, nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val lang = remember { Locale.getDefault().language }
    var text by rememberSaveable { mutableStateOf(initial) }
    var submitted by rememberSaveable { mutableStateOf(initial) }
    var picked by rememberSaveable { mutableStateOf(0) }
    var openNowChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val (things, askedOpen) = remember(submitted) { FindModel.parts(submitted) }
    val openNow = openNowChoice ?: askedOpen
    val thing = things.getOrNull(picked.coerceIn(0, (things.size - 1).coerceAtLeast(0))).orEmpty()
    val answer = remember(thing) { FindModel.resolve(thing, lang) }
    var frontId by rememberSaveable(thing) { mutableStateOf<String?>(null) }
    val front = answer?.sellers?.firstOrNull { it.id == frontId } ?: answer?.sellers?.firstOrNull()
    val area by produceState(ShopArea.empty(here), here, front?.filter?.toText()) {
        if (front != null) repo.area("find:" + front.filter.toText(), front.filter, here, FIND_RADIUS_M).collect { value = it } else value = ShopArea.empty(here).let { ShopArea(here, FIND_RADIUS_M, emptyList(), null, loading = false) }
    }
    val shops = remember(area, front, now, openNow) { front?.let { FindModel.shops(it, area.places, area, now, openNow) }.orEmpty() }

    Box(Modifier.fillMaxSize().background(argb(WOOD)).drawGrain()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop())) {
            Spacer(Modifier.height(42.dp))
            TopBar(text, { text = it }, openNow, { openNowChoice = !openNow }, nav) { submitted = text.trim(); picked = 0; openNowChoice = null }
            if (things.size > 1) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    things.forEachIndexed { i, t ->
                        val on = i == picked
                        Line(
                            t, bt(14f, 400, 18f, ELITE), if (on) INK_F else 0xFFF4EBDD,
                            Modifier.boardBox(if (on) 0xFFFBF6EA else 0x24FFFFFF, round(18f)).tap(t) { picked = i }.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            // The catalogue's first tab stands 10 px under the bar (the cards from y 128, their tabs 24 above).
            Spacer(Modifier.height(10.dp))
            when {
                thing.isEmpty() -> EmptyDrawer { q -> text = q; submitted = q; picked = 0 }
                answer == null -> Column(Modifier.padding(horizontal = 22.dp).boardBox(0xFFFBF6EA, round(16f))) {
                    StateMessage(
                        "ui.search", stringResource(R.string.shop_find_unknown, thing), text = stringResource(R.string.shop_find_unknown_text), tint = argb(INK_F),
                        action = { PillButton(onClick = { nav.go(Dest.Search(thing)) }) { Text(stringResource(R.string.shop_find_search_map), style = app.atlas.ui.kit.Atlas.type.label) } },
                    )
                }
                else -> Catalogue(answer, front!!, shops, area, now, nav) { frontId = it.id }
            }
            // Room for the drawer front, which overlaps the card's foot by 17 px as the board's does.
            Spacer(Modifier.height(if (answer != null) 177.dp + boardLift() else 24.dp))
        }
        if (answer != null) Drawer(thing, answer.sellers.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp + boardLift()))
    }
}

private const val FIND_RADIUS_M = 1_500.0

private const val WOOD: Long = 0xFF5A3B22
private const val INK_F: Long = 0xFF2A1D14
private const val RUST: Long = 0xFFB8461A
private const val TAN: Long = 0xFFC9B089

/** The cabinet's vertical grain, as the board's repeating gradient: 3 px of 12 % black, 4 of 3 % white, 8 of 5 % black, every 15 px. */
private fun Modifier.drawGrain(): Modifier = drawBehind {
    var x = 0f
    val u = 1.dp.toPx()
    while (x < size.width) {
        drawRect(Color(0x1F000000), Offset(x, 0f), Size(3 * u, size.height))
        drawRect(Color(0x08FFFFFF), Offset(x + 3 * u, 0f), Size(4 * u, size.height))
        drawRect(Color(0x0D000000), Offset(x + 7 * u, 0f), Size(8 * u, size.height))
        x += 15 * u
    }
}

/** The bar at y 42: back (48 px, 14 % white) and the question on cream (52 high), with the open-now switch at its end. */
@Composable
private fun TopBar(text: String, onText: (String) -> Unit, openNow: Boolean, onOpen: () -> Unit, nav: Navigator, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).boardBox(0x24FFFFFF, round(24f)).tap(stringResource(R.string.shop_find_back)) { nav.back() }, contentAlignment = Alignment.Center) {
            Sym("ui.back", 20f, 20f, 0xFFF4EBDD)
        }
        val label = stringResource(R.string.shop_find_hint)
        Row(
            Modifier.weight(1f).height(52.dp).boardBox(0xFFFBF6EA, round(26f)).padding(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Sym("fact.new", 18f, 18f, 0xFF7B4DFF)
            val style = bt(16f, 600, 20.5f)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    text, onText, singleLine = true, textStyle = style.copy(color = argb(INK_F)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
                if (text.isEmpty()) Line(label, style, 0xFF9A8B76)
            }
            // The board's open-now mark, 16 px, 16 from the end (its target the bar's last 32 × 52).
            Box(
                Modifier.size(32.dp, 52.dp).tap(stringResource(R.string.shop_find_open_now)) { onOpen() }.semantics { selected = openNow },
                contentAlignment = Alignment.CenterStart,
            ) { Sym("fact.open", 16f, 16f, if (openNow) 0xFF1F9D55 else 0xFF9A8B76) }
        }
    }
}

@Composable
private fun EmptyDrawer(onTry: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp).fillMaxWidth().boardBox(0xFFFFFCF4, round(8f)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Line(stringResource(R.string.shop_find_empty), bt(15f, 400, 17f, COURIER), INK_F, lines = 3)
        for (e in FindModel.examples) Line("→ $e", bt(16f, 400, 20f, ELITE), RUST, Modifier.tap(e) { onTry(e) }.padding(vertical = 4.dp))
    }
}

/** The cards behind the front one, back to front: their paper, how far in from the left, where the tab sits and its ink. */
private val BackCards = longArrayOf(0xFFEFE5D0, 0xFFF2E9D6, 0xFFF6EEDD, 0xFFF9F3E6)
private val BackLeft = floatArrayOf(36f, 24f, 12f, 0f)
private val TabLeft = floatArrayOf(190f, 150f, 96f, 40f)
private val TabInk = longArrayOf(0xFF6B5A45, 0xFF5A4A35, 0xFF4A3A28, 0xFF3A2A18)

/**
 * The card catalogue, as the board's (22 px from the sides): up to four cards of the other kinds behind, 42 px apart,
 * each 90 high with its tab; the front card at least 440 high, ruled every 32 px, a red margin at 34, with the kind's
 * tags, its shops (64 px rows) and "ask" and the walk.
 */
@Composable
private fun Catalogue(answer: FindAnswer, front: FindSeller, shops: List<ShopPlace>, area: ShopArea, now: java.time.LocalDateTime, nav: Navigator, onPick: (FindSeller) -> Unit) {
    // The other kinds' cards behind, the least likely at the back; the picked one in front.
    val backs = answer.sellers.filter { it.id != front.id }.sortedBy { it.likelihood }.takeLast(4)
    val m = backs.size
    val rows = shops.take(3)
    val frontTop = 42f * m + if (m > 0) 2f else 0f
    // padding 44 + tags 32 + rows + the buttons' row 64 + 16
    val frontHeight = maxOf(440f, 44f + 32f + rows.size.coerceAtLeast(1) * 64f + 64f + 16f)
    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height((24f + frontTop + frontHeight).dp)) {
        backs.forEachIndexed { k, s ->
            val slot = 4 - m + k
            val top = 24f + 42f * k
            Box(
                Modifier.padding(start = BackLeft[slot].dp).fillMaxWidth().offset(y = top.dp).height(90.dp)
                    .boardBox(BackCards[slot], CssCorners(8f, 8f, 0f, 0f), listOf(if (slot == 0) CssShadow(0f, -1f, 0f, 0f, 0x1A000000) else CssShadow(0f, -2f, 3f, 0f, 0x1F000000)), clip = false),
            )
            Tab(s, BackCards[slot], TabInk[slot], Modifier.offset(x = TabLeft[slot].dp, y = (top - 24f + if (slot == 0) -1f else 0f).dp), small = true) { onPick(s) }
        }
        Column(
            Modifier.offset(y = (24f + frontTop).dp).fillMaxWidth().height(frontHeight.dp)
                .boardBox(
                    0xFFFFFCF4, round(8f), listOf(CssShadow(0f, -3f, 6f, 0f, 0x26000000), CssShadow(0f, 10f, 20f, 0f, 0x4C000000)),
                    grad = listOf(cssRepeatingLinear(180f, 32f, 0f to 0x00000000, 31f to 0x00000000, 31f to 0xFFE7DCC8, 32f to 0xFFE7DCC8)), clip = false,
                )
                .drawBehind { drawRect(Color(0x99D9534F), Offset(0f, 34.dp.toPx()), Size(size.width, 1.5.dp.toPx())) }
                .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 16.dp),
        ) {
            Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) { Line(front.tagLine, bt(13f, 400, 14.5f, COURIER), 0xFF7A6A55) }
            if (rows.isEmpty()) {
                Box(Modifier.height(64.dp), contentAlignment = Alignment.CenterStart) {
                    Line(stringResource(if (area.waiting) R.string.shop_loading else R.string.shop_find_none_near), bt(14f, 400, 16f, COURIER), 0xFF5A4A35, lines = 2)
                }
            }
            rows.forEach { p -> ShopRow(p, front, area, now) { nav.go(Dest.Place.of(p.place)) } }
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val first = rows.firstOrNull()
                val phone = first?.let { it.tags["phone"] ?: it.tags["contact:phone"] }?.let(::dialIntent)
                val context = LocalContext.current
                val ask = stringResource(R.string.shop_find_ask_d)
                if (phone != null) {
                    Row(
                        Modifier.height(44.dp).boardBox(r = round(22f), border = ring(1.5f, INK_F))
                            .tap(ask) { startSafely(context, phone) }.padding(horizontal = 15.5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym("ui.phone", 16f, 16f, INK_F)
                        Line(stringResource(R.string.shop_find_ask), bt(13f, 700, 14.5f, COURIER), INK_F)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (first != null) {
                    Row(
                        Modifier.height(48.dp).boardBox(0xFF2E5BFF, round(24f)).tap("${first.walkMinutes}′") { nav.go(Dest.Route(first.location.lat, first.location.lon, first.name, mode = "WALK")) }
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym("mode.walk", 18f, 18f, 0xFFFFFFFF)
                        Line("${first.walkMinutes}′", bt(16f, 600, 20.5f, tnum = true), 0xFFFFFFFF)
                    }
                }
            }
        }
        Tab(front, 0xFFFFFCF4, INK_F, Modifier.offset(x = 0.dp, y = frontTop.dp), small = false) {}
    }
}

/** An index tab (26 high, 6 px top corners, 12 px each side): the kind in Special Elite and its five dots, in the card's own paper. */
@Composable
private fun Tab(s: FindSeller, paper: Long, ink: Long, modifier: Modifier, small: Boolean, onClick: () -> Unit) {
    val desc = stringResource(R.string.shop_find_likely, s.label, s.likelihood)
    val size = if (small) 13f else 14f
    Row(
        modifier.height(26.dp).boardBox(paper, CssCorners(6f, 6f, 0f, 0f))
            .then(if (small) Modifier.tap(desc, onClick) else Modifier.semantics { contentDescription = desc }).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(s.label, bt(size, 400, size, ELITE), ink)
        Dots(s.likelihood, size, tab = small)
    }
}

/** A shop on the front card: 64 high, its 44 px mark, the name and hours in Courier Prime (red when it closes soon), the walk. */
@Composable
private fun ShopRow(p: ShopPlace, seller: FindSeller, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val closed = p.openness(now, area.hours) == Open.CLOSED
    Row(Modifier.fillMaxWidth().height(64.dp).alpha(if (closed) 0.6f else 1f).tap(p.name, onClick), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p.brand != null || p.tags["brand"] != null) {
            ShopLogo(p.brand, p.tags["brand"] ?: p.name, 44.dp, key = p.tags["brand"])
        } else {
            Box(Modifier.size(44.dp).boardBox(seller.colour, round(12f)), contentAlignment = Alignment.Center) { Sym(seller.symbol, 22f, 22f, 0xFF1A1A1A) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(p.name, bt(17f, 700, 19.5f, COURIER), INK_F)
            val soon = p.openness(now, area.hours) == Open.CLOSING_SOON
            val line = openLine(p, area, now)
            val extra = if ((p.footprintM2 ?: 0) >= 2_000) " · " + stringResource(R.string.shop_find_big) else ""
            if (line != null) Line(line.first + extra, bt(13f, if (soon) 700 else 400, 14.5f, COURIER), if (soon) 0xFFB03A3A else 0xFF5A4A35)
        }
        Line("${p.walkMinutes}′", bt(22f, 700, 24.5f, COURIER, tnum = true), INK_F)
    }
}

/**
 * The drawer front under the cards (12 px from the sides, 170 high, 24 above the bottom): wood with a light top edge and
 * a dark foot, the brass plate (170 × 46) with the paper label ("HOOKS · 5 KINDS"), and the brass pull, 12 apart.
 */
@Composable
private fun Drawer(thing: String, kinds: Int, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().height(170.dp)
            .boardBox(
                r = round(8f),
                shadows = listOf(CssShadow(0f, 3f, 0f, 0f, 0x2EFFFFFF, inset = true), CssShadow(0f, -4f, 0f, 0f, 0x40000000, inset = true), CssShadow(0f, -6f, 14f, 0f, 0x4C000000)),
                grad = listOf(cssLinear(180f, 0f to 0xFF8C6238, 1f to 0xFF6E4A28)),
            ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(170.dp, 46.dp).boardBox(
                r = round(4f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF8C6A25, inset = true), CssShadow(0f, 2f, 3f, 0f, 0x66000000)),
                grad = listOf(cssLinear(180f, 0f to 0xFFE9D08A, 1f to 0xFFB9933F)), clip = false,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(150.dp, 32.dp).background(argb(0xFFFBF6EA)), contentAlignment = Alignment.Center) {
                Text(
                    if (kinds == 1) stringResource(R.string.shop_find_kind, thing.uppercase()) else stringResource(R.string.shop_find_kinds, thing.uppercase(), kinds),
                    Modifier.padding(horizontal = 4.dp),
                    style = bt(15f, 400, 15f, ELITE, spacing = 1.2f, align = TextAlign.Center), color = argb(INK_F), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 15.sp),
                )
            }
        }
        Box(
            Modifier.size(90.dp, 20.dp).boardBox(
                r = round(10f), shadows = listOf(CssShadow(0f, 3f, 4f, 0f, 0x66000000)), grad = listOf(cssLinear(180f, 0f to 0xFFF1D38A, 1f to 0xFF9C7A2E)), clip = false,
            ),
        )
    }
}
