package app.atlas

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.ChargerFilters
import app.atlas.providers.ChargerMerge
import app.atlas.providers.ChargerProvider
import app.atlas.providers.FailoverGeocoder
import app.atlas.providers.FoursquareRatings
import app.atlas.providers.Geocoder
import app.atlas.providers.Http
import app.atlas.providers.Mangrove
import app.atlas.providers.Nominatim
import app.atlas.providers.OpenChargeMap
import app.atlas.providers.OsmChargers
import app.atlas.providers.Overpass
import app.atlas.providers.Photon
import app.atlas.providers.ProviderState
import app.atlas.providers.RatingsAggregator
import app.atlas.providers.TripAdvisorRatings
import app.atlas.providers.Weather
import app.atlas.providers.YelpRatings
import app.atlas.route.BRouterRouter
import app.atlas.route.ChargerSource
import app.atlas.route.EvPlanner
import app.atlas.route.MultiRouter
import app.atlas.route.OverpassRestrictionSource
import app.atlas.route.Router
import app.atlas.route.SegmentStore
import app.atlas.route.WaterRouter
import app.atlas.search.PlaceSource
import app.atlas.search.PlaceStore
import app.atlas.search.Regions
import app.atlas.search.SearchEngine
import app.atlas.services.Connectivity
import app.atlas.services.DemoLocationSource
import app.atlas.services.FixedConnectivity
import app.atlas.services.KeyStore
import app.atlas.services.LayeredPlaceFinder
import app.atlas.services.AndroidLocationSource
import app.atlas.services.NoVoice
import app.atlas.services.PlaceCache
import app.atlas.services.PlaceFinder
import app.atlas.services.RegionManager
import app.atlas.services.SavedCamera
import app.atlas.services.SettingsKeys
import app.atlas.services.SettingsStore
import app.atlas.services.SwitchableLocation
import app.atlas.services.SystemConnectivity
import app.atlas.services.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.Clock

/** How [Services] is set up: the real app uses the defaults; tests and CI point it elsewhere. */
data class ServicesConfig(
    /** Where files live instead of the app's own folders (tests use a temporary folder). */
    val root: File? = null,
    /** Start at this demo location instead of the phone's (CI: the Westerkerk). */
    val demoLocation: LatLon? = null,
    /** Never touch the network: no Overpass, no providers; screens show their offline states. */
    val offline: Boolean = false,
    /** Place stores to search instead of the downloaded regions' (tests: the Amsterdam extract). */
    val placeStores: List<PlaceSource>? = null,
    /** The time and time zone screens show and reason with (tests: a fixed evening in Amsterdam). */
    val clock: Clock = Clock.systemDefaultZone(),
    /** Wire the map engine to this app's region packs ([MapData]); tests that draw no map leave it off. */
    val mapEngine: Boolean = true,
)

/** The folders Atlas keeps its data in. */
class Dirs(val files: File, val cache: File) {
    val settings = File(files, "settings/settings.preferences_pb")
    val keys = File(files, "settings/keys.preferences_pb")
    val regions = File(files, "regions")
    val places = File(files, "places")
    val segments = File(files, "routing/segments")
    val profiles = File(files, "routing/profiles")
    val http = File(cache, "http")
    val edits = File(files, "edits/queue.json")
    val osmAccount = File(files, "settings/osm.preferences_pb")
}

/**
 * Every long-lived object of the app, built lazily on first use, by hand (no DI framework): settings and keys, HTTP,
 * the map's data and the features' (OpenStreetMap: packs on the phone, region stores, Overpass), places, routing,
 * weather, ratings, chargers, location, the voice and the offline regions. Screens reach it through `LocalServices`; features add their own lazily built
 * services here (append-only).
 */
class Services(context: Context, val config: ServicesConfig = ServicesConfig()) : AutoCloseable {
    val context: Context = context.applicationContext ?: context

    /** Work that outlives a screen (downloads, refreshes); cancelled by [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What "now" is for every screen: opening hours, moments, the weather, night mode. */
    val clock: Clock get() = config.clock

