package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.geo.PackedRTree
import app.atlas.geo.Polyline
import app.atlas.geo.PreparedPolygon
import app.atlas.geo.Shapes
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Facts
import app.atlas.places.ChangeKind
import app.atlas.places.OpenState
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.Presets
import app.atlas.places.TagFilter
import app.atlas.places.Years
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Runs a [Query] over a [PlaceSource] (one store, every attached region, or places in memory).
 *
 * The plan: find the narrowest part of the query the indexes can answer (an id, a name, a rare tag, a brand, a box),
 * take its places as candidates, then check every candidate against the whole query. "A within d of B" is a spatial
 * join: B's places in the area grown by d go into an R-tree, and each A keeps its nearest B and the distance.
 * Results are ranked by relevance × distance × open now × rating, unless the query asks for another order.
 */
class OfflineEvaluator(val source: PlaceSource) {
    /** Runs [query] for [ctx]. */
    fun run(query: Query, ctx: EvalContext): QueryResult {
        val started = System.nanoTime()
        var q = query
        var sort = SortBy.RELEVANCE
        var limit = ctx.limit
        while (true) {
            q = when (q) {
                is Query.Sort -> { sort = q.by; q.query }
                is Query.Limit -> { limit = min(limit, q.count); q.query }
                else -> break
            }
        }
        limit = limit.coerceAtLeast(0)
        val run = Run(ctx)
        val area = run.areaOf(q)
        val largest = if (area == null && sort == SortBy.LARGEST) largestOf(q, ctx, limit) else null
        val candidates = when {
            area != null -> run.candidates(q, area)
            largest != null -> largest
            // A seed of a few thousand places is cheap anywhere; a bigger one (every café of a country) is looked
            // for around the searcher first, then further, like a query nothing narrows down.
            q.hasSeed() && (run.cost(q, null) <= WIDE_SEED || (ctx.location == null && ctx.viewport == null)) -> run.candidates(q, null)
            else -> run.expanding(q)
        }
        run.scope = extentOf(candidates)
        run.branches = candidates
        val hits = ArrayList<Hit>()
        for (c in candidates) run.hit(q, c)?.let { hits += it }
        run.likely(hits)
        sortHits(hits, sort, run)
        val total = hits.size
        val out = if (hits.size > limit) hits.subList(0, limit).toList() else hits
        return QueryResult(out, total, candidates.size, (System.nanoTime() - started) / 1000, hits.count { it.sure == Sure.YES })
    }

    /**
     * The largest of the kind [q] asks for across the region that holds the searcher, read by their size ([Largest])
     * where no area is asked: "largest parks" are the region's largest, not the largest of the nearest few (the user,
     * 9 November: "Is it somewhere possible to see the largest green areas in your country or around you?"). Null
     * where the query names no kind, or no store holds the searcher: then it is read as any query is.
     */
    private fun largestOf(q: Query, ctx: EvalContext, limit: Int): List<PlaceRecord>? {
        val kind = kindOf(q) ?: return null
        val at = ctx.location ?: ctx.viewport?.center
        val stores = (source as? Regions)?.stores ?: listOf(source)
        val holding = stores.filter { at == null || it.covers(at) }.ifEmpty { return null }
        return Largest.of(holding, kind, limit.coerceIn(1, LARGEST_MAX) * LARGEST_SPARE)
    }

    /** The kind a query asks for: its tags, or those of the first of its parts that has them ("parks with a dog area"). */
    private fun kindOf(q: Query): TagFilter? = when (q) {
        is Query.Tags -> q.filter
        is Query.And -> q.parts.firstNotNullOfOrNull { kindOf(it) }
        else -> null
    }

    /**
     * Runs the steps of a plan ("a café, then dinner") in order: each step after the first is searched around the
     * best result of the one before.
     */
    fun plan(steps: List<Query>, ctx: EvalContext): List<QueryResult> {
        val out = ArrayList<QueryResult>()
        var c = ctx
        for (s in steps) {
            val r = run(s, c)
            out += r
            r.hits.firstOrNull()?.let { c = c.copy(location = it.record.location, viewport = null) }
        }
        return out
    }

    /**
     * When nothing is just as asked: the places [base] finds (what they are: a kind, a name, an area), each with what
     * it misses of [requirements] and how far off ([Hit.misses]), closest first ([Closest.rank]), by score between
     * equals. Empty when [base] finds nothing either.
     */
    fun closest(base: Query, requirements: List<Query>, ctx: EvalContext): QueryResult {
        val started = System.nanoTime()
        val found = run(base, ctx.copy(limit = max(ctx.limit, CLOSEST_POOL)))
        if (found.hits.isEmpty() || requirements.isEmpty()) return found
        val run = Run(ctx)
        val records = found.hits.map { it.record }
        run.branches = records
        run.scope = extentOf(records)
        val ranked = Closest.rank(found.hits.map { h -> h.copy(misses = requirements.mapNotNull { run.miss(it, h.record) }) }) { it.misses }
        val out = if (ranked.size > ctx.limit) ranked.subList(0, ctx.limit).toList() else ranked
        return QueryResult(out, ranked.size, found.examined, (System.nanoTime() - started) / 1000, out.count { it.sure == Sure.YES })
    }

    /** [hits] each with what it misses of [requirements] and how far off ([Hit.misses]), read for [ctx]; in their order. */
    fun withMisses(hits: List<Hit>, requirements: List<Query>, ctx: EvalContext): List<Hit> {
        if (hits.isEmpty() || requirements.isEmpty()) return hits
        val run = Run(ctx)
        val records = hits.map { it.record }
        run.branches = records
        run.scope = extentOf(records)
        return hits.map { h -> h.copy(misses = h.misses + requirements.mapNotNull { run.miss(it, h.record) }) }
    }

    /** The places [query] finds, unranked: for the far side of a "near", and for counts. */
    fun matches(query: Query, ctx: EvalContext, area: BBox? = null): List<PlaceRecord> {
        val run = Run(ctx)
        val a = area ?: run.areaOf(query)
        val cands = if (a != null || query.hasSeed()) run.candidates(query, a) else run.expanding(query)
        run.scope = extentOf(cands)
        run.branches = cands
        return cands.filter { run.matches(query, it, null) }
    }

    private fun sortHits(hits: MutableList<Hit>, by: SortBy, run: Run? = null) {
        // the orders by a value: those with it first, by it; those without after, by score
        fun byValue(descending: Boolean, value: (Hit) -> Double?) {
            val values = IdentityHashMap<Hit, Double?>().apply { for (h in hits) put(h, value(h)) }
            val known = compareBy<Hit> { if (values[it] == null) 1 else 0 }
            hits.sortWith(
                known.then(if (descending) compareByDescending { values[it] ?: 0.0 } else compareBy { values[it] ?: 0.0 })
                    .thenByDescending { it.score },
            )
        }
        when (by) {
            SortBy.RELEVANCE -> hits.sortWith(compareByDescending<Hit> { it.score }.thenBy { it.distance ?: Double.MAX_VALUE })
            SortBy.DISTANCE -> hits.sortWith(compareBy<Hit> { it.distance ?: Double.MAX_VALUE }.thenByDescending { it.score })
            SortBy.RATING -> hits.sortWith(compareByDescending<Hit> { it.rating ?: -1.0 }.thenByDescending { it.score })
            SortBy.OPEN_LONGEST -> hits.sortWith(
                compareBy<Hit> { openRank(it.open) }
                    .thenByDescending { h -> h.open?.takeIf { it.isOpen }?.changesAt ?: LocalDateTime.MAX }
                    .thenByDescending { it.score },
            )
            SortBy.OLDEST -> byValue(false) { Years.year(it.tags["start_date"] ?: it.tags["building:start_date"] ?: "") }
            SortBy.NEWEST -> byValue(true) { Years.year(it.tags["start_date"] ?: it.tags["building:start_date"] ?: "") }
            SortBy.TALLEST -> byValue(true) { heightOf(it.tags) }
            SortBy.HIGHEST -> byValue(true) { metres(it.tags["ele"]) ?: heightOf(it.tags) }
            SortBy.CHEAPEST -> byValue(false) { Tells.price(it.tags) }
            SortBy.LARGEST -> byValue(true) { h -> footprint(h) }
            // a point has no size to go by
            SortBy.SMALLEST -> byValue(false) { h -> footprint(h)?.takeIf { it > 0 } }
            SortBy.LONGEST -> byValue(true) { h -> metres(h.tags["length"]) ?: h.place.bbox?.let { b -> Geo.distance(LatLon(b.south, b.west), LatLon(b.north, b.east)) }?.takeIf { it > 0 } }
            SortBy.POWER -> byValue(true) { Power.maxKw(it.tags) }
            SortBy.STARS -> byValue(true) { stars(it.tags["stars"]) }
            SortBy.MOST_HOURS -> byValue(true) { h -> run?.hoursToday(h.record)?.toDouble() }
            SortBy.OPENS_EARLIEST -> byValue(false) { h -> run?.opensToday(h.record)?.toDouble() }
            SortBy.OPENS_LATEST -> byValue(true) { h -> run?.opensToday(h.record)?.toDouble() }
            SortBy.CLOSES_LATEST -> byValue(true) { h -> run?.closesToday(h.record)?.toDouble() }
            // open ones first, then the closed by when they open
            SortBy.OPENS_NEXT -> byValue(false) { h ->
                h.open?.takeIf { !it.unknown }?.let { s -> if (s.isOpen) 0.0 else s.changesAt?.toEpochSecond(java.time.ZoneOffset.UTC)?.toDouble() }
            }
        }
    }

