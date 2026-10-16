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
import app.atlas.ui.kit.SansFamily
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.compositeOver
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssLinear

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

    // The board's page: a cool grey, lighter at 45 per cent across.
    BoardPage(background = Color.Transparent, modifier = Modifier.boardBox(grad = listOf(cssLinear(90f, 0f to 0xFFE4E8EB, 0.45f to 0xFFF2F4F5, 1f to 0xFFE6EAED)), clip = false)) {
        BoardTitleBar(stringResource(R.string.collect_title), onBack = { nav.back() }, italic = false, size = 32, colour = Ink) {
            val name = waste?.name ?: hub.waste.source
            if (name != null) BoardLine(name, boardStyle(13f, 600, Soft.css(), 17f, align = TextAlign.End), Modifier.widthIn(max = 160.dp))
        }
        Spacer(Modifier.height(18.dp))
        WallPage(next, today, hour, onImport = { pick.launch(arrayOf("text/calendar", "text/*", "application/octet-stream", "*/*")) })
        Spacer(Modifier.height(24.dp))
        if (waste != null) {
            // The week: seven days of 86 px, 4 apart.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) WeekCell(monday.plusDays(i.toLong()), week[monday.plusDays(i.toLong())], today, next?.date, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            ReminderRow(remind, hour, onToggle = ::toggleRemind, onHour = { hour = if (hour >= 22) 18 else hour + 1; hub.prefs.putInt("collect.hour", hour); reschedule() })
            Spacer(Modifier.height(16.dp))
            // The special days the calendar itself lists (bulky waste, chemical waste), with its own words for them.
            for (p in specials) {
                SpecialCard(p, today)
                Spacer(Modifier.height(8.dp))
            }
        }
        // Bringing a calendar: the board's e-waste card (76 px, the grey tile, the round chevron).
        Row(
            Modifier.fillMaxWidth().height(76.dp).boardBox(0xFFFFFFFF, corners(20f)).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).boardBox(0xFFE8EBEE, corners(14f)), contentAlignment = Alignment.Center) { Symbol("ui.download", tint = Color(0xFF3E4A56), size = 24.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BoardLine(stringResource(if (waste == null) R.string.collect_import else R.string.collect_import_again), boardStyle(16f, 800, Ink.css(), 20.5f))
                BoardPara(stringResource(R.string.collect_import_sub), boardStyle(12f, 400, Soft.css(), 15.5f), lines = 2)
            }
            Box(
                Modifier.size(44.dp).boardBox(0xFFF1EEE8, corners(22f)).clickable { pick.launch(arrayOf("text/calendar", "text/*", "application/octet-stream", "*/*")) }
                    .semantics { contentDescription = importLabel },
                contentAlignment = Alignment.Center,
            ) { Symbol("ui.chevron-right", tint = Ink, size = 18.dp) }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) BoardLine(stringResource(R.string.collect_import_bad), boardStyle(12f, 700, 0xFFC8322A, 15.5f))
            BoardPara(stringResource(R.string.collect_scope), boardStyle(12f, 400, Soft.css(), 15.5f))
            if (waste != null) BoardLine(
                stringResource(R.string.collect_remove), boardStyle(13f, 700, 0xFFC8322A, 17f),
                Modifier.clickable { hub.waste.clear(); remind = false; hub.prefs.putBool("collect.remind", false); reschedule() },
            )
        }
    }
}

