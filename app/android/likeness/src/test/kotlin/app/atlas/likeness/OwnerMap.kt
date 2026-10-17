package app.atlas.likeness

import app.atlas.anchor.AnchorCompiler
import app.atlas.anchor.OwnerType
import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgClip
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgPaint
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgPathData
import app.atlas.mapview.svg.SvgShape
import app.atlas.mapview.svg.SvgStyle
import app.atlas.mapview.svg.SvgText
import app.atlas.mapview.svg.SvgUnits
import app.atlas.render.live.WorldTape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which OSM element each pixel of a reference shows (plan-vector D16, "per owner"): the owner map, from the world tape.
 *
 * The rule, which the core's ID pass follows too (the contract of the per-owner metrics): every drawn element in
 * painter's order writes its owner at the pixel centres its geometry covers, inside its clips, when it is opaque enough
 * to hide what is under it: its fill (or stroke) at an effective opacity of at least ½ (the groups' opacities, its own,
 * the paint's alpha; a gradient by the mean of its stops), blended normally. Translucent and blended layers (shadows,
 * haze, glows, the vignette), pattern fills and text take no pixels; generator-only filtered elements are not drawn
 * (structural mode); other filters draw their source's geometry. An opaque element with no OSM owner (the sky, the
 * ground, water, quays) writes 0, so it hides what is under it.
 *
 * An element's owner: inside the objects group, the item the world tape logged for its characters (M1b's items: the
 * building, part, tree, lamp, door …); elsewhere, the owner most of its vertices were projected for on the tape.
 */
