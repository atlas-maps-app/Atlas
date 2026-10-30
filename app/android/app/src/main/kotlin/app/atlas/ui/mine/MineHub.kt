package app.atlas.ui.mine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.atlas.Services
import app.atlas.search.PlaceStore
import app.atlas.search.RegionImport
import app.atlas.services
import app.atlas.services.RegionLayer
import app.atlas.tiles.Pack
import app.atlas.tiles.PackCatalog
import app.atlas.tiles.PackDownloader
import app.atlas.ui.mine.offline.AtlasRegionManager
import app.atlas.ui.mine.offline.AutoRegions
import app.atlas.ui.mine.offline.PickerModel
import app.atlas.ui.mine.offline.RegionDeps
import app.atlas.ui.mine.offline.RegionEngine
import app.atlas.ui.mine.offline.RegionFiles
import app.atlas.ui.mine.offline.RegionScheduler
import app.atlas.ui.mine.offline.WorkRegionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The mine package's long-lived part, one per process (`Services.mine`): the offline regions and what they download
 * with, where you have been and the barometer's log (both off until switched on), and the voice settings applied at
 * start. Built on first use; the first screen that asks for the regions starts it.
 */
class MineHub(private val services: Services) {
    val files = RegionFiles(services.dirs.regions, services.dirs.places, services.dirs.segments)

    /**
     * Atlas's published packs (packs-latest's list), the last good copy kept for offline use. The renders' list
     * (packs-renders: the regions the boards were drawn from, with their render packs) is not offered: the map is one
     * pack per country (ENGINE.md step 3), and draws no region's render pack.
     */
    val packs: PackLists by lazy {
        PackLists(
            PackCatalog(PackCatalog.DEFAULT_URL, services.http.client, File(services.dirs.regions, "packs-latest.json")),
            offline = services.config.offline,
        )
    }

    private var scheduler: RegionScheduler? = null

    @Volatile
    private var depsOverride: RegionDeps? = null

    /** Gives the offline engine other dependencies than the app's (tests); must be called before the first region is used. */
    fun useDeps(deps: RegionDeps) { depsOverride = deps }

    /** Where background downloads are queued; tests put an inline one here before the first download. */
    fun useScheduler(s: RegionScheduler) { scheduler = s }

    private val segmentSizes by lazy { SegmentSizes(services) }

    val engine: RegionEngine by lazy {
        RegionEngine(files, depsOverride ?: deps(), services::reloadRegions)
    }

    /** The offline regions of the app (`Services.regions`). */
    val offline: AtlasRegionManager by lazy {
        AtlasRegionManager(
            files, engine,
            scheduler = { scheduler ?: WorkRegionScheduler(services.context).also { scheduler = it } },
            wifiOnly = { wifiOnlyNow },
            scope = services.scope,
        ).also { start() }
    }

    val trail: TrailStore by lazy { TrailStore(File(services.dirs.files, "trail/trail.csv")) }
    val pressure: PressureLog by lazy { PressureLog(File(services.dirs.files, "pressure/pressure.csv")) }

    private var started = false

    /** The "Wi-Fi only" setting as last seen, so a download can be queued from the screen without waiting on the settings file. */
    @Volatile
    private var wifiOnlyNow = true

    @Synchronized
    private fun start() {
        if (started) return
        started = true
        services.scope.launch(Dispatchers.IO) { engine.refresh() }
        services.scope.launch { services.settings.flow(MineKeys.WIFI_ONLY, true).collect { wifiOnlyNow = it } }
        // Voice settings: only a change from the defaults wakes the voice (the speech engine starts on first use).
        services.scope.launch {
            services.settings.flow(MineKeys.VOICE_MUTED, false).collect { muted -> if (muted) services.go.setMuted(true) }
        }
        services.scope.launch {
            services.settings.flow(MineKeys.HAPTICS, true).collect { on -> if (!on) (services.voice as? app.atlas.ui.go.TtsVoice)?.setHaptics(false) }
        }
        // Where you have been: only while the setting is on, from the fixes the phone already has.
        services.scope.launch(Dispatchers.IO) {
            services.settings.flow(MineKeys.TRAIL, false).collectLatest { on ->
                if (on) services.location.last.collect { fix -> if (fix != null) trail.record(fix.location, fix.time) }
            }
        }
        // Automatic downloads: around home and work and along the trip being guided, once each, while the setting is on.
        services.scope.launch(Dispatchers.IO) {
            services.settings.flow(MineKeys.AUTO_REGIONS, false).collectLatest { on ->
                if (on) kotlinx.coroutines.flow.combine(services.go.store.home, services.go.store.work, trips()) { h, w, t -> Triple(h, w, t) }
                    .collectLatest { (h, w, t) -> autoDownload(h?.at, w?.at, t) }
            }
        }
        // The barometer's log: one reading every 15 minutes in the background while the setting is on.
        if (!services.config.offline) services.scope.launch {
            services.settings.flow(MineKeys.PRESSURE, false).collect { on -> PressureWorker.schedule(services.context, on) }
        }
    }

