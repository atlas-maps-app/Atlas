package app.atlas.ui.search

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.cssRadial
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.walkMinutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.random.Random

/**
 * One wedge of the wheel, in the board's order and colours: a kind of outing, the categories it draws from, its name and
 * whether it is indoors.
 */
@Immutable
data class Wedge(val id: String, val categories: List<String>, @StringRes val label: Int, val indoors: Boolean) {
    /** The wedge's fill on the board's wheel. */
    val colour: Long get() = WheelArt.FILLS[WherePicker.WEDGES.indexOfFirst { it.id == id }.coerceAtLeast(0)]
}

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
    /** The board's eight wedges, clockwise from the top: water, views, food, quiet, night, nature, culture, odd. */
    val WEDGES = listOf(
        Wedge("water", listOf("water"), R.string.where_w_water, indoors = false),
        Wedge("views", listOf("viewpoint"), R.string.where_w_views, indoors = false),
        Wedge("food", listOf("restaurants"), R.string.where_w_food, indoors = true),
        Wedge("quiet", listOf("library", "worship"), R.string.where_w_quiet, indoors = true),
        Wedge("night", listOf("drinks"), R.string.where_w_night, indoors = true),
        Wedge("nature", listOf("parks", "nature_spots"), R.string.where_w_nature, indoors = false),
        Wedge("culture", listOf("museums", "stage"), R.string.where_w_culture, indoors = true),
        Wedge("odd", listOf("sights"), R.string.where_w_odd, indoors = false),
    )
    private const val LIMIT = 80

    /** The places of every wedge around [at] that pass [f] at [now]: the region on the phone, else the map's labels. */
    suspend fun places(services: Services, at: LatLon, f: WhereFilters, now: LocalDateTime, hours: OpeningHoursContext): WheelPlaces = withContext(Dispatchers.Default) {
        val out = LinkedHashMap<String, List<Place>>()
        for (w in WEDGES) {
            val found = w.categories.flatMap { id ->
                val answer = withTimeoutOrNull(5_000) {
                    try {
                        services.places.nearby(id, at, f.radiusM, LIMIT).first().places
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                answer.orEmpty()
            }
            out[w.id] = found.distinctBy { it.key }.filter { fits(it, w, at, f, now, hours) }
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

/** The Where board's ink. */
private const val INK = 0xFF1E1830

/**
 * Where to?, as on the Where board: the chips (how far, free, open now, indoors), the prize wheel with a symbol on each
 * wedge (greyed when nothing near fits), and a spin that lands on a real place: the card in the wedge's colour with its
 * kind, name, walk, whether it is free and the sunset (tap it for the way there), then "Another", save and share. The
 * board's numbers: the chips 38 px at 100, the wheel 300 × 320 at 160, the card at 500 (corners 28, 18 px in), and the
 * buttons 56 px, 28 px from the foot.
 */
@Composable
fun WhereScreen(args: Dest.Where, nav: Navigator) {
    val services = LocalServices.current
    val context = LocalContext.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    val now = rememberNow()
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
            val target = -(pick.first * wedge + wedge / 2 + WheelArt.BASE_TURN)
            val base = angle.value - angle.value % 360f
            angle.animateTo(base - 360f * 4 + target, tween(2_600, easing = CubicBezierEasing(0.1f, 0.6f, 0.2f, 1f)))
            landed = pick
        }
    }
    LaunchedEffect(filters) { landed = null }
    val l = landed
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val saved = remember(lists, l) { l != null && lists.any { list -> list.places.any { it.place.key == l.second.key } } }
    val shareLabel = stringResource(R.string.place_share)

    // The board's page: radial-gradient(ellipse at 50% 30%, #FFF8EC, #F6E6D0 70%).
    val glow = remember { cssRadial(false, 206f, 274.5f, 0f to 0xFFFFF8EC, 0.7f to 0xFFF6E6D0, 1f to 0xFFF6E6D0) }
    Column(Modifier.fillMaxSize().boardBox(0xFFF6E6D0, grad = listOf(glow)).padding(top = boardShift())) {
        BoardTitleRow(stringResource(R.string.where_title), INK, onBack = { nav.back() })
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            WhereChip("≤ 15′", filters.walkMinutes == 15, symbol = "ui.clock") { filters = filters.copy(walkMinutes = 15) }
            WhereChip("≤ 30′", filters.walkMinutes == 30) { filters = filters.copy(walkMinutes = 30) }
            WhereChip(stringResource(R.string.where_free), filters.free) { filters = filters.copy(free = !filters.free) }
            WhereChip(stringResource(R.string.category_open_now), filters.openNow) { filters = filters.copy(openNow = !filters.openNow) }
            WhereChip(stringResource(R.string.where_indoors), filters.indoors) { filters = filters.copy(indoors = !filters.indoors) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Wheel(places, { angle.value }, onSpin = spin, lang = lang, modifier = Modifier.padding(top = 22.dp).width(300.dp))
            val part = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)
            when {
                places == null -> BoardLine(stringResource(R.string.kit_loading), boardText(12f, 400, 15.5f), 0xFF6B6480, part)
                places!!.byWedge.values.all { it.isEmpty() } -> app.atlas.ui.kit.StateMessage("ui-search", stringResource(R.string.where_nothing), part, text = stringResource(R.string.where_nothing_text))
                l == null -> Column(part, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.where_tap), Modifier.fillMaxWidth(), style = boardText(15f, 700, 19.5f, align = TextAlign.Center), color = Color(INK))
                    Text(stringResource(R.string.where_footer), Modifier.fillMaxWidth(), style = boardText(11f, 400, 14f, align = TextAlign.Center), color = Color(0xFF6B6480))
                }
                else -> Landed(WherePicker.WEDGES[l.first], l.second, here, now, hours, lang, nav, part)
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp).windowInsetsPadding(WindowInsets.navigationBars),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).height(56.dp).boardBox(0xFFFFFFFF, round(28f)).clickable(role = Role.Button, onClick = spin),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardSymbol("ui.refresh", 18f, INK)
                BoardLine(stringResource(R.string.where_another), boardText(15f, 700, 19.5f), INK)
            }
            val off = Modifier.alpha(if (l == null) 0.4f else 1f)
            BoardRoundButton(
                "ui.bookmark", stringResource(if (saved) R.string.place_saved_plain else R.string.place_save_plain),
                { l?.let { (_, p) -> services.scope.launch(Dispatchers.IO) { runCatching { services.saved.toggle(p) } } } },
                off, side = 56f, glyph = 20f, bg = if (saved) INK else 0xFFFFFFFF, tint = if (saved) 0xFFFFFFFF else INK,
            )
            BoardRoundButton(
                "ui.share", shareLabel,
                {
                    l?.let { (_, p) ->
                        val text = listOfNotNull(app.atlas.places.Localise.name(p.tags, lang), CoordinateFormat.geoUri(p.location), p.id?.url).joinToString("\n")
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                },
                off, side = 56f, glyph = 20f, bg = INK, tint = 0xFFFFFFFF,
            )
        }
    }
}

