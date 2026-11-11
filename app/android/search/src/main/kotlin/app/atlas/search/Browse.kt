package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.ChangeKind
import app.atlas.places.Freshness
import app.atlas.places.NodeKind
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.TagFilter
import app.atlas.model.Place
import app.atlas.providers.BathingSite
import app.atlas.providers.WaterKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.exp

/** What a category's tile says without being opened, chosen by how many places are near and what they are. */
enum class TellKind {
    /** "12 open": many near, their hours known. */
    OPEN_COUNT,

    /** "30 near": many near, few hours known. */
    COUNT,

    /** "6 min": few near, the nearest. */
    NEAREST,

    /** "opens 09:00": none open now. */
    OPENS_AT,

    /** "till 03:00": open late, the latest of them closes then. */
    OPEN_TILL,

    /** "150 kW": the fastest charger near. */
    FASTEST_KW,

    /** "4 free": toilets and the like that cost nothing. */
    FREE_COUNT,

    /** Nothing near. */
    NONE,
}

/**
 * A category's state around the searcher, for its tile in the grid (the user, 3 November: "Smart categories: they
 * show additional info without the need to open, useful now and specific to the category… a main tell, depending on
 * density (populated vs sparse)"): how many places of it are near and open, the nearest and how long it takes to
 * reach, and the one thing its tile says ([tell] with its [value]).
 */
data class CategoryPulse(
    val node: CategoryNode,
    val count: Int,
    val open: Int,
    /** Of [open], those open only by hours guessed from their brand's branches or their kind's around them ([HoursBook]). */
    val unconfirmed: Int,
    val nearest: PlaceRecord?,
    val metres: Double?,
    val minutes: Int?,
    val tell: TellKind,
    /** The tell's value: a time ("22:00"), kilowatts ("150"), a count, minutes. */
    val value: String?,
    /**
     * When the last of the open ones closes (those that never close aside), by hours of their own where any of them
     * says, else by guessed ones ([untilGuessed]); null when none of them closes.
     */
    val until: LocalDateTime? = null,
    /** Of [open], those with no closing ahead: open day and night, or open without hours as such places are. */
    val endless: Int = 0,
    /** With none open, when the first of them opens, by hours of their own where any says ([opensGuessed]); null when one is open or none says. */
    val opensAt: LocalDateTime? = null,
    /** True when only guessed hours say [until]: none of those open by hours of their own says when it closes. */
    val untilGuessed: Boolean = false,
    /** True when only guessed hours say [opensAt]: none of them by hours of its own says when it opens. */
    val opensGuessed: Boolean = false,
) {
    /** Many places near: the tile counts them; few: it says the nearest. */
    val dense: Boolean get() = count >= DENSE

    companion object {
        const val DENSE = 5

        /** A kind none of which is near: no count, no nearest. */
        fun none(node: CategoryNode) = CategoryPulse(node, 0, 0, 0, null, null, null, TellKind.NONE, null)
    }
}

/** One result: the place, how far and how long to reach, its hours' state and how sure Atlas is of them. */
data class ResultHit(
    val record: PlaceRecord,
    val metres: Double,
    val minutes: Int,
    val mode: TravelMode,
    /** Its opening state now, from its own hours, its brand's or its kind's around it ([HoursBook]), null when none is known. */
    val open: OpenStatus?,
    /** False when its hours are its brand's branches' or its kind's around it, not its own ([hoursFrom] says which). */
    val hoursConfirmed: Boolean,
    /** True when its hours were mapped or checked too long ago to be sure of ([Freshness]). */
    val hoursStale: Boolean,
    /** The deepest kind of thing it is: its symbol stands beside the result (a pizza for a pizzeria). */
    val node: CategoryNode?,
    val score: Double,
    /** When its hours were last checked (else when it was last edited), null when never: the year a stale row shows. */
    val hoursChecked: LocalDate? = null,
    /** What its row says of it besides the time to reach it, by its kind's purpose ([Purpose.tells]). */
    val tells: List<Tell> = emptyList(),
    /** How sure it is to serve the intent chosen on its kind (swimming, on lakes); null when none is chosen. */
    val sure: Sure? = null,
    /** The official bathing water at it, with its quality and any advisory in force. */
    val site: BathingSite? = null,
    /** Its hours as read: its own, or its brand's branches' when [hoursConfirmed] is false. */
    val hours: OpeningHours? = null,
    /** For a typed query's result, per "near" of it, the nearest thing it is near ("🚲 60 m · 🌲 30 m"). */
    val near: List<NearMatch> = emptyList(),
    /** For a typed query's result, the keys of what it wants that the place's tags do not say ("toilets"): one tap answers them. */
    val unknown: List<String> = emptyList(),
    /** Where its [hours] come from; null when none are known. */
    val hoursFrom: HoursFrom? = null,
    /**
     * For a closest match ([CategoryResults.closest]), what it misses of what was asked and how far off ("opens in
     * 20 min", "300 m beyond the circle"); empty for a place just as asked.
     */
    val misses: List<Miss> = emptyList(),
)

