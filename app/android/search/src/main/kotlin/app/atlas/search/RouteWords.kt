package app.atlas.search

import app.atlas.model.RoutePrefs

/**
 * A way asked for: from [from] (null: where the searcher is) to [to], by [mode] (a `Mode` name) when one was said. A
 * [fromWord] that may be part of the start's name ("van Gogh Museum naar het Centraal": "van") is kept apart, for the
 * start to be looked up with it as well as without. [along]: the query's places are wanted on the way ("coffee on my
 * way to work"), not the way itself. [prefs]: how the way should go, when said ("well lit route home", "zonder snelweg").
 * [via]: the stops on the way, in order ("to Utrecht via Amersfoort", "Prinsengracht 263, 500, 1012").
 */
data class RouteAsk(
    val from: String?,
    val to: String,
    val mode: String? = null,
    val fromWord: String? = null,
    val along: Boolean = false,
    val prefs: RoutePrefs? = null,
    val via: List<String> = emptyList(),
)

/**
 * Reads ways asked for in English, Dutch, German and French: "from A to B", "van A naar B", "von A nach B", "de A à
 * B"; "route to B", "directions to B", "hoe kom ik bij B", "wie komme ich zum B", "itinéraire vers B"; a travel mode
 * anywhere ("by bike", "met de fiets", "zu Fuß", "en voiture") and at the start ("walk to B", "fietsen naar B"); stops
 * on the way ("via B", "über B", "par B"); the bare "A to B" ([Found.explicit] false), which is a way only when neither
 * side reads as a query of its own; a place and how the way should keep clear ("Los Angeles no highways",
 * [Found.implied]); and addresses one after the other ("Prinsengracht 263, 500, 1012"), called at in turn ([stops]).
 */
internal object RouteWords {
    /**
     * [along]: the words of what is wanted on the way ("coffee" of "coffee on my way to work"). [implied]: only how the
     * way should go said it is a way, a way only when its end is no query of its own. [streets]: the streets of a list
     * of addresses, which must not be kinds of place ("bus 26, 125").
     */
    data class Found(
        val from: String?,
        val to: String,
        val mode: String?,
        val fromWord: String?,
        val explicit: Boolean,
        val along: String? = null,
        val prefs: RoutePrefs? = null,
        val via: List<String> = emptyList(),
        val implied: Boolean = false,
        val streets: List<String> = emptyList(),
    )

    /**
     * Addresses to call at one after the other, a bare number on the street said before it ("Prinsengracht 263, 500,
     * 1012", "Damrak 1, Kalverstraat 92", "26 Manhattan Street, 125"): the last is the end, the others the stops.
     */
    fun stops(text: String): Found? {
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        var street: String? = null
        var numberFirst = false
        val streets = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (p in parts) {
            val after = ADDRESS.matchEntire(p)
            // the number first only before a street's word: "1012 AB Amsterdam" is a postcode and a town
            val before = ADDRESS_US.matchEntire(p)?.takeIf { streetLike(it.groupValues[2]) }
            when {
                after != null -> { street = after.groupValues[1].trim(); numberFirst = false; out += p }
                before != null -> { street = before.groupValues[2].trim(); numberFirst = true; out += p }
                street != null && HOUSE.matches(p) -> out += if (numberFirst) "$p $street" else "$street $p"
                else -> return null
            }
            streets += street!!
        }
        return Found(null, out.last(), null, null, explicit = true, via = out.dropLast(1), streets = streets.toList())
    }

    /** Whether [name] says it is a street: a street's word in it ("Manhattan Street", "rue de Rivoli") or at its end ("Prinsengracht"). */
    fun streetLike(name: String): Boolean {
        val words = Words.split(name).map { it.text }
        return words.any { it in STREET_WORDS } || words.lastOrNull()?.let { w -> STREET_ENDS.any { w.length > it.length + 2 && w.endsWith(it) } } == true
    }

    fun read(text: String): Found? {
        var words = Words.split(text)
        // "well lit route home", "rustige fietsroute naar Zandvoort", "… zonder snelweg": how, said apart from where
        var prefs: RoutePrefs? = null
        for ((phrase, apply) in PREFS) {
            val at = find(words, phrase) ?: continue
            if (words.getOrNull(at + phrase.size)?.text !in WAY_WORDS) continue
            prefs = apply(prefs ?: RoutePrefs())
            words = words.subList(0, at) + words.subList(at + phrase.size, words.size)
        }
        var keepsClear = false
        for ((phrase, apply) in AVOIDS) {
            val at = find(words, phrase) ?: continue
            prefs = apply(prefs ?: RoutePrefs())
            words = words.subList(0, at) + words.subList(at + phrase.size, words.size)
            // stairs and steps are a place's too ("toilets without steps"); tolls and motorways only a way's
            if (phrase.none { it in PLACE_AVOIDS }) keepsClear = true
        }
        val found = read(text, words) ?: implied(text, words, prefs).takeIf { keepsClear } ?: return null
        val (to, beyond) = vias(found.to)
        val (from, before) = found.from?.let { vias(it) } ?: (null to emptyList())
        return found.copy(from = from, to = to, via = found.via + before + beyond, prefs = prefs ?: found.prefs)
    }

