package app.atlas.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.atlas.geo.LatLon
import app.atlas.ui.eat.EatScreen
import app.atlas.ui.go.EarsScreen
import app.atlas.ui.go.GoScreen
import app.atlas.ui.go.NavigateScreen
import app.atlas.ui.go.RouteScreen
import app.atlas.ui.go.VehicleScreen
import app.atlas.ui.home.HomeScreen
import app.atlas.ui.local.BuildingStoryScreen
import app.atlas.ui.local.GlobeScreen
import app.atlas.ui.local.LocalScreen
import app.atlas.ui.local.NamedAfterScreen
import app.atlas.ui.local.SoonScreen
import app.atlas.ui.mine.AboutScreen
import app.atlas.ui.mine.DataSourcesScreen
import app.atlas.ui.mine.LayersScreen
import app.atlas.ui.mine.MineScreen
import app.atlas.ui.mine.OnboardingScreen
import app.atlas.ui.mine.PrivacyScreen
import app.atlas.ui.mine.SettingsScreen
import app.atlas.ui.mine.WeatherScreen
import app.atlas.ui.mine.charge.ChargeScreen
import app.atlas.ui.mine.charge.ChargingRouteScreen
import app.atlas.ui.mine.offline.OfflineScreen
import app.atlas.ui.mine.offline.RegionPickerScreen
import app.atlas.ui.search.BrandScreen
import app.atlas.ui.search.BrandsScreen
import app.atlas.ui.search.CategoryScreen
import app.atlas.ui.search.PlaceScreen
import app.atlas.ui.search.QueryScreen
import app.atlas.ui.search.SearchScreen
import app.atlas.ui.search.WhatsHereScreen
import app.atlas.ui.shop.GroceriesScreen
import app.atlas.ui.shop.NeedsScreen
import app.atlas.model.Place as OsmPlace

/**
 * Every destination of the app. Each is data (its arguments) plus the screen that shows it, a function in its feature
 * package: `XScreen(args: Dest.X, nav: Navigator)`. Each has a [route] (`name?key=value…`) that [parse] reads back, for
 * the saved back stack, deep links and CI.
 *
 * To add one: a class here with its route and `Content`, and a line in [PARSERS]. Append; do not reorder.
 */
@Immutable
sealed interface Dest {
    /** The address of this destination; [parse] of it gives an equal destination. */
    val route: String

    /** The bottom-bar tab this destination belongs to. */
    val tab: Tab

    /** True for a tab's home, the only screens that show the bottom bar. */
    val isTabRoot: Boolean get() = false

    @Composable
    fun Content(nav: Navigator)

    // ---- Map (ui/home, ui/search, ui/eat, ui/shop) ------------------------------------------------------------------

    /** The Map home. [focus] centres the map there (a geo: link) at [zoom]. */
    data class Home(val focusLat: Double? = null, val focusLon: Double? = null, val zoom: Double? = null) : Dest {
        val focus: LatLon? get() = if (focusLat != null && focusLon != null) LatLon(focusLat, focusLon) else null
        override val route get() = Routes.build("home", "lat" to focusLat, "lon" to focusLon, "zoom" to zoom)
        override val tab get() = Tab.MAP
        override val isTabRoot get() = true

        @Composable
        override fun Content(nav: Navigator) = HomeScreen(this, nav)
    }

    /** Search: the field (with [query] typed in) and live results; [category] narrows to one category. */
    data class Search(val query: String = "", val category: String? = null) : Dest {
        override val route get() = Routes.build("search", "q" to query.ifEmpty { null }, "category" to category)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = SearchScreen(this, nav)
    }

    /** The query builder: "A near B" and the power syntax, starting from [text]. */
    data class Query(val text: String = "") : Dest {
        override val route get() = Routes.build("query", "q" to text.ifEmpty { null })
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = QueryScreen(this, nav)
    }

    /**
     * One place. [key] finds it in `Services.placeCache` with everything already known; [osmId] ("n123") fetches it
     * otherwise; [lat]/[lon] and [name] show at once while it loads. An osm.org link gives only the id: [at] is then null
     * until the place is found.
     */
    data class Place(val lat: Double? = null, val lon: Double? = null, val osmId: String? = null, val name: String? = null, val key: String? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("place", "lat" to lat, "lon" to lon, "id" to osmId, "name" to name, "key" to key)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = PlaceScreen(this, nav)

        companion object {
            /** The destination for [place], whose key the caller has put in the place cache (`Services.remember`). */
            fun of(place: OsmPlace): Place =
                Place(place.location.lat, place.location.lon, place.id?.toString(), place.name(), place.key)
        }
    }

