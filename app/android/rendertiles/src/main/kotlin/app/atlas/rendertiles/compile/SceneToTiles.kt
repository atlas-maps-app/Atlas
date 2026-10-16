package app.atlas.rendertiles.compile

import app.atlas.render.Amsterdam
import app.atlas.render.PoiStyle
import app.atlas.render.PyRe
import app.atlas.render.Real
import app.atlas.render.V2
import app.atlas.render.data.PackReader
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveScene
import app.atlas.render.live.Lod
import app.atlas.render.py.Dict
import app.atlas.render.py.PyCompat
import app.atlas.render.py.PyMath
import app.atlas.render.py.truthy
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleTables
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Stage P (plan-vector D2): a scene the port loaded ([LiveScene], its `S`), written as render tiles at [levels].
 *
 * Every record of the scene becomes a feature with the values the loader derived, field by field ([Schema]); geometry
 * goes onto OSM's grid exactly ([Grid]: a vertex that is not a grid point of the scene's frame fails the build, so a
 * tile never holds a vertex the oracle does not); buildings, parts and areas get keep codes ([KeepCodes]); land cover
 * and water are clipped at the tiles' edges ([Clip]); the ground gets its slots in the renders' order ([GroundKeys]);
 * quay edges are `water_edges`' own ([Amsterdam.waterEdges]).
 *
 * [variants] are the same region loaded whole at other tolerances of the ladder: where a building's or part's derived
 * values differ there (the loader reads some looks off the simplified footprint), a [Layer.VARIANT] feature carries
 * them for that tolerance.
 *
 * L14 holds everything. L12 holds the ground and the water at the ladder's 4 m (`simplify` at [COARSE], with its
 * exceptions) and the label candidates: blocks of buildings, roads by class and the rest of a coarse level's
 * generalisation come with K21.
 *
 * What a scene holds that the tiles cannot yet carry (a theme park's coasters and woods, terrain, the relief) fails the
 * build with its name, rather than being left out unnoticed.
 */
class SceneToTiles(
    private val scene: LiveScene,
    private val variants: List<Variant> = emptyList(),
    private val levels: IntArray = Format.LEVELS,
) {
    /**
     * A building or part whose attributes differ when the region is loaded at another [tolerance] of the ladder: its
     * [layer], source [index], and every attribute as that load derives it ([Schema.BUILDING_ATTRIBUTES], canonical).
     */
    class Variant(val tolerance: Double, val layer: Layer, val index: Int, val attributes: Map<String, Any?>)

    private val S: Dict = scene.S
    private val region: LiveRegion = scene.region

    // the frame the scene was loaded in: the port's module state after its load
    val lat0: Double
    val lon0: Double
    val kx: Double
    val kz: Double

    init {
        scene.state.restore()
        lat0 = Amsterdam.LAT0
        lon0 = Amsterdam.LON0
        kx = Amsterdam.KX
        kz = Amsterdam.KZ
        val f = region.frame
        require(f.lat0 == lat0 && f.lon0 == lon0 && f.kx.toRawBits() == kx.toRawBits() && f.kz == kz) { "the scene's frame ($lat0, $lon0, $kx, $kz) is not its region's ${region.frame}" }
        require(scene.tolerance == 0.0) { "render tiles are written from a scene loaded at tolerance 0, not ${scene.tolerance}" }
        for (v in variants) require(Keep.indexOf(v.tolerance) > 0) { "a variant at ${v.tolerance}, which is not on the keep ladder" }
        unsupported()
    }

    /** What a scene can hold that the tiles do not carry yet: the build fails rather than drop it. */
    private fun unsupported() {
        val later = mapOf(
            "coasters" to "a theme park's coaster tracks (K15)", "woods" to "a park's planted woods (K15)", "dem" to "a survey's heights (K28)",
            "_terrain" to "a region drawn on its terrain (K28)", "_flat" to "the relief (K28)", "_lines" to "lines draped on terrain (K28)",
            "_grid" to "a terrain grid (K28)", "gantries" to "gantries (K15)", "conveyors" to "conveyors (K15)",
        )
        val found = later.filterKeys { k -> truthy(S[k]) }
        if (found.isNotEmpty()) throw UnsupportedOperationException("${region.id}: render tiles cannot carry ${found.values.joinToString()} yet")
    }

    // ------------------------------------------------------------------ the grid

    private fun lat(z: Double, what: () -> String): Int = Grid.latOf(z, lat0, kz) ?: throw IllegalStateException("${what()}: z = $z is not on the grid of ${region.id}'s frame")
    private fun lon(x: Double, what: () -> String): Int = Grid.lonOf(x, lon0, kx) ?: throw IllegalStateException("${what()}: x = $x is not on the grid of ${region.id}'s frame")

    private fun geom(pts: List<V2>, what: () -> String): Geom {
        val a = IntArray(pts.size)
        val o = IntArray(pts.size)
        for (i in pts.indices) {
            a[i] = lat(pts[i].y, what)
            o[i] = lon(pts[i].x, what)
        }
        return Geom(a, o, null)
    }

    private fun point(x: Double, z: Double, what: () -> String) = GridPoint(lat(z, what), lon(x, what))

    /** A record's ring or line ([key], `poly` by default) as the scene holds it: a list of points. */
    internal fun points(d: Map<String, Any?>, key: String = "poly"): List<V2> {
        val v = d[key] as? List<*> ?: throw IllegalStateException("a record without its $key")
        @Suppress("UNCHECKED_CAST")
        if (v.all { it is V2 }) return v as List<V2>
        throw IllegalStateException("a record's $key holds something other than points")
    }

    /** A point in the frame's metres, rounded onto the grid: only for choosing a tile and a cell. */
    private fun nearest(x: Double, z: Double): GridPoint = GridPoint(Grid.round(lat0 - z / kz), Grid.round(lon0 + x / kx))

    // ------------------------------------------------------------------ the scene's lists

    @Suppress("UNCHECKED_CAST")
    private fun dicts(key: String): List<Dict> = (S[key] as List<Dict>?) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun rings(key: String): List<List<V2>> = (S[key] as List<List<V2>>?) ?: emptyList()

    private val buildings = dicts("buildings")
    private val parts = dicts("parts")
    private val nodes = dicts("nodes")
    private val trees = dicts("trees")
    private val water = rings("water")
    private val holes = rings("water_holes")

    private val nodeIndex = IdentityHashMap<Any, Int>().also { m -> nodes.forEachIndexed { i, n -> m[n] = i } }
    private val waterIndex = IdentityHashMap<Any, Int>().also { m -> water.forEachIndexed { i, r -> m[r] = i } }
    private val pois: Set<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> s.addAll(dicts("pois")) }
    private val treeSet: Set<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> s.addAll(trees) }

    /** `S['water_edges']` as `render` makes them: the quays. */
    internal val edges: List<Amsterdam.Edge> = Amsterdam.waterEdges(S)

    // ------------------------------------------------------------------ features

    /** A feature before it is placed: its layer, fields, reference point, extent, top, and order among its layer's features. */
    private class Pending(val layer: Layer, val fields: Map<String, Any?>, val ref: GridPoint, val box: IntArray, val heightCm: Int, val order: Long)

    /** Every tile of the scene at [levels]. */
    fun tiles(): List<RenderTile> {
        val out = ArrayList<RenderTile>()
        for (z in levels) {
            val byTile = LinkedHashMap<Cells.TileKey, MutableList<Feature>>()
            fun put(p: Pending, z: Int) {
                val key = Cells.tileOf(z, p.ref.lat, p.ref.lon)
                byTile.getOrPut(key) { ArrayList() }.add(Feature(p.layer, Schema.canonical(p.layer, p.fields), Cells.cellOf(key, p.ref.lat, p.ref.lon), p.box, p.heightCm, p.order))
            }
            val full = z >= 14
            val placed = HashMap<Pair<Layer, Int>, GridPoint>()
            if (full) {
                for ((layer, list) in listOf(Layer.BUILDING to buildings, Layer.PART to parts)) {
                    for ((i, b) in list.withIndex()) {
                        val p = building(layer, i, b)
                        placed[layer to i] = p.ref
                        put(p, z)
                    }
                }
                for (p in variantFeatures(placed)) put(p, z)
                for (p in quays()) put(p, z)
                for (p in treeFeatures()) put(p, z)
                for (p in thingFeatures()) put(p, z)
                for (p in itemFeatures()) put(p, z)
                for (p in roadFeatures()) put(p, z)
                for (p in lineFeatures()) put(p, z)
            }
            // a coarse level takes the ground at the ladder's far tolerance; blocks, roads by class and the rest of a
            // coarse level's generalisation come with K21
            val coarse = if (full) 0 else Keep.indexOf(COARSE)
            for (p in areaFeatures(coarse)) put(p, z)
            for (p in Labels.features(this)) put(p.toPending(), z)
            // clipped fills: one piece per ring and tile it crosses
            for ((key, fs) in clipped(z, coarse)) byTile.getOrPut(key) { ArrayList() }.addAll(fs)
            for ((key, fs) in byTile) out += RenderTile.of(key.z, key.x, key.y, fs)
        }
        return out
    }

    // ------------------------------------------------------------------ buildings and parts

    private fun building(layer: Layer, i: Int, b: Dict): Pending {
        val poly = points(b)
        val what = { "${layer.label} $i (${b["id"]})" }
        val ring = geom(poly, what).withKeep(KeepCodes.of(poly))
        val fields = LinkedHashMap<String, Any?>()
        fields["index"] = i
        fields["id"] = (b["id"] as Number).toLong()
        fields["ring"] = ring
        fields.putAll(attributes(layer, b, Amsterdam.nearWater(edges, poly, 16.0)))
        val c = Real.centroid(poly)
        val h = (b["h"] as? Double) ?: 0.0
        return Pending(layer, fields, nearest(c.x, c.y), ring.box(), cm(h), i.toLong())
    }

    /** A building's or part's attributes ([Schema.BUILDING_ATTRIBUTES]): every field the loader left, typed as the schema codes it. */
    fun attributes(layer: Layer, b: Dict, nearWater: Boolean): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val extra = LinkedHashMap<String, Any?>()
        val cols = Schema.BUILDING_ATTRIBUTES.associateBy { it.name }
        @Suppress("UNCHECKED_CAST")
        val tags = (b["tags"] as Map<String, Any?>?) ?: emptyMap()
        for ((k, v) in b) {
            when (k) {
                "poly", "id" -> {}
                "_bb" -> {} // the box of the tolerance-0 ring: the ring's own, derived again by a reader
                "tags" -> out.putAll(tagFields(layer, tags))
                "guessed" -> {
                    @Suppress("UNCHECKED_CAST")
                    val g = v as Map<String, Any?>
                    if (g.values.all { it == true }) out["guessed"] = g.keys.toList() else extra[k] = v
                }
                "places" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ps = v as List<Dict>
                    out["places"] = ps.map { (it["id"] as Number).toLong() }
                    @Suppress("UNCHECKED_CAST")
                    out["place_hours"] = ps.map { (((it["tags"] as Map<String, Any?>?)?.get("opening_hours")) as String?) ?: "" }
                }
                else -> {
                    val col = cols[k]
                    if (col != null && fits(col, v)) out[k] = v else extra[k] = v
                }
            }
        }
        out["flags"] = flags(b, tags, nearWater)
        if (extra.isNotEmpty()) out["extra"] = checkValue(extra) { "${layer.label} ${b["id"]}" }
        return out
    }

    /** The derived flags ([Schema.Flags]) with the renders' own tests (amsterdam.py's `_draw_building`). */
    private fun flags(b: Dict, tags: Map<String, Any?>, nearWater: Boolean): Long {
        val btype = tags["building"] as String?
        var f = 0L
        @Suppress("UNCHECKED_CAST")
        val places = (b["places"] as List<Dict>?) ?: emptyList()
        if (btype in CHURCHES || tags["building:part"] == "church" || truthy(b["in_church"])) f = f or Schema.Flags.CHURCH.toLong()
        if (btype in WAREHOUSES) f = f or Schema.Flags.WAREHOUSE.toLong()
        if (places.isNotEmpty() || btype in SHOP_FLOORS) f = f or Schema.Flags.SHOP_FLOOR.toLong()
        if (btype == "construction") f = f or Schema.Flags.CONSTRUCTION.toLong()
        if (nearWater) f = f or Schema.Flags.NEAR_WATER.toLong()
        if (btype == "houseboat") f = f or Schema.Flags.HOUSEBOAT.toLong()
        val yr = (b["year"] as Number?)?.toInt() ?: 0
        if (yr >= 1960 && btype !in CHURCHES) f = f or Schema.Flags.MODERN.toLong()
        return f
    }

    /** The [variants], placed where their base feature is, after it. */
    private fun variantFeatures(placed: Map<Pair<Layer, Int>, GridPoint>): List<Pending> = variants.map { v ->
        val level = Keep.indexOf(v.tolerance)
        val fields = LinkedHashMap<String, Any?>()
        fields["layer"] = v.layer.id
        fields["index"] = v.index
        fields["tolerance"] = level
        fields.putAll(v.attributes)
        val ref = placed[v.layer to v.index] ?: throw IllegalStateException("a variant of ${v.layer.label} ${v.index}, which the scene does not have")
        Pending(Layer.VARIANT, fields, ref, intArrayOf(ref.lat, ref.lon, ref.lat, ref.lon), 0, (v.index.toLong() shl 8) or (v.layer.id.toLong() shl 40) or level.toLong())
    }

    // ------------------------------------------------------------------ the ground

    /** `S['areas']`, whole, each simplified at ladder index [coarse] (0: as the scene holds it) with fresh keep codes. */
    private fun areaFeatures(coarse: Int = 0): List<Pending> {
        val out = ArrayList<Pending>()
        for ((i, a) in dicts("areas").withIndex()) {
            val poly = generalised(points(a), coarse)
            val ring = geom(poly) { "area $i" }.withKeep(KeepCodes.of(poly))
            val fields = LinkedHashMap<String, Any?>()
            fields["source"] = 0
            fields["index"] = i
            fields["ring"] = ring
            val extra = LinkedHashMap<String, Any?>()
            for ((k, v) in a) when (k) {
                "poly" -> {}
                "kind" -> if (v is String) fields["kind"] = v else extra[k] = v
                "tags" -> fields.putAll(tagFields(Layer.GROUND, tagMap(v)))
                "id" -> fields["id"] = (v as Number).toLong()
                else -> extra[k] = v
            }
            fields["ground_key"] = GroundKeys.area(i)
            if (extra.isNotEmpty()) fields["extra"] = checkValue(extra) { "area $i" }
            val box = ring.box()
            val c = Cells.centre(box)
            out += Pending(Layer.GROUND, fields, GridPoint(c[0], c[1]), box, 0, i.toLong())
        }
        return out
    }

    /** [pts] as `simplify` leaves it at ladder index [level] (0: unchanged). */
    private fun generalised(pts: List<V2>, level: Int): List<V2> = if (level == 0) pts else KeepCodes.simplify(pts, KeepCodes.of(pts), level)

    /** Land cover and water, simplified at ladder index [coarse] (0: as they are), clipped at the edges of every tile at zoom [z] they reach. */
    private fun clipped(z: Int, coarse: Int = 0): Map<Cells.TileKey, List<Feature>> {
        val out = LinkedHashMap<Cells.TileKey, MutableList<Feature>>()
        fun cut(layer: Layer, ring: Geom, base: Map<String, Any?>, order: Long) {
            val box = ring.box()
            val t0 = Cells.tileOf(z, box[2], box[1])
            val t1 = Cells.tileOf(z, box[0], box[3])
            for (x in t0.x..t1.x) for (y in t0.y..t1.y) {
                val key = Cells.TileKey(z, x, y)
                val tb = Grid.tileBox(z, x, y)
                val piece = if (Clip.inside(ring.lat, ring.lon, tb)) Clip.Piece(ring.lat, ring.lon, BooleanArray(ring.size)) else Clip.ring(ring.lat, ring.lon, tb) ?: continue
                val pts = (0 until piece.size).map { V2(Grid.x(piece.lon[it], lon0, kx), Grid.z(piece.lat[it], lat0, kz)) }
                val codes = KeepCodes.of(pts)
                for (k in 0 until piece.size) if (piece.cut[k]) codes[k] = Keep.ALL.toByte()
                val g = Geom(piece.lat, piece.lon, codes)
                val fields = LinkedHashMap(base)
                fields["ring"] = g
                val pb = g.box()
                val c = Cells.centre(pb)
                out.getOrPut(key) { ArrayList() }.add(Feature(layer, Schema.canonical(layer, fields), Cells.cellOf(key, c[0], c[1]), pb, 0, order))
            }
        }
        val land = dicts("land")
        val landKeys = GroundKeys.land(land)
        for ((i, a) in land.withIndex()) {
            val ring = geom(generalised(points(a), coarse)) { "land $i" }
            val base = LinkedHashMap<String, Any?>()
            base["source"] = 1
            base["index"] = i
            val extra = LinkedHashMap<String, Any?>()
            for ((k, v) in a) when (k) {
                "poly" -> {}
                "kind" -> if (v is String) base["kind"] = v else extra[k] = v
                "tags" -> base.putAll(tagFields(Layer.GROUND, tagMap(v)))
                "id" -> base["id"] = (v as Number).toLong()
                else -> extra[k] = v
            }
            base["ground_key"] = landKeys[i]
            if (extra.isNotEmpty()) base["extra"] = checkValue(extra) { "land $i" }
            cut(Layer.GROUND, ring, base, (1L shl 40) or i.toLong())
        }
        @Suppress("UNCHECKED_CAST")
        val shore = (S["natural_shore"] as Collection<Any?>?) ?: emptyList()
        val shoreSet = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> shore.forEach { if (it != null) s.add(it) } }
        @Suppress("UNCHECKED_CAST")
        val tidal = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> ((S["_tidal"] as List<Any?>?) ?: emptyList()).forEach { if (it != null) s.add(it) } }
        @Suppress("UNCHECKED_CAST")
        val holeOf = (S["_hole_of"] as Map<Any?, Any?>?) ?: emptyMap()
        for ((hole, list) in listOf(false to water, true to holes)) {
            for ((i, r) in list.withIndex()) {
                val ring = geom(generalised(r, coarse)) { "${if (hole) "island" else "water"} $i" }
                val base = LinkedHashMap<String, Any?>()
                base["index"] = i
                if (hole) base["hole"] = true
                if (r in shoreSet) base["shore"] = true
                levelOf(r)?.let { base["level"] = it }
                if (r in tidal) base["tidal"] = true
                if (hole) holeOf.entries.firstOrNull { it.key === r }?.value?.let { o -> base["hole_of"] = (waterIndex[o] ?: throw IllegalStateException("island $i belongs to a ring outside S['water']")).toLong() }
                base["ground_key"] = GroundKeys.water(i, hole)
                cut(Layer.WATER, ring, base, (if (hole) 1L shl 40 else 0L) or i.toLong())
            }
        }
        return out
    }

    /** The water level `render` takes for ring [r] (`S['water_y']`, keyed by the ring itself), or null for the region's `WATER_Y`. */
    private fun levelOf(r: Any): Double? {
        @Suppress("UNCHECKED_CAST")
        val lvl = (S["water_y"] as Map<Any?, Any?>?) ?: return null
        for ((k, v) in lvl) if (k === r) return (v as Number).toDouble()
        return null
    }

    /** `water_edges(S)`: every edge that carries a quay, in its order, in the tile of its midpoint. */
    private fun quays(): List<Pending> {
        val out = ArrayList<Pending>()
        @Suppress("UNCHECKED_CAST")
        val tidal = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> ((S["_tidal"] as List<Any?>?) ?: emptyList()).forEach { if (it != null) s.add(it) } }
        // which ring each edge lies on: the rings whose consecutive vertices are its ends
        val ringOf = IdentityHashMap<V2, MutableList<Pair<Boolean, Int>>>()
        for ((hole, list) in listOf(false to water, true to holes)) for ((i, r) in list.withIndex()) for (p in r) ringOf.getOrPut(p) { ArrayList(1) }.add(hole to i)
        for ((k, e) in edges.withIndex()) {
            val what = { "quay edge $k" }
            val (hole, ring) = (ringOf[e.a] ?: emptyList()).firstOrNull { (h, i) -> val r = if (h) holes[i] else water[i]; r.any { it === e.b } }
                ?: throw IllegalStateException("${what()} lies on no ring of the scene")
            val ex = e.b.x - e.a.x
            val ez = e.b.y - e.a.y
            val lg = PyMath.hypot(ex, ez)
            val nx = ez / lg
            val nz = -ex / lg
            val flip = when {
                e.nx == nx && e.nz == nz -> false
                e.nx == -nx && e.nz == -nz -> true
                else -> throw IllegalStateException("${what()}: its normal is neither (ez, -ex)/L nor its opposite")
            }
            val fields = LinkedHashMap<String, Any?>()
            fields["index"] = k
            if (hole) fields["hole"] = true
            fields["ring"] = ring
            val own: Any = if (hole) holes[ring] else water[ring]
            if (e.r !== own) fields["owner"] = (waterIndex[e.r] ?: throw IllegalStateException("${what()} counts as a ring outside S['water']")).toLong()
            val a = point(e.a.x, e.a.y, what)
            val b = point(e.b.x, e.b.y, what)
            fields["edge"] = Geom(intArrayOf(a.lat, b.lat), intArrayOf(a.lon, b.lon), null)
            if (flip) fields["flip"] = true
            levelOf(e.r)?.let { fields["level"] = it }
            if (e.r in tidal) fields["tidal"] = true
            val box = intArrayOf(minOf(a.lat, b.lat), minOf(a.lon, b.lon), maxOf(a.lat, b.lat), maxOf(a.lon, b.lon))
            val c = Cells.centre(box)
            out += Pending(Layer.QUAY, fields, GridPoint(c[0], c[1]), box, 0, k.toLong())
        }
        return out
    }

    // ------------------------------------------------------------------ roads and lines

    private fun roadFeatures(): List<Pending> {
        val out = ArrayList<Pending>()
        for ((i, r) in dicts("roads").withIndex()) {
            val line = geom(points(r, "pts")) { "road $i (${r["id"]})" }
            val fields = LinkedHashMap<String, Any?>()
            fields["index"] = i
            val extra = LinkedHashMap<String, Any?>()
            for ((k, v) in r) when (k) {
                "pts" -> fields["line"] = line
                "id" -> fields["id"] = (v as Number).toLong()
                "w", "layer" -> if (v is Double) fields[k] = v else extra[k] = v
                "hw" -> if (v is String) fields[k] = v else extra[k] = v
                "bridge", "wguess" -> if (v is Boolean) fields[k] = v else extra[k] = v
                "tags" -> fields.putAll(tagFields(Layer.ROAD, tagMap(v)))
                else -> extra[k] = v
            }
            fields["ground_key"] = GroundKeys.road(r["hw"] as String, i)
            if (extra.isNotEmpty()) fields["extra"] = checkValue(extra) { "road $i" }
            out += linePending(Layer.ROAD, fields, line, i.toLong())
        }
        return out
    }

    private fun linePending(layer: Layer, fields: Map<String, Any?>, line: Geom, order: Long): Pending {
        val box = line.box()
        val m = line.size / 2
        return Pending(layer, fields, GridPoint(line.lat[m], line.lon[m]), box, 0, order)
    }

    private fun lineFeatures(): List<Pending> {
        val out = ArrayList<Pending>()
        for ((i, rl) in rings("rails").withIndex()) {
            val line = geom(rl) { "tram track $i" }
            out += linePending(Layer.LINE, mapOf("source" to 0, "index" to i, "line" to line, "ground_key" to GroundKeys.tram(i)), line, i.toLong())
        }
        val big = dicts("big")
        val bigKeys = GroundKeys.big(big)
        val aero = dicts("aero")
        val aeroKeys = GroundKeys.aero(aero)
        val sources = listOf(
            Triple(1, "rail", { i: Int -> GroundKeys.rail(i) }),
            Triple(2, "big", { i: Int -> bigKeys[i] }),
            Triple(3, "aero", { i: Int -> aeroKeys[i] }),
            Triple(4, "power", { _: Int -> null }),
            Triple(5, "marine", { i: Int -> GroundKeys.marine(i) }),
            Triple(6, "park", { i: Int -> GroundKeys.park(i) }),
        )
        for ((source, key, groundKey) in sources) {
            for ((i, d) in dicts(key).withIndex()) {
                val what = { "$key $i" }
                val fields = LinkedHashMap<String, Any?>()
                fields["source"] = source
                fields["index"] = i
                val extra = LinkedHashMap<String, Any?>()
                var line: Geom? = null
                for ((k, v) in d) when (k) {
                    "pts", "ring" -> {
                        @Suppress("UNCHECKED_CAST")
                        line = geom(v as List<V2>, what)
                        fields["line"] = line
                    }
                    "id" -> fields["id"] = (v as Number).toLong()
                    "kind", "hw" -> if (v is String) fields[k] = v else extra[k] = v
                    "crane", "closed" -> if (v is Boolean) fields[k] = v else extra[k] = v
                    "h" -> if (v == null || v is Double) fields[k] = v else extra[k] = v
                    "tags" -> fields.putAll(tagFields(Layer.LINE, tagMap(v)))
                    else -> extra[k] = v
                }
                groundKey(i)?.let { fields["ground_key"] = it }
                if (extra.isNotEmpty()) fields["extra"] = checkValue(extra, what)
                val g = line ?: throw IllegalStateException("${what()} has no line")
                out += linePending(Layer.LINE, fields, g, (source.toLong() shl 40) or i.toLong())
            }
        }
        return out
    }

    // ------------------------------------------------------------------ trees, things, items

    private fun treeFeatures(): List<Pending> {
        val out = ArrayList<Pending>()
        for ((i, n) in trees.withIndex()) {
            val what = { "tree $i (${n["id"]})" }
            val p = point(n["x"] as Double, n["z"] as Double, what)
            val fields = LinkedHashMap<String, Any?>()
            fields["index"] = i
            fields["node"] = (nodeIndex[n] ?: throw IllegalStateException("${what()} is not in S['nodes']")).toLong()
            fields["id"] = (n["id"] as Number).toLong()
            fields["pos"] = p
            val extra = LinkedHashMap<String, Any?>()
            for ((k, v) in n) when (k) {
                "x", "z", "id" -> {}
                "h", "crown", "gy" -> if (v == null || v is Double) fields[k] = v else extra[k] = v
                "guessed" -> tagMap(v).let { g -> if (g.values.all { it == true }) fields["guessed"] = g.keys.toList() else extra[k] = v }
                "tags" -> fields.putAll(tagFields(Layer.TREE, tagMap(v)))
                "style" -> extra[k] = styleValue(v)
                "_b" -> {} // its building: the building's places hold it
                else -> extra[k] = v
            }
            if (extra.isNotEmpty()) fields["extra"] = checkValue(extra, what)
            out += Pending(Layer.TREE, fields, p, intArrayOf(p.lat, p.lon, p.lat, p.lon), cm((n["h"] as? Double) ?: 0.0), i.toLong())
        }
        return out
    }

    /** Whether the scene draws facades (doors and shopfronts): the living map does, regions do not (`S['no_facades']`). */
    private val facades = !truthy(S["no_facades"])

    /**
     * True when the renders draw node [n] as a thing in this scene: a street lamp, a bench, a rack or a basket, a bollard,
     * a place in `S['pois']` (its glow at night); and where facades are drawn, every address (its door and number) and
     * every node with a shop style other than a service (its shopfront) that stands in a building facades are drawn on
     * (the others never show). Trees are the tree layer's.
     */
    private fun isThing(n: Dict): Boolean {
        if (n in treeSet) return false
        val t = tagMap(n["tags"])
        if (t["highway"] == "street_lamp" || t["amenity"] in FURNITURE || t["barrier"] in BOLLARDS || n in pois) return true
        return facades && doorBuilding(n) != null
    }

    /** The buildings facades are drawn on (`facade_details`' `blds`): shown, with a height, not a houseboat; and their boxes. */
    private val facadeBuildings: List<Pair<Int, DoubleArray>> by lazy {
        buildings.withIndex().filter { (_, b) -> !truthy(b["hidden"]) && truthy(b["h"]) && tagMap(b["tags"])["building"] != "houseboat" }.map { (i, b) ->
            val p = points(b)
            var x0 = p[0].x; var x1 = p[0].x; var z0 = p[0].y; var z1 = p[0].y
            for (q in p) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < z0) z0 = q.y; if (q.y > z1) z1 = q.y }
            i to doubleArrayOf(x0, x1, z0, z1)
        }
    }

    /**
     * The building whose facade carries node [n]'s door or shopfront (`facade_details`: a node with a house number or a
     * shop style other than a service, in the first of [facadeBuildings] whose box and ring hold it), as its index in
     * `S['buildings']`; null for a node with neither, or in none.
     */
    private fun doorBuilding(n: Dict): Int? {
        val t = tagMap(n["tags"])
        val st = n["style"] as PoiStyle.Style?
        if (!(truthy(t["addr:housenumber"]) || (st != null && st.cat != "service"))) return null
        val x = n["x"] as Double
        val z = n["z"] as Double
        for ((i, bb) in facadeBuildings) if (bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3] && Real.pip(x, z, points(buildings[i]))) return i
        return null
    }

    /** `re.match(r'\d+', s)` read as an int, as `amsterdam.py`'s doors read a house number: null without leading digits. */
    private fun leadingNumber(s: String): Long? {
        var i = 0
        while (i < s.length && Character.isDigit(s[i])) i++
        if (i == 0) return null
        return java.math.BigInteger(PyRe.asciiDigits(s.substring(0, i))).toLong()
    }

    private fun thingFeatures(): List<Pending> {
        val out = ArrayList<Pending>()
        for ((i, n) in nodes.withIndex()) {
            if (!isThing(n)) continue
            val what = { "node $i (${n["id"]})" }
            val p = point(n["x"] as Double, n["z"] as Double, what)
            val fields = LinkedHashMap<String, Any?>()
            fields["index"] = i
            fields["id"] = (n["id"] as Number).toLong()
            fields["pos"] = p
            if (n in pois) fields["poi"] = true
            val extra = LinkedHashMap<String, Any?>()
            for ((k, v) in n) when (k) {
                "x", "z", "id" -> {}
                "gy" -> if (v == null || v is Double) fields[k] = v else extra[k] = v
                "style" -> {
                    val st = v as PoiStyle.Style
                    // the glyph is the app's icon: the renders never read it
                    fields["style.cat"] = st.cat; fields["style.col"] = st.col; fields["style.kind"] = st.kind
                    fields["style.brand"] = st.brand; fields["style.bg"] = st.bg; fields["style.fg"] = st.fg
                }
                "_b" -> {} // the building it stands in: that building's places name it
                "tags" -> {
                    fields.putAll(tagFields(Layer.THING, tagMap(v)))
                    val num = tagMap(v)["addr:housenumber"]
                    if (truthy(num)) {
                        fields["door"] = true
                        leadingNumber(num as String)?.let { fields["door_number"] = it }
                    }
                }
                else -> extra[k] = v
            }
            if (extra.isNotEmpty()) fields["extra"] = checkValue(extra, what)
            out += Pending(Layer.THING, fields, p, intArrayOf(p.lat, p.lon, p.lat, p.lon), 0, i.toLong())
        }
        return out
    }

    private fun itemFeatures(): List<Pending> {
        val out = ArrayList<Pending>()
        fun item(source: Int, i: Int, d: Map<String, Any?>, what: () -> String) {
            val fields = LinkedHashMap<String, Any?>()
            fields["source"] = source
            fields["index"] = i
            val extra = LinkedHashMap<String, Any?>()
            val x = d["x"] as Double
            val z = d["z"] as Double
            val exact = Grid.latOf(z, lat0, kz) != null && Grid.lonOf(x, lon0, kx) != null
            val p = if (exact) point(x, z, what) else nearest(x, z)
            fields["pos"] = p
            if (!exact) { extra["x"] = x; extra["z"] = z }
            for ((k, v) in d) when (k) {
                "x", "z" -> {}
                "id" -> fields["id"] = (v as Number).toLong()
                "poly" -> if (v != null) fields["poly"] = geom(pointList(v), what)
                "h", "rotor" -> if (v == null || v is Double) fields[k] = v else extra[k] = v
                "tags" -> fields["tags"] = allTags(tagMap(v))
                else -> extra[k] = v
            }
            if (extra.isNotEmpty()) fields["extra"] = checkValue(extra, what)
            out += Pending(Layer.ITEM, fields, p, intArrayOf(p.lat, p.lon, p.lat, p.lon), cm((d["h"] as? Double) ?: 0.0), (source.toLong() shl 40) or i.toLong())
        }
        for ((source, key) in listOf(0 to "cranes", 1 to "masts", 2 to "mtowers", 4 to "turbines")) {
            for ((i, d) in dicts(key).withIndex()) item(source, i, d) { "$key $i" }
        }
        @Suppress("UNCHECKED_CAST")
        val towers = (S["towers"] as Map<Any?, Pair<V2, Double?>>?) ?: emptyMap()
        for ((i, e) in towers.entries.withIndex()) {
            val (pos, h) = e.value
            item(3, i, linkedMapOf("x" to pos.x, "z" to pos.y, "id" to e.key, "h" to h)) { "pylon $i" }
        }
        return out
    }

    // ------------------------------------------------------------------ values

    /** The tags of a record that the renders read for [layer] ([Schema.RENDER_TAGS]), as its `tag:<key>` fields. */
    fun tagFields(layer: Layer, tags: Map<String, Any?>): Map<String, String> {
        val keys = Schema.RENDER_TAGS[layer] ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in keys) {
            val v = tags[k] ?: continue
            out[Schema.tag(k)] = v as? String ?: throw IllegalStateException("tag $k=$v is not a string")
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun tagMap(v: Any?): Map<String, Any?> = (v as Map<String, Any?>?) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun pointList(v: Any?): List<V2> = (v as List<*>).also { l -> require(l.all { it is V2 }) { "a list of points holds something else" } } as List<V2>

    private fun allTags(tags: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in tags) if (v != null) out[k] = v as? String ?: throw IllegalStateException("tag $k=$v is not a string")
        return out
    }

    private fun styleValue(v: Any?): Any? = (v as PoiStyle.Style?)?.let { linkedMapOf("cat" to it.cat, "col" to it.col, "glyph" to it.glyph, "kind" to it.kind, "brand" to it.brand, "bg" to it.bg, "fg" to it.fg) }

    /** Fails the build on a value [app.atlas.rendertiles.codec.Values] cannot carry, naming where it is. */
    private fun checkValue(v: Any?, what: () -> String): Any? {
        when (v) {
            null, is Boolean, is Int, is Long, is Double, is String -> {}
            is List<*> -> v.forEach { checkValue(it, what) }
            is Map<*, *> -> v.forEach { (k, e) -> if (k !is String) throw IllegalStateException("${what()}: a map with a ${k?.let { it::class.simpleName }} key"); checkValue(e, what) }
            else -> throw IllegalStateException("${what()}: a field holds a ${v::class.qualifiedName}, which render tiles cannot carry")
        }
        return v
    }

    private fun fits(col: Schema.Column, v: Any?): Boolean = when (col.codec) {
        Codec.CENTI -> v == null || v is Double
        Codec.NVARZ, Codec.NDELTA -> v == null || v is Int || v is Long
        Codec.STR -> v == null || v is String
        Codec.BOOL -> v is Boolean
        else -> false
    }

    /** Lookups for [Labels]. */
    internal val sceneNodes: List<Dict> get() = nodes
    internal fun labelDicts(): List<Dict> = dicts("labels")
    internal fun gridPoint(p: V2, what: () -> String): GridPoint = point(p.x, p.y, what)
    internal fun tagsFor(layer: Layer, d: Dict): Map<String, String> = tagFields(layer, tagMap(d["tags"]))

    private fun Labels.Item.toPending(): Pending = Pending(Layer.LABEL, fields, ref, intArrayOf(ref.lat, ref.lon, ref.lat, ref.lon), 0, order)

    companion object {
        /** What [pack] made: the file written, and the tiles, head, style and variants written into it. */
        class Packed(val written: Format3Writer.Written, val tiles: List<RenderTile>, val head: RenderHead, val style: StyleTables, val variants: List<Variant>)

        /**
         * The render pack of [elementPack] (an `.atlas` region pack, format 1 or 2) written to [out]: its scene loaded
         * whole at tolerance 0, the variants of a region at every other tolerance its views and the live map's bands load
         * it at, tiles at [levels] ([levelsOf] by default), the `rhead` and the `style`. The engine unpacks the region
         * under [work]. [log] hears each step.
         */
        fun pack(elementPack: File, out: File, work: File, levels: IntArray? = null, log: (String) -> Unit = {}): Packed {
            val t0 = System.nanoTime()
            val source = sha256(elementPack)
            val before = LiveEngine.workDir
            val minorBefore = PyCompat.minor
            LiveEngine.workDir = work
            // the port as the renders ran it, CPython 3.9's last bits, whatever a caller set
            PyCompat.minor = 9
            try {
                val (region, info) = PackReader.open(elementPack).use { r ->
                    val m = r.manifest
                    LiveEngine.open(r, source) to Format3Writer.Info(m.id, m.name, m.bbox, m.origin, m.timezone, m.tradition, m.dataTime, m.config, m.header, m.sources)
                }
                val base = LiveEngine.loadScene(region)
                log("${region.id}: scene loaded whole in ${base.loadMillis} ms")
                val variants = ArrayList<Variant>()
                for (t in variantTolerances(region)) {
                    val other = LiveEngine.loadScene(region, null, base.opts, t)
                    val vs = variants(base, other)
                    variants += vs
                    log("${region.id}: at $t m ${vs.size} buildings and parts differ (${other.loadMillis} ms)")
                }
                val lv = levels ?: levelsOf(region)
                val stt = SceneToTiles(base, variants, lv)
                val tiles = stt.tiles()
                val head = Head.of(base, stt, source, lv)
                val style = Style.of(base)
                val written = Format3Writer.write(tiles, head, style, info, out)
                log("${region.id}: ${tiles.size} tiles, ${written.bytes} bytes in ${(System.nanoTime() - t0) / 1_000_000} ms")
                return Packed(written, tiles, head, style, variants)
            } finally {
                LiveEngine.workDir = before
                PyCompat.minor = minorBefore
            }
        }

        /**
         * The levels of [region]'s pack: L14 and L12 for a region; L14 alone for the living map, whose few tiles serve it at
         * every distance (it is the Westerkerk's close look, and the region around it carries L12).
         */
        fun levelsOf(region: LiveRegion): IntArray = if (region.kind == LiveRegion.Kind.LIVING) intArrayOf(14) else intArrayOf(14, 12)

        /**
         * The tolerances other than 0 [region] is loaded at: its views' own (`GENERALISE`) and the live map's bands
         * (`Lod.BANDS`); none for the living map.
         */
        fun variantTolerances(region: LiveRegion): List<Double> {
            if (region.kind == LiveRegion.Kind.LIVING) return emptyList()
            val ts = java.util.TreeSet<Double>()
            for (k in region.ladder.knots) ts += Lod.ofView(region.id, k.name)
            for (b in Lod.BANDS) ts += b
            ts.remove(0.0)
            for (t in ts) require(Keep.indexOf(t) > 0) { "${region.id} loads at $t m, which is not on the keep ladder" }
            return ts.toList()
        }

        /** The SHA-256 of [f], lower-case hex. */
        fun sha256(f: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val b = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(b)
                    if (n < 0) break
                    md.update(b, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /**
         * The buildings and parts of [other] (the same region loaded whole at another tolerance of the ladder) whose
         * attributes differ from [base]'s (loaded at 0): the [Variant]s a pack carries for that tolerance. Fails when the
         * two scenes do not hold the same records, or other water.
         */
        fun variants(base: LiveScene, other: LiveScene): List<Variant> {
            require(other.region === base.region) { "a variant of another region" }
            require(Keep.indexOf(other.tolerance) > 0) { "a variant at ${other.tolerance}, which is not on the keep ladder" }
            val a = SceneToTiles(base)
            other.state.restore()
            val vEdges = Amsterdam.waterEdges(other.S)
            base.state.restore()
            val out = ArrayList<Variant>()
            @Suppress("UNCHECKED_CAST")
            for ((layer, key) in listOf(Layer.BUILDING to "buildings", Layer.PART to "parts")) {
                val mine = (base.S[key] as List<Dict>?) ?: emptyList()
                val theirs = (other.S[key] as List<Dict>?) ?: emptyList()
                require(mine.size == theirs.size) { "${base.region.id} at ${other.tolerance}: ${theirs.size} $key, not ${mine.size}" }
                for (i in mine.indices) {
                    val b = mine[i]
                    val o = theirs[i]
                    require(b["id"] == o["id"]) { "${base.region.id} at ${other.tolerance}: $key $i is ${o["id"]}, not ${b["id"]}" }
                    val x = Schema.canonical(Layer.VARIANT, a.attributes(layer, b, Amsterdam.nearWater(a.edges, a.points(b), 16.0)))
                    val y = Schema.canonical(Layer.VARIANT, a.attributes(layer, o, Amsterdam.nearWater(vEdges, a.points(o), 16.0)))
                    if (x != y) out += Variant(other.tolerance, layer, i, y)
                }
            }
            @Suppress("UNCHECKED_CAST")
            val w0 = (base.S["water"] as List<List<app.atlas.render.V2>>?) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val w1 = (other.S["water"] as List<List<app.atlas.render.V2>>?) ?: emptyList()
            require(w0 == w1) { "${base.region.id} at ${other.tolerance}: its water differs from tolerance 0's" }
            return out
        }

        /** The tolerance a coarse level's ground is simplified at: the ladder's 4 m, for the far levels (plan-vector D2). */
        const val COARSE = 4.0

        /** `amsterdam.py`'s CHURCHES, WAREHOUSES and SHOP_FLOORS (private in the port; `RenderTablesTest` holds them equal). */
        val CHURCHES = setOf("church", "cathedral", "chapel")
        val WAREHOUSES = setOf("industrial", "warehouse", "service")
        val SHOP_FLOORS = setOf("retail", "commercial")

        /** `amsterdam.py`'s FURNITURE and BOLLARDS (private in the port; held equal by `RenderTablesTest`). */
        val FURNITURE = setOf("bench", "bicycle_parking", "waste_basket")
        val BOLLARDS = setOf("bollard", "block")

        /** [m] metres in whole centimetres, within an Int. */
        fun cm(m: Double): Int = Math.round(m * 100).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
