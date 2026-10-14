package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.TileId

/** One draw call. */
sealed interface Batch {
    /**
     * Triangles in screen pixels with a colour per vertex, for `Canvas.drawVertices`: [vertexCount] vertices as x, y in
     * [positions], [indexCount] indices. Arrays are reused by the renderer: valid until the frame after next.
     *
     * When [layer] is not 0, consecutive batches with the same layer draw opaque into one offscreen layer that is then
     * composited at [layerAlpha], so overlapping triangles (shadows) do not darken each other.
     */
    class Triangles(
        val positions: FloatArray,
        val colours: IntArray,
        val indices: ShortArray,
        val vertexCount: Int,
        val indexCount: Int,
        val layerAlpha: Float = 1f,
        val layer: Int = 0,
    ) : Batch
}

/** What a renderer produces per frame: draw calls in painter's order, then labels and symbols to draw on top. */
class Frame internal constructor(
    val width: Int,
    val height: Int,
    val batches: List<Batch>,
    val labels: List<PlacedLabel>,
    val pois: List<PlacedPoi>,
    val stats: FrameStats,
    internal val hits: HitRecords,
)

/**
 * A label placed this frame: [text] at ([x], [y]) turned [angle] degrees, or along a path when [glyphs] holds x, y and
 * angle (degrees) per character, each centred on its point. [size] is in pixels, [haloWidth] too.
 */
class PlacedLabel(
    val text: String,
    val kind: LabelKind,
    val x: Float,
    val y: Float,
    val angle: Float,
    val glyphs: FloatArray?,
    val style: LabelStyle,
    val size: Float,
    val letterSpacing: Float,
    val colour: Int,
    val halo: Int,
    val haloWidth: Float,
    val alpha: Float,
    val key: Long,
)

/** A point of interest placed this frame, clear of labels and other symbols, for the view to draw its symbol at. */
class PlacedPoi(
    val poi: PoiCandidate,
    val x: Float,
    val y: Float,
    val depth: Float,
    /** 1 near the target, smaller far away in pitched views. */
    val scale: Float,
    val alpha: Float,
    val tile: TileId,
    val location: LatLon,
)

/** What the renderer did, for benchmarks and a debug overlay. */
data class FrameStats(
    val vertices: Int,
    val triangles: Int,
    val batches: Int,
    val scenes: Int,
    val objectsDrawn: Int,
    val facesDrawn: Int,
    val treesDrawn: Int,
    val labels: Int,
    val pois: Int,
    val sortReused: Boolean,
    val geometryNanos: Long,
    val labelNanos: Long,
    val totalNanos: Long,
    val groundNanos: Long = 0,
    val shadowNanos: Long = 0,
    val objectNanos: Long = 0,
) {
    override fun toString() =
        "vertices=$vertices triangles=$triangles batches=$batches scenes=$scenes objects=$objectsDrawn faces=$facesDrawn trees=$treesDrawn " +
            "labels=$labels pois=$pois reused=$sortReused geometry=${"%.2f".format(geometryNanos / 1e6)} ms (ground ${"%.2f".format(groundNanos / 1e6)}, " +
            "shadows ${"%.2f".format(shadowNanos / 1e6)}, objects ${"%.2f".format(objectNanos / 1e6)}) labels=${"%.2f".format(labelNanos / 1e6)} ms " +
            "total=${"%.2f".format(totalNanos / 1e6)} ms"
}

/** Per-frame choices that need no rebuild: label and symbol switches, sizes, and a calm override. */
class FrameStyle(
    /** Fixed calm 0..1; null takes the scenes' look (which ramps in below zoom 15). */
    val calm: Float? = null,
    val labels: Boolean = true,
    val pois: Boolean = true,
    /** The view's symbol size in dp, to keep labels clear of it. */
    val poiSize: Float = 26f,
    val maxPois: Int = 24,
    val labelScale: Float = 1f,
)

/** Text widths for label placement, from the platform's fonts. Sizes are in pixels. */
interface TextMeasurer {
    fun width(text: String, style: LabelStyle, size: Float): Float

    /** The advance of each character of [text] into [out]. */
    fun advances(text: String, style: LabelStyle, size: Float, out: FloatArray) {
        for (i in text.indices) out[i] = width(text[i].toString(), style, size)
    }
}

/** A measurer without fonts: widths from average glyph proportions. Good enough for tests and layout estimates. */
object ApproximateMeasurer : TextMeasurer {
    override fun width(text: String, style: LabelStyle, size: Float): Float {
        var w = 0f
        for (c in text) w += advance(c, style) * size
        return w
    }

    override fun advances(text: String, style: LabelStyle, size: Float, out: FloatArray) {
        for (i in text.indices) out[i] = advance(text[i], style) * size
    }

    private fun advance(c: Char, style: LabelStyle): Float {
        val base = when {
            c == ' ' -> 0.28f
            c in "iljtf.,;:'!|" -> 0.3f
            c in "mwMW" -> 0.85f
            c.isUpperCase() -> 0.66f
            c.isDigit() -> 0.56f
            else -> 0.52f
        }
        return if (style == LabelStyle.SERIF_ITALIC) base * 0.95f else base
    }
}

/** What lies under a point of the screen. */
sealed class Hit {
    /** A point of interest's symbol. */
    class Poi(val poi: PoiCandidate, val location: LatLon, val tile: TileId) : Hit()

    /** A building: its record and footprint. */
    class Building(val info: BuildingInfo, val tile: TileId, val location: LatLon, val footprint: List<LatLon>) : Hit()
}

/** Which triangles of which batch each drawn building became: for hit tests. */
internal class HitRecords(capacity: Int = 1024) {
    var count = 0
    var scene = arrayOfNulls<TileScene>(capacity)
    var objectIndex = IntArray(capacity)
    var batch = IntArray(capacity)
    var indexStart = IntArray(capacity)
    var indexEnd = IntArray(capacity)
    var poiBoxes = FloatArray(0)

    fun clear() { count = 0 }

    fun add(s: TileScene, o: Int, b: Int, i0: Int, i1: Int) {
        if (i1 <= i0) return
        if (count > 0 && scene[count - 1] === s && objectIndex[count - 1] == o && batch[count - 1] == b && indexEnd[count - 1] == i0) {
            indexEnd[count - 1] = i1
            return
        }
        if (count == batch.size) {
            val n = count * 2
            scene = scene.copyOf(n); objectIndex = objectIndex.copyOf(n); batch = batch.copyOf(n)
            indexStart = indexStart.copyOf(n); indexEnd = indexEnd.copyOf(n)
        }
        scene[count] = s; objectIndex[count] = o; batch[count] = b; indexStart[count] = i0; indexEnd[count] = i1
        count++
    }
}
