package app.atlas.search

import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.Geometry
import app.atlas.places.NodeKind
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.ln

/** The weather where the searcher is, as far as it is known: what decides whether a lake, a park or a museum fits. */
data class WeatherNow(
    val tempC: Double? = null,
    val rain: Boolean = false,
    /** A storm or thunder warning in force. */
    val storm: Boolean = false,
    val sunny: Boolean = false,
    val windKmh: Double? = null,
    /** The water's temperature, from a bathing-water source when Atlas has one. */
    val waterC: Double? = null,
)

/**
 * Who searches, when and how (the user, 3 November: "Context aware: time of day, weekday, events, weather, location,
 * mode of transport, history, preferences"): the time, the weather, how the searcher gets about, which categories they
 * chose before and how often, and the categories they keep in a fixed order, if they set one.
 */
data class Moment(
    val now: ZonedDateTime,
    val weather: WeatherNow? = null,
    val mode: TravelMode = TravelMode.WALK,
    /** Node ids ("cat:restaurants") by how often the searcher chose them. */
    val picks: Map<String, Int> = emptyMap(),
    /** Node ids the searcher chose lately, newest first. */
    val recent: List<String> = emptyList(),
    /** Today's sunset where the searcher is, for the evening's views. */
    val sunset: LocalTime? = null,
    val lang: String = "en",
    /** The hours ahead, when a forecast reaches them: what is out of doors should know of the storm this afternoon. */
    val outlook: Outlook? = null,
    /** Today is a public holiday where the searcher is: offices and the post shut, the parks and the lakes full. */
    val holiday: Boolean = false,
) {
    val part: Daypart get() = Daypart.of(now.toLocalTime())
    val weekend: Boolean get() = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
}

/** The parts of a day a category matters in differently. */
enum class Daypart {
    MORNING, MIDDAY, AFTERNOON, EVENING, NIGHT;

    companion object {
        fun of(t: LocalTime): Daypart = when (t.hour) {
            in 6..10 -> MORNING
            in 11..13 -> MIDDAY
            in 14..17 -> AFTERNOON
            in 18..21 -> EVENING
            else -> NIGHT
        }
    }
}

/**
 * Something a searcher wants to do, which places of several kinds serve, and which the weather and the time decide
 * (the user, 3 November: "Some categories have intents, e.g. a lake. It's often for swimming. That's weather
 * dependent."): its [filter] says which places serve it, [presets] which kinds of things call it up (a lake calls up
 * swimming), [months] and [hours] when it fits, [minC] and [maxC] the air it needs, [outdoors] whether rain and
 * storms spoil it, [night] whether it still fits at night. [sources] names where Atlas reads what it says: OpenStreetMap,
 * the weather, and sources still to add (bathing-water quality, which the EU publishes per bathing site).
 */