    /** Brands around: chains and independents; [category] narrows to one category. */
    data class Brands(val category: String? = null) : Dest {
        override val route get() = Routes.build("brands", "category" to category)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = BrandsScreen(this, nav)
    }

    /** One brand ([id] from `:places` Brands) and its branches. */
    data class Brand(val id: String) : Dest {
        override val route get() = Routes.build("brand", "id" to id)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = BrandScreen(this, nav)
    }

    /** Every place of a category ([id] from `:places` Categories) around, as a list and on the map. */
    data class Category(val id: String) : Dest {
        override val route get() = Routes.build("category", "id" to id)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = CategoryScreen(this, nav)
    }

    /**
     * What is at a point: the long press on the map; with the building under the finger ([osmId], "w123": the one the map
     * drew there) where the press met one.
     */
    data class WhatsHere(val lat: Double, val lon: Double, val osmId: String? = null) : Dest {
        val at: LatLon get() = LatLon(lat, lon)
        override val route get() = Routes.build("whatshere", "lat" to lat, "lon" to lon, "id" to osmId)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = WhatsHereScreen(this, nav)
    }

    /** Eat & drink: the table of objects; [kind] opens one of them ("carte", "bar", "cafe", "ramen", "late", "order"). */
    data class Eat(val kind: String? = null) : Dest {
        override val route get() = Routes.build("eat", "kind" to kind)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = EatScreen(this, nav)
    }

    /** Groceries: the pantry; [kind] opens one shelf ("super", "bakery", "refill"…) or a tool ("list", "compare"). */
    data class Groceries(val kind: String? = null) : Dest {
        override val route get() = Routes.build("groceries", "kind" to kind)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = GroceriesScreen(this, nav)
    }

    /** A need, one tap away, pointing the way: [kind] is "water", "food" or "toilet". */
    data class Needs(val kind: String) : Dest {
        override val route get() = Routes.build("needs", "kind" to kind)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = NeedsScreen(this, nav)
    }

    // ---- Go (ui/go) ---------------------------------------------------------------------------------------------

    /** The Go tab. */
    data object Go : Dest {
        override val route get() = "go"
        override val tab get() = Tab.GO
        override val isTabRoot get() = true

        @Composable
        override fun Content(nav: Navigator) = GoScreen(this, nav)
    }

    /** The route planner to a place, from [from] (null: here), by [mode] (a `Mode` name; null: the usual one). */
    data class Route(
        val toLat: Double? = null,
        val toLon: Double? = null,
        val toName: String? = null,
        val fromLat: Double? = null,
        val fromLon: Double? = null,
        val fromName: String? = null,
        val mode: String? = null,
        /** Stops on the way, in order, as `lat,lon,name` joined by `|` (a shopping run); null for none. */
        val via: String? = null,
    ) : Dest {
        val to: LatLon? get() = if (toLat != null && toLon != null) LatLon(toLat, toLon) else null
        val from: LatLon? get() = if (fromLat != null && fromLon != null) LatLon(fromLat, fromLon) else null
        override val route get() = Routes.build(
            "route", "tolat" to toLat, "tolon" to toLon, "to" to toName, "fromlat" to fromLat, "fromlon" to fromLon, "from" to fromName, "mode" to mode,
            "via" to via,
        )
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = RouteScreen(this, nav)
    }

    /** Turn-by-turn to a place by [mode]; [demo] drives the simulator instead of the phone's location. */
    data class Navigate(val toLat: Double, val toLon: Double, val toName: String? = null, val mode: String = "WALK", val demo: Boolean = false) : Dest {
        val to: LatLon get() = LatLon(toLat, toLon)
        override val route get() = Routes.build("navigate", "tolat" to toLat, "tolon" to toLon, "to" to toName, "mode" to mode, "demo" to demo.takeIf { it })
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = NavigateScreen(this, nav)
    }

    /** Ears only: the world around you by sound. */
    data object Ears : Dest {
        override val route get() = "ears"
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = EarsScreen(this, nav)
    }