class OwnerMap(
    val w: Int,
    val h: Int,
    /** Per pixel, the index of its owner in [owners] (1-based), 0 for none. */
    val index: IntArray,
    /** The owners, by index − 1. */
    val owners: List<Owner>,
) {
    /** An OSM element that owns pixels: its [type] (`OwnerType`), [id], class, the item kind that drew it, and its pixels. */
    class Owner(val type: Byte, val id: Long, val klass: String, val kind: String, var pixels: Int = 0) {
        /** `w123`-style name, as the report prints it (`a` an area: a way or a relation). */
        val tag: String get() = typeLetter(type) + id
    }

    /** Pixels owned by anyone. */
    val owned: Int get() = index.count { it != 0 }

    /** The share of the frame owned by [klass]. */
    fun share(klass: String): Double {
        var n = 0
        for (i in index) if (i != 0 && owners[i - 1].klass == klass) n++
        return n.toDouble() / index.size
    }

    companion object {
        /** OwnerType's letter: n, w, r, a (an area: a way or a relation), o (other). */
        fun typeLetter(t: Byte): String = when (t) {
            OwnerType.NODE -> "n"
            OwnerType.WAY -> "w"
            OwnerType.RELATION -> "r"
            OwnerType.AREA -> "a"
            else -> "o"
        }

        /** The class D16 reports owners by, from the kind of item that drew it (or the ground record it came from). */
        fun classOf(kind: String): String = when (kind) {
            "building" -> "building"
            "part" -> "part"
            "tree" -> "tree"
            "road" -> "road"
            "area" -> "area"
            "water" -> "water"
            "lamp", "bench", "bollard", "rack", "basket", "railing", "hydrant", "dock", "sign", "bin", "post" -> "furniture"
            "door", "shopfront", "terrace", "awning" -> "facade"
            else -> "other"
        }

        /**
         * The owner map of [made] (the port's render under its tape) at [size], its frame moved by ([shiftX], [shiftY])
         * device px; [layers] stand in for the render's own (a plant's edited text) with [items] the edited item table.
         */
        fun of(made: Oracle.Made, size: FrameSize, layers: List<String> = made.layers, items: ItemTable = ItemTable.of(made.tape), shiftX: Double = 0.0, shiftY: Double = 0.0): OwnerMap {
            val b = Builder(size, made.tape, items, shiftX, shiftY)
            var base = 0
            for (text in layers) base = b.layer(text, base)
            return b.finish()
        }
    }

    /** Builds an owner map layer by layer. */
    class Builder(val size: FrameSize, private val tape: WorldTape?, private val items: ItemTable, shiftX: Double, shiftY: Double) {
        private val raster = OwnerRaster(size.wPx, size.hPx, size.density, shiftX, shiftY)
        private val owners = ArrayList<Owner>()
        private val byKey = HashMap<Long, Int>()
        private val byObject = java.util.IdentityHashMap<Any, Int>()

        /** Draws one layer's ownership; [base] is where its objects group's content continues the previous layer's. Returns the next base. */
        fun layer(text: String, base: Int): Int {
            val doc = SvgParser.parse(text)
            val scan = SvgScan.scan(text)
            val leaves = ArrayList<SvgNode>()
            collectLeaves(doc.root, leaves)
            check(leaves.size == scan.leaves.size) { "the layer's text has ${scan.leaves.size} drawable elements, the parser ${leaves.size}" }
            val ownerOf = IntArray(leaves.size)
            for ((k, leaf) in leaves.withIndex()) {
                val at = scan.leaves[k]
                ownerOf[k] = if (scan.inObjects(at)) itemOwner(base + scan.objectsOffset(at)) else groundOwner(leaf, doc)
            }
            Painter(doc, raster, ownerOf).paint()
            return base + scan.objectsLength
        }

        fun finish(): OwnerMap {
            val idx = raster.owners
            for (i in idx) if (i != 0) owners[i - 1].pixels++
            return OwnerMap(raster.w, raster.h, idx, owners)
        }

        private fun collectLeaves(g: SvgGroup, out: MutableList<SvgNode>) {
            for (c in g.children) if (c is SvgGroup) collectLeaves(c, out) else out.add(c)
        }

        /** The owner index of the item whose characters hold objects offset [at]. */
        private fun itemOwner(at: Int): Int {
            val k = items.at(at)
            if (k < 0) return 0
            val o = tape?.ownerObject(items.owner[k]) ?: return 0
            return indexOf(o, items.kind[k])
        }

        private val xs = DoubleArray(1 shl 16)
        private val ys = DoubleArray(1 shl 16)
        private val found = IntArray(1 shl 16)

        /** The owner index most of [leaf]'s vertices were projected for. */
        private fun groundOwner(leaf: SvgNode, doc: SvgDocument): Int {
            val t = tape ?: return 0
            if (leaf !is SvgShape) return 0
            var n = 0
            when (leaf.kind) {
                SvgShape.Kind.PATH -> {
                    var c = leaf.coordStart
                    for (v in leaf.verbStart until leaf.verbEnd) {
                        if (doc.path.verbs[v] == SvgPathData.CLOSE) continue
                        if (n < xs.size) { xs[n] = doc.path.coords[c].toDouble(); ys[n] = doc.path.coords[c + 1].toDouble(); n++ }
                        c += 2
                    }
                }
                SvgShape.Kind.RECT -> { val g = leaf.geometry; xs[0] = g[0].toDouble(); ys[0] = g[1].toDouble(); xs[1] = (g[0] + g[2]).toDouble(); ys[1] = (g[1] + g[3]).toDouble(); n = 2 }
                else -> { xs[0] = leaf.geometry[0].toDouble(); ys[0] = leaf.geometry[1].toDouble(); n = 1 }
            }
            if (n == 0) return 0
            val hits = t.resolve(xs, ys, n, found)
            if (hits == 0) return 0
            // the majority owner of the vertices found (Boyer–Moore), then its object
            var cand = 0; var count = 0
            for (j in 0 until n) {
                val e = found[j]
                if (e < 0) continue
                val o = t.owner(e)
                if (count == 0) { cand = o; count = 1 } else if (o == cand) count++ else count--
            }
            val obj = t.ownerObject(cand) ?: return 0
            return indexOf(obj, groundKind(obj))
        }

        private fun groundKind(o: Any): String {
            val m = o as? Map<*, *> ?: return "other"
            return when {
                m["pts"] != null || m["hw"] != null -> "road"
                m["poly"] != null -> "area"
                else -> "other"
            }
        }

        /** The owner index of tape owner [o] (an OSM element's record), registered on first use; 0 when it has no OSM id. */
        private fun indexOf(o: Any, kind: String): Int {
            byObject[o]?.let { return it }
            val (type, id) = AnchorCompiler.osmOwner(o)
            val k = if (id == 0L || type == OwnerType.NONE || type == OwnerType.OTHER) 0 else {
                val key = (type.toLong() shl 56) xor id
                byKey.getOrPut(key) { owners.add(Owner(type, id, classOf(kind), kind)); owners.size }
            }
            byObject[o] = k
            return k
        }
    }

    /** Walks a parsed layer in painter's order and writes each opaque element's owner, [ownerOf] by leaf. */
    internal class Painter(val doc: SvgDocument, val raster: OwnerRaster, val ownerOf: IntArray) {
        private var leaf = 0
        private val masks = HashMap<SvgClip, BooleanArray>()
        private val generatorOnly = HashMap<String, Boolean>()

        fun paint() = group(doc.root, 1f, false, null)

        private fun group(g: SvgGroup, opacity: Float, blended: Boolean, clip: BooleanArray?) {
            for (c in g.children) {
                val o = opacity * c.opacity
                val b = blended || c.blend != SvgBlend.NORMAL
                val skip = c.filter?.let { f -> generatorOnly.getOrPut(f.id) { AnchorCompiler.generatorOnly(f) } } == true
                val cl = c.clip?.let { combine(clip, mask(it)) } ?: clip
                if (c is SvgGroup) {
                    if (skip) { leaf += countLeaves(c); continue }
                    group(c, o, b, cl)
                    continue
                }
                val owner = ownerOf[leaf++]
                if (skip || b || c !is SvgShape) continue
                val st = c.style
                val fillA = o * st.fillOpacity * alpha(st.fill)
                val strokeA = o * st.strokeOpacity * alpha(st.stroke)
                if (fillA >= 0.5f) raster.fill(Geometry.fill(c, doc, raster), st.evenOdd, cl, owner)
                if (strokeA >= 0.5f && st.strokeWidth > 0f) raster.fill(Geometry.stroke(c, doc, raster), false, cl, owner)
            }
        }

        private fun countLeaves(g: SvgGroup): Int = g.children.sumOf { if (it is SvgGroup) countLeaves(it) else 1 }

        /** A paint's alpha: a colour's own, a gradient's mean over its stops, a pattern's 0 (sparse marks, not a surface). */
        private fun alpha(p: SvgPaint): Float = when (p) {
            SvgPaint.None -> 0f
            is SvgPaint.Color -> (p.argb ushr 24) / 255f
            is SvgPaint.Ref -> {
                val g = doc.gradients[p.id]
                when {
                    g != null -> if (g.colors.isEmpty()) 0f else g.colors.sumOf { (it ushr 24).toDouble() }.toFloat() / (255f * g.colors.size)
                    doc.patterns[p.id] != null -> 0f
                    else -> alpha(p.fallback)
                }
            }
        }

        private fun combine(a: BooleanArray?, b: BooleanArray): BooleanArray {
            if (a == null) return b
            return BooleanArray(b.size) { a[it] && b[it] }
        }

        /** Clip [c]'s mask over the frame: the union of its children's fills, within its own clip. */
        private fun mask(c: SvgClip): BooleanArray = masks.getOrPut(c) {
            if (c.units == SvgUnits.BOUNDING_BOX) throw IllegalStateException("clip ${c.id}: bounding-box clips are not written by the engine")
            val m = OwnerRaster(raster.w, raster.h, raster.density, raster.shiftX, raster.shiftY)
            for (child in c.children) if (child is SvgShape) m.fill(Geometry.fill(child, doc, m), child.style.evenOdd, null, 1)
            val out = BooleanArray(raster.w * raster.h) { m.owners[it] != 0 }
            val parent = c.clip
            if (parent != null) { val p = mask(parent); for (i in out.indices) out[i] = out[i] && p[i] }
            out
        }
    }
}

