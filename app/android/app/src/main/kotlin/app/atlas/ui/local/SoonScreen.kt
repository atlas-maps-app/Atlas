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
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.compositeOver
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.CssShadow

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
        Column(Modifier.fillMaxSize().padding(top = boardShift()).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // The head, 50 px at 44: Soon in Fraunces, the date and place 4 px under it, the planner's pill at the right.
            Row(Modifier.fillMaxWidth().padding(top = 44.dp).height(50.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false).wrapContentHeight(unbounded = true)) {
                    BoardLine(stringResource(R.string.soon_title), boardStyle(36f, 600, Ink.css(), 36f, FRAUNCES, italic = true), Modifier.semantics { heading() }, overflow = TextOverflow.Visible)
                    val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(now.atZone(zone))
                    BoardLine(listOfNotNull(date, place).joinToString(" · "), boardStyle(12f, 400, Soft.css(), 15.5f), Modifier.padding(top = 4.dp))
                }
                Row(
                    Modifier.padding(start = 8.dp).height(42.dp).boardBox(Ink.css(), corners(21f)).clickable { nav.go(Dest.Ask()) }.padding(start = 12.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("fact.new", tint = Color(0xFFC9B6FF), size = 17.dp)
                    BoardLine(stringResource(R.string.soon_plan), boardStyle(14f, 700, 0xFFFFFFFF, 18f))
                }
            }
            Spacer(Modifier.height(16.dp))
            WeekCard(days, selected, best, snap?.noForecast == true, zone, now, onPick = { picked = it }, onOpen = { nav.go(Dest.Weather()) })
            Spacer(Modifier.height(12.dp))
            BoardLine(stringResource(R.string.soon_coming_up), boardStyle(21f, 600, Ink.css(), 26f, FRAUNCES, italic = true), Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            val moments = snap?.moments.orEmpty().take(5)
            if (snap == null) {
                BoardLine(stringResource(R.string.kit_loading), boardStyle(13f, 400, Soft.css(), 17f))
            } else if (moments.isEmpty()) {
                BoardLine(stringResource(R.string.soon_nothing), boardStyle(13f, 400, Soft.css(), 17f))
            } else {
                // The timeline: a dotted rule 2 px wide at 41 from 12 px above the first row to 2 px above the last one's foot, rows 14 apart.
                Column(
                    Modifier.drawBehind {
                        val x = 25.dp.toPx()
                        val w = 2.dp.toPx()
                        var y = -12.dp.toPx()
                        val end = size.height - 2.dp.toPx()
                        val dash = 5.dp.toPx()
                        while (y < end) { drawRect(Color(0xFFC9BDA4), Offset(x, y), Size(w, minOf(dash, end - y))); y += 2 * dash }
                    },
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    for ((i, m) in moments.withIndex()) {
                        val v = momentView(m, now, zone, here)
                        val first = i == 0 || moments[i - 1].at.atZone(zone).toLocalDate() != m.at.atZone(zone).toLocalDate()
                        TimelineRow(v, now, zone, first, i, onClick = { v.dest?.let(nav::go) })
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            BoardLine(stringResource(R.string.soon_tools), boardStyle(21f, 600, Ink.css(), 26f, FRAUNCES, italic = true), Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            val tools = listOf(
                Tool(R.string.soon_tool_forecast, R.string.soon_tool_forecast_sub, "weather.partly-cloudy.2", 0xFFE89A0C, Dest.Forecast),
                Tool(R.string.soon_tool_year, R.string.soon_tool_year_sub, "nature.season", 0xFF1E9E5A, Dest.Year),
                Tool(R.string.soon_tool_sky, R.string.soon_tool_sky_sub, "ui.moon", 0xFF2E5BFF, Dest.Sky),
                Tool(R.string.soon_tool_moments, R.string.soon_tool_moments_sub, "ui.bell", 0xFF7B4DFF, Dest.Moments),
                Tool(R.string.soon_tool_season, R.string.soon_tool_season_sub, "food.apple.2", 0xFFC4452F, Dest.Season),
                Tool(R.string.soon_tool_lists, R.string.soon_tool_lists_sub, "tab.local", 0xFF8A6A1E, Dest.Lists()),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in tools.chunked(3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in row) ToolCard(t, Modifier.weight(1f)) { nav.go(t.dest) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            BoardPara(stringResource(R.string.soon_source), boardStyle(11f, 400, Soft.css(), 14.85f), Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(LocalBottomBarInset.current + 8.dp))
        }
    }
}

/** The board's display face for the Soon tab: Fraunces at 600. */
private const val FRAUNCES = "Fraunces"

private class Tool(val title: Int, val sub: Int, val symbol: String, val colour: Long, val dest: Dest)

/** A tool of the board: 60 px, radius 12, its colour's 3 px top edge and soft shadow; the 20 px symbol 10 in, the name and a word 8 after it. */
@Composable
private fun ToolCard(t: Tool, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(60.dp)
            .boardBox(PaperCard.css(), corners(12f), listOf(CssShadow(0f, 3f, 0f, 0f, t.colour, inset = true), CssShadow(0f, 4f, 10f, 0f, 0x14503C14), CssShadow(0f, 0f, 0f, 1f, 0x14503C14)))
            .clickable(onClick = onClick).padding(start = 10.dp, end = 8.dp, top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(t.symbol, tint = argb(t.colour), size = 20.dp)
        Column(Modifier.weight(1f)) {
            BoardLine(stringResource(t.title), boardStyle(13f, 700, Ink.css(), 15.6f))
            BoardLine(stringResource(t.sub), boardStyle(11f, 400, Soft.css(), 13.2f))
        }
    }
}

/**
 * The week card of the board: 142 px, radius 18, warm white with its light top edge, shadow and hairline; 12 px down and
 * 10 in. "This week →" and the hand-written note 6 px in on one baseline, then the seven days 8 px under them, 3 apart:
 * the letter, the date, the 24 px sky, the high and the chance of rain; the chosen day ringed by the board's hand.
 */
@Composable
private fun WeekCard(days: List<WeatherDay>, selected: Int, best: Int, noForecast: Boolean, zone: ZoneId, now: Instant, onPick: (Int) -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(142.dp)
            .boardBox(PaperCard.css(), corners(18f), listOf(CssShadow(0f, 1f, 0f, 0f, 0xCCFFFFFF, inset = true), CssShadow(0f, 8f, 20f, 0f, 0x1A503C14), CssShadow(0f, 0f, 0f, 1f, 0x14503C14)), clip = false)
            .padding(start = 10.dp, end = 10.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BoardLine(stringResource(R.string.soon_this_week), boardStyle(13f, 700, Ink.css(), 17f, spacing = 0.26f), Modifier.alignByBaseline().clickable(onClick = onOpen))
            val note = if (days.isNotEmpty()) dayNoteText(days[selected], selected == best) else ""
            BoardLine(note, boardStyle(19f, 700, Violet.css(), 23.5f, CAVEAT), Modifier.alignByBaseline().padding(start = 8.dp))
        }
        if (days.isEmpty()) {
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Symbol(if (noForecast) "ui-cloud-off" else "weather.cloud", tint = Soft, size = 22.dp)
                BoardLine(stringResource(if (noForecast) R.string.soon_no_forecast else R.string.kit_loading), boardStyle(13f, 400, Soft.css(), 17f))
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val col = maxWidth / days.size
                Row(Modifier.fillMaxWidth()) {
                    for ((i, d) in days.withIndex()) DayColumn(d, i == selected, Modifier.weight(1f), zone, onClick = { onPick(i) })
                }
                // The board's hand-drawn ring round the chosen day: 54 x 104, 3 px left of the column's middle, 7.5 px above it.
                val handCircle = remember { BoardPaths.parse("M27 3C44 3 51 20 51 50s-6 50-24 50S3 82 3 52 9 5 29 4") }
                Canvas(Modifier.offset(x = col * selected + col / 2 - 30.dp, y = (-7.5).dp).size(54.dp, 104.dp)) {
                    val s = size.width / 54f
                    scale(s, s, Offset.Zero) { drawPath(handCircle, Violet, style = Stroke(2f, cap = StrokeCap.Round)) }
                }
            }
        }
    }
}

/** The board's sky for a day of the week: its own rain, sun, sun-and-cloud and cloud; the kit's for snow, storms and fog. */
private fun daySymbol(sky: Sky): String = when (sky) {
    Sky.DRIZZLE, Sky.FREEZING_DRIZZLE, Sky.RAIN, Sky.FREEZING_RAIN, Sky.SHOWERS -> "weather.rain.3"
    Sky.CLEAR, Sky.MAINLY_CLEAR -> "weather.sun"
    Sky.PARTLY_CLOUDY -> "weather.partly-cloudy.2"
    Sky.OVERCAST, Sky.UNKNOWN -> "weather.cloud"
    else -> skySymbol(sky, day = true)
}

@Composable
private fun DayColumn(d: WeatherDay, on: Boolean, modifier: Modifier, zone: ZoneId, onClick: () -> Unit) {
    val name = d.date.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault())
    val letter = d.date.dayOfWeek.getDisplayName(DateStyle.NARROW, Locale.getDefault()).uppercase()
    val rain = d.precipitationProbabilityMax.roundToInt()
    val tint = when {
        d.sky.wet -> 0xFF2E7BC4
        d.sky == Sky.CLEAR || d.sky == Sky.MAINLY_CLEAR -> 0xFFE89A0C
        d.sky == Sky.PARTLY_CLOUDY -> 0xFFC98A1A
        else -> 0xFF6F7686
    }
    Column(
        modifier.clickable(onClick = onClick).semantics { contentDescription = "$name ${d.date.dayOfMonth}: ${d.temperatureMax.roundToInt()}°, $rain%"; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BoardLine(letter, boardStyle(12f, 800, if (on) Violet.css() else Ink.css(), 15.5f, tnum = true))
        BoardLine(d.date.dayOfMonth.toString(), boardStyle(11f, 400, Soft.css(), 14f, tnum = true))
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { Symbol(daySymbol(d.sky), tint = argb(tint), size = 22.dp) }
        BoardLine("${d.temperatureMax.roundToInt()}°", boardStyle(14f, 700, Ink.css(), 18f, tnum = true))
        BoardLine("$rain%", boardStyle(11f, 400, if (rain >= 20) 0xFF2E7BC4 else 0xFF8A8170, 14f, tnum = true))
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

/**
 * One row of the board's timeline: the 52 px date column (the day in capitals, the time in Fraunces 26, the date), and
 * 12 px on the 66 px card (radius 14, its shadow and hairline; 16 px in): the title in Fraunces 17 and a line 3 under it,
 * the moment's 42 px tile; a strip of washi tape over its corner, left and right in turn.
 */
@Composable
private fun TimelineRow(v: MomentView, now: Instant, zone: ZoneId, firstOfDay: Boolean, index: Int, onClick: () -> Unit) {
    val at = v.moment.at.atZone(zone)
    val today = LocalDate.ofInstant(now, zone) == at.toLocalDate()
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The board's column is 52 px: today says so, other days by their short name ("SAT") over the date.
                val day = if (today) stringResource(R.string.soon_today) else at.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.')
                if (firstOfDay) BoardLine(day.uppercase(), boardStyle(11f, 800, Soft.css(), 11.55f, spacing = 0.66f, tnum = true), overflow = TextOverflow.Visible)
                BoardLine(DateTimeFormatter.ofPattern("H:mm").format(at), boardStyle(26f, 600, Ink.css(), 32f, FRAUNCES, tnum = true), overflow = TextOverflow.Visible)
                if (firstOfDay && !today) BoardLine("${at.dayOfMonth} ${at.month.getDisplayName(DateStyle.SHORT, Locale.getDefault()).uppercase().trimEnd('.')}", boardStyle(11f, 700, Soft.css(), 11.55f, tnum = true), overflow = TextOverflow.Visible)
            }
            Row(
                Modifier.weight(1f).height(66.dp).boardBox(PaperCard.css(), corners(14f), listOf(CssShadow(0f, 6f, 16f, 0f, 0x17503C14), CssShadow(0f, 0f, 0f, 1f, 0x14503C14)))
                    .clickable(enabled = v.dest != null, onClick = onClick).padding(start = 16.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BoardLine(v.title, boardStyle(17f, 600, Ink.css(), 21f, FRAUNCES))
                    BoardLine(v.line, boardStyle(12f, 400, Soft.css(), 15.5f, tnum = true))
                }
                Box(Modifier.size(42.dp).boardBox(argb(v.colour).copy(alpha = 0.12f).compositeOver(PaperCard).css(), corners(12f)), contentAlignment = Alignment.Center) {
                    Symbol(v.symbol, tint = argb(v.colour), size = 22.dp)
                }
            }
        }
        // The washi tape: 58 x 18, the moment's colour at 45 per cent with white stripes, at 85 per cent.
        val left = index % 2 == 0
        Box(
            Modifier.offset(x = if (left) 42.dp else 292.dp, y = (-7).dp).rotate(if (left) -8f else 7f).size(58.dp, 18.dp).alpha(0.85f)
                .boardBox(argb(v.colour).copy(alpha = 0.45f).css(), grad = listOf(cssRepeatingLinear(45f, 8f, 0f to 0x59FFFFFF, 4f to 0x59FFFFFF, 4f to 0x00FFFFFF, 8f to 0x00FFFFFF))),
        )
    }
}
