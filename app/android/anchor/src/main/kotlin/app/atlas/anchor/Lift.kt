package app.atlas.anchor

import app.atlas.render.City
import app.atlas.render.live.WorldTape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The anchor's points as they are made: each point's SVG coordinate (anchor CSS px, as the parser read it), its place
 * in the anchor camera's eye frame, its lift class and, for billboards, the point it hangs on. [build] computes the
 * residuals and freezes the table.
 */
internal class PointTable(capacity: Int = 1024) {
    var size = 0
        private set
    private var svg = DoubleArray(capacity * 2)
    private var xyz = FloatArray(capacity * 3)
    private var lift = ByteArray(capacity)
    // per point, its owner on the tape (taped points) or −1
    private var owner = IntArray(capacity)
    private var bbPoint = IntArray(64)
    private var bbHang = IntArray(64)
    private var bbOff = FloatArray(128)
    private var bbCount = 0

    /** Residuals beyond the 16-bit range, clamped (counted; a gate keeps it 0). */
    var overflow = 0
        private set

    /** The largest residual per lift class at [build], CSS px. */
    val maxResidual = DoubleArray(LiftClass.COUNT)

    /** Adds a point at SVG coordinate ([x], [y]), unlifted (screen class until lifted); returns its index. */
    fun add(x: Double, y: Double): Int {
        if (size == lift.size) grow()
        val i = size++
        svg[i * 2] = x; svg[i * 2 + 1] = y
        xyz[i * 3] = x.toFloat(); xyz[i * 3 + 1] = y.toFloat(); xyz[i * 3 + 2] = 0f
        lift[i] = UNLIFTED
        owner[i] = -1
        return i
    }

    private fun grow() {
        val c = lift.size * 2
        svg = svg.copyOf(c * 2); xyz = xyz.copyOf(c * 3); lift = lift.copyOf(c); owner = owner.copyOf(c)
    }

    /** Forgets the last point added (a path's closing vertex that repeats its first). */
    fun dropLast() {
        check(size > 0)
        size--
    }

    fun svgX(i: Int): Double = svg[i * 2]
    fun svgY(i: Int): Double = svg[i * 2 + 1]
    fun liftOf(i: Int): Byte = lift[i]
    fun lifted(i: Int): Boolean = lift[i] != UNLIFTED
    fun ownerOf(i: Int): Int = owner[i]
    fun eyeX(i: Int): Float = xyz[i * 3]
    fun eyeY(i: Int): Float = xyz[i * 3 + 1]
    fun eyeZ(i: Int): Float = xyz[i * 3 + 2]

    /** Places point [i] at eye-frame ([x], [y], [z]) with lift class [cls] (and, for a taped point, its tape owner). */
    fun place(i: Int, x: Double, y: Double, z: Double, cls: Byte, tapeOwner: Int = -1) {
        xyz[i * 3] = x.toFloat(); xyz[i * 3 + 1] = y.toFloat(); xyz[i * 3 + 2] = z.toFloat()
        lift[i] = cls
        owner[i] = tapeOwner
    }

    /** Fixes point [i] to the screen at its own SVG coordinate. */
    fun screen(i: Int) {
        xyz[i * 3] = svg[i * 2].toFloat(); xyz[i * 3 + 1] = svg[i * 2 + 1].toFloat(); xyz[i * 3 + 2] = 0f
        lift[i] = LiftClass.SCREEN
    }

    /** Hangs point [i] on taped point [h], at its SVG coordinate's offset from [h]'s projection on [cam]'s screen. */
    fun billboard(i: Int, h: Int, cam: AnchorCamera) {
        val hx = xyz[h * 3].toDouble(); val hy = xyz[h * 3 + 1].toDouble(); val hz = xyz[h * 3 + 2].toDouble()
        xyz[i * 3] = xyz[h * 3]; xyz[i * 3 + 1] = xyz[h * 3 + 1]; xyz[i * 3 + 2] = xyz[h * 3 + 2]
        lift[i] = LiftClass.BILLBOARD
        if (bbCount == bbPoint.size) {
            bbPoint = bbPoint.copyOf(bbCount * 2); bbHang = bbHang.copyOf(bbCount * 2); bbOff = bbOff.copyOf(bbCount * 4)
        }
        bbPoint[bbCount] = i; bbHang[bbCount] = h
        bbOff[bbCount * 2] = (svg[i * 2] - cam.sx(hx, hz)).toFloat()
        bbOff[bbCount * 2 + 1] = (svg[i * 2 + 1] - cam.sy(hy, hz)).toFloat()
        bbCount++
    }

