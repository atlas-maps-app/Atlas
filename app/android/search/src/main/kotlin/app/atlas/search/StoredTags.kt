package app.atlas.search

/**
 * Which tags a store keeps and how it writes them. Bookkeeping that no screen shows is dropped from OSM places
 * (sources, notes, fixmes, editor names, import ids, registry numbers); a value is written by its id in the value
 * dictionary when it repeats across places (kinds, operators, cities), inline when it is a name or otherwise unique.
 */
internal object StoredTags {
    /** Keys dropped wherever they are. */
    private val DROP = setOf(
        "source", "note", "fixme", "FIXME", "created_by", "comment", "history", "import_uuid", "odbl", "converted_by",
        "is_in", "attribution", "source_ref",
    )

    /** Key prefixes dropped: per-tag sources and notes, editor and import bookkeeping. */
    private val DROP_PREFIXES = listOf(
        "source:", "note:", "fixme:", "FIXME:", "is_in:", "odbl:", "tiger:", "gnis:", "NHD:", "nhd:", "osak:", "lacounty:",
        "nysgissam:", "massgis:", "geobase:", "canvec:", "KSJ2:", "yh:", "linz:", "mvdgis:", "import:", "bag:",
    )

    /** Registry numbers (`ref:*`) worth keeping: stop ids for departures, charge point ids, station codes. */
    private val REF_KEEP = setOf("ref:IFOPT", "ref:EU:EVSE", "ref:ibnr", "ref:uic", "ref:station", "ref:mobile", "ref:vatin")

    /** Survey dates kept on points of interest only, where they tell how fresh the facts are. */
    private val POI_DATES = setOf("check_date", "check_date:opening_hours", "survey:date")

    /** Keys whose values are names, contacts or free text: unique enough to be written inline. */
    private val INLINE = setOf(
        "addr:housenumber", "addr:unit", "addr:housename", "addr:flats", "addr:door", "website", "url", "email", "phone",
        "fax", "mobile", "wikipedia", "wikidata", "description", "inscription", "image", "wikimedia_commons", "mapillary",
        "opening_hours", "collection_times", "service_times", "ref", "local_ref", "uic_ref", "ref:IFOPT", "ref:EU:EVSE",
        "ele", "height", "capacity", "start_date", "opening_date", "survey:date", "check_date:opening_hours",
    )

    /** Longest value written by its id in the dictionary; longer ones are written inline. */
    const val MAX_DICT_VALUE = 48

    /** True when an OSM place's tag [key] is kept; [poi] tells a point of interest (its survey dates are kept). */
    fun keeps(key: String, poi: Boolean): Boolean {
        if (key in DROP) return false
        if (key in POI_DATES) return poi
        if (key.startsWith("check_date")) return false
        if (key.startsWith("ref:")) return key in REF_KEEP
        for (p in DROP_PREFIXES) if (key.startsWith(p)) return false
        return true
    }

    /** [tags] without what the store drops (the same map when nothing is). Places from other sources keep everything. */
    fun filter(tags: Map<String, String>, source: String = "osm"): Map<String, String> {
        if (source != "osm") return tags
        val poi = isPoi(tags)
        var drop = 0
        for (k in tags.keys) if (!keeps(k, poi)) drop++
        if (drop == 0) return tags
        val out = LinkedHashMap<String, String>(tags.size - drop + 2)
        for ((k, v) in tags) if (keeps(k, poi)) out[k] = v
        return out
    }

    /** A point of interest: anything with one of the keys whose every value makes a place. */
    fun isPoi(tags: Map<String, String>): Boolean = Selection.POI_KEYS.any { it in tags }

    /** True when [value] of [key] is written inline rather than by its dictionary id. */
    fun inline(key: String, value: String): Boolean =
        value.length > MAX_DICT_VALUE || key in INLINE || key.startsWith("contact:") ||
            (SearchText.isNameKey(key) && !key.startsWith("brand"))
}
