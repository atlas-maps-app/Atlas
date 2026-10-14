package app.atlas.engine

import app.atlas.geo.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws scenes for a camera, on the CPU: projects every visible vertex to the screen, clips triangles at the near
 * plane, culls what lies off screen or faces away, hazes colours toward the horizon and orders everything for the
 * painter: sky, ground (tile by tile in its own order), the shadow layer, then buildings and trees back to front.
 * Labels and points of interest are placed last, clear of each other.
 *
 * One renderer serves one view. Its buffers are reused (two sets, alternating), so a [Frame] stays valid while the
 * next one is computed, and no geometry is allocated per frame.
 */
class Renderer(private val measurer: TextMeasurer = ApproximateMeasurer) {
    private val buffers = arrayOf(BatchBuffers(), BatchBuffers())
    private var current = 0
    private val labels = LabelPlacer(measurer)

    // Per-frame state.
    private lateinit var out: BatchBuffers
    private lateinit var cam: Camera
    private val m = FloatArray(12)
    private val eye = FloatArray(3)
    private var near = 0f
    private var width = 0f
    private var height = 0f
    private var fogStart = 0f
    private var fogRange = 1f
    private var fogMax = 0f
    private var fogPow = 1f
    private var fogColour = 0
    private var calm = 0f
    private var calmTarget = 0
    private var hits: HitRecords = HitRecords()

    // Scratch for one face.
    private var sx = FloatArray(1024); private var sy = FloatArray(1024); private var sw = FloatArray(1024)
    private var px = FloatArray(1024); private var py = FloatArray(1024)
    private var remap = IntArray(1024)

    // Draw order of objects, cached while the camera holds still.
    private var order = LongArray(4096)
    private var orderCount = 0
    /** A copy of the tiles the order was made for (the caller's list may change in place between frames). */
    private val orderTiles = ArrayList<DrawTile>()
    private var orderValid = false
    private var orderEye = DoubleArray(3)
    private var orderBearing = 0.0
    private var orderPitch = 0.0
    /** The projection the order was culled for: a resized viewport or a moved principal point sees other objects. */
    private var orderWidth = -1
    private var orderHeight = -1
    private var orderFocal = 0.0
    private var orderPrincipalY = 0.0
    private var faceOrder = IntArray(64)
    private var faceKey = FloatArray(64)

    // Counters.
    private var objectsDrawn = 0
    private var facesDrawn = 0
    private var treesDrawn = 0
    private val colourCache = IntArray(4096)
    private val colourCacheIn = IntArray(4096)

    /** Draws [scenes] (all exactly as built) for [camera] at [timeMillis] (which drives label fades). */
    @JvmName("frameScenes")
    fun frame(camera: Camera, scenes: List<TileScene>, timeMillis: Long, style: FrameStyle = FrameStyle()): Frame =
        frame(camera, scenes.map { DrawTile(it) }, timeMillis, style)

