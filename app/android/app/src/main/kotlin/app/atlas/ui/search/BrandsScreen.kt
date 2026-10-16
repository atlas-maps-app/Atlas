package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
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
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.Segment
import app.atlas.ui.kit.SegmentedPill
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetLook
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.TxLine
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssEl
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.txStyle
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/** How far around you Brands looks. */
internal const val BRANDS_RADIUS_M = 1_200.0

/** The placeholder bell of a brand: brands to tell about, kept in settings. */
internal val BRAND_ALERTS = stringSetPreferencesKey("search.brand.alerts")

/** The Brands board's colours (`Brands.dc.html`). */
private object BrandsLook {
    const val PAPER: Argb = 0xFFF2F1EC
    const val INK: Argb = 0xFF14161B
    const val WHITE: Argb = 0xFFFFFFFF
    const val SUB: Argb = 0xFF464C5A
    const val CLOSED: Argb = 0xFF676D7C
    const val HIDDEN_TILE: Argb = 0xFFE9E7E0
    const val HIDDEN_INK: Argb = 0xFF8A8F99
    const val ROUND_BUTTON: Argb = 0xFFF1EEE8
    const val OPEN_DOT: Argb = 0xFF1E9E5A
    const val CLOSED_DOT: Argb = 0xFFB5B9C2
    const val RULE: Argb = 0xFFECE8E1
    const val GRABBER: Argb = 0xFFD8D4CC

    /** The sheet's height on the board: 368 px from its top to the screen's foot. */
    val SHEET_PEEK: Dp = 368.dp
}

/** The board's sheet: white, 30 round on top, its shadow 10 px up and 30 soft. */
private val BRANDS_SHEET = SheetLook(
    Modifier.cssEl(BrandsLook.WHITE, r = CssCorners(30f, 30f, 0f, 0f), shadows = listOf(CssShadow(0f, -10f, 30f, 0f, 0x1F181C30)), clip = true),
    argb(BrandsLook.GRABBER),
)

/**
 * Brands around you, as on the Brands board: the back button and "Brands" in Instrument Serif, "Chains N | Independent
 * M" for the area, the chains as a grid of 96 px logo tiles four across with their branch counts (and a tile for the
 * hidden ones), and the sheet of the chosen brand with its branches by walk time, open or not, and "All N on the map".
 */
@Composable
fun BrandsScreen(args: Dest.Brands, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val hidden by services.saved.hiddenBrands.collectAsStateWithLifecycle()
    val filter = remember(args.category) { args.category?.let { Categories.byId(it) }?.let { LayeredPlaceFinder.filterOf(it) } }
    val nearby by produceState<BrandsNearby?>(null, here, filter) {
        val all = BrandsAround.near(services, here, BRANDS_RADIUS_M)
        value = if (filter == null) all else BrandsAround.split(
            (all.chains.flatMap { it.branches } + all.independents).filter { filter.matches(it.tags) }, here, BRANDS_RADIUS_M, all.origin,
        )
    }
    val n = nearby
    val visible = n?.chains.orEmpty().filter { showHidden || it.brand.id !in hidden }
    LaunchedEffect(n) { if (chosen == null) chosen = n?.chains?.firstOrNull { it.brand.id !in hidden }?.brand?.id }
    val selected = n?.chains?.firstOrNull { it.brand.id == chosen }
    val sheet = rememberSheetState(SheetStop.PEEK, setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL))
    val sheetUp = tab == 0 && selected != null

    Box(Modifier.fillMaxSize().background(argb(BrandsLook.PAPER))) {
        Column(Modifier.fillMaxSize().padding(top = boardShift())) {
            // The head at 42: the 44 px back button and the title 10 after it.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp).height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandsRoundButton("ui.back", stringResource(R.string.kit_back), BrandsLook.WHITE, glyph = 20f, onClick = { nav.back() })
                TxLine(
                    stringResource(R.string.brands_title), txStyle(34f, 400, 34f, family = SERIF), BrandsLook.INK,
                    Modifier.weight(1f).semantics { heading() },
                )
            }
            // Chains or independent, at 100.
            SegmentedPill(
                listOf(
                    Segment(stringResource(R.string.brands_chains), "ui.link", n?.chains?.size),
                    Segment(stringResource(R.string.brands_independent), "fact.independent", n?.independents?.size),
                ),
                tab, { tab = it },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
            val listFoot = if (sheetUp) BrandsLook.SHEET_PEEK + 8.dp else 24.dp
            when {
                n == null -> TxLine(stringResource(R.string.kit_loading), txStyle(13f, 400, 17f), BrandsLook.SUB, Modifier.padding(20.dp))
                tab == 0 && n.chains.isEmpty() || tab == 1 && n.independents.isEmpty() -> StateMessage("ui-store", stringResource(R.string.brands_none))
                tab == 0 -> LazyVerticalGrid(
                    GridCells.Fixed(4),
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = listFoot),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.brand.id }) { chain ->
                        BrandTile(
                            chain.brand, chain.branches.size, selected = chain.brand.id == chosen, dimmed = chain.brand.id in hidden,
                            onClick = { chosen = chain.brand.id },
                        )
                    }
                    val hiddenHere = n.chains.count { it.brand.id in hidden }
                    if (hiddenHere > 0) item(key = "hidden") { HiddenTile(hiddenHere, showHidden) { showHidden = !showHidden } }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "origin") { OriginNote(n) }
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = listFoot)) {
                    val shown = n.independents.take(80)
                    itemsIndexed(shown, key = { _, p -> p.key }) { i, p ->
                        BranchRow(p, here, null, last = i == shown.lastIndex) { services.remember(p); nav.go(Dest.Place.of(p)) }
                    }
                    item(key = "origin") { OriginNote(n) }
                }
            }
        }
        if (sheetUp && selected != null) {
            Sheet(sheet, peekHeight = BrandsLook.SHEET_PEEK, halfFraction = 0.6f, look = BRANDS_SHEET) {
                BrandSheet(selected, here, hidden, nav, onAll = { nav.go(Dest.Brand(selected.brand.id)) })
            }
        }
    }
}

