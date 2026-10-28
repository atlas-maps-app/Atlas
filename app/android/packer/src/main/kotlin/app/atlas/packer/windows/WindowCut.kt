package app.atlas.packer.windows

import app.atlas.packer.pbf.PbfRelation
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater

/** One L14 window: its tile, and its box as the core's load::window_box gives it (south, west, north, east). */
class Window(val x: Int, val y: Int, val south: Double, val west: Double, val north: Double, val east: Double)

/** Why a window is left out, so the phone asks Overpass there. */
enum class LeftOut(val why: String) {
    BEYOND("its box with a kilometre around it reaches beyond the extract's polygon"),
    WAY("a way in it has nodes the extract does not hold"),
    RELATION("a relation in it has members the extract does not hold"),
    TWICE("an element in it has a key twice"),
    TOO_BIG("its answer is over 1 GiB"),
}

/**
 * A window cut: its answer gzipped with the gzip member's SHA-256, or why it is left out and the element's id that
 * leaves it out (a way's, a relation's).
 */
class Answer(
    val window: Window,
    val gzip: ByteArray?,
    val sha256: ByteArray?,
    val jsonBytes: Int,
    val elements: Int,
    val leftOut: LeftOut?,
    val by: Long = 0,
)

/**
 * A grid of cells of 0.01° over the windows' boxes, in 1e-7 degree integers, which hands a window the elements that can
 * lie in its box: each tagged node in its point's cell, each way in the cells its segments' boxes meet (both sides of
 * the antimeridian for a segment that crosses it, as Overpass's intersects takes it), each relation's member node in its
 * point's cell. A window looks at every cell its box meets, widened by a margin, so no element in the box is missed.
 */
internal class Cells(south: Double, west: Double, north: Double, east: Double) {
    val lat0 = Math.floor(south * 1e7).toLong() - MARGIN
    val lon0 = Math.floor(west * 1e7).toLong() - MARGIN
    val rows = ((Math.ceil(north * 1e7).toLong() + MARGIN - lat0) / CELL + 1).toInt()
    val cols = ((Math.ceil(east * 1e7).toLong() + MARGIN - lon0) / CELL + 1).toInt()

    init {
        require(rows.toLong() * cols < Int.MAX_VALUE / 2) { "a grid of $rows by $cols cells" }
    }

    val count: Int get() = rows * cols

    /** The cell of a point, or -1 outside the grid. */
    fun of(latE7: Int, lonE7: Int): Int {
        val r = Math.floorDiv(latE7 - lat0, CELL)
        val c = Math.floorDiv(lonE7 - lon0, CELL)
        return if (r < 0 || r >= rows || c < 0 || c >= cols) -1 else (r * cols + c).toInt()
    }

    /** Calls [each] with every cell the box of 1e-7 degree integers meets, within the grid. */
    inline fun box(south: Long, west: Long, north: Long, east: Long, each: (Int) -> Unit) {
        val r0 = maxOf(0L, Math.floorDiv(south - lat0, CELL))
        val r1 = minOf(rows - 1L, Math.floorDiv(north - lat0, CELL))
        val c0 = maxOf(0L, Math.floorDiv(west - lon0, CELL))
        val c1 = minOf(cols - 1L, Math.floorDiv(east - lon0, CELL))
        var r = r0
        while (r <= r1) {
            var c = c0
            while (c <= c1) {
                each((r * cols + c).toInt())
                c++
            }
            r++
        }
    }

    /** The cells a window's box meets, widened by the margin. */
    inline fun window(w: Window, each: (Int) -> Unit) = box(
        Math.floor(w.south * 1e7).toLong() - MARGIN, Math.floor(w.west * 1e7).toLong() - MARGIN,
        Math.ceil(w.north * 1e7).toLong() + MARGIN, Math.ceil(w.east * 1e7).toLong() + MARGIN, each,
    )