/**
 * A row of results: a place, or places on one deck: a brand's branches under its logo ("5 near"), or in a view of
 * several kinds a kind's places that have no name of their own ("Playground", 2 near).
 */
sealed class ResultItem {
    abstract val first: ResultHit

    /** Every place it stands for: the one, or the deck's. */
    abstract val hits: List<ResultHit>

    data class One(val hit: ResultHit) : ResultItem() {
        override val first get() = hit
        override val hits get() = listOf(hit)
    }

    /** Places on one deck, nearest first. */
    sealed class Deck : ResultItem() {
        override val first get() = hits.first()
    }

    /** A brand's branches, nearest first. */
    data class Bundle(val brand: String, override val hits: List<ResultHit>) : Deck() {
        val name: String get() = Brands.byId(brand)?.name ?: first.record.name() ?: brand
        val logo: String? get() = Brands.byId(brand)?.logoSlug?.let { "logo-$it" }
    }

    /**
     * A kind's places with no name of their own, nearest first, in a view of several kinds ([Browse.bundle]): each would
     * be a row titled by the kind alike (two "Playground" rows among Fun's places).
     */
    data class Unnamed(val kind: CategoryNode, override val hits: List<ResultHit>) : Deck()
}

/** The results that serve an intent as surely as [sure] (lakes one may swim in), in the results' order. */
data class Part(val sure: Sure, val hits: List<ResultHit>, val items: List<ResultItem>)

/**
 * A category's results: the places the facets keep, ranked and with brands bundled, the facets, the narrower kinds;
 * the kind's [purpose] and the [order] they are in; with an intent chosen on the kind ([aim]), the results parted by
 * how surely each serves it ([parts]).
 */
data class CategoryResults(
    val node: CategoryNode,
    val items: List<ResultItem>,
    val hits: List<ResultHit>,
    val facets: FacetSet,
    /** The node's children with how many of the kept places each holds, the most first; none for a preset with none. */
    val subcategories: List<Pair<CategoryNode, Int>>,
    /** How many of the places the other filters keep are open at each hour from [hoursFrom] on ([Browse.openByHour]). */
    val byHour: List<Int> = emptyList(),
    val hoursFrom: LocalDateTime? = null,
    val purpose: Purpose = Purposes.LIST,
    val order: Order = Order.FIT,
    val aim: Intent? = null,
    val parts: List<Part> = emptyList(),
    /**
     * True when no place is just as asked and [hits] are the closest matches instead (the owner, 4 November: "should
     * always show closest match if you drop or lower a requirement"): those missing the fewest of the filters chosen
     * (and of the circle asked for) first, each with its [ResultHit.misses].
     */
    val closest: Boolean = false,
) {
    /** The part of those surely serving the intent chosen, when one is chosen and some do. */
    val sure: Part? get() = parts.firstOrNull { it.sure == Sure.YES }
}

/**
 * The one search's ways into places: the grid's tiles ([pulses]) and a category's results ([results]), both over
 * places already read around the searcher, so they answer offline and at once.
 */
object Browse {
    /**
     * True when [node] holds the place [r]: a category its own places (the first rule that takes them), a shelf its
     * categories' and its guests' ([CategoryNode.guests], a climbing hall in Fun), the rest by tags.
     */
    fun holds(node: CategoryNode, r: PlaceRecord): Boolean = when (node.kind) {
        NodeKind.SHELF -> if (node.category.family == CategoryTree.OSM) r.category == null && node.filter.matches(r.tags)
            else r.category != null && Categories.byId(r.category)?.shelf?.family == node.category.family || node.guests.any { holds(it, r) }
        NodeKind.CATEGORY -> r.category == node.category.id
        NodeKind.KEY -> r.category == null && node.filter.matches(r.tags)
        NodeKind.PRESET -> node.filter.matches(r.tags) && (if (node.category.family == CategoryTree.OSM) r.category == null else r.category == node.category.id)
    }

    /** The tiles of [nodes] over [pool], the places read around [from]. */
    fun pulses(nodes: List<CategoryNode>, pool: List<PlaceRecord>, from: LatLon, moment: Moment, hours: HoursBook = HoursBook(pool)): List<CategoryPulse> {
        val now = moment.now.toLocalDateTime()
        val ctx = OpeningContext(now, hours)
        return nodes.map { node -> pulse(node, pool.filter { holds(node, it) }, from, moment, ctx) }
    }

    /**
     * The tile of what [intent] lists over [pool], the places read around [from]: its own places and not its kind's
     * ([node] stands for them; parks are also what a walk lists, but pocket gardens are not). Out of doors, being open
     * says nothing of a park or a beach: the nearest, by name where any near has one, and the minutes to it say what
     * there is to say (the owner, 9 November: "What does a walk suggest?", when it said "7 open").
     */
    fun pulse(intent: Intent, node: CategoryNode, pool: List<PlaceRecord>, from: LatLon, moment: Moment, hours: HoursBook = HoursBook(pool)): CategoryPulse {
        val places = pool.filter(intent::takes)
        if (places.isEmpty()) return CategoryPulse.none(node)
        if (!intent.outdoors) return pulse(node, places, from, moment, OpeningContext(moment.now.toLocalDateTime(), hours))
        // the nearest named of those it goes round in (a walk's greens), else of all it takes; where none has a name, the nearest
        val named = places.filter { !it.name(moment.lang).isNullOrBlank() }
        val (nearest, metres) = named.filter(intent::goesRound).ifEmpty { named }.ifEmpty { places }.map { it to it.distanceTo(from) }.minBy { it.second }
        val minutes = Travel.minutes(metres, moment.mode)
        return CategoryPulse(node, places.size, 0, 0, nearest, metres, minutes, TellKind.NEAREST, minutes.toString())
    }