/** A round board button: [bg] at [side] px with a [glyph] px [symbol] in [tint]. */
@Composable
private fun BrandsRoundButton(
    symbol: String,
    description: String,
    bg: Argb,
    onClick: () -> Unit,
    glyph: Float = 19f,
    tint: Argb = BrandsLook.INK,
    side: Float = 44f,
    selected: Boolean? = null,
) {
    Box(
        Modifier
            .size(side.dp)
            .cssEl(bg, r = CssCorners(side / 2, side / 2, side / 2, side / 2), clip = true)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = argb(tint), size = glyph.dp) }
}

/** A brand's sign colour, or a colour of its own picked by its id when the index has none. */
internal fun brandColour(brand: Brand): Color = brand.colour?.let { Color(it) } ?: Color(MONOGRAM[Math.floorMod(brand.id.hashCode(), MONOGRAM.size)])

/** The text colour on a brand's sign. */
internal fun brandInk(brand: Brand): Color =
    brand.textColour?.let { Color(it) } ?: if (brandColour(brand).luminance() > 0.45f) argb(BrandsLook.INK) else Color.White

private fun Color.argbLong(): Argb = toArgb().toLong() and 0xFFFFFFFFL

/**
 * A brand's tile, 96 px high, 18 round: its 40 px logo over "Name · 8" in Geist 700 at 12, 6 apart. The chosen one takes
 * the brand's colour inside a 3 px ink ring; a brand with no logo shows its initial (900 at 34) on its colour; the
 * others are white with the logo in the brand's colour. Hidden brands, when shown, are faded.
 */
@Composable
internal fun BrandTile(brand: Brand, branches: Int, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.brands_branches, brandName(brand), branches)
    val slug = brand.logoSlug
    val coloured = selected || slug == null
    val colour = brandColour(brand)
    val bg = if (coloured) colour.argbLong() else BrandsLook.WHITE
    val ink = if (coloured) brandInk(brand).argbLong() else BrandsLook.INK
    Column(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .cssEl(bg, r = TILE, shadows = if (selected) listOf(CssShadow(0f, 0f, 0f, 3f, BrandsLook.INK)) else emptyList(), clip = true)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected }
            .alpha(if (dimmed) 0.4f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        if (slug == null) {
            // The board's initial: 900 at 34 on a 40 px line, the face's taller glyph box centred on it as CSS does.
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                TxLine(brandName(brand).take(1).uppercase(), txStyle(34f, 900, 40f), ink, Modifier.wrapContentHeight(unbounded = true))
            }
        } else {
            // On white the logo takes the brand's colour, unless that colour would vanish on white.
            val tint = if (coloured) Color(ink.toInt()) else if (colour.luminance() > 0.75f) argb(BrandsLook.INK) else colour
            Symbol("logo-$slug", tint = tint, size = 40.dp)
        }
        TxLine("${brandName(brand)} · $branches", txStyle(12f, 700, 15.5f, align = TextAlign.Center), ink, Modifier.padding(horizontal = 4.dp))
    }
}

