package app.atlas.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.BoardFonts
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily

/**
 * The colours of the mine boards (Settings, Privacy, Mine, Offline, Power, Weather, …): their grey pages, white cards and
 * the accents the boards give their tiles and switches. The boards are light only, and so are these screens.
 */
internal object MineTone {
    /** The board's colour ([light]); the boards have no dark designs, so [dark] is not drawn. */
    @Suppress("UNUSED_PARAMETER")
    fun of(light: Long, dark: Long): Color = Color(light)

    val green = Color(0xFF1E9E5A)
    val greenDeep = Color(0xFF178A4E)
    val blue = Color(0xFF2E5BFF)
    val purple = Color(0xFF7B4DFF)
    val amber = Color(0xFFE8922A)
    val red = Color(0xFFC8322A)
    val steel = Color(0xFF6B7282)
}

/** A colour as the boards' ARGB literal. */
internal fun Color.argbLong(): Argb = toArgb().toLong() and 0xFFFFFFFFL

/** Bricolage Grotesque, the title of the Mine board. */
internal val BricolageFamily: FontFamily get() = BoardFonts.Bricolage

/** VT323, the pump's amber display. */
internal val Vt323Family: FontFamily get() = BoardFonts.VT323

/** Caveat, the handwriting on the desk's notes. */
internal val CaveatFamily: FontFamily get() = BoardFonts.Caveat

/** Courier Prime, the ticket stubs' typewriter. */
internal val CourierFamily: FontFamily get() = BoardFonts.CourierPrime

/** The board's sans (Geist) at [size] and [weight]; [tabular] for figures that change. */
internal fun mt(size: Float, weight: Int = 600, tabular: Boolean = false, spacing: Float = 0f, line: Float = 0f): TextStyle = TextStyle(
    fontFamily = SansFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    letterSpacing = spacing.sp,
    lineHeight = if (line > 0f) line.sp else (size * 1.25f).sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

/** The italic serif title of the boards, at [size] on a line of its size (`line-height: 1`). */
internal fun serifTitle(size: Float, italic: Boolean = true) = TextStyle(
    fontFamily = SerifFamily,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp,
    lineHeight = size.sp,
)

/**
 * A mine board's page: the title row of the grey boards ([TitleRow]: the round back button at y 42 and the serif title,
 * [titleSize] as the board has it, an optional [trailing] at the right), then the content [top] px under it (the boards'
 * first card is at 98) in a column of 16 px margins and [gap] between cards, which scrolls when it must. Without a
 * [header] the content starts at the top of the screen and draws its own.
 */
@Composable
internal fun BoardPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    background: Color = Color(PAGE),
    backgroundBrush: androidx.compose.ui.graphics.Brush? = null,
    ink: Color = Color(INK),
    @Suppress("UNUSED_PARAMETER") backFill: Color = Color.White,
    italic: Boolean = true,
    titleSize: Float = 32f,
    gap: Dp = 12.dp,
    top: Dp = 12.dp,
    scroll: Boolean = true,
    header: Boolean = true,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    bottomPadding: Dp = 24.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(
            modifier
                .fillMaxSize()
                .then(if (backgroundBrush != null) Modifier.background(backgroundBrush) else Modifier.background(background)),
        ) {
            if (header) TitleRow(title, onBack, ink = ink.argbLong(), size = titleSize, italic = italic) { trailing?.invoke(this) }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val room = maxHeight
                Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier)) {
                    // the page is at least the screen's height, so a bottom bar sits at the foot when the content is short
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = room).padding(start = 16.dp, end = 16.dp, top = if (header) top else 0.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), content = content)
                        if (bottomBar != null) {
                            // the board's buttons: 54 px, 8 px apart, 30 px from the foot
                            Row(
                                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = foot(30f)),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                content = bottomBar,
                            )
                        } else Spacer(Modifier.height(foot(bottomPadding.value)))
                    }
                }
            }
        }
    }
}

/** A white card of the boards: radius 22, [padding] inside (the rows' cards: 2 px above and below, 14 at the sides). */
@Composable
internal fun BoardCard(
    modifier: Modifier = Modifier,
    colour: Color = Color.White,
    radius: Float = 22f,
    shadows: List<CssShadow> = emptyList(),
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .bbox(colour.argbLong(), cr(radius), shadows)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** The small caps heading of a card: "KEPT HERE", "WHAT GOES ONLINE" (11 px, 800, 1.32 px apart, on a 14 px line). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, colour: Color = Color(LABEL)) {
    CardLabel(text, modifier, colour.argbLong())
}

/**
 * One row of a settings card, as the Settings board draws it: a 1 px hairline on top, [padV] above and below, a tile of
 * colour ([symbol] 18 px in [tileSymbol]) 34 px square with radius 10, 12 px on the [title] (14, 800, on 18) and its
 * [subtitle] (11 on 14, 1 px under), what it is set to ([value]: 13 on 17, grey, right) and a 16 px chevron 12 px after
 * when it opens something.
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    tile: Color = MineTone.steel,
    tileSymbol: Color = Color.White,
    value: String? = null,
    subtitle: String? = null,
    @Suppress("UNUSED_PARAMETER") first: Boolean = false,
    chevron: Boolean = true,
    padV: Float = 10f,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .bbox(border = hairTop())
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(top = (padV + 1f).dp, bottom = padV.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) {
            Box(Modifier.size(34.dp).bbox(tile.argbLong(), cr(10f)), contentAlignment = Alignment.Center) {
                MSy(symbol, 18f, tileSymbol.argbLong())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BText(title, bt(14f, 800, 18f), INK)
            if (subtitle != null) BText(subtitle, bt(11f, 400, 14f), SOFT, lines = 2)
        }
        if (value != null) BText(value, bt(13f, 400, 17f, tnum = true), SOFT, Modifier.widthIn(max = 180.dp))
        trailing?.invoke(this)
        if (onClick != null && chevron) MSy("ui.chevron-right", 16f, FAINT)
    }
}

/** The board's switch: 44 × 26, a 20 px knob, [onColour] when on, grey when off. */
@Composable
internal fun BoardSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onColour: Color = MineTone.green,
    enabled: Boolean = true,
) {
    MineSwitch(checked, onChange, label, modifier, onColour = onColour.argbLong(), enabled = enabled)
}

/** A small tag of text ("ODbL", "open data"): [fg] on [bg], 10 px 700 on 13, radius 8, 7 px at the sides. */
@Composable
internal fun TagPill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, height: Dp = 19.dp) {
    Box(modifier.height(height).bbox(bg.argbLong(), cr(8f)).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        BText(text, bt(10f, 700, 13f), fg.argbLong())
    }
}

/** The boards' text button (28 high, radius 14, 11 px 800 on 14, 10 px at the sides): dark, or light when not [primary]. */
@Composable
internal fun MiniButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, symbol: String? = null) {
    TextPill(
        text, bt(11f, 800, 14f), if (primary) INK else HAIR, if (primary) WHITE else INK, modifier,
        symbol = symbol, symbolSize = 14f, onClick = onClick,
    )
}

/** A hairline-bordered outline for dashed or empty states. */
internal fun Modifier.outline(colour: Color, shape: Shape, width: Dp = 1.dp): Modifier = this.border(width, colour, shape)
