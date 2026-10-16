package app.atlas.ui.shop

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import app.atlas.ui.kit.argb
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import java.util.Locale

/** The streams' bins as the boards draw them: symbol, size on the board, the words and the colour. */
internal class StreamArt(val stream: Stream, val symbol: String, val w: Int, val h: Int, val label: Int)

internal val StreamArts: Map<Stream, StreamArt> = listOf(
    StreamArt(Stream.RUBBISH, "bin.trash", 40, 44, R.string.shop_stream_rubbish),
    StreamArt(Stream.MIXED, "bin.plastic.2", 52, 74, R.string.shop_stream_mixed),
    StreamArt(Stream.PAPER, "bin.paper", 52, 74, R.string.shop_stream_paper),
    StreamArt(Stream.ORGANIC, "bin.organic", 52, 74, R.string.shop_stream_organic),
    StreamArt(Stream.BATTERIES, "bin.battery", 44, 60, R.string.shop_stream_batteries),
    StreamArt(Stream.CLOTHES, "bin.textile", 52, 74, R.string.shop_stream_clothes),
    StreamArt(Stream.ELECTRONICS, "bin.electronics", 46, 62, R.string.shop_stream_electronics),
    StreamArt(Stream.OIL, "bin.deposit", 22, 22, R.string.shop_stream_oil),
    StreamArt(Stream.HAZARDOUS, "bin.hazardous", 22, 22, R.string.shop_stream_hazardous),
).associateBy { it.stream }


/**
 * Bins, as on the Bins board: the nearest trash can and the nearest recycling point as two big cards with the arrow to
 * each, the nearest drop-off for every waste stream as a little bin in its colour, "What goes where?" (type a thing and
 * it says the stream it usually goes to, and where the nearest drop-off is), the deposit and reuse shortcuts, and the
 * things that are easy to get wrong. Places and the streams they take come from OpenStreetMap's `recycling:*` tags.
 * The advice is the usual rule; the city's own rules win, and the screen says so.
 */
