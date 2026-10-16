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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.fillMaxHeight
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssGradient
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.CssCorners

private val Paper = Color(0xFFF4EAD6)
private val Ink = Color(0xFF2A1E10)
private val Sepia = Color(0xFF7A6A50)
private val Brass = Color(0xFF8A6A40)

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
            all == null -> Column(Modifier.padding(top = boardShift()).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.time_title), onBack = { nav.back() }, colour = Ink, size = 32, backBackground = Color(0xE6FFFFFF), backTint = Color(0xFF14161B))
                Spacer(Modifier.height(24.dp))
                BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, Sepia.css(), 18f))
            }
            all.none { it.year != null } -> Column(Modifier.padding(top = boardShift()).padding(horizontal = 16.dp)) {
                BoardTitleBar(stringResource(R.string.time_title), onBack = { nav.back() }, colour = Ink, size = 32, backBackground = Color(0xE6FFFFFF), backTint = Color(0xFF14161B))
                Spacer(Modifier.height(24.dp))
                StateMessage("ui-landmark", stringResource(R.string.time_none), text = if (all.isEmpty()) stringResource(R.string.time_none_text) else stringResource(R.string.time_nodates, all.size), tint = Sepia)
            }
            else -> {
                Stage(all, here, year, split, today, onSplit = { split = it })
                Chip(all, year, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 436.dp))
                // The board's header over the picture: the back button at 90 per cent white, the title in white with its shadow.
                Row(
                    Modifier.padding(top = boardShift()).padding(horizontal = 16.dp).padding(top = 42.dp).fillMaxWidth().height(44.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackCircle({ nav.back() }, background = Color(0xE6FFFFFF), tint = Color(0xFF14161B))
                    BoardLine(stringResource(R.string.time_title), boardStyle(32f, 400, 0xFFFFFFFF, 32f, SERIF, italic = true, shadow = CssShadow(0f, 2f, 8f, 0f, 0x80000000)), Modifier.weight(1f))
                }
                Sheet(all, year, today, onYear = { year = it }, modifier = Modifier.padding(top = 520.dp))
            }
        }
    }
}

/** The pill that says how many of the buildings stood, in the board's dark caption chip: 24 px, 8 in, Geist 11 at 800. */
@Composable
private fun Chip(all: List<Footprint>, year: Int, modifier: Modifier) {
    val s = TimeMachine.slice(all, year)
    Box(modifier.height(24.dp).boardBox(0xD92A1E10, corners(12f)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        BoardLine(stringResource(R.string.time_stood, s.stood, s.total, year), boardStyle(11f, 800, Paper.css(), 14f))
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
                // The board's vignette over the past: clear to 55 per cent of the way out, then warm brown at 35 per cent.
                drawRect(VIGNETTE.brush(Size(splitX, size.height), drawContext.density.density), size = Size(splitX, size.height))
            }
        }
        val handleX = with(density) { (widthPx * split).toDp() }
        // The seam: 4 px of paper with its 10 px shadow.
        Box(Modifier.offset(x = handleX - 2.dp).width(4.dp).fillMaxHeight().boardBox(Paper.css(), shadows = shadow(0f, 0f, 10f, 0x66000000), clip = false))
        Box(
            Modifier.offset(x = handleX - 24.dp, y = 300.dp).size(48.dp).boardBox(r = corners(24f), shadows = shadow(0f, 6f, 14f, 0x66000000), grad = listOf(brass(48f)))
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); if (widthPx > 0) onSplit((split + drag.x / widthPx).coerceIn(0.08f, 0.92f)) } }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui.code.2", tint = Ink, size = 22.dp) }
        Pill("$year", Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 100.dp), 0xCC2A1E10, Paper.css())
        Pill("$today", Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 100.dp), 0xE6FFFFFF, 0xFF14161B)
        Legend(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp))
    }
}

