package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.TagFilter
import app.atlas.ui.kit.CurrentWeather
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import app.atlas.geo.Sun
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.compositeOver
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssShadow

private val Ink = Color(0xFF1C2A1E)
private val Forest = Color(0xFF1F4A28)

/** One of the eight plates on the picnic cloth. */
private class Plate(val id: String, val filter: String, val symbol: String, val tint: Long, val label: Int, val desc: Int)

private val PLATES = listOf(
    Plate("tables", "leisure=picnic_table", "place.picnic", 0xFF1F4A28, R.string.picnic_tables, R.string.picnic_tables_desc),
    Plate("grill", "amenity=bbq", "place.bbq.2", 0xFFB8461A, R.string.picnic_grill, R.string.picnic_grill_desc),
    Plate("play", "leisure=playground", "place.playground", 0xFFE8622C, R.string.picnic_play, R.string.picnic_play_desc),
    Plate("dogs", "leisure=dog_park", "fact.dog", 0xFF12A9A6, R.string.picnic_dogs, R.string.picnic_dogs_desc),
    Plate("lawn", "landuse=grass or natural=grassland or leisure=common", "place.lawn", 0xFF2F6B3A, R.string.picnic_lawn, R.string.picnic_lawn_desc),
    Plate("splash", "playground=splash_pad or leisure=water_park", "place.water-play", 0xFF1560A8, R.string.picnic_splash, R.string.picnic_splash_desc),
    Plate("fitness", "leisure=fitness_station", "place.fitness", 0xFF7B4DFF, R.string.picnic_fitness, R.string.picnic_fitness_desc),
    Plate("courts", "leisure=pitch", "place.pitch", 0xFFC8322A, R.string.picnic_courts, R.string.picnic_courts_desc),
)

/** The radius round you that the plates count within. */
private const val RADIUS = 1_500.0

/**
 * Picnic and play: the eight plates of a gingham cloth (tables, grills, playgrounds, dog parks, lawns, splash pads, fitness
 * stations, courts) with how many there are within 1.5 km, and the nearest of the chosen kind below, with the children's
 * filters for playgrounds (ages, fenced, a toilet near, step-free).
 */
