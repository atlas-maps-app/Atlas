package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.math.sqrt

/**
 * Restrictions from OpenStreetMap through the Overpass API. [query] runs Overpass QL and returns the JSON text, throwing an
 * [IOException] when the server fails (the app plugs in its HTTP client or the providers module); this class builds the
 * queries and reads the answers. Long corridors are asked for in pieces of at most [pieceMetres], one query each, so no
 * single query gets heavy, and a failed query is tried again [attempts] times in all, waiting longer each time: the public
 * servers often answer 504 or 429 when busy.
 *
 * Nothing is asked where [mayAsk] says the network is not to be had (offline), and a failure asking again cannot cure
 * ([retryable] says no: the user switched the servers off, or the query is refused) is not asked again: the route then
 * comes at once, unchecked, where it waited six seconds a piece for nothing. The whole check ends after [deadlineMillis],
 * the route unchecked: a lorry's route waited about twenty minutes where every server was stuck (30 October 2026, the user:
 * "are all sources reliable and respond fast?").
 */
class OverpassRestrictionSource(
    private val query: suspend (String) -> String,
    /** Road limits, water limits, or both. */
    private val scope: Scope = Scope.ROAD,
    private val pieceMetres: Double = 30_000.0,
    private val attempts: Int = 3,
    private val retryDelayMillis: Long = 2_000,
    /** Whether the servers may be asked now (the app: the phone is online). */
    private val mayAsk: () -> Boolean = { true },
    /** Whether a query that failed so may be asked again. */
    private val retryable: (IOException) -> Boolean = { true },
    /** The longest the whole check takes, all its pieces and tries. */
    private val deadlineMillis: Long = DEADLINE_MS,
) : RestrictionSource {

    enum class Scope { ROAD, WATER, BOTH }

    init {
        require(pieceMetres > 0) { "pieceMetres must be positive" }
    }

    override suspend fun restrictionsAlong(corridor: Corridor): List<Restriction> {
        if (corridor.line.isEmpty()) return emptyList()
        if (!mayAsk()) throw IOException("offline: the limits along the route were not checked")
        return withTimeoutOrNull(deadlineMillis) { along(corridor) }
            ?: throw IOException("the limits along the route did not come in ${deadlineMillis / 1000} s")
    }

    private suspend fun along(corridor: Corridor): List<Restriction> {
        val seen = HashSet<String>()
        val out = ArrayList<Restriction>()
        for (piece in OverpassQl.pieces(corridor.line, pieceMetres, corridor.bufferMetres)) {
            for (r in ask(OverpassQl.restrictions(Corridor(piece, corridor.bufferMetres), scope))) {
                // one element can limit both boats and trucks (a canal bridge): those are two restrictions with one id
                if (r.id == null || seen.add((if (r.water) "water " else "road ") + r.id)) out += r
            }
        }
        return out
    }

    /** Runs [ql] and reads the answer, asking again when the server fails or answers with a runtime error. */
    private suspend fun ask(ql: String): List<Restriction> {
        var wait = retryDelayMillis
        for (attempt in 1 until attempts.coerceAtLeast(1)) {
            try {
                return OverpassQl.parse(query(ql), scope)
            } catch (e: IOException) {
                if (!retryable(e) || !mayAsk()) throw e
                delay(wait)
                wait *= 2
            }
        }
        return OverpassQl.parse(query(ql), scope)
    }

    companion object {
        /** The longest a route's whole check takes: four minutes (a long route's pieces answer in seconds each). */
        const val DEADLINE_MS = 4 * 60 * 1000L
    }
}

/** Builds the Overpass QL for a route corridor and reads its answer into [Restriction]s. */
object OverpassQl {

    /** Overpass `around` filters get slow with long lines; the corridor is simplified to this many metres first. */
    private const val SIMPLIFY_METRES = 10.0
    private const val MAX_AROUND_POINTS = 400

