package app.atlas.ui.kit

import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import app.atlas.mapview.vk.ChromeShapes
import app.atlas.mapview.vk.LocalChromeShapes
import app.atlas.mapview.vk.chromeShape
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What lies behind the boards' frosted glass: the content a screen marks with [backdropSource] (the Map home's map and
 * its wash), recorded every frame into [layer], which [frosted] surfaces blur behind themselves. One per app, provided
 * by the root; [active] while a source is on screen.
 *
 * Where the map's core draws on a SurfaceView, the map's window lies under the app's and HWUI cannot record it: there
 * the core draws the glass under every frosted surface itself (plan-vector D5.1, K11b), from [shapes], where each
 * [frosted] surface puts its box, corner, blur and saturation, and the surfaces draw only their tint and content.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    @Volatile
    internal var origin = Offset.Zero

    @Volatile
    internal var size = Size.Zero

    @Volatile
    internal var active = false

    /** The chrome's shapes: the root provides them as LocalChromeShapes, the map writes them into the core's frames. */
    val shapes = ChromeShapes()
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * Marks this content as what the frosted surfaces above it blur: it is recorded into the backdrop and drawn from it.
 * While the core draws the glass (the map on a SurfaceView), nothing reads the recording, and the content draws as it is.
 */
fun Modifier.backdropSource(backdrop: Backdrop?): Modifier = if (backdrop == null) this else composed {
    DisposableEffect(backdrop) {
        backdrop.active = true
        onDispose { backdrop.active = false }
    }
    this
        .onGloballyPositioned {
            backdrop.origin = it.positionInRoot()
            backdrop.size = it.size.toSize()
        }
        .drawWithContent {
            if (backdrop.shapes.coreDraws) {
                drawContent()
                return@drawWithContent
            }
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }
}

/**
 * The boards' frosted glass, CSS `background: [tint]; backdrop-filter: blur([blur]) saturate([saturate])`: what lies
 * behind (the [LocalBackdrop]) blurred by a Gaussian of σ = [blur] and saturated, inside [shape], under [tint]. The shape
 * is one of the chrome's ([ChromeShapes]): where the map's core draws on a SurfaceView, the core draws the glass under
 * it and this draws the tint alone over it. Without a backdrop on screen, or before Android 12 (no RenderEffect), the
 * tint alone.
 */
fun Modifier.frosted(shape: Shape, tint: Color, blur: Dp, saturate: Float = 1f): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val chrome = LocalChromeShapes.current ?: backdrop?.shapes
    val placed = chromeShape(chrome, shape, blur, saturate)
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || chrome?.coreDraws == true) {
        return@composed placed.drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            onDrawBehind { drawPath(path, tint) }
        }
    }
    val glass = rememberGraphicsLayer()
    val at = remember { FloatArray(2) }
    placed
        .onGloballyPositioned { val p = it.positionInRoot(); at[0] = p.x; at[1] = p.y }
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val sigma = blur.toPx()
            // three σ of what lies around the surface feed its blur, as the browser's blur reads past the edges
            val margin = ceil(sigma * 3f)
            // The blur runs on a copy at 1 / DOWN of the resolution with σ / DOWN, then scales back up: the same picture
            // for blurs this wide (σ of 20 px and more), at a sixteenth of the work.
            val down = if (sigma >= 8f) DOWN else 1f
            // Android's blur radius r is a Gaussian of σ = 0.57735 r + 0.5 px (hwui's convertRadiusToSigma)
            val radius = ((sigma / down - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
            val blurEffect = android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            glass.renderEffect = (
                if (saturate == 1f) blurEffect
                else android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturation(saturate))),
                    blurEffect,
                )
                ).asComposeRenderEffect()
            onDrawBehind {
                if (backdrop.active) {
                    // The copy covers the surface and three σ around it, within the backdrop, in whole blocks of the
                    // backdrop's own grid of DOWN pixels: so a surface moving over a still backdrop copies the same pixels,
                    // and at the window's edge the blur's clamp repeats the backdrop's edge, as the core's copy does
                    // (CP/passes/Frosted.cpp) and CSS keeps the glass whole at the viewport's edge.
                    val ox = backdrop.origin.x
                    val oy = backdrop.origin.y
                    val gx0 = max(0f, floor((at[0] - margin - ox) / down))
                    val gy0 = max(0f, floor((at[1] - margin - oy) / down))
                    val gx1 = min(floor(backdrop.size.width / down), ceil((at[0] + size.width + margin - ox) / down))
                    val gy1 = min(floor(backdrop.size.height / down), ceil((at[1] + size.height + margin - oy) / down))
                    if (gx1 > gx0 && gy1 > gy0) {
                        val x0 = ox + gx0 * down
                        val y0 = oy + gy0 * down
                        glass.record(IntSize((gx1 - gx0).toInt(), (gy1 - gy0).toInt())) {
                            scale(1f / down, 1f / down, Offset.Zero) { translate(ox - x0, oy - y0) { drawLayer(backdrop.layer) } }
                        }
                        clipPath(path) { translate(x0 - at[0], y0 - at[1]) { scale(down, down, Offset.Zero) { drawLayer(glass) } } }
                    }
                }
                drawPath(path, tint)
            }
        }
}

/** How much smaller the copy that a wide blur runs on is. */
private const val DOWN = 4f

/** CSS `saturate(s)` as a colour matrix (Filter Effects 1, `feColorMatrix type="saturate"`), in sRGB as browsers apply it. */
private fun saturation(s: Float): FloatArray = floatArrayOf(
    0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
    0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
    0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/** The boards' width in CSS pixels: every board is drawn on a 412 × 915 phone. */
const val BOARD_WIDTH = 412f

/**
 * Lays [content] out in board pixels: on a phone held upright, one dp is the screen's width over [BOARD_WIDTH], so every
 * screen is its board, in proportion, at the phone's own resolution (the boards' 412 × 915 is the 20:9 of most phones).
 * Tablets and landscape keep the system's density.
 */
@Composable
fun BoardScale(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val system = LocalDensity.current
        val upright = constraints.maxHeight > constraints.maxWidth
        val widthDp = constraints.maxWidth / system.density
        if (!upright || widthDp < 300f || widthDp > 520f || !constraints.hasBoundedWidth) {
            content()
        } else {
            CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth / BOARD_WIDTH, system.fontScale), content = content)
        }
    }
}
