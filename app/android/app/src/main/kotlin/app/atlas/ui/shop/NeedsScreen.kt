package app.atlas.ui.shop

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
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.shortDistance

/** How one need looks: the board's colours, its compass, and the words it uses. */
private class NeedLook(
    val bg: Color, val ink: Color, val soft: Color, val accent: Color, val chipBg: Color, val chipInk: Color,
    val dial: String, val dialSize: Int, val distanceSize: Int, val unitSize: Int,
    val title: Int, val kind: Int, val badgeSymbol: String?,
)

private fun look(kind: NeedKind) = when (kind) {
    NeedKind.TOILET -> NeedLook(
        Color(0xFFF0EDFB), Color(0xFF1B1440), Color(0xFF5A5288), Color(0xFF4B34B8), Color(0xFFEDE9FF), Color(0xFF4B34B8),
        ShopBoards.COMPASS_TOILET, 340, 64, 30, R.string.shop_need_toilet_t, R.string.shop_need_kind_toilet, null,
    )
    NeedKind.WATER -> NeedLook(
        Color(0xFFEAF3FB), Color(0xFF0E2A44), Color(0xFF3E5A74), Color(0xFF1560A8), Color(0xFFE4F0FB), Color(0xFF1560A8),
        ShopBoards.COMPASS_WATER, 340, 64, 30, R.string.shop_need_water_t, R.string.shop_need_kind_water, "need.water",
    )
    NeedKind.FOOD -> NeedLook(
        Color(0xFFFBEFE6), Color(0xFF3A1A08), Color(0xFF7A4A2A), Color(0xFFA8420E), Color(0xFFFBEFE6), Color(0xFFA8420E),
        ShopBoards.COMPASS_FOOD, 300, 56, 26, R.string.shop_need_food_t, R.string.shop_need_kind_food, "need.food",
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

    Box(Modifier.fillMaxSize().background(look.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 2.dp, bottom = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar(kind, look, chosen, nav) { f -> filters = if (f.name in filters) filters - f.name else filters + f.name }
            Spacer(Modifier.height(if (kind == NeedKind.FOOD) 22.dp else 26.dp))
            val rel = top?.let { PointMe.relative(it.bearing, heading?.toDouble()) }
            val words = rel?.let { directionWords(it) }
            val desc = if (top != null && words != null) stringResource(R.string.shop_need_arrow, stringResource(look.kind), words) else stringResource(look.title)
            NeedDial(look.dial, look.dialSize.dp, heading, top?.bearing?.toFloat(), desc)
            Spacer(Modifier.height(if (kind == NeedKind.FOOD) 12.dp else 14.dp))
            when {
                area.waiting -> Text(stringResource(R.string.shop_loading), style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp), color = look.soft)
                top == null -> Column(Modifier.padding(horizontal = 24.dp)) {
                    StateMessage(
                        look.badgeSymbol ?: "need.toilet",
                        stringResource(if (area.places.isEmpty() || chosen.isEmpty()) R.string.shop_need_none else R.string.shop_need_none_match),
                        text = area.error, tint = look.soft,
                    )
                }
                else -> {
                    Distance(top.distanceM, look)
                    val sub = if (kind == NeedKind.FOOD) stringResource(R.string.shop_need_eat_in, top.place.name, top.walkMinutes) else stringResource(R.string.shop_need_min_walk, top.walkMinutes)
                    Text(sub, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = if (kind == NeedKind.FOOD) 15.sp else 16.sp, fontFeatureSettings = "tnum"), color = look.soft, textAlign = TextAlign.Center)
                    if (heading == null) Text(stringResource(R.string.shop_need_no_compass), Modifier.padding(horizontal = 32.dp, vertical = 6.dp), style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp), color = look.soft, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(if (kind == NeedKind.FOOD) 10.dp else 8.dp)) {
                        when (kind) {
                            NeedKind.FOOD -> targets.take(3).forEach { t -> FoodRow(t, look, area, now, heading?.toDouble()) { open(nav, services, t) } }
                            else -> {
                                DetailCard(top, kind, look, area, now) { open(nav, services, top) }
                                Spacer(Modifier.height(4.dp))
                                targets.drop(1).take(3).forEach { t -> AltRow(t, look, area, now, heading?.toDouble()) { open(nav, services, t) } }
                            }
                        }
                    }
                }
            }
        }
        if (top != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp)) {
            val ears by services.go.ears.collectAsStateWithLifecycle()
            val view by (ears?.view ?: remember { kotlinx.coroutines.flow.MutableStateFlow(app.atlas.ui.go.EarsView()) }).collectAsStateWithLifecycle()
            val guiding = view.beacon != null && Geo.distance(view.beacon!!.place.location, top.place.location) < 1.0
            BigPill(
                stringResource(if (guiding) R.string.shop_need_guide_stop else R.string.shop_need_guide),
                onClick = {
                    val session = services.go.startEars(context, CompassHeadings.flow(context))
                    session.toggleBeacon(beaconOf(top, here, heading?.toDouble()))
                },
                modifier = Modifier.fillMaxWidth(), fill = look.accent,
                icon = { Symbol("ui.volume", tint = Color.White, size = 20.dp) },
            )
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

@Composable
private fun TopBar(kind: NeedKind, look: NeedLook, on: Set<NeedFilter>, nav: Navigator, onToggle: (NeedFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(if (kind == NeedKind.FOOD) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundSymbolButton("ui.back", stringResource(R.string.shop_back_map), { nav.back() }, size = 44.dp, fill = Color.White, tint = look.ink, iconSize = 20.dp)
        Box(Modifier.size(44.dp).background(look.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            if (look.badgeSymbol == null) Text(stringResource(R.string.shop_need_badge_wc), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Black, fontSize = 16.sp), color = Color.White)
            else Symbol(look.badgeSymbol, tint = Color.White, size = 24.dp)
        }
        Spacer(Modifier.weight(1f))
        for (f in NeedFilter.of(kind)) {
            val selected = f in on
            val label = filterLabel(f)
            val (text, symbol) = when (f) {
                NeedFilter.FREE -> stringResource(R.string.shop_f_free) to null
                NeedFilter.BOTTLE -> stringResource(R.string.shop_f_bottle) to "fact.container"
                NeedFilter.CHEAP -> "$" to null
                NeedFilter.VEGAN -> stringResource(R.string.shop_f_vegan) to null
                NeedFilter.HALAL -> stringResource(R.string.shop_f_halal) to null
                NeedFilter.WHEELCHAIR -> null to "fact.wheelchair"
                NeedFilter.BABY -> null to "fact.baby"
            }
            val fg = if (selected) Color.White else if (f == NeedFilter.VEGAN) Color(0xFF2F6B34) else look.ink
            Row(
                Modifier.height(40.dp).then(if (text == null) Modifier.size(40.dp) else Modifier)
                    .clip(CircleShape).background(if (selected) look.accent else Color.White).tap(label) { onToggle(f) }.semantics { this.selected = selected }
                    .padding(horizontal = if (text == null) 0.dp else if (symbol != null) 11.dp else 11.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (symbol != null) Symbol(symbol, tint = fg, size = if (text == null) 18.dp else 16.dp)
                if (text != null) Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = if (f == NeedFilter.CHEAP) 13.sp else 12.sp), color = fg, maxLines = 1)
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

@Composable
private fun Distance(metres: Double, look: NeedLook) {
    val (n, unit) = if (metres >= 1_000) String.format(java.util.Locale.getDefault(), "%.1f", metres / 1000.0) to " km" else "${(Math.round(metres / 10.0) * 10).toInt()}" to " m"
    Text(
        buildAnnotatedString {
            append(n)
            withStyle(SpanStyle(fontSize = look.unitSize.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)) { append(unit) }
        },
        style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Black, fontSize = look.distanceSize.sp, lineHeight = look.distanceSize.sp, letterSpacing = (-0.03f * look.distanceSize).sp, fontFeatureSettings = "tnum"),
        color = look.ink,
    )
}

/** The nearest place on a white card: its name, when it closes, and its facts as round badges. */
@Composable
private fun DetailCard(t: Target, kind: NeedKind, look: NeedLook, area: ShopArea, now: java.time.LocalDateTime, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().cssShadow(RoundedCornerShape(26.dp), y = 6.dp, blur = 18.dp, colour = look.ink.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(26.dp)).background(Color.White).tap(t.place.name, onClick).padding(horizontal = 16.dp, vertical = if (kind == NeedKind.WATER) 14.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == NeedKind.WATER) BoardSymbol("place.drinking-fountain", 56.dp, 76.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (kind == NeedKind.WATER) 8.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.place.name, Modifier.weight(1f), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (kind != NeedKind.WATER) openLine(t.place, area, now)?.let { (text, c) -> Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFeatureSettings = "tnum"), color = if (c == Color(0xFF464C5A)) Color(0xFF1E7A46) else c, maxLines = 1) }
            }
            FactRow(t, kind, look)
        }
    }
}

