package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Shapes
import app.atlas.model.OsmType
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.CompareOp
import app.atlas.places.TagFilter
import app.atlas.places.Units
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns a [Query] into Overpass QL for the online, full-planet version of a search: the `{} QL` behind the query
 * builder, and what [OnlineSearch] sends when the downloaded regions are not enough.
 *
 * Conjunctions of tag tests become one statement each (`nwr["amenity"="cafe"]["outdoor_seating"="yes"](bbox)`),
 * disjunctions become unions, "A within d of B" becomes B's set and `(around.near1:d)` on A, "not near" and other
 * negations become set differences. What QL cannot say exactly (opening hours, numbers with units, soft preferences)
 * compiles to a superset that the client checks again: [OnlineSearch] runs the same query over the answer.
 */
object OverpassCompiler {
    /** Keys whose values are often `;` lists, matched by their parts as the offline filters do. */
    private val LIST_KEYS = setOf("cuisine", "sport", "vending", "healthcare:speciality", "diet", "religion", "craft", "service:vehicle")

    private val NAME_KEYS = "^(name|name:[a-z_-]+|alt_name|old_name|official_name|short_name|loc_name|brand)$"

    /**
     * QL for [query] in [area] (a box, a circle or a corridor; anywhere when null). [me] is the searcher, for
     * "around me" (the area's centre when unknown). The answer holds the results and, so they can be checked and
     * drawn, the places of every "near" clause.
     */
    fun compile(query: Query, area: OverpassArea? = null, me: LatLon? = null, timeoutSeconds: Int = 60): String {
        var q = query
        var limit: Int? = null
        while (true) {
            q = when (q) {
                is Query.Sort -> q.query
                is Query.Limit -> { limit = q.count; q.query }
                else -> break
            }
        }
        val c = Compilation(me ?: centreOf(area))
        val result = c.set(q, area, "result")
        val sb = StringBuilder()
        sb.append("[out:json][timeout:").append(timeoutSeconds).append("];\n")
        sb.append(c.body)
        // a client-side check after the fact (opening hours, units) needs every candidate, so no limit then
        val countClause = if (limit != null && !needsClientCheck(q)) " $limit" else ""
        // `bb`: the outline's box (its centre is the pin, as with `center`), so "within 200 m of a park" is measured
        // to the park's edge, as offline
        sb.append('.').append(result).append(" out tags bb qt").append(countClause).append(";\n")
        for (n in c.nearSets) sb.append('.').append(n).append(" out tags bb qt;\n")
        return sb.toString().trimEnd()
    }

    fun compile(query: Query, box: BBox, me: LatLon? = null): String = compile(query, OverpassArea.Box(box), me)

    private fun centreOf(area: OverpassArea?): LatLon? = when (area) {
        is OverpassArea.Box -> area.box.center
        is OverpassArea.Around -> area.center
        is OverpassArea.Along -> area.points[area.points.size / 2]
        null -> null
    }

    private fun needsClientCheck(q: Query): Boolean {
        var needs = false
        q.walk {
            if (it is Query.OpenAt || (it is Query.Tags && hasCompare(it.filter)) || it is Query.Name) needs = true
        }
        return needs
    }

    private fun hasCompare(f: TagFilter): Boolean = when (f) {
        is TagFilter.Compare -> true
        is TagFilter.And -> f.parts.any { hasCompare(it) }
        is TagFilter.Or -> f.parts.any { hasCompare(it) }
        is TagFilter.Not -> hasCompare(f.part)
        else -> false
    }

