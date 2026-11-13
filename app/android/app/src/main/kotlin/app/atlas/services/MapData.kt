package app.atlas.services

import androidx.datastore.preferences.core.emptyPreferences
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.UserEdit
import app.atlas.mapview.CountryTiles
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapParts
import app.atlas.mapview.MapWeather
import app.atlas.mapview.vk.WorldKeeper
import app.atlas.mapview.vk.WorldReads
import app.atlas.providers.Forecast
import app.atlas.providers.Overpass
import app.atlas.providers.SwitchedOffException
import app.atlas.render.data.HttpPost
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.RegionBuilder
import app.atlas.render.data.inTurns
import app.atlas.render.live.HttpGet
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.WxHour
import app.atlas.tiles.PackCatalog
import app.atlas.tiles.WindowPacks
import app.atlas.tiles.WindowTiles
import app.atlas.tiles.WorldFetch
import app.atlas.ui.mine.MineKeys
import app.atlas.ui.mine.offline.RegionFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The map's data: the sources of the windows the core draws (ENGINE.md step 3: a country's tiles, Atlas's window packs,
 * the element packs on the phone, Overpass), the world beyond the countries, and the forecast that lights the live
 * moment. [wire] hands them to every map ([MapEngine]).
 */
class MapData(private val services: Services) {
    /** The world on the phone changed (a start found it, a new version came, its keep list came). */
    private val landed = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** No call timeout: a region pack is megabytes, an Overpass answer can take minutes. */
    private val client: OkHttpClient by lazy {
        services.http.client.newBuilder().cache(null).callTimeout(0, TimeUnit.SECONDS).readTimeout(330, TimeUnit.SECONDS).build()
    }

    private val get = HttpGet { url ->
        val res = client.newCall(Request.Builder().url(url).header("User-Agent", services.http.userAgent).build()).execute()
        if (!res.isSuccessful) {
            res.close()
            throw IOException("$url answered ${res.code}")
        }
        val body = res.body
        body.byteStream() to body.contentLength()
    }

    private val post = HttpPost { url, form, agent ->
        val b = FormBody.Builder()
        for ((k, v) in form) b.add(k, v)
        val res = client.newCall(Request.Builder().url(url).header("User-Agent", agent).post(b.build()).build()).execute()
        if (!res.isSuccessful) {
            val msg = runCatching { res.body.string().take(200) }.getOrDefault("")
            res.close()
            throw IOException("$url answered ${res.code} $msg")
        }
        res.body.byteStream()
    }

