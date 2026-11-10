package app.atlas.places

import java.util.Locale

/**
 * What a node of the [CategoryTree] stands for: a shelf of the category grid, an Atlas category, an OpenStreetMap key
 * on the shelf of everything else on the map ("natural"), or a preset under one of those.
 */
enum class NodeKind { SHELF, CATEGORY, KEY, PRESET }

/**
 * One node of the category tree, the search's way into every kind of place: a shelf of the grid (a group of the Map
 * home's row, or a family's shelf), an Atlas category on it, or an id-tagging-schema preset under that category, each
 * preset under its parent preset where it has one (a restaurant, then a pizzeria). [filter] says which places it holds.
 * Ids are prefixed by kind ("shelf:eat", "cat:restaurants", "preset:amenity/restaurant/pizza"): a family, a category
 * and a preset may share a name ("health", "playground"). The last shelf holds what no Atlas category takes (a lake, a
 * tree, a power line, a vacant shop), one [NodeKind.KEY] node per key, its presets under it.
 */
class CategoryNode internal constructor(
    val id: String,
    val kind: NodeKind,
    val parentId: String?,
    /** A symbol id for [Symbols.icon]: the shelf's or category's own, a preset's from its nearest ancestor with one. */
    val symbol: String,
    /** Opaque ARGB: the shelf's, the category's, or the category's for its presets (a kind's own where [KindColours] names one). */
    val colour: Int,
    /** The tags a place must pass to be in it: a shelf's leaves' rules, a category's rule, a preset's own tags. */
    val filter: TagFilter,
    /**
     * The Atlas category: the group or family shelf for a shelf, the category itself, the one a preset lies in, or the
     * shelf of everything on the map for its keys and their presets.
     */
    val category: Category,
    val preset: Preset?,
) {
    /** The name in [lang], else in English: a preset's from id-tagging-schema, else the category's (a key's made readable). */
    fun name(lang: String? = "en"): String = preset?.name(lang)
        ?: if (kind == NodeKind.KEY) id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() } else category.name(lang)

    /**
     * Its name under [broader], which says the rest (the owner, 6 November: "Show "Italian" instead of "Italian
     * Restaurant"?"): a cuisine's own name under its kind that is no cuisine ("Italian", "Italiaans" under the
     * restaurants; not under Eat, where a pizza restaurant and a pizza takeaway would both be "Pizza"), else its name
     * with the broader's taken out ("Organic" under the supermarkets, "Second-Hand" under the clothes); its whole name
     * where it is not under [broader], or holds no word of its name.
     */
    fun nameUnder(broader: CategoryNode, lang: String? = "en"): String {
        val full = name(lang)
        if (id == broader.id || !CategoryTree.isUnder(this, broader)) return full
        val cuisine = preset?.tags?.get("cuisine")
        if (cuisine != null && broader.preset?.let { it.tags["cuisine"] == null } == true) return Cuisine.name(cuisine, lang)
        val outer = Regex("(^|[\\s-])" + Regex.escape(broader.name(lang)) + "(?=$|[\\s-])", RegexOption.IGNORE_CASE)
        val short = full.replace(outer, " ").trim(' ', '-')
        return if (short.isEmpty() || short.length >= full.length) full else short.replaceFirstChar { it.titlecase(Locale.forLanguageTag(lang ?: "en")) }
    }

    val parent: CategoryNode? get() = parentId?.let { CategoryTree.byId(it) }

    val children: List<CategoryNode> get() = CategoryTree.children(id)

    /** 0 for a shelf, 1 for a category or a key, 2 and more for presets. */
    val depth: Int get() = CategoryTree.pathOf(id).size - 1

    /**
     * The kinds people come to this node for most ([Category.core]): its category's, a shelf's categories' together;
     * none in a kind, whose places are all of it.
     */
    val core: Set<String> by lazy {
        when (kind) {
            NodeKind.SHELF -> children.flatMapTo(HashSet()) { it.core }
            NodeKind.CATEGORY -> category.core
            else -> emptySet()
        }
    }

    /**
     * A shelf's kinds whose home is another shelf, which it holds too ([Category.also]; the owner, 6 November: "Doesnt
     * sports somehow also belong in fun?"): Fun's climbing halls, ice rinks and water parks, Stay's campsites. Each keeps
     * its own family, colour and pin; the shelf lists it after its own kinds and counts its places.
     */
    val guests: List<CategoryNode> by lazy { also().filter { it.kind != NodeKind.SHELF } }

    /** The shelves a shelf's row of kinds leads into at its end, their places not its own: Sport from Fun. */
    val doors: List<CategoryNode> by lazy { also().filter { it.kind == NodeKind.SHELF } }

    private fun also(): List<CategoryNode> = if (kind != NodeKind.SHELF) emptyList() else category.also.mapNotNull { CategoryTree.byId(it) }

    /**
     * The kinds that belong with this one though each stands where it is ([Categories.links]; the owner, 6 November:
     * "Miniature golf is in fun. Where is golf? Link kinds that belong to each other."): a minigolf's golf courses and
     * disc golf, a pool's beaches. Its own first, then those of the kinds above it (a water park's are the pools'); never
     * a shelf's, nor a kind above or under it.
     */
    val links: List<CategoryNode> by lazy { CategoryTree.linksOf(this) }

    /**
     * Where [kind], one of this node's kinds, stands in its row, before how many of it are near: below 0 for its
     * category's [core] kinds, which lead it in their order (the supermarkets, then the convenience stores, among the
     * groceries); above 0 for the kinds it names last ([Category.last]), the specialists whose goods the others sell too
     * and what is no errand to go on (the frozen food shops among the groceries, the benches among the services); 0 for
     * the rest, which go by how many are near (the owner, 6 November, of theme parks and zoos leading Fun: "I think they
     * shouldnt"). A kind under a named one stands as that one does. Only a shelf's and a category's: a kind's own
     * narrower kinds all stand at 0.
     */
    fun standing(kind: CategoryNode): Int {
        val at = { ids: List<String> -> ids.indexOfFirst { id -> CategoryTree.byId(id)?.let { CategoryTree.isUnder(kind, it) } == true } }
        val first = at(leads)
        if (first >= 0) return first - leads.size
        return at(ends) + 1
    }

    private val leads: List<String> by lazy { if (kind == NodeKind.CATEGORY) category.core.map { "preset:$it" } else emptyList() }

    private val ends: List<String> get() = if (kind == NodeKind.SHELF || kind == NodeKind.CATEGORY) category.last else emptyList()

    /** Whether a place of [preset]'s kind is one of this node's [core] kinds, or of a narrower kind of one. */
    fun isCore(preset: Preset?): Boolean {
        if (core.isEmpty()) return false
        var p = preset
        while (p != null) {
            if (p.id in core) return true
            p = p.broader
        }
        return false
    }

    override fun toString() = id
}

