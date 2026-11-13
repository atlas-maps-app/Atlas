package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.places.Brand
import app.atlas.places.Palette
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.BrandSign
import app.atlas.ui.kit.DarkColors
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.LightColors
import app.atlas.ui.kit.SheetLook
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.openTotal
import app.atlas.ui.kit.openTotalSaid
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/*
 * The one search's look (decision 12), the Index: warm paper over the map, every kind a card in its own colour, the
 * app's own faces (Instrument Serif upright for what is named, as the place sheet names a place; Geist heavy for the
 * figures read first, minutes and counts, as the app sets its numerals; Geist for the rest), symbols in place of words
 * wherever a symbol says it (the instructions: "text is only what must be text"). Depth only where it means something:
 * a raised card is something to tap, a tray holds a shelf, a stack is a brand's branches or the kinds asleep.
 */

/** The Index's colours, light and dark. */
@Immutable
internal class IndexColors(
    val paper: Color,
    val tray: Color,
    val card: Color,
    val ink: Color,
    val soft: Color,
    val faint: Color,
    val hair: Color,
    /** The shade along a tray's top rim. */
    val shade: Color,
    val open: Color,
    val soon: Color,
    /** The kept filter's bookmark. */
    val brass: Color,
    val shadow: Color,
    val grabber: Color,
    val dark: Boolean,
    /**
     * The panel a list's rows sit in: What's here's own "NEAREST" panel, in its very shade by day and by night. It is
     * not a colour of this sheet's own but the kit's muted card, the one value What's here reads too, so the two
     * cannot drift (the owner, 12 November, of a shade written here apart from it: "I noticed the cream paper panel
     * shade changed a shade more brownish yellow, it shouldnt", then "it should match the nearby panel shade, isnt it
     * shared?").
     */
    val panel: Color,
) {
    /** Ink's opposite, for words on an ink chip. */
    val onInk: Color get() = if (dark) paper else Color.White

    /** The sheet: paper, 30 round at the top, lifted off the map, as the place sheet is. */
    val sheet: SheetLook by lazy {
        SheetLook(Modifier.cssShadow(SHEET_TOP, y = (-10).dp, blur = 30.dp, colour = shadow).clip(SHEET_TOP).background(paper), grabber)
    }

    fun of(t: Tone): Color = when (t) {
        Tone.OPEN -> open
        Tone.SOON -> soon
        Tone.CLOSED, Tone.UNKNOWN -> soft
    }

    companion object {
        /** The place sheet's paper, shadow and grabber (the Place board's), so a place opens on the paper it was found on. */
        val Light = IndexColors(
            paper = Color(SHEET_PAPER), tray = Color(0xFFF0ECE5), card = Color.White, ink = Color(0xFF14161B),
            soft = Color(0xFF5D616B), faint = Color(0xFF9A9EA6), hair = Color(0x1A14161B), shade = Color(0x123A2E1A),
            open = Color(0xFF178A4E), soon = Color(0xFFB0661A), brass = Color(0xFFB08D4A), shadow = Color(0x1F181C30),
            grabber = Color(0xFFD8D4CC), dark = false, panel = LightColors.cardMuted,
        )
        val Dark = IndexColors(
            paper = Color(0xFF17181B), tray = Color(0xFF0F1012), card = Color(0xFF25272C), ink = Color(0xFFF2EEE6),
            soft = Color(0xFFAEB2BA), faint = Color(0xFF6F737C), hair = Color(0x1FFFFFFF), shade = Color(0x47000000),
            open = Color(0xFF4CC38A), soon = Color(0xFFE0A458), brass = Color(0xFFC9A65E), shadow = Color(0x8C000000),
            grabber = Color(0xFF3A3C42), dark = true, panel = DarkColors.cardMuted,
        )
    }
}

private val SHEET_TOP = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

@Composable
internal fun indexColors(): IndexColors = if (Atlas.colors.isDark) IndexColors.Dark else IndexColors.Light

// ---------------------------------------------------------------------------------------------------------------- type

/**
 * A title: Geist bold and set tight, the app's own face (the user, 4 November, of Instrument Serif on a kind's hero:
 * "different font").
 */
internal fun indexTitle(size: Float) = boardText(size, 700, size * 1.18f, spacing = -0.022f * size)

/** A figure read first (minutes, a count, a time): Geist heavy, all one width, as the app sets its numerals. */
internal fun indexFigure(size: Float, weight: Int = 750) = boardText(size, weight, size * 1.08f, spacing = -0.02f * size, tnum = true)

