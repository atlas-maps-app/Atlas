package app.atlas.ui.local

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.Sun
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.CurrentWeather
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.skySymbol
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.zIndex
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow

private val Walnut = Color(0xFF3A2415)
private val Gold = Color(0xFFE8C878)
private val Parchment = Color(0xFFF1E6CC)
private val Inkwell = Color(0xFF2A1A0E)
private val Sepia = Color(0xFF6B5438)

/** One drawer of the cabinet: where it leads, its brass label's symbol, and the line on its card when opened. */
private class Drawer(val id: String, val symbol: String, val title: Int, val dest: Dest)

private fun drawers(here: app.atlas.geo.LatLon): List<Drawer> = listOf(
    Drawer("named", "ui-signpost", R.string.local_drawer_named, Dest.NamedAfter(here.lat, here.lon)),
    Drawer("building", "ui-landmark", R.string.local_drawer_building, Dest.BuildingStory(here.lat, here.lon)),
    Drawer("collect", "bin.recycling", R.string.local_drawer_collect, Dest.Collect),
    Drawer("seasons", "nature.season", R.string.local_drawer_seasons, Dest.Season),
    Drawer("sky", "ui.moon", R.string.local_drawer_sky, Dest.Sky),
    Drawer("then", "local.then", R.string.local_drawer_then, Dest.Time()),
    Drawer("look", "ui.eye", R.string.local_drawer_look, Dest.Look()),
    Drawer("peaks", "local.peaks", R.string.local_drawer_peaks, Dest.Peaks()),
    Drawer("nature", "nature.tree", R.string.local_drawer_nature, Dest.Nature()),
    Drawer("parks", "place.park", R.string.local_drawer_parks, Dest.Parkland()),
    Drawer("picnic", "place.picnic", R.string.local_drawer_picnic, Dest.Picnic()),
    Drawer("swim", "place.pool", R.string.local_drawer_swim, Dest.Swim()),
    Drawer("relax", "place.spa", R.string.local_drawer_relax, Dest.Relax()),
    Drawer("gps", "local.gps", R.string.local_drawer_gps, Dest.Satellites),
    Drawer("water", "local.water", R.string.local_drawer_water, Dest.Water()),
)

/**
 * The Local tab: a walnut card-catalogue cabinet of drawers, one for each thing the phone can say about this place. A
 * paper card above them shows what is true here now (the weather, the next rain, sunset, the Moon); tapping a drawer
 * pulls it out to read its label, and "Open" goes in. [Dest.Local.drawer] names a drawer to open first.
 */
