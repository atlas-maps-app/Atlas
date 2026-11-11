package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/** The EU's class of a bathing water over its last four seasons, best first; or not classed yet (a new one). */
enum class WaterQuality { EXCELLENT, GOOD, SUFFICIENT, POOR, UNCLASSIFIED }

/** A swimming advisory in force at a swim spot: a warning (blue-green algae, say), advice not to swim, a ban, a check. */
enum class SwimAdvisory { WARNING, ADVISED_AGAINST, BANNED, INVESTIGATING }

/** What kind of water a bathing water is, as the EU reports it. */
enum class WaterKind { LAKE, RIVER, COASTAL, TRANSITIONAL, OTHER }

/**
 * One official bathing water or swim spot: its id ("NLBW92_DIEMPK"), name, where it is, its kind of water, the EU's
 * class for the last [season] reported, and an advisory in force where the country publishes them live. [area] is the
 * spot's outline where the source draws one.
 */
data class BathingSite(
    val id: String,
    val name: String,
    val location: LatLon,
    val kind: WaterKind,
    val quality: WaterQuality?,
    val season: Int?,
    val advisory: SwimAdvisory? = null,
    val area: BBox? = null,
    /** "eea" for the EU's register, "zwemwater" for a Dutch spot only the provinces' register holds. */
    val source: String = EEA,
) {
    /** Safe to swim as far as the registers say: no advisory against it and not classed poor. */
    val swimmable: Boolean get() = advisory != SwimAdvisory.ADVISED_AGAINST && advisory != SwimAdvisory.BANNED && quality != WaterQuality.POOR

    companion object {
        const val EEA = "eea"
        const val ZWEMWATER = "zwemwater"
    }
}

/**
 * The official bathing waters around a place (the user, 3 November: "If osm doesn't cover these and theres another
 * useful source this one gets queried to highlight swimming lakes around you"): every EU bathing water with its
 * quality class, from the European Environment Agency's map service (each season a service of its own, the newest
 * found once a week), and in the Netherlands the provinces' swim register live, with the advisories in force
 * (a warning, advice against swimming, a ban). Neither needs a key.
 */
