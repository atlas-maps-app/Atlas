package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Sky
import app.atlas.providers.WeatherDay
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.skySymbol
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateStyle
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFF14161B)
private val Soft = Color(0xFF6A5F4E)
private val PaperCard = Color(0xFFFFFDF7)
private val Violet = Color(0xFF7B4DFF)

/** The Soon tab: the week's weather, what is coming up near you, and the planning tools. */
@Composable
fun SoonScreen(args: Dest.Soon, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val snap by produceState<SoonSnapshot?>(null, here, now) { value = loadSoon(services, here, now, zone) }
    val place = rememberPlaceName(here)
    val today = remember(now) { LocalDate.ofInstant(now, zone) }
    val days = snap?.forecast?.days.orEmpty().filter { !it.date.isBefore(today) }.take(7)
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    val best = remember(days) { if (days.isEmpty()) 0 else Moments.bestDay(days) }
    val selected = if (picked in days.indices) picked else best

    Box(Modifier.fillMaxSize().background(Color(0xFFF5EFE2)).dotGrid()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.soon_title), style = TextStyle(fontFamily = SerifFamily, fontStyle = FontStyle.Italic, fontSize = 40.sp, lineHeight = 40.sp, color = Ink))
                    val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(now.atZone(zone))
                    Text(listOfNotNull(date, place).joinToString(" · "), color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    Modifier.height(42.dp).clip(CircleShape).background(Ink).clickable { nav.go(Dest.Ask()) }.padding(start = 12.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.sparkles", tint = Color(0xFFC9B6FF), size = 17.dp)
                    Text(stringResource(R.string.soon_plan), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(16.dp))
            WeekCard(days, selected, best, snap?.noForecast == true, zone, now, onPick = { picked = it }, onOpen = { nav.go(Dest.Weather()) })
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.soon_coming_up), style = TextStyle(fontFamily = SerifFamily, fontStyle = FontStyle.Italic, fontSize = 24.sp, color = Ink))
            Spacer(Modifier.height(8.dp))
            val moments = snap?.moments.orEmpty().take(5)
            if (snap == null) {
                Text(stringResource(R.string.kit_loading), color = Soft, fontSize = 13.sp)
            } else if (moments.isEmpty()) {
                Text(stringResource(R.string.soon_nothing), color = Soft, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for ((i, m) in moments.withIndex()) {
                        val v = momentView(m, now, zone, here)
                        val first = i == 0 || moments[i - 1].at.atZone(zone).toLocalDate() != m.at.atZone(zone).toLocalDate()
                        TimelineRow(v, now, zone, first, i, onClick = { v.dest?.let(nav::go) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.soon_tools), style = TextStyle(fontFamily = SerifFamily, fontStyle = FontStyle.Italic, fontSize = 24.sp, color = Ink))
            Spacer(Modifier.height(10.dp))
            val tools = listOf(
                Tool(R.string.soon_tool_forecast, R.string.soon_tool_forecast_sub, "weather.partly-cloudy", 0xFFE89A0C, Dest.Forecast),
                Tool(R.string.soon_tool_year, R.string.soon_tool_year_sub, "nature.season", 0xFF1E9E5A, Dest.Year),
                Tool(R.string.soon_tool_sky, R.string.soon_tool_sky_sub, "ui.moon", 0xFF2E5BFF, Dest.Sky),
                Tool(R.string.soon_tool_moments, R.string.soon_tool_moments_sub, "ui.bell", 0xFF7B4DFF, Dest.Moments),
                Tool(R.string.soon_tool_season, R.string.soon_tool_season_sub, "food.apple.2", 0xFFC4452F, Dest.Season),
                Tool(R.string.soon_tool_lists, R.string.soon_tool_lists_sub, "ui.bookmark", 0xFF8A6A1E, Dest.Lists()),
            )
            for (row in tools.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in row) ToolCard(t, Modifier.weight(1f)) { nav.go(t.dest) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                stringResource(R.string.soon_source), color = Soft, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(LocalBottomBarInset.current + 8.dp))
        }
    }
}

private class Tool(val title: Int, val sub: Int, val symbol: String, val colour: Long, val dest: Dest)

