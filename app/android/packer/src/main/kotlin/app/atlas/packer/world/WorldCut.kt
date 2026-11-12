package app.atlas.packer.world

import app.atlas.rendertiles.compile.Cells
import app.atlas.rendertiles.compile.GroundKeys
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.GroundKey
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import com.onthegomap.planetiler.VectorTile
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.LinearComponentExtracter
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust

/**
 * One world tile cut on the grid at its box and its cells (ENGINE.md step 5: "their geometry is cut at their 4 × 4
 * cells"), from Planetiler's tile of the same key (its features in its 4,096 units a tile, decoded to its pixels) and
 * the land under it ([LandBase]), in format 3's layers:
 * - GROUND source [LAND_BASE]: the land, kind `land`, under everything (no ground key); with the sea it fills each cell
 *   exactly, which [Cut.cover] measures;
 * - GROUND source 1: the land cover by kind, ordered as the loader orders `S['land']` (LAND's draw order, then by area,
 *   largest first), each with its ground key (GroundKey.LAND class 0, its index);
 * - WATER: the sea first (`tidal`), then lakes, reservoirs and riverbanks, every one a natural shore;
 * - ROAD: secondaries and tertiaries (class, their class's width as a guess), in the roads' drawing order;
 * - LINE: rail (source 1), big roads (2, motorways over trunks over primaries), runways (3) and rivers ([RIVERS],
 *   drawn with the water);
 * - LABEL source 1: the places, by rank (a country, then cities, towns, villages and suburbs, each by population);
 *   source 2 ([WATER_NAMES]): the water's names at their points, by rank (oceans, seas, bays and straits, lakes and
 *   reservoirs, each by area), `count` their areas in m²; source 3 ([RIVER_NAMES]): the rivers' names, one a river a
 *   tile at the middle of its longest piece, `prev` before it along the baseline, `count` its length in the tile in
 *   metres, by length. The core's places pass reads source 1 alone (world-names-format.md, the world session's note).
 */
class WorldCut(private val tags: StagePTags = StagePTags.STAGE_P) {
    /** A cut tile and how its cells' ground fills them. */
    class Cut(val tile: RenderTile?, val cover: Cover)

    /**
     * Each cell's land and sea against its box, in twice the grid's square units: the most any cell's sum differs by
     * and that cell's area, and the rings found across a cell's edge.
     */
    class Cover(val worstDifference: Long, val cellArea: Long, val ringsOutside: Int) {
        /** The worst cell's difference as a share of its area. */
        val relative: Double get() = if (cellArea == 0L) 0.0 else worstDifference.toDouble() / cellArea
    }

    /** One feature over the land: its profile's layer ([WorldProfile.LAND], …), its attributes, its geometry in its tile's pixels. */
    class Item(val layer: String, val attrs: Map<String, Any?>, val geometry: Geometry)

    /** Planetiler's tile [mvt] as items. */
    fun items(mvt: ByteArray?): List<Item> =
        (mvt?.let { VectorTile.decode(it) } ?: emptyList()).map { f -> Item(f.layer(), f.attrs(), f.geometry().decode()) }

    /** Tile ([z], [x], [y]) from Planetiler's tile [mvt] (null: none) and its [land] in its pixels (null: none). */
    fun cut(z: Int, x: Int, y: Int, mvt: ByteArray?, land: Geometry?): Cut = cut(z, x, y, items(mvt), land)

