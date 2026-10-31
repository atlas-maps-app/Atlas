package app.atlas.packer.world

import com.onthegomap.planetiler.FeatureCollector
import com.onthegomap.planetiler.FeatureMerge
import com.onthegomap.planetiler.Profile
import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.reader.SourceFeature
import com.onthegomap.planetiler.reader.osm.OsmElement
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature
import app.atlas.rendertiles.model.Grid
import com.onthegomap.planetiler.geo.GeoUtils
import org.locationtech.jts.algorithm.Area
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.util.concurrent.ConcurrentHashMap

/** An OSM element's type. */
enum class OsmType { NODE, WAY, RELATION }

/**
 * What the world's mid-zoom base (L6 to L11, ENGINE.md step 5) makes of one OSM element: the [layer] it is drawn in,
 * its [kind] (a land cover kind, a road's class, an aeroway, a place's kind) and [name] where it carries one, from
 * level [minLevel] to L11. [layer] is one of the profile's layers: `land`, `water`, `road`, `big`, `rail`, `aero`,
 * `river`, `place`.
 */
data class Decision(val layer: String, val kind: String, val minLevel: Int, val name: String? = null)

/**
 * The world's profile (ENGINE.md step 5, the world's mid-zoom base): which OSM elements the levels L6 to L11 draw, and
 * as what, chosen by stage P's own rules from its tag tables ([StagePTags]), so an element is the same kind and class
 * in a world tile as in a country's (the profile against stage P, `:packer`'s WorldParityTest):
 * - land cover by the look's LAND kinds (regions.py's `land_kind`, not a node, not a building, after the coastline and
 *   the parks, which region_features takes first), every level, as its outer rings over 4 m², each whole (regions.py's
 *   `rings_of`: the land cover draws no holes);
 * - water: natural=water and the waterways mapped as areas (amsterdam.py's water, after its buildings), every level;
 *   the sea is the packer's own, outside osmdata's land polygons ([LandBase]);
 * - rivers (waterway=river ways) as lines from L8;
 * - roads by class, each from the level its class first shows: motorways from L6, trunks from L7, primaries from L9,
 *   secondaries from L10, tertiaries at L11 (their links with them; stage P draws no tertiary link). Motorways to
 *   primaries and secondary links are regions.py's big roads (`S['big']`, left out underground), secondaries and
 *   tertiaries amsterdam.py's roads (`S['roads']`, with their class's width as a guess);
 * - main railways (railway=rail with no service tag, a region's `S['rail']`, left out underground) from L9;
 * - runways (regions.py's aeroways) from L10;
 * - places: countries and cities at every level, towns from L8, villages from L10, suburbs at L11 (every node with a
 *   `place` is a place label in stage P), with their name, population, capital and admin_level;
 * - no buildings.
 *
 * Built-up areas are the land cover's residential, commercial, retail and industrial kinds, as in the renders. Every
 * feature is simplified at 0.42 dp at its level's start ([TOLERANCE]) with no minimum size, then same-kind polygons
 * within 1 dp merge and any under 0.3 dp² is dropped ([postProcessLayerFeatures]), the renders' far rule.
 *
 * With an [audit], the decisions on every element whose box meets one of the audit's boxes are kept by element, with the
 * tiles that hold their reference points, for the comparison with stage P ([WorldTiles.audit]).
 */
class WorldProfile(private val tags: StagePTags = StagePTags.STAGE_P, val audit: Audit? = null) : Profile {
    /**
     * The decisions on the elements whose geometry's box meets one of [boxes] (lon/lat), by element, each decision with
     * the [tiles] (level, column, row) that hold its reference point as stage P files a feature (load/Cut.hpp: a ring's
     * box centre, each ring of a multipolygon its own; a line's middle vertex; a node).
     */
    class Audit(val boxes: List<Envelope>, val tiles: List<IntArray> = emptyList()) {
        /**
         * An element's decisions and, for each, its reference points (lon/lat) and the tiles that hold them, as their
         * indices in [tiles]; its geometry's box (lon/lat) and its land rings' areas (m²).
         */
        class Audited(val decisions: List<Decision>, val points: List<List<Coordinate>>, val meets: List<List<Int>>, val box: Envelope, val landAreas: List<Double>)

