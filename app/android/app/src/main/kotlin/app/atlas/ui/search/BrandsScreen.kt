package app.atlas.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brand
import app.atlas.places.Categories
import app.atlas.places.OpeningHours
import app.atlas.services.LayeredPlaceFinder
import app.atlas.ui.kit.BrandSign
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.TxLine
import app.atlas.ui.kit.cssEl
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.txStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/** How far around you Brands looks. */
internal const val BRANDS_RADIUS_M = 1_200.0

/** The board's grid of chains, for tests to scroll. */
internal const val BRANDS_GRID_TAG = "brands-grid"

/** The inks of a branch row on the Index's paper, light or dark ([of]), as the Brands board lists its independents. */
internal class BrandInks(
    val ink: Color,
    val sub: Color,
    val closed: Color,
    val rule: Color,
    val open: Color,
    val shut: Color,
) {
    companion object {
        fun of(c: IndexColors) = BrandInks(c.ink, c.soft, c.faint, c.hair, c.open, c.faint)
    }
}

/** The chains around and their stacks by kind, worked out once, off the main thread. */
private class Around(val nearby: BrandsNearby, val stacks: List<BrandStack>)

/**
 * Brands around you, rebuilt in the Index's look, light or dark, on the search's own signs (the owner, 6 November:
 * "Improve or re built the brand board along the way"): the way back and "Brands", the chains or the independents to
 * choose with how many there are, and the chains stacked by kind as the search's shelf stacks them
 * ([BrandsAround.stacks]). A kind of one chain is that chain's sign, how many of its branches are near on a tab at its
 * corner and its kind under its name, so that a brand no one knows says what it is; a kind of more is a deck under its
 * name and how many chains it holds, dealt out in its place on a tray at a press, led by its kind's disc, which stacks
 * it again. A chain pressed opens its page ([Dest.Brand]: the search's chain in its own colours, all that is known of
 * it and its branches as rows, cards or pins; the owner, 6 November, of that page: "accessible from the brands menu"),
 * and back comes back to the board as it was left. The hidden brands wait behind a tile of their own;
 * [Dest.Brands.category] narrows to one category.
 */
@Composable
fun BrandsScreen(args: Dest.Brands, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val c = indexColors()
    val lang = remember { Locale.getDefault().language }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dealt by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val hidden by services.saved.hiddenBrands.collectAsStateWithLifecycle()
    val filter = remember(args.category) { args.category?.let { Categories.byId(it) }?.let { LayeredPlaceFinder.filterOf(it) } }
    val around by produceState<Around?>(null, here, filter) {
        val all = BrandsAround.near(services, here, BRANDS_RADIUS_M)
        val n = if (filter == null) all else BrandsAround.split(
            (all.chains.flatMap { it.branches } + all.independents).filter { filter.matches(it.tags) }, here, BRANDS_RADIUS_M, all.origin,
        )
        value = Around(n, withContext(Dispatchers.Default) { BrandsAround.stacks(n.chains) })
    }
    val n = around?.nearby
    // the hidden brands leave their stacks, unless they are shown
    val stacks = remember(around, hidden, showHidden) {
        around?.stacks.orEmpty().mapNotNull { s ->
            s.chains.filter { showHidden || it.brand.id !in hidden }.takeIf { it.isNotEmpty() }?.let { BrandStack(s.kind, it) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().padding(top = boardShift())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp).height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IndexRound("ui-chevron-left", stringResource(R.string.kit_back), { nav.back() }, size = 44.dp, glyph = 20.dp)
                Text(stringResource(R.string.brands_title), Modifier.weight(1f).semantics { heading() }, style = indexTitle(30f), color = c.ink, maxLines = 1)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IndexChip(stringResource(R.string.brands_chains), { tab = 0 }, symbol = "ui.link", count = n?.chains?.size, selected = tab == 0, role = Role.RadioButton)
                IndexChip(
                    stringResource(R.string.brands_independent), { tab = 1 }, symbol = "fact.independent", count = n?.independents?.size,
                    selected = tab == 1, role = Role.RadioButton,
                )
            }
            when {
                n == null -> Text(stringResource(R.string.kit_loading), Modifier.padding(20.dp), style = boardText(13f, 400, 17f), color = c.soft)
                tab == 0 && n.chains.isEmpty() || tab == 1 && n.independents.isEmpty() -> StateMessage("ui-store", stringResource(R.string.brands_none))
                tab == 0 -> ChainsGrid(
                    stacks, n, lang, dealt, hidden, showHidden,
                    onDeal = { dealt = it },
                    onOpen = { chain -> nav.go(Dest.Brand(chain.brand.id)) },
                    onHidden = { showHidden = !showHidden },
                )
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp)) {
                    val shown = n.independents.take(80)
                    val inks = BrandInks.of(c)
                    itemsIndexed(shown, key = { _, p -> p.key }) { i, p ->
                        BranchRow(p, here, null, last = i == shown.lastIndex, inks = inks) { services.remember(p); nav.go(Dest.Place.of(p)) }
                    }
                    item(key = "origin") { OriginNote(n) }
                }
            }
        }
    }
}

