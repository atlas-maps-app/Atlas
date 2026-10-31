package app.atlas.packer.world

import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateSequence
import org.locationtech.jts.geom.CoordinateSequenceFilter
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.TopologyException
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.geom.util.LinearComponentExtracter
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.locationtech.jts.triangulate.polygon.PolygonHoleJoiner

/**
 * The world's geometry (ENGINE.md step 5): Web Mercator's tiles in their 256 pixels, as Planetiler and the land
 * polygons are read, and the grid of 1e-7° a format-3 tile stores ([Grid]), on which every cut is made exactly: JTS on
 * the grid's integers ([GRID]), so the pieces of a ring in neighbouring cells share their cut and a cell's land and sea
 * fill it exactly.
 */
object WorldGeometry {
    /** The grid's integers, as JTS's fixed precision. */
    val GRID = PrecisionModel(1.0)

    /** Floating-point geometry: tile pixels and Mercator's unit square. */
    val FLOAT = GeometryFactory()

    /** The grid's geometry. */
    val ON_GRID = GeometryFactory(GRID)

    /** Mercator's last latitude, where its square ends. */
    const val MAX_LAT = 85.05112877980659

    /** Mercator's x in its unit square (0 at 180° W) of longitude [lon]. */
    fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

    /** Mercator's y in its unit square (0 at the north edge) of latitude [lat], clamped to the square. */
    fun worldY(lat: Double): Double {
        val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return ((1.0 - Math.log(Math.tan(Math.PI / 4 + phi / 2)) / Math.PI) / 2.0).coerceIn(0.0, 1.0)
    }

    /** The unit square of Web Mercator's metres (EPSG:3857). */
    private const val HALF = 20037508.342789244

    fun worldOfMetres(mx: Double, my: Double): Coordinate = Coordinate((mx / HALF + 1.0) / 2.0, ((1.0 - my / HALF) / 2.0).coerceIn(0.0, 1.0))

    /** [g] (lon/lat degrees) in Mercator's unit square. */
    fun worldOfDegrees(g: Geometry): Geometry = transformed(g) { c -> c.x = worldX(c.x); c.y = worldY(c.y) }

    /** [g] (EPSG:3857 metres) in Mercator's unit square. */
    fun worldOfMetres(g: Geometry): Geometry = transformed(g) { c -> val w = worldOfMetres(c.x, c.y); c.x = w.x; c.y = w.y }

    /**
     * A source's polygon [g] in Mercator's square, valid: where it is not (Antarctica's land, glaciers and ice shelves
     * south of 85.05° S fold onto the square's edge; a ring of Natural Earth's 1:110m land crosses itself off Haida
     * Gwaii), mended (GeometryFixer: what it encloses kept) and counted in [mendedSources], so the cuts' overlays take
     * it.
     */
    fun valid(g: Geometry): Geometry {
        if (g.dimension < 2 || g.isValid) return g
        mendedSources.incrementAndGet()
        return GeometryFixer.fix(g)
    }

    /** The source polygons [valid] mended since the process started. */
    val mendedSources = java.util.concurrent.atomic.AtomicInteger()

    /** Tile ([z], [x], [y])'s envelope in the unit square, [margin] of its own pixels wider each way. */
    fun tileWorld(z: Int, x: Int, y: Int, margin: Double = 0.0): Envelope {
        val n = (1 shl z).toDouble()
        val m = margin / 256.0
        return Envelope((x - m) / n, (x + 1 + m) / n, (y - m) / n, (y + 1 + m) / n)
    }

    /** [g] (unit square) in tile ([z], [x], [y])'s pixels. */
    fun worldToPixels(g: Geometry, z: Int, x: Int, y: Int): Geometry {
        val n = (1 shl z).toDouble()
        return transformed(g) { c -> c.x = (c.x * n - x) * 256.0; c.y = (c.y * n - y) * 256.0 }
    }

    /** The grid longitude of pixel column [px] of tile column [x] at zoom [z]. */
    fun lonOfPixel(z: Int, x: Int, px: Double): Double = Grid.round((x + px / 256.0) / (1 shl z) * 360.0 - 180.0).toDouble()

    /** The grid latitude of pixel row [py] of tile row [y] at zoom [z]. */
    fun latOfPixel(z: Int, y: Int, py: Double): Double {
        val m = Math.PI * (1.0 - 2.0 * (y + py / 256.0) / (1 shl z))
        return Grid.round(Math.toDegrees(Math.atan(Math.sinh(m)))).toDouble()
    }

    /** The grid longitude of pixel column [px] of tile column [x] at zoom [z], before rounding. */
    private fun lonUnits(z: Int, x: Int, px: Double): Double = ((x + px / 256.0) / (1 shl z) * 360.0 - 180.0) * Grid.SCALE