    /** Calls [each] with the cells of the segment's box (twice for one that crosses the antimeridian). */
    inline fun segment(lat0: Int, lon0: Int, lat1: Int, lon1: Int, each: (Int) -> Unit) {
        val s = minOf(lat0, lat1).toLong()
        val n = maxOf(lat0, lat1).toLong()
        val w = minOf(lon0, lon1).toLong()
        val e = maxOf(lon0, lon1).toLong()
        if (e - w > 1_800_000_000L) {
            box(s, e, n, 1_800_000_000L, each)
            box(s, -1_800_000_000L, n, w, each)
        } else box(s, w, n, e, each)
    }

    companion object {
        const val CELL = 100_000L
        const val MARGIN = 2L
    }
}

/** Items by cell: cell c holds items[start[c] until start[c + 1]]. */
internal class Buckets(val start: IntArray, val items: IntArray) {
    companion object {
        /** Buckets of [n] items; [cellsOf] calls its second argument with each cell of item i, once each. */
        fun of(cells: Int, n: Int, cellsOf: (Int, (Int) -> Unit) -> Unit): Buckets {
            val start = IntArray(cells + 1)
            val count: (Int) -> Unit = { c -> start[c + 1]++ }
            for (i in 0 until n) cellsOf(i, count)
            for (c in 0 until cells) start[c + 1] += start[c]
            val items = IntArray(start[cells])
            val fill = start.copyOf(cells)
            var item = 0
            val put: (Int) -> Unit = { c -> items[fill[c]++] = item }
            for (i in 0 until n) {
                item = i
                cellsOf(i, put)
            }
            return Buckets(start, items)
        }
    }
}

/**
 * The windows' answers from an [Extract]: for each window, the elements Overpass's window query gives for its box (each
 * tagged node in it, each way that meets it, each multipolygon, national park and protected area with a member node in
 * it or a member way that meets it), in Overpass's order (nodes, ways, relations, each by id), each as `out geom` prints
 * it with no box (a way's bounds, nodes and points; a relation's bounds and its members whole), the answer's head saying
 * the extract's date. A window is left out where the answer would hold an element the extract does not hold whole.
 */
class WindowCut(private val e: Extract, windows: List<Window>, date: String, private val level: Int = Deflater.BEST_COMPRESSION) {
    private val head = AnswerJson.head(date)
    internal val cells: Cells
    private val nodeCells: Buckets
    private val wayCells: Buckets
    private val memberNodeCells: Buckets

    init {
        var s = Double.POSITIVE_INFINITY
        var w = Double.POSITIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        var ea = Double.NEGATIVE_INFINITY
        for (x in windows) {
            s = minOf(s, x.south); w = minOf(w, x.west); n = maxOf(n, x.north); ea = maxOf(ea, x.east)
        }
        cells = if (windows.isEmpty()) Cells(0.0, 0.0, 0.0, 0.0) else Cells(s, w, n, ea)
        nodeCells = Buckets.of(cells.count, e.taggedCount) { t, add ->
            val node = e.taggedNode[t]
            val c = cells.of(e.nodes.latE7(node), e.nodes.lonE7(node))
            if (c >= 0) add(c)
        }
        val scratch = IntList()
        wayCells = Buckets.of(cells.count, e.wayCount) { way, add ->
            scratch.clear()
            val from = e.wayStart[way]
            val to = e.wayStart[way + 1]
            var prev = -1
            for (i in from until to) {
                val node = e.wayRefs[i]
                if (node >= 0) {
                    val la = e.nodes.latE7(node)
                    val lo = e.nodes.lonE7(node)
                    if (prev >= 0) cells.segment(e.nodes.latE7(prev), e.nodes.lonE7(prev), la, lo) { scratch.add(it) }
                    else cells.of(la, lo).let { if (it >= 0) scratch.add(it) }
                }
                prev = node
            }
            scratch.sortUnique()
            for (k in 0 until scratch.size) add(scratch[k])
        }
        memberNodeCells = Buckets.of(cells.count, e.relationNodes.size) { k, add ->
            val node = e.relationNodes[k].toInt()
            val c = cells.of(e.nodes.latE7(node), e.nodes.lonE7(node))
            if (c >= 0) add(c)
        }
    }

