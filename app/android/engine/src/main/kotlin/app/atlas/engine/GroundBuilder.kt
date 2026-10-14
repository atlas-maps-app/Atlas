package app.atlas.engine

import app.atlas.geo.Earcut
import app.atlas.geo.Mercator
import app.atlas.tiles.GeomType
import app.atlas.tiles.TileFeature
import app.atlas.tiles.TileLayer
import app.atlas.tiles.VectorTile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The ground of one scene, triangulated in draw order: earth, protected nature (from afar), land use and cover, far
 * building footprints, water (sunk below the quays, lighter along its banks, glinting close up), waterways, quay walls,
 * piers and squares, railways, roads (tunnels faded, casings, fills, bridges raised with deck edges), tram lines and
 * one-way hints. Everything is cut to the tile's square.
 */
internal class GroundBuilder(
    private val frame: TileFrame,
    private val theme: Theme,
    private val shading: Shading,
) {
    val mb = MeshBuilder(16384).also { it.clip = floatArrayOf(0f, 0f, frame.size.toFloat(), frame.size.toFloat()) }
    private val zoom = frame.zoom
    private val mpd = frame.metresPerDp
    private val pad = 60.0

    fun earth() {
        val s = frame.size.toFloat()
        mb.begin()
        val c = theme.earth
        mb.quad(mb.v(0f, 0f, 0f, c), mb.v(s, 0f, 0f, c), mb.v(s, s, 0f, c), mb.v(0f, s, 0f, c))
        mb.end()
    }

    /** Fills a polygon (outer ring, holes) at height [z]. */
    fun fill(rings: List<DoubleArray>, z: Float, colour: Int) {
        if (rings.isEmpty() || rings[0].size < 6) return
        var total = 0
        for (r in rings) total += r.size
        val flat = DoubleArray(total)
        val holes = if (rings.size > 1) IntArray(rings.size - 1) else null
        var o = 0
        for ((k, r) in rings.withIndex()) {
            if (k > 0) holes!![k - 1] = o / 2
            r.copyInto(flat, o)
            o += r.size
        }
        val tris = Earcut.triangulate(flat, holes)
        if (tris.isEmpty()) return
        mb.begin()
        mb.polygon(flat, tris, z, colour)
        mb.end()
    }

    /**
     * Fills a polygon like [fill], its triangles cut into smaller ones (each split evenly, so no edge is longer than
     * [maxEdge] metres): for colours the renderer computes per vertex from where it lands on the screen.
     */
    fun fillFine(rings: List<DoubleArray>, z: Float, colour: Int, maxEdge: Double) {
        if (rings.isEmpty() || rings[0].size < 6) return
        var total = 0
        for (r in rings) total += r.size
        val flat = DoubleArray(total)
        val holes = if (rings.size > 1) IntArray(rings.size - 1) else null
        var o = 0
        for ((k, r) in rings.withIndex()) {
            if (k > 0) holes!![k - 1] = o / 2
            r.copyInto(flat, o)
            o += r.size
        }
        val tris = Earcut.triangulate(flat, holes)
        if (tris.isEmpty()) return
        val s = frame.size
        var idx = IntArray(64)
        mb.begin()
        var k = 0
        while (k < tris.size) {
            val ax = flat[2 * tris[k]]; val ay = flat[2 * tris[k] + 1]
            val bx = flat[2 * tris[k + 1]]; val by = flat[2 * tris[k + 1] + 1]
            val cx = flat[2 * tris[k + 2]]; val cy = flat[2 * tris[k + 2] + 1]
            k += 3
            if ((ax < 0 && bx < 0 && cx < 0) || (ay < 0 && by < 0 && cy < 0) || (ax > s && bx > s && cx > s) || (ay > s && by > s && cy > s)) continue
            val longest = max(hypot(bx - ax, by - ay), max(hypot(cx - bx, cy - by), hypot(ax - cx, ay - cy)))
            val steps = min(24, max(1, kotlin.math.ceil(longest / maxEdge).toInt()))
            val count = (steps + 1) * (steps + 2) / 2
            if (mb.openVertexCount + count > FINE_FACE_VERTICES) { mb.end(); mb.begin() }
            if (idx.size < count) idx = IntArray(count)
            var m = 0
            for (i in 0..steps) for (j in 0..steps - i) {
                val u = i.toDouble() / steps; val v = j.toDouble() / steps; val w = 1 - u - v
                idx[m++] = mb.v((ax * w + bx * u + cx * v).toFloat(), (ay * w + by * u + cy * v).toFloat(), z, colour)
            }
            for (i in 0 until steps) for (j in 0 until steps - i) {
                val a = i * (steps + 1) - i * (i - 1) / 2 + j
                val b = (i + 1) * (steps + 1) - (i + 1) * i / 2 + j
                mb.tri(idx[a], idx[b], idx[a + 1])
                if (j < steps - i - 1) mb.tri(idx[b], idx[b + 1], idx[a + 1])
            }
        }
        mb.end()
    }

    private class Area(val rings: List<DoubleArray>, val colour: Int, val area: Double, val z: Float)

    /** The layer's polygons [colourOf] gives a colour, at the height [zOf] gives, largest first. */
    private fun areas(layer: TileLayer?, zOf: (TileFeature) -> Float = { 0f }, colourOf: (TileFeature) -> Int?): List<Area> {
        if (layer == null) return emptyList()
        val out = ArrayList<Area>()
        for (f in layer.features) {
            if (f.type != GeomType.POLYGON) continue
            val colour = colourOf(f) ?: continue
            val z = zOf(f)
            val g = frame.geometry(f.geometry, layer.extent, pad)
            for (poly in frame.polygons(g, layer.extent)) out += Area(poly, colour, abs(Rings.area(poly[0])), z)
        }
        // Large areas first, so small ones stay on top.
        out.sortByDescending { it.area }
        return out
    }

    /**
     * Land use and land cover: from afar, nature's protected areas as a tint, then the base land uses, cover (woods,
     * grass, sand) and the land uses drawn over it; the protected areas' outlines on top.
     */
    fun land(tile: VectorTile) {
        val parks = if (zoom < PARK_EDGE_ZOOM) areas(tile["park"]) { f -> if (isNature(f.string("class"))) theme.park else null } else emptyList()
        for (a in parks) fill(a.rings, 0f, a.colour)
        val landuse = tile["landuse"]
        for (a in areas(landuse) { f -> f.string("class")?.takeIf { it in BASE_LANDUSE }?.let { theme.land(it) } }) fill(a.rings, 0f, a.colour)
        for (a in areas(tile["landcover"]) { f -> theme.land(f.string("subclass")) ?: theme.land(f.string("class")) }) fill(a.rings, 0f, a.colour)
        for (a in areas(landuse) { f -> f.string("class")?.takeIf { it !in BASE_LANDUSE && it !in NOT_DRAWN }?.let { theme.land(it) } }) fill(a.rings, 0f, a.colour)
        // Airports: aprons and runways as areas, then runways and taxiways mapped as lines, at their usual widths.
        val aeroway = tile["aeroway"]
        for (a in areas(aeroway) { f ->
            when (f.string("class")) {
                "runway", "taxiway", "helipad" -> theme.runway
                "apron" -> theme.aeroway
                else -> null
            }
        }) fill(a.rings, 0f, a.colour)
        if (aeroway != null) {
            for (f in aeroway.features) {
                if (f.type != GeomType.LINE) continue
                val (w, minPx) = when (f.string("class")) {
                    "runway" -> 45.0 to 1.5
                    "taxiway" -> 18.0 to 0.6
                    else -> continue
                }
                val hw = (max(w, minPx * mpd) / 2).toFloat()
                val g = frame.geometry(f.geometry, aeroway.extent, pad)
                mb.begin()
                for (p in 0 until g.partCount) {
                    val xy = frame.line(g, p, aeroway.extent)
                    Ribbon.add(mb, xy, xy.size / 2, hw, theme.runway, z0 = 0f, capStart = false, capEnd = false, roundJoins = round)
                }
                mb.end()
            }
        }
        if (zoom < PARK_EDGE_ZOOM) parkEdges(parks)
    }

    /**
     * Whether a `park` layer class protects nature (a national park, a reserve, Natura 2000), not a townscape or an estate:
     * the canal ring of Amsterdam is a protected area too, and must not turn green.
     */
    private fun isNature(cls: String?): Boolean {
        if (cls == null) return false
        val c = cls.lowercase()
        return c in NATURE_PARKS || c.startsWith("natura") || c.startsWith("natuurnetwerk")
    }

    /** The outlines of protected areas: a thin line along each ring. */
    private fun parkEdges(parks: List<Area>) {
        val hw = (max(4.0, 0.7 * mpd) / 2).toFloat()
        for (a in parks) {
            mb.begin()
            for (r in a.rings) {
                val n = r.size / 2
                if (n < 3) continue
                val xy = FloatArray(2 * n + 2)
                for (i in 0 until 2 * n) xy[i] = r[i].toFloat()
                xy[2 * n] = xy[0]; xy[2 * n + 1] = xy[1]
                Ribbon.add(mb, xy, n + 1, hw, theme.parkEdge, z0 = 0f, capStart = false, capEnd = false, roundJoins = false)
            }
            mb.end()
        }
    }

    /** Buildings drawn flat from far away (or from tiles without heights), in the roofscape colour. */
    fun footprints(tile: VectorTile) {
        val layer = tile["building"] ?: return
        for (f in layer.features) {
            if (f.type != GeomType.POLYGON || f.bool("hide_3d") == true) continue
            val g = frame.geometry(f.geometry, layer.extent, pad)
            for (poly in frame.polygons(g, layer.extent)) {
                val c = f.string("colour")?.let { Colours.paint(it) }?.let { Colours.mix(theme.footprint, it, 0.35f) } ?: theme.footprint
                fill(poly, 0f, c)
            }
        }
    }

    /** Water sunk to [WATER_Z], waterways on it, and the quay walls along every bank. */
    fun water(tile: VectorTile) {
        val layer = tile["water"]
        val polys = ArrayList<List<DoubleArray>>()
        val colours = IntList()
        val open = ScreenWater.water(theme)
        if (layer != null) {
            for (f in layer.features) {
                if (f.type != GeomType.POLYGON || f.string("brunnel") == "tunnel") continue
                val colour = if (f.string("class") == "swimming_pool") Colours.mix(theme.water, Colours.WHITE, 0.45f) else open
                val g = frame.geometry(f.geometry, layer.extent, pad)
                for (poly in frame.polygons(g, layer.extent)) {
                    polys += poly
                    colours.add(colour)
                }
            }
        }
        // Edges shared by two water polygons lie inside the water: no bank there.
        val shared = HashMap<Long, Int>()
        if (theme.chart || zoom >= QUAY_ZOOM) {
            for (poly in polys) for (r in poly) forEdges(r) { ax, ay, bx, by -> shared.merge(edgeKey(ax, ay, bx, by), 1, Int::plus) }
        }
        val cuts by lazy(LazyThreadSafetyMode.NONE) { layer?.let { cutLines(it) } ?: (DoubleArray(0) to DoubleArray(0)) }
        if (theme.chart && polys.isNotEmpty()) {
            val shore = EdgeGrid(shoreEdges(polys, shared, cuts), SHORE_CELL)
            for (poly in polys) chartWater(poly, shore)
        } else {
            // Water coloured by the screen needs vertices close enough that its ramp never runs far past the view.
            val maxEdge = if (ScreenWater.on(theme)) max(RAMP_EDGE_METRES, RAMP_EDGE_DP * mpd) else 0.0
            for ((i, poly) in polys.withIndex()) {
                if (maxEdge > 0.0 && colours[i] == open) fillFine(poly, WATER_Z, colours[i], maxEdge) else fill(poly, WATER_Z, colours[i])
            }
        }
        waterways(tile)
        // Open water (not pools) gets the light band along its banks; its wind streaks are seeded once the roads are in.
        val openPolys = polys.filterIndexed { i, _ -> colours[i] == open }
        val wantBanks = theme.waterEdge != null && zoom >= QUAY_ZOOM
        val wantGlints = theme.glint != null && zoom >= GLINT_ZOOM
        if (!theme.chart && openPolys.isNotEmpty() && (wantBanks || wantGlints)) {
            val shore = shoreEdges(openPolys, shared, cuts)
            if (wantBanks) theme.waterEdge?.let { banks(shore, it) }
            if (wantGlints) { glintWater = openPolys; glintShore = shore }
        }
        // Below this zoom a quay is under a pixel tall: not worth its vertices.
        if (zoom >= QUAY_ZOOM) quays(polys, shared, cuts)
    }

    /**
     * The light band along the banks, on the water: from [edge] at the bank to the water's own colour a couple of metres
     * out, with a wedge round each corner so the band stays whole where the bank turns.
     */
    private fun banks(shore: DoubleArray, edge: Int) {
        val w = max(BANK_METRES, BANK_DP * mpd).toFloat()
        val z = WATER_Z + 0.01f
        val water = ScreenWater.water(theme)
        val n = shore.size / 4
        var prevNx = 0f; var prevNy = 0f; var prevBx = Double.NaN; var prevBy = Double.NaN
        for (e in 0 until n) {
            val ax = shore[4 * e]; val ay = shore[4 * e + 1]; val bx = shore[4 * e + 2]; val by = shore[4 * e + 3]
            val len = hypot(bx - ax, by - ay)
            if (len < 0.2) continue
            // Toward the water, as for the quays.
            val nx = (-(by - ay) / len).toFloat(); val ny = ((bx - ax) / len).toFloat()
            val x0 = ax.toFloat(); val y0 = ay.toFloat(); val x1 = bx.toFloat(); val y1 = by.toFloat()
            mb.begin()
            val a0 = mb.v(x0, y0, z, edge)
            val b0 = mb.v(x1, y1, z, edge)
            val b1 = mb.v(x1 + nx * w, y1 + ny * w, z, water)
            val a1 = mb.v(x0 + nx * w, y0 + ny * w, z, water)
            mb.quad(a0, b0, b1, a1)
            if (ax == prevBx && ay == prevBy) {
                val c = mb.v(x0, y0, z, edge)
                mb.tri(c, mb.v(x0 + prevNx * w, y0 + prevNy * w, z, water), mb.v(x0 + nx * w, y0 + ny * w, z, water))
            }
            mb.end()
            prevNx = nx; prevNy = ny; prevBx = bx; prevBy = by
        }
    }

    // What the wind streaks need: the open water and its banks, and what crosses or lies over the water.
    private var glintWater: List<List<DoubleArray>> = emptyList()
    private var glintShore = DoubleArray(0)
    private val crossings = DoubleList()
    private var crossingHalfWidth = 0.0
    private val decks = ArrayList<List<DoubleArray>>()

    /**
     * Seeds for the wind streaks ([GlintSet]): on a jittered grid anchored to the world (so tiles agree), about one per
     * [GLINT_SPACING_DP] square at the scene's zoom where [GLINT_KEEP] of them stay; each on open water, clear of the
     * banks, of every street, rail and bridge (which are drawn under the streaks) and of piers and decks, with how far
     * its streak may reach.
     */
    fun glints(): GlintSet {
        if (glintWater.isEmpty()) return GlintSet.EMPTY
        val shore = EdgeGrid(glintShore, SHORE_CELL)
        for (d in decks) for (r in d) forEdges(r) { ax, ay, bx, by -> crossings.add(ax); crossings.add(ay); crossings.add(bx); crossings.add(by) }
        val all = EdgeGrid(crossings.toArray(), SHORE_CELL)
        val size = frame.size
        val worldM = Mercator.CIRCUMFERENCE
        val k = worldM / frame.metresPerUnit // Mercator metres per scene metre
        val spacing = floor(GLINT_SPACING_DP * mpd * k * 2 + 0.5) / 2
        val limit = GLINT_REACH_DP * mpd
        val ox = frame.id.unitX * worldM
        val oy = frame.id.unitY * worldM
        val xs = FloatList(); val ys = FloatList(); val reach = FloatList(); val sizes = FloatList()
        for (poly in glintWater) {
            val outer = poly[0]
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in 0 until outer.size / 2) {
                x0 = min(x0, outer[2 * i]); x1 = max(x1, outer[2 * i]); y0 = min(y0, outer[2 * i + 1]); y1 = max(y1, outer[2 * i + 1])
            }
            x0 = max(x0, 0.0); y0 = max(y0, 0.0); x1 = min(x1, size); y1 = min(y1, size)
            if (x1 <= x0 || y1 <= y0) continue
            val r0 = floor((oy + y0 * k) / spacing).toLong(); val r1 = floor((oy + y1 * k) / spacing).toLong()
            val c0 = floor((ox + x0 * k) / spacing).toLong(); val c1 = floor((ox + x1 * k) / spacing).toLong()
            if ((r1 - r0 + 1) * (c1 - c0 + 1) > MAX_GLINT_CELLS) continue
            for (row in r0..r1) for (col in c0..c1) {
                val h = Hash(row * 83_492_791L xor col * 2_654_435_761L xor 0x61D7L)
                if (h.next() > GLINT_KEEP) continue
                val x = (col + h.range(0.1, 0.9)) * spacing / k - ox / k
                val y = (row + h.range(0.1, 0.9)) * spacing / k - oy / k
                if (x < 0 || y < 0 || x >= size || y >= size || !Rings.contains(poly, x, y)) continue
                if (decks.any { Rings.contains(it, x, y) }) continue
                var r = shore.distance(x, y, limit)
                if (r > 0.0) r = min(r, all.distance(x, y, r + crossingHalfWidth) - crossingHalfWidth)
                r -= GLINT_MARGIN_M
                if (r < GLINT_MIN_REACH_DP * mpd) continue
                xs.add(x.toFloat()); ys.add(y.toFloat()); reach.add(r.toFloat()); sizes.add(h.next().toFloat())
            }
        }
        return GlintSet(xs.size, xs.toArray(), ys.toArray(), WATER_Z + 0.02f, reach.toArray(), sizes.toArray())
    }

    /**
     * Where the tile (or the overzoom pre-clip) cut the layer's polygons, in scene metres: x lines then y lines.
     * Edges along them are not banks. The tile's own edges count too, for sources that cut without a buffer.
     */
    private fun cutLines(layer: TileLayer): Pair<DoubleArray, DoubleArray> {
        val e = layer.extent
        val b = TileFrame.layerBounds(layer)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        xs += frame.x(0.0, e); xs += frame.x(e.toDouble(), e)
        ys += frame.y(0.0, e); ys += frame.y(e.toDouble(), e)
        if (b[0] < 0) xs += frame.x(b[0].toDouble(), e)
        if (b[2] > e) xs += frame.x(b[2].toDouble(), e)
        if (b[1] < 0) ys += frame.y(b[1].toDouble(), e)
        if (b[3] > e) ys += frame.y(b[3].toDouble(), e)
        if (!frame.transform.isIdentity) {
            val r = frame.sourceRect(e, pad)
            xs += frame.x(r[0].toDouble(), e); xs += frame.x(r[2].toDouble(), e)
            ys += frame.y(r[1].toDouble(), e); ys += frame.y(r[3].toDouble(), e)
        }
        return xs.toDoubleArray() to ys.toDoubleArray()
    }

    /** The real banks of the water polygons, as edges (ax, ay, bx, by): no tile cuts, no edges between two polygons. */
    private fun shoreEdges(polys: List<List<DoubleArray>>, shared: Map<Long, Int>, cuts: Pair<DoubleArray, DoubleArray>): DoubleArray {
        var total = 0
        for (poly in polys) for (r in poly) total += r.size / 2
        val out = DoubleArray(total * 4)
        var n = 0
        for (poly in polys) for (r in poly) forEdges(r) { ax, ay, bx, by ->
            if ((shared[edgeKey(ax, ay, bx, by)] ?: 0) > 1 || isCutEdge(ax, ay, bx, by, cuts)) return@forEdges
            out[n++] = ax; out[n++] = ay; out[n++] = bx; out[n++] = by
        }
        return out.copyOf(n)
    }

    /** Whether edge a–b runs along one of the [cuts] lines ([cutLines]): a tile's cut, not a bank. */
    private fun isCutEdge(ax: Double, ay: Double, bx: Double, by: Double, cuts: Pair<DoubleArray, DoubleArray>): Boolean {
        val eps = 1e-6 * frame.size + 1e-6
        for (l in cuts.first) if (abs(ax - l) < eps && abs(bx - l) < eps) return true
        for (l in cuts.second) if (abs(ay - l) < eps && abs(by - l) < eps) return true
        return false
    }

    private fun waterways(tile: VectorTile) {
        val layer = tile["waterway"] ?: return
        for (f in layer.features) {
            if (f.type != GeomType.LINE || f.string("brunnel") == "tunnel") continue
            // On a chart the water polygons carry the depth shading; only small streams need lines.
            if (theme.chart && f.string("class") in setOf("river", "canal")) continue
            val w = when (f.string("class")) {
                "river" -> 16.0; "canal" -> 9.0; "stream" -> 3.0; "drain" -> 2.0; "ditch" -> 1.5; else -> 2.0
            }
            val hw = (max(w, (if (f.string("class") == "river") 1.4 else 0.6) * mpd) / 2).toFloat()
            val g = frame.geometry(f.geometry, layer.extent, pad)
            mb.begin()
            for (p in 0 until g.partCount) {
                val xy = frame.line(g, p, layer.extent)
                Ribbon.add(mb, xy, xy.size / 2, hw, ScreenWater.water(theme), z0 = WATER_Z)
            }
            mb.end()
        }
    }

    /**
     * Quay walls: each bank edge as a face that shows, on the far bank (facing the camera across the water), a wet foot
     * at the waterline, the brick wall and a light coping; seen from behind on the near bank, it covers the strip of
     * sunk water the bank hides in the bank's own colour.
     */
    private fun quays(polys: List<List<DoubleArray>>, count: Map<Long, Int>, cuts: Pair<DoubleArray, DoubleArray>) {
        if (polys.isEmpty()) return
        val front = theme.quayWall
        val top = theme.quayTop
        val foot = theme.quayFoot
        val back = theme.quayBack ?: top
        val footTop = if (foot != null) WATER_Z + QUAY_FOOT else WATER_Z
        for (poly in polys) for (r in poly) forEdges(r) { ax, ay, bx, by ->
            if ((count[edgeKey(ax, ay, bx, by)] ?: 0) > 1 || isCutEdge(ax, ay, bx, by, cuts)) return@forEdges
            val ex = bx - ax; val ey = by - ay
            val len = hypot(ex, ey)
            if (len < 0.4) return@forEdges
            // Toward the water.
            val nx = (-ey / len).toFloat(); val ny = (ex / len).toFloat()
            val mx = ((ax + bx) / 2).toFloat(); val my = ((ay + by) / 2).toFloat()
            mb.begin(FaceMesh.TWO_SIDED, nx, ny, 0f, mx, my, WATER_Z / 2, back = back)
            val sunny = shading.lambert(nx, ny, 0f) > 0f
            val wall = shading.shade(front, nx, ny, 0f, sunny)
            val coping = shading.shade(top, nx, ny, 0f, sunny)
            val x0 = ax.toFloat(); val y0 = ay.toFloat(); val x1 = bx.toFloat(); val y1 = by.toFloat()
            if (foot != null) band(x0, y0, x1, y1, WATER_Z, footTop, foot)
            band(x0, y0, x1, y1, footTop, COPING_FOOT, wall)
            band(x0, y0, x1, y1, COPING_FOOT, 0f, coping)
            mb.end()
        }
    }

    /** One upright band of a quay face, from [z0] to [z1], in [colour]. */
    private fun band(x0: Float, y0: Float, x1: Float, y1: Float, z0: Float, z1: Float, colour: Int) {
        mb.quad(mb.v(x0, y0, z0, colour), mb.v(x1, y1, z0, colour), mb.v(x1, y1, z1, colour), mb.v(x0, y0, z1, colour))
    }

    private inline fun forEdges(r: DoubleArray, block: (Double, Double, Double, Double) -> Unit) {
        val n = r.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            block(r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1])
        }
    }

    private fun edgeKey(ax: Double, ay: Double, bx: Double, by: Double): Long {
        val a = pointKey(ax, ay)
        val b = pointKey(bx, by)
        return if (a < b) a * 1_000_003L + b else b * 1_000_003L + a
    }

    private fun pointKey(x: Double, y: Double): Long = Math.round(x * 20) * 400_009L + Math.round(y * 20)

    /**
     * Chart water: the polygon cut into a mesh of small triangles, each corner coloured by how far it lies from the
     * nearest real bank ([shore]): shallow within 40 m, then 110 m, deep beyond 140 m, blending between. Faces close
     * every few thousand vertices, so the renderer can cull them piece by piece.
     */
    private fun chartWater(rings: List<DoubleArray>, shore: EdgeGrid) {
        // From far away the shoals are under a couple of pixels wide: plain water.
        if (mpd * 2 > DEEP_FROM) {
            fill(rings, WATER_Z, theme.water)
            return
        }
        var total = 0
        for (r in rings) total += r.size
        val flat = DoubleArray(total)
        val holes = if (rings.size > 1) IntArray(rings.size - 1) else null
        var o = 0
        for ((k, r) in rings.withIndex()) {
            if (k > 0) holes!![k - 1] = o / 2
            r.copyInto(flat, o)
            o += r.size
        }
        val tris = Earcut.triangulate(flat, holes)
        val shallow = theme.waterShallow ?: theme.water
        val shallow2 = theme.waterShallow2 ?: theme.water
        fun depthColour(x: Double, y: Double): Int {
            val d = shore.distance(x, y, DEEP_FROM)
            return when {
                d < 40 -> shallow
                d < 70 -> Colours.mix(shallow, shallow2, ((d - 40) / 30).toFloat())
                d < 110 -> shallow2
                d < DEEP_FROM -> Colours.mix(shallow2, theme.water, ((d - 110) / 30).toFloat())
                else -> theme.water
            }
        }
        val maxEdge = max(30.0, 2 * mpd)
        mb.begin()
        var k = 0
        while (k < tris.size) {
            val ax = flat[2 * tris[k]]; val ay = flat[2 * tris[k] + 1]
            val bx = flat[2 * tris[k + 1]]; val by = flat[2 * tris[k + 1] + 1]
            val cx = flat[2 * tris[k + 2]]; val cy = flat[2 * tris[k + 2] + 1]
            k += 3
            // Wholly outside the scene: the clip would drop it anyway.
            val s = frame.size
            if ((ax < 0 && bx < 0 && cx < 0) || (ay < 0 && by < 0 && cy < 0) || (ax > s && bx > s && cx > s) || (ay > s && by > s && cy > s)) continue
            val longest = max(hypot(bx - ax, by - ay), max(hypot(cx - bx, cy - by), hypot(ax - cx, ay - cy)))
            val steps = min(48, max(1, (longest / maxEdge).toInt() + 1))
            if (mb.openVertexCount + (steps + 1) * (steps + 2) / 2 > CHART_FACE_VERTICES) { mb.end(); mb.begin() }
            // Barycentric subdivision into steps² triangles.
            val idx = IntArray((steps + 1) * (steps + 2) / 2)
            var m = 0
            for (i in 0..steps) for (j in 0..steps - i) {
                val u = i.toDouble() / steps; val v = j.toDouble() / steps; val w = 1 - u - v
                val x = ax * w + bx * u + cx * v
                val y = ay * w + by * u + cy * v
                idx[m++] = mb.v(x.toFloat(), y.toFloat(), WATER_Z, depthColour(x, y))
            }
            fun at(i: Int, j: Int): Int = idx[i * (steps + 1) - i * (i - 1) / 2 + j]
            for (i in 0 until steps) for (j in 0 until steps - i) {
                mb.tri(at(i, j), at(i + 1, j), at(i, j + 1))
                if (j < steps - i - 1) mb.tri(at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
            }
        }
        mb.end()
    }

    /** Piers, squares, platforms and bridge decks drawn as areas. */
    fun transportAreas(tile: VectorTile) {
        val layer = tile["transportation"] ?: return
        // Piers and bridge decks stand a little above the street; squares and platforms lie on it.
        val raised = { f: TileFeature -> if (f.string("class") == "pier" || f.string("class") == "bridge") 0.25f else 0.02f }
        for (a in areas(layer, raised) { f ->
            if (f.string("brunnel") == "tunnel") return@areas null
            when (f.string("class")) {
                "pier" -> theme.pier
                "bridge" -> theme.bridgeArea
                "path" -> when (f.string("subclass")) {
                    "platform" -> theme.platform
                    else -> theme.pedestrianArea
                }
                else -> if (f.string("subclass") == "pedestrian") theme.pedestrianArea else null
            }
        }) {
            fill(a.rings, a.z, a.colour)
            if (a.colour == theme.pier || a.colour == theme.bridgeArea) decks += a.rings
        }
    }

    private class Road(
        val xy: FloatArray, val cls: String, val key: String, val width: Double, val minPx: Double,
        val bridge: Int, val tunnel: Boolean, val oneway: Int, val capStart: Boolean, val capEnd: Boolean,
    ) {
        val rank: Int get() = CLASS_RANK[cls] ?: 0
    }

    /** Railways, roads and paths, with tunnels, bridges and one-way hints. */
    fun roads(tile: VectorTile) {
        val layer = tile["transportation"] ?: return
        val bounds = TileFrame.layerBounds(layer)
        val preRect = if (frame.transform.isIdentity) null else frame.sourceRect(layer.extent, pad)
        val roads = ArrayList<Road>()
        val rails = ArrayList<Road>()
        val trams = ArrayList<Road>()
        for (f in layer.features) {
            if (f.type != GeomType.LINE) continue
            val cls = f.string("class") ?: continue
            val sub = f.string("subclass")
            val brunnel = f.string("brunnel")
            val layerNo = f.int("layer") ?: 0
            val tunnel = brunnel == "tunnel" || layerNo < 0
            val bridge = if (brunnel == "bridge") max(1, layerNo) else 0
            val oneway = f.int("oneway") ?: 0
            val g0 = f.geometry
            val g = frame.geometry(g0, layer.extent, pad)
            for (p in 0 until g.partCount) {
                val xy = simplify(frame.line(g, p, layer.extent), 0.25 * mpd)
                if (xy.size < 4) continue
                val s = g.partStart(p); val e = g.partEnd(p) - 1
                val capS = !isCut(g.x(s), g.y(s), bounds, preRect, layer.extent)
                val capE = !isCut(g.x(e), g.y(e), bounds, preRect, layer.extent)
                when (cls) {
                    "rail" -> rails += Road(xy, cls, "rail", if (sub == "narrow_gauge") 2.2 else 3.2, 0.7, bridge, tunnel, 0, capS, capE)
                    "transit" -> when (sub) {
                        "tram", "light_rail" -> trams += Road(xy, cls, sub, 1.44, 0.0, bridge, tunnel, 0, capS, capE)
                        "subway" -> if (!tunnel) rails += Road(xy, cls, "rail", 3.0, 0.5, bridge, false, 0, capS, capE)
                        else -> rails += Road(xy, cls, "rail", 2.6, 0.5, bridge, tunnel, 0, capS, capE)
                    }
                    "ferry", "aerialway" -> {}
                    else -> {
                        if (cls.endsWith("_construction")) continue
                        // Far out, paths and service roads are below a pixel: leave them out.
                        if (zoom < 14 && cls in setOf("path", "track", "service")) continue
                        val key = if (cls == "path") (sub ?: "path") else cls
                        val (w, minPx) = width(cls, sub, f.string("service"))
                        roads += Road(xy, cls, key, w, minPx, bridge, tunnel, oneway, capS, capE)
                    }
                }
            }
        }
        // Everything on the ground the wind streaks must keep clear of: drawn under them.
        for (r in roads) if (!r.tunnel) crossing(r)
        for (r in rails) if (!r.tunnel) crossing(r)
        for (r in trams) if (!r.tunnel) crossing(r)
        // Tunnels: faded, under everything else.
        for (r in roads.filter { it.tunnel }.sortedBy { it.rank }) {
            val paint = theme.road(r.key)
            ribbon(r, halfWidth(r), Colours.mix(paint.fill, theme.earth, 0.6f), 0.0f)
        }
        for (r in rails.filter { it.bridge == 0 && !it.tunnel }) rail(r, 0f)
        val ground = roads.filter { it.bridge == 0 && !it.tunnel }.sortedBy { it.rank }
        for (r in ground) theme.road(r.key).casing?.let { ribbon(r, halfWidth(r) + casing(), it, 0.0f) }
        for (r in ground) ribbon(r, halfWidth(r), theme.road(r.key).fill, 0.0f)
        for (t in trams.filter { it.bridge == 0 && !it.tunnel }) tram(t, null)
        if (zoom >= 17) for (r in ground) if (r.oneway != 0 && r.rank >= CLASS_RANK["service"]!!) arrows(r, null)
        // Bridges, layer by layer.
        val bridgeLayers = (roads.map { it.bridge } + rails.map { it.bridge } + trams.map { it.bridge }).filter { it > 0 }.distinct().sorted()
        for (layerNo in bridgeLayers) {
            val br = roads.filter { it.bridge == layerNo }.sortedBy { it.rank }
            val brRails = rails.filter { it.bridge == layerNo }
            val profiles = HashMap<Any, FloatArray>()
            for (r in br) profiles[r] = profile(r, layerNo)
            for (r in brRails) profiles[r] = profile(r, layerNo)
            for (r in br) deckSides(r, halfWidth(r) + casing(), profiles[r]!!)
            for (r in brRails) deckSides(r, halfWidth(r), profiles[r]!!)
            for (r in br) theme.road(r.key).casing?.let { ribbon(r, halfWidth(r) + casing(), it, 0f, profiles[r]) }
            for (r in br) ribbon(r, halfWidth(r), theme.road(r.key).fill, 0f, profiles[r])
            for (r in brRails) rail(r, 0f, profiles[r])
            for (t in trams.filter { it.bridge == layerNo }) tram(t, profile(t, layerNo))
            if (zoom >= 17) for (r in br) if (r.oneway != 0 && r.rank >= CLASS_RANK["service"]!!) arrows(r, profiles[r])
        }
    }

    /** Notes [r]'s centre line (and its half width, casing included) for the wind streaks to keep clear of. */
    private fun crossing(r: Road) {
        val n = r.xy.size / 2
        for (i in 0 until n - 1) {
            crossings.add(r.xy[2 * i].toDouble()); crossings.add(r.xy[2 * i + 1].toDouble())
            crossings.add(r.xy[2 * i + 2].toDouble()); crossings.add(r.xy[2 * i + 3].toDouble())
        }
        crossingHalfWidth = max(crossingHalfWidth, (halfWidth(r) + casing()).toDouble())
    }

    /** A polyline with points closer than [tolerance] metres to their neighbours' line dropped (Douglas–Peucker). */
    private fun simplify(xy: FloatArray, tolerance: Double): FloatArray {
        val n = xy.size / 2
        if (n <= 2 || tolerance <= 0.0) return xy
        val coords = DoubleArray(xy.size) { xy[it].toDouble() }
        val keep = app.atlas.geo.Shapes.simplifyIndices(coords, tolerance)
        if (keep.size == n) return xy
        val out = FloatArray(keep.size * 2)
        for ((i, k) in keep.withIndex()) { out[2 * i] = xy[2 * k]; out[2 * i + 1] = xy[2 * k + 1] }
        return out
    }

    /** True when a line end lies where the tile (or our pre-clip) cut it, not where the road really ends. */
    private fun isCut(x: Int, y: Int, bounds: IntArray, pre: IntArray?, extent: Int): Boolean {
        if ((x <= bounds[0] && bounds[0] < 0) || (x >= bounds[2] && bounds[2] > extent) ||
            (y <= bounds[1] && bounds[1] < 0) || (y >= bounds[3] && bounds[3] > extent)
        ) return true
        if (pre != null && (x <= pre[0] || x >= pre[2] || y <= pre[1] || y >= pre[3])) return true
        return false
    }

    /**
     * A street's width in metres (the living map's widths by kind: big roads by two lanes and their edges, the rest as
     * the canal town's streets are) and the fewest dp it is drawn, so it never vanishes from afar.
     */
    private fun width(cls: String, sub: String?, service: String?): Pair<Double, Double> = when (cls) {
        "motorway" -> 11.0 to 1.7
        "trunk" -> 9.0 to 1.5
        "primary" -> 8.0 to 1.3
        "secondary" -> 9.0 to 1.1
        "tertiary" -> 7.5 to 0.9
        "busway", "bus_guideway" -> 6.0 to 0.6
        "minor", "raceway" -> 5.5 to 0.55
        "service" -> (if (service == "parking_aisle" || service == "driveway") 3.0 else 3.5) to 0.3
        "track" -> 3.5 to 0.25
        "path" -> when (sub) {
            "pedestrian" -> 5.0 to 0.3
            "cycleway" -> 2.4 to 0.2
            "footway", "sidewalk", "crossing", "steps", "bridleway" -> 2.0 to 0.2
            "platform" -> 3.0 to 0.2
            else -> 1.6 to 0.2
        }
        else -> 4.0 to 0.2
    }

    private fun halfWidth(r: Road): Float = (max(r.width, r.minPx * mpd) / 2).toFloat()

    private fun casing(): Float = max(0.6, 0.45 * mpd).toFloat()

    /** Round joins and caps only where they show: from zoom 14 up. */
    private val round = zoom >= 14

    /** A road as a ribbon; a bridge (with heights [z]) ends square, so its casing never arcs over the street it joins. */
    private fun ribbon(r: Road, hw: Float, colour: Int, z0: Float, z: FloatArray? = null) {
        val caps = round && z == null
        mb.begin()
        Ribbon.add(mb, r.xy, r.xy.size / 2, hw, colour, z, z0, r.capStart && caps, r.capEnd && caps, roundJoins = round)
        mb.end()
    }

    /** A railway: its ballast bed, then two rails at gauge, drawn [RAIL_DP] wide however far away they are. */
    private fun rail(r: Road, z0: Float, z: FloatArray? = null) {
        val hw = halfWidth(r)
        val n = r.xy.size / 2
        mb.begin()
        Ribbon.add(mb, r.xy, n, hw, theme.rail, z, z0 + 0.03f, r.capStart, r.capEnd)
        mb.end()
        if (zoom < 14) return
        rails(r.xy, n, (RAIL_DP / 2 * mpd).toFloat(), theme.railTie, z, z0 + 0.05f)
    }

    /** Tram rails: two lines either side of the centre, at gauge 1.435 m, [TRAM_DP] wide. */
    private fun tram(t: Road, z: FloatArray?) {
        if (zoom < 14) return
        rails(t.xy, t.xy.size / 2, (TRAM_DP / 2 * mpd).toFloat(), theme.tram, z, 0.06f)
    }

    private fun rails(xy: FloatArray, n: Int, hw: Float, colour: Int, z: FloatArray?, z0: Float) {
        for (side in arrayOf(-0.72f, 0.72f)) {
            val off = offsetLine(xy, n, side)
            mb.begin()
            Ribbon.add(mb, off, n, max(0.05f, hw), colour, z?.let { lift(it, z0) }, z0, false, false, roundJoins = false)
            mb.end()
        }
    }

    private fun arrows(r: Road, z: FloatArray?) {
        val n = r.xy.size / 2
        val len = Ribbon.length(r.xy, n)
        if (len < 20f) return
        val size = (halfWidth(r) * 1.1f).coerceIn(1.6f, 4.0f)
        mb.begin()
        Ribbon.arrows(mb, r.xy, n, 28f, size, theme.onewayArrow, (z?.average()?.toFloat() ?: 0f) + 0.04f, r.oneway < 0)
        mb.end()
    }

    /** Heights along a bridge: up to layer × 5 m, held to 12% of its length, with ramps at its real ends. */
    private fun profile(r: Road, layerNo: Int): FloatArray {
        val n = r.xy.size / 2
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(r.xy[2 * i] - r.xy[2 * i - 2], r.xy[2 * i + 1] - r.xy[2 * i - 1])
        val len = cum[n - 1]
        val h = min(layerNo * 5f, max(0.6f, len * 0.12f))
        val ramp = min(len / 2, max(h * 6f, 8f))
        val z = FloatArray(n)
        for (i in 0 until n) {
            var k = 1f
            if (r.capStart) k = min(k, smooth(cum[i] / ramp))
            if (r.capEnd) k = min(k, smooth((len - cum[i]) / ramp))
            z[i] = 0.3f + h * k
        }
        return z
    }

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }

    private fun lift(z: FloatArray, d: Float) = FloatArray(z.size) { z[it] + d }

    /** The vertical edges of a raised deck: faces along both sides, shown from the side they face. */
    private fun deckSides(r: Road, hw: Float, z: FloatArray) {
        val n = r.xy.size / 2
        val depth = 0.9f
        for (side in arrayOf(1f, -1f)) {
            val off = offsetLine(r.xy, n, hw * side)
            for (i in 0 until n - 1) {
                val ax = off[2 * i]; val ay = off[2 * i + 1]
                val bx = off[2 * i + 2]; val by = off[2 * i + 3]
                val ex = bx - ax; val ey = by - ay
                val len = sqrt(ex * ex + ey * ey)
                if (len < 0.05f || (z[i] < 0.5f && z[i + 1] < 0.5f)) continue
                // Outward: the left normal (−ey, ex) for the +1 side.
                val nx = -ey / len * side; val ny = ex / len * side
                mb.begin(FaceMesh.CULL, nx, ny, 0f, (ax + bx) / 2, (ay + by) / 2, (z[i] + z[i + 1]) / 2 - depth / 2)
                val col = shading.shade(theme.bridgeSide, nx, ny, 0f, shading.lambert(nx, ny, 0f) > 0f)
                val a0 = mb.v(ax, ay, z[i] - depth, col)
                val b0 = mb.v(bx, by, z[i + 1] - depth, col)
                val b1 = mb.v(bx, by, z[i + 1], col)
                val a1 = mb.v(ax, ay, z[i], col)
                mb.quad(a0, b0, b1, a1)
                mb.end()
            }
        }
    }

    /** The polyline moved sideways by [d] metres (to its left for positive d), corners mitred (at most twice). */
    private fun offsetLine(xy: FloatArray, n: Int, d: Float): FloatArray {
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            var nx = 0f; var ny = 0f
            var count = 0
            if (i > 0) {
                val dx = xy[2 * i] - xy[2 * i - 2]; val dy = xy[2 * i + 1] - xy[2 * i - 1]
                val l = sqrt(dx * dx + dy * dy)
                if (l > 1e-5f) { nx += -dy / l; ny += dx / l; count++ }
            }
            if (i < n - 1) {
                val dx = xy[2 * i + 2] - xy[2 * i]; val dy = xy[2 * i + 3] - xy[2 * i + 1]
                val l = sqrt(dx * dx + dy * dy)
                if (l > 1e-5f) { nx += -dy / l; ny += dx / l; count++ }
            }
            val l = sqrt(nx * nx + ny * ny)
            if (l > 1e-5f) {
                // Two unit normals sum to 2·cos(half the turn): the mitre is 1 / cos(half the turn) long.
                val k = if (count == 2) min(2f, 2f / l) else 1f
                nx = nx / l * k; ny = ny / l * k
            }
            out[2 * i] = xy[2 * i] + nx * d
            out[2 * i + 1] = xy[2 * i + 1] + ny * d
        }
        return out
    }

    fun build(): FaceMesh = mb.build()

    companion object {
        /** Water lies this far below the quays (metres): the canal town's generic waterline. */
        const val WATER_Z = -1.5f

        /** A quay's wet foot is this tall (metres); its coping starts this far below the street. */
        private const val QUAY_FOOT = 0.35f
        private const val COPING_FOOT = -0.18f

        /** Water coloured by the screen is cut into triangles no longer than this: metres, or dp when that is more. */
        private const val RAMP_EDGE_METRES = 30.0
        private const val RAMP_EDGE_DP = 60.0
        private const val FINE_FACE_VERTICES = 4096

        /** The bank band is this wide: metres, or density-independent pixels when that is more. */
        private const val BANK_METRES = 2.2
        private const val BANK_DP = 1.3

        /** Streaks from this scene zoom, about one per [GLINT_SPACING_DP] square where [GLINT_KEEP] of them stay. */
        const val GLINT_ZOOM = 13
        private const val GLINT_SPACING_DP = 11.0
        private const val GLINT_KEEP = 0.65
        private const val MAX_GLINT_CELLS = 40_000L
        /** A streak reaches at most this far (dp at the scene's zoom), stays this far off the banks, and needs this room. */
        private const val GLINT_REACH_DP = 24.0
        private const val GLINT_MARGIN_M = 1.0
        private const val GLINT_MIN_REACH_DP = 3.0



        /** Protected areas show (tint and outline) below this scene zoom; closer, their land cover speaks for them. */
        const val PARK_EDGE_ZOOM = 14

        /** `park` classes drawn as nature (with the Natura 2000 and national network ones, whatever their spelling). */
        val NATURE_PARKS = setOf("national_park", "nature_reserve", "wildlife_refuge", "conservation", "biosphere reserve")

        /** Rails and tram rails are drawn this wide (dp), as fine lines at every distance. */
        private const val RAIL_DP = 0.7
        private const val TRAM_DP = 0.9

        /** Quay walls from this scene zoom: further out they are under a pixel tall. */
        const val QUAY_ZOOM = 15

        /** Chart water is deep this far from the bank (metres). */
        private const val DEEP_FROM = 140.0
        private const val SHORE_CELL = 70.0
        private const val CHART_FACE_VERTICES = 4096

        /** Land uses drawn first, under the land cover; residential only where the theme gives it a colour. */
        val BASE_LANDUSE = setOf("residential", "commercial", "industrial", "retail", "railway", "military", "garages", "bus_station", "quarry", "dam")
        val NOT_DRAWN = setOf("suburb", "quarter", "neighbourhood", "city", "town", "village")

        val CLASS_RANK = mapOf(
            "path" to 0, "track" to 1, "service" to 2, "raceway" to 3, "busway" to 3, "bus_guideway" to 3, "minor" to 4,
            "tertiary" to 5, "secondary" to 6, "primary" to 7, "trunk" to 8, "motorway" to 9,
        )
    }
}
