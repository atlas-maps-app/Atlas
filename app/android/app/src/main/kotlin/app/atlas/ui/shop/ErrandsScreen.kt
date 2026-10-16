package app.atlas.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRepeatingLinear
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

    Box(Modifier.fillMaxSize().boardBox(grad = listOf(cssLinear(180f, 0f to 0xFFE9EAEC, 1f to 0xFFDCDEE2)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 110.dp)) {
            Box(Modifier.fillMaxWidth().height(448.dp)) {
                Box(
                    Modifier.at(16f, 42f, 44f, 44f).boardBox(0xFFFFFFFF, round(22f)).tap(stringResource(R.string.shop_find_back)) { nav.back() },
                    contentAlignment = Alignment.Center,
                ) { Sym("ui.back", 20f, 20f) }
                Column(Modifier.offset(0.dp, 46.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Block(stringResource(R.string.shop_errands), bt(36f, 400, 36f, SERIF), INK, Modifier.semantics { heading() })
                    val sub = when {
                        picks.isEmpty() -> stringResource(R.string.shop_errands_none)
                        plan?.openTill != null -> stringResource(R.string.shop_errands_picked, picks.size, plan.openTill.hm())
                        else -> stringResource(R.string.shop_errands_picked_open, picks.size)
                    }
                    Line(sub, bt(13f, 400, 17f, tnum = true), SOFT)
                }
                KeyRing(picks, plan, toggle)
                MoreErrands(picks, toggle)
            }
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .boardBox(0xFFFFFFFF, round(22f), listOf(CssShadow(0f, 8f, 20f, 0f, 0x1A181C30)), clip = false).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    picks.isEmpty() -> Line(stringResource(R.string.shop_errands_none), bt(13f, 400, 17f), SOFT, lines = 2)
                    plan == null -> Line(stringResource(R.string.shop_errand_loading), bt(13f, 400, 17f), SOFT, lines = 2)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    else -> {
                        plan.stops.forEachIndexed { i, s ->
                            // The dotted line between stops: 2 × 10, 3 px on and 3 off, under the discs' centres.
                            if (i > 0) Box(Modifier.padding(start = 15.dp).size(2.dp, 10.dp).boardBox(grad = listOf(cssRepeatingLinear(180f, 6f, 0f to 0xFFB5B9C2, 3f to 0xFFB5B9C2, 3f to 0x00000000, 6f to 0x00000000))))
                            StopRow(i + 1, s, area, now) { nav.go(Dest.Place.of(s.place.place).also { services.remember(s.place.place) }) }
                        }
                        for (m in plan.missing) {
                            Row(Modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(32.dp).boardBox(r = round(16f), border = ring(2f, m.colour)), contentAlignment = Alignment.Center) { Line("?", bt(14f, 700, 18f), m.colour) }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Line(stringResource(m.label), bt(16f, 700, 20.5f), INK)
                                    Line(stringResource(R.string.shop_errand_nowhere), bt(13f, 400, 17f), RED)
                                }
                            }
                        }
                        if (plan.late) Line(stringResource(R.string.shop_errand_late), bt(12f, 600, 15.5f), RED, lines = 2)
                    }
                }
            }
        }
        val p = plan
        if (p != null && p.stops.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 26.dp + boardLift()).fillMaxWidth().height(56.dp)
                    .boardBox(0xFF2E5BFF, round(28f))
                    .clickable(role = Role.Button) {
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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("mode.walk", 20f, 20f, 0xFFFFFFFF)
                Line(stringResource(R.string.shop_errand_go, p.minutes, p.doneAt.hm()), bt(16f, 600, 20.5f, tnum = true), 0xFFFFFFFF)
            }
        }
    }
}

private const val SOFT: Long = 0xFF464C5A
private const val RED: Long = 0xFFB03A3A

/** How the board hangs five fobs: turned about the top of the ring, from 14° on the left to -17° on the right. */
private val FiveAngles = floatArrayOf(14f, 6f, -2f, -9f, -17f)

/**
 * The key ring (the board's steel ring, 140 × 60 at x 136, y 104) and a fob per errand from y 138, 64 wide and 4
 * apart, centred, hanging in a fan; the numbers are the stops, "+" the ones not picked.
 */