@Composable
private fun ToolCard(t: Tool, modifier: Modifier, onClick: () -> Unit) {
    val c = Color(t.colour.toInt())
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.height(60.dp).cssShadow(shape, 4.dp, 10.dp, Color(0x14503C14)).clip(shape).background(PaperCard).clickable(onClick = onClick)
            .drawTopEdge(c).padding(start = 10.dp, end = 8.dp, top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(t.symbol, tint = c, size = 20.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(t.title), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(t.sub), color = Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The card's coloured top edge (the board's `inset 0 3px 0 colour`). */
private fun Modifier.drawTopEdge(c: Color): Modifier = this.drawWithContent {
    drawContent()
    drawRect(c, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
}

@Composable
private fun WeekCard(days: List<WeatherDay>, selected: Int, best: Int, noForecast: Boolean, zone: ZoneId, now: Instant, onPick: (Int) -> Unit, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().cssShadow(shape, 8.dp, 20.dp, Color(0x1A503C14)).clip(shape).background(PaperCard).padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.soon_this_week), Modifier.clickable(onClick = onOpen), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
            val note = if (days.isNotEmpty()) dayNoteText(days[selected], selected == best) else ""
            Text(note, color = Violet, fontFamily = LocalType.hand, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (days.isEmpty()) {
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Symbol(if (noForecast) "ui-cloud-off" else "weather.cloud", tint = Soft, size = 22.dp)
                Text(stringResource(if (noForecast) R.string.soon_no_forecast else R.string.kit_loading), color = Soft, fontSize = 13.sp)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val col = maxWidth / days.size
                Row(Modifier.fillMaxWidth()) {
                    for ((i, d) in days.withIndex()) DayColumn(d, i == selected, Modifier.weight(1f), zone, onClick = { onPick(i) })
                }
                // The hand-drawn circle round the chosen day.
                val circle = Violet
                // The board's own path data, with its smooth curves.
                val handCircle = remember { BoardPaths.parse("M27 3C44 3 51 20 51 50s-6 50-24 50S3 82 3 52 9 5 29 4") }
                Canvas(Modifier.offset(x = col * selected + (col - 54.dp) / 2, y = (-2).dp).size(54.dp, 104.dp)) {
                    val s = size.width / 54f
                    scale(s, s, Offset.Zero) {
                        drawPath(handCircle, circle, style = Stroke(2f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayColumn(d: WeatherDay, on: Boolean, modifier: Modifier, zone: ZoneId, onClick: () -> Unit) {
    val name = d.date.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault())
    val letter = d.date.dayOfWeek.getDisplayName(DateStyle.NARROW, Locale.getDefault()).uppercase()
    val rain = d.precipitationProbabilityMax.roundToInt()
    val symbol = skySymbol(d.sky, day = true)
    val tint = when {
        d.sky.wet -> Color(0xFF2E7BC4)
        d.sky == Sky.CLEAR || d.sky == Sky.MAINLY_CLEAR -> Color(0xFFE89A0C)
        d.sky == Sky.PARTLY_CLOUDY -> Color(0xFFC98A1A)
        else -> Color(0xFF6F7686)
    }
    Column(
        modifier.clickable(onClick = onClick).semantics { contentDescription = "$name ${d.date.dayOfMonth}: ${d.temperatureMax.roundToInt()}°, $rain%"; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(letter, color = if (on) Violet else Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        Text(d.date.dayOfMonth.toString(), color = Soft, fontSize = 11.sp)
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { Symbol(symbol, tint = tint, size = 22.dp) }
        Text("${d.temperatureMax.roundToInt()}°", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("$rain%", color = if (rain >= 20) Color(0xFF2E7BC4) else Color(0xFF8A8170), fontSize = 11.sp)
    }
}

@Composable
private fun dayNoteText(d: WeatherDay, best: Boolean): String {
    val name = d.date.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault())
    if (best) return stringResource(R.string.soon_note_best, name)
    val short = d.date.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.')
    val phrase = when (Moments.note(d)) {
        Moments.DayNote.DRY_WARM -> R.string.soon_note_dry_warm
        Moments.DayNote.DRY -> R.string.soon_note_dry
        Moments.DayNote.SHOWERS -> R.string.soon_note_showers
        Moments.DayNote.WET -> R.string.soon_note_wet
        Moments.DayNote.STORM -> R.string.soon_note_storm
        Moments.DayNote.SNOW -> R.string.soon_note_snow
        Moments.DayNote.COLD -> R.string.soon_note_cold
    }
    return stringResource(R.string.soon_note, short, stringResource(phrase))
}

@Composable
private fun TimelineRow(v: MomentView, now: Instant, zone: ZoneId, firstOfDay: Boolean, index: Int, onClick: () -> Unit) {
    val at = v.moment.at.atZone(zone)
    val today = LocalDate.ofInstant(now, zone) == at.toLocalDate()
    val tomorrow = LocalDate.ofInstant(now, zone).plusDays(1) == at.toLocalDate()
    val colour = Color(v.colour.toInt())
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val day = when {
                today -> stringResource(R.string.soon_today)
                tomorrow -> stringResource(R.string.soon_tomorrow)
                else -> at.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.')
            }
            if (firstOfDay) Text(day.uppercase(), color = Soft, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, maxLines = 1)
            Text(DateTimeFormatter.ofPattern("H:mm").format(at), style = TextStyle(fontFamily = SerifFamily, fontSize = 22.sp, lineHeight = 26.sp, color = Ink), maxLines = 1)
            if (firstOfDay && !today) Text("${at.dayOfMonth} ${at.month.getDisplayName(DateStyle.SHORT, Locale.getDefault()).uppercase().trimEnd('.')}", color = Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        val shape = RoundedCornerShape(14.dp)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 66.dp).cssShadow(shape, 6.dp, 16.dp, Color(0x17503C14)).clip(shape).background(PaperCard)
                    .clickable(enabled = v.dest != null, onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(v.title, style = TextStyle(fontFamily = SerifFamily, fontSize = 18.sp, lineHeight = 20.sp, color = Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(v.line, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(colour.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Symbol(v.symbol, tint = colour, size = 22.dp)
                }
            }
            // A strip of washi tape over the corner, as the board's cards have.
            Box(
                Modifier.align(Alignment.TopStart).offset(x = (-22).dp, y = (-9).dp).rotate(if (index % 2 == 0) -8f else 7f).size(58.dp, 18.dp)
                    .background(colour.copy(alpha = 0.40f)),
            )
        }
    }
}