    /** A vehicle's dimensions and energy for [mode] (a `Mode` name). */
    data class Vehicle(val mode: String = "CAR") : Dest {
        override val route get() = Routes.build("vehicle", "mode" to mode)
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = VehicleScreen(this, nav)
    }

    /** Where to park, lock or charge a two-wheeler around you, for [mode] (a `Mode` name; null: a bike). */
    data class Twowheel(val mode: String? = null) : Dest {
        override val route get() = Routes.build("twowheel", "mode" to mode)
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.go.TwowheelScreen(this, nav)
    }

    // ---- Soon and Local (ui/local) --------------------------------------------------------------------------------

    /** The Soon tab: what is coming up near you. */
    data object Soon : Dest {
        override val route get() = "soon"
        override val tab get() = Tab.SOON
        override val isTabRoot get() = true

        @Composable
        override fun Content(nav: Navigator) = SoonScreen(this, nav)
    }

    /** The Local tab: the cabinet of drawers; [drawer] opens one. */
    data class Local(val drawer: String? = null) : Dest {
        override val route get() = Routes.build("local", "drawer" to drawer)
        override val tab get() = Tab.LOCAL
        override val isTabRoot get() = drawer == null

        @Composable
        override fun Content(nav: Navigator) = LocalScreen(this, nav)
    }

    /** Who or what a street or place is named after. */
    data class NamedAfter(val lat: Double, val lon: Double, val osmId: String? = null, val name: String? = null) : Dest {
        override val route get() = Routes.build("namedafter", "lat" to lat, "lon" to lon, "id" to osmId, "name" to name)
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = NamedAfterScreen(this, nav)
    }

    /** A building's story: year, architect, heritage, former use. */
    data class BuildingStory(val lat: Double, val lon: Double, val osmId: String? = null) : Dest {
        override val route get() = Routes.build("building", "lat" to lat, "lon" to lon, "id" to osmId)
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = BuildingStoryScreen(this, nav)
    }

    /** The zoom ladder from the street to the galaxy, starting at the globe. */
    data object Globe : Dest {
        override val route get() = "globe"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = GlobeScreen(this, nav)
    }

    /** The zoom ladder opened at the Moon. */
    data object Moon : Dest {
        override val route get() = "moon"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.MoonScreen(this, nav)
    }

    /** The zoom ladder opened at the solar system: the planets today and which are up tonight. */
    data object Planets : Dest {
        override val route get() = "planets"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.PlanetsScreen(this, nav)
    }

    /** The zoom ladder opened at the galaxy, with you in the Orion Spur. */
    data object Galaxy : Dest {
        override val route get() = "galaxy"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.GalaxyScreen(this, nav)
    }

    /** What is in season here: the year wheel of foods, the nearest farmers' market. */
    data object Season : Dest {
        override val route get() = "season"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.SeasonScreen(this, nav)
    }

    /** The year here: seasons of things to do, on a wheel. */
    data object Year : Dest {
        override val route get() = "year"
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.YearScreen(this, nav)
    }

    /** A few moments picked on this phone: rain, sun, places closing, the station overhead. */
    data object Moments : Dest {
        override val route get() = "moments"
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.MomentsScreen(this, nav)
    }

    /** The sky: rain on its way, the space station, tonight's planets and the Moon. */
    data object Sky : Dest {
        override val route get() = "sky"
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.SkyScreen(this, nav)
    }

    /** The time machine: what stood here, year by year, from the buildings' mapped dates. */
    data class Time(val lat: Double? = null, val lon: Double? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("time", "lat" to lat, "lon" to lon)
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.TimeScreen(this, nav)
    }

    /** Look around: the street ahead of you, the places in view, a street photo where one exists. */
    data class Look(val lat: Double? = null, val lon: Double? = null, val heading: Double? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("look", "lat" to lat, "lon" to lon, "heading" to heading)
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.LookScreen(this, nav)
    }

    /** What's that peak?: the named peaks around a point (null: here) and the compass heading to each. */
    data class Peaks(val lat: Double? = null, val lon: Double? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("peaks", "lat" to lat, "lon" to lon)
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.PeaksScreen(this, nav)
    }

    /** The waste calendar: what goes out when, from a calendar you import, with a night-before reminder. */
    data object Collect : Dest {
        override val route get() = "collect"
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.CollectScreen(this, nav)
    }

