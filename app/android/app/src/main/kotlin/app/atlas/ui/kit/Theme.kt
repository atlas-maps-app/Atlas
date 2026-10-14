package app.atlas.ui.kit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.R

/**
 * Atlas's colours. Warm paper and near-black ink by day; [go] blue for routes and the primary action, [open] green for
 * open and charging, [alert] red, [caution] amber, [eat] orange-red, [ask] purple for suggestions and questions.
 * Night (navigation after dark) is black with a lime [ring].
 */
@Immutable
data class AtlasColors(
    val paper: Color,
    val paperDeep: Color,
    val card: Color,
    val cardMuted: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val line: Color,
    val go: Color,
    val goSoft: Color,
    val open: Color,
    val openSoft: Color,
    val alert: Color,
    val alertSoft: Color,
    val caution: Color,
    val eat: Color,
    val ask: Color,
    val askSoft: Color,
    /** The near-black pill of secondary actions and selected chips, and what is drawn on it. */
    val pill: Color,
    val onPill: Color,
    val ring: Color,
    val shadow: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightColors = AtlasColors(
    paper = Color(0xFFF2EEE6),
    paperDeep = Color(0xFFE9E3D8),
    card = Color(0xFFFFFFFF),
    cardMuted = Color(0xFFF4F1EA),
    ink = Color(0xFF14161B),
    inkSoft = Color(0xFF5D636E),
    inkFaint = Color(0xFF9EA3AC),
    line = Color(0xFFE7E2D8),
    go = Color(0xFF2E5BFF),
    goSoft = Color(0xFFE6ECFF),
    open = Color(0xFF1F9D55),
    openSoft = Color(0xFFE3F3E9),
    alert = Color(0xFFD23C3C),
    alertSoft = Color(0xFFFBE7E5),
    caution = Color(0xFFE29A12),
    eat = Color(0xFFE0592A),
    ask = Color(0xFF7B4FD6),
    askSoft = Color(0xFFEEE8FB),
    pill = Color(0xFF14161B),
    onPill = Color(0xFFFFFFFF),
    ring = Color(0xFF2E5BFF),
    shadow = Color(0x2E3A2E1A),
    scrim = Color(0x6614161B),
    isDark = false,
)

val DarkColors = AtlasColors(
    paper = Color(0xFF101114),
    paperDeep = Color(0xFF08090B),
    card = Color(0xFF1B1D22),
    cardMuted = Color(0xFF24272D),
    ink = Color(0xFFF2EEE6),
    inkSoft = Color(0xFFAEB2BA),
    inkFaint = Color(0xFF6C717B),
    line = Color(0xFF2C2F36),
    go = Color(0xFF5A7DFF),
    goSoft = Color(0xFF1D2547),
    open = Color(0xFF3CC47A),
    openSoft = Color(0xFF15301F),
    alert = Color(0xFFF0605A),
    alertSoft = Color(0xFF3A1C1B),
    caution = Color(0xFFF2B33D),
    eat = Color(0xFFF07A4A),
    ask = Color(0xFFA585F0),
    askSoft = Color(0xFF2A2140),
    pill = Color(0xFFF2EEE6),
    onPill = Color(0xFF14161B),
    ring = Color(0xFF5A7DFF),
    shadow = Color(0x66000000),
    scrim = Color(0x99000000),
    isDark = true,
)

/** Navigation after dark: OLED black, the route and progress in lime, as on the Ride board. */
val NightColors = DarkColors.copy(
    paper = Color(0xFF000000),
    paperDeep = Color(0xFF000000),
    card = Color(0xFF141414),
    cardMuted = Color(0xFF1E1E1E),
    go = Color(0xFFB4F05A),
    goSoft = Color(0xFF233214),
    ring = Color(0xFFB4F05A),
)

/** Inter for the interface, from one variable font file. */
val InterFamily = FontFamily(
    interFont(FontWeight.Normal),
    interFont(FontWeight.Medium),
    interFont(FontWeight.SemiBold),
    interFont(FontWeight.Bold),
    interFont(FontWeight.ExtraBold),
    interFont(FontWeight.Black),
)

private fun interFont(weight: FontWeight) =
    Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Instrument Serif for display titles: upright for nouns, italic for moods ("Ears only"). */
val SerifFamily = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** The type scale: serif display titles, a heavy grotesque for names and numbers, small caps kickers. */
@Immutable
data class AtlasType(
    val display: TextStyle,
    val displayItalic: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val name: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val kicker: TextStyle,
    val numeral: TextStyle,
    val numeralSmall: TextStyle,
    val mono: TextStyle,
)

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

val Type = AtlasType(
    display = TextStyle(fontFamily = SerifFamily, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-0.01).em),
    displayItalic = TextStyle(fontFamily = SerifFamily, fontStyle = FontStyle.Italic, fontSize = 42.sp, lineHeight = 46.sp),
    title = TextStyle(fontFamily = SerifFamily, fontSize = 30.sp, lineHeight = 34.sp),
    headline = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    name = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em),
    body = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyStrong = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    label = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp, lineHeightStyle = trim),
    caption = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    kicker = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em),
    numeral = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em),
    numeralSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

val LocalAtlasColors = staticCompositionLocalOf { LightColors }
val LocalAtlasType = staticCompositionLocalOf { Type }

/** The current theme's colours and type, as `Atlas.colors.go` and `Atlas.type.display`. */
object Atlas {
    val colors: AtlasColors
        @Composable @ReadOnlyComposable get() = LocalAtlasColors.current
    val type: AtlasType
        @Composable @ReadOnlyComposable get() = LocalAtlasType.current
}

/**
 * Atlas's look. It follows the system's dark setting; [night] switches to the black-and-lime look of navigation after
 * dark. Material components inside get a matching colour scheme, so a stray Material widget does not look foreign.
 */
@Composable
fun AtlasTheme(dark: Boolean = isSystemInDarkTheme(), night: Boolean = false, content: @Composable () -> Unit) {
    val colors = when {
        night -> NightColors
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalAtlasColors provides colors,
        LocalAtlasType provides Type,
    ) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = materialTypography) {
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

private val materialTypography = Typography(
    displayLarge = Type.display, displayMedium = Type.display, displaySmall = Type.title,
    headlineLarge = Type.title, headlineMedium = Type.headline, headlineSmall = Type.headline,
    titleLarge = Type.headline, titleMedium = Type.name, titleSmall = Type.bodyStrong,
    bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.caption,
    labelLarge = Type.label, labelMedium = Type.label, labelSmall = Type.caption,
)

private fun AtlasColors.toMaterial(): ColorScheme =
    if (isDark) darkColorScheme(
        primary = go, onPrimary = Color.Black, secondary = ink, onSecondary = paper, background = paper, onBackground = ink,
        surface = card, onSurface = ink, surfaceVariant = cardMuted, onSurfaceVariant = inkSoft, outline = line, error = alert,
    ) else lightColorScheme(
        primary = go, onPrimary = Color.White, secondary = ink, onSecondary = paper, background = paper, onBackground = ink,
        surface = card, onSurface = ink, surfaceVariant = cardMuted, onSurfaceVariant = inkSoft, outline = line, error = alert,
    )

/**
 * True when it is dark at [at] at [now]: the sun more than 6° below the horizon (past civil dusk). Navigation switches
 * to [NightColors] then, whatever the system theme.
 */
fun isAfterDark(at: app.atlas.geo.LatLon, now: java.time.Instant = java.time.Instant.now()): Boolean =
    app.atlas.geo.Sun.position(now, at).altitudeDeg < -6.0
