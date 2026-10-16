package app.atlas.ui.shop

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.util.Locale


/** The sign colour of each shelf's aisle, from the thing standing on it in the pantry. */
internal fun Shelf.colour(): Color = when (this) {
    Shelf.SUPER -> Color(0xFFE8622C)
    Shelf.CORNER -> Color(0xFF2E5BFF)
    Shelf.FRESH -> Color(0xFF6BB04A)
    Shelf.BAKERY -> Color(0xFFC79A5B)
    Shelf.MEAT_FISH -> Color(0xFFB03A3A)
    Shelf.DELI -> Color(0xFFD9A82B)
    Shelf.WORLD -> Color(0xFF1B6F8A)
    Shelf.REFILL -> Color(0xFF8C6A3E)
    Shelf.DRINKS -> Color(0xFF2F4A2A)
    Shelf.FARM -> Color(0xFFC98E5A)
    Shelf.LATE -> Color(0xFFE0356A)
    Shelf.SHARE -> Color(0xFFE8622C)
}

/** The glyph for a kind of shop: the boards' coloured grocery symbols where they have one. */
internal fun shelfGlyph(shelf: Shelf): String = when (shelf) {
    Shelf.SUPER, Shelf.CORNER, Shelf.LATE -> "grocery.supermarket"
    Shelf.FRESH, Shelf.FARM -> "grocery.produce"
    Shelf.BAKERY -> "food.bread"
    Shelf.MEAT_FISH -> "shop.cart"
    Shelf.DELI -> "grocery.cheese"
    Shelf.WORLD -> "grocery.world-foods"
    Shelf.REFILL -> "refill.jar"
    Shelf.DRINKS -> "food.wine"
    Shelf.SHARE -> "place.fridge"
}

/** The aisle of a pantry shelf. */
internal fun Shelf.spec(): AisleSpec = ShelfArts.getValue(this).let { art ->
    AisleSpec(id, art.name, Shelf.entries.indexOf(this) + 1, colour(), shelfGlyph(this)) { area, at -> PantryCounts.placesOf(this, area, at) }
}

/**
 * One aisle, as on the Aisles board: the blue hanging sign (the shelf's number, its name, how many are open and how many
 * brands), the chains as logo chips with counts, toggles (Sunday, self-checkout, delivery, parking, step-free), the four
 * sorts and a shelf-edge label per shop with a brand-colour strip, its closing time, footprint and rating, and a huge walk
 * numeral. Cheapest appears only when Open Prices (or your notes) have prices for the list.
 */
