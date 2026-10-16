package app.atlas.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.horizontalScroll
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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.ToggleChip
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.shortDistance
import java.util.Locale

private val Ink = Color(0xFF1C2A2E)
private val Soft = Color(0xFF4A5A5E)
private val Wall = Color(0xFFF7F8F6)
private val Grout = Color(0xFFD9DDDA)
private val Teal = Color(0xFF1B6F8A)

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

    Box(
        Modifier.fillMaxSize().background(Wall).drawBehind {
            // The tile wall: grout lines 1.5 px every 58 px across and every 29 px down.
            val w = 1.5.dp.toPx()
            var y = 0f
            while (y < size.height) { drawRect(Grout, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, w)); y += 29.dp.toPx() }
            var x = 0f
            while (x < size.width) { drawRect(Grout, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)); x += 58.dp.toPx() }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 2.dp, bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(start = 16.dp)) { RoundSymbolButton("ui.back", stringResource(R.string.shop_back_pantry), { nav.back() }, size = 44.dp, fill = Color.White, tint = Ink, iconSize = 20.dp) }
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.shop_refill), Modifier.background(Wall).padding(horizontal = 10.dp), style = TextStyle(fontFamily = SerifFamily, fontSize = 34.sp, lineHeight = 34.sp), color = Ink)
                    Text(
                        stringResource(R.string.shop_refill_sub, view.total),
                        Modifier.background(Wall).padding(horizontal = 8.dp),
                        style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Soft,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Jars(view, kind) { picked = it.name }
            Box(
                Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(12.dp)
                    .cssShadow(RoundedCornerShape(3.dp), y = 6.dp, blur = 10.dp, colour = Color(0x1F000000))
                    .background(Brush.verticalGradient(listOf(Color(0xFFE6E9EB), Color(0xFFB9C0C5))), RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(46.dp))
            WaterCard(water, heading, here, area)
            Spacer(Modifier.height(20.dp))
            Filters(view, chosen) { f -> filters = if (f.name in filters) filters - f.name else filters + f.name }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    area.waiting -> Text(stringResource(R.string.shop_loading), style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp), color = Soft)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    view.rows.isEmpty() -> StateMessage(
                        "refill.jar",
                        stringResource(if (chosen.isEmpty()) R.string.shop_refill_none else R.string.shop_refill_none_match),
                    )
                    else -> view.rows.take(30).forEach { r -> RefillCard(r, area, now) { services.remember(r.place.place); nav.go(Dest.Place.of(r.place.place)) } }
                }
            }
            Spacer(Modifier.height(18.dp))
            Containers(containers, onAdd = { adding = true }, onRemove = { repo.containers.remove(it) })
            Text(
                stringResource(R.string.shop_refill_source),
                Modifier.padding(horizontal = 24.dp, vertical = 14.dp).fillMaxWidth().background(Wall),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, lineHeight = 13.5.sp), color = Color(0xFF5A6A6E), textAlign = TextAlign.Center,
            )
        }
    }
    if (adding) AddContainerDialog(onDone = { c -> adding = false; if (c != null) repo.containers.add(c) })
}

@Composable
private fun Jars(view: RefillView, selected: String, onPick: (RefillKind) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(180.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        for (j in Jars) {
            val on = j.kind.name == selected
            val label = stringResource(j.label)
            val count = view.counts[j.kind] ?: 0
            Column(
                Modifier.width(74.dp).fillMaxHeight()
                    .tap(stringResource(R.string.shop_refill_kind_count, stringResource(j.desc), count)) { onPick(j.kind) }.semantics { this.selected = on },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                BoardSymbol(j.symbol, j.w.dp, j.h.dp)
                Box(
                    Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Ink else Color.White)
                        .then(if (on) Modifier else Modifier.border(1.dp, Grout, RoundedCornerShape(11.dp)))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$label $count",
                        style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFeatureSettings = "tnum"),
                        color = if (on) Color.White else Ink, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun WaterCard(water: ShopPlace?, heading: Float?, here: app.atlas.geo.LatLon, area: ShopArea) {
    if (water == null) {
        if (!area.waiting) Text(
            stringResource(R.string.shop_refill_no_water),
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(16.dp),
            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp), color = Soft,
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
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(86.dp).clip(RoundedCornerShape(24.dp)).background(Teal).padding(horizontal = 16.dp)
            .semantics { contentDescription = cardLabel },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp).background(Color.White.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { Symbol("need.water.2", tint = Color.White, size = 28.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(dist, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 30.sp, fontFeatureSettings = "tnum"), color = Color.White)
            Text(sub, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp), color = Color(0xFFCFE8F1))
        }
        // The arrow points at the water relative to where the phone faces: straight up when it is dead ahead.
        Symbol("ui.arrow-up.2", Modifier.rotate(bearing - (heading ?: 0f)), tint = Color.White, size = 44.dp)
    }
}

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
            val symbol = when (f) { RefillFilter.OWN -> "fact.container"; RefillFilter.NOW -> "ui.clock"; RefillFilter.ORGANIC -> "fact.vegan.2"; RefillFilter.CARD -> "fact.card" }
            val wide = f == RefillFilter.OWN || f == RefillFilter.NOW
            Row(
                Modifier.height(40.dp).alpha(if (dim) 0.45f else 1f).clip(RoundedCornerShape(20.dp)).background(if (selected) Ink else Color.White)
                    .then(if (selected) Modifier else Modifier.border(1.dp, Grout, RoundedCornerShape(20.dp)))
                    .tap(text) { onToggle(f) }.semantics { this.selected = selected }
                    .padding(start = if (wide) 10.dp else 11.dp, end = if (wide) 12.dp else 11.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(symbol, tint = if (selected) Color.White else if (dim) Soft.copy(alpha = 0.4f) else Ink, size = if (wide) 16.dp else 17.dp)
                if (wide) Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = Color.White)
            }
        }
    }
}

