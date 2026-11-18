package app.atlas.places

/**
 * A place's country, the one way Atlas reads it (the reviewer, 17 November, findings 8 and 9): the place's own
 * `addr:country`, else the country known at its point, which the caller gives ([of]'s `around`: the regions downloaded
 * there, else the map's own; `HereContext` in the app), never the phone's language. Its public holidays, its clock and
 * the brands it can be are that country's.
 */
object PlaceCountry {
    /** ISO 3166 alpha-2, upper case: [tags]' own `addr:country`, else [around]; null when neither says. */
    fun of(tags: Map<String, String>, around: String?): String? = tagged(tags) ?: code(around)

    /** The place's own `addr:country`, when it is a two-letter code. */
    fun tagged(tags: Map<String, String>): String? = code(tags["addr:country"])

    private fun code(s: String?): String? = s?.trim()?.uppercase()?.takeIf { c -> c.length == 2 && c.all { it in 'A'..'Z' } }
}