    /** The table as [AnchorPoints], with each point's residual at [cam]; every point must be lifted. */
    fun build(cam: AnchorCamera): AnchorPoints {
        val n = size
        // billboards are added in point order except dash cuts made later: sort the table by point
        val order = (0 until bbCount).sortedBy { bbPoint[it] }
        val bp = IntArray(bbCount) { bbPoint[order[it]] }
        val bh = IntArray(bbCount) { bbHang[order[it]] }
        val bo = FloatArray(bbCount * 2) { bbOff[order[it / 2] * 2 + (it and 1)] }
        val pts = AnchorPoints(xyz.copyOf(n * 3), ShortArray(n * 2), lift.copyOf(n), bp, bh, bo)
        for (i in 0 until n) check(lift[i] != UNLIFTED) { "point $i was never lifted" }
        overflow = 0
        maxResidual.fill(0.0)
        val res = pts.residual
        for (i in 0 until n) {
            val dx = svg[i * 2] - pts.projectedX(cam, i)
            val dy = svg[i * 2 + 1] - pts.projectedY(cam, i)
            val c = lift[i].toInt()
            maxResidual[c] = max(maxResidual[c], max(abs(dx), abs(dy)))
            res[i * 2] = quantise(dx); res[i * 2 + 1] = quantise(dy)
        }
        return pts
    }

    private fun quantise(d: Double): Short {
        val v = (d * 1000.0).roundToInt()
        if (v > Short.MAX_VALUE || v < -Short.MAX_VALUE) {
            overflow++
            return if (v > 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort()
        }
        return v.toShort()
    }

    companion object {
        const val UNLIFTED: Byte = -1
    }
}

/**
 * Lifts an element's points into the anchor camera's eye frame (plan-gpu D2, "Lifting"): first from the world tape
 * ([tape]), choosing among a key's alternatives by the element's owner and then the nearest world point; then the rest
 * by a plane through the element's taped points, as billboards on a taped point of the same owner, onto the ground
 * plane (the ground's own elements), or fixed to the screen.
 */
internal class Lifter(private val tape: WorldTape, private val cam: AnchorCamera, private val pts: PointTable) {
    private val cx = cam.cx
    private val cy = cam.cy
    private val focal = cam.focal

    // the tape's entries by owner and on a screen grid (the projected points the engine built screen offsets from,
    // printed or not), for billboards; built when the first billboard needs them
    private var ownerStart: IntArray? = null
    private var ownerEntries: IntArray? = null
    private val entryPoints = LongIntMap(64)

    // scratch per element
    private var entries = IntArray(64)
    private val w = DoubleArray(3)

    /** Counts for reports and gates. */
    val stats = LiftStats()

    /**
     * Looks points [first] until [first] + [count] up on the tape (those on the `%.1f` grid only, when [onGrid] is true);
     * [itemOwner] is the element's owner on the tape when the objects' items say it (else −1). Returns the element's
     * owner (the item's, else the majority of its unambiguous taped points', else 0).
     */
    fun tapeElement(first: Int, count: Int, itemOwner: Int, onGrid: Boolean): Int {
        if (entries.size < count) entries = IntArray(count * 2)
        var found = 0
        var pending = false
        for (j in 0 until count) {
            val i = first + j
            entries[j] = -1
            if (!onGrid) continue
            val e = find(pts.svgX(i), pts.svgY(i))
            if (e < 0) continue
            found++
            if (tape.next(e) < 0) entries[j] = e else { entries[j] = -2 - e; pending = true }
        }
        val owner = if (itemOwner >= 0) itemOwner else majority(count)
        if (pending) resolve(count, owner)
        for (j in 0 until count) {
            val e = entries[j]
            if (e < 0) continue
            val i = first + j
            val d = tape.depth(e)
            val sx = tape.screenX10(e) / 10.0; val sy = tape.screenY10(e) / 10.0
            pts.place(i, (sx - cx) * d / focal, (cy - sy) * d / focal, d, LiftClass.TAPED, tape.owner(e))
        }
        stats.lookups += count
        stats.found += found
        return if (owner >= 0) owner else 0
    }