    /**
     * One alternative of a disjunction: an element type and the filters that all must hold. [pinned] are the tags its
     * equality filters fix (to judge "open" by), and [open] asks for places open at some time.
     */
    private data class Alt(
        val type: String,
        val filters: List<String>,
        val spatial: Boolean,
        val pinned: Map<String, String> = emptyMap(),
        val open: Boolean = false,
    ) {
        fun and(o: Alt): Alt? {
            val t = when {
                type == "nwr" -> o.type
                o.type == "nwr" || o.type == type -> type
                else -> return null
            }
            return Alt(t, filters + o.filters, spatial || o.spatial, o.pinned + pinned, open || o.open)
        }

        /**
         * The filters to send. Opening hours are checked by the client; the server is only asked for them when the
         * kind of place is pinned and is not one that is open whenever its hours are not mapped (toilets, docks, parks).
         */
        fun selectors(): String {
            val all = if (open && needsHours(pinned)) filters + "[${OverpassQl.quote("opening_hours")}]" else filters
            return all.joinToString("")
        }

        companion object {
            val ANY = Alt("nwr", emptyList(), false)

            /** Keys that say what kind of place it is ("sport" does not: a pitch is a leisure, open whenever unmapped). */
            private val KIND_KEYS = setOf("amenity", "shop", "craft", "office", "healthcare", "tourism", "leisure", "club")

            private fun needsHours(pinned: Map<String, String>): Boolean =
                pinned.keys.any { it in KIND_KEYS } && !OfflineEvaluator.usuallyOpen(pinned)
        }
    }

    private class Compilation(val me: LatLon?) {
        val body = StringBuilder()
        val nearSets = ArrayList<String>()
        private var sets = 0
        private var nears = 0
        private var areas = 0
        private val namedAreas = HashMap<String, String>()

        /** Emits statements that leave [q]'s elements in set [name] (a fresh one when null) and returns its name. */
        fun set(q: Query, area: OverpassArea?, name: String? = null): String {
            val target = name ?: "s${++sets}"
            val alts = alts(q, area)
            if (alts != null) {
                emit(alts, area, target)
                return target
            }
            when (q) {
                is Query.And -> {
                    // the parts QL can filter by stay filters; the others become sets to intersect or subtract
                    val filters = ArrayList<Query>()
                    val complex = ArrayList<Query>()
                    val negated = ArrayList<Query>()
                    for (p in q.parts) {
                        val a = alts(p, area)
                        when {
                            p is Query.Not && a == null -> negated += p.part
                            a != null -> filters += p
                            else -> complex += p
                        }
                    }
                    var simple: List<Query> = filters
                    var filterAlts = alts(Query.and(simple), area)
                    if (filterAlts == null) {
                        // together they spell too many alternatives: those with several become sets of their own
                        val (several, one) = simple.partition { (alts(it, area)?.size ?: 0) > 1 }
                        complex += several
                        simple = one
                        filterAlts = alts(Query.and(simple), area) ?: error("single alternatives multiply to one")
                    }
                    val base = if (complex.isEmpty()) {
                        set(Query.and(simple), area, if (negated.isEmpty()) target else null)
                    } else {
                        val parts = complex.map { set(it, area) }
                        val s = if (negated.isEmpty()) target else "s${++sets}"
                        val stmts = filterAlts.map { a -> "${a.type}${parts.joinToString("") { ".$it" }}${a.selectors()}" }
                        statements(stmts, s)
                        s
                    }
                    if (negated.isEmpty()) return rename(base, target)
                    val minus = negated.map { set(Query.and(it), area) }
                    body.append("(.").append(base).append(";")
                    for (m in minus) body.append(" - .").append(m).append(";")
                    body.append(")->.").append(target).append(";\n")
                    return target
                }
                is Query.Or -> {
                    val parts = q.parts.map { set(it, area) }
                    body.append("(").append(parts.joinToString(" ") { ".$it;" }).append(")->.").append(target).append(";\n")
                    return target
                }
                is Query.Not -> {
                    // everything in the area but the part
                    val all = set(Query.All, area)
                    val part = set(q.part, area)
                    body.append("(.").append(all).append("; - .").append(part).append(";)->.").append(target).append(";\n")
                    return target
                }
                is Query.Near -> {
                    val subject = set(q.subject, area)
                    val near = nearSet(q.other, area, q.maxDistance)
                    body.append("nwr.").append(subject).append("(around.").append(near).append(":").append(num(q.maxDistance)).append(")->.").append(target).append(";\n")
                    return target
                }
                is Query.NotNear -> {
                    val subject = set(q.subject, area)
                    val near = nearSet(q.other, area, q.minDistance)
                    val close = "s${++sets}"
                    body.append("nwr.").append(subject).append("(around.").append(near).append(":").append(num(q.minDistance)).append(")->.").append(close).append(";\n")
                    body.append("(.").append(subject).append("; - .").append(close).append(";)->.").append(target).append(";\n")
                    return target
                }
                is Query.Sort -> return set(q.query, area, target)
                is Query.Limit -> return set(q.query, area, target)
                is Query.Tags -> return set(lift(q.filter), area, target)
                is Query.Category -> return set(categoryFilter(q.id)?.let { lift(it) } ?: Query.Or(emptyList()), area, target)
                else -> error("cannot compile $q")
            }
        }