/** Small tracked capitals (the words upper-cased by [Caps]). */
internal val INDEX_CAPS = boardText(10f, 650, 13f, spacing = 1.4f)

/** A row's name: the "NEAREST" row's, Geist 800 at 15 ([ROW_TITLE]). */
internal val INDEX_NAME = ROW_TITLE
internal val INDEX_META = boardText(12.5f, 450, 16f)

/** The line under a row's name: the "NEAREST" row's, 12 on 15.5 ([ROW_LINE]). */
internal val INDEX_LINE = ROW_LINE

/** The kicker over a panel, as What's here heads its own ("NEAREST"): the kit's [Kicker], in this sheet's soft ink at night. */
@Composable
internal fun PanelKicker(text: String, modifier: Modifier = Modifier) {
    val c = indexColors()
    if (c.dark) Kicker(text, modifier, colour = c.soft) else Kicker(text, modifier)
}

/** A line of small capitals. */
@Composable
internal fun Caps(text: String, modifier: Modifier = Modifier, colour: Color = indexColors().faint) {
    Text(text.uppercase(Locale.getDefault()), modifier, style = INDEX_CAPS, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ------------------------------------------------------------------------------------------------------------ surfaces

/**
 * Where a row sits in a panel that a lazy list draws row by row: the first carries the panel's top corners, the last
 * its bottom ones. The panel is What's here's "NEAREST" one, which is one card round its rows; a long list that makes
 * its rows only as they scroll into view seats each with [panelSeat] and comes out the same.
 */
internal enum class PanelSeat(val top: Boolean, val bottom: Boolean) {
    ONLY(true, true), FIRST(true, false), MIDDLE(false, false), LAST(false, true);

    companion object {
        /** The seat of row [index] of [count]. */
        fun of(index: Int, count: Int): PanelSeat = of(index == 0, index >= count - 1)

        /** The seat of a row that starts ([top]) or ends ([bottom]) its panel. */
        fun of(top: Boolean, bottom: Boolean): PanelSeat = when {
            top && bottom -> ONLY
            top -> FIRST
            bottom -> LAST
            else -> MIDDLE
        }
    }
}

/** The corners of a panel: the kit's card's 22. */
internal val PANEL_RADIUS = 22.dp

/** How far a panel stands in from the sheet's edges. */
internal val PANEL_OUT = 16.dp

/** How far a panel's rows stand in from its edges: the "NEAREST" panel's 16. */
internal val PANEL_IN = 16.dp

/** A row's share of a panel of [colour]: in from the sheet's edges by [PANEL_OUT], round where its [seat] ends the panel. */
internal fun Modifier.panelSeat(seat: PanelSeat, colour: Color): Modifier {
    val top = if (seat.top) PANEL_RADIUS else 0.dp
    val bottom = if (seat.bottom) PANEL_RADIUS else 0.dp
    return padding(horizontal = PANEL_OUT).clip(RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)).background(colour)
}

/**
 * The ground a row's marks are cut out of (its status light's ring, the rim the signs behind a kind's tile go under):
 * the panel the row sits in, null on the sheet's own paper.
 */
internal val LocalGround = compositionLocalOf<Color?> { null }

/** The ground under a row's marks: its panel's, else the sheet's paper. */
@Composable
internal fun ground(): Color = LocalGround.current ?: indexColors().paper

/**
 * A panel of its own for what is no row of a list (a message, the rows still looked for), in from the sheet's edges
 * as the lists' panels are, its marks cut out of it.
 */
@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = indexColors()
    Box(modifier.fillMaxWidth().panelSeat(PanelSeat.ONLY, c.panel)) {
        CompositionLocalProvider(LocalGround provides c.panel, content = content)
    }
}

/** A raised card: [fill] on a soft shadow, with an edge where it meets the paper. */
internal fun Modifier.raised(shape: Shape, c: IndexColors, y: Dp = 4.dp, blur: Dp = 14.dp, fill: Color = c.card): Modifier =
    cssShadow(shape, y = y, blur = blur, colour = c.shadow)
        .cssShadow(shape, y = 0.5.dp, blur = 0.5.dp, colour = c.hair)
        .clip(shape)
        .background(fill)

