package app.atlas.ui.kit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import app.atlas.places.Icon
import java.util.Collections
import java.util.IdentityHashMap

private val built = Collections.synchronizedMap(IdentityHashMap<Icon, SymbolPaths>())

/**
 * Draws a board object that is not in the symbol registries (the mine package's `mine_art.json`) into a [side] square at
 * [topLeft], the way [drawSymbol] draws a registered symbol: paths with their own colours, widths, caps, dashes, opacity
 * and gradients; paths without a colour take [tint]. Texts and drop shadows of the object are not drawn.
 */
fun DrawScope.drawIcon(icon: Icon, topLeft: Offset, side: Float, tint: Color) {
    val s = built.getOrPut(icon) { SymbolPaths.build(icon) }
    val vb = s.viewBox
    val scale = side / maxOf(vb.width, vb.height)
    val dx = topLeft.x + (side - vb.width * scale) / 2f - vb.x * scale
    val dy = topLeft.y + (side - vb.height * scale) / 2f - vb.y * scale
    withTransform({
        translate(dx, dy)
        scale(scale, scale, Offset.Zero)
    }) {
        for (p in s.parts) {
            if (p.fill) {
                if (p.brush != null) drawPath(p.path, p.brush, alpha = p.opacity, style = Fill)
                else drawPath(p.path, p.colour ?: tint, alpha = p.opacity, style = Fill)
            }
            if (p.stroke != null) {
                if (p.brush != null) drawPath(p.path, p.brush, alpha = p.opacity, style = p.stroke)
                else drawPath(p.path, p.colour ?: tint, alpha = p.opacity, style = p.stroke)
            }
        }
    }
}
