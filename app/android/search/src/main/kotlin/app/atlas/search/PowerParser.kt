package app.atlas.search

import app.atlas.geo.CoordinateParser
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.Cuisine
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import app.atlas.places.TagFilterException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The power syntax, close to Overpass Turbo's wizard: tag filters as `:places` [TagFilter] reads them
 * (`amenity=cafe and outdoor_seating=yes`, `cuisine~pizza`, `"socket:type2_combo:output">=150kW`), then clauses:
 * `near <filter> [200m]` (chainable), `not near <filter> [100m]`, `within 2km [of <coordinates>]`, `in <area>`,
 * `in bbox`, `around me`, `open now|tonight|late|<time>|<day>`, `global`; and anywhere `type:node`, `id:n123`,
 * `newer:2026-01-01`, `limit:20`, `sort:distance`. A clause whose filter is not tag syntax ("near park") is read as
 * natural language.
 */
internal object PowerParser {
    private val TAG_OP = Regex("""(^|[\s(!])(["'][^"']+["']|[A-Za-z_][\w:.\-]*)\s*(=|!=|~|!~|>=|<=|<|>)""")
    private val SPECIAL = Regex("""(?i)(^|\s)(type|id|newer|limit|sort):\S""")
    private val DISTANCE = Regex("""(?i)^(\d+(?:[.,]\d+)?)\s*(m|km|mi|ft)?$""")
    private val UNITS = mapOf("m" to 1.0, "km" to 1000.0, "mi" to 1609.344, "ft" to 0.3048)
    private val SORTS = mapOf(
        "distance" to SortBy.DISTANCE, "nearest" to SortBy.DISTANCE, "rating" to SortBy.RATING, "relevance" to SortBy.RELEVANCE,
        "open" to SortBy.OPEN_LONGEST, "open_longest" to SortBy.OPEN_LONGEST,
    )
    private const val DEFAULT_NEAR = 200.0
    private const val AROUND = 1_000.0

    fun looksLikePower(text: String): Boolean = TAG_OP.containsMatchIn(text) || SPECIAL.containsMatchIn(text)

    private enum class Kind { SUBJECT, NEAR, NOT_NEAR, WITHIN, IN, AROUND, OPEN, GLOBAL }

    private class Clause(val kind: Kind, val chunks: MutableList<String> = ArrayList())