@Composable
fun PicnicScreen(args: Dest.Picnic, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val local = remember(clock) { LocalDateTime.ofInstant(now, zone) }
    var plate by rememberSaveable { mutableStateOf(args.kind?.takeIf { k -> PLATES.any { it.id == k } } ?: "play") }
    var toddler by rememberSaveable { mutableStateOf(false) }
    var fence by rememberSaveable { mutableStateOf(false) }
    var wc by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(false) }

    val found by produceState<Map<String, FindResult>?>(null, here) {
        val kinds = PLATES.associate { it.id to TagFilter.parse(it.filter) } + ("toilets" to TagFilter.parse("amenity=toilets"))
        value = services.local.finder.findMany(kinds, here, RADIUS, limit = 99)
    }
    val weather by produceState<CurrentWeather?>(null, here) {
        val f = runCatching { if (services.local.online()) services.weather.forecast(here) else services.weather.kept(here) }.getOrNull()
        value = CurrentWeather.of(f, now)
    }
    val sunset = remember(here, now) { Sun.times(LocalDate.ofInstant(now, zone), here.lat, here.lon, zone).sunset }
    val toilets = found?.get("toilets")?.places.orEmpty()
    val chosen = PLATES.first { it.id == plate }
    val items = found?.get(plate)?.places.orEmpty().filter { f ->
        val t = f.place.tags
        (plate != "play" || !toddler || takesAge(t, 2)) && (plate != "play" || !fence || fenced(t)) &&
            (!wc || nearestWithin(f.place, toilets, 150.0) != null) && (!step || t["wheelchair"] == "yes")
    }

    // The board's lawn: the green with its mown stripes, two fine repeating gradients.
    Box(Modifier.fillMaxSize().background(Color(0xFF8DBB66)).boardBox(grad = LAWN, clip = false)) {
        BoardPage(background = Color.Transparent) {
            // The header: back at (16, 42), the title at (72, 46) in white with its shadow, the weather pill 16 px from the right at 48.
            Box(Modifier.fillMaxWidth().height(86.dp)) {
                BackCircle({ nav.back() }, Modifier.padding(top = 42.dp), background = Color(0xE6FFFFFF), tint = Ink)
                BoardLine(
                    stringResource(R.string.picnic_title), boardStyle(34f, 400, 0xFFFFFFFF, 34f, SERIF, shadow = CssShadow(0f, 2f, 6f, 0f, 0x59002800)),
                    Modifier.padding(start = 56.dp, top = 46.dp).semantics { heading() },
                )
                val w = weather
                val label = listOfNotNull(w?.let { "${it.temperature.roundToInt()}°" }, sunset?.let { DateTimeFormatter.ofPattern("H:mm").format(it) }).joinToString(" · ")
                if (label.isNotEmpty()) Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 48.dp).height(38.dp).boardBox(0xE6FFFFFF, corners(19f)).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    w?.let { Symbol(it.symbol, tint = Color(0xFFF2A71B), size = 16.dp) } ?: Symbol("weather.sunset", tint = Color(0xFFF2A71B), size = 16.dp)
                    BoardLine(label, boardStyle(13f, 700, Ink.css(), 17f, tnum = true))
                }
            }
            Spacer(Modifier.height(22.dp))
            Cloth(PLATES, found, plate, onPick = { plate = it })
            Spacer(Modifier.height(24.dp))
            if (plate == "play") {
                // The children's filters: 40 px chips, 8 apart.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(stringResource(R.string.picnic_age_chip), null, toddler, stringResource(R.string.picnic_age_desc)) { toddler = !toddler }
                    FilterChip(stringResource(R.string.picnic_fenced), "fact.fenced", fence, stringResource(R.string.picnic_fenced)) { fence = !fence }
                    FilterChip(stringResource(R.string.picnic_wc), null, wc, stringResource(R.string.picnic_wc_desc), ink = 0xFF4B34B8) { wc = !wc }
                    FilterChip(null, "fact.wheelchair", step, stringResource(R.string.picnic_step_desc)) { step = !step }
                }
                Spacer(Modifier.height(20.dp))
            }
            val result = found?.get(plate)
            when {
                found == null -> BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 700, 0xFFFFFFFF, 18f))
                items.isEmpty() -> Box(Modifier.fillMaxWidth().boardBox(0xBFFBFAF4, corners(24f))) {
                    StateMessage(
                        if (result?.error != null) "ui-cloud-off" else chosen.symbol, stringResource(if (result?.error != null) R.string.picnic_offline else R.string.picnic_none, shortDistance(RADIUS)),
                        text = if (result?.error == null && result?.origin == app.atlas.services.PlaceOrigin.STORE && !services.local.online()) stringResource(R.string.picnic_offline_text) else null,
                        tint = if (result?.error != null) Color(0xFFC8322A) else Color(0xFF4E5E50),
                    )
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (f in items.take(6)) {
                        val t = f.place.tags
                        val facts = buildList {
                            ageRange(t)?.let { add(it) }
                            if (fenced(t)) add(stringResource(R.string.picnic_fenced))
                            nearestWithin(f.place, toilets, 150.0)?.let { add(stringResource(R.string.picnic_wc_at, shortDistance(it.distanceM))) }
                            if (t["wheelchair"] == "yes") add(stringResource(R.string.picnic_step_free))
                            if (t["covered"] == "yes") add(stringResource(R.string.picnic_covered))
                            t["surface"]?.takeIf { plate == "play" }?.let { add(it.replace('_', ' ')) }
                        }
                        val shut = remember(f, local) { isShut(f.place, here, local) }
                        PlaceCard(
                            f, here, facts.take(4), local, PICNIC_CARD, tile = { dim -> PlateTile(chosen, dim) }, dim = shut,
                            onClick = { services.remember(f.place); nav.go(Dest.Place.of(f.place)) },
                        )
                    }
                }
            }
        }
    }
}

/** The board's lawn stripes: `repeating-linear-gradient(100deg, white 5% 0 2px, clear 2px 6px)` over `(80deg, green 6% 0 1px, clear 1px 5px)`. */
private val LAWN = listOf(
    cssRepeatingLinear(100f, 6f, 0f to 0x0DFFFFFF, 2f to 0x0DFFFFFF, 2f to 0x00FFFFFF, 6f to 0x00FFFFFF),
    cssRepeatingLinear(80f, 5f, 0f to 0x0F003C00, 1f to 0x0F003C00, 1f to 0x00003C00, 5f to 0x00003C00),
)

/** The board's picnic card: 86 px, radius 24, warm white with a green-tinged shadow; a shut place fades and loses its shadow. */
private val PICNIC_CARD = CardLook(
    bg = 0xFFFBFAF4, radius = 24f, minHeight = 86f, shadows = shadow(0f, 6f, 14f, 0x2E14320A), ink = 0xFF1C2A1E, soft = 0xFF4E5E50,
    walkSize = 18f, walkLine = 23.5f, factGap = 8f, dimBg = 0xBFFBFAF4, dimShadows = emptyList(), dimWalk = 0xFF676D7C,
)

/** True when the place's hours say it is shut now (closed, or opening later). */
internal fun isShut(place: app.atlas.model.Place, here: app.atlas.geo.LatLon, now: LocalDateTime): Boolean {
    val s = app.atlas.ui.kit.PlaceSummary.of(place, here, null, now)
    val w = app.atlas.ui.kit.OpenWords.of(s, now)
    return w is app.atlas.ui.kit.OpenWords.Opens || w == app.atlas.ui.kit.OpenWords.Closed
}