        private fun rename(from: String, to: String): String {
            if (from == to) return to
            body.append(".").append(from).append("->.").append(to).append(";\n")
            return to
        }

        private fun emit(alts: List<Alt>, area: OverpassArea?, target: String) {
            val stmts = alts.map { a ->
                val spatial = if (a.spatial || area == null) "" else area.clause()
                val selectors = a.selectors() + spatial
                // Overpass refuses a statement with no condition at all ("everything on the planet")
                require(selectors.isNotEmpty()) { "A query that does not narrow by any tag needs an area" }
                a.type + selectors
            }
            statements(stmts, target)
        }

        private fun statements(stmts: List<String>, target: String) {
            if (stmts.isEmpty()) {
                // nothing can match: an empty set
                body.append("node(id:0)->.").append(target).append(";\n")
            } else if (stmts.size == 1) {
                body.append(stmts[0]).append("->.").append(target).append(";\n")
            } else {
                body.append("(\n")
                for (s in stmts) body.append("  ").append(s).append(";\n")
                body.append(")->.").append(target).append(";\n")
            }
        }

        private val nearNames = HashMap<Pair<Query, OverpassArea?>, String>()

        /**
         * The places of a "near" clause, in the area grown by its distance; kept for the output. Asked again for the
         * same places (the parts of a conjunction are tried more than once), it is the same set.
         */
        private fun nearSet(q: Query, area: OverpassArea?, metres: Double): String {
            val grown = grow(area, metres)
            return nearNames.getOrPut(q to grown) {
                val name = "near${++nears}"
                set(q, grown, name)
                nearSets += name
                name
            }
        }

        private fun grow(area: OverpassArea?, metres: Double): OverpassArea? = when (area) {
            is OverpassArea.Box -> OverpassArea.Box(area.box.buffer(metres))
            is OverpassArea.Around -> OverpassArea.Around(area.center, area.metres + metres)
            is OverpassArea.Along -> OverpassArea.Along(area.points, area.metres + metres)
            null -> null
        }

