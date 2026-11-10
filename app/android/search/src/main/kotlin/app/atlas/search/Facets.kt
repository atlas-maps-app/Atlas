package app.atlas.search

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.BrandHours
import app.atlas.places.Cuisine
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.Facts
import app.atlas.places.Fields
import app.atlas.places.Freshness
import app.atlas.places.InferredHours
import app.atlas.places.Kinds
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.places.Presets
import app.atlas.places.Symbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * How a facet narrows: by opening hours, by price, by when its tags were last checked, by a yes/no tag (its states:
 * yes, in part, only, no), by a tag's values (cuisine, street, brand), or by whether a tag is mapped at all (a phone
 * number: every value is different).
 */
enum class FacetKind { OPEN, PRICE, CHECKED, FLAG, VALUES, MAPPED }

/** Where a facet stands among all filters, in this order. */
enum class FacetSection { WHEN, KIND, FOOD, ACCESS, COST, PAYMENT, SERVICES, CONTACT, ADDRESS, NAMES, MAPPING, OTHER }

/**
 * One value a facet offers. [id] is what a [FacetSelection] holds: a tag's value ("pizza"), a state ("~yes",
 * "~limited", "~only", "~no"), "*" (mapped), "!" (not mapped), or a facet's own ("now", "free", "band:2-5").
 * [count] is how many of the places it counts over have it; [unconfirmed] how many of those only by hours worked out
 * from their brand's other branches; [elsewhere] how many places beyond the search's area have it.
 */
data class FacetValue(
    val id: String,
    val label: String,
    /** The words for a chip of its own ("Step-free", "Vegan", "Open now"). */
    val chip: String,
    val symbol: String?,
    val count: Int,
    val selected: Boolean,
    val unconfirmed: Int = 0,
    val elsewhere: Int = 0,
)

/**
 * A filter made of the places' own tags: one per tag key the places have (a few keys share one: the price's), with
 * its values and how many places have each. [over] is how many places it counts over: those that every other chosen
 * facet keeps, so choosing a value of this facet can only add to it.
 */
data class Facet(
    val id: String,
    val kind: FacetKind,
    val section: FacetSection,
    val label: String,
    val symbol: String,
    /** The OpenStreetMap keys it reads. */
    val keys: List<String>,
    val values: List<FacetValue>,
    val mapped: Int,
    val over: Int,
    val rank: Double,
) {
    val selected: Boolean get() = values.any { it.selected }

    fun value(id: String): FacetValue? = values.firstOrNull { it.id == id }
}

/** The sections whose facets make chips: what a place is like, not what it is called or where it is mapped. */
private val CHIP_SECTIONS = setOf(FacetSection.WHEN, FacetSection.FOOD, FacetSection.ACCESS, FacetSection.COST, FacetSection.SERVICES, FacetSection.PAYMENT)

/**
 * The facets of a search's places, best first, and the places the chosen values keep; [overHours] are the places the
 * hours' facet counts over, those every other chosen value keeps (the time picker's curve counts them).
 */
data class FacetSet(val facets: List<Facet>, val kept: List<PlaceRecord>, val total: Int, val overHours: List<PlaceRecord> = kept) {
    fun byId(id: String): Facet? = facets.firstOrNull { it.id == id }

    /** The facets of [section], best first. */
    fun section(section: FacetSection): List<Facet> = facets.filter { it.section == section }

    /**
     * The values worth a chip in the row under the search: the chosen ones, then open now, then the best value of each
     * of the best facets that narrows the places (keeps some, not all), the commonest first among equals.
     */
    fun chips(max: Int = 6): List<Pair<Facet, FacetValue>> {
        val out = ArrayList<Pair<Facet, FacetValue>>()
        for (f in facets) for (v in f.values) if (v.selected) out += f to v
        fun narrows(f: Facet, v: FacetValue) = v.count in 1 until f.over
        byId(Facets.OPEN)?.let { f -> f.value("now")?.takeIf { !it.selected && narrows(f, it) }?.let { out += f to it } }
        for (f in facets) {
            if (out.size >= max) break
            if (f.selected || f.id == Facets.OPEN || f.section !in CHIP_SECTIONS || f.kind == FacetKind.MAPPED) continue
            val v = f.values.filter { it.id != Facets.MISSING && narrows(f, it) }.let { vs ->
                if (f.kind == FacetKind.FLAG) vs.firstOrNull { it.id == "~yes" } else vs.maxByOrNull { it.count }
            } ?: continue
            out += f to v
        }
        return out.take(max)
    }
}

/**
 * The values chosen per facet: a place passes a facet when it has any of its chosen values, and the search keeps
 * the places that pass every facet. Written as ids "facet=value" ("cuisine=pizza", "@open=now", "wheelchair=~yes"),
 * which is how sticky filters are kept.
 */
data class FacetSelection(val chosen: Map<String, Set<String>> = emptyMap()) {
    val isEmpty: Boolean get() = chosen.isEmpty()

    fun has(facet: String, value: String): Boolean = chosen[facet]?.contains(value) == true

    fun toggle(facet: String, value: String): FacetSelection {
        val now = chosen[facet].orEmpty().let { if (value in it) it - value else it + value }
        return FacetSelection(if (now.isEmpty()) chosen - facet else chosen + (facet to now))
    }

    /** Only [value] chosen of [facet] (choosing one time to be open at replaces another). */
    fun only(facet: String, value: String): FacetSelection = FacetSelection(chosen + (facet to setOf(value)))

    fun clear(facet: String): FacetSelection = FacetSelection(chosen - facet)

    fun ids(): List<String> = chosen.flatMap { (f, vs) -> vs.map { "$f=$it" } }

    operator fun plus(other: FacetSelection): FacetSelection {
        val m = HashMap(chosen)
        for ((f, vs) in other.chosen) m[f] = m[f].orEmpty() + vs
        return FacetSelection(m)
    }

    companion object {
        val NONE = FacetSelection()

        fun of(ids: Collection<String>): FacetSelection {
            val m = LinkedHashMap<String, MutableSet<String>>()
            for (id in ids) {
                val i = id.indexOf('=').takeIf { it > 0 } ?: continue
                m.getOrPut(id.substring(0, i)) { LinkedHashSet() }.add(id.substring(i + 1))
            }
            return FacetSelection(m)
        }
    }
}

/** Where a place's hours come from: its own `opening_hours`, its brand's other branches, or the places of its kind around it. */
enum class HoursFrom { OWN, BRAND, KIND }

/** A place's hours: its own, or worked out from its brand's other branches or its kind around it (never confirmed). */
data class KnownHours(val hours: OpeningHours, val from: HoursFrom) {
    val confirmed: Boolean get() = from == HoursFrom.OWN
}

