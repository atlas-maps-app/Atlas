package app.atlas.places

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/** How a feature is drawn in OSM: a lone node, a node on a way, a line, an area, a relation. */
enum class Geometry(val code: Char) {
    POINT('p'), VERTEX('v'), LINE('l'), AREA('a'), RELATION('r');

    companion object {
        fun of(code: Char): Geometry? = entries.firstOrNull { it.code == code }
    }
}

/** An id-tagging-schema preset: what a feature is, the tags that say so, and its symbol. */
class Preset internal constructor(
    val id: String,
    val tags: Map<String, String>,
    val addTags: Map<String, String>,
    val geometry: Set<Geometry>,
    /** A CC0 symbol id for [Symbols.icon], or null. */
    val icon: String?,
    /** The fact fields id shows first for this preset ("opening_hours", "outdoor_seating", "diet_multi"…). */
    val fields: List<String>,
    /** The fact fields id offers on request ("wheelchair", "takeaway"…), [fields] left out. */
    val moreFields: List<String>,
    val matchScore: Double,
    val searchable: Boolean,
    /** ISO countries (lower case) it is limited to; empty means everywhere. */
    val countries: Set<String>,
    val excludedCountries: Set<String>,
) {
    val parentId: String? get() = id.substringBeforeLast('/', "").ifEmpty { null }

    fun name(lang: String? = "en"): String = Presets.name(this, lang)

    fun terms(lang: String? = "en"): List<String> = I18n.pack(lang).presets[id]?.terms ?: emptyList()

    fun availableIn(country: String?): Boolean {
        if (country == null) return true
        val c = country.lowercase()
        return (countries.isEmpty() || c in countries) && c !in excludedCountries
    }

    /** id's match score: each exact tag counts [matchScore], each "*" tag half, an `addTags` match adds more; -1 if any tag fails. */
    fun score(entity: Map<String, String>): Double {
        var s = 0.0
        for ((k, v) in tags) {
            val e = entity[k]
            s += when {
                e == v -> matchScore
                v == "*" && e != null -> matchScore / 2
                else -> return -1.0
            }
        }
        for ((k, v) in addTags) if (k !in tags && entity[k] == v) s += matchScore
        return if (searchable) s else s * 0.999
    }

    override fun toString() = id
}

/** A search result: the preset, its score (higher is better), its name in the language and the text that matched. */
data class PresetHit(val preset: Preset, val score: Double, val name: String, val matched: String)

/** Every id-tagging-schema preset, offline: matching tags to a preset, names in 18 languages, and search. */
object Presets {
    private val fallbackIds = setOf("point", "line", "area", "vertex", "relation", "address")

    val all: List<Preset> by lazy { load() }
    private val byId: Map<String, Preset> by lazy { all.associateBy { it.id } }
    private val byKey: Map<String, List<Preset>> by lazy {
        val m = HashMap<String, MutableList<Preset>>()
        for (p in all) if (p.id !in fallbackIds) for (k in p.tags.keys) m.getOrPut(k) { ArrayList() }.add(p)
        m
    }

    fun byId(id: String): Preset? = byId[id]

    private fun load(): List<Preset> {
        val root = Resources.json("presets.json").obj ?: error("presets.json")
        val names = root["fields"].stringList()
        fun fieldList(e: JsonElement?): List<String> = e?.arr?.mapNotNull { i -> i.num?.toInt()?.let { names.getOrNull(it) } } ?: emptyList()
        val arr = root["presets"]?.arr ?: error("presets.json")
        return arr.mapNotNull { e ->
            val o = e.obj ?: return@mapNotNull null
            Preset(
                id = o["id"]?.str ?: return@mapNotNull null,
                tags = o["t"].stringMap(),
                addTags = o["a"].stringMap(),
                geometry = (o["g"]?.str ?: "").mapNotNull { Geometry.of(it) }.toSet(),
                icon = o["i"]?.str,
                fields = fieldList(o["f"]),
                moreFields = fieldList(o["m"]),
                matchScore = o["s"]?.num ?: 1.0,
                searchable = o["x"] == null,
                countries = o["c"].stringList().toSet(),
                excludedCountries = o["xc"].stringList().toSet(),
            )
        }
    }

    /**
     * The best preset for [tags] drawn as [geometry], by id's rules (match score, then specificity), or null when
     * nothing more specific than "a point" or "an area" fits. A point also tries vertex presets and an area point
     * presets, since an extract cannot always tell them apart.
     */
    fun match(tags: Map<String, String>, geometry: Geometry = Geometry.POINT, country: String? = null): Preset? {
        if (tags.isEmpty()) return null
        matchExactly(tags, geometry, country)?.let { return it }
        val alt = when (geometry) {
            Geometry.POINT -> Geometry.VERTEX
            Geometry.VERTEX, Geometry.AREA -> Geometry.POINT
            Geometry.RELATION -> Geometry.AREA
            Geometry.LINE -> null
        }
        alt?.let { g -> matchExactly(tags, g, country)?.let { return it } }
        // any part of an address makes it an address, where the geometry allows one (not a line)
        if (tags.keys.any { it.startsWith("addr:") }) return byId["address"]?.takeIf { geometry in it.geometry }
        return null
    }

