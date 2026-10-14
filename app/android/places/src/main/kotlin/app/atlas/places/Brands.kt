package app.atlas.places

import kotlin.math.ln

/**
 * A chain from the name-suggestion-index (or a branded place of the prototype's extracts that NSI lacks): its
 * names, Wikidata item, the main tag of its branches, where it trades, and its logo and sign colours when known.
 */
class Brand internal constructor(
    /** NSI's id ("albertheijn-1cf55d"), or "osm-…" for a brand seen only in OSM data. */
    val id: String,
    /** NSI's display name: unique, sometimes with a region ("Jumbo (Europe)"). */
    val name: String,
    /** The value of the `brand` tag on its branches ("Jumbo"). */
    val brand: String,
    /** The `brand:wikidata` item, or null. */
    val wikidata: String?,
    /** The main tag of its branches, e.g. {shop=supermarket}. */
    val keyTags: Map<String, String>,
    /** ISO countries (lower case) where it trades; empty means everywhere. */
    val countries: Set<String>,
    val excludedCountries: Set<String>,
    /** How widely it trades: the number of its countries, 250 for everywhere. */
    val spread: Int,
    /** The simple-icons slug of its glyph, or null. */
    val logoSlug: String?,
    /** Its sign colour (opaque ARGB), or null. */
    val colour: Int?,
    /** The text colour on its sign, or null (then white or black by contrast). */
    val textColour: Int?,
    /** Other names it is mapped under. */
    val matchNames: List<String>,
    /** Further tags its branches carry (cuisine, …). */
    val otherTags: Map<String, String>,
) {
    /** The brand's glyph (a 24×24 filled path), or null. */
    val logo: Icon? get() = logoSlug?.let { Symbols.icon("logo-$it") }

    /** The tags a new branch gets: brand, brand:wikidata, name, the main tag and the rest. */
    val tags: Map<String, String>
        get() = buildMap {
            putAll(otherTags)
            putAll(keyTags)
            put("brand", brand)
            put("name", brand)
            wikidata?.let { put("brand:wikidata", it) }
        }

    fun availableIn(country: String?): Boolean {
        if (country == null) return true
        val c = country.lowercase()
        return (countries.isEmpty() || c in countries) && c !in excludedCountries
    }

    override fun toString() = "$name ($id)"
}

/**
 * Brands, offline: 14,000 chains of the common keys (eating and drinking, fuel and charging, banks, pharmacies, every
 * shop, hotels, gyms) from the name-suggestion-index, with simple-icons glyphs and sign colours where known.
 * Read from `brands.tsv` on first use.
 */
object Brands {
    private class Data(
        val all: List<Brand>,
        val byId: Map<String, Brand>,
        val byWikidata: Map<String, List<Brand>>,
        val byKey: Map<String, List<Brand>>,
        val folded: Array<Array<String>>,
    )

    private val data: Data by lazy { load() }