/**
 * The chains, four to a line: a kind's stack, dealt out where it is [dealt] ([DealtTray]), or its one chain's sign with
 * its kind under its name; then the hidden brands' tile and where the brands came from.
 */
@Composable
private fun ColumnScope.ChainsGrid(
    stacks: List<BrandStack>,
    n: BrandsNearby,
    lang: String,
    dealt: String?,
    hidden: Set<String>,
    showHidden: Boolean,
    onDeal: (String?) -> Unit,
    onOpen: (BrandBranches) -> Unit,
    onHidden: () -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Fixed(LINE),
        Modifier.fillMaxWidth().weight(1f).testTag(BRANDS_GRID_TAG),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (stack in stacks) {
            val only = stack.chains.singleOrNull()
            when {
                only != null -> item(key = only.brand.id) {
                    ChainSign(only, CELL_SIGN, { onOpen(only) }, Modifier.fillMaxWidth(), sub = stack.kind?.name(lang), dimmed = only.brand.id in hidden)
                }
                stack.id == dealt -> item(key = "dealt:" + stack.id, span = { GridItemSpan(maxLineSpan) }) {
                    DealtTray(stack, lang, hidden, onStack = { onDeal(null) }, onOpen = onOpen)
                }
                else -> item(key = "stack:" + stack.id) {
                    val count = pluralStringResource(R.plurals.brands_n_chains, stack.chains.size, stack.chains.size)
                    ChainStack(stack, stack.name(lang), CELL_SIGN, { onDeal(stack.id) }, Modifier.fillMaxWidth(), sub = count)
                }
            }
        }
        val hiddenHere = n.chains.count { it.brand.id in hidden }
        if (hiddenHere > 0) item(key = "hidden") { HiddenCell(hiddenHere, showHidden, onHidden) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "origin") { OriginNote(n) }
    }
}

/**
 * A stack dealt out on the board, in its place and brought into view, on a tray that reaches into the margins: its
 * kind's disc first, over how many chains it holds, which stacks it again; then the sign of each of its chains, four to
 * a line as the grid's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DealtTray(stack: BrandStack, lang: String, hidden: Set<String>, onStack: () -> Unit, onOpen: (BrandBranches) -> Unit) {
    val c = indexColors()
    val into = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val count = pluralStringResource(R.plurals.brands_n_chains, stack.chains.size, stack.chains.size)
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(into)
            .onSizeChanged { scope.launch { into.bringIntoView() } }
            .drawBehind {
                val out = TRAY_OUT.toPx()
                drawRoundRect(c.tray, topLeft = Offset(-out, 0f), size = Size(size.width + out * 2, size.height), cornerRadius = CornerRadius(TRAY_CORNER.toPx()))
            }
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // the disc is the first of the tray's cells, the chains the rest
        for (line in (0..stack.chains.size).chunked(LINE)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            for (i in line) {
                if (i == 0) StackHead(stack, stack.name(lang), CELL_SIGN, onStack, Modifier.weight(1f), sub = count)
                else stack.chains[i - 1].let { ch ->
                    ChainSign(ch, CELL_SIGN, { onOpen(ch) }, Modifier.weight(1f), dimmed = ch.brand.id in hidden)
                }
            }
            repeat(LINE - line.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** The tile of the hidden brands: the eye, "N hidden", and a press to show them in their stacks or hide them again. */
@Composable
private fun HiddenCell(count: Int, showing: Boolean, onClick: () -> Unit) {
    val c = indexColors()
    val said = stringResource(if (showing) R.string.brands_hidden_hide else R.string.brands_hidden_show, count)
    SignColumn(onClick, Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = said }) {
        Box(Modifier.size(CELL_SIGN).recessed(RoundedCornerShape(cornerOf(CELL_SIGN)), c), contentAlignment = Alignment.Center) {
            Symbol(if (showing) "ui.eye" else "ui.hide", tint = c.faint, size = 26.dp)
        }
        Text(stringResource(R.string.brands_hidden_n, count), style = SIGN_NAME, color = c.soft, maxLines = 1)
    }
}

