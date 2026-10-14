package app.atlas.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.mapview.Follow
import app.atlas.places.Categories
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Compass
import app.atlas.ui.kit.CurrentWeather
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.softShadow
import kotlin.math.roundToInt

/** Whether the area on screen works without a connection. */
enum class OfflineState(val symbol: String) {
    /** A downloaded region covers it. */
    READY("ui-cloud-check"),

    /** It comes from the network, which is there now. */
    ONLINE("ui-cloud"),

    /** No region and no connection: only what is cached. */
    NONE("ui-cloud-off"),
}

/** The white pill at the top left: the sky and the temperature, then whether this area works offline. */
@Composable
fun WeatherChip(weather: CurrentWeather?, offline: OfflineState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
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
            .height(46.dp)
            .softShadow(CircleShape, c.shadow, 10.dp)
            .clip(CircleShape)
            .background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (now != null) {
            val symbol = now.symbol
            val tint = when {
                symbol == "ui-sun" -> Color(0xFFE0592A)
                symbol == "ui-cloud-sun" -> Color(0xFFE08A2A)
                else -> c.inkSoft
            }
            Symbol(symbol, tint = tint, size = 22.dp)
            Text("${now.temperature.roundToInt()}°", style = Atlas.type.numeralSmall, color = c.ink)
            Box(Modifier.width(1.dp).height(22.dp).background(c.line))
        }
        Symbol(
            offline.symbol,
            tint = when (offline) {
                OfflineState.READY -> c.open
                OfflineState.ONLINE -> c.inkSoft
                OfflineState.NONE -> c.alert
            },
            size = 22.dp,
        )
    }
}

/** The dark "✦ 3" pill: what is coming up. */
@Composable
fun MomentsChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    val description = stringResource(R.string.home_moments, count)
    Row(
        modifier
            .height(40.dp)
            .softShadow(CircleShape, c.shadow, 8.dp)
            .clip(CircleShape)
            .background(LIGHT_INK)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol("ui-sparkles", tint = Color(0xFFFFD66B), size = 20.dp)
        Text(count.toString(), style = Atlas.type.name, color = Color.White)
    }
}

private val LIGHT_INK = Color(0xFF14161B)

/** The quick needs: water, food, a toilet, each one tap away. */
@Composable
fun NeedsPill(onWater: () -> Unit, onFood: () -> Unit, onToilet: () -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    Row(
        modifier
            .softShadow(CircleShape, c.shadow, 10.dp)
            .clip(CircleShape)
            .background(c.card)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        NeedButton(Color(0xFF1E64C8), stringResource(R.string.home_need_water), onWater) { Symbol("ui-droplet", tint = Color.White, size = 18.dp) }
        NeedButton(Color(0xFFB4471C), stringResource(R.string.home_need_food), onFood) { Symbol("temaki-bread", tint = Color.White, size = 20.dp) }
        NeedButton(Color(0xFF6B3FD4), stringResource(R.string.home_need_toilet), onToilet) {
            Text(stringResource(R.string.home_wc), style = Atlas.type.label.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
        }
    }
}

@Composable
private fun NeedButton(colour: Color, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(colour)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The right-hand column: compass (north up), 3D, layers, locate and follow, the globe. */
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
    val c = Atlas.colors
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .width(48.dp)
            .softShadow(shape, c.shadow, 12.dp)
            .clip(shape)
            .background(c.card),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val compass = stringResource(R.string.home_compass)
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = compass, onClick = onCompass).semantics { contentDescription = compass },
            contentAlignment = Alignment.Center,
        ) {
            Compass(heading = bearing.toFloat(), size = 34.dp)
        }
        Divider()
        ControlButton("ui-cube", stringResource(if (is3d) R.string.home_3d_on else R.string.home_3d_off), on3d, tint = if (is3d) c.go else c.ink, selected = is3d)
        Divider()
        ControlButton("ui-layers", stringResource(R.string.home_layers), onLayers)
        Divider()
        val following = follow != Follow.NONE
        ControlButton(
            if (follow == Follow.HEADING) "ui-navigation-2" else "ui-navigation",
            stringResource(if (follow == Follow.POSITION) R.string.home_follow else R.string.home_locate),
            onLocate,
            tint = if (following) c.go else c.ink,
            selected = following,
        )
        Divider()
        ControlButton("ui-globe", stringResource(R.string.home_globe), onGlobe, tint = c.go)
    }
}

@Composable
private fun Divider() = Box(Modifier.width(28.dp).height(1.dp).background(Atlas.colors.line))

@Composable
private fun ControlButton(symbol: String, description: String, onClick: () -> Unit, tint: Color = Atlas.colors.ink, selected: Boolean = false) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint = tint, size = 22.dp)
    }
}

/** The eight categories of the Map home, in the boards' order. */
val HOME_CATEGORIES = listOf("eat", "groceries", "shops", "stay", "charge", "nature", "fun", "water")

/**
 * The row of round category buttons above the search bar, sized so all eight fit the width as on the board; the chosen
 * one fills with its colour.
 */
@Composable
fun CategoryRow(selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, lang: String? = null) {
    val c = Atlas.colors
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val gap = 6.dp
        val size = ((maxWidth - gap * (HOME_CATEGORIES.size - 1)) / HOME_CATEGORIES.size).coerceIn(36.dp, 44.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (id in HOME_CATEGORIES) {
                val cat = Categories.byId(id) ?: continue
                val colour = Color(cat.colour)
                val on = id == selected
                val name = cat.name(lang)
                Box(
                    Modifier
                        .size(size)
                        .softShadow(CircleShape, c.shadow, 8.dp)
                        .clip(CircleShape)
                        .background(if (on) colour else c.card)
                        .toggleable(on, role = Role.Switch) { onSelect(id) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(cat.symbol, tint = if (on) Color.White else colour, size = size * 0.5f)
                }
            }
        }
    }
}

/** "Search or ask", with the A-near-B rings and the mic. The field itself opens the search screen. */
@Composable
fun SearchBar(onSearch: () -> Unit, onNear: () -> Unit, onMic: () -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    val hint = stringResource(R.string.home_search_hint)
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .softShadow(CircleShape, c.shadow, 14.dp)
            .clip(CircleShape)
            .background(c.card)
            .padding(start = 18.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(58.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_search), onClick = onSearch),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol("ui-search", tint = c.ink, size = 24.dp)
            Text(hint, style = Atlas.type.body.copy(fontSize = 19.sp), color = c.inkFaint, maxLines = 1)
        }
        val near = stringResource(R.string.home_near)
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(c.cardMuted).clickable(role = Role.Button, onClick = onNear)
                .semantics { contentDescription = near },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui-near", tint = c.ink, size = 22.dp) }
        Spacer(Modifier.width(6.dp))
        val mic = stringResource(R.string.home_mic)
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(c.pill).clickable(role = Role.Button, onClick = onMic)
                .semantics { contentDescription = mic },
            contentAlignment = Alignment.Center,
        ) { Symbol("ui-mic", tint = c.onPill, size = 22.dp) }
    }
}
