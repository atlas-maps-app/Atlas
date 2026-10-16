package app.atlas.ui.local

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle as DateStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val Ink = Color(0xFF15181C)
private val Soft = Color(0xFF464C5A)

@Composable
internal fun streamName(s: Stream): String = stringResource(
    when (s) {
        Stream.REST -> R.string.stream_rest; Stream.PAPER -> R.string.stream_paper; Stream.PACKAGING -> R.string.stream_packaging
        Stream.GLASS -> R.string.stream_glass; Stream.ORGANIC -> R.string.stream_organic; Stream.TEXTILE -> R.string.stream_textile
        Stream.HAZARDOUS -> R.string.stream_hazardous; Stream.BULKY -> R.string.stream_bulky; Stream.OTHER -> R.string.stream_other
    },
)

/** The text of [input] as UTF-8, or null when it is longer than [limit] bytes: a calendar of bins is a few hundred kilobytes at most. */
internal fun readCapped(input: java.io.InputStream, limit: Int = 4_000_000): String? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

/** The line the Local cabinet shows for a pickup: "Tomorrow: paper, packaging · bins out tonight". */
@Composable
internal fun pickupLine(p: Pickup, today: LocalDate): String {
    val names = p.streams.filter { it != Stream.OTHER }.ifEmpty { p.streams }.map { streamName(it) }.joinToString(", ")
    val days = WasteCalendar.daysBetween(today, p.date).toInt()
    return when (days) {
        0 -> stringResource(R.string.collect_line_today, names)
        1 -> stringResource(R.string.collect_line_tomorrow, names)
        else -> stringResource(R.string.collect_line_day, p.date.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault()), names)
    }
}

/**
 * The waste calendar: the next collection on a pinned wall-calendar page with its bins drawn, the week as a strip of days, and
 * a reminder the night before. The calendar is the `.ics` file you import (Storage Access Framework); calendars from national
 * services are not built in, and the screen says so.
 */
@Composable
fun CollectScreen(args: Dest.Collect, nav: Navigator) {
    val services = LocalServices.current
    val hub = services.local
    val clock = appClock()
    val zone = clock.zone
    val today = remember(clock) { LocalDate.now(clock) }
    val waste by hub.waste.calendar.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var remind by remember { mutableStateOf(hub.prefs.bool("collect.remind")) }
    var hour by remember { mutableIntStateOf(hub.prefs.int("collect.hour", 20)) }

    val monday = remember(today) { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val week = remember(waste, monday) { waste?.pickups(monday, monday.plusDays(6)).orEmpty().associateBy { it.date } }
    val next = remember(waste, today) { waste?.next(today) }
    val specials = remember(waste, today) { waste?.pickups(today, today.plusDays(180)).orEmpty().filter { p -> p.streams.any { it.special } }.take(2) }
    val bodyTitle = stringResource(R.string.collect_reminder_title)
    val bodyText = stringResource(R.string.collect_reminder_text)
    val importLabel = stringResource(R.string.collect_import)

    fun reschedule() {
        val cal = hub.waste.calendar.value
        hub.reminders.keys("collect.").forEach { hub.reminders.cancel(it) }
        if (!remind || cal == null) return
        for (p in cal.pickups(today, today.plusDays(21))) {
            if (p.streams.all { it.special }) continue
            val at = LocalDateTime.of(p.date.minusDays(1), LocalTime.of(hour, 0)).atZone(zone).toInstant()
            if (at.isAfter(clock.instant())) hub.reminders.schedule("collect.${p.date}", at, bodyTitle, bodyText)
        }
    }

    // The reminders reach three weeks ahead: opening the screen lays the next three weeks again, so they do not run out.
    LaunchedEffect(Unit) {
        val allowed = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (remind && allowed) reschedule()
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val (text, name) = withContext(Dispatchers.IO) {
                val t = runCatching { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }.getOrNull()
                val n = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                t to n
            }
            val kept = text != null && withContext(Dispatchers.IO) { hub.waste.import(text, name) }
            error = if (kept) null else "bad"
            if (error == null) reschedule()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        remind = granted || Build.VERSION.SDK_INT < 33
        hub.prefs.putBool("collect.remind", remind)
        reschedule()
    }
    fun toggleRemind() {
        if (!remind && Build.VERSION.SDK_INT >= 33) { permission.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        remind = !remind
        hub.prefs.putBool("collect.remind", remind)
        reschedule()
    }

    BoardPage(background = Color(0xFFEDF0F2)) {
        BoardTitleBar(stringResource(R.string.collect_title), onBack = { nav.back() }, italic = false, size = 32, colour = Ink) {
            val name = waste?.name ?: hub.waste.source
            if (name != null) Text(name, color = Soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp), textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(12.dp))
        WallPage(next, today, hour, onImport = { pick.launch(arrayOf("text/calendar", "text/*", "application/octet-stream", "*/*")) })
        Spacer(Modifier.height(14.dp))
        if (waste != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) WeekCell(monday.plusDays(i.toLong()), week[monday.plusDays(i.toLong())], today, next?.date, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            ReminderRow(remind, hour, onToggle = ::toggleRemind, onHour = { hour = if (hour >= 22) 18 else hour + 1; hub.prefs.putInt("collect.hour", hour); reschedule() })
            Spacer(Modifier.height(12.dp))
            // The special days the calendar itself lists (bulky waste, chemical waste), with its own words for them.
            for (p in specials) {
                SpecialCard(p, today)
                Spacer(Modifier.height(8.dp))
            }
        }
        val shape = RoundedCornerShape(20.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Color.White).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE8EBEE)), contentAlignment = Alignment.Center) { Symbol("ui.download", tint = Color(0xFF3E4A56), size = 24.dp) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (waste == null) R.string.collect_import else R.string.collect_import_again), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(stringResource(R.string.collect_import_sub), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFF1EEE8)).clickable { pick.launch(arrayOf("text/calendar", "text/*", "application/octet-stream", "*/*")) }
                        .semantics { contentDescription = importLabel },
                    contentAlignment = Alignment.Center,
                ) { Symbol("ui.chevron-right", tint = Ink, size = 18.dp) }
            }
            if (error != null) Text(stringResource(R.string.collect_import_bad), color = Color(0xFFC8322A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.collect_scope), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            if (waste != null) Text(
                stringResource(R.string.collect_remove), color = Color(0xFFC8322A), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { hub.waste.clear(); remind = false; hub.prefs.putBool("collect.remind", false); reschedule() },
            )
        }
    }
}