/**
 * The hours of a search's places: each place's own; else, for a branch of a brand that maps none, the hours its
 * brand's branches among [branches] keep ([BrandHours]), one of them enough ([BrandHours.MIN_SIBLINGS]); else the
 * hours the places of its kind around it keep (the
 * owner, 4 November: "Many places have no opening times but they can be guessed?"). A kind's guess is worked out as a
 * brand's is, from the nearest [KIND_SAMPLE] of [branches] of the same preset (else of the preset it is a kind of)
 * that map readable hours within [KIND_REACH_M], once per kind and per cell of [CELL_DEG] degrees; at least
 * [BrandHours.MIN_BRANCHES] of them, and [KIND_SHARE] of the kind's places within reach, must map hours, or the kind
 * says nothing. Places usually open (a park, a bench) and places whose own hours do not read are guessed none. Brand
 * and kind guesses are never confirmed. Values are read once; it may be read from several threads.
 */
class HoursBook(branches: Collection<PlaceRecord>) {
    private class Read(val hours: OpeningHours?)
    private class Guess(val hours: InferredHours?)
    private class Week(val days: List<Pair<Int, Int>?>?)

    private val all: Collection<PlaceRecord> = branches
    private val parsed = ConcurrentHashMap<String, Read>()
    private val weeks = ConcurrentHashMap<String, Week>()
    private val inferred: Map<String, InferredHours> = branches.filter { it.brand != null }.groupBy { it.brand!! }
        .mapNotNull { (brand, list) -> BrandHours.infer(list.mapNotNull { it.tags["opening_hours"] })?.let { brand to it } }.toMap()

    /** The places of one kind by cell, and how many of them map hours of their own. */
    private class Kind(val byCell: HashMap<Long, MutableList<PlaceRecord>> = HashMap(), var mapped: Int = 0)

    /** Per kind (a preset, and the preset it is a kind of), its places, read the first time a guess is asked. */
    private val kinds: Map<String, Kind> by lazy {
        val out = HashMap<String, Kind>()
        for (r in all) for (k in kindsOf(r)) {
            val kind = out.getOrPut(k) { Kind() }
            kind.byCell.getOrPut(cellOf(r.location.lat, r.location.lon)) { ArrayList() } += r
            if ("opening_hours" in r.tags) kind.mapped++
        }
        out
    }
    private val guesses = ConcurrentHashMap<String, Guess>()

    fun of(r: PlaceRecord): KnownHours? {
        r.tags["opening_hours"]?.let { raw -> return parse(raw)?.let { KnownHours(it, HoursFrom.OWN) } }
        r.brand?.let { inferred[it] }?.let { return KnownHours(it.hours, HoursFrom.BRAND) }
        return ofKind(r)?.let { KnownHours(it.hours, HoursFrom.KIND) }
    }

    /** The hours worked out for [brand]'s branches, when there were enough of them. */
    fun ofBrand(brand: String): OpeningHours? = inferred[brand]?.hours

    /** The same with how many branches they come from and how many of those keep exactly them. */
    fun inferred(brand: String): InferredHours? = inferred[brand]

    /** The hours the places of [r]'s kind around it keep, for a place that maps none; null when they say nothing. */
    fun ofKind(r: PlaceRecord): InferredHours? {
        if ("opening_hours" in r.tags || OfflineEvaluator.usuallyOpen(r)) return null
        val cell = cellOf(r.location.lat, r.location.lon)
        for (k in kindsOf(r)) guess(k, cell)?.let { return it }
        return null
    }

    private fun parse(raw: String): OpeningHours? = parsed.getOrPut(raw) { Read(OpeningHours.parseOrNull(raw)) }.hours

    private fun guess(kind: String, cell: Long): InferredHours? = guesses.getOrPut("$kind|$cell") { Guess(work(kind, cell)) }.hours

    /** The hours of [kind] around the middle of [cell], from its nearest places that map their own. */
    private fun work(kind: String, cell: Long): InferredHours? {
        val byCell = kinds[kind]?.takeIf { it.mapped >= BrandHours.MIN_BRANCHES }?.byCell ?: return null
        val lat = ((cell shr 32).toInt() + 0.5) * CELL_DEG
        val lon = (cell.toInt() + 0.5) * CELL_DEG
        val middle = LatLon(lat, lon)
        val rows = ceil(KIND_REACH_M / CELL_M).toInt()
        val cols = ceil(KIND_REACH_M / (CELL_M * cos(Math.toRadians(lat)).coerceAtLeast(0.1))).toInt()
        val row = (cell shr 32).toInt()
        val col = cell.toInt()
        var around = 0
        val mapped = ArrayList<Pair<Double, OpeningHours>>()
        for (dr in -rows..rows) for (dc in -cols..cols) {
            val list = byCell[key(row + dr, col + dc)] ?: continue
            for (p in list) {
                val d = Geo.distance(middle, p.location)
                if (d > KIND_REACH_M) continue
                around++
                p.tags["opening_hours"]?.let(::parse)?.let { mapped += d to it }
            }
        }
        if (mapped.size < BrandHours.MIN_BRANCHES || mapped.size < around * KIND_SHARE) return null
        val nearest = mapped.sortedBy { it.first }.take(KIND_SAMPLE)
        return BrandHours.fromWeeks(nearest.mapNotNull { (_, h) -> weekOf(h) })
    }

    private fun weekOf(h: OpeningHours): List<Pair<Int, Int>?>? = weeks.getOrPut(h.raw) { Week(BrandHours.weekOf(h)) }.days

    companion object {
        val EMPTY = HoursBook(emptyList())

        /** How many of a kind's nearest places a guess is worked out from. */
        const val KIND_SAMPLE = 9

        /** How far from a place the places of its kind are taken to keep its hours, in metres. */
        const val KIND_REACH_M = 3_000.0

        /** The least share of a kind's places around that must map hours for the rest to be guessed. */
        const val KIND_SHARE = 0.3

        /** The side of a guess's cell, in degrees of latitude and longitude: guesses are made once per cell. */
        const val CELL_DEG = 0.01
        private const val CELL_M = CELL_DEG * 111_320.0

        /** A place's kinds, narrowest first: its preset, and the preset that is a kind of ("amenity/fast_food" for burgers). */
        private fun kindsOf(r: PlaceRecord): List<String> {
            val p = r.preset ?: return emptyList()
            val parent = p.substringBeforeLast('/', "").takeIf { '/' in it }
            return if (parent == null) listOf(p) else listOf(p, parent)
        }

        private fun cellOf(lat: Double, lon: Double): Long = key(floor(lat / CELL_DEG).toInt(), floor(lon / CELL_DEG).toInt())

        private fun key(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)
    }
}

/** What facets are read against: when it is, a time asked about, the language, and the places' hours. */
data class FacetContext(
    val now: LocalDateTime,
    val lang: String = "en",
    val hours: HoursBook = HoursBook.EMPTY,
    val opening: OpeningHoursContext = OpeningHoursContext.Default,
    /** A time the searcher asked about ("open Saturday 14:00"), offered as a value of the hours' facet. */
    val at: LocalDateTime? = null,
)