    /** How much ground a place covers, by its box (in square degrees, shrunk by the latitude): to compare, not to say. */
    private fun footprint(h: Hit): Double? = h.place.bbox?.let { b -> (b.east - b.west) * (b.north - b.south) * kotlin.math.cos(Math.toRadians(b.center.lat)) }

    /** A hotel's stars ("4", "3S": superior is half a star more). */
    private fun stars(v: String?): Double? {
        val n = v?.let { Regex("""^\s*(\d+(?:[.,]\d)?)""").find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() } ?: return null
        return if (v.trim().endsWith("S", ignoreCase = true)) n + 0.5 else n
    }

    /** What a comparison of [key] reads of a place: its height, its elevation, the year it was built. */
    private fun measure(key: String, t: Map<String, String>): Double? = when (key) {
        "height" -> heightOf(t)
        "start_date" -> Years.year(t["start_date"] ?: t["building:start_date"] ?: "")
        else -> metres(t[key])
    }

    /** How tall a place stands, in metres: its height, else three metres a floor. */
    private fun heightOf(t: Map<String, String>): Double? =
        metres(t["height"]) ?: metres(t["building:height"]) ?: t["building:levels"]?.toDoubleOrNull()?.let { it * 3 }

    private fun metres(v: String?): Double? = v?.let { app.atlas.places.Units.parse(it) }?.takeIf {
        it.dimension == app.atlas.places.Dimension.LENGTH || it.dimension == app.atlas.places.Dimension.NONE
    }?.value

    private fun openRank(s: OpenStatus?): Int = when {
        s == null || s.unknown -> 1
        s.isOpen -> 0
        else -> 2
    }

    /** True when some part of [this] can be looked up through an index. */
    private fun Query.hasSeed(): Boolean = when (this) {
        is Query.Tags -> filterSeedable(filter)
        is Query.Name, is Query.Category, is Query.Brand, is Query.Id -> true
        is Query.Within, is Query.InArea, is Query.AlongRoute -> true
        is Query.Near -> subject.hasSeed() || other.hasSeed()
        is Query.NotNear -> subject.hasSeed()
        is Query.Inside -> subject.hasSeed() || container.hasSeed()
        is Query.Contains -> subject.hasSeed()
        is Query.Sort -> query.hasSeed()
        is Query.Limit -> query.hasSeed()
        is Query.And -> parts.any { it.hasSeed() }
        is Query.Or -> parts.all { it.hasSeed() }
        else -> false
    }

    private fun filterSeedable(f: TagFilter): Boolean = when (f) {
        is TagFilter.Has, is TagFilter.Equals, is TagFilter.Compare -> true
        is TagFilter.Matches -> !f.negate
        is TagFilter.And -> f.parts.any { filterSeedable(it) }
        is TagFilter.Or -> f.parts.all { filterSeedable(it) }
        else -> false
    }

    // ------------------------------------------------------------------------------------------------- one run