    /**
     * The query for everything along [corridor] that limits road vehicles and/or boats, with geometry. The expensive
     * `around` filter runs once per kind of way (roads, waterways, bridges); the limits are then picked from those sets,
     * and point limits from the nodes of those ways.
     */
    fun restrictions(corridor: Corridor, scope: OverpassRestrictionSource.Scope): String {
        val simple = simplified(corridor.line, maxOf(SIMPLIFY_METRES, corridor.bufferMetres / 3), MAX_AROUND_POINTS)
        // five decimals are a metre: plenty for a corridor of metres, and the query stays short
        val coords = simple.points.joinToString(",") { String.format(Locale.ROOT, "%.5f,%.5f", it.lat, it.lon) }
        // the simplified line strays up to maxOffset from the route: widen the corridor by as much, so nothing is missed
        val radius = kotlin.math.ceil(corridor.bufferMetres + simple.maxOffset - 1e-6)
        val around = String.format(Locale.ROOT, "(around:%.0f,%s)", radius, coords)
        val road = scope != OverpassRestrictionSource.Scope.WATER
        val water = scope != OverpassRestrictionSource.Scope.ROAD
        val sb = StringBuilder()
        sb.append("[out:json][timeout:90];\n")
        if (road) sb.append("way[\"highway\"]$around->.roads;\n")
        if (water) {
            sb.append("way[\"waterway\"]$around->.water;\n")
            sb.append("way[\"bridge\"]$around->.bridges;\n")
        }
        sb.append("(\n")
        if (road) {
            sb.append("  way.roads[~\"^(${ROAD_KEYS.joinToString("|")})$\"~\".\"];\n")
            sb.append("  way.roads[\"hgv\"~\"^(${HGV_LIMITING.joinToString("|")})$\"];\n")
            sb.append("  way.roads[\"hazmat\"=\"no\"];\n")
            sb.append("  way.roads[~\"^hazmat:(water|[BCDE])$\"~\"^no$\"];\n")
            sb.append("  node(w.roads)[\"barrier\"=\"height_restrictor\"];\n")
            sb.append("  node(w.roads)[\"maxheight\"];\n")
        }
        if (water) {
            sb.append("  way.water[~\"^(maxheight|maxwidth|maxlength|maxdraught|maxdraft|draft)$\"~\".\"];\n")
            sb.append("  way.water[\"lock\"=\"yes\"];\n")
            sb.append("  node(w.water)[\"waterway\"=\"lock_gate\"];\n")
            sb.append("  node[\"seamark:type\"=\"bridge\"]$around;\n")
            // bridges over the water, for their clearance where it is mapped on the bridge
            sb.append("  way.bridges[\"seamark:bridge:clearance_height\"];\n")
            sb.append("  way.bridges[\"maxheight\"][\"highway\"!~\".\"][\"railway\"!~\".\"];\n")
        }
        sb.append(");\nout tags geom;\n")
        return sb.toString()
    }

    /**
     * Cuts [line] into pieces of at most [maxMetres], each overlapping the next by [overlapMetres] so that nothing at a
     * cut is missed.
     */
    fun pieces(line: List<LatLon>, maxMetres: Double, overlapMetres: Double = 0.0): List<List<LatLon>> {
        if (line.size < 2) return listOf(line)
        val poly = app.atlas.geo.Polyline(line)
        if (poly.length <= maxMetres) return listOf(line)
        val count = kotlin.math.ceil(poly.length / maxMetres).toInt()
        val step = poly.length / count
        return List(count) { k ->
            val from = maxOf(0.0, k * step - overlapMetres)
            val to = minOf(poly.length, (k + 1) * step + overlapMetres)
            poly.slice(from, to).points
        }
    }

    private val ROAD_KEYS = listOf(
        "maxheight", "maxheight:physical", "maxweight", "maxweightrating", "maxweight:hgv", "maxweightrating:hgv",
        "maxwidth", "maxwidth:physical", "maxlength", "maxlength:hgv", "maxaxleload",
    )

    /**
     * Reads an Overpass JSON answer (`out tags geom`). Elements without a usable limit are skipped; [scope] keeps the
     * road limits, the water limits or both (a canal bridge can be both: a weight limit on it, a clearance under it).
     */
    fun parse(json: String, scope: OverpassRestrictionSource.Scope = OverpassRestrictionSource.Scope.BOTH): List<Restriction> {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw IOException("Overpass answered with something other than JSON: ${json.take(120)}")
        // a query that times out or runs out of memory still answers 200, with what it had so far and a remark
        val remark = (root["remark"] as? JsonPrimitive)?.contentOrNull
        if (remark != null && remark.contains("runtime error")) throw IOException("Overpass: $remark")
        val elements = root["elements"] as? JsonArray ?: throw IOException("Overpass answered without elements")
        val out = ArrayList<Restriction>()
        for (e in elements) {
            val o = e as? JsonObject ?: continue
            val type = o["type"]?.jsonPrimitive?.content ?: continue
            val id = o["id"]?.jsonPrimitive?.longOrNull
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val geometry: List<LatLon> = when (type) {
                "node" -> {
                    val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                    val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                    listOf(LatLon(lat, lon))
                }
                "way" -> (o["geometry"] as? JsonArray)?.mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    val lat = po["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val lon = po["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    LatLon(lat, lon)
                } ?: continue
                else -> continue
            }
            if (geometry.isEmpty()) continue
            val osmId = id?.let { OsmId(if (type == "node") OsmType.NODE else OsmType.WAY, it) }
            if (scope != OverpassRestrictionSource.Scope.WATER) roadRestriction(osmId, geometry, tags)?.let { out += it }
            if (scope != OverpassRestrictionSource.Scope.ROAD) waterRestriction(osmId, geometry, tags)?.let { out += it }
        }
        return out
    }