    private class OpeningContext(val now: LocalDateTime, val hours: HoursBook) {
        private val cache = HashMap<PlaceRecord, Pair<OpenStatus?, Boolean>>()

        /** Its state now and whether its hours are its own; usually open places are open without hours. */
        fun status(r: PlaceRecord): Pair<OpenStatus?, Boolean> = cache.getOrPut(r) {
            val known = hours.of(r)
            when {
                known != null -> known.hours.status(now) to known.confirmed
                OfflineEvaluator.usuallyOpen(r) -> OpenStatus(true, null, null, false) to true
                else -> null to true
            }
        }
    }

    private fun pulse(node: CategoryNode, places: List<PlaceRecord>, from: LatLon, moment: Moment, ctx: OpeningContext): CategoryPulse {
        if (places.isEmpty()) return CategoryPulse.none(node)
        val byDistance = places.map { it to it.distanceTo(from) }.sortedBy { it.second }
        val statuses = places.associateWith { ctx.status(it) }
        val open = places.filter { statuses[it]?.first?.isOpen == true }
        val unconfirmed = open.count { statuses[it]?.second == false }
        // the nearest open one where being open is the point (a pharmacy), else the nearest (a memorial)
        val nearestOpen = byDistance.firstOrNull { statuses[it.first]?.first?.isOpen == true }.takeIf { Ranking.weights(node.category.id).open >= 1.5 }
        val (nearest, metres) = nearestOpen ?: byDistance.first()
        val minutes = Travel.minutes(metres, moment.mode)
        val withHours = places.count { statuses[it]?.first != null }
        val category = node.category.id
        // each change with whether the hours saying it are the place's own; a time is the own hours' where any says it
        fun changes(of: List<PlaceRecord>, kind: ChangeKind) = of.mapNotNull { r ->
            statuses[r]?.let { (s, own) -> s?.takeIf { it.nextChangeKind == kind }?.changesAt?.let { it to own } }
        }
        fun pick(of: List<Pair<LocalDateTime, Boolean>>, last: Boolean): Pair<LocalDateTime, Boolean>? {
            fun best(l: List<Pair<LocalDateTime, Boolean>>) = if (last) l.maxByOrNull { it.first } else l.minByOrNull { it.first }
            return best(of.filter { it.second })?.let { it.first to false } ?: best(of)?.let { it.first to true }
        }
        val latest = pick(changes(open, ChangeKind.CLOSES), last = true)
        val endless = open.count { r -> statuses[r]?.first?.let { it.nextChangeKind != ChangeKind.CLOSES || it.changesAt == null } == true }
        val opens = if (open.isNotEmpty()) null else pick(changes(places, ChangeKind.OPENS), last = false)

        fun tell(kind: TellKind, value: String?) = CategoryPulse(
            node, places.size, open.size, unconfirmed, nearest, metres, minutes, kind, value, latest?.first, endless, opens?.first,
            untilGuessed = latest?.second == true, opensGuessed = opens?.second == true,
        )

        // the category's own tell first: what is worth knowing about it in particular
        if (category == "charging") fastestKw(places)?.let { return tell(TellKind.FASTEST_KW, it) }
        if (category == "toilets" || category == "parking" || category == "showers") {
            val free = places.count { Facets.keeps(it, FacetSelection.NONE.toggle(Facets.PRICE, "free"), FacetContext(ctx.now)) }
            if (free > 0 && places.size >= CategoryPulse.DENSE) return tell(TellKind.FREE_COUNT, free.toString())
        }
        // none open: when the first opens
        if (withHours > 0 && opens != null) return tell(TellKind.OPENS_AT, clock(opens.first.toLocalTime()))
        val dense = places.size >= CategoryPulse.DENSE
        if (dense && open.isNotEmpty() && (moment.part == Daypart.EVENING || moment.part == Daypart.NIGHT) && Relevance.atNight(category)) {
            // at night the question is how long
            if (latest != null) return tell(TellKind.OPEN_TILL, clock(latest.first.toLocalTime()))
        }
        return when {
            dense && withHours * 3 >= places.size -> tell(TellKind.OPEN_COUNT, open.size.toString())
            dense -> tell(TellKind.COUNT, places.size.toString())
            else -> tell(TellKind.NEAREST, minutes.toString())
        }
    }

