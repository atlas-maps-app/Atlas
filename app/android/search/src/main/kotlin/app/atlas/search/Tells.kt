package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.places.Cuisine
import app.atlas.places.FactState
import app.atlas.places.Facts
import app.atlas.places.Fields
import app.atlas.places.Freshness
import app.atlas.places.Symbols
import app.atlas.providers.BathingSite
import app.atlas.providers.SwimAdvisory
import app.atlas.providers.WaterQuality
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * What a result's row can say of a place besides how long it takes to reach and whether it is open, each read from the
 * place's own tags (the user, 3 November: "Places results always show whats useful and omit whats not and what you
 * don't need at a glance immediately but when you open the place"). Which of them a row says is the kind's
 * ([Purpose.tells]): a charger its power, a toilet whether it is free, a restaurant its cuisine.
 */
enum class TellKey(val symbol: String) {
    CUISINE("ui-utensils"),
    DIET("ui-vegan"),
    OUTDOOR("ui-umbrella"),
    TAKEAWAY("ui-package"),
    WIFI("ui-wifi"),
    FEE("ui-coins"),
    POWER("ui-plug-zap"),
    SOCKETS("ui-plug"),
    CAPACITY("ui-parking"),
    COVERED("ui-umbrella"),
    WHEELCHAIR("ui-wheelchair"),
    CHANGING("ui-baby"),
    STARS("ui-star"),
    RATING("ui-star"),
    SWIM("ui-waves"),
    DRINKABLE("ui-droplet"),
    BOTTLE("ui-bottle"),
    ROUTES("ui-bus"),
    ELEVATION("ui-mountain"),
    OPERATOR("ui-tag"),
    DISPENSING("ui-pill"),
    CASH_IN("ui-cash"),
    RELIGION("ui-church"),
    SPORT("ui-dumbbell"),
    LIT("ui-lightbulb"),
    INDOOR("ui-door"),
    ORGANIC("ui-sprout"),
    SECOND_HAND("ui-recycle"),
    SELF_SERVICE("ui-hand"),
    FUEL("ui-fuel"),
    LEVEL("ui-stairs"),
    BACKREST("ui-sofa"),
    DOG("ui-dog"),
    TOILETS("ui-toilet"),
    COLLECTION("ui-mailbox"),
    RECYCLES("ui-recycle"),
    VENDS("ui-coins-hand"),
    WHERE("ui-map-pin"),
    WIKI("ui-book"),

    /** About how long a walk round it takes, from its size (a park, a nature reserve). */
    LAP("ui-loop"),

    /** The EU's class of the bathing water at it. */
    QUALITY("ui-sparkles"),

    /** A swimming advisory in force at it. */
    ADVISORY("ui-alert-triangle"),
    ;

    /**
     * The tag one adds where a place does not say it (a toilet's fee), to answer as similar places around do; null
     * where no one tag says it (a charger's power).
     */
    val tag: String? get() = TAGS[this]

    /** What it says, as a long press explains its symbol ("Step-free access"). */
    fun label(lang: String): String = (TellLabels.of[this] ?: TellLabels.of.getValue(CUISINE)).let { it[lang] ?: it.getValue("en") }
}

private val TAGS = mapOf(
    TellKey.CUISINE to "cuisine", TellKey.DIET to "diet:vegetarian", TellKey.OUTDOOR to "outdoor_seating", TellKey.TAKEAWAY to "takeaway",
    TellKey.WIFI to "internet_access", TellKey.FEE to "fee", TellKey.CAPACITY to "capacity", TellKey.COVERED to "covered",
    TellKey.WHEELCHAIR to "wheelchair", TellKey.CHANGING to "changing_table", TellKey.STARS to "stars", TellKey.SWIM to "swimming",
    TellKey.DRINKABLE to "drinking_water", TellKey.BOTTLE to "bottle", TellKey.DISPENSING to "dispensing", TellKey.CASH_IN to "cash_in",
    TellKey.LIT to "lit", TellKey.INDOOR to "indoor", TellKey.ORGANIC to "organic", TellKey.SECOND_HAND to "second_hand",
    TellKey.SELF_SERVICE to "self_service", TellKey.BACKREST to "backrest", TellKey.DOG to "dog", TellKey.TOILETS to "toilets",
)

/** The tells' names in English, Dutch, German and French. */
private object TellLabels {
    private fun l(en: String, nl: String, de: String, fr: String) = mapOf("en" to en, "nl" to nl, "de" to de, "fr" to fr)