    val dirs = Dirs(config.root ?: this.context.filesDir, config.root?.let { File(it, "cache") } ?: this.context.cacheDir)

    val settings: SettingsStore by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)) { dirs.settings })
    }

    /** Provider keys the user entered; the providers read them as their `Keys`. */
    val keys: KeyStore by lazy {
        KeyStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)) { dirs.keys }, scope)
    }

    val connectivity: Connectivity by lazy { if (config.offline) FixedConnectivity(online = false) else SystemConnectivity(this.context) }

    /** The one HTTP client of the providers: shared pool, disk cache, polite per-host limits. */
    val http: Http by lazy { Http(cacheDir = dirs.http) }

    private val regionVersion = MutableStateFlow(0)

    /** Bumped whenever offline files change, so anything built from them can rebuild. */
    val regionsChanged: StateFlow<Int> = regionVersion.asStateFlow()

    /** Tells every service that offline files changed (a region downloaded, updated or deleted). */
    fun reloadRegions() {
        if (config.placeStores == null) scope.launch(Dispatchers.IO) { syncPlaceStores(placeStores) }
        // the map and the features read the regions' own packs too
        if (mapDataLazy.isInitialized()) {
            mapData.packs.rescan()
            app.atlas.mapview.MapEngine.invalidate()
        }
        regionVersion.value++
    }

    /** The saved camera, read once at start so the first frame of the map is already in place. */
    val startCamera: Deferred<SavedCamera?> by lazy {
        scope.async(Dispatchers.IO) { withTimeoutOrNull(1_500) { settings.camera.first() } }
    }

    /** The saved camera if it has been read already, else null (never waits). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startCameraNow(): SavedCamera? = startCamera.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }

    // ---- Places and search ---------------------------------------------------------------------------------------

    private val openStores = HashMap<File, PlaceStore>()

    /** The offline place stores of the downloaded regions (`places/<region>.db`), all searched at once. */
    val placeStores: Regions by lazy {
        Regions(config.placeStores.orEmpty()).also { if (config.placeStores == null) syncPlaceStores(it) }
    }

    /** Attaches the store files in `places/` that are new and detaches (and closes) the ones that are gone. */
    private fun syncPlaceStores(regions: Regions) = synchronized(openStores) {
        val files = dirs.places.listFiles { f -> f.isFile && f.name.endsWith(".db") }.orEmpty().toSet()
        for (gone in openStores.keys - files) {
            openStores.remove(gone)?.let { regions.detach(it); runCatching { it.close() } }
        }
        for (f in files - openStores.keys) {
            runCatching { PlaceStore.open(f) }.getOrNull()?.let { store ->
                openStores[f] = store
                regions.attach(store)
            }
        }
    }

    /** The search box: offline over [placeStores]; online it adds Photon and Overpass. */
    val search: SearchEngine by lazy {
        SearchEngine(placeStores, geocoder = if (config.offline) null else geocoder, overpass = if (config.offline) null else overpass)
    }

    /** Nearby places by category (the Map home's row and needs): the place stores, else the map's packs, then online. */
    val places: PlaceFinder by lazy {
        LayeredPlaceFinder(
            local = { localData },
            overpass = { if (config.offline) null else overpass },
            online = { connectivity.online.value },
            store = { placeStores },
        )
    }

    /** Places handed between screens, by key. */
    val placeCache = PlaceCache()

    fun remember(place: Place): String = placeCache.put(place)

    // ---- Providers -----------------------------------------------------------------------------------------------

    val overpass: Overpass by lazy { Overpass(http) }
    val weather: Weather by lazy { Weather(http, File(dirs.cache, "providers"), keys, clock = { clock.instant() }) }
    /** Photon for the search box; for an address here, Nominatim too where Photon cannot answer, unless switched off. */
    val geocoder: Geocoder by lazy {
        FailoverGeocoder(Photon(http), Nominatim(http)) { Nominatim.INFO.id !in settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).first() }
    }
    val mangrove: Mangrove by lazy { Mangrove(http) }

    /** Ratings from Mangrove (open, no key) and the keyed providers the user enabled with a key. */
    val ratings: RatingsAggregator by lazy {
        RatingsAggregator(listOf(mangrove, FoursquareRatings(http, keys), YelpRatings(http, keys), TripAdvisorRatings(http, keys)))
    }

    /** Charger sources: OpenStreetMap through Overpass, and Open Charge Map when the user added a key. */
    val chargers: List<ChargerProvider> by lazy { listOf(OsmChargers(overpass), OpenChargeMap(http, keys)) }

    // ---- Search: saved places, Wikidata, Commons, notes (ui/search) ---------------------------------------------

    private val savedLazy = lazy { app.atlas.ui.search.SavedPlaces.open(File(dirs.files, "saved/saved.db"), clock) }

    /** Saved places and their lists, recent searches, queries kept as layers and hidden brands: on the phone only. */
    val saved: app.atlas.ui.search.SavedPlaces by savedLazy

    /** Wikidata: a place's architect, year, namesake and image, and brand facts. */
    val wikidata: app.atlas.providers.Wikidata by lazy { app.atlas.providers.Wikidata(http) }

    /** Wikimedia Commons: images with their licences. */
    val commons: app.atlas.providers.Commons by lazy { app.atlas.providers.Commons(http) }

    /** Anonymous OpenStreetMap notes: "leave a note for mappers" and the place quests. */
    val osmNotes: app.atlas.providers.OsmNotes by lazy { app.atlas.providers.OsmNotes(http) }

    /** The user's edits to OpenStreetMap, kept on this phone until they are sent (FillIn, the painter). */
    val edits: app.atlas.services.EditQueue by lazy { app.atlas.services.EditQueue.open(dirs.edits) }

    /** Edits sent with the user's own OpenStreetMap account, after their review. */
    val osmEdits: app.atlas.providers.OsmEdits by lazy {
        app.atlas.providers.OsmEdits(http, createdBy = "Atlas ${BuildConfig.VERSION_NAME}")
    }

    /**
     * The user's OpenStreetMap account: signed in in the browser, the token sealed by the Android Keystore. Switched off
     * while the app has no client id (BuildConfig.OSM_CLIENT_ID, the Gradle property atlas.osmClientId).
     */
    val osmAccount: app.atlas.services.OsmAccount by lazy {
        val id = BuildConfig.OSM_CLIENT_ID
        val redirect = app.atlas.services.OsmAccount.REDIRECT
        app.atlas.services.OsmAccount(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)) { dirs.osmAccount },
            app.atlas.services.KeystoreSealer(),
            if (id.isBlank()) null else app.atlas.services.OsmAccount.Osm(app.atlas.providers.OsmAuth(http, id, redirect), redirect),
            scope,
        )
    }

    // ---- Routing -------------------------------------------------------------------------------------------------

    /** BRouter's segment files: what is on the phone and downloads of more. */
    val segments: SegmentStore by lazy { dirs.segments.mkdirs(); SegmentStore(dirs.segments, client = http.client) }

    val brouter: BRouterRouter by lazy {
        dirs.segments.mkdirs()
        val memoryClass = this.context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 64
        // Road limits for trucks and campers, and bridge clearances and depths for boats (the water router uses this router).
        val restrictions = if (config.offline) null else OverpassRestrictionSource({ ql -> overpass.raw(ql) }, OverpassRestrictionSource.Scope.BOTH)
        // Street names for the instructions come from the map's packs, else Overpass (BRouter's data has none).
        BRouterRouter(dirs.segments, dirs.profiles, memoryClassMb = memoryClass, restrictions = restrictions, namer = localData.wayNamer())
    }

    /** Charging stops for the EV planner, from every charger source that is ready, merged. */
    val chargerSource: ChargerSource by lazy {
        ChargerSource { corridor, minKw, connectors ->
            val ready = chargers.filter { it.state() == ProviderState.READY }
            val found = coroutineScope {
                ready.map { p ->
                    async {
                        try {
                            p.along(corridor.line, corridor.bufferMetres, ChargerFilters(minKw = minKw, connectors = connectors))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            ChargerMerge.merge(found)
        }
    }

    /** Every mode: land through BRouter, water through Atlas's water router, electric vehicles through the EV planner. */
    val router: Router by lazy { MultiRouter.standard(brouter, WaterRouter(brouter), EvPlanner(brouter, chargerSource)) }

    // ---- The phone -----------------------------------------------------------------------------------------------

    val location: SwitchableLocation by lazy {
        SwitchableLocation(config.demoLocation?.let(::demoAt) ?: AndroidLocationSource(this.context, scope), scope)
    }

    /** Puts the app at a fixed demo location (CI, screenshots). */
    fun useDemoLocation(at: LatLon) = location.use(demoAt(at))

    /** A demo position whose fixes carry the app's time, so a fixed test clock and the fixes agree. */
    private fun demoAt(at: LatLon) = DemoLocationSource(at, clock = { clock.instant() })

    /** The voice of the guide: the phone's TextToSpeech, earcons and vibrations (the go package's [app.atlas.ui.go.TtsVoice]). */
    val voice: Voice by lazy { app.atlas.ui.go.TtsVoice.forApp(this.context) }

    /** Routes and guidance: the trip being guided, Ears only, and what the Go tab keeps (the go package). */
    val go: app.atlas.ui.go.GoHub by lazy { app.atlas.ui.go.GoHub(this) }

    /** The mine package's long-lived part: offline regions, where you have been, the barometer's log, the voice settings. */
    val mine: app.atlas.ui.mine.MineHub by lazy { app.atlas.ui.mine.MineHub(this) }

    /** The local package's long-lived part: the space station's orbit and passes, the zoom ladder's pictures, the waste calendar. */
    val local: app.atlas.ui.local.LocalHub by lazy { app.atlas.ui.local.LocalHub(this) }

    /** The offline regions: map, places, routing and voice layers, downloaded in the background (the mine package). */
    val regions: RegionManager by lazy { mine.offline }

    // ---- The map's engine ----------------------------------------------------------------------------------------

    private val mapDataLazy = lazy { app.atlas.services.MapData(this) }

    /** The map's region packs and the live moment's forecast, wired to every map (the 1:1 engine draws from them). */
    val mapData: app.atlas.services.MapData by mapDataLazy

    // ---- Features on Atlas's own data ----------------------------------------------------------------------------

    private val localDataLazy = lazy {
        val shelf = app.atlas.services.PackShelf({ mapData.packs.local().map { it.file } })
        app.atlas.services.LocalData(
            stores = { placeStores },
            packs = shelf,
            overpass = { if (config.offline) null else overpass },
            isOnline = { !config.offline && connectivity.online.value },
            onClose = shelf::close,
        )
    }

    /**
     * The features' data, offline first: the downloaded regions' place stores, the map's own packs (the bundled
     * Westerkerk, the renders', city, country and Overpass-built ones), then Overpass online where nothing covers.
     */
    val localData: app.atlas.services.LocalData by localDataLazy

    /** CI's demo (`atlas.ci`) and the bench shortcut (`atlas.bench`): the home opens on the Westerkerk. */
    @Volatile
    var ciDemo: Boolean = false

    /**
     * CI's run (`atlas.ci`): the phone builds no windows, so the emulator asks no server for them; its map is the canal
     * ring CI puts on the device (MainActivity.BENCH_PACKS), as no APK carries a map.
     */
    @Volatile
    var ciRun: Boolean = false

    /**
     * CI's light (`atlas.ci.theme`): the home's map in this theme whatever the settings say, so two launches compared
     * pixel for pixel draw the same moment instead of whichever the live forecast gave each by then.
     */
    @Volatile
    var ciTheme: app.atlas.mapview.Theme? = null

    init {
        if (config.mapEngine) mapData.wire()
    }

    override fun close() {
        scope.cancel()
        synchronized(openStores) { openStores.values.forEach { runCatching { it.close() } } }
        if (savedLazy.isInitialized()) runCatching { saved.close() }
        if (localDataLazy.isInitialized()) runCatching { localData.close() }
    }

    companion object {
        /** The Westerkerk, Amsterdam: the CI demo location and the first view before any fix. */
        val WESTERKERK = LatLon(52.37452, 4.88377)
    }
}
