package app.atlas.ui.local

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.geo.Moon
import app.atlas.geo.Sun
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.nav.Tab
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.local.astro.Astro
import app.atlas.ui.local.astro.GeoPoint
import app.atlas.ui.local.astro.NightView
import app.atlas.ui.local.astro.OrthoView
import app.atlas.ui.local.astro.Planet
import app.atlas.ui.local.astro.PlanetNight
import app.atlas.ui.local.astro.Sgp4
import app.atlas.ui.local.astro.Tonight
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** The stops of the zoom ladder, from the street out to the galaxy. */
enum class CosmosStop(val symbol: String) {
    STREET("zoom.street"), EARTH("ui.globe"), MOON("ui.moon"), PLANETS("weather.sun"), GALAXY("zoom.galaxy"),
}

/** The zoom ladder opened at the Earth, with the day and night turning under the sun. */
@Composable
fun GlobeScreen(args: Dest.Globe, nav: Navigator) = CosmosScreen(CosmosStop.EARTH, nav)

/** The zoom ladder opened at the Moon. */
@Composable
fun MoonScreen(args: Dest.Moon, nav: Navigator) = CosmosScreen(CosmosStop.MOON, nav)

/** The zoom ladder opened at the solar system. */
@Composable
fun PlanetsScreen(args: Dest.Planets, nav: Navigator) = CosmosScreen(CosmosStop.PLANETS, nav)

/** The zoom ladder opened at the galaxy. */
@Composable
fun GalaxyScreen(args: Dest.Galaxy, nav: Navigator) = CosmosScreen(CosmosStop.GALAXY, nav)

private val ZoomEase = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
private val Night = Color(0xFF05070D)
private val Dim = Color(0xFF9FAAC2)
private val Faint = Color(0xFF8C97AE)
private val Gold = Color(0xFFFFE7A3)