    /** Field notes: notable trees, birds, gardens and wild places near a point (null: here); [kind] opens one section. */
    data class Nature(val kind: String? = null, val lat: Double? = null, val lon: Double? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("nature", "kind" to kind, "lat" to lat, "lon" to lon)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.NatureScreen(this, nav)
    }

    /** A park's own plan: what is in it and where. [osmId] names the park ("w123"); null opens the nearest. */
    data class Parkland(val osmId: String? = null, val lat: Double? = null, val lon: Double? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("parkland", "id" to osmId, "lat" to lat, "lon" to lon)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.ParklandScreen(this, nav)
    }

    /** Picnic and play: tables, grills, playgrounds, dog parks, lawns, splash pads, fitness and courts; [kind] picks one plate. */
    data class Picnic(val kind: String? = null) : Dest {
        override val route get() = Routes.build("picnic", "kind" to kind)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.PicnicScreen(this, nav)
    }

    /** Where to swim: pools, lakes and the sea, with the tide and the water's quality where they are known. */
    data class Swim(val kind: String? = null) : Dest {
        override val route get() = Routes.build("swim", "kind" to kind)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.SwimScreen(this, nav)
    }

    /** Peace and quiet: saunas, thermal baths, yoga, quiet rooms and gardens; [kind] picks one. */
    data class Relax(val kind: String? = null) : Dest {
        override val route get() = Routes.build("relax", "kind" to kind)
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.local.RelaxScreen(this, nav)
    }

    // ---- Mine (ui/mine) -----------------------------------------------------------------------------------------

    /** The Mine tab. */
    data object Mine : Dest {
        override val route get() = "mine"
        override val tab get() = Tab.MINE
        override val isTabRoot get() = true

        @Composable
        override fun Content(nav: Navigator) = MineScreen(this, nav)
    }

    /** Settings; [section] scrolls to one ("units", "voice", "lenses"). */
    data class Settings(val section: String? = null) : Dest {
        override val route get() = Routes.build("settings", "section" to section)
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = SettingsScreen(this, nav)
    }

    /** Data sources & keys; [provider] opens one provider's key field. */
    data class DataSources(val provider: String? = null) : Dest {
        override val route get() = Routes.build("datasources", "provider" to provider)
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = DataSourcesScreen(this, nav)
    }

    /** On this phone: what is kept, what goes online. */
    data object Privacy : Dest {
        override val route get() = "privacy"
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = PrivacyScreen(this, nav)
    }

    /** About & licences. */
    data object About : Dest {
        override val route get() = "about"
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = AboutScreen(this, nav)
    }

    /** Welcome: the first steps, from [step]. */
    data class Onboarding(val step: Int = 0) : Dest {
        override val route get() = Routes.build("onboarding", "step" to step.takeIf { it > 0 })
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = OnboardingScreen(this, nav)
    }

    /** The map's look and layers. */
    data object Layers : Dest {
        override val route get() = "layers"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = LayersScreen(this, nav)
    }

    /** The weather at a point (null: here) and what it means. */
    data class Weather(val lat: Double? = null, val lon: Double? = null) : Dest {
        override val route get() = Routes.build("weather", "lat" to lat, "lon" to lon)
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = WeatherScreen(this, nav)
    }

    /** The offline regions on this phone. */
    data object Offline : Dest {
        override val route get() = "offline"
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = OfflineScreen(this, nav)
    }

    /** Picking an area to take offline, around a point (null: here); [mode] opens "area", "route" or "country". */
    data class RegionPicker(val lat: Double? = null, val lon: Double? = null, val mode: String? = null) : Dest {
        override val route get() = Routes.build("regionpicker", "lat" to lat, "lon" to lon, "mode" to mode)
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = RegionPickerScreen(this, nav)
    }

    /** Chargers around a point (null: here), for your car; [select] opens one charger's card (a place key). */
    data class Charge(val lat: Double? = null, val lon: Double? = null, val select: String? = null) : Dest {
        override val route get() = Routes.build("charge", "lat" to lat, "lon" to lon, "select" to select)
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = ChargeScreen(this, nav)
    }

    /** A route with charging stops to a place. */
    data class ChargingRoute(val toLat: Double? = null, val toLon: Double? = null, val toName: String? = null) : Dest {
        override val route get() = Routes.build("chargingroute", "tolat" to toLat, "tolon" to toLon, "to" to toName)
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = ChargingRouteScreen(this, nav)
    }

