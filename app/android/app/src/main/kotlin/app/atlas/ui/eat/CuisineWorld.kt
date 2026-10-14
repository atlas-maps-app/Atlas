package app.atlas.ui.eat

/**
 * One plank of a cuisine world: the native word and its latin reading on the wooden tab, the title on the curtain
 * when it is chosen, and the cuisine values (canonical) of its places. [drinks] are the cuisines that put a bar or a
 * pub on this plank (an izakaya is as often tagged a bar with `cuisine=japanese` as a restaurant).
 */
data class Plank(
    val id: String,
    val native: String,
    val latin: String,
    val title: String,
    val cuisines: Set<String>,
    val drinks: Set<String> = emptySet(),
)

/**
 * A cuisine as its own world, the Ramen board generalised: the header object (a noren curtain woven of [curtain] with
 * an ensō of [mark]), the wooden planks that pick a style, and the words of its ticket machine. The skin is data: a
 * new world is a new value here, not new drawing code.
 */
data class CuisineWorld(
    val id: String,
    val family: CuisineFamily,
    val planks: List<Plank>,
    /** Planks in the order a place is put on one: the most specific first. */
    val specificity: List<String>,
    /** The curtain's woven stripes, repeating left to right: (colour, width in px) as the board's gradient. */
    val curtain: List<Pair<Long, Float>>,
    /** The brush ensō's colour. */
    val mark: Long,
    /** The title's colour on the curtain and the colour of the reading under it. */
    val title: Long,
    val reading: Long,
    /** The machine's own word for a meal ticket ("食券"). */
    val ticket: String,
    /** What a closed button says before its opening time ("準備中", "in preparation"). */
    val preparing: String,
) {
    /** Every cuisine value of the world. */
    val cuisines: Set<String> = planks.flatMapTo(HashSet()) { it.cuisines }

    private val ordered: List<Plank> = specificity.map { id -> planks.first { it.id == id } }

    /** The plank [p] belongs on, or null when it is not of this world. */
    fun plankOf(p: EatPlace): Plank? {
        val cs = p.cuisines
        if (cs.isEmpty()) return null
        return when {
            p.kind.isMeal -> ordered.firstOrNull { plank -> cs.any { it in plank.cuisines } }
            p.kind.isDrinks -> ordered.firstOrNull { plank -> cs.any { it in plank.drinks } }
            else -> null
        }
    }

    /** The places of [plank] in [area], open ones first, then nearest. */
    fun places(area: EatArea, plank: Plank): List<EatPlace> = area.places.filter { plankOf(it) == plank }

    fun plankIndex(id: String): Int = planks.indexOfFirst { it.id == id }
}

/** The cuisine worlds Atlas has skins for, and how the carte and routes reach them. */
object CuisineWorlds {
    val JAPAN = CuisineWorld(
        id = "japanese",
        family = CuisineFamily.JAPAN,
        planks = listOf(
            Plank("sushi", "寿司", "SUSHI", "スシ", setOf("sushi", "omakase", "kaiten")),
            Plank("ramen", "拉麺", "RAMEN", "ラーメン", setOf("ramen", "tsukemen")),
            Plank("izakaya", "居酒屋", "IZAKAYA", "居酒屋", setOf("izakaya", "yakitori"), drinks = setOf("japanese", "izakaya", "yakitori", "sake")),
            Plank("soba", "蕎麦", "SOBA", "そば", setOf("soba", "udon")),
            Plank(
                "japanese", "和食", "WASHOKU", "和食",
                setOf("japanese", "beef_bowl", "tempura", "teppanyaki", "okonomiyaki", "donburi", "onigiri", "katsu", "gyoza", "kaiseki", "tonkatsu"),
            ),
        ),
        specificity = listOf("sushi", "ramen", "soba", "izakaya", "japanese"),
        curtain = listOf(0xFF1E2F5A to 2f, 0xFF22355F to 1f, 0xFF1C2B54 to 2f),
        mark = 0xFFF3EEE3,
        title = 0xFFFFFFFF,
        reading = 0xFFC9D2EA,
        ticket = "食券",
        preparing = "準備中",
    )

    val all: List<CuisineWorld> = listOf(JAPAN)

    /** The world and plank of a route's kind: "ramen", "sushi"…, or "world:japanese" (its first plank with places). */
    fun route(kind: String): Pair<CuisineWorld, Int>? {
        if (kind.startsWith("world:")) {
            val id = kind.removePrefix("world:")
            val w = all.firstOrNull { it.id == id } ?: return null
            return w to -1
        }
        for (w in all) {
            val i = w.plankIndex(kind)
            if (i >= 0) return w to i
        }
        return null
    }

    /** The world a carte line opens, with its plank: a Japanese cuisine opens the ticket machine. */
    fun forCuisine(cuisine: String): Pair<CuisineWorld, Plank>? {
        for (w in all) {
            for (id in w.specificity) {
                val plank = w.planks.first { it.id == id }
                if (cuisine in plank.cuisines) return w to plank
            }
        }
        return null
    }

    /** The world of a whole family, if it has one. */
    fun forFamily(f: CuisineFamily): CuisineWorld? = all.firstOrNull { it.family == f }
}