/**
 * Every tag of a search's places as a filter (the user, 3 November: "Every tag thats there for your search results in
 * your radius plus places everywhere is a possible filter and subcategory that shows a little number of how many from
 * your current search results have that tag. And update when you apply one tag. Open now and a specific opening time
 * you search for can be there too, prices, free and price ranges. Not a single tag a place has gets omitted,
 * anywhere."). Each key the places have makes a facet, but `opening_hours` (the hours' facet: open now, at the time
 * asked, tonight, late, 24/7, unknown) and `fee`, `charge` and `price` (the price's: free, paid, price bands). A
 * yes/no key offers its states, a key whose values repeat offers each value (a `;` list by its parts), a key whose
 * values are all different (a phone number) offers mapped and not mapped. Every facet but the hours' also counts the
 * places that do not map it, for the quests. Counts are disjunctive: a facet counts over the places every other
 * chosen facet keeps.
 */
object Facets {
    const val OPEN = "@open"
    const val PRICE = "@price"
    const val CHECKED = "@checked"

    /** The value of the places that do not map a facet. */
    const val MISSING = "!"

    /** What [key] is called in [lang] ("toilets": "Toilets", "internet_access": "Wi-Fi"), as its facet is. */
    fun label(key: String, lang: String): String = Labels.facet(key, lang)

    /** [key]'s symbol, as its facet's. */
    fun symbol(key: String): String = Labels.facetSymbol(key)

    /** The value of the places that map a key whose values are all different. */
    const val MAPPED = "*"

    /** Hours known, and none of the hours' values: counted, never offered. */
    private const val CLOSED = "closed"

    private val PRICE_KEYS = listOf("fee", "charge", "price")
    private const val HOURS_KEY = "opening_hours"

    /**
     * The facets of [places] with [selection] chosen, read in [context]. [elsewhere] are the same kind of places beyond
     * the search's area: their values are offered too, counted apart, so a value no place nearby has can be chosen.
     */
    fun of(
        places: List<PlaceRecord>,
        selection: FacetSelection = FacetSelection.NONE,
        context: FacetContext,
        elsewhere: List<PlaceRecord> = emptyList(),
    ): FacetSet {
        val kinds = kindsOf(places + elsewhere)
        val reader = Reader(kinds, context)
        val near = Tally(places, selection, reader)
        val far = if (elsewhere.isEmpty()) null else Tally(elsewhere, selection, reader)
        val ids = LinkedHashSet<String>().apply {
            addAll(near.facets)
            far?.let { addAll(it.facets) }
            addAll(selection.chosen.keys)
        }
        val facets = ids.mapNotNull { id -> build(id, near, far, selection, reader) }
            .sortedWith(compareByDescending<Facet> { it.rank }.thenBy { it.label })
        return FacetSet(facets, near.kept, places.size, near.overHours)
    }

    /** True when [r] passes [selection], read in [context] (the kinds of keys judged from [r] alone). */
    fun keeps(r: PlaceRecord, selection: FacetSelection, context: FacetContext): Boolean {
        if (selection.isEmpty) return true
        val reader = Reader(kindsOf(listOf(r)), context)
        val values = reader.values(r)
        return selection.chosen.all { (f, chosen) -> passes(values[f], chosen) }
    }

    /**
     * What each of [places] misses of [selection], read in [context] ([Miss], in the order the facets were chosen):
     * for the hours' facet when it opens or closed nearest the time asked, for the price's how many bands it is off,
     * for any other the value it lacks and whether its tags say nothing of it. A place that passes has none.
     */
    fun misses(places: List<PlaceRecord>, selection: FacetSelection, context: FacetContext): List<List<Miss>> {
        if (selection.isEmpty) return places.map { emptyList() }
        val reader = Reader(kindsOf(places), context)
        return places.map { r ->
            val values = reader.values(r)
            selection.chosen.mapNotNull { (f, chosen) -> if (passes(values[f], chosen)) null else miss(f, chosen, values[f], r, reader) }
        }
    }

    private fun miss(facet: String, chosen: Set<String>, has: Set<String>?, r: PlaceRecord, reader: Reader): Miss {
        when (facet) {
            OPEN -> reader.openMiss(r, chosen)?.let { return it }
            PRICE -> priceMiss(chosen, has)?.let { return it }
        }
        val lang = reader.ctx.lang
        val kind = when (facet) {
            OPEN -> FacetKind.OPEN
            PRICE -> FacetKind.PRICE
            CHECKED -> FacetKind.CHECKED
            else -> reader.kinds[facet] ?: if (chosen.any { it.startsWith("~") }) FacetKind.FLAG else FacetKind.VALUES
        }
        val v = chosen.firstOrNull { it != MISSING } ?: chosen.first()
        val chip = Labels.value(facet, kind, v, Labels.facet(facet, lang), lang).second
        return Miss.Lacks(facet, chip, Labels.valueSymbol(facet, kind, v) ?: Labels.facetSymbol(facet), unsaid = has.isNullOrEmpty())
    }

    /** How many price bands [has] is off the dearest of [chosen] (free the cheapest); null when either says no band. */
    private fun priceMiss(chosen: Set<String>, has: Set<String>?): Miss? {
        val asked = chosen.mapNotNull(::priceStep)
        val own = has?.mapNotNull(::priceStep)?.maxOrNull() ?: return null
        if (asked.isEmpty()) return null
        val steps = if (own > asked.max()) own - asked.max() else own - asked.min()
        return if (steps == 0) null else Miss.Price(steps)
    }

    private fun priceStep(v: String): Int? = if (v == "free") 0
    else BANDS.indexOfFirst { v == "band:${fmt(it.first)}-${fmt(it.second)}" }.takeIf { it >= 0 }?.plus(1)

    // ------------------------------------------------------------------------------------------------- reading tags

    /** How each key of [places] is offered: a yes/no flag, its values, or mapped or not. */
    private fun kindsOf(places: List<PlaceRecord>): Map<String, FacetKind> {
        val mapped = HashMap<String, Int>()
        val states = HashMap<String, Int>()
        val distinct = HashMap<String, MutableSet<String>>()
        for (r in places) for ((k, v) in r.tags) {
            if (k == HOURS_KEY || k in PRICE_KEYS) continue
            mapped.merge(k, 1, Int::plus)
            if (Facts.stateOf(v) != null) states.merge(k, 1, Int::plus)
            val d = distinct.getOrPut(k) { HashSet() }
            if (d.size <= 64) for (p in parts(v)) d += p
        }
        return mapped.mapValues { (k, m) ->
            val d = distinct[k]?.size ?: 0
            when {
                k in VALUE_KEYS || VALUE_PREFIXES.any { k.startsWith(it) } -> FacetKind.VALUES
                k in UNIQUE_KEYS || UNIQUE_PREFIXES.any { k.startsWith(it) } -> FacetKind.MAPPED
                (states[k] ?: 0) * 5 >= m * 4 -> FacetKind.FLAG
                d > 12 && d * 2 > m -> FacetKind.MAPPED
                else -> FacetKind.VALUES
            }
        }
    }

