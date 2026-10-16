package app.atlas.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.guide.Nearby
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.go.CompassHeadings
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.shortDistance

/** How one need looks: the board's colours (ARGB), its compass, and the words it uses. */
private class NeedLook(
    val bg: Long, val ink: Long, val soft: Long, val accent: Long, val chipBg: Long, val chipInk: Long, val altBg: Long,
    val dial: String, val dialSize: Float, val distanceSize: Float, val unitSize: Float,
    val title: Int, val kind: Int, val badgeSymbol: String?, val badgeW: Float, val badgeH: Float,
)

private fun look(kind: NeedKind) = when (kind) {
    NeedKind.TOILET -> NeedLook(
        0xFFF0EDFB, 0xFF1B1440, 0xFF5A5288, 0xFF4B34B8, 0xFFEDE9FF, 0xFF4B34B8, 0xB2FFFFFF,
        ShopBoards.COMPASS_TOILET, 340f, 64f, 30f, R.string.shop_need_toilet_t, R.string.shop_need_kind_toilet, null, 0f, 0f,
    )
    NeedKind.WATER -> NeedLook(
        0xFFEAF3FB, 0xFF0E2A44, 0xFF3E5A74, 0xFF1560A8, 0xFFE4F0FB, 0xFF1560A8, 0xBFFFFFFF,
        ShopBoards.COMPASS_WATER, 340f, 64f, 30f, R.string.shop_need_water_t, R.string.shop_need_kind_water, "need.water", 24f, 24f,
    )
    NeedKind.FOOD -> NeedLook(
        0xFFFBEFE6, 0xFF3A1A08, 0xFF7A4A2A, 0xFFA8420E, 0xFFFBEFE6, 0xFFA8420E, 0xFFFFFFFF,
        ShopBoards.COMPASS_FOOD, 300f, 56f, 26f, R.string.shop_need_food_t, R.string.shop_need_kind_food, "need.food", 26f, 24f,
    )
}

/**
 * A need, pointed at (the Toilet, Water and Food boards): a big compass whose arrow turns to the nearest place that
 * fits, the distance in large figures and the minutes on foot, what the tags say about it (free, step-free, baby
 * changing, drinkable, off for the winter), the next few places with the way to each, and "Guide me by voice", which
 * starts the audio beacon of Ears only on it. The arrow follows the phone's rotation-vector sensor, else the way you are
 * moving; without either it is drawn with north up and says so.
 */