@Composable
fun LocalScreen(args: Dest.Local, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val place = rememberPlaceName(here)
    val snap by produceState<SoonSnapshot?>(null, here, now) { value = loadSoon(services, here, now, zone) }
    val all = remember(here) { drawers(here) }
    var open by rememberSaveable { mutableStateOf(args.drawer) }
    val season = remember(here, now) { FoodCalendar.now(FoodRegion.of(here), LocalDate.ofInstant(now, zone), here.lat < 0) }
    val wasteCal by services.local.waste.calendar.collectAsState()
    val collect = wasteCal?.next(LocalDate.ofInstant(now, zone))?.let { p -> pickupLine(p, LocalDate.ofInstant(now, zone)) }

    Box(Modifier.fillMaxSize().background(Walnut).boardBox(grad = WALNUT, clip = false)) {
        Column(Modifier.fillMaxSize().padding(top = boardShift()).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // The head, 58 px at 42: Local in gilt Cormorant, the place and its coordinates 3 px under it, the question pill.
            Row(Modifier.fillMaxWidth().padding(top = 42.dp).height(58.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false).wrapContentHeight(unbounded = true)) {
                    BoardLine(
                        stringResource(R.string.local_title),
                        boardStyle(44f, 600, 0xFF000000, 41.8f, CORMORANT, italic = true).copy(brush = Brush.verticalGradient(0f to Color(0xFFF6E2A8), 0.6f to Color(0xFFC9973E), 1f to Color(0xFFF1D38A))),
                        Modifier.semantics { heading() }, overflow = TextOverflow.Visible,
                    )
                    val coords = coordsLabel(here)
                    BoardLine(listOfNotNull(place, coords).joinToString(" · "), boardStyle(12f, 400, 0xFFE0C9A0, 15.5f, tnum = true), Modifier.padding(top = 3.dp))
                }
                Row(
                    Modifier.padding(start = 8.dp).height(42.dp).boardBox(0x1FF4ECD8, corners(21f), shadow(0f, 0f, 0f, 0x80E8C878, spread = 1f, inset = true)).clickable { nav.go(Dest.Ask()) }.padding(start = 12.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("fact.new", tint = Gold, size = 16.dp)
                    BoardLine(stringResource(R.string.local_ask), boardStyle(14f, 600, 0xFFF4E3B8, 18f))
                }
            }
            Spacer(Modifier.height(16.dp))
            LabelCard(open?.let { id -> all.firstOrNull { it.id == id } }, all, snap, season, collect, zone, now, onClose = { open = null }, onOpen = { nav.go(it.dest) }, onGo = { nav.go(it) })
            Spacer(Modifier.height(16.dp))
            // The cabinet: 388 wide from 12, 8 px in, radius 8, its sunken shadow; drawers three a row, 7 apart.
            Column(
                Modifier.fillMaxWidth().outdent(4.dp)
                    .boardBox(0xFF24150B, corners(8f), listOf(CssShadow(0f, 2f, 6f, 0f, 0x99000000, inset = true), CssShadow(0f, 1f, 0f, 0f, 0x14FFDCAA)), clip = false)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (row in all.chunked(3)) {
                    Row(Modifier.fillMaxWidth().zIndex(if (row.any { it.id == open }) 2f else 1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        for (d in row) DrawerFront(d, d.id == open, Modifier.weight(1f)) { open = if (open == d.id) null else d.id }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(25.dp))
            BoardPara(stringResource(R.string.local_sources), boardStyle(11f, 400, 0xFFD9C3A0, 14.85f), Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(LocalBottomBarInset.current + 8.dp))
        }
    }
}

/** The walnut: CSS `repeating-linear-gradient(91deg, black 9% 0 2px, clear 2px 9px)`, `(88deg, cream 5% 0 1px, clear 1px 13px)` over a dark wash. */
private val WALNUT = listOf(
    cssRepeatingLinear(91f, 9f, 0f to 0x17000000, 2f to 0x17000000, 2f to 0x00000000, 9f to 0x00000000),
    cssRepeatingLinear(88f, 13f, 0f to 0x0DFFDCAA, 1f to 0x0DFFDCAA, 1f to 0x00FFDCAA, 13f to 0x00FFDCAA),
    cssLinear(180f, 0f to 0xFF4A2E1B, 1f to 0xFF2A190E),
)

/**
 * The parchment card of the board: 92 px at least, radius 6, its two soft lights, shadow and hairline, 12 px down and 6
 * in; the brass pin it hangs from. Closed, what is true here now; opened, the drawer's label and what it holds.
 */
@Composable
private fun LabelCard(
    drawer: Drawer?, all: List<Drawer>, snap: SoonSnapshot?, season: SeasonNow, collect: String?, zone: java.time.ZoneId, now: java.time.Instant,
    onClose: () -> Unit, onOpen: (Drawer) -> Unit, onGo: (Dest) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 92.dp)
                .boardBox(
                    Parchment.css(), corners(6f), listOf(CssShadow(0f, 10f, 24f, 0f, 0x66000000), CssShadow(0f, 0f, 0f, 1f, 0x4078501E, inset = true)),
                    grad = listOf(cssRadialAt(0.3f, 0.2f, 0f to 0x73FFFFFF, 0.6f to 0x00FFFFFF), cssRadialAt(0.9f, 1f, 0f to 0x2EA06E32, 0.55f to 0x00A06E32)),
                )
                .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
        ) {
            if (drawer == null) StatusRow(snap, now, zone, onGo)
            else OpenedLabel(drawer, all.indexOf(drawer) + 1, factFor(drawer, snap, season, collect, zone, now), onClose = onClose, onOpen = { onOpen(drawer) })
        }
        // The brass pin, 12 px, 7 px above the card's top.
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-7).dp).size(12.dp)
                .boardBox(r = corners(6f), shadows = shadow(0f, 2f, 3f, 0x80000000), grad = listOf(cssRadial(true, 4.2f, 3.6f, 0f to 0xFFF7DC8C, 1f to 0xFFA87C2A)), clip = false),
        )
    }
}

