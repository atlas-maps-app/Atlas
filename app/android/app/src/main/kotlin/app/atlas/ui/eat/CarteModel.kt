package app.atlas.ui.eat

import app.atlas.places.Cuisine
import java.text.Collator
import java.time.LocalDateTime
import java.util.Locale

/** A diet the carte's bar filters on (`diet:*`), with the letters on its button. */
enum class Diet(val key: String, val code: String) {
    VEGETARIAN("vegetarian", "V"),
    VEGAN("vegan", "VG"),
    GLUTEN_FREE("gluten_free", "GF"),
    HALAL("halal", "H"),
    KOSHER("kosher", "K"),
}

/** How the carte orders its lines within each chapter. */
enum class CarteSort { NEAREST, MOST, BEST_RATED, A_Z }

/**
 * One line of the carte: a cuisine ([cuisine] canonical, or [UNMAPPED_RESTAURANT] / [UNMAPPED_FAST_FOOD] for kitchens
 * nobody tagged), its places, how many are open at the table's time, the nearest on foot, the commonest other
 * cuisines of those places ([subStyles], in italics) and the best rating among them, if anyone rated one.
 */
data class CarteLine(
    val cuisine: String,
    val name: String,
    val family: CuisineFamily,
    val places: List<EatPlace>,
    val open: Int,
    val nearestWalk: Int,
    val subStyles: List<String>,
    val bestStars: Double?,
) {
    /** True when the hours say none of its places is open now. */
    val allClosed: Boolean get() = open == 0
}

/** A chapter of the carte: one cuisine family and its lines. */
data class CarteChapter(val family: CuisineFamily, val lines: List<CarteLine>)

/**
 * The carte: every restaurant around, by family and cuisine. [counted] is what the header counts: places open at the
 * time ([countsOpen] true) or, when no hours are known (the tiles), every place.
 */
data class Carte(
    val chapters: List<CarteChapter>,
    val counted: Int,
    val countsOpen: Boolean,
    val total: Int,
    /** Places per diet among all restaurants, before the diet filter: a diet with none is shown off. */
    val dietCounts: Map<Diet, Int>,
    val anyRated: Boolean,
) {
    fun chapter(f: CuisineFamily): CarteChapter? = chapters.firstOrNull { it.family == f }
}

object CarteModel {
    const val UNMAPPED_RESTAURANT = "unmapped_restaurant"
    const val UNMAPPED_FAST_FOOD = "unmapped_fast_food"

    /** The line a place belongs on: its first cuisine with a family, else its first cuisine, else "not mapped". */
    fun lineOf(p: EatPlace, areaCountry: String?): String =
        p.cuisines.firstOrNull { CuisineFamily.of(it, areaCountry) != CuisineFamily.MORE }
            ?: p.cuisines.firstOrNull()
            ?: if (p.kind == EatKind.FAST_FOOD) UNMAPPED_FAST_FOOD else UNMAPPED_RESTAURANT

    /** The family of a line key. */
    fun familyOf(line: String, areaCountry: String?): CuisineFamily =
        if (line == UNMAPPED_RESTAURANT || line == UNMAPPED_FAST_FOOD) CuisineFamily.MORE else CuisineFamily.of(line, areaCountry)

    /** The restaurants of [area] that serve every diet of [diets]. */
    fun restaurants(area: EatArea, diets: Set<Diet>): List<EatPlace> =
        EatSets.carte(area.places).filter { p -> diets.all { p.diet(it.key) } }

    fun build(
        area: EatArea,
        at: LocalDateTime,
        sort: CarteSort,
        diets: Set<Diet>,
        ratings: AreaRatings,
        lang: String,
        names: (String) -> String = { Cuisine.name(it, lang) },
    ): Carte {
        val all = EatSets.carte(area.places)
        val places = all.filter { p -> diets.all { p.diet(it.key) } }
        val countsOpen = all.any { it.hasHours }
        val open = HashSet<String>()
        if (countsOpen) for (p in places) if (p.openness(at, area.hours).isOpen) open += p.key
        val country = area.country
        val byLine = places.groupBy { lineOf(it, country) }
        val collator = Collator.getInstance(Locale.forLanguageTag(lang))
        val lines = byLine.map { (key, ps) ->
            val subs = ps.flatMap { p -> p.cuisines.filter { it != key } }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(2).map { names(it.key).lowercase(Locale.forLanguageTag(lang)) }
            CarteLine(
                cuisine = key,
                name = names(key),
                family = familyOf(key, country),
                places = ps.sortedBy { it.distanceM },
                open = if (countsOpen) ps.count { it.key in open } else ps.size,
                nearestWalk = ps.minOf { it.walkMinutes },
                subStyles = subs,
                bestStars = ps.mapNotNull { ratings.stars(it.key) }.maxOrNull(),
            )
        }
        val order: Comparator<CarteLine> = when (sort) {
            CarteSort.NEAREST -> compareBy<CarteLine> { it.nearestWalk }.thenBy { it.places.first().distanceM }
            CarteSort.MOST -> compareByDescending<CarteLine> { it.open }.thenByDescending { it.places.size }.thenBy { it.nearestWalk }
            CarteSort.BEST_RATED -> compareByDescending<CarteLine> { it.bestStars ?: -1.0 }.thenByDescending { it.open }.thenBy { it.nearestWalk }
            CarteSort.A_Z -> Comparator { a, b -> collator.compare(a.name, b.name) }
        }
        // The kitchens nobody tagged always close the book.
        val unmappedLast = compareBy<CarteLine> { it.cuisine == UNMAPPED_RESTAURANT || it.cuisine == UNMAPPED_FAST_FOOD }
        val chapters = CuisineFamily.entries.mapNotNull { f ->
            val ls = lines.filter { it.family == f }.sortedWith(unmappedLast.then(order))
            if (ls.isEmpty()) null else CarteChapter(f, ls)
        }
        return Carte(
            chapters = chapters,
            counted = if (countsOpen) open.size else places.size,
            countsOpen = countsOpen,
            total = places.size,
            dietCounts = Diet.entries.associateWith { d -> all.count { it.diet(d.key) } },
            anyRated = places.any { ratings.stars(it.key) != null },
        )
    }

    /** The places of one carte line (a cuisine key, as [lineOf] gives), nearest first. */
    fun placesOf(area: EatArea, line: String): List<EatPlace> =
        EatSets.carte(area.places).filter { lineOf(it, area.country) == line }.sortedBy { it.distanceM }
}
