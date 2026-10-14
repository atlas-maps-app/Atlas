package app.atlas.engine

/**
 * Pre-triangulated geometry in a scene's tile-local metres (x east, y south, z up) with one colour per vertex,
 * grouped into faces in draw order. Each face owns a contiguous run of vertices and indices (indices are absolute),
 * a normal and centre for back-face tests and sorting, and its bounds for culling.
 */
class FaceMesh(
    val positions: FloatArray,
    val colours: IntArray,
    val indices: IntArray,
    val faceCount: Int,
    /** First index of each face; `faceCount + 1` entries. */
    val faceIndexStart: IntArray,
    /** First vertex of each face; `faceCount + 1` entries. */
    val faceVertexStart: IntArray,
    val faceKind: ByteArray,
    /** Face normals, x, y, z. */
    val faceNormal: FloatArray,
    /** Face centres, x, y, z: the point the back-face test uses. */
    val faceCentre: FloatArray,
    /** Where each face sorts among its object's faces, x, y, z (details sort just in front of their wall). */
    val faceSort: FloatArray,
    /** The colour a [TWO_SIDED] face takes when seen from behind. */
    val faceBack: IntArray,
    /** Draw group inside an object: walls first, then roofs, then what stands on roofs. */
    val faceGroup: ByteArray,
    /** Bounds per face: minX, minY, minZ, maxX, maxY, maxZ. */
    val faceBounds: FloatArray,
) {
    val vertexCount: Int get() = faceVertexStart[faceCount]
    val indexCount: Int get() = faceIndexStart[faceCount]
    val triangleCount: Int get() = indexCount / 3

    /** Roughly how much memory the mesh holds, in bytes: for a scene cache's budget. */
    val bytes: Long
        get() = 4L * (positions.size + colours.size + indices.size + faceIndexStart.size + faceVertexStart.size + faceNormal.size +
            faceCentre.size + faceSort.size + faceBack.size + faceBounds.size) + faceKind.size + faceGroup.size

    fun x(v: Int) = positions[3 * v]
    fun y(v: Int) = positions[3 * v + 1]
    fun z(v: Int) = positions[3 * v + 2]

    companion object {
        /** Drawn whatever its facing. */
        const val ALWAYS: Byte = 0

        /** Drawn only from the side its normal points to. */
        const val CULL: Byte = 1

        /** Drawn from both sides, in [faceBack] from behind (a quay: brick wall on the far bank, coping on the near one). */
        const val TWO_SIDED: Byte = 2

        const val GROUP_WALL: Byte = 0
        const val GROUP_ROOF: Byte = 1
        const val GROUP_ON_ROOF: Byte = 2

        val EMPTY = MeshBuilder().build()
    }
}

/**
 * Builds a [FaceMesh] face by face. Between [begin] and [end], add vertices with [v] (indices local to the face) and
 * triangles with [tri]. With [clip] set, triangles are cut to that rectangle when the face ends, so neighbouring tiles
 * meet exactly without overlap. Small always-drawn faces are merged, up to [MERGE_LIMIT] vertices.
 */
class MeshBuilder(capacity: Int = 4096) {
    private var pos = FloatArray(capacity * 3)
    private var col = IntArray(capacity)
    private var idx = IntArray(capacity * 2)
    private var nv = 0
    private var ni = 0

    private var faces = 0
    private var fIdx = IntArray(65)
    private var fVert = IntArray(65)
    private var fKind = ByteArray(64)
    private var fNormal = FloatArray(64 * 3)
    private var fCentre = FloatArray(64 * 3)
    private var fSort = FloatArray(64 * 3)
    private var fBack = IntArray(64)
    private var fGroup = ByteArray(64)
    private var fBounds = FloatArray(64 * 6)