@Composable
private fun KeyRing(picks: List<Errand>, plan: ErrandPlan?, toggle: (Errand) -> Unit) {
    val shown = (Errand.ring + picks.filterNot { it in Errand.ring }).take(8)
    val n = shown.size
    BoardArt(ShopBoards.KEY_RING, Modifier.offset(136.dp, 104.dp))
    Row(Modifier.offset(0.dp, 138.dp).fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        shown.forEachIndexed { i, e ->
            val angle = if (n == 5) FiveAngles[i] else if (n == 1) 0f else 14f - (31f * i / (n - 1))
            val on = e in picks
            val order = plan?.stops?.indexOfFirst { it.errand == e }?.takeIf { it >= 0 }?.plus(1)
            // Five fit as the board hangs them (64 wide, the tag 56); more hang narrower, 44 wide with 36 px tags.
            Fob(e, on, order, plan?.missing?.contains(e) == true, angle, if (n > 5) 44f else 64f) { toggle(e) }
        }
    }
}

/** A fob: its 14 px ring, then the 56 × 150 tag (10 10 28 28 corners, a 3 px light inset rim, a drop shadow) with the symbol and the stop. */
@Composable
private fun Fob(e: Errand, on: Boolean, order: Int?, missing: Boolean, angle: Float, width: Float, onClick: () -> Unit) {
    val name = stringResource(e.label)
    Column(
        Modifier.width(width.dp).height(190.dp)
            .graphicsLayer { rotationZ = angle; transformOrigin = TransformOrigin(0.5f, 0f) }
            .alpha(if (on) 1f else 0.55f)
            .tap(stringResource(if (on) R.string.shop_errand_remove else R.string.shop_errand_add, name), onClick).semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(14.dp).boardBox(r = round(7f), border = ring(3f, 0xFF9DA3AC)))
        Column(
            Modifier.width((width - 8f).dp).height(150.dp)
                .boardBox(e.colour, CssCorners(10f, 10f, 28f, 28f), listOf(CssShadow(0f, 0f, 0f, 3f, 0x40FFFFFF, inset = true), CssShadow(0f, 6f, 10f, 0f, 0x33000000)), clip = false),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            val sym = e.symbol
            if (sym != null) Sym(sym, 28f, 28f, 0xFFFFFFFF)
            else Line("$", bt(26f, 900, 34f), 0xFFFFFFFF)
            Line(if (missing) "?" else order?.toString() ?: "+", bt(11f, 700, 14f, MONO), 0xFFFFFFFF)
        }
    }
}

/** More errands: 44 px white circles, 6 apart, centred at y 380; ink when picked. */
@Composable
private fun MoreErrands(picks: List<Errand>, toggle: (Errand) -> Unit) {
    Row(Modifier.offset(0.dp, 380.dp).fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        for (e in Errand.more) {
            val on = e in picks
            val name = stringResource(e.label)
            Box(
                Modifier.size(44.dp).boardBox(if (on) INK else 0xFFFFFFFF, round(22f))
                    .tap(stringResource(if (on) R.string.shop_errand_remove else R.string.shop_errand_add, name)) { toggle(e) }.semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) { Sym(e.symbol ?: "place.shop", 20f, 20f, if (on) 0xFFFFFFFF else INK) }
        }
    }
}

/** A stop: 62 high, the numbered 32 px disc in the errand's colour, the place and its hours (red when it closes first), the walk. */
@Composable
private fun StopRow(n: Int, s: ErrandStop, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(62.dp).tap(s.place.name, onClick), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).boardBox(s.errand.colour, round(16f)), contentAlignment = Alignment.Center) {
            Line(n.toString(), bt(14f, 700, 18f), 0xFFFFFFFF)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(s.place.name, bt(16f, 700, 20.5f), INK)
            if (s.first && s.closesAt != null) {
                Line(stringResource(R.string.shop_errand_closes_first, s.closesAt.hm()), bt(13f, 700, 17f, tnum = true), RED)
            } else {
                val line = openLine(s.place, area, now)
                Line(listOfNotNull(line?.first, stringResource(s.errand.label).takeIf { s.place.name != stringResource(s.errand.label) }).joinToString(" · "), bt(13f, 400, 17f, tnum = true), SOFT)
            }
        }
        Line("${s.walkMinutes}′", bt(18f, 800, 23.5f, tnum = true), INK)
    }
}