@Composable
private fun FactRow(t: Target, kind: NeedKind, look: NeedLook) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (kind == NeedKind.WATER) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val small = kind == NeedKind.WATER
        val h = if (small) 30.dp else 36.dp
        fun chip(text: String, bg: Color = look.chipBg, fg: Color = look.chipInk, symbol: String? = null) = @Composable {
            Row(
                Modifier.height(h).clip(RoundedCornerShape(h / 2)).background(bg).padding(horizontal = if (small) 10.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (symbol != null) Symbol(symbol, tint = fg, size = 14.dp)
                Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = if (small) 12.sp else 13.sp, fontFeatureSettings = "tnum"), color = fg, maxLines = 1)
            }
        }
        @Composable
        fun badge(symbol: String, desc: String, bg: Color = look.chipBg, fg: Color = look.chipInk) {
            Box(Modifier.size(h).background(bg, CircleShape).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) { Symbol(symbol, tint = fg, size = 18.dp) }
        }
        val f = t.facts
        if (Fact.FREE in f) chip(stringResource(R.string.shop_fact_free))()
        if (Fact.PAID in f) chip(stringResource(R.string.shop_fact_paid), Color(0xFFFFF4E0), Color(0xFF8A5A00))()
        if (Fact.DRINKABLE in f && kind == NeedKind.WATER) chip(stringResource(R.string.shop_fact_drinkable), symbol = "ui.check")()
        if (Fact.WHEELCHAIR in f) badge("fact.wheelchair", stringResource(R.string.shop_fact_wheelchair))
        if (Fact.BABY in f) badge("fact.baby", stringResource(R.string.shop_fact_baby))
        if (Fact.ALL_GENDER in f) badge("fact.person", stringResource(R.string.shop_fact_all_gender))
        if (Fact.WATER in f) badge("need.water", stringResource(R.string.shop_fact_water), Color(0xFFE4F0FB), Color(0xFF1560A8))
        if (Fact.BOTTLE in f && kind == NeedKind.WATER) chip(stringResource(R.string.shop_fact_bottle), symbol = "fact.container")()
        if (Fact.CUSTOMERS in f) chip(stringResource(R.string.shop_fact_customers), Color(0xFFF1EEF8), Color(0xFF5A5288))()
        t.offOn?.let { chip(stringResource(R.string.shop_fact_off, java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()).format(it)), Color(0xFFFFF4E0), Color(0xFF8A5A00))() }
            ?: if (Fact.SEASONAL in f) chip(stringResource(R.string.shop_fact_seasonal), Color(0xFFFFF4E0), Color(0xFF8A5A00))() else Unit
    }
}