    /** The state of one run: the neighbour sets of its "near" clauses, prepared corridors, parsed opening hours. */
    private inner class Run(val ctx: EvalContext) {
        private val nearSets = IdentityHashMap<Query, NearSet>()
        private val corridors = IdentityHashMap<Query.AlongRoute, Corridor>()
        private val nearIndex = IdentityHashMap<Query, Int>()
        private val nameWords = IdentityHashMap<Query.Name, List<String>>()
        private val hours = HashMap<String, OpeningHours?>()
        private val now: LocalDateTime = ctx.now.toLocalDateTime()

        /** The places looked at, whose branches of a brand (else places of a kind) give the hours of those that keep none. */
        var branches: Collection<PlaceRecord> = emptyList()
        private val book by lazy { HoursBook(branches) }

        /** Per want and brand, how many branches' tags pass it and how many say otherwise; each place counted once. */
        private val wantStats = IdentityHashMap<Query.Want, HashMap<String, IntArray>>()
        private val tallied = IdentityHashMap<Query.Want, HashSet<String>>()

        /** The buildings places lie in, for what a place takes of its building. */
        private val buildings = Query.Inside(Query.All, Query.Tags(TagFilter.Has("building")))

        /** The box around the candidates being checked, when known: the far side of a "near" is fetched for it. */
        var scope: BBox? = null

        /** The box a query is confined to by its conjunctive spatial parts, or null. */
        fun areaOf(q: Query): BBox? = when (q) {
            is Query.Within -> (q.center ?: ctx.location)?.let { BBox.around(it, q.radius) }
            is Query.InArea -> resolve(q)
            is Query.AlongRoute -> BBox.of(q.line)?.buffer(q.maxDetourM)
            is Query.And -> q.parts.mapNotNull { areaOf(it) }.reduceOrNull { a, b -> intersect(a, b) }
            is Query.Near -> areaOf(q.subject)
            is Query.NotNear -> areaOf(q.subject)
            is Query.Inside -> areaOf(q.subject)
            is Query.Contains -> areaOf(q.subject)
            is Query.Sort -> areaOf(q.query)
            is Query.Limit -> areaOf(q.query)
            else -> null
        }

        /** The box of an area: its own, the view for "bbox", else what the resolver knows of its name. */
        private fun resolve(q: Query.InArea): BBox? = q.bbox ?: q.name?.let { n ->
            if (n.equals("bbox", ignoreCase = true)) ctx.viewport else areaCache.getOrPut(n) { ctx.areas?.resolve(n) }
        }

        private val areaCache = HashMap<String, BBox?>()

        /**
         * No spatial part and nothing to look up: around the searcher (or the view), widening until enough match. A
         * query with a seed that none matches within the widest circle looks wherever the regions hold its places (the
         * owner, 4 November: "Default should be no maximum distance pre set if there are no results"); one without
         * would read every place there is, and stops at the circle.
         */
        fun expanding(q: Query): List<PlaceRecord> {
            ctx.viewport?.let { return candidates(q, it) }
            val at = ctx.location ?: return source.bounds?.let { candidates(q, it) } ?: emptyList()
            var last: List<PlaceRecord> = emptyList()
            var found = 0
            for (r in doubleArrayOf(1_500.0, 6_000.0, 25_000.0)) {
                last = candidates(q, BBox.around(at, r))
                scope = extentOf(last)
                found = last.count { matches(q, it, null) }
                if (found >= ctx.limit) return last
            }
            if (found == 0 && q.hasSeed()) source.bounds?.let { b -> last = candidates(q, b); scope = extentOf(last) }
            return last
        }

        /** Candidate places for [q] inside [area] (anywhere when null), a superset of its matches. */
        fun candidates(q: Query, area: BBox?): List<PlaceRecord> = seed(q, area) ?: area?.let { source.inBox(it) } ?: source.bounds?.let { source.inBox(it) } ?: emptyList()

        private fun seed(q: Query, area: BBox?): List<PlaceRecord>? = when (q) {
            is Query.Tags -> seedFilter(q.filter, area)
            is Query.Name -> source.text(q.text, ctx.location, area, TEXT_CANDIDATES)
            is Query.Category -> source.byCategory(leaves(q.id), area)
            is Query.Brand -> seedBrand(q.id, area)
            is Query.Id -> listOfNotNull(source.get(q.id)).filter { area == null || it.location in area }
            is Query.Within, is Query.InArea, is Query.AlongRoute -> areaOf(q)?.let { b -> source.inBox(area?.let { intersect(it, b) } ?: b) }
            is Query.Near -> seedNear(q, area)
            is Query.NotNear -> seed(q.subject, area)
            is Query.Inside -> seedInside(q, area)
            is Query.Contains -> seed(q.subject, area)
            is Query.Sort -> seed(q.query, area)
            is Query.Limit -> seed(q.query, area)
            is Query.And -> {
                val a = q.parts.mapNotNull { areaOf(it) }.fold(area) { acc, b -> acc?.let { intersect(it, b) } ?: b }
                val best = q.parts.filter { it.hasSeed() && it !is Query.Within && it !is Query.InArea && it !is Query.AlongRoute }
                    .minByOrNull { cost(it, a) }
                when {
                    best != null && (a == null || cost(best, a) <= source.estimate(a)) -> seed(best, a)
                    a != null -> source.inBox(a)
                    else -> null
                }
            }
            is Query.Or -> {
                val parts = q.parts.map { seed(it, area) ?: return null }
                union(parts)
            }
            else -> null
        }

        private fun seedFilter(f: TagFilter, area: BBox?): List<PlaceRecord>? = when (f) {
            is TagFilter.Has -> source.byTag(f.key, null, area)
            is TagFilter.Equals -> source.byTag(f.key, f.value, area)
            is TagFilter.Compare -> source.byTag(f.key, null, area)
            is TagFilter.Matches -> if (f.negate) null else literals(f.pattern)?.let { words ->
                union(words.map { source.byTag(f.key, it, area) })
            } ?: source.byTag(f.key, null, area)
            is TagFilter.And -> f.parts.filter { filterSeedable(it) }.minByOrNull { filterCost(it) }?.let { seedFilter(it, area) }
            is TagFilter.Or -> union(f.parts.map { seedFilter(it, area) ?: return null })
            else -> null
        }

        private fun seedBrand(id: String, area: BBox?): List<PlaceRecord> {
            val b = Brands.byId(id)
            val ids = b?.wikidata?.let { q -> Brands.byWikidata(q).map { it.id } }?.plus(id)?.distinct() ?: listOf(id)
            val byColumn = source.byBrand(ids, area)
            val byItem = b?.wikidata?.let { source.byTag("brand:wikidata", it, area) } ?: emptyList()
            return union(listOf(byColumn, byItem))
        }

        private fun seedNear(q: Query.Near, area: BBox?): List<PlaceRecord>? {
            val near = nearSet(q, area?.buffer(q.maxDistance))
            if (near.size == 0) return emptyList()
            val extent = near.extent.buffer(q.maxDistance)
            val box = area?.let { intersect(it, extent) } ?: extent
            if (near.size <= PER_NEIGHBOUR_SEEDS) {
                val parts = near.records.map { b ->
                    val around = PlaceRecord.boxOf(b.place).buffer(q.maxDistance)
                    val a = intersect(box, around)
                    seed(q.subject, a) ?: source.inBox(a)
                }
                return union(parts)
            }
            return seed(q.subject, box) ?: source.inBox(box)
        }

        /** The places in or at [q]'s containers, as a near clause's seed is the places around its far side. */
        private fun seedInside(q: Query.Inside, area: BBox?): List<PlaceRecord>? {
            val within = nearSet(q, area?.buffer(INSIDE_M))
            if (within.size == 0) return emptyList()
            val extent = within.extent.buffer(POINT_CONTAINER_M)
            val box = area?.let { intersect(it, extent) } ?: extent
            if (within.size <= PER_NEIGHBOUR_SEEDS) {
                return union(within.records.map { c ->
                    val a = intersect(box, PlaceRecord.boxOf(c.place).buffer(if (c.place.bbox == null) POINT_CONTAINER_M else INSIDE_M))
                    seed(q.subject, a) ?: source.inBox(a)
                })
            }
            return seed(q.subject, box) ?: source.inBox(box)
        }

        /** About how many places a seed for [q] would fetch in [area]. */
        fun cost(q: Query, area: BBox?): Double {
            val scale = if (area == null || source.size == 0) 1.0 else min(1.0, source.estimate(area).toDouble() / source.size)
            return when (q) {
                is Query.Id -> 1.0
                is Query.Brand -> 20.0
                is Query.Name -> 60.0
                is Query.Tags -> filterCost(q.filter) * scale
                is Query.Category -> Categories.byId(q.id)?.let { c ->
                    if (c.isGroup) Categories.children(c.id).sumOf { it.rule?.let(::filterCost) ?: 0.0 } else c.rule?.let(::filterCost)
                }?.times(scale) ?: 500.0
                is Query.Near -> min(cost(q.subject, area), cost(q.other, area) * 8)
                is Query.NotNear -> cost(q.subject, area)
                is Query.Inside -> min(cost(q.subject, area), cost(q.container, area) * 8)
                is Query.Contains -> cost(q.subject, area)
                is Query.And -> q.parts.filter { it.hasSeed() }.minOfOrNull { cost(it, area) } ?: Double.MAX_VALUE
                is Query.Or -> q.parts.sumOf { cost(it, area) }
                is Query.Within, is Query.InArea, is Query.AlongRoute -> areaOf(q)?.let { source.estimate(it).toDouble() } ?: Double.MAX_VALUE
                else -> Double.MAX_VALUE
            }
        }

        private fun filterCost(f: TagFilter): Double = when (f) {
            is TagFilter.Has -> keyCost(f.key, null)
            is TagFilter.Equals -> keyCost(f.key, f.value)
            is TagFilter.Compare -> keyCost(f.key, null)
            is TagFilter.Matches -> if (f.negate) Double.MAX_VALUE else literals(f.pattern)?.sumOf { keyCost(f.key, it) }
                ?: keyCost(f.key, null)
            is TagFilter.And -> f.parts.filter { filterSeedable(it) }.minOfOrNull { filterCost(it) } ?: Double.MAX_VALUE
            is TagFilter.Or -> f.parts.sumOf { if (filterSeedable(it)) filterCost(it) else return Double.MAX_VALUE }
            else -> Double.MAX_VALUE
        }

        /**
         * What looking up [key] (= [value]) costs: the places that carry it, or, when no index holds the tag, every
         * place it reads to find them (a store keeps no index of tags searches rarely filter on).
         */
        private fun keyCost(key: String, value: String?): Double {
            val n = source.estimate(key, value).toDouble()
            return if (source.indexes(key, value)) n else maxOf(n, source.size.toDouble())
        }

        // --------------------------------------------------------------------------------------------- checking

        /**
         * Whether [r] passes [q]. [found], when given, collects what scoring needs: the nearest neighbour of each
         * "near" clause, the text relevance, the route position, preferences met and the opening state.
         */
        fun matches(q: Query, r: PlaceRecord, found: Found?): Boolean = when (q) {
            is Query.Tags -> q.filter.matches(r.tags).also { if (it) found?.why?.add(whyOf(q.filter)) }
            is Query.Name -> {
                val rel = relevance(q, r)
                val ok = rel >= MIN_RELEVANCE
                if (ok) found?.let { it.relevance = min(it.relevance, rel); it.why += "name" }
                ok
            }
            is Query.Category -> {
                val c = r.category
                (c != null && (c == q.id || Categories.byId(c)?.parentId == q.id)).also { if (it) found?.why?.add("category:$c") }
            }
            is Query.Brand -> brandMatches(q.id, r).also { if (it) found?.why?.add("brand:${q.id}") }
            is Query.OpenAt -> openAt(q.time, r, found)
            is Query.Within -> {
                val c = q.center ?: ctx.location
                c != null && r.distanceTo(c) <= q.radius
            }
            // an area nobody could resolve does not narrow anything (the search engine says so)
            is Query.InArea -> resolve(q)?.let { r.location in it } ?: true
            is Query.AlongRoute -> {
                val c = corridors.getOrPut(q) { Corridor(q) }
                if (c.contains(r.location)) {
                    found?.along = c.along(r.location)
                    true
                } else {
                    false
                }
            }
            is Query.Near -> matches(q.subject, r, found) && near(q, r, found)
            is Query.NotNear -> matches(q.subject, r, found) && !notNear(q, r)
            is Query.Sort -> matches(q.query, r, found)
            is Query.Limit -> matches(q.query, r, found)
            is Query.And -> q.parts.all { matches(it, r, found) }
            is Query.Or -> if (found == null) q.parts.any { matches(it, r, null) } else q.parts.any { p ->
                // what a failed alternative noted down is forgotten
                val why = found.why.size
                val near = found.near.size
                val relevance = found.relevance
                val prefer = found.prefer
                val openUnknown = found.openUnknown
                val along = found.along
                val unsure = found.unsure.size
                matches(p, r, found).also { if (!it) found.reset(why, near, relevance, prefer, openUnknown, along, unsure) }
            }
            // "not in the centre" of a town nobody could resolve narrows nothing either
            is Query.Not -> (q.part as? Query.InArea)?.let { resolve(it) == null } == true || !matches(q.part, r, null)
            is Query.Type -> r.id?.type == q.type
            is Query.Id -> r.id == q.id
            is Query.Newer -> r.place.extra["timestamp"]?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let { !it.isBefore(q.since) } ?: false
            is Query.Prefer -> {
                if (found != null && matches(q.part, r, null)) {
                    found.prefer *= 1 + q.weight
                    found.why += "prefer:" + whyOf(q.part)
                }
                true
            }
            is Query.Inside -> matches(q.subject, r, found) && inside(q, r, found)
            is Query.Contains -> matches(q.subject, r, found) && contains(q, r, found)
            is Query.Want -> want(q, r, found)
            is Query.Rated -> ctx.rating(r.place)?.let { q.op.test(it, q.value) } ?: false
            is Query.ChangesAt -> changesAt(q, r, found)
            is Query.Coloured -> q.family in Colours.of(r.tags)
            is Query.ClosedOn -> closedOn(q.day, r, found)
            is Query.Than -> than(q, r)
            Query.All -> true
        }

        /** True when [r]'s hours (its brand's, where it keeps none) keep it shut all of [day]; unknown hours do not. */
        private fun closedOn(day: DayOfWeek, r: PlaceRecord, found: Found?): Boolean {
            val known = hoursOrBrand(r) ?: return false
            val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(day))
            val shut = known.first.spans(date, ctx.hours).none { it.state == OpenState.OPEN || it.state == OpenState.UNKNOWN }
            if (shut) found?.let { it.why += "closed"; if (!known.second) it.inferred = true }
            return shut
        }

