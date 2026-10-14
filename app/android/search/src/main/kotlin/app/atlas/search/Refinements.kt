package app.atlas.search

import app.atlas.places.OpeningHours
import java.time.LocalDateTime

/** A narrower search offered under the box ("Open now", "Step-free"): its chip, its query, and how many it leaves. */
data class Refinement(val chip: Chip, val query: Query, val count: Int)

/**
 * The refinements worth offering for a kind of place, judged from the places themselves: "Open now" when some are
 * open and some are not, "Open tonight" when that differs again, then the properties mapped on some of them (step-free,
 * wifi, a terrace…), the commonest first. A chip that would keep all of them, or none, is left out.
 */
internal object Refinements {
    private const val MAX = 4

    /** Properties worth a chip, in the order they win ties. */
    private val OFFERED = listOf(
        "wheelchair", "wifi", "terrace", "vegan", "vegetarian", "gluten_free", "halal", "takeaway", "delivery", "dogs",
        "organic", "outlet", "garden", "kids", "free", "always_open", "fast",
    )

    private val LABELS: Map<String, Map<String, String>> = mapOf(
        "now" to mapOf("en" to "Open now", "nl" to "Nu open", "de" to "Jetzt offen", "fr" to "Ouvert maintenant"),
        "tonight" to mapOf("en" to "Open tonight", "nl" to "Vanavond open", "de" to "Heute Abend offen", "fr" to "Ouvert ce soir"),
        "wheelchair" to mapOf("en" to "Step-free", "nl" to "Drempelvrij", "de" to "Stufenlos", "fr" to "Accessible"),
        "wifi" to mapOf("en" to "Wi-Fi", "nl" to "Wifi", "de" to "WLAN", "fr" to "Wi-Fi"),
        "terrace" to mapOf("en" to "Terrace", "nl" to "Terras", "de" to "Terrasse", "fr" to "Terrasse"),
        "vegan" to mapOf("en" to "Vegan", "nl" to "Vegan", "de" to "Vegan", "fr" to "Végane"),
        "vegetarian" to mapOf("en" to "Vegetarian", "nl" to "Vegetarisch", "de" to "Vegetarisch", "fr" to "Végétarien"),
        "gluten_free" to mapOf("en" to "Gluten-free", "nl" to "Glutenvrij", "de" to "Glutenfrei", "fr" to "Sans gluten"),
        "halal" to mapOf("en" to "Halal", "nl" to "Halal", "de" to "Halal", "fr" to "Halal"),
        "takeaway" to mapOf("en" to "Takeaway", "nl" to "Afhalen", "de" to "Zum Mitnehmen", "fr" to "À emporter"),
        "delivery" to mapOf("en" to "Delivery", "nl" to "Bezorging", "de" to "Lieferung", "fr" to "Livraison"),
        "dogs" to mapOf("en" to "Dogs welcome", "nl" to "Honden welkom", "de" to "Hunde erlaubt", "fr" to "Chiens admis"),
        "organic" to mapOf("en" to "Organic", "nl" to "Biologisch", "de" to "Bio", "fr" to "Bio"),
        "outlet" to mapOf("en" to "Outlets", "nl" to "Stopcontacten", "de" to "Steckdosen", "fr" to "Prises"),
        "garden" to mapOf("en" to "Garden", "nl" to "Tuin", "de" to "Garten", "fr" to "Jardin"),
        "kids" to mapOf("en" to "Kid-friendly", "nl" to "Kindvriendelijk", "de" to "Kinderfreundlich", "fr" to "Enfants bienvenus"),
        "free" to mapOf("en" to "Free", "nl" to "Gratis", "de" to "Kostenlos", "fr" to "Gratuit"),
        "always_open" to mapOf("en" to "24/7", "nl" to "24/7", "de" to "24/7", "fr" to "24h/24"),
        "fast" to mapOf("en" to "Fast charging", "nl" to "Snelladen", "de" to "Schnellladen", "fr" to "Recharge rapide"),
    )

    fun label(id: String, lang: String): String = LABELS[id]?.let { it[lang] ?: it["en"] } ?: id

    /** The refinements of [base] over the places it found, [found], for [ctx]. */
    fun offer(base: Query, found: List<PlaceRecord>, ctx: EvalContext): List<Refinement> {
        if (found.size < 2) return emptyList()
        val now = ctx.now.toLocalDateTime()
        val hours = HashMap<String, OpeningHours?>()
        fun openAt(r: PlaceRecord, at: LocalDateTime): Boolean {
            val raw = r.tags["opening_hours"] ?: return OfflineEvaluator.usuallyOpen(r)
            return hours.getOrPut(raw) { OpeningHours.parseOrNull(raw) }?.isOpen(at, ctx.hours) == true
        }
        fun narrows(n: Int) = n in 1 until found.size

        val out = ArrayList<Refinement>()
        val openNow = found.count { openAt(it, now) }
        if (narrows(openNow)) {
            out += Refinement(Chip(ChipKind.OPEN, "ui-clock", label("now", ctx.lang), "now"), Query.and(base, Query.OpenAt(OpenTime.Now)), openNow)
        }
        val evening = now.toLocalDate().atTime(20, 30)
        if (now.isBefore(evening)) {
            val tonight = found.count { openAt(it, evening) }
            if (narrows(tonight) && tonight != openNow) {
                out += Refinement(Chip(ChipKind.OPEN, "ui-moon", label("tonight", ctx.lang), "tonight"), Query.and(base, Query.OpenAt(OpenTime.Tonight)), tonight)
            }
        }
        val properties = OFFERED.mapIndexedNotNull { rank, id ->
            val a = Lexicon.attributes[id] ?: return@mapIndexedNotNull null
            val n = found.count { a.filter.matches(it.tags) }
            if (!narrows(n)) return@mapIndexedNotNull null
            Triple(rank, n, Refinement(Chip(ChipKind.PROPERTY, a.symbol, label(id, ctx.lang), a.filter.toText()), Query.and(base, Query.Tags(a.filter)), n))
        }
        out += properties.sortedWith(compareByDescending<Triple<Int, Int, Refinement>> { it.second }.thenBy { it.first }).map { it.third }
        return out.take(MAX)
    }
}