/** A place on the list: a jar filled as far as the place stays open today, its name, its closing time and the walk. */
@Composable
private fun RefillCard(r: RefillRow, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val colour = when (r.kind) {
        RefillKind.BULK -> Color(0xFFE8913A)
        RefillKind.SOAP -> Color(0xFF8FC6A2)
        RefillKind.WATER -> Color(0xFF6FB7EA)
        RefillKind.RETURN -> Color(0xFF3E8A5A)
        RefillKind.VEND -> Color(0xFFB9C0C7)
    }
    Row(
        Modifier.fillMaxWidth().height(80.dp)
            .cssShadow(RoundedCornerShape(20.dp), y = 2.dp, blur = 10.dp, colour = Color(0x0F000000))
            .clip(RoundedCornerShape(20.dp)).background(Color.White).tap(r.place.name, onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.kind == RefillKind.RETURN) {
            Box(Modifier.size(50.dp, 58.dp).background(Color(0xFFB9C0C7), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Symbol("fact.second-hand", size = 26.dp) }
        } else {
            Box(
                Modifier.size(50.dp, 58.dp).clip(RoundedCornerShape(6.dp, 6.dp, 10.dp, 10.dp)).background(Color(0x99D7E8EE)).border(2.dp, Color(0xFF9FB9C3), RoundedCornerShape(6.dp, 6.dp, 10.dp, 10.dp)),
                contentAlignment = Alignment.BottomCenter,
            ) { Box(Modifier.fillMaxWidth().fillMaxHeight(r.fill.coerceIn(0f, 1f)).background(colour)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(r.place.name, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val st = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum")
                openLine(r.place, area, now)?.let { (t, _) -> Text(t, style = st, color = Soft, maxLines = 1) }
                listOfNotNull(r.tagline, stringResource(R.string.shop_refill_f_organic).lowercase().takeIf { r.organic }).joinToString(" · ").ifEmpty { null }
                    ?.let { Text(it, Modifier.weight(1f, fill = false), style = st, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Text("${r.place.walkMinutes}′", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, fontFeatureSettings = "tnum"), color = Ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Containers(items: List<Container>, onAdd: () -> Unit, onRemove: (Container) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.shop_refill_containers),
            Modifier.background(Wall).padding(end = 6.dp),
            style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.4.sp), color = Soft,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in items) {
                val removeLabel = stringResource(R.string.shop_container_remove, c.name)
                Row(
                    Modifier.height(36.dp).cssShadow(RoundedCornerShape(18.dp), y = 2.dp, blur = 8.dp, colour = Color(0x0F000000)).background(Color.White, RoundedCornerShape(18.dp))
                        .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(c); true }) }
                        // Held down to remove: a stray tap must not lose a tare somebody weighed.
                        .combinedClickable(onClickLabel = null, onLongClickLabel = removeLabel, onLongClick = { onRemove(c) }, onClick = {})
                        .padding(start = 8.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).background(Color(0xFFE3F0F2), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Symbol("fact.container", tint = Color(0xFF1B6B7A), size = 14.dp) }
                    Text(c.name, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = Ink, maxLines = 1)
                    Text("${c.tareGrams} g", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Soft, maxLines = 1)
                }
            }
            Row(
                Modifier.height(36.dp).border(1.5.dp, Color(0xFF9FB9C3), RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).tap(stringResource(R.string.shop_refill_add), onAdd).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ui.plus", tint = Soft, size = 14.dp)
                Text(stringResource(R.string.shop_refill_add), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = Soft, maxLines = 1)
            }
        }
        if (items.isEmpty()) Text(stringResource(R.string.shop_refill_add_hint), style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp), color = Soft)
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
