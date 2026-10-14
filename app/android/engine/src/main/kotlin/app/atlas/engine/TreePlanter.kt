package app.atlas.engine

import app.atlas.geo.Mercator
import app.atlas.tiles.GeomType
import app.atlas.tiles.VectorTile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Trees for a scene: the real ones the app gives ([Extras]: `natural=tree` places and tree rows), and, in woods,
 * parks, gardens and cemeteries that have none mapped, trees planted on a jittered hexagonal grid anchored to the
 * world (so neighbouring tiles and every zoom agree), never on water or buildings.
 */
internal class TreePlanter(
    private val frame: TileFrame,
    private val theme: Theme,
    private val shading: Shading,
    private val caster: ShadowCaster,
    /** How far broadleaved crowns have turned ([Look.autumn]). */
    private val autumn: Float = 0f,
) {
    private val xs = FloatList(); private val ys = FloatList(); private val hs = FloatList(); private val rs = FloatList()
    private val dark = IntList(); private val mid = IntList(); private val lite = IntList(); private val far = IntList()
    private val mapped = ArrayList<Boolean>()
    private val size = frame.size
    private val originX = frame.id.unitX
    private val originY = frame.id.unitY
    private val mpu = frame.metresPerUnit

    private val sunNx: Float
    private val sunNy: Float
    private val sunNz: Float
    private val hiNx: Float
    private val hiNy: Float
    private val hiNz: Float

    init {
        val l = shading.light
        val a = sqrt(l.x * l.x + l.y * l.y + 0.64f)
        sunNx = l.x / a; sunNy = l.y / a; sunNz = 0.8f / a
        val b = sqrt(l.x * l.x + l.y * l.y + 0.36f)
        hiNx = l.x / b; hiNy = l.y / b; hiNz = 0.6f / b
    }

    val count: Int get() = xs.size

    /** Real trees from the app's place store and tree rows. */
    fun real(extras: Extras?) {
        if (extras == null) return
        for (p in extras.trees) {
            val x = ((Mercator.x(p.location.lon) - originX) * mpu)
            val y = ((Mercator.y(p.location.lat) - originY) * mpu)
            if (x < 0 || y < 0 || x >= size || y >= size) continue
            val h = p.tags["height"]?.let { num(it) } ?: 12.0
            val crown = p.tags["diameter_crown"]?.let { num(it) } ?: 7.0
            val seed = Hash.ofPoint(Mercator.x(p.location.lon) * 1e7, Mercator.y(p.location.lat) * 1e7, 3)
            add(x, y, h, crown / 2, seed, true, p.tags["leaf_type"] == "needleleaved")
        }
        for (row in extras.treeRows) {
            if (row.line.size < 2) continue
            // Walked in world units at the row's own scale, so every scene (any zoom, any tile) plants the same trees.
            val k = Mercator.metresPerUnit(row.line[0].lat)
            val spacing = row.spacing / k
            val us = DoubleArray(row.line.size) { Mercator.x(row.line[it].lon) }
            val vs = DoubleArray(row.line.size) { Mercator.y(row.line[it].lat) }
            val h = row.tags["height"]?.let { num(it) } ?: 11.0
            val crown = (row.tags["diameter_crown"]?.let { num(it) } ?: 6.5) / 2
            var carry = spacing / 2
            for (i in 0 until us.size - 1) {
                val len = hypot(us[i + 1] - us[i], vs[i + 1] - vs[i])
                var s = carry
                while (s <= len) {
                    val u = us[i] + (us[i + 1] - us[i]) * s / len; val v = vs[i] + (vs[i + 1] - vs[i]) * s / len
                    val x = (u - originX) * mpu; val y = (v - originY) * mpu
                    if (x >= 0 && y >= 0 && x < size && y < size) add(x, y, h, crown, Hash.ofPoint(u * 1e7, v * 1e7, 5), true, false)
                    s += spacing
                }
                carry = s - len
            }
        }
    }

    /** Trees planted in green areas that have no mapped tree inside. */
    fun procedural(tile: VectorTile, water: List<List<DoubleArray>>, limit: Int) {
        if (frame.zoom < 15) return
        val realCount = xs.size
        val areas = ArrayList<Pair<List<DoubleArray>, Kind>>()
        tile["landcover"]?.let { layer ->
            for (f in layer.features) {
                if (f.type != GeomType.POLYGON) continue
                val kind = when (f.string("subclass") ?: f.string("class")) {
                    "wood", "forest" -> Kind.WOOD
                    "park" -> Kind.PARK
                    "garden" -> Kind.GARDEN
                    else -> null
                } ?: continue
                val g = frame.geometry(f.geometry, layer.extent, 10.0)
                for (poly in frame.polygons(g, layer.extent)) areas += poly to kind
            }
        }
        tile["landuse"]?.let { layer ->
            for (f in layer.features) {
                if (f.type != GeomType.POLYGON || f.string("class") != "cemetery") continue
                val g = frame.geometry(f.geometry, layer.extent, 10.0)
                for (poly in frame.polygons(g, layer.extent)) areas += poly to Kind.CEMETERY
            }
        }
        for ((poly, kind) in areas) {
            if (xs.size - realCount >= limit) break
            if (hasRealTree(poly, realCount)) continue
            plant(poly, kind, water, realCount, limit)
        }
    }

    private enum class Kind(val spacing: Double, val hLo: Double, val hHi: Double, val rLo: Double, val rHi: Double, val keep: Double) {
        WOOD(8.5, 13.0, 21.0, 3.0, 4.5, 1.0),
        PARK(15.0, 10.0, 18.0, 3.5, 5.5, 0.8),
        GARDEN(12.0, 6.0, 12.0, 2.4, 3.8, 0.55),
        CEMETERY(11.0, 8.0, 15.0, 2.8, 4.2, 0.85),
    }

    private fun hasRealTree(poly: List<DoubleArray>, realCount: Int): Boolean {
        for (i in 0 until realCount) if (Rings.contains(poly, xs[i].toDouble(), ys[i].toDouble())) return true
        return false
    }

    private fun plant(poly: List<DoubleArray>, kind: Kind, water: List<List<DoubleArray>>, realCount: Int, limit: Int) {
        val outer = poly[0]
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until outer.size / 2) {
            x0 = min(x0, outer[2 * i]); x1 = max(x1, outer[2 * i]); y0 = min(y0, outer[2 * i + 1]); y1 = max(y1, outer[2 * i + 1])
        }
        x0 = max(x0, 0.0); y0 = max(y0, 0.0); x1 = min(x1, size); y1 = min(y1, size)
        if (x1 <= x0 || y1 <= y0) return
        // A world-anchored grid: spacing in Mercator metres, quantised so neighbouring tiles share it.
        val worldM = Mercator.CIRCUMFERENCE
        val k = worldM / mpu // Mercator metres per scene metre
        val sx = (kind.spacing * k * 2).let { floor(it + 0.5) / 2 }
        val sy = sx * sqrt(3.0) / 2
        val gx0 = originX * worldM + x0 * k
        val gy0 = originY * worldM + y0 * k
        val gx1 = originX * worldM + x1 * k
        val gy1 = originY * worldM + y1 * k
        val r0 = floor(gy0 / sy).toLong()
        val r1 = floor(gy1 / sy).toLong()
        for (row in r0..r1) {
            val shift = if (row and 1L == 1L) sx / 2 else 0.0
            val c0 = floor((gx0 - shift) / sx).toLong()
            val c1 = floor((gx1 - shift) / sx).toLong()
            for (col in c0..c1) {
                val h = Hash(row * 73_856_093L xor col * 19_349_663L xor kind.ordinal.toLong())
                if (h.next() > kind.keep) continue
                val gx = col * sx + shift + h.range(-0.3, 0.3) * sx
                val gy = row * sy + h.range(-0.3, 0.3) * sy
                val x = (gx - originX * worldM) / k
                val y = (gy - originY * worldM) / k
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                if (!Rings.contains(poly, x, y)) continue
                if (blocked(x, y, water)) continue
                add(x, y, h.range(kind.hLo, kind.hHi), h.range(kind.rLo, kind.rHi), h.nextLong(), false, false)
                if (xs.size - realCount >= limit) return
            }
        }
    }

    private fun blocked(x: Double, y: Double, water: List<List<DoubleArray>>): Boolean {
        val d = 1.8
        if (caster.buildingAt(x, y) >= 0 || caster.buildingAt(x + d, y) >= 0 || caster.buildingAt(x - d, y) >= 0 ||
            caster.buildingAt(x, y + d) >= 0 || caster.buildingAt(x, y - d) >= 0
        ) return true
        for (w in water) if (Rings.contains(w, x, y)) return true
        return false
    }

    /**
     * A crown's tones, as the living map shades a tree: its shaded underside (the leaf colour at 70 %, lit by the sky
     * alone), its sunlit top (the leaf facing up toward the sun) and, for crowns a few pixels across, one tone between
     * the two. Broadleaved leaves turn toward [Theme.treeAutumn] by [autumn]; needles stay green.
     */
    private fun add(x: Double, y: Double, h: Double, r: Double, seed: Long, real: Boolean, needle: Boolean) {
        val hash = Hash(seed)
        val green = if (needle) Colours.mix(theme.treeMid, theme.treeDark, 0.5f) else theme.treeMid
        val leaf = theme.treeAutumn?.takeIf { !needle && autumn > 0f }?.let { Colours.mix(green, it, autumn) } ?: green
        val base = if (theme.treeAutumn != null) leaf else BuildingGuesser.jitter(leaf, seed, 0.08)
        val lit = !theme.night
        xs.add(x.toFloat()); ys.add(y.toFloat()); hs.add(h.toFloat()); rs.add((r * hash.range(0.92, 1.08)).toFloat())
        val under = shading.shade(Colours.scale(base, 0.7f), 0f, 0f, 0.3f, false)
        val top = shading.shade(base, sunNx, sunNy, sunNz, lit)
        dark.add(under)
        mid.add(top)
        lite.add(shading.shade(Colours.scale(Colours.mix(base, theme.treeLight, 0.3f), 0.82f), hiNx, hiNy, hiNz, lit))
        far.add(Colours.mix(under, top, 0.55f))
        mapped += real
    }

    fun build(): TreeSet {
        val n = xs.size
        return TreeSet(
            n, xs.toArray(), ys.toArray(), FloatArray(n), hs.toArray(), rs.toArray(), dark.toArray(), mid.toArray(), lite.toArray(),
            shading.shade(theme.trunk, 0f, 0f, 1f, false), mapped.toBooleanArray(), far.toArray(),
        )
    }

    /** Ground shadows of the trees: a disc each, offset from the trunk as the sun says. */
    fun shadows(set: TreeSet, mb: MeshBuilder, colour: Int) {
        val l = shading.light
        if (!l.isUp) return
        for (i in 0 until set.count) {
            val dx = l.shadowDx(0.7f * set.height[i]); val dy = l.shadowDy(0.7f * set.height[i])
            val cx = set.x[i] + dx; val cy = set.y[i] + dy
            val r = set.radius[i]
            mb.begin(FaceMesh.CULL, 0f, 0f, 1f, set.x[i], set.y[i], 0f)
            val c = mb.v(cx, cy, 0.05f, colour)
            var prev = -1
            var first = -1
            for (k in 0 until 12) {
                val a = (2 * PI * k / 12).toFloat()
                val v = mb.v(cx + r * cos(a), cy + r * kotlin.math.sin(a), 0.05f, colour)
                if (prev >= 0) mb.tri(c, prev, v) else first = v
                prev = v
            }
            mb.tri(c, prev, first)
            // The trunk's thin shadow from the foot of the tree, its width across the way the shadow falls.
            val len = sqrt(dx * dx + dy * dy)
            if (len > 0.05f) {
                val wx = -dy / len * 0.18f; val wy = dx / len * 0.18f
                val t0 = mb.v(set.x[i] - wx, set.y[i] - wy, 0.05f, colour)
                val t1 = mb.v(set.x[i] + wx, set.y[i] + wy, 0.05f, colour)
                val t2 = mb.v(cx + wx, cy + wy, 0.05f, colour)
                val t3 = mb.v(cx - wx, cy - wy, 0.05f, colour)
                mb.quad(t0, t1, t2, t3)
            }
            mb.end()
        }
    }

    private fun num(s: String): Double? = s.trim().split(' ', ';').firstOrNull()?.toDoubleOrNull()
}