    /** The tape entry printed at ([x], [y]) when it lies on the `%.1f` grid (as the parser read it), or −1. */
    private fun find(x: Double, y: Double): Int {
        val kx = grid10(x); val ky = grid10(y)
        if (kx == Long.MIN_VALUE || ky == Long.MIN_VALUE) return -1
        return tape.findKey(kx, ky)
    }

    // x times ten when x lies on the 0.1 grid within the precision a float parse leaves, else MIN_VALUE
    private fun grid10(v: Double): Long {
        val t = v * 10.0
        val k = Math.rint(t)
        val tol = max(1e-3, 15.0 * Math.ulp(v.toFloat()).toDouble())
        return if (abs(t - k) <= tol && abs(k) < 2.6e8) k.toLong() else Long.MIN_VALUE
    }

    private fun majority(count: Int): Int {
        var cand = -1; var c = 0
        for (j in 0 until count) {
            val e = entries[j]
            if (e < 0) continue
            val o = tape.owner(e)
            if (c == 0) { cand = o; c = 1 } else if (o == cand) c++ else c--
        }
        return cand
    }

    /** Chooses among the alternatives of the pending entries: the owner's first, then the world point nearest the element's others. */
    private fun resolve(count: Int, owner: Int) {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var m = 0
        for (j in 0 until count) {
            val e = entries[j]
            if (e < 0) continue
            tape.world(e, w); sx += w[0]; sy += w[1]; sz += w[2]; m++
        }
        for (j in 0 until count) {
            if (entries[j] > -2) continue
            var e = -2 - entries[j]
            var best = -1; var bestD = Double.MAX_VALUE; var ownerHit = false
            while (e >= 0) {
                val hit = owner > 0 && tape.owner(e) == owner
                if (hit && !ownerHit) { ownerHit = true; best = -1; bestD = Double.MAX_VALUE }
                if (hit || !ownerHit) {
                    tape.world(e, w)
                    // with nothing to compare against, the nearest to the camera (the front surface is what shows)
                    val d = if (m == 0) tape.depth(e) else {
                        val dx = w[0] - sx / m; val dy = w[1] - sy / m; val dz = w[2] - sz / m
                        dx * dx + dy * dy + dz * dz
                    }
                    if (best < 0 || d < bestD) { best = e; bestD = d }
                }
                e = tape.next(e)
            }
            entries[j] = best
            if (best >= 0) {
                tape.world(best, w); sx += w[0]; sy += w[1]; sz += w[2]; m++
            }
            stats.ambiguous++
        }
    }

