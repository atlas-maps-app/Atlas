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
        // The guidance screens in demo mode, each on a real route from the Westerkerk.
        "drive" to Dest.Navigate(CENTRAAL_LAT, CENTRAAL_LON, "Amsterdam Centraal", "CAR", demo = true),
        "truck" to Dest.Navigate(52.38785, 4.63830, "Haarlem", "TRUCK", demo = true),
        "walk" to Dest.Navigate(CENTRAAL_LAT, CENTRAAL_LON, "Amsterdam Centraal", "WALK", demo = true),
        "sail" to Dest.Navigate(52.3810, 4.9000, "Het IJ", "BOAT", demo = true),
        "twowheel" to Dest.Twowheel(),
        "soon" to Dest.Soon,
        "local" to Dest.Local(),
        "namedafter" to Dest.NamedAfter(52.37502, 4.88392, name = "Westermarkt"),
        "building" to Dest.BuildingStory(LAT, LON, "w99205257"),
        "globe" to Dest.Globe,
        // The local package: the zoom ladder, the seasons, the soon and local tabs, nature (ui/local).
        "moon" to Dest.Moon,
        "planets" to Dest.Planets,
        "galaxy" to Dest.Galaxy,
        "season" to Dest.Season,
        "year" to Dest.Year,
        "moments" to Dest.Moments,
        "sky" to Dest.Sky,
        "time" to Dest.Time(LAT, LON),
        "look" to Dest.Look(LAT, LON),
        "peaks" to Dest.Peaks(LAT, LON),
        "collect" to Dest.Collect,
        "nature" to Dest.Nature(null, LAT, LON),
        "naturewild" to Dest.Nature("wild", LAT, LON),
        "parkland" to Dest.Parkland(null, 52.3873, 4.8728),
        "picnic" to Dest.Picnic(),
        "picnicplay" to Dest.Picnic("play"),
        "swim" to Dest.Swim(),
        "swimsea" to Dest.Swim("sea"),
        "relax" to Dest.Relax(),
        "relaxsauna" to Dest.Relax("sauna"),
        "mine" to Dest.Mine,
        "settings" to Dest.Settings(),
        "datasources" to Dest.DataSources(),
        "privacy" to Dest.Privacy,
        "about" to Dest.About,
        "onboarding" to Dest.Onboarding(),
        "onboarding2" to Dest.Onboarding(1),
        "onboarding3" to Dest.Onboarding(2),
        "layers" to Dest.Layers,
        "weather" to Dest.Weather(),
        "offline" to Dest.Offline,
        "regionpicker" to Dest.RegionPicker(LAT, LON),
        "regioncountry" to Dest.RegionPicker(LAT, LON, "country"),
        "charge" to Dest.Charge(LAT, LON),
        "chargecard" to Dest.Charge(LAT, LON, select = "nearest"),
        "power" to Dest.Power,
        "pump" to Dest.Pump(LAT, LON),
        "water" to Dest.Water(LAT, LON),
        "forecast" to Dest.Forecast,
        "satellites" to Dest.Satellites,
        "watch" to Dest.Watch,
        "newphone" to Dest.NewPhone,
        "chargingroute" to Dest.ChargingRoute(51.92250, 4.47917, "Rotterdam"),
        "lists" to Dest.Lists(),
        "ask" to Dest.Ask("quiet café with an outlet, then dinner"),
        "quests" to Dest.Quests(LAT, LON),
        "where" to Dest.Where,
        "eatcarte" to Dest.Eat("carte"),
        "eatorder" to Dest.Eat("order"),
        "eatbar" to Dest.Eat("bar"),
        "eatcafe" to Dest.Eat("cafe"),
        "eatramen" to Dest.Eat("ramen"),
        "eatlate" to Dest.Eat("late"),
        "eatsweets" to Dest.Eat("sweets"),
        "eatmarket" to Dest.Eat("market"),
        "eatmap" to Dest.Eat("map"),
        // Groceries, errands and needs (ui/shop).
        "shopaisle" to Dest.Groceries("super"),
        "shoplist" to Dest.Groceries("list"),
        "shopcompare" to Dest.Groceries("compare"),
        "shoprefill" to Dest.Groceries("refill"),
        "shopfind" to Dest.Groceries("find:picture hooks"),
        "shoperrands" to Dest.Groceries("errands"),
        "shophelp" to Dest.Groceries("help"),
        "shophighstreet" to Dest.Groceries("highstreet"),
        "shopbins" to Dest.Groceries("bins"),
        "shopdrop" to Dest.Groceries("drop:batteries"),
        "shopstroll" to Dest.Groceries("stroll"),
        "needwater" to Dest.Needs("water"),
        "needfood" to Dest.Needs("food"),
    )

    /** The stack CI opens for [name]: the Map home, with the named screen on top; null for an unknown name. */
    fun stackFor(name: String): List<Dest>? {
        val dest = screens[name.trim().lowercase()] ?: Dest.parse(name) ?: return null
        return if (dest is Dest.Home) listOf(dest) else listOf(Dest.Home(), dest)
    }

    /** Every name, space-separated, as `atlas.screen=list` writes it. */
    fun list(): String = screens.keys.joinToString(" ")
}