@Composable
fun NeedsScreen(args: Dest.Needs, nav: Navigator) {
    val kind = remember(args.kind) { NeedKind.of(args.kind) }
    val look = remember(kind) { look(kind) }
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberLiveHere()
    val now = rememberLiveNow()
    val heading by rememberHeading()
    var filters by rememberSaveable(kind) { mutableStateOf(NeedFilter.initial(kind).map { it.name }.toSet()) }
    val chosen = filters.mapNotNull { n -> NeedFilter.entries.firstOrNull { it.name == n } }.toSet()
    val area by produceState(ShopArea.empty(here, NEED_RADIUS_M), here, kind) {
        repo.area("need:" + kind.name, PointMe.filterOf(kind), here, NEED_RADIUS_M, nameless = true).collect { value = it }
    }
    val targets = remember(area, here, chosen, now) { PointMe.targets(kind, area, here, chosen, now) }
    val top = targets.firstOrNull()
    val context = LocalContext.current
    val food = kind == NeedKind.FOOD

    Box(Modifier.fillMaxSize().background(argb(look.bg))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = boardTop(), bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(42.dp))
            TopBar(kind, look, chosen, nav) { f -> filters = if (f.name in filters) filters - f.name else filters + f.name }
            // The compass 30 px under the bar (24 for food), the distance's line box 14 under it (10), the walk 2 under that.
            Spacer(Modifier.height(if (food) 24.dp else 30.dp))
            val rel = top?.let { PointMe.relative(it.bearing, heading?.toDouble()) }
            val words = rel?.let { directionWords(it) }
            val desc = if (top != null && words != null) stringResource(R.string.shop_need_arrow, stringResource(look.kind), words) else stringResource(look.title)
            NeedDial(look.dial, look.dialSize.dp, heading, top?.bearing?.toFloat(), desc)
            Spacer(Modifier.height(if (food) 10.dp else 13.75.dp))
            when {
                area.waiting -> Line(stringResource(R.string.shop_loading), bt(16f, 400, 20.5f), look.soft)
                top == null -> Column(Modifier.padding(horizontal = 24.dp)) {
                    StateMessage(
                        look.badgeSymbol ?: "need.toilet",
                        stringResource(if (area.places.isEmpty() || chosen.isEmpty()) R.string.shop_need_none else R.string.shop_need_none_match),
                        text = area.error, tint = argb(look.soft),
                    )
                }
                else -> {
                    Distance(top.distanceM, look)
                    Spacer(Modifier.height(2.dp))
                    val sub = if (food) stringResource(R.string.shop_need_eat_in, top.place.name, top.walkMinutes) else stringResource(R.string.shop_need_min_walk, top.walkMinutes)
                    Line(sub, if (food) bt(15f, 600, 19.5f, tnum = true) else bt(16f, 600, 20.5f, tnum = true), look.soft)
                    if (heading == null) Line(
                        stringResource(R.string.shop_need_no_compass), bt(12f, 400, 15.5f, align = TextAlign.Center), look.soft,
                        Modifier.padding(horizontal = 32.dp, vertical = 6.dp), lines = 2,
                    )
                    Spacer(Modifier.height(if (food) 18.5.dp else 15.5.dp))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(if (food) 10.dp else 8.dp)) {
                        if (food) {
                            targets.take(3).forEach { t -> FoodRow(t, look, area, now, heading?.toDouble()) { open(nav, services, t) } }
                        } else {
                            DetailCard(top, kind, look, area, now) { open(nav, services, top) }
                            Spacer(Modifier.height(8.5.dp))
                            targets.drop(1).take(3).forEach { t -> AltRow(t, look, area, now, heading?.toDouble()) { open(nav, services, t) } }
                        }
                    }
                }
            }
        }
        if (top != null) {
            val ears by services.go.ears.collectAsStateWithLifecycle()
            val view by (ears?.view ?: remember { kotlinx.coroutines.flow.MutableStateFlow(app.atlas.ui.go.EarsView()) }).collectAsStateWithLifecycle()
            val guiding = view.beacon != null && Geo.distance(view.beacon!!.place.location, top.place.location) < 1.0
            Row(
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 26.dp + boardLift()).fillMaxWidth().height(56.dp)
                    .boardBox(look.accent, round(28f))
                    .clickable(role = Role.Button) {
                        val session = services.go.startEars(context, CompassHeadings.flow(context))
                        session.toggleBeacon(beaconOf(top, here, heading?.toDouble()))
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("ui.volume", 20f, 20f, 0xFFFFFFFF)
                Line(stringResource(if (guiding) R.string.shop_need_guide_stop else R.string.shop_need_guide), bt(16f, 700, 20.5f), 0xFFFFFFFF)
            }
        }
    }
}

private const val NEED_RADIUS_M = 1_500.0

private fun open(nav: Navigator, services: app.atlas.Services, t: Target) {
    services.remember(t.place.place)
    nav.go(Dest.Place.of(t.place.place))
}

/** The audio beacon's target for [t]: the place, its name and where it lies from here. */
internal fun beaconOf(t: Target, here: app.atlas.geo.LatLon, heading: Double?): Nearby {
    val d = Geo.distance(here, t.place.location)
    val b = Geo.bearing(here, t.place.location)
    return Nearby(Place(t.place.place.id, t.place.location, t.place.tags), t.place.name, null, d, b, heading?.let { Geo.turnAngle(it, b) }, 1.0)
}

@Composable
private fun directionWords(rel: Double): String = stringResource(
    when {
        rel in -20.0..20.0 -> R.string.shop_need_dir_ahead
        rel in 20.0..70.0 -> R.string.shop_need_dir_ahead_right
        rel in 70.0..110.0 -> R.string.shop_need_dir_right
        rel in 110.0..160.0 -> R.string.shop_need_dir_behind_right
        rel in -70.0..-20.0 -> R.string.shop_need_dir_ahead_left
        rel in -110.0..-70.0 -> R.string.shop_need_dir_left
        rel in -160.0..-110.0 -> R.string.shop_need_dir_behind_left
        else -> R.string.shop_need_dir_behind
    },
)

