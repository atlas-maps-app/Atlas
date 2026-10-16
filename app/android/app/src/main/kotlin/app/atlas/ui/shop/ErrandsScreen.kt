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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF464C5A)

internal val Errand.label: Int
    get() = when (this) {
        Errand.PHARMACY -> R.string.shop_errand_pharmacy
        Errand.PARCEL -> R.string.shop_errand_parcel
        Errand.CASH -> R.string.shop_errand_cash
        Errand.CLEANING -> R.string.shop_errand_cleaning
        Errand.POST -> R.string.shop_errand_post
        Errand.LAUNDRY -> R.string.shop_errand_laundry
        Errand.KEYS -> R.string.shop_errand_keys
        Errand.PRINTING -> R.string.shop_errand_printing
        Errand.SHOE_REPAIR -> R.string.shop_errand_shoes
        Errand.TAILOR -> R.string.shop_errand_tailor
        Errand.RECYCLING -> R.string.shop_errand_recycling
    }

internal val Errand.symbol: String?
    get() = when (this) {
        Errand.PHARMACY -> "place.pharmacy"
        Errand.PARCEL -> "ui.grid"
        Errand.CASH -> null
        Errand.CLEANING -> "place.dry-cleaning"
        Errand.POST -> "ui.mail"
        Errand.LAUNDRY -> "place.laundry"
        Errand.KEYS -> "place.key-cutting"
        Errand.PRINTING -> "place.print"
        Errand.SHOE_REPAIR -> "place.shoe-repair"
        Errand.TAILOR -> "place.tailor"
        Errand.RECYCLING -> "fact.second-hand"
    }

/**
 * Errands, as on the Errands board: a key ring with a coloured fob per errand (pharmacy, parcel locker, cash, dry
 * cleaning, post office, and the round buttons for laundry, keys, printing, shoes, tailor, recycling); tap to add one
 * to the walk. The walk is solved for you: each errand at one of its nearest open places, in the order that gets
 * everything done soonest and keeps every closing time, with the number of the stop on each fob. Exact for up to ten
 * stops, a good order beyond. "Go" walks it.
 */
@Composable
fun ErrandsScreen(nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    var picked by rememberSaveable { mutableStateOf(listOf(Errand.PHARMACY.name, Errand.PARCEL.name, Errand.CASH.name, Errand.CLEANING.name)) }
    val picks = picked.mapNotNull { n -> Errand.entries.firstOrNull { it.name == n } }
    val area by produceState(ShopArea.empty(here, 1_500.0), here) { repo.area("errands", Errand.all, here, 1_500.0, nameless = true).collect { value = it } }
    // Up to 3^6 combinations are walked: off the main thread.
    var planned by remember { mutableStateOf<ErrandPlan?>(null) }
    LaunchedEffect(area, picked, now) {
        planned = if (area.waiting) null else withContext(Dispatchers.Default) { ErrandsPlanner.plan(picks, area.places, here, now, area.hours) }
    }
    val plan = planned
    val toggle = { e: Errand -> picked = if (e.name in picked) picked - e.name else picked + e.name }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE9EAEC), Color(0xFFDCDEE2))))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 2.dp, bottom = 100.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(start = 16.dp)) { RoundSymbolButton("ui.back", stringResource(R.string.shop_find_back), { nav.back() }, size = 44.dp, fill = Color.White, tint = Ink, iconSize = 20.dp) }
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.shop_errands), style = TextStyle(fontFamily = SerifFamily, fontSize = 36.sp, lineHeight = 36.sp), color = Ink)
                    val sub = when {
                        picks.isEmpty() -> stringResource(R.string.shop_errands_none)
                        plan?.openTill != null -> stringResource(R.string.shop_errands_picked, picks.size, plan.openTill.hm())
                        else -> stringResource(R.string.shop_errands_picked_open, picks.size)
                    }
                    Text(sub, style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Soft)
                }
            }
            Spacer(Modifier.height(14.dp))
            KeyRing(picks, plan, toggle)
            Spacer(Modifier.height(0.dp))
            MoreErrands(picks, toggle)
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .cssShadow(RoundedCornerShape(22.dp), y = 8.dp, blur = 20.dp, colour = Color(0x1A181C30))
                    .background(Color.White, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    picks.isEmpty() -> Text(stringResource(R.string.shop_errands_none), style = TextStyle(fontFamily = SansFamily, fontSize = 14.sp), color = Soft)
                    plan == null -> Text(stringResource(R.string.shop_errand_loading), style = TextStyle(fontFamily = SansFamily, fontSize = 14.sp), color = Soft)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    else -> {
                        plan.stops.forEachIndexed { i, s ->
                            if (i > 0) Box(Modifier.padding(start = 15.dp).size(2.dp, 10.dp).drawBehind {
                                var y = 0f
                                while (y < size.height) { drawRect(Color(0xFFB5B9C2), Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())); y += 6.dp.toPx() }
                            })
                            StopRow(i + 1, s, area, now) { nav.go(Dest.Place.of(s.place.place).also { services.remember(s.place.place) }) }
                        }
                        for (m in plan.missing) {
                            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(32.dp).border(2.dp, Color(m.colour), CircleShape), contentAlignment = Alignment.Center) { Text("?", style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp), color = Color(m.colour)) }
                                Column {
                                    Text(stringResource(m.label), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp), color = Ink)
                                    Text(stringResource(R.string.shop_errand_nowhere), style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp), color = Color(0xFFB03A3A))
                                }
                            }
                        }
                        if (plan.late) Text(stringResource(R.string.shop_errand_late), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = Color(0xFFB03A3A))
                    }
                }
            }
        }
        val p = plan
        if (p != null && p.stops.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp)) {
                BigPill(
                    stringResource(R.string.shop_errand_go, p.minutes, p.doneAt.hm()),
                    onClick = {
                        // Two errands at one place (a parcel and a stamp at the post office) are one call on the route.
                        val calls = p.stops.map { it.place }.fold(emptyList<ShopPlace>()) { acc, s -> if (acc.lastOrNull()?.key == s.key) acc else acc + s }
                        val last = calls.last()
                        nav.go(
                            Dest.Route(
                                last.location.lat, last.location.lon, last.name, mode = "WALK",
                                via = calls.dropLast(1).joinToString("|") { String.format(Locale.ROOT, "%.6f,%.6f,%s", it.location.lat, it.location.lon, it.name.replace('|', ' ')) }.ifEmpty { null },
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Symbol("mode.walk", tint = Color.White, size = 20.dp) },
                )
            }
        }
    }
}

