package app.atlas.ui.shop

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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.util.Locale

private val Wood = Color(0xFF5A3B22)
private val Ink = Color(0xFF2A1D14)
private val Tan = Color(0xFFC9B089)
private val Rust = Color(0xFFB8461A)

/** The dots of a likelihood: `●●●○○`, the filled ones in rust (tan when it is only a maybe). */
private fun dots(n: Int, tab: Boolean = false): AnnotatedString = buildAnnotatedString {
    val on = if (n <= 1 && tab) Tan else Rust
    withStyle(SpanStyle(color = on, letterSpacing = 1.sp)) { append("●".repeat(n.coerceIn(0, 5))) }
    withStyle(SpanStyle(color = Tan, letterSpacing = 1.sp)) { append("○".repeat(5 - n.coerceIn(0, 5))) }
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

    Box(
        Modifier.fillMaxSize().background(Wood).drawBehind {
            // Vertical grain: 3 px dark, 4 px light, 8 px mid, repeating every 15 px.
            var x = 0f
            val u = 1.dp.toPx()
            while (x < size.width) {
                drawRect(Color.Black.copy(alpha = 0.12f), Offset(x, 0f), Size(3 * u, size.height))
                drawRect(Color.White.copy(alpha = 0.03f), Offset(x + 3 * u, 0f), Size(4 * u, size.height))
                drawRect(Color.Black.copy(alpha = 0.05f), Offset(x + 7 * u, 0f), Size(8 * u, size.height))
                x += 15 * u
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 2.dp, bottom = 24.dp)) {
            TopBar(text, { text = it }, openNow, { openNowChoice = !openNow }, nav) { submitted = text.trim(); picked = 0; openNowChoice = null }
            if (things.size > 1) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    things.forEachIndexed { i, t ->
                        Text(
                            t,
                            Modifier.clip(CircleShape).background(if (i == picked) Color(0xFFFBF6EA) else Color.White.copy(alpha = 0.14f)).tap(t) { picked = i }.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = TextStyle(fontFamily = ShopType.typewriter, fontSize = 14.sp), color = if (i == picked) Ink else Color(0xFFF4EBDD), maxLines = 1,
                        )
                    }
                }
            } else Spacer(Modifier.height(28.dp))
            when {
                thing.isEmpty() -> EmptyDrawer { q -> text = q; submitted = q; picked = 0 }
                answer == null -> Column(Modifier.padding(horizontal = 16.dp).background(Color(0xFFFBF6EA), RoundedCornerShape(16.dp))) {
                    StateMessage(
                        "ui.search", stringResource(R.string.shop_find_unknown, thing), text = stringResource(R.string.shop_find_unknown_text), tint = Ink,
                        action = { PillButton(onClick = { nav.go(Dest.Search(thing)) }) { Text(stringResource(R.string.shop_find_search_map), style = app.atlas.ui.kit.Atlas.type.label) } },
                    )
                }
                else -> Catalogue(answer, front!!, shops, area, now, nav) { frontId = it.id }
            }
            Spacer(Modifier.height(16.dp))
            if (answer != null) Drawer(thing, answer.sellers.size)
        }
    }
}

private const val FIND_RADIUS_M = 1_500.0

@Composable
private fun TopBar(text: String, onText: (String) -> Unit, openNow: Boolean, onOpen: () -> Unit, nav: Navigator, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundSymbolButton("ui.back", stringResource(R.string.shop_find_back), { nav.back() }, size = 48.dp, fill = Color.White.copy(alpha = 0.14f), tint = Color(0xFFF4EBDD), iconSize = 20.dp)
        val label = stringResource(R.string.shop_find_hint)
        Row(
            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFFFBF6EA)).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardArt(ShopBoards.SPARKLE, tint = Color(0xFF7B4DFF))
            val style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ink)
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    text, onText, singleLine = true, textStyle = style,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
                if (text.isEmpty()) Text(label, style = style, color = Color(0xFF9A8B76), maxLines = 1)
            }
            Box(
                Modifier.size(44.dp).offset(x = 10.dp).tap(stringResource(R.string.shop_find_open_now)) { onOpen() }.semantics { selected = openNow },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(16.dp).then(if (openNow) Modifier.background(Color(0xFF1F9D55), CircleShape) else Modifier.background(Color(0xFF9A8B76), CircleShape)))
            }
        }
    }
}