    fun parse(text: String, lang: String, now: ZonedDateTime?): ParsedQuery {
        val chunks = split(text)
        val clauses = ArrayList<Clause>()
        var cur = Clause(Kind.SUBJECT).also { clauses += it }
        val specials = ArrayList<String>()
        var i = 0
        while (i < chunks.size) {
            val c = chunks[i]
            val l = c.lowercase(Locale.ROOT)
            val next = chunks.getOrNull(i + 1)?.lowercase(Locale.ROOT)
            val kind = when {
                l == "near" -> Kind.NEAR
                l == "!near" || l == "notnear" -> Kind.NOT_NEAR
                l == "not" && next == "near" -> { i++; Kind.NOT_NEAR }
                l == "within" -> Kind.WITHIN
                l == "in" -> Kind.IN
                l == "around" -> Kind.AROUND
                l == "open" -> Kind.OPEN
                l == "global" -> Kind.GLOBAL
                else -> null
            }
            if (kind != null) {
                cur = Clause(kind).also { clauses += it }
            } else if (SPECIAL.matches(" $c") || Regex("""(?i)^(type|id|newer|limit|sort):\S+$""").matches(c)) {
                specials += c
            } else {
                cur.chunks += c
            }
            i++
        }

        val chips = ArrayList<Chip>()
        val unknown = ArrayList<String>()
        var recognised = 0
        val parts = ArrayList<Query>()

        // the subject: a tag filter, else natural language
        val subjectText = trimConnectors(clauses[0].chunks).joinToString(" ")
        if (subjectText.isNotEmpty()) {
            val q = filterOrWords(subjectText, lang, now, chips, unknown, row = 0)
            if (q != null) { parts += q; recognised++ }
        }
        for (s in specials) {
            special(s, chips)?.let { parts += it; recognised++ } ?: run { unknown += s }
        }

        var query: Query = Query.and(parts)
        var nearRow = 0
        var sort: SortBy? = null
        var limit: Int? = null
        for (s in specials) {
            val key = s.substringBefore(':').lowercase(Locale.ROOT)
            val v = s.substringAfter(':').trim('"', '\'')
            if (key == "sort") sort = SORTS[v.lowercase(Locale.ROOT)]
            if (key == "limit") limit = positive(v)
        }

        for (c in clauses.drop(1)) {
            val words = trimConnectors(c.chunks)
            when (c.kind) {
                Kind.NEAR, Kind.NOT_NEAR -> {
                    nearRow++
                    val (d, rest) = trailingDistance(words)
                    val dist = d ?: DEFAULT_NEAR
                    chips += Chip(ChipKind.DISTANCE, "ui-ruler", "${dist.roundToInt()} m", dist.roundToInt().toString(), nearRow, negated = c.kind == Kind.NOT_NEAR)
                    val other = if (rest.isEmpty()) null else filterOrWords(rest.joinToString(" "), lang, now, chips, unknown, nearRow)
                    if (other == null) { unknown += "near"; continue }
                    recognised++
                    query = if (c.kind == Kind.NEAR) Query.Near(query, other, dist) else Query.NotNear(query, other, dist)
                }
                Kind.WITHIN -> {
                    val (d, rest) = leadingDistance(words)
                    if (d == null) { unknown += "within"; continue }
                    recognised++
                    val target = rest.dropWhile { it.equals("of", true) || it.equals("around", true) }.joinToString(" ")
                    val center = if (target.isEmpty() || target.equals("me", true)) null else CoordinateParser.parse(target)
                    if (target.isNotEmpty() && !target.equals("me", true) && center == null) {
                        query = Query.and(query, Query.InArea(null, target))
                        chips += Chip(ChipKind.AREA, "ui-map-pin", target, target)
                    } else {
                        query = Query.and(query, Query.Within(center, d))
                        chips += Chip(ChipKind.AREA, "ui-locate", "${format(d)}${center?.let { " · $it" } ?: ""}", d.roundToInt().toString())
                    }
                }
                Kind.IN -> {
                    val name = words.joinToString(" ")
                    if (name.isEmpty()) { unknown += "in"; continue }
                    recognised++
                    query = Query.and(query, Query.InArea(null, name))
                    chips += Chip(ChipKind.AREA, "ui-map-pin", name, name)
                }
                Kind.AROUND -> {
                    val (d, rest) = trailingDistance(words)
                    val name = rest.joinToString(" ")
                    recognised++
                    if (name.isEmpty() || name.equals("me", true)) {
                        query = Query.and(query, Query.Within(null, d ?: AROUND))
                        chips += Chip(ChipKind.AREA, "ui-locate", "around me", (d ?: AROUND).roundToInt().toString())
                    } else {
                        query = Query.and(query, Query.InArea(null, name))
                        chips += Chip(ChipKind.AREA, "ui-map-pin", name, name)
                    }
                }
                Kind.OPEN -> {
                    val parsed = NaturalParser(lang, now).parse("open " + words.joinToString(" "))
                    var open: Query.OpenAt? = null
                    parsed.query.walk { if (it is Query.OpenAt && open == null) open = it }
                    val o = open ?: Query.OpenAt(OpenTime.Now)
                    recognised++
                    query = Query.and(query, o)
                    chips += parsed.chips.filter { it.kind == ChipKind.OPEN }.map { it.unplaced() }.ifEmpty { listOf(Chip(ChipKind.OPEN, "ui-clock", "open now", "now")) }
                }
                Kind.GLOBAL -> recognised++
                Kind.SUBJECT -> {}
            }
        }
        sort?.let { query = Query.Sort(query, it); chips += Chip(ChipKind.SORT, "ui-sliders", it.name.lowercase(Locale.ROOT), it.name.lowercase(Locale.ROOT)) }
        limit?.let { query = Query.Limit(query, it); chips += Chip(ChipKind.LIMIT, "ui-filter", it.toString(), it.toString()) }

        val total = recognised + unknown.size
        val confidence = if (total == 0) 0.0 else recognised.toDouble() / total
        val steps = if (query == Query.All && parts.isEmpty()) emptyList() else listOf(query)
        return ParsedQuery(text, steps, chips, unknown, confidence, QuerySyntax.POWER, null, structured = steps.isNotEmpty())
    }

