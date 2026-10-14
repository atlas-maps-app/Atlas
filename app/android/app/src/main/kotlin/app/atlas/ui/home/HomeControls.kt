package app.atlas.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.mapview.Follow
import app.atlas.places.Categories
import app.atlas.places.PathSink
import app.atlas.places.SvgPath
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.CurrentWeather
import app.atlas.ui.kit.InterFamily
import app.atlas.ui.kit.MainSymbols
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import kotlin.math.roundToInt

/** Whether the area on screen works without a connection. */
enum class OfflineState(val symbol: String) {
    /** A downloaded region covers it. */
    READY("main.cloud-check"),

    /** It comes from the network, which is there now. */
    ONLINE("ui-cloud"),

    /** No region and no connection: only what is cached. */
    NONE("ui-cloud-off"),
}

/**
 * The Main board's look for the home's overlays, from `Main.dc.html`: frosted white pills (white at 80–86 % over the
 * map) with a cool shadow, ink #14161B, the sun's #B8461A and the offline #178A4E; in the dark the same shapes in the
 * theme's card and ink.
 */
internal class HomeLook(
    val ink: Color,
    val frost: Color,
    val frostStrong: Color,
    val solid: Color,
    val shadow: Color,
    val shadowStrong: Color,
    val line: Color,
    val railLine: Color,
    val sun: Color,
    val sky: Color,
    val offline: Color,
    val hint: Color,
    val nearBg: Color,
    val scrim: Color,
    val attribution: Color,
    val halo: Color,
) {
    companion object {
        val Light = HomeLook(
            ink = Color(0xFF14161B),
            frost = Color.White.copy(alpha = 0.8f),
            frostStrong = Color.White.copy(alpha = 0.86f),
            solid = Color.White,
            shadow = Color(24, 28, 48).copy(alpha = 0.12f),
            shadowStrong = Color(24, 28, 48).copy(alpha = 0.16f),
            line = Color(20, 22, 27).copy(alpha = 0.14f),
            railLine = Color(20, 22, 27).copy(alpha = 0.08f),
            sun = Color(0xFFB8461A),
            sky = Color(0xFF4A505E),
            offline = Color(0xFF178A4E),
            hint = Color(0xFFA9A9A9),
            nearBg = Color(0xFFF1EEE8),
            scrim = Color(239, 229, 218),
            attribution = Color(20, 22, 27).copy(alpha = 0.6f),
            halo = Color.White,
        )
    }
}

/** The home's look for the current theme. */
@Composable
internal fun homeLook(): HomeLook {
    val c = Atlas.colors
    return if (!c.isDark) HomeLook.Light else remember(c) {
        HomeLook(
            ink = c.ink,
            frost = c.card.copy(alpha = 0.86f),
            frostStrong = c.card.copy(alpha = 0.9f),
            solid = c.card,
            shadow = Color.Black.copy(alpha = 0.35f),
            shadowStrong = Color.Black.copy(alpha = 0.4f),
            line = c.ink.copy(alpha = 0.16f),
            railLine = c.ink.copy(alpha = 0.1f),
            sun = Color(0xFFE07A4A),
            sky = c.inkSoft,
            offline = c.open,
            hint = c.inkFaint,
            nearBg = c.cardMuted,
            scrim = c.paper,
            attribution = c.inkSoft,
            halo = Color.Black,
        )
    }
}

/** Tabular figures, as the board's `font-variant-numeric: tabular-nums`. */
private const val TNUM = "tnum"

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** The board's sans at [size] and [weight], tabular, its line box trimmed so rows centre it as CSS does. */
internal fun boardText(size: Float, weight: FontWeight, numbers: Boolean = false) = TextStyle(
    fontFamily = InterFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.2f).sp,
    lineHeightStyle = tight,
    fontFeatureSettings = if (numbers) TNUM else null,
)