@Composable
fun BinsScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberLiveHere()
    val heading by rememberHeading()
    val lang = remember { Locale.getDefault().language }
    val area by produceState(ShopArea.empty(here, BIN_RADIUS_M), here) { repo.area("bins", Stream.all, here, BIN_RADIUS_M, nameless = true).collect { value = it } }
    val view = remember(area, here) { BinsFinder.view(area, here) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var question by rememberSaveable { mutableStateOf("") }
    val answer = remember(question) { WasteGuide.lookup(question, lang) }

    Box(Modifier.fillMaxSize().background(argb(0xFFEFF1EF))) {
        // The board's page: a column 16 px from the sides from y 44, 12 apart.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop()).padding(start = 16.dp, end = 16.dp, top = 44.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).boardBox(0xFFFFFFFF, round(22f)).tap(stringResource(R.string.shop_bins_back)) { nav.back() }, contentAlignment = Alignment.Center) {
                    Sym("ui.back", 20f, 20f, INK_B)
                }
                Block(stringResource(R.string.shop_bins), bt(34f, 400, 34f, SERIF), INK_B, Modifier.weight(1f).semantics { heading() })
                val town = area.places.mapNotNull { it.tags["addr:city"] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                Row(
                    Modifier.height(32.dp).boardBox(0xFFFFFFFF, round(16f)).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym("ui.pin", 14f, 14f, SOFT)
                    Line(town ?: stringResource(R.string.shop_bins_as_mapped), bt(12f, 700, 15.5f), SOFT)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigBin(view.trash, 0xFF2B2F36, "bin.trash", 40f, 44f, heading, Modifier.weight(1f), R.string.shop_bin_trash_d) { nav.go(Dest.Groceries("drop:rubbish")) }
                BigBin(view.recycling, 0xFF1B5FA8, "bin.recycling", 40f, 40f, heading, Modifier.weight(1f), R.string.shop_bin_recycling_d) { nav.go(Dest.Groceries("drop:mixed")) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                for (s in listOf(Stream.MIXED, Stream.PAPER, Stream.ORGANIC, Stream.BATTERIES, Stream.CLOTHES, Stream.ELECTRONICS)) {
                    val art = StreamArts.getValue(s)
                    val t = view.byStream[s]
                    val dist = t?.let { shortDistance(it.distanceM) } ?: "–"
                    val d = stringResource(R.string.shop_stream_dist, stringResource(art.label), dist)
                    Column(Modifier.width(58.dp).tap(d) { nav.go(Dest.Groceries("drop:" + s.name.lowercase())) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sym(art.symbol, art.w.toFloat(), art.h.toFloat(), INK_B)
                        Line(dist, bt(12f, 800, 15.5f, tnum = true), INK_B)
                    }
                }
            }
            GoesWhere(asking, { asking = it }, question, { question = it }, answer, area, here, nav)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortCard("bin.deposit", 0xFF1E7A46, stringResource(R.string.shop_bins_deposit), Modifier.weight(1f)) { nav.go(Dest.Groceries("refill:return")) }
                ShortCard("fact.second-hand", 0xFF7B4DFF, stringResource(R.string.shop_bins_reuse), Modifier.weight(1f)) { nav.go(Dest.Groceries("street:vintage")) }
            }
            Column(Modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(22f)).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Line(stringResource(R.string.shop_bins_wrong), bt(12f, 800, 15.5f, spacing = 1.44f), SOFT)
                for ((i, item) in WasteGuide.tricky.withIndex()) {
                    val a = WasteGuide.lookup(item, "en") ?: continue
                    val symbol = listOf("bin.pizza-box", "bin.plastic-bag", "bin.takeout-tub")[i]
                    val tint = longArrayOf(0xFFC07A2A, 0xFF676D7C, 0xFF1B5FA8)[i]
                    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Sym(symbol, 26f, 26f, tint)
                        Column(Modifier.weight(1f)) {
                            Line(stringResource(itemRes(item)), bt(14f, 700, 18f), INK_B)
                            a.noteRes?.let { Line(stringResource(it), bt(11f, 400, 14f), SOFT, lines = 3) }
                        }
                        StreamChip(a)
                    }
                }
                Line(stringResource(R.string.shop_bins_goes_none), bt(12f, 400, 15.5f), SOFT, lines = 2)
            }
            Line(
                stringResource(R.string.shop_bins_source), bt(11f, 400, 14.85f, align = TextAlign.Center), 0xFF676D7C,
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 24.dp + boardLift()).fillMaxWidth(), lines = 3,
            )
        }
    }
}

private const val INK_B: Long = 0xFF15181C
private const val SOFT: Long = 0xFF464C5A

private const val BIN_RADIUS_M = 1_500.0

private fun itemRes(item: String): Int = when (item) {
    "pizza box" -> R.string.shop_guide_item_pizza
    "plastic bag" -> R.string.shop_guide_item_bag
    else -> R.string.shop_guide_item_tub
}

/** A big card for the nearest trash can or recycling point (150 high, 28 px corners, 16 in): its symbol, the arrow to it and the distance. */
@Composable
private fun BigBin(t: BinTarget?, colour: Long, symbol: String, w: Float, h: Float, heading: Float?, modifier: Modifier, desc: Int, onClick: () -> Unit) {
    val label = if (t == null) stringResource(R.string.shop_bin_none) else stringResource(desc, shortDistance(t.distanceM))
    Column(
        modifier.height(150.dp).boardBox(colour, round(28f)).tap(label, onClick).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Sym(symbol, w, h, 0xFFFFFFFF)
            if (t != null) Sym("nav.direction", 32f, 32f, 0xFFFFFFFF, rotate = PointMe.relative(t.bearing, heading?.toDouble()).toFloat())
        }
        val (n, unit) = if (t == null) "–" to "" else if (t.distanceM >= 1000) String.format(Locale.getDefault(), "%.1f", t.distanceM / 1000.0) to " km" else "${(Math.round(t.distanceM / 10.0) * 10).toInt()}" to " m"
        Block(
            buildAnnotatedString { append(n); withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)) { append(unit) } },
            bt(40f, 900, 40f, spacing = -0.8f, tnum = true), 0xFFFFFFFF,
        )
    }
}

