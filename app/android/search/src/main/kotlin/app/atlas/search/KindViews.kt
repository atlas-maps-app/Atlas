package app.atlas.search

import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.FactState
import app.atlas.places.Facts
import app.atlas.places.NodeKind
import app.atlas.places.TagFilter
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A typed query as a kind's own view with its filters chosen (decision 12: one search): "vegan restaurant open now"
 * is the restaurants with vegan and open now chosen, the same view a tap on the kind and its chips gives. [at] is a
 * time asked about, [radius] a circle asked for, [order] an order asked for, [aim] something to do there.
 */
data class KindView(
    val node: CategoryNode,
    val selection: FacetSelection,
    val at: LocalDateTime? = null,
    val radius: Double? = null,
    val order: Order? = null,
    val aim: Intent? = null,
)

/** Reads typed queries as kinds' views, when all of a query is what a view can show. */
object KindViews {
    /**
     * [parsed] as a kind's view, or null when some of it is more than a view's filters hold (a "near", an area, a
     * want of a place nearby, a plan, a count): then it stays a query of its own.
     */
    fun of(parsed: ParsedQuery, now: ZonedDateTime): KindView? {
        if (parsed.isPlan || parsed.ask != Ask.LIST || parsed.route != null || parsed.steps.isEmpty()) return null
        val aim = parsed.intent?.let { Intents.byId(it) }
        var q = parsed.query
        var order: Order? = null
        if (q is Query.Sort) {
            order = ORDERS[q.by] ?: return null
            q = q.query
        }
        val parts = if (q is Query.And) q.parts else listOf(q)
        var node: CategoryNode? = null
        val ids = ArrayList<String>()
        var at: LocalDateTime? = null
        var radius: Double? = null
        for (p in parts) {
            val kind = if (node == null) kindOf(p) else null
            when {
                kind != null -> { node = kind.first; ids += kind.second }
                p is Query.Tags -> ids += facetIds(p.filter) ?: return null
                p is Query.Not && p.part is Query.Tags -> ids += without((p.part as Query.Tags).filter) ?: return null
                p is Query.OpenAt -> when (val t = p.time) {
                    OpenTime.Now -> ids += "${Facets.OPEN}=now"
                    OpenTime.Tonight -> ids += "${Facets.OPEN}=tonight"
                    OpenTime.Late -> ids += "${Facets.OPEN}=late"
                    is OpenTime.At -> at = t.time
                    is OpenTime.Today -> at = now.toLocalDate().atTime(t.time).let { if (it.isBefore(now.toLocalDateTime().minusMinutes(30))) it.plusDays(1) else it }
                    is OpenTime.OnDay -> at = now.toLocalDate().with(TemporalAdjusters.nextOrSame(t.day)).atTime(t.time ?: return null)
                }
                p is Query.Within && p.center == null -> radius = p.radius
                // what something to do wants is the view's aim, not a filter
                p is Query.Want && aim?.want != null && p.part == Query.Tags(aim.want) -> {}
                // "toilets not wheelchair accessible", "café with wifi": what is wanted, and the places that do not say
                p is Query.Want && p.nearby == null && p.part is Query.Tags -> ids += wanted((p.part as Query.Tags).filter) ?: return null
                else -> return null
            }
        }
        val n = node ?: return null
        at?.let { ids += "${Facets.OPEN}=at:$it" }
        return KindView(n, FacetSelection.of(ids), at, radius, order, aim)
    }

    /**
     * The node of the tree a query part names and the facet values that narrow it to the part: a category; the kind
     * whose tags it asks for exactly; else, for one key's values ("a bar or a pub"), the narrowest kind holding them
     * all with those values chosen of the key's facet.
     */
    private fun kindOf(p: Query): Pair<CategoryNode, List<String>>? {
        if (p is Query.Category) return CategoryTree.category(p.id)?.let { it to emptyList() }
        val f = (p as? Query.Tags)?.filter ?: return null
        BY_FILTER[norm(f)]?.let { return it to emptyList() }
        val (key, values) = when (f) {
            is TagFilter.Equals -> f.key to listOf(f.value)
            is TagFilter.Matches -> if (f.negate) return null else f.key to (alternatives(f.pattern) ?: return null)
            else -> return null
        }
        val paths = values.map { v -> CategoryTree.of(mapOf(key to v))?.let { CategoryTree.pathOf(it.id) } ?: return null }
        val shared = paths.first().takeWhile { n -> paths.all { path -> n in path } }.lastOrNull() ?: return null
        if (shared.kind == NodeKind.SHELF && shared.parentId == null) return null
        return shared to values.map { "$key=$it" }
    }