    /**
     * Lifts the points of an element ([first] until [first] + [count]) the tape left: on the plane of its taped points
     * (three or more, not in a line), as billboards on its [owner]'s nearest tape entry, on the ground plane when
     * the ground owns it ([ground]: owner 0 in a ground layer), or fixed to the screen when [screenFixed] says the
     * engine drew it in screen space.
     */
    fun liftRest(first: Int, count: Int, owner: Int, ground: Boolean, screenFixed: Boolean) {
        var taped = 0
        for (j in 0 until count) if (pts.lifted(first + j)) taped++
        if (taped == count) return
        if (taped == 0) {
            when {
                screenFixed -> { for (j in 0 until count) pts.screen(first + j); stats.screenElements++ }
                else -> {
                    for (j in 0 until count) {
                        val i = first + j
                        val h = hangOn(owner, pts.svgX(i), pts.svgY(i)) ?: nearestAny(pts.svgX(i), pts.svgY(i))
                        if (h >= 0) pts.billboard(i, h, cam) else pts.screen(i)
                    }
                    stats.hungElements++
                }
            }
            return
        }
        val plane = if (taped >= 3) planeOf(first, count) else null
        for (j in 0 until count) {
            val i = first + j
            if (pts.lifted(i)) continue
            val ax = (pts.svgX(i) - cx) / focal; val ay = (cy - pts.svgY(i)) / focal
            if (plane != null) {
                val den = plane[0] * ax + plane[1] * ay + plane[2]
                val t = if (abs(den) > 1e-12) plane[3] / den else -1.0
                if (t > MIN_DEPTH && t < MAX_DEPTH) {
                    pts.place(i, ax * t, ay * t, t, LiftClass.PLANE); continue
                }
            }
            if (ground && owner == 0) {
                val t = groundHit(first, count, ax, ay)
                if (t > MIN_DEPTH && t < MAX_DEPTH) {
                    pts.place(i, ax * t, ay * t, t, LiftClass.GROUND); continue
                }
            }
            val h = hangOn(owner, pts.svgX(i), pts.svgY(i)) ?: nearestTaped(first, count, pts.svgX(i), pts.svgY(i))
            pts.billboard(i, h, cam)
        }
    }

    /** Lifts a decal's corners ([first] until [first] + 4) onto the horizontal plane at [height] (region frame metres). */
    fun liftDecal(first: Int, count: Int, height: Double, owner: Int, anchorPoint: Int): Boolean {
        val a = cam.axes
        var ok = true
        for (j in 0 until count) {
            val i = first + j
            if (pts.lifted(i)) continue
            val ax = (pts.svgX(i) - cx) / focal; val ay = (cy - pts.svgY(i)) / focal
            val den = ax * a[1] + ay * a[4] + a[7]
            val t = if (abs(den) > 1e-12) (height - cam.eyeY) / den else -1.0
            if (t > MIN_DEPTH && t < MAX_DEPTH) {
                pts.place(i, ax * t, ay * t, t, if (owner == 0) LiftClass.GROUND else LiftClass.PLANE)
            } else {
                ok = false
                if (anchorPoint >= 0 && pts.lifted(anchorPoint) && pts.liftOf(anchorPoint) != LiftClass.SCREEN && pts.liftOf(anchorPoint) != LiftClass.BILLBOARD) {
                    pts.billboard(i, anchorPoint, cam)
                } else {
                    val h = nearestAny(pts.svgX(i), pts.svgY(i))
                    if (h >= 0) pts.billboard(i, h, cam) else pts.screen(i)
                }
            }
        }
        return ok
    }

    /** Region-frame height of a taped point, or NaN. */
    fun heightOf(i: Int): Double {
        if (pts.liftOf(i) != LiftClass.TAPED) return Double.NaN
        cam.world(pts.eyeX(i).toDouble(), pts.eyeY(i).toDouble(), pts.eyeZ(i).toDouble(), w)
        return w[1]
    }

    /**
     * Lifts a point made on the screen segment from point [a] to point [b] at screen parameter [s] (a dash's end): on
     * the 3D segment between them (perspective-correct) when both stand in space, else as the nearer end does.
     */
    fun liftBetween(i: Int, a: Int, b: Int, s: Double) {
        val la = pts.liftOf(a); val lb = pts.liftOf(b)
        val spatial = { l: Byte -> l == LiftClass.TAPED || l == LiftClass.PLANE || l == LiftClass.GROUND }
        if (spatial(la) && spatial(lb)) {
            val za = pts.eyeZ(a).toDouble(); val zb = pts.eyeZ(b).toDouble()
            val t = s * za / ((1 - s) * zb + s * za)
            pts.place(
                i, pts.eyeX(a) + (pts.eyeX(b) - pts.eyeX(a)) * t, pts.eyeY(a) + (pts.eyeY(b) - pts.eyeY(a)) * t,
                za + (zb - za) * t, if (la == lb && la == LiftClass.GROUND) LiftClass.GROUND else LiftClass.PLANE,
            )
            return
        }
        if (la == LiftClass.SCREEN && lb == LiftClass.SCREEN) { pts.screen(i); return }
        val near = if (s < 0.5) a else b
        val other = if (s < 0.5) b else a
        val h = hangOf(near) ?: hangOf(other)
        if (h != null) pts.billboard(i, h, cam) else pts.screen(i)
    }