/** The items a world tape logged (M1b), as arrays: their owners, kinds, and the characters of the objects group they wrote. */
class ItemTable(val owner: IntArray, val kind: Array<String>, val first: IntArray, val count: IntArray) {
    val size: Int get() = owner.size

    /** The item whose characters hold objects offset [at] (items are in order, their ranges disjoint), or −1. */
    fun at(at: Int): Int {
        var lo = 0; var hi = size - 1; var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (first[mid] <= at) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        // items that wrote nothing share their successor's first character: step back to the one that holds [at]
        while (best >= 0 && count[best] == 0) best--
        return if (best >= 0 && at < first[best] + count[best]) best else -1
    }

    companion object {
        fun of(tape: WorldTape): ItemTable {
            val it = tape.items
            val n = it.size
            return ItemTable(IntArray(n) { k -> it.owner(k) }, Array(n) { k -> it.kind(k) }, IntArray(n) { k -> it.firstChar(k) }, IntArray(n) { k -> it.charCount(k) })
        }
    }
}

/**
 * The drawable leaves of an SVG layer's text, in document order, as `SvgParser` keeps them (shapes with geometry, texts
 * with content, nothing inside `defs`, clip paths, patterns or a `display: none`), with the character each starts at,
 * and where the objects group's content (`<g stroke-linejoin="round" …>` in the frame's clip group) lies.
 */
object SvgScan {
    class Result(val leaves: IntArray, val objectsStart: Int, val objectsEnd: Int) {
        /** Characters of the objects group's content (0 when the layer has none). */
        val objectsLength: Int get() = if (objectsStart < 0) 0 else objectsEnd - objectsStart
        fun inObjects(at: Int) = objectsStart in 0..at && at < objectsEnd
        fun objectsOffset(at: Int) = at - objectsStart
    }

    private const val OBJECTS = "<g stroke-linejoin=\"round\""

    private enum class Kind { ROOT, GROUP, OTHER, SKIP, SHAPE, TEXT }

