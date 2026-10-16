package app.atlas.ui.shop

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere

private val PaperWarm = Color(0xFFEDE4D6)
private val Ink = Color(0xFF14161B)
private val InkSoft = Color(0xFF5A5347)

/** The boards' twelve shelf objects: the symbol, its size on the board, and the strings that name it. */
internal class ShelfArt(val shelf: Shelf, val symbol: String, val width: Int, val height: Int, val label: Int, val name: Int)

internal val ShelfArts: Map<Shelf, ShelfArt> = listOf(
    ShelfArt(Shelf.SUPER, "pantry.supermarket", 74, 124, R.string.shop_shelf_super, R.string.shop_name_super),
    ShelfArt(Shelf.CORNER, "pantry.corner-shop", 58, 112, R.string.shop_shelf_corner, R.string.shop_name_corner),
    ShelfArt(Shelf.FRESH, "pantry.produce", 112, 84, R.string.shop_shelf_fresh, R.string.shop_name_fresh),
    ShelfArt(Shelf.BAKERY, "pantry.bakery", 62, 120, R.string.shop_shelf_bakery, R.string.shop_name_bakery),
    ShelfArt(Shelf.MEAT_FISH, "pantry.butcher", 96, 60, R.string.shop_shelf_meat_fish, R.string.shop_name_meat_fish),
    ShelfArt(Shelf.DELI, "pantry.cheese", 84, 62, R.string.shop_shelf_deli, R.string.shop_name_deli),
    ShelfArt(Shelf.WORLD, "pantry.world-foods", 64, 86, R.string.shop_shelf_world, R.string.shop_name_world),
    ShelfArt(Shelf.REFILL, "pantry.bulk", 62, 96, R.string.shop_shelf_refill, R.string.shop_name_refill),
    ShelfArt(Shelf.DRINKS, "pantry.wine", 38, 128, R.string.shop_shelf_drinks, R.string.shop_name_drinks),
    ShelfArt(Shelf.FARM, "pantry.eggs", 104, 52, R.string.shop_shelf_farm, R.string.shop_name_farm),
    ShelfArt(Shelf.LATE, "pantry.late", 96, 58, R.string.shop_shelf_late, R.string.shop_name_late),
    ShelfArt(Shelf.SHARE, "pantry.community", 74, 66, R.string.shop_shelf_share, R.string.shop_name_share),
).associateBy { it.shelf }

/**
 * The Groceries home, as on the Pantry board: three wooden shelves with twelve drawn things on them, a paper tag under
 * each with how many of those shops are open now; a search ("oat milk and saffron, open now"), the shopping list's badge,
 * "Plan my run" and the price tag for the basket compare. A tap on a thing opens that aisle.
 */
@Composable
fun PantryScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    var attempt by remember { mutableStateOf(0) }
    val area by produceState(ShopArea.empty(here), here, attempt) { repo.area("pantry", Shelf.all, here).collect { value = it } }
    val counts = remember(area, now) { PantryCounts.of(area, now) }
    val list by repo.list.items.collectAsStateWithLifecycle()
    val toBuy = list.count { !it.atHome }

    Box(Modifier.fillMaxSize().background(PaperWarm).drawStripes()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 4.dp, bottom = 100.dp),
        ) {
            PantryHeader(nav, counts, area, toBuy, now)
            Spacer(Modifier.height(18.dp))
            PantrySearch(nav)
            Spacer(Modifier.height(20.dp))
            ShelfRow(listOf(Shelf.SUPER, Shelf.CORNER, Shelf.FRESH, Shelf.BAKERY), 150.dp, counts, area, nav)
            Spacer(Modifier.height(32.dp))
            ShelfRow(listOf(Shelf.MEAT_FISH, Shelf.DELI, Shelf.WORLD, Shelf.REFILL), 130.dp, counts, area, nav)
            Spacer(Modifier.height(20.dp))
            ShelfRow(listOf(Shelf.DRINKS, Shelf.FARM, Shelf.LATE, Shelf.SHARE), 140.dp, counts, area, nav)
            PantryNote(area) { repo.forget(); attempt++ }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigPill(
                text = if (toBuy > 0) stringResource(R.string.shop_plan_run_n, toBuy) else stringResource(R.string.shop_plan_run),
                onClick = { nav.go(Dest.Groceries("list")) },
                modifier = Modifier.weight(1f),
                icon = { BoardArt(ShopBoards.CART, tint = Color.White) },
            )
            RoundSymbolButton(
                "ui.tag", stringResource(R.string.shop_compare_prices), { nav.go(Dest.Groceries("compare")) }, size = 56.dp, fill = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/** Faint vertical boards behind the pantry: a 1 px line every 58 px, as the board's background gradient. */
private fun Modifier.drawStripes(): Modifier = drawBehindCanvas { w, h, step ->
    var x = 0f
    while (x < w) {
        drawLine(Color.Black.copy(alpha = 0.03f), Offset(x + 0.5f, 0f), Offset(x + 0.5f, h), strokeWidth = 1f)
        x += step
    }
}

private fun Modifier.drawBehindCanvas(block: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Float, Float) -> Unit): Modifier =
    this.then(Modifier.drawBehind { block(size.width, size.height, 58.dp.toPx()) })

@Composable
private fun PantryHeader(nav: Navigator, counts: PantryCounts, area: ShopArea, toBuy: Int, now: java.time.LocalDateTime) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.72f)).tap(stringResource(R.string.shop_back_map)) { nav.back() },
            contentAlignment = Alignment.Center,
        ) { BoardArt(ShopBoards.PANTRY_MAP, tint = Ink) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.shop_pantry), style = TextStyle(fontFamily = SerifFamily, fontSize = 32.sp, lineHeight = 32.sp), color = Ink)
            val total = counts.total
            val line = when {
                area.waiting -> stringResource(R.string.shop_loading)
                total.open -> stringResource(R.string.shop_pantry_open, total.value, now.hm())
                else -> stringResource(R.string.shop_pantry_shops, total.value, now.hm())
            }
            Text(line, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = InkSoft)
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Ink).tap(
                if (toBuy > 0) stringResource(R.string.shop_list_open_n, toBuy) else stringResource(R.string.shop_list_open),
            ) { nav.go(Dest.Groceries("list")) },
            contentAlignment = Alignment.Center,
        ) {
            Symbol("ui.list.2", tint = Color.White, size = 20.dp)
            if (toBuy > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(20.dp).clip(CircleShape).background(PaperWarm).padding(2.dp).clip(CircleShape).background(Color(0xFFE8622C)),
                    contentAlignment = Alignment.Center,
                ) { Text(toBuy.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp), color = Color.White) }
            }
        }
    }
}

