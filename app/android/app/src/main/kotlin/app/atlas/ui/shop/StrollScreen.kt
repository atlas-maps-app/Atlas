package app.atlas.ui.shop

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere

/** How a shopfront is dressed: its wall, cornice, sign, and what the window shows. */
internal class FacadeStyle(
    val wall: Color, val cornice: Color, val frame: Color, val sign: Color, val signText: Color, val signRing: Color?,
    val font: FontFamily, val neon: Boolean, val awning: Boolean, val books: Boolean, val dark: Boolean,
)

private val Walls = listOf(0xFFB5532E, 0xFF1F3A68, 0xFF8C6A3E, 0xFF5E7F6A, 0xFF9A4A62, 0xFF47697F, 0xFFA8793A)

/** The look of the shopfront of [p]: by what it sells (books green with a gold serif sign, tech dark with neon, vintage with an awning). */
internal fun facadeStyle(p: ShopPlace): FacadeStyle {
    val family = Family.of(p.tags)
    return when {
        family == Family.BOOKS || p.tags["shop"] == "books" -> FacadeStyle(Color(0xFF2F5D3A), Color(0xFF24472C), Color(0xFFEDE6DA), Color(0xFF16301D), Color(0xFFF1D38A), Color(0xFFC9A45C), SerifFamily, false, false, true, false)
        family == Family.TECH -> FacadeStyle(Color(0xFF1B1D22), Color(0xFF2A2D34), Color(0xFF3A3D45), Color(0xFF1B1D22), Color(0xFFD6FBFF), null, ShopType.neon, true, false, false, true)
        family == Family.VINTAGE -> FacadeStyle(Color(0xFFD9A43A), Color(0xFFB98A2A), Color(0xFFF4EBDD), Color(0xFF1A1A1A), Color(0xFFF4E1A8), null, ShopType.overpass, false, true, false, false)
        family == Family.FASHION -> FacadeStyle(Color(0xFF7A2233), Color(0xFF5A1826), Color(0xFFEDE6DA), Color(0xFF3A0F18), Color(0xFFF1D38A), Color(0xFFC9A45C), SerifFamily, false, false, false, false)
        family == Family.FLOWERS -> FacadeStyle(Color(0xFF5E7F6A), Color(0xFF47634F), Color(0xFFEDE6DA), Color(0xFF2B3D31), Color(0xFFF8E6EC), Color(0xFFD08AA0), SerifFamily, false, true, false, false)
        p.tags["shop"] in setOf("bakery", "pastry") -> FacadeStyle(Color(0xFFC79A5B), Color(0xFFA9793E), Color(0xFFFBF3E0), Color(0xFF4A2F18), Color(0xFFFBE9C0), null, SerifFamily, false, true, false, false)
        else -> {
            val wall = Color(Walls[Math.floorMod(p.key.hashCode(), Walls.size)])
            FacadeStyle(wall, wall.copy(alpha = 1f).let { Color(it.red * 0.75f, it.green * 0.75f, it.blue * 0.75f) }, Color(0xFFEDE6DA), Color(0xFF1A1A1A), Color(0xFFF4E1A8), null, ShopType.overpass, false, false, false, false)
        }
    }
}

