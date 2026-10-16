package app.atlas.ui.shop

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.em
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
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
    // CSS `box-shadow: inset 0 0 0 [w]px`: a rim [w] wide inside the box.
    fun rim(x: Float, y: Float, w: Float, h: Float, c: Color, width: Float) =
        drawRect(c, Offset((x + width / 2) * k, (y + width / 2) * k), Size((w - width) * k, (h - width) * k), style = Stroke(width * k))
    fun framed(x: Float, y: Float, w: Float, h: Float, glass: Brush, rimColour: Color, rimW: Float = 4f) {
        drawRect(glass, Offset(x * k, y * k), Size(w * k, h * k))
        rim(x, y, w, h, rimColour, rimW)
    }
    // A CSS radial gradient (farthest corner, an ellipse) over a box at (x, y).
    fun glow(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float) = translate(x * k, y * k) {
        drawRect(cssRadial(false, cx * k / density, cy * k / density, 0f to 0xFFFFE6B3, 1f to 0xFFE8B066).brush(Size(w * k, h * k), density), Offset.Zero, Size(w * k, h * k))
    }
    r(0f, 0f, 186f, 410f, st.wall)
    // The cornice and its 3 px shadow line under it.
    r(-6f, 18f, 198f, 3f, Color(st.cornice.red * 0.72f, st.cornice.green * 0.74f, st.cornice.blue * 0.72f))
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
    st.signRing?.let { rim(10f, 196f, 166f, 40f, it, 2f) }
    val size = (if (name.length > 12) 17f else 24f) * k / density
    val text = measurer.measure(
        if (st.neon) name.uppercase().take(10) else name,
        TextStyle(
            fontFamily = st.font, fontSize = size.sp, color = st.signText, letterSpacing = if (st.font == SerifFamily) 0.06.em else (if (name.length > 10) 0.3.sp else 1.2.sp),
            fontWeight = if (st.font == SerifFamily || st.neon) FontWeight.Normal else FontWeight.ExtraBold,
            shadow = if (st.neon) androidx.compose.ui.graphics.Shadow(Color(0xFF36E3FF), Offset.Zero, 14f) else null,
        ),
        softWrap = false, maxLines = 1,
    )
    drawText(text, topLeft = Offset((93f * k - text.size.width / 2f).coerceAtLeast(12f * k), 216f * k - text.size.height / 2f))
    // Shop window, with its display, and the door.
    glow(12f, 250f, 108f, 160f, 54f, 64f)
    rim(12f, 250f, 108f, 160f, if (st.dark) Color(0xFF3A3D45) else st.sign, 5f)
    if (st.books) {
        val spines = listOf(0xFFB5532E to 44f, 0xFF1F3A68 to 52f, 0xFF2F5D3A to 40f, 0xFFC98A2B to 30f, 0xFF7A2233 to 48f, 0xFF12A9A6 to 38f)
        var x = 24f
        val widths = listOf(10f, 12f, 9f, 14f, 11f, 10f)
        spines.forEachIndexed { i, (c, h) -> r(x, 394f - h, widths[i], h, Color(c)); x += widths[i] + 3f }
        // Two books face out, turned 8° back and 6° on, as the board stands them.
        rotate(-8f, Offset(52f * k, 299f * k)) { r(41f, 284f, 22f, 30f, Color(0xFFF4EBDD)) }
        rotate(6f, Offset(80f * k, 299f * k)) { r(69f, 284f, 22f, 30f, Color(0xFFD93A5A)) }
    } else if (st.dark) {
        repeat(3) { i -> drawRoundRect(Color(0xFF2A3A55), Offset((26f + i * 28f) * k, 290f * k), Size(20f * k, 38f * k), CornerRadius(4f * k)); drawRoundRect(Color(0xFF5CE1FF), Offset((26f + i * 28f) * k, 290f * k), Size(20f * k, 38f * k), CornerRadius(4f * k), style = Stroke(2f * k)) }
    } else {
        // A plain display: a shelf with a few goods.
        r(22f, 350f, 88f, 4f, Color(0x33000000))
        listOf(0xFFC98A2B, 0xFF7A2233, 0xFF2F5D3A, 0xFF1F3A68).forEachIndexed { i, c -> r(26f + i * 20f, 322f, 14f, 28f, Color(c)) }
    }
    r(128f, 262f, 48f, 148f, if (st.dark) Color(0xFF10131A) else st.sign)
    rim(128f, 262f, 48f, 148f, st.cornice, 3f)
    glow(136f, 272f, 32f, 70f, 16f, 28f)
    // The door's card: 25.8 × 11 white with OPEN in 7 px green (padding 1 3), when it is open.
    r(138f, 292f, 25.8f, 11f, Color.White)
    val tag = measurer.measure(if (open) "OPEN" else "·", TextStyle(fontFamily = SansFamily, fontSize = (7f * k / density).sp, fontWeight = FontWeight.ExtraBold, color = if (open) Color(0xFF1E7A46) else Color(0xFF8A8F9C)))
    drawText(tag, topLeft = Offset(150.9f * k - tag.size.width / 2f, 297.5f * k - tag.size.height / 2f))
    drawCircle(Color(0xFFC9A45C), 3f * k, Offset(166f * k, 357f * k))
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

    // The sky: #CFE0EC at the top to the street's #EDE6DA at 140 px, as the board's background.
    Box(Modifier.fillMaxSize().background(argb(0xFFEDE6DA)).drawBehind { drawRect(Brush.verticalGradient(listOf(Color(0xFFCFE0EC), Color(0xFFEDE6DA)), 0f, 140.dp.toPx()), size = Size(size.width, 140.dp.toPx())) }) {
        if (street == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                if (area.waiting) Line(stringResource(R.string.shop_loading), bt(14f, 400, 18f), 0xFF464C5A)
                else StateMessage("place.shop-front", stringResource(R.string.shop_stroll_none), text = area.error, tint = Color(0xFF464C5A))
            }
        } else {
            val shop = street.shops[index]
            val open = shop.openness(now, area.hours).isOpen
            Box(
                Modifier.fillMaxSize().padding(top = boardTop()).pointerInput(street) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -80f) at = (index + 1).coerceAtMost(street.shops.lastIndex) else if (dx > 80f) at = (index - 1).coerceAtLeast(0) }) { _, d -> dx += d }
                },
            ) {
                // The street, as the board's: the shop at x 74 from y 150, its neighbours abutting it, pavement and road at 560.
                street.shops.getOrNull(index - 1)?.let { Facade(it, it.openness(now, area.hours).isOpen, Modifier.offset((-96).dp, 190.dp).alpha(0.55f), width = 170) }
                street.shops.getOrNull(index + 1)?.let { Facade(it, it.openness(now, area.hours).isOpen, Modifier.offset(260.dp, 176.dp).boardBox(shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x33000000)), clip = false), width = 176) }
                Facade(shop, open, Modifier.offset(74.dp, 150.dp).boardBox(shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x26000000), CssShadow(0f, 20f, 30f, 0f, 0x33000000)), clip = false))
                HangingSign(shop, Modifier.offset(254.dp, 336.dp))
                Box(Modifier.at(0f, 560f, 412f, 44f).boardBox(shadows = listOf(CssShadow(0f, -5f, 0f, 0f, 0xFF8E8A82, inset = true)), grad = listOf(cssLinear(180f, 0f to 0xFFC9C6BF, 1f to 0xFFB5B1A8))))
                Box(Modifier.at(0f, 604f, 412f, 20f).background(argb(0xFF5A5D63)))
                val you = stringResource(R.string.shop_stroll_you)
                Box(
                    Modifier.at(157f, 570f, 20f, 20f).boardBox(0xFF2E5BFF, round(10f), listOf(CssShadow(0f, 0f, 0f, 8f, 0x332E5BFF)), ring(3f, 0xFFFFFFFF), clip = false)
                        .semantics { contentDescription = you },
                )
                Box(
                    Modifier.at(10f, 340f, 44f, 44f).boardBox(0xE6FFFFFF, round(22f), listOf(CssShadow(0f, 4f, 10f, 0f, 0x33000000))).tap(stringResource(R.string.shop_stroll_back)) { at = (index - 1).coerceAtLeast(0) },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.back", 20f, 20f) }
                Box(
                    Modifier.at(358f, 340f, 44f, 44f).boardBox(0xE6FFFFFF, round(22f), listOf(CssShadow(0f, 4f, 10f, 0f, 0x33000000))).tap(stringResource(R.string.shop_stroll_on)) { at = (index + 1).coerceAtMost(street.shops.lastIndex) },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.chevron-right", 20f, 20f) }
                // The top: back, the street sign centred at y 44, and where you are on the street.
                Box(Modifier.at(16f, 42f, 44f, 44f).boardBox(0xD9FFFFFF, round(22f)).tap(stringResource(R.string.shop_back_map)) { nav.back() }, contentAlignment = Alignment.Center) {
                    Sym("ui.back", 20f, 20f)
                }
                val signLabel = stringResource(R.string.shop_stroll_street, street.name, street.direction)
                val signName = (street.name.ifEmpty { stringResource(R.string.shop_stroll_title) }).uppercase()
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 44.dp).widthIn(max = 280.dp).height(40.dp)
                        .boardBox(
                            0xFF0C6B3E, round(6f),
                            listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF0C6B3E, inset = true), CssShadow(0f, 0f, 0f, 3.5f, 0xFFF2F2F2, inset = true), CssShadow(0f, 3f, 6f, 0f, 0x40000000)),
                            clip = false,
                        )
                        .padding(horizontal = 14.dp).semantics { contentDescription = signLabel },
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Line(signName, bt(20f, 800, 25f, OVERPASS), 0xFFFFFFFF, Modifier.weight(1f, fill = false))
                    Line(street.direction + " " + Stroll.arrow(street.direction), bt(13f, 800, 16.5f, OVERPASS), 0xD9FFFFFF)
                }
                Line(
                    stringResource(R.string.shop_stroll_count, index + 1, street.shops.size), bt(13f, 600, 17f, tnum = true), 0xFF464C5A,
                    Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 16.dp),
                )
            }
            ShopCard(shop, area, now, ratings[shop.key], saved.any { l -> l.places.any { it.place.key == shop.key } }, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 24.dp + boardLift()),
                onPeek = { services.remember(shop.place); nav.go(Dest.Place.of(shop.place)) },
                onSave = { services.saved.toggle(shop.place) },
                onPhone = { call -> startSafely(context, call) },
            )
        }
    }
}