@Composable
private fun PantrySearch(nav: Navigator) {
    var text by remember { mutableStateOf("") }
    val label = stringResource(R.string.shop_search_label)
    val go = { nav.go(Dest.Groceries(if (text.isBlank()) "find" else "find:" + text.trim())) }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp)
            .cssShadow(RoundedCornerShape(25.dp), y = 2.dp, blur = 8.dp, colour = Color(0x143C280A))
            .clip(RoundedCornerShape(25.dp)).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardArt(ShopBoards.SPARKLE, tint = Color(0xFF7B4DFF))
        Box(Modifier.weight(1f)) {
            val style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Ink)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = style,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
            if (text.isEmpty()) Text(stringResource(R.string.shop_search_hint), style = style, color = Color(0xFF8C8374), maxLines = 1)
        }
    }
}

/** One wooden shelf: four things standing on a plank, each with its paper tag. */
@Composable
private fun ShelfRow(shelves: List<Shelf>, height: Dp, counts: PantryCounts, area: ShopArea, nav: Navigator) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.Bottom) {
            for (s in shelves) {
                val art = ShelfArts.getValue(s)
                val c = counts.byShelf[s]
                val label = stringResource(art.name)
                val desc = if (c == null || area.waiting || c.value < 0) label else if (c.open) stringResource(R.string.shop_shelf_desc, label, c.value) else stringResource(R.string.shop_shelf_desc_n, label, c.value)
                BoardSymbol(art.symbol, art.width.dp, art.height.dp, Modifier.tap(desc) { nav.go(Dest.Groceries(s.id)) })
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Plank()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
                for (s in shelves) {
                    val c = counts.byShelf[s]
                    val text = stringResource(ShelfArts.getValue(s).label) + if (c != null && !area.waiting) " " + (if (c.value < 0) "–" else c.value.toString()) else ""
                    Text(
                        text,
                        Modifier.cssShadow(RoundedCornerShape(2.dp), y = 1.dp, blur = 2.dp, colour = Color(0x33000000)).background(Color(0xFFFFFDF6), RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFeatureSettings = "tnum"),
                        color = Ink,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The plank: warm wood in a vertical gradient with a pale top edge and a long soft shadow under it. */
@Composable
private fun Plank() {
    Box(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(18.dp)
            .cssShadow(RoundedCornerShape(3.dp), y = 8.dp, blur = 12.dp, colour = Color(0x47503214))
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFC9985E), Color(0xFFA5763F)))),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.35f)))
    }
}

/** What the shelves are made of, said plainly: names only (tiles), a failed network with a way to try again, or nothing at all. */
@Composable
private fun PantryNote(area: ShopArea, retry: () -> Unit) {
    if (area.error != null && area.places.isEmpty()) {
        StateMessage(
            "ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C),
            action = { PillButton(onClick = retry) { Text(stringResource(R.string.shop_try_again), style = Atlas.type.label) } },
        )
    } else if (area.sketchOnly && area.places.isNotEmpty()) {
        Text(
            stringResource(R.string.shop_sketch_note),
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth(),
            style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp), color = InkSoft, textAlign = TextAlign.Center,
        )
    }
}
