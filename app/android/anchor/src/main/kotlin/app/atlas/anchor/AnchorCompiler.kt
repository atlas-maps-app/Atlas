package app.atlas.anchor

import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgBounds
import app.atlas.mapview.svg.SvgClip
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFe
import app.atlas.mapview.svg.SvgFilter
import app.atlas.mapview.svg.SvgFont
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgPathData
import app.atlas.mapview.svg.SvgShape
import app.atlas.mapview.svg.SvgStyle
import app.atlas.mapview.svg.SvgText
import app.atlas.mapview.svg.SvgUnits
import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.Raster
import app.atlas.render.live.LiveRender
import app.atlas.render.live.RegionFrame
import app.atlas.render.live.WorldTape
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What an anchor is, beyond its render: where, which kind, which moment, and the versions that make it current. */
class AnchorMeta(
    val regionId: String = "",
    val frame: RegionFrame = RegionFrame.living(),
    val kind: AnchorKind = AnchorKind.REST,
    val moment: String = "",
    val themeKey: String = "",
    val time: String? = null,
    val bandTolerance: Double = 0.0,
    val view: String? = null,
    val engineVersion: String = "dev",
    /** The scene source's hash (32 bytes); null hashes the render's own layers. */
    val sourceHash: ByteArray? = null,
    /** More of the screen spec, as JSON members (`"theme": {…}`), or null. */
    val spec: String? = null,
)

/**
 * One render under a world tape, ready to compile: the frame's [camera] and configuration ([cfg]), the parsed ground
 * layer and objects layers (`o`, then `o2`), the sealed [tape], the items' runs of the objects group's top-level
 * elements ([WorldTape.items]' `elementRuns` over the objects layers' text), and the relief raster.
 */
class AnchorSource(
    val camera: City.Camera,
    val cfg: Map<String, Any?>,
    val ground: SvgDocument,
    val objects: List<SvgDocument>,
    val tape: WorldTape,
    val itemRuns: IntArray?,
    val raster: Raster? = null,
    val meta: AnchorMeta = AnchorMeta(),
) {
    companion object {
        /**
         * The source of a live render made under [tape] (sealed here): its layers parsed, its items' runs read from the
         * objects layers' text.
         */
        fun of(render: LiveRender, tape: WorldTape, meta: AnchorMeta): AnchorSource {
            tape.seal()
            val objs = listOfNotNull(render.o, render.o2)
            val runs = if (tape.items.size > 0) tape.items.elementRuns(objs) else null
            val hash = meta.sourceHash ?: sha256(render.layers)
            val m = AnchorMeta(
                meta.regionId, render.frame, meta.kind, render.moment, render.themeKey, meta.time, render.tolerance, render.view,
                meta.engineVersion, hash, meta.spec,
            )
            return AnchorSource(
                Amsterdam.cameraOf(render.cfg), render.cfg, SvgParser.parse(render.g), objs.map { SvgParser.parse(it) }, tape, runs, render.raster, m,
            )
        }

        /** SHA-256 of [layers]' UTF-8 text, in order. */
        fun sha256(layers: List<String>): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (l in layers) md.update(l.toByteArray(Charsets.UTF_8))
            return md.digest()
        }
    }
}

/** What a compile did: counts per lift class and primitive kind, area and landing checks, and time. */
class CompileReport {
    /** Points per lift class, by what they belong to: path vertices, circle and ellipse centres, rect corners, text points. */
    val pathVertices = IntArray(LiftClass.COUNT)
    val centres = IntArray(LiftClass.COUNT)
    val corners = IntArray(LiftClass.COUNT)
    val textPoints = IntArray(LiftClass.COUNT)
    val clipPoints = IntArray(LiftClass.COUNT)
    val dashPoints = IntArray(LiftClass.COUNT)

    /** Paths with no vertex on the tape. */
    var pathsOffTape = 0
    var paths = 0

    /** Ground-class points whose element has an owner (must stay 0). */
    var groundOnOwners = 0

    /** Elements drawn, items, and objects-group elements outside every item. */
    var elements = 0
    var items = 0
    var looseObjects = 0

    lateinit var lift: LiftStats
    var fills = 0; var faces = 0; var tris = 0; var cutFaces = 0; var stencils = 0; var earcutMisses = 0
    var worstFillArea = 0.0
    var strokes = 0; var segs = 0; var joins = 0; var miters = 0; var bevels = 0; var dots = 0; var squareDots = 0
    var ellipses = 0; var tiles = 0
    val maxResidual = DoubleArray(LiftClass.COUNT)
    var residualOverflow = 0
    var nanos = 0L

    /** Each point's SVG coordinate (anchor CSS px, x and y), kept when [keepSvg] is set: for the landing gate. */
    var keepSvg = false
    var svg: DoubleArray? = null

    /** Each fill drawn (its element, transform and primitives), kept when [keepFills] is set: for the area gate. */
    var keepFills = false
    val fillRecords = ArrayList<FillRecord>()

    /** A fill: [node] of layer [doc] under [ctm], drawn as primitives [first] until [last]. */
    class FillRecord(val node: SvgShape, val doc: Int, val ctm: FloatArray?, val first: Int, val last: Int)

