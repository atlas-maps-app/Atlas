package app.atlas.ui.search

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.CoordinateFormat
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Categories
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.ToggleChip
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.drawSymbolCentred
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.softShadow
import app.atlas.ui.kit.walkMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.random.Random

/** One wedge of the wheel: a kind of outing, the category it draws from, its colour, and whether it is indoors. */
@Immutable
data class Wedge(val id: String, val category: String, val symbol: String, val colour: Long, val indoors: Boolean)

/** The places each wedge can land on, already narrowed by the chips. */
@Immutable
data class WheelPlaces(val byWedge: Map<String, List<Place>>) {
    fun of(w: Wedge): List<Place> = byWedge[w.id].orEmpty()
}

/** What the chips ask: how far on foot, free only, open now, indoors only. */
data class WhereFilters(val walkMinutes: Int = 15, val free: Boolean = false, val openNow: Boolean = false, val indoors: Boolean = false) {
    val radiusM: Double get() = walkMinutes * 60.0 * WALK_M_PER_S / DETOUR

    companion object {
        private const val WALK_M_PER_S = 4.8 / 3.6
        private const val DETOUR = 1.3
    }
}

/**
 * "Where to?": a random but fitting idea for what to do, from the places around. Eight wedges draw from Atlas's
 * categories; the chips narrow them (a walk of at most 15 or 30 minutes, free, open now, indoors); a wedge with nothing
 * that fits is greyed and never chosen.
 */
object WherePicker {
    val WEDGES = listOf(
        Wedge("sights", "sights", "maki-attraction", 0xFFE29A12, indoors = false),
        Wedge("museums", "museums", "maki-museum", 0xFF7B4FD6, indoors = true),
        Wedge("parks", "parks", "maki-park", 0xFF2F7D3A, indoors = false),
        Wedge("food", "restaurants", "maki-restaurant", 0xFFE0592A, indoors = true),
        Wedge("night", "drinks", "maki-bar", 0xFF2B3A8C, indoors = true),
        Wedge("stage", "stage", "maki-theatre", 0xFFB8326A, indoors = true),
        Wedge("play", "playground", "maki-playground", 0xFF0E8C8C, indoors = false),
        Wedge("view", "viewpoint", "maki-viewpoint", 0xFF1E7FB8, indoors = false),
    )
    private const val LIMIT = 80

    /** The places of every wedge around [at] that pass [f] at [now]: the region on the phone, else the map's labels. */
    suspend fun places(services: Services, at: LatLon, f: WhereFilters, now: LocalDateTime, hours: OpeningHoursContext): WheelPlaces = withContext(Dispatchers.Default) {
        val out = LinkedHashMap<String, List<Place>>()
        for (w in WEDGES) {
            val found = withTimeoutOrNull(5_000) { runCatching { services.places.nearby(w.category, at, f.radiusM, LIMIT).first() }.getOrNull() }
            out[w.id] = found?.places.orEmpty().filter { fits(it, w, at, f, now, hours) }
        }
        WheelPlaces(out)
    }

    /** True when [p] of wedge [w] passes the chips: near enough, named, free, open, indoors. */
    fun fits(p: Place, w: Wedge, at: LatLon, f: WhereFilters, now: LocalDateTime, hours: OpeningHoursContext): Boolean {
        if (p.tags["name"] == null) return false
        if (Geo.distance(at, p.location) > f.radiusM) return false
        if (f.indoors && !w.indoors) return false
        if (f.free && (p.tags["fee"] == "yes" || p.tags["charge"] != null)) return false
        if (f.openNow) {
            val oh = OpeningHours.parseOrNull(p.tags["opening_hours"])
            if (oh != null && oh.isOpen(now, hours) == false) return false
            // Indoors with no hours mapped may well be shut: only what surely is open.
            if (oh == null && w.indoors) return false
        }
        return true
    }

    /**
     * A spin: a wedge with places, and one of its nearest few, those not [closed] now first; null when nothing fits.
     */
    fun spin(places: WheelPlaces, at: LatLon, random: Random, closed: (Place) -> Boolean = { false }): Pair<Int, Place>? {
        val live = WEDGES.indices.filter { places.of(WEDGES[it]).isNotEmpty() }
        if (live.isEmpty()) return null
        val i = live[random.nextInt(live.size)]
        val all = places.of(WEDGES[i]).sortedBy { Geo.distance(at, it.location) }
        val near = all.filterNot(closed).ifEmpty { all }.take(8)
        return i to near[random.nextInt(near.size)]
    }
}