/**
 * One continuous zoom from your street to the galaxy (the Globe, Planets and Galaxy boards are one component at three
 * stops): the pictures cross-fade while they scale about the middle, the rail under the title picks a stop, and the card
 * at the bottom says what you are looking at, from live data: the sun and the Moon from `:core`, the space station from
 * SGP4 on its two-line elements, the planets from JPL's orbital elements.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CosmosScreen(initial: CosmosStop, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.local
    val data = hub.cosmos
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    // The station moves 7.7 km a second: the picture takes the time again every half minute.
    var now by remember(clock) { mutableStateOf(clock.instant()) }
    LaunchedEffect(clock) { while (true) { delay(30_000); now = clock.instant() } }
    var stop by rememberSaveable { mutableIntStateOf(if (initial == CosmosStop.STREET) CosmosStop.EARTH.ordinal else initial.ordinal) }
    var lon0 by rememberSaveable { mutableFloatStateOf(here.lon.toFloat()) }
    var tilt by rememberSaveable { mutableFloatStateOf(20f) }
    var spin by rememberSaveable { mutableStateOf(false) }
    var flow by rememberSaveable { mutableStateOf(false) }
    var dayOffset by rememberSaveable { mutableFloatStateOf(0f) }

    LaunchedEffect(spin, flow, stop) {
        var last = 0L
        while (spin || flow) {
            val t = withFrameNanos { it }
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                if (spin && (stop == CosmosStop.EARTH.ordinal || stop == CosmosStop.MOON.ordinal)) lon0 -= 8f * dt
                if (flow && stop == CosmosStop.PLANETS.ordinal) dayOffset += 12f * dt
            }
            last = t
        }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")

    val outlook by produceState<IssOutlook?>(null, here, now) { value = hub.iss(here, now) }
    val iss = remember(outlook, now) { issFrame(outlook, now) }
    val moon = remember(now) { Moon.phase(now) }
    val textMeasurer = rememberTextMeasurer()
    val boardText = remember(textMeasurer) { BoardText(textMeasurer, InterFamily) }
    val planetNames = planetNames()
    val youLabel = stringResource(R.string.cosmos_art_you)
    val hereLabel = stringResource(R.string.cosmos_art_here)
    val scaleLabel = stringResource(R.string.cosmos_art_scale)
    val sunlight = stringResource(R.string.cosmos_art_sunlight)
    val moonLabel = stringResource(R.string.cosmos_art_moon, (moon.illumination * 100).roundToInt())
    val earthLabel = stringResource(R.string.cosmos_earth)
    val placeName = rememberPlaceName(here)

    Box(
        Modifier
            .fillMaxSize()
            .background(Night),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(0f to Color(0xFF0C1428), 0.7f to Night, center = Offset(size.width * 0.5f, size.height * 0.45f), radius = max(size.width, size.height) * 0.75f))
            drawBackdrop(data)
        }
        for (stage in CosmosStop.entries) {
            if (stage == CosmosStop.STREET) continue
            val i = stage.ordinal
            val s by animateFloatAsState(if (i == stop) 1f else if (i < stop) 0.18f else 4f, tween(900, easing = ZoomEase), label = "scale$i")
            val a by animateFloatAsState(if (i == stop) 1f else 0f, tween(700), label = "alpha$i")
            if (a < 0.003f && i != stop) continue
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = s; scaleY = s; alpha = a; transformOrigin = TransformOrigin(0.5f, 420f / 915f) }
                    .then(
                        if (stage == CosmosStop.EARTH) Modifier.pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                lon0 -= drag.x * 0.35f
                                tilt = (tilt + drag.y * 0.2f).coerceIn(-50f, 70f)
                                spin = false
                            }
                        } else Modifier,
                    ),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val view = OrthoView(((lon0 + 540f) % 360f - 180f).toDouble(), tilt.toDouble())
                    when (stage) {
                        CosmosStop.EARTH -> drawEarth(data, EarthFrame(view, now, here, iss, pulse))
                        CosmosStop.MOON -> drawMoonScene(data, now, moon.illumination, moon.phase, moonLabel, earthLabel, sunlight, boardText)
                        CosmosStop.PLANETS -> drawSolarSystem(now.plusSeconds((dayOffset * 86400).toLong()), planetNames, youLabel, boardText)
                        CosmosStop.GALAXY -> drawGalaxy(data.galaxy, pulse, hereLabel, scaleLabel, boardText)
                        else -> Unit
                    }
                }
            }
        }
        if (stop == CosmosStop.EARTH.ordinal) PinLabel(OrthoView(((lon0 + 540f) % 360f - 180f).toDouble(), tilt.toDouble()), here, placeName)

        val statusTop = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)
        Column(Modifier.fillMaxWidth().padding(top = statusTop + 18.dp, start = 12.dp, end = 12.dp)) {
            Header(CosmosStop.entries[stop], onBack = { nav.back() })
            Spacer(Modifier.height(12.dp))
            Rail(
                stop,
                onPick = { picked -> if (picked == CosmosStop.STREET.ordinal) nav.tab(Tab.MAP) else stop = picked },
                onIn = { if (stop <= 1) nav.tab(Tab.MAP) else stop-- },
                onOut = { stop = (stop + 1).coerceAtMost(CosmosStop.GALAXY.ordinal) },
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xCC0C101C))
                .heightIn(min = 204.dp)
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (CosmosStop.entries[stop]) {
                CosmosStop.STREET -> Unit
                CosmosStop.EARTH -> EarthCard(
                    now, here, zone, outlook,
                    spinning = spin, onSpin = { spin = !spin }, onFaceMe = { lon0 = here.lon.toFloat(); tilt = (here.lat.toFloat() * 0.4f).coerceIn(10f, 40f); spin = false },
                    onSky = { nav.go(Dest.Sky) }, onGps = { nav.go(Dest.Satellites) },
                )
                CosmosStop.MOON -> MoonCard(now, here, moon.illumination, moon.phase, onSky = { nav.go(Dest.Sky) }, onPlanets = { stop = CosmosStop.PLANETS.ordinal })
                CosmosStop.PLANETS -> PlanetsCard(
                    now, dayOffset, flow, zone, here,
                    onFlow = { flow = !flow }, onToday = { dayOffset = 0f },
                )
                CosmosStop.GALAXY -> GalaxyCard(onSky = { nav.go(Dest.Sky) }, onEarth = { stop = CosmosStop.EARTH.ordinal })
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------------------------------------

@Composable
private fun planetNames(): Map<Planet, String> = mapOf(
    Planet.JUPITER to stringResource(R.string.cosmos_planet_jupiter), Planet.SATURN to stringResource(R.string.cosmos_planet_saturn),
    Planet.URANUS to stringResource(R.string.cosmos_planet_uranus), Planet.NEPTUNE to stringResource(R.string.cosmos_planet_neptune),
    Planet.MARS to stringResource(R.string.cosmos_planet_mars),
)

/** The compass name of an azimuth in the reader's language. */
@Composable
internal fun compassName(azimuthDeg: Double): String = when (Astro.compass8(azimuthDeg)) {
    "N" -> stringResource(R.string.cosmos_compass_n); "NE" -> stringResource(R.string.cosmos_compass_ne)
    "E" -> stringResource(R.string.cosmos_compass_e); "SE" -> stringResource(R.string.cosmos_compass_se)
    "S" -> stringResource(R.string.cosmos_compass_s); "SW" -> stringResource(R.string.cosmos_compass_sw)
    "W" -> stringResource(R.string.cosmos_compass_w); else -> stringResource(R.string.cosmos_compass_nw)
}