    val of: Map<TellKey, Map<String, String>> = mapOf(
        TellKey.CUISINE to l("Cuisine", "Keuken", "Küche", "Cuisine"),
        TellKey.DIET to l("Vegetarian and vegan", "Vegetarisch en veganistisch", "Vegetarisch und vegan", "Végétarien et vegan"),
        TellKey.OUTDOOR to l("Seats outside", "Terras", "Plätze draußen", "Terrasse"),
        TellKey.TAKEAWAY to l("Takeaway", "Afhalen", "Zum Mitnehmen", "À emporter"),
        TellKey.WIFI to l("Wi-Fi", "Wifi", "WLAN", "Wi-Fi"),
        TellKey.FEE to l("What it costs", "Wat het kost", "Was es kostet", "Ce que ça coûte"),
        TellKey.POWER to l("Fastest charging", "Snelste laden", "Schnellstes Laden", "Recharge la plus rapide"),
        TellKey.SOCKETS to l("Plugs", "Stekkers", "Stecker", "Prises"),
        TellKey.CAPACITY to l("How many fit", "Aantal plaatsen", "Plätze", "Nombre de places"),
        TellKey.COVERED to l("Covered", "Overdekt", "Überdacht", "Couvert"),
        TellKey.WHEELCHAIR to l("Step-free access", "Drempelvrij", "Stufenlos", "Accès sans marche"),
        TellKey.CHANGING to l("Baby changing table", "Verschoontafel", "Wickeltisch", "Table à langer"),
        TellKey.STARS to l("Stars", "Sterren", "Sterne", "Étoiles"),
        TellKey.RATING to l("Rating", "Beoordeling", "Bewertung", "Note"),
        TellKey.SWIM to l("Swimming", "Zwemmen", "Baden", "Baignade"),
        TellKey.DRINKABLE to l("Drinking water", "Drinkwater", "Trinkwasser", "Eau potable"),
        TellKey.BOTTLE to l("Fills a bottle", "Fles bijvullen", "Flasche auffüllen", "Remplir une gourde"),
        TellKey.ROUTES to l("Lines stopping here", "Lijnen die hier stoppen", "Linien hier", "Lignes qui s'arrêtent ici"),
        TellKey.ELEVATION to l("Height above sea level", "Hoogte boven zeeniveau", "Höhe über dem Meer", "Altitude"),
        TellKey.OPERATOR to l("Run by", "Beheerd door", "Betrieben von", "Exploité par"),
        TellKey.DISPENSING to l("Fills prescriptions", "Levert recepten", "Gibt Rezepte aus", "Délivre les ordonnances"),
        TellKey.CASH_IN to l("Takes deposits", "Geld storten", "Einzahlungen", "Dépôts"),
        TellKey.RELIGION to l("Faith", "Geloof", "Glaube", "Culte"),
        TellKey.SPORT to l("Sport", "Sport", "Sport", "Sport"),
        TellKey.LIT to l("Lit at night", "Verlicht", "Beleuchtet", "Éclairé"),
        TellKey.INDOOR to l("Indoors or out", "Binnen of buiten", "Drinnen oder draußen", "Dedans ou dehors"),
        TellKey.ORGANIC to l("Organic", "Biologisch", "Bio", "Bio"),
        TellKey.SECOND_HAND to l("Second-hand", "Tweedehands", "Gebraucht", "D'occasion"),
        TellKey.SELF_SERVICE to l("Self-service", "Zelfbediening", "Selbstbedienung", "Libre-service"),
        TellKey.FUEL to l("Fuels besides petrol", "Brandstoffen naast benzine", "Kraftstoffe außer Benzin", "Carburants en plus de l'essence"),
        TellKey.LEVEL to l("Floor", "Verdieping", "Etage", "Étage"),
        TellKey.BACKREST to l("Backrest", "Rugleuning", "Rückenlehne", "Dossier"),
        TellKey.DOG to l("Dogs", "Honden", "Hunde", "Chiens"),
        TellKey.TOILETS to l("Toilets", "Toiletten", "Toiletten", "Toilettes"),
        TellKey.COLLECTION to l("Next collection", "Volgende lichting", "Nächste Leerung", "Prochaine levée"),
        TellKey.RECYCLES to l("What it takes", "Wat erin mag", "Was hinein darf", "Ce qu'il accepte"),
        TellKey.VENDS to l("What it sells", "Wat het verkoopt", "Was er verkauft", "Ce qu'il vend"),
        TellKey.WHERE to l("Where it is", "Waar het hangt", "Wo es ist", "Où il se trouve"),
        TellKey.WIKI to l("Wikipedia article", "Wikipedia-artikel", "Wikipedia-Artikel", "Article Wikipédia"),
        TellKey.LAP to l("Time for a lap", "Tijd voor een rondje", "Zeit für eine Runde", "Temps pour un tour"),
        TellKey.QUALITY to l("EU bathing water class", "EU-zwemwaterklasse", "EU-Badegewässerklasse", "Classe européenne de baignade"),
        TellKey.ADVISORY to l("Swim advisory in force", "Zwemadvies van kracht", "Badehinweis in Kraft", "Avis de baignade en vigueur"),
    )
}