    fun scan(s: String): Result {
        val leaves = IntArrayList()
        val kinds = ArrayList<Kind>()
        val inDoc = ArrayList<Boolean>()
        var objStart = -1; var objEnd = -1; var objDepth = -1
        var textStart = -1; var textDoc = false; var textHasContent = false
        var i = s.indexOf('<')
        while (i >= 0 && i < s.length - 1) {
            val c = s[i + 1]
            if (c == '!' || c == '?') {
                val e = if (s.startsWith("<!--", i)) s.indexOf("-->", i + 4).let { if (it < 0) -1 else it + 2 } else s.indexOf('>', i + 2)
                if (e < 0) break
                if (kinds.lastOrNull() == Kind.TEXT && s.startsWith("<![CDATA[", i)) textHasContent = textHasContent || s.substring(i + 9, e - 2).isNotBlank()
                i = next(s, e + 1, kinds, textStart) { textHasContent = true }
                continue
            }
            if (c == '/') {
                val e = s.indexOf('>', i + 2)
                if (e < 0) break
                val k = kinds.removeAt(kinds.size - 1)
                val d = inDoc.removeAt(inDoc.size - 1)
                if (k == Kind.TEXT) {
                    if (d && textHasContent) leaves.add(textStart)
                    textStart = -1
                }
                if (objStart >= 0 && objEnd < 0 && kinds.size == objDepth) objEnd = i
                i = next(s, e + 1, kinds, textStart) { textHasContent = true }
                continue
            }
            val e = tagEnd(s, i)
            if (e < 0) break
            val selfClosing = s[e - 1] == '/'
            val nameEnd = nameEnd(s, i + 1)
            val name = s.substring(i + 1, nameEnd)
            val parent = kinds.lastOrNull()
            val parentDoc = inDoc.lastOrNull() ?: false
            val kind: Kind
            var doc = false
            if (parent == Kind.SKIP || parent == Kind.SHAPE || parent == Kind.TEXT || hidden(s, nameEnd, e)) {
                kind = Kind.SKIP
            } else {
                when (name) {
                    "svg" -> { kind = Kind.ROOT; doc = true }
                    "g" -> { kind = if (parent == null || parent == Kind.ROOT || parent == Kind.GROUP) Kind.GROUP else Kind.OTHER; doc = kind == Kind.GROUP && parentDoc }
                    "path", "rect", "circle", "ellipse" -> {
                        kind = Kind.SHAPE
                        if (parentDoc && kept(s, name, nameEnd, e)) leaves.add(i)
                    }
                    "text" -> { kind = Kind.TEXT; doc = parentDoc; textStart = i; textHasContent = false }
                    else -> kind = Kind.OTHER
                }
            }
            if (name == "g" && kind == Kind.GROUP && objStart < 0 && kinds.size == 2 && s.startsWith(OBJECTS, i)) {
                objStart = e + 1; objDepth = 2
                if (selfClosing) objEnd = objStart
            }
            if (selfClosing) {
                if (kind == Kind.TEXT) textStart = -1
            } else {
                kinds.add(kind); inDoc.add(doc)
            }
            i = next(s, e + 1, kinds, textStart) { textHasContent = true }
        }
        return Result(leaves.toArray(), objStart, if (objStart >= 0 && objEnd < 0) s.length else objEnd)
    }

    /** The next tag after [from]; inside a text element, notes whether the characters before it hold anything. */
    private inline fun next(s: String, from: Int, kinds: List<Kind>, textStart: Int, content: () -> Unit): Int {
        val j = s.indexOf('<', from)
        if (textStart >= 0 && kinds.lastOrNull() == Kind.TEXT) {
            val end = if (j < 0) s.length else j
            for (k in from until end) if (!s[k].isWhitespace()) { content(); break }
        }
        return j
    }

    /** Whether the parser keeps the shape: a path with a `d`, a rect with a size, a circle or an ellipse with radii. */
    private fun kept(s: String, name: String, from: Int, to: Int): Boolean = when (name) {
        "path" -> attr(s, from, to, "d")?.isNotBlank() == true
        "rect" -> (num(attr(s, from, to, "width")) ?: 0.0) > 0 && (num(attr(s, from, to, "height")) ?: 0.0) > 0
        "circle" -> (num(attr(s, from, to, "r")) ?: 0.0) > 0
        else -> (num(attr(s, from, to, "rx")) ?: 0.0) > 0 && (num(attr(s, from, to, "ry")) ?: 0.0) > 0
    }

    private fun hidden(s: String, from: Int, to: Int): Boolean {
        if (attr(s, from, to, "display")?.trim() == "none") return true
        val st = attr(s, from, to, "style") ?: return false
        return st.split(';').any { d -> val c = d.indexOf(':'); c > 0 && d.substring(0, c).trim() == "display" && d.substring(c + 1).trim() == "none" }
    }

    private fun num(v: String?): Double? = v?.trim()?.removeSuffix("px")?.toDoubleOrNull()

    /** The value of attribute [name] in the tag between [from] and [to], or null. */
    fun attr(s: String, from: Int, to: Int, name: String): String? {
        var j = from
        while (j < to) {
            while (j < to && (s[j].isWhitespace() || s[j] == '/')) j++
            val ns = j
            while (j < to && s[j] != '=' && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
            val n = s.substring(ns, j)
            while (j < to && s[j].isWhitespace()) j++
            if (j >= to || s[j] != '=') { if (n.isEmpty()) j++; continue }
            j++
            while (j < to && s[j].isWhitespace()) j++
            if (j >= to) return null
            val q = s[j]
            if (q != '"' && q != '\'') return null
            val ve = s.indexOf(q, j + 1)
            if (ve < 0 || ve > to) return null
            if (n == name) return s.substring(j + 1, ve)
            j = ve + 1
        }
        return null
    }

    private fun nameEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
        return j
    }