    /** Where to power a phone around a point (null: here): outlets, power banks, solar. */
    data object Power : Dest {
        override val route get() = "power"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.power.PowerScreen(this, nav)
    }

    /** Fuel stations around a point (null: here), by the fuel your vehicle takes. */
    data class Pump(val lat: Double? = null, val lon: Double? = null) : Dest {
        override val route get() = Routes.build("pump", "lat" to lat, "lon" to lon)
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.pump.PumpScreen(this, nav)
    }

    /** Springs, taps and streams around a point (null: here), and whether the water you carry is enough. */
    data class Water(val lat: Double? = null, val lon: Double? = null) : Dest {
        override val route get() = Routes.build("water", "lat" to lat, "lon" to lon)
        override val tab get() = Tab.GO

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.water.WaterScreen(this, nav)
    }

    /** The forecast's models side by side, and who makes it. */
    data object Forecast : Dest {
        override val route get() = "forecast"
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.ForecastScreen(this, nav)
    }

    /** The satellites the phone hears: a sky plot, signal strength, accuracy. */
    data object Satellites : Dest {
        override val route get() = "satellites"
        override val tab get() = Tab.LOCAL

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.SatellitesScreen(this, nav)
    }

    /** Haptic turns on a watch, and the phone's own vibrations to try. */
    data object Watch : Dest {
        override val route get() = "watch"
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.WatchScreen(this, nav)
    }

    /** Move to a new phone: settings, saved places and lists out as one file, and back in. */
    data object NewPhone : Dest {
        override val route get() = "newphone"
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.mine.NewPhoneScreen(this, nav)
    }

    // ---- Saved places, Ask, map quests and Where to? (ui/search) ------------------------------------------------

    /** Saved places: the notebooks of lists; [id] opens one list. */
    data class Lists(val id: Int? = null) : Dest {
        override val route get() = Routes.build("lists", "id" to id)
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.search.ListsScreen(this, nav)
    }

    /** Ask: a whole outing said in one sentence ([text]), read into a timed plan of real places. */
    data class Ask(val text: String = "") : Dest {
        override val route get() = Routes.build("ask", "q" to text.ifEmpty { null })
        override val tab get() = Tab.SOON

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.search.AskScreen(this, nav)
    }

    /** Map quests around a point (null: here): what nobody has mapped yet, answered as notes for mappers. */
    data class Quests(val lat: Double? = null, val lon: Double? = null) : Dest {
        val at: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null
        override val route get() = Routes.build("quests", "lat" to lat, "lon" to lon)
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.search.QuestsScreen(this, nav)
    }

    /**
     * Fill in the gaps (the FillIn board): one building's open questions, its front's colour and its roof's shape, asked
     * over the living map at [lat] [lon], then the user's edit to OpenStreetMap to review and send. [type] and [id] name
     * the building's element ("way" 266616341).
     */
    data class FillIn(val type: String, val id: Long, val lat: Double, val lon: Double) : Dest {
        val at: LatLon get() = LatLon(lat, lon)
        override val route get() = Routes.build("fillin", "type" to type, "id" to id, "lat" to lat, "lon" to lon)
        override val tab get() = Tab.MINE

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.search.FillInScreen(this, nav)
    }

    /** Where to?: the wheel of ideas for what to do around here. */
    data object Where : Dest {
        override val route get() = "where"
        override val tab get() = Tab.MAP

        @Composable
        override fun Content(nav: Navigator) = app.atlas.ui.search.WhereScreen(this, nav)
    }

