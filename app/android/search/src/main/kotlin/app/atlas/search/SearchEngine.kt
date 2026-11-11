package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.CoordinateParser
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.PlusCode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Kinds
import app.atlas.places.Localise
import app.atlas.places.OpeningHoursContext
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import app.atlas.places.Text
import app.atlas.providers.Geocoder
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference

/** Who is searching, where and when, in which language, and whether the phone may go online. */
data class SearchContext(
    val location: LatLon? = null,
    val viewport: BBox? = null,
    val lang: String = "en",
    val now: ZonedDateTime = ZonedDateTime.now(),
    val online: Boolean = false,
    /** ISO country of the searcher, for brands that trade there. */
    val country: String? = null,
    val limit: Int = 20,
    val hours: OpeningHoursContext = OpeningHoursContext.Default,
    /** The town the searcher is in, when the app knows its bounds ("how many bins in this town"). */
    val town: BBox? = null,
)

/** A search the user made before. */
data class RecentSearch(val text: String, val place: Place? = null)

/** The user's recent searches, kept by the app. */
fun interface RecentSearches {
    /** Recent searches that begin with or contain [text] (all of them, newest first, when it is empty). */
    fun matching(text: String, limit: Int): List<RecentSearch>

    companion object {
        val NONE = RecentSearches { _, _ -> emptyList() }
    }
}

/** Why a place is in the results. */
enum class MatchKind { NAME, ADDRESS, STREET, KIND, BRAND, GOODS, QUERY, NEARBY }

/** One kind of place that sells some goods, with how many there are and the nearest few. */
data class SellerResult(val seller: Seller, val label: String, val symbol: String, val count: Int, val nearest: List<Hit>) {
    val likelihood: Int get() = seller.likelihood
    val query: Query get() = seller.query
}

/** One line of the search results. Higher [score]s come first. */
sealed interface SearchItem {
    val score: Double

    /** Coordinates or a Plus Code typed straight in. */
    data class Coordinate(val location: LatLon, val plusCode: String, override val score: Double) : SearchItem

    /** An OSM element typed by id or URL ("n26973327", "osm.org/way/99205257"). */
    data class Element(val hit: Hit, override val score: Double) : SearchItem

    /** A place: its hit (distance, open state, symbol), why it matched, and how to show it. */
    data class PlaceResult(
        val hit: Hit,
        val match: MatchKind,
        val title: String,
        val subtitle: String?,
        override val score: Double,
        /** For a street or a stop: how many mapped stretches or platforms share its name (all shown as this one). */
        val parts: Int = 1,
    ) : SearchItem

    /** A kind of place ("Every pharmacy on the map · all 23"): its query, symbol and how many there are. */
    data class Kind(val label: String, val symbol: String, val query: Query, val count: Int, override val score: Double) : SearchItem

    /** Something to buy ("picture hooks · 5 kinds"): the kinds of place that sell it, likeliest first. */
    data class GoodsResult(val goods: Goods, val label: String, val symbol: String, val sellers: List<SellerResult>, override val score: Double) : SearchItem {
        /** All its sellers as one query, the likelier kinds ranked higher. */
        val query: Query get() = goods.query
    }

    /**
     * A chain ("All 23 Albert Heijn"): its branches as a query, and how many there are; [named] where the words are its
     * very name ("albert heijn", "hema", "appie"), the chain they clearly ask for, which then leads the answer.
     */
    data class BrandResult(val brand: Brand, val symbol: String, val query: Query, val count: Int, override val score: Double, val named: Boolean = false) : SearchItem

    /**
     * The text read as a query ("café 200 m from a bike dock"): its chips and its results, per step of a plan. When
     * nothing matched as asked, [loosened] says what was let go of to find the results there are. [view] is the kind's
     * own view with the query's filters chosen, when that is all the query asks ("vegan restaurant open now").
     */
    data class Structured(
        val parsed: ParsedQuery,
        val results: List<QueryResult>,
        val online: Boolean,
        override val score: Double,
        val loosened: List<Loosened> = emptyList(),
        val view: KindView? = null,
    ) : SearchItem

    /** The answer to "how many …": the count, of which kinds, and in which area ([area]: its name, or null around the searcher). */
    data class Count(val parsed: ParsedQuery, val answer: CountAnswer, val area: String?, override val score: Double) : SearchItem

    /** The answer to "… opening times": the places' hours side by side. */
    data class Hours(val parsed: ParsedQuery, val answer: HoursAnswer, override val score: Double) : SearchItem

    /** The answer to "how tall is …", "does … have toilets": what the places found say of [key], those that say it first. */
    data class Fact(val parsed: ParsedQuery, val key: String, val answers: List<FactAnswer>, override val score: Double) : SearchItem

    /** A way asked for ("from the station to the Westerkerk"): its ends as found; [from] null for the searcher's place (or a start not found). */
    data class Route(val ask: RouteAsk, val from: RouteEnd?, val to: RouteEnd, override val score: Double, val via: List<RouteEnd> = emptyList()) : SearchItem

    /** A place found by the online geocoder, beyond the downloaded regions. */
    data class Online(val place: Place, val distance: Double?, val symbol: String, override val score: Double) : SearchItem

    data class Recent(val recent: RecentSearch, override val score: Double) : SearchItem
}

/** The search box's answer, best first. */
/** One end of a way asked for: the words, the name found for them, where, and the place when it is one. */
data class RouteEnd(val text: String, val name: String?, val location: LatLon, val place: Place?)

data class SearchResults(
    val text: String,
    val items: List<SearchItem>,
    /** How the text read as a query, when it did. */
    val parsed: ParsedQuery? = null,
    /** An area the query named that no store or geocoder could find. */
    val unresolvedArea: String? = null,
    val usedOnline: Boolean = false,
    val micros: Long = 0,
    /** Narrower searches to offer as chips under the box, for the kind of place or brand found ("Open now", "Step-free"). */
    val refinements: List<Refinement> = emptyList(),
) {
    /** The places among the items, in order. */
    val places: List<SearchItem.PlaceResult> get() = items.filterIsInstance<SearchItem.PlaceResult>()
}