/**
 * The card about the shop (388 × 256, 28 px corners, 18 px in): its mark, the name in Instrument Serif, until when, the
 * stars and what it is; what the map knows as chips; Peek inside, Save and Phone along the bottom.
 */
@Composable
private fun ShopCard(
    p: ShopPlace, area: ShopArea, now: java.time.LocalDateTime, rating: app.atlas.providers.Rating?, saved: Boolean, modifier: Modifier,
    onPeek: () -> Unit, onSave: () -> Unit, onPhone: (Intent) -> Unit,
) {
    val phone = (p.tags["phone"] ?: p.tags["contact:phone"])?.let(::dialIntent)
    Column(
        modifier.fillMaxWidth().height(256.dp).boardBox(0xFFFBFAF7, round(28f), listOf(CssShadow(0f, -6f, 24f, 0f, 0x24181C30)), clip = false)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = remember(p.key) { facadeStyle(p) }
            if (p.brand != null) ShopLogo(p.brand, p.name, 52.dp)
            else Box(Modifier.size(52.dp).boardBox(if (style.dark) INK else (style.wall.toArgb().toLong() and 0xFFFFFFFFL), round(14f)), contentAlignment = Alignment.Center) {
                Sym(if (p.tags["shop"] == "books") "place.books" else "place.shop", 26f, 26f, style.signText.toArgb().toLong() and 0xFFFFFFFFL)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Block(p.name, bt(30f, 400, 30f, SERIF), INK, Modifier.semantics { heading() })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val st = bt(13f, 400, 17f, tnum = true)
                    openLine(p, area, now)?.let { (t, c) ->
                        val running = c == Color(0xFF464C5A)
                        Line(t, if (running) bt(13f, 700, 17f, tnum = true) else st, if (running) 0xFF1E7A46 else (c.toArgb().toLong() and 0xFFFFFFFFL))
                    }
                    rating?.stars?.let { Line("★ " + String.format(java.util.Locale.getDefault(), "%.1f", it), st, 0xFF464C5A) }
                    p.kindName(java.util.Locale.getDefault().language)?.takeIf { !it.equals(p.name, ignoreCase = true) }?.let { Line(it, st, 0xFF464C5A, Modifier.weight(1f, fill = false)) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.independent) Row(
                Modifier.height(30.dp).boardBox(0xFFE7F4EC, round(15f)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("fact.independent", 14f, 14f, 0xFF12663A)
                Line(stringResource(R.string.shop_indie), bt(12f, 700, 15.5f), 0xFF12663A)
            }
            if (p.tags["second_hand"] in setOf("yes", "only") || p.tags["shop"] in setOf("second_hand", "charity")) FactDot("fact.second-hand", stringResource(R.string.shop_stroll_secondhand))
            if (p.stepFree) FactDot("fact.wheelchair", stringResource(R.string.shop_stroll_stepfree))
            if (ShopPlace.CARD_KEYS.any { p.yes("payment:$it") }) FactDot("fact.card", stringResource(R.string.shop_stroll_cards))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            val peek = stringResource(R.string.shop_stroll_peek)
            Row(
                Modifier.weight(1f).height(54.dp).boardBox(INK, round(27f)).tap(peek, onPeek),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("ui.camera", 19f, 19f, 0xFFFFFFFF)
                Line(peek, bt(15f, 600, 19.5f), 0xFFFFFFFF)
            }
            Box(
                Modifier.size(54.dp).boardBox(if (saved) 0xFFE4EAFF else 0xFFF1EEE8, round(27f)).tap(stringResource(if (saved) R.string.shop_stroll_saved else R.string.shop_stroll_save), onSave),
                contentAlignment = Alignment.Center,
            ) { Sym("ui.bookmark", 20f, 20f) }
            if (phone != null) Box(
                Modifier.size(54.dp).boardBox(0xFFF1EEE8, round(27f)).tap(stringResource(R.string.shop_stroll_phone)) { onPhone(phone) },
                contentAlignment = Alignment.Center,
            ) { Sym("ui.phone", 19f, 19f) }
        }
    }
}

/** A fact as the board's 30 px disc with its 15 px symbol. */
@Composable
private fun FactDot(symbol: String, label: String) {
    Box(Modifier.size(30.dp).boardBox(0xFFF1EEE8, round(15f)).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Sym(symbol, 15f, 15f) }
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
        Sym(glyph, 24f, 24f, 0xFFF4EBDD, Modifier.offset(x = 22.dp, y = 24.dp))
    }
}