/** A special day of the calendar: the board's yellow card for chemical waste, the violet tile for bulky waste, with the calendar's own title and the days to go. */
@Composable
private fun SpecialCard(p: Pickup, today: LocalDate) {
    val hazard = Stream.HAZARDOUS in p.streams
    val title = p.labels.firstOrNull { WasteCalendar.classify(it).special } ?: p.labels.firstOrNull() ?: ""
    val days = WasteCalendar.daysBetween(today, p.date).toInt()
    val date = p.date.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.') + " " + p.date.dayOfMonth + " " + p.date.month.getDisplayName(DateStyle.SHORT, Locale.getDefault()).trimEnd('.')
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .boardBox(if (hazard) 0xFFFFF6DC else 0xFFFFFFFF, corners(20f), if (hazard) shadow(0f, 0f, 0f, 0xFFF2D27A, spread = 1.5f, inset = true) else emptyList())
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).boardBox(if (hazard) 0xFFFFFFFF else 0xFFEDE7F8, corners(14f)), contentAlignment = Alignment.Center) {
            if (hazard) Symbol("sign.warning", tint = Ink, size = 26.dp) else BoxSymbol(Stream.BULKY.symbol, 28f, 24f, Color(0xFF6B45B8))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BoardLine(title, boardStyle(16f, 800, Ink.css(), 20.5f))
            BoardLine(date, boardStyle(12f, 400, if (hazard) 0xFF5A4A20 else Soft.css(), 15.5f, tnum = true))
        }
        BoardLine(if (days == 0) stringResource(R.string.collect_out_today) else stringResource(R.string.collect_in_days, days), boardStyle(16f, 900, Ink.css(), 20.5f, tnum = true))
    }
}

/**
 * The pinned wall-calendar page, 300 x 250 turned 1.5 degrees: the page 240 high from 10 down (radius 10, the board's
 * shadow), the weekday band of 56, the big date and the bins 22 apart, the night line of 44 under a dashed rule, the pin.
 */
@Composable
private fun WallPage(next: Pickup?, today: LocalDate, hour: Int, onImport: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.width(300.dp).height(250.dp).rotate(-1.5f)) {
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().height(240.dp).boardBox(0xFFFFFFFF, corners(10f), shadow(0f, 12f, 24f, 0x24000000))) {
                val first = next?.streams?.firstOrNull { !it.special } ?: next?.streams?.firstOrNull()
                val band = first?.colour ?: 0xFF1B5FA8
                Box(Modifier.fillMaxWidth().height(56.dp).boardBox(band), contentAlignment = Alignment.Center) {
                    BoardLine(
                        (next?.date?.dayOfWeek?.getDisplayName(DateStyle.FULL, Locale.getDefault()) ?: stringResource(R.string.collect_no_calendar)).uppercase(),
                        boardStyle(18f, 900, 0xFFFFFFFF, 27.41f, spacing = 3.6f),
                    )
                }
                if (next == null) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        BoardPara(stringResource(R.string.collect_empty), boardStyle(16f, 700, Ink.css(), 20.5f, align = TextAlign.Center))
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.height(44.dp).boardBox(Ink.css(), corners(22f)).clickable(onClick = onImport).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                            BoardLine(stringResource(R.string.collect_import), boardStyle(14f, 800, 0xFFFFFFFF, 18f))
                        }
                    }
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        BoardLine(next.date.dayOfMonth.toString(), boardStyle(96f, 400, Ink.css(), 96f, SERIF, tnum = true), overflow = TextOverflow.Visible)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                            for (s in next.streams.filter { it != Stream.OTHER }.take(3)) {
                                val name = streamName(s)
                                BoxSymbol(s.symbol, 44f, 64f, Ink, Modifier.semantics { contentDescription = name })
                            }
                        }
                    }
                    val days = WasteCalendar.daysBetween(today, next.date).toInt()
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).dashedTop(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("ui.moon", tint = Soft, size = 16.dp)
                        BoardLine(
                            if (days == 1) stringResource(R.string.collect_out_tonight, hour) else if (days == 0) stringResource(R.string.collect_out_today) else stringResource(R.string.collect_in_days, days),
                            boardStyle(14f, 700, Soft.css(), 22.1f, tnum = true),
                        )
                    }
                }
            }
            // The pin: 28 px at 136 across, a red bead lit from 35 per cent across and 30 down.
            Box(
                Modifier.offset(x = 136.dp).size(28.dp)
                    .boardBox(r = corners(14f), shadows = shadow(0f, 4f, 6f, 0x4D000000), grad = listOf(cssRadial(true, 9.8f, 8.4f, 0f to 0xFFFF9A76, 0.6f to 0xFFE8622C, 1f to 0xFFB8441B))),
            )
        }
    }
}

