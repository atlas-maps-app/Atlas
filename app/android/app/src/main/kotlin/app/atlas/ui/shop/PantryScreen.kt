package app.atlas.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.rememberHere

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

/** Where the pantry's twelve things stand and their tags hang, in board px: the symbol's box and the tag's centre. */
private class ShelfPlace(val shelf: Shelf, val x: Float, val y: Float, val tagCentre: Float)

/** The three planks' tops in board px, and the things on each, as the Pantry board places them. */
private val Planks = listOf(
    326f to listOf(ShelfPlace(Shelf.SUPER, 25.25f, 202f, 63.23f), ShelfPlace(Shelf.CORNER, 117.75f, 214f, 162.63f), ShelfPlace(Shelf.FRESH, 194.25f, 242f, 258.28f), ShelfPlace(Shelf.BAKERY, 324.75f, 206f, 348.87f)),
    510f to listOf(ShelfPlace(Shelf.MEAT_FISH, 25.25f, 450f, 71.64f), ShelfPlace(Shelf.DELI, 139.75f, 448f, 166f), ShelfPlace(Shelf.WORLD, 242.25f, 424f, 255.26f), ShelfPlace(Shelf.REFILL, 324.75f, 414f, 350.89f)),
    692f to listOf(ShelfPlace(Shelf.DRINKS, 24.5f, 564f, 68.96f), ShelfPlace(Shelf.FARM, 79.5f, 640f, 166.15f), ShelfPlace(Shelf.LATE, 200.5f, 634f, 255.73f), ShelfPlace(Shelf.SHARE, 313.5f, 626f, 348.53f)),
)

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

    Box(Modifier.fillMaxSize().background(argb(PAPER)).drawStripes()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop())) {
            Box(Modifier.fillMaxWidth().height(BOARD_BOTTOM.dp)) {
                PantryHeader(nav, counts, area, toBuy, now)
                PantrySearch(nav)
                for ((plank, places) in Planks) ShelfRow(plank, places, counts, area, nav)
                PantryNote(area) { repo.forget(); attempt++ }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(bottom = boardLift()).fillMaxWidth().height(82.dp)) {
            // The board's bottom row, y 833 to 889 of 915: placed from the bottom, 26 px above it.
            val y = -833f
            val plan = if (toBuy > 0) stringResource(R.string.shop_plan_run_n, toBuy) else stringResource(R.string.shop_plan_run)
            Row(
                Modifier.at(16f, 833f + y, 316f, 56f).boardBox(0xFF2E5BFF, round(28f)).clickable(role = Role.Button) { nav.go(Dest.Groceries("list")) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("brand.greencart", 20f, 20f, 0xFFFFFFFF)
                Line(plan, bt(16f, 600, 20.5f, tnum = true), 0xFFFFFFFF)
            }
            Box(
                Modifier.at(340f, 833f + y, 56f, 56f).boardBox(0xCCFFFFFF, round(28f)).tap(stringResource(R.string.shop_compare_prices)) { nav.go(Dest.Groceries("compare")) },
                contentAlignment = Alignment.Center,
            ) { Sym("ui.tag", 21f, 21f) }
        }
    }
}

/** How far down the pantry's content goes: the last tag ends at 714, the note under it. */
private const val BOARD_BOTTOM = 820f

private const val PAPER: Long = 0xFFEDE4D6
private const val INK_SOFT: Long = 0xFF5A5347

/** Faint vertical boards behind the pantry: a 1 px line of 3 % black every 58 px, as the board's background image. */
private fun Modifier.drawStripes(): Modifier = drawBehind {
    val step = 58.dp.toPx()
    val w = 1.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawRect(Color(0x08000000), topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
        x += step
    }
}

