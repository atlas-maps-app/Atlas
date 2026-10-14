package app.atlas.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import app.atlas.geo.LatLon
import app.atlas.model.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.Executor

/** How often and how precisely to ask for fixes: turn-by-turn, the map's blue dot, or a light background watch. */
enum class LocationQuality(val intervalMillis: Long, val minIntervalMillis: Long, val minDistanceM: Float, val quality: Int) {
    NAVIGATION(1_000, 500, 0f, LocationRequestCompat.QUALITY_HIGH_ACCURACY),
    MAP(2_000, 1_000, 2f, LocationRequestCompat.QUALITY_HIGH_ACCURACY),
    LOW_POWER(15_000, 5_000, 25f, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY),
}

/** One satellite as the GNSS chip reports it. */
data class Satellite(val svid: Int, val constellation: Int, val cn0: Float, val azimuth: Float, val elevation: Float, val usedInFix: Boolean)

/** What the GNSS chip sees: every satellite, and how many are used in the fix. */
data class GnssInfo(val satellites: List<Satellite>, val time: Instant) {
    val used: Int get() = satellites.count { it.usedInFix }
    val visible: Int get() = satellites.size
}

/**
 * Where the phone is, without Google Play services: [fixes] while collected (one registration shared by all
 * collectors per [LocationQuality]), the [last] fix known, and the GNSS satellites ([gnss]) while the GPS runs.
 */
interface LocationSource {
    fun fixes(quality: LocationQuality = LocationQuality.MAP): Flow<Fix>
    val last: StateFlow<Fix?>
    val gnss: StateFlow<GnssInfo?>

    /** Whether Atlas may read the location; call [refreshPermission] after the user answers the permission dialog. */
    val permitted: StateFlow<Boolean>
    fun refreshPermission()

    /** True for the demo position (CI and screenshots) rather than the phone's own. */
    val isDemo: Boolean get() = false
}

/**
 * The phone's location through `LocationManagerCompat`: the fused provider when the system has one, else GPS and the
 * network provider together, keeping whichever fix is better ([FixChooser]).
 */
class AndroidLocationSource(private val context: Context, private val scope: CoroutineScope) : LocationSource {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private val lastState = MutableStateFlow<Fix?>(null)
    private val gnssState = MutableStateFlow<GnssInfo?>(null)
    private val permission = MutableStateFlow(hasPermission())
    private val shared = EnumMap<LocationQuality, Flow<Fix>>(LocationQuality::class.java)

    override val last: StateFlow<Fix?> = lastState.asStateFlow()
    override val gnss: StateFlow<GnssInfo?> = gnssState.asStateFlow()
    override val permitted: StateFlow<Boolean> = permission.asStateFlow()

    init {
        if (permission.value) lastKnown()?.let { lastState.value = it }
    }