class BathingWaters(
    private val http: Http,
    private val eea: String = EEA_BASE,
    private val zwemwater: String = ZWEMWATER_BASE,
) : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    private val sites = Lru<String, List<BathingSite>>(64, ttlMs = 24 * 3600_000L)
    private val advisories = Lru<String, List<BathingSite>>(64, ttlMs = 3600_000L)
    private var season: Pair<Int, Long>? = null

    /**
     * The bathing waters within [radiusM] of [at], nearest first: the EU's, each with the Dutch advisory in force
     * where one lies at it, and the Dutch spots the EU does not list.
     */
    suspend fun near(at: LatLon, radiusM: Double): List<BathingSite> {
        val r = radiusM.coerceIn(500.0, MAX_RADIUS_M)
        val key = "${(at.lat * 100).roundToInt()},${(at.lon * 100).roundToInt()},${r.roundToInt()}"
        val eu = sites.get(key) ?: euSites(at, r).also { sites.put(key, it) }
        val nl = if (inNetherlands(at)) (advisories.get(key) ?: dutchSpots(at, r).also { advisories.put(key, it) }) else emptyList()
        return merge(eu, nl).filter { Geo.distance(at, it.location) <= r }.sortedBy { Geo.distance(at, it.location) }
    }

    /** The EU's sites within [r] of [at], from the newest season's service. */
    private suspend fun euSites(at: LatLon, r: Double): List<BathingSite> {
        val year = newestSeason()
        val url = "$eea/BathingWater/BathingWater_Dyna_WM_$year/MapServer/$POINT_LAYER/query".toHttpUrl().newBuilder()
            .addQueryParameter("where", "1=1")
            .addQueryParameter("geometry", String.format(Locale.ROOT, "%.6f,%.6f", at.lon, at.lat))
            .addQueryParameter("geometryType", "esriGeometryPoint")
            .addQueryParameter("inSR", "4326")
            .addQueryParameter("distance", r.roundToInt().toString())
            .addQueryParameter("units", "esriSRUnit_Meter")
            .addQueryParameter("outFields", "bathingWaterIdentifier,bathingWaterName,bwWaterCategory,latitude,longitude,qualityStatus")
            .addQueryParameter("returnGeometry", "false")
            .addQueryParameter("resultRecordCount", MAX_SITES.toString())
            .addQueryParameter("f", "json")
            .build()
        val json = http.execute(http.request(url).build()).requireOk().json().asObject() ?: return emptyList()
        // ArcGIS answers a missing service with 200 and an error inside
        json.obj("error")?.let { throw ProviderException(ID, "EEA bathing water: ${it.str("message") ?: it.dbl("code")}") }
        return parseEu(json, year)
    }

    /** The newest season the EEA has a service for: listed once a week, the last year's on failing to list. */
    private suspend fun newestSeason(): Int {
        season?.let { (y, at) -> if (System.currentTimeMillis() - at < WEEK_MS) return y }
        val listed = runCatching {
            val json = http.execute(http.request("$eea/BathingWater?f=json").build()).requireOk().json().asObject()
            json?.arr("services")?.mapNotNull { s -> s.asObject()?.str("name")?.let { SEASON.find(it)?.groupValues?.get(1)?.toIntOrNull() } }?.maxOrNull()
        }.getOrNull()
        val year = listed ?: FALLBACK_SEASON
        season = year to System.currentTimeMillis()
        return year
    }

    /** The Dutch register's swim spots within [r] of [at], with their outlines and advisories. */
    private suspend fun dutchSpots(at: LatLon, r: Double): List<BathingSite> {
        val dLat = r / METRES_PER_DEGREE
        val dLon = r / (METRES_PER_DEGREE * cos(Math.toRadians(at.lat)).coerceAtLeast(0.05))
        val url = "$zwemwater/geoserver/zwr-wms/wfs".toHttpUrl().newBuilder()
            .addQueryParameter("service", "WFS")
            .addQueryParameter("version", "1.0.0")
            .addQueryParameter("request", "GetFeature")
            .addQueryParameter("typeName", "zwr-wms:zwemplek")
            .addQueryParameter("outputFormat", "application/json")
            .addQueryParameter("srsName", "EPSG:4326")
            .addQueryParameter("propertyName", "id,naam,status,geometrie")
            .addQueryParameter("bbox", String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f,EPSG:4326", at.lon - dLon, at.lat - dLat, at.lon + dLon, at.lat + dLat))
            .build()
        val json = runCatching { http.execute(http.request(url).build()).requireOk().json().asObject() }.getOrNull() ?: return emptyList()
        return parseDutch(json)
    }

    companion object {
        const val ID = "bathing-water"
        const val EEA_BASE = "https://water.discomap.eea.europa.eu/arcgis/rest/services"
        const val ZWEMWATER_BASE = "https://ogcgeo.zwemwater.nl"

        val INFO = ProviderInfo(
            id = ID, name = "EEA bathing water · Zwemwaterregister", licence = "CC BY 4.0 (EEA); Public Domain (zwemwater.nl)",
            attribution = "Bathing water quality: European Environment Agency, data from Member State authorities; swim advisories: zwemwater.nl",
            url = "https://www.eea.europa.eu/en/topics/in-depth/bathing-water",
            note = "Quality classes for every EU bathing water, yearly; live swim advisories in the Netherlands.",
            hosts = listOf("water.discomap.eea.europa.eu", "ogcgeo.zwemwater.nl"),
        )

        /** The layer of each season's service with a point per bathing water. */
        private const val POINT_LAYER = 3
        private const val MAX_SITES = 500
        private const val MAX_RADIUS_M = 50_000.0
        private const val FALLBACK_SEASON = 2025
        private const val WEEK_MS = 7 * 24 * 3600_000L
        private const val METRES_PER_DEGREE = 111_320.0

        /** A Dutch spot within this of an EU site is that site. */
        private const val SAME_SITE_M = 250.0

        private val SEASON = Regex("""BathingWater_Dyna_WM_(\d{4})$""")

        internal fun parseEu(json: JsonObject, season: Int): List<BathingSite> = json.arr("features").mapNotNull { f ->
            val a = f.asObject()?.obj("attributes") ?: return@mapNotNull null
            val id = a.str("bathingWaterIdentifier") ?: return@mapNotNull null
            val lat = a.dbl("latitude") ?: return@mapNotNull null
            val lon = a.dbl("longitude") ?: return@mapNotNull null
            BathingSite(id, readable(a.str("bathingWaterName") ?: id), LatLon(lat, lon), kindOf(a.str("bwWaterCategory")), qualityOf(a.str("qualityStatus")), season)
        }

        internal fun parseDutch(json: JsonObject): List<BathingSite> = json.arr("features").mapNotNull { f ->
            val o = f.asObject() ?: return@mapNotNull null
            val p = o.obj("properties") ?: return@mapNotNull null
            val name = p.str("naam") ?: return@mapNotNull null
            val box = o.arr("bbox").mapNotNull { it.asDouble() }.takeIf { it.size == 4 }?.let { BBox(it[0], it[1], it[2], it[3]) }
                ?: boxOf(o.obj("geometry")) ?: return@mapNotNull null
            BathingSite("zwr:${p.str("id") ?: p.dbl("id")?.toLong() ?: name}", name, box.center, WaterKind.OTHER, null, null, advisoryOf(p.str("status")), box, BathingSite.ZWEMWATER)
        }

        /** The EU's sites with the Dutch advisory in force at each, and the Dutch spots no EU site is. */
        internal fun merge(eu: List<BathingSite>, nl: List<BathingSite>): List<BathingSite> {
            if (nl.isEmpty()) return eu
            val used = HashSet<String>()
            val joined = eu.map { s ->
                val spot = nl.filter { it.id !in used && (it.area?.let { b -> inside(s.location, b, SAME_SITE_M) } ?: false || Geo.distance(it.location, s.location) <= SAME_SITE_M) }
                    .minByOrNull { Geo.distance(it.location, s.location) }
                if (spot == null) s else {
                    used += spot.id
                    s.copy(advisory = spot.advisory, area = spot.area)
                }
            }
            return joined + nl.filter { it.id !in used }
        }

        private fun inside(p: LatLon, b: BBox, m: Double): Boolean {
            val dLat = m / METRES_PER_DEGREE
            val dLon = m / (METRES_PER_DEGREE * cos(Math.toRadians(p.lat)).coerceAtLeast(0.05))
            return p.lat in (b.south - dLat)..(b.north + dLat) && p.lon in (b.west - dLon)..(b.east + dLon)
        }

        /** The box around a GeoJSON polygon or point's coordinates. */
        private fun boxOf(geometry: JsonObject?): BBox? {
            val points = ArrayList<Pair<Double, Double>>()
            fun walk(e: kotlinx.serialization.json.JsonElement?) {
                val a = e.asArray() ?: return
                val x = a.getOrNull(0).asDouble()
                val y = a.getOrNull(1).asDouble()
                if (x != null && y != null && a.size <= 3) points += x to y else a.forEach(::walk)
            }
            walk(geometry?.get("coordinates"))
            if (points.isEmpty()) return null
            return BBox(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
        }

        private fun inNetherlands(p: LatLon) = p.lat in 50.7..53.7 && p.lon in 3.2..7.3

        private fun qualityOf(s: String?): WaterQuality? = when (s?.trim()?.lowercase()) {
            "excellent" -> WaterQuality.EXCELLENT
            "good" -> WaterQuality.GOOD
            "sufficient" -> WaterQuality.SUFFICIENT
            "poor" -> WaterQuality.POOR
            null, "" -> null
            else -> WaterQuality.UNCLASSIFIED
        }

        private fun kindOf(s: String?): WaterKind = when (s?.trim()?.lowercase()) {
            "lake" -> WaterKind.LAKE
            "river" -> WaterKind.RIVER
            "coastal" -> WaterKind.COASTAL
            "transitional" -> WaterKind.TRANSITIONAL
            else -> WaterKind.OTHER
        }

        private fun advisoryOf(s: String?): SwimAdvisory? = when (s?.trim()?.uppercase()) {
            "WAARSCHUWING" -> SwimAdvisory.WARNING
            "NEGATIEF_ZWEMADVIES" -> SwimAdvisory.ADVISED_AGAINST
            "ZWEMVERBOD" -> SwimAdvisory.BANNED
            "NADER_ONDERZOEK" -> SwimAdvisory.INVESTIGATING
            else -> null
        }

        /** "STRAND ZUIDOEVER, GAASPERPLAS" as a name reads: "Strand Zuidoever, Gaasperplas". */
        internal fun readable(name: String): String {
            if (name.any { it.isLowerCase() }) return name.trim()
            return name.trim().lowercase(Locale.ROOT).split(' ').joinToString(" ") { w ->
                w.split('-').joinToString("-") { p -> p.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            }
        }
    }
}
