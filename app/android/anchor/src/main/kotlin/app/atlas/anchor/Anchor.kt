package app.atlas.anchor

import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgFilter
import app.atlas.mapview.svg.SvgGradient
import app.atlas.mapview.svg.SvgUnits
import app.atlas.render.py.PyMath

/**
 * One exact engine render at one camera, lifted into space: what the GPU draws at any camera (plan-gpu D2).
 *
 * Every point lives in the anchor camera's **eye frame**, metres: x right, y up, z forward (the depth), origin at the
 * anchor camera's eye. The eye frame is the region frame turned and moved by the anchor's camera ([AnchorCamera.world]),
 * so a frame at any other camera is one double-precision matrix away; it keeps single-precision points exact near the
 * camera, where the engine's near-plane cuts print points millions of pixels off screen. At the anchor's own camera
 * every point projects onto its SVG coordinate after its residual ([AnchorPoints.screenX]).
 *
 * The primitives are in the engine's painter's order ([AnchorPrims.drawn] of them), followed by the clip paths' own
 * primitives; the layer plan ([AnchorPlan]) groups them into layers, groups (opacity, blend, filter, clip) and runs.
 * Paints, gradients, filters, text runs and patterns are the SVG's, resolved to the anchor's screen.
 */
class Anchor(
    val head: AnchorHead,
    val points: AnchorPoints,
    val prims: AnchorPrims,
    /** Triangle corners (point indices) for TRI and STENCIL primitives, three per triangle. */
    val triangles: IntArray,
    val paints: AnchorPaints,
    /** The gradients paints refer to, as the SVG defines them (coordinates in their own units). */
    val gradients: List<SvgGradient>,
    val plan: AnchorPlan,
    /** The filters groups refer to, as the SVG defines them. */
    val filters: List<AnchorFilter>,
    val items: AnchorItems,
    val texts: List<AnchorText>,
    val patterns: List<AnchorPattern>,
    /** The relief raster of terrain regions (their `t` layer), or null. */
    val image: AnchorImage?,
    val screen: AnchorScreen,
    /** The engine's output version the anchor was made with (G4's `EngineVersion`); anchors of another are stale. */
    val engineVersion: String,
    /** The scene source's hash (pack sha or window chunk hashes), 32 bytes. */
    val sourceHash: ByteArray,
) {
    init {
        require(sourceHash.size == 32) { "the source hash has 32 bytes, not ${sourceHash.size}" }
    }

    /** Bytes the anchor's arrays hold in memory (strings and small objects estimated). */
    fun bytes(): Long {
        var b = points.bytes() + prims.bytes() + triangles.size * 4L + paints.bytes() + items.bytes() + plan.nodes.size * 32L
        b += gradients.sumOf { 64L + it.offsets.size * 8L }
        b += texts.sumOf { 96L + it.text.length * 2L } + patterns.sumOf { 32L + it.svg.length * 2L }
        b += (image?.data?.size ?: 0) + screen.svg.length * 2L + screen.spec.length * 2L
        return b
    }
}

/** What made an anchor, and how it is used (plan-gpu D2's kinds). */
enum class AnchorKind { REST, EDGE, BACKDROP, FOLLOW, PREFETCH, BUNDLED, PUBLISHED, DRAFT }

/**
 * The engine camera an anchor was rendered at, `render/city.py`'s `Camera`: target, yaw and pitch in degrees, distance,
 * vertical field of view, a [width] × [height] CSS px screen with principal point [cx], [cy], and the near plane. The
 * derived eye ([eyeX] …), axes and focal length are the engine's own doubles (Python's maths), so every reader projects
 * as the render did.
 */
