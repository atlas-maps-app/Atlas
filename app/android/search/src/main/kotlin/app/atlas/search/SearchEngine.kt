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

    /** A chain ("All 23 Albert Heijn"): its branches as a query, and how many there are. */
    data class BrandResult(val brand: Brand, val symbol: String, val query: Query, val count: Int, override val score: Double) : SearchItem

    /** The text read as a query ("café 200 m from a bike dock"): its chips and its results, per step of a plan. */
    data class Structured(val parsed: ParsedQuery, val results: List<QueryResult>, val online: Boolean, override val score: Double) : SearchItem

    /** A place found by the online geocoder, beyond the downloaded regions. */
    data class Online(val place: Place, val distance: Double?, val symbol: String, override val score: Double) : SearchItem

    data class Recent(val recent: RecentSearch, override val score: Double) : SearchItem
}

/** The search box's answer, best first. */
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
        val parsed = QueryParser.parse(t, ctx.lang, ctx.now)
        if (parsed.steps.isNotEmpty() && (parsed.syntax == QuerySyntax.POWER || (parsed.structured && parsed.confidence >= 0.6))) {
            return structured(text, parsed, ctx, eval, areas, started)
        }

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
        for (k in kinds(t, ctx.lang)) {
            val count = count(k.query, eval)
            if (count == 0) continue
            items += SearchItem.Kind(k.label, k.symbol, k.query, count, k.score * 0.5)
            if (refineFor == null) refineFor = k.query
            for (h in nearest(k.query, eval)) {
                items += placeResult(h.copy(why = listOf("kind:${k.id}")), MatchKind.KIND, ctx, k.score * 0.95 * (0.3 + 0.7 * distanceFactor(h.distance)))
            }
        }
        currentCoroutineContext().ensureActive()

        // Brands: "albert heijn", typos forgiven, with how many branches there are
        if (t.length >= 3) {
            for (b in brands(words, ctx.country)) {
                val q = Query.Brand(b.id)
                val count = count(q, eval)
                if (count == 0) continue
                val symbol = b.logoSlug?.let { "logo-$it" }?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(b.keyTags)
                items += SearchItem.BrandResult(b, symbol, q, count, 0.45)
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
                for ((i, p) in found.withIndex()) {
                    if (p.id != null && p.id in known) continue
                    items += SearchItem.Online(p, ctx.location?.let { Geo.distance(it, p.location) }, Symbols.symbolFor(p.tags), 0.3 - i * 0.01)
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

    private suspend fun structured(text: String, parsed: ParsedQuery, ctx: SearchContext, eval: EvalContext, areas: Areas, started: Long): SearchResults {
        // name areas resolve before the run: offline from the stores, else online
        var unresolved: String? = null
        val names = LinkedHashSet<String>()
        for (s in parsed.steps) s.walk { q ->
            if (q is Query.InArea && q.bbox == null && q.name != null && !q.name.equals("bbox", true)) names += q.name
        }
        for (n in names) {
            if (areas.resolve(n) == null && ctx.online) areas.resolveOnline(n, geocoder)
            if (areas.resolve(n) == null) unresolved = n
        }
        val results = if (parsed.isPlan) evaluator.plan(parsed.steps, eval) else listOf(evaluator.run(parsed.query, eval))
        var online = false
        var shown = results
        if (results.all { it.hits.isEmpty() } && ctx.online && overpass != null && !parsed.isPlan) {
            val area = searchArea(parsed.query, ctx, eval)
            if (area != null) {
                try {
                    shown = listOf(OnlineSearch(overpass).run(parsed.query, area, eval).result)
                    online = true
                } catch (e: IOException) {
                    // the offline (empty) answer stands
                }
            }
        }
        val items = ArrayList<SearchItem>()
        items += SearchItem.Structured(parsed, shown, online, 5.0)
        for ((i, h) in shown.first().hits.withIndex()) items += placeResult(h, MatchKind.QUERY, ctx, 4.0 - i * 0.001)
        return SearchResults(text, items, parsed, unresolved, online, micros(started))
    }

    /** Where an online query looks: its own circle or box, else the view, else 3 km around the searcher. */
    private fun searchArea(q: Query, ctx: SearchContext, eval: EvalContext): OverpassArea? {
        var within: Query.Within? = null
        var box: BBox? = null
        q.walk {
            if (it is Query.Within && within == null) within = it
            if (it is Query.InArea && box == null) box = it.bbox ?: it.name?.let { n -> eval.areas?.resolve(n) }
        }
        within?.let { w -> (w.center ?: ctx.location)?.let { return OverpassArea.Around(it, w.radius) } }
        box?.let { return OverpassArea.Box(it) }
        ctx.viewport?.let { return OverpassArea.Box(it) }
        return ctx.location?.let { OverpassArea.Around(it, 3_000.0) }
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
                val symbol = Presets.iconOf(h.preset)?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(h.preset.tags)
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

    /** Brands whose name the words spell (a typo allowed), widest first. */
    private fun brands(words: List<String>, country: String?): List<Brand> {
        val text = words.joinToString(" ")
        val exact = Lexicon.brand(text, country)
        val found = if (exact != null) listOf(exact) else Brands.search(text, country, 5)
        return found.filter { b -> NameMatch.score(words, Text.fold(b.brand)) >= 0.6 }.take(2)
    }

    private fun matchKind(words: List<String>, r: PlaceRecord): MatchKind {
        val name = SearchText.names(r.tags).maxOfOrNull { NameMatch.score(words, Text.fold(it)) } ?: 0.0
        if (name < 0.45 && r.tags["addr:street"] != null) {
            val addr = listOfNotNull(r.tags["addr:street"], r.tags["addr:housenumber"]).joinToString(" ")
            if (NameMatch.score(words, Text.fold(addr)) >= 0.45) return MatchKind.ADDRESS
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
        val kindName = r.preset?.let { Presets.byId(it) }?.name(ctx.lang) ?: r.category?.let { Categories.byId(it)?.name(ctx.lang) }
        val address = Localise.address(r.tags)
        val title = r.name(ctx.lang) ?: address ?: kindName ?: r.place.key
        val subtitle = listOfNotNull(kindName?.takeIf { it != title }, address?.takeIf { it != title && kind != MatchKind.STREET }).joinToString(" · ").ifEmpty { null }
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

        override fun resolve(name: String): BBox? = known.getOrPut(name.lowercase()) { offline(name) }

        fun offline(name: String): BBox? {
            val words = SearchText.words(name)
            val r = places.text(name, ctx.location, null, 50)
                .filter { it.tags["place"] != null || it.tags["boundary"] == "administrative" || it.tags["landuse"] == "residential" }
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
        private const val MAX_KINDS = 2
        private const val MIN_KIND_SCORE = 70.0
        private const val NAME_CANDIDATES = 40
        private const val ONLINE_BELOW = 3
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
