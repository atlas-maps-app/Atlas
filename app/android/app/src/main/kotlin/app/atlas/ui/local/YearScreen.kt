package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private val Ink = Color(0xFF1C2433)
private val Paper = Color(0xFFEFE8D8)
private val Muted = Color(0xFF8A7E66)

@Composable
private fun activityName(id: String): String = when (id) {
    "swim" -> stringResource(R.string.year_act_swim); "foliage" -> stringResource(R.string.year_act_foliage)
    "mushrooms" -> stringResource(R.string.year_act_mushrooms); "birds" -> stringResource(R.string.year_act_birds)
    "stars" -> stringResource(R.string.year_act_stars); "ski" -> stringResource(R.string.year_act_ski)
    "blossom" -> stringResource(R.string.year_act_blossom); else -> stringResource(R.string.year_act_festivals)
}

/** The year here: a wheel of things to do (swimming, leaves, mushrooms, birds, stars, skiing, blossom, festivals), with a needle at today. */
@Composable
fun YearScreen(args: Dest.Year, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val today = remember(clock) { LocalDate.now(clock) }
    val region = remember(here) { FoodRegion.of(here) }
    val now = remember(region, today, here) { YearCalendar.now(region, today, here.lat < 0) }
    val picks = remember(now) { YearCalendar.highlights(now) }
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, SansFamily) }
    val letters = remember { (1..12).map { Month.of(it).getDisplayName(TextStyle.NARROW, Locale.getDefault()).uppercase() } }
    val datePos = today.monthValue - 1 + (today.dayOfMonth - 0.5) / today.lengthOfMonth()
    val third = when (YearCalendar.third(today)) { 0 -> stringResource(R.string.year_early); 1 -> stringResource(R.string.year_mid); else -> stringResource(R.string.year_late) }
    val monthShort = Month.of(today.monthValue).getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase().trimEnd('.')
    val summary = now.map { activityName(it.activity.id) }.joinToString()

    BoardPage(
        background = Paper,
        bottomRoom = 56.dp,
        bottom = {
            Row(
                Modifier.fillMaxWidth().height(56.dp).boardBox(0xFF14203A, corners(28f)).clickable { nav.go(Dest.Ask(services.local.askAboutSeasons(today))) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { BoardLine(stringResource(R.string.year_plan), boardStyle(15f, 800, 0xFFFFFFFF, 19.5f)) }
        },
    ) {
        BoardTitleBar(stringResource(R.string.year_title), onBack = { nav.back() }, trailing = { HeaderPill(stringResource(R.string.year_food), size = 12f, lineHeight = 15.5f) { nav.go(Dest.Season) } })
        Spacer(Modifier.height(10.dp))
        // The wheel: 380 x 382 at (16, 96).
        Canvas(Modifier.size(380.dp, 382.dp).semantics { contentDescription = summary }) {
            val s = size.width / 380f
            scale(s, s, Offset.Zero) {
                drawYearWheel(
                    now.map { WheelArc(it.span.arc.first, it.span.arc.second, it.activity.colour, "", it.activity.letter) }, datePos, letters, -1, third, monthShort,
                    emptyMap(), text, needle = true, wedge = false, hubSize = 13f,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // The legend: four columns of 89 px, 8 apart, rows 6 apart; a 10 px dot, the name 5 px after it.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in now.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in row) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).boardBox(a.activity.colour, corners(5f)))
                        BoardLine(activityName(a.activity.id), boardStyle(11f, 700, 0xFF6A5A40, 14f))
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        SmallCaps(stringResource(R.string.year_now_next), Muted, Modifier.padding(start = 2.dp), size = 12, spacing = 0.12f, lineHeight = 15.5f)
        Spacer(Modifier.height(6.5.dp))
        if (picks.isEmpty()) BoardLine(stringResource(R.string.year_nothing), boardStyle(13f, 400, Muted.css(), 17f))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in picks.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in row) PickCard(a, today, Modifier.weight(1f)) { a.activity.dest?.let(nav::go) }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PickCard(a: ActivityNow, today: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    val end = Month.of(a.span.end).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val start = Month.of(a.span.start).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val line = when (a.phase) {
        Phase.LAST -> stringResource(R.string.year_phase_last, end)
        Phase.PEAK -> stringResource(R.string.year_phase_peak, end)
        Phase.ON -> stringResource(R.string.year_phase_on, end)
        else -> stringResource(R.string.year_phase_soon, start)
    }
    // The board's card: 186 x 64, 12 px in and 10 down; the colour's 22 x 6 bar, the name and the line 3 apart.
    Column(
        modifier.height(64.dp).boardBox(0xFFFFFFFF, corners(18f)).clickable(enabled = a.activity.dest != null, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(22.dp, 6.dp).boardBox(a.activity.colour, corners(3f)))
        BoardLine(activityName(a.activity.id), boardStyle(14f, 800, 0xFF1C2433, 18f))
        BoardLine(line, boardStyle(11f, 400, 0xFF7A6A58, 14f))
    }
}
