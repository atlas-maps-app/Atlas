package app.atlas.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow

/**
 * The look of the mine boards (Settings, Privacy, Mine, Offline, Power, Weather, …): their cool or warm grey pages,
 * white cards of radius 22, a round white back button, an italic serif title and tiles of colour with a white symbol.
 * Surfaces and ink follow the theme, so the same screens read in the dark; the accent colours are the boards' own.
 */
internal object MineTone {
    /** [light] by day, [dark] in the dark theme. */
    @Composable
    fun of(light: Long, dark: Long): Color = if (Atlas.colors.isDark) Color(dark) else Color(light)

    val green = Color(0xFF1E9E5A)
    val greenDeep = Color(0xFF178A4E)
    val blue = Color(0xFF2E5BFF)
    val purple = Color(0xFF7B4DFF)
    val amber = Color(0xFFE8922A)
    val red = Color(0xFFC8322A)
    val steel = Color(0xFF6B7282)
}

/** Bricolage Grotesque, the title of the Mine board. */
internal val BricolageFamily = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.mine_bricolage, FontWeight.ExtraBold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(800))),
)

/** VT323, the pump's amber display. */
internal val Vt323Family = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.mine_vt323))

/** Caveat, the handwriting on the desk's notes (the eat package's copy of the font). */
internal val CaveatFamily = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.eat_caveat, FontWeight.Bold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700))),
)

/** Courier Prime, the ticket stubs' typewriter. */
internal val CourierFamily = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.eat_courier_prime, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(R.font.eat_courier_prime_bold, FontWeight.Bold),
)

/** The board's sans (Inter here, for Geist) at [size] and [weight]; [tabular] for figures that change. */
internal fun mt(size: Float, weight: Int = 600, tabular: Boolean = false, spacing: Float = 0f, line: Float = 0f): TextStyle = TextStyle(
    fontFamily = SansFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    letterSpacing = spacing.sp,
    lineHeight = if (line > 0f) line.sp else (size * 1.25f).sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

/** The italic serif title of the boards, at [size]. */
internal fun serifTitle(size: Float, italic: Boolean = true) = TextStyle(
    fontFamily = SerifFamily,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp,
    lineHeight = (size * 1.05f).sp,
)

/**
 * A mine board's page: the round back button and the serif title ([titleSize] 32–36 as the boards have it), an optional
 * pill [trailing] on the right, and the content in a column of 16 dp margins and 12 dp gaps that scrolls when it must.
 */
@Composable
internal fun BoardPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    background: Color = MineTone.of(0xFFF1F3F6, 0xFF101114),
    backgroundBrush: androidx.compose.ui.graphics.Brush? = null,
    ink: Color = Atlas.colors.ink,
    backFill: Color = Atlas.colors.card,
    italic: Boolean = true,
    titleSize: Float = 32f,
    gap: Dp = 12.dp,
    scroll: Boolean = true,
    header: Boolean = true,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    bottomPadding: Dp = 24.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(
            modifier
                .fillMaxSize()
                .then(if (backgroundBrush != null) Modifier.background(backgroundBrush) else Modifier.background(background))
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            if (header) Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    val back = stringResource(R.string.kit_back)
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(backFill).clickable(role = Role.Button, onClick = onBack)
                            .semantics { contentDescription = back },
                        contentAlignment = Alignment.Center,
                    ) { Symbol("ui-chevron-left", tint = ink, size = 20.dp) }
                }
                Text(
                    title,
                    Modifier.weight(1f).semantics { heading() },
                    style = serifTitle(titleSize, italic),
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                trailing?.invoke(this)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                content()
                Spacer(Modifier.height(bottomPadding))
            }
        }
    }
}

/** A white card of the boards: radius 22, [padding] inside; the theme's card colour by night. */
@Composable
internal fun BoardCard(
    modifier: Modifier = Modifier,
    colour: Color = Atlas.colors.card,
    shape: Shape = RoundedCornerShape(22.dp),
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colour)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** The small caps heading over a card: "KEPT HERE", "ON YOUR TRAIL" (11 sp, 800, spaced 0.12 em). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, colour: Color = Atlas.colors.inkSoft) {
    Text(text.uppercase(), modifier.semantics { heading() }, style = mt(11f, 800, spacing = 1.3f), color = colour)
}

/**
 * One row of a settings card: a tile of colour ([symbol] in white) 34 dp square with radius 10, the [title] (14 sp, 800),
 * what it is set to ([value]) and a chevron when it opens something; a hairline above except on the [first] row.
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
    first: Boolean = false,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Atlas.colors
    androidx.compose.runtime.key(title, symbol) { SettingRowBody(title, modifier, symbol, tile, tileSymbol, value, subtitle, first, chevron, onClick, trailing) }
}

@Composable
private fun SettingRowBody(
    title: String,
    modifier: Modifier,
    symbol: String?,
    tile: Color,
    tileSymbol: Color,
    value: String?,
    subtitle: String?,
    first: Boolean,
    chevron: Boolean,
    onClick: (() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    val c = Atlas.colors
    Column(modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (symbol != null) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) {
                    Symbol(symbol, tint = tileSymbol, size = 18.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = mt(14f, 800), color = c.ink)
                if (subtitle != null) Text(subtitle, style = mt(11f, 500, line = 15f), color = c.inkSoft)
            }
            if (value != null) Text(value, style = mt(13f, 500, tabular = true), color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            trailing?.invoke(this)
            if (onClick != null && chevron) Symbol("ui-chevron-right", tint = c.inkFaint, size = 16.dp)
        }
    }
}

/** The board's switch: 44 × 26, a 20 dp knob, green when on ([on] colour), grey when off. */
@Composable
internal fun BoardSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onColour: Color = MineTone.green,
    enabled: Boolean = true,
) {
    val track = if (checked) onColour else MineTone.of(0xFFD5D8DE, 0xFF3A3E46)
    Box(
        modifier
            .size(44.dp, 26.dp)
            .clip(CircleShape)
            .background(track)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "on" else "off"
            },
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp)
                .size(20.dp)
                .cssShadow(CircleShape, 1.dp, 3.dp, Color(0x40000000))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** A small pill of text ("ODbL", "open data", "LIVE"): [fg] on [bg], 11 sp 800. */
@Composable
internal fun TagPill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, height: Dp = 22.dp) {
    Box(modifier.height(height).clip(CircleShape).background(bg).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = mt(10.5f, 800), color = fg, maxLines = 1)
    }
}

/** A text pill button (28 dp high, 11 sp 800): dark by default, light when [primary] is false. */
@Composable
internal fun MiniButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, symbol: String? = null) {
    val c = Atlas.colors
    val bg = if (primary) c.pill else MineTone.of(0xFFEEF0F4, 0xFF2A2D34)
    val fg = if (primary) c.onPill else c.ink
    Row(
        modifier.height(28.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) Symbol(symbol, tint = fg, size = 14.dp)
        Text(text, style = mt(11f, 800), color = fg, maxLines = 1)
    }
}

/** A hairline-bordered outline for dashed or empty states. */
internal fun Modifier.outline(colour: Color, shape: Shape, width: Dp = 1.dp): Modifier = this.border(width, colour, shape)