/**
 * Every kind of thing Atlas can find, as one tree: the shelves of the grid (the Map home's eight groups, then culture,
 * sport, health, services and getting around), each Atlas category on its shelf in rule order, under each category
 * every searchable id-tagging-schema preset whose tags it takes, nested by preset id; then the shelf of everything else
 * on the map, every other searchable preset under its key. A preset with only a wildcard ("amenity=*") narrows nothing:
 * it names its key's node and holds no node of its own.
 */
object CategoryTree {
    private class Data(
        val all: List<CategoryNode>,
        val byId: Map<String, CategoryNode>,
        val children: Map<String, List<CategoryNode>>,
        /** Each kind of [Categories.links], to the others of its groups. */
        val links: Map<String, List<CategoryNode>>,
    )

    private val data: Data by lazy { build() }

    private const val SHELF = "shelf:"
    private const val CATEGORY = "cat:"
    private const val KEY = "key:"
    private const val PRESET = "preset:"

    /** The family of the shelf of everything else on the map. */
    const val OSM = "osm"

    /** The grid's rows, in order. */
    fun shelves(): List<CategoryNode> = children(ROOT)

    /** Every node, shelves first, each followed by its descendants. */
    fun all(): List<CategoryNode> = data.all

    fun byId(id: String): CategoryNode? = data.byId[id]

    /** The node of the Atlas category [id] ("restaurants"), or of a group's shelf for a group's id ("eat"). */
    fun category(id: String): CategoryNode? = data.byId[CATEGORY + id] ?: data.byId[SHELF + id]