@Composable
private fun PantryHeader(nav: Navigator, counts: PantryCounts, area: ShopArea, toBuy: Int, now: java.time.LocalDateTime) {
    Box(Modifier.at(16f, 45.5f, 48f, 48f).boardBox(0xB8FFFFFF, round(24f)).tap(stringResource(R.string.shop_back_map)) { nav.back() }, contentAlignment = Alignment.Center) {
        Sym("tab.map", 21f, 21f)
    }
    Block(stringResource(R.string.shop_pantry), bt(32f, 400, 32f, SERIF, align = TextAlign.Center), INK, Modifier.at(72f, 39f, 268f, 41.5f).semantics { heading() })
    val total = counts.total
    val line = when {
        area.waiting -> stringResource(R.string.shop_loading)
        total.open -> stringResource(R.string.shop_pantry_open, total.value, now.hm())
        else -> stringResource(R.string.shop_pantry_shops, total.value, now.hm())
    }
    Line(line, bt(13f, 400, 17f, tnum = true, align = TextAlign.Center), INK_SOFT, Modifier.at(72f, 78f, 268f, 17f))
    Box(
        Modifier.at(348f, 45.5f, 48f, 48f).boardBox(INK, round(24f))
            .tap(if (toBuy > 0) stringResource(R.string.shop_list_open_n, toBuy) else stringResource(R.string.shop_list_open)) { nav.go(Dest.Groceries("list")) },
        contentAlignment = Alignment.Center,
    ) { Sym("ui.list.2", 20f, 20f, 0xFFFFFFFF) }
    if (toBuy > 0) {
        // The badge: 24 high, 10 px corners, a 2 px ring of the paper; wider for two figures.
        Box(
            Modifier.offset(374.dp, 43.5.dp).requiredHeight(24.dp).widthIn(min = 24.dp)
                .boardBox(0xFFE8622C, round(10f), border = ring(2f, PAPER)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Line(toBuy.toString(), bt(11f, 700, 14f, tnum = true), 0xFFFFFFFF) }
    }
}

@Composable
private fun PantrySearch(nav: Navigator) {
    var text by remember { mutableStateOf("") }
    val label = stringResource(R.string.shop_search_label)
    val go = { nav.go(Dest.Groceries(if (text.isBlank()) "find" else "find:" + text.trim())) }
    Box(Modifier.at(16f, 106f, 380f, 50f).boardBox(0xCCFFFFFF, round(25f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x143C280A)), clip = false))
    SyAt("fact.new", 32f, 122f, 18f, 18f, 0xFF7B4DFF)
    val style = bt(16f, 500, 20.5f)
    Box(Modifier.at(62f, 120.75f, 324f, 20.5f)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = style.copy(color = argb(INK)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
        if (text.isEmpty()) Line(stringResource(R.string.shop_search_hint), style, 0xFFA9A9A9)
    }
}

/** One wooden shelf: its four things standing on the plank at [plank], each with its paper tag. */
@Composable
private fun ShelfRow(plank: Float, places: List<ShelfPlace>, counts: PantryCounts, area: ShopArea, nav: Navigator) {
    for (p in places) {
        val art = ShelfArts.getValue(p.shelf)
        val c = counts.byShelf[p.shelf]
        val label = stringResource(art.name)
        val desc = if (c == null || area.waiting || c.value < 0) label else if (c.open) stringResource(R.string.shop_shelf_desc, label, c.value) else stringResource(R.string.shop_shelf_desc_n, label, c.value)
        Sym(art.symbol, art.width.toFloat(), art.height.toFloat(), 0xFF2E5BFF, Modifier.offset(p.x.dp, p.y.dp).tap(desc) { nav.go(Dest.Groceries(p.shelf.id)) })
    }
    // The plank: warm wood, a pale top edge (inset), a long soft shadow under it.
    Box(
        Modifier.at(8f, plank, 396f, 18f).boardBox(
            0, round(3f),
            listOf(CssShadow(0f, 8f, 12f, 0f, 0x47503214), CssShadow(0f, 2f, 0f, 0f, 0x59FFFFFF, inset = true)),
            grad = listOf(cssLinear(180f, 0f to 0xFFC9985E, 1f to 0xFFA5763F)),
        ),
    )
    for (p in places) {
        val c = counts.byShelf[p.shelf]
        val text = stringResource(ShelfArts.getValue(p.shelf).label) + if (c != null && !area.waiting) " " + (if (c.value < 0) "–" else c.value.toString()) else ""
        // The tag: 16 high, 6 px each side, centred where the board centres it.
        Box(Modifier.offset((p.tagCentre - 60f).dp, (plank + 6f).dp).requiredSize(120.dp, 16.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.boardBox(0xFFFFFDF6, round(2f), listOf(CssShadow(0f, 1f, 2f, 0f, 0x33000000)), clip = false).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Line(text, bt(11f, 700, 14f, tnum = true), INK)
            }
        }
    }
}

/** What the shelves are made of, said plainly under them: names only (tiles), a failed network with a way to try again, or nothing at all. */
@Composable
private fun PantryNote(area: ShopArea, retry: () -> Unit) {
    if (area.error != null && area.places.isEmpty()) {
        Box(Modifier.offset(0.dp, 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
            StateMessage(
                "ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C),
                action = { PillButton(onClick = retry) { Text(stringResource(R.string.shop_try_again), style = Atlas.type.label) } },
            )
        }
    } else if (area.sketchOnly && area.places.isNotEmpty()) {
        Line(
            stringResource(R.string.shop_sketch_note), bt(13f, 400, 17f, align = TextAlign.Center), INK_SOFT,
            Modifier.offset(24.dp, 736.dp).width(364.dp), lines = 3,
        )
    }
}