    /** Bytes the cell index holds. */
    val indexBytes: Long get() = 4L * (nodeCells.start.size + nodeCells.items.size + wayCells.start.size + wayCells.items.size + memberNodeCells.start.size + memberNodeCells.items.size)

    /** A thread's own buffers ([work]): the answer's JSON, the gzip member, the candidates. */
    class Work internal constructor(level: Int) {
        internal val json = JsonOut(1 shl 20)
        internal val nodes = IntList()
        internal val ways = IntList()
        internal val relations = IntList()
        internal val deflater = Deflater(level, true)
        internal val crc = CRC32()
        internal var packed = ByteArray(1 shl 18)
        internal val sha = MessageDigest.getInstance("SHA-256")
    }

    /** Buffers for one thread's windows. */
    fun work(): Work = Work(level)

    /** Window [w]'s answer, cut with [work]'s buffers (one per thread). */
    fun answer(w: Window, work: Work = work()): Answer {
        val box = OverpassBox(w.south, w.west, w.north, w.east)
        val nodes = work.nodes.apply { clear() }
        val ways = work.ways.apply { clear() }
        val relations = work.relations.apply { clear() }
        cells.window(w) { c ->
            for (k in nodeCells.start[c] until nodeCells.start[c + 1]) {
                val t = nodeCells.items[k]
                val node = e.taggedNode[t]
                if (box.holdsNode(e.nodes.latE7(node), e.nodes.lonE7(node))) nodes.add(t)
            }
            for (k in wayCells.start[c] until wayCells.start[c + 1]) ways.add(wayCells.items[k])
            for (k in memberNodeCells.start[c] until memberNodeCells.start[c + 1]) {
                val pair = e.relationNodes[memberNodeCells.items[k]]
                val node = pair.toInt()
                if (box.contains(e.nodeLat(node), e.nodeLon(node))) relations.add((pair ushr 32).toInt())
            }
        }
        nodes.sortUnique()
        ways.sortUnique()
        // the ways that meet the box; any way near it that lacks nodes leaves the window out, since its segments to
        // the nodes the extract does not hold could meet the box
        var kept = 0
        var broken = -1
        for (k in 0 until ways.size) {
            val way = ways[k]
            if (broken < 0 && e.wayBroken[way]) broken = way
            if (meets(way, box)) {
                ways.set(kept++, way)
                val range = e.relationsOf(way)
                for (j in (range ushr 32).toInt() until range.toInt()) relations.add(e.wayRelations[j].toInt())
            }
        }
        ways.truncate(kept)
        relations.sortUnique()
        val elements = nodes.size + ways.size + relations.size
        if (broken >= 0) return Answer(w, null, null, 0, elements, LeftOut.WAY, e.wayIds[broken])
        for (k in 0 until relations.size) {
            val r = relations[k]
            if (e.relationBroken[r]) return Answer(w, null, null, 0, elements, LeftOut.RELATION, e.relationIds[r])
        }
        for (k in 0 until nodes.size) {
            if (e.taggedTwice[nodes[k]]) return Answer(w, null, null, 0, elements, LeftOut.TWICE, e.nodes.id(e.taggedNode[nodes[k]]))
        }
        for (k in 0 until ways.size) if (e.wayTwice[ways[k]]) return Answer(w, null, null, 0, elements, LeftOut.TWICE, e.wayIds[ways[k]])
        for (k in 0 until relations.size) {
            if (e.relationTwice[relations[k]]) return Answer(w, null, null, 0, elements, LeftOut.TWICE, e.relationIds[relations[k]])
        }

        val out = work.json.apply { clear() }
        try {
            out.bytes(head)
            var first = true
            for (k in 0 until nodes.size) {
                if (!first) out.byte(','.code)
                first = false
                node(nodes[k], out)
            }
            for (k in 0 until ways.size) {
                if (!first) out.byte(','.code)
                first = false
                way(ways[k], out)
            }
            for (k in 0 until relations.size) {
                if (!first) out.byte(','.code)
                first = false
                relation(relations[k], out)
            }
            out.bytes(AnswerJson.FOOT)
        } catch (x: IllegalArgumentException) {
            return Answer(w, null, null, 0, elements, LeftOut.TOO_BIG)
        }
        val gz = gzip(out.bytes, out.size, work)
        work.sha.reset()
        return Answer(w, gz, work.sha.digest(gz), out.size, elements, null)
    }