/** Where the brands came from: the region on the phone, or the map's data on the phone. */
@Composable
private fun OriginNote(n: BrandsNearby) {
    val text = when (n.origin) {
        app.atlas.services.PlaceOrigin.STORE -> stringResource(R.string.brands_from_store)
        app.atlas.services.PlaceOrigin.PACK -> stringResource(R.string.brands_from_pack)
        else -> null
    } ?: return
    Text(text, Modifier.fillMaxWidth().padding(12.dp), style = boardText(12f, 400, 15.5f, align = TextAlign.Center), color = indexColors().faint, maxLines = 3)
}

/** The board's chains four to a line, the room between them, each sign's side, and how far a dealt tray reaches out. */
private const val LINE = 4
private val GAP = 4.dp
private val CELL_SIGN = 56.dp
private val TRAY_OUT = 8.dp
private val TRAY_CORNER = 20.dp

private fun Color.argbLong(): Argb = toArgb().toLong() and 0xFFFFFFFFL

/**
 * A place as the board lists the independents: 58 px over a hairline (none under the last), a 10 px green (open) or
 * grey dot, its name (a branch's street and level) in 600 at 16, "→ 21:00" or "opens 10:00" in 13, and the walk
 * minutes in 800 at 18 in a 44 px column at the right; a closed one is grey. [now] null reads the clock itself.
 */
@Composable
internal fun BranchRow(
    place: Place,
    here: LatLon,
    now: LocalDateTime?,
    last: Boolean = false,
    inks: BrandInks,
    onClick: () -> Unit,
) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val t = now ?: rememberNow(place)
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val line = remember(place, here, t) {
        PlaceLine.of(place, here, lang, t, open = OpeningHours.parseOrNull(place.tags["opening_hours"])?.status(t, ctx))
    }
    val street = listOfNotNull(place.tags["addr:street"], place.tags["addr:housenumber"]).joinToString(" ").ifEmpty { null }
    val branded = place.tags["brand"] != null || place.tags["brand:wikidata"] != null
    val title = if (branded) {
        listOfNotNull(place.tags["branch"] ?: street ?: line.summary.name, levelOf(place.tags)?.let { "L$it" }).joinToString(" · ")
    } else line.summary.name
    val status = when (val w = line.words) {
        is OpenWords.OpenUntil -> "→ " + untilClock(w.at)
        is OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, w.minutes)
        is OpenWords.Opens -> stringResource(R.string.place_opens, if (w.sameDay) clock(w.at) else w.at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + clock(w.at))
        OpenWords.Always -> stringResource(R.string.kit_open_24_7)
        OpenWords.Open -> stringResource(R.string.kit_open)
        OpenWords.Closed -> stringResource(R.string.kit_closed)
        null -> null
    }
    val closed = line.tone == Tone.CLOSED
    val openNow = line.tone == Tone.OPEN || line.tone == Tone.SOON
    val ink = inks.ink.argbLong()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (last) 58.dp else 59.dp)
            .then(if (last) Modifier else Modifier.cssEl(border = CssBorder(0f, 0f, 1f, 0f, longArrayOf(ink, ink, inks.rule.argbLong(), ink))))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(bottom = if (last) 0.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (openNow) inks.open else inks.shut))
        Column(Modifier.weight(1f)) {
            TxLine(title, txStyle(16f, 600, 20.5f), (if (closed) inks.closed else inks.ink).argbLong())
            if (title != line.summary.name && !branded) line.summary.kind?.let { TxLine(it, txStyle(13f, 400, 17f), inks.sub.argbLong()) }
        }
        status?.let { TxLine(it, txStyle(13f, 400, 17f, tnum = true), if (line.tone == Tone.SOON) toneArgb(Tone.SOON) else (if (closed) inks.closed else inks.sub).argbLong()) }
        line.trailing?.let {
            TxLine(it, txStyle(18f, 800, 23.5f, tnum = true, align = TextAlign.End), (if (closed) inks.closed else inks.ink).argbLong(), Modifier.width(44.dp))
        }
    }
}

/** The name a brand trades under ("Jumbo"), without the index's region ("Jumbo (Europe)"). */
internal fun brandName(brand: Brand): String = brand.brand.ifBlank { brand.name }

/**
 * A brand's sign ([BrandSign]): its logo ([logo], 62 % of [size] by default) on its colour in a square rounded by
 * [corner], or its initial in Geist 900 when it has no logo.
 */
@Composable
internal fun BrandLogo(brand: Brand, size: Dp, corner: Dp = size * 0.28f, logo: Dp = size * 0.62f) =
    BrandSign(brand, brandName(brand), size, shape = RoundedCornerShape(corner), logo = logo)