    // The open face, collected before clipping.
    private var tx = FloatArray(256)
    private var ty = FloatArray(256)
    private var tz = FloatArray(256)
    private var tc = IntArray(256)
    private var tn = 0
    private var ti = IntArray(512)
    private var tni = 0
    private var open = false
    private var kind: Byte = 0
    private var nx = 0f; private var ny = 0f; private var nz = 1f
    private var cx = 0f; private var cy = 0f; private var cz = 0f
    private var sx = 0f; private var sy = 0f; private var sz = 0f
    private var back = 0
    private var group: Byte = 0

    /** Clip rectangle (x0, y0, x1, y1) in metres, or null. */
    var clip: FloatArray? = null

    val vertexCount: Int get() = nv
    val faceCount: Int get() = faces

    /** Vertices added to the open face so far. */
    internal val openVertexCount: Int get() = if (open) tn else 0

    /** Starts a face. [sortX]..[sortZ] default to the centre. */
    fun begin(
        kind: Byte = FaceMesh.ALWAYS,
        nx: Float = 0f, ny: Float = 0f, nz: Float = 1f,
        cx: Float = 0f, cy: Float = 0f, cz: Float = 0f,
        back: Int = 0,
        group: Byte = FaceMesh.GROUP_WALL,
        sortX: Float = cx, sortY: Float = cy, sortZ: Float = cz,
    ) {
        check(!open) { "face already open" }
        open = true
        this.kind = kind
        this.nx = nx; this.ny = ny; this.nz = nz
        this.cx = cx; this.cy = cy; this.cz = cz
        this.sx = sortX; this.sy = sortY; this.sz = sortZ
        this.back = back
        this.group = group
        tn = 0
        tni = 0
    }