    /** Bbox_Filter::matches: a way of one node by its point, any other by a pair of consecutive points meeting the box. */
    internal fun meets(way: Int, box: OverpassBox): Boolean {
        val from = e.wayStart[way]
        val to = e.wayStart[way + 1]
        if (to == from) return false
        if (to - from == 1L) {
            val node = e.wayRefs[from]
            return node >= 0 && box.contains(e.nodeLat(node), e.nodeLon(node))
        }
        var prev = e.wayRefs[from]
        for (i in from + 1 until to) {
            val node = e.wayRefs[i]
            if (prev >= 0 && node >= 0 && box.intersects(e.nodeLat(prev), e.nodeLon(prev), e.nodeLat(node), e.nodeLon(node))) return true
            prev = node
        }
        return false
    }

    /** The bounds of way [way]'s points (calc_bounds of a Linestring_Geometry). */
    private fun wayBounds(way: Int): OverpassBounds {
        val from = e.wayStart[way]
        val n = (e.wayStart[way + 1] - from).toInt()
        return OverpassBounds.ofPoints(n, { e.nodeLat(e.wayRefs[from + it]) }, { e.nodeLon(e.wayRefs[from + it]) })
    }

    private fun bounds(b: OverpassBounds, out: JsonOut) {
        if (!b.valid) return
        out.ascii(",\"bounds\":{\"minlat\":").fixed7(b.south).ascii(",\"minlon\":").fixed7(b.west)
            .ascii(",\"maxlat\":").fixed7(b.north).ascii(",\"maxlon\":").fixed7(b.east).byte('}'.code)
    }

    /** A way's points as `out geom` prints them: `{"lat":…,"lon":…}` each. */
    private fun points(way: Int, out: JsonOut) {
        val from = e.wayStart[way]
        val to = e.wayStart[way + 1]
        for (i in from until to) {
            if (i > from) out.byte(','.code)
            val node = e.wayRefs[i]
            out.ascii("{\"lat\":").e7(e.nodes.latE7(node).toLong()).ascii(",\"lon\":").e7(e.nodes.lonE7(node).toLong()).byte('}'.code)
        }
    }

    /** output_json.cc's node: type, id, lat, lon, tags. */
    private fun node(t: Int, out: JsonOut) {
        val node = e.taggedNode[t]
        out.ascii("{\"type\":\"node\",\"id\":").long(e.nodes.id(node))
            .ascii(",\"lat\":").e7(e.nodes.latE7(node).toLong()).ascii(",\"lon\":").e7(e.nodes.lonE7(node).toLong())
        tags(e.taggedJson[t], e.taggedLength[t], out)
        out.byte('}'.code)
    }

    /** output_json.cc's way: type, id, bounds, nodes, geometry, tags. */
    private fun way(way: Int, out: JsonOut) {
        out.ascii("{\"type\":\"way\",\"id\":").long(e.wayIds[way])
        bounds(wayBounds(way), out)
        val from = e.wayStart[way]
        val to = e.wayStart[way + 1]
        if (to > from) {
            out.ascii(",\"nodes\":[")
            for (i in from until to) {
                if (i > from) out.byte(','.code)
                out.long(e.nodes.id(e.wayRefs[i]))
            }
            out.byte(']'.code)
        }
        out.ascii(",\"geometry\":[")
        points(way, out)
        out.byte(']'.code)
        tags(e.wayTags[way], e.wayTagLength[way], out)
        out.byte('}'.code)
    }

