package app.atlas.search

import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Natural-language queries in English, Dutch, German and French. The words are matched against the [Lexicon]
 * (longest phrase first, then plural and inflected forms, brand names and presets with a typo), then a small grammar
 * puts them together: properties attach to the kind of place they stand next to, "near / with / 200 m from" open a ↳
 * row with the next kind of place, "and" chains another, "then" (or a comma before a new kind) starts the next step.
 */
internal class NaturalParser(private val lang: String, private val now: ZonedDateTime?) {
    /** Words as the user wrote them, and where they stand in the text (end exclusive). */
    private class Said(val text: String, val start: Int, val end: Int) {
        companion object {
            val NONE = Said("", -1, -1)

            /** Several pieces as one: their words in order, from the first's start to the last's end. */
            fun of(parts: List<Said>): Said {
                val real = parts.filter { it.start >= 0 }.sortedBy { it.start }
                if (real.isEmpty()) return Said(parts.joinToString(" ") { it.text }, -1, -1)
                return Said(real.joinToString(" ") { it.text }, real.first().start, real.last().end)
            }
        }
    }

    private sealed class Item(val said: Said) {
        val raw: String get() = said.text

        class M(val meaning: Meaning, said: Said, val lang: String?, val fuzzy: Boolean = false) : Item(said)
        class Distance(val metres: Double, said: Said) : Item(said)
        class Time(val time: LocalTime, said: Said) : Item(said)
        class Number(val n: Int, said: Said) : Item(said)
        class Comma(said: Said) : Item(said)
        class Unknown(said: Said) : Item(said)
    }

    private class Attr(val meaning: Meaning.Attribute, val said: Said, val negated: Boolean)

    /** When a target should be open: an open word, a day, a clock time, in any combination. */
    private class OpenParts {
        var word: OpenTime? = null
        var day: DayOfWeek? = null
        var dayOffset: Int? = null
        var clock: LocalTime? = null
        val raw = ArrayList<Said>()
        val isEmpty get() = word == null && day == null && dayOffset == null && clock == null

        fun addTo(o: OpenParts) {
            word?.let { o.word = it }
            day?.let { o.day = it }
            dayOffset?.let { o.dayOffset = it }
            clock?.let { o.clock = it }
            o.raw += raw
        }

        fun clear() { word = null; day = null; dayOffset = null; clock = null; raw.clear() }
    }

    private class Target {
        var noun: Meaning.Noun? = null
        var nounSaid = Said.NONE
        val alternatives = ArrayList<Pair<Meaning.Noun, Said>>()
        var cuisine: Pair<Meaning.Noun, Said>? = null
        val attrs = ArrayList<Attr>()
        val open = OpenParts()
        val hasNoun get() = noun != null
    }

    private class Row(val target: Target, var distance: Double, var distanceSaid: Said?, val negated: Boolean, val viaWith: Boolean)

    private class Step {
        val subject = Target()
        val rows = ArrayList<Row>()
        var area: Query? = null
        var areaChip: Chip? = null
        var sort: SortBy? = null
        var sortSaid = Said.NONE
        var limit: Int? = null
        var limitSaid = Said.NONE
    }

    private enum class Pending { NONE, NEAR, WITH, NOT_NEAR, AND_NEAR, FROM, WITHIN, WITHOUT, IN }

    fun parse(text: String): ParsedQuery {
        val words = Words.split(text)
        val items = scan(words)
        return assemble(text, items)
    }

    // ------------------------------------------------------------------------------------------------- words

