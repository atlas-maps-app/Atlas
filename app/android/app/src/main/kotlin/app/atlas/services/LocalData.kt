package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.TagFilter
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.render.data.ElementType
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackBoxes
import app.atlas.render.data.PackFeatures
import app.atlas.render.data.PackReader
import app.atlas.route.OsmUnits
import app.atlas.route.WayNamer
import app.atlas.search.EvalContext
import app.atlas.search.MemoryPlaces
import app.atlas.search.OfflineEvaluator
import app.atlas.search.PlaceSource
import app.atlas.search.PlaceStore
import app.atlas.search.Query
import app.atlas.search.QueryResult
import app.atlas.search.Regions
import app.atlas.search.Selection
import app.atlas.search.byFilter
import app.atlas.search.walk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/** Where an answer of [LocalData] came from: a downloaded region's place store, Atlas's own map packs, or Overpass online. */
enum class LocalOrigin { STORE, PACK, ONLINE }

/**
 * An answer of [LocalData] and where it came from: null when nothing on the phone covers the place and it is offline.
 * [error] says why the online layer failed, when it did.
 */
data class LocalAnswer<T>(val value: T, val origin: LocalOrigin?, val error: String? = null)

/** The address at a point: the street and house number, the postcode, the quarter and town, where it stands, and how it was found. */
data class LocalAddress(
    val street: String?,
    val housenumber: String?,
    val postcode: String?,
    val quarter: String?,
    val town: String?,
    /** Where the addressed house (or entrance) stands; null when only the street is known. */
    val at: LatLon?,
    /** The element carrying the address. */
    val id: OsmId?,
    /** Metres from the point asked about to the addressed house: 0 inside it; null when only the street is known. */
    val distanceM: Double?,
    val origin: LocalOrigin,
) {
    /** "Westermarkt 13", or the street alone, or null. */
    val line: String? get() = street?.let { s -> housenumber?.let { "$s $it" } ?: s }
}

/** A named street or waterway with its lines (a way's geometry, or a waterway relation's member ways). */
data class LocalStreet(val id: OsmId, val name: String?, val ref: String?, val water: Boolean, val tags: Map<String, String>, val lines: List<List<LatLon>>)

/**
 * A bridge over a route: where along it (metres), the point, its name, the clearance under it for boats (the seamark's,
 * else `maxheight:physical`), the `maxheight` tagged on it, its clearance width, and every OSM element mapping it (a
 * road and its pavements are one bridge).
 */
data class LocalBridge(
    val along: Double,
    val at: LatLon,
    val name: String?,
    val clearance: Double?,
    val maxheight: Double?,
    val width: Double?,
    val ids: List<OsmId>,
)

/** One chain around: the brand and its branches, nearest first. */
data class LocalBrand(val brand: Brand, val branches: List<Place>)

/** What is at a point: its address, the place it is in (a building, a church, a park), and the named places around. */
data class LocalHere(val address: LocalAddress?, val spot: Place?, val nearby: List<Place>, val origin: LocalOrigin?)

/**
 * The features' data on Atlas's own sources, offline first, in layers: the downloaded regions' place stores (FTS5 and
 * R*Tree, every tag), the map's packs ([PackFeatures]: the bundled Westerkerk, the renders' packs, city and country
 * packs, packs built from Overpass; full geometry, so outlines, streets and bridges), and Overpass online where nothing
 * on the phone covers the place. Places, brands and quests ask the stores first (whole regions, indexed by category,
 * brand and text); addresses, street names, streets and bridges ask the packs first (they hold the lines and outlines).
 *
 * The calls that return plain values read files and must run off the main thread; the suspend ones switch themselves.
 */