    /**
     * output_json.cc's relation: type, id, bounds (geometry.cc's calc_bounds over its members' boxes: a node's point, a
     * way's points; a relation member has none), members (a node with its point, a way with its points: none for a way
     * of one node, which Overpass holds as a point; a relation by its reference alone), tags.
     */
    private fun relation(r: Int, out: JsonOut) {
        out.ascii("{\"type\":\"relation\",\"id\":").long(e.relationIds[r])
        val from = e.relationStart[r]
        val to = e.relationStart[r + 1]
        val parts = ArrayList<OverpassBounds>(to - from)
        for (m in from until to) {
            val i = e.memberIndex[m]
            when (e.memberType[m].toInt()) {
                PbfRelation.NODE -> {
                    val la = e.nodeLat(i)
                    val lo = e.nodeLon(i)
                    parts += OverpassBounds(la, lo, la, lo)
                }
                PbfRelation.WAY -> {
                    val n = e.wayStart[i + 1] - e.wayStart[i]
                    if (n >= 1) parts += wayBounds(i)
                }
            }
        }
        bounds(OverpassBounds.ofParts(parts.size) { parts[it] }, out)
        if (to > from) {
            out.ascii(",\"members\":[")
            for (m in from until to) {
                if (m > from) out.byte(','.code)
                val type = e.memberType[m].toInt()
                out.ascii("{\"type\":\"").ascii(TYPES[type]).ascii("\",\"ref\":").long(e.memberRef[m]).ascii(",\"role\":").bytes(e.roles[e.memberRole[m]])
                val i = e.memberIndex[m]
                when (type) {
                    PbfRelation.NODE -> out.ascii(",\"lat\":").e7(e.nodes.latE7(i).toLong()).ascii(",\"lon\":").e7(e.nodes.lonE7(i).toLong())
                    PbfRelation.WAY -> {
                        out.ascii(",\"geometry\":[")
                        if (e.wayStart[i + 1] - e.wayStart[i] >= 2) points(i, out)
                        out.byte(']'.code)
                    }
                }
                out.byte('}'.code)
            }
            out.byte(']'.code)
        }
        tags(e.relationTags[r], e.relationTagLength[r], out)
        out.byte('}'.code)
    }

    /** `,"tags":{…}` from the tags' JSON kept, or nothing for an element without tags. */
    private fun tags(at: Long, length: Int, out: JsonOut) {
        if (length == 0) return
        out.ascii(",\"tags\":{").bytes(e.json.page(at), e.json.offset(at), length).byte('}'.code)
    }

    /**
     * [n] bytes of [data] as one gzip member (RFC 1952): a fixed head (no name, no time, XFL 2, OS unknown), the deflate
     * stream at [level], CRC-32 and size. The same bytes give the same member.
     */
    private fun gzip(data: ByteArray, n: Int, work: Work): ByteArray {
        val d = work.deflater
        d.reset()
        d.setInput(data, 0, n)
        d.finish()
        var buf = work.packed
        System.arraycopy(GZIP_HEAD, 0, buf, 0, GZIP_HEAD.size)
        var size = GZIP_HEAD.size
        while (!d.finished()) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            size += d.deflate(buf, size, buf.size - size)
        }
        work.packed = buf
        work.crc.reset()
        work.crc.update(data, 0, n)
        val crc = work.crc.value
        val member = buf.copyOf(size + 8)
        for (i in 0 until 4) member[size + i] = (crc ushr (8 * i)).toByte()
        for (i in 0 until 4) member[size + 4 + i] = (n ushr (8 * i)).toByte()
        return member
    }

    companion object {
        private val TYPES = arrayOf("node", "way", "relation")
        private val GZIP_HEAD = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 2, 0xff.toByte())
    }
}
