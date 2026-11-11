package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.places.Brand
import app.atlas.places.BrandSite
import app.atlas.places.CategoryTree
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.Facts
import app.atlas.places.Localise
import app.atlas.providers.WikidataEntity
import app.atlas.search.CategoryResults
import app.atlas.search.Facets
import app.atlas.search.Hit
import app.atlas.search.HoursAnswer
import app.atlas.search.HoursBook
import app.atlas.search.Overview
import app.atlas.search.SearchItem
import app.atlas.ui.kit.BrandSigns
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/*
 * A brand's page, the search's chain in the brand's own colours (decision 12; the owner, 6 November: "Multiple layouts,
 * themed brand detail overview page that appears when you clearly search for a specific brand and accessible from the
 * brands menu", then "details about the brand, everything thats useful"): its band at the sheet's top, what is known of
 * it in a row of tiles under it, its branches near as the search lists a kind's places (rows or cards, the order and
 * the filters, its signs on the map), the other chains of its kind around, and the way to hide it.
 */

// ---------------------------------------------------------------------------------------------------------------- band

/**
 * A brand's band, its sign grown out to the sheet's edges: its colour (the neutral sign's where it is not known, never
 * one made up), its logo on it as on its fascia, its name, what it is (as Wikidata says, else its kind), how many of
 * its branches are near, how many open and the minutes to the nearest; the way back at one end and the way its
 * branches are shown, as rows or cards, at the other. It stays the sheet's top while they scroll under it.
 */