    /** The index of the `>` that ends the tag opening at [i], skipping quoted values; −1 if it never ends. */
    fun tagEnd(s: String, i: Int): Int {
        var j = i + 1
        while (j < s.length) {
            val ch = s[j]
            if (ch == '"' || ch == '\'') {
                val e = s.indexOf(ch, j + 1)
                if (e < 0) return -1
                j = e + 1
                continue
            }
            if (ch == '>') return j
            j++
        }
        return -1
    }
}

/** A growable int list. */
class IntArrayList(cap: Int = 1024) {
    private var a = IntArray(cap)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(size)
}

/** Contours in device pixels: x and y per vertex, each contour's first vertex and its vertex count. */
class Contours {
    var x = DoubleArray(256); var y = DoubleArray(256); var n = 0
    var start = IntArray(16); var count = IntArray(16); var contours = 0

    fun clear() { n = 0; contours = 0 }

    fun begin() {
        if (contours == start.size) { start = start.copyOf(contours * 2); count = count.copyOf(contours * 2) }
        start[contours] = n; count[contours] = 0; contours++
    }

    fun add(px: Double, py: Double) {
        if (n == x.size) { x = x.copyOf(n * 2); y = y.copyOf(n * 2) }
        x[n] = px; y[n] = py; n++; count[contours - 1]++
    }

    /** Reverses the last contour when its signed area is negative, so overlapping contours unite under the non-zero rule. */
    fun orientLast() {
        val c = contours - 1
        val s = start[c]; val k = count[c]
        var a = 0.0
        for (i in 0 until k) { val j = (i + 1) % k; a += x[s + i] * y[s + j] - x[s + j] * y[s + i] }
        if (a < 0) {
            var lo = s; var hi = s + k - 1
            while (lo < hi) { val tx = x[lo]; x[lo] = x[hi]; x[hi] = tx; val ty = y[lo]; y[lo] = y[hi]; y[hi] = ty; lo++; hi-- }
        }
    }
}

/** The fill and stroke regions of a parsed shape, as contours in device pixels. */
object Geometry {
    private val fillOut = ThreadLocal<Contours>()
    private val strokeOut = ThreadLocal<Contours>()

    /** This thread's contours of [t], made on first use. */
    private fun mine(t: ThreadLocal<Contours>): Contours = t.get() ?: Contours().also { t.set(it) }

    /** [s]'s fill region in [r]'s device pixels. */
    fun fill(s: SvgShape, doc: SvgDocument, r: OwnerRaster): Contours {
        val out = mine(fillOut); out.clear()
        val t = s.transform
        val g = s.geometry
        when (s.kind) {
            SvgShape.Kind.PATH -> {
                var c = s.coordStart
                var open = false
                for (v in s.verbStart until s.verbEnd) {
                    when (doc.path.verbs[v]) {
                        SvgPathData.MOVE -> { out.begin(); open = true; add(out, r, t, doc.path.coords[c].toDouble(), doc.path.coords[c + 1].toDouble()); c += 2 }
                        SvgPathData.LINE -> { if (!open) { out.begin(); open = true }; add(out, r, t, doc.path.coords[c].toDouble(), doc.path.coords[c + 1].toDouble()); c += 2 }
                        else -> open = false
                    }
                }
            }
            SvgShape.Kind.RECT -> roundRect(out, r, t, g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble(), g[4].toDouble(), g[5].toDouble())
            SvgShape.Kind.CIRCLE -> ellipse(out, r, t, g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[2].toDouble())
            SvgShape.Kind.ELLIPSE -> ellipse(out, r, t, g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble())
        }
        return out
    }

    /**
     * [s]'s stroke region: a quad per segment, discs at round joins and caps, squares extended at square caps (miter tips
     * are left out: they own a few pixels at sharp corners at most). Dashes are drawn solid.
     */
    fun stroke(s: SvgShape, doc: SvgDocument, r: OwnerRaster): Contours {
        val path = fill(s, doc, r)
        val out = mine(strokeOut); out.clear()
        val st = s.style
        val scale = s.transform?.let { sqrt(abs(it[0] * it[3] - it[1] * it[2]).toDouble()) } ?: 1.0
        val hw = st.strokeWidth * scale * r.density / 2
        val closedPath = s.kind != SvgShape.Kind.PATH
        val closed = BooleanArray(path.contours) { closedPath }
        if (s.kind == SvgShape.Kind.PATH) {
            // a subpath is closed when a CLOSE verb ends it
            var k = -1
            for (v in s.verbStart until s.verbEnd) when (doc.path.verbs[v]) {
                SvgPathData.MOVE -> k++
                SvgPathData.LINE -> if (k < 0) k = 0
                else -> if (k >= 0 && k < closed.size) closed[k] = true
            }
        }
        for (c in 0 until path.contours) {
            val b = path.start[c]; val n = path.count[c]
            if (n == 0) continue
            val cl = closed[c] && n > 2
            val segs = if (cl) n else n - 1
            if (segs <= 0) {
                if (st.cap == SvgStyle.Cap.ROUND) disc(out, path.x[b], path.y[b], hw)
                continue
            }
            for (i in 0 until segs) {
                val a = b + i; val e = b + (i + 1) % n
                var x0 = path.x[a]; var y0 = path.y[a]; var x1 = path.x[e]; var y1 = path.y[e]
                val len = hypot(x1 - x0, y1 - y0)
                if (len < 1e-9) continue
                val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
                if (!cl && st.cap == SvgStyle.Cap.SQUARE) {
                    if (i == 0) { x0 -= ux * hw; y0 -= uy * hw }
                    if (i == segs - 1) { x1 += ux * hw; y1 += uy * hw }
                }
                val nx = -uy * hw; val ny = ux * hw
                out.begin()
                out.add(x0 + nx, y0 + ny); out.add(x1 + nx, y1 + ny); out.add(x1 - nx, y1 - ny); out.add(x0 - nx, y0 - ny)
                out.orientLast()
            }
            if (st.join == SvgStyle.Join.ROUND) {
                val from = if (cl) 0 else 1
                val to = if (cl) n else n - 1
                for (i in from until to) disc(out, path.x[b + i], path.y[b + i], hw)
            }
            if (!cl && st.cap == SvgStyle.Cap.ROUND) { disc(out, path.x[b], path.y[b], hw); disc(out, path.x[b + n - 1], path.y[b + n - 1], hw) }
        }
        return out
    }

