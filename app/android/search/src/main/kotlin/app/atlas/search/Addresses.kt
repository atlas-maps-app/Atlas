package app.atlas.search

import app.atlas.places.Text

/** An address point as the store keeps it: its street (or the place it is numbered in), number, postcode, town and country. */
internal class AddressParts(
    val street: String,
    val kind: Int,
    val housenumber: String,
    val postcode: String?,
    val city: String?,
    val country: String?,
)

/**
 * The address points that go to the compact address table rather than being places: an element with a house number
 * and nothing else worth showing (an ordinary building, its levels and roof, an entrance). Anything more (a name, a
 * shop, a church, a flat number) keeps it a place, with its address among its tags.
 *
 * Address points hang off streets: a street is a name with its postcode, town and country inside one cell of a grid
 * of about 5 × 6 km, so a name that recurs across a country is many small streets, each with a box and a line in the
 * street text index. A point's position is kept as offsets from its cell's corner, which take three bytes each.
 */
internal object Addresses {
    /** Numbered along an `addr:street`. */
    const val STREET = 0

    /** Numbered in an `addr:place` (a hamlet or square without streets). */
    const val PLACE = 1

    /** Numbered without either. */
    const val NONE = 2

    const val HOUSENUMBER = "addr:housenumber"

    /** The keys an address point carries, in the order its tags are rebuilt. */
    val KEYS = listOf("addr:street", "addr:place", HOUSENUMBER, "addr:postcode", "addr:city", "addr:country")

    private val KEY_SET = KEYS.toHashSet()

    /** Tags an address point may carry and still be just an address. */
    private val STRUCTURE = setOf("building:levels", "building:flats", "height", "min_height", "start_date", "entrance", "levels", "roof:levels")

    /** Buildings that say no more than "a building"; a church or a station keeps its element a place. */
    private val ORDINARY_BUILDINGS = setOf(
        "yes", "house", "residential", "detached", "semidetached_house", "terrace", "apartments", "bungalow", "farm",
        "garage", "garages", "shed", "hut", "cabin", "static_caravan", "commercial", "retail", "industrial", "warehouse",
        "office", "barn", "farm_auxiliary", "outbuilding", "houseboat", "dormitory", "allotment_house", "construction",
        "carport", "greenhouse", "stable", "service", "semi", "semidetached", "annexe",
    )

    /** Longest house number an address point keeps; a longer one (a list, a description) keeps its element a place. */
    private const val MAX_HOUSENUMBER = 24

