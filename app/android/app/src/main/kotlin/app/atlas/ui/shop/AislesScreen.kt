package app.atlas.ui.shop

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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.util.Locale

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF464C5A)
private val Grey = Color(0xFF676D7C)
private val RingGrey = Color(0xFFE3E5E8)

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

    Box(Modifier.fillMaxSize().background(Color(0xFFF4F5F3))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
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
            Spacer(Modifier.height(18.dp))
            SortTabs(picked, canSortCheap) { sort = it.name }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    area.waiting -> Text(stringResource(R.string.shop_loading), style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp), color = Grey)
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

@Composable
private fun Ceiling(number: Int, name: String, sub: String?, nav: Navigator) {
    Box(Modifier.fillMaxWidth()) {
        // The ceiling with its two strip lights, and the two rods the sign hangs from.
        Box(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFFDCE0E3)).windowInsetsPadding(WindowInsets.statusBars)) {}
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            repeat(2) {
                Box(
                    Modifier.width(120.dp).height(10.dp)
                        .cssShadow(RoundedCornerShape(5.dp), y = 6.dp, blur = 20.dp, colour = Color(0xCCFFFFFF))
                        .background(Color.White, RoundedCornerShape(5.dp)),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(300.dp).height(84.dp + 38.dp)) {
                Box(Modifier.padding(start = 54.dp).width(2.dp).height(40.dp).background(Color(0xFF9AA0A8)))
                Box(Modifier.padding(start = 244.dp).width(2.dp).height(40.dp).background(Color(0xFF9AA0A8)))
                Sign(number, name, sub, Modifier.padding(top = 38.dp))
            }
        }
        // Back to the pantry and to the list, either side of the sign.
        Box(
            Modifier.padding(start = 14.dp, top = 94.dp).size(34.dp, 52.dp)
                .cssShadow(RoundedCornerShape(17.dp), y = 2.dp, blur = 8.dp, colour = Color(0x1A000000))
                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(17.dp))
                .tap(stringResource(R.string.shop_back_pantry)) { nav.back() },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.back", tint = Ink, size = 18.dp, weight = 1.1f) }
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 94.dp).size(34.dp, 52.dp)
                .cssShadow(RoundedCornerShape(17.dp), y = 2.dp, blur = 8.dp, colour = Color(0x1A000000))
                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(17.dp))
                .tap(stringResource(R.string.shop_list_open)) { nav.go(Dest.Groceries("list")) },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.list", tint = Ink, size = 18.dp) }
    }
}

/** The blue enamel sign: a thin dark rim, a light rim inside it, the number in a white disc and the name in highway lettering. */
@Composable
private fun Sign(number: Int, name: String, sub: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().height(84.dp)
            .cssShadow(shape, y = 10.dp, blur = 18.dp, colour = Color(0x40142846))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A6FB0), Color(0xFF1F5F99))))
            .border(BorderStroke(3.dp, Color(0xFF1A5286)), shape)
            .padding(3.dp).border(BorderStroke(2.dp, Color(0xFF6FA2D2)), RoundedCornerShape(7.dp))
            .padding(horizontal = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = TextStyle(fontFamily = ShopType.overpass, fontWeight = FontWeight.Black, fontSize = 30.sp), color = Color(0xFF1F5F99))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = TextStyle(fontFamily = ShopType.overpass, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, lineHeight = 25.sp, letterSpacing = 0.25.sp),
                color = Color.White,
                maxLines = 1,
            )
            if (sub != null) Text(sub, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Color(0xFFCFE0F2))
        }
    }
}