    private fun add(out: Contours, r: OwnerRaster, t: FloatArray?, x: Double, y: Double) {
        var px = x; var py = y
        if (t != null) { px = t[0] * x + t[2] * y + t[4]; py = t[1] * x + t[3] * y + t[5] }
        out.add(px * r.density + r.shiftX, py * r.density + r.shiftY)
    }

    private fun segmentsFor(radiusPx: Double): Int {
        if (radiusPx <= 0.2) return 8
        // the polygon stays within a tenth of a pixel of the curve
        val n = ceil(PI / acos(max(-1.0, 1 - 0.1 / radiusPx))).toInt()
        return n.coerceIn(8, 512)
    }

    private fun ellipse(out: Contours, r: OwnerRaster, t: FloatArray?, cx: Double, cy: Double, rx: Double, ry: Double) {
        val n = segmentsFor(max(rx, ry) * r.density)
        out.begin()
        for (i in 0 until n) { val a = 2 * PI * i / n; add(out, r, t, cx + rx * cos(a), cy + ry * sin(a)) }
        out.orientLast()
    }

    private fun roundRect(out: Contours, r: OwnerRaster, t: FloatArray?, x: Double, y: Double, w: Double, h: Double, rx: Double, ry: Double) {
        out.begin()
        if (rx <= 0 || ry <= 0) {
            add(out, r, t, x, y); add(out, r, t, x + w, y); add(out, r, t, x + w, y + h); add(out, r, t, x, y + h)
        } else {
            val n = max(2, segmentsFor(max(rx, ry) * r.density) / 4)
            val corners = arrayOf(doubleArrayOf(x + w - rx, y + ry, -PI / 2), doubleArrayOf(x + w - rx, y + h - ry, 0.0), doubleArrayOf(x + rx, y + h - ry, PI / 2), doubleArrayOf(x + rx, y + ry, PI))
            for (cn in corners) for (i in 0..n) { val a = cn[2] + PI / 2 * i / n; add(out, r, t, cn[0] + rx * cos(a), cn[1] + ry * sin(a)) }
        }
        out.orientLast()
    }

    private fun disc(out: Contours, cx: Double, cy: Double, rad: Double) {
        val n = segmentsFor(rad)
        out.begin()
        for (i in 0 until n) { val a = 2 * PI * i / n; out.add(cx + rad * cos(a), cy + rad * sin(a)) }
        out.orientLast()
    }
}

/**
 * Writes owners at pixel centres: a pixel (i, j) is inside a region when its centre (i + ½, j + ½) is, by the region's
 * fill rule, as a GPU samples a primitive at one sample per pixel.
 */
class OwnerRaster(val w: Int, val h: Int, val density: Double, val shiftX: Double = 0.0, val shiftY: Double = 0.0) {
    val owners = IntArray(w * h)
    private var rowCount = IntArray(64)
    private var rowStart = IntArray(65)
    private var xs = DoubleArray(256)
    private var dirs = IntArray(256)

