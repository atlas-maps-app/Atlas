package app.atlas.search

import app.atlas.places.FactState
import app.atlas.places.Freshness
import app.atlas.places.InferredHours
import app.atlas.places.OpeningHours
import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
import java.time.LocalDate

/** Where a value offered for an unmapped tag comes from: the places of its kind around, its brand's branches, or neither. */
enum class AnswerFrom { AROUND, BRAND, ASKED }

/**
 * One value to add with a tap: [key]=[value] as [label] reads it, mapped so on [count] of the places of its kind
 * around ([AnswerFrom.AROUND]), kept by [count] of its brand's branches ([AnswerFrom.BRAND]), or a plain yes or no
 * where no place around says ([AnswerFrom.ASKED], none counted).
 */
data class Answer(val key: String, val value: String, val label: String, val count: Int, val from: AnswerFrom = AnswerFrom.AROUND)

/**
 * A tag a place leaves unsaid that matters for its kind (a toilet's fee, a café's terrace, a branch's hours), with
 * the values the places of its kind around map most, best first (the user, 3 November: "Make it easy to one tap add
 * missing relevant tags mapped to similar places around you"). [share] is how many of those places map it (0..1);
 * [tell] the tell of the kind's rows it would fill. No answers: it is worth adding, but needs typing (a cuisine no
 * place around has, hours of its own).
 */
data class Missing(
    val key: String,
    val label: String,
    val symbol: String,
    val answers: List<Answer>,
    val share: Double,
    val tell: TellKey? = null,
)

/** What a place could say and does not, answered as its kind's places around answer it. */
object Answers {
    const val HOURS = "opening_hours"

    /** At most this many unmapped tags a place is asked about, and this many values offered for each. */
    const val MAX = 4
    private const val VALUES = 3

    /** A tag outside the kind's tells is asked about when this share of at least [AROUND] places around map it. */
    private const val COMMON = 0.3
    private const val AROUND = 3

    /**
     * The tags [r] leaves unsaid that matter for its kind: its hours first (one tap away when its brand's branches
     * keep the same: [brandHours]), then what its kind's rows tell ([purpose]), then what most of its kind around map;
     * each with the values of [facets], the facets of its kind's places around.
     */
    fun missing(r: PlaceRecord, purpose: Purpose, facets: FacetSet, lang: String, brandHours: InferredHours? = null, max: Int = MAX): List<Missing> {
        val tags = r.tags
        val out = ArrayList<Missing>()
        val around = facets.kept.size
        if (HOURS !in tags) {
            val share = if (around == 0) 0.0 else facets.kept.count { HOURS in it.tags }.toDouble() / around
            val brand = brandHours?.let { listOf(Answer(HOURS, it.hours.raw, it.hours.raw, it.from, AnswerFrom.BRAND)) }.orEmpty()
            if (brand.isNotEmpty() || share >= COMMON && around >= AROUND && !OfflineEvaluator.usuallyOpen(r)) {
                out += Missing(HOURS, Labels.facet(Facets.OPEN, lang), "ui-clock", brand, share)
            }
        }
        for (k in purpose.tells) {
            if (out.size >= max) break
            val key = k.tag ?: continue
            if (said(k, key, tags)) continue
            val answers = valuesOf(key, facets).ifEmpty { askedOf(key, lang) }
            val share = shareOf(key, facets)
            if (answers.isEmpty() && share < COMMON) continue
            out += Missing(key, k.label(lang), k.symbol, answers, share, k)
        }
        for (f in facets.facets) {
            if (out.size >= max) break
            if (f.section !in ASKED_SECTIONS || f.kind != FacetKind.FLAG && f.kind != FacetKind.VALUES) continue
            if (f.keys.any { it in tags } || out.any { it.key == f.id }) continue
            if (f.over < AROUND || f.mapped.toDouble() / f.over < COMMON) continue
            val answers = valuesOf(f.id, facets).takeIf { it.isNotEmpty() } ?: continue
            out += Missing(f.id, f.label, f.symbol, answers, f.mapped.toDouble() / f.over)
        }
        return out.take(max)
    }

    /** [missing] for a result, among the results of its kind ([results]); its brand's hours from [hours]. */
    fun missing(hit: ResultHit, results: CategoryResults, lang: String, hours: HoursBook? = null, max: Int = MAX): List<Missing> =
        missing(hit.record, results.purpose, results.facets, lang, hit.record.brand?.let { b -> hours?.inferred(b) }, max)

    /** True when [tags] already say what [k] reads under [key] (a toilet's price under any of the price's keys). */
    private fun said(k: TellKey, key: String, tags: Map<String, String>): Boolean = when (k) {
        TellKey.FEE -> Tells.FEE_KEYS.any { it in tags }
        TellKey.DIET -> "diet:vegetarian" in tags || "diet:vegan" in tags
        TellKey.SWIM -> Certainty.swim(tags) != Sure.MAYBE
        TellKey.COVERED -> "covered" in tags || "shelter" in tags
        else -> key in tags
    }

    /** The values the places around map for [key], the commonest first, as tags to set. */
    private fun valuesOf(key: String, facets: FacetSet): List<Answer> {
        // the price's keys are one facet: free is fee=no, paid fee=yes
        if (key == "fee") return facets.byId(Facets.PRICE)?.values.orEmpty()
            .filter { it.id == "free" || it.id == "paid" }.filter { it.count + it.elsewhere > 0 }
            .sortedWith(compareByDescending<FacetValue> { it.count }.thenByDescending { it.elsewhere })
            .map { Answer(key, if (it.id == "free") "no" else "yes", it.label, it.count) }
        val f = facets.byId(key)?.takeIf { it.kind == FacetKind.FLAG || it.kind == FacetKind.VALUES } ?: return emptyList()
        return f.values.filter { it.id != Facets.MISSING && it.count + it.elsewhere > 0 }
            .sortedWith(compareByDescending<FacetValue> { it.count }.thenByDescending { it.elsewhere })
            .mapNotNull { v -> tagValue(key, v.id)?.let { Answer(key, it, v.label, v.count) } }
            .distinctBy { it.value }
            .take(VALUES)
    }