    private fun hangOf(i: Int): Int? = when (pts.liftOf(i)) {
        LiftClass.TAPED, LiftClass.PLANE, LiftClass.GROUND -> i
        LiftClass.BILLBOARD -> nearestAny(pts.svgX(i), pts.svgY(i)).takeIf { it >= 0 }
        else -> null
    }

    /** The plane through an element's taped points, as (n, d) with n·p = d in the eye frame, or null when they lie in a line. */
    private fun planeOf(first: Int, count: Int): DoubleArray? {
        var p0 = -1
        for (j in 0 until count) if (pts.liftOf(first + j) == LiftClass.TAPED) { p0 = first + j; break }
        if (p0 < 0) return null
        val x0 = pts.eyeX(p0).toDouble(); val y0 = pts.eyeY(p0).toDouble(); val z0 = pts.eyeZ(p0).toDouble()
        var p1 = -1; var best = 0.0
        for (j in 0 until count) {
            val i = first + j
            if (pts.liftOf(i) != LiftClass.TAPED) continue
            val dx = pts.eyeX(i) - x0; val dy = pts.eyeY(i) - y0; val dz = pts.eyeZ(i) - z0
            val d = dx * dx + dy * dy + dz * dz
            if (d > best) { best = d; p1 = i }
        }
        if (p1 < 0 || best < 1e-8) return null
        val ux = pts.eyeX(p1) - x0; val uy = pts.eyeY(p1) - y0; val uz = pts.eyeZ(p1) - z0
        var nx = 0.0; var ny = 0.0; var nz = 0.0; var area = 0.0
        for (j in 0 until count) {
            val i = first + j
            if (pts.liftOf(i) != LiftClass.TAPED) continue
            val vx = pts.eyeX(i) - x0; val vy = pts.eyeY(i) - y0; val vz = pts.eyeZ(i) - z0
            val cx1 = uy * vz - uz * vy; val cy1 = uz * vx - ux * vz; val cz1 = ux * vy - uy * vx
            val a = cx1 * cx1 + cy1 * cy1 + cz1 * cz1
            if (a > area) { area = a; nx = cx1; ny = cy1; nz = cz1 }
        }
        // the triangle's area against the long side squared: a sliver is a line
        if (area <= 1e-10 * best * best) return null
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        return doubleArrayOf(nx, ny, nz, nx * x0 + ny * y0 + nz * z0)
    }

    /** The eye-frame depth at which the ray (ax, ay, 1) meets the horizontal plane at the element's taped height (else 0). */
    private fun groundHit(first: Int, count: Int, ax: Double, ay: Double): Double {
        var h = 0.0; var m = 0
        for (j in 0 until count) {
            val v = heightOf(first + j)
            if (!v.isNaN()) { h += v; m++ }
        }
        if (m > 0) h /= m
        val a = cam.axes
        val den = ax * a[1] + ay * a[4] + a[7]
        return if (abs(den) > 1e-12) (h - cam.eyeY) / den else -1.0
    }

