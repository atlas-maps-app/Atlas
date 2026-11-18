package app.atlas.places

/**
 * A place's country, the one way Atlas reads it (the reviewer, 17 November, findings 8 and 9): the place's own
 * `addr:country`, else the country known at its point, which the caller gives ([of]'s `around`: the regions downloaded
 * there, else the map's own; `HereContext` in the app), never the phone's language. Its public holidays, its clock and
 * the brands it can be are that country's; its state or nation, where the holidays differ by it, is [subdivision]'s.
 */
object PlaceCountry {
    /** ISO 3166 alpha-2, upper case: [tags]' own `addr:country`, else [around]; null when neither says. */
    fun of(tags: Map<String, String>, around: String?): String? = tagged(tags) ?: code(around)

    /** The place's own `addr:country`, when it is a two-letter code. */
    fun tagged(tags: Map<String, String>): String? = code(tags["addr:country"])

    /**
     * [s] as an ISO 3166 alpha-2 code, upper case; null when it is none. "UK", as many write Britain's, is GB (the app
     * maintainer, 18 November 2026, batch 4 B17): in the place's country, the store's tally and the nation.
     */
    fun code(s: String?): String? = s?.trim()?.uppercase()?.takeIf { c -> c.length == 2 && c.all { it in 'A'..'Z' } }
        ?.let { if (it == "UK") "GB" else it }

    /**
     * The state or nation of [country] that [tags] put the place in, as ISO 3166-2, in the countries whose public
     * holidays differ within them (the reviewer, 17 November, finding 10): the `ISO3166-2` tag itself; in Germany
     * `addr:state`, by its code or its name; in France the department `addr:postcode` begins with ("FR-67"); in Britain
     * the area of `addr:postcode`: Northern Ireland's, Scotland's, else England and Wales's ("GB-EAW", whose days are
     * the same), or `addr:state` by the nation's name. [country] is the place's own ([of]). Null where the tags do not
     * say, or say it ambiguously (a postcode district on both sides of a border), and in other countries.
     */
    fun subdivision(tags: Map<String, String>, country: String?): String? {
        val c = code(country) ?: return null
        tags["ISO3166-2"]?.trim()?.uppercase()?.let { iso -> if (iso.startsWith("$c-") && iso.length in 4..6) return iso }
        return when (c) {
            "DE" -> tags["addr:state"]?.let(::germanState)
            "FR" -> tags["addr:postcode"]?.let(::frenchDepartment)
            "GB" -> tags["addr:postcode"]?.let(::britishNation) ?: tags["addr:state"]?.let(::britishNationByName)
            else -> null
        }
    }

    /** The states whose own holidays the calendar keeps, by their codes. */
    private val GERMAN_STATES = setOf("BW", "BY", "BE", "BB", "HB", "HH", "HE", "MV", "NI", "NW", "RP", "SL", "SN", "ST", "SH", "TH")

    /** The states by name, folded ([fold]): German and English, without "Freistaat" and the like. */
    private val GERMAN_STATE_NAMES = mapOf(
        "badenwurttemberg" to "BW", "badenwuerttemberg" to "BW", "bayern" to "BY", "bavaria" to "BY", "berlin" to "BE",
        "brandenburg" to "BB", "bremen" to "HB", "hamburg" to "HH", "hessen" to "HE", "hesse" to "HE",
        "mecklenburgvorpommern" to "MV", "mecklenburgwesternpomerania" to "MV", "mecklenburgwestpomerania" to "MV",
        "niedersachsen" to "NI", "lowersaxony" to "NI", "nordrheinwestfalen" to "NW", "northrhinewestphalia" to "NW",
        "rheinlandpfalz" to "RP", "rhinelandpalatinate" to "RP", "saarland" to "SL", "sachsen" to "SN", "saxony" to "SN",
        "sachsenanhalt" to "ST", "saxonyanhalt" to "ST", "schleswigholstein" to "SH", "thuringen" to "TH",
        "thueringen" to "TH", "thuringia" to "TH",
    )

    private val STATE_PREFIXES = listOf("freistaat", "freieundhansestadt", "freiehansestadt", "land")

    private fun germanState(value: String): String? {
        val v = value.trim().uppercase().removePrefix("DE-")
        if (v in GERMAN_STATES) return "DE-$v"
        var f = fold(value)
        STATE_PREFIXES.firstOrNull { f.startsWith(it) && f.length > it.length }?.let { f = f.removePrefix(it) }
        return GERMAN_STATE_NAMES[f]?.let { "DE-$it" }
    }

    /**
     * The department a French postcode begins with: its first two digits, in metropolitan France. Not Corsica's (20:
     * two departments), not oversea's (97, 98: three digits, and countries of their own on the map) and not the army's
     * (00).
     */
    private fun frenchDepartment(value: String): String? {
        val v = value.trim().replace(" ", "")
        if (v.length != 5 || !v.all { it in '0'..'9' }) return null
        val d = v.substring(0, 2)
        return if (d == "00" || d == "20" || d >= "96") null else "FR-$d"
    }

    /** Scotland's postcode areas whose every district is in Scotland. */
    private val SCOTTISH_AREAS = setOf("AB", "DD", "DG", "EH", "FK", "G", "HS", "IV", "KA", "KW", "KY", "ML", "PA", "PH", "ZE")

    /** A British postcode, its outward code alone allowed: the area's letters, then the district. */
    private val BRITISH_POSTCODE = Regex("^([A-Z]{1,2})([0-9][0-9A-Z]?)(?: ?[0-9][A-Z]{2})?$")

    /**
     * The nation a British postcode's area is in: Northern Ireland's BT; Scotland's areas, and TD but for Coldstream's
     * TD12 and Berwick's TD15, which cross the border; the Channel Islands' and Man's (GY, JE, IM) none, being none of
     * Britain's; every other area England's or Wales's, which keep the same days.
     */
    private fun britishNation(value: String): String? {
        val m = BRITISH_POSTCODE.matchEntire(value.trim().uppercase()) ?: return null
        val (area, district) = m.destructured
        return when {
            area == "BT" -> "GB-NIR"
            area in SCOTTISH_AREAS -> "GB-SCT"
            area == "TD" -> if (district.takeWhile { it.isDigit() } in setOf("12", "15")) null else "GB-SCT"
            area in setOf("GY", "JE", "IM") -> null
            else -> "GB-EAW"
        }
    }

    private fun britishNationByName(value: String): String? = when (fold(value)) {
        "scotland", "alba" -> "GB-SCT"
        "northernireland" -> "GB-NIR"
        "england", "wales", "cymru" -> "GB-EAW"
        else -> null
    }

    /** [s] lower case, its umlauts and ß spelt in plain letters, without spaces, dashes and dots. */
    private fun fold(s: String): String = buildString {
        for (ch in s.trim().lowercase()) when (ch) {
            'ä' -> append('a'); 'ö' -> append('o'); 'ü' -> append('u'); 'ß' -> append("ss")
            ' ', '-', '.', '_', '\'' -> {}
            else -> append(ch)
        }
    }
}
