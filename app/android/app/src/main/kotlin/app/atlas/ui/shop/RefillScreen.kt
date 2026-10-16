package app.atlas.ui.shop

import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.ToggleChip
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.shortDistance
import java.util.Locale


private class JarArt(val kind: RefillKind, val symbol: String, val w: Int, val h: Int, val label: Int, val desc: Int)

private val Jars = listOf(
    JarArt(RefillKind.BULK, "refill.bulk", 66, 140, R.string.shop_refill_bulk, R.string.shop_refill_bulk_d),
    JarArt(RefillKind.SOAP, "refill.soap", 60, 126, R.string.shop_refill_soap, R.string.shop_refill_soap_d),
    JarArt(RefillKind.WATER, "refill.water", 56, 136, R.string.shop_refill_water, R.string.shop_refill_water_d),
    JarArt(RefillKind.RETURN, "refill.return", 66, 120, R.string.shop_refill_return, R.string.shop_refill_return_d),
    JarArt(RefillKind.VEND, "refill.vending", 58, 120, R.string.shop_refill_vend, R.string.shop_refill_vend_d),
)

/**
 * Refill, as on the Refill board: five jars on a tile wall (bulk, soap, water, return, vend) with how many are near, the
 * nearest free water on a teal card with an arrow that turns as you do, filters, a row per place with a jar that is as
 * full as the place stays open today, and your containers with their tare. From OpenStreetMap's `bulk_purchase`,
 * `zero_waste` and `drinking_water` tags.
 */
@Composable
internal fun RefillScreen(nav: Navigator, start: RefillKind? = null) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberLiveHere()
    val now = rememberLiveNow()
    val lang = remember { Locale.getDefault().language }
    val area by produceState(ShopArea.empty(here), here) { repo.area("refill", RefillKind.all, here, nameless = true).collect { value = it } }
    var picked by rememberSaveable { mutableStateOf<String?>(start?.name) }
    var filters by rememberSaveable { mutableStateOf(setOf(RefillFilter.OWN.name, RefillFilter.NOW.name)) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val chosen = filters.mapNotNull { n -> RefillFilter.entries.firstOrNull { it.name == n } }.toSet()
    // Until a jar is tapped, the first one that has something near: bulk if it does, else the next.
    val first = remember(area, now) { Refills.view(RefillKind.BULK, area, now, emptySet(), lang).counts.let { c -> RefillKind.entries.firstOrNull { (c[it] ?: 0) > 0 } ?: RefillKind.BULK } }
    val kind = picked ?: first.name
    val view = remember(area, now, kind, chosen) { Refills.view(RefillKind.valueOf(kind), area, now, chosen, lang) }
    val containers by repo.containers.items.collectAsStateWithLifecycle()
    val heading by rememberHeading()
    val water = remember(area) {
        area.places.filter { p -> RefillKind.WATER.filter.matches(p.tags) && p.tags["access"] !in setOf("private", "no", "customers") && p.tags["drinking_water"] != "no" && p.tags["amenity"] == "drinking_water" }
            .minByOrNull { it.distanceM }
    }

    Box(Modifier.fillMaxSize().background(argb(WALL)).drawTiles()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop())) {
            Box(Modifier.fillMaxWidth().height(304.dp)) {
                Box(
                    Modifier.at(16f, 42f, 44f, 44f).boardBox(0xFFFFFFFF, round(22f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x14000000))).tap(stringResource(R.string.shop_back_pantry)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.back", 20f, 20f, INK_R) }
                // The title and its line, each on a strip of the wall so the grout stops behind them: top 48, 2 apart.
                Column(Modifier.offset(0.dp, 48.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Block(stringResource(R.string.shop_refill), bt(34f, 400, 34f, SERIF), INK_R, Modifier.background(argb(WALL)).padding(horizontal = 10.dp).semantics { heading() })
                    Line(stringResource(R.string.shop_refill_sub, view.total), bt(13f, 400, 17f, tnum = true), SOFT, Modifier.background(argb(WALL)).padding(horizontal = 8.dp))
                }
                Jars(view, kind) { picked = it.name }
                // The glass shelf they stand on: 8 px from the sides at y 292, over the jars' feet.
                Box(
                    Modifier.at(8f, 292f, 396f, 12f).boardBox(
                        r = round(3f), shadows = listOf(CssShadow(0f, 6f, 10f, 0f, 0x1F000000)),
                        grad = listOf(cssLinear(180f, 0f to 0xFFE6E9EB, 1f to 0xFFB9C0C5)), clip = false,
                    ),
                )
            }
            // The jars' pills hang 18 px below the shelf; the water card starts at 350.
            Spacer(Modifier.height(46.dp))
            WaterCard(water, heading, here, area)
            Spacer(Modifier.height(20.dp))
            Filters(view, chosen) { f -> filters = if (f.name in filters) filters - f.name else filters + f.name }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    area.waiting -> Line(stringResource(R.string.shop_loading), bt(13f, 400, 17f), SOFT)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    view.rows.isEmpty() -> StateMessage(
                        "refill.jar",
                        stringResource(if (chosen.isEmpty()) R.string.shop_refill_none else R.string.shop_refill_none_match),
                    )
                    else -> view.rows.take(30).forEach { r -> RefillCard(r, area, now) { services.remember(r.place.place); nav.go(Dest.Place.of(r.place.place)) } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Containers(containers, onAdd = { adding = true }, onRemove = { repo.containers.remove(it) })
            Spacer(Modifier.height(22.5.dp))
            Line(
                stringResource(R.string.shop_refill_source), bt(10f, 400, 13.5f, align = TextAlign.Center), 0xFF5A6A6E,
                Modifier.padding(horizontal = 24.dp).fillMaxWidth().background(argb(WALL)), lines = 3,
            )
            Spacer(Modifier.height(16.dp + boardLift()))
        }
    }
    if (adding) AddContainerDialog(onDone = { c -> adding = false; if (c != null) repo.containers.add(c) })
}

private const val INK_R: Long = 0xFF1C2A2E
private const val SOFT: Long = 0xFF4A5A5E
private const val WALL: Long = 0xFFF7F8F6
private const val GROUT: Long = 0xFFD9DDDA

/**
 * The tile wall, as the board's two background gradients: a 1.5 px grout line at the foot of every 29 px row and at
 * the left of every 58 px column.
 */
private fun Modifier.drawTiles(): Modifier = drawBehind {
    val w = 1.5.dp.toPx()
    val grout = argb(GROUT)
    var y = 29.dp.toPx() - w
    while (y < size.height) { drawRect(grout, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, w)); y += 29.dp.toPx() }
    var x = 0f
    while (x < size.width) { drawRect(grout, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)); x += 58.dp.toPx() }
}

