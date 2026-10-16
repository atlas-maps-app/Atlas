package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.LocalDate
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

private val Paper = Color(0xFFF4EAD6)
private val Ink = Color(0xFF2A1E10)
private val Sepia = Color(0xFF7A6A50)
private val Brass = Color(0xFF8A6A40)
private val Thumb = Brush.radialGradient(listOf(Color(0xFFF6D98A), Color(0xFFB98A36), Color(0xFF7A5A20)))

/** The board's stage: 412 x 540, one metre of ground to one drawing unit. */
private const val STAGE_W = 412f

/** Radius of the buildings loaded and drawn round the point, metres. */
private const val RADIUS_M = 330.0

/** The time machine: the buildings round you and the year each was built, with a slider back to 1500 and a split between then and now. */
@Composable
fun TimeScreen(args: Dest.Time, nav: Navigator) {
    val services = LocalServices.current
    val here = args.at ?: rememberHere()
    val lang = remember { Locale.getDefault().language }
    val clock = appClock()
    val today = remember(clock) { LocalDate.now(clock).year }
    val loaded by produceState<List<Footprint>?>(null, here) { value = Footprints.around(services, here, RADIUS_M, lang) }
    var year by remember { mutableIntStateOf(1900) }
    var split by remember { mutableFloatStateOf(0.5f) }
    val all = loaded

    Box(Modifier.fillMaxSize().background(Paper)) {
        when {
            all == null -> Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.time_title), onBack = { nav.back() }, colour = Ink)
                Text(stringResource(R.string.kit_loading), Modifier.padding(top = 24.dp), color = Sepia, fontSize = 14.sp)
            }
            all.none { it.year != null } -> Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.time_title), onBack = { nav.back() }, colour = Ink)
                Spacer(Modifier.height(24.dp))
                StateMessage("ui-landmark", stringResource(R.string.time_none), text = if (all.isEmpty()) stringResource(R.string.time_none_text) else stringResource(R.string.time_nodates, all.size), tint = Sepia)
            }
            else -> {
                Stage(all, here, year, split, today, onSplit = { split = it })
                Chip(all, year, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 436.dp))
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Paper.copy(alpha = 0.95f), Paper.copy(alpha = 0.8f), Color.Transparent))))
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
                    BoardTitleBar(stringResource(R.string.time_title), onBack = { nav.back() }, colour = Ink, backBackground = Color.White.copy(alpha = 0.9f))
                }
                Sheet(all, year, today, onYear = { year = it }, modifier = Modifier.padding(top = 520.dp))
            }
        }
    }
}