/** The board's year pill over the picture: 30 px, 12 in, Instrument Serif 20. */
@Composable
private fun Pill(text: String, modifier: Modifier, background: Argb, ink: Argb) {
    Box(modifier.height(30.dp).boardBox(background, corners(15f)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        BoardLine(text, boardStyle(20f, 400, ink, 26f, SERIF))
    }
}

/** The board's brass bead: lit from 35 per cent across and 30 down, for a [side] px circle. */
private fun brass(side: Float) = cssRadial(true, side * 0.35f, side * 0.3f, 0f to 0xFFF6D98A, 0.7f to 0xFFB98A36, 1f to 0xFF7A5A20)

/** The vignette over the past (CSS `radial-gradient(ellipse at 50% 50%, transparent 55%, rgba(60,40,20,.35))`), centred on its box. */
private val VIGNETTE = CssGradient { s, d -> cssRadial(false, s.width / 2f / d, s.height / 2f / d, 0.55f to 0x003C2814, 1f to 0x593C2814).brush(s, d) }

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

/** The board's sheet from 520: paper with radius 28, 18 px down and 16 in, 12 apart: the year slider, the cards, the notes. */
@Composable
private fun Sheet(all: List<Footprint>, year: Int, today: Int, onYear: (Int) -> Unit, modifier: Modifier) {
    val cards = remember(all, year) { TimeMachine.cards(all, year) }
    Column(
        modifier.fillMaxSize().boardBox(Paper.css(), CssCorners(28f, 28f, 0f, 0f)).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
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
        if (unknown > 0) BoardPara(stringResource(R.string.time_unknown_note, unknown), boardStyle(11f, 400, Sepia.css(), 14f))
        BoardPara(stringResource(R.string.time_footer), boardStyle(10f, 400, 0xFF8A7A60, 13f, align = TextAlign.Center), Modifier.fillMaxWidth())
    }
}

/** A card of the board: 64 px, radius 18, 14 in; the year in Instrument Serif 22 in a 52 px column, the title and line 2 apart. Planned work is dashed. */
@Composable
private fun YearCard(year: String, title: String, sub: String?, dashed: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).boardBox(if (dashed) 0x99FFFFFF else 0xFFFFFFFF, corners(18f))
            .then(if (dashed) Modifier.dashedBorder() else Modifier).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardLine(year, boardStyle(22f, 400, Brass.css(), 29f, SERIF), Modifier.width(52.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardLine(title, boardStyle(14f, 800, Ink.css(), 18f))
            if (sub != null) BoardLine(sub, boardStyle(12f, 400, Sepia.css(), 15.5f))
        }
    }
}

/** CSS `border: 1.5px dashed #C9A26A` on the card's radius 18: dashes of three widths, as a browser draws them. */
private fun Modifier.dashedBorder(): Modifier = drawBehind {
    val w = 1.5.dp.toPx()
    val r = 18.dp.toPx() - w / 2
    drawRoundRect(
        Color(0xFFC9A26A), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r, r),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * w, 3 * w))),
    )
}

/**
 * The board's brass timeline, 56 px high and 16 px in from the sheet: the 8 px track (its inset shadow) at 14, a tick of
 * 2 x 16 at 10 and its year at 34 for the years that matter, the 24 px bead at the year.
 */
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
        Box(
            Modifier.fillMaxWidth().padding(top = 14.dp).height(8.dp)
                .boardBox(r = corners(4f), shadows = shadow(0f, 1f, 2f, 0x4C000000, inset = true), grad = listOf(cssLinear(90f, 0f to 0xFF8A6A40, 0.8f to 0xFFC9A26A, 1f to 0xFFE8D8B8))),
        )
        val widthDp = with(density) { widthPx.toDp() }
        for (y in listOf(1500, 1700, 1900, today)) {
            val x = widthDp * ((y - TimeMachine.FROM) / span)
            Box(Modifier.offset(x = x - 1.dp, y = 10.dp).size(2.dp, 16.dp).background(Color(0xFF6B4A20)))
            BoardLine(
                "$y", boardStyle(12f, 800, if (y > today) Brass.css() else Ink.css(), 15.5f, align = TextAlign.Center),
                Modifier.offset(x = x - 20.dp, y = 34.dp).width(40.dp), overflow = TextOverflow.Visible,
            )
        }
        val thumbX = widthDp * ((year - TimeMachine.FROM) / span)
        Box(Modifier.offset(x = thumbX - 12.dp, y = 6.dp).size(24.dp).boardBox(r = corners(12f), shadows = shadow(0f, 3f, 8f, 0x59000000), grad = listOf(brass(24f)), clip = false))
    }
}