    fun summary(): String {
        fun cls(a: IntArray) = LiftClass.NAMES.indices.joinToString(", ") { "${LiftClass.NAMES[it]} ${a[it]}" }
        val pv = pathVertices.sum().coerceAtLeast(1)
        return buildString {
            append("compile %.1f ms; %d elements, %d items\n".format(Locale.ROOT, nanos / 1e6, elements, items))
            append("  path vertices: ${cls(pathVertices)} (taped %.3f %%); paths %d, off the tape %d\n".format(Locale.ROOT, 100.0 * pathVertices[0] / pv, paths, pathsOffTape))
            append("  circle centres: ${cls(centres)}\n  rect corners: ${cls(corners)}\n  text points: ${cls(textPoints)}\n  clip points: ${cls(clipPoints)}\n")
            append("  ground points on owned elements: $groundOnOwners; tape lookups ${lift.lookups}, found ${lift.found}, ambiguous ${lift.ambiguous}; screen elements ${lift.screenElements}, hung elements ${lift.hungElements}, hang points ${lift.hangPoints}\n")
            append("  fills $fills: convex faces $faces, cut fills $tris into $cutFaces faces, stencils $stencils (Earcut misses $earcutMisses), worst area %.2e\n".format(Locale.ROOT, worstFillArea))
            append("  strokes $strokes: segs $segs, joins $joins, miters $miters, bevels $bevels, dots $dots (square dots $squareDots); ellipses $ellipses, tiles $tiles\n")
            append("  max residual: ${LiftClass.NAMES.indices.joinToString(", ") { "%s %.4f".format(Locale.ROOT, LiftClass.NAMES[it], maxResidual[it]) }} px; overflow $residualOverflow")
        }
    }
}

/**
 * Lifts one exact engine render into an [Anchor] (plan-gpu D2): every drawn element of the ground and objects layers,
 * in painter's order, becomes primitives on points lifted into the anchor camera's eye frame, with the paints, groups,
 * clips, filters, items and text runs the GPU needs to draw it at any camera, and exactly at its own.
 */
object AnchorCompiler {
    /** The anchor of [src]; [report], when given, gets the counts. */
    fun compile(src: AnchorSource, report: CompileReport? = null): Anchor {
        val t0 = System.nanoTime()
        val a = Compile(src, report).run()
        report?.nanos = System.nanoTime() - t0
        return a
    }

    /** Whether [f] only generates: none of its primitives reads SourceGraphic or SourceAlpha. */
    fun generatorOnly(f: SvgFilter): Boolean {
        val results = HashSet<String>()
        for ((k, p) in f.primitives.withIndex()) {
            val ins = when (p) {
                is SvgFe.Turbulence, is SvgFe.Flood -> emptyList()
                is SvgFe.Merge -> p.inputs
                is SvgFe.Composite, is SvgFe.DisplacementMap -> listOf(p.input, p.input2)
                else -> listOf(p.input)
            }
            for (i in ins) {
                when {
                    i == null -> if (k == 0) return false
                    i == SvgFe.SOURCE_GRAPHIC || i == SvgFe.SOURCE_ALPHA -> return false
                    i !in results && i != "BackgroundImage" -> return false
                }
            }
            p.result?.let { results.add(it) }
        }
        return true
    }

    /** The OSM element behind an owner the engine drew (its dict's `id`): areas as [OwnerType.AREA], lines as ways, points as nodes. */
    fun osmOwner(o: Any?): Pair<Byte, Long> {
        if (o == null) return OwnerType.NONE to 0L
        val m = o as? Map<*, *> ?: return OwnerType.OTHER to 0L
        val id = (m["id"] as? Number)?.toLong() ?: return OwnerType.OTHER to 0L
        return when {
            m["poly"] != null -> OwnerType.AREA to id
            m["pts"] != null -> OwnerType.WAY to id
            "x" in m -> OwnerType.NODE to id
            else -> OwnerType.AREA to id
        }
    }
}

/** The geometry of one element being emitted: its vertices (points and SVG coordinates) in subpaths. */
internal class ElementShape {
    var n = 0
    var pt = IntArray(256)
    var x = DoubleArray(256)
    var y = DoubleArray(256)
    var subs = 0
    var subStart = IntArray(16)
    var subCount = IntArray(16)
    var subClosed = BooleanArray(16)

    fun reset() { n = 0; subs = 0 }

    fun vertex(p: Int, px: Double, py: Double) {
        if (n == pt.size) { pt = pt.copyOf(n * 2); x = x.copyOf(n * 2); y = y.copyOf(n * 2) }
        pt[n] = p; x[n] = px; y[n] = py; n++
    }

    fun sub(start: Int, count: Int, closed: Boolean) {
        if (subs == subStart.size) { subStart = subStart.copyOf(subs * 2); subCount = subCount.copyOf(subs * 2); subClosed = subClosed.copyOf(subs * 2) }
        subStart[subs] = start; subCount[subs] = count; subClosed[subs] = closed; subs++
    }
}

/** The primitives as they are emitted, struct of arrays. */
internal class PrimBuilder {
    private val kind = ByteList(4096)
    private val flags = ByteList(4096)
    private val paint = IntList(4096)
    private val item = IntList(4096)
    private val start = IntList(4096)
    private val data = IntList(16384)
    private val tri = IntList(16384)
    private var open = false
    val counts = IntArray(PrimKind.COUNT)

    val count: Int get() = kind.size
    val triangleCount: Int get() = tri.size

    fun begin(k: Byte, f: Int, p: Int, it: Int) {
        check(!open) { "a primitive is open" }
        kind.add(k); flags.add(f.toByte()); paint.add(p); item.add(it); start.add(data.size)
        counts[k.toInt()]++
        open = true
    }

    fun put(v: Int) { data.add(v) }
    fun end() { open = false }
    fun triangle(p: Int) { tri.add(p) }