@Composable
private fun factFor(d: Drawer, snap: SoonSnapshot?, season: SeasonNow, collect: String?, zone: java.time.ZoneId, now: java.time.Instant): String = when (d.id) {
    "named" -> stringResource(R.string.local_fact_named)
    "building" -> stringResource(R.string.local_fact_building)
    "collect" -> collect ?: stringResource(R.string.local_fact_collect)
    "seasons" -> {
        val month = java.time.Month.of(season.month).getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        val best = season.best.take(3).map { foodName(it.id) }
        if (best.isEmpty()) stringResource(R.string.local_fact_seasons_none, month) else stringResource(R.string.local_fact_seasons, month, best.joinToString(", "))
    }
    "sky" -> {
        val first = snap?.moments?.firstOrNull { it is Moment.RainStarts || it is Moment.RainStops || it is Moment.Station }
        when (first) {
            is Moment.RainStarts -> stringResource(R.string.sky_rain_in, first.minutes)
            is Moment.RainStops -> stringResource(R.string.sky_raining_until, DateTimeFormatter.ofPattern("H:mm").format(first.at.atZone(zone)))
            is Moment.Station -> stringResource(R.string.moment_station, DateTimeFormatter.ofPattern("H:mm").format(first.pass.peak.atZone(zone)))
            else -> stringResource(R.string.local_fact_sky)
        }
    }
    "then" -> stringResource(R.string.local_fact_then)
    "look" -> stringResource(R.string.local_fact_look)
    "peaks" -> stringResource(R.string.local_fact_peaks)
    "nature" -> stringResource(R.string.local_fact_nature)
    "parks" -> stringResource(R.string.local_fact_parks)
    "picnic" -> stringResource(R.string.local_fact_picnic)
    "swim" -> stringResource(R.string.local_fact_swim)
    "relax" -> stringResource(R.string.local_fact_relax)
    "gps" -> stringResource(R.string.local_fact_gps)
    else -> stringResource(R.string.local_fact_water)
}

/** The closed card: what is true here now, four things in a row, each its 24 px symbol, a value in Libre Caslon and a word, 3 apart. */
@Composable
private fun StatusRow(snap: SoonSnapshot?, now: java.time.Instant, zone: java.time.ZoneId, onGo: (Dest) -> Unit) {
    val here = rememberHere()
    val hm = DateTimeFormatter.ofPattern("H:mm")
    val weather = CurrentWeather.of(snap?.forecast, now)
    val sunset = remember(now, here) { Sun.times(LocalDate.ofInstant(now, zone), here.lat, here.lon, zone).sunset }
    val moon = remember(now) { app.atlas.geo.Moon.phase(now) }
    val rain = snap?.moments?.firstOrNull { it is Moment.RainStarts || it is Moment.RainStops }
    Row(Modifier.fillMaxWidth()) {
        if (weather != null) StatusItem(weather.symbol, Color(0xFF1F6FB2), "${weather.temperature.roundToInt()}°", stringResource(R.string.local_status_now), Modifier.weight(1f)) { onGo(Dest.Weather()) }
        if (snap?.forecast?.hourAt(now) != null) {
            val (value, label) = when (rain) {
                is Moment.RainStarts -> "${rain.minutes}′" to stringResource(R.string.local_status_rain_in)
                is Moment.RainStops -> hm.format(rain.at.atZone(zone)) to stringResource(R.string.local_status_rain_until)
                else -> "–" to stringResource(R.string.local_status_dry)
            }
            StatusItem("weather.rain.2", Color(0xFF5A7ACA), value, label, Modifier.weight(1f)) { onGo(Dest.Sky) }
        }
        sunset?.let { StatusItem("weather.sunset", Color(0xFFC2641A), hm.format(it), stringResource(R.string.local_status_sunset), Modifier.weight(1f)) { onGo(Dest.Sky) } }
        StatusItem("ui.moon", Color(0xFF6B5438), "${(moon.illumination * 100).roundToInt()}%", stringResource(R.string.local_status_moon), Modifier.weight(1f)) { onGo(Dest.Moon) }
    }
}

@Composable
private fun StatusItem(symbol: String, tint: Color, value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Symbol(symbol, tint = tint, size = 24.dp)
        BoardLine(value, boardStyle(16f, 700, Inkwell.css(), 17.6f, CASLON, tnum = true))
        BoardLine(label, boardStyle(11f, 400, Sepia.css(), 14f, tnum = true))
    }
}

/** The board's face for the cabinet's labels and values. */
private const val CASLON = "Libre Caslon Text"

