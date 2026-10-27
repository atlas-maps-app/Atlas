package app.atlas.ui.search

import app.atlas.mapview.AskedBuilding
import app.atlas.model.OsmId
import app.atlas.providers.OsmElement

/**
 * Where the FillIn board's flow stands (its script's state: step, colour, roof, sent), for one building whose open
 * questions are [asks]: each question in turn, then the review of the edit, then done. Pure, so the flow is tested apart
 * from the screen.
 */
internal data class FillInState(
    val asks: List<FillIn.Ask>,
    val step: Int = 0,
    val colour: FillIn.Colour? = null,
    val roof: FillIn.Roof? = null,
    val sent: Boolean = false,
) {
    /** The question asked now, or null on the review and after it. */
    val ask: FillIn.Ask? get() = asks.getOrNull(step)

    val reviewing: Boolean get() = step == asks.size

    val done: Boolean get() = step > asks.size

    /** The tags the answers add, as the review lists them. */
    val adds: List<Pair<String, String>> get() = FillIn.adds(colour, roof)

    /**
     * The building [id] as the board draws it while its front's colour is asked: plain in the board's grey, hatched,
     * until the colour is answered ("Can't tell" leaves it open), in the answer's colour after, through the end; null
     * where the colour is mapped already, so the map draws the building as mapped.
     */
    fun asked(id: OsmId): AskedBuilding? = when {
        FillIn.Ask.COLOUR !in asks -> null
        colour != null -> AskedBuilding(id, colour.drawn, hatched = false)
        else -> AskedBuilding(id, FillIn.OPEN_WALL, hatched = true)
    }

    /** Whether the question asked now has its answer (the board's Next is dimmed until it has). */
    val canNext: Boolean get() = when (ask) {
        FillIn.Ask.COLOUR -> colour != null
        FillIn.Ask.ROOF -> roof != null
        null -> false
    }

    fun pick(c: FillIn.Colour) = copy(colour = c)

    fun pick(r: FillIn.Roof) = copy(roof = r)

    fun next() = if (canNext) copy(step = step + 1) else this

    /** "Can't tell": the question's answer forgotten, on to the next. */
    fun skip() = when (ask) {
        FillIn.Ask.COLOUR -> copy(step = step + 1, colour = null)
        FillIn.Ask.ROOF -> copy(step = step + 1, roof = null)
        null -> this
    }

    /** "← colour" and the review's "change": back to the first question, the answers kept. */
    fun restart() = copy(step = 0)

    /** Send (or leave it for someone else): done, sent where the answers add something. */
    fun send() = copy(step = asks.size + 1, sent = adds.isNotEmpty())

    companion object {
        /** The keys the renders guess, in the order the review names those still guessed. */
        private val GAPS = listOf("building:colour", "roof:shape", "height", "start_date", "building:levels", "roof:colour", "roof:height")

        /** What stays a guess on [tags] once [adds] are mapped (a colour counts as mapped with a material alone). */
        fun stillGuessed(tags: Map<String, String>, adds: List<Pair<String, String>>): List<String> {
            val now = tags + adds
            return GAPS.filter { k ->
                when (k) {
                    "building:colour" -> now["building:colour"].isNullOrBlank() && now["building:material"].isNullOrBlank()
                    else -> now[k].isNullOrBlank()
                }
            }
        }

        /** The tags the review lists first: what says most about a building. */
        private val TELLING = listOf("building", "height", "start_date", "building:levels", "addr:street", "addr:housenumber", "name")

        /**
         * The building's own tags as the review lists them: all of them where they fit the board's three lines, else the
         * two that say most, the third line counting the rest.
         */
        fun reviewTags(tags: Map<String, String>): List<Pair<String, String>> {
            val ordered = tags.entries.sortedWith(compareBy({ TELLING.indexOf(it.key).let { i -> if (i < 0) TELLING.size else i } }, { it.key }))
                .map { it.key to it.value }
            return if (ordered.size <= 3) ordered else ordered.take(2)
        }

        /** The building's line under the title: its address, when it was built and its height ("Leliegracht 49 · built 1673 · 16.1 m"). */
        fun address(tags: Map<String, String>): String? {
            val street = tags["addr:street"]
            val number = tags["addr:housenumber"]
            return when {
                street != null && number != null -> "$street $number"
                street != null -> street
                else -> tags["name"]
            }
        }

        /** The element's line atop the review ("way 266616341 · Leliegracht 49"). */
        fun elementLine(e: OsmElement): String = listOfNotNull("${e.type.api} ${e.id}", address(e.tags)).joinToString(" · ")
    }
}
