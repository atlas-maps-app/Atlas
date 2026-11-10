package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import java.text.Collator
import java.util.Locale

/**
 * The plain list of every kind around (the owner, 6 November: "a plain all kinds list row sortable with every tag/kind
 * that exists"): every kind the places hold, each place under its narrowest (a second-hand clothes shop under its own
 * kind, and among the clothes as well), with its pulse as a tile has it; the most, the nearest or by name first.
 */
object EveryKind {
    /** How the list is sorted: the kinds with the most places first, those whose nearest is nearest, or by name. */
    enum class Order { MOST, NEAREST, NAME }

    /**
     * Every kind among [places], read around [from], or every one under [scope] (a family's shelf, a kind), as its
     * pulse: how many, how many open, the nearest and the minutes to it.
     */
    fun around(places: List<PlaceRecord>, from: LatLon, moment: Moment, hours: HoursBook = HoursBook(places), scope: CategoryNode? = null): List<CategoryPulse> {
        val found = places.mapNotNullTo(LinkedHashSet()) { CategoryTree.of(it.tags, PlaceRecord.geometryOf(it.place)) }
            .filter { it.kind != NodeKind.SHELF && (scope == null || CategoryTree.isUnder(it, scope)) }
        // a family's own node (a category, a key), or the kind the list is of, stands for its places of no kind of their
        // own; beside the kinds under it, it would count them all again ("Shops, 227" among the shops), so it is listed
        // only where none is
        val kinds = found.filter { n ->
            n.kind == NodeKind.PRESET && n.id != scope?.id || found.none { it.id != n.id && CategoryTree.isUnder(it, n) }
        }
        // a kind holds its category's places only (those of none, on the shelf of everything else): each is counted
        // among them, not among all the places around
        val byCategory = places.groupBy { it.category }
        return kinds.groupBy { if (it.category.family == CategoryTree.OSM) null else it.category.id }
            .flatMap { (category, nodes) -> Browse.pulses(nodes, byCategory[category].orEmpty(), from, moment, hours) }
            .filter { it.count > 0 }
    }

    /**
     * Every kind [scope] holds, near or not (the owner, 6 November: "Theres no way to see every kind that belongs to a
     * category from within that category. Only the ones that exist nearby"): every preset under it at any depth, a
     * category or key with none under it standing for itself; for a shelf, those of the kinds it holds too. In the
     * tree's order; none for a kind with nothing narrower, and none for the shelf of everything else on the map, whose
     * thousand kinds (a power line, a tree) are no list to read: its keys' are.
     */
    fun of(scope: CategoryNode): List<CategoryNode> {
        if (scope.kind == NodeKind.SHELF && scope.category.family == CategoryTree.OSM) return emptyList()
        val out = LinkedHashMap<String, CategoryNode>()
        fun walk(n: CategoryNode) {
            val under = n.children
            if (n.kind == NodeKind.PRESET || under.isEmpty()) out[n.id] = n
            under.forEach(::walk)
        }
        scope.children.forEach(::walk)
        scope.guests.forEach(::walk)
        return out.values.toList()
    }

    /** [near], the kinds among the places around as [around] has them, then every other kind [scope] holds ([of]), none of which is near. */
    fun all(scope: CategoryNode, near: List<CategoryPulse>): List<CategoryPulse> {
        val have = near.mapTo(HashSet()) { it.node.id }
        return near + of(scope).filter { it.id !in have }.map { CategoryPulse.none(it) }
    }

    /**
     * The chains behind each of [kinds] among [places] (the owner, 6 November, of this list: "For every kind if there are
     * brands behind it show them hiding behind the category symbol?"), by the kind's node id: those with two branches or
     * more of it here, the most first, at most [most]; a kind with none has no entry.
     */
    fun chains(kinds: List<CategoryPulse>, places: List<PlaceRecord>, most: Int = 3): Map<String, List<String>> {
        val branded = places.filter { it.brand != null }
        if (branded.isEmpty()) return emptyMap()
        return kinds.mapNotNull { k ->
            branded.filter { Browse.holds(k.node, it) }.groupingBy { it.brand!! }.eachCount().filterValues { it >= 2 }.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(most).map { it.key }
                .takeIf { it.isNotEmpty() }?.let { k.node.id to it }
        }.toMap()
    }

    /**
     * A row of the list: a kind's pulse, how many kinds on the list it stands under (an organic supermarket: 1), and the
     * one it stands right under, which says the rest of its name ("Organic" under "Supermarket", [CategoryNode.nameUnder]).
     */
    data class Row(val pulse: CategoryPulse, val depth: Int, val under: CategoryNode? = null)

    /**
     * [kinds] in [order], those with none open left out when [openOnly]; names compared as [lang] sorts them. A narrower
     * kind follows the broader one it is a kind of, when that is listed too and is near as it is, or none near as it is
     * (the owner, 6 November: "Organic supermarket belongs to supermarket?"): the organic supermarkets under the
     * supermarkets, which count them, the pizzerias under the restaurants, each kind's narrower ones in [order] too. The
     * cuisines none of which is near stay with the kinds none of which is near, not between the restaurants and the cafés.
     */
    fun sorted(kinds: List<CategoryPulse>, order: Order, lang: String, openOnly: Boolean = false): List<Row> {
        val collator = Collator.getInstance(Locale.forLanguageTag(lang))
        val byName = Comparator<CategoryPulse> { a, b -> collator.compare(a.node.name(lang), b.node.name(lang)) }
        val nearest = compareBy<CategoryPulse, Double?>(nullsLast()) { it.metres }
        val kept = if (openOnly) kinds.filter { it.open > 0 } else kinds
        val by = when (order) {
            Order.MOST -> compareByDescending<CategoryPulse> { it.count }.then(nearest).then(byName)
            Order.NEAREST -> nearest.then(byName)
            Order.NAME -> byName
        }
        val listed = kept.associateBy { it.node.id }
        fun broader(k: CategoryPulse): String? {
            var n = k.node.parent
            while (n != null && listed[n.id]?.let { (it.count > 0) == (k.count > 0) } != true) n = n.parent
            return n?.id
        }
        val under = kept.groupBy(::broader)
        val out = ArrayList<Row>(kept.size)
        fun put(id: String?, depth: Int) {
            for (k in under[id].orEmpty().sortedWith(by)) {
                out += Row(k, depth, id?.let { listed[it]?.node })
                put(k.node.id, depth + 1)
            }
        }
        put(null, 0)
        return out
    }
}