    private fun load(): Data {
        val text = Resources.text("brands.tsv") ?: error("missing brands.tsv")
        val all = ArrayList<Brand>(15000)
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            if (text[start] != '#' && end > start) all += row(text.substring(start, end).split('\t'))
            start = end + 1
        }
        val byId = HashMap<String, Brand>(all.size * 2)
        val byWikidata = HashMap<String, MutableList<Brand>>()
        val byKey = HashMap<String, MutableList<Brand>>()
        for (b in all) {
            byId[b.id] = b
            b.wikidata?.let { byWikidata.getOrPut(it) { ArrayList(2) }.add(b) }
            for (n in names(b)) {
                val k = key(n)
                if (k.isNotEmpty()) byKey.getOrPut(k) { ArrayList(2) }.let { if (b !in it) it.add(b) }
            }
        }
        val folded = Array(all.size) { i -> names(all[i]).map(Text::fold).distinct().toTypedArray() }
        return Data(all, byId, byWikidata, byKey, folded)
    }

    private fun names(b: Brand): List<String> = listOf(b.name, b.brand) + b.matchNames

    private fun row(c: List<String>): Brand {
        fun col(i: Int) = c.getOrNull(i) ?: ""
        val kv = col(4)
        val name = col(1)
        return Brand(
            id = col(0),
            name = name,
            brand = col(2).ifEmpty { name },
            wikidata = col(3).ifEmpty { null },
            keyTags = if ('=' in kv) mapOf(kv.substringBefore('=') to kv.substringAfter('=')) else emptyMap(),
            countries = splitSet(col(5)),
            excludedCountries = splitSet(col(6)),
            spread = col(7).toIntOrNull() ?: 0,
            logoSlug = col(8).ifEmpty { null },
            colour = parseColour(col(9)),
            textColour = parseColour(col(10)),
            matchNames = col(11).let { m -> if (m.isEmpty()) emptyList() else m.split('|').filter { it.isNotEmpty() } },
            otherTags = otherTags(col(12)),
        )
    }

    /**
     * The "other tags" column: `k=v` pairs joined by ";". A value may itself be a ";" list (`cuisine=burger;pizza`),
     * so a part without "=" continues the value before it.
     */
    internal fun otherTags(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        for (part in s.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                key = part.substring(0, eq)
                out[key] = part.substring(eq + 1)
            } else if (key != null && part.isNotEmpty()) {
                out[key] = out[key] + ";" + part
            }
        }
        return out
    }

    private fun splitSet(s: String): Set<String> = if (s.isEmpty()) emptySet() else s.split(',').toSet()

    /** The matching key of a brand name: folded, "&" as "and", letters and digits only ("McDonald's" -> "mcdonalds"). */
    fun key(name: String): String {
        val f = Text.fold(name.replace("&", " and ").replace("+", " plus "))
        val sb = StringBuilder(f.length)
        for (ch in f) if (ch.isLetterOrDigit()) sb.append(ch)
        return sb.toString()
    }

    /** Every brand, or those trading in [country], widest first. */
    fun all(country: String? = null): List<Brand> =
        data.all.filter { it.availableIn(country) }.sortedWith(compareByDescending<Brand> { it.spread }.thenBy { it.name.lowercase() })

    fun byId(id: String): Brand? = data.byId[id]

    /** The brands with this `brand:wikidata` (one chain can trade under several NSI entries). */
    fun byWikidata(qid: String): List<Brand> = data.byWikidata[qid] ?: emptyList()

    /**
     * The brand of a mapped place: by `brand:wikidata`, else by its `brand` name with the same main key (amenity,
     * shop…), else by its `name` when the main tag matches exactly. Among several, the one whose main tag matches
     * and that trades in [country] wins.
     */
    fun forTags(tags: Map<String, String>, country: String? = null): Brand? {
        val c = country ?: tags["addr:country"]
        val qid = tags["brand:wikidata"]?.trim()?.takeIf { it.isNotEmpty() }
        qid?.let { data.byWikidata[it] }?.let { list -> return best(list, tags, c) }
        // a different Wikidata item means a different company, whatever the name
        fun sameItem(b: Brand) = qid == null || b.wikidata == null || b.wikidata == qid
        tags["brand"]?.let { b ->
            val list = data.byKey[key(b)]?.filter { sameKey(it, tags) && sameItem(it) }
            if (!list.isNullOrEmpty()) return best(list, tags, c)
        }
        tags["name"]?.let { n ->
            val list = data.byKey[key(n)]?.filter { exactTag(it, tags) && sameItem(it) }
            if (!list.isNullOrEmpty()) return best(list, tags, c)
        }
        return null
    }

    private fun exactTag(b: Brand, tags: Map<String, String>) = b.keyTags.isNotEmpty() && b.keyTags.all { (k, v) -> tags[k] == v }
    private fun sameKey(b: Brand, tags: Map<String, String>) = b.keyTags.isEmpty() || b.keyTags.keys.any { it in tags }

    private fun best(list: List<Brand>, tags: Map<String, String>, country: String?): Brand =
        list.maxBy { b ->
            (if (exactTag(b, tags)) 8 else 0) + (if (sameKey(b, tags)) 4 else 0) +
                (if (country != null && b.availableIn(country)) 2 else 0) + (if (b.logoSlug != null) 1 else 0) + b.spread / 1000.0
        }

    /**
     * Brands whose name starts with or nearly spells [text], case and accents ignored; those trading in [country]
     * and the widespread first.
     */
    fun search(text: String, country: String? = null, limit: Int = 20): List<Brand> {
        val q = Text.fold(text)
        if (q.isEmpty()) return emptyList()
        val d = data
        val hits = ArrayList<Pair<Brand, Double>>()
        for (i in d.all.indices) {
            val b = d.all[i]
            var s = 0.0
            for (n in d.folded[i]) s = maxOf(s, Presets.rank(q, n, 100.0))
            if (s <= 0) continue
            if (country != null && !b.availableIn(country)) s *= 0.5
            s += ln(1.0 + b.spread)
            hits += b to s
        }
        hits.sortWith(compareByDescending<Pair<Brand, Double>> { it.second }.thenBy { it.first.name.length })
        val seen = HashSet<String>()
        val out = ArrayList<Brand>()
        for ((b, _) in hits) {
            // one entry per chain: NSI lists some chains once per region
            if (seen.add(b.wikidata ?: b.id)) out += b
            if (out.size >= limit) break
        }
        return out
    }
}