    /** The destination's name and the points of the trip being guided, or null with none; changes when the destination does. */
    private fun trips(): kotlinx.coroutines.flow.Flow<Pair<String?, List<app.atlas.geo.LatLon>>?> =
        services.go.nav.flatMapLatest { s -> s?.view?.let { v -> kotlinx.coroutines.flow.flow<Pair<String?, List<app.atlas.geo.LatLon>>?> { v.collect { emit(s.destinationName to it.route.points) } } } ?: flowOf(null) }
            .distinctUntilChanged { a, b -> a?.second?.lastOrNull() == b?.second?.lastOrNull() }

    /** Asks for the regions around [home], [work] and [trip] that are not on the phone and were not asked for before. */
    private suspend fun autoDownload(home: app.atlas.geo.LatLon?, work: app.atlas.geo.LatLon?, trip: Pair<String?, List<app.atlas.geo.LatLon>>?) {
        val ctx = services.context
        val done = services.settings.flow(MineKeys.AUTO_DONE, emptySet()).first()
        val tripName = trip?.first?.let { ctx.getString(app.atlas.R.string.mine_picker_name_route, it) } ?: ctx.getString(app.atlas.R.string.mine_picker_name_route_plain)
        val wanted = AutoRegions.plan(
            home, work, trip?.second.orEmpty(), offline.details.value.map { it.meta.bbox }, done,
            ctx.getString(app.atlas.R.string.mine_auto_home), ctx.getString(app.atlas.R.string.mine_auto_work), tripName, ctx.getString(app.atlas.R.string.mine_picker_name_part),
        )
        if (wanted.isEmpty()) return
        services.settings.set(MineKeys.AUTO_DONE, done + wanted.map { it.key })
        val taken = offline.details.value.map { it.id }.toMutableSet()
        for (w in wanted) {
            val name = PickerModel.unique(w.name, taken)
            taken += RegionEngine.idFor(name)
            offline.download(app.atlas.services.RegionRequest(name, w.bbox, setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.ROUTING, RegionLayer.VOICE)))
        }
    }

    private fun deps() = RegionDeps(
        packs = { packs.all() },
        packDownloader = PackDownloader(services.http.client),
        importPlaces = { bbox, name, target, report ->
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val store = PlaceStore.open(target)
                try {
                    RegionImport(services.overpass).import(bbox, store, name = name) { p -> report(p.fraction.toFloat()) }
                } finally {
                    store.close()
                }
            }
        },
        segments = services.segments,
        segmentSizes = { segmentSizes.get() },
        mapBuilder = mapBuilder ?: if (services.config.offline) null else { id, name, bbox, target, report ->
            withContext(Dispatchers.IO) { services.mapData.buildRegion(id, name, bbox, target).also { report(1f) } }
        },
        voices = { runCatching { kotlinx.coroutines.runBlocking { services.settings.flow(MineKeys.VOICE_LANGS, emptySet()).first() } }.getOrDefault(emptySet()).sorted() },
        clock = { services.clock.millis() },
    )

    /**
     * Builds a region's map pack on the phone, in place of the app's own builder ([MapData.buildRegion]: Overpass, as the
     * map builds the places it shows); tests set it before the first region is used.
     */
    @Volatile
    var mapBuilder: (suspend (id: String, name: String, bbox: app.atlas.geo.BBox, target: File, report: (Float) -> Unit) -> Long)? = null
}

/** The packs the offline regions are taken from: the published list, offline none. */
class PackLists(private val catalog: PackCatalog, private val offline: Boolean) {
    /** Every pack the list offers, or what was kept from the last time; empty when neither can be had. */
    suspend fun all(refresh: Boolean = false): List<Pack> {
        if (offline) return emptyList()
        return runCatching { catalog.packs(refresh) }.getOrDefault(emptyList())
    }
}

/** The BRouter tiles' remote sizes, read once from their index and kept for the session. */
internal class SegmentSizes(private val services: Services) {
    @Volatile
    private var sizes: Map<String, Long>? = null

    suspend fun get(): Map<String, Long> = sizes ?: withContext(Dispatchers.IO) {
        if (services.config.offline) return@withContext emptyMap()
        runCatching { services.segments.remoteIndex().associate { it.name to it.bytes } }.getOrDefault(emptyMap()).also { sizes = it }
    }
}

/** Reads the barometer once, in the background, every 15 minutes while "pressure for the forecast" is on. */
class PressureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val hub = applicationContext.services.mine
        val hPa = Barometers.read(applicationContext) ?: return Result.success()
        hub.pressure.add(hPa, applicationContext.services.clock.instant())
        return Result.success()
    }

    companion object {
        private const val NAME = "atlas-pressure"

        fun schedule(context: Context, on: Boolean) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                if (on) wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PressureWorker>(15, TimeUnit.MINUTES).build())
                else wm.cancelUniqueWork(NAME)
            }
        }
    }
}

/** The phone's barometer. */
object Barometers {
    /** True when this phone has a pressure sensor. */
    fun available(context: Context): Boolean = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    /** One reading in hPa, or null when there is no sensor or none answered within a few seconds. */
    suspend fun read(context: Context): Double? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        return withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0].toDouble())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }
}