@Composable
internal fun BrandBand(
    brand: Brand,
    results: CategoryResults?,
    wiki: WikidataEntity?,
    cards: Boolean,
    lang: String,
    onBack: () -> Unit,
    onCards: (Boolean) -> Unit,
    starred: Boolean,
    onStar: (Boolean) -> Unit,
) {
    val c = indexColors()
    val sign = BrandSigns.colour(brand)
    val ink = BrandSigns.ink(brand)
    val kind = remember(brand.id) { CategoryTree.of(brand.keyTags) }
    val what = wiki?.description(listOf(lang, "en"))?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: kind?.name(lang)
    // a sign as dark as the dark sheet's paper would melt into it: a hairline under it there
    val melts = c.dark && sign.luminance() < MELTS
    Row(
        Modifier
            .fillMaxWidth()
            .background(signBrush(sign))
            .then(if (melts) Modifier.drawBehind { drawLine(EDGE_ON_DARK, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) } else Modifier)
            .padding(start = 4.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandBack(stringResource(R.string.find_up), onBack, tint = ink)
        Fascia(brand, ink, FASCIA)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(brandName(brand), Modifier.semantics { heading() }, style = indexTitle(21f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            what?.let { Text(it, style = INDEX_META, color = ink.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (results != null) Figures(results, ink, Modifier.padding(top = 4.dp), size = 14f)
        }
        RowStar(starred, onStar, tint = ink)
        ViewSwitch(cards, ink, sign, onCards)
    }
}

/** A brand's logo on its own colour as on its fascia, in a rim of its lettering: its glyph, else its initial. */
@Composable
internal fun Fascia(brand: Brand, ink: Color, size: Dp) {
    val corner = RoundedCornerShape(cornerOf(size))
    Box(Modifier.border(1.5.dp, ink.copy(alpha = 0.34f), corner)) { BrandLogo(brand, size, corner = cornerOf(size), logo = size * 0.6f) }
}

/** A brand's colour as light falls on it, lit a little at the top left: its own colour still, not a deeper one. */
private fun signBrush(sign: Color): Brush =
    Brush.linearGradient(listOf(lerp(sign, Color.White, 0.07f), sign, lerp(sign, Color.Black, 0.1f)), start = Offset.Zero, end = Offset.Infinite)

/**
 * A chain the typed words name outright ("albert heijn", "hema"), leading their answer as its page's band: its colour,
 * its logo, its name, its kind, how many of its branches are near; a press opens its page, as the search key does.
 */
@Composable
internal fun NamedBrand(item: SearchItem.BrandResult, lang: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = indexColors()
    val brand = item.brand
    val sign = BrandSigns.colour(brand)
    val ink = BrandSigns.ink(brand)
    val kind = remember(brand.id) { CategoryTree.of(brand.keyTags)?.name(lang) }
    val shape = RoundedCornerShape(24.dp)
    val said = stringResource(R.string.search_brand_all, item.count, brandName(brand))
    Row(
        modifier
            .fillMaxWidth()
            .cssShadow(shape, y = 3.dp, blur = 12.dp, colour = c.shadow)
            .clip(shape)
            .background(signBrush(sign))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Fascia(brand, ink, FASCIA)
        Column(Modifier.weight(1f)) {
            Text(brandName(brand), style = indexTitle(20f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            kind?.let { Text(it, style = INDEX_META, color = ink.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Figure(TellFigure(item.count.toString(), stringResource(R.string.find_word_near)), ink, 20f)
        Symbol("ui-arrow-right", tint = ink, size = 18.dp)
    }
}

// ------------------------------------------------------------------------------------------------------------- details

/**
 * What is known of a brand, a tile each in one row under its band, read at a glance and slid along for more: today's
 * hours across its branches near (a press shows the week's), what every branch of it is (its cuisine, takeaway,
 * organic only), where it trades, since when, what it is part of, where its head office is, its website and its
 * Wikipedia article, read in the app. Its branches' hours and the brand index's facts are on the phone; Wikidata's
 * come once read online and are kept ([rememberBrandWiki]); a fact no source gives has no tile.
 */
@Composable
internal fun BrandDetails(
    brand: Brand,
    wiki: WikidataEntity?,
    results: CategoryResults?,
    hours: HoursAnswer?,
    week: Boolean,
    now: LocalDateTime,
    lang: String,
    onWeek: () -> Unit,
) {
    val services = LocalServices.current
    val uri = LocalUriHandler.current
    val langs = listOf(lang, "en").distinct()
    var reading by remember { mutableStateOf(false) }
    // its branches' own websites say the brand's first ([BrandSite]), its Wikidata item's after
    val site = remember(results, wiki) {
        val tags = results?.hits?.map { it.record.tags }.orEmpty()
        webUrl(BrandSite.of(tags, tags.firstNotNullOfOrNull { it["addr:country"] }) ?: wiki?.website)
    }
    val article = wiki?.let { e -> langs.firstNotNullOfOrNull { l -> e.sitelinks["${l}wiki"]?.let { l to it } } }
    val every = everyBranch(brand, lang)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hours?.takeIf { it.known > 0 }?.let { h -> TodayTile(h, now, week, onWeek) }
        for (f in every) DetailTile(f.symbol, f.caption, f.value)
        tradesIn(brand)?.let { DetailTile("ui-globe", stringResource(R.string.brand_trades), it) }
        wiki?.inception?.let { DetailTile("ui-history", stringResource(R.string.brand_since), it.year.toString()) }
        wiki?.parents?.firstNotNullOfOrNull { it.label(langs) }?.let { DetailTile("ui-briefcase", stringResource(R.string.brand_part_of), it) }
        wiki?.headquarters?.firstNotNullOfOrNull { it.label(langs) }?.let { DetailTile("ui-building", stringResource(R.string.brand_head_office), it) }
        site?.let { url -> DetailTile("ui-web", stringResource(R.string.brand_website), hostOf(url), onClick = { runCatching { uri.openUri(url) } }) }
        article?.let { DetailTile("ui-book", stringResource(R.string.brand_wikipedia), stringResource(R.string.place_wiki_open), onClick = { reading = true }) }
    }
    val (l, title) = article ?: return
    if (reading) {
        // read here, in the app; offline, as read before; the reader leads on to Wikipedia itself
        val online = !services.config.offline && services.connectivity.online.value
        WikiReader("https://$l.wikipedia.org/wiki/" + title.replace(' ', '_'), load = { if (online) services.wikipedia.article(l, title) else services.wikipedia.cached(l, title) }) { reading = false }
    }
}

/**
 * Today across a brand's branches near: when most of them open and close (the middle opening to the middle closing),
 * and the last to close where one stays open later; closed today where none opens. A press shows the week's hours.
 */
@Composable
private fun TodayTile(h: HoursAnswer, now: LocalDateTime, week: Boolean, onWeek: () -> Unit) {
    val t = h.days.firstOrNull { it.day == now.dayOfWeek } ?: return
    val first = t.opensMiddle ?: t.earliest
    val last = t.closesMiddle ?: t.latest
    val value = if (first == null || last == null) stringResource(R.string.brand_today_closed) else "${hm(first)}–${hm(last)}"
    val later = t.latest?.takeIf { l -> last != null && l > last }?.let { stringResource(R.string.brand_today_latest, hm(it)) }
    DetailTile(
        "ui-clock", stringResource(R.string.brand_today), value, later, onClick = onWeek,
        label = stringResource(if (week) R.string.brand_week_hide else R.string.brand_week_show), unfolded = week,
    )
}

/**
 * One thing known of a brand, a tile raised off the paper: its symbol and [caption] in small capitals, [value] read
 * first, [sub] under it. One that leads somewhere (its website, its article) says so with an arrow at its caption, one
 * that unfolds something ([unfolded]) with a chevron.
 */
@Composable
private fun DetailTile(
    symbol: String,
    caption: String,
    value: String,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    label: String? = null,
    unfolded: Boolean? = null,
) {
    val c = indexColors()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .height(TILE)
            .widthIn(min = TILE_MIN, max = TILE_MAX)
            .raised(shape, c, y = 1.dp, blur = 4.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Symbol(symbol, tint = c.soft, size = 13.dp)
            Caps(caption, Modifier.weight(1f, fill = false))
            when {
                unfolded != null -> Symbol("ui.chevron-down", Modifier.rotate(if (unfolded) 180f else 0f), tint = c.faint, size = 11.dp)
                onClick != null -> Symbol("ui-arrow-up-right", tint = c.faint, size = 11.dp)
            }
        }
        Text(value, style = boardText(15f, 650, 19f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = INDEX_META, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A fact of every branch of a brand, as its tile says it. */
private class BranchFact(val symbol: String, val caption: String, val value: String)

/**
 * What the brand index says of every branch of [brand] (its own tags: a burger chain's cuisine, takeaway, an organic
 * shop's "only"), its cuisines in one tile: the facts its branches' own tags then need not say.
 */
@Composable
private fun everyBranch(brand: Brand, lang: String): List<BranchFact> {
    val words = mapOf(
        FactState.YES to stringResource(R.string.place_fact_yes), FactState.NO to stringResource(R.string.place_fact_no),
        FactState.LIMITED to stringResource(R.string.place_fact_limited), FactState.ONLY to stringResource(R.string.place_fact_only),
    )
    val facts = remember(brand.id, lang) { Facts.of(brand.otherTags + brand.keyTags, lang).filter { it.mapped && it.kind in BRANCH_FACTS && it.state != FactState.UNKNOWN } }
    return remember(facts, words) {
        val cuisines = facts.filter { it.kind == FactKind.CUISINE }
        buildList {
            if (cuisines.isNotEmpty()) add(BranchFact(cuisines.first().symbol, Facets.label("cuisine", lang), cuisines.mapNotNull { it.label }.joinToString(", ")))
            for (f in facts.filter { it.kind != FactKind.CUISINE }.take(3)) {
                add(BranchFact(f.symbol, f.label ?: f.key, words[f.state] ?: continue))
            }
        }
    }
}

/** The facts a brand's own tags say of every branch worth a tile: what it serves and how. */
private val BRANCH_FACTS = setOf(
    FactKind.CUISINE, FactKind.DIET, FactKind.TAKEAWAY, FactKind.DELIVERY, FactKind.DRIVE_THROUGH, FactKind.RESERVATION,
    FactKind.ORGANIC, FactKind.SECOND_HAND, FactKind.SELF_SERVICE,
)

/** Where [brand] trades: one or two countries by name, more by how many, everywhere as everywhere; null where not known. */
@Composable
private fun tradesIn(brand: Brand): String? {
    val countries = brand.countries - brand.excludedCountries
    return when {
        countries.isEmpty() -> if (brand.spread >= EVERYWHERE) stringResource(R.string.brand_worldwide) else null
        countries.size <= 2 -> countries.map { countryName(it) }.sorted().joinToString(", ")
        else -> pluralStringResource(R.plurals.brand_countries, countries.size, countries.size)
    }
}

/** A country by its ISO code ("nl"), named in the phone's language ("Netherlands"); the code where the phone knows no name. */
private fun countryName(code: String): String {
    val name = runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()) }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: code.uppercase(Locale.ROOT)
}

/**
 * A branch as its brand's page names it, where every row is of the brand: by its own name where that says more than the
 * brand's ("AH to go", "Albert Heijn XL"), else by its street and number ("Rozengracht 61"); null where neither is
 * mapped (its name then, as anywhere).
 */
internal fun branchName(p: Place, brand: String, lang: String): String? {
    val own = Localise.name(p.tags, lang)?.trim()
    if (!own.isNullOrEmpty() && !own.equals(brand, ignoreCase = true)) return own
    val street = p.tags["addr:street"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return listOfNotNull(street, p.tags["addr:housenumber"]?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
}

/** A website as read in a line: its host, without the scheme or "www." ("ah.nl"). */
internal fun hostOf(url: String): String = url.substringAfter("://").removePrefix("www.").substringBefore('/').substringBefore('?')

// --------------------------------------------------------------------------------------------------------- what is read

/**
 * The week of [results]' branches' hours ([Overview.hours], as typed words asking for them read them), a branch that
 * maps none taking its siblings' among them ([HoursBook]); worked out off the main thread, again as they change.
 */
@Composable
internal fun rememberChainHours(results: CategoryResults?, at: LatLon): HoursAnswer? {
    val services = LocalServices.current
    return produceState<HoursAnswer?>(null, results, at) {
        val r = results ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val now = ZonedDateTime.now(services.clock)
            val hits = r.hits.map { Hit(it.record, 1.0, 1.0, it.metres, it.open) }
            runCatching { Overview.hours(hits, now, HereContext.hours(services, at, now.zone), HoursBook(r.hits.map { it.record })) }.getOrNull()
        }
    }.value
}

/** [brand]'s Wikidata item ([brandWiki]); null while it is read, where none is known, or for no brand. */
@Composable
internal fun rememberBrandWiki(brand: Brand?, lang: String): WikidataEntity? {
    val services = LocalServices.current
    return produceState<WikidataEntity?>(null, brand?.wikidata, lang) {
        val qid = brand?.wikidata ?: return@produceState
        value = withContext(Dispatchers.IO) { brandWiki(services, qid, listOf(lang, "en").distinct()) }
    }.value
}

/**
 * A brand's Wikidata item ([qid]): as this Atlas kept it on the phone; else asked online, where the phone may go, and
 * kept; else as an Atlas that read fewer of its facts kept it (a branch's page keeps its brand's item too).
 */
internal suspend fun brandWiki(services: Services, qid: String, langs: List<String>): WikidataEntity? {
    val kept = KeptFacts(File(services.dirs.files, "search/wikidata"))
    kept.read(qid, KeptFacts.VERSION)?.let { return it }
    val online = !services.config.offline && services.connectivity.online.value
    val fresh = if (!online) null else try {
        services.wikidata.entity(qid, langs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    fresh?.let { runCatching { kept.keep(it) } }
    return fresh ?: kept.read(qid)
}

// ------------------------------------------------------------------------------------------------------ its kind, hiding

/**
 * The other chains of a brand's kind around, as the search's shelf stacks them ([SearchSheetState.brands]): the
 * supermarkets beside a supermarket, the burger chains beside a burger chain, each a press from its own page; back
 * comes back to this one.
 */
@Composable
internal fun AlsoNear(brand: Brand, stacks: List<BrandStack>, onChain: (Brand) -> Unit) {
    val kin = remember(brand.id, stacks) { kinOf(brand, stacks) }
    if (kin.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Caps(stringResource(R.string.brand_also_near), Modifier.padding(start = 22.dp, bottom = 2.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (ch in kin) ChainSign(ch, KIN_SIGN, { onChain(ch.brand) }, Modifier.width(KIN_CELL))
        }
    }
}

/** The chains on [brand]'s stack but itself, else on the stack of its kind; none where it has no kind around. */
internal fun kinOf(brand: Brand, stacks: List<BrandStack>): List<BrandBranches> {
    val stack = stacks.firstOrNull { s -> s.chains.any { it.brand.id == brand.id } }
        ?: CategoryTree.of(brand.keyTags)?.id?.let { kind -> stacks.firstOrNull { it.kind?.id == kind } }
    return stack?.chains.orEmpty().filter { it.brand.id != brand.id }
}

/** Hides the brand and its pins, or shows it again, as the Brands board's hidden tile does. */
@Composable
internal fun HideBrand(brand: Brand) {
    val services = LocalServices.current
    val c = indexColors()
    val hidden by services.saved.hiddenBrands.collectAsStateWithLifecycle()
    val isHidden = brand.id in hidden
    Row(
        Modifier
            .padding(start = 12.dp, top = 16.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.hideBrand(brand.id, !isHidden) } } }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(if (isHidden) "ui.eye" else "ui.hide", tint = c.soft, size = 16.dp)
        Text(stringResource(if (isHidden) R.string.brands_show else R.string.brands_hide, brandName(brand)), style = INDEX_META, color = c.soft, maxLines = 1)
    }
}

/** The fascia's side, on the band and on a named brand's card. */
private val FASCIA = 46.dp

/** A detail's tile: its height, and how narrow and how wide it may be. */
private val TILE = 76.dp
private val TILE_MIN = 96.dp
private val TILE_MAX = 184.dp

/** The chains of its kind: each sign's side and its cell. */
private val KIN_SIGN = 48.dp
private val KIN_CELL = KIN_SIGN + 18.dp

/** A sign darker than this melts into the dark sheet's paper; its band then has a hairline under it. */
private const val MELTS = 0.05f
private val EDGE_ON_DARK = Color.White.copy(alpha = 0.16f)

/** The spread of a brand that trades everywhere ([Brand.spread]). */
private const val EVERYWHERE = 250