    /** The limits one element sets for boats: a waterway's own limits, a lock, or the clearance under a bridge; null if none. */
    fun waterRestriction(id: OsmId?, geometry: List<LatLon>, tags: Map<String, String>): Restriction? {
        val waterway = tags["waterway"]
        val isWaterWay = waterway != null && waterway != "lock_gate" && tags["highway"] == null
        val seamarkBridge = tags["seamark:type"] == "bridge" || tags["seamark:bridge:clearance_height"] != null
        // a bridge way crosses the route (a road or rail bridge only counts when it carries a clearance for boats)
        val bridgeOverWater = !isWaterWay && tags["bridge"] != null && tags["bridge"] != "no" &&
            (seamarkBridge || (tags["highway"] == null && tags["railway"] == null))
        if (!isWaterWay && !seamarkBridge && !bridgeOverWater && waterway != "lock_gate") return null
        val clearance = OsmUnits.metres(tags["seamark:bridge:clearance_height"])
            ?: OsmUnits.metres(tags["seamark:bridge:clearance_height_closed"])
            ?: if (!seamarkBridge && bridgeOverWater) OsmUnits.metres(tags["maxheight"]) else null
        val road = tags["highway"] != null || tags["railway"] != null
        val r = Restriction(
            id = id, geometry = geometry,
            maxHeight = if (isWaterWay) OsmUnits.metres(tags["maxheight"]) else null,
            // a road bridge's maxwidth and maxlength are for the traffic on it
            maxWidth = if (road) null else OsmUnits.metres(tags["maxwidth"]),
            maxLength = if (road) null else OsmUnits.metres(tags["maxlength"]),
            bridgeClearance = clearance,
            maxDraught = OsmUnits.metres(tags["maxdraught"]) ?: OsmUnits.metres(tags["maxdraft"]) ?: OsmUnits.metres(tags["draft"]),
            maxBeam = OsmUnits.metres(tags["seamark:bridge:clearance_width"]),
            crossing = bridgeOverWater && geometry.size > 1,
            water = true,
            tags = tags,
        )
        val hasLimit = r.maxHeight != null || r.maxWidth != null || r.maxLength != null || r.bridgeClearance != null ||
            r.maxDraught != null || r.maxBeam != null || tags["lock"] == "yes" || waterway == "lock_gate"
        return if (hasLimit) r else null
    }

    /** The limits one element sets for road vehicles (height, weight, size, hazmat, hgv access); null if none. */
    fun roadRestriction(id: OsmId?, geometry: List<LatLon>, tags: Map<String, String>): Restriction? {
        val waterway = tags["waterway"]
        if (waterway != null && tags["highway"] == null) return null
        if (tags["highway"] == null && tags["barrier"] == null && geometry.size > 1) return null
        val height = lowest(OsmUnits.metres(tags["maxheight"]), OsmUnits.metres(tags["maxheight:physical"]))
        val weight = lowest(
            OsmUnits.tonnes(tags["maxweight"]), OsmUnits.tonnes(tags["maxweightrating"]),
            OsmUnits.tonnes(tags["maxweight:hgv"]), OsmUnits.tonnes(tags["maxweightrating:hgv"]),
        )
        val width = lowest(OsmUnits.metres(tags["maxwidth"]), OsmUnits.metres(tags["maxwidth:physical"]))
        val length = lowest(OsmUnits.metres(tags["maxlength"]), OsmUnits.metres(tags["maxlength:hgv"]))
        val axle = OsmUnits.tonnes(tags["maxaxleload"])
        val hazmatBanned = tags["hazmat"] == "no" || tags["hazmat:water"] == "no" || HAZMAT_TUNNEL.any { tags[it] == "no" }
        val hgv = tags["hgv"]?.takeIf { it in HGV_LIMITING }
        if (height == null && weight == null && width == null && length == null && axle == null && !hazmatBanned && hgv == null) return null
        return Restriction(
            id = id, geometry = geometry, maxHeight = height, maxWeight = weight, maxWidth = width, maxLength = length,
            maxAxleLoad = axle, hazmatAllowed = !hazmatBanned, hgv = hgv, tags = tags,
        )
    }

    private val HAZMAT_TUNNEL = listOf("hazmat:B", "hazmat:C", "hazmat:D", "hazmat:E")
    private val HGV_LIMITING = setOf("no", "destination", "delivery", "local", "private", "agricultural", "forestry")

    private fun lowest(vararg v: Double?): Double? = v.filterNotNull().minOrNull()