    /** The highest charging power mapped on [places], in kW ("150"), or null. */
    private fun fastestKw(places: List<PlaceRecord>): String? = places.flatMap { r ->
        r.tags.filterKeys { it.endsWith(":output") && (it.startsWith("socket:") || it.startsWith("charging_station")) }.values
    }.mapNotNull { v ->
        val n = KW.find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return@mapNotNull null
        // "22 kW", "50kW" or a bare number in kW; "11000 W" in watts
        if (!v.contains("kw", ignoreCase = true) && v.contains('W') && n >= 1000) n / 1000 else n
    }.maxOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, it) }

    private val KW = Regex("""(\d+(?:[.,]\d+)?)""")

    private fun clock(t: LocalTime): String = "%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute)

    // ---------------------------------------------------------------------------------------------------- results

    /**
     * The results of [node] over [pool] (read around [from]) with [selection] chosen: ranked the way the category is
     * best ranked ([Ranking]), a brand's branches bundled unless the brand is what was searched ([brandSearched]),
     * and the facets of the places with those of [elsewhere], the same kind beyond the search's area. With [filter]
     * (an intent's: swimming takes beaches, bathing places and lakes one may swim in) the places it takes are the
     * results, [node] standing for their kind in the ranking. In [order] (the kind's own when null); with [aim], an
     * intent chosen on the kind (swimming, on lakes), they are parted by how surely each serves it, places of [pool]
     * that serve it outright (a bathing place on a lake's shore) telling for the place they lie in or at. [sites], the
     * official bathing waters around, tell for the places they lie at, and with swimming chosen the ones at no place
     * found are results of their own. With [circle] (a circle asked for, in metres) only the places within it are
     * results. When no place is as chosen, the places of [pool] that come closest are the results instead
     * ([CategoryResults.closest]): those missing the fewest of the facets chosen and of the circle, then the least far
     * off, in [order] between equals. [with], kinds shown together with [node] (a cash machine's banks), are results
     * too, each place with its own kind's pin, ranked as [node]'s; its row of kinds stays its own. With [intent], the
     * places it takes are the results ([Intent.takes]: its filter and the room it asks for), as with [filter].
     */
    fun results(
        node: CategoryNode,
        pool: List<PlaceRecord>,
        from: LatLon,
        moment: Moment,
        selection: FacetSelection = FacetSelection.NONE,
        elsewhere: List<PlaceRecord> = emptyList(),
        hours: HoursBook = HoursBook(pool + elsewhere),
        brandSearched: String? = null,
        at: LocalDateTime? = null,
        filter: TagFilter? = null,
        order: Order? = null,
        aim: Intent? = null,
        sites: List<BathingSite> = emptyList(),
        sureOf: ((PlaceRecord) -> Sure?)? = null,
        circle: Double? = null,
        with: List<CategoryNode> = emptyList(),
        intent: Intent? = null,
    ): CategoryResults {
        val now = moment.now.toLocalDateTime()
        val takes: (PlaceRecord) -> Boolean = when {
            intent != null -> intent::takes
            filter != null -> { r -> filter.matches(r.tags) }
            else -> { r -> holds(node, r) || with.any { holds(it, r) } }
        }
        val all = pool.filter(takes)
        val places = if (circle == null) all else all.filter { it.distanceTo(from) <= circle }
        val far = elsewhere.filter(takes)
        val fctx = FacetContext(now, moment.lang, hours, at = at)
        val facets = Facets.of(places, selection, fctx, far)
        val ctx = OpeningContext(now, hours)
        val today = now.toLocalDate()
        val purpose = Purposes.of(node)
        val spots = aim?.let { a -> pool.filter { a.filter.matches(it.tags) && !takes(it) } }.orEmpty()
        val atSite = HashMap<PlaceRecord, BathingSite>()
        if (sites.isNotEmpty()) for (r in facets.kept) siteAt(r, sites)?.let { atSite[r] = it }
        // with swimming chosen, the bathing waters at no place found stand for themselves (where the facets keep them)
        val own = if (aim?.id != SWIM) emptyList() else {
            val taken = atSite.values.mapTo(HashSet()) { it.id }
            sites.filter { it.id !in taken && fits(node, it.kind) }.map { siteRecord(it) }.filter { Facets.keeps(it, selection, fctx) }
                .onEach { r -> atSite[r] = sites.first { it.id == r.place.extra[SITE_KEY] } }
        }
        // none as chosen: the closest, each with what it misses
        val closest = facets.kept.isEmpty() && own.isEmpty() && all.isNotEmpty()
        val misses = java.util.IdentityHashMap<PlaceRecord, List<Miss>>()
        if (closest) {
            val of = Facets.misses(all, selection, fctx)
            for ((i, r) in all.withIndex()) {
                val beyond = circle?.let { c -> r.distanceTo(from).takeIf { it > c }?.let { Miss.Beyond(it - c, c) } }
                misses[r] = listOfNotNull(beyond) + of[i]
            }
        }
        val ranked = (if (closest) all else facets.kept + own).map { r ->
            val metres = r.distanceTo(from)
            val (status, confirmed) = ctx.status(r)
            val edited = editedOf(r)
            val checked = Freshness.of(r.tags, "opening_hours", today, edited)
            val stale = r.tags["opening_hours"] != null && checked.stale
            val deepest = CategoryTree.of(r.tags, PlaceRecord.geometryOf(r.place))
            val minutes = Travel.minutes(metres, moment.mode)
            val site = atSite[r]
            val sure = aim?.let { a -> site?.takeIf { a.id == SWIM }?.let { if (it.swimmable) Sure.YES else Sure.NO } ?: certainty(a, r, spots) } ?: sureOf?.invoke(r)
            // what the filters chosen already say of every result goes unsaid ("free" with free chosen)
            val tells = Tells.of(purpose.tells, r.tags, moment.lang, now, edited, r.place.extra, sure, site, r.place.bbox).filterNot { t -> said(t, selection) }
            val known = hours.of(r)
            ResultHit(
                r, metres, minutes, moment.mode, status, confirmed, stale, deepest ?: SITE_NODE, 0.0, checked.date, tells, sure, site, known?.hours,
                hoursFrom = known?.from, misses = misses[r].orEmpty(),
            )
        }.map { it.copy(score = Ranking.score(node, it, moment)) }.sortedByDescending { it.score }
        val chosen = order ?: purpose.order
        // as asked: the pool's own order (a query's "oldest first"), the ranking only between equals
        val ordered = if (chosen == Order.ASKED) {
            val at = java.util.IdentityHashMap<PlaceRecord, Int>().apply { pool.forEachIndexed { i, r -> put(r, i) } }
            ranked.sortedBy { at[it.record] ?: Int.MAX_VALUE }
        } else {
            ranked.inOrder(chosen, now)
        }
        val hits = if (closest) Closest.rank(ordered) { it.misses } else ordered
        val mixed = mixed(node, with)
        val parts = if (aim == null && sureOf == null) emptyList() else Sure.entries.mapNotNull { s ->
            hits.filter { it.sure == s }.takeIf { it.isNotEmpty() }?.let { Part(s, it, bundle(it, brandSearched, mixed)) }
        }
        // the day of the time asked about (or of now) from its morning, the small hours its night before
        val from = (at ?: now).minusHours(CURVE_FROM.toLong()).toLocalDate().atTime(CURVE_FROM, 0)
        return CategoryResults(
            node, if (parts.isEmpty()) bundle(hits, brandSearched, mixed) else parts.flatMap { it.items }, if (parts.isEmpty()) hits else parts.flatMap { it.hits },
            facets, if (filter != null) kindsAmong(node, facets.kept) else subcategories(node, if (with.isEmpty()) facets.kept else facets.kept.filter { holds(node, it) }),
            byHour = openByHour(facets.overHours, from, hours), hoursFrom = from,
            purpose = purpose, order = chosen, aim = aim, parts = parts, closest = closest,
        )
    }

    /**
     * The places a typed query found ([hits], in its order) as a kind's results (decision 12: a query opens in the
     * sheet as a kind does): ranked as the narrowest kind holding them all is ([kindOf]; [within] narrows them to one
     * of the kinds among them), or as asked where the query asked for an order ([Order.ASKED]); every tag of them a
     * filter with its count; the kinds among them a tap narrower; parted by how surely each has what the query
     * wants that tags may not say ("with toilets"), with what each is near and what it leaves unsaid.
     */
    fun asked(
        hits: List<Hit>,
        from: LatLon,
        moment: Moment,
        selection: FacetSelection = FacetSelection.NONE,
        hours: HoursBook = HoursBook(hits.map { it.record }),
        at: LocalDateTime? = null,
        order: Order? = null,
        brandSearched: String? = null,
        within: CategoryNode? = null,
    ): CategoryResults {
        val kept = if (within == null) hits else hits.filter { holds(within, it.record) }
        val records = kept.map { it.record }
        val node = kindOf(records)?.takeIf { within == null || CategoryTree.isUnder(it, within) } ?: within ?: EVERYTHING
        val of = java.util.IdentityHashMap<PlaceRecord, Hit>().apply { for (h in kept) put(h.record, h) }
        val wants = kept.any { it.sure != Sure.YES }
        val r = results(
            node, records, from, moment, selection, hours = hours, brandSearched = brandSearched, at = at, filter = TagFilter.All,
            order = order, sureOf = if (wants) { rec -> of[rec]?.sure } else null,
        )
        fun more(h: ResultHit) = of[h.record]?.let { hit -> h.copy(near = hit.near, unknown = hit.unknown, misses = hit.misses + h.misses) } ?: h
        // what the query found closest to it (nothing was as asked) stays closest first, the kind's order between equals
        val closest = r.closest || kept.any { it.misses.isNotEmpty() }
        fun rank(hits: List<ResultHit>) = if (closest) Closest.rank(hits.map(::more)) { it.misses } else hits.map(::more)
        val mixed = mixed(node, emptyList())
        val parts = r.parts.map { p -> rank(p.hits).let { hs -> p.copy(hits = hs, items = bundle(hs, brandSearched, mixed)) } }
        val all = if (parts.isEmpty()) rank(r.hits) else parts.flatMap { it.hits }
        return r.copy(items = if (parts.isEmpty()) bundle(all, brandSearched, mixed) else parts.flatMap { it.items }, hits = all, parts = parts, closest = closest)
    }

    /**
     * The narrowest kind that holds every one of [records] ([holds]): a pizzeria's for pizzerias, a restaurant's for
     * them and grills; null for none. It is looked for up the path of the commonest kind among them.
     */
    fun kindOf(records: List<PlaceRecord>): CategoryNode? {
        if (records.isEmpty()) return null
        val commonest = records.groupingBy { CategoryTree.of(it.tags, PlaceRecord.geometryOf(it.place))?.id }.eachCount()
            .entries.filter { it.key != null }.maxByOrNull { it.value }?.key ?: return null
        return CategoryTree.pathOf(commonest).asReversed().firstOrNull { n -> records.all { holds(n, it) } }
    }

    /**
     * The kinds among [places] one step under [node], with how many each holds, the most first (pizzerias and grills
     * among restaurants); where [node] holds them only as a stand-in, their categories.
     */
    fun kindsAmong(node: CategoryNode, places: List<PlaceRecord>): List<Pair<CategoryNode, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (r in places) {
            val path = CategoryTree.of(r.tags, PlaceRecord.geometryOf(r.place))?.let { CategoryTree.pathOf(it.id) } ?: continue
            val at = path.indexOfFirst { it.id == node.id }
            val kind = if (at >= 0) path.getOrNull(at + 1) else path.getOrNull(1) ?: path.first()
            kind?.let { counts[it.id] = (counts[it.id] ?: 0) + 1 }
        }
        return counts.mapNotNull { (id, n) -> CategoryTree.byId(id)?.let { it to n } }.sortedByDescending { it.second }
    }

    /** The kind standing for [records] in a ranking and a view: the narrowest holding them all, else [EVERYTHING]. */
    fun kindFor(records: List<PlaceRecord>): CategoryNode = kindOf(records) ?: EVERYTHING

    /** The node standing for places that share no kind: the shelf of everything on the map, a plain list. */
    val EVERYTHING: CategoryNode by lazy { CategoryTree.shelf(CategoryTree.OSM) ?: CategoryTree.shelves().last() }

    /** The official bathing water at [r]: in or at its outline, or near it where it is drawn as a point. */
    fun siteAt(r: PlaceRecord, sites: List<BathingSite>): BathingSite? {
        val box = PlaceRecord.boxOf(r.place)
        val reach = if (r.place.bbox == null) SPOT_NEAR_POINT_M else SPOT_AT_M
        val dLat = reach / METRES_PER_DEGREE
        val dLon = reach / (METRES_PER_DEGREE * kotlin.math.cos(Math.toRadians(r.location.lat)).coerceAtLeast(0.05))
        return sites.filter { s ->
            val p = s.location
            p.lat in (box.south - dLat)..(box.north + dLat) && p.lon in (box.west - dLon)..(box.east + dLon)
        }.minByOrNull { r.distanceTo(it.location) }
    }

    /** A bathing water no place found lies at, as a result of its own: a swim spot by the register's name. */
    fun siteRecord(s: BathingSite): PlaceRecord =
        PlaceRecord.of(Place(null, s.location, mapOf("leisure" to "bathing_place", "name" to s.name), s.area, s.source, mapOf(SITE_KEY to s.id)))

    /** True when a bathing water of [kind] is the kind of water [node] holds: a lake's for lakes, any for beaches. */
    private fun fits(node: CategoryNode, kind: WaterKind): Boolean {
        val preset = node.preset?.id ?: return true
        return when {
            preset.startsWith("natural/water/lake") || preset.startsWith("natural/water/pond") || preset.startsWith("natural/water/reservoir") -> kind == WaterKind.LAKE || kind == WaterKind.OTHER
            preset.startsWith("natural/water/river") || preset.startsWith("waterway") -> kind == WaterKind.RIVER || kind == WaterKind.OTHER
            else -> true
        }
    }

    /** The extra key of a result made from a bathing water: its register id. */
    const val SITE_KEY = "bathing_water"
    private const val SWIM = "swim"
    private val SITE_NODE: CategoryNode? by lazy { CategoryTree.preset("leisure/bathing_place") }

    /** When [r] was last edited, as its source says, else null. */
    fun editedOf(r: PlaceRecord): LocalDate? = r.place.extra["timestamp"]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** True when the values chosen in [selection] already say [t] of every result: its tag's facet, or the price's. */
    private fun said(t: Tell, selection: FacetSelection): Boolean =
        t.keys.any { it in selection.chosen } || (t.key == TellKey.FEE && Facets.PRICE in selection.chosen)

    /**
     * How surely [r] serves [aim]: by its own tags, else, for a place one goes into (a lake), by the places among
     * [spots] that serve it outright and lie in or at it (a bathing place on its shore: yes) or near it (a beach or
     * a lifeguard on the shore: likely).
     */
    fun certainty(aim: Intent, r: PlaceRecord, spots: List<PlaceRecord>): Sure {
        val own = Certainty.of(aim, r.tags)
        if (own == Sure.YES || own == Sure.NO || spots.isEmpty()) return own
        val box = PlaceRecord.boxOf(r.place)
        val point = r.place.bbox == null
        fun within(s: PlaceRecord, m: Double): Boolean {
            val dLat = m / METRES_PER_DEGREE
            val dLon = m / (METRES_PER_DEGREE * kotlin.math.cos(Math.toRadians(r.location.lat)).coerceAtLeast(0.05))
            val p = s.location
            return p.lat in (box.south - dLat)..(box.north + dLat) && p.lon in (box.west - dLon)..(box.east + dLon)
        }
        val inOrAt = if (point) SPOT_NEAR_POINT_M else SPOT_AT_M
        if (spots.any { Certainty.of(aim, it.tags) == Sure.YES && Certainty.spot(aim, it.tags) && within(it, inOrAt) }) return Sure.YES
        if (spots.any { Certainty.hint(aim, it.tags) && within(it, inOrAt * 2) }) return Sure.LIKELY
        return own
    }

    private const val METRES_PER_DEGREE = 111_320.0

    /** How far around a lake drawn as an area a bathing place counts as its, and around one drawn only as a point. */
    private const val SPOT_AT_M = 60.0
    private const val SPOT_NEAR_POINT_M = 250.0

    /** The hour a day of [openByHour] starts at: the morning, so a night stays with the evening before it. */
    const val CURVE_FROM = 6

    /**
     * How many of [places] are open at each of the 24 hours from [from], by their hours or their brand's ([hours]); one
     * without hours that is usually open (a park) counts at every hour. The filters' time picker draws it, so the
     * time asked about shows how much is open then.
     */
    fun openByHour(places: List<PlaceRecord>, from: LocalDateTime, hours: HoursBook): List<Int> {
        // places share their hours (a brand's, a common "Mo-Su 09:00-18:00"): each is read once per hour
        val shared = java.util.IdentityHashMap<OpeningHours, Int>()
        var always = 0
        for (r in places) {
            val known = hours.of(r)
            when {
                known != null -> shared[known.hours] = (shared[known.hours] ?: 0) + 1
                OfflineEvaluator.usuallyOpen(r) -> always++
            }
        }
        return List(24) { h ->
            val t = from.plusHours(h.toLong())
            always + shared.entries.sumOf { (oh, n) -> if (oh.isOpen(t) == true) n else 0 }
        }
    }

    /**
     * [hits] with each brand's branches together where a brand has two or more, at the place of its best-ranked one; in
     * a view of several kinds, of [mixed], each kind's places with no name of their own too, where two or more of one
     * kind are among them (the owner, 6 November, of two playgrounds among Fun's places: "If there are multiple like 2
     * playgrounds make that visible at first glance. A stack?", then "Yes, build decks"). Each would be a row titled by
     * the kind alike; the view's own kind's stay rows, each a choice told apart by where it is.
     */
    fun bundle(hits: List<ResultHit>, brandSearched: String? = null, mixed: CategoryNode? = null): List<ResultItem> {
        val byBrand = hits.filter { it.record.brand != null && it.record.brand != brandSearched }.groupBy { it.record.brand!! }.filterValues { it.size >= 2 }
        val byKind = if (mixed == null) emptyMap() else {
            hits.filter { h -> h.record.name() == null && h.node?.let { dealtBy(it).id != mixed.id } == true }.groupBy { dealtBy(it.node!!).id }.filterValues { it.size >= 2 }
        }
        val out = ArrayList<ResultItem>()
        val done = HashSet<String>()
        for (h in hits) {
            val brand = h.record.brand
            val branches = brand?.let { byBrand[it] }
            val kind = h.node?.takeIf { branches == null && h.record.name() == null }?.let(::dealtBy)
            val alike = kind?.let { byKind[it.id] }
            when {
                branches != null -> if (done.add("brand:$brand")) out += ResultItem.Bundle(brand, branches.sortedBy { it.metres })
                kind != null && alike != null -> if (done.add(kind.id)) out += ResultItem.Unnamed(kind, alike.sortedBy { it.metres })
                else -> out += ResultItem.One(h)
            }
        }
        return out
    }

    /**
     * The kind a place with no name of its own is dealt by: its preset's broadest, as one says what it is: the toilets'
     * for flush toilets and pit latrines alike, the vending machines' for a coin press.
     */
    private fun dealtBy(node: CategoryNode): CategoryNode {
        var n = node
        while (true) n = n.parent?.takeIf { it.kind == NodeKind.PRESET } ?: return n
    }

    /**
     * [node] where its view is of several kinds, whose places with no name of their own [bundle] puts on decks: a shelf
     * (Fun, the shops, the services' benches and bins), or a kind shown [with] its kin (the cash machines with the banks);
     * null for a kind's own view, however many narrower kinds it holds: one taps the playgrounds to choose one of them.
     */
    private fun mixed(node: CategoryNode, with: List<CategoryNode>): CategoryNode? =
        node.takeIf { with.isNotEmpty() || it.kind == NodeKind.SHELF }

    /**
     * [node]'s children with how many of [places] each holds: what people come to it for most first
     * ([CategoryNode.standing]: the supermarkets, then the convenience stores, among the groceries), then the most, then
     * the specialists it names last (the frozen food shops, the benches among the services); those with none near after
     * all of them, in the same order. Where one
     * child holds them all, its own children in its place, and so on down: a chip that narrows nothing is a step for
     * nothing (the owner, 6 November, of the groceries' one "Groceries" chip: "shouldnt all subcategories be there
     * already instead of that extra step?"). One holding some of them narrows, and stays (3 coffee shops among 31 cafes).
     * A shelf's guests, the kinds of other shelves it holds too ([CategoryNode.guests]), follow its own kinds, the most
     * first, and those with none come last of all.
     */
    fun subcategories(node: CategoryNode, places: List<PlaceRecord>): List<Pair<CategoryNode, Int>> {
        val guests = node.guests.map { g -> g to places.count { holds(g, it) } }.sortedByDescending { it.second }
        if (guests.isEmpty()) return ownKinds(node, places)
        val own = ownKinds(node, places.filter { r -> guests.none { holds(it.first, r) } })
        val (some, none) = (own + guests).partition { it.second > 0 }
        return some + none
    }

    /** [subcategories] among [node]'s own children: one holding all of [places] gives way to its own, and so on down. */
    private fun ownKinds(node: CategoryNode, places: List<PlaceRecord>): List<Pair<CategoryNode, Int>> {
        val counted = node.children.map { c -> c to places.count { holds(c, it) } }
            .sortedWith(compareBy<Pair<CategoryNode, Int>> { it.second == 0 }.thenBy { node.standing(it.first) }.thenByDescending { it.second })
        val all = counted.filter { it.second > 0 }.singleOrNull()?.takeIf { it.second == places.size }?.first ?: return counted
        return ownKinds(all, places)
    }
}