    fun build(drawn: Int): Pair<AnchorPrims, IntArray> {
        val st = IntArray(count + 1)
        for (i in 0 until count) st[i] = start[i]
        st[count] = data.size
        return AnchorPrims(kind.toArray(), flags.toArray(), paint.toArray(), item.toArray(), st, data.toArray(), drawn) to tri.toArray()
    }
}

/** One compile: two passes over the layers (allocate and tape, then lift and emit), then the anchor. */
private class Compile(val src: AnchorSource, val report: CompileReport?) {
    val cam = anchorCamera(src.camera)
    val docs: List<SvgDocument> = listOf(src.ground) + src.objects
    val bounds = docs.map { SvgBounds(it) }
    val pts = PointTable(16384)
    val lifter = Lifter(src.tape, cam, pts)
    val prims = PrimBuilder()
    val paints = PaintTable()
    val plan = PlanBuilder()
    val fills = Fills(prims)
    val strokes = Strokes(prims)
    val shape = ElementShape()
    val width = cam.width
    val horizon = cam.horizonY()

    val filters = ArrayList<AnchorFilter>()
    val filterIndex = HashMap<Pair<Int, String>, Int>()
    val clipDrafts = ArrayList<ClipDraft>()
    val clipIndex = HashMap<Any, Int>()
    val texts = ArrayList<AnchorText>()
    val events = ArrayList<Any>()

    // items: the objects groups' top-level elements, by run
    val items = src.tape.items
    val runs = src.itemRuns
    var objectsSeen = 0
    var currentItem = -1
    val itemFirst = IntArray(items.size) { -1 }
    val itemLast = IntArray(items.size) { -1 }

    // subpaths of every element, global
    var subStarts = IntList(4096)
    var subCounts = IntList(4096)
    var subClosed = ByteList(4096)

    // screen-fixed elements, for SCRN
    val screenNodes = docs.map { ArrayList<SvgNode>() }

    class OpenLayer(val kind: LayerKind)
    class OpenGroup(val opacity: Float, val blend: SvgBlend, val filter: Int, val box: DoubleArray?, val clip: Int, val sprite: Boolean)
    object Close

    class Element(
        val node: SvgNode, val doc: Int, val ctm: FloatArray?, val layer: LayerKind, val item: Int, val gen: Boolean,
        val first: Int, val count: Int, val sub0: Int, val subN: Int, val owner: Int,
        val fill: Int, val stroke: Int, val opacity: Float,
    )

    class ClipDraft(val clip: SvgClip, val units: SvgUnits, val elements: ArrayList<Element>, var parent: Int)

    fun run(): Anchor {
        // pass 1: walk, resolve paints, allocate and tape every element's points
        walkLayer(src.ground, 0, LayerKind.GROUND)
        if (src.raster != null) { events.add(OpenLayer(LayerKind.RASTER)); events.add(Close) }
        for (d in 1 until docs.size) walkLayer(docs[d], d, LayerKind.OBJECTS)
        if (runs != null) {
            var total = 0
            for (k in 0 until items.size) total += runs[k * 2 + 1]
            check(total == objectsSeen) { "the items' runs cover $total elements of the objects groups, the parser read $objectsSeen" }
        }
        // pass 2: lift the rest, emit primitives and the plan
        for (e in events) {
            when (e) {
                is OpenLayer -> plan.openLayer(e.kind, prims.count).also { if (e.kind == LayerKind.RASTER) rasterLayer = true }
                is OpenGroup -> plan.openGroup(e.opacity, e.blend, e.filter, e.box, e.clip, e.sprite, prims.count)
                is Close -> closeNode()
                is Element -> emit(e)
            }
        }
        val drawn = prims.count
        val clips = emitClips()
        val (anchorPrims, triangles) = prims.build(drawn)
        val points = pts.build(cam)
        val head = head(points, anchorPrims)
        val anchor = Anchor(
            head, points, anchorPrims, triangles, paints.build(), paints.gradients, plan.build(clips), filters, items(),
            texts, paints.patterns, src.raster?.let { AnchorImage(it.ext, it.data, it.clip) }, screen(),
            src.meta.engineVersion, src.meta.sourceHash ?: AnchorSource.sha256(emptyList()),
        )
        report?.let { r ->
            r.lift = lifter.stats
            r.fills = fills.fills; r.faces = fills.faces; r.tris = fills.tris; r.cutFaces = fills.cutFaces; r.stencils = fills.stencils; r.earcutMisses = fills.earcutMisses
            r.worstFillArea = fills.worstArea
            r.strokes = strokes.strokes; r.segs = strokes.segs; r.joins = strokes.joins; r.miters = strokes.miters; r.bevels = strokes.bevels
            r.dots = strokes.dots; r.squareDots = strokes.squareDots
            r.ellipses = prims.counts[PrimKind.ELL.toInt()]; r.tiles = prims.counts[PrimKind.TILE.toInt()]
            pts.maxResidual.copyInto(r.maxResidual); r.residualOverflow = pts.overflow
            if (r.keepSvg) r.svg = DoubleArray(pts.size * 2) { if (it % 2 == 0) pts.svgX(it / 2) else pts.svgY(it / 2) }
            r.items = items.size
        }
        return anchor
    }

    var rasterLayer = false

    private fun closeNode() {
        if (rasterLayer) {
            // the raster layer holds no primitives; the plan keeps it as a marker between the ground and the objects
            rasterLayer = false
            plan.keepEmpty()
        }
        plan.close()
    }

    // ------------------------------------------------------------------ pass 1