class AnchorCamera(
    val targetX: Double, val targetY: Double, val targetZ: Double,
    val yaw: Double, val pitch: Double, val dist: Double, val fov: Double,
    val width: Double, val height: Double, val cx: Double, val cy: Double, val near: Double,
    /** The eye, region frame metres. */
    val eyeX: Double, val eyeY: Double, val eyeZ: Double,
    /** Right, up and forward unit vectors: (rx, ry, rz, ux, uy, uz, fx, fy, fz). */
    val axes: DoubleArray,
    /** Pixels per unit of tangent: (H/2) / tan(fov/2). */
    val focal: Double,
) {
    init {
        require(axes.size == 9) { "axes hold right, up and forward" }
    }

    /** Screen x (CSS px) of eye-frame point ([x], [y], [z]). */
    fun sx(x: Double, z: Double): Double = cx + focal * x / z

    /** Screen y (CSS px) of eye-frame point ([x], [y], [z]). */
    fun sy(y: Double, z: Double): Double = cy - focal * y / z

    /** Writes the region-frame point of eye-frame ([x], [y], [z]) into [out] from [at]. */
    fun world(x: Double, y: Double, z: Double, out: DoubleArray, at: Int = 0) {
        val a = axes
        out[at] = eyeX + x * a[0] + y * a[3] + z * a[6]
        out[at + 1] = eyeY + x * a[1] + y * a[4] + z * a[7]
        out[at + 2] = eyeZ + x * a[2] + y * a[5] + z * a[8]
    }

    /** Writes the eye-frame point of region-frame ([x], [y], [z]) into [out] from [at]. */
    fun eye(x: Double, y: Double, z: Double, out: DoubleArray, at: Int = 0) {
        val a = axes
        val dx = x - eyeX; val dy = y - eyeY; val dz = z - eyeZ
        out[at] = dx * a[0] + dy * a[1] + dz * a[2]
        out[at + 1] = dx * a[3] + dy * a[4] + dz * a[5]
        out[at + 2] = dx * a[6] + dy * a[7] + dz * a[8]
    }

    /** The screen row of the horizon (`Camera.horizon_y`). */
    fun horizonY(): Double = cy - (-axes[7] / axes[4]) * focal
}

/**
 * The anchor's header (the file's `HEAD`): where and what it is, its camera, how far it is complete and the engine's
 * radius cut, its moment, and counts.
 */
class AnchorHead(
    val regionId: String,
    /** The region frame: x = (lon − lon0)·kx, z = −(lat − lat0)·kz. */
    val lat0: Double, val lon0: Double, val kx: Double, val kz: Double,
    val kind: AnchorKind,
    val camera: AnchorCamera,
    /** The completeness rect, anchor CSS px (left, top, right, bottom): the frame plus the engine's smallest cull margin. */
    val complete: DoubleArray,
    /** The engine's radius cut: items whose reference point lies beyond [radius] of ([radiusX], [radiusZ]) are not drawn. */
    val radius: Double, val radiusX: Double, val radiusZ: Double,
    /** The ground's own radius (the engine's `ground_r`). */
    val groundR: Double,
    val moment: String,
    val themeKey: String,
    /** The moment's time (ISO 8601), or null for a named moment. */
    val time: String?,
    /** The scene's simplification band, metres (Lod). */
    val bandTolerance: Double,
    /** The region view the camera matched, or null between views. */
    val view: String?,
    /** Points per lift class ([LiftClass]). */
    val liftCounts: IntArray,
    /** Primitives per kind ([PrimKind]), clip primitives included. */
    val primCounts: IntArray,
)

/** How a point got its place in space (plan-gpu D2, "Lifting"). */
object LiftClass {
    /** On the world tape: the engine projected it. */
    const val TAPED: Byte = 0

    /** Not on the tape, on an element with three or more taped points: on the plane through them. */
    const val PLANE: Byte = 1

    /** Built by the engine as a screen offset from a projected point: hangs on a taped point ([AnchorPoints.hang]). */
    const val BILLBOARD: Byte = 2

    /** On the ground plane (only elements the ground owns, owner 0). */
    const val GROUND: Byte = 3

    /** Fixed to the screen (sky, haze, fin, cloud shadows): the point's x and y are anchor CSS px, z is 0. */
    const val SCREEN: Byte = 4

    const val COUNT = 5
    val NAMES = listOf("taped", "plane", "billboard", "ground", "screen")
}