/** A shopfront drawn as the Stroll board draws its houses, 186 × 410 px: cornice, windows, sign band, shop window and door. */
@Composable
internal fun Facade(shop: ShopPlace, open: Boolean, modifier: Modifier = Modifier, width: Int = 186) {
    val style = remember(shop.key) { facadeStyle(shop) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    Canvas(modifier.size(width.dp, (width * 410 / 186f).dp)) { drawFacade(shop.name, style, open, measurer, size.width / 186f) }
}

private fun DrawScope.drawFacade(name: String, st: FacadeStyle, open: Boolean, measurer: TextMeasurer, k: Float) {
    fun r(x: Float, y: Float, w: Float, h: Float, c: Color) = drawRect(c, Offset(x * k, y * k), Size(w * k, h * k))
    fun framed(x: Float, y: Float, w: Float, h: Float, glass: Brush, rim: Color, rimW: Float = 4f) {
        drawRect(glass, Offset(x * k, y * k), Size(w * k, h * k))
        drawRect(rim, Offset(x * k, y * k), Size(w * k, h * k), style = Stroke(rimW * 2 * k))
    }
    r(0f, 0f, 186f, 410f, st.wall)
    r(-6f, 0f, 198f, 18f, st.cornice)
    val sky = Brush.verticalGradient(listOf(Color(0xFFA9C6D8), Color(0xFF7FA3BD)))
    val warm = Brush.verticalGradient(listOf(Color(0xFFFFE3A8), Color(0xFFF2B45E)))
    framed(20f, 42f, 56f, 80f, sky, st.frame); r(46f, 42f, 4f, 80f, st.frame)
    framed(110f, 42f, 56f, 80f, if (open) warm else sky, st.frame); r(136f, 42f, 4f, 80f, st.frame)
    framed(20f, 146f, 56f, 34f, androidx.compose.ui.graphics.SolidColor(Color(0xFF7FA3BD)), st.frame)
    framed(110f, 146f, 56f, 34f, androidx.compose.ui.graphics.SolidColor(Color(0xFF7FA3BD)), st.frame)
    if (st.awning) {
        var x = 0f
        var on = true
        while (x < 186f) { r(x, 220f, 18f, 26f, if (on) Color(0xFFD9A43A) else Color(0xFFFBF3E0)); x += 18f; on = !on }
    }
    // The sign band, with the shop's name.
    r(10f, 196f, 166f, 40f, st.sign)
    st.signRing?.let { drawRect(it, Offset(10f * k, 196f * k), Size(166f * k, 40f * k), style = Stroke(2f * k)) }
    val size = (if (name.length > 12) 17f else 24f) * k / density
    val text = measurer.measure(
        if (st.neon) name.uppercase().take(10) else name,
        TextStyle(
            fontFamily = st.font, fontSize = size.sp, color = st.signText, letterSpacing = if (st.font == SerifFamily) 0.6.sp else (if (name.length > 10) 0.3.sp else 1.2.sp),
            fontWeight = if (st.font == SerifFamily || st.neon) FontWeight.Normal else FontWeight.ExtraBold,
            shadow = if (st.neon) androidx.compose.ui.graphics.Shadow(Color(0xFF36E3FF), Offset.Zero, 14f) else null,
        ),
        softWrap = false, maxLines = 1,
    )
    drawText(text, topLeft = Offset((93f * k - text.size.width / 2f).coerceAtLeast(12f * k), 216f * k - text.size.height / 2f))
    // Shop window, with its display, and the door.
    drawRect(Brush.radialGradient(listOf(Color(0xFFFFE6B3), Color(0xFFE8B066)), Offset(66f * k, 310f * k), 110f * k), Offset(12f * k, 250f * k), Size(108f * k, 160f * k))
    drawRect(if (st.dark) Color(0xFF3A3D45) else st.sign, Offset(12f * k, 250f * k), Size(108f * k, 160f * k), style = Stroke(10f * k))
    if (st.books) {
        val spines = listOf(0xFFB5532E to 44f, 0xFF1F3A68 to 52f, 0xFF2F5D3A to 40f, 0xFFC98A2B to 30f, 0xFF7A2233 to 48f, 0xFF12A9A6 to 38f)
        var x = 24f
        val widths = listOf(10f, 12f, 9f, 14f, 11f, 10f)
        spines.forEachIndexed { i, (c, h) -> r(x, 394f - h, widths[i], h, Color(c)); x += widths[i] + 3f }
        r(32f, 284f, 22f, 30f, Color(0xFFF4EBDD)); r(60f, 284f, 22f, 30f, Color(0xFFD93A5A))
    } else if (st.dark) {
        repeat(3) { i -> drawRoundRect(Color(0xFF2A3A55), Offset((26f + i * 28f) * k, 290f * k), Size(20f * k, 38f * k), CornerRadius(4f * k)); drawRoundRect(Color(0xFF5CE1FF), Offset((26f + i * 28f) * k, 290f * k), Size(20f * k, 38f * k), CornerRadius(4f * k), style = Stroke(2f * k)) }
    } else {
        // A plain display: a shelf with a few goods.
        r(22f, 350f, 88f, 4f, Color(0x33000000))
        listOf(0xFFC98A2B, 0xFF7A2233, 0xFF2F5D3A, 0xFF1F3A68).forEachIndexed { i, c -> r(26f + i * 20f, 322f, 14f, 28f, Color(c)) }
    }
    r(128f, 262f, 48f, 148f, if (st.dark) Color(0xFF10131A) else st.sign)
    drawRect(st.cornice, Offset(128f * k, 262f * k), Size(48f * k, 148f * k), style = Stroke(6f * k))
    drawRect(Brush.radialGradient(listOf(Color(0xFFFFE6B3), Color(0xFFE8B066)), Offset(152f * k, 300f * k), 60f * k), Offset(136f * k, 272f * k), Size(32f * k, 70f * k))
    r(138f, 292f, 28f, 14f, Color.White)
    val tag = measurer.measure(if (open) "OPEN" else "·", TextStyle(fontSize = (7f * k / density).sp, fontWeight = FontWeight.ExtraBold, color = if (open) Color(0xFF1E7A46) else Color(0xFF8A8F9C)))
    drawText(tag, topLeft = Offset(152f * k - tag.size.width / 2f, 299f * k - tag.size.height / 2f))
    drawCircle(Color(0xFFC9A45C), 3f * k, Offset(170f * k, 365f * k))
}

/**
 * Stroll, as on the Stroll board: the shopfronts along one street, in the order you pass them, as drawn houses (the
 * board's own: cornice, windows, sign band, window and door, dressed by what the shop sells) with the street sign on top
 * and "3 / 8" at the corner. Swipe or tap the arrows to walk on or back; the card below says who is there, until when,
 * the stars if anyone reviewed it, and what the map knows (indie, second-hand, step-free, cards), with Peek inside (the
 * place's page), Save and Phone.
 */
@Composable
fun StrollScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val context = LocalContext.current
    val area by produceState(ShopArea.empty(here, 800.0), here) { repo.area("stroll", app.atlas.places.TagFilter.parse("shop"), here, 800.0).collect { value = it } }
    val street = remember(area, here) { Stroll.street(area, here) }
    val ratings by produceState(emptyMap<String, app.atlas.providers.Rating>(), area.places.size, area.origin) { value = repo.ratings(area) }
    var at by rememberSaveable { mutableIntStateOf(-1) }
    val index = if (street == null) 0 else (if (at < 0) street.start else at).coerceIn(0, street.shops.lastIndex)
    val saved by services.saved.lists.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFFCFE0EC), 0.16f to Color(0xFFEDE6DA), 1f to Color(0xFFEDE6DA)))) {
        if (street == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                if (area.waiting) Text(stringResource(R.string.shop_loading), style = TextStyle(fontFamily = SansFamily, fontSize = 14.sp), color = Color(0xFF464C5A))
                else StateMessage("place.shop-front", stringResource(R.string.shop_stroll_none), text = area.error, tint = Color(0xFF464C5A))
            }
        } else {
            val shop = street.shops[index]
            val open = shop.openness(now, area.hours).isOpen
            Box(
                Modifier.fillMaxSize().pointerInput(street) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -80f) at = (index + 1).coerceAtMost(street.shops.lastIndex) else if (dx > 80f) at = (index - 1).coerceAtLeast(0) }) { _, d -> dx += d }
                },
            ) {
                // The street: neighbours abut the shop in the middle; pavement, kerb and road at its foot.
                Box(Modifier.align(Alignment.TopCenter).padding(top = 150.dp)) {
                    Box(Modifier.width(186.dp)) {
                        street.shops.getOrNull(index - 1)?.let { Facade(it, it.openness(now, area.hours).isOpen, Modifier.offset(x = (-170).dp, y = 26.dp).alpha(0.85f), width = 170) }
                        street.shops.getOrNull(index + 1)?.let { Facade(it, it.openness(now, area.hours).isOpen, Modifier.offset(x = 186.dp, y = 26.dp), width = 176) }
                        Facade(shop, open, Modifier.cssShadow(RoundedCornerShape(0.dp), y = 20.dp, blur = 30.dp, colour = Color(0x33000000)))
                        HangingSign(shop, Modifier.offset(x = 180.dp, y = 36.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 560.dp).height(44.dp).background(Brush.verticalGradient(listOf(Color(0xFFC9C6BF), Color(0xFFB5B1A8)))))
                Box(Modifier.fillMaxWidth().padding(top = 604.dp).height(20.dp).background(Color(0xFF5A5D63)))
                Box(Modifier.align(Alignment.TopCenter).offset(x = (-10).dp, y = 570.dp).size(20.dp).background(Color(0x332E5BFF), CircleShape).padding(0.dp)) {
                    Box(Modifier.align(Alignment.Center).size(20.dp).background(Color.White, CircleShape).padding(3.dp).background(Color(0xFF2E5BFF), CircleShape))
                }
            }
            // The street sign, and where you are on the street.
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundSymbolButton("ui.back", stringResource(R.string.shop_back_map), { nav.back() }, size = 44.dp, fill = Color.White.copy(alpha = 0.85f), iconSize = 20.dp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val signLabel = stringResource(R.string.shop_stroll_street, street.name, street.direction)
                    val signName = (street.name.ifEmpty { stringResource(R.string.shop_stroll_title) }).uppercase()
                    Row(
                        Modifier.height(40.dp).cssShadow(RoundedCornerShape(6.dp), y = 3.dp, blur = 6.dp, colour = Color(0x40000000))
                            .background(Color(0xFF0C6B3E), RoundedCornerShape(6.dp)).padding(2.dp).border(1.5.dp, Color(0xFFF2F2F2), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp)
                            .semantics { contentDescription = signLabel },
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(signName, style = TextStyle(fontFamily = ShopType.overpass, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp), color = Color.White, maxLines = 1)
                        Text(street.direction + " " + Stroll.arrow(street.direction), style = TextStyle(fontFamily = ShopType.overpass, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp), color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                    }
                }
                Text(stringResource(R.string.shop_stroll_count, index + 1, street.shops.size), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Color(0xFF464C5A))
            }
            RoundSymbolButton("ui.back", stringResource(R.string.shop_stroll_back), { at = (index - 1).coerceAtLeast(0) }, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 340.dp), size = 44.dp, fill = Color.White.copy(alpha = 0.9f), iconSize = 20.dp, weight = 1.1f)
            RoundSymbolButton("ui.chevron-right", stringResource(R.string.shop_stroll_on), { at = (index + 1).coerceAtMost(street.shops.lastIndex) }, Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 340.dp), size = 44.dp, fill = Color.White.copy(alpha = 0.9f), iconSize = 20.dp, weight = 1.1f)
            ShopCard(shop, area, now, ratings[shop.key], saved.any { l -> l.places.any { it.place.key == shop.key } }, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
                onPeek = { services.remember(shop.place); nav.go(Dest.Place.of(shop.place)) },
                onSave = { services.saved.toggle(shop.place) },
                onPhone = { call -> startSafely(context, call) },
            )
        }
    }
}

