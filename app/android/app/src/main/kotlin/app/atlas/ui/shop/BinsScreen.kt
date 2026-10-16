package app.atlas.ui.shop

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

private val Ink = Color(0xFF15181C)
private val Soft = Color(0xFF464C5A)

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

    Box(Modifier.fillMaxSize().background(Color(0xFFEFF1EF))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundSymbolButton("ui.back", stringResource(R.string.shop_bins_back), { nav.back() }, size = 44.dp, fill = Color.White, tint = Ink, iconSize = 20.dp)
                Text(stringResource(R.string.shop_bins), Modifier.weight(1f), style = TextStyle(fontFamily = SerifFamily, fontSize = 34.sp, lineHeight = 34.sp), color = Ink)
                val town = area.places.mapNotNull { it.tags["addr:city"] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.pin", tint = Soft, size = 14.dp)
                    Text(town ?: stringResource(R.string.shop_bins_as_mapped), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp), color = Soft, maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigBin(view.trash, Color(0xFF2B2F36), "bin.trash", 40, 44, heading, Modifier.weight(1f), R.string.shop_bin_trash_d) { nav.go(Dest.Groceries("drop:rubbish")) }
                BigBin(view.recycling, Color(0xFF1B5FA8), "bin.recycling", 40, 40, heading, Modifier.weight(1f), R.string.shop_bin_recycling_d) { nav.go(Dest.Groceries("drop:mixed")) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                for (s in listOf(Stream.MIXED, Stream.PAPER, Stream.ORGANIC, Stream.BATTERIES, Stream.CLOTHES, Stream.ELECTRONICS)) {
                    val art = StreamArts.getValue(s)
                    val t = view.byStream[s]
                    val dist = t?.let { shortDistance(it.distanceM) } ?: "–"
                    val d = stringResource(R.string.shop_stream_dist, stringResource(art.label), dist)
                    Column(Modifier.width(58.dp).tap(d) { nav.go(Dest.Groceries("drop:" + s.name.lowercase())) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BoardSymbol(art.symbol, art.w.dp, art.h.dp, Modifier.then(if (t == null) Modifier.background(Color.Transparent) else Modifier))
                        Text(dist, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, fontFeatureSettings = "tnum"), color = Ink, maxLines = 1)
                    }
                }
            }
            GoesWhere(asking, { asking = it }, question, { question = it }, answer, area, here, nav)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortCard("bin.deposit", Color(0xFF1E7A46), stringResource(R.string.shop_bins_deposit), Modifier.weight(1f)) { nav.go(Dest.Groceries("refill:return")) }
                ShortCard("fact.second-hand", Color(0xFF7B4DFF), stringResource(R.string.shop_bins_reuse), Modifier.weight(1f)) { nav.go(Dest.Groceries("street:vintage")) }
            }
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.shop_bins_wrong), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.4.sp), color = Soft)
                for ((i, item) in WasteGuide.tricky.withIndex()) {
                    val a = WasteGuide.lookup(item, "en") ?: continue
                    val symbol = listOf("bin.pizza-box", "bin.plastic-bag", "bin.takeout-tub")[i]
                    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(symbol, size = 26.dp)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(itemRes(item)), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp), color = Ink)
                            a.noteRes?.let { Text(stringResource(it), style = TextStyle(fontFamily = SansFamily, fontSize = 11.sp, lineHeight = 14.sp), color = Soft) }
                        }
                        StreamChip(a)
                    }
                }
                Text(stringResource(R.string.shop_bins_goes_none), style = TextStyle(fontFamily = SansFamily, fontSize = 12.sp), color = Soft)
            }
            Text(stringResource(R.string.shop_bins_source), Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(), style = TextStyle(fontFamily = SansFamily, fontSize = 11.sp, lineHeight = 15.sp), color = Color(0xFF676D7C), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private const val BIN_RADIUS_M = 1_500.0

private fun itemRes(item: String): Int = when (item) {
    "pizza box" -> R.string.shop_guide_item_pizza
    "plastic bag" -> R.string.shop_guide_item_bag
    else -> R.string.shop_guide_item_tub
}

/** A big card for the nearest trash can or recycling point: its symbol, the arrow to it and the distance. */
@Composable
private fun BigBin(t: BinTarget?, colour: Color, symbol: String, w: Int, h: Int, heading: Float?, modifier: Modifier, desc: Int, onClick: () -> Unit) {
    val label = if (t == null) stringResource(R.string.shop_bin_none) else stringResource(desc, shortDistance(t.distanceM))
    Column(
        modifier.height(150.dp).clip(RoundedCornerShape(28.dp)).background(colour).tap(label, onClick).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            BoardSymbol(symbol, w.dp, h.dp, tint = Color.White)
            if (t != null) Symbol("nav.direction", Modifier.rotate(PointMe.relative(t.bearing, heading?.toDouble()).toFloat()), tint = Color.White, size = 32.dp)
        }
        val (n, unit) = if (t == null) "–" to "" else if (t.distanceM >= 1000) String.format(Locale.getDefault(), "%.1f", t.distanceM / 1000.0) to " km" else "${(Math.round(t.distanceM / 10.0) * 10).toInt()}" to " m"
        Text(
            buildAnnotatedString { append(n); withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)) { append(unit) } },
            style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum"), color = Color.White,
        )
    }
}