/** Whether a tell is good news for what the kind is for (free, may swim), a warning (no swimming), or neither. */
enum class TellTone { PLAIN, GOOD, WARN }

/**
 * One thing a row says: its [symbol] and its few words ([text]; none where the symbol says it all, a step-free
 * entrance), the tags it reads ([keys]), and whether those were last checked so long ago that one should not count on
 * them ([stale], with the day they were last [checked]): the user, 3 November, "Sometimes it's important to
 * immediately [see] if a detail or tag you may be about to depend on has been mapped a long time ago".
 */
data class Tell(
    val key: TellKey,
    val symbol: String,
    val text: String?,
    val keys: List<String>,
    val tone: TellTone = TellTone.PLAIN,
    val stale: Boolean = false,
    val checked: LocalDate? = null,
)

/** Reads [TellKey]s off a place's tags, in English, Dutch, German and French. */
object Tells {
    /**
     * The tells [keys] of a place with [tags] (and [extra], what providers added: its rating) that it maps, in their
     * order, as they stand at [now] (a post box's next collection), with their freshness ([edited]: when the place
     * was last edited). [sure] is how surely it serves swimming when more than its tags tell (a bathing place on a
     * lake's shore); [site] the official bathing water at it; [box] the box of its outline (how long a walk round it takes).
     */
    fun of(
        keys: List<TellKey>,
        tags: Map<String, String>,
        lang: String,
        now: LocalDateTime,
        edited: LocalDate? = null,
        extra: Map<String, String> = emptyMap(),
        sure: Sure? = null,
        site: BathingSite? = null,
        box: BBox? = null,
    ): List<Tell> = keys.mapNotNull { k ->
        if (site != null && (k == TellKey.QUALITY || k == TellKey.ADVISORY || k == TellKey.SWIM)) ofSite(k, site, lang)
        else if (k == TellKey.LAP) lap(box, lang)
        else read(k, tags, extra, lang, now, sure)?.let { fresh(it, tags, now.toLocalDate(), edited) }
    }

    /**
     * About how long a walk round a green of [box] takes, in words, or how far round it is where that would take most of
     * a day ("≈ 30 km lap": a national park); nothing for a pocket garden or one of no known size.
     */
    private fun lap(box: BBox?, lang: String): Tell? {
        val metres = Laps.metres(box) ?: return null
        val time = Laps.minutes(metres)?.let { minutes ->
            if (minutes < 60) w(LAP_MINUTES, lang).format(minutes) else w(LAP_HOURS, lang).format(minutes / 60, minutes % 60).removeSuffix(" 00")
        } ?: Laps.km(metres)?.let { "$it km" } ?: return null
        return Tell(TellKey.LAP, TellKey.LAP.symbol, w(LAP_WORDS, lang).format(time), emptyList())
    }

    /** What the official bathing water at a place says: a bathing water, its class, the advisory in force. */
    private fun ofSite(k: TellKey, site: BathingSite, lang: String): Tell? = when (k) {
        TellKey.SWIM -> Tell(k, k.symbol, w(BATHING_WATER, lang), emptyList(), if (site.swimmable) TellTone.GOOD else TellTone.PLAIN)
        TellKey.QUALITY -> site.quality?.takeIf { it != WaterQuality.UNCLASSIFIED }?.let { q ->
            Tell(k, k.symbol, w(QUALITY_NAMES.getValue(q), lang), emptyList(), when (q) {
                WaterQuality.EXCELLENT -> TellTone.GOOD
                WaterQuality.POOR -> TellTone.WARN
                else -> TellTone.PLAIN
            })
        }
        TellKey.ADVISORY -> site.advisory?.let { a -> Tell(k, k.symbol, w(ADVISORY_NAMES.getValue(a), lang), emptyList(), TellTone.WARN) }
        else -> null
    }

    /**
     * What a place costs as the cheapest order reads it: 0 when free, the amount mapped, [PAID_UNKNOWN] when it is
     * paid without saying how much; null when it says nothing.
     */
    fun price(tags: Map<String, String>): Double? {
        if (FEE_KEYS.none { it in tags }) return null
        val amount = FEE_KEYS.drop(1).firstNotNullOfOrNull { tags[it]?.let(Facets::amount) }
        return when {
            Facts.stateOf(tags["fee"]) == FactState.NO -> 0.0
            amount != null -> amount.value
            Facts.stateOf(tags["fee"]) == FactState.YES -> PAID_UNKNOWN
            else -> null
        }
    }