        val elements: MutableMap<Pair<OsmType, Long>, Audited> = ConcurrentHashMap()

        internal fun record(type: OsmType, id: Long, decisions: List<Decision>, sf: SourceFeature, land: Geometry?) {
            val g = runCatching { sf.latLonGeometry() }.getOrNull() ?: return
            if (boxes.none { it.intersects(g.envelopeInternal) }) return
            val points = decisions.map { d ->
                when (d.layer) {
                    LAND -> rings(GeoUtils.worldToLatLonCoords(land!!))
                    WATER -> rings(g)
                    PLACE -> listOf(g.coordinate)
                    else -> runCatching { GeoUtils.worldToLatLonCoords(sf.line()).coordinates }.getOrNull()?.let { listOf(it[it.size / 2]) }.orEmpty()
                }
            }
            val meets = points.map { ps ->
                tiles.indices.filter { i ->
                    val (z, x, y) = tiles[i]
                    ps.any { p -> Grid.col(Grid.round(p.x), z) == x && Grid.row(Grid.round(p.y), z) == y }
                }
            }
            val areas = if (land == null) emptyList() else PolygonExtracter.getPolygons(land).map { squareMetres((it as Polygon).exteriorRing.coordinates) }
            elements[type to id] = Audited(decisions, points, meets, g.envelopeInternal, areas)
        }

        /** The box centres of [g]'s polygons' outer rings. */
        private fun rings(g: Geometry): List<Coordinate> = PolygonExtracter.getPolygons(g).map { (it as Polygon).exteriorRing.envelopeInternal.centre() }
    }

    override fun name(): String = "atlas-world"

    override fun description(): String = "Atlas's world mid-zoom base (ENGINE.md step 5)"

    override fun attribution(): String = "© OpenStreetMap contributors"

    override fun isOverlay(): Boolean = false

    override fun processFeature(sf: SourceFeature, fc: FeatureCollector) {
        if (sf.tags().isEmpty()) return
        val element = (sf as? OsmSourceFeature)?.originalElement() ?: return
        val type = when (element) {
            is OsmElement.Node -> OsmType.NODE
            is OsmElement.Relation -> OsmType.RELATION
            else -> OsmType.WAY
        }
        val t = TagView { k -> sf.getTag(k)?.toString() }
        // amsterdam.py's `pts[0] == pts[-1]`: the way's first and last nodes the same
        val closed = element is OsmElement.Way && element.nodes().size() > 1 && element.nodes().get(0) == element.nodes().get(element.nodes().size() - 1)
        var decisions = decide(t, type, closed, sf.canBePolygon(), sf.canBeLine())
        if (decisions.isEmpty()) return
        // regions.py's land cover: the element's outer rings over 4 m², whole
        val land = if (decisions.any { it.layer == LAND }) landRings(sf) else null
        if (land == null) decisions = decisions.filter { it.layer != LAND }
        if (decisions.isEmpty()) return
        audit?.record(type, element.id(), decisions, sf, land)
        for (d in decisions) emit(d, sf, fc, element.id(), t, land)
    }

    /**
     * regions.py's land rings of [sf] (`rings_of(e)`: a closed way, or a multipolygon's outer rings, each kept where it
     * is over 4 m²), each whole, as the land cover draws no holes (a clearing mapped as a forest's inner ring is forest
     * in the renders), in world coordinates; null for none.
     */
    private fun landRings(sf: SourceFeature): Geometry? {
        val g = runCatching { sf.polygon() }.getOrNull() ?: return null
        val shells = PolygonExtracter.getPolygons(g).map { (it as Polygon).exteriorRing }.filter { squareMetres(it.coordinates) > 4.0 }
        if (shells.isEmpty()) return null
        val polygons = shells.map { g.factory.createPolygon(it.coordinates) }
        return polygons.singleOrNull() ?: UnaryUnionOp.union(polygons)
    }