private fun Modifier.dashedTop(): Modifier = this.drawBehind {
    drawLine(Color(0xFFD0D4D8), androidx.compose.ui.geometry.Offset(0f, 0.5.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
}

/** The pale ground of the day the next bins go out: the board's for the blue bin, else the bin's colour at 9 per cent. */
private fun dayTint(s: Stream): Argb = if (s == Stream.PACKAGING) 0xFFEAF2FB else Color(s.colour.toInt()).copy(alpha = 0.09f).compositeOver(Color.White).css()

/** One day of the week strip: 86 px, its letter 12 px at 800 and its bins 6 below, centred; today ringed in ink. */
@Composable
private fun WeekCell(day: LocalDate, pickup: Pickup?, today: LocalDate, nextDate: LocalDate?, modifier: Modifier) {
    val isToday = day == today
    val special = pickup?.streams?.any { it.special } == true
    val bins = pickup?.streams?.filter { !it.special && it != Stream.OTHER }.orEmpty()
    val accent = bins.firstOrNull()
    val bg = when {
        special -> 0xFFFFF6DC
        day == nextDate && accent != null -> dayTint(accent)
        else -> 0xFFFFFFFF
    }
    val ink = when {
        special -> 0xFF8A5A00
        day == nextDate && accent != null -> accent.colour
        isToday -> Ink.css()
        else -> 0xFF676D7C
    }
    val letter = day.dayOfWeek.getDisplayName(DateStyle.NARROW, Locale.getDefault()).uppercase()
    val streamNames = pickup?.streams?.map { streamName(it).lowercase() }
    val desc = day.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault()) + ": " + (streamNames?.joinToString() ?: "-")
    Column(
        modifier.height(86.dp).boardBox(bg, corners(14f), if (isToday) shadow(0f, 0f, 0f, Ink.css(), spread = 2f, inset = true) else emptyList()).semantics { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        BoardLine(letter, boardStyle(12f, 800, ink, 15.5f))
        if (special) {
            Symbol("bin.hazardous", tint = Color(0xFF8A5A00), size = 18.dp)
        } else Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val one = bins.size == 1
                for (s in bins.take(3)) Box(Modifier.size(if (one) 16.dp else 10.dp, 22.dp).boardBox(s.colour, if (one) CssCorners(3f, 3f, 5f, 5f) else CssCorners(2f, 2f, 4f, 4f)))
            }
        }
    }
}

/** The board's reminder bar: 70 px of ink, 18 px in on the left and 12 on the right, the bell, the line, the hour and the switch 12 apart. */
@Composable
private fun ReminderRow(on: Boolean, hour: Int, onToggle: () -> Unit, onHour: () -> Unit) {
    val stateLabel = stringResource(if (on) R.string.local_on else R.string.local_off)
    Row(
        Modifier.fillMaxWidth().height(70.dp).boardBox(Ink.css(), corners(22f)).padding(start = 18.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui.bell", tint = Color(0xFFFFD166), size = 22.dp)
        BoardPara(stringResource(R.string.collect_remind), boardStyle(15f, 700, 0xFFFFFFFF, 19.5f), Modifier.weight(1f), lines = 2)
        BoardLine("%d:00".format(hour), boardStyle(14f, 800, 0xFFFFD166, 18f, tnum = true), Modifier.clickable(onClick = onHour))
        Box(
            Modifier.width(48.dp).height(30.dp).boardBox(if (on) 0xFF1E9E5A else 0xFF4A505C, corners(15f)).clickable(onClick = onToggle)
                .semantics { contentDescription = stateLabel; role = androidx.compose.ui.semantics.Role.Switch },
        ) {
            Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(24.dp).boardBox(0xFFFFFFFF, corners(12f)))
        }
    }
}