    override fun refreshPermission() {
        permission.value = hasPermission()
        if (permission.value && lastState.value == null) lastKnown()?.let { lastState.value = it }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun fixes(quality: LocationQuality): Flow<Fix> = synchronized(shared) {
        shared.getOrPut(quality) {
            permission.flatMapLatest { ok -> if (ok && manager != null) updates(manager, quality) else emptyFlow() }
                .onEach { lastState.value = it }
                // Once nobody watches, forget the replayed fix: a new watcher gets fresh fixes, not an old one as new
                // (the last known stays in [last], where it is known as such).
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 3_000, replayExpirationMillis = 0), replay = 1)
        }
    }

    /** The providers to listen to: fused alone when present, else GPS and network (whichever exist). */
    private fun providers(m: LocationManager): List<String> =
        if (LocationManagerCompat.hasProvider(m, FUSED)) listOf(FUSED)
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { LocationManagerCompat.hasProvider(m, it) }

    @SuppressLint("MissingPermission")
    private fun updates(m: LocationManager, quality: LocationQuality): Flow<Fix> = callbackFlow {
        val request = LocationRequestCompat.Builder(quality.intervalMillis)
            .setQuality(quality.quality)
            .setMinUpdateIntervalMillis(quality.minIntervalMillis)
            .setMinUpdateDistanceMeters(quality.minDistanceM)
            .build()
        val chooser = FixChooser()
        val listeners = ArrayList<LocationListenerCompat>()
        for (p in providers(m)) {
            val l = LocationListenerCompat { loc -> chooser.offer(loc.toFix(), p)?.let { trySend(it) } }
            listeners += l
            runCatching { LocationManagerCompat.requestLocationUpdates(m, p, request, executor, l) }
        }
        val gnssCallback = object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                gnssState.value = GnssInfo(
                    List(status.satelliteCount) { i ->
                        Satellite(status.getSvid(i), status.getConstellationType(i), status.getCn0DbHz(i), status.getAzimuthDegrees(i), status.getElevationDegrees(i), status.usedInFix(i))
                    },
                    Instant.now(),
                )
            }
        }
        val gnssOn = quality != LocationQuality.LOW_POWER &&
            runCatching { LocationManagerCompat.registerGnssStatusCallback(m, executor, gnssCallback) }.getOrDefault(false)
        awaitClose {
            listeners.forEach { runCatching { LocationManagerCompat.removeUpdates(m, it) } }
            if (gnssOn) {
                runCatching { LocationManagerCompat.unregisterGnssStatusCallback(m, gnssCallback) }
                // The satellites seen while the GPS ran are not what it sees now that it is off.
                gnssState.value = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Fix? {
        val m = manager ?: return null
        if (!hasPermission()) return null
        val chooser = FixChooser()
        var best: Fix? = null
        for (p in listOf(FUSED, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = runCatching { if (LocationManagerCompat.hasProvider(m, p)) m.getLastKnownLocation(p) else null }.getOrNull() ?: continue
            chooser.offer(loc.toFix(), p)?.let { best = it }
        }
        return best
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** `LocationManager.FUSED_PROVIDER` (API 31), by value so it reads on older systems too. */
        const val FUSED = "fused"
    }
}

/** An Android [Location] as Atlas's [Fix]; absent speed, bearing and altitude stay null. */
fun Location.toFix(): Fix = Fix(
    location = LatLon(latitude, longitude),
    time = Instant.ofEpochMilli(time),
    accuracy = if (hasAccuracy()) accuracy.toDouble() else 50.0,
    speed = if (hasSpeed()) speed.toDouble() else null,
    bearing = if (hasBearing()) bearing.toDouble() else null,
    altitude = if (hasAltitude()) altitude else null,
)

/**
 * Picks the better of fixes from several providers: a much newer fix wins, then a more accurate one, then a newer one
 * that is not less accurate; from the same provider a newer fix wins unless it is far less accurate.
 */
class FixChooser {
    private var best: Fix? = null
    private var bestProvider: String? = null

    /** Offers [fix] from [provider]; returns it when it becomes the best, else null. */
    @Synchronized
    fun offer(fix: Fix, provider: String): Fix? {
        if (!isBetter(fix, provider)) return null
        best = fix
        bestProvider = provider
        return fix
    }

    private fun isBetter(fix: Fix, provider: String): Boolean {
        val current = best ?: return true
        val dt = fix.time.toEpochMilli() - current.time.toEpochMilli()
        if (dt > SIGNIFICANT_MILLIS) return true
        if (dt < -SIGNIFICANT_MILLIS) return false
        val accuracyDelta = fix.accuracy - current.accuracy
        val newer = dt > 0
        return when {
            accuracyDelta < 0 -> true
            newer && accuracyDelta <= 0 -> true
            newer && accuracyDelta <= MUCH_LESS_ACCURATE_M && provider == bestProvider -> true
            else -> false
        }
    }

    private companion object {
        const val SIGNIFICANT_MILLIS = 15_000L
        const val MUCH_LESS_ACCURATE_M = 200.0
    }
}

/**
 * A phone standing still at [at], for CI and screenshots: a fix every second with a small, honest accuracy.
 * `atlas.ci=true` puts the app here (the Westerkerk, Amsterdam).
 */
class DemoLocationSource(val at: LatLon, private val accuracyM: Double = 6.0, private val clock: () -> Instant = Instant::now) : LocationSource {
    private val lastState = MutableStateFlow<Fix?>(Fix(at, clock(), accuracyM))
    override val last: StateFlow<Fix?> = lastState.asStateFlow()
    override val gnss: StateFlow<GnssInfo?> = MutableStateFlow(null)
    override val permitted: StateFlow<Boolean> = MutableStateFlow(true)
    override val isDemo: Boolean get() = true

    override fun refreshPermission() = Unit

    override fun fixes(quality: LocationQuality): Flow<Fix> = flow {
        while (true) {
            val f = Fix(at, clock(), accuracyM, speed = 0.0)
            lastState.value = f
            emit(f)
            delay(quality.intervalMillis)
        }
    }
}

/** The location source the app uses, switchable at run time between the phone's own and the demo one. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableLocation(initial: LocationSource, scope: CoroutineScope) : LocationSource {
    private val current = MutableStateFlow(initial)

    /** The source in use now. */
    val source: LocationSource get() = current.value

    fun use(source: LocationSource) {
        current.value = source
    }

    override fun fixes(quality: LocationQuality): Flow<Fix> = current.flatMapLatest { it.fixes(quality) }
    override val last: StateFlow<Fix?> = current.flatMapLatest { it.last }.stateIn(scope, SharingStarted.Eagerly, initial.last.value)
    override val gnss: StateFlow<GnssInfo?> = current.flatMapLatest { it.gnss }.stateIn(scope, SharingStarted.Eagerly, initial.gnss.value)
    override val permitted: StateFlow<Boolean> = current.flatMapLatest { it.permitted }.stateIn(scope, SharingStarted.Eagerly, initial.permitted.value)
    override fun refreshPermission() = current.value.refreshPermission()
    override val isDemo: Boolean get() = current.value.isDemo
}