    /** Reads the facet values of one place. */
    private class Reader(val kinds: Map<String, FacetKind>, val ctx: FacetContext) {
        private val today: LocalDate = ctx.now.toLocalDate()
        private val tonight: LocalDateTime? = today.atTime(20, 30).takeIf { ctx.now.isBefore(it) }
        private val late: LocalDateTime? = today.atTime(23, 30).takeIf { ctx.now.isBefore(it) }
        val atId: String? = ctx.at?.let { "at:$it" }

        /** Per facet id the values [r] has (none for a facet it does not map), and which hours values are unconfirmed. */
        fun values(r: PlaceRecord): Map<String, Set<String>> = read(r).first

        fun read(r: PlaceRecord): Pair<Map<String, Set<String>>, Boolean> {
            val out = HashMap<String, MutableSet<String>>()
            for ((k, v) in r.tags) {
                if (k == HOURS_KEY || k in PRICE_KEYS) continue
                val set = out.getOrPut(k) { LinkedHashSet() }
                when (kinds[k] ?: FacetKind.VALUES) {
                    FacetKind.FLAG -> for (p in parts(v)) set += Facts.stateOf(p)?.let { "~" + it.name.lowercase(Locale.ROOT) } ?: p
                    FacetKind.MAPPED -> set += MAPPED
                    else -> set += parts(v)
                }
                if (set.isEmpty()) out -= k
            }
            val (open, unconfirmed) = open(r)
            if (open.isNotEmpty()) out[OPEN] = open
            price(r.tags)?.let { out[PRICE] = it }
            out[CHECKED] = mutableSetOf(checked(r))
            return out to unconfirmed
        }

        private fun open(r: PlaceRecord): Pair<MutableSet<String>, Boolean> {
            val known = ctx.hours.of(r)
            val out = LinkedHashSet<String>()
            if (known == null) {
                if (OfflineEvaluator.usuallyOpen(r)) {
                    out += "now"
                    tonight?.let { out += "tonight" }
                    late?.let { out += "late" }
                    atId?.let { out += it }
                } else out += "unknown"
                return out to false
            }
            val h = known.hours
            if (h.isOpen(ctx.now, ctx.opening) == true) out += "now"
            if (tonight != null && h.isOpen(tonight, ctx.opening) == true) out += "tonight"
            if (late != null && h.isOpen(late, ctx.opening) == true) out += "late"
            if (h.isAlwaysOpen) out += "247"
            if (atId != null && h.isOpen(ctx.at!!, ctx.opening) == true) out += atId
            if (out.isEmpty()) out += if (h.isOpen(ctx.now, ctx.opening) == null) "unknown" else CLOSED
            return out to !known.confirmed
        }

        /**
         * How far [r] is from being open as [chosen] asks: when it next opens after the time asked or, for a time
         * ahead, closed last before it, whichever is nearer; null when [chosen] asks no time (24/7, unknown) or it is
         * open at none.
         */
        fun openMiss(r: PlaceRecord, chosen: Set<String>): Miss? {
            val times = chosen.mapNotNull { v ->
                when {
                    v == "now" -> ctx.now to false
                    v == "tonight" -> tonight?.let { it to true }
                    v == "late" -> late?.let { it to true }
                    v.startsWith("at:") -> runCatching { LocalDateTime.parse(v.removePrefix("at:")) }.getOrNull()?.let { it to true }
                    else -> null
                }
            }
            if (times.isEmpty()) return null
            val h = ctx.hours.of(r)?.hours ?: return Miss.HoursUnknown
            return times.mapNotNull { (at, back) -> Closest.openMiss(h, at, back, ctx.opening) }.minByOrNull { it.off }
        }

        private fun checked(r: PlaceRecord): String {
            val edited = r.place.extra["timestamp"]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            val c = Freshness.of(r.tags, null, today, edited)
            val days = c.days ?: return MISSING
            return when {
                days <= Freshness.CHANGING_DAYS -> "recent"
                days <= Freshness.LASTING_DAYS -> "older"
                else -> "stale"
            }
        }
    }

    /** The values of the price facet for [tags]: free, paid and the band of an amount; null when none is mapped. */
    private fun price(tags: Map<String, String>): MutableSet<String>? {
        if (PRICE_KEYS.none { it in tags }) return null
        val out = LinkedHashSet<String>()
        val fee = Facts.stateOf(tags["fee"])
        val amounts = PRICE_KEYS.mapNotNull { tags[it]?.let(::amount) }
        val top = amounts.maxOfOrNull { it.value }
        when {
            fee == FactState.NO || top == 0.0 -> out += "free"
            fee != null && fee != FactState.UNKNOWN || top != null -> out += "paid"
        }
        if (top != null && top > 0) BANDS.firstOrNull { top <= it.second }?.let { out += "band:${fmt(it.first)}-${fmt(it.second)}" }
        return out.takeIf { it.isNotEmpty() }
    }

    /** An amount of money ("1 EUR", "1.00 EUR", "€2,50", "EUR2"), with its currency when it says one. */
    internal data class Amount(val value: Double, val currency: String?)

    internal fun amount(s: String): Amount? {
        val m = AMOUNT.find(s) ?: return null
        val value = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        val cur = (m.groupValues[1].ifEmpty { m.groupValues[3] }).uppercase(Locale.ROOT).ifEmpty { null }
        return Amount(value, CURRENCY[cur] ?: cur)
    }

    private val AMOUNT = Regex("""(€|\$|£|EUR|USD|GBP|CHF)?\s*(\d+(?:[.,]\d{1,2})?)\s*(€|\$|£|EUR|USD|GBP|CHF)?""", RegexOption.IGNORE_CASE)
    private val CURRENCY = mapOf("EUR" to "€", "USD" to "$", "GBP" to "£", "€" to "€", "$" to "$", "£" to "£")

    /** Price bands, from and up to: small change, a coffee, a meal, a night out. */
    private val BANDS = listOf(0.0 to 2.0, 2.0 to 5.0, 5.0 to 10.0, 10.0 to 20.0, 20.0 to 50.0, 50.0 to Double.MAX_VALUE)

    private fun fmt(d: Double) = if (d == Double.MAX_VALUE) "" else if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun parts(v: String): List<String> = if (';' in v) v.split(';').map { it.trim() }.filter { it.isNotEmpty() } else listOf(v.trim()).filter { it.isNotEmpty() }

    private fun passes(has: Set<String>?, chosen: Set<String>): Boolean =
        if (has == null || has.isEmpty()) MISSING in chosen else chosen.any { it in has }