        /** Per comparison with another place, that place's measure: NaN where no place of its name says one. */
        private val bounds = IdentityHashMap<Query.Than, Double>()

        /** True when [r]'s measure stands as [q] asks against the other place's ("taller than the Westertoren"). */
        private fun than(q: Query.Than, r: PlaceRecord): Boolean {
            val bound = bounds.getOrPut(q) { boundOf(q) }
            if (bound.isNaN()) return false
            val v = measure(q.key, r.tags) ?: return false
            return q.op.test(v, bound)
        }

        /** The measure of the place [Query.Than.other] finds: the best named of them, then the nearest. */
        private fun boundOf(q: Query.Than): Double {
            val name = q.other as? Query.Name
            val known = this@OfflineEvaluator.matches(q.other, ctx.copy(viewport = null))
                .mapNotNull { p -> measure(q.key, p.tags)?.let { v -> Triple(p, v, name?.let { relevance(it, p) } ?: 1.0) } }
            return known.sortedWith(compareByDescending<Triple<PlaceRecord, Double, Double>> { it.third }.thenBy { t -> ctx.location?.let { t.first.distanceTo(it) } ?: 0.0 })
                .firstOrNull()?.second ?: Double.NaN
        }

        /** True when [r] lies in or at a place of [q]'s containers (a point one: near it), noted as a near match. */
        private fun inside(q: Query.Inside, r: PlaceRecord, found: Found?): Boolean {
            val c = container(q, r) ?: return false
            found?.let { it.near += NearMatch(nearIndex.getOrPut(q) { nearIndex.size }, c.first, c.second, INSIDE_M) }
            return true
        }

        /** The container of [q] that [r] lies in, the smallest of them, and how far from its outline (0 inside). */
        private fun container(q: Query, r: PlaceRecord): Pair<PlaceRecord, Double>? =
            nearSet(q, around(r, POINT_CONTAINER_M), candidate = true).around(r, POINT_CONTAINER_M)
                .filter { (c, d) -> c.place.key != r.place.key && d <= (if (c.place.bbox == null) POINT_CONTAINER_M else INSIDE_M) }
                .minByOrNull { (c, d) -> d + boxArea(c) }

        private fun boxArea(c: PlaceRecord): Double = c.place.bbox?.let { (it.east - it.west) * (it.north - it.south) } ?: 0.0

        /** True when a place of [q]'s parts lies in or at [r]'s outline (around it, for a place drawn as a point). */
        private fun contains(q: Query.Contains, r: PlaceRecord, found: Found?): Boolean {
            val reach = if (r.place.bbox == null) POINT_CONTAINER_M else INSIDE_M
            val box = PlaceRecord.boxOf(r.place).buffer(reach)
            val part = nearSet(q, box, candidate = true).inBox(box)
                .filter { it.place.key != r.place.key && r.distanceTo(it.location) <= reach }
                .minByOrNull { Geo.distance(r.location, it.location) } ?: return false
            found?.let { it.near += NearMatch(nearIndex.getOrPut(q) { nearIndex.size }, part, Geo.distance(r.location, part.location), reach) }
            return true
        }

        /**
         * A want: passed by [r]'s tags (one of the reasons it matched), or by its building's for what a building says
         * (its age, height, floors, colours); failed when they say otherwise; else kept, unsure, likely when a place
         * [Query.Want.nearby] finds is within reach. Its brand's branches are counted, for [likely].
         */
        private fun want(q: Query.Want, r: PlaceRecord, found: Found?): Boolean {
            if (matches(q.part, r, null)) { tally(q, r, true); found?.why?.add(whyOf(q.part)); return true }
            if (says(q.part, r)) { tally(q, r, false); return false }
            building(q.part, r)?.let { b ->
                if (matches(q.part, b, null)) { found?.why?.add("building"); return true }
                if (says(q.part, b)) return false
            }
            val near = q.nearby?.let { nearSet(q, around(r, q.metres), candidate = true).nearest(r, q.metres) }
            found?.unsure?.add(q to (near != null))
            return true
        }

        /**
         * True when [r]'s tags say otherwise than [part], which they do not pass: a value it reads that it does not take
         * (a bare "yes" only says there is one: "outdoor_seating=yes" leaves a garden open), its rating, its hours.
         */
        private fun says(part: Query, r: PlaceRecord): Boolean = when (part) {
            is Query.Tags -> against(part.filter, r.tags)
            is Query.Rated -> ctx.rating(r.place) != null
            is Query.Coloured -> Colours.KEYS.any { it in r.tags }
            is Query.OpenAt, is Query.ChangesAt -> "opening_hours" in r.tags
            is Query.And -> part.parts.any { says(it, r) }
            is Query.Or -> part.parts.any { says(it, r) }
            // what it must not be, it is: said when the tags that make it are readable
            is Query.Not -> (part.part as? Query.Tags)?.filter?.keys()?.any { readable(it, r.tags) } ?: says(part.part, r)
            else -> true
        }