/** The key ring: a steel ring and a fob per errand, hanging in a fan; the numbers are the stops, "+" the ones not picked. */
@Composable
private fun KeyRing(picks: List<Errand>, plan: ErrandPlan?, toggle: (Errand) -> Unit) {
    val shown = (Errand.ring + picks.filterNot { it in Errand.ring }).take(8)
    val n = shown.size
    Box(Modifier.fillMaxWidth().height(270.dp)) {
        BoardArt(ShopBoards.KEY_RING, Modifier.align(Alignment.TopCenter).padding(top = 0.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 34.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            shown.forEachIndexed { i, e ->
                // Fan from +14° on the left to -17° on the right, as the board hangs them.
                val angle = if (n == 1) 0f else 14f - (31f * i / (n - 1))
                val on = e in picks
                val order = plan?.stops?.indexOfFirst { it.errand == e }?.takeIf { it >= 0 }?.plus(1)
                Fob(e, on, order, plan?.missing?.contains(e) == true, angle, Modifier.width(if (n > 5) 52.dp else 64.dp)) { toggle(e) }
            }
        }
    }
}

@Composable
private fun Fob(e: Errand, on: Boolean, order: Int?, missing: Boolean, angle: Float, modifier: Modifier, onClick: () -> Unit) {
    val name = stringResource(e.label)
    Column(
        modifier.height(190.dp)
            .graphicsLayer { rotationZ = angle; transformOrigin = TransformOrigin(0.5f, 0f) }
            .alpha(if (on) 1f else 0.55f)
            .tap(stringResource(if (on) R.string.shop_errand_remove else R.string.shop_errand_add, name), onClick).semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(14.dp).border(3.dp, Color(0xFF9DA3AC), CircleShape))
        Column(
            Modifier.width(56.dp).height(150.dp)
                .cssShadow(RoundedCornerShape(10.dp, 10.dp, 28.dp, 28.dp), y = 6.dp, blur = 10.dp, colour = Color(0x33000000))
                .background(Color(e.colour), RoundedCornerShape(10.dp, 10.dp, 28.dp, 28.dp))
                .border(3.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp, 10.dp, 28.dp, 28.dp)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            val sym = e.symbol
            if (sym != null) Symbol(sym, tint = Color.White, size = 28.dp)
            else Text("$", style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Black, fontSize = 26.sp), color = Color.White)
            Text(
                if (missing) "?" else order?.toString() ?: "+",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp), color = Color.White,
            )
        }
    }
}

@Composable
private fun MoreErrands(picks: List<Errand>, toggle: (Errand) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        for (e in Errand.more) {
            val on = e in picks
            val name = stringResource(e.label)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (on) Ink else Color.White)
                    .tap(stringResource(if (on) R.string.shop_errand_remove else R.string.shop_errand_add, name)) { toggle(e) }.semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) { Symbol(e.symbol ?: "place.shop", tint = if (on) Color.White else Ink, size = 20.dp) }
        }
    }
}

@Composable
private fun StopRow(n: Int, s: ErrandStop, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(62.dp).tap(s.place.name, onClick), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(Color(s.errand.colour), CircleShape), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp), color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.place.name, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val st = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, fontFeatureSettings = "tnum")
            if (s.first && s.closesAt != null) {
                Text(stringResource(R.string.shop_errand_closes_first, s.closesAt.hm()), style = st.copy(fontWeight = FontWeight.Bold), color = Color(0xFFB03A3A), maxLines = 1)
            } else {
                val line = openLine(s.place, area, now)
                Text(listOfNotNull(line?.first, stringResource(s.errand.label).takeIf { s.place.name != stringResource(s.errand.label) }).joinToString(" · "), style = st, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("${s.walkMinutes}′", style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, fontFeatureSettings = "tnum"), color = Ink)
    }
}
