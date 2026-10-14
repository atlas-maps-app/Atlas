package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The configuration of a region that has no entry in `data/regions/places.py`: the same shape as an entry (bbox
 * `[south, west, north, east]`, origin `[lat, lon]`, title, tz, tradition, views), made from the box alone.
 *
 * The origin is the box's centre; the time zone and the building tradition come from the country; the views follow the
 * region's size, scaled the way the hand-framed entries are (the overview's distance about 0.6 of the region's longer
 * side, between 300 m and 4 km; a close view at the living map's distance when the region is bigger than 900 m).
 */
object RegionConfig {
    fun forBox(name: String, bbox: BBox, country: String? = null): JsonObject {
        val origin = bbox.center
        val code = country ?: Countries.of(origin)
        val tz = Countries.timezone(code, origin)
        val tradition = Countries.tradition(code)
        return buildJsonObject {
            put("bbox", array(round(bbox.south, 4), round(bbox.west, 4), round(bbox.north, 4), round(bbox.east, 4)))
            put("origin", array(round(origin.lat, 4), round(origin.lon, 4)))
            put("title", name)
            put("tz", tz)
            if (tradition != null) put("tradition", tradition)
            if (code != null) put("country", code)
            put("views", views(bbox))
        }
    }

    /** The default cameras for a region: '' the overview, 'close' a street-level look when the region is big enough. */
    fun views(bbox: BBox): JsonObject {
        val c = bbox.center
        val widthM = bbox.width * 111_320.0 * cos(Math.toRadians(c.lat))
        val heightM = bbox.height * 110_540.0
        val longer = max(widthM, heightM)
        val dist = min(4000.0, max(300.0, longer * 0.6)).roundToInt()
        val overview = camera(yaw = 35, pitch = 36, dist = dist, radius = (dist * 0.85).roundToInt(), ground = (max(1500.0, dist * 1.4) / 100).roundToInt() * 100)
        return buildJsonObject {
            put("", overview)
            if (longer > 900) put("close", camera(yaw = 35, pitch = 36, dist = 430, radius = 420, ground = 1300, winD = 480))
        }
    }

    private fun camera(yaw: Int, pitch: Int, dist: Int, radius: Int, ground: Int, winD: Int? = null): JsonObject = buildJsonObject {
        put("target", array(0, 0, 0))
        put("yaw", yaw)
        put("pitch", pitch)
        put("dist", dist)
        put("fov", 36)
        put("cy", 440)
        put("radius", radius)
        put("ground_r", ground)
        if (winD != null) put("win_d", winD)
    }

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }

    private fun array(vararg v: Number): JsonArray = JsonArray(v.map<Number, JsonElement> { JsonPrimitive(it) })

    /** `bbox` of an entry (south, west, north, east) as a [BBox]. */
    fun bboxOf(config: JsonObject): BBox? {
        val a = (config["bbox"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: return null
        return if (a.size == 4) BBox(a[1], a[0], a[3], a[2]) else null
    }

    /** `origin` of an entry (lat, lon). */
    fun originOf(config: JsonObject): LatLon? {
        val a = (config["origin"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: return null
        return if (a.size == 2) LatLon(a[0], a[1]) else null
    }
}
