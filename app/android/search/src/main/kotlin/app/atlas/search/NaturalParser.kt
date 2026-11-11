package app.atlas.search

import app.atlas.places.ChangeKind
import app.atlas.places.CompareOp
import app.atlas.places.Kinds
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Natural-language queries in English, Dutch, German and French. The words are matched against the [Lexicon]
 * (longest phrase first, then plural and inflected forms, brand names and presets with a typo), then a small grammar
 * puts them together: properties attach to the kind of place they stand next to, "near / with / 200 m from" open a ↳
 * row with the next kind of place, "and" chains another, "then" (or a comma before a new kind) starts the next step.
 * "In" a kind of place puts the subject inside one; "with" one gives it one: as a part of its own when it is a
 * container ("woods with a lake"), as something it may have without saying when it is an amenity ("a Starbucks with
 * toilets"), else near it. Comparisons take the value after them ("built before 1900", "taller than 100 m", "under
 * €100", "150 kW"), superlatives order ("the oldest"), and "how many" or "opening times" say what is asked.
 */
internal class NaturalParser(
    private val lang: String,
    private val now: ZonedDateTime?,
    private val country: String? = null,
    /** Whether words after a kind may be where it is ("boulangerie amsterdam"); the engine reads again without when no such place is known. */
    private val guessAreas: Boolean = false,
) {
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

        /** A length, or the way a walk or a ride of some minutes goes ([timed]). */
        class Distance(val metres: Double, said: Said, val timed: Boolean = false) : Item(said)
        /** A clock time; [ambiguous] when its hour could be either half of the day ("half acht", "at 8"). */
        class Time(val time: LocalTime, said: Said, val ambiguous: Boolean = false) : Item(said)
        class Number(val n: Int, said: Said) : Item(said)

        /** A number with a unit that is not a length: kW, euros, stars, years, floors, seats, spaces. */
        class Amount(val value: Double, val unit: AmountUnit, said: Said) : Item(said)

        /** A span of years: a decade ("1890s"), a century ("19th century"), an age ("medieval"). */
        class Years(val from: Int, val to: Int, said: Said) : Item(said)

        /** A floor: "2nd floor", "begane grond", "basement" (below 0). */
        class Level(val level: Int, said: Said) : Item(said)
        class Comma(said: Said) : Item(said)
        class Unknown(said: Said) : Item(said)
    }

    private enum class AmountUnit { KW, MONEY, STARS, YEARS, AGE, RECENT, FLOORS, SEATS, SPACES, ROOMS }

    private class Attr(val meaning: Meaning.Attribute, val said: Said, val negated: Boolean, val soft: Boolean = false)

    /** What a comparison, a colour, a floor or a closing time asks of a target, resolved by its kind of place. */
    private sealed class Cond(val said: Said, val soft: Boolean) {
        /** Built in [from]..[to]; with [op], before or after [from] (`start_date` [op] [from]). */
        class Built(val op: CompareOp?, val from: Int, val to: Int, said: Said, soft: Boolean) : Cond(said, soft)

        /** [dim] [op] [value]; equal to it when [op] is null. */
        class Measure(val dim: MeasureDim, val op: CompareOp?, val value: Double, said: Said, soft: Boolean) : Cond(said, soft)
        class Level(val level: Int, said: Said, soft: Boolean) : Cond(said, soft)
        class Colour(val family: String, said: Said, soft: Boolean) : Cond(said, soft)

        /** Opens or closes ([kind]) within [window] minutes of [at]. */
        class Change(val kind: ChangeKind, val at: LocalTime, val window: Int, said: Said, soft: Boolean) : Cond(said, soft)

        /** Its [key] [op] that of the place called [name] ("taller than the Westertoren"). */
        class Than(val key: String, val op: CompareOp, val name: String, said: Said, soft: Boolean) : Cond(said, soft)
    }

    /** What a measure is of: a height (an elevation for peaks), kW, a price, stars, a rating, floors, seats, spaces, rooms. */
    private enum class MeasureDim { TALL, ELEVATION, POWER, MONEY, STARS, RATING, FLOORS, SEATS, SPACES, ROOMS }

    /**
     * When a target should be open: an open word, a day, a clock time, in any combination. [weekend] is Saturday or
     * Sunday; [ambiguous] a clock that may be the evening's ("om half acht" for a restaurant); [closed] turns it round.
     */
    private class OpenParts {
        var word: OpenTime? = null
        var day: DayOfWeek? = null
        var dayOffset: Int? = null
        var clock: LocalTime? = null
        var weekend = false
        var ambiguous = false
        var closed = false
        val raw = ArrayList<Said>()
        val isEmpty get() = word == null && day == null && dayOffset == null && clock == null && !weekend

        fun addTo(o: OpenParts) {
            word?.let { if (it != OpenTime.Now || o.word == null) o.word = it }
            day?.let { o.day = it }
            dayOffset?.let { o.dayOffset = it }
            clock?.let { o.clock = it; o.ambiguous = ambiguous }
            if (weekend) o.weekend = true
            if (closed) o.closed = true
            o.raw += raw
        }

        fun clear() { word = null; day = null; dayOffset = null; clock = null; weekend = false; ambiguous = false; closed = false; raw.clear() }
    }

    private class Target {
        var noun: Meaning.Noun? = null
        var nounSaid = Said.NONE
        val alternatives = ArrayList<Pair<Meaning.Noun, Said>>()
        var cuisine: Pair<Meaning.Noun, Said>? = null
        val attrs = ArrayList<Attr>()
        val conds = ArrayList<Cond>()
        val open = OpenParts()

        /** Something to do there ("a lake to swim"). */
        var aim: Pair<Intent, Said>? = null

        /** Brands it is not ("a supermarket but not Albert Heijn"). */
        val excluded = ArrayList<Pair<Meaning.Noun, Said>>()

        /** What a recycling point should take ("recycle batteries": `recycling:batteries`). */
        var recycles: Pair<String, Said>? = null
        val hasNoun get() = noun != null
    }

    /** How a row's place stands to its parent: near it, away from it, around it, in it, or something it may have. */
    private enum class RowKind { NEAR, NOT_NEAR, INSIDE, CONTAINS, WANT }

    /**
     * A ↳ row: [target] as [kind] says to [parent], the step's subject or a row's place ("in the woods with a lake":
     * the lake is the woods'). A want row's [have] is the tags its parent would say it with; [connector] the words
     * of "in" or "with".
     */
    private class Row(
        val target: Target,
        var distance: Double,
        var distanceSaid: Said?,
        val kind: RowKind,
        val viaWith: Boolean,
        val parent: Target,
        val soft: Boolean = false,
        val have: TagFilter? = null,
        val connector: Said? = null,
    ) {
        val negated get() = kind == RowKind.NOT_NEAR
    }

    private class Step {
        var subject = Target()
        val rows = ArrayList<Row>()
        var area: Query? = null
        var areaChip: Chip? = null
        /** An area it keeps out of ("not in the centre", "outside Amsterdam"), beside the one it is in. */
        var away: Query? = null
        var awayChip: Chip? = null
        var sort: SortBy? = null
        var sortSaid = Said.NONE
        var limit: Int? = null
        var limitSaid = Said.NONE
    }

    private enum class Pending { NONE, NEAR, WITH, NOT_NEAR, AND_NEAR, FROM, WITHIN, WITHOUT, IN }

    fun parse(text: String): ParsedQuery {
        val words = Words.split(text)
        val items = outside(scan(words))
        return assemble(text, items, words.map { it.text })
    }

    /** "Outside Amsterdam", "buiten het centrum": an outdoor word before a town or a name keeps away from it. */
    private fun outside(items: List<Item>): List<Item> = items.mapIndexed { i, it ->
        val next = items.subList(i + 1, items.size).firstOrNull { n -> n.role() != Meaning.Role.FILLER }
        val area = next is Item.Unknown || next is Item.M && (next.meaning is Meaning.Area || next.meaning == Meaning.Town)
        if (it is Item.M && it.meaning is Meaning.Attribute && Words.fold(it.raw) in OUTSIDE_WORDS && area) Item.M(Meaning.Word(Meaning.Role.OUTSIDE), it.said, it.lang) else it
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
            time(words, i)?.let { c -> out += Item.Time(c.time, said(words, i, c.words), c.ambiguous); i += c.words; continue }
            years(words, i)?.let { (span, n) -> out += Item.Years(span.first, span.second, said(words, i, n)); i += n; continue }
            level(words, i)?.let { (l, n) -> out += Item.Level(l, said(words, i, n)); i += n; continue }
            amount(words, i)?.let { (a, n) -> out += Item.Amount(a.first, a.second, said(words, i, n)); i += n; continue }
            distance(words, i)?.let { (d, n) -> out += Item.Distance(d.first, said(words, i, n), d.second); i += n; continue }
            val entries = Lexicon.lookup(texts, i, lang)
            // a colour on its own; "red brick" and "white wine" are phrases of their own, and a colour the table knows as a
            // kind is that kind where no kind follows it ("grootste groen": the greens; "groen dak" a green roof)
            val colour = Colours.named(w.text)
            if (colour != null && entries.none { it.words.size > 1 } && !kindHere(entries, texts, i, lang)) { out += Item.M(Meaning.Colour(colour), one, null); i++; continue }
            // a brand over words that know only part of its name ("Burger King") or over a preset's search term
            val b = if (entries.isNotEmpty()) brand(texts, i)?.takeIf { (_, n) -> brandBeats(n, entries, texts, i) } else null
            if (b != null) { out += Item.M(b.first, said(words, i, b.second), null); i += b.second; continue }
            if (entries.isNotEmpty()) {
                val e = pick(entries)
                // "apoteek": a preset's search term that misspells the table's kind ("apotheek") is that kind
                val typo = if (e.words.size == 1 && (e.meaning as? Meaning.Noun)?.rank == 5) Lexicon.fuzzyNoun(w.text) else null
                if (typo != null) { out += Item.M(typo.meaning, one, typo.lang, fuzzy = true); i++; continue }
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
                out += pick(stemmed).let { e -> Item.M(e.meaning, one, e.lang) }
                i++
                continue
            }
            brand(texts, i)?.let { (noun, n) -> out += Item.M(noun, said(words, i, n), null); i += n; continue }
            Lexicon.fuzzyNoun(w.text)?.let { e -> out += Item.M(e.meaning, one, e.lang, fuzzy = true); i++; continue }
            Lexicon.fuzzyGoods(w.text, lang)?.let { e -> out += Item.M(e.meaning, one, e.lang, fuzzy = true); i++; continue }
            fuzzyPreset(w.text)?.let { noun -> out += Item.M(noun, one, lang, fuzzy = true); i++; continue }
            out += Item.Unknown(one)
            i++
        }
        return out
    }

    /**
     * Among entries of the same length, the app's language, then the lowest rank. Small words mean what they mean in
     * the app's language, then in English: "a" is an article before it is French for "in", "en" Dutch for "and". So
     * do the hand-picked words (ranks to [Lexicon.CURATED]): French "pommes" are apples, German ones fries, a Dutch
     * "roman" is a novel; a preset's names and search terms stay behind them, whatever their language.
     */
    private fun pick(entries: List<Entry>): Entry {
        val longest = entries[0].words.size
        val same = entries.takeWhile { it.words.size == longest }
        if (same.all { it.meaning is Meaning.Word }) {
            (same.firstOrNull { it.lang == lang } ?: same.firstOrNull { it.lang == "en" })?.let { return it }
        }
        same.filter { it.lang == lang && Lexicon.rank(it.meaning) <= Lexicon.CURATED }.minByOrNull { Lexicon.rank(it.meaning) }?.let { return it }
        val best = same.minOf { Lexicon.rank(it.meaning) }
        return same.filter { Lexicon.rank(it.meaning) == best }.let { tied -> tied.firstOrNull { it.lang == lang } ?: tied[0] }
    }

    private fun said(words: List<Word>, from: Int, n: Int): Said =
        Said(words.subList(from, from + n).joinToString(" ") { it.raw }, words[from].start, words[from + n - 1].end)

    /** Whether the word at [at], which [entries] start at, is a kind the table knows and no kind follows it ("grootste groen"). */
    private fun kindHere(entries: List<Entry>, texts: List<String>, at: Int, lang: String): Boolean =
        entries.any { it.words.size == 1 && (it.meaning as? Meaning.Noun)?.rank == 0 } && !kindAt(texts, at + 1, lang)

    /** Whether a kind of place is named at [at] ("dak", "daken"): a colour before it is that kind's colour. */
    private fun kindAt(texts: List<String>, at: Int, lang: String): Boolean = at < texts.size &&
        (Lexicon.lookup(texts, at, lang) + Lexicon.stemmed(texts[at], lang)).any { it.meaning is Meaning.Noun }

    /**
     * A brand named at [at] ("Albert Heijn", "HEMA", "ah", "McDonald's") and how many words it took: never with a
     * small word at either end, nor an ordinary word alone ("more", "closed"); and one that does not trade in the
     * searcher's country only as a whole name ("Albert" is not the start of "Albert Cuypstraat").
     */
    private fun brand(texts: List<String>, at: Int): Pair<Meaning.Noun, Int>? {
        if (small(texts[at])) return null
        for (n in minOf(3, texts.size - at) downTo 1) {
            val words = texts.subList(at, at + n)
            val phrase = words.joinToString(" ")
            if (phrase.length < 3 && !Lexicon.isAlias(phrase)) continue
            if (n == 1 && Lexicon.ordinary(phrase)) continue
            if (n > 1 && small(words.last()) && !Lexicon.isAlias(phrase)) continue
            val b = Lexicon.brand(phrase, country) ?: continue
            val next = texts.getOrNull(at + n)
            if (!b.availableIn(country) && next != null && next.any { it.isLetter() } && Lexicon.lookup(texts, at + n, lang).isEmpty()) continue
            val symbol = b.logoSlug?.let { "logo-$it" }?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(b.keyTags)
            return Meaning.Noun(Query.Brand(b.id), symbol, "brand:${b.id}", rank = 2) to n
        }
        return null
    }

    /** Whether a brand of [n] words reads better than the lexicon's [entries]: longer than what it knows, or as long as a preset's search term. */
    private fun brandBeats(n: Int, entries: List<Entry>, texts: List<String>, at: Int): Boolean {
        val e = pick(entries)
        if (n > e.words.size) return (at until at + n).any { j -> Lexicon.lookup(texts, j, lang).none { it.words.size == 1 && Lexicon.rank(it.meaning) <= 3 } }
        return n == e.words.size && e.meaning is Meaning.Noun && Lexicon.rank(e.meaning) >= 5
    }

    /** True for a connector or a small word of some language ("the", "in", "met", "de"). */
    private fun small(word: String): Boolean = Lexicon.lookup(listOf(word), 0, lang).any { it.words.size == 1 && it.meaning is Meaning.Word }

    private fun fuzzyPreset(word: String): Meaning.Noun? {
        if (word.length < 6) return null
        val hit = Presets.search(word, lang, limit = 1).firstOrNull() ?: return null
        if (hit.score < 55) return null
        val filter = Lexicon.filterOf(hit.preset.tags) ?: return null
        val symbol = Kinds.symbolOf(hit.preset) ?: Symbols.symbolFor(hit.preset.tags)
        return Meaning.Noun(Query.Tags(filter), symbol, "preset:${hit.preset.id}", rank = 6)
    }

    /** A clock time said, how many words it took, and whether its hour may be either half of the day ("om 8", "half acht"). */
    private class Clock(val time: LocalTime, val words: Int, val ambiguous: Boolean = false)

    private fun time(words: List<Word>, i: Int): Clock? {
        val w = words[i].text
        val next = words.getOrNull(i + 1)?.text
        val prev = words.getOrNull(i - 1)?.text
        if (w in MIDNIGHT) return Clock(LocalTime.MIDNIGHT, 1)
        if (w in NOON) return Clock(LocalTime.NOON, 1)
        CLOCK.matchEntire(w)?.let { m ->
            return clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), next)?.let { Clock(it, if (next in AMPM) 2 else 1) }
        }
        H.matchEntire(w)?.let { m ->
            // "24h" is round the clock, not midnight
            if (m.groupValues[1].toInt() == 24 && m.groupValues[2].isEmpty()) return null
            return clock(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), null)?.let { Clock(it, 1) }
        }
        AMPM_WORD.matchEntire(w)?.let { m -> return clock(m.groupValues[1].toInt(), 0, m.groupValues[2])?.let { Clock(it, 1) } }
        DOTTED.matchEntire(w)?.let { m ->
            if (next in HOUR_WORDS || prev in AT_WORDS) return clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), next)?.let { Clock(it, if (next in HOUR_WORDS || next in AMPM) 2 else 1) }
        }
        spoken(words, i)?.let { return it }
        val digits = if (w.all { it.isDigit() } && w.length <= 2) w.toInt() else null
        // "22 uur", "10 pm", "8 o'clock", "acht uur", "huit heures et demie"; but "24 uur" and "24 heures" are round the clock
        val h = digits ?: HOUR_NUMBERS[w]?.takeIf { it > 1 }
        val oclock = next == "o" && words.getOrNull(i + 2)?.text == "clock"
        if (h != null && (next in HOUR_WORDS || next in AMPM || oclock) && !(h == 24 && next !in AMPM)) {
            val t = clock(h, 0, next) ?: return null
            val n = if (oclock) 3 else 2
            val after = words.drop(i + n).take(3).map { it.text }
            val (minutes, used) = when {
                after.take(2) == listOf("et", "demie") -> 30 to 2
                after.take(2) == listOf("et", "quart") -> 15 to 2
                after == listOf("moins", "le", "quart") -> -15 to 3
                else -> 0 to 0
            }
            return Clock(t.plusMinutes(minutes.toLong()), n + used, ambiguous = next !in AMPM && h in 1..11)
        }
        // "om 8", "at 8", "um 8", "vers 8": an hour after "at", when no unit follows
        if (digits != null && digits <= 24 && (prev in BARE_AT || prev == "a" && lang == "fr") && !unitAfter(next) && !compared(words, i)) {
            return clock(digits, 0, null)?.let { Clock(it, 1, ambiguous = digits in 1..11) }
        }
        return null
    }

    /** "une note supérieure à 4", "rating at least 4": a number compared or rated, not an hour after "à" or "at". */
    private fun compared(words: List<Word>, i: Int): Boolean {
        val texts = words.map { it.text }
        return (maxOf(0, i - 3) until i).any { j ->
            Lexicon.lookup(texts, j, lang).any { e ->
                (e.meaning as? Meaning.Measure)?.dim == Meaning.Dim.RATING || e.meaning is Meaning.Compare && e.words.size >= 2 && j + e.words.size == i
            }
        }
    }

    /**
     * A clock time in words: "half acht" and "halb acht" (half an hour before eight), "half eight" and "half past
     * eight" (after it), "kwart over acht", "viertel vor acht", "quarter to eight".
     */
    private fun spoken(words: List<Word>, i: Int): Clock? {
        val t = words.drop(i).take(3).map { it.text }
        fun hourAt(k: Int): Int? = t.getOrNull(k)?.let { w -> if (w.all { it.isDigit() } && w.length <= 2) w.toInt().takeIf { it in 1..12 } else HOUR_NUMBERS[w] }
        fun at(h: Int, m: Int, n: Int) = Clock(LocalTime.of(if (h == 0) 12 else h, 0).plusMinutes(m.toLong()), n, ambiguous = true)
        when (t[0]) {
            "half", "halb" -> {
                if (t.getOrNull(1) == "past") return hourAt(2)?.let { at(it, 30, 3) }
                val h = hourAt(1) ?: return null
                // English "half eight" is after the hour; Dutch "half acht" and German "halb acht" before it
                val english = t[0] == "half" && (t[1] in EN_HOURS || t[1].first().isDigit() && lang == "en")
                return if (english) at(h, 30, 2) else at(h - 1, 30, 2)
            }
            "kwart", "viertel", "quarter" -> {
                val h = hourAt(2) ?: return null
                return when (t.getOrNull(1)) {
                    "over", "nach", "past", "after" -> at(h, 15, 3)
                    "voor", "vor", "to", "before" -> at(h - 1, 45, 3)
                    else -> null
                }
            }
        }
        return null
    }

    /** Whether [next] is a unit, so the number before it is no hour ("at 5 km", "om 2 sterren"). */
    private fun unitAfter(next: String?): Boolean = next != null &&
        (next in LENGTH || next in MINUTES || next in UNITS || next in FLOOR || next in CURRENCY || next in OLD || next in CENTURY || next in HOUR_WORDS)

    private fun clock(h: Int, m: Int, suffix: String?): LocalTime? {
        var hour = h
        if (suffix == "pm" && hour in 1..11) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        if (hour == 24) hour = 0
        if (hour !in 0..23 || m !in 0..59) return null
        return LocalTime.of(hour, m)
    }

    /** A span of years: "1890s", "1800s", "19th century", "19e eeuw", "19. Jahrhundert", "XIXe siècle", "medieval". */
    private fun years(words: List<Word>, i: Int): Pair<Pair<Int, Int>, Int>? {
        val w = words[i].text
        DECADE.matchEntire(w)?.let { m ->
            val d = m.groupValues[1].toInt()
            return (if (d % 100 == 0) d to d + 99 else d to d + 9) to 1
        }
        if (w in MEDIEVAL) return (MEDIEVAL_FROM to MEDIEVAL_TO) to 1
        // "world war 2", "de Gouden Eeuw", "Zwischenkriegszeit"
        for ((phrase, span) in ERAS) {
            if (words.size - i >= phrase.size && phrase.indices.all { words[i + it].text == phrase[it] }) return span to phrase.size
        }
        val next = words.getOrNull(i + 1)?.text ?: return null
        if (next !in CENTURY) return null
        val c = ORDINAL.matchEntire(w)?.groupValues?.get(1)?.toIntOrNull() ?: roman(w) ?: return null
        if (c !in 1..21) return null
        // the 17th century is the 1600s
        return ((c - 1) * 100 to (c - 1) * 100 + 99) to 2
    }

    /** A Roman century ("xix", "xixe", "xixeme"), or null. */
    private fun roman(w: String): Int? {
        val r = w.removeSuffix("eme").removeSuffix("ieme").removeSuffix("e").removeSuffix("er")
        if (r.isEmpty() || !r.all { it == 'i' || it == 'v' || it == 'x' }) return null
        var total = 0
        var prev = 0
        for (c in r.reversed()) {
            val v = when (c) { 'i' -> 1; 'v' -> 5; else -> 10 }
            total += if (v < prev) -v else v
            prev = max(prev, v)
        }
        return total
    }

    /** A floor and how many words it took: "2nd floor", "2e verdieping", "2. Stock", "floor 2", "ground floor", "kelder". */
    private fun level(words: List<Word>, i: Int): Pair<Int, Int>? {
        val texts = words.drop(i).take(3).map { it.text }
        for ((phrase, level) in NAMED_LEVELS) {
            if (texts.size >= phrase.size && texts.subList(0, phrase.size) == phrase) return level to phrase.size
        }
        val w = texts[0]
        val next = texts.getOrNull(1) ?: return null
        if (next in FLOOR) {
            val n = ORDINAL_SUFFIXED.matchEntire(w)?.groupValues?.get(1)?.toIntOrNull() ?: ORDINAL_WORDS[w]
                // "2. Stock": the dot of the German ordinal is not a word
                ?: (if (w.all { it.isDigit() } && w.length <= 2 && next in FLOOR_AFTER_NUMBER) w.toInt() else null)
                ?: return null
            return n to 2
        }
        if (w in FLOOR && next.all { it.isDigit() } && next.length <= 2) return next.toInt() to 2
        return null
    }

    /** A number with a unit that is not a length, and how many words it took: "150 kW", "€100", "4*", "500 years old". */
    private fun amount(words: List<Word>, i: Int): Pair<Pair<Double, AmountUnit>, Int>? {
        val w = words[i].text
        val next = words.getOrNull(i + 1)?.text
        if (w in CURRENCY && next != null && NUMBER.matches(next)) return number(next)?.let { (it to AmountUnit.MONEY) to 2 }
        JOINED_AMOUNT.matchEntire(w)?.let { m ->
            val n = number(m.groupValues[1]) ?: return null
            return (n to (UNITS[m.groupValues[2]] ?: return null)) to 1
        }
        // "in the last 10 years", "afgelopen 10 jaar", "in den letzten 10 Jahren": built since then
        if (w in RECENT && next != null && NUMBER.matches(next) && UNITS[words.getOrNull(i + 2)?.text] == AmountUnit.YEARS) {
            return number(next)?.let { (it to AmountUnit.RECENT) to 3 }
        }
        if (!(words[i].isNumber || NUMBER.matches(w)) || next == null) return null
        val n = number(w) ?: return null
        // "ces 10 dernières années"
        if (next in RECENT && UNITS[words.getOrNull(i + 2)?.text] == AmountUnit.YEARS) return (n to AmountUnit.RECENT) to 3
        val unit = UNITS[next] ?: return null
        if (unit == AmountUnit.YEARS && words.getOrNull(i + 2)?.text in OLD) return (n to AmountUnit.AGE) to 3
        return (n to unit) to 2
    }

    /** A length, or a walk or ride of some minutes ([Pair.second] true), and how many words it took. */
    private fun distance(words: List<Word>, i: Int): Pair<Pair<Double, Boolean>, Int>? {
        val w = words[i].text
        JOINED.matchEntire(w)?.let { m ->
            val n = number(m.groupValues[1]) ?: return null
            val unit = m.groupValues[2]
            if (unit in MINUTES) {
                val (speed, used) = mode(words, i + 1)
                return (n * speed to true) to 1 + used
            }
            return (n * (LENGTH[unit] ?: return null) to false) to 1
        }
        val n = (if (words[i].isNumber || NUMBER.matches(w)) number(w) else null) ?: return null
        val unit = words.getOrNull(i + 1)?.text ?: return null
        if (unit in MINUTES) {
            val (speed, used) = mode(words, i + 2)
            return (n * speed to true) to 2 + used
        }
        val f = LENGTH[unit] ?: return null
        return (n * f to false) to 2
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

    private fun assemble(text: String, items: List<Item>, texts: List<String>): ParsedQuery {
        val steps = ArrayList<Step>()
        var step = Step()
        var current = step.subject
        var pending = Pending.NONE
        var pendingDistance: Pair<Double, Said>? = null
        val pendingAttrs = ArrayList<Attr>()
        val withAttrs = ArrayList<Attr>()
        val pendingConds = ArrayList<Cond>()
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
        // what waits for its value: a comparison, "closes", "until", "about", "built", "tall"; and a wish
        var cmp: Pair<Meaning.Compare, Said>? = null
        var change: Pair<ChangeKind, Said>? = null
        var until: Said? = null
        var approx: Said? = null
        var built: Said? = null
        var measure: Pair<Meaning.Dim, Said>? = null
        var soft = false
        var ask = Ask.LIST
        var askSaid: Said? = null
        var askSymbol: String? = null
        var asked: String? = null
        var guessed: String? = null
        // a "near" said right after a row's place, and the words of the last "in" or "with"
        var nearAfterRow: Row? = null
        var nearSaid: Said? = null
        var connector: Said? = null
        var lastRole: Meaning.Role? = null
        val year = (now ?: ZonedDateTime.now()).year
        // "is the Rijksmuseum …", "does the HEMA have …", "le musée est-il …": a question of one place
        val question = isQuestion(texts)
        var askHave = false
        // "not near", "not within": the next row stands away; "closed on Monday"; "gaat … open", "macht … zu"
        var negateRow = false
        var closedNext = false
        // the open time said last and where, for a closed word after it ("op maandag dicht")
        var lastOpen: OpenParts? = null
        var lastOpenAt = -1
        var goes: Said? = null
        var goesAt: Pair<LocalTime, Said>? = null
        var builtWord = false
        var counted = false
        // "per neighbourhood": what a count is told by
        var perNext = false
        var per: String? = null
        // the items that name an area or what is asked, which are not a question's place; and those of the area
        val aside = HashSet<Int>()
        val areaAt = HashSet<Int>()

        fun setAroundMe(said: Said) {
            val d = pendingDistance?.first ?: AROUND_ME
            step.area = Query.Within(null, d)
            step.areaChip = chip(ChipKind.AREA, "ui-locate", Said.of(listOfNotNull(pendingDistance?.second, said)), d.roundToInt().toString())
            pendingDistance = null
        }

        /**
         * A "near" with no place after it: the row's place before it may stand near ("Starbucks with toilets near"),
         * else the searcher's surroundings ("pharmacy near").
         */
        fun danglingNear() {
            if (pending != Pending.NEAR) return
            val row = nearAfterRow
            if (row != null) {
                if (row.kind == RowKind.WANT && row.distanceSaid == null) {
                    row.distance = max(row.distance, DEFAULT_NEAR)
                    row.distanceSaid = nearSaid
                }
            } else if (aroundMe == null && step.area == null) {
                aroundMe = nearSaid
            }
            pending = Pending.NONE
            nearAfterRow = null
        }

        /**
         * Who "with" a property describes when no kind of place follows: a near row's place is a landmark, its subject
         * is meant, unless only the place can have it ("a playground near a café with a terrace").
         */
        fun withTarget(): Target {
            val row = step.rows.lastOrNull { it.target === current } ?: return current
            if (row.kind != RowKind.NEAR && row.kind != RowKind.NOT_NEAR) return current
            val venueOnly = withAttrs.isNotEmpty() && withAttrs.all { a -> a.meaning.filter.keys().any { it in VENUE_KEYS } }
            return if (venueOnly && !venue(step.subject) && venue(current)) current else step.subject
        }

        fun endWith() {
            if (pending != Pending.WITH) return
            withTarget().attrs += withAttrs
            withAttrs.clear()
            pending = Pending.NONE
        }

        fun closeStep(flushOpen: Boolean) {
            danglingNear()
            aroundMe?.let { setAroundMe(it) }
            aroundMe = null
            pendingDistance?.let { (d, r) ->
                step.area = Query.Within(null, d)
                step.areaChip = chip(ChipKind.AREA, "ui-locate", r, d.roundToInt().toString())
            }
            pendingDistance = null
            if (withAttrs.isNotEmpty()) { withTarget().attrs += withAttrs; withAttrs.clear() }
            if (pendingAttrs.isNotEmpty()) { current.attrs += pendingAttrs; pendingAttrs.clear() }
            if (pendingConds.isNotEmpty()) { current.conds += pendingConds; pendingConds.clear() }
            // "gaat om 8 uur" and no end to it: open then
            goesAt?.let { (t, said) -> pendingOpen.clock = t; pendingOpen.raw += said }
            goesAt = null
            goes = null
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
            negateRow = false
            closedNext = false
            askHave = false
            cmp = null
            change = null
            until = null
            approx = null
            built = null
            measure = null
            soft = false
        }

        /** Gives [t] what was waiting for the next kind of place: properties and conditions said before it, and opening times. */
        fun settle(t: Target) {
            t.attrs += pendingAttrs
            pendingAttrs.clear()
            t.conds += pendingConds
            pendingConds.clear()
            if (!pendingOpen.isEmpty) { pendingOpen.addTo(t.open); pendingOpen.clear() }
        }

        fun takeSoft(): Boolean = soft.also { soft = false }

        /** Whether the next word that matters names a kind of place. */
        fun nounFollows(i: Int): Boolean {
            for (j in i + 1 until items.size) {
                val it = items[j]
                if (it.role() == Meaning.Role.FILLER) continue
                return it is Item.M && it.meaning is Meaning.Noun
            }
            return false
        }

        /** Whether an opening time and then a kind of place follow [i], as a plan's next stop has them ("and a late ramen"). */
        fun timedNounFollows(i: Int): Boolean {
            var timed = false
            for (j in i + 1 until items.size) {
                val it = items[j]
                when {
                    it.role() == Meaning.Role.FILLER -> {}
                    it is Item.Time || it is Item.M && (it.meaning is Meaning.Open || it.meaning is Meaning.Day) -> timed = true
                    it is Item.M && it.meaning is Meaning.Noun -> return timed
                    else -> return false
                }
            }
            return false
        }

        /** Whether a kind of place follows a number, past orders and properties: "the 3 closest supermarkets", "10 oude gebouwen". */
        fun limitFollows(i: Int): Boolean {
            for (j in i + 1 until items.size) {
                val it = items[j]
                if (it is Item.M && (it.meaning is Meaning.Sort || it.meaning is Meaning.Attribute || it.meaning is Meaning.Colour)) continue
                if (it.role() == Meaning.Role.FILLER) continue
                return it is Item.M && it.meaning is Meaning.Noun
            }
            return false
        }

        /** Whether the next kind of place is one places lie in ("on the beach", "op de markt"). */
        fun containerFollows(i: Int): Boolean {
            for (j in i + 1 until items.size) {
                val it = items[j]
                if (it.role() == Meaning.Role.FILLER) continue
                return it is Item.M && (it.meaning as? Meaning.Noun)?.let { n -> Lexicon.isContainer(n.query) } == true
            }
            return false
        }

        /** A condition for the kind of place it stands next to: the one before it, else the one it comes before. */
        fun cond(c: Cond, i: Int) {
            recognised++
            if (!current.hasNoun || nounFollows(i)) pendingConds += c else current.conds += c
        }

        fun noun(said: Meaning.Noun, raw: Said, at: Int = -1) {
            var n = said
            // "bars per neighbourhood": what the count is told by, not a kind of its own
            if (perNext) {
                perNext = false
                PER_PLACES[sample(n.query)["place"]]?.let { by ->
                    per = by
                    if (at >= 0) aside += at
                    return
                }
            }
            // "does the HEMA have toilets": what the question asks of it
            if (askHave && pending == Pending.WITH) {
                val amenity = (n.query as? Query.Tags)?.let { Lexicon.amenities[it.filter.toText()] }
                if (amenity != null) {
                    ask = Ask.FACT
                    asked = amenity.first.keys().first()
                    askSaid = raw
                    askSymbol = n.symbol
                    if (at >= 0) aside += at
                    pending = Pending.NONE
                    askHave = false
                    return
                }
            }
            // "a supermarket but not Albert Heijn", "Supermarkt außer Lidl": a brand it is not
            if ((pending == Pending.WITHOUT || negateNext) && n.query is Query.Brand && current.hasNoun) {
                current.excluded += n to raw
                pending = Pending.NONE
                negateNext = false
                return
            }
            // "recycle batteries", "batterijen recyclen": what the recycling point takes
            val here = current
            val takes = RECYCLES[n.id]
            if (takes != null && here.noun?.id == RECYCLING && here.recycles == null) {
                here.recycles = takes to raw
                return
            }
            val taken = here.noun?.id?.let { RECYCLES[it] }
            if (n.id == RECYCLING && taken != null && here.recycles == null) {
                here.recycles = taken to here.nounSaid
                here.noun = n
                here.nounSaid = raw
                return
            }
            // "near Dam": a preset said bare after "near" may as well be a place's name
            if (pending == Pending.NEAR && n.rank >= 4 && at > 0 && items[at - 1].role() == Meaning.Role.NEAR) {
                n = Meaning.Noun(Query.or(listOf(n.query, Query.Name(raw.text))), n.symbol, n.id, n.rank, n.cuisine)
            }
            // "after" before a kind of place is "then" ("the cinema after dinner"); other values never came
            cmp?.let { (c, _) -> if (c.then && step.subject.hasNoun) newStep(flushOpen = true) }
            cmp = null
            change = null
            until = null
            built = null
            measure = null
            val qualified = !pendingOpen.isEmpty
            if (pending == Pending.NONE && !orNext && !afterComma && !soft && pendingDistance == null && aroundMe == null && current === step.subject &&
                step.rows.isEmpty() && at > 0 && (items[at - 1] as? Item.M)?.meaning is Meaning.Noun && service(n.query) && step.subject.noun?.let { service(it.query) } == false
            ) pending = Pending.WITH
            // "… and maybe an ATM nearby": a wish for a place by the subject
            val wish = soft && step.subject.hasNoun
            val connects = wish || pending == Pending.NEAR || pending == Pending.WITH || pending == Pending.NOT_NEAR ||
                pending == Pending.AND_NEAR || pending == Pending.FROM || pending == Pending.IN || pending == Pending.WITHOUT ||
                aroundMe != null || (pendingDistance != null && pending != Pending.WITHIN)
            // "… with cheap parking and a late ramen": a kind with its own time after "and" is a stop of its own
            val ownStop = qualified && pending == Pending.AND_NEAR
            if (connects && step.subject.hasNoun && !ownStop) {
                // "een bank in het park": a bench
                if (pending == Pending.IN && current === step.subject && Words.fold(current.nounSaid.text) in BANK_WORDS && outdoors(n.query)) {
                    Lexicon.noun("bench")?.let { current.noun = it }
                }
                val t = Target().also { it.noun = n; it.nounSaid = raw }
                val amenity = (n.query as? Query.Tags)?.let { Lexicon.amenities[it.filter.toText()] }
                var parent = step.subject
                var have: TagFilter? = null
                var distance = pendingDistance?.first
                    ?: (if (pending == Pending.AND_NEAR) lastRow?.distance else null)
                    ?: DEFAULT_NEAR
                val kind = when {
                    pending == Pending.IN -> RowKind.INSIDE
                    negateRow -> RowKind.NOT_NEAR
                    pending == Pending.WITH && current.noun?.let { Lexicon.isContainer(it.query) } == true -> {
                        parent = current
                        RowKind.CONTAINS
                    }
                    (pending == Pending.WITH || wish) && amenity != null -> {
                        have = amenity.first
                        distance = pendingDistance?.first ?: amenity.second
                        RowKind.WANT
                    }
                    pending == Pending.NOT_NEAR || pending == Pending.WITHOUT -> RowKind.NOT_NEAR
                    else -> RowKind.NEAR
                }
                val via = if (kind == RowKind.INSIDE || kind == RowKind.CONTAINS) connector else null
                val row = Row(t, distance, pendingDistance?.second, kind, pending == Pending.WITH, parent, takeSoft(), have, via)
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
                negateRow = false
                lastWasRowNoun = true
                afterComma = false
                orNext = false
                return
            }
            soft = false
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
            val attr = Attr(a, raw, negateNext, takeSoft())
            negateNext = false
            val row = lastRow
            when {
                pending == Pending.WITH -> withAttrs += attr
                // "a Starbucks with toilets and wifi": the wifi is the Starbucks'
                pending == Pending.AND_NEAR && row != null && row.kind == RowKind.WANT && current === row.target -> {
                    row.parent.attrs += attr
                    pending = Pending.NONE
                }
                pending == Pending.NEAR || pending == Pending.NOT_NEAR || pending == Pending.AND_NEAR || pending == Pending.FROM -> pendingAttrs += attr
                !current.hasNoun -> pendingAttrs += attr
                else -> current.attrs += attr
            }
        }

        /** Open words and times before a kind of place wait for it ("the 20:00 film", "a late ramen"); after one they are its. */
        fun openPart(i: Int, apply: (OpenParts) -> Unit, raw: Said) {
            val target = if (!current.hasNoun || nounFollows(i)) pendingOpen else current.open
            apply(target)
            // "closed on Monday"
            if (closedNext) { target.closed = true; closedNext = false }
            target.raw += raw
            lastOpen = target
            lastOpenAt = i
        }

        /** Built in [from]..[to], or before or after it as the waiting comparison says ("before 1900", "after the 1950s"). */
        fun builtIn(from: Int, to: Int, said: Said, i: Int) {
            val op = cmp?.first?.op
            val words = Said.of(listOfNotNull(built, cmp?.second, approx, said))
            val cond = when (op) {
                null -> if (approx != null) Cond.Built(null, from - APPROX_YEARS, to + APPROX_YEARS, words, takeSoft()) else Cond.Built(null, from, to, words, takeSoft())
                CompareOp.LT, CompareOp.GE -> Cond.Built(op, from, from, words, takeSoft())
                CompareOp.LE, CompareOp.GT -> Cond.Built(op, to, to, words, takeSoft())
            }
            cond(cond, i)
            cmp = null
            built = null
            approx = null
        }

        /** The end of "gaat … open", "macht … zu": opens or closes at the time said between, else at the time after. */
        fun goesTo(kind: ChangeKind, said: Said, i: Int) {
            val at = goesAt
            goes = null
            goesAt = null
            if (at != null) cond(Cond.Change(kind, at.first, AT_MINUTES, Said.of(listOf(at.second, said)), takeSoft()), i)
            else change = kind to said
        }

        /** A clock time: when places close or open, an "until", "before" or "after", or plainly when to be open. */
        fun clockTime(at: LocalTime, said: Said, i: Int, ambiguous: Boolean = false) {
            recognised++
            // "gaat om 8 uur open", "macht um 22 Uhr zu": the time waits for the verb's end
            if (goes != null && change == null && cmp == null && until == null) {
                goesAt = at to said
                return
            }
            var t = at
            var amb = ambiguous
            // "open until 10", "closes at 8": the evening's
            if (amb && (until != null || change?.first == ChangeKind.CLOSES) && t.hour in 5..11) {
                t = t.plusHours(12)
                amb = false
            }
            val around = approx != null || pending == Pending.NEAR
            // "around 19:30" is a time, not a place
            if (pending == Pending.NEAR) { pending = Pending.NONE; nearAfterRow = null }
            val ch = change
            val c = cmp
            val before = c != null && (c.first.op == CompareOp.LT || c.first.op == CompareOp.LE)
            val after = c != null && (c.first.op == CompareOp.GT || c.first.op == CompareOp.GE)
            when {
                // "opens before 7": open by then; "closes after 10 pm": still open then
                ch != null && ch.first == ChangeKind.OPENS && before ->
                    openPart(i, { it.clock = t.minusMinutes(BEFORE_MINUTES) }, Said.of(listOfNotNull(ch.second, c?.second, said)))
                ch != null && ch.first == ChangeKind.CLOSES && after ->
                    openPart(i, { it.clock = t }, Said.of(listOfNotNull(ch.second, c?.second, said)))
                ch != null -> {
                    recognised--
                    val window = if (around) AROUND_MINUTES else AT_MINUTES
                    cond(Cond.Change(ch.first, t, window, Said.of(listOfNotNull(ch.second, approx, nearSaid.takeIf { around && approx == null }, said)), takeSoft()), i)
                }
                until != null -> openPart(i, { it.clock = t.minusMinutes(1) }, Said.of(listOfNotNull(until, said)))
                before -> openPart(i, { it.clock = t.minusMinutes(BEFORE_MINUTES); it.ambiguous = amb }, Said.of(listOfNotNull(c?.second, said)))
                else -> openPart(i, { it.clock = t; it.ambiguous = amb }, Said.of(listOfNotNull(c?.second, said)))
            }
            change = null
            until = null
            cmp = null
            approx = null
            lastWasRowNoun = false
        }

        /** "500 years old", "older than 500 years", "less than 10 years old": built before or after that many years ago. */
        fun age(years: Double, said: Said, i: Int) {
            val c = cmp?.first
            val older = when {
                c == null -> true
                c.dim == Meaning.Dim.AGE -> c.op == CompareOp.LT || c.op == CompareOp.LE
                else -> c.op == CompareOp.GT || c.op == CompareOp.GE
            }
            val op = when {
                !older -> CompareOp.GT
                c == null -> CompareOp.LE
                else -> CompareOp.LT
            }
            val y = year - years.roundToInt()
            cond(Cond.Built(op, y, y, Said.of(listOfNotNull(cmp?.second, said)), takeSoft()), i)
            cmp = null
        }

        fun amountOf(item: Item.Amount, i: Int) {
            val c = cmp?.first
            val said = Said.of(listOfNotNull(cmp?.second, item.said))
            val (dim, op) = when (item.unit) {
                AmountUnit.YEARS, AmountUnit.AGE -> { age(item.value, item.said, i); return }
                AmountUnit.RECENT -> {
                    val y = year - item.value.roundToInt()
                    cond(Cond.Built(CompareOp.GE, y, y, said, takeSoft()), i)
                    cmp = null
                    return
                }
                AmountUnit.KW -> MeasureDim.POWER to (c?.op ?: CompareOp.GE)
                AmountUnit.MONEY -> MeasureDim.MONEY to (c?.op ?: CompareOp.LE)
                AmountUnit.STARS -> MeasureDim.STARS to c?.op
                AmountUnit.FLOORS -> MeasureDim.FLOORS to c?.op
                AmountUnit.SEATS -> MeasureDim.SEATS to (c?.op ?: CompareOp.GE)
                AmountUnit.SPACES -> MeasureDim.SPACES to (c?.op ?: CompareOp.GE)
                AmountUnit.ROOMS -> MeasureDim.ROOMS to (c?.op ?: CompareOp.GE)
            }
            cond(Cond.Measure(dim, op, item.value, said, takeSoft()), i)
            cmp = null
        }

        /** "4 stars or more", "under 2 euros or less": the amount said before as a least or a most. */
        fun reop(op: CompareOp) {
            for (list in listOf(pendingConds, current.conds, step.subject.conds)) {
                val k = list.indexOfLast { it is Cond.Measure }
                if (k < 0) continue
                val c = list[k] as Cond.Measure
                list[k] = Cond.Measure(c.dim, op, c.value, c.said, c.soft)
                return
            }
        }

        /** Whether a length said with "more than" is a height: of buildings, towers, peaks; not of cafés. */
        fun measured(t: Target): Boolean = t.noun?.query?.let { tall(it) } ?: true

        /** The area the step is in, or, after "not" and "outside", the one it keeps out of. */
        fun placeArea(q: Query.InArea, said: Said, lowercase: Boolean = false) {
            val c = chip(ChipKind.AREA, "ui-map-pin", said, q.name.orEmpty(), negated = negateNext, lowercase = lowercase)
            if (negateNext) { step.away = Query.Not(q); step.awayChip = c } else { step.area = q; step.areaChip = c }
            negateNext = false
        }

        /** An area named after "in", "at" or "on" ("in de Jordaan", "op de Albert Cuypstraat"); where it ends, or -1. */
        fun area(role: Meaning.Role, i: Int): Int {
            val (name, next) = areaName(items, i + 1)
            if (name == null) return -1
            recognised++
            placeArea(Query.InArea(null, name.text), name)
            for (k in i + 1 until next) { aside += k; areaAt += k }
            lastRole = role
            return next
        }

        var i = 0
        while (i < items.size) {
            val item = items[i]
            val isFiller = item.role() == Meaning.Role.FILLER
            // "nearby" followed by a kind of place is "near" it; otherwise it is the searcher's surroundings
            if (aroundMe != null && !isFiller && !(item is Item.M && item.meaning is Meaning.Noun)) {
                setAroundMe(aroundMe!!)
                aroundMe = null
            }
            // "how many spots", "hoeveel plekken": the places' capacity
            if (ask == Ask.COUNT && Words.fold(item.raw) in SPACE_WORDS) {
                ask = Ask.FACT
                asked = CAPACITY
                askSaid = Said.of(listOfNotNull(askSaid, item.said))
                askSymbol = "ui-users"
                counted = true
                recognised++
                i++
                continue
            }
            val prev = items.getOrNull(i - 1)
            when (item) {
                is Item.Comma -> {
                    danglingNear()
                    afterComma = true
                    // a comma ends "with …": "a café with an outlet, charge the car nearby"
                    endWith()
                    cmp = null
                    change = null
                    until = null
                    built = null
                    goes = null
                    soft = false
                }
                is Item.Unknown -> {
                    val f = Words.fold(item.raw)
                    val named = pending == Pending.NEAR || pending == Pending.NOT_NEAR || pending == Pending.AND_NEAR || pending == Pending.FROM
                    when {
                        // "macht um 8 Uhr auf"
                        goes != null && f in OPEN_PARTICLES -> { recognised++; goesTo(ChangeKind.OPENS, item.said, i) }
                        // "taller than the Westertoren", "older than the Westerkerk": the other place's measure is the bound
                        cmp?.first?.dim?.let { THAN_KEYS[it] } != null && step.subject.hasNoun -> {
                            val (name, next) = nameRun(items, i)
                            val (c, cs) = cmp!!
                            cond(Cond.Than(THAN_KEYS.getValue(c.dim!!), c.op, name.text, Said.of(listOf(cs, name)), takeSoft()), i)
                            cmp = null
                            i = next
                            continue
                        }
                        // a place by its name: "near the Westerkerk", "bij het Rijksmuseum", "500 m from the A10"
                        named && step.subject.hasNoun -> {
                            val (name, next) = nameRun(items, i)
                            val ref = ROAD_REF.matchEntire(f)?.takeIf { next == i + 1 }
                            val q = if (ref != null) Query.Tags(roadRef(ref)) else Query.Name(name.text)
                            recognised++
                            noun(Meaning.Noun(q, if (ref != null) "ui-car" else "ui-map-pin", "name:" + Words.fold(name.text), rank = 0), name)
                            lastRole = null
                            i = next
                            continue
                        }
                        // "boulangerie amsterdam", "lidl zuidas": words right after a kind say where, if a place of that name is known;
                        // a question names its place ("la hauteur de la tour Eiffel")
                        guessAreas && !question && ask != Ask.FACT && ask != Ask.HOURS && step.area == null && pending == Pending.NONE &&
                            prev is Item.M && prev.meaning is Meaning.Noun && !prev.fuzzy -> {
                            val end = (i until items.size).firstOrNull { items[it] !is Item.Unknown } ?: items.size
                            val name = Said.of(items.subList(i, end).map { it.said })
                            recognised++
                            step.area = Query.InArea(null, name.text)
                            step.areaChip = chip(ChipKind.AREA, "ui-map-pin", name, name.text, lowercase = false)
                            for (k in i until end) { aside += k; areaAt += k }
                            guessed = name.text
                            i = end
                            continue
                        }
                        else -> unknown += item.raw
                    }
                }
                is Item.Number -> {
                    val n = item.n
                    val isYear = item.raw.length == 4 && n in MIN_YEAR..MAX_YEAR
                    val c = cmp?.first
                    val m = measure
                    when {
                        // "cafés in 1017", "bakker op 1072": a postcode, not the year of a café
                        item.raw.length in 4..5 && item.raw.all { it.isDigit() } && c == null && built == null && approx == null &&
                            (lastRole == Meaning.Role.IN || lastRole == Meaning.Role.AT || lastRole == Meaning.Role.ON) &&
                            current.noun?.query?.let { tall(it) } != true -> {
                            recognised++
                            step.area = Query.InArea(null, item.raw)
                            step.areaChip = chip(ChipKind.AREA, "ui-map-pin", item.said, item.raw, lowercase = false)
                            aside += i
                            areaAt += i
                            if (pending == Pending.IN) pending = Pending.NONE
                        }
                        // "rated above 4", "beoordeling hoger dan 8": a rating out of five, or of ten
                        m != null && m.first == Meaning.Dim.RATING && n in 0..10 -> {
                            val v = (item.raw.replace(',', '.').toDoubleOrNull() ?: n.toDouble()).let { if (it > 5) it / 2 else it }
                            cond(Cond.Measure(MeasureDim.RATING, c?.op ?: CompareOp.GE, v, Said.of(listOfNotNull(m.second, cmp?.second, item.said)), takeSoft()), i)
                            cmp = null
                            measure = null
                        }
                        // "closes at 6", "open until 10", "after 10": hours
                        !isYear && n in 0..24 && (change != null || until != null || c?.dim == Meaning.Dim.YEAR) -> {
                            val h = if ((change?.first == ChangeKind.CLOSES || until != null) && n in 5..11) n + 12 else n
                            clockTime(LocalTime.of(h % 24, 0), item.said, i)
                        }
                        isYear && (c != null || built != null || lastRole == Meaning.Role.FROM || lastRole == Meaning.Role.IN || lastRole == Meaning.Role.OF || approx != null) ->
                            builtIn(n, n, item.said, i)
                        limitFollows(i) -> { step.limit = n; step.limitSaid = item.said; recognised++ }
                        else -> unknown += item.raw
                    }
                }
                is Item.Amount -> amountOf(item, i)
                is Item.Years -> builtIn(item.from, item.to, item.said, i)
                is Item.Level -> cond(Cond.Level(item.level, item.said, takeSoft()), i)
                is Item.Distance -> {
                    val c = cmp
                    val m = measure
                    val height = !item.timed && (
                        c?.first?.dim == Meaning.Dim.TALL || c?.first?.dim == Meaning.Dim.ELEVATION || m != null ||
                            (c != null && c.first.dim == null && (c.first.op == CompareOp.GT || c.first.op == CompareOp.GE) && measured(current))
                        )
                    if (height) {
                        // "taller than 100 m", "peaks above 2000 m"
                        val dim = if (c?.first?.dim == Meaning.Dim.ELEVATION || m?.first == Meaning.Dim.ELEVATION) MeasureDim.ELEVATION else MeasureDim.TALL
                        cond(Cond.Measure(dim, c?.first?.op ?: CompareOp.GE, item.metres, Said.of(listOfNotNull(c?.second, m?.second, item.said)), takeSoft()), i)
                        cmp = null
                        measure = null
                    } else {
                        recognised++
                        // "under 500 m", "less than 10 min walk": within
                        if (c != null && (c.first.op == CompareOp.LT || c.first.op == CompareOp.LE)) pending = Pending.WITHIN
                        cmp = null
                        val row = lastRow
                        if (lastWasRowNoun && row != null && row.distanceSaid == null) {
                            row.distance = item.metres
                            row.distanceSaid = item.said
                        } else {
                            pendingDistance = item.metres to item.said
                        }
                    }
                    lastWasRowNoun = false
                }
                is Item.Time -> { endWith(); clockTime(item.time, item.said, i, item.ambiguous) }
                is Item.M -> {
                    item.lang?.let { votes.merge(it, 1, Int::plus) }
                    if (item.fuzzy) fuzzy++
                    when (val m = item.meaning) {
                        is Meaning.Noun -> { recognised++; noun(m, item.said, i) }
                        is Meaning.Attribute -> { recognised++; attribute(m, item.said); lastWasRowNoun = false }
                        is Meaning.Open -> {
                            recognised++
                            endWith()
                            val t = m.time
                            if (goes != null && t == OpenTime.Now) {
                                // "gaat om 8 uur open"
                                goesTo(ChangeKind.OPENS, item.said, i)
                            } else {
                                // "opens late", "opens early": open then
                                change = null
                                // "avondwinkel", "nachtapotheek": the word names the place too
                                m.noun?.let { if (!current.hasNoun && !nounFollows(i)) noun(it, item.said, i) }
                                when {
                                    t is OpenTime.Today -> openPart(i, { it.clock = t.time }, item.said)
                                    t is OpenTime.OnDay -> openPart(i, { o -> o.day = t.day; t.time?.let { o.clock = it } }, item.said)
                                    // "open now" after a kind is its own, or the subject's that a near row's place stands by
                                    t == OpenTime.Now && current.hasNoun -> withTarget().open.let { o ->
                                        // "restaurants vanavond open": open tonight, not now
                                        if (o.word == null) o.word = t
                                        o.raw += item.said
                                        if (closedNext) { o.closed = true; closedNext = false }
                                    }
                                    else -> openPart(i, { if (t != OpenTime.Now || it.word == null) it.word = t }, item.said)
                                }
                            }
                            lastWasRowNoun = false
                        }
                        is Meaning.Day -> {
                            recognised++
                            endWith()
                            openPart(i, { o ->
                                when {
                                    m.weekend -> o.weekend = true
                                    m.day != null -> o.day = m.day
                                    else -> o.dayOffset = m.offset
                                }
                            }, item.said)
                            lastWasRowNoun = false
                        }
                        is Meaning.Sort -> {
                            recognised++
                            if (pending == Pending.WITH && m.by == SortBy.CHEAPEST && nounFollows(i)) {
                                // "a cinema with cheap parking": the parking is cheap; the cinemas are not ordered by price
                                attribute(CHEAP, item.said)
                            } else {
                                endWith()
                                step.sort = m.by
                                step.sortSaid = item.said
                            }
                        }
                        is Meaning.Compare -> {
                            recognised++
                            if (m.dim == Meaning.Dim.NEW) {
                                // "newly built": in the last few years
                                recognised--
                                cond(Cond.Built(CompareOp.GE, year - NEW_YEARS, year - NEW_YEARS, item.said, takeSoft()), i)
                            } else {
                                endWith()
                                cmp = m to item.said
                            }
                        }
                        is Meaning.Measure -> {
                            val d = pendingDistance
                            if (prev is Item.Distance && d != null) {
                                // "100 m high": the length before was a height
                                pendingDistance = null
                                if (pending == Pending.WITHIN) pending = Pending.NONE
                                val dim = if (m.dim == Meaning.Dim.ELEVATION) MeasureDim.ELEVATION else MeasureDim.TALL
                                recognised--
                                cond(Cond.Measure(dim, cmp?.first?.op ?: CompareOp.GE, d.first, Said.of(listOfNotNull(cmp?.second, d.second, item.said)), takeSoft()), i)
                                cmp = null
                            } else {
                                measure = m.dim to item.said
                            }
                        }
                        Meaning.Built -> { recognised++; built = item.said; builtWord = true }
                        is Meaning.Change -> { recognised++; change = m.kind to item.said }
                        is Meaning.AskFor -> {
                            recognised++
                            aside += i
                            // the first that names a fact stays: "when was it built" asks the year, "and when" the hours
                            if (ask != Ask.FACT) {
                                ask = m.ask
                                asked = m.key
                                askSaid = item.said
                                askSymbol = m.symbol
                            }
                        }
                        is Meaning.Rating -> cond(Cond.Measure(MeasureDim.RATING, m.op, m.value, item.said, takeSoft()), i)
                        is Meaning.Later -> {
                            // "closing soon", "in an hour": that many minutes from now, give or take
                            if (approx == null) approx = item.said
                            val t = (now ?: ZonedDateTime.now()).toLocalTime().withSecond(0).withNano(0).plusMinutes(m.minutes.toLong())
                            clockTime(t, item.said, i)
                        }
                        is Meaning.Radius -> {
                            recognised++
                            step.area = Query.Within(null, m.metres)
                            step.areaChip = chip(ChipKind.AREA, "ui-locate", item.said, m.metres.roundToInt().toString())
                        }
                        is Meaning.Area -> {
                            recognised++
                            if (pending == Pending.IN || pending == Pending.NEAR || pending == Pending.WITHOUT) pending = Pending.NONE
                            placeArea(Query.InArea(null, m.name), item.said)
                            aside += i
                            areaAt += i
                        }
                        is Meaning.Aim -> Intents.byId(m.id)?.let { intent ->
                            recognised++
                            // a word that names the place too ("Badesee") is that place, to do it at
                            m.noun?.let { noun(it, item.said, i) }
                            // the place it is said after ("a café by a lake to swim"), else the subject
                            val t = lastRow?.takeIf { lastWasRowNoun }?.target ?: step.subject
                            if (t.aim == null) t.aim = intent to item.said
                        }
                        Meaning.Town -> {
                            recognised++
                            if (pending == Pending.IN || pending == Pending.WITHOUT) pending = Pending.NONE
                            placeArea(Query.InArea(null, Query.InArea.TOWN), item.said, lowercase = true)
                            aside += i
                            areaAt += i
                        }
                        is Meaning.Colour -> cond(Cond.Colour(m.family, item.said, takeSoft()), i)
                        is Meaning.Word -> {
                            when (m.role) {
                                Meaning.Role.NEAR -> {
                                    recognised++
                                    // "not near a busy road"
                                    if (negateNext) { negateRow = true; negateNext = false }
                                    nearAfterRow = if (lastWasRowNoun) lastRow else null
                                    nearSaid = item.said
                                    pending = Pending.NEAR
                                    if (pendingDistance == null && Words.fold(item.raw) in NEXT_TO_WORDS) pendingDistance = NEXT_TO to item.said
                                }
                                Meaning.Role.WITH -> { danglingNear(); recognised++; connector = item.said; pending = Pending.WITH }
                                // "restaurants that have a terrace"; in a question what is asked ("does it have toilets")
                                Meaning.Role.HAVE -> {
                                    danglingNear()
                                    recognised++
                                    connector = item.said
                                    if (question) askHave = true
                                    pending = Pending.WITH
                                }
                                Meaning.Role.NOT_NEAR -> { danglingNear(); recognised++; pending = Pending.NOT_NEAR }
                                Meaning.Role.PER -> if (ask == Ask.COUNT) { recognised++; perNext = true }
                                // "within the park" is in it; "within 500 m" a circle; "not within 500 m of" away
                                Meaning.Role.WITHIN -> {
                                    recognised++
                                    if (negateNext) { negateRow = true; negateNext = false }
                                    connector = item.said
                                    pending = if (nounFollows(i)) Pending.IN else Pending.WITHIN
                                }
                                Meaning.Role.WITHOUT -> { recognised++; negateNext = true; if (nounFollows(i)) pending = Pending.WITHOUT }
                                // "hoogste gebouw van Rotterdam", "le plus vieux café de Paris": where the superlative holds
                                Meaning.Role.FROM, Meaning.Role.OF -> if (step.sort != null && step.area == null && pendingDistance == null && area(m.role, i).also { if (it >= 0) i = it } >= 0) {
                                    continue
                                } else if (m.role == Meaning.Role.FROM) {
                                    if (pendingDistance != null) { recognised++; pending = Pending.FROM }
                                } else when {
                                    pendingDistance != null -> { recognised++; pending = Pending.FROM }
                                    item.lang == "nl" && lang == "nl" -> { recognised++; orNext = true }
                                }
                                Meaning.Role.AROUND_ME -> {
                                    recognised++
                                    val row = lastRow
                                    if (lastWasRowNoun && row != null && (row.viaWith || row.soft || row.kind == RowKind.WANT)) {
                                        // "a café with a charger nearby", "maybe an ATM nearby": the row's place stands near
                                        if (row.kind == RowKind.WANT && row.distanceSaid == null) {
                                            row.distance = max(row.distance, DEFAULT_NEAR)
                                            row.distanceSaid = item.said
                                        }
                                    } else {
                                        aroundMe = item.said
                                    }
                                }
                                // "outside Amsterdam", "buiten het centrum": the area after it, kept out of
                                Meaning.Role.OUTSIDE -> {
                                    danglingNear()
                                    recognised++
                                    negateNext = true
                                    val next = area(Meaning.Role.IN, i)
                                    if (next >= 0) {
                                        recognised--
                                        i = next
                                        continue
                                    }
                                }
                                Meaning.Role.IN -> {
                                    danglingNear()
                                    val next = area(m.role, i)
                                    if (next >= 0) {
                                        i = next
                                        continue
                                    } else if (nounFollows(i)) {
                                        recognised++
                                        connector = item.said
                                        pending = Pending.IN
                                    }
                                }
                                // "at Schiphol", "op de Albert Cuypstraat": an area; "at the station", "aan de gracht": by
                                // it; "on the beach", "op de markt": in it
                                Meaning.Role.AT, Meaning.Role.ON -> {
                                    danglingNear()
                                    val next = area(m.role, i)
                                    if (next >= 0) {
                                        i = next
                                        continue
                                    } else if (nounFollows(i)) {
                                        recognised++
                                        connector = item.said
                                        if (m.role == Meaning.Role.ON && containerFollows(i)) {
                                            pending = Pending.IN
                                        } else {
                                            nearAfterRow = if (lastWasRowNoun) lastRow else null
                                            nearSaid = item.said
                                            pending = Pending.NEAR
                                        }
                                    }
                                }
                                Meaning.Role.THEN -> { recognised++; newStep(flushOpen = true) }
                                Meaning.Role.AND -> {
                                    val row = lastRow
                                    when {
                                        // "then the film … and a late ramen": a kind with a time of its own is the plan's next stop
                                        steps.isNotEmpty() && timedNounFollows(i) -> { recognised++; newStep(flushOpen = true) }
                                        row != null && current === row.target && pending != Pending.WITH -> pending = Pending.AND_NEAR
                                    }
                                }
                                Meaning.Role.OR -> { recognised++; orNext = true }
                                Meaning.Role.SOFT -> { recognised++; soft = true }
                                Meaning.Role.APPROX -> { recognised++; approx = item.said }
                                Meaning.Role.UNTIL -> { recognised++; until = item.said }
                                // "closed on Monday"; the end of "gaat … dicht", "macht … zu"
                                Meaning.Role.CLOSED -> {
                                    recognised++
                                    val before = (i - 1 downTo 0).firstOrNull { items[it].role() != Meaning.Role.FILLER }
                                    val day = lastOpen?.takeIf { before == lastOpenAt && !items.timeFollows(i) }
                                    when {
                                        goes != null -> goesTo(ChangeKind.CLOSES, item.said, i)
                                        // "op maandag dicht", "montags geschlossen": the day before it
                                        day != null -> day.closed = true
                                        else -> closedNext = true
                                    }
                                }
                                Meaning.Role.OR_MORE -> { recognised++; reop(CompareOp.GE) }
                                Meaning.Role.OR_LESS -> { recognised++; reop(CompareOp.LE) }
                                Meaning.Role.GOES -> { recognised++; goes = item.said }
                                Meaning.Role.FILLER -> {}
                            }
                            if (m.role != Meaning.Role.FILLER && m.role != Meaning.Role.AROUND_ME && m.role != Meaning.Role.AND) lastWasRowNoun = false
                        }
                    }
                }
            }
            if (!isFiller) lastRole = item.role()
            i++
        }
        closeStep(flushOpen = true)

        // --------------------------------------------------------------------- the one place a question is about
        if (ask == Ask.HOURS && builtWord) {
            // "when was the Rijksmuseum built": the year
            ask = Ask.FACT
            asked = START_DATE
            askSymbol = "ui-history"
        }
        if (steps.size == 1 && !counted && (question || ask == Ask.FACT || ask == Ask.HOURS)) {
            val s = steps[0]
            val content = items.indices.filterTo(ArrayList()) { j ->
                j !in aside && items[j].let { it is Item.Unknown || it is Item.M && (it.meaning is Meaning.Noun || it.meaning is Meaning.Attribute) }
            }
            // "is the Rijksmuseum wheelchair accessible": a yes-or-no question asks its last property
            val last = content.lastOrNull()?.let { items[it] as? Item.M }
            val yesNo = (last?.meaning as? Meaning.Attribute)?.takeIf { question && ask == Ask.LIST && content.size > 1 }
            if (yesNo != null) content.removeAt(content.lastIndex)
            if (content.isNotEmpty()) {
                val first = items[content.first()]
                val single = content.size == 1 && first is Item.M && first.meaning is Meaning.Noun && !first.fuzzy
                val definite = items.getOrNull(content.first() - 1)?.let { Words.fold(it.raw) in DEFINITE } == true
                val brand = single && ((first as Item.M).meaning as Meaning.Noun).query is Query.Brand
                if (question && ask == Ask.LIST && (!single || brand || definite)) {
                    if (yesNo != null && last != null) {
                        ask = Ask.FACT
                        asked = yesNo.filter.keys().firstOrNull()
                        askSaid = last.said
                        askSymbol = yesNo.symbol
                        for (t in listOf(s.subject) + s.rows.map { it.target }) t.attrs.removeAll { it.meaning === yesNo && it.said.start == last.said.start }
                    } else if (s.subject.open.word == OpenTime.Now) {
                        // "is het Vondelpark open": its hours
                        ask = Ask.HOURS
                        askSaid = s.subject.open.raw.lastOrNull()
                        askSymbol = "ui-clock"
                        s.subject.open.word = null
                        if (s.subject.open.isEmpty) s.subject.open.raw.clear()
                    }
                }
                if ((ask == Ask.FACT || ask == Ask.HOURS) && !single) {
                    var from = content.first()
                    // "website of the Van Gogh Museum": the name's own "van"
                    while (from >= 2 && items[from - 1].role().let { it == Meaning.Role.FROM || it == Meaning.Role.OF } && items[from - 2].role() == Meaning.Role.FILLER) from--
                    val to = content.last()
                    val start = items[from].said.start
                    val end = items[to].said.end
                    if (start >= 0 && end > start) {
                        val said = Said(text.substring(start, end), start, end)
                        val place = Target().also { t ->
                            t.noun = Meaning.Noun(Query.Name(said.text), "ui-map-pin", "name:" + Words.fold(said.text), rank = 0)
                            t.nounSaid = said
                            s.subject.open.addTo(t.open)
                        }
                        s.subject = place
                        // its name's words are no kinds, rows or area of their own
                        s.rows.clear()
                        if (areaAt.any { it in from..to }) { s.area = null; s.areaChip = null }
                        for (j in from..to) if (items[j] is Item.Unknown && unknown.remove(items[j].raw)) recognised++
                    }
                }
            } else if (asked == POPULATION) {
                // "how many people live in Utrecht", "population of The Hague": the town's own
                (s.area as? Query.InArea)?.name?.takeIf { !it.startsWith("@") }?.let { name ->
                    s.subject = Target().also { t ->
                        t.noun = Meaning.Noun(Query.Name(name), "ui-map-pin", "name:" + Words.fold(name), rank = 0)
                        t.nounSaid = Said(name, -1, -1)
                    }
                    s.area = null
                    s.areaChip = null
                }
            }
        }

        // ---------------------------------------------------------------------------- what a kind left unsaid means
        for (s in steps) {
            val subject = s.subject
            if (subject.noun == null && subject.cuisine == null && subject.aim == null) when {
                // "a heated terrace", "terras": a café's, a bar's or a restaurant's
                subject.attrs.any { it.meaning.id in VENUE_ATTRS } -> subject.noun = VENUE
                // "drive through": food or coffee from the car
                subject.attrs.any { it.meaning.id == DRIVE_THROUGH_ATTR } -> subject.noun = DRIVE_THROUGH
                // "the highest": peaks; "the tallest": buildings
                s.rows.isEmpty() && s.sort == SortBy.HIGHEST -> subject.noun = Lexicon.noun("peak")
                s.rows.isEmpty() && s.sort == SortBy.TALLEST -> subject.noun = Lexicon.noun("building")
            }
            // "the highest building": the tallest
            if (s.sort == SortBy.HIGHEST && subject.noun?.query?.let { tall(it) && !elevated(it) } == true) s.sort = SortBy.TALLEST
            // "the bakery that opens the last in the morning": when they open is the order, the morning where; not open at nine
            val o = subject.open
            val at = o.clock
            if ((s.sort == SortBy.OPENS_LATEST || s.sort == SortBy.OPENS_EARLIEST) && at != null && o.word == null && o.day == null && o.dayOffset == null && !o.weekend) {
                subject.conds += Cond.Change(ChangeKind.OPENS, at, if (at.hour < NOON_HOUR) MORNING_WINDOW else CHANGE_WINDOW, Said.of(o.raw), soft = false)
                o.clear()
            }
        }

        // ---------------------------------------------------------------------------------------- to queries
        val queries = ArrayList<Query>()
        val chips = ArrayList<Chip>()
        askSaid?.let { chips += chip(ChipKind.ASK, askSymbol ?: if (ask == Ask.COUNT) "ui-layers" else "ui-clock", it, ask.name.lowercase(Locale.ROOT)) }
        for ((si, s) in steps.withIndex()) {
            var q: Query = build(s, s.subject, 0, null, chips, si) ?: Query.All
            s.area?.let { q = Query.and(q, it) }
            s.areaChip?.let { chips += it.copy(step = si) }
            s.away?.let { q = Query.and(q, it) }
            s.awayChip?.let { chips += it.copy(step = si) }
            s.sort?.let { q = Query.Sort(q, it); chips += chip(ChipKind.SORT, SORT_SYMBOLS.getValue(it), s.sortSaid, it.name.lowercase(Locale.ROOT), step = si) }
            s.limit?.let { q = Query.Limit(q, it); chips += chip(ChipKind.LIMIT, "ui-filter", s.limitSaid, it.toString(), step = si) }
            if (q != Query.All) queries += q
        }

        val significant = recognised + unknown.size
        var confidence = if (significant == 0) 0.0 else recognised.toDouble() / significant
        val anyKind = steps.any { it.subject.hasNoun || it.rows.isNotEmpty() || it.subject.attrs.isNotEmpty() || it.subject.conds.isNotEmpty() || it.subject.aim != null }
        if (!anyKind) confidence *= 0.3
        repeat(fuzzy) { confidence *= 0.85 }
        // Two kinds side by side ("café molen", "pizza restaurant") may well be a name or a preset's own name: only
        // connectors, properties, conditions, times, areas, orders and questions make it a query for sure.
        val structured = steps.size > 1 || ask != Ask.LIST || steps.any { s ->
            s.rows.isNotEmpty() || s.area != null || s.away != null || s.sort != null || s.limit != null || s.subject.attrs.isNotEmpty() ||
                s.subject.conds.isNotEmpty() || !s.subject.open.isEmpty || s.subject.aim != null || s.subject.excluded.isNotEmpty() ||
                s.subject.recycles != null
        }
        val intent = steps.firstOrNull()?.let { s -> s.subject.aim ?: s.rows.firstNotNullOfOrNull { it.target.aim } }?.first?.id
        val language = votes.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { if (it.key == lang) 0 else 1 }).firstOrNull()?.key
        return ParsedQuery(
            text, queries, chips, unknown, confidence.coerceIn(0.0, 1.0), QuerySyntax.NATURAL, language, structured && queries.isNotEmpty(), ask, intent,
            asked = asked.takeIf { ask == Ask.FACT },
            guessedArea = guessed?.takeIf { g -> steps.any { (it.area as? Query.InArea)?.name == g } },
            per = per.takeIf { ask == Ask.COUNT },
        )
    }

    /** The query for [t] with the rows that hang on it, in the words' order, and their chips. */
    private fun build(s: Step, t: Target, rowNo: Int, via: Row?, chips: MutableList<Chip>, step: Int): Query? {
        var q = targetQuery(t, chips, rowNo, step, via)
        for ((ri, r) in s.rows.withIndex()) {
            if (r.parent !== t) continue
            val other = build(s, r.target, ri + 1, r, chips, step) ?: continue
            val base = q ?: Query.All
            q = when (r.kind) {
                RowKind.NEAR -> if (r.soft) Query.and(base, Query.Prefer(Query.Near(Query.All, other, r.distance))) else Query.Near(base, other, r.distance)
                RowKind.NOT_NEAR -> Query.NotNear(base, other, r.distance)
                RowKind.INSIDE -> if (r.soft) Query.and(base, Query.Prefer(Query.Inside(Query.All, other))) else Query.Inside(base, other)
                RowKind.CONTAINS -> if (r.soft) Query.and(base, Query.Prefer(Query.Contains(Query.All, other))) else Query.Contains(base, other)
                RowKind.WANT -> {
                    val have = Query.Tags(r.have!!)
                    // its own, or one of its kind next door
                    Query.and(base, if (r.soft) Query.Prefer(Query.or(listOf(have, Query.Near(Query.All, other, r.distance)))) else Query.Want(have, other, r.distance))
                }
            }
        }
        return q
    }

    /** The query for one target and its chips; null when it names nothing. */
    private fun targetQuery(t: Target, chips: MutableList<Chip>, row: Int, step: Int, via: Row? = null): Query? {
        via?.let { r ->
            when (r.kind) {
                RowKind.INSIDE -> chips += chip(ChipKind.INSIDE, "ui-door", r.connector ?: Said("in", -1, -1), "", row, step)
                RowKind.CONTAINS -> chips += chip(ChipKind.CONTAINS, "ui-merge", r.connector ?: Said("with", -1, -1), "", row, step)
                else -> {
                    val said = r.distanceSaid ?: Said("${r.distance.roundToInt()} m", -1, -1)
                    chips += chip(ChipKind.DISTANCE, "ui-ruler", said, r.distance.roundToInt().toString(), row, step, r.negated)
                }
            }
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
            // "the American embassy": its country, not its food
            val nation = if (noun?.id == EMBASSY) NATIONS[n.id.removePrefix("cuisine:")] else null
            if (nation != null) {
                parts += Query.Tags(TagFilter.Equals("country", nation))
                chips += chip(ChipKind.PROPERTY, "ui-flag", said, "country=$nation", row, step)
            } else {
                parts += n.query
                chips += chip(ChipKind.KIND, n.symbol, said, valueOf(n), row, step)
            }
        }
        for ((n, said) in t.excluded) {
            parts += Query.Not(n.query)
            chips += chip(ChipKind.KIND, n.symbol, said, valueOf(n), row, step, negated = true)
        }
        t.recycles?.let { (k, said) ->
            val f = TagFilter.Equals("recycling:$k", "yes")
            parts += Query.Tags(f)
            chips += chip(ChipKind.PROPERTY, "ui-recycle", said, f.toText(), row, step)
        }
        t.aim?.let { (intent, said) ->
            // a kind that can say it serves the intent wants it said ("a lake to swim": bathing allowed), else the
            // places that serve it are what is searched for ("somewhere to swim")
            val want = intent.want
            when {
                noun == null && t.cuisine == null -> parts += Query.Tags(intent.filter)
                want != null -> parts += Query.Want(Query.Tags(want), intent.wantNear?.let { Query.Tags(it) }, intent.wantMetres)
            }
            chips += chip(ChipKind.AIM, intent.symbol, said, intent.id, row, step)
        }
        // a kind, a cuisine or an aim wants what it may leave unsaid; a property on its own is what is searched for
        val kindOf = noun != null || t.cuisine != null || t.aim != null
        for (a in t.attrs) {
            val soft = a.meaning.soft || a.soft
            val f = a.meaning.filter
            val q: Query = when {
                !a.negated -> Query.Tags(f)
                // "not a chain": a key that is there says it, so only its absence rules it out
                soft || f is TagFilter.Has -> Query.Not(Query.Tags(f))
                // "without wifi", "toilets that aren't paid": the places that say the opposite, and those that do not say
                else -> opposite(f)?.let { Query.Tags(it) } ?: Query.Not(Query.Tags(f))
            }
            parts += when {
                soft -> Query.Prefer(q)
                (a.meaning.want || a.negated && q is Query.Tags) && kindOf -> Query.Want(q)
                else -> q
            }
            chips += chip(if (soft) ChipKind.PREFERENCE else ChipKind.PROPERTY, a.meaning.symbol, a.said, a.meaning.filter.toText(), row, step, a.negated)
            // "free wifi": those that say it is free first
            if (!a.negated) a.meaning.prefer?.let { parts += Query.Prefer(Query.Tags(it)) }
            // quiet is away from busy roads too; off the tourist track away from its sights; uncrowded away from what draws crowds
            if (!a.negated) AWAY[a.meaning.id]?.let { (from, metres) -> parts += Query.Prefer(Query.NotNear(Query.All, Query.Tags(from), metres)) }
        }
        val day = t.open.day ?: t.open.dayOffset?.let { off -> (now ?: ZonedDateTime.now()).dayOfWeek.plus(off.toLong()) }
        for (c in t.conds) {
            val out = condQuery(t, c, day)
            parts += if (c.soft) Query.Prefer(out.soft) else out.query
            chips += chip(if (c.soft) ChipKind.PREFERENCE else out.kind, out.symbol, c.said, out.value, row, step)
        }
        // "shops that close around 19:30 on Sunday": the day is the closing time's
        val dayOfChange = t.conds.any { it is Cond.Change } && t.open.clock == null && t.open.word == null
        if (!dayOfChange) {
            val times = openTimes(t)
            if (times.isNotEmpty()) {
                val closedDays = times.mapNotNull { (it as? OpenTime.OnDay)?.takeIf { d -> d.time == null }?.day }
                parts += when {
                    t.open.closed && closedDays.size == times.size -> Query.or(closedDays.map { Query.ClosedOn(it) })
                    t.open.closed -> Query.Not(Query.or(times.map { Query.OpenAt(it) }))
                    else -> Query.or(times.map { Query.OpenAt(it) })
                }
                chips += chip(ChipKind.OPEN, "ui-clock", Said.of(t.open.raw), times.joinToString(" ") { openValue(it) }, row, step, t.open.closed)
            }
        }
        if (parts.isEmpty()) return null
        return Query.and(parts)
    }

    /** A condition's query (a [Query.Want] when its kind of place may leave it unsaid), what it prefers when wished, and its chip. */
    private class CondQuery(val query: Query, val soft: Query, val kind: ChipKind, val symbol: String, val value: String)

    /** Whether [q] is a kind of structure with a build year of its own (a building, a tower, a bunker), not a business in one. */
    private fun structure(q: Query): Boolean {
        var own = false
        q.walk { if (it is Query.Tags && it.filter.keys().any { k -> k in STRUCTURE_KEYS }) own = true }
        return own
    }

    private fun condQuery(t: Target, c: Cond, day: DayOfWeek?): CondQuery {
        val noun = t.noun?.query
        // A kind of place may not say it, or say it of its building ("a café in a building of before 1900"): wanted.
        // On its own ("built before 1900") it is what is searched for.
        fun out(raw: Query, symbol: String, value: String, own: Boolean = false) =
            CondQuery(if (noun != null && !own) Query.Want(raw) else raw, raw, ChipKind.PROPERTY, symbol, value)

        fun compare(key: String, op: CompareOp?, v: Double, unit: String? = null): TagFilter =
            if (op == null) TagFilter.And(listOf(TagFilter.Compare(key, CompareOp.GE, v, unit), TagFilter.Compare(key, CompareOp.LE, v, unit)))
            else TagFilter.Compare(key, op, v, unit)
        return when (c) {
            is Cond.Built -> {
                val f = if (c.op == null) {
                    TagFilter.And(listOf(TagFilter.Compare(START_DATE, CompareOp.GE, c.from.toDouble()), TagFilter.Compare(START_DATE, CompareOp.LE, c.to.toDouble())))
                } else {
                    TagFilter.Compare(START_DATE, c.op, c.from.toDouble())
                }
                // "buildings from the last 10 years": a building says its year, a café in one may not
                out(Query.Tags(f), "ui-history", f.toText(), own = noun?.let { structure(it) } == true)
            }
            is Cond.Measure -> {
                val v = c.value
                when (c.dim) {
                    MeasureDim.TALL, MeasureDim.ELEVATION -> if (c.dim == MeasureDim.ELEVATION || noun != null && elevated(noun)) {
                        val f = compare("ele", c.op, v, "m")
                        out(Query.Tags(f), "ui-mountain", f.toText())
                    } else {
                        val f = TagFilter.Or(listOf(compare("height", c.op, v, "m"), compare("building:height", c.op, v, "m")))
                        out(Query.Tags(f), "ui-ruler", f.toText())
                    }
                    // chargers say their power: what does not is not fast
                    MeasureDim.POWER -> {
                        val f = TagFilter.Or(SOCKET_OUTPUTS.map { compare(it, c.op ?: CompareOp.GE, v, "kW") })
                        CondQuery(Query.Tags(f), Query.Tags(f), ChipKind.PROPERTY, "ui-bolt", f.toText())
                    }
                    MeasureDim.MONEY -> {
                        val cheap = c.op == CompareOp.LT || c.op == CompareOp.LE
                        val f = TagFilter.Or(listOfNotNull(compare("charge", c.op, v), compare("price", c.op, v), TagFilter.Equals("fee", "no").takeIf { cheap }))
                        out(Query.Tags(f), "ui-euro", f.toText())
                    }
                    MeasureDim.STARS -> if (noun != null && lodging(noun)) {
                        val f = if (c.op == null) TagFilter.And(listOf(TagFilter.Compare("stars", CompareOp.GE, v), TagFilter.Compare("stars", CompareOp.LT, v + 1))) else TagFilter.Compare("stars", c.op, v)
                        out(Query.Tags(f), "ui-star", f.toText())
                    } else {
                        // a restaurant's stars are its rating
                        val r = Query.Rated(c.op ?: CompareOp.GE, v)
                        CondQuery(Query.Want(r), r, ChipKind.PROPERTY, "ui-star", "rating${(c.op ?: CompareOp.GE).symbol}${fmt(v)}")
                    }
                    MeasureDim.FLOORS -> {
                        val f = compare("building:levels", c.op, v)
                        out(Query.Tags(f), "ui-stairs", f.toText())
                    }
                    MeasureDim.SEATS -> {
                        val f = TagFilter.Or(listOf(compare("seats", c.op, v), compare("capacity", c.op, v)))
                        out(Query.Tags(f), "ui-users", f.toText())
                    }
                    MeasureDim.SPACES -> {
                        val f = compare("capacity", c.op, v)
                        out(Query.Tags(f), "ui-users", f.toText())
                    }
                    MeasureDim.ROOMS -> {
                        val f = compare("rooms", c.op, v)
                        out(Query.Tags(f), "ui-bed", f.toText())
                    }
                    MeasureDim.RATING -> {
                        val r = Query.Rated(c.op ?: CompareOp.GE, v)
                        CondQuery(if (noun != null) Query.Want(r) else r, r, ChipKind.PROPERTY, "ui-star", "rating${(c.op ?: CompareOp.GE).symbol}${fmt(v)}")
                    }
                }
            }
            is Cond.Level -> {
                val f = if (c.level < 0) TagFilter.Compare("level", CompareOp.LT, 0.0) else TagFilter.Equals("level", c.level.toString())
                out(Query.Tags(f), "ui-stairs", f.toText())
            }
            is Cond.Colour -> {
                val q = Query.Coloured(c.family)
                // on its own, the places that say a colour are where to look
                val alone = Query.and(Query.Tags(TagFilter.Or(Colours.KEYS.map { TagFilter.Has(it) })), q)
                CondQuery(if (noun != null) Query.Want(q) else alone, q, ChipKind.PROPERTY, "ui-palette", c.family)
            }
            is Cond.Than -> {
                // a mountain higher than another stands higher above the sea
                val key = if (c.key == HEIGHT && noun != null && elevated(noun)) ELE else c.key
                val q = Query.Than(key, c.op, Query.Name(c.name))
                CondQuery(q, q, ChipKind.PROPERTY, if (key == START_DATE) "ui-history" else if (key == ELE) "ui-mountain" else "ui-ruler", "$key${c.op.symbol}${c.name}")
            }
            is Cond.Change -> {
                val q = Query.ChangesAt(c.kind, c.at, c.window, day)
                val word = if (c.kind == ChangeKind.CLOSES) "closes" else "opens"
                CondQuery(q, q, ChipKind.OPEN, if (c.kind == ChangeKind.CLOSES) "ui-sunset" else "ui-sunrise", "$word ${c.at}")
            }
        }
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** Some tags of the places a kind finds, for telling what sort of place it is. */
    private fun sample(q: Query): Map<String, String> = when (q) {
        is Query.Tags -> Lexicon.sampleOf(q.filter)
        is Query.Or -> q.parts.firstOrNull()?.let { sample(it) } ?: emptyMap()
        is Query.And -> q.parts.fold(emptyMap()) { acc, p -> acc + sample(p) }
        else -> emptyMap()
    }

    /** Peaks, volcanoes, saddles: their height is their elevation. */
    private fun elevated(q: Query): Boolean = sample(q)["natural"] in setOf("peak", "volcano", "saddle", "hill", "ridge", "mountain_range")

    /** Places to stay: their stars are a grade, not a rating. */
    /** True for what one needs by a place more than goes to for itself: parking, a cash machine, toilets, a charger. */
    private fun service(q: Query): Boolean = sample(q)["amenity"] in SERVICES

    private fun lodging(q: Query): Boolean = sample(q)["tourism"] in setOf("hotel", "hostel", "guest_house", "motel", "apartment", "chalet", "camp_site", "caravan_site", "alpine_hut")

    /** Kinds that stand tall: buildings, towers, churches, peaks, monuments. */
    private fun tall(q: Query): Boolean {
        val s = sample(q)
        return s.keys.any { it in TALL_KEYS } || s["amenity"] == "place_of_worship"
    }

    /**
     * The opening times a target's parts spell: a day and a time, a day, a time, or an open word; the weekend's
     * Saturday and Sunday. A clock that may be either half of the day is the evening's at a restaurant or a bar ("om
     * half acht"), and tonight at a club is late.
     */
    private fun openTimes(t: Target): List<OpenTime> {
        val o = t.open
        if (o.isEmpty) return emptyList()
        val kind = t.noun?.query
        var clock = o.clock
        if (clock != null && o.ambiguous && clock.hour in 1..11 && kind != null && evening(kind)) clock = clock.plusHours(12)
        if (o.weekend && o.day == null && o.dayOffset == null) return listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).map { OpenTime.OnDay(it, clock) }
        // "today" and "tomorrow" need a clock: the one given, else the phone's
        val day = o.day ?: o.dayOffset?.let { off -> (now ?: ZonedDateTime.now()).dayOfWeek.plus(off.toLong()) }
        val word = if (o.word == OpenTime.Tonight && kind != null && nightlife(kind)) OpenTime.Late else o.word
        return listOf(
            when {
                day != null -> OpenTime.OnDay(day, clock)
                clock != null -> OpenTime.Today(clock)
                word != null -> word
                else -> OpenTime.Now
            },
        )
    }

    /**
     * The unknown words after "in" that name an area, articles allowed between ("in de Jordaan"), and where they end.
     * A word read only as a kind of place with a typo, or as a cuisine, is a place's name there ("in America").
     */
    private fun areaName(items: List<Item>, from: Int): Pair<Said?, Int> {
        val parts = ArrayList<Said>()
        var j = from
        while (j < items.size) {
            val it = items[j]
            val article = it is Item.M && (it.meaning as? Meaning.Word)?.role.let { r -> r == Meaning.Role.FILLER || r == Meaning.Role.OF }
            // a preset's word written as a name ("op de Dam"), where nouns are not all capitalised
            val proper = it is Item.M && lang != "de" && (it.meaning as? Meaning.Noun)?.rank?.let { r -> r >= 4 } == true && it.raw.firstOrNull()?.isUpperCase() == true
            val named = it is Item.Unknown || proper || it is Item.M && (it.fuzzy || (it.meaning as? Meaning.Noun)?.cuisine == true)
            when {
                named -> parts += it.said
                article && items.getOrNull(j + 1).let { n -> n is Item.Unknown || n is Item.M && n.fuzzy } -> if (parts.isNotEmpty()) parts += it.said
                else -> break
            }
            j++
        }
        return (if (parts.isEmpty()) null else Said.of(parts)) to j
    }

    /** The words of a place's name from [from] ("Westerkerk", "Anne Frank House", "van Gogh Museum") and where they end. */
    private fun nameRun(items: List<Item>, from: Int): Pair<Said, Int> {
        val parts = ArrayList<Said>()
        // "near van Gogh": a name's own "van" or "de" just before it
        items.getOrNull(from - 1)?.takeIf { it.role() == Meaning.Role.FROM || it.role() == Meaning.Role.OF }?.let { p ->
            if (items.getOrNull(from - 2)?.role().let { it == Meaning.Role.FILLER || it == Meaning.Role.NEAR }) parts += p.said
        }
        var j = from
        while (j < items.size) {
            val it = items[j]
            val r = it.role()
            val inner = r == Meaning.Role.FILLER || r == Meaning.Role.OF || r == Meaning.Role.FROM || r == Meaning.Role.ON
            when {
                it is Item.Unknown || it is Item.M && it.fuzzy -> parts += it.said
                // "Paleis op de Dam": small words inside a name
                inner && parts.isNotEmpty() && items.getOrNull(j + 1).let { n ->
                    n is Item.Unknown || n?.role() == Meaning.Role.FILLER && items.getOrNull(j + 2) is Item.Unknown
                } -> parts += it.said
                // "Anne Frank House", "Amsterdam Centraal": the kind of place that ends it
                it is Item.M && it.meaning is Meaning.Noun && parts.isNotEmpty() -> { parts += it.said; j++; break }
                else -> break
            }
            j++
        }
        return Said.of(parts) to j
    }

    /** A yes-or-no question: "is the Rijksmuseum …", "does the HEMA have …", "heeft de Jumbo …", "le musée est-il …". */
    private fun isQuestion(texts: List<String>): Boolean {
        val first = texts.firstOrNull() ?: return false
        if (first in QUESTION_STARTS && texts.getOrNull(1) !in NOT_QUESTIONS) return true
        for (k in 0 until texts.size - 1) {
            val a = texts[k]
            val b = texts[k + 1]
            if (a == "est" && (b == "il" || b == "elle")) return true
            if (a == "sont" && (b == "ils" || b == "elles")) return true
            if (a == "t" && k > 0 && texts[k - 1] == "a" && (b == "il" || b == "elle")) return true
        }
        return false
    }

    /** "A2", "N201", "E19": the road of that number, its ref written either way ("A2", "A 2"). */
    private fun roadRef(m: MatchResult): TagFilter {
        val l = m.groupValues[1].uppercase(Locale.ROOT)
        val n = m.groupValues[2]
        return TagFilter.Matches("ref", "^($l$n|$l $n)$")
    }

    /** The tag that says the opposite of a property of one key: `key=no`, or `key=yes` for one that is `key=no`. */
    private fun opposite(f: TagFilter): TagFilter? {
        val k = f.keys().singleOrNull() ?: return null
        return if (f is TagFilter.Equals && f.value == "no") TagFilter.Equals(k, "yes") else TagFilter.Equals(k, "no")
    }

    /** Cafés, bars, restaurants: the places that have terraces, live music and happy hours. */
    private fun venue(t: Target): Boolean = t.noun?.query?.let { sample(it)["amenity"] in VENUE_AMENITIES } == true

    /** Restaurants, bars, cinemas, theatres: a clock that may be either half of the day is their evening's. */
    private fun evening(q: Query): Boolean = sample(q)["amenity"] in EVENING

    private fun nightlife(q: Query): Boolean = sample(q)["amenity"] == "nightclub"

    /** Parks, woods, gardens, beaches: where a bank is a bench. */
    private fun outdoors(q: Query): Boolean = sample(q).keys.any { it in OUTDOOR_KEYS }

    private fun Item.role(): Meaning.Role? = ((this as? Item.M)?.meaning as? Meaning.Word)?.role

    /** Whether a day or a time comes next, past small words ("closed on Monday"). */
    private fun List<Item>.timeFollows(i: Int): Boolean {
        val next = subList(i + 1, size).firstOrNull { it.role().let { r -> r != Meaning.Role.FILLER && r != Meaning.Role.ON && r != Meaning.Role.AT } }
        return next is Item.Time || next is Item.M && (next.meaning is Meaning.Day || next.meaning is Meaning.Open)
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

        /** Outdoor words that, before a town or a name, keep away from it. */
        val OUTSIDE_WORDS = setOf("outside", "buiten", "dehors", "hors", "aussen")

        /** What "per" a place counts by: its quarter or its town. */
        val PER_PLACES = mapOf(
            "neighbourhood" to "neighbourhood", "suburb" to "neighbourhood", "quarter" to "neighbourhood", "borough" to "neighbourhood",
            "city" to "town", "town" to "town", "village" to "town",
        )

        /**
         * The tag a comparison with another place compares ("taller than", "older than"); not a year's "before", whose
         * word after is more often a time than a place ("before the war").
         */
        val THAN_KEYS = mapOf(Meaning.Dim.TALL to HEIGHT, Meaning.Dim.ELEVATION to ELE, Meaning.Dim.AGE to START_DATE)

        /** "Next to", "naast", "neben", "à côté de": nearer than near. */
        const val NEXT_TO = 50.0
        val NEXT_TO_WORDS = setOf("next to", "right next to", "beside", "naast", "neben", "a cote de")

        /** The kinds a place is wanted by when named right after it ([service]). */
        val SERVICES = setOf("parking", "atm", "toilets", "charging_station", "bicycle_parking", "drinking_water", "bicycle_rental")

        /** "Opens in the morning": from 6 to 12; a time otherwise give or take half an hour. */
        const val MORNING_WINDOW = 180
        const val CHANGE_WINDOW = 30
        const val NOON_HOUR = 12
        const val AROUND_ME = 1_000.0
        const val WALK = 80.0

        /** "Before 8": open at a quarter to. "Closes at 19:30": within this many minutes of it; "around 19:30": within this many. */
        const val BEFORE_MINUTES = 15L
        const val AT_MINUTES = 15
        const val AROUND_MINUTES = 30

        /** "Newly built": in the last this many years. "Built around 1900": this many years either side. */
        const val NEW_YEARS = 5
        const val APPROX_YEARS = 10
        const val MIN_YEAR = 1000
        const val MAX_YEAR = 2100
        const val MEDIEVAL_FROM = 500
        const val MEDIEVAL_TO = 1500
        const val START_DATE = "start_date"
        const val HEIGHT = "height"
        const val ELE = "ele"

        val EAT: TagFilter = TagFilter.parse("amenity~^(restaurant|fast_food|cafe|food_court|ice_cream)$")

        /** The keys a charger says its power under. */
        val SOCKET_OUTPUTS = listOf(
            "socket:type2_combo:output", "socket:chademo:output", "socket:tesla_supercharger:output", "socket:tesla_supercharger_ccs:output",
            "socket:type1_combo:output", "socket:nacs:output", "socket:type2:output", "socket:type2_cable:output", "charging_station:output",
        )
        val TALL_KEYS = setOf("building", "man_made", "historic", "tower", "natural", "power", "aerialway", "bridge", "tourism")

        val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")
        val H = Regex("""^(\d{1,2})[hu](\d{2})?$""")
        val AMPM_WORD = Regex("""^(\d{1,2})(am|pm)$""")
        val DOTTED = Regex("""^(\d{1,2})\.(\d{2})$""")
        val JOINED = Regex("""^(\d+(?:[.,]\d+)?)(m|km|mi|ft|min|mins)$""")
        val JOINED_AMOUNT = Regex("""^(\d+(?:[.,]\d+)?)(kw|eur|euro|euros|usd|gbp|stars|star|sterren|sterne)$""")
        val NUMBER = Regex("""^\d+(?:[.,]\d+)?$""")
        val DECADE = Regex("""^(\d{3}0)s$""")
        val ORDINAL = Regex("""^(\d{1,2})(?:st|nd|rd|th|e|de|ste|er|re|eme|ieme)?$""")
        val ORDINAL_SUFFIXED = Regex("""^(\d{1,2})(?:st|nd|rd|th|e|de|ste|er|re|eme|ieme)$""")
        val AMPM = setOf("am", "pm")
        val HOUR_WORDS = setOf("uur", "uhr", "h", "u", "heures", "heure", "oclock", "am", "pm")
        val AT_WORDS = setOf("at", "om", "um", "a", "vers", "rond", "gegen")

        /** Words a bare hour may follow ("om 8", "before 7", "nach 22"). */
        val BARE_AT = setOf("at", "om", "um", "vers", "rond", "gegen", "tegen", "around", "omstreeks", "vanaf", "ab", "des", "after", "na", "nach", "apres", "before", "voor", "vor", "avant")

        /** The hours in words, in the four languages ("acht uur", "half eight", "huit heures"). */
        val HOUR_NUMBERS: Map<String, Int> = buildMap {
            for (words in listOf(
                "one two three four five six seven eight nine ten eleven twelve",
                "een twee drie vier vijf zes zeven acht negen tien elf twaalf",
                "eins zwei drei vier funf sechs sieben acht neun zehn elf zwolf",
                "une deux trois quatre cinq six sept huit neuf dix onze douze",
            )) for ((k, w) in words.split(' ').withIndex()) putIfAbsent(w, k + 1)
            put("ein", 1)
        }
        val EN_HOURS = "one two three four five six seven eight nine ten eleven twelve".split(' ').toSet()

        /** Spans of years named by what happened in them. */
        val ERAS: List<Pair<List<String>, Pair<Int, Int>>> = listOf(
            (1939 to 1945) to "world war 2,world war ii,ww2,wwii,second world war,the second world war,tweede wereldoorlog,zweiter weltkrieg,zweiten weltkrieg,zweiten weltkriegs,seconde guerre mondiale,deuxieme guerre mondiale",
            (1914 to 1918) to "world war 1,world war i,ww1,wwi,first world war,the first world war,eerste wereldoorlog,erster weltkrieg,ersten weltkrieg,ersten weltkriegs,premiere guerre mondiale,grande guerre",
            (1600 to 1699) to "golden age,dutch golden age,gouden eeuw,goldenes zeitalter,goldenen zeitalter,goldenen zeitalters,siecle d or",
            (1919 to 1938) to "interbellum,interwar,between the wars,zwischenkriegszeit,entre deux guerres,l entre deux guerres",
            (1945 to 1965) to "post war,postwar,naoorlogs,naoorlogse,wederopbouw,nachkriegszeit,nachkriegs,d apres guerre,apres guerre",
        ).flatMap { (span, phrases) -> phrases.split(',').map { it.split(' ') to span } }.sortedByDescending { it.first.size }

        /** The first words of a yes-or-no question, and the second words that make it a search ("is there a …"). */
        val QUESTION_STARTS = setOf("is", "does", "do", "are", "was", "has", "heeft", "hebben", "zijn", "ist", "hat", "haben", "sind", "war")
        val NOT_QUESTIONS = setOf("there", "er", "es", "y", "you", "anyone", "jij", "je", "u", "du", "ihr", "sie", "man", "jemand", "iemand")
        val DEFINITE = setOf("the", "de", "het", "der", "die", "das", "dem", "den", "le", "la", "l", "les")

        /** "Spots", "plekken": what "how many" counts is a place's capacity. */
        val SPACE_WORDS = setOf("spots", "spaces", "plekken", "parkeerplekken", "parkeerplaatsen", "stellplatze", "emplacements")
        const val CAPACITY = "capacity"
        const val POPULATION = "population"

        /** The end of German "macht … auf" and Dutch "gaat … open". */
        val OPEN_PARTICLES = setOf("auf", "open", "op")
        val ROAD_REF = Regex("^([aenbrs])([0-9]{1,3})$")

        /** What a recycling point takes, by the kind or goods that name it ("recycle batteries", "kleding inleveren"). */
        const val RECYCLING = "recycling"
        val RECYCLES = mapOf(
            "goods:batteries" to "batteries", "goods:bulbs" to "light_bulbs", "goods:phone" to "mobile_phones", "goods:computer" to "computers",
            "goods:paint" to "paint", "goods:cables" to "electrical_items", "goods:stationery" to "paper", "goods:glasses" to "glasses",
            "clothes" to "clothes", "second_hand_shop" to "clothes",
        )

        /** Nationalities as their countries' codes ("the American embassy": country=US). */
        const val EMBASSY = "embassy"
        val NATIONS = mapOf(
            "american" to "US", "british" to "GB", "english" to "GB", "french" to "FR", "german" to "DE", "italian" to "IT",
            "spanish" to "ES", "dutch" to "NL", "belgian" to "BE", "chinese" to "CN", "japanese" to "JP", "turkish" to "TR",
            "indian" to "IN", "mexican" to "MX", "greek" to "GR", "portuguese" to "PT", "polish" to "PL", "russian" to "RU",
            "thai" to "TH", "vietnamese" to "VN", "korean" to "KR", "moroccan" to "MA", "indonesian" to "ID", "surinamese" to "SR",
            "brazilian" to "BR", "argentinian" to "AR", "canadian" to "CA", "australian" to "AU", "swiss" to "CH", "austrian" to "AT",
            "swedish" to "SE", "norwegian" to "NO", "danish" to "DK", "finnish" to "FI", "irish" to "IE", "israeli" to "IL",
            "egyptian" to "EG", "ethiopian" to "ET", "lebanese" to "LB", "persian" to "IR", "iranian" to "IR", "pakistani" to "PK",
            "syrian" to "SY", "ukrainian" to "UA", "hungarian" to "HU", "czech" to "CZ", "peruvian" to "PE", "colombian" to "CO",
        )

        /** Properties only places to eat and drink have, and those places. */
        val VENUE_KEYS = setOf(
            "outdoor_seating", "outdoor_seating:heated", "smoking", "live_music", "happy_hours", "beer_garden", "rooftop", "real_fire",
            "breakfast", "takeaway", "delivery", "reservation", "highchair", "diet:vegan", "diet:vegetarian",
        )
        val VENUE_AMENITIES = setOf("cafe", "bar", "pub", "biergarten", "restaurant", "fast_food", "nightclub", "ice_cream", "food_court")
        val VENUE_ATTRS = setOf("terrace", "garden", "rooftop", "heated", "smoking", "non_smoking", "live_music")
        val VENUE = Meaning.Noun(Query.Tags(TagFilter.parse("amenity~^(cafe|bar|pub|biergarten|restaurant)$")), "maki-cafe", "venue", rank = 0)
        const val DRIVE_THROUGH_ATTR = "drive_through"

        /** "with cheap parking": what is cheap is better free. */
        val CHEAP = Meaning.Attribute(TagFilter.Equals("fee", "no"), "ui-coins", "cheap", soft = true)
        val DRIVE_THROUGH = Meaning.Noun(Query.Tags(TagFilter.parse("amenity~^(fast_food|cafe|restaurant)$")), "maki-fast-food", "drive_through_food", rank = 0)

        /** Kinds of place that have a build year of their own, not a business in a building. */
        val STRUCTURE_KEYS = setOf("building", "man_made", "historic", "military", "bridge", "tower:type")

        /** "the last 10 years", "afgelopen 10 jaar", "ces 10 dernières années". */
        val RECENT = setOf("last", "past", "afgelopen", "laatste", "letzten", "letzte", "vergangenen", "vergangene", "derniers", "dernieres", "dernier", "derniere")
        val EVENING = setOf("restaurant", "bar", "pub", "biergarten", "nightclub", "cinema", "theatre", "music_venue", "casino", "events_venue", "concert_hall")
        val OUTDOOR_KEYS = setOf("leisure", "landuse", "natural", "boundary")
        val BANK_WORDS = setOf("bank", "banken", "banke")

        /**
         * What a preference keeps away from, and by how much: quiet from busy roads, off the tourist track from the
         * sights tourists come for, uncrowded from what brings people in (stops, car parks, sights).
         */
        val AWAY: Map<String, Pair<TagFilter, Double>> = mapOf(
            "quiet" to (TagFilter.parse("highway~^(motorway|trunk|primary)$") to 100.0),
            "untouristy" to (TagFilter.parse("tourism~^(attraction|museum|gallery|viewpoint|theme_park|zoo|aquarium)$ or historic~^(castle|palace|monument)$") to 300.0),
            "uncrowded" to (TagFilter.parse("amenity=parking or public_transport=station or railway~^(station|halt)$ or tourism=attraction") to 400.0),
        )
        val MIDNIGHT = setOf("midnight", "middernacht", "mitternacht", "minuit")
        val NOON = setOf("noon", "midday")
        val MINUTES = setOf("min", "mins", "minute", "minutes", "minuten", "minuut", "minuto")
        val CENTURY = setOf("century", "centuries", "eeuw", "eeuwse", "jahrhundert", "jahrhunderts", "jh", "siecle", "siecles")
        val MEDIEVAL = setOf("medieval", "mediaeval", "middeleeuws", "middeleeuwse", "mittelalterlich", "mittelalterliche", "mittelalterlichen", "medievale", "medievales", "medievaux")
        val OLD = setOf("old", "oud", "oude", "alt", "alte", "alter", "altes")
        val CURRENCY = setOf("€", "$", "£", "eur", "usd", "gbp")
        val UNITS: Map<String, AmountUnit> = buildMap {
            for (w in "kw kilowatt kilowatts".split(' ')) put(w, AmountUnit.KW)
            for (w in "€ $ £ eur euro euros usd dollar dollars gbp pound pounds".split(' ')) put(w, AmountUnit.MONEY)
            for (w in "star stars * ster sterren stern sterne sternen etoile etoiles".split(' ')) put(w, AmountUnit.STARS)
            for (w in "year years yr yrs jaar jaren jahr jahre jahren ans annee annees".split(' ')) put(w, AmountUnit.YEARS)
            for (w in "floors storeys stories levels verdiepingen etages stockwerke stockwerken geschosse geschossen".split(' ')) put(w, AmountUnit.FLOORS)
            for (w in "seat seats people persons personen personnes pers guests gasten gaste zitplaatsen sitzplatze couverts".split(' ')) put(w, AmountUnit.SEATS)
            for (w in "spaces spots plekken parkeerplekken stellplatze".split(' ')) put(w, AmountUnit.SPACES)
            for (w in "rooms room kamers kamer zimmer chambres chambre".split(' ')) put(w, AmountUnit.ROOMS)
        }
        val FLOOR = setOf("floor", "storey", "story", "level", "verdieping", "etage", "stock", "stockwerk", "og", "obergeschoss", "geschoss")
        val FLOOR_AFTER_NUMBER = setOf("stock", "og", "obergeschoss", "etage", "stockwerk")
        val ORDINAL_WORDS = mapOf(
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
            "eerste" to 1, "tweede" to 2, "derde" to 3, "vierde" to 4, "vijfde" to 5,
            "erste" to 1, "ersten" to 1, "zweite" to 2, "zweiten" to 2, "dritte" to 3, "dritten" to 3, "vierte" to 4, "vierten" to 4,
            "premier" to 1, "premiere" to 1, "deuxieme" to 2, "seconde" to 2, "troisieme" to 3, "quatrieme" to 4,
        )
        val NAMED_LEVELS: List<Pair<List<String>, Int>> = listOf(
            "ground floor" to 0, "ground level" to 0, "begane grond" to 0, "gelijkvloers" to 0, "erdgeschoss" to 0, "parterre" to 0,
            "rez de chaussee" to 0, "basement" to -1, "kelder" to -1, "souterrain" to -1, "keller" to -1, "untergeschoss" to -1, "sous sol" to -1,
        ).map { (p, l) -> p.split(' ') to l }.sortedByDescending { it.first.size }
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
        val SORT_SYMBOLS = mapOf(
            SortBy.DISTANCE to "ui-navigation", SortBy.RATING to "ui-star", SortBy.OPEN_LONGEST to "ui-clock", SortBy.RELEVANCE to "ui-sparkles",
            SortBy.OLDEST to "ui-history", SortBy.NEWEST to "ui-sparkles", SortBy.TALLEST to "ui-ruler", SortBy.HIGHEST to "ui-mountain",
            SortBy.CHEAPEST to "ui-coins", SortBy.LARGEST to "ui-cube", SortBy.MOST_HOURS to "ui-hourglass", SortBy.OPENS_EARLIEST to "ui-sunrise",
            SortBy.CLOSES_LATEST to "ui-moon", SortBy.OPENS_NEXT to "ui-timer", SortBy.SMALLEST to "ui-cube", SortBy.LONGEST to "ui-ruler",
            SortBy.POWER to "ui-bolt", SortBy.STARS to "ui-star", SortBy.OPENS_LATEST to "ui-sun",
        )
    }
}
