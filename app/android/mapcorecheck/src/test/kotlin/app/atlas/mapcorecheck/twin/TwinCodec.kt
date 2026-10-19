package app.atlas.mapcorecheck.twin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The twin's stream read on the JVM (plan-vector D16's L1): what the core's frame draws at its camera, as every pass
 * emitted it (`CP/twin/Twin.hpp`, whose header defines the encoding, version 1, little-endian), into the same terms as
 * the port's SVG: elements with their styles and nodes, the definitions they refer to, their layers, groups, items and
 * painter slots. [TwinDocument.order] gives a layer's elements in painter's order, as the SVG draws them.
 */
object TwinCodec {
    /** The stream's layout version (`twin::stream_version`). */
    const val VERSION = 1

    // The records' opcodes (twin::Op).
    const val LAYER = 1
    const val GROUP = 2
    const val ITEM = 3
    const val GRADIENT = 4
    const val FILTER = 5
    const val PATTERN = 6
    const val CLIP_BEGIN = 7
    const val CLIP_END = 8
    const val AT = 9
    const val PATH = 10
    const val RECT = 11
    const val CIRCLE = 12
    const val ELLIPSE = 13
    const val TEXT = 14

    // Layers (twin::Layer).
    const val GROUND = 0
    const val RASTER = 1
    const val OBJECTS = 2

    // Paint kinds (twin::Paint::Kind).
    const val PAINT_NONE = 0
    const val PAINT_COLOUR = 1
    const val PAINT_GRADIENT = 2
    const val PAINT_PATTERN = 3

    /** The slot of the screen-fixed steps drawn after every object (`Stream::after`). */
    const val AFTER = 0xFFFF_FFFFL

    /** Reads a stream; fails, naming the byte, on one the core could not have written. */
    fun decode(bytes: ByteArray): TwinDocument {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && bytes[0] == 'T'.code.toByte() && bytes[1] == 'W'.code.toByte() && bytes[2] == 'N'.code.toByte() &&
            bytes[3] == '1'.code.toByte()) { "not a twin stream" }
        b.position(4)
        val version = b.int
        require(version == VERSION) { "a twin stream of version $version, this reader reads $VERSION" }
        val doc = TwinDocument()
        var layer = OBJECTS
        var slot = 0L
        var group = -1
        var item = -1
        var clip = -1
        var sequence = 0
        fun str(): String {
            val n = b.int
            require(n >= 0 && n <= b.remaining()) { "a string of $n bytes at ${b.position()}" }
            val a = ByteArray(n)
            b.get(a)
            return String(a, Charsets.UTF_8)
        }
        fun node() = TwinNode(
            b.float, b.get().toInt() and 0xFF, b.int, b.int, (b.get().toInt() != 0).let { t -> FloatArray(6) { b.float }.takeIf { t } },
        )
        fun paint() = TwinPaint(b.get().toInt() and 0xFF, b.int, b.int)
        fun style(): TwinStyle {
            val fill = paint(); val fillOpacity = b.float; val evenOdd = b.get().toInt() != 0
            val stroke = paint(); val strokeOpacity = b.float; val width = b.float
            val cap = b.get().toInt() and 0xFF; val join = b.get().toInt() and 0xFF; val miter = b.float
            val n = b.int
            require(n >= 0 && n * 4L <= b.remaining()) { "$n dashes at ${b.position()}" }
            val dashes = FloatArray(n) { b.float }
            return TwinStyle(fill, fillOpacity, evenOdd, stroke, strokeOpacity, width, cap, join, miter, dashes, b.float)
        }
        while (b.hasRemaining()) {
            val at = b.position()
            when (val op = b.get().toInt() and 0xFF) {
                LAYER -> layer = b.get().toInt() and 0xFF
                GROUP -> {
                    val index = b.int; val parent = b.int; val n = node()
                    require(index == doc.groups.size && parent < index) { "a malformed group at $at" }
                    doc.groups += TwinGroup(n, parent)
                }
                ITEM -> {
                    val index = b.int; val owner = b.get().toInt() and 0xFF; val id = b.long; val kind = str()
                    require(index == doc.items.size) { "a malformed item at $at" }
                    doc.items += TwinItem(owner, id, kind)
                }
                GRADIENT -> {
                    val index = b.int; val id = str(); val radial = b.get().toInt() != 0
                    val coords = FloatArray(5) { b.float }; val units = b.get().toInt() and 0xFF
                    val transformed = b.get().toInt() != 0; val transform = FloatArray(6) { b.float }
                    val spread = b.get().toInt() and 0xFF
                    val n = b.int
                    require(n >= 0 && n * 8L <= b.remaining()) { "$n stops at ${b.position()}" }
                    val offsets = FloatArray(n); val colours = IntArray(n)
                    for (i in 0 until n) { offsets[i] = b.float; colours[i] = b.int }
                    require(index == doc.gradients.size) { "a malformed gradient at $at" }
                    doc.gradients += TwinGradient(id, radial, coords, units, transform.takeIf { transformed }, spread, offsets, colours)
                }
                FILTER, PATTERN -> {
                    val index = b.int; val id = str()
                    val defs = if (op == FILTER) doc.filters else doc.patterns
                    require(index == defs.size) { "a malformed definition at $at" }
                    defs += id
                }
                CLIP_BEGIN -> {
                    val index = b.int; val id = str(); val units = b.get().toInt() and 0xFF; val parent = b.int
                    require(index == doc.clips.size && clip < 0 && parent < index) { "a malformed clip path at $at" }
                    doc.clips += TwinClip(id, units, parent)
                    clip = index
                }
                CLIP_END -> {
                    require(clip >= 0) { "a clip path's end without its beginning at $at" }
                    clip = -1
                }
                AT -> {
                    slot = b.int.toLong() and 0xFFFF_FFFFL; group = b.int; item = b.int
                    require(group < doc.groups.size && item < doc.items.size) { "a malformed place at $at" }
                }
                PATH, RECT, CIRCLE, ELLIPSE, TEXT -> {
                    val n = node(); val s = style()
                    var points = FloatArray(0); var ends = IntArray(0); var closed = BooleanArray(0)
                    var text: String? = null; var font: TwinFont? = null
                    when (op) {
                        PATH -> {
                            val subpaths = b.int
                            require(subpaths >= 0 && subpaths * 5L <= b.remaining()) { "$subpaths subpaths at ${b.position()}" }
                            val pts = ArrayList<Float>(); ends = IntArray(subpaths); closed = BooleanArray(subpaths)
                            for (k in 0 until subpaths) {
                                closed[k] = b.get().toInt() != 0
                                val count = b.int
                                require(count >= 0 && count * 8L <= b.remaining()) { "$count points at ${b.position()}" }
                                repeat(count) { pts += b.float; pts += b.float }
                                ends[k] = pts.size / 2
                            }
                            points = pts.toFloatArray()
                        }
                        RECT -> points = FloatArray(6) { b.float }
                        CIRCLE -> points = floatArrayOf(b.float, b.float, b.float, 0f)
                        ELLIPSE -> points = FloatArray(4) { b.float }
                        else -> {
                            text = str()
                            points = floatArrayOf(b.float, b.float)
                            font = TwinFont(str(), b.float, b.int, b.get().toInt() != 0, b.float, b.get().toInt() and 0xFF)
                        }
                    }
                    doc.elements += TwinElement(op, layer, slot, group, item, clip, n, s, points, ends, closed, text, font, sequence++)
                }
                else -> throw IllegalArgumentException("opcode $op at $at")
            }
        }
        require(clip < 0) { "the stream ends inside a clip path" }
        return doc
    }
}