@Composable
private fun ShopCard(
    p: ShopPlace, area: ShopArea, now: java.time.LocalDateTime, rating: app.atlas.providers.Rating?, saved: Boolean, modifier: Modifier,
    onPeek: () -> Unit, onSave: () -> Unit, onPhone: (Intent) -> Unit,
) {
    val family = Family.of(p.tags)
    val art = family?.let { FamilyArts[it] }
    val phone = (p.tags["phone"] ?: p.tags["contact:phone"])?.let(::dialIntent)
    Column(
        modifier.fillMaxWidth().height(256.dp).cssShadow(RoundedCornerShape(28.dp), y = (-6).dp, blur = 24.dp, colour = Color(0x24181C30))
            .background(Color(0xFFFBFAF7), RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = remember(p.key) { facadeStyle(p) }
            Box(Modifier.size(52.dp).background(if (style.dark) Color(0xFF14161B) else style.wall, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                if (p.brand != null) ShopLogo(p.brand, p.name, 52.dp)
                else Symbol(art?.symbol?.let { "place.shop" } ?: "place.shop", tint = style.signText, size = 26.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = TextStyle(fontFamily = SerifFamily, fontSize = 30.sp, lineHeight = 30.sp), color = Color(0xFF14161B), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val st = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, fontFeatureSettings = "tnum")
                    openLine(p, area, now)?.let { (t, c) -> Text(t, style = st.copy(fontWeight = if (c == Color(0xFF464C5A)) FontWeight.Bold else FontWeight.Normal), color = if (c == Color(0xFF464C5A)) Color(0xFF1E7A46) else c, maxLines = 1) }
                    rating?.stars?.let { Text("★ " + String.format(java.util.Locale.getDefault(), "%.1f", it), style = st, color = Color(0xFF464C5A)) }
                    p.kindName(java.util.Locale.getDefault().language)?.takeIf { !it.equals(p.name, ignoreCase = true) }?.let { Text(it, Modifier.weight(1f, fill = false), style = st, color = Color(0xFF464C5A), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.independent) Row(
                Modifier.height(30.dp).background(Color(0xFFE7F4EC), RoundedCornerShape(15.dp)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("fact.independent", tint = Color(0xFF12663A), size = 14.dp)
                Text(stringResource(R.string.shop_indie), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp), color = Color(0xFF12663A))
            }
            if (p.tags["second_hand"] in setOf("yes", "only") || p.tags["shop"] in setOf("second_hand", "charity")) FactDot("fact.second-hand", stringResource(R.string.shop_stroll_secondhand))
            if (p.stepFree) FactDot("fact.wheelchair", stringResource(R.string.shop_stroll_stepfree))
            if (ShopPlace.CARD_KEYS.any { p.yes("payment:$it") }) FactDot("fact.card", stringResource(R.string.shop_stroll_cards))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            val peek = stringResource(R.string.shop_stroll_peek)
            Row(
                Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(27.dp)).background(Color(0xFF14161B)).tap(peek, onPeek),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ui.camera", tint = Color.White, size = 19.dp)
                Text(peek, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = Color.White)
            }
            RoundSymbolButton(if (saved) "ui.bookmark" else "ui.bookmark", stringResource(if (saved) R.string.shop_stroll_saved else R.string.shop_stroll_save), onSave, size = 54.dp, fill = if (saved) Color(0xFFE4EAFF) else Color(0xFFF1EEE8), iconSize = 20.dp)
            if (phone != null) RoundSymbolButton("ui.phone", stringResource(R.string.shop_stroll_phone), { onPhone(phone) }, size = 54.dp, fill = Color(0xFFF1EEE8), iconSize = 19.dp)
        }
    }
}

@Composable
private fun FactDot(symbol: String, label: String) {
    Box(Modifier.size(30.dp).background(Color(0xFFF1EEE8), CircleShape).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Symbol(symbol, tint = Color(0xFF14161B), size = 15.dp) }
}

/** The sign that hangs over the pavement from a bracket: the board's own (iron, chain, rounded plate) with the kind's glyph on the plate. */
@Composable
private fun HangingSign(p: ShopPlace, modifier: Modifier) {
    val style = remember(p.key) { facadeStyle(p) }
    val frame = remember(style.wall) {
        val svg = ShopBoards.SIGN
        svg.substringBefore("<path d=\"M34 30").plus("</svg>").replace("#2F5D3A", "#%06X".format(style.wall.toArgb() and 0xFFFFFF))
    }
    val glyph = when {
        p.tags["shop"] == "books" -> "place.books"
        p.tags["shop"] in setOf("bakery", "pastry") -> "food.bread"
        p.tags["shop"] in setOf("supermarket", "convenience", "greengrocer") -> "grocery.supermarket"
        else -> "place.shop"
    }
    Box(modifier.size(70.dp, 80.dp)) {
        BoardArt(frame, tint = Color(0xFF1A1A1A))
        Box(Modifier.offset(x = 22.dp, y = 24.dp).size(24.dp)) { Symbol(glyph, tint = Color(0xFFF4EBDD), size = 24.dp) }
    }
}
