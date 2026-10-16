package app.atlas.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.nav.LocalServices
import app.atlas.places.Icon
import app.atlas.R
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.drawIcon
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.hasSymbol

/** Draws the glyph [id] into a [side] square at [topLeft]: one of the boards' symbols, else one of this package's drawings. */
internal fun DrawScope.drawGlyph(id: String, topLeft: Offset, side: Float, tint: Color, art: Map<String, Icon>) {
    if (hasSymbol(id)) drawSymbol(id, topLeft, side, tint) else art[id]?.let { drawIcon(it, topLeft, side, tint) }
}

/** A glyph of a food or a board drawing, as a composable: the symbol catalogue first, else the local drawings. */
@Composable
internal fun Glyph(id: String, tint: Color, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val art = LocalServices.current.local.art
    if (hasSymbol(id)) {
        Symbol(id, modifier, tint = tint, size = size, contentDescription = contentDescription)
    } else {
        val described = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
        Canvas(described.size(size)) { drawGlyph(id, Offset.Zero, this.size.minDimension, tint, art) }
    }
}

/** The round back button of the boards' header: 44 px, the board's 20 px back arrow. */
@Composable
internal fun BackCircle(onBack: () -> Unit, modifier: Modifier = Modifier, background: Color = Color.White, tint: Color = Color(0xFF1C2433), label: String = stringResource(R.string.kit_back), shadows: List<app.atlas.ui.kit.CssShadow> = emptyList(), blur: Float = 0f) {
    Box(
        modifier.size(44.dp).boardBox(background.css(), corners(22f), shadows, blur = blur).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Symbol("ui.back", tint = tint, size = 20.dp) }
}

/**
 * The header the local boards share, its row at y 42 (the board's top 40 px are the status bar): the 44 px back
 * button, the Instrument Serif title 10 px after it at line height 1 (italic for moods), and something at the right.
 */
@Composable
internal fun BoardTitleBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    colour: Color = Color(0xFF1C2433),
    backBackground: Color = Color.White,
    italic: Boolean = true,
    size: Int = 34,
    backTint: Color? = null,
    shadow: app.atlas.ui.kit.CssShadow? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(top = 42.dp).height(44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BackCircle(onBack, background = backBackground, tint = backTint ?: if (backBackground.luminance() > 0.5f) Color(0xFF1C2433) else colour)
        BoardLine(title, boardStyle(size.toFloat(), 400, colour.css(), size.toFloat(), SERIF, italic, shadow = shadow), Modifier.weight(1f).semantics { heading() })
        trailing?.invoke(this)
    }
}

/** The white pill at the right of a header ("Veloria", "food →"): 34 px high, 12 px in, Geist at 800 ([size] 13 or 12). */
@Composable
internal fun HeaderPill(text: String, modifier: Modifier = Modifier, ink: Color = Color(0xFF1C2433), background: Color = Color.White, size: Float = 13f, lineHeight: Float = 17f, onClick: (() -> Unit)? = null) {
    Box(
        modifier.height(34.dp).boardBox(background.css(), corners(17f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { BoardLine(text, boardStyle(size, 800, ink.css(), lineHeight)) }
}

/**
 * A scrolling page on a board's own background, laid out in board px from the board's top (its first 40 px are the
 * status bar; a taller one moves the page down), 16 px in from the sides, with a [bottom] bar pinned 28 px above the
 * foot (a call to action) and the content's last line ending above it.
 */
@Composable
internal fun BoardPage(
    background: Color,
    modifier: Modifier = Modifier,
    bottom: (@Composable () -> Unit)? = null,
    bottomRoom: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(background)) {
        Column(
            Modifier.fillMaxSize().padding(top = boardShift()).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            content = {
                content()
                androidx.compose.foundation.layout.Spacer(Modifier.height(bottomRoom + 24.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(0.dp))
            },
        )
        if (bottom != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) { bottom() }
    }
}

/** The letter-spaced capitals that head a group ("BEST NOW"): Geist at 800, [spacing] em apart, in a line box of [lineHeight]. */
@Composable
internal fun SmallCaps(text: String, colour: Color, modifier: Modifier = Modifier, size: Int = 12, spacing: Float = 0.12f, lineHeight: Float = 0f) {
    val lh = if (lineHeight > 0f) lineHeight else if (size >= 12) size * 1.2917f else size * 1.2727f
    BoardLine(text.uppercase(), boardStyle(size.toFloat(), 800, colour.css(), lh, spacing = size * spacing), modifier)
}

/** The board's dotted paper: CSS `radial-gradient(circle, rgba(90,70,40,.16) 1px, transparent 1.3px)` every 16 px from the corner. */
internal fun Modifier.dotGrid(): Modifier = this.drawWithContent {
    val step = 16.dp.toPx()
    val dot = Color(0x29 shl 24 or 0x5A4628)
    val r = 1.15.dp.toPx()
    var y = 0f
    while (y < size.height + r) {
        var x = 0f
        while (x < size.width + r) { drawCircle(dot, r, Offset(x, y)); x += step }
        y += step
    }
    drawContent()
}