/**
 * How a category's results are ranked (the user, 3 November: "Ranking of search results different for every
 * category: open, unconfirmed derived opening hours, rating, distance, weekday/events, time of day, weather,
 * preferences, transport mode, recent/frequent queries"). Each kind of category weighs differently: a toilet or a
 * defibrillator is wanted near above all, a restaurant open and liked, a lake or a park worth a longer way. The
 * time a place takes to reach is by the searcher's way of getting about; open now counts most when its hours are its
 * own, less when they are its brand's; a kind of thing that suits the moment (coffee in the morning) rises.
 */
object Ranking {
    /** How much each part counts for a kind of category, and over how many minutes nearness halves. */
    data class Weights(val open: Double, val near: Double, val rating: Double, val halfMinutes: Double)

    private val URGENT = Weights(open = 1.5, near = 3.0, rating = 0.2, halfMinutes = 6.0)
    private val FOOD = Weights(open = 2.0, near = 2.0, rating = 1.0, halfMinutes = 12.0)
    private val ERRAND = Weights(open = 2.0, near = 2.5, rating = 0.4, halfMinutes = 10.0)
    private val OUTING = Weights(open = 0.8, near = 1.5, rating = 0.8, halfMinutes = 30.0)
    private val STAY = Weights(open = 0.5, near = 1.5, rating = 1.2, halfMinutes = 20.0)