    private fun walkLayer(doc: SvgDocument, d: Int, kind: LayerKind) {
        events.add(OpenLayer(kind))
        val objects = if (d >= 1) objectsGroup(doc) else null
        visit(doc.root, null, d, kind, false, objects)
        events.add(Close)
    }

    /** The objects group (`<g stroke-linejoin="round" …>` inside the frame's clip group): the first group two levels down. */
    private fun objectsGroup(doc: SvgDocument): SvgGroup? {
        for (a in doc.root.children) {
            if (a !is SvgGroup) continue
            for (b in a.children) if (b is SvgGroup) return b
            return null
        }
        return null
    }

    private fun visit(node: SvgNode, parent: FloatArray?, d: Int, layer: LayerKind, inGen: Boolean, objects: SvgGroup?) {
        val ctm = SvgBounds.compose(parent, node.transform)
        val f = node.filter
        val gen = inGen || (f != null && AnchorCompiler.generatorOnly(f))
        when (node) {
            is SvgGroup -> {
                val effect = node.needsLayer || node.clip != null
                if (effect) openGroup(node, ctm, d, gen)
                if (node === objects && runs != null) {
                    for (c in node.children) {
                        currentItem = itemAt(objectsSeen)
                        objectsSeen++
                        if (currentItem < 0) report?.let { it.looseObjects++ }
                        visit(c, ctm, d, layer, gen, null)
                    }
                    currentItem = -1
                } else {
                    for (c in node.children) visit(c, ctm, d, layer, gen, objects)
                }
                if (effect) events.add(Close)
            }
            is SvgShape, is SvgText -> {
                val st = if (node is SvgShape) node.style else (node as SvgText).style
                val direct = f == null && node.clip == null && node.blend == SvgBlend.NORMAL && (node.opacity >= 1f || !(st.hasFill && st.hasStroke))
                if (!direct) openGroup(node, ctm, d, gen)
                element(node, ctm, d, layer, gen, if (direct) node.opacity else 1f)
                if (!direct) events.add(Close)
            }
        }
    }

    /** The item holding the objects group's top-level element [index], or −1. */
    private fun itemAt(index: Int): Int {
        val r = runs ?: return -1
        var lo = 0; var hi = r.size / 2 - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (r[mid * 2] <= index) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        while (found >= 0 && r[found * 2 + 1] == 0) found--
        return if (found >= 0 && index < r[found * 2] + r[found * 2 + 1]) found else -1
    }

    private fun openGroup(node: SvgNode, ctm: FloatArray?, d: Int, gen: Boolean) {
        val f = node.filter
        var fi = -1
        var box: DoubleArray? = null
        if (f != null) {
            fi = filterIndex.getOrPut(d to f.id) { filters.add(AnchorFilter(f, AnchorCompiler.generatorOnly(f))); filters.size - 1 }
            box = filterBox(node, f, ctm, d)
        }
        val ci = node.clip?.let { clip(it, node, ctm, d) } ?: -1
        val sprite = f != null || node.blend != SvgBlend.NORMAL || node.opacity < 1f
        events.add(OpenGroup(node.opacity, node.blend, fi, box, ci, sprite))
    }

    /** The filter region at the anchor's camera, anchor CSS px (the box of its corners under [ctm]), or null when it has none. */
    private fun filterBox(node: SvgNode, f: SvgFilter, ctm: FloatArray?, d: Int): DoubleArray? {
        val r = f.region
        val x: Double; val y: Double; val w: Double; val h: Double
        if (f.units == SvgUnits.BOUNDING_BOX) {
            val b = FloatArray(4)
            if (!bounds[d].of(node, b)) return null
            val bw = (b[2] - b[0]).toDouble(); val bh = (b[3] - b[1]).toDouble()
            x = b[0] + r[0] * bw; y = b[1] + r[1] * bh; w = r[2] * bw; h = r[3] * bh
        } else {
            x = r[0].toDouble(); y = r[1].toDouble(); w = r[2].toDouble(); h = r[3].toDouble()
        }
        val out = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for ((px, py) in listOf(x to y, x + w to y, x to y + h, x + w to y + h)) {
            val sx = tx(ctm, px, py); val sy = ty(ctm, px, py)
            out[0] = min(out[0], sx); out[1] = min(out[1], sy); out[2] = max(out[2], sx); out[3] = max(out[3], sy)
        }
        return out
    }

