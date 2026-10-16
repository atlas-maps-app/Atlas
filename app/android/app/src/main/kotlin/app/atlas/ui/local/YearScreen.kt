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
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF14203A)).clickable { nav.go(Dest.Ask(services.local.askAboutSeasons(today))) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { Text(stringResource(R.string.year_plan), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
        },
    ) {
        BoardTitleBar(stringResource(R.string.year_title), onBack = { nav.back() }, trailing = { HeaderPill(stringResource(R.string.year_food)) { nav.go(Dest.Season) } })
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(380f / 382f).semantics { contentDescription = summary }) {
            val s = size.width / 380f
            scale(s, s, Offset.Zero) {
                drawYearWheel(
                    now.map { WheelArc(it.span.arc.first, it.span.arc.second, it.activity.colour, "", it.activity.letter) }, datePos, letters, -1, third, monthShort,
                    emptyMap(), text, needle = true, wedge = false, hubSize = 13f,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in now.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in row) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(a.activity.colour.toInt())))
                        Text(activityName(a.activity.id), color = Color(0xFF6A5A40), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SmallCaps(stringResource(R.string.year_now_next), Muted, Modifier.padding(start = 2.dp))
        Spacer(Modifier.height(8.dp))
        if (picks.isEmpty()) Text(stringResource(R.string.year_nothing), color = Muted, fontSize = 13.sp)
        for (row in picks.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in row) PickCard(a, today, Modifier.weight(1f)) { a.activity.dest?.let(nav::go) }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
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
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(enabled = a.activity.dest != null, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(22.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(Color(a.activity.colour.toInt())))
        Text(activityName(a.activity.id), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(line, color = Color(0xFF7A6A58), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