    /** The element's own taped point nearest ([x], [y]) on the screen. */
    private fun nearestTaped(first: Int, count: Int, x: Double, y: Double): Int {
        var best = -1; var bd = Double.MAX_VALUE
        for (j in 0 until count) {
            val i = first + j
            if (pts.liftOf(i) != LiftClass.TAPED) continue
            val dx = pts.svgX(i) - x; val dy = pts.svgY(i) - y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /**
     * The point a billboard of [owner] at ([x], [y]) hangs on: the owner's tape entry nearest on the screen (a point the
     * engine projected for that owner, the one its screen offsets were most likely built from, printed or not; vertical
     * distance counts twice), as a taped point of the anchor; null when the owner has none.
     */
    private fun hangOn(owner: Int, x: Double, y: Double): Int? {
        if (owner <= 0) return null
        if (ownerStart == null) {
            // the tape's entries sorted by owner (a counting sort)
            val n = tape.size
            val owners = tape.ownerCount
            val start = IntArray(owners + 2)
            for (e in 0 until n) start[tape.owner(e) + 1]++
            for (o in 1..owners + 1) start[o] += start[o - 1]
            val fill = start.copyOf()
            val list = IntArray(n)
            for (e in 0 until n) list[fill[tape.owner(e)]++] = e
            ownerStart = start; ownerEntries = list
        }
        val start = ownerStart!!; val list = ownerEntries!!
        if (owner + 1 >= start.size) return null
        // nearest, with vertical distance counted twice: the engine's offsets are mostly across (a trunk's or a mast's
        // half width), so a point level with the vertex is the likelier base than one as near above or below it
        var best = -1; var bd = Double.MAX_VALUE
        for (k in start[owner] until start[owner + 1]) {
            val e = list[k]
            val dx = tape.screenX10(e) / 10.0 - x; val dy = tape.screenY10(e) / 10.0 - y
            val d = dx * dx + 4 * dy * dy
            if (d < bd) { bd = d; best = e }
        }
        return if (best < 0) null else entryPoint(best)
    }

    /**
     * The tape entry nearest ([x], [y]) on the screen, of any owner, as a taped point of the anchor (−1 when the tape is
     * empty there): what a billboard hangs on when nothing says whose it is. Searches a grid of the entries that print
     * within the frame and a frame's width and height around it.
     */
    fun nearestAny(x: Double, y: Double): Int {
        if (gridStart == null) buildGrid()
        val start = gridStart!!; val list = gridEntries!!
        val cx0 = Math.floorDiv(x.toLong(), CELL.toLong()).toInt() - gx0
        val cy0 = Math.floorDiv(y.toLong(), CELL.toLong()).toInt() - gy0
        var best = -1; var bd = Double.MAX_VALUE
        var r = 0
        val maxR = maxOf(gw, gh)
        while (r <= maxR) {
            for (gy in cy0 - r..cy0 + r) {
                if (gy < 0 || gy >= gh) continue
                for (gx in cx0 - r..cx0 + r) {
                    if (gx < 0 || gx >= gw) continue
                    if (r > 0 && gx != cx0 - r && gx != cx0 + r && gy != cy0 - r && gy != cy0 + r) continue
                    val c = gy * gw + gx
                    for (k in start[c] until start[c + 1]) {
                        val e = list[k]
                        val dx = tape.screenX10(e) / 10.0 - x; val dy = tape.screenY10(e) / 10.0 - y
                        val d = dx * dx + dy * dy
                        if (d < bd) { bd = d; best = e }
                    }
                }
            }
            // a hit within the rings searched so far is the nearest once the next ring lies farther
            if (best >= 0 && Math.sqrt(bd) <= r * CELL.toDouble()) break
            r++
        }
        return if (best < 0) -1 else entryPoint(best)
    }

    private var gridStart: IntArray? = null
    private var gridEntries: IntArray? = null
    private var gx0 = 0; private var gy0 = 0; private var gw = 1; private var gh = 1

    private fun buildGrid() {
        val w = cam.width; val h = cam.height
        gx0 = Math.floorDiv((-w).toLong(), CELL.toLong()).toInt(); gy0 = Math.floorDiv((-h).toLong(), CELL.toLong()).toInt()
        gw = Math.floorDiv((2 * w).toLong(), CELL.toLong()).toInt() - gx0 + 1; gh = Math.floorDiv((2 * h).toLong(), CELL.toLong()).toInt() - gy0 + 1
        val n = tape.size
        val cellOf = IntArray(n)
        val start = IntArray(gw * gh + 1)
        for (e in 0 until n) {
            val gx = Math.floorDiv(tape.screenX10(e), (CELL * 10).toLong()).toInt() - gx0
            val gy = Math.floorDiv(tape.screenY10(e), (CELL * 10).toLong()).toInt() - gy0
            cellOf[e] = if (gx in 0 until gw && gy in 0 until gh) gy * gw + gx else -1
            if (cellOf[e] >= 0) start[cellOf[e] + 1]++
        }
        for (c in 1..gw * gh) start[c] += start[c - 1]
        val fill = start.copyOf()
        val list = IntArray(start[gw * gh])
        for (e in 0 until n) if (cellOf[e] >= 0) list[fill[cellOf[e]]++] = e
        gridStart = start; gridEntries = list
    }

    /** Tape entry [e] as a taped point of the anchor: made once, then reused. */
    private fun entryPoint(e: Int): Int {
        val have = entryPoints.get(e.toLong())
        if (have >= 0) return have
        val sx = tape.screenX10(e) / 10.0; val sy = tape.screenY10(e) / 10.0
        val d = tape.depth(e)
        val i = pts.add(sx, sy)
        pts.place(i, (sx - cx) * d / focal, (cy - sy) * d / focal, d, LiftClass.TAPED, tape.owner(e))
        entryPoints.put(e.toLong(), i)
        stats.hangPoints++
        return i
    }

    companion object {
        private const val CELL = 16
        /** A lifted point nearer than this (metres) or farther is not trusted: it is hung or fixed instead. */
        const val MIN_DEPTH = 0.05
        const val MAX_DEPTH = 2.0e6
    }
}

/** What lifting found, for reports and gates. */
class LiftStats {
    /** Points looked up on the tape, and found. */
    var lookups = 0L
    var found = 0L

    /** Points whose key had alternatives. */
    var ambiguous = 0L

    /** Elements with no taped point: fixed to the screen, or hung as billboards on their owner's (or the nearest) tape entry. */
    var screenElements = 0
    var hungElements = 0

    /** Tape entries no element printed, added as points for billboards to hang on. */
    var hangPoints = 0
}

/** An open-addressing map from longs to ints (no boxing); [get] gives −1 for a missing key. */
internal class LongIntMap(expected: Int = 64) {
    private var keys = LongArray(cap(expected))
    private var vals = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    private var mask = keys.size - 1
    var size = 0
        private set

    private fun cap(n: Int): Int { var c = 16; while (c < n * 2) c = c shl 1; return c }

    private fun slot(k: Long): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h.toInt()) and mask
    }