    companion object {
        /** Reads a destination's arguments back, by route name. Append a line per new destination. */
        private val PARSERS: Map<String, (RouteArgs) -> Dest?> = mapOf(
            "home" to { a -> Home(a.double("lat"), a.double("lon"), a.double("zoom")) },
            "search" to { a -> Search(a.str("q", ""), a.str("category")) },
            "query" to { a -> Query(a.str("q", "")) },
            "place" to { a -> if (a.latLon() == null && a.str("id") == null) null else Place(a.double("lat"), a.double("lon"), a.str("id"), a.str("name"), a.str("key")) },
            "brands" to { a -> Brands(a.str("category")) },
            "brand" to { a -> a.str("id")?.let { Brand(it) } },
            "category" to { a -> a.str("id")?.let { Category(it) } },
            "whatshere" to { a -> a.latLon()?.let { WhatsHere(it.lat, it.lon, a.str("id")) } },
            "eat" to { a -> Eat(a.str("kind")) },
            "groceries" to { a -> Groceries(a.str("kind")) },
            "needs" to { a -> a.str("kind")?.let { Needs(it) } },
            "go" to { _ -> Go },
            "route" to { a ->
                Route(a.double("tolat"), a.double("tolon"), a.str("to"), a.double("fromlat"), a.double("fromlon"), a.str("from"), a.str("mode"), a.str("via"))
            },
            "navigate" to { a -> a.latLon("to")?.let { Navigate(it.lat, it.lon, a.str("to"), a.str("mode", "WALK"), a.bool("demo")) } },
            "ears" to { _ -> Ears },
            "vehicle" to { a -> Vehicle(a.str("mode", "CAR")) },
            "twowheel" to { a -> Twowheel(a.str("mode")) },
            "soon" to { _ -> Soon },
            "local" to { a -> Local(a.str("drawer")) },
            "namedafter" to { a -> a.latLon()?.let { NamedAfter(it.lat, it.lon, a.str("id"), a.str("name")) } },
            "building" to { a -> a.latLon()?.let { BuildingStory(it.lat, it.lon, a.str("id")) } },
            "globe" to { _ -> Globe },
            "moon" to { _ -> Moon },
            "planets" to { _ -> Planets },
            "galaxy" to { _ -> Galaxy },
            "season" to { _ -> Season },
            "year" to { _ -> Year },
            "moments" to { _ -> Moments },
            "sky" to { _ -> Sky },
            "time" to { a -> Time(a.double("lat"), a.double("lon")) },
            "look" to { a -> Look(a.double("lat"), a.double("lon"), a.double("heading")) },
            "peaks" to { a -> Peaks(a.double("lat"), a.double("lon")) },
            "collect" to { _ -> Collect },
            "nature" to { a -> Nature(a.str("kind"), a.double("lat"), a.double("lon")) },
            "parkland" to { a -> Parkland(a.str("id"), a.double("lat"), a.double("lon")) },
            "picnic" to { a -> Picnic(a.str("kind")) },
            "swim" to { a -> Swim(a.str("kind")) },
            "relax" to { a -> Relax(a.str("kind")) },
            "mine" to { _ -> Mine },
            "settings" to { a -> Settings(a.str("section")) },
            "datasources" to { a -> DataSources(a.str("provider")) },
            "privacy" to { _ -> Privacy },
            "about" to { _ -> About },
            "onboarding" to { a -> Onboarding(a.int("step") ?: 0) },
            "layers" to { _ -> Layers },
            "weather" to { a -> Weather(a.double("lat"), a.double("lon")) },
            "offline" to { _ -> Offline },
            "regionpicker" to { a -> RegionPicker(a.double("lat"), a.double("lon"), a.str("mode")) },
            "charge" to { a -> Charge(a.double("lat"), a.double("lon"), a.str("select")) },
            "power" to { _ -> Power },
            "pump" to { a -> Pump(a.double("lat"), a.double("lon")) },
            "water" to { a -> Water(a.double("lat"), a.double("lon")) },
            "forecast" to { _ -> Forecast },
            "satellites" to { _ -> Satellites },
            "watch" to { _ -> Watch },
            "newphone" to { _ -> NewPhone },
            "chargingroute" to { a -> ChargingRoute(a.double("tolat"), a.double("tolon"), a.str("to")) },
            "lists" to { a -> Lists(a.int("id")) },
            "ask" to { a -> Ask(a.str("q", "")) },
            "quests" to { a -> Quests(a.double("lat"), a.double("lon")) },
            "fillin" to { a ->
                val type = a.str("type")
                val id = a.long("id")
                val lat = a.double("lat")
                val lon = a.double("lon")
                if (type == null || id == null || lat == null || lon == null) null else FillIn(type, id, lat, lon)
            },
            "where" to { _ -> Where },
        )

        /** The destination [route] names, or null when it is unknown, malformed or its arguments are incomplete. */
        fun parse(route: String): Dest? {
            val (name, args) = try {
                Routes.split(route.trim())
            } catch (e: IllegalArgumentException) {
                // A broken %-escape in a route typed by hand (CI, a deep link).
                return null
            }
            return PARSERS[name]?.invoke(args)
        }

        /** Every route name, for tests and the CI list. */
        val names: Set<String> get() = PARSERS.keys
    }
}