    // -------------------------------------------------------------------------------------------------- counting

    /** The counts of one pool of places under a selection: per facet, the places it counts over and their values. */
    private class Tally(places: List<PlaceRecord>, selection: FacetSelection, reader: Reader) {
        val facets = LinkedHashSet<String>()
        val over = HashMap<String, Int>()
        val mapped = HashMap<String, Int>()
        val counts = HashMap<String, HashMap<String, Int>>()
        val unconfirmed = HashMap<String, Int>()
        val kept = ArrayList<PlaceRecord>()
        val overHours = ArrayList<PlaceRecord>()

        init {
            val read = places.map { reader.read(it) }
            for ((values, _) in read) facets += values.keys
            for ((i, r) in places.withIndex()) {
                val (values, guessed) = read[i]
                // the chosen facets it fails: none keeps it, one lets that facet count it
                var failed: String? = null
                var failures = 0
                for ((f, chosen) in selection.chosen) if (!passes(values[f], chosen)) { failures++; failed = f }
                if (failures == 0) kept += r
                if (failures == 0 || failures == 1 && failed == OPEN) overHours += r
                if (failures > 1) continue
                for (f in facets) {
                    if (failures == 1 && failed != f) continue
                    over.merge(f, 1, Int::plus)
                    val has = values[f]
                    val c = counts.getOrPut(f) { HashMap() }
                    if (has.isNullOrEmpty()) c.merge(MISSING, 1, Int::plus) else {
                        mapped.merge(f, 1, Int::plus)
                        for (v in has) {
                            c.merge(v, 1, Int::plus)
                            if (guessed && f == OPEN && v != "unknown") unconfirmed.merge(v, 1, Int::plus)
                        }
                    }
                }
            }
        }
    }

    private fun build(id: String, near: Tally, far: Tally?, selection: FacetSelection, reader: Reader): Facet? {
        val lang = reader.ctx.lang
        val kind = when (id) {
            OPEN -> FacetKind.OPEN
            PRICE -> FacetKind.PRICE
            CHECKED -> FacetKind.CHECKED
            else -> reader.kinds[id] ?: if (selection.chosen[id].orEmpty().any { it.startsWith("~") }) FacetKind.FLAG else FacetKind.VALUES
        }
        val counts = near.counts[id].orEmpty()
        val farCounts = far?.counts?.get(id).orEmpty()
        val chosen = selection.chosen[id].orEmpty()
        val valueIds = LinkedHashSet<String>().apply {
            addAll(counts.keys)
            addAll(farCounts.keys)
            addAll(chosen)
            if (id == OPEN) reader.atId?.let { add(it) }
        }
        if (valueIds.isEmpty()) return null
        val label = Labels.facet(id, lang)
        val symbol = Labels.facetSymbol(id)
        val values = valueIds.filter { it != CLOSED }.map { v ->
            val (vl, chip) = Labels.value(id, kind, v, label, lang)
            FacetValue(
                id = v,
                label = vl,
                chip = chip,
                symbol = Labels.valueSymbol(id, kind, v) ?: symbol,
                count = counts[v] ?: 0,
                selected = v in chosen,
                unconfirmed = if (id == OPEN) near.unconfirmed[v] ?: 0 else 0,
                elsewhere = farCounts[v] ?: 0,
            )
        }.sortedWith(valueOrder(id, kind))
        val over = near.over[id] ?: 0
        val mapped = near.mapped[id] ?: 0
        return Facet(id, kind, Labels.section(id), label, symbol, keysOf(id), values, mapped, over, rank(id, kind, values, mapped, over))
    }

    private fun keysOf(id: String): List<String> = when (id) {
        OPEN -> listOf(HOURS_KEY)
        PRICE -> PRICE_KEYS
        CHECKED -> listOf("check_date")
        else -> listOf(id)
    }

    private val OPEN_ORDER = listOf("now", "tonight", "late", "247", "unknown")
    private val PRICE_ORDER = listOf("free", "paid")
    private val CHECKED_ORDER = listOf("recent", "older", "stale", MISSING)
    private val STATE_ORDER = listOf("~yes", "~only", "~limited", "~no")

    /** The order a facet's values are listed in: their own for hours, prices, dates and states; else the commonest first. */
    private fun valueOrder(id: String, kind: FacetKind): Comparator<FacetValue> =
        when (kind) {
            FacetKind.OPEN -> compareBy { v -> if (v.id == "now") 0 else if (v.id.startsWith("at:")) 1 else 2 + OPEN_ORDER.indexOf(v.id).let { if (it < 0) 9 else it } }
            FacetKind.PRICE -> compareBy<FacetValue> { v -> if (v.id == MISSING) 9 else PRICE_ORDER.indexOf(v.id).let { if (it < 0) 2 else it } }.thenBy { bandStart(it.id) }
            FacetKind.CHECKED -> compareBy { CHECKED_ORDER.indexOf(it.id) }
            FacetKind.FLAG -> compareBy<FacetValue> { if (it.id == MISSING) 99 else STATE_ORDER.indexOf(it.id).let { i -> if (i < 0) 50 else i } }.thenByDescending { it.count }
            else -> compareBy<FacetValue> { it.id == MISSING }.thenByDescending { it.count }.thenByDescending { it.elsewhere }.thenBy { numeric(it.id) ?: Double.MAX_VALUE }.thenBy { it.label }
        }

    private fun bandStart(id: String): Double = if (id.startsWith("band:")) id.removePrefix("band:").substringBefore('-').toDoubleOrNull() ?: 0.0 else 0.0

    private fun numeric(id: String): Double? = id.toDoubleOrNull()

    /**
     * How worth showing a facet is: its section's weight and the fact's prominence where Atlas has one for its key,
     * by how many places map it, and whether any of its values narrows them.
     */
    private fun rank(id: String, kind: FacetKind, values: List<FacetValue>, mapped: Int, over: Int): Double {
        val section = Labels.section(id)
        val fact = FactKind.entries.firstOrNull { Fields.of(it)?.let { f -> f.key == id || f.isMultiKey && id.startsWith(f.key) } == true }
        // a key few places map ranks low however much it matters: a kitchen's hours on two of 700 restaurants
        val coverage = if (over == 0) 0.0 else kotlin.math.sqrt(mapped.toDouble() / over)
        val narrows = values.any { it.id != MISSING && it.count in 1 until over }
        return coverage * (SECTION_WEIGHT.getValue(section) + (fact?.priority ?: 0) / 50.0) + (if (narrows) 1.0 else 0.0) +
            (if (kind == FacetKind.MAPPED) -1.0 else 0.0)
    }