@Composable
internal fun AislesScreen(spec: AisleSpec, nav: Navigator, indie: Boolean = false) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val area by produceState(ShopArea.empty(here), here) { repo.area(if (spec.id.startsWith("street:")) "street" else "pantry", if (spec.id.startsWith("street:")) Family.all else Shelf.all, here).collect { value = it } }
    var brand by rememberSaveable { mutableStateOf<String?>(null) }
    var filters by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var sort by rememberSaveable { mutableStateOf(AisleSort.NEAREST.name) }
    val ratings by produceState(emptyMap<String, app.atlas.providers.Rating>(), area.places.size, area.origin) { value = repo.ratings(area) }
    val list by repo.list.items.collectAsStateWithLifecycle()
    val notes by repo.priceNotes.items.collectAsStateWithLifecycle()
    // What the shopping list costs at each shop, when Open Prices or your notes have prices for it.
    val baskets by produceState(emptyMap<String, Double>(), list, notes, here) {
        val data = repo.priceData(list, here)
        val index = PriceIndex.of(list, data, notes)
        value = index.stores.keys.mapNotNull { k -> index.completeTotal(list.filterNot { it.atHome }, k)?.let { k to it } }.toMap()
    }
    val cheapestKey = baskets.minByOrNull { it.value }?.key
    val canSortCheap = baskets.isNotEmpty()
    val picked = AisleSort.valueOf(sort).takeIf { it != AisleSort.CHEAPEST || canSortCheap } ?: AisleSort.NEAREST
    val chosen = filters.mapNotNull { n -> AisleFilter.entries.firstOrNull { it.name == n } }.toSet()
    val view = remember(area, now, brand, chosen, picked, baskets, indie) { Aisles.view(spec, area, now, brand, chosen, picked, baskets, indie) }

    Box(Modifier.fillMaxSize().background(argb(0xFFF4F5F3))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 32.dp + boardLift())) {
            val brandsN = view.brands.count { !it.independent && it.count >= 2 }
            val sub = when {
                area.waiting -> null
                !view.openCount.open -> stringResource(R.string.shop_places_n, view.openCount.value)
                brandsN < 2 -> stringResource(R.string.shop_open_n, view.openCount.value)
                else -> stringResource(R.string.shop_open_brands, view.openCount.value, brandsN)
            }
            Ceiling(spec.number, stringResource(spec.name), sub, nav)
            Spacer(Modifier.height(24.dp))
            if (view.brands.size > 1 || (view.brands.size == 1 && !view.brands[0].independent)) {
                BrandRow(view.brands, brand) { k -> brand = if (brand == k) null else k }
                Spacer(Modifier.height(18.dp))
            }
            Toggles(view, chosen) { f -> filters = if (f.name in filters) filters - f.name else filters + f.name }
            Spacer(Modifier.height(16.dp))
            SortTabs(picked, canSortCheap) { sort = it.name }
            Spacer(Modifier.height(17.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    area.waiting -> Line(stringResource(R.string.shop_loading), bt(14f, 400, 18f), GREY)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    view.all.isEmpty() -> StateMessage(spec.glyph, stringResource(R.string.shop_aisle_empty))
                    view.shown.isEmpty() -> StateMessage("ui.filter", stringResource(R.string.shop_aisle_none_match))
                    else -> for (p in view.shown.take(40)) {
                        ShelfLabel(p, spec, area, now, ratings[p.key], isCheapest = p.key == cheapestKey && picked == AisleSort.CHEAPEST) { nav.go(Dest.Place.of(p.place).also { services.remember(p.place) }) }
                    }
                }
            }
        }
    }
}

private const val SOFT: Long = 0xFF464C5A
private const val GREY: Long = 0xFF676D7C
private const val RING: Long = 0xFFE3E5E8

/** The strip lights' glow: `0 0 14px` white at 95 % and `0 6px 20px` white at 80 %. */
private val LightGlow = listOf(CssShadow(0f, 0f, 14f, 0f, 0xF2FFFFFF), CssShadow(0f, 6f, 20f, 0f, 0xCCFFFFFF))

/** The ceiling (40 high, two strip lights), the two rods at x 110 and 300, the sign at y 78 and the two side buttons. */
@Composable
private fun Ceiling(number: Int, name: String, sub: String?, nav: Navigator) {
    Box(Modifier.fillMaxWidth().height(162.dp)) {
        Box(Modifier.at(0f, 0f, 412f, 40f).background(argb(0xFFDCE0E3)))
        Box(Modifier.at(43f, 15f, 120f, 10f).boardBox(0xFFFFFFFF, round(5f), LightGlow, clip = false))
        Box(Modifier.at(249f, 15f, 120f, 10f).boardBox(0xFFFFFFFF, round(5f), LightGlow, clip = false))
        Box(Modifier.at(110f, 40f, 2f, 40f).background(argb(0xFF9AA0A8)))
        Box(Modifier.at(300f, 40f, 2f, 40f).background(argb(0xFF9AA0A8)))
        Sign(number, name, sub)
        Box(
            Modifier.at(14f, 94f, 34f, 52f).boardBox(0xE6FFFFFF, round(17f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x1A000000))).tap(stringResource(R.string.shop_back_pantry)) { nav.back() },
            contentAlignment = Alignment.Center,
        ) { Sym("ui.back", 18f, 18f) }
        Box(
            Modifier.at(364f, 94f, 34f, 52f).boardBox(0xE6FFFFFF, round(17f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x1A000000))).tap(stringResource(R.string.shop_list_open)) { nav.go(Dest.Groceries("list")) },
            contentAlignment = Alignment.Center,
        ) { Sym("ui.list", 18f, 18f) }
    }
}

/**
 * The blue enamel sign (336 × 84 at x 56, y 78): a 3 px dark rim and a 2 px light one inside it, the number in a 52 px
 * white disc and the name in Overpass, 12 px apart, 18 px in from the sides.
 */