    /** Adds a vertex to the open face; returns its index within the face. */
    fun v(x: Float, y: Float, z: Float, colour: Int): Int {
        if (tn == tx.size) {
            val n = tx.size * 2
            tx = tx.copyOf(n); ty = ty.copyOf(n); tz = tz.copyOf(n); tc = tc.copyOf(n)
        }
        tx[tn] = x; ty[tn] = y; tz[tn] = z; tc[tn] = colour
        return tn++
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (tni + 3 > ti.size) ti = ti.copyOf(ti.size * 2)
        ti[tni++] = a; ti[tni++] = b; ti[tni++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    /** Adds triangles over flat (x, y) [coords] with [tris] indices from Earcut, all at [z] in [colour]. */
    fun polygon(coords: DoubleArray, tris: IntArray, z: Float, colour: Int) {
        val base = tn
        for (i in 0 until coords.size / 2) v(coords[2 * i].toFloat(), coords[2 * i + 1].toFloat(), z, colour)
        var k = 0
        while (k < tris.size) {
            tri(base + tris[k], base + tris[k + 1], base + tris[k + 2])
            k += 3
        }
    }

    /** Ends the open face, clipping it when [clip] is set. Faces left without triangles vanish. */
    fun end() {
        check(open) { "no open face" }
        open = false
        if (tni == 0) return
        val r = clip
        if (r == null || allInside(r)) emitWhole() else emitClipped(r)
    }

    private fun allInside(r: FloatArray): Boolean {
        for (i in 0 until tn) if (tx[i] < r[0] || tx[i] > r[2] || ty[i] < r[1] || ty[i] > r[3]) return false
        return true
    }

    private fun emitWhole() {
        startFace(tn, tni)
        ensure(tn, tni)
        val start = nv
        for (i in 0 until tn) {
            pos[3 * nv] = tx[i]; pos[3 * nv + 1] = ty[i]; pos[3 * nv + 2] = tz[i]
            col[nv] = tc[i]
            nv++
        }
        for (k in 0 until tni) idx[ni++] = start + ti[k]
        finishFace()
    }

    // Scratch for clipping one triangle: up to 9 vertices of x, y, z, colour.
    private val px = FloatArray(10); private val py = FloatArray(10); private val pz = FloatArray(10); private val pc = IntArray(10)
    private val qx = FloatArray(10); private val qy = FloatArray(10); private val qz = FloatArray(10); private val qc = IntArray(10)
    private var remap = IntArray(256)

    private fun emitClipped(r: FloatArray) {
        startFace(tn, tni)
        val indicesBefore = ni
        if (remap.size < tn) remap = IntArray(tn * 2)
        java.util.Arrays.fill(remap, 0, tn, -1)
        var k = 0
        while (k < tni) {
            val a = ti[k]; val b = ti[k + 1]; val c = ti[k + 2]
            k += 3
            val oa = outcode(tx[a], ty[a], r); val ob = outcode(tx[b], ty[b], r); val oc = outcode(tx[c], ty[c], r)
            if (oa or ob or oc == 0) {
                ensure(3, 3)
                idx[ni++] = keep(a); idx[ni++] = keep(b); idx[ni++] = keep(c)
                continue
            }
            if (oa and ob and oc != 0) continue
            var n = 3
            px[0] = tx[a]; py[0] = ty[a]; pz[0] = tz[a]; pc[0] = tc[a]
            px[1] = tx[b]; py[1] = ty[b]; pz[1] = tz[b]; pc[1] = tc[b]
            px[2] = tx[c]; py[2] = ty[c]; pz[2] = tz[c]; pc[2] = tc[c]
            for (edge in 0 until 4) {
                n = clipEdge(n, edge, r)
                if (n < 3) break
            }
            if (n < 3) continue
            ensure(n, (n - 2) * 3)
            val first = nv
            for (i in 0 until n) {
                pos[3 * nv] = px[i]; pos[3 * nv + 1] = py[i]; pos[3 * nv + 2] = pz[i]
                col[nv] = pc[i]
                nv++
            }
            for (i in 1 until n - 1) {
                idx[ni++] = first; idx[ni++] = first + i; idx[ni++] = first + i + 1
            }
        }
        if (ni == indicesBefore) {
            // Everything was cut away: leave the face list as it was.
            if (merging) faces++
            merging = false
            return
        }
        finishFace()
    }

    private fun keep(i: Int): Int {
        var o = remap[i]
        if (o >= 0) return o
        o = nv
        pos[3 * nv] = tx[i]; pos[3 * nv + 1] = ty[i]; pos[3 * nv + 2] = tz[i]
        col[nv] = tc[i]
        nv++
        remap[i] = o
        return o
    }

    private fun outcode(x: Float, y: Float, r: FloatArray): Int {
        var c = 0
        if (x < r[0]) c = c or 1
        if (x > r[2]) c = c or 2
        if (y < r[1]) c = c or 4
        if (y > r[3]) c = c or 8
        return c
    }

    /** Clips the polygon in p* against one rectangle edge into q*, then swaps back into p*. Returns the new count. */
    private fun clipEdge(n: Int, edge: Int, r: FloatArray): Int {
        var m = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val di = dist(px[i], py[i], edge, r)
            val dj = dist(px[j], py[j], edge, r)
            if (di >= 0) { qx[m] = px[i]; qy[m] = py[i]; qz[m] = pz[i]; qc[m] = pc[i]; m++ }
            if ((di >= 0) != (dj >= 0)) {
                val t = di / (di - dj)
                qx[m] = px[i] + (px[j] - px[i]) * t
                qy[m] = py[i] + (py[j] - py[i]) * t
                qz[m] = pz[i] + (pz[j] - pz[i]) * t
                qc[m] = Colours.mix(pc[i], pc[j], t)
                // Snap onto the edge exactly, so tiles meet without hairline gaps.
                when (edge) {
                    0 -> qx[m] = r[0]
                    1 -> qx[m] = r[2]
                    2 -> qy[m] = r[1]
                    else -> qy[m] = r[3]
                }
                m++
            }
        }
        for (i in 0 until m) { px[i] = qx[i]; py[i] = qy[i]; pz[i] = qz[i]; pc[i] = qc[i] }
        return m
    }

    /** Signed distance inside a rectangle edge: 0 left, 1 right, 2 top, 3 bottom. */
    private fun dist(x: Float, y: Float, edge: Int, r: FloatArray): Float = when (edge) {
        0 -> x - r[0]
        1 -> r[2] - x
        2 -> y - r[1]
        else -> r[3] - y
    }

    /** Opens the face record (or reopens the previous always-drawn one to merge into); returns its first vertex. */
    private fun startFace(nVerts: Int, nIdx: Int): Int {
        ensure(nVerts * 2 + 8, nIdx * 2 + 8)
        if (kind == FaceMesh.ALWAYS && faces > 0 && fKind[faces - 1] == FaceMesh.ALWAYS &&
            fVert[faces] - fVert[faces - 1] + nVerts * 2 <= MERGE_LIMIT
        ) {
            faces--
            merging = true
            return fVert[faces]
        }
        merging = false
        if (faces + 2 > fIdx.size) growFaces()
        fIdx[faces] = ni
        fVert[faces] = nv
        return nv
    }

    private var merging = false

    private fun finishFace() {
        val f = faces
        fKind[f] = kind
        if (!merging) {
            fNormal[3 * f] = nx; fNormal[3 * f + 1] = ny; fNormal[3 * f + 2] = nz
            fCentre[3 * f] = cx; fCentre[3 * f + 1] = cy; fCentre[3 * f + 2] = cz
            fSort[3 * f] = sx; fSort[3 * f + 1] = sy; fSort[3 * f + 2] = sz
            fBack[f] = back
            fGroup[f] = group
        }
        // Bounds over the face's vertices (all of them, when merged).
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (v in fVert[f] until nv) {
            val x = pos[3 * v]; val y = pos[3 * v + 1]; val z = pos[3 * v + 2]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            if (z < z0) z0 = z
            if (z > z1) z1 = z
        }
        fBounds[6 * f] = x0; fBounds[6 * f + 1] = y0; fBounds[6 * f + 2] = z0
        fBounds[6 * f + 3] = x1; fBounds[6 * f + 4] = y1; fBounds[6 * f + 5] = z1
        faces++
        fIdx[faces] = ni
        fVert[faces] = nv
        merging = false
    }

    private fun growFaces() {
        val n = fKind.size * 2
        fIdx = fIdx.copyOf(n + 1); fVert = fVert.copyOf(n + 1); fKind = fKind.copyOf(n)
        fNormal = fNormal.copyOf(n * 3); fCentre = fCentre.copyOf(n * 3); fSort = fSort.copyOf(n * 3)
        fBack = fBack.copyOf(n); fGroup = fGroup.copyOf(n); fBounds = fBounds.copyOf(n * 6)
    }

    private fun ensure(verts: Int, indices: Int) {
        if (nv + verts > col.size) {
            val n = maxOf(col.size * 2, nv + verts)
            pos = pos.copyOf(n * 3); col = col.copyOf(n)
        }
        if (ni + indices > idx.size) idx = idx.copyOf(maxOf(idx.size * 2, ni + indices))
    }

    /** The mesh so far, trimmed. The builder can keep adding afterwards. */
    fun build(): FaceMesh {
        check(!open) { "a face is still open" }
        return FaceMesh(
            positions = pos.copyOf(nv * 3), colours = col.copyOf(nv), indices = idx.copyOf(ni), faceCount = faces,
            faceIndexStart = fIdx.copyOf(faces + 1).also { it[faces] = ni },
            faceVertexStart = fVert.copyOf(faces + 1).also { it[faces] = nv },
            faceKind = fKind.copyOf(faces), faceNormal = fNormal.copyOf(faces * 3), faceCentre = fCentre.copyOf(faces * 3),
            faceSort = fSort.copyOf(faces * 3), faceBack = fBack.copyOf(faces), faceGroup = fGroup.copyOf(faces),
            faceBounds = fBounds.copyOf(faces * 6),
        )
    }

    companion object {
        /** Always-drawn faces merge up to this many vertices (far below a 16-bit draw batch). */
        const val MERGE_LIMIT = 4096
    }
}