    fun get(k: Long): Int {
        var s = slot(k)
        while (used[s]) {
            if (keys[s] == k) return vals[s]
            s = (s + 1) and mask
        }
        return -1
    }

    fun put(k: Long, v: Int) {
        if (size * 2 >= keys.size) grow()
        var s = slot(k)
        while (used[s]) {
            if (keys[s] == k) { vals[s] = v; return }
            s = (s + 1) and mask
        }
        used[s] = true; keys[s] = k; vals[s] = v; size++
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = IntArray(keys.size); used = BooleanArray(keys.size); mask = keys.size - 1; size = 0
        for (i in ok.indices) if (ou[i]) put(ok[i], ov[i])
    }
}

/** A growable list of ints. */
internal class IntList(capacity: Int = 8) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]

    fun clear() { size = 0 }

    fun toArray(): IntArray = a.copyOf(size)
}

/** The engine's camera of a render as the anchor stores it. */
internal fun anchorCamera(c: City.Camera): AnchorCamera = AnchorCamera(
    c.target[0], c.target[1], c.target[2], c.yaw, c.pitch, c.dist, c.fov, c.W, c.H, c.cx, c.cy, c.near,
    c.C[0], c.C[1], c.C[2],
    doubleArrayOf(c.r[0], c.r[1], c.r[2], c.u[0], c.u[1], c.u[2], c.f[0], c.f[1], c.f[2]),
    c.focal,
)

/** Clamps [v] to [lo] … [hi]. */
internal fun clamp(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))
