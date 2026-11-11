package app.atlas.packer.world

import java.io.File

/**
 * OpenStreetMap's own names of what Natural Earth's globe shows (ENGINE.md step 5, the globe: "places, countries and
 * seas from OSM, so their names come in every script, ranked by Natural Earth's scalerank where their Wikidata ids
 * match"), by Wikidata id: world.yml's filter job writes them from the extract it filters (`scratch-names.tsv`), one
 * element a line, tab-separated:
 *
 *     <wikidata id> <type and id: n123, w45, r6> <key=value: place=city, natural=water, …> <lat> <lon> <name>
 *
 * the latitude and longitude a node's (empty for a way or a relation), the name OSM's `name` in its own script (a tab
 * or a line break in it written as a space). A place of Natural Earth's takes the name of the first element of its id
 * with a place=* of a settlement or a country within [NEAR] degrees of it; a sea's, a bay's or a lake's the name of the
 * first element of its id that is water (place=ocean or sea, natural=water, bay or strait). Elsewhere Natural Earth's
 * own name stays.
 */
class OsmNames private constructor(private val places: Map<String, List<Named>>, private val waters: Map<String, Named>) {
    /** An element's name, and a node's place (degrees; NaN for a way or a relation). */
    class Named(val name: String, val lat: Double, val lon: Double)

    /** OSM's name of Natural Earth's place [wikidata] at ([lat], [lon]); null where none of its elements is near it. */
    fun place(wikidata: String?, lat: Double, lon: Double): String? {
        val all = places[wikidata ?: return null] ?: return null
        return all.firstOrNull { n ->
            n.lat.isNaN() || (Math.abs(n.lat - lat) <= NEAR && Math.abs(lonDelta(n.lon, lon)) * Math.cos(Math.toRadians(lat)) <= NEAR)
        }?.name
    }

    /** OSM's name of Natural Earth's water [wikidata]; null where OSM has none of its id. */
    fun water(wikidata: String?): String? = waters[wikidata ?: return null]?.name

    /** The ids it holds names for. */
    val size: Int get() = places.size + waters.size

    companion object {
        /** How far, degrees, a place's OSM node may lie from Natural Earth's point. */
        const val NEAR = 1.0

        private val WIKIDATA = Regex("^Q[1-9][0-9]*$")

        /** The place=* of the settlements and countries Natural Earth's places are. */
        val PLACES = setOf("country", "state", "province", "region", "city", "town", "village", "hamlet", "suburb", "municipality", "borough", "quarter", "locality", "island", "islet", "county")

        private fun lonDelta(a: Double, b: Double): Double {
            var d = (a - b) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }

        fun read(file: File): OsmNames {
            val places = LinkedHashMap<String, MutableList<Named>>()
            val waters = LinkedHashMap<String, Named>()
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t', limit = 6)
                    if (f.size < 6 || !WIKIDATA.matches(f[0])) continue
                    val name = f[5].trim()
                    if (name.isEmpty()) continue
                    val (key, value) = f[2].split('=', limit = 2).let { if (it.size == 2) it[0] to it[1] else continue }
                    val n = Named(name, f[3].toDoubleOrNull() ?: Double.NaN, f[4].toDoubleOrNull() ?: Double.NaN)
                    val water = (key == "place" && (value == WorldProfile.OCEAN || value == WorldProfile.SEA)) ||
                        (key == "natural" && (value == "water" || value == WorldProfile.BAY || value == WorldProfile.STRAIT))
                    when {
                        water -> waters.putIfAbsent(f[0], n)
                        key == "place" && value in PLACES -> places.getOrPut(f[0]) { ArrayList() } += n
                    }
                }
            }
            return OsmNames(places, waters)
        }
    }
}
