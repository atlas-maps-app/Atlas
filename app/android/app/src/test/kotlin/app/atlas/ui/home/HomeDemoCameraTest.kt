package app.atlas.ui.home

import android.os.Looper
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.HomeView
import app.atlas.services.SavedCamera
import app.atlas.testing.testServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * CI's demo and the bench shortcut open the home on the Westerkerk (the frame the emulator measures) even over a saved
 * view, and never save the demo's camera over the view the user left, until the user moves the map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeDemoCameraTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun theDemoBeatsASavedViewAndLeavesItAlone() {
        val s = testServices()
        val left = SavedCamera(LatLon(51.9225, 4.4792), 15.0, 10.0, 40.0)
        runBlocking {
            s.settings.saveCamera(left)
            s.startCamera.await()
        }
        assertEquals(left, s.startCameraNow())

        s.ciDemo = true
        val vm = HomeViewModel(s, null, null)
        assertEquals(HomeView.westerkerk, vm.map.camera)
        // the camera settles (the saver waits 800 ms): the demo's view is not saved
        vm.onCameraMove(vm.map.camera, byUser = false)
        repeat(20) { idle(100); Thread.sleep(10) }
        assertEquals(left, runBlocking { s.settings.camera.first() })

        // the user moves the map: from then on it is theirs, and saved
        val moved = CameraPosition(LatLon(52.3731, 4.8826), 17.5, 30.0, 45.0)
        vm.onCameraMove(moved, byUser = true)
        val want = SavedCamera(moved.target, 17.5, 30.0, 45.0)
        val end = System.currentTimeMillis() + 10_000
        while (runBlocking { s.settings.camera.first() } != want && System.currentTimeMillis() < end) {
            idle(100)
            Thread.sleep(20)
        }
        assertEquals(want, runBlocking { s.settings.camera.first() })
    }

    @Test
    fun withoutTheDemoTheSavedViewWins() {
        val s = testServices()
        val left = SavedCamera(LatLon(51.9225, 4.4792), 15.0, 10.0, 40.0)
        runBlocking {
            s.settings.saveCamera(left)
            s.startCamera.await()
        }
        val vm = HomeViewModel(s, null, null)
        assertEquals(CameraPosition(left.target, 15.0, 10.0, 40.0), vm.map.camera)
        assertTrue(vm.focus == null)
    }
}