data class Intent(
    val id: String,
    private val names: Map<String, String>,
    val symbol: String,
    val filter: TagFilter,
    /** Category ids whose places serve it. */
    val categories: Set<String>,
    /** id-tagging-schema preset ids whose things call it up ("natural/water/lake" calls up swimming). */
    val presets: Set<String> = emptySet(),
    val months: IntRange? = null,
    val hours: IntRange? = null,
    val minC: Double? = null,
    val maxC: Double? = null,
    val outdoors: Boolean = false,
    val night: Boolean = false,
    /** It fits best when it rains (somewhere dry to be). */
    val wantsRain: Boolean = false,
    /** It fits in the last ninety minutes before sunset. */
    val beforeSunset: Boolean = false,
    val sources: List<String> = listOf("osm"),
    /**
     * What a place of another kind says when it serves the intent ("a lake to swim": `swimming=yes`), [wantNear] what
     * nearby makes it likely when it says nothing (a bathing place on its shore, within [wantMetres]); null when a
     * kind cannot say it.
     */
    val want: TagFilter? = null,
    val wantNear: TagFilter? = null,
    val wantMetres: Double = 0.0,
    /** The way round, in metres, a place must at least have to serve it (a walk: no pocket garden); one of no known size may. */
    val roundAtLeast: Double? = null,
    /** The places [roundAtLeast] is asked of (a walk's greens, not its monuments); null: all of them. */
    val roomy: TagFilter? = null,
    /** How far round its places are read at least (a walk: as far as one walks there and back in an hour and more). */
    val reach: Double? = null,
) {
    fun name(lang: String): String = names[lang] ?: names.getValue("en")

    /** Whether [r] is one of its places: what its [filter] takes, with the room it asks for ([roundAtLeast]) where it asks it ([roomy]). */
    fun takes(r: PlaceRecord): Boolean = filter.matches(r.tags) && roomEnough(r)

    /** Whether [r] is one of the places it goes round in ([roomy]); all are where it names none. */
    fun goesRound(r: PlaceRecord): Boolean = roomy?.matches(r.tags) ?: true

    private fun roomEnough(r: PlaceRecord): Boolean {
        val least = roundAtLeast ?: return true
        return !goesRound(r) || (Laps.metres(r.place.bbox) ?: least) >= least
    }

    /**
     * How well it fits [m], 0 (not at all: a storm over the lake) to 1, and the reason, as a [FitReason]. Unknown
     * weather neither helps nor hurts.
     */
    fun fit(m: Moment): Pair<Double, FitReason> {
        val w = m.weather
        val t = m.now.toLocalTime()
        if (outdoors && w?.storm == true) return 0.0 to FitReason.STORM
        // a storm or a downpour on its way soon spoils going out now
        m.outlook?.let { o ->
            val soon = m.now.toLocalDateTime().plusHours(SOON_HOURS)
            if (outdoors && o.stormAt?.isBefore(soon) == true) return 0.25 to FitReason.STORM
            if (outdoors && o.heavyAt?.isBefore(soon) == true) return 0.3 to FitReason.RAIN
        }
        months?.let { if (m.now.monthValue !in it) return 0.15 to FitReason.SEASON }
        if (beforeSunset) {
            val s = m.sunset ?: LocalTime.of(19, 0)
            val minutes = java.time.Duration.between(t, s).toMinutes()
            return if (minutes in 0..90) 1.0 to FitReason.SUNSET else 0.2 to FitReason.TIME
        }
        hours?.let { h -> if (t.hour !in h && !(night && m.part == Daypart.NIGHT)) return 0.2 to FitReason.TIME }
        if (!night && m.part == Daypart.NIGHT) return 0.1 to FitReason.NIGHT
        // somewhere dry fits only in the rain, a warm or a cold day's only when the day is known to be so
        if (wantsRain && w?.rain != true) return 0.2 to FitReason.RAIN
        if ((minC != null || maxC != null) && w?.tempC == null) return 0.4 to FitReason.TIME
        var fit = 0.6
        var reason = FitReason.TIME
        if (w != null) {
            if (outdoors && w.rain) return 0.15 to FitReason.RAIN
            if (wantsRain && w.rain) { fit = 1.0; reason = FitReason.RAIN }
            w.tempC?.let { c ->
                minC?.let { if (c < it) return 0.1 to FitReason.COLD }
                maxC?.let { if (c > it) return 0.2 to FitReason.WARM }
                if (minC != null) { fit = (0.6 + (c - minC) / 10).coerceAtMost(1.0); reason = FitReason.WARM }
                if (maxC != null) { fit = (0.6 + (maxC - c) / 10).coerceAtMost(1.0); reason = FitReason.COLD }
            }
            if (outdoors && w.sunny && reason == FitReason.TIME) { fit = 0.85; reason = FitReason.SUN }
        }
        return fit to reason
    }
}

/** Within this many hours a storm or a downpour counts as coming now. */
private const val SOON_HOURS = 2L

/** Why an intent or a category fits now, or not. */
enum class FitReason { TIME, NIGHT, SEASON, SUNSET, SUN, RAIN, WARM, COLD, STORM, HISTORY, NEARBY }

/**
 * The intents Atlas knows: what people go out to do, the places that serve each, and when each fits. A thing of a
 * kind that calls an intent up ([Intent.presets]) shows it ("a lake: for swimming, 24°"), and the Now shelf offers the
 * intents that fit the moment.
 */
object Intents {
    private fun l(en: String, nl: String, de: String, fr: String) = mapOf("en" to en, "nl" to nl, "de" to de, "fr" to fr)
    private fun f(s: String) = TagFilter.parse(s)

    /** The intent whose view offers a walk round from where one is. */
    const val WALK = "walk"

    /** The time to eat out: at lunch and dinner the app calls it after the meal, in the Eat screen's own words. */
    const val EAT = "eat"