    /** The clip path [c] as the node [owner] (whose user space is [ctm]) uses it: registered once, its children allocated. */
    private fun clip(c: SvgClip, owner: SvgNode, ctm: FloatArray?, d: Int): Int {
        // a box-relative clip depends on its owner; a user-space one on the space it is used in
        val key: Any = if (c.units == SvgUnits.BOUNDING_BOX) Triple(c, owner, d) else Triple(c, ctm?.toList(), d)
        clipIndex[key]?.let { return it }
        var m = ctm
        if (c.units == SvgUnits.BOUNDING_BOX) {
            val b = FloatArray(4)
            if (bounds[d].of(owner, b)) m = SvgBounds.compose(ctm, floatArrayOf(b[2] - b[0], 0f, 0f, b[3] - b[1], b[0], b[1]))
            else m = SvgBounds.compose(ctm, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f))
        }
        val draft = ClipDraft(c, c.units, ArrayList(), -1)
        val index = clipDrafts.size
        clipDrafts.add(draft)
        clipIndex[key] = index
        for (child in c.children) {
            if (child !is SvgShape) continue
            val cm = SvgBounds.compose(m, child.transform)
            allocate(child, cm, d, LayerKind.GROUND, -1, false, -2, -2, 1f)?.let { draft.elements.add(it) }
        }
        c.clip?.let { draft.parent = clip(it, owner, ctm, d) }
        return index
    }

    private fun element(node: SvgNode, ctm: FloatArray?, d: Int, layer: LayerKind, gen: Boolean, opacity: Float) {
        val doc = docs[d]
        val e = when (node) {
            is SvgShape -> {
                val st = node.style
                val fill = if (st.hasFill) paints.resolve(st.fill, st.fillOpacity * opacity, node, ctm, d, doc, bounds[d]) else -1
                val stroke = if (st.hasStroke) paints.resolve(st.stroke, st.strokeOpacity * opacity, node, ctm, d, doc, bounds[d]) else -1
                if (fill < 0 && stroke < 0) return
                allocate(node, ctm, d, layer, currentItem, gen, fill, stroke, opacity)
            }
            is SvgText -> {
                val st = node.style
                if (!st.hasFill) return
                val fill = paints.resolve(st.fill, st.fillOpacity * opacity, node, ctm, d, doc, bounds[d])
                if (fill < 0) return
                allocate(node, ctm, d, layer, currentItem, gen, fill, -1, opacity)
            }
            else -> null
        } ?: return
        events.add(e)
    }

    private fun tx(m: FloatArray?, x: Double, y: Double): Double = if (m == null) x else m[0] * x + m[2] * y + m[4]
    private fun ty(m: FloatArray?, x: Double, y: Double): Double = if (m == null) y else m[1] * x + m[3] * y + m[5]

    /** Allocates [node]'s points (SVG coordinates under [ctm]) and looks them up on the tape. */
    private fun allocate(node: SvgNode, ctm: FloatArray?, d: Int, layer: LayerKind, item: Int, gen: Boolean, fill: Int, stroke: Int, opacity: Float): Element? {
        val first = pts.size
        val sub0 = subStarts.size
        when (node) {
            is SvgShape -> when (node.kind) {
                SvgShape.Kind.PATH -> path(node, ctm, d)
                SvgShape.Kind.RECT -> rect(node.geometry, ctm)
                SvgShape.Kind.CIRCLE -> pts.add(tx(ctm, node.geometry[0].toDouble(), node.geometry[1].toDouble()), ty(ctm, node.geometry[0].toDouble(), node.geometry[1].toDouble()))
                SvgShape.Kind.ELLIPSE -> pts.add(tx(ctm, node.geometry[0].toDouble(), node.geometry[1].toDouble()), ty(ctm, node.geometry[0].toDouble(), node.geometry[1].toDouble()))
            }
            is SvgText -> textQuad(node, ctm)
            else -> return null
        }
        val count = pts.size - first
        if (count == 0) return null
        val itemOwner = if (item >= 0) items.owner(item) else -1
        val owner = when (node) {
            is SvgText -> lifter.tapeElement(first, 1, itemOwner, true)
            else -> lifter.tapeElement(first, count, itemOwner, ctm == null)
        }
        return Element(node, d, ctm, layer, item, gen, first, count, sub0, subStarts.size - sub0, owner, fill, stroke, opacity)
    }

    private fun path(s: SvgShape, ctm: FloatArray?, d: Int) {
        val verbs = docs[d].path.verbs; val xy = docs[d].path.coords
        var k = s.coordStart
        var subFirst = -1 // the open subpath's first point
        var startX = 0.0; var startY = 0.0
        var lastX = Double.NaN; var lastY = Double.NaN
        fun endSub(closed: Boolean) {
            if (subFirst < 0) return
            var n = pts.size - subFirst
            if (closed && n > 1 && pts.svgX(pts.size - 1) == pts.svgX(subFirst) && pts.svgY(pts.size - 1) == pts.svgY(subFirst)) {
                // the closing vertex repeats the first: Z closes it
                pts.dropLast(); n--
            }
            subStarts.add(subFirst); subCounts.add(n); subClosed.add(if (closed) 1 else 0)
            subFirst = -1
        }
        for (v in s.verbStart until s.verbEnd) {
            when (verbs[v]) {
                SvgPathData.MOVE -> {
                    endSub(false)
                    val x = tx(ctm, xy[k].toDouble(), xy[k + 1].toDouble()); val y = ty(ctm, xy[k].toDouble(), xy[k + 1].toDouble())
                    k += 2
                    subFirst = pts.add(x, y); startX = x; startY = y; lastX = x; lastY = y
                }
                SvgPathData.LINE -> {
                    val x = tx(ctm, xy[k].toDouble(), xy[k + 1].toDouble()); val y = ty(ctm, xy[k].toDouble(), xy[k + 1].toDouble())
                    k += 2
                    if (subFirst < 0) { subFirst = pts.add(startX, startY); lastX = startX; lastY = startY }
                    if (x == lastX && y == lastY) continue
                    pts.add(x, y); lastX = x; lastY = y
                }
                else -> { endSub(true); lastX = startX; lastY = startY }
            }
        }
        endSub(false)
    }

    private fun rect(g: FloatArray, ctm: FloatArray?) {
        val x = g[0].toDouble(); val y = g[1].toDouble(); val w = g[2].toDouble(); val h = g[3].toDouble()
        val rx = g[4].toDouble(); val ry = g[5].toDouble()
        val first = pts.size
        if (rx > 0 && ry > 0) {
            // corners as arcs of ARC_STEPS segments each, clockwise from the top left
            val cxs = doubleArrayOf(x + w - rx, x + w - rx, x + rx, x + rx)
            val cys = doubleArrayOf(y + ry, y + h - ry, y + h - ry, y + ry)
            val a0 = doubleArrayOf(-Math.PI / 2, 0.0, Math.PI / 2, Math.PI)
            for (c in 0 until 4) for (s in 0..ARC_STEPS) {
                val a = a0[c] + Math.PI / 2 * s / ARC_STEPS
                val px = cxs[c] + rx * cos(a); val py = cys[c] + ry * sin(a)
                pts.add(tx(ctm, px, py), ty(ctm, px, py))
            }
        } else {
            for ((px, py) in listOf(x to y, x + w to y, x + w to y + h, x to y + h)) pts.add(tx(ctm, px, py), ty(ctm, px, py))
        }
        subStarts.add(first); subCounts.add(pts.size - first); subClosed.add(1)
    }

    /** A text run's origin, then its tile quad's corners (top left, top right, bottom right, bottom left), under [ctm]. */
    private fun textQuad(t: SvgText, ctm: FloatArray?) {
        val f = t.font
        val ox = t.x.toDouble(); val oy = t.y.toDouble()
        pts.add(tx(ctm, ox, oy), ty(ctm, ox, oy))
        val len = t.text.length
        val w = len * f.size * 0.62 + max(0, len - 1) * f.letterSpacing
        val left = when (f.anchor) {
            SvgFont.Anchor.START -> ox
            SvgFont.Anchor.MIDDLE -> ox - w / 2
            SvgFont.Anchor.END -> ox - w
        } - f.size * 0.3
        val right = left + w + f.size * 0.6
        val top = oy - f.size * 1.05; val bottom = oy + f.size * 0.4
        for ((px, py) in listOf(left to top, right to top, right to bottom, left to bottom)) pts.add(tx(ctm, px, py), ty(ctm, px, py))
    }

    // ------------------------------------------------------------------ pass 2

    private fun emit(e: Element) {
        val node = e.node
        if (node is SvgText) {
            emitText(e, node); return
        }
        node as SvgShape
        lifter.liftRest(e.first, e.count, e.owner, e.layer == LayerKind.GROUND, screenHint(e))
        count(e)
        val screenFixed = allScreen(e.first, e.count)
        if (screenFixed) screenNodes[e.doc].add(node)
        val base = if (screenFixed) PrimFlag.SCREEN_FIXED else 0
        val wall = e.item >= 0 && items.kind(e.item) in Faces.WALL_KINDS
        val before = prims.count
        val st = node.style
        when (node.kind) {
            SvgShape.Kind.PATH, SvgShape.Kind.RECT -> {
                shape(e)
                if (e.fill >= 0) {
                    val opaque = paints.isOpaque(e.fill)
                    val f0 = prims.count
                    fills.fill(shape, st.evenOdd, opaque, base or (if (opaque) 0 else PrimFlag.TRANSLUCENT), e.fill, e.item, wall)
                    report?.let { r -> if (r.keepFills) r.fillRecords.add(CompileReport.FillRecord(node, e.doc, e.ctm, f0, prims.count)) }
                }
                if (e.stroke >= 0) stroke(e, st, base)
            }
            SvgShape.Kind.CIRCLE, SvgShape.Kind.ELLIPSE -> ellipse(e, node, base)
        }
        attach(e, before, screenFixed)
    }

    /** The primitives emitted since [before] join the plan and the element's item. */
    private fun attach(e: Element, before: Int, screenFixed: Boolean) {
        for (p in before until prims.count) plan.prim(p, screenFixed)
        if (e.item >= 0 && prims.count > before) {
            if (itemFirst[e.item] < 0) itemFirst[e.item] = before
            itemLast[e.item] = prims.count
        }
        report?.let { it.elements++ }
    }

    private fun screenHint(e: Element): Boolean {
        if (e.gen) return true
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (j in 0 until e.count) {
            val i = e.first + j
            x0 = min(x0, pts.svgX(i)); x1 = max(x1, pts.svgX(i)); y1 = max(y1, pts.svgY(i))
        }
        val node = e.node
        if (node is SvgShape && (node.kind == SvgShape.Kind.CIRCLE || node.kind == SvgShape.Kind.ELLIPSE)) {
            val r = node.geometry[2].toDouble()
            val ry = if (node.kind == SvgShape.Kind.ELLIPSE) node.geometry[3].toDouble() else r
            x0 -= r; x1 += r; y1 += ry
        }
        return (x0 <= 0.5 && x1 >= width - 0.5) || y1 <= horizon
    }

    private fun allScreen(first: Int, count: Int): Boolean {
        for (j in 0 until count) if (pts.liftOf(first + j) != LiftClass.SCREEN) return false
        return true
    }

    /** Fills [shape] with element [e]'s points and subpaths. */
    private fun shape(e: Element) {
        shape.reset()
        for (s in 0 until e.subN) {
            val st = subStarts[e.sub0 + s]; val c = subCounts[e.sub0 + s]
            val from = shape.n
            for (j in 0 until c) shape.vertex(st + j, pts.svgX(st + j), pts.svgY(st + j))
            shape.sub(from, c, subClosed[e.sub0 + s].toInt() != 0)
        }
    }

    private fun scale(ctm: FloatArray?): Double = if (ctm == null) 1.0 else sqrt(abs(ctm[0].toDouble() * ctm[3] - ctm[1].toDouble() * ctm[2]))

    private fun stroke(e: Element, st: SvgStyle, base: Int) {
        val k = scale(e.ctm)
        val cap = when (st.cap) { SvgStyle.Cap.BUTT -> Cap.BUTT; SvgStyle.Cap.ROUND -> Cap.ROUND; SvgStyle.Cap.SQUARE -> Cap.SQUARE }
        val join = when (st.join) { SvgStyle.Join.MITER -> Join.MITER; SvgStyle.Join.ROUND -> Join.ROUND; SvgStyle.Join.BEVEL -> Join.BEVEL }
        val dashes = st.dashes?.let { d -> DoubleArray(d.size) { d[it] * k } }
        strokes.stroke(shape, st.strokeWidth * k, cap, join, st.miterLimit.toDouble(), dashes, st.dashOffset * k, base, e.stroke, e.item) { a, b, s, x, y ->
            val p = pts.add(x, y)
            lifter.liftBetween(p, a, b, s)
            report?.let { it.dashPoints[pts.liftOf(p).toInt()]++ }
            p
        }
    }

    private fun ellipse(e: Element, node: SvgShape, base: Int) {
        val g = node.geometry
        val rx0 = g[2].toDouble(); val ry0 = if (node.kind == SvgShape.Kind.ELLIPSE) g[3].toDouble() else rx0
        // the axes of the circle's image under the element's transform (an SVD of its 2 × 2 part)
        val m = e.ctm
        val a = (m?.get(0) ?: 1f) * rx0; val b = (m?.get(1) ?: 0f) * rx0; val c = (m?.get(2) ?: 0f) * ry0; val d = (m?.get(3) ?: 1f) * ry0
        val (rx, ry, rot) = axes(a, b, c, d)
        val st = node.style
        if (e.fill >= 0) {
            val opaque = paints.isOpaque(e.fill)
            prims.begin(PrimKind.ELL, base or (if (opaque) 0 else PrimFlag.TRANSLUCENT), e.fill, e.item)
            prims.put(e.first); prims.put(fb(rx)); prims.put(fb(ry)); prims.put(fb(rot)); prims.put(fb(0.0)); prims.end()
        }
        if (e.stroke >= 0 && st.strokeWidth > 0f) {
            prims.begin(PrimKind.ELL, base or PrimFlag.TRANSLUCENT, e.stroke, e.item)
            prims.put(e.first); prims.put(fb(rx)); prims.put(fb(ry)); prims.put(fb(rot)); prims.put(fb(st.strokeWidth * scale(m))); prims.end()
        }
    }

    private fun fb(v: Double): Int = java.lang.Float.floatToRawIntBits(v.toFloat())

    /** The semi-axes and rotation of the ellipse image of the unit circle under (a c; b d). */
    private fun axes(a: Double, b: Double, c: Double, d: Double): Triple<Double, Double, Double> {
        if (b == 0.0 && c == 0.0) return Triple(abs(a), abs(d), 0.0)
        // singular values of [[a, c], [b, d]]
        val e = (a + d) / 2; val f = (a - d) / 2; val g = (b + c) / 2; val h = (b - c) / 2
        val q = sqrt(e * e + h * h); val r = sqrt(f * f + g * g)
        val s1 = q + r; val s2 = abs(q - r)
        val a1 = atan2(g, f); val a2 = atan2(h, e)
        val theta = (a2 + a1) / 2
        return Triple(s1, s2, theta)
    }

    private fun emitText(e: Element, t: SvgText) {
        val origin = e.first
        val height = if (pts.liftOf(origin) == LiftClass.TAPED) lifter.heightOf(origin) else 0.0
        lifter.liftDecal(e.first, e.count, height, e.owner, origin)
        count(e)
        val screenFixed = allScreen(e.first, e.count)
        if (screenFixed) screenNodes[e.doc].add(t)
        val before = prims.count
        val m = e.ctm ?: PaintTable.IDENTITY
        val f = t.font
        texts.add(
            AnchorText(
                t.text, f.family, f.size, f.weight, f.italic, f.letterSpacing,
                when (f.anchor) { SvgFont.Anchor.START -> 0; SvgFont.Anchor.MIDDLE -> 1; SvgFont.Anchor.END -> 2 },
                t.x, t.y, e.fill, e.opacity, m.copyOf(), height.toFloat(),
            ),
        )
        prims.begin(PrimKind.TILE, PrimFlag.TRANSLUCENT or (if (screenFixed) PrimFlag.SCREEN_FIXED else 0), e.fill, e.item)
        prims.put(texts.size - 1)
        for (j in 1..4) prims.put(e.first + j)
        prims.end()
        attach(e, before, screenFixed)
    }

    /** Counts element [e]'s points by lift class, for the report. */
    private fun count(e: Element) {
        val r = report ?: return
        val node = e.node
        val into = when {
            node is SvgText -> r.textPoints
            node is SvgShape && node.kind == SvgShape.Kind.PATH -> r.pathVertices
            node is SvgShape && node.kind == SvgShape.Kind.RECT -> r.corners
            else -> r.centres
        }
        var taped = 0
        for (j in 0 until e.count) {
            val c = pts.liftOf(e.first + j).toInt()
            into[c]++
            if (c == LiftClass.TAPED.toInt()) taped++
            if (c == LiftClass.GROUND.toInt() && e.owner != 0) r.groundOnOwners++
        }
        if (node is SvgShape && node.kind == SvgShape.Kind.PATH) { r.paths++; if (taped == 0) r.pathsOffTape++ }
    }

    // ------------------------------------------------------------------ clips, items, head, screen

    private fun emitClips(): List<AnchorClip> {
        val out = ArrayList<AnchorClip>()
        for (draft in clipDrafts) {
            val first = prims.count
            for (e in draft.elements) {
                val node = e.node as SvgShape
                lifter.liftRest(e.first, e.count, e.owner, true, screenHint(e))
                report?.let { r -> for (j in 0 until e.count) r.clipPoints[pts.liftOf(e.first + j).toInt()]++ }
                var f = PrimFlag.CLIP or PrimFlag.STENCIL
                if (allScreen(e.first, e.count)) f = f or PrimFlag.SCREEN_FIXED
                if (node.style.evenOdd) f = f or PrimFlag.EVEN_ODD
                when (node.kind) {
                    SvgShape.Kind.PATH, SvgShape.Kind.RECT -> {
                        shape(e)
                        prims.begin(PrimKind.STENCIL, f, -1, -1)
                        val t0 = prims.triangleCount
                        for (s in 0 until shape.subs) {
                            val st = shape.subStart[s]; val c = shape.subCount[s]
                            for (j in 1 until c - 1) { prims.triangle(shape.pt[st]); prims.triangle(shape.pt[st + j]); prims.triangle(shape.pt[st + j + 1]) }
                        }
                        prims.put(t0); prims.put(prims.triangleCount - t0); prims.end()
                    }
                    SvgShape.Kind.CIRCLE, SvgShape.Kind.ELLIPSE -> {
                        val g = node.geometry
                        val rx = g[2].toDouble() * scale(e.ctm); val ry = (if (node.kind == SvgShape.Kind.ELLIPSE) g[3].toDouble() else g[2].toDouble()) * scale(e.ctm)
                        prims.begin(PrimKind.ELL, f, -1, -1)
                        prims.put(e.first); prims.put(fb(rx)); prims.put(fb(ry)); prims.put(fb(0.0)); prims.put(fb(0.0)); prims.end()
                    }
                }
            }
            out.add(AnchorClip(draft.clip.id, draft.units, first, prims.count, draft.parent))
        }
        return out
    }

    private fun items(): AnchorItems {
        val n = items.size
        val kinds = ArrayList<String>()
        val kindIndex = HashMap<String, Int>()
        val ot = ByteArray(n); val oid = LongArray(n); val kind = IntArray(n)
        val far = DoubleArray(n * 4); val sort = DoubleArray(n * 3); val off = DoubleArray(n); val depth = DoubleArray(n)
        val first = IntArray(n); val last = IntArray(n)
        var next = 0
        for (i in 0 until n) {
            val (t, id) = AnchorCompiler.osmOwner(src.tape.ownerObject(items.owner(i)))
            ot[i] = t; oid[i] = id
            kind[i] = kindIndex.getOrPut(items.kind(i)) { kinds.add(items.kind(i)); kinds.size - 1 }
            far[i * 4] = items.farX(i, 0); far[i * 4 + 1] = items.farZ(i, 0); far[i * 4 + 2] = items.farX(i, 1); far[i * 4 + 3] = items.farZ(i, 1)
            sort[i * 3] = items.sortX(i); sort[i * 3 + 1] = items.sortY(i); sort[i * 3 + 2] = items.sortZ(i)
            off[i] = items.sortOffset(i); depth[i] = items.depth(i)
            // an item that drew nothing gets an empty range where it would have been
            if (itemFirst[i] < 0) { first[i] = next; last[i] = next } else { first[i] = itemFirst[i]; last[i] = itemLast[i]; next = itemLast[i] }
        }
        return AnchorItems(first, last, ot, oid, kind, kinds, far, sort, off, depth)
    }

    private fun head(points: AnchorPoints, p: AnchorPrims): AnchorHead {
        val cfg = src.cfg
        val meta = src.meta
        val target = cfg["target"] as List<*>
        val liftCounts = IntArray(LiftClass.COUNT)
        for (i in 0 until points.size) liftCounts[points.lift[i].toInt()]++
        val primCounts = IntArray(PrimKind.COUNT)
        for (i in 0 until p.size) primCounts[p.kind[i].toInt()]++
        val m = Coverage.MARGIN
        return AnchorHead(
            meta.regionId, meta.frame.lat0, meta.frame.lon0, meta.frame.kx, meta.frame.kz, meta.kind, cam,
            doubleArrayOf(-m, -m, cam.width + m, cam.height + m),
            (cfg["radius"] as Number?)?.toDouble() ?: 1e9, (target[0] as Number).toDouble(), (target[2] as Number).toDouble(),
            (cfg["ground_r"] as Number?)?.toDouble() ?: 1500.0,
            meta.moment, meta.themeKey, meta.time, meta.bandTolerance, meta.view, liftCounts, primCounts,
        )
    }

    private fun screen(): AnchorScreen {
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(fmt(cam.width)).append(' ').append(fmt(cam.height))
            .append("\" width=\"").append(fmt(cam.width)).append("\" height=\"").append(fmt(cam.height)).append("\">")
        for ((d, nodes) in screenNodes.withIndex()) {
            if (nodes.isEmpty()) continue
            sb.append("<g id=\"layer").append(d).append("\">")
            SvgWriter.defs(sb, docs[d], nodes)
            for (n in nodes) SvgWriter.node(sb, docs[d], n)
            sb.append("</g>")
        }
        sb.append("</svg>")
        val spec = buildString {
            append("{\"cfg\":").append(Json.write(src.cfg))
            src.meta.spec?.let { append(',').append(it) }
            append('}')
        }
        return AnchorScreen(horizon, sb.toString(), spec)
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

    companion object {
        const val ARC_STEPS = 6
    }
}
