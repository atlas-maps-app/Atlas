package app.atlas.search

/**
 * Which OSM elements a region's store keeps: points of interest and named features with their full tags, addresses,
 * stops and stations; trees go to a light table of their own for the map engine. [RegionImport]'s Overpass query and
 * the file import share these rules.
 */
object Selection {
    /** Keys whose every value makes a place. */
    val POI_KEYS = listOf(
        "amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "emergency", "historic", "sport",
        "man_made", "public_transport", "club", "aeroway",
    )

    /** `natural` values worth finding (trees are kept apart). */
    val NATURAL = listOf("peak", "spring", "tree", "volcano", "cave_entrance", "waterfall", "beach", "bay", "cape", "saddle", "glacier", "hot_spring", "geyser", "rock", "stone")

    val RAILWAY = listOf("station", "halt", "tram_stop", "subway_entrance", "platform")

    /** Relation types that are not places: routes, networks, turn rules. */
    private val SKIP_RELATIONS = setOf("route", "route_master", "superroute", "network", "restriction", "enforcement", "connectivity", "waterway", "collection", "site_relation")

    /** True when [tags] describe a tree, which goes to the tree table. */
    fun isTree(tags: Map<String, String>): Boolean = tags["natural"] == "tree"

    /** True when the store keeps an element with these tags (a relation's [relationType] decides too). */
    fun keeps(tags: Map<String, String>, relationType: String? = tags["type"], isRelation: Boolean = false): Boolean {
        if (tags.isEmpty()) return false
        if (isRelation && relationType in SKIP_RELATIONS) return false
        if (tags["natural"] == "tree") return hasName(tags)
        for (k in POI_KEYS) if (k in tags) return true
        tags["natural"]?.let { if (it in NATURAL) return true }
        tags["railway"]?.let { if (it in RAILWAY) return true }
        if (tags["highway"] == "bus_stop") return true
        if ("addr:housenumber" in tags) return true
        return hasName(tags)
    }

    private fun hasName(tags: Map<String, String>): Boolean = tags.keys.any { it == "name" || (it.startsWith("name:") && SearchText.isNameKey(it)) }
}
