package app.atlas.route

/**
 * Reads BRouter tag descriptions ("highway=residential surface=asphalt").
 * Descriptions repeat a lot along a track, so each distinct string is parsed once.
 */
internal class TagReader {
    private val cache = HashMap<String, Map<String, String>>()

    fun tags(desc: String?): Map<String, String> {
        if (desc.isNullOrEmpty()) return emptyMap()
        return cache.getOrPut(desc) { parse(desc) }
    }

    operator fun get(desc: String?, key: String): String? = tags(desc)[key]

    companion object {
        fun parse(desc: String): Map<String, String> {
            val out = HashMap<String, String>(8)
            var i = 0
            val n = desc.length
            while (i < n) {
                while (i < n && desc[i] == ' ') i++
                val start = i
                while (i < n && desc[i] != ' ') i++
                if (i > start) {
                    val eq = desc.indexOf('=', start)
                    if (eq in (start + 1) until i) out[desc.substring(start, eq)] = desc.substring(eq + 1, i)
                }
            }
            return out
        }
    }
}

/** Values of OpenStreetMap tags that mean the same thing for routing. */
internal object OsmValues {
    val UNPAVED_SURFACES = setOf(
        "unpaved", "gravel", "ground", "dirt", "grass", "sand", "earth", "mud", "clay", "rock", "stone",
        "pebblestone", "compacted", "fine_gravel", "grass_paver", "woodchips",
    )
    val COBBLE_SURFACES = setOf("sett", "cobblestone", "unhewn_cobblestone", "cobblestone:flattened")

    fun isUnpaved(tags: Map<String, String>): Boolean {
        val surface = tags["surface"]
        if (surface != null) return surface in UNPAVED_SURFACES
        val track = tags["tracktype"]
        if (track != null) return track != "grade1"
        return tags["highway"] == "track"
    }

    fun isCobbles(tags: Map<String, String>) = tags["surface"] in COBBLE_SURFACES

    /** km/h from a maxspeed value as BRouter's lookups store it ("50", "urban", "rural"); null when unknown. */
    fun speedKmh(value: String?): Double? = when {
        value == null -> null
        value == "urban" -> 50.0
        value == "rural" -> 80.0
        value == "walk" -> 6.0
        value.endsWith("mph") -> value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.times(1.609344)
        else -> value.toDoubleOrNull()
    }

    /** The incline in percent, positive uphill in the direction of travel; null when not numeric. */
    fun inclinePercent(value: String?, reversed: Boolean): Double? {
        val v = value ?: return null
        val num = v.removeSuffix("%").removeSuffix("°").toDoubleOrNull() ?: return null
        val pct = if (v.endsWith("°")) kotlin.math.tan(Math.toRadians(num)) * 100 else num
        return if (reversed) -pct else pct
    }
}