    /** The shelf of a family ("culture") or group ("eat"). */
    fun shelf(family: String): CategoryNode? = data.byId[SHELF + family]

    /** The node of preset [id] ("amenity/restaurant/pizza"), when the tree holds it. */
    fun preset(id: String): CategoryNode? = data.byId[PRESET + id]

    /** The node of an OpenStreetMap key on the shelf of everything on the map ("natural"), when it has presets there. */
    fun key(key: String): CategoryNode? = data.byId[KEY + key]

    fun children(id: String): List<CategoryNode> = data.children[id] ?: emptyList()

    /** From the shelf down to the node [id]; empty when there is no such node. */
    fun pathOf(id: String): List<CategoryNode> {
        val out = ArrayList<CategoryNode>()
        var n = data.byId[id]
        while (n != null) {
            out += n
            n = n.parentId?.let { data.byId[it] }
        }
        return out.asReversed()
    }

    /**
     * The deepest node that holds a thing with [tags] drawn as [geometry]: its preset's node, or the nearest ancestor
     * preset the tree holds, else its category's; for what no category takes, its preset's node on the shelf of
     * everything on the map, else its key's. Null when no preset knows it.
     */
    fun of(tags: Map<String, String>, geometry: Geometry = Geometry.POINT): CategoryNode? {
        val category = Categories.of(tags)
        val holder = category ?: Categories.shelf(OSM)
        var p = Presets.match(tags, geometry)
        val first = p
        while (p != null) {
            data.byId[PRESET + p.id]?.takeIf { it.category === holder }?.let { return it }
            p = p.broader
        }
        if (category != null) return data.byId[CATEGORY + category.id]
        return first?.let { data.byId[KEY + keyOf(it)] }
    }

    /**
     * True when [node] is reached through a door of the shelf [from]: one of its guests ([CategoryNode.guests]) or a
     * shelf it leads into ([CategoryNode.doors]), or a kind under one of those.
     */
    fun leadsTo(from: CategoryNode, node: CategoryNode): Boolean = (from.guests + from.doors).any { isUnder(node, it) }

    /** The kinds that belong with [node] ([CategoryNode.links]). */
    internal fun linksOf(node: CategoryNode): List<CategoryNode> {
        val out = LinkedHashMap<String, CategoryNode>()
        for (n in pathOf(node.id).asReversed()) {
            if (n.kind == NodeKind.SHELF) continue
            for (m in data.links[n.id].orEmpty()) if (!isUnder(m, node) && !isUnder(node, m)) out.putIfAbsent(m.id, m)
        }
        return out.values.toList()
    }

    /** True when [node] is [ancestor] or lies under it. */
    fun isUnder(node: CategoryNode, ancestor: CategoryNode): Boolean {
        var n: CategoryNode? = node
        while (n != null) {
            if (n.id == ancestor.id) return true
            n = n.parent
        }
        return false
    }

    /** The parent of the shelves. */
    const val ROOT = ""