private fun issFrame(outlook: IssOutlook?, now: Instant): IssFrame? {
    val orbit = outlook?.orbit ?: return null
    if (orbit.useless) return null
    val sgp = Sgp4(orbit.tle)
    fun point(t: Instant): GeoPoint? {
        val s = sgp.propagate(t) ?: return null
        val e = Astro.temeToEcef(s.x, s.y, s.z, Astro.gmst(t))
        val (lat, lon) = Astro.geodetic(e.x, e.y, e.z)
        return GeoPoint(lat, lon)
    }
    val here = point(now)
    val track = (-30..60).mapNotNull { point(now.plus(Duration.ofMinutes(it.toLong()))) }
    val pass = outlook.nextVisible?.track?.map { GeoPoint(it.latDeg, it.lonDeg) }.orEmpty()
    return IssFrame(here, track, pass)
}

@Composable
private fun Header(stop: CosmosStop, onBack: () -> Unit) {
    val back = stringResource(R.string.kit_back)
    val (title, sub) = when (stop) {
        CosmosStop.STREET -> R.string.cosmos_title_street to R.string.cosmos_sub_street
        CosmosStop.EARTH -> R.string.cosmos_title_earth to R.string.cosmos_sub_earth
        CosmosStop.MOON -> R.string.cosmos_title_moon to R.string.cosmos_sub_moon
        CosmosStop.PLANETS -> R.string.cosmos_title_planets to R.string.cosmos_sub_planets
        CosmosStop.GALAXY -> R.string.cosmos_title_galaxy to R.string.cosmos_sub_galaxy
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.back", tint = Color.White, size = 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), color = Color.White, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 28.sp, letterSpacing = (-0.01).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(sub), color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Rail(stop: Int, onPick: (Int) -> Unit, onIn: () -> Unit, onOut: () -> Unit) {
    val labels = listOf(R.string.cosmos_street, R.string.cosmos_earth, R.string.cosmos_moon, R.string.cosmos_planets, R.string.cosmos_galaxy)
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(CircleShape)
            .background(Color(0xB80E121E))
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton("+", stringResource(R.string.cosmos_zoom_in), onIn)
        BoxWithConstraints(Modifier.weight(1f).height(62.dp)) {
            val frac by animateFloatAsState(0.1f + 0.2f * stop, tween(600, easing = ZoomEase), label = "thumb")
            Box(Modifier.padding(horizontal = 27.dp - 0.dp).offset(y = 23.dp).fillMaxWidth().height(2.dp).background(Color(0x24FFFFFF)))
            Box(
                Modifier.offset(x = maxWidth * frac - 17.dp, y = 6.dp).size(34.dp)
                    .background(Color(0x24FFFFFF), CircleShape).padding(2.dp).background(Color.White, CircleShape),
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                for ((i, stopEntry) in CosmosStop.entries.withIndex()) {
                    val on = i == stop
                    val name = stringResource(labels[i])
                    val fg by animateFloatAsState(if (on) 1f else 0f, tween(300), label = "fg$i")
                    Column(
                        Modifier.width(54.dp).clickable(role = Role.Button) { onPick(i) }.semantics { contentDescription = name; selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Symbol(stopEntry.symbol, tint = androidx.compose.ui.graphics.lerp(Color(0xFFC9D2E6), Color(0xFF0B0F1A), fg), size = 18.dp)
                        }
                        Text(name, color = androidx.compose.ui.graphics.lerp(Color(0xFF8C97AE), Color.White, fg), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        RoundButton("−", stringResource(R.string.cosmos_zoom_out), onOut)
    }
}

@Composable
private fun RoundButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0x1AFFFFFF)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}

/** The "You · place" chip beside the pin on the globe. */
@Composable
private fun PinLabel(view: OrthoView, here: LatLon, place: String?) {
    val at = remember(view.centreLonDeg, view.tiltDeg, here) { youOnBoard(view, here) } ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val s = minOf(maxWidth.value / CosmosFrame.W, maxHeight.value / CosmosFrame.H)
        val ox = (maxWidth.value - CosmosFrame.W * s) / 2f
        val oy = (maxHeight.value - CosmosFrame.H * s) / 2f
        val px = ox + at.x * s
        val py = oy + at.y * s
        val left = if (at.x > 250f) px - 140f * s else px + 12f * s
        Text(
            if (place != null) stringResource(R.string.cosmos_you_at, place) else stringResource(R.string.cosmos_art_you),
            Modifier.offset(x = left.dp, y = (py - 30f * s).dp).clip(RoundedCornerShape(11.dp)).background(Color(0xCC0A0E18)).padding(horizontal = 9.dp, vertical = 3.dp),
            color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

@Composable
private fun Tile(background: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(background), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun InfoRow(tile: @Composable () -> Unit, title: String, sub: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        tile()
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFEEF2FA), fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 17.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = Color(0x1FFFFFFF), icon: String? = null) {
    Row(
        modifier.height(40.dp).clip(CircleShape).background(background).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Symbol(icon, tint = Color.White, size = 14.dp)
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, Modifier.fillMaxWidth(), color = Faint, fontSize = 10.sp, lineHeight = 13.5.sp)
}

private val HM = DateTimeFormatter.ofPattern("H:mm")

/** The planets the board names for tonight: the ones the eye finds. */
internal val NAKED_EYE = listOf(Planet.MERCURY, Planet.VENUS, Planet.MARS, Planet.JUPITER, Planet.SATURN)

@Composable
private fun EarthCard(
    now: Instant, here: LatLon, zone: ZoneId, outlook: IssOutlook?,
    spinning: Boolean, onSpin: () -> Unit, onFaceMe: () -> Unit, onSky: () -> Unit, onGps: () -> Unit,
) {
    val sun = remember(now, here) { Sun.times(LocalDate.ofInstant(now, zone), here.lat, here.lon, zone) }
    val clockNow = HM.format(now.atZone(zone))
    val nextSet = sun.sunset?.toInstant()?.takeIf { it.isAfter(now) }
    val nextRise = if (nextSet == null) Sun.times(LocalDate.ofInstant(now, zone).plusDays(1), here.lat, here.lon, zone).sunrise else null
    val title = when {
        nextSet != null -> stringResource(R.string.cosmos_you_time_sunset, clockNow, HM.format(nextSet.atZone(zone)))
        nextRise != null -> stringResource(R.string.cosmos_you_time_sunrise, clockNow, HM.format(nextRise))
        else -> stringResource(R.string.cosmos_you_time, clockNow)
    }
    InfoRow(
        tile = { Tile(Gold) { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFD24A))) } },
        title = title, sub = stringResource(R.string.cosmos_lights),
    )
    val pass = outlook?.nextVisible
    val sub = when {
        outlook == null -> ""
        outlook.noData -> stringResource(R.string.cosmos_iss_nodata)
        pass != null -> {
            val minutes = max(1, ((pass.visibleDuration.seconds + 30) / 60).toInt())
            val base = stringResource(R.string.cosmos_iss_pass, HM.format(pass.peak.atZone(zone)), minutes)
            if (outlook.orbit?.stale == true) stringResource(R.string.cosmos_iss_stale, outlook.orbit.ageDays.roundToInt()) else base
        }
        outlook.orbit?.stale == true -> stringResource(R.string.cosmos_iss_stale, outlook.orbit.ageDays.roundToInt())
        else -> stringResource(R.string.cosmos_iss_none)
    }
    InfoRow(
        tile = { Tile(Color(0xFFCFE0FF)) { Symbol("sky.satellite", tint = Color(0xFF0B0F1A), size = 17.dp) } },
        title = stringResource(R.string.cosmos_iss_title, 92), sub = sub,
        modifier = Modifier.clickable(role = Role.Button, onClick = onSky),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(if (spinning) R.string.cosmos_pause else R.string.cosmos_spin), onSpin, icon = if (spinning) "ui.pause" else "ui.play")
        Pill(stringResource(R.string.cosmos_face_me), onFaceMe)
        Pill(stringResource(R.string.cosmos_gps), onGps)
    }
    Footer(stringResource(R.string.cosmos_earth_source))
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

@Composable
private fun MoonCard(now: Instant, here: LatLon, illumination: Double, phase: Double, onSky: () -> Unit, onPlanets: () -> Unit) {
    val zone = appClock().zone
    val pos = remember(now, here) { Moon.position(now, here.lat, here.lon) }
    val age = (phase * 29.530588).roundToInt()
    val (full, new) = remember(now) { nextFullAndNew(now) }
    val lit = (illumination * 100).roundToInt()
    InfoRow(
        tile = { Tile(Color(0xFFE9E6DD)) { Symbol("ui.moon", tint = Color(0xFF0B0F1A), size = 17.dp) } },
        title = stringResource(if (phase < 0.5) R.string.cosmos_moon_title_waxing else R.string.cosmos_moon_title_waning, lit),
        sub = stringResource(R.string.cosmos_moon_age, age, DAY.format(full.atZone(zone)), DAY.format(new.atZone(zone))),
    )
    val km = String.format(Locale.getDefault(), "%,d", pos.distanceKm.roundToInt())
    val seconds = String.format(Locale.getDefault(), "%.1f", pos.distanceKm / 299_792.458)
    InfoRow(
        tile = { Tile(Color(0xFF9CC8F0)) { Text("$seconds s", color = Color(0xFF0B0F1A), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) } },
        title = stringResource(R.string.cosmos_moon_distance, km), sub = stringResource(R.string.cosmos_moon_light, seconds),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.cosmos_tonights_sky), onSky)
        Pill(stringResource(R.string.cosmos_planets_minus), onPlanets)
    }
    Footer(stringResource(R.string.cosmos_moon_source))
}