@Composable
private fun ShortCard(symbol: String, tint: Color, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(64.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).tap(label, onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = tint, size = 22.dp)
        Text(label, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp), color = Ink)
    }
}

/** A pill saying where an item goes: the stream's colour square and its name, with the note when there is one. */
@Composable
private fun StreamChip(a: WasteAnswer) {
    val s = a.stream ?: return
    val c = Color(s.colour)
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(c, RoundedCornerShape(2.dp)))
        Text(
            stringResource(StreamArts.getValue(s).label),
            style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp), color = if (s == Stream.RUBBISH) Ink else c, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The big dark "What goes where?" button, which opens into a field: a thing in, its stream and the nearest drop-off out. */
@Composable
private fun GoesWhere(open: Boolean, onOpen: (Boolean) -> Unit, text: String, onText: (String) -> Unit, answer: WasteAnswer?, area: ShopArea, here: LatLon, nav: Navigator) {
    val label = stringResource(R.string.shop_bins_goes)
    if (!open) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(38.dp)).background(Ink).tap(label) { onOpen(true) },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("ui.search", tint = Color.White, size = 26.dp)
            Text(label, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp), color = Color.White)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol("ui.search", tint = Color.White, size = 22.dp)
            val style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    text, onText, singleLine = true, textStyle = style, cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = {}),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
                if (text.isEmpty()) Text(stringResource(R.string.shop_bins_goes_hint), style = style, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
            }
        }
        if (text.isNotBlank()) {
            if (answer?.stream == null) {
                Text(stringResource(R.string.shop_bins_goes_none), style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp), color = Color.White.copy(alpha = 0.75f))
            } else {
                val s = answer.stream
                val nearest = BinsFinder.forStream(area, here, s).firstOrNull()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).tap(stringResource(StreamArts.getValue(s).label)) { nav.go(Dest.Groceries("drop:" + s.name.lowercase())) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoardSymbol(StreamArts.getValue(s).symbol, (StreamArts.getValue(s).w / 1.6).dp, (StreamArts.getValue(s).h / 1.6).dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(StreamArts.getValue(s).label), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp), color = Ink)
                        answer.noteRes?.let { Text(stringResource(it), style = TextStyle(fontFamily = SansFamily, fontSize = 12.sp), color = Soft) }
                    }
                    Text(nearest?.let { shortDistance(it.distanceM) } ?: "–", style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Black, fontSize = 17.sp, fontFeatureSettings = "tnum"), color = Ink)
                }
            }
        }
    }
}
