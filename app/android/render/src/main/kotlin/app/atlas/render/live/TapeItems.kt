package app.atlas.render.live

import app.atlas.render.py.PyMath

/**
 * What an item's maker knew of it, kept on the item while its render is taped: the [kind] of thing ("building", "part",
 * "tree", "door", "shopfront", "crane", …), its [owner] (the scene record drawn: an OSM element's dict, a node, a feature),
 * the points its radius cut tested ([fx0], [fz0] and [fx1], [fz1]; the same point twice when it tested one, NaN when it
 * tested none), and the point its depth was sorted by ([sx], [sy], [sz], world metres) with the [off] metres the engine
 * adds to that point's depth.
 */
class ItemNote(
    @JvmField val kind: String, @JvmField val owner: Any?,
    @JvmField val fx0: Double, @JvmField val fz0: Double, @JvmField val fx1: Double, @JvmField val fz1: Double,
    @JvmField val sx: Double, @JvmField val sy: Double, @JvmField val sz: Double, @JvmField val off: Double,
)

/**
 * The painter-order items of one taped render: every object the render sorted by depth and drew (a building, a tree, a
 * lamp, a door, a shopfront, a crane, …), in the order drawn (far to near), with its owner, the points its radius cut
 * tested (`hypot(x − TX, z − TZ) > RAD`), the point and offset its sort depth came from, and the characters it added to
 * the objects group (`<g stroke-linejoin="round" …>`; with `obj_split`, that group in o, then in o2), whose content is
 * the items' fragments joined. [elementRuns] turns those into runs of the group's top-level elements.
 *
 * Filled by the render loops while a [WorldTape] records ([WorldTape.items]); otherwise nothing is kept.
 */
class TapeItems {
    private var n = 0
    private var cap = 0
    // per item: owner id, first character, characters
    private var ints = IntArray(0)
    // per item: sort depth, fx0, fz0, fx1, fz1, sx, sy, sz, off
    private var nums = DoubleArray(0)
    private var kinds = arrayOfNulls<String>(0)
    private var total = 0

    /** Items drawn. */
    val size: Int get() = n

    /** Characters the items added to the objects group, all told. */
    val chars: Int get() = total

    /** Bytes the log's arrays hold (object references counted at 8 bytes). */
    fun bytes(): Long = ints.size * 4L + nums.size * 8L + kinds.size * 8L

    /** Forgets every item, keeping the arrays. */
    fun clear() {
        kinds.fill(null, 0, n)
        n = 0; total = 0
    }

    /** What drew item [i] (an [ItemNote.kind], or the engine's item kind when the maker left no note). */
    fun kind(i: Int): String = kinds[check(i)]!!

    /** Item [i]'s owner id on its tape (0 for none; see [WorldTape.ownerObject]). */
    fun owner(i: Int): Int = ints[check(i) * INTS]

    /** Where item [i]'s fragments start in the objects group's content (the group in o, then in o2, as one text). */
    fun firstChar(i: Int): Int = ints[check(i) * INTS + 1]

    /** How many characters item [i]'s fragments hold. */
    fun charCount(i: Int): Int = ints[check(i) * INTS + 2]

    /** The depth item [i] was sorted by: metres along the frame camera's view direction. */
    fun depth(i: Int): Double = nums[check(i) * NUMS]

    /** How many points item [i]'s radius cut tested: 1, 2 (a segment: kept while either end is within the radius) or 0. */
    fun farPoints(i: Int): Int {
        val o = check(i) * NUMS
        return if (nums[o + 1].isNaN()) 0 else if (nums[o + 1] == nums[o + 3] && nums[o + 2] == nums[o + 4]) 1 else 2
    }

    /** World x of the [k]th point (0 or 1) item [i]'s radius cut tested; NaN when it tested none. */
    fun farX(i: Int, k: Int = 0): Double = nums[check(i) * NUMS + 1 + 2 * k.coerceIn(0, 1)]

    /** World z of the [k]th point (0 or 1) item [i]'s radius cut tested; NaN when it tested none. */
    fun farZ(i: Int, k: Int = 0): Double = nums[check(i) * NUMS + 2 + 2 * k.coerceIn(0, 1)]

    /** World x of the point item [i]'s depth was sorted by (NaN when its maker left no note). */
    fun sortX(i: Int): Double = nums[check(i) * NUMS + 5]

    /** World y (height) of the point item [i]'s depth was sorted by: its sort height. */
    fun sortY(i: Int): Double = nums[check(i) * NUMS + 6]

    /** World z of the point item [i]'s depth was sorted by. */
    fun sortZ(i: Int): Double = nums[check(i) * NUMS + 7]

    /** Metres the engine added to the sort point's camera depth to get item [i]'s [depth]. */
    fun sortOffset(i: Int): Double = nums[check(i) * NUMS + 8]

    /**
     * Whether the engine's radius cut about ([tx], [tz]) with radius [rad] drops item [i], as its `far` test does: every
     * point it tested lies beyond the radius. An item that tested none is never dropped.
     */
    fun cut(i: Int, tx: Double, tz: Double, rad: Double): Boolean {
        val o = check(i) * NUMS
        if (nums[o + 1].isNaN()) return false
        return PyMath.hypot(nums[o + 1] - tx, nums[o + 2] - tz) > rad && PyMath.hypot(nums[o + 3] - tx, nums[o + 4] - tz) > rad
    }

