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
import app.atlas.ui.kit.InterFamily
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

/** The round white back button of the boards' header. */
@Composable
internal fun BackCircle(onBack: () -> Unit, modifier: Modifier = Modifier, background: Color = Color.White, tint: Color = Color(0xFF1C2433), label: String = stringResource(R.string.kit_back)) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(background).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Symbol("ui.back", tint = tint, size = 20.dp) }
}

/** The header the local boards share: back, a serif title (italic for moods), and something at the right. */
@Composable
internal fun BoardTitleBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    colour: Color = Color(0xFF1C2433),
    backBackground: Color = Color.White,
    italic: Boolean = true,
    size: Int = 34,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BackCircle(onBack, background = backBackground, tint = if (backBackground.luminance() > 0.5f) Color(0xFF1C2433) else colour)
        Text(
            title, Modifier.weight(1f).semantics { heading() },
            style = TextStyle(fontFamily = SerifFamily, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, fontSize = size.sp, lineHeight = size.sp, color = colour),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

/** A white pill with a short bold word, at the right of a header ("Veloria", "food →"). */
@Composable
internal fun HeaderPill(text: String, modifier: Modifier = Modifier, ink: Color = Color(0xFF1C2433), onClick: (() -> Unit)? = null) {
    Box(
        modifier.height(34.dp).clip(CircleShape).background(Color.White)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
}

/**
 * A scrolling page on a board's own background, with the status bar and the system navigation bar kept clear and a
 * [bottom] bar pinned under the content (a call to action), the content's last line ending above it.
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
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            content = {
                content()
                androidx.compose.foundation.layout.Spacer(Modifier.height(bottomRoom + 24.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(0.dp))
            },
        )
        if (bottom != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) { bottom() }
    }
}

/** The letter-spaced small caps line that heads a group ("BEST NOW"). */
@Composable
internal fun SmallCaps(text: String, colour: Color, modifier: Modifier = Modifier, size: Int = 12) {
    Text(text.uppercase(), modifier, color = colour, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (size * 0.12f).sp, fontFamily = InterFamily)
}

/** The board's dotted paper: a dot every 16 px, 1 px across, in warm brown at 16 per cent. */
internal fun Modifier.dotGrid(): Modifier = this.drawWithContent {
    val step = 16.dp.toPx()
    val dot = Color(0x29 shl 24 or 0x5A4628)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(dot, 0.65.dp.toPx(), Offset(x, y)); x += step }
        y += step
    }
    drawContent()
}