    /** The grid latitude of pixel row [py] of tile row [y] at zoom [z], before rounding. */
    private fun latUnits(z: Int, y: Int, py: Double): Double = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + py / 256.0) / (1 shl z))))) * Grid.SCALE

    /**
     * The lattice of tile row [y] at zoom [z]: its steps in latitude and longitude, in the grid's units, each the largest
     * power of two no wider than 1/32 of a pixel anywhere in the row (a latitude's pixel the narrowest at the row's
     * poleward edge), half of Planetiler's own 4,096 units a tile or less: at L11 128 units of longitude, and of latitude
     * 128 to 53.4° and 64 from there to 72.6°.
     */
    fun lattice(z: Int, y: Int): IntArray {
        val lon = 360.0 * Grid.SCALE / 256.0 / (1 shl z)
        val poleward = maxOf(Math.abs(latUnits(z, y, 0.0)), Math.abs(latUnits(z, y, 256.0))) / Grid.SCALE
        val lat = lon * Math.cos(Math.toRadians(poleward))
        fun step(units: Double): Int = Integer.highestOneBit(maxOf(1.0, Math.floor(units / 32.0)).toInt())
        return intArrayOf(step(lat), step(lon))
    }

    /**
     * [g] (tile ([z], [x], [y])'s pixels) on the grid (x the longitude, y the latitude), made valid there, every vertex
     * on the tile row's [lattice]: a vertex moves by at most 1/64 of a pixel each way, half as far as Planetiler's own
     * rounding moved it, and the low bits of every step between vertices are the same, which zstd packs into almost
     * nothing (the Netherlands' world packs 30 % smaller). A polygon the lattice cannot take as it is (Planetiler's merged and simplified rings can
     * cross themselves) is mended first (GeometryFixer: what it encloses kept), and counted in [mended]. The cuts' new
     * vertices, where a ring crosses a cell's edge, lie on the grid but off the lattice.
     */
    fun pixelsToGrid(g: Geometry, z: Int, x: Int, y: Int): Geometry {
        val (qLat, qLon) = lattice(z, y).let { it[0].toDouble() to it[1].toDouble() }
        val t = transformed(g) { c -> c.x = lonUnits(z, x, c.x) / qLon; c.y = latUnits(z, y, c.y) / qLat }
        val onLattice = try {
            GeometryPrecisionReducer.reduce(t, GRID)
        } catch (e: RuntimeException) {
            // "Reduction failed, possible invalid input" (IllegalArgumentException), or OverlayNG's TopologyException
            if (e !is IllegalArgumentException && e !is TopologyException) throw e
            mended.incrementAndGet()
            GeometryPrecisionReducer.reduce(GeometryFixer.fix(t), GRID)
        }
        return transformed(onLattice) { c -> c.x *= qLon; c.y *= qLat }
    }

    /** The geometries [pixelsToGrid] mended since the process started. */
    val mended = java.util.concurrent.atomic.AtomicInteger()

    private fun transformed(g: Geometry, f: (Coordinate) -> Unit): Geometry {
        val copy = g.copy()
        copy.apply(object : CoordinateSequenceFilter {
            override fun filter(seq: CoordinateSequence, i: Int) {
                val c = Coordinate(seq.getX(i), seq.getY(i))
                f(c)
                seq.setOrdinate(i, 0, c.x)
                seq.setOrdinate(i, 1, c.y)
            }

            override fun isDone(): Boolean = false

            override fun isGeometryChanged(): Boolean = true
        })
        copy.geometryChanged()
        return copy
    }

    /**
     * The boxes of tile ([z], [x], [y])'s cells on the grid, [south, west, north, east] each, row by row from the
     * north-west, as Cells.cellOf files a point: a point inside a cell's box, off its east and south edges, is in it.
     */
    fun cellBoxes(z: Int, x: Int, y: Int): List<IntArray> {
        val n = Format.cellsPerSide(z)
        val b = Grid.tileBox(z, x, y)
        val w = b[1].toLong()
        val e = b[3].toLong()
        val s = b[0].toLong()
        val nn = b[2].toLong()
        fun col(k: Int): Int = (w + Math.floorDiv(k * (e - w) + n - 1, n.toLong())).toInt()
        fun row(r: Int): Int = (nn - Math.floorDiv(r * (nn - s) + n - 1, n.toLong())).toInt()
        val out = ArrayList<IntArray>(n * n)
        for (r in 0 until n) for (k in 0 until n) out += intArrayOf(row(r + 1), col(k), row(r), col(k + 1))
        return out
    }

    /** A grid box as a polygon. */
    fun rect(box: IntArray): Polygon {
        val s = box[0].toDouble()
        val w = box[1].toDouble()
        val n = box[2].toDouble()
        val e = box[3].toDouble()
        return ON_GRID.createPolygon(arrayOf(Coordinate(w, s), Coordinate(e, s), Coordinate(e, n), Coordinate(w, n), Coordinate(w, s)))
    }

    /** [a] ∩ [b] on the grid. */
    fun intersection(a: Geometry, b: Geometry): Geometry = OverlayNG.overlay(a, b, OverlayNG.INTERSECTION, GRID)

    /** [a] − [b] on the grid. */
    fun difference(a: Geometry, b: Geometry): Geometry = OverlayNG.overlay(a, b, OverlayNG.DIFFERENCE, GRID)

    /**
     * The polygons of [g] (on the grid) as rings: each polygon one ring, its holes joined to it by zero-width bridges
     * (the format's way, as Clip's pieces), turned as the scene's rings are (clockwise in longitude and latitude, so the
     * frame's signed area is positive), without its closing vertex; none left with no area.
     */
    fun rings(g: Geometry): List<Geom> {
        val out = ArrayList<Geom>()
        for (p in PolygonExtracter.getPolygons(g)) {
            val poly = p as Polygon
            if (poly.isEmpty) continue
            val coords: Array<Coordinate> = if (poly.numInteriorRing == 0) poly.exteriorRing.coordinates else joined(poly)
            ring(coords)?.let { out += it }
        }
        return out
    }

    /** [poly]'s shell and holes as one ring; if the joiner fails, the polygon is cut through its holes instead. */
    private fun joined(poly: Polygon): Array<Coordinate> {
        val c = runCatching { PolygonHoleJoiner.join(poly) }.getOrNull()
        if (c != null && c.size >= 4 && Math.abs(areaTwice(c)) == expectedTwice(poly)) return c
        throw HoleJoinException(poly)
    }

    /** Thrown when a polygon's holes cannot be joined to its shell exactly; [WorldCut] cuts it through its holes then. */
    class HoleJoinException(val polygon: Polygon) : RuntimeException("holes not joined")

    private fun expectedTwice(poly: Polygon): Long {
        var a = Math.abs(areaTwice(poly.exteriorRing.coordinates))
        for (i in 0 until poly.numInteriorRing) a -= Math.abs(areaTwice(poly.getInteriorRingN(i).coordinates))
        return a
    }

    /** Twice the signed area of a closed or open ring of grid integers, exactly (about its first vertex). */
    fun areaTwice(c: Array<Coordinate>): Long {
        if (c.size < 3) return 0
        val x0 = c[0].x.toLong()
        val y0 = c[0].y.toLong()
        var a = 0L
        for (i in c.indices) {
            val j = (i + 1) % c.size
            a += (c[i].x.toLong() - x0) * (c[j].y.toLong() - y0) - (c[j].x.toLong() - x0) * (c[i].y.toLong() - y0)
        }
        return a
    }

    /** Twice a ring's signed area in (longitude, latitude), exactly. */
    fun areaTwice(g: Geom): Long {
        if (g.size < 3) return 0
        val x0 = g.lon[0].toLong()
        val y0 = g.lat[0].toLong()
        var a = 0L
        for (i in 0 until g.size) {
            val j = (i + 1) % g.size
            a += (g.lon[i] - x0) * (g.lat[j] - y0) - (g.lon[j] - x0) * (g.lat[i] - y0)
        }
        return a
    }

    private fun ring(coords: Array<Coordinate>): Geom? {
        val lat = ArrayList<Int>(coords.size)
        val lon = ArrayList<Int>(coords.size)
        for (c in coords) {
            val la = c.y.toInt()
            val lo = c.x.toInt()
            if (lat.isNotEmpty() && lat.last() == la && lon.last() == lo) continue
            lat += la
            lon += lo
        }
        while (lat.size > 1 && lat.first() == lat.last() && lon.first() == lon.last()) {
            lat.removeAt(lat.size - 1)
            lon.removeAt(lon.size - 1)
        }
        if (lat.size < 3) return null
        var g = Geom(lat.toIntArray(), lon.toIntArray(), null)
        val a = areaTwice(g)
        if (a == 0L) return null
        if (a > 0) g = Geom(g.lat.reversedArray(), g.lon.reversedArray(), null)
        return g
    }

    /** The lines of [g] (on the grid), each as a line of its vertices, repeats dropped; none of fewer than two. */
    fun lines(g: Geometry): List<Geom> {
        val out = ArrayList<Geom>()
        for (l in LinearComponentExtracter.getLines(g)) {
            val c = (l as LineString).coordinates
            val lat = ArrayList<Int>(c.size)
            val lon = ArrayList<Int>(c.size)
            for (p in c) {
                if (lat.isNotEmpty() && lat.last() == p.y.toInt() && lon.last() == p.x.toInt()) continue
                lat += p.y.toInt()
                lon += p.x.toInt()
            }
            if (lat.size >= 2) out += Geom(lat.toIntArray(), lon.toIntArray(), null)
        }
        return out
    }
}