        /** True when [tags] speak against [f]: a key it reads there, readable, with a value it does not take. */
        private fun against(f: TagFilter, tags: Map<String, String>): Boolean = when (f) {
            is TagFilter.And -> f.parts.any { against(it, tags) }
            is TagFilter.Or -> f.parts.any { against(it, tags) }
            is TagFilter.Not -> f.part.keys().any { readable(it, tags) }
            is TagFilter.Equals -> readable(f.key, tags) && !f.matches(tags) && !(tags[f.key] == "yes" && f.value != "no")
            is TagFilter.Matches -> readable(f.key, tags) && !f.matches(tags) && !(tags[f.key] == "yes" && !f.negate && !f.matches(mapOf(f.key to "no")))
            else -> f.keys().any { readable(it, tags) } && !f.matches(tags)
        }

        /** True when [tags] hold [k] in a form it can be read in (a date key's value a date). */
        private fun readable(k: String, tags: Map<String, String>): Boolean = tags[k]?.let { v -> !Years.isDateKey(k) || Years.of(v) != null } == true

        /** The building [r] lies in, when [part] reads what a building says and [r] is not a building itself. */
        private fun building(part: Query, r: PlaceRecord): PlaceRecord? {
            if ("building" in r.tags || r.place.bbox != null && r.id?.type != app.atlas.model.OsmType.NODE) return null
            if (!readsBuilding(part)) return null
            return container(buildings, r)?.first
        }

        private fun readsBuilding(part: Query): Boolean = when (part) {
            is Query.Tags -> part.filter.keys().any { it in BUILDING_KEYS }
            is Query.Coloured -> true
            is Query.And -> part.parts.any { readsBuilding(it) }
            is Query.Or -> part.parts.any { readsBuilding(it) }
            else -> false
        }

        private fun tally(q: Query.Want, r: PlaceRecord, yes: Boolean) {
            val brand = r.brand ?: return
            if (!tallied.getOrPut(q) { HashSet() }.add(r.place.key)) return
            val counts = wantStats.getOrPut(q) { HashMap() }.getOrPut(brand) { IntArray(2) }
            counts[if (yes) 0 else 1]++
        }

        /**
         * [hits] that are unsure of a want made likely where most of their brand's branches that say pass it (at
         * least [LIKELY_BRANCHES], twice as many passing as not), and scored as sure as they are.
         */
        fun likely(hits: MutableList<Hit>) {
            for ((i, h) in hits.withIndex()) {
                if (h.sure != Sure.MAYBE) continue
                val brand = h.record.brand ?: continue
                val wants = unsureOf[h.record] ?: continue
                val all = wants.all { w -> wantStats[w]?.get(brand)?.let { (yes, no) -> yes >= LIKELY_BRANCHES && yes >= 2 * no } == true }
                if (all) hits[i] = h.copy(sure = Sure.LIKELY, score = h.score / MAYBE_FACTOR * LIKELY_FACTOR, why = h.why + "brand")
            }
        }

        /** The wants each unsure hit did not know, for [likely]. */
        private val unsureOf = IdentityHashMap<PlaceRecord, List<Query.Want>>()

        /** Places that open or close within the minutes asked of the time asked, on the day asked (today when none). */
        private fun changesAt(q: Query.ChangesAt, r: PlaceRecord, found: Found?): Boolean {
            val (oh, own) = hoursOrBrand(r) ?: return false
            if (!own) found?.let { it.inferred = true }
            val date = q.day?.let { now.toLocalDate().with(TemporalAdjusters.nextOrSame(it)) } ?: now.toLocalDate()
            val spans = oh.weekTable(date, ctx.hours)[date.dayOfWeek.value - 1].filter { it.state == OpenState.OPEN }
            val target = q.at.hour * 60 + q.at.minute
            val times = if (q.kind == ChangeKind.OPENS) spans.map { it.start } else spans.filter { !it.openEnd }.map { it.end }
            return times.any { t -> kotlin.math.abs(t - target) <= q.withinMinutes || kotlin.math.abs(t - DAY_MINUTES - target) <= q.withinMinutes }
        }

        /** How many minutes [r] is open today by its own hours or its brand's, or null when it has neither. */
        fun hoursToday(r: PlaceRecord): Int? {
            val oh = hoursOrBrand(r)?.first ?: return null
            if (oh.isAlwaysOpen) return 1440
            return oh.weekTable(now.toLocalDate(), ctx.hours)[now.dayOfWeek.value - 1].filter { it.state == OpenState.OPEN }.sumOf { it.minutes }
        }

        /** When [r] first opens today, minutes from midnight, or null when it does not or has no hours. */
        fun opensToday(r: PlaceRecord): Int? {
            val oh = hoursOrBrand(r)?.first ?: return null
            if (oh.isAlwaysOpen) return 0
            return oh.weekTable(now.toLocalDate(), ctx.hours)[now.dayOfWeek.value - 1].filter { it.state == OpenState.OPEN }.minOfOrNull { it.start }
        }

        /** When [r] last closes today, minutes from midnight (past 1440 after midnight), or null when it does not open or has no hours. */
        fun closesToday(r: PlaceRecord): Int? {
            val oh = hoursOrBrand(r)?.first ?: return null
            if (oh.isAlwaysOpen) return DAY_MINUTES
            return oh.weekTable(now.toLocalDate(), ctx.hours)[now.dayOfWeek.value - 1].filter { it.state == OpenState.OPEN }.maxOfOrNull { it.end }
        }

        /** A short reason for a filter or query part: its tag text, at most [WHY_LENGTH] characters. */
        private fun whyOf(f: TagFilter): String = f.toText().let { if (it.length > WHY_LENGTH) it.take(WHY_LENGTH - 1) + "…" else it }

        private fun whyOf(q: Query): String = when (q) {
            is Query.Tags -> whyOf(q.filter)
            is Query.Category -> "category:${q.id}"
            is Query.Brand -> "brand:${q.id}"
            is Query.Not -> "not " + whyOf(q.part)
            else -> q::class.simpleName?.lowercase() ?: "query"
        }

        private fun brandMatches(id: String, r: PlaceRecord): Boolean {
            if (r.brand == id) return true
            val b = Brands.byId(id) ?: return false
            val q = b.wikidata ?: return r.brand != null && Brands.byId(r.brand)?.brand == b.brand
            return r.tags["brand:wikidata"] == q || (r.brand != null && Brands.byId(r.brand)?.wikidata == q)
        }

        private fun relevance(q: Query.Name, r: PlaceRecord): Double {
            val words = nameWords.getOrPut(q) { SearchText.words(q.text) }
            if (words.isEmpty()) return 1.0
            val names = SearchText.names(r.tags)
            // an address point (or a building that is only its address) by its number and street
            if (names.isEmpty() && r.tags["addr:housenumber"] != null && words.any(Addresses::looksLikeNumber)) {
                return Addresses.relevance(words, r.tags)
            }
            val others = ArrayList<String>(6)
            r.tags["operator"]?.let { others += it }
            // a road's or a line's number: "N201", "A10", the night bus "N47"
            r.tags["ref"]?.let { others += it }
            (r.tags["addr:street"] ?: r.tags["addr:place"])?.let { s -> others += listOfNotNull(s, r.tags["addr:housenumber"]).joinToString(" ") }
            r.preset?.let { Presets.byId(it) }?.let { p -> others += p.name(ctx.lang); if (ctx.lang != "en") others += p.name("en") }
            r.tags["cuisine"]?.let { others += it.replace('_', ' ').replace(';', ' ') }
            return NameMatch.best(words, names, others)
        }

