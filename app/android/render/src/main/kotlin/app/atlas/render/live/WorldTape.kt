package app.atlas.render.live

import app.atlas.render.City
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The world point behind every screen point of one render. While a tape is [recording], each `Camera.pc` of the frame's
 * camera records the screen point it returns, keyed exactly as the SVG text prints it (`%.1f`, times ten), with the depth
 * of the camera-space point it projected and the [owner] being drawn at the time.
 *
 * An entry's world point ([x], [y], [z]) is the frame camera's ray through the entry's printed screen point, at the
 * entry's depth: re-projected by the frame's camera it lands on the SVG's own coordinates, and it lies within half a
 * tenth of a pixel (at its depth) of the point the engine projected.
 *
 * While recording, a call only appends 16 bytes to a paged log (pages never copy), unless it repeats bit for bit one of
 * the last calls (a 1024-slot direct-mapped memory of them, which drops most of a frame's repeats before they are
 * logged); no lookup into the tape, so the render barely slows. [seal], or the first query, folds the log in place, in
 * recording order, into its distinct entries: a call that
 * repeats an entry (same key and owner, depth within [SAME] metres) is dropped, and points that print the same screen
 * point at another depth or for another owner become alternatives of the key's first entry ([next]). [resolve] picks
 * among them from the rest of a path. Once sealed, the entries are 0 until [size], in the order of their first call; an
 * entry id (from [find], [findKey], [next] or that range) stays valid until [reset].
 *
 * Recording never changes what the render returns. Not thread-safe: one render records on one thread; [recording]
 * closes the tape when the render returns; whoever takes it then calls [seal] before handing it to other threads.
 */
class WorldTape(frame: City.Camera? = null) {
    // per record (and, once sealed, per entry), two longs in its page: the screen key; the depth's float bits (high
    // half) and the owner (low half)
    private var pages = arrayOfNulls<LongArray>(16)
    private var pageCount = 0
    private var cur: LongArray? = null
    private var pos = PAGE * 2
    private var n = 0
    // the records of the latest calls, by a hash of the record: a call that repeats one of them bit for bit is not logged
    // again (it would fold as its twin does); a record never holds 0, 0 (depth 0 has no screen point)
    private val recent = LongArray(RECENT * 2)

    // once sealed: per entry, its next alternative (-1 for none)
    private var links = arrayOfNulls<IntArray>(16)
    private var linkCount = 0

    // once sealed: open addressing over the keys' first entries, each slot the entry shifted left 8 or-ed with a
    // fingerprint of its key; -1 empty
    private var index = IntArray(0)
    private var mask = 0
    private var heads = 0

    /** True once the render that recorded it has returned (or [seal]): records are ignored, and counted in [late]. */
    var closed = false
        private set

    /** True once the log is folded into entries and indexed. */
    var sealed = false
        private set

    /** `Camera.pc` calls of the frame camera, repeats included. */
    var calls = 0L
        private set

    /** Calls whose screen point was not finite or beyond the key range (about ±2.6 × 10^7 px), so not kept. */
    var skipped = 0L
        private set

    /** Calls after the tape was closed (not kept). */
    var late = 0L
        private set

    /** Calls dropped because their key already had [MAX_ALTERNATIVES] entries, or the log [MAX_RECORDS]. */
    var overflow = 0L
        private set

    /** Cameras made while recording that do not project as the frame does (so they were not taped). */
    var foreignCameras = 0
        private set

    /** The most bytes the tape's arrays have held at once since it was made. */
    var peakBytes = 0L
        private set

    private var ambiguous = 0
    private var touched = 0 // the sum of the fold's read-ahead reads, kept so that the reads are made

    /** Whether the tape knows its frame camera (given at construction, or the first camera taped). */
    var framed = false
        private set
    private var fFocal = 0.0; private var fCx = 0.0; private var fCy = 0.0
    private var c0 = 0.0; private var c1 = 0.0; private var c2 = 0.0
    private var r0 = 0.0; private var r1 = 0.0; private var r2 = 0.0
    private var u0 = 0.0; private var u1 = 0.0; private var u2 = 0.0
    private var f0 = 0.0; private var f1 = 0.0; private var f2 = 0.0

    private val ownerIds = IdentityHashMap<Any, Int>()
    private val ownerList = ArrayList<Any>()
    private var current = 0

