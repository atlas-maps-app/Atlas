package app.atlas.engine

import app.atlas.geo.Shapes
import app.atlas.geo.TileId
import app.atlas.tiles.GeomType
import app.atlas.tiles.VectorTile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Builds a [TileScene] from a decoded vector tile (OpenMapTiles schema): once per tile, off the main thread.
 *
 * Buildings are extruded at scene zoom 14 and above when the data carries heights; farther scenes draw them as flat
 * footprints. Overzoomed scenes (built from an ancestor's data, [source] ≠ [id]) take only what falls in their square.
 *
 * A build can be cancelled: when its thread is interrupted (a coroutine cancelled inside `runInterruptible`, a future
 * cancelled with interruption), it stops within a few milliseconds by throwing [InterruptedException].
 */
object SceneBuilder {
    /** Procedural trees a scene plants at most. */
    const val TREE_LIMIT = 5000

    fun build(tile: VectorTile, id: TileId, look: Look, extras: Extras? = null, source: TileId = id): TileScene {
        val t0 = System.nanoTime()
        val frame = TileFrame(id, source)
        val theme = look.theme
        val shading = look.shading
        val tradition = look.traditionAt(frame.centreLat, frame.centreLon)

        // Buildings first: the ground's trees and the shadows need them.
        val buildingLayer = tile["building"]
        val extrude = id.z >= 14 && buildingLayer != null && buildingLayer.keyIndex("render_height") >= 0
        val nearby = ArrayList<Building>()
        val buildings = if (extrude) readBuildings(tile, frame, tradition, theme, nearby) else emptyList()
        checkCancelled()
        // Neighbours' buildings near the edge shade this scene's walls and keep its trees out of them too.
        val caster = ShadowCaster(if (nearby.isEmpty()) buildings else buildings + nearby, shading.light)

        // Ground.
        val ground = GroundBuilder(frame, theme, shading)
        ground.earth()
        ground.land(tile)
        checkCancelled()
        if (!extrude) ground.footprints(tile)
        ground.water(tile)
        checkCancelled()
        ground.transportAreas(tile)
        ground.roads(tile)
        checkCancelled()

        // Objects.
        val objects = MeshBuilder(32768)
        val mesher = BuildingMesher(theme, shading, caster, id.z)
        val shadowsOn = shading.light.isUp && !theme.night && theme.shadowOpacity > 0f
        val shadows = MeshBuilder(8192)
        val faceStart = IntList(buildings.size + 1)
        val centre = FloatList(buildings.size * 3)
        val radius = FloatList(buildings.size)
        val ref = IntList(buildings.size)
        val infos = ArrayList<BuildingInfo>(buildings.size)
        for ((i, b) in buildings.withIndex()) {
            if (i and 63 == 63) checkCancelled()
            faceStart.add(objects.faceCount)
            mesher.mesh(b, i, objects)
            centre.add(b.centroidX.toFloat()); centre.add(b.centroidY.toFloat()); centre.add(((b.minHeight + b.height) / 2).toFloat())
            var r2 = 0.0
            val o = b.outer
            for (k in 0 until o.size / 2) {
                val dx = o[2 * k] - b.centroidX; val dy = o[2 * k + 1] - b.centroidY
                r2 = max(r2, dx * dx + dy * dy)
            }
            val hh = (b.height - b.minHeight) / 2 + 2.0
            radius.add(sqrt(r2 + hh * hh).toFloat())
            ref.add(i)
            infos += BuildingInfo(
                b.featureId, TileFrame.osmId(b.featureId), b.height.toFloat(), b.minHeight.toFloat(), b.roof, b.kind,
                b.wallColour, b.roofColour, b.guessed, b.why, FloatArray(o.size) { o[it].toFloat() },
            )
            if (shadowsOn) shadow(b, mesher, shading.light, shadows, theme.shadow)
        }
        faceStart.add(objects.faceCount)
        checkCancelled()

        // Trees.
        val water = waterPolygons(tile, frame)
        val planter = TreePlanter(frame, theme, shading, caster)
        planter.real(extras)
        planter.procedural(tile, water, TREE_LIMIT)
        val trees = planter.build()
        if (shadowsOn) planter.shadows(trees, shadows, theme.shadow)
        checkCancelled()

        // Labels and points of interest.
        val labels = LabelReader(frame, theme, water)
        labels.read(tile)

        val groundMesh = ground.build()
        val objectMesh = objects.build()
        val shadowMesh = shadows.build()
        val stats = SceneStats(
            buildings = buildings.size, groundVertices = groundMesh.vertexCount, objectVertices = objectMesh.vertexCount,
            shadowVertices = shadowMesh.vertexCount, trees = trees.count, labels = labels.labels.size, pois = labels.pois.size,
            buildMillis = (System.nanoTime() - t0) / 1e6,
        )
        return TileScene(
            id = id, source = source, look = look, originX = id.unitX, originY = id.unitY, metresPerUnit = frame.metresPerUnit,
            size = frame.size.toFloat(), ground = groundMesh, shadows = shadowMesh,
            objects = ObjectSet(objectMesh, buildings.size, faceStart.toArray(), centre.toArray(), radius.toArray(), ref.toArray()),
            trees = trees, labels = labels.labels, pois = labels.pois, buildings = infos, stats = stats,
        )
    }

    /**
     * The scene's buildings: each footprint owned by the scene holding its centroid (whole, so it is drawn once); one the
     * tile itself had to cut is cut again to the scene's square, without walls along the cut.
     *
     * A footprint across the edge of the data's tile is drawn whole only when it reaches less than [STRADDLE] of the
     * tile's extent into both tiles: then both tiles hold all of it (their buffers are wider) and agree on its owner.
     * Any larger one may be cut by the neighbour's buffer, so every tile cuts it to its own square and each draws its
     * piece: drawn once, never twice.
     *
     * Whole buildings another scene owns within [NEARBY] metres of the square go to [nearby] when it is given: they are
     * not drawn here, but they cast shadows into the scene.
     */
    internal fun readBuildings(
        tile: VectorTile, frame: TileFrame, tradition: Tradition, theme: Theme, nearby: MutableList<Building>? = null,
    ): List<Building> {
        val layer = tile["building"] ?: return emptyList()
        val extent = layer.extent
        val bounds = TileFrame.layerBounds(layer)
        val reach = extent * STRADDLE
        val sq = frame.sourceRect(extent, 0.0) // the scene's square in source units
        val around = frame.sourceRect(extent, NEARBY)
        val size = frame.size
        val hints = KindHints(tile, frame)
        val guesser = BuildingGuesser(tradition, theme)
        val out = ArrayList<Building>()
        val features = layer.features
        for (n in features.indices) {
            if (n and 255 == 255) checkCancelled()
            val f = features[n]
            if (f.type != GeomType.POLYGON || f.bool("hide_3d") == true) continue
            val g = f.geometry
            val height = f.double("render_height")
            val minHeight = f.double("render_min_height")
            val colour = f.string("colour")
            g.forEachPolygon { _, first, end ->
                // Bounds of the outer ring in source units.
                var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
                for (i in g.partStart(first) until g.partEnd(first)) {
                    val x = g.x(i); val y = g.y(i)
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
                val inSquare = !(x1 < sq[0] || x0 > sq[2] || y1 < sq[1] || y0 > sq[3])
                if (!inSquare && (nearby == null || x1 < around[0] || x0 > around[2] || y1 < around[1] || y0 > around[3])) return@forEachPolygon
                // Cut by the tile: it reaches the edge of the tile's buffer (where the layer's data stops, outside the tile),
                // or it crosses the tile's edge too far for the neighbour to be sure to hold it whole.
                val cutByTile = (x0 <= bounds[0] && bounds[0] < 0) || (x1 >= bounds[2] && bounds[2] > extent) ||
                    (y0 <= bounds[1] && bounds[1] < 0) || (y1 >= bounds[3] && bounds[3] > extent) ||
                    !straddlesLittle(x0, x1, extent, reach) || !straddlesLittle(y0, y1, extent, reach)
                var rings = ArrayList<DoubleArray>(end - first)
                for (p in first until end) {
                    var r = frame.ring(g, p, extent)
                    if (r.size < 6) continue
                    val a = Rings.area(r)
                    if (p == first && a < 0) r = Rings.reversed(r)
                    if (p != first && a > 0) r = Rings.reversed(r)
                    if (p == first || rings.isNotEmpty()) rings += r
                }
                if (rings.isEmpty()) return@forEachPolygon
                // Pieces of a cut building belong to the scenes they fall in.
                if (cutByTile && !inSquare) return@forEachPolygon
                var owned = true
                var cut: List<BooleanArray>? = null
                if (cutByTile) {
                    // Cut to the scene square; edges along it are not walls.
                    val clipped = ArrayList<DoubleArray>()
                    for ((k, r) in rings.withIndex()) {
                        val c = Shapes.clipRing(r, 0.0, 0.0, size, size)
                        if (c.size < 6) { if (k == 0) return@forEachPolygon else continue }
                        clipped += stripClosing(c)
                    }
                    rings = clipped
                    cut = rings.map { r -> edgesOnSquare(r, size) }
                } else {
                    val c = Rings.centroid(rings[0])
                    owned = c[0] >= 0 && c[1] >= 0 && c[0] < size && c[1] < size
                    if (!owned && nearby == null) return@forEachPolygon
                }
                val cleaned = ArrayList<DoubleArray>(rings.size)
                val cleanedCut = if (cut != null) ArrayList<BooleanArray>() else null
                for ((k, r) in rings.withIndex()) {
                    val c = if (cut == null) Rings.clean(r) else r
                    if (c.size < 6) { if (k == 0) return@forEachPolygon else continue }
                    if (k > 0 && abs(Rings.area(c)) < 2.0) continue
                    cleaned += c
                    cleanedCut?.add(if (cut != null) edgesOnSquare(c, size) else BooleanArray(c.size / 2))
                }
                // A piece of a cut building may be a sliver; dropping it would leave a gap where the pieces meet.
                if (cleaned.isEmpty() || abs(Rings.area(cleaned[0])) < (if (cut != null) MIN_PIECE else MIN_AREA)) return@forEachPolygon
                val sx = g.x(g.partStart(first)); val sy = g.y(g.partStart(first))
                val seed = Hash.mix((frame.source.hashCode().toLong() shl 32) xor (sx.toLong() * 92_821L + sy) xor f.id)
                val cen = Rings.centroid(cleaned[0])
                val raw = RawBuilding(cleaned, cleanedCut, height, minHeight, colour, f.id, hints.kindAt(cen[0], cen[1], cleaned), seed)
                val b = guesser.resolve(raw) ?: return@forEachPolygon
                if (owned) out += b else nearby?.add(b)
            }
        }
        return out
    }

    /**
     * Fraction of a tile's extent a building may reach across the tile's edge, on either side, and still be drawn
     * whole: half the 4-pixel buffer OpenMapTiles tiles carry, so both tiles are sure to hold all of it.
     */
    internal const val STRADDLE = 1.0 / 128

    /** How far outside its square (metres) a scene looks for buildings that shade it. */
    internal const val NEARBY = 40.0

    /** Footprints smaller than this (m²) are left out; pieces of a cut building down to [MIN_PIECE]. */
    internal const val MIN_AREA = 3.0
    internal const val MIN_PIECE = 0.05

    /** Whether [lo]..[hi] (source units on one axis) stays inside 0..[extent] or crosses an edge by less than [reach] each way. */
    private fun straddlesLittle(lo: Int, hi: Int, extent: Int, reach: Double): Boolean {
        if (lo >= 0 && hi <= extent) return true
        if (lo < 0 && hi > extent) return false
        return if (lo < 0) lo > -reach && hi < reach else hi < extent + reach && lo > extent - reach
    }

    private fun stripClosing(r: DoubleArray): DoubleArray {
        val n = r.size / 2
        return if (n >= 2 && r[0] == r[2 * n - 2] && r[1] == r[2 * n - 1]) r.copyOf(r.size - 2) else r
    }

    /** Which edges of a ring lie along the scene square's sides. */
    private fun edgesOnSquare(r: DoubleArray, size: Double): BooleanArray {
        val n = r.size / 2
        val out = BooleanArray(n)
        val eps = 1e-6 * size + 1e-6
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = r[2 * i]; val ay = r[2 * i + 1]; val bx = r[2 * j]; val by = r[2 * j + 1]
            out[i] = (abs(ax) < eps && abs(bx) < eps) || (abs(ax - size) < eps && abs(bx - size) < eps) ||
                (abs(ay) < eps && abs(by) < eps) || (abs(ay - size) < eps && abs(by - size) < eps)
        }
        return out
    }

    /** A building's cast shadow on the ground: the footprint swept from its base to its top along the sun's shadow. */
    private fun shadow(b: Building, mesher: BuildingMesher, light: Light, mb: MeshBuilder, colour: Int) {
        val dxt = light.shadowDx(b.height.toFloat()); val dyt = light.shadowDy(b.height.toFloat())
        val dxb = light.shadowDx(b.minHeight.toFloat()); val dyb = light.shadowDy(b.minHeight.toFloat())
        val flat = mesher.lastFlat
        val tris = mesher.lastTriangles
        val z = 0.05f
        mb.begin(FaceMesh.CULL, 0f, 0f, 1f, b.centroidX.toFloat(), b.centroidY.toFloat(), 0f)
        // A convex footprint standing on the ground sweeps exactly its hull: a handful of triangles.
        if (b.rings.size == 1 && b.minHeight <= 0.5 && convex(b.outer)) {
            val n = flat.size / 2
            val pts = DoubleArray(n * 4)
            for (i in 0 until n) {
                pts[2 * i] = flat[2 * i]; pts[2 * i + 1] = flat[2 * i + 1]
                pts[2 * (n + i)] = flat[2 * i] + dxt; pts[2 * (n + i) + 1] = flat[2 * i + 1] + dyt
            }
            val hull = Shapes.convexHull(pts)
            val m = hull.size / 2
            if (m >= 3) {
                val first = mb.v(hull[0].toFloat(), hull[1].toFloat(), z, colour)
                var prev = mb.v(hull[2].toFloat(), hull[3].toFloat(), z, colour)
                for (k in 2 until m) {
                    val v = mb.v(hull[2 * k].toFloat(), hull[2 * k + 1].toFloat(), z, colour)
                    mb.tri(first, prev, v)
                    prev = v
                }
                mb.end()
                return
            }
        }
        val n = flat.size / 2
        val base = IntArray(n) { mb.v((flat[2 * it] + dxb).toFloat(), (flat[2 * it + 1] + dyb).toFloat(), z, colour) }
        val top = IntArray(n) { mb.v((flat[2 * it] + dxt).toFloat(), (flat[2 * it + 1] + dyt).toFloat(), z, colour) }
        // The footprint itself lies under the building: only a raised part needs its base drawn.
        val raised = b.minHeight > 0.5
        var k = 0
        while (k < tris.size) {
            if (raised) mb.tri(base[tris[k]], base[tris[k + 1]], base[tris[k + 2]])
            mb.tri(top[tris[k]], top[tris[k + 1]], top[tris[k + 2]])
            k += 3
        }
        // The sides of the sweep, ring by ring.
        var start = 0
        val holes = mesher.lastHoles
        val ringStarts = IntArray((holes?.size ?: 0) + 2)
        ringStarts[0] = 0
        holes?.forEachIndexed { i, h -> ringStarts[i + 1] = h }
        ringStarts[ringStarts.size - 1] = n
        for (ri in 0 until ringStarts.size - 1) {
            start = ringStarts[ri]
            val end = ringStarts[ri + 1]
            for (i in start until end) {
                val j = if (i + 1 < end) i + 1 else start
                mb.quad(base[i], base[j], top[j], top[i])
            }
        }
        mb.end()
    }

    /** Whether a ring turns the same way at every corner. */
    private fun convex(r: DoubleArray): Boolean {
        val n = r.size / 2
        if (n < 3) return false
        var sign = 0
        for (i in 0 until n) {
            val a = (i + n - 1) % n; val c = (i + 1) % n
            val cross = (r[2 * i] - r[2 * a]) * (r[2 * c + 1] - r[2 * i + 1]) - (r[2 * i + 1] - r[2 * a + 1]) * (r[2 * c] - r[2 * i])
            if (abs(cross) < 1e-9) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun waterPolygons(tile: VectorTile, frame: TileFrame): List<List<DoubleArray>> {
        val layer = tile["water"] ?: return emptyList()
        val out = ArrayList<List<DoubleArray>>()
        for (f in layer.features) {
            if (f.type != GeomType.POLYGON) continue
            out += frame.polygons(frame.geometry(f.geometry, layer.extent, 10.0), layer.extent)
        }
        return out
    }

    /** Kinds of buildings from what is in or around them: a church's point of interest, the land use under them. */
    private class KindHints(tile: VectorTile, private val frame: TileFrame) {
        private val pois = ArrayList<Triple<Double, Double, String>>()
        private val landuse = ArrayList<Pair<List<DoubleArray>, String>>()

        init {
            tile["poi"]?.let { layer ->
                for (f in layer.features) {
                    if (f.type != GeomType.POINT) continue
                    val kind = BuildingGuesser.kindOfPoi(f.string("class"), f.string("subclass")) ?: continue
                    val g = f.geometry
                    for (i in 0 until g.pointCount) {
                        pois += Triple(frame.x(g.x(i).toDouble(), layer.extent), frame.y(g.y(i).toDouble(), layer.extent), kind)
                    }
                }
            }
            tile["landuse"]?.let { layer ->
                for (f in layer.features) {
                    if (f.type != GeomType.POLYGON) continue
                    val kind = BuildingGuesser.kindOfLanduse(f.string("class")) ?: continue
                    for (poly in frame.polygons(frame.geometry(f.geometry, layer.extent, 40.0), layer.extent)) landuse += poly to kind
                }
            }
        }

        private val landBox: List<DoubleArray> = landuse.map { box(it.first[0]) }

        private fun box(r: DoubleArray): DoubleArray {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in 0 until r.size / 2) {
                if (r[2 * i] < x0) x0 = r[2 * i]
                if (r[2 * i] > x1) x1 = r[2 * i]
                if (r[2 * i + 1] < y0) y0 = r[2 * i + 1]
                if (r[2 * i + 1] > y1) y1 = r[2 * i + 1]
            }
            return doubleArrayOf(x0, y0, x1, y1)
        }

        fun kindAt(x: Double, y: Double, rings: List<DoubleArray>): String? {
            if (pois.isNotEmpty()) {
                val b = box(rings[0])
                for ((px, py, k) in pois) {
                    if (px < b[0] || px > b[2] || py < b[1] || py > b[3]) continue
                    if (Rings.contains(rings, px, py)) return k
                }
            }
            for (i in landuse.indices) {
                val b = landBox[i]
                if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) continue
                if (Rings.contains(landuse[i].first, x, y)) return landuse[i].second
            }
            return null
        }
    }
}

/** Stops a scene build whose thread was interrupted, clearing the interrupt as [InterruptedException] promises. */
internal fun checkCancelled() {
    if (Thread.interrupted()) throw InterruptedException("scene build cancelled")
}