    /** Draws a plan of tiles ([TileCover.plan]) for [camera] at [timeMillis]. */
    fun frame(camera: Camera, tiles: List<DrawTile>, timeMillis: Long, style: FrameStyle = FrameStyle()): Frame {
        val t0 = System.nanoTime()
        current = 1 - current
        out = buffers[current]
        out.reset()
        hits = out.hits
        hits.clear()
        cam = camera
        width = camera.width.toFloat()
        height = camera.height.toFloat()
        near = camera.near.toFloat()
        objectsDrawn = 0; facesDrawn = 0; treesDrawn = 0
        val look = tiles.firstOrNull()?.scene?.look ?: Look.DAY
        val theme = look.theme
        fogStart = (camera.distance * theme.fogStart).toFloat()
        fogRange = max(1f, camera.far.toFloat() - fogStart)
        fogMax = theme.fogMax
        fogPow = theme.fogPower
        fogColour = theme.fog
        prepareFog()
        calm = (style.calm ?: look.calmAt(camera.zoom)).coerceIn(0f, 1f)
        calmTarget = theme.earth
        groundView = camera.groundPolygon()
        java.util.Arrays.fill(colourCacheIn, 0)
        java.util.Arrays.fill(shadeStep, -1)

        sky(theme)
        for (t in tiles) ground(t)
        out.endBatch()
        val tg = System.nanoTime()
        shadows(tiles, theme)
        val ts = System.nanoTime()
        val reused = objects(tiles)
        out.endBatch()
        val t1 = System.nanoTime()

        val placed = labels.place(camera, tiles, timeMillis, style)
        hits.poiBoxes = placed.poiBoxes
        val t2 = System.nanoTime()
        val stats = FrameStats(
            vertices = out.totalVertices, triangles = out.totalIndices / 3, batches = out.batches.size, scenes = tiles.size,
            objectsDrawn = objectsDrawn, facesDrawn = facesDrawn, treesDrawn = treesDrawn,
            labels = placed.labels.size, pois = placed.pois.size, sortReused = reused,
            geometryNanos = t1 - t0, labelNanos = t2 - t1, totalNanos = t2 - t0,
            groundNanos = tg - t0, shadowNanos = ts - tg, objectNanos = t1 - ts,
        )
        return Frame(camera.width, camera.height, out.batches.toList(), placed.labels, placed.pois, stats, hits)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sky and haze.

    private fun sky(theme: Theme) {
        val hy = cam.horizonY.toFloat()
        val fy = cam.farY.toFloat()
        if (fy <= 0f && hy <= 0f) return
        out.reserve(8, 12)
        val top = theme.skyTop
        val hor = theme.skyHorizon
        if (hy > 0f) {
            // From the top of the screen down to the horizon (the gradient reaches sky_top 300 px above it).
            val topColour = Colours.mix(hor, top, min(1f, hy / (300f * cam.density.coerceAtLeast(1f))))
            quad(0f, 0f, width, min(hy, height), topColour, hor)
        }
        if (fy > max(hy, 0f)) {
            val farGround = Colours.mix(calmed(theme.earth), fogColour, fogMax)
            quad(0f, max(hy, 0f), width, min(fy + 2f, height), Colours.mix(hor, fogColour, 0.5f), farGround)
        }
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, cTop: Int, cBottom: Int) {
        out.reserve(4, 6)
        val a = out.vertex(x0, y0, cTop)
        val b = out.vertex(x1, y0, cTop)
        val c = out.vertex(x1, y1, cBottom)
        val d = out.vertex(x0, y1, cBottom)
        out.index(a); out.index(b); out.index(c)
        out.index(a); out.index(c); out.index(d)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ground.

    private fun ground(t: DrawTile) {
        val s = t.scene
        cam.tileMatrix(s.originX, s.originY, s.metresPerUnit, m)
        cam.eyeInTile(s.originX, s.originY, s.metresPerUnit, eye)
        val mesh = s.ground
        val bounds = visibleBounds(s)
        for (f in 0 until mesh.faceCount) {
            val b = 6 * f
            if (mesh.faceBounds[b + 3] < bounds[0] || mesh.faceBounds[b] > bounds[2] ||
                mesh.faceBounds[b + 4] < bounds[1] || mesh.faceBounds[b + 1] > bounds[3]
            ) continue
            var override = 0
            when (mesh.faceKind[f]) {
                FaceMesh.CULL -> if (!front(mesh, f)) continue
                FaceMesh.TWO_SIDED -> if (!front(mesh, f)) override = mesh.faceBack[f]
            }
            emitFace(mesh, f, override, -1f)
        }
    }

    private val tileBounds = FloatArray(4)
    private var groundView = DoubleArray(8)

    /** The scene-metre box of the ground in view (plus a margin for raised things), to skip faces outside it. */
    private fun visibleBounds(s: TileScene): FloatArray {
        val g = groundView
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = ((g[2 * i] - s.originX) * s.metresPerUnit).toFloat()
            val y = ((g[2 * i + 1] - s.originY) * s.metresPerUnit).toFloat()
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        val pad = 40f
        tileBounds[0] = x0 - pad; tileBounds[1] = y0 - pad; tileBounds[2] = x1 + pad; tileBounds[3] = y1 + pad
        return tileBounds
    }

    private fun front(mesh: FaceMesh, f: Int): Boolean {
        val k = 3 * f
        return mesh.faceNormal[k] * (eye[0] - mesh.faceCentre[k]) + mesh.faceNormal[k + 1] * (eye[1] - mesh.faceCentre[k + 1]) +
            mesh.faceNormal[k + 2] * (eye[2] - mesh.faceCentre[k + 2]) > 0f
    }

    /**
     * Projects face [f] of [mesh] and writes its visible triangles. [override] (when not 0) replaces every vertex colour;
     * [fogT] ≥ 0 hazes the whole face by one amount, else each vertex by its own depth.
     */
    private fun emitFace(mesh: FaceMesh, f: Int, override: Int, fogT: Float) {
        val v0 = mesh.faceVertexStart[f]
        val v1 = mesh.faceVertexStart[f + 1]
        val n = v1 - v0
        if (n <= 0) return
        if (sx.size < n) grow(n)
        val pos = mesh.positions
        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]; val m3 = m[3]
        val m4 = m[4]; val m5 = m[5]; val m6 = m[6]; val m7 = m[7]
        val m8 = m[8]; val m9 = m[9]; val m10 = m[10]; val m11 = m[11]
        var minW = Float.MAX_VALUE
        var allLeft = true; var allRight = true; var allUp = true; var allDown = true
        for (k in 0 until n) {
            val p = 3 * (v0 + k)
            val x = pos[p]; val y = pos[p + 1]; val z = pos[p + 2]
            val w = m8 * x + m9 * y + m10 * z + m11
            val X = m0 * x + m1 * y + m2 * z + m3
            val Y = m4 * x + m5 * y + m6 * z + m7
            sx[k] = X; sy[k] = Y; sw[k] = w
            if (w < minW) minW = w
            if (w >= near) {
                val qx = X / w; val qy = Y / w
                px[k] = qx; py[k] = qy
                if (qx >= 0f) allLeft = false
                if (qx <= width) allRight = false
                if (qy >= 0f) allUp = false
                if (qy <= height) allDown = false
            } else {
                allLeft = false; allRight = false; allUp = false; allDown = false
            }
        }
        if (allLeft || allRight || allUp || allDown) return
        if (minW < near) {
            // Anything wholly behind the near plane goes.
            var any = false
            for (k in 0 until n) if (sw[k] >= near) { any = true; break }
            if (!any) return
        }
        val i0 = mesh.faceIndexStart[f]
        val i1 = mesh.faceIndexStart[f + 1]
        if (minW >= near && n <= FAST_FACE && i1 - i0 <= FAST_FACE * 3) {
            fastFace(mesh, f, v0, n, i0, i1, override, fogT)
            return
        }
        java.util.Arrays.fill(remap, 0, n, -1)
        var batch = out.batchIndex
        val idx = mesh.indices
        val cols = mesh.colours
        val margin = 4f
        var drew = false
        var t = i0
        while (t < i1) {
            val a = idx[t] - v0; val b = idx[t + 1] - v0; val c = idx[t + 2] - v0
            t += 3
            val wa = sw[a]; val wb = sw[b]; val wc = sw[c]
            if (wa >= near && wb >= near && wc >= near) {
                val ax = px[a]; val bx = px[b]; val cx = px[c]
                if (ax < -margin && bx < -margin && cx < -margin) continue
                if (ax > width + margin && bx > width + margin && cx > width + margin) continue
                val ay = py[a]; val by = py[b]; val cy = py[c]
                if (ay < -margin && by < -margin && cy < -margin) continue
                if (ay > height + margin && by > height + margin && cy > height + margin) continue
                if (out.reserve(3, 3)) { java.util.Arrays.fill(remap, 0, n, -1); batch = out.batchIndex }
                out.index(vertexOf(a, v0, cols, override, fogT))
                out.index(vertexOf(b, v0, cols, override, fogT))
                out.index(vertexOf(c, v0, cols, override, fogT))
                drew = true
                continue
            }
            if (wa < near && wb < near && wc < near) continue
            if (out.reserve(4, 6)) { java.util.Arrays.fill(remap, 0, n, -1); batch = out.batchIndex }
            clipTriangle(a, b, c, v0, cols, override, fogT)
            drew = true
        }
        if (drew) facesDrawn++
        if (batch != out.batchIndex) java.util.Arrays.fill(remap, 0, n, -1)
    }

    /**
     * The common case: the face lies wholly in front of the near plane and fits the open batch, so vertices and indices
     * go straight into the batch arrays, each vertex once, only for triangles that touch the screen.
     */
    private fun fastFace(mesh: FaceMesh, f: Int, v0: Int, n: Int, i0: Int, i1: Int, override: Int, fogT: Float) {
        out.ensure(n, i1 - i0)
        val opos = out.pos
        val ocol = out.col
        val oidx = out.idx
        var nv = out.vertexCount
        var ni = out.indexCount
        val rm = remap
        java.util.Arrays.fill(rm, 0, n, -1)
        val idx = mesh.indices
        val cols = mesh.colours
        val w = width + 4f
        val h = height + 4f
        var lastIn = 0
        var lastOut = 0
        var t = i0
        while (t < i1) {
            val a = idx[t] - v0; val b = idx[t + 1] - v0; val c = idx[t + 2] - v0
            t += 3
            val ax = px[a]; val bx = px[b]; val cx = px[c]
            if (ax < -4f && bx < -4f && cx < -4f) continue
            if (ax > w && bx > w && cx > w) continue
            val ay = py[a]; val by = py[b]; val cy = py[c]
            if (ay < -4f && by < -4f && cy < -4f) continue
            if (ay > h && by > h && cy > h) continue
            for (q in 0 until 3) {
                val k = if (q == 0) a else if (q == 1) b else c
                var o = rm[k]
                if (o < 0) {
                    val base = if (override != 0) override else cols[v0 + k]
                    val colour = if (fogT >= 0f) {
                        if (base != lastIn || lastOut == 0) { lastIn = base; lastOut = shade(base, fogT) }
                        lastOut
                    } else shade(base, fog(sw[k]))
                    opos[2 * nv] = px[k]
                    opos[2 * nv + 1] = py[k]
                    ocol[nv] = colour
                    o = nv++
                    rm[k] = o
                }
                oidx[ni++] = o.toShort()
            }
        }
        if (ni > out.indexCount) facesDrawn++
        out.vertexCount = nv
        out.indexCount = ni
    }

    private fun vertexOf(k: Int, v0: Int, cols: IntArray, override: Int, fogT: Float): Int {
        var o = remap[k]
        if (o >= 0) return o
        val base = if (override != 0) override else cols[v0 + k]
        o = out.vertex(px[k], py[k], shade(base, if (fogT >= 0f) fogT else fog(sw[k])))
        remap[k] = o
        return o
    }

    // Clip scratch: up to 4 points in homogeneous screen space.
    private val cx = FloatArray(4); private val cy = FloatArray(4); private val cw = FloatArray(4); private val cc = IntArray(4)

    /** A triangle cut by the near plane: the part in front, as one or two triangles of new vertices. */
    private fun clipTriangle(a: Int, b: Int, c: Int, v0: Int, cols: IntArray, override: Int, fogT: Float) {
        var n = 0
        for (e in 0 until 3) {
            val i = if (e == 0) a else if (e == 1) b else c
            val j = if (e == 0) b else if (e == 1) c else a
            val wi = sw[i]; val wj = sw[j]
            val ci = if (override != 0) override else cols[v0 + i]
            val cj = if (override != 0) override else cols[v0 + j]
            if (wi >= near) { cx[n] = sx[i]; cy[n] = sy[i]; cw[n] = wi; cc[n] = ci; n++ }
            if ((wi >= near) != (wj >= near)) {
                val tt = (near - wi) / (wj - wi)
                cx[n] = sx[i] + (sx[j] - sx[i]) * tt
                cy[n] = sy[i] + (sy[j] - sy[i]) * tt
                cw[n] = near
                cc[n] = Colours.mix(ci, cj, tt)
                n++
            }
        }
        if (n < 3) return
        val first = out.vertex(cx[0] / cw[0], cy[0] / cw[0], shade(cc[0], if (fogT >= 0f) fogT else fog(cw[0])))
        var prev = out.vertex(cx[1] / cw[1], cy[1] / cw[1], shade(cc[1], if (fogT >= 0f) fogT else fog(cw[1])))
        for (k in 2 until n) {
            val v = out.vertex(cx[k] / cw[k], cy[k] / cw[k], shade(cc[k], if (fogT >= 0f) fogT else fog(cw[k])))
            out.index(first); out.index(prev); out.index(v)
            prev = v
        }
    }

    private fun grow(n: Int) {
        val s = max(n, sx.size * 2)
        sx = FloatArray(s); sy = FloatArray(s); sw = FloatArray(s); px = FloatArray(s); py = FloatArray(s); remap = IntArray(s)
    }

    /** Haze curve sampled in 256 steps, so no power is taken per vertex. */
    private val fogCurve = FloatArray(257)
    private var fogScale = 0f

    private fun prepareFog() {
        for (i in 0..256) fogCurve[i] = (i / 256f).pow(fogPow) * fogMax
        fogScale = 256f / fogRange
    }

    /** Haze by depth: none near, full [fogMax] at the far edge of the ground. */
    private fun fog(depth: Float): Float {
        if (depth <= fogStart) return 0f
        val i = ((depth - fogStart) * fogScale).toInt()
        return if (i >= 256) fogCurve[256] else fogCurve[i]
    }

    // Shaded colours by (colour, haze step): most vertices share a few colours.
    private val shadeIn = IntArray(8192)
    private val shadeStep = IntArray(8192)
    private val shadeOut = IntArray(8192)

    /** A colour calmed and hazed by [t] (in steps of 1/128), through a small cache. */
    private fun shade(c: Int, t: Float): Int {
        val step = if (t <= 0.004f) 0 else (t * 128f + 0.5f).toInt().coerceIn(1, 128)
        if (step == 0 && calm <= 0f) return c
        var h = c xor (step * -0x61c88647)
        h = (h xor (h ushr 15) xor (h ushr 7)) and 8191
        if (shadeIn[h] == c && shadeStep[h] == step) return shadeOut[h]
        val base = calmed(c)
        val r = if (step == 0) base else Colours.mix(base, fogColour, step / 128f)
        shadeIn[h] = c
        shadeStep[h] = step
        shadeOut[h] = r
        return r
    }

    private fun calmed(c: Int): Int {
        if (calm <= 0f) return c
        val h = (c xor (c ushr 12) xor (c ushr 20)) and 4095
        if (colourCacheIn[h] == c && c != 0) return colourCache[h]
        val r = Colours.mix(Colours.desaturate(c, calm * 0.55f), calmTarget, calm * 0.32f)
        colourCacheIn[h] = c
        colourCache[h] = r
        return r
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shadows.

    private fun shadows(tiles: List<DrawTile>, theme: Theme) {
        if (theme.shadowOpacity <= 0f) return
        out.beginLayer(1, theme.shadowOpacity)
        for (t in tiles) {
            val s = t.scene
            val mesh = s.shadows
            if (mesh.faceCount == 0) continue
            cam.tileMatrix(s.originX, s.originY, s.metresPerUnit, m)
            cam.eyeInTile(s.originX, s.originY, s.metresPerUnit, eye)
            val bounds = visibleBounds(s)
            for (f in 0 until mesh.faceCount) {
                val b = 6 * f
                if (mesh.faceBounds[b + 3] < bounds[0] || mesh.faceBounds[b] > bounds[2] ||
                    mesh.faceBounds[b + 4] < bounds[1] || mesh.faceBounds[b + 1] > bounds[3]
                ) continue
                if (t.only != null && !t.includes(s.unitX(mesh.faceCentre[3 * f]), s.unitY(mesh.faceCentre[3 * f + 1]))) continue
                emitFace(mesh, f, 0, 0f)
            }
        }
        out.endLayer()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Buildings and trees, back to front.

    /** Returns true when the previous frame's order was reused. */
    private fun objects(tiles: List<DrawTile>): Boolean {
        val reuse = orderValid && sameTiles(orderTiles, tiles)
        val moved = abs(cam.eyeX + cam.targetX * cam.metresPerUnit - orderEye[0]) + abs(cam.eyeY + cam.targetY * cam.metresPerUnit - orderEye[1]) +
            abs(cam.eyeZ - orderEye[2])
        val sameView = cam.width == orderWidth && cam.height == orderHeight && cam.focal == orderFocal && cam.principalY == orderPrincipalY
        val still = reuse && sameView && moved < cam.distance * 0.004 && abs(cam.bearing - orderBearing) < 0.3 && abs(cam.pitch - orderPitch) < 0.3
        if (!still) {
            sortObjects(tiles)
            orderTiles.clear()
            for (i in tiles.indices) orderTiles.add(tiles[i])
            orderValid = true
            orderEye[0] = cam.eyeX + cam.targetX * cam.metresPerUnit
            orderEye[1] = cam.eyeY + cam.targetY * cam.metresPerUnit
            orderEye[2] = cam.eyeZ
            orderBearing = cam.bearing
            orderPitch = cam.pitch
            orderWidth = cam.width
            orderHeight = cam.height
            orderFocal = cam.focal
            orderPrincipalY = cam.principalY
        }
        var lastTile = -1
        for (k in 0 until orderCount) {
            val key = order[k]
            val tileIdx = ((key ushr 24) and 0xFF).toInt()
            val item = (key and 0xFFFFFF).toInt()
            val t = tiles[tileIdx and 0x7F]
            val s = t.scene
            if ((tileIdx and 0x7F) != lastTile) {
                cam.tileMatrix(s.originX, s.originY, s.metresPerUnit, m)
                cam.eyeInTile(s.originX, s.originY, s.metresPerUnit, eye)
                lastTile = tileIdx and 0x7F
            }
            if (tileIdx and 0x80 != 0) tree(s, item) else building(s, item)
        }
        return still
    }

    private fun sameTiles(a: List<DrawTile>, b: List<DrawTile>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].scene !== b[i].scene || a[i].only != b[i].only) return false
        return true
    }

    /**
     * Visible objects of every tile, sorted far to near by depth of their centre. Keys pack depth (high bits), then
     * the tile (and a tree flag) and the item, so one primitive sort orders them.
     */
    private fun sortObjects(tiles: List<DrawTile>) {
        orderCount = 0
        val marginX = width * 0.15f
        val marginY = height * 0.15f
        for ((ti, t) in tiles.withIndex()) {
            if (ti > 127) break
            val s = t.scene
            cam.tileMatrix(s.originX, s.originY, s.metresPerUnit, m)
            val o = s.objects
            for (i in 0 until o.count) {
                val x = o.centre[3 * i]; val y = o.centre[3 * i + 1]; val z = o.centre[3 * i + 2]
                val d = visibleDepth(x, y, z, o.radius[i], marginX, marginY)
                if (d < 0f) continue
                if (t.only != null && !t.includes(s.unitX(x), s.unitY(y))) continue
                push(d, ti, i)
            }
            val tr = s.trees
            for (i in 0 until tr.count) {
                val x = tr.x[i]; val y = tr.y[i]; val z = tr.z[i] + tr.height[i] * 0.6f
                val d = visibleDepth(x, y, z, tr.radius[i] + tr.height[i] * 0.5f, marginX, marginY)
                if (d < 0f) continue
                if (t.only != null && !t.includes(s.unitX(x), s.unitY(y))) continue
                push(d, ti or 0x80, i)
            }
        }
        java.util.Arrays.sort(order, 0, orderCount)
    }

    private fun push(depth: Float, tile: Int, item: Int) {
        if (orderCount == order.size) order = order.copyOf(order.size * 2)
        // Far first: larger depth must sort first, so invert it.
        val d = ((1e6f - depth.coerceIn(0f, 999_999f)) * 64f).toLong()
        order[orderCount++] = (d shl 32) or ((tile.toLong() and 0xFF) shl 24) or (item.toLong() and 0xFFFFFF)
    }

    /** Depth of a sphere's centre when any of it is in view (with margins), else -1. */
    private fun visibleDepth(x: Float, y: Float, z: Float, r: Float, marginX: Float, marginY: Float): Float {
        val w = m[8] * x + m[9] * y + m[10] * z + m[11]
        if (w + r < near || w - r > cam.far * 1.05f) return -1f
        if (w <= r) return max(w, near)
        val X = (m[0] * x + m[1] * y + m[2] * z + m[3]) / w
        val Y = (m[4] * x + m[5] * y + m[6] * z + m[7]) / w
        val rp = r * cam.focal.toFloat() / w
        // Smaller than a pixel: not worth a draw.
        if (rp < MIN_OBJECT_PX) return -1f
        if (X + rp < -marginX || X - rp > width + marginX || Y + rp < -marginY || Y - rp > height + marginY) return -1f
        return w
    }

    private fun building(s: TileScene, o: Int) {
        val set = s.objects
        val mesh = set.mesh
        val f0 = set.faceStart[o]
        val f1 = set.faceStart[o + 1]
        val nf = f1 - f0
        if (nf <= 0) return
        if (faceOrder.size < nf) { faceOrder = IntArray(nf * 2); faceKey = FloatArray(nf * 2) }
        var n = 0
        for (f in f0 until f1) {
            if (mesh.faceKind[f] == FaceMesh.CULL && !front(mesh, f)) continue
            val k = 3 * f
            val dx = mesh.faceSort[k] - eye[0]; val dy = mesh.faceSort[k + 1] - eye[1]; val dz = mesh.faceSort[k + 2] - eye[2]
            // Group first (walls, roofs, things on roofs), then far to near.
            val key = mesh.faceGroup[f] * 1e9f - (dx * dx + dy * dy + dz * dz)
            // Insertion sort: a building shows only a handful of faces.
            var j = n
            while (j > 0 && faceKey[j - 1] > key) { faceKey[j] = faceKey[j - 1]; faceOrder[j] = faceOrder[j - 1]; j-- }
            faceKey[j] = key
            faceOrder[j] = f
            n++
        }
        if (n == 0) return
        val c = 3 * o
        val w = m[8] * set.centre[c] + m[9] * set.centre[c + 1] + m[10] * set.centre[c + 2] + m[11]
        val fogT = fog(w)
        var batch = out.batchIndex
        var start = out.indexCount
        for (i in 0 until n) {
            emitFace(mesh, faceOrder[i], 0, fogT)
            if (out.batchIndex != batch) {
                hits.add(s, o, batch, start, out.batchEnd(batch))
                batch = out.batchIndex
                start = 0
            }
        }
        hits.add(s, o, batch, start, out.indexCount)
        objectsDrawn++
    }

    private var sunDx = 0f
    private var sunDy = -1f

    /** A tree: trunk, then a dark crown, a sunlit one shifted toward the sun, and a highlight. */
    private fun tree(s: TileScene, i: Int) {
        val tr = s.trees
        val x = tr.x[i]; val y = tr.y[i]; val h = tr.height[i]
        val cz = tr.z[i] + h * 0.66f
        val w = m[8] * x + m[9] * y + m[10] * cz + m[11]
        if (w < near) return
        val qx = (m[0] * x + m[1] * y + m[2] * cz + m[3]) / w
        val qy = (m[4] * x + m[5] * y + m[6] * cz + m[7]) / w
        val rp = tr.radius[i] * cam.focal.toFloat() / w
        if (rp < 0.5f) return
        val fogT = fog(w)
        if (treesDrawn == 0) sunDirection(s)
        if (rp > 2.5f) {
            val wb = m[8] * x + m[9] * y + m[10] * tr.z[i] + m[11]
            if (wb >= near) {
                val bx = (m[0] * x + m[1] * y + m[2] * tr.z[i] + m[3]) / wb
                val by = (m[4] * x + m[5] * y + m[6] * tr.z[i] + m[7]) / wb
                val hw = max(0.5f, 0.22f * cam.focal.toFloat() / w)
                out.reserve(4, 6)
                val col = shade(tr.trunk, fogT)
                val a = out.vertex(bx - hw, by, col)
                val b = out.vertex(bx + hw, by, col)
                val c = out.vertex(qx + hw * 0.7f, qy, col)
                val d = out.vertex(qx - hw * 0.7f, qy, col)
                out.index(a); out.index(b); out.index(c); out.index(a); out.index(c); out.index(d)
            }
        }
        val seg = (rp / 1.6f).toInt().coerceIn(6, 20)
        disc(qx, qy, rp, seg, shade(tr.dark[i], fogT))
        if (rp > 1.2f) disc(qx + sunDx * rp * 0.28f, qy + sunDy * rp * 0.28f, rp * 0.76f, seg, shade(tr.mid[i], fogT))
        if (rp > 3f) disc(qx + sunDx * rp * 0.5f, qy + sunDy * rp * 0.5f, rp * 0.36f, max(6, seg / 2), shade(tr.light[i], fogT))
        treesDrawn++
    }

    private fun disc(x: Float, y: Float, r: Float, seg: Int, colour: Int) {
        out.ensure(seg + 1, seg * 3)
        val cosT = CIRCLE_COS[seg]
        val sinT = CIRCLE_SIN[seg]
        val opos = out.pos
        val ocol = out.col
        val oidx = out.idx
        var nv = out.vertexCount
        var ni = out.indexCount
        val c = nv
        opos[2 * nv] = x; opos[2 * nv + 1] = y; ocol[nv] = colour; nv++
        val first = nv
        for (k in 0 until seg) {
            opos[2 * nv] = x + r * cosT[k]; opos[2 * nv + 1] = y + r * sinT[k]; ocol[nv] = colour
            nv++
        }
        for (k in 0 until seg) {
            oidx[ni++] = c.toShort()
            oidx[ni++] = (first + k).toShort()
            oidx[ni++] = (first + (k + 1) % seg).toShort()
        }
        out.vertexCount = nv
        out.indexCount = ni
    }

    /** Which way the sun lies on screen: lit sides of crowns face it. */
    private fun sunDirection(s: TileScene) {
        val l = s.look.light
        val ex = m[0] * l.x + m[1] * l.y + m[2] * l.z
        val ey = m[4] * l.x + m[5] * l.y + m[6] * l.z
        val ew = m[8] * l.x + m[9] * l.y + m[10] * l.z
        // Screen direction of the light vector at the target: d(X/W) for a small step.
        val w0 = cam.distance.toFloat()
        val dx = ex - ew * cam.principalX.toFloat()
        val dy = ey - ew * cam.principalY.toFloat()
        val len = sqrt(dx * dx + dy * dy)
        if (len > 1e-6f && w0 > 0f) { sunDx = dx / len; sunDy = dy / len } else { sunDx = 0f; sunDy = -1f }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hit tests.

    /** The top-most symbol or building under screen point ([x], [y]) in [frame], or null. */
    fun hitTest(frame: Frame, x: Float, y: Float): Hit? {
        val h = frame.hits
        val boxes = h.poiBoxes
        for (i in frame.pois.indices.reversed()) {
            val b = 4 * i
            if (b + 3 >= boxes.size) continue
            if (x >= boxes[b] && x <= boxes[b + 2] && y >= boxes[b + 1] && y <= boxes[b + 3]) {
                val p = frame.pois[i]
                return Hit.Poi(p.poi, p.location, p.tile)
            }
        }
        for (r in h.count - 1 downTo 0) {
            val batch = frame.batches.getOrNull(h.batch[r]) as? Batch.Triangles ?: continue
            val pos = batch.positions
            val idx = batch.indices
            var k = h.indexStart[r]
            val end = min(h.indexEnd[r], batch.indexCount)
            while (k + 2 < end) {
                val a = (idx[k].toInt() and 0xFFFF) * 2
                val b = (idx[k + 1].toInt() and 0xFFFF) * 2
                val c = (idx[k + 2].toInt() and 0xFFFF) * 2
                if (inTriangle(x, y, pos[a], pos[a + 1], pos[b], pos[b + 1], pos[c], pos[c + 1])) {
                    val s = h.scene[r]!!
                    val o = h.objectIndex[r]
                    val info = s.buildings[s.objects.ref[o]]
                    val fp = info.footprint
                    val ring = List(fp.size / 2) { s.latLon(fp[2 * it], fp[2 * it + 1]) }
                    var cxs = 0f; var cys = 0f
                    for (i in 0 until fp.size / 2) { cxs += fp[2 * i]; cys += fp[2 * i + 1] }
                    val n = max(1, fp.size / 2)
                    return Hit.Building(info, s.id, s.latLon(cxs / n, cys / n), ring)
                }
                k += 3
            }
        }
        return null
    }

    private fun inTriangle(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        // Without area a triangle covers nothing (the sign test alone would take one shrunk to a point for everything).
        if ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay) == 0f) return false
        val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
        val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
        val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    companion object {
        /** Faces up to this many vertices take the direct path. */
        private const val FAST_FACE = 8192

        /** Objects whose bounding sphere spans less than this many pixels are left out. */
        private const val MIN_OBJECT_PX = 0.6f

        /** Unit circles of 0..24 segments, so discs need no trigonometry per frame. */
        private val CIRCLE_COS = Array(25) { n -> FloatArray(n) { k -> cos(2 * PI * k / n).toFloat() } }
        private val CIRCLE_SIN = Array(25) { n -> FloatArray(n) { k -> sin(2 * PI * k / n).toFloat() } }

        /** The point on Earth under the screen point, for a [Hit] on bare ground. */
        fun groundAt(camera: Camera, x: Float, y: Float): LatLon? = camera.unproject(x.toDouble(), y.toDouble())
    }
}

/**
 * The renderer's output buffers: batches of at most 65,535 vertices, reused from frame to frame. A batch closes when it
 * fills, when a layer starts or ends, or at the end of the ground and object passes.
 */
internal class BatchBuffers {
    private class Store {
        val positions = FloatArray(MAX_VERTICES * 2)
        val colours = IntArray(MAX_VERTICES)
        var indices = ShortArray(MAX_VERTICES * 3)
    }