/** A further place on a slim row: the arrow to it, its name, a short label, and how far. */
@Composable
private fun AltRow(t: Target, look: NeedLook, area: ShopArea, now: java.time.LocalDateTime, heading: Double?, onClick: () -> Unit) {
    val rel = PointMe.relative(t.bearing, heading).toFloat()
    val usable = t.open != Open.CLOSED && Fact.CUSTOMERS !in t.facts
    Row(
        Modifier.fillMaxWidth().height(54.dp).alpha(if (usable) 1f else 0.75f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.72f)).tap(t.place.name, onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("nav.direction", Modifier.rotate(rel), tint = look.accent, size = 22.dp)
        Text(t.place.name, Modifier.weight(1f), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val label = when {
            t.refill && Fact.FREE in t.facts && Fact.BOTTLE in t.facts -> stringResource(R.string.shop_fact_free_refill) to true
            Fact.CUSTOMERS in t.facts -> stringResource(R.string.shop_fact_customers) to false
            Fact.FREE in t.facts -> stringResource(R.string.shop_fact_free) to true
            else -> null
        }
        label?.let { (text, strong) -> Text(text, style = TextStyle(fontFamily = InterFamily, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.Bold, fontSize = 12.sp), color = if (strong) look.accent else look.soft, maxLines = 1) }
        openLine(t.place, area, now)?.let { (text, _) -> Text(text, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = look.soft, maxLines = 1) }
        Text(shortDistance(t.distanceM), Modifier.width(58.dp), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, fontFeatureSettings = "tnum"), color = look.ink, textAlign = TextAlign.End, maxLines = 1)
    }
}