        private fun openAt(t: OpenTime, r: PlaceRecord, found: Found?): Boolean {
            val known = hoursOrGuess(r)
            if (known == null) {
                // No hours mapped: toilets, docks and the like are usually always open; the rest we cannot vouch for.
                val usually = usuallyOpen(r)
                if (usually) found?.let { it.openUnknown = true }
                return usually
            }
            val oh = known.first
            val ok = when (t) {
                OpenTime.Now -> oh.isOpen(now, ctx.hours)
                is OpenTime.At -> oh.isOpen(t.time, ctx.hours)
                is OpenTime.Today -> oh.isOpen(today(t.time), ctx.hours)
                OpenTime.Tonight -> oh.isOpen(now.toLocalDate().atTime(20, 30), ctx.hours)
                OpenTime.Late -> oh.isOpen(now.toLocalDate().atTime(23, 0), ctx.hours)
                is OpenTime.OnDay -> {
                    val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(t.day))
                    if (t.time != null) oh.isOpen(date.atTime(t.time), ctx.hours)
                    else oh.spans(date, ctx.hours).any { it.state == OpenState.OPEN }
                }
            }
            if (ok == true) found?.let { it.why += "open"; if (!known.second) it.inferred = true }
            return ok == true
        }

        private fun today(t: LocalTime): LocalDateTime {
            val at = now.toLocalDate().atTime(t)
            return if (at.isBefore(now.minusMinutes(30))) at.plusDays(1) else at
        }

        fun hoursOf(r: PlaceRecord): OpeningHours? {
            val raw = r.tags["opening_hours"] ?: return null
            return hours.getOrPut(raw) { OpeningHours.parseOrNull(raw) }
        }

        /**
         * [r]'s hours and whether they are its own: its own when it keeps any, else its brand's as its branches among
         * the places looked at keep them (unconfirmed), else null.
         */
        fun hoursOrBrand(r: PlaceRecord): Pair<OpeningHours, Boolean>? {
            if ("opening_hours" in r.tags) return hoursOf(r)?.let { it to true }
            return r.brand?.let { book.ofBrand(it) }?.let { it to false }
        }

        /**
         * The same, else its kind's as the places of its kind around it keep them ([HoursBook], unconfirmed): for
         * whether it is open at a time asked, never for a day it is closed or a time it changes.
         */
        fun hoursOrGuess(r: PlaceRecord): Pair<OpeningHours, Boolean>? {
            if ("opening_hours" in r.tags) return hoursOf(r)?.let { it to true }
            return book.of(r)?.let { it.hours to false }
        }

        /** The "near"s looked for farther, to tell how far the nearest is for a place that is not near enough. */
        private val farther = IdentityHashMap<Query.Near, Query.Near>()

        /** What [r] misses of the requirement [q] and how far off it is, or null when it meets it. */
        fun miss(q: Query, r: PlaceRecord): Miss? {
            if (matches(q, r, null)) return null
            return when (q) {
                is Query.OpenAt -> openMiss(q.time, r)
                is Query.Within -> (q.center ?: ctx.location)?.let { c -> Miss.Beyond((r.distanceTo(c) - q.radius).coerceAtLeast(0.0), q.radius) } ?: lacks(q, r)
                is Query.Near -> {
                    val wide = farther.getOrPut(q) { q.copy(maxDistance = q.maxDistance * FARTHER_LOOK) }
                    val d = nearSet(wide, around(r, wide.maxDistance), candidate = true).nearest(r, wide.maxDistance)?.second
                    val (label, symbol) = labelOf(q.other)
                    Miss.Farther(label, symbol, d, q.maxDistance)
                }
                is Query.Rated -> Miss.Rated(ctx.rating(r.place), q.value)
                is Query.Want -> (q.part as? Query.Rated)?.let { Miss.Rated(ctx.rating(r.place), it.value) } ?: lacks(q.part, r)
                is Query.InArea -> Miss.Outside(q.name?.takeIf { !it.startsWith("@") && !it.equals("bbox", true) } ?: "", resolve(q)?.let { outside(it, r.location) })
                else -> lacks(q, r)
            }
        }

        /** How far [r] is from being open at [t]: as [Closest.openMiss] tells, the hours of its brand or kind standing in for its own. */
        private fun openMiss(t: OpenTime, r: PlaceRecord): Miss {
            val h = hoursOrGuess(r)?.first ?: return Miss.HoursUnknown
            val (at, back) = when (t) {
                OpenTime.Now -> now to false
                is OpenTime.At -> t.time to true
                is OpenTime.Today -> today(t.time) to true
                OpenTime.Tonight -> now.toLocalDate().atTime(20, 30) to true
                OpenTime.Late -> now.toLocalDate().atTime(23, 0) to true
                is OpenTime.OnDay -> (t.time?.let { now.toLocalDate().with(TemporalAdjusters.nextOrSame(t.day)).atTime(it) } ?: return lacks(Query.OpenAt(t), r)) to true
            }
            return Closest.openMiss(h, at, back, ctx.hours) ?: lacks(Query.OpenAt(t), r)
        }

        /** [r] does not have what [q] asks: its first key's label (with the value asked, where one is), and whether [r] says nothing of it. */
        private fun lacks(q: Query, r: PlaceRecord): Miss {
            val keys = keysOf(q)
            val (label, symbol) = labelOf(q)
            return Miss.Lacks(keys.firstOrNull() ?: "", label, symbol, unsaid = keys.isNotEmpty() && keys.none { it in r.tags })
        }

        /**
         * The words and symbol for what [q] asks: a tag's value as its facet calls it ("Vegan"), else its key's
         * ("Wi-Fi"); no words for what reads no tag.
         */
        private fun labelOf(q: Query): Pair<String, String?> {
            when (q) {
                is Query.Near -> return labelOf(q.other)
                is Query.NotNear -> return labelOf(q.other)
                is Query.Inside -> return labelOf(q.container)
                is Query.Contains -> return labelOf(q.part)
                is Query.Category -> Categories.byId(q.id)?.let { return it.name(ctx.lang) to it.symbol }
                is Query.And -> return q.parts.map { labelOf(it) }.firstOrNull { it.first.isNotEmpty() } ?: ("" to null)
                is Query.Or -> return q.parts.map { labelOf(it) }.firstOrNull { it.first.isNotEmpty() } ?: ("" to null)
                else -> {}
            }
            val f = (q as? Query.Tags)?.filter ?: (q as? Query.Want)?.part?.let { (it as? Query.Tags)?.filter } ?: (q as? Query.Not)?.part?.let { (it as? Query.Tags)?.filter }
            val first = f?.let { firstTag(it) }
            val key = first?.first ?: keysOf(q).firstOrNull() ?: return "" to null
            val facet = Labels.facet(key, ctx.lang)
            val value = first?.second ?: return facet to Labels.facetSymbol(key)
            val id = Facts.stateOf(value)?.let { "~" + it.name.lowercase(Locale.ROOT) }
            val kind = if (id != null) FacetKind.FLAG else FacetKind.VALUES
            val v = id ?: value
            return Labels.value(key, kind, v, facet, ctx.lang).second to (Labels.valueSymbol(key, kind, v) ?: Labels.facetSymbol(key))
        }

        private fun firstTag(f: TagFilter): Pair<String, String?>? = when (f) {
            is TagFilter.Equals -> f.key to f.value
            is TagFilter.Has -> f.key to null
            is TagFilter.Compare -> f.key to null
            is TagFilter.Matches -> f.key to null
            is TagFilter.And -> f.parts.firstNotNullOfOrNull { firstTag(it) }
            is TagFilter.Or -> f.parts.firstNotNullOfOrNull { firstTag(it) }
            else -> null
        }

        /** Metres from [p] to the nearest edge of [b], 0 inside it. */
        private fun outside(b: BBox, p: LatLon): Double {
            val lat = p.lat.coerceIn(b.south, b.north)
            val lon = p.lon.coerceIn(b.west, b.east)
            return Geo.distance(p.lat, p.lon, lat, lon)
        }

        private fun near(q: Query.Near, r: PlaceRecord, found: Found?): Boolean {
            val set = nearSet(q, around(r, q.maxDistance), candidate = true)
            val (b, d) = set.nearest(r, q.maxDistance) ?: return false
            found?.let { it.near += NearMatch(nearIndex.getOrPut(q) { nearIndex.size }, b, d, q.maxDistance) }
            return true
        }

        private fun notNear(q: Query.NotNear, r: PlaceRecord): Boolean =
            nearSet(q, around(r, q.minDistance), candidate = true).nearest(r, q.minDistance) != null

        private fun around(r: PlaceRecord, metres: Double): BBox = PlaceRecord.boxOf(r.place).buffer(metres)

        /**
         * The places of a near clause's far side, at least all of those meeting [need] (all of them when null). Built
         * once for the run's area grown by the distance; built again, for everything, only if a candidate falls
         * outside what it holds. A [candidate]'s own box is too small to build for: the set is then built around
         * all the candidates ([Run.scope]), or for everything.
         */
        private fun nearSet(q: Query, need: BBox?, candidate: Boolean = false): NearSet {
            val cached = nearSets[q]
            if (cached != null && covers(cached.coverage, need)) return cached
            val (other, d, subject) = when (q) {
                is Query.Near -> Triple(q.other, q.maxDistance, q.subject)
                is Query.NotNear -> Triple(q.other, q.minDistance, q.subject)
                is Query.Inside -> Triple(q.container, POINT_CONTAINER_M, q.subject)
                is Query.Contains -> Triple(q.part, POINT_CONTAINER_M, q.subject)
                is Query.Want -> Triple(q.nearby ?: Query.All, q.metres, Query.All)
                else -> error("not a near clause")
            }
            // the first time, for the subject's own area or around all the candidates; when a candidate falls
            // outside, around all the candidates if that is more, else for everything
            val wide = scope?.buffer(d)
            val coverage = when {
                cached == null && !candidate -> need ?: areaOf(subject)?.buffer(d)
                wide != null && covers(wide, need) && (cached == null || !covers(cached.coverage, wide)) -> wide
                else -> null
            }
            val own = areaOf(other)
            val box = when {
                coverage == null -> own
                own == null -> coverage
                else -> intersect(coverage, own)
            }
            val set = NearSet(candidates(other, box).filter { matches(other, it, null) }, coverage)
            nearSets[q] = set
            return set
        }

        private fun covers(have: BBox?, need: BBox?): Boolean = when {
            have == null -> true
            need == null -> false
            else -> need.west >= have.west && need.east <= have.east && need.south >= have.south && need.north <= have.north
        }

        // --------------------------------------------------------------------------------------------- scoring

        fun hit(q: Query, r: PlaceRecord): Hit? {
            val found = Found()
            if (!matches(q, r, found)) return null
            val distance = ctx.location?.let { r.distanceTo(it) } ?: areaOf(q)?.center?.let { r.distanceTo(it) }
            // its kind's hours stand in only where the query asks when it is open
            val known = if (asksOpen(q)) hoursOrGuess(r) else hoursOrBrand(r)
            val inferred = known?.second == false
            val at = openTimeOf(q)
            val status = known?.first?.status(at, ctx.hours)
            val rating = ctx.rating(r.place)

            val scale = withinRadius(q)?.let { max(it / 2, 100.0) } ?: DISTANCE_SCALE
            val distanceFactor = distance?.let { 1.0 / (1.0 + it / scale) } ?: 1.0
            val openState = when {
                found.openUnknown -> 0.75
                status == null -> 0.93
                status.unknown -> 0.9
                status.isOpen -> 1.0
                else -> 0.8
            } * (if (inferred) INFERRED_FACTOR else 1.0)
            // a place looked up by its name is that place, open or not: its hours only nudge
            val openFactor = if (byName(q)) 1.0 - 0.25 * (1.0 - openState) else openState
            val ratingFactor = rating?.let { 0.8 + 0.04 * it.coerceIn(0.0, 5.0) } ?: 0.94
            var nearFactor = 1.0
            for (n in found.near) nearFactor *= 1.0 - 0.25 * (n.distance / n.maxDistance).coerceIn(0.0, 1.0)
            val named = if (r.name() != null) 1.0 else 0.9
            val relevance = found.relevance
            // a want its tags do not say: likely when a place that provides it is at hand, else unsure
            val sure = when {
                found.unsure.isEmpty() -> Sure.YES
                found.unsure.all { it.second } -> Sure.LIKELY
                else -> Sure.MAYBE
            }
            if (sure == Sure.MAYBE) unsureOf[r] = found.unsure.filter { !it.second }.map { it.first }
            val sureFactor = when (sure) {
                Sure.YES -> 1.0
                Sure.LIKELY -> LIKELY_FACTOR
                else -> MAYBE_FACTOR
            }
            val score = relevance * relevance * distanceFactor * openFactor * ratingFactor * nearFactor * found.prefer * named * sureFactor
            return Hit(
                record = r,
                score = score,
                relevance = relevance,
                distance = distance,
                open = status,
                near = found.near.sortedBy { it.clause },
                along = found.along,
                why = found.why.distinct(),
                rating = rating,
                sure = sure,
                unknown = found.unsure.flatMap { keysOf(it.first.part) }.distinct(),
                hoursInferred = inferred,
            )
        }

        private val asks = IdentityHashMap<Query, Boolean>()

        /** True when [q] asks whether its places are open at some time. */
        private fun asksOpen(q: Query): Boolean = asks.getOrPut(q) {
            var open = false
            q.walk { if (it is Query.OpenAt) open = true }
            open
        }

        private fun openTimeOf(q: Query): LocalDateTime {
            var t: OpenTime? = null
            q.walk { if (it is Query.OpenAt && t == null) t = it.time }
            return when (val o = t) {
                null, OpenTime.Now -> now
                is OpenTime.At -> o.time
                is OpenTime.Today -> today(o.time)
                OpenTime.Tonight -> now.toLocalDate().atTime(20, 30)
                OpenTime.Late -> now.toLocalDate().atTime(23, 0)
                is OpenTime.OnDay -> now.toLocalDate().with(TemporalAdjusters.nextOrSame(o.day)).atTime(o.time ?: LocalTime.NOON)
            }
        }

        /** True when [q] looks a place up by name and does not ask about opening times. */
        private fun byName(q: Query): Boolean {
            var name = false
            var open = false
            q.walk { if (it is Query.Name) name = true else if (it is Query.OpenAt) open = true }
            return name && !open
        }

        private fun withinRadius(q: Query): Double? = when (q) {
            is Query.Within -> q.radius
            is Query.And -> q.parts.firstNotNullOfOrNull { withinRadius(it) }
            is Query.Near -> withinRadius(q.subject)
            is Query.Inside -> withinRadius(q.subject)
            is Query.Contains -> withinRadius(q.subject)
            else -> null
        }

        /** The keys [q] reads, for saying what a place does not say. */
        private fun keysOf(q: Query): List<String> = when (q) {
            is Query.Tags -> q.filter.keys().toList()
            is Query.Rated -> listOf("rating")
            is Query.Coloured -> listOf("building:colour")
            is Query.OpenAt, is Query.ChangesAt -> listOf("opening_hours")
            is Query.And -> q.parts.flatMap { keysOf(it) }
            is Query.Or -> q.parts.flatMap { keysOf(it) }.take(1)
            is Query.Not -> keysOf(q.part)
            else -> emptyList()
        }
    }

    /** What checking a candidate found out, for its score. */
    private class Found {
        var relevance = 1.0
        var prefer = 1.0
        var openUnknown = false

        /** Its hours, asked about, are its brand's rather than its own. */
        var inferred = false
        var along: Double? = null
        val near = ArrayList<NearMatch>(2)
        val why = ArrayList<String>(2)

        /** The wants its tags did not say, each with whether a place providing it is at hand. */
        val unsure = ArrayList<Pair<Query.Want, Boolean>>(1)

        /** Goes back to the notes as they stood: reasons, near matches, text relevance, preferences, route position. */
        fun reset(whySize: Int, nearSize: Int, relevance: Double, prefer: Double, openUnknown: Boolean, along: Double?, unsureSize: Int) {
            this.relevance = relevance
            this.prefer = prefer
            this.openUnknown = openUnknown
            this.along = along
            while (why.size > whySize) why.removeAt(why.size - 1)
            while (near.size > nearSize) near.removeAt(near.size - 1)
            while (unsure.size > unsureSize) unsure.removeAt(unsure.size - 1)
        }
    }

    /** The far side of a near clause in an R-tree of boxes, in metres around the set's centre; [coverage] is the box it holds all of (null: everywhere). */
    private class NearSet(val records: List<PlaceRecord>, val coverage: BBox?) {
        val size get() = records.size
        val extent: BBox = records.fold(null as BBox?) { acc, r -> PlaceRecord.boxOf(r.place).let { b -> acc?.union(b) ?: b } } ?: BBox(0.0, 0.0, 0.0, 0.0)
        private val frame = LocalFrame(extent.center)
        private val cosCentre = kotlin.math.cos(Math.toRadians(extent.center.lat))
        private val tree: PackedRTree? = if (records.isEmpty()) null else PackedRTree(records.size).also { t ->
            for (r in records) {
                val b = PlaceRecord.boxOf(r.place)
                t.add(frame.x(b.west), frame.y(b.south), frame.x(b.east), frame.y(b.north))
            }
            t.finish()
        }

        /** The places of the set whose boxes [b] touches. */
        fun inBox(b: BBox): List<PlaceRecord> {
            val t = tree ?: return emptyList()
            return t.search(frame.x(b.west), frame.y(b.south), frame.x(b.east), frame.y(b.north)).map { records[it] }
        }

        /** Every place of the set within [max] metres of [r] (its outline from [r]'s point), with the distance. */
        fun around(r: PlaceRecord, max: Double): List<Pair<PlaceRecord, Double>> {
            val b = BBox.around(r.location, max)
            return inBox(b).map { it to it.distanceTo(r.location) }.filter { it.second <= max }
        }

        /** The nearest of the set to [r] within [max] metres (not [r] itself), and its distance. */
        fun nearest(r: PlaceRecord, max: Double): Pair<PlaceRecord, Double>? {
            val t = tree ?: return null
            val p = r.location
            val x = frame.x(p.lon)
            val y = frame.y(p.lat)
            // The frame's metres east are exact at its centre's latitude and too long towards the pole (by 4% across
            // a country): look as much wider as the set reaches poleward, then measure.
            val poleward = minOf(89.0, maxOf(kotlin.math.abs(extent.south), kotlin.math.abs(extent.north)) + Geo.metresToDegreesLat(max))
            val stretch = maxOf(1.0, cosCentre / kotlin.math.cos(Math.toRadians(poleward)))
            val ids = t.neighbors(x, y, 4, max * stretch * 1.01 + 1)
            var best: PlaceRecord? = null
            var bestD = Double.MAX_VALUE
            for (i in ids) {
                val b = records[i]
                if (b.place.key == r.place.key) continue
                val d = b.distanceTo(p)
                if (d <= max && d < bestD) { best = b; bestD = d }
            }
            return best?.let { it to bestD }
        }
    }

    /** A route's corridor, prepared for many point tests, and the route itself for positions along it. */
    private class Corridor(q: Query.AlongRoute) {
        private val line = Polyline(q.line)
        private val polygon: PreparedPolygon? = if (q.line.size >= 2) PreparedPolygon(Shapes.corridorRings(line, q.maxDetourM)) else null
        private val point = q.line.singleOrNull()
        private val radius = q.maxDetourM

        fun contains(p: LatLon): Boolean = when {
            polygon != null -> polygon.contains(p)
            point != null -> Geo.distance(point, p) <= radius
            else -> false
        }

        fun along(p: LatLon): Double? = line.snap(p)?.along
    }

    internal companion object {
        const val TEXT_CANDIDATES = 300
        const val WIDE_SEED = 20_000.0

        /** The most a "largest" sort lists of a region's ([largestOf]), and how many times as many it reads to check. */
        const val LARGEST_MAX = 60
        const val LARGEST_SPARE = 2

        /** How many of a query's places, as what they are, the closest matches are chosen from. */
        const val CLOSEST_POOL = 400

        /** How many times its distance a "near" not met is looked for, to tell how far the nearest is. */
        const val FARTHER_LOOK = 10.0
        const val PER_NEIGHBOUR_SEEDS = 48
        const val MIN_RELEVANCE = 0.45
        const val DISTANCE_SCALE = 1_500.0
        const val WHY_LENGTH = 48

        /** How far outside a container's outline a place still counts as in it (drawn on its edge), and near one drawn as a point. */
        const val INSIDE_M = 10.0
        const val POINT_CONTAINER_M = 60.0

        /** How a want's unsure and likely places score against the sure. */
        const val MAYBE_FACTOR = 0.55
        const val LIKELY_FACTOR = 0.8

        /** Hours that are a brand's, not the place's own, count a little less. */
        const val INFERRED_FACTOR = 0.96
        const val DAY_MINUTES = 1440

        /** A brand's branches that say so make a want likely at its others from this many on. */
        const val LIKELY_BRANCHES = 3

        /** What a building says of the places in it: its age, its height and floors, its colours, its stone. */
        val BUILDING_KEYS = setOf("start_date", "building:start_date", "height", "building:height", "building:levels", "building:colour", "roof:colour", "building:material", "colour")

        /** The box around every place of [records], or null when there are none. */
        fun extentOf(records: List<PlaceRecord>): BBox? {
            if (records.isEmpty()) return null
            var w = Double.POSITIVE_INFINITY
            var s = Double.POSITIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            for (r in records) {
                val b = r.place.bbox
                val p = r.place.location
                w = min(w, b?.west ?: p.lon); s = min(s, b?.south ?: p.lat)
                e = max(e, b?.east ?: p.lon); n = max(n, b?.north ?: p.lat)
            }
            return BBox(w, s, e, n)
        }

        fun intersect(a: BBox, b: BBox): BBox {
            val w = max(a.west, b.west)
            val s = max(a.south, b.south)
            val e = min(a.east, b.east)
            val n = min(a.north, b.north)
            return if (w > e || s > n) BBox(w, s, w, s) else BBox(w, s, e, n)
        }

        fun union(lists: List<List<PlaceRecord>>): List<PlaceRecord> {
            if (lists.size == 1) return lists[0]
            val seen = HashSet<String>()
            val out = ArrayList<PlaceRecord>()
            for (l in lists) for (r in l) if (seen.add(r.place.key)) out += r
            return out
        }

        /** The words of an anchored list of literal alternatives, `^(a|b)$`, else null. */
        fun literals(p: String): List<String>? {
            if (p.length < 3 || p[0] != '^' || p[p.length - 1] != '$') return null
            var body = p.substring(1, p.length - 1)
            val grouped = body.length >= 2 && body[0] == '(' && body[body.length - 1] == ')'
            if (grouped) body = body.substring(1, body.length - 1)
            if (body.isEmpty() || ('|' in body && !grouped)) return null
            if (body.any { !(it.isLetterOrDigit() || it == '_' || it == ':' || it == '|' || it == '-' || it == ' ') }) return null
            return body.split('|')
        }

        /** Kinds of places that are open whenever their hours are not mapped. */
        private val USUALLY_OPEN = setOf(
            "toilets", "drinking_water", "bicycle_rental", "charging_station", "atm", "bench", "parking", "bicycle_parking",
            "shelter", "water_point", "post_box", "telephone", "vending_machine", "parcel_locker", "defibrillator",
        )
        private val USUALLY_OPEN_LEISURE = setOf("park", "playground", "garden", "pitch", "common", "dog_park", "picnic_table")

        fun usuallyOpen(r: PlaceRecord): Boolean = usuallyOpen(r.tags)

        /** True for kinds of places that are open whenever their hours are not mapped: toilets, docks, parks, stops. */
        fun usuallyOpen(t: Map<String, String>): Boolean =
            t["amenity"] in USUALLY_OPEN || t["leisure"] in USUALLY_OPEN_LEISURE || t["tourism"] == "viewpoint" ||
                t["emergency"] == "defibrillator" || t["highway"] == "bus_stop" || t["public_transport"] != null

        fun leaves(id: String): Set<String> {
            val c = Categories.byId(id) ?: return setOf(id)
            return if (c.isGroup) Categories.children(id).map { it.id }.toSet() + id else setOf(id)
        }
    }
}

/** True when [key] (= [value]) is found through an index of this source, rather than by reading its places. */
internal fun PlaceSource.indexes(key: String, value: String?): Boolean = when (this) {
    is PlaceStore -> indexes(key, value)
    is Regions -> stores.all { it.indexes(key, value) }
    else -> true
}
