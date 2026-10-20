package app.atlas.services

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapWeather
import app.atlas.providers.Forecast
import app.atlas.render.data.HttpPost
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.RegionBuilder
import app.atlas.render.live.HttpGet
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.WxHour
import kotlinx.coroutines.launch
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
     * The packs on the phone and the published ones (`files/maps`), and the offline regions' own packs where the regions
     * keep them (`files/regions/atlas`, read again when the regions change: [Services.reloadRegions]). The bundled pack
     * is installed on first use (a copy of 627 KB, once), on whichever background thread asks first, before anything is
     * chosen. The render packs the APK carries ([BundledRenders]) are never downloaded again: the canal ring's first
     * visit fetches its element pack only.
     */
    val packs: PackLibrary by lazy {
        val bundledRenders = BundledRenders.read { open(BundledRenders.ASSET) }
        val regionPacks = File(services.dirs.regions, "atlas")
        PackLibrary(
            File(services.dirs.files, "maps"), listOf(PackLibrary.RENDERS), get, bundledRenders = bundledRenders,
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

    /** Overpass for the map's builds: the same servers, in the same order, as the app's own client, so no build depends on one server. */
    private val overpass: OverpassClient by lazy { OverpassClient(post, hosts = OVERPASS_HOSTS) }

    /** Which region a view draws, and getting it ready. */
    val regions: LiveRegions by lazy {
        LiveRegions(packs, worker, online = ::online, overpass = overpass, onChange = { MapEngine.invalidate() })
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
    }
}