    private val stores = ArrayList<Store>()
    val batches = ArrayList<Batch>()
    val hits = HitRecords()

    private var store: Store? = null
    private var used = 0

    /** The open batch's arrays (valid after [reserve] or [ensure]). */
    var pos: FloatArray = EMPTY_F
        private set
    var col: IntArray = EMPTY_I
        private set
    var idx: ShortArray = EMPTY_S
        private set
    var vertexCount = 0
    var indexCount = 0
    private var layer = 0
    private var layerAlpha = 1f
    var totalVertices = 0
        private set
    var totalIndices = 0
        private set

    /** Index of the batch being written (it becomes batches[batchIndex] when closed). */
    val batchIndex: Int get() = batches.size

    fun reset() {
        batches.clear()
        used = 0
        store = null
        vertexCount = 0
        indexCount = 0
        layer = 0
        layerAlpha = 1f
        totalVertices = 0
        totalIndices = 0
    }

    /** Makes room for [v] vertices and [i] indices; returns true when that opened a new batch. */
    fun reserve(v: Int, i: Int): Boolean {
        val s = store
        if (s != null && vertexCount + v <= MAX_VERTICES && indexCount + i <= idx.size) return false
        endBatch()
        open()
        if (indexCount + i > idx.size) growIndices(i)
        return true
    }

