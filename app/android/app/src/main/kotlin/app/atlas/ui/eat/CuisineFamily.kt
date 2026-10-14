package app.atlas.ui.eat

import app.atlas.places.Cuisine

/**
 * The carte's chapters: cuisines grouped by where they come from, each with the two- or three-letter code of its
 * thumb-index tab and its ink (the Carte board's tab colours). [MORE] holds what belongs nowhere in particular (seafood, sandwiches, brunch) and
 * kitchens nobody has mapped.
 */
enum class CuisineFamily(val code: String, val colour: Long) {
    ITALY("IT", 0xFFB5532E),
    JAPAN("JP", 0xFF1E2F5A),
    CHINA_KOREA("CN", 0xFF9E2B25),
    SOUTHEAST_ASIA("SEA", 0xFF2E7D5B),
    INDIA("IN", 0xFFD98E1F),
    MIDDLE_EAST("ME", 0xFF1B6F8A),
    AFRICA("AF", 0xFF8C5A2B),
    AMERICAS("AM", 0xFFB03A3A),
    EUROPE("EU", 0xFF2F4A6B),
    MORE("+", 0xFF6B6F76);

    companion object {
        /** The family of a cuisine value ("ramen" -> JAPAN); [areaCountry] settles "regional" and "local". */
        fun of(cuisine: String, areaCountry: String? = null): CuisineFamily {
            val v = CuisineTaxonomy.canonical(cuisine)
            CuisineTaxonomy.BY_VALUE[v]?.let { return it }
            if (v == "regional" || v == "local") return areaCountry?.let { byCountry(it) } ?: MORE
            Cuisine.country(v)?.let { c -> byCountry(c)?.let { return it } }
            return MORE
        }

        /** The family of a country's national cuisine (ISO 3166 alpha-2), or null for a country Atlas does not place. */
        fun byCountry(iso: String): CuisineFamily? = CuisineTaxonomy.BY_COUNTRY[iso.uppercase()]
    }
}

/** The cuisine → family table, and the aliases mappers use for the same thing. */
object CuisineTaxonomy {
    /** Spellings that mean the same row: "pancakes" is "pancake", "italian_pizza" is "pizza". */
    private val ALIASES = mapOf(
        "pancakes" to "pancake", "noodles" to "noodle", "italian_pizza" to "pizza", "crepes" to "crepe", "chips" to "fries",
        "tacos" to "mexican", "burgers" to "burger", "doner" to "kebab", "döner" to "kebab", "kebap" to "kebab", "gyros" to "greek",
        "dumplings" to "dumpling", "donuts" to "donut", "doughnut" to "donut", "teahouse" to "tea", "coffee" to "coffee_shop",
        "fried_chicken" to "chicken", "bbq" to "barbecue", "wok" to "noodle", "sushi_bar" to "sushi", "pokè" to "poke",
        "gelato" to "ice_cream", "frozen_yoghurt" to "frozen_yogurt", "bubbletea" to "bubble_tea",
    )

    /** The row a cuisine value belongs to, after aliases. */
    fun canonical(value: String): String {
        val v = value.trim().lowercase().replace(' ', '_')
        return ALIASES[v] ?: v
    }

