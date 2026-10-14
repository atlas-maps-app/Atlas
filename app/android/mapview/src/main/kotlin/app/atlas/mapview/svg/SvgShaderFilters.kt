package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Filters as GPU effects on API 33+: the element is recorded into a [RenderNode] at filter-pixel scale, and the node
 * gets a [RenderEffect] made of the filter's AGSL program ([SvgShaderProgram]) with the recording as its `src` input,
 * then, when the filter ends with one, a blur. Only a hardware-accelerated canvas draws render effects.
 *
 * The final blur is Skia's Gaussian where WebKit uses three box blurs; it is otherwise the same filter as the CPU's.
 */
@RequiresApi(33)
internal class SvgShaderFilters {
    private val programs = IdentityHashMap<SvgFilter, Compiled?>()
    private val noise = HashMap<Float, Shader>()

    private class Compiled(val program: SvgShaderProgram, val shader: RuntimeShader?)

    /**
     * Draws [source] through [f] on [c] (whose matrix has the filter scale [sx], [sy]) for the region [region] in user
     * units. Returns false when this filter cannot run as a shader; the caller then draws it on the CPU.
     */
    fun draw(
        c: Canvas,
        f: SvgFilter,
        region: DoubleArray,
        sx: Double,
        sy: Double,
        visible: SvgFilterRaster.Rect,
        source: (Canvas) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !c.isHardwareAccelerated) return false
        val compiled = if (programs.containsKey(f)) programs[f] else compile(f).also { programs[f] = it }
        compiled ?: return false
        val p = compiled.program
        val regionRect = SvgFilterRaster.Rect.enclosing(region[0] * sx, region[1] * sy, region[2] * sx, region[3] * sy)
        val out = regionRect.intersect(visible)
        if (out.isEmpty) return true
        val reachX = p.reach(sx)
        val reachY = p.reach(sy)
        val nodeRect = regionRect.intersect(out.inset(reachX, reachY))
        val node = RenderNode("svg-filter")
        node.setPosition(nodeRect.left, nodeRect.top, nodeRect.right, nodeRect.bottom)
        val rec = node.beginRecording(nodeRect.width, nodeRect.height)
        try {
            rec.translate(-nodeRect.left.toFloat(), -nodeRect.top.toFloat())
            rec.scale(sx.toFloat(), sy.toFloat())
            source(rec)
        } finally {
            node.endRecording()
        }
        var effect: RenderEffect? = null
        val shader = compiled.shader
        if (shader != null) {
            shader.setFloatUniform("origin", nodeRect.left.toFloat(), nodeRect.top.toFloat())
            shader.setFloatUniform("scale", sx.toFloat(), sy.toFloat())
            shader.setFloatUniform(
                "region", regionRect.left.toFloat(), regionRect.top.toFloat(), regionRect.right.toFloat(), regionRect.bottom.toFloat(),
            )
            for ((i, t) in p.turbulences.withIndex()) shader.setInputShader("noise$i", noiseShader(t.seed))
            effect = RenderEffect.createRuntimeShaderEffect(shader, "src")
        }
        p.blur?.let { b ->
            val blur = RenderEffect.createBlurEffect(radius(b.deviationX * sx), radius(b.deviationY * sy), Shader.TileMode.DECAL)
            effect = effect?.let { RenderEffect.createChainEffect(blur, it) } ?: blur
        }
        node.setRenderEffect(effect)
        c.save()
        c.scale((1 / sx).toFloat(), (1 / sy).toFloat())
        c.clipRect(max(out.left, nodeRect.left).toFloat(), max(out.top, nodeRect.top).toFloat(), min(out.right, nodeRect.right).toFloat(), min(out.bottom, nodeRect.bottom).toFloat())
        c.drawRenderNode(node)
        c.restore()
        return true
    }

    private fun compile(f: SvgFilter): Compiled? {
        val program = SvgShaderProgram.of(f) ?: return null
        return try {
            Compiled(program, program.source?.let { RuntimeShader(it) })
        } catch (e: IllegalArgumentException) {
            // A program the platform's compiler refuses: the CPU draws this filter instead.
            null
        }
    }

    private fun noiseShader(seed: Float): Shader = noise.getOrPut(seed) {
        val table = SvgShaderProgram.noiseTable(Turbulence(seed))
        val bmp = Bitmap.createBitmap(table, 512, 5, Bitmap.Config.ARGB_8888)
        BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
    }

    /** The radius [RenderEffect.createBlurEffect] takes for a Gaussian of standard deviation [sigma] (it maps r to 0.57735 r + 0.5). */
    private fun radius(sigma: Double): Float = if (sigma <= 0.5) 0f else ((sigma - 0.5) / 0.57735).toFloat()
}