    private val SECTION_WEIGHT = mapOf(
        FacetSection.WHEN to 9.0, FacetSection.KIND to 5.0, FacetSection.FOOD to 6.0, FacetSection.ACCESS to 6.0,
        FacetSection.COST to 5.5, FacetSection.PAYMENT to 3.0, FacetSection.SERVICES to 4.0, FacetSection.CONTACT to 2.0,
        FacetSection.ADDRESS to 1.5, FacetSection.NAMES to 1.0, FacetSection.MAPPING to 0.5, FacetSection.OTHER to 1.0,
    )

    /** Keys offered by their values whatever they look like: the kinds of places, and lists of things. */
    private val VALUE_KEYS = setOf(
        "amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "historic", "sport", "man_made",
        "emergency", "natural", "water", "waterway", "landuse", "building", "vending", "clothes", "cuisine", "religion",
        "denomination", "artwork_type", "memorial", "attraction", "information", "recycling_type", "club", "theme",
        "healthcare:speciality", "brand", "operator", "network", "addr:street", "addr:city", "addr:suburb", "level",
        "internet_access", "smoking", "dog", "access", "surface", "stars", "capacity", "seats", "rooms", "min_age",
        "source", "reservation", "highway", "railway", "public_transport", "shelter_type", "parking", "material",
    )
    private val VALUE_PREFIXES = listOf("socket:", "building:", "roof:")

    /** Keys whose values are every place's own: names, numbers, addresses, links, dates and notes. */
    private val UNIQUE_KEYS = setOf(
        "name", "alt_name", "old_name", "official_name", "short_name", "loc_name", "int_name", "description", "note", "fixme",
        "FIXME", "phone", "mobile", "fax", "email", "website", "url", "addr:housenumber", "addr:postcode", "addr:unit",
        "wikidata", "wikipedia", "wikimedia_commons", "image", "panoramax", "mapillary", "ref", "branch", "inscription",
        "start_date", "check_date", "survey:date", "last_checked", "source:date", "collection_times", "service_times",
        "happy_hours", "brand:wikidata", "brand:wikipedia", "brand:website", "operator:wikidata", "not:brand:wikidata",
        "height", "ele", "internet_access:ssid",
    )
    private val UNIQUE_PREFIXES = listOf(
        "name:", "alt_name:", "old_name:", "official_name:", "description:", "note:", "contact:", "website:", "url:",
        "check_date:", "opening_hours:", "ref:", "source:", "wikipedia:", "inscription:", "image:", "menu:", "phone:",
    )

    /** The day and time asked about, for the hours' facet ("Sat 14:00"). */
    internal fun timeLabel(at: LocalDateTime, lang: String): String =
        at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag(lang)).trimEnd('.') + " " + "%02d:%02d".format(Locale.ROOT, at.hour, at.minute)
}

/** The names, symbols and sections of facets and their values, in English, Dutch, German and French. */
internal object Labels {
    fun section(id: String): FacetSection = when {
        id == Facets.OPEN -> FacetSection.WHEN
        id == Facets.PRICE -> FacetSection.COST
        id == Facets.CHECKED -> FacetSection.MAPPING
        id.endsWith(":fee") || id.endsWith(":charge") || id.startsWith("cost:") || id.startsWith("price:") || id.startsWith("fee:") -> FacetSection.COST
        id.startsWith("payment:") || id.startsWith("currency:") || id == "cash_in" || id == "cash_withdrawal" -> FacetSection.PAYMENT
        id.startsWith("opening_hours:") && id != "opening_hours:signed" || id in WHEN_KEYS -> FacetSection.WHEN
        id.startsWith("diet:") || id.startsWith("drink:") || id in FOOD_KEYS -> FacetSection.FOOD
        id.startsWith("wheelchair") || id.startsWith("blind:") || id.startsWith("ramp") || id.startsWith("toilets:wheelchair") || id in ACCESS_KEYS -> FacetSection.ACCESS
        id.startsWith("addr:") -> FacetSection.ADDRESS
        id.startsWith("contact:") || id.startsWith("website") || id.endsWith(":url") || id in CONTACT_KEYS -> FacetSection.CONTACT
        id.startsWith("check_date") || id.startsWith("source") || id.startsWith("survey") || id.startsWith("not:") || id in MAPPING_KEYS -> FacetSection.MAPPING
        id.startsWith("name:") || id.startsWith("brand") || id.startsWith("operator") || id.startsWith("network") || id in NAME_KEYS -> FacetSection.NAMES
        id in KIND_KEYS -> FacetSection.KIND
        id.startsWith("internet_access") || id.startsWith("toilets") || id.startsWith("socket:") || id.startsWith("fuel:") ||
            id.startsWith("recycling:") || id in SERVICE_KEYS -> FacetSection.SERVICES
        else -> FacetSection.OTHER
    }

    /** Sections whose values say enough on a chip alone ("Pizza", "Prinsengracht", "Albert Heijn"). */
    private val BARE_VALUES = setOf(FacetSection.KIND, FacetSection.FOOD, FacetSection.NAMES, FacetSection.ADDRESS)

    private val WHEN_KEYS = setOf("collection_times", "service_times", "happy_hours", "seasonal", "open_air")
    private val FOOD_KEYS = setOf(
        "cuisine", "takeaway", "delivery", "outdoor_seating", "indoor_seating", "drive_through", "reservation", "organic", "food",
        "bar", "cafe", "brewery", "microbrewery", "breakfast", "lunch", "dinner", "highchair", "kitchen", "beer_garden", "self_service",
    )
    private val ACCESS_KEYS = setOf(
        "access", "entrance", "level", "step_count", "automatic_door", "door", "hearing_loop", "min_age", "max_age", "female",
        "male", "unisex", "lgbtq", "dog", "indoor", "covered", "lit", "surface", "incline", "kids_area",
    )
    private val CONTACT_KEYS = setOf("phone", "mobile", "fax", "email", "url", "facebook", "instagram", "twitter")
    private val MAPPING_KEYS = setOf("note", "fixme", "FIXME", "wikidata", "wikipedia", "wikimedia_commons", "image", "panoramax", "mapillary", "layer", "last_checked", "opening_hours:signed", "lastcheck")
    private val NAME_KEYS = setOf("name", "alt_name", "old_name", "official_name", "short_name", "loc_name", "int_name", "branch", "ref")
    /** The keys that say what a place is, not what it is like. */
    internal val KIND_KEYS = setOf(
        "amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "healthcare:speciality", "historic", "sport",
        "man_made", "emergency", "natural", "water", "waterway", "landuse", "vending", "clothes", "religion", "denomination",
        "artwork_type", "memorial", "attraction", "information", "recycling_type", "club", "theme", "place", "highway",
        "railway", "public_transport", "building", "shelter_type", "playground", "tower:type", "second_hand",
    )
    private val SERVICE_KEYS = setOf(
        "changing_table", "drinking_water", "air_conditioning", "smoking", "capacity", "seats", "rooms", "stars", "atm",
        "bicycle_parking", "self_checkout", "baby_feeding", "shower", "backrest", "armrest", "material", "darkroom",
        "bench", "bin", "shelter", "power_supply", "wifi",
    )