@Composable
private fun Sign(number: Int, name: String, sub: String?) {
    Row(
        Modifier.at(56f, 78f, 336f, 84f).boardBox(
            r = round(10f),
            shadows = listOf(CssShadow(0f, 10f, 18f, 0f, 0x40142846), CssShadow(0f, 0f, 0f, 3f, 0xFF1A5286, inset = true), CssShadow(0f, 0f, 0f, 5f, 0xFF6FA2D2, inset = true)),
            grad = listOf(cssLinear(180f, 0f to 0xFF2A6FB0, 1f to 0xFF1F5F99)),
        ).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).boardBox(0xFFFFFFFF, round(26f)), contentAlignment = Alignment.Center) {
            Line(number.toString(), bt(30f, 900, 38f, OVERPASS), 0xFF1F5F99)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Block(name, bt(25f, 800, 25f, OVERPASS, spacing = 0.25f), 0xFFFFFFFF, Modifier.semantics { heading() })
            if (sub != null) Line(sub, bt(13f, 400, 17f, tnum = true), 0xFFCFE0F2)
        }
    }
}

/** The chains as 52 high chips, 8 apart from x 16: a 40 px logo, the name and the count; ink when picked, else white with a 1 px ring. */
@Composable
private fun BrandRow(brands: List<BrandChip>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in brands) {
            val on = selected == b.key
            val name = if (b.independent) stringResource(R.string.shop_independent) else b.name
            Row(
                Modifier.height(52.dp)
                    .boardBox(if (on) INK else 0xFFFFFFFF, round(26f), if (on) emptyList() else listOf(CssShadow(0f, 0f, 0f, 1f, RING)))
                    .tap(name) { onPick(b.key) }
                    .semantics { this.selected = on }
                    .padding(start = 6.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (b.independent) {
                    Box(Modifier.size(40.dp).boardBox(0xFFE9E3D8, round(20f)), contentAlignment = Alignment.Center) { Sym("fact.independent", 22f, 22f) }
                } else {
                    ShopLogo(b.brand, b.name, 40.dp, key = b.key, round = b.brand?.logoSlug == null)
                }
                Line("$name ${b.count}", bt(14f, 600, 18f), if (on) 0xFFFFFFFF else INK)
            }
        }
    }
}

/** The toggles, 8 apart: Sunday as a 40 high pill with its word, the others as 40 px circles; blue when on, else white with a ring. */
@Composable
private fun Toggles(view: AisleView, on: Set<AisleFilter>, onToggle: (AisleFilter) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in AisleFilter.entries) {
            val selected = f in on
            val dim = view.filterCounts[f] == 0 && !selected
            val label = stringResource(
                when (f) {
                    AisleFilter.SUNDAY -> R.string.shop_f_sunday_d
                    AisleFilter.SELF_CHECKOUT -> R.string.shop_f_selfcheckout
                    AisleFilter.DELIVERY -> R.string.shop_f_delivery
                    AisleFilter.PARKING -> R.string.shop_f_parking
                    AisleFilter.STEP_FREE -> R.string.shop_f_stepfree
                },
            )
            val fg = if (selected) 0xFF1F3FC7 else SOFT
            val wide = f == AisleFilter.SUNDAY
            Row(
                Modifier.height(40.dp).then(if (wide) Modifier else Modifier.width(40.dp)).alpha(if (dim) 0.45f else 1f)
                    .boardBox(if (selected) 0xFFE4EAFF else 0xFFFFFFFF, round(20f), if (selected) emptyList() else listOf(CssShadow(0f, 0f, 0f, 1f, RING)))
                    .tap(label) { onToggle(f) }
                    .semantics { this.selected = selected }
                    .then(if (wide) Modifier.padding(start = 10.dp, end = 12.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (f) {
                    AisleFilter.SUNDAY -> {
                        Sym("ui.calendar", 16f, 16f, fg)
                        Line(stringResource(R.string.shop_f_sunday), bt(13f, 700, 17f), fg)
                    }
                    AisleFilter.SELF_CHECKOUT -> Sym("shop.self-checkout", 18f, 18f, fg)
                    AisleFilter.DELIVERY -> Sym("fact.delivery", 18f, 18f, fg)
                    AisleFilter.PARKING -> Line("P", bt(15f, 800, 19.5f), fg)
                    AisleFilter.STEP_FREE -> Sym("fact.wheelchair", 18f, 18f, fg)
                }
            }
        }
    }
}