/**
 * Where to?, as on the Where board: the chips (how far, free, open now, indoors), the prize wheel with a symbol on each
 * wedge (greyed when nothing near fits), and a spin that lands on a real place: its kind, name, walk, whether it is open
 * and the sunset, with "Another", save, share and go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhereScreen(args: Dest.Where, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    val now = rememberNow()
    val c = Atlas.colors
    var filters by remember { mutableStateOf(WhereFilters()) }
    val hours = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val places by produceState<WheelPlaces?>(null, filters, here) { value = WherePicker.places(services, here, filters, now, hours) }
    var spins by rememberSaveable { mutableIntStateOf(0) }
    var landed by remember { mutableStateOf<Pair<Int, Place>?>(null) }
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spin: () -> Unit = spin@{
        val p = places ?: return@spin
        val closed = { pl: Place -> OpeningHours.parseOrNull(pl.tags["opening_hours"])?.isOpen(now, hours) == false }
        val pick = WherePicker.spin(p, here, Random(services.clock.millis() + spins), closed) ?: run { landed = null; return@spin }
        spins++
        landed = null
        scope.launch {
            // Land the wedge's middle under the pointer at the top, after four full turns.
            val wedge = 360f / WherePicker.WEDGES.size
            val target = -(pick.first * wedge + wedge / 2)
            val base = angle.value - angle.value % 360f
            angle.animateTo(base - 360f * 4 + target, tween(2_600, easing = CubicBezierEasing(0.1f, 0.6f, 0.2f, 1f)))
            landed = pick
        }
    }
    LaunchedEffect(filters) { landed = null }

    AtlasScreen(stringResource(R.string.where_title), onBack = { nav.back() }, italic = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("≤ 15′", filters.walkMinutes == 15, { filters = filters.copy(walkMinutes = 15) }, symbol = "ui-walk")
            ToggleChip("≤ 30′", filters.walkMinutes == 30, { filters = filters.copy(walkMinutes = 30) }, symbol = "ui-walk")
            ToggleChip(stringResource(R.string.where_free), filters.free, { filters = filters.copy(free = it) }, symbol = "ui-coins")
            ToggleChip(stringResource(R.string.category_open_now), filters.openNow, { filters = filters.copy(openNow = it) }, symbol = "ui-clock")
            ToggleChip(stringResource(R.string.where_indoors), filters.indoors, { filters = filters.copy(indoors = it) }, symbol = "ui-house")
        }
        Wheel(places, angle.value, onSpin = spin, lang = lang, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(1f))
        val l = landed
        when {
            places == null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = c.inkSoft)
            places!!.byWedge.values.all { it.isEmpty() } -> app.atlas.ui.kit.StateMessage("ui-search", stringResource(R.string.where_nothing), text = stringResource(R.string.where_nothing_text))
            l == null -> Text(stringResource(R.string.where_tap), Modifier.fillMaxWidth(), style = Atlas.type.bodyStrong, color = c.inkSoft, textAlign = TextAlign.Center)
            else -> Landed(WherePicker.WEDGES[l.first], l.second, here, now, hours, lang, nav, onAnother = spin)
        }
        HonestyFooter(stringResource(R.string.where_footer))
    }
}

/** The prize wheel: eight wedges in their colours with their symbols, a rim of bulbs, the pointer and the hub. */
@Composable
private fun Wheel(places: WheelPlaces?, angle: Float, onSpin: () -> Unit, lang: String, modifier: Modifier) {
    val c = Atlas.colors
    val spinLabel = stringResource(R.string.where_spin)
    val wedges = WherePicker.WEDGES
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = wedges.joinToString { Categories.byId(it.category)?.name(lang) ?: it.id } }) {
            val r = size.minDimension / 2f * 0.92f
            val centre = Offset(size.width / 2, size.height / 2 + r * 0.04f)
            // The stand's shadow and the rim.
            drawCircle(Color(0x33000000), r * 1.04f, centre + Offset(0f, r * 0.04f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF6D77B), Color(0xFFB8862B)), centre, r * 1.06f), r * 1.04f, centre)
            val n = wedges.size
            val sweep = 360f / n
            rotate(angle, centre) {
                for ((i, w) in wedges.withIndex()) {
                    val empty = places != null && places.of(w).isEmpty()
                    val base = Color(w.colour)
                    val fill = if (empty) lerp(base, Color(0xFFBDB7AC), 0.75f) else base
                    val start = -90f + i * sweep
                    drawArc(
                        Brush.radialGradient(listOf(lerp(fill, Color.White, 0.18f), fill), centre, r), start, sweep, true,
                        Offset(centre.x - r * 0.94f, centre.y - r * 0.94f), Size(r * 1.88f, r * 1.88f),
                    )
                    drawArc(Color(0x55FFFFFF), start, sweep, true, Offset(centre.x - r * 0.94f, centre.y - r * 0.94f), Size(r * 1.88f, r * 1.88f), style = Stroke(r * 0.012f))
                    val mid = Math.toRadians((start + sweep / 2).toDouble())
                    val at = Offset(centre.x + (r * 0.62f * kotlin.math.cos(mid)).toFloat(), centre.y + (r * 0.62f * kotlin.math.sin(mid)).toFloat())
                    rotate(start + sweep / 2 + 90f, at) { drawSymbolCentred(w.symbol, at, r * 0.26f, Color.White.copy(alpha = if (empty) 0.6f else 1f)) }
                }
            }
            // Bulbs on the rim.
            for (k in 0 until 24) {
                val a = Math.toRadians(k * 15.0)
                val p = Offset(centre.x + (r * 0.99f * kotlin.math.cos(a)).toFloat(), centre.y + (r * 0.99f * kotlin.math.sin(a)).toFloat())
                drawCircle(Color(0xFFFFF4C2), r * 0.022f, p)
            }
            // The pointer at the top.
            val tip = Path().apply {
                moveTo(centre.x, centre.y - r * 0.80f)
                lineTo(centre.x - r * 0.08f, centre.y - r * 1.10f)
                lineTo(centre.x + r * 0.08f, centre.y - r * 1.10f)
                close()
            }
            drawPath(tip, Color(0xFFD23C3C))
            drawPath(tip, Color.White, style = Stroke(r * 0.015f))
        }
        Box(
            Modifier.size(92.dp).softShadow(CircleShape, c.shadow, 10.dp).clip(CircleShape).background(c.pill)
                .clickable(role = Role.Button, onClickLabel = spinLabel, onClick = onSpin).semantics { contentDescription = spinLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.where_spin).uppercase(Locale.getDefault()), style = Atlas.type.kicker.copy(fontSize = 15.sp, fontWeight = FontWeight.Black), color = c.onPill)
        }
    }
}

