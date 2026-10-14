package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** The radii of the boards: cards 24, sheets 32, pills fully round. */
object Radii {
    val card = RoundedCornerShape(24.dp)
    val tile = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val pill = CircleShape
}

/** The soft, warm shadow under cards, pills and buttons that float over the map or the paper. */
fun Modifier.softShadow(shape: Shape, colour: Color, elevation: Dp = 12.dp): Modifier =
    dropShadow(shape, Shadow(radius = elevation, color = colour, offset = DpOffset(0.dp, elevation / 4)))

/**
 * A white card with large radii and a soft shadow, the board's basic surface. [onClick] makes the whole card a button;
 * [onClickLabel] says what it does for accessibility.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = Radii.card,
    colour: Color = Atlas.colors.card,
    padding: PaddingValues = PaddingValues(16.dp),
    elevation: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shadow = Atlas.colors.shadow
    Column(
        modifier
            .then(if (elevation > 0.dp) Modifier.softShadow(shape, shadow, elevation) else Modifier)
            .clip(shape)
            .background(colour)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColourFor(colour), content = { content() })
    }
}

/** The ink that reads on [background]: the theme's ink on its light surfaces, white on dark or saturated ones. */
@Composable
fun contentColourFor(background: Color): Color {
    val c = Atlas.colors
    return when (background) {
        c.card, c.cardMuted, c.paper, c.paperDeep -> c.ink
        c.pill -> c.onPill
        else -> if (background.luminance() > 0.45f) LightColors.ink else Color.White
    }
}
