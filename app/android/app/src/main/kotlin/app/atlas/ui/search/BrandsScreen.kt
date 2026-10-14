package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.BackButton
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.Segment
import app.atlas.ui.kit.SegmentedPill
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.StatusDot
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.contentColourFor
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.rememberSheetState
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

/**
 * Brands around you, as on the Brands board: "Chains N | Independent M" for the area, the chains as a grid of logo
 * tiles with their branch counts (and a tile for the hidden ones), and the sheet of the chosen brand with its branches
 * by walk time, open or not, and "All N on the map".
 */
@Composable
fun BrandsScreen(args: Dest.Brands, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val c = Atlas.colors
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
    val sheet = rememberSheetState(SheetStop.HALF, setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL))

    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton({ nav.back() })
                Text(stringResource(R.string.brands_title), style = Atlas.type.display, color = c.ink)
            }
            SegmentedPill(
                listOf(
                    Segment(stringResource(R.string.brands_chains), "ui-link", n?.chains?.size),
                    Segment(stringResource(R.string.brands_independent), "ui-store", n?.independents?.size),
                ),
                tab, { tab = it },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            when {
                n == null -> Text(stringResource(R.string.kit_loading), Modifier.padding(20.dp), style = Atlas.type.caption, color = c.inkSoft)
                tab == 0 && n.chains.isEmpty() || tab == 1 && n.independents.isEmpty() -> StateMessage("ui-store", stringResource(R.string.brands_none))
                tab == 0 -> LazyVerticalGrid(
                    GridCells.Fixed(4),
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 320.dp),
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
                    item(key = "hidden") { HiddenTile(hiddenHere, showHidden) { showHidden = !showHidden } }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "origin") { OriginNote(n) }
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(n.independents.take(80), key = { it.key }) { p ->
                        BranchRow(p, here, null) { services.remember(p); nav.go(Dest.Place.of(p)) }
                        Hairline()
                    }
                    item(key = "origin") { OriginNote(n) }
                    item(key = "end") { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
                }
            }
        }
        if (tab == 0 && selected != null) {
            Sheet(sheet, peekHeight = 150.dp, halfFraction = 0.46f) {
                BrandSheet(selected, here, hidden, nav, onAll = { nav.go(Dest.Brand(selected.brand.id)) })
            }
        }
    }
}