@Composable
private fun EmptyDrawer(onTry: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp).fillMaxWidth().background(Color(0xFFFFFCF4), RoundedCornerShape(8.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shop_find_empty), style = TextStyle(fontFamily = ShopType.receipt, fontSize = 15.sp), color = Ink)
        for (e in FindModel.examples) {
            Text("→ $e", Modifier.tap(e) { onTry(e) }.padding(vertical = 4.dp), style = TextStyle(fontFamily = ShopType.typewriter, fontSize = 16.sp), color = Rust)
        }
    }
}

private val BackCards = listOf(Color(0xFFEFE5D0), Color(0xFFF2E9D6), Color(0xFFF6EEDD), Color(0xFFF9F3E6))
private val BackLeft = listOf(36, 24, 12, 0)
private val TabLeft = listOf(190, 150, 96, 40)

@Composable
private fun Catalogue(answer: FindAnswer, front: FindSeller, shops: List<ShopPlace>, area: ShopArea, now: java.time.LocalDateTime, nav: Navigator, onPick: (FindSeller) -> Unit) {
    // The other drawers' cards behind, the least likely at the back; the picked one in front.
    val backs = answer.sellers.filter { it.id != front.id }.sortedBy { it.likelihood }.takeLast(4)
    val m = backs.size
    val rows = shops.take(3)
    val frontTop = if (m > 0) 42 * m + 2 else 0
    val frontHeight = maxOf(440, 44 + 32 + rows.size.coerceAtLeast(1) * 64 + 64 + 16)
    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height((frontTop + frontHeight + 26).dp)) {
        backs.forEachIndexed { k, s ->
            val slot = 4 - m + k
            val colour = BackCards[slot]
            val top = 26 + 42 * k
            Box(
                Modifier.padding(start = BackLeft[slot].dp).fillMaxWidth().offset(y = top.dp).height(90.dp)
                    .cssShadow(RoundedCornerShape(8.dp, 8.dp, 0.dp, 0.dp), y = (-2).dp, blur = 3.dp, colour = Color(0x1F000000))
                    .background(colour, RoundedCornerShape(8.dp, 8.dp, 0.dp, 0.dp)),
            )
            Tab(s, colour, Modifier.offset(x = TabLeft[slot].dp, y = (top - 24).dp), small = true) { onPick(s) }
        }
        Column(
            Modifier.offset(y = (26 + frontTop).dp).fillMaxWidth().height(frontHeight.dp)
                .cssShadow(RoundedCornerShape(8.dp), y = 10.dp, blur = 20.dp, colour = Color(0x4D000000))
                .background(Color(0xFFFFFCF4), RoundedCornerShape(8.dp))
                .drawBehind {
                    // Ruled lines every 32 px, and the red margin line near the top, as the board's card.
                    var y = 31.dp.toPx()
                    while (y < size.height) { drawRect(Color(0xFFE7DCC8), Offset(0f, y), Size(size.width, 1.dp.toPx())); y += 32.dp.toPx() }
                    drawRect(Color(0xFFD9534F).copy(alpha = 0.6f), Offset(0f, 34.dp.toPx()), Size(size.width, 1.5.dp.toPx()))
                }
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 16.dp),
        ) {
            Text(front.tagLine, Modifier.height(32.dp), style = TextStyle(fontFamily = ShopType.receipt, fontSize = 13.sp), color = Color(0xFF7A6A55), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rows.isEmpty()) {
                Text(
                    stringResource(if (area.waiting) R.string.shop_loading else R.string.shop_find_none_near),
                    Modifier.height(64.dp), style = TextStyle(fontFamily = ShopType.receipt, fontSize = 14.sp), color = Color(0xFF5A4A35),
                )
            }
            rows.forEach { p -> ShopRow(p, front, area, now) { nav.go(Dest.Place.of(p.place)) } }
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val first = rows.firstOrNull()
                val phone = first?.let { it.tags["phone"] ?: it.tags["contact:phone"] }?.let(::dialIntent)
                val context = LocalContext.current
                val ask = stringResource(R.string.shop_find_ask_d)
                if (phone != null) {
                    Row(
                        Modifier.height(44.dp).border(1.5.dp, Ink, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                            .tap(ask) { startSafely(context, phone) }.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("ui.phone", tint = Ink, size = 16.dp)
                        Text(stringResource(R.string.shop_find_ask), style = TextStyle(fontFamily = ShopType.receipt, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = Ink)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (first != null) {
                    BigPillSmall("${first.walkMinutes}′") { nav.go(Dest.Route(first.location.lat, first.location.lon, first.name, mode = "WALK")) }
                }
            }
        }
        Tab(front, Color(0xFFFFFCF4), Modifier.offset(x = 0.dp, y = (26 + frontTop - 24).dp), small = false) {}
    }
}

@Composable
private fun BigPillSmall(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2E5BFF)).tap(text, onClick).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("mode.walk", tint = Color.White, size = 18.dp)
        Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, fontFeatureSettings = "tnum"), color = Color.White)
    }
}