    /** Writes [owner] at the pixels whose centres [c] covers (non-zero or [evenOdd]), where [clip] allows. */
    fun fill(c: Contours, evenOdd: Boolean, clip: BooleanArray?, owner: Int) {
        if (c.contours == 0) return
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (k in 0 until c.n) { val y = c.y[k]; if (y < minY) minY = y; if (y > maxY) maxY = y }
        if (!(minY < maxY)) return
        val r0 = max(0, ceil(minY - 0.5).toInt())
        val r1 = min(h - 1, ceil(maxY - 0.5).toInt() - 1)
        if (r0 > r1) return
        val rows = r1 - r0 + 1
        if (rowCount.size < rows) { rowCount = IntArray(rows * 2); rowStart = IntArray(rows * 2 + 1) }
        java.util.Arrays.fill(rowCount, 0, rows, 0)
        // pass 1: crossings per row
        forEdges(c) { x0, y0, x1, y1 ->
            val top = min(y0, y1); val bot = max(y0, y1)
            val a = max(r0, ceil(top - 0.5).toInt()); val b = min(r1, ceil(bot - 0.5).toInt() - 1)
            for (j in a..b) rowCount[j - r0]++
        }
        var total = 0
        for (j in 0 until rows) { rowStart[j] = total; total += rowCount[j] }
        rowStart[rows] = total
        if (total == 0) return
        if (xs.size < total) { xs = DoubleArray(total * 2); dirs = IntArray(total * 2) }
        java.util.Arrays.fill(rowCount, 0, rows, 0)
        // pass 2: each crossing's x at the row's centre line and its direction
        forEdges(c) { x0, y0, x1, y1 ->
            val dir = if (y1 > y0) 1 else -1
            val top = min(y0, y1); val bot = max(y0, y1)
            val a = max(r0, ceil(top - 0.5).toInt()); val b = min(r1, ceil(bot - 0.5).toInt() - 1)
            val slope = (x1 - x0) / (y1 - y0)
            for (j in a..b) {
                val yc = j + 0.5
                val k = rowStart[j - r0] + rowCount[j - r0]++
                xs[k] = x0 + (yc - y0) * slope; dirs[k] = dir
            }
        }
        for (j in 0 until rows) {
            val s = rowStart[j]; val e = rowStart[j + 1]
            if (e - s < 2) continue
            // insertion sort by x: a row holds few crossings
            for (p in s + 1 until e) {
                val vx = xs[p]; val vd = dirs[p]
                var q = p - 1
                while (q >= s && xs[q] > vx) { xs[q + 1] = xs[q]; dirs[q + 1] = dirs[q]; q-- }
                xs[q + 1] = vx; dirs[q + 1] = vd
            }
            var wind = 0
            val row = (j + r0) * w
            for (p in s until e - 1) {
                wind += if (evenOdd) 1 else dirs[p]
                val inside = if (evenOdd) (wind and 1) != 0 else wind != 0
                if (!inside) continue
                val a = max(0, ceil(xs[p] - 0.5).toInt()); val b = min(w - 1, ceil(xs[p + 1] - 0.5).toInt() - 1)
                if (clip == null) {
                    for (i in a..b) owners[row + i] = owner
                } else {
                    for (i in a..b) if (clip[row + i]) owners[row + i] = owner
                }
            }
        }
    }

    private inline fun forEdges(c: Contours, f: (Double, Double, Double, Double) -> Unit) {
        for (k in 0 until c.contours) {
            val s = c.start[k]; val n = c.count[k]
            if (n < 2) continue
            for (i in 0 until n) {
                val a = s + i; val b = s + (i + 1) % n
                val y0 = c.y[a]; val y1 = c.y[b]
                if (y0 == y1) continue
                f(c.x[a], y0, c.x[b], y1)
            }
        }
    }
}

class OwnerMapTest {
    private val s1 = FrameSize.NAMED.getValue("S1")

    /** The owner map's walk over [svg] with each drawable leaf its own owner (1, 2, …), to see what wins where. */
    private fun map(svg: String, size: FrameSize = FrameSize("t", 40, 30, 1.0)): IntArray {
        val doc = SvgParser.parse(svg)
        var leaves = 0
        fun count(g: SvgGroup) { for (c in g.children) if (c is SvgGroup) count(c) else leaves++ }
        count(doc.root)
        assertEquals("the scanner sees the parser's leaves", leaves, SvgScan.scan(svg).leaves.size)
        val raster = OwnerRaster(size.wPx, size.hPx, size.density)
        OwnerMap.Painter(doc, raster, IntArray(leaves) { it + 1 }).paint()
        return raster.owners
    }

    @Test
    fun `pixel centres decide, painter's order wins, fill rules hold`() {
        // a 10 × 10 square at 5, 5, then a square half over it, then an even-odd ring with a hole
        val px = map(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 30' width='40' height='30'>" +
                "<path d='M5 5L15 5L15 15L5 15Z' fill='#112233'/>" +
                "<path d='M10 10L20 10L20 20L10 20Z' fill='#445566'/>" +
                "<path d='M25 5L35 5L35 15L25 15ZM28 8L32 8L32 12L28 12Z' fill='#778899' fill-rule='evenodd'/>" +
                "</svg>",
        )
        fun at(x: Int, y: Int) = px[y * 40 + x]
        assertEquals(1, at(5, 5)); assertEquals(1, at(14, 9)); assertEquals(0, at(15, 5)); assertEquals(0, at(4, 5))
        assertEquals(2, at(10, 10)); assertEquals(2, at(19, 19)); assertEquals(0, at(20, 20))
        assertEquals(3, at(25, 5)); assertEquals(0, at(29, 9)); assertEquals(3, at(27, 9))
        // the first square's 100 pixels, less the 25 the second covers
        assertEquals(75, px.count { it == 1 }); assertEquals(100, px.count { it == 2 })
        assertEquals(100 - 16, px.count { it == 3 })
    }