    /** A filter as a set of what it asks, so "a|b" and "b|a" are the same kind. */
    private fun norm(f: TagFilter): Any = when (f) {
        is TagFilter.Matches -> alternatives(f.pattern)?.let { Triple(f.key, it.toSet(), f.negate) } ?: f
        is TagFilter.And -> "and" to f.parts.map { norm(it) }.toSet()
        is TagFilter.Or -> "or" to f.parts.map { norm(it) }.toSet()
        else -> f
    }

    /** The facet values a tag filter asks for ("wheelchair=yes": `wheelchair=~yes`), or null when no facet holds it. */
    private fun facetIds(f: TagFilter): List<String>? = when (f) {
        is TagFilter.Equals -> listOf(id(f.key, f.value))
        is TagFilter.Matches -> alternatives(f.pattern)?.takeIf { !f.negate }?.map { id(f.key, it) }
        // "free entry": what is free, by its fee or charge
        is TagFilter.NotEquals -> if (f.key == "fee" && f.value == "yes") listOf("${Facets.PRICE}=free") else null
        // one facet holds only one key: "wheelchair=yes or toilets:wheelchair=yes" chooses the first key's values
        is TagFilter.Or -> f.parts.mapNotNull { facetIds(it) }.flatten().let { ids -> ids.filter { it.substringBefore('=') == ids.firstOrNull()?.substringBefore('=') } }.ifEmpty { null }
        else -> null
    }

    /** A wish: the facet values it asks for and its key's "not said", for the places that may have it. */
    private fun wanted(f: TagFilter): List<String>? {
        val ids = facetIds(f) ?: return null
        val key = ids.firstOrNull()?.substringBefore('=') ?: return null
        return ids + "$key=${Facets.MISSING}"
    }

    /** "Without wheelchair access": the places that say no, and those that say nothing. */
    private fun without(f: TagFilter): List<String>? = when (f) {
        is TagFilter.Equals -> if (Facts.stateOf(f.value) == FactState.YES) listOf("${f.key}=~no", "${f.key}=${Facets.MISSING}") else null
        is TagFilter.Matches -> if (!f.negate && alternatives(f.pattern)?.any { Facts.stateOf(it) == FactState.YES } == true) listOf("${f.key}=~no", "${f.key}=${Facets.MISSING}") else null
        is TagFilter.Or -> f.parts.firstNotNullOfOrNull { without(it) }
        else -> null
    }

    private fun id(key: String, value: String): String =
        Facts.stateOf(value)?.let { "$key=~${it.name.lowercase(Locale.ROOT)}" } ?: "$key=$value"

    /** The values of a plain alternation, `^(yes|only)$`; null for any other pattern. */
    private fun alternatives(pattern: String): List<String>? =
        Regex("""\^\(?([\w:.-]+(?:\|[\w:.-]+)*)\)?\$""").matchEntire(pattern)?.groupValues?.get(1)?.split('|')

    private val ORDERS = mapOf(
        SortBy.DISTANCE to Order.NEAREST, SortBy.RATING to Order.RATING, SortBy.OPEN_LONGEST to Order.OPEN_LONGEST,
        SortBy.CHEAPEST to Order.CHEAPEST, SortBy.RELEVANCE to Order.FIT, SortBy.POWER to Order.POWER, SortBy.STARS to Order.STARS,
        SortBy.LARGEST to Order.LARGEST,
    )

    /** The kinds of the tree by the filter they ask for, the shallowest first where two ask the same. */
    private val BY_FILTER: Map<Any, CategoryNode> by lazy {
        CategoryTree.all().filter { it.kind == NodeKind.PRESET || it.kind == NodeKind.CATEGORY }
            .sortedBy { CategoryTree.pathOf(it.id).size }
            .groupBy { norm(it.filter) }.mapValues { it.value.first() }
    }
}