/** The white pill at the top left: the sky and the temperature, then whether this area works offline (44 dp high). */
@Composable
fun WeatherChip(weather: CurrentWeather?, offline: OfflineState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = homeLook()
    val now = weather
    val statusText = stringResource(
        when (offline) {
            OfflineState.READY -> R.string.home_offline_ready
            OfflineState.ONLINE -> R.string.home_online_only
            OfflineState.NONE -> R.string.home_offline_now
        },
    )
    val description = if (now != null) stringResource(R.string.home_weather, "${now.temperature.roundToInt()}°", statusText) else statusText
    Row(
        modifier
            .height(44.dp)
            .cssShadow(CircleShape, y = 6.dp, blur = 24.dp, colour = look.shadow)
            .clip(CircleShape)
            .background(look.frost)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (now != null) {
            val symbol = boardSky(now.symbol)
            Symbol(symbol, tint = if (symbol == "main.sun") look.sun else look.sky, size = 20.dp, weight = MainSymbols.Width.PILL)
            Text("${now.temperature.roundToInt()}°", style = boardText(15f, FontWeight.SemiBold, numbers = true), color = look.ink)
            Box(Modifier.width(1.dp).height(18.dp).background(look.line))
        }
        Symbol(
            offline.symbol,
            tint = when (offline) {
                OfflineState.READY -> look.offline
                OfflineState.ONLINE -> look.sky
                OfflineState.NONE -> Atlas.colors.alert
            },
            size = 20.dp,
            weight = MainSymbols.Width.PILL,
        )
    }
}

/** The board draws the clear sky with its own sun; other skies keep their symbols. */
private fun boardSky(symbol: String): String = if (symbol == "ui-sun") "main.sun" else symbol

/** The dark "✦ 3" pill: what is coming up (36 dp high). */
@Composable
fun MomentsChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.home_moments, count)
    val dark = Atlas.colors.isDark
    Row(
        modifier
            .height(36.dp)
            .cssShadow(CircleShape, y = 6.dp, blur = 18.dp, colour = Color(20, 22, 27).copy(alpha = if (dark) 0.5f else 0.25f))
            .clip(CircleShape)
            .background(if (dark) Color(0xFF2A2D35) else MOMENTS_BG)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol("main.sparkles", tint = MOMENTS_STAR, size = 18.dp)
        Text(count.toString(), style = boardText(14f, FontWeight.Bold, numbers = true), color = Color.White)
    }
}

private val MOMENTS_BG = Color(0xFF14161B)
private val MOMENTS_STAR = Color(0xFFFFE7A3)

private val NEED_WATER = Color(0xFF1560A8)
private val NEED_FOOD = Color(0xFFA8420E)
private val NEED_TOILET = Color(0xFF4B34B8)

/**
 * The quick needs: water, food, a toilet, each one tap away. The board's 26 dp discs, 4 dp apart in a 44 dp pill; each
 * one's touch area runs the pill's full height and halfway into the gaps.
 */
@Composable
fun NeedsPill(onWater: () -> Unit, onFood: () -> Unit, onToilet: () -> Unit, modifier: Modifier = Modifier) {
    val look = homeLook()
    Row(
        modifier
            .height(44.dp)
            .cssShadow(CircleShape, y = 6.dp, blur = 24.dp, colour = look.shadow)
            .clip(CircleShape)
            .background(look.frostStrong),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedButton(NEED_WATER, stringResource(R.string.home_need_water), onWater, start = 10.dp, end = 2.dp) {
            Symbol("main.drop", tint = Color.White, size = 14.dp)
        }
        NeedButton(NEED_FOOD, stringResource(R.string.home_need_food), onFood, start = 2.dp, end = 2.dp) {
            Symbol("main.bread", tint = Color.White, size = 15.dp)
        }
        NeedButton(NEED_TOILET, stringResource(R.string.home_need_toilet), onToilet, start = 2.dp, end = 10.dp) {
            Text(stringResource(R.string.home_wc), style = boardText(9f, FontWeight.ExtraBold), color = Color.White)
        }
    }
}

@Composable
private fun NeedButton(colour: Color, description: String, onClick: () -> Unit, start: Dp, end: Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = start, end = end),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The right-hand rail, 48 dp wide: the compass (north up) 52 dp high, then 3D, layers, locate and follow, and the globe,
 * 46 dp each under a hairline.
 */