    @Test
    fun `translucent, blended, pattern and generator-only elements take no pixels, clips and strokes do`() {
        val px = map(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 30' width='40' height='30'>" +
                "<defs><clipPath id='c'><rect x='0' y='0' width='20' height='30'/></clipPath>" +
                "<filter id='n' filterUnits='userSpaceOnUse' x='0' y='0' width='40' height='30'><feTurbulence baseFrequency='.5'/></filter>" +
                "<pattern id='p' width='4' height='4' patternUnits='userSpaceOnUse'><path d='M0 0h4' stroke='#000'/></pattern></defs>" +
                "<rect width='40' height='30' fill='#FFFFFF'/>" +
                "<g opacity='.3'><rect width='40' height='30' fill='#000'/></g>" +
                "<rect width='40' height='30' fill='#F00' style='mix-blend-mode:screen'/>" +
                "<rect width='40' height='30' fill='url(#p)'/>" +
                "<rect width='40' height='30' filter='url(#n)'/>" +
                "<g clip-path='url(#c)'><rect x='10' y='0' width='20' height='10' fill='#0F0'/></g>" +
                "<path d='M0 20L40 20' stroke='#00F' stroke-width='4' fill='none'/>" +
                "<text x='5' y='25' font-size='8'>IJ</text>" +
                "</svg>",
        )
        fun at(x: Int, y: Int) = px[y * 40 + x]
        assertEquals(1, at(35, 5))
        assertEquals("the clip keeps the left part", 6, at(15, 5)); assertEquals(1, at(25, 5))
        assertEquals("a 4 px stroke owns two rows each side", 7, at(5, 18)); assertEquals(7, at(5, 21)); assertEquals(1, at(5, 17)); assertEquals(1, at(5, 22))
    }

    @Test
    fun `the scanner skips defs, clips, patterns and hidden elements, and finds the objects group`() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><defs><clipPath id='vb'><rect width='10' height='10'/></clipPath>" +
            "<pattern id='q' width='2' height='2'><path d='M0 0h2'/></pattern></defs><g clip-path='url(#vb)'>" +
            "<path d='M0 0L1 1Z'/><path d=''/><rect width='0' height='3'/><g display='none'><path d='M1 1L2 2Z'/></g>" +
            "<g stroke-linejoin=\"round\" fill='#000'><path d='M2 2L3 3Z'/><text x='1' y='1'> </text><text x='1' y='1'>A</text><circle r='1'/></g></g></svg>"
        val r = SvgScan.scan(svg)
        val doc = SvgParser.parse(svg)
        var n = 0
        fun count(g: SvgGroup) { for (c in g.children) if (c is SvgGroup) count(c) else n++ }
        count(doc.root)
        assertEquals(n, r.leaves.size)
        assertEquals(4, r.leaves.size)
        assertTrue(r.objectsStart > 0 && r.inObjects(r.leaves[1]) && !r.inObjects(r.leaves[0]))
        assertEquals('<', svg[r.objectsStart]); assertEquals("</g></g></svg>", svg.substring(r.objectsEnd))
    }

    @Test
    fun `the Westerkerk home has its buildings, parts and trees where the renders draw them`() {
        val set = CaseSet.load(phone = null)
        val made = Oracle.render(set.case("home sunny S1"))
        val t0 = System.nanoTime()
        val m = OwnerMap.of(made, s1)
        val ms = (System.nanoTime() - t0) / 1e6
        val buildings = m.share("building"); val parts = m.share("part"); val trees = m.share("tree")
        println("owner map of home sunny S1 in %.0f ms: %d owners; buildings %.1f %%, parts %.1f %%, trees %.2f %%, roads %.1f %%, areas %.1f %%".format(
            ms, m.owners.size, buildings * 100, parts * 100, trees * 100, m.share("road") * 100, m.share("area") * 100))
        // N18 measured the objects' coverage at the board's view: buildings 58.9 %, parts 8.0 %, trees 1.06 % (unions,
        // before the painter's order hides some of each behind the next)
        assertTrue("buildings $buildings", buildings in 0.35..0.62)
        assertTrue("parts $parts", parts in 0.03..0.09)
        assertTrue("trees $trees", trees in 0.002..0.02)
        assertTrue("every owner has an OSM id", m.owners.all { it.id != 0L })
        val westertoren = m.owners.filter { it.klass == "part" }.maxBy { it.pixels }
        println("the largest part: ${westertoren.tag} with ${westertoren.pixels} px")
    }

    @Test
    fun `an owner map writes and reads back as a PNG`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ownermap-test").also { it.mkdirs() }
        try {
            val idx = IntArray(64 * 48) { (it * 7919) % 70000 }
            val f = File(dir, "o.png")
            Png.writeIndex(f, idx, 64, 48)
            val back = Png.readIndex(f)
            assertEquals(64, back.w); assertTrue(idx.contentEquals(back.px))
        } finally {
            dir.deleteRecursively()
        }
    }
}