    /** What a paid place costs when it does not say: after every amount. */
    const val PAID_UNKNOWN = 1e9

    /** A tell with its freshness: stale when what it reads was last checked longer ago than such a tag lasts. */
    private fun fresh(t: Tell, tags: Map<String, String>, today: LocalDate, edited: LocalDate?): Tell {
        val key = t.keys.firstOrNull() ?: return t
        val c = Freshness.of(tags, key, today, edited)
        return t.copy(stale = c.stale && c.days != null, checked = c.date)
    }

    private fun read(k: TellKey, tags: Map<String, String>, extra: Map<String, String>, lang: String, now: LocalDateTime, sure: Sure?): Tell? {
        fun tell(text: String?, vararg keys: String, tone: TellTone = TellTone.PLAIN, symbol: String = k.symbol) =
            Tell(k, symbol.takeIf { Symbols.has(it) } ?: k.symbol, text, keys.toList(), tone)
        fun state(key: String) = Facts.stateOf(tags[key])
        return when (k) {
            TellKey.CUISINE -> Cuisine.values(tags).firstOrNull()?.let { v ->
                tell(Cuisine.name(v, lang), "cuisine", symbol = Cuisine.symbol(v))
            }
            TellKey.DIET -> when {
                state("diet:vegan") == FactState.ONLY -> tell(w(ONLY_VEGAN, lang), "diet:vegan", tone = TellTone.GOOD)
                state("diet:vegan") == FactState.YES -> tell(w(VEGAN, lang), "diet:vegan")
                state("diet:vegetarian") == FactState.ONLY -> tell(w(ONLY_VEGETARIAN, lang), "diet:vegetarian", tone = TellTone.GOOD, symbol = "ui-leaf")
                state("diet:vegetarian") == FactState.YES -> tell(w(VEGETARIAN, lang), "diet:vegetarian", symbol = "ui-leaf")
                else -> null
            }
            TellKey.OUTDOOR -> if (state("outdoor_seating") == FactState.YES) tell(null, "outdoor_seating") else null
            TellKey.TAKEAWAY -> when (state("takeaway")) {
                FactState.YES -> tell(null, "takeaway")
                FactState.ONLY -> tell(w(TAKEAWAY_ONLY, lang), "takeaway")
                else -> null
            }
            TellKey.WIFI -> tags["internet_access"]?.takeIf { it in WIFI_VALUES }?.let {
                tell(if (state("internet_access:fee") == FactState.NO) w(FREE, lang) else null, "internet_access")
            }
            TellKey.FEE -> fee(tags, lang)?.let { (text, tone) -> tell(text, *(FEE_KEYS.filter { it in tags }.toTypedArray()), tone = tone) }
            TellKey.POWER -> Power.maxKw(tags)?.let { kw -> tell("${Power.format(kw)} kW", *(tags.keys.filter { Power.isOutput(it) }.take(1).toTypedArray())) }
            TellKey.SOCKETS -> SOCKETS.filter { (key, _) -> Facts.stateOf(tags[key])?.let { it != FactState.NO } ?: tags[key]?.toIntOrNull()?.let { it > 0 } ?: false }
                .map { it.second }.distinct().take(2).takeIf { it.isNotEmpty() }?.let { tell(it.joinToString(" · "), "socket") }
            TellKey.CAPACITY -> tags["capacity"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { tell(it.toString(), "capacity") }
            TellKey.COVERED -> if (state("covered") == FactState.YES || state("shelter") == FactState.YES) tell(null, if ("covered" in tags) "covered" else "shelter") else null
            TellKey.WHEELCHAIR -> when (state("wheelchair")) {
                FactState.YES, FactState.ONLY -> tell(null, "wheelchair", tone = TellTone.GOOD)
                FactState.LIMITED -> tell(w(PARTLY, lang), "wheelchair")
                FactState.NO -> tell(w(NOT_STEP_FREE, lang), "wheelchair", tone = TellTone.WARN)
                else -> null
            }
            TellKey.CHANGING -> if (state("changing_table") == FactState.YES) tell(null, "changing_table") else null
            TellKey.STARS -> tags["stars"]?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..7 }?.let { tell("★".repeat(it), "stars") }
            TellKey.RATING -> extra["rating"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { r -> Tell(k, k.symbol, String.format(Locale.ROOT, "%.1f", r), emptyList()) }
            TellKey.SWIM -> swim(tags, lang, sure)?.let { (text, tone, key) -> tell(text, *(listOfNotNull(key).toTypedArray()), tone = tone) }
            TellKey.DRINKABLE -> when (state("drinking_water")) {
                FactState.YES -> tell(w(DRINKABLE, lang), "drinking_water", tone = TellTone.GOOD)
                FactState.NO -> tell(w(NOT_DRINKABLE, lang), "drinking_water", tone = TellTone.WARN)
                else -> null
            }
            TellKey.BOTTLE -> if (state("bottle") == FactState.YES) tell(null, "bottle") else null
            TellKey.ROUTES -> tags["route_ref"]?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?.takeIf { it.isNotEmpty() }?.let { refs -> tell(refs.take(4).joinToString(" · ") + if (refs.size > 4) " +${refs.size - 4}" else "", "route_ref") }
            TellKey.ELEVATION -> tags["ele"]?.trim()?.substringBefore(' ')?.replace(',', '.')?.toDoubleOrNull()?.let { tell("${it.toInt()} m", "ele") }
            TellKey.OPERATOR -> (tags["operator"] ?: tags["network"])?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 && it != tags["name"] && it != tags["brand"] }
                ?.let { tell(it, if ("operator" in tags) "operator" else "network") }
            TellKey.DISPENSING -> if (state("dispensing") == FactState.YES) tell(null, "dispensing") else null
            TellKey.CASH_IN -> if (state("cash_in") == FactState.YES) tell(w(DEPOSIT, lang), "cash_in") else null
            TellKey.RELIGION -> (tags["denomination"]?.let { "denomination" to it } ?: tags["religion"]?.let { "religion" to it })
                ?.let { (key, v) -> tell(option(key, v, lang), key) }
            TellKey.SPORT -> tags["sport"]?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { tell(option("sport", it, lang), "sport") }
            TellKey.LIT -> if (state("lit") == FactState.YES) tell(null, "lit") else null
            TellKey.INDOOR -> when (state("indoor")) {
                FactState.YES -> tell(w(INDOORS, lang), "indoor")
                FactState.NO -> tell(w(OUTSIDE, lang), "indoor", tone = TellTone.GOOD)
                else -> null
            }
            TellKey.ORGANIC -> when (state("organic")) {
                FactState.ONLY -> tell(w(ONLY_ORGANIC, lang), "organic", tone = TellTone.GOOD)
                FactState.YES -> tell(w(ORGANIC, lang), "organic")
                else -> null
            }
            TellKey.SECOND_HAND -> when (state("second_hand")) {
                FactState.YES, FactState.ONLY -> tell(w(SECOND_HAND, lang), "second_hand")
                else -> null
            }
            TellKey.SELF_SERVICE -> if (state("self_service") == FactState.YES) tell(null, "self_service") else null
            TellKey.FUEL -> FUELS.filter { (key, _) -> state(key) == FactState.YES }.map { it.second }.take(3).takeIf { it.isNotEmpty() }
                ?.let { tell(it.joinToString(" · "), "fuel") }
            TellKey.LEVEL -> tags["level"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" }?.let { tell(w(FLOOR, lang).format(it), "level") }
            TellKey.BACKREST -> if (state("backrest") == FactState.YES) tell(null, "backrest") else null
            TellKey.DOG -> when (tags["dog"]?.trim()) {
                "yes", "leashed", "unleashed" -> tell(null, "dog", tone = TellTone.GOOD)
                "no" -> tell(w(NO_DOGS, lang), "dog", tone = TellTone.WARN)
                else -> null
            }
            TellKey.TOILETS -> if (state("toilets") == FactState.YES) tell(null, "toilets") else null
            TellKey.COLLECTION -> tags["collection_times"]?.let { Collections.next(it, now) }?.let { at ->
                tell(if (at.toLocalDate() == now.toLocalDate()) clock(at.toLocalTime()) else day(at.dayOfWeek, lang) + " " + clock(at.toLocalTime()), "collection_times")
            }
            TellKey.RECYCLES -> tags.keys.filter { it.startsWith("recycling:") && state(it) == FactState.YES }.map { it.removePrefix("recycling:") }
                .takeIf { it.isNotEmpty() }?.let { what ->
                    val names = what.take(3).map { option("recycling:$it", it, lang) }
                    tell(names.joinToString(" · ") + if (what.size > 3) " +${what.size - 3}" else "", "recycling:${what.first()}")
                }
            TellKey.VENDS -> tags["vending"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?.let { v -> tell(v.take(2).joinToString(" · ") { option("vending", it, lang) }, "vending") }
            TellKey.WHERE -> (tags["defibrillator:location:$lang"] ?: tags["defibrillator:location"] ?: tags["description:$lang"]?.takeIf { "defibrillator" in tags.values })
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { tell(if (it.length > WHERE_MAX) it.take(WHERE_MAX - 1).trimEnd() + "…" else it, if ("defibrillator:location:$lang" in tags) "defibrillator:location:$lang" else "defibrillator:location") }
            TellKey.WIKI -> if (tags.keys.any { it == "wikipedia" || it.startsWith("wikipedia:") }) tell(null, "wikipedia") else null
            // told from the place's size, not its tags ([lap])
            TellKey.LAP -> null
            // the registers', not the tags'
            TellKey.QUALITY, TellKey.ADVISORY -> null
        }
    }

    private fun clock(t: LocalTime): String = "%02d:%02d".format(Locale.ROOT, t.hour, t.minute)

    private fun day(d: DayOfWeek, lang: String): String = d.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag(lang)).trimEnd('.')

    private const val WHERE_MAX = 34

    /** Free, an amount ("€0.50"), or paid; null when none of the price's keys is mapped. */
    private fun fee(tags: Map<String, String>, lang: String): Pair<String, TellTone>? {
        if (FEE_KEYS.none { it in tags }) return null
        val amount = FEE_KEYS.drop(1).firstNotNullOfOrNull { tags[it]?.let(Facets::amount) }
        val fee = Facts.stateOf(tags["fee"])
        return when {
            fee == FactState.NO || amount?.value == 0.0 -> w(FREE, lang) to TellTone.GOOD
            amount != null -> (amount.currency ?: "€").let { c -> if (c.length == 1) c + money(amount.value) else money(amount.value) + " " + c } to TellTone.PLAIN
            fee == FactState.YES -> w(PAID, lang) to TellTone.PLAIN
            else -> null
        }
    }

    private fun money(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ROOT, "%.2f", v)

    /**
     * Whether one may swim there: a swim spot, allowed, likely (a beach on its shore), or not; null where nothing
     * says. The key it reads is the tag that says so, none where a place nearby does.
     */
    private fun swim(tags: Map<String, String>, lang: String, sure: Sure?): Triple<String, TellTone, String?>? {
        val own = Certainty.swim(tags)
        val key = SWIM_KEYS.firstOrNull { it in tags && own != Sure.MAYBE }
        return when (sure ?: own) {
            Sure.YES -> Triple(w(if (tags["leisure"] == "bathing_place" || tags["leisure"] == "swimming_area" || own != Sure.YES) SWIM_SPOT else SWIM, lang), TellTone.GOOD, key)
            Sure.LIKELY -> Triple(w(SWIM_LIKELY, lang), TellTone.PLAIN, null)
            Sure.NO -> Triple(w(NO_SWIMMING, lang), TellTone.WARN, key ?: "access")
            Sure.MAYBE -> null
        }
    }

    private fun option(key: String, value: String, lang: String): String =
        Fields.forKey(key)?.takeIf { !it.isMultiKey && it.options.isNotEmpty() }?.optionLabel(value, lang)
            ?: value.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    internal val FEE_KEYS = listOf("fee", "charge", "price")
    private val WIFI_VALUES = setOf("wlan", "yes", "wifi")
    internal val SWIM_KEYS = listOf("swimming", "bathing", "leisure", "access")

    /** Socket keys and how a row names them, the commonest fast ones first. */
    private val SOCKETS = listOf(
        "socket:type2_combo" to "CCS", "socket:chademo" to "CHAdeMO", "socket:tesla_supercharger" to "Tesla",
        "socket:type2" to "Type 2", "socket:type2_cable" to "Type 2", "socket:type1" to "Type 1", "socket:schuko" to "Schuko",
    )

    /** Fuels worth saying (petrol everywhere is not). */
    private val FUELS = listOf("fuel:lpg" to "LPG", "fuel:cng" to "CNG", "fuel:lng" to "LNG", "fuel:hydrogen" to "H₂", "fuel:e85" to "E85", "fuel:adblue" to "AdBlue", "fuel:biodiesel" to "Bio")

    private fun w(m: Map<String, String>, lang: String): String = m[lang] ?: m.getValue("en")
    private fun l(en: String, nl: String, de: String, fr: String) = mapOf("en" to en, "nl" to nl, "de" to de, "fr" to fr)

    private val FREE = l("free", "gratis", "kostenlos", "gratuit")
    private val LAP_WORDS = l("≈ %s lap", "≈ %s rondje", "≈ %s Runde", "tour ≈ %s")
    private val LAP_MINUTES = l("%d min", "%d min", "%d Min.", "%d min")
    private val LAP_HOURS = l("%d h %02d", "%d u %02d", "%d Std. %02d", "%d h %02d")
    private val PAID = l("paid", "betaald", "kostenpflichtig", "payant")
    private val VEGAN = l("vegan", "veganistisch", "vegan", "vegan")
    private val ONLY_VEGAN = l("all vegan", "volledig veganistisch", "rein vegan", "100 % vegan")
    private val VEGETARIAN = l("vegetarian", "vegetarisch", "vegetarisch", "végétarien")
    private val ONLY_VEGETARIAN = l("all vegetarian", "volledig vegetarisch", "rein vegetarisch", "100 % végétarien")
    private val TAKEAWAY_ONLY = l("takeaway only", "alleen afhalen", "nur zum Mitnehmen", "à emporter seulement")
    private val PARTLY = l("partly step-free", "deels drempelvrij", "teilweise stufenlos", "accès partiel")
    private val NOT_STEP_FREE = l("steps", "met drempel", "mit Stufen", "marches")
    private val SWIM = l("swim", "zwemmen", "baden", "baignade")
    private val SWIM_SPOT = l("swim spot", "zwemplek", "Badestelle", "lieu de baignade")
    private val NO_SWIMMING = l("no swimming", "niet zwemmen", "Baden verboten", "baignade interdite")
    private val SWIM_LIKELY = l("likely", "waarschijnlijk", "wahrscheinlich", "probable")
    private val BATHING_WATER = l("bathing water", "zwemwater", "Badegewässer", "eau de baignade")
    private val QUALITY_NAMES = mapOf(
        WaterQuality.EXCELLENT to l("excellent", "uitstekend", "ausgezeichnet", "excellente"),
        WaterQuality.GOOD to l("good", "goed", "gut", "bonne"),
        WaterQuality.SUFFICIENT to l("sufficient", "aanvaardbaar", "ausreichend", "suffisante"),
        WaterQuality.POOR to l("poor", "slecht", "mangelhaft", "insuffisante"),
    )
    private val ADVISORY_NAMES = mapOf(
        SwimAdvisory.WARNING to l("swim warning", "waarschuwing", "Warnung", "avertissement"),
        SwimAdvisory.ADVISED_AGAINST to l("advised not to swim", "negatief zwemadvies", "vom Baden abgeraten", "baignade déconseillée"),
        SwimAdvisory.BANNED to l("swimming banned", "zwemverbod", "Badeverbot", "baignade interdite"),
        SwimAdvisory.INVESTIGATING to l("being checked", "nader onderzoek", "wird geprüft", "en cours d'analyse"),
    )
    private val DRINKABLE = l("drinkable", "drinkbaar", "Trinkwasser", "potable")
    private val NOT_DRINKABLE = l("not drinkable", "geen drinkwater", "kein Trinkwasser", "non potable")
    private val DEPOSIT = l("deposits", "storten", "Einzahlung", "dépôt")
    private val INDOORS = l("indoors", "binnen", "drinnen", "à l'intérieur")
    private val OUTSIDE = l("outside", "buiten", "draußen", "à l'extérieur")
    private val ORGANIC = l("organic", "biologisch", "bio", "bio")
    private val ONLY_ORGANIC = l("all organic", "volledig biologisch", "rein bio", "100 % bio")
    private val SECOND_HAND = l("second-hand", "tweedehands", "gebraucht", "d'occasion")
    private val FLOOR = l("floor %s", "verdieping %s", "Etage %s", "étage %s")
    private val NO_DOGS = l("no dogs", "geen honden", "keine Hunde", "chiens interdits")
}

/**
 * How surely a place serves what was asked: yes; likely (a beach on a lake's shore); unclear, nothing says; or no
 * (the user, 3 November: "you might still want to see lakes where swimming might be possible and lakes where its
 * unclear").
 */
enum class Sure { YES, LIKELY, MAYBE, NO }

/** Whether a place serves an intent, as its tags say (the user, 3 November: "lakes where swimming might be possible and lakes where its unclear"). */
object Certainty {
    /** May one swim there: a swim spot, a beach or a water mapped for swimming; not where it says no; else it does not say. */
    fun swim(tags: Map<String, String>): Sure {
        val no = listOf("swimming", "bathing").any { Facts.stateOf(tags[it]) == FactState.NO } || Facts.stateOf(tags["access"]) == FactState.NO
        if (no) return Sure.NO
        val yes = listOf("swimming", "bathing").any { Facts.stateOf(tags[it]).let { s -> s == FactState.YES || s == FactState.LIMITED } } ||
            tags["leisure"] in setOf("bathing_place", "swimming_area", "beach_resort") || tags["natural"] == "beach" ||
            tags["sport"]?.split(';')?.any { it.trim() == "swimming" } == true
        return if (yes) Sure.YES else Sure.MAYBE
    }