@Composable
private fun BrandRow(brands: List<BrandChip>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in brands) {
            val on = selected == b.key
            val name = if (b.independent) stringResource(R.string.shop_independent) else b.name
            Row(
                Modifier.height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) Ink else Color.White)
                    .then(if (on) Modifier else Modifier.border(1.dp, RingGrey, RoundedCornerShape(26.dp)))
                    .tap(name) { onPick(b.key) }
                    .semantics { this.selected = on }
                    .padding(start = 6.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (b.independent) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE9E3D8)), contentAlignment = Alignment.Center) { Symbol("fact.independent", tint = Ink, size = 22.dp) }
                } else {
                    ShopLogo(b.brand, b.name, 40.dp, key = b.key, round = b.name.length < 1)
                }
                Text(
                    "$name ${b.count}",
                    style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    color = if (on) Color.White else Ink,
                    maxLines = 1,
                )
            }
        }
    }
}

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
            val fg = if (selected) Color(0xFF1F3FC7) else Color(0xFF464C5A)
            Row(
                Modifier.height(40.dp).alpha(if (dim) 0.45f else 1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) Color(0xFFE4EAFF) else Color.White)
                    .then(if (selected) Modifier else Modifier.border(1.dp, RingGrey, RoundedCornerShape(20.dp)))
                    .tap(label) { onToggle(f) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = if (f == AisleFilter.SUNDAY) 12.dp else 11.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (f) {
                    AisleFilter.SUNDAY -> {
                        Symbol("ui.calendar", tint = fg, size = 16.dp)
                        Text(stringResource(R.string.shop_f_sunday), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = fg)
                    }
                    AisleFilter.SELF_CHECKOUT -> Symbol("shop.self-checkout", tint = fg, size = 18.dp)
                    AisleFilter.DELIVERY -> Symbol("fact.delivery", tint = fg, size = 18.dp)
                    AisleFilter.PARKING -> Text("P", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp), color = fg, modifier = Modifier.width(18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    AisleFilter.STEP_FREE -> Symbol("fact.wheelchair", tint = fg, size = 18.dp)
                }
            }
        }
    }
}

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
            Text(
                label,
                Modifier.tap(label) { onPick(s) }.semantics { this.selected = on }
                    .drawBehind { if (on) drawRect(Ink, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                    .padding(bottom = 5.dp),
                style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = if (on) Ink else Grey,
            )
        }
    }
}

/** A shelf-edge label: white card with a colour strip, the shop, its closing time, footprint and stars, a barcode, and the walk in minutes. */
@Composable
private fun ShelfLabel(p: ShopPlace, spec: AisleSpec, area: ShopArea, now: java.time.LocalDateTime, rating: app.atlas.providers.Rating?, isCheapest: Boolean, onClick: () -> Unit) {
    val closed = p.openness(now, area.hours) == Open.CLOSED
    val colour = p.brand?.let { brandColour(it, null) } ?: p.tags["brand"]?.let { brandColour(null, it) } ?: spec.colour
    val card = if (isCheapest) Color(0xFFFFE45C) else Color.White
    val strip = if (isCheapest) Color(0xFFF28C28) else colour
    val ink = if (isCheapest) Color(0xFF3A3520) else Soft
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().height(112.dp).alpha(if (closed) 0.6f else 1f)
            .cssShadow(shape, y = 6.dp, blur = 14.dp, colour = Color(0x1414161B))
            .cssShadow(shape, y = 2.dp, blur = 0.dp, colour = if (isCheapest) Color(0xFFD8BE2E) else Color(0xFFD5D8DC))
            .clip(shape).background(card).tap(p.name, onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(strip))
        Row(Modifier.weight(1f).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.brand != null || p.tags["brand"] != null) {
                ShopLogo(p.brand, p.tags["brand"] ?: p.name, 44.dp, key = p.tags["brand"], round = p.brand?.logoSlug == null)
            } else {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(spec.colour.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Symbol(spec.glyph, tint = hueOf(spec.glyph) ?: spec.colour, size = 24.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp), color = Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val details = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontFeatureSettings = "tnum")
                    openLine(p, area, now)?.let { (t, c) -> Text(t, style = details, color = if (isCheapest) ink else c, maxLines = 1) }
                    p.footprintM2?.let { Text(stringResource(R.string.shop_footprint, "%,d".format(Locale.getDefault(), it)), style = details, color = ink, maxLines = 1) }
                    rating?.stars?.let { Text("★ " + "%.1f".format(Locale.getDefault(), it), style = details, color = ink, maxLines = 1) }
                    if (isCheapest) Text(stringResource(R.string.shop_cheapest_basket), style = details.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1)
                }
                if (!isCheapest) Barcode(p.key, Modifier.size(90.dp, 12.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    buildAnnotatedString {
                        append(p.walkMinutes.toString())
                        withStyle(SpanStyle(fontSize = 22.sp)) { append("′") }
                    },
                    style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-0.02f).em(), fontFeatureSettings = "tnum"),
                    color = Ink,
                    maxLines = 1,
                )
                Text(stringResource(R.string.shop_walk), style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp), color = if (isCheapest) ink else Grey)
            }
        }
    }
}

private fun Float.em() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Em)

/** The barcode of a shelf-edge label, drawn as the board's: 1.2 px bars two to four px apart, here from the shop's own id. */
@Composable
internal fun Barcode(seed: String, modifier: Modifier = Modifier, colour: Color = Ink) {
    Canvas(modifier) {
        var h = seed.hashCode().toLong() * 2654435761L + 12345
        var x = 0f
        val k = size.width / 90f
        while (x < 89f) {
            drawLine(colour, Offset(x * k + 0.6f, 0f), Offset(x * k + 0.6f, size.height), strokeWidth = 1.2f * k)
            h = h * 6364136223846793005L + 1442695040888963407L
            x += 2f + ((h ushr 33) % 3).toInt()
        }
    }
}