/**
 * The five jars, as the board's row: 10 px from the sides from y 142, each a 74 × 180 button that stands its drawing
 * on a 22 px pill, 6 px apart (the pill ink when chosen, else white with a 1 px grout ring; Arial, as the board's
 * buttons have it).
 */
@Composable
private fun Jars(view: RefillView, selected: String, onPick: (RefillKind) -> Unit) {
    Row(Modifier.offset(0.dp, 142.dp).fillMaxWidth().padding(horizontal = 10.dp).height(180.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (j in Jars) {
            val on = j.kind.name == selected
            val label = stringResource(j.label)
            val count = view.counts[j.kind] ?: 0
            Column(
                Modifier.width(74.dp).fillMaxHeight()
                    .tap(stringResource(R.string.shop_refill_kind_count, stringResource(j.desc), count)) { onPick(j.kind) }.semantics { this.selected = on },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                Sym(j.symbol, j.w.toFloat(), j.h.toFloat(), 0xFF000000)
                Box(
                    Modifier.height(22.dp).boardBox(if (on) INK_R else 0xFFFFFFFF, round(11f), if (on) emptyList() else listOf(CssShadow(0f, 0f, 0f, 1f, GROUT)), clip = false)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Line("$label $count", bt(12f, 700, 13.5f, "Arial", tnum = true), if (on) 0xFFFFFFFF else INK_R) }
            }
        }
    }
}

/** The nearest free water: a teal card 86 high (24 px corners), a 54 px disc, the distance, and an arrow that turns. */
@Composable
private fun WaterCard(water: ShopPlace?, heading: Float?, here: app.atlas.geo.LatLon, area: ShopArea) {
    if (water == null) {
        if (!area.waiting) Line(
            stringResource(R.string.shop_refill_no_water), bt(13f, 400, 17f), SOFT,
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().boardBox(0xFFFFFFFF, round(24f)).padding(16.dp), lines = 3,
        )
        return
    }
    val bearing = Geo.bearing(here, water.location).toFloat()
    val dist = shortDistance(Geo.distance(here, water.location))
    val seasonal = water.tags["seasonal"]?.let { it != "no" } == true
    val free = water.tags["fee"] != "yes"
    val sub = stringResource(
        when {
            !free -> R.string.shop_refill_paid
            seasonal -> R.string.shop_refill_free_seasonal
            water.tags["opening_hours"] == null || water.alwaysOpen -> R.string.shop_refill_free_always
            else -> R.string.shop_refill_free
        },
    )
    val cardLabel = stringResource(R.string.shop_refill_water_card, dist)
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(86.dp).boardBox(0xFF1B6F8A, round(24f)).padding(horizontal = 16.dp)
            .semantics { contentDescription = cardLabel },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp).boardBox(0x24FFFFFF, round(27f)), contentAlignment = Alignment.Center) { Sym("need.water.2", 28f, 28f, 0xFFFFFFFF) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Block(dist, bt(30f, 800, 30f, tnum = true), 0xFFFFFFFF)
            Line(sub, bt(13f, 400, 17f), 0xFFCFE8F1)
        }
        // The arrow points at the water relative to where the phone faces: straight up when it is dead ahead.
        Sym("ui.arrow-up.2", 44f, 44f, 0xFFFFFFFF, rotate = bearing - (heading ?: 0f))
    }
}