/** An index tab: the kind in typewriter lettering and its five dots, in the card's own paper. */
@Composable
private fun Tab(s: FindSeller, paper: Color, modifier: Modifier, small: Boolean, onClick: () -> Unit) {
    val desc = stringResource(R.string.shop_find_likely, s.label, s.likelihood)
    Row(
        modifier.height(26.dp).clip(RoundedCornerShape(6.dp, 6.dp, 0.dp, 0.dp)).background(paper).then(if (small) Modifier.tap(desc, onClick) else Modifier.semantics { contentDescription = desc }).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.label, style = TextStyle(fontFamily = ShopType.typewriter, fontSize = if (small) 13.sp else 14.sp), color = if (small) Color(0xFF5A4A35) else Ink, maxLines = 1)
        Text(dots(s.likelihood, tab = small), style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp))
    }
}

@Composable
private fun ShopRow(p: ShopPlace, seller: FindSeller, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val closed = p.openness(now, area.hours) == Open.CLOSED
    Row(Modifier.fillMaxWidth().height(64.dp).alpha(if (closed) 0.6f else 1f).tap(p.name, onClick), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p.brand != null || p.tags["brand"] != null) {
            ShopLogo(p.brand, p.tags["brand"] ?: p.name, 44.dp, key = p.tags["brand"])
        } else {
            Box(Modifier.size(44.dp).background(Color(seller.colour), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Symbol(seller.symbol, tint = Color(0xFF1A1A1A), size = 22.dp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = TextStyle(fontFamily = ShopType.receipt, fontWeight = FontWeight.Bold, fontSize = 17.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val soon = p.openness(now, area.hours) == Open.CLOSING_SOON
            val line = openLine(p, area, now)
            val extra = if ((p.footprintM2 ?: 0) >= 2_000) " · " + stringResource(R.string.shop_find_big) else ""
            if (line != null) {
                Text(
                    line.first + extra,
                    style = TextStyle(fontFamily = ShopType.receipt, fontSize = 13.sp, fontWeight = if (soon) FontWeight.Bold else FontWeight.Normal),
                    color = if (soon) Color(0xFFB03A3A) else Color(0xFF5A4A35), maxLines = 1,
                )
            }
        }
        Text("${p.walkMinutes}′", style = TextStyle(fontFamily = ShopType.receipt, fontWeight = FontWeight.Bold, fontSize = 22.sp, fontFeatureSettings = "tnum"), color = Ink)
    }
}

/** The drawer front under the cards: wood, a brass plate with the paper label ("HOOKS · 5 KINDS") and a brass pull. */
@Composable
private fun Drawer(thing: String, kinds: Int) {
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(170.dp)
            .cssShadow(RoundedCornerShape(8.dp), y = (-6).dp, blur = 14.dp, colour = Color(0x4D000000))
            .clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(Color(0xFF8C6238), Color(0xFF6E4A28))))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.18f), Offset(0f, 0f), Size(size.width, 3.dp.toPx()))
                drawRect(Color.Black.copy(alpha = 0.25f), Offset(0f, size.height - 4.dp.toPx()), Size(size.width, 4.dp.toPx()))
            },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(170.dp, 46.dp).cssShadow(RoundedCornerShape(4.dp), y = 2.dp, blur = 3.dp, colour = Color(0x66000000))
                .background(Brush.verticalGradient(listOf(Color(0xFFE9D08A), Color(0xFFB9933F))), RoundedCornerShape(4.dp))
                .border(2.dp, Color(0xFF8C6A25), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(150.dp, 32.dp).background(Color(0xFFFBF6EA)), contentAlignment = Alignment.Center) {
                Text(
                    if (kinds == 1) stringResource(R.string.shop_find_kind, thing.uppercase()) else stringResource(R.string.shop_find_kinds, thing.uppercase(), kinds),
                    Modifier.padding(horizontal = 4.dp),
                    style = TextStyle(fontFamily = ShopType.typewriter, fontSize = 13.sp, letterSpacing = 1.sp), color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(90.dp, 20.dp).cssShadow(RoundedCornerShape(10.dp), y = 3.dp, blur = 4.dp, colour = Color(0x66000000)).background(Brush.verticalGradient(listOf(Color(0xFFF1D38A), Color(0xFF9C7A2E))), RoundedCornerShape(10.dp)))
    }
}