/** The pill that says how many of the buildings stood. */
@Composable
private fun Chip(all: List<Footprint>, year: Int, modifier: Modifier) {
    val s = TimeMachine.slice(all, year)
    Box(modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(Ink.copy(alpha = 0.85f)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.time_stood, s.stood, s.total, year), color = Paper, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** The split view: sepia buildings of [year] left of the brass handle, today's coloured ones right of it. */
@Composable
private fun Stage(all: List<Footprint>, at: LatLon, year: Int, split: Float, today: Int, onSplit: (Float) -> Unit) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    val boxes = remember(all, at) { projected(all, at) }
    val label = stringResource(R.string.time_compare, year)
    Box(Modifier.fillMaxWidth().height(540.dp).onSizeChanged { widthPx = it.width.toFloat() }.pointerInput(Unit) { detectTapGestures { p -> if (widthPx > 0) onSplit((p.x / widthPx).coerceIn(0.08f, 0.92f)) } }) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / STAGE_W
            val splitX = size.width * split
            // Today: the whole city on clean paper.
            drawRect(Color(0xFFEFE8D8))
            for ((f, r) in boxes) fillFootprint(r, k, TimeMachine.era(f.year).colour, if (f.year == null) 0.45f else 1f)
            // Then: sepia paper over the left, only what stood.
            clipRect(0f, 0f, splitX, size.height) {
                drawRect(Color(0xFFD7C39A))
                for ((f, r) in boxes) {
                    if (f.year == null) fillFootprint(r, k, Color(0xFF8A6A40), 0.22f)
                    else if (TimeMachine.stood(f, year)) fillFootprint(r, k, Color(0xFF5B3E1E), 1f)
                }
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x593C2814)), center = Offset(splitX / 2, size.height / 2), radius = size.height * 0.75f))
            }
            // The seam.
            drawRect(Paper, Offset(splitX - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
        }
        val handleX = with(density) { (widthPx * split).toDp() }
        Box(
            Modifier.offset(x = handleX - 24.dp, y = 300.dp).size(48.dp).clip(CircleShape).background(Thumb)
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); if (widthPx > 0) onSplit((split + drag.x / widthPx).coerceIn(0.08f, 0.92f)) } }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { PathGlyph(BoardPaths.CHEVRONS, tint = Ink, size = 22.dp, strokeWidth = 2.4f) }
        Pill("$year", Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 100.dp), Ink.copy(alpha = 0.8f), Paper)
        Pill("$today", Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 100.dp), Color.White.copy(alpha = 0.9f), Color(0xFF14161B))
        Legend(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp))
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier, background: Color, ink: Color) {
    Box(modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(background).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ink, style = TextStyle(fontFamily = SerifFamily, fontSize = 20.sp), maxLines = 1)
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (e in TimeMachine.Era.entries) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(e.colour))
            Text(
                stringResource(
                    when (e) {
                        TimeMachine.Era.OLD -> R.string.time_era_old
                        TimeMachine.Era.BRICK -> R.string.time_era_brick
                        TimeMachine.Era.OCHRE -> R.string.time_era_ochre
                        TimeMachine.Era.POSTWAR -> R.string.time_era_postwar
                        TimeMachine.Era.NEW -> R.string.time_era_new
                        TimeMachine.Era.UNKNOWN -> R.string.time_era_unknown
                    },
                ),
                Modifier.padding(end = 3.dp), color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

/** Each building's footprint in drawing units, [at] at the middle of the stage; oldest first so that what is newer lies over it. */
private fun projected(all: List<Footprint>, at: LatLon): List<Pair<Footprint, Rect>> {
    val mx = 111_320.0 * cos(Math.toRadians(at.lat))
    val my = 110_574.0
    fun x(lon: Double) = (STAGE_W / 2 + (lon - at.lon) * mx).toFloat()
    fun y(lat: Double) = (290.0 + (at.lat - lat) * my).toFloat()
    return all.sortedBy { it.year ?: 9999 }.map { f ->
        val left = x(f.box.west)
        val top = y(f.box.north)
        f to Rect(left, top, x(f.box.east).coerceAtLeast(left + 2f), y(f.box.south).coerceAtLeast(top + 2f))
    }
}

private fun DrawScope.fillFootprint(r: Rect, k: Float, colour: Color, alpha: Float) {
    val tl = Offset(r.left * k, r.top * k)
    val sz = Size(r.width * k, r.height * k)
    drawRect(colour.copy(alpha = alpha), tl, sz)
    if (alpha > 0.5f) drawRect(Color.Black.copy(alpha = 0.22f), tl, sz, style = Stroke(0.6f * k))
}

/** The sheet under the stage: the year slider and the buildings that mark the year. */
@Composable
private fun Sheet(all: List<Footprint>, year: Int, today: Int, onYear: (Int) -> Unit, modifier: Modifier) {
    val cards = remember(all, year) { TimeMachine.cards(all, year) }
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Paper).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        YearSlider(year, today, onYear)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in cards.before) {
                val kind = f.place.tags["building"]?.takeIf { it != "yes" }?.replace('_', ' ')
                YearCard(f.year!!.toString(), f.name ?: "", if (kind != null) stringResource(R.string.time_stands_kind, kind) else stringResource(R.string.time_stands), false)
            }
            if (cards.before.isEmpty()) YearCard(year.toString(), stringResource(R.string.time_nothing_yet), null, false)
            cards.after?.let { YearCard(it.year!!.toString(), it.name ?: "", stringResource(R.string.time_later, it.year!! - year), false) }
            cards.planned?.let { YearCard(today.toString(), it.name ?: it.place.tags["construction"]?.replace('_', ' ')?.replaceFirstChar { c -> c.titlecase() } ?: "", stringResource(R.string.time_planned), true) }
        }
        val unknown = all.count { it.year == null }
        if (unknown > 0) Text(stringResource(R.string.time_unknown_note, unknown), color = Sepia, fontSize = 11.sp, lineHeight = 15.sp)
        Text(stringResource(R.string.time_footer), color = Color(0xFF8A7A60), fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun YearCard(year: String, title: String, sub: String?, dashed: Boolean) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(shape).background(if (dashed) Color.White.copy(alpha = 0.6f) else Color.White)
            .then(if (dashed) Modifier.border(1.5.dp, Color(0xFFC9A26A), shape) else Modifier).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(year, Modifier.width(52.dp), style = TextStyle(fontFamily = SerifFamily, fontSize = 22.sp, color = Brass), maxLines = 1)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Sepia, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The brass timeline: 1500 to 2030, a thumb to drag, the years that matter ticked. */
@Composable
private fun YearSlider(year: Int, today: Int, onYear: (Int) -> Unit) {
    val label = stringResource(R.string.time_year)
    val span = (TimeMachine.TO - TimeMachine.FROM).toFloat()
    var widthPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    fun at(x: Float) = (TimeMachine.FROM + (x / widthPx).coerceIn(0f, 1f) * span).roundToInt()
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp).onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) { detectTapGestures { onYear(at(it.x)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); onYear(at(change.position.x)) } }
            .semantics {
                contentDescription = "$label $year"
                progressBarRangeInfo = ProgressBarRangeInfo(year.toFloat(), TimeMachine.FROM.toFloat()..TimeMachine.TO.toFloat(), 0)
                setProgress { v -> onYear(v.roundToInt().coerceIn(TimeMachine.FROM, TimeMachine.TO)); true }
            },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(0f to Color(0xFF8A6A40), 0.8f to Color(0xFFC9A26A), 1f to Color(0xFFE8D8B8))))
        val widthDp = with(density) { widthPx.toDp() }
        for ((y, strong) in listOf(1500 to false, 1700 to false, 1900 to false, today to true)) {
            val x = widthDp * ((y - TimeMachine.FROM) / span)
            Box(Modifier.offset(x = x - 1.dp, y = 10.dp).size(2.dp, 16.dp).background(Color(0xFF6B4A20)))
            Text(
                "$y", Modifier.offset(x = (x - 20.dp).coerceIn((-16).dp, maxOf((-16).dp, widthDp - 24.dp)), y = 34.dp).width(40.dp),
                color = if (strong) Ink else Brass, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            )
        }
        val thumbX = widthDp * ((year - TimeMachine.FROM) / span)
        Box(Modifier.offset(x = thumbX - 12.dp, y = 6.dp).size(24.dp).clip(CircleShape).background(Thumb))
    }
}