    /**
     * Tile ([z], [x], [y]) from its [items] and its [land] in its pixels (null: none). Its tile is null for open sea with
     * nothing in it.
     */
    fun cut(z: Int, x: Int, y: Int, decoded: List<Item>, land: Geometry?): Cut {
        val cells = WorldGeometry.cellBoxes(z, x, y)
        val rects = cells.map { WorldGeometry.rect(it) }
        val tileBox = Grid.tileBox(z, x, y)
        val landGrid = land?.let { WorldGeometry.pixelsToGrid(it, z, x, y) }?.takeIf { !it.isEmpty }
        if (landGrid == null && decoded.isEmpty()) return Cut(null, Cover(0, 0, 0))

        val out = ArrayList<Feature>()
        var worst = 0L
        var worstArea = 0L
        var outside = 0
        fun add(layer: Layer, fields: Map<String, Any?>, cell: Int, geom: Geom?, order: Long, point: GridPoint? = null) {
            val box = geom?.box() ?: intArrayOf(point!!.lat, point.lon, point.lat, point.lon)
            val c = cells[cell]
            if (box[0] < c[0] || box[1] < c[1] || box[2] > c[2] || box[3] > c[3]) outside++
            out += Feature(layer, Schema.canonical(layer, fields), cell, box, 0, order)
        }

        // the land and the sea, cell by cell
        var baseIndex = 0
        var waterIndex = 0
        for ((i, rect) in rects.withIndex()) {
            val cellTwice = 2L * (cells[i][2] - cells[i][0]).toLong() * (cells[i][3] - cells[i][1]).toLong()
            val (landHere, seaHere) = if (landGrid == null) emptyList<Geom>() to polygonRings(rect) else partition(landGrid, cells[i])
            var sum = 0L
            for (g in landHere) {
                sum += Math.abs(WorldGeometry.areaTwice(g))
                add(Layer.GROUND, mapOf("source" to LAND_BASE, "index" to baseIndex.toLong(), "ring" to g, "kind" to "land"), i, g, order(0, baseIndex++))
            }
            for (g in seaHere) {
                sum += Math.abs(WorldGeometry.areaTwice(g))
                add(Layer.WATER, mapOf("index" to waterIndex.toLong(), "ring" to g, "shore" to true, "tidal" to true, "ground_key" to GroundKeys.water(waterIndex, false)), i, g, order(0, waterIndex++))
            }
            val d = Math.abs(sum - cellTwice)
            if (d > worst || worstArea == 0L) {
                worst = maxOf(worst, d)
                worstArea = cellTwice
            }
        }

        // Planetiler's features, by layer
        val byLayer = decoded.groupBy { it.layer }
        fun grid(f: Item): Geometry = WorldGeometry.pixelsToGrid(f.geometry, z, x, y)

        // land cover: LAND's draw order, then area, largest first (the tile's piece of each)
        val cover = (byLayer[WorldProfile.LAND] ?: emptyList()).mapNotNull { f ->
            val kind = f.attrs["kind"]?.toString() ?: return@mapNotNull null
            val order = tags.land[kind] ?: return@mapNotNull null
            val g = grid(f).takeIf { !it.isEmpty } ?: return@mapNotNull null
            Triple(kind, order, g)
        }.sortedWith(compareBy<Triple<String, Int, Geometry>> { it.second }.thenByDescending { it.third.area })
        for ((index, item) in cover.withIndex()) {
            val (kind, _, g) = item
            for ((i, rect) in rects.withIndex()) {
                if (!rect.envelopeInternal.intersects(g.envelopeInternal)) continue
                for (r in polygonRings(WorldGeometry.intersection(g, rect))) {
                    add(Layer.GROUND, mapOf("source" to 1, "index" to index.toLong(), "ring" to r, "kind" to kind, "ground_key" to GroundKey.of(GroundKey.LAND, 0, index)), i, r, order(1, index))
                }
            }
        }

        // inland water after the sea
        val water = (byLayer[WorldProfile.WATER] ?: emptyList()).map { grid(it) }.filter { !it.isEmpty }.sortedByDescending { it.area }
        for (g in water) {
            val index = waterIndex++
            for ((i, rect) in rects.withIndex()) {
                if (!rect.envelopeInternal.intersects(g.envelopeInternal)) continue
                for (r in polygonRings(WorldGeometry.intersection(g, rect))) {
                    add(Layer.WATER, mapOf("index" to index.toLong(), "ring" to r, "shore" to true, "ground_key" to GroundKeys.water(index, false)), i, r, order(1, index))
                }
            }
        }

        // areas over the water (the globe's ice shelves), in their order
        for ((index, f) in (byLayer[AREAS] ?: emptyList()).withIndex()) {
            val kind = f.attrs["kind"]?.toString() ?: continue
            val g = grid(f)
            for ((i, rect) in rects.withIndex()) {
                if (g.isEmpty || !rect.envelopeInternal.intersects(g.envelopeInternal)) continue
                for (r in polygonRings(WorldGeometry.intersection(g, rect))) {
                    add(Layer.GROUND, mapOf("source" to 0, "index" to index.toLong(), "ring" to r, "kind" to kind, "ground_key" to GroundKeys.area(index)), i, r, order(2, index))
                }
            }
        }

        // roads: class by class in the roads' drawing order
        val roads = (byLayer[WorldProfile.ROAD] ?: emptyList()).mapNotNull { f ->
            val hw = f.attrs["hw"]?.toString() ?: return@mapNotNull null
            if (hw !in GroundKeys.ROAD_ORDER) return@mapNotNull null
            hw to grid(f)
        }.sortedBy { GroundKeys.ROAD_ORDER.indexOf(it.first) }
        for ((index, item) in roads.withIndex()) {
            val (hw, g) = item
            val width = tags.roads[hw] ?: continue
            lines(g, rects) { i, l ->
                add(Layer.ROAD, mapOf("index" to index.toLong(), "line" to l, "w" to width, "hw" to hw, "wguess" to true, "ground_key" to GroundKeys.road(hw, index)), i, l, index.toLong())
            }
        }

        // lines over the ground: rail, big roads (minor classes first), runways; rivers with the water
        var lineIndex = 0
        for (f in byLayer[WorldProfile.RAIL] ?: emptyList()) {
            val index = lineIndex++
            lines(grid(f), rects) { i, l -> add(Layer.LINE, mapOf("source" to 1, "index" to index.toLong(), "line" to l, "ground_key" to GroundKeys.rail(index)), i, l, order(1, index)) }
        }
        val big = (byLayer[WorldProfile.BIG] ?: emptyList()).mapNotNull { f -> f.attrs["hw"]?.toString()?.let { it to f } }.sortedBy { BIG_ORDER.indexOf(it.first) }
        for ((rank, item) in big.withIndex()) {
            val (hw, f) = item
            val index = lineIndex++
            lines(grid(f), rects) { i, l -> add(Layer.LINE, mapOf("source" to 2, "index" to index.toLong(), "line" to l, "hw" to hw, "ground_key" to GroundKey.of(GroundKey.OVER, 1, rank)), i, l, order(2, rank)) }
        }
        val aero = (byLayer[WorldProfile.AERO] ?: emptyList()).mapNotNull { f -> f.attrs["kind"]?.toString()?.let { it to f } }.sortedBy { GroundKeys.AERO_ORDER[it.first] ?: 0 }
        for ((rank, item) in aero.withIndex()) {
            val (kind, f) = item
            val index = lineIndex++
            lines(grid(f), rects) { i, l -> add(Layer.LINE, mapOf("source" to 3, "index" to index.toLong(), "line" to l, "kind" to kind, "ground_key" to GroundKey.of(GroundKey.OVER, 2, rank)), i, l, order(3, rank)) }
        }
        for ((rank, f) in (byLayer[WorldProfile.RIVER] ?: emptyList()).withIndex()) {
            val index = lineIndex++
            lines(grid(f), rects) { i, l -> add(Layer.LINE, mapOf("source" to RIVERS, "index" to index.toLong(), "line" to l, "kind" to "river", "ground_key" to GroundKey.of(GroundKey.WATER, RIVER_CLASS, rank)), i, l, order(0, rank)) }
        }

        // places by rank
        val places = (byLayer[WorldProfile.PLACE] ?: emptyList()).mapNotNull { f ->
            val p = f.geometry as? Point ?: return@mapNotNull null
            val lat = WorldGeometry.latOfPixel(z, y, p.y).toInt()
            val lon = WorldGeometry.lonOfPixel(z, x, p.x).toInt()
            if (lat < tileBox[0] || lat >= tileBox[2] || lon < tileBox[1] || lon >= tileBox[3]) return@mapNotNull null
            Place(f.attrs, GridPoint(lat, lon))
        }.sortedWith(Place.BY_RANK)
        for ((index, p) in places.withIndex()) {
            val cell = Cells.cellOf(Cells.TileKey(z, x, y), p.pos.lat, p.pos.lon)
            val fields = LinkedHashMap<String, Any?>()
            fields["source"] = 1
            fields["index"] = index.toLong()
            fields["id"] = p.id
            fields["name"] = p.name
            fields["pos"] = p.pos
            fields["prev"] = p.pos
            fields["count"] = 1L
            fields["kind"] = p.kind
            fields[Schema.tag("place")] = p.kind
            fields[Schema.tag("population")] = p.attrs["population"]?.toString()
            fields[Schema.tag("capital")] = p.attrs["capital"]?.toString()
            fields[Schema.tag("admin_level")] = p.attrs["admin_level"]?.toString()
            add(Layer.LABEL, fields, cell, null, index.toLong(), p.pos)
        }

        // the water's names at their points, by rank (source 2, after the places: the core's places pass leaves them)
        val waters = (byLayer[WorldProfile.WATER_NAME] ?: emptyList()).mapNotNull { f ->
            val p = f.geometry as? Point ?: return@mapNotNull null
            val name = f.attrs["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = WorldGeometry.latOfPixel(z, y, p.y).toInt()
            val lon = WorldGeometry.lonOfPixel(z, x, p.x).toInt()
            if (lat < tileBox[0] || lat >= tileBox[2] || lon < tileBox[1] || lon >= tileBox[3]) return@mapNotNull null
            WaterName(f.attrs, name, GridPoint(lat, lon))
        }.sortedWith(WaterName.BY_RANK)
        for ((index, w) in waters.withIndex()) {
            val fields = LinkedHashMap<String, Any?>()
            fields["source"] = WATER_NAMES
            fields["index"] = index.toLong()
            fields["id"] = w.id
            fields["name"] = w.name
            fields["pos"] = w.pos
            fields["prev"] = w.pos
            fields["count"] = w.squareMetres
            fields["kind"] = w.kind
            add(Layer.LABEL, fields, Cells.cellOf(Cells.TileKey(z, x, y), w.pos.lat, w.pos.lon), null, order(1, index), w.pos)
        }

        // the rivers' names along their lines, a river's once a tile, by its length in the tile (source 3)
        val rivers = (byLayer[WorldProfile.RIVER_NAME] ?: emptyList())
            .mapNotNull { f -> f.attrs["name"]?.toString()?.takeIf { it.isNotBlank() }?.let { it to f.geometry } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (name, pieces) -> riverName(name, pieces, z, x, y) }
            .sortedWith(compareByDescending<RiverName> { it.metres }.thenBy { it.name })
        for ((index, r) in rivers.withIndex()) {
            val fields = LinkedHashMap<String, Any?>()
            fields["source"] = RIVER_NAMES
            fields["index"] = index.toLong()
            fields["name"] = r.name
            fields["pos"] = r.pos
            fields["prev"] = r.prev
            fields["count"] = r.metres
            fields["kind"] = WorldProfile.RIVER
            add(Layer.LABEL, fields, Cells.cellOf(Cells.TileKey(z, x, y), r.pos.lat, r.pos.lon), null, order(2, index), r.pos)
        }

        if (out.none { it.layer != Layer.WATER || it.fields["tidal"] != true }) return Cut(null, Cover(worst, worstArea, outside))
        return Cut(RenderTile.of(z, x, y, out), Cover(worst, worstArea, outside))
    }

    /** A place from Planetiler's tile. */
    private class Place(val attrs: Map<String, Any?>, val pos: GridPoint) {
        val kind: String = attrs["kind"].toString()
        val name: String? = attrs["name"]?.toString()
        val id: Long? = (attrs["id"] as? Number)?.toLong()
        val population: Long = WorldProfile.num(attrs["population"]?.toString())?.toLong() ?: 0L

        companion object {
            val BY_RANK: Comparator<Place> = compareBy<Place> { PLACE_RANK.indexOf(it.kind).let { r -> if (r < 0) PLACE_RANK.size else r } }
                .thenBy { (it.attrs["scalerank"] as? Number)?.toInt() ?: 0 }.thenByDescending { it.population }.thenBy { it.id ?: Long.MAX_VALUE }.thenBy { it.name ?: "" }
        }
    }

    /** A water's name from Planetiler's tile (or the globe's): its kind, its id (none for Natural Earth's), its area in m². */
    private class WaterName(val attrs: Map<String, Any?>, val name: String, val pos: GridPoint) {
        val kind: String = attrs["kind"].toString()
        val id: Long? = (attrs["id"] as? Number)?.toLong()
        val squareMetres: Long = (attrs["area"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L

        companion object {
            /** Oceans, seas, bays and straits, lakes and reservoirs, each by area (largest first; a node's none, last). */
            val BY_RANK: Comparator<WaterName> = compareBy<WaterName> { WATER_RANK[it.kind] ?: WATER_RANK.size }
                .thenByDescending { it.squareMetres }.thenBy { it.id ?: Long.MAX_VALUE }.thenBy { it.name }
        }
    }

    /** A river's name in a tile: its point on the river, a point before it along its baseline, its length in the tile (m). */
    private class RiverName(val name: String, val pos: GridPoint, val prev: GridPoint, val metres: Long)

    /**
     * River [name]'s name in tile ([z], [x], [y]) from its [pieces] (Planetiler's lines of that name, in the tile's
     * pixels, its buffer beyond the edges): the pieces cut at the tile's edges, the name at the middle of the longest
     * by length; `prev` [NAME_HALF] before it on the chord from the river's point that far before the middle to its
     * point that far after (a piece shorter than twice that: its ends), so prev → pos is the baseline's direction as a
     * canal name's is; its length the pieces' in the tile, in metres at the name's latitude. Null where none lies in the
     * tile.
     */
    private fun riverName(name: String, pieces: List<Geometry>, z: Int, x: Int, y: Int): RiverName? {
        val box = WorldGeometry.FLOAT.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 256.0, 0.0, 256.0))
        val lines = pieces.flatMap { g ->
            LinearComponentExtracter.getLines(OverlayNGRobust.overlay(g, box, OverlayNG.INTERSECTION)).map { it as LineString }
        }.filter { it.length > 0.0 }
        if (lines.isEmpty()) return null
        val longest = lines.maxBy { it.length }
        val along = LengthIndexedLine(longest)
        val middle = longest.length / 2
        val h = minOf(NAME_HALF, middle)
        val at = along.extractPoint(middle)
        val before = along.extractPoint(middle - h)
        val after = along.extractPoint(middle + h)
        var dx = after.x - before.x
        var dy = after.y - before.y
        if (dx == 0.0 && dy == 0.0) {
            // a loop: its ends meet, so the way it leaves its middle
            val next = along.extractPoint(minOf(longest.length, middle + 1e-3))
            dx = next.x - at.x
            dy = next.y - at.y
        }
        val d = Math.hypot(dx, dy)
        val prevPx = if (d > 0.0) Coordinate(at.x - dx / d * NAME_HALF, at.y - dy / d * NAME_HALF) else at
        fun grid(c: Coordinate) = GridPoint(WorldGeometry.latOfPixel(z, y, c.y).toInt(), WorldGeometry.lonOfPixel(z, x, c.x).toInt())
        val pos = grid(at)
        val tileBox = Grid.tileBox(z, x, y)
        if (pos.lat < tileBox[0] || pos.lat >= tileBox[2] || pos.lon < tileBox[1] || pos.lon >= tileBox[3]) return null
        val metresPerPixel = WorldProfile.EQUATOR_METRES * Math.cos(Math.toRadians(Grid.deg(pos.lat))) / (256.0 * (1 shl z))
        return RiverName(name, pos, grid(prevPx), Math.round(lines.sumOf { it.length } * metresPerPixel))
    }

    /** [g]'s pieces in each cell of [rects], as lines. */
    private inline fun lines(g: Geometry, rects: List<Polygon>, each: (Int, Geom) -> Unit) {
        if (g.isEmpty) return
        for ((i, rect) in rects.withIndex()) {
            if (!rect.envelopeInternal.intersects(g.envelopeInternal)) continue
            for (l in WorldGeometry.lines(WorldGeometry.intersection(g, rect))) each(i, l)
        }
    }

    companion object {
        /** GROUND's source for the land under everything (0 and 1 are the scene's areas and land cover). */
        const val LAND_BASE = 2

        /** The items' layer of areas drawn over the water (the globe's ice shelves): GROUND source 0, the scene's areas'. */
        const val AREAS = "area"

        /** LINE's source for the world's rivers (0 to 9 are the scene's lists). */
        const val RIVERS = 10

        /** The water's ground class for rivers: after the water (0) and the islands (1). */
        const val RIVER_CLASS = 2

        /** The big roads' drawing order: the minor classes under the major ones. */
        val BIG_ORDER = listOf("secondary_link", "primary_link", "primary", "trunk_link", "trunk", "motorway_link", "motorway")

        /** The places' ranks. */
        val PLACE_RANK = listOf("country", "city", "town", "village", "suburb")

        /** LABEL's source for the water's names at their points (0 a canal's name, 1 a place). */
        const val WATER_NAMES = 2

        /** LABEL's source for the rivers' names along their lines. */
        const val RIVER_NAMES = 3

        /** The water's names' ranks by kind: oceans, seas, then bays and straits, then lakes and reservoirs. */
        val WATER_RANK: Map<String, Int> = linkedMapOf(
            WorldProfile.OCEAN to 0, WorldProfile.SEA to 1, WorldProfile.BAY to 2, WorldProfile.STRAIT to 2, WorldProfile.LAKE to 3, WorldProfile.RESERVOIR to 3,
        )

        /** Half a river's name's baseline, tile pixels: 48 dp at the level's start. */
        const val NAME_HALF = 48.0 * WorldProfile.DP

        private fun order(rank: Int, index: Int): Long = (rank.toLong() shl 32) or index.toLong()

        /**
         * The land and the sea of grid box [box] ([south, west, north, east]) as rings, which fill it exactly: where a
         * piece's holes cannot be joined to it, the box is cut in two through the hole by a line of the grid and each half
         * is parted alike, so the land and the sea share every cut.
         */
        fun partition(land: Geometry, box: IntArray): Pair<List<Geom>, List<Geom>> {
            val rect = WorldGeometry.rect(box)
            val inLand = WorldGeometry.intersection(land, rect)
            val inSea = WorldGeometry.difference(rect, land)
            try {
                return WorldGeometry.rings(inLand) to WorldGeometry.rings(inSea)
            } catch (e: WorldGeometry.HoleJoinException) {
                val hole = (0 until e.polygon.numInteriorRing).map { e.polygon.getInteriorRingN(it).envelopeInternal }.maxByOrNull { maxOf(it.width, it.height) }!!
                val (a, b) = when {
                    hole.width >= 2 -> Math.floor((hole.minX + hole.maxX) / 2).toInt().let { cx -> intArrayOf(box[0], box[1], box[2], cx) to intArrayOf(box[0], cx, box[2], box[3]) }
                    hole.height >= 2 -> Math.floor((hole.minY + hole.maxY) / 2).toInt().let { cy -> intArrayOf(box[0], box[1], cy, box[3]) to intArrayOf(cy, box[1], box[2], box[3]) }
                    // a hole within a square unit: the box halved across its longer side
                    box[3] - box[1] >= box[2] - box[0] -> ((box[1].toLong() + box[3]) / 2).toInt().let { cx -> intArrayOf(box[0], box[1], box[2], cx) to intArrayOf(box[0], cx, box[2], box[3]) }
                    else -> ((box[0].toLong() + box[2]) / 2).toInt().let { cy -> intArrayOf(box[0], box[1], cy, box[3]) to intArrayOf(cy, box[1], box[2], box[3]) }
                }
                val (la, sa) = partition(land, a)
                val (lb, sb) = partition(land, b)
                return (la + lb) to (sa + sb)
            }
        }

        /**
         * [poly] cut in two through its widest hole, by a line of the grid strictly inside it (north to south where the
         * hole is two units wide or more, else west to east); a hole too small for either (under a square unit) is
         * filled instead.
         */
        private fun cutThroughHole(poly: Polygon): List<Geom> {
            val env = poly.envelopeInternal
            val holes = (0 until poly.numInteriorRing).map { poly.getInteriorRingN(it) }
            val big = holes.maxByOrNull { maxOf(it.envelopeInternal.width, it.envelopeInternal.height) }!!.envelopeInternal
            if (big.width < 2 && big.height < 2) {
                val kept = holes.filter { it.envelopeInternal.width >= 2 || it.envelopeInternal.height >= 2 }.toTypedArray()
                return polygonRings(WorldGeometry.ON_GRID.createPolygon(poly.exteriorRing, kept))
            }
            val (a, b) = if (big.width >= 2) {
                val cx = Math.floor((big.minX + big.maxX) / 2).toInt()
                intArrayOf(env.minY.toInt(), env.minX.toInt(), env.maxY.toInt(), cx) to intArrayOf(env.minY.toInt(), cx, env.maxY.toInt(), env.maxX.toInt())
            } else {
                val cy = Math.floor((big.minY + big.maxY) / 2).toInt()
                intArrayOf(env.minY.toInt(), env.minX.toInt(), cy, env.maxX.toInt()) to intArrayOf(cy, env.minX.toInt(), env.maxY.toInt(), env.maxX.toInt())
            }
            return polygonRings(WorldGeometry.intersection(poly, WorldGeometry.rect(a))) + polygonRings(WorldGeometry.intersection(poly, WorldGeometry.rect(b)))
        }

        /**
         * The polygons of [g] as rings ([WorldGeometry.rings]); a polygon whose holes cannot be joined to its shell is
         * cut in two through a hole, again until every piece joins.
         */
        fun polygonRings(g: Geometry): List<Geom> = try {
            WorldGeometry.rings(g)
        } catch (e: WorldGeometry.HoleJoinException) {
            val out = ArrayList<Geom>()
            for (p in PolygonExtracter.getPolygons(g)) {
                val poly = p as Polygon
                out += try {
                    WorldGeometry.rings(poly)
                } catch (e2: WorldGeometry.HoleJoinException) {
                    cutThroughHole(poly)
                }
            }
            out
        }
    }
}
