package app.atlas.nav

/**
 * The screens CI opens on the emulator (`MainActivity`'s `atlas.screen` extra, see `ci/smoke.sh`), by name, each with
 * sample data that makes sense at the demo location, the Westerkerk in Amsterdam. Each opens on top of the Map home.
 * Feature packages append their screens here.
 */
object CiScreens {
    private const val LAT = 52.37452
    private const val LON = 4.88377

    /** The Westerkerk: OSM way 99205257. */
    private val westerkerk = Dest.Place(LAT, LON, "w99205257", "Westerkerk")

    /** Amsterdam Centraal, 1.6 km away: a destination for routes. */
    private const val CENTRAAL_LAT = 52.37903
    private const val CENTRAAL_LON = 4.90010

    val screens: LinkedHashMap<String, Dest> = linkedMapOf(
        "home" to Dest.Home(),
        "search" to Dest.Search("apotheek"),
        "query" to Dest.Query("cafe near bicycle rental 200m"),
        "place" to westerkerk,
        "brands" to Dest.Brands(),
        "brand" to Dest.Brand("albertheijn-1cf55d"),
        "category" to Dest.Category("health"),
        "whatshere" to Dest.WhatsHere(LAT, LON),
        "eat" to Dest.Eat(),
        "groceries" to Dest.Groceries(),
        "needs" to Dest.Needs("toilet"),
        "go" to Dest.Go,
        "route" to Dest.Route(CENTRAAL_LAT, CENTRAAL_LON, "Amsterdam Centraal", LAT, LON, "Westerkerk", "WALK"),
        "navigate" to Dest.Navigate(CENTRAAL_LAT, CENTRAAL_LON, "Amsterdam Centraal", "BIKE", demo = true),
        "ears" to Dest.Ears,
        "vehicle" to Dest.Vehicle("TRUCK"),
        "soon" to Dest.Soon,
        "local" to Dest.Local(),
        "namedafter" to Dest.NamedAfter(52.37502, 4.88392, name = "Westermarkt"),
        "building" to Dest.BuildingStory(LAT, LON, "w99205257"),
        "globe" to Dest.Globe,
        "mine" to Dest.Mine,
        "settings" to Dest.Settings(),
        "datasources" to Dest.DataSources(),
        "privacy" to Dest.Privacy,
        "about" to Dest.About,
        "onboarding" to Dest.Onboarding(),
        "layers" to Dest.Layers,
        "weather" to Dest.Weather(),
        "offline" to Dest.Offline,
        "regionpicker" to Dest.RegionPicker(LAT, LON),
        "charge" to Dest.Charge(LAT, LON),
        "chargingroute" to Dest.ChargingRoute(51.92250, 4.47917, "Rotterdam"),
    )

    /** The stack CI opens for [name]: the Map home, with the named screen on top; null for an unknown name. */
    fun stackFor(name: String): List<Dest>? {
        val dest = screens[name.trim().lowercase()] ?: Dest.parse(name) ?: return null
        return if (dest is Dest.Home) listOf(dest) else listOf(Dest.Home(), dest)
    }

    /** Every name, space-separated, as `atlas.screen=list` writes it. */
    fun list(): String = screens.keys.joinToString(" ")
}