    fun facet(id: String, lang: String): String {
        when (id) {
            Facets.OPEN -> return t(OPEN_LABEL, lang)
            Facets.PRICE -> return t(PRICE_LABEL, lang)
            Facets.CHECKED -> return t(CHECKED_LABEL, lang)
        }
        KEYS[id]?.let { return t(it, lang) }
        Fields.forKey(id)?.let { f -> return if (f.isMultiKey) f.optionLabel(id.removePrefix(f.key), lang) else f.label(lang) }
        if (id.startsWith("name:")) return t(KEYS.getValue("name"), lang) + " · " + id.removePrefix("name:").uppercase(Locale.ROOT)
        if (id.startsWith("check_date:")) return t(CHECKED_LABEL, lang) + " · " + facet(id.removePrefix("check_date:"), lang)
        Presets.byId(id)?.takeIf { it.tags[id] == "*" }?.let { return it.name(lang) }
        return readable(id.removePrefix("contact:").removePrefix("addr:"))
    }

    fun facetSymbol(id: String): String {
        when (id) {
            Facets.OPEN -> return "ui-clock"
            Facets.PRICE -> return "ui-coins"
            Facets.CHECKED -> return "ui-history"
        }
        FactKind.entries.firstOrNull { k -> Fields.of(k)?.let { f -> f.key == id || f.isMultiKey && id.startsWith(f.key) } == true }
            ?.symbol?.takeIf { Symbols.has(it) }?.let { return it }
        Lexicon.attributeByKey[id]?.symbol?.takeIf { Symbols.has(it) }?.let { return it }
        return when (section(id)) {
            FacetSection.KIND -> Presets.byId(id)?.let { Kinds.symbolOf(it) } ?: "ui-layers"
            FacetSection.FOOD -> "ui-utensils"
            FacetSection.ACCESS -> "ui-door"
            FacetSection.COST -> "ui-coins"
            FacetSection.PAYMENT -> "ui-card"
            FacetSection.CONTACT -> when {
                id.contains("phone") || id == "mobile" -> "ui-phone"
                id.contains("mail") -> "ui-mail"
                else -> "ui-web"
            }
            FacetSection.ADDRESS -> "ui-house"
            FacetSection.NAMES -> "ui-tag"
            FacetSection.MAPPING -> "ui-history"
            else -> "ui-info"
        }
    }

    /** A value's own symbol: a kind's preset's, a cuisine's. */
    fun valueSymbol(id: String, kind: FacetKind, value: String): String? = when {
        kind == FacetKind.OPEN -> when (value) {
            "tonight", "late" -> "ui-moon"
            "unknown" -> "ui-help"
            "247" -> "ui-sun"
            else -> "ui-clock"
        }
        kind == FacetKind.PRICE -> if (value == "free") "ui-gift" else "ui-coins"
        kind != FacetKind.VALUES || value == Facets.MISSING -> null
        id == "cuisine" -> Cuisine.symbol(value).takeIf { Symbols.has(it) }
        else -> Presets.byId("$id/$value")?.let { Kinds.symbolOf(it) }
    }

    /** A value's label in the list of all filters, and its words on a chip of its own. */
    fun value(id: String, kind: FacetKind, value: String, facet: String, lang: String): Pair<String, String> {
        if (value == Facets.MISSING) return t(NOT_MAPPED, lang) to "$facet: ${t(NOT_MAPPED, lang).lowercase(Locale.forLanguageTag(lang))}"
        return when (kind) {
            FacetKind.OPEN -> {
                val l = if (value.startsWith("at:")) t(OPEN_AT, lang).format(Facets.timeLabel(java.time.LocalDateTime.parse(value.removePrefix("at:")), lang))
                else t(OPEN_VALUES[value] ?: return value to value, lang)
                l to l
            }
            FacetKind.PRICE -> {
                val l = if (value.startsWith("band:")) band(value.removePrefix("band:")) else t(PRICE_VALUES[value] ?: return value to value, lang)
                l to l
            }
            FacetKind.CHECKED -> t(CHECKED_VALUES[value] ?: return value to value, lang).let { it to "$facet: ${it.lowercase(Locale.forLanguageTag(lang))}" }
            FacetKind.MAPPED -> t(MAPPED_LABEL, lang) to facet
            FacetKind.FLAG -> {
                val state = STATES[value]
                if (state == null) {
                    val l = optionOf(id, value, lang)
                    l to "$facet: $l"
                } else {
                    val l = t(state, lang)
                    // "yes" is the facet itself on a chip ("Step-free", "Outdoor seating"); the other states say so
                    val attribute = Lexicon.attributeByKey[id]?.id
                    val plain = attribute?.let { Refinements.label(it, lang) }?.takeIf { it != attribute } ?: facet
                    l to if (value == "~yes") plain else "$facet: ${l.lowercase(Locale.forLanguageTag(lang))}"
                }
            }
            FacetKind.VALUES -> optionOf(id, value, lang).let { it to if (section(id) in BARE_VALUES) it else "$facet: $it" }
        }
    }

    private fun optionOf(id: String, value: String, lang: String): String {
        if (id == "cuisine") return Cuisine.name(value, lang)
        Fields.forKey(id)?.takeIf { !it.isMultiKey && it.options.isNotEmpty() }?.let { return it.optionLabel(value, lang) }
        Presets.byId("$id/$value")?.let { return it.name(lang) }
        return if (value.firstOrNull()?.isLowerCase() == true && ' ' !in value) readable(value) else value
    }

    private fun band(range: String): String {
        val lo = range.substringBefore('-')
        val hi = range.substringAfter('-')
        return when {
            lo == "0" -> "≤ $hi €"
            hi.isEmpty() -> "$lo € +"
            else -> "$lo–$hi €"
        }
    }

    private fun readable(s: String): String = s.replace(':', ' ').replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun t(m: Map<String, String>, lang: String): String = m[lang] ?: m.getValue("en")

    private fun l(en: String, nl: String, de: String, fr: String) = mapOf("en" to en, "nl" to nl, "de" to de, "fr" to fr)

