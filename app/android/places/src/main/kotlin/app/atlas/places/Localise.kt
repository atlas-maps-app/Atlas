package app.atlas.places

import app.atlas.model.Place

/** How a country writes a postal address. */
enum class AddressStyle {
    /** "Prinsengracht 263", "1016 GV Amsterdam": the Netherlands, Germany and most of Europe. */
    STREET_FIRST,

    /** "12 Rue de Rivoli", "75004 Paris": France, Luxembourg, Monaco. */
    NUMBER_FIRST,

    /** "10 Downing Street", "London SW1A 2AA": the United Kingdom and Ireland. */
    BRITISH,

    /** "1600 Pennsylvania Avenue NW", "Washington, DC 20500": the United States, Canada, Australia. */
    AMERICAN,
}

/** Names and addresses as people read them: the name in their language with sensible fallbacks, addresses by country. */
object Localise {
    /**
     * The name to show in [lang]: `name:<lang>` (or of its base language, "pt" for "pt-BR"), `name`, `brand:<lang>`,
     * `brand`, `operator`, then `ref`. Null when the feature has none of them.
     */
    fun name(tags: Map<String, String>, lang: String? = null): String? {
        for (l in langKeys(lang)) tags["name:$l"]?.let { if (it.isNotBlank()) return it.trim() }
        tags["name"]?.let { if (it.isNotBlank()) return it.trim() }
        for (l in langKeys(lang)) tags["brand:$l"]?.let { if (it.isNotBlank()) return it.trim() }
        for (k in FALLBACK_KEYS) tags[k]?.let { if (it.isNotBlank()) return it.trim() }
        return null
    }

    fun name(place: Place, lang: String? = null): String? = name(place.tags, lang)

    private val FALLBACK_KEYS = listOf("brand", "operator", "ref")

    /** "zh-Hant-TW" -> zh-Hant-TW, zh-Hant, zh: the tag, then each shorter prefix. */
    private fun langKeys(lang: String?): List<String> {
        if (lang.isNullOrBlank()) return emptyList()
        var l = lang.trim().replace('_', '-')
        val out = ArrayList<String>(3)
        while (true) {
            out += l
            val cut = l.lastIndexOf('-')
            if (cut <= 0) break
            l = l.substring(0, cut)
        }
        out[out.size - 1] = out.last().lowercase()
        return out.distinct()
    }

    /** The address style of an ISO country code (any case); street first when unknown. */
    fun styleFor(country: String?): AddressStyle = when (country?.uppercase()) {
        "FR", "LU", "MC" -> AddressStyle.NUMBER_FIRST
        "GB", "UK", "IE", "IM", "JE", "GG" -> AddressStyle.BRITISH
        "US", "CA", "AU", "NZ" -> AddressStyle.AMERICAN
        else -> AddressStyle.STREET_FIRST
    }

    /**
     * The postal address of a feature as lines, in the style of `addr:country`, else of [country], the country of the
     * map at the feature, which every caller gives (the reviewer, 17 November, finding 11): street and number, then
     * postcode and place. Where the number comes first, a unit (`addr:unit`) follows the street in America, "1600
     * Pennsylvania Avenue NW, Suite 200", and comes before it in Britain and France, "Flat 2, 10 Downing Street" (the
     * app maintainer, 18 November 2026, batch 4 B23), but for a unit of one letter, which joins a number of digits
     * alone there, "10A Downing Street" (batch 5 B11). Empty when no address is mapped.
     */
    fun addressLines(tags: Map<String, String>, country: String?): List<String> {
        fun t(k: String) = tags["addr:$k"]?.trim()?.takeIf { it.isNotEmpty() }
        val street = t("street") ?: t("place")
        val unit = t("unit")
        val number = listOfNotNull(t("housenumber"), unit).joinToString(" ").ifEmpty { null }
        val houseName = t("housename")
        val postcode = t("postcode")
        val city = t("city") ?: t("town") ?: t("village") ?: if (t("street") != null) t("place") else null
        val state = t("state") ?: t("province")
        val style = styleFor(t("country") ?: country)
        val first = when {
            street == null -> number
            number == null -> street
            style == AddressStyle.STREET_FIRST -> "$street $number"
            style == AddressStyle.AMERICAN -> listOfNotNull(t("housenumber")?.let { "$it $street" } ?: street, unit).joinToString(", ")
            else -> {
                val house = t("housenumber")
                // a unit of one letter joins a number of digits alone: "10A Downing Street", where "10A" with "B" or
                // "10-12" with "A" would read as another number (batch 5 B11)
                val letter = unit?.takeIf { house != null && house.all(Char::isDigit) && it.length == 1 && it[0].isLetter() }
                listOfNotNull(unit.takeIf { letter == null }, house?.let { "$it${letter.orEmpty()} $street" } ?: street).joinToString(", ")
            }
        }
        val second = when (style) {
            AddressStyle.STREET_FIRST, AddressStyle.NUMBER_FIRST -> listOfNotNull(postcode, city).joinToString(" ")
            AddressStyle.BRITISH -> listOfNotNull(city, postcode).joinToString(" ")
            AddressStyle.AMERICAN -> {
                val region = listOfNotNull(state, postcode).joinToString(" ")
                listOfNotNull(city, region.ifEmpty { null }).joinToString(", ")
            }
        }
        return listOfNotNull(houseName?.takeIf { it != street }, first, second.ifEmpty { null })
    }

    /** The address on one line ("Prinsengracht 263, 1016 GV Amsterdam"), or null when none is mapped ([addressLines]). */
    fun address(tags: Map<String, String>, country: String?): String? =
        addressLines(tags, country).joinToString(", ").ifEmpty { null }
}