/** A way onward not yet taken: only a dashed outline, the paper showing through. */
internal fun Modifier.dashed(c: IndexColors): Modifier = clip(CircleShape).drawBehind {
    val w = 1.3.dp.toPx()
    drawRoundRect(
        c.faint.copy(alpha = 0.75f), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(size.height / 2), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** A recessed tray: the tray's colour, its rim's shade along the top. */
internal fun Modifier.recessed(shape: Shape, c: IndexColors): Modifier = clip(shape).background(c.tray).drawBehind {
    val h = 9.dp.toPx()
    drawRect(Brush.verticalGradient(0f to c.shade, 1f to Color.Transparent, startY = 0f, endY = h), size = Size(size.width, h))
}

/** A brass bookmark hanging from the top edge: the filter is kept on in every search. */
internal fun Modifier.ribbon(colour: Color, on: Boolean): Modifier = if (!on) this else drawWithContent {
    drawContent()
    val x = 17.dp.toPx()
    val w = 7.dp.toPx()
    val h = 10.dp.toPx()
    val notch = 2.5.dp.toPx()
    drawPath(Path().apply { moveTo(x, 0f); lineTo(x + w, 0f); lineTo(x + w, h); lineTo(x + w / 2, h - notch); lineTo(x, h); close() }, colour)
}

// --------------------------------------------------------------------------------------------------------------- atoms

/**
 * A kind as the map draws its pins (a disc of its colour in a white ring, its symbol in white), so a kind in the sheet
 * is the thing on the map above it; [faded] when none is near: flat, in the tray.
 */
@Composable
internal fun KindPin(symbol: String, colour: Int, modifier: Modifier = Modifier, size: Dp = 52.dp, faded: Boolean = false) {
    val c = indexColors()
    val base = Color(colour)
    val surface = if (faded) {
        Modifier.clip(CircleShape).background(c.tray).border(1.dp, c.hair, CircleShape)
    } else {
        Modifier
            .cssShadow(CircleShape, y = size * 0.1f, blur = size * 0.26f, colour = Color(0x3814161B))
            .clip(CircleShape)
            .background(Color.White)
            // the pin's ring: 2 of its 30
            .padding(size / 15)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(lerp(base, Color.White, 0.1f), base, lerp(base, Color.Black, 0.12f))))
    }
    Box(modifier.size(size).then(surface), contentAlignment = Alignment.Center) {
        Symbol(symbol, tint = if (faded) base.copy(alpha = 0.6f) else Color.White, size = size * 0.44f)
    }
}

/**
 * A place's kind as the "NEAREST" rows of What's here show it ([RowTile]): a tile filled with its colour, its symbol in
 * white on it, as its pin on the map above; here with its status light. [faded] where it is closed, as its light says
 * it ([closedThere]): half way into the paper under it ([Palette.CLOSED_FADE]), as its pin is lighter on the map.
 */