    /** "Los Angeles no highways", "naar Utrecht zonder tol": a place and how the way there keeps clear; the end is the place. */
    private fun implied(text: String, words: List<Word>, prefs: RoutePrefs?): Found? {
        val rest = if (words.firstOrNull()?.text?.let { listOf(it) } in BARE_TO) words.drop(1) else words
        if (rest.isEmpty() || rest.all { it.isNumber }) return null
        val to = rest.joinToString(" ") { text.substring(it.start, it.end) }.trim().takeIf { it.isNotEmpty() } ?: return null
        return Found(null, to, null, null, explicit = true, prefs = prefs, implied = true)
    }

    /** An end and the stops said after it ("Utrecht via Amersfoort en Hilversum"), in the order they come. */
    private fun vias(s: String): Pair<String, List<String>> {
        val parts = VIA.split(s).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return s to emptyList()
        return parts[0] to parts.drop(1).flatMap { p -> AND.split(p).map { it.trim() }.filter { it.isNotEmpty() } }
    }

    private fun read(text: String, all: List<Word>): Found? {
        var words = all
        if (words.size < 2) return null
        var mode: String? = null
        var verb = false
        // a mode said anywhere ("… by bike"), or at the start as a verb ("walk to …"; not "bike to rent")
        for ((phrase, m) in VERBS) {
            if (words.size <= phrase.size || (phrase.indices).any { words[it].text != phrase[it] }) continue
            mode = m
            verb = true
            words = words.subList(phrase.size, words.size)
            break
        }
        if (mode == null) for ((phrase, m) in MODES) {
            val at = find(words, phrase) ?: continue
            mode = m
            words = words.subList(0, at) + words.subList(at + phrase.size, words.size)
            break
        }
        if (words.isEmpty()) return null
        // an end is a place, not a number ("open from 9 to 5")
        fun raw(from: Int, to: Int): String? = if (from >= to || (from until to).all { words[it].isNumber }) null
        else text.substring(words[from].start, words[to - 1].end).trim().takeIf { it.isNotEmpty() }
        val texts = words.map { it.text }

        // "the way home", "take me home"
        for (p in HOME) if (texts.size >= p.size && texts.subList(texts.size - p.size, texts.size) == p) {
            if (texts.size == p.size || texts.subList(0, texts.size - p.size).none { it in FROM }) return Found(null, HOME_WORD, mode, null, explicit = true)
        }
        // "coffee on my way to work", "supermarket between home and work": what to find on a way
        for (p in ALONG) {
            val at = find(words, p) ?: continue
            if (at == 0) continue
            val subject = raw(0, at) ?: continue
            val to = raw(at + p.size, words.size) ?: continue
            return Found(null, to, mode, null, explicit = true, along = subject)
        }
        for ((p, and) in BETWEEN) {
            val at = find(words, p) ?: continue
            if (at == 0) continue
            val mid = (at + p.size + 1 until words.size - 1).firstOrNull { texts[it] == and } ?: continue
            val from = raw(at + p.size, mid) ?: continue
            val to = raw(mid + 1, words.size) ?: continue
            // "open between noon and two" is a time
            if ((at + p.size until words.size).any { texts[it] in TIME_WORDS }) continue
            return Found(from, to, mode, null, explicit = true, along = raw(0, at))
        }
        // "fietsroute naar Zandvoort": a way by the mode the word names
        for ((lead, m) in MODE_LEADS) {
            if (texts.size <= lead.size || texts.subList(0, lead.size) != lead) continue
            val to = raw(lead.size, words.size) ?: continue
            return Found(null, to, mode ?: m, null, explicit = true)
        }
        // "from A to B"
        if (texts[0] in FROM) {
            for (j in 2 until texts.size) {
                val toLen = TO.firstNotNullOfOrNull { p -> p.size.takeIf { j + it <= texts.size && texts.subList(j, j + it) == p } } ?: continue
                val from = raw(1, j) ?: continue
                val to = raw(j + toLen, words.size) ?: continue
                return Found(from, to, mode, words[0].raw, explicit = true)
            }
        }
        // "route to B", "how do I get to B" (and "… from A" after it)
        for (lead in LEADS) {
            if (texts.size <= lead.size || texts.subList(0, lead.size) != lead) continue
            val rest = lead.size
            val fromAt = (rest + 1 until texts.size).firstOrNull { texts[it] in FROM_AFTER }
            val to = raw(rest, fromAt ?: words.size) ?: return null
            val from = fromAt?.let { raw(it + 1, words.size) }
            return Found(from, to, mode, fromAt?.let { words[it].raw }, explicit = true)
        }
        // "naar Utrecht", "nach Köln über Bonn": the word for "to" alone leads, not before a time ("nach 22 Uhr", "vers 20h")
        if (texts.size >= 2 && texts[0] in BARE_LEADS && !words[1].text.first().isDigit() && texts[1] !in TIME_WORDS) {
            val to = raw(1, words.size) ?: return null
            return Found(null, to, mode, null, explicit = true)
        }
        // "walk to B": the mode said as a verb leads
        if (verb && texts.size >= 2 && listOf(texts[0]) in TO) {
            val to = raw(1, words.size) ?: return null
            return Found(null, to, mode, null, explicit = true)
        }
        // "A to B": a way only if neither side reads as a query (the parser decides)
        val cuts = texts.indices.filter { i -> i in 1 until texts.size - 1 && listOf(texts[i]) in BARE_TO }
        if (cuts.size == 1) {
            val c = cuts.single()
            val from = raw(0, c) ?: return null
            val to = raw(c + 1, words.size) ?: return null
            return Found(from, to, mode, null, explicit = false)
        }
        return null
    }

