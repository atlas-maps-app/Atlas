package app.atlas.services

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import app.atlas.model.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** The phone's own location: fixes while watched, nothing stale handed out as new when watching starts again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLocationSourceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(LocationManager::class.java)

    @After
    fun tearDown() = scope.cancel()

    /** Lets the main looper deliver (listeners run on the main executor) until [done] or the time is up. */
    private fun until(timeoutMs: Long = 5_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun simulate(lat: Double, lon: Double) {
        for (p in listOf(AndroidLocationSource.FUSED, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                shadowOf(manager).simulateLocation(
                    Location(p).apply {
                        latitude = lat
                        longitude = lon
                        accuracy = 5f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    },
                )
            }
        }
    }

    @Test
    fun aNewWatcherIsNotHandedAnOldFixAsNew() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val source = AndroidLocationSource(app, scope)
        assertTrue(source.permitted.value)
        val got = AtomicReference<Fix?>(null)
        val watcher = scope.launch { source.fixes().collect { got.set(it) } }
        // Registration happens off the main thread; keep offering a fix until it arrives.
        val end = System.currentTimeMillis() + 5_000
        while (got.get() == null && System.currentTimeMillis() < end) {
            simulate(52.37452, 4.88377)
            until(200) { got.get() != null }
        }
        assertEquals(52.37452, got.get()!!.location.lat, 1e-9)
        assertEquals(52.37452, source.last.value!!.location.lat, 1e-9)
        // Nobody watches for longer than the grace period: the GPS stops.
        watcher.cancel()
        Thread.sleep(5_000)
        // Watching again, there is no new fix yet: the old one must not come back as if it were current.
        val again = runBlocking { withTimeoutOrNull(500) { source.fixes().first() } }
        assertNull(again)
        // The last known fix is still there for whoever asks for it as such.
        assertEquals(52.37452, source.last.value!!.location.lat, 1e-9)
    }
}
