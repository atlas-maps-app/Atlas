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

    Box(Modifier.fillMaxSize().background(Color(0xFF8DBB66)).grassStripes()) {
        BoardPage(background = Color.Transparent) {
            BoardTitleBar(
                stringResource(R.string.picnic_title), onBack = { nav.back() }, italic = false, size = 34, colour = Color.White, backBackground = Color.White.copy(alpha = 0.9f),
                trailing = {
                    val w = weather
                    val label = listOfNotNull(w?.let { "${it.temperature.roundToInt()}°" }, sunset?.let { DateTimeFormatter.ofPattern("H:mm").format(it) }).joinToString(" · ")
                    if (label.isNotEmpty()) Row(
                        Modifier.height(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        w?.let { Symbol(it.symbol, tint = Color(0xFFF2A71B), size = 16.dp) } ?: Symbol("weather.sunset", tint = Color(0xFFF2A71B), size = 16.dp)
                        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            Cloth(PLATES, found, plate, onPick = { plate = it })
            Spacer(Modifier.height(16.dp))
            if (plate == "play") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(stringResource(R.string.picnic_age_chip), null, toddler, stringResource(R.string.picnic_age_desc)) { toddler = !toddler }
                    FilterChip(stringResource(R.string.picnic_fenced), "ui.lock", fence, stringResource(R.string.picnic_fenced)) { fence = !fence }
                    FilterChip(stringResource(R.string.picnic_wc), null, wc, stringResource(R.string.picnic_wc_desc)) { wc = !wc }
                    FilterChip(null, "fact.wheelchair", step, stringResource(R.string.picnic_step_desc)) { step = !step }
                }
                Spacer(Modifier.height(12.dp))
            }
            val result = found?.get(plate)
            when {
                found == null -> Text(stringResource(R.string.kit_loading), color = Color.White, fontSize = 14.sp)
                items.isEmpty() -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xBFFBFAF4))) {
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
                        PlaceCard(f, here, facts.take(4), local, symbol = chosen.symbol, tile = Color(chosen.tint.toInt()), onClick = { services.remember(f.place); nav.go(Dest.Place.of(f.place)) })
                    }
                }
            }
        }
    }
}

/** Mown-grass stripes over the lawn colour, as the board's background. */
private fun Modifier.grassStripes(): Modifier = this.drawBehind {
    var x = 0f
    val light = Color(0x0DFFFFFF)
    while (x < size.width) { drawRect(light, Offset(x, 0f), Size(2.dp.toPx(), size.height)); x += 6.dp.toPx() }
}

@Composable
private fun Cloth(plates: List<Plate>, found: Map<String, FindResult>?, selected: String, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().height(330.dp).rotate(-2f).cssShadow(shape, 14.dp, 26.dp, Color(0x59143204)).clip(shape).background(Color(0xFFFFFDF8))) {
        // The gingham: red stripes a half-cell wide every 44 dp, both ways, so the squares where they cross are darker.
        Canvas(Modifier.fillMaxSize()) {
            val cell = 44.dp.toPx()
            val red = Color(0x8CC8322A)
            var x = 0f
            while (x < size.width) { drawRect(red, Offset(x, 0f), Size(cell / 2, size.height)); x += cell }
            var y = 0f
            while (y < size.height) { drawRect(red, Offset(0f, y), Size(size.width, cell / 2)); y += cell }
        }
        Column(Modifier.fillMaxWidth().rotate(2f).padding(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in plates.chunked(4)) {
                Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in row) PlateButton(p, found?.get(p.id)?.places?.size, p.id == selected, Modifier.weight(1f)) { onPick(p.id) }
                }
            }
        }
    }
}


@Composable
private fun PlateButton(p: Plate, count: Int?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(p.label)
    val tint = Color(p.tint.toInt())
    val text = if (count != null) "$label $count" else label
    val desc = stringResource(p.desc) + (count?.let { ", $it" } ?: "")
    Column(
        modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = desc; selected = on }.offset(y = if (on) (-4).dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val size = if (on) 80.dp else 74.dp
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension / 2
                drawCircle(Color(0x33000000), r, Offset(center.x, center.y + 3.dp.toPx()))
                if (on) drawCircle(Color(0xFFE8622C), r + 3.dp.toPx(), center)
                drawCircle(if (on) Color(0xFFFFF3E6) else Color.White, r, center)
                drawCircle(if (on) Color(0xFFFFE0C4) else Color(0xFFF4F1EA), r - 3.5.dp.toPx(), center, style = Stroke(7.dp.toPx()))
            }
            Glyph(p.symbol, if (on) Color(0xFFE8622C) else tint, if (on) 34.dp else 30.dp)
        }
        Text(
            text, color = if (on) Color.White else Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFFE8622C) else Color.White).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun FilterChip(text: String?, symbol: String?, on: Boolean, description: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(if (on) Forest else Color.White.copy(alpha = 0.9f)).clickable(role = androidx.compose.ui.semantics.Role.Switch, onClick = onClick)
            .semantics { contentDescription = description; selected = on }.padding(horizontal = if (text == null) 11.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Glyph(symbol, if (on) Color.White else Ink, 16.dp)
        if (text != null) Text(text, color = if (on) Color.White else Color(0xFF4B34B8).takeIf { text == "WC" } ?: Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