class LocalData(
    private val stores: () -> PlaceSource?,
    private val packs: () -> PackFeatures?,
    private val overpass: () -> Overpass? = { null },
    private val isOnline: () -> Boolean = { false },
    private val onClose: () -> Unit = {},
) : Closeable {

    // ---- Places ---------------------------------------------------------------------------------------------------

    /** Places within [radiusM] of [centre] whose tags pass [filter], nearest first, at most [limit]. */
    suspend fun places(centre: LatLon, radiusM: Double, filter: TagFilter, limit: Int = DEFAULT_LIMIT): LocalAnswer<List<Place>> =
        withContext(Dispatchers.IO) {
            val box = BBox.around(centre, radiusM)
            val within = { p: Place -> Geo.distance(centre, p.location) <= radiusM }
            layered(centre, box, filter, within) { nearest(it, centre, limit) }
        }

    /** Places in [box] whose tags pass [filter], nearest its middle first, at most [limit]. */
    suspend fun places(box: BBox, filter: TagFilter, limit: Int = DEFAULT_LIMIT): LocalAnswer<List<Place>> = withContext(Dispatchers.IO) {
        layered(box.center, box, filter, { it.location in box }) { nearest(it, box.center, limit) }
    }

    private suspend fun layered(
        at: LatLon,
        box: BBox,
        filter: TagFilter,
        keep: (Place) -> Boolean,
        order: (List<Place>) -> List<Place>,
    ): LocalAnswer<List<Place>> {
        val pack = packsCovering(at)
        storeCovering(at)?.let { s ->
            val found = s.byFilter(filter, box).map { it.place }.filter(keep)
            // A region on the phone answers fully; a pack holding the place too is asked only when the store has none.
            if (found.isNotEmpty() || pack == null) return LocalAnswer(order(found), LocalOrigin.STORE)
        }
        if (pack != null) return LocalAnswer(order(packPlaces(pack, box) { filter.matches(it) }.filter(keep)), LocalOrigin.PACK)
        val got = askOverpass(filter, box)
        return got.copy(value = order(got.value.filter(keep)))
    }

    /** The chains trading in [box] (shops, eateries, banks, fuel, hotels), most branches first, at most [limit]. */
    suspend fun brands(box: BBox, limit: Int = DEFAULT_LIMIT): LocalAnswer<List<LocalBrand>> = withContext(Dispatchers.IO) {
        val centre = box.center
        val pack = packsCovering(centre)
        val answer = storeCovering(centre)?.let { s ->
            s.inBox(box).filter { it.brand != null && isTrade(it.tags) }.map { it.place }
                .takeIf { it.isNotEmpty() || pack == null }?.let { LocalAnswer(it, LocalOrigin.STORE) }
        } ?: pack?.let { f -> LocalAnswer(packPlaces(f, box, ::isTrade), LocalOrigin.PACK) }
            ?: askOverpass(TagFilter.Or(listOf(TagFilter.Has("brand"), TagFilter.Has("brand:wikidata"))), box)
        val byBrand = LinkedHashMap<String, MutableList<Place>>()
        val brands = HashMap<String, Brand>()
        for (p in answer.value) {
            if (!isTrade(p.tags) || p.location !in box) continue
            val b = Brands.forTags(p.tags) ?: continue
            brands[b.id] = b
            byBrand.getOrPut(b.id) { ArrayList() } += p
        }
        val chains = byBrand.map { (id, list) -> LocalBrand(brands.getValue(id), nearest(list, centre, Int.MAX_VALUE)) }
            .sortedWith(compareByDescending<LocalBrand> { it.branches.size }.thenBy { Geo.distance(centre, it.branches.first().location) })
        LocalAnswer(chains.take(limit), answer.origin, answer.error)
    }

    /** The branches of brand [id] (a name-suggestion-index id) within [radiusM] of [centre], nearest first. */
    suspend fun branches(id: String, centre: LatLon, radiusM: Double): LocalAnswer<List<Place>> = withContext(Dispatchers.IO) {
        val box = BBox.around(centre, radiusM)
        val within = { p: Place -> Geo.distance(centre, p.location) <= radiusM }
        val ofBrand = { t: Map<String, String> -> isTrade(t) && Brands.forTags(t)?.id == id }
        val pack = packsCovering(centre)
        storeCovering(centre)?.let { s ->
            val found = s.byBrand(listOf(id), box).map { it.place }.filter(within)
            if (found.isNotEmpty() || pack == null) return@withContext LocalAnswer(nearest(found, centre, Int.MAX_VALUE), LocalOrigin.STORE)
        }
        if (pack != null) return@withContext LocalAnswer(nearest(packPlaces(pack, box, ofBrand).filter(within), centre, Int.MAX_VALUE), LocalOrigin.PACK)
        val brand = Brands.byId(id)
        val filter = brand?.wikidata?.let { TagFilter.Equals("brand:wikidata", it) } ?: brand?.let { TagFilter.Equals("brand", it.brand) }
            ?: return@withContext LocalAnswer(emptyList(), null)
        val got = askOverpass(filter, box)
        LocalAnswer(nearest(got.value.filter { ofBrand(it.tags) && within(it) }, centre, Int.MAX_VALUE), got.origin, got.error)
    }

    /**
     * The places in [box] a passer-by could be asked about (named, of a category, with every tag, so only what is
     * truly unmapped is asked): from a region's store, else the packs, else Overpass online. Never from map labels.
     */
    suspend fun quests(box: BBox): LocalAnswer<List<Place>> = withContext(Dispatchers.IO) {
        val centre = box.center
        val askable = { p: Place -> p.id != null && p.tags["name"] != null && Categories.of(p.tags) != null && p.location in box }
        val pack = packsCovering(centre)
        storeCovering(centre)?.let { s ->
            val found = s.inBox(box).map { it.place }.filter(askable)
            if (found.isNotEmpty() || pack == null) return@withContext LocalAnswer(found, LocalOrigin.STORE)
        }
        pack?.let { f -> return@withContext LocalAnswer(packPlaces(f, box) { "name" in it }.filter(askable), LocalOrigin.PACK) }
        val op = overpass()?.takeIf { isOnline() } ?: return@withContext LocalAnswer(emptyList(), null)
        askOnline {
            val bbox = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", box.south, box.west, box.north, box.east)
            op.places("[out:json][timeout:25];nwr[name][~\"^(amenity|shop|tourism|leisure|craft)$\"~\".\"]($bbox);out tags center 300;").filter(askable)
        }
    }

    // ---- Where ----------------------------------------------------------------------------------------------------

    /**
     * The address at [at], offline: from the packs, the building whose outline holds the point (its own address, or the
     * nearest address point inside it), else the nearest address within [HOUSE_M], with the street within [STREET_M]
     * when no house is near; else a region's address table. The quarter and town come from the place areas holding
     * the point, a region's place records or the nearest place labels, the town also from the address's own city.
     * Null when nothing on the phone covers the point.
     */
    fun address(at: LatLon): LocalAddress? {
        val store = storeCovering(at)
        packsCovering(at)?.let { f -> fromPacks(f, at, store)?.let { return it } }
        return store?.let { fromStore(it, at) }
    }

    private fun fromPacks(f: PackFeatures, at: LatLon, store: PlaceSource?): LocalAddress? {
        val house = f.addressAt(at, HOUSE_M)
        val street = house?.street ?: f.streetName(at, null, STREET_M)
        val t = house?.element?.tags.orEmpty()
        val where = whereIs(f, at, store, t["addr:city"])
        if (street == null && where.first == null) return null
        return LocalAddress(
            street, house?.housenumber, t["addr:postcode"], where.first, where.second,
            house?.at, house?.let { PackFeatures.osmId(it.element) }, house?.distance, LocalOrigin.PACK,
        )
    }

    private fun fromStore(s: PlaceSource, at: LatLon): LocalAddress? {
        val found = storesOf(s).mapNotNull { st -> st.nearestAddress(at, HOUSE_M) }.minByOrNull { it.distanceTo(at) } ?: return null
        val t = found.tags
        val (quarter, town) = placeNames(s, at)
        return LocalAddress(
            t["addr:street"] ?: t["addr:place"], t["addr:housenumber"], t["addr:postcode"], quarter, town ?: t["addr:city"],
            found.location, found.id, found.distanceTo(at), LocalOrigin.STORE,
        )
    }

    /**
     * The quarter and town of [at]: the place areas holding it (packs that carry them), else a region store's place
     * records, else the nearest place labels in the packs (looked for in growing boxes, so a dense pack is read only as
     * far as the nearest label); the town also from the address's own city.
     */
    private fun whereIs(f: PackFeatures, at: LatLon, store: PlaceSource?, city: String?): Pair<String?, String?> {
        val held = f.containing(at) { it.tag("place") != null && it.tag("name") != null }
        var quarter = held.firstOrNull { it.element.tag("place") in QUARTERS }?.element?.tag("name")
        var town = held.firstOrNull { it.element.tag("place") in TOWNS }?.element?.tag("name")
        if ((quarter == null || town == null) && store != null) {
            val (q, t) = placeNames(store, at)
            quarter = quarter ?: q
            town = town ?: t
        }
        if (quarter == null) quarter = nearestLabel(f, at, QUARTERS, QUARTER_M)
        if (town == null) town = city ?: nearestLabel(f, at, TOWNS, TOWN_LABEL_M)
        return quarter to town
    }

    private fun nearestLabel(f: PackFeatures, at: LatLon, kinds: Set<String>, withinM: Double): String? {
        var r = LABEL_FIRST_M
        while (true) {
            val reach = minOf(r, withinM)
            val found = f.elements(BBox.around(at, reach)) { it.type == ElementType.NODE && it.tag("place") in kinds && it.tag("name") != null }
                .map { it to Geo.distance(at.lat, at.lon, it.lat, it.lon) }
                .filter { it.second <= reach }
                .minByOrNull { it.second }
            // Nearer than the reach is nearest of all: anything nearer would be inside this box too.
            if (found != null) return found.first.tag("name")
            if (reach >= withinM) return null
            r *= 3
        }
    }

    private fun placeNames(s: PlaceSource, at: LatLon): Pair<String?, String?> {
        fun nearestOf(kinds: Set<String>, withinM: Double): String? = kinds.flatMap { k -> s.byTag("place", k, BBox.around(at, withinM)) }
            .filter { it.tags["name"] != null && it.distanceTo(at) <= withinM }
            .minByOrNull { it.distanceTo(at) }?.tags?.get("name")
        return nearestOf(QUARTERS, QUARTER_M) to nearestOf(TOWNS, TOWN_M)
    }

    /**
     * The name of the street (or canal, for a boat) at [at] within [withinM]: the one running along [bearing] wins, a
     * street merely crossing there only when nothing runs along; with no bearing, the nearest. From the packs; null
     * where none covers the point. In [language] when the street's name is mapped in it.
     */
    fun wayName(at: LatLon, bearing: Double?, withinM: Double = PackFeatures.NEAR_M, language: String? = null): String? =
        packsCovering(at)?.streetName(at, bearing, withinM, language)

    /** [wayName] as the router's street namer (BRouter's data has no names), for the steps of a route. */
    fun wayNamer(language: String? = null): WayNamer = WayNamer { at, bearing -> wayName(at, bearing, PackFeatures.NEAR_M, language) }

    /** The named streets and waterways meeting [box], from the packs. */
    fun streets(box: BBox): List<LocalStreet> = packsMeeting(box)?.streets(box).orEmpty().map { s ->
        LocalStreet(
            PackFeatures.osmId(s.element), s.name, s.ref, s.water, s.element.tagsOrEmpty,
            s.lines.map { g -> (0 until g.size).filter { !g.isNull(it) }.map { LatLon(g.lat[it], g.lon[it]) } },
        )
    }

    /** The bridges over [route], in order along it. */
    fun bridges(route: Route): List<LocalBridge> = bridges(route.points)

    /**
     * The bridges [line] passes, in order along it, from the packs: every bridge way crossing it and every bridge
     * outline it passes through, the ways of one bridge (a road and its pavements, within [SAME_M] along) as one.
     */
    fun bridges(line: List<LatLon>): List<LocalBridge> {
        if (line.size < 2) return emptyList()
        val f = packsMeeting(BBox.of(line) ?: return emptyList()) ?: return emptyList()
        val crossings = f.bridges(line)
        val out = ArrayList<LocalBridge>()
        var i = 0
        while (i < crossings.size) {
            var j = i + 1
            while (j < crossings.size && crossings[j].along - crossings[i].along < SAME_M) j++
            val group = crossings.subList(i, j)
            // The outline's tags first (it is the bridge itself), then each way's.
            val tags = group.sortedBy { if (it.element.tag("man_made") == "bridge") 0 else 1 }.map { it.element.tagsOrEmpty }
            fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> tags.firstNotNullOfOrNull { it[k] } }
            val mid = group[group.size / 2]
            out += LocalBridge(
                along = mid.along,
                at = mid.at,
                name = first("seamark:name", "bridge:name") ?: tags.firstNotNullOfOrNull { t -> t["name"]?.takeIf { t["man_made"] == "bridge" || "highway" !in t } }
                    ?: first("name"),
                clearance = first("seamark:bridge:clearance_height", "seamark:bridge:clearance_height_closed", "maxheight:physical")?.let(OsmUnits::metres),
                maxheight = first("maxheight")?.let(OsmUnits::metres),
                width = first("seamark:bridge:clearance_width")?.let(OsmUnits::metres),
                ids = group.map { PackFeatures.osmId(it.element) }.distinct(),
            )
            i = j
        }
        return out
    }

    /**
     * What is at [at] for "What's here": its address, the place whose outline holds it (else a named place within
     * [SPOT_POINT_M]), and the named places Atlas lists within [radiusM], measured to their outlines, nearest first.
     */
    suspend fun whatsHere(at: LatLon, radiusM: Double = NEARBY_M): LocalHere = withContext(Dispatchers.IO) {
        val address = address(at)
        val box = BBox.around(at, radiusM)
        val pack = packsCovering(at)
        val store = storeCovering(at)
        val listed = { t: Map<String, String> -> t["name"] != null && Categories.of(t) != null }
        // The places around with their distance to the point (to their outline when they have one).
        val (around, origin) = when {
            store != null -> store.inBox(box).filter { it.category != null && it.tags["name"] != null }
                .map { it.place to it.distanceTo(at) } to LocalOrigin.STORE
            pack != null -> pack.elements(box) { e -> e.tags?.let { listed(it) && keeps(e, it) } == true }
                .mapNotNull { e -> PackFeatures.place(e)?.let { it to PackFeatures.distance(e, at) } } to LocalOrigin.PACK
            else -> {
                val got = askOverpass(TagFilter.Has("name"), box)
                got.value.filter { listed(it.tags) }.map { it to Geo.distance(at, it.location) } to got.origin
            }
        }
        val near = around.filter { it.second <= radiusM }.sortedBy { it.second }
        val spot = pack?.let { f ->
            f.containing(at) { e -> e.tag("name") != null || e.tag("building") != null }
                .firstOrNull { it.element.tag("place") == null && it.element.tag("boundary") == null }
                ?.let { PackFeatures.place(it.element) }
        } ?: store?.let { s ->
            s.inBox(BBox.around(at, SPOT_POINT_M))
                .filter { r -> r.place.bbox?.let { b -> b.width > 0 && b.height > 0 && at in b } == true && (r.tags["name"] != null || r.tags["building"] != null) }
                .minByOrNull { r -> r.place.bbox!!.let { it.width * it.height } }?.place
        } ?: near.firstOrNull { it.second <= SPOT_POINT_M }?.first
        val nearby = near.map { it.first }.filter { it.key != spot?.key }.distinctBy { it.key }.take(NEARBY_MAX)
        LocalHere(address, spot, nearby, origin ?: address?.origin)
    }

    // ---- Queries --------------------------------------------------------------------------------------------------

    /**
     * Runs [query] ([app.atlas.search.QueryParser]'s) through the offline evaluator: over the regions' stores where they
     * cover the searcher, else over the packs' places around ([QUERY_M], or the query's own area). Online search is the
     * search engine's.
     */
    fun query(query: Query, ctx: EvalContext): LocalAnswer<QueryResult> {
        val area = areaOf(query) ?: ctx.viewport ?: ctx.location?.let { BBox.around(it, QUERY_M) }
        val at = ctx.location ?: area?.center ?: return LocalAnswer(QueryResult(emptyList(), 0, 0, 0), null)
        storeCovering(at)?.let { return LocalAnswer(OfflineEvaluator(it).run(query, ctx), LocalOrigin.STORE) }
        val f = packsCovering(at) ?: return LocalAnswer(QueryResult(emptyList(), 0, 0, 0), null)
        val places = MemoryPlaces(packPlaces(f, area ?: BBox.around(at, QUERY_M)) { true })
        return LocalAnswer(OfflineEvaluator(places).run(query, ctx), LocalOrigin.PACK)
    }

    private fun areaOf(q: Query): BBox? {
        var box: BBox? = null
        q.walk { part ->
            val b = when (part) {
                is Query.Within -> part.center?.let { BBox.around(it, part.radius) }
                is Query.InArea -> part.bbox
                else -> null
            }
            if (b != null) box = box?.union(b) ?: b
        }
        return box?.takeIf { abs(it.width) < MAX_QUERY_DEG && abs(it.height) < MAX_QUERY_DEG }
    }

    override fun close() = onClose()

    // ---- Layers -----------------------------------------------------------------------------------------------------

    private fun storeCovering(p: LatLon): PlaceSource? = stores()?.takeIf { s -> s.size > 0 && s.bounds?.let { p in it } == true }

    private fun packsCovering(p: LatLon): PackFeatures? = packs()?.takeIf { it.covers(p) }

    private fun packsMeeting(box: BBox): PackFeatures? = packs()?.takeIf { f -> f.sources.any { it.coverage.intersects(box) } }

    private fun storesOf(s: PlaceSource): List<PlaceStore> = when (s) {
        is PlaceStore -> listOf(s)
        is Regions -> s.stores.flatMap { storesOf(it) }
        else -> emptyList()
    }

    /** The places of the packs in [box] whose tags [accept] keeps: what a region's store would hold, with every tag. */
    private fun packPlaces(f: PackFeatures, box: BBox, accept: (Map<String, String>) -> Boolean): List<Place> =
        f.elements(box) { e -> e.tags?.let { keeps(e, it) && accept(it) } == true }.mapNotNull { PackFeatures.place(it) }

    /** Places in [box] passing [filter] from Overpass, when the phone is online; nothing (and no origin) when not. */
    private suspend fun askOverpass(filter: TagFilter, box: BBox): LocalAnswer<List<Place>> {
        val op = overpass()?.takeIf { isOnline() } ?: return LocalAnswer(emptyList(), null)
        val selectors = OverpassSelectors.of(filter) ?: return LocalAnswer(emptyList(), null, "the filter cannot be asked of Overpass")
        return askOnline {
            op.places(OverpassQl.places(selectors, OverpassArea.Box(box), timeoutSeconds = 25)).filter { filter.matches(it.tags) && Categories.isPlace(it.tags) }
        }
    }

    private suspend fun askOnline(ask: suspend () -> List<Place>): LocalAnswer<List<Place>> = try {
        LocalAnswer(ask(), LocalOrigin.ONLINE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        LocalAnswer(emptyList(), LocalOrigin.ONLINE, e.message ?: "offline")
    } catch (e: Exception) {
        // An answer that does not parse (a captive portal's page, a broken proxy) is a failure to report too.
        LocalAnswer(emptyList(), LocalOrigin.ONLINE, e.message ?: e.toString())
    }

    companion object {
        const val DEFAULT_LIMIT = 200

        /** How far a house number may be from the point asked about (metres). */
        const val HOUSE_M = 25.0

        /** How far the street may be when no house is near (metres). */
        const val STREET_M = 60.0

        /** What's here: how far the places around reach, and how near a place's point must be to be the spot. */
        const val NEARBY_M = 150.0
        const val SPOT_POINT_M = 12.0
        private const val NEARBY_MAX = 8

        /** Two bridge crossings closer than this along a route are one bridge (metres). */
        const val SAME_M = 15.0

        /** The area a query over the packs looks in around the searcher (metres). */
        const val QUERY_M = 1_000.0
        private const val MAX_QUERY_DEG = 0.2

        private const val QUARTER_M = 1_500.0
        private const val TOWN_M = 15_000.0

        /** How far the packs are searched for a town's label when nothing else names the town (metres). */
        private const val TOWN_LABEL_M = 3_000.0
        private const val LABEL_FIRST_M = 150.0
        private val QUARTERS = setOf("neighbourhood", "quarter", "suburb")
        private val TOWNS = setOf("city", "town", "village", "hamlet")

        /** The kinds of amenity a chain can run, beside shops and hotels. */
        private val TRADES = setOf(
            "restaurant", "fast_food", "cafe", "bar", "pub", "ice_cream", "pharmacy", "bank", "fuel", "charging_station",
            "car_rental", "bicycle_rental", "cinema", "post_office",
        )
        private val LODGING = setOf("hotel", "hostel", "motel")

        /** True for a place a chain could run: a shop, an eatery or another trade, or a hotel. */
        fun isTrade(tags: Map<String, String>): Boolean = "shop" in tags || tags["amenity"] in TRADES || tags["tourism"] in LODGING

        /**
         * True when a region's store would keep the element as a place ([Selection]), not as a bare address: a point of
         * interest, a named feature, a stop. Bare addresses are [address]'s.
         */
        fun keeps(e: OsmElement, tags: Map<String, String>): Boolean =
            Selection.keeps(tags, isRelation = e.type == ElementType.RELATION) && !onlyAddress(tags)

        /** Tags that say no more than an address on an ordinary building (the place store's address points). */
        fun onlyAddress(tags: Map<String, String>): Boolean {
            val hn = tags["addr:housenumber"]?.trim() ?: return false
            if (hn.isEmpty() || hn.length > 24) return false
            for ((k, v) in tags) {
                when {
                    k.startsWith("addr:") || k in STRUCTURE -> {}
                    k == "building" -> if (v !in ORDINARY_BUILDINGS) return false
                    k.startsWith("building:") && k != "building:part" && k != "building:use" -> {}
                    k.startsWith("roof:") || dropped(k) -> {}
                    k == "type" && v == "multipolygon" -> {}
                    else -> return false
                }
            }
            return true
        }

        /** Bookkeeping a store drops before it decides (sources, notes, import ids, registry numbers, survey dates). */
        private fun dropped(k: String): Boolean =
            k in DROP || DROP_PREFIXES.any { k.startsWith(it) } || k.startsWith("check_date") || (k.startsWith("ref:") && k !in REF_KEEP)

        private val DROP = setOf(
            "source", "note", "fixme", "FIXME", "created_by", "comment", "history", "import_uuid", "odbl", "converted_by",
            "is_in", "attribution", "source_ref",
        )
        private val DROP_PREFIXES = listOf(
            "source:", "note:", "fixme:", "FIXME:", "is_in:", "odbl:", "tiger:", "gnis:", "NHD:", "nhd:", "osak:", "lacounty:",
            "nysgissam:", "massgis:", "geobase:", "canvec:", "KSJ2:", "yh:", "linz:", "mvdgis:", "import:", "bag:",
        )
        private val REF_KEEP = setOf("ref:IFOPT", "ref:EU:EVSE", "ref:ibnr", "ref:uic", "ref:station", "ref:mobile", "ref:vatin")

        private val STRUCTURE = setOf("building:levels", "building:flats", "height", "min_height", "start_date", "entrance", "levels", "roof:levels")

        private val ORDINARY_BUILDINGS = setOf(
            "yes", "house", "residential", "detached", "semidetached_house", "terrace", "apartments", "bungalow", "farm",
            "garage", "garages", "shed", "hut", "cabin", "static_caravan", "commercial", "retail", "industrial", "warehouse",
            "office", "barn", "farm_auxiliary", "outbuilding", "houseboat", "dormitory", "allotment_house", "construction",
            "carport", "greenhouse", "stable", "service", "semi", "semidetached", "annexe",
        )

        fun nearest(places: List<Place>, centre: LatLon, limit: Int): List<Place> =
            places.distinctBy { it.key }.sortedBy { Geo.distance(centre, it.location) }.take(limit)
    }
}