    /**
     * Logs one item drawn: [kind], [owner] id, its sort [depth], the [note] its maker left (or none), and the fragments it
     * added to the objects group, `out[from until out.size]`.
     */
    fun add(kind: String, owner: Int, depth: Double, note: ItemNote?, out: List<String>, from: Int) {
        if (n == cap) grow()
        var len = 0
        for (j in from until out.size) len += out[j].length
        val io = n * INTS
        ints[io] = owner; ints[io + 1] = total; ints[io + 2] = len
        val o = n * NUMS
        nums[o] = depth
        if (note != null) {
            nums[o + 1] = note.fx0; nums[o + 2] = note.fz0; nums[o + 3] = note.fx1; nums[o + 4] = note.fz1
            nums[o + 5] = note.sx; nums[o + 6] = note.sy; nums[o + 7] = note.sz; nums[o + 8] = note.off
        } else {
            for (k in 1 until NUMS) nums[o + k] = Double.NaN
        }
        kinds[n] = kind
        total += len
        n++
    }

    /**
     * Each item's run of the objects group's top-level elements, read from the render's layers that hold the group
     * ([layers]: o, then o2; layers without it are passed over): the index of the item's first element among the
     * group's children and how many it added, as pairs in item order. Fails when the groups' content is not the items'
     * fragments: another length, or an item with characters that does not start an element at its first one (its
     * fragments began inside the item before it).
     */
    fun elementRuns(layers: List<String?>): IntArray {
        var starts = IntArray(1024); var m = 0
        var base = 0
        for (svg in layers) {
            if (svg == null) continue
            val content = LongArray(1)
            m = children(svg, base, content, starts, m).let { (s, k) -> starts = s; k }
            base += content[0].toInt()
        }
        check(base == total) { "the objects groups hold $base characters, the items $total" }
        val runs = IntArray(n * 2)
        var c = 0
        for (k in 0 until n) {
            val lo = ints[k * INTS + 1]; val hi = lo + ints[k * INTS + 2]
            while (c < m && starts[c] < lo) c++
            val first = c
            while (c < m && starts[c] < hi) c++
            check(hi == lo || (c > first && starts[first] == lo)) { "item $k (${kinds[k]}) does not start an element at its character $lo" }
            runs[k * 2] = first; runs[k * 2 + 1] = c - first
        }
        return runs
    }

    private fun grow() {
        cap = if (cap == 0) 256 else cap * 2
        ints = ints.copyOf(cap * INTS); nums = nums.copyOf(cap * NUMS); kinds = kinds.copyOf(cap)
    }

    private fun check(i: Int): Int {
        if (i < 0 || i >= n) throw IndexOutOfBoundsException("item $i of $n")
        return i
    }

    companion object {
        private const val INTS = 3
        private const val NUMS = 9
        private const val OBJECTS = "<g stroke-linejoin=\"round\""

        /** The index of the `>` that ends the tag opening at [i], skipping quoted values; -1 if it never ends. */
        private fun tagEnd(s: String, i: Int): Int {
            var j = i + 1
            val len = s.length
            while (j < len) { // names char by char, quoted values (most of the text: path data) by indexOf
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

        /**
         * Appends to [starts] (from [m]) the offsets, counted from [base], at which the top-level elements of [svg]'s
         * objects group (`<g stroke-linejoin="round" …>` inside the frame's clip group) start in the group's content,
         * and puts the content's length in [length]. Returns the array (grown when needed) and the new count.
         */
        private fun children(svg: String, base: Int, length: LongArray, starts0: IntArray, m0: Int): Pair<IntArray, Int> {
            var starts = starts0; var m = m0
            var depth = 0; var from = -1
            var i = svg.indexOf('<')
            while (i >= 0 && i < svg.length - 1) {
                val c = svg[i + 1]
                if (c == '/' || c == '!' || c == '?') { // a closing tag, a comment, a declaration
                    if (c == '/') {
                        depth--
                        if (from >= 0 && depth == 2) { length[0] = (i - from).toLong(); return starts to m }
                    }
                    val e = if (svg.startsWith("<!--", i)) svg.indexOf("-->", i + 4).let { if (it < 0) -1 else it + 2 } else svg.indexOf('>', i + 2)
                    if (e < 0) break
                    i = svg.indexOf('<', e + 1)
                    continue
                }
                if (from >= 0 && depth == 3) {
                    if (m == starts.size) starts = starts.copyOf(m * 2)
                    starts[m++] = base + (i - from)
                }
                val j = tagEnd(svg, i)
                if (j < 0) break
                if (svg[j - 1] != '/') {
                    if (from < 0 && depth == 2 && svg.startsWith(OBJECTS, i)) from = j + 1
                    depth++
                }
                i = svg.indexOf('<', j + 1)
            }
            length[0] = 0
            return starts to m
        }
    }
}
