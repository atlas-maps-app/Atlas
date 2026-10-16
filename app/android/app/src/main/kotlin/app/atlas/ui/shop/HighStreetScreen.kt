package app.atlas.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere

/** The high street's twelve trade signs: the symbol (a wrought-iron sign, 120 × 112 as the board draws it), its words and its colour. */
internal class FamilyArt(val family: Family, val symbol: String, val label: Int, val name: Int, val colour: Color)

internal val FamilyArts: Map<Family, FamilyArt> = listOf(
    FamilyArt(Family.FASHION, "shop.fashion", R.string.shop_fam_fashion, R.string.shop_famname_fashion, Color(0xFF7A2233)),
    FamilyArt(Family.SHOES, "shop.shoes", R.string.shop_fam_shoes, R.string.shop_famname_shoes, Color(0xFF1F3F7A)),
    FamilyArt(Family.BOOKS, "shop.books", R.string.shop_fam_books, R.string.shop_famname_books, Color(0xFF2F5D3A)),
    FamilyArt(Family.TECH, "shop.tech", R.string.shop_fam_tech, R.string.shop_famname_tech, Color(0xFF14161B)),
    FamilyArt(Family.HOME, "shop.diy", R.string.shop_fam_home, R.string.shop_famname_home, Color(0xFFC98A2B)),
    FamilyArt(Family.FLOWERS, "shop.florist", R.string.shop_fam_flowers, R.string.shop_famname_flowers, Color(0xFF2F6B34)),
    FamilyArt(Family.HEALTH, "shop.health", R.string.shop_fam_health, R.string.shop_famname_health, Color(0xFF1E9E5A)),
    FamilyArt(Family.SPORT, "shop.sport", R.string.shop_fam_sport, R.string.shop_famname_sport, Color(0xFFC8322A)),
    FamilyArt(Family.KIDS, "shop.kids", R.string.shop_fam_kids, R.string.shop_famname_kids, Color(0xFFB8861B)),
    FamilyArt(Family.PETS, "shop.pets", R.string.shop_fam_pets, R.string.shop_famname_pets, Color(0xFF12A9A6)),
    FamilyArt(Family.VINTAGE, "shop.vintage", R.string.shop_fam_vintage, R.string.shop_famname_vintage, Color(0xFFD9A82B)),
    FamilyArt(Family.ERRANDS, "shop.errands", R.string.shop_fam_errands, R.string.shop_famname_errands, Color(0xFF464C5A)),
).associateBy { it.family }

/** The aisle of a high-street family: every shop that is first of that family. */
internal fun Family.spec(): AisleSpec = FamilyArts.getValue(this).let { art ->
    AisleSpec("street:" + name.lowercase(), art.name, ordinal + 1, art.colour, art.symbol) { area, _ -> area.places.filter { Family.of(it.tags) == this } }
}

/** The families' shops open now, the total and the name of the place. */
internal class StreetCounts(val byFamily: Map<Family, ShopArea.Count>, val total: ShopArea.Count, val town: String?) {
    companion object {
        fun of(area: ShopArea, at: java.time.LocalDateTime, indie: Boolean): StreetCounts {
            val shops = area.places.filter { !indie || it.independent }
            val by = shops.groupBy { Family.of(it.tags) }
            val counts = Family.entries.associateWith { f -> area.count(by[f].orEmpty(), at) }
            val town = area.places.mapNotNull { it.tags["addr:suburb"] ?: it.tags["addr:city"] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return StreetCounts(counts, area.count(shops.filter { Family.of(it.tags) != null }, at), town)
        }
    }
}

/**
 * The high street, as on the HighStreet board: a brick wall with twelve wrought-iron trade signs (fashion, shoes, books,
 * tech, home and DIY, flowers, health, sport, kids, pets, vintage, errands) each with how many shops of that kind are
 * open; the "indie" switch drops every chain; a search that goes to Find; Stroll along the street, brands and branches,
 * and malls. A tap on a sign lists those shops as an aisle.
 */
@Composable
fun HighStreetScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    val area by produceState(ShopArea.empty(here), here) { repo.area("street", Family.all, here).collect { value = it } }
    var indie by rememberSaveable { mutableStateOf(false) }
    val counts = remember(area, now, indie) { StreetCounts.of(area, now, indie) }
    val wall = Color(0xFFA85C44)