/** The next full and the next new Moon after [now], found by stepping over the phase. */
internal fun nextFullAndNew(now: Instant): Pair<Instant, Instant> {
    var full: Instant? = null
    var new: Instant? = null
    var prev = Moon.phase(now).phase
    var t = now
    var i = 0
    while ((full == null || new == null) && i < 31 * 8) {
        t = t.plus(Duration.ofHours(3))
        val p = Moon.phase(t).phase
        if (full == null && prev < 0.5 && p >= 0.5) full = t
        if (new == null && prev > 0.9 && p < 0.1) new = t
        prev = p
        i++
    }
    return (full ?: now) to (new ?: now)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanetsCard(now: Instant, dayOffset: Float, flowing: Boolean, zone: ZoneId, here: LatLon, onFlow: () -> Unit, onToday: () -> Unit) {
    val at = now.plusSeconds((dayOffset * 86400).toLong())
    val label = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(at.atZone(zone))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.cosmos_where_they_are), color = Dim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp, maxLines = 1)
            Text(label, color = Color(0xFFEEF2FA), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Pill(stringResource(if (flowing) R.string.cosmos_pause else R.string.cosmos_let_time_run), onFlow, icon = if (flowing) "ui.pause" else "ui.play")
        Pill(stringResource(R.string.cosmos_today), onToday)
    }
    val tonight = remember(now, here) { Tonight.planets(now, here.lat, here.lon, zone, NAKED_EYE).filter { it.view != NightView.NOT_UP } }
    if (tonight.isEmpty()) {
        Text(stringResource(R.string.cosmos_planet_none), color = Dim, fontSize = 12.sp)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in tonight) PlanetChip(p)
        }
    }
    Footer(stringResource(R.string.cosmos_planets_source))
}