/** The sorts, 16 apart (14 semibold grey); the chosen one ink, underlined 2 px with 3 px under the words. */
@Composable
private fun SortTabs(selected: AisleSort, cheapest: Boolean, onPick: (AisleSort) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (s in AisleSort.entries) {
            if (s == AisleSort.CHEAPEST && !cheapest) continue
            val on = s == selected
            val label = stringResource(
                when (s) {
                    AisleSort.NEAREST -> R.string.shop_sort_nearest
                    AisleSort.BIGGEST -> R.string.shop_sort_biggest
                    AisleSort.OPEN_LATEST -> R.string.shop_sort_latest
                    AisleSort.CHEAPEST -> R.string.shop_sort_cheapest
                },
            )
            Line(
                label, bt(14f, 600, 18f), if (on) INK else GREY,
                Modifier.tap(label) { onPick(s) }.semantics { this.selected = on }
                    .then(if (on) Modifier.boardBox(border = CssBorder(0f, 0f, 2f, 0f, longArrayOf(INK, INK, INK, INK)), clip = false) else Modifier)
                    .padding(bottom = 5.dp),
            )
        }
    }
}

/**
 * A shelf-edge label, as the board's (112 high, 6 px corners, a 2 px grey foot and a soft shadow): the brand-colour
 * strip, the shop's mark, its name, closing time, footprint and stars, the barcode, and the walk in big figures.
 */
@Composable
private fun ShelfLabel(p: ShopPlace, spec: AisleSpec, area: ShopArea, now: java.time.LocalDateTime, rating: app.atlas.providers.Rating?, isCheapest: Boolean, onClick: () -> Unit) {
    val closed = p.openness(now, area.hours) == Open.CLOSED
    val colour = p.brand?.let { brandColour(it, null) } ?: p.tags["brand"]?.let { brandColour(null, it) } ?: spec.colour
    val strip = if (isCheapest) Color(0xFFF28C28) else colour
    val ink = if (isCheapest) 0xFF3A3520 else SOFT
    Column(
        Modifier.fillMaxWidth().height(112.dp).alpha(if (closed) 0.6f else 1f)
            .boardBox(
                if (isCheapest) 0xFFFFE45C else 0xFFFFFFFF, round(6f),
                listOf(CssShadow(0f, 2f, 0f, 0f, if (isCheapest) 0xFFD8BE2E else 0xFFD5D8DC), CssShadow(0f, 6f, 14f, 0f, 0x1414161B)),
            )
            .tap(p.name, onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(strip))
        Row(Modifier.weight(1f).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.brand != null || p.tags["brand"] != null) {
                ShopLogo(p.brand, p.tags["brand"] ?: p.name, 44.dp, key = p.tags["brand"], round = p.brand?.logoSlug == null)
            } else {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(spec.colour.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Sym(spec.glyph, 24f, 24f, hue(spec.glyph) ?: (spec.colour.toArgb().toLong() and 0xFFFFFFFFL))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Line(p.name, bt(17f, 700, 22f), INK)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val details = bt(12f, 400, 15.5f, tnum = true)
                    openLine(p, area, now)?.let { (t, c) -> Line(t, details, if (isCheapest) ink else (c.toArgb().toLong() and 0xFFFFFFFFL)) }
                    p.footprintM2?.let { Line(stringResource(R.string.shop_footprint, "%,d".format(Locale.getDefault(), it)), details, ink) }
                    rating?.stars?.let { Line("★ " + "%.1f".format(Locale.getDefault(), it), details, ink) }
                    if (isCheapest) Line(stringResource(R.string.shop_cheapest_basket), bt(12f, 700, 15.5f, tnum = true), ink)
                }
                if (!isCheapest) BoardArt(if (Math.floorMod(p.key.hashCode(), 2) == 0) ShopBoards.LABEL_BARCODE_1 else ShopBoards.LABEL_BARCODE_2, tint = argb(INK))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    buildAnnotatedString {
                        append(p.walkMinutes.toString())
                        withStyle(SpanStyle(fontSize = 22.sp)) { append("′") }
                    },
                    style = bt(38f, 800, 38f, spacing = -0.76f, tnum = true),
                    color = argb(INK),
                    maxLines = 1,
                )
                Line(stringResource(R.string.shop_walk), bt(11f, 400, 11f), if (isCheapest) ink else GREY)
            }
        }
    }
}