/** The opened label: "No. 03", the drawer's name in Libre Caslon 17, Open → and the close button, 8 apart; what it holds under them. */
@Composable
private fun OpenedLabel(d: Drawer, number: Int, fact: String, onClose: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BoardLine(stringResource(R.string.local_drawer_no, number), boardStyle(11f, 700, 0xFF8A6A3A, 14f, spacing = 1.32f))
            BoardLine(stringResource(d.title), boardStyle(17f, 700, Inkwell.css(), 21f, CASLON), Modifier.weight(1f))
            Box(Modifier.height(30.dp).boardBox(Inkwell.css(), corners(15f)).clickable(onClick = onOpen).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                BoardLine(stringResource(R.string.local_open), boardStyle(12f, 700, 0xFFF4E3B8, 15.5f))
            }
            val close = stringResource(R.string.local_close)
            Box(Modifier.size(30.dp).boardBox(0x1A3C2814, corners(15f)).clickable(onClick = onClose).semantics { contentDescription = close }, contentAlignment = Alignment.Center) {
                Symbol("ui.close", tint = Inkwell, size = 14.dp)
            }
        }
        BoardPara(fact, boardStyle(13f, 400, 0xFF3A2616, 17.55f))
    }
}

/**
 * A drawer of the board: 80 px, radius 5, the walnut front with its lit top edge, dark foot and hairline; 11 px down,
 * the 100 x 34 brass holder with the paper label (the symbol and the name in Libre Caslon 13, 5 apart), and 9 under it
 * the brass pull. Pulled out 7 px when open, lighter, with its deeper shadow.
 */
@Composable
private fun DrawerFront(d: Drawer, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val dy by animateDpAsState(if (open) 7.dp else 0.dp, tween(250), label = "pull")
    val title = stringResource(d.title)
    Column(
        modifier.height(80.dp).offset(y = dy)
            .boardBox(
                r = corners(5f), clip = false,
                shadows = if (open) OPEN_SHADOWS else SHUT_SHADOWS,
                grad = listOf(if (open) cssLinear(180f, 0f to 0xFF6E4629, 1f to 0xFF573520) else cssLinear(180f, 0f to 0xFF5E3B22, 1f to 0xFF4A2D18)),
            )
            .clickable(onClick = onClick).semantics { contentDescription = title }
            .padding(top = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(100.dp, 34.dp)
                .boardBox(r = corners(3f), shadows = shadow(0f, 1f, 2f, 0x80000000), grad = listOf(cssLinear(180f, 0f to 0xFFF0D48A, 0.55f to 0xFFB38A36, 1f to 0xFFE2C06E)), clip = false)
                .padding(2.dp),
        ) {
            Row(
                Modifier.fillMaxSize().boardBox(if (open) 0xFFFFF7DE else 0xFFF4ECD8, corners(2f), shadow(0f, 1f, 2f, 0x59503214, inset = true)).padding(horizontal = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(d.symbol, tint = Color(0xFF6B4A22), size = 15.dp)
                BoardLine(title, boardStyle(13f, 700, Inkwell.css(), 16f, CASLON), Modifier.weight(1f, fill = false))
            }
        }
        Box(
            Modifier.size(44.dp, 11.dp).boardBox(
                r = CssCorners(0f, 0f, 22f, 22f), clip = false,
                shadows = listOf(CssShadow(0f, 3f, 4f, 0f, 0x80000000), CssShadow(0f, 1f, 0f, 0f, 0x66FFFFFF, inset = true)),
                grad = listOf(cssLinear(180f, 0f to 0xFFE8C878, 1f to 0xFFA8802F)),
            ),
        )
    }
}

private val SHUT_SHADOWS = listOf(
    CssShadow(0f, 1f, 0f, 0f, 0x33FFDCAA, inset = true), CssShadow(0f, -3f, 0f, 0f, 0x59000000, inset = true),
    CssShadow(0f, 0f, 0f, 1f, 0x40000000, inset = true), CssShadow(0f, 2f, 3f, 0f, 0x59000000),
)
private val OPEN_SHADOWS = listOf(
    CssShadow(0f, 12f, 16f, 0f, 0x99000000), CssShadow(0f, 1f, 0f, 0f, 0x4DFFDCAA, inset = true), CssShadow(0f, 0f, 0f, 1f, 0x40000000, inset = true),
)