    /** A tag filter, or failing that natural language ("near park"); null when neither makes sense of it. */
    private fun filterOrWords(text: String, lang: String, now: ZonedDateTime?, chips: MutableList<Chip>, unknown: MutableList<String>, row: Int): Query? {
        val filter = try {
            TagFilter.parse(text)
        } catch (e: TagFilterException) {
            null
        }
        if (filter != null && (looksLikePower(text) || filter !is TagFilter.Has && filter !is TagFilter.And)) {
            // bare words among tags name kinds or properties, as in Overpass Turbo's wizard: "toilets wheelchair=yes"
            val words = HashMap<String, ParsedQuery>()
            collectBareWords(filter, text, lang, now, words)
            if (words.isEmpty()) {
                chips += filterChips(filter, lang, row)
                return Query.Tags(filter)
            }
            return lift(filter, words, lang, chips, row)
        }
        val words = NaturalParser(lang, now).parse(text)
        if (words.steps.isEmpty()) {
            if (filter != null) {
                chips += filterChips(filter, lang, row)
                return Query.Tags(filter)
            }
            unknown += text
            return null
        }
        unknown += words.unknown
        chips += words.chips.filter { it.step == 0 && it.row == 0 }.map { it.copy(row = row).unplaced() }
        return words.query
    }

    /** Keys written as bare words (no quotes, no operator) that read as a kind or a property in natural language. */
    private fun collectBareWords(f: TagFilter, text: String, lang: String, now: ZonedDateTime?, into: MutableMap<String, ParsedQuery>) {
        when (f) {
            is TagFilter.Has -> {
                if (Regex("""(^|[\s(])${Regex.escape(f.key)}(?=$|[\s)])""").containsMatchIn(text)) {
                    val p = NaturalParser(lang, now).parse(f.key)
                    if (p.steps.size == 1 && p.unknown.isEmpty()) into[f.key] = p
                }
            }
            is TagFilter.And -> f.parts.forEach { collectBareWords(it, text, lang, now, into) }
            is TagFilter.Or -> f.parts.forEach { collectBareWords(it, text, lang, now, into) }
            is TagFilter.Not -> collectBareWords(f.part, text, lang, now, into)
            else -> {}
        }
    }

    /** The filter as a query, its bare words replaced by what they mean. */
    private fun lift(f: TagFilter, words: Map<String, ParsedQuery>, lang: String, chips: MutableList<Chip>, row: Int): Query = when (f) {
        is TagFilter.Has -> words[f.key]?.let { p -> chips += p.chips.map { it.copy(row = row).unplaced() }; p.query } ?: Query.Tags(f).also { chips += filterChips(f, lang, row) }
        is TagFilter.And -> Query.and(f.parts.map { lift(it, words, lang, chips, row) })
        is TagFilter.Or -> Query.or(f.parts.map { lift(it, words, lang, chips, row) })
        is TagFilter.Not -> Query.Not(lift(f.part, words, lang, chips, row))
        else -> Query.Tags(f).also { chips += filterChips(f, lang, row) }
    }

    /** One chip per part of a conjunction, with the preset's symbol and name when the part names one. */
    private fun filterChips(f: TagFilter, lang: String, row: Int): List<Chip> {
        val parts = if (f is TagFilter.And) f.parts else listOf(f)
        return parts.map { p ->
            val sample = Lexicon.sampleOf(p)
            val preset = if (p is TagFilter.Equals) Presets.match(sample) else null
            val attribute = p.keys().firstOrNull()?.let { Lexicon.attributeByKey[it] }
            val symbol = when {
                sample.isEmpty() -> "ui-filter"
                sample.keys.first() == "cuisine" -> Cuisine.symbol(sample.values.first())
                else -> Symbols.symbolFor(sample).takeIf { it != Symbols.MARKER } ?: attribute?.symbol ?: "ui-filter"
            }
            val label = preset?.takeIf { it.tags.size == 1 && it.tags[p.keys().first()] == (p as TagFilter.Equals).value }?.name(lang)?.lowercase(Locale.ROOT) ?: p.toText()
            // a property ("wheelchair=yes") reads as one whatever its place; the first part names the kind
            val kind = when {
                attribute != null && preset == null -> ChipKind.PROPERTY
                p === parts[0] || preset != null -> ChipKind.KIND
                else -> ChipKind.FILTER
            }
            Chip(kind, symbol, label, p.toText(), row)
        }
    }