/** A special day of the calendar: the board's yellow card for chemical waste, the violet tile for bulky waste, with the calendar's own title and the days to go. */
@Composable
private fun SpecialCard(p: Pickup, today: LocalDate) {
    val hazard = Stream.HAZARDOUS in p.streams
    val shape = RoundedCornerShape(20.dp)
    val title = p.labels.firstOrNull { WasteCalendar.classify(it).special } ?: p.labels.firstOrNull() ?: ""
    val days = WasteCalendar.daysBetween(today, p.date).toInt()
    val date = p.date.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.') + " " + p.date.dayOfMonth + " " + p.date.month.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.')
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).background(if (hazard) Color(0xFFFFF6DC) else Color.White).then(if (hazard) Modifier.border(1.5.dp, Color(0xFFF2D27A), shape) else Modifier).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (hazard) Color.White else Color(0xFFEDE7F8)), contentAlignment = Alignment.Center) {
            Symbol(if (hazard) "sign.warning" else Stream.BULKY.symbol, size = 26.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, color = if (hazard) Color(0xFF5A4A20) else Soft, fontSize = 12.sp, maxLines = 1)
        }
        Text(
            if (days == 0) stringResource(R.string.collect_out_today) else stringResource(R.string.collect_in_days, days), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
        )
    }
}

