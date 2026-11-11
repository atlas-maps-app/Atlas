package app.atlas.ui.eat

import androidx.compose.ui.text.TextStyle
import app.atlas.places.Cuisine

/**
 * A cuisine's house, as the Cuisines and HouseCards boards dress it: the typeface its places' names wear on the Eat card
 * ([face], a CSS family the boards name, in its [weight] and slant), its [ink], light [tint] and the [chip] behind their
 * ratings, the motif it hangs ([houseMotif]) and the cuisine values (canonical) that live in it. Every house sits in one
 * of the carte's [family] regions: the Houses board (5 November) fuses the carte's chapters, the plates and the Japanese
 * world into one house per cuisine.
 */
enum class CuisineHouse(
    val face: String,
    val weight: Int,
    val italic: Boolean,
    val ink: Long,
    val tint: Long,
    val chip: Long,
    val family: CuisineFamily,
    val cuisines: Set<String>,
) {
    ITALIANA(
        "Bodoni Moda", 600, true, 0xFF8E3A1E, 0xFFFBF5EA, 0xFFF1E3CF, CuisineFamily.ITALY,
        setOf("italian", "pizza", "pasta", "trattoria", "focaccia", "piadina"),
    ),
    JAPANESE(
        "Shippori Mincho", 700, false, 0xFF1E2F5A, 0xFFF6F1E6, 0xFFE6E6EE, CuisineFamily.JAPAN,
        setOf(
            "japanese", "sushi", "ramen", "udon", "soba", "izakaya", "yakitori", "beef_bowl", "tempura", "teppanyaki",
            "okonomiyaki", "donburi", "onigiri", "katsu", "gyoza", "kaiseki", "omakase", "tonkatsu",
        ),
    ),
    MEXICANA("DM Serif Display", 400, false, 0xFFB0145A, 0xFFFFF8EC, 0xFFFBE3EE, CuisineFamily.AMERICAS, setOf("mexican", "burrito", "tex-mex")),
    INDIAN("Yatra One", 400, false, 0xFF8E4A0E, 0xFFFDF6E8, 0xFFF6E3C2, CuisineFamily.INDIA, setOf("indian", "curry", "nepalese", "tandoori", "biryani")),
    CHINESE(
        "ZCOOL XiaoWei", 400, false, 0xFF8A2220, 0xFFFBF1EC, 0xFFF5DED8, CuisineFamily.CHINA_KOREA,
        setOf("chinese", "dim_sum", "sichuan", "cantonese", "hotpot", "dumpling", "shanghainese", "hunan", "peking_duck"),
    ),
    KOREAN("Gowun Batang", 700, false, 0xFF1F5B42, 0xFFF4F6F1, 0xFFE1ECE5, CuisineFamily.CHINA_KOREA, setOf("korean", "korean_bbq", "bibimbap")),
    THAI("Chonburi", 400, false, 0xFF0D4F3C, 0xFFF2F6F2, 0xFFDDEBE3, CuisineFamily.SOUTHEAST_ASIA, setOf("thai")),
    VIET("Be Vietnam Pro", 600, false, 0xFF1F5F4E, 0xFFF3F7F4, 0xFFDCEBE5, CuisineFamily.SOUTHEAST_ASIA, setOf("vietnamese", "pho", "banh_mi")),
    LEVANTINE(
        "Amiri", 700, false, 0xFF164478, 0xFFF1F5F8, 0xFFDDE7F2, CuisineFamily.MIDDLE_EAST,
        setOf("lebanese", "falafel", "shawarma", "turkish", "kebab", "syrian", "mezze", "hummus"),
    ),
    ETHIOPIAN("Noto Serif Ethiopic", 600, false, 0xFF7A2A1E, 0xFFFBF4EA, 0xFFF1E1CC, CuisineFamily.AFRICA, setOf("ethiopian", "eritrean")),
    BISTROT("Italiana", 400, false, 0xFF1F4A3A, 0xFFF2F1EA, 0xFFE2E6DC, CuisineFamily.EUROPE, setOf("french", "crepe", "fondue", "bistro", "brasserie")),
    DINER(
        "Righteous", 400, false, 0xFFB02A23, 0xFFF7F4F2, 0xFFF3E0DE, CuisineFamily.AMERICAS,
        setOf("american", "burger", "diner", "barbecue", "steak_house", "hot_dog"),
    );

    /** A name as the house writes it: its typeface, weight, slant and ink, on a line of [size]. */
    internal fun nameStyle(size: Float): TextStyle = cssText(face, size, weight, italic, colour = ink, lineHeight = size)

    companion object {
        private val BY_CUISINE: Map<String, CuisineHouse> = entries.flatMap { h -> h.cuisines.map { it to h } }.toMap()

        /** The house [p] wears, or null for a place in none, which keeps its plate. */
        fun of(p: EatPlace): CuisineHouse? = of(p.cuisines)

        /**
         * The house of the first of [cuisines] (canonical, in mapped order) that has one, unless a country's own
         * kitchen with no house comes before it: "dutch;regional;pizza" is a Dutch place and "argentinian;steak_house"
         * an Argentinian grill, while "asian;sushi" is a sushi bar.
         */
        internal fun of(cuisines: List<String>): CuisineHouse? {
            for (c in cuisines) {
                BY_CUISINE[c]?.let { return it }
                if (Cuisine.country(c) != null) return null
            }
            return null
        }
    }
}