/**
 * The one entry point for the search box. It reads coordinates and Plus Codes, OSM ids and links, kinds of places
 * ("pharm" is the pharmacies, with a count), brands ("All 23 Albert Heijn"), named places and streets (typos
 * forgiven), addresses, and whole queries in natural language or power syntax, all offline over the attached
 * regions; online it adds the geocoder when the regions have little, and Overpass for queries beyond them.
 * A new search cancels the one still running, so it can be called on every keystroke.
 */
class SearchEngine(
    private val places: PlaceSource,
    private val geocoder: Geocoder? = null,
    private val overpass: Overpass? = null,
    private val recents: RecentSearches = RecentSearches.NONE,
) {
    private val evaluator = OfflineEvaluator(places)
    private val running = AtomicReference<Job?>(null)

    /** Searches [text] for [context]; cancels the previous search if it is still running. */
    suspend fun search(text: String, context: SearchContext): SearchResults = coroutineScope {
        val job = async(Dispatchers.IO) { run(text, context) }
        running.getAndSet(job)?.cancel()
        try {
            job.await()
        } finally {
            running.compareAndSet(job, null)
        }
    }

    /**
     * [text] read as a query and answered in full for the sheet that opens it over the map (decision 12): every place
     * it finds up to [SearchContext.limit], as [search] finds them (areas resolved, online where the regions have
     * none, loosened where nothing is as asked), without the field's lines. Null when the text is no query. It runs
     * beside [search], cancelling nothing.
     */
    suspend fun answer(text: String, context: SearchContext): SearchResults? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val areas = Areas(context)
        val parsed = known(QueryParser.parse(text.trim(), context.lang, context.now, context.country), context, areas)
        if (parsed.steps.isEmpty() || parsed.route != null) return@withContext null
        val eval = EvalContext(context.location, context.viewport, context.now, context.lang, context.limit, context.hours, areas = areas)
        structured(text, parsed, context, eval, areas, started, lines = false)
    }

    private suspend fun run(text: String, ctx: SearchContext): SearchResults {
        val started = System.nanoTime()
        val t = text.trim()
        val items = ArrayList<SearchItem>()
        if (t.isEmpty()) {
            recents.matching("", ctx.limit).forEachIndexed { i, r -> items += SearchItem.Recent(r, 1.0 - i * 0.01) }
            return SearchResults(text, items, micros = micros(started))
        }
        val areas = Areas(ctx)
        val eval = EvalContext(ctx.location, ctx.viewport, ctx.now, ctx.lang, ctx.limit, ctx.hours, areas = areas)

        // Coordinates and Plus Codes, with what is there
        CoordinateParser.parse(t, ctx.location)?.let { at ->
            items += SearchItem.Coordinate(at, PlusCode.encode(at), 10.0)
            for ((i, r) in places.nearest(at, 3, 150.0) { it.name() != null }.withIndex()) {
                items += placeResult(hit(r, at, eval), MatchKind.NEARBY, ctx, 5.0 - i * 0.1)
            }
            return SearchResults(text, items, micros = micros(started))
        }

        // OSM ids and links. A link or "node/123" is surely an id; a short "n123" may be a road or a line too ("N201",
        // the night bus "N47"), so the search goes on with the element first.
        var usedOnline = false
        osmRef(t)?.let { id ->
            val local = places.get(id)?.let { hit(it, ctx.location, eval) }
            val r = local ?: if (ctx.online) online(id, ctx) else null
            r?.let { items += SearchItem.Element(it, 9.0) }
            usedOnline = local == null && r != null
            if (!SHORT_REF.matches(t)) return SearchResults(text, items, usedOnline = usedOnline, micros = micros(started))
        }
        currentCoroutineContext().ensureActive()

        // A whole query: "café 200 m from a bike dock", "amenity=toilets wheelchair=yes open now"
        val parsed = known(QueryParser.parse(t, ctx.lang, ctx.now, ctx.country), ctx, areas)
        // A way: its ends as the searcher would find them, the end's other candidates after
        parsed.route?.let { r -> route(text, r, parsed, ctx, started)?.let { return it } }
        if (parsed.isQuery) return structured(text, parsed, ctx, eval, areas, started)

        val words = SearchText.words(t)
        currentCoroutineContext().ensureActive()
        var refineFor: Query? = null

        // Things to buy: "picture hooks" is the hardware stores, then variety stores, department stores…
        goodsOf(parsed)?.let { (g, label) ->
            val sellers = g.sellers.map { s ->
                SellerResult(s, s.name(ctx.lang), s.symbol, count(s.query, eval), nearest(s.query, eval))
            }
            items += SearchItem.GoodsResult(g, label, g.symbol, sellers, 0.96)
            for (s in sellers.filter { it.count > 0 }.take(GOODS_SELLERS)) {
                for (h in s.nearest) {
                    val score = 0.9 * (0.5 + 0.1 * s.likelihood) * (0.3 + 0.7 * distanceFactor(h.distance))
                    items += placeResult(h.copy(why = listOf("goods:${g.id}", s.seller.filter.toText())), MatchKind.GOODS, ctx, score)
                }
            }
        }
        currentCoroutineContext().ensureActive()

        // Kinds of places: "pharm", "apotheek", "Apotheke"; the nearest few, then all of them
        val kindsNamed = kinds(t, ctx.lang)
        for (k in kindsNamed) {
            val count = count(k.query, eval)
            if (count == 0) continue
            items += SearchItem.Kind(k.label, k.symbol, k.query, count, k.score * 0.5)
            if (refineFor == null) refineFor = k.query
            for (h in nearest(k.query, eval)) {
                items += placeResult(h.copy(why = listOf("kind:${k.id}")), MatchKind.KIND, ctx, k.score * 0.95 * (0.3 + 0.7 * distanceFactor(h.distance)))
            }
        }
        currentCoroutineContext().ensureActive()

        // Brands: "albert heijn", typos forgiven, with how many branches there are. The very name of one, not a kind's
        // nor an ordinary word, asks for that chain: it leads, above the places it names, and the search key opens it
        // (the owner, 6 November: "appears when you clearly search for a specific brand")
        if (t.length >= 3) {
            val named = Lexicon.brand(words.joinToString(" "), ctx.country)?.takeIf { kindsNamed.isEmpty() && !(words.size == 1 && Lexicon.ordinary(words[0])) }
            for (b in brands(words, ctx.country)) {
                val q = Query.Brand(b.id)
                val count = count(q, eval)
                if (count == 0) continue
                val symbol = b.logoSlug?.let { "logo-$it" }?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(b.keyTags)
                val clear = b.id == named?.id
                items += SearchItem.BrandResult(b, symbol, q, count, if (clear) NAMED_BRAND else 0.45, named = clear)
                if (refineFor == null) refineFor = q
                for (h in nearest(q, eval)) {
                    items += placeResult(h.copy(why = listOf("brand:${b.id}")), MatchKind.BRAND, ctx, 0.9 * (0.3 + 0.7 * distanceFactor(h.distance)))
                }
            }
        }
        currentCoroutineContext().ensureActive()

        // Names and addresses, typos forgiven; a street's stretches, or a stop's platforms, as one line
        val named = evaluator.run(Query.Name(t), eval.copy(limit = NAME_CANDIDATES)).hits
        val groups = HashMap<String, Int>()
        for (h in named) {
            val kind = matchKind(words, h.record)
            val name = h.record.name()
            if (name != null && kind != MatchKind.ADDRESS) {
                val t = h.record.tags
                val transit = t["public_transport"] != null || t["railway"] != null || t["highway"] == "bus_stop"
                // a canal, a square or a quarter mapped in several pieces (or as a node and an outline) is one line
                // too; shops and cafés are not
                val feature = (h.record.id?.type != OsmType.NODE || "place" in t) && Selection.POI_KEYS.none { it in t }
                val group = when {
                    kind == MatchKind.STREET -> "street"
                    transit -> "transit"
                    feature -> "feature"
                    else -> null
                }
                val key = Text.fold(name) + "|" + (group ?: h.record.preset ?: h.record.category ?: "")
                val at = groups[key]
                if (at != null && group != null) {
                    val prev = items[at] as SearchItem.PlaceResult
                    val near = h.distance == null || prev.hit.distance == null || h.record.distanceTo(prev.hit.record.location) < GROUP_METRES
                    if (near) {
                        items[at] = prev.copy(parts = prev.parts + 1)
                        continue
                    }
                }
                if (at == null) groups[key] = items.size
            }
            items += placeResult(h, kind, ctx, h.score)
        }

        // Online, when the regions have little
        val offlinePlaces = items.count { it is SearchItem.PlaceResult }
        if (ctx.online && geocoder != null && offlinePlaces < ONLINE_BELOW) {
            try {
                val found = geocoder.search(t, ctx.location, ctx.lang, 8, null)
                val known = items.filterIsInstance<SearchItem.PlaceResult>().mapNotNull { it.hit.place.id }.toSet()
                val ofKind = kindsNamed.mapNotNull { tagsTest(it.query) }
                for ((i, p) in found.withIndex()) {
                    if (p.id != null && p.id in known) continue
                    val distance = ctx.location?.let { Geo.distance(it, p.location) }
                    items += SearchItem.Online(p, distance, Symbols.symbolFor(p.tags), onlineScore(p, i, distance, ofKind))
                }
                usedOnline = true
            } catch (e: IOException) {
                // offline results stand on their own
            }
        }

        // Half a query while it is being typed ("café near …") finds no name: the kind it names, rather than nothing
        if (items.isEmpty() && parsed.steps.isNotEmpty() && parsed.confidence >= 0.5) {
            return structured(text, parsed, ctx, eval, areas, started)
        }

        for ((i, r) in recents.matching(t, 3).withIndex()) items += SearchItem.Recent(r, 0.25 - i * 0.01)
        currentCoroutineContext().ensureActive()
        val refinements = refineFor?.let { refinements(it, eval) } ?: emptyList()
        return SearchResults(
            text, rank(items, ctx.limit), parsed.takeIf { it.steps.isNotEmpty() }, usedOnline = usedOnline, micros = micros(started),
            refinements = refinements,
        )
    }

    private suspend fun structured(text: String, parsed: ParsedQuery, ctx: SearchContext, eval: EvalContext, areas: Areas, started: Long, lines: Boolean = true): SearchResults {
        // A count looks in the searcher's town unless the query says where.
        val asked = if (parsed.ask == Ask.COUNT && !parsed.steps.any { spatial(it) }) {
            parsed.copy(steps = parsed.steps.map { inTown(it) })
        } else {
            parsed
        }
        // name areas resolve before the run: offline from the stores, else online
        var unresolved: String? = null
        val names = LinkedHashSet<String>()
        for (s in asked.steps) s.walk { q ->
            if (q is Query.InArea && q.bbox == null && q.name != null && !q.name.equals("bbox", true)) names += q.name
        }
        for (n in names) {
            // the searcher's own town, quarter and centre are looked for around them, not by name
            if (n.startsWith("@")) continue
            if (areas.resolve(n) == null && ctx.online) areas.resolveOnline(n, geocoder)
            if (areas.resolve(n) == null) unresolved = n
        }
        // "not in the centre": of the town the search names, else the searcher's
        if (Query.InArea.CENTRE in names) areas.centreOf(names.firstOrNull { !it.startsWith("@") })
        val steps = asked.steps.map { withBoxes(it, areas) }
        val wide = when (asked.ask) {
            Ask.COUNT -> eval.copy(limit = COUNT_LIMIT)
            Ask.HOURS, Ask.FACT -> eval.copy(limit = HOURS_LIMIT)
            Ask.LIST -> eval
        }
        val results = if (asked.isPlan) evaluator.plan(steps, wide) else listOf(evaluator.run(steps.first(), wide))
        var online = false
        var shown = results
        if (results.all { it.hits.isEmpty() } && ctx.online && overpass != null && !asked.isPlan) {
            // around the searcher it looks farther while it finds none (the owner, 4 November: "Default should be no
            // maximum distance pre set if there are no results")
            for (area in searchAreas(steps.first(), ctx, eval)) {
                try {
                    shown = listOf(OnlineSearch(overpass).run(steps.first(), area, wide).result)
                    online = true
                } catch (e: IOException) {
                    // the offline (empty) answer stands
                    break
                }
                currentCoroutineContext().ensureActive()
                if (shown.any { it.hits.isNotEmpty() }) break
            }
        }
        // a count counts what is just as asked, even none; the places it shows may come closest instead
        val asAsked = shown.first()
        // Nothing as asked: the nearest thing to it, saying what was let go of, each place with what it misses; else
        // the places that come closest, missing the fewest requirements (the owner: "Search should rarely not present
        // any results").
        var loosened = emptyList<Loosened>()
        if (shown.all { it.hits.isEmpty() } && !asked.isPlan) {
            val q = steps.first()
            val relaxed = loosen(q, wide)?.let { (r, how) -> r.copy(hits = evaluator.withMisses(r.hits, how.map { it.from }, wide)) to how }
                ?: closest(q, wide)
            relaxed?.let { (r, how) -> shown = listOf(r); loosened = how }
        }
        val items = ArrayList<SearchItem>()
        val first = shown.first()
        when (asked.ask) {
            Ask.COUNT -> {
                val area = names.firstOrNull { !it.startsWith("@") }
                val records = asAsked.hits.map { it.record }
                // a place open only by guessed hours counts, as open perhaps, not for sure
                val guessed = asAsked.hits.count { it.sure == Sure.YES && it.hoursInferred && "open" in it.why }
                val counted = Overview.count(records, asAsked.sure - guessed, ctx.lang)
                items += SearchItem.Count(asked, asked.per?.let { counted.copy(groups = groups(records, it, ctx.lang)) } ?: counted, area, 6.0)
            }
            Ask.HOURS -> {
                val book = HoursBook(first.hits.map { it.record })
                items += SearchItem.Hours(asked, Overview.hours(first.hits, ctx.now, ctx.hours, book), 6.0)
            }
            Ask.FACT -> asked.asked?.let { key -> items += SearchItem.Fact(asked, key, Overview.facts(first.hits, key), 6.0) }
            Ask.LIST -> {}
        }
        // a loosened answer is the query it became, not the kind's view with the filters that found nothing
        val view = if (loosened.isEmpty()) KindViews.of(asked, ctx.now) else null
        items += SearchItem.Structured(asked, shown.map { it.copy(hits = it.hits.take(ctx.limit)) }, online, 5.0, loosened, view)
        if (lines) for ((i, h) in first.hits.take(ctx.limit).withIndex()) items += placeResult(h, MatchKind.QUERY, ctx, 4.0 - i * 0.001)
        return SearchResults(text, items, asked, unresolved, online, micros(started))
    }

    /**
     * When [q] finds nothing as asked, the nearest query that finds something and how it was loosened: one hard part
     * at a time first, the likeliest to be let go of first, then all of them at once. Null when nothing looser finds
     * anything either.
     */
    private fun loosen(q: Query, eval: EvalContext): Pair<QueryResult, List<Loosened>>? {
        val ways = Loosening.of(q)
        for (w in ways.sortedBy { Loosening.rank(it) }) {
            val r = evaluator.run(Loosening.apply(q, listOf(w)), eval)
            if (r.hits.isNotEmpty()) return r to listOf(w)
        }
        if (ways.size < 2) return null
        val r = evaluator.run(Loosening.apply(q, ways), eval)
        return if (r.hits.isNotEmpty()) r to ways else null
    }

    /**
     * When nothing looser finds anything either: the places of the kind [q] asks for that come closest to it
     * ([OfflineEvaluator.closest]), each requirement let go of and every place saying what it misses. Where the
     * kind has no place in the area asked, the nearest outside it, around the area. Null when the kind has no place
     * anywhere.
     */
    private fun closest(q: Query, eval: EvalContext): Pair<QueryResult, List<Loosened>>? {
        val (base, needs) = Closest.split(q) ?: (q to emptyList())
        if (needs.isNotEmpty()) {
            val r = evaluator.closest(base, needs, eval)
            if (r.hits.isNotEmpty()) return r to needs.map { Loosened(it, null) }
        }
        val (rest, areas) = Closest.withoutAreas(base)
        if (areas.isEmpty() || rest === Query.All) return null
        val around = areas.firstNotNullOfOrNull { it.bbox?.center ?: it.name?.let { n -> eval.areas?.resolve(n)?.center } }
        val r = evaluator.closest(rest, areas + needs, eval.copy(location = around ?: eval.location, viewport = null))
        return if (r.hits.isNotEmpty()) r to (areas + needs).map { Loosened(it, null) } else null
    }

    /**
     * "From A to B": each end found as a search for its words would find it (the start with its "van" or "de" too,
     * as a name may begin with one), the end's other candidates after it.
     */
    private suspend fun route(text: String, r: RouteAsk, parsed: ParsedQuery, ctx: SearchContext, started: Long): SearchResults? {
        // the stops in turn, each looked for around the one before ("Prinsengracht 263, 500, 1012"); one not found is no way
        var near = ctx.location
        val via = r.via.map { v ->
            val stop = endOf(run(v, ctx.copy(location = near)), v) ?: return null
            near = stop.location
            stop
        }
        val to = run(r.to, ctx.copy(location = near))
        // an end that leads nowhere ("bike to rent") was not a way: the words are searched as they are
        val end = endOf(to, r.to) ?: return null
        val from = r.from?.let { f ->
            val plain = run(f, ctx)
            val whole = r.fromWord?.let { w -> run("$w $f", ctx) }
            listOfNotNull(whole, plain).maxByOrNull { endScore(it) }
        }
        val items = ArrayList<SearchItem>()
        items += SearchItem.Route(r, from?.let { endOf(it, r.from) }, end, 7.0, via)
        items += to.items.filter { it !is SearchItem.Route }
        return SearchResults(text, items, parsed, usedOnline = to.usedOnline || from?.usedOnline == true, micros = micros(started))
    }

    /** Where a search for an end leads: its first place or point. */
    private fun endOf(r: SearchResults, text: String): RouteEnd? {
        for (item in r.items) when (item) {
            is SearchItem.Coordinate -> return RouteEnd(text, null, item.location, null)
            is SearchItem.PlaceResult -> return RouteEnd(text, item.title, item.hit.place.location, item.hit.place)
            is SearchItem.Element -> return RouteEnd(text, item.hit.record.name(), item.hit.place.location, item.hit.place)
            is SearchItem.Online -> return RouteEnd(text, item.place.extra["label"] ?: item.place.tags["name"], item.place.location, item.place)
            else -> {}
        }
        return null
    }

    /** How surely a search found the place its words name: a name or an address match beats a kind's nearest. */
    private fun endScore(r: SearchResults): Double = r.places.firstOrNull()?.let { p ->
        p.hit.relevance + if (p.match == MatchKind.NAME || p.match == MatchKind.ADDRESS) 1.0 else 0.0
    } ?: if (r.items.any { it is SearchItem.Coordinate }) 2.0 else 0.0

    /** [q] in the searcher's town, inside its order and limit. */
    private fun inTown(q: Query): Query = when (q) {
        is Query.Sort -> Query.Sort(inTown(q.query), q.by)
        is Query.Limit -> Query.Limit(inTown(q.query), q.count)
        else -> Query.and(q, Query.InArea(null, Query.InArea.TOWN))
    }

    /**
     * [p], or [p]'s text read again without its guessed area when no place of that name is known offline: "boulangerie
     * amsterdam" is in Amsterdam, "bakker Jansen" a bakery of that name.
     */
    private fun known(p: ParsedQuery, ctx: SearchContext, areas: Areas): ParsedQuery {
        val guess = p.guessedArea ?: return p
        return if (areas.resolve(guess) != null) p else QueryParser.parse(p.text.trim(), ctx.lang, ctx.now, ctx.country, guessAreas = false)
    }

    /** True when [q] says where to look: a circle, a box or an area, a route. */
    private fun spatial(q: Query): Boolean {
        var found = false
        q.walk { if (it is Query.Within || it is Query.InArea || it is Query.AlongRoute) found = true }
        return found
    }

    /** [q] with the boxes of its named areas filled in where they resolve, so online search looks there too. */
    private fun withBoxes(q: Query, areas: Areas): Query = when (q) {
        is Query.InArea -> if (q.bbox == null && q.name != null) areas.resolve(q.name)?.let { Query.InArea(it, q.name) } ?: q else q
        is Query.And -> Query.And(q.parts.map { withBoxes(it, areas) })
        is Query.Or -> Query.Or(q.parts.map { withBoxes(it, areas) })
        is Query.Sort -> Query.Sort(withBoxes(q.query, areas), q.by)
        is Query.Limit -> Query.Limit(withBoxes(q.query, areas), q.count)
        is Query.Not -> Query.Not(withBoxes(q.part, areas))
        else -> q
    }

    /**
     * [records] told by the quarter ([per] "neighbourhood") or the town ("town") each lies in, else lies nearest, the
     * most first: by the finest quarters there are several of, where a city maps its districts and its neighbourhoods.
     */
    private fun groups(records: List<PlaceRecord>, per: String, lang: String): List<KindCount> {
        val box = BBox.of(records.map { it.location })?.buffer(GROUP_REACH_M) ?: return emptyList()
        val levels = if (per == "town") listOf(TOWN_PLACES) else listOf(setOf("neighbourhood"), setOf("quarter"), setOf("suburb", "borough"))
        val named = places.byTag("place", null, box).filter { it.name(lang) != null }
        val by = levels.map { l -> named.filter { it.tags["place"] in l } }.firstOrNull { it.size >= MIN_GROUPS } ?: return emptyList()
        val of = records.mapNotNull { r ->
            by.filter { q -> q.place.bbox?.let { r.location in it } == true }.minByOrNull { q -> q.place.bbox!!.let { it.width * it.height } }
                ?: by.minByOrNull { Geo.distance(it.location, r.location) }?.takeIf { Geo.distance(it.location, r.location) <= GROUP_REACH_M }
        }
        return of.groupBy { it.place.key }.values
            .map { list -> KindCount(list.first().name(lang).orEmpty(), "ui-map-pin", list.size) }
            .sortedByDescending { it.count }
    }

    /**
     * Where an online query looks: its own circle or box, else the view, else around the searcher, 3 km and then
     * farther in turn while nothing is found ([ONLINE_RADII_M]).
     */
    private fun searchAreas(q: Query, ctx: SearchContext, eval: EvalContext): List<OverpassArea> {
        var within: Query.Within? = null
        var box: BBox? = null
        q.walk {
            if (it is Query.Within && within == null) within = it
            if (it is Query.InArea && box == null) box = it.bbox ?: it.name?.let { n -> eval.areas?.resolve(n) }
        }
        within?.let { w -> (w.center ?: ctx.location)?.let { return listOf(OverpassArea.Around(it, w.radius)) } }
        box?.let { return listOf(OverpassArea.Box(it)) }
        ctx.viewport?.let { return listOf(OverpassArea.Box(it)) }
        return ctx.location?.let { at -> ONLINE_RADII_M.map { OverpassArea.Around(at, it) } }.orEmpty()
    }

    private suspend fun online(id: OsmId, ctx: SearchContext): Hit? {
        val o = overpass ?: return null
        val type = when (id.type) {
            OsmType.NODE -> "node"
            OsmType.WAY -> "way"
            OsmType.RELATION -> "relation"
        }
        return try {
            o.places("[out:json][timeout:25];\n$type(id:${id.id});\nout tags center;").firstOrNull()?.let { p ->
                val r = PlaceRecord.of(p)
                Hit(r, 1.0, 1.0, ctx.location?.let { r.distanceTo(it) }, null)
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * The goods a plain search names ("picture hooks", "where to buy batteries"), when that is all it says, and the
     * words that named them.
     */
    private fun goodsOf(parsed: ParsedQuery): Pair<Goods, String>? {
        if (parsed.syntax != QuerySyntax.NATURAL || parsed.structured || parsed.steps.size != 1) return null
        if (parsed.unknown.size > 1 || parsed.confidence < 0.5) return null
        val chips = parsed.chips
        if (chips.isEmpty() || chips.any { it.kind != ChipKind.KIND || !it.value.startsWith("goods:") }) return null
        val id = chips.map { it.value.removePrefix("goods:") }.distinct().singleOrNull() ?: return null
        return GoodsCatalogue.byId(id)?.let { it to chips.first().label }
    }

    /** Refinements for a kind or a brand, from its places around the searcher (or in the view). */
    private fun refinements(base: Query, eval: EvalContext): List<Refinement> {
        val area = eval.viewport ?: eval.location?.let { BBox.around(it, REFINE_METRES) } ?: places.bounds ?: return emptyList()
        return Refinements.offer(base, evaluator.matches(base, eval, area), eval)
    }

    /** How many places [q] finds on the whole map: from the tag statistics for a single tag, else by running it. */
    private fun count(q: Query, eval: EvalContext): Int {
        val f = (q as? Query.Tags)?.filter
        if (f is TagFilter.Equals) return places.estimate(f.key, f.value)
        return evaluator.matches(q, eval, places.bounds).size
    }

    /** The few places of [q] nearest the searcher. */
    private fun nearest(q: Query, eval: EvalContext): List<Hit> =
        evaluator.run(Query.Limit(Query.Sort(q, SortBy.DISTANCE), KIND_NEAREST), eval).hits

    private class KindMatch(val id: String, val label: String, val symbol: String, val query: Query, val score: Double)

    /** Kinds of places the text names: presets in the app's language, then in the other search languages, and categories. */
    private fun kinds(text: String, lang: String): List<KindMatch> {
        val out = ArrayList<KindMatch>()
        val langs = (listOf(lang) + Lexicon.LANGS).distinct()
        for ((i, l) in langs.withIndex()) {
            val hits = Presets.search(text, l, limit = 4).filter { it.score >= MIN_KIND_SCORE }
            for (h in hits) {
                val filter = Lexicon.filterOf(h.preset.tags) ?: continue
                // two presets that tag alike ("Restroom", "Toilets") are one kind
                if (out.any { it.id == h.preset.id || (it.query as? Query.Tags)?.filter == filter }) continue
                val symbol = Kinds.symbolOf(h.preset) ?: Symbols.symbolFor(h.preset.tags)
                out += KindMatch(h.preset.id, h.preset.name(lang), symbol, Query.Tags(filter), h.score / 100 * (if (i == 0) 1.0 else 0.95))
            }
            if (out.isNotEmpty()) break
        }
        val words = SearchText.words(text)
        for (c in Categories.all()) {
            val best = Lexicon.LANGS.maxOf { NameMatch.score(words, Text.fold(c.name(it))) }
            if (best < 0.85) continue
            // a category that is a preset already found ("Toilets" and "Restroom") is that kind
            if (!c.isGroup && out.any { (it.query as? Query.Tags)?.filter == c.rule }) continue
            out += KindMatch("category:${c.id}", c.name(lang), c.symbol, Query.Category(c.id), best * 0.9)
        }
        return out.sortedByDescending { it.score }.take(MAX_KINDS)
    }

    /** Whether a place's tags are of the kind [q] names ([Query.Tags] or [Query.Category]); null for other queries. */
    private fun tagsTest(q: Query): ((Map<String, String>) -> Boolean)? = when (q) {
        is Query.Tags -> q.filter::matches
        is Query.Category -> Categories.byId(q.id)?.let { c ->
            val rules = if (c.isGroup) Categories.children(c.id).mapNotNull { it.rule } else listOfNotNull(c.rule)
            if (rules.isEmpty()) null else { tags: Map<String, String> -> rules.any { it.matches(tags) } }
        }
        else -> null
    }

    /**
     * An online result's score, below the offline results as before. For a name ("Westerkerk", "Haarlem") the
     * geocoder's order stands: it already weighs importance and the searcher's position. For a kind ("apotheek",
     * "cafe") the places of that kind come before those that only bear its word in their name, and the nearer before
     * the farther, as offline: a building called "Apotheek" 46 km away no longer heads the pharmacies round the corner.
     */
    private fun onlineScore(p: Place, index: Int, distance: Double?, ofKind: List<(Map<String, String>) -> Boolean>): Double {
        val order = 1.0 - ONLINE_ORDER_STEP * index
        if (ofKind.isEmpty()) return ONLINE_TOP * order
        val kind = if (ofKind.any { it(p.tags) }) 1.0 else ONLINE_OTHER_KIND
        return ONLINE_TOP * order * kind * (0.3 + 0.7 * distanceFactor(distance))
    }

    /** Brands whose name the words spell (a typo allowed), widest first. */
    private fun brands(words: List<String>, country: String?): List<Brand> {
        val text = words.joinToString(" ")
        // its very name, or one people call it by ("appie"), is the brand; a near spelling must look like its name
        Lexicon.brand(text, country)?.let { return listOf(it) }
        return Brands.search(text, country, 5).filter { b -> NameMatch.score(words, Text.fold(b.brand)) >= 0.6 }.take(2)
    }

    private fun matchKind(words: List<String>, r: PlaceRecord): MatchKind {
        val name = SearchText.names(r.tags).maxOfOrNull { NameMatch.score(words, Text.fold(it)) } ?: 0.0
        val street = r.tags["addr:street"] ?: r.tags["addr:place"]
        if (name < 0.45 && street != null) {
            // with a number, the number must be this address's; without, the street's name will do
            val hn = r.tags["addr:housenumber"]
            val fit = if (hn != null && words.any(Addresses::looksLikeNumber)) {
                Addresses.relevance(words, r.tags)
            } else {
                NameMatch.score(words, Text.fold(listOfNotNull(street, hn).joinToString(" ")))
            }
            if (fit >= 0.45) return MatchKind.ADDRESS
        }
        if (r.tags["highway"] != null && r.id?.type == OsmType.WAY && r.tags["highway"] != "bus_stop") return MatchKind.STREET
        return MatchKind.NAME
    }

    private fun hit(r: PlaceRecord, from: LatLon?, eval: EvalContext): Hit {
        val status = r.tags["opening_hours"]?.let { app.atlas.places.OpeningHours.parseOrNull(it) }?.status(eval.now.toLocalDateTime(), eval.hours)
        return Hit(r, 1.0, 1.0, from?.let { r.distanceTo(it) }, status)
    }

    private fun placeResult(h: Hit, kind: MatchKind, ctx: SearchContext, score: Double): SearchItem.PlaceResult {
        val r = h.record
        val preset = r.preset?.let { Presets.byId(it) }
        val kindName = preset?.name(ctx.lang) ?: r.category?.let { Categories.byId(it)?.name(ctx.lang) }
        val address = Localise.address(r.tags)
        // A place with no name nor address is titled by what it is ("Gift Shop"), and no line repeats it ("Gifts")
        val title = r.name(ctx.lang) ?: address ?: preset?.fullName(ctx.lang) ?: kindName ?: r.place.key
        val said = title == kindName || title == preset?.fullName(ctx.lang)
        val subtitle = listOfNotNull(kindName?.takeIf { !said }, address?.takeIf { it != title && kind != MatchKind.STREET }).joinToString(" · ").ifEmpty { null }
        return SearchItem.PlaceResult(h, kind, title, subtitle, score)
    }

    /** Best first, each place once (at its best), and no more places than asked. */
    private fun rank(items: List<SearchItem>, limit: Int): List<SearchItem> {
        val best = LinkedHashMap<String, SearchItem>()
        var n = 0
        for (item in items.sortedByDescending { it.score }) {
            val key = when (item) {
                is SearchItem.PlaceResult -> "p:" + item.hit.place.key
                is SearchItem.Online -> "o:" + item.place.key
                is SearchItem.Kind -> "k:" + item.label
                is SearchItem.BrandResult -> "b:" + item.brand.id
                else -> "x:" + n++
            }
            if (key in best) continue
            if (item is SearchItem.PlaceResult && best.values.count { it is SearchItem.PlaceResult } >= limit) continue
            best[key] = item
        }
        return best.values.toList()
    }

    /** Area names resolved offline from the stores' places (cities, quarters, boundaries), else online. */
    private inner class Areas(val ctx: SearchContext) : AreaResolver {
        private val known = HashMap<String, BBox?>()

        override fun resolve(name: String): BBox? = when (name) {
            Query.InArea.TOWN -> known.getOrPut(name) { town() }
            Query.InArea.NEIGHBOURHOOD -> known.getOrPut(name) { neighbourhood() }
            Query.InArea.CENTRE -> known.getOrPut(name) { centre(null) }
            else -> known.getOrPut(name.lowercase()) { offline(name) }
        }

        /** "The centre" of the town called [town], where the search names one; else of the searcher's. */
        fun centreOf(town: String?) {
            known[Query.InArea.CENTRE] = centre(town?.let { resolve(it) })
        }

        /**
         * A town's centre: its quarter called so (Centrum, Binnenstad, Innenstadt, Centre-ville), else a walk around its
         * heart, where its place is marked or, failing that, the middle of its box.
         */
        private fun centre(town: BBox?): BBox? {
            val box = town ?: town() ?: return null
            val inTown = places.byTag("place", null, box)
            val heart = inTown.filter { it.tags["place"] in TOWN_PLACES }
                .maxByOrNull { placeRank(it.tags["place"]) * HEART_RANK - Geo.distance(it.location, box.center) }?.location ?: box.center
            val quarter = inTown.filter { q -> q.tags["place"] in QUARTERS && SearchText.names(q.tags).any { Text.fold(it).replace('-', ' ') in CENTRE_NAMES } }
                .minByOrNull { Geo.distance(it.location, heart) }
            return quarter?.let { q -> q.place.bbox?.takeIf { it.width > 0 } ?: BBox.around(q.location, radiusOf(q.tags["place"])) } ?: BBox.around(heart, CENTRE_M)
        }

        /** The quarter the searcher is in: the smallest suburb, quarter or neighbourhood around them, else a walk around. */
        private fun neighbourhood(): BBox? {
            val at = ctx.location ?: return ctx.viewport
            val around = places.byTag("place", null, BBox.around(at, NEIGHBOURHOOD_SEARCH_M)).filter { it.tags["place"] in QUARTERS }
            val inside = around.filter { r -> r.place.bbox?.let { at in it } ?: (r.distanceTo(at) <= radiusOf(r.tags["place"])) }
            val r = inside.minByOrNull { r -> r.place.bbox?.let { it.width * it.height } ?: radiusOf(r.tags["place"]).let { it * it } }
                ?: return BBox.around(at, NEIGHBOURHOOD_FALLBACK_M)
            return r.place.bbox?.takeIf { it.width > 0 } ?: BBox.around(r.location, radiusOf(r.tags["place"]))
        }

        /**
         * The town the searcher is in: the app's, else the city, town or village of the stores whose reach takes in
         * the searcher (the biggest), else the nearest, else a few kilometres around the searcher.
         */
        private fun town(): BBox? {
            ctx.town?.let { return it }
            val at = ctx.location ?: return ctx.viewport
            val around = places.byTag("place", null, BBox.around(at, TOWN_SEARCH_M)).filter { it.tags["place"] in TOWN_PLACES }
            val inside = around.filter { r -> r.place.bbox?.let { at in it } ?: (r.distanceTo(at) <= radiusOf(r.tags["place"])) }
            val r = inside.maxByOrNull { placeRank(it.tags["place"]) } ?: around.minByOrNull { it.distanceTo(at) }
                ?: return BBox.around(at, TOWN_FALLBACK_M)
            return r.place.bbox?.takeIf { it.width > 0 } ?: BBox.around(r.location, radiusOf(r.tags["place"]))
        }

        fun offline(name: String): BBox? {
            val words = SearchText.words(name)
            val r = places.text(name, ctx.location, null, 50)
                .filter { it.tags["place"] != null || it.tags["boundary"] in AREA_BOUNDARIES || it.tags["landuse"] == "residential" }
                .map { it to (SearchText.names(it.tags).maxOfOrNull { n -> NameMatch.score(words, Text.fold(n), typing = false) } ?: 0.0) }
                .filter { it.second >= 0.9 }
                .maxByOrNull { it.second * 10 + placeRank(it.first.tags["place"]) }?.first ?: return null
            return r.place.bbox?.takeIf { it.width > 0 } ?: BBox.around(r.location, radiusOf(r.tags["place"]))
        }

        suspend fun resolveOnline(name: String, g: Geocoder?) {
            if (g == null) return
            try {
                val p = g.search(name, ctx.location, ctx.lang, 1, null).firstOrNull() ?: return
                known[name.lowercase()] = p.bbox?.takeIf { it.width > 0 } ?: BBox.around(p.location, radiusOf(p.tags["place"]))
            } catch (e: IOException) {
                // stays unresolved
            }
        }

        private fun placeRank(place: String?): Int = when (place) {
            "city" -> 5
            "town" -> 4
            "suburb", "borough", "quarter" -> 3
            "village", "neighbourhood" -> 2
            else -> 1
        }

        private fun radiusOf(place: String?): Double = when (place) {
            "city" -> 8_000.0
            "town" -> 3_000.0
            "suburb", "borough", "village" -> 1_500.0
            "quarter", "neighbourhood" -> 700.0
            else -> 1_000.0
        }
    }

    private fun distanceFactor(d: Double?): Double = d?.let { 1.0 / (1.0 + it / 1_500.0) } ?: 1.0

    private fun micros(started: Long) = (System.nanoTime() - started) / 1000

    companion object {
        private const val KIND_NEAREST = 3

        /** A chain named outright: above any place (one at most), below what the words ask (a count, a way). */
        private const val NAMED_BRAND = 2.0

        /** A count counts up to this many places; an hours overview compares up to this many. */
        private const val COUNT_LIMIT = 100_000
        private const val HOURS_LIMIT = 200

        /** "This town": a city, town or village within this far, else this far around the searcher. */
        private const val TOWN_SEARCH_M = 15_000.0
        private const val TOWN_FALLBACK_M = 3_000.0
        private val TOWN_PLACES = setOf("city", "town", "village")

        /** "This neighbourhood": the smallest of these around the searcher within this far, else this far around them. */
        private const val NEIGHBOURHOOD_SEARCH_M = 3_000.0
        private const val NEIGHBOURHOOD_FALLBACK_M = 800.0
        private val QUARTERS = setOf("suburb", "quarter", "neighbourhood", "borough")

        /** What a town's centre is called where it is mapped as a quarter; else this far around its heart. */
        private val CENTRE_NAMES = setOf(
            "centrum", "binnenstad", "stadscentrum", "historisch centrum", "oude stad", "centre", "center", "city centre", "city center",
            "town centre", "downtown", "old town", "centre ville", "vieille ville", "hypercentre", "innenstadt", "altstadt", "zentrum",
            "stadtzentrum", "stadtmitte", "mitte", "centro", "centro storico", "centro historico",
        )
        private const val CENTRE_M = 1_200.0
        private const val HEART_RANK = 1e6

        /** A count by place: the quarters at least this many of are mapped, each place given to one within this far. */
        private const val MIN_GROUPS = 3
        private const val GROUP_REACH_M = 2_500.0

        /** Boundaries a name may be an area by: a municipality's, a postcode's ("cafés in 1017"). */
        private val AREA_BOUNDARIES = setOf("administrative", "postal_code")
        private const val MAX_KINDS = 2
        private const val MIN_KIND_SCORE = 70.0
        private const val NAME_CANDIDATES = 40
        private const val ONLINE_BELOW = 3

        /** How far around the searcher an online query looks, in turn while it finds nothing. */
        private val ONLINE_RADII_M = listOf(3_000.0, 25_000.0, 100_000.0)
        private const val ONLINE_TOP = 0.3
        private const val ONLINE_ORDER_STEP = 0.03
        private const val ONLINE_OTHER_KIND = 0.6
        private const val GROUP_METRES = 600.0
        private const val GOODS_SELLERS = 3
        private const val REFINE_METRES = 3_000.0

        private val OSM_REF = Regex("""(?i)^\s*(?:([nwr])\s?(\d{1,12})|(node|way|relation)\s*[/ ]\s*(\d{1,12}))\s*$""")
        private val SHORT_REF = Regex("""(?i)^\s*[nwr]\s?\d{1,12}\s*$""")
        private val OSM_URL = Regex("""(?i)openstreetmap\.org/(node|way|relation)/(\d+)""")

        /** An OSM id typed or pasted: "n26973327", "way 99205257", "node/123", an openstreetmap.org link. */
        fun osmRef(text: String): OsmId? {
            OSM_URL.find(text)?.let { return OsmId.parse("${it.groupValues[1]}/${it.groupValues[2]}") }
            if (!OSM_REF.matches(text)) return null
            return OsmId.parse(text.trim())
        }
    }
}