/** The pinned wall-calendar page: weekday, big date, the bins, and when to put them out. */
@Composable
private fun WallPage(next: Pickup?, today: LocalDate, hour: Int, onImport: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(264.dp), contentAlignment = Alignment.TopCenter) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier.padding(top = 10.dp).width(300.dp).height(240.dp).rotate(-1.5f).cssShadow(shape, 12.dp, 24.dp, Color(0x24000000)).clip(shape).background(Color.White),
        ) {
            val first = next?.streams?.firstOrNull { !it.special } ?: next?.streams?.firstOrNull()
            val band = first?.let { Color(it.colour.toInt()) } ?: Color(0xFF1B5FA8)
            Box(Modifier.fillMaxWidth().height(56.dp).background(band), contentAlignment = Alignment.Center) {
                Text(
                    (next?.date?.dayOfWeek?.getDisplayName(DateStyle.FULL, Locale.getDefault()) ?: stringResource(R.string.collect_no_calendar)).uppercase(),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.6.sp, maxLines = 1,
                )
            }
            if (next == null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.collect_empty), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.height(44.dp).clip(CircleShape).background(Ink).clickable(onClick = onImport).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.collect_import), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    Text(next.date.dayOfMonth.toString(), style = TextStyle(fontFamily = SerifFamily, fontSize = 96.sp, lineHeight = 96.sp, color = Ink))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        for (s in next.streams.filter { it != Stream.OTHER }.take(3)) Symbol(s.symbol, size = 44.dp, contentDescription = streamName(s))
                    }
                }
                val days = WasteCalendar.daysBetween(today, next.date).toInt()
                Row(
                    Modifier.fillMaxWidth().height(44.dp).dashedTop(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("ui.moon", tint = Soft, size = 16.dp)
                    Text(
                        if (days == 1) stringResource(R.string.collect_out_tonight, hour) else if (days == 0) stringResource(R.string.collect_out_today) else stringResource(R.string.collect_in_days, days),
                        color = Soft, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        // The pin.
        Box(
            Modifier.align(Alignment.TopCenter).offset(x = (-14).dp).size(28.dp).cssShadow(CircleShape, 4.dp, 6.dp, Color(0x4D000000)).clip(CircleShape)
                .background(Brush.radialGradient(0f to Color(0xFFFF9A76), 0.6f to Color(0xFFE8622C), 1f to Color(0xFFB8441B), center = androidx.compose.ui.geometry.Offset(10f, 9f), radius = 40f)),
        )
    }
}

private fun Modifier.dashedTop(): Modifier = this.drawBehind {
    drawLine(Color(0xFFD0D4D8), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
}

@Composable
private fun WeekCell(day: LocalDate, pickup: Pickup?, today: LocalDate, nextDate: LocalDate?, modifier: Modifier) {
    val isToday = day == today
    val special = pickup?.streams?.any { it.special } == true
    val bins = pickup?.streams?.filter { !it.special && it != Stream.OTHER }.orEmpty()
    val accent = bins.firstOrNull()?.let { Color(it.colour.toInt()) }
    val bg = when {
        special -> Color(0xFFFFF6DC)
        day == nextDate && accent != null -> accent.copy(alpha = 0.10f)
        else -> Color.White
    }
    val letter = day.dayOfWeek.getDisplayName(DateStyle.NARROW, Locale.getDefault()).uppercase()
    val streamNames = pickup?.streams?.map { streamName(it).lowercase() }
    val desc = day.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault()) + ": " + (streamNames?.joinToString() ?: "-")
    Column(
        modifier.height(86.dp).clip(RoundedCornerShape(14.dp)).background(bg).then(if (isToday) Modifier.border(2.dp, Ink, RoundedCornerShape(14.dp)) else Modifier).semantics { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text(letter, color = if (day == nextDate && accent != null) accent else if (isToday) Ink else Soft.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (special) Symbol("sign.warning", size = 18.dp)
            else Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in bins.take(3)) Box(Modifier.size(if (bins.size == 1) 16.dp else 10.dp, 22.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 5.dp, bottomEnd = 5.dp)).background(Color(s.colour.toInt())))
            }
        }
    }
}

@Composable
private fun ReminderRow(on: Boolean, hour: Int, onToggle: () -> Unit, onHour: () -> Unit) {
    val stateLabel = stringResource(if (on) R.string.local_on else R.string.local_off)
    Row(
        Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(22.dp)).background(Ink).padding(start = 18.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui.bell", tint = Color(0xFFFFD166), size = 22.dp)
        Text(stringResource(R.string.collect_remind), Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text("%d:00".format(hour), Modifier.clickable(onClick = onHour), color = Color(0xFFFFD166), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Box(
            Modifier.width(48.dp).height(30.dp).clip(CircleShape).background(if (on) Color(0xFF1E9E5A) else Color(0xFF4A505C)).clickable(onClick = onToggle)
                .semantics { contentDescription = stateLabel; role = androidx.compose.ui.semantics.Role.Switch },
        ) {
            Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(24.dp).clip(CircleShape).background(Color.White))
        }
    }
}