/**
 * The `.atlas` packs on the phone as one [PackFeatures]: each file opened once and reopened when it changes (a newer
 * download, a pack built here), newest data first so an element two packs share comes from the fresher one. Readers
 * of a replaced file are closed a while later, once nothing can still be reading them.
 */
class PackShelf(private val files: () -> List<File>, private val clock: () -> Long = System::currentTimeMillis) : () -> PackFeatures?, Closeable {
    private class Opened(val stamp: String, val reader: PackReader)

    private val opened = HashMap<String, Opened>()
    private val retired = ArrayList<Pair<Long, PackReader>>()
    private var key: String? = null
    private var current: PackFeatures? = null

    override fun invoke(): PackFeatures? = synchronized(this) {
        val list = runCatching { files() }.getOrDefault(emptyList()).filter { it.isFile }
        val stamps = list.map { it.path to "${it.length()}-${it.lastModified()}" }
        val k = stamps.joinToString("|") { "${it.first}:${it.second}" }
        if (k == key) return current
        val now = clock()
        val keep = HashMap<String, Opened>()
        for ((path, stamp) in stamps) {
            val old = opened[path]
            if (old != null && old.stamp == stamp) {
                keep[path] = old
                continue
            }
            // A file that does not open (a download still being placed) is left out until it does.
            runCatching { PackReader.open(File(path)) }.getOrNull()?.let { keep[path] = Opened(stamp, it) }
        }
        // Readers of files gone or replaced may still be mid-query on another thread: closed a while later.
        for (o in opened.values) if (keep.values.none { it === o }) retired += now to o.reader
        opened.clear()
        opened.putAll(keep)
        retired.removeAll { (at, r) -> (now - at > RETIRE_MS).also { if (it) runCatching { r.close() } } }
        val sources = opened.values.map { it.reader }
            .sortedWith(compareByDescending<PackReader> { it.manifest.dataTime ?: "" }.thenBy { it.manifest.bbox.let { b -> b.width * b.height } })
            .map { PackBoxes(it) }
        key = k
        current = if (sources.isEmpty()) null else PackFeatures(sources)
        current
    }

    override fun close() = synchronized(this) {
        opened.values.forEach { runCatching { it.reader.close() } }
        retired.forEach { runCatching { it.second.close() } }
        opened.clear()
        retired.clear()
        key = null
        current = null
    }

    private companion object {
        const val RETIRE_MS = 30_000L
    }
}