    /** The greens a walk goes round in: each says how long a lap of it takes, none is a pocket garden. */
    private const val WALK_GREENS = "leisure~^(park|garden|common|nature_reserve)$ or boundary=national_park or ((natural=wood or landuse=forest) and name)"

    /**
     * Where else a walk goes (the owner, 9 November: "can a walk gather as many places to walk to first?"): a view, a
     * tower to climb, a mill, a pier, a castle, a ruin, a monument, a memorial that stands (not a plaque or a stone in
     * the pavement), a statue, a beach, a peak, a waterfall; no square yet, which has no kind of its own to show.
     */
    private const val WALK_TO = "tourism~^(viewpoint|attraction)$ or leisure=bird_hide or man_made~^(lighthouse|windmill|watermill|pier)$ or " +
        "(man_made=tower and tower:type=observation) or historic~^(monument|castle|ruins|city_gate|fort|windmill|watermill|archaeological_site)$ or " +
        "(historic=memorial and memorial!~^(plaque|blue_plaque|stolperstein|stone|ghost_bike|bench)$) or " +
        "(tourism=artwork and artwork_type~^(statue|sculpture|installation)$ and name) or natural~^(beach|peak|waterfall|cave_entrance|cape)$ or " +
        "waterway=waterfall"

    /** How far a walk's places are read at least: about forty minutes on foot, each way. */
    const val WALK_REACH_M = 3_000.0