    /** How sure [tags] are of [intent]: swimming as [swim] says; any other intent by its filter, no tag saying no. */
    fun of(intent: Intent, tags: Map<String, String>): Sure = when (intent.id) {
        "swim" -> swim(tags)
        else -> if (intent.filter.matches(tags)) Sure.YES else Sure.MAYBE
    }

    /** True when a place with [tags] is a spot for [intent] (a bathing place), so what it lies in serves it too (its lake). */
    fun spot(intent: Intent, tags: Map<String, String>): Boolean = when (intent.id) {
        "swim" -> tags["leisure"] in SWIM_SPOTS || Facts.stateOf(tags["swimming"]) == FactState.YES || Facts.stateOf(tags["bathing"]) == FactState.YES
        else -> intent.filter.matches(tags)
    }

    /** True when a place with [tags] near one makes [intent] likely there: a beach on a lake's shore. */
    fun hint(intent: Intent, tags: Map<String, String>): Boolean = when (intent.id) {
        "swim" -> tags["natural"] == "beach" || tags["emergency"]?.startsWith("lifeguard") == true
        else -> false
    }

    private val SWIM_SPOTS = setOf("bathing_place", "swimming_area", "beach_resort")
}

/** A charger's power, read off its sockets' outputs. */
object Power {
    /** True when [key] holds a socket's or the station's output ("socket:type2_combo:output"). */
    fun isOutput(key: String): Boolean = key.endsWith(":output") && (key.startsWith("socket:") || key.startsWith("charging_station"))