private val TILE = CssCorners(18f, 18f, 18f, 18f)

/** The sand tile of the hidden brands: the 36 px eye, "N hidden", and a tap to show or hide them in the grid. */
@Composable
private fun HiddenTile(count: Int, showing: Boolean, onClick: () -> Unit) {
    val d = stringResource(if (showing) R.string.brands_hidden_hide else R.string.brands_hidden_show, count)
    Column(
        Modifier.fillMaxWidth().height(96.dp).cssEl(BrandsLook.HIDDEN_TILE, r = TILE, clip = true).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = d },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Symbol(if (showing) "ui.eye" else "ui.hide", tint = argb(BrandsLook.HIDDEN_INK), size = 36.dp)
        TxLine(stringResource(R.string.brands_hidden_n, count), txStyle(12f, 700, 15.5f, align = TextAlign.Center), BrandsLook.HIDDEN_INK)
    }
}

/** Where the brands came from: the region on the phone, or only the map's labels. */
@Composable
private fun OriginNote(n: BrandsNearby) {
    val text = when (n.origin) {
        app.atlas.services.PlaceOrigin.STORE -> stringResource(R.string.brands_from_store)
        app.atlas.services.PlaceOrigin.TILES -> stringResource(R.string.brands_from_tiles)
        else -> null
    } ?: return
    TxLine(text, txStyle(12f, 400, 15.5f, align = TextAlign.Center), BrandsLook.CLOSED, Modifier.fillMaxWidth().padding(12.dp), lines = 3)
}

/**
 * The head of a brand's sheet, as on the board: its 52 px sign (14 round), the name in 800 at 20 over "8 branches · 6
 * open", and the bell and hide as 44 px sand buttons.
 */
@Composable
internal fun BrandHead(brand: Brand, sub: String, bell: Boolean, isHidden: Boolean, onBell: () -> Unit, onHide: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandLogo(brand, 52.dp, corner = 14.dp, logo = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TxLine(brandName(brand), txStyle(20f, 800, 26f), BrandsLook.INK)
            TxLine(sub, txStyle(13f, 400, 17f, tnum = true), BrandsLook.SUB)
        }
        // Watching shows as the ink button; the board's resting state is sand.
        BrandsRoundButton(
            "ui.bell", stringResource(if (bell) R.string.brands_bell_on else R.string.brands_bell),
            if (bell) BrandsLook.INK else BrandsLook.ROUND_BUTTON, onBell,
            tint = if (bell) BrandsLook.WHITE else BrandsLook.INK, selected = bell,
        )
        BrandsRoundButton(
            if (isHidden) "ui.eye" else "ui.hide", stringResource(if (isHidden) R.string.brands_show else R.string.brands_hide, brandName(brand)),
            BrandsLook.ROUND_BUTTON, onHide, selected = isHidden,
        )
    }
}

/** The board's dark call to action, 52 px and fully round: the map symbol 8 before "All 8 on the map". */
@Composable
internal fun AllOnMapButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(52.dp).cssEl(BrandsLook.INK, r = CssCorners(26f, 26f, 26f, 26f), clip = true).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("tab.map", tint = Color.White, size = 19.dp)
        TxLine(stringResource(R.string.brands_all_on_map, count), txStyle(15f, 600, 19.5f), BrandsLook.WHITE)
    }
}