    fun weights(category: String): Weights = when (category) {
        "toilets", "defibrillator", "drinking_water", "money", "transit", "parking", "fuel", "charging", "health", "rentals", "showers",
        "street_furniture", "bike_parking" -> URGENT
        "restaurants", "drinks" -> FOOD
        "food_shops", "shopping", "post", "laundry", "car_care", "recycling", "vending", "lost_property", "support" -> ERRAND
        "hotels", "camping" -> STAY
        else -> OUTING
    }

    fun score(node: CategoryNode, h: ResultHit, m: Moment): Double {
        val w = weights(h.record.category ?: node.category.id)
        val open = when {
            h.open == null -> 0.5
            !h.open.isOpen -> if (h.open.unknown) 0.5 else 0.0
            // closing within half an hour is nearly closed
            h.open.nextChangeKind == ChangeKind.CLOSES && h.open.changesAt?.let { java.time.Duration.between(m.now.toLocalDateTime(), it).toMinutes() < 30 } == true -> 0.5
            !h.hoursConfirmed -> 0.8
            else -> 1.0
        } * (if (h.hoursStale) 0.9 else 1.0)
        val near = exp(-h.minutes / w.halfMinutes)
        val rating = h.record.place.extra["rating"]?.toDoubleOrNull()?.let { it / 5.0 } ?: 0.5
        // the kind of thing suits the moment: coffee in the morning, a bar at night
        val suits = h.node?.let { n -> Intents.of(n).maxOfOrNull { it.fit(m).first } }?.let { (it - 0.6) * 0.8 } ?: 0.0
        val liked = h.node?.let { n -> CategoryTree.pathOf(n.id).sumOf { (m.picks[it.id] ?: 0).coerceAtMost(5) } * 0.04 } ?: 0.0
        return w.open * open + w.near * near + w.rating * rating + suits + liked
    }
}