    private fun emit(d: Decision, sf: SourceFeature, fc: FeatureCollector, id: Long, t: TagView, land: Geometry?) {
        val f = when (d.layer) {
            LAND -> fc.geometry(LAND, land!!).setAttr("kind", d.kind)
            WATER -> fc.polygon(WATER)
            ROAD, BIG -> fc.line(d.layer).setAttr("hw", d.kind)
            RAIL -> fc.line(RAIL)
            AERO -> fc.line(AERO).setAttr("kind", d.kind)
            RIVER -> fc.line(RIVER)
            PLACE -> fc.point(PLACE).setAttr("kind", d.kind).setAttr("name", d.name).setAttr("id", id)
                .setAttr("population", t["population"]).setAttr("capital", t["capital"]).setAttr("admin_level", t["admin_level"])
            else -> throw IllegalStateException("no layer ${d.layer}")
        }
        f.setZoomRange(d.minLevel, MAX_LEVEL).setPixelToleranceAtAllZooms(TOLERANCE).setMinPixelSizeAtAllZooms(0).setBufferPixels(BUFFER.toDouble())
    }

    /**
     * What the world makes of an element with tags [t]: [type], whether it is a closed way ([closed]), and whether it
     * can be an area ([area]: a closed way, or a multipolygon) or a line ([line]: a way). Stage P's chains in their
     * order: amsterdam.py's (its buildings, then water, roads and place nodes) and regions.py's region_features (the
     * coastline and the parks, then land cover, aeroways, big roads and railways), each the first that takes the element.
     */
    fun decide(t: TagView, type: OsmType, closed: Boolean, area: Boolean, line: Boolean): List<Decision> {
        val out = ArrayList<Decision>(2)
        val node = type == OsmType.NODE
        val way = type == OsmType.WAY
        // amsterdam.py's chain
        run {
            if ((t.has("building") || t.has("building:part")) && !node && !t.isValue("building", "no")) return@run
            if ((t.isValue("natural", "water") || t["waterway"] in tags.waterWays) && !node) {
                if (area) out += Decision(WATER, "water", MIN_LEVEL)
                return@run
            }
            if (way && t.has("highway")) {
                val hw = t["highway"]!!
                if (hw !in tags.roads) return@run
                if (closed && (t.isValue("area", "yes") || hw == "pedestrian")) return@run
                if (line) roadLevel(hw)?.let { out += Decision(ROAD, hw, it) }
                return@run
            }
            if (node) {
                val kind = t["place"] ?: return@run
                val level = PLACE_LEVELS[kind] ?: return@run
                out += Decision(PLACE, kind, level, t["name"])
            }
        }
        // regions.py's region_features
        run {
            if (t.isValue("natural", "coastline") && way) return@run
            if (t.isValue("boundary", "national_park")) return@run
            landKind(t)?.let { k ->
                if (!node && !t.truthy("building")) {
                    if (area) out += Decision(LAND, k, MIN_LEVEL)
                    return@run
                }
            }
            val aeroway = t["aeroway"]
            if (way && aeroway in tags.aero) {
                if (aeroway == "runway" && line) out += Decision(AERO, aeroway, RUNWAY_LEVEL)
                return@run
            }
            val underground = t["tunnel"] in tags.tunnels || (num(t["layer"]) ?: 0.0) < 0
            val hw = t["highway"]
            if (way && hw in tags.bigRoads) {
                if (!underground && line) roadLevel(hw!!)?.let { out += Decision(BIG, hw, it) }
                return@run
            }
            val crane = t.isValue("man_made", "crane_rail") || t.isValue("railway", "crane")
            val railway = t["railway"]
            if (way && (railway in tags.rail || crane)) {
                if (!underground && line && !crane && railway == "rail" && !t.has("service")) out += Decision(RAIL, "rail", RAIL_LEVEL)
                return@run
            }
        }
        // the world's own: rivers, which no region draws as lines
        if (way && line && t.isValue("waterway", "river")) out += Decision(RIVER, "river", RIVER_LEVEL)
        return out
    }

    /** regions.py's `land_kind(t)`. */
    fun landKind(t: TagView): String? {
        if (t.isValue("aeroway", "apron")) return "apron"
        if (t.isValue("amenity", "parking") && !t.isValue("parking", "underground")) return "parking"
        t["leisure"]?.let { if (it in tags.landLeisure) return it }
        if (t.isValue("industrial", "port") || t.isValue("landuse", "port")) return "port"
        t["natural"]?.let { if (it in tags.land) return it }
        t["landuse"]?.let { if (it in tags.land) return it }
        return null
    }