    /** The highest output mapped on [tags], in kW, or null. */
    fun maxKw(tags: Map<String, String>): Double? = tags.filterKeys(::isOutput).values.mapNotNull { v ->
        val n = NUMBER.find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return@mapNotNull null
        // "22 kW", "50kW" or a bare number in kW; "11000 W" in watts
        if (!v.contains("kw", ignoreCase = true) && v.contains('W') && n >= 1000) n / 1000 else n
    }.maxOrNull()

    fun format(kw: Double): String = if (kw % 1.0 == 0.0) kw.toInt().toString() else String.format(Locale.ROOT, "%.1f", kw)

    private val NUMBER = Regex("""(\d+(?:[.,]\d+)?)""")
}

/**
 * A post box's collection times ("Mo-Fr 09:00,17:00; Sa 12:00"), the opening-hours syntax with times instead of
 * spans: the next collection from a moment.
 */
object Collections {
    /** The next collection after [now] within a week, or null when [value] says none Atlas can read. */
    fun next(value: String, now: LocalDateTime): LocalDateTime? {
        val rules = value.split(';').mapNotNull(::rule)
        if (rules.isEmpty()) return null
        for (d in 0..7) {
            val date = now.toLocalDate().plusDays(d.toLong())
            // a later rule for the same day wins, as in opening hours
            val times = rules.lastOrNull { date.dayOfWeek in it.first }?.second ?: continue
            times.sorted().map { date.atTime(it) }.firstOrNull { it.isAfter(now) }?.let { return it }
        }
        return null
    }