/**
 * The anchor's points: eye-frame metres ([xyz]; for [LiftClass.SCREEN], anchor CSS px and 0), each with its residual
 * and lift class. Billboards are listed apart, sorted by point: the taped point each hangs on and its anchor screen offset.
 */
class AnchorPoints(
    val xyz: FloatArray,
    /** Per point, the anchor's printed screen position minus the projection of its point, 1/1000 CSS px (x, y). */
    val residual: ShortArray,
    val lift: ByteArray,
    /** The billboard points, ascending. */
    val billboards: IntArray,
    /** Per billboard, the taped point it hangs on. */
    val hangs: IntArray,
    /** Per billboard, its anchor screen offset from its hang point's projection, CSS px (x, y). */
    val offsets: FloatArray,
) {
    val size: Int get() = lift.size

    init {
        require(xyz.size == size * 3 && residual.size == size * 2) { "points, residuals and classes disagree" }
        require(hangs.size == billboards.size && offsets.size == billboards.size * 2) { "billboard tables disagree" }
    }

    fun x(i: Int): Float = xyz[i * 3]
    fun y(i: Int): Float = xyz[i * 3 + 1]
    fun z(i: Int): Float = xyz[i * 3 + 2]

    /** The row of point [i] in the billboard tables, or −1 when it is not a billboard. */
    fun billboard(i: Int): Int {
        if (lift[i] != LiftClass.BILLBOARD) return -1
        val k = billboards.binarySearch(i)
        return if (k >= 0) k else -1
    }

    /** The taped point billboard point [i] hangs on, or −1. */
    fun hang(i: Int): Int = billboard(i).let { if (it < 0) -1 else hangs[it] }

    /** Screen x of point [i] at the anchor's own camera, CSS px, without its residual (see [screenX]). */
    fun projectedX(cam: AnchorCamera, i: Int): Double = when (lift[i]) {
        LiftClass.SCREEN -> xyz[i * 3].toDouble()
        LiftClass.BILLBOARD -> {
            val k = billboard(i); val h = hangs[k]
            cam.sx(xyz[h * 3].toDouble(), xyz[h * 3 + 2].toDouble()) + offsets[k * 2]
        }
        else -> cam.sx(xyz[i * 3].toDouble(), xyz[i * 3 + 2].toDouble())
    }

    /** Screen y of point [i] at the anchor's own camera, CSS px, without its residual. */
    fun projectedY(cam: AnchorCamera, i: Int): Double = when (lift[i]) {
        LiftClass.SCREEN -> xyz[i * 3 + 1].toDouble()
        LiftClass.BILLBOARD -> {
            val k = billboard(i); val h = hangs[k]
            cam.sy(xyz[h * 3 + 1].toDouble(), xyz[h * 3 + 2].toDouble()) + offsets[k * 2 + 1]
        }
        else -> cam.sy(xyz[i * 3 + 1].toDouble(), xyz[i * 3 + 2].toDouble())
    }

    /** Screen x of point [i] at the anchor's camera with its residual: the SVG's own coordinate. */
    fun screenX(cam: AnchorCamera, i: Int): Double = projectedX(cam, i) + residual[i * 2] / 1000.0

    /** Screen y of point [i] at the anchor's camera with its residual: the SVG's own coordinate. */
    fun screenY(cam: AnchorCamera, i: Int): Double = projectedY(cam, i) + residual[i * 2 + 1] / 1000.0

    fun bytes(): Long = xyz.size * 4L + residual.size * 2L + lift.size + billboards.size * 4L + hangs.size * 4L + offsets.size * 4L
}

/** The kinds of primitive (plan-gpu D2, "What an anchor holds"). */
object PrimKind {
    /**
     * A convex ring of at most [MAX_FACE] corners with analytic coverage: `n, hard, p0 … p(n−1)`. Bit k of `hard` marks
     * edge k (corner k to corner k + 1) as internal: a cut between two faces of one concave fill, tested at the pixel's
     * centre with no anti-aliasing ([Faces.hardSide]) in both passes, so the face owning the centre draws the pixel and
     * the faces of a fill tile it exactly; the others are the fill's outline, anti-aliased.
     */
    const val FACE: Byte = 0