/** A chip of the board's row: 38 px, 11 px in, 13 / 800; on in ink with white words, off white; an optional 15 px symbol. */
@Composable
private fun WhereChip(text: String, on: Boolean, symbol: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).boardBox(if (on) INK else 0xFFFFFFFF, round(19f)).toggleable(on, role = Role.Checkbox) { onClick() }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (symbol != null) BoardSymbol(symbol, 15f, if (on) 0xFFFFFFFF else INK)
        BoardLine(text, boardText(13f, 800, 17f, tnum = true), if (on) 0xFFFFFFFF else INK)
    }
}

/**
 * Where the wheel landed, as the board's card: 190 px in the wedge's colour (corners 28, 18 px in, 10 between, its
 * shadow 0 14 28 of that colour at 30 %): the wedge's glyph and name (13 / 800, 0.1 em apart), the place in
 * Instrument Serif 34, the walk, free and the sunset (14 / 700, 12 apart), and its kind and "Open this place" as the
 * board's translucent pills. Tap the card for the way there.
 */
@Composable
private fun Landed(w: Wedge, p: Place, here: LatLon, now: LocalDateTime, hours: OpeningHoursContext, lang: String, nav: Navigator, modifier: Modifier) {
    val services = LocalServices.current
    val line = remember(p, now) { PlaceLine.of(p, here, lang, now, open = OpeningHours.parseOrNull(p.tags["opening_hours"])?.status(now, hours)) }
    val sunset = remember(here, now) { Sun.times(now.toLocalDate(), here.lat, here.lon, ZonedDateTime.now(services.clock).zone).sunset?.toLocalDateTime() }
    val walk = Geo.distance(here, p.location)
    val index = WherePicker.WEDGES.indexOf(w)
    Column(
        modifier.fillMaxWidth().heightIn(min = 190.dp)
            .boardBox(w.colour, round(28f), listOf(CssShadow(0f, 14f, 28f, 0f, (w.colour and 0xFFFFFFL) or 0x4C000000L)))
            .clickable(role = Role.Button) { nav.go(Dest.Route(p.location.lat, p.location.lon, line.summary.name)) }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WedgeGlyph(index, 26f)
            BoardLine(stringResource(w.label).uppercase(Locale.getDefault()), boardText(13f, 800, 17f, spacing = 1.3f), 0xD1FFFFFF)
        }
        BoardBlock(line.summary.name, boardText(34f, 400, 34f, family = SERIF), 0xFFFFFFFF, lines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val facts = listOfNotNull(
                stringResource(R.string.ask_walk, walkMinutes(WalkTime.seconds(walk))),
                if (p.tags["fee"] == "no") stringResource(R.string.where_free) else openWords(line),
                sunset?.takeIf { it.isAfter(now) }?.let { stringResource(R.string.where_sunset, clock(it)) },
            )
            for (f in facts) BoardLine(f, boardText(14f, 700, 18f, tnum = true), 0xE0FFFFFF)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            line.summary.kind?.let { WherePill(it) }
            WherePill(stringResource(R.string.whatshere_open_place), Modifier.clickable(role = Role.Button) { services.remember(p); nav.go(Dest.Place.of(p)) })
        }
    }
}

/** The board's pill on the result card: 26 px, 9 px in, white at 18 %, 12 / 800. */
@Composable
private fun WherePill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(26.dp).boardBox(0x2EFFFFFF, round(13f)).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
        BoardLine(text, boardText(12f, 800, 15.5f), 0xFFFFFFFF)
    }
}