    private fun find(words: List<Word>, phrase: List<String>): Int? {
        for (i in 0..words.size - phrase.size) if ((phrase.indices).all { words[i + it].text == phrase[it] }) return i
        return null
    }

    private fun phrases(s: String): List<List<String>> = s.split(',').map { it.trim().split(' ') }.filter { it.isNotEmpty() }

    /** How a way should go, said just before the word for it ("well lit route", "rustige fietsroute", "flache Strecke"). */
    private val PREFS: List<Pair<List<String>, (RoutePrefs) -> RoutePrefs>> = buildList {
        fun p(s: String, f: (RoutePrefs) -> RoutePrefs) = phrases(s).forEach { add(it to f) }
        p("well lit,best lit,lit,goed verlichte,goed verlicht,verlichte,verlicht,gut beleuchtete,gut beleuchteter,gut beleuchtet,beleuchtete,beleuchteter,bien eclaire,bien eclairee,eclaire,eclairee") { it.copy(lit = 1.0) }
        p("quiet,quietest,rustige,rustig,ruhige,ruhiger,ruhig,calme,tranquille") { it.copy(quiet = 1.0) }
        p("green,greenest,groene,groen,grune,gruner,grun,vert,verte") { it.copy(green = 1.0) }
        p("flat,flattest,vlakke,vlak,flache,flacher,flach,plat,plate") { it.copy(flat = 1.0) }
        p("shady,shaded,schaduwrijke,schaduwrijk,schattige,schattiger,schattig,ombrage,ombragee") { it.copy(shade = 1.0) }
        p("fastest,quickest,snelste,schnellste,plus rapide") { it.copy(fast = 1.0) }
    }.sortedByDescending { it.first.size }

    /** The words for a way that a preference stands before. */
    private val WAY_WORDS = setOf(
        "route", "routes", "way", "path", "walk", "ride", "drive", "weg", "pad", "fietsroute", "wandelroute", "looproute", "radroute",
        "strecke", "chemin", "itineraire", "trajet", "parcours",
    )

    /** What a way should keep clear of, said anywhere ("route to Utrecht avoiding motorways", "zonder tol"). */
    private val AVOIDS: List<Pair<List<String>, (RoutePrefs) -> RoutePrefs>> = buildList {
        fun p(s: String, f: (RoutePrefs) -> RoutePrefs) = phrases(s).forEach { add(it to f) }
        p("avoiding tolls,avoid tolls,no tolls,without tolls,toll free,zonder tol,geen tol,ohne maut,mautfrei,sans peage,sans peages") { it.copy(avoidTolls = true) }
        p("avoiding motorways,avoid motorways,no motorways,without motorways,avoiding highways,avoid highways,no highways,without highways,zonder snelweg,zonder snelwegen,geen snelweg,geen snelwegen,ohne autobahn,keine autobahn,sans autoroute,sans autoroutes") { it.copy(avoidMotorways = true) }
        p("avoiding ferries,avoid ferries,no ferries,no ferry,without ferries,zonder pont,geen pont,zonder veerpont,ohne fahre,sans bac,sans ferry") { it.copy(avoidFerries = true) }
        p("avoiding stairs,avoid stairs,no stairs,no steps,without stairs,without steps,zonder trappen,geen trappen,ohne treppen,keine treppen,sans escaliers,sans marches") { it.copy(avoidSteps = true) }
        p("avoiding unpaved,avoid unpaved,no unpaved,no gravel,paved only,zonder onverhard,geen onverhard,ohne schotter,kein schotter,sans gravier,sans chemins de terre") { it.copy(avoidUnpaved = true) }
    }.sortedByDescending { it.first.size }