@Composable
fun ControlColumn(
    bearing: Double,
    is3d: Boolean,
    follow: Follow,
    onCompass: () -> Unit,
    on3d: () -> Unit,
    onLayers: () -> Unit,
    onLocate: () -> Unit,
    onGlobe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = homeLook()
    val c = Atlas.colors
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .width(48.dp)
            .cssShadow(shape, y = 6.dp, blur = 24.dp, colour = look.shadow)
            .clip(shape)
            .background(look.frost),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val compass = stringResource(R.string.home_compass)
        Box(
            Modifier.size(48.dp, 52.dp).clickable(role = Role.Button, onClickLabel = compass, onClick = onCompass).semantics { contentDescription = compass },
            contentAlignment = Alignment.Center,
        ) {
            NorthDial(bearing.toFloat())
        }
        ControlButton("main.cube", stringResource(if (is3d) R.string.home_3d_on else R.string.home_3d_off), on3d, tint = if (is3d) c.go else look.ink, selected = is3d, size = 21.dp)
        ControlButton("main.layers", stringResource(R.string.home_layers), onLayers, tint = look.ink, size = 21.dp)
        val following = follow != Follow.NONE
        ControlButton(
            if (follow == Follow.HEADING) "main.locate.on" else "main.locate",
            stringResource(if (follow == Follow.POSITION) R.string.home_follow else R.string.home_locate),
            onLocate,
            tint = if (following) c.go else look.ink,
            selected = following,
            size = 20.dp,
        )
        ControlButton("main.globe", stringResource(R.string.home_globe), onGlobe, tint = if (c.isDark) c.go else GLOBE, size = 21.dp)
    }
}

private val GLOBE = Color(0xFF1F5F99)

@Composable
private fun ControlButton(symbol: String, description: String, onClick: () -> Unit, tint: Color, size: Dp, selected: Boolean = false) {
    val line = homeLook().railLine
    Column(
        Modifier
            .width(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
            Symbol(symbol, tint = tint, size = size, weight = MainSymbols.Width.RAIL)
        }
    }
}

/**
 * The rail's compass, as the board draws it: a 34 dp porcelain disc lit from the top left, a stone rim and a soft drop
 * shadow, with a red north and slate south needle that turns so it points to real north on the map.
 */
@Composable
fun NorthDial(bearing: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .cssShadow(CircleShape, y = 1.dp, blur = 3.dp, colour = Color.Black.copy(alpha = 0.25f))
            .innerShadow(CircleShape, Shadow(radius = 3.dp * 0.866f, color = Color.White.copy(alpha = 0.9f), offset = DpOffset(0.dp, 2.dp))),
    ) {
        Canvas(Modifier.size(34.dp)) {
            val s = size.width
            // CSS `radial-gradient(circle at 35% 30%, …)`, sized to the farthest corner of the box.
            val centre = Offset(s * 0.35f, s * 0.30f)
            val far = Offset(s, s) - centre
            drawCircle(
                Brush.radialGradient(0f to Color.White, 0.7f to Color(0xFFE9E4DA), 1f to Color(0xFFCFC6B6), center = centre, radius = far.getDistance()),
            )
            // The board's inset 1.5 dp rim.
            val rim = 1.5.dp.toPx()
            drawCircle(Color(0xFFB9AE99), radius = s / 2f - rim / 2f, style = Stroke(rim))
            // The 14 × 26 needle, centred, turned with the map.
            val unit = 1.dp.toPx()
            rotate(-bearing) {
                translate(s / 2f - 7f * unit, s / 2f - 13f * unit) {
                    scale(unit, unit, Offset.Zero) {
                        drawPath(NEEDLE_NORTH, Color(0xFFD93A3A))
                        drawPath(NEEDLE_SOUTH, Color(0xFF3A3F4B))
                        drawCircle(Color(0xFFF3EEE4), radius = 1.8f, center = Offset(7f, 13f))
                        drawCircle(Color(0xFF8C826F), radius = 1.8f, center = Offset(7f, 13f), style = Stroke(0.8f))
                    }
                }
            }
        }
    }
}