    private fun matchExactly(tags: Map<String, String>, geometry: Geometry, country: String?): Preset? {
        var best: Preset? = null
        var bestScore = 0.0
        for (k in tags.keys) {
            val candidates = byKey[k] ?: continue
            for (p in candidates) {
                if (geometry !in p.geometry || !p.availableIn(country)) continue
                val s = p.score(tags)
                if (s > bestScore || (s == bestScore && best != null && s > 0 && better(p, best))) {
                    best = p; bestScore = s
                }
            }
        }
        return best
    }

    /** Ties go to the preset with more tags, then the searchable one, then the shorter id. */
    private fun better(a: Preset, b: Preset): Boolean = when {
        a.tags.size != b.tags.size -> a.tags.size > b.tags.size
        a.searchable != b.searchable -> a.searchable
        else -> a.id.length < b.id.length || (a.id.length == b.id.length && a.id < b.id)
    }

    /** The generic preset for a geometry: "point" (also for a vertex), "line" or "area"; null for a relation. */
    fun fallback(geometry: Geometry): Preset? = when (geometry) {
        Geometry.POINT, Geometry.VERTEX -> byId["point"]
        Geometry.LINE -> byId["line"]
        Geometry.AREA -> byId["area"]
        Geometry.RELATION -> null
    }

    /** The preset's name in [lang], else in English, else its last id segment made readable. */
    fun name(preset: Preset, lang: String? = "en"): String =
        I18n.presetName(preset.id, lang) ?: preset.id.substringAfterLast('/').replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** The preset whose icon draws [preset]: itself, else the nearest ancestor with an icon. */
    fun iconOf(preset: Preset): String? {
        var p: Preset? = preset
        while (p != null) {
            p.icon?.let { return it }
            p = p.parentId?.let { byId[it] }
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------- search

    private class Entry(val preset: Preset, val names: Array<String>, val aliases: Array<String>, val terms: Array<String>, val english: Boolean)

    private val indexes = ConcurrentHashMap<String, List<Entry>>()

    private fun index(lang: String): List<Entry> = indexes.getOrPut(lang) {
        val pack = I18n.pack(lang)
        val en = I18n.pack("en")
        val out = ArrayList<Entry>()
        for (p in all) {
            if (!p.searchable || p.id in fallbackIds) continue
            if (p.geometry.none { it == Geometry.POINT || it == Geometry.AREA || it == Geometry.VERTEX }) continue
            val own = pack.presets[p.id]
            if (own != null && lang != "en") {
                out += Entry(p, arrayOf(Text.fold(own.name)), own.aliases.map(Text::fold).toTypedArray(), own.terms.map(Text::fold).toTypedArray(), false)
            }
            val e = en.presets[p.id] ?: continue
            out += Entry(p, arrayOf(Text.fold(e.name)), e.aliases.map(Text::fold).toTypedArray(), e.terms.map(Text::fold).toTypedArray(), lang != "en")
        }
        out
    }

    /**
     * Presets whose name, aliases or search terms in [lang] (and in English) start with or nearly spell [term]:
     * case and accents do not matter, and one typo is forgiven from 4 letters, two from 8.
     */
    fun search(term: String, lang: String? = "en", limit: Int = 20, geometry: Geometry? = null, country: String? = null): List<PresetHit> {
        val q = Text.fold(term)
        if (q.isEmpty()) return emptyList()
        val l = Langs.resolve(lang)
        val best = HashMap<String, PresetHit>()
        for (e in index(l)) {
            val p = e.preset
            if (geometry != null && geometry !in p.geometry) continue
            if (!p.availableIn(country)) continue
            var score = 0.0
            var matched = ""
            fun consider(s: Double, text: String) { if (s > score) { score = s; matched = text } }
            for (n in e.names) consider(rank(q, n, 100.0), n)
            for (a in e.aliases) consider(rank(q, a, 95.0), a)
            for (t in e.terms) consider(rank(q, t, 75.0), t)
            if (score <= 0) continue
            if (e.english) score *= 0.9
            score *= 0.9 + 0.1 * p.matchScore.coerceAtMost(1.0)
            val prev = best[p.id]
            if (prev == null || score > prev.score) best[p.id] = PresetHit(p, score, name(p, l), matched)
        }
        return best.values.sortedWith(compareByDescending<PresetHit> { it.score }.thenBy { it.name.length }.thenBy { it.preset.id }).take(limit)
    }

    /** How well query [q] fits candidate [c] (both folded), scaled to [top]: exact > prefix > word prefix > typo. */
    internal fun rank(q: String, c: String, top: Double): Double {
        if (c.isEmpty()) return 0.0
        if (c == q) return top
        if (c.startsWith(q)) return top * 0.9 - (c.length - q.length) * 0.05
        if (q.length >= 2) {
            var i = c.indexOf(' ')
            while (i >= 0) {
                if (c.startsWith(q, i + 1)) return top * 0.8 - (c.length - q.length) * 0.05
                i = c.indexOf(' ', i + 1)
            }
        }
        val k = Text.typosAllowed(q.length)
        if (k == 0) return 0.0
        val whole = Text.distance(q, c, k)
        if (whole <= k) return top * (0.7 - 0.1 * whole)
        if (c.length > q.length - k) {
            // a word still being typed, with a letter wrong, missing or extra: "restur" for "restaurant"
            val prefix = Text.prefixDistance(q, c, k)
            if (prefix <= k) return top * (0.6 - 0.1 * prefix)
        }
        if (q.length >= 4 && c.contains(q)) return top * 0.5
        return 0.0
    }
}