    private val OPEN_LABEL = l("Opening hours", "Openingstijden", "Öffnungszeiten", "Horaires")
    private val PRICE_LABEL = l("Price", "Prijs", "Preis", "Prix")
    private val CHECKED_LABEL = l("Last checked", "Laatst gecontroleerd", "Zuletzt geprüft", "Vérifié")
    private val NOT_MAPPED = l("Not mapped", "Niet gemapt", "Nicht erfasst", "Non renseigné")
    private val MAPPED_LABEL = l("Mapped", "Gemapt", "Erfasst", "Renseigné")
    private val OPEN_AT = l("Open %s", "Open %s", "Offen %s", "Ouvert %s")
    private val OPEN_VALUES = mapOf(
        "now" to l("Open now", "Nu open", "Jetzt offen", "Ouvert maintenant"),
        "tonight" to l("Open tonight", "Vanavond open", "Heute Abend offen", "Ouvert ce soir"),
        "late" to l("Open late", "Laat open", "Spät offen", "Ouvert tard"),
        "247" to l("24/7", "24/7", "24/7", "24h/24"),
        "unknown" to l("Hours unknown", "Tijden onbekend", "Zeiten unbekannt", "Horaires inconnus"),
    )
    private val PRICE_VALUES = mapOf(
        "free" to l("Free", "Gratis", "Kostenlos", "Gratuit"),
        "paid" to l("Paid", "Betaald", "Kostenpflichtig", "Payant"),
    )
    private val CHECKED_VALUES = mapOf(
        "recent" to l("This year", "Dit jaar", "Dieses Jahr", "Cette année"),
        "older" to l("1 to 4 years ago", "1 tot 4 jaar geleden", "Vor 1 bis 4 Jahren", "Il y a 1 à 4 ans"),
        "stale" to l("Over 4 years ago", "Meer dan 4 jaar geleden", "Vor über 4 Jahren", "Il y a plus de 4 ans"),
        Facets.MISSING to l("Never", "Nooit", "Nie", "Jamais"),
    )
    private val STATES = mapOf(
        "~yes" to l("Yes", "Ja", "Ja", "Oui"),
        "~limited" to l("Partly", "Deels", "Teilweise", "En partie"),
        "~only" to l("Only", "Alleen", "Nur", "Uniquement"),
        "~no" to l("No", "Nee", "Nein", "Non"),
        "~unknown" to l("Unknown", "Onbekend", "Unbekannt", "Inconnu"),
    )

    /** Names of the keys id-tagging-schema has no field for in Atlas's data, where the key alone reads badly. */
    private val KEYS: Map<String, Map<String, String>> = mapOf(
        "name" to l("Name", "Naam", "Name", "Nom"),
        "brand" to l("Brand", "Merk", "Marke", "Marque"),
        "operator" to l("Operator", "Exploitant", "Betreiber", "Exploitant"),
        "network" to l("Network", "Netwerk", "Netz", "Réseau"),
        "branch" to l("Branch", "Filiaal", "Filiale", "Succursale"),
        "alt_name" to l("Also called", "Ook genoemd", "Auch genannt", "Aussi appelé"),
        "old_name" to l("Former name", "Vroegere naam", "Früherer Name", "Ancien nom"),
        "official_name" to l("Official name", "Officiële naam", "Offizieller Name", "Nom officiel"),
        "short_name" to l("Short name", "Korte naam", "Kurzname", "Nom court"),
        "addr:street" to l("Street", "Straat", "Straße", "Rue"),
        "addr:housenumber" to l("House number", "Huisnummer", "Hausnummer", "Numéro"),
        "addr:city" to l("City", "Plaats", "Ort", "Ville"),
        "addr:postcode" to l("Postcode", "Postcode", "Postleitzahl", "Code postal"),
        "addr:country" to l("Country", "Land", "Land", "Pays"),
        "addr:suburb" to l("District", "Wijk", "Stadtteil", "Quartier"),
        "email" to l("Email", "E-mail", "E-Mail", "E-mail"),
        "contact:email" to l("Email", "E-mail", "E-Mail", "E-mail"),
        "contact:phone" to l("Phone", "Telefoon", "Telefon", "Téléphone"),
        "contact:website" to l("Website", "Website", "Website", "Site web"),
        "website:menu" to l("Menu online", "Menukaart online", "Speisekarte online", "Menu en ligne"),
        "description" to l("Description", "Beschrijving", "Beschreibung", "Description"),
        "note" to l("Note for mappers", "Notitie voor mappers", "Notiz für Mapper", "Note aux contributeurs"),
        "source" to l("Source", "Bron", "Quelle", "Source"),
        "source:date" to l("Source date", "Datum van de bron", "Datum der Quelle", "Date de la source"),
        "check_date" to l("Checked", "Gecontroleerd", "Geprüft", "Vérifié"),
        "survey:date" to l("Surveyed", "Ter plaatse bekeken", "Vor Ort geprüft", "Relevé sur place"),
        "start_date" to l("Since", "Sinds", "Seit", "Depuis"),
        "covered" to l("Covered", "Overdekt", "Überdacht", "Couvert"),
        "backrest" to l("Backrest", "Rugleuning", "Rückenlehne", "Dossier"),
        "armrest" to l("Armrests", "Armleuningen", "Armlehnen", "Accoudoirs"),
        "lgbtq" to l("LGBTQ+ welcome", "LHBTIQ+ welkom", "LGBTQ+ willkommen", "LGBTQ+ bienvenus"),
        "brewery" to l("Beers on tap", "Bieren van de tap", "Biere vom Fass", "Bières pression"),
        "food" to l("Serves food", "Serveert eten", "Mit Essen", "Sert à manger"),
        "bar" to l("Has a bar", "Met bar", "Mit Bar", "Avec bar"),
        "clothes" to l("Clothes", "Kleding", "Kleidung", "Vêtements"),
        "vending" to l("Sells", "Verkoopt", "Verkauft", "Vend"),
        "min_age" to l("Minimum age", "Minimumleeftijd", "Mindestalter", "Âge minimum"),
        "highchair" to l("High chair", "Kinderstoel", "Hochstuhl", "Chaise haute"),
        "currency:XBT" to l("Bitcoin", "Bitcoin", "Bitcoin", "Bitcoin"),
        "brand:wikidata" to l("Brand in Wikidata", "Merk in Wikidata", "Marke in Wikidata", "Marque dans Wikidata"),
        "level" to l("Floor", "Verdieping", "Stockwerk", "Étage"),
        "building" to l("Building", "Gebouw", "Gebäude", "Bâtiment"),
        "height" to l("Height", "Hoogte", "Höhe", "Hauteur"),
        "toilets:access" to l("Toilets for", "Toiletten voor", "Toiletten für", "Toilettes pour"),
        "seats" to l("Seats", "Zitplaatsen", "Sitzplätze", "Places assises"),
        "bicycle_parking" to l("Bicycle parking", "Fietsenstalling", "Fahrradparken", "Parking vélo"),
        "material" to l("Material", "Materiaal", "Material", "Matériau"),
        "indoor" to l("Indoors", "Binnen", "Drinnen", "À l'intérieur"),
        "religion" to l("Religion", "Religie", "Religion", "Religion"),
        "denomination" to l("Denomination", "Stroming", "Konfession", "Confession"),
        "ref" to l("Reference", "Kenmerk", "Kennung", "Référence"),
        "panoramax" to l("Street photo", "Straatfoto", "Straßenfoto", "Photo de rue"),
        "wikimedia_commons" to l("Photos on Commons", "Foto's op Commons", "Fotos auf Commons", "Photos sur Commons"),
    )
}
