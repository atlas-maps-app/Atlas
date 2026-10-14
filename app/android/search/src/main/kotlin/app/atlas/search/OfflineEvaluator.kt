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
import app.atlas.places.OpenState
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.Presets
import app.atlas.places.TagFilter
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.IdentityHashMap
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
        val candidates = when {
            area != null -> run.candidates(q, area)
            // A seed of a few thousand places is cheap anywhere; a bigger one (every café of a country) is looked
            // for around the searcher first, then further, like a query nothing narrows down.
            q.hasSeed() && (run.cost(q, null) <= WIDE_SEED || (ctx.location == null && ctx.viewport == null)) -> run.candidates(q, null)
            else -> run.expanding(q)
        }
        run.scope = extentOf(candidates)
        val hits = ArrayList<Hit>()
        for (c in candidates) run.hit(q, c)?.let { hits += it }
        sortHits(hits, sort)
        val total = hits.size
        val out = if (hits.size > limit) hits.subList(0, limit).toList() else hits
        return QueryResult(out, total, candidates.size, (System.nanoTime() - started) / 1000)
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

    /** The places [query] finds, unranked: for the far side of a "near", and for counts. */
    fun matches(query: Query, ctx: EvalContext, area: BBox? = null): List<PlaceRecord> {
        val run = Run(ctx)
        val a = area ?: run.areaOf(query)
        val cands = if (a != null || query.hasSeed()) run.candidates(query, a) else run.expanding(query)
        run.scope = extentOf(cands)
        return cands.filter { run.matches(query, it, null) }
    }

    private fun sortHits(hits: MutableList<Hit>, by: SortBy) {
        when (by) {
            SortBy.RELEVANCE -> hits.sortWith(compareByDescending<Hit> { it.score }.thenBy { it.distance ?: Double.MAX_VALUE })
            SortBy.DISTANCE -> hits.sortWith(compareBy<Hit> { it.distance ?: Double.MAX_VALUE }.thenByDescending { it.score })
            SortBy.RATING -> hits.sortWith(compareByDescending<Hit> { it.rating ?: -1.0 }.thenByDescending { it.score })
            SortBy.OPEN_LONGEST -> hits.sortWith(
                compareBy<Hit> { openRank(it.open) }
                    .thenByDescending { h -> h.open?.takeIf { it.isOpen }?.changesAt ?: LocalDateTime.MAX }
                    .thenByDescending { it.score },
            )
        }
    }

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
            is Query.Sort -> areaOf(q.query)
            is Query.Limit -> areaOf(q.query)
            else -> null
        }

        /** The box of an area: its own, the view for "bbox", else what the resolver knows of its name. */
        private fun resolve(q: Query.InArea): BBox? = q.bbox ?: q.name?.let { n ->
            if (n.equals("bbox", ignoreCase = true)) ctx.viewport else areaCache.getOrPut(n) { ctx.areas?.resolve(n) }
        }

        private val areaCache = HashMap<String, BBox?>()

        /** No spatial part and nothing to look up: around the searcher (or the view), widening until enough match. */
        fun expanding(q: Query): List<PlaceRecord> {
            ctx.viewport?.let { return candidates(q, it) }
            val at = ctx.location ?: return source.bounds?.let { candidates(q, it) } ?: emptyList()
            var last: List<PlaceRecord> = emptyList()
            for (r in doubleArrayOf(1_500.0, 6_000.0, 25_000.0)) {
                last = candidates(q, BBox.around(at, r))
                scope = extentOf(last)
                if (last.count { matches(q, it, null) } >= ctx.limit) return last
            }
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
                is Query.And -> q.parts.filter { it.hasSeed() }.minOfOrNull { cost(it, area) } ?: Double.MAX_VALUE
                is Query.Or -> q.parts.sumOf { cost(it, area) }
                is Query.Within, is Query.InArea, is Query.AlongRoute -> areaOf(q)?.let { source.estimate(it).toDouble() } ?: Double.MAX_VALUE
                else -> Double.MAX_VALUE
            }
        }

        private fun filterCost(f: TagFilter): Double = when (f) {
            is TagFilter.Has -> source.estimate(f.key, null).toDouble()
            is TagFilter.Equals -> source.estimate(f.key, f.value).toDouble()
            is TagFilter.Compare -> source.estimate(f.key, null).toDouble()
            is TagFilter.Matches -> if (f.negate) Double.MAX_VALUE else literals(f.pattern)?.sumOf { source.estimate(f.key, it).toDouble() }
                ?: source.estimate(f.key, null).toDouble()
            is TagFilter.And -> f.parts.filter { filterSeedable(it) }.minOfOrNull { filterCost(it) } ?: Double.MAX_VALUE
            is TagFilter.Or -> f.parts.sumOf { if (filterSeedable(it)) filterCost(it) else return Double.MAX_VALUE }
            else -> Double.MAX_VALUE
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
                matches(p, r, found).also { if (!it) found.reset(why, near, relevance, prefer, openUnknown, along) }
            }
            is Query.Not -> !matches(q.part, r, null)
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
            Query.All -> true
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
            val others = ArrayList<String>(6)
            r.tags["operator"]?.let { others += it }
            // a road's or a line's number: "N201", "A10", the night bus "N47"
            r.tags["ref"]?.let { others += it }
            r.tags["addr:street"]?.let { s -> others += listOfNotNull(s, r.tags["addr:housenumber"]).joinToString(" ") }
            r.preset?.let { Presets.byId(it) }?.let { p -> others += p.name(ctx.lang); if (ctx.lang != "en") others += p.name("en") }
            r.tags["cuisine"]?.let { others += it.replace('_', ' ').replace(';', ' ') }
            return NameMatch.best(words, names, others)
        }

        private fun openAt(t: OpenTime, r: PlaceRecord, found: Found?): Boolean {
            val oh = hoursOf(r)
            if (oh == null) {
                // No hours mapped: toilets, docks and the like are usually always open; the rest we cannot vouch for.
                val usually = usuallyOpen(r)
                if (usually) found?.let { it.openUnknown = true }
                return usually
            }
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
            if (ok == true) found?.let { it.why += "open" }
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
            val hours = hoursOf(r)
            val at = openTimeOf(q)
            val status = hours?.status(at, ctx.hours)
            val rating = ctx.rating(r.place)

            val scale = withinRadius(q)?.let { max(it / 2, 100.0) } ?: DISTANCE_SCALE
            val distanceFactor = distance?.let { 1.0 / (1.0 + it / scale) } ?: 1.0
            val openState = when {
                found.openUnknown -> 0.75
                status == null -> 0.93
                status.unknown -> 0.9
                status.isOpen -> 1.0
                else -> 0.8
            }
            // a place looked up by its name is that place, open or not: its hours only nudge
            val openFactor = if (byName(q)) 1.0 - 0.25 * (1.0 - openState) else openState
            val ratingFactor = rating?.let { 0.8 + 0.04 * it.coerceIn(0.0, 5.0) } ?: 0.94
            var nearFactor = 1.0
            for (n in found.near) nearFactor *= 1.0 - 0.25 * (n.distance / n.maxDistance).coerceIn(0.0, 1.0)
            val named = if (r.name() != null) 1.0 else 0.9
            val relevance = found.relevance
            val score = relevance * relevance * distanceFactor * openFactor * ratingFactor * nearFactor * found.prefer * named
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
            )
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
            else -> null
        }
    }

    /** What checking a candidate found out, for its score. */
    private class Found {
        var relevance = 1.0
        var prefer = 1.0
        var openUnknown = false
        var along: Double? = null
        val near = ArrayList<NearMatch>(2)
        val why = ArrayList<String>(2)

        /** Goes back to the notes as they stood: reasons, near matches, text relevance, preferences, route position. */
        fun reset(whySize: Int, nearSize: Int, relevance: Double, prefer: Double, openUnknown: Boolean, along: Double?) {
            this.relevance = relevance
            this.prefer = prefer
            this.openUnknown = openUnknown
            this.along = along
            while (why.size > whySize) why.removeAt(why.size - 1)
            while (near.size > nearSize) near.removeAt(near.size - 1)
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
        const val PER_NEIGHBOUR_SEEDS = 48
        const val MIN_RELEVANCE = 0.45
        const val DISTANCE_SCALE = 1_500.0
        const val WHY_LENGTH = 48

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