    /**
     * Triangles at 4x MSAA: `first, count` into [Anchor.triangles] (indices, three a triangle). The compiler cuts its
     * fills into FACEs instead (their outline keeps analytic coverage); TRI is for anchors made without an engine render
     * (draft anchors, G14).
     */
    const val TRI: Byte = 1

    /** Triangles through the stencil (a fan per ring; nonzero, or even-odd with [PrimFlag.EVEN_ODD]): `first, count`. */
    const val STENCIL: Byte = 2

    /**
     * A stroke segment with exact area coverage, and the join at its end: `a, b, prev, next, width, ends, miterLimit`.
     * At a joined start (`prev` before `a`, else −1) it stops at `a` and at the bisector with its predecessor; at a
     * joined end (`next` after `b`, else −1) it stops at the bisector with its successor, and draws the join's wedge,
     * what lies beyond both segments' ends (round, miter or bevel). At a free end it ends with its cap. `ends` holds the
     * start cap (bits 0–1, [Cap]), the end cap (bits 2–3) and the join at `b` (bits 4–5, [Join]); width and miter limit
     * are float bits, the width in anchor CSS px.
     */
    const val SEG: Byte = 3

    /** A dot (a zero-length subpath with round caps): `p, width`. */
    const val JOIN: Byte = 4

    /**
     * An ellipse: `centre, rx, ry, rotation (radians), ring` in anchor CSS px (float bits); a ring > 0 strokes it that
     * wide. Coverage: [Faces.ellipseCoverage] of the first-order distance to its edge.
     */
    const val ELL: Byte = 5

    /** A text tile: `text, p0, p1, p2, p3` (the tile quad's corners: top left, top right, bottom right, bottom left). */
    const val TILE: Byte = 6

    const val COUNT = 7
    const val MAX_FACE = 8
    val NAMES = listOf("FACE", "TRI", "STENCIL", "SEG", "JOIN", "ELL", "TILE")
}

/** Primitive flags. */
object PrimFlag {
    /** Its paint is not opaque (alpha, opacity, gradient alpha or pattern): drawn in the translucent pass. */
    const val TRANSLUCENT = 1

    /** Drawn through the stencil (STENCIL fills, and clip primitives). */
    const val STENCIL = 2

    /** Fixed to the screen: its points are anchor CSS px ([LiftClass.SCREEN]). */
    const val SCREEN_FIXED = 4

    /** A wall's or roof's face: dropped when its projected winding differs from its winding at the anchor's camera ([POSITIVE]). */
    const val CULL_ON_FLIP = 8

    /** The face's shoelace area on the anchor's screen (x right, y down) is positive. */
    const val POSITIVE = 16

    /** A clip path's primitive (after the drawn ones; never drawn into colour). */
    const val CLIP = 32

    /** The even-odd fill rule (STENCIL and clip primitives). */
    const val EVEN_ODD = 64
}

/** How a stroke's free end ends. */
object Cap {
    const val BUTT = 0
    const val ROUND = 1
    const val SQUARE = 2
}

/** How two segments of a stroke meet (a miter beyond its limit is drawn as a bevel). */
object Join {
    const val MITER = 0
    const val ROUND = 1
    const val BEVEL = 2
}

/**
 * The primitives, struct of arrays: per primitive its [kind], [flags], [paint] (index into [Anchor.paints], −1 for a
 * clip primitive), [item] (index into [Anchor.items], −1 outside the objects) and its data `data[start[i] until
 * start[i + 1]]` as [PrimKind] lays it out (floats as their bits). The first [drawn] are in painter's order.
 */