    private fun build(): Data {
        val all = ArrayList<CategoryNode>()
        val byId = HashMap<String, CategoryNode>()
        val children = HashMap<String, MutableList<CategoryNode>>()
        fun add(n: CategoryNode) {
            check(byId.put(n.id, n) == null) { "two nodes ${n.id}" }
            children.getOrPut(n.parentId ?: ROOT) { ArrayList() }.add(n)
        }

        // The presets each category takes, by the same rule a named place is put in it; the rest by key, for the last shelf.
        val presetsOf = HashMap<String, MutableList<Preset>>()
        val byKey = LinkedHashMap<String, MutableList<Preset>>()
        for (p in Presets.all) {
            if (!p.searchable || p.tags.isEmpty() || p.tags.values.any { it == "*" }) continue
            val c = Categories.of(Kinds.named(p.tags + p.addTags.filterValues { it != "*" }))
            if (c != null && placeLike(p)) presetsOf.getOrPut(c.id) { ArrayList() }.add(p) else byKey.getOrPut(keyOf(p)) { ArrayList() }.add(p)
        }

        for (shelf in Categories.grid()) {
            val leaves = Categories.onShelf(shelf)
            if (leaves.isEmpty()) continue
            val shelfId = SHELF + shelf.family
            // a shelf takes the places of the kinds it holds too (a climbing hall in Fun), its leaves' first
            val guests = shelf.also.mapNotNull { id ->
                when {
                    id.startsWith(CATEGORY) -> Categories.byId(id.removePrefix(CATEGORY))?.rule
                    id.startsWith(PRESET) -> Presets.byId(id.removePrefix(PRESET))?.let(::filterOf)
                    else -> null
                }
            }
            add(CategoryNode(shelfId, NodeKind.SHELF, null, shelf.symbol, shelf.colour, TagFilter.Or(leaves.mapNotNull { it.rule } + guests), shelf, null))
            for (leaf in leaves) {
                val rule = leaf.rule ?: continue
                val leafId = CATEGORY + leaf.id
                add(CategoryNode(leafId, NodeKind.CATEGORY, shelfId, leaf.symbol, leaf.colour, rule, leaf, null))
                val presets = presetsOf[leaf.id].orEmpty()
                addPresets(presets, leafId, leaf, leaf.symbol, ::add)
            }
        }
        Categories.shelf(OSM)?.let { osm ->
            val shelfId = SHELF + OSM
            val keys = byKey.entries.sortedBy { it.key }
            add(CategoryNode(shelfId, NodeKind.SHELF, null, osm.symbol, osm.colour, TagFilter.Or(keys.map { TagFilter.Has(firstKey(it.value)) }.distinct()), osm, null))
            for ((key, presets) in keys) {
                // the key's own wildcard preset names it ("Natural Feature") and gives its symbol; it holds every place
                // with its key that no category takes, as [of] puts them under it (an old house of no name, "Building")
                val own = Presets.byId(key)
                val symbol = own?.let { Kinds.symbolOf(it) } ?: osm.symbol
                val keyId = KEY + key
                val holds = TagFilter.Or(presets.map(::filterOf) + TagFilter.Has(firstKey(presets)))
                add(CategoryNode(keyId, NodeKind.KEY, shelfId, symbol, osm.colour, holds, osm, own))
                addPresets(presets, keyId, osm, symbol, ::add)
            }
        }
        // depth first, in each parent's order
        fun walk(id: String) {
            for (n in children[id].orEmpty()) {
                all += n
                walk(n.id)
            }
        }
        walk(ROOT)
        // each kind of a group that belongs together, to the others of it the tree holds
        val links = HashMap<String, MutableList<CategoryNode>>()
        for (group in Categories.links()) {
            val nodes = group.mapNotNull { byId[it] }
            for (n in nodes) links.getOrPut(n.id) { ArrayList() } += nodes.filter { it !== n }
        }
        return Data(all, byId, children, links)
    }

    /** [presets] under [top], each under its nearest ancestor among them: parents first, by depth, then in their own order. */
    private fun addPresets(presets: List<Preset>, top: String, category: Category, symbol: String, add: (CategoryNode) -> Unit) {
        val ids = presets.mapTo(HashSet()) { it.id }
        for (p in presets.sortedBy { it.id.count { ch -> ch == '/' } }) {
            var parent = p.broader
            while (parent != null && parent.id !in ids) parent = parent.broader
            val own = Kinds.symbolOf(p) ?: symbol
            val colour = KindColours.of(p) ?: category.colour
            add(CategoryNode(PRESET + p.id, NodeKind.PRESET, parent?.let { PRESET + it.id } ?: top, own, colour, filterOf(p), category, p))
        }
    }

    /** A preset a category holds: one drawn as a point or an area (a category's places are), not only as a line. */
    private fun placeLike(p: Preset): Boolean = p.geometry.any { it == Geometry.POINT || it == Geometry.AREA || it == Geometry.VERTEX }

    /** The key a preset of the last shelf stands under: its id's first part ("natural/water/lake" -> "natural"). */
    private fun keyOf(p: Preset): String = p.id.substringBefore('/')

    /** The tag key of a key node's presets: the first key of the first ("area/highway" -> "area:highway"). */
    private fun firstKey(presets: List<Preset>): String = presets.first().tags.keys.first()

    /** The tags that make a place this preset, as a filter. */
    private fun filterOf(p: Preset): TagFilter {
        val parts = p.tags.map { (k, v) -> TagFilter.Equals(k, v) }
        return if (parts.size == 1) parts[0] else TagFilter.And(parts)
    }
}