    init {
        if (frame != null) adopt(frame)
    }

    /** Empties the tape for the next render, keeping its pages and index. */
    fun reset() {
        n = 0; heads = 0; closed = false; sealed = false
        calls = 0; skipped = 0; late = 0; overflow = 0; ambiguous = 0; foreignCameras = 0
        cur = pages[0]; pos = if (cur == null) PAGE * 2 else 0
        recent.fill(0)
        framed = false; ownerIds.clear(); ownerList.clear(); current = 0
    }

    /** Whether camera [cam] is taped: it projects exactly as the first camera taped (or the frame given at construction). */
    internal fun adopt(cam: City.Camera): Boolean {
        if (!framed) {
            fFocal = cam.focal; fCx = cam.cx; fCy = cam.cy
            c0 = cam.C.x; c1 = cam.C.y; c2 = cam.C.z
            r0 = cam.r.x; r1 = cam.r.y; r2 = cam.r.z
            u0 = cam.u.x; u1 = cam.u.y; u2 = cam.u.z
            f0 = cam.f.x; f1 = cam.f.y; f2 = cam.f.z
            framed = true
            return true
        }
        val same = cam.focal == fFocal && cam.cx == fCx && cam.cy == fCy &&
            cam.C.x == c0 && cam.C.y == c1 && cam.C.z == c2 && cam.r.x == r0 && cam.r.y == r1 && cam.r.z == r2 &&
            cam.u.x == u0 && cam.u.y == u1 && cam.u.z == u2 && cam.f.x == f0 && cam.f.y == f1 && cam.f.z == f2
        if (!same) foreignCameras++
        return same
    }

    /** The owner of what is drawn from now on: an OSM element's dict, a tree, a POI; null for none (owner 0). */
    fun setOwner(o: Any?) {
        current = if (o == null) 0 else ownerIds.getOrPut(o) { ownerList.add(o); ownerList.size }
    }

    /** The object behind owner [id] (1-based), or null for 0. */
    fun ownerObject(id: Int): Any? = if (id <= 0) null else ownerList[id - 1]

    /** Owners registered. */
    val ownerCount: Int get() = ownerList.size

    /** One `Camera.pc` of the frame camera: a camera-space point at [depth] metres projected to ([sx], [sy]). */
    fun record(depth: Double, sx: Double, sy: Double) {
        calls++
        if (closed) { late++; return }
        val ix = key10(sx); val iy = key10(sy)
        if (ix == NO_KEY || iy == NO_KEY) { skipped++; return }
        val k = pack(ix, iy)
        val v = (depth.toFloat().toRawBits().toLong() shl 32) or (current.toLong() and LOW)
        val r = ((k xor v) * -0x61c8864680b583ebL ushr (64 - RECENT_BITS)).toInt() * 2
        if (recent[r] == k && recent[r + 1] == v) return
        recent[r] = k; recent[r + 1] = v
        if (pos == PAGE * 2 && !nextPage()) return
        val page = cur!!
        page[pos] = k; page[pos + 1] = v
        pos += 2; n++
    }

    private fun nextPage(): Boolean {
        if (n >= MAX_RECORDS) { overflow++; return false }
        val p = n ushr PAGE_BITS
        if (p == pages.size) pages = pages.copyOf(p * 2)
        if (pages[p] == null) { pages[p] = LongArray(PAGE * 2); pageCount = p + 1; peak() }
        cur = pages[p]; pos = 0
        return true
    }