/** The light ground and ring of a plate's tile: the board's for the playground and the splash pad, else the colour at 10 and 22 per cent. */
private fun light(p: Plate): Pair<Argb, Argb> = when (p.id) {
    "play" -> 0xFFFFF3E6 to 0xFFFFE0C4
    "splash" -> 0xFFE4F0FB to mix(p.tint, 0.22f)
    else -> mix(p.tint, 0.10f) to mix(p.tint, 0.22f)
}

private fun mix(c: Argb, a: Float): Argb = argb(c).copy(alpha = a).compositeOver(Color.White).css()

/** A card's tile: a 54 px circle of the plate's light colour with a 5 px ring (none when shut) and its 26 px symbol. */
@Composable
private fun PlateTile(p: Plate, dim: Boolean) {
    val (ground, ring) = light(p)
    Box(Modifier.size(54.dp).boardBox(ground, corners(27f), if (dim) emptyList() else shadow(0f, 0f, 0f, ring, spread = 5f, inset = true)), contentAlignment = Alignment.Center) {
        Glyph(p.symbol, argb(p.tint), 26.dp)
    }
}

/**
 * The gingham cloth: 376 x 330 turned 2 degrees anticlockwise, cream with red bands of half a 44 px square both ways
 * (darker where they cross), radius 4 and its shadow; the eight plates on it, turned back upright, 14 by 18 px in.
 */
@Composable
private fun Cloth(plates: List<Plate>, found: Map<String, FindResult>?, selected: String, onPick: (String) -> Unit) {
    Box(
        Modifier.padding(start = 2.dp).size(376.dp, 330.dp).rotate(-2f)
            .boardBox(0xFFFFFDF8, corners(4f), shadow(0f, 14f, 26f, 0x5914320A), clip = false)
            .drawBehind {
                val cell = 44.dp.toPx()
                val half = cell / 2
                val red = Color(0x8CC8322A)
                clipRect {
                    var x = 0f
                    while (x < size.width) { drawRect(red, Offset(x, 0f), Size(half, size.height)); x += cell }
                    var y = half
                    while (y < size.height) { drawRect(red, Offset(0f, y), Size(size.width, half)); y += cell }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp).rotate(2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in plates.chunked(4)) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in row) PlateButton(p, found?.get(p.id)?.places?.size, p.id == selected, Modifier.weight(1f).fillMaxHeight()) { onPick(p.id) }
                }
            }
        }
    }
}

/**
 * A plate: 74 px white with a 7 px rim and its shadow, the 30 px symbol, the count on a white tag 4 px under it (Arial
 * 12 at 800, as the board's buttons draw it); the chosen one 80 px in apricot with an orange ring, raised 4 px.
 */
@Composable
private fun PlateButton(p: Plate, count: Int?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(p.label)
    val text = if (count != null) "$label $count" else label
    val desc = stringResource(p.desc) + (count?.let { ", $it" } ?: "")
    Column(
        modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = desc; selected = on }.offset(y = if (on) (-4).dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val side = if (on) 80f else 74f
        Box(
            Modifier.size(side.dp).boardBox(
                if (on) 0xFFFFF3E6 else 0xFFFFFFFF, corners(side / 2),
                if (on) listOf(CssShadow(0f, 0f, 0f, 7f, 0xFFFFE0C4, inset = true), CssShadow(0f, 0f, 0f, 3f, 0xFFE8622C), CssShadow(0f, 8f, 12f, 0f, 0x40000000))
                else listOf(CssShadow(0f, 0f, 0f, 7f, 0xFFF4F1EA, inset = true), CssShadow(0f, 4f, 8f, 0f, 0x33000000)),
                clip = false,
            ),
            contentAlignment = Alignment.Center,
        ) { Glyph(p.symbol, if (on) Color(0xFFE8622C) else argb(p.tint), if (on) 34.dp else 30.dp) }
        Box(Modifier.height(16.dp).boardBox(if (on) 0xFFE8622C else 0xFFFFFFFF, corners(8f)).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
            BoardLine(text, boardStyle(12f, 800, if (on) 0xFFFFFFFF else 0xFF000000, 13.5f, "Arial", tnum = true))
        }
    }
}

/** A filter chip of the board: 40 px, radius 20; forest green when on; 12 px in for words, 10 before a 16 px symbol, a 40 px circle for a symbol alone. */
@Composable
private fun FilterChip(text: String?, symbol: String?, on: Boolean, description: String, ink: Argb = 0xFF1C2A1E, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).then(if (text == null) Modifier.width(40.dp) else Modifier).boardBox(if (on) Forest.css() else 0xE6FFFFFF, corners(20f))
            .clickable(role = androidx.compose.ui.semantics.Role.Switch, onClick = onClick)
            .semantics { contentDescription = description; selected = on }
            .padding(start = if (text == null) 0.dp else if (symbol != null) 10.dp else 12.dp, end = if (text == null) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Glyph(symbol, if (on) Color.White else Ink, if (text == null) 17.dp else 16.dp)
        if (text != null) BoardLine(text, boardStyle(12f, 800, if (on) 0xFFFFFFFF else ink, 15.5f, tnum = true))
    }
}
