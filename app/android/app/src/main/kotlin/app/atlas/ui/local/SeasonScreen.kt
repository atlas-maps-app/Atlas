package app.atlas.ui.local

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.OpeningHours
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF1C2433)
private val Paper = Color(0xFFEFE8D8)
private val Muted = Color(0xFF8A7E66)

/** The seasonal wheel's food, with its name in the reader's language. */
@Composable
internal fun foodName(id: String): String {
    val context = LocalContext.current
    val res = remember(id) { context.resources.getIdentifier("local_food_$id", "string", context.packageName) }
    return if (res != 0) stringResource(res) else id
}

/** A food's name in the middle of a sentence: lower case, except in German where nouns keep their capital. */
internal fun inSentence(name: String): String = if (Locale.getDefault().language == "de") name else name.lowercase(Locale.getDefault())

/** The nearest farmers' market and when it next opens: what the green card shows. */
class MarketNow(val place: app.atlas.model.Place, val name: String, val distanceM: Double, val opens: String?)

/** What is in season here, now: the year wheel, the best, the last chance and the coming, the nearest market, and the alert. */
@Composable
fun SeasonScreen(args: Dest.Season, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.local
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val today = remember(clock) { LocalDate.now(clock) }
    val region = remember(here) { FoodRegion.of(here) }
    val season = remember(region, today, here) { FoodCalendar.now(region, today, southern = here.lat < 0) }
    val market by produceState<MarketNow?>(null, here, today) { value = nearestMarket(services, here, clock.instant(), zone) }
    var watchedId by remember { mutableStateOf(hub.prefs.string("season.watch")) }
    val watched = remember(watchedId, season) {
        season.foods.firstOrNull { it.id == watchedId && !it.span.inSeason(season.month) }
            ?: listOf("strawberries", "asparagus", "cherries", "apricots", "figs", "raspberries").firstNotNullOfOrNull { id -> season.foods.firstOrNull { it.id == id && !it.span.inSeason(season.month) } }
    }
    var alertAt by remember(watched) { mutableStateOf(watched?.let { hub.reminders.scheduledAt("season.${it.id}") }) }

    val context = LocalContext.current
    val title = watched?.let { inSentence(foodName(it.id)) }
    val alertTitle = title?.let { stringResource(R.string.season_alert_title, it) }
    val alertText = stringResource(R.string.season_alert_text)
    fun toggle() {
        val w = watched ?: return
        val key = "season.${w.id}"
        if (hub.reminders.scheduledAt(key) != null) {
            hub.reminders.cancel(key)
            alertAt = null
        } else {
            val start = FoodCalendar.nextStart(w.span, today)
            val at = LocalDateTime.of(start, LocalTime.of(9, 0)).atZone(zone).toInstant()
            hub.reminders.schedule(key, at, alertTitle.orEmpty(), alertText)
            alertAt = at
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) toggle() }

    BoardPage(
        background = Paper,
        bottomRoom = 56.dp,
        bottom = {
            if (watched != null && title != null) {
                val monthName = Month.of(watched.span.start).getDisplayName(TextStyle.FULL, Locale.getDefault())
                val label = if (alertAt != null) stringResource(R.string.season_alert_set, title, monthName) else stringResource(R.string.season_tell_me, title)
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Ink)
                        .clickable { if (Build.VERSION.SDK_INT >= 33 && alertAt == null) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else toggle() },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(if (alertAt != null) "ui.check" else "ui.bell", tint = Color.White, size = 20.dp)
                    Text(label, color = Color.White, fontFamily = SansFamily, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
                }
            }
        },
    ) {
        BoardTitleBar(
            stringResource(R.string.season_title), onBack = { nav.back() },
            trailing = region.country?.let { cc -> { HeaderPill(Locale("", cc).getDisplayCountry(Locale.getDefault())) } } ?: { HeaderPill(stringResource(R.string.season_region_other)) },
        )
        Spacer(Modifier.height(12.dp))
        val monthLetters = remember { (1..12).map { Month.of(it).getDisplayName(TextStyle.NARROW, Locale.getDefault()).uppercase() } }
        val monthShort = Month.of(season.month).getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase().trimEnd('.')
        val week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val measurer = rememberTextMeasurer()
        val text = remember(measurer) { BoardText(measurer, SansFamily) }
        val art = hub.art
        val weekLabel = stringResource(R.string.season_week, week)
        val summary = season.wheel.map { foodName(it.id) }.joinToString()
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(380f / 382f).semantics { contentDescription = summary },
        ) {
            val s = size.width / 380f
            scale(s, s, Offset.Zero) { drawYearWheel(season.wheel.map { WheelArc(it.span.arc.first, it.span.arc.second, it.colour, it.glyph) }, season.month - 1 + 0.5, monthLetters, season.month - 1, monthShort, weekLabel, art, text) }
        }
        Spacer(Modifier.height(10.dp))
        SmallCaps(stringResource(R.string.season_best), Muted, Modifier.padding(start = 2.dp))
        Spacer(Modifier.height(8.dp))
        if (season.best.isEmpty()) {
            Text(stringResource(R.string.season_nothing_peak), color = Muted, fontSize = 13.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in season.best.take(4)) {
                    val tag = stringResource(if (f.span.atPeak(season.month)) R.string.season_peak else R.string.season_new)
                    val name = foodName(f.id)
                    Column(
                        Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)
                            .then(if (watched?.id == f.id) Modifier.border(2.dp, Ink, RoundedCornerShape(20.dp)) else Modifier)
                            .semantics(mergeDescendants = true) { contentDescription = "$name, $tag" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Glyph(f.glyph, Color(f.colour.toInt()), 34.dp)
                        Text(name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                        Text(tag, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                repeat(4 - season.best.take(4).size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoodGroup(Modifier.weight(1f), stringResource(R.string.season_last), Color(0xFFA8241D), season.last.take(2)) { watchedId = it; hub.prefs.putString("season.watch", it) }
            FoodGroup(Modifier.weight(1f), stringResource(R.string.season_coming), Color(0xFF2F7A3A), season.coming.take(2)) { watchedId = it; hub.prefs.putString("season.watch", it) }
        }
        market?.let { m ->
            Spacer(Modifier.height(10.dp))
            MarketCard(m, onClick = { nav.go(Dest.Place.of(m.place)) })
        }
    }
}

@Composable
private fun FoodGroup(modifier: Modifier, title: String, colour: Color, foods: List<FoodItem>, onPick: (String) -> Unit) {
    Column(
        modifier.heightIn(min = 92.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallCaps(title, colour, size = 11)
        if (foods.isEmpty()) {
            Text("–", color = Muted, fontSize = 14.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (f in foods) Column(Modifier.clickable { onPick(f.id) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Glyph(f.glyph, Color(f.colour.toInt()), 28.dp)
                    Text(foodName(f.id), color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MarketCard(m: MarketNow, onClick: () -> Unit) {
    val walkSeconds = WalkTime.seconds(m.distanceM)
    val trailing = if (walkSeconds > 45 * 60) shortDistance(m.distanceM) else walkMinutes(walkSeconds)
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF2F7A3A)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("place.market.2", Color.White, 26.dp)
        Column(Modifier.weight(1f)) {
            Text(m.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            m.opens?.let { Text(it, color = Color(0xFFCFE6D2), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(trailing, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** The nearest `amenity=marketplace` within 15 km and when it next opens, from its `opening_hours`. */
internal suspend fun nearestMarket(services: app.atlas.Services, at: LatLon, now: Instant, zone: ZoneId): MarketNow? {
    val found = services.local.finder.find("amenity=marketplace", at, 15_000.0, limit = 10).places
    val lang = Locale.getDefault().language
    val pick = found.firstOrNull { isFarmersMarket(it.place.tags) } ?: found.firstOrNull { it.place.tags["opening_hours"] != null } ?: found.firstOrNull() ?: return null
    val tags = pick.place.tags
    val name = app.atlas.places.Localise.name(tags, lang) ?: return null
    return MarketNow(pick.place, name, pick.distanceM, marketLine(tags["opening_hours"], now, zone) { services.context.getString(R.string.moment_market_open_until, it) })
}

private val FARM_WORDS = listOf("boeren", "farm", "biolog", "organic", "bauern", "wochenmarkt", "producteurs", "paysan", "greenmarket", "streekmarkt", "groente", "fresh")

/** True when a marketplace says it sells what the farms grow: in its name, its hours' comment or its description. */
internal fun isFarmersMarket(tags: Map<String, String>): Boolean {
    val text = listOfNotNull(tags["name"], tags["opening_hours"], tags["description"], tags["market"], tags["check_date:opening_hours"]).joinToString(" ").lowercase()
    return tags["market"] == "farmers" || tags["farmers_market"] == "yes" || FARM_WORDS.any { it in text }
}

/** "Sat 8–13" for the next opening of a market's hours, "open now until 13:00" while it is open; null when the hours are not mapped. */
internal fun marketLine(hours: String?, now: Instant, zone: ZoneId, openUntil: (String) -> String): String? {
    val parsed = OpeningHours.parseOrNull(hours) ?: return null
    val local = LocalDateTime.ofInstant(now, zone)
    val st = parsed.status(local)
    val hm = DateTimeFormatter.ofPattern("H:mm")
    val day = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    fun short(t: LocalDateTime) = if (t.minute == 0) t.hour.toString() else hm.format(t)
    val change = st.changesAt ?: return null
    return if (st.isOpen) {
        openUntil(hm.format(change))
    } else {
        val close = parsed.status(change).changesAt
        val range = if (close != null && close.toLocalDate() == change.toLocalDate()) "${short(change)}–${short(close)}" else hm.format(change)
        "${day.format(change)} $range"
    }
}

/** One arc of the wheel: positions in months from 1 January ([from] to [to], to after from), a colour and a glyph. */
internal class WheelArc(val from: Double, val to: Double, val colour: Long, val glyph: String, val letter: String? = null)

/** The Season board's wheel: month spokes, a ring per food with its arc and icon, the current month's wedge, the hub. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawYearWheel(
    arcs: List<WheelArc>, centrePos: Double, letters: List<String>, currentIndex: Int, hubTop: String, hubBottom: String,
    art: Map<String, app.atlas.places.Icon>, text: BoardText, needle: Boolean = false, markers: Boolean = true, wedge: Boolean = true, hubSize: Float = 15f,
) {
    val c = Offset(190f, 190f)
    fun at(angleDeg: Double, r: Float) = Offset(c.x + r * cos(angleDeg * PI / 180).toFloat(), c.y + r * sin(angleDeg * PI / 180).toFloat())
    fun angle(pos: Double) = -90.0 + (pos - centrePos) * 30.0
    drawCircle(Color(0x1F000000), 186f, Offset(190f, 192f))
    drawCircle(Color(0xFFFBF6EA), 186f, c)
    drawCircle(Color(0xFFD9CCAE), 186f, c, style = Stroke(2f))
    for (j in 0 until 12) {
        val a = angle(j.toDouble())
        drawLine(Color(0xFFE8DEC6), at(a, 44f), at(a, 186f), 1f)
    }
    for ((i, arc) in arcs.withIndex()) {
        val r = 150f - 14f * i
        val colour = Color(arc.colour.toInt())
        drawCircle(Color(0xFFEFE7D4), r, c, style = Stroke(10f))
        val inset = Math.toDegrees(5.0 / r)
        val start = angle(arc.from) + inset
        val sweep = (arc.to - arc.from) * 30.0 - 2 * inset
        if (sweep > 0) drawArc(colour, start.toFloat(), sweep.toFloat(), false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(10f, cap = StrokeCap.Round))
        if (markers) {
            val p = at(angle(arc.from) + inset, r)
            drawCircle(Color.White, 8.5f, p)
            drawCircle(Color(0xFFD9CCAE), 8.5f, p, style = Stroke(1f))
            if (arc.letter != null) {
                drawCircle(Color.White, 8f, p)
                drawCircle(colour, 8f, p, style = Stroke(2f))
                text.draw(this, arc.letter, p.x, p.y + 3f, 8f, colour, bold = true, anchor = 0.5f)
            } else drawGlyph(arc.glyph, Offset(p.x - 6f, p.y - 6f), 12f, colour, art)
        }
    }
    // The current month: a wedge from the hub to the rim.
    val a0 = -105.0
    val a1 = -75.0
    if (wedge) {
    val wedgePath = Path().apply {
        moveTo(at(a0, 186f).x, at(a0, 186f).y)
        arcTo(androidx.compose.ui.geometry.Rect(c, 186f), a0.toFloat(), 30f, false)
        lineTo(at(a1, 44f).x, at(a1, 44f).y)
        arcTo(androidx.compose.ui.geometry.Rect(c, 44f), a1.toFloat(), -30f, false)
        close()
    }
    drawPath(wedgePath, Ink.copy(alpha = 0.08f), style = Fill)
    drawPath(Path().apply { moveTo(at(a0, 186f).x, at(a0, 186f).y); lineTo(c.x, c.y); lineTo(at(a1, 186f).x, at(a1, 186f).y) }, Ink, style = Stroke(2f))
    }
    for (k in 0 until 12) {
        val on = k == currentIndex
        val a = angle(k + 0.5)
        val p = at(a, 170f)
        text.draw(this, letters[k], p.x, p.y + if (on) 5.5f else 4.5f, if (on) 15f else if (wedge) 12f else 11f, if (on) Ink else Muted, bold = true, anchor = 0.5f)
    }
    if (needle) {
        drawLine(Color(0xFFC8322A), Offset(190f, 4f), Offset(190f, 44f), 3f)
        drawPath(Path().apply { moveTo(184f, 4f); lineTo(196f, 4f); lineTo(190f, 12f); close() }, Color(0xFFC8322A))
    }
    drawCircle(Ink, 40f, c)
    if (hubSize > 13f) {
        text.draw(this, hubTop, 190f, 186f, hubSize, Color.White, bold = true, anchor = 0.5f)
        text.draw(this, hubBottom, 190f, 203f, 10f, Color(0xFFC9D2DE), bold = true, anchor = 0.5f)
    } else {
        text.draw(this, hubTop, 190f, 187f, 13f, Color.White, bold = true, anchor = 0.5f)
        text.draw(this, hubBottom, 190f, 203f, 13f, Color.White, bold = true, anchor = 0.5f)
    }
}