/** The four filters, 8 apart: own jar and now as 40 high pills with words, organic and card as 40 px circles. */
@Composable
private fun Filters(view: RefillView, on: Set<RefillFilter>, onToggle: (RefillFilter) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in RefillFilter.entries) {
            val selected = f in on
            val dim = view.filterCounts[f] == 0 && !selected
            val text = when (f) {
                RefillFilter.OWN -> stringResource(R.string.shop_refill_f_own)
                RefillFilter.NOW -> stringResource(R.string.shop_refill_f_now)
                RefillFilter.ORGANIC -> stringResource(R.string.shop_refill_f_organic)
                RefillFilter.CARD -> stringResource(R.string.shop_refill_f_card)
            }
            val symbol = when (f) { RefillFilter.OWN -> "refill.jar"; RefillFilter.NOW -> "ui.clock"; RefillFilter.ORGANIC -> "fact.vegan.2"; RefillFilter.CARD -> "fact.card" }
            val wide = f == RefillFilter.OWN || f == RefillFilter.NOW
            val fg = if (selected) 0xFFFFFFFF else INK_R
            Row(
                Modifier.height(40.dp).then(if (wide) Modifier else Modifier.width(40.dp)).alpha(if (dim) 0.45f else 1f)
                    .boardBox(if (selected) INK_R else 0xFFFFFFFF, round(20f), if (selected) emptyList() else listOf(CssShadow(0f, 0f, 0f, 1f, GROUT, inset = true)))
                    .tap(text) { onToggle(f) }.semantics { this.selected = selected }
                    .then(if (wide) Modifier.padding(start = 10.dp, end = 12.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                val side = if (wide) 16f else 17f
                Sym(symbol, side, side, fg)
                if (wide) Line(text, bt(13f, 600, 17f), fg)
            }
        }
    }
}