    private fun scan(words: List<Word>): List<Item> {
        val texts = words.map { it.text }
        val out = ArrayList<Item>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val one = said(words, i, 1)
            if (w.text == "," || w.text == ";") { out += Item.Comma(one); i++; continue }
            if (w.text == "&" || w.text == "+") { out += Item.M(Meaning.Word(Meaning.Role.AND), one, null); i++; continue }
            time(words, i)?.let { (t, n) -> out += Item.Time(t, said(words, i, n)); i += n; continue }
            distance(words, i)?.let { (d, n) -> out += Item.Distance(d, said(words, i, n)); i += n; continue }
            val entries = Lexicon.lookup(texts, i, lang)
            if (entries.isNotEmpty()) {
                val e = pick(entries)
                out += Item.M(e.meaning, said(words, i, e.words.size), e.lang)
                i += e.words.size
                continue
            }
            if (w.isNumber) {
                out += w.text.replace(',', '.').toDoubleOrNull()?.let { Item.Number(it.roundToInt(), one) } ?: Item.Unknown(one)
                i++
                continue
            }
            val stemmed = Lexicon.stemmed(w.text, lang)
            if (stemmed.isNotEmpty()) {
                out += Item.M(pick(stemmed).meaning, one, stemmed[0].lang)
                i++
                continue
            }
            brand(texts, i)?.let { (noun, n) -> out += Item.M(noun, said(words, i, n), null); i += n; continue }
            Lexicon.fuzzyGoods(w.text, lang)?.let { e -> out += Item.M(e.meaning, one, e.lang, fuzzy = true); i++; continue }
            fuzzyPreset(w.text)?.let { noun -> out += Item.M(noun, one, lang, fuzzy = true); i++; continue }
            out += Item.Unknown(one)
            i++
        }
        return out
    }

    /** Among entries of the same length, the lowest rank, then the app's language. */
    private fun pick(entries: List<Entry>): Entry {
        val longest = entries[0].words.size
        val same = entries.takeWhile { it.words.size == longest }
        val best = same.minOf { Lexicon.rank(it.meaning) }
        return same.filter { Lexicon.rank(it.meaning) == best }.let { tied -> tied.firstOrNull { it.lang == lang } ?: tied[0] }
    }

    private fun said(words: List<Word>, from: Int, n: Int): Said =
        Said(words.subList(from, from + n).joinToString(" ") { it.raw }, words[from].start, words[from + n - 1].end)

    private fun brand(texts: List<String>, at: Int): Pair<Meaning.Noun, Int>? {
        for (n in minOf(3, texts.size - at) downTo 1) {
            val phrase = texts.subList(at, at + n).joinToString(" ")
            if (phrase.length < 3) continue
            val b = Lexicon.brand(phrase) ?: continue
            val symbol = b.logoSlug?.let { "logo-$it" }?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(b.keyTags)
            return Meaning.Noun(Query.Brand(b.id), symbol, "brand:${b.id}", rank = 2) to n
        }
        return null
    }

    private fun fuzzyPreset(word: String): Meaning.Noun? {
        if (word.length < 5) return null
        val hit = Presets.search(word, lang, limit = 1).firstOrNull() ?: return null
        if (hit.score < 55) return null
        val filter = Lexicon.filterOf(hit.preset.tags) ?: return null
        val symbol = Presets.iconOf(hit.preset)?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(hit.preset.tags)
        return Meaning.Noun(Query.Tags(filter), symbol, "preset:${hit.preset.id}", rank = 6)
    }

    private fun time(words: List<Word>, i: Int): Pair<LocalTime, Int>? {
        val w = words[i].text
        val next = words.getOrNull(i + 1)?.text
        val prev = words.getOrNull(i - 1)?.text
        CLOCK.matchEntire(w)?.let { m ->
            return clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), next)?.let { it to (if (next in AMPM) 2 else 1) }
        }
        H.matchEntire(w)?.let { m ->
            // "24h" is round the clock, not midnight
            if (m.groupValues[1].toInt() == 24 && m.groupValues[2].isEmpty()) return null
            return clock(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), null)?.let { it to 1 }
        }
        AMPM_WORD.matchEntire(w)?.let { m -> return clock(m.groupValues[1].toInt(), 0, m.groupValues[2])?.let { it to 1 } }
        DOTTED.matchEntire(w)?.let { m ->
            if (next in HOUR_WORDS || prev in AT_WORDS) return clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), next)?.let { it to (if (next in HOUR_WORDS || next in AMPM) 2 else 1) }
        }
        // "22 uur", "10 pm"; but "24 uur" and "24 heures" are round the clock
        if (w.all { it.isDigit() } && w.length <= 2 && (next in HOUR_WORDS || next in AMPM) && !(w == "24" && next !in AMPM)) {
            return clock(w.toInt(), 0, next)?.let { it to 2 }
        }
        return null
    }

    private fun clock(h: Int, m: Int, suffix: String?): LocalTime? {
        var hour = h
        if (suffix == "pm" && hour in 1..11) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        if (hour == 24) hour = 0
        if (hour !in 0..23 || m !in 0..59) return null
        return LocalTime.of(hour, m)
    }

    private fun distance(words: List<Word>, i: Int): Pair<Double, Int>? {
        val w = words[i].text
        JOINED.matchEntire(w)?.let { m ->
            val n = number(m.groupValues[1]) ?: return null
            val unit = m.groupValues[2]
            if (unit in MINUTES) {
                val (speed, used) = mode(words, i + 1)
                return n * speed to 1 + used
            }
            return n * (LENGTH[unit] ?: return null) to 1
        }
        val n = (if (words[i].isNumber || NUMBER.matches(w)) number(w) else null) ?: return null
        val unit = words.getOrNull(i + 1)?.text ?: return null
        if (unit in MINUTES) {
            val (speed, used) = mode(words, i + 2)
            return n * speed to 2 + used
        }
        val f = LENGTH[unit] ?: return null
        return n * f to 2
    }

    private fun number(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    /** The travel mode after "5 min": metres per minute, and how many words it took. Walking unless told. */
    private fun mode(words: List<Word>, at: Int): Pair<Double, Int> {
        val texts = words.drop(at).take(4).map { it.text }
        for ((phrase, speed) in MODES) {
            if (texts.size >= phrase.size && texts.subList(0, phrase.size) == phrase) return speed to phrase.size
        }
        return WALK to 0
    }

    // ------------------------------------------------------------------------------------------------- grammar

    private fun assemble(text: String, items: List<Item>): ParsedQuery {
        val steps = ArrayList<Step>()
        var step = Step()
        var current = step.subject
        var pending = Pending.NONE
        var pendingDistance: Pair<Double, Said>? = null
        val pendingAttrs = ArrayList<Attr>()
        val withAttrs = ArrayList<Attr>()
        val pendingOpen = OpenParts()
        var aroundMe: Said? = null
        var negateNext = false
        var orNext = false
        var afterComma = false
        var lastRow: Row? = null
        var lastWasRowNoun = false
        val unknown = ArrayList<String>()
        var recognised = 0
        var fuzzy = 0
        val votes = HashMap<String, Int>()

        fun setAroundMe(said: Said) {
            val d = pendingDistance?.first ?: AROUND_ME
            step.area = Query.Within(null, d)
            step.areaChip = chip(ChipKind.AREA, "ui-locate", Said.of(listOfNotNull(pendingDistance?.second, said)), d.roundToInt().toString())
            pendingDistance = null
        }

        fun closeStep(flushOpen: Boolean) {
            aroundMe?.let { setAroundMe(it) }
            aroundMe = null
            pendingDistance?.let { (d, r) ->
                step.area = Query.Within(null, d)
                step.areaChip = chip(ChipKind.AREA, "ui-locate", r, d.roundToInt().toString())
            }
            pendingDistance = null
            if (withAttrs.isNotEmpty()) { current.attrs += withAttrs; withAttrs.clear() }
            if (pendingAttrs.isNotEmpty()) { current.attrs += pendingAttrs; pendingAttrs.clear() }
            if (flushOpen && !pendingOpen.isEmpty) { pendingOpen.addTo(current.open); pendingOpen.clear() }
            steps += step
        }

        fun newStep(flushOpen: Boolean) {
            closeStep(flushOpen)
            step = Step()
            current = step.subject
            pending = Pending.NONE
            lastRow = null
            lastWasRowNoun = false
            afterComma = false
            orNext = false
            negateNext = false
        }

        /** Gives [t] what was waiting for the next kind of place: properties said before it, and opening times. */
        fun settle(t: Target) {
            t.attrs += pendingAttrs
            pendingAttrs.clear()
            if (!pendingOpen.isEmpty) { pendingOpen.addTo(t.open); pendingOpen.clear() }
        }

        fun noun(n: Meaning.Noun, raw: Said) {
            val qualified = !pendingOpen.isEmpty
            val connects = pending == Pending.NEAR || pending == Pending.WITH || pending == Pending.NOT_NEAR ||
                pending == Pending.AND_NEAR || pending == Pending.FROM || pending == Pending.IN || pending == Pending.WITHOUT ||
                aroundMe != null || (pendingDistance != null && pending != Pending.WITHIN)
            // "… with cheap parking and a late ramen": a kind with its own time after "and" is a stop of its own
            val ownStop = qualified && pending == Pending.AND_NEAR
            if (connects && step.subject.hasNoun && !ownStop) {
                val t = Target().also { it.noun = n; it.nounSaid = raw }
                val distance = pendingDistance?.first
                    ?: (if (pending == Pending.AND_NEAR) lastRow?.distance else null)
                    ?: if (pending == Pending.IN) INSIDE else DEFAULT_NEAR
                val row = Row(t, distance, pendingDistance?.second, pending == Pending.NOT_NEAR || pending == Pending.WITHOUT, pending == Pending.WITH)
                if (pending == Pending.WITH) t.attrs += withAttrs else current.attrs += withAttrs
                withAttrs.clear()
                settle(t)
                step.rows += row
                lastRow = row
                current = t
                pending = Pending.NONE
                pendingDistance = null
                aroundMe = null
                negateNext = false
                lastWasRowNoun = true
                afterComma = false
                orNext = false
                return
            }
            lastWasRowNoun = false
            val cur = current
            when {
                !step.subject.hasNoun -> {
                    step.subject.noun = n
                    step.subject.nounSaid = raw
                    current = step.subject
                    settle(current)
                }
                afterComma || ownStop -> {
                    newStep(flushOpen = false)
                    step.subject.noun = n
                    step.subject.nounSaid = raw
                    current = step.subject
                    settle(current)
                }
                n.cuisine && !cur.noun!!.cuisine && cur.cuisine == null -> { cur.cuisine = n to raw; settle(cur) }
                cur.noun!!.cuisine && !n.cuisine && cur.cuisine == null -> {
                    cur.cuisine = cur.noun!! to cur.nounSaid
                    cur.noun = n
                    cur.nounSaid = raw
                    settle(cur)
                }
                else -> { cur.alternatives += n to raw; settle(cur) }
            }
            orNext = false
            afterComma = false
            if (pending != Pending.WITHIN) pending = Pending.NONE
        }

        fun attribute(a: Meaning.Attribute, raw: Said) {
            val attr = Attr(a, raw, negateNext)
            negateNext = false
            when {
                pending == Pending.WITH -> withAttrs += attr
                pending == Pending.NEAR || pending == Pending.NOT_NEAR || pending == Pending.AND_NEAR || pending == Pending.FROM -> pendingAttrs += attr
                !current.hasNoun -> pendingAttrs += attr
                else -> current.attrs += attr
            }
        }

        /** Whether the next word that matters names a kind of place. */
        fun nounFollows(i: Int): Boolean {
            for (j in i + 1 until items.size) {
                val it = items[j]
                if (it is Item.M && (it.meaning as? Meaning.Word)?.role == Meaning.Role.FILLER) continue
                return it is Item.M && it.meaning is Meaning.Noun
            }
            return false
        }

        /** Open words and times before a kind of place wait for it ("the 20:00 film", "a late ramen"); after one they are its. */
        fun openPart(i: Int, apply: (OpenParts) -> Unit, raw: Said) {
            val target = if (!current.hasNoun || nounFollows(i)) pendingOpen else current.open
            apply(target)
            target.raw += raw
        }

        var i = 0
        while (i < items.size) {
            val item = items[i]
            val isFiller = item is Item.M && (item.meaning as? Meaning.Word)?.role == Meaning.Role.FILLER
            // "nearby" followed by a kind of place is "near" it; otherwise it is the searcher's surroundings
            if (aroundMe != null && !isFiller && !(item is Item.M && item.meaning is Meaning.Noun)) {
                setAroundMe(aroundMe!!)
                aroundMe = null
            }
            when (item) {
                is Item.Comma -> {
                    afterComma = true
                    // a comma ends "with …": "a café with an outlet, charge the car nearby"
                    if (pending == Pending.WITH) {
                        current.attrs += withAttrs
                        withAttrs.clear()
                        pending = Pending.NONE
                    }
                }
                is Item.Unknown -> unknown += item.raw
                is Item.Number -> if (nounFollows(i)) { step.limit = item.n; step.limitSaid = item.said; recognised++ } else unknown += item.raw
                is Item.Distance -> {
                    recognised++
                    val row = lastRow
                    if (lastWasRowNoun && row != null && row.distanceSaid == null) {
                        row.distance = item.metres
                        row.distanceSaid = item.said
                    } else {
                        pendingDistance = item.metres to item.said
                    }
                    lastWasRowNoun = false
                }
                is Item.Time -> { recognised++; openPart(i, { it.clock = item.time }, item.said); lastWasRowNoun = false }
                is Item.M -> {
                    item.lang?.let { votes.merge(it, 1, Int::plus) }
                    if (item.fuzzy) fuzzy++
                    when (val m = item.meaning) {
                        is Meaning.Noun -> { recognised++; noun(m, item.said) }
                        is Meaning.Attribute -> { recognised++; attribute(m, item.said); lastWasRowNoun = false }
                        is Meaning.Open -> {
                            recognised++
                            // "open now" after a kind belongs to it; "late" or "tonight" before one waits for it
                            if (m.time == OpenTime.Now && current.hasNoun) { current.open.word = m.time; current.open.raw += item.said }
                            else openPart(i, { it.word = m.time }, item.said)
                            lastWasRowNoun = false
                        }
                        is Meaning.Day -> {
                            recognised++
                            openPart(i, { o -> if (m.day != null) o.day = m.day else o.dayOffset = m.offset }, item.said)
                            lastWasRowNoun = false
                        }
                        is Meaning.Sort -> { recognised++; step.sort = m.by; step.sortSaid = item.said }
                        is Meaning.Word -> {
                            when (m.role) {
                                Meaning.Role.NEAR -> { recognised++; pending = Pending.NEAR }
                                Meaning.Role.WITH -> { recognised++; pending = Pending.WITH }
                                Meaning.Role.NOT_NEAR -> { recognised++; pending = Pending.NOT_NEAR }
                                Meaning.Role.WITHIN -> { recognised++; pending = Pending.WITHIN }
                                Meaning.Role.WITHOUT -> { recognised++; negateNext = true; if (nounFollows(i)) pending = Pending.WITHOUT }
                                Meaning.Role.FROM -> if (pendingDistance != null) { recognised++; pending = Pending.FROM }
                                Meaning.Role.OF -> when {
                                    pendingDistance != null -> { recognised++; pending = Pending.FROM }
                                    item.lang == "nl" && lang == "nl" -> { recognised++; orNext = true }
                                }
                                Meaning.Role.AROUND_ME -> {
                                    recognised++
                                    // "a café with a charger nearby": the "nearby" is the charger's
                                    if (!(lastWasRowNoun && lastRow?.viaWith == true)) aroundMe = item.said
                                }
                                Meaning.Role.IN -> {
                                    val (name, next) = areaName(items, i + 1)
                                    if (name != null) {
                                        recognised += 1
                                        step.area = Query.InArea(null, name.text)
                                        step.areaChip = chip(ChipKind.AREA, "ui-map-pin", name, name.text, lowercase = false)
                                        i = next
                                        continue
                                    } else if (nounFollows(i)) {
                                        recognised++
                                        pending = Pending.IN
                                    }
                                }
                                Meaning.Role.THEN -> { recognised++; newStep(flushOpen = true) }
                                Meaning.Role.AND -> {
                                    val row = lastRow
                                    if (row != null && current === row.target && pending != Pending.WITH) pending = Pending.AND_NEAR
                                }
                                Meaning.Role.OR -> { recognised++; orNext = true }
                                Meaning.Role.FILLER -> {}
                            }
                            if (m.role != Meaning.Role.FILLER && m.role != Meaning.Role.AROUND_ME && m.role != Meaning.Role.AND) lastWasRowNoun = false
                        }
                    }
                }
            }
            i++
        }
        closeStep(flushOpen = true)

        // ---------------------------------------------------------------------------------------- to queries
        val queries = ArrayList<Query>()
        val chips = ArrayList<Chip>()
        for ((si, s) in steps.withIndex()) {
            val subject = targetQuery(s.subject, chips, row = 0, step = si)
            var q: Query = subject ?: Query.All
            for ((ri, r) in s.rows.withIndex()) {
                val other = targetQuery(r.target, chips, row = ri + 1, step = si, distance = r) ?: continue
                q = if (r.negated) Query.NotNear(q, other, r.distance) else Query.Near(q, other, r.distance)
            }
            s.area?.let { q = Query.and(q, it) }
            s.areaChip?.let { chips += it.copy(step = si) }
            s.sort?.let { q = Query.Sort(q, it); chips += chip(ChipKind.SORT, SORT_SYMBOLS.getValue(it), s.sortSaid, it.name.lowercase(Locale.ROOT), step = si) }
            s.limit?.let { q = Query.Limit(q, it); chips += chip(ChipKind.LIMIT, "ui-filter", s.limitSaid, it.toString(), step = si) }
            if (q != Query.All) queries += q
        }

        val significant = recognised + unknown.size
        var confidence = if (significant == 0) 0.0 else recognised.toDouble() / significant
        val anyKind = steps.any { it.subject.hasNoun || it.rows.isNotEmpty() || it.subject.attrs.isNotEmpty() }
        if (!anyKind) confidence *= 0.3
        repeat(fuzzy) { confidence *= 0.85 }
        // Two kinds side by side ("café molen", "pizza restaurant") may well be a name or a preset's own name: only
        // connectors, properties, times, areas and orders make it a query for sure.
        val structured = steps.size > 1 || steps.any { s ->
            s.rows.isNotEmpty() || s.area != null || s.sort != null || s.limit != null || s.subject.attrs.isNotEmpty() || !s.subject.open.isEmpty
        }
        val language = votes.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { if (it.key == lang) 0 else 1 }).firstOrNull()?.key
        return ParsedQuery(text, queries, chips, unknown, confidence.coerceIn(0.0, 1.0), QuerySyntax.NATURAL, language, structured && queries.isNotEmpty())
    }

    /** The query for one target and its chips; null when it names nothing. */
    private fun targetQuery(t: Target, chips: MutableList<Chip>, row: Int, step: Int, distance: Row? = null): Query? {
        distance?.let { r ->
            val said = r.distanceSaid ?: Said("${r.distance.roundToInt()} m", -1, -1)
            chips += chip(ChipKind.DISTANCE, "ui-ruler", said, r.distance.roundToInt().toString(), row, step, r.negated)
        }
        val parts = ArrayList<Query>()
        val noun = t.noun
        if (noun != null) {
            val kinds = listOf(noun to t.nounSaid) + t.alternatives
            val q = Query.or(kinds.map { it.first.query })
            if (noun.cuisine && t.cuisine == null && t.alternatives.isEmpty()) {
                parts += Query.Tags(EAT)
            }
            parts += q
            for ((n, said) in kinds) chips += chip(ChipKind.KIND, n.symbol, said, valueOf(n), row, step)
        }
        t.cuisine?.let { (n, said) ->
            parts += n.query
            chips += chip(ChipKind.KIND, n.symbol, said, valueOf(n), row, step)
        }
        for (a in t.attrs) {
            val q: Query = Query.Tags(a.meaning.filter).let { if (a.negated) Query.Not(it) else it }
            parts += if (a.meaning.soft) Query.Prefer(q) else q
            chips += chip(if (a.meaning.soft) ChipKind.PREFERENCE else ChipKind.PROPERTY, a.meaning.symbol, a.said, a.meaning.filter.toText(), row, step, a.negated)
        }
        openTime(t.open)?.let { o ->
            parts += Query.OpenAt(o)
            chips += chip(ChipKind.OPEN, "ui-clock", Said.of(t.open.raw), openValue(o), row, step)
        }
        if (parts.isEmpty()) return null
        return Query.and(parts)
    }

    /** The opening time the parts spell: a day and a time, a day, a time, or an open word. */
    private fun openTime(o: OpenParts): OpenTime? {
        if (o.isEmpty) return null
        // "today" and "tomorrow" need a clock: the one given, else the phone's
        val day = o.day ?: o.dayOffset?.let { off -> (now ?: ZonedDateTime.now()).dayOfWeek.plus(off.toLong()) }
        return when {
            day != null -> OpenTime.OnDay(day, o.clock)
            o.dayOffset == 0 && o.clock != null -> OpenTime.Today(o.clock!!)
            o.clock != null -> OpenTime.Today(o.clock!!)
            o.word != null -> o.word
            else -> OpenTime.Now
        }
    }

    /** The unknown words after "in" that name an area, articles allowed between ("in de Jordaan"), and where they end. */
    private fun areaName(items: List<Item>, from: Int): Pair<Said?, Int> {
        val parts = ArrayList<Said>()
        var j = from
        while (j < items.size) {
            val it = items[j]
            val article = it is Item.M && (it.meaning as? Meaning.Word)?.role.let { r -> r == Meaning.Role.FILLER || r == Meaning.Role.OF }
            when {
                it is Item.Unknown -> parts += it.said
                article && items.getOrNull(j + 1) is Item.Unknown -> if (parts.isNotEmpty()) parts += it.said
                else -> break
            }
            j++
        }
        return (if (parts.isEmpty()) null else Said.of(parts)) to j
    }

    /** A chip for words the user wrote, lower-cased as a label unless told otherwise, with where they stand. */
    private fun chip(
        kind: ChipKind, symbol: String, said: Said, value: String, row: Int = 0, step: Int = 0, negated: Boolean = false,
        lowercase: Boolean = true,
    ) = Chip(kind, symbol, if (lowercase) said.text.lowercase() else said.text, value, row, step, negated, said.start, said.end)

    /** What a kind chip's value says: the filter, or the category, brand or goods it stands for. */
    private fun valueOf(n: Meaning.Noun): String = when (val q = n.query) {
        is Query.Tags -> q.filter.toText()
        is Query.Category -> "category:${q.id}"
        is Query.Brand -> "brand:${q.id}"
        else -> n.id
    }

    private fun openValue(o: OpenTime): String = when (o) {
        OpenTime.Now -> "now"
        OpenTime.Tonight -> "tonight"
        OpenTime.Late -> "late"
        is OpenTime.Today -> o.time.toString()
        is OpenTime.At -> o.time.toString()
        is OpenTime.OnDay -> o.day.name.lowercase(Locale.ROOT) + (o.time?.let { " $it" } ?: "")
    }

    private companion object {
        const val DEFAULT_NEAR = 200.0
        const val AROUND_ME = 1_000.0
        const val INSIDE = 50.0
        const val WALK = 80.0

        val EAT: TagFilter = TagFilter.parse("amenity~^(restaurant|fast_food|cafe|food_court|ice_cream)$")

        val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")
        val H = Regex("""^(\d{1,2})[hu](\d{2})?$""")
        val AMPM_WORD = Regex("""^(\d{1,2})(am|pm)$""")
        val DOTTED = Regex("""^(\d{1,2})\.(\d{2})$""")
        val JOINED = Regex("""^(\d+(?:[.,]\d+)?)(m|km|mi|ft|min|mins)$""")
        val NUMBER = Regex("""^\d+(?:[.,]\d+)?$""")
        val AMPM = setOf("am", "pm")
        val HOUR_WORDS = setOf("uur", "uhr", "h", "u", "heures", "heure", "oclock", "am", "pm")
        val AT_WORDS = setOf("at", "om", "um", "a", "vers", "rond", "gegen")
        val MINUTES = setOf("min", "mins", "minute", "minutes", "minuten", "minuut", "minuto")
        val LENGTH = mapOf(
            "m" to 1.0, "meter" to 1.0, "meters" to 1.0, "metre" to 1.0, "metres" to 1.0, "metern" to 1.0, "metres." to 1.0,
            "km" to 1000.0, "kilometer" to 1000.0, "kilometers" to 1000.0, "kilometre" to 1000.0, "kilometres" to 1000.0, "kilometern" to 1000.0,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344, "ft" to 0.3048, "feet" to 0.3048, "foot" to 0.3048,
            "yd" to 0.9144, "yards" to 0.9144,
        )
        val MODES: List<Pair<List<String>, Double>> = listOf(
            "walk" to WALK, "walking" to WALK, "on foot" to WALK, "lopen" to WALK, "te voet" to WALK, "wandelen" to WALK,
            "zu fuss" to WALK, "fuss" to WALK, "a pied" to WALK, "gehen" to WALK, "fussweg" to WALK,
            "by bike" to 250.0, "by bicycle" to 250.0, "bike" to 250.0, "cycling" to 250.0, "fietsen" to 250.0, "met de fiets" to 250.0,
            "fiets" to 250.0, "mit dem rad" to 250.0, "mit dem fahrrad" to 250.0, "rad" to 250.0, "fahrrad" to 250.0, "a velo" to 250.0, "velo" to 250.0,
            "by car" to 600.0, "drive" to 600.0, "driving" to 600.0, "rijden" to 600.0, "met de auto" to 600.0, "auto" to 600.0,
            "mit dem auto" to 600.0, "fahrt" to 600.0, "en voiture" to 600.0, "voiture" to 600.0,
        ).map { (p, s) -> p.split(' ') to s }.sortedByDescending { it.first.size }
        val SORT_SYMBOLS = mapOf(SortBy.DISTANCE to "ui-navigation", SortBy.RATING to "ui-star", SortBy.OPEN_LONGEST to "ui-clock", SortBy.RELEVANCE to "ui-sparkles")
    }
}