    /** The avoids a place may have as well as a way ("toilets without steps"): alone, they make no way. */
    private val PLACE_AVOIDS = setOf("stairs", "steps", "trappen", "treppen", "escaliers", "marches", "unpaved", "gravel", "onverhard", "schotter", "gravier")

    /** The words before the stops of a way ("via", "über", "en passant par"), and between them ("and", ","). */
    private val VIA = Regex("""\s+(?:via|over|über|uber|en passant par|par)\s+""", RegexOption.IGNORE_CASE)
    private val AND = Regex("""\s*,\s*|\s+(?:and|en|und|et)\s+""", RegexOption.IGNORE_CASE)

    /** An address: a street and its number ("Prinsengracht 263"), or the number first ("26 Manhattan Street"). */
    private val ADDRESS = Regex("""^(\p{L}[\p{L}\p{M}'’.\- ]*?)\s+(\d{1,5}[a-zA-Z]?(?:[-/]\d{1,4})?)$""")
    private val ADDRESS_US = Regex("""^(\d{1,5}[a-zA-Z]?)\s+(\p{L}[\p{L}\p{M}'’.\- ]*)$""")
    private val HOUSE = Regex("""^\d{1,5}[a-zA-Z]?(?:[-/]\d{1,4})?$""")

    /** The words that make a name a street's, and the endings of a street's compound name. */
    private val STREET_WORDS = setOf(
        "street", "st", "avenue", "ave", "road", "rd", "lane", "ln", "boulevard", "blvd", "drive", "dr", "way", "place", "pl", "court",
        "ct", "square", "sq", "terrace", "crescent", "close", "row", "parkway", "highway", "straat", "laan", "weg", "plein", "kade",
        "gracht", "singel", "dijk", "steeg", "pad", "markt", "strasse", "str", "gasse", "platz", "allee", "ring", "damm", "ufer", "rue",
        "quai", "chemin", "impasse", "cours", "via", "calle", "plaza",
    )
    private val STREET_ENDS = listOf(
        "straat", "gracht", "laan", "weg", "plein", "kade", "singel", "dijk", "steeg", "markt", "burgwal", "dreef", "baan", "hof",
        "strasse", "gasse", "platz", "allee", "damm", "ufer", "ring",
    )

    /** Where a way home ends: the place the router knows as home. */
    private const val HOME_WORD = "home"

    /** A way home said whole, at the end ("well lit route home"). */
    private val HOME = phrases(
        "route home,the route home,way home,the way home,directions home,take me home,bring me home,get me home,go home," +
            "route naar huis,de weg naar huis,weg naar huis,naar huis,breng me naar huis,breng me thuis," +
            "nach hause,weg nach hause,der weg nach hause,heimweg,bring mich nach hause," +
            "rentrer a la maison,rentrer chez moi,ramene moi a la maison,ramene moi chez moi,le chemin de la maison",
    ).sortedByDescending { it.size }

    /** The words between what is wanted and the end of a way it is wanted on. */
    private val ALONG = phrases(
        "on my way to,on the way to,on our way to,along the way to,en route to,on my route to," +
            "op weg naar,op mijn weg naar,onderweg naar,op de weg naar," +
            "auf dem weg zu,auf dem weg zum,auf dem weg zur,auf dem weg nach,auf meinem weg zu,auf meinem weg nach,unterwegs nach,unterwegs zu," +
            "sur le chemin de,sur le chemin du,sur le chemin vers,sur la route de,sur la route du,en allant a,en allant au",
    ).sortedByDescending { it.size }

    /** "Between A and B": the word before A, and the one between them. */
    private val BETWEEN = listOf(listOf("between") to "and", listOf("tussen") to "en", listOf("zwischen") to "und", listOf("entre") to "et")