    internal val BY_VALUE: Map<String, CuisineFamily> = buildMap {
        fun put(f: CuisineFamily, vararg v: String) = v.forEach { put(it, f) }
        put(CuisineFamily.ITALY, "italian", "pizza", "pasta", "trattoria", "focaccia", "piadina")
        put(
            CuisineFamily.JAPAN, "japanese", "sushi", "ramen", "udon", "soba", "izakaya", "yakitori", "beef_bowl", "tempura",
            "teppanyaki", "okonomiyaki", "donburi", "onigiri", "katsu", "gyoza", "kaiseki", "omakase", "tonkatsu",
        )
        put(
            CuisineFamily.CHINA_KOREA, "chinese", "cantonese", "sichuan", "dim_sum", "dumpling", "hotpot", "korean", "korean_bbq",
            "taiwanese", "mongolian", "hong_kong", "noodle", "shanghainese", "hunan", "peking_duck", "bibimbap",
        )
        put(
            CuisineFamily.SOUTHEAST_ASIA, "thai", "vietnamese", "indonesian", "malaysian", "filipino", "singaporean", "lao",
            "cambodian", "burmese", "asian", "oriental", "pho", "banh_mi", "rijsttafel",
        )
        put(CuisineFamily.INDIA, "indian", "nepalese", "pakistani", "sri_lankan", "curry", "tibetan", "bangladeshi", "tandoori", "biryani")
        put(
            CuisineFamily.MIDDLE_EAST, "lebanese", "turkish", "kebab", "shawarma", "falafel", "persian", "arab", "arabic", "syrian",
            "israeli", "middle_eastern", "afghan", "iraqi", "jordanian", "egyptian", "mezze", "hummus", "kurdish", "yemeni",
        )
        put(
            CuisineFamily.AFRICA, "african", "ethiopian", "eritrean", "moroccan", "nigerian", "ghanaian", "senegalese", "tunisian",
            "algerian", "south_african", "kenyan", "somali", "west_african",
        )
        put(
            CuisineFamily.AMERICAS, "american", "burger", "diner", "hot_dog", "chicken", "wings", "bagel", "barbecue", "steak_house",
            "tex-mex", "mexican", "burrito", "latin_american", "south_american", "caribbean", "hawaiian", "poke", "argentinian",
            "brazilian", "peruvian", "bolivian", "jamaican", "cuban", "colombian", "venezuelan", "chilean", "surinamese",
            "canadian", "cajun", "soul_food",
        )
        put(
            CuisineFamily.EUROPE, "french", "spanish", "tapas", "greek", "portuguese", "dutch", "belgian", "german", "bavarian",
            "british", "irish", "fish_and_chips", "pancake", "friture", "fries", "crepe", "waffle", "polish", "russian", "ukrainian",
            "georgian", "hungarian", "balkan", "croatian", "scandinavian", "danish", "swedish", "swiss", "czech", "austrian",
            "mediterranean", "european", "bistro", "brasserie", "basque", "catalan", "sausage", "schnitzel", "fondue", "nordic",
        )
    }

    internal val BY_COUNTRY: Map<String, CuisineFamily> = buildMap {
        fun put(f: CuisineFamily, vararg c: String) = c.forEach { put(it, f) }
        put(CuisineFamily.ITALY, "IT", "SM", "VA")
        put(CuisineFamily.JAPAN, "JP")
        put(CuisineFamily.CHINA_KOREA, "CN", "KR", "KP", "TW", "HK", "MO", "MN")
        put(CuisineFamily.SOUTHEAST_ASIA, "TH", "VN", "ID", "MY", "PH", "SG", "LA", "KH", "MM", "BN", "TL")
        put(CuisineFamily.INDIA, "IN", "NP", "PK", "LK", "BD", "BT", "MV")
        put(CuisineFamily.MIDDLE_EAST, "LB", "TR", "SY", "IL", "PS", "IR", "AF", "IQ", "JO", "AE", "SA", "EG", "YE", "KW", "QA", "OM", "BH", "AZ", "AM")
        put(
            CuisineFamily.AFRICA, "ET", "ER", "MA", "DZ", "TN", "LY", "NG", "GH", "SN", "CI", "CM", "KE", "TZ", "UG", "ZA", "SO", "SD",
            "CD", "AO", "MZ", "ZW", "MG", "ML", "BF",
        )
        put(
            CuisineFamily.AMERICAS, "US", "CA", "MX", "BR", "AR", "PE", "BO", "JM", "CU", "CO", "VE", "CL", "EC", "UY", "PY", "SR",
            "GY", "DO", "HT", "PR", "TT", "GT", "HN", "SV", "NI", "CR", "PA",
        )
        put(
            CuisineFamily.EUROPE, "FR", "ES", "PT", "GR", "NL", "BE", "LU", "DE", "AT", "CH", "GB", "IE", "PL", "CZ", "SK", "HU",
            "RO", "BG", "HR", "SI", "RS", "BA", "ME", "MK", "AL", "RU", "UA", "BY", "GE", "DK", "SE", "NO", "FI", "IS", "EE",
            "LV", "LT", "MT", "CY",
        )
    }
}