/** The chosen brand: its head, its branches by walk time, and all on the map, 12 px apart as the board's sheet. */
@Composable
private fun ColumnScope.BrandSheet(chain: BrandBranches, here: LatLon, hidden: Set<String>, nav: Navigator, onAll: () -> Unit) {
    val services = LocalServices.current
    val now = rememberNow(chain)
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val open = remember(chain, now) {
        chain.branches.count { OpeningHours.parseOrNull(it.tags["opening_hours"])?.isOpen(now, ctx) == true }
    }
    val alerts by remember { services.settings.flow(BRAND_ALERTS, emptySet()) }.collectAsStateWithLifecycle(emptySet())
    val brand = chain.brand
    val isHidden = brand.id in hidden
    val bell = brand.id in alerts
    // The kit's sheet puts its content 21 px under its top; the board's head is 27 under it.
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandHead(
            brand, pluralStringResource(R.plurals.brand_branches_open, chain.branches.size, chain.branches.size, open), bell, isHidden,
            onBell = {
                services.scope.launch { services.settings.edit { p -> val s = p[BRAND_ALERTS].orEmpty(); p[BRAND_ALERTS] = if (bell) s - brand.id else s + brand.id } }
            },
            onHide = { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.hideBrand(brand.id, !isHidden) } } },
        )
        val rows = chain.branches.take(40)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(rows, key = { _, p -> p.key }) { i, p ->
                BranchRow(p, here, now, last = i == rows.lastIndex) { services.remember(p); nav.go(Dest.Place.of(p)) }
            }
        }
        AllOnMapButton(chain.branches.size, onAll)
        Spacer(Modifier.height(25.dp))
    }
}

/**
 * A branch as the board's sheet lists it: 58 px over a hairline (#ECE8E1, none under the last), a 10 px green (open) or
 * grey dot, the branch's name or street and level in 600 at 16, "→ 21:00" or "opens 10:00" in 13, and the walk minutes
 * in 800 at 18 in a 44 px column at the right; a closed branch is grey. [now] null reads the clock itself.
 */
@Composable
internal fun BranchRow(place: Place, here: LatLon, now: LocalDateTime?, last: Boolean = false, onClick: () -> Unit) {
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (last) 58.dp else 59.dp)
            .then(if (last) Modifier else Modifier.cssEl(border = app.atlas.ui.kit.CssBorder(0f, 0f, 1f, 0f, longArrayOf(BrandsLook.INK, BrandsLook.INK, BrandsLook.RULE, BrandsLook.INK))))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(bottom = if (last) 0.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(argb(if (openNow) BrandsLook.OPEN_DOT else BrandsLook.CLOSED_DOT)))
        Column(Modifier.weight(1f)) {
            TxLine(title, txStyle(16f, 600, 20.5f), if (closed) BrandsLook.CLOSED else BrandsLook.INK)
            if (title != line.summary.name && !branded) line.summary.kind?.let { TxLine(it, txStyle(13f, 400, 17f), BrandsLook.SUB) }
        }
        status?.let { TxLine(it, txStyle(13f, 400, 17f, tnum = true), if (line.tone == Tone.SOON) toneArgb(Tone.SOON) else if (closed) BrandsLook.CLOSED else BrandsLook.SUB) }
        line.trailing?.let {
            TxLine(it, txStyle(18f, 800, 23.5f, tnum = true, align = TextAlign.End), if (closed) BrandsLook.CLOSED else BrandsLook.INK, Modifier.width(44.dp))
        }
    }
}

/** [places] as branch rows, nearest first as given, divided by hairlines. */
@Composable
internal fun BranchRows(places: List<Place>, here: LatLon, onPlace: (Place) -> Unit) {
    val now = rememberNow(places)
    places.forEachIndexed { i, p -> BranchRow(p, here, now, last = i == places.lastIndex) { onPlace(p) } }
}

/** The name a brand trades under ("Jumbo"), without the index's region ("Jumbo (Europe)"). */
internal fun brandName(brand: Brand): String = brand.brand.ifBlank { brand.name }

/** Sign colours for brands whose own is not known, picked by the brand's id so each keeps its colour. */
private val MONOGRAM = longArrayOf(0xFF2F5D8A, 0xFFC8322D, 0xFF2F7D3A, 0xFFE8962C, 0xFF7B4FD6, 0xFF0E8C8C, 0xFF8A5A2B, 0xFFD2457F)

/**
 * A brand's sign: its logo ([logo], 62 % of [size] by default) on its colour in a square rounded by [corner], or its
 * initial in Geist 900 when it has no logo.
 */
@Composable
internal fun BrandLogo(brand: Brand, size: Dp, corner: Dp = size * 0.28f, logo: Dp = size * 0.62f) {
    val bg = brandColour(brand)
    val fg = brandInk(brand)
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(bg), contentAlignment = Alignment.Center) {
        val slug = brand.logoSlug
        if (slug != null) Symbol("logo-$slug", tint = fg, size = logo)
        else TxLine(brandName(brand).take(1).uppercase(), txStyle(size.value * 0.5f, 900, size.value * 0.6f), fg.argbLong())
    }
}
