package app.atlas.engine

import app.atlas.geo.Earcut
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns [Building]s into faces: walls (in sun and shade cells close up), roofs by shape (flat, gabled, hipped,
 * pyramidal, mansard, skillion) and, from zoom 17, the kit: cornices, plinths, chimneys, lit ridges, then tile courses,
 * hoist beams and windows closer still. Lit windows at night from zoom 16.
 */
class BuildingMesher(
    private val theme: Theme,
    private val shading: Shading,
    private val caster: ShadowCaster,
    /** The scene's zoom: it decides how much detail is worth drawing. */
    private val zoom: Int,
) {
    private val detail = zoom >= 17
    private val fine = zoom >= 18
    private val night = theme.night
    private val windowsOn = if (night) zoom >= 16 && theme.litWindowShare > 0f else fine
    private val skyGlass = Colours.mix(theme.windowGlass, theme.skyHorizon, 0.22f)

    /** Footprint triangles of the last building meshed (for its shadow), as indices into [lastFlat]. */
    var lastTriangles: IntArray = IntArray(0)
        private set
    var lastFlat: DoubleArray = DoubleArray(0)
        private set
    var lastHoles: IntArray? = null
        private set

    /** Emits building [index]'s faces into [mb]. */
    fun mesh(b: Building, index: Int, mb: MeshBuilder) {
        triangulate(b)
        val z0 = b.minHeight.toFloat()
        val h = b.height.toFloat()
        val wt = b.wallTop.toFloat()
        val skillion = b.roof == RoofShape.SKILLION
        // Skillion walls follow the slope: highest away from the downhill side.
        var sMin = 0.0
        var span = 1.0
        if (skillion) {
            val e = Rings.extent(b.outer, b.centroidX, b.centroidY, b.axisX, b.axisY)
            sMin = e[0]; span = max(0.1, e[1] - e[0])
        }
        for (k in b.rings.indices) {
            val r = b.rings[k]
            val n = r.size / 2
            val cut = b.cut?.getOrNull(k)
            for (i in 0 until n) {
                if (cut != null && cut[i]) continue
                val j = (i + 1) % n
                val ax = r[2 * i]; val ay = r[2 * i + 1]
                val bx = r[2 * j]; val by = r[2 * j + 1]
                val topA: Float
                val topB: Float
                if (skillion) {
                    topA = (b.height - b.roofHeight * ((ax - b.centroidX) * b.axisX + (ay - b.centroidY) * b.axisY - sMin) / span).toFloat()
                    topB = (b.height - b.roofHeight * ((bx - b.centroidX) * b.axisX + (by - b.centroidY) * b.axisY - sMin) / span).toFloat()
                } else {
                    topA = wt; topB = wt
                }
                wall(b, index, mb, ax, ay, bx, by, z0, topA, topB)
            }
        }
        when (b.roof) {
            RoofShape.FLAT -> flat(b, index, mb, h)
            RoofShape.GABLED, RoofShape.HIPPED -> ridged(b, index, mb, z0, wt, h)
            RoofShape.PYRAMIDAL -> pyramid(b, index, mb, wt, h)
            RoofShape.MANSARD -> mansard(b, index, mb, wt, h)
            RoofShape.SKILLION -> skillionRoof(b, index, mb, sMin, span)
        }
    }

    private fun triangulate(b: Building) {
        var total = 0
        for (r in b.rings) total += r.size
        val flat = DoubleArray(total)
        var holes: IntArray? = null
        var o = 0
        for ((k, r) in b.rings.withIndex()) {
            if (k > 0) {
                if (holes == null) holes = IntArray(b.rings.size - 1)
                holes[k - 1] = o / 2
            }
            r.copyInto(flat, o)
            o += r.size
        }
        lastFlat = flat
        lastHoles = holes
        lastTriangles = Earcut.triangulate(flat, holes)
    }

    private fun wall(b: Building, index: Int, mb: MeshBuilder, ax: Double, ay: Double, bx: Double, by: Double, z0: Float, topA: Float, topB: Float) {
        val ex = bx - ax; val ey = by - ay
        val len = hypot(ex, ey)
        if (len < 0.3) return
        val nx = (ey / len).toFloat(); val ny = (-ex / len).toFloat()
        val mx = ((ax + bx) / 2).toFloat(); val my = ((ay + by) / 2).toFloat()
        val zMid = (z0 + (topA + topB) / 2) / 2
        mb.begin(FaceMesh.CULL, nx, ny, 0f, mx, my, zMid, group = FaceMesh.GROUP_WALL)
        val facing = shading.lambert(nx, ny, 0f) > 0f
        val wallH = (topA + topB) / 2 - z0
        // Sun and shade cells close up: up to 9 m wide and 7 m tall, three rows at most.
        val cols = if (detail && facing) max(1, min(8, (len / 9.0).toInt())) else 1
        val rows = if (detail && facing) max(1, min(3, (wallH / 7f).toInt())) else 1
        for (c in 0 until cols) {
            val u0 = c.toDouble() / cols; val u1 = (c + 1).toDouble() / cols
            val x0 = (ax + ex * u0).toFloat(); val y0 = (ay + ey * u0).toFloat()
            val x1 = (ax + ex * u1).toFloat(); val y1 = (ay + ey * u1).toFloat()
            val t0 = topA + (topB - topA) * u0.toFloat(); val t1 = topA + (topB - topA) * u1.toFloat()
            for (rr in 0 until rows) {
                val f0 = rr.toFloat() / rows; val f1 = (rr + 1).toFloat() / rows
                val za0 = z0 + (t0 - z0) * f0; val za1 = z0 + (t0 - z0) * f1
                val zb0 = z0 + (t1 - z0) * f0; val zb1 = z0 + (t1 - z0) * f1
                val lit = facing && !night && caster.lit(
                    ((x0 + x1) / 2 + nx * 0.15f).toDouble(), ((y0 + y1) / 2 + ny * 0.15f).toDouble(),
                    ((za0 + za1 + zb0 + zb1) / 4).toDouble(), index,
                )
                val col = shading.shade(b.wallColour, nx, ny, 0f, lit)
                val v0 = mb.v(x0, y0, za0, col)
                val v1 = mb.v(x1, y1, zb0, col)
                val v2 = mb.v(x1, y1, zb1, col)
                val v3 = mb.v(x0, y0, za1, col)
                mb.quad(v0, v1, v2, v3)
            }
        }
        val litWall = facing && !night
        if (detail && z0 <= 0.5f && wallH > 3f) {
            band(mb, ax, ay, ex, ey, nx, ny, z0, z0 + 0.7f, z0 + 0.7f, 0.02f, shading.shade(theme.plinth, nx, ny, 0f, litWall))
        }
        if (windowsOn && len > 2.2 && wallH > 2.4f && b.wallMaterial != "glass" && b.kind !in NO_WINDOWS) {
            windows(b, mb, ax, ay, ex, ey, len, nx, ny, z0, min(topA, topB))
        }
        if (detail && b.cornice && len > 2.0) {
            val t = min(topA, topB)
            band(mb, ax, ay, ex, ey, nx, ny, t - 0.42f, t, t, 0.05f, shading.shade(theme.cornice, nx, ny, 0f, litWall))
        }
        mb.end()
    }

    /** A horizontal band along a wall from [zLo] to [zHi], pushed out by [off]. */
    private fun band(mb: MeshBuilder, ax: Double, ay: Double, ex: Double, ey: Double, nx: Float, ny: Float, zLo: Float, zHi: Float, zHi2: Float, off: Float, col: Int) {
        val x0 = ax.toFloat() + nx * off; val y0 = ay.toFloat() + ny * off
        val x1 = (ax + ex).toFloat() + nx * off; val y1 = (ay + ey).toFloat() + ny * off
        val v0 = mb.v(x0, y0, zLo, col)
        val v1 = mb.v(x1, y1, zLo, col)
        val v2 = mb.v(x1, y1, zHi2, col)
        val v3 = mb.v(x0, y0, zHi, col)
        mb.quad(v0, v1, v2, v3)
    }

    private fun windows(b: Building, mb: MeshBuilder, ax: Double, ay: Double, ex: Double, ey: Double, len: Double, nx: Float, ny: Float, z0: Float, top: Float) {
        val wallH = top - z0
        val levels = max(1, min(b.levels, (wallH / 2.4f).toInt()))
        val fh = wallH / levels
        val style = b.windows
        val modern = b.year != null && b.year >= 1960
        val bay = style?.bay ?: if (modern) 3.4 else 2.1
        val cols = floor(len / bay).toInt()
        if (cols < 1) return
        val shopFloor = b.kind == "retail"
        val off = 0.04f
        // Seeded by the wall's place on its building (to the decimetre), not in the scene, so every zoom lights the same windows.
        val hash = Hash(b.seed * 7919 + Math.round((ax - b.centroidX) * 10) * 92_821 + Math.round((ay - b.centroidY) * 10))
        val lit = theme.windowLit
        for (r in (if (shopFloor) 1 else 0) until levels) {
            val ya: Float
            val yb: Float
            if (style != null) {
                ya = z0 + r * fh + fh * (1f - style.top.toFloat() - style.height.toFloat())
                yb = ya + fh * style.height.toFloat()
            } else {
                ya = z0 + r * fh + fh * (if (r > 0) 0.22f else 0.30f)
                yb = ya + fh * (if (r > 0) (if (modern) 0.62f else 0.58f) else 0.50f)
            }
            // From afar, lit windows next to each other merge into one strip of light: fewer quads, the same look.
            var runStart = -1
            var runColour = 0
            for (c in 0..cols) {
                if (night && !fine) {
                    // One draw per window, as close up, so a run lights exactly the windows zoom 18 lights one by one.
                    val pick = if (c < cols) hash.next() else 1.0
                    val on = pick < theme.litWindowShare
                    if (on && runStart < 0) { runStart = c; runColour = lit[((pick * 1000).toInt()) % lit.size] }
                    if (!on && runStart >= 0) {
                        windowQuad(mb, ax, ay, ex, ey, len, nx, ny, off, ya, yb, runStart, c - 1, cols, bay, style, modern, runColour)
                        runStart = -1
                    }
                    continue
                }
                if (c == cols) break
                val pick = hash.next()
                val colour: Int
                if (night) {
                    if (pick >= theme.litWindowShare) continue
                    colour = lit[((pick * 1000).toInt()) % lit.size]
                } else {
                    colour = shading.shade(skyGlass, nx, ny, 0f, false)
                }
                val u0: Double
                val u1: Double
                if (style != null) {
                    val half = style.width * bay / 2 / len
                    u0 = (c + 0.5) / cols - half; u1 = (c + 0.5) / cols + half
                } else {
                    u0 = (c + (if (modern) 0.10 else 0.28)) / cols; u1 = (c + (if (modern) 0.90 else 0.72)) / cols
                }
                val x0 = (ax + ex * u0).toFloat() + nx * off; val y0 = (ay + ey * u0).toFloat() + ny * off
                val x1 = (ax + ex * u1).toFloat() + nx * off; val y1 = (ay + ey * u1).toFloat() + ny * off
                val v0 = mb.v(x0, y0, ya, colour)
                val v1 = mb.v(x1, y1, ya, colour)
                val v2 = mb.v(x1, y1, yb, colour)
                val v3 = mb.v(x0, y0, yb, colour)
                mb.quad(v0, v1, v2, v3)
            }
        }
    }

    /** One quad over windows [c0]..[c1] of a row. */
    private fun windowQuad(
        mb: MeshBuilder, ax: Double, ay: Double, ex: Double, ey: Double, len: Double, nx: Float, ny: Float, off: Float,
        ya: Float, yb: Float, c0: Int, c1: Int, cols: Int, bay: Double, style: WindowStyle?, modern: Boolean, colour: Int,
    ) {
        val u0: Double
        val u1: Double
        if (style != null) {
            val half = style.width * bay / 2 / len
            u0 = (c0 + 0.5) / cols - half; u1 = (c1 + 0.5) / cols + half
        } else {
            u0 = (c0 + (if (modern) 0.10 else 0.28)) / cols; u1 = (c1 + (if (modern) 0.90 else 0.72)) / cols
        }
        val x0 = (ax + ex * u0).toFloat() + nx * off; val y0 = (ay + ey * u0).toFloat() + ny * off
        val x1 = (ax + ex * u1).toFloat() + nx * off; val y1 = (ay + ey * u1).toFloat() + ny * off
        val v0 = mb.v(x0, y0, ya, colour)
        val v1 = mb.v(x1, y1, ya, colour)
        val v2 = mb.v(x1, y1, yb, colour)
        val v3 = mb.v(x0, y0, yb, colour)
        mb.quad(v0, v1, v2, v3)
    }

    private fun flat(b: Building, index: Int, mb: MeshBuilder, h: Float) {
        val lit = !night && caster.lit(b.centroidX, b.centroidY, b.height + 0.05, index)
        val col = shading.shade(b.roofColour, 0f, 0f, 1f, lit)
        mb.begin(FaceMesh.CULL, 0f, 0f, 1f, b.centroidX.toFloat(), b.centroidY.toFloat(), h, group = FaceMesh.GROUP_ROOF)
        mb.polygon(lastFlat, lastTriangles, h, col)
        mb.end()
    }

    private fun ridged(b: Building, index: Int, mb: MeshBuilder, z0: Float, wt: Float, h: Float) {
        val r = b.outer
        val n = r.size / 2
        val ux = b.axisX; val uy = b.axisY
        val vx = -uy; val vy = ux
        val e = Rings.extent(r, b.centroidX, b.centroidY, ux, uy)
        val mcx = b.centroidX + ux * (e[0] + e[1]) / 2 + vx * (e[2] + e[3]) / 2
        val mcy = b.centroidY + uy * (e[0] + e[1]) / 2 + vy * (e[2] + e[3]) / 2
        val half = (e[1] - e[0]) / 2
        val hipped = b.roof == RoofShape.HIPPED
        val inset = if (hipped) min(b.roofHeight, half) else 0.0
        val lo = -half + inset
        val hi = half - inset
        val qx = DoubleArray(n); val qy = DoubleArray(n)
        for (i in 0 until n) {
            val s = ((r[2 * i] - mcx) * ux + (r[2 * i + 1] - mcy) * uy).coerceIn(lo, hi)
            qx[i] = mcx + ux * s; qy[i] = mcy + uy * s
        }
        val rh = b.roofHeight.toFloat()
        // Gable ends first (they are walls), then the slopes.
        for (pass in 0..1) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = r[2 * i]; val ay = r[2 * i + 1]
                val bx = r[2 * j]; val by = r[2 * j + 1]
                if (hypot(bx - ax, by - ay) < 0.05) continue
                val end = hypot(qx[j] - qx[i], qy[j] - qy[i]) < 0.3
                if (end && !hipped) {
                    if (pass != 0) continue
                    val px = ((qx[i] + qx[j]) / 2).toFloat(); val py = ((qy[i] + qy[j]) / 2).toFloat()
                    gable(b, index, mb, ax, ay, bx, by, px, py, wt, h, rh)
                } else {
                    if (pass != 1) continue
                    if (end) slope(b, index, mb, ax, ay, bx, by, qx[i], qy[i], qx[i], qy[i], wt, h, true)
                    else slope(b, index, mb, ax, ay, bx, by, qx[j], qy[j], qx[i], qy[i], wt, h, false)
                }
            }
        }
        if (detail && !night) {
            // A lit ridge: a pale line along the top.
            val r0x = (mcx + ux * lo).toFloat(); val r0y = (mcy + uy * lo).toFloat()
            val r1x = (mcx + ux * hi).toFloat(); val r1y = (mcy + uy * hi).toFloat()
            if (hi - lo > 0.5) {
                val w = 0.08f
                val col = Colours.mix(shading.shade(b.roofColour, 0f, 0f, 1f, true), theme.ridge, 0.5f)
                mb.begin(FaceMesh.CULL, 0f, 0f, 1f, (r0x + r1x) / 2, (r0y + r1y) / 2, h + 0.03f, group = FaceMesh.GROUP_ON_ROOF)
                val wx = (vx * w).toFloat(); val wy = (vy * w).toFloat()
                val a = mb.v(r0x - wx, r0y - wy, h + 0.03f, col)
                val bb = mb.v(r1x - wx, r1y - wy, h + 0.03f, col)
                val c = mb.v(r1x + wx, r1y + wy, h + 0.03f, col)
                val d = mb.v(r0x + wx, r0y + wy, h + 0.03f, col)
                mb.quad(a, bb, c, d)
                mb.end()
            }
            b.chimney?.let { chimney(b, index, mb, mcx, mcy, ux, uy, lo, hi, h, it) }
        }
    }

    private fun gable(b: Building, index: Int, mb: MeshBuilder, ax: Double, ay: Double, bx: Double, by: Double, px: Float, py: Float, wt: Float, h: Float, rh: Float) {
        val ex = bx - ax; val ey = by - ay
        val len = hypot(ex, ey)
        val nx = (ey / len).toFloat(); val ny = (-ex / len).toFloat()
        val cx = ((ax + bx) / 2).toFloat(); val cy = ((ay + by) / 2).toFloat()
        mb.begin(FaceMesh.CULL, nx, ny, 0f, cx, cy, wt + rh / 3, group = FaceMesh.GROUP_WALL)
        val facing = shading.lambert(nx, ny, 0f) > 0f
        val lit = facing && !night && caster.lit((cx + nx * 0.15f).toDouble(), (cy + ny * 0.15f).toDouble(), (wt + rh / 3).toDouble(), index)
        val col = shading.shade(b.wallColour, nx, ny, 0f, lit)
        val v0 = mb.v(ax.toFloat(), ay.toFloat(), wt, col)
        val v1 = mb.v(bx.toFloat(), by.toFloat(), wt, col)
        val v2 = mb.v(px, py, h, col)
        mb.tri(v0, v1, v2)
        if (fine && b.hoist && len > 3) {
            // The hoist beam: a dark stub under the apex.
            val beam = if (theme.wallPalette != null) Colours.scale(b.wallColour, 0.7f) else HOIST_BEAM
            val z = h - rh / 4
            val ox = nx * 0.06f; val oy = ny * 0.06f
            val tx = (ex / len).toFloat() * 0.18f; val ty = (ey / len).toFloat() * 0.18f
            val a = mb.v(px - tx + ox, py - ty + oy, z - 0.22f, beam)
            val bb = mb.v(px + tx + ox, py + ty + oy, z - 0.22f, beam)
            val c = mb.v(px + tx + ox, py + ty + oy, z + 0.12f, beam)
            val d = mb.v(px - tx + ox, py - ty + oy, z + 0.12f, beam)
            mb.quad(a, bb, c, d)
        }
        mb.end()
    }

    /** One roof slope from the eave a–b up to ridge points q (qb then qa), or a hip triangle when [triangle]. */
    private fun slope(
        b: Building, index: Int, mb: MeshBuilder, ax: Double, ay: Double, bx: Double, by: Double,
        qbx: Double, qby: Double, qax: Double, qay: Double, wt: Float, h: Float, triangle: Boolean,
    ) {
        // Normal from the eave edge and the rise to the ridge, turned upward.
        val ex = bx - ax; val ey = by - ay
        val fx = qax - ax; val fy = qay - ay; val fz = (h - wt).toDouble()
        var nx = ey * fz - 0.0 * fy
        var ny = 0.0 * fx - ex * fz
        var nz = ex * fy - ey * fx
        if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < 1e-9) return
        val nxf = (nx / nl).toFloat(); val nyf = (ny / nl).toFloat(); val nzf = (nz / nl).toFloat()
        val cx = ((ax + bx + qax + qbx) / 4).toFloat(); val cy = ((ay + by + qay + qby) / 4).toFloat()
        val cz = (wt + h) / 2
        mb.begin(FaceMesh.CULL, nxf, nyf, nzf, cx, cy, cz, group = FaceMesh.GROUP_ROOF)
        val facing = shading.lambert(nxf, nyf, nzf) > 0f
        val lit = facing && !night && caster.lit((cx + nxf * 0.2f).toDouble(), (cy + nyf * 0.2f).toDouble(), (cz + nzf * 0.2f).toDouble(), index)
        val col = shading.shade(b.roofColour, nxf, nyf, nzf, lit)
        val v0 = mb.v(ax.toFloat(), ay.toFloat(), wt, col)
        val v1 = mb.v(bx.toFloat(), by.toFloat(), wt, col)
        if (triangle) {
            mb.tri(v0, v1, mb.v(qax.toFloat(), qay.toFloat(), h, col))
        } else {
            val v2 = mb.v(qbx.toFloat(), qby.toFloat(), h, col)
            val v3 = mb.v(qax.toFloat(), qay.toFloat(), h, col)
            mb.quad(v0, v1, v2, v3)
            if (fine && (b.roofMaterial == "roof_tiles" || b.roofMaterial == "slate")) {
                courses(mb, ax, ay, bx, by, qax, qay, qbx, qby, wt, h, nxf, nyf, nzf, Colours.scale(col, 0.84f))
            }
        }
        mb.end()
    }

    /** Tile courses: thin lines up the slope, parallel to the eaves. */
    private fun courses(
        mb: MeshBuilder, ax: Double, ay: Double, bx: Double, by: Double, qax: Double, qay: Double, qbx: Double, qby: Double,
        wt: Float, h: Float, nx: Float, ny: Float, nz: Float, col: Int,
    ) {
        val rise = (h - wt).toDouble()
        val run = hypot(qax - ax, qay - ay)
        val slopeLen = sqrt(rise * rise + run * run)
        val step = 0.5
        val count = (slopeLen / step).toInt()
        if (count < 2) return
        val w = (0.06 / slopeLen).toFloat()
        val ox = nx * 0.02f; val oy = ny * 0.02f; val oz = nz * 0.02f
        for (k in 1 until count) {
            val t = (k * step / slopeLen).toFloat()
            fun px(t: Float, x0: Double, x1: Double) = (x0 + (x1 - x0) * t).toFloat()
            val a0x = px(t, ax, qax) + ox; val a0y = px(t, ay, qay) + oy
            val b0x = px(t, bx, qbx) + ox; val b0y = px(t, by, qby) + oy
            val a1x = px(t + w, ax, qax) + ox; val a1y = px(t + w, ay, qay) + oy
            val b1x = px(t + w, bx, qbx) + ox; val b1y = px(t + w, by, qby) + oy
            val z0 = wt + (h - wt) * t + oz
            val z1 = wt + (h - wt) * (t + w) + oz
            val v0 = mb.v(a0x, a0y, z0, col)
            val v1 = mb.v(b0x, b0y, z0, col)
            val v2 = mb.v(b1x, b1y, z1, col)
            val v3 = mb.v(a1x, a1y, z1, col)
            mb.quad(v0, v1, v2, v3)
        }
    }

    private fun chimney(b: Building, index: Int, mb: MeshBuilder, mcx: Double, mcy: Double, ux: Double, uy: Double, lo: Double, hi: Double, h: Float, style: ChimneyStyle) {
        val rnd = Hash(b.seed * 3 + 1)
        val (fLo, fHi) = if (style.kind == "capped") 0.25 to 0.75 else 0.3 to 0.7
        val s = lo + (hi - lo) * rnd.range(fLo, fHi)
        val cx = mcx + ux * s
        val cy = mcy + uy * s
        val hs = when (style.kind) { "capped" -> 0.32; "party" -> 0.35; else -> 0.28 }
        val ht = when (style.kind) { "capped" -> 1.0; "party" -> 1.3; else -> 0.9 }
        // A theme with its own palette (chart, grey) paints chimneys in it too, a shade darker than the walls.
        val own = theme.wallPalette != null
        val colour = if (own) Colours.scale(b.wallColour, 0.9f) else style.colour
        box(mb, index, cx, cy, ux, uy, hs, hs, h - 0.6f, h + ht.toFloat(), colour)
        if (style.kind == "capped") {
            box(mb, index, cx, cy, ux, uy, hs + 0.12, hs + 0.12, h + ht.toFloat(), h + ht.toFloat() + 0.14f, if (own) colour else style.cap ?: colour)
        }
    }

    /** A box standing on a roof: four sides and a top, each its own face. */
    private fun box(mb: MeshBuilder, index: Int, cx: Double, cy: Double, ux: Double, uy: Double, hu: Double, hv: Double, z0: Float, z1: Float, colour: Int) {
        val vx = -uy; val vy = ux
        val xs = DoubleArray(4); val ys = DoubleArray(4)
        val su = doubleArrayOf(-hu, hu, hu, -hu); val sv = doubleArrayOf(-hv, -hv, hv, hv)
        for (k in 0 until 4) { xs[k] = cx + ux * su[k] + vx * sv[k]; ys[k] = cy + uy * su[k] + vy * sv[k] }
        // Wind the corners like an outer ring (positive area) so (ey, -ex) points out.
        val ring = doubleArrayOf(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], xs[3], ys[3])
        val pos = Rings.area(ring) > 0
        for (k in 0 until 4) {
            val i = if (pos) k else 3 - k
            val j = if (pos) (k + 1) % 4 else (3 - k + 3) % 4
            val ex = xs[j] - xs[i]; val ey = ys[j] - ys[i]
            val l = hypot(ex, ey)
            val nx = (ey / l).toFloat(); val ny = (-ex / l).toFloat()
            val mx = ((xs[i] + xs[j]) / 2).toFloat(); val my = ((ys[i] + ys[j]) / 2).toFloat()
            mb.begin(FaceMesh.CULL, nx, ny, 0f, mx, my, (z0 + z1) / 2, group = FaceMesh.GROUP_ON_ROOF)
            val col = shading.shade(colour, nx, ny, 0f, !night && shading.lambert(nx, ny, 0f) > 0f &&
                caster.lit((mx + nx * 0.1f).toDouble(), (my + ny * 0.1f).toDouble(), z1.toDouble(), index))
            val a = mb.v(xs[i].toFloat(), ys[i].toFloat(), z0, col)
            val bb = mb.v(xs[j].toFloat(), ys[j].toFloat(), z0, col)
            val c = mb.v(xs[j].toFloat(), ys[j].toFloat(), z1, col)
            val d = mb.v(xs[i].toFloat(), ys[i].toFloat(), z1, col)
            mb.quad(a, bb, c, d)
            mb.end()
        }
        val top = shading.shade(colour, 0f, 0f, 1f, !night)
        mb.begin(FaceMesh.CULL, 0f, 0f, 1f, cx.toFloat(), cy.toFloat(), z1, group = FaceMesh.GROUP_ON_ROOF)
        val v = IntArray(4) { mb.v(xs[it].toFloat(), ys[it].toFloat(), z1, top) }
        mb.quad(v[0], v[1], v[2], v[3])
        mb.end()
    }

    private fun pyramid(b: Building, index: Int, mb: MeshBuilder, wt: Float, h: Float) {
        val r = b.outer
        val n = r.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            slope(b, index, mb, r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1], b.centroidX, b.centroidY, b.centroidX, b.centroidY, wt, h, true)
        }
    }

    private fun mansard(b: Building, index: Int, mb: MeshBuilder, wt: Float, h: Float) {
        val rh = b.roofHeight
        val inner = Rings.inset(b.outer, min(1.4, 0.36 * rh)) ?: Rings.inset(b.outer, min(0.8, 0.22 * rh))
        if (inner == null) {
            flatAt(b, index, mb, b.outer, wt)
            return
        }
        val r = b.outer
        val n = r.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            slope(b, index, mb, r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1], inner[2 * j], inner[2 * j + 1], inner[2 * i], inner[2 * i + 1], wt, h, false)
        }
        flatAt(b, index, mb, inner, h)
    }

    private fun flatAt(b: Building, index: Int, mb: MeshBuilder, ring: DoubleArray, z: Float) {
        val tris = Earcut.triangulate(ring)
        val c = Rings.centroid(ring)
        val lit = !night && caster.lit(c[0], c[1], z + 0.05, index)
        val col = shading.shade(b.roofColour, 0f, 0f, 1f, lit)
        mb.begin(FaceMesh.CULL, 0f, 0f, 1f, c[0].toFloat(), c[1].toFloat(), z, group = FaceMesh.GROUP_ROOF)
        mb.polygon(ring, tris, z, col)
        mb.end()
    }

    private fun skillionRoof(b: Building, index: Int, mb: MeshBuilder, sMin: Double, span: Double) {
        val r = b.outer
        val n = r.size / 2
        val rh = b.roofHeight
        var nx = b.axisX * rh / span
        var ny = b.axisY * rh / span
        var nz = 1.0
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        val zc = (b.height - rh / 2).toFloat()
        mb.begin(FaceMesh.CULL, nx.toFloat(), ny.toFloat(), nz.toFloat(), b.centroidX.toFloat(), b.centroidY.toFloat(), zc, group = FaceMesh.GROUP_ROOF)
        val lit = !night && caster.lit(b.centroidX, b.centroidY, zc + 0.2, index)
        val col = shading.shade(b.roofColour, nx.toFloat(), ny.toFloat(), nz.toFloat(), lit)
        val tris = Earcut.triangulate(r)
        val base = IntArray(n)
        for (i in 0 until n) {
            val s = (r[2 * i] - b.centroidX) * b.axisX + (r[2 * i + 1] - b.centroidY) * b.axisY
            base[i] = mb.v(r[2 * i].toFloat(), r[2 * i + 1].toFloat(), (b.height - rh * (s - sMin) / span).toFloat(), col)
        }
        var k = 0
        while (k < tris.size) { mb.tri(base[tris[k]], base[tris[k + 1]], base[tris[k + 2]]); k += 3 }
        mb.end()
    }

    companion object {
        private val HOIST_BEAM = Colours.parse("#2A221C")

        /** Kinds drawn without window rows (sheds, hangars, glass and the like). */
        val NO_WINDOWS = setOf("industrial", "hangar", "tank", "canopy", "shed", "parking", "greenhouse", "bunker", "caravan", "kiosk", "terminal", "tower")
    }
}