/** The bar at y 42: back, the need's 44 px badge (12 px corners), and its filters at the right, 40 high. */
@Composable
private fun TopBar(kind: NeedKind, look: NeedLook, on: Set<NeedFilter>, nav: Navigator, onToggle: (NeedFilter) -> Unit) {
    val food = kind == NeedKind.FOOD
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(if (food) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).boardBox(0xFFFFFFFF, round(22f)).tap(stringResource(R.string.shop_back_map)) { nav.back() }, contentAlignment = Alignment.Center) {
            Sym("ui.back", 20f, 20f, look.ink)
        }
        Box(Modifier.size(44.dp).boardBox(look.accent, round(12f)), contentAlignment = Alignment.Center) {
            if (look.badgeSymbol == null) Line(stringResource(R.string.shop_need_badge_wc), bt(16f, 900, 20.5f), 0xFFFFFFFF)
            else Sym(look.badgeSymbol, look.badgeW, look.badgeH, 0xFFFFFFFF)
        }
        Spacer(Modifier.weight(1f))
        for (f in NeedFilter.of(kind)) {
            val selected = f in on
            val label = filterLabel(f)
            val (text, symbol) = when (f) {
                NeedFilter.FREE -> stringResource(R.string.shop_f_free) to null
                NeedFilter.BOTTLE -> stringResource(R.string.shop_f_bottle) to "fact.bottle-refill"
                NeedFilter.CHEAP -> "$" to null
                NeedFilter.VEGAN -> stringResource(R.string.shop_f_vegan) to null
                NeedFilter.HALAL -> stringResource(R.string.shop_f_halal) to null
                NeedFilter.WHEELCHAIR -> null to "fact.wheelchair"
                NeedFilter.BABY -> null to "fact.baby"
            }
            val fg = if (selected) 0xFFFFFFFF else if (f == NeedFilter.VEGAN) 0xFF2F6B34 else look.ink
            Row(
                Modifier.height(40.dp).then(if (text == null) Modifier.size(40.dp) else Modifier)
                    .boardBox(if (selected) look.accent else 0xFFFFFFFF, round(20f)).tap(label) { onToggle(f) }.semantics { this.selected = selected }
                    .padding(start = if (text == null) 0.dp else if (symbol != null) 10.dp else if (f == NeedFilter.VEGAN) 10.dp else 11.dp, end = if (text == null) 0.dp else if (f == NeedFilter.VEGAN) 10.dp else 11.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (symbol != null) Sym(symbol, if (text == null) 18f else 16f, if (text == null) 18f else 16f, fg)
                if (text != null) Line(text, if (f == NeedFilter.CHEAP) bt(13f, 800, 17f) else bt(12f, 800, 15.5f), fg)
            }
        }
    }
}

@Composable
private fun filterLabel(f: NeedFilter) = stringResource(
    when (f) {
        NeedFilter.FREE -> R.string.shop_f_free_d
        NeedFilter.WHEELCHAIR -> R.string.shop_f_wheelchair_d
        NeedFilter.BABY -> R.string.shop_f_baby_d
        NeedFilter.BOTTLE -> R.string.shop_f_bottle_d
        NeedFilter.CHEAP -> R.string.shop_f_cheap_d
        NeedFilter.VEGAN -> R.string.shop_f_vegan_d
        NeedFilter.HALAL -> R.string.shop_f_halal_d
    },
)

/** The distance in Geist Black (64, or 56 for food; the unit 30 or 26 bold), tightened by 3 % of the size. */
@Composable
private fun Distance(metres: Double, look: NeedLook) {
    val (n, unit) = if (metres >= 1_000) String.format(java.util.Locale.getDefault(), "%.1f", metres / 1000.0) to " km" else "${(Math.round(metres / 10.0) * 10).toInt()}" to " m"
    Block(
        buildAnnotatedString {
            append(n)
            withStyle(SpanStyle(fontSize = look.unitSize.sp, fontWeight = FontWeight.Bold)) { append(unit) }
        },
        bt(look.distanceSize, 900, look.distanceSize, spacing = -0.03f * look.distanceSize, tnum = true), look.ink,
    )
}

