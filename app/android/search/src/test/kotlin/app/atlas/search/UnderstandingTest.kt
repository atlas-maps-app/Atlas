package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brands
import app.atlas.places.ChangeKind
import app.atlas.places.TagFilter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The catalogue of what people type (`understanding.tsv`: 649 queries in English, Dutch, German and French, written on
 * 4 November 2026 for the user's "everything, no matter how complex, should be possible"), each read as the search
 * reads it and every fact it must mean checked: a kind's and a tag's by what the query does to sample places (a
 * pizzeria passes "pizza", a bench does not), the rest by the query's shape (an order, an area, a time, a route).
 * Facts the catalogue marks as beyond the query model (`unsupported=`) are counted, not checked. The report of every
 * line that reads otherwise is written to `build/understanding.txt`; the count that reads right only goes up.
 */
class UnderstandingTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    /** The catalogue's clock: Tuesday 3 November 2026, 14:00 in Amsterdam. */
    private val now = ZonedDateTime.of(2026, 11, 3, 14, 0, 0, 0, zone)
    private val here = LatLon(52.3744, 4.8838)

    data class Line(val section: String, val lang: String, val text: String, val expect: List<String>, val freq: String, val note: String)

    /** One fact checked: what it said, and why it failed (null when it held; [SKIPPED] when it is beyond the model). */
    data class Checked(val fact: String, val failure: String?)

    @Test
    fun everyQueryReadsAsMeant() {
        val lines = read()
        val checked = lines.map { it to check(it) }
        val failing = checked.filter { (_, cs) -> cs.any { it.failure != null && it.failure != SKIPPED } }
        val report = StringBuilder()
        report.appendLine("${lines.size - failing.size} of ${lines.size} queries read as meant")
        for ((section, group) in checked.groupBy { it.first.section }) {
            val bad = group.count { (_, cs) -> cs.any { it.failure != null && it.failure != SKIPPED } }
            report.appendLine("  $section: ${group.size - bad}/${group.size}")
        }
        val byFact = HashMap<String, Int>()
        for ((_, cs) in checked) for (c in cs) if (c.failure != null && c.failure != SKIPPED) byFact.merge(c.fact.substringBefore('='), 1, Int::plus)
        report.appendLine("failing facts: " + byFact.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        report.appendLine()
        for ((line, cs) in failing) {
            val p = QueryParser.parse(line.text, line.lang, now, COUNTRY)
            report.appendLine("[${line.section}] ${line.lang} \"${line.text}\"")
            for (c in cs.filter { it.failure != null && it.failure != SKIPPED }) report.appendLine("    ✗ ${c.fact}: ${c.failure}")
            report.appendLine("    read: ${describe(p)}")
        }
        File("build/understanding.txt").writeText(report.toString())
        println(report.lines().take(40).joinToString("\n"))
        val right = lines.size - failing.size
        assertTrue("$right of ${lines.size} read as meant (see build/understanding.txt)", right >= READ_RIGHT)
    }

    // --------------------------------------------------------------------------------------------------- reading

    private fun read(): List<Line> {
        val text = javaClass.getResourceAsStream("/understanding.tsv")!!.bufferedReader().readText()
        var section = ""
        val out = ArrayList<Line>()
        for (raw in text.lines()) {
            if (raw.startsWith("# section:")) section = raw.substringAfter(':').trim()
            if (raw.isBlank() || raw.startsWith("#")) continue
            val cols = raw.split('\t')
            out += Line(section, cols[0], cols[1], cols[2].split(" ; ").map { it.trim() }, cols.getOrElse(3) { "" }, cols.getOrElse(4) { "" })
        }
        return out
    }

    // -------------------------------------------------------------------------------------------------- checking

    private fun check(line: Line): List<Checked> {
        val p = QueryParser.parse(line.text, line.lang, now, COUNTRY)
        val all = buildList { for (s in p.steps) s.walk { add(it) } }
        val main = p.query
        val tagFacts = line.expect.filter { it.startsWith("tag=") }.map { it.removePrefix("tag=") }
        val positives = tagFacts.fold(emptyMap<String, String>()) { m, t -> m + positive(t) }
        val kinds = line.expect.firstOrNull { it.startsWith("kind=") }?.removePrefix("kind=")?.let(::alternatives)
        return line.expect.map { fact ->
            val (key, value) = fact.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val failure = when {
                key == "unsupported" -> SKIPPED
                key == "kind" -> kindFails(kinds!!, tagFacts, main, p)
                key == "tag" -> tagFails(value, kinds, tagFacts, main, all, line.expect)
                key == "brand" -> if (all.any { brandIs(it, value) } || !p.isQuery && Words.fold(line.text).let { t -> Words.fold(value).let { v -> t.contains(v) || v.contains(t) } }) null else "no brand ${value}"
                // a name not read as a query is searched for as words
                key == "name" -> if (all.any { nameIs(it, value) } || !p.isQuery) null else "no name $value"
                key == "want" -> if (all.any { it is Query.Want && keysOf(it.part).any { k -> sameKey(k, value) } }) null else "no want of $value"
                key == "inside" -> if (all.any { it is Query.Inside && acceptsAny(it.container, alternatives(value)) }) null else "nothing inside $value"
                // what a place has of its own, or says it has ("a campsite with a pool": swimming_pool=yes, or one at it)
                key == "contains" -> if (all.any { it is Query.Contains && acceptsAny(it.part, alternatives(value)) || it is Query.Want && it.nearby?.let { n -> acceptsAny(n, alternatives(value)) } == true }) null else "nothing containing $value"
                key == "near" -> nearFails(value, all.filterIsInstance<Query.Near>().map { it.other to it.maxDistance }, positives)
                key == "notnear" -> nearFails(value, all.filterIsInstance<Query.NotNear>().map { it.other to it.minDistance }, positives)
                key == "prefer" -> preferFails(value, all, p)
                key == "sort" -> sortFails(value, all)
                key == "limit" -> if (all.any { it is Query.Limit && it.count == value.toInt() }) null else "no limit $value"
                key == "ask" -> when (value) {
                    "COUNT", "HOURS" -> if (p.ask.name == value) null else "asks ${p.ask}"
                    else -> if (p.ask == Ask.FACT && p.asked == value) null else "asks ${p.ask} ${p.asked ?: ""}".trim()
                }
                key == "area" -> areaFails(value, all.filter { q -> all.none { it is Query.Not && it.part === q } })
                // "not in the city centre", "outside Amsterdam": an area kept out of
                key == "notarea" -> areaFails(value, all.filterIsInstance<Query.Not>().map { it.part })
                key == "closed" -> if (all.any { it is Query.ClosedOn && it.day == DAYS[value] }) null else "not closed on $value"
                // "taller than the Westertoren": key, comparison and the other place's name ("height>Westertoren")
                key == "than" -> if (all.any { it is Query.Than && Words.fold("${it.key}${it.op.symbol}${(it.other as? Query.Name)?.text}") == Words.fold(value) }) null else "no comparison $value"
                key == "per" -> if (p.per == value) null else "counted per ${p.per}"
                // the stops on a way, in order ("Amersfoort|Hilversum")
                key == "via" -> p.route?.via.orEmpty().map { Words.fold(it) }.let { v -> if (v == value.split('|').map { Words.fold(it) }) null else "via $v" }
                key == "open" -> openFails(value, all)
                key == "closes" -> changeFails(ChangeKind.CLOSES, value, all)
                key == "opens" -> changeFails(ChangeKind.OPENS, value, all)
                key == "within" -> withinFails(value.toDouble(), all)
                key == "colour" -> if (all.any { it is Query.Coloured && it.family == value }) null else "no colour $value"
                key == "intent" -> if (p.intent == value || p.intent in SAME_INTENT[value].orEmpty()) null else "intent ${p.intent}"
                key == "route" -> routeFails(value, p, line.lang)
                key.startsWith("rating") -> if (all.any { it is Query.Rated && it.value == fact.filter { c -> c.isDigit() || c == '.' }.toDouble() }) null else "no rating"
                else -> "unknown fact"
            }
            Checked(fact, failure)
        }
    }

    /** A kind: a place of one of [kinds] (with the tags asked) passes the query; one of another kind does not. */
    private fun kindFails(kinds: List<Map<String, String>>, tagFacts: List<String>, main: Query, p: ParsedQuery): String? {
        if (p.steps.isEmpty()) return "not read as a query"
        val with = tagFacts.fold(emptyMap<String, String>()) { m, t -> m + positive(t) }
        val local = local(main)
        if (kinds.none { accepts(local, it + with) }) return "takes no ${kinds.joinToString("|") { it.entries.joinToString(",") }}"
        // a kind named by its values must leave other kinds out
        if (kinds.all { k -> k[ANY_KEY] != null }) return null
        val foreign = if (kinds.any { "amenity" in it }) mapOf("shop" to "hairdresser") else mapOf("amenity" to "bench")
        return if (accepts(local, foreign + with)) "takes any kind" else null
    }

    /**
     * A tag: a place with it passes, the same place without it does not; the subject's, or the place of a row the
     * query names ("a playground near a café with a terrace": the café's terrace).
     */
    private fun tagFails(t: String, kinds: List<Map<String, String>>?, tagFacts: List<String>, main: Query, all: List<Query>, expect: List<String>): String? {
        val failure = tagFailsOn(t, kinds, tagFacts, main, all) ?: return null
        val rows = expect.filter { it.startsWith("near=") || it.startsWith("contains=") || it.startsWith("inside=") }
            .map { alternatives(it.substringAfter('=').substringBefore('@')) }
        val others = all.mapNotNull { q ->
            when (q) {
                is Query.Near -> q.other
                is Query.Contains -> q.part
                is Query.Inside -> q.container
                else -> null
            }
        }
        for (other in others) for (k in rows) {
            val with = k.map { it + positive(t) }
            if (acceptsAny(other, with) && tagFailsOn(t, k, emptyList(), other, buildList { other.walk { add(it) } }) == null) return null
        }
        return failure
    }

    private fun tagFailsOn(t: String, kinds: List<Map<String, String>>?, tagFacts: List<String>, main: Query, all: List<Query>): String? {
        val local = local(main)
        val base = (kinds?.firstOrNull { accepts(local, it + tagFacts.fold(emptyMap<String, String>()) { m, f -> m + positive(f) }) } ?: kinds?.firstOrNull()).orEmpty()
        val others = tagFacts.filter { it != t }.fold(emptyMap<String, String>()) { m, f -> m + positive(f) }
        val yes = base + others + positive(t)
        if (!accepts(local, yes)) return "a place with it does not pass"
        val no = (base + others).let { m -> negative(t)?.let { m.filterKeys { k -> k != tagKey(t) } + it } ?: m.filterKeys { k -> k != tagKey(t) } }
        // "happy hour": a key that is set or not is a wish when the places that leave it out stay, unsure
        if (negative(t) == null && all.any { it is Query.Want && keysOf(it.part).any { k -> sameKey(k, tagKey(t)) } }) return null
        if (accepts(local, no)) {
            val soft = all.any { (it is Query.Want || it is Query.Prefer) && keysOf(it).any { k -> sameKey(k, tagKey(t)) } }
            return if (soft) "a wish, not a rule" else "a place without it passes too"
        }
        return null
    }

    /** A place near: one of the kind said, with what the query asks of it ("near a café with a terrace"). */
    private fun nearFails(value: String, nears: List<Pair<Query, Double>>, with: Map<String, String>): String? {
        val (what, metres) = value.split('@').let { it[0] to it.getOrNull(1)?.toDoubleOrNull() }
        val found = nears.filter { (other, _) ->
            when {
                what.startsWith("name=") -> other.let { o -> buildList { o.walk { add(it) } } }.any { nameIs(it, what.removePrefix("name=")) || brandIs(it, what.removePrefix("name=")) }
                else -> alternatives(what).let { k -> acceptsAny(other, k) || acceptsAny(other, k.map { it + with }) }
            }
        }
        if (found.isEmpty()) return if (nears.isEmpty()) "nothing near" else "near something else"
        if (metres != null && found.none { (_, d) -> d in metres * 0.5..metres * 1.5 }) return "near within ${found.map { it.second.toInt() }}"
        return null
    }

    private fun preferFails(value: String, all: List<Query>, p: ParsedQuery): String? {
        val prefers = all.filterIsInstance<Query.Prefer>().map { it.part } + all.filterIsInstance<Query.Want>().flatMap { w ->
            listOfNotNull(w.part, w.nearby?.let { n -> Query.Near(Query.All, n, w.metres) })
        }
        val (kind, what) = value.split('=', limit = 2).let { it[0] to it[1] }
        // "well lit route home": the router's own preference
        p.route?.prefs?.let { r -> if (kind == "tag" && ROUTE_PREFS[what]?.invoke(r) == true) return null }
        val ok = prefers.any { part ->
            val parts = buildList { part.walk { add(it) } }
            when (kind) {
                "near" -> parts.any { it is Query.Near && acceptsAny(it.other, alternatives(what)) }
                "notnear" -> parts.any { it is Query.NotNear && acceptsAny(it.other, alternatives(what)) }
                "tag" -> accepts(local(part), positive(what)) && !accepts(local(part), negative(what) ?: emptyMap())
                else -> false
            }
        }
        return if (ok) null else "no wish of $value"
    }

    private fun sortFails(value: String, all: List<Query>): String? {
        val sorts = all.filterIsInstance<Query.Sort>().map { it.by.name }
        val ok = value in sorts || sorts.any { it in SAME_SORT[value].orEmpty() }
        return if (ok) null else "sorted ${sorts.ifEmpty { listOf("as found") }}"
    }

    private fun areaFails(value: String, all: List<Query>): String? {
        val names = all.filterIsInstance<Query.InArea>().mapNotNull { it.name }
        val want = Words.fold(value)
        return if (names.any { n -> Words.fold(n).let { it == want || it.contains(want) || want.contains(it) } }) null else "areas $names"
    }

    private fun openFails(value: String, all: List<Query>): String? {
        val times = all.filterIsInstance<Query.OpenAt>().map { it.time }
        if (value == "24/7") {
            val tagged = all.any { it is Query.Tags && it.filter.toText().contains("24/7") }
            return if (tagged || times.isNotEmpty()) null else "not open day and night"
        }
        val parts = value.split(' ')
        val day = DAYS[parts[0]]
        val time = (if (day == null) parts[0] else parts.getOrNull(1))?.let { t -> TIMES[t] ?: runCatching { LocalTime.parse(t) }.getOrNull() }
        val ok = times.any { t ->
            when {
                value == "now" -> t == OpenTime.Now
                value == "late" -> t == OpenTime.Late || (t is OpenTime.Today && t.time == LATE)
                day != null -> when (t) {
                    is OpenTime.OnDay -> t.day == day && (time == null || t.time?.let { near(it, time) } == true)
                    is OpenTime.At -> t.time.dayOfWeek == day && (time == null || near(t.time.toLocalTime(), time))
                    // today is a Tuesday
                    is OpenTime.Today -> day == now.dayOfWeek && (time == null || near(t.time, time))
                    else -> false
                }
                time != null -> when (t) {
                    // "open until 22:00" is open at 21:59, "before 8" at 7:45
                    is OpenTime.Today -> near(t.time, time)
                    is OpenTime.At -> near(t.time.toLocalTime(), time)
                    OpenTime.Tonight -> time == TONIGHT
                    OpenTime.Late -> time == LATE
                    else -> false
                }
                else -> false
            }
        }
        return if (ok) null else "open ${times.ifEmpty { listOf("any time") }}"
    }

    /** True when [t] is [want] or up to half an hour before it, past midnight too. */
    private fun near(t: LocalTime, want: LocalTime): Boolean {
        val before = ((want.toSecondOfDay() - t.toSecondOfDay()) / 60 + 1440) % 1440
        return before <= 30
    }

    private fun changeFails(kind: ChangeKind, value: String, all: List<Query>): String? {
        val at = LocalTime.parse(value)
        val changes = all.filterIsInstance<Query.ChangesAt>()
        return if (changes.any { it.kind == kind && it.at == at }) null else "changes ${changes.map { "${it.kind} ${it.at}" }}"
    }

    private fun withinFails(metres: Double, all: List<Query>): String? {
        val radii = all.filterIsInstance<Query.Within>().map { it.radius }
        return if (radii.any { it in metres * 0.7..metres * 1.3 }) null else "within ${radii.map { it.toInt() }}"
    }

    private fun routeFails(value: String, p: ParsedQuery, lang: String): String? {
        val (from, to) = value.split("->").let { it[0] to it[1] }
        // "get coffee then take the train": a plan from here whose last step is the end
        if (p.route == null && p.isPlan && from == "here" && '=' in to) return if (acceptsAny(local(p.steps.last()), alternatives(to))) null else "plan ends elsewhere"
        val r = p.route ?: return "no route"
        fun end(text: String?, want: String): Boolean = when {
            want == "here" -> text == null
            text == null -> false
            want in PLACES_OF_ONES_OWN -> Words.fold(text).split(' ').any { it in PLACES_OF_ONES_OWN.getValue(want) }
            '=' in want -> acceptsAny(local(QueryParser.parse(text, lang, now, COUNTRY).query), alternatives(want))
            else -> Words.fold(text).let { t -> Words.fold(want).let { w -> t.contains(w) || w.contains(t) } }
        }
        return when {
            !end(r.from, from) -> "from ${r.from ?: "here"}"
            !end(r.to, to) -> "to ${r.to}"
            else -> null
        }
    }

    // ---------------------------------------------------------------------------------------------- the samples

    /** The part of [q] that a place's own tags decide: kinds and tags, without what needs other places or a clock. */
    private fun local(q: Query): Query = when (q) {
        is Query.And -> Query.and(q.parts.map(::local))
        is Query.Or -> q.parts.map(::local).let { ps -> if (ps.any { it == Query.All }) Query.All else Query.or(ps) }
        is Query.Not -> (q.part as? Query.Brand)?.let { identity(it) } ?: local(q.part).let { if (it == Query.All) Query.All else Query.Not(it) }
        is Query.Sort -> local(q.query)
        is Query.Limit -> local(q.query)
        is Query.Near -> local(q.subject)
        is Query.NotNear -> local(q.subject)
        is Query.Inside -> local(q.subject)
        is Query.Contains -> local(q.subject)
        // a want stays: it fails a place that says otherwise
        is Query.Want -> local(q.part).let { if (it == Query.All) Query.All else Query.Want(it) }
        // a brand's branches are of its kind; what is not the brand is told by its name
        is Query.Brand -> Brands.byId(q.id)?.keyTags?.entries?.firstOrNull { it.key in KIND_KEYS }?.let { Query.Tags(TagFilter.Equals(it.key, it.value)) } ?: Query.All
        is Query.Tags, is Query.Category -> q
        else -> Query.All
    }

    /** "Not Lidl": a place that is the brand by its brand tag, its Wikidata or its name. */
    private fun identity(q: Query.Brand): Query {
        val b = Brands.byId(q.id) ?: return Query.All
        return Query.Not(Query.Tags(TagFilter.Or(listOfNotNull(TagFilter.Equals("brand", b.brand), b.wikidata?.let { TagFilter.Equals("brand:wikidata", it) }, TagFilter.Equals("name", b.brand)))))
    }

    /** True when a place with [tags] here passes [q]. */
    private fun accepts(q: Query, tags: Map<String, String>): Boolean {
        if (q == Query.All) return true
        // what a tag implies is mapped with it (a lake is natural=water with water=lake); the marks of the samples go
        val mapped = tags.filterKeys { it != ANY_KEY }.let { m -> m.keys.mapNotNull { IMPLIED[it] }.fold(m) { acc, extra -> extra + acc } }
        val place = Place(OsmId(OsmType.NODE, 1L), here, mapped + ("name" to "Sample"))
        return OfflineEvaluator(MemoryPlaces(listOf(place))).matches(local(q), EvalContext(location = here, now = now)).isNotEmpty()
    }

    private fun acceptsAny(q: Query, kinds: List<Map<String, String>>): Boolean = kinds.any { accepts(q, it) }

    /**
     * "amenity=restaurant|amenity=fast_food", "natural=wood|landuse=forest", "shop=*": each alternative as sample tags;
     * "highway=*+tunnel=yes" is one alternative of both.
     */
    private fun alternatives(text: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        var key = ""
        for (part in text.split('|')) {
            var sample = emptyMap<String, String>()
            for (piece in part.split('+')) {
                val (k, v) = if ('=' in piece) piece.split('=', limit = 2).let { it[0] to it[1] } else key to piece
                key = k
                sample = sample + if (v == ANY) mapOf(k to (TYPICAL[k] ?: "yes"), ANY_KEY to k) else mapOf(k to v)
            }
            out += sample
        }
        return out
    }

    private fun tagKey(t: String): String = TAG.matchEntire(t)!!.groupValues[1].replace("*", "type2_combo")

    /** Tags that meet the tag fact [t] ("diet:vegan=yes|only", "start_date<1900", "brand!=*", "toilets"). */
    private fun positive(t: String): Map<String, String> {
        val (k0, op, v) = TAG.matchEntire(t)!!.destructured
        val k = k0.replace("*", "type2_combo")
        val first = v.substringBefore('|')
        return when (op) {
            "=" -> mapOf(k to (if (first == ANY) TYPICAL[k] ?: "yes" else first))
            "!=" -> emptyMap()
            "<" -> mapOf(k to number(first, -10))
            "<=", ">=" -> mapOf(k to number(first, 0))
            ">" -> mapOf(k to number(first, 10))
            else -> mapOf(k to "yes")
        }
    }

    /** Tags that break the tag fact [t], or null where leaving the key out breaks it. */
    private fun negative(t: String): Map<String, String>? {
        val (k0, op, v) = TAG.matchEntire(t)!!.destructured
        val k = k0.replace("*", "type2_combo")
        val first = v.substringBefore('|')
        return when (op) {
            "=" -> if (first == ANY) null else mapOf(k to if (first == "yes") "no" else "other")
            "!=" -> mapOf(k to if (first == ANY) "x" else first)
            "<", "<=" -> mapOf(k to number(first, 10))
            ">", ">=" -> mapOf(k to number(first, -10))
            else -> null
        }
    }

    private fun number(v: String, by: Int): String = v.toDoubleOrNull()?.let { (it + by).let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() } } ?: v

    private fun keysOf(q: Query): Set<String> = buildSet { q.walk { if (it is Query.Tags) addAll(it.filter.keys()) } }

    private fun sameKey(a: String, b: String): Boolean = a == b || a.startsWith("$b:") || b.startsWith("$a:") ||
        (a.contains(':') && b.contains(':') && a.substringBefore(':') == b.substringBefore(':'))

    /** A brand by its name ("Aldi" is "Aldi (Aldi Nord group)"), the names it goes by, or a name or brand tag it is told by. */
    private fun brandIs(q: Query, name: String): Boolean = when (q) {
        is Query.Brand -> Brands.byId(q.id)?.let { b ->
            val want = Brands.key(name)
            val names = (listOf(b.name.substringBefore(" ("), b.brand) + b.matchNames).map { Brands.key(it) }
            names.any { it == want || it.startsWith(want) || want.startsWith(it) } || Lexicon.brand(name, COUNTRY)?.id == b.id
        } ?: false
        is Query.Name -> nameIs(q, name)
        is Query.Tags -> leafIs(q.filter, setOf("brand", "name"), name)
        else -> false
    }

    /** A name: searched for as words, or told by a name tag ("Centraal Station": `name~centraal`). */
    private fun nameIs(q: Query, name: String): Boolean = when (q) {
        is Query.Name -> Words.fold(q.text).let { a -> Words.fold(name).let { b -> a == b || a.contains(b) || b.contains(a) } }
        is Query.Tags -> leafIs(q.filter, setOf("name"), name)
        else -> false
    }

    /** True when a part of [f] on one of [keys] takes [value]. */
    private fun leafIs(f: TagFilter, keys: Set<String>, value: String): Boolean = when (f) {
        is TagFilter.And -> f.parts.any { leafIs(it, keys, value) }
        is TagFilter.Or -> f.parts.any { leafIs(it, keys, value) }
        is TagFilter.Equals, is TagFilter.Matches -> f.keys().any { it in keys } && f.keys().any { k -> f.matches(mapOf(k to value)) }
        else -> false
    }

    /** A query as the report shows it: its chips, its tree, what it asks. */
    private fun describe(p: ParsedQuery): String = buildString {
        append(p.chips.joinToString(" ") { "[${it.kind}:${it.label}]" })
        if (p.ask != Ask.LIST) append(" ask=${p.ask}")
        p.intent?.let { append(" intent=$it") }
        p.route?.let { append(" route=${it.from ?: "here"}->${it.to}") }
        if (p.unknown.isNotEmpty()) append(" unknown=${p.unknown}")
        append(" · ")
        append(p.steps.joinToString(" THEN ") { it.toString() }.take(400))
    }

    companion object {
        /** How many of the catalogue's queries read as meant: it only goes up. */
        const val READ_RIGHT = 673

        const val SKIPPED = "beyond the model"
        private const val ANY = "*"

        /** Marks a sample made for "any value" of its key ("shop=*"), which says nothing of other kinds. */
        private const val ANY_KEY = "@any"

        /** Tags mapped with others as a rule: a lake's natural=water, a sandpit's playground. */
        private val IMPLIED = mapOf(
            "water" to mapOf("natural" to "water"),
            "playground" to mapOf("leisure" to "playground"),
        )
        private val TAG = Regex("""^([^=<>!]+)(=|!=|<=|>=|<|>)?(.*)$""")
        private val LATE = LocalTime.of(23, 0)
        private val TONIGHT = LocalTime.of(20, 30)

        /** A value a key takes when any will do. */
        private val TYPICAL = mapOf(
            "shop" to "clothes", "building" to "yes", "amenity" to "cafe", "tourism" to "attraction", "leisure" to "park",
            "natural" to "wood", "historic" to "monument", "highway" to "footway", "sport" to "soccer", "craft" to "carpenter",
            "office" to "company", "healthcare" to "doctor", "railway" to "station", "public_transport" to "station",
        )
        private val DAYS = mapOf(
            "Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY, "Th" to DayOfWeek.THURSDAY,
            "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY,
        )
        private val TIMES = mapOf("early" to LocalTime.of(7, 0), "late" to LATE)

        /** Intents the catalogue names otherwise than Atlas does. */
        private val SAME_INTENT = mapOf(
            "watch_sunset" to setOf("sunset"), "indoors" to setOf("shelter"), "go_out" to setOf("drinks", "late_food"), "study" to setOf("work"),
        )

        /** Orders that read the catalogue's as well ("the longest opening time" in the afternoon: open the longest). */
        private val SAME_SORT = listOf("MOST_HOURS", "OPEN_LONGEST", "CLOSES_LATEST").let { same -> same.associateWith { (same - it).toSet() } }

        /** Tag wishes a way's preferences stand for. */
        private val ROUTE_PREFS: Map<String, (app.atlas.model.RoutePrefs) -> Boolean> = mapOf(
            "lit=yes" to { r -> r.lit > 0 }, "highway!=motorway" to { r -> r.avoidMotorways }, "toll!=yes" to { r -> r.avoidTolls },
        )

        /** The catalogue's searcher is in the Netherlands. */
        private const val COUNTRY = "nl"

        /** The keys a brand's kind is told by, for what a brand's query does to sample places. */
        private val KIND_KEYS = listOf("amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare")

        /** The words of a place of one's own that a route can end at. */
        private val PLACES_OF_ONES_OWN = mapOf(
            "home" to setOf("home", "huis", "thuis", "hause", "zuhause", "maison", "chez"),
            "work" to setOf("work", "werk", "kantoor", "arbeit", "buro", "travail", "bureau", "boulot"),
        )
    }
}
