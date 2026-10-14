package app.atlas

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.ChargerFilters
import app.atlas.providers.ChargerMerge
import app.atlas.providers.ChargerProvider
import app.atlas.providers.FoursquareRatings
import app.atlas.providers.Geocoder
import app.atlas.providers.Http
import app.atlas.providers.Mangrove
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
import app.atlas.services.LibraryRegionManager
import app.atlas.services.AndroidLocationSource
import app.atlas.services.NoVoice
import app.atlas.services.PlaceCache
import app.atlas.services.PlaceFinder
import app.atlas.services.RegionManager
import app.atlas.services.SavedCamera
import app.atlas.services.SettingsStore
import app.atlas.services.SwitchableLocation
import app.atlas.services.SystemConnectivity
import app.atlas.services.Voice
import app.atlas.tiles.MbTiles
import app.atlas.tiles.OpenFreeMapSource
import app.atlas.tiles.RegionDownloader
import app.atlas.tiles.RegionLibrary
import app.atlas.tiles.TileCache
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
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
import kotlinx.coroutines.delay
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
    /** Never touch the network: no online tiles, no providers; screens show their offline states. */
    val offline: Boolean = false,
    /** Tiles to draw instead of the offline packs and OpenFreeMap (tests: the fixture tiles). */
    val tiles: TileSource? = null,
    /** Place stores to search instead of the downloaded regions' (tests: the Amsterdam extract). */
    val placeStores: List<PlaceSource>? = null,
    /** The time and time zone screens show and reason with (tests: a fixed evening in Amsterdam). */
    val clock: Clock = Clock.systemDefaultZone(),
)

/** The folders Atlas keeps its data in. */
class Dirs(val files: File, val cache: File) {
    val settings = File(files, "settings/settings.preferences_pb")
    val keys = File(files, "settings/keys.preferences_pb")
    val regions = File(files, "regions")
    val tiles = File(cache, "tiles")
    val places = File(files, "places")
    val segments = File(files, "routing/segments")
    val profiles = File(files, "routing/profiles")
    val http = File(cache, "http")
}

/**
 * Every long-lived object of the app, built lazily on first use, by hand (no DI framework): settings and keys, HTTP,
 * the tile stack (offline packs, the cache, OpenFreeMap), places, routing, weather, ratings, chargers, location, the
 * voice and the offline regions. Screens reach it through `LocalServices`; features add their own lazily built
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

    // ---- Tiles --------------------------------------------------------------------------------------------------

    private val tileCacheLazy = lazy { dirs.tiles.mkdirs(); TileCache(File(dirs.tiles, "cache.mbtiles")) }
    val tileCache: TileCache by tileCacheLazy

    /** OpenFreeMap online, reading through [tileCache]; it shares the HTTP pool but not its disk cache (tiles have their own). */
    val openFreeMap: OpenFreeMapSource by lazy { OpenFreeMapSource(cache = tileCache, client = http.client.newBuilder().cache(null).build()) }

    val regionLibrary: RegionLibrary by lazy { RegionLibrary(dirs.regions) }

    private val tileState = MutableStateFlow(config.tiles)
    /** The packs in the published stack; guarded by [tileState]. */
    private var openPacks: List<MbTiles> = emptyList()

    /** What the map draws: offline packs first, then OpenFreeMap through the cache. Null until built (a few ms). */
    val tiles: StateFlow<TileSource?> by lazy {
        if (config.tiles == null) scope.launch(Dispatchers.IO) { rebuildTiles() }
        tileState.asStateFlow()
    }

    private val regionVersion = MutableStateFlow(0)

    /** Bumped whenever offline files change, so anything built from them can rebuild. */
    val regionsChanged: StateFlow<Int> = regionVersion.asStateFlow()

    /** Tells every service that offline files changed (a region downloaded, updated or deleted). */
    fun reloadRegions() {
        if (config.tiles == null) scope.launch(Dispatchers.IO) { rebuildTiles() }
        if (config.placeStores == null) scope.launch(Dispatchers.IO) { syncPlaceStores(placeStores) }
        regionVersion.value++
    }

    /**
     * Opens the packs on the phone and publishes a new stack. Serialised: two rebuilds at once (two regions finishing
     * together) must not publish one stack while closing its packs as the other's "old" ones.
     */
    private fun rebuildTiles() = synchronized(tileState) {
        val packs = regionLibrary.openPacks()
        val sources = buildList<TileSource> {
            addAll(packs)
            if (!config.offline) add(openFreeMap) else add(tileCache)
        }
        val old = openPacks
        openPacks = packs
        tileState.value = TileStack(sources)
        // Readers of the old stack may still be mid-tile: close its packs a little later.
        scope.launch {
            delay(10_000)
            old.forEach { runCatching { it.close() } }
        }
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

    /** Nearby places by category (the Map home's row and needs): the place stores, else the tiles, then online. */
    val places: PlaceFinder by lazy {
        LayeredPlaceFinder(
            tiles = { tiles.value },
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
    val geocoder: Geocoder by lazy { Photon(http) }
    val mangrove: Mangrove by lazy { Mangrove(http) }

    /** Ratings from Mangrove (open, no key) and the keyed providers the user enabled with a key. */
    val ratings: RatingsAggregator by lazy {
        RatingsAggregator(listOf(mangrove, FoursquareRatings(http, keys), YelpRatings(http, keys), TripAdvisorRatings(http, keys)))
    }

    /** Charger sources: OpenStreetMap through Overpass, and Open Charge Map when the user added a key. */
    val chargers: List<ChargerProvider> by lazy { listOf(OsmChargers(overpass), OpenChargeMap(http, keys)) }

    // ---- Routing -------------------------------------------------------------------------------------------------

    /** BRouter's segment files: what is on the phone and downloads of more. */
    val segments: SegmentStore by lazy { dirs.segments.mkdirs(); SegmentStore(dirs.segments, client = http.client) }

    val brouter: BRouterRouter by lazy {
        dirs.segments.mkdirs()
        val memoryClass = this.context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 64
        val restrictions = if (config.offline) null else OverpassRestrictionSource({ ql -> overpass.raw(ql) })
        BRouterRouter(dirs.segments, dirs.profiles, memoryClassMb = memoryClass, restrictions = restrictions)
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

    /** The voice of the guide; the go package provides the TextToSpeech implementation. */
    val voice: Voice by lazy { NoVoice() }

    /** The offline regions; the mine package provides the full manager. */
    val regions: RegionManager by lazy {
        LibraryRegionManager(regionLibrary, { RegionDownloader(openFreeMap) }, scope, ::reloadRegions)
    }

    override fun close() {
        scope.cancel()
        synchronized(tileState) { openPacks.forEach { runCatching { it.close() } } }
        synchronized(openStores) { openStores.values.forEach { runCatching { it.close() } } }
        if (tileCacheLazy.isInitialized()) runCatching { tileCache.close() }
    }

    companion object {
        /** The Westerkerk, Amsterdam: the CI demo location and the first view before any fix. */
        val WESTERKERK = LatLon(52.37452, 4.88377)
    }
}