/** What an element carries itself (an SVG node): opacity, blend (SvgBlend's order), filter and clip (indices, −1), transform. */
class TwinNode(val opacity: Float, val blend: Int, val filter: Int, val clip: Int, val transform: FloatArray?)

/** A paint: [kind] (TwinCodec.PAINT_…), its colour (ARGB, its own alpha) or the gradient's or pattern's index. */
class TwinPaint(val kind: Int, val argb: Int, val ref: Int)

/** An element's style, as SvgStyle's: caps and joins in SvgStyle's orders. */
class TwinStyle(
    val fill: TwinPaint,
    val fillOpacity: Float,
    val evenOdd: Boolean,
    val stroke: TwinPaint,
    val strokeOpacity: Float,
    val strokeWidth: Float,
    val cap: Int,
    val join: Int,
    val miterLimit: Float,
    val dashes: FloatArray,
    val dashOffset: Float,
)

/** A text run's font: anchor 0 start, 1 middle, 2 end. */
class TwinFont(val family: String, val size: Float, val weight: Int, val italic: Boolean, val letterSpacing: Float, val anchor: Int)

/** A gradient: linear (x1 y1 x2 y2) or radial (cx cy r fx fy); units 0 user space, 1 bounding box; spread 0 pad. */
class TwinGradient(
    val id: String,
    val radial: Boolean,
    val coords: FloatArray,
    val units: Int,
    val transform: FloatArray?,
    val spread: Int,
    val offsets: FloatArray,
    val colours: IntArray,
)

/** A group (an SVG <g> with an effect) and its parent (−1: the layer's root). */
class TwinGroup(val node: TwinNode, val parent: Int)

/** An item: a painter unit of the objects, its owner (AnchorItems' OwnerType), OSM id and kind. */
class TwinItem(val owner: Int, val id: Long, val kind: String)

/** A clip path, its units and its parent clip. */
class TwinClip(val id: String, val units: Int, val parent: Int)

/**
 * An element: its [kind] (TwinCodec's opcodes), layer, painter slot, group, item and the clip path it is geometry of
 * (−1: drawn). [points]: a path's (x, y) pairs with each subpath's end ([ends], in points) and closing; a rect's x y,
 * width height, rx ry; a circle's centre, r and 0; an ellipse's centre, rx ry; a text run's x y.
 */
class TwinElement(
    val kind: Int,
    val layer: Int,
    val slot: Long,
    val group: Int,
    val item: Int,
    val clip: Int,
    val node: TwinNode,
    val style: TwinStyle,
    val points: FloatArray,
    val ends: IntArray,
    val closed: BooleanArray,
    val text: String?,
    val font: TwinFont?,
    val sequence: Int,
)

/** A decoded stream. */
class TwinDocument {
    val gradients = ArrayList<TwinGradient>()
    val filters = ArrayList<String>()
    val patterns = ArrayList<String>()
    val clips = ArrayList<TwinClip>()
    val groups = ArrayList<TwinGroup>()
    val items = ArrayList<TwinItem>()
    val elements = ArrayList<TwinElement>()

    /** The drawn elements of [layer] in painter's order: by slot, the stream's order within a slot. */
    fun order(layer: Int): List<TwinElement> = elements.filter { it.layer == layer && it.clip < 0 }.sortedBy { it.slot }

    /** The clip paths' geometry: the elements of clip [index]. */
    fun clipGeometry(index: Int): List<TwinElement> = elements.filter { it.clip == index }

    /** The chain of groups from the layer's root to [group] (innermost last). */
    fun chain(group: Int): List<TwinGroup> {
        val out = ArrayList<TwinGroup>()
        var g = group
        while (g >= 0) {
            out += groups[g]
            g = groups[g].parent
        }
        return out.asReversed()
    }
}