    /** Closes the tape (it records nothing more until [reset]), then folds its log into entries and indexes them. Idempotent. */
    fun seal() {
        closed = true
        if (sealed) return
        sealed = true
        var cap = 16
        while (cap / 4 * 3 < n && cap < MAX_SLOTS) cap = cap shl 1 // never more keys than records: never more than 3/4 full
        if (index.size != cap) index = IntArray(cap)
        index.fill(-1)
        mask = cap - 1; heads = 0
        peak()
        var m = 0
        // the index is read AHEAD records ahead of the fold (the read's value is kept, so it is not dropped), so that
        // its cache line is there when the record's turn comes
        val ring = LongArray(AHEAD)
        var touch = 0
        for (j in 0 until Math.min(AHEAD, n)) {
            val hj = mix(pages[j ushr PAGE_BITS]!![(j and PAGE_MASK) * 2]); ring[j] = hj; touch += index[hj.toInt() and mask]
        }
        for (i in 0 until n) {
            val page = pages[i ushr PAGE_BITS]!!
            val o = (i and PAGE_MASK) * 2
            val k = page[o]; val v = page[o + 1]
            val h = ring[i and (AHEAD - 1)]
            val j = i + AHEAD
            if (j < n) {
                val hj = mix(pages[j ushr PAGE_BITS]!![(j and PAGE_MASK) * 2]); ring[j and (AHEAD - 1)] = hj; touch += index[hj.toInt() and mask]
            }
            var s = h.toInt() and mask
            val fp = (h ushr 56).toInt()
            var head = -1
            while (true) {
                val x = index[s]
                if (x < 0) break
                if ((x and 0xFF) == fp && keyOf(x ushr 8) == k) { head = x ushr 8; break }
                s = (s + 1) and mask
            }
            if (head < 0) { // a new key: its first entry
                put(m, k, v)
                index[s] = (m shl 8) or fp
                m++
                heads++
                continue
            }
            var e = head; var count = 1; var repeat = false
            while (true) {
                if (same(valOf(e), v)) { repeat = true; break }
                val nx = linkOf(e)
                if (nx < 0) break
                e = nx; count++
            }
            if (repeat) continue
            if (count >= MAX_ALTERNATIVES) { overflow++; continue }
            put(m, k, v)
            links[e ushr PAGE_BITS]!![e and PAGE_MASK] = m
            if (count == 1) ambiguous++
            m++
        }
        n = m
        touched = touch
        val used = (m + PAGE - 1) ushr PAGE_BITS // the pages the entries left free go back to the heap
        for (p in used until pageCount) pages[p] = null
        pageCount = used
        for (p in used until linkCount) links[p] = null
        linkCount = Math.min(linkCount, used)
        cur = null; pos = PAGE * 2
    }

    // entry m at its page place (m is never past the record being read, so the fold runs in place), with no alternative yet
    private fun put(m: Int, k: Long, v: Long) {
        val p = m ushr PAGE_BITS
        val page = pages[p]!!
        val o = (m and PAGE_MASK) * 2
        page[o] = k; page[o + 1] = v
        if (p == linkCount) {
            if (p == links.size) links = links.copyOf(p * 2)
            if (links[p] == null) links[p] = IntArray(PAGE)
            linkCount = p + 1
            peak()
        }
        links[p]!![m and PAGE_MASK] = -1
    }

    private fun same(a: Long, b: Long): Boolean {
        if (a == b) return true
        if ((a and LOW) != (b and LOW)) return false
        return Math.abs(Float.fromBits((a ushr 32).toInt()) - Float.fromBits((b ushr 32).toInt())) <= SAME
    }

    private fun peak() {
        peakBytes = Math.max(peakBytes, bytes())
    }

    private fun keyOf(e: Int): Long = pages[e ushr PAGE_BITS]!![(e and PAGE_MASK) * 2]

    private fun valOf(e: Int): Long = pages[e ushr PAGE_BITS]!![(e and PAGE_MASK) * 2 + 1]

    private fun linkOf(e: Int): Int = links[e ushr PAGE_BITS]!![e and PAGE_MASK]

    private fun lookup(k: Long): Int {
        seal()
        val h = mix(k)
        var s = h.toInt() and mask
        val fp = (h ushr 56).toInt()
        while (true) {
            val x = index[s]
            if (x < 0) return -1
            if ((x and 0xFF) == fp && keyOf(x ushr 8) == k) return x ushr 8
            s = (s + 1) and mask
        }
    }

    /** Entries, alternatives included (calls logged, until sealed). */
    val size: Int get() = n

    /** Distinct screen keys; seals the tape. */
    val keys: Int get() { seal(); return heads }

    /** Screen keys with more than one entry; seals the tape. */
    val ambiguousKeys: Int get() { seal(); return ambiguous }

    /** The first entry for the screen point printed as ([sx], [sy]) in the SVG (values on the 0.1 grid), or -1. Seals the tape. */
    fun find(sx: Double, sy: Double): Int {
        if (!(Math.abs(sx) < KEY_LIMIT && Math.abs(sy) < KEY_LIMIT)) return -1
        return findKey(Math.round(sx * 10), Math.round(sy * 10))
    }