@Composable
internal fun KindTile(
    symbol: String, colour: Int, tone: Tone?, modifier: Modifier = Modifier, size: Dp = ROW_TILE, faded: Boolean = tone == Tone.CLOSED,
) {
    Box(modifier.size(size)) {
        Box(Modifier.closed(faded)) { RowTile(symbol, Color(colour), size) }
        if (tone != null && tone != Tone.UNKNOWN) StatusLight(tone, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
    }
}

/** The side of a row's tile: the "NEAREST" row's 40. */
internal val ROW_TILE = 40.dp

/** A chain's place on its own sign, in the colours it has in the street, with its status light ([signOf]); [faded] as [KindTile]. */
@Composable
internal fun SignTile(brand: Brand, tone: Tone?, modifier: Modifier = Modifier, size: Dp = ROW_TILE, faded: Boolean = tone == Tone.CLOSED) {
    Box(modifier.size(size)) {
        BrandSign(brand, brand.name, size, Modifier.closed(faded), shape = RoundedCornerShape(size * 0.3f))
        if (tone != null && tone != Tone.UNKNOWN) StatusLight(tone, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
    }
}

/** A closed place's mark, where [faded]: half way into the paper under it, which shows through ([Palette.CLOSED_FADE]). */
private fun Modifier.closed(faded: Boolean): Modifier = if (faded) alpha(1f - Palette.CLOSED_FADE) else this

/** Open, closing soon, or closed (hollow), in a ring of the ground it sits on ([ground]). */
@Composable
internal fun StatusLight(tone: Tone, modifier: Modifier = Modifier, size: Dp = 13.dp) {
    val c = indexColors()
    val light = when (tone) {
        Tone.OPEN -> Modifier.background(c.open)
        Tone.SOON -> Modifier.background(c.soon)
        else -> Modifier.border(1.5.dp, c.faint, CircleShape)
    }
    Box(modifier.size(size).clip(CircleShape).background(ground()).padding(2.dp).clip(CircleShape).then(light))
}

/**
 * How long to get there, at a row's end: the minutes in heavy figures as the boards write them ("7′", hours past a
 * hundred minutes), said in words ("7 min").
 */
@Composable
internal fun MinutesFigure(minutes: Int, modifier: Modifier = Modifier, size: Float = 20f, colour: Color = indexColors().ink) {
    val said = spokenMinutes(minutes)
    Text(primeMinutes(minutes), modifier.semantics { contentDescription = said }, style = indexFigure(size, 800), color = colour, maxLines = 1)
}

/** Minutes as the boards write them ([app.atlas.ui.kit.walkMinutes]: "7′"); from a hundred on, their hours ("2 h"). */
@Composable
internal fun primeMinutes(minutes: Int): String =
    if (minutes < 100) "$minutes′" else "${(minutes + 30) / 60} ${stringResource(R.string.find_unit_h)}"

/** The same said aloud, where a prime is no word: "7 min". */
@Composable
internal fun spokenMinutes(minutes: Int): String =
    if (minutes < 100) "$minutes ${stringResource(R.string.find_unit_min)}" else "${(minutes + 30) / 60} ${stringResource(R.string.find_unit_h)}"

/** The minutes to the nearest as what a card says: "4′" alone, said "4 min"; from a hundred on, its hours ("2 H"). */
@Composable
internal fun minutesTell(minutes: Int): TellFigure =
    if (minutes < 100) TellFigure("$minutes′", "", said = spokenMinutes(minutes))
    else TellFigure(((minutes + 30) / 60).toString(), stringResource(R.string.find_unit_h))

/** How many are open as a card says it: those surely open, "≈7" where only guessed hours keep any open ([openTotal]). */
@Composable
internal fun openTell(open: Int, guessed: Int): TellFigure {
    val word = stringResource(R.string.find_word_open)
    val figure = openTotal(open, guessed)
    // "≈7" is said "about 7 open"
    val said = openTotalSaid(open, guessed).takeIf { it != figure }?.let { "$it $word" }
    return TellFigure(figure, word, said = said)
}

/** A figure as a number, which way it rolls when it changes: "≈7" as 7, "22:00" as 22. */
internal fun rolled(figure: String): Int = figure.trimStart('≈').takeWhile(Char::isDigit).toIntOrNull() ?: 0

/** A count in a ring filled to its share: how much a filter would keep of what is shown. */
@Composable
internal fun Gauge(count: Int, share: Float, modifier: Modifier = Modifier, onInk: Boolean = false) {
    val c = indexColors()
    val track = if (onInk) c.onInk.copy(alpha = 0.25f) else c.hair
    val fill = if (onInk) c.onInk else c.ink.copy(alpha = 0.6f)
    Box(
        modifier.size(24.dp).drawBehind {
            val w = 2.dp.toPx()
            drawCircle(track, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
            if (share > 0f) {
                drawArc(
                    fill, -90f, 360f * share.coerceIn(0f, 1f), useCenter = false,
                    topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = boardText(if (count >= 100) 8f else 9.5f, 700, 11f, tnum = true), color = if (onInk) c.onInk else c.ink, maxLines = 1)
    }
}

/**
 * A filter, a kind or a choice as a raised pill: its symbol, its words, and its count (in a [Gauge] when its [share]
 * is given); on, it is ink; a kept one wears the brass bookmark. A hold does [onLongClick] (keeps it). A [ghost] is a
 * word that could come next: only its dashed outline until it is chosen. A chosen word says how many places it
 * [hides] ("−5"), as a next word says how many it would keep.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IndexChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    count: Int? = null,
    share: Float? = null,
    selected: Boolean = false,
    kept: Boolean = false,
    faint: Boolean = false,
    ghost: Boolean = false,
    hides: Int? = null,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Checkbox,
    /** What a tap does, said to TalkBack ("Take it out"), where the role alone does not say it. */
    onClickLabel: String? = null,
) {
    val c = indexColors()
    val hidden = hides?.takeIf { it > 0 }
    val fg = when {
        selected -> c.onInk
        faint -> c.faint
        else -> c.ink
    }
    val surface = when {
        selected -> Modifier.cssShadow(CircleShape, y = 3.dp, blur = 8.dp, colour = c.shadow).clip(CircleShape).background(c.ink)
        ghost -> Modifier.dashed(c)
        else -> Modifier.raised(CircleShape, c, y = 1.dp, blur = 4.dp)
    }
    Row(
        modifier
            .height(38.dp)
            .then(surface)
            .ribbon(c.brass, kept)
            .combinedClickable(role = role, onClickLabel = onClickLabel, onLongClick = onLongClick, onClick = onClick)
            .padding(start = if (symbol != null) 11.dp else 14.dp, end = if ((share != null && count != null) || hidden != null) 7.dp else 13.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        symbol?.let { Symbol(it, tint = fg, size = 15.dp) }
        Text(label, style = boardText(13.5f, 550, 17f), color = fg, maxLines = 1)
        hidden?.let { n ->
            val said = stringResource(R.string.find_word_hides, n)
            Text(
                "−$n",
                Modifier.clip(CircleShape).background(fg.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp).semantics { contentDescription = said },
                style = boardText(11f, 700, 14f, tnum = true), color = fg.copy(alpha = 0.82f), maxLines = 1,
            )
        }
        when {
            count == null -> Unit
            share != null -> Gauge(count, share, onInk = selected)
            else -> Text(count.toString(), style = boardText(12f, 500, 15f, tnum = true), color = if (selected) fg.copy(alpha = 0.7f) else c.faint)
        }
    }
}

/** A switch of the grid as a pill: ink when on, a hairline when off. */
@Composable
internal fun IndexToggle(label: String, symbol: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = indexColors()
    val fg = if (on) c.onInk else c.soft
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .then(if (on) Modifier.background(c.ink) else Modifier.border(1.dp, c.hair, CircleShape))
            .toggleable(on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = fg, size = 14.dp)
        Text(label, style = boardText(12.5f, 550, 16f), color = fg, maxLines = 1)
    }
}

/** A round raised button with a symbol and no words (back, close). */
@Composable
internal fun IndexRound(symbol: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp, glyph: Dp = 18.dp) {
    val c = indexColors()
    Box(
        modifier.size(size).raised(CircleShape, c, y = 2.dp, blur = 6.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = c.ink, size = glyph) }
}

// --------------------------------------------------------------------------------------------------------- instruments

/**
 * The day's hours as bars of how many places are open in each, 24 from [from]: the filters' time picker. Now's bar is
 * in [accent], the hour asked about in ink; a tap or a drag along it picks an hour.
 */
@Composable
internal fun HourCurve(
    byHour: List<Int>,
    from: LocalDateTime,
    now: LocalDateTime,
    at: LocalDateTime?,
    accent: Color,
    onPick: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = indexColors()
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val labelStyle = boardText(9.5f, 550, 12f, tnum = true).copy(color = c.faint)
    var scrub by remember { mutableIntStateOf(-1) }
    val pick by rememberUpdatedState(onPick)
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    val nowIndex = if (now.isBefore(from)) null else Duration.between(from, now).toHours().toInt().takeIf { it in byHour.indices }
    val atIndex = at?.let { if (it.isBefore(from)) null else Duration.between(from, it).toHours().toInt() }?.takeIf { it in byHour.indices }
    fun index(x: Float, width: Int) = (x / width * byHour.size).toInt().coerceIn(0, byHour.lastIndex)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .pointerInput(byHour.size, from) { detectTapGestures { p -> pick(from.plusHours(index(p.x, size.width).toLong())) } }
            .pointerInput(byHour.size, from) {
                detectHorizontalDragGestures(
                    onDragStart = { p -> scrub = index(p.x, size.width) },
                    onDragEnd = {
                        if (scrub >= 0) pick(from.plusHours(scrub.toLong()))
                        scrub = -1
                    },
                    onDragCancel = { scrub = -1 },
                ) { change, _ -> scrub = index(change.position.x, size.width) }
            },
    ) {
        val labels = 16.dp.toPx()
        val area = size.height - labels
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (byHour.size - 1)) / byHour.size
        val chosen = if (scrub >= 0) scrub else atIndex
        for ((i, n) in byHour.withIndex()) {
            val h = (n.toFloat() / max * area).coerceAtLeast(2.dp.toPx())
            val colour = when (i) {
                chosen -> c.ink
                nowIndex -> accent
                else -> c.ink.copy(alpha = if (n == 0) 0.06f else 0.16f)
            }
            drawRoundRect(colour, topLeft = Offset(i * (w + gap), area - h), size = Size(w, h), cornerRadius = CornerRadius(w / 3))
        }
        for (i in byHour.indices step 6) {
            val label = measurer.measure(String.format(Locale.ROOT, "%02d", from.plusHours(i.toLong()).hour), labelStyle)
            drawText(label, topLeft = Offset(i * (w + gap), area + 4.dp.toPx()))
        }
    }
}