private fun svgPath(d: String): Path = Path().also { p ->
    SvgPath.parse(d).replay(object : PathSink {
        override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadraticTo(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
        override fun close() = p.close()
    })
}

private val NEEDLE_NORTH = svgPath("M7 1l4 12H3z")
private val NEEDLE_SOUTH = svgPath("M7 25l4-12H3z")

/** The eight categories of the Map home, in the boards' order. */
val HOME_CATEGORIES = listOf("eat", "groceries", "shops", "stay", "charge", "nature", "fun", "water")

/** The Main board's own symbol and colour for each of [HOME_CATEGORIES], and its symbol size in dp. */
internal class HomeCategory(val id: String, val symbol: String, val colour: Color, val size: Dp)

internal val HOME_CATEGORY_LOOK: Map<String, HomeCategory> = listOf(
    HomeCategory("eat", "main.eat", Color(0xFFB8461A), 22.dp),
    HomeCategory("groceries", "main.groceries", Color(0xFF1E7A46), 22.dp),
    HomeCategory("shops", "main.shops", Color(0xFF7A2233), 21.dp),
    HomeCategory("stay", "main.stay", Color(0xFF5B3FD6), 22.dp),
    HomeCategory("charge", "main.charge", Color(0xFF178A4E), 22.dp),
    HomeCategory("nature", "main.outdoors", Color(0xFF2F6B34), 22.dp),
    HomeCategory("fun", "main.events", Color(0xFF7B4DFF), 22.dp),
    HomeCategory("water", "main.water", Color(0xFF1F5F99), 22.dp),
).associateBy { it.id }

/**
 * The row of category buttons above the search bar, as the board lays it out: eight 44 dp buttons 8 dp apart, which
 * narrow evenly (to 41.5 dp on a 412 dp phone) when the row is too short for them, as the board's flex row does; the
 * chosen one fills with its colour.
 */
@Composable
fun CategoryRow(selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, lang: String? = null) {
    val look = homeLook()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val width = ((maxWidth - gap * (HOME_CATEGORIES.size - 1)) / HOME_CATEGORIES.size).coerceIn(32.dp, 44.dp)
        val shape = RoundedCornerShape(percent = 50)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (id in HOME_CATEGORIES) {
                val cat = Categories.byId(id) ?: continue
                val board = HOME_CATEGORY_LOOK.getValue(id)
                val on = id == selected
                val name = cat.name(lang)
                Box(
                    Modifier
                        .size(width, 44.dp)
                        .cssShadow(shape, y = 4.dp, blur = 14.dp, colour = look.shadow.copy(alpha = look.shadow.alpha * 0.1f / 0.12f))
                        .clip(shape)
                        .background(if (on) board.colour else look.frostStrong)
                        .toggleable(on, role = Role.Switch) { onSelect(id) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(board.symbol, tint = if (on) Color.White else board.colour, size = board.size, weight = MainSymbols.Width.PILL)
                }
            }
        }
    }
}

/** "Search or ask", 58 dp high, with the A-near-B rings and the dark mic. The field itself opens the search screen. */
@Composable
fun SearchBar(onSearch: () -> Unit, onNear: () -> Unit, onMic: () -> Unit, modifier: Modifier = Modifier) {
    val look = homeLook()
    val c = Atlas.colors
    val hint = stringResource(R.string.home_search_hint)
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .cssShadow(CircleShape, y = 10.dp, blur = 30.dp, colour = look.shadowStrong)
            .clip(CircleShape)
            .background(look.solid)
            .padding(end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(58.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_search), onClick = onSearch)
                .padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol("main.search", tint = look.ink, size = 21.dp, weight = MainSymbols.Width.PILL)
            Text(hint, style = boardText(16f, FontWeight.Medium), color = look.hint, maxLines = 1)
        }
        val near = stringResource(R.string.home_near)
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(look.nearBg).clickable(role = Role.Button, onClick = onNear)
                .semantics { contentDescription = near },
            contentAlignment = Alignment.Center,
        ) { Symbol("main.near", tint = look.ink, size = 19.dp, weight = MainSymbols.Width.PILL) }
        Box(Modifier.width(10.dp))
        val mic = stringResource(R.string.home_mic)
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(c.pill).clickable(role = Role.Button, onClick = onMic)
                .semantics { contentDescription = mic },
            contentAlignment = Alignment.Center,
        ) { Symbol("main.mic", tint = c.onPill, size = 19.dp, weight = MainSymbols.Width.PILL) }
    }
}