    /**
     * The element packs on the phone and the published ones (`files/maps`), and the offline regions' own packs where the
     * regions keep them (`files/regions/atlas`, read again when the regions change: [Services.reloadRegions]): the
     * features' data, and the windows' where nothing else gives a window's ([localWindow]). The bundled pack is installed
     * on first use (a copy of 627 KB, once), on whichever background thread asks first, before anything is chosen. No
     * region's render pack is fetched beside its element pack (`renders` off): the core draws the windows alone
     * (ENGINE.md step 3, one pack per country).
     */
    val packs: PackLibrary by lazy {
        val regionPacks = File(services.dirs.regions, "atlas")
        PackLibrary(
            File(services.dirs.files, "maps"), listOf(PackLibrary.RENDERS), get, renders = false,
            elsewhere = { regionPacks.listFiles { f -> f.isFile && f.name.endsWith(PackLibrary.EXT) }.orEmpty().toList() },
        ).also { lib ->
            try {
                lib.install(BUNDLED) { open(BUNDLED_ASSET) }
            } catch (_: IOException) {
                // no bundled pack in this build: the published ones still come
            }
        }
    }

    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "atlas-map-data").apply { isDaemon = true } }

    private val OVERPASS_HOSTS = app.atlas.providers.Overpass.ENDPOINTS.map { it.removeSuffix("/api/interpreter") }

    private fun online(): Boolean = !services.config.offline && services.connectivity.online.value

    /** Throws where the user switched Overpass off (Settings · Data): the windows ask none of its servers then. */
    private fun overpassAllowed() {
        if (Overpass.INFO.id in services.sourcesOff()) throw SwitchedOffException(Overpass.INFO.id, Overpass.INFO.hosts.first())
    }

    /** Where the packs' list (packs-latest's packs.json) and their indexes are kept for offline use: in the app's files. */
    private val packsIndexDir: File by lazy { File(services.dirs.engine, "windows/index") }

    /** Atlas's published packs, as the windows read them: packs-latest's list, the last good copy kept. */
    private val windowCatalog: PackCatalog by lazy {
        PackCatalog(PackCatalog.DEFAULT_URL, services.http.client, File(packsIndexDir, "packs-latest.json"))
    }

    /** The windows' data in Atlas's own packs, read on the windows' own thread. */
    private val windowPacks: WindowPacks by lazy {
        WindowPacks({ runBlocking { windowCatalog.packs() } }, packsIndexDir, services.http.client)
    }

    /**
     * Window ([x], [y])'s data in Atlas's own packs (Overpass's answer for its box, gzipped), for "What's here" where the
     * phone holds nothing for a point: null where no pack holds it. Blocks; throws IOException where its bytes did not
     * come whole.
     */
    fun packedWindow(x: Int, y: Int): ByteArray? = windowPacks.window(WindowPacks.LEVEL, x, y, null)?.gz

    /**
     * The countries' tiles in Atlas's own packs (their indexes kept beside the windows' data's), read on the windows' own
     * thread.
     */
    private val windowTiles: WindowTiles by lazy {
        WindowTiles({ runBlocking { windowCatalog.packs() } }, packsIndexDir, services.http.client, local = { _, set ->
            set.tiles.sha256?.let(regionFiles::keptTiles)?.let { (tiles, index) -> WindowTiles.Local(tiles, index) }
        })
    }

    /** When each country's tiles' index was last asked for ([countryHolds]), by its id, ms of the uptime clock. */
    private val indexAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Whether country [id]'s tiles hold the place at [lat], [lon] (MapEngine.countryHolds), from its tiles' index in
     * memory; null until it is read. The first camera within a country's box has the index read in the background (from
     * the windows' folder, from the country kept whole, else downloaded: the Netherlands' is 1.5 MB), and the maps
     * drawn again once it is; where it does not come it is asked for again a minute on. CI's runs ask no server for it
     * (`atlas.ci`), but for the world phase (`atlas.ci.world`). On the main thread at each camera: the answer from
     * memory allocates nothing.
     */
    private fun countryHolds(id: String, lat: Double, lon: Double): Boolean? {
        val version = MapEngine.windowVersion() ?: return null
        windowTiles.holds(id, version, lat, lon)?.let { return it }
        if (services.ciRun && !services.worldFetch.isCompleted) return null
        val now = android.os.SystemClock.uptimeMillis()
        val asked = indexAsked[id]
        if (asked != null && now - asked < COUNTRIES_RETRY_MS) return null
        indexAsked[id] = now
        services.scope.launch(Dispatchers.IO) {
            try {
                val tiles = windowTiles.index(id, version) ?: return@launch
                android.util.Log.i("AtlasMap", "vk windows: the tiles' index of $id on the phone, $tiles tiles: beyond them no levels, the world under the cover")
                MapEngine.invalidate()
            } catch (e: IOException) {
                android.util.Log.w("AtlasMap", "vk windows: the tiles' index of $id did not come: ${e.message}")
            }
        }
        return null
    }

    /** The offline regions' files, read here for a country's tiles kept whole (the Maps board's download of a country). */
    private val regionFiles by lazy { RegionFiles(services.dirs.regions, services.dirs.places, services.dirs.segments) }

    /**
     * Overpass for the map's builds: the same servers, in the same order, as the app's own client, so no build depends on
     * one server; the same record of what they did lately ([Services.overpassServers]), so a server either found down
     * rests for both; and each request in its server's turn of the app's limiter, so the two run one query at a time on
     * each server between them.
     */
    private val overpass: OverpassClient by lazy {
        OverpassClient(post.inTurns(services.http.limiter::turn), hosts = OVERPASS_HOSTS, servers = services.overpassServers)
    }

    /**
     * Window [w]'s data from the element packs on the phone ([packs]; ENGINE.md step 3, the windows' local lane): those
     * that meet its box ([PackWindows]); null where none holds any of it. Where none meets it, the published pack
     * holding the window's middle is asked for ([publishedPacks]), on a job of its own: this window does not wait
     * for it (its data comes as any window's meanwhile), and the windows the phone has nothing of once the pack is
     * there come from it. Reads the phone's files alone, on the windows' own thread: nothing of the network.
     */
    private fun localWindow(w: MapCore.Window): app.atlas.mapview.vk.Windows.Packed? {
        val b = w.box()
        val box = BBox(b[1], b[0], b[3], b[2])
        val here = packs.local().filter { it.bbox.intersects(box) }
        if (here.isEmpty()) {
            publishedPacks.want(box.center)
            return null
        }
        return PackWindows.answer(here.map { it.file }, box)
    }

    /** The Maps board's "Wi-Fi only" as last read (on until the settings say otherwise): what a download on mobile data waits for. */
    @Volatile
    private var wifiOnly = true

    /**
     * The published packs the map's places ask for ([localWindow]), fetched off the windows' step and only on a network
     * the user lets downloads use: any that is not metered, and mobile data once the Maps board's "Wi-Fi only" is off.
     */
    private val publishedPacks: PublishedPacks by lazy {
        PublishedPacks(
            packs,
            allowed = { online() && (!services.connectivity.metered.value || !wifiOnly) },
            run = worker::execute,
            changed = { MapEngine.invalidate() },
            said = { android.util.Log.i("AtlasMap", "vk packs: $it") },
        )
    }

    /**
     * Builds the map pack of an offline region on the phone into [target], as the map builds the places it shows: the
     * renders' query, from every server in turn, a box too big for one answer asked in parts. Returns its size. Blocks:
     * call it off the main thread.
     */
    fun buildRegion(id: String, name: String, bbox: BBox, target: File): Long {
        RegionBuilder.buildFromOverpass(overpass, id, name, bbox, target)
        return target.length()
    }

    /** The forecast for the live moment: the last one fetched near the place, refreshed in the background. */
    val weather: MapWeather = Forecasts()

    /** Hands the map engine its data. */
    fun wire() {
        // the windows the phone took or built, which the map draws offline: in the app's files, never cleared by Android
        if (services.config.root == null) MapEngine.useKeptDir(services.dirs.engine)
        MapEngine.weather = weather
        // the windows the phone builds (ENGINE.md step 3): their data from the same Overpass servers, from one whose data
        // is of the last day where one answers; built again after a week from the changes since. Where the user switched
        // Overpass off, none is asked (and none rested for it), and the map says so.
        MapEngine.windowData = { b, file ->
            overpassAllowed()
            overpass.window(app.atlas.geo.BBox(b[1], b[0], b[3], b[2]), file, freshAfter = java.time.Instant.now().minus(java.time.Duration.ofDays(1)))
        }
        MapEngine.windowDiff = { b, since, notBefore ->
            overpassAllowed()
            overpass.windowDiff(app.atlas.geo.BBox(b[1], b[0], b[3], b[2]), since, java.time.Instant.parse(notBefore))
        }
        MapEngine.overpassOff = { Overpass.INFO.id in services.sourcesOff() }
        // and before them, Atlas's own packs, built on GitHub from Geofabrik's extracts, where one holds the window
        MapEngine.windowPacks = { w, after ->
            windowPacks.window(w.z, w.x, w.y, after)?.let { app.atlas.mapview.vk.Windows.Packed(it.gz, it.date, it.pack) }
        }
        // and before any data, a window the phone has none of from its country's tiles (ENGINE.md step 3, one pack per
        // country), where the phone's own core built them
        MapEngine.windowTiles = { w ->
            windowTiles.window(w.z, w.x, w.y, MapCore.Window.version())?.let { app.atlas.mapview.vk.Windows.Tiled(it.bytes, it.date, it.pack) }
        }
        // and where neither gives a window's data, the element packs on the phone (an offline region's map, a place's
        // published pack fetched for it): before Overpass, and offline too
        MapEngine.windowLocal = { w -> localWindow(w) }
        MapEngine.localKept = { packs.local().isNotEmpty() }
        // a published pack on its way for such a place, for the map's line; taken up as the network or the Maps board's
        // "Wi-Fi only" changes (it never comes over mobile data while that is on)
        MapEngine.packComing = {
            publishedPacks.coming?.let { c -> MapEngine.PackComing(c.name, c.bbox.south, c.bbox.west, c.bbox.north, c.bbox.east, c.done, c.bytes) }
        }
        services.scope.launch {
            combine(services.settings.flow(MineKeys.WIFI_ONLY, true), services.connectivity.online, services.connectivity.metered) { wifi, _, _ -> wifi }
                .collect {
                    wifiOnly = it
                    publishedPacks.wake()
                }
        }
        // a country downloaded whole (the Maps board): its windows come from it offline too
        MapEngine.tilesKept = { regionFiles.keepsTiles() }
        // and the windows taken from it are the first the windows' budget lets go: they come back from the phone at once
        MapEngine.windowsKept = { windowTiles.keptPacks(MapCore.Window.version()) }
        // the countries whose tiles the core draws, from the published list (its copy kept, offline), read off the main
        // thread: within them the map never hands a place to today's map while its window comes (MapEngine.countries);
        // asked again a minute on while no list is to be had
        services.scope.launch(Dispatchers.IO) {
            while (true) {
                val list = runCatching { windowCatalog.packs() }.getOrNull()
                if (list != null) {
                    // which tiles this phone takes: none of its core's version means every window is built here, and
                    // no frame cell's levels are drawn (they come from those tiles alone)
                    val version = MapEngine.windowVersion()
                    MapEngine.countries = list.filter { it.tileSets.isNotEmpty() }.map { p ->
                        CountryTiles(p.id, p.name, p.bbox, levels = version != null && p.tilesOf(version) != null)
                    }
                    MapEngine.invalidate()
                    val sets = list.flatMap { p -> p.tileSets.map { "${p.id} ${it.index.coreVersion}" } }
                    val own = list.filter { p -> version != null && p.tilesOf(version) != null }.map { it.id }
                    android.util.Log.i(
                        "AtlasMap",
                        "vk windows: this core builds ${version ?: "?"}; tiles published: ${sets.joinToString().ifEmpty { "none" }}; " +
                            "its own: ${own.joinToString().ifEmpty { "none, so every window is built on the phone" }}",
                    )
                    break
                }
                delay(COUNTRIES_RETRY_MS)
            }
        }
        // which places within a country's box its tiles hold, as their index lists them: beyond its border (Antwerp and
        // Aachen in the Netherlands' box) no country's levels are, and the world draws under the cover
        MapEngine.countryHolds = MapEngine.CountryHolds { c, lat, lon -> countryHolds(c.id, lat, lon) }
        MapEngine.windowsAllowed = { !services.ciRun }
        // the world beyond the countries' reach (ENGINE.md step 6): world.json from world-latest at most once a day, the
        // index and the globe of its release at the first start and each new version (a new one drops the old one and
        // the world's cache), then each pack's head and its tiles' chunks by ranges as the maps' views want them
        MapEngine.worldDir()?.let { wireWorld(it) }
        // the user's own edits drawn as mapped until the map's data carries them (ENGINE.md step 6): those OpenStreetMap
        // took; one still waiting shows as waiting (EditMarkers)
        MapEngine.windowEdits = {
            services.edits.mapped.map { q -> UserEdit(q.edit.type.api, q.edit.id, q.edit.set, q.edit.seen) }
        }
        // the screen's haze, vignette and grain as the Look settings have them (on when unset: screenEffectsOn), handed
        // to the maps before the settings are read, so the first frame draws the defaults, and again as they change
        screenEffectsOn(emptyPreferences()).let { (haze, vignette, grain) -> MapEngine.screenEffects(haze, vignette, grain) }
        services.scope.launch {
            services.settings.screenEffects.collect { (haze, vignette, grain) -> MapEngine.screenEffects(haze, vignette, grain) }
        }
        // the Map look's Tone and palette, as the user set them; unset, alpha 22's look for now (the Tone off and the
        // Alpha 22 palette: mapTone, mapPalette), handed to MapEngine before the settings are read, as the parts are
        // below, so the first frame draws the defaults
        MapEngine.tone(mapTone(emptyPreferences()))
        MapEngine.palette(DEFAULT_PALETTE)
        services.scope.launch {
            services.settings.data
                .map { mapTone(it) }
                .distinctUntilChanged()
                .collect { s -> MapEngine.tone(s) }
        }
        services.scope.launch {
            services.settings.flow(SettingsKeys.MAP_PALETTE).collect { code -> MapEngine.palette(mapPalette(code)) }
        }
        // a debug build's frame pacing, as the Look settings have it (off when unset)
        services.scope.launch {
            services.settings.flow(SettingsKeys.MAP_PACING, false).collect { on -> MapEngine.pacing(on) }
        }
        // a debug build's screen compression, as the Look settings have it (the driver's when unset)
        services.scope.launch {
            services.settings.flow(SettingsKeys.MAP_COMPRESSION, true).collect { on -> MapEngine.compression(on) }
        }
        // the Map look's Fast refresh, as the Look settings have it (on when unset)
        services.scope.launch {
            services.settings.fastRefresh.collect { on -> MapEngine.fastRefresh(on) }
        }
        // the core's parts a debug build's Look settings switch, every layer and effect, so the map's panel shows their
        // cost: every part but the plinths, the quays and the water's light but its reflections when unset
        // (MapParts.byDefault); set before the settings are read, so the first frame draws what the defaults say
        MapEngine.leaveOut(MapParts.byDefault)
        services.scope.launch {
            services.settings.partsOff.collect { off -> MapEngine.leaveOut(off) }
        }
    }

    /**
     * The world's fetch, once a launch of the map allows it (Services.worldFetch: not CI's runs but its world phase):
     * world.json, the index and the globe as soon as the phone is online and again each day (WorldFetch.follow), and
     * the packs' heads and chunks as the maps' views want them (MapEngine.worldReads, a step a map asks for twice a
     * second). The world's cache within the budget the user set (256 MB when unset). `vk world` lines say what came
     * (CI's world phase reads them).
     */
    private fun wireWorld(dir: File) {
        services.scope.launch {
            services.settings.flow(SettingsKeys.WORLD_CACHE_MB).collect { mb ->
                MapEngine.worldCacheBytes = maxOf(MapEngine.WORLD_CACHE_BYTES, (mb ?: 0).toLong() shl 20)
            }
        }
        // the world's names' faces: the boards' Instrument Serif italic, and the Globe board's Geist (one variable
        // font file: the names' painter sets its weight to 700)
        MapEngine.nameTypeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(services.context, app.atlas.R.font.instrument_serif_italic) }.getOrNull()
        MapEngine.globeTypeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(services.context, app.atlas.R.font.geist) }.getOrNull()
        val said: (String) -> Unit = { line -> android.util.Log.i("AtlasMap", "vk world: $line") }
        // a new version drops the old one's heads and chunks, unless a country is kept by its tiles: then it waits
        // beside the old one until the keeper holds its kept world whole (ENGINE.md step 11: no kept world lost offline)
        val world = WorldFetch(
            dir, client = services.http.client,
            drop = {
                MapEngine.worldCacheDir()?.let { cache ->
                    val why = MapCore.worldDrop(cache.path, MapEngine.worldCacheBytes)
                    said(if (why.isEmpty()) "the old version's heads and chunks dropped" else "the old version's heads and chunks not dropped: $why")
                }
            },
            hold = { services.mine.engine.keepsWorld() },
        )
        services.scope.launch(Dispatchers.IO) {
            services.worldFetch.await()
            val cache = MapEngine.worldCacheDir() ?: return@launch
            MapEngine.worldReads = WorldReads(world, cache, budget = { MapEngine.worldCacheBytes }, online = { services.connectivity.online.value }, said = said)
            MapEngine.worldKeeper = WorldKeeper(world, cache, budget = { MapEngine.worldCacheBytes }, said = said, adopted = { MapEngine.invalidate() })
            launch { keepWorld(landed, said) }
            world.follow(services.connectivity.online, said = said, landed = {
                MapEngine.invalidate()
                landed.tryEmit(Unit)
            })
        }
    }

    /**
     * The world of the countries kept by their tiles (ENGINE.md step 11), looked at each time the world on the phone
     * changes and as the network changes: what they keep is pinned in the world's cache at once, and what the phone
     * lacks of it (after a new version of the world, or a download that stopped) is fetched while the phone is online
     * and its network is not metered, as a region's download waits for Wi-Fi; a fetch that fails is tried again
     * [KEEP_AGAIN_MS] on. A new version of the world waits beside the phone's until its keep is whole, and is taken
     * then (WorldKeeper), so the phone keeps drawing the old one, offline too, however long that fetch waits. A
     * country's own download keeps its world as it takes its tiles (RegionEngine).
     */
    private suspend fun keepWorld(landed: Flow<Unit>, said: (String) -> Unit) {
        combine(landed, services.connectivity.online, services.connectivity.metered) { _, online, metered -> online && !metered }
            .collectLatest { fetch ->
                while (true) {
                    val done = runCatching { runInterruptible(Dispatchers.IO) { services.mine.engine.keepWorld(fetch) } }
                    val failed = done.exceptionOrNull()
                    if (failed is CancellationException) throw failed
                    if (failed == null || !fetch) break
                    said("the kept countries' world not whole yet: ${failed.message}")
                    delay(KEEP_AGAIN_MS)
                }
            }
    }

    private fun open(asset: String): InputStream = services.context.assets.open(asset)

    private inner class Forecasts : MapWeather {
        @Volatile
        private var kept: Forecast? = null

        @Volatile
        private var asked = 0L

        override fun hours(at: LatLon): List<WxHour>? {
            val f = kept
            val now = System.currentTimeMillis()
            val near = f != null && Geo.distance(f.location, at) < NEAR_M
            val fresh = near && now - f!!.fetchedAt.toEpochMilli() < FRESH_MS
            if (!fresh && now - asked > ASK_MS) {
                asked = now
                services.scope.launch {
                    val got = runCatching { if (online()) services.weather.forecast(at) else services.weather.kept(at) }.getOrNull()
                    if (got != null) {
                        kept = got
                        MapEngine.invalidate()
                    }
                }
            }
            return if (near) f!!.hours.map(::hour) else null
        }

        /** The kept forecast's own zone where it is of a place near [at] (Open-Meteo's `timezone`, else its offset). */
        override fun zone(at: LatLon): java.time.ZoneId? {
            val f = kept ?: return null
            if (Geo.distance(f.location, at) >= NEAR_M) return null
            return runCatching { java.time.ZoneId.of(f.timezone) }.getOrElse { java.time.ZoneOffset.ofTotalSeconds(f.utcOffsetSeconds) }
        }

        private fun hour(h: app.atlas.providers.WeatherHour) = WxHour(
            h.time, h.cloudCover, h.precipitation, h.windSpeed, h.windDirection, h.windGusts, h.temperature, h.pressureMsl, h.visibility, h.code,
        )
    }

    companion object {
        const val BUNDLED = "amsterdam"
        const val BUNDLED_ASSET = "packs/amsterdam.atlas"
        private const val NEAR_M = 25_000.0
        private const val FRESH_MS = 3_600_000L
        private const val ASK_MS = 300_000L

        /** How long the map waits to ask for the published list again where none was to be had (ms). */
        private const val COUNTRIES_RETRY_MS = 60_000L

        /** A fetch of the kept countries' world that failed is tried again this long on. */
        private const val KEEP_AGAIN_MS = 600_000L
    }
}