/** The nearest place on a white card (26 px corners): its name, when it closes, and its facts as round badges; the fountain for water. */
@Composable
private fun DetailCard(t: Target, kind: NeedKind, look: NeedLook, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val water = kind == NeedKind.WATER
    Row(
        Modifier.fillMaxWidth().boardBox(0xFFFFFFFF, round(26f), listOf(CssShadow(0f, 6f, 18f, 0f, (look.ink and 0xFFFFFF) or 0x14000000)))
            .tap(t.place.name, onClick).padding(horizontal = 16.dp, vertical = if (water) 14.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (water) Sym("place.drinking-fountain", 56f, 76f, look.ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (water) 8.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Line(t.place.name, bt(18f, 800, 23.5f), look.ink, Modifier.weight(1f), lines = 2)
                if (!water) openLine(t.place, area, now)?.let { (text, c) ->
                    Line(text, bt(14f, 700, 18f, tnum = true), if (c == Color(0xFF464C5A)) 0xFF1E7A46 else (c.toArgb().toLong() and 0xFFFFFFFFL))
                }
            }
            FactRow(t, kind, look)
        }
    }
}

/** The card's facts: words as pills (36 high, 30 for water), the rest as round badges of a symbol. */
@Composable
private fun FactRow(t: Target, kind: NeedKind, look: NeedLook) {
    val small = kind == NeedKind.WATER
    val h = if (small) 30f else 36f
    Row(horizontalArrangement = Arrangement.spacedBy(if (small) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun chip(text: String, bg: Long = look.chipBg, fg: Long = look.chipInk, symbol: String? = null) {
            Row(
                Modifier.height(h.dp).boardBox(bg, round(h / 2)).padding(horizontal = if (small) 10.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (symbol != null) Sym(symbol, 14f, 14f, fg)
                Line(text, if (small) bt(12f, 800, 15.5f, tnum = true) else bt(13f, 800, 17f, tnum = true), fg)
            }
        }
        @Composable
        fun badge(symbol: String, desc: String, bg: Long = look.chipBg, fg: Long = look.chipInk, side: Float = 18f) {
            Box(Modifier.size(h.dp).boardBox(bg, round(h / 2)).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) { Sym(symbol, side, side, fg) }
        }
        val f = t.facts
        if (Fact.FREE in f) chip(stringResource(R.string.shop_fact_free))
        if (Fact.PAID in f) chip(stringResource(R.string.shop_fact_paid), 0xFFFFF4E0, 0xFF8A5A00)
        if (Fact.DRINKABLE in f && kind == NeedKind.WATER) chip(stringResource(R.string.shop_fact_drinkable), symbol = "ui.check")
        if (Fact.WHEELCHAIR in f) badge("fact.wheelchair", stringResource(R.string.shop_fact_wheelchair))
        if (Fact.BABY in f) badge("fact.baby", stringResource(R.string.shop_fact_baby))
        if (Fact.ALL_GENDER in f) badge("need.toilet.5", stringResource(R.string.shop_fact_all_gender))
        if (Fact.WATER in f) badge("need.water", stringResource(R.string.shop_fact_water), 0xFFE4F0FB, 0xFF1560A8, 17f)
        if (Fact.BOTTLE in f && kind == NeedKind.WATER) chip(stringResource(R.string.shop_fact_bottle), symbol = "fact.bottle-refill")
        if (Fact.CUSTOMERS in f) chip(stringResource(R.string.shop_fact_customers), 0xFFF1EEF8, 0xFF5A5288)
        val off = t.offOn
        if (off != null) chip(stringResource(R.string.shop_fact_off, java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()).format(off)), 0xFFFFF4E0, 0xFF8A5A00)
        else if (Fact.SEASONAL in f) chip(stringResource(R.string.shop_fact_seasonal), 0xFFFFF4E0, 0xFF8A5A00)
    }
}

/** A further place on a slim row (54 high, 18 px corners, 14 in): the arrow to it, its name, a short label, the hours and how far. */
@Composable
private fun AltRow(t: Target, look: NeedLook, area: ShopArea, now: java.time.LocalDateTime, heading: Double?, onClick: () -> Unit) {
    val rel = PointMe.relative(t.bearing, heading).toFloat()
    val usable = t.open != Open.CLOSED && Fact.CUSTOMERS !in t.facts
    Row(
        Modifier.fillMaxWidth().height(54.dp).alpha(if (usable) 1f else 0.75f).boardBox(look.altBg, round(18f)).tap(t.place.name, onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym("nav.direction", 22f, 22f, look.accent, rotate = rel)
        Line(t.place.name, bt(15f, 700, 19.5f), look.ink, Modifier.weight(1f))
        val label = when {
            t.refill && Fact.FREE in t.facts && Fact.BOTTLE in t.facts -> stringResource(R.string.shop_fact_free_refill) to true
            Fact.CUSTOMERS in t.facts -> stringResource(R.string.shop_fact_customers) to false
            Fact.FREE in t.facts -> stringResource(R.string.shop_fact_free) to true
            else -> null
        }
        label?.let { (text, strong) -> Line(text, bt(12f, if (strong) 800 else 700, 15.5f), if (strong) look.accent else look.soft) }
        openLine(t.place, area, now)?.let { (text, _) -> Line(text, bt(13f, 400, 17f, tnum = true), look.soft) }
        Line(shortDistance(t.distanceM), bt(16f, 800, 20.5f, tnum = true, align = TextAlign.End), look.ink, Modifier.width(58.dp))
    }
}

/** A food row (76 high, 22 px corners): the kind in a 46 px tile, its name, price mark, hours, an arrow and the distance. */
@Composable
private fun FoodRow(t: Target, look: NeedLook, area: ShopArea, now: java.time.LocalDateTime, heading: Double?, onClick: () -> Unit) {
    val rel = PointMe.relative(t.bearing, heading).toFloat()
    val free = Fact.FREE in t.facts
    val (symbol, w, h) = when {
        t.place.tags["amenity"] in setOf("food_sharing", "fridge") -> Triple("place.fridge", 22f, 22f)
        t.place.tags["shop"] == "bakery" || t.place.tags["shop"] == "pastry" -> Triple("need.food", 24f, 22f)
        t.place.tags["amenity"] in setOf("restaurant", "fast_food", "cafe", "food_court") -> Triple("food.hot", 22f, 22f)
        else -> Triple("grocery.supermarket", 22f, 22f)
    }
    Row(
        Modifier.fillMaxWidth().height(76.dp).boardBox(0xFFFFFFFF, round(22f), listOf(CssShadow(0f, 4f, 14f, 0f, 0x123A1A08))).tap(t.place.name, onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).boardBox(if (free) 0xFFDDEFE7 else look.bg, round(14f)), contentAlignment = Alignment.Center) {
            Sym(symbol, w, h, if (free) 0xFF2F6B55 else look.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line(t.place.name, bt(16f, 800, 20.5f), look.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val st = bt(13f, 400, 17f, tnum = true)
                if (free) Line(stringResource(R.string.shop_fact_free), bt(13f, 800, 17f, tnum = true), 0xFF2F6B55)
                else if (Fact.CHEAP in t.facts) Line("$", bt(13f, 800, 17f, tnum = true), look.soft)
                openLine(t.place, area, now)?.let { (text, _) -> Line(text, st, if (free) 0xFF2F6B55 else look.soft) }
                if (t.open == Open.OPEN || t.open == Open.CLOSING_SOON) Line(stringResource(R.string.shop_fact_ready), st, look.soft)
            }
        }
        Sym("nav.direction", 22f, 22f, look.accent, rotate = rel)
        Line(shortDistance(t.distanceM), bt(17f, 900, 22f, tnum = true, align = TextAlign.End), look.ink, Modifier.width(if (t.distanceM >= 100) 64.dp else 50.dp))
    }
}