@Composable
private fun PlanetChip(p: PlanetNight) {
    val name = when (p.planet) {
        Planet.MERCURY -> stringResource(R.string.cosmos_planet_mercury); Planet.VENUS -> stringResource(R.string.cosmos_planet_venus)
        Planet.MARS -> stringResource(R.string.cosmos_planet_mars); Planet.JUPITER -> stringResource(R.string.cosmos_planet_jupiter)
        Planet.SATURN -> stringResource(R.string.cosmos_planet_saturn); Planet.URANUS -> stringResource(R.string.cosmos_planet_uranus)
        Planet.NEPTUNE -> stringResource(R.string.cosmos_planet_neptune); Planet.EARTH -> stringResource(R.string.cosmos_planet_earth)
    }
    val low = if (p.low) stringResource(R.string.cosmos_planet_low, compassName(p.bestAzimuthDeg)) else ""
    val text = when (p.view) {
        NightView.EVENING -> stringResource(R.string.cosmos_planet_evening, name, low)
        NightView.BEFORE_DAWN -> stringResource(R.string.cosmos_planet_dawn, name, low)
        NightView.ALL_NIGHT -> stringResource(R.string.cosmos_planet_night, name, low)
        else -> stringResource(R.string.cosmos_planet_late, name, low)
    }
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1AFFFFFF)).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(planetColour(p.planet)))
        Text(text, color = Color(0xFFEEF2FA), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun GalaxyCard(onSky: () -> Unit, onEarth: () -> Unit) {
    InfoRow(
        tile = { Tile(Color(0xFFC9B8FF)) { Symbol("zoom.galaxy", tint = Color(0xFF0B0F1A), size = 18.dp) } },
        title = stringResource(R.string.cosmos_galaxy_line), sub = stringResource(R.string.cosmos_galaxy_orbit),
    )
    InfoRow(
        tile = { Tile(Gold) { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFD24A))) } },
        title = stringResource(R.string.cosmos_galaxy_here), sub = stringResource(R.string.cosmos_galaxy_centre),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.cosmos_tonights_sky), onSky, background = Color(0xFF5B3FD6))
        Pill(stringResource(R.string.cosmos_earth), onEarth)
    }
    Footer(stringResource(R.string.cosmos_galaxy_source))
}