/** A shortcut card: 64 high, 22 px corners, its symbol and word centred 8 apart. */
@Composable
private fun ShortCard(symbol: String, tint: Long, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(64.dp).boardBox(0xFFFFFFFF, round(22f)).tap(label, onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym(symbol, 22f, 22f, tint)
        Line(label, bt(14f, 800, 18f), INK_B)
    }
}

/** The board's chips for the streams it shows (paper green, blue), else the stream's colour on a 12 % wash of it. */
private fun chipColours(s: Stream): Triple<Long, Long, Long> = when (s) {
    Stream.PAPER -> Triple(0xFFE3F1E8, 0xFF1F6B3E, 0xFF2E7D4A)
    Stream.MIXED -> Triple(0xFFE4EEF9, 0xFF1B5FA8, 0xFF1B5FA8)
    else -> {
        val c = s.colour
        fun mix(shift: Int) = (255 - (0.12 * (255 - ((c shr shift) and 0xFF))).toLong()).coerceIn(0, 255)
        Triple(0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0), if (s == Stream.RUBBISH) INK_B else c, c)
    }
}

/** A pill saying where an item goes (26 high, 10 in): the stream's 8 px square and its name. */
@Composable
private fun StreamChip(a: WasteAnswer) {
    val s = a.stream ?: return
    val (bg, fg, square) = chipColours(s)
    Row(
        Modifier.height(26.dp).boardBox(bg, round(13f)).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).boardBox(square, round(2f)))
        Line(stringResource(StreamArts.getValue(s).label), bt(12f, 800, 15.5f), fg)
    }
}

/** The big dark "What goes where?" button, which opens into a field: a thing in, its stream and the nearest drop-off out. */
@Composable
private fun GoesWhere(open: Boolean, onOpen: (Boolean) -> Unit, text: String, onText: (String) -> Unit, answer: WasteAnswer?, area: ShopArea, here: LatLon, nav: Navigator) {
    val label = stringResource(R.string.shop_bins_goes)
    if (!open) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).boardBox(INK_B, round(38f)).tap(label) { onOpen(true) },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Sym("ui.search", 26f, 26f, 0xFFFFFFFF)
            Line(label, bt(18f, 800, 23.5f), 0xFFFFFFFF)
        }
        return
    }
    Column(Modifier.fillMaxWidth().boardBox(INK_B, round(28f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym("ui.search", 22f, 22f, 0xFFFFFFFF)
            val style = bt(17f, 700, 22f).copy(color = Color.White)
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    text, onText, singleLine = true, textStyle = style, cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = {}),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
                if (text.isEmpty()) Line(stringResource(R.string.shop_bins_goes_hint), style, 0x80FFFFFF)
            }
        }
        if (text.isNotBlank()) {
            if (answer?.stream == null) {
                Line(stringResource(R.string.shop_bins_goes_none), bt(13f, 400, 17f), 0xBFFFFFFF, lines = 3)
            } else {
                val s = answer.stream
                val nearest = BinsFinder.forStream(area, here, s).firstOrNull()
                Row(
                    Modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(18f)).tap(stringResource(StreamArts.getValue(s).label)) { nav.go(Dest.Groceries("drop:" + s.name.lowercase())) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym(StreamArts.getValue(s).symbol, StreamArts.getValue(s).w / 1.6f, StreamArts.getValue(s).h / 1.6f, INK_B)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Line(stringResource(StreamArts.getValue(s).label), bt(16f, 800, 20.5f), INK_B)
                        answer.noteRes?.let { Line(stringResource(it), bt(12f, 400, 15.5f), SOFT, lines = 3) }
                    }
                    Line(nearest?.let { shortDistance(it.distanceM) } ?: "–", bt(17f, 900, 22f, tnum = true), INK_B)
                }
            }
        }
    }
}