/** A food row: the kind in a soft tile, its name, price mark, hours, an arrow and the distance. */
@Composable
private fun FoodRow(t: Target, look: NeedLook, area: ShopArea, now: java.time.LocalDateTime, heading: Double?, onClick: () -> Unit) {
    val rel = PointMe.relative(t.bearing, heading).toFloat()
    val free = Fact.FREE in t.facts
    val symbol = when {
        t.place.tags["amenity"] in setOf("food_sharing", "fridge") -> "place.fridge"
        t.place.tags["shop"] == "bakery" || t.place.tags["shop"] == "pastry" -> "need.food"
        t.place.tags["amenity"] in setOf("restaurant", "fast_food", "cafe", "food_court") -> "food.hot"
        else -> "grocery.supermarket"
    }
    Row(
        Modifier.fillMaxWidth().height(76.dp).cssShadow(RoundedCornerShape(22.dp), y = 4.dp, blur = 14.dp, colour = Color(0x123A1A08))
            .clip(RoundedCornerShape(22.dp)).background(Color.White).tap(t.place.name, onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).background(if (free) Color(0xFFDDEFE7) else look.bg, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Symbol(symbol, tint = if (free) Color(0xFF2F6B55) else look.accent, size = 24.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.place.name, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val st = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontFeatureSettings = "tnum")
                if (free) Text(stringResource(R.string.shop_fact_free), style = st.copy(fontWeight = FontWeight.ExtraBold), color = Color(0xFF2F6B55))
                else if (Fact.CHEAP in t.facts) Text("$", style = st.copy(fontWeight = FontWeight.ExtraBold), color = look.soft)
                openLine(t.place, area, now)?.let { (text, _) -> Text(text, style = st, color = if (free) Color(0xFF2F6B55) else look.soft, maxLines = 1) }
                if (t.open == Open.OPEN || t.open == Open.CLOSING_SOON) Text(stringResource(R.string.shop_fact_ready), style = st, color = look.soft, maxLines = 1)
            }
        }
        Symbol("nav.direction", Modifier.rotate(rel), tint = look.accent, size = 22.dp)
        Text(shortDistance(t.distanceM), Modifier.width(if (t.distanceM >= 100) 64.dp else 50.dp), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Black, fontSize = 17.sp, fontFeatureSettings = "tnum"), color = look.ink, textAlign = TextAlign.End, maxLines = 1)
    }
}