    /** How many of the places around map [key], 0..1. */
    private fun shareOf(key: String, facets: FacetSet): Double {
        val f = (if (key == "fee") facets.byId(Facets.PRICE) else facets.byId(key)) ?: return 0.0
        val missing = f.value(Facets.MISSING)?.count ?: 0
        return if (f.over == 0) 0.0 else (f.over - missing).toDouble() / f.over
    }

    /** A yes or a no to set where no place around maps [key], for the keys that are yes or no. */
    private fun askedOf(key: String, lang: String): List<Answer> {
        val yes = YES_NO[key] ?: return emptyList()
        val label = Labels.facet(key, lang)
        return listOf(
            Answer(key, yes, Labels.value(key, FacetKind.FLAG, "~yes", label, lang).first, 0, AnswerFrom.ASKED),
            Answer(key, "no", Labels.value(key, FacetKind.FLAG, "~no", label, lang).first, 0, AnswerFrom.ASKED),
        )
    }

    /** The tag value of a facet value: a state's word ("~yes" is yes, Wi-Fi's wlan), else the value itself. */
    private fun tagValue(key: String, id: String): String? = when {
        id == Facets.MAPPED || id.startsWith("band:") -> null
        id.startsWith("~") -> FactState.entries.firstOrNull { "~" + it.name.lowercase() == id }?.let { s ->
            when (s) {
                FactState.YES -> YES_NO[key] ?: "yes"
                FactState.NO -> "no"
                FactState.LIMITED -> "limited"
                FactState.ONLY -> "only"
                else -> null
            }
        }
        else -> id
    }

    /** Where facets ask what a place is like, not what it is called, where it is or how it was mapped. */
    private val ASKED_SECTIONS = setOf(FacetSection.FOOD, FacetSection.ACCESS, FacetSection.SERVICES, FacetSection.PAYMENT)

    /** The keys answered with a yes or a no, and the value yes is mapped as. */
    private val YES_NO = mapOf(
        "fee" to "yes", "wheelchair" to "yes", "changing_table" to "yes", "outdoor_seating" to "yes", "takeaway" to "yes",
        "diet:vegetarian" to "yes", "covered" to "yes", "drinking_water" to "yes", "bottle" to "yes", "dispensing" to "yes",
        "cash_in" to "yes", "lit" to "yes", "indoor" to "yes", "organic" to "yes", "second_hand" to "yes", "self_service" to "yes",
        "backrest" to "yes", "dog" to "yes", "toilets" to "yes", "internet_access" to "wlan", "swimming" to "yes",
    )
}

/**
 * The edits one tap makes to OpenStreetMap, sent later with the user's own account: a value added, changed or
 * removed, or one confirmed as still right (the user, 3 November: "Long press any value like the opening times to
 * adjust it"). Only for places of OpenStreetMap with an id; each says what the user saw, so it never lands on what
 * someone changed meanwhile.
 */
object TagEdits {
    /**
     * [key] set to [value] on [r] (null or blank removes it). A changed value of what changes often (hours, a fee)
     * was just looked at, so it is dated today too (`check_date:key`); a value added is new and needs no date. The
     * same value again confirms it ([confirm]). Null for a place not of OpenStreetMap, or hours that do not read.
     */
    fun set(r: PlaceRecord, key: String, value: String?, today: LocalDate): TagEdit? {
        val (type, id) = osm(r) ?: return null
        val old = r.tags[key]
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        if (v == old) return if (v == null) null else confirm(r, key, today)
        if (v != null && (v.length > MAX_LENGTH || key == Answers.HOURS && OpeningHours.parseOrNull(v) == null)) return null
        val dateKey = "check_date:$key"
        val set = LinkedHashMap<String, String?>()
        set[key] = v
        when {
            v == null -> if (dateKey in r.tags) set[dateKey] = null
            old != null && Freshness.changes(key) -> set[dateKey] = today.toString()
        }
        return TagEdit(type, id, set, set.keys.associateWith { r.tags[it] })
    }

    /** [key] of [r] confirmed as still right: dated today. Null when [r] does not map it, or it was checked today. */
    fun confirm(r: PlaceRecord, key: String, today: LocalDate): TagEdit? {
        val (type, id) = osm(r) ?: return null
        if (key !in r.tags) return null
        val dateKey = "check_date:$key"
        val date = today.toString()
        if (r.tags[dateKey] == date) return null
        return TagEdit(type, id, mapOf(dateKey to date), mapOf(dateKey to r.tags[dateKey]))
    }

    /** The edit an [answer] makes. */
    fun answer(r: PlaceRecord, answer: Answer, today: LocalDate): TagEdit? = set(r, answer.key, answer.value, today)

    /** What an edit reads as in the list of edits: the place and the key ("Café de Jaren · outdoor_seating"). */
    fun label(r: PlaceRecord, key: String, lang: String): String = listOfNotNull(r.name(lang), key).joinToString(" · ")

    /** True when a tag of [r] can be edited: it is of OpenStreetMap and has an id. */
    fun editable(r: PlaceRecord): Boolean = osm(r) != null

    private fun osm(r: PlaceRecord): Pair<OsmType, Long>? {
        if (r.place.source != "osm") return null
        val id = r.id ?: return null
        return OsmType.valueOf(id.type.name) to id.id
    }

    /** OpenStreetMap takes values up to this long. */
    private const val MAX_LENGTH = 255
}