/** Where the wheel landed: the wedge's colour, the kind, the place, its walk and status and the sunset, and what to do next. */
@Composable
private fun Landed(w: Wedge, p: Place, here: LatLon, now: LocalDateTime, hours: OpeningHoursContext, lang: String, nav: Navigator, onAnother: () -> Unit) {
    val services = LocalServices.current
    val context = LocalContext.current
    val c = Atlas.colors
    val colour = Color(w.colour)
    val line = remember(p, now) { PlaceLine.of(p, here, lang, now, open = OpeningHours.parseOrNull(p.tags["opening_hours"])?.status(now, hours)) }
    val sunset = remember(here, now) { Sun.times(now.toLocalDate(), here.lat, here.lon, ZonedDateTime.now(services.clock).zone).sunset?.toLocalDateTime() }
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val saved = remember(lists, p.key) { lists.any { l -> l.places.any { it.place.key == p.key } } }
    val walk = Geo.distance(here, p.location)
    val shareLabel = stringResource(R.string.place_share)
    Card(Modifier.fillMaxWidth(), colour = colour, padding = PaddingValues(18.dp)) {
        Text((Categories.byId(w.category)?.name(lang) ?: w.id).uppercase(Locale.getDefault()), style = Atlas.type.kicker, color = Color.White.copy(alpha = 0.85f))
        Text(line.summary.name, style = Atlas.type.title, color = Color.White)
        val parts = listOfNotNull(
            stringResource(R.string.ask_walk, walkMinutes(WalkTime.seconds(walk))),
            if (p.tags["fee"] == "no") stringResource(R.string.where_free) else null,
            openWords(line),
            sunset?.takeIf { it.isAfter(now) }?.let { stringResource(R.string.where_sunset, clock(it)) },
            line.summary.kind,
        )
        Text(parts.joinToString(" · "), style = Atlas.type.body, color = Color.White.copy(alpha = 0.92f))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                walkMinutes(WalkTime.seconds(walk)), onClick = { nav.go(Dest.Route(p.location.lat, p.location.lon, line.summary.name)) },
                modifier = Modifier.weight(1f), symbol = "ui-corner-up-right", colour = Color.White,
            )
            SymbolButton("ui-refresh", stringResource(R.string.where_another), onAnother, size = 52.dp, elevation = 0.dp)
            SymbolButton("ui-bookmark", stringResource(if (saved) R.string.place_saved_plain else R.string.place_save_plain), {
                services.scope.launch(Dispatchers.IO) { runCatching { services.saved.toggle(p) } }
            }, size = 52.dp, elevation = 0.dp, selected = saved)
            SymbolButton("ui-share", shareLabel, {
                val text = listOfNotNull(line.summary.name, CoordinateFormat.geoUri(p.location), p.id?.url).joinToString("\n")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, size = 52.dp, elevation = 0.dp)
        }
        Text(
            stringResource(R.string.whatshere_open_place),
            Modifier.padding(top = 8.dp).clip(CircleShape).clickable(role = Role.Button) { services.remember(p); nav.go(Dest.Place.of(p)) }.padding(vertical = 6.dp),
            style = Atlas.type.label, color = Color.White,
        )
    }
}