    private fun rule(part: String): Pair<Set<DayOfWeek>, List<LocalTime>>? {
        val m = RULE.matchEntire(part.trim()) ?: return null
        val days = m.groupValues[1].takeIf { it.isNotBlank() }?.let(::days) ?: DayOfWeek.entries.toSet()
        val times = m.groupValues[2].split(',').mapNotNull { t ->
            val (h, mi) = t.trim().split(':').takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return@mapNotNull null
            if (h == null || mi == null || h !in 0..24 || mi !in 0..59) null else LocalTime.of(h % 24, mi)
        }
        return if (times.isEmpty() || days.isEmpty()) null else days to times
    }

    private fun days(s: String): Set<DayOfWeek> = s.split(',').flatMapTo(HashSet()) { span ->
        val ends = span.trim().split('-').map { DAYS[it.trim()] }
        when {
            ends.any { it == null } -> emptyList()
            ends.size == 1 -> listOf(ends[0]!!)
            // a span runs on to its last day, over the week's end ("Sa-Mo")
            else -> generateSequence(ends[0]!!) { if (it == ends[1]) null else it.plus(1) }.take(7).toList()
        }
    }

    private val DAYS = mapOf("Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY, "Th" to DayOfWeek.THURSDAY, "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY)
    private val RULE = Regex("""^((?:(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?,?)*)\s*((?:\d{1,2}:\d{2}\s*,?\s*)+)$""")
}