/** A brand's tile: its logo, "Name · 8", a dark ring when chosen, faded when hidden. */
@Composable
internal fun BrandTile(brand: Brand, branches: Int, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val description = stringResource(R.string.brands_branches, brandName(brand), branches)
    Column(
        Modifier
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(18.dp))
            .background(c.card)
            .then(if (selected) Modifier.border(2.5.dp, c.ink, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected }
            .alpha(if (dimmed) 0.4f else 1f)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandLogo(brand, 46.dp)
        Text(
            "${brandName(brand)} · $branches", Modifier.padding(top = 6.dp),
            style = Atlas.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp),
            color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** The grey tile of the hidden brands: how many, and a tap to show or hide them in the grid. */
@Composable
private fun HiddenTile(count: Int, showing: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val d = stringResource(if (showing) R.string.brands_hidden_hide else R.string.brands_hidden_show, count)
    Column(
        Modifier.aspectRatio(0.92f).clip(RoundedCornerShape(18.dp)).background(c.paperDeep).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = d }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Symbol(if (showing) "ui-eye" else "ui-eye-off", tint = c.inkSoft, size = 30.dp)
        Text(
            if (count > 0) stringResource(R.string.brands_hidden_n, count) else stringResource(R.string.brands_hidden),
            Modifier.padding(top = 6.dp), style = Atlas.type.caption, color = c.inkSoft,
        )
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
    Text(text, Modifier.fillMaxWidth().padding(12.dp), style = Atlas.type.caption, color = Atlas.colors.inkFaint, textAlign = TextAlign.Center)
}

/** The chosen brand: logo, "8 branches · 6 open", the bell and hide, its branches by walk time, and all on the map. */
@Composable
private fun BrandSheet(chain: BrandBranches, here: LatLon, hidden: Set<String>, nav: Navigator, onAll: () -> Unit) {
    val services = LocalServices.current
    val c = Atlas.colors
    val now = rememberNow(chain)
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val open = remember(chain, now) {
        chain.branches.count { OpeningHours.parseOrNull(it.tags["opening_hours"])?.isOpen(now, ctx) == true }
    }
    val alerts by remember { services.settings.flow(BRAND_ALERTS, emptySet()) }.collectAsStateWithLifecycle(emptySet())
    val brand = chain.brand
    val isHidden = brand.id in hidden
    val bell = brand.id in alerts
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandLogo(brand, 52.dp)
            Column(Modifier.weight(1f)) {
                Text(brandName(brand), style = Atlas.type.headline, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.brand_branches_open, chain.branches.size, chain.branches.size, open),
                    style = Atlas.type.caption, color = c.inkSoft,
                )
            }
            SymbolButton("ui-bell", stringResource(if (bell) R.string.brands_bell_on else R.string.brands_bell), {
                services.scope.launch { services.settings.edit { p -> val s = p[BRAND_ALERTS].orEmpty(); p[BRAND_ALERTS] = if (bell) s - brand.id else s + brand.id } }
            }, size = 44.dp, elevation = 0.dp, background = c.cardMuted, selected = bell)
            SymbolButton(if (isHidden) "ui-eye" else "ui-eye-off", stringResource(if (isHidden) R.string.brands_show else R.string.brands_hide, brandName(brand)), {
                services.scope.launch(Dispatchers.IO) { runCatching { services.saved.hideBrand(brand.id, !isHidden) } }
            }, size = 44.dp, elevation = 0.dp, background = c.cardMuted, selected = isHidden)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp)) {
            items(chain.branches.take(40), key = { it.key }) { p ->
                BranchRow(p, here, now) { services.remember(p); nav.go(Dest.Place.of(p)) }
                Hairline()
            }
        }
        PillButton(onClick = onAll, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 56.dp)) {
            Spacer(Modifier.weight(1f))
            Symbol("ui-map", size = 20.dp)
            Text(stringResource(R.string.brands_all_on_map, chain.branches.size), style = Atlas.type.name)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/**
 * A branch: a green (open) or grey dot, its name or street and level, "→ 21:00" or "opens 10:00", and the walk
 * minutes. [now] null reads the clock itself.
 */
@Composable
internal fun BranchRow(place: Place, here: LatLon, now: LocalDateTime?, onClick: () -> Unit) {
    val services = LocalServices.current
    val c = Atlas.colors
    val lang = remember { Locale.getDefault().language }
    val t = now ?: rememberNow(place)
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val line = remember(place, here, t) {
        PlaceLine.of(place, here, lang, t, open = OpeningHours.parseOrNull(place.tags["opening_hours"])?.status(t, ctx))
    }
    val street = listOfNotNull(place.tags["addr:street"], place.tags["addr:housenumber"]).joinToString(" ").ifEmpty { null }
    val title = if (place.tags["brand"] != null || place.tags["brand:wikidata"] != null) {
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(if (line.tone == Tone.OPEN || line.tone == Tone.SOON) toneColour(line.tone) else c.inkFaint, size = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = Atlas.type.name, color = if (closed) c.inkSoft else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (title != line.summary.name && place.tags["brand"] == null) line.summary.kind?.let { Text(it, style = Atlas.type.caption, color = c.inkSoft, maxLines = 1) }
        }
        status?.let { Text(it, style = Atlas.type.label, color = if (line.tone == Tone.SOON) c.caution else c.inkSoft, maxLines = 1) }
        line.trailing?.let { Text(it, style = Atlas.type.numeralSmall, color = if (closed) c.inkSoft else c.ink) }
    }
}

/** [places] as branch rows, nearest first as given, divided by hairlines. */
@Composable
internal fun BranchRows(places: List<Place>, here: LatLon, onPlace: (Place) -> Unit) {
    val now = rememberNow(places)
    places.forEachIndexed { i, p ->
        BranchRow(p, here, now) { onPlace(p) }
        if (i < places.lastIndex) Hairline()
    }
}

/** The name a brand trades under ("Jumbo"), without the index's region ("Jumbo (Europe)"). */
internal fun brandName(brand: Brand): String = brand.brand.ifBlank { brand.name }

/** Sign colours for brands whose own is not known, picked by the brand's id so each keeps its colour. */
private val MONOGRAM = longArrayOf(0xFF2F5D8A, 0xFFC8322D, 0xFF2F7D3A, 0xFFE8962C, 0xFF7B4FD6, 0xFF0E8C8C, 0xFF8A5A2B, 0xFFD2457F)

/** A brand's logo on its colour, or its initial on a colour of its own when it has no logo. */
@Composable
internal fun BrandLogo(brand: Brand, size: Dp) {
    val bg = brand.colour?.let { Color(it) } ?: Color(MONOGRAM[Math.floorMod(brand.id.hashCode(), MONOGRAM.size)])
    val fg = brand.textColour?.let { Color(it) } ?: contentColourFor(bg)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(bg), contentAlignment = Alignment.Center) {
        val slug = brand.logoSlug
        if (slug != null) Symbol("logo-$slug", tint = fg, size = size * 0.62f)
        else Text(brandName(brand).take(1).uppercase(), style = Atlas.type.numeralSmall, color = fg)
    }
}