    /** Words that make "between … and …" a time, not two places. */
    private val TIME_WORDS = setOf("noon", "midday", "midnight", "midi", "minuit", "middag", "middernacht", "mittag", "mitternacht", "deux", "two", "twee", "zwei")

    /** Leads that name the travel mode themselves ("fietsroute naar …", "walking route to …"). */
    private val MODE_LEADS: List<Pair<List<String>, String>> = buildList {
        fun m(mode: String, s: String) = phrases(s).forEach { add(it to mode) }
        m("BIKE", "bike route to,cycling route to,cycle route to,fietsroute naar,fietsroute,fietsen naar,radroute nach,radroute zu,radweg nach,itineraire velo vers,itineraire a velo vers")
        m("WALK", "walking route to,walk route to,wandelroute naar,looproute naar,fussweg nach,fussweg zu,itineraire a pied vers")
        m("CAR", "driving route to,autoroute naar,route met de auto naar")
    }.sortedByDescending { it.first.size }

    private val FROM = setOf("from", "van", "vanaf", "von", "vom", "ab", "de", "du", "des", "depuis")

    /** The start said after the end ("route to B from A"): only words that are not also parts of names ("van Gogh"). */
    private val FROM_AFTER = setOf("from", "vanaf", "ab", "depuis")

    /** The words between the start and the end, longest first. */
    private val TO = phrases("to,naar,tot,nach,zu,zum,zur,bis,bis zum,bis zur,a,au,aux,vers,jusqu a,jusqu au,jusque").sortedByDescending { it.size }

    /** The words for "to" that lead a way alone ("naar Utrecht"); not "to", which leads "to go" and "to do". */
    private val BARE_LEADS = setOf("naar", "nach", "vers")

    /** The words of a bare "A to B": not "a", "zu", "tot", which are too often other things. */
    private val BARE_TO = phrases("to,naar,nach,vers")

    private val LEADS = phrases(
        "route to,directions to,direction to,navigate to,navigation to,take me to,get me to,how do i get to,how to get to,how can i get to,way to,the way to,go to," +
            "route naar,navigeer naar,navigatie naar,breng me naar,hoe kom ik bij,hoe kom ik naar,hoe kom ik in,de weg naar,weg naar,ga naar," +
            "route nach,route zu,route zum,route zur,weg nach,weg zu,weg zum,weg zur,der weg zum,der weg zur,navigiere zu,navigiere zum,navigiere zur,navigiere nach,navigation nach,navigation zu," +
            "wie komme ich zu,wie komme ich zum,wie komme ich zur,wie komme ich nach,bring mich zu,bring mich zum,bring mich zur,bring mich nach," +
            "itineraire vers,itineraire pour,itineraire jusqu a,itineraire a,aller a,aller au,aller aux,comment aller a,comment aller au,comment aller aux,emmene moi a,emmene moi au,chemin vers,le chemin de,naviguer vers",
    ).sortedByDescending { it.size }

    /** Travel modes said in words anywhere ("… by bike"), the longest phrases first, as `Mode` names. */
    private val MODES: List<Pair<List<String>, String>> = buildList {
        fun m(mode: String, s: String) = phrases(s).forEach { add(it to mode) }
        m("WALK", "on foot,by foot,walking,te voet,lopend,zu fuss,a pied")
        m("BIKE", "by bike,by bicycle,cycling,met de fiets,op de fiets,fietsend,mit dem rad,mit dem fahrrad,a velo,en velo")
        m("CAR", "by car,driving,met de auto,mit dem auto,en voiture,en auto")
        m("TRANSIT", "by public transport,public transport,by train,by bus,by tram,by metro,met het ov,met de trein,met de bus,met de tram,mit der bahn,mit dem zug,mit dem bus,mit den offentlichen,en transports en commun,en train,en bus,en tram,en metro")
        m("WHEELCHAIR", "by wheelchair,in a wheelchair,met de rolstoel,mit dem rollstuhl,en fauteuil roulant")
    }.sortedByDescending { it.first.size }

    /** Travel modes said as the verb that leads a way ("walk to the station", "fietsen naar huis"). */
    private val VERBS: List<Pair<List<String>, String>> = buildList {
        fun m(mode: String, s: String) = phrases(s).forEach { add(it to mode) }
        m("WALK", "walk,walking,lopen,wandelen,laufen,gehen,marcher")
        m("BIKE", "cycle,cycling,ride,fietsen,radeln,rad fahren,radfahren,pedaler")
        m("CAR", "drive,driving,rijden,autorijden,fahren,auto fahren,conduire,rouler")
    }.sortedByDescending { it.first.size }
}
