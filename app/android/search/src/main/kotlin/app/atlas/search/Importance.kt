package app.atlas.search

/**
 * How much a place matters when a name matches thousands (every "kerk" of a country): towns before the streets
 * named after them, stations and landmarks before the shops, named before unnamed. 0 to 255; the text index's
 * matches are taken most important first, then scored by how well their names fit.
 */
internal object Importance {
    private val PLACES = mapOf(
        "country" to 255, "state" to 240, "province" to 235, "region" to 230, "city" to 250, "town" to 230,
        "municipality" to 215, "village" to 210, "borough" to 200, "suburb" to 200, "quarter" to 190, "island" to 185,
        "neighbourhood" to 180, "hamlet" to 175, "locality" to 150, "square" to 150, "isolated_dwelling" to 120,
    )

    fun of(tags: Map<String, String>): Int {
        val named = tags["name"] != null
        var base = when {
            tags["place"] != null -> PLACES[tags["place"]] ?: 160
            tags["boundary"] == "administrative" -> 230 - 8 * (tags["admin_level"]?.toIntOrNull() ?: 10).coerceIn(2, 11)
            tags["aeroway"] == "aerodrome" -> 200
            tags["railway"] == "station" || tags["public_transport"] == "station" -> 190
            tags["railway"] == "halt" -> 170
            tags["tourism"] == "attraction" || tags["tourism"] == "museum" || tags["historic"] != null -> if (named) 150 else 50
            StoredTags.isPoi(tags) -> if (named) 130 else 60
            tags["highway"] != null -> if (named) 110 else 30
            named -> 100
            else -> 30
        }
        if (tags["wikidata"] != null) base += 20
        if (tags["wikipedia"] != null) base += 5
        return base.coerceIn(0, 255)
    }
}