    /** The first entry for the screen key ([ix], [iy]) (the `%.1f` text times ten), or -1. Seals the tape. */
    fun findKey(ix: Long, iy: Long): Int {
        if (ix <= -KEY_RANGE || ix >= KEY_RANGE || iy <= -KEY_RANGE || iy >= KEY_RANGE) return -1
        return lookup(pack(ix, iy))
    }

    /** The next alternative of entry [e] (another depth or owner at the same screen key), or -1. */
    fun next(e: Int): Int = linkOf(e)

    /** The screen x of entry [e] as its `%.1f` text times ten. */
    fun screenX10(e: Int): Long = unpack(keyOf(e) ushr 29)

    /** The screen y of entry [e] as its `%.1f` text times ten. */
    fun screenY10(e: Int): Long = unpack(keyOf(e))

    /** The camera-space depth of entry [e], metres along the frame camera's view direction. */
    fun depth(e: Int): Double = Float.fromBits((valOf(e) ushr 32).toInt()).toDouble()

    /** The owner id of entry [e] (0 for none; see [ownerObject]). */
    fun owner(e: Int): Int = valOf(e).toInt()

    /** World x of entry [e], metres in the region's frame. */
    fun x(e: Int): Double { val d = depth(e); return c0 + r0 * across(e, d) + u0 * up(e, d) + f0 * d }

    /** World y (height) of entry [e], metres. */
    fun y(e: Int): Double { val d = depth(e); return c1 + r1 * across(e, d) + u1 * up(e, d) + f1 * d }

    /** World z of entry [e], metres. */
    fun z(e: Int): Double { val d = depth(e); return c2 + r2 * across(e, d) + u2 * up(e, d) + f2 * d }

    /** Writes entry [e]'s world x, y, z into [out] from [at]. */
    fun world(e: Int, out: DoubleArray, at: Int = 0) {
        val d = depth(e); val a = across(e, d); val b = up(e, d)
        out[at] = c0 + r0 * a + u0 * b + f0 * d
        out[at + 1] = c1 + r1 * a + u1 * b + f1 * d
        out[at + 2] = c2 + r2 * a + u2 * b + f2 * d
    }

    // the camera-space x and y of the frame camera's ray through entry e's printed screen point, at depth d
    private fun across(e: Int, d: Double): Double {
        check(framed) { "the tape has no frame camera" }
        return (unpack(keyOf(e) ushr 29) / 10.0 - fCx) * d / fFocal
    }

    private fun up(e: Int, d: Double): Double = (fCy - unpack(keyOf(e)) / 10.0) * d / fFocal

    /** Bytes the tape's arrays hold now. */
    fun bytes(): Long = pageCount.toLong() * PAGE * 16 + linkCount.toLong() * PAGE * 4 + index.size * 4L

    /**
     * The entries of one drawn path's [n] vertices ([xs], [ys], as its SVG prints them) into [out] (-1 where a vertex is
     * not on the tape), choosing among a key's alternatives from the path itself: the owner most of its unambiguous
     * vertices have, then the world point nearest their centroid. Returns how many vertices are on the tape. Seals the tape.
     */
    fun resolve(xs: DoubleArray, ys: DoubleArray, n: Int, out: IntArray): Int {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var m = 0; var found = 0
        var pending = false
        val w = DoubleArray(3)
        for (j in 0 until n) {
            val e = find(xs[j], ys[j])
            if (e < 0) { out[j] = -1; continue }
            found++
            if (next(e) < 0) {
                out[j] = e; world(e, w); sx += w[0]; sy += w[1]; sz += w[2]; m++
            } else { out[j] = -2 - e; pending = true }
        }
        if (!pending) return found
        val major = majorOwner(out, n)
        if (m > 0) { sx /= m; sy /= m; sz /= m }
        for (j in 0 until n) {
            if (out[j] > -2) continue
            var e = -2 - out[j]
            var best = -1; var bestD = Double.MAX_VALUE; var ownerHit = false
            while (e >= 0) {
                val hit = major != 0 && owner(e) == major
                if (hit && !ownerHit) { ownerHit = true; best = -1; bestD = Double.MAX_VALUE }
                if (hit || !ownerHit) {
                    val d = if (m == 0) 0.0 else {
                        world(e, w)
                        val dx = w[0] - sx; val dy = w[1] - sy; val dz = w[2] - sz
                        dx * dx + dy * dy + dz * dz
                    }
                    if (best < 0 || d < bestD) { best = e; bestD = d }
                }
                e = next(e)
            }
            out[j] = best
        }
        return found
    }

