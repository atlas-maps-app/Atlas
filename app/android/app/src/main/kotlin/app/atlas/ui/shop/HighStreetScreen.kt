package app.atlas.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
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

    Box(Modifier.fillMaxSize().background(argb(0xFFA85C44)).drawBricks()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 95.dp + boardLift())) {
            Box(Modifier.fillMaxWidth().height(248.dp)) {
                Box(
                    Modifier.at(16f, 42f, 44f, 44f).boardBox(0xD9FFFFFF, round(22f)).tap(stringResource(R.string.shop_back_map)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { Sym("tab.map", 20f, 20f) }
                IndieSwitch(indie, Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 42.dp)) { indie = it }
                Plaque(counts, area, Modifier.align(Alignment.TopCenter).padding(top = 100.dp))
                StreetSearch(nav, Modifier.offset(0.dp, 180.dp))
            }
            // The twelve signs: three columns 6 apart, rows of 140 four apart, 10 px from the sides.
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
                                Sym(art.symbol, 120f, 112f, 0xFFFFFFFF)
                                Line(
                                    stringResource(art.label) + if (c != null && !area.waiting) " ${c.value}" else "",
                                    bt(12f, 700, 15.5f, tnum = true), 0xFFF4EBDD,
                                    Modifier.offset(y = (-4).dp).boardBox(0xFF1A1A1A, round(4f)).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 26.dp + boardLift()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).height(56.dp).boardBox(INK, round(28f)).clickable(role = Role.Button) { nav.go(Dest.Groceries("stroll")) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("mode.walk.5", 20f, 20f, 0xFFFFFFFF)
                Line(stringResource(R.string.shop_stroll), bt(16f, 600, 20.5f), 0xFFFFFFFF)
            }
            Box(Modifier.size(56.dp).boardBox(0xE6FFFFFF, round(28f)).tap(stringResource(R.string.shop_brands_branches)) { nav.go(Dest.Brands()) }, contentAlignment = Alignment.Center) {
                Sym("ui.brands", 22f, 22f)
            }
            Box(Modifier.size(56.dp).boardBox(0xE6FFFFFF, round(28f)).tap(stringResource(R.string.shop_malls)) { nav.go(Dest.Search("mall")) }, contentAlignment = Alignment.Center) {
                Sym("ui.layers", 22f, 22f)
            }
        }
    }
}

/**
 * The brick wall, as the board's background: a 2 px joint of pale mortar at the foot of every 22 px course and every
 * 26 px across, darkened towards the top and the bottom.
 */
private fun Modifier.drawBricks(): Modifier = drawBehind {
    val mortar = Color(0x8CEBDCC8)
    val w = 2.dp.toPx()
    var y = 22.dp.toPx() - w
    while (y < size.height) { drawRect(mortar, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, w)); y += 22.dp.toPx() }
    var x = 0f
    while (x < size.width) { drawRect(mortar, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)); x += 26.dp.toPx() }
    drawRect(Brush.verticalGradient(0f to Color(0x59280F08), 0.3f to Color(0x00280F08), 0.7f to Color(0x00280F08), 1f to Color(0x59280F08)))
}

/** "indie": 44 high, the shop symbol, the word and a 44 × 28 switch (green when on, its knob 3 px in from the end). */
@Composable
private fun IndieSwitch(on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val d = stringResource(R.string.shop_indie_d)
    Row(
        modifier.height(44.dp).boardBox(0xD9FFFFFF, round(22f))
            .tap(d) { onChange(!on) }.semantics { selected = on }.padding(start = 12.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym("place.shop", 18f, 18f)
        Line(stringResource(R.string.shop_indie), bt(12f, 700, 15.5f), INK)
        Box(Modifier.size(44.dp, 28.dp).boardBox(if (on) 0xFF1E7A46 else 0xFF9AA0A8, round(14f))) {
            Box(
                Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 3.dp).size(22.dp)
                    .boardBox(0xFFFFFFFF, round(11f), listOf(CssShadow(0f, 1f, 3f, 0f, 0x4C000000)), clip = false),
            )
        }
    }
}

/** The street sign (260 × 62): white enamel, a 2 px black line 3 px in, the street in Instrument Serif and the count in red spaced capitals. */
@Composable
private fun Plaque(counts: StreetCounts, area: ShopArea, modifier: Modifier) {
    Column(
        modifier.size(260.dp, 62.dp).boardBox(
            0xFFFBFBF7, round(10f),
            listOf(CssShadow(0f, 0f, 0f, 3f, 0xFFFBFBF7, inset = true), CssShadow(0f, 0f, 0f, 5f, 0xFF1A1A1A, inset = true), CssShadow(0f, 6f, 12f, 0f, 0x59000000)),
            clip = false,
        ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Block(stringResource(R.string.shop_highstreet), bt(30f, 400, 30f, SERIF, spacing = 1.2f), 0xFF1A1A1A, Modifier.semantics { heading() })
        val town = counts.town?.uppercase()
        val sub = when {
            area.waiting -> null
            town != null && counts.total.open -> stringResource(R.string.shop_highstreet_sub, town, counts.total.value)
            town != null -> stringResource(R.string.shop_highstreet_sub_all, town, counts.total.value)
            counts.total.open -> stringResource(R.string.shop_highstreet_sub_here, counts.total.value)
            else -> stringResource(R.string.shop_highstreet_sub_here_all, counts.total.value)
        }
        if (sub != null) Line(sub, bt(10f, 700, 13f, spacing = 3f), 0xFFC8322A)
    }
}

/** The search on the wall (y 180, 50 high, 92 % white): what it finds goes to Find. */
@Composable
private fun StreetSearch(nav: Navigator, modifier: Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val hint = stringResource(R.string.shop_street_search)
    val label = stringResource(R.string.shop_find_hint)
    Row(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp)
            .boardBox(0xEBFFFFFF, round(25f), listOf(CssShadow(0f, 4f, 12f, 0f, 0x40000000)), clip = false).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym("fact.new", 18f, 18f, 0xFF7B4DFF)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val style = bt(16f, 500, 20.5f)
            BasicTextField(
                text, { text = it }, singleLine = true, textStyle = style.copy(color = argb(INK)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { nav.go(Dest.Groceries(if (text.isBlank()) "find" else "find:" + text.trim())) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
            if (text.isEmpty()) Line(hint, style, 0xFF676D7C)
        }
    }
}