        /** [q] as alternatives of filter chains, or null when it needs set algebra. */
        fun alts(q: Query, area: OverpassArea?): List<Alt>? = when (q) {
            is Query.Tags -> filterAlts(q.filter)
            // an unknown category finds nothing; a rule filters cannot say needs set algebra (null)
            is Query.Category -> categoryFilter(q.id).let { f -> if (f == null) emptyList() else filterAlts(f) }
            is Query.Brand -> {
                val b = Brands.byId(q.id)
                if (b == null) emptyList() else listOfNotNull(
                    b.wikidata?.let { Alt("nwr", listOf("[${OverpassQl.quote("brand:wikidata")}=${OverpassQl.quote(it)}]"), false) },
                    Alt("nwr", listOf("[${OverpassQl.quote("brand")}=${OverpassQl.quote(b.brand)}]"), false),
                )
            }
            is Query.Name -> listOf(Alt("nwr", listOf("[~${OverpassQl.quote(NAME_KEYS)}~${OverpassQl.quote(nameRegex(q.text))},i]"), false))
            // checked by the client; see Alt.selectors for when the server is asked for opening hours
            is Query.OpenAt -> listOf(Alt("nwr", emptyList(), false, open = true))
            is Query.Type -> listOf(Alt(typeName(q.type), emptyList(), false))
            is Query.Id -> listOf(Alt(typeName(q.id.type), listOf("(id:${q.id.id})"), false))
            is Query.Newer -> listOf(Alt("nwr", listOf("(newer:${OverpassQl.quote(q.since.truncatedTo(ChronoUnit.SECONDS).toString())})"), false))
            is Query.Within -> {
                val c = q.center ?: me
                if (c == null) listOf(Alt.ANY) else listOf(Alt("nwr", listOf(OverpassArea.Around(c, q.radius).clause()), true))
            }
            is Query.InArea -> {
                val b = q.bbox
                if (b != null) listOf(Alt("nwr", listOf(OverpassArea.Box(b).clause()), true))
                else listOf(Alt("nwr", listOf("(area.${namedArea(q.name!!)})"), true))
            }
            is Query.AlongRoute -> {
                val line = if (q.line.size > 2) Shapes.simplify(q.line, q.maxDetourM / 4) else q.line
                listOf(Alt("nwr", listOf(OverpassArea.Along(line, q.maxDetourM).clause()), true))
            }
            is Query.Near -> {
                // the subject first, so a chain's near sets come out in the order they were written
                val subject = alts(q.subject, area)
                if (subject == null) null else {
                    val near = nearSet(q.other, area, q.maxDistance)
                    subject.map { it.copy(filters = it.filters + "(around.$near:${num(q.maxDistance)})") }
                }
            }
            is Query.Prefer, Query.All -> listOf(Alt.ANY)
            is Query.And -> {
                var acc: List<Alt>? = listOf(Alt.ANY)
                for (p in q.parts) {
                    val a = alts(p, area) ?: return null
                    acc = acc!!.flatMap { x -> a.mapNotNull { y -> x.and(y) } }
                    if (acc.size > MAX_ALTERNATIVES) return null
                }
                acc
            }
            is Query.Or -> q.parts.flatMap { alts(it, area) ?: return null }.takeIf { it.size <= MAX_ALTERNATIVES }
            is Query.Not -> when (val p = q.part) {
                is Query.Tags -> filterAlts(TagFilter.Not(p.filter))
                is Query.Category -> categoryFilter(p.id)?.let { filterAlts(TagFilter.Not(it)) }
                is Query.OpenAt, is Query.Prefer -> listOf(Alt.ANY)
                else -> null
            }
            is Query.NotNear -> null
            is Query.Sort -> alts(q.query, area)
            is Query.Limit -> alts(q.query, area)
        }

        private fun namedArea(name: String): String = namedAreas.getOrPut(name) {
            val set = "area${++areas}"
            body.append("area[${OverpassQl.quote("name")}=${OverpassQl.quote(name)}][${OverpassQl.quote("boundary")}=${OverpassQl.quote("administrative")}]->.").append(set).append(";\n")
            set
        }