    private fun majorOwner(out: IntArray, n: Int): Int {
        var cand = 0; var count = 0
        for (j in 0 until n) { // Boyer–Moore majority vote over the unambiguous vertices' owners
            val e = out[j]
            if (e < 0) continue
            val o = owner(e)
            if (count == 0) { cand = o; count = 1 } else if (o == cand) count++ else count--
        }
        return cand
    }

    companion object {
        /** A key holds at most this many entries (its first and the alternatives). */
        const val MAX_ALTERNATIVES = 63

        /** Two depths at one key and owner closer than this, metres, are one point. */
        const val SAME = 0.005f

        /** At most this many calls are logged (16 bytes each: 67 MB, several times the densest frame's). */
        const val MAX_RECORDS = 1 shl 22

        private const val AHEAD = 16
        private const val RECENT_BITS = 10
        private const val RECENT = 1 shl RECENT_BITS
        private const val PAGE_BITS = 15
        private const val PAGE = 1 shl PAGE_BITS
        private const val PAGE_MASK = PAGE - 1
        private const val MAX_SLOTS = MAX_RECORDS * 2
        private const val KEY_RANGE = 1L shl 28
        private const val KEY_LIMIT = (KEY_RANGE - 1) / 10.0
        private const val LOW = 0xFFFFFFFFL
        private const val NO_KEY = Long.MIN_VALUE

        // the key: x and y times ten, in 29 signed bits each
        private fun pack(ix: Long, iy: Long): Long = ((ix and 0x1FFFFFFFL) shl 29) or (iy and 0x1FFFFFFFL)

        private fun unpack(v: Long): Long = ((v and 0x1FFFFFFFL) shl 35) shr 35

        // the low bits pick the slot, the top byte is the slot's fingerprint (most probes then skip reading the page)
        private fun mix(k: Long): Long {
            var h = k * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h * -0x4b47d5b1f4a7f2c3L
        }

        /**
         * `'%.1f' % v` times ten, exactly as Python prints it (round half to even on the double's exact value), or
         * [Long.MIN_VALUE] when [v] is not finite or its key is out of range. 10·v is split exactly into s + e
         * (8v and 2v are exact, then Fast2Sum), so only a tie at s ± 0.5 needs e to decide.
         */
        fun key10(v: Double): Long {
            if (!(Math.abs(v) < KEY_LIMIT)) return NO_KEY
            val a = v * 8.0; val b = v * 2.0
            val s = a + b
            val e = b - (s - a)
            val r = Math.rint(s)
            val d = s - r
            val k = if (d == 0.5) { if (e > 0) r + 1 else r } else if (d == -0.5) { if (e < 0) r - 1 else r } else r
            return k.toLong()
        }

        private val local = ThreadLocal<WorldTape?>()
        private val open = AtomicInteger()

        /**
         * Runs [block] (one render) with [tape] recording on this thread: the cameras made inside it that project as the
         * frame record into it. The tape is closed when the block returns; [seal] it before reading it elsewhere.
         */
        fun <T> recording(tape: WorldTape, block: () -> T): T {
            val prev = local.get()
            local.set(tape); open.incrementAndGet()
            try {
                return block()
            } finally {
                open.decrementAndGet(); local.set(prev)
                tape.closed = true
            }
        }

        /** The tape recording on this thread, or null. */
        fun active(): WorldTape? = if (open.get() == 0) null else local.get()

        /** The tape a camera being made records into: the active one, when the camera projects as its frame. */
        fun attach(cam: City.Camera): WorldTape? {
            if (open.get() == 0) return null
            val t = local.get() ?: return null
            return if (!t.closed && t.adopt(cam)) t else null
        }

        /** Marks what the render loops draw next as [o]'s (null: no owner); does nothing without a tape. */
        fun owner(o: Any?) {
            if (open.get() == 0) return
            local.get()?.setOwner(o)
        }
    }
}
