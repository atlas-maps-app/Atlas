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

    Box(Modifier.fillMaxSize().walnut()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.local_title),
                        style = TextStyle(
                            fontFamily = LocalType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 46.sp,
                            brush = Brush.verticalGradient(listOf(Color(0xFFF6E2A8), Color(0xFFC9973E), Color(0xFFF1D38A))),
                        ),
                    )
                    val coords = coordsLabel(here)
                    Text(listOfNotNull(place, coords).joinToString(" · "), color = Color(0xFFE0C9A0), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    Modifier.height(42.dp).clip(CircleShape).background(Color(0x1FF4ECD8)).border(1.dp, Color(0x80E8C878), CircleShape).clickable { nav.go(Dest.Ask()) }.padding(start = 12.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.sparkles", tint = Gold, size = 16.dp)
                    Text(stringResource(R.string.local_ask), color = Color(0xFFF4E3B8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(16.dp))
            LabelCard(open?.let { id -> all.firstOrNull { it.id == id } }, all, snap, season, collect, zone, now, onClose = { open = null }, onOpen = { nav.go(it.dest) }, onGo = { nav.go(it) })
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24150B)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (row in all.chunked(3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        for (d in row) DrawerFront(d, d.id == open, Modifier.weight(1f)) { open = if (open == d.id) null else d.id }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Text(
                stringResource(R.string.local_sources), color = Color(0xB3E0C9A0), fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(LocalBottomBarInset.current + 8.dp))
        }
    }
}

/** The walnut: a vertical wash and the grain, long fine lines a little apart. */
private fun Modifier.walnut(): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF4A2E1B), Color(0xFF2A190E))))
    var x = 0f
    val dark = Color(0x17000000)
    val light = Color(0x0DFFDCAA)
    while (x < size.width) { drawRect(dark, Offset(x, 0f), Size(2.dp.toPx(), size.height)); x += 9.dp.toPx() }
    x = 4.dp.toPx()
    while (x < size.width) { drawRect(light, Offset(x, 0f), Size(1.dp.toPx(), size.height)); x += 13.dp.toPx() }
}

@Composable
private fun LabelCard(
    drawer: Drawer?, all: List<Drawer>, snap: SoonSnapshot?, season: SeasonNow, collect: String?, zone: java.time.ZoneId, now: java.time.Instant,
    onClose: () -> Unit, onOpen: (Drawer) -> Unit, onGo: (Dest) -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 92.dp).cssShadow(shape, 10.dp, 24.dp, Color(0x66000000)).clip(shape)
            .background(Parchment).drawBehind {
                drawRect(Brush.radialGradient(listOf(Color(0x73FFFFFF), Color(0x00FFFFFF)), center = Offset(size.width * 0.3f, size.height * 0.2f), radius = size.width * 0.6f))
            }.padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
    ) {
        // The brass pin the card hangs from.
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-18).dp).size(12.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFF7DC8C), Color(0xFFA87C2A)), center = Offset(4f, 4f), radius = 14f)),
        )
        if (drawer == null) StatusRow(snap, now, zone, onGo)
        else OpenedLabel(drawer, all.indexOf(drawer) + 1, factFor(drawer, snap, season, collect, zone, now), onClose = onClose, onOpen = { onOpen(drawer) })
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

/** The closed card: what is true here now, four things in a row. */
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
            StatusItem("weather.rain", Color(0xFF5A7ACA), value, label, Modifier.weight(1f)) { onGo(Dest.Sky) }
        }
        sunset?.let { StatusItem("weather.sunset", Color(0xFFC2641A), hm.format(it), stringResource(R.string.local_status_sunset), Modifier.weight(1f)) { onGo(Dest.Sky) } }
        StatusItem("ui.moon", Color(0xFF6B5438), "${(moon.illumination * 100).roundToInt()}%", stringResource(R.string.local_status_moon), Modifier.weight(1f)) { onGo(Dest.Moon) }
    }
}

@Composable
private fun StatusItem(symbol: String, tint: Color, value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Symbol(symbol, tint = tint, size = 24.dp)
        Text(value, style = TextStyle(fontFamily = SerifFamily, fontSize = 20.sp, lineHeight = 22.sp, color = Inkwell), maxLines = 1)
        Text(label, color = Sepia, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun OpenedLabel(d: Drawer, number: Int, fact: String, onClose: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.local_drawer_no, number), color = Color(0xFF8A6A3A), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Text(stringResource(d.title), Modifier.weight(1f), style = TextStyle(fontFamily = SerifFamily, fontSize = 22.sp, lineHeight = 24.sp, color = Inkwell), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.height(30.dp).clip(CircleShape).background(Inkwell).clickable(onClick = onOpen).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.local_open), color = Color(0xFFF4E3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            val close = stringResource(R.string.local_close)
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x1A3C2814)).clickable(onClick = onClose).semantics { contentDescription = close }, contentAlignment = Alignment.Center) {
                Symbol("ui.close", tint = Inkwell, size = 14.dp)
            }
        }
        Text(fact, color = Color(0xFF3A2616), fontSize = 13.sp, lineHeight = 17.5.sp)
    }
}

/** A drawer's front: a brass label holder with the paper label in it, and a brass pull; pulled out a little when open. */
@Composable
private fun DrawerFront(d: Drawer, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val dy by animateDpAsState(if (open) 7.dp else 0.dp, tween(250), label = "pull")
    val shape = RoundedCornerShape(5.dp)
    val title = stringResource(d.title)
    Box(modifier.height(80.dp).offset(y = dy)) {
        Column(
            Modifier.fillMaxSize()
                .then(if (open) Modifier.cssShadow(shape, 12.dp, 16.dp, Color(0x99000000)) else Modifier.cssShadow(shape, 2.dp, 3.dp, Color(0x59000000)))
                .clip(shape)
                .background(Brush.verticalGradient(if (open) listOf(Color(0xFF6E4629), Color(0xFF573520)) else listOf(Color(0xFF5E3B22), Color(0xFF4A2D18))))
                .drawWithContent {
                    drawContent()
                    drawRect(Color(0x33FFDCAA), Offset.Zero, Size(size.width, 1.dp.toPx()))
                    drawRect(Color(0x59000000), Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
                }
                .clickable(onClick = onClick).semantics { contentDescription = title },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier.padding(top = 9.dp).width(100.dp).height(34.dp).clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(0f to Color(0xFFF0D48A), 0.55f to Color(0xFFB38A36), 1f to Color(0xFFE2C06E))).padding(2.dp),
            ) {
                Row(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).background(if (open) Color(0xFFFFF7DE) else Color(0xFFF4ECD8)),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(d.symbol, tint = Color(0xFF6B4A22), size = 15.dp)
                    Text(title, style = TextStyle(fontFamily = SerifFamily, fontSize = 14.sp, color = Inkwell), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Box(
                Modifier.width(44.dp).height(11.dp).clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFE8C878), Color(0xFFA8802F)))),
            )
        }
    }
}