/** A place on the list (80 high, 20 px corners): a jar filled as far as the place stays open today, its name, its closing time and the walk. */
@Composable
private fun RefillCard(r: RefillRow, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val colour = when (r.kind) {
        RefillKind.BULK -> 0xFFE8913A
        RefillKind.SOAP -> 0xFF8FC6A2
        RefillKind.WATER -> 0xFF6FB7EA
        RefillKind.RETURN -> 0xFF3E8A5A
        RefillKind.VEND -> 0xFFB9C0C7
    }
    Row(
        Modifier.fillMaxWidth().height(80.dp)
            .boardBox(0xFFFFFFFF, round(20f), listOf(CssShadow(0f, 2f, 10f, 0f, 0x0F000000))).tap(r.place.name, onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.kind == RefillKind.RETURN) {
            Box(Modifier.size(50.dp, 58.dp).boardBox(0xFFB9C0C7, round(8f)), contentAlignment = Alignment.Center) { Sym("fact.second-hand", 26f, 26f, 0xFF1E7A46) }
        } else {
            // The jar: glass (6 6 10 10 corners, a 2 px inset rim) filled from the bottom.
            Box(Modifier.size(50.dp, 58.dp).boardBox(0x99D7E8EE, CssCorners(6f, 6f, 10f, 10f)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(r.fill.coerceIn(0f, 1f)).background(argb(colour)))
                Box(Modifier.matchParentSize().boardBox(r = CssCorners(6f, 6f, 10f, 10f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF9FB9C3, inset = true))))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line(r.place.name, bt(17f, 700, 22f), INK_R)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val st = bt(13f, 400, 17f, tnum = true)
                openLine(r.place, area, now)?.let { (t, _) -> Line(t, st, SOFT) }
                listOfNotNull(r.tagline, stringResource(R.string.shop_refill_f_organic).lowercase().takeIf { r.organic }).joinToString(" · ").ifEmpty { null }
                    ?.let { Line(it, st, SOFT, Modifier.weight(1f, fill = false)) }
            }
        }
        Line("${r.place.walkMinutes}′", bt(22f, 800, 28.5f, tnum = true), INK_R)
    }
}

/** Your containers: the spaced capitals on a strip of wall, then a 36 high chip per container (tare in grams) and one to add. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Containers(items: List<Container>, onAdd: () -> Unit, onRemove: (Container) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Line(stringResource(R.string.shop_refill_containers), bt(12f, 800, 15.5f, spacing = 1.44f), SOFT, Modifier.background(argb(WALL)).padding(end = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in items) {
                val removeLabel = stringResource(R.string.shop_container_remove, c.name)
                Row(
                    Modifier.height(36.dp).boardBox(0xFFFFFFFF, round(18f), listOf(CssShadow(0f, 2f, 8f, 0f, 0x0F000000)))
                        .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(c); true }) }
                        // Held down to remove: a stray tap must not lose a tare somebody weighed.
                        .combinedClickable(onClickLabel = null, onLongClickLabel = removeLabel, onLongClick = { onRemove(c) }, onClick = {})
                        .padding(start = 8.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).boardBox(0xFFE3F0F2, round(6f)), contentAlignment = Alignment.Center) { Sym("fact.container", 14f, 14f, 0xFF1B6B7A) }
                    Line(c.name, bt(13f, 700, 17f), INK_R)
                    Line("${c.tareGrams} g", bt(13f, 600, 17f, tnum = true), SOFT)
                }
            }
            Row(
                Modifier.height(36.dp).boardBox(0, round(18f), listOf(CssShadow(0f, 0f, 0f, 1.5f, 0xFF9FB9C3, inset = true)))
                    .tap(stringResource(R.string.shop_refill_add), onAdd).padding(start = 8.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Sym("ui.plus", 14f, 14f, SOFT) }
                Line(stringResource(R.string.shop_refill_add), bt(13f, 600, 17f), SOFT)
            }
        }
        if (items.isEmpty()) Line(stringResource(R.string.shop_refill_add_hint), bt(12f, 400, 15.5f), SOFT, lines = 3)
    }
}

@Composable
private fun AddContainerDialog(onDone: (Container?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("jar") }
    var tare by rememberSaveable { mutableStateOf("") }
    val grams = tare.toIntOrNull()?.takeIf { it in 1..5000 }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.shop_refill_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("jar" to R.string.shop_container_jar, "bottle" to R.string.shop_container_bottle, "tin" to R.string.shop_container_tin, "box" to R.string.shop_container_box).forEach { (k, r) ->
                        ToggleChip(stringResource(r), kind == k, { kind = k })
                    }
                }
                OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text(stringResource(R.string.shop_container_name)) })
                OutlinedTextField(
                    tare, { tare = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text(stringResource(R.string.shop_container_tare)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = grams != null, onClick = { onDone(Container(name.trim().ifEmpty { kind }, kind, grams!!)) }) { Text(stringResource(R.string.shop_note_save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.shop_cancel)) } },
    )
}