        private fun filterAlts(f: TagFilter): List<Alt>? = when (f) {
            is TagFilter.Has -> one("[${OverpassQl.quote(f.key)}]")
            is TagFilter.Equals -> listOf(Alt("nwr", listOf(equals(f.key, f.value)), false, mapOf(f.key to f.value)))
            is TagFilter.NotEquals -> one("[${OverpassQl.quote(f.key)}!=${OverpassQl.quote(f.value)}]")
            is TagFilter.Matches -> one(matches(f.key, f.pattern, f.negate))
            is TagFilter.KeyMatches -> one("[~${OverpassQl.quote(f.keyPattern)}~${OverpassQl.quote(f.valuePattern ?: ".")},i]")
            is TagFilter.Compare -> {
                val base = Units.toBase(f.value, f.unit).value
                val t = "t[${OverpassQl.quote(f.key)}]"
                listOf(Alt("nwr", listOf("[${OverpassQl.quote(f.key)}]", "(if: !is_number($t) || number($t) ${op(f.op)} ${num(base)})"), false))
            }
            is TagFilter.And -> {
                var acc: List<Alt>? = listOf(Alt.ANY)
                for (p in f.parts) {
                    val a = filterAlts(p) ?: return null
                    acc = acc!!.flatMap { x -> a.mapNotNull { y -> x.and(y) } }
                    if (acc.size > MAX_ALTERNATIVES) return null
                }
                acc
            }
            is TagFilter.Or -> f.parts.flatMap { filterAlts(it) ?: return null }.takeIf { it.size <= MAX_ALTERNATIVES }
            is TagFilter.Not -> when (val p = f.part) {
                is TagFilter.Has -> one("[!${OverpassQl.quote(p.key)}]")
                is TagFilter.Equals -> one("[${OverpassQl.quote(p.key)}!=${OverpassQl.quote(p.value)}]")
                is TagFilter.NotEquals -> one(equals(p.key, p.value))
                is TagFilter.Matches -> one(matches(p.key, p.pattern, !p.negate))
                is TagFilter.Not -> filterAlts(p.part)
                is TagFilter.Or -> filterAlts(TagFilter.And(p.parts.map { TagFilter.Not(it) }))
                else -> null
            }
            TagFilter.All -> listOf(Alt.ANY)
        }

        private fun one(filter: String) = listOf(Alt("nwr", listOf(filter), false))

        private fun equals(key: String, value: String): String =
            if (key in LIST_KEYS) "[${OverpassQl.quote(key)}~${OverpassQl.quote(listPattern(regexLiteral(value)))}]"
            else "[${OverpassQl.quote(key)}=${OverpassQl.quote(value)}]"

        /**
         * A regex test; on a list key an anchored list of words (`^(pizza|pasta)$`) must match one part, as offline:
         * "italian;pizza" has pizza.
         */
        private fun matches(key: String, pattern: String, negate: Boolean): String {
            val words = if (key in LIST_KEYS) OfflineEvaluator.literals(pattern) else null
            val p = words?.let { w -> listPattern(w.joinToString("|", "(", ")") { regexLiteral(it) }) } ?: pattern
            return "[${OverpassQl.quote(key)}${if (negate) "!~" else "~"}${OverpassQl.quote(p)},i]"
        }

        private fun listPattern(alternatives: String) = "(^|;)\\s*$alternatives\\s*(;|$)"
    }

    private const val MAX_ALTERNATIVES = 32

    /** A tag filter's own and/or/not as query nodes, so set algebra can take over where filter chains cannot. */
    private fun lift(f: TagFilter): Query = when (f) {
        is TagFilter.And -> Query.And(f.parts.map { lift(it) })
        is TagFilter.Or -> Query.Or(f.parts.map { lift(it) })
        is TagFilter.Not -> Query.Not(lift(f.part))
        else -> Query.Tags(f)
    }

    private fun categoryFilter(id: String): TagFilter? {
        val c = Categories.byId(id) ?: return null
        if (!c.isGroup) return c.rule
        val rules = Categories.children(id).mapNotNull { it.rule }
        return if (rules.isEmpty()) null else TagFilter.or(*rules.toTypedArray())
    }

    private fun typeName(t: OsmType) = when (t) {
        OsmType.NODE -> "node"
        OsmType.WAY -> "way"
        OsmType.RELATION -> "relation"
    }

    private fun op(o: CompareOp) = o.symbol

    /** A name as a case-insensitive regex: its words in order, anything between them. */
    private fun nameRegex(text: String): String = text.trim().split(Regex("\\s+")).joinToString(".*") { regexLiteral(it) }

    private fun regexLiteral(s: String): String = s.replace(Regex("""[.*+?^${'$'}()\[\]{}|\\]""")) { "\\" + it.value }

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')
}