    val all: List<Intent> = listOf(
        Intent(
            "swim", l("Swim", "Zwemmen", "Schwimmen", "Se baigner"), "ui-waves",
            f("leisure~^(swimming_area|bathing_place|beach_resort)$ or natural=beach or (natural=water and (swimming=yes or bathing=yes))"),
            setOf("beach", "swim"),
            presets = setOf("natural/water/lake", "natural/water/pond", "natural/water/reservoir", "natural/beach", "leisure/swimming_area", "leisure/bathing_place", "natural/water/river"),
            months = 5..9, hours = 9..20, minC = 20.0, outdoors = true,
            sources = listOf("osm", "weather", "bathing-water"),
            want = f("swimming=yes or bathing=yes"), wantNear = f("leisure~^(swimming_area|bathing_place|beach_resort)$ or natural=beach"), wantMetres = 150.0,
        ),
        Intent(
            "picnic", l("Picnic", "Picknicken", "Picknick", "Pique-nique"), "ui-basket",
            f("leisure~^(picnic_table|park|garden)$ and access!=private"), setOf("parks", "picnic"),
            presets = setOf("leisure/picnic_table", "leisure/park", "leisure/garden"), months = 4..9, hours = 11..19, minC = 16.0, outdoors = true,
            want = f("picnic_table=yes"), wantNear = f("leisure=picnic_table or tourism=picnic_site"), wantMetres = 80.0,
        ),
        Intent(
            "sunset", l("Watch the sunset", "Zonsondergang kijken", "Sonnenuntergang sehen", "Voir le coucher du soleil"), "ui-sunset",
            f("tourism=viewpoint or natural~^(beach|peak)$"), setOf("viewpoint", "beach", "nature_spots"),
            presets = setOf("tourism/viewpoint", "natural/peak", "natural/beach", "natural/water/lake"), outdoors = true, beforeSunset = true, sources = listOf("osm", "weather", "sun"),
        ),
        Intent(
            "shelter", l("Somewhere dry", "Ergens droog", "Irgendwo trocken", "Un endroit au sec"), "ui-umbrella",
            f("amenity~^(cafe|library|cinema|shelter)$ or tourism=museum or shop=mall"), setOf("restaurants", "library", "museums", "cinema"),
            wantsRain = true, night = false, sources = listOf("osm", "weather"),
        ),
        Intent(
            "coffee", l("Coffee", "Koffie", "Kaffee", "Un café"), "ui-cup",
            f("amenity=cafe or shop=coffee or cuisine~coffee_shop"), setOf("restaurants"),
            presets = setOf("amenity/cafe", "amenity/cafe/coffee_shop"), hours = 7..17,
        ),
        Intent(
            EAT, l("Eat", "Eten", "Essen", "Manger"), "ui-utensils",
            f("amenity~^(restaurant|fast_food|food_court)$"), setOf("restaurants"), hours = 11..21,
        ),
        Intent(
            "late_food", l("Late bite", "Laat eten", "Spät essen", "Manger tard"), "ui-moon",
            f("amenity~^(fast_food|restaurant)$"), setOf("restaurants"), hours = 22..23, night = true,
        ),
        Intent(
            "drinks", l("Drinks", "Borrelen", "Etwas trinken", "Boire un verre"), "ui-beer",
            f("amenity~^(bar|pub|biergarten)$"), setOf("drinks"), hours = 16..23, night = true,
        ),
        Intent(
            "ice_cream", l("Ice cream", "IJsje", "Eis", "Une glace"), "ui-ice-cream",
            f("amenity=ice_cream or shop=ice_cream or cuisine~ice_cream"), setOf("restaurants"),
            presets = setOf("amenity/ice_cream"), months = 4..9, hours = 12..21, minC = 18.0,
        ),
        Intent(
            "play", l("Play outside", "Buiten spelen", "Draußen spielen", "Jouer dehors"), "ui-users",
            f("leisure~^(playground|park)$"), setOf("playground", "parks"),
            presets = setOf("leisure/playground"), hours = 8..19, minC = 5.0, outdoors = true,
            want = f("playground=yes"), wantNear = f("leisure=playground"), wantMetres = 100.0,
        ),
        Intent(
            WALK, l("A walk", "Wandelen", "Spazieren", "Se promener"), "ui-walk",
            f("($WALK_GREENS or $WALK_TO) and access!~^(private|no)$"), setOf("parks", "nature_reserve"),
            presets = setOf("leisure/park", "leisure/nature_reserve"), hours = 7..20, outdoors = true,
            roundAtLeast = Laps.POCKET_M, roomy = f(WALK_GREENS), reach = WALK_REACH_M,
        ),
        Intent(
            "museum", l("A museum", "Een museum", "Ein Museum", "Un musée"), "ui-landmark",
            f("tourism~^(museum|gallery)$"), setOf("museums"), hours = 10..16, wantsRain = true,
        ),
        Intent(
            "sauna", l("Warm up", "Opwarmen", "Aufwärmen", "Se réchauffer"), "ui-flame",
            f("leisure~^(sauna|spa)$ or amenity=public_bath"), setOf("sauna_spa"), hours = 10..22, maxC = 12.0,
        ),
        Intent(
            "refill", l("Refill water", "Water bijvullen", "Wasser auffüllen", "Remplir sa gourde"), "ui-droplet",
            f("amenity=drinking_water or (man_made=water_tap and drinking_water=yes) or drinking_water:refill=yes"), setOf("drinking_water"), minC = 22.0, night = true,
        ),
        Intent(
            "cash", l("Cash", "Geld pinnen", "Bargeld", "Retirer de l'argent"), "ui-cash",
            f("amenity=atm or (amenity=bank and atm=yes)"), setOf("money"), night = true,
        ),
        Intent(
            "toilet", l("A toilet", "Een wc", "Eine Toilette", "Des toilettes"), "ui-toilet",
            f("amenity=toilets"), setOf("toilets"), night = true,
        ),
        Intent(
            "pharmacy", l("A pharmacy", "Een apotheek", "Eine Apotheke", "Une pharmacie"), "ui-pill",
            f("amenity=pharmacy or healthcare=pharmacy"), setOf("health"), night = true,
        ),
        Intent(
            "work", l("Work somewhere", "Ergens werken", "Irgendwo arbeiten", "Travailler quelque part"), "ui-laptop",
            f("amenity~^(library|coworking_space|cafe)$ or office=coworking"), setOf("library", "coworking", "restaurants"),
            presets = setOf("amenity/library", "amenity/coworking_space"), hours = 8..18,
            want = f("internet_access~^(wlan|yes|wifi|public|terminal)$"),
        ),
        Intent(
            "stargaze", l("Stargazing", "Sterren kijken", "Sterne beobachten", "Observer les étoiles"), "ui-sparkles",
            f("man_made=observatory or tourism=viewpoint or leisure=nature_reserve or boundary=national_park or (natural=heath and access!=private)"),
            setOf("viewpoint", "nature_reserve"), presets = setOf("man_made/observatory"), hours = 20..23, outdoors = true, night = true,
            sources = listOf("osm", "weather", "moon"),
        ),
        Intent(
            "run", l("A run", "Hardlopen", "Laufen", "Courir"), "ui-walk",
            f("leisure~^(park|track|nature_reserve)$ or (leisure=track and sport~running|athletics) or route~^(running|fitness_trail)$"),
            setOf("parks", "sports"), hours = 6..21, outdoors = true,
        ),
        Intent(
            "fish", l("Fishing", "Vissen", "Angeln", "Pêcher"), "ui-fish",
            f("leisure=fishing or fishing=yes or (natural=water and fishing!=no)"), setOf("water", "nature_spots"),
            presets = setOf("leisure/fishing"), hours = 5..21, outdoors = true,
            want = f("fishing~^(yes|permit)$"),
        ),
        Intent(
            "cool_down", l("Cool down", "Afkoelen", "Abkühlen", "Se rafraîchir"), "ui-snowflake",
            f("leisure~^(swimming_area|bathing_place|water_park|swimming_pool)$ or amenity=fountain or natural=beach or tourism=museum or shop=mall"),
            setOf("swim", "beach", "museums"), months = 5..9, minC = 25.0,
        ),
        Intent(
            "watch_football", l("Watch football", "Voetbal kijken", "Fußball schauen", "Regarder le foot"), "ui-users",
            f("amenity~^(pub|bar|sports_bar)$ or leisure=stadium"), setOf("drinks", "stadium"), hours = 12..23, night = true,
            want = f("sport~soccer or screen=yes or tv=yes"),
        ),
        Intent(
            "barbecue", l("Barbecue", "Barbecueën", "Grillen", "Faire un barbecue"), "ui-flame",
            f("amenity=bbq or leisure=firepit or (leisure=park and bbq=yes)"), setOf("parks", "picnic"),
            presets = setOf("amenity/bbq", "leisure/firepit"), months = 4..9, hours = 12..21, minC = 15.0, outdoors = true,
            want = f("bbq=yes"), wantNear = f("amenity=bbq"), wantMetres = 100.0,
        ),
        Intent(
            "sightsee", l("Sights", "Bezienswaardigheden", "Sehenswürdigkeiten", "Visites"), "ui-camera",
            f("tourism~^(attraction|museum|gallery|viewpoint|artwork)$ or historic or heritage"), setOf("sights", "museums", "heritage", "viewpoint"),
            hours = 9..19,
        ),
        Intent(
            "walk_dog", l("Walk the dog", "De hond uitlaten", "Gassi gehen", "Promener le chien"), "ui-dog",
            f("leisure=dog_park or dog=unleashed or (leisure~^(park|nature_reserve)$ and dog!=no)"), setOf("parks", "nature_reserve"),
            presets = setOf("leisure/dog_park"), outdoors = true, night = true,
            want = f("dog~^(yes|leashed|unleashed)$"),
        ),
        Intent(
            "kitesurf", l("Kitesurf", "Kitesurfen", "Kitesurfen", "Kitesurf"), "ui-wind",
            f("sport~kitesurfing or (natural=beach and kitesurfing=yes)"), setOf("beach", "boating"), months = 4..10, hours = 9..19, outdoors = true,
            sources = listOf("osm", "weather", "wind"),
        ),
        Intent(
            "ice_skate", l("Ice skating", "Schaatsen", "Eislaufen", "Patiner"), "ui-snowflake",
            f("leisure=ice_rink or sport~ice_skating"), setOf("sports"), months = 11..12, maxC = 5.0,
        ),
        Intent(
            "sunbathe", l("Sunbathe", "Zonnen", "Sonnenbaden", "Bronzer"), "ui-sun",
            f("natural=beach or leisure~^(park|beach_resort|sunbathing)$"), setOf("beach", "parks"), months = 5..9, hours = 10..18, minC = 20.0, outdoors = true,
        ),
        Intent(
            "charge_phone", l("Charge my phone", "Telefoon opladen", "Handy laden", "Recharger mon téléphone"), "ui-plug",
            f("amenity~^(cafe|library|device_charging_station)$ or power_supply~^(yes|available)$"), setOf("restaurants", "library"), night = true,
            want = f("power_supply and power_supply!=no"),
        ),
        Intent(
            "sit", l("A place to sit", "Ergens zitten", "Ein Platz zum Sitzen", "S'asseoir"), "ui-sofa",
            f("amenity~^(bench|lounger)$ or leisure=picnic_table"), setOf("street_furniture", "picnic", "parks"), outdoors = true, night = true,
        ),
        Intent(
            "sleep", l("A bed for the night", "Een bed voor de nacht", "Ein Bett für die Nacht", "Un lit pour la nuit"), "ui-bed",
            f("tourism~^(hotel|hostel|guest_house|motel)$"), setOf("hotels"), hours = 18..23, night = true,
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Intent? = byId[id]

    /** The intents a kind of thing calls up: a lake, swimming and the sunset. */
    fun of(node: CategoryNode): List<Intent> {
        val path = CategoryTree.pathOf(node.id)
        return all.filter { i -> path.any { n -> n.preset?.id in i.presets || n.kind == NodeKind.CATEGORY && n.category.id in i.categories } }
    }

    /** The intents of a thing with [tags] ("natural=water + water=lake": swimming, the sunset), drawn as a point or an area. */
    fun of(tags: Map<String, String>): List<Intent> {
        val nodes = listOfNotNull(CategoryTree.of(tags, Geometry.POINT), CategoryTree.of(tags, Geometry.AREA)).distinct()
        return nodes.flatMap { of(it) }.distinct().ifEmpty { all.filter { it.filter.matches(tags) } }
    }

    /** The intents that fit [m] best, best first, those that fit badly left out. */
    fun now(m: Moment, max: Int = 4): List<Pair<Intent, FitReason>> =
        all.map { Triple(it, it.fit(m).first, it.fit(m).second) }.filter { it.second >= 0.6 }
            .sortedByDescending { it.second }.take(max).map { it.first to it.third }
}

/**
 * How much each category matters in each part of the day (the user, 3 November: "Which categories make sense at
 * night and which don't"): bars and fuel at night, not museums and playgrounds. From these, the weather, the weekday
 * and what the searcher chose before, [order] puts the grid's categories in the moment's order.
 */
object Relevance {
    /** Weights 0..1 for morning, midday, afternoon, evening and night. */
    private val TABLE: Map<String, DoubleArray> = """
        restaurants .6 1 .6 1 .5|drinks .1 .3 .5 1 .9|museums .7 .8 .9 .3 0|hotels .2 .2 .4 .8 .9|library .7 .7 .7 .3 0
        education .5 .3 .3 .1 0|skate .4 .6 .8 .5 0|climbing .4 .6 .8 .7 0|sports .5 .6 .8 .7 0|stadium .2 .3 .6 .8 .1
        gym .8 .6 .6 .8 .2|swim .6 .8 .9 .5 0|playground .6 .8 .9 .4 0|games .2 .5 .8 .8 .3|cinema .1 .3 .6 1 .4
        zoo .7 .8 .8 .1 0|theme_park .7 .8 .8 .2 0|camping .3 .3 .6 .8 .4|nature_reserve .8 .7 .8 .4 0|heritage .6 .7 .8 .4 .1
        health .6 .6 .6 .6 .8|money .5 .5 .5 .5 .6|post .6 .6 .5 .1 0|laundry .5 .4 .4 .3 .1|fuel .5 .4 .5 .6 .8
        parking .5 .5 .5 .5 .5|toilets .6 .7 .7 .7 .8|recycling .5 .5 .4 .2 0|transit .8 .5 .6 .7 .9|airport .5 .5 .5 .5 .5
        worship .5 .4 .4 .3 0|viewpoint .5 .5 .7 .9 .3|support .5 .5 .5 .4 .4|showers .6 .4 .4 .4 .3|defibrillator .5 .5 .5 .5 .5
        car_care .6 .5 .5 .1 0|low_emission_zone .3 .3 .3 .3 .3|vending .4 .4 .4 .5 .7|lost_property .4 .4 .3 0 0
        coworking .9 .7 .6 .2 0|drinking_water .5 .7 .8 .5 .2|street_furniture .4 .6 .6 .3 .1|charging .5 .5 .5 .6 .6
        sights .6 .8 .9 .6 .2|stage .1 .2 .4 1 .4|book_swap .5 .6 .7 .4 0|parks .7 .8 .9 .5 0|beach .4 .8 .9 .6 .1
        boating .5 .7 .8 .4 0|offices .8 .6 .5 .1 0|rentals .6 .6 .6 .6 .6|sauna_spa .4 .5 .7 .9 .2|nature_spots .6 .7 .8 .5 .1
        picnic .2 .9 .8 .4 0|bike_parking .6 .5 .5 .6 .4|community .4 .5 .6 .7 .1
        food_shops .8 .7 .8 .6 .2|shopping .4 .7 .9 .4 0|other .3 .3 .3 .3 .3
    """.trimIndent().replace('\n', '|').split('|').map { it.trim().split(' ') }.associate { p -> p[0] to p.drop(1).map { it.toDouble() }.toDoubleArray() }

    /** Categories out of doors: rain and cold count against them, sun for them. */
    private val OUTDOORS = setOf(
        "parks", "beach", "playground", "nature_reserve", "viewpoint", "sights", "camping", "boating", "skate", "sports",
        "zoo", "theme_park", "street_furniture", "picnic", "nature_spots", "heritage", "stadium",
    )

    /** Categories indoors that a rainy day sends people to. */
    private val INDOORS = setOf("museums", "cinema", "library", "games", "stage", "sauna_spa", "gym", "restaurants", "shopping", "climbing")

    /** Categories closed or pointless at the weekend (offices, schools) and those it favours. */
    private val WEEKDAYS = setOf("offices", "education", "post", "coworking", "car_care", "lost_property", "support")
    private val WEEKENDS = setOf(
        "parks", "zoo", "theme_park", "playground", "nature_reserve", "beach", "museums", "sights", "boating", "camping", "picnic",
    )

    /** How much category [id] matters in [part], 0..1. */
    fun weight(id: String, part: Daypart): Double = TABLE[id]?.get(part.ordinal) ?: 0.4

    /** True when category [id] still matters at night (a bar, a pharmacy, fuel), false when it does not (a museum). */
    fun atNight(id: String): Boolean = weight(id, Daypart.NIGHT) >= 0.5

    /**
     * How much category [id] matters at [m], with why: the time of day, then the weather for what is out of doors or a
     * shelter from it, the weekday, and how often and lately the searcher chose it.
     */
    fun score(id: String, m: Moment): Pair<Double, FitReason> {
        var s = weight(id, m.part)
        var why = if (m.part == Daypart.NIGHT) FitReason.NIGHT else FitReason.TIME
        m.weather?.let { w ->
            if (id in OUTDOORS) {
                if (w.storm) { s *= 0.1; why = FitReason.STORM } else if (w.rain) { s *= 0.4; why = FitReason.RAIN } else if (w.sunny) { s += 0.15; why = FitReason.SUN }
                w.tempC?.let { if (it < 5) { s *= 0.6; why = FitReason.COLD } }
            }
            if (id in INDOORS && (w.rain || w.storm)) { s += 0.2; why = FitReason.RAIN }
            if ((id == "beach" || id == "swim") && w.tempC != null) {
                if (w.tempC >= 22) { s += 0.3; why = FitReason.WARM } else if (w.tempC < 15 && id == "beach") { s *= 0.5; why = FitReason.COLD }
            }
            if (id == "sauna_spa" && (w.tempC ?: 20.0) < 8) { s += 0.2; why = FitReason.COLD }
        }
        // a storm on its way counts against going out, though it is dry now
        if (id in OUTDOORS && m.weather?.storm != true && m.outlook?.stormAt?.isBefore(m.now.toLocalDateTime().plusHours(SOON_HOURS)) == true) {
            s *= 0.3
            why = FitReason.STORM
        }
        // a public holiday rests as a Sunday does
        val restDay = m.weekend || m.holiday
        if (restDay && id in WEEKDAYS) s *= 0.4
        if (restDay && id in WEEKENDS) s += 0.1
        val node = "cat:$id"
        val picks = m.picks[node] ?: 0
        if (picks > 0) {
            val top = m.picks.values.max()
            s += 0.35 * ln(1.0 + picks) / ln(1.0 + top)
            why = FitReason.HISTORY
        }
        m.recent.indexOf(node).takeIf { it >= 0 }?.let { s += 0.2 / (1 + it); why = FitReason.HISTORY }
        return s to why
    }

    /** [ids] in the order [m] gives them, best first; equal scores keep their order. */
    fun order(ids: List<String>, m: Moment): List<String> = ids.withIndex().sortedWith(
        compareByDescending<IndexedValue<String>> { score(it.value, m).first }.thenBy { it.index },
    ).map { it.value }
}