    /** Chips read from part of the text by the natural parser: their positions are not positions in the whole text. */
    private fun Chip.unplaced() = copy(start = -1, end = -1)

    private fun special(s: String, chips: MutableList<Chip>): Query? {
        val key = s.substringBefore(':').lowercase(Locale.ROOT)
        val v = s.substringAfter(':').trim('"', '\'')
        return when (key) {
            "type" -> {
                val t = when (v.lowercase(Locale.ROOT)) {
                    "node", "n" -> OsmType.NODE
                    "way", "w" -> OsmType.WAY
                    "relation", "rel", "r" -> OsmType.RELATION
                    "nwr", "any" -> return Query.All.also { chips += Chip(ChipKind.TYPE, "ui-cube", "any", "nwr") }
                    else -> return null
                }
                chips += Chip(ChipKind.TYPE, "ui-cube", t.name.lowercase(Locale.ROOT), t.name.lowercase(Locale.ROOT))
                Query.Type(t)
            }
            "id" -> {
                val id = OsmId.parse(v) ?: v.toLongOrNull()?.let { OsmId(OsmType.NODE, it) } ?: return null
                chips += Chip(ChipKind.ID, "ui-tag", id.toString(), id.toString())
                Query.Id(id)
            }
            "newer" -> {
                val t = runCatching { Instant.parse(v) }.getOrNull()
                    ?: runCatching { LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull() ?: return null
                chips += Chip(ChipKind.NEWER, "ui-history", "since ${v.take(10)}", t.toString())
                Query.Newer(t)
            }
            // read with the clauses; a count that is not a positive number, or an unknown order, is not understood
            "limit" -> if (positive(v) != null) Query.All else null
            "sort" -> if (SORTS[v.lowercase(Locale.ROOT)] != null) Query.All else null
            else -> null
        }
    }

    private fun positive(v: String): Int? = v.toIntOrNull()?.takeIf { it > 0 }

    /** Splits on spaces outside quotes and parentheses. */
    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in text) {
            when {
                quote != null -> { sb.append(c); if (c == quote) quote = null }
                c == '"' || c == '\'' -> { sb.append(c); quote = c }
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c.isWhitespace() && depth <= 0 -> if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun trimConnectors(chunks: List<String>): List<String> {
        val c = chunks.toMutableList()
        while (c.isNotEmpty() && c.first().lowercase(Locale.ROOT) in CONNECTORS) c.removeAt(0)
        while (c.isNotEmpty() && c.last().lowercase(Locale.ROOT) in CONNECTORS) c.removeAt(c.size - 1)
        return c
    }

    private val CONNECTORS = setOf("and", "or", "&&", "||", "&", "|")

    /** A distance at the end ("… 200m", "… 2 km"), and the chunks before it. */
    private fun trailingDistance(chunks: List<String>): Pair<Double?, List<String>> {
        if (chunks.isEmpty()) return null to chunks
        distance(chunks.last())?.let { return it to chunks.dropLast(1) }
        if (chunks.size >= 2) {
            val unit = UNITS[chunks.last().lowercase(Locale.ROOT)]
            val n = chunks[chunks.size - 2].replace(',', '.').toDoubleOrNull()
            if (unit != null && n != null) return n * unit to chunks.dropLast(2)
        }
        return null to chunks
    }

    /** A distance at the start ("2km of …"), and the chunks after it. */
    private fun leadingDistance(chunks: List<String>): Pair<Double?, List<String>> {
        if (chunks.isEmpty()) return null to chunks
        if (chunks.size >= 2) {
            val unit = UNITS[chunks[1].lowercase(Locale.ROOT)]
            val n = chunks[0].replace(',', '.').toDoubleOrNull()
            if (unit != null && n != null) return n * unit to chunks.drop(2)
        }
        distance(chunks[0])?.let { return it to chunks.drop(1) }
        return null to chunks
    }

    private fun distance(s: String): Double? {
        val m = DISTANCE.matchEntire(s) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase(Locale.ROOT).ifEmpty { "m" }
        return n * (UNITS[unit] ?: return null)
    }

    private fun format(m: Double): String = if (m >= 1000) "${(m / 100).roundToInt() / 10.0} km".replace(".0 km", " km") else "${m.roundToInt()} m"
}