class AnchorPrims(
    val kind: ByteArray,
    val flags: ByteArray,
    val paint: IntArray,
    val item: IntArray,
    val start: IntArray,
    val data: IntArray,
    val drawn: Int,
) {
    val size: Int get() = kind.size

    init {
        require(flags.size == size && paint.size == size && item.size == size && start.size == size + 1) { "primitive arrays disagree" }
        require(drawn in 0..size) { "drawn $drawn of $size" }
    }

    fun has(i: Int, flag: Int): Boolean = (flags[i].toInt() and flag) != 0

    /** The [k]th int of primitive [i]'s data. */
    fun int(i: Int, k: Int): Int = data[start[i] + k]

    /** The [k]th int of primitive [i]'s data as a float. */
    fun float(i: Int, k: Int): Float = Float.fromBits(data[start[i] + k])

    /** Corners of FACE [i]. */
    fun corners(i: Int): Int = data[start[i]]

    /** The [k]th corner (point) of FACE [i]. */
    fun corner(i: Int, k: Int): Int = data[start[i] + 2 + k]

    /** FACE [i]'s internal edges (bit k: corner k to corner k + 1). */
    fun hardEdges(i: Int): Int = data[start[i] + 1]

    fun bytes(): Long = size * 10L + start.size * 4L + data.size * 4L
}

/** Paint kinds. */
object PaintKind {
    const val COLOR: Byte = 0
    const val GRADIENT: Byte = 1
    const val PATTERN: Byte = 2
}

/**
 * The paints, struct of arrays: per paint its [kind]; its colour [argb] (the SVG's colour, not premultiplied; 0 for
 * gradients and patterns); [ref], the gradient or pattern; its [opacity] (fill or stroke opacity times the element
 * opacities folded into it); and [transform] (six per paint: `a b c d e f`), which maps anchor screen CSS px to the
 * gradient's or pattern's own space (bounding box, element and gradient transforms folded in).
 */
class AnchorPaints(
    val kind: ByteArray,
    val argb: IntArray,
    val ref: IntArray,
    val opacity: FloatArray,
    val transform: FloatArray,
) {
    val size: Int get() = kind.size

    init {
        require(argb.size == size && ref.size == size && opacity.size == size && transform.size == size * 6) { "paint arrays disagree" }
    }

    fun bytes(): Long = size * (1L + 4 + 4 + 4 + 24)
}

/** A filter of the SVG, with whether it only generates (never reads SourceGraphic or SourceAlpha). */
class AnchorFilter(val filter: SvgFilter, val generatorOnly: Boolean)

/** The kinds of the layer plan's nodes. */
enum class LayerKind { GROUND, RASTER, OBJECTS }

/**
 * The layer plan (`LAYR`): [nodes] in pre-order, each with [PlanNode.end], the index just past its subtree. Layers hold
 * groups and runs; groups hold groups and runs; runs are ranges of drawn primitives. [clips] are the clip paths groups
 * refer to.
 */
class AnchorPlan(val nodes: List<PlanNode>, val clips: List<AnchorClip>) {
    /** The children of node [i] (or of the root for −1): their indices. */
    fun children(i: Int): List<Int> {
        val out = ArrayList<Int>()
        var j = if (i < 0) 0 else i + 1
        val end = if (i < 0) nodes.size else nodes[i].end
        while (j < end) {
            out.add(j); j = nodes[j].end
        }
        return out
    }
}

/** A node of the layer plan. */
sealed class PlanNode {
    /** The index just past this node's subtree. */
    abstract val end: Int

    /** A layer: the ground (`g`), the relief raster (`t`), or the objects (`o` and `o2` joined). */
    class Layer(val layer: LayerKind, override val end: Int) : PlanNode()

    /**
     * A group with an effect. [sprite]: drawn into an offscreen target first and composited at its [painter] index
     * with [opacity] and [blend] (opacity that cannot fold into one paint, a blend mode, or a filter). [filter] is an
     * index into [Anchor.filters] (−1 for none), its region [filterBox] already resolved in anchor CSS px at the
     * anchor's camera (left, top, right, bottom). [clip] is an index into [AnchorPlan.clips] (−1 for none).
     * [screenFixed]: everything in it is fixed to the screen.
     */
    class Group(
        val opacity: Float,
        val blend: SvgBlend,
        val filter: Int,
        val filterBox: DoubleArray?,
        val clip: Int,
        val sprite: Boolean,
        val screenFixed: Boolean,
        val painter: Int,
        override val end: Int,
    ) : PlanNode()

    /** Drawn primitives [first] until [last] (exclusive), in painter's order. */
    class Run(val first: Int, val last: Int, override val end: Int) : PlanNode()
}

