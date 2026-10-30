package app.atlas.services

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
import app.atlas.providers.Forecast
import app.atlas.providers.Overpass
import app.atlas.providers.SwitchedOffException
import app.atlas.render.data.HttpPost
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.RegionBuilder
import app.atlas.render.live.HttpGet
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.WxHour
import app.atlas.tiles.PackCatalog
import app.atlas.tiles.WindowPacks
import app.atlas.tiles.WindowTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The map's data: the engine's region packs (the Westerkerk bundled in the app, the renders' packs downloaded when the
 * camera is in their box, packs built here from Overpass where none reaches), kept in the app's files and used offline;
 * and the forecast that lights the live moment. [wire] hands them to every map ([MapEngine]).
 */
class MapData(private val services: Services) {
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
     * regions keep them (`files/regions/atlas`, read again when the regions change: [Services.reloadRegions]): today's
     * map's and the features' data. The bundled pack is installed on first use (a copy of 627 KB, once), on whichever
     * background thread asks first, before anything is chosen. No region's render pack is fetched beside its element
     * pack (`renders` off): the core draws the windows alone (ENGINE.md step 3, one pack per country).
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

    /** Where the packs' list (packs-latest's packs.json) and their indexes are kept for offline use. */
    private val packsIndexDir: File by lazy { File(services.dirs.cache, "engine/windows/index") }

    /** Atlas's published packs, as the windows read them: packs-latest's list, the last good copy kept. */
    private val windowCatalog: PackCatalog by lazy {
        PackCatalog(PackCatalog.DEFAULT_URL, services.http.client, File(packsIndexDir, "packs-latest.json"))
    }

    /** The windows' data in Atlas's own packs, read on the windows' own thread. */
    private val windowPacks: WindowPacks by lazy {
        WindowPacks({ runBlocking { windowCatalog.packs() } }, packsIndexDir, services.http.client)
    }

    /**
     * The countries' tiles in Atlas's own packs (their indexes kept beside the windows' data's), read on the windows' own
     * thread.
     */
    private val windowTiles: WindowTiles by lazy {
        WindowTiles({ runBlocking { windowCatalog.packs() } }, packsIndexDir, services.http.client)
    }

    /** Overpass for the map's builds: the same servers, in the same order, as the app's own client, so no build depends on one server. */
    private val overpass: OverpassClient by lazy { OverpassClient(post, hosts = OVERPASS_HOSTS) }

    /**
     * Which region a view draws, and getting it ready. Today's map builds no area of its own from Overpass any more
     * (null): the core's windows build every place (ENGINE.md step 3), and a second build of the same streets only
     * asked the same busy servers twice and showed "building 0 of 0 KB" where it never came.
     */
    val regions: LiveRegions by lazy {
        LiveRegions(packs, worker, online = ::online, overpass = null, onChange = { MapEngine.invalidate() })
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
        LiveEngine.workDir = File(services.dirs.cache, "engine")
        // the CPU filters' noise, kept across frames and launches and warmed up for the screen now (in the app's own
        // cache, not in a test's folder)
        if (services.config.root == null) MapEngine.useCacheDir(File(services.dirs.cache, "engine"))
        MapEngine.weather = weather
        MapEngine.regions = regions
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
        // the countries whose tiles the core draws, from the published list (its copy kept, offline), read off the main
        // thread: within them the map never hands a place to today's map while its window comes (MapEngine.countries);
        // asked again a minute on while no list is to be had
        services.scope.launch(Dispatchers.IO) {
            while (true) {
                val list = runCatching { windowCatalog.packs() }.getOrNull()
                if (list != null) {
                    MapEngine.countries = list.filter { it.tileSets.isNotEmpty() }.map { CountryTiles(it.id, it.name, it.bbox) }
                    MapEngine.invalidate()
                    break
                }
                delay(COUNTRIES_RETRY_MS)
            }
        }
        MapEngine.windowsAllowed = { !services.ciRun }
        // the user's own edits drawn as mapped until the map's data carries them (ENGINE.md step 6): pending or sent
        MapEngine.windowEdits = {
            services.edits.all.value.filter { it.state != EditQueue.State.CONFLICT }
                .map { q -> UserEdit(q.edit.type.api, q.edit.id, q.edit.set, q.edit.seen) }
        }
        // the screen's haze, vignette and grain as the Look settings have them (off when unset), handed to the maps as they
        // change
        services.scope.launch {
            services.settings.data
                .map { Triple(it[SettingsKeys.MAP_HAZE] ?: false, it[SettingsKeys.MAP_VIGNETTE] ?: false, it[SettingsKeys.MAP_GRAIN] ?: false) }
                .distinctUntilChanged()
                .collect { (haze, vignette, grain) -> MapEngine.screenEffects(haze, vignette, grain) }
        }
        // true depth or the renderer's painter's order for what stands up, as the Look settings have it
        services.scope.launch {
            services.settings.data
                .map { it[SettingsKeys.MAP_TRUE_DEPTH] ?: true }
                .distinctUntilChanged()
                .collect { trueDepth -> MapEngine.painterOrder(!trueDepth) }
        }
        // a debug build's frame pacing, as the Look settings have it (off when unset)
        services.scope.launch {
            services.settings.flow(SettingsKeys.MAP_PACING, false).collect { on -> MapEngine.pacing(on) }
        }
        // the core's parts a debug build's Look settings switch, every layer and effect, so the map's panel shows their
        // cost: every part drawn when unset (MapParts.byDefault); set before the settings are read, so the first frame
        // draws what the defaults say
        MapEngine.leaveOut(MapParts.byDefault)
        services.scope.launch {
            services.settings.partsOff.collect { off -> MapEngine.leaveOut(off) }
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
    }
}