    /** The address of [tags] when they are only an address (tags already filtered by [StoredTags]), else null. */
    fun parts(tags: Map<String, String>): AddressParts? {
        val hn = tags[HOUSENUMBER]?.trim() ?: return null
        if (hn.isEmpty() || hn.length > MAX_HOUSENUMBER) return null
        for ((k, v) in tags) {
            when {
                k in KEY_SET || k in STRUCTURE -> {}
                k == "building" -> if (v !in ORDINARY_BUILDINGS) return null
                k.startsWith("building:") && k != "building:part" && k != "building:use" -> {}
                k.startsWith("roof:") -> {}
                k == "type" && v == "multipolygon" -> {}
                else -> return null
            }
        }
        val street = tags["addr:street"]?.trim()?.takeIf { it.isNotEmpty() }
        val place = tags["addr:place"]?.trim()?.takeIf { it.isNotEmpty() }
        if (street != null && place != null) return null
        val postcode = tags["addr:postcode"]?.trim()?.takeIf { it.isNotEmpty() }
        val city = tags["addr:city"]?.trim()?.takeIf { it.isNotEmpty() }
        val country = tags["addr:country"]?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            street != null -> AddressParts(street, STREET, hn, postcode, city, country)
            place != null -> AddressParts(place, PLACE, hn, postcode, city, country)
            else -> AddressParts("", NONE, hn, postcode, city, country)
        }
    }

    /** The tags of an address point, as its element had them (less what the store drops). */
    fun tags(street: String, kind: Int, housenumber: String, postcode: String?, city: String?, country: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>(8)
        when (kind) {
            STREET -> out["addr:street"] = street
            PLACE -> out["addr:place"] = street
        }
        out[HOUSENUMBER] = housenumber
        postcode?.let { out["addr:postcode"] = it }
        city?.let { out["addr:city"] = it }
        country?.let { out["addr:country"] = it }
        return out
    }

    /** True for keys an address point can carry: the ones the address table answers lookups for. */
    fun isAddressKey(key: String): Boolean = key in KEY_SET

    private const val CELL_LAT_E7 = 440_000L
    private const val CELL_LON_E7 = 880_000L
    private const val CELL_COLUMNS = 8192L
    private const val LAT_OFFSET = 900_000_000L
    private const val LON_OFFSET = 1_800_000_000L

    /** The grid cell of a point. */
    fun cellOf(latE7: Int, lonE7: Int): Long = ((latE7 + LAT_OFFSET) / CELL_LAT_E7) * CELL_COLUMNS + (lonE7 + LON_OFFSET) / CELL_LON_E7

    /** The south edge of [cell], in 1e-7 degree. */
    fun cellSouth(cell: Long): Int = ((cell / CELL_COLUMNS) * CELL_LAT_E7 - LAT_OFFSET).toInt()

    /** The west edge of [cell], in 1e-7 degree. */
    fun cellWest(cell: Long): Int = ((cell % CELL_COLUMNS) * CELL_LON_E7 - LON_OFFSET).toInt()

    /** The street text index's line for a street: its name, then its town and postcode (also written without spaces). */
    fun streetText(name: String, city: String?, postcode: String?): String = buildString {
        append(Text.fold(name))
        city?.let { append(' ').append(Text.fold(it)) }
        postcode?.let { pc ->
            val f = Text.fold(pc)
            append(' ').append(f)
            if (' ' in f) append(' ').append(f.replace(" ", ""))
        }
    }

    /** Query [words] (folded) as an address reads them: a country's letter before a postcode left out ("L-2449", "D-10115"). */
    fun queryWords(words: List<String>): List<String> {
        if (words.size < 2) return words
        return words.filterIndexed { i, w -> !(w.length == 1 && w[0].isLetter() && i + 1 < words.size && words[i + 1][0].isDigit()) }.ifEmpty { words }
    }

    /**
     * How well query [words] (folded) name the address in [tags]: a word that is its house number ("263", "263 h",
     * "26" while typed last) and the other words in its street, then its town and postcode; or the words its postcode
     * ("1016 gv", "L-2449"). 0 to 1; 0 when the number is another one, so "rue de luxembourg 12" is not number 8 of a
     * street in the city of Luxembourg whose postcode begins with 12.
     */
    fun relevance(words: List<String>, tags: Map<String, String>): Double {
        val ws = queryWords(words)
        val hn = tags[HOUSENUMBER] ?: return 0.0
        val street = Text.fold(tags["addr:street"] ?: tags["addr:place"] ?: "")
        val area = listOfNotNull(tags["addr:city"], tags["addr:postcode"]).joinToString(" ") { Text.fold(it) }
        var best = 0.0
        for (i in ws.indices) {
            if (!looksLikeNumber(ws[i])) continue
            var number = ws[i]
            var last = i
            if (i + 1 < ws.size && ws[i + 1].length == 1 && ws[i + 1][0].isLetter()) {
                number += ws[i + 1]
                last = i + 1
            }
            val typing = last == ws.size - 1
            val fit = numberFit(number, hn, typing)
            if (fit == 0.0) continue
            val rest = ws.filterIndexed { j, _ -> j != i && j != last }
            val place = if (rest.isEmpty()) {
                0.5
            } else {
                maxOf(NameMatch.score(rest, street, !typing), 0.9 * NameMatch.score(rest, "$street $area".trim(), !typing))
            }
            best = maxOf(best, fit * place)
        }
        if (best == 0.0) {
            val pc = tags["addr:postcode"]?.let { Text.fold(it).replace(" ", "") } ?: return 0.0
            val joined = ws.joinToString("")
            if (joined.isNotEmpty() && pc.startsWith(joined)) best = 0.8 * joined.length / pc.length + 0.1
        }
        return best
    }

    /** True when [word] (folded) could be a house number: it begins with a digit and is short. */
    fun looksLikeNumber(word: String): Boolean = word.isNotEmpty() && word[0].isDigit() && word.length <= MAX_HOUSENUMBER

    /**
     * How well a stored house number [hn] answers the typed [word]: 1 for the same number ("12a" for "12A"), less for
     * one the word begins while it is being typed ([prefix]: "26" for "263"), 0 for none.
     */
    fun numberFit(word: String, hn: String, prefix: Boolean): Double {
        val f = hn.lowercase().replace(" ", "")
        if (f == word) return 1.0
        // "263h" for "263-H"
        val plain = f.filter { it.isLetterOrDigit() }
        if (plain == word) return 0.98
        // "12" finds "12;14", "12-14" and "263" finds "263-H": a list, range or suffixed number that holds it
        if (f.split(';', '-', ',', '/').any { it == word }) return 0.95
        if (prefix && plain.startsWith(word)) return 0.7 + 0.2 * word.length / plain.length
        return 0.0
    }
}