    /** A simplified line and how far, at most, the original strays from it (metres). */
    internal class Simplified(val points: List<LatLon>, val maxOffset: Double)

    /** Douglas–Peucker in a local flat frame, then thinned to at most [maxPoints]. */
    internal fun simplify(points: List<LatLon>, tolerance: Double, maxPoints: Int): List<LatLon> = simplified(points, tolerance, maxPoints).points

    /**
     * Douglas–Peucker in a local flat frame, measuring the distance to each segment (not to its infinite line, which would
     * drop a stretch that runs on past a segment's end and comes back); the tolerance doubles until at most [maxPoints] remain.
     */
    internal fun simplified(points: List<LatLon>, tolerance: Double, maxPoints: Int): Simplified {
        if (points.size <= 2) return Simplified(points, 0.0)
        val n = points.size
        val f = LocalFrame(points[n / 2])
        val xs = DoubleArray(n) { f.x(points[it].lon) }
        val ys = DoubleArray(n) { f.y(points[it].lat) }
        var tol = tolerance
        while (true) {
            val keep = BooleanArray(n)
            keep[0] = true; keep[n - 1] = true
            var maxOffset = 0.0
            val stack = ArrayDeque<Long>()
            stack.addLast(0L shl 32 or (n - 1).toLong())
            while (stack.isNotEmpty()) {
                val ab = stack.removeLast()
                val a = (ab ushr 32).toInt()
                val b = ab.toInt()
                var worst = -1
                var worstD = -1.0
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
                val len2 = dx * dx + dy * dy
                for (i in a + 1 until b) {
                    val t = if (len2 == 0.0) 0.0 else (((xs[i] - xs[a]) * dx + (ys[i] - ys[a]) * dy) / len2).coerceIn(0.0, 1.0)
                    val ex = xs[a] + dx * t - xs[i]; val ey = ys[a] + dy * t - ys[i]
                    val d = sqrt(ex * ex + ey * ey)
                    if (d > worstD) {
                        worstD = d; worst = i
                    }
                }
                if (worst < 0) continue
                if (worstD > tol) {
                    keep[worst] = true
                    stack.addLast(a.toLong() shl 32 or worst.toLong()); stack.addLast(worst.toLong() shl 32 or b.toLong())
                } else if (worstD > maxOffset) {
                    maxOffset = worstD
                }
            }
            val out = points.filterIndexed { i, _ -> keep[i] }
            if (out.size <= maxPoints) return Simplified(out, maxOffset)
            tol *= 2
        }
    }
}

/** Reads OpenStreetMap length and weight values with their units: metres and tonnes by default. */
object OsmUnits {
    private val FEET_INCHES = Regex("""^\s*(\d+(?:\.\d+)?)\s*(?:'|ft|feet)\s*(?:(\d+(?:\.\d+)?)\s*(?:"|in|inch|inches))?\s*$""")
    private val NUMBER_UNIT = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*([a-zA-Z"']*)\s*$""")

    /** Metres from "4.2", "4,2 m", "14'6\"", "13 ft", "350 cm"; null for "default", "none", "below_default" and the like. */
    fun metres(value: String?): Double? {
        val v = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (v.isEmpty() || v == "default" || v == "none" || v == "below_default" || v == "unsigned" || v == "no") return null
        FEET_INCHES.matchEntire(v)?.let { m ->
            val feet = m.groupValues[1].toDouble()
            val inches = m.groupValues[2].toDoubleOrNull() ?: 0.0
            return feet * 0.3048 + inches * 0.0254
        }
        val m = NUMBER_UNIT.matchEntire(v) ?: return null
        val num = m.groupValues[1].replace(',', '.').toDouble()
        return when (m.groupValues[2]) {
            "", "m" -> num
            "cm" -> num / 100
            "mm" -> num / 1000
            "in", "\"" -> num * 0.0254
            "ft", "'" -> num * 0.3048
            "km" -> num * 1000
            "mi" -> num * 1609.344
            "nmi" -> num * 1852
            else -> null
        }
    }

    /** Tonnes from "7.5", "7,5 t", "3500 kg", "12 st" (short tons), "20000 lbs"; null when unreadable. */
    fun tonnes(value: String?): Double? {
        val v = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (v.isEmpty() || v == "default" || v == "none" || v == "unsigned" || v == "no") return null
        val m = NUMBER_UNIT.matchEntire(v) ?: return null
        val num = m.groupValues[1].replace(',', '.').toDouble()
        return when (m.groupValues[2]) {
            "", "t" -> num
            "kg" -> num / 1000
            "st" -> num * 0.90718474
            "lt" -> num * 1.0160469
            "lbs", "lb" -> num * 0.00045359237
            else -> null
        }
    }
}