    /** Like [reserve], also growing the index array of the open batch when only it is short. */
    fun ensure(v: Int, i: Int) {
        val s = store
        if (s != null && vertexCount + v <= MAX_VERTICES) {
            if (indexCount + i > idx.size) growIndices(indexCount + i)
            return
        }
        endBatch()
        open()
        if (i > idx.size) growIndices(i)
    }

    private fun growIndices(need: Int) {
        val s = store!!
        s.indices = s.indices.copyOf(maxOf(need, s.indices.size * 2))
        idx = s.indices
    }

    private fun open() {
        if (used == stores.size) stores += Store()
        val s = stores[used++]
        store = s
        pos = s.positions
        col = s.colours
        idx = s.indices
        vertexCount = 0
        indexCount = 0
    }

    fun vertex(x: Float, y: Float, colour: Int): Int {
        pos[2 * vertexCount] = x
        pos[2 * vertexCount + 1] = y
        col[vertexCount] = colour
        return vertexCount++
    }

    fun index(i: Int) {
        if (indexCount == idx.size) growIndices(indexCount + 1)
        idx[indexCount++] = i.toShort()
    }

    /** The index count of batch [b] (closed) or of the open one. */
    fun batchEnd(b: Int): Int = if (b < batches.size) (batches[b] as Batch.Triangles).indexCount else indexCount

    fun endBatch() {
        val s = store ?: return
        if (indexCount > 0) {
            batches += Batch.Triangles(s.positions, s.colours, s.indices, vertexCount, indexCount, layerAlpha, layer)
            totalVertices += vertexCount
            totalIndices += indexCount
        } else {
            used--
        }
        store = null
        pos = EMPTY_F
        col = EMPTY_I
        idx = EMPTY_S
        vertexCount = 0
        indexCount = 0
    }

    fun beginLayer(id: Int, alpha: Float) {
        endBatch()
        layer = id
        layerAlpha = alpha
    }

    fun endLayer() {
        endBatch()
        layer = 0
        layerAlpha = 1f
    }

    companion object {
        const val MAX_VERTICES = 65535
        private val EMPTY_F = FloatArray(0)
        private val EMPTY_I = IntArray(0)
        private val EMPTY_S = ShortArray(0)
    }
}