/**
 * A clip path: the union of its children, each a STENCIL primitive among the clip primitives ([first] until [last]),
 * in anchor screen space; itself clipped by [parent] (−1 for none). [units] are the SVG's (bounding-box clips are
 * resolved at the anchor's camera).
 */
class AnchorClip(val id: String, val units: SvgUnits, val first: Int, val last: Int, val parent: Int)

/** OSM element kinds of an item's owner. */
object OwnerType {
    const val NONE: Byte = 0
    const val NODE: Byte = 1
    const val WAY: Byte = 2
    const val RELATION: Byte = 3

    /** An area the source kept no element type for: a way or a relation with that id. */
    const val AREA: Byte = 4

    /** An owner with no OSM element (a census tree, a passer-by): id 0. */
    const val OTHER: Byte = 5
}

/**
 * The items (`ITEM`): the painter-order units of the objects (a building, a part, a tree, a lamp, a door, …), each with
 * its primitive range, its owner, its kind, the points the engine's radius cut tested (one or two, NaN for none: kept
 * while either lies within the radius) and the point and offset its depth was sorted by.
 */
class AnchorItems(
    val first: IntArray,
    val last: IntArray,
    val ownerType: ByteArray,
    val ownerId: LongArray,
    /** Index into [kinds]. */
    val kind: IntArray,
    val kinds: List<String>,
    /** fx0, fz0, fx1, fz1 per item, region frame metres. */
    val far: DoubleArray,
    /** sx, sy, sz per item, region frame metres: the sort point; sy is the sort height. */
    val sort: DoubleArray,
    /** Metres the engine added to the sort point's depth. */
    val sortOffset: DoubleArray,
    /** The depth the engine sorted the item by at the anchor's camera. */
    val depth: DoubleArray,
) {
    val size: Int get() = first.size

    /** The reference point's x (the first point the radius cut tested). */
    fun refX(i: Int): Double = far[i * 4]

    /** The reference point's z. */
    fun refZ(i: Int): Double = far[i * 4 + 1]

    /** Whether the engine's radius cut about ([tx], [tz]) with radius [rad] drops item [i] (its `far` test). */
    fun cut(i: Int, tx: Double, tz: Double, rad: Double): Boolean {
        val o = i * 4
        if (far[o].isNaN()) return false
        return PyMath.hypot(far[o] - tx, far[o + 1] - tz) > rad && PyMath.hypot(far[o + 2] - tx, far[o + 3] - tz) > rad
    }

    fun bytes(): Long = size * (4L + 4 + 1 + 8 + 4 + 32 + 24 + 8 + 8)
}

/**
 * A text run (`TEXT`): its string and font as the SVG gives them, its paint and folded opacity, the affine from its
 * text space to anchor CSS px ([transform], `a b c d e f`), its origin ([x], [y], text space) and the height of the
 * ground plane it lies on (its decal's plane in motion), metres.
 */
class AnchorText(
    val text: String,
    val family: String,
    val size: Float,
    val weight: Int,
    val italic: Boolean,
    val letterSpacing: Float,
    /** 0 start, 1 middle, 2 end. */
    val anchor: Int,
    val x: Float,
    val y: Float,
    val paint: Int,
    val opacity: Float,
    val transform: FloatArray,
    val groundHeight: Float,
)

/** A pattern (`PATT`): its SVG fragment, re-rasterised on the device. */
class AnchorPattern(val id: String, val svg: String)

/** The relief raster (`IMAG`): its encoded bytes ([ext], e.g. "jpg") and its clip path's SVG, if any. */
class AnchorImage(val ext: String, val data: ByteArray, val clip: String?)

/**
 * The screen-space part (`SCRN`): the horizon row at the anchor's camera ([horizon], CSS px), the anchor's screen-fixed
 * elements as an SVG document ([svg]), and the screen spec ([spec], JSON: the camera configuration and, when given,
 * the theme snapshot ScreenLayers needs).
 */
class AnchorScreen(val horizon: Double, val svg: String, val spec: String)