    /**
     * Same-kind polygons within 1 dp merged and any under 0.3 dp² dropped; lines of a class joined end to end and
     * simplified again, as the tiles are drawn whole.
     */
    override fun postProcessLayerFeatures(layer: String, zoom: Int, items: List<VectorTile.Feature>): List<VectorTile.Feature> = when (layer) {
        LAND, WATER -> FeatureMerge.mergeNearbyPolygons(items, MIN_AREA, MIN_AREA, MERGE_DISTANCE, MERGE_BUFFER)
        ROAD, BIG, RAIL, AERO, RIVER -> FeatureMerge.mergeLineStrings(items, 0.0, TOLERANCE, BUFFER.toDouble())
        else -> items
    }

    companion object {
        const val LAND = "land"
        const val WATER = "water"
        const val ROAD = "road"
        const val BIG = "big"
        const val RAIL = "rail"
        const val AERO = "aero"
        const val RIVER = "river"
        const val PLACE = "place"

        /** The world's mid-zoom levels. */
        const val MIN_LEVEL = 6
        const val MAX_LEVEL = 11

        /**
         * A level starts where its tiles are about 910 dp wide (as L12's are at 9.4 km), so a dp is 256 / 910 of a
         * tile's pixels there.
         */
        const val DP = 256.0 / 910.0

        /** The renders' 0.42 css px, in tile pixels at a level's start: 6 m at L11, 190 m at L6 (at 52° N). */
        const val TOLERANCE = 0.42 * DP

        /** Same-kind polygons within 1 dp merge (half of it closes the gap from each side). */
        const val MERGE_DISTANCE = 1.0 * DP
        const val MERGE_BUFFER = 0.5 * DP

        /** The renders' far rule: a polygon under 0.3 dp² is not drawn. */
        const val MIN_AREA = 0.3 * DP * DP

        /** Planetiler's tiles keep this many pixels beyond their edges (the packer cuts them at the edge again). */
        const val BUFFER = 4

        const val RAIL_LEVEL = 9
        const val RUNWAY_LEVEL = 10
        const val RIVER_LEVEL = 8

        /** The level each road class first shows at, its links with it; null for the classes the world leaves out. */
        fun roadLevel(hw: String): Int? = when (hw) {
            "motorway", "motorway_link" -> 6
            "trunk", "trunk_link" -> 7
            "primary", "primary_link" -> 9
            "secondary", "secondary_link" -> 10
            "tertiary" -> 11
            else -> null
        }

        /** The place kinds the world draws and the level each first shows at. */
        val PLACE_LEVELS: Map<String, Int> = linkedMapOf("country" to 6, "city" to 6, "town" to 8, "village" to 10, "suburb" to 11)

        /**
         * The area of ring [c] (world coordinates) in square metres as stage P measures a ring in its frame (its frame's
         * 111,320 m a degree of longitude at its latitude, 110,540 m a degree of latitude), at the ring's own latitude.
         */
        fun squareMetres(c: Array<Coordinate>): Double {
            val lat = Math.toRadians(GeoUtils.getWorldLat((c.minOf { it.y } + c.maxOf { it.y }) / 2))
            val cos = Math.cos(lat)
            // a world unit is 360° of longitude, and 360° · cos(lat) of latitude where the Mercator's scale is 1 / cos(lat)
            return Math.abs(Area.ofRingSigned(c)) * 360.0 * 360.0 * cos * 111_320.0 * cos * 110_540.0
        }

        /** real.py's `num(v)`: the number a tag's value starts with (`\s*(-?\d+(?:\.\d+)?)`), or null. */
        fun num(v: String?): Double? {
            if (v == null) return null
            val m = NUM.find(v) ?: return null
            val digits = StringBuilder()
            for (c in m.groupValues[1]) digits.append(if (c == '-' || c == '.') c else Character.forDigit(Character.digit(c, 10), 10))
            return digits.toString().toDouble()
        }

        private val NUM = Regex("(?U)^\\s*(-?\\d+(?:\\.\\d+)?)")
    }
}

/** An element's tags as stage P reads them. */
fun interface TagView {
    operator fun get(key: String): String?

    fun has(key: String): Boolean = get(key) != null

    fun isValue(key: String, value: String): Boolean = get(key) == value

    /** `bool(t.get(key))`: there, and not empty. */
    fun truthy(key: String): Boolean = !get(key).isNullOrEmpty()
}
