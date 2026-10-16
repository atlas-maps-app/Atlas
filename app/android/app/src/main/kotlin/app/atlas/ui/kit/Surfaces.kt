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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max

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
 * A CSS `box-shadow: [x] [y] [blur] [spread] [colour]` as the boards write it, drawn as CSS draws it: the box grown by
 * [spread] (its corner radii grown as CSS grows them, so a round box keeps a round ring), moved by the offset, blurred by
 * a Gaussian of σ = blur / 2, and shown only outside the box (glass over it does not show the shadow through).
 */
fun Modifier.cssShadow(shape: Shape, y: Dp, blur: Dp, colour: Color, x: Dp = 0.dp, spread: Dp = 0.dp): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val box = Path().apply { addOutline(outline) }
    val sp = spread.toPx()
    val shadow = spreadOutline(shape, outline, sp).apply { translate(Offset(x.toPx(), y.toPx())) }.asAndroidPath()
    val sigma = blur.toPx() / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colour.toArgb()
        // Skia's blur radius r gives σ = 0.57735 r + 0.5 px
        if (sigma > 0.5f) maskFilter = android.graphics.BlurMaskFilter((sigma - 0.5f) / 0.57735f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    onDrawBehind {
        clipPath(box, clipOp = ClipOp.Difference) { drawContext.canvas.nativeCanvas.drawPath(shadow, paint) }
    }
}

/** The box's outline grown by [sp] px on every side, as CSS grows a shadow: each corner radius by [cssSpreadRadius]. */
private fun androidx.compose.ui.draw.CacheDrawScope.spreadOutline(shape: Shape, outline: Outline, sp: Float): Path = Path().apply {
    when {
        sp == 0f -> addOutline(outline)
        outline is Outline.Rectangle -> addRect(outline.rect.inflate(sp))
        outline is Outline.Rounded -> {
            val r = outline.roundRect
            fun grow(c: CornerRadius) = CornerRadius(cssSpreadRadius(c.x, sp), cssSpreadRadius(c.y, sp))
            addRoundRect(
                RoundRect(
                    r.left - sp, r.top - sp, r.right + sp, r.bottom + sp,
                    grow(r.topLeftCornerRadius), grow(r.topRightCornerRadius), grow(r.bottomRightCornerRadius), grow(r.bottomLeftCornerRadius),
                ),
            )
        }
        else -> {
            // a drawn outline: the shape made at the grown size
            val bigger = Size(max(0f, size.width + 2 * sp), max(0f, size.height + 2 * sp))
            addOutline(shape.createOutline(bigger, layoutDirection, this@spreadOutline))
            translate(Offset(-sp, -sp))
        }
    }
}

/**
 * A corner radius [r] grown by a shadow's spread [s] (CSS Backgrounds 3, box-shadow): r + s, except that a radius below
 * the spread grows by s · (1 + (r / s − 1)³), so a square corner stays square; a negative spread shrinks it to 0 at least.
 */
internal fun cssSpreadRadius(r: Float, s: Float): Float = when {
    s <= 0f -> max(0f, r + s)
    r >= s -> r + s
    else -> {
        val q = r / s - 1f
        r + s * (1f + q * q * q)
    }
}

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