    Box(
        Modifier.fillMaxSize().background(wall).drawBehind {
            // Brick joints: a line every 22 px across and every 26 px down, in pale mortar.
            val mortar = Color(0xFFEBDCC8).copy(alpha = 0.55f)
            val w = 2.dp.toPx()
            var y = 0f
            while (y < size.height) { drawRect(mortar, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, w)); y += 22.dp.toPx() }
            var x = 0f
            while (x < size.width) { drawRect(mortar, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)); x += 26.dp.toPx() }
            drawRect(Brush.verticalGradient(0f to Color(0x59280F08), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0x59280F08)))
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 2.dp, bottom = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)).tap(stringResource(R.string.shop_back_map)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { BoardArt(ShopBoards.PANTRY_MAP, tint = Color(0xFF14161B), width = 20.dp, height = 20.dp) }
                Spacer(Modifier.weight(1f))
                IndieSwitch(indie) { indie = it }
            }
            Spacer(Modifier.height(14.dp))
            Plaque(counts, area)
            Spacer(Modifier.height(18.dp))
            StreetSearch(nav)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Family.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in row) {
                            val art = FamilyArts.getValue(f)
                            val c = counts.byFamily[f]
                            val name = stringResource(art.name)
                            val desc = if (c == null || area.waiting) name else if (c.open) stringResource(R.string.shop_fam_desc, name, c.value) else stringResource(R.string.shop_fam_desc_n, name, c.value)
                            Column(
                                Modifier.weight(1f).height(140.dp).tap(desc) { nav.go(if (f == Family.ERRANDS) Dest.Groceries("errands") else Dest.Groceries("street:" + f.name.lowercase() + if (indie) ":indie" else "")) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                BoardSymbol(art.symbol, 120.dp, 112.dp)
                                Text(
                                    stringResource(art.label) + if (c != null && !area.waiting) " ${c.value}" else "",
                                    Modifier.offset(y = (-4).dp).background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFeatureSettings = "tnum"), color = Color(0xFFF4EBDD), maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigPill(
                stringResource(R.string.shop_stroll), { nav.go(Dest.Groceries("stroll")) }, Modifier.weight(1f), fill = Color(0xFF14161B),
                icon = { Symbol("mode.walk.5", tint = Color.White, size = 20.dp) },
            )
            RoundSymbolButton("ui.brands", stringResource(R.string.shop_brands_branches), { nav.go(Dest.Brands()) }, size = 56.dp, fill = Color.White.copy(alpha = 0.9f), iconSize = 22.dp)
            RoundSymbolButton("ui.layers", stringResource(R.string.shop_malls), { nav.go(Dest.Search("mall")) }, size = 56.dp, fill = Color.White.copy(alpha = 0.9f), iconSize = 22.dp)
        }
    }
}

@Composable
private fun IndieSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val d = stringResource(R.string.shop_indie_d)
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.85f))
            .tap(d) { onChange(!on) }.semantics { selected = on }.padding(start = 12.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("place.shop", tint = Color(0xFF14161B), size = 18.dp)
        Text(stringResource(R.string.shop_indie), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp), color = Color(0xFF14161B))
        Box(Modifier.size(44.dp, 28.dp).background(if (on) Color(0xFF1E7A46) else Color(0xFF9AA0A8), RoundedCornerShape(14.dp))) {
            Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 3.dp).size(22.dp).cssShadow(CircleShape, y = 1.dp, blur = 3.dp, colour = Color(0x4D000000)).background(Color.White, CircleShape))
        }
    }
}

/** The street sign: white enamel with a black double border, the street in Instrument Serif and the count in red capitals. */
@Composable
private fun Plaque(counts: StreetCounts, area: ShopArea) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.size(260.dp, 62.dp).cssShadow(shape, y = 6.dp, blur = 12.dp, colour = Color(0x59000000))
            .background(Color(0xFFFBFBF7), shape).padding(3.dp).border(2.dp, Color(0xFF1A1A1A), RoundedCornerShape(7.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.shop_highstreet), style = TextStyle(fontFamily = SerifFamily, fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = 1.2.sp), color = Color(0xFF1A1A1A))
        val town = counts.town?.uppercase()
        val sub = when {
            area.waiting -> null
            town != null && counts.total.open -> stringResource(R.string.shop_highstreet_sub, town, counts.total.value)
            town != null -> stringResource(R.string.shop_highstreet_sub_all, town, counts.total.value)
            counts.total.open -> stringResource(R.string.shop_highstreet_sub_here, counts.total.value)
            else -> stringResource(R.string.shop_highstreet_sub_here_all, counts.total.value)
        }
        if (sub != null) Text(sub, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 3.sp), color = Color(0xFFC8322A), maxLines = 1)
    }
}

@Composable
private fun StreetSearch(nav: Navigator) {
    var text by rememberSaveable { mutableStateOf("") }
    val hint = stringResource(R.string.shop_street_search)
    val label = stringResource(R.string.shop_find_hint)
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp).cssShadow(RoundedCornerShape(25.dp), y = 4.dp, blur = 12.dp, colour = Color(0x40000000))
            .clip(RoundedCornerShape(25.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardArt(ShopBoards.SPARKLE, tint = Color(0xFF7B4DFF))
        Box(Modifier.weight(1f)) {
            val style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Color(0xFF14161B))
            BasicTextField(
                text, { text = it }, singleLine = true, textStyle = style,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { nav.go(Dest.Groceries(if (text.isBlank()) "find" else "find:" + text.trim())) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
            if (text.isEmpty()) Text(hint, style = style, color = Color(0xFF676D7C), maxLines = 1)
        }
    }
}
